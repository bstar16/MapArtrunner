package com.mapartrunner.core.refill;

import com.mapartrunner.core.model.BuildPlan.GridPos;
import com.mapartrunner.core.refill.RefillPlan.Material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Bounded progress for one frozen request. Effects, correlation, native clocks and cleanup stay external. */
public final class RefillFlow {
    private Request request;
    private RefillPlan plan;
    private Stage stage;
    private Outcome outcome;
    private String reason = "";
    private boolean paused;
    private boolean navigationAdmitted;
    private boolean admissionPending;
    private boolean pendingAcknowledgement;
    private boolean satisfied;
    private boolean executable;
    private boolean exhausted;
    private boolean capacityLimited;
    private boolean cleanupPending;
    private boolean pauseCleanup;
    private boolean pauseCleanupSettled;
    private int supplyIndex = -1;
    private final List<Integer> visited = new ArrayList<>();
    private final Set<Long> chargedCallbacks = new HashSet<>();
    private final Map<Integer, Integer> consumed = new LinkedHashMap<>();
    private long totalCallbacks;
    private int stageCallbacks;
    private int progressCallbacks;
    private int acknowledgementCallbacks;
    private int pacingCallbacks;
    private int cleanupCallbacks;
    private long cleanupStartedMillis;
    private int pauseCleanupCallbacks;
    private Long pauseCleanupStartedMillis;
    private long pauseCleanupElapsedMillis;
    private long lastMillis;
    private double progressBaseline;

    public void start(Request request) {
        Objects.requireNonNull(request, "request");
        if (this.request != null) throw new IllegalStateException("A flow owns exactly one request");
        // Create binding, frozen targets and all counters together. This instance never resets for another trip.
        this.request = request;
        plan = request.plan();
        lastMillis = request.startedAtMillis();
        capacityLimited = !plan.unmet().isEmpty();
        satisfied = noAdditionalNeeded(request.additionalNeeded());
        stage = Stage.INITIAL_RELEASE;
        cleanupPending = true;
        cleanupStartedMillis = lastMillis;
        if (satisfied && !capacityLimited) finish(Outcome.SATISFIED, "No collection required");
        else if (satisfied) fail("Capacity limited: no actionable inventory capacity");
    }

