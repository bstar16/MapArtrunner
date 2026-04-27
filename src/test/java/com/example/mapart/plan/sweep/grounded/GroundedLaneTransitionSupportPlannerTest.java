package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundedLaneTransitionSupportPlannerTest {

    @Test
    void eastWestTransitionBuildsZBridgeBetweenCenterlines() {
        BuildPlan plan = filledPlan(new Vec3i(5, 1, 5));
        BlockPos origin = new BlockPos(10, 64, 10);
        GroundedSchematicBounds bounds = GroundedSchematicBounds.fromPlan(plan, origin);
        GroundedSweepLane from = new GroundedSweepLane(0, 11, GroundedLaneDirection.EAST,
                new BlockPos(10, 64, 11), new BlockPos(14, 64, 11), new GroundedLaneCorridorBounds(10, 14, 10, 14), 1.0);
        GroundedSweepLane to = new GroundedSweepLane(1, 13, GroundedLaneDirection.WEST,
                new BlockPos(14, 64, 13), new BlockPos(10, 64, 13), new GroundedLaneCorridorBounds(10, 14, 12, 14), 1.0);

        List<GroundedLaneTransitionSupportPlanner.SupportTarget> targets =
                GroundedSingleLaneDebugRunner.buildLaneTransitionSupportTargetsForTests(plan, origin, bounds, from, to);

        assertFalse(targets.isEmpty());
        assertTrue(targets.stream().allMatch(t -> t.worldPos().getZ() >= 11 && t.worldPos().getZ() <= 13));
        assertTrue(targets.stream().anyMatch(t -> t.worldPos().getZ() == 12));
    }

    @Test
    void northSouthTransitionBuildsXBridgeBetweenCenterlines() {
        BuildPlan plan = filledPlan(new Vec3i(5, 1, 5));
        BlockPos origin = new BlockPos(10, 64, 10);
        GroundedSchematicBounds bounds = GroundedSchematicBounds.fromPlan(plan, origin);
        GroundedSweepLane from = new GroundedSweepLane(0, 11, GroundedLaneDirection.SOUTH,
                new BlockPos(11, 64, 10), new BlockPos(11, 64, 14), new GroundedLaneCorridorBounds(10, 12, 10, 14), 1.0);
        GroundedSweepLane to = new GroundedSweepLane(1, 13, GroundedLaneDirection.NORTH,
                new BlockPos(13, 64, 14), new BlockPos(13, 64, 10), new GroundedLaneCorridorBounds(12, 14, 10, 14), 1.0);

        List<GroundedLaneTransitionSupportPlanner.SupportTarget> targets =
                GroundedSingleLaneDebugRunner.buildLaneTransitionSupportTargetsForTests(plan, origin, bounds, from, to);

        assertFalse(targets.isEmpty());
        assertTrue(targets.stream().allMatch(t -> t.worldPos().getX() >= 11 && t.worldPos().getX() <= 13));
        assertTrue(targets.stream().anyMatch(t -> t.worldPos().getX() == 12));
    }

    @Test
    void transitionSupportTargetsStayInsideBounds() {
        BuildPlan plan = filledPlan(new Vec3i(2, 1, 2));
        BlockPos origin = new BlockPos(10, 64, 10);
        GroundedSchematicBounds bounds = GroundedSchematicBounds.fromPlan(plan, origin);
        GroundedSweepLane from = new GroundedSweepLane(0, 10, GroundedLaneDirection.EAST,
                new BlockPos(10, 64, 10), new BlockPos(11, 64, 10), new GroundedLaneCorridorBounds(10, 11, 10, 10), 1.0);
        GroundedSweepLane to = new GroundedSweepLane(1, 11, GroundedLaneDirection.WEST,
                new BlockPos(11, 64, 11), new BlockPos(10, 64, 11), new GroundedLaneCorridorBounds(10, 11, 11, 11), 1.0);

        List<GroundedLaneTransitionSupportPlanner.SupportTarget> targets =
                GroundedSingleLaneDebugRunner.buildLaneTransitionSupportTargetsForTests(plan, origin, bounds, from, to);

        assertTrue(targets.stream().allMatch(t -> t.worldPos().getX() >= bounds.minX() && t.worldPos().getX() <= bounds.maxX()));
        assertTrue(targets.stream().allMatch(t -> t.worldPos().getZ() >= bounds.minZ() && t.worldPos().getZ() <= bounds.maxZ()));
    }

    private static BuildPlan filledPlan(Vec3i dimensions) {
        List<Placement> placements = new ArrayList<>();
        for (int z = 0; z < dimensions.getZ(); z++) {
            for (int x = 0; x < dimensions.getX(); x++) {
                placements.add(new Placement(new BlockPos(x, 0, z), null));
            }
        }
        return new BuildPlan("test", Path.of("plan.schem"), dimensions, placements, Map.of(), List.of());
    }
}
