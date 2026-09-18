package com.herasgarden.gardenlands.territory;

import org.bukkit.block.banner.Pattern;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BannerFlagCodec {
    private BannerFlagCodec() {
    }

    public static TerritoryFlagDescriptor decode(String flagData) {
        if (flagData == null || flagData.isBlank()) {
            throw new IllegalArgumentException("Territory flag data is empty.");
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(flagData);
        } catch (InvalidConfigurationException exception) {
            throw new IllegalArgumentException("Territory flag data is invalid.", exception);
        }
        ItemStack flag = yaml.getItemStack("flag");
        if (flag == null || !flag.getType().name().endsWith("_BANNER") || !(flag.getItemMeta() instanceof BannerMeta meta)) {
            throw new IllegalArgumentException("Territory flag data does not contain a banner.");
        }

        String material = flag.getType().name();
        String base = material.substring(0, material.length() - "_BANNER".length()).toLowerCase(Locale.ROOT);
        List<TerritoryFlagDescriptor.Layer> layers = new ArrayList<>();
        for (Pattern pattern : meta.getPatterns()) {
            String patternKey = pattern.getPattern().getKey().getKey();
            layers.add(new TerritoryFlagDescriptor.Layer(
                    pattern.getColor().name().toLowerCase(Locale.ROOT),
                    patternKey
            ));
        }
        return new TerritoryFlagDescriptor(base, List.copyOf(layers));
    }
}
