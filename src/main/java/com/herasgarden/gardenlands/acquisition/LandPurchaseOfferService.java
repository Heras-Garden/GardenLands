package com.herasgarden.gardenlands.acquisition;

import com.herasgarden.gardencore.api.GardenPlatform;
import com.herasgarden.gardencore.api.claim.ClaimOwnershipBridge;
import com.herasgarden.gardencore.api.claim.ClaimTransferPolicy;
import com.herasgarden.gardencore.api.integration.IntegrationEventType;
import com.herasgarden.gardencore.api.order.GardenOrder;
import com.herasgarden.gardencore.api.order.OrderState;
import com.herasgarden.gardencore.api.order.OrderType;
import com.herasgarden.gardencore.api.storage.GardenStorage;
import com.herasgarden.gardenlands.claim.ClaimDirectory;
import com.herasgarden.gardenlands.claim.LandClaimRecord;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class LandPurchaseOfferService {
    private final GardenPlatform platform;
    private final GardenStorage storage;
    private final ClaimDirectory claims;
    private final ClaimOwnershipBridge ownership;

    public LandPurchaseOfferService(GardenPlatform platform, ClaimDirectory claims, ClaimOwnershipBridge ownership) {
        this.platform = platform;
        this.storage = platform.storage();
        this.claims = claims;
        this.ownership = ownership;
    }

    public LandPurchaseOffer create(UUID buyerId, LandClaimRecord target, long amount) throws SQLException {
        if (target == null) {
            throw new IllegalArgumentException("Look at the property you want to buy first.");
        }
        if ("TERRITORY".equalsIgnoreCase(target.type())) {
            throw new IllegalArgumentException("Territories cannot be purchased through a property offer.");
        }
        if (!"PLAYER".equalsIgnoreCase(target.ownerType())) {
            throw new IllegalArgumentException("This claim is organization-owned. Organization acquisitions will use the government/company workflow.");
        }
        if (target.ownerId().equals(buyerId)) {
            throw new IllegalArgumentException("You already own this claim.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Offer amount must be greater than zero.");
        }
        if (hasPendingOffer(buyerId, target.id())) {
            throw new IllegalArgumentException("You already have a pending offer for this claim.");
        }
        Optional<String> blocked = transferBlockReason(target.id());
        if (blocked.isPresent()) {
            throw new IllegalArgumentException(blocked.get());
        }

        GardenOrder order = platform.orders().create(
                OrderType.PROPERTY_PURCHASE,
                buyerId,
                "PLAYER",
                target.ownerId().toString(),
                amount,
                "gardenlands.land-offer",
                target.id().toString(),
                "{\"kind\":\"voluntary-land-acquisition\"}"
        );
        platform.orders().transition(order.id(), OrderState.READY, "Land purchase offer prepared");
        platform.orders().transition(order.id(), OrderState.AWAITING_CONFIRMATION, "Waiting for property owner response");

        long now = System.currentTimeMillis();
        LandPurchaseOffer offer = new LandPurchaseOffer(
                UUID.randomUUID(), target.id(), buyerId, target.ownerId(), amount,
                order.id(), "PENDING", now, now);
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO gl_land_purchase_offers "
                             + "(offer_uuid, target_claim_uuid, buyer_uuid, seller_uuid, amount, order_uuid, status, created_at, updated_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            bind(statement, offer);
            statement.executeUpdate();
        }
        return offer;
    }

    public List<LandPurchaseOffer> incoming(UUID sellerId) throws SQLException {
        List<LandPurchaseOffer> offers = new ArrayList<>();
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gl_land_purchase_offers WHERE seller_uuid = ? AND status = 'PENDING' ORDER BY created_at")) {
            statement.setString(1, sellerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    offers.add(read(result));
                }
            }
        }
        return offers;
    }

    public Optional<LandPurchaseOffer> find(UUID offerId) throws SQLException {
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gl_land_purchase_offers WHERE offer_uuid = ?")) {
            statement.setString(1, offerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    public synchronized LandPurchaseOffer accept(UUID sellerId, UUID offerId) throws SQLException {
        LandPurchaseOffer offer = find(offerId)
                .orElseThrow(() -> new IllegalArgumentException("That land offer does not exist."));
        if (!"PENDING".equals(offer.status())) {
            throw new IllegalArgumentException("That land offer is no longer pending.");
        }
        if (!offer.sellerId().equals(sellerId)) {
            throw new IllegalArgumentException("Only the current property owner can accept this offer.");
        }

        claims.refresh();
        LandClaimRecord target = claims.find(offer.targetClaimId())
                .orElseThrow(() -> new IllegalArgumentException("The target claim no longer exists."));
        if (!"PLAYER".equalsIgnoreCase(target.ownerType()) || !target.ownerId().equals(sellerId)) {
            cancelStale(offer, "Property owner changed before acceptance");
            throw new IllegalArgumentException("You no longer own the target claim.");
        }
        Optional<String> blocked = transferBlockReason(target.id());
        if (blocked.isPresent()) {
            throw new IllegalArgumentException(blocked.get());
        }
        if (!platform.currency().has(offer.buyerId(), offer.amount())) {
            platform.orders().transition(offer.orderId(), OrderState.PAYMENT_PENDING, "Seller accepted land offer");
            platform.orders().transition(offer.orderId(), OrderState.PAYMENT_FAILED, "Buyer no longer has enough Obols");
            updateStatus(offer.id(), "PAYMENT_FAILED");
            throw new IllegalArgumentException("The buyer no longer has enough Obols for this offer.");
        }

        platform.orders().transition(offer.orderId(), OrderState.PAYMENT_PENDING, "Seller accepted land offer");
        if (!platform.currency().withdraw(offer.buyerId(), offer.amount())) {
            platform.orders().transition(offer.orderId(), OrderState.PAYMENT_FAILED, "Buyer payment could not be withdrawn");
            updateStatus(offer.id(), "PAYMENT_FAILED");
            throw new IllegalArgumentException("The buyer payment could not be collected.");
        }
        if (!platform.currency().deposit(offer.sellerId(), offer.amount())) {
            platform.currency().deposit(offer.buyerId(), offer.amount());
            platform.orders().transition(offer.orderId(), OrderState.PAYMENT_FAILED, "Seller payment could not be deposited; buyer refunded");
            updateStatus(offer.id(), "PAYMENT_FAILED");
            throw new IllegalArgumentException("The seller payment could not be deposited. The buyer was refunded.");
        }

        platform.orders().transition(offer.orderId(), OrderState.PAID, "Land offer paid");
        platform.orders().transition(offer.orderId(), OrderState.FULFILLING, "Transferring claim ownership");

        boolean transferred;
        try {
            transferred = ownership.transferPlayerClaim(
                    offer.targetClaimId(), offer.sellerId(), offer.buyerId());
        } catch (SQLException | RuntimeException exception) {
            refundAfterFulfillmentFailure(offer, "Ownership transfer failed: " + exception.getMessage());
            throw exception;
        }
        if (!transferred) {
            refundAfterFulfillmentFailure(offer, "Ownership changed before transfer");
            throw new IllegalArgumentException("The property changed before it could be transferred. Both players were refunded to their previous balances.");
        }

        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gc_properties SET for_sale = 0 WHERE claim_uuid = ?")) {
            statement.setString(1, offer.targetClaimId().toString());
            statement.executeUpdate();
        }

        updateStatus(offer.id(), "ACCEPTED");
        claims.refresh();
        platform.orders().transition(offer.orderId(), OrderState.COMPLETED, "Claim ownership transferred");
        platform.integrations().publish(
                IntegrationEventType.PROPERTY_SOLD,
                "claim",
                offer.targetClaimId().toString(),
                "{\"buyerUuid\":\"" + offer.buyerId() + "\",\"sellerUuid\":\"" + offer.sellerId()
                        + "\",\"amount\":" + offer.amount() + "}"
        );
        return find(offer.id()).orElse(offer);
    }

    public synchronized void decline(UUID sellerId, UUID offerId) throws SQLException {
        LandPurchaseOffer offer = find(offerId)
                .orElseThrow(() -> new IllegalArgumentException("That land offer does not exist."));
        if (!"PENDING".equals(offer.status())) {
            throw new IllegalArgumentException("That land offer is no longer pending.");
        }
        if (!offer.sellerId().equals(sellerId)) {
            throw new IllegalArgumentException("Only the property owner can decline this offer.");
        }
        platform.orders().transition(offer.orderId(), OrderState.CANCELLED, "Property owner declined land offer");
        updateStatus(offer.id(), "DECLINED");
    }

    private void cancelStale(LandPurchaseOffer offer, String detail) throws SQLException {
        try {
            platform.orders().transition(offer.orderId(), OrderState.CANCELLED, detail);
        } catch (RuntimeException ignored) {
        }
        updateStatus(offer.id(), "STALE");
    }

    private void refundAfterFulfillmentFailure(LandPurchaseOffer offer, String detail) throws SQLException {
        boolean sellerDebit = platform.currency().withdraw(offer.sellerId(), offer.amount());
        boolean buyerRefund = sellerDebit && platform.currency().deposit(offer.buyerId(), offer.amount());
        if (sellerDebit && buyerRefund) {
            platform.orders().transition(offer.orderId(), OrderState.REFUNDED, detail + "; payment reversed");
            updateStatus(offer.id(), "REFUNDED");
        } else {
            platform.orders().transition(offer.orderId(), OrderState.FULFILLMENT_FAILED,
                    detail + "; automatic payment reversal requires admin review");
            updateStatus(offer.id(), "REVIEW_REQUIRED");
        }
    }

    private Optional<String> transferBlockReason(UUID claimId) {
        RegisteredServiceProvider<ClaimTransferPolicy> registration =
                Bukkit.getServicesManager().getRegistration(ClaimTransferPolicy.class);
        if (registration == null || registration.getProvider() == null) {
            return Optional.empty();
        }
        try {
            Optional<String> result = registration.getProvider().blockReason(claimId);
            return result == null ? Optional.empty() : result;
        } catch (RuntimeException exception) {
            return Optional.of("This property cannot be transferred right now.");
        }
    }

    private boolean hasPendingOffer(UUID buyerId, UUID claimId) throws SQLException {
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM gl_land_purchase_offers WHERE buyer_uuid = ? AND target_claim_uuid = ? "
                             + "AND status = 'PENDING' LIMIT 1")) {
            statement.setString(1, buyerId.toString());
            statement.setString(2, claimId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void updateStatus(UUID offerId, String status) throws SQLException {
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gl_land_purchase_offers SET status = ?, updated_at = ? WHERE offer_uuid = ?")) {
            statement.setString(1, status);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, offerId.toString());
            statement.executeUpdate();
        }
    }

    private LandPurchaseOffer read(ResultSet result) throws SQLException {
        return new LandPurchaseOffer(
                UUID.fromString(result.getString("offer_uuid")),
                UUID.fromString(result.getString("target_claim_uuid")),
                UUID.fromString(result.getString("buyer_uuid")),
                UUID.fromString(result.getString("seller_uuid")),
                result.getLong("amount"),
                UUID.fromString(result.getString("order_uuid")),
                result.getString("status"),
                result.getLong("created_at"),
                result.getLong("updated_at")
        );
    }

    private void bind(PreparedStatement statement, LandPurchaseOffer offer) throws SQLException {
        statement.setString(1, offer.id().toString());
        statement.setString(2, offer.targetClaimId().toString());
        statement.setString(3, offer.buyerId().toString());
        statement.setString(4, offer.sellerId().toString());
        statement.setLong(5, offer.amount());
        statement.setString(6, offer.orderId().toString());
        statement.setString(7, offer.status());
        statement.setLong(8, offer.createdAt());
        statement.setLong(9, offer.updatedAt());
    }
}
