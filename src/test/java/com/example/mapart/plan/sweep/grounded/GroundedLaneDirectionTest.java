package com.example.mapart.plan.sweep.grounded;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GroundedLaneDirectionTest {

    @Test
    void usesMinecraftYawMappingForCardinalDirections() {
        assertEquals(-90.0f, GroundedLaneDirection.EAST.yawDegrees());
        assertEquals(90.0f, GroundedLaneDirection.WEST.yawDegrees());
        assertEquals(0.0f, GroundedLaneDirection.SOUTH.yawDegrees());
        assertEquals(180.0f, GroundedLaneDirection.NORTH.yawDegrees());
    }

    @Test
    void exposesDeterministicOppositeDirection() {
        assertEquals(GroundedLaneDirection.WEST, GroundedLaneDirection.EAST.opposite());
        assertEquals(GroundedLaneDirection.EAST, GroundedLaneDirection.WEST.opposite());
        assertEquals(GroundedLaneDirection.NORTH, GroundedLaneDirection.SOUTH.opposite());
        assertEquals(GroundedLaneDirection.SOUTH, GroundedLaneDirection.NORTH.opposite());
    }
}
