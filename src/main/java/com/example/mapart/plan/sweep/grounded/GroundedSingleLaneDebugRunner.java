package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.baritone.BaritoneFacade;
import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.state.BuildSession;
import com.example.mapart.plan.state.PlacementExecutor;
import com.example.mapart.plan.state.PlacementResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntPredicate;

public final class GroundedSingleLaneDebugRunner {
    private static final int MAX_PLACEMENT_ATTEMPTS_PER_TICK = 2;
    private static final int PLACEMENT_VERIFICATION_DELAY_TICKS = 3;

    private final GroundedSweepLanePlanner lanePlanner = new GroundedSweepLanePlanner();
    private final GroundedLaneWalker laneWalker = new GroundedLaneWalker();
    private final GroundedDisplacementAlert displacementAlert = new GroundedDisplacementAlert();
    private final PlacementExecutor placementExecutor = new PlacementExecutor();
    private final BaritoneFacade baritoneFacade;

    private BuildSession activeSession;
    private GroundedSchematicBounds activeBounds;
    private GroundedSweepLane activeLane;
    private GroundedSweepPlacementExecutor placementSelector;
    private Map<Integer, Placement> lanePlacementsByIndex = Map.of();
    private List<GroundedSweepPlacementExecutor.PlacementTarget> pendingPlacementTargets = List.of();
    private final Map<Integer, PendingPlacementVerification> pendingPlacementVerifications = new HashMap<>();
    private List<GroundedSweepLeftoverTracker.GroundedLeftoverRecord> currentLeftovers = List.of();
    private int successfulPlacements;
    private int failedPlacements;
    private int missedPlacements;
    private long laneTicksElapsed;
    private boolean awaitingStartApproach;
    private boolean startApproachIssued;
    private DebugStatus lastStatus = new DebugStatus(
            false,
            null,
            GroundedLaneWalker.GroundedLaneWalkState.IDLE,
            false,
            Optional.empty(),
            0,
            0,
            0,
            0,
            0,
            List.of()
    );

    public GroundedSingleLaneDebugRunner(BaritoneFacade baritoneFacade) {
        this.baritoneFacade = Objects.requireNonNull(baritoneFacade, "baritoneFacade");
    }

    public Optional<String> start(BuildSession session, int laneIndex, GroundedSweepSettings settings) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(settings, "settings");
        if (session.getOrigin() == null) {
            return Optional.of("Origin must be set before starting debug grounded sweep mode.");
        }
        if (activeLane != null || laneWalker.state() == GroundedLaneWalker.GroundedLaneWalkState.ACTIVE) {
            return Optional.of("Grounded single-lane debug run is already active.");
        }

        BuildPlan plan = session.getPlan();
        if (plan.placements().isEmpty()) {
            return Optional.of("Loaded plan has no placements.");
        }

        GroundedSchematicBounds bounds = GroundedSchematicBounds.fromPlan(plan, session.getOrigin());
        List<GroundedSweepLane> lanes = lanePlanner.planLanes(bounds, settings);
        if (laneIndex < 0 || laneIndex >= lanes.size()) {
            return Optional.of("Requested lane index " + laneIndex + " does not exist. Available range: 0-" + (lanes.size() - 1) + ".");
        }

        activeSession = session;
        activeBounds = bounds;
        activeLane = lanes.get(laneIndex);
        placementSelector = new GroundedSweepPlacementExecutor(GroundedSweepPlacementExecutorSettings.fromGroundedSweepSettings(settings));
        lanePlacementsByIndex = new HashMap<>();
        pendingPlacementVerifications.clear();
        pendingPlacementTargets = buildLanePlacementTargets(
                session.getPlan(),
                session.getOrigin(),
                activeBounds,
                activeLane,
                settings.sweepHalfWidth(),
                lanePlacementsByIndex
        );
        currentLeftovers = List.of();
        successfulPlacements = 0;
        failedPlacements = 0;
        missedPlacements = 0;
        laneTicksElapsed = 0;
        awaitingStartApproach = true;
        startApproachIssued = false;
        displacementAlert.reset();
        lastStatus = new DebugStatus(true, activeLane.laneIndex(), GroundedLaneWalker.GroundedLaneWalkState.IDLE, true, Optional.empty(), 0, 0, 0, 0, 0, List.of());
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

        if (laneWalker.state() != GroundedLaneWalker.GroundedLaneWalkState.ACTIVE) {
            handleTerminalState(client);
            return;
        }

