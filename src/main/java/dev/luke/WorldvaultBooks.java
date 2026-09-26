package dev.luke;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32;

/** Immutable book payloads. The caller places these in chests and tracks their positions. */
public final class WorldvaultBooks {
    // Deliberately below the game's page/character limits; leave space for labels and metadata.
    private static final int RAW_PER_BOOK = 2048;
    private static final int PAGE_CHARS = 512;
    private static final int MAX_BOOKS = 1024;
    public record Book(int index, int total, List<String> pages) {
        public Book { pages = List.copyOf(pages); }
    }
    private WorldvaultBooks() {}

    public static List<Book> pack(String filename, byte[] input) {
        byte[] name = filename.getBytes(StandardCharsets.UTF_8);
        if (filename.isBlank() || name.length > 255 || filename.equals(".") || filename.equals("..")
                || filename.contains("/") || filename.contains("\\")) {
            throw new IllegalArgumentException("Unsafe filename");
        }
        if (input.length > RAW_PER_BOOK * MAX_BOOKS - 269) {
            throw new IllegalArgumentException("File too large for vault");
        }
        CRC32 crc = new CRC32();
        crc.update(input);
        ByteBuffer header = ByteBuffer.allocate(4 + 1 + name.length + 4 + 4).order(ByteOrder.BIG_ENDIAN);
        header.put(new byte[]{'W', 'V', 'B', 1}).put((byte) name.length).put(name)
                .putInt(input.length).putInt((int) crc.getValue());
        byte[] whole = new byte[header.capacity() + input.length];
        System.arraycopy(header.array(), 0, whole, 0, header.capacity());
        System.arraycopy(input, 0, whole, header.capacity(), input.length);
        int total = (whole.length + RAW_PER_BOOK - 1) / RAW_PER_BOOK;
        List<Book> books = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int from = i * RAW_PER_BOOK;
            String encoded = Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(whole, from, Math.min(from + RAW_PER_BOOK, whole.length)));
            List<String> pages = new ArrayList<>();
            for (int pos = 0; pos < encoded.length(); pos += PAGE_CHARS) {
                pages.add(encoded.substring(pos, Math.min(pos + PAGE_CHARS, encoded.length())));
            }
            books.add(new Book(i, total, pages));
        }
        return List.copyOf(books);
    }

    public static WorldvaultCodec.File unpack(List<Book> books) {
        if (books.isEmpty() || books.size() > MAX_BOOKS) throw new IllegalArgumentException("Wrong book count");
        Book[] ordered = new Book[books.size()];
        for (Book book : books) {
            if (book.total() != books.size() || book.index() < 0 || book.index() >= ordered.length
                    || ordered[book.index()] != null || book.pages().isEmpty() || book.pages().size() > 6) {
                throw new IllegalArgumentException("Missing, duplicated, or malformed book");
            }
            ordered[book.index()] = book;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (Book book : ordered) {
            if (book == null) throw new IllegalArgumentException("Missing book");
            StringBuilder encoded = new StringBuilder();
            for (String page : book.pages()) {
                if (page.length() > PAGE_CHARS) throw new IllegalArgumentException("Oversized page");
                encoded.append(page);
            }
            byte[] part = Base64.getDecoder().decode(encoded.toString());
            if (part.length > RAW_PER_BOOK) throw new IllegalArgumentException("Oversized book");
            bytes.writeBytes(part);
        }
        ByteBuffer in = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.BIG_ENDIAN);
        if (in.remaining() < 13 || in.get() != 'W' || in.get() != 'V' || in.get() != 'B' || in.get() != 1) {
            throw new IllegalArgumentException("Not a vault book");
        }
        int nameLength = Byte.toUnsignedInt(in.get());
        if (in.remaining() < nameLength + 8) throw new IllegalArgumentException("Truncated header");
        byte[] name = new byte[nameLength];
        in.get(name);
        String filename = new String(name, StandardCharsets.UTF_8);
        if (filename.isBlank() || filename.equals(".") || filename.equals("..") || filename.contains("/") || filename.contains("\\")) {
            throw new IllegalArgumentException("Unsafe filename");
        }
        int expectedLength = in.getInt();
        long expectedCrc = Integer.toUnsignedLong(in.getInt());
        if (expectedLength < 0 || expectedLength != in.remaining()) throw new IllegalArgumentException("Wrong file length");
        byte[] contents = new byte[expectedLength];
        in.get(contents);
        CRC32 crc = new CRC32();
        crc.update(contents);
        if (crc.getValue() != expectedCrc) throw new IllegalArgumentException("Vault is damaged");
        return new WorldvaultCodec.File(filename, contents);
    }
}
