package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.Vec3d;

import java.util.Objects;
import java.util.Optional;

public final class GroundedLaneWalker {
    private static final double CENTERLINE_TOLERANCE = 0.15;

    private final GroundedSweepLane lane;
    private final GroundedSchematicBounds bounds;
    private final GroundedSweepSettings settings;

    private GroundedLaneWalkerState state = GroundedLaneWalkerState.PREPARE;
    private int ticksElapsed;
    private Optional<String> failureReason = Optional.empty();

    public GroundedLaneWalker(GroundedSweepLane lane, GroundedSchematicBounds bounds, GroundedSweepSettings settings) {
        this.lane = Objects.requireNonNull(lane, "lane");
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public GroundedLaneWalkerState state() {
        return state;
    }

    public GroundedControlCommand currentCommand(Vec3d playerPosition) {
        Objects.requireNonNull(playerPosition, "playerPosition");
        if (state == GroundedLaneWalkerState.FAILED || state == GroundedLaneWalkerState.COMPLETE || state == GroundedLaneWalkerState.INTERRUPTED) {
            return GroundedControlCommand.idle();
        }

        float yaw = yawForDirection(lane.direction());
        double lateralOffset = lateralOffsetFromCenterline(playerPosition);
        boolean moveLeft = false;
        boolean moveRight = false;
        if (Math.abs(lateralOffset) > CENTERLINE_TOLERANCE) {
            moveLeft = shouldPressLeftForOffset(lane.direction(), lateralOffset);
            moveRight = !moveLeft;
        }

        return new GroundedControlCommand(
                yaw,
                true,
                false,
                moveLeft,
                moveRight,
                false,
                false,
                settings.groundedSweepConstantSprint()
        );
    }

    public void tick(Vec3d playerPosition) {
        Objects.requireNonNull(playerPosition, "playerPosition");
        if (isTerminal()) {
            return;
        }

        ticksElapsed++;
        if (state == GroundedLaneWalkerState.PREPARE) {
            state = GroundedLaneWalkerState.ACTIVE;
        }

        if (!isInsideHardBounds(playerPosition)) {
            fail("displaced outside lane corridor/bounds");
            return;
        }

        if (isEndpointReached(playerPosition)) {
            state = GroundedLaneWalkerState.COMPLETE;
        }
    }

    public void interrupt() {
        if (!isTerminal()) {
            state = GroundedLaneWalkerState.INTERRUPTED;
        }
    }

    public GroundedLaneWalkResult result() {
        return new GroundedLaneWalkResult(state, ticksElapsed, failureReason);
    }

    public boolean isBelowBuildArea(Vec3d playerPosition) {
        return playerPosition.y < bounds.minY();
    }

    public boolean isTerminal() {
        return state == GroundedLaneWalkerState.COMPLETE
                || state == GroundedLaneWalkerState.FAILED
                || state == GroundedLaneWalkerState.INTERRUPTED;
    }

    static float yawForDirection(GroundedLaneDirection direction) {
        return switch (direction) {
            case EAST -> -90.0f;
            case WEST -> 90.0f;
            case SOUTH -> 0.0f;
            case NORTH -> 180.0f;
        };
    }

    private static boolean shouldPressLeftForOffset(GroundedLaneDirection direction, double lateralOffset) {
        return switch (direction) {
            case EAST -> lateralOffset > 0;
            case WEST -> lateralOffset < 0;
            case SOUTH -> lateralOffset < 0;
            case NORTH -> lateralOffset > 0;
        };
    }

    private double lateralOffsetFromCenterline(Vec3d playerPosition) {
        return switch (lane.direction()) {
            case EAST, WEST -> playerPosition.z - lane.centerlineCoordinate();
            case SOUTH, NORTH -> playerPosition.x - lane.centerlineCoordinate();
        };
    }

    private boolean isEndpointReached(Vec3d playerPosition) {
        Vec3d endpoint = Vec3d.ofCenter(lane.endPoint());
        double dx = endpoint.x - playerPosition.x;
        double dz = endpoint.z - playerPosition.z;
        double horizontalDistance = Math.sqrt((dx * dx) + (dz * dz));
        return horizontalDistance <= lane.endpointTolerance();
    }

    private boolean isInsideHardBounds(Vec3d playerPosition) {
        int blockX = (int) Math.floor(playerPosition.x);
        int blockZ = (int) Math.floor(playerPosition.z);

        boolean insideCorridor = blockX >= lane.corridorBounds().minX()
                && blockX <= lane.corridorBounds().maxX()
                && blockZ >= lane.corridorBounds().minZ()
                && blockZ <= lane.corridorBounds().maxZ();
        if (!insideCorridor) {
            return false;
        }

        return blockX >= bounds.minX()
                && blockX <= bounds.maxX()
                && blockZ >= bounds.minZ()
                && blockZ <= bounds.maxZ();
    }

    private void fail(String reason) {
        state = GroundedLaneWalkerState.FAILED;
        failureReason = Optional.of(reason);
    }

    public record GroundedControlCommand(
            float yaw,
            boolean forwardPressed,
            boolean backPressed,
            boolean leftPressed,
            boolean rightPressed,
            boolean jumpPressed,
            boolean sneakPressed,
            boolean sprinting
    ) {
        public static GroundedControlCommand idle() {
            return new GroundedControlCommand(0.0f, false, false, false, false, false, false, false);
        }
    }
}
