package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GroundedSweepLanePlannerTest {

    private final GroundedSweepLanePlanner planner = new GroundedSweepLanePlanner();

    @Test
    void usesDeterministicNorthWestAnchoredStart() {
        GroundedSweepSettings settings = GroundedSweepSettings.defaults();
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(100, 64, 200),
                new BlockPos(100, 64, 200),
                new BlockPos(110, 64, 209)
        );

        List<GroundedSweepLane> lanes = planner.planLanes(bounds, settings);

        assertEquals(new BlockPos(100, 64, 202), lanes.getFirst().startPoint());
    }

    @Test
    void usesEastWestOrientationWhenXSpanIsLonger() {
        GroundedSweepSettings settings = new GroundedSweepSettings(2, 5, 5, 1, 1, true, 1.0);
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(14, 64, 8)
        );

        List<GroundedSweepLane> lanes = planner.planLanes(bounds, settings);

        assertEquals(GroundedLaneDirection.EAST, lanes.getFirst().direction());
        assertEquals(new BlockPos(0, 64, 2), lanes.getFirst().startPoint());
        assertEquals(new BlockPos(14, 64, 2), lanes.getFirst().endPoint());
    }

    @Test
    void usesEastWestOrientationEvenWhenZSpanIsLonger() {
        GroundedSweepSettings settings = new GroundedSweepSettings(2, 5, 5, 1, 1, true, 1.0);
        GroundedSchematicBounds zLongerBounds = new GroundedSchematicBounds(
                new BlockPos(10, 64, 10),
                new BlockPos(10, 64, 10),
                new BlockPos(15, 64, 24)
        );

        List<GroundedSweepLane> lanes = planner.planLanes(zLongerBounds, settings);

        assertEquals(GroundedLaneDirection.EAST, lanes.getFirst().direction());
        assertEquals(new BlockPos(10, 64, 12), lanes.getFirst().startPoint());
        assertEquals(new BlockPos(15, 64, 12), lanes.getFirst().endPoint());
    }

    @Test
    void alternatesSerpentineDirection() {
        GroundedSweepSettings settings = GroundedSweepSettings.defaults();
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(20, 64, 14)
        );

        List<GroundedSweepLane> lanes = planner.planLanes(bounds, settings);

        assertEquals(GroundedLaneDirection.EAST, lanes.get(0).direction());
        assertEquals(GroundedLaneDirection.WEST, lanes.get(1).direction());
        assertEquals(GroundedLaneDirection.EAST, lanes.get(2).direction());
    }

    @Test
    void computesLaneCountAndClampsFinalLaneForUnevenDimensions() {
        GroundedSweepSettings settings = GroundedSweepSettings.defaults();
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(15, 64, 10)
        );

        List<GroundedSweepLane> lanes = planner.planLanes(bounds, settings);

        assertEquals(3, lanes.size());
        assertEquals(2, lanes.get(0).centerlineCoordinate());
        assertEquals(7, lanes.get(1).centerlineCoordinate());
        assertEquals(8, lanes.get(2).centerlineCoordinate());
    }

    @Test
    void generatesExpectedFiveWideCorridorBounds() {
        GroundedSweepSettings settings = GroundedSweepSettings.defaults();
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(50, 70, 50),
                new BlockPos(50, 70, 50),
                new BlockPos(58, 70, 58)
        );

        List<GroundedSweepLane> lanes = planner.planLanes(bounds, settings);
        GroundedLaneCorridorBounds firstCorridor = lanes.getFirst().corridorBounds();

        assertEquals(50, firstCorridor.minX());
        assertEquals(58, firstCorridor.maxX());
        assertEquals(50, firstCorridor.minZ());
        assertEquals(54, firstCorridor.maxZ());
    }
}
