package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.MapArtMod;
import com.example.mapart.baritone.BaritoneFacade;
import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.state.BuildSession;
import com.example.mapart.plan.state.PlacementExecutor;
import com.example.mapart.plan.state.PlacementResult;
import com.example.mapart.supply.SupplyStore;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
    private static final int ENTRY_BURST_DURATION_TICKS = 16;
    private static final int MAX_ENTRY_ATTEMPTS_PER_TARGET = 2;
    private static final int ENTRY_BURST_ATTEMPTS_PER_TICK = 2;
    private static final int MAX_LANE_ENTRY_STEP_TICKS = 40;
    private static final int MAX_ENTRY_CENTERLINE_ALIGN_TICKS = 40;
    private static final double ENTRY_PROGRESS_CROSS_TOLERANCE = 0.2;
    private static final double LANE_START_CENTERLINE_TOLERANCE = 0.25;
    private static final int MIN_TRANSITION_YAW_LOCK_TICKS = 2;
    private static final int MAX_TRANSITION_YAW_LOCK_TICKS = 8;
    private static final int PREFLIGHT_LOOKAHEAD_TARGETS = 64;
    private static final int MAX_REFILL_LOOKAHEAD_ITEMS = PlayerInventory.MAIN_SIZE * 64;

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
    private LaneTransitionStage laneTransitionStage = LaneTransitionStage.TURN_TO_SHIFT_DIRECTION;
    private int laneTransitionTicks;
    private int laneTransitionYawLockTicks;
    private LaneStartStage laneStartStage = LaneStartStage.NONE;
    private GroundedSweepLane pendingLaneStart;
    private int pendingLaneStartTicks;
    private int laneEntryStepTicks;
    private int laneEntryCenterlineAlignTicks;
    private int entryBurstTicks;
    private boolean entrySupportEstablished;
    private Map<Integer, Integer> entryAttemptsByPlacementIndex = Map.of();
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
    private final List<String> diagnosticsEventBuffer = new ArrayList<>();
    private final List<Integer> recentPlacementIndices = new ArrayList<>();
    private final List<Map<String, Object>> recentVerificationResults = new ArrayList<>();

    private boolean groundedTraceEnabled;
    private final GroundedDiagnostics groundedDiagnostics = new GroundedDiagnostics();
    private long lastGroundedSnapshotTick = -1;
    private long groundedTraceTickCounter;
    private boolean corridorWarningActive;
    private GroundedLaneDirection lastTransitionDirection;
    private float lastTransitionYaw;
    private boolean lastTransitionSprint;
    private boolean lastTransitionOvershootCorrected;
    private BlockPos lastPlacedBlockPos;
    private Integer refillLaneProgressHint;
    private Integer refillLaneCursorHint;

    private final SupplyStore supplyStore;
    private final GroundedRefillController refillController = new GroundedRefillController();
    private final Map<Identifier, GroundedRefillController.SupplyExhaustedReason> exhaustedMaterials = new LinkedHashMap<>();

    private final GroundedRecoveryState recoveryState = new GroundedRecoveryState();
    private double lastKnownProgressCoordinate;
    private int ticksSinceProgressAdvance;
    private long lastSuccessfulPlacementTick;
    private int consecutiveYawDriftTicks;

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
        this(baritoneFacade, null);
    }

    public GroundedSingleLaneDebugRunner(BaritoneFacade baritoneFacade, SupplyStore supplyStore) {
        this.baritoneFacade = Objects.requireNonNull(baritoneFacade, "baritoneFacade");
        this.supplyStore = supplyStore;
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
        traceGroundedEvent("smart resume selected: " + describeResumePoint(resumePoint));

        // If a refill hint is available, use it as a hard constraint — it captures the exact
        // lane and progress where building was active when refill fired, which is more reliable
        // than smart resume's block-scan result (smart resume may pick an earlier lane if few
        // blocks have been placed in the hinted lane yet).
        if (refillLaneCursorHint != null && refillLaneCursorHint >= 0 && refillLaneCursorHint < forwardLanes.size()) {
            traceGroundedEvent("refill hint consumed: overriding smart resume lane "
                    + selectedLaneIndex + " with hint lane " + refillLaneCursorHint);
            selectedLaneIndex = refillLaneCursorHint;
        }
        laneCursor = selectedLaneIndex;
        smartResumeUsed = useSmartResume;
        selectedResumePoint = resumePoint;
        skippedCompletedForwardLanes = selectedLaneIndex;

        // Use the resume point's anchor only if smart resume picked the same lane as the hint;
        // otherwise the resume point is for a different lane and a fresh start is correct.
        boolean resumePointMatchesSelectedLane = resumePoint.laneIndex() == selectedLaneIndex;
        laneEntryAnchor = (resumePointMatchesSelectedLane && resumePoint.reason() == GroundedSweepResumePoint.ResumeReason.PARTIAL_LANE)
                ? buildLaneEntryAnchorFromResumePoint(forwardLanes.get(selectedLaneIndex), bounds, resumePoint)
                : buildLaneEntryAnchorForFreshStart(forwardLanes.get(selectedLaneIndex), bounds, LaneEntrySource.FRESH_START);

        Optional<Integer> minimumProgress = (resumePointMatchesSelectedLane && resumePoint.reason() == GroundedSweepResumePoint.ResumeReason.PARTIAL_LANE)
                ? Optional.of(resumePoint.progressCoordinate())
                : Optional.empty();
        if (minimumProgress.isEmpty() && refillLaneProgressHint != null) {
            minimumProgress = Optional.of(refillLaneProgressHint);
            traceGroundedEvent("refill hint consumed: applying progress=" + refillLaneProgressHint
                    + " for lane " + selectedLaneIndex);
        }
        refillLaneProgressHint = null;
        refillLaneCursorHint = null;
        activateLaneData(forwardLanes.get(selectedLaneIndex), Set.of(), minimumProgress);
        Optional<String> preflightFailure = runPreflightMaterialCheckBeforeSweep();
        if (preflightFailure.isPresent()) {
            return preflightFailure;
        }
        if (refillController.isActive()) {
            awaitingStartApproach = false;
            startApproachIssued = false;
            return Optional.empty();
        }
        awaitingStartApproach = true;
        startApproachIssued = false;
        awaitingLaneShift = false;
        laneShiftTarget = null;
        laneShiftPlan = null;
        laneTransitionStage = LaneTransitionStage.TURN_TO_SHIFT_DIRECTION;
        laneTransitionTicks = 0;
        laneTransitionYawLockTicks = 0;
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
        writeDiagnosticsSnapshot(client);

        if (recoveryState.isActive()) {
            tickRecovery(client);
            return;
        }

        if (refillController.isActive()) {
            tickRefill(client);
            return;
        }

        if (awaitingStartApproach) {
            tickStartApproach(client, constantSprint);
            return;
        }
        if (awaitingLaneShift) {
            tickLaneShift(client, constantSprint);
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

        Vec3d playerPosition = client.player.getEntityPos();

        // Recovery detection: BELOW_BUILD_PLANE
        if (playerPosition.y < activeBounds.minY()) {
            triggerRecovery(client, GroundedRecoveryReason.BELOW_BUILD_PLANE);
            return;
        }

        // Recovery detection: OFF_CORRIDOR
        if (!insideCorridorForDiagnostics(playerPosition)) {
            triggerRecovery(client, GroundedRecoveryReason.OFF_CORRIDOR);
            return;
        }

        laneWalker.tick(playerPosition);
        laneTicksElapsed++;

        // Track progress coordinate for NO_PROGRESS detection
        double currentProgress = activeLane.direction().progressCoordinate(playerPosition.x, playerPosition.z);
        if (Math.abs(currentProgress - lastKnownProgressCoordinate) > 0.5) {
            lastKnownProgressCoordinate = currentProgress;
            ticksSinceProgressAdvance = 0;
        } else {
            ticksSinceProgressAdvance++;
        }

        // Recovery detection: NO_PROGRESS
        if (ticksSinceProgressAdvance >= 100) {
            triggerRecovery(client, GroundedRecoveryReason.NO_PROGRESS);
            return;
        }

        // Recovery detection: YAW_DRIFT
        float expectedYaw = activeLane.direction().yawDegrees();
        float currentYaw = client.player.getYaw();
        float yawDelta = Math.abs(MathHelper.wrapDegrees(currentYaw - expectedYaw));
        if (yawDelta > 15.0f) {
            consecutiveYawDriftTicks++;
            if (consecutiveYawDriftTicks >= 20) {
                triggerRecovery(client, GroundedRecoveryReason.YAW_DRIFT);
                return;
            }
        } else {
            consecutiveYawDriftTicks = 0;
        }

        processDuePlacementVerifications(client, laneTicksElapsed, false);
        boolean walkerActiveAfterTick = shouldAttemptPlacementAfterWalkerTick(laneWalker.state());
        if (walkerActiveAfterTick) {
            tickPlacementExecutor(client);
        }

        // Recovery detection: PLACEMENT_STALL
        if (laneTicksElapsed - lastSuccessfulPlacementTick >= 200 && !pendingPlacementTargets.isEmpty()) {
            triggerRecovery(client, GroundedRecoveryReason.PLACEMENT_STALL);
            return;
        }

        applyLaneControls(client);
        displacementAlert.tick(client, true, client.player.getY() < activeBounds.minY());

        if (!walkerActiveAfterTick) {
            handleTerminalState(client);
        }
    }

    public void cancelRefillAndStop() {
        if (refillController.isActive()) {
            refillController.cancel(baritoneFacade);
            refillController.clear();
        }
        stop();
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
        exhaustedMaterials.clear();
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

    public GroundedRecoveryState getRecoveryState() {
        return recoveryState;
    }

    public GroundedRefillController getRefillController() {
        return refillController;
    }

    private void triggerRecovery(MinecraftClient client, GroundedRecoveryReason reason) {
        if (client == null || client.player == null || activeLane == null) {
            return;
        }

        clearControls(client);
        laneWalker.interrupt();
        baritoneFacade.cancel();

        Vec3d playerPosition = client.player.getEntityPos();
        double progressCoordinate = activeLane.direction().progressCoordinate(playerPosition.x, playerPosition.z);

        GroundedRecoverySnapshot snapshot = new GroundedRecoverySnapshot(
                activeLane,
                sweepPassPhase,
                activeLane.direction(),
                lastKnownProgressCoordinate,
                playerPosition,
                reason
        );

        recoveryState.activate(snapshot);

        String reasonText = switch (reason) {
            case OFF_CORRIDOR -> "player exited lane corridor bounds";
            case BELOW_BUILD_PLANE -> "player fell below build plane";
            case NO_PROGRESS -> "no forward progress in 100 ticks";
            case BLOCKED -> "movement blocked";
            case MANUAL_INPUT -> "manual input detected";
            case YAW_DRIFT -> "yaw drift exceeded 15° for 20 ticks";
            case CENTERLINE_DRIFT -> "centerline drift exceeded threshold";
            case PLACEMENT_STALL -> "no successful placements in 200 ticks";
        };

        traceGroundedEvent("recovery triggered: " + reasonText);
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[Mapart grounded] Recovery triggered: " + reasonText + ". Stabilizing..."), false);
        }
    }

    private void tickRecovery(MinecraftClient client) {
        if (!recoveryState.isActive()) {
            return;
        }

        // Phase 1: Stabilization period
        if (recoveryState.isStabilizing()) {
            recoveryState.tickStabilization();
            if (!recoveryState.isStabilizing()) {
                // Stabilization complete
                if (recoveryState.isAutoResumeEnabled()) {
                    traceGroundedEvent("recovery stabilized, checking if auto-resume is possible");
                    if (client.player != null) {
                        client.player.sendMessage(Text.literal("[Mapart grounded] Recovery stabilized. Checking if safe to auto-resume..."), false);
                    }
                } else {
                    traceGroundedEvent("recovery stabilized, auto-resume disabled");
                    if (client.player != null) {
                        client.player.sendMessage(Text.literal("[Mapart grounded] Recovery stabilized. Auto-resume disabled — run /mapart debug grounded-sweep start to continue."), false);
                    }
                }
            }
            return;
        }

        // Phase 2: Auto-resume if enabled
        if (recoveryState.isReadyForAutoResume()) {
            if (isPlayerInSaneState(client)) {
                // Player is in good state, auto-resume
                BuildSession session = activeSession;
                GroundedSweepSettings settings = activeSettings;
                recoveryState.clear();

                if (session == null || settings == null) {
                    if (client.player != null) {
                        client.player.sendMessage(
                                Text.literal("[Mapart grounded] Recovery auto-resume failed: session state was lost."),
                                false);
                    }
                    return;
                }

                traceGroundedEvent("recovery auto-resume: resuming sweep with smart resume");
                if (client.player != null) {
                    client.player.sendMessage(
                            Text.literal("[Mapart grounded] Recovery auto-resume: restarting sweep with smart resume."),
                            false);
                }

                Optional<String> err = startFullSweep(session, settings);
                if (err.isPresent() && client.player != null) {
                    client.player.sendMessage(
                            Text.literal("[Mapart grounded] Recovery auto-resume failed: " + err.get()),
                            false);
                }
            } else {
                // Player not in sane state, retry
                recoveryState.tickRetry();
                if (!recoveryState.hasRetriesRemaining()) {
                    // Out of retries
                    traceGroundedEvent("recovery auto-resume failed: player not in sane state after 10 seconds");
                    if (client.player != null) {
                        client.player.sendMessage(
                                Text.literal("[Mapart grounded] Recovery failed to stabilize after 10 seconds. Manual intervention required."),
                                false);
                    }
                    // Keep recovery active so sweep doesn't continue in bad state
                }
            }
        }
    }

    private boolean isPlayerInSaneState(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return false;
        }

        Vec3d playerPos = client.player.getEntityPos();

        // Check if player is still falling
        if (client.player.getVelocity().y < -0.5) {
            return false;
        }

        // Check if player is in lava
        if (client.player.isInLava()) {
            return false;
        }

        // Check if player is on solid ground or at least has stopped moving vertically
        boolean onGround = client.player.isOnGround();
        boolean stableVertically = Math.abs(client.player.getVelocity().y) < 0.1;

        return onGround || stableVertically;
    }

    private boolean triggerRefill(MinecraftClient client, Item requiredItem) {
        clearControls(client);
        laneWalker.interrupt();
        baritoneFacade.cancel();

        // Capture lane progress hint before any state changes, so smart resume can
        // return to the exact position after refill rather than defaulting to lane start.
        if (client != null && client.player != null && activeLane != null) {
            BlockPos playerPos = client.player.getBlockPos();
            refillLaneProgressHint = activeLane.direction().alongX() ? playerPos.getX() : playerPos.getZ();
            refillLaneCursorHint = laneCursor;
            traceGroundedEvent("refill progress hint captured: laneCursor=" + refillLaneCursorHint + " progress=" + refillLaneProgressHint);
        }

        // Use the same multi-lane lookahead as the preflight path so deficit counts
        // cover the full remaining build, not just the current lane's pending targets.
        List<GroundedSweepPlacementExecutor.PlacementTarget> lookaheadTargets = buildRefillLookaheadPlacements(MAX_REFILL_LOOKAHEAD_ITEMS);
        Map<Item, Integer> neededCounts = new HashMap<>();
        for (GroundedSweepPlacementExecutor.PlacementTarget target : lookaheadTargets) {
            Placement placement = lanePlacementsByIndex.get(target.placementIndex());
            if (placement == null || placement.block() == null) {
                if (activeSession != null && target.placementIndex() >= 0
                        && target.placementIndex() < activeSession.getPlan().placements().size()) {
                    placement = activeSession.getPlan().placements().get(target.placementIndex());
                }
            }
            if (placement == null || placement.block() == null) {
                continue;
            }
            neededCounts.merge(placement.block().asItem(), 1, Integer::sum);
        }

        traceGroundedEvent("refill scan: lookaheadTargets=" + lookaheadTargets.size());
        for (Map.Entry<Item, Integer> entry : neededCounts.entrySet()) {
            String itemName = Registries.ITEM.getId(entry.getKey()).getPath();
            traceGroundedEvent("refill scan: need " + entry.getValue() + "x " + itemName);
        }

        Map<Item, Integer> heldCounts = (client != null && client.player != null)
                ? countItemsInInventory(client.player)
                : Map.of();

        Map<Item, Integer> neededItems = neededCounts.entrySet().stream()
                .map(entry -> {
                    int deficit = entry.getValue() - heldCounts.getOrDefault(entry.getKey(), 0);
                    return deficit > 0 ? Map.entry(entry.getKey(), deficit) : null;
                })
                .filter(entry -> entry != null)
                .sorted(Map.Entry.<Item, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(entry -> Registries.ITEM.getId(entry.getKey()).toString()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        Map<Identifier, Integer> deficitMap = new LinkedHashMap<>();
        neededItems.forEach((item, count) -> deficitMap.put(Registries.ITEM.getId(item), count));

        BlockPos returnTarget = selectRefillReturnTarget(client);
        traceGroundedEvent("grounded refill request created: source=placement-failure");
        traceGroundedEvent("deficit map=" + summarizeItemCounts(neededItems));
        traceGroundedEvent("selected return target=" + (returnTarget == null ? "<none>" : returnTarget.toShortString()));
        boolean initiated = refillController.initiate(client, supplyStore, deficitMap, returnTarget, baritoneFacade);
        if (!initiated) {
            if (client.player != null) {
                client.player.sendMessage(
                        Text.literal("[Mapart grounded] Cannot refill: "
                                + refillController.failureMessage().orElse("no supply available.")),
                        false);
            }
            refillController.clear();
            return false;
        }
        traceGroundedEvent("refill triggered, heading to supply #"
                + refillController.targetSupply().map(s -> String.valueOf(s.id())).orElse("?")
                + " at " + refillController.targetSupply().map(s -> s.pos().toShortString()).orElse("?"));
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("[Mapart grounded] Pausing sweep: heading to supply #"
                            + refillController.targetSupply().get().id() + " at "
                            + refillController.targetSupply().get().pos().toShortString() + "."),
                    false);
        }
        return true;
    }

    private Optional<String> runPreflightMaterialCheckBeforeSweep() {
        MinecraftClient client = MinecraftClient.getInstance();
        PreflightMaterialCheckResult preflight = evaluatePreflightMaterialCheck(client);
        traceGroundedEvent("preflight started: checkedTargets=" + preflight.checkedTargetCount()
                + ", uniqueBlocks=" + preflight.checkedUniqueBlockCount()
                + ", lookaheadLimit=" + PREFLIGHT_LOOKAHEAD_TARGETS
                + ", creativeSkipped=" + preflight.creativeSkipped());
        if (preflight.creativeSkipped()) {
            traceGroundedEvent("preflight creative skipped");
            return Optional.empty();
        }
        if (!preflight.unsupportedBlocks().isEmpty()) {
            traceGroundedEvent("preflight unsupported block ids=" + preflight.unsupportedBlocks());
            return Optional.of("Required placements include blocks without obtainable item forms: "
                    + String.join(", ", preflight.unsupportedBlocks()));
        }
        if (preflight.missingBlockIds().isEmpty()) {
            traceGroundedEvent("preflight passed");
            return Optional.empty();
        }

        String missingText = String.join(", ", preflight.missingBlockIds());
        traceGroundedEvent("preflight missing material ids=" + preflight.missingBlockIds());
        if (client != null) {
            clearControls(client);
        }
        if (triggerRefillForMissingMaterialIds(client, preflight.requiredItemCounts())) {
            awaitingStartApproach = false;
            startApproachIssued = false;
            traceGroundedEvent("preflight refill started due to missing materials");
            return Optional.empty();
        }
        if (supplyStore == null || supplyStore.list().isEmpty()) {
            traceGroundedEvent("preflight refill unavailable due to no supply");
            return Optional.of("Missing required materials and no supply point is registered: " + missingText);
        }
        return Optional.of("Missing required materials and refill could not start: " + missingText);
    }

    private PreflightMaterialCheckResult evaluatePreflightMaterialCheck(MinecraftClient client) {
        if (client == null || client.player == null) {
            return new PreflightMaterialCheckResult(true, 0, 0, List.of(), List.of(), Map.of(), List.of());
        }
        if (client != null && client.player != null && client.player.getAbilities().creativeMode) {
            return new PreflightMaterialCheckResult(true, 0, 0, List.of(), List.of(), Map.of(), List.of());
        }
        Map<Item, Integer> heldCounts = countItemsInInventory(client.player);
        List<GroundedSweepPlacementExecutor.PlacementTarget> preflightTargets = pendingPlacementTargets.stream()
                .limit(PREFLIGHT_LOOKAHEAD_TARGETS)
                .toList();
        return evaluatePreflightTargets(preflightTargets, heldCounts);
    }

    PreflightMaterialCheckResult evaluatePreflightTargetsForTests(
            List<GroundedSweepPlacementExecutor.PlacementTarget> targets,
            Map<Item, Integer> heldItems
    ) {
        return evaluatePreflightTargets(targets, heldItems);
    }

    PreflightMaterialCheckResult evaluatePendingPreflightForTests(Map<Item, Integer> heldItems) {
        List<GroundedSweepPlacementExecutor.PlacementTarget> preflightTargets = pendingPlacementTargets.stream()
                .limit(PREFLIGHT_LOOKAHEAD_TARGETS)
                .toList();
        return evaluatePreflightTargets(preflightTargets, heldItems);
    }

    int preflightCheckedTargetCountForTests(Map<Item, Integer> heldItems) {
        return evaluatePendingPreflightForTests(heldItems).checkedTargetCount();
    }

    Map<Item, Integer> preflightMissingItemsForTests(Map<Item, Integer> heldItems) {
        List<Item> missingList = evaluatePendingPreflightForTests(heldItems).missingItems();
        Map<Item, Integer> result = new LinkedHashMap<>();
        for (Item item : missingList) {
            result.merge(item, 1, Integer::sum);
        }
        return result;
    }

    private PreflightMaterialCheckResult evaluatePreflightTargets(
            List<GroundedSweepPlacementExecutor.PlacementTarget> targets,
            Map<Item, Integer> heldItems
    ) {
        LinkedHashSet<Item> uniqueRequiredItems = new LinkedHashSet<>();
        LinkedHashSet<String> missingBlockIds = new LinkedHashSet<>();
        java.util.LinkedHashMap<Item, Integer> requiredCounts = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<Item, Integer> inventoryCounts = new java.util.LinkedHashMap<>();
        inventoryCounts.putAll(heldItems);
        LinkedHashSet<String> unsupportedBlocks = new LinkedHashSet<>();
        int checkedTargets = 0;
        for (GroundedSweepPlacementExecutor.PlacementTarget target : targets) {
            Placement placement = lanePlacementsByIndex.get(target.placementIndex());
            if (placement == null || placement.block() == null) {
                continue;
            }
            checkedTargets++;
            Item expectedItem = placement.block().asItem();
            if (expectedItem == null || expectedItem == Items.AIR) {
                Identifier blockId = Registries.BLOCK.getId(placement.block());
                unsupportedBlocks.add(blockId == null ? placement.block().toString() : blockId.toString());
                continue;
            }
            uniqueRequiredItems.add(expectedItem);
            requiredCounts.merge(expectedItem, 1, Integer::sum);
        }
        List<Item> missingItems = new ArrayList<>();
        /**
         * Map of item ID to TOTAL count required for checked targets.
         * This is the absolute count needed (NOT a deficit).
         * GroundedRefillController will pull items until player inventory meets these totals.
         */
        Map<Identifier, Integer> requiredItemCounts = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : requiredCounts.entrySet()) {
            int held = inventoryCounts.getOrDefault(entry.getKey(), 0);
            if (held < entry.getValue()) {
                missingItems.add(entry.getKey());
                Identifier itemId = Registries.ITEM.getId(entry.getKey());
                missingBlockIds.add(itemId != null ? itemId.toString() : entry.getKey().toString());
                if (itemId != null) {
                    // Store TOTAL required, not deficit (entry.getValue() - held)
                    requiredItemCounts.put(itemId, entry.getValue());
                }
            }
        }
        return new PreflightMaterialCheckResult(
                false,
                checkedTargets,
                uniqueRequiredItems.size(),
                List.copyOf(missingBlockIds),
                List.copyOf(missingItems),
                Map.copyOf(requiredItemCounts),
                List.copyOf(unsupportedBlocks)
        );
    }

    private boolean triggerRefillForMissingMaterialIds(MinecraftClient client, Map<Identifier, Integer> preflightDeficitMap) {
        if (preflightDeficitMap.isEmpty() || supplyStore == null || refillController.isActive() || recoveryState.isActive()) {
            return false;
        }

        // Build lookahead across current and future lanes to utilize full inventory
        List<GroundedSweepPlacementExecutor.PlacementTarget> lookaheadTargets = buildRefillLookaheadPlacements(MAX_REFILL_LOOKAHEAD_ITEMS);

        Map<Item, Integer> neededCounts = new HashMap<>();
        for (GroundedSweepPlacementExecutor.PlacementTarget target : lookaheadTargets) {
            Placement placement = lanePlacementsByIndex.get(target.placementIndex());
            if (placement == null || placement.block() == null) {
                // Placement might be from a future lane, fetch from plan directly
                if (activeSession != null && target.placementIndex() >= 0 && target.placementIndex() < activeSession.getPlan().placements().size()) {
                    placement = activeSession.getPlan().placements().get(target.placementIndex());
                }
            }
            if (placement == null || placement.block() == null) {
                continue;
            }
            Item item = placement.block().asItem();
            neededCounts.merge(item, 1, Integer::sum);
        }

        Map<Item, Integer> heldCounts = (client != null && client.player != null)
                ? countItemsInInventory(client.player)
                : Map.of();

        Map<Item, Integer> neededItems = neededCounts.entrySet().stream()
                .map(entry -> {
                    int deficit = entry.getValue() - heldCounts.getOrDefault(entry.getKey(), 0);
                    return deficit > 0 ? Map.entry(entry.getKey(), deficit) : null;
                })
                .filter(entry -> entry != null)
                .sorted(Map.Entry.<Item, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(entry -> Registries.ITEM.getId(entry.getKey()).toString()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        Map<Identifier, Integer> deficitMap = new LinkedHashMap<>();
        neededItems.forEach((item, count) -> deficitMap.put(Registries.ITEM.getId(item), count));

        BlockPos returnTarget = selectRefillReturnTarget(client);
        traceGroundedEvent("grounded refill request created: source=preflight");
        traceGroundedEvent("deficit map=" + summarizeDeficitMap(deficitMap));
        traceGroundedEvent("selected return target=" + (returnTarget == null ? "<none>" : returnTarget.toShortString()));
        boolean initiated = refillController.initiate(client, supplyStore, deficitMap, returnTarget, baritoneFacade);
        if (!initiated) {
            refillController.clear();
            return false;
        }
        return true;
    }

    private BlockPos determineGroundedRefillReturnTarget(MinecraftClient client) {
        if (client != null && client.player != null) {
            return client.player.getBlockPos();
        }
        return activeStartApproachTarget();
    }

    private static Map<Item, Integer> countItemsInInventory(ClientPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        Map<Item, Integer> counts = new HashMap<>();
        for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    private void tickRefill(MinecraftClient client) {
        GroundedRefillController.TickResult result = refillController.tick(client, baritoneFacade);
        switch (result) {
            case ACTIVE -> { /* waiting */ }
            case DONE -> handleRefillDone(client);
            case FAILED -> handleRefillFailed(client);
        }
    }

    private void handleRefillDone(MinecraftClient client) {
        noteExhaustedFromRefill(client);
        BuildSession session = activeSession;
        GroundedSweepSettings settings = activeSettings;
        refillController.clear();
        clearActiveRunState();
        traceGroundedEvent("refill hint after clearActiveRunState: laneCursor=" + refillLaneCursorHint + " progress=" + refillLaneProgressHint);

        // Defensive: ensure any open container screen is closed before resuming
        if (client != null && client.player != null && client.currentScreen instanceof HandledScreen) {
            client.player.closeHandledScreen();
            MapArtMod.LOGGER.info("[grounded-trace:refill] defensive screen close in handleRefillDone");
        }

        if (session == null || settings == null) {
            if (client != null && client.player != null) {
                client.player.sendMessage(
                        Text.literal("[Mapart grounded] Refill complete but session state was lost; cannot resume."),
                        false);
            }
            return;
        }
        if (client != null && client.player != null) {
            client.player.sendMessage(
                    Text.literal("[Mapart grounded] Refill complete. Resuming sweep with smart resume."),
                    false);
        }
        traceGroundedEvent("smart resume after refill");
        Optional<String> err = startFullSweep(session, settings);
        if (err.isPresent() && client != null && client.player != null) {
            client.player.sendMessage(
                    Text.literal("[Mapart grounded] Resume after refill failed: " + err.get()),
                    false);
        }
    }

    private BlockPos selectRefillReturnTarget(MinecraftClient client) {
        if (lastPlacedBlockPos != null) {
            traceGroundedEvent("selectRefillReturnTarget: lastPlacedBlockPos=" + lastPlacedBlockPos.toShortString());
            return lastPlacedBlockPos.toImmutable();
        }
        // When no block has been placed yet (e.g. first attempt in lane fails), use the
        // player's current position — they are at the failure point, which is a better
        // return target than the lane entry staging anchor.
        if (client != null && client.player != null) {
            BlockPos playerPos = client.player.getBlockPos();
            traceGroundedEvent("selectRefillReturnTarget: lastPlacedBlockPos=null, using player pos="
                    + playerPos.toShortString()
                    + " laneEntryAnchor=" + (laneEntryAnchor != null ? laneEntryAnchor.stagingTarget().toShortString() : "null"));
            return playerPos;
        }
        if (laneEntryAnchor != null && laneEntryAnchor.stagingTarget() != null) {
            traceGroundedEvent("selectRefillReturnTarget: falling back to laneEntryAnchor stagingTarget="
                    + laneEntryAnchor.stagingTarget().toShortString());
            return laneEntryAnchor.stagingTarget();
        }
        if (activeLane != null && activeBounds != null) {
            return activeStartApproachTarget();
        }
        return null;
    }

    private String summarizeItemCounts(Map<Item, Integer> counts) {
        if (counts == null || counts.isEmpty()) return "{}";
        return counts.entrySet().stream()
                .map(e -> Registries.ITEM.getId(e.getKey()).getPath() + "=" + e.getValue())
                .sorted()
                .collect(java.util.stream.Collectors.joining(", ", "{", "}"));
    }

    private String summarizeDeficitMap(Map<Identifier, Integer> counts) {
        if (counts == null || counts.isEmpty()) return "{}";
        return counts.entrySet().stream()
                .map(e -> e.getKey().getPath() + "=" + e.getValue())
                .sorted()
                .collect(Collectors.joining(", ", "{", "}"));
    }

    private void handleRefillFailed(MinecraftClient client) {
        noteExhaustedFromRefill(client);
        String msg = refillController.failureMessage().orElse("Unknown refill failure.");
        refillController.clear();
        if (client != null && client.player != null) {
            client.player.sendMessage(Text.literal("[Mapart grounded] Refill failed: " + msg), false);
        }
        stop();
    }


    private void noteExhaustedFromRefill(MinecraftClient client) {
        for (Map.Entry<Identifier, GroundedRefillController.SupplyExhaustedReason> e : refillController.exhaustedReasons().entrySet()) {
            exhaustedMaterials.put(e.getKey(), e.getValue());
            int held = client != null && client.player != null ? countItemInInventory(client.player, e.getKey()) : -1;
            traceGroundedEvent("supply exhausted marked: item=" + e.getKey() + " reason=" + e.getValue() + " held=" + held);
        }
    }

    private int countItemInInventory(ClientPlayerEntity player, Identifier id) {
        if (player == null || id == null) return 0;
        return countItemsInInventory(player).entrySet().stream()
                .filter(e -> id.equals(Registries.ITEM.getId(e.getKey())))
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    private void hardStopForExhaustedItem(MinecraftClient client, Identifier itemId) {
        traceGroundedEvent("hard stop exhausted item=" + itemId + " held=0 reason=" + exhaustedMaterials.get(itemId));
        if (client != null) {
            clearControls(client);
        }
        laneWalker.interrupt();
        baritoneFacade.cancel();
        if (client != null && client.player != null) {
            client.player.sendMessage(Text.literal("[Mapart grounded] Build stopped: " + itemId
                    + " exhausted in inventory and no registered supply contains more."), false);
        }
        stop();
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
        if (!queueLaneStart(client, activeLane, LaneStartStage.AWAITING_LANE_ENTRY_STEP)) {
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
        GroundedLaneEntryAnchor entryAnchor = laneEntryAnchorForActiveLane(pendingLaneStart, activeBounds);
        attemptLaneEntryBurst(client, pendingLaneStart, activeBounds, entryAnchor);
        if (client.player != null && client.player.getY() < activeBounds.minY() && !entrySupportEstablished) {
            failLaneStart(client, "Unable to establish lane entry support; air-place may be unsupported or starter support is missing");
            return;
        }
        if (entryBurstTicks >= ENTRY_BURST_DURATION_TICKS
                && !entrySupportEstablished
                && client.player != null
                && !insideCorridor(client.player.getEntityPos(), pendingLaneStart, activeBounds)) {
            failLaneStart(client, "Unable to establish lane entry support; air-place may be unsupported or starter support is missing");
            return;
        }

        if (laneStartStage == LaneStartStage.AWAITING_LANE_ENTRY_STEP) {
            laneEntryStepTicks++;
            if (laneEntryStepTicks > MAX_LANE_ENTRY_STEP_TICKS) {
                failLaneStart(client, "Timed out stepping onto lane start.");
                return;
            }
            if (!stepToLaneEntryTarget(client, pendingLaneStart, entryAnchor)) {
                return;
            }
            clearControls(client);
            laneStartStage = LaneStartStage.ALIGN_ENTRY_CENTERLINE;
            pendingLaneStartTicks = 0;
            traceGroundedEvent("lane entry step complete");
        }

        if (laneStartStage == LaneStartStage.ALIGN_ENTRY_CENTERLINE) {
            laneEntryCenterlineAlignTicks++;
            Vec3d playerPosition = client.player == null ? null : client.player.getEntityPos();
            if (playerPosition == null) {
                failLaneStart(client, "Unable to center on lane entry before walking");
                return;
            }
            if (isCenteredForLaneWalk(playerPosition, pendingLaneStart)) {
                clearControls(client);
                traceGroundedEvent("entry centered");
                laneStartStage = LaneStartStage.LOCK_LANE_YAW;
                pendingLaneStartTicks = 0;
            } else {
                if (laneEntryCenterlineAlignTicks > MAX_ENTRY_CENTERLINE_ALIGN_TICKS) {
                    failLaneStart(client, "Unable to center on lane entry before walking");
                    return;
                }
                GroundedLaneDirection correctionDirection = entryCenterlineCorrectionDirection(playerPosition, pendingLaneStart);
                traceGroundedEvent("entry centerline correction direction=" + correctionDirection
                        + " target=" + (pendingLaneStart.centerlineCoordinate() + 0.5)
                        + " lateral=" + entryLateralCoordinate(playerPosition, pendingLaneStart)
                        + " delta=" + centerlineDelta(playerPosition, pendingLaneStart));
                applyShiftControls(client, correctionDirection, false);
                return;
            }
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
        laneEntryCenterlineAlignTicks = 0;
        entryBurstTicks = 0;
        entrySupportEstablished = false;
        entryAttemptsByPlacementIndex = new HashMap<>();
        return true;
    }

    private static LaneStartStage laneStartStageForCurrentPosition(Vec3d playerPosition, GroundedSweepLane lane, GroundedSchematicBounds bounds) {
        if (playerPosition == null || lane == null || bounds == null) {
            return LaneStartStage.AWAITING_LANE_ENTRY_STEP;
        }
        return insideCorridor(playerPosition, lane, bounds)
                ? LaneStartStage.ALIGN_ENTRY_CENTERLINE
                : LaneStartStage.AWAITING_LANE_ENTRY_STEP;
    }

    private void attemptLaneEntryBurst(
            MinecraftClient client,
            GroundedSweepLane lane,
            GroundedSchematicBounds bounds,
            GroundedLaneEntryAnchor entryAnchor
    ) {
        if (activeSession == null || client == null || client.world == null || lane == null || bounds == null || entryAnchor == null) {
            return;
        }
        if (entryBurstTicks >= ENTRY_BURST_DURATION_TICKS) {
            return;
        }
        entryBurstTicks++;
        List<GroundedSweepPlacementExecutor.PlacementTarget> burstTargets = entryBurstTargets(lane, bounds, entryAnchor, pendingPlacementTargets, activeSettings.sweepHalfWidth());
        traceGroundedEvent("entry burst target count=" + burstTargets.size() + " tick=" + entryBurstTicks);
        int attempts = 0;
        for (GroundedSweepPlacementExecutor.PlacementTarget target : burstTargets) {
            if (attempts >= ENTRY_BURST_ATTEMPTS_PER_TICK) {
                break;
            }
            int usedAttempts = entryAttemptsByPlacementIndex.getOrDefault(target.placementIndex(), 0);
            if (usedAttempts >= MAX_ENTRY_ATTEMPTS_PER_TARGET) {
                continue;
            }
            BlockPos pos = target.worldPos();
            Placement placement = lanePlacementsByIndex.get(target.placementIndex());
            if (placement == null || placement.block() == null) {
                continue;
            }
            entryAttemptsByPlacementIndex.put(target.placementIndex(), usedAttempts + 1);
            PlacementResult result = placementExecutor.execute(client, activeSession, placement, pos);
            switch (result.status()) {
                case PLACED -> {
                    entrySupportEstablished = true;
                    onPlacementPlaced(target.placementIndex(), placement, pos, laneTicksElapsed);
                    traceGroundedEvent("entry burst attempt result=PLACED idx=" + target.placementIndex() + " attempts=" + (usedAttempts + 1));
                }
                case ALREADY_CORRECT -> {
                    entrySupportEstablished = true;
                    onPlacementResult(target.placementIndex(), GroundedSweepPlacementExecutor.PlacementResult.SUCCESS, laneTicksElapsed);
                    traceGroundedEvent("entry burst attempt result=ALREADY_CORRECT idx=" + target.placementIndex() + " attempts=" + (usedAttempts + 1));
                }
                case RETRY, MOVE_REQUIRED -> traceGroundedEvent("entry burst attempt result=" + result.status() + " idx=" + target.placementIndex() + " attempts=" + (usedAttempts + 1));
                case MISSING_ITEM, ERROR -> onFinalFailure(target.placementIndex());
            }
            attempts++;
        }
        processDuePlacementVerifications(client, laneTicksElapsed, true);
    }

    private boolean stepToLaneEntryTarget(MinecraftClient client, GroundedSweepLane lane, GroundedLaneEntryAnchor entryAnchor) {
        if (client == null || client.player == null || lane == null) {
            return false;
        }
        Vec3d playerPos = client.player.getEntityPos();
        if (insideCorridor(playerPos, lane, activeBounds) && hasCrossedEntryProgress(playerPos, entryAnchor, lane)) {
            return true;
        }
        forcePlayerLaneYaw(client, lane);
        applyShiftControls(client, lane.direction(), false);
        return false;
    }

    static boolean hasCrossedEntryProgress(Vec3d playerPos, GroundedLaneEntryAnchor anchor, GroundedSweepLane lane) {
        if (playerPos == null || anchor == null || lane == null) {
            return false;
        }
        double progressCoordinate = lane.direction().alongX() ? playerPos.x : playerPos.z;
        double target = anchor.progressCoordinate();
        return switch (lane.direction()) {
            case EAST, SOUTH -> progressCoordinate >= target + ENTRY_PROGRESS_CROSS_TOLERANCE;
            case WEST, NORTH -> progressCoordinate <= target - ENTRY_PROGRESS_CROSS_TOLERANCE;
        };
    }

    private void failLaneStart(MinecraftClient client, String reason) {
        // Before failing, check if this is end-of-build: last lane with no pending placements
        if (isEndOfBuildGracefulSkip()) {
            traceGroundedEvent("lane staging failed but lane is complete (end-of-build), skipping gracefully: " + reason);
            if (client != null && client.player != null) {
                client.player.sendMessage(
                        Text.literal("[Mapart grounded] Skipping completed lane " + (activeLane != null ? activeLane.laneIndex() : "?") +
                                " (staging unreachable but no placements remaining)."),
                        false);
            }
            laneStartStage = LaneStartStage.NONE;
            pendingLaneStart = null;
            pendingLaneStartTicks = 0;
            laneEntryStepTicks = 0;
            laneEntryCenterlineAlignTicks = 0;
            entryBurstTicks = 0;
            entrySupportEstablished = false;
            entryAttemptsByPlacementIndex = Map.of();
            laneWalker.interrupt();
            if (client != null) {
                clearControls(client);
            }
            // Try to advance to next lane instead of failing
            if (!tryAdvanceSweepToNextLane()) {
                captureLastStatus(GroundedLaneWalker.GroundedLaneWalkState.COMPLETE, Optional.empty());
                clearActiveRunState();
            }
            return;
        }

        traceGroundedEvent("lane failed: " + reason);
        laneStartStage = LaneStartStage.NONE;
        pendingLaneStart = null;
        pendingLaneStartTicks = 0;
        laneEntryStepTicks = 0;
        laneEntryCenterlineAlignTicks = 0;
        entryBurstTicks = 0;
        entrySupportEstablished = false;
        entryAttemptsByPlacementIndex = Map.of();
        laneWalker.interrupt();
        captureLastStatus(GroundedLaneWalker.GroundedLaneWalkState.FAILED, Optional.of(reason));
        if (client != null) {
            clearControls(client);
        }
        clearActiveRunState();
    }

    private boolean isEndOfBuildGracefulSkip() {
        if (runMode != SweepRunMode.FULL_SWEEP || activeLane == null) {
            return false;
        }
        // Check if lane has zero pending placements
        if (!pendingPlacementTargets.isEmpty()) {
            return false;
        }
        if (!pendingVerificationsByPlacement.isEmpty()) {
            return false;
        }
        // Check if this is the last lane in the current pass
        List<GroundedSweepLane> activeLaneList = sweepPassPhase == SweepPassPhase.FORWARD ? forwardLanes : reverseLanes;
        if (activeLaneList.isEmpty() || laneCursor >= activeLaneList.size() - 1) {
            return true; // Last lane
        }
        return false;
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
            BlockPos clamped = clampApproachTargetToBounds(approachTarget);
            if (!clamped.equals(approachTarget)) {
                traceGroundedEvent("approach target clamped to build bounds: original="
                        + approachTarget.toShortString() + " clamped=" + clamped.toShortString());
            } else {
                traceGroundedEvent("approach target valid, no clamping needed: " + approachTarget.toShortString());
            }
            traceGroundedEvent("Baritone approach target issued: " + clamped.toShortString());
            baritoneFacade.goTo(clamped);
            startApproachIssued = true;
        }
    }

    // Staging anchors are legitimately 1 block outside the build bounds, so allow a small
    // grace margin to avoid clamping normal approach targets. Only targets that are far
    // outside the bounds (e.g. from a wrong lane selection) get clamped.
    private static final int APPROACH_TARGET_BOUNDS_GRACE = 2;

    private BlockPos clampApproachTargetToBounds(BlockPos pos) {
        int x = Math.max(activeBounds.minX() - APPROACH_TARGET_BOUNDS_GRACE,
                         Math.min(activeBounds.maxX() + APPROACH_TARGET_BOUNDS_GRACE, pos.getX()));
        int y = Math.max(activeBounds.minY(), pos.getY());
        int z = Math.max(activeBounds.minZ() - APPROACH_TARGET_BOUNDS_GRACE,
                         Math.min(activeBounds.maxZ() + APPROACH_TARGET_BOUNDS_GRACE, pos.getZ()));
        return new BlockPos(x, y, z);
    }

    void advanceSweepToNextLaneForTests() {
        captureLaneLeftoversForPass();
        tryAdvanceSweepToNextLane();
    }

    void completeLaneShiftIfNearForTests(Vec3d playerPosition, boolean constantSprint) {
        if (!awaitingLaneShift || playerPosition == null || laneShiftPlan == null || pendingShiftLane == null) {
            return;
        }
        if (laneTransitionStage == LaneTransitionStage.TURN_TO_SHIFT_DIRECTION) {
            laneTransitionStage = LaneTransitionStage.SHIFT_TO_NEXT_CENTERLINE;
            traceGroundedEvent("lane transition stage changed: " + laneTransitionStage);
        }
        if (laneTransitionStage == LaneTransitionStage.SHIFT_TO_NEXT_CENTERLINE && isCenterlineAligned(playerPosition, pendingShiftLane)) {
            completeTransitionSupportPhase();
            laneTransitionStage = LaneTransitionStage.TURN_TO_NEXT_LANE;
            traceGroundedEvent("lane transition stage changed: " + laneTransitionStage);
        }
        if (laneTransitionStage == LaneTransitionStage.TURN_TO_NEXT_LANE) {
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
        if (laneTransitionStage == LaneTransitionStage.SHIFT_TO_NEXT_CENTERLINE && !isCenterlineAligned(playerPosition, pendingShiftLane)) {
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

    boolean hasActiveLaneTransitionStateForTests() {
        return hasActiveLaneTransitionState();
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

    void triggerRefillForTests(List<Item> neededItems, java.util.List<com.example.mapart.supply.SupplyPoint> supplyPoints, BaritoneFacade testBaritone) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            clearControls(client);
        }
        laneWalker.interrupt();
        Map<Identifier, Integer> deficits = new LinkedHashMap<>();
        for (Item item : neededItems) {
            deficits.merge(Registries.ITEM.getId(item), 1, Integer::sum);
        }
        refillController.initiateWithSuppliesForTests(supplyPoints, deficits, null, testBaritone);
    }

    void triggerRefillForTests(Map<Identifier, Integer> deficits, java.util.List<com.example.mapart.supply.SupplyPoint> supplyPoints, BaritoneFacade testBaritone) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            clearControls(client);
        }
        laneWalker.interrupt();
        refillController.initiateWithSuppliesForTests(supplyPoints, deficits, null, testBaritone);
    }

    void simulateRefillCompleteForTests() {
        handleRefillDone(null);
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
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null && client.player != null) {
                    client.player.sendMessage(
                            Text.literal("[Mapart grounded] Build complete. No leftovers — skipping reverse pass."),
                            false);
                }
                traceGroundedEvent("forward pass complete, no leftovers, skipping reverse pass");
                sweepPassPhase = SweepPassPhase.COMPLETE;
                return false;
            }

            // Scan which reverse lanes actually have leftovers
            Map<Integer, Integer> lanesWithLeftovers = scanLanesForLeftovers(reverseLanes, forwardLeftoverPlacements);
            if (lanesWithLeftovers.isEmpty()) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null && client.player != null) {
                    client.player.sendMessage(
                            Text.literal("[Mapart grounded] Build complete. No leftovers found in any lane — skipping reverse pass."),
                            false);
                }
                traceGroundedEvent("forward pass complete, no lanes with leftovers, skipping reverse pass");
                sweepPassPhase = SweepPassPhase.COMPLETE;
                return false;
            }

            reversePlacementFilter.clear();
        exhaustedMaterials.clear();
            reversePlacementFilter.addAll(forwardLeftoverPlacements);
            sweepPassPhase = SweepPassPhase.REVERSE;
            laneCursor = 0;

            StringBuilder leftoverSummary = new StringBuilder();
            lanesWithLeftovers.forEach((laneIdx, count) -> {
                if (leftoverSummary.length() > 0) leftoverSummary.append(", ");
                leftoverSummary.append("lane ").append(laneIdx).append(": ").append(count);
            });
            traceGroundedEvent("entering reverse pass: " + lanesWithLeftovers.size() + " lanes with leftovers [" + leftoverSummary + "]");

            // Find first lane with leftovers
            while (laneCursor < reverseLanes.size()) {
                GroundedSweepLane candidateLane = reverseLanes.get(laneCursor);
                if (lanesWithLeftovers.containsKey(candidateLane.laneIndex())) {
                    activateLaneForNativeShift(candidateLane, reversePlacementFilter);
                    return true;
                }
                laneCursor++;
            }
            sweepPassPhase = SweepPassPhase.COMPLETE;
            return false;
        }

        if (sweepPassPhase == SweepPassPhase.REVERSE && laneCursor < reverseLanes.size() && !reversePlacementFilter.isEmpty()) {
            // Scan to find next lane with leftovers
            Map<Integer, Integer> lanesWithLeftovers = scanLanesForLeftovers(reverseLanes, reversePlacementFilter);
            while (laneCursor < reverseLanes.size()) {
                GroundedSweepLane candidateLane = reverseLanes.get(laneCursor);
                if (lanesWithLeftovers.containsKey(candidateLane.laneIndex())) {
                    activateLaneForNativeShift(candidateLane, reversePlacementFilter);
                    return true;
                }
                traceGroundedEvent("reverse pass: skipping lane " + candidateLane.laneIndex() + " (no leftovers)");
                laneCursor++;
            }
        }

        sweepPassPhase = SweepPassPhase.COMPLETE;
        return false;
    }

    private Map<Integer, Integer> scanLanesForLeftovers(List<GroundedSweepLane> lanes, Set<Integer> placementFilter) {
        if (activeSession == null || activeBounds == null || activeSettings == null || placementFilter.isEmpty()) {
            return Map.of();
        }

        Map<Integer, Integer> lanesWithLeftovers = new HashMap<>();
        for (GroundedSweepLane lane : lanes) {
            // Try to build lane targets from the plan filtered by leftover indices
            Map<Integer, Placement> tempPlacementLookup = new HashMap<>();
            List<GroundedSweepPlacementExecutor.PlacementTarget> laneTargets = buildLanePlacementTargets(
                    activeSession.getPlan(),
                    activeSession.getOrigin(),
                    activeBounds,
                    lane,
                    activeSettings.sweepHalfWidth(),
                    tempPlacementLookup,
                    placementFilter,
                    Optional.empty()
            );
            if (!laneTargets.isEmpty()) {
                lanesWithLeftovers.put(lane.laneIndex(), laneTargets.size());
            }
        }

        // If no lanes were found with targets from the plan but we have leftover indices,
        // it might be because we're in a test scenario or the leftovers are from pending targets.
        // In this case, be conservative and assume at least one lane has leftovers.
        if (lanesWithLeftovers.isEmpty() && !placementFilter.isEmpty() && !lanes.isEmpty()) {
            // Return first lane as having potential leftovers - the actual filtering
            // will happen when the lane is activated
            lanesWithLeftovers.put(lanes.get(0).laneIndex(), placementFilter.size());
        }

        return lanesWithLeftovers;
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
        laneTransitionStage = LaneTransitionStage.TURN_TO_SHIFT_DIRECTION;
        laneTransitionTicks = 0;
        laneTransitionYawLockTicks = 0;
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
                case MISSING_ITEM -> {
                    Identifier itemId = placement.block() == null ? null : Registries.ITEM.getId(placement.block().asItem());
                    int heldCount = countItemInInventory(client.player, itemId);
                    if (itemId != null && exhaustedMaterials.containsKey(itemId)) {
                        traceGroundedEvent("refill skipped: known exhausted item=" + itemId + " held=" + heldCount
                                + " reason=" + exhaustedMaterials.get(itemId));
                        if (heldCount > 0) {
                            client.player.sendMessage(Text.literal("[Mapart grounded] Supply is out of " + itemId
                                    + "; continuing with remaining inventory and may run short."), false);
                            onPlacementResult(candidate.placementIndex(), GroundedSweepPlacementExecutor.PlacementResult.MISSED, laneTicksElapsed);
                            attempts++;
                            continue;
                        }
                        hardStopForExhaustedItem(client, itemId);
                        return;
                    }
                    if (supplyStore != null && !refillController.isActive() && !recoveryState.isActive()
                            && placement.block() != null) {
                        if (triggerRefill(client, placement.block().asItem())) {
                            return;
                        }
                    }
                    onFinalFailure(candidate.placementIndex());
                }
                case ERROR -> onFinalFailure(candidate.placementIndex());
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

        lastPlacedBlockPos = worldPos.toImmutable();
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
            lastSuccessfulPlacementTick = laneTicksElapsed;
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
        Map<GroundedSweepPlacementExecutor.LaneRelativeBand, Integer> bandCounts = new HashMap<>();

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

            // Track band distribution for diagnostics
            GroundedSweepPlacementExecutor.LaneRelativeBand band =
                GroundedSweepPlacementExecutor.laneRelativeBand(lane, worldPos);
            bandCounts.merge(band, 1, Integer::sum);
        }

        MapArtMod.LOGGER.info("[grounded-trace:event] buildLanePlacementTargets: total=" + targets.size()
                + " LEFT_TWO=" + bandCounts.getOrDefault(GroundedSweepPlacementExecutor.LaneRelativeBand.LEFT_TWO, 0)
                + " LEFT_ONE=" + bandCounts.getOrDefault(GroundedSweepPlacementExecutor.LaneRelativeBand.LEFT_ONE, 0)
                + " CENTER=" + bandCounts.getOrDefault(GroundedSweepPlacementExecutor.LaneRelativeBand.CENTERLINE, 0)
                + " RIGHT_ONE=" + bandCounts.getOrDefault(GroundedSweepPlacementExecutor.LaneRelativeBand.RIGHT_ONE, 0)
                + " RIGHT_TWO=" + bandCounts.getOrDefault(GroundedSweepPlacementExecutor.LaneRelativeBand.RIGHT_TWO, 0)
                + " sweepHalfWidth=" + sweepHalfWidth);

        return List.copyOf(targets);
    }

    /**
     * Builds a read-only lookahead view of upcoming placements across current and future lanes
     * in serpentine order, capped at maxItems. Used for refill deficit scanning to fill entire
     * inventory with materials needed across multiple lanes.
     * Does NOT activate future lanes or mutate sweep state.
     */
    private List<GroundedSweepPlacementExecutor.PlacementTarget> buildRefillLookaheadPlacements(int maxItems) {
        if (activeSession == null || activeBounds == null || activeSettings == null || runMode != SweepRunMode.FULL_SWEEP) {
            return List.copyOf(pendingPlacementTargets.subList(0, Math.min(pendingPlacementTargets.size(), maxItems)));
        }

        List<GroundedSweepPlacementExecutor.PlacementTarget> lookahead = new ArrayList<>(maxItems);
        Map<Integer, Integer> laneBreakdown = new LinkedHashMap<>();

        // Add remaining placements from current lane
        int remainingInCurrentLane = Math.min(pendingPlacementTargets.size(), maxItems);
        lookahead.addAll(pendingPlacementTargets.subList(0, remainingInCurrentLane));
        if (activeLane != null) {
            laneBreakdown.put(activeLane.laneIndex(), remainingInCurrentLane);
        }

        // Scan future lanes in serpentine order until we hit maxItems
        List<GroundedSweepLane> activeLaneList = sweepPassPhase == SweepPassPhase.FORWARD ? forwardLanes : reverseLanes;
        for (int futureLaneIndex = laneCursor + 1; futureLaneIndex < activeLaneList.size() && lookahead.size() < maxItems; futureLaneIndex++) {
            GroundedSweepLane futureLane = activeLaneList.get(futureLaneIndex);
            Map<Integer, Placement> tempPlacementLookup = new HashMap<>();
            List<GroundedSweepPlacementExecutor.PlacementTarget> futureLanePlacements = buildLanePlacementTargets(
                    activeSession.getPlan(),
                    activeSession.getOrigin(),
                    activeBounds,
                    futureLane,
                    activeSettings.sweepHalfWidth(),
                    tempPlacementLookup,
                    Set.of(),
                    Optional.empty()
            );

            int toTake = Math.min(futureLanePlacements.size(), maxItems - lookahead.size());
            lookahead.addAll(futureLanePlacements.subList(0, toTake));
            laneBreakdown.put(futureLane.laneIndex(), toTake);
        }

        // Log breakdown
        StringBuilder breakdown = new StringBuilder("lookahead window built: ");
        laneBreakdown.forEach((laneIdx, count) -> breakdown.append("lane#").append(laneIdx).append("=").append(count).append(" "));
        breakdown.append("→ total=").append(lookahead.size()).append(" capped at ").append(maxItems);
        traceGroundedEvent(breakdown.toString());

        return lookahead;
    }

    private static int lateralDeltaFromCenterline(GroundedLaneDirection direction, int centerlineCoordinate, BlockPos worldPos) {
        int lateralCoordinate = direction.alongX() ? worldPos.getZ() : worldPos.getX();
        return lateralCoordinate - centerlineCoordinate;
    }

    static boolean isCenteredForLaneWalk(Vec3d playerPos, GroundedSweepLane lane) {
        if (playerPos == null || lane == null) {
            return false;
        }
        return Math.abs(centerlineDelta(playerPos, lane)) <= LANE_START_CENTERLINE_TOLERANCE;
    }

    private static double entryLateralCoordinate(Vec3d playerPos, GroundedSweepLane lane) {
        return lane.direction().alongX() ? playerPos.z : playerPos.x;
    }

    static GroundedLaneDirection entryCenterlineCorrectionDirection(Vec3d playerPos, GroundedSweepLane lane) {
        return centerlineAlignmentDirection(playerPos, lane);
    }

    static List<GroundedSweepPlacementExecutor.PlacementTarget> entryBurstTargets(
            GroundedSweepLane lane,
            GroundedSchematicBounds bounds,
            GroundedLaneEntryAnchor entryAnchor,
            List<GroundedSweepPlacementExecutor.PlacementTarget> pendingTargets,
            int sweepHalfWidth
    ) {
        if (lane == null || bounds == null || entryAnchor == null || pendingTargets == null || pendingTargets.isEmpty()) {
            return List.of();
        }
        int baseProgress = entryAnchor.progressCoordinate();
        int forwardSign = lane.direction().forwardSign();
        Set<Integer> allowedProgress = new LinkedHashSet<>();
        allowedProgress.add(baseProgress);
        allowedProgress.add(baseProgress + forwardSign);
        allowedProgress.add(baseProgress + (2 * forwardSign));
        List<GroundedSweepPlacementExecutor.PlacementTarget> selected = new ArrayList<>();
        for (GroundedSweepPlacementExecutor.PlacementTarget target : pendingTargets) {
            BlockPos pos = target.worldPos();
            int progress = lane.direction().alongX() ? pos.getX() : pos.getZ();
            if (!allowedProgress.contains(progress)) {
                continue;
            }
            if (Math.abs(lateralDeltaFromCenterline(lane.direction(), lane.centerlineCoordinate(), pos)) > sweepHalfWidth) {
                continue;
            }
            if (pos.getX() < bounds.minX() || pos.getX() > bounds.maxX()
                    || pos.getY() < bounds.minY() || pos.getY() > bounds.maxY()
                    || pos.getZ() < bounds.minZ() || pos.getZ() > bounds.maxZ()) {
                continue;
            }
            selected.add(target);
        }
        return List.copyOf(selected);
    }

    static List<GroundedSweepPlacementExecutor.PlacementTarget> entryBurstTargetsForTests(
            GroundedSweepLane lane,
            GroundedSchematicBounds bounds,
            List<GroundedSweepPlacementExecutor.PlacementTarget> pendingTargets,
            int sweepHalfWidth,
            int progressCoordinate
    ) {
        return entryBurstTargets(
                lane,
                bounds,
                new GroundedLaneEntryAnchor(
                        lane.laneIndex(),
                        lane.direction(),
                        lane.startPoint(),
                        stagingTargetForEntry(lane.direction(), lane.startPoint()),
                        progressCoordinate,
                        LaneEntrySource.FRESH_START
                ),
                pendingTargets,
                sweepHalfWidth
        );
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
        if (client == null || client.player == null || !hasActiveLaneTransitionState()) {
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
        if (laneTransitionStage == LaneTransitionStage.TURN_TO_SHIFT_DIRECTION) {
            clearControls(client);
            forcePlayerYaw(client, laneShiftPlan.shiftDirection().yawDegrees());
            laneTransitionYawLockTicks++;
            traceGroundedEvent("turn to shift direction started: shiftDirection=" + laneShiftPlan.shiftDirection());
            if (laneTransitionYawLockTicks > MAX_TRANSITION_YAW_LOCK_TICKS) {
                failLaneTransition(client, "Lane transition failed to lock shift direction yaw");
                return;
            }
            if (!isDirectionYawLocked(client, laneShiftPlan.shiftDirection())) {
                return;
            }
            if (laneTransitionYawLockTicks < MIN_TRANSITION_YAW_LOCK_TICKS) {
                return;
            }
            laneTransitionStage = LaneTransitionStage.SHIFT_TO_NEXT_CENTERLINE;
            laneTransitionYawLockTicks = 0;
            traceGroundedEvent("turn to shift direction confirmed");
            traceGroundedEvent("lane transition stage changed: " + laneTransitionStage);
        }
        if (laneTransitionStage == LaneTransitionStage.SHIFT_TO_NEXT_CENTERLINE) {
            tickTransitionSupport(client);
            if (!hasActiveLaneTransitionState()) {
                return;
            }
            if (isCenterlineAligned(playerPosition, pendingShiftLane)) {
                clearControls(client);
                completeTransitionSupportPhase();
                laneTransitionStage = LaneTransitionStage.TURN_TO_NEXT_LANE;
                laneTransitionYawLockTicks = 0;
                traceGroundedEvent("centered on next lane");
                traceGroundedEvent("lane transition stage changed: " + laneTransitionStage);
            } else {
                GroundedLaneDirection correctionDirection = centerlineAlignmentDirection(playerPosition, pendingShiftLane);
                if (correctionDirection == null) {
                    clearControls(client);
                    return;
                }
                boolean overshootCorrected = correctionDirection != laneShiftPlan.shiftDirection();
                double delta = centerlineDelta(playerPosition, pendingShiftLane);
                double lateral = pendingShiftLane.direction().alongX() ? playerPosition.z : playerPosition.x;
                traceGroundedEvent("shift movement direction=" + correctionDirection
                        + " lateral=" + lateral
                        + " targetCenterline=" + laneShiftPlan.targetCenterline()
                        + " centerlineDelta=" + delta);
                if (overshootCorrected) {
                    traceGroundedEvent("overshoot correction direction=" + correctionDirection);
                }
                lastTransitionOvershootCorrected = overshootCorrected;
                applyShiftControls(client, correctionDirection, false);
                recordTransitionDiagnostics(client, correctionDirection, false);
                return;
            }
        }
        if (!hasActiveLaneTransitionState()) {
            return;
        }
        if (laneTransitionStage == LaneTransitionStage.TURN_TO_NEXT_LANE) {
            clearControls(client);
            forcePlayerYaw(client, laneShiftPlan.toLane().direction().yawDegrees());
            laneTransitionYawLockTicks++;
            traceGroundedEvent("turn to next lane direction started: nextYaw=" + laneShiftPlan.nextLaneYaw());
            if (laneTransitionYawLockTicks > MAX_TRANSITION_YAW_LOCK_TICKS) {
                failLaneTransition(client, "Lane transition failed to lock next lane yaw");
                return;
            }
            if (!isDirectionYawLocked(client, laneShiftPlan.toLane().direction())) {
                return;
            }
            if (laneTransitionYawLockTicks < MIN_TRANSITION_YAW_LOCK_TICKS) {
                return;
            }
            laneTransitionStage = LaneTransitionStage.START_NEXT_LANE;
            laneTransitionYawLockTicks = 0;
            traceGroundedEvent("turn to next lane direction confirmed");
            traceGroundedEvent("lane transition stage changed: " + laneTransitionStage);
        }
        if (!hasActiveLaneTransitionState()) {
            return;
        }
        if (laneTransitionStage == LaneTransitionStage.START_NEXT_LANE) {
            traceGroundedEvent("lane walker started on next lane");
            beginShiftedLane(client, constantSprint);
        }
    }

    private boolean hasActiveLaneTransitionState() {
        return activeSession != null
                && activeBounds != null
                && activeLane != null
                && awaitingLaneShift
                && pendingShiftLane != null
                && laneShiftPlan != null;
    }

    private void recordTransitionDiagnostics(MinecraftClient client, GroundedLaneDirection direction, boolean sprintEnabled) {
        if (client == null || client.player == null) {
            return;
        }
        lastTransitionDirection = direction;
        lastTransitionYaw = direction.yawDegrees();
        lastTransitionSprint = sprintEnabled;
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
            traceGroundedEvent("transition support timeout: abandoning " + pendingTransitionSupportTargets.size() + " remaining targets as leftovers");
            completeTransitionSupportPhase();
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
                failTransitionSupport(client, "ERROR", target, placement, (PlacementResult) null);
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
                    traceGroundedEvent("transition support block skipped: idx=" + target.placementIndex() + " reason=" + result.status().name());
                    removePendingTransitionSupportTarget(target.placementIndex());
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
        laneTransitionStage = LaneTransitionStage.TURN_TO_SHIFT_DIRECTION;
        laneTransitionTicks = 0;
        laneTransitionYawLockTicks = 0;
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
        laneTransitionStage = LaneTransitionStage.TURN_TO_SHIFT_DIRECTION;
        laneTransitionTicks = 0;
        laneTransitionYawLockTicks = 0;
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
        laneTransitionStage = LaneTransitionStage.TURN_TO_SHIFT_DIRECTION;
        laneTransitionTicks = 0;
        laneTransitionYawLockTicks = 0;
        clearTransitionSupportState();
        laneWalker.interrupt();
        captureLastStatus(GroundedLaneWalker.GroundedLaneWalkState.FAILED, Optional.of(reason));
        if (client != null) {
            clearControls(client);
        }
        clearActiveRunState();
    }

    private void failTransitionSupport(
            MinecraftClient client,
            String resultStatus,
            GroundedSweepPlacementExecutor.PlacementTarget target,
            Placement placement,
            PlacementResult result
    ) {
        traceTransitionSupportFailure(client, resultStatus, target, placement, result, null);
        failLaneTransition(client, "Unable to build safe transition support path");
    }

    private void failTransitionSupport(
            MinecraftClient client,
            String resultStatus,
            GroundedSweepPlacementExecutor.PlacementTarget target,
            Placement placement,
            PendingPlacementVerification pending
    ) {
        traceTransitionSupportFailure(client, resultStatus, target, placement, null, pending);
        failLaneTransition(client, "Unable to build safe transition support path");
    }

    private void traceTransitionSupportFailure(
            MinecraftClient client,
            String resultStatus,
            GroundedSweepPlacementExecutor.PlacementTarget target,
            Placement placement,
            PlacementResult result,
            PendingPlacementVerification pending
    ) {
        BlockPos failingPos = target != null ? target.worldPos() : pending == null ? null : pending.worldPos();
        Integer failingIndex = target != null ? (Integer) target.placementIndex() : pending == null ? null : pending.placementIndex();
        Placement failingPlacement = placement;
        if (failingPlacement == null && failingIndex != null) {
            failingPlacement = transitionSupportPlacementsByIndex.get(failingIndex);
        }
        if (failingPlacement == null && pending != null && pending.expectedBlock() != null) {
            failingPlacement = new Placement(pending.worldPos(), pending.expectedBlock());
        }
        String expectedBlock = blockIdForDiagnostics(failingPlacement == null ? null : failingPlacement.block());
        String worldBlock = worldBlockForDiagnostics(client, failingPos);
        boolean worldMatchesExpected = worldMatchesExpected(client, failingPos, failingPlacement);
        String playerHasItem = playerHasItemForPlacement(client, failingPlacement);
        String creativeMode = creativeModeForDiagnostics(client);
        traceGroundedEvent("transition support failed: idx=" + (failingIndex == null ? "unknown" : failingIndex)
                + ", pos=" + (failingPos == null ? "unknown" : failingPos.toShortString())
                + ", result=" + resultStatus
                + ", expected=" + expectedBlock
                + ", world=" + worldBlock
                + ", worldMatchesExpected=" + worldMatchesExpected
                + ", targetsRemaining=" + pendingTransitionSupportTargets.size()
                + ", pendingVerification=" + pendingTransitionSupportVerifications.size()
                + ", supportTargetCount=" + (pendingTransitionSupportTargets.size() + transitionSupportAlreadyCorrectCount + transitionSupportPlacedCount)
                + ", alreadyCorrectCount=" + transitionSupportAlreadyCorrectCount
                + ", placedCount=" + transitionSupportPlacedCount
                + ", failedCount=" + transitionSupportFailedCount
                + ", playerHasItem=" + playerHasItem
                + ", creativeMode=" + creativeMode
                + (result == null || result.message() == null ? "" : ", detail=" + result.message()));
    }

    private static String worldBlockForDiagnostics(MinecraftClient client, BlockPos worldPos) {
        if (client == null || client.world == null || worldPos == null) {
            return "unavailable";
        }
        BlockState state = client.world.getBlockState(worldPos);
        return blockIdForDiagnostics(state.getBlock());
    }

    private static boolean worldMatchesExpected(MinecraftClient client, BlockPos worldPos, Placement placement) {
        if (client == null || client.world == null || worldPos == null || placement == null || placement.block() == null) {
            return false;
        }
        return client.world.getBlockState(worldPos).isOf(placement.block());
    }

    private static String playerHasItemForPlacement(MinecraftClient client, Placement placement) {
        if (client == null || client.player == null || placement == null || placement.block() == null) {
            return "unknown";
        }
        Item expectedItem = placement.block().asItem();
        if (client.player.getMainHandStack().isOf(expectedItem)) {
            return "true";
        }
        for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
            if (client.player.getInventory().getStack(slot).isOf(expectedItem)) {
                return "true";
            }
        }
        return "false";
    }

    private static String creativeModeForDiagnostics(MinecraftClient client) {
        if (client == null || client.player == null) {
            return "unknown";
        }
        return Boolean.toString(client.player.getAbilities().creativeMode);
    }

    private static String blockIdForDiagnostics(net.minecraft.block.Block block) {
        if (block == null) {
            return "unknown";
        }
        return Registries.BLOCK.getId(block).toString();
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

    private static boolean isDirectionYawLocked(MinecraftClient client, GroundedLaneDirection direction) {
        if (client == null || client.player == null || direction == null) {
            return false;
        }
        float targetYaw = direction.yawDegrees();
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
            traceGroundedEvent("entry anchor selected: partial resume progress=" + laneEntryAnchor.progressCoordinate()
                    + " entry=" + laneEntryAnchor.entryStandingTarget().toShortString()
                    + " staging=" + laneEntryAnchor.stagingTarget().toShortString());
            return laneEntryAnchor;
        }
        laneEntryAnchor = buildLaneEntryAnchorForFreshStart(
                lane,
                bounds,
                runMode == SweepRunMode.FULL_SWEEP && !awaitingStartApproach ? LaneEntrySource.SHIFTED_LANE : LaneEntrySource.FRESH_START
        );
        traceGroundedEvent("entry anchor selected: " + laneEntryAnchor.source().name().toLowerCase()
                + " progress=" + laneEntryAnchor.progressCoordinate()
                + " entry=" + laneEntryAnchor.entryStandingTarget().toShortString()
                + " staging=" + laneEntryAnchor.stagingTarget().toShortString());
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
        if (playerPosition == null || nextLane == null) {
            return false;
        }
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
        if (playerPosition == null || targetLane == null) {
            return null;
        }
        double delta = centerlineDelta(playerPosition, targetLane);
        if (targetLane.direction().alongX()) {
            return delta >= 0.0 ? GroundedLaneDirection.SOUTH : GroundedLaneDirection.NORTH;
        }
        return delta >= 0.0 ? GroundedLaneDirection.EAST : GroundedLaneDirection.WEST;
    }

    private static double centerlineDelta(Vec3d playerPosition, GroundedSweepLane targetLane) {
        if (playerPosition == null || targetLane == null) {
            return Double.NaN;
        }
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
        double startForwardCoordinate = toLane.direction().alongX() ? previousLane.endPoint().getX() + 0.5 : previousLane.endPoint().getZ() + 0.5;
        double targetForwardCoordinate = toLane.direction().alongX() ? toLane.startPoint().getX() + 0.5 : toLane.startPoint().getZ() + 0.5;
        double startCenterline = previousLane.centerlineCoordinate() + 0.5;
        double targetCenterline = toLane.centerlineCoordinate() + 0.5;
        return new LaneShiftPlan(
                previousLane,
                toLane,
                shiftDirection,
                startForwardCoordinate,
                targetForwardCoordinate,
                startCenterline,
                targetCenterline,
                shiftTarget,
                toLane.direction().yawDegrees()
        );
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
        String event = "[grounded-trace:event] " + message;
        diagnosticsEventBuffer.add(event);
        if (diagnosticsEventBuffer.size() > 500) { diagnosticsEventBuffer.remove(0); }
        if (!groundedTraceEnabled) {
            return;
        }
        groundedTraceEvents.add(event);
        if (groundedTraceEvents.size() > 200) {
            groundedTraceEvents.remove(0);
        }
        MapArtMod.LOGGER.info(event);
        sendGroundedTraceChat(message);
    }


    public boolean groundedDiagnosticsEnabled() { return groundedDiagnostics.enabled(); }

    public void setGroundedDiagnosticsEnabled(boolean enabled) { groundedDiagnostics.setEnabled(enabled); }

    public java.nio.file.Path groundedDiagnosticsPath() { return groundedDiagnostics.logPath(); }

    private void writeDiagnosticsSnapshot(MinecraftClient client) {
        if (!groundedDiagnostics.enabled() || !GroundedDiagnostics.shouldEmitSnapshotForTick(groundedTraceTickCounter)) {
            return;
        }
        try {
            groundedDiagnostics.writeSnapshot(groundedTraceTickCounter, buildDiagnosticsPayload(client), drainDiagnosticsEvents());
        } catch (Exception exception) {
            traceGroundedEvent("diagnostics snapshot error: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    Map<String, Object> buildDiagnosticsPayload(MinecraftClient client) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sweepRunMode", runMode.name());
        payload.put("sweepPassPhase", sweepPassPhase.name());
        payload.put("smartResumeUsed", smartResumeUsed);
        payload.put("selectedResumePoint", selectedResumePoint == null ? null : describeResumePoint(selectedResumePoint));

        Map<String, Object> player = new LinkedHashMap<>();
        ClientPlayerEntity p = client == null ? null : client.player;
        player.put("position", p == null ? null : vecMap(p.getEntityPos()));
        player.put("velocity", p == null ? null : vecMap(p.getVelocity()));
        player.put("yaw", p == null ? null : p.getYaw());
        payload.put("player", player);

        Map<String, Object> lane = new LinkedHashMap<>();
        lane.put("index", activeLane == null ? null : activeLane.laneIndex());
        lane.put("direction", activeLane == null ? null : activeLane.direction().name());
        lane.put("start", activeLane == null ? null : posMap(activeLane.startPoint()));
        lane.put("end", activeLane == null ? null : posMap(activeLane.endPoint()));
        payload.put("lane", lane);

        Map<String, Object> walker = new LinkedHashMap<>();
        walker.put("state", laneWalker.state().name());
        walker.put("commandYaw", laneWalker.currentCommand().map(GroundedLaneWalker.GroundedLaneWalkCommand::yaw).orElse(null));
        payload.put("walker", walker);

        Map<String, Object> transition = new LinkedHashMap<>();
        transition.put("stage", laneTransitionStage.name());
        transition.put("direction", lastTransitionDirection == null ? null : lastTransitionDirection.name());
        payload.put("transition", transition);

        Map<String, Object> placements = new LinkedHashMap<>();
        placements.put("pendingCount", pendingPlacementTargets.size());
        placements.put("pendingVerificationCount", pendingVerificationsByPlacement.size());
        placements.put("transitionSupportPendingCount", pendingTransitionSupportTargets.size());
        placements.put("transitionSupportVerificationCount", pendingTransitionSupportVerifications.size());
        placements.put("successfulCount", successfulPlacements);
        placements.put("failedCount", failedPlacements);
        placements.put("missedCount", missedPlacements);
        placements.put("lastPlacedPos", posMap(lastPlacedBlockPos));
        placements.put("recentPlacementIndices", List.copyOf(recentPlacementIndices));
        placements.put("recentVerificationResults", List.copyOf(recentVerificationResults));
        payload.put("placements", placements);

        Map<String, Object> refill = new LinkedHashMap<>();
        refill.put("active", refillController.isActive());
        refill.put("state", refillController.state().name());
        refill.put("deficits", refillController.diagnosticsDeficits());
        refill.put("remaining", refillController.diagnosticsRemaining());
        refill.put("returnTarget", refillController.diagnosticsReturnTarget());
        GroundedRefillController.RefillState refillState = refillController.state();
        if (client != null
                && (refillState == GroundedRefillController.RefillState.OPENING_CONTAINER
                        || refillState == GroundedRefillController.RefillState.REFILLING)
                && client.currentScreen instanceof HandledScreen<?> supplyScreen) {
            var handler = supplyScreen.getScreenHandler();
            int containerSlots = Math.max(0, handler.slots.size() - net.minecraft.entity.player.PlayerInventory.MAIN_SIZE);
            List<Map<String, Object>> chestContents = new java.util.ArrayList<>();
            for (int si = 0; si < containerSlots; si++) {
                ItemStack stack = handler.slots.get(si).getStack();
                if (!stack.isEmpty()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("slot", si);
                    entry.put("itemId", Registries.ITEM.getId(stack.getItem()).toString());
                    entry.put("count", stack.getCount());
                    chestContents.add(entry);
                }
            }
            refill.put("supplyChestContents", chestContents);
        }
        payload.put("refill", refill);

        Map<String, Object> recovery = new LinkedHashMap<>();
        recovery.put("active", recoveryState.isActive());
        recovery.put("stabilizing", recoveryState.isStabilizing());
        recovery.put("autoResumeEnabled", recoveryState.isAutoResumeEnabled());
        recovery.put("snapshot", recoveryState.snapshot().map(Object::toString).orElse(null));
        payload.put("recovery", recovery);

        Map<String, Object> baritone = new LinkedHashMap<>();
        baritone.put("busy", baritoneFacade.isBusy());
        baritone.put("lastIssuedGoal", baritoneFacade.diagnosticsLastIssuedGoal().orElse(null));
        baritone.put("lastIssuedGoalRange", baritoneFacade.diagnosticsLastIssuedGoalRange().orElse(null));
        baritone.put("constraintsApplied", baritoneFacade.diagnosticsConstraintsApplied().map(Object::toString).orElse("unknown"));
        payload.put("baritone", baritone);
        return payload;
    }

    private static Map<String, Object> posMap(BlockPos pos) {
        if (pos == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", pos.getX());
        map.put("y", pos.getY());
        map.put("z", pos.getZ());
        return map;
    }

    private static Map<String, Object> vecMap(Vec3d vec) {
        if (vec == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", vec.x);
        map.put("y", vec.y);
        map.put("z", vec.z);
        return map;
    }

    private List<String> drainDiagnosticsEvents() {
        List<String> copy = List.copyOf(diagnosticsEventBuffer);
        diagnosticsEventBuffer.clear();
        return copy;
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
        laneTransitionStage = LaneTransitionStage.TURN_TO_SHIFT_DIRECTION;
        laneTransitionTicks = 0;
        laneTransitionYawLockTicks = 0;
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
        laneTransitionStage = LaneTransitionStage.TURN_TO_SHIFT_DIRECTION;
        laneTransitionTicks = 0;
        laneTransitionYawLockTicks = 0;
        clearTransitionSupportState();

        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            Vec3d playerPos = client.player.getEntityPos();
            lastKnownProgressCoordinate = lane.direction().progressCoordinate(playerPos.x, playerPos.z);
        } else {
            lastKnownProgressCoordinate = lane.direction().progressCoordinate(lane.startPoint().getX(), lane.startPoint().getZ());
        }
        ticksSinceProgressAdvance = 0;
        lastSuccessfulPlacementTick = 0;
        consecutiveYawDriftTicks = 0;

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
        laneTransitionStage = LaneTransitionStage.TURN_TO_SHIFT_DIRECTION;
        laneTransitionTicks = 0;
        laneTransitionYawLockTicks = 0;
        traceGroundedEvent("transition plan created: from=" + describeLane(laneShiftPlan.fromLane())
                + ", to=" + describeLane(laneShiftPlan.toLane())
                + ", shiftDirection=" + laneShiftPlan.shiftDirection()
                + ", targetCenterline=" + laneShiftPlan.targetCenterline()
                + ", nextYaw=" + laneShiftPlan.nextLaneYaw());
        initializeTransitionSupportPhase(fromLane, lane);
        traceGroundedEvent("awaitingLaneShift=true");
        traceGroundedEvent("lane transition stage changed: " + laneTransitionStage);
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
        traceGroundedEvent("activateLaneData: pendingPlacementTargets initialized with " + pendingPlacementTargets.size() + " targets");
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
    }

    private boolean transitionSupportReady() {
        return pendingTransitionSupportTargets.isEmpty() && pendingTransitionSupportVerifications.isEmpty();
    }

    private void completeTransitionSupportPhase() {
        awaitingTransitionSupport = false;
        traceGroundedEvent("transition support placement complete: alreadyCorrect=" + transitionSupportAlreadyCorrectCount
                + ", placed=" + transitionSupportPlacedCount
                + ", pendingVerification=" + pendingTransitionSupportVerifications.size()
                + ", failedMissing=" + transitionSupportFailedCount);
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
                failTransitionSupport(client, "verification mismatch", null, null, pending);
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

    static GroundedLaneDirection centerlineAlignmentDirectionForTests(Vec3d playerPosition, GroundedSweepLane lane) {
        return centerlineAlignmentDirection(playerPosition, lane);
    }

    static boolean isCenterlineAlignedForTests(Vec3d playerPosition, GroundedSweepLane lane) {
        return isCenterlineAligned(playerPosition, lane);
    }

    static boolean hasCrossedEntryProgressForTests(Vec3d playerPosition, GroundedSweepLane lane, GroundedSchematicBounds bounds) {
        GroundedLaneEntryAnchor anchor = buildLaneEntryAnchorForFreshStart(lane, bounds, LaneEntrySource.FRESH_START);
        return hasCrossedEntryProgress(playerPosition, anchor, lane);
    }

    static int maxEntryAttemptsPerTargetForTests() {
        return MAX_ENTRY_ATTEMPTS_PER_TARGET;
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
            GroundedSweepLane fromLane,
            GroundedSweepLane toLane,
            GroundedLaneDirection shiftDirection,
            double startForwardCoordinate,
            double targetForwardCoordinate,
            double startCenterline,
            double targetCenterline,
            BlockPos targetStandingPosition,
            float nextLaneYaw
    ) {
        BlockPos shiftTarget() {
            return targetStandingPosition;
        }

        int targetCenterlineCoordinate() {
            return toLane.centerlineCoordinate();
        }
    }

    private enum LaneTransitionStage {
        TURN_TO_SHIFT_DIRECTION,
        SHIFT_TO_NEXT_CENTERLINE,
        TURN_TO_NEXT_LANE,
        START_NEXT_LANE
    }

    enum LaneStartStage {
        NONE,
        AWAITING_LANE_ENTRY_STEP,
        ALIGN_ENTRY_CENTERLINE,
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

    /**
     * Preflight material check result.
     * @param requiredItemCounts Map of item ID to TOTAL count required for checked targets (NOT deficit).
     */
    private record PreflightMaterialCheckResult(
            boolean creativeSkipped,
            int checkedTargetCount,
            int checkedUniqueBlockCount,
            List<String> missingBlockIds,
            List<Item> missingItems,
            Map<Identifier, Integer> requiredItemCounts,
            List<String> unsupportedBlocks
    ) {
    }
}
