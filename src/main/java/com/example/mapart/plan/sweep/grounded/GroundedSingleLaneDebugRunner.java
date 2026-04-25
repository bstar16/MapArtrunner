package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.baritone.BaritoneFacade;
import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.state.BuildSession;
import com.example.mapart.plan.state.PlacementExecutor;
import com.example.mapart.plan.state.PlacementResult;
import com.example.mapart.plan.state.WorldPlacementResolver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class GroundedSingleLaneDebugRunner {
    private final GroundedSweepLanePlanner lanePlanner = new GroundedSweepLanePlanner();
    private final GroundedLaneWalker laneWalker = new GroundedLaneWalker();
    private final GroundedDisplacementAlert displacementAlert = new GroundedDisplacementAlert();
    private final BaritoneFacade baritoneFacade;
    private final PlacementExecutor placementExecutor = new PlacementExecutor();

    private BuildSession activeSession;
    private GroundedSchematicBounds activeBounds;
    private GroundedSweepLane activeLane;
    private GroundedSweepPlacementExecutor activePlacementExecutor;
    private List<GroundedSweepPlacementExecutor.PlacementTarget> lanePlacementTargets = List.of();
    private final Set<Integer> resolvedPlacements = new HashSet<>();
    private List<GroundedSweepLeftoverTracker.GroundedLeftoverRecord> latestLeftovers = List.of();
    private long activeTicks;
    private boolean awaitingStartApproach;
    private boolean startApproachIssued;
    private DebugStatus lastStatus = new DebugStatus(
            false,
            null,
            GroundedLaneWalker.GroundedLaneWalkState.IDLE,
            false,
            Optional.empty(),
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
        activePlacementExecutor = new GroundedSweepPlacementExecutor(
                GroundedSweepPlacementExecutorSettings.fromGroundedSweepSettings(settings)
        );
        lanePlacementTargets = collectLanePlacementTargets(session, bounds, activeLane);
        resolvedPlacements.clear();
        latestLeftovers = List.of();
        activeTicks = 0L;
        awaitingStartApproach = true;
        startApproachIssued = false;
        displacementAlert.reset();
        lastStatus = new DebugStatus(true, activeLane.laneIndex(), GroundedLaneWalker.GroundedLaneWalkState.IDLE, true, Optional.empty(), 0, List.of());
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

        activeTicks++;
        laneWalker.tick(client.player.getEntityPos());
        tickPlacement(client);
        applyLaneControls(client);
        displacementAlert.tick(client, true, client.player.getY() < activeBounds.minY());

        if (laneWalker.state() != GroundedLaneWalker.GroundedLaneWalkState.ACTIVE) {
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
                    activeTicks,
                    latestLeftovers
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
        lastStatus = new DebugStatus(true, activeLane.laneIndex(), GroundedLaneWalker.GroundedLaneWalkState.ACTIVE, false, Optional.empty(), activeTicks, latestLeftovers);
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

    private void handleTerminalState(MinecraftClient client) {
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
                activeTicks,
                latestLeftovers
        );
    }

    private void clearActiveRunState() {
        activeSession = null;
        activeBounds = null;
        activeLane = null;
        activePlacementExecutor = null;
        lanePlacementTargets = List.of();
        resolvedPlacements.clear();
        latestLeftovers = List.of();
        activeTicks = 0L;
        awaitingStartApproach = false;
        startApproachIssued = false;
        displacementAlert.reset();
    }

    private void tickPlacement(MinecraftClient client) {
        if (activeLane == null || activeBounds == null || activeSession == null || activePlacementExecutor == null || client.player == null) {
            return;
        }

        int currentProgressCoordinate = progressCoordinate(activeLane.direction(), client.player.getEntityPos());
        List<GroundedSweepPlacementExecutor.PlacementTarget> pending = lanePlacementTargets.stream()
                .filter(target -> !resolvedPlacements.contains(target.placementIndex()))
                .toList();
        GroundedSweepPlacementExecutor.SweepSelection selection = activePlacementExecutor.select(
                activeLane,
                activeBounds,
                currentProgressCoordinate,
                activeTicks,
                pending
        );
        latestLeftovers = selection.leftovers();

        if (selection.rankedCandidates().isEmpty()) {
            return;
        }

        GroundedSweepPlacementExecutor.SweepCandidate candidate = selection.rankedCandidates().getFirst();
        Placement placement = activeSession.getPlan().placements().get(candidate.placementIndex());
        PlacementResult placementResult = placementExecutor.execute(client, activeSession, placement, candidate.worldPos());
        GroundedSweepPlacementExecutor.PlacementResult groundedResult = mapPlacementResult(placementResult);
        activePlacementExecutor.recordPlacementResult(candidate.placementIndex(), groundedResult, activeTicks);
        if (groundedResult == GroundedSweepPlacementExecutor.PlacementResult.SUCCESS) {
            resolvedPlacements.add(candidate.placementIndex());
        }
        List<GroundedSweepPlacementExecutor.PlacementTarget> unresolvedAfterAttempt = lanePlacementTargets.stream()
                .filter(target -> !resolvedPlacements.contains(target.placementIndex()))
                .toList();
        latestLeftovers = activePlacementExecutor.select(
                activeLane,
                activeBounds,
                currentProgressCoordinate,
                activeTicks,
                unresolvedAfterAttempt
        ).leftovers();
    }

    static List<GroundedSweepPlacementExecutor.PlacementTarget> collectLanePlacementTargets(
            BuildSession session,
            GroundedSchematicBounds bounds,
            GroundedSweepLane lane
    ) {
        BuildPlan plan = session.getPlan();
        WorldPlacementResolver resolver = new WorldPlacementResolver();
        return java.util.stream.IntStream.range(0, plan.placements().size())
                .mapToObj(placementIndex -> {
                    Placement placement = plan.placements().get(placementIndex);
                    Optional<BlockPos> worldPos = resolver.resolveAbsolute(session, placement);
                    if (worldPos.isEmpty()) {
                        return null;
                    }
                    BlockPos pos = worldPos.get();
                    if (!isPlacementWithinLaneAndBounds(pos, lane, bounds)) {
                        return null;
                    }
                    return new GroundedSweepPlacementExecutor.PlacementTarget(placementIndex, pos);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    static boolean isPlacementWithinLaneAndBounds(BlockPos pos, GroundedSweepLane lane, GroundedSchematicBounds bounds) {
        if (pos.getX() < bounds.minX() || pos.getX() > bounds.maxX()
                || pos.getY() < bounds.minY() || pos.getY() > bounds.maxY()
                || pos.getZ() < bounds.minZ() || pos.getZ() > bounds.maxZ()) {
            return false;
        }
        GroundedLaneCorridorBounds corridor = lane.corridorBounds();
        if (pos.getX() < corridor.minX() || pos.getX() > corridor.maxX()
                || pos.getZ() < corridor.minZ() || pos.getZ() > corridor.maxZ()) {
            return false;
        }
        int lateralCoordinate = lane.direction().alongX() ? pos.getZ() : pos.getX();
        return Math.abs(lateralCoordinate - lane.centerlineCoordinate()) <= 2;
    }

    private static int progressCoordinate(GroundedLaneDirection direction, Vec3d position) {
        return direction.alongX() ? (int) Math.floor(position.x) : (int) Math.floor(position.z);
    }

    private static GroundedSweepPlacementExecutor.PlacementResult mapPlacementResult(PlacementResult placementResult) {
        return switch (placementResult.status()) {
            case PLACED, ALREADY_CORRECT -> GroundedSweepPlacementExecutor.PlacementResult.SUCCESS;
            case MOVE_REQUIRED, RETRY -> GroundedSweepPlacementExecutor.PlacementResult.MISSED;
            case MISSING_ITEM, ERROR -> GroundedSweepPlacementExecutor.PlacementResult.FAILED;
        };
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
            List<GroundedSweepLeftoverTracker.GroundedLeftoverRecord> leftovers
    ) {
        public DebugStatus {
            failureReason = failureReason == null ? Optional.empty() : failureReason;
            leftovers = List.copyOf(leftovers);
        }
    }
}
