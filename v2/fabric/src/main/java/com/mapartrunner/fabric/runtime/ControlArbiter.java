package com.mapartrunner.fabric.runtime;

import com.mapartrunner.core.movement.MovementPolicy;
import com.mapartrunner.core.run.BuildEngine.ControlOwner;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.StickyKeyBinding;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Client-thread owner of automation keys and the sole effect-generation authority.
 * The adapter captures Binding before snapshot/core work; delayed callbacks must never refresh it.
 * Before every effect it also checks RUNNING, actual world/session/run identity and unapplied tick.
 * Only trusted start/resume paths bind; pause/stop/terminal/world loss invalidate before disposal.
 * Pause keeps core data, not these tokens. A key lease alone cannot authorize yaw/slot/placement.
 */
public final class ControlArbiter {
    private final Sink sink;
    private long generation;
    private Binding binding;
    private Lease lease;

    public ControlArbiter(Sink sink) { this.sink = Objects.requireNonNull(sink, "sink"); }

    public static ControlArbiter forClient(MinecraftClient client) {
        return new ControlArbiter(new MinecraftSink(Objects.requireNonNull(client, "client")));
    }

    /** Trusted lifecycle activation only, never a decision/callback operation. */
    public Binding bindRun(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        Lease previous = lease;
        Throwable failure = revoke(null);
        if (previous != null) failure = releaseOwned(previous.owner, failure);
        if (failure != null) throw propagate(failure);
        binding = new Binding(runId, generation);
        return binding;
    }

    /** Revoke first so cleanup, including a throwing sink, cannot leave old callbacks authorized. */
    public void invalidate() {
        Lease previous = lease;
        Throwable failure = revoke(null);
        if (previous != null) failure = releaseOwned(previous.owner, failure);
        if (failure != null) throw propagate(failure);
    }

    public boolean isCurrent(Binding candidate) {
        return binding == Objects.requireNonNull(candidate, "binding");
    }

    /** Logical ownership; a throwing release is propagated, not a claim of physical key release. */
    public ControlOwner owner() { return lease == null ? ControlOwner.NONE : lease.owner; }

    public AcquireResult acquire(Binding captured, ControlOwner requestedOwner) {
        Objects.requireNonNull(captured, "binding");
        Objects.requireNonNull(requestedOwner, "requestedOwner");
        if (requestedOwner == ControlOwner.NONE) throw new IllegalArgumentException("NONE must release its lease");
        if (!isCurrent(captured)) return new AcquireResult(AcquireStatus.STALE, Optional.empty());
        if (lease != null && lease.owner == requestedOwner) {
            return new AcquireResult(AcquireStatus.ACQUIRED, Optional.of(lease));
        }

        // Detach old ownership before cleanup; never acquire the replacement on release failure.
        Lease previous = lease;
        lease = null;
        if (previous != null) {
            Throwable failure = releaseOwned(previous.owner, null);
            if (failure != null) throw propagate(failure);
        }
        try {
            if (!sink.acquire(requestedOwner)) return new AcquireResult(AcquireStatus.UNAVAILABLE, Optional.empty());
            lease = new Lease(captured, requestedOwner);
            return new AcquireResult(AcquireStatus.ACQUIRED, Optional.of(lease));
        } catch (RuntimeException | Error failure) {
            // A backend can throw after becoming active, before a lease was returned.
            throw propagate(releaseOwned(requestedOwner, revoke(failure)));
        }
    }

    public boolean apply(Lease captured, ControlMask mask) {
        Objects.requireNonNull(captured, "lease");
        Objects.requireNonNull(mask, "mask");
        if (!owns(captured) || captured.owner != ControlOwner.NATIVE) return false;
        try {
            for (Control control : Control.values()) sink.setPressed(control, mask.pressed(control));
            return true;
        } catch (RuntimeException | Error failure) {
            throw propagate(releaseOwned(captured.owner, revoke(failure)));
        }
    }

    public boolean release(Lease captured) {
        Objects.requireNonNull(captured, "lease");
        if (!owns(captured)) return false;
        lease = null;
        Throwable failure = releaseOwned(captured.owner, null);
        if (failure != null) throw propagate(failure);
        return true;
    }

    private boolean owns(Lease captured) { return lease == captured && binding == captured.binding; }

    private Throwable revoke(Throwable failure) {
        binding = null;
        lease = null;
        try {
            generation = Math.incrementExact(generation);
        } catch (ArithmeticException overflow) {
            failure = retain(failure, overflow);
        }
        return failure;
    }

