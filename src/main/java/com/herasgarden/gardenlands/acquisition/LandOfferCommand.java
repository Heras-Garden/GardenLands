package com.herasgarden.gardenlands.acquisition;

import com.herasgarden.gardenlands.claim.ClaimDirectory;
import com.herasgarden.gardenlands.claim.LandClaimRecord;
import com.herasgarden.gardenlands.ui.LandsMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class LandOfferCommand implements CommandExecutor, TabCompleter {
    private final LandPurchaseOfferService offers;
    private final ClaimDirectory claims;
    private final String symbol;

    public LandOfferCommand(LandPurchaseOfferService offers, ClaimDirectory claims, String symbol) {
        this.offers = offers;
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
            if (args.length == 0 || args[0].equalsIgnoreCase("offers")) {
                showIncoming(player);
                return true;
            }
            if (args[0].equalsIgnoreCase("offer")) {
                if (args.length < 2) {
                    LandsMessages.send(player, "Usage: /land offer <amount>");
                    return true;
                }
                long amount = parseAmount(args[1]);
                claims.refresh();
                LandClaimRecord target = targetClaim(player);
                LandPurchaseOffer offer = offers.create(player.getUniqueId(), target, amount);
                LandsMessages.send(player, "Offer sent for " + symbol + " " + amount + ".");
                Player seller = Bukkit.getPlayer(offer.sellerId());
                if (seller != null) {
                    seller.sendMessage(Component.text("[Server] ", NamedTextColor.GRAY)
                            .append(Component.text(player.getName() + " offered " + symbol + " " + amount
                                    + " for one of your properties. ", NamedTextColor.WHITE))
                            .append(Component.text("[Accept]", NamedTextColor.GREEN)
                                    .clickEvent(ClickEvent.runCommand("/land accept " + offer.id()))
                                    .hoverEvent(HoverEvent.showText(Component.text("Accept and transfer the property."))))
                            .append(Component.space())
                            .append(Component.text("[Decline]", NamedTextColor.RED)
                                    .clickEvent(ClickEvent.runCommand("/land decline " + offer.id()))));
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("accept") && args.length >= 2) {
                UUID id = UUID.fromString(args[1]);
                LandPurchaseOffer accepted = offers.accept(player.getUniqueId(), id);
                LandsMessages.send(player, "Property transferred. You received " + symbol + " " + accepted.amount() + ".");
                Player buyer = Bukkit.getPlayer(accepted.buyerId());
                if (buyer != null) {
                    LandsMessages.send(buyer, "Your land offer was accepted. The property is now yours.");
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("decline") && args.length >= 2) {
                UUID id = UUID.fromString(args[1]);
                LandPurchaseOffer existing = offers.find(id)
                        .orElseThrow(() -> new IllegalArgumentException("That land offer does not exist."));
                offers.decline(player.getUniqueId(), id);
                LandsMessages.send(player, "Land offer declined.");
                Player buyer = Bukkit.getPlayer(existing.buyerId());
                if (buyer != null) {
                    LandsMessages.send(buyer, "Your land purchase offer was declined.");
                }
                return true;
            }
            LandsMessages.send(player, "Usage: /land <offer <amount>|offers|accept <id>|decline <id>>");
        } catch (NumberFormatException exception) {
            LandsMessages.send(player, "Use a whole-number Obol amount.");
        } catch (IllegalArgumentException exception) {
            LandsMessages.send(player, exception.getMessage());
        } catch (SQLException exception) {
            LandsMessages.send(player, "The land offer could not be processed right now.");
        }
        return true;
    }

    private void showIncoming(Player player) throws SQLException {
        List<LandPurchaseOffer> incoming = offers.incoming(player.getUniqueId());
        if (incoming.isEmpty()) {
            LandsMessages.send(player, "You do not have any pending land offers.");
            return;
        }
        LandsMessages.send(player, "Pending land offers:");
        for (LandPurchaseOffer offer : incoming) {
            String buyer = Bukkit.getOfflinePlayer(offer.buyerId()).getName();
            if (buyer == null) {
                buyer = offer.buyerId().toString().substring(0, 8);
            }
            player.sendMessage(Component.text(buyer + " offered " + symbol + " " + offer.amount() + " ", NamedTextColor.WHITE)
                    .append(Component.text("[Accept]", NamedTextColor.GREEN)
                            .clickEvent(ClickEvent.runCommand("/land accept " + offer.id())))
                    .append(Component.space())
                    .append(Component.text("[Decline]", NamedTextColor.RED)
                            .clickEvent(ClickEvent.runCommand("/land decline " + offer.id()))));
        }
    }

    private LandClaimRecord targetClaim(Player player) {
        Block target = player.getTargetBlockExact(8);
        LandClaimRecord claim = target == null ? null : claims.findAt(target).orElse(null);
        if (claim == null) {
            claim = claims.findAt(player.getLocation().getBlock()).orElse(null);
        }
        if (claim == null) {
            throw new IllegalArgumentException("Look at or stand inside the property you want to buy.");
        }
        return claim;
    }

    private long parseAmount(String value) {
        long amount = Long.parseLong(value.replace(",", ""));
        if (amount <= 0) {
            throw new IllegalArgumentException("Offer amount must be greater than zero.");
        }
        return amount;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("offer", "offers", "accept", "decline").stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
