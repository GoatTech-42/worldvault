package dev.luke;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;

/**
 * Turns the chest positions a player named into store targets. Every world lookup goes through
 * {@link WorldAccess}, so the commands use a live level and tests use a map of chest block entities.
 */
public final class VaultTargets {
    public static final int MAX_RANGE_VOLUME = 4096;
    public static final int MAX_CHESTS = 64;
    private static final double REACH_SQR = 64;

    private VaultTargets() {}

    /** The few level calls a store makes. */
    public interface WorldAccess {
        String dimension();

        /** True when the chunk column holding {@code pos} is loaded, whatever its height. Never loads anything. */
        boolean isLoaded(BlockPos pos);

        /** The block entity at {@code pos}. On a server level this loads the chunk when it is not loaded. */
        BlockEntity blockEntity(BlockPos pos);
    }

    public static void checkReach(Vec3 player, List<BlockPos> chests) {
        for (BlockPos pos : chests) {
            if (player.distanceToSqr(pos.getCenter()) > REACH_SQR) throw new IllegalArgumentException("Stand near your chests");
        }
    }

    public static List<VaultService.Target> targets(WorldAccess world, List<BlockPos> chests) {
        List<VaultService.Target> targets = new ArrayList<>();
        for (BlockPos pos : chests) targets.add(target(world, pos));
        return targets;
    }

    public static VaultService.Target target(WorldAccess world, BlockPos pos) {
        requireLoaded(world, pos, "Chest at ");
        if (!(world.blockEntity(pos) instanceof ChestBlockEntity chest)) {
            throw new IllegalArgumentException("No chest at " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        }
        boolean paired = chest.getBlockState().getValue(ChestBlock.TYPE) != ChestType.SINGLE;
        if (!paired) return new VaultService.Target(world.dimension(), pos, false, chest, List.of(pos));
        requireLoaded(world, pos.relative(ChestBlock.getConnectedDirection(chest.getBlockState())),
                "The other half of the double chest at ");
        ChestBlockEntity[] halves = WorldvaultChest.halves(chest, world::blockEntity);
        return new VaultService.Target(world.dimension(), pos, true, WorldvaultChest.combine(halves[0], halves[1]),
                List.of(halves[0].getBlockPos(), halves[1].getBlockPos()));
    }

    /** Opens the chest (or double chest) behind a recorded chunk, in the layout it was stored in. */
    public static net.minecraft.world.Container open(WorldAccess world, VaultIndexV2.Chunk chunk) throws java.io.IOException {
        BlockPos pos = new BlockPos(chunk.x(), chunk.y(), chunk.z());
        // Never load a chunk to restore: a lookup in an unloaded chunk would load it synchronously on the server thread.
        if (!world.isLoaded(pos)) {
            throw new java.io.IOException("Chest at " + chunk.x() + " " + chunk.y() + " " + chunk.z()
                    + " is not loaded; go near it and try again");
        }
        if (!(world.blockEntity(pos) instanceof ChestBlockEntity chest)) {
            throw new VaultService.ChestMissingException("Chest at " + chunk.x() + " " + chunk.y() + " " + chunk.z()
                    + " is gone; restore a world backup or bring the books back");
        }
        if (chest.getBlockState().getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(chest.getBlockState()));
            if (!world.isLoaded(other)) {
                throw new java.io.IOException("The other half of the double chest at " + chunk.x() + " " + chunk.y() + " "
                        + chunk.z() + " is not loaded; go near it and try again");
            }
        }
        return WorldvaultChest.restoreSpace(chunk.doubleChest(), chest, world::blockEntity);
    }

    /** Whether the chest at a recorded chunk, or any chest that could be its other half, holds Worldvault books. */
    public static boolean holdsVaultBooks(WorldAccess world, VaultIndexV2.Chunk chunk) throws java.io.IOException {
        BlockPos pos = new BlockPos(chunk.x(), chunk.y(), chunk.z());
        List<BlockPos> look = new ArrayList<>(List.of(pos));
        // The layout may have changed since the store (a half broken, a chest re-paired), so inspect each chest
        // block on its own. A double chest's other half is always a horizontal neighbour of the recorded half.
        if (chunk.doubleChest()) for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) look.add(pos.relative(d));
        for (BlockPos p : look) {
            if (!world.isLoaded(p)) {
                throw new java.io.IOException("Chest at " + p.getX() + " " + p.getY() + " " + p.getZ()
                        + " is not loaded; go near it and try again");
            }
        }
        for (BlockPos p : look) {
            if (world.blockEntity(p) instanceof ChestBlockEntity chest && WorldvaultChest.holdsVaultBooks(chest)) return true;
        }
        return false;
    }

    /** Fails before any lookup when {@code pos} sits in an unloaded chunk, so a store never loads one. */
    private static void requireLoaded(WorldAccess world, BlockPos pos, String what) {
        if (!world.isLoaded(pos)) {
            throw new IllegalArgumentException(what + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " is not loaded; stand closer");
        }
    }

    /** Chest positions inside the box, one per chest: a double chest is listed once, by the half met first. */
    public static List<BlockPos> scanRange(WorldAccess world, BlockPos from, BlockPos to) {
        long volume = (long) (Math.abs(from.getX() - to.getX()) + 1) * (Math.abs(from.getY() - to.getY()) + 1)
                * (Math.abs(from.getZ() - to.getZ()) + 1);
        if (volume > MAX_RANGE_VOLUME) throw new IllegalArgumentException("Range is too large (limit " + MAX_RANGE_VOLUME + " blocks)");
        // Check first, so a scan never loads a chunk (a level lookup in an unloaded chunk would load it).
        for (int x = Math.min(from.getX(), to.getX()); x <= Math.max(from.getX(), to.getX()); x++)
            for (int z = Math.min(from.getZ(), to.getZ()); z <= Math.max(from.getZ(), to.getZ()); z++) {
                if (!world.isLoaded(new BlockPos(x, from.getY(), z))) {
                    throw new IllegalArgumentException("Part of that range is not loaded; stand closer");
                }
            }
        List<BlockPos> found = new ArrayList<>();
        Set<BlockPos> covered = new HashSet<>();
        for (int x = Math.min(from.getX(), to.getX()); x <= Math.max(from.getX(), to.getX()); x++)
            for (int y = Math.min(from.getY(), to.getY()); y <= Math.max(from.getY(), to.getY()); y++)
                for (int z = Math.min(from.getZ(), to.getZ()); z <= Math.max(from.getZ(), to.getZ()); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (covered.contains(p) || !(world.blockEntity(p) instanceof ChestBlockEntity chest)) continue;
                    covered.add(p);
                    if (chest.getBlockState().getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                        covered.add(p.relative(ChestBlock.getConnectedDirection(chest.getBlockState())));
                    }
                    found.add(p);
                    if (found.size() > MAX_CHESTS) throw new IllegalArgumentException("Too many chests in range (limit " + MAX_CHESTS + ")");
                }
        if (found.isEmpty()) throw new IllegalArgumentException("No chests in that range");
        return found;
    }
}
