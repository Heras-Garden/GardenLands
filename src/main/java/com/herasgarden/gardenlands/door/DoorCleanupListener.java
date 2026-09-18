package com.herasgarden.gardenlands.door;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.sql.SQLException;

public final class DoorCleanupListener implements Listener {
    private final DoorPermissionService permissions;

    public DoorCleanupListener(DoorPermissionService permissions) {
        this.permissions = permissions;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!DoorKey.supported(event.getBlock())) {
            return;
        }
        try {
            permissions.remove(event.getBlock());
        } catch (SQLException ignored) {
            // A failed cleanup is harmless; a later overwrite/reset can replace the stale row.
        }
    }
}
