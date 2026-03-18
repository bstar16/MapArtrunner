package com.example.mapart.plan.state;

import com.example.mapart.plan.BuildPlan;
import net.minecraft.util.math.BlockPos;

public class BuildSession {
    private final BuildPlan plan;
    private final BuildProgress progress;
    private BlockPos origin;
    private BuildPlanState state;
    private RefillStatus refillStatus;
    private BuildPlanState stateBeforePause;

    public BuildSession(BuildPlan plan) {
        this.plan = plan;
        this.progress = new BuildProgress();
        this.state = BuildPlanState.IDLE;
    }

    public BuildPlan getPlan() {
        return plan;
    }

    public BuildProgress getProgress() {
        return progress;
    }

    public BlockPos getOrigin() {
        return origin;
    }

    public void setOrigin(BlockPos origin) {
        this.origin = origin;
    }

    public BuildPlanState getState() {
        return state;
    }

    public int getCurrentRegionIndex() {
        return progress.getCurrentRegionIndex();
    }

    public void setCurrentRegionIndex(int currentRegionIndex) {
        progress.setCurrentRegionIndex(currentRegionIndex);
    }

    public int getCurrentPlacementIndex() {
        return progress.getCurrentPlacementIndex();
    }

    public void setCurrentPlacementIndex(int currentPlacementIndex) {
        progress.setCurrentPlacementIndex(currentPlacementIndex);
    }

    public int getTotalCompletedPlacements() {
        return progress.getTotalCompletedPlacements();
    }

    public void incrementCompletedPlacements() {
        progress.incrementCompletedPlacements();
    }

    public RefillStatus getRefillStatus() {
        return refillStatus;
    }

    public void setRefillStatus(RefillStatus refillStatus) {
        this.refillStatus = refillStatus;
    }

    public BuildPlanState getStateBeforePause() {
        return stateBeforePause;
    }

    public void setStateBeforePause(BuildPlanState stateBeforePause) {
        this.stateBeforePause = stateBeforePause;
    }

    public void transitionTo(BuildPlanState nextState) {
        if (!state.canTransitionTo(nextState)) {
            throw new IllegalStateException("Invalid transition: " + state + " -> " + nextState);
        }
        this.state = nextState;
    }
}
