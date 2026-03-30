package com.example.mapart.plan.sweep;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class SweepPlacementController {
    private final SweepPlacementControllerSettings settings;

    public SweepPlacementController(SweepPlacementControllerSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public SweepPlacementSelection selectCandidates(BuildPlaneModel model,
                                                    BuildLane activeLane,
                                                    PassProgressSnapshot snapshot,
                                                    Vec3d playerPosition) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(activeLane, "activeLane");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(playerPosition, "playerPosition");

        if (snapshot.laneIndex() != activeLane.laneIndex()) {
            return new SweepPlacementSelection(SweepPlacementAction.NO_CANDIDATES, 0, List.of(), List.of());
        }

        List<BuildPlaneModel.LanePlacement> incomplete = model.incompletePlacements();
        List<SweepPlacementCandidate> ranked = new ArrayList<>();
        List<SweepPlacementCandidate> deferred = new ArrayList<>();
        int laneValidIncomplete = 0;

        for (BuildPlaneModel.LanePlacement lanePlacement : incomplete) {
            int placementProgress = lanePlacement.progress();
            if (placementProgress < activeLane.minProgress() || placementProgress > activeLane.maxProgress()) {
                continue;
            }

            LaneBandClassification band = classifyBand(model.classifyWidthBand(activeLane, lanePlacement.placement()));
            if (band == LaneBandClassification.OUTSIDE) {
                continue;
            }
            laneValidIncomplete++;

            double distance = distanceToPlayer(playerPosition, lanePlacement.placement().relativePos());
            if (distance > settings.maxCandidateDistance()) {
                continue;
            }

            int signedDelta = signedProgressDelta(activeLane, snapshot.currentProgress(), placementProgress);
            ProgressRelation progressRelation = classifyProgressRelation(signedDelta);

            SweepPlacementCandidate candidate = new SweepPlacementCandidate(
                    lanePlacement.placementIndex(),
                    lanePlacement.placement().relativePos(),
                    lanePlacement.laneIndex(),
                    placementProgress,
                    signedDelta,
                    band,
                    progressRelation,
                    distance
            );

            if (progressRelation == ProgressRelation.FAR_BEHIND || progressRelation == ProgressRelation.OUT_OF_WINDOW) {
                deferred.add(candidate);
                continue;
            }

            ranked.add(candidate);
        }

        ranked.sort(rankingComparator());
        deferred.sort(Comparator.comparingInt(SweepPlacementCandidate::placementIndex));

        if (ranked.size() > settings.maxRankedCandidates()) {
            ranked = new ArrayList<>(ranked.subList(0, settings.maxRankedCandidates()));
        }

        SweepPlacementAction action = ranked.isEmpty()
                ? (deferred.isEmpty() ? SweepPlacementAction.NO_CANDIDATES : SweepPlacementAction.DEFER_AND_ADVANCE)
                : SweepPlacementAction.PLACE_TOP_CANDIDATE;

        return new SweepPlacementSelection(action, laneValidIncomplete, ranked, deferred);
    }

    private ProgressRelation classifyProgressRelation(int signedDelta) {
        if (Math.abs(signedDelta) <= settings.nearProgressWindow()) {
            return ProgressRelation.NEAR_CURRENT;
        }
        if (signedDelta > settings.nearProgressWindow() && signedDelta <= settings.aheadProgressWindow()) {
            return ProgressRelation.SLIGHTLY_AHEAD;
        }
        if (signedDelta < 0 && Math.abs(signedDelta) <= settings.trivialBehindWindow()) {
            return ProgressRelation.SLIGHTLY_BEHIND;
        }
        if (signedDelta <= -settings.farBehindDeferralThreshold()) {
            return ProgressRelation.FAR_BEHIND;
        }
        return ProgressRelation.OUT_OF_WINDOW;
    }

    private static LaneBandClassification classifyBand(LaneWidthBand widthBand) {
        return switch (widthBand) {
            case PRIMARY -> LaneBandClassification.PRIMARY;
            case EDGE -> LaneBandClassification.EDGE;
            case OUTSIDE -> LaneBandClassification.OUTSIDE;
        };
    }

    private static int signedProgressDelta(BuildLane lane, int currentProgress, int placementProgress) {
        if (lane.direction() == LaneDirection.FORWARD) {
            return placementProgress - currentProgress;
        }
        return currentProgress - placementProgress;
    }

    private static double distanceToPlayer(Vec3d playerPosition, BlockPos relativePos) {
        double dx = (relativePos.getX() + 0.5) - playerPosition.x;
        double dy = (relativePos.getY() + 0.5) - playerPosition.y;
        double dz = (relativePos.getZ() + 0.5) - playerPosition.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static Comparator<SweepPlacementCandidate> rankingComparator() {
        return Comparator
                .comparingInt((SweepPlacementCandidate candidate) -> relationPriority(candidate.progressRelation()))
                .thenComparingInt(candidate -> bandPriority(candidate.laneBand()))
                .thenComparingDouble(SweepPlacementCandidate::distanceToPlayer)
                .thenComparingInt(candidate -> Math.abs(candidate.signedProgressDelta()))
                .thenComparingInt(SweepPlacementCandidate::placementIndex);
    }

    private static int relationPriority(ProgressRelation relation) {
        return switch (relation) {
            case NEAR_CURRENT -> 0;
            case SLIGHTLY_AHEAD -> 1;
            case SLIGHTLY_BEHIND -> 2;
            case FAR_BEHIND, OUT_OF_WINDOW -> 3;
        };
    }

    private static int bandPriority(LaneBandClassification band) {
        return switch (band) {
            case PRIMARY -> 0;
            case EDGE -> 1;
            case OUTSIDE -> 2;
        };
    }
}
