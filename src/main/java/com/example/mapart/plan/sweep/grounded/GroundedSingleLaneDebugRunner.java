package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.baritone.BaritoneFacade;
import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.state.BuildSession;
import com.example.mapart.plan.state.PlacementExecutor;
import com.example.mapart.plan.state.PlacementResult;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.math.BlockPos;
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

    private final GroundedSweepLanePlanner lanePlanner = new GroundedSweepLanePlanner();
    private final GroundedLaneWalker laneWalker = new GroundedLaneWalker();
    private final GroundedDisplacementAlert displacementAlert = new GroundedDisplacementAlert();
    private final PlacementExecutor placementExecutor = new PlacementExecutor();
    private final BaritoneFacade baritoneFacade;

    private BuildSession activeSession;
    private GroundedSchematicBounds activeBounds;
    private GroundedSweepLane activeLane;
    private GroundedSweepSettings activeSettings;
    private GroundedSweepPlacementExecutor placementSelector;
    private Map<Integer, Placement> lanePlacementsByIndex = Map.of();
    private List<GroundedSweepPlacementExecutor.PlacementTarget> pendingPlacementTargets = List.of();
    private Map<Integer, PendingPlacementVerification> pendingVerificationsByPlacement = Map.of();
    private List<GroundedSweepLeftoverTracker.GroundedLeftoverRecord> currentLeftovers = List.of();
    private int successfulPlacements;
    private int failedPlacements;
    private int missedPlacements;
    private long laneTicksElapsed;
    private boolean awaitingStartApproach;
    private boolean startApproachIssued;
    private boolean awaitingLaneShift;
    private BlockPos laneShiftTarget;
    private GroundedSweepLane pendingShiftLane;
    private LaneShiftPlan laneShiftPlan;
    private LaneTransitionStage laneTransitionStage = LaneTransitionStage.ALIGN_FORWARD_AXIS;
    private int laneTransitionTicks;
    private boolean laneStartPrepared;

    private SweepRunMode runMode = SweepRunMode.SINGLE_LANE;
    private List<GroundedSweepLane> forwardLanes = List.of();
    private List<GroundedSweepLane> reverseLanes = List.of();
    private int laneCursor;
    private SweepPassPhase sweepPassPhase = SweepPassPhase.FORWARD;
    private final Set<Integer> forwardLeftoverPlacements = new LinkedHashSet<>();
    private final Set<Integer> reversePlacementFilter = new LinkedHashSet<>();

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
            SweepPassPhase.FORWARD
    );

    public GroundedSingleLaneDebugRunner(BaritoneFacade baritoneFacade) {
        this.baritoneFacade = Objects.requireNonNull(baritoneFacade, "baritoneFacade");
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

        List<GroundedSweepLane> reverse = buildReverseSweepLanes(lanes);
        initializeRunState(session, bounds, settings, SweepRunMode.FULL_SWEEP, lanes, reverse, SweepPassPhase.FORWARD);
        laneCursor = 0;
        activateLane(forwardLanes.getFirst(), Set.of());
        return Optional.empty();
    }

    public void tick(MinecraftClient client, boolean constantSprint) {
        if (client == null || client.player == null || activeLane == null || activeBounds == null || activeSession == null) {
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
        if (laneStartPrepared) {
            if (!startPreparedLaneWalker(client, constantSprint)) {
                failLaneTransition(client, "Failed to start grounded lane: unable to lock lane yaw.");
            }
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
        awaitingLaneShift = false;
        laneShiftTarget = null;
        pendingShiftLane = null;
        laneShiftPlan = null;
        laneWalker.interrupt();
        baritoneFacade.cancel();
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
                    sweepPassPhase
            );
        }
        return lastStatus;
    }

    private void tickStartApproach(MinecraftClient client, boolean constantSprint) {
        issueStartApproachIfNeeded();

        BlockPos standingStart = approachTargetForLaneStart(activeLane, activeBounds);
        if (!isNearLaneStart(client.player.getEntityPos(), standingStart)) {
            return;
        }

        baritoneFacade.cancel();
        Optional<String> preparationFailure = prepareLaneStart(client, activeLane);
        if (preparationFailure.isPresent()) {
            failLaneTransition(client, preparationFailure.get());
            return;
        }
        awaitingStartApproach = false;
        laneStartPrepared = true;
        lastStatus = new DebugStatus(
                true,
                activeLane.laneIndex(),
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
                sweepPassPhase
        );
    }

    void issueStartApproachIfNeeded() {
        if (awaitingStartApproach && !startApproachIssued && activeLane != null && activeBounds != null) {
            baritoneFacade.goTo(approachTargetForLaneStart(activeLane, activeBounds));
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
        }
        if (laneTransitionStage == LaneTransitionStage.SHIFT_TO_CENTERLINE && isCenterlineAligned(playerPosition, pendingShiftLane)) {
            laneTransitionStage = LaneTransitionStage.START_NEXT_LANE;
        }
        if (laneTransitionStage == LaneTransitionStage.START_NEXT_LANE) {
            beginShiftedLane(null, constantSprint);
            startPreparedLaneWalker(null, constantSprint);
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
            return laneShiftPlan.shiftDirection();
        }
        return null;
    }

    void forceLaneTransitionTimeoutForTests(String reason) {
        failLaneTransition(null, reason);
    }

    Optional<BlockPos> laneShiftTargetForTests() {
        return Optional.ofNullable(laneShiftTarget);
    }

    Optional<LaneShiftPlan> laneShiftPlanForTests() {
        return Optional.ofNullable(laneShiftPlan);
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
        captureLaneLeftoversForPass();

        if (terminalState == GroundedLaneWalker.GroundedLaneWalkState.COMPLETE && runMode == SweepRunMode.FULL_SWEEP) {
            if (tryAdvanceSweepToNextLane()) {
                if (client != null) {
                    clearControls(client);
                }
                return;
            }
        }

        captureLastStatus(terminalState, failureReason);
        if (client != null) {
            clearControls(client);
        }
        clearActiveRunState();
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
                sweepPassPhase
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
        laneShiftTarget = null;
        pendingShiftLane = null;
        laneShiftPlan = null;
        laneTransitionStage = LaneTransitionStage.ALIGN_FORWARD_AXIS;
        laneTransitionTicks = 0;
        laneStartPrepared = false;
        displacementAlert.reset();

        runMode = SweepRunMode.SINGLE_LANE;
        forwardLanes = List.of();
        reverseLanes = List.of();
        laneCursor = 0;
        sweepPassPhase = SweepPassPhase.FORWARD;
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
            Set<Integer> placementFilter
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

        GroundedLaneWalker.GroundedLaneWalkCommand command = laneWalker.currentCommand().orElse(GroundedLaneWalker.GroundedLaneWalkCommand.idle());
        client.player.setYaw(command.yaw());
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
            failLaneTransition(client, "Lane transition failed to reach next lane start");
            return;
        }
        Vec3d playerPosition = client.player.getEntityPos();
        if (laneTransitionStage == LaneTransitionStage.ALIGN_FORWARD_AXIS) {
            if (isForwardAxisAligned(playerPosition, pendingShiftLane)) {
                laneTransitionStage = LaneTransitionStage.SHIFT_TO_CENTERLINE;
            } else {
                applyShiftControls(client, forwardAlignmentDirection(playerPosition, pendingShiftLane), constantSprint);
                return;
            }
        }
        if (laneTransitionStage == LaneTransitionStage.SHIFT_TO_CENTERLINE) {
            if (isCenterlineAligned(playerPosition, pendingShiftLane)) {
                laneTransitionStage = LaneTransitionStage.START_NEXT_LANE;
            } else {
                applyShiftControls(client, laneShiftPlan.shiftDirection(), constantSprint);
                return;
            }
        }
        if (laneTransitionStage == LaneTransitionStage.START_NEXT_LANE) {
            beginShiftedLane(client, constantSprint);
        }
    }

    private void beginShiftedLane(MinecraftClient client, boolean constantSprint) {
        Optional<String> preparationFailure = prepareLaneStart(client, pendingShiftLane);
        if (preparationFailure.isPresent()) {
            failLaneTransition(client, preparationFailure.get());
            return;
        }
        awaitingLaneShift = false;
        laneShiftTarget = null;
        laneShiftPlan = null;
        laneTransitionStage = LaneTransitionStage.ALIGN_FORWARD_AXIS;
        laneTransitionTicks = 0;
        activeLane = pendingShiftLane;
        pendingShiftLane = null;
        laneStartPrepared = true;
        lastStatus = new DebugStatus(
                true,
                activeLane.laneIndex(),
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
                sweepPassPhase
        );
    }

    private void failLaneTransition(MinecraftClient client, String reason) {
        awaitingLaneShift = false;
        laneShiftTarget = null;
        laneShiftPlan = null;
        pendingShiftLane = null;
        laneTransitionStage = LaneTransitionStage.ALIGN_FORWARD_AXIS;
        laneTransitionTicks = 0;
        laneStartPrepared = false;
        laneWalker.interrupt();
        captureLastStatus(GroundedLaneWalker.GroundedLaneWalkState.FAILED, Optional.of(reason));
        if (client != null) {
            clearControls(client);
        }
        clearActiveRunState();
    }

    private Optional<String> prepareLaneStart(MinecraftClient client, GroundedSweepLane lane) {
        if (lane == null) {
            return Optional.of("Failed to start grounded lane: lane is unavailable.");
        }
        if (client == null || client.player == null || client.options == null) {
            return Optional.empty();
        }
        clearControls(client);
        float targetYaw = lane.direction().yawDegrees();
        client.player.setYaw(targetYaw);
        if (!isYawLocked(client.player.getYaw(), targetYaw)) {
            clearControls(client);
            return Optional.of("Failed to start grounded lane: unable to apply planned lane yaw.");
        }
        return Optional.empty();
    }

    private static void applyShiftControls(MinecraftClient client, GroundedLaneDirection shiftDirection, boolean constantSprint) {
        if (client.options == null || client.player == null) {
            return;
        }
        client.player.setYaw(shiftDirection.yawDegrees());
        setKey(client.options.forwardKey, true);
        setKey(client.options.backKey, false);
        setKey(client.options.leftKey, false);
        setKey(client.options.rightKey, false);
        setKey(client.options.jumpKey, false);
        setKey(client.options.sneakKey, false);
        client.player.setSprinting(constantSprint);
    }

    static BlockPos approachTargetForLaneStart(GroundedSweepLane lane, GroundedSchematicBounds bounds) {
        return new BlockPos(lane.startPoint().getX(), bounds.minY() + 1, lane.startPoint().getZ());
    }

    static boolean isNearLaneStart(Vec3d playerPosition, BlockPos standingStartTarget) {
        Vec3d standingCenter = new Vec3d(standingStartTarget.getX() + 0.5, standingStartTarget.getY(), standingStartTarget.getZ() + 0.5);
        Vec3d playerFlat = new Vec3d(playerPosition.x, standingCenter.y, playerPosition.z);
        return playerFlat.squaredDistanceTo(standingCenter) <= 2.25;
    }

    static boolean isNearLaneShiftTarget(Vec3d playerPosition, BlockPos shiftTarget) {
        Vec3d standingCenter = new Vec3d(shiftTarget.getX() + 0.5, shiftTarget.getY(), shiftTarget.getZ() + 0.5);
        Vec3d playerFlat = new Vec3d(playerPosition.x, standingCenter.y, playerPosition.z);
        return playerFlat.squaredDistanceTo(standingCenter) <= LANE_SHIFT_REACH_DISTANCE_SQ;
    }

    private boolean startPreparedLaneWalker(MinecraftClient client, boolean constantSprint) {
        if (!laneStartPrepared || activeLane == null || activeBounds == null) {
            return false;
        }
        if (client != null && client.player != null) {
            float targetYaw = activeLane.direction().yawDegrees();
            client.player.setYaw(targetYaw);
            if (!isYawLocked(client.player.getYaw(), targetYaw)) {
                clearControls(client);
                return false;
            }
            clearControls(client);
        }
        laneWalker.start(activeLane, activeBounds, constantSprint);
        laneStartPrepared = false;
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
                sweepPassPhase
        );
        return true;
    }

    private static boolean isForwardAxisAligned(Vec3d playerPosition, GroundedSweepLane nextLane) {
        return Math.abs(forwardAxisDelta(playerPosition, nextLane)) <= LANE_TRANSITION_AXIS_TOLERANCE;
    }

    private static boolean isCenterlineAligned(Vec3d playerPosition, GroundedSweepLane nextLane) {
        double centerlineTarget = nextLane.centerlineCoordinate() + 0.5;
        double lateralCoordinate = nextLane.direction().alongX() ? playerPosition.z : playerPosition.x;
        return Math.abs(lateralCoordinate - centerlineTarget) <= LANE_TRANSITION_AXIS_TOLERANCE;
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
        BlockPos shiftTarget = approachTargetForLaneStart(toLane, bounds);
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
        return buildLanePlacementTargets(plan, origin, bounds, lane, sweepHalfWidth, lanePlacementsByIndex, Set.of());
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

    private static boolean isYawLocked(float currentYaw, float expectedYaw) {
        return Math.abs(normalizeYaw(currentYaw - expectedYaw)) <= 0.01f;
    }

    private static float normalizeYaw(float yaw) {
        float wrapped = yaw % 360.0f;
        if (wrapped > 180.0f) {
            wrapped -= 360.0f;
        } else if (wrapped <= -180.0f) {
            wrapped += 360.0f;
        }
        return wrapped;
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
        awaitingLaneShift = false;
        laneShiftTarget = null;
        pendingShiftLane = null;
        laneShiftPlan = null;
        laneTransitionStage = LaneTransitionStage.ALIGN_FORWARD_AXIS;
        laneTransitionTicks = 0;
        laneStartPrepared = false;
        displacementAlert.reset();
    }

    private void activateLane(GroundedSweepLane lane, Set<Integer> placementFilter) {
        activeLane = lane;
        pendingShiftLane = null;
        placementSelector = new GroundedSweepPlacementExecutor(GroundedSweepPlacementExecutorSettings.fromGroundedSweepSettings(activeSettings));
        lanePlacementsByIndex = new HashMap<>();
        pendingPlacementTargets = buildLanePlacementTargets(
                activeSession.getPlan(),
                activeSession.getOrigin(),
                activeBounds,
                activeLane,
                activeSettings.sweepHalfWidth(),
                lanePlacementsByIndex,
                placementFilter
        );
        pendingVerificationsByPlacement = new LinkedHashMap<>();
        currentLeftovers = List.of();

        awaitingStartApproach = true;
        startApproachIssued = false;
        awaitingLaneShift = false;
        laneShiftTarget = null;
        laneShiftPlan = null;
        laneTransitionStage = LaneTransitionStage.ALIGN_FORWARD_AXIS;
        laneTransitionTicks = 0;
        laneStartPrepared = false;

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
                sweepPassPhase
        );
    }

    private void activateLaneForNativeShift(GroundedSweepLane lane, Set<Integer> placementFilter) {
        GroundedSweepLane fromLane = activeLane;
        activateLaneData(lane, placementFilter);
        awaitingStartApproach = false;
        startApproachIssued = false;
        awaitingLaneShift = true;
        laneShiftPlan = buildLaneShiftPlan(fromLane, lane, activeBounds);
        laneShiftTarget = laneShiftPlan.shiftTarget();
        pendingShiftLane = lane;
        laneTransitionStage = LaneTransitionStage.ALIGN_FORWARD_AXIS;
        laneTransitionTicks = 0;
        laneStartPrepared = false;
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
                sweepPassPhase
        );
    }

    private void activateLaneData(GroundedSweepLane lane, Set<Integer> placementFilter) {
        activeLane = lane;
        placementSelector = new GroundedSweepPlacementExecutor(GroundedSweepPlacementExecutorSettings.fromGroundedSweepSettings(activeSettings));
        lanePlacementsByIndex = new HashMap<>();
        pendingPlacementTargets = buildLanePlacementTargets(
                activeSession.getPlan(),
                activeSession.getOrigin(),
                activeBounds,
                activeLane,
                activeSettings.sweepHalfWidth(),
                lanePlacementsByIndex,
                placementFilter
        );
        pendingVerificationsByPlacement = new LinkedHashMap<>();
        currentLeftovers = List.of();
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
            SweepPassPhase phase
    ) {
        public DebugStatus {
            failureReason = failureReason == null ? Optional.empty() : failureReason;
            leftovers = leftovers == null ? List.of() : List.copyOf(leftovers);
            phase = phase == null ? SweepPassPhase.FORWARD : phase;
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
}
