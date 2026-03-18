package com.example.mapart.plan.state;

import com.example.mapart.supply.SupplyPoint;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;

public record RefillStatus(
        SupplyPoint supplyPoint,
        List<SupplyPoint> supplyPoints,
        int supplyIndex,
        Map<Identifier, Integer> missingMaterials,
        boolean arrivedAtSupply,
        BlockPos returnTarget
) {
}
