package com.example.mapart.plan.sweep;

import com.example.mapart.plan.Placement;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SweepPassControllerTest {
    private final LanePlanner lanePlanner = new LanePlanner();

    @Test
    void progressesFromPrepareToActiveThenCompletesAtEndpoint() {
        BuildLane lane = lanes().getFirst();
        BuildPlaneModel model = modelFor(List.of(placement(2, lane.fixedCoordinate())));
        SweepPassController controller = new SweepPassController(
                0,
                model,
                lane,
                new SweepPlacementController(SweepPlacementControllerSettings.defaults()),
                candidate -> PlacementAttemptResult.placed(),
                new SweepPassControllerSettings(2, 1, 32)
        );

        assertEquals(SweepPassState.PREPARE, controller.state());
        controller.tick(new Vec3d(1.5, 70.5, lane.fixedCoordinate() + 0.5));
        assertEquals(SweepPassState.ACTIVE, controller.state());

        controller.tick(new Vec3d(lane.endPoint().getX() + 0.5, lane.endPoint().getY() + 0.5, lane.endPoint().getZ() + 0.5));

        assertEquals(SweepPassState.COMPLETE, controller.state());
    }

    @Test
    void doesNotCompleteEarlyWhenCandidateMomentarilyEmpty() {
        BuildLane lane = lanes().getFirst();
        BuildPlaneModel model = modelFor(List.of(placement(5, lane.fixedCoordinate())));
        SweepPassController controller = new SweepPassController(
                1,
                model,
                lane,
                new SweepPlacementController(new SweepPlacementControllerSettings(0.25, 0, 2, 1, 3, 4)),
                candidate -> PlacementAttemptResult.placed(),
                new SweepPassControllerSettings(2, 1, 32)
        );

        controller.tick(new Vec3d(0.5, 70.5, lane.fixedCoordinate() + 0.5));
        controller.tick(new Vec3d(0.5, 70.5, lane.fixedCoordinate() + 0.5));
        controller.tick(new Vec3d(0.5, 70.5, lane.fixedCoordinate() + 0.5));

        assertEquals(SweepPassState.ACTIVE, controller.state());
        assertTrue(controller.currentProgress() > lane.minProgress());
    }

    @Test
    void tracksDeferredMissedAndSkippedPlacements() {
        BuildLane lane = lanes().getFirst();
        BuildPlaneModel model = modelFor(List.of(
                placement(4, lane.fixedCoordinate()),
                placement(0, lane.fixedCoordinate())
        ));
        Map<Integer, Integer> attemptCounts = new HashMap<>();
        SweepPassController controller = new SweepPassController(
                2,
                model,
                lane,
                new SweepPlacementController(SweepPlacementControllerSettings.defaults()),
                candidate -> {
                    int attempt = attemptCounts.getOrDefault(candidate.placementIndex(), 0);
                    attemptCounts.put(candidate.placementIndex(), attempt + 1);
                    if (candidate.placementIndex() == 0 && attempt == 0) {
                        return PlacementAttemptResult.failed("blocked");
                    }
                    return PlacementAttemptResult.skipped("deferred");
                },
                new SweepPassControllerSettings(2, 1, 32)
        );

        controller.tick(new Vec3d(4.5, 70.5, lane.fixedCoordinate() + 0.5));
        controller.tick(new Vec3d(4.5, 70.5, lane.fixedCoordinate() + 0.5));

        SweepPassResult result = controller.result();
        assertEquals(1, result.missedCount());
        assertEquals(2, result.deferredCount());
        assertEquals(1, result.skippedCount());
    }

    @Test
    void limitsRepeatedAttemptsForImpossibleTarget() {
        BuildLane lane = lanes().getFirst();
        BuildPlaneModel model = modelFor(List.of(placement(3, lane.fixedCoordinate())));
        Map<Integer, Integer> attempts = new HashMap<>();
        SweepPassController controller = new SweepPassController(
                3,
                model,
                lane,
                new SweepPlacementController(SweepPlacementControllerSettings.defaults()),
                candidate -> {
                    attempts.merge(candidate.placementIndex(), 1, Integer::sum);
                    return PlacementAttemptResult.failed("still impossible");
                },
                new SweepPassControllerSettings(2, 1, 32)
        );

        for (int i = 0; i < 6; i++) {
            controller.tick(new Vec3d(3.5, 70.5, lane.fixedCoordinate() + 0.5));
        }

        assertEquals(2, attempts.getOrDefault(0, 0));
        assertTrue(controller.result().exhaustedPlacementIndices().contains(0));
    }

    @Test
    void resultSummaryIncludesLeftoversAndFinalState() {
        BuildLane lane = lanes().getFirst();
        BuildPlaneModel model = modelFor(List.of(placement(2, lane.fixedCoordinate())));

        SweepPassController controller = new SweepPassController(
                4,
                model,
                lane,
                new SweepPlacementController(SweepPlacementControllerSettings.defaults()),
                candidate -> PlacementAttemptResult.failed("miss"),
                new SweepPassControllerSettings(1, 1, 32)
        );

        controller.tick(new Vec3d(2.5, 70.5, lane.fixedCoordinate() + 0.5));
        controller.interrupt();

        SweepPassResult result = controller.result();
        assertEquals(SweepPassState.INTERRUPTED, result.finalState());
        assertEquals(0, result.successCount());
        assertEquals(1, result.missedCount());
        assertEquals(List.of(0), result.leftoverPlacementIndices());
    }

    private BuildPlaneModel modelFor(List<Placement> placements) {
        return new BuildPlaneModel(lanes(), placements, index -> false);
    }

    private List<BuildLane> lanes() {
        return lanePlanner.planLanes(
                new BlockPos(0, 70, 0),
                new BlockPos(8, 70, 4),
                new LanePlannerSettings(2, 2, 1.0)
        );
    }

    private static Placement placement(int x, int z) {
        return new Placement(new BlockPos(x, 70, z), block());
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
