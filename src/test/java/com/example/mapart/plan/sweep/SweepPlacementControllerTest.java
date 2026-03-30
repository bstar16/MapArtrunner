package com.example.mapart.plan.sweep;

import com.example.mapart.plan.Placement;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SweepPlacementControllerTest {

    private final LanePlanner lanePlanner = new LanePlanner();

    @Test
    void filtersToIncompleteCandidatesWithinActiveLaneRules() {
        BuildLane activeLane = xAxisLanes().getFirst();
        BuildPlaneModel model = new BuildPlaneModel(xAxisLanes(), placements(), index -> index == 0);
        SweepPlacementController controller = new SweepPlacementController(defaultSettings());

        SweepPlacementSelection selection = controller.selectCandidates(
                model,
                activeLane,
                snapshot(activeLane, 2),
                new Vec3d(2.5, 0.5, 0.5)
        );

        assertEquals(SweepPlacementAction.PLACE_TOP_CANDIDATE, selection.action());
        assertTrue(selection.rankedCandidates().stream().noneMatch(candidate -> candidate.placementIndex() == 0));
        assertTrue(selection.rankedCandidates().stream().allMatch(candidate -> candidate.laneBand() != LaneBandClassification.OUTSIDE));
    }

    @Test
    void prioritizesPrimaryBandOverEdgeBandWhenProgressRelationMatches() {
        BuildLane activeLane = xAxisLanes().getFirst();
        BuildPlaneModel model = modelForPlacements(List.of(
                placement(2, activeLane.fixedCoordinate()),
                placement(2, activeLane.fixedCoordinate() + 3)
        ));
        SweepPlacementController controller = new SweepPlacementController(defaultSettings());

        SweepPlacementSelection selection = controller.selectCandidates(
                model,
                activeLane,
                snapshot(activeLane, 2),
                new Vec3d(2.5, 0.5, activeLane.fixedCoordinate() + 2.5)
        );

        assertEquals(2, selection.rankedCandidates().size());
        assertEquals(LaneBandClassification.PRIMARY, selection.rankedCandidates().get(0).laneBand());
        assertEquals(LaneBandClassification.EDGE, selection.rankedCandidates().get(1).laneBand());
    }

    @Test
    void ranksNearThenAheadThenTrivialBehind() {
        BuildLane activeLane = xAxisLanes().getFirst();
        BuildPlaneModel model = modelForPlacements(List.of(
                placement(4, activeLane.fixedCoordinate()),
                placement(5, activeLane.fixedCoordinate()),
                placement(3, activeLane.fixedCoordinate())
        ));
        SweepPlacementController controller = new SweepPlacementController(defaultSettings());

        SweepPlacementSelection selection = controller.selectCandidates(
                model,
                activeLane,
                snapshot(activeLane, 4),
                new Vec3d(4.5, 0.5, 0.5)
        );

        assertEquals(ProgressRelation.NEAR_CURRENT, selection.rankedCandidates().get(0).progressRelation());
        assertEquals(ProgressRelation.SLIGHTLY_AHEAD, selection.rankedCandidates().get(1).progressRelation());
        assertEquals(ProgressRelation.SLIGHTLY_BEHIND, selection.rankedCandidates().get(2).progressRelation());
    }

    @Test
    void rankingIsDeterministicForEquivalentDistances() {
        BuildLane activeLane = xAxisLanes().getFirst();
        BuildPlaneModel model = modelForPlacements(List.of(
                placement(2, activeLane.fixedCoordinate()),
                placement(2, activeLane.fixedCoordinate()),
                placement(2, activeLane.fixedCoordinate())
        ));
        SweepPlacementController controller = new SweepPlacementController(defaultSettings());

        SweepPlacementSelection selection = controller.selectCandidates(
                model,
                activeLane,
                snapshot(activeLane, 2),
                new Vec3d(2.5, 0.5, 0.5)
        );

        assertEquals(List.of(0, 1, 2), selection.rankedCandidates().stream().map(SweepPlacementCandidate::placementIndex).toList());
    }

    @Test
    void excludesOutOfRangePlacements() {
        BuildLane activeLane = xAxisLanes().getFirst();
        BuildPlaneModel model = modelForPlacements(List.of(
                placement(2, activeLane.fixedCoordinate()),
                placement(7, activeLane.fixedCoordinate())
        ));
        SweepPlacementController controller = new SweepPlacementController(new SweepPlacementControllerSettings(
                2.0,
                0,
                6,
                1,
                4,
                8
        ));

        SweepPlacementSelection selection = controller.selectCandidates(
                model,
                activeLane,
                snapshot(activeLane, 2),
                new Vec3d(2.5, 0.5, 0.5)
        );

        assertEquals(1, selection.rankedCandidates().size());
        assertEquals(0, selection.rankedCandidates().getFirst().placementIndex());
    }

    @Test
    void defersFarBehindPlacementsInsteadOfChasingThem() {
        BuildLane activeLane = xAxisLanes().getFirst();
        BuildPlaneModel model = modelForPlacements(List.of(placement(0, activeLane.fixedCoordinate())));
        SweepPlacementController controller = new SweepPlacementController(defaultSettings());

        SweepPlacementSelection selection = controller.selectCandidates(
                model,
                activeLane,
                snapshot(activeLane, 4),
                new Vec3d(0.5, 0.5, 0.5)
        );

        assertEquals(SweepPlacementAction.DEFER_AND_ADVANCE, selection.action());
        assertTrue(selection.rankedCandidates().isEmpty());
        assertEquals(1, selection.deferredCandidates().size());
        assertEquals(ProgressRelation.FAR_BEHIND, selection.deferredCandidates().getFirst().progressRelation());
    }

    @Test
    void returnsStructuredSelectionResult() {
        BuildLane activeLane = xAxisLanes().getFirst();
        BuildPlaneModel model = modelForPlacements(List.of(
                placement(4, activeLane.fixedCoordinate()),
                placement(0, activeLane.fixedCoordinate())
        ));
        SweepPlacementController controller = new SweepPlacementController(defaultSettings());

        SweepPlacementSelection selection = controller.selectCandidates(
                model,
                activeLane,
                snapshot(activeLane, 4),
                new Vec3d(4.5, 0.5, 0.5)
        );

        assertEquals(2, selection.totalIncompleteInLane());
        assertEquals(1, selection.rankedCandidates().size());
        assertEquals(1, selection.deferredCandidates().size());
        assertEquals(SweepPlacementAction.PLACE_TOP_CANDIDATE, selection.action());
    }

    private SweepPlacementControllerSettings defaultSettings() {
        return new SweepPlacementControllerSettings(6.0, 0, 2, 1, 3, 8);
    }

    private PassProgressSnapshot snapshot(BuildLane lane, int currentProgress) {
        return new PassProgressSnapshot(lane.laneIndex(), SweepPassState.ACTIVE, lane.maxProgress(), currentProgress, 0);
    }

    private BuildPlaneModel modelForPlacements(List<Placement> placements) {
        return new BuildPlaneModel(xAxisLanes(), placements, index -> false);
    }

    private List<BuildLane> xAxisLanes() {
        return lanePlanner.planLanes(
                new BlockPos(0, 70, 0),
                new BlockPos(8, 70, 4),
                new LanePlannerSettings(2, 2, 1.0)
        );
    }

    private static Placement placement(int x, int z) {
        return new Placement(new BlockPos(x, 0, z), block());
    }

    private static List<Placement> placements() {
        return List.of(
                placement(2, 0),
                placement(3, 0),
                placement(3, 1),
                placement(9, 0)
        );
    }

    private static Block block() {
        try {
            java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            return (Block) unsafe.allocateInstance(Block.class);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to allocate block", exception);
        }
    }
}
