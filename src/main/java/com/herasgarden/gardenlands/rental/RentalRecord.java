package com.herasgarden.gardenlands.rental;

import java.util.UUID;

public record RentalRecord(
        UUID id,
        UUID propertyId,
        UUID claimId,
        UUID ownerId,
        UUID renterId,
        long price,
        long durationMinutes,
        UUID orderId,
        String status,
        long createdAt,
        Long startedAt,
        Long expiresAt,
        long updatedAt
) {
    public boolean listed() {
        return "LISTED".equals(status);
    }

    public boolean active(long now) {
        return "ACTIVE".equals(status) && renterId != null && expiresAt != null && expiresAt > now;
    }
}
