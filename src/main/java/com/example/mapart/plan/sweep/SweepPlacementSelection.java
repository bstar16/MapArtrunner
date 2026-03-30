package com.example.mapart.plan.sweep;

import java.util.List;

public record SweepPlacementSelection(
        SweepPlacementAction action,
        int totalIncompleteInLane,
        List<SweepPlacementCandidate> rankedCandidates,
        List<SweepPlacementCandidate> deferredCandidates
) {
    public SweepPlacementSelection {
        rankedCandidates = List.copyOf(rankedCandidates);
        deferredCandidates = List.copyOf(deferredCandidates);
    }
}
