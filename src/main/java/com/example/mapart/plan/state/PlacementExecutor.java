package com.example.mapart.plan.state;

import com.example.mapart.plan.Placement;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

public class PlacementExecutor {
    private static final int HOTBAR_SIZE = 9;
    private static final int PLAYER_MAIN_START = 9;
    private static final int PLAYER_MAIN_END = 35;
    private static final double MAX_REACH_SQUARED = 36.0D;

    public PlacementResult execute(MinecraftClient client, Placement placement, BlockPos targetPos) {
        if (client == null || client.player == null || client.world == null) {
            return PlacementResult.unrecoverable("Client context is unavailable.");
        }
        if (placement == null || targetPos == null) {
            return PlacementResult.unrecoverable("Placement target is unresolved.");
        }

        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        ClientPlayerInteractionManager interactionManager = client.interactionManager;
        if (interactionManager == null) {
            return PlacementResult.recoverable("Interaction manager is unavailable.");
        }
        if (!world.isPosLoaded(targetPos)) {
            return PlacementResult.recoverable("Target chunk is not loaded at " + targetPos.toShortString() + ".");
        }

        BlockState currentState = world.getBlockState(targetPos);
        if (currentState.isOf(placement.block())) {
            return PlacementResult.alreadyCorrect();
        }
        if (!currentState.isReplaceable()) {
            return PlacementResult.recoverable("Target " + targetPos.toShortString() + " is occupied by "
                    + Registries.BLOCK.getId(currentState.getBlock()) + ".");
        }

        Item expectedItem = placement.block().asItem();
        if (!(expectedItem instanceof BlockItem)) {
            return PlacementResult.unrecoverable("Expected block " + Registries.BLOCK.getId(placement.block())
                    + " has no placeable item representation.");
        }

        SlotSelection slotSelection = selectItem(player, interactionManager, expectedItem);
        if (!slotSelection.success()) {
            return PlacementResult.needsRefill("Missing required item " + Registries.ITEM.getId(expectedItem) + " for "
                    + targetPos.toShortString() + ".");
        }

        Optional<ResolvedPlacementTarget> resolvedTarget = resolvePlacementTarget(player, world, targetPos);
        if (resolvedTarget.isEmpty()) {
            return PlacementResult.recoverable("No valid neighbor face was found for " + targetPos.toShortString() + ".");
        }

        ResolvedPlacementTarget interactionTarget = resolvedTarget.get();
        if (player.getEyePos().squaredDistanceTo(interactionTarget.hitPos()) > MAX_REACH_SQUARED) {
            return PlacementResult.recoverable("Target " + targetPos.toShortString() + " is out of placement reach.");
        }

        ActionResult result = interactionManager.interactBlock(player, Hand.MAIN_HAND, interactionTarget.hitResult());
        if (!result.isAccepted()) {
            return PlacementResult.recoverable("Placement interaction failed at " + targetPos.toShortString() + ".");
        }

        player.swingHand(Hand.MAIN_HAND);
        interactionManager.cancelBlockBreaking();

        BlockState afterState = world.getBlockState(targetPos);
        if (!afterState.isOf(placement.block())) {
            return PlacementResult.recoverable("Placement at " + targetPos.toShortString() + " did not match expected block "
                    + Registries.BLOCK.getId(placement.block()) + ".");
        }

        return PlacementResult.placed(slotSelection.selectedHotbarSlot(), targetPos);
    }

    private SlotSelection selectItem(ClientPlayerEntity player, ClientPlayerInteractionManager interactionManager, Item item) {
        PlayerInventory inventory = player.getInventory();
        if (inventory.getMainHandStack().isOf(item)) {
            return SlotSelection.success(inventory.selectedSlot);
        }

        for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
            if (inventory.getStack(slot).isOf(item)) {
                inventory.selectedSlot = slot;
                return SlotSelection.success(slot);
            }
        }

        for (int slot = PLAYER_MAIN_START; slot <= PLAYER_MAIN_END; slot++) {
            if (!inventory.getStack(slot).isOf(item)) {
                continue;
            }
            interactionManager.clickSlot(player.playerScreenHandler.syncId, slot, inventory.selectedSlot, SlotActionType.SWAP, player);
            if (inventory.getMainHandStack().isOf(item)) {
                return SlotSelection.success(inventory.selectedSlot);
            }
            return SlotSelection.failure();
        }

        return SlotSelection.failure();
    }

    private Optional<ResolvedPlacementTarget> resolvePlacementTarget(ClientPlayerEntity player, ClientWorld world, BlockPos targetPos) {
        Vec3d targetCenter = Vec3d.ofCenter(targetPos);
        Direction bestDirection = null;
        double bestDistance = Double.MAX_VALUE;

        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = targetPos.offset(direction);
            if (!world.isPosLoaded(neighborPos)) {
                continue;
            }

            BlockState neighborState = world.getBlockState(neighborPos);
            if (neighborState.isAir() || neighborState.isReplaceable()) {
                continue;
            }

            Vec3d hitPos = targetCenter.add(Vec3d.of(direction.getVector()).multiply(0.5D - 1.0E-3D));
            double distance = player.getEyePos().squaredDistanceTo(hitPos);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestDirection = direction;
            }
        }

        if (bestDirection == null) {
            return Optional.empty();
        }

        Direction interactionFace = bestDirection.getOpposite();
        Vec3d hitPos = targetCenter.add(Vec3d.of(bestDirection.getVector()).multiply(0.5D - 1.0E-3D));
        return Optional.of(new ResolvedPlacementTarget(
                new BlockHitResult(hitPos, interactionFace, targetPos.offset(bestDirection), false),
                hitPos
        ));
    }

    public record PlacementResult(Status status, String message, BlockPos targetPos, int selectedHotbarSlot) {
        enum Status {
            PLACED,
            ALREADY_CORRECT,
            NEEDS_REFILL,
            RECOVERABLE_FAILURE,
            UNRECOVERABLE_FAILURE
        }

        static PlacementResult placed(int hotbarSlot, BlockPos targetPos) {
            return new PlacementResult(Status.PLACED, "", targetPos, hotbarSlot);
        }

        static PlacementResult alreadyCorrect() {
            return new PlacementResult(Status.ALREADY_CORRECT, "", null, -1);
        }

        static PlacementResult needsRefill(String message) {
            return new PlacementResult(Status.NEEDS_REFILL, message, null, -1);
        }

        static PlacementResult recoverable(String message) {
            return new PlacementResult(Status.RECOVERABLE_FAILURE, message, null, -1);
        }

        static PlacementResult unrecoverable(String message) {
            return new PlacementResult(Status.UNRECOVERABLE_FAILURE, message, null, -1);
        }

        public boolean placedBlock() {
            return status == Status.PLACED;
        }

        public boolean alreadyCorrectBlock() {
            return status == Status.ALREADY_CORRECT;
        }

        public boolean needsRefill() {
            return status == Status.NEEDS_REFILL;
        }

        public boolean unrecoverableFailure() {
            return status == Status.UNRECOVERABLE_FAILURE;
        }
    }

    private record SlotSelection(boolean success, int selectedHotbarSlot) {
        static SlotSelection success(int selectedHotbarSlot) {
            return new SlotSelection(true, selectedHotbarSlot);
        }

        static SlotSelection failure() {
            return new SlotSelection(false, -1);
        }
    }

    private record ResolvedPlacementTarget(BlockHitResult hitResult, Vec3d hitPos) {
    }
}
