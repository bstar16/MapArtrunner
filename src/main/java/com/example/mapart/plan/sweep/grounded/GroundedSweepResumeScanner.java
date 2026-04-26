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
    private static final double SMART_RESUME_DISTANCE_TIE_TOLERANCE = 16.0;

    public GroundedSweepResumeSelection scan(
            BuildSession session,
            GroundedSchematicBounds bounds,
            List<GroundedSweepLane> lanes,
            int sweepHalfWidth,
            Vec3d playerPosition,
            PlacementCompletionLookup completionLookup
    ) {
        Objects.requireNonNull(session, "session");
        return scan(session.getPlan(), session.getOrigin(), bounds, lanes, sweepHalfWidth, playerPosition, completionLookup);
    }

    public GroundedSweepResumeSelection scan(
            BuildPlan plan,
            BlockPos origin,
            GroundedSchematicBounds bounds,
            List<GroundedSweepLane> lanes,
            int sweepHalfWidth,
            Vec3d playerPosition,
            PlacementCompletionLookup completionLookup
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(lanes, "lanes");
        Objects.requireNonNull(playerPosition, "playerPosition");
        Objects.requireNonNull(completionLookup, "completionLookup");

        List<PlacementWorldState> worldStates = collectWorldStates(plan, origin, completionLookup);
        long completedCount = worldStates.stream().filter(PlacementWorldState::complete).count();
        long unfinishedCount = worldStates.size() - completedCount;

        if (unfinishedCount == 0) {
            return GroundedSweepResumeSelection.complete();
        }

        if (completedCount == 0 && !lanes.isEmpty()) {
            return GroundedSweepResumeSelection.resumeAt(buildLaneStartResumePoint(
                    lanes.getFirst(),
                    bounds,
                    GroundedSweepResumePoint.ResumeReason.FRESH_START,
                    (int) unfinishedCount,
                    (int) unfinishedCount
            ));
        }

        List<ScoredCandidate> candidates = collectCandidates(bounds, lanes, sweepHalfWidth, playerPosition, worldStates);
        if (candidates.isEmpty()) {
            // Fallback: unfinished placements exist but lane projection found nothing useful.
            if (lanes.isEmpty()) {
                return GroundedSweepResumeSelection.complete();
            }
            return GroundedSweepResumeSelection.resumeAt(buildLaneStartResumePoint(
                    lanes.getFirst(),
                    bounds,
                    GroundedSweepResumePoint.ResumeReason.FIRST_UNFINISHED,
                    (int) unfinishedCount,
                    (int) unfinishedCount
            ));
        }

        ScoredCandidate selected = selectBestCandidate(candidates);
        return GroundedSweepResumeSelection.resumeAt(selected.point());
    }

    private static List<PlacementWorldState> collectWorldStates(BuildPlan plan, BlockPos origin, PlacementCompletionLookup lookup) {
        List<PlacementWorldState> states = new ArrayList<>(plan.placements().size());
        for (int i = 0; i < plan.placements().size(); i++) {
            Placement placement = plan.placements().get(i);
            BlockPos worldPos = origin.add(placement.relativePos());
            // TODO: use full block-state comparison instead of Block identity when placement model supports it.
            boolean complete = lookup.isExpectedBlockPlaced(worldPos, placement.block());
            states.add(new PlacementWorldState(i, placement, worldPos, complete));
        }
        return List.copyOf(states);
    }

    private static List<ScoredCandidate> collectCandidates(
            GroundedSchematicBounds bounds,
            List<GroundedSweepLane> lanes,
            int sweepHalfWidth,
            Vec3d playerPosition,
            List<PlacementWorldState> worldStates
    ) {
        List<ScoredCandidate> candidates = new ArrayList<>();
        for (GroundedSweepLane lane : lanes) {
            List<PlacementWorldState> laneUnfinished = worldStates.stream()
                    .filter(state -> !state.complete())
                    .filter(state -> isInLaneCorridor(state.worldPos(), lane, bounds, sweepHalfWidth))
                    .sorted(Comparator.comparingDouble(state -> sweepProgressOrder(state.worldPos(), lane)))
                    .toList();
            if (laneUnfinished.isEmpty()) {
                continue;
            }

            int unfinishedCount = laneUnfinished.size();
            candidates.add(toScored(buildLaneStartResumePoint(
                    lane,
                    bounds,
                    GroundedSweepResumePoint.ResumeReason.FIRST_UNFINISHED,
                    unfinishedCount,
                    unfinishedCount
            ), playerPosition));

            PlacementWorldState earliest = laneUnfinished.getFirst();
            candidates.add(toScored(buildPartialResumePoint(
                    lane,
                    bounds,
                    earliest.worldPos(),
                    unfinishedCount
            ), playerPosition));
        }
        return candidates;
    }

    private static ScoredCandidate toScored(GroundedSweepResumePoint point, Vec3d playerPosition) {
        Vec3d standingCenter = Vec3d.ofBottomCenter(point.standingPosition());
        double distance = standingCenter.distanceTo(playerPosition);
        int sweepOrder = point.laneIndex() * 10 + (point.reason() == GroundedSweepResumePoint.ResumeReason.FIRST_UNFINISHED ? 1 : 0);
        return new ScoredCandidate(point, distance, sweepOrder);
    }

    private static ScoredCandidate selectBestCandidate(List<ScoredCandidate> candidates) {
        ScoredCandidate best = null;
        for (ScoredCandidate candidate : candidates) {
            if (best == null) {
                best = candidate;
                continue;
            }
            if (candidate.distanceToPlayer() + SMART_RESUME_DISTANCE_TIE_TOLERANCE < best.distanceToPlayer()) {
                best = candidate;
                continue;
            }
            if (Math.abs(candidate.distanceToPlayer() - best.distanceToPlayer()) <= SMART_RESUME_DISTANCE_TIE_TOLERANCE
                    && candidate.sweepOrder() < best.sweepOrder()) {
                best = candidate;
            }
        }
        return best;
    }

    private static GroundedSweepResumePoint buildLaneStartResumePoint(
            GroundedSweepLane lane,
            GroundedSchematicBounds bounds,
            GroundedSweepResumePoint.ResumeReason reason,
            int usefulCount,
            int unfinishedCount
    ) {
        return new GroundedSweepResumePoint(
                lane.laneIndex(),
                GroundedSweepResumePoint.SweepPhase.FORWARD,
                lane.direction(),
                lane.centerlineCoordinate(),
                lane.direction().alongX() ? lane.startPoint().getX() : lane.startPoint().getZ(),
                new BlockPos(lane.startPoint().getX(), bounds.minY() + 1, lane.startPoint().getZ()),
                lane.direction().yawDegrees(),
                reason,
                usefulCount,
                unfinishedCount
        );
    }

    private static GroundedSweepResumePoint buildPartialResumePoint(
            GroundedSweepLane lane,
            GroundedSchematicBounds bounds,
            BlockPos unfinishedWorldPos,
            int unfinishedCount
    ) {
        BlockPos standing = lane.direction().alongX()
                ? new BlockPos(unfinishedWorldPos.getX(), bounds.minY() + 1, lane.centerlineCoordinate())
                : new BlockPos(lane.centerlineCoordinate(), bounds.minY() + 1, unfinishedWorldPos.getZ());
        int progressCoordinate = lane.direction().alongX() ? unfinishedWorldPos.getX() : unfinishedWorldPos.getZ();
        return new GroundedSweepResumePoint(
                lane.laneIndex(),
                GroundedSweepResumePoint.SweepPhase.FORWARD,
                lane.direction(),
                lane.centerlineCoordinate(),
                progressCoordinate,
                standing,
                lane.direction().yawDegrees(),
                GroundedSweepResumePoint.ResumeReason.PARTIAL_LANE,
                unfinishedCount,
                unfinishedCount
        );
    }

    private static boolean isInLaneCorridor(BlockPos worldPos, GroundedSweepLane lane, GroundedSchematicBounds bounds, int sweepHalfWidth) {
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

    private static double sweepProgressOrder(BlockPos worldPos, GroundedSweepLane lane) {
        double progress = lane.direction().progressCoordinate(worldPos.getX(), worldPos.getZ());
        return progress * lane.direction().forwardSign();
    }

    private record PlacementWorldState(int index, Placement placement, BlockPos worldPos, boolean complete) {
    }

    private record ScoredCandidate(GroundedSweepResumePoint point, double distanceToPlayer, int sweepOrder) {
    }
}
