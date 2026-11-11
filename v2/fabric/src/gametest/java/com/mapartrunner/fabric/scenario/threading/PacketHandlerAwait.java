package com.mapartrunner.fabric.scenario.threading;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** Called with Fabric's condition lock held; Fabric still owns draining and failure handling. */
public final class PacketHandlerAwait {
    private PacketHandlerAwait() {}

    public static boolean await(Condition condition, long timeout, TimeUnit unit,
                                IntSupplier inFlight, BooleanSupplier handlersEmpty,
                                BooleanSupplier invalid) throws InterruptedException {
        // Preserve Condition's interruption behavior even when an obsolete wait can be avoided.
        if (Thread.interrupted()) throw new InterruptedException();
        int packets = inFlight.getAsInt();
        if (invalid.getAsBoolean() || packets < 0) return false;
        if (packets == 0 || !handlersEmpty.getAsBoolean()) return true;

        // A real (possibly spurious) wake still belongs to Fabric's original predicate loop.
        if (condition.await(timeout, unit)) return true;
        packets = inFlight.getAsInt();
        return !invalid.getAsBoolean() && packets >= 0
                && (packets == 0 || !handlersEmpty.getAsBoolean());
    }
}
