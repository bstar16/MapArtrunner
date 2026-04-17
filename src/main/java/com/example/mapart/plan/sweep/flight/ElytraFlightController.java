package com.example.mapart.plan.sweep.flight;

import com.example.mapart.plan.sweep.BuildLane;
import com.example.mapart.plan.sweep.LaneAxis;
import com.example.mapart.plan.sweep.LaneDirection;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;
import java.util.Optional;

public final class ElytraFlightController {
    private static final double DEBUG_LANE_FLIGHT_HEIGHT_OFFSET = 2.0;
    private static final double FIXED_ALTITUDE_HALF_BAND = 0.45;

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
    private int outOfBoundsTicks;

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
        double targetY = laneTargetFlightY();
        if (y < targetY - FIXED_ALTITUDE_HALF_BAND) {
            return AltitudeBand.BELOW;
        }
        if (y > targetY + FIXED_ALTITUDE_HALF_BAND) {
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

        if (!input.inSchematicBounds()) {
            outOfBoundsTicks++;
        } else {
            outOfBoundsTicks = 0;
        }

        if (outOfBoundsTicks >= 20 && state != ElytraFlightState.RECOVERY) {
            requestRecovery(state, FlightFailureReason.SCHEMATIC_BOUNDS_VIOLATION);
        }

        if (state == ElytraFlightState.IDLE) {
            transitionTo(ElytraFlightState.TAKEOFF);
        }

        switch (state) {
            case TAKEOFF -> handleTakeoff(input);
            case LANE_ENTRY_ALIGNMENT -> handleLaneEntryAlignment(input);
            case LANE_FOLLOWING -> handleLaneFollowing(input);
            case APPROACH_ENDPOINT -> handleEndpointApproach(input);
            case SOFT_TURN -> handleSoftTurn(input);
            case RECOVERY -> handleRecovery(input);
            default -> {
            }
        }

        if (!isTerminal()) {
            lastCommand = buildControlCommand(input);
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
        Vec3d laneStart = laneStartWorldPoint();
        LaneEntryPlanner.LaneEntryPlan plan = laneEntryPlanner.plan(
                lane,
                toRelative(input.playerPosition()),
                settings.laneEntryCenterTolerance(),
                Math.max(1.0, settings.laneEntryCenterTolerance())
        );
        boolean atLaneStart = input.playerPosition().distanceTo(laneStart) <= Math.max(1.0, settings.laneEntryCenterTolerance());

        if (plan.aligned() && plan.startAligned() && atLaneStart && input.inLaneCorridor()) {
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

        if (!input.inLaneCorridor()) {
            requestRecovery(ElytraFlightState.LANE_FOLLOWING, FlightFailureReason.LANE_CORRIDOR_VIOLATION);
            return;
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
        if (!input.inLaneCorridor()) {
            requestRecovery(ElytraFlightState.APPROACH_ENDPOINT, FlightFailureReason.LANE_CORRIDOR_VIOLATION);
            return;
        }

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

        transitionTo(ElytraFlightState.COMPLETE);
    }

    private void handleRecovery(FlightTickInput input) {
        FlightRecoveryHandler.RecoveryDecision decision = recoveryHandler.evaluate(
                recoveryAttempts,
                settings.maxRecoveryAttempts(),
                pendingFailureReason
        );

        if (!decision.retryPermitted()) {
            fail(FlightFailureReason.RECOVERY_EXHAUSTED);
            return;
        }

        if (input.fallFlying() && input.inSchematicBounds() && input.inLaneCorridor()) {
            transitionTo(recoveryTargetState);
            return;
        }

        if (stateTicks >= settings.recoveryTimeoutTicks()) {
            fail(FlightFailureReason.RECOVERY_TIMEOUT);
        }
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

    private FlightControlCommand buildControlCommand(FlightTickInput input) {
        return switch (state) {
            case TAKEOFF -> buildTakeoffCommand(input);
            case LANE_ENTRY_ALIGNMENT -> buildLaneCommand(input, true, true);
            case LANE_FOLLOWING -> buildLaneCommand(input, false, true);
            case APPROACH_ENDPOINT -> buildLaneCommand(input, false, true);
            case SOFT_TURN -> buildLaneCommand(input, false, true);
            case RECOVERY -> buildRecoveryCommand(input);
            default -> FlightControlCommand.idle();
        };
    }

    private FlightControlCommand buildTakeoffCommand(FlightTickInput input) {
        Vec3d worldPlayerPosition = input.playerPosition();
        Vec3d entry = toWorldCenter(lane.entryPoint());
        float yaw = yawTo(worldPlayerPosition, entry);
        float pitch = input.onGround() ? -25.0f : -38.0f;
        boolean jump = true;
        boolean sneak = false;
        return new FlightControlCommand(yaw, pitch, true, false, false, false, jump, sneak, true);
    }

    private FlightControlCommand buildRecoveryCommand(FlightTickInput input) {
        return buildLaneCommand(input, true, false);
    }

    private FlightControlCommand buildLaneCommand(FlightTickInput input,
                                                  boolean aggressiveClimb,
                                                  boolean allowForwardMotion) {
        Vec3d worldPlayerPosition = input.playerPosition();
        double lateralOffset = laneLateralOffset(worldPlayerPosition);
        double lateralIntent = clamp(lateralOffset * 1.3, -1.0, 1.0);
        boolean left = lateralIntent > 0.15;
        boolean right = lateralIntent < -0.15;

        boolean forward = allowForwardMotion;
        boolean back = false;

        double verticalIntent = altitudeBandIntent(worldPlayerPosition.y, aggressiveClimb);

        boolean jump = verticalIntent > 0.08;
        boolean sneak = verticalIntent < -0.08;

        double baseYaw = laneHeadingYaw();
        float yaw = normalizeYaw((float) (baseYaw + lateralIntent * 12.0));
        float pitch = pitchForVerticalIntent(verticalIntent);

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

    private double altitudeError(double worldY) {
        return laneTargetFlightY() - worldY;
    }

    private double altitudeBandIntent(double worldY, boolean aggressiveClimb) {
        AltitudeBand band = classifyAltitude(worldY);
        if (band == AltitudeBand.BELOW) {
            return aggressiveClimb ? 1.0 : 0.85;
        }
        if (band == AltitudeBand.ABOVE) {
            return -0.7;
        }
        return 0.0;
    }

    private float pitchForVerticalIntent(double verticalIntent) {
        if (verticalIntent > 0.2) {
            return -30.0f;
        }
        if (verticalIntent < -0.2) {
            return 8.0f;
        }
        return -6.0f;
    }

    private float yawTo(Vec3d from, Vec3d to) {
        Vec3d delta = to.subtract(from);
        return normalizeYaw((float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0f));
    }

    private double laneTargetFlightY() {
        return lane.entryPoint().getY() + worldOrigin.y + DEBUG_LANE_FLIGHT_HEIGHT_OFFSET;
    }

    private static float normalizeYaw(float yaw) {
        float normalized = yaw % 360.0f;
        if (normalized >= 180.0f) {
            normalized -= 360.0f;
        }
        if (normalized < -180.0f) {
            normalized += 360.0f;
        }
        return normalized;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private Vec3d laneStartWorldPoint() {
        return toWorldCenter(lane.entryPoint());
    }

    private Vec3d laneEndWorldPoint() {
        return toWorldCenter(lane.endPoint());
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

    public record FlightTickInput(Vec3d playerPosition,
                                  boolean fallFlying,
                                  boolean onGround,
                                  boolean inSchematicBounds,
                                  boolean inLaneCorridor,
                                  Optional<Vec3d> corridorCenterTarget,
                                  Optional<BuildLane> nextLane) {
        public FlightTickInput {
            Objects.requireNonNull(playerPosition, "playerPosition");
            Objects.requireNonNull(corridorCenterTarget, "corridorCenterTarget");
            Objects.requireNonNull(nextLane, "nextLane");
        }

        public static FlightTickInput currentLaneOnly(Vec3d playerPosition, boolean fallFlying) {
            return new FlightTickInput(playerPosition, fallFlying, false, true, true, Optional.empty(), Optional.empty());
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
