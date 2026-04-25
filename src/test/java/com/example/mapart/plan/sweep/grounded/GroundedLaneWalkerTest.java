package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundedLaneWalkerTest {

    @Test
    void walksFromStartToEndAndCompletes() {
        GroundedLaneWalker walker = new GroundedLaneWalker(eastLane(), bounds(), true, 200);

        walker.tick(Vec3d.ofCenter(eastLane().startPoint()));
        assertEquals(GroundedLaneWalkState.ACTIVE, walker.state());
        assertTrue(walker.currentCommand().forwardPressed());

        walker.tick(Vec3d.ofCenter(eastLane().endPoint()));
        assertEquals(GroundedLaneWalkState.COMPLETE, walker.state());
        assertFalse(walker.currentCommand().forwardPressed());
    }

    @Test
    void usesDeterministicHeadingPerLaneDirection() {
        assertEquals(-90.0f, GroundedLaneWalker.yawFor(GroundedLaneDirection.EAST));
        assertEquals(90.0f, GroundedLaneWalker.yawFor(GroundedLaneDirection.WEST));
        assertEquals(0.0f, GroundedLaneWalker.yawFor(GroundedLaneDirection.SOUTH));
        assertEquals(180.0f, GroundedLaneWalker.yawFor(GroundedLaneDirection.NORTH));
    }

    @Test
    void preservesConstantSprintIntentWhenEnabled() {
        GroundedLaneWalker walker = new GroundedLaneWalker(eastLane(), bounds(), true, 200);
        walker.tick(Vec3d.ofCenter(eastLane().startPoint()));
        walker.tick(new Vec3d(2.5, 64.0, 2.5));
        assertTrue(walker.currentCommand().sprinting());
    }

    @Test
    void failsWhenLeavingLaneCenterlineCorridor() {
        GroundedLaneWalker walker = new GroundedLaneWalker(eastLane(), bounds(), true, 200);
        walker.tick(Vec3d.ofCenter(eastLane().startPoint()));

        walker.tick(new Vec3d(3.0, 64.0, 8.0));
        assertEquals(GroundedLaneWalkState.FAILED, walker.state());
        assertTrue(walker.failureReason().orElse("").contains("corridor"));
    }

    @Test
    void clearsForcedMovementOnInterrupt() {
        GroundedLaneWalker walker = new GroundedLaneWalker(eastLane(), bounds(), true, 200);
        walker.tick(Vec3d.ofCenter(eastLane().startPoint()));
        walker.interrupt();

        assertEquals(GroundedLaneWalkState.INTERRUPTED, walker.state());
        assertFalse(walker.currentCommand().forwardPressed());
        assertFalse(walker.currentCommand().sprinting());
    }

    private static GroundedSweepLane eastLane() {
        return new GroundedSweepLane(
                0,
                2,
                GroundedLaneDirection.EAST,
                new BlockPos(0, 64, 2),
                new BlockPos(8, 64, 2),
                new GroundedLaneCorridorBounds(0, 8, 0, 4),
                1.0
        );
    }

    private static GroundedSchematicBounds bounds() {
        return new GroundedSchematicBounds(
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(8, 64, 8)
        );
    }
}
