package com.example.mapart.plan.state;

import com.example.mapart.plan.Placement;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
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
    private static final int PLACE_RANGE = 4;
    private static final Direction[] FACE_PRIORITY = new Direction[]{
            Direction.DOWN,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST,
            Direction.UP
    };

    public PlacementResult execute(MinecraftClient client, BuildSession session, Placement placement, BlockPos targetPos) {
        if (client == null || client.player == null || client.world == null || client.interactionManager == null) {
            return PlacementResult.error("Client context is unavailable for placement.");
        }
        if (session == null || placement == null || targetPos == null) {
            return PlacementResult.error("Placement target is not available.");
        }
        if (session.getOrigin() == null) {
            return PlacementResult.error("Origin is not set.");
        }

        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;

        if (!world.isPosLoaded(targetPos)) {
            return PlacementResult.retry("Target chunk is not loaded at " + targetPos.toShortString() + ".");
        }

        BlockState currentState = world.getBlockState(targetPos);
        if (currentState.isOf(placement.block())) {
            return PlacementResult.alreadyCorrect("Target already matches expected block at " + targetPos.toShortString() + ".");
        }
        if (!currentState.isReplaceable()) {
            return PlacementResult.retry("Target is occupied by " + Registries.BLOCK.getId(currentState.getBlock())
                    + " at " + targetPos.toShortString() + ".");
        }

        if (!isWithinPlaceRange(player.getBlockPos(), targetPos)) {
            return PlacementResult.moveRequired("Target is outside placement range at " + targetPos.toShortString() + ".");
        }

        Item expectedItem = placement.block().asItem();
        if (!(expectedItem instanceof BlockItem)) {
            return PlacementResult.error("Expected block " + Registries.BLOCK.getId(placement.block()) + " does not have a placeable block item.");
        }

        InventorySelection selection = ensureSelectedItem(client, player, expectedItem);
        if (!selection.available()) {
            return PlacementResult.missingItem("Missing required item " + Registries.ITEM.getId(expectedItem) + " for "
                    + Registries.BLOCK.getId(placement.block()) + ".");
        }

        Optional<BlockHitResult> hitResult = resolvePlacementHit(world, targetPos);
        if (hitResult.isEmpty()) {
            return PlacementResult.retry("No valid neighbor face is available to place at " + targetPos.toShortString() + ".");
        }

        ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult.get());
        if (result.isAccepted()) {
            player.swingHand(Hand.MAIN_HAND);
        }
        if (!result.isAccepted()) {
            return PlacementResult.retry("Placement interaction was not accepted for " + targetPos.toShortString() + ".");
        }

        BlockState placedState = world.getBlockState(targetPos);
        if (placedState.isOf(placement.block())) {
            return PlacementResult.placed("Placed " + Registries.BLOCK.getId(placement.block()) + " at " + targetPos.toShortString() + ".");
        }

        return PlacementResult.retry("Placement interaction succeeded but the world still shows "
                + Registries.BLOCK.getId(placedState.getBlock()) + " at " + targetPos.toShortString() + ".");
    }

    private InventorySelection ensureSelectedItem(MinecraftClient client, ClientPlayerEntity player, Item expectedItem) {
        PlayerInventory inventory = player.getInventory();
        if (player.getMainHandStack().isOf(expectedItem)) {
            return InventorySelection.selected(inventory.selectedSlot, false);
        }

        for (int hotbarSlot = 0; hotbarSlot < PlayerInventory.getHotbarSize(); hotbarSlot++) {
            if (inventory.getStack(hotbarSlot).isOf(expectedItem)) {
                inventory.selectedSlot = hotbarSlot;
                return InventorySelection.selected(hotbarSlot, false);
            }
        }

        int swapHotbarSlot = inventory.selectedSlot;
        for (int slot = PlayerInventory.getHotbarSize(); slot < PlayerInventory.MAIN_SIZE; slot++) {
            if (!inventory.getStack(slot).isOf(expectedItem)) {
                continue;
            }
            client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slot, swapHotbarSlot, SlotActionType.SWAP, player);
            if (inventory.getStack(swapHotbarSlot).isOf(expectedItem)) {
                inventory.selectedSlot = swapHotbarSlot;
                return InventorySelection.selected(swapHotbarSlot, true);
            }
        }

        return InventorySelection.missing();
    }

    private Optional<BlockHitResult> resolvePlacementHit(ClientWorld world, BlockPos targetPos) {
        for (Direction face : FACE_PRIORITY) {
            BlockPos neighborPos = targetPos.offset(face);
            if (!world.isPosLoaded(neighborPos)) {
                continue;
            }

            BlockState neighborState = world.getBlockState(neighborPos);
            if (neighborState.isReplaceable()) {
                continue;
            }

            Direction interactionSide = face.getOpposite();
            Vec3d hitPos = Vec3d.ofCenter(targetPos).add(
                    interactionSide.getOffsetX() * 0.5,
                    interactionSide.getOffsetY() * 0.5,
                    interactionSide.getOffsetZ() * 0.5
            );
            return Optional.of(new BlockHitResult(hitPos, interactionSide, neighborPos, false));
        }
        return Optional.empty();
    }

    private boolean isWithinPlaceRange(BlockPos playerPos, BlockPos targetPos) {
        return Math.abs(playerPos.getX() - targetPos.getX()) <= PLACE_RANGE
                && Math.abs(playerPos.getY() - targetPos.getY()) <= PLACE_RANGE
                && Math.abs(playerPos.getZ() - targetPos.getZ()) <= PLACE_RANGE;
    }

    private record InventorySelection(boolean available, int selectedSlot, boolean movedToHotbar) {
        static InventorySelection selected(int selectedSlot, boolean movedToHotbar) {
            return new InventorySelection(true, selectedSlot, movedToHotbar);
        }

        static InventorySelection missing() {
            return new InventorySelection(false, -1, false);
        }
    }
}
