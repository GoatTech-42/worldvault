package dev.luke;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Version 2 index text format: a file may span several chests, in order.
 * First line is {@code #worldvault-index v2}. Each row is tab separated:
 * name (base64url), size in bytes, whole-file SHA-256 (hex, or "-" when unknown), then one
 * chunk field per chest in order: {@code dimension|x|y|z|books|layout|crc32hex}.
 * A file without the header is a v1 index and is read through {@link #fromV1}.
 */
public final class VaultIndexV2 {
    public static final String HEADER = "#worldvault-index v2";

    public record Chunk(String dimension, int x, int y, int z, int books, boolean doubleChest, long crc32) {}

    public record Entry(String name, long size, String sha256, List<Chunk> chunks) {
        public Entry { chunks = List.copyOf(chunks); }
    }

    private VaultIndexV2() {}

    /** Wraps a v1 entry as a single-chunk v2 entry; size and hashes are unknown for v1 vaults. */
    public static Entry fromV1(VaultIndex.Entry old) {
        return new Entry(old.name(), -1, "-", List.of(
                new Chunk(old.dimension(), old.x(), old.y(), old.z(), old.books(), old.doubleChest(), -1)));
    }

    public static boolean isV2(String text) {
        return text.startsWith(HEADER);
    }

    public static String write(List<Entry> entries) {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (Entry e : entries) {
            out.append(Base64.getUrlEncoder().withoutPadding().encodeToString(e.name().getBytes(StandardCharsets.UTF_8)))
                    .append('\t').append(e.size()).append('\t').append(e.sha256());
            for (Chunk c : e.chunks()) {
                out.append('\t').append(c.dimension()).append('|').append(c.x()).append('|').append(c.y()).append('|')
                        .append(c.z()).append('|').append(c.books()).append('|')
                        .append(c.doubleChest() ? "double" : "single").append('|')
                        .append(c.crc32() < 0 ? "-" : Long.toHexString(c.crc32()));
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** Reads a v2 index, or a v1 index (migrated in memory). */
    public static List<Entry> read(String text) throws IOException {
        List<Entry> entries = new ArrayList<>();
        boolean v2 = isV2(text);
        String[] lines = text.split("\n", -1);
        for (int i = v2 ? 1 : 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            try {
                entries.add(v2 ? parseV2(line) : fromV1(parseV1(line)));
            } catch (IllegalArgumentException ex) { throw new IOException("Bad vault index row", ex); }
        }
        return List.copyOf(entries);
    }

    private static VaultIndex.Entry parseV1(String line) throws IOException {
        String[] f = line.split("\\t", -1);
        if (f.length != 6 && f.length != 7) throw new IOException("Bad vault index row");
        if (f.length == 7 && !f[6].equals("double")) throw new IllegalArgumentException("Bad vault layout");
        String name = new String(Base64.getUrlDecoder().decode(f[0]), StandardCharsets.UTF_8);
        return new VaultIndex.Entry(name, f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                Integer.parseInt(f[4]), Integer.parseInt(f[5]), f.length == 7);
    }

    private static Entry parseV2(String line) {
        String[] f = line.split("\\t", -1);
        if (f.length < 4) throw new IllegalArgumentException("Bad vault index row");
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 3; i < f.length; i++) {
            String[] c = f[i].split("\\|", -1);
            if (c.length != 7) throw new IllegalArgumentException("Bad chunk");
            boolean dbl = switch (c[5]) {
                case "double" -> true;
                case "single" -> false;
                default -> throw new IllegalArgumentException("Bad vault layout");
            };
            chunks.add(new Chunk(c[0], Integer.parseInt(c[1]), Integer.parseInt(c[2]), Integer.parseInt(c[3]),
                    Integer.parseInt(c[4]), dbl, c[6].equals("-") ? -1 : Long.parseLong(c[6], 16)));
        }
        return new Entry(new String(Base64.getUrlDecoder().decode(f[0]), StandardCharsets.UTF_8),
                Long.parseLong(f[1]), f[2], chunks);
    }
}
