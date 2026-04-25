package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GroundedSweepPlacementExecutor {
    private final GroundedSweepPlacementExecutorSettings settings;
    private final GroundedSweepLeftoverTracker leftoverTracker = new GroundedSweepLeftoverTracker();
    private final Map<Integer, Long> graceExpiryTickByPlacement = new HashMap<>();

    public GroundedSweepPlacementExecutor(GroundedSweepPlacementExecutorSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public SweepSelection select(
            GroundedSweepLane lane,
            GroundedSchematicBounds bounds,
            int currentProgressCoordinate,
            long tick,
            List<PlacementTarget> pending
    ) {
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(pending, "pending");

        List<SweepCandidate> ranked = new ArrayList<>();
        List<SweepCandidate> deferred = new ArrayList<>();

        for (PlacementTarget target : pending) {
            if (!withinBounds(target.worldPos(), bounds) || !withinLaneStripe(target.worldPos(), lane)) {
                continue;
            }

            if (isInGracePeriod(target.placementIndex(), tick)) {
                leftoverTracker.mark(target.placementIndex(), GroundedSweepLeftoverTracker.GroundedLeftoverReason.RETRY_DELAYED);
                continue;
            }

            int signedProgressDelta = signedProgressDelta(lane.direction(), currentProgressCoordinate, progressCoordinate(lane.direction(), target.worldPos()));
            ProgressBucket bucket = classifyBucket(signedProgressDelta);
            if (bucket == ProgressBucket.DEFERRED) {
                leftoverTracker.mark(target.placementIndex(), GroundedSweepLeftoverTracker.GroundedLeftoverReason.DEFERRED);
                deferred.add(new SweepCandidate(target.placementIndex(), target.worldPos(), signedProgressDelta, laneRelativeBand(lane, target.worldPos()), bucket));
                continue;
            }

            ranked.add(new SweepCandidate(target.placementIndex(), target.worldPos(), signedProgressDelta, laneRelativeBand(lane, target.worldPos()), bucket));
        }

        ranked.sort(Comparator
                .comparingInt((SweepCandidate candidate) -> candidate.bucket().priority)
                .thenComparingInt(candidate -> Math.abs(candidate.signedProgressDelta()))
                .thenComparingInt(candidate -> candidate.laneBand().sortKey)
                .thenComparingInt(SweepCandidate::placementIndex));

        deferred.sort(Comparator.comparingInt(SweepCandidate::placementIndex));

        return new SweepSelection(List.copyOf(ranked), List.copyOf(deferred), leftoverTracker.snapshot());
    }

    public void recordPlacementResult(int placementIndex, PlacementResult placementResult, long tick) {
        Objects.requireNonNull(placementResult, "placementResult");

        switch (placementResult) {
            case SUCCESS -> {
                graceExpiryTickByPlacement.remove(placementIndex);
                leftoverTracker.clear(placementIndex);
            }
            case MISSED -> leftoverTracker.mark(placementIndex, GroundedSweepLeftoverTracker.GroundedLeftoverReason.MISSED);
            case FAILED -> {
                Long graceExpiryTick = graceExpiryTickByPlacement.get(placementIndex);
                if (graceExpiryTick == null) {
                    graceExpiryTickByPlacement.put(placementIndex, tick + settings.placementFailureGraceTicks());
                    leftoverTracker.clearReason(placementIndex, GroundedSweepLeftoverTracker.GroundedLeftoverReason.FAILED);
                    leftoverTracker.mark(placementIndex, GroundedSweepLeftoverTracker.GroundedLeftoverReason.RETRY_DELAYED);
                    return;
                }

                if (tick <= graceExpiryTick) {
                    leftoverTracker.clearReason(placementIndex, GroundedSweepLeftoverTracker.GroundedLeftoverReason.FAILED);
                    leftoverTracker.mark(placementIndex, GroundedSweepLeftoverTracker.GroundedLeftoverReason.RETRY_DELAYED);
                    return;
                }

                graceExpiryTickByPlacement.remove(placementIndex);
                leftoverTracker.clearReason(placementIndex, GroundedSweepLeftoverTracker.GroundedLeftoverReason.RETRY_DELAYED);
                leftoverTracker.mark(placementIndex, GroundedSweepLeftoverTracker.GroundedLeftoverReason.FAILED);
            }
        }
    }

    public void recordFinalFailure(int placementIndex) {
        graceExpiryTickByPlacement.remove(placementIndex);
        leftoverTracker.clearReason(placementIndex, GroundedSweepLeftoverTracker.GroundedLeftoverReason.RETRY_DELAYED);
        leftoverTracker.mark(placementIndex, GroundedSweepLeftoverTracker.GroundedLeftoverReason.FAILED);
    }

    static LaneRelativeBand laneRelativeBand(GroundedSweepLane lane, BlockPos worldPos) {
        int lateralDelta = lateralDeltaFromCenterline(lane.direction(), lane.centerlineCoordinate(), worldPos);
        if (lateralDelta == 0) {
            return LaneRelativeBand.CENTERLINE;
        }

        boolean left = isLeftOfTravel(lane.direction(), lateralDelta);
        int abs = Math.abs(lateralDelta);
        if (abs == 1) {
            return left ? LaneRelativeBand.LEFT_ONE : LaneRelativeBand.RIGHT_ONE;
        }
        if (abs == 2) {
            return left ? LaneRelativeBand.LEFT_TWO : LaneRelativeBand.RIGHT_TWO;
        }
        return LaneRelativeBand.OUTSIDE;
    }

    private ProgressBucket classifyBucket(int signedProgressDelta) {
        if (signedProgressDelta == 0) {
            return ProgressBucket.CURRENT_CROSS_SECTION;
        }
        if (signedProgressDelta > 0 && signedProgressDelta <= settings.forwardLookaheadSteps()) {
            return ProgressBucket.SMALL_FORWARD_LOOKAHEAD;
        }
        if (signedProgressDelta < 0 && Math.abs(signedProgressDelta) <= settings.trivialBehindCleanupSteps()) {
            return ProgressBucket.TRIVIAL_BEHIND_CLEANUP;
        }
        return ProgressBucket.DEFERRED;
    }

    private boolean isInGracePeriod(int placementIndex, long tick) {
        Long graceExpiryTick = graceExpiryTickByPlacement.get(placementIndex);
        return graceExpiryTick != null && tick <= graceExpiryTick;
    }

    private static boolean withinBounds(BlockPos pos, GroundedSchematicBounds bounds) {
        return pos.getX() >= bounds.minX() && pos.getX() <= bounds.maxX()
                && pos.getY() >= bounds.minY() && pos.getY() <= bounds.maxY()
                && pos.getZ() >= bounds.minZ() && pos.getZ() <= bounds.maxZ();
    }

    private boolean withinLaneStripe(BlockPos pos, GroundedSweepLane lane) {
        GroundedLaneCorridorBounds corridor = lane.corridorBounds();
        if (pos.getX() < corridor.minX() || pos.getX() > corridor.maxX() || pos.getZ() < corridor.minZ() || pos.getZ() > corridor.maxZ()) {
            return false;
        }
        return Math.abs(lateralDeltaFromCenterline(lane.direction(), lane.centerlineCoordinate(), pos)) <= settings.corridorHalfWidth();
    }

    private static int signedProgressDelta(GroundedLaneDirection direction, int currentProgress, int placementProgress) {
        return direction.forwardSign() > 0
                ? placementProgress - currentProgress
                : currentProgress - placementProgress;
    }

    private static int progressCoordinate(GroundedLaneDirection direction, BlockPos pos) {
        return direction.alongX() ? pos.getX() : pos.getZ();
    }

    private static int lateralDeltaFromCenterline(GroundedLaneDirection direction, int centerline, BlockPos worldPos) {
        int lateralCoordinate = direction.alongX() ? worldPos.getZ() : worldPos.getX();
        return lateralCoordinate - centerline;
    }

    private static boolean isLeftOfTravel(GroundedLaneDirection direction, int lateralDelta) {
        int leftSign = switch (direction) {
            case EAST, NORTH -> -1;
            case WEST, SOUTH -> 1;
        };
        return lateralDelta * leftSign > 0;
    }

    public enum PlacementResult {
        SUCCESS,
        MISSED,
        FAILED
    }

    public enum LaneRelativeBand {
        LEFT_TWO(0),
        LEFT_ONE(1),
        CENTERLINE(2),
        RIGHT_ONE(3),
        RIGHT_TWO(4),
        OUTSIDE(5);

        private final int sortKey;

        LaneRelativeBand(int sortKey) {
            this.sortKey = sortKey;
        }
    }

    public enum ProgressBucket {
        CURRENT_CROSS_SECTION(0),
        SMALL_FORWARD_LOOKAHEAD(1),
        TRIVIAL_BEHIND_CLEANUP(2),
        DEFERRED(3);

        private final int priority;

        ProgressBucket(int priority) {
            this.priority = priority;
        }
    }

    public record PlacementTarget(int placementIndex, BlockPos worldPos) {
        public PlacementTarget {
            Objects.requireNonNull(worldPos, "worldPos");
        }
    }

    public record SweepCandidate(
            int placementIndex,
            BlockPos worldPos,
            int signedProgressDelta,
            LaneRelativeBand laneBand,
            ProgressBucket bucket
    ) {
        public SweepCandidate {
            Objects.requireNonNull(worldPos, "worldPos");
            Objects.requireNonNull(laneBand, "laneBand");
            Objects.requireNonNull(bucket, "bucket");
        }
    }

    public record SweepSelection(
            List<SweepCandidate> rankedCandidates,
            List<SweepCandidate> deferredCandidates,
            List<GroundedSweepLeftoverTracker.GroundedLeftoverRecord> leftovers
    ) {
        public SweepSelection {
            rankedCandidates = List.copyOf(rankedCandidates);
            deferredCandidates = List.copyOf(deferredCandidates);
            leftovers = List.copyOf(leftovers);
        }
    }
}
