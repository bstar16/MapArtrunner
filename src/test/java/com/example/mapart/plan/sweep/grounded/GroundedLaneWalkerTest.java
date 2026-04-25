package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundedLaneWalkerTest {
    @Test
    void laneTraversalProgressesFromPrepareToComplete() {
        GroundedLaneWalker walker = walker(lane(GroundedLaneDirection.EAST), true);

        walker.tick(new Vec3d(0.5, 64.0, 2.5));
        assertEquals(GroundedLaneWalkerState.ACTIVE, walker.state());

        walker.tick(new Vec3d(8.5, 64.0, 2.5));
        assertEquals(GroundedLaneWalkerState.COMPLETE, walker.state());
    }

    @Test
    void deterministicHeadingMatchesLaneDirection() {
        assertEquals(-90.0f, GroundedLaneWalker.yawForDirection(GroundedLaneDirection.EAST));
        assertEquals(90.0f, GroundedLaneWalker.yawForDirection(GroundedLaneDirection.WEST));
        assertEquals(0.0f, GroundedLaneWalker.yawForDirection(GroundedLaneDirection.SOUTH));
        assertEquals(180.0f, GroundedLaneWalker.yawForDirection(GroundedLaneDirection.NORTH));
    }

    @Test
    void constantSprintIntentRespectsGroundedSweepSetting() {
        GroundedLaneWalker sprinting = walker(lane(GroundedLaneDirection.EAST), true);
        GroundedLaneWalker walking = walker(lane(GroundedLaneDirection.EAST), false);

        assertTrue(sprinting.currentCommand(new Vec3d(1.5, 64.0, 2.5)).sprinting());
        assertFalse(walking.currentCommand(new Vec3d(1.5, 64.0, 2.5)).sprinting());
    }

    @Test
    void laneCenterlineConstraintAddsStrafeAndFailsOutsideCorridor() {
        GroundedLaneWalker walker = walker(lane(GroundedLaneDirection.EAST), true);

        GroundedLaneWalker.GroundedControlCommand command = walker.currentCommand(new Vec3d(1.5, 64.0, 3.2));
        assertTrue(command.leftPressed());
        assertFalse(command.rightPressed());

        walker.tick(new Vec3d(1.5, 64.0, 6.5));
        assertEquals(GroundedLaneWalkerState.FAILED, walker.state());
        assertTrue(walker.result().failureReason().isPresent());
    }

    @Test
    void interruptClearsForcedForwardControlIntent() {
        GroundedLaneWalker walker = walker(lane(GroundedLaneDirection.EAST), true);
        walker.tick(new Vec3d(1.5, 64.0, 2.5));

        walker.interrupt();
        GroundedLaneWalker.GroundedControlCommand command = walker.currentCommand(new Vec3d(2.0, 64.0, 2.5));

        assertEquals(GroundedLaneWalkerState.INTERRUPTED, walker.state());
        assertFalse(command.forwardPressed());
        assertFalse(command.sprinting());
    }

    private static GroundedLaneWalker walker(GroundedSweepLane lane, boolean constantSprint) {
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(8, 64, 4)
        );
        GroundedSweepSettings settings = new GroundedSweepSettings(false, 2, 5, 5, 1, 1, constantSprint, 1.0);
        return new GroundedLaneWalker(lane, bounds, settings);
    }

    private static GroundedSweepLane lane(GroundedLaneDirection direction) {
        return new GroundedSweepLane(
                0,
                2,
                direction,
                new BlockPos(0, 64, 2),
                new BlockPos(8, 64, 2),
                new GroundedLaneCorridorBounds(0, 8, 0, 4),
                1.0
        );
    }
}
