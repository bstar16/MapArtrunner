package com.example.mapart.plan.state;

import com.example.mapart.supply.SupplyPoint;
import net.minecraft.util.Identifier;

import java.util.Map;

public record RefillStatus(SupplyPoint supplyPoint, Map<Identifier, Integer> missingMaterials, boolean arrivedAtSupply) {
}
