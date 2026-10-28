package com.mapartrunner.core.run;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.Target;
import com.mapartrunner.core.movement.MovementPolicy;
import com.mapartrunner.core.movement.MovementPolicy.*;
import com.mapartrunner.core.placement.PlacementLedger;
import com.mapartrunner.core.placement.PlacementObservation;
import com.mapartrunner.core.placement.PlacementObservation.Evidence;
import com.mapartrunner.core.run.RunLifecycle.*;
import com.mapartrunner.core.traversal.LanePlanner;

import java.util.*;

/**
 * Single-threaded one-lane coordinator. The adapter owns effects and their generation barrier;
 * this owner issues immutable decisions, never client keys or claims of server acknowledgement.
 */
public final class BuildEngine {
    // Milestone safety budgets, measured in actual active unique callbacks, never sample/wall time.
    private static final int MAX_ATTEMPTS = 3;
    private static final int RETRY_SPACING = 4;
    private static final int PENDING_LIMIT = 80;
    private static final int VERIFY_LIMIT = 80;
    private static final int RUN_LIMIT = 1200;

    private final RunLifecycle lifecycle = new RunLifecycle();
    private final MovementPolicy movementPolicy = new MovementPolicy();
    private BuildPlan plan;
    private Settings settings;
    private PlacementLedger ledger;
    private LaneEntry entry;
    private long lastTick = -1;
    private long eligibleTicks;
    private long verifyTicks;
    private long verifyEnteredTick = -1;
    private long nextActionId;
    private List<PlacementEffectResult> effectResults = List.of();
    private Snapshot terminalSummary;

    public void start(BuildPlan plan, Context context, Settings settings) {
        Objects.requireNonNull(plan, "plan"); Objects.requireNonNull(context, "context");
        Objects.requireNonNull(settings, "settings");
        if (!plan.fingerprint().equals(context.planFingerprint())
                || !settings.fingerprint().equals(context.settingsFingerprint())) {
            throw new IllegalArgumentException("Plan and settings must match the bound context fingerprints");
        }
        lifecycle.start(context);
        // Create owners only after lifecycle validation. A fresh run replaces the entire prior summary.
        this.plan = plan; this.settings = settings;
        ledger = new PlacementLedger(plan); ledger.bindRun(context.runId());
        entry = null; lastTick = -1; eligibleTicks = 0; verifyTicks = 0;
        verifyEnteredTick = -1; nextActionId = 0; effectResults = List.of(); terminalSummary = null;
        if (plan.targets().isEmpty()) {
            finish(Outcome.SUCCESS, Reason.NOTHING_TO_BUILD, List.of(), "The normalized plan is empty");
            return;
        }
        List<LanePlanner.Lane> lanes;
        try {
            lanes = new LanePlanner().plan(plan, settings.halfWidth(), settings.stride());
        } catch (IllegalArgumentException unsupported) {
            finish(Outcome.FAILED, Reason.UNSUPPORTED_PLAN, allIds(), unsupported.getMessage());
            return;
        }
        if (lanes.size() != 1) {
            finish(Outcome.FAILED, Reason.UNSUPPORTED_PLAN, allIds(), "This milestone requires exactly one flat lane");
            return;
        }
        entry = new LaneEntry(lanes.getFirst(), Stage.ENTER_POSITION, 0, 0, 0, 0, 0,
                ledger.pendingTargetIds(), settings.constantSprint());
    }

    /** Pause retains the ledger and every movement counter; the adapter invalidates/releases effects. */
    public void pause() { lifecycle.pause(); }

    public void resume(Context context) { lifecycle.resume(context); }

    public void stop() {
        if (lifecycle.snapshot().phase() == Phase.TERMINAL) return;
        if (lifecycle.snapshot().phase() == Phase.IDLE) throw new IllegalStateException("Stop requires a started run");
        finish(Outcome.CANCELLED, Reason.CANCELLED, unfinishedIds(), "Stopped by owner");
    }

    public Snapshot snapshot() {
        if (terminalSummary != null) return terminalSummary;
        return new Snapshot(lifecycle.snapshot(), eligibleTicks, verifyTicks, Optional.ofNullable(entry),
                ledger == null ? List.of() : unfinishedIds(), ledger == null ? Set.of() : ledger.pendingTargetIds(),
                Optional.empty(), effectResults);
    }

