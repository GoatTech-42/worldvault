package dev.luke;

import dev.luke.VaultGameplayGapsTest.FakeWorld;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static dev.luke.VaultGameplayGapsTest.SINGLE_CAP;
import static dev.luke.VaultGameplayGapsTest.assertNoOutput;
import static dev.luke.VaultGameplayGapsTest.indexBytes;
import static dev.luke.VaultGameplayGapsTest.out;
import static org.junit.jupiter.api.Assertions.*;

/** Source-review findings on the multi-chest commands (B1-B3, C4-C10), through the code the commands call. */
class VaultReviewFindingsTest {
    @TempDir Path world;
    static final String DIM = "minecraft:overworld";

    @BeforeAll static void initializeGame() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    static byte[] data(int size) {
        byte[] b = new byte[size];
        new Random(9100 + size).nextBytes(b);
        return b;
    }

    static List<BlockPos> row(int count) {
        List<BlockPos> list = new ArrayList<>();
        for (int i = 0; i < count; i++) list.add(new BlockPos(i * 2, 64, 0));
        return list;
    }

    static FakeWorld singles(int count) {
        FakeWorld w = new FakeWorld();
        for (BlockPos p : row(count)) w.single(p);
        return w;
    }

    static boolean holdsBooks(Container c) {
        for (int s = 0; s < c.getContainerSize(); s++) if (!c.getItem(s).isEmpty()) return true;
        return false;
    }

    static void empty(Container c) {
        for (int s = 0; s < c.getContainerSize(); s++) c.setItem(s, ItemStack.EMPTY);
    }

    /** Stands in for the process dying: an Error that no rollback code catches. */
    static final class SimulatedCrash extends Error {
        SimulatedCrash() { super("simulated crash"); }
    }

    // ---- B1: index only after the chest writes; repair for phantom rows ----

    @Test void crashAfterChestWritesLeavesNoIndexRow() throws Exception {
        FakeWorld w = singles(2);
        VaultIndexStore index = new VaultIndexStore(world);
        var plan = VaultService.prepare("f.bin", data(SINGLE_CAP + 100), VaultTargets.targets(w, row(2)), index);
        assertThrows(SimulatedCrash.class, () -> VaultService.commit(plan, new VaultService.Hooks() {
            @Override public void afterChestWrites() { throw new SimulatedCrash(); }
        }));
        assertNull(indexBytes(world), "a crash before the store finished left an index row");
        // the books written before the crash are ordinary items the player can see and take back
        assertTrue(holdsBooks((Container) w.blocks.get(row(2).get(0))));
    }

    @Test void afterACrashTheNameAndChestsCanBeUsedAgain() throws Exception {
        FakeWorld w = singles(2);
        VaultIndexStore index = new VaultIndexStore(world);
        var plan = VaultService.prepare("f.bin", data(SINGLE_CAP + 100), VaultTargets.targets(w, row(2)), index);
        assertThrows(SimulatedCrash.class, () -> VaultService.commit(plan, new VaultService.Hooks() {
            @Override public void afterChestWrites() { throw new SimulatedCrash(); }
        }));
        for (BlockPos p : row(2)) empty((Container) w.blocks.get(p));
        byte[] again = data(SINGLE_CAP + 7);
        VaultService.commit(VaultService.prepare("f.bin", again, VaultTargets.targets(w, row(2)), index));
        assertArrayEquals(again, VaultService.restoreTo(index.load().get(0), w.resolver(), out(world)).bytes());
    }

