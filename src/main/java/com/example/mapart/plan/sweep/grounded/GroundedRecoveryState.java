package com.example.mapart.plan.sweep.grounded;

import java.util.Optional;

public final class GroundedRecoveryState {
    private static final int STABILIZATION_TICKS = 60;
    private static final int MAX_RETRY_TICKS = 200;

    private boolean active;
    private GroundedRecoverySnapshot snapshot;
    private boolean autoResumeEnabled;
    private int stabilizationTicksRemaining;
    private int retryTicksRemaining;

    public GroundedRecoveryState() {
        this.active = false;
        this.snapshot = null;
        this.autoResumeEnabled = true;
        this.stabilizationTicksRemaining = 0;
        this.retryTicksRemaining = 0;
    }

    public boolean isActive() {
        return active;
    }

    public Optional<GroundedRecoverySnapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    public boolean isAutoResumeEnabled() {
        return autoResumeEnabled;
    }

    public void setAutoResumeEnabled(boolean enabled) {
        this.autoResumeEnabled = enabled;
    }

    public boolean isStabilizing() {
        return active && stabilizationTicksRemaining > 0;
    }

    public boolean isRetrying() {
        return active && stabilizationTicksRemaining <= 0 && retryTicksRemaining > 0;
    }

    public boolean isReadyForAutoResume() {
        return active && autoResumeEnabled && stabilizationTicksRemaining <= 0;
    }

    public void activate(GroundedRecoverySnapshot snapshot) {
        this.active = true;
        this.snapshot = snapshot;
        this.stabilizationTicksRemaining = STABILIZATION_TICKS;
        this.retryTicksRemaining = MAX_RETRY_TICKS;
    }

    public void tickStabilization() {
        if (stabilizationTicksRemaining > 0) {
            stabilizationTicksRemaining--;
        }
    }

    public void tickRetry() {
        if (retryTicksRemaining > 0) {
            retryTicksRemaining--;
        }
    }

    public boolean hasRetriesRemaining() {
        return retryTicksRemaining > 0;
    }

    public void clear() {
        this.active = false;
        this.snapshot = null;
        this.stabilizationTicksRemaining = 0;
        this.retryTicksRemaining = 0;
    }
}