    public TickDecision tick(TickInput input) {
        Objects.requireNonNull(input, "input");
        var state = lifecycle.snapshot();
        // Routing identity precedes live binding: an old callback cannot cancel a newer run.
        if ((state.phase() != Phase.RUNNING && state.phase() != Phase.PAUSED)
                || !state.context().orElseThrow().runId().equals(input.runId()) || input.tick() <= lastTick) {
            return decision(Optional.empty(), input.tick(), Optional.empty(), Optional.empty());
        }
        lastTick = input.tick();
        if (input.binding().isEmpty() || !state.context().orElseThrow().equals(input.binding().orElseThrow())) {
            finish(Outcome.FAILED, Reason.BINDING_LOST, unresolvedIds(), "Current connection/world/context binding was lost");
            return currentDecision(input.tick(), Optional.empty(), Optional.empty());
        }
        Map<Integer, PlacementObservation> current = routeObservations(input);
        effectResults = correlatedResults(input.effectResults());
        if (state.phase() == Phase.PAUSED) return currentDecision(input.tick(), Optional.empty(), Optional.empty());

        eligibleTicks++;
        boolean wasVerifying = state.activity().orElseThrow() == Activity.VERIFY;
        if (wasVerifying) verifyTicks++;
        Optional<MovementIntent> movement = Optional.empty();
        if (!wasVerifying && input.player().isPresent()) {
            PlayerObservation player = input.player().orElseThrow();
            LaneEntry currentEntry = new LaneEntry(entry.lane(), entry.stage(), entry.stageElapsedTicks(),
                    entry.entryElapsedTicks(), entry.consecutiveYawAlignedTicks(), entry.supportHoldTicks(),
                    entry.yawHoldTicks(), ledger.pendingTargetIds(), entry.constantSprint());
            MovementIntent result = movementPolicy.decide(player, currentEntry, input.support());
            if (state.activity().orElseThrow() == Activity.APPROACH) {
                // The single policy result owns the local envelope, not physical staging safety.
                if (result.failure() == Failure.BELOW_BUILD_PLANE || result.failure() == Failure.OUTSIDE_CORRIDOR) {
                    finish(Outcome.FAILED, Reason.COARSE_TRAVEL_UNAVAILABLE, unresolvedIds(), "Native entry requires unavailable coarse travel");
                    return currentDecision(input.tick(), Optional.empty(), Optional.empty());
                }
                lifecycle.updateActivity(input.runId(), Activity.BUILD);
            }
            // Store every returned counter once. Evidence/issuance never resets 6b's independent clocks.
            entry = new LaneEntry(entry.lane(), result.nextStage(), result.nextStageElapsedTicks(),
                    result.nextEntryElapsedTicks(), result.nextYawAlignedTicks(), result.nextSupportHoldTicks(),
                    result.nextYawHoldTicks(), ledger.pendingTargetIds(), entry.constantSprint());
            if (result.status() == Status.FAILED) {
                finish(Outcome.FAILED, Reason.MOVEMENT_FAILED, unresolvedIds(), result.failure().name());
                return currentDecision(input.tick(), Optional.empty(), Optional.empty());
            }
            if (result.status() == Status.COMPLETE) {
                lifecycle.updateActivity(input.runId(), Activity.VERIFY); verifyEnteredTick = input.tick();
            } else movement = Optional.of(result);
        }

        boolean verifying = lifecycle.snapshot().activity().orElseThrow() == Activity.VERIFY;
        boolean fullScan = current.size() == plan.targets().size()
                && input.observations().size() == plan.targets().size()
                && plan.targets().stream().allMatch(t -> current.get(t.id()).evidence() == Evidence.OBSERVED
                && current.get(t.id()).observedState().orElseThrow().equals(t.block()));
        if (verifying && input.tick() > verifyEnteredTick && fullScan
                && ledger.outcome() == PlacementLedger.Outcome.ALL_CONFIRMED) {
            finish(Outcome.SUCCESS, Reason.VERIFIED, List.of(), "Later current full-plan OBSERVED scan matched every expected state");
            return currentDecision(input.tick(), Optional.empty(), Optional.empty());
        }

        // Genuine observations are already routed. Failure precedence is lifecycle, 6b, pending,
        // VERIFY, total-run; exhausted attempts are resolved accounting, never a fourth issuance.
        for (int id : ledger.pendingTargetIds()) {
            var attempt = ledger.latestAttempt(id).orElseThrow();
            if (attempt.issuedEligibleTick().isEmpty()) return invariantFailure(input.tick());
            if (eligibleTicks - attempt.issuedEligibleTick().getAsLong() >= PENDING_LIMIT) {
                finish(Outcome.FAILED, Reason.PENDING_EVIDENCE_TIMEOUT, unresolvedIds(), "Pending evidence reached eligible age " + PENDING_LIMIT);
                return currentDecision(input.tick(), Optional.empty(), Optional.empty());
            }
        }
        if (verifying && verifyTicks >= VERIFY_LIMIT) {
            finish(Outcome.FAILED, Reason.VERIFY_TIMEOUT, finalMissingIds(current), "No valid full current final scan within " + VERIFY_LIMIT + " VERIFY callbacks");
            return currentDecision(input.tick(), Optional.empty(), Optional.empty());
        }
        if (eligibleTicks >= RUN_LIMIT) {
            finish(Outcome.FAILED, Reason.RUN_TIMEOUT, unresolvedIds(), "Run reached " + RUN_LIMIT + " active callbacks");
            return currentDecision(input.tick(), Optional.empty(), Optional.empty());
        }
        for (Target target : ledger.unfinishedTargets()) {
            var attempt = ledger.latestAttempt(target.id());
            if (attempt.isPresent() && attempt.orElseThrow().issuedEligibleTick().isEmpty()) return invariantFailure(input.tick());
            var sample = current.get(target.id());
            if (attempt.isPresent() && !attempt.orElseThrow().awaitingObservation() && attempt.orElseThrow().count() >= MAX_ATTEMPTS
                    && sample != null && sample.evidence() == Evidence.OBSERVED
                    && !sample.observedState().orElseThrow().equals(target.block())) {
                finish(Outcome.FAILED, Reason.ATTEMPTS_EXHAUSTED, unresolvedIds(), "A resolved target exhausted " + MAX_ATTEMPTS + " attempts");
                return currentDecision(input.tick(), Optional.empty(), Optional.empty());
            }
        }
        Optional<PlacementIntent> placement = Optional.empty();
        if (input.player().isPresent()) {
            PlayerObservation player = input.player().orElseThrow();
            Optional<Target> selected = ledger.unfinishedTargets().stream()
                    .filter(t -> input.availability().get(t.id()) == PlacementAvailability.READY)
                    .filter(t -> retryReady(t, current))
                    .filter(t -> verifying || bucket(t, player) < 3)
                    .min(verifying ? Comparator.comparingDouble((Target t) -> distance(t, player)).thenComparingInt(Target::id)
                            : Comparator.comparingInt((Target t) -> bucket(t, player))
                            .thenComparingDouble(t -> lateral(t)).thenComparingInt(Target::id));
            if (selected.isPresent()) {
                int id = selected.orElseThrow().id(); long action = nextActionId++;
                if (!ledger.recordIssuedAttempt(input.runId(), action, id, input.tick(), eligibleTicks)) return invariantFailure(input.tick());
                placement = Optional.of(new PlacementIntent(input.runId(), action, id, input.tick()));
            }
        }
        return currentDecision(input.tick(), movement, placement);
    }

