package com.mapartrunner.core.refill;

import com.mapartrunner.core.model.BuildPlan.BlockSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mapartrunner.core.refill.RefillPlan.*;
import static org.junit.jupiter.api.Assertions.*;

class RefillPlanTest {
    static final Material A = material("stone");
    static final Material B = material("dirt");

    @Test
    void absoluteTotalsExcludeReservedStockWithoutSubtractingUsableTwice() {
        var plan = allocate(List.of(demand(0, A, 60)), facts(
                List.of(held(0, 40, false, A), held(1, 7, true, A)),
                List.of(slot(9, Area.MAIN, true, false, 64, A))));
        assertEquals(Map.of(A, 60L), plan.targetInventoryTotals());
        assertEquals(20, plan.reservations().getFirst().amount());
        assertTrue(plan.unmet().isEmpty());
    }

    @Test
    void firstMaterialClaimsEmptySlotBeforeItsCompatiblePartialSlot() {
        var plan = allocate(List.of(demand(0, A, 64), demand(1, B, 64)), facts(
                List.of(held(0, 32, false, A)), List.of(
                        slot(10, Area.MAIN, false, false, 32, A),
                        slot(9, Area.MAIN, true, false, 64, A, B))));
        assertEquals(Map.of(A, 64L, B, 0L), plan.targetInventoryTotals());
        assertEquals(9, plan.reservations().getFirst().slot());
        assertEquals(32, plan.reservations().getFirst().amount());
        assertEquals(List.of(new Unmet(B, 64, Reason.CAPACITY_LIMITED)), plan.unmet());
    }

    @Test
    void physicalHeldPoolsAndSlotsAreSharedAcrossDifferentEligibleMaterials() {
        var plan = allocate(List.of(demand(1, A, 8), demand(2, B, 12)), facts(
                List.of(held(0, 10, false, A, B)),
                List.of(slot(9, Area.MAIN, true, false, 5, A, B))));
        assertEquals(Map.of(A, 8L, B, 7L), plan.targetInventoryTotals());
        assertEquals(List.of(new Unmet(B, 5, Reason.CAPACITY_LIMITED)), plan.unmet());
        assertEquals(B, plan.reservations().getFirst().material());
    }

    @Test
    void respectsAllFourDestinationPreferencesAndNativeMax16() {
        var plan = allocate(List.of(demand(0, A, 100)), facts(List.of(), List.of(
                slot(0, Area.HOTBAR, false, false, 3, A),
                slot(9, Area.MAIN, false, false, 4, A),
                slot(1, Area.HOTBAR, true, false, 16, A),
                slot(10, Area.MAIN, true, false, 16, A),
                slot(2, Area.HOTBAR, true, true, 64, A))));
        assertEquals(List.of(10, 1, 9, 0), plan.reservations().stream().map(DestinationReservation::slot).toList());
        assertEquals(39L, plan.targetInventoryTotals().get(A));
        assertEquals(61, plan.unmet().getFirst().amount());
    }

    @Test
    void nativeEligibilityKeepsComponentsOpaqueAndDoesNotEvictFullSlots() {
        var state = new Material(new BlockSpec("minecraft:stone", Map.of("axis", "x")), "minecraft:stone");
        var plan = allocate(List.of(demand(0, state, 16), demand(1, B, 2)), facts(List.of(),
                List.of(slot(9, Area.MAIN, false, false, 16, A),
                        slot(10, Area.MAIN, false, false, 0, B))));
        assertEquals(Map.of(state, 0L, B, 0L), plan.targetInventoryTotals());
        assertEquals(2, plan.unmet().size());
        assertTrue(plan.reservations().isEmpty());
    }