    public Decision tick(Observation observation) {
        requireStarted(); Objects.requireNonNull(observation, "observation");
        if (!request.runId().equals(observation.runId())) return decision();
        if (observation.monotonicMillis() < lastMillis) throw new IllegalArgumentException("Monotonic time moved backwards");
        validateAdditional(observation.additionalNeeded(), request.plan().targetInventoryTotals());
        lastMillis = observation.monotonicMillis();
        // Wall expiry is independent of callback deduplication, eligibility, pause and terminal status.
        if (cleanupPending && lastMillis - cleanupStartedMillis >= Limits.CLEANUP_MILLIS) cleanupExpired("physical cleanup wall timeout");
        if (pauseCleanup && lastMillis - pauseCleanupStartedMillis >= Limits.CLEANUP_MILLIS - pauseCleanupElapsedMillis) cleanupExpired("pause cleanup wall timeout");
        boolean cleanup = observation.cleanupCallback() && (cleanupPending || pauseCleanup);
        boolean ordinary = observation.eligible() && !paused && !terminal();
        if ((!cleanup && !ordinary) || !chargedCallbacks.add(observation.callbackId())) return decision();
        totalCallbacks = Math.incrementExact(totalCallbacks);
        if (totalCallbacks >= Limits.maxTripCallbacks(request.config().orderedSupplies().size())) {
            cleanupExpired("Total refill callback budget exhausted");
            return decision();
        }
        var checked = observation.checkedResult();
        boolean matching = checked.stage() == stage;
        Result result = matching ? checked.result() : Result.WAITING;
        if (cleanup) {
            if (pauseCleanup) {
                pauseCleanupCallbacks++;
                if (result == Result.RELEASED) {
                    pauseCleanupElapsedMillis += lastMillis - pauseCleanupStartedMillis;
                    pauseCleanup = false; pauseCleanupSettled = true;
                }
                else if (pauseCleanupCallbacks >= Limits.CLEANUP_CALLBACKS) cleanupExpired("pause cleanup callback budget exhausted");
            }
            if (cleanupPending) {
                cleanupCallbacks++;
                if (result == Result.RELEASED) cleanupPending = false;
                else if (cleanupCallbacks >= Limits.CLEANUP_CALLBACKS) cleanupExpired("physical cleanup callback budget exhausted");
            }
        }
        if (paused || terminal()) return decision();
        if (!ordinary) return decision();
        stageCallbacks++;
        executable = observation.executableRetainedWork();
        // Once collected, later construction consumption cannot turn this request back into a top-up.
        if (noAdditionalNeeded(observation.additionalNeeded())) satisfied = true;
        if (isFailure(result)) {
            fail(stage + " action " + result);
            return decision();
        }
        switch (stage) {
            case INITIAL_RELEASE -> { if (!cleanupPending) nextSupplyOrReturn(); }
            case OUTBOUND, RETURNING -> navigate(result, observation.distanceToGoal());
            case OUTBOUND_RELEASE -> { if (!cleanupPending) enter(Stage.OPENING); }
            case OPENING -> { if (result == Result.OPENED) enter(Stage.TRANSFERRING); }
            case TRANSFERRING -> transfer(result);
            case CLOSING -> { if (result == Result.CLOSED) enter(Stage.TRANSACTION_RELEASE); }
            case TRANSACTION_RELEASE -> { if (!cleanupPending) nextSupplyOrReturn(); }
            case RETURN_RELEASE -> {
                if (!cleanupPending) {
                    if (satisfied && !capacityLimited) finish(Outcome.SATISFIED, "Frozen request satisfied");
                    else if (executable) finish(Outcome.PARTIAL_EXECUTABLE,
                            capacityLimited ? "Capacity limited; retained work is executable" : "Supply exhausted; retained work is executable");
                    else fail(capacityLimited ? "Capacity limited; required work is not executable" : "Required supply exhausted");
                }
            }
            case DONE, FAILED -> { }
        }
        if (!terminal() && stageCallbacks >= stageLimit(stage)) fail(stage + " callback budget exhausted");
        return decision();
    }

    private void navigate(Result result, double distance) {
        if (!navigationAdmitted) {
            if (result == Result.ACCEPTED) {
                navigationAdmitted = true;
                // First admitted distance is the first window's real baseline, never a sentinel.
                if (progressCallbacks == 0) progressBaseline = distance;
            } else if (result == Result.ARRIVED) fail(stage + " arrival without admitted navigation");
            else admissionPending = true;
            return;
        }
        if (result == Result.ARRIVED) {
            enter(stage == Stage.OUTBOUND ? Stage.OUTBOUND_RELEASE : Stage.RETURN_RELEASE);
            return;
        }
        progressCallbacks++;
        if (progressCallbacks >= Limits.PROGRESS_CALLBACKS) {
            if (progressBaseline - distance < Limits.PROGRESS_BLOCKS) fail(stage + " navigation progress below 1.0 block");
            else { progressCallbacks = 0; progressBaseline = distance; }
        }
    }

    private void transfer(Result result) {
        pacingCallbacks++;
        if (pendingAcknowledgement) {
            acknowledgementCallbacks++;
            if (result == Result.ACKNOWLEDGED) { pendingAcknowledgement = false; acknowledgementCallbacks = 0; }
            else if (acknowledgementCallbacks >= Limits.ACTION_CALLBACKS) { fail("Click acknowledgement/reconciliation callback budget exhausted"); return; }
        } else if (result == Result.CLICK_ISSUED) {
            if (pacingCallbacks < request.config().inventoryClickDelayTicks()) { fail("Click issued before pacing allowance"); return; }
            pendingAcknowledgement = true;
            acknowledgementCallbacks = 0;
            pacingCallbacks = 0;
        }
        if (result == Result.STOCK_EXHAUSTED) {
            if (pendingAcknowledgement) { fail("Supply exhausted with an unresolved click"); return; }
            exhausted = true;
            enter(Stage.CLOSING);
        } else if (satisfied && !pendingAcknowledgement) enter(Stage.CLOSING);
    }