        laneWalker.tick(client.player.getEntityPos());
        laneTicksElapsed++;
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
        if (!isActive()) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                clearControls(client);
            }
            return Optional.empty();
        }

        awaitingStartApproach = false;
        laneWalker.interrupt();
        baritoneFacade.cancel();
        handleTerminalState(MinecraftClient.getInstance());
        return Optional.empty();
    }

    public DebugStatus status() {
        if (isActive()) {
            return new DebugStatus(
                    true,
                    activeLane.laneIndex(),
                    laneWalker.state(),
                    awaitingStartApproach,
                    laneWalker.failureReason(),
                    laneTicksElapsed,
                    successfulPlacements,
                    missedPlacements,
                    failedPlacements,
                    pendingPlacementVerifications.size(),
                    currentLeftovers
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
        laneWalker.start(activeLane, activeBounds, constantSprint);
        awaitingStartApproach = false;
        lastStatus = new DebugStatus(
                true,
                activeLane.laneIndex(),
                GroundedLaneWalker.GroundedLaneWalkState.ACTIVE,
                false,
                Optional.empty(),
                laneTicksElapsed,
                successfulPlacements,
                missedPlacements,
                failedPlacements,
                pendingPlacementVerifications.size(),
                currentLeftovers
        );
        applyLaneControls(client);
    }

    void issueStartApproachIfNeeded() {
        if (!startApproachIssued && activeLane != null && activeBounds != null) {
            baritoneFacade.goTo(approachTargetForLaneStart(activeLane, activeBounds));
            startApproachIssued = true;
        }
    }

    void finalizeTerminalStateForTests(GroundedLaneWalker.GroundedLaneWalkState terminalState, Optional<String> failureReason) {
        captureLastStatus(terminalState, failureReason);
        clearActiveRunState();
    }

    void seedLanePlacementsForTests(List<GroundedSweepPlacementExecutor.PlacementTarget> pendingPlacements) {
        pendingPlacementTargets = List.copyOf(pendingPlacements);
        currentLeftovers = List.of();
        pendingPlacementVerifications.clear();
    }

    void seedPlacementDefinitionForTests(int placementIndex, Placement placement) {
        if (lanePlacementsByIndex instanceof HashMap<Integer, Placement> mutablePlacements) {
            mutablePlacements.put(placementIndex, placement);
            return;
        }
        Map<Integer, Placement> mutablePlacements = new HashMap<>(lanePlacementsByIndex);
        mutablePlacements.put(placementIndex, placement);
        lanePlacementsByIndex = mutablePlacements;
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
                selectablePendingPlacementTargets()
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
        return placementSelector.select(activeLane, activeBounds, currentProgressCoordinate, tick, selectablePendingPlacementTargets())
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

    void recordPlacementAttemptStatusForTests(int placementIndex, PlacementResult.Status status, long tick) {
        if (placementSelector == null || status == null) {
            return;
        }
        if (status == PlacementResult.Status.PLACED) {
            Placement placement = lanePlacementsByIndex.get(placementIndex);
            Optional<BlockPos> worldPos = pendingPlacementTargets.stream()
                    .filter(target -> target.placementIndex() == placementIndex)
                    .map(GroundedSweepPlacementExecutor.PlacementTarget::worldPos)
                    .findFirst();
            if (placement != null && worldPos.isPresent()) {
                queuePendingVerification(placementIndex, worldPos.get(), placement, tick);
            }
            return;
        }
        if (status == PlacementResult.Status.ALREADY_CORRECT) {
            onPlacementResult(placementIndex, GroundedSweepPlacementExecutor.PlacementResult.SUCCESS, tick);
            return;
        }
        if (status == PlacementResult.Status.RETRY || status == PlacementResult.Status.MOVE_REQUIRED) {
            onPlacementResult(placementIndex, GroundedSweepPlacementExecutor.PlacementResult.MISSED, tick);
            return;
        }
        onFinalFailure(placementIndex);
    }

    void queuePendingVerificationForTests(int placementIndex, Placement placement, BlockPos worldPos, long dueTick) {
        pendingPlacementVerifications.put(placementIndex, new PendingPlacementVerification(placementIndex, worldPos, placement, dueTick));
    }

    int pendingVerificationCountForTests() {
        return pendingPlacementVerifications.size();
    }

    void processPendingVerificationsForTests(long tick, IntPredicate verificationMatcher, boolean includeNotYetDue) {
        processPendingPlacementVerifications(tick, includeNotYetDue, verificationMatcher::test);
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
        processDuePlacementVerifications(client, true);
        captureLastStatus(laneWalker.state(), laneWalker.failureReason());
        if (client != null) {
            clearControls(client);
        }
        clearActiveRunState();
    }

    private void captureLastStatus(GroundedLaneWalker.GroundedLaneWalkState state, Optional<String> failureReason) {
        Integer laneIndex = activeLane == null ? null : activeLane.laneIndex();
        lastStatus = new DebugStatus(
                false,
                laneIndex,
                state,
                false,
                failureReason == null ? Optional.empty() : failureReason,
                laneTicksElapsed,
                successfulPlacements,
                missedPlacements,
                failedPlacements,
                pendingPlacementVerifications.size(),
                currentLeftovers
        );
    }

    private void clearActiveRunState() {
        activeSession = null;
        activeBounds = null;
        activeLane = null;
        placementSelector = null;
        lanePlacementsByIndex = Map.of();
        pendingPlacementVerifications.clear();
        pendingPlacementTargets = List.of();
        currentLeftovers = List.of();
        awaitingStartApproach = false;
        startApproachIssued = false;
        displacementAlert.reset();
    }

    private void tickPlacementExecutor(MinecraftClient client) {
        if (activeLane == null || activeBounds == null || activeSession == null || placementSelector == null) {
            return;
        }

        BlockPos playerPos = client.player.getBlockPos();
        int currentProgress = activeLane.direction().alongX() ? playerPos.getX() : playerPos.getZ();
        processDuePlacementVerifications(client, false);
        List<GroundedSweepPlacementExecutor.PlacementTarget> selectableTargets = selectablePendingPlacementTargets();
        if (selectableTargets.isEmpty()) {
            currentLeftovers = placementSelector.select(
                    activeLane,
                    activeBounds,
                    currentProgress,
                    laneTicksElapsed,
                    selectableTargets
            ).leftovers();
            return;
        }
        GroundedSweepPlacementExecutor.SweepSelection selection = placementSelector.select(
                activeLane,
                activeBounds,
                currentProgress,
                laneTicksElapsed,
                selectableTargets
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
                case PLACED -> queuePendingVerification(candidate.placementIndex(), candidate.worldPos(), placement, laneTicksElapsed);
                case ALREADY_CORRECT -> onPlacementResult(candidate.placementIndex(), GroundedSweepPlacementExecutor.PlacementResult.SUCCESS, laneTicksElapsed);
                case RETRY, MOVE_REQUIRED -> onPlacementResult(candidate.placementIndex(), GroundedSweepPlacementExecutor.PlacementResult.MISSED, laneTicksElapsed);
                case MISSING_ITEM, ERROR -> onFinalFailure(candidate.placementIndex());
            }
            attempts++;
        }

        processDuePlacementVerifications(client, false);
        currentLeftovers = placementSelector.select(
                activeLane,
                activeBounds,
                currentProgress,
                laneTicksElapsed,
                selectablePendingPlacementTargets()
        ).leftovers();
    }

    private void onPlacementResult(int placementIndex, GroundedSweepPlacementExecutor.PlacementResult groundedResult, long tick) {
        placementSelector.recordPlacementResult(placementIndex, groundedResult, tick);
        if (groundedResult == GroundedSweepPlacementExecutor.PlacementResult.SUCCESS) {
            successfulPlacements++;
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
        pendingPlacementVerifications.remove(placementIndex);
        removePendingPlacement(placementIndex);
    }

    private void removePendingPlacement(int placementIndex) {
        pendingPlacementVerifications.remove(placementIndex);
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

    private void queuePendingVerification(int placementIndex, BlockPos worldPos, Placement placement, long currentTick) {
        pendingPlacementVerifications.put(
                placementIndex,
                new PendingPlacementVerification(placementIndex, worldPos, placement, currentTick + PLACEMENT_VERIFICATION_DELAY_TICKS)
        );
    }

    private void processDuePlacementVerifications(MinecraftClient client, boolean includeNotYetDue) {
        processPendingPlacementVerifications(
                laneTicksElapsed,
                includeNotYetDue,
                placementIndex -> isPlacementVerifiedInWorld(client, placementIndex)
        );
    }

    private void processPendingPlacementVerifications(long currentTick, boolean includeNotYetDue, IntPredicate verifiedPlacementMatcher) {
        if (placementSelector == null || pendingPlacementVerifications.isEmpty()) {
            return;
        }
        List<PendingPlacementVerification> dueVerifications = new ArrayList<>();
        for (PendingPlacementVerification verification : pendingPlacementVerifications.values()) {
            if (!includeNotYetDue && verification.dueTick() > currentTick) {
                continue;
            }
            dueVerifications.add(verification);
        }

        for (PendingPlacementVerification verification : dueVerifications) {
            boolean verified = verifiedPlacementMatcher.test(verification.placementIndex());
            if (verified) {
                onPlacementResult(verification.placementIndex(), GroundedSweepPlacementExecutor.PlacementResult.SUCCESS, currentTick);
            } else {
                onPlacementResult(verification.placementIndex(), GroundedSweepPlacementExecutor.PlacementResult.MISSED, currentTick);
            }
        }
    }

    private boolean isPlacementVerifiedInWorld(MinecraftClient client, int placementIndex) {
        PendingPlacementVerification verification = pendingPlacementVerifications.get(placementIndex);
        if (verification == null) {
            return false;
        }
        if (client == null) {
            return false;
        }
        ClientWorld world = client.world;
        if (world == null) {
            return false;
        }
        BlockPos worldPos = verification.worldPos();
        if (!world.isPosLoaded(worldPos)) {
            return false;
        }
        if (verification.expectedPlacement().block() == null) {
            return false;
        }
        return world.getBlockState(worldPos).isOf(verification.expectedPlacement().block());
    }

    private List<GroundedSweepPlacementExecutor.PlacementTarget> selectablePendingPlacementTargets() {
        if (pendingPlacementVerifications.isEmpty()) {
            return pendingPlacementTargets;
        }
        List<GroundedSweepPlacementExecutor.PlacementTarget> selectable = new ArrayList<>(pendingPlacementTargets.size());
        for (GroundedSweepPlacementExecutor.PlacementTarget target : pendingPlacementTargets) {
            if (!pendingPlacementVerifications.containsKey(target.placementIndex())) {
                selectable.add(target);
            }
        }
        return List.copyOf(selectable);
    }

    private static List<GroundedSweepPlacementExecutor.PlacementTarget> buildLanePlacementTargets(
            BuildPlan plan,
            BlockPos origin,
            GroundedSchematicBounds bounds,
            GroundedSweepLane lane,
            int sweepHalfWidth,
            Map<Integer, Placement> lanePlacementsByIndex
    ) {
        List<GroundedSweepPlacementExecutor.PlacementTarget> targets = new ArrayList<>();
        GroundedLaneCorridorBounds corridor = lane.corridorBounds();
        for (int i = 0; i < plan.placements().size(); i++) {
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

    static BlockPos approachTargetForLaneStart(GroundedSweepLane lane, GroundedSchematicBounds bounds) {
        return new BlockPos(lane.startPoint().getX(), bounds.minY() + 1, lane.startPoint().getZ());
    }

    static boolean isNearLaneStart(Vec3d playerPosition, BlockPos standingStartTarget) {
        Vec3d standingCenter = new Vec3d(standingStartTarget.getX() + 0.5, standingStartTarget.getY(), standingStartTarget.getZ() + 0.5);
        Vec3d playerFlat = new Vec3d(playerPosition.x, standingCenter.y, playerPosition.z);
        return playerFlat.squaredDistanceTo(standingCenter) <= 2.25;
    }

    static List<GroundedSweepPlacementExecutor.PlacementTarget> buildLanePlacementTargetsForTests(
            BuildPlan plan,
            BlockPos origin,
            GroundedSchematicBounds bounds,
            GroundedSweepLane lane,
            int sweepHalfWidth,
            Map<Integer, Placement> lanePlacementsByIndex
    ) {
        return buildLanePlacementTargets(plan, origin, bounds, lane, sweepHalfWidth, lanePlacementsByIndex);
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
        client.player.setSprinting(false);
    }

    private static void setKey(KeyBinding key, boolean pressed) {
        if (key != null) {
            key.setPressed(pressed);
        }
    }

    public boolean isActive() {
        return activeLane != null;
    }

    public record DebugStatus(
            boolean active,
            Integer laneIndex,
            GroundedLaneWalker.GroundedLaneWalkState walkState,
            boolean awaitingStartApproach,
            Optional<String> failureReason,
            long ticksElapsed,
            int successfulPlacements,
            int missedPlacements,
            int failedPlacements,
            int pendingVerification,
            List<GroundedSweepLeftoverTracker.GroundedLeftoverRecord> leftovers
    ) {
        public DebugStatus {
            failureReason = failureReason == null ? Optional.empty() : failureReason;
            leftovers = leftovers == null ? List.of() : List.copyOf(leftovers);
        }
    }

    private record PendingPlacementVerification(int placementIndex, BlockPos worldPos, Placement expectedPlacement, long dueTick) {
        private PendingPlacementVerification {
            Objects.requireNonNull(worldPos, "worldPos");
            Objects.requireNonNull(expectedPlacement, "expectedPlacement");
        }
    }
}
