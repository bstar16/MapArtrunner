package com.example.mapart.plan.state;

import com.example.mapart.persistence.ConfigStore;
import com.example.mapart.persistence.ProgressStore;
import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.Region;
import net.minecraft.block.BlockState;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
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
        progressStore.initializePlanProgress(session);
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

    public Optional<String> setOrigin(BlockPos origin) {
        if (session == null) {
            return Optional.of("No build plan loaded.");
        }
        session.setOrigin(origin.toImmutable());
        configStore.rememberOrigin(origin);
        progressStore.saveProgress(session);
        return Optional.empty();
    }

    public Optional<String> start() {
        if (session == null) {
            return Optional.of("No build plan loaded.");
        }
        if (session.getOrigin() == null) {
            return Optional.of("Origin is not set. Use /mapart setorigin first.");
        }

        try {
            if (session.getState() == BuildPlanState.COMPLETED) {
                session.getProgress().setCurrentPlacementIndex(0);
                session.getProgress().setCurrentRegionIndex(0);
            }
            session.transitionTo(BuildPlanState.BUILDING);
            progressStore.saveProgress(session);
            return Optional.empty();
        } catch (IllegalStateException exception) {
            return Optional.of("Cannot start from state " + session.getState() + ".");
        }
    }

    public Optional<String> pause() {
        if (session == null) {
            return Optional.of("No build session.");
        }

        try {
            session.transitionTo(BuildPlanState.PAUSED);
            progressStore.saveProgress(session);
            return Optional.empty();
        } catch (IllegalStateException exception) {
            return Optional.of("Can only pause while BUILDING.");
        }
    }


    public Optional<String> stop() {
        if (session == null) {
            return Optional.of("No build session.");
        }

        if (session.getState() == BuildPlanState.IDLE) {
            return Optional.of("No active build session.");
        }

        try {
            session.getProgress().reset();
            if (session.getState() != BuildPlanState.LOADED) {
                session.transitionTo(BuildPlanState.LOADED);
            }
            progressStore.saveProgress(session);
            return Optional.empty();
        } catch (IllegalStateException exception) {
            return Optional.of("Cannot stop from state " + session.getState() + ".");
        }
    }

    public Optional<String> resume() {
        if (session == null) {
            return Optional.of("No build session.");
        }

        try {
            session.transitionTo(BuildPlanState.BUILDING);
            progressStore.saveProgress(session);
            return Optional.empty();
        } catch (IllegalStateException exception) {
            return Optional.of("Can only resume while PAUSED.");
        }
    }

    public StepResult next(ServerCommandSource source) {
        ValidationResult validation = validateForNext(source);
        if (!validation.valid()) {
            return StepResult.error(validation.message());
        }

        BuildPlan plan = session.getPlan();
        List<Placement> placements = plan.placements();
        BuildProgress progress = session.getProgress();

        while (progress.getCurrentPlacementIndex() < placements.size()) {
            Placement placement = placements.get(progress.getCurrentPlacementIndex());
            Optional<BlockPos> targetPos = placementResolver.resolveAbsolute(session.getOrigin(), placement);
            if (targetPos.isEmpty()) {
                session.transitionTo(BuildPlanState.ERROR);
                return StepResult.error("Failed to resolve target block position.");
            }

            ServerWorld world = source.getWorld();
            BlockPos absolute = targetPos.get();
            if (!world.isChunkLoaded(absolute)) {
                return StepResult.error("Target chunk is not loaded at " + absolute.toShortString() + ".");
            }

            BlockState currentState = world.getBlockState(absolute);
            if (currentState.isOf(placement.block())) {
                progress.incrementCompletedPlacements();
                progress.setCurrentPlacementIndex(progress.getCurrentPlacementIndex() + 1);
                updateRegionIndex(progress, plan.regions());
                continue;
            }

            progress.incrementCompletedPlacements();
            progress.setCurrentPlacementIndex(progress.getCurrentPlacementIndex() + 1);
            updateRegionIndex(progress, plan.regions());
            progressStore.saveProgress(session);
            return StepResult.actionable(placement, absolute);
        }

        session.transitionTo(BuildPlanState.COMPLETED);
        progressStore.saveProgress(session);
        return StepResult.completed();
    }

    private ValidationResult validateForNext(ServerCommandSource source) {
        if (session == null) {
            return ValidationResult.error("No plan loaded.");
        }
        if (session.getState() != BuildPlanState.BUILDING) {
            return ValidationResult.error("Build is not active. Use /mapart start or /mapart resume.");
        }
        if (session.getOrigin() == null) {
            return ValidationResult.error("Origin is not set.");
        }

        if (source.getWorld() == null) {
            return ValidationResult.error("World is unavailable.");
        }

        BuildProgress progress = session.getProgress();
        BuildPlan plan = session.getPlan();

        if (progress.getCurrentRegionIndex() < 0 || progress.getCurrentRegionIndex() > plan.regions().size()) {
            session.transitionTo(BuildPlanState.ERROR);
            return ValidationResult.error("Invalid current region index.");
        }

        if (progress.getCurrentPlacementIndex() < 0 || progress.getCurrentPlacementIndex() > plan.placements().size()) {
            session.transitionTo(BuildPlanState.ERROR);
            return ValidationResult.error("Invalid current placement index.");
        }

        return ValidationResult.success();
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
