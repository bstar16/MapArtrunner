package com.mapartrunner.fabric.scenario.threading;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

class PacketHandlerAwaitTest {
    @Test
    void completionSignalledBeforeLockAcquisitionDoesNotWait() throws Exception {
        signalBeforeAwait(false);
    }

    @Test
    void queuedHandlerSignalledBeforeLockAcquisitionRemainsForOwner() throws Exception {
        signalBeforeAwait(true);
    }

    private void signalBeforeAwait(boolean queuedHandler) throws Exception {
        var lock = new ReentrantLock();
        var condition = lock.newCondition();
        var awaitCalls = new AtomicInteger();
        var countedCondition = timed((timeout, unit) -> {
            awaitCalls.incrementAndGet();
            return condition.await(timeout, unit);
        });
        var inFlight = new AtomicInteger(1);
        var handlers = new ArrayDeque<Runnable>();
        var signalled = new CountDownLatch(1);
        assertTrue(inFlight.get() > 0 && handlers.isEmpty(), "original pre-lock check");
        var producer = Thread.ofPlatform().start(() -> {
            lock.lock();
            try {
                if (queuedHandler) handlers.add(() -> {});
                else inFlight.set(0);
                condition.signal();
                signalled.countDown();
            } finally {
                lock.unlock();
            }
        });
        try {
            assertTrue(signalled.await(2, TimeUnit.SECONDS));
            lock.lock();
            try {
                assertTrue(PacketHandlerAwait.await(countedCondition, 0, TimeUnit.NANOSECONDS,
                        inFlight::get, handlers::isEmpty, () -> false));
                assertEquals(0, awaitCalls.get(), "already-signalled progress must skip the wait");
                assertEquals(queuedHandler ? 1 : 0, handlers.size());
                assertEquals(queuedHandler ? 1 : 0, inFlight.get());
            } finally {
                lock.unlock();
            }
        } finally {
            producer.join(2_000);
            assertFalse(producer.isAlive());
        }
    }

    @Test
    void completionAfterFalseAwaitIsRechecked() throws Exception {
        var inFlight = new AtomicInteger(1);
        assertTrue(PacketHandlerAwait.await(timed((timeout, unit) -> {
            inFlight.set(0);
            return false;
        }), 10, TimeUnit.SECONDS, inFlight::get, () -> true, () -> false));
    }

    @Test
    void handlerArrivalAfterFalseAwaitRemainsForOwner() throws Exception {
        var handlers = new ArrayDeque<Runnable>();
        assertTrue(PacketHandlerAwait.await(timed((timeout, unit) -> {
            handlers.add(() -> {});
            return false;
        }), 10, TimeUnit.SECONDS, () -> 1, handlers::isEmpty, () -> false));
        assertEquals(1, handlers.size());
    }

    @Test
    void genuinePendingTimeoutAndOriginalBoundAreUnchanged() throws Exception {
        var calls = new AtomicInteger();
        assertFalse(PacketHandlerAwait.await(timed((timeout, unit) -> {
            calls.incrementAndGet();
            assertEquals(10, timeout);
            assertSame(TimeUnit.SECONDS, unit);
            return false;
        }), 10, TimeUnit.SECONDS, () -> 1, () -> true, () -> false));
        assertEquals(1, calls.get());
    }

    @Test
    void ordinarySignalledWakeLeavesQueuedWorkToOwner() throws Exception {
        var handlers = new ArrayDeque<Runnable>();
        var ran = new AtomicBoolean();
        assertTrue(PacketHandlerAwait.await(timed((timeout, unit) -> {
            handlers.add(() -> ran.set(true));
            return true;
        }), 10, TimeUnit.SECONDS, () -> 1, handlers::isEmpty, () -> false));
        assertFalse(ran.get());
        assertEquals(1, handlers.size());
        handlers.remove().run();
        assertTrue(ran.get());
    }

    @Test
    void spuriousSignalledWakeIsStillRecheckedByOriginalLoop() throws Exception {
        assertTrue(PacketHandlerAwait.await(timed((timeout, unit) -> true),
                10, TimeUnit.SECONDS, () -> 1, () -> true, () -> false));
    }

    @Test
    void invalidOrNegativeStateCannotProduceSyntheticProgress() throws Exception {
        var condition = timed((timeout, unit) -> false);
        assertFalse(PacketHandlerAwait.await(condition, 10, TimeUnit.SECONDS,
                () -> 0, () -> false, () -> true));
        assertFalse(PacketHandlerAwait.await(condition, 10, TimeUnit.SECONDS,
                () -> -1, () -> false, () -> false));
        var invalid = new AtomicBoolean();
        var inFlight = new AtomicInteger(1);
        assertFalse(PacketHandlerAwait.await(timed((timeout, unit) -> {
            inFlight.set(0);
            invalid.set(true);
            return false;
        }), 10, TimeUnit.SECONDS, inFlight::get, () -> true, invalid::get));
        inFlight.set(1);
        assertFalse(PacketHandlerAwait.await(timed((timeout, unit) -> {
            inFlight.set(-1);
            return false;
        }), 10, TimeUnit.SECONDS, inFlight::get, () -> true, () -> false));
    }

    @Test
    void originalAwaitInterruptionIsPropagated() {
        var interrupted = new InterruptedException("original await");
        assertSame(interrupted, assertThrows(InterruptedException.class,
                () -> PacketHandlerAwait.await(timed((timeout, unit) -> { throw interrupted; }),
                        10, TimeUnit.SECONDS, () -> 1, () -> true, () -> false)));
    }

    @Test
    void originalAwaitFatalFailuresArePropagated() {
        var runtime = new IllegalStateException("original await");
        assertSame(runtime, assertThrows(IllegalStateException.class,
                () -> PacketHandlerAwait.await(timed((timeout, unit) -> { throw runtime; }),
                        10, TimeUnit.SECONDS, () -> 1, () -> true, () -> false)));
        var error = new AssertionError("original await");
        assertSame(error, assertThrows(AssertionError.class,
                () -> PacketHandlerAwait.await(timed((timeout, unit) -> { throw error; }),
                        10, TimeUnit.SECONDS, () -> 1, () -> true, () -> false)));
    }

    @Test
    void alreadyInterruptedThreadCannotAcceptSyntheticProgress() {
        var lock = new ReentrantLock();
        lock.lock();
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, () -> PacketHandlerAwait.await(lock.newCondition(),
                    10, TimeUnit.SECONDS, () -> 0, () -> true, () -> false));
            assertFalse(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            lock.unlock();
        }
    }

    @FunctionalInterface
    private interface TimedAwait {
        boolean await(long timeout, TimeUnit unit) throws InterruptedException;
    }

    private static Condition timed(TimedAwait action) {
        return new Condition() {
            @Override public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
                return action.await(timeout, unit);
            }
            @Override public void await() { throw new AssertionError("unexpected await"); }
            @Override public void awaitUninterruptibly() { throw new AssertionError("unexpected await"); }
            @Override public long awaitNanos(long nanos) { throw new AssertionError("unexpected await"); }
            @Override public boolean awaitUntil(Date deadline) { throw new AssertionError("unexpected await"); }
            @Override public void signal() { throw new AssertionError("unexpected signal"); }
            @Override public void signalAll() { throw new AssertionError("unexpected signal"); }
        };
    }
}
