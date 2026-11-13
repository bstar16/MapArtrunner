package com.mapartrunner.fabric.runtime;

import com.mapartrunner.core.refill.RefillPlan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Collection quantities from prequalified facts; native freshness remains the producer's responsibility. */
public final class FabricInventory {
    private FabricInventory() { }

    public static Map<RefillPlan.Material, Long> additionalNeeded(RefillPlan plan, RefillPlan.InventorySnapshot inventory) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(inventory, "inventory");
        // Cap demand so earlier materials cannot consume shared held stock beyond their frozen objectives.
        var cappedDemand = new LinkedHashMap<RefillPlan.Material, RefillPlan.MaterialDemand>();
        for (var demand : plan.demand()) {
            cappedDemand.computeIfAbsent(demand.material(), material -> new RefillPlan.MaterialDemand(
                    demand.targetId(), material, plan.targetInventoryTotals().get(material)));
        }
        var heldOnly = new RefillPlan.InventorySnapshot(inventory.revision(), inventory.held(), List.of());
        var heldCredits = RefillPlan.allocate(List.copyOf(cappedDemand.values()), heldOnly).targetInventoryTotals();
        var additional = new LinkedHashMap<>(plan.targetInventoryTotals());
        additional.replaceAll((material, total) -> Math.max(0L, total - heldCredits.get(material)));
        return Collections.unmodifiableMap(additional);
    }
}
