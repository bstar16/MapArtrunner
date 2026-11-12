package com.mapartrunner.fabric.scenario;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import com.mapartrunner.core.run.BuildEngine.ControlOwner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static com.mapartrunner.fabric.scenario.ScenarioCommands.*;
import static com.mapartrunner.fabric.scenario.ScenarioHost.Command;
import static org.junit.jupiter.api.Assertions.*;

/** Simulated input/clock policy checks. These do not establish native keyboard or gameplay acceptance. */
class InspectAcceptanceDriverTest {
    @Test void activeUnknownCaseFailsBeforeAnyInputButInactiveSelectionIsInert() {
        var previous = System.getProperty("mapart.test.inspect.acceptance.case");
        try {
            for (var value : List.of("", "unknown")) {
                System.setProperty("mapart.test.inspect.acceptance.case", value);
                var inactive = new Fixture(); inactive.enabled = false; inactive.tick();
                assertTrue(inactive.input.events.isEmpty()); assertEquals(0, inactive.input.stops);
                var active = new Fixture(); active.tick(); active.assertFailed("Unknown acceptance case");
                assertTrue(active.input.events.isEmpty());
            }
        } finally {
            if (previous == null) System.clearProperty("mapart.test.inspect.acceptance.case");
            else System.setProperty("mapart.test.inspect.acceptance.case", previous);
        }
    }
    @Test void explicitDoubleOptInAndAbsentAutomaticModeAreRequired() {
        for (var inspect : Arrays.asList(null, "false", "yes", "true"))
            for (var acceptance : Arrays.asList(null, "false", "1", "true"))
                for (var automatic : Arrays.asList(null, "false", "true"))
                    assertEquals("true".equals(inspect) && "true".equals(acceptance) && automatic == null,
                            InspectAcceptanceDriver.enabled(inspect, acceptance, automatic));
        var f = new Fixture(); f.enabled = false; f.tick(10);
        assertTrue(f.input.events.isEmpty()); assertTrue(f.logs.isEmpty()); assertEquals(0, f.input.stops);
    }

    @Test void displacedProgramRequiresExactFailureBThenLastExplicitResetAndSuccess() {
        displaced(f -> {
            f.beginForward(); f.input.x = 0.5; f.tick(); assertFalse(f.input.pressed);
            f.sendCompletion(3); f.send(); f.tick();
            f.sendCompletion(4); f.sendCompletion(5); f.send(); f.tick();
            assertEquals(List.of("/maparttest run grounded-two-lane", "/maparttest reset grounded-two-lane",
                    "/maparttest run grounded-two-lane", "/maparttest last", "/maparttest reset grounded-two-lane",
                    "/maparttest run grounded-two-lane", "/maparttest last"), f.input.commands);
            assertEquals(1, f.input.stops); assertFalse(f.input.pressed);
            assertEquals(5, f.logs.stream().filter(s -> s.contains("RESULT command=")).count());
            assertTrue(f.logs.stream().anyMatch(s -> s.contains("ordinal=3 verdict=FAIL") && s.contains("run=none")));
            assertEquals(2, f.logs.stream().filter(s -> s.contains("LAST submitted")).count());
            assertTrue(f.logs.stream().anyMatch(s -> s.contains("SEQUENCE_COMPLETE")));
        });
    }

    @Test void displacementRequiresSettledResetAndMeasuredDistanceWithTwentiethCallbackInclusive() {
        displaced(f -> {
            f.sendCompletion(1); f.send();
            f.observation = new InspectObservation(true, true, false, Optional.of(f.completion(2)));
            f.tick(4); assertFalse(f.input.pressed);
            f.observation = idle(f.completion(2)); f.tick(); assertFalse(f.input.pressed);
            f.tick(); assertTrue(f.input.pressed);
            f.input.reenter = () -> f.driver.tick(null);
            f.input.x = 0.499; f.tick(19); assertTrue(f.input.pressed); assertEquals(2, f.input.commands.size());
            f.input.x = 0.5; f.tick(); assertFalse(f.input.pressed);
            assertTrue(f.logs.stream().anyMatch(s -> s.contains("distance=0.5 callbacks=20")));
            assertEquals(1, Collections.frequency(f.input.events, "87+"));
            assertEquals(1, Collections.frequency(f.input.events, "87-"));
        });
        displaced(f -> { f.beginForward(); f.tick(20); f.assertFailed("20 END callbacks"); assertFalse(f.input.pressed); });
        displaced(f -> { f.beginForward(); f.input.z = -0.6; f.tick(); assertFalse(f.input.pressed); });
    }

