package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundedLaneWalkerTest {

    @Test
    void walksSingleLaneFromStartToEndDeterministically() {
        GroundedLaneWalker walker = new GroundedLaneWalker();
        GroundedSweepLane lane = eastboundLane();
        walker.start(lane, bounds(), true);

        walker.tick(new Vec3d(0.5, 64.0, 2.5));
        assertEquals(GroundedLaneWalker.GroundedLaneWalkState.ACTIVE, walker.state());

        walker.tick(new Vec3d(4.8, 64.0, 2.5));
        assertEquals(GroundedLaneWalker.GroundedLaneWalkState.ACTIVE, walker.state());

        walker.tick(new Vec3d(8.5, 64.0, 2.5));
        assertEquals(GroundedLaneWalker.GroundedLaneWalkState.COMPLETE, walker.state());
        assertTrue(walker.currentCommand().isEmpty());
    }

    @Test
    void emitsDeterministicHeadingFromLaneDirection() {
        GroundedLaneWalker walker = new GroundedLaneWalker();
        GroundedSweepLane eastLane = eastboundLane();
        walker.start(eastLane, bounds(), true);

        float eastYaw = walker.currentCommand().orElseThrow().yaw();
        assertEquals(90.0f, eastYaw);

        GroundedSweepLane northLane = new GroundedSweepLane(
                1,
                3,
                GroundedLaneDirection.NORTH,
                new BlockPos(3, 64, 8),
                new BlockPos(3, 64, 0),
                new GroundedLaneCorridorBounds(1, 5, 0, 8),
                0.4
        );
        walker.start(northLane, bounds(), true);
        assertEquals(180.0f, walker.currentCommand().orElseThrow().yaw());
    }

    @Test
    void keepsSprintIntentConstantWhileWalking() {
        GroundedLaneWalker walker = new GroundedLaneWalker();
        walker.start(eastboundLane(), bounds(), true);

        walker.tick(new Vec3d(1.5, 64.0, 2.5));
        assertTrue(walker.currentCommand().orElseThrow().sprinting());

        walker.tick(new Vec3d(5.5, 64.0, 2.58));
        assertTrue(walker.currentCommand().orElseThrow().sprinting());
    }

    @Test
    void laneCenterlineConstraintAddsCorrectiveStrafe() {
        GroundedLaneWalker walker = new GroundedLaneWalker();
        walker.start(eastboundLane(), bounds(), true);

        walker.tick(new Vec3d(2.5, 64.0, 3.2));
        assertTrue(walker.currentCommand().orElseThrow().leftPressed());
        assertFalse(walker.currentCommand().orElseThrow().rightPressed());

        walker.tick(new Vec3d(3.5, 64.0, 1.8));
        assertTrue(walker.currentCommand().orElseThrow().rightPressed());
        assertFalse(walker.currentCommand().orElseThrow().leftPressed());
    }

    @Test
    void cleanupClearsForcedMovementOnFailAndInterrupt() {
        GroundedLaneWalker walker = new GroundedLaneWalker();
        walker.start(eastboundLane(), bounds(), true);

        walker.tick(new Vec3d(4.0, 64.0, 8.0));
        assertEquals(GroundedLaneWalker.GroundedLaneWalkState.FAILED, walker.state());
        assertTrue(walker.currentCommand().isEmpty());

        walker.start(eastboundLane(), bounds(), true);
        walker.interrupt();
        assertEquals(GroundedLaneWalker.GroundedLaneWalkState.INTERRUPTED, walker.state());
        assertTrue(walker.currentCommand().isEmpty());
    }

    private static GroundedSweepLane eastboundLane() {
        return new GroundedSweepLane(
                0,
                2,
                GroundedLaneDirection.EAST,
                new BlockPos(0, 64, 2),
                new BlockPos(8, 64, 2),
                new GroundedLaneCorridorBounds(0, 8, 0, 4),
                0.5
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
