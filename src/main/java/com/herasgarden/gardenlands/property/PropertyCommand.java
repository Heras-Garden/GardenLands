package com.herasgarden.gardenlands.property;

import com.herasgarden.gardencore.api.land.PropertyAddress;
import com.herasgarden.gardencore.api.land.PropertyDirectory;
import com.herasgarden.gardencore.api.land.PropertyManagementService;
import com.herasgarden.gardencore.api.land.PropertyMailbox;
import com.herasgarden.gardencore.api.land.PropertyPurchaseResult;
import com.herasgarden.gardencore.api.organization.OrganizationDirectory;
import com.herasgarden.gardencore.api.ui.GardenMessages;
import com.herasgarden.gardenlands.claim.ClaimDirectory;
import com.herasgarden.gardenlands.claim.LandClaimRecord;
import com.herasgarden.gardenlands.container.ContainerPermissionService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class PropertyCommand implements CommandExecutor, TabCompleter {
    private static final TextColor TEXT = GardenMessages.MESSAGE_COLOR;
    private static final TextColor MUTED = GardenMessages.NEUTRAL_GRAY;
    private static final TextColor ACCENT = GardenMessages.PETAL_FROST;
    private static final TextColor NEGATIVE = GardenMessages.BUBBLEGUM_PINK;

    private final PropertyDirectory properties;
    private final PropertyManagementService management;
    private final ClaimDirectory claims;
    private final OrganizationDirectory organizations;
    private final ContainerPermissionService containers;

    public PropertyCommand(
            PropertyDirectory properties,
            PropertyManagementService management,
            ClaimDirectory claims,
            OrganizationDirectory organizations,
            ContainerPermissionService containers
    ) {
        this.properties = properties;
        this.management = management;
        this.claims = claims;
        this.organizations = organizations;
        this.containers = containers;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("gardenlands.property")) {
            GardenMessages.send(sender, "You do not have permission to use Garden property commands.");
            return true;
        }
        try {
            if (args.length == 0) {
                help(sender);
                return true;
            }
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "register" -> register(sender, args, false);
                case "registerunit", "unit" -> register(sender, args, true);
                case "sell" -> sell(sender, args);
                case "market", "offmarket" -> market(sender, args);
                case "edit" -> edit(sender, args);
                case "editid" -> editById(sender, args);
                case "delete" -> delete(sender, args);
                case "mailbox" -> mailbox(sender);
                case "info" -> info(sender);
                case "buy" -> buy(sender, args);
                case "cancel", "cancelpurchase", "canceldelete" -> cancel(sender);
                case "list" -> list(sender);
                default -> {
                    help(sender);
                    yield true;
                }
            };
        } catch (IllegalArgumentException exception) {
            GardenMessages.send(sender, exception.getMessage());
        } catch (SQLException exception) {
            GardenMessages.send(sender, "The property system could not update right now.");
        }
        return true;
    }

    private boolean register(CommandSender sender, String[] args, boolean unitAddress) throws SQLException {
        Player player = requirePlayer(sender, "Register property addresses in-game while standing inside the claim.");
        if (player == null) return true;

        String number;
        String unit = null;
        int roadStart;
        if (unitAddress) {
            if (args.length < 4) {
                GardenMessages.send(player, "Use /property registerunit <number> <unit> <road...>.");
                return true;
            }
            number = args[1];
            unit = args[2];
            roadStart = 3;
        } else {
            if (args.length < 3) {
                GardenMessages.send(player, "Use /property register <number> <road...>.");
                return true;
            }
            number = args[1];
            roadStart = 2;
        }

        String road = join(args, roadStart);
        if (number.isBlank() || road.isBlank() || (unitAddress && (unit == null || unit.isBlank()))) {
            GardenMessages.send(player, "The property address must include a valid road and number.");
            return true;
        }

        LandClaimRecord claim = currentClaim(player);
        requireManage(player, claim);
        if (claim.is("UNIT") && claim.tagged("HOTEL_ROOM")) {
            throw new IllegalArgumentException("Hotel rooms are temporary units and do not use registered addresses.");
        }
        if (claim.is("UNIT") && claim.tagged("APARTMENT") && !unitAddress) {
            throw new IllegalArgumentException("Apartments require a unit address. Use /property registerunit <number> <unit> <road...>.");
        }
        PropertyAddress property = management.register(claim.id(), road, number, unit);
        GardenMessages.send(player, "Registered " + property.display() + ".");
        if (claim.is("UNIT") && claim.tagged("APARTMENT")) {
            GardenMessages.send(player,
                    "Place the room sign on the apartment wall first, then place the matching mailbox sign on its mailbox chest.");
        } else {
            GardenMessages.send(player,
                    "Place the property sign on the front of its mailbox chest with road, number, and price.");
        }
        return true;
    }

    private boolean sell(CommandSender sender, String[] args) throws SQLException {
        Player player = requirePlayer(sender, "This command must be used in-game.");
        if (player == null) return true;
        if (args.length < 2) {
            GardenMessages.send(player, "Use /property sell <price> [any|player|society] while standing inside the property.");
            return true;
        }

        long price = positiveLong(args[1], "The sale price must be a positive whole number of Obols.");
        String audience = args.length >= 3 ? normalizeAudience(args[2]) : "ANY";
        PropertyAddress property = currentProperty(player);
        requireManage(player, requireClaim(property.claimId()));
        management.setBuyerAudience(property.propertyId(), audience);
        property = management.setForSale(property.propertyId(), price);
        GardenMessages.send(player, property.display() + " is for sale for ⟡ " + price
                + " to " + audienceLabel(audience) + ".");
        return true;
    }

    private boolean market(CommandSender sender, String[] args) throws SQLException {
        Player player = requirePlayer(sender, "This command must be used in-game.");
        if (player == null) return true;

        PropertyAddress property = currentProperty(player);
        requireManage(player, requireClaim(property.claimId()));
        boolean turnOff = args.length < 2 || args[1].equalsIgnoreCase("off");
        if (!turnOff) {
            GardenMessages.send(player, "Use /property sell <price>, or /property market off.");
            return true;
        }
        property = management.takeOffMarket(property.propertyId());
        GardenMessages.send(player, property.display() + " is no longer for sale.");
        return true;
    }

    private boolean edit(CommandSender sender, String[] args) throws SQLException {
        Player player = requirePlayer(sender, "Property editing must be done in-game.");
        if (player == null) return true;

        PropertyAddress property = currentProperty(player);
        requireManage(player, requireClaim(property.claimId()));
        if (args.length < 2) {
            sendEditMenu(player, property);
            return true;
        }
        editAction(player, property, args[1], args, 2);
        return true;
    }

    private boolean editById(CommandSender sender, String[] args) throws SQLException {
        Player player = requirePlayer(sender, "Property editing must be done in-game.");
        if (player == null) return true;
        if (args.length < 2) {
            throw new IllegalArgumentException("That property edit link is incomplete.");
        }

        UUID propertyId = uuid(args[1], "That property edit link is no longer valid.");
        PropertyAddress property = management.find(propertyId)
                .orElseThrow(() -> new IllegalArgumentException("That property no longer exists."));
        requireManage(player, requireClaim(property.claimId()));
        if (args.length == 2) {
            sendEditMenu(player, property);
            return true;
        }
        editAction(player, property, args[2], args, 3);
        return true;
    }

    private void editAction(
            Player player,
            PropertyAddress property,
            String action,
            String[] args,
            int valueStart
    ) throws SQLException {
        switch (action.toLowerCase(Locale.ROOT)) {
            case "price" -> {
                if (args.length <= valueStart) {
                    GardenMessages.send(player, "Enter a price after the command.");
                    return;
                }
                long price = positiveLong(args[valueStart], "The price must be a positive whole number.");
                management.setForSale(property.propertyId(), price);
                GardenMessages.send(player, "Price updated to ⟡ " + price + ".");
            }
            case "address" -> {
                if (args.length <= valueStart + 1) {
                    GardenMessages.send(player, "Use number followed by the road name.");
                    return;
                }
                String number = args[valueStart];
                String road = join(args, valueStart + 1);
                PropertyAddress updated = management.updateAddress(
                        property.propertyId(), road, number, property.unitLabel());
                GardenMessages.send(player, "Address updated to " + updated.display() + ".");
            }
            case "unit" -> {
                if (args.length <= valueStart) {
                    GardenMessages.send(player, "Enter a unit label, or none.");
                    return;
                }
                String unit = args[valueStart].equalsIgnoreCase("none")
                        ? null : join(args, valueStart);
                PropertyAddress updated = management.updateAddress(
                        property.propertyId(), property.road(), property.number(), unit);
                GardenMessages.send(player, "Unit updated. Address: " + updated.display() + ".");
            }
            case "audience" -> {
                if (args.length <= valueStart) {
                    GardenMessages.send(player, "Use audience any, player, or society.");
                    return;
                }
                String audience = normalizeAudience(args[valueStart]);
                management.setBuyerAudience(property.propertyId(), audience);
                GardenMessages.send(player, "Buyer audience set to " + audienceLabel(audience) + ".");
            }
            case "offmarket" -> {
                PropertyAddress updated = management.takeOffMarket(property.propertyId());
                GardenMessages.send(player, updated.display() + " is no longer for sale.");
            }
            case "delete" -> sendDeletePrompt(player, property);
            default -> sendEditMenu(player, property);
        }
    }

    private boolean delete(CommandSender sender, String[] args) throws SQLException {
        Player player = requirePlayer(sender, "Property deletion must be confirmed in-game.");
        if (player == null) return true;

        if (args.length >= 3 && args[1].equalsIgnoreCase("confirm")) {
            UUID id = uuid(args[2], "That property deletion link is no longer valid.");
            PropertyAddress property = management.find(id)
                    .orElseThrow(() -> new IllegalArgumentException("That property no longer exists."));
            requireManage(player, requireClaim(property.claimId()));
            String address = property.display();
            PropertyMailbox mailbox = properties.mailbox(id).orElse(null);
            management.delete(id);
            if (mailbox != null) {
                containers.forgetMailbox(
                        mailbox.worldId(), mailbox.x(), mailbox.y(), mailbox.z());
            }
            claims.refresh();
            GardenMessages.send(player,
                    "Deleted " + address + ". Its linked property signs and claim were removed.");
            return true;
        }

        PropertyAddress property = currentProperty(player);
        requireManage(player, requireClaim(property.claimId()));
        sendDeletePrompt(player, property);
        return true;
    }

    private boolean mailbox(CommandSender sender) throws SQLException {
        Player player = requirePlayer(sender, "Mailbox setup is done in-game with the property sign.");
        if (player == null) return true;

        PropertyAddress property = currentProperty(player);
        LandClaimRecord claim = requireClaim(property.claimId());
        requireManage(player, claim);
        if (claim.is("UNIT") && claim.tagged("APARTMENT")) {
            GardenMessages.send(player,
                    "Apartment mailboxes use a second matching property sign on the mailbox chest.");
        } else {
            GardenMessages.send(player,
                    "This property's address/listing sign belongs on the front of its mailbox chest. GardenLands uses that registered mailbox for delivery.");
        }
        return true;
    }

    private boolean info(CommandSender sender) throws SQLException {
        Player player = requirePlayer(sender, "Use this command in-game while standing inside a property.");
        if (player == null) return true;

        PropertyAddress property = currentProperty(player);
        LandClaimRecord claim = requireClaim(property.claimId());
        String audience = management.buyerAudience(property.propertyId());
        String market = property.forSale()
                ? "For sale: ⟡ " + property.price() + " | Buyers: " + audienceLabel(audience)
                : "Not for sale";
        GardenMessages.send(player,
                property.display() + " | Owner: " + ownerDisplay(claim) + " | " + market + ".");
        return true;
    }

    private boolean buy(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "Property purchases must be completed in-game.");
        if (player == null) return true;
        if (args.length < 2) {
            GardenMessages.send(player, "Right-click a Garden property sign and choose Yes or No.");
            return true;
        }

        UUID propertyId = uuid(args[1], "That property purchase link is no longer valid.");
        PropertyPurchaseResult result = management.purchase(player, propertyId);
        GardenMessages.send(player, result.message());
        try {
            claims.refresh();
        } catch (SQLException exception) {
            // Ownership is already durable; scheduled cache refresh will reconcile.
        }
        return true;
    }

    private boolean cancel(CommandSender sender) {
        GardenMessages.send(sender, "Cancelled.");
        return true;
    }

    private boolean list(CommandSender sender) {
        if (!sender.hasPermission("gardenlands.property.admin")) {
            GardenMessages.send(sender, "You do not have permission to list all properties.");
            return true;
        }
        List<PropertyAddress> all = management.all();
        GardenMessages.send(sender, "Registered properties: " + all.size() + ".");
        for (PropertyAddress property : all) {
            LandClaimRecord claim = claims.find(property.claimId()).orElse(null);
            String owner = claim == null ? "Unavailable" : ownerDisplay(claim);
            sender.sendMessage("- " + property.display() + " | " + owner
                    + (property.forSale() ? " | ⟡ " + property.price() : ""));
        }
        return true;
    }

    private PropertyAddress currentProperty(Player player) throws SQLException {
        LandClaimRecord claim = currentClaim(player);
        return properties.findByClaim(claim.id())
                .orElseThrow(() -> new IllegalArgumentException(
                        "This claim does not have a property address yet."));
    }

    private LandClaimRecord currentClaim(Player player) {
        return claims.findAt(player.getLocation().getBlock())
                .orElseThrow(() -> new IllegalArgumentException("There is no Garden claim here."));
    }

    private LandClaimRecord requireClaim(UUID claimId) {
        return claims.find(claimId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "The claim linked to this property is unavailable."));
    }

    private void requireManage(Player player, LandClaimRecord claim) {
        if (!claims.canManage(player, claim)
                && !player.hasPermission("gardenlands.property.admin")) {
            throw new IllegalArgumentException("You do not manage this property.");
        }
    }

    private String ownerDisplay(LandClaimRecord claim) {
        if ("PLAYER".equalsIgnoreCase(claim.ownerType())) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(claim.ownerId());
            String name = player.getName();
            return name == null || name.isBlank()
                    ? claim.ownerId().toString().substring(0, 8) : name;
        }
        if (organizations != null) {
            return organizations.find(claim.ownerId())
                    .map(value -> value.name())
                    .orElse(claim.ownerType());
        }
        return claim.ownerType();
    }

    private Player requirePlayer(CommandSender sender, String message) {
        if (sender instanceof Player player) return player;
        GardenMessages.send(sender, message);
        return null;
    }

    private void sendEditMenu(Player player, PropertyAddress property) {
        player.sendActionBar(Component.text("Property editor | " + property.display(), MUTED));
        Component menu = GardenMessages.prefix()
                .append(Component.text("Property editor", GardenMessages.PETAL_FROST))
                .append(Component.newline())
                .append(Component.text(property.display(), TEXT))
                .append(Component.newline())
                .append(Component.text("Choose what you want to change.", MUTED))
                .append(Component.newline())
                .append(suggestButton("Price", "/property editid " + property.propertyId() + " price ",
                        "Change the sale price."))
                .append(Component.space())
                .append(suggestButton("Address", "/property editid " + property.propertyId() + " address ",
                        "Format: number road name"))
                .append(Component.space())
                .append(suggestButton("Unit", "/property editid " + property.propertyId() + " unit ",
                        "Set a unit label, or type none."))
                .append(Component.space())
                .append(suggestButton("Buyer Audience", "/property editid " + property.propertyId() + " audience ",
                        "Choose any, player, or society."))
                .append(Component.space())
                .append(runButton("Off Market", "/property editid " + property.propertyId() + " offmarket",
                        MUTED, "Remove the property from sale."))
                .append(Component.space())
                .append(runButton("Delete Property", "/property editid " + property.propertyId() + " delete",
                        NEGATIVE, "Delete the property, claim, and linked signs."));
        player.sendMessage(menu);
    }

    private void sendDeletePrompt(Player player, PropertyAddress property) {
        Component prompt = GardenMessages.prefix()
                .append(Component.text("Delete property", GardenMessages.PETAL_FROST))
                .append(Component.newline())
                .append(Component.text(
                        "Delete " + property.display()
                                + "? This also deletes its claim and every linked property/mailbox sign.",
                        TEXT))
                .append(Component.newline())
                .append(runButton("Yes, Delete",
                        "/property delete confirm " + property.propertyId(),
                        NEGATIVE,
                        "Permanently delete this property."))
                .append(Component.space())
                .append(runButton("No", "/property canceldelete", MUTED, "Keep the property."));
        player.sendMessage(prompt);
    }

    private String normalizeAudience(String value) {
        String audience = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (audience) {
            case "ANY" -> "ANY";
            case "PLAYER", "PLAYERS" -> "PLAYER";
            case "SOCIETY", "NPC", "NPCS" -> "SOCIETY";
            default -> throw new IllegalArgumentException("Buyer audience must be any, player, or society.");
        };
    }

    private String audienceLabel(String audience) {
        return switch (audience == null ? "ANY" : audience.toUpperCase(Locale.ROOT)) {
            case "PLAYER" -> "players only";
            case "SOCIETY" -> "Society residents only";
            default -> "players or Society residents";
        };
    }

    private long positiveLong(String value, String error) {
        try {
            long parsed = Long.parseLong(value.replace("⟡", "").replace(",", "").trim());
            if (parsed > 0) return parsed;
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException(error);
    }

    private UUID uuid(String value, String error) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(error);
        }
    }

    private String join(String[] args, int start) {
        return String.join(" ", Arrays.copyOfRange(args, start, args.length)).trim();
    }

    private Component suggestButton(String label, String command, String hover) {
        return Component.text("[" + label + "]", ACCENT)
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover, MUTED)));
    }

    private Component runButton(String label, String command, TextColor color, String hover) {
        return Component.text("[" + label + "]", color)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover, MUTED)));
    }

    private void help(CommandSender sender) {
        GardenMessages.send(sender,
                "Use /property <register|registerunit|sell|market|edit|delete|mailbox|info|buy|list>. Sell supports buyer audience: any, player, or society.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of(
                    "register", "registerunit", "sell", "market", "edit", "delete",
                    "mailbox", "info", "buy", "list")
                    .stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("market")) {
            return "off".startsWith(args[1].toLowerCase(Locale.ROOT)) ? List.of("off") : List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("edit")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of("price", "address", "unit", "audience")
                    .stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("edit")
                && args[1].equalsIgnoreCase("audience")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return List.of("any", "player", "society").stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("sell")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return List.of("any", "player", "society").stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("edit")
                && args[1].equalsIgnoreCase("unit")) {
            return "none".startsWith(args[2].toLowerCase(Locale.ROOT)) ? List.of("none") : List.of();
        }
        return List.of();
    }
}
