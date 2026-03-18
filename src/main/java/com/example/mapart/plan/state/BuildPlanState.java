package com.example.mapart.plan.state;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum BuildPlanState {
    IDLE,
    LOADED,
    BUILDING,
    NEED_REFILL,
    REFILLING,
    RETURNING,
    PAUSED,
    ERROR,
    COMPLETED;

    private static final Map<BuildPlanState, Set<BuildPlanState>> VALID_TRANSITIONS = createTransitions();

    public boolean canTransitionTo(BuildPlanState nextState) {
        return VALID_TRANSITIONS.getOrDefault(this, Set.of()).contains(nextState);
    }

    public Set<BuildPlanState> allowedTransitions() {
        return VALID_TRANSITIONS.getOrDefault(this, Set.of());
    }

    private static Map<BuildPlanState, Set<BuildPlanState>> createTransitions() {
        EnumMap<BuildPlanState, Set<BuildPlanState>> transitions = new EnumMap<>(BuildPlanState.class);
        transitions.put(IDLE, EnumSet.of(LOADED));
        transitions.put(LOADED, EnumSet.of(BUILDING, ERROR));
        transitions.put(BUILDING, EnumSet.of(NEED_REFILL, PAUSED, COMPLETED, ERROR, LOADED));
        transitions.put(NEED_REFILL, EnumSet.of(REFILLING, PAUSED, ERROR, LOADED));
        transitions.put(REFILLING, EnumSet.of(RETURNING, PAUSED, ERROR, LOADED));
        transitions.put(RETURNING, EnumSet.of(BUILDING, PAUSED, ERROR, LOADED));
        transitions.put(PAUSED, EnumSet.of(BUILDING, NEED_REFILL, REFILLING, RETURNING, ERROR, LOADED));
        transitions.put(ERROR, EnumSet.of(LOADED));
        transitions.put(COMPLETED, EnumSet.of(BUILDING, LOADED));
        return Collections.unmodifiableMap(transitions);
    }
}
