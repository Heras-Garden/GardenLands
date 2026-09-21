package com.herasgarden.gardenlands.home;

import com.herasgarden.gardencore.api.land.PropertyDirectory;
import com.herasgarden.gardencore.api.social.MarriageDirectory;
import com.herasgarden.gardencore.api.land.PropertyMailbox;
import com.herasgarden.gardencore.api.land.PropertyManagementService;
import com.herasgarden.gardencore.api.land.PropertySignBinding;
import com.herasgarden.gardencore.api.land.PropertySignKind;
import com.herasgarden.gardencore.api.ui.GardenMessages;
import com.herasgarden.gardenlands.claim.ClaimDirectory;
import com.herasgarden.gardenlands.claim.LandClaimRecord;
import com.herasgarden.gardenlands.ui.LandsMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.util.Vector;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public final class HomeCommand implements CommandExecutor, TabCompleter {
    private final PropertyDirectory properties;
    private final PropertyManagementService propertyManagement;
    private final ClaimDirectory claims;

    public HomeCommand(
            PropertyDirectory properties,
            PropertyManagementService propertyManagement,
            ClaimDirectory claims
    ) {
        this.properties = properties;
        this.propertyManagement = propertyManagement;
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
            List<PropertyMailbox> homes = homesFor(player);
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
                LandsMessages.send(player, "No owned home matches \"" + query + "\". Use /home to choose an address.");
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

    private List<PropertyMailbox> homesFor(Player player) throws SQLException {
        List<PropertyMailbox> homes = new ArrayList<>();
        Set<UUID> owners = new LinkedHashSet<>();
        owners.add(player.getUniqueId());

        RegisteredServiceProvider<MarriageDirectory> registration =
                Bukkit.getServicesManager().getRegistration(MarriageDirectory.class);
        if (registration != null && registration.getProvider() != null) {
            owners.addAll(registration.getProvider().partners(player.getUniqueId()));
        }

        Set<UUID> seenProperties = new LinkedHashSet<>();
        for (UUID ownerId : owners) {
            for (PropertyMailbox mailbox : properties.mailboxesOwnedBy(ownerId)) {
                if (seenProperties.add(mailbox.propertyId())) homes.add(mailbox);
            }
        }
        return homes;
    }

    private boolean isHome(PropertyMailbox home) {
        LandClaimRecord claim = claims.find(home.claimId()).orElse(null);
        return claim != null && (claim.is("HOME") || (claim.is("UNIT") && claim.tagged("APARTMENT")));
    }

    private void showPicker(Player player, List<PropertyMailbox> homes) {
        player.sendMessage(GardenMessages.prefix()
                .append(Component.text("Homes", GardenMessages.PETAL_FROST)));
        player.sendMessage(Component.text("Choose the address you want to teleport to.", GardenMessages.NEUTRAL_GRAY));
        for (PropertyMailbox home : homes) {
            Component line = Component.text("[Home] ", GardenMessages.MUTED_OLIVE)
                    .clickEvent(ClickEvent.runCommand("/home " + home.address()))
                    .hoverEvent(HoverEvent.showText(
                            Component.text("Teleport to " + home.address(), GardenMessages.NEUTRAL_GRAY)))
                    .append(Component.text(home.address(), GardenMessages.MESSAGE_COLOR));
            player.sendMessage(line);
        }
    }

    private void teleport(Player player, PropertyMailbox home) {
        LandClaimRecord claim = claims.find(home.claimId()).orElse(null);
        if (claim == null) {
            LandsMessages.send(player, "That home's claim is unavailable.");
            return;
        }

        World world = Bukkit.getWorld(claim.worldId());
        if (world == null) world = Bukkit.getWorld(home.worldName());
        if (world == null) {
            LandsMessages.send(player, "That home's world is unavailable.");
            return;
        }

        Location destination = mailboxSignDestination(world, home);
        if (destination == null) destination = safeLocation(world, claim);
        if (destination == null) {
            LandsMessages.send(player, "No safe teleport spot could be found at the mailbox for " + home.address() + ".");
            return;
        }
        if (player.teleport(destination, PlayerTeleportEvent.TeleportCause.COMMAND)) {
            LandsMessages.send(player, "Welcome home: " + home.address() + ".");
        } else {
            LandsMessages.send(player, "Home teleport was blocked.");
        }
    }

    private Location mailboxSignDestination(World world, PropertyMailbox home) {
        PropertySignKind wanted = isApartment(home)
                ? PropertySignKind.APARTMENT_MAILBOX
                : PropertySignKind.PROPERTY_MAILBOX;
        PropertySignBinding binding = propertyManagement.signsFor(home.propertyId()).stream()
                .filter(value -> value.kind() == wanted)
                .findFirst()
                .orElse(null);
        if (binding == null || !binding.position().worldId().equals(world.getUID())) return null;

        Block signBlock = world.getBlockAt(
                binding.position().x(),
                binding.position().y(),
                binding.position().z());
        if (!(signBlock.getState() instanceof Sign)) return null;

        org.bukkit.block.BlockFace facing = signBlock.getBlockData() instanceof Directional directional
                ? directional.getFacing() : org.bukkit.block.BlockFace.NORTH;
        Block front = signBlock.getRelative(facing);
        Location safe = safeNear(front, signBlock);
        if (safe != null) return safe;

        return safeNear(signBlock, signBlock);
    }

    private boolean isApartment(PropertyMailbox home) {
        LandClaimRecord claim = claims.find(home.claimId()).orElse(null);
        return claim != null && claim.is("UNIT") && claim.tagged("APARTMENT");
    }

    private Location safeNear(Block center, Block faceToward) {
        World world = center.getWorld();
        for (int radius = 0; radius <= 2; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    Block feet = world.getBlockAt(center.getX() + dx, center.getY(), center.getZ() + dz);
                    Block head = feet.getRelative(org.bukkit.block.BlockFace.UP);
                    Block floor = feet.getRelative(org.bukkit.block.BlockFace.DOWN);
                    if (!safe(feet, head, floor)) continue;

                    Location destination = feet.getLocation().add(0.5, 0.0, 0.5);
                    Vector look = faceToward.getLocation().add(0.5, 0.5, 0.5).toVector()
                            .subtract(destination.toVector());
                    if (look.lengthSquared() > 0.0001D) destination.setDirection(look);
                    return destination;
                }
            }
        }
        return null;
    }

    private Location safeLocation(World world, LandClaimRecord claim) {
        if (claim.vertices().isEmpty()) return null;

        int minX = claim.vertices().stream().mapToInt(LandClaimRecord.Point::x).min().orElse(0);
        int maxX = claim.vertices().stream().mapToInt(LandClaimRecord.Point::x).max().orElse(0);
        int minZ = claim.vertices().stream().mapToInt(LandClaimRecord.Point::z).min().orElse(0);
        int maxZ = claim.vertices().stream().mapToInt(LandClaimRecord.Point::z).max().orElse(0);
        int centerX = (minX + maxX) / 2;
        int centerZ = (minZ + maxZ) / 2;
        int maxRadius = Math.max(4, Math.min(24, Math.max(maxX - minX, maxZ - minZ)));

        for (int radius = 0; radius <= maxRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    int x = centerX + dx;
                    int z = centerZ + dz;
                    if (x < minX || x > maxX || z < minZ || z > maxZ) continue;

                    if (claim.fullHeight()) {
                        int y = Math.min(world.getMaxHeight() - 2, world.getHighestBlockYAt(x, z) + 1);
                        if (!claim.contains(x, y, z)) continue;
                        Block feet = world.getBlockAt(x, y, z);
                        Block head = world.getBlockAt(x, y + 1, z);
                        Block floor = world.getBlockAt(x, y - 1, z);
                        if (safe(feet, head, floor)) return new Location(world, x + 0.5, y, z + 0.5);
                    } else {
                        for (int y = claim.minY() + 1; y < claim.maxY(); y++) {
                            if (!claim.contains(x, y, z)) continue;
                            Block feet = world.getBlockAt(x, y, z);
                            Block head = world.getBlockAt(x, y + 1, z);
                            Block floor = world.getBlockAt(x, y - 1, z);
                            if (safe(feet, head, floor)) return new Location(world, x + 0.5, y, z + 0.5);
                        }
                    }
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
            List<String> addresses = homesFor(player).stream()
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
