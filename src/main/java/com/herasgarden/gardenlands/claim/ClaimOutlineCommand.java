package com.herasgarden.gardenlands.claim;

import com.herasgarden.gardenlands.territory.TerritoryDirectory;
import com.herasgarden.gardenlands.territory.TerritoryRecord;
import com.herasgarden.gardenlands.ui.LandsMessages;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ClaimOutlineCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final ClaimDirectory claims;
    private final TerritoryDirectory territories;
    private final Map<UUID, BukkitTask> active = new ConcurrentHashMap<>();
    private final Map<UUID, List<Location>> fakeGlowstone = new ConcurrentHashMap<>();

    public ClaimOutlineCommand(JavaPlugin plugin, ClaimDirectory claims, TerritoryDirectory territories) {
        this.plugin = plugin;
        this.claims = claims;
        this.territories = territories;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            LandsMessages.send(sender, "Players only.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("off")) {
            stop(player.getUniqueId());
            LandsMessages.send(player, "Claim outline hidden.");
            return true;
        }

        try {
            claims.refresh();
            LandClaimRecord claim = resolve(player, args);
            if (claim == null) {
                LandsMessages.send(player, "No matching claim was found.");
                return true;
            }
            start(player, claim);
            String name = claim.type().replace('_', ' ').toLowerCase(Locale.ROOT);
            LandsMessages.send(player, "Showing " + name + " " + claim.id().toString().substring(0, 8)
                    + " for 20 seconds. Full-height claims use private glowstone markers; UNIT claims use a 3D particle box.");
        } catch (IllegalArgumentException exception) {
            LandsMessages.send(player, exception.getMessage());
        } catch (SQLException exception) {
            LandsMessages.send(player, "Claim outlines could not be loaded right now.");
        }
        return true;
    }

    private LandClaimRecord resolve(Player player, String[] args) throws SQLException {
        if (args.length == 0 || args[0].equalsIgnoreCase("here")) {
            return claims.findAt(player.getLocation().getBlock()).orElse(null);
        }
        if (args[0].equalsIgnoreCase("territory")) {
            if (args.length < 2) {
                throw new IllegalArgumentException("Use /outline territory <name>.");
            }
            territories.refresh();
            String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            TerritoryRecord territory = territories.findByName(name)
                    .orElseThrow(() -> new IllegalArgumentException("That territory does not exist."));
            return claims.find(territory.claimId()).orElse(null);
        }
        if (args[0].equalsIgnoreCase("claim")) {
            if (args.length < 2) {
                throw new IllegalArgumentException("Use /outline claim <claim-id>.");
            }
            return byIdPrefix(args[1]);
        }

        territories.refresh();
        String joined = String.join(" ", args);
        TerritoryRecord territory = territories.findByName(joined).orElse(null);
        if (territory != null) {
            return claims.find(territory.claimId()).orElse(null);
        }
        return byIdPrefix(args[0]);
    }

    private LandClaimRecord byIdPrefix(String token) {
        String prefix = token.toLowerCase(Locale.ROOT);
        List<LandClaimRecord> matches = claims.all().stream()
                .filter(claim -> claim.id().toString().toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
        if (matches.size() > 1) {
            throw new IllegalArgumentException("That claim ID prefix matches more than one claim.");
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private void start(Player player, LandClaimRecord claim) {
        stop(player.getUniqueId());
        if (claim.fullHeight()) showGlowstoneBoundary(player, claim);
        final int[] remaining = {40};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || --remaining[0] < 0) {
                stop(player.getUniqueId());
                return;
            }
            render(player, claim);
        }, 0L, 10L);
        active.put(player.getUniqueId(), task);
    }

    private void stop(UUID playerId) {
        BukkitTask task = active.remove(playerId);
        if (task != null) task.cancel();
        List<Location> locations = fakeGlowstone.remove(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (locations != null && player != null && player.isOnline()) {
            for (Location location : locations) {
                if (location.getWorld() != null && player.getWorld().equals(location.getWorld())) {
                    player.sendBlockChange(location, location.getBlock().getBlockData());
                }
            }
        }
    }

    private void render(Player player, LandClaimRecord claim) {
        World world = Bukkit.getWorld(claim.worldId());
        if (world == null || !player.getWorld().getUID().equals(claim.worldId()) || claim.vertices().size() < 2) {
            return;
        }

        if (claim.fullHeight()) return;

        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(255, 190, 218), 1.35f);
        double bottom = claim.minY() + 0.15;
        drawPolygon(player, claim.vertices(), bottom, dust);

        double top = claim.maxY() + 1.0;
        drawPolygon(player, claim.vertices(), top, dust);
        for (LandClaimRecord.Point point : claim.vertices()) {
            drawLine(player, point.x() + 0.5, bottom, point.z() + 0.5,
                    point.x() + 0.5, top, point.z() + 0.5, dust);
        }
    }

    private void showGlowstoneBoundary(Player player, LandClaimRecord claim) {
        World world = Bukkit.getWorld(claim.worldId());
        if (world == null || !player.getWorld().getUID().equals(claim.worldId())) return;

        Set<Location> locations = new LinkedHashSet<>();
        for (int i = 0; i < claim.vertices().size() && locations.size() < 500; i++) {
            LandClaimRecord.Point from = claim.vertices().get(i);
            LandClaimRecord.Point to = claim.vertices().get((i + 1) % claim.vertices().size());
            double dx = to.x() - from.x();
            double dz = to.z() - from.z();
            double distance = Math.sqrt(dx * dx + dz * dz);
            int steps = Math.max(1, (int) Math.ceil(distance / 2.5));
            for (int step = 0; step <= steps && locations.size() < 500; step++) {
                double t = step / (double) steps;
                int x = (int) Math.round(from.x() + dx * t);
                int z = (int) Math.round(from.z() + dz * t);
                int y = Math.min(world.getMaxHeight() - 2, world.getHighestBlockYAt(x, z) + 1);
                locations.add(new Location(world, x, y, z));
            }
        }

        for (Location location : locations) {
            player.sendBlockChange(location, Material.GLOWSTONE.createBlockData());
        }
        fakeGlowstone.put(player.getUniqueId(), List.copyOf(locations));
    }

    private void drawPolygon(Player player, List<LandClaimRecord.Point> points, double y, Particle.DustOptions dust) {
        for (int i = 0; i < points.size(); i++) {
            LandClaimRecord.Point from = points.get(i);
            LandClaimRecord.Point to = points.get((i + 1) % points.size());
            drawLine(player, from.x() + 0.5, y, from.z() + 0.5,
                    to.x() + 0.5, y, to.z() + 0.5, dust);
        }
    }

    private void drawLine(Player player, double x1, double y1, double z1,
                          double x2, double y2, double z2, Particle.DustOptions dust) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, Math.min(450, (int) Math.ceil(distance / 1.25)));
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            player.spawnParticle(Particle.DUST,
                    x1 + dx * t, y1 + dy * t, z1 + dz * t,
                    1, 0, 0, 0, 0, dust);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("here", "claim", "territory", "off"));
            territories.list().forEach(territory -> values.add(territory.name()));
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("territory")) {
            String prefix = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).toLowerCase(Locale.ROOT);
            return territories.list().stream()
                    .map(TerritoryRecord::name)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("claim")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return claims.all().stream()
                    .map(claim -> claim.id().toString().substring(0, 8))
                    .filter(id -> id.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
