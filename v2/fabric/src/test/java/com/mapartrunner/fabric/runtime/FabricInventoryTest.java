package com.mapartrunner.fabric.runtime;

import com.mapartrunner.core.model.BuildPlan.BlockSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mapartrunner.core.refill.RefillPlan.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricInventoryTest {
    private static final Material A = material("stone");
    private static final Material B = material("dirt");

    @Test
    void subtractsUsableHeldOnceWithoutChangingAbsoluteTargetOrReservations() {
        var inventory = facts(List.of(held(0, 40, false, A), held(1, 7, true, A)),
                List.of(slot(9, 64, A)));
        var demand = List.of(new MaterialDemand(73, A, 60));
        var plan = allocate(demand, inventory);
        var reservations = List.copyOf(plan.reservations());
        var snapshot = new InventorySnapshot(inventory.revision(), inventory.held(), inventory.slots());

        assertEquals(Map.of(A, 20L), FabricInventory.additionalNeeded(plan, inventory));
        assertEquals(Map.of(A, 60L), plan.targetInventoryTotals());
        assertEquals(demand, plan.demand());
        assertEquals(20, reservations.getFirst().amount());
        assertEquals(reservations, plan.reservations());
        assertTrue(plan.unmet().isEmpty());
        assertEquals(snapshot, inventory);
    }

    @Test
    void zeroExactAndExcessHeldStockKeepNonnegativeQuotas() {
        var plan = allocate(List.of(new MaterialDemand(73, A, 60)), facts(List.of(), List.of(slot(9, 64, A))));
        assertEquals(Map.of(A, 60L), FabricInventory.additionalNeeded(plan, facts(List.of(), List.of())));
        assertEquals(Map.of(A, 0L), FabricInventory.additionalNeeded(plan, facts(List.of(held(0, 60, false, A)), List.of())));
        assertEquals(Map.of(A, 0L), FabricInventory.additionalNeeded(plan, facts(List.of(held(0, 80, false, A)), List.of())));
        assertEquals(Map.of(A, 60L), plan.targetInventoryTotals());
    }

    @Test
    void emptyPlansReturnEmptyImmutableResults() {
        var plan = allocate(List.of(), facts(List.of(), List.of()));
        var result = FabricInventory.additionalNeeded(plan, facts(List.of(held(0, 5, false, A)), List.of(slot(9, 64, A))));
        assertTrue(result.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.put(A, 1L));
    }

    @Test
    void zeroDemandMaterialsRemainPresentInOrder() {
        var plan = allocate(List.of(new MaterialDemand(73, B, 0), new MaterialDemand(21, A, 0)), facts(List.of(), List.of()));
        var result = FabricInventory.additionalNeeded(plan, facts(List.of(held(0, 64, false, A, B)), List.of()));
        assertEquals(Map.of(B, 0L, A, 0L), result);
        assertEquals(List.of(B, A), new ArrayList<>(result.keySet()));
    }

    @Test
    void repeatedMaterialsKeepFirstOccurrenceOrderAndSharePhysicalHeldOnlyOnce() {
        var demand = List.of(new MaterialDemand(73, B, 3), new MaterialDemand(21, A, 5), new MaterialDemand(90, B, 2));
        var plan = allocate(demand, facts(List.of(), List.of(slot(9, 16, B), slot(10, 16, A))));
        var result = FabricInventory.additionalNeeded(plan, facts(List.of(held(0, 7, false, A, B)), List.of()));
        assertEquals(Map.of(B, 0L, A, 3L), result);
        assertEquals(List.of(B, A), new ArrayList<>(result.keySet()));
        assertEquals(demand, plan.demand());
        assertEquals(Map.of(B, 5L, A, 5L), plan.targetInventoryTotals());
    }

    @Test
    void capsHeldCreditAtFrozenObjectivesBeforeAllocatingSharedStock() {
        var demand = List.of(new MaterialDemand(73, A, 96), new MaterialDemand(21, B, 32));
        var plan = allocate(demand, facts(List.of(), List.of(slot(9, 32, A), slot(10, 32, B))));
        var reservations = List.copyOf(plan.reservations());
        var unmet = List.of(new Unmet(A, 64, Reason.CAPACITY_LIMITED));
        assertEquals(Map.of(A, 32L, B, 32L), plan.targetInventoryTotals());
        assertEquals(unmet, plan.unmet());

        assertEquals(Map.of(A, 0L, B, 0L), FabricInventory.additionalNeeded(plan,
                facts(List.of(held(0, 64, false, A, B)), List.of())));
        assertEquals(demand, plan.demand());
        assertEquals(Map.of(A, 32L, B, 32L), plan.targetInventoryTotals());
        assertEquals(reservations, plan.reservations());
        assertEquals(unmet, plan.unmet());
    }

    @Test
    void keepsCapacityLimitedMaterialAtZeroWithoutDroppingItsUnmetWork() {
        var inventory = facts(List.of(held(0, 32, false, A)), List.of(slot(9, 64, A, B)));
        var plan = allocate(List.of(new MaterialDemand(73, A, 64), new MaterialDemand(21, B, 64)), inventory);
        var result = FabricInventory.additionalNeeded(plan, inventory);
        assertEquals(Map.of(A, 32L, B, 0L), result);
        assertEquals(List.of(A, B), new ArrayList<>(result.keySet()));
        assertEquals(Map.of(A, 64L, B, 0L), plan.targetInventoryTotals());
        assertEquals(List.of(new Unmet(B, 64, Reason.CAPACITY_LIMITED)), plan.unmet());
        assertEquals(32, plan.reservations().getFirst().amount());
    }

    @Test
    void freshDestinationCapacityIsNeverCreditedAsHeldStock() {
        var plan = allocate(List.of(new MaterialDemand(73, A, 60)), facts(List.of(), List.of(slot(9, 64, A))));
        assertEquals(Map.of(A, 60L), FabricInventory.additionalNeeded(plan, facts(List.of(), List.of(slot(10, 64, A)))));
        assertEquals(Map.of(A, 60L), FabricInventory.additionalNeeded(plan, facts(List.of(), List.of())));
    }

    @Test
    void reservedAndIneligibleHeldPoolsCannotReduceQuota() {
        var plan = allocate(List.of(new MaterialDemand(73, A, 20)), facts(List.of(), List.of(slot(9, 64, A))));
        var fresh = facts(List.of(held(0, 7, true, A), held(1, 40, false, B),
                held(2, 11, false), held(3, 5, false, A)), List.of());
        assertEquals(Map.of(A, 15L), FabricInventory.additionalNeeded(plan, fresh));
    }

    @Test
    void consumesOpaqueNativeEligibilityAndMax16ObjectiveWithoutRequalifyingItems() {
        var stateVariant = new Material(new BlockSpec("minecraft:stone", Map.of("axis", "x")), A.itemId());
        var empty = new Slot(9, Area.MAIN, true, false, "slot-r1", List.of(
                new Eligibility(A, "opaque A components", "native A insertion", 16, 16),
                new Eligibility(B, "opaque B components", "native B insertion", 16, 16)));
        var incompatiblePartial = new Slot(10, Area.MAIN, false, false, "slot-r1", List.of());
        var plan = allocate(List.of(new MaterialDemand(73, A, 16), new MaterialDemand(21, B, 16)),
                facts(List.of(held(10, 8, false, A)), List.of(incompatiblePartial, empty)));
        var fresh = facts(List.of(held(10, 8, false, A), held(11, 16, false, stateVariant)), List.of(empty));
        assertEquals(Map.of(A, 8L, B, 0L), FabricInventory.additionalNeeded(plan, fresh));
        assertEquals(Map.of(A, 16L, B, 0L), plan.targetInventoryTotals());
        assertEquals("opaque A components", plan.reservations().getFirst().componentIdentity());
        assertEquals(16, plan.reservations().getFirst().capacity());
    }

    @Test
    void changedHeldFactsRecomputeQuotaEvenWithSameRevision() {
        var plan = allocate(List.of(new MaterialDemand(73, A, 60)), facts(List.of(), List.of(slot(9, 64, A))));
        var first = FabricInventory.additionalNeeded(plan, facts(List.of(held(0, 40, false, A)), List.of()));
        assertEquals(Map.of(A, 20L), first);
        assertEquals(Map.of(A, 50L), FabricInventory.additionalNeeded(plan, facts(List.of(held(0, 10, false, A)), List.of())));
        assertEquals(Map.of(A, 0L), FabricInventory.additionalNeeded(plan, facts(List.of(held(0, 60, false, A)), List.of())));
        assertEquals(Map.of(A, 20L), first);
    }

    @Test
    void resultIsAnImmutableCopyIncludingZeroEntries() {
        var plan = allocate(List.of(new MaterialDemand(73, B, 0), new MaterialDemand(21, A, 20)),
                facts(List.of(), List.of(slot(9, 64, A))));
        var result = FabricInventory.additionalNeeded(plan, facts(List.of(), List.of()));
        assertNotSame(plan.targetInventoryTotals(), result);
        assertEquals(List.of(B, A), new ArrayList<>(result.keySet()));
        assertEquals(Map.of(B, 0L, A, 20L), result);
        assertThrows(UnsupportedOperationException.class, () -> result.put(A, 1L));
        assertThrows(UnsupportedOperationException.class, () -> result.remove(B));
        assertThrows(UnsupportedOperationException.class, () -> result.entrySet().iterator().next().setValue(1L));
    }

    @Test
    void rejectsNullPlanAndInventory() {
        var inventory = facts(List.of(), List.of());
        var plan = allocate(List.of(), inventory);
        assertThrows(NullPointerException.class, () -> FabricInventory.additionalNeeded(null, inventory));
        assertThrows(NullPointerException.class, () -> FabricInventory.additionalNeeded(plan, null));
    }

    private static Material material(String name) {
        return new Material(new BlockSpec("minecraft:" + name, Map.of()), "minecraft:" + name);
    }

    private static Held held(int slot, int amount, boolean reserved, Material... materials) {
        return new Held(slot, amount, reserved, Set.of(materials));
    }

    private static Slot slot(int slot, int capacity, Material... materials) {
        return new Slot(slot, Area.MAIN, true, false, "slot-r1", java.util.Arrays.stream(materials)
                .map(material -> new Eligibility(material, "opaque components", "native insertion", capacity, Math.max(1, capacity))).toList());
    }

    private static InventorySnapshot facts(List<Held> held, List<Slot> slots) {
        return new InventorySnapshot("inventory-r1", held, slots);
    }
}
