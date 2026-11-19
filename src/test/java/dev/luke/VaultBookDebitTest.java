package dev.luke;

import dev.luke.VaultGameplayGapsTest.FakeWorld;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static dev.luke.VaultGameplayGapsTest.SINGLE_CAP;
import static dev.luke.VaultGameplayGapsTest.indexBytes;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Book-and-quill debit on the store path. Every store here runs the same service calls, in the same order, as
 * {@code /worldvault store} (targets, prepare, commitAndTakeBooks) against the game's own player {@link Inventory}
 * and chest block entities; the player's inventory is compared slot by slot as serialized item data.
 */
class VaultBookDebitTest {
    @TempDir Path world;
    static RegistryAccess registries;

    @BeforeAll static void initializeGame() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    static byte[] data(int size) {
        byte[] b = new byte[size];
        new Random(4400 + size).nextBytes(b);
        return b;
    }

    static List<BlockPos> row(int count) {
        List<BlockPos> list = new ArrayList<>();
        for (int i = 0; i < count; i++) list.add(new BlockPos(i * 2, 64, 0));
        return list;
    }

    /** A player inventory as the game builds it (36 item slots plus armor, offhand, body and saddle). */
    static Inventory inventory() {
        return new Inventory(null, new EntityEquipment());
    }

    static ItemStack book() { return new ItemStack(Items.WRITABLE_BOOK); }

    /** Book-and-quills stack to 1; a max_stack_size component (as /give can set) lets one slot hold several. */
    static ItemStack bookStack(int count) {
        ItemStack stack = new ItemStack(Items.WRITABLE_BOOK);
        stack.set(DataComponents.MAX_STACK_SIZE, 16);
        stack.setCount(count);
        return stack;
    }

