package com.herasgarden.gardenlands.territory;

import com.herasgarden.gardencore.api.society.TerritoryPopulationProvider;
import com.herasgarden.gardenlands.ui.LandsMessages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

public final class TerritoryCommand implements CommandExecutor, TabCompleter {
    private final TerritoryDirectory territories;

    public TerritoryCommand(TerritoryDirectory territories) {
        this.territories = territories;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            territories.refresh();
            if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
                List<TerritoryRecord> all = territories.list();
                if (all.isEmpty()) {
                    LandsMessages.send(sender, "There are no territories yet.");
                } else {
                    LandsMessages.send(sender, "Territories: "
                            + String.join(", ", all.stream().map(TerritoryRecord::name).toList()));
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("population")) {
                TerritoryRecord territory = requireTerritory(args);
                TerritoryPopulationProvider provider = populationProvider();
                if (provider == null) {
                    LandsMessages.send(sender, "Permanent resident population is unavailable until GardenSociety is enabled.");
                    return true;
                }
                LandsMessages.send(sender, territory.name() + " population: "
                        + provider.population(territory.claimId()) + ".");
                return true;
            }
            if (args[0].equalsIgnoreCase("info")) {
                TerritoryRecord territory = requireTerritory(args);
                TerritoryPopulationProvider provider = populationProvider();
                if (provider == null) {
                    LandsMessages.send(sender, territory.name()
                            + " is registered. Permanent resident population is unavailable until GardenSociety is enabled.");
                    return true;
                }
                int count = provider.population(territory.claimId());
                LandsMessages.send(sender, territory.name() + " has " + count + " permanent Society resident"
                        + (count == 1 ? "" : "s") + ".");
                return true;
            }
            LandsMessages.send(sender, "Usage: /territory <list|info|population>");
        } catch (IllegalArgumentException exception) {
            LandsMessages.send(sender, exception.getMessage());
        } catch (SQLException exception) {
            LandsMessages.send(sender, "Territory data could not be loaded right now.");
        }
        return true;
    }

    private TerritoryRecord requireTerritory(String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Enter a territory name.");
        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        return territories.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("That territory does not exist."));
    }

    private TerritoryPopulationProvider populationProvider() {
        RegisteredServiceProvider<TerritoryPopulationProvider> registration =
                Bukkit.getServicesManager().getRegistration(TerritoryPopulationProvider.class);
        return registration == null ? null : registration.getProvider();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("list", "info", "population").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length >= 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("population"))) {
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
