package dev.luke;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Writes book chunks into one empty chest; never overwrites another player's items. */
public final class WorldvaultChest {
    private WorldvaultChest() {}

    public static void write(ChestBlockEntity chest, List<WorldvaultBooks.Book> books) {
        if (books.size() > chest.getContainerSize()) throw new IllegalArgumentException("File needs more than one chest");
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            if (!chest.getItem(slot).isEmpty()) throw new IllegalArgumentException("Chest is not empty");
        }
        for (int slot = 0; slot < books.size(); slot++) {
            WorldvaultBooks.Book book = books.get(slot);
            List<Filterable<Component>> pages = book.pages().stream()
                    .map(page -> Filterable.passThrough((Component) Component.literal(page))).toList();
            ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
            stack.set(DataComponents.WRITTEN_BOOK_CONTENT,
                    new WrittenBookContent(Filterable.passThrough("WV " + (book.index() + 1) + "/" + book.total()),
                            "Worldvault", 0, pages, true));
            chest.setItem(slot, stack);
        }
        chest.setChanged();
    }

    public static WorldvaultCodec.File read(ChestBlockEntity chest) {
        List<WorldvaultBooks.Book> books = new ArrayList<>();
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.isEmpty()) continue;
            if (!stack.is(Items.WRITTEN_BOOK)) throw new IllegalArgumentException("Chest contains other items");
            WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (content == null || !content.author().equals("Worldvault")) {
                throw new IllegalArgumentException("Chest contains another book");
            }
            String label = content.title().raw();
            if (!label.matches("WV [1-9][0-9]*/[1-9][0-9]*")) throw new IllegalArgumentException("Wrong book label");
            String[] parts = label.substring(3).split("/");
            int index = Integer.parseInt(parts[0]) - 1;
            int total = Integer.parseInt(parts[1]);
            if (total > chest.getContainerSize()) throw new IllegalArgumentException("Too many books");
            List<String> pages = content.pages().stream().map(page -> page.raw().getString()).toList();
            books.add(new WorldvaultBooks.Book(index, total, pages));
        }
        return WorldvaultBooks.unpack(books);
    }
}
