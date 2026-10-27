package com.mapartrunner.core.run;

import com.mapartrunner.core.model.BuildPlan.GridPos;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Coordinator-owned lifecycle only; target/traversal owners retain their own paused data. */
public final class RunLifecycle {
    // Retained for this instance's lifetime, including terminal/new-start transitions. This grows
    // by one UUID per start so an old callback can never become valid through ID reuse.
    private final Set<UUID> usedRunIds = new HashSet<>();
    private Snapshot state = new Snapshot(Phase.IDLE, Optional.empty(), Optional.empty(), Optional.empty());

    public void start(Context context) {
        Objects.requireNonNull(context, "context");
        if (state.phase() != Phase.IDLE && state.phase() != Phase.TERMINAL) {
            throw new IllegalStateException("Start requires IDLE or TERMINAL");
        }
        if (!usedRunIds.add(context.runId())) {
            throw new IllegalArgumentException("A new run requires a fresh UUID");
        }
        // Replace all prior terminal data together; a new run always enters APPROACH.
        state = new Snapshot(Phase.RUNNING, Optional.of(context), Optional.of(Activity.APPROACH), Optional.empty());
    }

    public void pause() {
        requirePhase(Phase.RUNNING);
        // Preserve binding and activity. The later engine releases effects; this class owns none.
        state = new Snapshot(Phase.PAUSED, state.context(), state.activity(), Optional.empty());
    }

    public void resume(Context context) {
        Objects.requireNonNull(context, "context");
        requirePhase(Phase.PAUSED);
        if (!state.context().orElseThrow().equals(context)) {
            throw new IllegalArgumentException("Changed run/world binding requires a fresh scan and a fresh run");
        }
        state = new Snapshot(Phase.RUNNING, state.context(), state.activity(), Optional.empty());
    }

    /** Owner-command transition; callers accepting asynchronous results must correlate run IDs first. */
    public void stop(Outcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (state.phase() == Phase.TERMINAL) {
            return;
        }
        if (state.phase() == Phase.IDLE) {
            throw new IllegalStateException("Stop requires a started run");
        }
        // Clear resumable activity before exposing terminal state; keep the first result and binding
        // for diagnostics. The engine must separately clear attempts/recovery and release controls.
        state = new Snapshot(Phase.TERMINAL, state.context(), Optional.empty(), Optional.of(outcome));
    }

    /** Inactive or prior-run results are rejected without changing any lifecycle state. */
    public boolean updateActivity(UUID runId, Activity activity) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(activity, "activity");
        if (state.phase() != Phase.RUNNING || !state.context().orElseThrow().runId().equals(runId)) {
            return false;
        }
        state = new Snapshot(Phase.RUNNING, state.context(), Optional.of(activity), Optional.empty());
        return true;
    }

    public Snapshot snapshot() {
        return state;
    }

    private void requirePhase(Phase phase) {
        if (state.phase() != phase) {
            throw new IllegalStateException("Expected " + phase + ", found " + state.phase());
        }
    }

    public enum Phase { IDLE, RUNNING, PAUSED, TERMINAL }

    public enum Activity { APPROACH, BUILD, SHIFT, REFILL, RECOVER, VERIFY }

    public enum Outcome { SUCCESS, PARTIAL, FAILED, CANCELLED }

    /** connectionId must identify the connection session, including a rejoin to the same world. */
    public record Context(UUID runId, String connectionId, String worldId, String dimension,
                          String planFingerprint, GridPos origin, String settingsFingerprint) {
        public Context {
            Objects.requireNonNull(runId, "runId");
            requireText(connectionId, "connectionId");
            requireText(worldId, "worldId");
            requireText(dimension, "dimension");
            requireText(planFingerprint, "planFingerprint");
            Objects.requireNonNull(origin, "origin");
            requireText(settingsFingerprint, "settingsFingerprint");
        }
    }

    /** Activity is retained while PAUSED for resume, and absent in IDLE/TERMINAL. */
    public record Snapshot(Phase phase, Optional<Context> context, Optional<Activity> activity,
                           Optional<Outcome> outcome) {
        public Snapshot {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(activity, "activity");
            Objects.requireNonNull(outcome, "outcome");
            boolean valid = switch (phase) {
                case IDLE -> context.isEmpty() && activity.isEmpty() && outcome.isEmpty();
                case RUNNING, PAUSED -> context.isPresent() && activity.isPresent() && outcome.isEmpty();
                case TERMINAL -> context.isPresent() && activity.isEmpty() && outcome.isPresent();
            };
            if (!valid) {
                throw new IllegalArgumentException("Snapshot fields do not match phase " + phase);
            }
        }
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
