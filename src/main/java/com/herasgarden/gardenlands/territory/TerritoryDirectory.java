package com.herasgarden.gardenlands.territory;

import com.herasgarden.gardencore.api.storage.GardenStorage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached transitional territory directory reading the existing gc_territories
 * table. Chat and command lookups use the cache, not a database query on every
 * message.
 */
public final class TerritoryDirectory {
    private final GardenStorage storage;
    private final Map<String, TerritoryRecord> byName = new ConcurrentHashMap<>();
    private final Map<UUID, TerritoryRecord> byClaim = new ConcurrentHashMap<>();

    public TerritoryDirectory(GardenStorage storage) {
        this.storage = storage;
    }

    public synchronized void refresh() throws SQLException {
        Map<String, TerritoryRecord> names = new ConcurrentHashMap<>();
        Map<UUID, TerritoryRecord> claims = new ConcurrentHashMap<>();
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT claim_uuid, name, name_key, flag_data FROM gc_territories");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                TerritoryRecord territory = read(result);
                names.put(territory.nameKey(), territory);
                claims.put(territory.claimId(), territory);
            }
        }
        byName.clear();
        byName.putAll(names);
        byClaim.clear();
        byClaim.putAll(claims);
    }

    public Optional<TerritoryRecord> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(normalize(name)));
    }

    public Optional<TerritoryRecord> findByClaim(UUID claimId) {
        return claimId == null ? Optional.empty() : Optional.ofNullable(byClaim.get(claimId));
    }

    public List<TerritoryRecord> list() {
        List<TerritoryRecord> territories = new ArrayList<>(byClaim.values());
        territories.sort(Comparator.comparing(TerritoryRecord::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(territories);
    }

    private TerritoryRecord read(ResultSet result) throws SQLException {
        return new TerritoryRecord(
                UUID.fromString(result.getString("claim_uuid")),
                result.getString("name"),
                result.getString("name_key"),
                result.getString("flag_data")
        );
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
    }
}
