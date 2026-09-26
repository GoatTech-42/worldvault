package dev.luke;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Small locator within the save, not a copy of the contents. Books remain authoritative. */
public final class VaultIndex {
    public record Entry(String name, String dimension, int x, int y, int z, int books) { }
    private final Path path;
    public VaultIndex(Path worldFolder) { path = worldFolder.resolve("worldvault/index.tsv"); }

    public List<Entry> load() throws IOException {
        if (!Files.exists(path)) return List.of();
        List<Entry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String[] fields = line.split("\\t", -1);
            if (fields.length != 6) throw new IOException("Bad vault index row");
            try {
                String name = new String(java.util.Base64.getUrlDecoder().decode(fields[0]), StandardCharsets.UTF_8);
                entries.add(new Entry(name, fields[1], Integer.parseInt(fields[2]), Integer.parseInt(fields[3]),
                        Integer.parseInt(fields[4]), Integer.parseInt(fields[5])));
            } catch (IllegalArgumentException ex) { throw new IOException("Bad vault index row", ex); }
        }
        return List.copyOf(entries);
    }

    public void add(Entry entry) throws IOException {
        List<Entry> entries = new ArrayList<>(load());
        for (Entry old : entries) {
            if (old.name().equals(entry.name())) throw new IOException("Vault name already used");
            if (old.dimension().equals(entry.dimension()) && old.x() == entry.x() && old.y() == entry.y()
                    && old.z() == entry.z()) throw new IOException("Chest already holds a vault");
        }
        entries.add(entry);
        write(entries);
    }

    public void remove(Entry entry) throws IOException {
        List<Entry> entries = new ArrayList<>(load());
        if (entries.remove(entry)) write(entries);
    }

    private void write(List<Entry> entries) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "index-", ".tmp");
        try {
            StringBuilder output = new StringBuilder();
            for (Entry entry : entries) {
                String name = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(entry.name().getBytes(StandardCharsets.UTF_8));
                output.append(name).append('\t').append(entry.dimension()).append('\t').append(entry.x())
                        .append('\t').append(entry.y()).append('\t').append(entry.z()).append('\t').append(entry.books())
                        .append('\n');
            }
            Files.writeString(temporary, output.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }
}
