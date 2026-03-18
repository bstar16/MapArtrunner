package com.example.mapart.plan.state;

import com.example.mapart.baritone.BaritoneFacade;
import com.example.mapart.persistence.ConfigStore;
import com.example.mapart.persistence.ProgressStore;
import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.Region;
import com.example.mapart.supply.SupplyPoint;
import com.example.mapart.supply.SupplyStore;
import com.example.mapart.util.MaterialCountFormatter;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class BuildCoordinator {
    private static final int TARGET_APPROACH_RANGE = 3;

    private final WorldPlacementResolver placementResolver;
    private final ConfigStore configStore;
    private final ProgressStore progressStore;
    private final SupplyStore supplyStore;
    private final BaritoneFacade baritoneFacade;
    private BuildSession session;
    private BlockPos activeMovementTarget;
    private boolean movementPaused;
    private MovementPurpose activeMovementPurpose = MovementPurpose.BUILD;

    public BuildCoordinator(
            WorldPlacementResolver placementResolver,
            ConfigStore configStore,
            ProgressStore progressStore,
            SupplyStore supplyStore,
            BaritoneFacade baritoneFacade
    ) {
        this.placementResolver = placementResolver;
        this.configStore = configStore;
        this.progressStore = progressStore;
        this.supplyStore = supplyStore;
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
            session.setRefillStatus(null);
        }

        return transitionSession(BuildPlanState.BUILDING, "Cannot start from state " + session.getState() + ".");
    }

    public Optional<String> pause() {
        if (session == null) {
            return Optional.of("No build session.");
        }
        if (session.getState() == BuildPlanState.PAUSED) {
            return Optional.of("Build session is already paused.");
        }

        BuildPlanState currentState = session.getState();
        session.setStateBeforePause(currentState);
        Optional<String> sessionTransitionError = transitionSession(BuildPlanState.PAUSED, "Cannot pause while " + currentState + ".");
        if (sessionTransitionError.isPresent()) {
            session.setStateBeforePause(null);
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
        session.setRefillStatus(null);
        session.setStateBeforePause(null);
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
        if (session.getState() != BuildPlanState.PAUSED) {
            return Optional.of("Can only resume while PAUSED.");
        }

        BuildPlanState resumeState = session.getStateBeforePause() == null ? BuildPlanState.BUILDING : session.getStateBeforePause();
        Optional<String> sessionTransitionError = transitionSession(resumeState, "Can only resume while PAUSED.");
        if (sessionTransitionError.isPresent()) {
            return sessionTransitionError;
        }
        session.setStateBeforePause(null);

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
        ValidationResult validation = validateForTick(client);
        if (!validation.valid()) {
            return AssistedStepResult.noop();
        }

        if (activeMovementTarget != null) {
            return monitorActiveMovement(client);
        }

        if (session.getState() == BuildPlanState.BUILDING) {
            Optional<RefillCheck> refillCheck = checkForRefill(client);
            if (refillCheck.isPresent()) {
                return beginRefillMovement(refillCheck.get());
            }
        }

        if (session.getState() != BuildPlanState.BUILDING) {
            return AssistedStepResult.noop();
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
        activeMovementPurpose = MovementPurpose.BUILD;
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

    public Optional<RefillStatus> refillStatus() {
        return session == null ? Optional.empty() : Optional.ofNullable(session.getRefillStatus());
    }

    private StepResult computeNextStep(MinecraftClient client, boolean advanceOnActionable) {
        ValidationResult validation = validateForBuildActions(client);
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

    private Optional<RefillCheck> checkForRefill(MinecraftClient client) {
        if (session == null || client.player == null || client.world == null) {
            return Optional.empty();
        }

        int regionIndex = session.getCurrentRegionIndex();
        List<Region> regions = session.getPlan().regions();
        if (regionIndex < 0 || regionIndex >= regions.size()) {
            return Optional.empty();
        }

        Region region = regions.get(regionIndex);
        Map<Identifier, Integer> required = computeRequiredMaterialsForCurrentRegion();
        if (required.isEmpty()) {
            session.setRefillStatus(null);
            return Optional.empty();
        }

        Map<Identifier, Integer> inventory = countInventoryMaterials(client.player);
        Map<Identifier, Integer> missing = new LinkedHashMap<>();
        required.forEach((id, count) -> {
            int deficit = count - inventory.getOrDefault(id, 0);
            if (deficit > 0) {
                missing.put(id, deficit);
            }
        });

        if (missing.isEmpty()) {
            session.setRefillStatus(null);
            return Optional.empty();
        }

        String dimensionKey = client.world.getRegistryKey().getValue().toString();
        SupplyPoint supplyPoint = supplyStore.findNearestInDimension(dimensionKey, client.player.getBlockPos()).orElse(null);
        session.setRefillStatus(new RefillStatus(supplyPoint, missing, false));
        progressStore.saveProgress(session);
        return Optional.of(new RefillCheck(region, missing, supplyPoint));
    }

    private AssistedStepResult beginRefillMovement(RefillCheck refillCheck) {
        List<String> missingSummary = formatMaterialMap(refillCheck.missingMaterials(), 5);
        String missingText = missingSummary.isEmpty() ? "missing materials" : String.join(", ", missingSummary);

        if (refillCheck.supplyPoint() == null) {
            transitionSession(BuildPlanState.NEED_REFILL, "Cannot switch to NEED_REFILL.");
            return pauseForRecoverableFailure("Need refill for region " + session.getCurrentRegionIndex()
                    + ", but no supply point is registered in this dimension. Missing: " + missingText);
        }

        if (session.getState() != BuildPlanState.NEED_REFILL) {
            Optional<String> transitionError = transitionSession(BuildPlanState.NEED_REFILL, "Cannot switch to NEED_REFILL.");
            if (transitionError.isPresent()) {
                return AssistedStepResult.failure(transitionError.get(), false);
            }
        }

        BaritoneFacade.CommandResult movementRequest = baritoneFacade.goNear(refillCheck.supplyPoint().pos(), TARGET_APPROACH_RANGE);
        if (!movementRequest.success()) {
            return pauseForRecoverableFailure("Failed to start refill movement to supply #" + refillCheck.supplyPoint().id()
                    + " at " + refillCheck.supplyPoint().pos().toShortString() + ": " + movementRequest.message());
        }

        activeMovementTarget = refillCheck.supplyPoint().pos().toImmutable();
        activeMovementPurpose = MovementPurpose.REFILL;
        movementPaused = false;
        return AssistedStepResult.moving("Missing materials for region " + session.getCurrentRegionIndex()
                + " (" + missingText + "). Moving to supply #" + refillCheck.supplyPoint().id()
                + " at " + refillCheck.supplyPoint().pos().toShortString() + ".");
    }

    private Map<Identifier, Integer> computeRequiredMaterialsForCurrentRegion() {
        if (session == null) {
            return Map.of();
        }

        List<Region> regions = session.getPlan().regions();
        int regionIndex = session.getCurrentRegionIndex();
        if (regionIndex < 0 || regionIndex >= regions.size()) {
            return Map.of();
        }

        Region region = regions.get(regionIndex);
        int regionStartIndex = regionStartIndex(regions, regionIndex);
        int localStartIndex = Math.max(0, session.getCurrentPlacementIndex() - regionStartIndex);
        if (localStartIndex >= region.placements().size()) {
            return Map.of();
        }

        Map<Identifier, Integer> required = new LinkedHashMap<>();
        for (int i = localStartIndex; i < region.placements().size(); i++) {
            Placement placement = region.placements().get(i);
            Identifier id = Registries.BLOCK.getId(placement.block());
            required.merge(id, 1, Integer::sum);
        }
        return required;
    }

    private Map<Identifier, Integer> countInventoryMaterials(ClientPlayerEntity player) {
        if (player == null) {
            return Map.of();
        }

        Map<Identifier, Integer> inventory = new LinkedHashMap<>();
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            Identifier id = Registries.ITEM.getId(stack.getItem());
            inventory.merge(id, stack.getCount(), Integer::sum);
        }
        return inventory;
    }

    private int regionStartIndex(List<Region> regions, int regionIndex) {
        int index = 0;
        for (int i = 0; i < regionIndex; i++) {
            index += regions.get(i).placements().size();
        }
        return index;
    }

    private List<String> formatMaterialMap(Map<Identifier, Integer> counts, int limit) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<Identifier, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(entry -> entry.getKey().toString()))
                .limit(limit)
                .map(entry -> entry.getKey() + "=" + MaterialCountFormatter.formatCount(entry.getValue(), resolveItem(entry.getKey())))
                .collect(Collectors.toList());
    }

    private Item resolveItem(Identifier id) {
        Block block = Registries.BLOCK.get(id);
        if (block == null) {
            return Items.AIR;
        }
        Item item = block.asItem();
        return item == null ? Items.AIR : item;
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
                resolveNextTarget(session),
                Optional.ofNullable(session.getRefillStatus())
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

    private ValidationResult validateForTick(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return ValidationResult.error("Client context is unavailable.");
        }
        if (session == null) {
            return ValidationResult.error("No plan loaded.");
        }
        if (session.getState() == BuildPlanState.PAUSED || session.getState() == BuildPlanState.ERROR
                || session.getState() == BuildPlanState.COMPLETED || session.getState() == BuildPlanState.LOADED
                || session.getState() == BuildPlanState.IDLE) {
            return ValidationResult.error("Build is not active.");
        }
        if (session.getOrigin() == null) {
            return ValidationResult.error("Origin is not set.");
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

    private ValidationResult validateForBuildActions(MinecraftClient client) {
        ValidationResult validation = validateForTick(client);
        if (!validation.valid()) {
            return validation;
        }
        if (session.getState() != BuildPlanState.BUILDING) {
            return ValidationResult.error("Build is not active. Use /mapart start or /mapart resume.");
        }
        return ValidationResult.success();
    }

    private AssistedStepResult monitorActiveMovement(MinecraftClient client) {
        if (session == null || client.player == null) {
            return AssistedStepResult.noop();
        }
        if (session.getState() == BuildPlanState.PAUSED) {
            return AssistedStepResult.noop();
        }

        BlockPos playerPos = client.player.getBlockPos();
        if (isWithinRange(playerPos, activeMovementTarget, TARGET_APPROACH_RANGE)) {
            if (baritoneFacade.isBusy()) {
                baritoneFacade.cancel();
            }

            BlockPos reachedTarget = activeMovementTarget;
            activeMovementTarget = null;
            movementPaused = false;
            if (activeMovementPurpose == MovementPurpose.REFILL) {
                activeMovementPurpose = MovementPurpose.BUILD;
                if (session.getRefillStatus() != null) {
                    session.setRefillStatus(new RefillStatus(session.getRefillStatus().supplyPoint(), session.getRefillStatus().missingMaterials(), true));
                }
                if (session.getState() == BuildPlanState.NEED_REFILL) {
                    transitionSession(BuildPlanState.REFILLING, "Cannot switch to REFILLING.");
                }
                return AssistedStepResult.arrived("Arrived at supply point " + reachedTarget.toShortString() + ". Ready to refill.");
            }

            return AssistedStepResult.arrived("Reached target area.");
        }

        if (movementPaused) {
            return AssistedStepResult.noop();
        }

        if (!baritoneFacade.isBusy()) {
            String message = "Movement ended before reaching " + activeMovementTarget.toShortString() + ". Run /mapart resume to retry.";
            activeMovementTarget = null;
            activeMovementPurpose = MovementPurpose.BUILD;
            return pauseForRecoverableFailure(message);
        }

        return AssistedStepResult.noop();
    }

    private AssistedStepResult pauseForRecoverableFailure(String message) {
        if (session != null && session.getState() != BuildPlanState.PAUSED) {
            session.setStateBeforePause(session.getState());
            transitionSession(BuildPlanState.PAUSED, "Cannot pause current state.");
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
        activeMovementPurpose = MovementPurpose.BUILD;
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
                case BUILDING, NEED_REFILL, REFILLING, RETURNING -> activeSession.transitionTo(restoredState);
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

    private enum MovementPurpose {
        BUILD,
        REFILL
    }

    private record ValidationResult(boolean valid, String message) {
        static ValidationResult success() {
            return new ValidationResult(true, "");
        }

        static ValidationResult error(String message) {
            return new ValidationResult(false, message);
        }
    }

    private record RefillCheck(Region region, Map<Identifier, Integer> missingMaterials, SupplyPoint supplyPoint) {
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
            Optional<NextTarget> nextTarget,
            Optional<RefillStatus> refillStatus
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
            String message
    ) {
        static AssistedStepResult noop() {
            return new AssistedStepResult(false, false, false, "");
        }

        static AssistedStepResult moving(String message) {
            return new AssistedStepResult(true, false, false, message);
        }

        static AssistedStepResult arrived(String message) {
            return new AssistedStepResult(true, false, false, message);
        }

        static AssistedStepResult completed(String message) {
            return new AssistedStepResult(true, true, false, message);
        }

        static AssistedStepResult failure(String message, boolean done) {
            return new AssistedStepResult(true, done, true, message);
        }
    }
}