    /** A row whose chests hold no Worldvault books (crash after the index flush, before the world saved). */
    @Test void repairRemovesAnOrphanedRowAndFreesItsNameAndChests() throws Exception {
        FakeWorld w = singles(2);
        VaultIndexStore index = new VaultIndexStore(world);
        var plan = VaultService.prepare("f.bin", data(SINGLE_CAP + 100), VaultTargets.targets(w, row(2)), index);
        index.add(plan.entry()); // the row reached disk, the chest contents never did
        VaultService.Check check = VaultService.repair(index.load().get(0), w.inspector(), w.resolver(), index);
        assertEquals(VaultService.Verdict.ORPHANED, check.verdict(), check.detail());
        assertTrue(index.load().isEmpty());
        VaultService.commit(VaultService.prepare("f.bin", data(50), VaultTargets.targets(w, row(1)), index));
    }

    @Test void repairTreatsABrokenChestAsHoldingNoBooks() throws Exception {
        FakeWorld w = singles(2);
        VaultIndexStore index = new VaultIndexStore(world);
        var plan = VaultService.prepare("f.bin", data(SINGLE_CAP + 100), VaultTargets.targets(w, row(2)), index);
        index.add(plan.entry());
        w.blocks.remove(row(2).get(1));
        assertEquals(VaultService.Verdict.ORPHANED, VaultService.repair(index.load().get(0), w.inspector(), w.resolver(), index).verdict());
        assertTrue(index.load().isEmpty());
    }

    @Test void repairKeepsIntactPartlyPresentAndUnloadedRows() throws Exception {
        // intact
        FakeWorld w = singles(3);
        VaultIndexStore index = new VaultIndexStore(world);
        VaultService.commit(VaultService.prepare("f.bin", data(2 * SINGLE_CAP), VaultTargets.targets(w, row(2)), index));
        VaultService.Check intact = VaultService.repair(index.load().get(0), w.inspector(), w.resolver(), index);
        assertEquals(VaultService.Verdict.INTACT, intact.verdict());
        assertEquals(1, index.load().size());
        // chest 1 emptied, chest 2 still holds its books: keep the row and name the chest with books
        empty((Container) w.blocks.get(row(2).get(0)));
        VaultService.Check partly = VaultService.repair(index.load().get(0), w.inspector(), w.resolver(), index);
        assertEquals(VaultService.Verdict.KEEP, partly.verdict());
        assertTrue(partly.detail().contains("2 64 0"), partly.detail());
        assertEquals(1, index.load().size());
        // a chest that is not loaded cannot be judged: keep the row
        empty((Container) w.blocks.get(row(2).get(1)));
        w.unload(0, 0);
        VaultService.Check unloaded = VaultService.repair(index.load().get(0), w.inspector(), w.resolver(), index);
        assertEquals(VaultService.Verdict.KEEP, unloaded.verdict());
        assertTrue(unloaded.detail().contains("not loaded"), unloaded.detail());
        assertEquals(1, index.load().size());
        assertEquals(List.of(), w.forcedLoads);
    }

