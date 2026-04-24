package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.sweep.LaneAxis;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundedLanePlannerTest {

    private final GroundedLanePlanner planner = new GroundedLanePlanner();

    @Test
    void deterministicNorthWestAnchoredStart() {
        GroundedLanePlan plan = planner.plan(bounds(100, 64, 200, 16, 1, 13), GroundedSweepSettings.defaults());
        GroundedSweepLane lane0 = plan.lanes().getFirst();

        assertEquals(new BlockPos(100, 64, 202), lane0.startPoint());
        assertEquals(GroundedSweepDirection.EAST, lane0.direction());
    }

    @Test
    void preferLongerAxisFalseUsesEastWestFirstPass() {
        GroundedSweepSettings settings = new GroundedSweepSettings(false, 2, 5, 5, 1, 1, true, 1.0);

        GroundedLanePlan plan = planner.plan(bounds(0, 70, 0, 6, 1, 20), settings);

        assertEquals(LaneAxis.X, plan.primaryAxis());
        assertEquals(GroundedSweepDirection.EAST, plan.lanes().getFirst().direction());
    }

    @Test
    void preferLongerAxisTrueSwitchesToNorthSouthWhenZIsLonger() {
        GroundedSweepSettings settings = new GroundedSweepSettings(true, 2, 5, 5, 1, 1, true, 1.0);

        GroundedLanePlan zPrimary = planner.plan(bounds(0, 70, 0, 7, 1, 18), settings);
        GroundedLanePlan xPrimary = planner.plan(bounds(0, 70, 0, 18, 1, 7), settings);

        assertEquals(LaneAxis.Z, zPrimary.primaryAxis());
        assertEquals(GroundedSweepDirection.SOUTH, zPrimary.lanes().getFirst().direction());
        assertEquals(LaneAxis.X, xPrimary.primaryAxis());
        assertEquals(GroundedSweepDirection.EAST, xPrimary.lanes().getFirst().direction());
    }

    @Test
    void lanesAlternateInSerpentineOrder() {
        GroundedLanePlan plan = planner.plan(bounds(10, 64, 20, 15, 1, 20), GroundedSweepSettings.defaults());

        assertEquals(GroundedSweepDirection.EAST, plan.lanes().get(0).direction());
        assertEquals(GroundedSweepDirection.WEST, plan.lanes().get(1).direction());
        assertEquals(GroundedSweepDirection.EAST, plan.lanes().get(2).direction());
        assertEquals(GroundedSweepDirection.WEST, plan.lanes().get(3).direction());
    }

    @Test
    void unevenDimensionsClampFinalLaneCenterlineInsideBounds() {
        GroundedLanePlan plan = planner.plan(bounds(50, 80, 75, 40, 1, 14), GroundedSweepSettings.defaults());

        assertEquals(3, plan.lanes().size());
        assertEquals(77, plan.lanes().getFirst().centerlineCoordinate());
        assertEquals(86, plan.lanes().getLast().centerlineCoordinate());
        assertEquals(84, plan.lanes().getLast().corridorMinSweepCoordinate());
        assertEquals(88, plan.lanes().getLast().corridorMaxSweepCoordinate());
        assertEquals(88, plan.bounds().worldMaxInclusive().getZ());
    }

    @Test
    void generatesExactStartAndEndPointsForEachLane() {
        GroundedLanePlan plan = planner.plan(bounds(5, 65, 100, 8, 1, 12), GroundedSweepSettings.defaults());

        GroundedSweepLane lane0 = plan.lanes().get(0);
        GroundedSweepLane lane1 = plan.lanes().get(1);

        assertEquals(new BlockPos(5, 65, 102), lane0.startPoint());
        assertEquals(new BlockPos(12, 65, 102), lane0.endPoint());
        assertEquals(new BlockPos(12, 65, 107), lane1.startPoint());
        assertEquals(new BlockPos(5, 65, 107), lane1.endPoint());
    }

    @Test
    void generatesExplicitFiveWideCorridorBoundsForEveryLane() {
        GroundedLanePlan plan = planner.plan(bounds(0, 64, 0, 20, 1, 20), GroundedSweepSettings.defaults());

        assertTrue(plan.lanes().stream().allMatch(lane -> lane.corridorMaxSweepCoordinate() - lane.corridorMinSweepCoordinate() + 1 == 5));
        assertTrue(plan.lanes().stream().allMatch(lane -> lane.corridorMinSweepCoordinate() >= plan.bounds().worldMinInclusive().getZ()));
        assertTrue(plan.lanes().stream().allMatch(lane -> lane.corridorMaxSweepCoordinate() <= plan.bounds().worldMaxInclusive().getZ()));
    }

    @Test
    void derivesBoundsFromPlanDimensionsAndOrigin() {
        BuildPlan plan = new BuildPlan("test", Path.of("test.schem"), new Vec3i(128, 1, 129), List.of(), Map.of(), List.of());

        GroundedSchematicBounds bounds = GroundedSchematicBounds.from(plan, new BlockPos(1000, 70, -200));

        assertEquals(new BlockPos(1000, 70, -200), bounds.worldMinInclusive());
        assertEquals(new BlockPos(1127, 70, -72), bounds.worldMaxInclusive());
    }

    private static GroundedSchematicBounds bounds(int originX, int originY, int originZ, int xSize, int ySize, int zSize) {
        BuildPlan plan = new BuildPlan("test", Path.of("test.schem"), new Vec3i(xSize, ySize, zSize), List.of(), Map.of(), List.of());
        return GroundedSchematicBounds.from(plan, new BlockPos(originX, originY, originZ));
    }
}
