package com.herasgarden.gardenlands.acquisition;

import java.util.UUID;

public record LandPurchaseOffer(
        UUID id,
        UUID targetClaimId,
        UUID buyerId,
        UUID sellerId,
        long amount,
        UUID orderId,
        String status,
        long createdAt,
        long updatedAt
) {
}
