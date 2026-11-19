package dev.luke;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import com.mojang.brigadier.builder.ArgumentBuilder;

/** Singleplayer command bridge. File bytes live in actual book items, not the index. */
public final class WorldvaultMod implements ModInitializer {
    @Override public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(Commands.literal("worldvault")
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
                                    .executes(ctx -> restore(ctx.getSource(), StringArgumentType.getString(ctx, "name"))))));
        });
    }

    /** {@code chests <c1> <c2> [... up to <c8>]}: an explicit ordered list of chests. */
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
    private static final int MAX_RANGE_VOLUME = 4096;
    private static final int MAX_CHESTS = 64;

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
            long volume = (long) (Math.abs(from.getX() - to.getX()) + 1) * (Math.abs(from.getY() - to.getY()) + 1)
                    * (Math.abs(from.getZ() - to.getZ()) + 1);
            if (volume > MAX_RANGE_VOLUME) throw new IllegalArgumentException("Range is too large (limit " + MAX_RANGE_VOLUME + " blocks)");
            ServerLevel level = source.getLevel();
            List<BlockPos> found = new ArrayList<>();
            java.util.Set<BlockPos> covered = new java.util.HashSet<>();
            for (int x = Math.min(from.getX(), to.getX()); x <= Math.max(from.getX(), to.getX()); x++)
                for (int y = Math.min(from.getY(), to.getY()); y <= Math.max(from.getY(), to.getY()); y++)
                    for (int z = Math.min(from.getZ(), to.getZ()); z <= Math.max(from.getZ(), to.getZ()); z++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (covered.contains(p) || !(level.getBlockEntity(p) instanceof ChestBlockEntity chest)) continue;
                        covered.add(p);
                        if (chest.getBlockState().getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                            covered.add(p.relative(ChestBlock.getConnectedDirection(chest.getBlockState())));
                        }
                        found.add(p);
                        if (found.size() > MAX_CHESTS) throw new IllegalArgumentException("Too many chests in range (limit " + MAX_CHESTS + ")");
                    }
            if (found.isEmpty()) throw new IllegalArgumentException("No chests in that range");
            return store(source, name, found);
        } catch (Exception ex) {
            source.sendFailure(Component.literal("Worldvault: " + ex.getMessage()));
            return 0;
        }
    }

    private static Path root(CommandSourceStack source) {
        return source.getServer().getWorldPath(LevelResource.ROOT).resolve("worldvault");
    }
    private static boolean singleplayer(CommandSourceStack source) {
        if (source.getServer().isSingleplayer()) return true;
        source.sendFailure(Component.literal("Worldvault currently works in singleplayer only."));
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
                        + first.dimension() + " " + first.x() + " " + first.y() + " " + first.z() + ")"), false);
            }
            return entries.size();
        } catch (IOException ex) {
            source.sendFailure(Component.literal("Index read failed: " + ex.getMessage()));
            return 0;
        }
    }

    private static VaultService.Target target(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) {
            throw new IllegalArgumentException("No chest at " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        }
        String dimension = level.dimension().identifier().toString();
        boolean paired = chest.getBlockState().getValue(ChestBlock.TYPE) != ChestType.SINGLE;
        if (!paired) return new VaultService.Target(dimension, pos, false, chest, List.of(pos));
        ChestBlockEntity[] halves = WorldvaultChest.halves(chest, level::getBlockEntity);
        return new VaultService.Target(dimension, pos, true, WorldvaultChest.combine(halves[0], halves[1]),
                List.of(halves[0].getBlockPos(), halves[1].getBlockPos()));
    }

    private static int store(CommandSourceStack source, String name, List<BlockPos> chests) {
        if (!singleplayer(source)) return 0;
        try {
            ServerPlayer player = source.getPlayerOrException();
            ServerLevel level = source.getLevel();
            for (BlockPos pos : chests) {
                if (player.position().distanceToSqr(pos.getCenter()) > 64) throw new IllegalArgumentException("Stand near your chests");
            }
            if (name.isBlank() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
                throw new IllegalArgumentException("Use a plain file name");
            }
            if (name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255) {
                throw new IllegalArgumentException("Filename is too long");
            }
            Path inputDir = root(source).resolve("in").toAbsolutePath().normalize();
            Path input = inputDir.resolve(name).normalize();
            if (!input.startsWith(inputDir) || !Files.isRegularFile(input)) {
                throw new IllegalArgumentException("File not found in worldvault/in");
            }
            List<VaultService.Target> targets = new ArrayList<>();
            for (BlockPos pos : chests) targets.add(target(level, pos));
            VaultService.Plan plan = VaultService.prepare(name, Files.readAllBytes(input), targets,
                    new VaultIndexStore(source.getServer().getWorldPath(LevelResource.ROOT)));
            int books = plan.totalBooks();
            int blanks = 0;
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                if (player.getInventory().getItem(slot).is(Items.WRITABLE_BOOK)) blanks += player.getInventory().getItem(slot).getCount();
            }
            if (blanks < books) throw new IllegalArgumentException("Need " + books + " book-and-quills in your inventory");
            VaultService.commit(plan); // validated first; a failed write is rolled back and consumes nothing
            int left = books;
            for (int slot = 0; slot < player.getInventory().getContainerSize() && left > 0; slot++) {
                var stack = player.getInventory().getItem(slot);
                if (stack.is(Items.WRITABLE_BOOK)) {
                    int taken = Math.min(stack.getCount(), left);
                    stack.shrink(taken);
                    left -= taken;
                }
            }
            player.getInventory().setChanged();
            int chestCount = plan.used().size();
            source.sendSuccess(() -> Component.literal("Stored " + name + " in " + books + " books across "
                    + chestCount + (chestCount == 1 ? " chest." : " chests.")), false);
            return 1;
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
            Path outputDir = root(source).resolve("out").toAbsolutePath().normalize();
            Path output = outputDir.resolve(entry.name()).normalize();
            if (!output.startsWith(outputDir)) throw new IOException("Unsafe output name");
            if (Files.exists(output)) throw new IOException("worldvault/out already has " + entry.name() + "; move it first");
            // Every chest is read and verified before anything is written to disk.
            WorldvaultCodec.File file = VaultService.restore(entry, chunk -> openChunk(source, chunk));
            Files.createDirectories(outputDir);
            Files.write(output, file.bytes(), StandardOpenOption.CREATE_NEW);
            source.sendSuccess(() -> Component.literal("Restored " + file.name() + " to worldvault/out."), false);
            return 1;
        } catch (Exception ex) {
            source.sendFailure(Component.literal("Worldvault: " + ex.getMessage()));
            return 0;
        }
    }

    private static Container openChunk(CommandSourceStack source, VaultIndexV2.Chunk chunk) throws IOException {
        ServerLevel level = null;
        for (ServerLevel candidate : source.getServer().getAllLevels()) {
            if (candidate.dimension().identifier().toString().equals(chunk.dimension())) { level = candidate; break; }
        }
        if (level == null) throw new IOException("That dimension is unavailable");
        BlockPos chestPos = new BlockPos(chunk.x(), chunk.y(), chunk.z());
        // Load the saved chunk, including when the player is on the other side of the world.
        level.getChunk(chestPos.getX() >> 4, chestPos.getZ() >> 4);
        if (!(level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest)) {
            throw new IOException("Chest at " + chunk.x() + " " + chunk.y() + " " + chunk.z()
                    + " is gone; restore a world backup or bring the books back");
        }
        ServerLevel halfLevel = level;
        return WorldvaultChest.restoreSpace(chunk.doubleChest(), chest, pos -> {
            halfLevel.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            return halfLevel.getBlockEntity(pos);
        });
    }
}
