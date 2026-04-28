package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.MapArtMod;
import com.example.mapart.baritone.BaritoneFacade;
import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.state.BuildSession;
import com.example.mapart.plan.state.PlacementExecutor;
import com.example.mapart.plan.state.PlacementResult;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

public final class GroundedSingleLaneDebugRunner {
    private static final int MAX_PLACEMENT_ATTEMPTS_PER_TICK = 2;
    private static final int PLACEMENT_VERIFICATION_DELAY_TICKS = 3;
    private static final double LANE_SHIFT_REACH_DISTANCE_SQ = 1.5 * 1.5;
    private static final double LANE_TRANSITION_AXIS_TOLERANCE = 0.6;
    private static final int MAX_LANE_TRANSITION_TICKS = 120;
    private static final int MAX_TRANSITION_SUPPORT_TICKS = 80;
    private static final int MAX_TRANSITION_SUPPORT_ATTEMPTS_PER_TICK = 2;
    private static final int MIN_LANE_YAW_LOCK_TICKS = 2;
    private static final int MAX_LANE_YAW_LOCK_TICKS = 10;
    private static final float LANE_YAW_LOCK_TOLERANCE_DEGREES = 1.5f;
    private static final int GROUNDED_TRACE_SNAPSHOT_INTERVAL_TICKS = 20;
    private static final double START_APPROACH_NEAR_RADIUS_SQ = 2.25;
    private static final double START_APPROACH_FLAT_DISTANCE_TOLERANCE = 1.0;
    private static final double START_APPROACH_CENTERLINE_TOLERANCE = 0.8;
    private static final double START_APPROACH_FORWARD_TOLERANCE = 1.0;
    private static final int MAX_START_APPROACH_TICKS = 200;
    private static final int MAX_START_APPROACH_NO_PROGRESS_TICKS = 80;
    private static final double START_APPROACH_PROGRESS_EPSILON_SQ = 0.01;
    private static final int MAX_LANE_ENTRY_SUPPORT_TICKS = 60;
    private static final int MAX_LANE_ENTRY_ATTEMPTS_PER_TICK = 2;
    private static final int MAX_LANE_ENTRY_STEP_TICKS = 40;
    private static final double LANE_ENTRY_STEP_REACH_DISTANCE_SQ = 0.35 * 0.35;
    private static final double TRANSITION_WALKING_DISABLE_SPRINT_DELTA = 1.5;

    private final GroundedSweepLanePlanner lanePlanner = new GroundedSweepLanePlanner();
    private final GroundedLaneWalker laneWalker = new GroundedLaneWalker();
    private final GroundedDisplacementAlert displacementAlert = new GroundedDisplacementAlert();
    private final PlacementExecutor placementExecutor = new PlacementExecutor();
    private final GroundedSweepResumeScanner resumeScanner = new GroundedSweepResumeScanner();
    private final BaritoneFacade baritoneFacade;

    private BuildSession activeSession;
    private GroundedSchematicBounds activeBounds;
    private GroundedSweepLane activeLane;
    private GroundedSweepSettings activeSettings;
    private GroundedSweepPlacementExecutor placementSelector;
    private final GroundedLaneTransitionSupportPlanner transitionSupportPlanner = new GroundedLaneTransitionSupportPlanner();
    private Map<Integer, Placement> lanePlacementsByIndex = Map.of();
    private Map<Integer, Placement> transitionSupportPlacementsByIndex = Map.of();
    private List<GroundedSweepPlacementExecutor.PlacementTarget> pendingPlacementTargets = List.of();
    private Map<Integer, PendingPlacementVerification> pendingVerificationsByPlacement = Map.of();
    private List<GroundedSweepPlacementExecutor.PlacementTarget> pendingTransitionSupportTargets = List.of();
    private Map<Integer, PendingPlacementVerification> pendingTransitionSupportVerifications = Map.of();
    private List<GroundedSweepLeftoverTracker.GroundedLeftoverRecord> currentLeftovers = List.of();
    private int successfulPlacements;
    private int failedPlacements;
    private int missedPlacements;
    private long laneTicksElapsed;
    private boolean awaitingStartApproach;
    private boolean startApproachIssued;
    private boolean awaitingLaneShift;
    private boolean awaitingTransitionSupport;
    private BlockPos laneShiftTarget;
    private GroundedSweepLane pendingShiftLane;
    private LaneShiftPlan laneShiftPlan;
    private LaneTransitionStage laneTransitionStage = LaneTransitionStage.ALIGN_FORWARD_AXIS;
    private int laneTransitionTicks;
    private LaneStartStage laneStartStage = LaneStartStage.NONE;
    private GroundedSweepLane pendingLaneStart;
    private int pendingLaneStartTicks;
    private int laneEntryStepTicks;
    private int startApproachTicks;
    private double startApproachBestDistanceSq = Double.POSITIVE_INFINITY;
    private int startApproachNoProgressTicks;
    private long transitionSupportTicks;
    private int transitionSupportAlreadyCorrectCount;
    private int transitionSupportPlacedCount;
    private int transitionSupportFailedCount;

    private SweepRunMode runMode = SweepRunMode.SINGLE_LANE;
    private List<GroundedSweepLane> forwardLanes = List.of();
    private List<GroundedSweepLane> reverseLanes = List.of();
    private int laneCursor;
    private SweepPassPhase sweepPassPhase = SweepPassPhase.FORWARD;
    private boolean smartResumeUsed;
    private GroundedSweepResumePoint selectedResumePoint;
    private int skippedCompletedForwardLanes;
    private GroundedLaneEntryAnchor laneEntryAnchor;
    private final Set<Integer> forwardLeftoverPlacements = new LinkedHashSet<>();
    private final Set<Integer> reversePlacementFilter = new LinkedHashSet<>();
    private final List<String> groundedTraceEvents = new ArrayList<>();

    private boolean groundedTraceEnabled;
    private long lastGroundedSnapshotTick = -1;
    private long groundedTraceTickCounter;
    private boolean corridorWarningActive;
    private GroundedLaneDirection lastTransitionDirection;
    private float lastTransitionYaw;
    private boolean lastTransitionSprint;
    private boolean lastTransitionOvershootCorrected;

    private DebugStatus lastStatus = new DebugStatus(
            false,
            null,
            GroundedLaneWalker.GroundedLaneWalkState.IDLE,
            false,
            false,
            Optional.empty(),
            0,
            0,
            0,
            0,
            0,
            List.of(),
            SweepPassPhase.FORWARD,
            false,
            Optional.empty(),
            0
    );

    public GroundedSingleLaneDebugRunner(BaritoneFacade baritoneFacade) {
        this.baritoneFacade = Objects.requireNonNull(baritoneFacade, "baritoneFacade");
    }

    public boolean groundedTraceEnabled() {
        return groundedTraceEnabled;
    }

    public void setGroundedTraceEnabled(boolean enabled) {
        groundedTraceEnabled = enabled;
        traceGroundedEvent("groundedTrace=" + enabled);
    }

    List<String> groundedTraceEventsForTests() {
        return List.copyOf(groundedTraceEvents);
    }

    public Optional<String> start(BuildSession session, int laneIndex, GroundedSweepSettings settings) {
        Optional<String> validation = validateStart(session, settings);
        if (validation.isPresent()) {
            return validation;
        }

        GroundedSchematicBounds bounds = GroundedSchematicBounds.fromPlan(session.getPlan(), session.getOrigin());
        List<GroundedSweepLane> lanes = lanePlanner.planLanes(bounds, settings);
        if (laneIndex < 0 || laneIndex >= lanes.size()) {
            return Optional.of("Requested lane index " + laneIndex + " does not exist. Available range: 0-" + (lanes.size() - 1) + ".");
        }

        initializeRunState(session, bounds, settings, SweepRunMode.SINGLE_LANE, lanes, List.of(), SweepPassPhase.FORWARD);
        activateLane(lanes.get(laneIndex), Set.of());
        traceGroundedEvent("single lane start selected: " + describeLane(activeLane));
        return Optional.empty();
    }

    public Optional<String> startFullSweep(BuildSession session, GroundedSweepSettings settings) {
        Optional<String> validation = validateStart(session, settings);
        if (validation.isPresent()) {
            return validation;
        }

        GroundedSchematicBounds bounds = GroundedSchematicBounds.fromPlan(session.getPlan(), session.getOrigin());
        List<GroundedSweepLane> lanes = lanePlanner.planLanes(bounds, settings);
        if (lanes.isEmpty()) {
            return Optional.of("Grounded sweep did not produce any lanes for this schematic footprint.");
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null && client.world != null) {
            PlacementCompletionLookup lookup = (worldPos, expectedBlock) -> expectedBlock != null && client.world.getBlockState(worldPos).isOf(expectedBlock);
            return startFullSweepFromSelection(session, settings, bounds, lanes, lookup, client.player.getEntityPos(), true);
        }

        List<GroundedSweepLane> reverse = buildReverseSweepLanes(lanes);
        initializeRunState(session, bounds, settings, SweepRunMode.FULL_SWEEP, lanes, reverse, SweepPassPhase.FORWARD);
        laneCursor = 0;
        smartResumeUsed = false;
        selectedResumePoint = null;
        skippedCompletedForwardLanes = 0;
        activateLane(forwardLanes.getFirst(), Set.of());
        traceGroundedEvent("full sweep start selected: " + describeLane(activeLane));
        return Optional.empty();
    }

    public Optional<String> startFullSweepSmart(
            BuildSession session,
            GroundedSweepSettings settings,
            Vec3d playerPosition,
            PlacementCompletionLookup completionLookup
    ) {
        Optional<String> validation = validateStart(session, settings);
        if (validation.isPresent()) {
            return validation;
        }

        GroundedSchematicBounds bounds = GroundedSchematicBounds.fromPlan(session.getPlan(), session.getOrigin());
        List<GroundedSweepLane> lanes = lanePlanner.planLanes(bounds, settings);
        if (lanes.isEmpty()) {
            return Optional.of("Grounded sweep did not produce any lanes for this schematic footprint.");
        }

        return startFullSweepFromSelection(session, settings, bounds, lanes, completionLookup, playerPosition, true);
    }

    private Optional<String> startFullSweepFromSelection(
            BuildSession session,
            GroundedSweepSettings settings,
            GroundedSchematicBounds bounds,
            List<GroundedSweepLane> lanes,
            PlacementCompletionLookup completionLookup,
            Vec3d playerPosition,
            boolean useSmartResume
    ) {
        GroundedSweepResumeSelection selection = resumeScanner.scan(
                session,
                bounds,
                lanes,
                settings.sweepHalfWidth(),
                playerPosition,
                completionLookup
        );
        if (selection.buildComplete()) {
            return Optional.of("Grounded full sweep smart resume found no unfinished placements. Build appears complete.");
        }

        GroundedSweepResumePoint resumePoint = selection.resumePoint().orElseThrow();
        List<GroundedSweepLane> reverse = buildReverseSweepLanes(lanes);
        initializeRunState(session, bounds, settings, SweepRunMode.FULL_SWEEP, lanes, reverse, SweepPassPhase.FORWARD);

        int selectedLaneIndex = Math.max(0, Math.min(resumePoint.laneIndex(), forwardLanes.size() - 1));
        laneCursor = selectedLaneIndex;
        smartResumeUsed = useSmartResume;
        selectedResumePoint = resumePoint;
        traceGroundedEvent("smart resume selected: " + describeResumePoint(resumePoint));
        skippedCompletedForwardLanes = selectedLaneIndex;
        laneEntryAnchor = resumePoint.reason() == GroundedSweepResumePoint.ResumeReason.PARTIAL_LANE
                ? buildLaneEntryAnchorFromResumePoint(forwardLanes.get(selectedLaneIndex), bounds, resumePoint)
                : buildLaneEntryAnchorForFreshStart(forwardLanes.get(selectedLaneIndex), bounds, LaneEntrySource.FRESH_START);

        Optional<Integer> minimumProgress = resumePoint.reason() == GroundedSweepResumePoint.ResumeReason.PARTIAL_LANE
                ? Optional.of(resumePoint.progressCoordinate())
                : Optional.empty();
        activateLaneData(forwardLanes.get(selectedLaneIndex), Set.of(), minimumProgress);
        awaitingStartApproach = true;
        startApproachIssued = false;
        awaitingLaneShift = false;
        laneShiftTarget = null;
        laneShiftPlan = null;
        laneTransitionStage = LaneTransitionStage.ALIGN_FORWARD_AXIS;
        laneTransitionTicks = 0;
        lastStatus = new DebugStatus(
                true,
                activeLane.laneIndex(),
                GroundedLaneWalker.GroundedLaneWalkState.IDLE,
                true,
                false,
                Optional.empty(),
                laneTicksElapsed,
                successfulPlacements,
                missedPlacements,
                failedPlacements,
                0,
                currentLeftovers,
                sweepPassPhase,
                smartResumeUsed,
                Optional.ofNullable(selectedResumePoint),
                skippedCompletedForwardLanes
        );
        return Optional.empty();
    }

    private BlockPos activeStartApproachTarget() {
        if (laneEntryAnchor != null && activeLane != null && laneEntryAnchor.laneIndex() == activeLane.laneIndex()) {
            return laneEntryAnchor.stagingTarget();
        }
        return buildLaneEntryAnchorForFreshStart(activeLane, activeBounds, LaneEntrySource.FRESH_START).stagingTarget();
    }

