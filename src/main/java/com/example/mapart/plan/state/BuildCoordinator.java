package com.example.mapart.plan.state;

import com.example.mapart.baritone.BaritoneFacade;
import com.example.mapart.persistence.ConfigStore;
import com.example.mapart.persistence.ProgressStore;
import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.Region;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Optional;

public class BuildCoordinator {
    private static final int TARGET_APPROACH_RANGE = 3;

    private final WorldPlacementResolver placementResolver;
    private final ConfigStore configStore;
    private final ProgressStore progressStore;
    private final BaritoneFacade baritoneFacade;
    private BuildSession session;
    private BlockPos activeMovementTarget;
    private boolean movementPaused;

    public BuildCoordinator(
            WorldPlacementResolver placementResolver,
            ConfigStore configStore,
            ProgressStore progressStore,
            BaritoneFacade baritoneFacade
    ) {
        this.placementResolver = placementResolver;
        this.configStore = configStore;
        this.progressStore = progressStore;
        this.baritoneFacade = baritoneFacade;
    }

    public BuildSession loadPlan(BuildPlan plan) {
        session = new BuildSession(plan);
        session.transitionTo(BuildPlanState.LOADED);
        configStore.rememberLoadedPlan(plan);
        restoreProgressForLoadedPlan(session);
        progressStore.saveProgress(session);
        return session;
    }

    public Optional<BuildSession> getSession() {
        return Optional.ofNullable(session);
    }

    public boolean hasLoadedPlan() {
        return session != null;
    }

    public Optional<BuildPlan> currentPlan() {
        return getSession().map(BuildSession::getPlan);
    }

    public boolean unload() {
        if (session == null) {
            return false;
        }

        cancelActiveMovement();
        session = null;
        progressStore.clearProgress();
        configStore.clearRememberedState();
        return true;
    }

    public Optional<String> setOrigin(BlockPos origin) {
        if (session == null) {
            return Optional.of("No build plan loaded.");
        }

        session.setOrigin(origin.toImmutable());
        progressStore.saveProgress(session);
        configStore.rememberOrigin(origin);
        return Optional.empty();
    }

    public Optional<String> start() {
        if (session == null) {
            return Optional.of("No build plan loaded.");
        }
        if (session.getOrigin() == null) {
            return Optional.of("Origin is not set. Use /mapart setorigin first.");
        }

        if (session.getState() == BuildPlanState.COMPLETED) {
            session.getProgress().reset();
        }

        return transitionSession(BuildPlanState.BUILDING, "Cannot start from state " + session.getState() + ".");
    }

    public Optional<String> pause() {
        if (session == null) {
            return Optional.of("No build session.");
        }

        Optional<String> sessionTransitionError = transitionSession(BuildPlanState.PAUSED, "Can only pause while BUILDING.");
        if (sessionTransitionError.isPresent()) {
            return sessionTransitionError;
        }

        if (activeMovementTarget == null) {
            return Optional.empty();
        }

        BaritoneFacade.CommandResult result = baritoneFacade.pause();
        if (!result.success()) {
            return Optional.of("Build session paused, but failed to pause Baritone: " + result.message());
        }

        movementPaused = true;
        return Optional.empty();
    }

    public Optional<String> stop() {
        if (session == null) {
            return Optional.of("No build session.");
        }

        cancelActiveMovement();
        session.getProgress().reset();
        if (session.getState() == BuildPlanState.LOADED) {
            progressStore.saveProgress(session);
            return Optional.empty();
        }

        return transitionSession(BuildPlanState.LOADED, "Cannot stop from state " + session.getState() + ".");
    }

    public Optional<String> resume() {
        if (session == null) {
            return Optional.of("No build session.");
        }

        Optional<String> sessionTransitionError = transitionSession(BuildPlanState.BUILDING, "Can only resume while PAUSED.");
        if (sessionTransitionError.isPresent()) {
            return sessionTransitionError;
        }

        if (!movementPaused) {
            return Optional.empty();
        }

        BaritoneFacade.CommandResult result = baritoneFacade.resume();
        if (!result.success()) {
            movementPaused = false;
            activeMovementTarget = null;
            return Optional.of("Build session resumed, but Baritone movement could not resume: " + result.message());
        }

        movementPaused = false;
        return Optional.empty();
    }

