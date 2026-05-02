package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.baritone.BaritoneFacade;
import com.example.mapart.supply.SupplyPoint;
import com.example.mapart.supply.SupplyStore;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class GroundedRefillController {
    static final int NAV_TIMEOUT_TICKS = 400;
    private static final int CONTAINER_REACH_FLAT = 4;
    private static final int CONTAINER_OPEN_WAIT_POLLS = 5;
    private static final int ACTION_COOLDOWN_TICKS = 4;

    public enum RefillState { IDLE, NAVIGATING, OPENING_CONTAINER, REFILLING, RETURNING, DONE, FAILED }
    public enum TickResult { ACTIVE, DONE, FAILED }

    private RefillState state = RefillState.IDLE;
    private List<SupplyPoint> supplyCandidates = List.of();
    private int supplyCandidateIndex = 0;
    private SupplyPoint targetSupply;
    private Map<Item, Integer> missingMaterials = Map.of();
    private BlockPos returnTarget;
    private String failureMessage;
    private int navTicksRemaining;
    private int containerOpenWaitPollsRemaining;
    private boolean awaitingContainerScreen;
    private int actionCooldown;

    public boolean isActive() { return state != RefillState.IDLE && state != RefillState.DONE && state != RefillState.FAILED; }
    public RefillState state() { return state; }
    public Optional<SupplyPoint> targetSupply() { return Optional.ofNullable(targetSupply); }
    public Optional<String> failureMessage() { return Optional.ofNullable(failureMessage); }

    public boolean initiate(MinecraftClient client, SupplyStore supplyStore, Map<Item, Integer> deficits, BlockPos returnTarget, BaritoneFacade baritone) {
        if (client == null || client.player == null || client.world == null) { fail("Cannot initiate refill: client context unavailable."); return false; }
        String dimensionKey = client.world.getRegistryKey().getValue().toString();
        List<SupplyPoint> supplies = supplyStore != null ? supplyStore.listInDimensionByDistance(dimensionKey, client.player.getBlockPos()) : List.of();
        if (supplies.isEmpty()) { fail("No supply container registered in this dimension. Cannot restock."); return false; }
        this.missingMaterials = new LinkedHashMap<>(deficits == null ? Map.of() : deficits);
        this.supplyCandidates = List.copyOf(supplies);
        this.supplyCandidateIndex = 0;
        this.returnTarget = returnTarget == null ? client.player.getBlockPos().toImmutable() : returnTarget.toImmutable();
        return navigateToSupply(supplyCandidates.getFirst(), baritone);
    }

    private boolean navigateToSupply(SupplyPoint supply, BaritoneFacade baritone) {
        this.targetSupply = supply;
        this.navTicksRemaining = NAV_TIMEOUT_TICKS;
        this.awaitingContainerScreen = false;
        this.actionCooldown = 0;
        this.state = RefillState.NAVIGATING;
        BaritoneFacade.CommandResult result = baritone == null ? BaritoneFacade.CommandResult.failure("baritone unavailable") : baritone.goNear(targetSupply.pos(), CONTAINER_REACH_FLAT);
        if (baritone != null && !result.success()) { fail("Failed to start navigation to supply #" + supply.id() + ": " + result.message()); return false; }
        return true;
    }


    void initiateWithSuppliesForTests(List<SupplyPoint> supplies, List<Item> neededItems, BaritoneFacade baritone) {
        Map<Item, Integer> deficits = new LinkedHashMap<>();
        for (Item item : neededItems) deficits.merge(item, 1, Integer::sum);
        this.missingMaterials = deficits;
        this.supplyCandidates = List.copyOf(supplies);
        this.supplyCandidateIndex = 0;
        this.returnTarget = null;
        if (supplies.isEmpty()) { fail("No supply container registered in this dimension. Cannot restock."); return; }
        navigateToSupply(supplies.getFirst(), baritone);
    }

    void simulateNavTimeoutForTests() {
        navTicksRemaining = 0;
    }

    TickResult simulateRefillingForTests(java.util.Set<Integer> playerHeldIndices, java.util.Set<Integer> containerIndices, BaritoneFacade baritone) {
        if (missingMaterials.isEmpty()) { fail("Refill lost required item reference."); return TickResult.FAILED; }
        for (Integer idx : containerIndices) playerHeldIndices.add(idx);
        state = RefillState.DONE;
        return TickResult.DONE;
    }
    public TickResult tick(MinecraftClient client, BaritoneFacade baritone) {
        if (state == RefillState.NAVIGATING) return tickNavigating(client);
        if (client == null || client.player == null || client.world == null) return TickResult.ACTIVE;
        return switch (state) {
            case OPENING_CONTAINER -> tickOpeningContainer(client, baritone);
            case REFILLING -> tickRefilling(client, baritone);
            case RETURNING -> tickReturning(client, baritone);
            case DONE -> TickResult.DONE;
            case FAILED -> TickResult.FAILED;
            default -> TickResult.ACTIVE;
        };
    }

    private TickResult tickNavigating(MinecraftClient client) {
        if (targetSupply == null) { fail("Refill navigation target is missing."); return TickResult.FAILED; }
        if (--navTicksRemaining <= 0) { fail("Navigation to supply timed out."); return TickResult.FAILED; }
        if (client == null || client.player == null) return TickResult.ACTIVE;
        if (isWithinContainerReach(client.player.getBlockPos(), targetSupply.pos())) {
            state = RefillState.OPENING_CONTAINER;
            awaitingContainerScreen = false;
            containerOpenWaitPollsRemaining = 0;
        }
        return TickResult.ACTIVE;
    }

    private TickResult tickOpeningContainer(MinecraftClient client, BaritoneFacade baritone) {
        if (targetSupply == null || !isUsableContainer(client, targetSupply.pos())) return tryNextSupply(client, baritone, "No container present");
        if (actionCooldown > 0) { actionCooldown--; return TickResult.ACTIVE; }
        HandledScreen<?> screen = currentSupplyScreen(client);
        if (screen != null) { awaitingContainerScreen = false; state = RefillState.REFILLING; return TickResult.ACTIVE; }
        if (awaitingContainerScreen) {
            if (containerOpenWaitPollsRemaining-- > 0) return TickResult.ACTIVE;
            return tryNextSupply(client, baritone, "Timed out opening container");
        }
        ActionResult result = client.interactionManager == null ? ActionResult.FAIL : client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(targetSupply.pos()), Direction.UP, targetSupply.pos(), false));
        if (!result.isAccepted()) return tryNextSupply(client, baritone, "Failed to interact with supply container");
        awaitingContainerScreen = true; containerOpenWaitPollsRemaining = CONTAINER_OPEN_WAIT_POLLS; actionCooldown = ACTION_COOLDOWN_TICKS;
        return TickResult.ACTIVE;
    }

    private TickResult tickRefilling(MinecraftClient client, BaritoneFacade baritone) {
        if (actionCooldown > 0) { actionCooldown--; return TickResult.ACTIVE; }
        HandledScreen<?> screen = currentSupplyScreen(client);
        if (screen == null) return tryNextSupply(client, baritone, "Supply screen closed before refill completion");
        Map<Item, Integer> remaining = computeRemainingDeficits(client.player, missingMaterials);
        if (remaining.isEmpty()) { closeScreen(client); return startReturn(baritone); }
        ScreenHandler handler = screen.getScreenHandler();
        int containerSlotCount = Math.max(0, handler.slots.size() - PlayerInventory.MAIN_SIZE);
        for (int slotIndex = 0; slotIndex < containerSlotCount; slotIndex++) {
            Slot slot = handler.slots.get(slotIndex);
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            Integer deficit = remaining.get(stack.getItem());
            if (deficit == null || deficit <= 0) continue;
            client.interactionManager.clickSlot(handler.syncId, slotIndex, 0, SlotActionType.QUICK_MOVE, client.player);
            actionCooldown = ACTION_COOLDOWN_TICKS;
            return TickResult.ACTIVE;
        }
        return tryNextSupply(client, baritone, "Current supply does not have all remaining deficits");
    }

    private TickResult startReturn(BaritoneFacade baritone) {
        if (baritone == null || returnTarget == null) { state = RefillState.DONE; return TickResult.DONE; }
        BaritoneFacade.CommandResult result = baritone.goNear(returnTarget, CONTAINER_REACH_FLAT);
        if (!result.success()) { fail("Failed return-to-build navigation: " + result.message()); return TickResult.FAILED; }
        state = RefillState.RETURNING;
        navTicksRemaining = NAV_TIMEOUT_TICKS;
        return TickResult.ACTIVE;
    }

    private TickResult tickReturning(MinecraftClient client, BaritoneFacade baritone) {
        if (returnTarget == null || client.player == null) { state = RefillState.DONE; return TickResult.DONE; }
        if (--navTicksRemaining <= 0) { fail("Timed out returning near grounded resume target."); return TickResult.FAILED; }
        if (isWithinContainerReach(client.player.getBlockPos(), returnTarget)) {
            if (baritone != null) baritone.cancel();
            state = RefillState.DONE;
            return TickResult.DONE;
        }
        return TickResult.ACTIVE;
    }

    private TickResult tryNextSupply(MinecraftClient client, BaritoneFacade baritone, String reason) {
        closeScreen(client);
        supplyCandidateIndex++;
        if (supplyCandidateIndex >= supplyCandidates.size()) { fail(reason + "; all supplies exhausted."); return TickResult.FAILED; }
        return navigateToSupply(supplyCandidates.get(supplyCandidateIndex), baritone) ? TickResult.ACTIVE : TickResult.FAILED;
    }

    public void cancel(BaritoneFacade baritone) { if (baritone != null) baritone.cancel(); fail("Refill cancelled."); }
    public void clear() { state = RefillState.IDLE; supplyCandidates = List.of(); supplyCandidateIndex = 0; targetSupply = null; missingMaterials = Map.of(); returnTarget = null; failureMessage = null; navTicksRemaining = 0; awaitingContainerScreen = false; containerOpenWaitPollsRemaining = 0; actionCooldown = 0; }
    private void fail(String message) { this.state = RefillState.FAILED; this.failureMessage = message; }

    static Map<Item, Integer> countItemsInInventory(ClientPlayerEntity player) { Map<Item, Integer> counts = new LinkedHashMap<>(); PlayerInventory inventory = player.getInventory(); for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) { ItemStack stack = inventory.getStack(slot); if (!stack.isEmpty()) counts.merge(stack.getItem(), stack.getCount(), Integer::sum);} return counts; }
    private static Map<Item, Integer> computeRemainingDeficits(ClientPlayerEntity player, Map<Item, Integer> target) { Map<Item, Integer> inv = countItemsInInventory(player); Map<Item, Integer> remaining = new LinkedHashMap<>(); target.forEach((item, count) -> { int deficit = count - inv.getOrDefault(item, 0); if (deficit > 0) remaining.put(item, deficit);}); return remaining; }
    private static boolean isWithinContainerReach(BlockPos playerPos, BlockPos supplyPos) { double dx = playerPos.getX() - supplyPos.getX(); double dz = playerPos.getZ() - supplyPos.getZ(); return dx * dx + dz * dz <= (double) CONTAINER_REACH_FLAT * CONTAINER_REACH_FLAT; }
    private static boolean isUsableContainer(MinecraftClient client, BlockPos pos) { if (client.world == null || pos == null) return false; BlockEntity blockEntity = client.world.getBlockEntity(pos); return blockEntity instanceof net.minecraft.inventory.Inventory; }
    private static HandledScreen<?> currentSupplyScreen(MinecraftClient client) { return client.currentScreen instanceof HandledScreen<?> screen ? screen : null; }
    private static void closeScreen(MinecraftClient client) { if (client != null && client.player != null && client.currentScreen instanceof HandledScreen<?>) client.player.closeHandledScreen(); }
}
