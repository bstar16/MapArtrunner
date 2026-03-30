package com.example.mapart.plan.sweep;

import com.example.mapart.plan.Placement;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntPredicate;

public final class BuildPlaneModel {
    private final List<BuildLane> lanes;
    private final List<Placement> placements;
    private final IntPredicate completedPlacementIndex;
    private final LaneCoordinateMapper coordinateMapper;
    private final Map<Integer, BuildLane> laneByFixedCoordinate;
    private final Map<Integer, List<LanePlacement>> lanePlacements;

    public BuildPlaneModel(List<BuildLane> lanes, List<Placement> placements, IntPredicate completedPlacementIndex) {
        this(lanes, placements, completedPlacementIndex, new LaneCoordinateMapper());
    }

    public BuildPlaneModel(List<BuildLane> lanes,
                           List<Placement> placements,
                           IntPredicate completedPlacementIndex,
                           LaneCoordinateMapper coordinateMapper) {
        this.lanes = List.copyOf(lanes);
        this.placements = List.copyOf(placements);
        this.completedPlacementIndex = completedPlacementIndex;
        this.coordinateMapper = coordinateMapper;

        validateLanes(this.lanes);

        this.laneByFixedCoordinate = new HashMap<>();
        for (BuildLane lane : this.lanes) {
            laneByFixedCoordinate.put(lane.fixedCoordinate(), lane);
        }

        Map<Integer, List<LanePlacement>> mutableLanePlacements = new HashMap<>();
        for (BuildLane lane : this.lanes) {
            mutableLanePlacements.put(lane.laneIndex(), new ArrayList<>());
        }

        for (int i = 0; i < this.placements.size(); i++) {
            Placement placement = this.placements.get(i);
            BlockPos relativePos = placement.relativePos();
            BuildLane lane = laneByFixedCoordinate.get(coordinateMapper.placementSweepCoordinate(this.lanes.getFirst(), relativePos));
            if (lane == null) {
                continue;
            }

            int progress = coordinateMapper.placementProgress(lane, relativePos);
            mutableLanePlacements.get(lane.laneIndex()).add(new LanePlacement(i, placement, lane.laneIndex(), progress));
        }

        this.lanePlacements = new HashMap<>();
        for (Map.Entry<Integer, List<LanePlacement>> entry : mutableLanePlacements.entrySet()) {
            List<LanePlacement> sorted = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(LanePlacement::placementIndex))
                    .toList();
            lanePlacements.put(entry.getKey(), sorted);
        }
    }

    public List<LanePlacement> placementsForLane(int laneIndex) {
        return lanePlacements.getOrDefault(laneIndex, List.of());
    }

    public List<LanePlacement> incompletePlacementsForLane(int laneIndex) {
        return placementsForLane(laneIndex).stream()
                .filter(placement -> !completedPlacementIndex.test(placement.placementIndex()))
                .toList();
    }

    public List<LanePlacement> incompletePlacements() {
        return lanePlacements.values().stream()
                .flatMap(List::stream)
                .filter(placement -> !completedPlacementIndex.test(placement.placementIndex()))
                .toList();
    }

    public double projectProgress(BuildLane lane, Vec3d position) {
        return coordinateMapper.projectProgress(lane, position);
    }

    public double lateralOffsetFromCenter(BuildLane lane, Vec3d position) {
        return coordinateMapper.lateralOffsetFromCenter(lane, position);
    }

    public double distanceToLaneEndpoint(BuildLane lane, Vec3d position) {
        return coordinateMapper.distanceToEndpoint(lane, position);
    }

    public PlacementProgressRelation classifyAheadBehind(BuildLane lane, Placement placement, double currentProgress) {
        int placementProgress = coordinateMapper.placementProgress(lane, placement.relativePos());
        if (lane.direction() == LaneDirection.FORWARD) {
            return classifyForForward(currentProgress, placementProgress);
        }
        return classifyForReverse(currentProgress, placementProgress);
    }

    public LaneWidthBand classifyWidthBand(BuildLane lane, Placement placement) {
        int sweepCoordinate = coordinateMapper.placementSweepCoordinate(lane, placement.relativePos());
        return classifyWidthBandFromAbsoluteOffset(lane, Math.abs(sweepCoordinate - lane.fixedCoordinate()));
    }

    public LaneWidthBand classifyWidthBand(BuildLane lane, double lateralOffset) {
        return classifyWidthBandFromAbsoluteOffset(lane, Math.abs(lateralOffset));
    }

    private LaneWidthBand classifyWidthBandFromAbsoluteOffset(BuildLane lane, double absoluteOffset) {
        if (absoluteOffset <= lane.primaryHalfWidth()) {
            return LaneWidthBand.PRIMARY;
        }

        if (absoluteOffset <= lane.primaryHalfWidth() + lane.edgeHalfWidth()) {
            return LaneWidthBand.EDGE;
        }

        return LaneWidthBand.OUTSIDE;
    }

    private static PlacementProgressRelation classifyForForward(double currentProgress, int placementProgress) {
        if (placementProgress > currentProgress) {
            return PlacementProgressRelation.AHEAD;
        }
        if (placementProgress < currentProgress) {
            return PlacementProgressRelation.BEHIND;
        }
        return PlacementProgressRelation.AT_PROGRESS;
    }

    private static PlacementProgressRelation classifyForReverse(double currentProgress, int placementProgress) {
        if (placementProgress < currentProgress) {
            return PlacementProgressRelation.AHEAD;
        }
        if (placementProgress > currentProgress) {
            return PlacementProgressRelation.BEHIND;
        }
        return PlacementProgressRelation.AT_PROGRESS;
    }

    private static void validateLanes(List<BuildLane> lanes) {
        if (lanes.isEmpty()) {
            return;
        }

        LaneAxis axis = lanes.getFirst().axis();
        for (BuildLane lane : lanes) {
            if (lane.axis() != axis) {
                throw new IllegalArgumentException("all lanes must use the same axis");
            }
        }
    }

    public record LanePlacement(int placementIndex, Placement placement, int laneIndex, int progress) {
        public LanePlacement {
            Objects.requireNonNull(placement, "placement");
        }
    }
}
