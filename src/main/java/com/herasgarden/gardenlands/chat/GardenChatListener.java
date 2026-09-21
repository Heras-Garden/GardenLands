package com.herasgarden.gardenlands.chat;

import com.herasgarden.gardencore.api.cosmetic.CosmeticChatProfile;
import com.herasgarden.gardencore.api.cosmetic.CosmeticProfileService;
import com.herasgarden.gardenlands.citizen.CitizenshipService;
import com.herasgarden.gardenlands.territory.TerritoryGlyphService;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

/** Renders Garden chat without doing database work on the async chat thread. */
public final class GardenChatListener implements Listener, ChatRenderer {
    private final CitizenshipService citizenship;
    private final TerritoryGlyphService glyphs;
    private final CosmeticProfileService cosmetics;
    private final String separator;
    private final TextColor defaultNameColor;
    private final TextColor adminNameColor;
    private final String ownerName;
    private final TextColor ownerNameColor;
    private final TextColor donorTagColor;

    public GardenChatListener(
            CitizenshipService citizenship,
            TerritoryGlyphService glyphs,
            CosmeticProfileService cosmetics,
            String separator,
            String defaultNameColor,
            String adminNameColor,
            String ownerName,
            String ownerNameColor,
            String donorTagColor
    ) {
        this.citizenship = citizenship;
        this.glyphs = glyphs;
        this.cosmetics = cosmetics;
        this.separator = separator == null || separator.isBlank() ? ">>" : separator.trim();
        this.defaultNameColor = color(defaultNameColor, 0xE7E3E5);
        this.adminNameColor = color(adminNameColor, 0xA78BFA);
        this.ownerName = ownerName == null ? "" : ownerName.trim();
        this.ownerNameColor = color(ownerNameColor, 0xF472B6);
        this.donorTagColor = color(donorTagColor, 0xD4AF37);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        event.renderer(this);
    }

    @Override
    public Component render(Player source, Component sourceDisplayName, Component message, Audience viewer) {
        Component head = Component.object()
                .contents(source.asObjectContents())
                .fallback(Component.text("■", NamedTextColor.GRAY))
                .build();

        Component prefix = head.append(Component.space());
        UUID territoryClaim = citizenship.territoryClaimOfCached(source.getUniqueId()).orElse(null);
        if (territoryClaim != null) {
            Component flag = glyphs.component(territoryClaim).orElse(null);
            if (flag != null) {
                prefix = prefix.append(flag).append(Component.space());
            }
        }

        CosmeticChatProfile profile = cosmetics == null
                ? CosmeticChatProfile.empty()
                : cosmetics.chatProfile(source.getUniqueId());

        TextColor nameColor = nameColor(source, profile);
        Component displayName = sourceDisplayName.color(nameColor);
        if (profile.fontKey() != null && !profile.fontKey().isBlank()) {
            try {
                displayName = displayName.font(Key.key(profile.fontKey()));
            } catch (IllegalArgumentException ignored) {
            }
        }

        return prefix
                .append(displayName)
                .append(Component.text(" " + separator + " ", NamedTextColor.GRAY))
                .append(message);
    }

    private TextColor nameColor(Player source, CosmeticChatProfile profile) {
        if (!ownerName.isBlank() && source.getName().equalsIgnoreCase(ownerName)) {
            return ownerNameColor;
        }
        if (source.hasPermission("gardenlands.chat.admin-color")) {
            return adminNameColor;
        }
        if (profile.nameHex() != null) {
            return color(profile.nameHex(), defaultNameColor.value());
        }
        return defaultNameColor;
    }

    private static TextColor color(String value, int fallback) {
        if (value == null) return TextColor.color(fallback);
        String clean = value.trim();
        if (clean.startsWith("#")) clean = clean.substring(1);
        try {
            if (clean.matches("[0-9A-Fa-f]{6}")) {
                return TextColor.color(Integer.parseInt(clean, 16));
            }
        } catch (NumberFormatException ignored) {
        }
        return TextColor.color(fallback);
    }
}
