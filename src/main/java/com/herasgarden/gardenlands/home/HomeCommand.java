package com.herasgarden.gardenlands.home;

import com.herasgarden.gardencore.api.land.PropertyDirectory;
import com.herasgarden.gardencore.api.land.PropertyMailbox;
import com.herasgarden.gardenlands.claim.ClaimDirectory;
import com.herasgarden.gardenlands.claim.LandClaimRecord;
import com.herasgarden.gardenlands.ui.LandsMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class HomeCommand implements CommandExecutor, TabCompleter {
    private final PropertyDirectory properties;
    private final ClaimDirectory claims;

    public HomeCommand(PropertyDirectory properties, ClaimDirectory claims) {
        this.properties = properties;
        this.claims = claims;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            LandsMessages.send(sender, "Home teleport must be used in-game.");
            return true;
        }
        if (!player.hasPermission("gardenlands.home")) {
            LandsMessages.send(player, "You do not have permission to use /home.");
            return true;
        }

        try {
            List<PropertyMailbox> homes = new ArrayList<>(properties.mailboxesOwnedBy(player.getUniqueId()));
            homes.removeIf(home -> !isHome(home));
            homes.sort(Comparator.comparing(PropertyMailbox::address, String.CASE_INSENSITIVE_ORDER));

            if (homes.isEmpty()) {
                LandsMessages.send(player, "You do not currently own an addressed home with a registered mailbox.");
                return true;
            }

            if (args.length == 0) {
                if (homes.size() == 1) {
                    teleport(player, homes.getFirst());
                } else {
                    showPicker(player, homes);
                }
                return true;
            }

            String query = String.join(" ", args).trim();
            if (query.equalsIgnoreCase("list")) {
                showPicker(player, homes);
                return true;
            }

            List<PropertyMailbox> matches = homes.stream()
                    .filter(home -> matches(home.address(), query))
                    .toList();
            if (matches.isEmpty()) {
                LandsMessages.send(player, "No owned home matches "" + query + "". Use /home to choose an address.");
                return true;
            }
            if (matches.size() > 1) {
                LandsMessages.send(player, "More than one home matches that address. Choose one:");
                showPicker(player, matches);
                return true;
            }

            teleport(player, matches.getFirst());
        } catch (SQLException exception) {
            LandsMessages.send(player, "Your homes could not be loaded right now.");
        }
        return true;
    }

    private boolean isHome(PropertyMailbox home) {
        LandClaimRecord claim = claims.find(home.claimId()).orElse(null);
        return claim != null && (claim.is("HOME") || (claim.is("UNIT") && claim.tagged("APARTMENT")));
    }

    private void showPicker(Player player, List<PropertyMailbox> homes) {
        LandsMessages.send(player, "Choose a home address:");
        for (PropertyMailbox home : homes) {
            Component line = Component.text("[Home] ", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/home " + home.address()))
                    .hoverEvent(HoverEvent.showText(Component.text("Teleport to " + home.address())))
                    .append(Component.text(home.address(), NamedTextColor.WHITE));
            player.sendMessage(line);
        }
    }

    private void teleport(Player player, PropertyMailbox home) {
        LandClaimRecord claim = claims.find(home.claimId()).orElse(null);
        if (claim == null) {
            LandsMessages.send(player, "That home's claim is unavailable.");
            return;
        }

        World world = Bukkit.getWorld(home.worldId());
        if (world == null) world = Bukkit.getWorld(home.worldName());
        if (world == null) {
            LandsMessages.send(player, "That home's world is unavailable.");
            return;
        }

        Location destination = safeLocation(world, home, claim);
        if (destination == null) {
            LandsMessages.send(player, "No safe teleport spot could be found near " + home.address() + ".");
            return;
        }

        destination.setYaw(player.getLocation().getYaw());
        destination.setPitch(player.getLocation().getPitch());
        if (player.teleport(destination, PlayerTeleportEvent.TeleportCause.COMMAND)) {
            LandsMessages.send(player, "Welcome home: " + home.address() + ".");
        } else {
            LandsMessages.send(player, "Home teleport was blocked.");
        }
    }

    private Location safeLocation(World world, PropertyMailbox home, LandClaimRecord claim) {
        int[][] offsets = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
                {2, 0}, {-2, 0}, {0, 2}, {0, -2},
                {2, 1}, {2, -1}, {-2, 1}, {-2, -1},
                {1, 2}, {-1, 2}, {1, -2}, {-1, -2},
                {2, 2}, {2, -2}, {-2, 2}, {-2, -2}
        };

        for (int[] offset : offsets) {
            int x = home.x() + offset[0];
            int z = home.z() + offset[1];
            for (int y = home.y() + 2; y >= home.y() - 2; y--) {
                if (!claim.contains(x, y, z)) continue;
                Block feet = world.getBlockAt(x, y, z);
                Block head = world.getBlockAt(x, y + 1, z);
                Block floor = world.getBlockAt(x, y - 1, z);
                if (safe(feet, head, floor)) {
                    return new Location(world, x + 0.5, y, z + 0.5);
                }
            }
        }
        return null;
    }

    private boolean safe(Block feet, Block head, Block floor) {
        Material feetType = feet.getType();
        Material headType = head.getType();
        return feet.isPassable()
                && head.isPassable()
                && !feet.isLiquid()
                && !head.isLiquid()
                && floor.getType().isSolid()
                && feetType != Material.POWDER_SNOW
                && headType != Material.POWDER_SNOW;
    }

    private boolean matches(String address, String query) {
        String a = normalize(address);
        String q = normalize(query);
        return a.equals(q) || a.contains(q);
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace(",", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        try {
            List<String> addresses = properties.mailboxesOwnedBy(player.getUniqueId()).stream()
                    .filter(this::isHome)
                    .map(PropertyMailbox::address)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
            if (args.length == 1) {
                String prefix = args[0].toLowerCase(Locale.ROOT);
                List<String> result = new ArrayList<>();
                if ("list".startsWith(prefix)) result.add("list");
                for (String address : addresses) {
                    if (address.toLowerCase(Locale.ROOT).startsWith(prefix)) result.add(address);
                }
                return result;
            }
        } catch (SQLException ignored) {
        }
        return List.of();
    }
}