    public AssistedStepResult tickAssisted(MinecraftClient client) {
        ValidationResult validation = validateForNext(client);
        if (!validation.valid()) {
            return AssistedStepResult.noop();
        }

        if (activeMovementTarget != null) {
            return monitorActiveMovement(client);
        }

        StepResult stepResult = computeNextStep(client, false);
        if (!stepResult.actionable() && !stepResult.done()) {
            return pauseForRecoverableFailure(stepResult.message());
        }
        if (stepResult.done()) {
            cancelActiveMovement();
            return AssistedStepResult.completed(stepResult.message());
        }

        BaritoneFacade.CommandResult movementRequest = baritoneFacade.goNear(stepResult.targetPos(), TARGET_APPROACH_RANGE);
        if (!movementRequest.success()) {
            return pauseForRecoverableFailure("Failed to start movement to "
                    + stepResult.targetPos().toShortString() + ": " + movementRequest.message());
        }

        activeMovementTarget = stepResult.targetPos().toImmutable();
        movementPaused = false;
        return AssistedStepResult.moving("Moving near " + activeMovementTarget.toShortString() + ".");
    }

    public Optional<String> debugSkipToSecondLastPlacement() {
        if (session == null) {
            return Optional.of("No build plan loaded.");
        }

        BuildPlan plan = session.getPlan();
        if (plan.placements().size() < 2) {
            return Optional.of("Plan must contain at least 2 placements to skip to the second last.");
        }

        session.setCurrentPlacementIndex(plan.placements().size() - 2);
        updateRegionIndex(session.getProgress(), plan.regions());
        progressStore.saveProgress(session);
        return Optional.empty();
    }

    public StepResult next(MinecraftClient client) {
        return computeNextStep(client, true);
    }

    private StepResult computeNextStep(MinecraftClient client, boolean advanceOnActionable) {
        ValidationResult validation = validateForNext(client);
        if (!validation.valid()) {
            return StepResult.error(validation.message());
        }
        if (activeMovementTarget != null) {
            return StepResult.error("Movement is already active toward " + activeMovementTarget.toShortString() + ".");
        }

        BuildPlan plan = session.getPlan();
        List<Placement> placements = plan.placements();

        int placementIndex = session.getCurrentPlacementIndex();
        int completedPlacements = 0;

        while (placementIndex < placements.size()) {
            Placement placement = placements.get(placementIndex);
            Optional<BlockPos> targetPos = placementResolver.resolveAbsolute(session, placement);
            if (targetPos.isEmpty()) {
                markSessionError();
                return StepResult.error("Failed to resolve target block position.");
            }

            ClientWorld world = client.world;
            BlockPos absolute = targetPos.get();
            if (!world.isPosLoaded(absolute)) {
                return StepResult.error("Target chunk is not loaded at " + absolute.toShortString() + ".");
            }

            BlockState currentState = world.getBlockState(absolute);
            if (currentState.isOf(placement.block())) {
                completedPlacements++;
                placementIndex++;
                continue;
            }

            if (advanceOnActionable) {
                completedPlacements++;
                placementIndex++;
            }

            applyProgressAdvance(plan, placementIndex, completedPlacements);

            return StepResult.actionable(placement, absolute);
        }

        applyProgressAdvance(plan, placementIndex, completedPlacements);

        if (transitionToCompleted()) {
            return StepResult.completed();
        }

        return StepResult.error("Failed to transition session to COMPLETED state.");
    }

    private void applyProgressAdvance(BuildPlan plan, int placementIndex, int completedPlacements) {
        if (completedPlacements <= 0 && placementIndex == session.getCurrentPlacementIndex()) {
            return;
        }

        session.setCurrentPlacementIndex(placementIndex);
        for (int i = 0; i < completedPlacements; i++) {
            session.incrementCompletedPlacements();
        }
        updateRegionIndex(session.getProgress(), plan.regions());
        progressStore.saveProgress(session);
    }

