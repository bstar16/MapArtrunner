package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.Vec3d;

import java.util.Objects;
import java.util.Optional;

public final class GroundedLaneWalker {
    private static final double CENTERLINE_STRAFE_DEADBAND = 0.08;

    private GroundedSweepLane lane;
    private GroundedSchematicBounds schematicBounds;
    private boolean constantSprint;
    private GroundedLaneWalkState state = GroundedLaneWalkState.IDLE;
    private GroundedLaneWalkCommand forcedCommand = GroundedLaneWalkCommand.idle();
    private String failureReason = "";

    public void start(GroundedSweepLane lane, GroundedSchematicBounds schematicBounds, boolean constantSprint) {
        this.lane = Objects.requireNonNull(lane, "lane");
        this.schematicBounds = Objects.requireNonNull(schematicBounds, "schematicBounds");
        this.constantSprint = constantSprint;
        this.state = GroundedLaneWalkState.ACTIVE;
        this.forcedCommand = buildCommand(0.0);
        this.failureReason = "";
    }

    public GroundedLaneWalkState state() {
        return state;
    }

    public Optional<GroundedLaneWalkCommand> currentCommand() {
        if (state != GroundedLaneWalkState.ACTIVE) {
            return Optional.empty();
        }
        return Optional.of(forcedCommand);
    }

    public Optional<String> failureReason() {
        return failureReason.isBlank() ? Optional.empty() : Optional.of(failureReason);
    }

    public void interrupt() {
        if (state == GroundedLaneWalkState.ACTIVE) {
            state = GroundedLaneWalkState.INTERRUPTED;
        }
        clearForcedState();
    }

    public void tick(Vec3d playerPosition) {
        Objects.requireNonNull(playerPosition, "playerPosition");
        if (state != GroundedLaneWalkState.ACTIVE || lane == null || schematicBounds == null) {
            return;
        }

        if (!isInsideHardBounds(playerPosition) || !isInsideLaneCorridor(playerPosition)) {
            fail("Player left the grounded lane corridor bounds.");
            return;
        }

        if (isEndpointReached(playerPosition)) {
            state = GroundedLaneWalkState.COMPLETE;
            clearForcedState();
            return;
        }

        double lateralError = computeSignedLateralError(playerPosition);
        forcedCommand = buildCommand(lateralError);
    }

    private boolean isEndpointReached(Vec3d playerPosition) {
        double playerProgress = lane.direction().progressCoordinate(playerPosition.x, playerPosition.z);
        double targetProgress = lane.direction().progressCoordinate(lane.endPoint().getX() + 0.5, lane.endPoint().getZ() + 0.5);
        double signedDelta = (targetProgress - playerProgress) * lane.direction().forwardSign();
        return signedDelta <= lane.endpointTolerance();
    }

    private double computeSignedLateralError(Vec3d playerPosition) {
        double centerline = lane.centerlineCoordinate() + 0.5;
        return lane.direction().lateralCoordinate(playerPosition.x, playerPosition.z) - centerline;
    }

    private GroundedLaneWalkCommand buildCommand(double lateralError) {
        double signedError = lateralError * lane.direction().lateralStrafeSign();
        boolean strafeLeft = signedError > CENTERLINE_STRAFE_DEADBAND;
        boolean strafeRight = signedError < -CENTERLINE_STRAFE_DEADBAND;
        return new GroundedLaneWalkCommand(
                lane.direction().yawDegrees(),
                true,
                false,
                strafeLeft,
                strafeRight,
                false,
                false,
                constantSprint
        );
    }

    private boolean isInsideHardBounds(Vec3d playerPosition) {
        return playerPosition.x >= schematicBounds.minX() - 0.5
                && playerPosition.x <= schematicBounds.maxX() + 1.5
                && playerPosition.z >= schematicBounds.minZ() - 0.5
                && playerPosition.z <= schematicBounds.maxZ() + 1.5;
    }

    private boolean isInsideLaneCorridor(Vec3d playerPosition) {
        GroundedLaneCorridorBounds corridor = lane.corridorBounds();
        return playerPosition.x >= corridor.minX() - 0.5
                && playerPosition.x <= corridor.maxX() + 1.5
                && playerPosition.z >= corridor.minZ() - 0.5
                && playerPosition.z <= corridor.maxZ() + 1.5;
    }

    private void fail(String reason) {
        failureReason = reason;
        state = GroundedLaneWalkState.FAILED;
        clearForcedState();
    }

    private void clearForcedState() {
        forcedCommand = GroundedLaneWalkCommand.idle();
    }

    public enum GroundedLaneWalkState {
        IDLE,
        ACTIVE,
        COMPLETE,
        FAILED,
        INTERRUPTED
    }

    public record GroundedLaneWalkCommand(
            float yaw,
            boolean forwardPressed,
            boolean backPressed,
            boolean leftPressed,
            boolean rightPressed,
            boolean jumpPressed,
            boolean sneakPressed,
            boolean sprinting
    ) {
        public static GroundedLaneWalkCommand idle() {
            return new GroundedLaneWalkCommand(0.0f, false, false, false, false, false, false, false);
        }
    }
}