    @Test void nativeForwardKeyPolicyRejectsRemapCollisionPressedControlsAndProductionOwnerWithoutRepair() {
        var forward = new KeyBinding("key.mapart.k-forward", 87, KeyBinding.Category.MOVEMENT);
        var other = new KeyBinding("key.mapart.k-other", 65, KeyBinding.Category.MOVEMENT);
        var keys = new KeyBinding[]{forward, other}; var controls = List.of(forward, other);
        assertDoesNotThrow(() -> InspectAcceptanceDriver.requireForwardKeys(forward, keys, controls, ControlOwner.NONE));
        for (var held : controls) {
            held.setPressed(true);
            assertThrows(IllegalStateException.class, () -> InspectAcceptanceDriver.requireForwardKeys(forward, keys, controls, ControlOwner.NONE));
            assertTrue(held.isPressed(), "Guard must not repair manual input"); held.setPressed(false);
        }
        for (var owner : List.of(ControlOwner.NATIVE, ControlOwner.TRAVEL))
            assertThrows(IllegalStateException.class, () -> InspectAcceptanceDriver.requireForwardKeys(forward, keys, controls, owner));
        other.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(87));
        assertThrows(IllegalStateException.class, () -> InspectAcceptanceDriver.requireForwardKeys(forward, keys, controls, ControlOwner.NONE));
        other.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(65));
        forward.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(88));
        assertThrows(IllegalStateException.class, () -> InspectAcceptanceDriver.requireForwardKeys(forward, keys, controls, ControlOwner.NONE));
        forward.setBoundKey(forward.getDefaultKey());
    }

    @Test void forwardDeadlineCarriesThroughBChatAndRejectsLateThresholdAndNonfinitePose() {
        displaced(f -> {
            f.beginForward(); f.now.set(29_000_000_000L); f.input.x = 0.5; f.tick();
            f.send(); f.now.set(30_000_000_000L); f.tick(); f.assertFailed("Input deadline expired");
        });
        displaced(f -> {
            f.beginForward(); f.now.set(30_000_000_000L); f.input.x = 1; f.tick();
            f.assertFailed("Input deadline expired"); assertFalse(f.input.pressed);
            assertTrue(f.logs.stream().noneMatch(s -> s.contains("FORWARD_RELEASE")));
        });
        displaced(f -> { f.input.x = Double.NaN; f.beginForward(); f.assertFailed("Nonfinite"); assertFalse(f.input.pressed); });
        displaced(f -> { f.beginForward(); f.input.z = Double.POSITIVE_INFINITY; f.tick(); f.assertFailed("Nonfinite"); assertFalse(f.input.pressed); });
    }

    @Test void wrongBClassMessageVerdictOrRunCannotBecomeAcceptedFailure() {
        for (var bad : List.of("class", "message", "verdict", "run")) displaced(f -> {
            f.beginForward(); f.input.x = 1; f.tick(); f.send(); var c = f.completion(3);
            f.observation = idle(new PublishedCompletion(c.sessionId(), c.operationId(), c.ordinal(), c.command(),
                    bad.equals("run") ? Optional.of(UUID.randomUUID()) : c.runId(), bad.equals("verdict") ? "PASS" : "FAIL",
                    true, "RELEASED", true, c.evidenceDirectory(), c.summaryPath(), Optional.of(new FailureObservation(
                    bad.equals("class") ? "java.lang.IllegalStateException" : "java.lang.AssertionError",
                    bad.equals("message") ? "other" : "Start state changed without physics"))));
            f.tick(); f.assertFailed("completion");
        });
    }

    @Test void heldInputLosesGameplayAuthorityWithOnlyExactCapturedFalseCleanup() {
        for (var lost : List.of("window", "world", "connection", "focus", "gate", "guard")) displaced(f -> {
            f.beginForward(); int effects = f.input.events.size();
            if (lost.equals("gate")) f.enabled = false;
            else if (lost.equals("guard")) f.guardFailure = "guard";
            else f.input.refusal = lost;
            f.tick(); f.tick(3); f.assertFailed(lost.equals("gate") ? "gate disabled" : lost);
            assertFalse(f.input.pressed);
            assertEquals(List.of("captured-false"), f.input.events.subList(effects, f.input.events.size()));
            assertEquals(2, f.input.commands.size());
        });
    }

    @Test void releaseDebtSurvivesWrongThreadAndStoppingNeedsNoFollowingEndTick() {
        for (var lost : List.of("client", "thread")) displaced(f -> {
            f.beginForward(); f.input.clientRefusal = lost; f.tick();
            assertTrue(f.input.pressed); assertEquals(0, f.input.stops);
            f.input.clientRefusal = null; f.input.refusal = "world"; f.tick();
            assertFalse(f.input.pressed); f.assertFailed(lost);
            assertEquals(1, Collections.frequency(f.input.events, "captured-false"));
        });
        displaced(f -> {
            f.beginForward(); f.input.refusal = "window"; f.driver.releaseOnStopping(null);
            assertFalse(f.input.pressed); int events = f.input.events.size();
            f.driver.releaseOnStopping(null); assertEquals(events, f.input.events.size());
        });
        displaced(f -> {
            f.beginForward(); f.input.pressed = false; int events = f.input.events.size();
            f.driver.releaseOnStopping(null); assertEquals(events, f.input.events.size());
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rejectedCurrentEntryRetainsForwardDebtEvenWhenCapturedClientCleanupWouldSucceed(boolean stopping) {
        displaced(f -> {
            f.beginForward(); int effects = f.input.events.size();
            f.input.entryRefusal = "foreign current";
            // Model a foreign current argument on the healthy installed client's own thread.
            assertTrue(f.input.forwardPressed(f.input.forward), "Captured-client checks remain valid");
            if (stopping) f.driver.releaseOnStopping(null); else f.tick();
            assertTrue(f.input.pressed, "Rejected entry must retain the exact release debt");
            assertEquals(effects, f.input.events.size()); assertEquals(0, f.input.stops);
            f.input.entryRefusal = null;
            if (stopping) f.driver.releaseOnStopping(null); else f.tick();
            assertFalse(f.input.pressed);
            assertEquals(1, Collections.frequency(f.input.events, "87-"));
            assertEquals(0, Collections.frequency(f.input.events, "captured-false"));
            if (stopping) f.driver.releaseOnStopping(null); else f.tick();
            assertEquals(1, Collections.frequency(f.input.events, "87-"));
            assertEquals(1, f.logs.stream().filter(s -> s.contains("DRIVER_FAIL") && s.contains("foreign current")).count());
        });
    }

    @Test void changingPublishedValueOrAdmissionWhileHeldStopsBeforeAnyNewGameplayInput() {
        for (boolean busy : List.of(true, false)) displaced(f -> {
            f.beginForward(); int effects = f.input.events.size();
            f.observation = new InspectObservation(true, busy, false,
                    Optional.of(busy ? f.completion(2) : f.changed(f.completion(2), "session")));
            f.tick(); f.assertFailed(busy ? "admission while moving" : "Retained completion changed");
            assertFalse(f.input.pressed);
            assertEquals(List.of("captured-false"), f.input.events.subList(effects, f.input.events.size()));
        });
    }

    @Test void applyingPressOrReleaseExceptionsCannotLeakOrResendForwardAndCleanupFailureIsNotSuccess() {
        for (var event : List.of("87+", "87-")) displaced(f -> {
            f.input.throwAt = event; f.input.reenter = () -> f.driver.tick(null);
            f.beginForward(); if (event.equals("87-")) { f.input.x = 1; f.tick(); }
            f.tick(3); f.assertFailed("callback"); assertFalse(f.input.pressed);
            assertEquals(1, Collections.frequency(f.input.events, "87+"));
            assertEquals(1, Collections.frequency(f.input.events, "87-"));
            assertTrue(f.logs.stream().noneMatch(s -> s.contains("FORWARD_RELEASE")));
        });
        displaced(f -> {
            f.beginForward(); f.input.refusal = "world"; f.input.clearFails = true; f.tick();
            assertTrue(f.input.pressed); assertEquals(0, f.input.stops);
            assertTrue(f.logs.stream().noneMatch(s -> s.contains("FORWARD_RELEASE")));
            f.input.clearFails = false; f.tick(); assertFalse(f.input.pressed); assertEquals(1, f.input.stops);
        });
    }

    private static void displaced(java.util.function.Consumer<Fixture> test) {
        var previous = System.getProperty("mapart.test.inspect.acceptance.case");
        try {
            System.setProperty("mapart.test.inspect.acceptance.case", "displaced");
            var f = new Fixture(); f.displaced = true; test.accept(f);
        } finally {
            if (previous == null) System.clearProperty("mapart.test.inspect.acceptance.case");
            else System.setProperty("mapart.test.inspect.acceptance.case", previous);
        }
    }

    @Test void startupWaitDoesNotConsumeInputBoundAndInitialLastMustBeAbsent() {
        var f = new Fixture(); f.observation = new InspectObservation(false, true, false, Optional.empty());
        f.tick(); f.now.set(100_000_000_000L); f.tick(); assertTrue(f.input.events.isEmpty());
        f.observation = idle(null); f.tick(); assertEquals(List.of("84+", "84-"), f.input.events);
        var stale = new Fixture(); stale.observation = idle(stale.completion(1)); stale.tick();
        stale.assertFailed("Initial last must be absent");
    }

    @Test void fixedSequenceAcceptsFastCompletionsAndOnlyReportsLastSubmitted() {
        var f = new Fixture();
        for (int ordinal = 1; ordinal <= 5; ordinal++) {
            f.send(); assertEquals(ordinal, f.input.commands.size());
            var completion = f.completion(ordinal);
            f.observation = idle(completion); f.tick(); f.tick();
        }
        f.send(); assertEquals(List.of("/maparttest run grounded-two-lane", "/maparttest reset grounded-two-lane",
                "/maparttest run grounded-two-lane", "/maparttest rerun grounded-two-lane",
                "/maparttest rerun grounded-two-lane", "/maparttest last"), f.input.commands);
        assertEquals(0, f.input.stops, "Enter delivery alone cannot finish LAST");
        f.tick(); assertEquals(1, f.input.stops); f.tick(10); assertEquals(1, f.input.stops);
        assertEquals(12, f.input.events.size() / 2);
        assertEquals(1, f.logs.stream().filter(s -> s.contains("SEQUENCE_COMPLETE")).count());
        assertTrue(f.logs.stream().anyMatch(s -> s.contains("LAST submitted")));
        assertTrue(f.logs.stream().noneMatch(s -> s.contains("response proved") || s.contains("normal-close PASS")));
        var complete = f.logs.stream().filter(s -> s.contains("SEQUENCE_COMPLETE")).findFirst().orElseThrow();
        for (int i = 2; i < 5; i++) assertTrue(complete.contains(f.runs[i].toString()));
    }

    @Test void matchingPublicationStillBusyCannotAdvanceAndOldResultWaits() {
        var f = new Fixture(); f.send(); var first = f.completion(1);
        f.observation = new InspectObservation(true, true, false, Optional.of(first)); f.tick(3);
        assertEquals(1, f.input.commands.size());
        f.observation = idle(first); f.tick(); f.tick(); f.send();
        f.tick(3); assertEquals(2, f.input.commands.size()); assertEquals(0, f.input.stops);
        f.observation = idle(f.completion(2)); f.tick(); f.tick();
        assertEquals(0, f.input.stops);
    }

    @Test void originalDeadlineIncludesChatOpeningTypingAndAdmissionWithoutRenewal() {
        var f = new Fixture(); f.tick(); f.now.set(29_000_000_000L); f.openChat(); f.tick();
        f.now.set(30_000_000_000L); f.tick(); f.assertFailed("Input deadline expired");
        assertEquals(1, f.input.commands.size());
        var opening = new Fixture(); opening.tick(); opening.now.set(30_000_000_000L); opening.tick();
        opening.assertFailed("Input deadline expired"); assertTrue(opening.input.commands.isEmpty());
    }

    @Test void admittedWorkUsesExistingSupervisionRatherThanInputDeadline() {
        var f = new Fixture(); f.send();
        f.observation = new InspectObservation(true, true, false, Optional.empty()); f.tick();
        f.now.set(600_000_000_000L); f.tick(3); assertEquals(0, f.input.stops);
        f.observation = idle(f.completion(1)); f.tick(); assertEquals(0, f.input.stops);
    }

    @Test void callbackExceptionAndReentrancyCannotResendAndKeysArePaired() {
        for (var event : List.of("84+", "char", "257+", "257-")) {
            var f = new Fixture(); f.input.reenter = () -> f.driver.tick(null); f.input.throwAt = event;
            f.send(); f.tick(5); f.assertFailed("callback");
            assertTrue(Collections.frequency(f.input.events, "84+") <= 1);
            assertTrue(Collections.frequency(f.input.events, "257+") <= 1);
            assertEquals(Collections.frequency(f.input.events, "84+"), Collections.frequency(f.input.events, "84-"));
            assertEquals(Collections.frequency(f.input.events, "257+"), Collections.frequency(f.input.events, "257-"));
        }
    }

    @Test void invalidNativeIdentityScreenDraftFocusOrPauseStopsOnceWithoutRepair() {
        for (var refusal : List.of("window", "world", "server", "player", "handler", "connection", "pause", "focus", "binding")) {
            var f = new Fixture(); f.tick(); f.input.refusal = refusal; f.tick(3); f.assertFailed(refusal);
            assertTrue(f.input.commands.isEmpty());
        }
        var screen = new Fixture(); screen.tick(); screen.input.screen = new Object(); screen.tick(); screen.assertFailed("screen");
        var draft = new Fixture(); draft.tick(); draft.openChat(); draft.input.draft = "foreign"; draft.tick(); draft.assertFailed("draft");
        var replaced = new Fixture(); replaced.tick(); replaced.openChat(); replaced.input.replaceOnChar = true;
        replaced.tick(); replaced.assertFailed("screen"); assertTrue(replaced.input.commands.isEmpty());
    }

    @Test void wrongClientOrThreadCannotDeliverInputOrStopUntilAValidCallback() {
        for (var refusal : List.of("client", "thread")) {
            var f = new Fixture(); f.input.clientRefusal = refusal; f.tick(3);
            assertTrue(f.input.events.isEmpty()); assertEquals(0, f.input.stops);
            f.input.clientRefusal = null; f.tick(3); f.assertFailed(refusal);
            assertTrue(f.input.events.isEmpty());
        }
    }

    @Test void preEnterForeignAdmissionAndPendingResultChangesFailWithoutResend() {
        var before = new Fixture(); before.tick();
        before.observation = new InspectObservation(true, true, false, Optional.empty()); before.openChat(); before.tick();
        before.assertFailed("admission before Enter"); assertTrue(before.input.commands.isEmpty());
        var pending = new Fixture(); pending.send(); var c = pending.completion(1);
        pending.observation = new InspectObservation(true, true, false, Optional.of(c)); pending.tick();
        pending.observation = idle(pending.changed(c, "session")); pending.tick(); pending.assertFailed("completion changed");
        assertEquals(1, pending.input.commands.size());
    }

    @Test void lastRequiresNaturalHandoffAndUnchangedPublishedResult() {
        var f = new Fixture();
        for (int i = 1; i <= 5; i++) { f.send(); f.observation = idle(f.completion(i)); f.tick(); f.tick(); }
        f.send(); f.observation = idle(f.changed(f.completion(5), "session")); f.tick();
        f.assertFailed("Retained completion changed");
        assertTrue(f.logs.stream().noneMatch(s -> s.contains("SEQUENCE_COMPLETE")));
    }

    @Test void failureDiagnosticIsBoundedAndNeverRepeated() {
        var f = new Fixture(); f.tick(); f.input.refusal = "x".repeat(6000); f.tick(5);
        f.assertFailed("DRIVER_FAIL");
        assertEquals(4096, f.logs.stream().filter(s -> s.contains("DRIVER_FAIL")).findFirst().orElseThrow().length());
    }

    @Test void restartOrReadinessLossCannotBecomeSuccess() {
        for (var observation : List.of(new InspectObservation(true, false, true, Optional.empty()),
                new InspectObservation(false, false, false, Optional.empty()))) {
            var f = new Fixture(); f.send(); f.observation = observation; f.tick(); f.assertFailed("unavailable");
        }
    }

    @Test void foreignSkippedReclassifiedDuplicateAndIncompleteResultsAreRejected() {
        for (var bad : List.of("ordinal", "command", "session", "operation", "directory", "summary", "cleanup", "settled", "complete", "verdict", "failure", "run")) {
            var f = new Fixture(); f.send(); f.observation = idle(f.completion(1)); f.tick(); f.tick(); f.send();
            f.observation = idle(f.changed(f.completion(2), bad)); f.tick(); f.assertFailed("completion");
        }
        var changed = new Fixture(); changed.send(); changed.observation = idle(changed.completion(1)); changed.tick(); changed.tick(); changed.send();
        changed.observation = idle(changed.changed(changed.completion(1), "verdict")); changed.tick(); changed.assertFailed("Retained completion changed");
    }

    @Test void expectedFailureARequiresExactCauseAndRemainsFail() {
        for (var cause : List.of("PASS", "other class", "other message", "with run")) {
            var f = new Fixture(); f.send(); var c = f.completion(1);
            f.observation = idle(new PublishedCompletion(c.sessionId(), c.operationId(), 1, c.command(),
                    cause.equals("with run") ? Optional.of(UUID.randomUUID()) : c.runId(), cause.equals("PASS") ? "PASS" : "FAIL",
                    true, "RELEASED", true, c.evidenceDirectory(), c.summaryPath(), Optional.of(new FailureObservation(
                    cause.equals("other class") ? "java.lang.IllegalStateException" : "java.lang.AssertionError",
                    cause.equals("other message") ? "other" : "Run requires this invocation's successful reset"))));
            f.tick(); f.assertFailed("completion");
        }
        var f = new Fixture(); f.send(); f.observation = idle(f.completion(1)); f.tick();
        assertTrue(f.logs.stream().anyMatch(s -> s.contains("verdict=FAIL"))); assertEquals(0, f.input.stops);
    }

    @Test void repeatedRunUuidAndUnexpectedBuildFailureAreRejected() {
        for (boolean duplicate : List.of(true, false)) {
            var f = new Fixture();
            for (int i = 1; i <= 3; i++) { f.send(); f.observation = idle(f.completion(i)); f.tick(); f.tick(); }
            f.send(); var c = f.completion(4);
            f.observation = idle(new PublishedCompletion(c.sessionId(), c.operationId(), 4, c.command(),
                    duplicate ? f.completion(3).runId() : c.runId(), duplicate ? "PASS" : "FAIL", true, "RELEASED", true,
                    c.evidenceDirectory(), c.summaryPath(), duplicate ? Optional.empty() : Optional.of(new FailureObservation("x", "x"))));
            f.tick(); f.assertFailed("completion");
        }
    }

    private static InspectObservation idle(PublishedCompletion completion) { return new InspectObservation(true, false, false, Optional.ofNullable(completion)); }

    private static final class Fixture {
        final AtomicLong now = new AtomicLong(); final SimulatedChat input = new SimulatedChat();
        final List<String> logs = new ArrayList<>(); final UUID session = UUID.randomUUID();
        final UUID[] operations = new UUID[5], runs = new UUID[5];
        InspectObservation observation = idle(null); boolean enabled = true, displaced; String guardFailure;
        final InspectAcceptanceDriver driver = new InspectAcceptanceDriver(() -> observation, input, now::get, () -> enabled, logs::add,
                expected -> { if (guardFailure != null) throw new IllegalStateException(guardFailure); assertEquals(observation.last().orElseThrow(), expected); assertFalse(observation.busy()); });
        Fixture() { Arrays.setAll(operations, i -> UUID.randomUUID()); Arrays.setAll(runs, i -> UUID.randomUUID()); }
        void tick() { driver.tick(null); } void tick(int count) { for (int i = 0; i < count; i++) tick(); }
        void openChat() { input.screen = input.chat; input.draft = ""; }
        void send() { tick(); if (input.screen == null) openChat(); tick(); }
        void sendCompletion(int ordinal) { send(); observation = idle(completion(ordinal)); tick(); }
        void beginForward() { sendCompletion(1); sendCompletion(2); assertFalse(input.pressed); tick(); }
        void assertFailed(String reason) {
            assertEquals(1, input.stops, logs.toString());
            assertEquals(1, logs.stream().filter(s -> s.contains("DRIVER_FAIL")).count(), logs.toString());
            assertTrue(logs.stream().anyMatch(s -> s.contains(reason)), logs.toString());
            assertTrue(logs.stream().allMatch(s -> s.length() <= 4096));
        }
        PublishedCompletion completion(int ordinal) {
            return new PublishedCompletion(session, operations[ordinal - 1], ordinal,
                    ordinal == 2 || displaced && ordinal == 4 ? Command.RESET : displaced || ordinal < 4 ? Command.RUN : Command.RERUN,
                    (displaced ? ordinal == 5 : ordinal > 2) ? Optional.of(runs[ordinal - 1]) : Optional.empty(), ordinal == 1 || displaced && ordinal == 3 ? "FAIL" : "PASS",
                    true, "RELEASED", true, "operation-" + ordinal, "operation-" + ordinal + "/summary.json",
                    ordinal == 1 || displaced && ordinal == 3 ? Optional.of(new FailureObservation("java.lang.AssertionError", ordinal == 1 ? "Run requires this invocation's successful reset" : "Start state changed without physics")) : Optional.empty());
        }
        PublishedCompletion changed(PublishedCompletion c, String field) {
            return new PublishedCompletion(field.equals("session") ? UUID.randomUUID() : c.sessionId(),
                    field.equals("operation") ? operations[0] : c.operationId(), field.equals("ordinal") ? 4 : c.ordinal(),
                    field.equals("command") ? Command.RERUN : c.command(), field.equals("run") ? Optional.of(UUID.randomUUID()) : c.runId(),
                    field.equals("verdict") ? c.verdict().equals("FAIL") ? "PASS" : "FAIL" : c.verdict(), !field.equals("complete"),
                    field.equals("cleanup") ? "UNRESOLVED" : c.cleanupStatus(), !field.equals("settled"),
                    field.equals("directory") ? "operation-1" : c.evidenceDirectory(),
                    field.equals("summary") ? "operation-1/summary.json" : c.summaryPath(),
                    field.equals("failure") ? Optional.of(new FailureObservation("x", "x")) : c.failure());
        }
    }

    private static final class SimulatedChat implements InspectAcceptanceDriver.ChatBoundary {
        final Object chat = new Object(); Object screen; String draft = "", refusal, clientRefusal, entryRefusal, throwAt; boolean replaceOnChar;
        int stops; Runnable reenter; final List<String> events = new ArrayList<>(), commands = new ArrayList<>();
        public void requireClient(MinecraftClient current) {
            if (entryRefusal != null) throw new IllegalStateException(entryRefusal);
            requireCapturedClient();
        }
        private void requireCapturedClient() { if (clientRefusal != null) throw new IllegalStateException(clientRefusal); }
        public void captureBinding() { requireBinding(); }
        public void requireBinding() { if (refusal != null) throw new IllegalStateException(refusal); }
        public Object screen() { return screen; }
        public void requireChat(Object expected, String text) {
            if (screen != chat || screen != expected) throw new IllegalStateException("screen");
            if (!draft.equals(text)) throw new IllegalStateException("draft");
        }
        public int chatKey() { return 84; }
        public void key(int code, boolean press, Object expected) {
            events.add(code + (press ? "+" : "-"));
            if (code == 257 && press) { commands.add(draft); screen = null; }
            callback(code + (press ? "+" : "-"));
        }
        public void character(int codepoint) { draft += Character.toString(codepoint); if (replaceOnChar) screen = new Object(); callback("char"); }
        private void callback(String event) {
            if (reenter != null) reenter.run();
            if (event.equals(throwAt)) throw new IllegalStateException("callback " + event);
        }
        public void stop() { stops++; }
        final Object forward = new Object(); boolean pressed, clearFails; double x, z;
        public Object forwardBinding() { requireBinding(); return forward; }
        public InspectAcceptanceDriver.Pose pose() { requireBinding(); return new InspectAcceptanceDriver.Pose(x, z); }
        public boolean forwardPressed(Object binding) { requireCapturedClient(); assertSame(forward, binding); return pressed; }
        public void forward(Object binding, boolean press) {
            requireBinding(); assertSame(forward, binding); pressed = press;
            events.add("87" + (press ? "+" : "-")); callback("87" + (press ? "+" : "-"));
        }
        public void clearForward(Object binding) {
            requireCapturedClient(); assertSame(forward, binding); if (clearFails) throw new IllegalStateException("clear unavailable");
            pressed = false; events.add("captured-false");
        }
    }
}
