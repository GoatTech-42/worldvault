package dev.luke;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The index file as the commands use it. Reads v1 and v2 files; every write produces a v2 file, so an
 * old v1 index is migrated the first time something is stored. Books stay authoritative.
 */
public final class VaultIndexStore {
    private final Path path;
    public VaultIndexStore(Path worldFolder) { path = worldFolder.resolve("worldvault/index.tsv"); }

    public List<VaultIndexV2.Entry> load() throws IOException {
        if (!Files.exists(path)) return List.of();
        return VaultIndexV2.read(Files.readString(path, StandardCharsets.UTF_8));
    }

    /** Rejects a reused name or any chest position that already holds a vault. */
    public void add(VaultIndexV2.Entry entry) throws IOException {
        List<VaultIndexV2.Entry> entries = new ArrayList<>(load());
        for (VaultIndexV2.Entry old : entries) {
            if (old.name().equals(entry.name())) throw new IOException("Vault name already used");
            for (VaultIndexV2.Chunk a : old.chunks()) for (VaultIndexV2.Chunk b : entry.chunks()) {
                if (samePos(a, b)) throw new IOException("Chest already holds a vault");
            }
        }
        entries.add(entry);
        write(entries);
    }

    public void remove(VaultIndexV2.Entry entry) throws IOException {
        List<VaultIndexV2.Entry> entries = new ArrayList<>(load());
        if (entries.remove(entry)) write(entries);
    }

    /** The index file exactly as it is on disk, or null when there is none; see {@link #restoreRaw}. */
    public byte[] raw() throws IOException {
        return Files.exists(path) ? Files.readAllBytes(path) : null;
    }

    /** Puts back what {@link #raw} returned: the same bytes, or no file at all. */
    public void restoreRaw(byte[] raw) throws IOException {
        if (raw == null) { Files.deleteIfExists(path); return; }
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "index-", ".tmp");
        try {
            Files.write(temporary, raw);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }

    static boolean samePos(VaultIndexV2.Chunk a, VaultIndexV2.Chunk b) {
        return a.dimension().equals(b.dimension()) && a.x() == b.x() && a.y() == b.y() && a.z() == b.z();
    }

    private void write(List<VaultIndexV2.Entry> entries) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "index-", ".tmp");
        try {
            Files.writeString(temporary, VaultIndexV2.write(entries), StandardCharsets.UTF_8);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }
}