    /** Items the store has no business touching, each with components that a careless copy would lose. */
    static void addBystanders(Inventory inv) {
        ItemStack diamonds = new ItemStack(Items.DIAMOND, 37);
        diamonds.set(DataComponents.CUSTOM_NAME, Component.literal("savings"));
        inv.setItem(0, diamonds);
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.set(DataComponents.DAMAGE, 17);
        sword.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("first sword"))));
        inv.setItem(1, sword);
        inv.setItem(7, new ItemStack(Items.BOOK, 12));
        inv.setItem(30, new ItemStack(Items.FEATHER, 64));
        inv.setItem(Inventory.SLOT_OFFHAND, new ItemStack(Items.SHIELD));
    }

    /** Every slot, serialized with the game's item codec: item, count and every data component. */
    static byte[] bytes(Inventory inv) throws IOException {
        CompoundTag all = new CompoundTag();
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            all.put("slot" + slot, ItemStack.OPTIONAL_CODEC.encodeStart(ops, inv.getItem(slot)).getOrThrow());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.write(all, new DataOutputStream(out));
        return out.toByteArray();
    }

    static void assertSameInventory(byte[] expected, Inventory actual, String what) throws IOException {
        assertArrayEquals(expected, bytes(actual), what);
    }

    /** {@code /worldvault store} from the target lookup on: targets, prepare, then commit and take the books. */
    static VaultIndexV2.Entry store(FakeWorld w, List<BlockPos> chests, String name, byte[] file, VaultIndexStore index,
                                    Inventory inv, VaultService.Hooks hooks) throws IOException {
        VaultService.Plan plan = VaultService.prepare(name, file, VaultTargets.targets(w, chests), index);
        return VaultService.commitAndTakeBooks(plan, inv, hooks);
    }

    static VaultIndexV2.Entry store(FakeWorld w, List<BlockPos> chests, String name, byte[] file, VaultIndexStore index,
                                    Inventory inv) throws IOException {
        return store(w, chests, name, file, index, inv, VaultService.NO_HOOKS);
    }

    static FakeWorld singles(int count) {
        FakeWorld w = new FakeWorld();
        for (BlockPos p : row(count)) w.single(p);
        return w;
    }

    static int booksFor(int size) {
        return WorldvaultBooks.pack("f.bin", new byte[size]).size();
    }

    // ---- 1. A successful store takes exactly its books, and only once the commit is done ----

    @Test void successfulStoreTakesExactlyTheBooksItWroteAfterTheChestsAndIndexRow() throws Exception {
        FakeWorld w = singles(2);
        VaultIndexStore index = new VaultIndexStore(world);
        byte[] file = data(SINGLE_CAP + 1000);
        Inventory inv = inventory();
        addBystanders(inv);
        // 30 blanks: singles in slots 3, 4 and 9, a stack of 10 in slot 20, 16 singles in slots 21-37 (not 30), offhand
        inv.setItem(3, book());
        inv.setItem(4, book());
        inv.setItem(9, book());
        inv.setItem(20, bookStack(10));
        for (int slot = 21; slot < 38; slot++) if (slot != 30) inv.setItem(slot, book());
        inv.setItem(Inventory.SLOT_OFFHAND, book());
        int blanksBefore = VaultService.blankBooks(inv);
        byte[] before = bytes(inv);
        int timesBefore = inv.getTimesChanged();

        VaultService.Plan plan = VaultService.prepare("f.bin", file, VaultTargets.targets(w, row(2)), index);
        int needed = plan.totalBooks();
        assertEquals(28, needed, "fixture: the file needs 27 books in chest 1 and one in chest 2");
        List<String> seenDuringCommit = new ArrayList<>();
        VaultService.commitAndTakeBooks(plan, inv, new VaultService.Hooks() {
            @Override public void afterChestWrites() {
                try {
                    assertSameInventory(before, inv, "books were taken before the commit finished");
                    assertNull(indexBytes(world), "index row written before the chests");
                    seenDuringCommit.add("checked");
                } catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
            }
        });
        assertEquals(List.of("checked"), seenDuringCommit);

        // the commit really happened: index row, books in both chests, exact bytes back
        assertEquals(1, index.load().size());
        assertEquals(27, WorldvaultChest.bookCount((ChestBlockEntity) w.blocks.get(row(2).get(0))));
        assertEquals(1, WorldvaultChest.bookCount((ChestBlockEntity) w.blocks.get(row(2).get(1))));
        assertArrayEquals(file, VaultService.restore(index.load().get(0), w.resolver()).bytes());

        // exactly 28 taken, first slot first: 3, 4, 9, all 10 of slot 20, then the singles in 21-29 and 31-36
        assertEquals(blanksBefore - needed, VaultService.blankBooks(inv));
        assertEquals(2, VaultService.blankBooks(inv));
        Inventory expected = inventory();
        addBystanders(expected);
        expected.setItem(37, book());
        expected.setItem(Inventory.SLOT_OFFHAND, book());
        assertSameInventory(bytes(expected), inv, "inventory after a store");
        assertTrue(inv.getTimesChanged() > timesBefore, "inventory not marked changed after taking books");
    }

    @Test void partialStackIsShrunkInPlaceKeepingItsComponents() throws Exception {
        FakeWorld w = singles(1);
        VaultIndexStore index = new VaultIndexStore(world);
        byte[] file = data(5 * 2048);
        int needed = booksFor(file.length);
        assertEquals(6, needed);
        Inventory inv = inventory();
        addBystanders(inv);
        inv.setItem(5, book());
        inv.setItem(12, bookStack(9));
        store(w, row(1), "f.bin", file, index, inv);
        Inventory expected = inventory();
        addBystanders(expected);
        expected.setItem(12, bookStack(4)); // slot 5 gave one, slot 12 gave five
        assertSameInventory(bytes(expected), inv, "partial stack after a store");
        assertEquals(16, inv.getItem(12).getMaxStackSize(), "the stack's own components survive");
    }

    @Test void exactlyEnoughBooksAreAllTakenAndNothingElse() throws Exception {
        FakeWorld w = singles(1);
        VaultIndexStore index = new VaultIndexStore(world);
        byte[] file = data(3 * 2048);
        int needed = booksFor(file.length);
        Inventory inv = inventory();
        addBystanders(inv);
        for (int i = 0; i < needed; i++) inv.setItem(10 + i * 2, book());
        store(w, row(1), "f.bin", file, index, inv);
        assertEquals(0, VaultService.blankBooks(inv));
        Inventory expected = inventory();
        addBystanders(expected);
        assertSameInventory(bytes(expected), inv, "inventory after spending every book");
    }

    // ---- 2. Too few book-and-quills: refused, nothing taken, nothing written ----

    @Test void tooFewBooksIsRefusedAndTakesNoneAndWritesNothing() throws Exception {
        FakeWorld w = singles(2);
        VaultIndexStore index = new VaultIndexStore(world);
        byte[] file = data(SINGLE_CAP + 1000);
        Inventory inv = inventory();
        addBystanders(inv);
        inv.setItem(2, book());
        inv.setItem(20, bookStack(16));
        for (int slot = 21; slot < 31; slot++) if (slot != 30) inv.setItem(slot, book());
        assertEquals(26, VaultService.blankBooks(inv), "fixture: two short of the 28 needed");
        byte[] before = bytes(inv);
        int timesBefore = inv.getTimesChanged();
        var chestsBefore = w.snapshot();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> store(w, row(2), "f.bin", file, index, inv));
        assertEquals("Need 28 book-and-quills in your inventory", ex.getMessage());
        assertSameInventory(before, inv, "a refused store took books");
        assertEquals(timesBefore, inv.getTimesChanged());
        w.assertUnchanged(chestsBefore);
        assertNull(indexBytes(world), "a refused store wrote an index row");
    }

    @Test void noBooksAtAllIsRefused() throws Exception {
        FakeWorld w = singles(1);
        VaultIndexStore index = new VaultIndexStore(world);
        Inventory inv = inventory();
        addBystanders(inv);
        byte[] before = bytes(inv);
        var chestsBefore = w.snapshot();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> store(w, row(1), "f.bin", data(100), index, inv));
        assertEquals("Need 1 book-and-quills in your inventory", ex.getMessage());
        assertSameInventory(before, inv, "a refused store took items");
        w.assertUnchanged(chestsBefore);
        assertNull(indexBytes(world));
    }

    // ---- 3. Every prepare, commit and rollback failure takes no books ----

    /** Plenty of books, in singles, full stacks, a partial stack and the offhand. */
    static Inventory wellStocked() {
        Inventory inv = inventory();
        addBystanders(inv);
        inv.setItem(2, book());
        inv.setItem(8, bookStack(16));
        inv.setItem(9, bookStack(16));
        inv.setItem(10, bookStack(16));
        inv.setItem(11, bookStack(7));
        for (int slot = 12; slot < 16; slot++) inv.setItem(slot, bookStack(16));
        inv.setItem(Inventory.SLOT_OFFHAND, book());
        assertTrue(VaultService.blankBooks(inv) > 81, "fixture: enough books for a three-chest store");
        return inv;
    }

    void assertFailureTakesNone(FakeWorld w, List<BlockPos> chests, String name, byte[] file, VaultIndexStore index,
                                VaultService.Hooks hooks, Class<? extends Throwable> expected, String message)
            throws IOException {
        Inventory inv = wellStocked();
        byte[] before = bytes(inv);
        int timesBefore = inv.getTimesChanged();
        Throwable ex = assertThrows(expected, () -> store(w, chests, name, file, index, inv, hooks));
        if (message != null) assertTrue(ex.getMessage().contains(message), ex.getMessage());
        assertSameInventory(before, inv, "a failed store took books: " + ex.getMessage());
        assertEquals(timesBefore, inv.getTimesChanged(), "a failed store marked the inventory changed");
    }

    @Test void prepareFailuresTakeNoBooks() throws Exception {
        VaultIndexStore index = new VaultIndexStore(world);
        // missing chest
        FakeWorld missing = singles(1);
        assertFailureTakesNone(missing, List.of(row(1).get(0), new BlockPos(40, 64, 0)), "f.bin",
                data(100), index, VaultService.NO_HOOKS, IllegalArgumentException.class, "No chest at 40 64 0");
        // unloaded chest
        FakeWorld unloaded = singles(1);
        unloaded.single(new BlockPos(64, 64, 0));
        unloaded.unload(4, 0);
        assertFailureTakesNone(unloaded, List.of(row(1).get(0), new BlockPos(64, 64, 0)), "f.bin",
                data(SINGLE_CAP + 10), index, VaultService.NO_HOOKS, IllegalArgumentException.class, "not loaded");
        assertTrue(unloaded.forcedLoads.isEmpty(), "a failed store loaded a chunk");
        // occupied chest
        FakeWorld occupied = singles(2);
        ((ChestBlockEntity) occupied.blocks.get(row(2).get(1))).setItem(26, new ItemStack(Items.DIAMOND, 3));
        var occupiedBefore = occupied.snapshot();
        assertFailureTakesNone(occupied, row(2), "f.bin", data(SINGLE_CAP + 10), index,
                VaultService.NO_HOOKS, IllegalArgumentException.class, "Chest 2 of 2 is not empty");
        occupied.assertUnchanged(occupiedBefore);
        // too few chests for the file
        assertFailureTakesNone(singles(1), row(1), "f.bin", data(SINGLE_CAP + 10), index,
                VaultService.NO_HOOKS, IllegalArgumentException.class, "needs 2 chests");
        // a chest named twice
        assertFailureTakesNone(singles(1), List.of(row(1).get(0), row(1).get(0)), "f.bin", data(100), index,
                VaultService.NO_HOOKS, IllegalArgumentException.class, "listed twice");
        assertNull(indexBytes(world), "a failed prepare wrote an index row");
        // name already stored, and a chest already registered to another vault
        FakeWorld used = singles(3);
        Inventory first = wellStocked();
        store(used, List.of(row(3).get(0)), "f.bin", data(100), index, first);
        byte[] indexBefore = indexBytes(world);
        assertFailureTakesNone(used, List.of(row(3).get(1)), "f.bin", data(100), index,
                VaultService.NO_HOOKS, IllegalArgumentException.class, "already stored");
        ((ChestBlockEntity) used.blocks.get(row(3).get(0))).clearContent();
        assertFailureTakesNone(used, List.of(row(3).get(0)), "g.bin", data(100), index,
                VaultService.NO_HOOKS, IllegalArgumentException.class, "already registered");
        assertArrayEquals(indexBefore, indexBytes(world));
    }

    @Test void writeFailureInTheSecondOfThreeChestsTakesNoBooks() throws Exception {
        FakeWorld w = new FakeWorld();
        w.single(row(3).get(0));
        ChestBlockEntity bad = VaultGameplayGapsTest.failingAfter(row(3).get(1), 5);
        w.blocks.put(bad.getBlockPos(), bad);
        w.single(row(3).get(2));
        var chestsBefore = w.snapshot();
        assertFailureTakesNone(w, row(3), "f.bin", data(3 * SINGLE_CAP - 50), new VaultIndexStore(world),
                VaultService.NO_HOOKS, IllegalStateException.class, "disk full");
        w.assertUnchanged(chestsBefore);
        assertNull(indexBytes(world));
    }

    @Test void writeFailureInTheFirstChestTakesNoBooks() throws Exception {
        FakeWorld w = new FakeWorld();
        ChestBlockEntity bad = VaultGameplayGapsTest.failingAfter(row(2).get(0), 0);
        w.blocks.put(bad.getBlockPos(), bad);
        w.single(row(2).get(1));
        assertFailureTakesNone(w, row(2), "f.bin", data(SINGLE_CAP + 10), new VaultIndexStore(world),
                VaultService.NO_HOOKS, IllegalStateException.class, "disk full");
        assertNull(indexBytes(world));
    }

    @Test void rollbackThatCannotFinishTakesNoBooks() throws Exception {
        FakeWorld w = new FakeWorld();
        w.single(row(3).get(0));
        ChestBlockEntity stuck = VaultReviewFindingsTest.jammed(row(3).get(1));
        w.blocks.put(stuck.getBlockPos(), stuck);
        ChestBlockEntity failing = VaultGameplayGapsTest.failingAfter(row(3).get(2), 2);
        w.blocks.put(failing.getBlockPos(), failing);
        assertFailureTakesNone(w, row(3), "f.bin", data(3 * SINGLE_CAP - 50), new VaultIndexStore(world),
                VaultService.NO_HOOKS, VaultService.RollbackIncompleteException.class, "could not be put back");
        assertNull(indexBytes(world));
    }

    @Test void indexWriteFailureAfterTheChestWritesTakesNoBooks() throws Exception {
        FakeWorld w = singles(2);
        VaultIndexStore index = new VaultIndexStore(world);
        VaultService.Plan plan = VaultService.prepare("f.bin", data(SINGLE_CAP + 100), VaultTargets.targets(w, row(2)), index);
        Files.createDirectories(world.resolve("worldvault/index.tsv/blocked")); // the index cannot be read or replaced
        Inventory inv = wellStocked();
        byte[] before = bytes(inv);
        int timesBefore = inv.getTimesChanged();
        List<String> wrote = new ArrayList<>();
        assertThrows(IOException.class, () -> VaultService.commitAndTakeBooks(plan, inv, new VaultService.Hooks() {
            @Override public void afterChestWrites() { wrote.add("chests written"); }
        }));
        assertEquals(List.of("chests written"), wrote, "fixture: the failure must come after the chest writes");
        assertSameInventory(before, inv, "a failed index write took books");
        assertEquals(timesBefore, inv.getTimesChanged());
        for (BlockPos p : row(2)) {
            assertEquals(0, WorldvaultChest.bookCount((ChestBlockEntity) w.blocks.get(p)), "chest at " + p + " kept books");
        }
    }

    @Test void failureAfterTheChestWritesTakesNoBooks() throws Exception {
        FakeWorld w = singles(2);
        var chestsBefore = w.snapshot();
        assertFailureTakesNone(w, row(2), "f.bin", data(SINGLE_CAP + 100), new VaultIndexStore(world),
                new VaultService.Hooks() {
                    @Override public void afterChestWrites() { throw new IllegalStateException("world save failed"); }
                }, IllegalStateException.class, "world save failed");
        w.assertUnchanged(chestsBefore);
        assertNull(indexBytes(world));
    }

    @Test void crashAfterTheChestWritesTakesNoBooks() throws Exception {
        FakeWorld w = singles(2);
        assertFailureTakesNone(w, row(2), "f.bin", data(SINGLE_CAP + 100), new VaultIndexStore(world),
                new VaultService.Hooks() {
                    @Override public void afterChestWrites() { throw new VaultReviewFindingsTest.SimulatedCrash(); }
                }, VaultReviewFindingsTest.SimulatedCrash.class, "simulated crash");
        assertNull(indexBytes(world), "a crash left an index row");
    }

    @Test void chestFilledBetweenPrepareAndCommitTakesNoBooks() throws Exception {
        FakeWorld w = singles(3);
        VaultIndexStore index = new VaultIndexStore(world);
        VaultService.Plan plan = VaultService.prepare("f.bin", data(3 * SINGLE_CAP - 50), VaultTargets.targets(w, row(3)), index);
        ChestBlockEntity second = (ChestBlockEntity) w.blocks.get(row(3).get(1));
        second.setItem(13, new ItemStack(Items.DIAMOND, 5));
        var chestsBefore = w.snapshot();
        Inventory inv = wellStocked();
        byte[] before = bytes(inv);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> VaultService.commitAndTakeBooks(plan, inv));
        assertEquals("Chest is not empty", ex.getMessage());
        assertSameInventory(before, inv, "a failed commit took books");
        w.assertUnchanged(chestsBefore);
        assertNull(indexBytes(world));
    }

    @Test void aFailedStoreThenASuccessfulOneTakesBooksOnlyOnce() throws Exception {
        FakeWorld w = singles(2);
        VaultIndexStore index = new VaultIndexStore(world);
        Inventory inv = wellStocked();
        int blanks = VaultService.blankBooks(inv);
        byte[] file = data(SINGLE_CAP + 100);
        assertThrows(VaultReviewFindingsTest.SimulatedCrash.class, () -> store(w, row(2), "f.bin", file, index, inv,
                new VaultService.Hooks() {
                    @Override public void afterChestWrites() { throw new VaultReviewFindingsTest.SimulatedCrash(); }
                }));
        for (BlockPos p : row(2)) ((ChestBlockEntity) w.blocks.get(p)).clearContent();
        assertEquals(blanks, VaultService.blankBooks(inv));
        store(w, row(2), "f.bin", file, index, inv);
        assertEquals(blanks - booksFor(SINGLE_CAP) - booksFor(100), VaultService.blankBooks(inv));
        assertArrayEquals(file, VaultService.restore(index.load().get(0), w.resolver()).bytes());
    }

    // ---- 4. Other items and the books a store does not need stay exactly as they were ----

    @Test void storeLeavesEveryOtherSlotByteForByte() throws Exception {
        FakeWorld w = singles(1);
        VaultIndexStore index = new VaultIndexStore(world);
        Inventory inv = inventory();
        addBystanders(inv);
        ItemStack named = book();
        named.set(DataComponents.CUSTOM_NAME, Component.literal("spare"));
        inv.setItem(14, bookStack(3));
        inv.setItem(15, book());
        inv.setItem(27, named);
        inv.setItem(35, bookStack(12));
        inv.setItem(Inventory.SLOT_OFFHAND, book());
        byte[] file = data(2 * 2048 - 100);
        assertEquals(2, booksFor(file.length));
        store(w, row(1), "f.bin", file, index, inv);
        // two from slot 14's stack of three; slot 15, 27, 35 and the offhand are untouched
        Inventory expected = inventory();
        addBystanders(expected);
        expected.setItem(14, bookStack(1));
        expected.setItem(15, book());
        expected.setItem(27, named.copy());
        expected.setItem(35, bookStack(12));
        expected.setItem(Inventory.SLOT_OFFHAND, book());
        assertSameInventory(bytes(expected), inv, "a store changed slots it did not need");
    }

    /** A book-and-quill the player has started writing in. */
    static ItemStack drafted(String text) {
        ItemStack stack = new ItemStack(Items.WRITABLE_BOOK);
        stack.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(Filterable.passThrough(text))));
        return stack;
    }

    @Test void storeNeverTakesABookAndQuillWithWritingInIt() throws Exception {
        FakeWorld w = singles(1);
        VaultIndexStore index = new VaultIndexStore(world);
        Inventory inv = inventory();
        addBystanders(inv);
        inv.setItem(3, drafted("base coordinates: 120 64 -300"));
        inv.setItem(4, book());
        inv.setItem(5, book());
        byte[] file = data(2 * 2048 - 100);
        store(w, row(1), "f.bin", file, index, inv);
        Inventory expected = inventory();
        addBystanders(expected);
        expected.setItem(3, drafted("base coordinates: 120 64 -300"));
        assertSameInventory(bytes(expected), inv, "a store took a book the player had written in");
    }

    @Test void booksWithWritingDoNotCountTowardTheBooksNeeded() throws Exception {
        FakeWorld w = singles(1);
        VaultIndexStore index = new VaultIndexStore(world);
        Inventory inv = inventory();
        addBystanders(inv);
        inv.setItem(3, drafted("notes"));
        inv.setItem(4, book());
        byte[] before = bytes(inv);
        var chestsBefore = w.snapshot();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> store(w, row(1), "f.bin", data(2 * 2048 - 100), index, inv));
        assertEquals("Need 2 book-and-quills in your inventory", ex.getMessage());
        assertSameInventory(before, inv, "a refused store took books");
        w.assertUnchanged(chestsBefore);
        assertNull(indexBytes(world));
    }
}
