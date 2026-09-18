package com.herasgarden.gardenlands.territory;

import java.util.List;

public record TerritoryFlagDescriptor(
        String baseColor,
        List<Layer> patterns
) {
    public record Layer(String color, String pattern) {
    }
}
