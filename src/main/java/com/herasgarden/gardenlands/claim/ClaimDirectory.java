package com.herasgarden.gardenlands.claim;

import com.herasgarden.gardencore.api.storage.GardenStorage;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Cached claim index used by GardenLands during the Core to Lands migration. */
public final class ClaimDirectory {
    private final GardenStorage storage;
    private final Map<UUID, LandClaimRecord> claims = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> organizationManagers = new ConcurrentHashMap<>();

    public ClaimDirectory(GardenStorage storage) {
        this.storage = storage;
    }

    public synchronized void refresh() throws SQLException {
        Map<UUID, MutableClaim> mutable = new HashMap<>();
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT claim_uuid, claim_type, claim_tag, name, owner_type, owner_uuid, parent_uuid, world_uuid, min_y, max_y, full_height "
                             + "FROM gc_claims");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                UUID id = UUID.fromString(result.getString("claim_uuid"));
                mutable.put(id, new MutableClaim(
                        id,
                        result.getString("claim_type"),
                        result.getString("claim_tag"),
                        result.getString("name"),
                        result.getString("owner_type"),
                        UUID.fromString(result.getString("owner_uuid")),
                        nullableUuid(result.getString("parent_uuid")),
                        UUID.fromString(result.getString("world_uuid")),
                        result.getInt("min_y"),
                        result.getInt("max_y"),
                        result.getInt("full_height") != 0
                ));
            }
        }

        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT claim_uuid, x, z FROM gc_claim_vertices ORDER BY claim_uuid, vertex_index");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                MutableClaim claim = mutable.get(UUID.fromString(result.getString("claim_uuid")));
                if (claim != null) {
                    claim.vertices.add(new LandClaimRecord.Point(result.getInt("x"), result.getInt("z")));
                }
            }
        }

        Map<UUID, LandClaimRecord> loaded = new ConcurrentHashMap<>();
        mutable.values().forEach(claim -> loaded.put(claim.id, claim.freeze()));
        claims.clear();
        claims.putAll(loaded);

        Map<UUID, Set<UUID>> managers = new ConcurrentHashMap<>();
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT m.org_uuid, m.player_uuid FROM gc_org_members m "
                             + "JOIN gc_org_role_permissions p ON p.org_uuid = m.org_uuid AND p.role_key = m.role_key "
                             + "WHERE p.permission = 'CLAIM_MANAGE'");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                UUID org = UUID.fromString(result.getString("org_uuid"));
                UUID player = UUID.fromString(result.getString("player_uuid"));
                managers.computeIfAbsent(org, ignored -> ConcurrentHashMap.newKeySet()).add(player);
            }
        }
        organizationManagers.clear();
        organizationManagers.putAll(managers);
    }

    public Optional<LandClaimRecord> findAt(Block block) {
        return findAllAt(block).stream()
                .min(Comparator.comparingDouble(LandClaimRecord::area));
    }

    public List<LandClaimRecord> findAllAt(Block block) {
        UUID worldId = block.getWorld().getUID();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        return claims.values().stream()
                .filter(claim -> claim.worldId().equals(worldId))
                .filter(claim -> claim.contains(x, y, z))
                .sorted(Comparator.comparingDouble(LandClaimRecord::area))
                .toList();
    }

    public boolean isSameOrAncestor(LandClaimRecord candidate, LandClaimRecord descendant) {
        if (candidate == null || descendant == null) {
            return false;
        }
        if (candidate.id().equals(descendant.id())) {
            return true;
        }
        UUID parent = descendant.parentId();
        java.util.HashSet<UUID> seen = new java.util.HashSet<>();
        while (parent != null && seen.add(parent)) {
            LandClaimRecord current = claims.get(parent);
            if (current == null) {
                return false;
            }
            if (current.id().equals(candidate.id())) {
                return true;
            }
            parent = current.parentId();
        }
        return false;
    }

    public Optional<LandClaimRecord> find(UUID claimId) {
        return Optional.ofNullable(claims.get(claimId));
    }

    public List<LandClaimRecord> all() {
        return List.copyOf(claims.values());
    }

    public boolean canManage(Player player, LandClaimRecord claim) {
        if (player.hasPermission("gardenlands.claim.admin") || player.hasPermission("gardencore.claim.admin")) {
            return true;
        }
        if ("PLAYER".equalsIgnoreCase(claim.ownerType())) {
            return claim.ownerId().equals(player.getUniqueId());
        }
        return organizationManagers.getOrDefault(claim.ownerId(), Set.of()).contains(player.getUniqueId());
    }

    private UUID nullableUuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private static final class MutableClaim {
        private final UUID id;
        private final String type;
        private final String tag;
        private final String name;
        private final String ownerType;
        private final UUID ownerId;
        private final UUID parentId;
        private final UUID worldId;
        private final int minY;
        private final int maxY;
        private final boolean fullHeight;
        private final List<LandClaimRecord.Point> vertices = new ArrayList<>();

        private MutableClaim(UUID id, String type, String tag, String name, String ownerType, UUID ownerId, UUID parentId,
                             UUID worldId, int minY, int maxY, boolean fullHeight) {
            this.id = id;
            this.type = type;
            this.tag = tag;
            this.name = name;
            this.ownerType = ownerType;
            this.ownerId = ownerId;
            this.parentId = parentId;
            this.worldId = worldId;
            this.minY = minY;
            this.maxY = maxY;
            this.fullHeight = fullHeight;
        }

        private LandClaimRecord freeze() {
            return new LandClaimRecord(id, type, tag, name, ownerType, ownerId, parentId, worldId,
                    minY, maxY, fullHeight, List.copyOf(vertices));
        }
    }
}