    public void tick(MinecraftClient client, boolean constantSprint) {
        groundedTraceTickCounter++;
        if (client == null || client.player == null || activeLane == null || activeBounds == null || activeSession == null) {
            return;
        }
        traceGroundedSnapshot(client);

        if (awaitingStartApproach) {
            tickStartApproach(client, constantSprint);
            return;
        }
        if (awaitingLaneShift) {
            tickLaneShift(client, constantSprint);
            return;
        }
        if (awaitingTransitionSupport) {
            tickTransitionSupport(client);
            return;
        }
        if (laneStartStage != LaneStartStage.NONE) {
            tickPendingLaneStart(client, constantSprint);
            return;
        }

        if (laneWalker.state() != GroundedLaneWalker.GroundedLaneWalkState.ACTIVE) {
            handleTerminalState(client);
            return;
        }

        laneWalker.tick(client.player.getEntityPos());
        laneTicksElapsed++;
        processDuePlacementVerifications(client, laneTicksElapsed, false);
        boolean walkerActiveAfterTick = shouldAttemptPlacementAfterWalkerTick(laneWalker.state());
        if (walkerActiveAfterTick) {
            tickPlacementExecutor(client);
        }
        applyLaneControls(client);
        displacementAlert.tick(client, true, client.player.getY() < activeBounds.minY());
        if (!insideCorridorForDiagnostics(client.player.getEntityPos())) {
            if (!corridorWarningActive) {
                corridorWarningActive = true;
                traceGroundedEvent("corridor warning / leaving corridor: " + describeLane(activeLane));
            }
        } else if (corridorWarningActive) {
            corridorWarningActive = false;
            traceGroundedEvent("corridor warning cleared");
        }

        if (!walkerActiveAfterTick) {
            handleTerminalState(client);
        }
    }

