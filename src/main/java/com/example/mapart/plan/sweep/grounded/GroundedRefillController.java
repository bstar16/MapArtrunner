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
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
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
import java.util.Set;
import java.util.HashSet;

public final class GroundedRefillController {
    // Constants
    static final int NAV_TIMEOUT_TICKS = 400;
    private static final int CONTAINER_REACH_FLAT = 4;
    private static final int RETURN_REACH_FLAT = 4;
    private static final int CONTAINER_OPEN_WAIT_POLLS = 5;
    private static final int ACTION_COOLDOWN_TICKS = 4;

    public enum RefillState {
        IDLE, NAVIGATING, OPENING_CONTAINER, REFILLING, RETURNING, DONE, FAILED
    }

    public enum TickResult {
        ACTIVE, DONE, FAILED
    }

    private RefillState state = RefillState.IDLE;
    private List<SupplyPoint> supplyCandidates = List.of();
    private int supplyCandidateIndex = 0;
    private SupplyPoint targetSupply;
    private Map<Identifier, Integer> deficits = Map.of();
    private BlockPos returnTarget;
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

    public boolean initiate(MinecraftClient client, SupplyStore supplyStore, Map<Identifier, Integer> deficits, BaritoneFacade baritone) {
        return initiate(client, supplyStore, deficits, null, baritone);
    }

    public boolean initiate(MinecraftClient client, SupplyStore supplyStore, Map<Identifier, Integer> deficits, BlockPos returnTarget, BaritoneFacade baritone) {
        if (client == null || client.player == null || client.world == null) {
            fail("Cannot initiate refill: client context unavailable.");
            return false;
        }
        String dimensionKey = client.world.getRegistryKey().getValue().toString();
        BlockPos playerPos = client.player.getBlockPos();
        List<SupplyPoint> supplies = supplyStore != null
                ? supplyStore.listInDimensionByDistance(dimensionKey, playerPos)
                : List.of();
        return initiateWithSupplies(supplies, deficits, returnTarget, baritone);
    }

    private boolean initiateWithSupplies(List<SupplyPoint> supplies, Map<Identifier, Integer> deficits, BaritoneFacade baritone) {
        return initiateWithSupplies(supplies, deficits, null, baritone);
    }

    private boolean initiateWithSupplies(List<SupplyPoint> supplies, Map<Identifier, Integer> deficits, BlockPos returnTarget, BaritoneFacade baritone) {
        if (supplies.isEmpty()) {
            fail("No supply container registered in this dimension. Cannot restock.");
            return false;
        }
        this.supplyCandidates = List.copyOf(supplies);
        this.supplyCandidateIndex = 0;
        this.deficits = deficits != null ? new LinkedHashMap<>(deficits) : Map.of();
        this.returnTarget = returnTarget;
        return navigateToSupply(supplyCandidates.get(0), baritone);
    }

    private boolean navigateToSupply(SupplyPoint supply, BaritoneFacade baritone) {
        this.targetSupply = supply;
        this.navTicksRemaining = NAV_TIMEOUT_TICKS;
        this.awaitingContainerScreen = false;
        this.actionCooldown = 0;
        this.state = RefillState.NAVIGATING;
        if (baritone != null) {
            BaritoneFacade.CommandResult result = baritone.goNear(targetSupply.pos(), CONTAINER_REACH_FLAT);
            if (!result.success()) {
                fail("Failed to start supply navigation near " + targetSupply.pos().toShortString() + ": " + result.message());
                return false;
            }
        }
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
    void initiateWithSuppliesForTests(List<SupplyPoint> supplies, List<Item> items, BaritoneFacade baritone) {
        Map<Identifier, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            Identifier id = (item != null) ? Registries.ITEM.getId(item) : Identifier.of("mapart", "_slot_" + i);
            map.merge(id, 1, Integer::sum);
        }
        initiateWithSupplies(supplies, map, null, baritone);
    }

