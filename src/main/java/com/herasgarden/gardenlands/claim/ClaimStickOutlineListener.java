package com.herasgarden.gardenlands.claim;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class ClaimStickOutlineListener implements Listener {
    private final ClaimOutlineCommand outlines;

    public ClaimStickOutlineListener(ClaimOutlineCommand outlines) {
        this.outlines = outlines;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getPlayer().getInventory().getItemInMainHand().getType() != Material.STICK
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        event.setCancelled(true);
        outlines.showHere(event.getPlayer());
    }
}
