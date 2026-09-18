package com.herasgarden.gardenlands.container;

import com.herasgarden.gardencore.api.permission.AccessDecision;

import java.util.UUID;

public record ContainerOverride(
        UUID claimId,
        AccessDecision open,
        AccessDecision insert,
        AccessDecision take,
        AccessDecision breakAccess
) {
    public static ContainerOverride inherited(UUID claimId) {
        return new ContainerOverride(claimId, AccessDecision.INHERIT, AccessDecision.INHERIT,
                AccessDecision.INHERIT, AccessDecision.INHERIT);
    }

    public boolean fullyInherited() {
        return open == AccessDecision.INHERIT && insert == AccessDecision.INHERIT
                && take == AccessDecision.INHERIT && breakAccess == AccessDecision.INHERIT;
    }
}
