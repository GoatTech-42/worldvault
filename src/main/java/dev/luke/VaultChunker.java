package dev.luke;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;

/** Splits a file into ordered chunks with a CRC32 each and a whole-file SHA-256, and verifies on rejoin. */
public final class VaultChunker {
    public record Piece(byte[] data, long crc32) {}

    public record Split(long size, String sha256, List<Piece> pieces) {}

    /** Thrown when pieces are missing, changed, or out of order. */
    public static final class VerifyException extends Exception {
        public VerifyException(String message) { super(message); }
    }

    private VaultChunker() {}

    public static long crc(byte[] data) {
        CRC32 c = new CRC32();
        c.update(data);
        return c.getValue();
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Splits into pieces of at most chunkBytes each, in order. An empty file yields one empty piece. */
    public static Split split(byte[] data, int chunkBytes) {
        if (chunkBytes <= 0) throw new IllegalArgumentException("chunkBytes must be positive");
        List<Piece> pieces = new ArrayList<>();
        int off = 0;
        do {
            int len = Math.min(chunkBytes, data.length - off);
            byte[] p = java.util.Arrays.copyOfRange(data, off, off + len);
            pieces.add(new Piece(p, crc(p)));
            off += len;
        } while (off < data.length);
        return new Split(data.length, sha256(data), pieces);
    }

    /** Rejoins pieces in order, checking each CRC (when recorded, i.e. >= 0), then size and SHA-256 (when recorded). */
    public static byte[] join(List<byte[]> pieces, List<Long> crcs, long size, String sha256) throws VerifyException {
        if (pieces.size() != crcs.size()) throw new VerifyException("Expected " + crcs.size() + " chunks but got " + pieces.size());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < pieces.size(); i++) {
            byte[] p = pieces.get(i);
            if (p == null) throw new VerifyException("Chunk " + (i + 1) + " is missing");
            long want = crcs.get(i);
            if (want >= 0 && crc(p) != want) throw new VerifyException("Chunk " + (i + 1) + " changed (CRC mismatch)");
            out.writeBytes(p);
        }
        byte[] all = out.toByteArray();
        if (size >= 0 && all.length != size) throw new VerifyException("Size mismatch: expected " + size + " got " + all.length);
        if (sha256 != null && !sha256.equals("-") && !sha256.equalsIgnoreCase(sha256(all)))
            throw new VerifyException("SHA-256 mismatch");
        return all;
    }

    /** Convenience: verify against a v2 entry's recorded values. */
    public static byte[] join(List<byte[]> pieces, VaultIndexV2.Entry e) throws VerifyException {
        return join(pieces, e.chunks().stream().map(VaultIndexV2.Chunk::crc32).toList(), e.size(), e.sha256());
    }
}
