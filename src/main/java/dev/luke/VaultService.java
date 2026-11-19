package dev.luke;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Multi-chest store and restore. A file is split into ordered chunks (CRC32 each, SHA-256 overall), one chunk
 * per chest, and rejoined with verification. Store is two steps: {@link #prepare} validates everything and
 * touches nothing; {@link #commit} writes. A failed write is rolled back, so no chest is left half written.
 */
public final class VaultService {
    private static final Logger LOGGER = LoggerFactory.getLogger("worldvault");
    private static final int RAW_PER_BOOK = 2048;
    private static final int HEADER_RESERVE = 269;

    private VaultService() {}

    /** One chest (or double chest) to fill. {@code blocks} lists every chest block it covers. */
    public record Target(String dimension, BlockPos pos, boolean doubleChest, Container space, List<BlockPos> blocks) {}

    public record Plan(String name, VaultIndexV2.Entry entry, List<Target> used, List<List<WorldvaultBooks.Book>> books,
                       VaultIndexStore index) {
        public int totalBooks() { return books.stream().mapToInt(List::size).sum(); }
    }

    /** Opens the container behind one recorded chunk, or fails with a message the player can read. */
    public interface ChestResolver {
        Container open(VaultIndexV2.Chunk chunk) throws IOException;
    }

    /** Thrown by a resolver when the recorded position is loaded but no chest is there any more. */
    public static final class ChestMissingException extends IOException {
        public ChestMissingException(String message) { super(message); }
    }

    /**
     * A store failed and putting its chests back failed too. Names every chest that may still hold this store's
     * books; no index row was written for them, so they are plain items the player can take out by hand.
     */
    public static final class RollbackIncompleteException extends IOException {
        private final List<String> chests;
        public RollbackIncompleteException(Exception cause, List<String> chests) {
            super("Store failed (" + cause.getMessage() + ") and these chests could not be put back: "
                    + String.join(", ", chests) + ". They may still hold Worldvault books that are not in the index;"
                    + " take them out by hand before using those chests again.", cause);
            this.chests = List.copyOf(chests);
        }
        public List<String> chests() { return chests; }
    }

    /** Test seam for the steps of {@link #commit}; the commands use {@link #NO_HOOKS}. */
    public interface Hooks {
        /** Runs once every chest has been written, before commit returns. */
        default void afterChestWrites() {}
    }
    public static final Hooks NO_HOOKS = new Hooks() {};

    /** What {@link #check} found for one index row. */
    public enum Verdict { INTACT, ORPHANED, KEEP }
    public record Check(Verdict verdict, String detail) {}

    /**
     * Most bytes the given chests can hold, so callers can reject a file before reading it into memory. Every
     * chunk is sized for the smallest chest given (see {@link #prepare}), so a double chest listed with single
     * chests holds one single chest's worth.
     */
    public static long capacity(List<Target> targets) {
        if (targets.isEmpty()) return 0;
        long perChunk = Long.MAX_VALUE;
        for (Target t : targets) perChunk = Math.min(perChunk, (long) t.space().getContainerSize() * RAW_PER_BOOK - HEADER_RESERVE);
        return Math.max(0, perChunk) * targets.size();
    }

    public static Plan prepare(String name, byte[] data, List<Target> targets, VaultIndexStore index) throws IOException {
        if (targets.isEmpty()) throw new IllegalArgumentException("Give at least one chest");
        Set<String> seen = new HashSet<>();
        for (Target t : targets) {
            for (BlockPos b : t.blocks()) {
                if (!seen.add(t.dimension() + "|" + b.getX() + "|" + b.getY() + "|" + b.getZ())) {
                    throw new IllegalArgumentException("A chest is listed twice or overlaps another");
                }
            }
        }
        int perChunk = Integer.MAX_VALUE;
        for (Target t : targets) perChunk = Math.min(perChunk, t.space().getContainerSize() * RAW_PER_BOOK - HEADER_RESERVE);
        VaultChunker.Split split = VaultChunker.split(data, perChunk);
        int needed = split.pieces().size();
        if (needed > targets.size()) {
            throw new IllegalArgumentException("File needs " + needed + " chests but only " + targets.size() + " were given");
        }
        List<Target> used = List.copyOf(targets.subList(0, needed));
        List<List<WorldvaultBooks.Book>> books = new ArrayList<>();
        List<VaultIndexV2.Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < needed; i++) {
            Target t = used.get(i);
            VaultChunker.Piece piece = split.pieces().get(i);
            List<WorldvaultBooks.Book> packed = WorldvaultBooks.pack(name, piece.data());
            if (packed.size() > t.space().getContainerSize()) throw new IllegalArgumentException("Need a larger chest");
            for (int slot = 0; slot < t.space().getContainerSize(); slot++) {
                if (!t.space().getItem(slot).isEmpty()) {
                    throw new IllegalArgumentException("Chest " + (i + 1) + " of " + needed + " is not empty");
                }
            }
            books.add(packed);
            chunks.add(new VaultIndexV2.Chunk(t.dimension(), t.pos().getX(), t.pos().getY(), t.pos().getZ(),
                    packed.size(), t.doubleChest(), piece.crc32()));
        }
        VaultIndexV2.Entry entry = new VaultIndexV2.Entry(name, split.size(), split.sha256(), chunks);
        for (VaultIndexV2.Entry old : index.load()) {
            if (old.name().equals(name)) throw new IllegalArgumentException("A file with that name is already stored");
            for (VaultIndexV2.Chunk c : old.chunks()) {
                for (Target t : used) for (BlockPos b : t.blocks()) {
                    if (c.dimension().equals(t.dimension()) && c.x() == b.getX() && c.y() == b.getY() && c.z() == b.getZ()) {
                        throw new IllegalArgumentException("Chest already registered");
                    }
                }
            }
        }
        return new Plan(name, entry, used, books, index);
    }

    /**
     * Writes every chest, then adds the index row last, so a crash part way leaves books in chests but never an
     * index row for a vault that was not written. On a failure before the row is added, each chest touched is
     * put back slot by slot as it was before the commit; the index file is not touched.
     *
     * <p>Chest contents reach disk at the next world save while the index is written at once, so a crash after
     * this returns but before the world saves can still leave a row whose chests are empty again;
     * {@link #repair} finds and removes such rows.
     */
    public static VaultIndexV2.Entry commit(Plan plan) throws IOException {
        return commit(plan, NO_HOOKS);
    }

    public static VaultIndexV2.Entry commit(Plan plan, Hooks hooks) throws IOException {
        List<List<ItemStack>> before = new ArrayList<>();
        for (Target t : plan.used()) {
            List<ItemStack> slots = new ArrayList<>();
            for (int slot = 0; slot < t.space().getContainerSize(); slot++) slots.add(t.space().getItem(slot).copy());
            before.add(slots);
        }
        int touched = 0;
        try {
            for (int i = 0; i < plan.used().size(); i++) {
                touched = i + 1;
                WorldvaultChest.write(plan.used().get(i).space(), plan.books().get(i));
            }
            hooks.afterChestWrites();
            plan.index().add(plan.entry());
        } catch (RuntimeException | IOException ex) {
            List<String> stuck = new ArrayList<>();
            for (int i = 0; i < touched; i++) {
                Target t = plan.used().get(i);
                try { putBack(t.space(), before.get(i)); }
                catch (RuntimeException cleanup) {
                    ex.addSuppressed(cleanup);
                    stuck.add(t.dimension() + " " + t.pos().getX() + " " + t.pos().getY() + " " + t.pos().getZ());
                }
            }
            if (stuck.isEmpty()) throw ex;
            RollbackIncompleteException report = new RollbackIncompleteException(ex, stuck);
            LOGGER.error("Worldvault: {}", report.getMessage(), report);
            throw report;
        }
        return plan.entry();
    }

    /** Restores only the slots that differ, so items the store never placed are left alone. */
    private static void putBack(Container space, List<ItemStack> before) {
        boolean changed = false;
        for (int slot = 0; slot < space.getContainerSize(); slot++) {
            if (!ItemStack.matches(space.getItem(slot), before.get(slot))) {
                space.setItem(slot, before.get(slot).copy());
                changed = true;
            }
        }
        if (changed) space.setChanged();
    }

    /**
     * The input file for a store, as a real path that is inside the real {@code inputDir}. A link inside the folder
     * that resolves to a file outside it is refused, so a store can never read anything else on the host.
     */
    public static Path inputFile(Path inputDir, String name) throws IOException {
        Path dir = inputDir.toAbsolutePath().normalize();
        Path input = dir.resolve(name).normalize();
        if (!input.startsWith(dir) || !Files.isRegularFile(input)) {
            throw new IllegalArgumentException("File not found in worldvault/in");
        }
        Path real = input.toRealPath();
        if (!real.startsWith(dir.toRealPath()) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("That file links outside worldvault/in");
        }
        return real;
    }

    /**
     * How a restored entry was verified, for the player's message. Rows migrated from a v1 index recorded no
     * whole-file SHA-256 and no per-chunk CRC32, so only the CRC32 inside the books protects them.
     */
    public static String integrity(VaultIndexV2.Entry entry) {
        if (isLegacy(entry)) return "legacy v1 vault: book CRC32 checked, no SHA-256 was recorded";
        return "per-chest CRC32 and whole-file SHA-256 verified";
    }

    /** True for a row read from a v1 index: no size, no SHA-256, no chunk CRC32. */
    public static boolean isLegacy(VaultIndexV2.Entry entry) {
        return entry.sha256().equals("-");
    }

    /**
     * Verifies an index row against its chests. ORPHANED: every recorded chest is loaded and none holds a
     * Worldvault book (a broken chest counts as holding none), so the row points at nothing. INTACT: the vault
     * restores and verifies. KEEP: anything else, including a chest that is not loaded or books that are there but
     * do not verify; the detail says which chest.
     */
    public static Check check(VaultIndexV2.Entry entry, ChestResolver resolver) {
        int n = entry.chunks().size();
        List<String> withBooks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VaultIndexV2.Chunk c = entry.chunks().get(i);
            String where = c.dimension() + " " + c.x() + " " + c.y() + " " + c.z();
            Container space;
            try { space = resolver.open(c); }
            catch (ChestMissingException gone) { continue; }
            catch (IOException ex) {
                return new Check(Verdict.KEEP, "Chest " + (i + 1) + " of " + n + " (" + where + "): " + ex.getMessage());
            }
            if (WorldvaultChest.holdsVaultBooks(space)) withBooks.add(where);
        }
        if (withBooks.isEmpty()) {
            return new Check(Verdict.ORPHANED, "none of its " + n + (n == 1 ? " chest holds" : " chests hold") + " Worldvault books");
        }
        try {
            restore(entry, resolver);
            return new Check(Verdict.INTACT, "all " + n + (n == 1 ? " chest verifies" : " chests verify"));
        } catch (IOException ex) {
            return new Check(Verdict.KEEP, "Worldvault books are still in " + String.join(", ", withBooks)
                    + " but the vault does not verify (" + ex.getMessage() + ")");
        }
    }

    /** Runs {@link #check} and removes the row only when it is orphaned. */
    public static Check repair(VaultIndexV2.Entry entry, ChestResolver resolver, VaultIndexStore index) throws IOException {
        Check result = check(entry, resolver);
        if (result.verdict() == Verdict.ORPHANED) index.remove(entry);
        return result;
    }

    /**
     * Restores into {@code outputDir}: every chest is read and verified first, and the file is written only when
     * all of it checks out, so a failed restore leaves no output file.
     */
    public static WorldvaultCodec.File restoreTo(VaultIndexV2.Entry entry, ChestResolver resolver, Path outputDir)
            throws IOException {
        Path dir = outputDir.toAbsolutePath().normalize();
        Path output = dir.resolve(entry.name()).normalize();
        if (!output.startsWith(dir)) throw new IOException("Unsafe output name");
        if (Files.exists(output)) throw new IOException("worldvault/out already has " + entry.name() + "; move it first");
        WorldvaultCodec.File file = restore(entry, resolver);
        Files.createDirectories(dir);
        Files.write(output, file.bytes(), StandardOpenOption.CREATE_NEW);
        return file;
    }

    /** Reads every chunk, verifies each CRC, the size and the SHA-256 (v1 entries carry none), and returns the file. */
    public static WorldvaultCodec.File restore(VaultIndexV2.Entry entry, ChestResolver resolver) throws IOException {
        List<byte[]> pieces = new ArrayList<>();
        int n = entry.chunks().size();
        for (int i = 0; i < n; i++) {
            String label = "Chest " + (i + 1) + " of " + n + ": ";
            Container space = resolver.open(entry.chunks().get(i));
            try {
                WorldvaultCodec.File part = WorldvaultChest.read(space);
                if (!part.name().equals(entry.name())) throw new IOException(label + "books do not match the index");
                pieces.add(part.bytes());
            } catch (IllegalArgumentException ex) {
                throw new IOException(label + ex.getMessage(), ex);
            }
        }
        try {
            return new WorldvaultCodec.File(entry.name(), VaultChunker.join(pieces, entry));
        } catch (VaultChunker.VerifyException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
    }
}