    private Map<Integer, PlacementObservation> routeObservations(TickInput input) {
        Map<Integer, Long> counts = new HashMap<>();
        for (var o : input.observations()) counts.merge(o.targetId(), 1L, Long::sum);
        Map<Integer, PlacementObservation> accepted = new HashMap<>();
        for (var o : input.observations()) {
            if (counts.get(o.targetId()) == 1 && o.runId().equals(input.runId()) && o.tick() == input.tick()
                    && entry.lane().expectedFloor().containsTargetId(o.targetId()) && ledger.observe(o)) accepted.put(o.targetId(), o);
        }
        return accepted;
    }

    private List<PlacementEffectResult> correlatedResults(List<PlacementEffectResult> results) {
        UUID runId = lifecycle.snapshot().context().orElseThrow().runId();
        return results.stream().filter(r -> r.runId().equals(runId)
                && entry.lane().expectedFloor().containsTargetId(r.targetId()))
                .filter(r -> ledger.latestAttempt(r.targetId()).filter(a -> a.actionId() == r.actionId()
                        && a.tick() == r.tick()).isPresent()).distinct().toList();
    }

    private boolean retryReady(Target target, Map<Integer, PlacementObservation> current) {
        var attempt = ledger.latestAttempt(target.id());
        if (attempt.isEmpty()) return true;
        var prior = attempt.orElseThrow();
        var sample = current.get(target.id());
        return !prior.awaitingObservation() && prior.count() < MAX_ATTEMPTS
                && eligibleTicks - prior.issuedEligibleTick().orElseThrow() >= RETRY_SPACING
                && sample != null && sample.evidence() == Evidence.OBSERVED
                && !sample.observedState().orElseThrow().equals(target.block());
    }

