package dev.luke;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gameplay paths from QA on the multi-chest commands: double chests, unloaded or distant targets, missing and
 * corrupt books, and a write failing partway through a store. Everything runs through the code the commands call
 * (VaultTargets, VaultService, WorldvaultChest) with the game's own chest classes and a fake world; no live server.
 */
class VaultGameplayGapsTest {
    @TempDir Path world;
    static final String DIM = "minecraft:overworld";
    static final int SINGLE_CAP = 27 * 2048 - 269;
    static final int DOUBLE_CAP = 54 * 2048 - 269;

    @BeforeAll static void initializeGame() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    static byte[] data(int size) {
        byte[] b = new byte[size];
        new Random(7000 + size).nextBytes(b);
        return b;
    }

    /**
     * A world of chests with loaded and unloaded chunks. Like a server level, a block-entity lookup in an unloaded
     * chunk still answers (it would load the chunk); every such lookup is recorded so tests can prove none happen.
     */
    static final class FakeWorld implements VaultTargets.WorldAccess {
        final Map<BlockPos, BlockEntity> blocks = new HashMap<>();
        final Set<Long> unloadedChunks = new HashSet<>();
        final List<BlockPos> forcedLoads = new ArrayList<>();

        @Override public String dimension() { return DIM; }
        @Override public boolean isLoaded(BlockPos pos) { return !unloadedChunks.contains(chunkKey(pos)); }
        @Override public BlockEntity blockEntity(BlockPos pos) {
            if (!isLoaded(pos)) forcedLoads.add(pos);
            return blocks.get(pos);
        }
        static long chunkKey(BlockPos pos) { return ((long) (pos.getX() >> 4) << 32) ^ ((pos.getZ() >> 4) & 0xffffffffL); }
        void unload(int chunkX, int chunkZ) { unloadedChunks.add(chunkKey(new BlockPos(chunkX << 4, 0, chunkZ << 4))); }

