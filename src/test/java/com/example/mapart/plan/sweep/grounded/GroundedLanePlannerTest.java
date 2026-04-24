package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GroundedLanePlannerTest {
    private final GroundedLanePlanner planner = new GroundedLanePlanner();

    @Test
    void deterministicNorthWestAnchoredStart() {
        var lanes = plan(12, 11, false);

        GroundedLanePlan first = lanes.getFirst();
        assertEquals(new BlockPos(100, 70, 202), first.startPoint());
        assertEquals(new BlockPos(111, 70, 202), first.endPoint());
        assertEquals(GroundedLaneDirection.EAST, first.direction());
        assertEquals(202, first.centerlineCoordinate());
    }

    @Test
    void preferLongerAxisFalseStartsEastAndAdvancesSouth() {
        var lanes = plan(8, 12, false);

        assertEquals(List.of(GroundedLaneDirection.EAST, GroundedLaneDirection.WEST, GroundedLaneDirection.EAST),
                lanes.stream().map(GroundedLanePlan::direction).toList());
        assertEquals(List.of(202, 207, 209), lanes.stream().map(GroundedLanePlan::centerlineCoordinate).toList());
    }

    @Test
    void preferLongerAxisTrueUsesLongerAxisOrientation() {
        var xLonger = plan(15, 9, true);
        assertEquals(GroundedLaneDirection.EAST, xLonger.getFirst().direction());
        assertEquals(new BlockPos(100, 70, 202), xLonger.getFirst().startPoint());

        var zLonger = plan(9, 15, true);
        assertEquals(GroundedLaneDirection.SOUTH, zLonger.getFirst().direction());
        assertEquals(new BlockPos(102, 70, 200), zLonger.getFirst().startPoint());
    }

    @Test
    void serpentineAlternatesDirections() {
        var alongX = plan(20, 20, false);
        assertEquals(List.of(GroundedLaneDirection.EAST, GroundedLaneDirection.WEST, GroundedLaneDirection.EAST, GroundedLaneDirection.WEST),
                alongX.stream().map(GroundedLanePlan::direction).toList());

        var alongZ = plan(10, 20, true);
        assertEquals(List.of(GroundedLaneDirection.SOUTH, GroundedLaneDirection.NORTH),
                alongZ.stream().map(GroundedLanePlan::direction).toList());
    }

    @Test
    void unevenDimensionsClampFinalLaneCenterline() {
        var lanes = plan(18, 13, false);

        assertEquals(3, lanes.size());
        assertEquals(List.of(202, 207, 210), lanes.stream().map(GroundedLanePlan::centerlineCoordinate).toList());

        GroundedLanePlan finalLane = lanes.getLast();
        assertEquals(208, finalLane.corridorBounds().minZ());
        assertEquals(212, finalLane.corridorBounds().maxZ());
    }

    @Test
    void generatesStartEndAndFiveWideCorridorBounds() {
        var lanes = plan(10, 15, true);

        GroundedLanePlan first = lanes.getFirst();
        assertEquals(new BlockPos(102, 70, 200), first.startPoint());
        assertEquals(new BlockPos(102, 70, 214), first.endPoint());
        assertEquals(100, first.corridorBounds().minX());
        assertEquals(104, first.corridorBounds().maxX());
        assertEquals(200, first.corridorBounds().minZ());
        assertEquals(214, first.corridorBounds().maxZ());
    }

    private List<GroundedLanePlan> plan(int xSpan, int zSpan, boolean preferLongerAxis) {
        GroundedSchematicBounds bounds = GroundedSchematicBounds.from(
                new LoadedPlanBounds(0, xSpan - 1, 0, 0, 0, zSpan - 1),
                new BlockPos(100, 70, 200));

        GroundedSweepSettings settings = new GroundedSweepSettings(preferLongerAxis, 2, 5, 5, 1, 1, true);
        return planner.planLanes(bounds, settings);
    }
}
