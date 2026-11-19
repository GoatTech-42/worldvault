package dev.luke;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class VaultChunkerTest {
    private static byte[] data(int n) { byte[] b = new byte[n]; new Random(42).nextBytes(b); return b; }

    private static VaultIndexV2.Entry entry(VaultChunker.Split s) {
        var chunks = s.pieces().stream()
                .map(p -> new VaultIndexV2.Chunk("minecraft:overworld", 0, 64, 0, 27, false, p.crc32())).toList();
        return new VaultIndexV2.Entry("f.bin", s.size(), s.sha256(), chunks);
    }

    private static List<byte[]> bytes(VaultChunker.Split s) { return s.pieces().stream().map(VaultChunker.Piece::data).toList(); }

    @Test
    void splitsAndRejoinsUpToOneMegabyte() throws Exception {
        for (int n : new int[] {0, 1, 999, 1000, 1001, 65_537, 1_000_000}) {
            byte[] d = data(n);
            var s = VaultChunker.split(d, 1000);
            assertEquals((int) Math.max(1, Math.ceil(n / 1000.0)), s.pieces().size());
            assertArrayEquals(d, VaultChunker.join(bytes(s), entry(s)));
        }
    }

    @Test
    void survivesIndexRoundTrip() throws Exception {
        byte[] d = data(5000);
        var s = VaultChunker.split(d, 1024);
        var read = VaultIndexV2.read(VaultIndexV2.write(List.of(entry(s)))).get(0);
        assertArrayEquals(d, VaultChunker.join(bytes(s), read));
    }

    @Test
    void tamperedChunkFailsCleanly() {
        var s = VaultChunker.split(data(5000), 1024);
        var b = new java.util.ArrayList<>(bytes(s));
        byte[] t = b.get(2).clone(); t[0] ^= 1; b.set(2, t);
        var ex = assertThrows(VaultChunker.VerifyException.class, () -> VaultChunker.join(b, entry(s)));
        assertTrue(ex.getMessage().contains("Chunk 3"));
    }

    @Test
    void swappedOrMissingChunksFail() {
        var s = VaultChunker.split(data(5000), 1024);
        var swapped = new java.util.ArrayList<>(bytes(s));
        java.util.Collections.swap(swapped, 0, 1);
        assertThrows(VaultChunker.VerifyException.class, () -> VaultChunker.join(swapped, entry(s)));
        assertThrows(VaultChunker.VerifyException.class, () -> VaultChunker.join(bytes(s).subList(0, 2), entry(s)));
        var nul = new java.util.ArrayList<>(bytes(s)); nul.set(1, null);
        assertThrows(VaultChunker.VerifyException.class, () -> VaultChunker.join(nul, entry(s)));
    }

    @Test
    void wholeFileShaCatchesMatchingCrcButWrongHash() {
        var s = VaultChunker.split(data(3000), 1024);
        var bad = new VaultIndexV2.Entry("f", s.size(), "00".repeat(32), entry(s).chunks());
        assertThrows(VaultChunker.VerifyException.class, () -> VaultChunker.join(bytes(s), bad));
    }

    @Test
    void v1MigratedEntryHasNothingToVerifyButJoins() throws Exception {
        byte[] d = data(100);
        var e = VaultIndexV2.fromV1(new VaultIndex.Entry("a", "minecraft:overworld", 0, 0, 0, 1, false));
        assertArrayEquals(d, VaultChunker.join(List.of(d), e));
    }
}
