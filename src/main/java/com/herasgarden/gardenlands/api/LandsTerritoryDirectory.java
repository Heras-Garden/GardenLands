package com.herasgarden.gardenlands.api;

import com.herasgarden.gardencore.api.land.GardenTerritoryDirectory;
import com.herasgarden.gardencore.api.land.TerritorySummary;
import com.herasgarden.gardenlands.claim.ClaimDirectory;
import com.herasgarden.gardenlands.claim.LandClaimRecord;
import com.herasgarden.gardenlands.territory.TerritoryDirectory;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class LandsTerritoryDirectory implements GardenTerritoryDirectory {
    private final TerritoryDirectory territories;
    private final ClaimDirectory claims;

    public LandsTerritoryDirectory(TerritoryDirectory territories, ClaimDirectory claims) {
        this.territories = territories;
        this.claims = claims;
    }

    @Override
    public Optional<TerritorySummary> findByName(String name) {
        return territories.findByName(name).map(value -> new TerritorySummary(value.claimId(), value.name()));
    }

    @Override
    public Optional<TerritorySummary> findByClaim(UUID claimId) {
        return territories.findByClaim(claimId).map(value -> new TerritorySummary(value.claimId(), value.name()));
    }

    @Override
    public List<TerritorySummary> list() {
        return territories.list().stream()
                .map(value -> new TerritorySummary(value.claimId(), value.name()))
                .toList();
    }

    @Override
    public boolean canManage(Player player, UUID territoryClaimId) {
        if (player == null || territoryClaimId == null) {
            return false;
        }
        LandClaimRecord claim = claims.find(territoryClaimId).orElse(null);
        return claim != null
                && "TERRITORY".equalsIgnoreCase(claim.type())
                && claims.canManage(player, claim);
    }
}
