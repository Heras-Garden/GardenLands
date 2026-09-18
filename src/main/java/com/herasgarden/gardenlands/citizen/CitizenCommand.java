package com.herasgarden.gardenlands.citizen;

import com.herasgarden.gardenlands.territory.TerritoryDirectory;
import com.herasgarden.gardenlands.territory.TerritoryRecord;
import com.herasgarden.gardenlands.ui.LandsMessages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CitizenCommand implements CommandExecutor, TabCompleter {
    private final CitizenshipService citizenship;
    private final TerritoryDirectory territories;

    public CitizenCommand(CitizenshipService citizenship, TerritoryDirectory territories) {
        this.citizenship = citizenship;
        this.territories = territories;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            LandsMessages.send(sender, "Players only.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            showStatus(player);
            return true;
        }
        try {
            switch (args[0].toLowerCase()) {
                case "join" -> {
                    if (args.length < 2) {
                        LandsMessages.send(player, "Usage: /citizen join <territory>");
                        return true;
                    }
                    territories.refresh();
                    String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                    TerritoryRecord joined = citizenship.join(player.getUniqueId(), name);
                    LandsMessages.send(player, "You are now affiliated with " + joined.name() + ".");
                }
                case "leave" -> {
                    if (citizenship.leave(player.getUniqueId())) {
                        LandsMessages.send(player, "Your territory affiliation was cleared.");
                    } else {
                        LandsMessages.send(player, "You do not currently have a territory affiliation.");
                    }
                }
                case "list" -> {
                    if (args.length < 2) {
                        LandsMessages.send(player, "Usage: /citizen list <territory>");
                        return true;
                    }
                    territories.refresh();
                    String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                    TerritoryRecord territory = territories.findByName(name)
                            .orElseThrow(() -> new IllegalArgumentException("That territory does not exist."));
                    List<java.util.UUID> members = citizenship.citizens(territory.claimId());
                    if (members.isEmpty()) {
                        LandsMessages.send(player, territory.name() + " does not have any declared citizens yet.");
                        return true;
                    }
                    List<String> names = new ArrayList<>();
                    for (java.util.UUID uuid : members) {
                        names.add(Optional.ofNullable(Bukkit.getOfflinePlayer(uuid).getName()).orElse(uuid.toString()));
                    }
                    LandsMessages.send(player, territory.name() + " citizens: " + String.join(", ", names));
                }
                default -> LandsMessages.send(player, "Usage: /citizen <status|join|leave|list>");
            }
        } catch (IllegalArgumentException exception) {
            LandsMessages.send(player, exception.getMessage());
        } catch (SQLException exception) {
            LandsMessages.send(player, "Citizenship could not be updated right now.");
        }
        return true;
    }

    private void showStatus(Player player) {
        Optional<TerritoryRecord> current = citizenship.territoryOf(player.getUniqueId());
        if (current.isPresent()) {
            LandsMessages.send(player, "Territory affiliation: " + current.get().name() + ".");
        } else {
            LandsMessages.send(player, "You have not chosen a territory affiliation.");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("status", "join", "leave", "list").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length >= 2 && (args[0].equalsIgnoreCase("join") || args[0].equalsIgnoreCase("list"))) {
            try {
                territories.refresh();
                String prefix = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).toLowerCase();
                return territories.list().stream()
                        .map(TerritoryRecord::name)
                        .filter(name -> name.toLowerCase().startsWith(prefix))
                        .toList();
            } catch (SQLException ignored) {
                return List.of();
            }
        }
        return List.of();
    }
}
