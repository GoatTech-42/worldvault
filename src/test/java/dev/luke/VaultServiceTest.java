package dev.luke;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Multi-chest store/restore through VaultService (the code the commands call), using the game's chest classes. */
class VaultServiceTest {
    @TempDir Path world;
    static final String DIM = "minecraft:overworld";
    static final int CHUNK = 27 * 2048 - 269;

    @BeforeAll static void initializeGame() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    static byte[] data(int size) {
        byte[] b = new byte[size];
        new Random(size).nextBytes(b);
        return b;
    }

    /** Chests keyed by position, like a world. */
    static final class Chests {
        final Map<BlockPos, ChestBlockEntity> map = new HashMap<>();
        List<VaultService.Target> targets = new ArrayList<>();
        Chests(int count) {
            for (int i = 0; i < count; i++) {
                BlockPos pos = new BlockPos(i * 2, 64, 0);
                ChestBlockEntity c = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
                map.put(pos, c);
                targets.add(new VaultService.Target(DIM, pos, false, c, List.of(pos)));
            }
        }
        ChestBlockEntity at(int i) { return map.get(new BlockPos(i * 2, 64, 0)); }
        VaultService.ChestResolver resolver() {
            return chunk -> {
                ChestBlockEntity c = map.get(new BlockPos(chunk.x(), chunk.y(), chunk.z()));
                if (c == null) throw new IOException("Chest at " + chunk.x() + " " + chunk.y() + " " + chunk.z() + " is gone");
                return c;
            };
        }
        String snapshot() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < map.size(); i++) {
                for (int s = 0; s < 27; s++) {
                    ItemStack st = at(i).getItem(s);
                    sb.append(st.isEmpty() ? "." : st.getComponents().toString().hashCode()).append(',');
                }
                sb.append('|');
            }
            return sb.toString();
        }
    }

    @Test void multiChestRoundTripsAcrossSizesUpToOneMegabyte() throws Exception {
        int[] sizes = {0, 1, CHUNK - 1, CHUNK, CHUNK + 1, 3 * CHUNK, 100_000};
        for (int size : sizes) {
            Chests chests = new Chests(Math.max(1, (size + CHUNK - 1) / CHUNK) + 1);
            VaultIndexStore index = new VaultIndexStore(world.resolve("w" + size));
            byte[] original = data(size);
            VaultIndexV2.Entry entry = VaultService.commit(VaultService.prepare("f.bin", original, chests.targets, index));
            assertEquals(Math.max(1, (size + CHUNK - 1) / CHUNK), entry.chunks().size(), "chests for " + size);
            assertEquals(entry, index.load().get(0));
            assertArrayEquals(original, VaultService.restore(index.load().get(0), chests.resolver()).bytes(), "size " + size);
        }
        // about 1 MB over 20 chests
        Chests chests = new Chests(20);
        VaultIndexStore index = new VaultIndexStore(world.resolve("big"));
        byte[] original = data(1_000_000);
        VaultService.commit(VaultService.prepare("big.bin", original, chests.targets, index));
        assertEquals(19, index.load().get(0).chunks().size());
        assertArrayEquals(original, VaultService.restore(index.load().get(0), chests.resolver()).bytes());
        assertTrue(chests.at(19).getItem(0).isEmpty(), "the unused 20th chest is untouched");
    }

    @Test void tooFewChestsFailsAndWritesNothing() throws Exception {
        Chests chests = new Chests(2);
        VaultIndexStore index = new VaultIndexStore(world);
        String before = chests.snapshot();
        assertThrows(IllegalArgumentException.class,
                () -> VaultService.prepare("f.bin", data(3 * CHUNK), chests.targets, index));
        assertEquals(before, chests.snapshot());
        assertTrue(index.load().isEmpty());
    }

    @Test void occupiedTargetFailsBeforeAnyChestIsWritten() throws Exception {
        for (int occupied = 0; occupied < 3; occupied++) {
            Chests chests = new Chests(3);
            chests.at(occupied).setItem(5, new ItemStack(Items.DIAMOND, 3));
            VaultIndexStore index = new VaultIndexStore(world.resolve("o" + occupied));
            String before = chests.snapshot();
            assertThrows(IllegalArgumentException.class,
                    () -> VaultService.prepare("f.bin", data(3 * CHUNK), chests.targets, index));
            assertEquals(before, chests.snapshot());
            assertTrue(index.load().isEmpty(), "no index row reserved");
            assertEquals(3, chests.at(occupied).getItem(5).getCount());
        }
    }

    @Test void duplicateOrAlreadyRegisteredChestIsRejected() throws Exception {
        Chests chests = new Chests(2);
        VaultIndexStore index = new VaultIndexStore(world);
        List<VaultService.Target> twice = List.of(chests.targets.get(0), chests.targets.get(0));
        assertThrows(IllegalArgumentException.class, () -> VaultService.prepare("f.bin", data(CHUNK + 1), twice, index));
        VaultService.commit(VaultService.prepare("a.bin", data(10), chests.targets.subList(0, 1), index));
        Chests other = new Chests(1); // same coordinates, fresh empty chest
        assertThrows(IllegalArgumentException.class, () -> VaultService.prepare("b.bin", data(10), other.targets, index));
        assertThrows(IllegalArgumentException.class, () -> VaultService.prepare("a.bin", data(10), chests.targets.subList(1, 2), index));
    }

    @Test void missingChestFailsCleanlyAndWritesNothingToDisk() throws Exception {
        Chests chests = new Chests(3);
        VaultIndexStore index = new VaultIndexStore(world);
        byte[] original = data(3 * CHUNK - 10);
        VaultService.commit(VaultService.prepare("f.bin", original, chests.targets, index));
        chests.map.remove(new BlockPos(2, 64, 0)); // chest 2 broken
        IOException ex = assertThrows(IOException.class, () -> VaultService.restore(index.load().get(0), chests.resolver()));
        assertTrue(ex.getMessage().contains("gone"), ex.getMessage());
        // restore returns bytes only on full success; nothing is produced
        assertEquals(1, index.load().size());
    }

    @Test void changedChestFailsCleanly() throws Exception {
        Chests chests = new Chests(3);
        VaultIndexStore index = new VaultIndexStore(world);
        byte[] original = data(3 * CHUNK - 10);
        VaultService.commit(VaultService.prepare("f.bin", original, chests.targets, index));
        // 1) a book inside chest 2 is swapped for a well-formed book of different data: the chunk CRC catches it
        Chests donor = new Chests(1);
        byte[] other = data(CHUNK);
        VaultService.commit(VaultService.prepare("f.bin", other, donor.targets, new VaultIndexStore(world.resolve("donor"))));
        for (int s = 0; s < 27; s++) chests.at(1).setItem(s, donor.at(0).getItem(s).copy());
        IOException swapped = assertThrows(IOException.class, () -> VaultService.restore(index.load().get(0), chests.resolver()));
        assertTrue(swapped.getMessage().contains("Chunk 2") && swapped.getMessage().contains("changed"), swapped.getMessage());
        // 2) a book removed from chest 3 is caught by the book checks, naming the chest
        Chests c2 = new Chests(3);
        VaultIndexStore index2 = new VaultIndexStore(world.resolve("two"));
        VaultService.commit(VaultService.prepare("f.bin", original, c2.targets, index2));
        c2.at(2).setItem(0, ItemStack.EMPTY);
        IOException removed = assertThrows(IOException.class, () -> VaultService.restore(index2.load().get(0), c2.resolver()));
        assertTrue(removed.getMessage().startsWith("Chest 3 of 3"), removed.getMessage());
        // 3) a foreign item dropped into chest 1
        Chests c3 = new Chests(3);
        VaultIndexStore index3 = new VaultIndexStore(world.resolve("three"));
        VaultService.commit(VaultService.prepare("f.bin", original, c3.targets, index3));
        c3.at(0).setItem(26, new ItemStack(Items.DIRT));
        assertThrows(IOException.class, () -> VaultService.restore(index3.load().get(0), c3.resolver()));
    }

    @Test void failedWriteRollsBackEveryChestAndTheIndexRow() throws Exception {
        Chests chests = new Chests(3);
        // third chest rejects writes
        ChestBlockEntity bad = new ChestBlockEntity(new BlockPos(4, 64, 0), Blocks.CHEST.defaultBlockState()) {
            @Override public void setItem(int slot, ItemStack stack) { throw new IllegalStateException("disk full"); }
        };
        List<VaultService.Target> targets = new ArrayList<>(chests.targets.subList(0, 2));
        targets.add(new VaultService.Target(DIM, bad.getBlockPos(), false, bad, List.of(bad.getBlockPos())));
        VaultIndexStore index = new VaultIndexStore(world);
        String before = chests.snapshot();
        var plan = VaultService.prepare("f.bin", data(3 * CHUNK - 10), targets, index);
        assertThrows(IllegalStateException.class, () -> VaultService.commit(plan));
        assertEquals(before, chests.snapshot(), "earlier chests were emptied again");
        assertTrue(index.load().isEmpty());
    }

    @Test void v1VaultStillRestoresAndMigratesOnNextStore() throws Exception {
        Chests chests = new Chests(2);
        byte[] original = data(5000);
        WorldvaultChest.write(chests.at(0), WorldvaultBooks.pack("old.bin", original));
        Path dir = world.resolve("worldvault");
        Files.createDirectories(dir);
        String name = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("old.bin".getBytes(StandardCharsets.UTF_8));
        Files.writeString(dir.resolve("index.tsv"), name + "\t" + DIM + "\t0\t64\t0\t" + WorldvaultBooks.pack("old.bin", original).size() + "\n");
        VaultIndexStore index = new VaultIndexStore(world);
        VaultIndexV2.Entry old = index.load().get(0);
        assertEquals(-1, old.size());
        assertArrayEquals(original, VaultService.restore(old, chests.resolver()).bytes());
        // storing a new file migrates the index to v2 and the v1 vault still reads
        byte[] fresh = data(2 * CHUNK);
        Chests more = new Chests(3);
        List<VaultService.Target> t = List.of(
                new VaultService.Target(DIM, new BlockPos(100, 64, 0), false, more.at(0), List.of(new BlockPos(100, 64, 0))),
                new VaultService.Target(DIM, new BlockPos(102, 64, 0), false, more.at(1), List.of(new BlockPos(102, 64, 0))));
        VaultService.commit(VaultService.prepare("new.bin", fresh, t, index));
        assertTrue(Files.readString(dir.resolve("index.tsv")).startsWith(VaultIndexV2.HEADER));
        List<VaultIndexV2.Entry> all = index.load();
        assertEquals(2, all.size());
        assertArrayEquals(original, VaultService.restore(all.get(0), chests.resolver()).bytes());
    }

    @Test void doubleChestTargetsMixWithSingles() throws Exception {
        net.minecraft.world.level.block.state.BlockState l = Blocks.CHEST.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.LEFT);
        ChestBlockEntity left = new ChestBlockEntity(new BlockPos(0, 64, 0), l);
        ChestBlockEntity right = new ChestBlockEntity(new BlockPos(1, 64, 0), Blocks.CHEST.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.RIGHT));
        Container dbl = WorldvaultChest.combine(left, right);
        ChestBlockEntity single = new ChestBlockEntity(new BlockPos(5, 64, 0), Blocks.CHEST.defaultBlockState());
        List<VaultService.Target> t = List.of(
                new VaultService.Target(DIM, left.getBlockPos(), true, dbl, List.of(left.getBlockPos(), right.getBlockPos())),
                new VaultService.Target(DIM, single.getBlockPos(), false, single, List.of(single.getBlockPos())));
        // one chunk is limited by the smallest chest (27 slots), so two chunks are used
        byte[] original = data(CHUNK + 500);
        VaultIndexStore index = new VaultIndexStore(world);
        VaultIndexV2.Entry e = VaultService.commit(VaultService.prepare("m.bin", original, t, index));
        assertEquals(2, e.chunks().size());
        assertTrue(e.chunks().get(0).doubleChest());
        assertArrayEquals(original, VaultService.restore(e, chunk -> chunk.doubleChest() ? dbl : single).bytes());
        // a target that overlaps a double chest's other half is rejected
        ChestBlockEntity clash = new ChestBlockEntity(right.getBlockPos(), Blocks.CHEST.defaultBlockState());
        assertThrows(IllegalArgumentException.class, () -> VaultService.prepare("x.bin", data(CHUNK + 1), List.of(t.get(0),
                new VaultService.Target(DIM, clash.getBlockPos(), false, clash, List.of(clash.getBlockPos()))), new VaultIndexStore(world.resolve("c"))));
    }
}
