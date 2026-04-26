package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.state.BuildSession;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class GroundedSweepResumeScanner {
    private static final double DEFAULT_DISTANCE_TIE_TOLERANCE = 16.0;

    public ResumeSelection selectResumePoint(
            BuildSession session,
            GroundedSchematicBounds bounds,
            List<GroundedSweepLane> lanes,
            Vec3d playerPosition,
            PlacementCompletionLookup completionLookup
    ) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(lanes, "lanes");
        Objects.requireNonNull(playerPosition, "playerPosition");
        Objects.requireNonNull(completionLookup, "completionLookup");

        BuildPlan plan = session.getPlan();
        if (lanes.isEmpty()) {
            return ResumeSelection.complete(0, 0);
        }

        int completeCount = 0;
        int unfinishedCount = 0;

        List<LanePlacementStatus> statuses = new ArrayList<>(plan.placements().size());
        for (int i = 0; i < plan.placements().size(); i++) {
            Placement placement = plan.placements().get(i);
            BlockPos worldPos = bounds.origin().add(placement.relativePos());
            boolean complete = completionLookup.isPlacementComplete(placement, worldPos);
            statuses.add(new LanePlacementStatus(i, placement, worldPos, complete));
            if (complete) {
                completeCount++;
            } else {
                unfinishedCount++;
            }
        }

        if (unfinishedCount == 0) {
            return ResumeSelection.complete(plan.placements().size(), completeCount);
        }

        if (completeCount == 0) {
            GroundedSweepLane lane0 = lanes.getFirst();
            return ResumeSelection.resumePoint(buildPoint(
                    lane0,
                    lane0.startPoint(),
                    GroundedSweepResumePoint.Reason.FRESH_START,
                    countUnfinishedInLane(statuses, lane0),
                    countUnfinishedInLane(statuses, lane0)
            ), plan.placements().size(), completeCount, unfinishedCount);
        }

        List<CandidateScore> candidateScores = buildCandidateScores(statuses, lanes, playerPosition);
        CandidateScore selected = pickBestCandidate(candidateScores, DEFAULT_DISTANCE_TIE_TOLERANCE);
        if (selected == null) {
            GroundedSweepLane firstLane = lanes.getFirst();
            return ResumeSelection.resumePoint(buildPoint(
                    firstLane,
                    firstLane.startPoint(),
                    GroundedSweepResumePoint.Reason.FIRST_UNFINISHED,
                    0,
                    countUnfinishedInLane(statuses, firstLane)
            ), plan.placements().size(), completeCount, unfinishedCount);
        }

        return ResumeSelection.resumePoint(selected.point(), plan.placements().size(), completeCount, unfinishedCount);
    }

    private static List<CandidateScore> buildCandidateScores(List<LanePlacementStatus> statuses, List<GroundedSweepLane> lanes, Vec3d playerPosition) {
        List<CandidateScore> candidates = new ArrayList<>();
        for (GroundedSweepLane lane : lanes) {
            List<LanePlacementStatus> lanePlacements = placementsInLane(statuses, lane);
            if (lanePlacements.isEmpty()) {
                continue;
            }

            List<LanePlacementStatus> unfinished = lanePlacements.stream().filter(s -> !s.complete()).toList();
            if (unfinished.isEmpty()) {
                continue;
            }

            int unfinishedInLane = unfinished.size();

            GroundedSweepResumePoint laneStart = buildPoint(
                    lane,
                    lane.startPoint(),
                    GroundedSweepResumePoint.Reason.FIRST_UNFINISHED,
                    unfinishedInLane,
                    unfinishedInLane
            );
            candidates.add(new CandidateScore(laneStart, distance(playerPosition, laneStart.standingPosition()), sweepOrderKey(laneStart)));

            LanePlacementStatus earliest = unfinished.stream()
                    .min(Comparator.comparingInt(status -> progressOrderKey(lane.direction(), status.worldPos())))
                    .orElseThrow();
            int usefulAhead = unfinished.stream()
                    .mapToInt(status -> progressOrderKey(lane.direction(), status.worldPos()) >= progressOrderKey(lane.direction(), earliest.worldPos()) ? 1 : 0)
                    .sum();

            GroundedSweepResumePoint partial = buildPoint(
                    lane,
                    projectToLaneCenterline(lane, earliest.worldPos()),
                    GroundedSweepResumePoint.Reason.PARTIAL_LANE,
                    usefulAhead,
                    unfinishedInLane
            );
            candidates.add(new CandidateScore(partial, distance(playerPosition, partial.standingPosition()), sweepOrderKey(partial)));
        }
        return candidates;
    }

    private static CandidateScore pickBestCandidate(List<CandidateScore> candidates, double tieTolerance) {
        if (candidates.isEmpty()) {
            return null;
        }

        List<CandidateScore> perLaneBest = candidates.stream()
                .collect(java.util.stream.Collectors.toMap(
                        candidate -> candidate.point().laneIndex(),
                        candidate -> candidate,
                        (left, right) -> left.distanceToPlayer() <= right.distanceToPlayer() ? left : right
                ))
                .values()
                .stream()
                .toList();

        CandidateScore bestByDistance = perLaneBest.stream()
                .min(Comparator.comparingDouble(CandidateScore::distanceToPlayer))
                .orElseThrow();

        return perLaneBest.stream()
                .filter(candidate -> Math.abs(candidate.distanceToPlayer() - bestByDistance.distanceToPlayer()) <= tieTolerance)
                .min(Comparator.comparingDouble(CandidateScore::sweepOrderKey))
                .orElse(bestByDistance)
                .withReason(GroundedSweepResumePoint.Reason.CLOSEST_USEFUL);
    }

    private static GroundedSweepResumePoint buildPoint(
            GroundedSweepLane lane,
            BlockPos buildPlanePosition,
            GroundedSweepResumePoint.Reason reason,
            int usefulCount,
            int unfinishedCount
    ) {
        int progressCoordinate = lane.direction().alongX() ? buildPlanePosition.getX() : buildPlanePosition.getZ();
        BlockPos standing = new BlockPos(buildPlanePosition.getX(), buildPlanePosition.getY() + 1, buildPlanePosition.getZ());
        return new GroundedSweepResumePoint(
                lane.laneIndex(),
                GroundedSweepResumePoint.SweepPhase.FORWARD,
                lane.direction(),
                lane.centerlineCoordinate(),
                progressCoordinate,
                standing,
                lane.direction().yawDegrees(),
                reason,
                usefulCount,
                unfinishedCount
        );
    }

    private static List<LanePlacementStatus> placementsInLane(List<LanePlacementStatus> statuses, GroundedSweepLane lane) {
        GroundedLaneCorridorBounds corridor = lane.corridorBounds();
        return statuses.stream()
                .filter(status -> withinCorridor(corridor, status.worldPos()))
                .toList();
    }

    private static int countUnfinishedInLane(List<LanePlacementStatus> statuses, GroundedSweepLane lane) {
        return (int) placementsInLane(statuses, lane).stream().filter(status -> !status.complete()).count();
    }

    private static boolean withinCorridor(GroundedLaneCorridorBounds corridor, BlockPos pos) {
        return pos.getX() >= corridor.minX() && pos.getX() <= corridor.maxX()
                && pos.getZ() >= corridor.minZ() && pos.getZ() <= corridor.maxZ();
    }

    private static BlockPos projectToLaneCenterline(GroundedSweepLane lane, BlockPos worldPos) {
        if (lane.direction().alongX()) {
            return new BlockPos(worldPos.getX(), lane.startPoint().getY(), lane.centerlineCoordinate());
        }
        return new BlockPos(lane.centerlineCoordinate(), lane.startPoint().getY(), worldPos.getZ());
    }

    private static int progressOrderKey(GroundedLaneDirection direction, BlockPos pos) {
        int progressCoordinate = direction.alongX() ? pos.getX() : pos.getZ();
        return progressCoordinate * direction.forwardSign();
    }

    private static double sweepOrderKey(GroundedSweepResumePoint point) {
        return (point.laneIndex() * 1_000_000.0) + (point.progressCoordinate() * point.laneDirection().forwardSign());
    }

    private static double distance(Vec3d playerPosition, BlockPos target) {
        return playerPosition.distanceTo(Vec3d.ofCenter(target));
    }

    public interface PlacementCompletionLookup {
        boolean isPlacementComplete(Placement placement, BlockPos worldPosition);

        static PlacementCompletionLookup blockEquality(WorldBlockLookup worldBlockLookup) {
            Objects.requireNonNull(worldBlockLookup, "worldBlockLookup");
            return (placement, worldPosition) -> {
                Block expected = placement.block();
                if (expected == null) {
                    return false;
                }
                // TODO: compare full block state, not only block identity.
                return worldBlockLookup.blockAt(worldPosition) == expected;
            };
        }
    }

    public interface WorldBlockLookup {
        Block blockAt(BlockPos worldPosition);
    }

    public record ResumeSelection(
            Optional<GroundedSweepResumePoint> resumePoint,
            boolean complete,
            int totalPlacements,
            int completePlacements,
            int unfinishedPlacements
    ) {
        static ResumeSelection resumePoint(
                GroundedSweepResumePoint resumePoint,
                int totalPlacements,
                int completePlacements,
                int unfinishedPlacements
        ) {
            return new ResumeSelection(Optional.of(resumePoint), false, totalPlacements, completePlacements, unfinishedPlacements);
        }

        static ResumeSelection complete(int totalPlacements, int completePlacements) {
            return new ResumeSelection(Optional.empty(), true, totalPlacements, completePlacements, 0);
        }
    }

    private record LanePlacementStatus(int index, Placement placement, BlockPos worldPos, boolean complete) {}

    private record CandidateScore(GroundedSweepResumePoint point, double distanceToPlayer, double sweepOrderKey) {
        CandidateScore withReason(GroundedSweepResumePoint.Reason reason) {
            return new CandidateScore(
                    new GroundedSweepResumePoint(
                            point.laneIndex(),
                            point.sweepPhase(),
                            point.laneDirection(),
                            point.centerlineCoordinate(),
                            point.progressCoordinate(),
                            point.standingPosition(),
                            point.yawDegrees(),
                            reason,
                            point.usefulPlacementCount(),
                            point.unfinishedPlacementCount()
                    ),
                    distanceToPlayer,
                    sweepOrderKey
            );
        }
    }
}
