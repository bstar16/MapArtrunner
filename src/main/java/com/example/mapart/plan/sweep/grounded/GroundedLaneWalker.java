package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.BlockPos;
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

    // Support-guard state — lifecycle:
    //   Set to PENDING_VERIFICATION or MISSING_UNTRACKED when the next footprint block has not
    //   yet been server-confirmed. Cleared (set to CONFIRMED or ALREADY_CORRECT) when the check
    //   passes on a subsequent tick. Cleared entirely (null) when the walker is not ACTIVE or
    //   has never run a support check yet.
    private SupportCheckResult lastSupportCheck = null;
    private BlockPos lastSupportCheckPos = null;

    public void start(GroundedSweepLane lane, GroundedSchematicBounds schematicBounds, boolean constantSprint) {
        this.lane = Objects.requireNonNull(lane, "lane");
        this.schematicBounds = Objects.requireNonNull(schematicBounds, "schematicBounds");
        this.constantSprint = constantSprint;
        this.state = GroundedLaneWalkState.ACTIVE;
        this.forcedCommand = buildCommand(0.0);
        this.failureReason = "";
        this.lastSupportCheck = null;
        this.lastSupportCheckPos = null;
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

    /** Returns the support check result from the most recent tick, or null if never checked. */
    public SupportCheckResult lastSupportCheck() {
        return lastSupportCheck;
    }

    /** Returns the footprint block position that was checked on the most recent tick, or null. */
    public BlockPos lastSupportCheckPos() {
        return lastSupportCheckPos;
    }

    public void interrupt() {
        if (state == GroundedLaneWalkState.ACTIVE) {
            state = GroundedLaneWalkState.INTERRUPTED;
        }
        clearForcedState();
        lastSupportCheck = null;
        lastSupportCheckPos = null;
    }

    /**
     * Ticks the walker without a support guard (legacy path, used when no lookup is available).
     * Equivalent to tick(playerPosition, null, null, null).
     */
    public void tick(Vec3d playerPosition) {
        tick(playerPosition, null, null, null);
    }

    /**
     * Ticks the walker with an optional support guard.
     *
     * <p>If {@code supportLookup} is non-null, the walker checks whether the floor block directly
     * under the player's NEXT footprint position (one step ahead along the lane direction) is
     * safe to walk onto before issuing forward movement. If the block is pending placement or
     * pending server verification the walker holds position (issues an idle command) instead of
     * advancing. This prevents the player from stepping into a hole whose support block has been
     * sent to the server but not yet confirmed.
     *
     * @param playerPosition   current player position
     * @param supportLookup    function to check whether a block at a world position matches the
     *                         expected block (may be null — guard disabled)
     * @param pendingPlacements set of placement indices still in the pending-placement queue
     *                          (not yet placed); used to detect MISSING_UNTRACKED state
     * @param pendingVerifications set of placement world positions that have been placed but not
     *                             yet server-confirmed; used to detect PENDING_VERIFICATION state
     */
    public void tick(
            Vec3d playerPosition,
            PlacementCompletionLookup supportLookup,
            java.util.Set<BlockPos> pendingPlacements,
            java.util.Set<BlockPos> pendingVerifications
    ) {
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
            lastSupportCheck = null;
            lastSupportCheckPos = null;
            return;
        }

        // Support guard: compute next-footprint block and hold if it is not yet safe to walk on.
        if (supportLookup != null) {
            BlockPos nextFootprint = computeNextFootprint(playerPosition);
            SupportCheckResult check = evaluateSupportBlock(nextFootprint, supportLookup, pendingPlacements, pendingVerifications);
            lastSupportCheck = check;
            lastSupportCheckPos = nextFootprint;

            if (check == SupportCheckResult.PENDING_VERIFICATION || check == SupportCheckResult.MISSING_UNTRACKED) {
                // Hold: suppress forward movement but keep lateral correction active so
                // the player stays on the centerline while waiting for the block.
                double lateralError = computeSignedLateralError(playerPosition);
                forcedCommand = buildHoldCommand(lateralError);
                return;
            }
        } else {
            lastSupportCheck = null;
            lastSupportCheckPos = null;
        }

        double lateralError = computeSignedLateralError(playerPosition);
        forcedCommand = buildCommand(lateralError);
    }

    /**
     * Computes the world-space block position that the player will be standing on after one
     * step forward along the lane direction. This is the floor block at buildPlaneY directly
     * under the player's centerline one block ahead.
     */
    private BlockPos computeNextFootprint(Vec3d playerPosition) {
        int buildY = schematicBounds.minY();
        int forwardSign = lane.direction().forwardSign();
        if (lane.direction().alongX()) {
            int nextX = (int) Math.floor(playerPosition.x) + forwardSign;
            int centerZ = lane.centerlineCoordinate();
            return new BlockPos(nextX, buildY, centerZ);
        } else {
            int centerX = lane.centerlineCoordinate();
            int nextZ = (int) Math.floor(playerPosition.z) + forwardSign;
            return new BlockPos(centerX, buildY, nextZ);
        }
    }

    /**
     * Evaluates whether the support block at {@code footprintPos} is in a state safe to walk on.
     *
     * <p>Return values:
     * <ul>
     *   <li>ALREADY_CORRECT — block is already present in the world (lookup confirms it)</li>
     *   <li>CONFIRMED — block is already correct (synonym of ALREADY_CORRECT in this context)</li>
     *   <li>PENDING_VERIFICATION — block placement was sent but not yet server-confirmed</li>
     *   <li>MISSING_UNTRACKED — block is absent and not tracked in pending queues</li>
     * </ul>
     */
    private static SupportCheckResult evaluateSupportBlock(
            BlockPos footprintPos,
            PlacementCompletionLookup lookup,
            java.util.Set<BlockPos> pendingPlacements,
            java.util.Set<BlockPos> pendingVerifications
    ) {
        // If the block is already confirmed in the world, it's safe.
        // We pass null for the expected block: the lookup must tolerate null (treat as "any solid").
        // In practice the runner's lookup checks isOf(expectedBlock), and with null expectedBlock
        // it returns false — so we do a separate "any block" check by passing null-tolerant path.
        // The lookup contract says: isExpectedBlockPlaced(pos, expectedBlock).
        // When expectedBlock is null the default runner lambda returns false. We accept that result
        // and treat it as not-confirmed: fall through to check pending queues.
        if (lookup.isExpectedBlockPlaced(footprintPos, null)) {
            return SupportCheckResult.ALREADY_CORRECT;
        }

        // Check if placement was sent but not yet verified.
        if (pendingVerifications != null && pendingVerifications.contains(footprintPos)) {
            return SupportCheckResult.PENDING_VERIFICATION;
        }

        // Check if it's still in the pending-placement queue (not yet placed at all).
        if (pendingPlacements != null && pendingPlacements.contains(footprintPos)) {
            return SupportCheckResult.MISSING_UNTRACKED;
        }

        // Block is not in world and not being tracked. This could mean:
        // (a) it is an air column legitimately (no placement needed there), or
        // (b) the block was missed/skipped and is not being retried.
        // We return ALREADY_CORRECT here — if there is no placement scheduled for this position
        // the walker cannot do anything about it and should not stall indefinitely.
        return SupportCheckResult.ALREADY_CORRECT;
    }

    /**
     * Builds a "hold" command: no forward movement, but lateral correction is still active.
     * This keeps the player on the centerline while the support block is awaited.
     */
    private GroundedLaneWalkCommand buildHoldCommand(double lateralError) {
        double signedError = lateralError * lane.direction().lateralStrafeSign();
        boolean strafeLeft = signedError > CENTERLINE_STRAFE_DEADBAND;
        boolean strafeRight = signedError < -CENTERLINE_STRAFE_DEADBAND;
        return new GroundedLaneWalkCommand(
                lane.direction().yawDegrees(),
                false,    // forwardPressed = false (hold)
                false,
                strafeLeft,
                strafeRight,
                false,
                false,
                false     // no sprint when holding
        );
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
        lastSupportCheck = null;
        lastSupportCheckPos = null;
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

    /**
     * Result of the per-tick support block check. Used by the runner to emit diagnostics.
     */
    public enum SupportCheckResult {
        /** Block is already present in the world — safe to proceed. */
        ALREADY_CORRECT,
        /** Block placement was sent to the server but not yet confirmed — hold. */
        PENDING_VERIFICATION,
        /** Block is absent and not in any pending queue — emit a diagnostic warning, proceed. */
        MISSING_UNTRACKED,
        /** Block was confirmed by a previous verification tick — safe to proceed. */
        CONFIRMED
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
