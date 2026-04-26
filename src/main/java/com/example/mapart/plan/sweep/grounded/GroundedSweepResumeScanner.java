package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.state.BuildSession;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class GroundedSweepResumeScanner {
    private static final double DEFAULT_DISTANCE_TIE_TOLERANCE = 16.0;

    public GroundedSweepResumeSelection scan(
            BuildSession session,
            GroundedSchematicBounds bounds,
            List<GroundedSweepLane> lanes,
            Vec3d playerPosition,
            int sweepHalfWidth,
            WorldBlockLookup blockLookup
    ) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(lanes, "lanes");
        Objects.requireNonNull(playerPosition, "playerPosition");
        Objects.requireNonNull(blockLookup, "blockLookup");
        if (lanes.isEmpty()) {
            return GroundedSweepResumeSelection.complete(0);
        }

        BuildPlan plan = session.getPlan();
        BlockPos origin = session.getOrigin();
        if (origin == null) {
            return GroundedSweepResumeSelection.fromPoint(laneStartPoint(lanes.getFirst(), GroundedSweepResumePoint.Reason.FRESH_START, 0), 0, plan.placements().size());
        }

        List<PlacementSnapshot> snapshots = new ArrayList<>(plan.placements().size());
        int completeCount = 0;
        int unfinishedCount = 0;
        for (Placement placement : plan.placements()) {
            BlockPos worldPos = origin.add(placement.relativePos());
            // TODO: compare full block states when placement model includes stateful block data.
            boolean complete = blockLookup.blockMatches(worldPos, placement.block());
            snapshots.add(new PlacementSnapshot(worldPos, complete));
            if (complete) {
                completeCount++;
            } else {
                unfinishedCount++;
            }
        }

        List<GroundedSweepResumePoint> candidates = new ArrayList<>();

        for (GroundedSweepLane lane : lanes) {
            LaneScan laneScan = scanLane(snapshots, bounds, lane, sweepHalfWidth);
            candidates.addAll(laneScan.candidates());
        }

        if (unfinishedCount == 0) {
            return GroundedSweepResumeSelection.complete(completeCount);
        }
        if (completeCount == 0) {
            GroundedSweepResumePoint freshStart = laneStartPoint(lanes.getFirst(), GroundedSweepResumePoint.Reason.FRESH_START, unfinishedCount);
            return GroundedSweepResumeSelection.fromPoint(freshStart, completeCount, unfinishedCount);
        }

        GroundedSweepResumePoint best = chooseBestCandidate(candidates, playerPosition, DEFAULT_DISTANCE_TIE_TOLERANCE);
        return GroundedSweepResumeSelection.fromPoint(best, completeCount, unfinishedCount);
    }

    private LaneScan scanLane(
            List<PlacementSnapshot> snapshots,
            GroundedSchematicBounds bounds,
            GroundedSweepLane lane,
            int sweepHalfWidth
    ) {
        List<BlockPos> unfinished = new ArrayList<>();

        for (PlacementSnapshot snapshot : snapshots) {
            BlockPos worldPos = snapshot.worldPos();
            if (!withinBounds(worldPos, bounds, lane, sweepHalfWidth)) {
                continue;
            }
            if (!snapshot.complete()) {
                unfinished.add(worldPos);
            }
        }

        List<GroundedSweepResumePoint> candidates = new ArrayList<>();
        if (!unfinished.isEmpty()) {
            candidates.add(laneStartPoint(lane, GroundedSweepResumePoint.Reason.FIRST_UNFINISHED, unfinished.size()));
            BlockPos earliest = earliestBySweepOrder(unfinished, lane.direction());
            candidates.add(partialLanePoint(lane, earliest, unfinished.size()));
        }
        return new LaneScan(candidates);
    }

    private static boolean withinBounds(BlockPos worldPos, GroundedSchematicBounds bounds, GroundedSweepLane lane, int sweepHalfWidth) {
        if (worldPos.getX() < bounds.minX() || worldPos.getX() > bounds.maxX()
                || worldPos.getY() < bounds.minY() || worldPos.getY() > bounds.maxY()
                || worldPos.getZ() < bounds.minZ() || worldPos.getZ() > bounds.maxZ()) {
            return false;
        }

        GroundedLaneCorridorBounds corridor = lane.corridorBounds();
        if (worldPos.getX() < corridor.minX() || worldPos.getX() > corridor.maxX()
                || worldPos.getZ() < corridor.minZ() || worldPos.getZ() > corridor.maxZ()) {
            return false;
        }

        int lateralCoordinate = lane.direction().alongX() ? worldPos.getZ() : worldPos.getX();
        return Math.abs(lateralCoordinate - lane.centerlineCoordinate()) <= sweepHalfWidth;
    }

    private static GroundedSweepResumePoint laneStartPoint(
            GroundedSweepLane lane,
            GroundedSweepResumePoint.Reason reason,
            int unfinishedPlacementCount
    ) {
        int progress = progressCoordinate(lane.direction(), lane.startPoint());
        return new GroundedSweepResumePoint(
                lane.laneIndex(),
                GroundedSweepResumePoint.SweepPhase.FORWARD,
                lane.direction(),
                lane.centerlineCoordinate(),
                progress,
                lane.startPoint(),
                lane.direction().yawDegrees(),
                reason,
                unfinishedPlacementCount,
                unfinishedPlacementCount
        );
    }

    private static GroundedSweepResumePoint partialLanePoint(
            GroundedSweepLane lane,
            BlockPos earliestUnfinished,
            int unfinishedPlacementCount
    ) {
        int progress = progressCoordinate(lane.direction(), earliestUnfinished);
        BlockPos approachTarget = lane.direction().alongX()
                ? new BlockPos(progress, lane.startPoint().getY(), lane.centerlineCoordinate())
                : new BlockPos(lane.centerlineCoordinate(), lane.startPoint().getY(), progress);

        return new GroundedSweepResumePoint(
                lane.laneIndex(),
                GroundedSweepResumePoint.SweepPhase.FORWARD,
                lane.direction(),
                lane.centerlineCoordinate(),
                progress,
                approachTarget,
                lane.direction().yawDegrees(),
                GroundedSweepResumePoint.Reason.PARTIAL_LANE,
                unfinishedPlacementCount,
                unfinishedPlacementCount
        );
    }

    private static BlockPos earliestBySweepOrder(List<BlockPos> worldPositions, GroundedLaneDirection direction) {
        Comparator<BlockPos> comparator = Comparator.comparingInt(pos -> progressCoordinate(direction, pos) * direction.forwardSign());
        return worldPositions.stream().min(comparator).orElse(worldPositions.getFirst());
    }

    private static int progressCoordinate(GroundedLaneDirection direction, BlockPos pos) {
        return direction.alongX() ? pos.getX() : pos.getZ();
    }

    private static GroundedSweepResumePoint chooseBestCandidate(
            List<GroundedSweepResumePoint> candidates,
            Vec3d playerPosition,
            double tieToleranceBlocks
    ) {
        if (candidates.isEmpty()) {
            throw new IllegalStateException("Expected at least one resume candidate for unfinished placements.");
        }
        GroundedSweepResumePoint best = null;
        double bestDistance = Double.MAX_VALUE;
        for (GroundedSweepResumePoint candidate : candidates) {
            double candidateDistance = Math.sqrt(distanceSq(playerPosition, candidate.approachTarget()));
            if (best == null || candidateDistance < bestDistance) {
                best = candidate;
                bestDistance = candidateDistance;
                continue;
            }
            if (best != null
                    && candidate.laneIndex() != best.laneIndex()
                    && Math.abs(candidateDistance - bestDistance) <= tieToleranceBlocks
                    && sweepOrderRank(candidate) < sweepOrderRank(best)) {
                best = candidate;
                bestDistance = candidateDistance;
            }
        }
        return best;
    }

    private static int sweepOrderRank(GroundedSweepResumePoint point) {
        return (point.laneIndex() * 1_000_000) + (point.progressCoordinate() * point.laneDirection().forwardSign());
    }

    private static double distanceSq(Vec3d playerPosition, BlockPos target) {
        double dx = playerPosition.x - (target.getX() + 0.5);
        double dy = playerPosition.y - (target.getY() + 0.5);
        double dz = playerPosition.z - (target.getZ() + 0.5);
        return (dx * dx) + (dy * dy) + (dz * dz);
    }

    private record PlacementSnapshot(BlockPos worldPos, boolean complete) {
    }

    private record LaneScan(List<GroundedSweepResumePoint> candidates) {
    }
}
