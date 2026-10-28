package com.mapartrunner.fabric.scenario.threading;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/** One exact-thread startup handshake; Fabric still owns registration and phase progression. */
public final class RegistrationAcknowledgement {
    private Ticket current;

    public void startAndAwait(Thread child, Duration timeout) {
        Objects.requireNonNull(child, "child");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("Registration timeout must be positive and at most five seconds");
        }

        Ticket ticket;
        synchronized (this) {
            if (current != null) throw new IllegalStateException("A test thread startup is already pending");
            // Publish before start; waiting releases this monitor so only the exact child can acknowledge.
            current = ticket = new Ticket(child, System.nanoTime() + timeout.toNanos());
        }
        try {
            child.start();
            synchronized (this) {
                try {
                    try {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        while (ticket.state == State.PENDING) {
                            long remaining = ticket.remainingNanos();
                            if (remaining <= 0) {
                                fail(ticket, new IllegalStateException("Test thread registration timed out"));
                                break;
                            }
                            TimeUnit.NANOSECONDS.timedWait(this, remaining);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        fail(ticket, new IllegalStateException("Interrupted awaiting test thread registration", interrupted));
                    }
                    if (ticket.failure instanceof RuntimeException runtime) throw runtime;
                    if (ticket.failure instanceof Error error) throw error;
                } finally {
                    close(ticket);
                }
            }
        } finally {
            synchronized (this) {
                if (current == ticket) close(ticket);
            }
        }
    }

    public int registerAndSignal(IntSupplier registration) {
        Objects.requireNonNull(registration, "registration");
        Ticket ticket;
        synchronized (this) {
            ticket = current;
            if (ticket == null || ticket.child != Thread.currentThread()) {
                throw new IllegalStateException("Missing or mismatched test thread registration ticket");
            }
            if (ticket.state != State.PENDING || ticket.registrationStarted) {
                var duplicate = new IllegalStateException("Duplicate test thread registration signal");
                fail(ticket, duplicate);
                throw duplicate;
            }
            if (ticket.remainingNanos() <= 0) {
                var late = new IllegalStateException("Test thread registration arrived after its deadline");
                fail(ticket, late);
                throw late;
            }
            ticket.registrationStarted = true;
        }
        try {
            int phase = registration.getAsInt();
            if (phase < 0) throw new IllegalStateException("Test thread registered with a terminated phaser");
            synchronized (this) {
                if (current != ticket || ticket.state != State.PENDING || ticket.remainingNanos() <= 0) {
                    throw new IllegalStateException("Late or duplicate test thread registration completion");
                }
                ticket.state = State.ACKNOWLEDGED;
                notifyAll();
            }
            return phase;
        } catch (RuntimeException | Error failure) {
            fail(ticket, failure);
            throw failure;
        }
    }

    private synchronized void fail(Ticket ticket, Throwable failure) {
        if (current == ticket && (ticket.state == State.PENDING || ticket.state == State.ACKNOWLEDGED)) {
            ticket.failure = failure;
            ticket.state = State.FAILED;
            notifyAll();
        }
    }

    private void close(Ticket ticket) {
        // Under the monitor, close before clearing; late completion cannot touch a newer ticket.
        ticket.state = State.CLOSED;
        if (current == ticket) current = null;
    }

    private enum State { PENDING, ACKNOWLEDGED, FAILED, CLOSED }

    private static final class Ticket {
        private final Thread child;
        private final long deadlineNanos;
        private State state = State.PENDING;
        private boolean registrationStarted;
        private Throwable failure;

        private Ticket(Thread child, long deadlineNanos) {
            this.child = child;
            this.deadlineNanos = deadlineNanos;
        }

        private long remainingNanos() {
            // Subtraction remains valid across nanoTime wraparound for this bounded five-second interval.
            return deadlineNanos - System.nanoTime();
        }
    }
}
