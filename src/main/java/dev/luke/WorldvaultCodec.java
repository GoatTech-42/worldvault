package dev.luke;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/** Maps a file to ordinary item stacks. Each stack carries exactly one byte. */
public final class WorldvaultCodec {
    private static final byte[] MAGIC = {'W', 'V', 'L', 'T'};
    private static final int VERSION = 1;
    private static final int MAX_FILE = 8 * 1024 * 1024;
    // A stack of 1..64 in one of four item types encodes every byte value.
    public record Stack(int kind, int count) {
        public Stack {
            if (kind < 0 || kind > 3 || count < 1 || count > 64) {
                throw new IllegalArgumentException("Invalid stack symbol");
            }
        }
    }
    public record File(String name, byte[] bytes) {
        public File {
            bytes = bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    private WorldvaultCodec() {}

    public static List<Stack> encode(String name, byte[] data) {
        byte[] filename = name.getBytes(StandardCharsets.UTF_8);
        if (name.isBlank() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")
                || filename.length > 255 || data.length > MAX_FILE) {
            throw new IllegalArgumentException("Invalid name or file size");
        }
        ByteBuffer header = ByteBuffer.allocate(4 + 1 + 1 + filename.length + 4 + 4).order(ByteOrder.BIG_ENDIAN);
        CRC32 crc = new CRC32();
        crc.update(data);
        header.put(MAGIC).put((byte) VERSION).put((byte) filename.length).put(filename)
                .putInt(data.length).putInt((int) crc.getValue());
        byte[] prefix = header.array();
        List<Stack> stacks = new ArrayList<>(prefix.length + data.length);
        for (byte b : prefix) stacks.add(symbol(b));
        for (byte b : data) stacks.add(symbol(b));
        return stacks;
    }

    public static File decode(List<Stack> stacks) {
        if (stacks.size() < 14) throw new IllegalArgumentException("Incomplete vault header");
        ByteArrayOutputStream stream = new ByteArrayOutputStream(stacks.size());
        for (Stack stack : stacks) stream.write((stack.kind() << 6) | (stack.count() - 1));
        ByteBuffer in = ByteBuffer.wrap(stream.toByteArray()).order(ByteOrder.BIG_ENDIAN);
        byte[] magic = new byte[4];
        in.get(magic);
        if (!Arrays.equals(MAGIC, magic) || Byte.toUnsignedInt(in.get()) != VERSION) {
            throw new IllegalArgumentException("Not a supported vault");
        }
        int nameLength = Byte.toUnsignedInt(in.get());
        if (in.remaining() < nameLength + 8) throw new IllegalArgumentException("Truncated vault header");
        byte[] name = new byte[nameLength];
        in.get(name);
        String filename = new String(name, StandardCharsets.UTF_8);
        int length = in.getInt();
        long checksum = Integer.toUnsignedLong(in.getInt());
        if (length < 0 || length > MAX_FILE || in.remaining() != length) {
            throw new IllegalArgumentException("Truncated or oversized vault");
        }
        byte[] contents = new byte[length];
        in.get(contents);
        CRC32 crc = new CRC32();
        crc.update(contents);
        if (crc.getValue() != checksum) throw new IllegalArgumentException("Vault data is damaged");
        if (filename.isBlank() || filename.equals(".") || filename.equals("..") || filename.contains("/") || filename.contains("\\")) {
            throw new IllegalArgumentException("Unsafe file name");
        }
        return new File(filename, contents);
    }

    private static Stack symbol(byte b) {
        int value = Byte.toUnsignedInt(b);
        return new Stack(value >>> 6, (value & 63) + 1);
    }
}
