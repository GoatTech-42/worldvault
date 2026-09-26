package dev.luke;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

public class WorldvaultCodecTest {
    @org.junit.jupiter.api.Test
    public void test() {
        Random random = new Random(42);
        for (int size : new int[]{0, 1, 26, 8192, 131072}) {
            byte[] original = new byte[size];
            random.nextBytes(original);
            var stacks = WorldvaultCodec.encode("fíle-" + size + ".bin", original);
            var recovered = WorldvaultCodec.decode(stacks);
            if (!Arrays.equals(original, recovered.bytes()) || !recovered.name().equals("fíle-" + size + ".bin")) {
                throw new AssertionError("Roundtrip failed at " + size);
            }
            var damaged = new ArrayList<>(stacks);
            int index = damaged.size() - 1;
            var last = damaged.get(index);
            damaged.set(index, new WorldvaultCodec.Stack((last.kind() + 1) % 4, last.count()));
            try {
                WorldvaultCodec.decode(damaged);
                throw new AssertionError("Damage was not detected");
            } catch (IllegalArgumentException expected) { }
        }
        for (String unsafe : new String[]{"", "../oops", "nested/file"}) {
            try {
                WorldvaultCodec.encode(unsafe, new byte[0]);
                throw new AssertionError("Unsafe path was allowed");
            } catch (IllegalArgumentException expected) { }
        }
        System.out.println("Worldvault codec: roundtrip, damage detection, and path checks passed");
    }
}
