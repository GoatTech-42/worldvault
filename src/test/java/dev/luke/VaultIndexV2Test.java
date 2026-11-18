package dev.luke;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class VaultIndexV2Test {
    @Test
    void roundTripsMultiChunkEntry() throws IOException {
        var e = new VaultIndexV2.Entry("big file.bin", 1_000_000, "ab".repeat(32), List.of(
                new VaultIndexV2.Chunk("minecraft:overworld", 1, 64, 2, 27, true, 0xdeadbeefL),
                new VaultIndexV2.Chunk("minecraft:overworld", 4, 64, 2, 3, false, 7)));
        String text = VaultIndexV2.write(List.of(e));
        assertTrue(VaultIndexV2.isV2(text));
        assertEquals(List.of(e), VaultIndexV2.read(text));
    }

    @Test
    void readsV1AsSingleChunk() throws IOException {
        String name = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("a.txt".getBytes());
        String v1 = name + "\tminecraft:overworld\t1\t2\t3\t5\n" + name + "b\tminecraft:the_nether\t4\t5\t6\t9\tdouble\n";
        var read = VaultIndexV2.read(v1);
        assertEquals(2, read.size());
        assertEquals("a.txt", read.get(0).name());
        assertEquals(1, read.get(0).chunks().size());
        assertEquals(5, read.get(0).chunks().get(0).books());
        assertTrue(read.get(1).chunks().get(0).doubleChest());
        assertEquals(-1, read.get(0).size());
    }

    @Test
    void migrationIsStable() throws IOException {
        String name = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("a".getBytes());
        var migrated = VaultIndexV2.read(name + "\tminecraft:overworld\t1\t2\t3\t5\n");
        assertEquals(migrated, VaultIndexV2.read(VaultIndexV2.write(migrated)));
    }

    @Test
    void rejectsMalformedRows() {
        assertThrows(IOException.class, () -> VaultIndexV2.read(VaultIndexV2.HEADER + "\nbad\n"));
        assertThrows(IOException.class, () -> VaultIndexV2.read("only\tthree\tfields\n"));
    }
}
