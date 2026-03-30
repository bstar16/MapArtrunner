package com.example.mapart.plan.sweep.air;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;
import java.util.Optional;

public final class AirPlacementEngine {
    private static final Direction[] FACE_PRIORITY = new Direction[]{
            Direction.DOWN,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST,
            Direction.UP
    };

    public AirPlacementOutcome place(MinecraftClient client, AirPlacementRequest request) {
        AirPlacementOutcome requestValidation = validateRequest(request);
        if (requestValidation != null) {
            return requestValidation;
        }

        if (client == null || client.player == null || client.world == null || client.interactionManager == null) {
            return AirPlacementOutcome.FAILED_INTERACTION;
        }

        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        BlockPos targetPos = request.targetPos();

        if (!world.isPosLoaded(targetPos)) {
            return AirPlacementOutcome.INVALID_TARGET;
        }

        AirPlacementOutcome heldItemOutcome = classifyHeldItemCompatibility(
                isHeldItemCompatible(player.getMainHandStack(), request.requiredItem())
        );
        if (heldItemOutcome != null) {
            return heldItemOutcome;
        }

        if (!isWithinRange(player.getEyePos(), targetPos, request.maxPlaceDistance())) {
            return AirPlacementOutcome.OUT_OF_RANGE;
        }

        BlockState targetState = world.getBlockState(targetPos);
        if (!targetState.isReplaceable()) {
            return AirPlacementOutcome.BLOCKED;
        }

        Optional<BlockHitResult> syntheticHit = buildSyntheticHit(world, targetPos);
        if (syntheticHit.isEmpty()) {
            return AirPlacementOutcome.BLOCKED;
        }

        ActionResult interactionResult = client.interactionManager.interactBlock(player, request.hand(), syntheticHit.get());
        AirPlacementOutcome mapped = mapInteractionResult(interactionResult);
        if (mapped == AirPlacementOutcome.SUCCESS && request.swingOnSuccess()) {
            player.swingHand(request.hand());
        }
        return mapped;
    }

    static AirPlacementOutcome validateRequest(AirPlacementRequest request) {
        if (request == null
                || request.targetPos() == null
                || request.requiredItem() == null
                || request.hand() == null
                || Double.isNaN(request.maxPlaceDistance())
                || request.maxPlaceDistance() <= 0.0) {
            return AirPlacementOutcome.INVALID_TARGET;
        }
        return null;
    }

    static AirPlacementOutcome classifyHeldItemCompatibility(boolean heldItemCompatible) {
        return heldItemCompatible ? null : AirPlacementOutcome.WRONG_ITEM;
    }

    static boolean isHeldItemCompatible(ItemStack heldStack, Item requiredItem) {
        return heldStack != null && requiredItem != null && heldStack.isOf(requiredItem);
    }

    static boolean isWithinRange(Vec3d playerEyePos, BlockPos targetPos, double maxPlaceDistance) {
        if (playerEyePos == null || targetPos == null || maxPlaceDistance <= 0.0 || Double.isNaN(maxPlaceDistance)) {
            return false;
        }
        Vec3d center = Vec3d.ofCenter(targetPos);
        return playerEyePos.squaredDistanceTo(center) <= maxPlaceDistance * maxPlaceDistance;
    }

    static AirPlacementOutcome mapInteractionResult(ActionResult result) {
        return result != null && result.isAccepted()
                ? AirPlacementOutcome.SUCCESS
                : AirPlacementOutcome.FAILED_INTERACTION;
    }

    static Optional<BlockHitResult> buildSyntheticHit(ClientWorld world, BlockPos targetPos) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(targetPos, "targetPos");

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
}
