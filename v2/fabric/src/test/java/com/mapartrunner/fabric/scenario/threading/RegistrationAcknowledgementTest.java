package com.mapartrunner.fabric.scenario.threading;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RegistrationAcknowledgementTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private final List<Thread> threads = new ArrayList<>();

    @AfterEach
    void settleThreads() throws InterruptedException {
        for (Thread thread : threads) {
            thread.join(7_000);
            assertFalse(thread.isAlive(), "test worker did not settle: " + thread.getName());
        }
    }

    @Test
    void delayedRegistrationHoldsParentUntilMatchingCompletion() throws Exception {
        var acknowledgement = new RegistrationAcknowledgement();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var continuations = new AtomicInteger();
        var child = new TestThread(() -> assertEquals(17, acknowledgement.registerAndSignal(() -> {
            calls.incrementAndGet();
            entered.countDown();
            await(release);
            return 17;
        })));
        var parent = startParent(acknowledgement, child, TIMEOUT, continuations);
        try {
            await(entered);
            assertBlocked(parent);
            assertEquals(1, calls.get());
            assertEquals(0, continuations.get());
            release.countDown();
            completed(parent);
            completed(child);
            assertEquals(1, continuations.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void wrongThreadCannotAcknowledge() throws Exception {
        var acknowledgement = new RegistrationAcknowledgement();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var child = heldChild(acknowledgement, entered, release);
        var parent = startParent(acknowledgement, child, TIMEOUT, new AtomicInteger());
        try {
            await(entered);
            assertThrows(IllegalStateException.class, () -> acknowledgement.registerAndSignal(calls::incrementAndGet));
            assertEquals(0, calls.get());
            assertBlocked(parent);
            release.countDown();
            completed(parent);
            completed(child);
        } finally {
            release.countDown();
        }
    }

    @Test
    void duplicateSignalFailsWithoutSecondRelease() throws Exception {
        var acknowledgement = new RegistrationAcknowledgement();
        var duplicated = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var continuations = new AtomicInteger();
        var child = new TestThread(() -> {
            acknowledgement.registerAndSignal(calls::incrementAndGet);
            try {
                acknowledgement.registerAndSignal(calls::incrementAndGet);
            } finally {
                duplicated.countDown();
            }
        }) {
            @Override
            public void start() {
                super.start();
                await(duplicated);
            }
        };
        var parent = startParent(acknowledgement, child, TIMEOUT, continuations);
        Throwable childFailure = failed(child);
        assertInstanceOf(IllegalStateException.class, childFailure);
        assertSame(childFailure, failed(parent));
        assertEquals(1, calls.get());
        assertEquals(0, continuations.get());
    }

    @Test
    void lateSignalCannotReleaseNewStart() throws Exception {
        var acknowledgement = new RegistrationAcknowledgement();
        var oldEntered = new CountDownLatch(1);
        var oldRelease = new CountDownLatch(1);
        var newEntered = new CountDownLatch(1);
        var newRelease = new CountDownLatch(1);
        var oldCalls = new AtomicInteger();
        var oldChild = new TestThread(() -> {
            oldEntered.countDown();
            await(oldRelease);
            acknowledgement.registerAndSignal(oldCalls::incrementAndGet);
        });
        var oldParent = startParent(acknowledgement, oldChild, Duration.ofMillis(200), new AtomicInteger());
        try {
            await(oldEntered);
            assertInstanceOf(IllegalStateException.class, failed(oldParent));
            var newChild = heldChild(acknowledgement, newEntered, newRelease);
            var newParent = startParent(acknowledgement, newChild, TIMEOUT, new AtomicInteger());
            await(newEntered);
            oldRelease.countDown();
            assertInstanceOf(IllegalStateException.class, failed(oldChild));
            assertEquals(0, oldCalls.get());
            assertBlocked(newParent);
            newRelease.countDown();
            completed(newParent);
            completed(newChild);
        } finally {
            oldRelease.countDown();
            newRelease.countDown();
        }
    }

    @Test
    void timeoutClosesTicketAndAllowsFreshStart() throws Exception {
        var acknowledgement = new RegistrationAcknowledgement();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var child = new TestThread(() -> {
            entered.countDown();
            await(release);
            acknowledgement.registerAndSignal(calls::incrementAndGet);
        });
        var parent = startParent(acknowledgement, child, Duration.ofMillis(200), new AtomicInteger());
        try {
            await(entered);
            assertInstanceOf(IllegalStateException.class, failed(parent));
            release.countDown();
            assertInstanceOf(IllegalStateException.class, failed(child));
            assertEquals(0, calls.get());
            freshStart(acknowledgement);
        } finally {
            release.countDown();
        }
    }

    @Test
    void registrationCompletingAfterTimeoutCannotAcknowledge() throws Exception {
        var acknowledgement = new RegistrationAcknowledgement();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var continuations = new AtomicInteger();
        var child = new TestThread(() -> acknowledgement.registerAndSignal(() -> {
            entered.countDown();
            await(release);
            return 0;
        }));
        var parent = startParent(acknowledgement, child, Duration.ofMillis(200), continuations);
        try {
            await(entered);
            assertInstanceOf(IllegalStateException.class, failed(parent));
            release.countDown();
            assertInstanceOf(IllegalStateException.class, failed(child));
            assertEquals(0, continuations.get());
            freshStart(acknowledgement);
        } finally {
            release.countDown();
        }
    }

    @Test
    void originalStartFailureIsPreserved() throws Exception {
        for (Throwable sentinel : List.of(new IllegalStateException("original start"), new AssertionError("original start"))) {
            var acknowledgement = new RegistrationAcknowledgement();
            var calls = new AtomicInteger();
            var child = new TestThread(() -> fail("must not run")) {
                @Override
                public void start() {
                    calls.incrementAndGet();
                    rethrow(sentinel);
                }
            };
            assertSame(sentinel, assertThrows(Throwable.class, () -> acknowledgement.startAndAwait(child, TIMEOUT)));
            assertEquals(1, calls.get());
            freshStart(acknowledgement);
        }
    }

    @Test
    void alreadyStartedThreadFailureClearsTicket() throws Exception {
        var acknowledgement = new RegistrationAcknowledgement();
        var child = new TestThread(() -> {});
        child.start();
        completed(child);
        assertThrows(IllegalThreadStateException.class, () -> acknowledgement.startAndAwait(child, TIMEOUT));
        freshStart(acknowledgement);
    }

    @Test
    void originalRegistrationFailureIsPreserved() throws Exception {
        for (Throwable sentinel : List.of(new IllegalArgumentException("original registration"), new AssertionError("original registration"))) {
            var acknowledgement = new RegistrationAcknowledgement();
            var calls = new AtomicInteger();
            var continuations = new AtomicInteger();
            var child = new TestThread(() -> acknowledgement.registerAndSignal(() -> {
                calls.incrementAndGet();
                rethrow(sentinel);
                return 0;
            }));
            var parent = startParent(acknowledgement, child, TIMEOUT, continuations);
            assertSame(sentinel, failed(parent));
            assertSame(sentinel, failed(child));
            assertEquals(1, calls.get());
            assertEquals(0, continuations.get());
            freshStart(acknowledgement);
        }
    }

    @Test
    void recordedRegistrationFailureSurvivesParentInterruption() throws Exception {
        var acknowledgement = new RegistrationAcknowledgement();
        var registered = new CountDownLatch(1);
        var sentinel = new AssertionError("original registration before interruption");
        var child = new TestThread(() -> {
            try {
                acknowledgement.registerAndSignal(() -> { throw sentinel; });
            } finally {
                registered.countDown();
            }
        }) {
            @Override
            public void start() {
                super.start();
                await(registered);
                Thread.currentThread().interrupt();
            }
        };
        var parent = startParent(acknowledgement, child, TIMEOUT, new AtomicInteger());
        assertSame(sentinel, failed(parent));
        assertSame(sentinel, failed(child));
        freshStart(acknowledgement);
    }

    @Test
    void interruptionRestoresFlagAndClosesTicket() throws Exception {
        var acknowledgement = new RegistrationAcknowledgement();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var child = heldChild(acknowledgement, entered, release);
        var parent = new TestThread(() -> {
            try {
                acknowledgement.startAndAwait(child, TIMEOUT);
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        parent.start();
        try {
            await(entered);
            parent.interrupt();
            Throwable failure = failed(parent);
            assertInstanceOf(IllegalStateException.class, failure);
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertTrue(interrupted.get());
            release.countDown();
            assertInstanceOf(IllegalStateException.class, failed(child));
            freshStart(acknowledgement);
        } finally {
            release.countDown();
        }
    }

    @Test
    void alreadyInterruptedParentCannotAcceptAcknowledgement() throws Exception {
        var acknowledgement = new RegistrationAcknowledgement();
        var registered = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var child = new TestThread(() -> {
            try {
                acknowledgement.registerAndSignal(() -> 0);
            } finally {
                registered.countDown();
            }
        }) {
            @Override
            public void start() {
                super.start();
                await(registered);
                Thread.currentThread().interrupt();
            }
        };
        var parent = new TestThread(() -> {
            try {
                acknowledgement.startAndAwait(child, TIMEOUT);
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        parent.start();
        Throwable failure = failed(parent);
        assertInstanceOf(IllegalStateException.class, failure);
        assertInstanceOf(InterruptedException.class, failure.getCause());
        assertTrue(interrupted.get());
        completed(child);
        freshStart(acknowledgement);
    }

    @Test
    void terminatedRegistrationCannotAcknowledge() throws Exception {
        var acknowledgement = new RegistrationAcknowledgement();
        var continuations = new AtomicInteger();
        var child = new TestThread(() -> acknowledgement.registerAndSignal(() -> -1));
        var parent = startParent(acknowledgement, child, TIMEOUT, continuations);
        Throwable failure = failed(child);
        assertInstanceOf(IllegalStateException.class, failure);
        assertSame(failure, failed(parent));
        assertEquals(0, continuations.get());
        freshStart(acknowledgement);
    }

    @Test
    void overlappingStartCannotReplaceTicket() throws Exception {
        var acknowledgement = new RegistrationAcknowledgement();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var child = heldChild(acknowledgement, entered, release);
        var parent = startParent(acknowledgement, child, TIMEOUT, new AtomicInteger());
        var otherChild = new TestThread(() -> fail("overlapping child must not start"));
        try {
            await(entered);
            assertThrows(IllegalStateException.class, () -> acknowledgement.startAndAwait(otherChild, TIMEOUT));
            assertEquals(Thread.State.NEW, otherChild.getState());
            assertBlocked(parent);
            release.countDown();
            completed(parent);
            completed(child);
        } finally {
            release.countDown();
        }
    }

    @Test
    void invalidInputsFailBeforeStarting() {
        var acknowledgement = new RegistrationAcknowledgement();
        var child = new TestThread(() -> fail("invalid input must not start"));
        assertThrows(NullPointerException.class, () -> acknowledgement.startAndAwait(null, TIMEOUT));
        assertThrows(NullPointerException.class, () -> acknowledgement.startAndAwait(child, null));
        for (Duration timeout : List.of(Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(5).plusNanos(1), Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThrows(IllegalArgumentException.class, () -> acknowledgement.startAndAwait(child, timeout));
        }
        assertEquals(Thread.State.NEW, child.getState());
        assertThrows(NullPointerException.class, () -> acknowledgement.registerAndSignal(null));
        var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> acknowledgement.registerAndSignal(calls::incrementAndGet));
        assertEquals(0, calls.get());
    }

    private TestThread heldChild(RegistrationAcknowledgement acknowledgement, CountDownLatch entered, CountDownLatch release) {
        return new TestThread(() -> {
            entered.countDown();
            await(release);
            acknowledgement.registerAndSignal(() -> 0);
        });
    }

    private TestThread startParent(RegistrationAcknowledgement acknowledgement, Thread child, Duration timeout, AtomicInteger continuations) {
        var parent = new TestThread(() -> {
            acknowledgement.startAndAwait(child, timeout);
            continuations.incrementAndGet();
        });
        parent.start();
        return parent;
    }

    private void freshStart(RegistrationAcknowledgement acknowledgement) throws Exception {
        var child = new TestThread(() -> assertEquals(23, acknowledgement.registerAndSignal(() -> 23)));
        acknowledgement.startAndAwait(child, TIMEOUT);
        completed(child);
    }

    private static void assertBlocked(TestThread thread) {
        assertThrows(TimeoutException.class, () -> thread.completion.get(100, TimeUnit.MILLISECONDS));
    }

    private static void completed(TestThread thread) throws Exception {
        thread.completion.get(7, TimeUnit.SECONDS);
    }

    private static Throwable failed(TestThread thread) {
        return assertThrows(ExecutionException.class, () -> thread.completion.get(7, TimeUnit.SECONDS)).getCause();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(7, TimeUnit.SECONDS), "test latch timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        throw (Error) failure;
    }

    private class TestThread extends Thread {
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final Runnable action;

        TestThread(Runnable action) {
            super("registration-test-" + threads.size());
            this.action = action;
            threads.add(this);
        }

        @Override
        public void run() {
            try {
                action.run();
                completion.complete(null);
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        }
    }
}
