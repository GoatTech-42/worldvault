package dev.luke;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.mojang.brigadier.builder.ArgumentBuilder;

/**
 * Singleplayer command bridge. File bytes live in actual book items, not the index.
 *
 * <p>Who can run it, from the code paths (not from a live LAN or dedicated-server test): the commands are not
 * registered on a dedicated server; on an integrated server they need owner permission level (4) and the source
 * must be the player hosting the world, so on a world opened to LAN other players are refused even with cheats
 * on. Every command runs on the server thread; see {@link VaultService} for what that means for hoppers.
 */
public final class WorldvaultMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("worldvault");

    @Override public void onInitialize() {
        if (!registersOn(FabricLoader.getInstance().getEnvironmentType())) {
            LOGGER.info("Worldvault: dedicated server, commands not registered");
            return;
        }
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> register(dispatcher));
    }

    /**
     * Whether the commands are registered in this environment. Never on a dedicated server: the commands read and
     * write files on the machine running the server, which on a dedicated server belongs to someone else.
     */
    static boolean registersOn(EnvType environment) {
        return environment != EnvType.SERVER;
    }

    /**
     * Owner level (4). The commands read files from and write files to the host's disk ({@code worldvault/in} and
     * {@code worldvault/out}) and edit the index, which is more than level 2 (/give, /setblock) grants. In
     * singleplayer the host has level 4 when cheats are allowed, so nothing changes for the host.
     */
    static final PermissionCheck PERMISSION = Commands.LEVEL_OWNERS;

    /**
     * Why a command source may not run Worldvault, or null when it may. Only the singleplayer host: on an
     * integrated server opened to LAN, other players are refused even if they hold a high permission level,
     * because the files belong to the host's machine.
     */
    static String refusal(boolean dedicatedServer, boolean singleplayer, boolean owner) {
        if (dedicatedServer || !singleplayer) return "Worldvault currently works in singleplayer only.";
        if (!owner) return "Only the player hosting this world can use Worldvault.";
        return null;
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("worldvault")
                .requires(Commands.hasPermission(PERMISSION))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("store")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("chest", BlockPosArgument.blockPos())
                                        .executes(ctx -> store(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"),
                                                List.of(BlockPosArgument.getLoadedBlockPos(ctx, "chest")))))
                                .then(Commands.literal("range")
                                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                        .executes(ctx -> storeRange(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "name"),
                                                                BlockPosArgument.getLoadedBlockPos(ctx, "from"),
                                                                BlockPosArgument.getLoadedBlockPos(ctx, "to"))))))
                                .then(chestList())))
                .then(Commands.literal("restore")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> restore(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("repair")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> repair(ctx.getSource(), StringArgumentType.getString(ctx, "name"))))));
    }

    /**
     * {@code chests <c1> <c2> [... up to <c8>]}: an explicit ordered list of chests. The list stops at 8 because
     * every extra coordinate is another nested Brigadier node and another three numbers to type; {@code range}
     * covers larger vaults (up to {@link VaultTargets#MAX_CHESTS}) without either cost. Both still require every
     * chest within reach of the player, so the limits differ in typing, not in what can be stored.
     */
    private static ArgumentBuilder<CommandSourceStack, ?> chestList() {
        ArgumentBuilder<CommandSourceStack, ?> next = null;
        for (int i = MAX_LIST; i >= 1; i--) {
            var arg = Commands.argument("c" + i, BlockPosArgument.blockPos());
            if (i >= 2) arg.executes(ctx -> storeList(ctx));
            if (next != null) arg.then(next);
            next = arg;
        }
        return Commands.literal("chests").then(next);
    }
    private static final int MAX_LIST = 8;

    private static int storeList(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        List<BlockPos> list = new ArrayList<>();
        try {
            for (int i = 1; i <= MAX_LIST; i++) {
                try { list.add(BlockPosArgument.getLoadedBlockPos(ctx, "c" + i)); }
                catch (IllegalArgumentException absent) { break; }
            }
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ex) {
            ctx.getSource().sendFailure(Component.literal("Worldvault: " + ex.getMessage()));
            return 0;
        }
        return store(ctx.getSource(), StringArgumentType.getString(ctx, "name"), list);
    }

    private static int storeRange(CommandSourceStack source, String name, BlockPos from, BlockPos to) {
        if (!singleplayer(source)) return 0;
        try {
            return store(source, name, VaultTargets.scanRange(access(source.getLevel()), from, to));
        } catch (Exception ex) {
            source.sendFailure(Component.literal("Worldvault: " + ex.getMessage()));
            return 0;
        }
    }

    private static Path root(CommandSourceStack source) {
        return source.getServer().getWorldPath(LevelResource.ROOT).resolve("worldvault");
    }
    private static boolean singleplayer(CommandSourceStack source) {
        var server = source.getServer();
        ServerPlayer player = source.getPlayer();
        boolean owner = player != null && server.isSingleplayerOwner(player.nameAndId());
        String refused = refusal(server.isDedicatedServer(), server.isSingleplayer(), owner);
        if (refused == null) return true;
        source.sendFailure(Component.literal(refused));
        return false;
    }
    private static int list(CommandSourceStack source) {
        if (!singleplayer(source)) return 0;
        try {
            List<VaultIndexV2.Entry> entries = new VaultIndexStore(source.getServer().getWorldPath(LevelResource.ROOT)).load();
            if (entries.isEmpty()) source.sendSuccess(() -> Component.literal("No files in this world yet."), false);
            for (VaultIndexV2.Entry entry : entries) {
                VaultIndexV2.Chunk first = entry.chunks().get(0);
                int books = entry.chunks().stream().mapToInt(VaultIndexV2.Chunk::books).sum();
                source.sendSuccess(() -> Component.literal(entry.name() + " (" + books + " books, "
                        + entry.chunks().size() + (entry.chunks().size() == 1 ? " chest, " : " chests, ")
                        + (VaultService.isLegacy(entry) ? "v1, CRC32 only, " : "")
                        + first.dimension() + " " + first.x() + " " + first.y() + " " + first.z() + ")"), false);
            }
            return entries.size();
        } catch (IOException ex) {
            source.sendFailure(Component.literal("Index read failed: " + ex.getMessage()));
            return 0;
        }
    }

    private static VaultTargets.WorldAccess access(ServerLevel level) {
        return new VaultTargets.WorldAccess() {
            @Override public String dimension() { return level.dimension().identifier().toString(); }
            @Override public boolean isLoaded(BlockPos pos) { return level.hasChunkAt(pos); }
            @Override public net.minecraft.world.level.block.entity.BlockEntity blockEntity(BlockPos pos) {
                return level.getBlockEntity(pos);
            }
        };
    }

    private static int store(CommandSourceStack source, String name, List<BlockPos> chests) {
        if (!singleplayer(source)) return 0;
        try {
            ServerPlayer player = source.getPlayerOrException();
            ServerLevel level = source.getLevel();
            VaultTargets.checkReach(player.position(), chests);
            if (name.isBlank() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
                throw new IllegalArgumentException("Use a plain file name");
            }
            if (name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255) {
                throw new IllegalArgumentException("Filename is too long");
            }
            Path input = VaultService.inputFile(root(source).resolve("in"), name);
            List<VaultService.Target> targets = VaultTargets.targets(access(level), chests);
            long size = Files.size(input);
            if (size > VaultService.capacity(targets) || size > Integer.MAX_VALUE - 8) {
                throw new IllegalArgumentException("File is too large for the chests given");
            }
            VaultService.Plan plan = VaultService.prepare(name, Files.readAllBytes(input), targets,
                    new VaultIndexStore(source.getServer().getWorldPath(LevelResource.ROOT)));
            int books = plan.totalBooks();
            VaultService.commitAndTakeBooks(plan, player.getInventory());
            int chestCount = plan.used().size();
            source.sendSuccess(() -> Component.literal("Stored " + name + " in " + books + " books across "
                    + chestCount + (chestCount == 1 ? " chest." : " chests.")), false);
            return 1;
        } catch (VaultService.RollbackIncompleteException ex) {
            // already logged with every suppressed cleanup failure; tell the player which chests to empty
            source.sendFailure(Component.literal("Worldvault: " + ex.getMessage()));
            return 0;
        } catch (Exception ex) {
            source.sendFailure(Component.literal("Worldvault: " + ex.getMessage()));
            return 0;
        }
    }

    private static int restore(CommandSourceStack source, String name) {
        if (!singleplayer(source)) return 0;
        try {
            source.getPlayerOrException();
            VaultIndexV2.Entry found = null;
            for (VaultIndexV2.Entry entry : new VaultIndexStore(source.getServer().getWorldPath(LevelResource.ROOT)).load()) {
                if (entry.name().equals(name)) { found = entry; break; }
            }
            if (found == null) throw new IllegalArgumentException("No file by that name in this world");
            VaultIndexV2.Entry entry = found;
            // Every chest is read and verified before anything is written to disk.
            WorldvaultCodec.File file = VaultService.restoreTo(entry, chunk -> openChunk(source, chunk),
                    root(source).resolve("out"));
            String integrity = VaultService.integrity(entry);
            source.sendSuccess(() -> Component.literal("Restored " + file.name() + " to worldvault/out (" + integrity + ")."), false);
            return 1;
        } catch (Exception ex) {
            source.sendFailure(Component.literal("Worldvault: " + ex.getMessage()));
            return 0;
        }
    }

    /** Removes an index row only when none of its chests holds Worldvault books; otherwise reports and keeps it. */
    private static int repair(CommandSourceStack source, String name) {
        if (!singleplayer(source)) return 0;
        try {
            VaultIndexStore index = new VaultIndexStore(source.getServer().getWorldPath(LevelResource.ROOT));
            VaultIndexV2.Entry found = null;
            for (VaultIndexV2.Entry entry : index.load()) {
                if (entry.name().equals(name)) { found = entry; break; }
            }
            if (found == null) throw new IllegalArgumentException("No file by that name in this world");
            VaultService.Check check = VaultService.repair(found,
                    chunk -> VaultTargets.holdsVaultBooks(access(level(source, chunk)), chunk),
                    chunk -> openChunk(source, chunk), index);
            switch (check.verdict()) {
                case ORPHANED -> {
                    LOGGER.warn("Worldvault: removed orphaned index row {}: {}", name, check.detail());
                    source.sendSuccess(() -> Component.literal("Removed the index row for " + name + ": " + check.detail() + "."), false);
                    return 1;
                }
                case INTACT -> {
                    source.sendSuccess(() -> Component.literal(name + " is intact (" + check.detail() + "); nothing to repair."), false);
                    return 1;
                }
                default -> {
                    source.sendFailure(Component.literal("Worldvault: kept the index row for " + name + ". " + check.detail()));
                    return 0;
                }
            }
        } catch (Exception ex) {
            source.sendFailure(Component.literal("Worldvault: " + ex.getMessage()));
            return 0;
        }
    }

    private static ServerLevel level(CommandSourceStack source, VaultIndexV2.Chunk chunk) throws IOException {
        for (ServerLevel candidate : source.getServer().getAllLevels()) {
            if (candidate.dimension().identifier().toString().equals(chunk.dimension())) return candidate;
        }
        throw new IOException("That dimension is unavailable");
    }

    private static Container openChunk(CommandSourceStack source, VaultIndexV2.Chunk chunk) throws IOException {
        return VaultTargets.open(access(level(source, chunk)), chunk);
    }
}
