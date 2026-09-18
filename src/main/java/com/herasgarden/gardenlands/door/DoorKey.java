package com.herasgarden.gardenlands.door;

import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;

import java.util.UUID;

public record DoorKey(UUID worldId, int x, int y, int z) {
    public static boolean supported(Block block) {
        return block != null && (Tag.DOORS.isTagged(block.getType()) || Tag.TRAPDOORS.isTagged(block.getType()));
    }

    public static Block canonicalBlock(Block block) {
        if (!supported(block)) {
            throw new IllegalArgumentException("That block is not a door or trapdoor.");
        }
        if (Tag.DOORS.isTagged(block.getType()) && block.getBlockData() instanceof Bisected bisected
                && bisected.getHalf() == Bisected.Half.TOP) {
            return block.getRelative(BlockFace.DOWN);
        }
        return block;
    }

    public static DoorKey of(Block block) {
        Block canonical = canonicalBlock(block);
        return new DoorKey(canonical.getWorld().getUID(), canonical.getX(), canonical.getY(), canonical.getZ());
    }
}
