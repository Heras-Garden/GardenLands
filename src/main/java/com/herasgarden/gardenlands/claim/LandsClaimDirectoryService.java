package com.herasgarden.gardenlands.claim;

import com.herasgarden.gardencore.api.claim.ClaimDirectoryService;
import com.herasgarden.gardencore.api.claim.ClaimSummary;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** GardenLands-owned public claim metadata adapter. */
public final class LandsClaimDirectoryService implements ClaimDirectoryService {
    private final ClaimDirectory claims;

    public LandsClaimDirectoryService(ClaimDirectory claims) {
        this.claims = claims;
    }

    @Override
    public Optional<ClaimSummary> find(UUID claimId) {
        return claims.find(claimId).map(this::summary);
    }

    @Override
    public void refresh() throws SQLException {
        claims.refresh();
    }

    private ClaimSummary summary(LandClaimRecord claim) {
        return new ClaimSummary(
                claim.id(),
                claim.type(),
                claim.tag(),
                claim.name(),
                claim.ownerType(),
                claim.ownerId(),
                claim.parentId(),
                claim.worldId(),
                claim.minY(),
                claim.maxY(),
                claim.fullHeight()
        );
    }
}
