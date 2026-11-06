package dev.luke;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import java.util.function.Function;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * Writes book chunks into one empty chest, or one empty double chest; never overwrites another player's items.
 *
 * <p>Double-chest slot order: the combined space is 54 slots. Slots 0-26 are the LEFT half
 * (ChestBlock.TYPE == LEFT) and slots 27-53 are the RIGHT half. LEFT/RIGHT is a property of the block state,
 * so the order does not depend on which half the player clicked. Books fill slots 0..N-1 in index order.
 * Reading never trusts slot position: every book carries its own "WV i/N" label, so a restore also works if
 * items were shuffled, and fails if any book is missing, duplicated or foreign.
 */
public final class WorldvaultChest {
    private WorldvaultChest() {}

    public static void write(Container chest, List<WorldvaultBooks.Book> books) {
        if (books.size() > chest.getContainerSize()) throw new IllegalArgumentException("File needs more room than this chest has");
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

    public static WorldvaultCodec.File read(Container chest) {
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

    /** The combined 54-slot space of a double chest: LEFT half first (slots 0-26), then RIGHT half (27-53). */
    public static Container combine(ChestBlockEntity left, ChestBlockEntity right) {
        return new CompoundContainer(left, right);
    }

    /**
     * Resolves both halves of the double chest that {@code chest} belongs to, as {LEFT, RIGHT}.
     * Throws IllegalArgumentException if the chest is single or its partner is missing or inconsistent.
     */
    public static ChestBlockEntity[] halves(ChestBlockEntity chest, Function<BlockPos, BlockEntity> lookup) {
        BlockState state = chest.getBlockState();
        ChestType type = state.getValue(ChestBlock.TYPE);
        if (type == ChestType.SINGLE) throw new IllegalArgumentException("Chest is not part of a double chest");
        BlockPos otherPos = chest.getBlockPos().relative(ChestBlock.getConnectedDirection(state));
        if (!(lookup.apply(otherPos) instanceof ChestBlockEntity other)) {
            throw new IllegalArgumentException("The other half of the double chest is missing");
        }
        BlockState otherState = other.getBlockState();
        if (otherState.getBlock() != state.getBlock()
                || otherState.getValue(ChestBlock.TYPE) != (type == ChestType.LEFT ? ChestType.RIGHT : ChestType.LEFT)
                || otherState.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING)
                || !other.getBlockPos().relative(ChestBlock.getConnectedDirection(otherState)).equals(chest.getBlockPos())) {
            throw new IllegalArgumentException("The chest halves do not form a consistent double chest");
        }
        return type == ChestType.LEFT ? new ChestBlockEntity[]{chest, other} : new ChestBlockEntity[]{other, chest};
    }

    /**
     * The container a restore reads from. The index records whether the books were stored in a single or a
     * double chest; the chest must still be in that layout. A chest paired after a single-chest store (or
     * unpaired after a double-chest store) fails instead of being guessed at.
     */
    public static Container restoreSpace(boolean storedAsDouble, ChestBlockEntity chest,
                                         Function<BlockPos, BlockEntity> lookup) throws java.io.IOException {
        boolean paired = chest.getBlockState().getValue(ChestBlock.TYPE) != ChestType.SINGLE;
        if (!storedAsDouble) {
            if (paired) throw new java.io.IOException("Vault chest was paired with another chest after it was stored; separate it first");
            return chest;
        }
        if (!paired) throw new java.io.IOException("Vault was stored in a double chest, but the chest is no longer paired");
        try {
            ChestBlockEntity[] halves = halves(chest, lookup);
            return combine(halves[0], halves[1]);
        } catch (IllegalArgumentException ex) {
            throw new java.io.IOException(ex.getMessage(), ex);
        }
    }
}
