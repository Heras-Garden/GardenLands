package com.herasgarden.gardenlands.container;

import com.herasgarden.gardencore.api.permission.AccessDecision;
import com.herasgarden.gardencore.api.permission.ClaimAccessAction;
import com.herasgarden.gardencore.api.permission.ClaimAccessPolicy;
import com.herasgarden.gardencore.api.permission.ContainerAccessAction;
import com.herasgarden.gardencore.api.permission.ContainerAccessPolicy;
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

public final class ContainerPermissionService implements ContainerAccessPolicy {
    private final GardenStorage storage;
    private final ClaimDirectory claims;
    private final Map<ContainerKey, ContainerOverride> overrides = new ConcurrentHashMap<>();
    private final Map<ContainerKey, UUID> mailboxClaims = new ConcurrentHashMap<>();

    public ContainerPermissionService(GardenStorage storage, ClaimDirectory claims) {
        this.storage = storage;
        this.claims = claims;
    }

    public synchronized void refresh() throws SQLException {
        Map<ContainerKey, ContainerOverride> loaded = new ConcurrentHashMap<>();
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT world_uuid, x, y, z, claim_uuid, open_value, insert_value, take_value, break_value "
                             + "FROM gl_container_permissions");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                ContainerKey key = new ContainerKey(
                        UUID.fromString(result.getString("world_uuid")),
                        result.getInt("x"), result.getInt("y"), result.getInt("z"));
                ContainerOverride value = new ContainerOverride(
                        UUID.fromString(result.getString("claim_uuid")),
                        parse(result.getString("open_value")),
                        parse(result.getString("insert_value")),
                        parse(result.getString("take_value")),
                        parse(result.getString("break_value"))
                );
                loaded.put(key, value);
            }
        }
        Map<ContainerKey, UUID> loadedMailboxes = new ConcurrentHashMap<>();
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT m.world_uuid, m.x, m.y, m.z, p.claim_uuid "
                             + "FROM gc_property_mailboxes m "
                             + "JOIN gc_properties p ON p.property_uuid = m.property_uuid");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                loadedMailboxes.put(new ContainerKey(
                                UUID.fromString(result.getString("world_uuid")),
                                result.getInt("x"), result.getInt("y"), result.getInt("z")),
                        UUID.fromString(result.getString("claim_uuid")));
            }
        }

        overrides.clear();
        overrides.putAll(loaded);
        mailboxClaims.clear();
        mailboxClaims.putAll(loadedMailboxes);
    }

    public Optional<ContainerOverride> get(Block block) {
        if (!ContainerKey.supported(block)) {
            return Optional.empty();
        }
        return Optional.ofNullable(overrides.get(ContainerKey.of(block)));
    }

    public void noteMailbox(Block block, UUID claimId) {
        if (!ContainerKey.supported(block) || claimId == null) {
            return;
        }
        mailboxClaims.put(
                new ContainerKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()),
                claimId
        );
    }

    public void forgetMailbox(UUID worldId, int x, int y, int z) {
        if (worldId == null) {
            return;
        }
        mailboxClaims.remove(new ContainerKey(worldId, x, y, z));
    }

    public boolean isMailbox(Block block) {
        return mailboxClaim(block).isPresent();
    }

    public Optional<UUID> mailboxClaim(Block block) {
        if (!ContainerKey.supported(block)) {
            return Optional.empty();
        }
        for (ContainerKey key : ContainerKey.members(block)) {
            UUID claimId = mailboxClaims.get(key);
            if (claimId != null) {
                return Optional.of(claimId);
            }
        }
        return Optional.empty();
    }

    public ContainerOverride effectiveRecord(Block block) {
        LandClaimRecord claim = claims.findAt(block)
                .orElseThrow(() -> new IllegalArgumentException("That container is not inside a Garden claim."));
        return get(block).filter(value -> value.claimId().equals(claim.id()))
                .orElseGet(() -> ContainerOverride.inherited(claim.id()));
    }

    public ContainerOverride set(Player editor, Block block, ContainerAccessAction action, AccessDecision value)
            throws SQLException {
        if (!ContainerKey.supported(block)) {
            throw new IllegalArgumentException("Look at a chest, trapped chest, or barrel first.");
        }
        LandClaimRecord claim = claims.findAt(block)
                .orElseThrow(() -> new IllegalArgumentException("That container is not inside a Garden claim."));
        if (!claims.canManage(editor, claim)) {
            throw new IllegalArgumentException("You do not manage this claim.");
        }
        if (isMailbox(block)) {
            throw new IllegalArgumentException("Mailbox access is automatic and cannot be changed here.");
        }

        ContainerOverride current = effectiveRecord(block);
        ContainerOverride updated = switch (action) {
            case OPEN -> new ContainerOverride(claim.id(), value, current.insert(), current.take(), current.breakAccess());
            case INSERT -> new ContainerOverride(claim.id(), current.open(), value, current.take(), current.breakAccess());
            case TAKE -> new ContainerOverride(claim.id(), current.open(), current.insert(), value, current.breakAccess());
            case BREAK -> new ContainerOverride(claim.id(), current.open(), current.insert(), current.take(), value);
        };
        save(editor.getUniqueId(), block, updated);
        return updated;
    }

    public void reset(Player editor, Block block) throws SQLException {
        if (!ContainerKey.supported(block)) {
            throw new IllegalArgumentException("Look at a chest, trapped chest, or barrel first.");
        }
        LandClaimRecord claim = claims.findAt(block)
                .orElseThrow(() -> new IllegalArgumentException("That container is not inside a Garden claim."));
        if (!claims.canManage(editor, claim)) {
            throw new IllegalArgumentException("You do not manage this claim.");
        }
        if (isMailbox(block)) {
            throw new IllegalArgumentException("Mailbox access is automatic and cannot be reset.");
        }
        remove(block);
    }

    public void remove(Block block) throws SQLException {
        if (!ContainerKey.supported(block)) {
            return;
        }
        ContainerKey key = ContainerKey.of(block);
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM gl_container_permissions WHERE world_uuid = ? AND x = ? AND y = ? AND z = ?")) {
            statement.setString(1, key.worldId().toString());
            statement.setInt(2, key.x());
            statement.setInt(3, key.y());
            statement.setInt(4, key.z());
            statement.executeUpdate();
        }
        overrides.remove(key);
    }

    @Override
    public AccessDecision decide(Player player, Block block, ContainerAccessAction action) {
        if (!ContainerKey.supported(block)) {
            return AccessDecision.INHERIT;
        }

        UUID mailboxClaimId = mailboxClaim(block).orElse(null);
        if (mailboxClaimId != null) {
            LandClaimRecord mailboxClaim = claims.find(mailboxClaimId).orElse(null);
            boolean admin = player.hasPermission("gardenlands.claim.admin")
                    || player.hasPermission("gardencore.claim.admin");
            if (admin) {
                return AccessDecision.ALLOW;
            }
            if (mailboxClaim != null && claims.canManage(player, mailboxClaim)) {
                return action == ContainerAccessAction.BREAK ? AccessDecision.DENY : AccessDecision.ALLOW;
            }
            if (player.hasPermission("gardenlands.mailbox.mailman")
                    || player.hasPermission("gardenpost.mailman")) {
                return action == ContainerAccessAction.BREAK ? AccessDecision.DENY : AccessDecision.ALLOW;
            }
            return AccessDecision.DENY;
        }

        LandClaimRecord claim = claims.findAt(block).orElse(null);
        if (claim == null || claims.canManage(player, claim)) {
            return AccessDecision.INHERIT;
        }
        AccessDecision delegated = claimDecision(player, claim.id(), action);
        if (delegated != AccessDecision.INHERIT) {
            return delegated;
        }

        ContainerOverride value = overrides.get(ContainerKey.of(block));
        if (value == null || !value.claimId().equals(claim.id())) {
            return AccessDecision.INHERIT;
        }
        return switch (action) {
            case OPEN -> value.open();
            case INSERT -> value.insert();
            case TAKE -> value.take();
            case BREAK -> value.breakAccess();
        };
    }

    private AccessDecision claimDecision(Player player, UUID claimId, ContainerAccessAction action) {
        RegisteredServiceProvider<ClaimAccessPolicy> registration =
                Bukkit.getServicesManager().getRegistration(ClaimAccessPolicy.class);
        if (registration == null || registration.getProvider() == null) {
            return AccessDecision.INHERIT;
        }
        ClaimAccessAction claimAction = switch (action) {
            case OPEN -> ClaimAccessAction.CONTAINER_OPEN;
            case INSERT -> ClaimAccessAction.CONTAINER_INSERT;
            case TAKE -> ClaimAccessAction.CONTAINER_TAKE;
            case BREAK -> ClaimAccessAction.CONTAINER_BREAK;
        };
        try {
            AccessDecision decision = registration.getProvider().decide(player, claimId, claimAction);
            return decision == null ? AccessDecision.INHERIT : decision;
        } catch (RuntimeException ignored) {
            return AccessDecision.INHERIT;
        }
    }

    private void save(UUID editor, Block block, ContainerOverride value) throws SQLException {
        ContainerKey key = ContainerKey.of(block);
        if (value.fullyInherited()) {
            remove(block);
            return;
        }
        long now = System.currentTimeMillis();
        try (Connection connection = storage.connection()) {
            int changed;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE gl_container_permissions SET claim_uuid = ?, open_value = ?, insert_value = ?, "
                            + "take_value = ?, break_value = ?, updated_by = ?, updated_at = ? "
                            + "WHERE world_uuid = ? AND x = ? AND y = ? AND z = ?")) {
                update.setString(1, value.claimId().toString());
                update.setString(2, value.open().name());
                update.setString(3, value.insert().name());
                update.setString(4, value.take().name());
                update.setString(5, value.breakAccess().name());
                update.setString(6, editor.toString());
                update.setLong(7, now);
                update.setString(8, key.worldId().toString());
                update.setInt(9, key.x());
                update.setInt(10, key.y());
                update.setInt(11, key.z());
                changed = update.executeUpdate();
            }
            if (changed == 0) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO gl_container_permissions "
                                + "(world_uuid, x, y, z, claim_uuid, open_value, insert_value, take_value, break_value, updated_by, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    insert.setString(1, key.worldId().toString());
                    insert.setInt(2, key.x());
                    insert.setInt(3, key.y());
                    insert.setInt(4, key.z());
                    insert.setString(5, value.claimId().toString());
                    insert.setString(6, value.open().name());
                    insert.setString(7, value.insert().name());
                    insert.setString(8, value.take().name());
                    insert.setString(9, value.breakAccess().name());
                    insert.setString(10, editor.toString());
                    insert.setLong(11, now);
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
