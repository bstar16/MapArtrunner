package com.example.mapart.plan.state;

import com.example.mapart.MapArtMod;
import com.example.mapart.inventory.HotbarSlotReservations;
import com.example.mapart.plan.Placement;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
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
        return execute(client, session, placement, targetPos, 0);
    }

    public PlacementResult execute(MinecraftClient client, BuildSession session, Placement placement, BlockPos targetPos, int reservedHotbarSlots) {
        HotbarSlotReservations.validateReservedHotbarSlots(reservedHotbarSlots);
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

        InventorySelection selection = ensureSelectedItem(client, player, expectedItem, reservedHotbarSlots);
        if (!selection.available()) {
            return PlacementResult.missingItem("Missing required item " + Registries.ITEM.getId(expectedItem) + " for "
                    + Registries.BLOCK.getId(placement.block()) + ".");
        }
        if (shouldDelayPlacementAfterSelection(selection)) {
            MapArtMod.LOGGER.debug(
                    "PLACEMENT_HOTBAR_SWAP_PENDING worldPos={} expectedItem={} expectedBlock={} sourceInventorySlot={} targetHotbarSlot={} movedToHotbar=true",
                    targetPos.toShortString(),
                    Registries.ITEM.getId(expectedItem),
                    Registries.BLOCK.getId(placement.block()),
                    selection.sourceInventorySlot(),
                    selection.selectedSlot()
            );
            return PlacementResult.hotbarSwapPending("Hotbar swap pending for " + Registries.ITEM.getId(expectedItem)
                    + " at " + targetPos.toShortString()
                    + " sourceInventorySlot=" + selection.sourceInventorySlot()
                    + " targetHotbarSlot=" + selection.selectedSlot() + ".");
        }

        Optional<BlockHitResult> hitResult = resolvePlacementHit(world, targetPos);
        if (hitResult.isEmpty()) {
            return PlacementResult.retry("No valid neighbor face is available to place at " + targetPos.toShortString() + ".");
        }

        MapArtMod.LOGGER.debug(
                "Placement trace at {}: expectedItem={}, selectedSlot={}, mainHandItem={}, movedToHotbar={}",
                targetPos.toShortString(),
                Registries.ITEM.getId(expectedItem),
                selection.selectedSlot(),
                Registries.ITEM.getId(player.getMainHandStack().getItem()),
                selection.movedToHotbar()
        );

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

        return PlacementResult.acceptedPendingVerification("Placement interaction succeeded but the world still shows "
                + Registries.BLOCK.getId(placedState.getBlock()) + " at " + targetPos.toShortString() + ".");
    }

    public PlacementResult executeUtilityBlock(MinecraftClient client, BlockPos targetPos, Item expectedItem, Block expectedBlock, int reservedHotbarSlots) {
        HotbarSlotReservations.validateReservedHotbarSlots(reservedHotbarSlots);
        if (client == null || client.player == null || client.world == null || client.interactionManager == null) {
            return PlacementResult.error("Client context is unavailable for utility placement.");
        }
        if (targetPos == null || expectedItem == null || expectedBlock == null) {
            return PlacementResult.error("Utility placement target is not available.");
        }

        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (!world.isPosLoaded(targetPos) || !world.isPosLoaded(targetPos.down())) {
            return PlacementResult.retry("Utility target chunk is not loaded at " + targetPos.toShortString() + ".");
        }

        BlockState currentState = world.getBlockState(targetPos);
        if (currentState.isOf(expectedBlock)) {
            return PlacementResult.alreadyCorrect("Utility target already matches expected block at " + targetPos.toShortString() + ".");
        }
        if (!currentState.isReplaceable()) {
            return PlacementResult.retry("Utility target is occupied by " + Registries.BLOCK.getId(currentState.getBlock())
                    + " at " + targetPos.toShortString() + ".");
        }
        if (!isWithinPlaceRange(player.getBlockPos(), targetPos)) {
            return PlacementResult.moveRequired("Utility target is outside placement range at " + targetPos.toShortString() + ".");
        }
        if (!(expectedItem instanceof BlockItem)) {
            return PlacementResult.error("Expected utility item " + Registries.ITEM.getId(expectedItem) + " is not a placeable block item.");
        }

        InventorySelection selection = ensureSelectedItem(client, player, expectedItem, reservedHotbarSlots);
        if (!selection.available()) {
            return PlacementResult.missingItem("Missing required utility item " + Registries.ITEM.getId(expectedItem) + ".");
        }
        if (shouldDelayPlacementAfterSelection(selection)) {
            MapArtMod.LOGGER.debug(
                    "PLACEMENT_HOTBAR_SWAP_PENDING path=torch grid worldPos={} expectedItem={} expectedBlock={} sourceInventorySlot={} targetHotbarSlot={} movedToHotbar=true",
                    targetPos.toShortString(),
                    Registries.ITEM.getId(expectedItem),
                    Registries.BLOCK.getId(expectedBlock),
                    selection.sourceInventorySlot(),
                    selection.selectedSlot()
            );
            return PlacementResult.hotbarSwapPending("Hotbar swap pending for utility item " + Registries.ITEM.getId(expectedItem)
                    + " at " + targetPos.toShortString()
                    + " sourceInventorySlot=" + selection.sourceInventorySlot()
                    + " targetHotbarSlot=" + selection.selectedSlot() + ".");
        }

        BlockPos supportPos = targetPos.down();
        BlockState supportState = world.getBlockState(supportPos);
        if (supportState.isReplaceable()) {
            return PlacementResult.retry("Utility target support is not ready at " + supportPos.toShortString() + ".");
        }
        BlockHitResult hitResult = new BlockHitResult(
                Vec3d.ofCenter(supportPos).add(0.0, 0.5, 0.0),
                Direction.UP,
                supportPos,
                false
        );
        ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);
        if (result.isAccepted()) {
            player.swingHand(Hand.MAIN_HAND);
        } else {
            return PlacementResult.retry("Utility placement interaction was not accepted for " + targetPos.toShortString() + ".");
        }

        BlockState placedState = world.getBlockState(targetPos);
        if (placedState.isOf(expectedBlock)) {
            return PlacementResult.placed("Placed utility block " + Registries.BLOCK.getId(expectedBlock) + " at " + targetPos.toShortString() + ".");
        }
        return PlacementResult.acceptedPendingVerification("Utility placement interaction succeeded but the world still shows "
                + Registries.BLOCK.getId(placedState.getBlock()) + " at " + targetPos.toShortString() + ".");
    }

    private InventorySelection ensureSelectedItem(MinecraftClient client, ClientPlayerEntity player, Item expectedItem, int reservedHotbarSlots) {
        PlayerInventory inventory = player.getInventory();
        int selectedSlot = inventory.getSelectedSlot();
        if (player.getMainHandStack().isOf(expectedItem)
                && HotbarSlotReservations.isAutomatedHotbarSlot(selectedSlot, reservedHotbarSlots)) {
            return InventorySelection.selected(inventory.getSelectedSlot(), false, -1);
        }

        for (int hotbarSlot = reservedHotbarSlots; hotbarSlot < PlayerInventory.getHotbarSize(); hotbarSlot++) {
            if (inventory.getStack(hotbarSlot).isOf(expectedItem)) {
                inventory.setSelectedSlot(hotbarSlot);
                syncSelectedSlotWithServer(player, hotbarSlot);
                return InventorySelection.selected(hotbarSlot, false, -1);
            }
        }

        int swapHotbarSlot = firstPreferredSwapHotbarSlot(inventory, reservedHotbarSlots);
        if (swapHotbarSlot < 0) {
            return InventorySelection.missing();
        }
        for (int slot = PlayerInventory.getHotbarSize(); slot < PlayerInventory.MAIN_SIZE; slot++) {
            if (!inventory.getStack(slot).isOf(expectedItem)) {
                continue;
            }
            client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slot, swapHotbarSlot, SlotActionType.SWAP, player);
            if (inventory.getStack(swapHotbarSlot).isOf(expectedItem)) {
                inventory.setSelectedSlot(swapHotbarSlot);
                syncSelectedSlotWithServer(player, swapHotbarSlot);
                return InventorySelection.selected(swapHotbarSlot, true, slot);
            }
        }

        return InventorySelection.missing();
    }

    static boolean shouldDelayPlacementAfterAutomatedHotbarSwapForTests(boolean selectionAvailable, boolean movedToHotbar) {
        return shouldDelayPlacementAfterSelection(new InventorySelection(selectionAvailable, 0, movedToHotbar, -1));
    }

    private static boolean shouldDelayPlacementAfterSelection(InventorySelection selection) {
        return selection.available() && selection.movedToHotbar();
    }

    static int firstPreferredSwapHotbarSlotForTests(int selectedSlot, boolean[] emptySlots, int reservedHotbarSlots) {
        HotbarSlotReservations.validateReservedHotbarSlots(reservedHotbarSlots);
        if (HotbarSlotReservations.isAutomatedHotbarSlot(selectedSlot, reservedHotbarSlots)
                && selectedSlot >= 0
                && selectedSlot < emptySlots.length
                && emptySlots[selectedSlot]) {
            return selectedSlot;
        }
        for (int hotbarSlot = reservedHotbarSlots; hotbarSlot < Math.min(PlayerInventory.getHotbarSize(), emptySlots.length); hotbarSlot++) {
            if (emptySlots[hotbarSlot]) {
                return hotbarSlot;
            }
        }
        return HotbarSlotReservations.firstAutomatedHotbarSlot(reservedHotbarSlots).orElse(-1);
    }

    private int firstPreferredSwapHotbarSlot(PlayerInventory inventory, int reservedHotbarSlots) {
        int selectedSlot = inventory.getSelectedSlot();
        if (HotbarSlotReservations.isAutomatedHotbarSlot(selectedSlot, reservedHotbarSlots)
                && inventory.getStack(selectedSlot).isEmpty()) {
            return selectedSlot;
        }
        for (int hotbarSlot = reservedHotbarSlots; hotbarSlot < PlayerInventory.getHotbarSize(); hotbarSlot++) {
            if (inventory.getStack(hotbarSlot).isEmpty()) {
                return hotbarSlot;
            }
        }
        return HotbarSlotReservations.firstAutomatedHotbarSlot(reservedHotbarSlots).orElse(-1);
    }

    private void syncSelectedSlotWithServer(ClientPlayerEntity player, int selectedSlot) {
        if (selectedSlot < 0 || selectedSlot >= PlayerInventory.getHotbarSize() || player.networkHandler == null) {
            return;
        }
        player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(selectedSlot));
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
            // hitPos must be on the clicked face of the support block, not on the far side of targetPos.
            // center(neighborPos) + interactionSide*0.5 lands exactly on that face.
            Vec3d hitPos = Vec3d.ofCenter(neighborPos).add(
                    interactionSide.getOffsetX() * 0.5,
                    interactionSide.getOffsetY() * 0.5,
                    interactionSide.getOffsetZ() * 0.5
            );
            MapArtMod.LOGGER.debug(
                    "BlockHitResult: targetPos={} supportPos={} side={} hitVec={} support.offset(side)==target:{}",
                    targetPos.toShortString(), neighborPos.toShortString(), interactionSide,
                    hitPos, neighborPos.offset(interactionSide).equals(targetPos)
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

    private record InventorySelection(boolean available, int selectedSlot, boolean movedToHotbar, int sourceInventorySlot) {
        static InventorySelection selected(int selectedSlot, boolean movedToHotbar, int sourceInventorySlot) {
            return new InventorySelection(true, selectedSlot, movedToHotbar, sourceInventorySlot);
        }

        static InventorySelection missing() {
            return new InventorySelection(false, -1, false, -1);
        }
    }
}
