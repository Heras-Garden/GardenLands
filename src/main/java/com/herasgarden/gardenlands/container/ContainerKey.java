package com.herasgarden.gardenlands.container;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;
import java.util.UUID;

public record ContainerKey(UUID worldId, int x, int y, int z) {
    public static boolean supported(Block block) {
        Material type = block.getType();
        return type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL;
    }

    public static ContainerKey of(Block block) {
        if (!supported(block)) {
            throw new IllegalArgumentException("That block is not a supported container.");
        }
        List<ContainerKey> members = members(block);
        if (members.size() == 2) {
            ContainerKey a = members.get(0);
            ContainerKey b = members.get(1);
            if (a.x() != b.x()) return a.x() < b.x() ? a : b;
            if (a.y() != b.y()) return a.y() < b.y() ? a : b;
            return a.z() <= b.z() ? a : b;
        }
        return members.get(0);
    }

    /** Exact block keys that make up this container. A double chest returns both halves. */
    public static List<ContainerKey> members(Block block) {
        if (!supported(block)) {
            throw new IllegalArgumentException("That block is not a supported container.");
        }
        if (block.getState() instanceof Chest chest) {
            InventoryHolder holder = chest.getInventory().getHolder();
            if (holder instanceof DoubleChest doubleChest) {
                Block left = blockOf(doubleChest.getLeftSide());
                Block right = blockOf(doubleChest.getRightSide());
                if (left != null && right != null) {
                    return List.of(direct(left), direct(right));
                }
            }
        }
        return List.of(direct(block));
    }

    private static Block blockOf(InventoryHolder holder) {
        return holder instanceof Container container ? container.getBlock() : null;
    }

    private static ContainerKey direct(Block block) {
        return new ContainerKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    private static int compare(Block a, Block b) {
        int x = Integer.compare(a.getX(), b.getX());
        if (x != 0) return x;
        int y = Integer.compare(a.getY(), b.getY());
        if (y != 0) return y;
        return Integer.compare(a.getZ(), b.getZ());
    }
}