    @Test
    void exactSharedCapacityCaseAlsoUsesNativeComponentAndMax16Facts() {
        var empty = new Slot(9, Area.MAIN, true, false, "r", List.of(
                new Eligibility(A, "opaque incoming A components", "native insert A", 16, 16),
                new Eligibility(B, "opaque incoming B components", "native insert B", 16, 16)));
        // Native qualification omits A from this partial slot: core never tries its own component merge.
        var incompatiblePartial = new Slot(10, Area.MAIN, false, false, "r", List.of());
        var plan = allocate(List.of(demand(0, A, 16), demand(1, B, 16)),
                facts(List.of(held(10, 8, false, A)), List.of(incompatiblePartial, empty)));
        assertEquals(Map.of(A, 16L, B, 0L), plan.targetInventoryTotals());
        assertEquals(8, plan.reservations().getFirst().amount());
        assertEquals(9, plan.reservations().getFirst().slot());
        assertEquals("opaque incoming A components", plan.reservations().getFirst().componentIdentity());
        assertEquals(16, plan.unmet().getFirst().amount());
    }

    @Test
    void combinesEqualKeysInTraversalOrderAndSortsTargetTiesStably() {
        var plan = allocate(List.of(demand(7, B, 3), demand(2, A, 5), demand(9, B, 2)),
                facts(List.of(), List.of(slot(9, Area.MAIN, true, false, 64, B))));
        assertEquals(List.of(B, A), new ArrayList<>(plan.targetInventoryTotals().keySet()));
        assertEquals(5L, plan.targetInventoryTotals().get(B));
        assertEquals(2, plan.demand().get(1).targetId());
        assertTrue(allocate(List.of(demand(0, A, 0)), facts(List.of(), List.of())).unmet().isEmpty());
        assertTrue(allocate(List.of(), facts(List.of(), List.of())).targetInventoryTotals().isEmpty());
    }

    @Test
    void snapshotsAndResultsAreDeeplyImmutable() {
        var eligible = new java.util.HashSet<>(Set.of(A));
        var stock = new ArrayList<>(List.of(new Held(0, 1, false, eligible)));
        var slots = new ArrayList<>(List.of(slot(9, Area.MAIN, true, false, 16, A)));
        var demands = new ArrayList<>(List.of(demand(0, A, 4)));
        var snapshot = facts(stock, slots);
        var plan = allocate(demands, snapshot);
        eligible.clear(); stock.clear(); slots.clear(); demands.clear();
        assertEquals(4L, plan.targetInventoryTotals().get(A));
        assertEquals(1, snapshot.held().size());
        assertEquals(Set.of(A), snapshot.held().getFirst().eligibleMaterials());
        assertThrows(UnsupportedOperationException.class, () -> plan.targetInventoryTotals().put(B, 3L));
        assertThrows(UnsupportedOperationException.class, () -> plan.reservations().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.slots().clear());
    }

    @Test
    void revisionRetainsConsumedAssignmentsEvenWhenDifferentFreshCapacityAppears() {
        var original = allocate(List.of(demand(0, A, 20)), facts(List.of(), List.of(slot(9, Area.MAIN, true, false, 64, A))));
        var lostStock = facts(List.of(), List.of(slot(10, Area.MAIN, true, false, 64, A)));
        var revised = original.revise(lostStock, Map.of(9, 20));
        assertTrue(revised.reservations().isEmpty());
        assertEquals(20L, revised.targetInventoryTotals().get(A));
        assertEquals(20, revised.unmet().getFirst().amount());
        var partial = original.revise(lostStock, Map.of(9, 7));
        assertEquals(13, partial.reservations().getFirst().amount());
        assertEquals(10, partial.reservations().getFirst().slot());
        assertEquals(9, partial.reservations().getFirst().assignmentSlot());
        var truthfulHeld = original.revise(facts(List.of(held(9, 20, false, A)), lostStock.slots()), Map.of(9, 20));
        assertEquals(20L, truthfulHeld.targetInventoryTotals().get(A));
        assertTrue(truthfulHeld.unmet().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> original.revise(lostStock, Map.of(9, 21)));
        assertThrows(IllegalArgumentException.class, () -> original.revise(lostStock, Map.of(10, 1)));
        assertThrows(IllegalArgumentException.class, () -> original.revise(lostStock, Map.of(9, -1)));
    }

