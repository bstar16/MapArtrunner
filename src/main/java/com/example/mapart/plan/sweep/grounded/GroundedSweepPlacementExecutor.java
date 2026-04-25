package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GroundedSweepPlacementExecutor {
    private final GroundedSweepSettings settings;
    private final int failureGraceTicks;
    private final int systemicLagThreshold;
    private final GroundedSweepLeftoverTracker leftoverTracker = new GroundedSweepLeftoverTracker();
    private final Map<Integer, DelayedFailure> delayedFailures = new HashMap<>();

    public GroundedSweepPlacementExecutor(GroundedSweepSettings settings, int failureGraceTicks, int systemicLagThreshold) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (failureGraceTicks < 0) {
            throw new IllegalArgumentException("failureGraceTicks must be >= 0");
        }
        if (systemicLagThreshold <= 0) {
            throw new IllegalArgumentException("systemicLagThreshold must be > 0");
        }
        this.failureGraceTicks = failureGraceTicks;
        this.systemicLagThreshold = systemicLagThreshold;
    }

    public GroundedSweepPlacementSelection selectPlacements(List<GroundedPlacementTarget> pendingPlacements,
                                                            GroundedSweepLane lane,
                                                            int currentProgress,
                                                            long currentTick) {
        Objects.requireNonNull(pendingPlacements, "pendingPlacements");
        Objects.requireNonNull(lane, "lane");
        expireGraceWindow(currentTick);

        List<GroundedSweepPlacementCandidate> ranked = new ArrayList<>();
        List<GroundedSweepPlacementCandidate> deferred = new ArrayList<>();

        for (GroundedPlacementTarget target : pendingPlacements) {
            if (!isInLaneCorridor(target.relativePos(), lane.corridorBounds())) {
                continue;
            }
            int relativeOffset = laneRelativeOffset(lane.direction(), lane.centerlineCoordinate(), target.relativePos());
            GroundedCorridorColumn column = GroundedCorridorColumn.fromOffset(relativeOffset);
            if (column == GroundedCorridorColumn.OUTSIDE) {
                continue;
            }

            int placementProgress = progressCoordinate(lane.direction(), target.relativePos());
            int signedDelta = signedProgressDelta(lane.direction(), currentProgress, placementProgress);
            GroundedProgressBucket bucket = classifyProgressBucket(signedDelta);

            GroundedSweepPlacementCandidate candidate = new GroundedSweepPlacementCandidate(
                    target.placementIndex(),
                    target.relativePos(),
                    placementProgress,
                    signedDelta,
                    column,
                    bucket
            );

            if (bucket == GroundedProgressBucket.DEFERRED) {
                deferred.add(candidate);
                leftoverTracker.mark(target.placementIndex(), GroundedSweepLeftoverTracker.GroundedLeftoverReason.DEFERRED);
                continue;
            }

            ranked.add(candidate);
        }

        ranked.sort(candidateComparator());
        deferred.sort(Comparator.comparingInt(GroundedSweepPlacementCandidate::placementIndex));

        return new GroundedSweepPlacementSelection(
                ranked,
                deferred,
                leftoverTracker.snapshot(),
                delayedFailures.size() >= systemicLagThreshold
        );
    }

    public void recordPlacementResult(int placementIndex, PlacementResultType resultType, long currentTick) {
        Objects.requireNonNull(resultType, "resultType");
        switch (resultType) {
            case PLACED -> {
                delayedFailures.remove(placementIndex);
                leftoverTracker.clear(placementIndex);
            }
            case DEFERRED -> leftoverTracker.mark(placementIndex, GroundedSweepLeftoverTracker.GroundedLeftoverReason.DEFERRED);
            case MISSED -> leftoverTracker.mark(placementIndex, GroundedSweepLeftoverTracker.GroundedLeftoverReason.MISSED);
            case FAILED -> {
                delayedFailures.remove(placementIndex);
                leftoverTracker.clearReason(placementIndex, GroundedSweepLeftoverTracker.GroundedLeftoverReason.LAG_GRACE_RETRY_DELAYED);
                leftoverTracker.mark(placementIndex, GroundedSweepLeftoverTracker.GroundedLeftoverReason.FAILED);
            }
            case RETRY_DELAYED -> {
                delayedFailures.compute(placementIndex, (ignored, existing) -> {
                    if (existing == null) {
                        return new DelayedFailure(currentTick, currentTick);
                    }
                    return new DelayedFailure(existing.firstFailureTick(), currentTick);
                });
                leftoverTracker.mark(placementIndex, GroundedSweepLeftoverTracker.GroundedLeftoverReason.LAG_GRACE_RETRY_DELAYED);
            }
        }
    }

    public List<GroundedSweepLeftoverTracker.GroundedLeftoverRecord> leftovers() {
        return leftoverTracker.snapshot();
    }

    private void expireGraceWindow(long currentTick) {
        Iterator<Map.Entry<Integer, DelayedFailure>> iterator = delayedFailures.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, DelayedFailure> entry = iterator.next();
            DelayedFailure failure = entry.getValue();
            if ((currentTick - failure.firstFailureTick()) < failureGraceTicks) {
                continue;
            }
            leftoverTracker.clearReason(entry.getKey(), GroundedSweepLeftoverTracker.GroundedLeftoverReason.LAG_GRACE_RETRY_DELAYED);
            leftoverTracker.mark(entry.getKey(), GroundedSweepLeftoverTracker.GroundedLeftoverReason.FAILED);
            iterator.remove();
        }
    }

    private GroundedProgressBucket classifyProgressBucket(int signedDelta) {
        if (signedDelta == 0) {
            return GroundedProgressBucket.CURRENT_CROSS_SECTION;
        }
        if (signedDelta > 0 && signedDelta <= settings.forwardLookaheadSteps()) {
            return GroundedProgressBucket.FORWARD_LOOKAHEAD;
        }
        if (signedDelta < 0 && Math.abs(signedDelta) <= settings.trivialBehindCleanupSteps()) {
            return GroundedProgressBucket.TRIVIAL_BEHIND_CLEANUP;
        }
        return GroundedProgressBucket.DEFERRED;
    }

    private static Comparator<GroundedSweepPlacementCandidate> candidateComparator() {
        return Comparator
                .comparingInt((GroundedSweepPlacementCandidate candidate) -> candidate.progressBucket().priority())
                .thenComparingInt(candidate -> Math.abs(candidate.corridorColumn().laneRelativeOffset()))
                .thenComparingInt(candidate -> Math.abs(candidate.signedProgressDelta()))
                .thenComparingInt(GroundedSweepPlacementCandidate::placementIndex);
    }

    private static int progressCoordinate(GroundedLaneDirection direction, BlockPos relativePos) {
        return direction.alongX() ? relativePos.getX() : relativePos.getZ();
    }

    private static int signedProgressDelta(GroundedLaneDirection direction, int currentProgress, int placementProgress) {
        return (placementProgress - currentProgress) * direction.forwardSign();
    }

    static int laneRelativeOffset(GroundedLaneDirection direction, int centerlineCoordinate, BlockPos relativePos) {
        int rawOffset = direction.alongX()
                ? relativePos.getZ() - centerlineCoordinate
                : relativePos.getX() - centerlineCoordinate;

        return switch (direction) {
            case EAST, NORTH -> rawOffset;
            case WEST, SOUTH -> -rawOffset;
        };
    }

    private static boolean isInLaneCorridor(BlockPos relativePos, GroundedLaneCorridorBounds corridor) {
        return relativePos.getX() >= corridor.minX()
                && relativePos.getX() <= corridor.maxX()
                && relativePos.getZ() >= corridor.minZ()
                && relativePos.getZ() <= corridor.maxZ();
    }

    private record DelayedFailure(long firstFailureTick, long lastFailureTick) {
    }

    public enum PlacementResultType {
        PLACED,
        DEFERRED,
        MISSED,
        FAILED,
        RETRY_DELAYED
    }

    public record GroundedPlacementTarget(int placementIndex, BlockPos relativePos) {
        public GroundedPlacementTarget {
            Objects.requireNonNull(relativePos, "relativePos");
        }
    }

    public record GroundedSweepPlacementSelection(
            List<GroundedSweepPlacementCandidate> rankedCandidates,
            List<GroundedSweepPlacementCandidate> deferredCandidates,
            List<GroundedSweepLeftoverTracker.GroundedLeftoverRecord> leftovers,
            boolean systemicLagLikely
    ) {
        public GroundedSweepPlacementSelection {
            rankedCandidates = List.copyOf(rankedCandidates);
            deferredCandidates = List.copyOf(deferredCandidates);
            leftovers = List.copyOf(leftovers);
        }
    }

    public record GroundedSweepPlacementCandidate(
            int placementIndex,
            BlockPos relativePos,
            int placementProgress,
            int signedProgressDelta,
            GroundedCorridorColumn corridorColumn,
            GroundedProgressBucket progressBucket
    ) {
        public GroundedSweepPlacementCandidate {
            Objects.requireNonNull(relativePos, "relativePos");
            Objects.requireNonNull(corridorColumn, "corridorColumn");
            Objects.requireNonNull(progressBucket, "progressBucket");
        }
    }

    public enum GroundedCorridorColumn {
        LEFT_TWO(-2),
        LEFT_ONE(-1),
        CENTER(0),
        RIGHT_ONE(1),
        RIGHT_TWO(2),
        OUTSIDE(Integer.MIN_VALUE);

        private final int laneRelativeOffset;

        GroundedCorridorColumn(int laneRelativeOffset) {
            this.laneRelativeOffset = laneRelativeOffset;
        }

        public int laneRelativeOffset() {
            return laneRelativeOffset;
        }

        static GroundedCorridorColumn fromOffset(int laneRelativeOffset) {
            return switch (laneRelativeOffset) {
                case -2 -> LEFT_TWO;
                case -1 -> LEFT_ONE;
                case 0 -> CENTER;
                case 1 -> RIGHT_ONE;
                case 2 -> RIGHT_TWO;
                default -> OUTSIDE;
            };
        }
    }

    public enum GroundedProgressBucket {
        CURRENT_CROSS_SECTION(0),
        FORWARD_LOOKAHEAD(1),
        TRIVIAL_BEHIND_CLEANUP(2),
        DEFERRED(3);

        private final int priority;

        GroundedProgressBucket(int priority) {
            this.priority = priority;
        }

        public int priority() {
            return priority;
        }
    }
}
