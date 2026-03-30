package com.example.mapart.plan.sweep;

import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class SweepPassController {
    @FunctionalInterface
    public interface PlacementAttemptExecutor {
        PlacementAttemptResult attempt(SweepPlacementCandidate candidate);
    }

    private final int passIndex;
    private final BuildPlaneModel model;
    private final BuildLane lane;
    private final SweepPlacementController placementController;
    private final PlacementAttemptExecutor attemptExecutor;
    private final SweepPassControllerSettings settings;

    private SweepPassState state;
    private int currentProgress;
    private int ticksElapsed;

    private final Set<Integer> successfulPlacements = new HashSet<>();
    private final Set<Integer> deferredPlacements = new HashSet<>();
    private final Set<Integer> missedPlacements = new HashSet<>();
    private final Set<Integer> skippedPlacements = new HashSet<>();
    private final Set<Integer> exhaustedPlacements = new HashSet<>();
    private final Map<Integer, Integer> attemptCounts = new HashMap<>();

    public SweepPassController(int passIndex,
                               BuildPlaneModel model,
                               BuildLane lane,
                               SweepPlacementController placementController,
                               PlacementAttemptExecutor attemptExecutor,
                               SweepPassControllerSettings settings) {
        if (passIndex < 0) {
            throw new IllegalArgumentException("passIndex must be >= 0");
        }
        this.passIndex = passIndex;
        this.model = Objects.requireNonNull(model, "model");
        this.lane = Objects.requireNonNull(lane, "lane");
        this.placementController = Objects.requireNonNull(placementController, "placementController");
        this.attemptExecutor = Objects.requireNonNull(attemptExecutor, "attemptExecutor");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.state = SweepPassState.PREPARE;
        this.currentProgress = lane.direction() == LaneDirection.FORWARD ? lane.minProgress() : lane.maxProgress();
    }

    public SweepPassState state() {
        return state;
    }

    public int currentProgress() {
        return currentProgress;
    }

    public void tick(Vec3d playerPosition) {
        Objects.requireNonNull(playerPosition, "playerPosition");
        if (isTerminal()) {
            return;
        }

        ticksElapsed++;
        if (state == SweepPassState.PREPARE) {
            state = SweepPassState.ACTIVE;
        }

        updateProgressFromPlayer(playerPosition);
        if (isEndpointReached(playerPosition)) {
            state = SweepPassState.COMPLETE;
            return;
        }

        if (ticksElapsed >= settings.maxTicksWithoutCompletion()) {
            state = SweepPassState.FAILED;
            return;
        }

        PassProgressSnapshot snapshot = new PassProgressSnapshot(
                lane.laneIndex(),
                state,
                lane.direction() == LaneDirection.FORWARD ? lane.maxProgress() : lane.minProgress(),
                currentProgress,
                remainingPlacementCount()
        );

        SweepPlacementSelection selection = placementController.selectCandidates(model, lane, snapshot, playerPosition);
        selection.deferredCandidates().stream()
                .filter(candidate -> !isResolved(candidate.placementIndex()))
                .forEach(candidate -> deferredPlacements.add(candidate.placementIndex()));

        List<SweepPlacementCandidate> ranked = selection.rankedCandidates().stream()
                .filter(candidate -> !isResolved(candidate.placementIndex()))
                .toList();

        if (selection.action() != SweepPlacementAction.PLACE_TOP_CANDIDATE || ranked.isEmpty()) {
            advanceProgressOnScarcity();
            return;
        }

        SweepPlacementCandidate top = ranked.getFirst();
        if (attemptCounts.getOrDefault(top.placementIndex(), 0) >= settings.maxAttemptsPerTarget()) {
            exhaustedPlacements.add(top.placementIndex());
            missedPlacements.add(top.placementIndex());
            return;
        }

        int attempts = attemptCounts.getOrDefault(top.placementIndex(), 0) + 1;
        attemptCounts.put(top.placementIndex(), attempts);

        PlacementAttemptResult attemptResult = Objects.requireNonNull(attemptExecutor.attempt(top), "attemptResult");
        if (attemptResult.placedBlock()) {
            successfulPlacements.add(top.placementIndex());
            return;
        }

        if (attemptResult.skipped()) {
            skippedPlacements.add(top.placementIndex());
            deferredPlacements.add(top.placementIndex());
        } else {
            missedPlacements.add(top.placementIndex());
        }

        if (attempts >= settings.maxAttemptsPerTarget()) {
            exhaustedPlacements.add(top.placementIndex());
        }
    }

    public void interrupt() {
        if (!isTerminal()) {
            state = SweepPassState.INTERRUPTED;
        }
    }

    public SweepPassResult result() {
        Set<Integer> leftovers = new TreeSet<>();
        model.placementsForLane(lane.laneIndex()).forEach(lp -> {
            if (!successfulPlacements.contains(lp.placementIndex())) {
                leftovers.add(lp.placementIndex());
            }
        });

        return new SweepPassResult(
                passIndex,
                lane.laneIndex(),
                state,
                ticksElapsed,
                successfulPlacements.size(),
                missedPlacements.size(),
                deferredPlacements.size(),
                skippedPlacements.size(),
                List.copyOf(leftovers),
                Set.copyOf(exhaustedPlacements)
        );
    }

    private int remainingPlacementCount() {
        return (int) model.placementsForLane(lane.laneIndex()).stream()
                .filter(lp -> !successfulPlacements.contains(lp.placementIndex()))
                .count();
    }

    private boolean isEndpointReached(Vec3d playerPosition) {
        return model.distanceToLaneEndpoint(lane, playerPosition) <= lane.endpointTolerance();
    }

    private void updateProgressFromPlayer(Vec3d playerPosition) {
        int projected = (int) Math.round(model.projectProgress(lane, playerPosition));
        projected = Math.max(lane.minProgress(), Math.min(lane.maxProgress(), projected));

        if (lane.direction() == LaneDirection.FORWARD) {
            currentProgress = Math.max(currentProgress, projected);
            return;
        }
        currentProgress = Math.min(currentProgress, projected);
    }

    private void advanceProgressOnScarcity() {
        if (lane.direction() == LaneDirection.FORWARD) {
            currentProgress = Math.min(lane.maxProgress(), currentProgress + settings.scarcityProgressStep());
            return;
        }
        currentProgress = Math.max(lane.minProgress(), currentProgress - settings.scarcityProgressStep());
    }

    private boolean isTerminal() {
        return state == SweepPassState.COMPLETE
                || state == SweepPassState.FAILED
                || state == SweepPassState.INTERRUPTED;
    }

    private boolean isResolved(int placementIndex) {
        return successfulPlacements.contains(placementIndex) || exhaustedPlacements.contains(placementIndex);
    }
}