    private Throwable releaseOwned(ControlOwner owner, Throwable failure) {
        // Keep the detached owner locally until every key and backend cleanup has been attempted.
        for (Control control : Control.values()) {
            try {
                sink.setPressed(control, false);
            } catch (RuntimeException | Error cleanup) {
                failure = failure == null ? revoke(cleanup) : retain(failure, cleanup);
            }
        }
        try {
            sink.release(owner);
        } catch (RuntimeException | Error cleanup) {
            failure = failure == null ? revoke(cleanup) : retain(failure, cleanup);
        }
        return failure;
    }

    private static Throwable retain(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) {
            for (Throwable suppressed : first.getSuppressed()) if (suppressed == next) return first;
            first.addSuppressed(next);
        }
        return first;
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof Error error) throw error;
        return (RuntimeException) failure;
    }

    public enum Control { FORWARD, BACK, LEFT, RIGHT, JUMP, SNEAK, SPRINT, USE, ATTACK }

    public record ControlMask(boolean forward, boolean back, boolean left, boolean right,
                              boolean jump, boolean sneak, boolean sprint, boolean use, boolean attack) {
        public static ControlMask none() {
            return new ControlMask(false, false, false, false, false, false, false, false, false);
        }

        public static ControlMask fromMovement(MovementPolicy.MovementIntent movement) {
            Objects.requireNonNull(movement, "movement");
            return new ControlMask(movement.forward(), movement.back(),
                    movement.strafe() == MovementPolicy.Strafe.LEFT, movement.strafe() == MovementPolicy.Strafe.RIGHT,
                    false, false, movement.sprint(), false, false);
        }

        private boolean pressed(Control control) {
            return switch (control) {
                case FORWARD -> forward;
                case BACK -> back;
                case LEFT -> left;
                case RIGHT -> right;
                case JUMP -> jump;
                case SNEAK -> sneak;
                case SPRINT -> sprint;
                case USE -> use;
                case ATTACK -> attack;
            };
        }
    }

    public static final class Binding {
        private final UUID runId;
        private final long generation;

        private Binding(UUID runId, long generation) { this.runId = runId; this.generation = generation; }
        public UUID runId() { return runId; }
        public long generation() { return generation; }
    }

    public static final class Lease {
        private final Binding binding;
        private final ControlOwner owner;

        private Lease(Binding binding, ControlOwner owner) { this.binding = binding; this.owner = owner; }
        public UUID runId() { return binding.runId; }
        public long generation() { return binding.generation; }
        public ControlOwner owner() { return owner; }
    }

    public enum AcquireStatus { ACQUIRED, STALE, UNAVAILABLE }

    public record AcquireResult(AcquireStatus status, Optional<Lease> lease) {
        public AcquireResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(lease, "lease");
            if ((status == AcquireStatus.ACQUIRED) != lease.isPresent()) {
                throw new IllegalArgumentException("Only ACQUIRED has a lease");
            }
        }
    }

    /** Client-thread sink: false acquire guarantees no new effects; a throw may be partially effective. */
    public interface Sink {
        boolean acquire(ControlOwner owner);
        void setPressed(Control control, boolean pressed);
        void release(ControlOwner owner);
    }

    static final class MinecraftSink implements Sink {
        private final MinecraftClient client;
        private ClientPlayerEntity sprintPlayer;

        private MinecraftSink(MinecraftClient client) { this.client = client; }

        @Override public boolean acquire(ControlOwner owner) {
            if (owner != ControlOwner.NATIVE) return false; // No production coarse-travel backend yet.
            sprintPlayer = client.player;
            return true;
        }

        @Override public void setPressed(Control control, boolean pressed) {
            KeyBinding key = switch (control) {
                case FORWARD -> client.options.forwardKey;
                case BACK -> client.options.backKey;
                case LEFT -> client.options.leftKey;
                case RIGHT -> client.options.rightKey;
                case JUMP -> client.options.jumpKey;
                case SNEAK -> client.options.sneakKey;
                case SPRINT -> client.options.sprintKey;
                case USE -> client.options.useKey;
                case ATTACK -> client.options.attackKey;
            };
            writeKey(key, pressed);
            if (control == Control.SPRINT && !pressed && sprintPlayer != null) sprintPlayer.setSprinting(false);
        }

        @Override public void release(ControlOwner owner) {
            try {
                // Retry independently of the SPRINT key write, including world-replacement cleanup.
                if (sprintPlayer != null) sprintPlayer.setSprinting(false);
            } finally {
                sprintPlayer = null;
            }
        }

        static void writeKey(KeyBinding key, boolean pressed) {
            // Owned false also consumes saved screen-close restoration, even when already untoggled.
            if (!pressed && key instanceof StickyKeyBinding sticky) sticky.shouldRestoreOnScreenClose();
            if (key.isPressed() != pressed) {
                key.setPressed(pressed);
                // Sticky toggle mode ignores false; flip the still-pressed binding once.
                if (!pressed && key.isPressed()) key.setPressed(true);
            }
        }
    }
}
