package com.mapartrunner.core.refill;

import com.mapartrunner.core.model.BuildPlan.BlockSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** One integer allocation of native-prequalified stock and destinations; no native merge simulation. */
public final class RefillPlan {
    private final List<MaterialDemand> demand;
    private final Map<Material, Long> targetInventoryTotals;
    private final List<DestinationReservation> reservations;
    private final List<Unmet> unmet;

    private RefillPlan(List<MaterialDemand> demand, Map<Material, Long> totals,
                       List<DestinationReservation> reservations, List<Unmet> unmet) {
        this.demand = List.copyOf(demand);
        this.targetInventoryTotals = Collections.unmodifiableMap(new LinkedHashMap<>(totals));
        this.reservations = List.copyOf(reservations);
        this.unmet = List.copyOf(unmet);
    }

    public List<MaterialDemand> demand() { return demand; }
    public Map<Material, Long> targetInventoryTotals() { return targetInventoryTotals; }
    public List<DestinationReservation> reservations() { return reservations; }
    public List<Unmet> unmet() { return unmet; }

    public static RefillPlan allocate(List<MaterialDemand> orderedDemand, InventorySnapshot facts) {
        return allocate(orderedDemand, facts, Map.of(), Set.of(), Map.of());
    }

    /** Revise this original request, never a prior revision, using cumulative acknowledged consumption. */
    public RefillPlan revise(InventorySnapshot facts, Map<Integer, Integer> consumedBySlot) {
        consumedBySlot = Map.copyOf(consumedBySlot);
        var limits = new LinkedHashMap<Material, Long>();
        targetInventoryTotals.keySet().forEach(material -> limits.put(material, 0L));
        var knownSlots = new HashSet<Integer>();
        var spent = new HashSet<Integer>();
        for (var reservation : reservations) {
            knownSlots.add(reservation.slot());
            int consumed = consumedBySlot.getOrDefault(reservation.slot(), 0);
            if (consumed < 0 || consumed > reservation.amount()) throw new IllegalArgumentException("Invalid consumed reservation amount");
            limits.merge(reservation.material(), (long) reservation.amount() - consumed, Math::addExact);
            if (consumed == reservation.amount()) spent.add(reservation.slot());
        }
        if (!knownSlots.containsAll(consumedBySlot.keySet())) throw new IllegalArgumentException("Unknown consumed reservation");
        var revised = allocate(demand, facts, limits, spent, targetInventoryTotals);
        var assigned = new ArrayList<DestinationReservation>();
        var remainingAllowances = new LinkedHashMap<Integer, Integer>();
        for (var reservation : reservations) remainingAllowances.put(reservation.assignmentSlot(),
                reservation.amount() - consumedBySlot.getOrDefault(reservation.assignmentSlot(), 0));
        for (var destination : revised.reservations()) {
            int amount = destination.amount();
            for (var original : reservations) {
                if (!original.material().equals(destination.material())) continue;
                int part = Math.min(amount, remainingAllowances.get(original.assignmentSlot()));
                if (part == 0) continue;
                assigned.add(new DestinationReservation(destination.material(), destination.slot(), part,
                        destination.slotRevision(), destination.componentIdentity(), destination.eligibilityIdentity(),
                        destination.capacity(), original.assignmentSlot()));
                remainingAllowances.compute(original.assignmentSlot(), (key, value) -> value - part);
                amount -= part;
                if (amount == 0) break;
            }
        }
        // The objective is frozen even when fresh facts make it infeasible; unmet reports that failure explicitly.
        return new RefillPlan(demand, targetInventoryTotals, assigned, revised.unmet());
    }

    private static RefillPlan allocate(List<MaterialDemand> orderedDemand, InventorySnapshot facts,
                                       Map<Material, Long> incomingLimits, Set<Integer> spentSlots,
                                       Map<Material, Long> frozenTargets) {
        Objects.requireNonNull(facts, "facts");
        var demands = List.copyOf(orderedDemand);
        var required = new LinkedHashMap<Material, Long>();
        var ids = new HashSet<Integer>();
        for (var demand : demands) {
            if (!ids.add(demand.targetId())) throw new IllegalArgumentException("Duplicate target ID: " + demand.targetId());
            required.merge(demand.material(), demand.requiredCount(), Math::addExact);
        }
        var heldRemaining = new int[facts.held().size()];
        for (int i = 0; i < heldRemaining.length; i++) {
            var held = facts.held().get(i);
            heldRemaining[i] = held.reserved() ? 0 : held.amount();
        }
        var totals = new LinkedHashMap<Material, Long>();
        // Assign all physical held units before reserving incoming stock, retaining demand order.
        for (var entry : required.entrySet()) {
            long credited = 0;
            for (int i = 0; i < heldRemaining.length; i++) {
                if (!facts.held().get(i).eligibleMaterials().contains(entry.getKey())) continue;
                int amount = (int) Math.min(entry.getValue() - credited, heldRemaining[i]);
                credited += amount;
                heldRemaining[i] -= amount;
            }
            totals.put(entry.getKey(), credited);
        }
        var orderedSlots = facts.slots().stream().sorted(Comparator.comparingInt(Slot::preference)).toList();
        var claimedSlots = new HashSet<Integer>();
        var reservations = new ArrayList<DestinationReservation>();
        var unmet = new ArrayList<Unmet>();
        for (var entry : required.entrySet()) {
            long missing = entry.getValue() - totals.get(entry.getKey());
            // Fresh held stock covers original demand even beyond a clipped trip objective, but incoming
            // reservations may fill only that frozen objective and the original unconsumed allowance.
            long objectiveMissing = Math.max(0, frozenTargets.getOrDefault(entry.getKey(), entry.getValue()) - totals.get(entry.getKey()));
            long remaining = Math.min(Math.min(missing, objectiveMissing), incomingLimits.getOrDefault(entry.getKey(), Long.MAX_VALUE));
            for (var slot : orderedSlots) {
                if (remaining == 0) break;
                if (slot.reserved() || claimedSlots.contains(slot.index()) || spentSlots.contains(slot.index())) continue;
                for (var eligibility : slot.eligibility()) {
                    if (!eligibility.material().equals(entry.getKey())) continue;
                    int capacity = Math.min(eligibility.capacity(), eligibility.maxStackSize());
                    int amount = (int) Math.min(remaining, capacity);
                    if (amount == 0) continue;
                    claimedSlots.add(slot.index());
                    reservations.add(new DestinationReservation(entry.getKey(), slot.index(), amount,
                            slot.revision(), eligibility.componentIdentity(), eligibility.identity(), capacity, slot.index()));
                    remaining -= amount;
                    totals.put(entry.getKey(), Math.addExact(totals.get(entry.getKey()), amount));
                    break;
                }
            }
            long unfilled = entry.getValue() - totals.get(entry.getKey());
            if (unfilled > 0) unmet.add(new Unmet(entry.getKey(), unfilled, Reason.CAPACITY_LIMITED));
        }
        return new RefillPlan(demands, totals, reservations, unmet);
    }

