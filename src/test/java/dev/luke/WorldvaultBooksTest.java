package dev.luke;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

public class WorldvaultBooksTest {
    @org.junit.jupiter.api.Test
    public void roundTripsAndRejectsDamage() {
        Random random = new Random(77);
        for (int size : new int[]{0, 1, 2030, 8192, 54000, 131072}) {
            byte[] original = new byte[size];
            random.nextBytes(original);
            var books = WorldvaultBooks.pack("photo-" + size + ".png", original);
            var shuffled = new ArrayList<>(books);
            java.util.Collections.reverse(shuffled);
            var result = WorldvaultBooks.unpack(shuffled);
            if (!Arrays.equals(original, result.bytes()) || !result.name().equals("photo-" + size + ".png")) {
                throw new AssertionError("Broken roundtrip " + size);
            }
            var damaged = new ArrayList<>(books);
            var first = damaged.get(0);
            var pages = new ArrayList<>(first.pages());
            pages.set(0, "A" + pages.get(0).substring(1));
            damaged.set(0, new WorldvaultBooks.Book(first.index(), first.total(), pages));
            try { WorldvaultBooks.unpack(damaged); throw new AssertionError("Damage undetected"); }
            catch (IllegalArgumentException expected) { }
            if (books.size() > 1) {
                try { WorldvaultBooks.unpack(books.subList(0, books.size() - 1)); throw new AssertionError("Missing book undetected"); }
                catch (IllegalArgumentException expected) { }
            }
        }
    }
    @org.junit.jupiter.api.Test
    public void pathAndEmptyNameChecks() {
        for (String bad : new String[]{"", " ", "../foo", "a/b", "a\\b"}) {
            try { WorldvaultBooks.pack(bad, new byte[1]); throw new AssertionError("Unsafe name accepted: " + bad); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
