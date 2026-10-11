package dev.luke;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the game's own chest and book serialization, not a mock inventory. */
class WorldvaultChestPersistenceTest {
    @TempDir Path directory;

    @BeforeAll static void initializeGame() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void bytesSurviveCompressedWorldDataSaveAndReload() throws Exception {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var position = new BlockPos(12, 64, -5);
        var state = Blocks.CHEST.defaultBlockState();
        for (int size : new int[]{0, 1, 2031, 2032, 8192, 54000}) {
            byte[] original = new byte[size];
            new Random(42 + size).nextBytes(original);
            var chest = new ChestBlockEntity(position, state);
            WorldvaultChest.write(chest, WorldvaultBooks.pack("fixture.bin", original));
            Path saved = directory.resolve("chest-" + size + ".nbt");
            NbtIo.writeCompressed(chest.saveWithFullMetadata(registries), saved);
            var reloaded = BlockEntity.loadStatic(position, state,
                    NbtIo.readCompressed(saved, NbtAccounter.unlimitedHeap()), registries);
            assertInstanceOf(ChestBlockEntity.class, reloaded);
            var restored = WorldvaultChest.read((ChestBlockEntity) reloaded);
            assertEquals("fixture.bin", restored.name());
            assertArrayEquals(original, restored.bytes());
        }
    }

    @Test void shuffledSlotsRestoreButMissingBooksFail() {
        byte[] original = new byte[8192];
        new Random(7).nextBytes(original);
        var chest = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        WorldvaultChest.write(chest, WorldvaultBooks.pack("fixture.bin", original));
        var first = chest.getItem(0);
        chest.setItem(0, chest.getItem(1));
        chest.setItem(1, first);
        assertArrayEquals(original, WorldvaultChest.read(chest).bytes());
        chest.setItem(0, ItemStack.EMPTY);
        assertThrows(IllegalArgumentException.class, () -> WorldvaultChest.read(chest));
    }

    @Test void occupiedChestAndOversizedFileNeverOverwriteItems() {
        var chest = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        chest.setItem(26, new ItemStack(Items.DIAMOND, 3));
        var books = WorldvaultBooks.pack("fixture.bin", new byte[1]);
        assertThrows(IllegalArgumentException.class, () -> WorldvaultChest.write(chest, books));
        assertTrue(chest.getItem(0).isEmpty());
        assertEquals(3, chest.getItem(26).getCount());
        assertTrue(chest.getItem(26).is(Items.DIAMOND));
        chest.setItem(26, ItemStack.EMPTY);
        var tooMany = WorldvaultBooks.pack("fixture.bin", new byte[27 * 2048]);
        assertEquals(28, tooMany.size());
        assertThrows(IllegalArgumentException.class, () -> WorldvaultChest.write(chest, tooMany));
        for (int slot = 0; slot < 27; slot++) assertTrue(chest.getItem(slot).isEmpty());
    }

    @Test void foreignItemsFailInsteadOfExportingIncompleteData() {
        var chest = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        WorldvaultChest.write(chest, WorldvaultBooks.pack("fixture.bin", new byte[8192]));
        chest.setItem(26, new ItemStack(Items.STONE));
        assertThrows(IllegalArgumentException.class, () -> WorldvaultChest.read(chest));
    }

}
