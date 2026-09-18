package com.herasgarden.gardenlands.door;

import com.herasgarden.gardencore.api.permission.AccessDecision;
import com.herasgarden.gardencore.api.permission.ClaimAccessAction;
import com.herasgarden.gardencore.api.permission.ClaimAccessPolicy;
import com.herasgarden.gardencore.api.permission.DoorAccessAction;
import com.herasgarden.gardencore.api.permission.DoorAccessPolicy;
import com.herasgarden.gardencore.api.storage.GardenStorage;
import com.herasgarden.gardenlands.claim.ClaimDirectory;
import com.herasgarden.gardenlands.claim.LandClaimRecord;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DoorPermissionService implements DoorAccessPolicy {
    private final GardenStorage storage;
    private final ClaimDirectory claims;
    private final Map<DoorKey, DoorOverride> overrides = new ConcurrentHashMap<>();

    public DoorPermissionService(GardenStorage storage, ClaimDirectory claims) {
        this.storage = storage;
        this.claims = claims;
    }

    public synchronized void refresh() throws SQLException {
        Map<DoorKey, DoorOverride> loaded = new ConcurrentHashMap<>();
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT world_uuid, x, y, z, claim_uuid, use_value, break_value FROM gl_door_permissions");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                DoorKey key = new DoorKey(
                        UUID.fromString(result.getString("world_uuid")),
                        result.getInt("x"), result.getInt("y"), result.getInt("z"));
                loaded.put(key, new DoorOverride(
                        UUID.fromString(result.getString("claim_uuid")),
                        parse(result.getString("use_value")),
                        parse(result.getString("break_value"))
                ));
            }
        }
        overrides.clear();
        overrides.putAll(loaded);
    }

    public Optional<DoorOverride> get(Block block) {
        if (!DoorKey.supported(block)) {
            return Optional.empty();
        }
        return Optional.ofNullable(overrides.get(DoorKey.of(block)));
    }

    public DoorOverride effectiveRecord(Block block) {
        Block canonical = DoorKey.canonicalBlock(block);
        LandClaimRecord claim = claims.findAt(canonical)
                .orElseThrow(() -> new IllegalArgumentException("That door is not inside a Garden claim."));
        return get(canonical).filter(value -> value.claimId().equals(claim.id()))
                .orElseGet(() -> DoorOverride.inherited(claim.id()));
    }

    public DoorOverride set(Player editor, Block block, DoorAccessAction action, AccessDecision value)
            throws SQLException {
        if (!DoorKey.supported(block)) {
            throw new IllegalArgumentException("Look at a door or trapdoor first.");
        }
        Block canonical = DoorKey.canonicalBlock(block);
        LandClaimRecord claim = claims.findAt(canonical)
                .orElseThrow(() -> new IllegalArgumentException("That door is not inside a Garden claim."));
        if (!claims.canManage(editor, claim)) {
            throw new IllegalArgumentException("You do not manage this claim.");
        }

        DoorOverride current = effectiveRecord(canonical);
        DoorOverride updated = switch (action) {
            case USE -> new DoorOverride(claim.id(), value, current.breakAccess());
            case BREAK -> new DoorOverride(claim.id(), current.use(), value);
        };
        save(editor.getUniqueId(), canonical, updated);
        return updated;
    }

    public void reset(Player editor, Block block) throws SQLException {
        if (!DoorKey.supported(block)) {
            throw new IllegalArgumentException("Look at a door or trapdoor first.");
        }
        Block canonical = DoorKey.canonicalBlock(block);
        LandClaimRecord claim = claims.findAt(canonical)
                .orElseThrow(() -> new IllegalArgumentException("That door is not inside a Garden claim."));
        if (!claims.canManage(editor, claim)) {
            throw new IllegalArgumentException("You do not manage this claim.");
        }
        remove(canonical);
    }

    public void remove(Block block) throws SQLException {
        if (!DoorKey.supported(block)) {
            return;
        }
        DoorKey key = DoorKey.of(block);
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM gl_door_permissions WHERE world_uuid = ? AND x = ? AND y = ? AND z = ?")) {
            statement.setString(1, key.worldId().toString());
            statement.setInt(2, key.x());
            statement.setInt(3, key.y());
            statement.setInt(4, key.z());
            statement.executeUpdate();
        }
        overrides.remove(key);
    }

    @Override
    public AccessDecision decide(Player player, Block block, DoorAccessAction action) {
        if (!DoorKey.supported(block)) {
            return AccessDecision.INHERIT;
        }
        Block canonical = DoorKey.canonicalBlock(block);
        LandClaimRecord claim = claims.findAt(canonical).orElse(null);
        if (claim == null || claims.canManage(player, claim)) {
            return AccessDecision.INHERIT;
        }
        AccessDecision delegated = claimDecision(player, claim.id(), action);
        if (delegated != AccessDecision.INHERIT) {
            return delegated;
        }

        DoorOverride value = overrides.get(DoorKey.of(canonical));
        if (value == null || !value.claimId().equals(claim.id())) {
            return AccessDecision.INHERIT;
        }
        return action == DoorAccessAction.USE ? value.use() : value.breakAccess();
    }

    private AccessDecision claimDecision(Player player, UUID claimId, DoorAccessAction action) {
        RegisteredServiceProvider<ClaimAccessPolicy> registration =
                Bukkit.getServicesManager().getRegistration(ClaimAccessPolicy.class);
        if (registration == null || registration.getProvider() == null) {
            return AccessDecision.INHERIT;
        }
        ClaimAccessAction claimAction = action == DoorAccessAction.USE
                ? ClaimAccessAction.DOOR_USE : ClaimAccessAction.BREAK;
        try {
            AccessDecision decision = registration.getProvider().decide(player, claimId, claimAction);
            return decision == null ? AccessDecision.INHERIT : decision;
        } catch (RuntimeException ignored) {
            return AccessDecision.INHERIT;
        }
    }

    private void save(UUID editor, Block block, DoorOverride value) throws SQLException {
        DoorKey key = DoorKey.of(block);
        if (value.fullyInherited()) {
            remove(block);
            return;
        }
        long now = System.currentTimeMillis();
        try (Connection connection = storage.connection()) {
            int changed;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE gl_door_permissions SET claim_uuid = ?, use_value = ?, break_value = ?, "
                            + "updated_by = ?, updated_at = ? WHERE world_uuid = ? AND x = ? AND y = ? AND z = ?")) {
                update.setString(1, value.claimId().toString());
                update.setString(2, value.use().name());
                update.setString(3, value.breakAccess().name());
                update.setString(4, editor.toString());
                update.setLong(5, now);
                update.setString(6, key.worldId().toString());
                update.setInt(7, key.x());
                update.setInt(8, key.y());
                update.setInt(9, key.z());
                changed = update.executeUpdate();
            }
            if (changed == 0) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO gl_door_permissions "
                                + "(world_uuid, x, y, z, claim_uuid, use_value, break_value, updated_by, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    insert.setString(1, key.worldId().toString());
                    insert.setInt(2, key.x());
                    insert.setInt(3, key.y());
                    insert.setInt(4, key.z());
                    insert.setString(5, value.claimId().toString());
                    insert.setString(6, value.use().name());
                    insert.setString(7, value.breakAccess().name());
                    insert.setString(8, editor.toString());
                    insert.setLong(9, now);
                    insert.executeUpdate();
                }
            }
        }
        overrides.put(key, value);
    }

    private AccessDecision parse(String value) {
        try {
            return AccessDecision.valueOf(value);
        } catch (Exception ignored) {
            return AccessDecision.INHERIT;
        }
    }
}
