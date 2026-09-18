package com.herasgarden.gardenlands.territory;

import com.herasgarden.gardenlands.citizen.CitizenshipService;
import com.herasgarden.gardenlands.ui.LandsMessages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

public final class TerritoryCommand implements CommandExecutor, TabCompleter {
    private final TerritoryDirectory territories;
    private final CitizenshipService citizenship;

    public TerritoryCommand(TerritoryDirectory territories, CitizenshipService citizenship) {
        this.territories = territories;
        this.citizenship = citizenship;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            // Territory creation still happens in GardenCore during the migration, so refresh
            // before player-facing reads to make newly-created territories visible immediately.
            territories.refresh();
            if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
                List<TerritoryRecord> all = territories.list();
                if (all.isEmpty()) {
                    LandsMessages.send(sender, "There are no territories yet.");
                } else {
                    LandsMessages.send(sender, "Territories: " + String.join(", ", all.stream().map(TerritoryRecord::name).toList()));
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("info")) {
                if (args.length < 2) {
                    LandsMessages.send(sender, "Usage: /territory info <name>");
                    return true;
                }
                String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                TerritoryRecord territory = territories.findByName(name)
                        .orElseThrow(() -> new IllegalArgumentException("That territory does not exist."));
                int count = citizenship.citizens(territory.claimId()).size();
                LandsMessages.send(sender, territory.name() + " has " + count + " declared citizen" + (count == 1 ? "" : "s") + ".");
                return true;
            }
            LandsMessages.send(sender, "Usage: /territory <list|info>");
        } catch (IllegalArgumentException exception) {
            LandsMessages.send(sender, exception.getMessage());
        } catch (SQLException exception) {
            LandsMessages.send(sender, "Territory data could not be loaded right now.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("list", "info").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("info")) {
            try {
                territories.refresh();
                String prefix = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).toLowerCase();
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
