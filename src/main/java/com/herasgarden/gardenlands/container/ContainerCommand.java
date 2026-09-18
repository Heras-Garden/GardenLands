package com.herasgarden.gardenlands.container;

import com.herasgarden.gardencore.api.permission.AccessDecision;
import com.herasgarden.gardencore.api.permission.ContainerAccessAction;
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

public final class ContainerCommand implements CommandExecutor, TabCompleter {
    private final ContainerPermissionService permissions;
    private final ClaimDirectory claims;

    public ContainerCommand(ContainerPermissionService permissions, ClaimDirectory claims) {
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
        if (block == null || !ContainerKey.supported(block)) {
            LandsMessages.send(player, "Look directly at a chest, trapped chest, copper chest, or barrel first.");
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
                LandsMessages.send(player, "This container now uses the claim defaults and allows mob access.");
                show(player, block);
                return true;
            }
            if ((args[0].equalsIgnoreCase("mobs") || args[0].equalsIgnoreCase("golems"))) {
                boolean allowed;
                if (args.length < 2 || args[1].equalsIgnoreCase("toggle")) {
                    allowed = !permissions.mobAccessAllowed(block);
                } else if (args[1].equalsIgnoreCase("allow") || args[1].equalsIgnoreCase("on")) {
                    allowed = true;
                } else if (args[1].equalsIgnoreCase("deny") || args[1].equalsIgnoreCase("off")) {
                    allowed = false;
                } else {
                    throw new IllegalArgumentException("Use /chest mobs <allow|deny|toggle>.");
                }
                permissions.setMobAccess(player, block, allowed);
                show(player, block);
                return true;
            }
            if (args[0].equalsIgnoreCase("cycle") && args.length >= 2) {
                ContainerAccessAction action = action(args[1]);
                ContainerOverride current = permissions.effectiveRecord(block);
                AccessDecision next = next(value(current, action));
                permissions.set(player, block, action, next);
                show(player, block);
                return true;
            }
            if (args[0].equalsIgnoreCase("set") && args.length >= 3) {
                ContainerAccessAction action = action(args[1]);
                AccessDecision decision = AccessDecision.valueOf(args[2].toUpperCase(Locale.ROOT));
                permissions.set(player, block, action, decision);
                show(player, block);
                return true;
            }
            LandsMessages.send(player, "Usage: /chest [reset|mobs <allow|deny|toggle>|cycle <open|insert|take|break>|set <action> <inherit|allow|deny>]");
        } catch (IllegalArgumentException exception) {
            LandsMessages.send(player, exception.getMessage());
        } catch (SQLException exception) {
            LandsMessages.send(player, "Container settings could not be saved right now.");
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
        if (!ContainerKey.supported(block)) {
            LandsMessages.send(player, "That block is not a supported container.");
            return false;
        }
        LandClaimRecord claim = claims.findAt(block).orElse(null);
        if (claim == null) {
            LandsMessages.send(player, "That container is not inside a Garden claim.");
            return false;
        }
        if (!claims.canManage(player, claim)) {
            LandsMessages.send(player, "You do not manage this claim.");
            return false;
        }
        if (permissions.isMailbox(block)) {
            LandsMessages.send(player, "This is a registered property mailbox. Access is automatic for the property owner, mailmen, and administrators.");
            return false;
        }
        return true;
    }

    private void show(Player player, Block block) {
        ContainerOverride value = permissions.effectiveRecord(block);
        player.sendMessage(Component.text("[Server] ", NamedTextColor.GRAY)
                .append(Component.text("Container Settings", NamedTextColor.WHITE)));
        line(player, "Open", ContainerAccessAction.OPEN, value.open());
        line(player, "Insert", ContainerAccessAction.INSERT, value.insert());
        line(player, "Take", ContainerAccessAction.TAKE, value.take());
        line(player, "Break", ContainerAccessAction.BREAK, value.breakAccess());
        boolean mobs = permissions.mobAccessAllowed(block);
        player.sendMessage(Component.text("Mobs/Golems: ", NamedTextColor.WHITE)
                .append(Component.text(mobs ? "[ALLOW]" : "[DENY]", mobs ? NamedTextColor.GREEN : NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/chest mobs toggle"))
                        .hoverEvent(HoverEvent.showText(Component.text("Allow or deny copper golems and other item-transporting mobs.")))));
        player.sendMessage(Component.text("[Reset to Claim Defaults]", NamedTextColor.GRAY)
                .clickEvent(ClickEvent.runCommand("/chest reset"))
                .hoverEvent(HoverEvent.showText(Component.text("Remove every override on this container."))));
    }

    private void line(Player player, String label, ContainerAccessAction action, AccessDecision value) {
        NamedTextColor color = switch (value) {
            case ALLOW -> NamedTextColor.GREEN;
            case DENY -> NamedTextColor.RED;
            case INHERIT -> NamedTextColor.GRAY;
        };
        player.sendMessage(Component.text(label + ": ", NamedTextColor.WHITE)
                .append(Component.text("[" + value.name() + "]", color)
                        .clickEvent(ClickEvent.runCommand("/chest cycle " + action.name().toLowerCase(Locale.ROOT)))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to cycle INHERIT, ALLOW, DENY.")))));
    }

    private ContainerAccessAction action(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "open" -> ContainerAccessAction.OPEN;
            case "insert", "put" -> ContainerAccessAction.INSERT;
            case "take", "remove" -> ContainerAccessAction.TAKE;
            case "break" -> ContainerAccessAction.BREAK;
            default -> throw new IllegalArgumentException("Unknown container action: " + value + ".");
        };
    }

    private AccessDecision value(ContainerOverride value, ContainerAccessAction action) {
        return switch (action) {
            case OPEN -> value.open();
            case INSERT -> value.insert();
            case TAKE -> value.take();
            case BREAK -> value.breakAccess();
        };
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
            return List.of("reset", "mobs", "golems", "cycle", "set").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("mobs") || args[0].equalsIgnoreCase("golems"))) {
            return List.of("allow", "deny", "toggle").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("cycle") || args[0].equalsIgnoreCase("set"))) {
            return List.of("open", "insert", "take", "break").stream()
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
