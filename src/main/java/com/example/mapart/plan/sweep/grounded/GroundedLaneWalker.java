package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.Vec3d;

import java.util.Objects;
import java.util.Optional;

public final class GroundedLaneWalker {
    private static final double CENTERLINE_CORRECTION_TOLERANCE = 0.15;
    private static final int DEFAULT_MAX_TICKS = 20 * 300;

    private final GroundedSweepLane lane;
    private final GroundedSchematicBounds bounds;
    private final boolean constantSprint;
    private final int maxTicks;

    private GroundedLaneWalkState state = GroundedLaneWalkState.PREPARE;
    private GroundedLaneWalkCommand currentCommand;
    private int ticksElapsed;
    private String failureReason = "";

    public GroundedLaneWalker(GroundedSweepLane lane, GroundedSchematicBounds bounds, boolean constantSprint) {
        this(lane, bounds, constantSprint, DEFAULT_MAX_TICKS);
    }

    GroundedLaneWalker(GroundedSweepLane lane, GroundedSchematicBounds bounds, boolean constantSprint, int maxTicks) {
        this.lane = Objects.requireNonNull(lane, "lane");
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.constantSprint = constantSprint;
        if (maxTicks <= 0) {
            throw new IllegalArgumentException("maxTicks must be > 0");
        }
        this.maxTicks = maxTicks;
        this.currentCommand = GroundedLaneWalkCommand.idle(yawFor(lane.direction()));
    }

    public GroundedLaneWalkState state() {
        return state;
    }

    public GroundedLaneWalkCommand currentCommand() {
        return currentCommand;
    }

    public GroundedLaneWalkResult result() {
        return new GroundedLaneWalkResult(state, ticksElapsed, failureReason);
    }

    public void tick(Vec3d playerPosition) {
        Objects.requireNonNull(playerPosition, "playerPosition");
        if (isTerminal()) {
            return;
        }

        ticksElapsed++;
        if (state == GroundedLaneWalkState.PREPARE) {
            state = GroundedLaneWalkState.ACTIVE;
        }

        if (!withinSchematicBounds(playerPosition)) {
            fail("player left schematic bounds");
            return;
        }
        if (!withinLaneCorridor(playerPosition)) {
            fail("player left lane corridor");
            return;
        }

        if (isEndpointReached(playerPosition)) {
            state = GroundedLaneWalkState.COMPLETE;
            currentCommand = GroundedLaneWalkCommand.idle(yawFor(lane.direction()));
            return;
        }

        if (ticksElapsed >= maxTicks) {
            fail("lane walk timed out");
            return;
        }

        currentCommand = buildCommand(playerPosition);
    }

    public void interrupt() {
        if (!isTerminal()) {
            state = GroundedLaneWalkState.INTERRUPTED;
            currentCommand = GroundedLaneWalkCommand.idle(yawFor(lane.direction()));
        }
    }

    public Optional<String> failureReason() {
        return failureReason.isBlank() ? Optional.empty() : Optional.of(failureReason);
    }

    private GroundedLaneWalkCommand buildCommand(Vec3d playerPosition) {
        double lateralError = lateralError(playerPosition);
        boolean strafeLeft = lateralError > CENTERLINE_CORRECTION_TOLERANCE && shouldStrafeLeftWhenLateralPositive();
        boolean strafeRight = lateralError > CENTERLINE_CORRECTION_TOLERANCE && !shouldStrafeLeftWhenLateralPositive();
        if (lateralError < -CENTERLINE_CORRECTION_TOLERANCE) {
            strafeLeft = !strafeLeft;
            strafeRight = !strafeRight;
        }

        return new GroundedLaneWalkCommand(
                yawFor(lane.direction()),
                true,
                false,
                strafeLeft,
                strafeRight,
                constantSprint
        );
    }

    private double lateralError(Vec3d playerPosition) {
        return switch (lane.direction()) {
            case EAST, WEST -> playerPosition.z - lane.centerlineCoordinate();
            case SOUTH, NORTH -> playerPosition.x - lane.centerlineCoordinate();
        };
    }

    private boolean shouldStrafeLeftWhenLateralPositive() {
        return switch (lane.direction()) {
            case EAST -> true;
            case WEST -> false;
            case SOUTH -> false;
            case NORTH -> true;
        };
    }

    private boolean withinSchematicBounds(Vec3d playerPosition) {
        return playerPosition.x >= bounds.minX() - 0.5
                && playerPosition.x <= bounds.maxX() + 0.5
                && playerPosition.z >= bounds.minZ() - 0.5
                && playerPosition.z <= bounds.maxZ() + 0.5;
    }

    private boolean withinLaneCorridor(Vec3d playerPosition) {
        GroundedLaneCorridorBounds corridor = lane.corridorBounds();
        return playerPosition.x >= corridor.minX() - 0.5
                && playerPosition.x <= corridor.maxX() + 0.5
                && playerPosition.z >= corridor.minZ() - 0.5
                && playerPosition.z <= corridor.maxZ() + 0.5;
    }

    private boolean isEndpointReached(Vec3d playerPosition) {
        Vec3d target = Vec3d.ofCenter(lane.endPoint());
        return target.distanceTo(playerPosition) <= lane.endpointTolerance();
    }

    private void fail(String reason) {
        state = GroundedLaneWalkState.FAILED;
        failureReason = reason;
        currentCommand = GroundedLaneWalkCommand.idle(yawFor(lane.direction()));
    }

    private boolean isTerminal() {
        return state == GroundedLaneWalkState.COMPLETE
                || state == GroundedLaneWalkState.FAILED
                || state == GroundedLaneWalkState.INTERRUPTED;
    }

    static float yawFor(GroundedLaneDirection direction) {
        return switch (direction) {
            case SOUTH -> 0.0f;
            case WEST -> 90.0f;
            case NORTH -> 180.0f;
            case EAST -> -90.0f;
        };
    }
}
