package com.example.mapart.player;

import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

public record ManualAirPlacePlan(
        ManualAirPlaceState state,
        BlockPos targetPos,
        BlockHitResult hitResult
) {
    public boolean valid() {
        return state == ManualAirPlaceState.VALID && targetPos != null && hitResult != null;
    }

    public Optional<BlockHitResult> hitResultOptional() {
        return Optional.ofNullable(hitResult);
    }
}
