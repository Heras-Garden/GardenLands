package com.herasgarden.gardenlands.citizen;

import com.herasgarden.gardencore.api.GardenPlatform;
import com.herasgarden.gardencore.api.integration.IntegrationEventType;
import com.herasgarden.gardencore.api.land.GardenCitizenshipDirectory;
import com.herasgarden.gardencore.api.storage.GardenStorage;
import com.herasgarden.gardenlands.territory.TerritoryDirectory;
import com.herasgarden.gardenlands.territory.TerritoryRecord;

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

public final class CitizenshipService implements GardenCitizenshipDirectory {
    private final GardenPlatform platform;
    private final GardenStorage storage;
    private final TerritoryDirectory territories;
    private final Map<UUID, UUID> affiliations = new ConcurrentHashMap<>();

    public CitizenshipService(GardenPlatform platform, TerritoryDirectory territories) {
        this.platform = platform;
        this.storage = platform.storage();
        this.territories = territories;
    }

    public synchronized void refresh() throws SQLException {
        Map<UUID, UUID> loaded = new ConcurrentHashMap<>();
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT player_uuid, territory_claim_uuid FROM gl_citizenships");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                UUID playerId = UUID.fromString(result.getString("player_uuid"));
                UUID territoryId = UUID.fromString(result.getString("territory_claim_uuid"));
                if (territories.findByClaim(territoryId).isPresent()) {
                    loaded.put(playerId, territoryId);
                }
            }
        }
        affiliations.clear();
        affiliations.putAll(loaded);
    }

    /** Fast cache-only lookup suitable for chat rendering. */
    public Optional<UUID> territoryClaimOfCached(UUID playerUuid) {
        return Optional.ofNullable(affiliations.get(playerUuid));
    }

    @Override
    public Optional<UUID> territoryClaimOf(UUID playerUuid) {
        return territoryClaimOfCached(playerUuid);
    }

    public Optional<TerritoryRecord> territoryOf(UUID playerUuid) {
        UUID claimId = affiliations.get(playerUuid);
        return claimId == null ? Optional.empty() : territories.findByClaim(claimId);
    }

    public TerritoryRecord join(UUID playerUuid, String territoryName) throws SQLException {
        TerritoryRecord territory = territories.findByName(territoryName)
                .orElseThrow(() -> new IllegalArgumentException("That territory does not exist."));
        long now = System.currentTimeMillis();
        try (Connection connection = storage.connection()) {
            int changed;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE gl_citizenships SET territory_claim_uuid = ?, joined_at = ? WHERE player_uuid = ?")) {
                update.setString(1, territory.claimId().toString());
                update.setLong(2, now);
                update.setString(3, playerUuid.toString());
                changed = update.executeUpdate();
            }
            if (changed == 0) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO gl_citizenships (player_uuid, territory_claim_uuid, joined_at) VALUES (?, ?, ?)")) {
                    insert.setString(1, playerUuid.toString());
                    insert.setString(2, territory.claimId().toString());
                    insert.setLong(3, now);
                    insert.executeUpdate();
                }
            }
        }
        affiliations.put(playerUuid, territory.claimId());
        platform.integrations().publish(
                IntegrationEventType.CITIZEN_AFFILIATION_CHANGED,
                "player",
                playerUuid.toString(),
                "{\"territory\":\"" + escape(territory.name()) + "\",\"territoryClaimUuid\":\""
                        + territory.claimId() + "\"}"
        );
        return territory;
    }

    @Override
    public void setCitizenship(UUID playerUuid, UUID territoryClaimId) throws SQLException {
        TerritoryRecord territory = territories.findByClaim(territoryClaimId)
                .orElseThrow(() -> new IllegalArgumentException("That territory does not exist."));
        join(playerUuid, territory.name());
    }

    @Override
    public boolean clearCitizenship(UUID playerUuid) throws SQLException {
        return clear(playerUuid, true);
    }

    public boolean leave(UUID playerUuid) throws SQLException {
        return clearCitizenship(playerUuid);
    }

    @Override
    public List<UUID> citizens(UUID territoryClaimId) {
        List<UUID> result = new ArrayList<>();
        affiliations.forEach((player, territory) -> {
            if (territory.equals(territoryClaimId)) {
                result.add(player);
            }
        });
        return List.copyOf(result);
    }

    public int clearTerritory(UUID territoryClaimId) throws SQLException {
        int changed;
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM gl_citizenships WHERE territory_claim_uuid = ?")) {
            statement.setString(1, territoryClaimId.toString());
            changed = statement.executeUpdate();
        }
        affiliations.entrySet().removeIf(entry -> entry.getValue().equals(territoryClaimId));
        return changed;
    }

    private boolean clear(UUID playerUuid, boolean publish) throws SQLException {
        int changed;
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM gl_citizenships WHERE player_uuid = ?")) {
            statement.setString(1, playerUuid.toString());
            changed = statement.executeUpdate();
        }
        if (changed > 0) {
            affiliations.remove(playerUuid);
        }
        if (changed > 0 && publish) {
            platform.integrations().publish(
                    IntegrationEventType.CITIZEN_AFFILIATION_CHANGED,
                    "player",
                    playerUuid.toString(),
                    "{\"territory\":null}"
            );
        }
        return changed > 0;
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
