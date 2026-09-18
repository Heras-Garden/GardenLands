package com.herasgarden.gardenlands.interaction;

import com.herasgarden.gardencore.api.permission.AccessDecision;
import com.herasgarden.gardencore.api.permission.ContainerAccessAction;
import com.herasgarden.gardencore.api.permission.DoorAccessAction;
import com.herasgarden.gardenlands.claim.ClaimDirectory;
import com.herasgarden.gardenlands.claim.LandClaimRecord;
import com.herasgarden.gardenlands.container.ContainerKey;
import com.herasgarden.gardenlands.container.ContainerOverride;
import com.herasgarden.gardenlands.container.ContainerPermissionService;
import com.herasgarden.gardenlands.door.DoorKey;
import com.herasgarden.gardenlands.door.DoorOverride;
import com.herasgarden.gardenlands.door.DoorPermissionService;
import com.herasgarden.gardenlands.ui.LandsMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Inventory-backed access editor. Unlike chat buttons, a single screen can be
 * redrawn in place after every click, so players never have multiple stale
 * settings menus stacked in chat.
 */
public final class AccessSettingsGui implements Listener {
    private static final int RESET_SLOT = 22;

    private final ClaimDirectory claims;
    private final ContainerPermissionService containers;
    private final DoorPermissionService doors;

    public AccessSettingsGui(ClaimDirectory claims, ContainerPermissionService containers,
                             DoorPermissionService doors) {
        this.claims = claims;
        this.containers = containers;
        this.doors = doors;
    }

    public void openContainer(Player player, Block block) {
        if (!ContainerKey.supported(block)) {
            return;
        }
        LandClaimRecord claim = claims.findAt(block).orElse(null);
        if (claim == null || !claims.canManage(player, claim)) {
            return;
        }
        if (containers.isMailbox(block)) {
            LandsMessages.send(player, "This property mailbox is locked automatically for its owner, mailmen, and administrators.");
            return;
        }

        MenuHolder holder = MenuHolder.container(block);
        Inventory inventory = Bukkit.createInventory(holder, 27, Component.text("Chest Settings"));
        holder.inventory = inventory;
        drawContainer(inventory, block);
        player.openInventory(inventory);
    }

    public void openDoor(Player player, Block block) {
        if (!DoorKey.supported(block)) {
            return;
        }
        Block canonical = DoorKey.canonicalBlock(block);
        LandClaimRecord claim = claims.findAt(canonical).orElse(null);
        if (claim == null || !claims.canManage(player, claim)) {
            return;
        }

        MenuHolder holder = MenuHolder.door(canonical);
        Inventory inventory = Bukkit.createInventory(holder, 27, Component.text("Door Settings"));
        holder.inventory = inventory;
        drawDoor(inventory, canonical);
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }

        Block block = holder.block();
        if (block == null) {
            player.closeInventory();
            LandsMessages.send(player, "That block is no longer available.");
            return;
        }

        try {
            if (holder.kind == Kind.CONTAINER) {
                handleContainer(player, event.getRawSlot(), block);
                drawContainer(event.getView().getTopInventory(), block);
            } else {
                handleDoor(player, event.getRawSlot(), block);
                drawDoor(event.getView().getTopInventory(), block);
            }
        } catch (IllegalArgumentException exception) {
            player.closeInventory();
            LandsMessages.send(player, exception.getMessage());
        } catch (SQLException exception) {
            player.closeInventory();
            LandsMessages.send(player, "That access setting could not be saved.");
        }
    }

    private void handleContainer(Player player, int slot, Block block) throws SQLException {
        if (slot == RESET_SLOT) {
            containers.reset(player, block);
            return;
        }
        ContainerAccessAction action = switch (slot) {
            case 10 -> ContainerAccessAction.OPEN;
            case 12 -> ContainerAccessAction.INSERT;
            case 14 -> ContainerAccessAction.TAKE;
            case 16 -> ContainerAccessAction.BREAK;
            default -> null;
        };
        if (action == null) {
            return;
        }
        ContainerOverride current = containers.effectiveRecord(block);
        AccessDecision value = switch (action) {
            case OPEN -> current.open();
            case INSERT -> current.insert();
            case TAKE -> current.take();
            case BREAK -> current.breakAccess();
        };
        containers.set(player, block, action, next(value));
    }

    private void handleDoor(Player player, int slot, Block block) throws SQLException {
        if (slot == RESET_SLOT) {
            doors.reset(player, block);
            return;
        }
        DoorAccessAction action = switch (slot) {
            case 11 -> DoorAccessAction.USE;
            case 15 -> DoorAccessAction.BREAK;
            default -> null;
        };
        if (action == null) {
            return;
        }
        DoorOverride current = doors.effectiveRecord(block);
        AccessDecision value = action == DoorAccessAction.USE ? current.use() : current.breakAccess();
        doors.set(player, block, action, next(value));
    }

    private void drawContainer(Inventory inventory, Block block) {
        inventory.clear();
        ContainerOverride value = containers.effectiveRecord(block);
        inventory.setItem(10, settingItem("Open", value.open()));
        inventory.setItem(12, settingItem("Insert", value.insert()));
        inventory.setItem(14, settingItem("Take", value.take()));
        inventory.setItem(16, settingItem("Break", value.breakAccess()));
        inventory.setItem(RESET_SLOT, resetItem());
    }

    private void drawDoor(Inventory inventory, Block block) {
        inventory.clear();
        DoorOverride value = doors.effectiveRecord(block);
        inventory.setItem(11, settingItem("Use", value.use()));
        inventory.setItem(15, settingItem("Break", value.breakAccess()));
        inventory.setItem(RESET_SLOT, resetItem());
    }

    private ItemStack settingItem(String label, AccessDecision value) {
        Material material = switch (value) {
            case ALLOW -> Material.LIME_CONCRETE;
            case DENY -> Material.RED_CONCRETE;
            case INHERIT -> Material.LIGHT_GRAY_CONCRETE;
        };
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(label + ": " + value.name(), NamedTextColor.WHITE));
        meta.lore(List.of(
                Component.text("Click to cycle", NamedTextColor.GRAY),
                Component.text("INHERIT -> ALLOW -> DENY", NamedTextColor.GRAY)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack resetItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Reset to Claim Defaults", NamedTextColor.WHITE));
        meta.lore(List.of(Component.text("Remove all overrides from this block.", NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private AccessDecision next(AccessDecision value) {
        return switch (value) {
            case INHERIT -> AccessDecision.ALLOW;
            case ALLOW -> AccessDecision.DENY;
            case DENY -> AccessDecision.INHERIT;
        };
    }

    private enum Kind {
        CONTAINER,
        DOOR
    }

    private static final class MenuHolder implements InventoryHolder {
        private final Kind kind;
        private final UUID worldId;
        private final int x;
        private final int y;
        private final int z;
        private Inventory inventory;

        private MenuHolder(Kind kind, Block block) {
            this.kind = kind;
            this.worldId = block.getWorld().getUID();
            this.x = block.getX();
            this.y = block.getY();
            this.z = block.getZ();
        }

        static MenuHolder container(Block block) {
            return new MenuHolder(Kind.CONTAINER, block);
        }

        static MenuHolder door(Block block) {
            return new MenuHolder(Kind.DOOR, block);
        }

        Block block() {
            World world = Bukkit.getWorld(worldId);
            return world == null ? null : world.getBlockAt(x, y, z);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
