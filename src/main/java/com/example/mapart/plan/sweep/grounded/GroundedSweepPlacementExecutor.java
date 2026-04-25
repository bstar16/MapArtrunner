package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GroundedSweepPlacementExecutor {
    public static final int FIXED_CORRIDOR_HALF_WIDTH = 2;
    public static final int FIXED_CORRIDOR_TOTAL_WIDTH = 5;

    private final GroundedSweepLane lane;
    private final GroundedSweepPlacementExecutorSettings settings;
    private final List<PlacementSlot> slots;
    private final Map<Integer, SlotRuntimeState> runtimeByPlacementIndex;

    public GroundedSweepPlacementExecutor(BuildPlan plan,
                                          BlockPos origin,
                                          GroundedSchematicBounds bounds,
                                          GroundedSweepLane lane,
                                          GroundedSweepPlacementExecutorSettings settings) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(bounds, "bounds");
        this.lane = Objects.requireNonNull(lane, "lane");
        this.settings = Objects.requireNonNull(settings, "settings");

        this.slots = buildSlots(plan.placements(), origin, bounds, lane);
        this.runtimeByPlacementIndex = initializeRuntime(this.slots);
    }

    public List<PlacementSlot> selectPlacements(int currentProgress, long nowTick) {
        expireGracePeriods(nowTick);

        List<PlacementSlot> ranked = new ArrayList<>();
        for (PlacementSlot slot : slots) {
            SlotRuntimeState runtime = runtimeByPlacementIndex.get(slot.placementIndex());
            if (runtime == null || runtime.finalized()) {
                continue;
            }
            if (runtime.status == PlacementStatus.LAG_GRACE_DELAYED || runtime.status == PlacementStatus.DEFERRED) {
                continue;
            }

            int signedProgressDelta = signedProgressDelta(slot.progressCoordinate(), currentProgress, lane.direction());
            if (signedProgressDelta < -settings.trivialBehindCleanupSteps()) {
                runtime.deferIfPending();
                continue;
            }
            if (signedProgressDelta > settings.forwardLookaheadSteps()) {
                continue;
            }

            ProgressBucket bucket = progressBucket(signedProgressDelta);
            ranked.add(slot.withSelectionContext(signedProgressDelta, bucket));
        }

        ranked.sort(Comparator
                .comparingInt((PlacementSlot slot) -> slot.selectionBucket().priority())
                .thenComparingInt(slot -> Math.abs(slot.laneOffset()))
                .thenComparingInt(slot -> Math.abs(slot.signedProgressDelta()))
                .thenComparingInt(PlacementSlot::placementIndex));

        return List.copyOf(ranked);
    }

    public void markPlacementSucceeded(int placementIndex) {
        SlotRuntimeState runtime = requireRuntime(placementIndex);
        runtime.markPlaced();
    }

    public void markPlacementFailed(int placementIndex, long nowTick, FailureKind failureKind) {
        SlotRuntimeState runtime = requireRuntime(placementIndex);
        runtime.attempts++;
        if (failureKind == FailureKind.TRANSIENT) {
            runtime.markLagGraceDelayed(nowTick + settings.placementFailureGraceTicks());
            runtime.lastFailureTick = nowTick;
            return;
        }
        runtime.markFailed(nowTick);
    }

    public List<LeftoverRecord> leftovers(long nowTick) {
        expireGracePeriods(nowTick);

        Map<Integer, LeftoverRecord> leftoversByPlacement = new LinkedHashMap<>();
        for (PlacementSlot slot : slots) {
            SlotRuntimeState runtime = runtimeByPlacementIndex.get(slot.placementIndex());
            if (runtime == null || runtime.status == PlacementStatus.PENDING || runtime.status == PlacementStatus.PLACED) {
                continue;
            }
            leftoversByPlacement.put(slot.placementIndex(), new LeftoverRecord(
                    slot.placementIndex(),
                    slot.worldPos(),
                    runtime.status,
                    runtime.attempts,
                    runtime.lastFailureTick
            ));
        }
        return List.copyOf(leftoversByPlacement.values());
    }

    public static LaneSide classifyLaneSide(GroundedSweepLane lane, BlockPos worldPos) {
        int offset = laneOffset(lane, worldPos);
        if (offset > 0) {
            return LaneSide.LEFT;
        }
        if (offset < 0) {
            return LaneSide.RIGHT;
        }
        return LaneSide.CENTER;
    }

    private static List<PlacementSlot> buildSlots(List<Placement> placements,
                                                  BlockPos origin,
                                                  GroundedSchematicBounds bounds,
                                                  GroundedSweepLane lane) {
        List<PlacementSlot> result = new ArrayList<>();
        for (int placementIndex = 0; placementIndex < placements.size(); placementIndex++) {
            Placement placement = placements.get(placementIndex);
            BlockPos worldPos = origin.add(placement.relativePos());
            if (!insideBounds(worldPos, bounds) || !insidePlannerCorridor(worldPos, lane)) {
                continue;
            }

            int laneOffset = laneOffset(lane, worldPos);
            if (Math.abs(laneOffset) > FIXED_CORRIDOR_HALF_WIDTH) {
                continue;
            }

            result.add(new PlacementSlot(
                    placementIndex,
                    worldPos.toImmutable(),
                    progressCoordinate(worldPos, lane.direction()),
                    laneOffset,
                    classifyLaneSide(lane, worldPos),
                    0,
                    ProgressBucket.CURRENT_CROSS_SECTION
            ));
        }

        result.sort(Comparator
                .comparingInt(PlacementSlot::progressCoordinate)
                .thenComparingInt(slot -> Math.abs(slot.laneOffset()))
                .thenComparingInt(PlacementSlot::placementIndex));
        return List.copyOf(result);
    }

    private static Map<Integer, SlotRuntimeState> initializeRuntime(List<PlacementSlot> slots) {
        Map<Integer, SlotRuntimeState> runtime = new HashMap<>();
        for (PlacementSlot slot : slots) {
            runtime.put(slot.placementIndex(), new SlotRuntimeState());
        }
        return runtime;
    }

    private void expireGracePeriods(long nowTick) {
        for (SlotRuntimeState runtime : runtimeByPlacementIndex.values()) {
            if (runtime.status == PlacementStatus.LAG_GRACE_DELAYED && runtime.graceDeadlineTick >= 0 && nowTick >= runtime.graceDeadlineTick) {
                runtime.markMissed();
            }
        }
    }

    private SlotRuntimeState requireRuntime(int placementIndex) {
        SlotRuntimeState runtime = runtimeByPlacementIndex.get(placementIndex);
        if (runtime == null) {
            throw new IllegalArgumentException("Unknown grounded placement index: " + placementIndex);
        }
        return runtime;
    }

    private static boolean insideBounds(BlockPos pos, GroundedSchematicBounds bounds) {
        return pos.getX() >= bounds.minX() && pos.getX() <= bounds.maxX()
                && pos.getY() >= bounds.minY() && pos.getY() <= bounds.maxY()
                && pos.getZ() >= bounds.minZ() && pos.getZ() <= bounds.maxZ();
    }

    private static boolean insidePlannerCorridor(BlockPos pos, GroundedSweepLane lane) {
        GroundedLaneCorridorBounds corridor = lane.corridorBounds();
        return pos.getX() >= corridor.minX() && pos.getX() <= corridor.maxX()
                && pos.getZ() >= corridor.minZ() && pos.getZ() <= corridor.maxZ();
    }

    private static int progressCoordinate(BlockPos pos, GroundedLaneDirection direction) {
        return direction.alongX() ? pos.getX() : pos.getZ();
    }

    private static int laneOffset(GroundedSweepLane lane, BlockPos worldPos) {
        GroundedLaneDirection direction = lane.direction();
        int centerline = lane.centerlineCoordinate();
        return switch (direction) {
            case EAST -> centerline - worldPos.getZ();
            case WEST -> worldPos.getZ() - centerline;
            case SOUTH -> worldPos.getX() - centerline;
            case NORTH -> centerline - worldPos.getX();
        };
    }

    private static int signedProgressDelta(int placementProgress, int currentProgress, GroundedLaneDirection direction) {
        return direction.forwardSign() * (placementProgress - currentProgress);
    }

    private static ProgressBucket progressBucket(int signedProgressDelta) {
        if (signedProgressDelta == 0) {
            return ProgressBucket.CURRENT_CROSS_SECTION;
        }
        if (signedProgressDelta > 0) {
            return ProgressBucket.FORWARD_LOOKAHEAD;
        }
        return ProgressBucket.TRIVIAL_BEHIND_CLEANUP;
    }

    public enum LaneSide {
        LEFT,
        CENTER,
        RIGHT
    }

    public enum FailureKind {
        TRANSIENT,
        HARD
    }

    public enum PlacementStatus {
        PENDING,
        PLACED,
        DEFERRED,
        MISSED,
        FAILED,
        LAG_GRACE_DELAYED
    }

    public enum ProgressBucket {
        CURRENT_CROSS_SECTION(0),
        FORWARD_LOOKAHEAD(1),
        TRIVIAL_BEHIND_CLEANUP(2);

        private final int priority;

        ProgressBucket(int priority) {
            this.priority = priority;
        }

        int priority() {
            return priority;
        }
    }

    public record PlacementSlot(
            int placementIndex,
            BlockPos worldPos,
            int progressCoordinate,
            int laneOffset,
            LaneSide laneSide,
            int signedProgressDelta,
            ProgressBucket selectionBucket
    ) {
        PlacementSlot withSelectionContext(int signedProgressDelta, ProgressBucket selectionBucket) {
            return new PlacementSlot(
                    placementIndex,
                    worldPos,
                    progressCoordinate,
                    laneOffset,
                    laneSide,
                    signedProgressDelta,
                    selectionBucket
            );
        }
    }

    public record LeftoverRecord(
            int placementIndex,
            BlockPos worldPos,
            PlacementStatus status,
            int attempts,
            long lastFailureTick
    ) {
    }

    private static final class SlotRuntimeState {
        private PlacementStatus status = PlacementStatus.PENDING;
        private int attempts;
        private long graceDeadlineTick = -1;
        private long lastFailureTick = -1;

        private boolean finalized() {
            return status == PlacementStatus.PLACED || status == PlacementStatus.FAILED || status == PlacementStatus.MISSED;
        }

        private void markPlaced() {
            status = PlacementStatus.PLACED;
            graceDeadlineTick = -1;
        }

        private void markFailed(long nowTick) {
            status = PlacementStatus.FAILED;
            graceDeadlineTick = -1;
            lastFailureTick = nowTick;
        }

        private void markMissed() {
            status = PlacementStatus.MISSED;
            graceDeadlineTick = -1;
        }

        private void markLagGraceDelayed(long graceDeadlineTick) {
            status = PlacementStatus.LAG_GRACE_DELAYED;
            this.graceDeadlineTick = graceDeadlineTick;
        }

        private void deferIfPending() {
            if (status == PlacementStatus.PENDING) {
                status = PlacementStatus.DEFERRED;
            }
        }
    }
}
