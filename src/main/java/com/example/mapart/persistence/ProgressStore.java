package com.example.mapart.persistence;

import com.example.mapart.plan.state.BuildPlanState;
import com.example.mapart.plan.state.BuildSession;

import java.util.Optional;

public class ProgressStore {
    private Snapshot snapshot;

    public void initializePlanProgress(BuildSession session) {
        saveProgress(session);
    }

    public void saveProgress(BuildSession session) {
        snapshot = new Snapshot(
                session.getState(),
                session.getProgress().getCurrentRegionIndex(),
                session.getProgress().getCurrentPlacementIndex(),
                session.getTotalCompletedPlacements()
        );
    }

    public Optional<Snapshot> getSnapshot() {
        return Optional.ofNullable(snapshot);
    }

    public void clearProgress() {
        snapshot = null;
    }

    public record Snapshot(
            BuildPlanState state,
            int currentRegionIndex,
            int currentPlacementIndex,
            int totalCompletedPlacements
    ) {
    }
}
