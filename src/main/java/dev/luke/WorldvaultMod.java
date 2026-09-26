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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

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
                                                    BlockPosArgument.getLoadedBlockPos(ctx, "chest"))))))
                    .then(Commands.literal("restore")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> restore(ctx.getSource(), StringArgumentType.getString(ctx, "name"))))));
        });
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
            List<VaultIndex.Entry> entries = new VaultIndex(source.getServer().getWorldPath(LevelResource.ROOT)).load();
            if (entries.isEmpty()) source.sendSuccess(() -> Component.literal("No files in this world yet."), false);
            for (VaultIndex.Entry entry : entries) {
                source.sendSuccess(() -> Component.literal(entry.name() + " (" + entry.books() + " books, "
                        + entry.dimension() + " " + entry.x() + " " + entry.y() + " " + entry.z() + ")"), false);
            }
            return entries.size();
        } catch (IOException ex) {
            source.sendFailure(Component.literal("Index read failed: " + ex.getMessage()));
            return 0;
        }
    }
    private static int store(CommandSourceStack source, String name, BlockPos pos) {
        if (!singleplayer(source)) return 0;
        try {
            ServerPlayer player = source.getPlayerOrException();
            if (player.position().distanceToSqr(pos.getCenter()) > 64) throw new IllegalArgumentException("Stand near your chest");
            ServerLevel level = source.getLevel();
            if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) {
                throw new IllegalArgumentException("Place an empty single chest at those coordinates");
            }
            if (chest.getBlockState().getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                throw new IllegalArgumentException("Use a single chest; paired chests are not supported yet");
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
            long size = Files.size(input);
            if (size > 27L * 2048 - 269) throw new IllegalArgumentException("File exceeds one chest (about 54 KB)");
            List<WorldvaultBooks.Book> books = WorldvaultBooks.pack(name, Files.readAllBytes(input));
            int blanks = 0;
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                if (player.getInventory().getItem(slot).is(Items.WRITABLE_BOOK)) blanks += player.getInventory().getItem(slot).getCount();
            }
            if (blanks < books.size()) throw new IllegalArgumentException("Need " + books.size() + " book-and-quills in your inventory");
            if (books.size() > chest.getContainerSize()) throw new IllegalArgumentException("Need a larger chest");
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                if (!chest.getItem(slot).isEmpty()) throw new IllegalArgumentException("Chest must be empty");
            }
            VaultIndex index = new VaultIndex(source.getServer().getWorldPath(LevelResource.ROOT));
            for (VaultIndex.Entry old : index.load()) {
                if (old.name().equals(name)) throw new IllegalArgumentException("A file with that name is already stored");
                if (old.dimension().equals(level.dimension().identifier().toString())
                        && old.x() == pos.getX() && old.y() == pos.getY() && old.z() == pos.getZ()) {
                    throw new IllegalArgumentException("Chest already registered");
                }
            }
            VaultIndex.Entry entry = new VaultIndex.Entry(name, level.dimension().identifier().toString(),
                    pos.getX(), pos.getY(), pos.getZ(), books.size());
            // Reserve the locator before writing books. Never consume the player's books on a failed write.
            index.add(entry);
            try {
                WorldvaultChest.write(chest, books);
            } catch (RuntimeException ex) {
                try { index.remove(entry); }
                catch (IOException rollback) { ex.addSuppressed(rollback); }
                throw ex;
            }
            int left = books.size();
            for (int slot = 0; slot < player.getInventory().getContainerSize() && left > 0; slot++) {
                var stack = player.getInventory().getItem(slot);
                if (stack.is(Items.WRITABLE_BOOK)) {
                    int taken = Math.min(stack.getCount(), left);
                    stack.shrink(taken);
                    left -= taken;
                }
            }
            player.getInventory().setChanged();
            source.sendSuccess(() -> Component.literal("Stored " + name + " in " + books.size() + " books."), false);
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
            VaultIndex.Entry found = null;
            for (VaultIndex.Entry entry : new VaultIndex(source.getServer().getWorldPath(LevelResource.ROOT)).load()) {
                if (entry.name().equals(name)) { found = entry; break; }
            }
            if (found == null) throw new IllegalArgumentException("No file by that name in this world");
            VaultIndex.Entry entry = found;
            ServerLevel level = null;
            for (ServerLevel candidate : source.getServer().getAllLevels()) {
                if (candidate.dimension().identifier().toString().equals(entry.dimension())) { level = candidate; break; }
            }
            if (level == null) throw new IllegalArgumentException("That dimension is unavailable");
            BlockPos chestPos = new BlockPos(entry.x(), entry.y(), entry.z());
            // Load the saved chunk, including when the player is on the other side of the world.
            level.getChunk(chestPos.getX() >> 4, chestPos.getZ() >> 4);
            if (!(level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest)) {
                throw new IllegalArgumentException("Chest is gone; restore a world backup or bring the books back");
            }
            if (chest.getBlockState().getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                throw new IOException("Vault chest was paired with another chest; separate it first");
            }
            WorldvaultCodec.File file = WorldvaultChest.read(chest);
            if (!file.name().equals(entry.name())) throw new IOException("Books do not match the index");
            Path outputDir = root(source).resolve("out").toAbsolutePath().normalize();
            Files.createDirectories(outputDir);
            Path output = outputDir.resolve(file.name()).normalize();
            if (!output.startsWith(outputDir)) throw new IOException("Unsafe output name");
            Files.write(output, file.bytes(), StandardOpenOption.CREATE_NEW);
            source.sendSuccess(() -> Component.literal("Restored " + file.name() + " to worldvault/out."), false);
            return 1;
        } catch (Exception ex) {
            source.sendFailure(Component.literal("Worldvault: " + ex.getMessage()));
            return 0;
        }
    }
}
