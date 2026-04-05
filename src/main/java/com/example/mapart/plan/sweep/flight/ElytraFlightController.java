package com.example.mapart.plan.sweep.flight;

import com.example.mapart.plan.sweep.BuildLane;
import com.example.mapart.plan.sweep.LaneAxis;
import com.example.mapart.plan.sweep.LaneDirection;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;
import java.util.Optional;

public final class ElytraFlightController {
    private final BuildLane lane;
    private final ElytraFlightControllerSettings settings;
    private final LaneEntryPlanner laneEntryPlanner;
    private final TurnPlanner turnPlanner;
    private final FlightRecoveryHandler recoveryHandler;
    private final Vec3d worldOrigin;

    private ElytraFlightState state = ElytraFlightState.IDLE;
    private ElytraFlightState recoveryTargetState = ElytraFlightState.TAKEOFF;
    private FlightFailureReason pendingFailureReason;

    private int ticksElapsed;
    private int stateTicks;
    private int recoveryAttempts;

    private boolean endpointApproachObserved;
    private boolean softTurnPlanned;
    private FlightFailureReason terminalFailureReason;
    private FlightControlCommand lastCommand = FlightControlCommand.idle();
    private int sustainedAltitudeViolationTicks;

    public ElytraFlightController(BuildLane lane,
                                  ElytraFlightControllerSettings settings,
                                  LaneEntryPlanner laneEntryPlanner,
                                  TurnPlanner turnPlanner,
                                  FlightRecoveryHandler recoveryHandler) {
        this(lane, settings, laneEntryPlanner, turnPlanner, recoveryHandler, BlockPos.ORIGIN);
    }

    public ElytraFlightController(BuildLane lane,
                                  ElytraFlightControllerSettings settings,
                                  LaneEntryPlanner laneEntryPlanner,
                                  TurnPlanner turnPlanner,
                                  FlightRecoveryHandler recoveryHandler,
                                  BlockPos worldOrigin) {
        this.lane = Objects.requireNonNull(lane, "lane");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.laneEntryPlanner = Objects.requireNonNull(laneEntryPlanner, "laneEntryPlanner");
        this.turnPlanner = Objects.requireNonNull(turnPlanner, "turnPlanner");
        this.recoveryHandler = Objects.requireNonNull(recoveryHandler, "recoveryHandler");
        Objects.requireNonNull(worldOrigin, "worldOrigin");
        this.worldOrigin = new Vec3d(worldOrigin.getX(), worldOrigin.getY(), worldOrigin.getZ());
    }

    public ElytraFlightState state() {
        return state;
    }

    public AltitudeBand classifyAltitude(double y) {
        if (y < settings.minAltitude()) {
            return AltitudeBand.BELOW;
        }
        if (y > settings.maxAltitude()) {
            return AltitudeBand.ABOVE;
        }
        return AltitudeBand.IN_BAND;
    }

    public boolean isEndpointApproaching(Vec3d playerPosition) {
        return distanceToLaneEndpoint(playerPosition) <= settings.endpointApproachDistance();
    }

    public FlightControlCommand currentCommand() {
        return lastCommand;
    }

    public void tick(FlightTickInput input) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(input.playerPosition(), "input.playerPosition");

        if (isTerminal()) {
            lastCommand = FlightControlCommand.idle();
            return;
        }

        ticksElapsed++;
        stateTicks++;

        if (state == ElytraFlightState.IDLE) {
            transitionTo(ElytraFlightState.TAKEOFF);
        }

        switch (state) {
            case TAKEOFF -> handleTakeoff(input);
            case LANE_ENTRY_ALIGNMENT -> handleLaneEntryAlignment(input);
            case LANE_FOLLOWING -> handleLaneFollowing(input);
            case APPROACH_ENDPOINT -> handleEndpointApproach(input);
            case SOFT_TURN -> handleSoftTurn(input);
            case RECOVERY -> handleRecovery();
            default -> {
            }
        }

