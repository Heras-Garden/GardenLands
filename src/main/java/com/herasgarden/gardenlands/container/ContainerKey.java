package com.herasgarden.gardenlands.container;

import com.herasgarden.gardencore.api.permission.ContainerTypes;
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
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        return ContainerTypes.supported(type);
    }

    public static boolean single(Block block) {
        return supported(block) && members(block).size() == 1;
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
}
