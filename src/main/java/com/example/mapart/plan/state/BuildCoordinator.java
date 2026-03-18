package com.example.mapart.plan.state;

import com.example.mapart.baritone.BaritoneFacade;
import com.example.mapart.persistence.ConfigStore;
import com.example.mapart.runtime.MapArtRuntime;
import com.example.mapart.persistence.ProgressStore;
import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.Region;
import com.example.mapart.supply.SupplyPoint;
import com.example.mapart.supply.SupplyStore;
import com.example.mapart.util.MaterialCountFormatter;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class BuildCoordinator {
    private static final int TARGET_APPROACH_RANGE = 3;
    private static final int REFILL_ACTION_DELAY_TICKS = 4;
    private static final int MAX_SUPPLY_SCREEN_WAIT_POLLS = 5;
    private static final int MAX_REFILL_LOOKAHEAD_ITEMS = PlayerInventory.MAIN_SIZE * 64;

    private final WorldPlacementResolver placementResolver;
    private final ConfigStore configStore;
    private final ProgressStore progressStore;
    private final SupplyStore supplyStore;
    private final BaritoneFacade baritoneFacade;
    private BuildSession session;
    private BlockPos activeMovementTarget;
    private boolean movementPaused;
    private MovementPurpose activeMovementPurpose = MovementPurpose.BUILD;
    private int refillActionCooldown;
    private boolean awaitingSupplyScreen;
    private int supplyScreenWaitPollsRemaining;

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
        debugToFile("Loaded plan " + plan.sourcePath().getFileName() + " with " + plan.placements().size() + " placements.");
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

        debugToChatAndFile("Unloading current plan and clearing progress.");
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
        debugToChatAndFile("Origin set to " + origin.toShortString() + ".");
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

        debugToChatAndFile("Starting build session from state " + session.getState() + ".");
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

        debugToChatAndFile("Pausing build session.");
        closeHandledScreen(MinecraftClient.getInstance());

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

        debugToChatAndFile("Stopping build session and resetting progress.");
        cancelActiveMovement();
        closeHandledScreen(MinecraftClient.getInstance());
        session.getProgress().reset();
        session.setRefillStatus(null);
        session.setStateBeforePause(null);
        resetRefillInteractionState();
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
        debugToChatAndFile("Resuming build session toward state " + resumeState + ".");
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

        if (session.getState() == BuildPlanState.NEED_REFILL
                || session.getState() == BuildPlanState.REFILLING
                || session.getState() == BuildPlanState.RETURNING) {
            return continueRefillWorkflow(client);
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

        debugToChatAndFile("Next placement target is " + stepResult.targetPos().toShortString() + " for block " + Registries.BLOCK.getId(stepResult.placement().block()) + ".");
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
        Map<Identifier, Integer> required = computeRequiredMaterialsForRefillWindow();
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
        List<SupplyPoint> supplyPoints = supplyStore.listInDimensionByDistance(dimensionKey, client.player.getBlockPos());
        SupplyPoint supplyPoint = supplyPoints.isEmpty() ? null : supplyPoints.getFirst();
        session.setRefillStatus(new RefillStatus(
                supplyPoint,
                supplyPoints,
                0,
                missing,
                false,
                determineReturnTarget()
        ));
        progressStore.saveProgress(session);
        return Optional.of(new RefillCheck(region, missing, supplyPoints, 0));
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

        debugToChatAndFile("Heading to supply #" + refillCheck.supplyPoint().id() + " at " + refillCheck.supplyPoint().pos().toShortString() + " for refill.");
        activeMovementTarget = refillCheck.supplyPoint().pos().toImmutable();
        activeMovementPurpose = MovementPurpose.REFILL;
        movementPaused = false;
        return AssistedStepResult.moving("Missing materials for region " + session.getCurrentRegionIndex()
                + " (" + missingText + "). Moving to supply #" + refillCheck.supplyPoint().id()
                + " at " + refillCheck.supplyPoint().pos().toShortString() + ".");
    }

    private AssistedStepResult continueRefillWorkflow(MinecraftClient client) {
        RefillStatus refillStatus = session == null ? null : session.getRefillStatus();
        if (refillStatus == null) {
            Optional<String> transitionError = transitionSession(BuildPlanState.BUILDING, "Cannot resume building without refill status.");
            return transitionError.isPresent()
                    ? AssistedStepResult.failure(transitionError.get(), false)
                    : AssistedStepResult.noop();
        }

        return switch (session.getState()) {
            case NEED_REFILL -> {
                if (refillStatus.supplyPoint() == null) {
                    yield AssistedStepResult.noop();
                }

                yield beginRefillMovement(new RefillCheck(
                        session.getPlan().regions().get(session.getCurrentRegionIndex()),
                        refillStatus.missingMaterials(),
                        refillStatus.supplyPoints(),
                        refillStatus.supplyIndex()
                ));
            }
            case REFILLING -> performRefill(client, refillStatus);
            case RETURNING -> continueBuildReturnMovement(client, refillStatus.returnTarget());
            default -> AssistedStepResult.noop();
        };
    }

    private AssistedStepResult performRefill(MinecraftClient client, RefillStatus refillStatus) {
        if (client.player == null || client.world == null || refillStatus.supplyPoint() == null) {
            return AssistedStepResult.noop();
        }
        if (!isUsableContainer(client.world, refillStatus.supplyPoint().pos())) {
            return advanceToNextSupplyOrPause(client, refillStatus,
                    "No container is present at registered supply #" + refillStatus.supplyPoint().id()
                            + " (" + refillStatus.supplyPoint().pos().toShortString() + ").");
        }
        if (!refillStatus.arrivedAtSupply()) {
            return AssistedStepResult.noop();
        }
        if (refillActionCooldown > 0) {
            refillActionCooldown--;
            return AssistedStepResult.noop();
        }

        Map<Identifier, Integer> remaining = computeRemainingDeficits(client.player, refillStatus.missingMaterials());
        if (remaining.isEmpty()) {
            closeHandledScreen(client);
            resetRefillInteractionState();
            return continueReturnToBuild(client, refillStatus);
        }

        HandledScreen<?> handledScreen = currentSupplyScreen(client);
        if (handledScreen == null) {
            if (awaitingSupplyScreen) {
                if (supplyScreenWaitPollsRemaining > 0) {
                    supplyScreenWaitPollsRemaining--;
                    return AssistedStepResult.noop();
                }

                return advanceToNextSupplyOrPause(client, refillStatus, "Timed out waiting for the supply container at "
                        + refillStatus.supplyPoint().pos().toShortString() + " to open.");
            }

            ActionResult interactResult = client.interactionManager == null
                    ? ActionResult.FAIL
                    : client.interactionManager.interactBlock(
                    client.player,
                    Hand.MAIN_HAND,
                    new BlockHitResult(
                            Vec3d.ofCenter(refillStatus.supplyPoint().pos()),
                            Direction.UP,
                            refillStatus.supplyPoint().pos(),
                            false
                    )
            );
            if (!interactResult.isAccepted()) {
                return advanceToNextSupplyOrPause(client, refillStatus, "Failed to interact with the supply container at "
                        + refillStatus.supplyPoint().pos().toShortString() + ".");
            }

            awaitingSupplyScreen = true;
            supplyScreenWaitPollsRemaining = MAX_SUPPLY_SCREEN_WAIT_POLLS;
            refillActionCooldown = REFILL_ACTION_DELAY_TICKS;
            debugToChatAndFile("Interacting with supply container at " + refillStatus.supplyPoint().pos().toShortString() + ".");
            return AssistedStepResult.arrived("Opening supply container at "
                    + refillStatus.supplyPoint().pos().toShortString() + ".");
        }

        awaitingSupplyScreen = false;
        supplyScreenWaitPollsRemaining = 0;
        ScreenHandler handler = handledScreen.getScreenHandler();
        int containerSlotCount = Math.max(0, handler.slots.size() - PlayerInventory.MAIN_SIZE);
        if (containerSlotCount <= 0) {
            return failRefill("Opened screen is not a supported supply container.");
        }

        boolean attemptedTransfer = false;
        for (int slotIndex = 0; slotIndex < containerSlotCount; slotIndex++) {
            Slot slot = handler.slots.get(slotIndex);
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) {
                continue;
            }

            Identifier itemId = Registries.ITEM.getId(stack.getItem());
            Integer deficit = remaining.get(itemId);
            if (deficit == null || deficit <= 0) {
                continue;
            }

            int beforeCount = countPlayerItem(client.player, stack.getItem());
            if (client.interactionManager == null) {
                return failRefill("Cannot transfer materials because the interaction manager is unavailable.");
            }

            client.interactionManager.clickSlot(handler.syncId, slotIndex, 0, SlotActionType.QUICK_MOVE, client.player);
            refillActionCooldown = REFILL_ACTION_DELAY_TICKS;
            int moved = Math.max(0, countPlayerItem(client.player, stack.getItem()) - beforeCount);
            attemptedTransfer = true;
            if (moved <= 0) {
                continue;
            }
            debugToChatAndFile("Withdrew " + MaterialCountFormatter.formatCount(moved, stack.getItem()) + " of " + itemId + " from supply.");
            return AssistedStepResult.arrived("Withdrew " + MaterialCountFormatter.formatCount(moved, stack.getItem())
                    + " of " + itemId + " from supply.");
        }

        if (attemptedTransfer) {
            return advanceToNextSupplyOrPause(client, refillStatus,
                    "Supply #" + refillStatus.supplyPoint().id() + " could not transfer the required materials into the player inventory.");
        }

        return advanceToNextSupplyOrPause(client, refillStatus,
                "Supply #" + refillStatus.supplyPoint().id() + " is missing "
                        + String.join(", ", formatMaterialMap(remaining, 5)) + ".");
    }

    private AssistedStepResult continueReturnToBuild(MinecraftClient client, RefillStatus refillStatus) {
        Map<Identifier, Integer> inventory = countInventoryMaterials(client.player);
        boolean stillMissingMaterials = refillStatus.missingMaterials().entrySet().stream()
                .anyMatch(entry -> inventory.getOrDefault(entry.getKey(), 0) < entry.getValue());
        if (stillMissingMaterials) {
            return AssistedStepResult.noop();
        }

        Optional<String> transitionError = transitionSession(BuildPlanState.RETURNING, "Cannot switch to RETURNING.");
        if (transitionError.isPresent()) {
            return AssistedStepResult.failure(transitionError.get(), false);
        }

        closeHandledScreen(client);
        resetRefillInteractionState();
        session.setRefillStatus(null);
        progressStore.saveProgress(session);
        return continueBuildReturnMovement(client, refillStatus.returnTarget());
    }

    private AssistedStepResult continueBuildReturnMovement(MinecraftClient client, BlockPos returnTarget) {
        BlockPos target = returnTarget == null ? null : returnTarget.toImmutable();
        if (target == null) {
            StepResult stepResult = computeNextStep(client, false);
            if (stepResult.done()) {
                cancelActiveMovement();
                return AssistedStepResult.completed(stepResult.message());
            }
            if (!stepResult.actionable()) {
                return pauseForRecoverableFailure(stepResult.message());
            }
            target = stepResult.targetPos();
        }
        BaritoneFacade.CommandResult movementRequest = baritoneFacade.goNear(target, TARGET_APPROACH_RANGE);
        if (!movementRequest.success()) {
            return pauseForRecoverableFailure("Failed to return to build area near "
                    + target.toShortString() + ": " + movementRequest.message());
        }

        debugToChatAndFile("Returning to build area near " + target.toShortString() + ".");
        activeMovementTarget = target;
        activeMovementPurpose = MovementPurpose.BUILD;
        movementPaused = false;
        return AssistedStepResult.moving("Materials refilled. Returning to build near "
                + activeMovementTarget.toShortString() + ".");
    }

    private Map<Identifier, Integer> computeRequiredMaterialsForRefillWindow() {
        if (session == null) {
            return Map.of();
        }

        BuildPlan plan = session.getPlan();
        int startIndex = session.getCurrentPlacementIndex();
        if (startIndex < 0 || startIndex >= plan.placements().size()) {
            return Map.of();
        }

        Map<Identifier, Integer> required = new LinkedHashMap<>();
        int endIndex = computeRefillLookaheadEndIndex(startIndex, plan.placements().size());
        for (int i = startIndex; i < endIndex; i++) {
            Placement placement = plan.placements().get(i);
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
        for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            Identifier id = Registries.ITEM.getId(stack.getItem());
            inventory.merge(id, stack.getCount(), Integer::sum);
        }
        return inventory;
    }

    private Map<Identifier, Integer> computeRemainingDeficits(ClientPlayerEntity player, Map<Identifier, Integer> targetDeficits) {
        Map<Identifier, Integer> inventory = countInventoryMaterials(player);
        Map<Identifier, Integer> remaining = new LinkedHashMap<>();
        targetDeficits.forEach((id, count) -> {
            int deficit = count - inventory.getOrDefault(id, 0);
            if (deficit > 0) {
                remaining.put(id, deficit);
            }
        });
        return remaining;
    }

    static int computeRefillLookaheadEndIndex(int startIndex, int totalPlacements) {
        if (startIndex < 0 || totalPlacements < 0 || startIndex >= totalPlacements) {
            return Math.max(0, Math.min(startIndex, totalPlacements));
        }
        return Math.min(totalPlacements, startIndex + MAX_REFILL_LOOKAHEAD_ITEMS);
    }

    private boolean isUsableContainer(ClientWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        BlockEntity blockEntity = world.getBlockEntity(pos);
        return blockEntity instanceof net.minecraft.inventory.Inventory;
    }

    private AssistedStepResult advanceToNextSupplyOrPause(MinecraftClient client, RefillStatus refillStatus, String reason) {
        closeHandledScreen(client);
        resetRefillInteractionState();

        List<SupplyPoint> supplyPoints = refillStatus.supplyPoints() == null ? List.of() : refillStatus.supplyPoints();
        for (int nextIndex = refillStatus.supplyIndex() + 1; nextIndex < supplyPoints.size(); nextIndex++) {
            SupplyPoint nextSupply = supplyPoints.get(nextIndex);
            if (!isUsableContainer(client.world, nextSupply.pos())) {
                continue;
            }

            Map<Identifier, Integer> remaining = computeRemainingDeficits(client.player, refillStatus.missingMaterials());
            session.setRefillStatus(new RefillStatus(
                    nextSupply,
                    supplyPoints,
                    nextIndex,
                    remaining,
                    false,
                    refillStatus.returnTarget()
            ));
            progressStore.saveProgress(session);
            return beginRefillMovement(new RefillCheck(
                    session.getPlan().regions().get(session.getCurrentRegionIndex()),
                    remaining,
                    supplyPoints,
                    nextIndex
            ));
        }

        return failRefill(reason + " No registered supply container in this dimension can provide the remaining materials.");
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
                    RefillStatus currentRefill = session.getRefillStatus();
                    session.setRefillStatus(new RefillStatus(
                            currentRefill.supplyPoint(),
                            currentRefill.supplyPoints(),
                            currentRefill.supplyIndex(),
                            currentRefill.missingMaterials(),
                            true,
                            currentRefill.returnTarget()
                    ));
                    progressStore.saveProgress(session);
                }
                if (session.getState() == BuildPlanState.NEED_REFILL) {
                    transitionSession(BuildPlanState.REFILLING, "Cannot switch to REFILLING.");
                }
                debugToChatAndFile("Arrived at supply point " + reachedTarget.toShortString() + ".");
                return AssistedStepResult.arrived("Arrived at supply point " + reachedTarget.toShortString() + ". Ready to refill.");
            }

            if (session.getState() == BuildPlanState.RETURNING) {
                transitionSession(BuildPlanState.BUILDING, "Cannot switch to BUILDING.");
            }

            debugToChatAndFile("Reached build target area near " + reachedTarget.toShortString() + ".");
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
        closeHandledScreen(MinecraftClient.getInstance());
        resetRefillInteractionState();
        debugToChatAndFile("Build paused after recoverable failure: " + message);
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

    private int countPlayerItem(ClientPlayerEntity player, Item item) {
        int total = 0;
        for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private AssistedStepResult failRefill(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        closeHandledScreen(client);
        resetRefillInteractionState();
        Map<Identifier, Integer> remaining = session == null || session.getRefillStatus() == null
                ? Map.of()
                : computeRemainingDeficits(client.player, session.getRefillStatus().missingMaterials());
        if (session != null && session.getRefillStatus() != null) {
            RefillStatus current = session.getRefillStatus();
            session.setRefillStatus(new RefillStatus(
                    current.supplyPoint(),
                    current.supplyPoints(),
                    current.supplyIndex(),
                    remaining,
                    true,
                    current.returnTarget()
            ));
            progressStore.saveProgress(session);
        }
        return pauseForRecoverableFailure(message);
    }

    private HandledScreen<?> currentSupplyScreen(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> handledScreen)) {
            return null;
        }
        return handledScreen;
    }

    private void resetRefillInteractionState() {
        awaitingSupplyScreen = false;
        supplyScreenWaitPollsRemaining = 0;
        refillActionCooldown = 0;
    }

    private void closeHandledScreen(MinecraftClient client) {
        if (client == null || client.player == null) {
            return;
        }
        if (client.currentScreen instanceof HandledScreen<?>) {
            client.player.closeHandledScreen();
        }
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

    private BlockPos determineReturnTarget() {
        if (session == null) {
            return null;
        }

        Optional<NextTarget> nextTarget = resolveNextTarget(session);
        if (nextTarget.isPresent()) {
            return nextTarget.get().absolutePos().toImmutable();
        }

        int previousPlacementIndex = session.getCurrentPlacementIndex() - 1;
        if (previousPlacementIndex < 0 || previousPlacementIndex >= session.getPlan().placements().size()) {
            return null;
        }

        Placement previousPlacement = session.getPlan().placements().get(previousPlacementIndex);
        return placementResolver.resolveAbsolute(session, previousPlacement)
                .map(BlockPos::toImmutable)
                .orElse(null);
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


    private void debugToChatAndFile(String message) {
        if (MapArtRuntime.debugReporter() != null) {
            MapArtRuntime.debugReporter().logToChatAndFile(message);
        }
    }

    private void debugToFile(String message) {
        if (MapArtRuntime.debugReporter() != null) {
            MapArtRuntime.debugReporter().logToFile(message);
        }
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

    private record RefillCheck(Region region, Map<Identifier, Integer> missingMaterials, List<SupplyPoint> supplyPoints, int supplyIndex) {
        SupplyPoint supplyPoint() {
            return supplyPoints == null || supplyIndex < 0 || supplyIndex >= supplyPoints.size() ? null : supplyPoints.get(supplyIndex);
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
