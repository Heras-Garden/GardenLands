package com.herasgarden.gardenlands.container;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.sql.SQLException;

public final class ContainerCleanupListener implements Listener {
    private final ContainerPermissionService permissions;

    public ContainerCleanupListener(ContainerPermissionService permissions) {
        this.permissions = permissions;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!ContainerKey.supported(event.getBlock()) || !permissions.hasCoordinateState(event.getBlock())) {
            return;
        }
        try {
            permissions.remove(event.getBlock());
        } catch (SQLException exception) {
            event.getPlayer().getServer().getLogger().warning(
                    "Could not remove GardenLands container override at "
                            + event.getBlock().getLocation() + ": " + exception.getMessage());
        }
    }
}