    void initiateWithSuppliesForTests(List<SupplyPoint> supplies, Map<Identifier, Integer> deficits, BaritoneFacade baritone) {
        initiateWithSupplies(supplies, deficits, null, baritone);
    }

    void initiateWithSuppliesForTests(List<SupplyPoint> supplies, Map<Identifier, Integer> deficits, BlockPos returnTarget, BaritoneFacade baritone) {
        initiateWithSupplies(supplies, deficits, returnTarget, baritone);
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
            case RETURNING -> tickReturning(client, baritone);
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
        if (client.player == null) {
            closeScreen(client);
            fail("Refill lost player context.");
            return TickResult.FAILED;
        }
        if (actionCooldown > 0) {
            actionCooldown--;
            return TickResult.ACTIVE;
        }

        Map<Identifier, Integer> remaining = computeRemainingDeficits(client.player, deficits);
        if (remaining.isEmpty()) {
            closeScreen(client);
            if (returnTarget != null && baritone != null) {
                baritone.goNear(returnTarget, CONTAINER_REACH_FLAT);
                state = RefillState.RETURNING;
                return TickResult.ACTIVE;
            }
            state = RefillState.DONE;
            return TickResult.DONE;
        }

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

        boolean anyUseful = false;
        for (Map.Entry<Identifier, Integer> missing : remaining.entrySet()) {
            Identifier needed = missing.getKey();
            for (int slotIndex = 0; slotIndex < containerSlotCount; slotIndex++) {
                ItemStack stack = handler.slots.get(slotIndex).getStack();
                if (!stack.isEmpty() && needed.equals(Registries.ITEM.getId(stack.getItem()))) {
                    client.interactionManager.clickSlot(handler.syncId, slotIndex, 0, SlotActionType.QUICK_MOVE, client.player);
                    actionCooldown = ACTION_COOLDOWN_TICKS;
                    anyUseful = true;
                    return TickResult.ACTIVE;
                }
            }
        }
        closeScreen(client);
        if (supplyCandidateIndex < supplyCandidates.size() - 1) {
            return tryNextSupply("Supply #" + targetSupply.id() + " did not have all needed items", baritone);
        }

        if (baritone != null) {
            baritone.cancel();
        }
        if (!anyUseful) {
            fail("Required items not found in any registered supply container.");
            return TickResult.FAILED;
        }
        if (returnTarget != null) {
            if (baritone == null) {
                fail("Failed to start return-to-build navigation near " + returnTarget.toShortString() + ": baritone unavailable.");
                return TickResult.FAILED;
            }
            BaritoneFacade.CommandResult result = baritone.goNear(returnTarget, RETURN_REACH_FLAT);
            if (!result.success()) {
                fail("Failed to start return-to-build navigation near " + returnTarget.toShortString() + ": " + result.message());
                return TickResult.FAILED;
            }
            state = RefillState.RETURNING;
            return TickResult.ACTIVE;
        }
        state = RefillState.DONE;
        return TickResult.DONE;
    }

    private TickResult tickReturning(MinecraftClient client, BaritoneFacade baritone) {
        if (returnTarget == null) {
            state = RefillState.DONE;
            return TickResult.DONE;
        }
        if (client == null || client.player == null) {
            return TickResult.ACTIVE;
        }
        BlockPos playerPos = client.player.getBlockPos();
        double dx = playerPos.getX() - returnTarget.getX();
        double dz = playerPos.getZ() - returnTarget.getZ();
        if (dx * dx + dz * dz <= (double) RETURN_REACH_FLAT * RETURN_REACH_FLAT) {
            if (baritone != null) {
                baritone.cancel();
            }
            state = RefillState.DONE;
            return TickResult.DONE;
        }
        return TickResult.ACTIVE;
    }

