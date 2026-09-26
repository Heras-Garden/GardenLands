package com.herasgarden.gardenlands.property;

import com.herasgarden.gardencore.api.land.PropertyHabitabilityService;
import com.herasgarden.gardenlands.claim.ClaimDirectory;
import com.herasgarden.gardenlands.claim.LandClaimRecord;
import com.herasgarden.gardenlands.rental.RentalService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.UUID;

/**
 * GardenLands-owned habitability checks for Society and other domain plugins.
 * Only already-loaded chunks are inspected, so a housing check never forces a
 * dormant property chunk to load.
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
        if (claim == null || claim.vertices().isEmpty()) return false;
        World world = Bukkit.getWorld(claim.worldId());
        if (world == null) return false;

        int minX = claim.vertices().stream().map(LandClaimRecord.Point::x).min(Comparator.naturalOrder()).orElse(0);
        int maxX = claim.vertices().stream().map(LandClaimRecord.Point::x).max(Comparator.naturalOrder()).orElse(0);
        int minZ = claim.vertices().stream().map(LandClaimRecord.Point::z).min(Comparator.naturalOrder()).orElse(0);
        int maxZ = claim.vertices().stream().map(LandClaimRecord.Point::z).max(Comparator.naturalOrder()).orElse(0);
        int minY = claim.fullHeight() ? world.getMinHeight() : Math.max(world.getMinHeight(), claim.minY());
        int maxY = claim.fullHeight() ? world.getMaxHeight() - 1 : Math.min(world.getMaxHeight() - 1, claim.maxY());

        for (Chunk chunk : world.getLoadedChunks()) {
            int chunkMinX = chunk.getX() << 4;
            int chunkMinZ = chunk.getZ() << 4;
            int chunkMaxX = chunkMinX + 15;
            int chunkMaxZ = chunkMinZ + 15;
            if (chunkMaxX < minX || chunkMinX > maxX || chunkMaxZ < minZ || chunkMinZ > maxZ) continue;

            int fromX = Math.max(minX, chunkMinX);
            int toX = Math.min(maxX, chunkMaxX);
            int fromZ = Math.max(minZ, chunkMinZ);
            int toZ = Math.min(maxZ, chunkMaxZ);
            for (int x = fromX; x <= toX; x++) {
                for (int z = fromZ; z <= toZ; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        if (!claim.contains(x, y, z)) continue;
                        Block block = chunk.getBlock(x - chunkMinX, y, z - chunkMinZ);
                        if (completeBedInsideClaim(world, claim, block)) return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean completeBedInsideClaim(World world, LandClaimRecord claim, Block block) {
        if (!(block.getBlockData() instanceof Bed bed)) return false;
        BlockFace offset = bed.getFacing();
        if (bed.getPart() == Bisected.Half.HEAD) offset = offset.getOppositeFace();
        int otherX = block.getX() + offset.getModX();
        int otherZ = block.getZ() + offset.getModZ();
        int otherY = block.getY();
        if (!claim.contains(otherX, otherY, otherZ)) return false;
        if (!world.isChunkLoaded(otherX >> 4, otherZ >> 4)) return false;

        Block other = world.getBlockAt(otherX, otherY, otherZ);
        if (!(other.getBlockData() instanceof Bed otherBed)) return false;
        return otherBed.getFacing() == bed.getFacing()
                && otherBed.getPart() != bed.getPart()
                && other.getType() == block.getType();
    }

    @Override
    public boolean rentalUnavailable(UUID claimId) throws SQLException {
        return rentals.openRental(claimId).isPresent();
    }
}