    private void nextSupplyOrReturn() {
        if (supplyIndex < 0 && request.config().orderedSupplies().isEmpty() && !satisfied) {
            exhausted = true;
            fail("Required supply exhausted: no configured supplies");
            return;
        }
        if (!satisfied && supplyIndex + 1 < request.config().orderedSupplies().size()) {
            supplyIndex++;
            visited.add(request.config().orderedSupplies().get(supplyIndex).id());
            enter(Stage.OUTBOUND);
        } else {
            if (!satisfied) exhausted = true;
            // Admission remains pending and bounded; every result, including return rejection, is checked.
            enter(Stage.RETURNING);
        }
    }

    public void pause(long monotonicMillis) {
        requireStarted();
        if (terminal() || paused) return;
        if (monotonicMillis < lastMillis) throw new IllegalArgumentException("Monotonic time moved backwards");
        lastMillis = monotonicMillis;
        paused = true;
        // Retain stage, progress, transfer, acknowledgement, visited and total consumption. Only authority is revoked.
        navigationAdmitted = false;
        admissionPending = false;
        pauseCleanup = true;
        pauseCleanupSettled = false;
        // Only time actually awaiting cleanup spends this wall reserve; settlement never renews it.
        pauseCleanupStartedMillis = monotonicMillis;
    }

    public void resume() {
        requireStarted();
        if (!paused || terminal()) throw new IllegalStateException("Resume requires a live paused request");
        if (!pauseCleanupSettled || cleanupPending) throw new IllegalStateException("Resume requires observed physical release");
        paused = false;
        // Reissue authority only; no counter or acknowledgement is cleared by resume.
    }

    public void cancel(long monotonicMillis) {
        requireStarted();
        if (monotonicMillis < lastMillis) throw new IllegalArgumentException("Monotonic time moved backwards");
        lastMillis = monotonicMillis;
        fail("Refill request cancelled");
    }

    public void revise(RefillPlan.InventorySnapshot fresh, Map<Integer, Integer> cumulativeConsumed) {
        requireStarted();
        if (terminal()) throw new IllegalStateException("Terminal request cannot revise reservations");
        cumulativeConsumed = Map.copyOf(cumulativeConsumed);
        for (var entry : consumed.entrySet()) {
            if (cumulativeConsumed.getOrDefault(entry.getKey(), 0) < entry.getValue()) throw new IllegalArgumentException("Consumption cannot decrease");
        }
        var revised = request.plan().revise(fresh, cumulativeConsumed);
        // Validate and allocate before replacing retained facts; revision never resets a deadline or visited supply.
        consumed.clear(); consumed.putAll(cumulativeConsumed);
        plan = revised;
        capacityLimited = !request.plan().unmet().isEmpty() || !revised.unmet().isEmpty();
    }

    private void enter(Stage next) {
        stage = next;
        stageCallbacks = 0;
        navigationAdmitted = false;
        admissionPending = false;
        progressCallbacks = 0;
        pendingAcknowledgement = false;
        acknowledgementCallbacks = 0;
        pacingCallbacks = request.config().inventoryClickDelayTicks();
        if (isRelease(next)) {
            cleanupPending = true;
            cleanupCallbacks = 0;
            cleanupStartedMillis = lastMillis;
        }
    }

    private void fail(String failure) {
        if (outcome != null) return;
        reason = failure;
        outcome = Outcome.FAILED;
        stage = Stage.FAILED;
        paused = false;
        navigationAdmitted = false;
        // Retain the primary failure and request facts before revoking progress. Never clear external transactions.
        if (!cleanupPending && !pauseCleanup) {
            cleanupPending = true;
            cleanupCallbacks = 0;
            cleanupStartedMillis = lastMillis;
        }
    }

    private void cleanupExpired(String failure) {
        fail(failure);
        // Expiry revokes all effect authority; later observations cannot re-enable release writes here.
        cleanupPending = false;
        pauseCleanup = false;
    }