    private int bucket(Target target, PlayerObservation player) {
        var direction = entry.lane().direction();
        double delta = (direction.progressCoordinate(target.relativePos().x(), target.relativePos().z())
                - Math.floor(direction.progressCoordinate(player.x(), player.z()))) * direction.forwardSign();
        return delta == 0 ? 0 : delta == 1 ? 1 : delta == -1 ? 2 : 3;
    }

    private double lateral(Target target) {
        var direction = entry.lane().direction();
        return direction.lateralCoordinate(target.relativePos().x(), target.relativePos().z()) * direction.lateralStrafeSign();
    }

    private double distance(Target target, PlayerObservation player) {
        double dx = target.relativePos().x() + .5 - player.x(), dz = target.relativePos().z() + .5 - player.z();
        return dx * dx + dz * dz;
    }

    private List<Integer> finalMissingIds(Map<Integer, PlacementObservation> current) {
        List<Integer> ids = plan.targets().stream().filter(t -> {
            var sample = current.get(t.id());
            return sample == null || sample.evidence() != Evidence.OBSERVED || !sample.observedState().orElseThrow().equals(t.block());
        }).map(Target::id).sorted().toList();
        return ids.isEmpty() ? allIds() : ids;
    }

    private List<Integer> unfinishedIds() { return ledger.unfinishedTargets().stream().map(Target::id).sorted().toList(); }
    private List<Integer> allIds() { return plan.targets().stream().map(Target::id).sorted().toList(); }
    private List<Integer> unresolvedIds() { var ids = unfinishedIds(); return ids.isEmpty() ? allIds() : ids; }

    private TickDecision invariantFailure(long tick) {
        finish(Outcome.FAILED, Reason.INVARIANT_FAILURE, unresolvedIds(), "Engine-issued attempt lacks valid ledger issuance metadata");
        return currentDecision(tick, Optional.empty(), Optional.empty());
    }

    private void finish(Outcome outcome, Reason reason, List<Integer> ids, String detail) {
        // Invalidate routing first, capture immutable diagnostics, then discard mutable owners.
        // The adapter must invalidate its captured generation and release controls before effects.
        lifecycle.stop(outcome);
        terminalSummary = new Snapshot(lifecycle.snapshot(), eligibleTicks, verifyTicks, Optional.empty(),
                unfinishedIds(), Set.of(), Optional.of(new Diagnostic(reason, ids, detail)), effectResults);
        entry = null; ledger = null; plan = null; settings = null;
    }

    private TickDecision currentDecision(long tick, Optional<MovementIntent> movement, Optional<PlacementIntent> placement) {
        return decision(lifecycle.snapshot().context().map(Context::runId), tick, movement, placement);
    }

    private TickDecision decision(Optional<UUID> runId, long tick, Optional<MovementIntent> movement, Optional<PlacementIntent> placement) {
        return new TickDecision(runId, tick, movement.isPresent() ? ControlOwner.NATIVE : ControlOwner.NONE,
                movement, placement, snapshot());
    }

