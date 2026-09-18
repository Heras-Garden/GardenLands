package com.herasgarden.gardenlands.door;

import com.herasgarden.gardencore.api.permission.AccessDecision;

import java.util.UUID;

public record DoorOverride(UUID claimId, AccessDecision use, AccessDecision breakAccess) {
    public static DoorOverride inherited(UUID claimId) {
        return new DoorOverride(claimId, AccessDecision.INHERIT, AccessDecision.INHERIT);
    }

    public boolean fullyInherited() {
        return use == AccessDecision.INHERIT && breakAccess == AccessDecision.INHERIT;
    }
}
