package com.example.mapart.plan.state;

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
    private final WorldPlacementResolver placementResolver;
    private final ConfigStore configStore;
    private final ProgressStore progressStore;
    private BuildSession session;

    public BuildCoordinator(WorldPlacementResolver placementResolver, ConfigStore configStore, ProgressStore progressStore) {
        this.placementResolver = placementResolver;
        this.configStore = configStore;
        this.progressStore = progressStore;
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

        return transitionSession(BuildPlanState.PAUSED, "Can only pause while BUILDING.");
    }

    public Optional<String> stop() {
        if (session == null) {
            return Optional.of("No build session.");
        }

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

        return transitionSession(BuildPlanState.BUILDING, "Can only resume while PAUSED.");
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
        ValidationResult validation = validateForNext(client);
        if (!validation.valid()) {
            return StepResult.error(validation.message());
        }

        BuildPlan plan = session.getPlan();
        List<Placement> placements = plan.placements();

        while (session.getCurrentPlacementIndex() < placements.size()) {
            Placement placement = placements.get(session.getCurrentPlacementIndex());
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
            session.incrementCompletedPlacements();
            session.setCurrentPlacementIndex(session.getCurrentPlacementIndex() + 1);
            updateRegionIndex(session.getProgress(), plan.regions());

            if (currentState.isOf(placement.block())) {
                continue;
            }

            progressStore.saveProgress(session);
            return StepResult.actionable(placement, absolute);
        }

        if (transitionToCompleted()) {
            return StepResult.completed();
        }

        return StepResult.error("Failed to transition session to COMPLETED state.");
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
}