    @Test
    void revisionSplitsRelocatedCapacityAcrossRetainedOriginalAllowances() {
        var original = allocate(List.of(demand(0, A, 30)), facts(List.of(), List.of(
                slot(9, Area.MAIN, true, false, 16, A), slot(10, Area.MAIN, true, false, 16, A))));
        var revised = original.revise(facts(List.of(), List.of(slot(11, Area.MAIN, true, false, 64, A))), Map.of(9, 6));
        assertEquals(List.of(9, 10), revised.reservations().stream().map(DestinationReservation::assignmentSlot).toList());
        assertEquals(List.of(10, 14), revised.reservations().stream().map(DestinationReservation::amount).toList());
        assertEquals(30L, revised.targetInventoryTotals().get(A));
        assertEquals(6, revised.unmet().getFirst().amount());
        assertEquals(List.of(11, 11), revised.reservations().stream().map(DestinationReservation::slot).toList());
    }

    @Test
    void revisionPreservesOriginalTargetIdentitiesAndCanonicalDemand() {
        var demands = List.of(demand(73, A, 7), demand(21, B, 4), demand(90, A, 3));
        var inventory = facts(List.of(), List.of(slot(9, Area.MAIN, true, false, 16, A), slot(10, Area.MAIN, true, false, 16, B)));
        var revised = allocate(demands, inventory).revise(inventory, Map.of());
        assertEquals(demands, revised.demand());
        assertEquals(List.of(A, B), new ArrayList<>(revised.targetInventoryTotals().keySet()));
    }

    @Test
    void rejectsDuplicatePhysicalFactsInvalidCountsAndOverflow() {
        assertThrows(IllegalArgumentException.class, () -> demand(-1, A, 1));
        assertThrows(IllegalArgumentException.class, () -> demand(0, A, -1));
        assertThrows(IllegalArgumentException.class, () -> held(0, -1, false, A));
        assertThrows(IllegalArgumentException.class, () -> slot(9, Area.MAIN, true, false, -1, A));
        assertThrows(IllegalArgumentException.class, () -> facts(List.of(held(0, 1, false, A), held(0, 1, false, A)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> facts(List.of(), List.of(slot(9, Area.MAIN, true, false, 1, A), slot(9, Area.MAIN, true, false, 1, A))));
        assertThrows(IllegalArgumentException.class, () -> allocate(List.of(demand(0, A, 1), demand(0, B, 1)), facts(List.of(), List.of())));
        assertThrows(ArithmeticException.class, () -> allocate(List.of(demand(0, A, Long.MAX_VALUE), demand(1, A, 1)), facts(List.of(), List.of())));
        assertThrows(IllegalArgumentException.class, () -> new Material(A.block(), "stone"));
        assertThrows(IllegalArgumentException.class, () -> new Eligibility(A, "c", "e", 17, 16));
        assertThrows(IllegalArgumentException.class, () -> facts(List.of(held(9, 7, true, A)), List.of(slot(9, Area.MAIN, false, false, 16, A))));
        assertThrows(IllegalArgumentException.class, () -> facts(List.of(held(9, 1, false, A)), List.of(slot(9, Area.MAIN, true, false, 16, A))));
    }

    static Material material(String name) { return new Material(new BlockSpec("minecraft:" + name, Map.of()), "minecraft:" + name); }
    static MaterialDemand demand(int id, Material material, long amount) { return new MaterialDemand(id, material, amount); }
    static Held held(int id, int amount, boolean reserved, Material... materials) { return new Held(id, amount, reserved, Set.of(materials)); }
    static Slot slot(int id, Area area, boolean empty, boolean reserved, int capacity, Material... materials) {
        return new Slot(id, area, empty, reserved, "slot-r1", java.util.Arrays.stream(materials)
                .map(m -> new Eligibility(m, "components-opaque", "native-eligible", capacity, Math.max(1, capacity))).toList());
    }
    static InventorySnapshot facts(List<Held> held, List<Slot> slots) { return new InventorySnapshot("inventory-r1", held, slots); }
}
