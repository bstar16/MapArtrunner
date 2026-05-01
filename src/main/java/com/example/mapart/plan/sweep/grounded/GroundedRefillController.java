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
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class GroundedRefillController {
    // Constants
    static final int NAV_TIMEOUT_TICKS = 400;
    private static final int CONTAINER_REACH_FLAT = 4;
    private static final int CONTAINER_OPEN_WAIT_POLLS = 5;
    private static final int ACTION_COOLDOWN_TICKS = 4;

    public enum RefillState {
        IDLE, NAVIGATING, OPENING_CONTAINER, REFILLING, DONE, FAILED
    }

    public enum TickResult {
        ACTIVE, DONE, FAILED
    }

    private RefillState state = RefillState.IDLE;
    private List<SupplyPoint> supplyCandidates = List.of();
    private int supplyCandidateIndex = 0;
    private SupplyPoint targetSupply;
    private List<Item> neededItems;
    private String failureMessage;
    private int navTicksRemaining;
    private int containerOpenWaitPollsRemaining;
    private boolean awaitingContainerScreen;
    private int actionCooldown;

    public boolean isActive() {
        return state != RefillState.IDLE && state != RefillState.DONE && state != RefillState.FAILED;
    }

    public RefillState state() { return state; }
    public Optional<SupplyPoint> targetSupply() { return Optional.ofNullable(targetSupply); }
    public Optional<String> failureMessage() { return Optional.ofNullable(failureMessage); }

    public boolean initiate(MinecraftClient client, SupplyStore supplyStore, List<Item> neededItems, BaritoneFacade baritone) {
        if (client == null || client.player == null || client.world == null) {
            fail("Cannot initiate refill: client context unavailable.");
            return false;
        }
        String dimensionKey = client.world.getRegistryKey().getValue().toString();
        BlockPos playerPos = client.player.getBlockPos();
        List<SupplyPoint> supplies = supplyStore != null
                ? supplyStore.listInDimensionByDistance(dimensionKey, playerPos)
                : List.of();
        return initiateWithSupplies(supplies, neededItems, baritone);
    }

    private boolean initiateWithSupplies(List<SupplyPoint> supplies, List<Item> neededItems, BaritoneFacade baritone) {
        if (supplies.isEmpty()) {
            fail("No supply container registered in this dimension. Cannot restock.");
            return false;
        }
        this.supplyCandidates = List.copyOf(supplies);
        this.supplyCandidateIndex = 0;
        this.neededItems = neededItems != null ? neededItems : List.of();
        return navigateToSupply(supplyCandidates.get(0), baritone);
    }

    private boolean navigateToSupply(SupplyPoint supply, BaritoneFacade baritone) {
        this.targetSupply = supply;
        this.navTicksRemaining = NAV_TIMEOUT_TICKS;
        this.awaitingContainerScreen = false;
        this.actionCooldown = 0;
        this.state = RefillState.NAVIGATING;
        baritone.goTo(targetSupply.pos());
        return true;
    }

    private TickResult tryNextSupply(String skipReason, BaritoneFacade baritone) {
        supplyCandidateIndex++;
        if (supplyCandidateIndex >= supplyCandidates.size()) {
            fail("Required items not found in any registered supply container.");
            return TickResult.FAILED;
        }
        navigateToSupply(supplyCandidates.get(supplyCandidateIndex), baritone);
        return TickResult.ACTIVE;
    }

    // Package-private test helpers
    void initiateWithSuppliesForTests(List<SupplyPoint> supplies, List<Item> neededItems, BaritoneFacade baritone) {
        initiateWithSupplies(supplies, neededItems, baritone);
    }

    void simulateNavTimeoutForTests() {
        navTicksRemaining = 0;
    }

    public TickResult tick(MinecraftClient client, BaritoneFacade baritone) {
        if (state == RefillState.NAVIGATING) {
            return tickNavigating(client, baritone);
        }
        if (client == null || client.player == null || client.world == null) {
            return TickResult.ACTIVE;
        }
        return switch (state) {
            case OPENING_CONTAINER -> tickOpeningContainer(client, baritone);
            case REFILLING -> tickRefilling(client, baritone);
            case DONE -> TickResult.DONE;
            case FAILED -> TickResult.FAILED;
            case IDLE, NAVIGATING -> TickResult.ACTIVE;
        };
    }

    private TickResult tickNavigating(MinecraftClient client, BaritoneFacade baritone) {
        if (targetSupply == null) {
            fail("Refill navigation target is missing.");
            return TickResult.FAILED;
        }
        navTicksRemaining--;
        if (navTicksRemaining <= 0) {
            fail("Navigation to supply #" + targetSupply.id() + " at " + targetSupply.pos().toShortString() + " timed out.");
            return TickResult.FAILED;
        }
        if (client == null || client.player == null) {
            return TickResult.ACTIVE;
        }
        BlockPos playerPos = client.player.getBlockPos();
        if (isWithinContainerReach(playerPos, targetSupply.pos())) {
            if (baritone != null) {
                baritone.cancel();
            }
            state = RefillState.OPENING_CONTAINER;
            awaitingContainerScreen = false;
            containerOpenWaitPollsRemaining = 0;
            actionCooldown = 0;
        }
        return TickResult.ACTIVE;
    }

    private TickResult tickOpeningContainer(MinecraftClient client, BaritoneFacade baritone) {
        if (targetSupply == null || client.world == null) {
            fail("Supply target lost during container opening.");
            return TickResult.FAILED;
        }
        if (!isUsableContainer(client, targetSupply.pos())) {
            return tryNextSupply("No container at supply #" + targetSupply.id() + " (" + targetSupply.pos().toShortString() + ").", baritone);
        }
        if (actionCooldown > 0) {
            actionCooldown--;
            return TickResult.ACTIVE;
        }
        HandledScreen<?> screen = currentSupplyScreen(client);
        if (screen != null) {
            awaitingContainerScreen = false;
            containerOpenWaitPollsRemaining = 0;
            state = RefillState.REFILLING;
            return TickResult.ACTIVE;
        }
        if (awaitingContainerScreen) {
            if (containerOpenWaitPollsRemaining > 0) {
                containerOpenWaitPollsRemaining--;
                return TickResult.ACTIVE;
            }
            return tryNextSupply("Timed out waiting for supply container to open at " + targetSupply.pos().toShortString() + ".", baritone);
        }
        if (client.interactionManager == null) {
            fail("Interaction manager unavailable.");
            return TickResult.FAILED;
        }
        ActionResult result = client.interactionManager.interactBlock(
                client.player,
                Hand.MAIN_HAND,
                new BlockHitResult(
                        Vec3d.ofCenter(targetSupply.pos()),
                        Direction.UP,
                        targetSupply.pos(),
                        false
                )
        );
        if (!result.isAccepted()) {
            return tryNextSupply("Failed to open supply container at " + targetSupply.pos().toShortString() + ".", baritone);
        }
        awaitingContainerScreen = true;
        containerOpenWaitPollsRemaining = CONTAINER_OPEN_WAIT_POLLS;
        actionCooldown = ACTION_COOLDOWN_TICKS;
        return TickResult.ACTIVE;
    }

    private TickResult tickRefilling(MinecraftClient client, BaritoneFacade baritone) {
        if (neededItems == null || client.player == null) {
            closeScreen(client);
            fail("Refill lost required item reference.");
            return TickResult.FAILED;
        }
        if (actionCooldown > 0) {
            actionCooldown--;
            return TickResult.ACTIVE;
        }

        Set<Item> heldItems = itemsInInventory(client.player);

        HandledScreen<?> screen = currentSupplyScreen(client);
        if (screen == null) {
            fail("Supply container screen closed before refill was complete.");
            return TickResult.FAILED;
        }
        ScreenHandler handler = screen.getScreenHandler();
        int containerSlotCount = Math.max(0, handler.slots.size() - PlayerInventory.MAIN_SIZE);
        if (containerSlotCount <= 0) {
            closeScreen(client);
            fail("Opened screen is not a recognized supply container.");
            return TickResult.FAILED;
        }

        // Pull one stack per tick for each needed item not yet in inventory
        boolean anyUseful = false;
        for (Item needed : neededItems) {
            if (heldItems.contains(needed)) {
                anyUseful = true;
                continue;
            }
            for (int slotIndex = 0; slotIndex < containerSlotCount; slotIndex++) {
                ItemStack stack = handler.slots.get(slotIndex).getStack();
                if (!stack.isEmpty() && stack.isOf(needed)) {
                    client.interactionManager.clickSlot(handler.syncId, slotIndex, 0, SlotActionType.QUICK_MOVE, client.player);
                    actionCooldown = ACTION_COOLDOWN_TICKS;
                    return TickResult.ACTIVE;
                }
            }
            // This item is absent from the container — skip it
        }

        // All needed items are either held or absent from this container
        closeScreen(client);

        boolean allSatisfied = neededItems.stream().allMatch(heldItems::contains);
        if (!allSatisfied && supplyCandidateIndex < supplyCandidates.size() - 1) {
            return tryNextSupply("Supply #" + targetSupply.id() + " did not have all needed items", baritone);
        }

        baritone.cancel();
        if (!anyUseful) {
            fail("Required items not found in any registered supply container.");
            return TickResult.FAILED;
        }
        state = RefillState.DONE;
        return TickResult.DONE;
    }

    // Package-private test helper: simulates the refilling logic using index-based sets to avoid
    // requiring live Item instances (which need the game registry). playerHeldIndices and
    // containerIndices refer to positions in the neededItems list.
    TickResult simulateRefillingForTests(Set<Integer> playerHeldIndices, Set<Integer> containerIndices, BaritoneFacade baritone) {
        if (neededItems == null) {
            fail("Refill lost required item reference.");
            return TickResult.FAILED;
        }
        boolean anyUseful = false;
        for (int i = 0; i < neededItems.size(); i++) {
            if (playerHeldIndices.contains(i)) {
                anyUseful = true;
                continue;
            }
            if (containerIndices.contains(i)) {
                playerHeldIndices.add(i);
                anyUseful = true;
            }
            // not in container — skip
        }
        if (!anyUseful) {
            fail("Required items not found in supply container.");
            return TickResult.FAILED;
        }
        state = RefillState.DONE;
        return TickResult.DONE;
    }

    public void cancel(BaritoneFacade baritone) {
        if (baritone != null && state == RefillState.NAVIGATING) {
            baritone.cancel();
        }
        fail("Refill cancelled.");
    }

    public void clear() {
        state = RefillState.IDLE;
        supplyCandidates = List.of();
        supplyCandidateIndex = 0;
        targetSupply = null;
        neededItems = null;
        failureMessage = null;
        navTicksRemaining = 0;
        awaitingContainerScreen = false;
        containerOpenWaitPollsRemaining = 0;
        actionCooldown = 0;
    }

    private void fail(String message) {
        this.state = RefillState.FAILED;
        this.failureMessage = message;
    }

    private static boolean isWithinContainerReach(BlockPos playerPos, BlockPos supplyPos) {
        double dx = playerPos.getX() - supplyPos.getX();
        double dz = playerPos.getZ() - supplyPos.getZ();
        return dx * dx + dz * dz <= (double) CONTAINER_REACH_FLAT * CONTAINER_REACH_FLAT;
    }

    private static boolean isUsableContainer(MinecraftClient client, BlockPos pos) {
        if (client.world == null || pos == null) {
            return false;
        }
        BlockEntity blockEntity = client.world.getBlockEntity(pos);
        return blockEntity instanceof net.minecraft.inventory.Inventory;
    }

    private static HandledScreen<?> currentSupplyScreen(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> screen)) {
            return null;
        }
        return screen;
    }

    private static void closeScreen(MinecraftClient client) {
        if (client != null && client.player != null && client.currentScreen instanceof HandledScreen<?>) {
            client.player.closeHandledScreen();
        }
    }

    static Set<Item> itemsInInventory(ClientPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        Set<Item> held = new HashSet<>();
        for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty()) {
                held.add(stack.getItem());
            }
        }
        return held;
    }
}