    public Optional<String> stop() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            clearControls(client);
        }
        if (!isActive()) {
            return Optional.empty();
        }

        awaitingStartApproach = false;
        traceGroundedEvent("awaitingStartApproach=false");
        awaitingLaneShift = false;
        traceGroundedEvent("awaitingLaneShift=false");
        laneShiftTarget = null;
        pendingShiftLane = null;
        laneShiftPlan = null;
        laneWalker.interrupt();
        baritoneFacade.cancel();
        traceGroundedEvent("Baritone cancelled");
        handleTerminalState(client);
        return Optional.empty();
    }

    public DebugStatus status() {
        if (isActive()) {
            return new DebugStatus(
                    true,
                    activeLane.laneIndex(),
                    laneWalker.state(),
                    awaitingStartApproach,
                    awaitingLaneShift,
                    laneWalker.failureReason(),
                    laneTicksElapsed,
                    successfulPlacements,
                    missedPlacements,
                    failedPlacements,
                    pendingVerificationsByPlacement.size(),
                    currentLeftovers,
                    sweepPassPhase,
                    smartResumeUsed,
                    Optional.ofNullable(selectedResumePoint),
                    skippedCompletedForwardLanes
            );
        }
        return lastStatus;
    }

    private void tickStartApproach(MinecraftClient client, boolean constantSprint) {
        issueStartApproachIfNeeded();

        BlockPos stagingTarget = activeStartApproachTarget();
        Vec3d playerPosition = client.player.getEntityPos();
        startApproachTicks++;
        double flatDistanceSq = flatDistanceToStandingTargetSq(playerPosition, stagingTarget);
        if (flatDistanceSq + START_APPROACH_PROGRESS_EPSILON_SQ < startApproachBestDistanceSq) {
            startApproachBestDistanceSq = flatDistanceSq;
            startApproachNoProgressTicks = 0;
        } else {
            startApproachNoProgressTicks++;
        }

        if (startApproachTicks > MAX_START_APPROACH_TICKS || startApproachNoProgressTicks > MAX_START_APPROACH_NO_PROGRESS_TICKS) {
            failLaneStart(client, "Unable to reach valid lane start staging position");
            return;
        }
        if (!isNearLaneStart(playerPosition, stagingTarget)) {
            return;
        }

        baritoneFacade.cancel();
        traceGroundedEvent("Baritone approach reached/cancelled at " + stagingTarget.toShortString());
        awaitingStartApproach = false;
        clearControls(client);
        if (!queueLaneStart(client, activeLane, LaneStartStage.AWAITING_LANE_ENTRY_SUPPORT)) {
            failLaneStart(client, "Unable to prepare lane entry before starting lane walk.");
            return;
        }
        captureAwaitingLaneStartStatus();
    }


    private void tickPendingLaneStart(MinecraftClient client, boolean constantSprint) {
        if (pendingLaneStart == null || activeLane == null || activeBounds == null) {
            failLaneStart(client, "Lane start preparation state is invalid.");
            return;
        }
        if (laneStartStage == LaneStartStage.AWAITING_LANE_ENTRY_SUPPORT) {
            pendingLaneStartTicks++;
            if (pendingLaneStartTicks > MAX_LANE_ENTRY_SUPPORT_TICKS) {
                failLaneStart(client, "Timed out building lane start entry support.");
                return;
            }
            if (!attemptLaneEntrySupport(client, pendingLaneStart, activeBounds, laneEntryAnchorForActiveLane(pendingLaneStart, activeBounds))) {
                return;
            }
            laneStartStage = LaneStartStage.AWAITING_LANE_ENTRY_STEP;
            laneEntryStepTicks = 0;
            pendingLaneStartTicks = 0;
            traceGroundedEvent("lane entry support ready");
        }

        if (laneStartStage == LaneStartStage.AWAITING_LANE_ENTRY_STEP) {
            laneEntryStepTicks++;
            if (laneEntryStepTicks > MAX_LANE_ENTRY_STEP_TICKS) {
                failLaneStart(client, "Timed out stepping onto lane start.");
                return;
            }
            if (!stepToLaneEntryTarget(client, pendingLaneStart, laneEntryAnchorForActiveLane(pendingLaneStart, activeBounds))) {
                return;
            }
            clearControls(client);
            laneStartStage = LaneStartStage.LOCK_LANE_YAW;
            pendingLaneStartTicks = 0;
            traceGroundedEvent("lane entry step complete");
        }

        if (laneStartStage == LaneStartStage.LOCK_LANE_YAW) {
            if (!forcePlayerLaneYaw(client, pendingLaneStart)) {
                traceGroundedEvent("lane yaw lock failed");
                failLaneStart(client, "Unable to lock lane yaw/body/head before starting lane walk.");
                return;
            }
            pendingLaneStartTicks++;
            if (pendingLaneStartTicks > MAX_LANE_YAW_LOCK_TICKS) {
                traceGroundedEvent("lane yaw lock failed: timeout");
                failLaneStart(client, "Lane yaw/body/head lock timed out before lane walk could begin.");
                return;
            }
            if (pendingLaneStartTicks < MIN_LANE_YAW_LOCK_TICKS) {
                return;
            }
            laneStartStage = LaneStartStage.START_WALKER;
            traceGroundedEvent("lane yaw lock confirmed");
        }

        if (laneStartStage == LaneStartStage.START_WALKER) {
            if (!forcePlayerLaneYaw(client, pendingLaneStart)) {
                traceGroundedEvent("lane yaw lock failed before walker start");
                failLaneStart(client, "Unable to confirm lane yaw/body/head lock before starting lane walk.");
                return;
            }
            laneWalker.start(activeLane, activeBounds, constantSprint);
            traceGroundedEvent("lane walker started");
            laneStartStage = LaneStartStage.NONE;
            pendingLaneStart = null;
            pendingLaneStartTicks = 0;
            lastStatus = new DebugStatus(
                    true,
                    activeLane.laneIndex(),
                    GroundedLaneWalker.GroundedLaneWalkState.ACTIVE,
                    false,
                    false,
                    Optional.empty(),
                    laneTicksElapsed,
                    successfulPlacements,
                    missedPlacements,
                    failedPlacements,
                    pendingVerificationsByPlacement.size(),
                    currentLeftovers,
                    sweepPassPhase,
                    smartResumeUsed,
                    Optional.ofNullable(selectedResumePoint),
                    skippedCompletedForwardLanes
            );
            applyLaneControls(client);
        }
    }

    private boolean queueLaneStart(MinecraftClient client, GroundedSweepLane lane, LaneStartStage initialStage) {
        if (lane == null) {
            return false;
        }
        clearControls(client);
        pendingLaneStart = lane;
        laneEntryAnchor = laneEntryAnchorForActiveLane(lane, activeBounds);
        laneStartStage = initialStage == null ? LaneStartStage.LOCK_LANE_YAW : initialStage;
        traceGroundedEvent("lane start stage entered: " + laneStartStage);
        pendingLaneStartTicks = 0;
        laneEntryStepTicks = 0;
        return true;
    }

    private static LaneStartStage laneStartStageForCurrentPosition(Vec3d playerPosition, GroundedSweepLane lane, GroundedSchematicBounds bounds) {
        if (playerPosition == null || lane == null || bounds == null) {
            return LaneStartStage.AWAITING_LANE_ENTRY_SUPPORT;
        }
        return insideCorridor(playerPosition, lane, bounds)
                ? LaneStartStage.LOCK_LANE_YAW
                : LaneStartStage.AWAITING_LANE_ENTRY_SUPPORT;
    }

    private boolean attemptLaneEntrySupport(
            MinecraftClient client,
            GroundedSweepLane lane,
            GroundedSchematicBounds bounds,
            GroundedLaneEntryAnchor entryAnchor
    ) {
        if (activeSession == null || client == null || client.world == null || lane == null || bounds == null) {
            return false;
        }
        int startProgress = entryAnchor.progressCoordinate();
        int attempts = 0;
        for (GroundedSweepPlacementExecutor.PlacementTarget target : pendingPlacementTargets) {
            if (attempts >= MAX_LANE_ENTRY_ATTEMPTS_PER_TICK) {
                break;
            }
            BlockPos pos = target.worldPos();
            int progress = lane.direction().alongX() ? pos.getX() : pos.getZ();
            if (progress != startProgress) {
                continue;
            }
            if (Math.abs(lateralDeltaFromCenterline(lane.direction(), lane.centerlineCoordinate(), pos)) > activeSettings.sweepHalfWidth()) {
                continue;
            }
            Placement placement = lanePlacementsByIndex.get(target.placementIndex());
            if (placement == null || placement.block() == null) {
                continue;
            }
            PlacementResult result = placementExecutor.execute(client, activeSession, placement, pos);
            switch (result.status()) {
                case PLACED -> onPlacementPlaced(target.placementIndex(), placement, pos, laneTicksElapsed);
                case ALREADY_CORRECT -> onPlacementResult(target.placementIndex(), GroundedSweepPlacementExecutor.PlacementResult.SUCCESS, laneTicksElapsed);
                case RETRY, MOVE_REQUIRED -> {
                    // Keep pending and retry during the next entry-support tick.
                }
                case MISSING_ITEM, ERROR -> onFinalFailure(target.placementIndex());
            }
            attempts++;
        }
        processDuePlacementVerifications(client, laneTicksElapsed, true);
        return !hasPendingStartCrossSectionTargets(lane, entryAnchor.progressCoordinate());
    }

    private boolean hasPendingStartCrossSectionTargets(GroundedSweepLane lane, int progressCoordinate) {
        if (lane == null || pendingPlacementTargets.isEmpty()) {
            return false;
        }
        for (GroundedSweepPlacementExecutor.PlacementTarget target : pendingPlacementTargets) {
            BlockPos pos = target.worldPos();
            int progress = lane.direction().alongX() ? pos.getX() : pos.getZ();
            if (progress == progressCoordinate
                    && Math.abs(lateralDeltaFromCenterline(lane.direction(), lane.centerlineCoordinate(), pos)) <= activeSettings.sweepHalfWidth()) {
                return true;
            }
        }
        return false;
    }

    private boolean stepToLaneEntryTarget(MinecraftClient client, GroundedSweepLane lane, GroundedLaneEntryAnchor entryAnchor) {
        if (client == null || client.player == null || lane == null) {
            return false;
        }
        Vec3d playerPos = client.player.getEntityPos();
        BlockPos entryStandingTarget = entryAnchor.entryStandingTarget();
        Vec3d entryCenter = new Vec3d(entryStandingTarget.getX() + 0.5, playerPos.y, entryStandingTarget.getZ() + 0.5);
        if (new Vec3d(playerPos.x, entryCenter.y, playerPos.z).squaredDistanceTo(entryCenter) <= LANE_ENTRY_STEP_REACH_DISTANCE_SQ
                && insideCorridor(playerPos, lane, activeBounds)) {
            return true;
        }
        applyShiftControls(client, lane.direction(), false);
        return false;
    }

    private void failLaneStart(MinecraftClient client, String reason) {
        traceGroundedEvent("lane failed: " + reason);
        laneStartStage = LaneStartStage.NONE;
        pendingLaneStart = null;
        pendingLaneStartTicks = 0;
        laneEntryStepTicks = 0;
        laneWalker.interrupt();
        captureLastStatus(GroundedLaneWalker.GroundedLaneWalkState.FAILED, Optional.of(reason));
        if (client != null) {
            clearControls(client);
        }
        clearActiveRunState();
    }

    private void captureAwaitingLaneStartStatus() {
        lastStatus = new DebugStatus(
                true,
                activeLane == null ? null : activeLane.laneIndex(),
                GroundedLaneWalker.GroundedLaneWalkState.IDLE,
                false,
                false,
                Optional.empty(),
                laneTicksElapsed,
                successfulPlacements,
                missedPlacements,
                failedPlacements,
                pendingVerificationsByPlacement.size(),
                currentLeftovers,
                sweepPassPhase,
                smartResumeUsed,
                Optional.ofNullable(selectedResumePoint),
                skippedCompletedForwardLanes
        );
    }

    void issueStartApproachIfNeeded() {
        if (awaitingStartApproach && !startApproachIssued && activeLane != null && activeBounds != null) {
            BlockPos approachTarget = activeStartApproachTarget();
            traceGroundedEvent("Baritone approach target issued: " + approachTarget.toShortString());
            baritoneFacade.goTo(approachTarget);
            startApproachIssued = true;
        }
    }

    void advanceSweepToNextLaneForTests() {
        captureLaneLeftoversForPass();
        tryAdvanceSweepToNextLane();
    }

    void completeLaneShiftIfNearForTests(Vec3d playerPosition, boolean constantSprint) {
        if (!awaitingLaneShift || playerPosition == null || laneShiftPlan == null || pendingShiftLane == null) {
            return;
        }
        if (laneTransitionStage == LaneTransitionStage.ALIGN_FORWARD_AXIS && isForwardAxisAligned(playerPosition, pendingShiftLane)) {
            laneTransitionStage = LaneTransitionStage.SHIFT_TO_CENTERLINE;
            traceGroundedEvent("lane transition stage changed: " + laneTransitionStage);
        }
        if (laneTransitionStage == LaneTransitionStage.SHIFT_TO_CENTERLINE && isCenterlineAligned(playerPosition, pendingShiftLane)) {
            laneTransitionStage = LaneTransitionStage.START_NEXT_LANE;
            traceGroundedEvent("lane transition stage changed: " + laneTransitionStage);
        }
        if (laneTransitionStage == LaneTransitionStage.START_NEXT_LANE) {
            beginShiftedLaneForTests(constantSprint);
        }
    }

    GroundedLaneDirection laneShiftDirectionForTests(Vec3d playerPosition) {
        if (!awaitingLaneShift || laneShiftPlan == null || pendingShiftLane == null || playerPosition == null) {
            return null;
        }
        if (laneTransitionStage == LaneTransitionStage.ALIGN_FORWARD_AXIS && !isForwardAxisAligned(playerPosition, pendingShiftLane)) {
            return forwardAlignmentDirection(playerPosition, pendingShiftLane);
        }
        if (laneTransitionStage == LaneTransitionStage.SHIFT_TO_CENTERLINE && !isCenterlineAligned(playerPosition, pendingShiftLane)) {
            return centerlineAlignmentDirection(playerPosition, pendingShiftLane);
        }
        return null;
    }

    void forceLaneTransitionTimeoutForTests(String reason) {
        failLaneTransition(null, reason);
    }

    void forceStartApproachTimeoutForTests() {
        failLaneStart(null, "Unable to reach valid lane start staging position");
    }

    Optional<BlockPos> laneShiftTargetForTests() {
        return Optional.ofNullable(laneShiftTarget);
    }

    Optional<BlockPos> laneEntryStandingTargetForTests() {
        return laneEntryAnchor == null ? Optional.empty() : Optional.of(laneEntryAnchor.entryStandingTarget());
    }

    Optional<BlockPos> laneEntryStagingTargetForTests() {
        return laneEntryAnchor == null ? Optional.empty() : Optional.of(laneEntryAnchor.stagingTarget());
    }

    Optional<Integer> laneEntryProgressCoordinateForTests() {
        return laneEntryAnchor == null ? Optional.empty() : Optional.of(laneEntryAnchor.progressCoordinate());
    }

    Optional<LaneShiftPlan> laneShiftPlanForTests() {
        return Optional.ofNullable(laneShiftPlan);
    }

    boolean awaitingTransitionSupportForTests() {
        return awaitingTransitionSupport;
    }

    List<BlockPos> transitionSupportWorldPositionsForTests() {
        return pendingTransitionSupportTargets.stream().map(GroundedSweepPlacementExecutor.PlacementTarget::worldPos).toList();
    }

    List<GroundedSweepPlacementExecutor.PlacementTarget> transitionSupportTargetsForTests() {
        return List.copyOf(pendingTransitionSupportTargets);
    }

    void keepOnlyTransitionSupportTargetForTests(int placementIndex) {
        if (pendingTransitionSupportTargets.isEmpty()) {
            return;
        }
        List<GroundedSweepPlacementExecutor.PlacementTarget> retained = new ArrayList<>();
        for (GroundedSweepPlacementExecutor.PlacementTarget target : pendingTransitionSupportTargets) {
            if (target.placementIndex() == placementIndex) {
                retained.add(target);
            }
        }
        pendingTransitionSupportTargets = List.copyOf(retained);
        if (!transitionSupportPlacementsByIndex.isEmpty()) {
            Placement placement = transitionSupportPlacementsByIndex.get(placementIndex);
            transitionSupportPlacementsByIndex = placement == null ? Map.of() : Map.of(placementIndex, placement);
        }
    }

    void recordTransitionSupportPlacedForTests(int placementIndex, BlockPos worldPos, long supportTick) {
        pendingTransitionSupportVerifications = mutableTransitionSupportVerifications();
        pendingTransitionSupportVerifications.put(
                placementIndex,
                new PendingPlacementVerification(placementIndex, worldPos, null, supportTick + PLACEMENT_VERIFICATION_DELAY_TICKS)
        );
        removePendingTransitionSupportTarget(placementIndex);
    }

    int pendingTransitionSupportVerificationCountForTests() {
        return pendingTransitionSupportVerifications.size();
    }

    void processTransitionSupportVerificationsForTests(Map<Integer, Boolean> matchesByPlacementIndex, long supportTick) {
        processTransitionSupportVerifications(
                null,
                supportTick,
                false,
                pending -> matchesByPlacementIndex.getOrDefault(pending.placementIndex(), false)
        );
        if (awaitingTransitionSupport && transitionSupportReady()) {
            completeTransitionSupportPhase();
        }
    }

    void completeTransitionSupportForTests() {
        pendingTransitionSupportTargets = List.of();
        pendingTransitionSupportVerifications = Map.of();
        if (awaitingTransitionSupport) {
            completeTransitionSupportPhase();
        }
    }

    void failTransitionSupportForTests() {
        failLaneTransition(null, "Unable to build safe transition support path");
    }

    Optional<GroundedLaneWalker.GroundedLaneWalkCommand> laneWalkCommandForTests() {
        return laneWalker.currentCommand();
    }

    void finalizeTerminalStateForTests(GroundedLaneWalker.GroundedLaneWalkState terminalState, Optional<String> failureReason) {
        captureLastStatus(terminalState, failureReason);
        clearActiveRunState();
    }

    void seedLanePlacementsForTests(List<GroundedSweepPlacementExecutor.PlacementTarget> pendingPlacements) {
        pendingPlacementTargets = List.copyOf(pendingPlacements);
        currentLeftovers = List.of();
    }

    void tickPlacementSelectionForTests(int currentProgressCoordinate, long tick) {
        if (activeLane == null || activeBounds == null || placementSelector == null) {
            return;
        }
        GroundedSweepPlacementExecutor.SweepSelection selection = placementSelector.select(
                activeLane,
                activeBounds,
                currentProgressCoordinate,
                tick,
                pendingPlacementTargets
        );
        currentLeftovers = selection.leftovers();
    }

    List<Integer> pendingPlacementIndicesForTests() {
        return pendingPlacementTargets.stream()
                .map(GroundedSweepPlacementExecutor.PlacementTarget::placementIndex)
                .toList();
    }

    List<BlockPos> pendingPlacementWorldPositionsForTests() {
        return pendingPlacementTargets.stream()
                .map(GroundedSweepPlacementExecutor.PlacementTarget::worldPos)
                .toList();
    }

    List<Integer> rankedPlacementIndicesForTests(int currentProgressCoordinate, long tick) {
        if (activeLane == null || activeBounds == null || placementSelector == null) {
            return List.of();
        }
        return placementSelector.select(activeLane, activeBounds, currentProgressCoordinate, tick, pendingPlacementTargets)
                .rankedCandidates()
                .stream()
                .map(GroundedSweepPlacementExecutor.SweepCandidate::placementIndex)
                .toList();
    }

    void recordPlacementOutcomeForTests(int placementIndex, GroundedSweepPlacementExecutor.PlacementResult result, long tick) {
        if (placementSelector == null) {
            return;
        }
        onPlacementResult(placementIndex, result, tick);
    }

    void recordPlacementPlacedForTests(int placementIndex, BlockPos worldPos, long tick) {
        pendingVerificationsByPlacement = mutablePendingVerifications();
        pendingVerificationsByPlacement.put(
                placementIndex,
                new PendingPlacementVerification(placementIndex, worldPos, null, tick + PLACEMENT_VERIFICATION_DELAY_TICKS)
        );
        removePendingPlacement(placementIndex);
    }

    int pendingVerificationCountForTests() {
        return pendingVerificationsByPlacement.size();
    }

    boolean hasPendingVerificationForTests(int placementIndex) {
        return pendingVerificationsByPlacement.containsKey(placementIndex);
    }

    void processPendingVerificationsForTests(Map<Integer, Boolean> matchesByPlacementIndex, long tick) {
        processDuePlacementVerifications(tick, false, pending -> matchesByPlacementIndex.getOrDefault(pending.placementIndex(), false));
    }

    void processAllPendingVerificationsForTests(Map<Integer, Boolean> matchesByPlacementIndex, long tick) {
        processDuePlacementVerifications(tick, true, pending -> matchesByPlacementIndex.getOrDefault(pending.placementIndex(), false));
    }

    void recordFinalFailureForTests(int placementIndex) {
        if (placementSelector == null) {
            return;
        }
        onFinalFailure(placementIndex);
    }

    static boolean shouldAttemptPlacementAfterWalkerTick(GroundedLaneWalker.GroundedLaneWalkState state) {
        return state == GroundedLaneWalker.GroundedLaneWalkState.ACTIVE;
    }

    private void handleTerminalState(MinecraftClient client) {
        processTerminalPendingVerifications(client);

        GroundedLaneWalker.GroundedLaneWalkState terminalState = laneWalker.state();
        Optional<String> failureReason = laneWalker.failureReason();
        if (terminalState == GroundedLaneWalker.GroundedLaneWalkState.COMPLETE) {
            runFinalEndpointPlacementPass(client);
        }
        traceGroundedEvent("lane walker terminal state: " + terminalState + failureReason.map(s -> " (" + s + ")").orElse(""));
        captureLaneLeftoversForPass();

        if (terminalState == GroundedLaneWalker.GroundedLaneWalkState.COMPLETE && runMode == SweepRunMode.FULL_SWEEP) {
            if (tryAdvanceSweepToNextLane()) {
                if (client != null) {
                    clearControls(client);
                }
                return;
            }
        }

        if (terminalState == GroundedLaneWalker.GroundedLaneWalkState.COMPLETE && sweepPassPhase == SweepPassPhase.COMPLETE) {
            traceGroundedEvent("run complete");
        }
        if (terminalState == GroundedLaneWalker.GroundedLaneWalkState.FAILED) {
            traceGroundedEvent("lane walker failed: " + failureReason.orElse("unknown"));
        }

        captureLastStatus(terminalState, failureReason);
        if (client != null) {
            clearControls(client);
        }
        clearActiveRunState();
    }

    private void runFinalEndpointPlacementPass(MinecraftClient client) {
        if (client == null || client.player == null || activeLane == null || activeBounds == null || activeSession == null || placementSelector == null) {
            return;
        }
        int endpointProgress = activeLane.direction().alongX() ? activeLane.endPoint().getX() : activeLane.endPoint().getZ();
        GroundedSweepPlacementExecutor.SweepSelection selection = placementSelector.select(
                activeLane,
                activeBounds,
                endpointProgress,
                laneTicksElapsed,
                pendingPlacementTargets
        );
        int attempts = 0;
        for (GroundedSweepPlacementExecutor.SweepCandidate candidate : selection.rankedCandidates()) {
            if (attempts >= MAX_PLACEMENT_ATTEMPTS_PER_TICK * 3) {
                break;
            }
            if (candidate.bucket() != GroundedSweepPlacementExecutor.ProgressBucket.CURRENT_CROSS_SECTION) {
                continue;
            }
            Placement placement = lanePlacementsByIndex.get(candidate.placementIndex());
            if (placement == null || placement.block() == null) {
                onFinalFailure(candidate.placementIndex());
                continue;
            }
            PlacementResult result = placementExecutor.execute(client, activeSession, placement, candidate.worldPos());
            switch (result.status()) {
                case PLACED -> onPlacementPlaced(candidate.placementIndex(), placement, candidate.worldPos(), laneTicksElapsed);
                case ALREADY_CORRECT -> onPlacementResult(candidate.placementIndex(), GroundedSweepPlacementExecutor.PlacementResult.SUCCESS, laneTicksElapsed);
                case RETRY, MOVE_REQUIRED -> onPlacementResult(candidate.placementIndex(), GroundedSweepPlacementExecutor.PlacementResult.MISSED, laneTicksElapsed);
                case MISSING_ITEM, ERROR -> onFinalFailure(candidate.placementIndex());
            }
            attempts++;
        }
        processTerminalPendingVerifications(client);
    }

    private void captureLaneLeftoversForPass() {
        if (placementSelector == null || activeLane == null || activeBounds == null) {
            return;
        }
        refreshCurrentLeftovers();
        if (sweepPassPhase == SweepPassPhase.FORWARD) {
            for (GroundedSweepLeftoverTracker.GroundedLeftoverRecord leftover : currentLeftovers) {
                forwardLeftoverPlacements.add(leftover.placementIndex());
            }
            for (GroundedSweepPlacementExecutor.PlacementTarget pendingPlacementTarget : pendingPlacementTargets) {
                forwardLeftoverPlacements.add(pendingPlacementTarget.placementIndex());
            }
            for (PendingPlacementVerification pending : pendingVerificationsByPlacement.values()) {
                forwardLeftoverPlacements.add(pending.placementIndex());
            }
        }
    }

    private boolean tryAdvanceSweepToNextLane() {
        laneCursor++;
        if (sweepPassPhase == SweepPassPhase.FORWARD && laneCursor < forwardLanes.size()) {
            activateLaneForNativeShift(forwardLanes.get(laneCursor), Set.of());
            return true;
        }

        if (sweepPassPhase == SweepPassPhase.FORWARD) {
            if (forwardLeftoverPlacements.isEmpty() || reverseLanes.isEmpty()) {
                sweepPassPhase = SweepPassPhase.COMPLETE;
                return false;
            }
            reversePlacementFilter.clear();
            reversePlacementFilter.addAll(forwardLeftoverPlacements);
            sweepPassPhase = SweepPassPhase.REVERSE;
            laneCursor = 0;
            activateLaneForNativeShift(reverseLanes.getFirst(), reversePlacementFilter);
            return true;
        }

        if (sweepPassPhase == SweepPassPhase.REVERSE && laneCursor < reverseLanes.size() && !reversePlacementFilter.isEmpty()) {
            activateLaneForNativeShift(reverseLanes.get(laneCursor), reversePlacementFilter);
            return true;
        }

        sweepPassPhase = SweepPassPhase.COMPLETE;
        return false;
    }

    private void captureLastStatus(GroundedLaneWalker.GroundedLaneWalkState state, Optional<String> failureReason) {
        Integer laneIndex = activeLane == null ? null : activeLane.laneIndex();
        lastStatus = new DebugStatus(
                false,
                laneIndex,
                state,
                false,
                false,
                failureReason == null ? Optional.empty() : failureReason,
                laneTicksElapsed,
                successfulPlacements,
                missedPlacements,
                failedPlacements,
                pendingVerificationsByPlacement.size(),
                currentLeftovers,
                sweepPassPhase,
                smartResumeUsed,
                Optional.ofNullable(selectedResumePoint),
                skippedCompletedForwardLanes
        );
    }

    private void clearActiveRunState() {
        activeSession = null;
        activeBounds = null;
        activeLane = null;
        activeSettings = null;
        placementSelector = null;
        lanePlacementsByIndex = Map.of();
        pendingPlacementTargets = List.of();
        pendingVerificationsByPlacement = Map.of();
        currentLeftovers = List.of();
        awaitingStartApproach = false;
        startApproachIssued = false;
        awaitingLaneShift = false;
        awaitingTransitionSupport = false;
        laneShiftTarget = null;
        pendingShiftLane = null;
        laneShiftPlan = null;
        laneTransitionStage = LaneTransitionStage.ALIGN_FORWARD_AXIS;
        laneTransitionTicks = 0;
        clearTransitionSupportState();
        laneStartStage = LaneStartStage.NONE;
        pendingLaneStart = null;
        pendingLaneStartTicks = 0;
        laneEntryStepTicks = 0;
        displacementAlert.reset();
        corridorWarningActive = false;
        lastGroundedSnapshotTick = -1;
        groundedTraceTickCounter = 0;
        lastTransitionDirection = null;
        lastTransitionYaw = 0.0f;
        lastTransitionSprint = false;
        lastTransitionOvershootCorrected = false;

        runMode = SweepRunMode.SINGLE_LANE;
        forwardLanes = List.of();
        reverseLanes = List.of();
        laneCursor = 0;
        sweepPassPhase = SweepPassPhase.FORWARD;
        smartResumeUsed = false;
        selectedResumePoint = null;
        skippedCompletedForwardLanes = 0;
        laneEntryAnchor = null;
        forwardLeftoverPlacements.clear();
        reversePlacementFilter.clear();
    }

    private void tickPlacementExecutor(MinecraftClient client) {
        if (activeLane == null || activeBounds == null || activeSession == null || placementSelector == null || pendingPlacementTargets.isEmpty()) {
            return;
        }

        BlockPos playerPos = client.player.getBlockPos();
        int currentProgress = activeLane.direction().alongX() ? playerPos.getX() : playerPos.getZ();
        GroundedSweepPlacementExecutor.SweepSelection selection = placementSelector.select(
                activeLane,
                activeBounds,
                currentProgress,
                laneTicksElapsed,
                pendingPlacementTargets
        );
        currentLeftovers = selection.leftovers();

        int attempts = 0;
        for (GroundedSweepPlacementExecutor.SweepCandidate candidate : selection.rankedCandidates()) {
            if (attempts >= MAX_PLACEMENT_ATTEMPTS_PER_TICK) {
                break;
            }
            Placement placement = lanePlacementsByIndex.get(candidate.placementIndex());
            if (placement == null || placement.block() == null) {
                onFinalFailure(candidate.placementIndex());
                continue;
            }

            PlacementResult result = placementExecutor.execute(client, activeSession, placement, candidate.worldPos());
            switch (result.status()) {
                case PLACED -> onPlacementPlaced(candidate.placementIndex(), placement, candidate.worldPos(), laneTicksElapsed);
                case ALREADY_CORRECT -> onPlacementResult(candidate.placementIndex(), GroundedSweepPlacementExecutor.PlacementResult.SUCCESS, laneTicksElapsed);
                case RETRY, MOVE_REQUIRED -> onPlacementResult(candidate.placementIndex(), GroundedSweepPlacementExecutor.PlacementResult.MISSED, laneTicksElapsed);
                case MISSING_ITEM, ERROR -> onFinalFailure(candidate.placementIndex());
            }
            attempts++;
        }

        currentLeftovers = placementSelector.select(activeLane, activeBounds, currentProgress, laneTicksElapsed, pendingPlacementTargets).leftovers();
    }

    private void onPlacementPlaced(int placementIndex, Placement placement, BlockPos worldPos, long tick) {
        if (placement == null || placement.block() == null) {
            onFinalFailure(placementIndex);
            return;
        }

        pendingVerificationsByPlacement = mutablePendingVerifications();
        pendingVerificationsByPlacement.put(
                placementIndex,
                new PendingPlacementVerification(placementIndex, worldPos, placement.block(), tick + PLACEMENT_VERIFICATION_DELAY_TICKS)
        );
        removePendingPlacement(placementIndex);
    }

    private void onPlacementResult(int placementIndex, GroundedSweepPlacementExecutor.PlacementResult groundedResult, long tick) {
        placementSelector.recordPlacementResult(placementIndex, groundedResult, tick);
        if (groundedResult == GroundedSweepPlacementExecutor.PlacementResult.SUCCESS) {
            successfulPlacements++;
            reversePlacementFilter.remove(placementIndex);
            removePendingPlacement(placementIndex);
            return;
        }
        if (groundedResult == GroundedSweepPlacementExecutor.PlacementResult.MISSED) {
            missedPlacements++;
            removePendingPlacement(placementIndex);
            return;
        }
        failedPlacements++;
        removePendingPlacement(placementIndex);
    }

    private void onFinalFailure(int placementIndex) {
        failedPlacements++;
        placementSelector.recordFinalFailure(placementIndex);
        removePendingVerification(placementIndex);
        removePendingPlacement(placementIndex);
    }

    private void removePendingPlacement(int placementIndex) {
        if (pendingPlacementTargets.isEmpty()) {
            return;
        }
        List<GroundedSweepPlacementExecutor.PlacementTarget> retained = new ArrayList<>(pendingPlacementTargets.size());
        for (GroundedSweepPlacementExecutor.PlacementTarget target : pendingPlacementTargets) {
            if (target.placementIndex() != placementIndex) {
                retained.add(target);
            }
        }
        pendingPlacementTargets = List.copyOf(retained);
    }

    private void removePendingVerification(int placementIndex) {
        if (pendingVerificationsByPlacement.isEmpty()) {
            return;
        }
        pendingVerificationsByPlacement = mutablePendingVerifications();
        pendingVerificationsByPlacement.remove(placementIndex);
        if (pendingVerificationsByPlacement.isEmpty()) {
            pendingVerificationsByPlacement = Map.of();
        }
    }

    private Map<Integer, PendingPlacementVerification> mutablePendingVerifications() {
        if (pendingVerificationsByPlacement instanceof LinkedHashMap<Integer, PendingPlacementVerification> linkedHashMap) {
            return linkedHashMap;
        }
        return new LinkedHashMap<>(pendingVerificationsByPlacement);
    }

    private void processTerminalPendingVerifications(MinecraftClient client) {
        if (pendingVerificationsByPlacement.isEmpty()) {
            return;
        }
        if (client == null || client.world == null) {
            processDuePlacementVerifications(laneTicksElapsed, true, pending -> false);
            return;
        }
        processDuePlacementVerifications(client, laneTicksElapsed, true);
    }

    private void processDuePlacementVerifications(MinecraftClient client, long tick, boolean forceAll) {
        processDuePlacementVerifications(tick, forceAll, pending -> verifyExpectedBlock(client, pending));
    }

    private void processDuePlacementVerifications(long tick, boolean forceAll, Predicate<PendingPlacementVerification> verifier) {
        if (placementSelector == null || pendingVerificationsByPlacement.isEmpty()) {
            return;
        }

        pendingVerificationsByPlacement = mutablePendingVerifications();
        boolean changed = false;
        Iterator<Map.Entry<Integer, PendingPlacementVerification>> iterator = pendingVerificationsByPlacement.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingPlacementVerification pending = iterator.next().getValue();
            if (!forceAll && tick < pending.verifyDueTick()) {
                continue;
            }

            if (verifier.test(pending)) {
                onPlacementResult(pending.placementIndex(), GroundedSweepPlacementExecutor.PlacementResult.SUCCESS, tick);
            } else {
                onPlacementResult(pending.placementIndex(), GroundedSweepPlacementExecutor.PlacementResult.MISSED, tick);
            }
            iterator.remove();
            changed = true;
        }

        if (pendingVerificationsByPlacement.isEmpty()) {
            pendingVerificationsByPlacement = Map.of();
        }
        if (changed) {
            refreshCurrentLeftovers();
        }
    }

    private boolean verifyExpectedBlock(MinecraftClient client, PendingPlacementVerification pending) {
        if (client == null || client.world == null || pending.expectedBlock() == null) {
            return false;
        }
        BlockState worldState = client.world.getBlockState(pending.worldPos());
        return worldState.isOf(pending.expectedBlock());
    }

    private void refreshCurrentLeftovers() {
        if (activeLane == null || activeBounds == null || placementSelector == null) {
            return;
        }
        currentLeftovers = placementSelector.select(
                activeLane,
                activeBounds,
                currentLaneProgressCoordinate(),
                laneTicksElapsed,
                pendingPlacementTargets
        ).leftovers();
    }

    private int currentLaneProgressCoordinate() {
        if (activeLane == null) {
            return 0;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return activeLane.direction().alongX() ? activeLane.startPoint().getX() : activeLane.startPoint().getZ();
        }

        BlockPos playerPos = client.player.getBlockPos();
        return activeLane.direction().alongX() ? playerPos.getX() : playerPos.getZ();
    }

    private static List<GroundedSweepPlacementExecutor.PlacementTarget> buildLanePlacementTargets(
            BuildPlan plan,
            BlockPos origin,
            GroundedSchematicBounds bounds,
            GroundedSweepLane lane,
            int sweepHalfWidth,
            Map<Integer, Placement> lanePlacementsByIndex,
            Set<Integer> placementFilter,
            Optional<Integer> minimumForwardProgressCoordinate
    ) {
        List<GroundedSweepPlacementExecutor.PlacementTarget> targets = new ArrayList<>();
        GroundedLaneCorridorBounds corridor = lane.corridorBounds();
        boolean hasFilter = placementFilter != null && !placementFilter.isEmpty();

        for (int i = 0; i < plan.placements().size(); i++) {
            if (hasFilter && !placementFilter.contains(i)) {
                continue;
            }
            Placement placement = plan.placements().get(i);
            BlockPos worldPos = origin.add(placement.relativePos());
            if (worldPos.getX() < bounds.minX() || worldPos.getX() > bounds.maxX()
                    || worldPos.getY() < bounds.minY() || worldPos.getY() > bounds.maxY()
                    || worldPos.getZ() < bounds.minZ() || worldPos.getZ() > bounds.maxZ()) {
                continue;
            }
            if (worldPos.getX() < corridor.minX() || worldPos.getX() > corridor.maxX()
                    || worldPos.getZ() < corridor.minZ() || worldPos.getZ() > corridor.maxZ()) {
                continue;
            }
            if (Math.abs(lateralDeltaFromCenterline(lane.direction(), lane.centerlineCoordinate(), worldPos)) > sweepHalfWidth) {
                continue;
            }
            if (minimumForwardProgressCoordinate.isPresent()) {
                int laneProgress = lane.direction().alongX() ? worldPos.getX() : worldPos.getZ();
                int resumeProgress = minimumForwardProgressCoordinate.get();
                int signedDelta = (laneProgress - resumeProgress) * lane.direction().forwardSign();
                if (signedDelta < 0) {
                    continue;
                }
            }
            lanePlacementsByIndex.put(i, placement);
            targets.add(new GroundedSweepPlacementExecutor.PlacementTarget(i, worldPos));
        }
        return List.copyOf(targets);
    }

    private static int lateralDeltaFromCenterline(GroundedLaneDirection direction, int centerlineCoordinate, BlockPos worldPos) {
        int lateralCoordinate = direction.alongX() ? worldPos.getZ() : worldPos.getX();
        return lateralCoordinate - centerlineCoordinate;
    }

    private void applyLaneControls(MinecraftClient client) {
        if (client.options == null || client.player == null) {
            return;
        }
        if (activeLane != null && !forcePlayerLaneYaw(client, activeLane)) {
            failLaneStart(client, "Lane yaw/body/head lock drifted during lane walk.");
            return;
        }

        GroundedLaneWalker.GroundedLaneWalkCommand command = laneWalker.currentCommand().orElse(GroundedLaneWalker.GroundedLaneWalkCommand.idle());
        float authoritativeYaw = activeLane == null ? command.yaw() : activeLane.direction().yawDegrees();
        client.player.setYaw(authoritativeYaw);
        setKey(client.options.forwardKey, command.forwardPressed());
        setKey(client.options.backKey, command.backPressed());
        setKey(client.options.leftKey, command.leftPressed());
        setKey(client.options.rightKey, command.rightPressed());
        setKey(client.options.jumpKey, command.jumpPressed());
        setKey(client.options.sneakKey, command.sneakPressed());
        client.player.setSprinting(command.sprinting());
    }

    private void tickLaneShift(MinecraftClient client, boolean constantSprint) {
        if (!awaitingLaneShift || pendingShiftLane == null || laneShiftPlan == null || client.player == null) {
            return;
        }
        laneTransitionTicks++;
        if (laneTransitionTicks > MAX_LANE_TRANSITION_TICKS) {
            traceGroundedEvent("lane failed: transition timeout");
            failLaneTransition(client, "Lane transition failed to reach next lane start");
            return;
        }
        Vec3d playerPosition = client.player.getEntityPos();
        lastTransitionOvershootCorrected = false;
        if (laneTransitionStage == LaneTransitionStage.ALIGN_FORWARD_AXIS) {
            if (isForwardAxisAligned(playerPosition, pendingShiftLane)) {
                laneTransitionStage = LaneTransitionStage.SHIFT_TO_CENTERLINE;
                traceGroundedEvent("lane transition stage changed: " + laneTransitionStage);
            } else {
                GroundedLaneDirection correctionDirection = forwardAlignmentDirection(playerPosition, pendingShiftLane);
                double delta = forwardAxisDelta(playerPosition, pendingShiftLane);
                boolean sprintEnabled = shouldSprintDuringTransition(constantSprint, Math.abs(delta));
                traceGroundedEvent("transition forward-axis correction direction=" + correctionDirection + " delta=" + delta);
                applyShiftControls(client, correctionDirection, sprintEnabled);
                recordTransitionDiagnostics(client, correctionDirection, sprintEnabled);
                return;
            }
        }
        if (laneTransitionStage == LaneTransitionStage.SHIFT_TO_CENTERLINE) {
            if (isCenterlineAligned(playerPosition, pendingShiftLane)) {
                laneTransitionStage = LaneTransitionStage.START_NEXT_LANE;
                traceGroundedEvent("lane transition stage changed: " + laneTransitionStage);
            } else {
                GroundedLaneDirection correctionDirection = centerlineAlignmentDirection(playerPosition, pendingShiftLane);
                boolean overshootCorrected = correctionDirection != laneShiftPlan.shiftDirection();
                double delta = centerlineDelta(playerPosition, pendingShiftLane);
                boolean sprintEnabled = shouldSprintDuringTransition(constantSprint, Math.abs(delta));
                traceGroundedEvent("transition centerline correction direction=" + correctionDirection + " delta=" + delta);
                if (overshootCorrected) {
                    traceGroundedEvent("transition centerline overshoot corrected direction=" + correctionDirection);
                }
                lastTransitionOvershootCorrected = overshootCorrected;
                applyShiftControls(client, correctionDirection, sprintEnabled);
                recordTransitionDiagnostics(client, correctionDirection, sprintEnabled);
                return;
            }
        }
        if (laneTransitionStage == LaneTransitionStage.START_NEXT_LANE) {
            traceGroundedEvent("transition staged for next lane");
            beginShiftedLane(client, constantSprint);
        }
    }

    private void recordTransitionDiagnostics(MinecraftClient client, GroundedLaneDirection direction, boolean sprintEnabled) {
        if (client == null || client.player == null) {
            return;
        }
        lastTransitionDirection = direction;
        lastTransitionYaw = direction.yawDegrees();
        lastTransitionSprint = sprintEnabled;
    }

    private static boolean shouldSprintDuringTransition(boolean constantSprint, double absoluteDelta) {
        return constantSprint && absoluteDelta > TRANSITION_WALKING_DISABLE_SPRINT_DELTA;
    }

    private void tickTransitionSupport(MinecraftClient client) {
        if (!awaitingTransitionSupport || activeSession == null || client.player == null || client.world == null) {
            return;
        }
        transitionSupportTicks++;
        processTransitionSupportVerifications(client, transitionSupportTicks, false);
        if (transitionSupportReady()) {
            completeTransitionSupportPhase();
            return;
        }
        if (transitionSupportTicks > MAX_TRANSITION_SUPPORT_TICKS) {
            failLaneTransition(client, "Unable to build safe transition support path");
            return;
        }

        int attempts = 0;
        for (GroundedSweepPlacementExecutor.PlacementTarget target : pendingTransitionSupportTargets) {
            if (attempts >= MAX_TRANSITION_SUPPORT_ATTEMPTS_PER_TICK) {
                break;
            }
            Placement placement = transitionSupportPlacementsByIndex.get(target.placementIndex());
            if (placement == null || placement.block() == null) {
                transitionSupportFailedCount++;
                failLaneTransition(client, "Unable to build safe transition support path");
                return;
            }
            PlacementResult result = placementExecutor.execute(client, activeSession, placement, target.worldPos());
            switch (result.status()) {
                case PLACED -> {
                    transitionSupportPlacedCount++;
                    pendingTransitionSupportVerifications = mutableTransitionSupportVerifications();
                    pendingTransitionSupportVerifications.put(
                            target.placementIndex(),
                            new PendingPlacementVerification(target.placementIndex(), target.worldPos(), placement.block(), transitionSupportTicks + PLACEMENT_VERIFICATION_DELAY_TICKS)
                    );
                    removePendingTransitionSupportTarget(target.placementIndex());
                }
                case ALREADY_CORRECT -> {
                    transitionSupportAlreadyCorrectCount++;
                    removePendingTransitionSupportTarget(target.placementIndex());
                }
                case RETRY, MOVE_REQUIRED -> {
                    // Keep target pending.
                }
                case MISSING_ITEM, ERROR -> {
                    transitionSupportFailedCount++;
                    failLaneTransition(client, "Unable to build safe transition support path");
                    return;
                }
            }
            attempts++;
        }
        processTransitionSupportVerifications(client, transitionSupportTicks, false);
        if (transitionSupportReady()) {
            completeTransitionSupportPhase();
        }
    }


    private void beginShiftedLaneForTests(boolean constantSprint) {
        awaitingLaneShift = false;
        laneShiftTarget = null;
        laneShiftPlan = null;
        laneTransitionStage = LaneTransitionStage.ALIGN_FORWARD_AXIS;
        laneTransitionTicks = 0;
        activeLane = pendingShiftLane;
        traceGroundedEvent("active lane changed: " + describeLane(activeLane));
        pendingShiftLane = null;
        laneWalker.start(activeLane, activeBounds, constantSprint);
        traceGroundedEvent("lane walker started");
        lastStatus = new DebugStatus(
                true,
                activeLane.laneIndex(),
                GroundedLaneWalker.GroundedLaneWalkState.ACTIVE,
                false,
                false,
                Optional.empty(),
                laneTicksElapsed,
                successfulPlacements,
                missedPlacements,
                failedPlacements,
                pendingVerificationsByPlacement.size(),
                currentLeftovers,
                sweepPassPhase,
                smartResumeUsed,
                Optional.ofNullable(selectedResumePoint),
                skippedCompletedForwardLanes
        );
    }

    private void beginShiftedLane(MinecraftClient client, boolean constantSprint) {
        GroundedSweepLane nextLane = pendingShiftLane;
        awaitingLaneShift = false;
        laneShiftTarget = null;
        laneShiftPlan = null;
        laneTransitionStage = LaneTransitionStage.ALIGN_FORWARD_AXIS;
        laneTransitionTicks = 0;
        activeLane = nextLane;
        traceGroundedEvent("active lane changed: " + describeLane(activeLane));
        pendingShiftLane = null;
        if (!queueLaneStart(client, activeLane, laneStartStageForCurrentPosition(client.player.getEntityPos(), activeLane, activeBounds))) {
            failLaneStart(client, "Unable to lock lane yaw before starting shifted lane walk.");
            return;
        }
        captureAwaitingLaneStartStatus();
    }

    private void failLaneTransition(MinecraftClient client, String reason) {
        traceGroundedEvent("lane failed: " + reason);
        awaitingLaneShift = false;
        awaitingTransitionSupport = false;
        laneShiftTarget = null;
        laneShiftPlan = null;
        pendingShiftLane = null;
        laneTransitionStage = LaneTransitionStage.ALIGN_FORWARD_AXIS;
        laneTransitionTicks = 0;
        clearTransitionSupportState();
        laneWalker.interrupt();
        captureLastStatus(GroundedLaneWalker.GroundedLaneWalkState.FAILED, Optional.of(reason));
        if (client != null) {
            clearControls(client);
        }
        clearActiveRunState();
    }

    private static boolean forcePlayerLaneYaw(MinecraftClient client, GroundedSweepLane lane) {
        if (client == null || client.player == null || lane == null) {
            return false;
        }
        float targetYaw = lane.direction().yawDegrees();
        forcePlayerYaw(client, targetYaw);
        return isLaneYawLocked(client, lane);
    }

    private static void forcePlayerYaw(MinecraftClient client, float targetYaw) {
        if (client == null || client.player == null) {
            return;
        }
        client.player.setYaw(targetYaw);
        applyFloatFieldIfPresent(client.player, "headYaw", targetYaw);
        applyFloatFieldIfPresent(client.player, "bodyYaw", targetYaw);
        applyFloatFieldIfPresent(client.player, "prevYaw", targetYaw);
        applyFloatFieldIfPresent(client.player, "prevHeadYaw", targetYaw);
        applyFloatFieldIfPresent(client.player, "prevBodyYaw", targetYaw);
    }

    private static boolean isLaneYawLocked(MinecraftClient client, GroundedSweepLane lane) {
        if (client == null || client.player == null || lane == null) {
            return false;
        }
        float targetYaw = lane.direction().yawDegrees();
        if (!yawWithinTolerance(client.player.getYaw(), targetYaw)) {
            return false;
        }
        Optional<Float> headYaw = readFloatField(client.player, "headYaw");
        if (headYaw.isPresent() && !yawWithinTolerance(headYaw.get(), targetYaw)) {
            return false;
        }
        Optional<Float> bodyYaw = readFloatField(client.player, "bodyYaw");
        return bodyYaw.isEmpty() || yawWithinTolerance(bodyYaw.get(), targetYaw);
    }

    private static boolean yawWithinTolerance(float actualYaw, float targetYaw) {
        return Math.abs(MathHelper.wrapDegrees(actualYaw - targetYaw)) <= LANE_YAW_LOCK_TOLERANCE_DEGREES;
    }

    private static void applyFloatFieldIfPresent(Object instance, String fieldName, float value) {
        readFloatField(instance, fieldName).ifPresent(ignored -> writeFloatField(instance, fieldName, value));
    }

    private static Optional<Float> readFloatField(Object instance, String fieldName) {
        if (instance == null) {
            return Optional.empty();
        }
        try {
            java.lang.reflect.Field field = instance.getClass().getField(fieldName);
            if (field.getType() != float.class && field.getType() != Float.class) {
                return Optional.empty();
            }
            return Optional.of(field.getFloat(instance));
        } catch (ReflectiveOperationException ignored) {
            return Optional.empty();
        }
    }

    private static void writeFloatField(Object instance, String fieldName, float value) {
        try {
            java.lang.reflect.Field field = instance.getClass().getField(fieldName);
            if (field.getType() == float.class || field.getType() == Float.class) {
                field.setFloat(instance, value);
            }
        } catch (ReflectiveOperationException ignored) {
            // Best-effort lock for versions exposing these fields.
        }
    }

    private static void applyShiftControls(MinecraftClient client, GroundedLaneDirection shiftDirection, boolean sprintEnabled) {
        if (client.options == null || client.player == null) {
            return;
        }
        forcePlayerYaw(client, shiftDirection.yawDegrees());
        setKey(client.options.forwardKey, true);
        setKey(client.options.backKey, false);
        setKey(client.options.leftKey, false);
        setKey(client.options.rightKey, false);
        setKey(client.options.jumpKey, false);
        setKey(client.options.sneakKey, false);
        client.player.setSprinting(sprintEnabled);
    }

    private static GroundedLaneEntryAnchor buildLaneEntryAnchorForFreshStart(
            GroundedSweepLane lane,
            GroundedSchematicBounds bounds,
            LaneEntrySource source
    ) {
        int y = bounds.minY() + 1;
        BlockPos entryStandingTarget = new BlockPos(lane.startPoint().getX(), y, lane.startPoint().getZ());
        return new GroundedLaneEntryAnchor(
                lane.laneIndex(),
                lane.direction(),
                entryStandingTarget,
                stagingTargetForEntry(lane.direction(), entryStandingTarget),
                lane.direction().alongX() ? lane.startPoint().getX() : lane.startPoint().getZ(),
                source
        );
    }

    private static GroundedLaneEntryAnchor buildLaneEntryAnchorFromResumePoint(
            GroundedSweepLane lane,
            GroundedSchematicBounds bounds,
            GroundedSweepResumePoint resumePoint
    ) {
        BlockPos entryStandingTarget = new BlockPos(
                resumePoint.standingPosition().getX(),
                bounds.minY() + 1,
                resumePoint.standingPosition().getZ()
        );
        return new GroundedLaneEntryAnchor(
                lane.laneIndex(),
                lane.direction(),
                entryStandingTarget,
                stagingTargetForEntry(lane.direction(), entryStandingTarget),
                resumePoint.progressCoordinate(),
                LaneEntrySource.PARTIAL_RESUME
        );
    }

    private GroundedLaneEntryAnchor laneEntryAnchorForActiveLane(GroundedSweepLane lane, GroundedSchematicBounds bounds) {
        if (lane == null || bounds == null) {
            return null;
        }
        if (laneEntryAnchor != null && laneEntryAnchor.laneIndex() == lane.laneIndex()) {
            return laneEntryAnchor;
        }
        if (selectedResumePoint != null
                && selectedResumePoint.laneIndex() == lane.laneIndex()
                && selectedResumePoint.reason() == GroundedSweepResumePoint.ResumeReason.PARTIAL_LANE) {
            laneEntryAnchor = buildLaneEntryAnchorFromResumePoint(lane, bounds, selectedResumePoint);
            return laneEntryAnchor;
        }
        laneEntryAnchor = buildLaneEntryAnchorForFreshStart(
                lane,
                bounds,
                runMode == SweepRunMode.FULL_SWEEP && !awaitingStartApproach ? LaneEntrySource.SHIFTED_LANE : LaneEntrySource.FRESH_START
        );
        return laneEntryAnchor;
    }

    private static BlockPos stagingTargetForEntry(GroundedLaneDirection direction, BlockPos entryStandingTarget) {
        return switch (direction) {
            case EAST -> new BlockPos(entryStandingTarget.getX() - 1, entryStandingTarget.getY(), entryStandingTarget.getZ());
            case WEST -> new BlockPos(entryStandingTarget.getX() + 1, entryStandingTarget.getY(), entryStandingTarget.getZ());
            case SOUTH -> new BlockPos(entryStandingTarget.getX(), entryStandingTarget.getY(), entryStandingTarget.getZ() - 1);
            case NORTH -> new BlockPos(entryStandingTarget.getX(), entryStandingTarget.getY(), entryStandingTarget.getZ() + 1);
        };
    }

    static BlockPos approachStagingTargetBeforeLaneStart(GroundedSweepLane lane, GroundedSchematicBounds bounds) {
        return buildLaneEntryAnchorForFreshStart(lane, bounds, LaneEntrySource.FRESH_START).stagingTarget();
    }

    static boolean isNearLaneStart(Vec3d playerPosition, BlockPos standingStartTarget) {
        return flatDistanceToStandingTargetSq(playerPosition, standingStartTarget) <= START_APPROACH_NEAR_RADIUS_SQ;
    }

    static boolean isReadyForLaneStart(
            Vec3d playerPosition,
            GroundedSweepLane lane,
            BlockPos standingStartTarget,
            GroundedSchematicBounds bounds
    ) {
        return evaluateLaneStartReadiness(playerPosition, lane, standingStartTarget, bounds).ready();
    }

    private static LaneStartReadiness evaluateLaneStartReadiness(
            Vec3d playerPosition,
            GroundedSweepLane lane,
            BlockPos standingStartTarget,
            GroundedSchematicBounds bounds
    ) {
        if (playerPosition == null || lane == null || standingStartTarget == null || bounds == null) {
            return LaneStartReadiness.notReady("missing start approach context", 0.0, 0.0, false, Double.POSITIVE_INFINITY);
        }
        double flatDistanceSq = flatDistanceToStandingTargetSq(playerPosition, standingStartTarget);
        Vec3d standingCenter = new Vec3d(standingStartTarget.getX() + 0.5, standingStartTarget.getY(), standingStartTarget.getZ() + 0.5);
        double playerForward = lane.direction().alongX() ? playerPosition.x : playerPosition.z;
        double standingForward = lane.direction().alongX() ? standingCenter.x : standingCenter.z;
        double laneStartForward = lane.direction().alongX() ? lane.startPoint().getX() + 0.5 : lane.startPoint().getZ() + 0.5;
        double centerlineTarget = lane.centerlineCoordinate() + 0.5;
        double playerLateral = lane.direction().alongX() ? playerPosition.z : playerPosition.x;
        double centerlineDelta = playerLateral - centerlineTarget;
        double forwardDeltaToStanding = (playerForward - standingForward) * lane.direction().forwardSign();
        double forwardDeltaFromLaneStart = (playerForward - laneStartForward) * lane.direction().forwardSign();
        boolean insideCorridor = insideCorridor(playerPosition, lane, bounds);

        if (flatDistanceSq > START_APPROACH_FLAT_DISTANCE_TOLERANCE * START_APPROACH_FLAT_DISTANCE_TOLERANCE) {
            return LaneStartReadiness.notReady("outside standing target distance tolerance", centerlineDelta, forwardDeltaToStanding, insideCorridor, flatDistanceSq);
        }
        if (!insideCorridor) {
            return LaneStartReadiness.notReady("outside lane corridor", centerlineDelta, forwardDeltaToStanding, insideCorridor, flatDistanceSq);
        }
        if (Math.abs(centerlineDelta) > START_APPROACH_CENTERLINE_TOLERANCE) {
            return LaneStartReadiness.notReady("outside centerline tolerance", centerlineDelta, forwardDeltaToStanding, insideCorridor, flatDistanceSq);
        }
        if (forwardDeltaFromLaneStart < -START_APPROACH_FORWARD_TOLERANCE) {
            return LaneStartReadiness.notReady("outside lane start edge", centerlineDelta, forwardDeltaToStanding, insideCorridor, flatDistanceSq);
        }
        if (Math.abs(forwardDeltaToStanding) > START_APPROACH_FORWARD_TOLERANCE) {
            return LaneStartReadiness.notReady("outside forward staging tolerance", centerlineDelta, forwardDeltaToStanding, insideCorridor, flatDistanceSq);
        }
        return LaneStartReadiness.ready(centerlineDelta, forwardDeltaToStanding, insideCorridor, flatDistanceSq);
    }

    private static double flatDistanceToStandingTargetSq(Vec3d playerPosition, BlockPos standingStartTarget) {
        if (playerPosition == null || standingStartTarget == null) {
            return Double.POSITIVE_INFINITY;
        }
        Vec3d standingCenter = new Vec3d(standingStartTarget.getX() + 0.5, standingStartTarget.getY(), standingStartTarget.getZ() + 0.5);
        Vec3d playerFlat = new Vec3d(playerPosition.x, standingCenter.y, playerPosition.z);
        return playerFlat.squaredDistanceTo(standingCenter);
    }

    private static boolean insideCorridor(Vec3d playerPosition, GroundedSweepLane lane, GroundedSchematicBounds bounds) {
        GroundedLaneCorridorBounds corridor = lane.corridorBounds();
        return playerPosition.x >= corridor.minX() && playerPosition.x <= corridor.maxX() + 1.0
                && playerPosition.z >= corridor.minZ() && playerPosition.z <= corridor.maxZ() + 1.0
                && playerPosition.x >= bounds.minX() && playerPosition.x <= bounds.maxX() + 1.0
                && playerPosition.z >= bounds.minZ() && playerPosition.z <= bounds.maxZ() + 1.0;
    }

    static boolean isNearLaneShiftTarget(Vec3d playerPosition, BlockPos shiftTarget) {
        Vec3d standingCenter = new Vec3d(shiftTarget.getX() + 0.5, shiftTarget.getY(), shiftTarget.getZ() + 0.5);
        Vec3d playerFlat = new Vec3d(playerPosition.x, standingCenter.y, playerPosition.z);
        return playerFlat.squaredDistanceTo(standingCenter) <= LANE_SHIFT_REACH_DISTANCE_SQ;
    }

    private static boolean isForwardAxisAligned(Vec3d playerPosition, GroundedSweepLane nextLane) {
        return Math.abs(forwardAxisDelta(playerPosition, nextLane)) <= LANE_TRANSITION_AXIS_TOLERANCE;
    }

    private static boolean isCenterlineAligned(Vec3d playerPosition, GroundedSweepLane nextLane) {
        return Math.abs(centerlineDelta(playerPosition, nextLane)) <= LANE_TRANSITION_AXIS_TOLERANCE;
    }

    private static GroundedLaneDirection forwardAlignmentDirection(Vec3d playerPosition, GroundedSweepLane nextLane) {
        double delta = forwardAxisDelta(playerPosition, nextLane);
        if (nextLane.direction().alongX()) {
            return delta >= 0.0 ? GroundedLaneDirection.EAST : GroundedLaneDirection.WEST;
        }
        return delta >= 0.0 ? GroundedLaneDirection.SOUTH : GroundedLaneDirection.NORTH;
    }

    private static double forwardAxisDelta(Vec3d playerPosition, GroundedSweepLane nextLane) {
        double targetForward = nextLane.direction().alongX()
                ? nextLane.startPoint().getX() + 0.5
                : nextLane.startPoint().getZ() + 0.5;
        double playerForward = nextLane.direction().alongX() ? playerPosition.x : playerPosition.z;
        return targetForward - playerForward;
    }

    private static GroundedLaneDirection centerlineAlignmentDirection(Vec3d playerPosition, GroundedSweepLane targetLane) {
        double delta = centerlineDelta(playerPosition, targetLane);
        if (targetLane.direction().alongX()) {
            return delta >= 0.0 ? GroundedLaneDirection.SOUTH : GroundedLaneDirection.NORTH;
        }
        return delta >= 0.0 ? GroundedLaneDirection.EAST : GroundedLaneDirection.WEST;
    }

    private static double centerlineDelta(Vec3d playerPosition, GroundedSweepLane targetLane) {
        double targetCenterline = targetLane.centerlineCoordinate() + 0.5;
        double lateralCoordinate = targetLane.direction().alongX() ? playerPosition.z : playerPosition.x;
        return targetCenterline - lateralCoordinate;
    }

    static LaneShiftPlan buildLaneShiftPlan(GroundedSweepLane fromLane, GroundedSweepLane toLane, GroundedSchematicBounds bounds) {
        GroundedSweepLane previousLane = fromLane == null ? toLane : fromLane;
        GroundedLaneDirection shiftDirection;
        if (toLane.direction().alongX()) {
            int delta = toLane.centerlineCoordinate() - previousLane.centerlineCoordinate();
            shiftDirection = delta >= 0 ? GroundedLaneDirection.SOUTH : GroundedLaneDirection.NORTH;
        } else {
            int delta = toLane.centerlineCoordinate() - previousLane.centerlineCoordinate();
            shiftDirection = delta >= 0 ? GroundedLaneDirection.EAST : GroundedLaneDirection.WEST;
        }
        BlockPos shiftTarget = approachStagingTargetBeforeLaneStart(toLane, bounds);
        return new LaneShiftPlan(shiftDirection, toLane.centerlineCoordinate(), shiftTarget, previousLane, toLane);
    }

    static LaneShiftPlan buildLaneShiftPlanForTests(GroundedSweepLane fromLane, GroundedSweepLane toLane, GroundedSchematicBounds bounds) {
        return buildLaneShiftPlan(fromLane, toLane, bounds);
    }

    static List<GroundedSweepPlacementExecutor.PlacementTarget> buildLanePlacementTargetsForTests(
            BuildPlan plan,
            BlockPos origin,
            GroundedSchematicBounds bounds,
            GroundedSweepLane lane,
            int sweepHalfWidth,
            Map<Integer, Placement> lanePlacementsByIndex
    ) {
        return buildLanePlacementTargets(plan, origin, bounds, lane, sweepHalfWidth, lanePlacementsByIndex, Set.of(), Optional.empty());
    }

    static List<GroundedSweepPlacementExecutor.PlacementTarget> buildTransitionSupportTargetsForTests(
            BuildPlan plan,
            BlockPos origin,
            GroundedSchematicBounds bounds,
            GroundedSweepLane fromLane,
            GroundedSweepLane toLane,
            Map<Integer, Placement> placementLookup
    ) {
        return new GroundedLaneTransitionSupportPlanner().planTargets(plan, origin, bounds, fromLane, toLane, placementLookup);
    }

    private static void clearControls(MinecraftClient client) {
        if (client.player == null || client.options == null) {
            return;
        }
        setKey(client.options.forwardKey, false);
        setKey(client.options.backKey, false);
        setKey(client.options.leftKey, false);
        setKey(client.options.rightKey, false);
        setKey(client.options.jumpKey, false);
        setKey(client.options.sneakKey, false);
        setKey(client.options.sprintKey, false);
        client.player.setSprinting(false);
    }

    private static void setKey(KeyBinding key, boolean pressed) {
        if (key != null) {
            key.setPressed(pressed);
        }
    }

    private void traceGroundedEvent(String message) {
        if (!groundedTraceEnabled) {
            return;
        }
        String event = "[grounded-trace:event] " + message;
        groundedTraceEvents.add(event);
        if (groundedTraceEvents.size() > 200) {
            groundedTraceEvents.remove(0);
        }
        MapArtMod.LOGGER.info(event);
        sendGroundedTraceChat(message);
    }

    private void sendGroundedTraceChat(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            client.player.sendMessage(Text.literal("[Mapart grounded] " + message), false);
        }
    }

    private void traceGroundedSnapshot(MinecraftClient client) {
        if (!groundedTraceEnabled) {
            return;
        }
        if (!shouldEmitGroundedSnapshotForTick(groundedTraceTickCounter)) {
            return;
        }
        if (lastGroundedSnapshotTick == groundedTraceTickCounter) {
            return;
        }
        lastGroundedSnapshotTick = groundedTraceTickCounter;
        MapArtMod.LOGGER.info("[grounded-trace:snapshot] {}", groundedSnapshot(client));
    }

    static boolean shouldEmitGroundedSnapshotForTick(long diagnosticsTickCounter) {
        return diagnosticsTickCounter > 0
                && diagnosticsTickCounter % GROUNDED_TRACE_SNAPSHOT_INTERVAL_TICKS == 0;
    }

    private String groundedSnapshot(MinecraftClient client) {
        if (client == null || client.player == null) {
            return "client/player unavailable"
                    + ", runMode=" + runMode
                    + ", phase=" + sweepPassPhase
                    + ", lane=" + describeLane(activeLane);
        }
        Vec3d playerPos = client.player.getEntityPos();
        GroundedLaneWalker.GroundedLaneWalkCommand command = laneWalker.currentCommand().orElse(GroundedLaneWalker.GroundedLaneWalkCommand.idle());
        String headYaw = readHeadYawForDiagnostics(client.player);
        String bodyYaw = readBodyYawForDiagnostics(client.player);
        String expectedYaw = activeLane == null ? "n/a" : Float.toString(activeLane.direction().yawDegrees());
        float yawDelta = activeLane == null ? 0.0f : MathHelper.wrapDegrees(client.player.getYaw() - activeLane.direction().yawDegrees());
        double centerlineDelta = centerlineDeltaForDiagnostics(playerPos);
        double forwardCoord = forwardCoordinateForDiagnostics(playerPos);
        double forwardDeltaFromStart = activeLane == null ? 0.0 : (forwardCoord - forwardStartCoordinateForDiagnostics(activeLane)) * activeLane.direction().forwardSign();
        double transitionTargetForward = pendingShiftLane == null ? 0.0 : (pendingShiftLane.direction().alongX() ? pendingShiftLane.startPoint().getX() + 0.5 : pendingShiftLane.startPoint().getZ() + 0.5);
        double transitionCurrentForward = pendingShiftLane == null ? 0.0 : (pendingShiftLane.direction().alongX() ? playerPos.x : playerPos.z);
        double transitionForwardDelta = pendingShiftLane == null ? 0.0 : transitionTargetForward - transitionCurrentForward;
        double transitionTargetCenterline = pendingShiftLane == null ? 0.0 : pendingShiftLane.centerlineCoordinate() + 0.5;
        double transitionCurrentLateral = pendingShiftLane == null ? 0.0 : (pendingShiftLane.direction().alongX() ? playerPos.z : playerPos.x);
        double transitionCenterlineDelta = pendingShiftLane == null ? 0.0 : centerlineDelta(playerPos, pendingShiftLane);
        GroundedLaneCorridorBounds corridor = activeLane == null ? null : activeLane.corridorBounds();
        return "runMode=" + runMode
                + ", phase=" + sweepPassPhase
                + ", activeState=" + laneWalker.state()
                + ", laneIndex=" + (activeLane == null ? "none" : activeLane.laneIndex())
                + ", laneDirection=" + (activeLane == null ? "none" : activeLane.direction())
                + ", laneStart=" + (activeLane == null ? "none" : activeLane.startPoint().toShortString())
                + ", laneEnd=" + (activeLane == null ? "none" : activeLane.endPoint().toShortString())
                + ", resumePoint=" + describeResumePoint(selectedResumePoint)
                + ", startApproachTarget=" + (activeLane == null ? "none" : activeStartApproachTarget().toShortString())
                + ", playerPos=" + playerPos
                + ", yaw=" + client.player.getYaw()
                + ", headYaw=" + headYaw
                + ", bodyYaw=" + bodyYaw
                + ", expectedLaneYaw=" + expectedYaw
                + ", yawDelta=" + yawDelta
                + ", centerlineDelta=" + centerlineDelta
                + ", forwardCoordinate=" + forwardCoord
                + ", forwardDeltaFromLaneStart=" + forwardDeltaFromStart
                + ", corridorBounds=" + (corridor == null ? "none" : corridor)
                + ", insideCorridor=" + insideCorridorForDiagnostics(playerPos)
                + ", laneWalkerState=" + laneWalker.state()
                + ", laneWalkerCommandYaw=" + command.yaw()
                + ", keys=" + movementKeysForDiagnostics(client)
                + ", awaitingStartApproach=" + awaitingStartApproach
                + ", awaitingLaneShift=" + awaitingLaneShift
                + ", laneStartStage=" + laneStartStage
                + ", laneTransitionStage=" + laneTransitionStage
                + ", transitionTargetForward=" + transitionTargetForward
                + ", transitionCurrentForward=" + transitionCurrentForward
                + ", forwardAxisDelta=" + transitionForwardDelta
                + ", transitionTargetCenterline=" + transitionTargetCenterline
                + ", transitionCurrentLateral=" + transitionCurrentLateral
                + ", centerlineDeltaTransition=" + transitionCenterlineDelta
                + ", transitionDirection=" + (lastTransitionDirection == null ? "none" : lastTransitionDirection)
                + ", transitionYaw=" + lastTransitionYaw
                + ", yawAfterTransition=" + client.player.getYaw()
                + ", headAfterTransition=" + headYaw
                + ", bodyAfterTransition=" + bodyYaw
                + ", transitionSprint=" + lastTransitionSprint
                + ", transitionOvershootCorrected=" + lastTransitionOvershootCorrected
                + ", pendingPlacementCount=" + pendingPlacementTargets.size()
                + ", pendingVerificationCount=" + pendingVerificationsByPlacement.size();
    }

    private double centerlineDeltaForDiagnostics(Vec3d playerPos) {
        if (activeLane == null || playerPos == null) {
            return 0.0;
        }
        double lateralCoordinate = activeLane.direction().alongX() ? playerPos.z : playerPos.x;
        return lateralCoordinate - (activeLane.centerlineCoordinate() + 0.5);
    }

    private double forwardCoordinateForDiagnostics(Vec3d playerPos) {
        if (activeLane == null || playerPos == null) {
            return 0.0;
        }
        return activeLane.direction().alongX() ? playerPos.x : playerPos.z;
    }

    private double forwardStartCoordinateForDiagnostics(GroundedSweepLane lane) {
        return lane.direction().alongX() ? lane.startPoint().getX() + 0.5 : lane.startPoint().getZ() + 0.5;
    }

    private boolean insideCorridorForDiagnostics(Vec3d playerPos) {
        if (activeLane == null || playerPos == null) {
            return false;
        }
        GroundedLaneCorridorBounds corridor = activeLane.corridorBounds();
        return playerPos.x >= corridor.minX() && playerPos.x <= corridor.maxX() + 1.0
                && playerPos.z >= corridor.minZ() && playerPos.z <= corridor.maxZ() + 1.0
                && Math.abs(centerlineDeltaForDiagnostics(playerPos)) <= activeSettings.sweepHalfWidth() + 0.6;
    }

    private String describeLane(GroundedSweepLane lane) {
        if (lane == null) {
            return "none";
        }
        return "lane#" + lane.laneIndex() + " " + lane.direction()
                + " " + lane.startPoint().toShortString() + "->" + lane.endPoint().toShortString();
    }

    private String describeResumePoint(GroundedSweepResumePoint point) {
        if (point == null) {
            return "none";
        }
        return "lane=" + point.laneIndex() + ",phase=" + point.phase() + ",reason=" + point.reason()
                + ",progress=" + point.progressCoordinate() + ",standing=" + point.standingPosition().toShortString();
    }

    static String readHeadYawForDiagnostics(Object player) {
        return readFloatField(player, "headYaw").map(String::valueOf).orElse("unavailable");
    }

    static String readBodyYawForDiagnostics(Object player) {
        return readFloatField(player, "bodyYaw").map(String::valueOf).orElse("unavailable");
    }

    private static String movementKeysForDiagnostics(MinecraftClient client) {
        if (client == null || client.options == null) {
            return "unavailable";
        }
        return "f=" + keyPressed(client.options.forwardKey)
                + ",b=" + keyPressed(client.options.backKey)
                + ",l=" + keyPressed(client.options.leftKey)
                + ",r=" + keyPressed(client.options.rightKey)
                + ",sprint=" + keyPressed(client.options.sprintKey)
                + ",jump=" + keyPressed(client.options.jumpKey)
                + ",sneak=" + keyPressed(client.options.sneakKey);
    }

    private static boolean keyPressed(KeyBinding key) {
        return key != null && key.isPressed();
    }

    public boolean isActive() {
        return activeLane != null;
    }

    private Optional<String> validateStart(BuildSession session, GroundedSweepSettings settings) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(settings, "settings");
        if (session.getOrigin() == null) {
            return Optional.of("Origin must be set before starting debug grounded sweep mode.");
        }
        if (activeLane != null || laneWalker.state() == GroundedLaneWalker.GroundedLaneWalkState.ACTIVE) {
            return Optional.of("Grounded single-lane debug run is already active.");
        }
        if (session.getPlan().placements().isEmpty()) {
            return Optional.of("Loaded plan has no placements.");
        }
        return Optional.empty();
    }

    private void initializeRunState(
            BuildSession session,
            GroundedSchematicBounds bounds,
            GroundedSweepSettings settings,
            SweepRunMode mode,
            List<GroundedSweepLane> plannedForwardLanes,
            List<GroundedSweepLane> plannedReverseLanes,
            SweepPassPhase initialPhase
    ) {
        activeSession = session;
        activeBounds = bounds;
        activeSettings = settings;
        runMode = mode;
        forwardLanes = List.copyOf(plannedForwardLanes);
        reverseLanes = List.copyOf(plannedReverseLanes);
        laneCursor = 0;
        sweepPassPhase = initialPhase;

        successfulPlacements = 0;
        failedPlacements = 0;
        missedPlacements = 0;
        laneTicksElapsed = 0;
        forwardLeftoverPlacements.clear();
        reversePlacementFilter.clear();

        pendingVerificationsByPlacement = new LinkedHashMap<>();
        currentLeftovers = List.of();
        awaitingStartApproach = false;
        startApproachIssued = false;
        startApproachTicks = 0;
        startApproachBestDistanceSq = Double.POSITIVE_INFINITY;
        startApproachNoProgressTicks = 0;
        awaitingLaneShift = false;
        awaitingTransitionSupport = false;
        laneShiftTarget = null;
        pendingShiftLane = null;
        laneShiftPlan = null;
        laneTransitionStage = LaneTransitionStage.ALIGN_FORWARD_AXIS;
        laneTransitionTicks = 0;
        clearTransitionSupportState();
        laneStartStage = LaneStartStage.NONE;
        pendingLaneStart = null;
        pendingLaneStartTicks = 0;
        laneEntryStepTicks = 0;
        smartResumeUsed = false;
        selectedResumePoint = null;
        skippedCompletedForwardLanes = 0;
        laneEntryAnchor = null;
        displacementAlert.reset();
        corridorWarningActive = false;
        lastGroundedSnapshotTick = -1;
        groundedTraceTickCounter = 0;
        lastTransitionDirection = null;
        lastTransitionYaw = 0.0f;
        lastTransitionSprint = false;
        lastTransitionOvershootCorrected = false;
    }

    private void activateLane(GroundedSweepLane lane, Set<Integer> placementFilter) {
        activateLaneData(lane, placementFilter, Optional.empty());
        laneEntryAnchor = buildLaneEntryAnchorForFreshStart(lane, activeBounds, LaneEntrySource.FRESH_START);
        pendingShiftLane = null;

        awaitingStartApproach = true;
        traceGroundedEvent("awaitingStartApproach=true");
        startApproachIssued = false;
        startApproachTicks = 0;
        startApproachBestDistanceSq = Double.POSITIVE_INFINITY;
        startApproachNoProgressTicks = 0;
        awaitingLaneShift = false;
        awaitingTransitionSupport = false;
        laneShiftTarget = null;
        laneShiftPlan = null;
        laneTransitionStage = LaneTransitionStage.ALIGN_FORWARD_AXIS;
        laneTransitionTicks = 0;
        clearTransitionSupportState();

        lastStatus = new DebugStatus(
                true,
                activeLane.laneIndex(),
                GroundedLaneWalker.GroundedLaneWalkState.IDLE,
                true,
                false,
                Optional.empty(),
                laneTicksElapsed,
                successfulPlacements,
                missedPlacements,
                failedPlacements,
                0,
                currentLeftovers,
                sweepPassPhase,
                smartResumeUsed,
                Optional.ofNullable(selectedResumePoint),
                skippedCompletedForwardLanes
        );
    }

    private void activateLaneForNativeShift(GroundedSweepLane lane, Set<Integer> placementFilter) {
        GroundedSweepLane fromLane = activeLane;
        activateLaneData(lane, placementFilter, Optional.empty());
        laneEntryAnchor = buildLaneEntryAnchorForFreshStart(lane, activeBounds, LaneEntrySource.SHIFTED_LANE);
        awaitingStartApproach = false;
        startApproachIssued = false;
        startApproachTicks = 0;
        startApproachBestDistanceSq = Double.POSITIVE_INFINITY;
        startApproachNoProgressTicks = 0;
        awaitingLaneShift = true;
        awaitingTransitionSupport = false;
        laneShiftPlan = buildLaneShiftPlan(fromLane, lane, activeBounds);
        laneShiftTarget = laneShiftPlan.shiftTarget();
        pendingShiftLane = lane;
        laneTransitionStage = LaneTransitionStage.ALIGN_FORWARD_AXIS;
        laneTransitionTicks = 0;
        initializeTransitionSupportPhase(fromLane, lane);
        if (!awaitingTransitionSupport) {
            traceGroundedEvent("awaitingLaneShift=true");
            traceGroundedEvent("lane transition stage changed: " + laneTransitionStage);
        }
        lastStatus = new DebugStatus(
                true,
                lane.laneIndex(),
                GroundedLaneWalker.GroundedLaneWalkState.IDLE,
                false,
                true,
                Optional.empty(),
                laneTicksElapsed,
                successfulPlacements,
                missedPlacements,
                failedPlacements,
                0,
                currentLeftovers,
                sweepPassPhase,
                smartResumeUsed,
                Optional.ofNullable(selectedResumePoint),
                skippedCompletedForwardLanes
        );
    }

    private void activateLaneData(GroundedSweepLane lane, Set<Integer> placementFilter, Optional<Integer> minimumForwardProgressCoordinate) {
        activeLane = lane;
        traceGroundedEvent("active lane changed: " + describeLane(lane));
        placementSelector = new GroundedSweepPlacementExecutor(GroundedSweepPlacementExecutorSettings.fromGroundedSweepSettings(activeSettings));
        lanePlacementsByIndex = new HashMap<>();
        pendingPlacementTargets = buildLanePlacementTargets(
                activeSession.getPlan(),
                activeSession.getOrigin(),
                activeBounds,
                activeLane,
                activeSettings.sweepHalfWidth(),
                lanePlacementsByIndex,
                placementFilter,
                minimumForwardProgressCoordinate
        );
        pendingVerificationsByPlacement = new LinkedHashMap<>();
        currentLeftovers = List.of();
    }

    private void initializeTransitionSupportPhase(GroundedSweepLane fromLane, GroundedSweepLane toLane) {
        if (activeSession == null || activeBounds == null || fromLane == null || toLane == null) {
            awaitingTransitionSupport = false;
            clearTransitionSupportState();
            return;
        }
        transitionSupportPlacementsByIndex = new HashMap<>();
        pendingTransitionSupportTargets = transitionSupportPlanner.planTargets(
                activeSession.getPlan(),
                activeSession.getOrigin(),
                activeBounds,
                fromLane,
                toLane,
                transitionSupportPlacementsByIndex
        );
        pendingTransitionSupportVerifications = new LinkedHashMap<>();
        transitionSupportTicks = 0;
        transitionSupportAlreadyCorrectCount = 0;
        transitionSupportPlacedCount = 0;
        transitionSupportFailedCount = 0;
        traceGroundedEvent("transition support phase started: from=" + describeLane(fromLane)
                + ", to=" + describeLane(toLane)
                + ", supportTargets=" + pendingTransitionSupportTargets.size());
        if (pendingTransitionSupportTargets.isEmpty()) {
            awaitingTransitionSupport = false;
            clearTransitionSupportState();
            return;
        }
        awaitingTransitionSupport = true;
        awaitingLaneShift = false;
    }

    private boolean transitionSupportReady() {
        return pendingTransitionSupportTargets.isEmpty() && pendingTransitionSupportVerifications.isEmpty();
    }

    private void completeTransitionSupportPhase() {
        awaitingTransitionSupport = false;
        awaitingLaneShift = true;
        traceGroundedEvent("transition support placement complete: alreadyCorrect=" + transitionSupportAlreadyCorrectCount
                + ", placed=" + transitionSupportPlacedCount
                + ", pendingVerification=" + pendingTransitionSupportVerifications.size()
                + ", failedMissing=" + transitionSupportFailedCount);
        traceGroundedEvent("awaitingLaneShift=true");
        traceGroundedEvent("lane transition stage changed: " + laneTransitionStage);
        clearTransitionSupportState();
    }

    private void processTransitionSupportVerifications(MinecraftClient client, long tick, boolean forceAll) {
        processTransitionSupportVerifications(client, tick, forceAll, pending -> verifyExpectedBlock(client, pending));
    }

    private void processTransitionSupportVerifications(
            MinecraftClient client,
            long tick,
            boolean forceAll,
            Predicate<PendingPlacementVerification> verifier
    ) {
        if (pendingTransitionSupportVerifications.isEmpty()) {
            return;
        }
        pendingTransitionSupportVerifications = mutableTransitionSupportVerifications();
        Iterator<Map.Entry<Integer, PendingPlacementVerification>> iterator = pendingTransitionSupportVerifications.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingPlacementVerification pending = iterator.next().getValue();
            if (!forceAll && tick < pending.verifyDueTick()) {
                continue;
            }
            if (!verifier.test(pending)) {
                transitionSupportFailedCount++;
                failLaneTransition(client, "Unable to build safe transition support path");
                return;
            }
            transitionSupportAlreadyCorrectCount++;
            iterator.remove();
        }
        if (pendingTransitionSupportVerifications.isEmpty()) {
            pendingTransitionSupportVerifications = Map.of();
        }
    }

    private void removePendingTransitionSupportTarget(int placementIndex) {
        if (pendingTransitionSupportTargets.isEmpty()) {
            return;
        }
        List<GroundedSweepPlacementExecutor.PlacementTarget> retained = new ArrayList<>(pendingTransitionSupportTargets.size());
        for (GroundedSweepPlacementExecutor.PlacementTarget target : pendingTransitionSupportTargets) {
            if (target.placementIndex() != placementIndex) {
                retained.add(target);
            }
        }
        pendingTransitionSupportTargets = List.copyOf(retained);
    }

    private Map<Integer, PendingPlacementVerification> mutableTransitionSupportVerifications() {
        if (pendingTransitionSupportVerifications instanceof LinkedHashMap<Integer, PendingPlacementVerification> linkedHashMap) {
            return linkedHashMap;
        }
        return new LinkedHashMap<>(pendingTransitionSupportVerifications);
    }

    private void clearTransitionSupportState() {
        transitionSupportPlacementsByIndex = Map.of();
        pendingTransitionSupportTargets = List.of();
        pendingTransitionSupportVerifications = Map.of();
        transitionSupportTicks = 0;
        transitionSupportAlreadyCorrectCount = 0;
        transitionSupportPlacedCount = 0;
        transitionSupportFailedCount = 0;
    }

    private static List<GroundedSweepLane> buildReverseSweepLanes(List<GroundedSweepLane> forwardLanes) {
        List<GroundedSweepLane> reversed = new ArrayList<>(forwardLanes.size());
        for (int i = forwardLanes.size() - 1; i >= 0; i--) {
            GroundedSweepLane lane = forwardLanes.get(i);
            reversed.add(new GroundedSweepLane(
                    lane.laneIndex(),
                    lane.centerlineCoordinate(),
                    lane.direction().opposite(),
                    lane.endPoint(),
                    lane.startPoint(),
                    lane.corridorBounds(),
                    lane.endpointTolerance()
            ));
        }
        return List.copyOf(reversed);
    }

    static List<GroundedSweepLane> buildReverseSweepLanesForTests(List<GroundedSweepLane> forwardLanes) {
        return buildReverseSweepLanes(forwardLanes);
    }

    LaneStartStage laneStartStageForTests() {
        return laneStartStage;
    }

    static LaneStartStage laneStartStageForCurrentPositionForTests(Vec3d playerPosition, GroundedSweepLane lane, GroundedSchematicBounds bounds) {
        return laneStartStageForCurrentPosition(playerPosition, lane, bounds);
    }


    boolean queueLaneStartForTests(TestYawState yawState, GroundedSweepLane lane) {
        if (lane == null || yawState == null) {
            return false;
        }
        float targetYaw = lane.direction().yawDegrees();
        yawState.setYaw(targetYaw);
        yawState.headYaw = targetYaw;
        yawState.bodyYaw = targetYaw;
        pendingLaneStart = lane;
        laneStartStage = LaneStartStage.LOCK_LANE_YAW;
        traceGroundedEvent("lane yaw lock started");
        pendingLaneStartTicks = 0;
        return true;
    }

    static boolean forceLaneYawForTests(TestYawState yawState, GroundedSweepLane lane) {
        if (yawState == null || lane == null) {
            return false;
        }
        float targetYaw = lane.direction().yawDegrees();
        yawState.setYaw(targetYaw);
        yawState.headYaw = targetYaw;
        yawState.bodyYaw = targetYaw;
        return yawWithinTolerance(yawState.yaw(), targetYaw)
                && yawWithinTolerance(yawState.headYaw, targetYaw)
                && yawWithinTolerance(yawState.bodyYaw, targetYaw);
    }

    static final class TestYawState {
        private float yaw;
        float headYaw;
        float bodyYaw;

        void setYaw(float value) {
            yaw = value;
        }

        float yaw() {
            return yaw;
        }
    }


    public record DebugStatus(
            boolean active,
            Integer laneIndex,
            GroundedLaneWalker.GroundedLaneWalkState walkState,
            boolean awaitingStartApproach,
            boolean awaitingLaneShift,
            Optional<String> failureReason,
            long ticksElapsed,
            int successfulPlacements,
            int missedPlacements,
            int failedPlacements,
            int pendingVerification,
            List<GroundedSweepLeftoverTracker.GroundedLeftoverRecord> leftovers,
            SweepPassPhase phase,
            boolean smartResumeUsed,
            Optional<GroundedSweepResumePoint> resumePoint,
            int skippedCompletedForwardLanes
    ) {
        public DebugStatus {
            failureReason = failureReason == null ? Optional.empty() : failureReason;
            leftovers = leftovers == null ? List.of() : List.copyOf(leftovers);
            phase = phase == null ? SweepPassPhase.FORWARD : phase;
            resumePoint = resumePoint == null ? Optional.empty() : resumePoint;
        }
    }

    public enum SweepPassPhase {
        FORWARD,
        REVERSE,
        COMPLETE
    }

    record LaneShiftPlan(
            GroundedLaneDirection shiftDirection,
            int targetCenterlineCoordinate,
            BlockPos shiftTarget,
            GroundedSweepLane fromLane,
            GroundedSweepLane toLane
    ) {
    }

    private enum LaneTransitionStage {
        ALIGN_FORWARD_AXIS,
        SHIFT_TO_CENTERLINE,
        START_NEXT_LANE
    }

    enum LaneStartStage {
        NONE,
        AWAITING_LANE_ENTRY_SUPPORT,
        AWAITING_LANE_ENTRY_STEP,
        LOCK_LANE_YAW,
        START_WALKER
    }

    private enum LaneEntrySource {
        FRESH_START,
        PARTIAL_RESUME,
        SHIFTED_LANE
    }

    private record GroundedLaneEntryAnchor(
            int laneIndex,
            GroundedLaneDirection direction,
            BlockPos entryStandingTarget,
            BlockPos stagingTarget,
            int progressCoordinate,
            LaneEntrySource source
    ) {
    }

    private enum SweepRunMode {
        SINGLE_LANE,
        FULL_SWEEP
    }

    private record PendingPlacementVerification(
            int placementIndex,
            BlockPos worldPos,
            net.minecraft.block.Block expectedBlock,
            long verifyDueTick
    ) {
        private PendingPlacementVerification {
            Objects.requireNonNull(worldPos, "worldPos");
        }
    }

    private record LaneStartReadiness(
            boolean ready,
            double centerlineDelta,
            double forwardDelta,
            boolean insideCorridor,
            String reason,
            double flatDistanceSq
    ) {
        private static LaneStartReadiness ready(double centerlineDelta, double forwardDelta, boolean insideCorridor, double flatDistanceSq) {
            return new LaneStartReadiness(true, centerlineDelta, forwardDelta, insideCorridor, "ready", flatDistanceSq);
        }

        private static LaneStartReadiness notReady(
                String reason,
                double centerlineDelta,
                double forwardDelta,
                boolean insideCorridor,
                double flatDistanceSq
        ) {
            return new LaneStartReadiness(false, centerlineDelta, forwardDelta, insideCorridor, reason, flatDistanceSq);
        }
    }
}