    public record Material(BlockSpec block, String itemId) {
        private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
        public Material {
            Objects.requireNonNull(block, "block");
            text(itemId, "itemId");
            if (!IDENTIFIER.matcher(itemId).matches()) throw new IllegalArgumentException("Invalid item identifier");
        }
    }

    /** Input order is canonical traversal order; target IDs retain its stable identity/tie break. */
    public record MaterialDemand(int targetId, Material material, long requiredCount) {
        public MaterialDemand {
            if (targetId < 0 || requiredCount < 0) throw new IllegalArgumentException("Negative demand identity/count");
            Objects.requireNonNull(material, "material");
        }
    }

    /** A physical prequalified pool can be eligible for several materials, but is credited only once. */
    public record Held(int slot, int amount, boolean reserved, Set<Material> eligibleMaterials) {
        public Held {
            if (slot < 0 || amount < 0) throw new IllegalArgumentException("Negative stock slot/count");
            eligibleMaterials = Set.copyOf(eligibleMaterials);
        }
    }

    public enum Area { MAIN, HOTBAR }
    public record Eligibility(Material material, String componentIdentity, String identity, int capacity, int maxStackSize) {
        public Eligibility {
            Objects.requireNonNull(material, "material");
            text(componentIdentity, "componentIdentity"); text(identity, "identity");
            if (capacity < 0 || maxStackSize <= 0 || capacity > maxStackSize) throw new IllegalArgumentException("Invalid native capacity/stack limit");
        }
    }
    public record Slot(int index, Area area, boolean empty, boolean reserved, String revision, List<Eligibility> eligibility) {
        public Slot {
            if (index < 0) throw new IllegalArgumentException("Negative slot");
            Objects.requireNonNull(area, "area"); text(revision, "revision");
            eligibility = List.copyOf(eligibility);
            var materials = new HashSet<Material>();
            for (var fact : eligibility) {
                if (!materials.add(fact.material())) throw new IllegalArgumentException("Duplicate slot eligibility");
            }
        }
        private int preference() { return (empty ? 0 : 2) + (area == Area.MAIN ? 0 : 1); }
    }
    public record InventorySnapshot(String revision, List<Held> held, List<Slot> slots) {
        public InventorySnapshot {
            text(revision, "revision"); held = List.copyOf(held); slots = List.copyOf(slots);
            var heldIds = new HashSet<Integer>();
            for (var fact : held) if (!heldIds.add(fact.slot())) throw new IllegalArgumentException("Duplicate held slot");
            var slotIds = new HashSet<Integer>();
            for (var fact : slots) if (!slotIds.add(fact.index())) throw new IllegalArgumentException("Duplicate destination slot");
            for (var stock : held) for (var destination : slots) {
                if (stock.slot() == destination.index() && (stock.reserved() != destination.reserved()
                        || (stock.amount() > 0 && destination.empty()))) {
                    throw new IllegalArgumentException("Contradictory physical slot facts");
                }
            }
        }
    }
    /** assignmentSlot retains the original reservation allowance when a fresh legal destination changes. */
    public record DestinationReservation(Material material, int slot, int amount, String slotRevision,
                                         String componentIdentity, String eligibilityIdentity, int capacity, int assignmentSlot) {
        public DestinationReservation {
            Objects.requireNonNull(material, "material");
            text(slotRevision, "slotRevision"); text(componentIdentity, "componentIdentity"); text(eligibilityIdentity, "eligibilityIdentity");
            if (slot < 0 || assignmentSlot < 0 || amount <= 0 || capacity < amount) throw new IllegalArgumentException("Invalid reservation");
        }
    }
    public enum Reason { CAPACITY_LIMITED }
    public record Unmet(Material material, long amount, Reason reason) {
        public Unmet {
            Objects.requireNonNull(material, "material"); Objects.requireNonNull(reason, "reason");
            if (amount <= 0) throw new IllegalArgumentException("Unmet count must be positive");
        }
    }
    private static void text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
