package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class GroundedLaneWalker {
    private static final double CENTERLINE_STRAFE_DEADBAND = 0.08;

    /**
     * Maximum ticks to hold when an EXPECTED_MISSING_UNTRACKED block is detected.
     * After this many hold ticks, fail() is called with a descriptive reason (~2 seconds).
     */
    static final int MAX_SUPPORT_HOLD_TICKS_MISSING_UNTRACKED = 40;

    /**
     * Maximum ticks to hold when a block is EXPECTED_PENDING_VERIFICATION or
     * EXPECTED_PENDING_PLACEMENT (~4 seconds).
     */
    static final int MAX_SUPPORT_HOLD_TICKS_PENDING = 80;

    /**
     * Lateral proximity threshold: if the player is within this many blocks of a lateral
     * block boundary, we also check the adjacent lateral column in the footprint.
     */
    private static final double LATERAL_BOUNDARY_PROXIMITY = 0.4;

    private GroundedSweepLane lane;
    private GroundedSchematicBounds schematicBounds;
    private boolean constantSprint;
    private GroundedLaneWalkState state = GroundedLaneWalkState.IDLE;
    private GroundedLaneWalkCommand forcedCommand = GroundedLaneWalkCommand.idle();
    private String failureReason = "";

    // Support-guard state — lifecycle:
    //   supportHoldTicks: incremented each tick that forward movement is held due to a support
    //   check result that is not safe (EXPECTED_PENDING_VERIFICATION, EXPECTED_PENDING_PLACEMENT,
    //   or EXPECTED_MISSING_UNTRACKED). Reset to 0 whenever movement resumes (check passes) or
    //   when the walker is reset/started/interrupted/failed. The bounded hold uses two timeout
    //   thresholds: MAX_SUPPORT_HOLD_TICKS_MISSING_UNTRACKED (40 ticks) for EXPECTED_MISSING_UNTRACKED
    //   (→ fail() with diagnostic), and MAX_SUPPORT_HOLD_TICKS_PENDING (80 ticks) for pending
    //   states (→ also fail() with diagnostic).
    //
    //   lastSupportCheck: most recent check result across the full walking footprint (worst case).
    //   lastSupportCheckPos: footprint position that produced lastSupportCheck. When multiple
    //   positions are checked, this is the first non-safe position found.
    //   Cleared entirely (null) when the walker is not ACTIVE or has never run a support check.
    private int supportHoldTicks = 0;
    private SupportCheckResult lastSupportCheck = null;
    private BlockPos lastSupportCheckPos = null;

    public void start(GroundedSweepLane lane, GroundedSchematicBounds schematicBounds, boolean constantSprint) {
        this.lane = Objects.requireNonNull(lane, "lane");
        this.schematicBounds = Objects.requireNonNull(schematicBounds, "schematicBounds");
        this.constantSprint = constantSprint;
        this.state = GroundedLaneWalkState.ACTIVE;
        this.forcedCommand = buildCommand(0.0);
        this.failureReason = "";
        this.supportHoldTicks = 0;
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

    /** Returns the current support hold tick count. Resets to 0 when movement resumes. */
    public int supportHoldTicks() {
        return supportHoldTicks;
    }

    public void interrupt() {
        if (state == GroundedLaneWalkState.ACTIVE) {
            state = GroundedLaneWalkState.INTERRUPTED;
        }
        clearForcedState();
        supportHoldTicks = 0;
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
     * Ticks the walker with an optional schematic-aware support guard.
     *
     * <p>If {@code targetLookup} is non-null, the walker evaluates the full walking footprint
     * (centerline ahead + player lateral offset column + adjacent if near boundary) at buildPlaneY.
     * For each position, it classifies the block state using {@link SupportCheckResult}:
     * <ul>
     *   <li>{@code EXPECTED_AND_CONFIRMED} and {@code NOT_EXPECTED_AIR_OK} — safe, movement continues</li>
     *   <li>{@code EXPECTED_PENDING_VERIFICATION} and {@code EXPECTED_PENDING_PLACEMENT} — hold
     *       (bounded up to {@link #MAX_SUPPORT_HOLD_TICKS_PENDING} ticks, then fail)</li>
     *   <li>{@code EXPECTED_MISSING_UNTRACKED} — hold briefly
     *       (up to {@link #MAX_SUPPORT_HOLD_TICKS_MISSING_UNTRACKED} ticks), then fail with
     *       a diagnostic reason so recovery can handle it</li>
     * </ul>
     *
     * @param playerPosition       current player position
     * @param targetLookup         schematic-target lookup: returns {@code true} if a block is
     *                             required at a world position and not yet confirmed; {@code false}
     *                             if no placement is needed or the block is already present. May
     *                             be null to disable the guard entirely.
     * @param pendingPlacements    world positions of blocks in the pending-placement queue
     *                             (scheduled but not yet placed); used to distinguish
     *                             EXPECTED_PENDING_PLACEMENT from EXPECTED_MISSING_UNTRACKED
     * @param pendingVerifications world positions of blocks placed but not yet server-confirmed;
     *                             used to classify EXPECTED_PENDING_VERIFICATION
     */
    public void tick(
            Vec3d playerPosition,
            SchematicTargetLookup targetLookup,
            Set<BlockPos> pendingPlacements,
            Set<BlockPos> pendingVerifications
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
            supportHoldTicks = 0;
            lastSupportCheck = null;
            lastSupportCheckPos = null;
            return;
        }

        // Support guard: evaluate the full walking footprint.
        if (targetLookup != null) {
            List<BlockPos> footprint = computeFootprint(playerPosition);
            SupportCheckResult worstCheck = SupportCheckResult.EXPECTED_AND_CONFIRMED;
            BlockPos worstPos = footprint.isEmpty() ? null : footprint.get(0);

            for (BlockPos pos : footprint) {
                SupportCheckResult check = evaluateSupportBlock(pos, targetLookup, pendingPlacements, pendingVerifications);
                if (check.ordinal() > worstCheck.ordinal()) {
                    worstCheck = check;
                    worstPos = pos;
                }
            }

            lastSupportCheck = worstCheck;
            lastSupportCheckPos = worstPos;

            // Determine if we need to hold.
            boolean shouldHold = switch (worstCheck) {
                case EXPECTED_AND_CONFIRMED, NOT_EXPECTED_AIR_OK -> false;
                case EXPECTED_PENDING_VERIFICATION, EXPECTED_PENDING_PLACEMENT,
                     EXPECTED_MISSING_UNTRACKED -> true;
            };

            if (shouldHold) {
                supportHoldTicks++;

                // Bounded hold: EXPECTED_MISSING_UNTRACKED has a shorter timeout and fails
                // to recovery so the build does not stall indefinitely waiting for a block
                // that was never scheduled or was irrecoverably missed.
                if (worstCheck == SupportCheckResult.EXPECTED_MISSING_UNTRACKED
                        && supportHoldTicks >= MAX_SUPPORT_HOLD_TICKS_MISSING_UNTRACKED) {
                    fail("support block missing and untracked at " + (worstPos != null ? worstPos.toShortString() : "?")
                            + " after " + supportHoldTicks + " ticks hold");
                    return;
                }

                // Pending states (verification / placement) get a longer timeout before
                // failing — the server may be lagging. If they time out we fail to recovery
                // so the system can reposition and retry.
                if ((worstCheck == SupportCheckResult.EXPECTED_PENDING_VERIFICATION
                        || worstCheck == SupportCheckResult.EXPECTED_PENDING_PLACEMENT)
                        && supportHoldTicks >= MAX_SUPPORT_HOLD_TICKS_PENDING) {
                    fail("support block " + worstCheck.name() + " at "
                            + (worstPos != null ? worstPos.toShortString() : "?")
                            + " after " + supportHoldTicks + " ticks hold — server may be lagging");
                    return;
                }

                // Still within timeout: hold (suppress forward movement, keep lateral correction).
                double lateralError = computeSignedLateralError(playerPosition);
                forcedCommand = buildHoldCommand(lateralError);
                return;
            }

            // Movement is safe — reset hold counter.
            supportHoldTicks = 0;
        } else {
            // Guard disabled: clear state.
            supportHoldTicks = 0;
            lastSupportCheck = null;
            lastSupportCheckPos = null;
        }

        double lateralError = computeSignedLateralError(playerPosition);
        forcedCommand = buildCommand(lateralError);
    }

    /**
     * Computes the walking footprint: the set of 2–3 block positions at buildPlaneY that the
     * player is about to step on. Always includes:
     * <ol>
     *   <li>The centerline column one step ahead along the lane direction.</li>
     *   <li>The player's current lateral offset column (floor of player lateral coordinate).</li>
     *   <li>If the player is within {@value #LATERAL_BOUNDARY_PROXIMITY} blocks of a lateral
     *       block boundary, also the adjacent column (the block the player is spilling into).</li>
     * </ol>
     *
     * <p>For EAST/WEST lanes (along X) the lateral axis is Z; for NORTH/SOUTH (along Z) it is X.
     */
    private List<BlockPos> computeFootprint(Vec3d playerPosition) {
        int buildY = schematicBounds.minY();
        List<BlockPos> footprint = new ArrayList<>(3);

        if (lane.direction().alongX()) {
            // Progress axis: X. Lateral axis: Z.
            int forwardSign = lane.direction().forwardSign();
            int nextX = (int) Math.floor(playerPosition.x) + forwardSign;

            // 1. Centerline ahead.
            int centerZ = lane.centerlineCoordinate();
            footprint.add(new BlockPos(nextX, buildY, centerZ));

            // 2. Player's actual lateral (Z) column.
            double playerZ = playerPosition.z;
            int playerZFloor = (int) Math.floor(playerZ);
            if (playerZFloor != centerZ) {
                footprint.add(new BlockPos(nextX, buildY, playerZFloor));
            }

            // 3. Adjacent lateral column if player is near a Z block boundary.
            double zFrac = playerZ - playerZFloor;
            if (zFrac < LATERAL_BOUNDARY_PROXIMITY) {
                int adjacentZ = playerZFloor - 1;
                if (adjacentZ != centerZ && !footprint.contains(new BlockPos(nextX, buildY, adjacentZ))) {
                    footprint.add(new BlockPos(nextX, buildY, adjacentZ));
                }
            } else if (zFrac > (1.0 - LATERAL_BOUNDARY_PROXIMITY)) {
                int adjacentZ = playerZFloor + 1;
                if (adjacentZ != centerZ && !footprint.contains(new BlockPos(nextX, buildY, adjacentZ))) {
                    footprint.add(new BlockPos(nextX, buildY, adjacentZ));
                }
            }
        } else {
            // Progress axis: Z. Lateral axis: X.
            int forwardSign = lane.direction().forwardSign();
            int nextZ = (int) Math.floor(playerPosition.z) + forwardSign;

            // 1. Centerline ahead.
            int centerX = lane.centerlineCoordinate();
            footprint.add(new BlockPos(centerX, buildY, nextZ));

            // 2. Player's actual lateral (X) column.
            double playerX = playerPosition.x;
            int playerXFloor = (int) Math.floor(playerX);
            if (playerXFloor != centerX) {
                footprint.add(new BlockPos(playerXFloor, buildY, nextZ));
            }

            // 3. Adjacent lateral column if player is near an X block boundary.
            double xFrac = playerX - playerXFloor;
            if (xFrac < LATERAL_BOUNDARY_PROXIMITY) {
                int adjacentX = playerXFloor - 1;
                if (adjacentX != centerX && !footprint.contains(new BlockPos(adjacentX, buildY, nextZ))) {
                    footprint.add(new BlockPos(adjacentX, buildY, nextZ));
                }
            } else if (xFrac > (1.0 - LATERAL_BOUNDARY_PROXIMITY)) {
                int adjacentX = playerXFloor + 1;
                if (adjacentX != centerX && !footprint.contains(new BlockPos(adjacentX, buildY, nextZ))) {
                    footprint.add(new BlockPos(adjacentX, buildY, nextZ));
                }
            }
        }

        return footprint;
    }

    /**
     * Classifies whether the support block at {@code footprintPos} is safe to walk on.
     *
     * <p>Classification rules (in priority order):
     * <ol>
     *   <li>If {@code targetLookup.isRequiredAndUnconfirmed()} returns {@code false}:
     *       → {@code NOT_EXPECTED_AIR_OK} — no placement needed or already confirmed, safe.</li>
     *   <li>If the block is in {@code pendingVerifications} (placed, awaiting server confirmation):
     *       → {@code EXPECTED_PENDING_VERIFICATION} — hold.</li>
     *   <li>If the block is in {@code pendingPlacements} (scheduled but not yet placed):
     *       → {@code EXPECTED_PENDING_PLACEMENT} — hold.</li>
     *   <li>Otherwise: block is expected but absent and not tracked anywhere:
     *       → {@code EXPECTED_MISSING_UNTRACKED} — hold briefly then fail to recovery.</li>
     * </ol>
     */
    private static SupportCheckResult evaluateSupportBlock(
            BlockPos footprintPos,
            SchematicTargetLookup targetLookup,
            Set<BlockPos> pendingPlacements,
            Set<BlockPos> pendingVerifications
    ) {
        boolean requiredAndUnconfirmed = targetLookup.isRequiredAndUnconfirmed(footprintPos);

        if (!requiredAndUnconfirmed) {
            // No block required, or already confirmed in world — safe.
            // The runner returns false for both "no placement needed" and "already confirmed".
            // Both map to safe movement.
            return SupportCheckResult.NOT_EXPECTED_AIR_OK;
        }

        // A block is required and not yet confirmed in the world.
        // Check if placement was sent but not yet verified.
        if (pendingVerifications != null && pendingVerifications.contains(footprintPos)) {
            return SupportCheckResult.EXPECTED_PENDING_VERIFICATION;
        }

        // Check if it's still in the pending-placement queue (not yet placed at all).
        if (pendingPlacements != null && pendingPlacements.contains(footprintPos)) {
            return SupportCheckResult.EXPECTED_PENDING_PLACEMENT;
        }

        // Block is expected by schematic, not in world, not in any tracking queue.
        // NEVER silently proceed: hold briefly then fail to recovery.
        return SupportCheckResult.EXPECTED_MISSING_UNTRACKED;
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
        supportHoldTicks = 0;
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
     * Result of the per-tick support block check across the full walking footprint.
     * Enum ordinal is used to rank severity — higher ordinal = less safe.
     * The footprint check picks the worst (highest ordinal) result.
     *
     * <p>Only {@code EXPECTED_AND_CONFIRMED} and {@code NOT_EXPECTED_AIR_OK} allow movement.
     * All other states cause the walker to hold forward movement.
     */
    public enum SupportCheckResult {
        /** Block expected by schematic and confirmed present in world — safe to proceed. */
        EXPECTED_AND_CONFIRMED,
        /** No block required at this position (air column) — safe to proceed. */
        NOT_EXPECTED_AIR_OK,
        /** Block expected, placed but not yet server-confirmed — hold (bounded). */
        EXPECTED_PENDING_VERIFICATION,
        /** Block expected, in pending-placement queue but not yet placed — hold (bounded). */
        EXPECTED_PENDING_PLACEMENT,
        /** Block expected by schematic, absent from world, not tracked anywhere — hold briefly then fail. */
        EXPECTED_MISSING_UNTRACKED
    }

    /**
     * Schematic-aware lookup used by the support guard to determine whether a floor block
     * is required at a given world position and is not yet confirmed in the world.
     *
     * <p>Implementations return:
     * <ul>
     *   <li>{@code false} — no placement is required at this position (air is acceptable), OR
     *       the required block is already confirmed present in the world</li>
     *   <li>{@code true} — a block is required at this position and has NOT yet been confirmed
     *       in the world (i.e., it needs tracking)</li>
     * </ul>
     *
     * <p>The runner builds this by combining the schematic's placement map with a world-state
     * check: if the schematic requires a block at {@code worldPos} and it is not yet confirmed,
     * return true; otherwise return false.
     */
    @FunctionalInterface
    public interface SchematicTargetLookup {
        /**
         * Returns {@code true} if a placement is required at worldPos and is not yet confirmed
         * in the world. Returns {@code false} if no placement is needed or the block is already
         * present.
         */
        boolean isRequiredAndUnconfirmed(BlockPos worldPos);
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