    // Package-private test helper: simulates the refilling logic using index-based sets to avoid
    // requiring live Item instances (which need the game registry). playerHeldIndices and
    // containerIndices refer to positions in the deficits list.
    TickResult simulateRefillingForTests(Set<Integer> playerHeldIndices, Set<Integer> containerIndices, BaritoneFacade baritone) {
        boolean anyUseful = false;
        for (int i = 0; i < deficits.size(); i++) {
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
        if (returnTarget != null) {
            if (baritone == null) {
                fail("Failed to start return-to-build navigation near " + returnTarget.toShortString() + ": baritone unavailable.");
                return TickResult.FAILED;
            }
            BaritoneFacade.CommandResult result = baritone.goNear(returnTarget, RETURN_REACH_FLAT);
            if (!result.success()) {
                fail("Failed to start return-to-build navigation near " + returnTarget.toShortString() + ": " + result.message());
                return TickResult.FAILED;
            }
            state = RefillState.RETURNING;
            return TickResult.ACTIVE;
        }
        state = RefillState.DONE;
        return TickResult.DONE;
    }

    // Package-private test helper: simulates refilling when deficits are given as Map<Identifier,Integer>.
    // Primarily used to test return navigation success/failure.
    TickResult simulateRefillingForTests(Map<Integer, Integer> playerHeld, Map<Integer, Integer> containerPulled, Set<Integer> containerIndices, BaritoneFacade baritone) {
        if (deficits.isEmpty()) {
            if (returnTarget != null) {
                if (baritone == null) {
                    fail("Failed to start return-to-build navigation near " + returnTarget.toShortString() + ": baritone unavailable.");
                    return TickResult.FAILED;
                }
                BaritoneFacade.CommandResult result = baritone.goNear(returnTarget, RETURN_REACH_FLAT);
                if (!result.success()) {
                    fail("Failed to start return-to-build navigation near " + returnTarget.toShortString() + ": " + result.message());
                    return TickResult.FAILED;
                }
                state = RefillState.RETURNING;
                return TickResult.ACTIVE;
            }
            state = RefillState.DONE;
            return TickResult.DONE;
        }
        int i = 0;
        boolean anyUseful = false;
        for (Identifier id : deficits.keySet()) {
            Integer held = playerHeld.get(i);
            if (held != null && held > 0) {
                anyUseful = true;
                i++;
                continue;
            }
            if (containerIndices.contains(i)) {
                playerHeld.put(i, 1);
                anyUseful = true;
            }
            i++;
        }
        if (!anyUseful) {
            fail("Required items not found in supply container.");
            return TickResult.FAILED;
        }
        if (returnTarget != null) {
            if (baritone == null) {
                fail("Failed to start return-to-build navigation near " + returnTarget.toShortString() + ": baritone unavailable.");
                return TickResult.FAILED;
            }
            BaritoneFacade.CommandResult result = baritone.goNear(returnTarget, RETURN_REACH_FLAT);
            if (!result.success()) {
                fail("Failed to start return-to-build navigation near " + returnTarget.toShortString() + ": " + result.message());
                return TickResult.FAILED;
            }
            state = RefillState.RETURNING;
            return TickResult.ACTIVE;
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
        deficits = Map.of();
        returnTarget = null;
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

    static Set<net.minecraft.item.Item> itemsInInventory(ClientPlayerEntity player) {
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

    private static Map<Identifier, Integer> computeRemainingDeficits(ClientPlayerEntity player, Map<Identifier, Integer> targetDeficits) {
        Map<Identifier, Integer> inventory = new LinkedHashMap<>();
        for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (!stack.isEmpty()) {
                inventory.merge(Registries.ITEM.getId(stack.getItem()), stack.getCount(), Integer::sum);
            }
        }
        Map<Identifier, Integer> remaining = new LinkedHashMap<>();
        targetDeficits.forEach((id, count) -> {
            int deficit = count - inventory.getOrDefault(id, 0);
            if (deficit > 0) {
                remaining.put(id, deficit);
            }
        });
        return remaining;
    }
}
