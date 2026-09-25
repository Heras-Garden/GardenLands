package com.herasgarden.gardenlands.property;

import com.herasgarden.gardencore.api.land.PropertyHabitabilityService;
import com.herasgarden.gardenlands.claim.ClaimDirectory;
import com.herasgarden.gardenlands.claim.LandClaimRecord;
import com.herasgarden.gardenlands.rental.RentalService;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.UUID;

/**
 * GardenLands-owned habitability checks for Society and other domain plugins.
 */
public final class LandsPropertyHabitabilityService implements PropertyHabitabilityService {
    private final ClaimDirectory claims;
    private final RentalService rentals;

    public LandsPropertyHabitabilityService(ClaimDirectory claims, RentalService rentals) {
        this.claims = claims;
        this.rentals = rentals;
    }

    @Override
    public boolean hasBed(UUID claimId) {
        LandClaimRecord claim = claims.find(claimId).orElse(null);
        if (claim == null) return false;
        World world = Bukkit.getWorld(claim.worldId());
        if (world == null || claim.vertices().isEmpty()) return false;

        int minX = claim.vertices().stream().map(LandClaimRecord.Point::x).min(Comparator.naturalOrder()).orElse(0);
        int maxX = claim.vertices().stream().map(LandClaimRecord.Point::x).max(Comparator.naturalOrder()).orElse(0);
        int minZ = claim.vertices().stream().map(LandClaimRecord.Point::z).min(Comparator.naturalOrder()).orElse(0);
        int maxZ = claim.vertices().stream().map(LandClaimRecord.Point::z).max(Comparator.naturalOrder()).orElse(0);
        int minY = claim.fullHeight() ? world.getMinHeight() : Math.max(world.getMinHeight(), claim.minY());
        int maxY = claim.fullHeight() ? world.getMaxHeight() - 1 : Math.min(world.getMaxHeight() - 1, claim.maxY());

        // Homes/units are expected to be compact. Scan exactly inside the
        // registered claim instead of guessing near the mailbox.
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    if (!claim.contains(x, y, z)) continue;
                    if (world.getBlockAt(x, y, z).getType().name().endsWith("_BED")) return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean rentalUnavailable(UUID claimId) throws SQLException {
        return rentals.openRental(claimId).isPresent();
    }
}
