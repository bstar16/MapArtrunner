package com.example.mapart.plan.state;

import com.example.mapart.plan.BuildPlan;
import net.minecraft.util.math.BlockPos;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public class BuildSession {
    private static final Map<BuildPlanState, Set<BuildPlanState>> TRANSITIONS = createTransitions();

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

    private static Map<BuildPlanState, Set<BuildPlanState>> createTransitions() {
        EnumMap<BuildPlanState, Set<BuildPlanState>> transitions = new EnumMap<>(BuildPlanState.class);
        transitions.put(BuildPlanState.IDLE, EnumSet.of(BuildPlanState.LOADED));
        transitions.put(BuildPlanState.LOADED, EnumSet.of(BuildPlanState.BUILDING, BuildPlanState.ERROR));
        transitions.put(BuildPlanState.BUILDING, EnumSet.of(
                BuildPlanState.PAUSED,
                BuildPlanState.COMPLETED,
                BuildPlanState.ERROR,
                BuildPlanState.LOADED
        ));
        transitions.put(BuildPlanState.PAUSED, EnumSet.of(
                BuildPlanState.BUILDING,
                BuildPlanState.ERROR,
                BuildPlanState.LOADED
        ));
        transitions.put(BuildPlanState.ERROR, EnumSet.of(BuildPlanState.LOADED));
        transitions.put(BuildPlanState.COMPLETED, EnumSet.of(BuildPlanState.BUILDING, BuildPlanState.LOADED));
        return Collections.unmodifiableMap(transitions);
    }
}