    /** The chest left behind when one half of a double chest is broken: same items, now a single chest. */
    static ChestBlockEntity unpair(FakeWorld w, ChestBlockEntity broken, ChestBlockEntity survivor) {
        w.blocks.remove(broken.getBlockPos());
        ChestBlockEntity single = new ChestBlockEntity(survivor.getBlockPos(), Blocks.CHEST.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ChestBlock.FACING,
                        survivor.getBlockState().getValue(net.minecraft.world.level.block.ChestBlock.FACING)));
        for (int s = 0; s < 27; s++) single.setItem(s, survivor.getItem(s).copy());
        w.blocks.put(single.getBlockPos(), single);
        return single;
    }

    /** CodeRabbit on f77ba53: the recorded half gone does not mean the other half is empty. */
    @Test void repairKeepsARowWhenTheRecordedHalfIsGoneButTheOtherHalfHoldsBooks() throws Exception {
        FakeWorld w = new FakeWorld();
        ChestBlockEntity[] p = w.pair(new BlockPos(0, 64, 0));
        VaultIndexStore index = new VaultIndexStore(world);
        // named by the RIGHT half; a small file sits entirely in the LEFT half
        VaultService.commit(VaultService.prepare("f.bin", data(5000), VaultTargets.targets(w, List.of(p[1].getBlockPos())), index));
        assertTrue(holdsBooks(p[0]));
        unpair(w, p[1], p[0]);
        VaultService.Check check = VaultService.repair(index.load().get(0), w.inspector(), w.resolver(), index);
        assertEquals(VaultService.Verdict.KEEP, check.verdict(), check.detail());
        assertEquals(1, index.load().size(), "the row was removed while the other half still holds the books");
    }

    @Test void repairKeepsARowWhenAPossibleOtherHalfIsNotLoaded() throws Exception {
        FakeWorld w = new FakeWorld();
        ChestBlockEntity[] p = w.pair(new BlockPos(15, 64, 0)); // RIGHT half at x=16, next chunk
        VaultIndexStore index = new VaultIndexStore(world);
        VaultService.commit(VaultService.prepare("f.bin", data(5000), VaultTargets.targets(w, List.of(p[1].getBlockPos())), index));
        w.blocks.remove(p[1].getBlockPos());
        w.unload(0, 0); // the surviving LEFT half's chunk
        VaultService.Check check = VaultService.repair(index.load().get(0), w.inspector(), w.resolver(), index);
        assertEquals(VaultService.Verdict.KEEP, check.verdict(), check.detail());
        assertTrue(check.detail().contains("not loaded"), check.detail());
        assertEquals(1, index.load().size());
        assertEquals(List.of(), w.forcedLoads);
    }

    /** CodeRabbit on f77ba53: an emptied double chest that lost a half is a single chest now, and still orphaned. */
    @Test void repairRemovesARowWhenAnEmptiedDoubleChestLostItsOtherHalf() throws Exception {
        FakeWorld w = new FakeWorld();
        ChestBlockEntity[] p = w.pair(new BlockPos(0, 64, 0));
        VaultIndexStore index = new VaultIndexStore(world);
        VaultService.commit(VaultService.prepare("f.bin", data(5000), VaultTargets.targets(w, List.of(p[0].getBlockPos())), index));
        empty(p[0]);
        empty(p[1]);
        unpair(w, p[1], p[0]);
        VaultService.Check check = VaultService.repair(index.load().get(0), w.inspector(), w.resolver(), index);
        assertEquals(VaultService.Verdict.ORPHANED, check.verdict(), check.detail());
        assertTrue(index.load().isEmpty());
    }

    /** CodeRabbit on f77ba53: a write that fails part way must not leave a partial file that blocks a retry. */
    @Test void failedOutputWriteLeavesNoPartialFileAndARetryWorks() throws Exception {
        FakeWorld w = singles(2);
        VaultIndexStore index = new VaultIndexStore(world);
        byte[] original = data(SINGLE_CAP + 100);
        VaultService.commit(VaultService.prepare("f.bin", original, VaultTargets.targets(w, row(2)), index));
        VaultService.Hooks diskFull = new VaultService.Hooks() {
            @Override public java.io.OutputStream wrapOutput(java.io.OutputStream out) {
                return new java.io.FilterOutputStream(out) {
                    int written;
                    @Override public void write(byte[] b, int off, int len) throws IOException {
                        int part = Math.min(len, 1000 - written);
                        if (part > 0) { out.write(b, off, part); written += part; }
                        if (part < len) throw new IOException("No space left on device");
                    }
                };
            }
        };
        IOException ex = assertThrows(IOException.class, () -> VaultService.restoreTo(index.load().get(0), w.resolver(), out(world), diskFull));
        assertEquals("No space left on device", ex.getMessage());
        assertNoOutput(world);
        assertArrayEquals(original, VaultService.restoreTo(index.load().get(0), w.resolver(), out(world)).bytes());
        assertArrayEquals(original, Files.readAllBytes(out(world).resolve("f.bin")));
        // an output that was there before is still never replaced
        IOException again = assertThrows(IOException.class, () -> VaultService.restoreTo(index.load().get(0), w.resolver(), out(world)));
        assertTrue(again.getMessage().contains("already has"), again.getMessage());
        assertArrayEquals(original, Files.readAllBytes(out(world).resolve("f.bin")));
    }

    // ---- B3: a rollback that cannot finish is reported, never silent ----

    /** A chest that takes books but refuses to be emptied again. */
    static ChestBlockEntity jammed(BlockPos pos) {
        return new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState()) {
            @Override public void setItem(int slot, ItemStack stack) {
                if (stack.isEmpty() && !getItem(slot).isEmpty()) throw new IllegalStateException("chest jammed");
                super.setItem(slot, stack);
            }
        };
    }

    @Test void rollbackThatCannotEmptyAChestNamesItAndWritesNoRow() throws Exception {
        FakeWorld w = new FakeWorld();
        w.single(row(3).get(0));
        ChestBlockEntity stuck = jammed(row(3).get(1));
        w.blocks.put(stuck.getBlockPos(), stuck);
        ChestBlockEntity failing = VaultGameplayGapsTest.failingAfter(row(3).get(2), 2);
        w.blocks.put(failing.getBlockPos(), failing);
        VaultIndexStore index = new VaultIndexStore(world);
        var plan = VaultService.prepare("f.bin", data(3 * SINGLE_CAP - 50), VaultTargets.targets(w, row(3)), index);
        Exception ex = assertThrows(Exception.class, () -> VaultService.commit(plan));
        assertTrue(ex.getMessage().contains("disk full"), ex.getMessage());
        assertTrue(ex.getMessage().contains(DIM + " 2 64 0"), "names the chest still holding books: " + ex.getMessage());
        assertFalse(ex.getMessage().contains(DIM + " 0 64 0"), "chest 1 was emptied again: " + ex.getMessage());
        assertFalse(holdsBooks((Container) w.blocks.get(row(3).get(0))));
        assertNull(indexBytes(world), "no index row for a failed store");
    }

    @Test void indexWriteFailingAfterTheChestWritesEmptiesTheChestsAgain() throws Exception {
        FakeWorld w = singles(2);
        VaultIndexStore index = new VaultIndexStore(world);
        var plan = VaultService.prepare("f.bin", data(SINGLE_CAP + 100), VaultTargets.targets(w, row(2)), index);
        Files.createDirectories(world.resolve("worldvault/index.tsv/blocked")); // the index file cannot be read or replaced
        assertThrows(IOException.class, () -> VaultService.commit(plan));
        for (BlockPos p : row(2)) assertFalse(holdsBooks((Container) w.blocks.get(p)), "chest at " + p + " kept books");
    }

    // ---- C4: restore never loads a chunk ----

    @Test void restoreFromAnUnloadedChestFailsWithoutLoadingIt() throws Exception {
        FakeWorld w = new FakeWorld();
        w.single(new BlockPos(0, 64, 0));
        w.single(new BlockPos(40, 64, 0));
        VaultIndexStore index = new VaultIndexStore(world);
        VaultService.commit(VaultService.prepare("f.bin", data(SINGLE_CAP + 100),
                VaultTargets.targets(w, List.of(new BlockPos(0, 64, 0), new BlockPos(40, 64, 0))), index));
        w.unload(2, 0);
        IOException ex = assertThrows(IOException.class, () -> VaultService.restoreTo(index.load().get(0), w.resolver(), out(world)));
        assertTrue(ex.getMessage().contains("40 64 0") && ex.getMessage().contains("not loaded"), ex.getMessage());
        assertEquals(List.of(), w.forcedLoads);
        assertNoOutput(world);
    }

    @Test void restoreWithTheOtherHalfUnloadedFailsWithoutLoadingIt() throws Exception {
        FakeWorld w = new FakeWorld();
        ChestBlockEntity[] p = w.pair(new BlockPos(15, 64, 0));
        VaultIndexStore index = new VaultIndexStore(world);
        VaultService.commit(VaultService.prepare("f.bin", data(SINGLE_CAP + 100), VaultTargets.targets(w, List.of(p[0].getBlockPos())), index));
        w.unload(1, 0);
        IOException ex = assertThrows(IOException.class, () -> VaultService.restoreTo(index.load().get(0), w.resolver(), out(world)));
        assertTrue(ex.getMessage().contains("not loaded"), ex.getMessage());
        assertEquals(List.of(), w.forcedLoads);
        assertNoOutput(world);
    }

    // ---- C5: the input file must really be inside worldvault/in ----

    @Test void symlinkPointingOutsideTheInputFolderIsRejected() throws Exception {
        Path in = Files.createDirectories(world.resolve("worldvault/in"));
        Path secret = Files.writeString(Files.createDirectories(world.resolve("outside")).resolve("secret.txt"), "host secret");
        Files.createSymbolicLink(in.resolve("evil.bin"), secret);
        assertThrows(IllegalArgumentException.class, () -> VaultService.inputFile(in, "evil.bin"));
        Path elsewhere = Files.createDirectories(world.resolve("outside/dir"));
        Files.createSymbolicLink(in.resolve("dirlink"), elsewhere);
        Files.writeString(elsewhere.resolve("x.bin"), "x");
        assertThrows(IllegalArgumentException.class, () -> VaultService.inputFile(in, "dirlink/x.bin"));
    }

    @Test void plainFilesAndLinksInsideTheInputFolderStillWork() throws Exception {
        Path in = Files.createDirectories(world.resolve("worldvault/in"));
        Path real = Files.writeString(in.resolve("real.bin"), "data");
        Files.createSymbolicLink(in.resolve("alias.bin"), real);
        assertEquals(real.toRealPath(), VaultService.inputFile(in, "real.bin").toRealPath());
        assertEquals(real.toRealPath(), VaultService.inputFile(in, "alias.bin").toRealPath());
        assertThrows(IllegalArgumentException.class, () -> VaultService.inputFile(in, "../outside.bin"));
        assertThrows(IllegalArgumentException.class, () -> VaultService.inputFile(in, "missing.bin"));
    }

    // ---- C6: v1 vaults are not described as SHA-256 protected ----

    @Test void integrityMessageSeparatesLegacyCrc32FromSha256() throws Exception {
        FakeWorld w = singles(1);
        VaultIndexStore index = new VaultIndexStore(world);
        VaultIndexV2.Entry v2 = VaultService.commit(VaultService.prepare("f.bin", data(100), VaultTargets.targets(w, row(1)), index));
        assertTrue(VaultService.integrity(v2).contains("SHA-256"), VaultService.integrity(v2));
        VaultIndexV2.Entry v1 = VaultIndexV2.fromV1(new VaultIndex.Entry("old.bin", DIM, 0, 64, 0, 1, false));
        String legacy = VaultService.integrity(v1);
        assertTrue(legacy.contains("v1") && legacy.contains("CRC32") && legacy.contains("no SHA-256"), legacy);
    }

    // ---- C7: the recorded per-chunk book count is checked ----

    @Test void v1RowWithWrongBookCountFailsInsteadOfRestoringAnotherVault() throws Exception {
        FakeWorld w = singles(1);
        ChestBlockEntity chest = (ChestBlockEntity) w.blocks.get(row(1).get(0));
        WorldvaultChest.write(chest, WorldvaultBooks.pack("old.bin", data(3000))); // 2 books, same name
        VaultIndexV2.Entry v1 = VaultIndexV2.fromV1(new VaultIndex.Entry("old.bin", DIM, 0, 64, 0, 1, false));
        IOException ex = assertThrows(IOException.class, () -> VaultService.restoreTo(v1, w.resolver(), out(world)));
        assertTrue(ex.getMessage().startsWith("Chest 1 of 1: ") && ex.getMessage().contains("1 book"), ex.getMessage());
        assertNoOutput(world);
    }

    @Test void v2RowWithWrongBookCountFails() throws Exception {
        FakeWorld w = singles(2);
        VaultIndexStore index = new VaultIndexStore(world);
        VaultIndexV2.Entry e = VaultService.commit(VaultService.prepare("f.bin", data(SINGLE_CAP + 100), VaultTargets.targets(w, row(2)), index));
        List<VaultIndexV2.Chunk> chunks = new ArrayList<>(e.chunks());
        VaultIndexV2.Chunk c = chunks.get(1);
        chunks.set(1, new VaultIndexV2.Chunk(c.dimension(), c.x(), c.y(), c.z(), c.books() + 1, c.doubleChest(), c.crc32()));
        VaultIndexV2.Entry edited = new VaultIndexV2.Entry(e.name(), e.size(), e.sha256(), chunks);
        IOException ex = assertThrows(IOException.class, () -> VaultService.restoreTo(edited, w.resolver(), out(world)));
        assertTrue(ex.getMessage().startsWith("Chest 2 of 2: "), ex.getMessage());
        assertNoOutput(world);
    }

    // ---- C10: a hopper pulling books out between store and restore ----

    @Test void hopperPullingOneBookMakesRestoreFailWithNoOutput() throws Exception {
        FakeWorld w = singles(2);
        VaultIndexStore index = new VaultIndexStore(world);
        VaultService.commit(VaultService.prepare("f.bin", data(SINGLE_CAP + 100), VaultTargets.targets(w, row(2)), index));
        // HopperBlockEntity takes one item at a time with Container.removeItem(slot, 1)
        Container second = (Container) w.blocks.get(row(2).get(1));
        assertFalse(second.removeItem(0, 1).isEmpty());
        IOException ex = assertThrows(IOException.class, () -> VaultService.restoreTo(index.load().get(0), w.resolver(), out(world)));
        assertTrue(ex.getMessage().startsWith("Chest 2 of 2: "), ex.getMessage());
        assertNoOutput(world);
    }

    // ---- must remain true: exact bytes after a save and reload of every chest ----

    @Test void restoredBytesAreExactAfterEveryChestIsSavedAndReloaded() throws Exception {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        FakeWorld w = new FakeWorld();
        ChestBlockEntity[] pair = w.pair(new BlockPos(0, 64, 0));
        ChestBlockEntity[] pair2 = w.pair(new BlockPos(4, 64, 0));
        VaultIndexStore index = new VaultIndexStore(world);
        byte[] original = data(54 * 2048 + 4000);
        VaultService.commit(VaultService.prepare("r.bin", original,
                VaultTargets.targets(w, List.of(pair[1].getBlockPos(), pair2[0].getBlockPos())), index));
        FakeWorld reloaded = new FakeWorld();
        int n = 0;
        for (var e : w.blocks.entrySet()) {
            Path saved = world.resolve("chest-" + n++ + ".nbt");
            NbtIo.writeCompressed(e.getValue().saveWithFullMetadata(registries), saved);
            BlockEntity back = BlockEntity.loadStatic(e.getKey(), e.getValue().getBlockState(),
                    NbtIo.readCompressed(saved, NbtAccounter.unlimitedHeap()), registries);
            reloaded.blocks.put(e.getKey(), back);
        }
        VaultIndexStore fresh = new VaultIndexStore(world); // a new process reads the index from disk
        assertArrayEquals(original, VaultService.restoreTo(fresh.load().get(0), reloaded.resolver(), out(world)).bytes());
        assertArrayEquals(original, Files.readAllBytes(out(world).resolve("r.bin")));
        assertTrue(Files.readString(world.resolve("worldvault/index.tsv"), StandardCharsets.UTF_8).startsWith(VaultIndexV2.HEADER));
    }
}