    public Optional<SessionStatus> sessionStatus() {
        if (session == null) {
            return Optional.empty();
        }

        BuildPlan plan = session.getPlan();
        return Optional.of(new SessionStatus(
                plan.sourcePath().getFileName().toString(),
                session.getState(),
                session.getOrigin(),
                session.getCurrentRegionIndex(),
                plan.regions().size(),
                session.getCurrentPlacementIndex(),
                plan.placements().size(),
                session.getTotalCompletedPlacements(),
                resolveNextTarget(session)
        ));
    }

    private Optional<String> transitionSession(BuildPlanState targetState, String invalidTransitionMessage) {
        try {
            session.transitionTo(targetState);
            progressStore.saveProgress(session);
            return Optional.empty();
        } catch (IllegalStateException exception) {
            return Optional.of(invalidTransitionMessage);
        }
    }

    private boolean transitionToCompleted() {
        try {
            session.transitionTo(BuildPlanState.COMPLETED);
            progressStore.saveProgress(session);
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private void markSessionError() {
        if (session == null || session.getState() == BuildPlanState.ERROR) {
            return;
        }

        try {
            session.transitionTo(BuildPlanState.ERROR);
            progressStore.saveProgress(session);
        } catch (IllegalStateException ignored) {
            // Keep the existing state if transition is not valid.
        }
    }

    private ValidationResult validateForNext(MinecraftClient client) {
        if (client == null || client.player == null) {
            return ValidationResult.error("Client context is unavailable.");
        }
        if (session == null) {
            return ValidationResult.error("No plan loaded.");
        }
        if (session.getState() != BuildPlanState.BUILDING) {
            return ValidationResult.error("Build is not active. Use /mapart start or /mapart resume.");
        }
        if (session.getOrigin() == null) {
            return ValidationResult.error("Origin is not set.");
        }
        if (client.world == null) {
            return ValidationResult.error("World is unavailable.");
        }

        BuildPlan plan = session.getPlan();
        if (session.getCurrentRegionIndex() < 0 || session.getCurrentRegionIndex() > plan.regions().size()) {
            return ValidationResult.error("Invalid current region index.");
        }

        if (session.getCurrentPlacementIndex() < 0 || session.getCurrentPlacementIndex() > plan.placements().size()) {
            return ValidationResult.error("Invalid current placement index.");
        }

        return ValidationResult.success();
    }

    private AssistedStepResult monitorActiveMovement(MinecraftClient client) {
        if (session == null || session.getState() != BuildPlanState.BUILDING || client.player == null) {
            return AssistedStepResult.noop();
        }

        BlockPos playerPos = client.player.getBlockPos();
        if (isWithinRange(playerPos, activeMovementTarget, TARGET_APPROACH_RANGE)) {
            if (baritoneFacade.isBusy()) {
                baritoneFacade.cancel();
            }

            activeMovementTarget = null;
            movementPaused = false;
            return AssistedStepResult.arrived("Reached target area.");
        }

        if (movementPaused) {
            return AssistedStepResult.noop();
        }

        if (!baritoneFacade.isBusy()) {
            String message = "Movement ended before reaching " + activeMovementTarget.toShortString() + ". Run /mapart resume to retry.";
            activeMovementTarget = null;
            return pauseForRecoverableFailure(message);
        }

        return AssistedStepResult.noop();
    }


    private AssistedStepResult pauseForRecoverableFailure(String message) {
        if (session != null && session.getState() == BuildPlanState.BUILDING) {
            transitionSession(BuildPlanState.PAUSED, "Can only pause while BUILDING.");
        }

        movementPaused = false;
        return AssistedStepResult.failure(message, false);
    }

    private boolean isWithinRange(BlockPos playerPos, BlockPos target, int range) {
        return Math.abs(playerPos.getX() - target.getX()) <= range
                && Math.abs(playerPos.getY() - target.getY()) <= range
                && Math.abs(playerPos.getZ() - target.getZ()) <= range;
    }

    private void cancelActiveMovement() {
        if (activeMovementTarget != null || movementPaused || baritoneFacade.isBusy()) {
            baritoneFacade.cancel();
        }

        activeMovementTarget = null;
        movementPaused = false;
    }

    private Optional<NextTarget> resolveNextTarget(BuildSession activeSession) {
        int nextIndex = activeSession.getCurrentPlacementIndex();
        BuildPlan plan = activeSession.getPlan();
        if (nextIndex < 0 || nextIndex >= plan.placements().size()) {
            return Optional.empty();
        }

        Placement placement = plan.placements().get(nextIndex);
        return placementResolver.resolveAbsolute(activeSession, placement)
                .map(pos -> new NextTarget(placement, pos));
    }

    private void restoreProgressForLoadedPlan(BuildSession activeSession) {
        ProgressStore.Snapshot snapshot = progressStore.getSnapshot().orElse(null);
        if (snapshot == null || !activeSession.getPlan().sourcePath().toString().equals(snapshot.loadedPlanId())) {
            configStore.getLastOrigin().ifPresent(activeSession::setOrigin);
            return;
        }

        snapshot.originPos().ifPresent(activeSession::setOrigin);
        activeSession.setCurrentPlacementIndex(snapshot.currentPlacementIndex());
        activeSession.setCurrentRegionIndex(snapshot.currentRegionIndex());
        activeSession.getProgress().setTotalCompletedPlacements(snapshot.totalCompletedPlacements());
        applyRestoredState(activeSession, snapshot.parsedState().orElse(BuildPlanState.LOADED));
    }

    private void applyRestoredState(BuildSession activeSession, BuildPlanState restoredState) {
        if (restoredState == BuildPlanState.LOADED) {
            return;
        }

        try {
            switch (restoredState) {
                case BUILDING -> activeSession.transitionTo(BuildPlanState.BUILDING);
                case PAUSED -> {
                    activeSession.transitionTo(BuildPlanState.BUILDING);
                    activeSession.transitionTo(BuildPlanState.PAUSED);
                }
                case COMPLETED -> {
                    activeSession.transitionTo(BuildPlanState.BUILDING);
                    activeSession.transitionTo(BuildPlanState.COMPLETED);
                }
                case ERROR -> activeSession.transitionTo(BuildPlanState.ERROR);
                case IDLE -> {
                    // Keep LOADED for invalid restore state.
                }
            }
        } catch (IllegalStateException ignored) {
            // Keep LOADED if restore transitions are invalid.
        }
    }

    private void updateRegionIndex(BuildProgress progress, List<Region> regions) {
        int placementCursor = progress.getCurrentPlacementIndex();
        int runningCount = 0;
        for (int i = 0; i < regions.size(); i++) {
            runningCount += regions.get(i).placements().size();
            if (placementCursor < runningCount) {
                progress.setCurrentRegionIndex(i);
                return;
            }
        }
        progress.setCurrentRegionIndex(regions.size());
    }

    private record ValidationResult(boolean valid, String message) {
        static ValidationResult success() {
            return new ValidationResult(true, "");
        }

        static ValidationResult error(String message) {
            return new ValidationResult(false, message);
        }
    }

    public record NextTarget(Placement placement, BlockPos absolutePos) {
    }

    public record SessionStatus(
            String planId,
            BuildPlanState state,
            BlockPos origin,
            int currentRegionIndex,
            int totalRegions,
            int currentPlacementIndex,
            int totalPlacements,
            int totalCompletedPlacements,
            Optional<NextTarget> nextTarget
    ) {
    }

    public record StepResult(boolean done, boolean actionable, String message, Placement placement, BlockPos targetPos) {
        static StepResult error(String message) {
            return new StepResult(false, false, message, null, null);
        }

        static StepResult actionable(Placement placement, BlockPos targetPos) {
            return new StepResult(false, true, "", placement, targetPos);
        }

        static StepResult completed() {
            return new StepResult(true, false, "Build plan complete.", null, null);
        }
    }

    public record AssistedStepResult(
            boolean didWork,
            boolean done,
            boolean failed,
            boolean unrecoverable,
            String message
    ) {
        static AssistedStepResult noop() {
            return new AssistedStepResult(false, false, false, false, "");
        }

        static AssistedStepResult moving(String message) {
            return new AssistedStepResult(true, false, false, false, message);
        }

        static AssistedStepResult arrived(String message) {
            return new AssistedStepResult(true, false, false, false, message);
        }

        static AssistedStepResult completed(String message) {
            return new AssistedStepResult(true, true, false, false, message);
        }

        static AssistedStepResult failure(String message, boolean unrecoverable) {
            return new AssistedStepResult(true, false, true, unrecoverable, message);
        }
    }
}
