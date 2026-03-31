package com.example.mapart.plan.sweep;

import com.example.mapart.plan.sweep.flight.ElytraFlightResult;
import com.example.mapart.plan.sweep.flight.FlightFailureReason;

import java.util.List;
import java.util.Optional;
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
        List<LeftoverTracker.LeftoverRecord> leftoverRecords,
        Set<Integer> exhaustedPlacementIndices,
        Optional<ElytraFlightResult> flightResult,
        Optional<FlightFailureReason> flightFailureReason
) {
    public SweepPassResult {
        leftoverPlacementIndices = List.copyOf(leftoverPlacementIndices);
        leftoverRecords = List.copyOf(leftoverRecords);
        exhaustedPlacementIndices = Set.copyOf(exhaustedPlacementIndices);
        flightResult = flightResult == null ? Optional.empty() : flightResult;
        flightFailureReason = flightFailureReason == null ? Optional.empty() : flightFailureReason;
    }
}
