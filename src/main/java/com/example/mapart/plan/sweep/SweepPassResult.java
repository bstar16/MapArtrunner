package com.example.mapart.plan.sweep;

import java.util.List;
import java.util.Set;

public record SweepPassResult(
        int passIndex,
        int laneIndex,
        SweepPassState finalState,
        int ticksElapsed,
        int successCount,
        int missedCount,
        int deferredCount,
        int skippedCount,
        List<Integer> leftoverPlacementIndices,
        Set<Integer> exhaustedPlacementIndices
) {
    public SweepPassResult {
        leftoverPlacementIndices = List.copyOf(leftoverPlacementIndices);
        exhaustedPlacementIndices = Set.copyOf(exhaustedPlacementIndices);
    }
}