        if (!isTerminal()) {
            lastCommand = buildControlCommand(input.playerPosition());
        } else {
            lastCommand = FlightControlCommand.idle();
        }
    }

    public void interrupt() {
        if (isTerminal()) {
            return;
        }
        terminalFailureReason = FlightFailureReason.INTERRUPTED_BY_CALLER;
        transitionTo(ElytraFlightState.INTERRUPTED);
    }

    public ElytraFlightResult result() {
        return new ElytraFlightResult(
                lane.laneIndex(),
                state,
                ticksElapsed,
                recoveryAttempts,
                endpointApproachObserved,
                softTurnPlanned,
                Optional.ofNullable(terminalFailureReason)
        );
    }

    private void handleTakeoff(FlightTickInput input) {
        if (input.fallFlying()) {
            transitionTo(ElytraFlightState.LANE_ENTRY_ALIGNMENT);
            return;
        }

        if (stateTicks >= settings.takeoffTimeoutTicks()) {
            requestRecovery(ElytraFlightState.TAKEOFF, FlightFailureReason.TAKEOFF_TIMEOUT);
        }
    }

    private void handleLaneEntryAlignment(FlightTickInput input) {
        LaneEntryPlanner.LaneEntryPlan plan = laneEntryPlanner.plan(
                lane,
                toRelative(input.playerPosition()),
                settings.laneEntryCenterTolerance()
        );

        if (plan.aligned()) {
            transitionTo(ElytraFlightState.LANE_FOLLOWING);
            return;
        }

        if (stateTicks >= settings.laneEntryTimeoutTicks()) {
            requestRecovery(ElytraFlightState.LANE_ENTRY_ALIGNMENT, FlightFailureReason.ENTRY_ALIGNMENT_TIMEOUT);
        }
    }

    private void handleLaneFollowing(FlightTickInput input) {
        AltitudeBand altitudeBand = classifyAltitude(input.playerPosition().y);
        if (altitudeBand != AltitudeBand.IN_BAND && Math.abs(altitudeError(input.playerPosition().y)) > 6.0) {
            sustainedAltitudeViolationTicks++;
        } else {
            sustainedAltitudeViolationTicks = 0;
        }

        if (sustainedAltitudeViolationTicks >= 20) {
            requestRecovery(ElytraFlightState.LANE_FOLLOWING, FlightFailureReason.ALTITUDE_BAND_VIOLATION);
            return;
        }

        if (isEndpointApproaching(input.playerPosition())) {
            endpointApproachObserved = true;
            transitionTo(ElytraFlightState.APPROACH_ENDPOINT);
        }
    }

    private void handleEndpointApproach(FlightTickInput input) {
        if (distanceToLaneEndpoint(input.playerPosition()) <= lane.endpointTolerance()) {
            if (input.nextLane().isPresent()) {
                TurnPlanner.TurnPlan turnPlan = turnPlanner.planSerpentineTurn(lane, input.nextLane().get(), input.playerPosition());
                if (!turnPlan.available()) {
                    fail(FlightFailureReason.TURN_PLAN_UNAVAILABLE);
                    return;
                }
                softTurnPlanned = true;
                transitionTo(ElytraFlightState.SOFT_TURN);
                return;
            }
            transitionTo(ElytraFlightState.COMPLETE);
            return;
        }

        if (stateTicks >= settings.endpointApproachTimeoutTicks()) {
            requestRecovery(ElytraFlightState.APPROACH_ENDPOINT, FlightFailureReason.ENDPOINT_APPROACH_TIMEOUT);
        }
    }

    private void handleSoftTurn(FlightTickInput input) {
        if (input.nextLane().isEmpty()) {
            fail(FlightFailureReason.TURN_PLAN_UNAVAILABLE);
            return;
        }

        // Skeleton behavior: reaching this state indicates a valid turn plan exists.
        transitionTo(ElytraFlightState.COMPLETE);
    }

    private void handleRecovery() {
        FlightRecoveryHandler.RecoveryDecision decision = recoveryHandler.evaluate(
                recoveryAttempts,
                settings.maxRecoveryAttempts(),
                pendingFailureReason
        );

        if (!decision.retryPermitted()) {
            fail(FlightFailureReason.RECOVERY_EXHAUSTED);
            return;
        }

        transitionTo(recoveryTargetState);
    }

    private void requestRecovery(ElytraFlightState targetState, FlightFailureReason reason) {
        recoveryAttempts++;
        recoveryTargetState = targetState;
        pendingFailureReason = reason;
        transitionTo(ElytraFlightState.RECOVERY);
    }

    private double distanceToLaneEndpoint(Vec3d playerPosition) {
        return playerPosition.distanceTo(toWorldCenter(lane.endPoint()));
    }

    private FlightControlCommand buildControlCommand(Vec3d worldPlayerPosition) {
        return switch (state) {
            case TAKEOFF -> buildTakeoffCommand(worldPlayerPosition);
            case LANE_ENTRY_ALIGNMENT -> buildLaneCommand(worldPlayerPosition, holdPositionTarget(worldPlayerPosition), true, false);
            case LANE_FOLLOWING -> buildLaneCommand(worldPlayerPosition, progressTargetWorldPosition(worldPlayerPosition, 8.0), false);
            case APPROACH_ENDPOINT -> buildLaneCommand(worldPlayerPosition, toWorldCenter(lane.endPoint()), false);
            case SOFT_TURN -> buildLaneCommand(worldPlayerPosition, toWorldCenter(lane.endPoint()), false);
            case RECOVERY -> buildRecoveryCommand(worldPlayerPosition);
            default -> FlightControlCommand.idle();
        };
    }

    private FlightControlCommand buildTakeoffCommand(Vec3d worldPlayerPosition) {
        Vec3d entry = toWorldCenter(lane.entryPoint());
        float yaw = yawTo(worldPlayerPosition, entry);
        float pitch = -28.0f;
        return new FlightControlCommand(yaw, pitch, true, false, false, false, true, false, true);
    }

    private FlightControlCommand buildRecoveryCommand(Vec3d worldPlayerPosition) {
        Vec3d recoveryTarget = progressTargetWorldPosition(worldPlayerPosition, 4.0);
        return buildLaneCommand(worldPlayerPosition, recoveryTarget, true);
    }

    private FlightControlCommand buildLaneCommand(Vec3d worldPlayerPosition, Vec3d worldTarget, boolean aggressiveClimb) {
        return buildLaneCommand(worldPlayerPosition, worldTarget, aggressiveClimb, true);
    }

    private FlightControlCommand buildLaneCommand(Vec3d worldPlayerPosition,
                                                  Vec3d worldTarget,
                                                  boolean aggressiveClimb,
                                                  boolean allowForwardMotion) {
        double laneYaw = laneHeadingYaw();
        double lateralOffset = laneLateralOffset(worldPlayerPosition);
        double forwardError = forwardDistance(worldPlayerPosition, worldTarget);
        double altitudeError = altitudeError(worldPlayerPosition.y);

        double lateralIntent = clamp(lateralOffset * 0.9, -1.0, 1.0);
        boolean left = lateralIntent > 0.18;
        boolean right = lateralIntent < -0.18;

        boolean forward = allowForwardMotion && forwardError > 0.35;
        boolean back = allowForwardMotion && forwardError < -0.45;

        double verticalIntent = clamp(altitudeError * 0.22, -1.0, 1.0);
        if (aggressiveClimb && worldPlayerPosition.y < settings.minAltitude() + 2.0) {
            verticalIntent = Math.max(verticalIntent, 0.85);
        }
        boolean jump = verticalIntent > 0.12;
        boolean sneak = verticalIntent < -0.12;

        float yaw = (float) clamp(laneYaw + lateralIntent * 18.0, -180.0, 180.0);
        float pitch = (float) clamp(-6.0 - verticalIntent * 25.0, -45.0, 35.0);

        return new FlightControlCommand(yaw, pitch, forward, back, left, right, jump, sneak, true);
    }

    private double laneHeadingYaw() {
        if (lane.axis() == LaneAxis.X) {
            return lane.direction() == LaneDirection.FORWARD ? -90.0 : 90.0;
        }
        return lane.direction() == LaneDirection.FORWARD ? 0.0 : 180.0;
    }

    private double laneLateralOffset(Vec3d worldPosition) {
        Vec3d relative = toRelative(worldPosition);
        double laneCenter = lane.fixedCoordinate() + 0.5;
        return lane.axis() == LaneAxis.X ? laneCenter - relative.z : relative.x - laneCenter;
    }

    private double forwardDistance(Vec3d worldPosition, Vec3d worldTarget) {
        Vec3d current = toRelative(worldPosition);
        Vec3d target = toRelative(worldTarget);
        if (lane.axis() == LaneAxis.X) {
            return lane.direction() == LaneDirection.FORWARD ? target.x - current.x : current.x - target.x;
        }
        return lane.direction() == LaneDirection.FORWARD ? target.z - current.z : current.z - target.z;
    }

    private double altitudeError(double worldY) {
        double target = (settings.minAltitude() + settings.maxAltitude()) * 0.5;
        return target - worldY;
    }

    private float yawTo(Vec3d from, Vec3d to) {
        Vec3d delta = to.subtract(from);
        return (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0f);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private Vec3d progressTargetWorldPosition(Vec3d worldPlayerPosition, double lookahead) {
        Vec3d relativePlayer = toRelative(worldPlayerPosition);
        double currentProgress = lane.axis() == LaneAxis.X ? relativePlayer.x : relativePlayer.z;
        double desiredProgress = lane.direction() == LaneDirection.FORWARD
                ? Math.min(lane.maxProgress(), currentProgress + lookahead)
                : Math.max(lane.minProgress(), currentProgress - lookahead);
        Vec3d relativeTarget = lane.axis() == LaneAxis.X
                ? new Vec3d(desiredProgress, relativePlayer.y, lane.fixedCoordinate() + 0.5)
                : new Vec3d(lane.fixedCoordinate() + 0.5, relativePlayer.y, desiredProgress);
        return toWorld(relativeTarget);
    }

    private Vec3d holdPositionTarget(Vec3d worldPlayerPosition) {
        Vec3d relativePlayer = toRelative(worldPlayerPosition);
        double currentProgress = lane.axis() == LaneAxis.X ? relativePlayer.x : relativePlayer.z;
        Vec3d relativeTarget = lane.axis() == LaneAxis.X
                ? new Vec3d(currentProgress, relativePlayer.y, lane.fixedCoordinate() + 0.5)
                : new Vec3d(lane.fixedCoordinate() + 0.5, relativePlayer.y, currentProgress);
        return toWorld(relativeTarget);
    }

    private Vec3d toWorld(Vec3d relative) {
        return relative.add(worldOrigin);
    }

    private Vec3d toRelative(Vec3d world) {
        return world.subtract(worldOrigin);
    }

    private Vec3d toWorldCenter(BlockPos relativeBlockPos) {
        return Vec3d.ofCenter(relativeBlockPos).add(worldOrigin);
    }

    private void fail(FlightFailureReason reason) {
        terminalFailureReason = reason;
        transitionTo(ElytraFlightState.FAILED);
    }

    private boolean isTerminal() {
        return state == ElytraFlightState.COMPLETE
                || state == ElytraFlightState.FAILED
                || state == ElytraFlightState.INTERRUPTED;
    }

    private void transitionTo(ElytraFlightState nextState) {
        state = nextState;
        stateTicks = 0;
    }

    public enum AltitudeBand {
        BELOW,
        IN_BAND,
        ABOVE
    }

    public record FlightTickInput(Vec3d playerPosition, boolean fallFlying, Optional<BuildLane> nextLane) {
        public FlightTickInput {
            Objects.requireNonNull(playerPosition, "playerPosition");
            Objects.requireNonNull(nextLane, "nextLane");
        }

        public static FlightTickInput currentLaneOnly(Vec3d playerPosition, boolean fallFlying) {
            return new FlightTickInput(playerPosition, fallFlying, Optional.empty());
        }
    }

    public record FlightControlCommand(float yaw,
                                       float pitch,
                                       boolean forwardPressed,
                                       boolean backPressed,
                                       boolean leftPressed,
                                       boolean rightPressed,
                                       boolean jumpPressed,
                                       boolean sneakPressed,
                                       boolean sprinting) {
        public static FlightControlCommand idle() {
            return new FlightControlCommand(0.0f, 0.0f, false, false, false, false, false, false, false);
        }
    }
}
