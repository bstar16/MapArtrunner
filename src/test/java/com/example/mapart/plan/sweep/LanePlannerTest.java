package com.example.mapart.plan.sweep;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanePlannerTest {

    private final LanePlanner planner = new LanePlanner();

    @Test
    void choosesLongerAxisAsLaneProgressAxis() {
        LanePlannerSettings settings = new LanePlannerSettings(4, 2, 1.25);

        List<BuildLane> xPrimary = planner.planLanes(new BlockPos(0, 64, 0), new BlockPos(9, 64, 2), settings);
        List<BuildLane> zPrimary = planner.planLanes(new BlockPos(0, 64, 0), new BlockPos(2, 64, 9), settings);

        assertTrue(xPrimary.stream().allMatch(lane -> lane.axis() == LaneAxis.X));
        assertTrue(zPrimary.stream().allMatch(lane -> lane.axis() == LaneAxis.Z));
    }

    @Test
    void alternatesSerpentineDirectionsByLaneIndex() {
        LanePlannerSettings settings = new LanePlannerSettings(3, 1, 0.5);

        List<BuildLane> lanes = planner.planLanes(new BlockPos(0, 70, 0), new BlockPos(6, 70, 3), settings);

        assertEquals(LaneDirection.FORWARD, lanes.get(0).direction());
        assertEquals(LaneDirection.REVERSE, lanes.get(1).direction());
        assertEquals(LaneDirection.FORWARD, lanes.get(2).direction());
        assertEquals(LaneDirection.REVERSE, lanes.get(3).direction());
    }

    @Test
    void producesLanePerSweepCoordinate() {
        LanePlannerSettings settings = new LanePlannerSettings(2, 1, 0.25);

        List<BuildLane> lanes = planner.planLanes(new BlockPos(5, 80, -2), new BlockPos(10, 80, 2), settings);

        assertEquals(5, lanes.size());
        assertEquals(-2, lanes.getFirst().fixedCoordinate());
        assertEquals(2, lanes.getLast().fixedCoordinate());
    }

    @Test
    void laneEntryAndEndPointsAreConsistentWithDirection() {
        LanePlannerSettings settings = new LanePlannerSettings(1, 1, 0.75);

        List<BuildLane> lanes = planner.planLanes(new BlockPos(2, 70, 10), new BlockPos(8, 70, 12), settings);

        BuildLane forward = lanes.getFirst();
        assertEquals(new BlockPos(2, 70, 10), forward.entryPoint());
        assertEquals(new BlockPos(8, 70, 10), forward.endPoint());

        BuildLane reverse = lanes.get(1);
        assertEquals(new BlockPos(8, 70, 11), reverse.entryPoint());
        assertEquals(new BlockPos(2, 70, 11), reverse.endPoint());
    }

    @Test
    void propagatesWidthAndToleranceToEveryLane() {
        LanePlannerSettings settings = new LanePlannerSettings(6, 2, 1.5);

        List<BuildLane> lanes = planner.planLanes(new BlockPos(0, 64, 0), new BlockPos(7, 64, 1), settings);

        assertTrue(lanes.stream().allMatch(lane -> lane.primaryHalfWidth() == 6));
        assertTrue(lanes.stream().allMatch(lane -> lane.edgeHalfWidth() == 2));
        assertTrue(lanes.stream().allMatch(lane -> lane.endpointTolerance() == 1.5));
    }
}
