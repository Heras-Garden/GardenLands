package com.herasgarden.gardenlands.territory;

import com.herasgarden.gardencore.api.storage.GardenStorage;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gives every territory a stable Private Use Area glyph and exports a normalized
 * banner manifest for the Garden resource-pack builder.
 */
public final class TerritoryGlyphService {
    public static final Key FONT = Key.key("herasgarden", "territory_flags");
    private static final int FIRST_CODEPOINT = 0xE100;
    private static final int LAST_CODEPOINT = 0xF8FF;

    private final JavaPlugin plugin;
    private final GardenStorage storage;
    private final TerritoryDirectory territories;
    private final Map<UUID, Integer> codepoints = new ConcurrentHashMap<>();

    public TerritoryGlyphService(JavaPlugin plugin, GardenStorage storage, TerritoryDirectory territories) {
        this.plugin = plugin;
        this.storage = storage;
        this.territories = territories;
    }

    public synchronized void refresh() throws SQLException {
        Map<UUID, Integer> loaded = new HashMap<>();
        Map<UUID, String> fingerprints = new HashMap<>();
        Set<Integer> used = new HashSet<>();

        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT territory_claim_uuid, codepoint, banner_fingerprint FROM gl_territory_glyphs");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                UUID claimId = UUID.fromString(result.getString("territory_claim_uuid"));
                int codepoint = result.getInt("codepoint");
                loaded.put(claimId, codepoint);
                fingerprints.put(claimId, result.getString("banner_fingerprint"));
                used.add(codepoint);
            }
        }

        for (TerritoryRecord territory : territories.list()) {
            String fingerprint = fingerprint(territory.flagData());
            Integer codepoint = loaded.get(territory.claimId());
            if (codepoint == null) {
                codepoint = nextAvailable(used);
                insert(territory.claimId(), codepoint, fingerprint);
                loaded.put(territory.claimId(), codepoint);
                fingerprints.put(territory.claimId(), fingerprint);
                used.add(codepoint);
            } else if (!fingerprint.equals(fingerprints.get(territory.claimId()))) {
                updateFingerprint(territory.claimId(), fingerprint);
            }
        }

        // Deleted territory rows are no longer useful and their codepoints may be
        // reused. Existing territories retain their original codepoint forever.
        Set<UUID> active = new HashSet<>();
        territories.list().forEach(territory -> active.add(territory.claimId()));
        for (UUID claimId : new ArrayList<>(loaded.keySet())) {
            if (!active.contains(claimId)) {
                delete(claimId);
                loaded.remove(claimId);
            }
        }

        codepoints.clear();
        codepoints.putAll(loaded);
        exportManifest();
    }

    public Optional<Integer> codepoint(UUID territoryClaimId) {
        return Optional.ofNullable(codepoints.get(territoryClaimId));
    }

    public Optional<Component> component(UUID territoryClaimId) {
        TerritoryRecord territory = territories.findByClaim(territoryClaimId).orElse(null);
        if (territory == null) {
            return Optional.empty();
        }

        Component hover = Component.text(
                territory.name() + "\nCitizen of " + territory.name(), NamedTextColor.GRAY);

        // The generated custom-font glyph contains the full banner pattern. Until
        // that generated pack is installed on the client, use Minecraft's native
        // banner item sprite so the territory badge is always visible in chat.
        if (plugin.getConfig().getBoolean("chat.use-generated-flag-font", false)) {
            Integer codepoint = codepoints.get(territoryClaimId);
            if (codepoint != null) {
                String glyph = new String(Character.toChars(codepoint));
                return Optional.of(Component.text(glyph)
                        .font(FONT)
                        .color(NamedTextColor.WHITE)
                        .hoverEvent(HoverEvent.showText(hover)));
            }
        }

        try {
            TerritoryFlagDescriptor descriptor = BannerFlagCodec.decode(territory.flagData());
            String base = descriptor.baseColor().toLowerCase(Locale.ROOT);
            Component sprite = Component.object()
                    .contents(ObjectContents.sprite(
                            Key.key("minecraft", "items"),
                            Key.key("minecraft", "item/" + base + "_banner")))
                    .fallback(Component.text("⚑", NamedTextColor.WHITE))
                    .build();
            return Optional.of(sprite.hoverEvent(HoverEvent.showText(hover)));
        } catch (IllegalArgumentException exception) {
            return Optional.of(Component.text("⚑", NamedTextColor.WHITE)
                    .hoverEvent(HoverEvent.showText(hover)));
        }
    }

    public Path manifestPath() {
        return plugin.getDataFolder().toPath().resolve("territory-flags.json");
    }

    public synchronized void exportManifest() {
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            List<TerritoryRecord> sorted = new ArrayList<>(territories.list());
            sorted.sort(Comparator.comparing(TerritoryRecord::name, String.CASE_INSENSITIVE_ORDER));

            StringBuilder json = new StringBuilder();
            json.append("{\n  \"font\": \"herasgarden:territory_flags\",\n  \"territories\": [");
            boolean firstTerritory = true;
            for (TerritoryRecord territory : sorted) {
                Integer codepoint = codepoints.get(territory.claimId());
                if (codepoint == null) {
                    continue;
                }
                TerritoryFlagDescriptor descriptor;
                try {
                    descriptor = BannerFlagCodec.decode(territory.flagData());
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().warning("Could not export flag for " + territory.name() + ": " + exception.getMessage());
                    continue;
                }
                if (!firstTerritory) {
                    json.append(',');
                }
                firstTerritory = false;
                json.append("\n    {\n")
                        .append("      \"claim_uuid\": \"").append(territory.claimId()).append("\",\n")
                        .append("      \"name\": \"").append(json(territory.name())).append("\",\n")
                        .append("      \"codepoint\": ").append(codepoint).append(",\n")
                        .append("      \"hex\": \"").append(String.format("%04X", codepoint)).append("\",\n")
                        .append("      \"base_color\": \"").append(json(descriptor.baseColor())).append("\",\n")
                        .append("      \"patterns\": [");
                boolean firstLayer = true;
                for (TerritoryFlagDescriptor.Layer layer : descriptor.patterns()) {
                    if (!firstLayer) {
                        json.append(',');
                    }
                    firstLayer = false;
                    json.append("{\"color\":\"").append(json(layer.color()))
                            .append("\",\"pattern\":\"").append(json(layer.pattern())).append("\"}");
                }
                json.append("]\n    }");
            }
            json.append("\n  ]\n}\n");
            Files.writeString(manifestPath(), json.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not write territory flag manifest: " + exception.getMessage());
        }
    }

    private int nextAvailable(Set<Integer> used) {
        for (int codepoint = FIRST_CODEPOINT; codepoint <= LAST_CODEPOINT; codepoint++) {
            if (!used.contains(codepoint)) {
                return codepoint;
            }
        }
        throw new IllegalStateException("No territory flag glyph slots remain.");
    }

    private void insert(UUID claimId, int codepoint, String fingerprint) throws SQLException {
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO gl_territory_glyphs (territory_claim_uuid, codepoint, banner_fingerprint, updated_at) "
                             + "VALUES (?, ?, ?, ?)")) {
            statement.setString(1, claimId.toString());
            statement.setInt(2, codepoint);
            statement.setString(3, fingerprint);
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private void updateFingerprint(UUID claimId, String fingerprint) throws SQLException {
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gl_territory_glyphs SET banner_fingerprint = ?, updated_at = ? "
                             + "WHERE territory_claim_uuid = ?")) {
            statement.setString(1, fingerprint);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, claimId.toString());
            statement.executeUpdate();
        }
    }

    private void delete(UUID claimId) throws SQLException {
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM gl_territory_glyphs WHERE territory_claim_uuid = ?")) {
            statement.setString(1, claimId.toString());
            statement.executeUpdate();
        }
    }

    private String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String json(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