        ChestBlockEntity single(BlockPos pos) {
            ChestBlockEntity c = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
            blocks.put(pos, c);
            return c;
        }
        /** A double chest facing north: LEFT half at {@code leftPos}, RIGHT half one block east. */
        ChestBlockEntity[] pair(BlockPos leftPos) {
            BlockState l = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT);
            BlockState r = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT);
            BlockPos rightPos = leftPos.relative(ChestBlock.getConnectedDirection(l));
            ChestBlockEntity left = new ChestBlockEntity(leftPos, l);
            ChestBlockEntity right = new ChestBlockEntity(rightPos, r);
            blocks.put(leftPos, left);
            blocks.put(rightPos, right);
            return new ChestBlockEntity[]{left, right};
        }
        /** Opens a recorded chunk the way the restore command does, honouring single/double layout. */
        VaultService.ChestResolver resolver() {
            return chunk -> {
                BlockPos pos = new BlockPos(chunk.x(), chunk.y(), chunk.z());
                if (!(blocks.get(pos) instanceof ChestBlockEntity chest)) {
                    throw new IOException("Chest at " + chunk.x() + " " + chunk.y() + " " + chunk.z() + " is gone");
                }
                return WorldvaultChest.restoreSpace(chunk.doubleChest(), chest, blocks::get);
            };
        }
        /** Every slot of every chest, copied, so a test can prove nothing changed. */
        Map<BlockPos, List<ItemStack>> snapshot() {
            Map<BlockPos, List<ItemStack>> out = new HashMap<>();
            for (var e : blocks.entrySet()) {
                if (!(e.getValue() instanceof ChestBlockEntity c)) continue;
                List<ItemStack> slots = new ArrayList<>();
                for (int s = 0; s < c.getContainerSize(); s++) slots.add(c.getItem(s).copy());
                out.put(e.getKey(), slots);
            }
            return out;
        }
        void assertUnchanged(Map<BlockPos, List<ItemStack>> before) {
            assertEquals(before.keySet(), snapshot().keySet());
            for (var e : snapshot().entrySet()) {
                List<ItemStack> was = before.get(e.getKey());
                for (int s = 0; s < was.size(); s++) {
                    assertTrue(ItemStack.matches(was.get(s), e.getValue().get(s)),
                            "chest at " + e.getKey() + " slot " + s + " changed");
                }
            }
        }
    }

    static Path out(Path world) { return world.resolve("worldvault/out"); }

    static void assertNoOutput(Path world) throws IOException {
        Path dir = out(world);
        if (!Files.exists(dir)) return;
        try (var files = Files.list(dir)) { assertEquals(0, files.count(), "restore wrote an output file"); }
    }

    static byte[] indexBytes(Path world) throws IOException {
        Path p = world.resolve("worldvault/index.tsv");
        return Files.exists(p) ? Files.readAllBytes(p) : null;
    }

    // ---- 1. Double chests ----

    @Test void doubleChestCapacityCountsBothHalvesAndFullDoubleRoundTrips() throws Exception {
        FakeWorld w = new FakeWorld();
        w.pair(new BlockPos(0, 64, 0));
        VaultService.Target t = VaultTargets.target(w, new BlockPos(0, 64, 0));
        assertTrue(t.doubleChest());
        assertEquals(54, t.space().getContainerSize());
        assertEquals(DOUBLE_CAP, VaultService.capacity(List.of(t)));
        VaultIndexStore index = new VaultIndexStore(world);
        assertThrows(IllegalArgumentException.class,
                () -> VaultService.prepare("full.bin", data(DOUBLE_CAP + 1), List.of(t), index), "one byte over a double chest");
        byte[] original = data(DOUBLE_CAP);
        VaultIndexV2.Entry e = VaultService.commit(VaultService.prepare("full.bin", original, List.of(t), index));
        assertEquals(1, e.chunks().size());
        assertTrue(e.chunks().get(0).doubleChest());
        assertEquals(54, e.chunks().get(0).books());
        assertArrayEquals(original, VaultService.restoreTo(index.load().get(0), w.resolver(), out(world)).bytes());
        assertArrayEquals(original, Files.readAllBytes(out(world).resolve("full.bin")));
    }

    @Test void eitherHalfResolvesToTheSameDoubleChest() {
        FakeWorld w = new FakeWorld();
        ChestBlockEntity[] p = w.pair(new BlockPos(0, 64, 0));
        VaultService.Target byLeft = VaultTargets.target(w, p[0].getBlockPos());
        VaultService.Target byRight = VaultTargets.target(w, p[1].getBlockPos());
        assertEquals(List.of(p[0].getBlockPos(), p[1].getBlockPos()), byLeft.blocks());
        assertEquals(byLeft.blocks(), byRight.blocks());
        assertTrue(byRight.doubleChest());
        assertEquals(54, byRight.space().getContainerSize());
    }

    @Test void storeAcrossTwoDoubleChestsRoundTripsFromEitherClickedHalf() throws Exception {
        FakeWorld w = new FakeWorld();
        ChestBlockEntity[] a = w.pair(new BlockPos(0, 64, 0));
        ChestBlockEntity[] b = w.pair(new BlockPos(3, 64, 0));
        // named by the RIGHT half of the first and the LEFT half of the second
        List<VaultService.Target> targets = VaultTargets.targets(w, List.of(a[1].getBlockPos(), b[0].getBlockPos()));
        assertEquals(2L * DOUBLE_CAP, VaultService.capacity(targets));
        byte[] original = data(DOUBLE_CAP + 20_000);
        VaultIndexStore index = new VaultIndexStore(world);
        VaultIndexV2.Entry e = VaultService.commit(VaultService.prepare("two.bin", original, targets, index));
        assertEquals(2, e.chunks().size());
        assertTrue(e.chunks().get(0).doubleChest() && e.chunks().get(1).doubleChest());
        assertEquals(a[1].getBlockPos().getX(), e.chunks().get(0).x(), "index records the half the player named");
        // books fill the LEFT half first, so the first chest's left half is full and its right half holds the rest
        assertFalse(a[0].getItem(26).isEmpty());
        assertFalse(a[1].getItem(0).isEmpty());
        assertArrayEquals(original, VaultService.restoreTo(index.load().get(0), w.resolver(), out(world)).bytes());
    }

    @Test void doubleChestNamedTwiceOrByBothHalvesIsRejectedBeforeAnyWrite() throws Exception {
        FakeWorld w = new FakeWorld();
        ChestBlockEntity[] p = w.pair(new BlockPos(0, 64, 0));
        w.single(new BlockPos(5, 64, 0));
        var before = w.snapshot();
        List<List<BlockPos>> lists = List.of(
                List.of(p[0].getBlockPos(), p[0].getBlockPos()),
                List.of(p[0].getBlockPos(), p[1].getBlockPos()),
                List.of(p[1].getBlockPos(), new BlockPos(5, 64, 0), p[0].getBlockPos()));
        for (List<BlockPos> named : lists) {
            VaultIndexStore index = new VaultIndexStore(world);
            List<VaultService.Target> targets = VaultTargets.targets(w, named);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> VaultService.prepare("d.bin", data(DOUBLE_CAP + SINGLE_CAP), targets, index), named.toString());
            assertEquals("A chest is listed twice or overlaps another", ex.getMessage());
            w.assertUnchanged(before);
            assertNull(indexBytes(world));
        }
    }

    @Test void rangeOverADoubleChestListsItOnce() {
        FakeWorld w = new FakeWorld();
        ChestBlockEntity[] p = w.pair(new BlockPos(0, 64, 0));
        w.single(new BlockPos(3, 64, 0));
        List<BlockPos> found = VaultTargets.scanRange(w, new BlockPos(0, 64, 0), new BlockPos(3, 64, 0));
        assertEquals(List.of(p[0].getBlockPos(), new BlockPos(3, 64, 0)), found);
        List<VaultService.Target> targets = VaultTargets.targets(w, found);
        assertEquals(2, targets.size());
        assertTrue(targets.get(0).doubleChest());
        assertEquals(List.of(p[0].getBlockPos(), p[1].getBlockPos()), targets.get(0).blocks());
    }

    /** capacity() is the pre-check the store command uses before reading a file; it must agree with prepare(). */
    @Test void capacityOfMixedDoubleAndSingleMatchesWhatPrepareAccepts() throws Exception {
        FakeWorld w = new FakeWorld();
        w.pair(new BlockPos(0, 64, 0));
        w.single(new BlockPos(5, 64, 0));
        List<VaultService.Target> targets = VaultTargets.targets(w, List.of(new BlockPos(0, 64, 0), new BlockPos(5, 64, 0)));
        long cap = VaultService.capacity(targets);
        VaultService.Plan plan = VaultService.prepare("m.bin", data((int) cap), targets, new VaultIndexStore(world));
        assertEquals(2, plan.used().size());
        assertThrows(IllegalArgumentException.class,
                () -> VaultService.prepare("m.bin", data((int) cap + 1), targets, new VaultIndexStore(world)));
    }

    // ---- 2. Unloaded or distant target ----

    @Test void distantChestFailsBeforeAnyLookupOrWrite() {
        FakeWorld w = new FakeWorld();
        w.single(new BlockPos(2, 64, 0));
        w.single(new BlockPos(200, 64, 0));
        var before = w.snapshot();
        Vec3 player = new Vec3(0.5, 64, 0.5);
        VaultTargets.checkReach(player, List.of(new BlockPos(2, 64, 0)));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> VaultTargets.checkReach(player, List.of(new BlockPos(2, 64, 0), new BlockPos(200, 64, 0))));
        assertEquals("Stand near your chests", ex.getMessage());
        w.assertUnchanged(before);
    }

    @Test void unloadedNamedChestFailsWithoutLoadingItsChunk() throws Exception {
        FakeWorld w = new FakeWorld();
        w.single(new BlockPos(2, 64, 0));
        w.single(new BlockPos(40, 64, 0));
        w.unload(2, 0);
        var before = w.snapshot();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> VaultTargets.targets(w, List.of(new BlockPos(2, 64, 0), new BlockPos(40, 64, 0))));
        assertTrue(ex.getMessage().contains("40 64 0") && ex.getMessage().contains("not loaded"), ex.getMessage());
        assertEquals(List.of(), w.forcedLoads, "a chunk was loaded");
        w.assertUnchanged(before);
        assertNull(indexBytes(world));
    }

    @Test void doubleChestWithItsOtherHalfInAnUnloadedChunkFailsWithoutLoadingIt() {
        FakeWorld w = new FakeWorld();
        ChestBlockEntity[] p = w.pair(new BlockPos(15, 64, 0)); // RIGHT half at x=16, the next chunk
        assertEquals(16, p[1].getBlockPos().getX());
        w.unload(1, 0);
        var before = w.snapshot();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> VaultTargets.target(w, p[0].getBlockPos()));
        assertTrue(ex.getMessage().contains("not loaded"), ex.getMessage());
        assertEquals(List.of(), w.forcedLoads, "the other half's chunk was loaded");
        w.assertUnchanged(before);
    }

    @Test void rangeReachingIntoAnUnloadedChunkFailsWithoutLoadingIt() {
        FakeWorld w = new FakeWorld();
        w.single(new BlockPos(2, 64, 0));
        w.single(new BlockPos(20, 64, 0));
        w.unload(1, 0);
        var before = w.snapshot();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> VaultTargets.scanRange(w, new BlockPos(0, 64, 0), new BlockPos(24, 64, 0)));
        assertTrue(ex.getMessage().contains("not loaded"), ex.getMessage());
        assertEquals(List.of(), w.forcedLoads, "the range scan loaded chunks");
        w.assertUnchanged(before);
    }

    // ---- 3. Missing book ----

    @Test void missingBookFailsNamingTheChestAndWritesNoOutputFile() throws Exception {
        for (int damaged = 0; damaged < 3; damaged++) {
            Path dir = world.resolve("m" + damaged);
            FakeWorld w = new FakeWorld();
            for (int i = 0; i < 3; i++) w.single(new BlockPos(i * 2, 64, 0));
            List<VaultService.Target> targets = VaultTargets.targets(w,
                    List.of(new BlockPos(0, 64, 0), new BlockPos(2, 64, 0), new BlockPos(4, 64, 0)));
            VaultIndexStore index = new VaultIndexStore(dir);
            VaultService.commit(VaultService.prepare("f.bin", data(3 * SINGLE_CAP - 50), targets, index));
            ((Container) w.blocks.get(new BlockPos(damaged * 2, 64, 0))).setItem(4, ItemStack.EMPTY);
            int chest = damaged + 1;
            IOException ex = assertThrows(IOException.class,
                    () -> VaultService.restoreTo(index.load().get(0), w.resolver(), out(dir)));
            assertTrue(ex.getMessage().startsWith("Chest " + chest + " of 3: "), ex.getMessage());
            assertTrue(ex.getMessage().contains("ook"), ex.getMessage());
            assertNoOutput(dir);
        }
    }

    @Test void chestEmptiedOfBooksOrDoubleMissingAHalfsBookFailsWithNoOutput() throws Exception {
        FakeWorld w = new FakeWorld();
        ChestBlockEntity[] p = w.pair(new BlockPos(0, 64, 0));
        ChestBlockEntity[] q = w.pair(new BlockPos(4, 64, 0));
        List<VaultService.Target> targets = VaultTargets.targets(w, List.of(p[0].getBlockPos(), q[1].getBlockPos()));
        VaultIndexStore index = new VaultIndexStore(world);
        VaultService.commit(VaultService.prepare("f.bin", data(DOUBLE_CAP + 1000), targets, index));
        // a book taken from the RIGHT half of the first double chest
        assertFalse(p[1].getItem(26).isEmpty(), "the right half holds books");
        ItemStack taken = p[1].getItem(26);
        p[1].setItem(26, ItemStack.EMPTY);
        IOException one = assertThrows(IOException.class, () -> VaultService.restoreTo(index.load().get(0), w.resolver(), out(world)));
        assertTrue(one.getMessage().startsWith("Chest 1 of 2: "), one.getMessage());
        assertNoOutput(world);
        p[1].setItem(26, taken);
        // every book taken from the second double chest
        for (ChestBlockEntity half : q) for (int s = 0; s < 27; s++) half.setItem(s, ItemStack.EMPTY);
        IOException two = assertThrows(IOException.class, () -> VaultService.restoreTo(index.load().get(0), w.resolver(), out(world)));
        assertTrue(two.getMessage().startsWith("Chest 2 of 2: "), two.getMessage());
        assertNoOutput(world);
    }

    // ---- 4. Corrupt book ----

    @Test void chunkCrcMismatchFailsNamingTheChunkWithNoOutput() throws Exception {
        FakeWorld w = new FakeWorld();
        for (int i = 0; i < 3; i++) w.single(new BlockPos(i * 2, 64, 0));
        List<VaultService.Target> targets = VaultTargets.targets(w,
                List.of(new BlockPos(0, 64, 0), new BlockPos(2, 64, 0), new BlockPos(4, 64, 0)));
        VaultIndexStore index = new VaultIndexStore(world);
        byte[] original = data(3 * SINGLE_CAP - 50);
        VaultService.commit(VaultService.prepare("f.bin", original, targets, index));
        // the index row's CRC for chunk 2 no longer matches what chest 2 holds; book-level checks all pass
        Path file = world.resolve("worldvault/index.tsv");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        VaultIndexV2.Chunk c2 = index.load().get(0).chunks().get(1);
        String field = c2.dimension() + "|2|64|0|" + c2.books() + "|single|" + Long.toHexString(c2.crc32());
        assertTrue(text.contains(field), text);
        Files.writeString(file, text.replace(field, field.substring(0, field.lastIndexOf('|') + 1)
                + Long.toHexString(c2.crc32() ^ 1)), StandardCharsets.UTF_8);
        IOException ex = assertThrows(IOException.class, () -> VaultService.restoreTo(index.load().get(0), w.resolver(), out(world)));
        assertEquals("Chunk 2 changed (CRC mismatch)", ex.getMessage());
        assertNoOutput(world);
    }

    @Test void flippedCharacterInOneBookFailsCleanlyWithNoOutput() throws Exception {
        FakeWorld w = new FakeWorld();
        for (int i = 0; i < 2; i++) w.single(new BlockPos(i * 2, 64, 0));
        List<VaultService.Target> targets = VaultTargets.targets(w, List.of(new BlockPos(0, 64, 0), new BlockPos(2, 64, 0)));
        VaultIndexStore index = new VaultIndexStore(world);
        VaultService.commit(VaultService.prepare("f.bin", data(2 * SINGLE_CAP - 50), targets, index));
        ChestBlockEntity chest = (ChestBlockEntity) w.blocks.get(new BlockPos(2, 64, 0));
        ItemStack book = chest.getItem(3);
        WrittenBookContent content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
        List<Filterable<Component>> pages = new ArrayList<>(content.pages());
        String page = pages.get(0).raw().getString();
        char flipped = page.charAt(10) == 'A' ? 'B' : 'A';
        pages.set(0, Filterable.passThrough(Component.literal(page.substring(0, 10) + flipped + page.substring(11))));
        ItemStack tampered = book.copy();
        tampered.set(DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(content.title(), content.author(), content.generation(), pages, true));
        chest.setItem(3, tampered);
        IOException ex = assertThrows(IOException.class, () -> VaultService.restoreTo(index.load().get(0), w.resolver(), out(world)));
        assertTrue(ex.getMessage().startsWith("Chest 2 of 2: "), ex.getMessage());
        assertNoOutput(world);
    }

    @Test void wholeFileSha256MismatchFailsWithNoOutput() throws Exception {
        FakeWorld w = new FakeWorld();
        for (int i = 0; i < 2; i++) w.single(new BlockPos(i * 2, 64, 0));
        List<VaultService.Target> targets = VaultTargets.targets(w, List.of(new BlockPos(0, 64, 0), new BlockPos(2, 64, 0)));
        VaultIndexStore index = new VaultIndexStore(world);
        VaultService.commit(VaultService.prepare("f.bin", data(SINGLE_CAP + 10), targets, index));
        // every chunk CRC still matches; only the recorded whole-file hash differs
        Path file = world.resolve("worldvault/index.tsv");
        String sha = index.load().get(0).sha256();
        String wrong = (sha.charAt(0) == '0' ? '1' : '0') + sha.substring(1);
        Files.writeString(file, Files.readString(file).replace(sha, wrong));
        IOException ex = assertThrows(IOException.class, () -> VaultService.restoreTo(index.load().get(0), w.resolver(), out(world)));
        assertEquals("SHA-256 mismatch", ex.getMessage());
        assertNoOutput(world);
    }

    // ---- 5. Write failure partway through a store ----

    /** A chest that accepts {@code limit} books and then throws, like a full disk or a broken block entity. */
    static ChestBlockEntity failingAfter(BlockPos pos, int limit) {
        return new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState()) {
            int written;
            @Override public void setItem(int slot, ItemStack stack) {
                if (!stack.isEmpty() && ++written > limit) throw new IllegalStateException("disk full");
                super.setItem(slot, stack);
            }
        };
    }

    @Test void secondOfThreeChestsFailingRollsBackEveryChestAndKeepsIndexBytes() throws Exception {
        FakeWorld w = new FakeWorld();
        w.single(new BlockPos(0, 64, 0));
        ChestBlockEntity bad = failingAfter(new BlockPos(2, 64, 0), 5);
        w.blocks.put(bad.getBlockPos(), bad);
        w.single(new BlockPos(4, 64, 0));
        ChestBlockEntity bystander = w.single(new BlockPos(6, 64, 0));
        bystander.setItem(3, new ItemStack(Items.DIAMOND, 7));
        // an earlier vault is already in the index
        FakeWorld other = new FakeWorld();
        other.single(new BlockPos(100, 64, 0));
        VaultIndexStore index = new VaultIndexStore(world);
        VaultService.commit(VaultService.prepare("old.bin", data(500), VaultTargets.targets(other, List.of(new BlockPos(100, 64, 0))), index));
        byte[] indexBefore = indexBytes(world);
        var before = w.snapshot();
        List<VaultService.Target> targets = VaultTargets.targets(w,
                List.of(new BlockPos(0, 64, 0), new BlockPos(2, 64, 0), new BlockPos(4, 64, 0)));
        VaultService.Plan plan = VaultService.prepare("f.bin", data(3 * SINGLE_CAP - 50), targets, index);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> VaultService.commit(plan));
        assertEquals("disk full", ex.getMessage());
        w.assertUnchanged(before);
        assertEquals(7, bystander.getItem(3).getCount());
        assertArrayEquals(indexBefore, indexBytes(world), "index file changed");
        try (var tmp = Files.list(world.resolve("worldvault"))) { assertEquals(1, tmp.count(), "stray index temp file"); }
    }

    @Test void failedStoreLeavesAMissingIndexMissing() throws Exception {
        FakeWorld w = new FakeWorld();
        w.single(new BlockPos(0, 64, 0));
        ChestBlockEntity bad = failingAfter(new BlockPos(2, 64, 0), 0);
        w.blocks.put(bad.getBlockPos(), bad);
        VaultIndexStore index = new VaultIndexStore(world);
        var before = w.snapshot();
        var plan = VaultService.prepare("f.bin", data(SINGLE_CAP + 10),
                VaultTargets.targets(w, List.of(new BlockPos(0, 64, 0), new BlockPos(2, 64, 0))), index);
        assertThrows(IllegalStateException.class, () -> VaultService.commit(plan));
        w.assertUnchanged(before);
        assertNull(indexBytes(world), "a failed store created an index file");
    }

    @Test void failedStoreLeavesAV1IndexByteForByte() throws Exception {
        FakeWorld w = new FakeWorld();
        ChestBlockEntity oldChest = w.single(new BlockPos(50, 64, 0));
        byte[] old = data(900);
        WorldvaultChest.write(oldChest, WorldvaultBooks.pack("old.bin", old));
        Path dir = world.resolve("worldvault");
        Files.createDirectories(dir);
        String name = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("old.bin".getBytes(StandardCharsets.UTF_8));
        Files.writeString(dir.resolve("index.tsv"), name + "\t" + DIM + "\t50\t64\t0\t1\n");
        byte[] indexBefore = indexBytes(world);
        w.single(new BlockPos(0, 64, 0));
        ChestBlockEntity bad = failingAfter(new BlockPos(2, 64, 0), 3);
        w.blocks.put(bad.getBlockPos(), bad);
        VaultIndexStore index = new VaultIndexStore(world);
        var before = w.snapshot();
        var plan = VaultService.prepare("f.bin", data(SINGLE_CAP + 9000),
                VaultTargets.targets(w, List.of(new BlockPos(0, 64, 0), new BlockPos(2, 64, 0))), index);
        assertThrows(IllegalStateException.class, () -> VaultService.commit(plan));
        w.assertUnchanged(before);
        assertArrayEquals(indexBefore, indexBytes(world), "a failed store rewrote the v1 index");
        assertArrayEquals(old, VaultService.restore(index.load().get(0), w.resolver()).bytes());
    }

    /** A chest that fills between prepare and commit must keep its items: rollback only undoes what the store wrote. */
    @Test void rollbackNeverClearsItemsTheStoreDidNotPlace() throws Exception {
        FakeWorld w = new FakeWorld();
        w.single(new BlockPos(0, 64, 0));
        ChestBlockEntity second = w.single(new BlockPos(2, 64, 0));
        w.single(new BlockPos(4, 64, 0));
        VaultIndexStore index = new VaultIndexStore(world);
        var plan = VaultService.prepare("f.bin", data(3 * SINGLE_CAP - 50), VaultTargets.targets(w,
                List.of(new BlockPos(0, 64, 0), new BlockPos(2, 64, 0), new BlockPos(4, 64, 0))), index);
        second.setItem(13, new ItemStack(Items.DIAMOND, 5));
        var before = w.snapshot();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> VaultService.commit(plan));
        assertEquals("Chest is not empty", ex.getMessage());
        w.assertUnchanged(before);
        assertEquals(5, second.getItem(13).getCount(), "the player's diamonds were deleted");
        assertNull(indexBytes(world));
    }
}
