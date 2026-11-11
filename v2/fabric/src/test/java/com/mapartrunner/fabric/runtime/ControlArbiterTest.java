package com.mapartrunner.fabric.runtime;

import com.mapartrunner.core.movement.MovementPolicy;
import com.mapartrunner.core.movement.MovementPolicy.Strafe;
import com.mapartrunner.core.run.BuildEngine.ControlOwner;
import com.mapartrunner.fabric.runtime.ControlArbiter.*;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.StickyKeyBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static com.mapartrunner.core.run.BuildEngine.ControlOwner.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlArbiterTest {
    private final RecordingSink sink = new RecordingSink();
    private final ControlArbiter arbiter = new ControlArbiter(sink);
    private final UUID runId = UUID.randomUUID();
    private static final ControlMask ALL = new ControlMask(true, true, true, true, true, true, true, true, true);

    @Test
    void idleAndUnownedInvalidationsPreserveEveryManualKey() {
        for (Control control : Control.values()) sink.keys.put(control, true);
        assertEquals(NONE, arbiter.owner());
        arbiter.invalidate();
        Binding binding = arbiter.bindRun(runId);
        assertTrue(arbiter.isCurrent(binding));
        arbiter.invalidate();
        arbiter.invalidate();
        assertTrue(sink.keys.values().stream().allMatch(Boolean::booleanValue));
        assertTrue(sink.events.isEmpty());
    }

    @Test
    void acquireUsesCurrentBindingAndSameOwnerKeepsTheSameUniqueLease() {
        Binding binding = arbiter.bindRun(runId);
        Lease lease = acquire(binding, NATIVE);
        assertEquals(runId, lease.runId());
        assertEquals(binding.generation(), lease.generation());
        assertEquals(NATIVE, lease.owner());
        assertEquals(NATIVE, arbiter.owner());
        assertSame(lease, acquire(binding, NATIVE));
        assertEquals(List.of("acquire:NATIVE"), sink.events);
    }

    @Test
    void nativeTravelNativeReleasesAllOldControlsBeforeAcquiringAndRejectsOldNativeLease() {
        Binding binding = arbiter.bindRun(runId);
        Lease oldNative = acquire(binding, NATIVE);
        assertTrue(arbiter.apply(oldNative, ALL));
        sink.events.clear();
        Lease travel = acquire(binding, TRAVEL);
        Lease newNative = acquire(binding, NATIVE);
        List<String> expected = cleanupEvents(NATIVE);
        expected.add("acquire:TRAVEL");
        expected.addAll(cleanupEvents(TRAVEL));
        expected.add("acquire:NATIVE");
        assertEquals(expected, sink.events);
        assertNotSame(oldNative, newNative);
        assertEquals(oldNative.generation(), newNative.generation());
        sink.events.clear();
        assertFalse(arbiter.apply(oldNative, ALL));
        assertFalse(arbiter.release(oldNative));
        assertFalse(arbiter.release(travel));
        assertTrue(sink.events.isEmpty());
        assertEquals(NATIVE, arbiter.owner());
        assertTrue(arbiter.apply(newNative, ControlMask.none()));
    }

    @Test
    void travelCannotWriteNativeKeys() {
        Lease travel = acquire(arbiter.bindRun(runId), TRAVEL);
        sink.events.clear();
        assertFalse(arbiter.apply(travel, ALL));
        assertTrue(sink.events.isEmpty());
        assertEquals(TRAVEL, arbiter.owner());
    }

    @Test
    void unavailableTravelLeavesNoneAfterNativeReleaseAndDoesNotInvalidateTheRun() {
        Binding binding = arbiter.bindRun(runId);
        arbiter.apply(acquire(binding, NATIVE), ALL);
        sink.travelAvailable = false;
        sink.events.clear();
        AcquireResult result = arbiter.acquire(binding, TRAVEL);
        assertEquals(AcquireStatus.UNAVAILABLE, result.status());
        assertTrue(result.lease().isEmpty());
        assertEquals(NONE, arbiter.owner());
        assertEquals(NONE, sink.held);
        List<String> expected = cleanupEvents(NATIVE);
        expected.add("acquire:TRAVEL");
        assertEquals(expected, sink.events);
        assertAllReleased();
        assertTrue(arbiter.isCurrent(binding));
        assertEquals(NATIVE, acquire(binding, NATIVE).owner());
    }

    @Test
    void unavailableFreshAcquireCreatesNoOwnedCleanupOrKeyWrites() {
        Binding binding = arbiter.bindRun(runId);
        sink.travelAvailable = false;
        assertEquals(AcquireStatus.UNAVAILABLE, arbiter.acquire(binding, TRAVEL).status());
        arbiter.invalidate();
        assertEquals(List.of("acquire:TRAVEL"), sink.events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pause", "stop", "world replacement"})
    void lifecycleInvalidationDefeatsOldCallbacksAndSameUuidResume(String lifecyclePath) {
        Binding oldBinding = arbiter.bindRun(runId);
        Lease oldLease = acquire(oldBinding, NATIVE);
        arbiter.apply(oldLease, ALL);
        sink.events.clear();
        arbiter.invalidate();
        assertEquals(cleanupEvents(NATIVE), sink.events, lifecyclePath);
        assertAllReleased();
        assertFalse(arbiter.isCurrent(oldBinding));
        sink.events.clear();
        assertStale(oldBinding, oldLease);
        Binding resumed = arbiter.bindRun(runId);
        assertEquals(runId, resumed.runId());
        assertTrue(resumed.generation() > oldBinding.generation());
        Lease current = acquire(resumed, NATIVE);
        sink.events.clear();
        assertStale(oldBinding, oldLease);
        assertTrue(sink.events.isEmpty());
        assertTrue(arbiter.apply(current, ControlMask.none()));
    }

    @Test
    void trustedReplacementRevokesBeforeCleanupAndOnlyThenPublishesNewBinding() {
        Binding oldBinding = arbiter.bindRun(runId);
        Lease oldLease = acquire(oldBinding, NATIVE);
        sink.onEvent = event -> {
            if (event.startsWith("key:") || event.startsWith("release:")) {
                assertFalse(arbiter.isCurrent(oldBinding));
                assertEquals(NONE, arbiter.owner());
            }
        };
        Binding replacement = arbiter.bindRun(UUID.randomUUID());
        assertTrue(arbiter.isCurrent(replacement));
        assertTrue(replacement.generation() > oldBinding.generation());
        assertNotEquals(oldBinding.runId(), replacement.runId());
        sink.events.clear();
        assertStale(oldBinding, oldLease);
        assertTrue(sink.events.isEmpty());
    }

    @Test
    void revokedBindingCannotAcquireDuringCleanupOrChangeFreshNativeControls() {
        Binding oldBinding = arbiter.bindRun(runId);
        Lease oldLease = acquire(oldBinding, NATIVE);
        arbiter.apply(oldLease, ALL);
        sink.onEvent = event -> {
            assertEquals(NONE, arbiter.owner());
            assertEquals(AcquireStatus.STALE, arbiter.acquire(oldBinding, NATIVE).status());
            assertEquals(AcquireStatus.STALE, arbiter.acquire(oldBinding, TRAVEL).status());
            assertFalse(arbiter.apply(oldLease, ALL));
            assertFalse(arbiter.release(oldLease));
        };
        sink.events.clear();
        Binding fresh = arbiter.bindRun(UUID.randomUUID());
        assertEquals(cleanupEvents(NATIVE), sink.events); assertAllReleased();
        sink.onEvent = event -> { };
        Lease current = acquire(fresh, NATIVE);
        arbiter.apply(current, only(Control.LEFT));
        sink.events.clear(); assertStale(oldBinding, oldLease);
        assertTrue(sink.events.isEmpty()); assertEquals(NATIVE, arbiter.owner());
        for (Control control : Control.values()) assertEquals(control == Control.LEFT, sink.keys.get(control));
    }

    @Test
    void foreignArbiterTokensNeverAffectTheCurrentOwner() {
        ControlArbiter foreign = new ControlArbiter(new RecordingSink());
        Binding otherBinding = foreign.bindRun(runId);
        Lease otherLease = foreign.acquire(otherBinding, NATIVE).lease().orElseThrow();
        Lease current = acquire(arbiter.bindRun(runId), NATIVE);
        sink.events.clear();
        assertFalse(arbiter.isCurrent(otherBinding));
        assertStale(otherBinding, otherLease);
        assertEquals(NATIVE, arbiter.owner());
        assertTrue(sink.events.isEmpty());
        assertTrue(arbiter.release(current));
    }

    @ParameterizedTest
    @EnumSource(Control.class)
    void completeMaskAndReleaseExplicitlyVisitEveryUnrequestedControl(Control requested) {
        Lease lease = acquire(arbiter.bindRun(runId), NATIVE);
        arbiter.apply(lease, ALL);
        sink.events.clear();
        assertTrue(arbiter.apply(lease, only(requested)));
        assertEquals(9, sink.events.size());
        for (Control control : Control.values()) assertEquals(control == requested, sink.keys.get(control));
        sink.events.clear();
        assertTrue(arbiter.release(lease));
        assertEquals(cleanupEvents(NATIVE), sink.events);
        assertAllReleased();
    }

    @Test
    void releaseIsIdempotentAndTheRetainedBindingCanAcquireANewUniqueLease() {
        Binding binding = arbiter.bindRun(runId);
        Lease old = acquire(binding, NATIVE);
        arbiter.apply(old, ALL);
        assertTrue(arbiter.release(old));
        assertTrue(arbiter.isCurrent(binding));
        assertEquals(NONE, arbiter.owner());
        sink.events.clear();
        assertFalse(arbiter.release(old));
        assertFalse(arbiter.apply(old, ALL));
        assertTrue(sink.events.isEmpty());
        assertNotSame(old, acquire(binding, NATIVE));
    }

    @ParameterizedTest
    @EnumSource(Strafe.class)
    void nativeMaskUsesExistingMovementValuesAndReleasesEveryOtherControl(Strafe strafe) {
        var movement = new MovementPolicy.MovementIntent(MovementPolicy.Status.WALKING,
                MovementPolicy.Failure.NONE, -90, true, false, strafe, true,
                MovementPolicy.Stage.WALK, 0, 0, 0, 0, 0);
        assertEquals(new ControlMask(true, false, strafe == Strafe.LEFT, strafe == Strafe.RIGHT,
                false, false, true, false, false), ControlMask.fromMovement(movement));
        assertEquals(new ControlMask(false, false, false, false, false, false, false, false, false), ControlMask.none());
    }

    @Test
    void backwardNonSprintingMovementDoesNotRetainForwardOrSprint() {
        var movement = new MovementPolicy.MovementIntent(MovementPolicy.Status.ENTERING,
                MovementPolicy.Failure.NONE, 90, false, true, Strafe.NONE, false,
                MovementPolicy.Stage.ENTER_POSITION, 1, 1, 0, 0, 0);
        assertEquals(new ControlMask(false, true, false, false, false, false, false, false, false),
                ControlMask.fromMovement(movement));
    }

    @ParameterizedTest
    @EnumSource(Control.class)
    void exceptionAtEveryApplyPositionRevokesAndAttemptsCompleteCleanup(Control failureAt) {
        Binding binding = arbiter.bindRun(runId);
        Lease lease = acquire(binding, NATIVE);
        RuntimeException failure = new IllegalStateException("apply " + failureAt);
        sink.fail(keyEvent(failureAt, true), failure);
        sink.events.clear();
        assertSame(failure, assertThrows(RuntimeException.class, () -> arbiter.apply(lease, ALL)));
        assertEquals(cleanupEvents(NATIVE), sink.events.subList(sink.events.size() - 10, sink.events.size()));
        assertEquals(NONE, sink.held);
        assertAllReleased();
        assertRevoked(binding, lease);
    }

    @ParameterizedTest
    @EnumSource(Control.class)
    void exceptionAtEveryReleasePositionStillAttemptsAllKeysAndBackend(Control failureAt) {
        Binding binding = arbiter.bindRun(runId);
        Lease lease = acquire(binding, NATIVE);
        arbiter.apply(lease, ALL);
        RuntimeException failure = new IllegalStateException("release " + failureAt);
        sink.fail(keyEvent(failureAt, false), failure);
        sink.events.clear();
        assertSame(failure, assertThrows(RuntimeException.class, () -> arbiter.release(lease)));
        assertEquals(cleanupEvents(NATIVE), sink.events);
        assertEquals(NONE, sink.held);
        assertRevoked(binding, lease);
    }

    @ParameterizedTest
    @ValueSource(strings = {"release", "invalidate", "bind", "transfer"})
    void backendReleaseExceptionPreventsNewAcquireAndKeepsNoActiveBinding(String operation) {
        Binding binding = arbiter.bindRun(runId);
        Lease lease = acquire(binding, NATIVE);
        RuntimeException failure = new IllegalStateException("backend release");
        sink.fail("release:NATIVE", failure);
        sink.events.clear();
        assertSame(failure, assertThrows(RuntimeException.class, () -> {
            switch (operation) {
                case "release" -> arbiter.release(lease);
                case "invalidate" -> arbiter.invalidate();
                case "bind" -> arbiter.bindRun(UUID.randomUUID());
                case "transfer" -> arbiter.acquire(binding, TRAVEL);
                default -> throw new AssertionError(operation);
            }
        }));
        assertEquals(cleanupEvents(NATIVE), sink.events);
        assertRevoked(binding, lease);
    }

    @ParameterizedTest
    @EnumSource(value = ControlOwner.class, names = {"NATIVE", "TRAVEL"})
    void throwingAcquireIsPotentiallyOwnedAndMustReceiveImmediateFullCleanup(ControlOwner owner) {
        Binding binding = arbiter.bindRun(runId);
        RuntimeException failure = new IllegalStateException("after acquisition");
        sink.fail("acquire:" + owner, failure);
        assertSame(failure, assertThrows(RuntimeException.class, () -> arbiter.acquire(binding, owner)));
        List<String> expected = new ArrayList<>(List.of("acquire:" + owner));
        expected.addAll(cleanupEvents(owner));
        assertEquals(expected, sink.events);
        assertEquals(NONE, sink.held);
        assertEquals(NONE, arbiter.owner());
        assertFalse(arbiter.isCurrent(binding));
        assertEquals(AcquireStatus.STALE, arbiter.acquire(binding, owner).status());
    }

    @Test
    void originalApplyExceptionSurvivesAllCleanupFailuresIncludingBackendSprintCleanup() {
        Binding binding = arbiter.bindRun(runId);
        Lease lease = acquire(binding, NATIVE);
        RuntimeException original = new IllegalStateException("apply");
        RuntimeException firstKey = new IllegalStateException("first cleanup key");
        RuntimeException lastKey = new IllegalStateException("last cleanup key");
        RuntimeException backend = new IllegalStateException("backend / actual sprint cleanup");
        sink.fail(keyEvent(Control.SPRINT, true), original);
        sink.fail(keyEvent(Control.FORWARD, false), firstKey);
        sink.fail(keyEvent(Control.ATTACK, false), lastKey);
        sink.fail("release:NATIVE", backend);
        assertSame(original, assertThrows(RuntimeException.class, () -> arbiter.apply(lease, ALL)));
        assertArrayEquals(new Throwable[]{firstKey, lastKey, backend}, original.getSuppressed());
        assertEquals(cleanupEvents(NATIVE), sink.events.subList(sink.events.size() - 10, sink.events.size()));
        assertRevoked(binding, lease);
    }

    @Test
    void cleanupInitiatedFailureRetainsFirstAndSuppressesEachDistinctLaterFailureOnlyOnce() {
        Binding binding = arbiter.bindRun(runId);
        Lease lease = acquire(binding, NATIVE);
        RuntimeException first = new IllegalStateException("first");
        RuntimeException later = new IllegalStateException("later");
        sink.fail(keyEvent(Control.FORWARD, false), first);
        sink.fail(keyEvent(Control.BACK, false), first);
        sink.fail(keyEvent(Control.ATTACK, false), later);
        sink.fail("release:NATIVE", later);
        assertSame(first, assertThrows(RuntimeException.class, () -> arbiter.release(lease)));
        assertArrayEquals(new Throwable[]{later}, first.getSuppressed());
        assertRevoked(binding, lease);
    }

    @Test
    void errorsAlsoRevokeAndAttemptAllCleanupWithoutChangingTheirType() {
        Binding binding = arbiter.bindRun(runId);
        Lease lease = acquire(binding, NATIVE);
        AssertionError original = new AssertionError("effect");
        AssertionError cleanup = new AssertionError("cleanup");
        sink.fail(keyEvent(Control.FORWARD, true), original);
        sink.fail(keyEvent(Control.SPRINT, false), cleanup);
        assertSame(original, assertThrows(AssertionError.class, () -> arbiter.apply(lease, ALL)));
        assertArrayEquals(new Throwable[]{cleanup}, original.getSuppressed());
        assertTrue(sink.events.contains("release:NATIVE"));
        assertRevoked(binding, lease);
    }

    @Test
    void acquisitionFailureAlsoRetainsCleanupFailures() {
        Binding binding = arbiter.bindRun(runId);
        RuntimeException original = new IllegalStateException("acquire");
        RuntimeException cleanup = new IllegalStateException("cleanup");
        sink.fail("acquire:NATIVE", original);
        sink.fail("release:NATIVE", cleanup);
        assertSame(original, assertThrows(RuntimeException.class, () -> arbiter.acquire(binding, NATIVE)));
        assertArrayEquals(new Throwable[]{cleanup}, original.getSuppressed());
        assertFalse(arbiter.isCurrent(binding));
    }

    @ParameterizedTest
    @CsvSource({"BASE,false", "BASE,true", "HOLD,false", "HOLD,true", "TOGGLE,false", "TOGGLE,true"})
    void realMappedKeyWritesAreIdempotentInBaseHoldAndToggleModes(String mode, boolean initiallyPressed) {
        String id = "key.mapart_test." + UUID.randomUUID();
        KeyBinding key = mode.equals("BASE")
                ? new KeyBinding(id, 0, KeyBinding.Category.MOVEMENT)
                : new StickyKeyBinding(id, 0, KeyBinding.Category.MOVEMENT, () -> mode.equals("TOGGLE"), true);
        key.setPressed(initiallyPressed);
        assertEquals(initiallyPressed, key.isPressed());
        for (boolean desired : new boolean[]{false, false, true, true, false, false, true, false}) {
            ControlArbiter.MinecraftSink.writeKey(key, desired);
            assertEquals(desired, key.isPressed(), mode + " desired=" + desired);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ownedFalseDiscardsPendingScreenRestorationEvenWhenAlreadyFalse(boolean pressedAgain) {
        ScreenStickyKey key = new ScreenStickyKey();
        key.setPressed(true);
        key.resetForScreen();
        assertFalse(key.isPressed(), "screen opening untoggles the real key");
        if (pressedAgain) key.setPressed(true);

        ControlArbiter.MinecraftSink.writeKey(key, false);
        assertFalse(key.isPressed());
        boolean restore = key.shouldRestoreOnScreenClose();
        if (restore) key.setPressed(true); // Exact public consumer used by vanilla screen close.
        assertFalse(restore, "owned release must discard saved automation restoration");
        assertFalse(key.isPressed(), "screen close must not restore released automation");
        assertFalse(key.shouldRestoreOnScreenClose());
    }

    @Test
    void idleLifecycleDoesNotConsumeManualPendingScreenRestoration() {
        ScreenStickyKey key = new ScreenStickyKey();
        key.setPressed(true);
        key.resetForScreen();
        ControlArbiter manual = new ControlArbiter(new Sink() {
            @Override public boolean acquire(ControlOwner owner) { return true; }
            @Override public void setPressed(Control control, boolean pressed) {
                ControlArbiter.MinecraftSink.writeKey(key, pressed);
            }
            @Override public void release(ControlOwner owner) { }
        });
        manual.invalidate();
        manual.bindRun(runId);
        manual.invalidate();
        assertFalse(key.isPressed());
        boolean restore = key.shouldRestoreOnScreenClose();
        assertTrue(restore, "unowned manual restoration must remain available");
        if (restore) key.setPressed(true);
        assertTrue(key.isPressed());
        ControlArbiter.MinecraftSink.writeKey(key, false);
    }

    @Test
    void invalidArgumentsAndInconsistentAcquireResultsFailWithoutEffects() {
        Binding binding = arbiter.bindRun(runId);
        assertThrows(NullPointerException.class, () -> new ControlArbiter(null));
        assertThrows(NullPointerException.class, () -> ControlArbiter.forClient(null));
        assertThrows(NullPointerException.class, () -> arbiter.bindRun(null));
        assertTrue(arbiter.isCurrent(binding));
        assertThrows(NullPointerException.class, () -> arbiter.isCurrent(null));
        assertThrows(NullPointerException.class, () -> arbiter.acquire(null, NATIVE));
        assertThrows(NullPointerException.class, () -> arbiter.acquire(binding, null));
        assertThrows(IllegalArgumentException.class, () -> arbiter.acquire(binding, NONE));
        assertThrows(NullPointerException.class, () -> arbiter.release(null));
        assertThrows(NullPointerException.class, () -> arbiter.apply(null, ALL));
        Lease lease = acquire(binding, NATIVE);
        sink.events.clear();
        assertThrows(NullPointerException.class, () -> arbiter.apply(lease, null));
        assertThrows(NullPointerException.class, () -> ControlMask.fromMovement(null));
        assertThrows(IllegalArgumentException.class, () -> new AcquireResult(AcquireStatus.ACQUIRED, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new AcquireResult(AcquireStatus.STALE, Optional.of(lease)));
        assertThrows(IllegalArgumentException.class, () -> new AcquireResult(AcquireStatus.UNAVAILABLE, Optional.of(lease)));
        assertThrows(NullPointerException.class, () -> new AcquireResult(null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new AcquireResult(AcquireStatus.STALE, null));
        assertTrue(sink.events.isEmpty());
        assertEquals(NATIVE, arbiter.owner());
    }

    private Lease acquire(Binding binding, ControlOwner owner) {
        AcquireResult result = arbiter.acquire(binding, owner);
        assertEquals(AcquireStatus.ACQUIRED, result.status());
        return result.lease().orElseThrow();
    }

    private void assertRevoked(Binding binding, Lease lease) {
        assertFalse(arbiter.isCurrent(binding));
        assertEquals(NONE, arbiter.owner());
        sink.events.clear();
        assertStale(binding, lease);
        assertTrue(sink.events.isEmpty());
    }

    private void assertStale(Binding binding, Lease lease) {
        AcquireResult stale = arbiter.acquire(binding, NATIVE);
        assertEquals(AcquireStatus.STALE, stale.status());
        assertTrue(stale.lease().isEmpty());
        assertFalse(arbiter.apply(lease, ALL));
        assertFalse(arbiter.release(lease));
    }

    private void assertAllReleased() {
        for (Control control : Control.values()) assertFalse(sink.keys.get(control), control.name());
    }

    private static ControlMask only(Control control) {
        return new ControlMask(control == Control.FORWARD, control == Control.BACK,
                control == Control.LEFT, control == Control.RIGHT, control == Control.JUMP,
                control == Control.SNEAK, control == Control.SPRINT, control == Control.USE,
                control == Control.ATTACK);
    }

    private static List<String> cleanupEvents(ControlOwner owner) {
        List<String> result = new ArrayList<>();
        for (Control control : Control.values()) result.add(keyEvent(control, false));
        result.add("release:" + owner);
        return result;
    }

    private static String keyEvent(Control control, boolean pressed) { return "key:" + control + "=" + pressed; }

    private static final class ScreenStickyKey extends StickyKeyBinding {
        ScreenStickyKey() {
            super("key.mapart_test." + UUID.randomUUID(), 0, KeyBinding.Category.MOVEMENT, () -> true, true);
        }

        void resetForScreen() { super.reset(); }
    }

    private static final class RecordingSink implements Sink {
        final List<String> events = new ArrayList<>();
        final Map<Control, Boolean> keys = new EnumMap<>(Control.class);
        final Map<String, Deque<Throwable>> failures = new HashMap<>();
        Consumer<String> onEvent = event -> { };
        ControlOwner held = NONE;
        boolean travelAvailable = true;

        RecordingSink() { for (Control control : Control.values()) keys.put(control, false); }

        void fail(String event, Throwable failure) {
            failures.computeIfAbsent(event, ignored -> new ArrayDeque<>()).add(failure);
        }

        @Override public boolean acquire(ControlOwner owner) {
            assertEquals(NONE, held, "old backend must release before acquire");
            boolean available = owner != TRAVEL || travelAvailable;
            if (available) held = owner; // A throw can happen after a backend became active.
            event("acquire:" + owner);
            return available;
        }

        @Override public void setPressed(Control control, boolean pressed) {
            event(keyEvent(control, pressed));
            keys.put(control, pressed);
        }

        @Override public void release(ControlOwner owner) {
            event("release:" + owner);
            held = NONE;
        }

        private void event(String event) {
            events.add(event);
            onEvent.accept(event);
            Deque<Throwable> queued = failures.get(event);
            if (queued == null || queued.isEmpty()) return;
            Throwable failure = queued.removeFirst();
            if (failure instanceof Error error) throw error;
            throw (RuntimeException) failure;
        }
    }
}