    public enum PlacementAvailability { READY, OUT_OF_REACH, NO_USABLE_ITEM, NO_VALID_HIT, UNSUPPORTED }
    public enum EffectStatus { ACCEPTED, REJECTED, UNAVAILABLE, STALE }
    public enum ControlOwner { NONE, NATIVE, TRAVEL }
    public enum Reason { NOTHING_TO_BUILD, UNSUPPORTED_PLAN, COARSE_TRAVEL_UNAVAILABLE, BINDING_LOST,
        MOVEMENT_FAILED, PENDING_EVIDENCE_TIMEOUT, ATTEMPTS_EXHAUSTED, VERIFY_TIMEOUT, RUN_TIMEOUT,
        CANCELLED, VERIFIED, INVARIANT_FAILURE }

    /** Reserved slots and sprint belong to the same immutable adapter/run settings snapshot. */
    public record Settings(int halfWidth, int stride, boolean constantSprint, int reservedHotbarSlots) {
        public Settings {
            if (halfWidth < 0 || stride <= 0 || stride > 2L * halfWidth + 1
                    || reservedHotbarSlots < 0 || reservedHotbarSlots > 8) throw new IllegalArgumentException("Invalid lane geometry or reserved slots");
        }
        public static Settings defaults() { return new Settings(2, 5, true, 0); }
        public String fingerprint() {
            return "halfWidth=" + halfWidth + ";stride=" + stride + ";constantSprint=" + constantSprint
                    + ";reservedHotbarSlots=" + reservedHotbarSlots;
        }
    }

    /** runId routes the callback; binding independently describes the currently live world context. */
    public record TickInput(UUID runId, long tick, Optional<Context> binding, Optional<PlayerObservation> player,
                            SupportObservation support, List<PlacementObservation> observations,
                            Map<Integer, PlacementAvailability> availability, List<PlacementEffectResult> effectResults) {
        public TickInput {
            Objects.requireNonNull(runId, "runId"); Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(player, "player"); Objects.requireNonNull(support, "support");
            if (tick < 0) throw new IllegalArgumentException("Tick must be nonnegative");
            observations = List.copyOf(observations); availability = Map.copyOf(availability); effectResults = List.copyOf(effectResults);
        }
    }
    public record PlacementIntent(UUID runId, long actionId, int targetId, long tick) {
        public PlacementIntent { requireIdentity(runId, actionId, targetId, tick); }
    }
    public record PlacementEffectResult(UUID runId, long actionId, int targetId, long tick, EffectStatus status) {
        public PlacementEffectResult { requireIdentity(runId, actionId, targetId, tick); Objects.requireNonNull(status, "status"); }
    }
    /** Empty runId is an ignored callback, not a current-run request to release controls. */
    public record TickDecision(Optional<UUID> runId, long tick, ControlOwner owner, Optional<MovementIntent> movement,
                               Optional<PlacementIntent> placement, Snapshot snapshot) { }
    public record Snapshot(RunLifecycle.Snapshot lifecycle, long eligibleTicks, long verifyTicks, Optional<LaneEntry> laneEntry,
                           List<Integer> unfinishedTargetIds, Set<Integer> pendingTargetIds, Optional<Diagnostic> diagnostic,
                           List<PlacementEffectResult> effectResults) {
        public Snapshot {
            Objects.requireNonNull(lifecycle, "lifecycle"); Objects.requireNonNull(laneEntry, "laneEntry");
            Objects.requireNonNull(diagnostic, "diagnostic");
            unfinishedTargetIds = List.copyOf(unfinishedTargetIds); pendingTargetIds = Set.copyOf(pendingTargetIds);
            effectResults = List.copyOf(effectResults);
        }
    }
    public record Diagnostic(Reason reason, List<Integer> targetIds, String detail) {
        public Diagnostic {
            Objects.requireNonNull(reason, "reason"); Objects.requireNonNull(detail, "detail"); targetIds = List.copyOf(targetIds);
        }
    }
    private static void requireIdentity(UUID runId, long actionId, int targetId, long tick) {
        Objects.requireNonNull(runId, "runId");
        if (actionId < 0 || targetId < 0 || tick < 0) throw new IllegalArgumentException("Effect identity must be nonnegative");
    }
}