    private void finish(Outcome result, String detail) {
        outcome = result; reason = detail; stage = Stage.DONE;
        cleanupPending = false; navigationAdmitted = false; paused = false;
    }
    private boolean terminal() { return stage == Stage.DONE || stage == Stage.FAILED; }
    public Decision decision() {
        requireStarted();
        Intent intent = cleanupPending || pauseCleanup ? Intent.RELEASE : Intent.NONE;
        if (!paused && !terminal() && !isRelease(stage)) intent = switch (stage) {
            case OUTBOUND -> navigationAdmitted || admissionPending ? Intent.WAIT : Intent.START_OUTBOUND;
            case RETURNING -> navigationAdmitted || admissionPending ? Intent.WAIT : Intent.START_RETURN;
            case OPENING -> Intent.OPEN;
            case TRANSFERRING -> pendingAcknowledgement || pacingCallbacks < request.config().inventoryClickDelayTicks() ? Intent.WAIT : Intent.TRANSFER;
            case CLOSING -> Intent.CLOSE;
            default -> intent;
        };
        return new Decision(stage, intent, supplyIndex < 0 ? Optional.empty() : Optional.of(request.config().orderedSupplies().get(supplyIndex)),
                request.anchorFloor(), plan, Optional.ofNullable(outcome), reason);
    }
    public Snapshot snapshot() {
        requireStarted();
        return new Snapshot(stage, paused, totalCallbacks, stageCallbacks, progressCallbacks, acknowledgementCallbacks,
                cleanupCallbacks, pauseCleanupCallbacks, List.copyOf(visited), request.plan().targetInventoryTotals(),
                Map.copyOf(consumed), request.anchorFloor(), capacityLimited, exhausted, Optional.ofNullable(outcome), reason);
    }
    private void requireStarted() { if (request == null) throw new IllegalStateException("Flow not started"); }
    public static boolean isRelease(Stage stage) {
        return stage == Stage.INITIAL_RELEASE || stage == Stage.OUTBOUND_RELEASE || stage == Stage.TRANSACTION_RELEASE || stage == Stage.RETURN_RELEASE;
    }
    private static boolean isFailure(Result result) { return result == Result.REJECTED || result == Result.STOPPED || result == Result.UNAVAILABLE || result == Result.FAILED; }
    private static int stageLimit(Stage stage) {
        return switch (stage) {
            case OUTBOUND, RETURNING -> Limits.NAVIGATION_CALLBACKS;
            case OPENING, CLOSING -> Limits.ACTION_CALLBACKS;
            case TRANSFERRING -> Limits.TRANSFER_CALLBACKS;
            default -> Integer.MAX_VALUE;
        };
    }
    private static boolean noAdditionalNeeded(Map<Material, Long> additional) { return additional.values().stream().allMatch(amount -> amount == 0); }
    private static Map<Material, Long> counts(Map<Material, Long> input) {
        var result = new LinkedHashMap<Material, Long>();
        input.forEach((material, amount) -> {
            Objects.requireNonNull(material, "material"); Objects.requireNonNull(amount, "amount");
            if (amount < 0) throw new IllegalArgumentException("Negative additional-needed amount");
            result.put(material, amount);
        });
        return Collections.unmodifiableMap(result);
    }
    private static void validateAdditional(Map<Material, Long> additional, Map<Material, Long> frozen) {
        if (!additional.keySet().equals(frozen.keySet())) throw new IllegalArgumentException("Additional-needed facts must cover frozen materials exactly");
        additional.forEach((material, amount) -> {
            if (amount > frozen.get(material)) throw new IllegalArgumentException("Additional needed exceeds frozen target");
        });
    }
    private static void text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    public enum Stage { INITIAL_RELEASE, OUTBOUND, OUTBOUND_RELEASE, OPENING, TRANSFERRING, CLOSING, TRANSACTION_RELEASE, RETURNING, RETURN_RELEASE, DONE, FAILED }
    public enum Intent { NONE, WAIT, RELEASE, START_OUTBOUND, OPEN, TRANSFER, CLOSE, START_RETURN }
    public enum Result { WAITING, ACCEPTED, ARRIVED, RELEASED, OPENED, CLICK_ISSUED, ACKNOWLEDGED, STOCK_EXHAUSTED, CLOSED, REJECTED, STOPPED, UNAVAILABLE, FAILED }
    public enum Outcome { SATISFIED, PARTIAL_EXECUTABLE, FAILED }
    public enum Trigger { INITIAL_PREFLIGHT, ACTIONABLE_SHORTAGE }
    public record Supply(int id, String dimension, GridPos worldPosition, String name) {
        public Supply {
            if (id <= 0) throw new IllegalArgumentException("Supply ID must be positive");
            text(dimension, "dimension"); Objects.requireNonNull(worldPosition, "worldPosition"); text(name, "name");
        }
    }
    public record Config(List<Supply> orderedSupplies, int inventoryClickDelayTicks, Limits limits) {
        public Config {
            orderedSupplies = List.copyOf(orderedSupplies); Objects.requireNonNull(limits, "limits");
            if (inventoryClickDelayTicks < 0 || inventoryClickDelayTicks > 10) throw new IllegalArgumentException("Pacing must be 0..10");
            var ids = new HashSet<Integer>();
            for (var supply : orderedSupplies) if (!ids.add(supply.id())) throw new IllegalArgumentException("Duplicate supply ID");
        }
        public static Config defaults(List<Supply> supplies) { return new Config(supplies, 4, new Limits()); }
    }
    /** Fixed policy snapshot: no unreviewed public calibration knobs. */
    public record Limits() {
        public static final int NAVIGATION_CALLBACKS = 400;
        public static final int PROGRESS_CALLBACKS = 200;
        public static final double PROGRESS_BLOCKS = 1.0;
        public static final int ACTION_CALLBACKS = 40;
        public static final int TRANSFER_CALLBACKS = 1200;
        public static final int CLEANUP_CALLBACKS = 80;
        public static final long CLEANUP_MILLIS = 10_000;
        public static long maxTripCallbacks(long supplyCount) {
            if (supplyCount < 0) throw new IllegalArgumentException("Negative supply count");
            return Math.addExact(Math.multiplyExact(1840L, supplyCount), 640L);
        }
    }
    public record Request(UUID runId, Config config, RefillPlan plan, GridPos anchorFloor, Trigger trigger,
                          Map<Material, Long> additionalNeeded, long startedAtMillis) {
        public Request {
            Objects.requireNonNull(runId, "runId"); Objects.requireNonNull(config, "config"); Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(anchorFloor, "anchorFloor"); Objects.requireNonNull(trigger, "trigger");
            if (startedAtMillis < 0) throw new IllegalArgumentException("Negative monotonic time");
            additionalNeeded = counts(additionalNeeded); validateAdditional(additionalNeeded, plan.targetInventoryTotals());
            if (trigger == Trigger.ACTIONABLE_SHORTAGE && noAdditionalNeeded(additionalNeeded)) throw new IllegalArgumentException("Later request needs actionable shortage");
        }
    }
    /** Engine has already checked action/run correlation; stage still protects against an old-stage result. */
    public record CheckedResult(Stage stage, Result result) {
        public CheckedResult { Objects.requireNonNull(stage, "stage"); Objects.requireNonNull(result, "result"); }
    }
    public record Observation(UUID runId, long callbackId, long monotonicMillis, boolean eligible, boolean cleanupCallback,
                              CheckedResult checkedResult, double distanceToGoal, Map<Material, Long> additionalNeeded,
                              boolean executableRetainedWork) {
        public Observation {
            Objects.requireNonNull(runId, "runId"); Objects.requireNonNull(checkedResult, "checkedResult");
            if (callbackId < 0 || monotonicMillis < 0 || !Double.isFinite(distanceToGoal) || distanceToGoal < 0) throw new IllegalArgumentException("Invalid callback/time/distance");
            additionalNeeded = counts(additionalNeeded);
        }
    }
    public record Decision(Stage stage, Intent intent, Optional<Supply> supply, GridPos anchorFloor, RefillPlan plan,
                           Optional<Outcome> outcome, String reason) { }
    public record Snapshot(Stage stage, boolean paused, long totalCallbacks, int stageCallbacks, int progressCallbacks,
                           int acknowledgementCallbacks, int cleanupCallbacks, int pauseCleanupCallbacks, List<Integer> visitedSupplyIds,
                           Map<Material, Long> frozenTotals, Map<Integer, Integer> consumedAssignments, GridPos anchorFloor,
                           boolean capacityLimited, boolean exhausted, Optional<Outcome> outcome, String reason) {
        public Snapshot {
            visitedSupplyIds = List.copyOf(visitedSupplyIds); frozenTotals = counts(frozenTotals); consumedAssignments = Map.copyOf(consumedAssignments);
        }
    }
}
