package com.herasgarden.gardenlands.interaction;

import com.herasgarden.gardenlands.claim.ClaimDirectory;
import com.herasgarden.gardenlands.claim.LandClaimRecord;
import com.herasgarden.gardenlands.container.ContainerKey;
import com.herasgarden.gardenlands.door.DoorKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class AccessSettingsListener implements Listener {
    private final ClaimDirectory claims;
    private final AccessSettingsGui gui;

    public AccessSettingsListener(ClaimDirectory claims, AccessSettingsGui gui) {
        this.claims = claims;
        this.gui = gui;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSneaking()) {
            return;
        }

        Block block = event.getClickedBlock();
        boolean container = ContainerKey.supported(block);
        boolean door = DoorKey.supported(block);
        if (!container && !door) {
            return;
        }

        // Chest settings only open from an empty main hand. This keeps normal
        // sneak-right-click placement working for signs and other blocks/items.
        if (container && (event.getHand() != EquipmentSlot.HAND
                || !player.getInventory().getItemInMainHand().getType().isAir())) {
            return;
        }

        Block claimBlock = door ? DoorKey.canonicalBlock(block) : block;
        LandClaimRecord claim = claims.findAt(claimBlock).orElse(null);
        if (claim == null || !claims.canManage(player, claim)) {
            return;
        }

        // Settings interaction replaces the normal open/toggle action for managers.
        event.setCancelled(true);
        if (container) {
            gui.openContainer(player, block);
        } else {
            gui.openDoor(player, block);
        }
    }
}
