package com.herasgarden.gardenlands.door;

import com.herasgarden.gardencore.api.permission.AccessDecision;
import com.herasgarden.gardencore.api.permission.DoorAccessAction;
import com.herasgarden.gardenlands.claim.ClaimDirectory;
import com.herasgarden.gardenlands.claim.LandClaimRecord;
import com.herasgarden.gardenlands.ui.LandsMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

public final class DoorCommand implements CommandExecutor, TabCompleter {
    private final DoorPermissionService permissions;
    private final ClaimDirectory claims;

    public DoorCommand(DoorPermissionService permissions, ClaimDirectory claims) {
        this.permissions = permissions;
        this.claims = claims;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            LandsMessages.send(sender, "Players only.");
            return true;
        }
        Block block = player.getTargetBlockExact(6);
        if (block == null || !DoorKey.supported(block)) {
            LandsMessages.send(player, "Look directly at a door or trapdoor first.");
            return true;
        }
        try {
            if (!prepare(player, block)) {
                return true;
            }
            if (args.length == 0) {
                show(player, block);
                return true;
            }
            if (args[0].equalsIgnoreCase("reset")) {
                permissions.reset(player, block);
                LandsMessages.send(player, "This door now uses the claim defaults.");
                show(player, block);
                return true;
            }
            if (args[0].equalsIgnoreCase("cycle") && args.length >= 2) {
                DoorAccessAction action = action(args[1]);
                DoorOverride current = permissions.effectiveRecord(block);
                AccessDecision next = next(value(current, action));
                permissions.set(player, block, action, next);
                show(player, block);
                return true;
            }
            if (args[0].equalsIgnoreCase("set") && args.length >= 3) {
                DoorAccessAction action = action(args[1]);
                AccessDecision decision = AccessDecision.valueOf(args[2].toUpperCase(Locale.ROOT));
                permissions.set(player, block, action, decision);
                show(player, block);
                return true;
            }
            LandsMessages.send(player, "Usage: /door [reset|cycle <use|break>|set <action> <inherit|allow|deny>]");
        } catch (IllegalArgumentException exception) {
            LandsMessages.send(player, exception.getMessage());
        } catch (SQLException exception) {
            LandsMessages.send(player, "Door settings could not be saved right now.");
        }
        return true;
    }

    public void openSettings(Player player, Block block) {
        try {
            if (prepare(player, block)) {
                show(player, block);
            }
        } catch (IllegalArgumentException exception) {
            LandsMessages.send(player, exception.getMessage());
        }
    }

    private boolean prepare(Player player, Block block) {
        Block canonical = DoorKey.canonicalBlock(block);
        LandClaimRecord claim = claims.findAt(canonical).orElse(null);
        if (claim == null) {
            LandsMessages.send(player, "That door is not inside a Garden claim.");
            return false;
        }
        if (!claims.canManage(player, claim)) {
            LandsMessages.send(player, "You do not manage this claim.");
            return false;
        }
        return true;
    }

    private void show(Player player, Block block) {
        DoorOverride value = permissions.effectiveRecord(block);
        player.sendMessage(Component.text("[Server] ", NamedTextColor.GRAY)
                .append(Component.text("Door Settings", NamedTextColor.WHITE)));
        line(player, "Use", DoorAccessAction.USE, value.use());
        line(player, "Break", DoorAccessAction.BREAK, value.breakAccess());
        player.sendMessage(Component.text("[Reset to Claim Defaults]", NamedTextColor.GRAY)
                .clickEvent(ClickEvent.runCommand("/door reset"))
                .hoverEvent(HoverEvent.showText(Component.text("Remove every override on this door."))));
    }

    private void line(Player player, String label, DoorAccessAction action, AccessDecision value) {
        NamedTextColor color = switch (value) {
            case ALLOW -> NamedTextColor.GREEN;
            case DENY -> NamedTextColor.RED;
            case INHERIT -> NamedTextColor.GRAY;
        };
        player.sendMessage(Component.text(label + ": ", NamedTextColor.WHITE)
                .append(Component.text("[" + value.name() + "]", color)
                        .clickEvent(ClickEvent.runCommand("/door cycle " + action.name().toLowerCase(Locale.ROOT)))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to cycle INHERIT, ALLOW, DENY.")))));
    }

    private DoorAccessAction action(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "use", "open" -> DoorAccessAction.USE;
            case "break" -> DoorAccessAction.BREAK;
            default -> throw new IllegalArgumentException("Unknown door action: " + value + ".");
        };
    }

    private AccessDecision value(DoorOverride value, DoorAccessAction action) {
        return action == DoorAccessAction.USE ? value.use() : value.breakAccess();
    }

    private AccessDecision next(AccessDecision value) {
        return switch (value) {
            case INHERIT -> AccessDecision.ALLOW;
            case ALLOW -> AccessDecision.DENY;
            case DENY -> AccessDecision.INHERIT;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reset", "cycle", "set").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("cycle") || args[0].equalsIgnoreCase("set"))) {
            return List.of("use", "break").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            return List.of("inherit", "allow", "deny").stream()
                    .filter(value -> value.startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }
}
