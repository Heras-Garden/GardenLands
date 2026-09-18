package com.herasgarden.gardenlands.territory;

import java.util.UUID;

public record TerritoryRecord(
        UUID claimId,
        String name,
        String nameKey,
        String flagData
) {
}
