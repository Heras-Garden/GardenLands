package com.herasgarden.gardenlands.property;

import com.herasgarden.gardencore.api.land.PropertyAddress;
import com.herasgarden.gardencore.api.land.PropertyDirectory;
import com.herasgarden.gardencore.api.land.PropertyMailbox;
import com.herasgarden.gardencore.api.storage.GardenStorage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * GardenLands view over the transitional gc_* property tables.
 *
 * The storage tables remain in GardenCore during modularization, while domain
 * plugins consume property data through this GardenLands-owned service.
 */
public final class LandsPropertyDirectory implements PropertyDirectory {
    private final GardenStorage storage;

    public LandsPropertyDirectory(GardenStorage storage) {
        this.storage = storage;
    }

    @Override
    public Optional<PropertyAddress> find(UUID propertyId) throws SQLException {
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gc_properties WHERE property_uuid = ?")) {
            statement.setString(1, propertyId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(address(result)) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<PropertyAddress> findByClaim(UUID claimId) throws SQLException {
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gc_properties WHERE claim_uuid = ? LIMIT 1")) {
            statement.setString(1, claimId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(address(result)) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<PropertyAddress> resolveAddress(
            String scopeKey,
            String road,
            String number,
            String unitLabel
    ) throws SQLException {
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gc_properties "
                             + "WHERE scope_key = ? AND road_key = ? AND number_key = ? AND unit_key = ? LIMIT 1")) {
            statement.setString(1, scopeKey == null || scopeKey.isBlank() ? "global" : scopeKey.trim());
            statement.setString(2, normalize(road));
            statement.setString(3, normalize(number));
            statement.setString(4, normalize(unitLabel));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(address(result)) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<PropertyMailbox> mailbox(UUID propertyId) throws SQLException {
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT p.property_uuid, p.claim_uuid, p.road, p.number, p.unit_label, "
                             + "m.world_uuid, m.world_name, m.x, m.y, m.z "
                             + "FROM gc_properties p "
                             + "JOIN gc_property_mailboxes m ON m.property_uuid = p.property_uuid "
                             + "WHERE p.property_uuid = ? LIMIT 1")) {
            statement.setString(1, propertyId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mailbox(result)) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<PropertyMailbox> mailboxAt(UUID worldId, int x, int y, int z) throws SQLException {
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT p.property_uuid, p.claim_uuid, p.road, p.number, p.unit_label, "
                             + "m.world_uuid, m.world_name, m.x, m.y, m.z "
                             + "FROM gc_property_mailboxes m "
                             + "JOIN gc_properties p ON p.property_uuid = m.property_uuid "
                             + "WHERE m.world_uuid = ? AND m.x = ? AND m.y = ? AND m.z = ? LIMIT 1")) {
            statement.setString(1, worldId.toString());
            statement.setInt(2, x);
            statement.setInt(3, y);
            statement.setInt(4, z);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mailbox(result)) : Optional.empty();
            }
        }
    }

    @Override
    public List<PropertyMailbox> mailboxesOwnedBy(UUID playerId) throws SQLException {
        List<PropertyMailbox> mailboxes = new ArrayList<>();
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT p.property_uuid, p.claim_uuid, p.road, p.number, p.unit_label, "
                             + "m.world_uuid, m.world_name, m.x, m.y, m.z "
                             + "FROM gc_properties p "
                             + "JOIN gc_property_mailboxes m ON m.property_uuid = p.property_uuid "
                             + "JOIN gc_claims c ON c.claim_uuid = p.claim_uuid "
                             + "WHERE c.owner_type = 'PLAYER' AND c.owner_uuid = ? "
                             + "ORDER BY p.created_at ASC")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    mailboxes.add(mailbox(result));
                }
            }
        }
        return List.copyOf(mailboxes);
    }

    @Override
    public List<PropertyAddress> listedForSale() throws SQLException {
        List<PropertyAddress> properties = new ArrayList<>();
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gc_properties WHERE for_sale = 1 ORDER BY created_at ASC");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                properties.add(address(result));
            }
        }
        return List.copyOf(properties);
    }

    @Override
    public boolean isOwnedBy(UUID propertyId, UUID playerId) throws SQLException {
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM gc_properties p "
                             + "JOIN gc_claims c ON c.claim_uuid = p.claim_uuid "
                             + "WHERE p.property_uuid = ? AND c.owner_type = 'PLAYER' AND c.owner_uuid = ? LIMIT 1")) {
            statement.setString(1, propertyId.toString());
            statement.setString(2, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private PropertyAddress address(ResultSet result) throws SQLException {
        return new PropertyAddress(
                UUID.fromString(result.getString("property_uuid")),
                UUID.fromString(result.getString("claim_uuid")),
                result.getString("scope_key"),
                result.getString("road"),
                result.getString("number"),
                result.getString("unit_label"),
                result.getLong("price"),
                result.getInt("for_sale") != 0
        );
    }

    private PropertyMailbox mailbox(ResultSet result) throws SQLException {
        String road = result.getString("road");
        String number = result.getString("number");
        String unit = result.getString("unit_label");
        String display = number + " " + road;
        if (unit != null && !unit.isBlank()) {
            display += ", " + unit;
        }
        return new PropertyMailbox(
                UUID.fromString(result.getString("property_uuid")),
                UUID.fromString(result.getString("claim_uuid")),
                UUID.fromString(result.getString("world_uuid")),
                result.getString("world_name"),
                result.getInt("x"),
                result.getInt("y"),
                result.getInt("z"),
                display
        );
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
    }
}
