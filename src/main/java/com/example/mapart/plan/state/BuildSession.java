package com.example.mapart.plan.state;

import com.example.mapart.plan.BuildPlan;
import net.minecraft.util.math.BlockPos;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public class BuildSession {
    private static final Map<BuildPlanState, Set<BuildPlanState>> TRANSITIONS = Map.of(
            BuildPlanState.IDLE, EnumSet.of(BuildPlanState.LOADED),
            BuildPlanState.LOADED, EnumSet.of(BuildPlanState.BUILDING, BuildPlanState.ERROR),
            BuildPlanState.BUILDING, EnumSet.of(BuildPlanState.PAUSED, BuildPlanState.COMPLETED, BuildPlanState.ERROR, BuildPlanState.LOADED),
            BuildPlanState.PAUSED, EnumSet.of(BuildPlanState.BUILDING, BuildPlanState.ERROR, BuildPlanState.LOADED),
            BuildPlanState.BUILDING, EnumSet.of(BuildPlanState.PAUSED, BuildPlanState.COMPLETED, BuildPlanState.ERROR),
            BuildPlanState.PAUSED, EnumSet.of(BuildPlanState.BUILDING, BuildPlanState.ERROR),
            BuildPlanState.ERROR, EnumSet.of(BuildPlanState.LOADED),
            BuildPlanState.COMPLETED, EnumSet.of(BuildPlanState.BUILDING, BuildPlanState.LOADED)
    );

    private final BuildPlan plan;
    private final BuildProgress progress;
    private BlockPos origin;
    private BuildPlanState state;

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

    public int getTotalCompletedPlacements() {
        return progress.getTotalCompletedPlacements();
    }

    public void transitionTo(BuildPlanState nextState) {
        Set<BuildPlanState> validTargets = TRANSITIONS.getOrDefault(state, Set.of());
        if (!validTargets.contains(nextState)) {
            throw new IllegalStateException("Invalid transition: " + state + " -> " + nextState);
        }
        this.state = nextState;
    }
}
