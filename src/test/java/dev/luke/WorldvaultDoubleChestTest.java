package dev.luke;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Double-chest store/restore through the game's own chest classes; synthetic data only, no server or world. */
class WorldvaultDoubleChestTest {
    @TempDir Path directory;
    static final int DOUBLE_CAP = 54 * 2048 - 269;

    @BeforeAll static void initializeGame() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A double chest facing north: LEFT at x=0, RIGHT at x=1 (positions as ChestBlock.getConnectedDirection resolves them). */
    static class Pair {
        final BlockPos leftPos, rightPos;
        final ChestBlockEntity left, right;
        final Map<BlockPos, BlockEntity> world = new HashMap<>();
        Pair(BlockPos leftPos, Direction facing, ChestType leftType) {
            this.leftPos = leftPos;
            BlockState leftState = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing).setValue(ChestBlock.TYPE, leftType);
            this.rightPos = leftPos.relative(ChestBlock.getConnectedDirection(leftState));
            BlockState rightState = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing)
                    .setValue(ChestBlock.TYPE, leftType == ChestType.LEFT ? ChestType.RIGHT : ChestType.LEFT);
            left = new ChestBlockEntity(leftPos, leftState);
            right = new ChestBlockEntity(rightPos, rightState);
            world.put(leftPos, left);
            world.put(rightPos, right);
        }
        ChestBlockEntity leftHalf() { return left.getBlockState().getValue(ChestBlock.TYPE) == ChestType.LEFT ? left : right; }
        ChestBlockEntity rightHalf() { return leftHalf() == left ? right : left; }
        Container space(ChestBlockEntity clicked) {
            ChestBlockEntity[] halves = WorldvaultChest.halves(clicked, world::get);
            return WorldvaultChest.combine(halves[0], halves[1]);
        }
    }

    static Pair pair() { return new Pair(new BlockPos(10, 64, 10), Direction.NORTH, ChestType.LEFT); }

    static byte[] data(int size) {
        byte[] original = new byte[size];
        new Random(1000 + size).nextBytes(original);
        return original;
    }

    @Test void roundTripAcrossBoundariesAndFullDoubleChest() {
        // 0, book boundaries (2048*k - 13 - name length), >54 KB, and the full ~108 KB cap
        int header = 4 + 1 + "fixture.bin".length() + 8;
        int[] sizes = {0, 1, 2048 - header, 2049 - header, 27 * 2048 - header, 27 * 2048 - header + 1,
                27 * 2048 + 1, 60000, 100000, DOUBLE_CAP - 1, DOUBLE_CAP};
        for (int size : sizes) {
            Pair p = pair();
            byte[] original = data(size);
            var books = WorldvaultBooks.pack("fixture.bin", original);
            assertTrue(books.size() <= 54, "size " + size + " needs " + books.size() + " books");
            WorldvaultChest.write(p.space(p.left), books);
            // restore resolves from either half and gets the identical file
            assertArrayEquals(original, WorldvaultChest.read(p.space(p.left)).bytes(), "size " + size);
            assertArrayEquals(original, WorldvaultChest.read(p.space(p.right)).bytes(), "size " + size);
        }
    }

    @Test void oneByteOverCapNeedsMoreThanFiftyFourBooks() {
        assertTrue(WorldvaultBooks.pack("fixture.bin", new byte[54 * 2048 - 13 - "fixture.bin".length() + 1]).size() > 54);
        Pair p = pair();
        var tooMany = WorldvaultBooks.pack("fixture.bin", new byte[54 * 2048]);
        assertThrows(IllegalArgumentException.class, () -> WorldvaultChest.write(p.space(p.left), tooMany));
        for (int slot = 0; slot < 54; slot++) assertTrue(p.space(p.left).getItem(slot).isEmpty());
    }

    @Test void slotOrderIsLeftHalfThenRightHalfRegardlessOfClickedHalf() {
        for (boolean clickRight : new boolean[]{false, true}) {
            Pair p = pair();
            var books = WorldvaultBooks.pack("fixture.bin", data(40 * 2048 - 30));
            assertEquals(40, books.size());
            WorldvaultChest.write(p.space(clickRight ? p.right : p.left), books);
            ChestBlockEntity leftHalf = p.leftHalf(), rightHalf = p.rightHalf();
            assertEquals(ChestType.LEFT, leftHalf.getBlockState().getValue(ChestBlock.TYPE));
            for (int slot = 0; slot < 27; slot++) assertEquals("WV " + (slot + 1) + "/40", title(leftHalf.getItem(slot)));
            for (int slot = 0; slot < 13; slot++) assertEquals("WV " + (slot + 28) + "/40", title(rightHalf.getItem(slot)));
            for (int slot = 13; slot < 27; slot++) assertTrue(rightHalf.getItem(slot).isEmpty());
        }
    }

    @Test void swappedFacingStillResolvesLeftThenRight() {
        Pair p = new Pair(new BlockPos(-3, 70, 4), Direction.EAST, ChestType.RIGHT);
        byte[] original = data(70000);
        WorldvaultChest.write(p.space(p.left), WorldvaultBooks.pack("fixture.bin", original));
        assertEquals("WV 1/" + WorldvaultBooks.pack("fixture.bin", original).size(), title(p.leftHalf().getItem(0)));
        assertTrue(p.rightHalf().getItem(0).getCount() > 0);
        assertArrayEquals(original, WorldvaultChest.read(p.space(p.right)).bytes());
    }

    @Test void survivesCompressedNbtSaveAndReloadOfBothHalves() throws Exception {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        Pair p = pair();
        byte[] original = data(DOUBLE_CAP);
        WorldvaultChest.write(p.space(p.left), WorldvaultBooks.pack("fixture.bin", original));
        Pair reloaded = pair();
        for (var half : new ChestBlockEntity[]{p.left, p.right}) {
            Path saved = directory.resolve("half-" + half.getBlockPos().getX() + ".nbt");
            NbtIo.writeCompressed(half.saveWithFullMetadata(registries), saved);
            var loaded = BlockEntity.loadStatic(half.getBlockPos(), half.getBlockState(),
                    NbtIo.readCompressed(saved, NbtAccounter.unlimitedHeap()), registries);
            Pair target = reloaded;
            ChestBlockEntity into = half == p.left ? target.left : target.right;
            for (int slot = 0; slot < 27; slot++) into.setItem(slot, ((ChestBlockEntity) loaded).getItem(slot));
        }
        assertArrayEquals(original, WorldvaultChest.read(reloaded.space(reloaded.left)).bytes());
    }

    @Test void booksMissingFromEitherHalfFail() {
        for (boolean rightHalf : new boolean[]{false, true}) {
            Pair p = pair();
            WorldvaultChest.write(p.space(p.left), WorldvaultBooks.pack("fixture.bin", data(100000)));
            (rightHalf ? p.rightHalf() : p.leftHalf()).setItem(rightHalf ? 3 : 5, ItemStack.EMPTY);
            assertThrows(IllegalArgumentException.class, () -> WorldvaultChest.read(p.space(p.left)));
        }
    }

    @Test void entireHalfEmptyFails() {
        Pair p = pair();
        WorldvaultChest.write(p.space(p.left), WorldvaultBooks.pack("fixture.bin", data(100000)));
        for (int slot = 0; slot < 27; slot++) p.rightHalf().setItem(slot, ItemStack.EMPTY);
        assertThrows(IllegalArgumentException.class, () -> WorldvaultChest.read(p.space(p.left)));
    }

    @Test void foreignItemsInEitherHalfFail() {
        for (boolean rightHalf : new boolean[]{false, true}) {
            Pair p = pair();
            WorldvaultChest.write(p.space(p.left), WorldvaultBooks.pack("fixture.bin", data(60000)));
            (rightHalf ? p.rightHalf() : p.leftHalf()).setItem(26, new ItemStack(Items.STONE));
            assertThrows(IllegalArgumentException.class, () -> WorldvaultChest.read(p.space(p.left)));
        }
    }

    @Test void tamperedBookInEitherHalfFailsLoudly() {
        for (boolean rightHalf : new boolean[]{false, true}) {
            Pair p = pair();
            byte[] original = data(100000);
            var books = WorldvaultBooks.pack("fixture.bin", original);
            WorldvaultChest.write(p.space(p.left), books);
            Container half = rightHalf ? p.rightHalf() : p.leftHalf();
            int slot = 0;
            ItemStack forged = half.getItem(slot).copy();
            // corrupt one page's content (labels intact): the CRC32 check must reject it
            var content = forged.get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT);
            var pages = new java.util.ArrayList<>(content.pages());
            String first = pages.get(1).raw().getString(); // a data page, past the name header
            char flipped = first.charAt(8) == 'A' ? 'B' : 'A';
            pages.set(1, net.minecraft.server.network.Filterable.passThrough((net.minecraft.network.chat.Component)
                    net.minecraft.network.chat.Component.literal(first.substring(0, 8) + flipped + first.substring(9))));
            forged.set(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT,
                    new net.minecraft.world.item.component.WrittenBookContent(content.title(), content.author(),
                            content.generation(), pages, true));
            half.setItem(slot, forged);
            assertThrows(IllegalArgumentException.class, () -> WorldvaultChest.read(p.space(p.left)));
        }
    }

    @Test void oneHalfOccupiedBlocksStoreAndLeavesItemsUntouched() {
        for (boolean rightHalf : new boolean[]{false, true}) {
            Pair p = pair();
            (rightHalf ? p.rightHalf() : p.leftHalf()).setItem(26, new ItemStack(Items.DIAMOND, 3));
            var books = WorldvaultBooks.pack("fixture.bin", data(70000));
            assertThrows(IllegalArgumentException.class, () -> WorldvaultChest.write(p.space(p.left), books));
            assertTrue(p.leftHalf().getItem(0).isEmpty());
            assertTrue(p.rightHalf().getItem(0).isEmpty());
            assertEquals(3, (rightHalf ? p.rightHalf() : p.leftHalf()).getItem(26).getCount());
        }
    }

    @Test void missingOrInconsistentPartnerIsRejected() {
        Pair p = pair();
        p.world.remove(p.rightPos);
        assertThrows(IllegalArgumentException.class, () -> WorldvaultChest.halves(p.left, p.world::get));
        Pair q = pair();
        var wrongFacing = new ChestBlockEntity(q.rightPos, q.right.getBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
        q.world.put(q.rightPos, wrongFacing);
        assertThrows(IllegalArgumentException.class, () -> WorldvaultChest.halves(q.left, q.world::get));
        var single = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        assertThrows(IllegalArgumentException.class, () -> WorldvaultChest.halves(single, pos -> null));
    }

    @Test void chestPairedAfterSingleStoreFailsInsteadOfGuessing() {
        // single-chest store, then a second chest is placed beside it
        Pair p = pair();
        var single = new ChestBlockEntity(p.leftPos, Blocks.CHEST.defaultBlockState());
        byte[] original = data(5000);
        WorldvaultChest.write(single, WorldvaultBooks.pack("fixture.bin", original));
        assertArrayEquals(original, WorldvaultChest.read(assertDoesNotThrow(() ->
                WorldvaultChest.restoreSpace(false, single, pos -> null))).bytes());
        // now the same books sit in a chest that has become the LEFT half of a pair
        for (int slot = 0; slot < 27; slot++) p.left.setItem(slot, single.getItem(slot));
        assertThrows(java.io.IOException.class, () -> WorldvaultChest.restoreSpace(false, p.left, p.world::get));
        assertThrows(java.io.IOException.class, () -> WorldvaultChest.restoreSpace(false, p.right, p.world::get));
    }

    @Test void doubleStoreFailsWhenUnpairedOrHalfGone() throws Exception {
        Pair p = pair();
        WorldvaultChest.write(p.space(p.left), WorldvaultBooks.pack("fixture.bin", data(70000)));
        assertNotNull(WorldvaultChest.restoreSpace(true, p.left, p.world::get));
        assertNotNull(WorldvaultChest.restoreSpace(true, p.right, p.world::get));
        var lone = new ChestBlockEntity(p.leftPos, Blocks.CHEST.defaultBlockState());
        assertThrows(java.io.IOException.class, () -> WorldvaultChest.restoreSpace(true, lone, pos -> null));
        p.world.remove(p.rightPos);
        assertThrows(java.io.IOException.class, () -> WorldvaultChest.restoreSpace(true, p.left, p.world::get));
    }

    @Test void indexRecordsLayoutAndStillReadsLegacyRows() throws Exception {
        var world = java.nio.file.Files.createTempDirectory("worldvault-layout");
        VaultIndex index = new VaultIndex(world);
        var single = new VaultIndex.Entry("a.bin", "minecraft:overworld", 1, 64, 1, 2);
        var dbl = new VaultIndex.Entry("b.bin", "minecraft:overworld", 5, 64, 5, 30, true);
        index.add(single);
        index.add(dbl);
        var loaded = index.load();
        assertFalse(loaded.get(0).doubleChest());
        assertTrue(loaded.get(1).doubleChest());
        assertEquals(java.util.List.of(single, dbl), loaded);
        String[] rows = java.nio.file.Files.readAllLines(world.resolve("worldvault/index.tsv")).toArray(new String[0]);
        assertEquals(6, rows[0].split("\t", -1).length, "single rows keep the legacy 6-column format");
        assertEquals(7, rows[1].split("\t", -1).length);
    }

    private static String title(ItemStack stack) {
        return stack.get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT).title().raw();
    }
}
