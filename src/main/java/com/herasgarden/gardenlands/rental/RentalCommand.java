package com.herasgarden.gardenlands.rental;

import com.herasgarden.gardenlands.claim.ClaimDirectory;
import com.herasgarden.gardenlands.claim.LandClaimRecord;
import com.herasgarden.gardenlands.ui.LandsMessages;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

public final class RentalCommand implements CommandExecutor, TabCompleter {
    private final RentalService rentals;
    private final ClaimDirectory claims;
    private final String symbol;

    public RentalCommand(RentalService rentals, ClaimDirectory claims, String symbol) {
        this.rentals = rentals;
        this.claims = claims;
        this.symbol = symbol;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            LandsMessages.send(sender, "Players only.");
            return true;
        }
        try {
            if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
                showInfo(player);
                return true;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "list" -> list(player, args);
                case "nightly" -> nightly(player, args);
                case "accept" -> accept(player);
                case "book" -> book(player, args);
                case "cancel" -> cancel(player);
                case "status" -> status(player);
                default -> LandsMessages.send(player,
                        "Usage: /rent <info|list <price> <duration>|nightly <price> [max-nights]|"
                                + "accept|book <nights>|cancel|status>");
            }
        } catch (NumberFormatException exception) {
            LandsMessages.send(player, "Use a whole-number Obol price.");
        } catch (IllegalArgumentException exception) {
            LandsMessages.send(player, exception.getMessage());
        } catch (SQLException exception) {
            LandsMessages.send(player, "The rental system could not update right now.");
        }
        return true;
    }

    private void list(Player player, String[] args) throws SQLException {
        if (!player.hasPermission("gardenlands.rent.list")) {
            LandsMessages.send(player, "You do not have permission to list properties for rent.");
            return;
        }
        if (args.length < 3) {
            LandsMessages.send(player, "Usage: /rent list <price> <duration>, for example /rent list 20 7d");
            return;
        }
        long price = Long.parseLong(args[1].replace(",", ""));
        long minutes = parseDuration(args[2]);
        claims.refresh();
        RentalRecord record = rentals.list(player, targetClaim(player), price, minutes);
        LandsMessages.send(player, "Property listed for " + symbol + " " + record.price()
                + " for " + displayDuration(record.durationMinutes()) + ".");
    }

    private void nightly(Player player, String[] args) throws SQLException {
        if (!player.hasPermission("gardenlands.rent.list")) {
            LandsMessages.send(player, "You do not have permission to list properties for rent.");
            return;
        }
        if (args.length < 2) {
            LandsMessages.send(player,
                    "Usage: /rent nightly <price-per-night> [max-nights], for example /rent nightly 12 7");
            return;
        }

        long nightlyPrice = Long.parseLong(args[1].replace(",", ""));
        int maxNights = args.length >= 3 ? Integer.parseInt(args[2]) : 30;
        claims.refresh();
        RentalRecord record = rentals.listNightly(
                player, targetClaim(player), nightlyPrice, maxNights);
        LandsMessages.send(player, "Property listed for " + symbol + " " + record.price()
                + " per night, up to " + maxNights + (maxNights == 1 ? " night." : " nights."));
    }

    private void accept(Player player) throws SQLException {
        if (!player.hasPermission("gardenlands.rent")) {
            LandsMessages.send(player, "You do not have permission to rent properties.");
            return;
        }
        claims.refresh();
        RentalRecord record = rentals.accept(player, targetClaim(player));
        LandsMessages.send(player, "Rental active until " + Instant.ofEpochMilli(record.expiresAt())
                + ". Paid " + symbol + " " + record.price() + ".");
        Player owner = Bukkit.getPlayer(record.ownerId());
        if (owner != null) {
            LandsMessages.send(owner, player.getName() + " rented your property for "
                    + symbol + " " + record.price() + ".");
        }
    }

    private void book(Player player, String[] args) throws SQLException {
        if (!player.hasPermission("gardenlands.rent")) {
            LandsMessages.send(player, "You do not have permission to rent properties.");
            return;
        }
        if (args.length < 2) {
            LandsMessages.send(player, "Usage: /rent book <nights>.");
            return;
        }

        int nights = Integer.parseInt(args[1]);
        claims.refresh();
        RentalRecord record = rentals.book(player, targetClaim(player), nights);
        LandsMessages.send(player, "Hotel rental active for " + nights
                + (nights == 1 ? " night" : " nights")
                + " until " + Instant.ofEpochMilli(record.expiresAt())
                + ". Paid " + symbol + " " + record.price() + ".");

        Player owner = Bukkit.getPlayer(record.ownerId());
        if (owner != null) {
            LandsMessages.send(owner, player.getName() + " booked your property for "
                    + nights + (nights == 1 ? " night" : " nights")
                    + " for " + symbol + " " + record.price() + ".");
        }
    }

    private void cancel(Player player) throws SQLException {
        claims.refresh();
        rentals.cancelListing(player.getUniqueId(), targetClaim(player));
        LandsMessages.send(player, "Rental listing cancelled.");
    }

    private void showInfo(Player player) throws SQLException {
        claims.refresh();
        LandClaimRecord claim = targetClaim(player);
        RentalRecord record = rentals.openRental(claim.id()).orElse(null);
        if (record == null) {
            LandsMessages.send(player, "This property is not listed or rented.");
            return;
        }
        if (record.listed()) {
            RentalService.RentalTerms terms = rentals.terms(record);
            if (terms.nightly()) {
                LandsMessages.send(player, "Hotel rental: " + symbol + " " + terms.unitPrice()
                        + " per night, up to " + terms.maxUnits()
                        + (terms.maxUnits() == 1 ? " night." : " nights.")
                        + " Use /rent book <nights>.");
            } else {
                LandsMessages.send(player, "For rent: " + symbol + " " + record.price()
                        + " for " + displayDuration(record.durationMinutes())
                        + ". Use /rent accept to rent it.");
            }
            return;
        }
        long remaining = Math.max(0L, record.expiresAt() - System.currentTimeMillis());
        LandsMessages.send(player, "This property is rented for another "
                + displayDuration(Math.max(1L, Duration.ofMillis(remaining).toMinutes())) + ".");
    }

    private void status(Player player) {
        List<RentalRecord> active = rentals.activeForRenter(player.getUniqueId());
        if (active.isEmpty()) {
            LandsMessages.send(player, "You do not have any active rentals.");
            return;
        }
        LandsMessages.send(player, "Active rentals:");
        for (RentalRecord record : active) {
            LandsMessages.send(player, record.propertyId().toString().substring(0, 8)
                    + " expires " + Instant.ofEpochMilli(record.expiresAt()) + ".");
        }
    }

    private LandClaimRecord targetClaim(Player player) {
        Block target = player.getTargetBlockExact(8);
        LandClaimRecord claim = target == null ? null : claims.findAt(target).orElse(null);
        if (claim == null) {
            claim = claims.findAt(player.getLocation().getBlock()).orElse(null);
        }
        if (claim == null) {
            throw new IllegalArgumentException("Look at or stand inside a Garden property first.");
        }
        return claim;
    }

    private long parseDuration(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Rental duration is required.");
        }
        String value = raw.toLowerCase(Locale.ROOT).trim();
        long multiplier;
        String number;
        if (value.endsWith("d")) {
            multiplier = 24L * 60L;
            number = value.substring(0, value.length() - 1);
        } else if (value.endsWith("h")) {
            multiplier = 60L;
            number = value.substring(0, value.length() - 1);
        } else if (value.endsWith("m")) {
            multiplier = 1L;
            number = value.substring(0, value.length() - 1);
        } else {
            multiplier = 60L;
            number = value;
        }
        long amount = Long.parseLong(number);
        if (amount <= 0) {
            throw new IllegalArgumentException("Rental duration must be positive.");
        }
        return Math.multiplyExact(amount, multiplier);
    }

    private String displayDuration(long minutes) {
        if (minutes % (24L * 60L) == 0) {
            long days = minutes / (24L * 60L);
            return days + (days == 1 ? " day" : " days");
        }
        if (minutes % 60L == 0) {
            long hours = minutes / 60L;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("info", "list", "nightly", "accept", "book", "cancel", "status").stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("list")) {
            return List.of("1h", "12h", "1d", "7d", "30d");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("nightly")) {
            return List.of("1", "3", "7", "14", "30");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("book")) {
            return List.of("1", "2", "3", "7", "14");
        }
        return List.of();
    }
}
