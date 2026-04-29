package com.example.mapart.plan.sweep.grounded;

import java.util.Optional;

public final class GroundedRecoveryState {
    private boolean active;
    private GroundedRecoverySnapshot snapshot;

    public GroundedRecoveryState() {
        this.active = false;
        this.snapshot = null;
    }

    public boolean isActive() {
        return active;
    }

    public Optional<GroundedRecoverySnapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    public void activate(GroundedRecoverySnapshot snapshot) {
        this.active = true;
        this.snapshot = snapshot;
    }

    public void clear() {
        this.active = false;
        this.snapshot = null;
    }
}
