package com.herasgarden.gardenlands.claim;

import com.herasgarden.gardencore.api.land.LandAccessService;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

public final class LandsAccessService implements LandAccessService {
    private final ClaimDirectory claims;

    public LandsAccessService(ClaimDirectory claims) {
        this.claims = claims;
    }

    @Override
    public boolean canManage(Player player, Block block) {
        return claims.findAt(block).map(claim -> claims.canManage(player, claim)).orElse(false);
    }

    @Override
    public Optional<UUID> claimIdAt(Block block) {
        return claims.findAt(block).map(LandClaimRecord::id);
    }

    @Override
    public Optional<String> claimTypeAt(Block block) {
        return claims.findAt(block).map(LandClaimRecord::type);
    }
}
