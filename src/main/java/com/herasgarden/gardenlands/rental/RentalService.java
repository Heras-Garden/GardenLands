package com.herasgarden.gardenlands.rental;

import com.herasgarden.gardencore.api.GardenPlatform;
import com.herasgarden.gardencore.api.claim.ClaimTransferPolicy;
import com.herasgarden.gardencore.api.integration.IntegrationEventType;
import com.herasgarden.gardencore.api.land.PropertyAddress;
import com.herasgarden.gardencore.api.land.PropertyDirectory;
import com.herasgarden.gardencore.api.order.GardenOrder;
import com.herasgarden.gardencore.api.order.OrderState;
import com.herasgarden.gardencore.api.order.OrderType;
import com.herasgarden.gardencore.api.permission.AccessDecision;
import com.herasgarden.gardencore.api.permission.ClaimAccessAction;
import com.herasgarden.gardencore.api.permission.ClaimAccessPolicy;
import com.herasgarden.gardencore.api.storage.GardenStorage;
import com.herasgarden.gardenlands.claim.ClaimDirectory;
import com.herasgarden.gardenlands.claim.LandClaimRecord;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RentalService implements ClaimAccessPolicy, ClaimTransferPolicy {
    private final GardenPlatform platform;
    private final GardenStorage storage;
    private final ClaimDirectory claims;
    private final PropertyDirectory properties;
    private final Map<UUID, RentalRecord> openByClaim = new ConcurrentHashMap<>();

    public RentalService(GardenPlatform platform, ClaimDirectory claims, PropertyDirectory properties) {
        this.platform = platform;
        this.storage = platform.storage();
        this.claims = claims;
        this.properties = properties;
    }

    public synchronized void refresh() throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection connection = storage.connection();
             PreparedStatement expire = connection.prepareStatement(
                     "UPDATE gl_property_rentals SET status = 'EXPIRED', updated_at = ? "
                             + "WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at <= ?")) {
            expire.setLong(1, now);
            expire.setLong(2, now);
            expire.executeUpdate();
        }

        Map<UUID, RentalRecord> loaded = new ConcurrentHashMap<>();
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gl_property_rentals WHERE status IN ('LISTED', 'ACTIVE')");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                RentalRecord record = read(result);
                if (!"ACTIVE".equals(record.status()) || record.active(now)) {
                    loaded.put(record.claimId(), record);
                }
            }
        }
        openByClaim.clear();
        openByClaim.putAll(loaded);
    }

    public synchronized RentalRecord list(Player owner, LandClaimRecord claim, long price, long durationMinutes)
            throws SQLException {
        return createListing(owner, claim, price, durationMinutes, "FIXED", 1);
    }

    public synchronized RentalRecord listNightly(
            Player owner,
            LandClaimRecord claim,
            long nightlyPrice,
            int maxNights
    ) throws SQLException {
        if (claim == null || !"HOTEL_ROOM".equalsIgnoreCase(claim.type())) {
            throw new IllegalArgumentException(
                    "Nightly listings are only available for HOTEL_ROOM claims.");
        }
        if (maxNights < 1 || maxNights > 30) {
            throw new IllegalArgumentException("Maximum hotel stay must be between 1 and 30 nights.");
        }
        return createListing(owner, claim, nightlyPrice, 24L * 60L, "NIGHTLY", maxNights);
    }

    private RentalRecord createListing(
            Player owner,
            LandClaimRecord claim,
            long price,
            long durationMinutes,
            String pricingMode,
            int maxUnits
    ) throws SQLException {
        if (claim == null) {
            throw new IllegalArgumentException("Look at or stand inside the property you want to rent out.");
        }
        if (!"PLAYER".equalsIgnoreCase(claim.ownerType()) || !claim.ownerId().equals(owner.getUniqueId())) {
            throw new IllegalArgumentException("You must directly own this property to list it for rent.");
        }
        if (price <= 0) {
            throw new IllegalArgumentException("Rent must be a positive whole number of Obols.");
        }
        if (durationMinutes < 10 || durationMinutes > 43_200) {
            throw new IllegalArgumentException("Rental duration must be between 10 minutes and 30 days.");
        }

        PropertyAddress property = properties.findByClaim(claim.id())
                .orElseThrow(() -> new IllegalArgumentException("This claim is not a registered Garden property."));
        if (property.forSale()) {
            throw new IllegalArgumentException("Take this property off the sale market before listing it for rent.");
        }

        RentalRecord existing = openByClaim.get(claim.id());
        if (existing != null) {
            throw new IllegalArgumentException(existing.listed()
                    ? "This property is already listed for rent."
                    : "This property already has an active renter.");
        }

        long now = System.currentTimeMillis();
        RentalRecord record = new RentalRecord(
                UUID.randomUUID(), property.propertyId(), claim.id(), owner.getUniqueId(), null,
                price, durationMinutes, null, "LISTED", now, null, null, now
        );

        try (Connection connection = storage.connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO gl_property_rentals "
                                + "(rental_uuid, property_uuid, claim_uuid, owner_uuid, renter_uuid, price, "
                                + "duration_minutes, order_uuid, status, created_at, started_at, expires_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, NULL, ?, ?, NULL, 'LISTED', ?, NULL, NULL, ?)")) {
                    statement.setString(1, record.id().toString());
                    statement.setString(2, record.propertyId().toString());
                    statement.setString(3, record.claimId().toString());
                    statement.setString(4, record.ownerId().toString());
                    statement.setLong(5, record.price());
                    statement.setLong(6, record.durationMinutes());
                    statement.setLong(7, now);
                    statement.setLong(8, now);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO gl_property_rental_terms "
                                + "(rental_uuid, pricing_mode, unit_price, unit_minutes, max_units, created_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?)")) {
                    statement.setString(1, record.id().toString());
                    statement.setString(2, pricingMode);
                    statement.setLong(3, price);
                    statement.setLong(4, durationMinutes);
                    statement.setInt(5, maxUnits);
                    statement.setLong(6, now);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }

        openByClaim.put(claim.id(), record);
        return record;
    }

    public synchronized RentalRecord accept(Player renter, LandClaimRecord claim) throws SQLException {
        return acceptUnits(renter, claim, null);
    }

    public synchronized RentalRecord book(Player renter, LandClaimRecord claim, int nights) throws SQLException {
        return acceptUnits(renter, claim, nights);
    }

    private RentalRecord acceptUnits(Player renter, LandClaimRecord claim, Integer requestedUnits) throws SQLException {
        if (claim == null) {
            throw new IllegalArgumentException("Look at or stand inside the property you want to rent.");
        }
        RentalRecord listing = openByClaim.get(claim.id());
        if (listing == null || !listing.listed()) {
            throw new IllegalArgumentException("This property is not currently listed for rent.");
        }
        if (listing.ownerId().equals(renter.getUniqueId())) {
            throw new IllegalArgumentException("You cannot rent your own property.");
        }

        RentalTerms terms = terms(listing);
        int units;
        long totalPrice;
        long durationMinutes;
        try {
            if (terms.nightly()) {
                if (requestedUnits == null) {
                    throw new IllegalArgumentException(
                            "This is a nightly rental. Use /rent book <nights>.");
                }
                if (requestedUnits < 1 || requestedUnits > terms.maxUnits()) {
                    throw new IllegalArgumentException(
                            "Book between 1 and " + terms.maxUnits() + " nights.");
                }
                units = requestedUnits;
                totalPrice = Math.multiplyExact(terms.unitPrice(), (long) units);
                durationMinutes = Math.multiplyExact(terms.unitMinutes(), (long) units);
            } else {
                if (requestedUnits != null) {
                    throw new IllegalArgumentException(
                            "This is a fixed rental. Use /rent accept.");
                }
                units = 1;
                totalPrice = listing.price();
                durationMinutes = listing.durationMinutes();
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("That rental total is too large.");
        }

        claims.refresh();
        LandClaimRecord current = claims.find(claim.id())
                .orElseThrow(() -> new IllegalArgumentException("That property no longer exists."));
        if (!"PLAYER".equalsIgnoreCase(current.ownerType()) || !current.ownerId().equals(listing.ownerId())) {
            cancelStale(listing);
            throw new IllegalArgumentException("The property owner changed before the rental could begin.");
        }
        PropertyAddress property = properties.findByClaim(claim.id())
                .orElseThrow(() -> new IllegalArgumentException("This claim is no longer a registered Garden property."));
        if (property.forSale()) {
            throw new IllegalArgumentException("This property is currently for sale and cannot be rented.");
        }

        GardenOrder order = platform.orders().create(
                OrderType.PROPERTY_RENT,
                renter.getUniqueId(),
                "PLAYER",
                listing.ownerId().toString(),
                totalPrice,
                "gardenlands.rental",
                listing.id().toString(),
                "{\"propertyUuid\":\"" + listing.propertyId() + "\",\"claimUuid\":\""
                        + listing.claimId() + "\",\"pricingMode\":\"" + terms.pricingMode()
                        + "\",\"units\":" + units + ",\"durationMinutes\":" + durationMinutes + "}"
        );
        platform.orders().transition(order.id(), OrderState.READY, "Property rental prepared");
        platform.orders().transition(order.id(), OrderState.AWAITING_CONFIRMATION, "Rental accepted in game");
        platform.orders().transition(order.id(), OrderState.PAYMENT_PENDING, "Collecting rental payment");

        if (!platform.currency().withdraw(renter.getUniqueId(), totalPrice)) {
            platform.orders().transition(order.id(), OrderState.PAYMENT_FAILED, "Renter has insufficient Obols");
            throw new IllegalArgumentException("You do not have enough Obols for this rental.");
        }
        if (!platform.currency().deposit(listing.ownerId(), totalPrice)) {
            boolean refunded = platform.currency().deposit(renter.getUniqueId(), totalPrice);
            platform.orders().transition(order.id(),
                    refunded ? OrderState.PAYMENT_FAILED : OrderState.FULFILLMENT_FAILED,
                    refunded
                            ? "Owner payment failed; renter refunded"
                            : "Owner payment failed and renter refund requires admin review");
            throw new IllegalArgumentException(refunded
                    ? "The rental payment could not be completed. Your Obols were returned."
                    : "The rental payment needs administrator review.");
        }

        platform.orders().transition(order.id(), OrderState.PAID, "Rental payment completed");
        platform.orders().transition(order.id(), OrderState.FULFILLING, "Activating renter access");

        long startedAt = System.currentTimeMillis();
        long expiresAt;
        try {
            expiresAt = Math.addExact(startedAt, Math.multiplyExact(durationMinutes, 60_000L));
        } catch (ArithmeticException exception) {
            boolean reversed = reversePayment(listing.ownerId(), renter.getUniqueId(), totalPrice);
            platform.orders().transition(order.id(),
                    reversed ? OrderState.REFUNDED : OrderState.FULFILLMENT_FAILED,
                    reversed
                            ? "Rental duration overflowed; payment reversed"
                            : "Rental duration overflowed; payment reversal requires admin review");
            throw new IllegalArgumentException(reversed
                    ? "That rental duration is too large. Your payment was reversed."
                    : "That rental duration is too large and needs administrator review.");
        }

        int changed;
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gl_property_rentals SET renter_uuid = ?, order_uuid = ?, price = ?, "
                             + "duration_minutes = ?, status = 'ACTIVE', started_at = ?, expires_at = ?, updated_at = ? "
                             + "WHERE rental_uuid = ? AND status = 'LISTED'")) {
            statement.setString(1, renter.getUniqueId().toString());
            statement.setString(2, order.id().toString());
            statement.setLong(3, totalPrice);
            statement.setLong(4, durationMinutes);
            statement.setLong(5, startedAt);
            statement.setLong(6, expiresAt);
            statement.setLong(7, startedAt);
            statement.setString(8, listing.id().toString());
            changed = statement.executeUpdate();
        }

        if (changed != 1) {
            boolean reversed = reversePayment(listing.ownerId(), renter.getUniqueId(), totalPrice);
            platform.orders().transition(order.id(),
                    reversed ? OrderState.REFUNDED : OrderState.FULFILLMENT_FAILED,
                    reversed
                            ? "Rental listing changed before activation; payment reversed"
                            : "Rental listing changed; payment reversal requires admin review");
            throw new IllegalArgumentException(reversed
                    ? "This rental changed before it could be activated. Your payment was reversed."
                    : "This rental changed and needs administrator review.");
        }

        RentalRecord active = new RentalRecord(
                listing.id(), listing.propertyId(), listing.claimId(), listing.ownerId(), renter.getUniqueId(),
                totalPrice, durationMinutes, order.id(), "ACTIVE", listing.createdAt(),
                startedAt, expiresAt, startedAt
        );
        openByClaim.put(active.claimId(), active);
        platform.orders().transition(order.id(), OrderState.COMPLETED, "Renter access activated");

        try {
            platform.integrations().publish(
                    IntegrationEventType.PROPERTY_RENTED,
                    "property",
                    active.propertyId().toString(),
                    "{\"claimUuid\":\"" + active.claimId() + "\",\"ownerUuid\":\"" + active.ownerId()
                            + "\",\"renterUuid\":\"" + active.renterId() + "\",\"pricingMode\":\""
                            + terms.pricingMode() + "\",\"units\":" + units + ",\"amount\":"
                            + active.price() + ",\"expiresAt\":" + active.expiresAt() + "}"
            );
        } catch (SQLException ignored) {
        }
        return active;
    }

    public RentalTerms terms(RentalRecord record) throws SQLException {
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT pricing_mode, unit_price, unit_minutes, max_units "
                             + "FROM gl_property_rental_terms WHERE rental_uuid = ? LIMIT 1")) {
            statement.setString(1, record.id().toString());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return new RentalTerms(
                            result.getString("pricing_mode"),
                            result.getLong("unit_price"),
                            result.getLong("unit_minutes"),
                            result.getInt("max_units")
                    );
                }
            }
        }
        return new RentalTerms("FIXED", record.price(), record.durationMinutes(), 1);
    }

    public synchronized void cancelListing(UUID ownerId, LandClaimRecord claim) throws SQLException {
        if (claim == null) {
            throw new IllegalArgumentException("Look at or stand inside the property rental listing.");
        }
        RentalRecord listing = openByClaim.get(claim.id());
        if (listing == null || !listing.listed()) {
            throw new IllegalArgumentException("This property does not have an open rental listing.");
        }
        if (!listing.ownerId().equals(ownerId)) {
            throw new IllegalArgumentException("Only the property owner can cancel this rental listing.");
        }
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gl_property_rentals SET status = 'CANCELLED', updated_at = ? "
                             + "WHERE rental_uuid = ? AND status = 'LISTED'")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, listing.id().toString());
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("That rental listing is no longer open.");
            }
        }
        openByClaim.remove(claim.id(), listing);
    }

    public Optional<RentalRecord> openRental(UUID claimId) {
        RentalRecord record = openByClaim.get(claimId);
        if (record == null) return Optional.empty();
        if ("ACTIVE".equals(record.status()) && !record.active(System.currentTimeMillis())) {
            openByClaim.remove(claimId, record);
            return Optional.empty();
        }
        return Optional.of(record);
    }

    public List<RentalRecord> activeForRenter(UUID renterId) {
        long now = System.currentTimeMillis();
        List<RentalRecord> result = new ArrayList<>();
        for (RentalRecord record : openByClaim.values()) {
            if (record.active(now) && renterId.equals(record.renterId())) {
                result.add(record);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public AccessDecision decide(Player player, UUID claimId, ClaimAccessAction action) {
        RentalRecord record = openByClaim.get(claimId);
        if (record != null && record.active(System.currentTimeMillis())
                && player.getUniqueId().equals(record.renterId())) {
            return AccessDecision.ALLOW;
        }
        return AccessDecision.INHERIT;
    }

    @Override
    public Optional<String> blockReason(UUID claimId) {
        RentalRecord record = openByClaim.get(claimId);
        if (record == null) {
            return Optional.empty();
        }
        if ("ACTIVE".equals(record.status()) && !record.active(System.currentTimeMillis())) {
            openByClaim.remove(claimId, record);
            return Optional.empty();
        }
        return Optional.of(record.listed()
                ? "Cancel the rental listing before transferring or selling this property."
                : "This property has an active renter and cannot be transferred until the rental expires.");
    }

    private void cancelStale(RentalRecord listing) throws SQLException {
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gl_property_rentals SET status = 'STALE', updated_at = ? "
                             + "WHERE rental_uuid = ? AND status = 'LISTED'")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, listing.id().toString());
            statement.executeUpdate();
        }
        openByClaim.remove(listing.claimId(), listing);
    }

    private boolean reversePayment(UUID ownerId, UUID renterId, long amount) {
        if (!platform.currency().withdraw(ownerId, amount)) {
            return false;
        }
        if (platform.currency().deposit(renterId, amount)) {
            return true;
        }
        platform.currency().deposit(ownerId, amount);
        return false;
    }

    private RentalRecord read(ResultSet result) throws SQLException {
        String renter = result.getString("renter_uuid");
        String order = result.getString("order_uuid");
        Object started = result.getObject("started_at");
        Object expires = result.getObject("expires_at");
        return new RentalRecord(
                UUID.fromString(result.getString("rental_uuid")),
                UUID.fromString(result.getString("property_uuid")),
                UUID.fromString(result.getString("claim_uuid")),
                UUID.fromString(result.getString("owner_uuid")),
                renter == null ? null : UUID.fromString(renter),
                result.getLong("price"),
                result.getLong("duration_minutes"),
                order == null ? null : UUID.fromString(order),
                result.getString("status"),
                result.getLong("created_at"),
                started == null ? null : result.getLong("started_at"),
                expires == null ? null : result.getLong("expires_at"),
                result.getLong("updated_at")
        );
    }

    public record RentalTerms(
            String pricingMode,
            long unitPrice,
            long unitMinutes,
            int maxUnits
    ) {
        public boolean nightly() {
            return "NIGHTLY".equalsIgnoreCase(pricingMode);
        }
    }

}
