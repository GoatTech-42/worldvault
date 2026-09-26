package dev.luke;

import java.nio.file.Files;

public class VaultIndexTest {
    @org.junit.jupiter.api.Test
    public void indexPersistsAndRejectsDuplicates() throws Exception {
        var world = Files.createTempDirectory("worldvault-test");
        VaultIndex index = new VaultIndex(world);
        var entry = new VaultIndex.Entry("pic\ttrue.png", "minecraft:overworld", 1, 64, -10, 4);
        if (!index.load().isEmpty()) throw new AssertionError("New index is not empty");
        index.add(entry);
        if (!index.load().equals(java.util.List.of(entry))) throw new AssertionError("Index did not persist");
        try { index.add(entry); throw new AssertionError("Duplicate allowed"); }
        catch (java.io.IOException expected) { }
        if (!index.load().equals(java.util.List.of(entry))) throw new AssertionError("Duplicate damaged index");
        index.remove(entry);
        if (!index.load().isEmpty()) throw new AssertionError("Rollback removal failed");
    }
}
