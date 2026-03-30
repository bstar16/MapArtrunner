package com.example.mapart.plan.sweep.air;

import net.minecraft.item.Item;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

public record AirPlacementRequest(
        BlockPos targetPos,
        Item requiredItem,
        double maxPlaceDistance,
        Hand hand,
        boolean swingOnSuccess
) {
    public static AirPlacementRequest mainHand(BlockPos targetPos, Item requiredItem, double maxPlaceDistance) {
        return new AirPlacementRequest(targetPos, requiredItem, maxPlaceDistance, Hand.MAIN_HAND, true);
    }
}
