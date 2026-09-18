package com.herasgarden.gardenlands.container;

import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Applies Garden container mob locks to Paper's item-transport target lookup.
 * Copper golems use this path when choosing containers for pickup/deposit.
 */
public final class ContainerMobAccessListener implements Listener {
    private final ContainerPermissionService permissions;

    public ContainerMobAccessListener(ContainerPermissionService permissions) {
        this.permissions = permissions;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onValidateTarget(ItemTransportingEntityValidateTargetEvent event) {
        if (!event.isAllowed()) {
            return;
        }
        Block block = event.getBlock();
        if (ContainerKey.supported(block) && !permissions.mobAccessAllowed(block)) {
            event.setAllowed(false);
        }
    }
}
