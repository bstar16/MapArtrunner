package com.mapartrunner.fabric.scenario;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import static com.mapartrunner.fabric.scenario.ScenarioHost.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Commands/Runner/Host lifecycle; synthetic native boundaries never claim chat/world acceptance. */
class ScenarioCommandsTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.createGameVersion(); net.minecraft.Bootstrap.initialize();
    }
    @TempDir Path directory;
    // JUnit owns cleanup of these temporary trees; the native game root is never a fixture cleanup target.
    Path receiptModule;

    @Test void grammarHasExactlyFourLiteralBranches() {
        var commands = new ScenarioCommands(new ArrayDeque<Runnable>()::add, nativeHost());
        var dispatcher = new CommandDispatcher<FabricClientCommandSource>(); commands.register(dispatcher);
        var root = dispatcher.getRoot().getChild("maparttest");
        assertNull(root.getCommand()); assertEquals(4, root.getChildren().size());
        for (var action : new String[]{"reset", "run", "rerun"}) {
            var node = root.getChild(action);
            assertNull(node.getCommand()); assertEquals(1, node.getChildren().size());
            assertNotNull(node.getChild("grounded-two-lane").getCommand());
            assertTrue(node.getChild("grounded-two-lane").getChildren().isEmpty());
        }
        assertNotNull(root.getChild("last").getCommand()); assertTrue(root.getChild("last").getChildren().isEmpty());
    }

    @Test void receiptFixturePublishesOnlyWithinTemporaryRootsAndPreservesNativeRoot() throws Exception {
        var nativeGame = loadedModule().resolve("build/run/inspect");
        var before = Files.exists(nativeGame, LinkOption.NOFOLLOW_LINKS) ? Files.readAttributes(nativeGame,
                java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS) : null;
        var launch = launch(); var launchId = launch.getFileName().toString().substring(7);
        var receipts = new ScenarioLaunchReceipt(launchId, launch.toString(), null, launch.getParent());
        var f = fixture(receipts, null);
        var roots = (ScenarioRunner.Roots) field(ScenarioRunner.class, "roots").get(f.runner);
        assertTrue(roots.game().startsWith(directory.toRealPath())); assertTrue(roots.evidence().startsWith(directory.toRealPath()));
        assertFalse(roots.game().startsWith(nativeGame)); assertFalse(roots.evidence().startsWith(loadedModule()));
        f.commands.enqueue(Command.RUN, message -> { }); f.queue.remove().run();
        var operation = f.outcomes.getFirst().operation();
        assertTrue(operation.evidenceDirectory().startsWith(roots.evidence())); assertEquals(roots.evidence(), launch.getParent());
        assertTrue(Files.isRegularFile(launch.resolve("inspect-op-1.begin.json")));
        assertTrue(Files.isRegularFile(launch.resolve("inspect-op-1.cleanup.json")));
        assertTrue(Files.isRegularFile(launch.resolve("inspect-op-1.end.json")));
        var now = Instant.now();
        assertThrows(AssertionError.class, () -> new ScenarioLaunchReceipt(launchId, launch.toString(), null, false)
                .operation(operation, "WORK", now, now.plusMillis(600000), null, null), "Existing portable constructor retains native authority");
        var foreign = new Operation(operation.sessionId(), 1, operation.operationId(), Command.RUN, launch);
        assertThrows(AssertionError.class, () -> receipts.operation(foreign, "WORK", now, now.plusMillis(600000), null, null));
        assertThrows(AssertionError.class, () -> new ScenarioLaunchReceipt(launchId, launch.toString(), null,
                roots.evidence().resolve(".")).operation(operation, "WORK", now, now.plusMillis(600000), null, null));
        assertEquals(before != null, Files.exists(nativeGame, LinkOption.NOFOLLOW_LINKS));
        if (before != null) {
            var after = Files.readAttributes(nativeGame, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            assertEquals(before.fileKey(), after.fileKey()); assertEquals(before.creationTime(), after.creationTime());
            assertEquals(before.lastModifiedTime(), after.lastModifiedTime());
        }
    }

    @Test void inactivePropertyIsInertAndUsesStandardBooleanSemantics() {
        var previous = System.getProperty("mapart.test.inspect");
        try {
            for (var value : List.of("false", "1", "yes", "")) {
                System.setProperty("mapart.test.inspect", value);
                assertDoesNotThrow(() -> new ScenarioClientInitializer().onInitializeClient());
                assertFalse(ScenarioClientInitializer.deferStop(null));
            }
            System.clearProperty("mapart.test.inspect");
            assertDoesNotThrow(() -> new ScenarioClientInitializer().onInitializeClient());
            assertFalse(ScenarioClientInitializer.deferStop(null));
            System.setProperty("mapart.test.inspect", "true");
            assertFalse(ScenarioClientInitializer.deferStop(null), "Absent installed owner is inert");
        } finally {
            if (previous == null) System.clearProperty("mapart.test.inspect"); else System.setProperty("mapart.test.inspect", previous);
        }
    }

    @Test void callbacksQueueOnlyOneWorkerAndRefuseBeforeReadyOrOnForeignWorld() throws Exception {
        var f = fixture(null, null); var replies = new ArrayList<String>();
        set(f.commands, "ready", false);
        assertEquals(0, f.commands.enqueue(Command.RUN, replies::add)); assertTrue(f.queue.isEmpty());
        set(f.commands, "ready", true);
        assertEquals(0, f.commands.submit(Command.RUN, null, replies::add));
        assertEquals(0, field(ScenarioRunner.class, "inspectOrdinal").getInt(f.runner)); assertTrue(f.queue.isEmpty());
        assertEquals(1, f.commands.enqueue(Command.RUN, replies::add));
        assertEquals(0, f.commands.enqueue(Command.RESET, replies::add)); assertEquals(1, f.queue.size());
        assertEquals(0, field(ScenarioRunner.class, "inspectOrdinal").getInt(f.runner));
        f.queue.remove().run(); assertTrue(f.queue.isEmpty());
        assertTrue(f.commands.lastDescription().contains("#1"));
    }

    @Test void runBeforeResetAndTwoResetFailuresHaveDistinctOwnedNoUuidEvidence() throws Exception {
        var f = fixture(null, null);
        for (var command : List.of(Command.RUN, Command.RESET, Command.RESET)) {
            assertEquals(1, f.commands.enqueue(command, message -> { })); f.queue.remove().run();
            var result = f.runner.lastInspectCompletion().orElseThrow();
            assertEquals(command, result.command()); assertEquals("FAIL", result.verdict());
            assertTrue(result.runId().isEmpty()); assertTrue(result.cleanupSettled());
            assertTrue(Files.exists(result.evidenceDirectory().resolve("operation.json")));
            assertTrue(Files.exists(result.summaryPath())); assertTrue(f.commands.isReady());
            assertTrue(f.commands.lastDescription().contains(result.operationId().toString()));
        }
        assertEquals(3, field(ScenarioRunner.class, "inspectOrdinal").getInt(f.runner));
        assertEquals(3, f.outcomes.size()); assertEquals(3, f.neutralScopes.size());
        assertEquals(3, f.outcomes.stream().map(o -> o.operation().operationId()).distinct().count());
        assertTrue(f.runner.lastInspectCompletion().orElseThrow().failure().getMessage().contains("startup"));
    }

    @Test void rerunSharesOneWorkScopeAndFailsAtResetBeforeRunWithoutRepairingWorld() throws Exception {
        var f = fixture(null, null);
        assertEquals(1, f.commands.enqueue(Command.RERUN, message -> { })); f.queue.remove().run();
        var result = f.runner.lastInspectCompletion().orElseThrow();
        assertEquals(Command.RERUN, result.command()); assertTrue(result.runId().isEmpty());
        assertTrue(result.failure().getMessage().contains("startup"));
        assertEquals(1, f.neutralScopes.size()); assertEquals(600_000_000_001L, f.neutralScopes.getFirst().deadlineNanos());
        assertEquals(1, f.outcomes.size()); assertEquals(result.operationId(), f.outcomes.getFirst().operation().operationId());
    }

    @Test void busyAndLastStayReadOnlyThroughActualHostSettlementAndOldCompletionCannotPromote() throws Exception {
        var holder = new Fixture[1]; var snapshots = new ArrayList<String>();
        var f = fixture(null, outcome -> {
            var current = holder[0]; snapshots.add(current.commands.lastDescription());
            assertEquals(0, current.commands.enqueue(Command.RESET, message -> { }));
            assertTrue(current.commands.lastDescription().contains("active"));
            assertEquals(outcome.operation().ordinal(), fieldInt(current.runner, "inspectOrdinal"));
        }); holder[0] = f;
        f.commands.enqueue(Command.RUN, message -> { }); f.queue.remove().run();
        var a = f.runner.lastInspectCompletion().orElseThrow(); byte[] bytes = Files.readAllBytes(a.summaryPath());
        f.commands.enqueue(Command.RESET, message -> { }); f.queue.remove().run();
        var b = f.runner.lastInspectCompletion().orElseThrow();
        assertTrue(snapshots.getFirst().contains("last: none")); assertTrue(snapshots.getLast().contains(a.operationId().toString()));
        assertTrue(f.commands.lastDescription().contains(b.operationId().toString()));
        var stale = new Operation(a.sessionId(), a.ordinal(), a.operationId(), a.command(), a.evidenceDirectory());
        assertThrows(IllegalStateException.class, () -> invoke(f.commands, "publish", new Class<?>[]{Operation.class, ScenarioRunner.InspectCompletion.class}, stale, a));
        assertTrue(f.commands.lastDescription().contains(b.operationId().toString())); assertArrayEquals(bytes, Files.readAllBytes(a.summaryPath()));
    }

    @Test void everyN1FailureKeepsPriorLastAndPermanentlyRefusesSuccessorAfterWorkerReturn() throws Exception {
        for (var boundary : List.of("allocation", "header", "pre-link", "post-link")) {
            var launch = launch();
            var receipts = new ScenarioLaunchReceipt(launch.getFileName().toString().substring(7), launch.toString(),
                    path -> { if (boundary.equals("post-link") && path.getFileName().toString().equals("inspect-op-2.begin.json"))
                        throw new IllegalStateException("after native hard link"); }, launch.getParent());
            Consumer<Operation> header = operation -> {
                if (boundary.equals("header") && operation.ordinal() == 2)
                    assertDoesNotThrow(() -> Files.writeString(operation.evidenceDirectory().resolve("operation.json"), "collision"));
            };
            var f = fixture(receipts, null, header);
            f.commands.enqueue(Command.RUN, message -> { }); f.queue.remove().run();
            var prior = f.runner.lastInspectCompletion().orElseThrow(); byte[] bytes = Files.readAllBytes(prior.summaryPath());
            if (boundary.equals("allocation")) {
                // Inject only the unavailable allocation parent; preserve every prior operation byte.
                var blocked = prior.evidenceDirectory().getParent().resolve("allocation-blocked"); Files.writeString(blocked, "blocked");
                set(f.runner, "inspectDirectory", blocked);
            }
            if (boundary.equals("pre-link")) Files.writeString(launch.resolve("inspect-op-2.begin.json"), "existing BEGIN");
            f.commands.enqueue(Command.RESET, message -> { }); f.queue.remove().run();
            var failed = f.runner.failedInspectAdmission().orElseThrow();
            assertEquals(2, failed.ordinal()); assertFalse(f.commands.isReady());
            assertSame(prior, f.runner.lastInspectCompletion().orElseThrow());
            assertTrue(f.commands.lastDescription().contains(prior.operationId().toString()));
            for (var command : List.of(Command.RESET, Command.RUN, Command.RERUN)) {
                var replies = new ArrayList<String>(); assertEquals(0, f.commands.enqueue(command, replies::add));
                assertTrue(replies.getFirst().contains("restart required"));
            }
            assertTrue(f.queue.isEmpty()); assertEquals(2, fieldInt(f.runner, "inspectOrdinal"));
            assertEquals(1, f.neutralScopes.size(), "No native work after damaged admission");
            assertArrayEquals(bytes, Files.readAllBytes(prior.summaryPath()));
            assertFalse(Files.exists(launch.resolve("inspect-op-3.begin.json")));
            if (boundary.equals("post-link")) assertTrue(Files.readString(launch.resolve("inspect-op-2.begin.json")).contains("WORK"));
            var begin = launch.resolve("inspect-op-2.begin.json");
            byte[] retainedBegin = Files.exists(begin) ? Files.readAllBytes(begin) : null;
            assertTrue(f.commands.requestStop(), "N1 survives terminal mutation admission");
            assertTrue(f.commands.requestStop()); assertEquals(1, f.queue.size());
            f.queue.remove().run(); assertTrue(f.commands.releaseNativeStop()); assertFalse(f.commands.requestStop());
            assertTrue(field(ScenarioRunner.class, "closed").getBoolean(f.runner));
            assertEquals(2, fieldInt(f.runner, "inspectOrdinal")); assertSame(prior, f.runner.lastInspectCompletion().orElseThrow());
            assertFalse(Files.exists(launch.resolve("inspect-op-2.cleanup.json"))); assertFalse(Files.exists(launch.resolve("inspect-op-2.end.json")));
            if (retainedBegin != null) assertArrayEquals(retainedBegin, Files.readAllBytes(begin));
            assertEquals(0, f.commands.enqueue(Command.RUN, message -> { })); assertTrue(f.queue.isEmpty());
            assertArrayEquals(bytes, Files.readAllBytes(prior.summaryPath()));
        }
    }

    @Test void unresolvedSettlementCannotReopenAdmissionOrPromoteLast() throws Exception {
        var f = fixture(null, outcome -> { throw new IllegalStateException("END settlement failed"); });
        f.commands.enqueue(Command.RUN, message -> { }); f.queue.remove().run();
        assertFalse(f.commands.isReady()); assertTrue(f.runner.lastInspectCompletion().isEmpty());
        assertEquals(0, f.commands.enqueue(Command.RESET, message -> { }));
        assertEquals(1, fieldInt(f.runner, "inspectOrdinal")); assertTrue(f.queue.isEmpty());
        assertThrows(AssertionError.class, f.runner::close); assertEquals(1, fieldInt(f.runner, "inspectOrdinal"));
        assertFalse(f.commands.requestStop()); assertTrue(f.queue.isEmpty());
    }

    @Test void unresolvedRunnerReleaseCannotPromoteLastOrReserveSuccessorAfterEnd() throws Exception {
        var f = fixture(null, null, operation -> { }, true);
        set(f.runner, "worldCreated", true); // Synthetic native envelope; actual release is deliberately unavailable.
        f.commands.enqueue(Command.RUN, message -> { }); f.queue.remove().run();
        assertEquals(CleanupStatus.UNRESOLVED, f.outcomes.getFirst().cleanup());
        assertTrue(f.runner.lastInspectCompletion().isEmpty()); assertFalse(f.commands.isReady());
        for (var command : List.of(Command.RESET, Command.RUN, Command.RERUN))
            assertEquals(0, f.commands.enqueue(command, message -> { }));
        assertEquals(1, fieldInt(f.runner, "inspectOrdinal")); assertTrue(f.queue.isEmpty());
        assertThrows(AssertionError.class, f.runner::close);
    }

    @Test void cancellationInvalidatesQueuedWorkAndNeverBindsAReplacement() throws Exception {
        var f = fixture(null, null); f.commands.enqueue(Command.RUN, message -> { });
        f.commands.cancel(new IllegalStateException("foreign replacement")); f.queue.remove().run();
        assertEquals(0, fieldInt(f.runner, "inspectOrdinal")); assertTrue(f.runner.lastInspectCompletion().isEmpty());
        assertEquals(0, f.commands.enqueue(Command.RESET, message -> { })); assertTrue(f.commands.lastDescription().contains("restart required"));
    }

    @Test void cancellationDuringSettlementRetainsPriorPublishedLast() throws Exception {
        var holder = new Fixture[1];
        var f = fixture(null, outcome -> {
            if (outcome.operation().ordinal() == 2) holder[0].commands.cancel(new IllegalStateException("replacement during END"));
        }); holder[0] = f;
        f.commands.enqueue(Command.RUN, message -> { }); f.queue.remove().run();
        var prior = f.runner.lastInspectCompletion().orElseThrow();
        var replies = new ArrayList<String>(); f.commands.enqueue(Command.RESET, replies::add); f.queue.remove().run();
        assertTrue(f.commands.lastDescription().contains(prior.operationId().toString()));
        assertTrue(f.commands.lastDescription().contains("restart required"));
        assertTrue(replies.getLast().contains("restart required"));
        assertEquals(0, f.commands.enqueue(Command.RUN, message -> { }));
        assertEquals(2, fieldInt(f.runner, "inspectOrdinal"));
    }

    @Test void nativeCancellationAndStoppingReturnWhileRealBeginPublicationStillHoldsPhaseLock() throws Exception {
        for (var boundary : List.of("cancel", "stopping", "application-exit")) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var returned = new CountDownLatch(1); var holder = new Fixture[1];
            var launch = launch();
            var receipts = new ScenarioLaunchReceipt(launch.getFileName().toString().substring(7), launch.toString(), path -> {
                if (!path.getFileName().toString().equals("inspect-op-1.begin.json")) return;
                assertTrue(assertDoesNotThrow(() -> (java.util.concurrent.locks.ReentrantLock)
                        field(InspectScenarioHost.class, "lock").get(holder[0].nativeHost)).isHeldByCurrentThread());
                entered.countDown(); assertDoesNotThrow(() -> assertTrue(release.await(10, TimeUnit.SECONDS)));
            }, launch.getParent());
            var f = fixture(receipts, null); holder[0] = f;
            var callbackFailure = new AtomicReference<Throwable>(); var expected = new AtomicReference<Throwable>();
            var original = new IllegalStateException("native cancellation original");
            var callback = new Thread(() -> {
                f.nativeThread.set(true);
                try {
                    if (boundary.equals("stopping")) f.nativeHost.onStopping(null);
                    if (boundary.equals("application-exit")) assertTrue(f.commands.requestStop());
                    else f.commands.cancel(original); // Same synchronous owner call as the initializer lifecycle callbacks.
                    @SuppressWarnings("unchecked") var cause = (AtomicReference<Throwable>) field(InspectScenarioHost.class, "originalFailure").get(f.nativeHost);
                    expected.set(cause.get());
                } catch (Throwable failure) { callbackFailure.set(failure); }
                finally { f.nativeThread.remove(); returned.countDown(); }
            }, "inspect-native-cancel-test");
            f.commands.enqueue(Command.RUN, message -> { });
            var worker = new Thread(f.queue.remove(), "inspect-command-publication-test");
            try {
                worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); callback.start();
                assertTrue(returned.await(2, TimeUnit.SECONDS), "Native callback blocked behind receipt publication");
                assertEquals(1, release.getCount(), "Callback must return before the phase lock is released");
                assertNull(callbackFailure.get()); assertNotNull(expected.get());
                if (boundary.equals("cancel")) assertSame(original, expected.get());
                if (boundary.equals("application-exit")) assertEquals("Inspect application exit requested", expected.get().getMessage());
                assertFalse(f.commands.isReady());
            } finally {
                release.countDown(); worker.join(5000); callback.join(5000);
                assertFalse(worker.isAlive()); assertFalse(callback.isAlive());
            }
            assertSame(expected.get(), f.runner.lastInspectCompletion().orElseThrow().failure());
            assertTrue(f.neutralScopes.isEmpty()); assertEquals(0, f.nativeEffects.get());
            assertTrue(f.commands.lastDescription().contains("last: none"));
            assertEquals(0, f.commands.enqueue(Command.RESET, message -> { }));
            assertEquals(1, fieldInt(f.runner, "inspectOrdinal"));
        }
    }

    @Test void initializationReturnsImmediatelyAndCannotManufactureReadyOnFactoryFailure() throws Exception {
        var queue = new ArrayDeque<Runnable>(); var nativeEffects = new AtomicInteger();
        var host = new InspectScenarioHost(() -> 1L, () -> false, () -> false,
                action -> nativeEffects.incrementAndGet(), action -> nativeEffects.incrementAndGet(), outcome -> fail("No startup outcome"));
        var commands = new ScenarioCommands(queue::add, host); var stops = new AtomicInteger();
        commands.initialize(new ScenarioObserver.Watch(), new ScenarioLaunchReceipt(null, null), stops::incrementAndGet);
        assertEquals(1, queue.size()); assertFalse(commands.isReady()); assertEquals(0, stops.get());
        // The real factory reads native root metadata, then refuses the unit launcher's unmanaged binding.
        var diagnostic = new java.io.ByteArrayOutputStream(); var previous = System.err;
        try {
            System.setErr(new java.io.PrintStream(diagnostic)); queue.remove().run();
        } finally { System.setErr(previous); }
        var failure = (Throwable) field(ScenarioCommands.class, "terminal").get(commands);
        var nativeGame = loadedModule().resolve("build/run/inspect");
        assertTrue(failure instanceof IllegalStateException && "Cannot establish loaded-module authority".equals(failure.getMessage())
                        && failure.getCause() instanceof NoSuchFileException missing && nativeGame.equals(Path.of(missing.getFile()))
                || failure instanceof AssertionError && "Unmanaged/redirected game root".equals(failure.getMessage()),
                () -> "Unexpected startup failure: " + failure);
        var text = diagnostic.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("Inspect startup failed; ordinary client stop requested"));
        assertTrue(text.contains(failure.toString()), "Retain the actual authority refusal diagnostic");
        assertNull(field(ScenarioCommands.class, "runner").get(commands));
        assertEquals(0, nativeEffects.get()); assertTrue(queue.isEmpty());
        assertEquals(1, stops.get()); assertFalse(commands.isReady());
        assertFalse(commands.requestStop(), "Failure's native stop must pass through without recursive CLOSE");
        assertEquals(0, commands.enqueue(Command.RUN, message -> { }));
        assertThrows(IllegalStateException.class, () -> commands.initialize(new ScenarioObserver.Watch(), null, () -> { }));
    }

    @Test void firstExitClosesAdmissionAndRepeatedRequestsNeverRenewOrQueueAnotherClose() throws Exception {
        var f = fixture(null, null); var now = new AtomicLong(1);
        set(f.commands, "clock", (LongSupplier) now::get);
        assertTrue(f.commands.requestStop()); assertEquals(1, f.queue.size());
        assertEquals(0, f.commands.enqueue(Command.RUN, message -> { }));
        now.set(9_000_000_001L); assertTrue(f.commands.requestStop()); assertEquals(1, f.queue.size());
        now.set(10_000_000_001L); assertTrue(f.commands.releaseNativeStop());
        assertFalse(f.commands.requestStop()); f.queue.remove().run();
        assertEquals(0, fieldInt(f.runner, "inspectOrdinal"), "Cutoff cannot allocate late CLOSE permission");
        assertTrue(f.outcomes.isEmpty()); assertEquals(0, f.nativeEffects.get());
    }

    @Test void cutoffDuringCloseAllocationRetainsIncompleteTruthWithoutIssuingLateWork() throws Exception {
        var now = new AtomicLong(1);
        var f = fixture(null, null, operation -> {
            if (operation.command() == Command.CLOSE) now.set(10_000_000_001L);
        }); set(f.commands, "clock", (LongSupplier) now::get);
        f.commands.enqueue(Command.RUN, message -> { }); f.queue.remove().run();
        var prior = f.runner.lastInspectCompletion().orElseThrow();
        assertTrue(f.commands.requestStop()); f.queue.remove().run();
        var pending = (Operation) field(ScenarioRunner.class, "inspectOperation").get(f.runner);
        assertEquals(Command.CLOSE, pending.command()); assertEquals(2, pending.ordinal());
        var header = pending.evidenceDirectory().resolve("operation.json"); var bytes = Files.readAllBytes(header);
        assertFalse(Files.exists(pending.evidenceDirectory().resolve("completion.json")));
        assertNull(field(InspectScenarioHost.class, "current").get(f.nativeHost));
        assertSame(prior, f.runner.lastInspectCompletion().orElseThrow()); assertEquals(1, f.outcomes.size());
        assertTrue(f.commands.releaseNativeStop()); assertFalse(f.commands.requestStop()); assertTrue(f.queue.isEmpty());
        assertArrayEquals(bytes, Files.readAllBytes(header)); assertEquals(0, f.nativeEffects.get());
    }

    @Test void idleExitUsesActualCloseScopeAndSettlesWhileDisconnectRetainsPublishedLast() throws Exception {
        var holder = new Fixture[1];
        var f = fixture(null, outcome -> {
            if (outcome.operation().command() == Command.CLOSE) {
                assertTrue(holder[0].commands.requestStop(), "Reentrant native intent stays deferred through END");
                holder[0].commands.cancel(new IllegalStateException("Inspect connection disconnected; restart required"));
            }
        }); holder[0] = f;
        f.commands.enqueue(Command.RUN, message -> { }); f.queue.remove().run();
        var prior = f.runner.lastInspectCompletion().orElseThrow();
        assertTrue(f.commands.requestStop()); f.queue.remove().run();
        var close = f.runner.lastInspectCompletion().orElseThrow();
        assertEquals(Command.CLOSE, close.command()); assertTrue(close.cleanupSettled()); assertEquals("PASS", close.verdict());
        assertTrue(Files.exists(close.summaryPath())); assertTrue(field(ScenarioRunner.class, "closed").getBoolean(f.runner));
        assertEquals(2, f.outcomes.size()); assertTrue(f.commands.lastDescription().contains(prior.operationId().toString()));
        assertFalse(f.commands.lastDescription().contains(close.operationId().toString()));
        assertTrue(f.commands.releaseNativeStop()); assertFalse(f.commands.requestStop()); assertTrue(f.queue.isEmpty());
    }

    @Test void exitCancelsQueuedWorkWithoutReservingSuccessorClose() throws Exception {
        var f = fixture(null, null); f.commands.enqueue(Command.RUN, message -> { });
        assertTrue(f.commands.requestStop()); assertEquals(1, f.queue.size());
        f.queue.remove().run(); assertTrue(f.commands.releaseNativeStop());
        assertEquals(0, fieldInt(f.runner, "inspectOrdinal")); assertTrue(f.outcomes.isEmpty());
        assertEquals(0, f.commands.enqueue(Command.RUN, message -> { }));
    }

    @Test void pendingWorkExitUnwindsItsOriginalCleanupAndRetainsPriorLastAndCause() throws Exception {
        var holder = new Fixture[1]; var stopInWork = new AtomicBoolean();
        var f = fixture(null, null, operation -> { }, false, scope -> {
            if (stopInWork.get()) {
                assertTrue(holder[0].commands.requestStop());
                assertTrue(holder[0].commands.requestStop());
            }
        }); holder[0] = f;
        f.commands.enqueue(Command.RUN, message -> { }); f.queue.remove().run();
        var prior = f.runner.lastInspectCompletion().orElseThrow(); stopInWork.set(true);
        f.commands.enqueue(Command.RESET, message -> { }); f.queue.remove().run();
        var cancelled = f.runner.lastInspectCompletion().orElseThrow();
        assertEquals(Command.RESET, cancelled.command()); assertTrue(cancelled.cleanupSettled());
        assertEquals("Inspect application exit requested", cancelled.failure().getMessage());
        assertTrue(f.commands.lastDescription().contains(prior.operationId().toString()));
        assertSame(prior, field(ScenarioCommands.class, "last").get(f.commands));
        assertTrue(f.commands.releaseNativeStop()); assertEquals(2, fieldInt(f.runner, "inspectOrdinal"));
        assertEquals(2, f.outcomes.size()); assertTrue(f.queue.isEmpty()); assertEquals(0, f.nativeEffects.get());
    }

    @Test void exitDuringIssuedCleanupRetainsOriginalFailureAndDoesNotStartAnotherClose() throws Exception {
        var holder = new Fixture[1]; var original = new AtomicReference<Throwable>();
        var f = fixture(null, outcome -> {
            if (outcome.operation().ordinal() != 2) return;
            var current = assertDoesNotThrow(() -> (Scope) field(InspectScenarioHost.class, "current").get(holder[0].nativeHost));
            assertEquals(Kind.CLEANUP, current.kind());
            @SuppressWarnings("unchecked") var cause = assertDoesNotThrow(() -> (AtomicReference<Throwable>)
                    field(InspectScenarioHost.class, "originalFailure").get(holder[0].nativeHost));
            original.set(cause.get());
            assertTrue(holder[0].commands.requestStop()); assertSame(original.get(), cause.get());
        }); holder[0] = f;
        f.commands.enqueue(Command.RUN, message -> { }); f.queue.remove().run();
        var prior = f.runner.lastInspectCompletion().orElseThrow();
        f.commands.enqueue(Command.RESET, message -> { }); f.queue.remove().run();
        assertSame(original.get(), f.runner.lastInspectCompletion().orElseThrow().failure());
        assertTrue(f.commands.lastDescription().contains(prior.operationId().toString()));
        assertEquals(2, fieldInt(f.runner, "inspectOrdinal")); assertTrue(f.queue.isEmpty());
        assertTrue(f.commands.releaseNativeStop()); assertEquals(0, f.nativeEffects.get());
    }

    @Test void pendingStartupExitUsesOriginalStartupCleanupWithoutN1OrOrdinaryLast() throws Exception {
        var f = fixture(null, null);
        invoke(f.runner, "reserveInspectStartup", new Class<?>[]{});
        set(f.commands, "ready", false); set(f.commands, "queued", true);
        Runnable startup = () -> {
            assertTrue(f.commands.requestStop()); f.nativeHost.waitTick();
        };
        var failure = assertThrows(IllegalStateException.class, () -> invoke(f.runner, "initializeInspectStartup",
                new Class<?>[]{Runnable.class}, startup));
        invoke(f.commands, "workerFinished", new Class<?>[]{});
        assertEquals("Inspect application exit requested", failure.getMessage());
        assertTrue(f.runner.inspectAdmissionFailure().isEmpty()); assertTrue(f.runner.failedInspectAdmission().isEmpty());
        assertNull(field(InspectScenarioHost.class, "current").get(f.nativeHost));
        assertTrue(f.runner.lastInspectCompletion().isEmpty()); assertTrue(f.outcomes.isEmpty());
        assertEquals(0, fieldInt(f.runner, "inspectOrdinal")); assertTrue(f.commands.releaseNativeStop());
        assertFalse(f.commands.requestStop()); assertTrue(f.queue.isEmpty()); assertEquals(0, f.nativeEffects.get());
    }

    @Test void damagedCloseAdmissionUsesN1OnceWithoutRepairingItsPhaseStream() throws Exception {
        var f = fixture(null, null, operation -> {
            if (operation.command() == Command.CLOSE)
                assertDoesNotThrow(() -> Files.writeString(operation.evidenceDirectory().resolve("operation.json"), "collision"));
        });
        f.commands.enqueue(Command.RUN, message -> { }); f.queue.remove().run();
        var prior = f.runner.lastInspectCompletion().orElseThrow();
        assertTrue(f.commands.requestStop()); f.queue.remove().run();
        assertTrue(f.runner.inspectAdmissionFailure().isPresent()); assertEquals(2, fieldInt(f.runner, "inspectOrdinal"));
        assertTrue(field(ScenarioRunner.class, "closed").getBoolean(f.runner));
        assertTrue(field(InspectScenarioHost.class, "shutdownIssued").getBoolean(f.nativeHost));
        assertSame(prior, f.runner.lastInspectCompletion().orElseThrow()); assertEquals(1, f.outcomes.size());
        assertTrue(f.commands.releaseNativeStop()); assertFalse(f.commands.requestStop()); assertTrue(f.queue.isEmpty());
    }

    @Test void pendingNativeActionIsRefusedAfterExitCancellationAndNativeStopping() throws Exception {
        var holder = new Fixture[1]; var nativeQueue = new LinkedBlockingQueue<Runnable>(); var effects = new AtomicInteger();
        var f = fixture(null, null, operation -> { }, false,
                scope -> holder[0].nativeHost.runOnClient(client -> effects.incrementAndGet()), nativeQueue::add);
        holder[0] = f;
        f.commands.enqueue(Command.RUN, message -> { }); var worker = new Thread(f.queue.remove(), "inspect-late-action-test");
        try {
            worker.start(); var late = nativeQueue.poll(2, TimeUnit.SECONDS); assertNotNull(late);
            assertTrue(f.commands.requestStop());
            worker.join(5000); assertFalse(worker.isAlive()); assertTrue(f.commands.releaseNativeStop());
            f.nativeThread.set(true);
            try { f.nativeHost.onStopping(null); late.run(); }
            finally { f.nativeThread.remove(); }
            assertEquals(0, effects.get()); assertEquals(1, fieldInt(f.runner, "inspectOrdinal"));
            assertEquals("Inspect application exit requested", f.runner.lastInspectCompletion().orElseThrow().failure().getMessage());
            assertFalse(f.commands.requestStop()); assertTrue(f.queue.isEmpty());
        } finally {
            f.commands.cancel(new IllegalStateException("test finally")); worker.join(5000); assertFalse(worker.isAlive());
        }
    }

    @Test void retainedN1CannotRenewIssuedExpiredOrSettledCleanup() throws Exception {
        for (var state : List.of("issued", "expired", "settled", "stopped")) {
            var f = fixture(null, null, operation -> {
                assertDoesNotThrow(() -> Files.writeString(operation.evidenceDirectory().resolve("operation.json"), "collision"));
            });
            f.commands.enqueue(Command.RUN, message -> { }); f.queue.remove().run();
            var failed = f.runner.failedInspectAdmission().orElseThrow();
            var now = new AtomicLong(1); set(f.nativeHost, "clock", (LongSupplier) now::get);
            var cleanup = f.nativeHost.beginShutdownCleanup(failed, f.runner.inspectAdmissionFailure().orElseThrow());
            if (state.equals("expired")) now.set(cleanup.deadlineNanos());
            if (state.equals("settled")) f.nativeHost.inScope(cleanup, () -> f.nativeHost.settleCleanup(cleanup,
                    new FinalOutcome(failed, Verdict.FAILURE, CleanupStatus.SETTLED)));
            if (state.equals("stopped")) {
                f.nativeThread.set(true); try { f.nativeHost.onStopping(null); } finally { f.nativeThread.remove(); }
            }
            var current = field(InspectScenarioHost.class, "current").get(f.nativeHost);
            assertTrue(f.commands.requestStop()); f.queue.remove().run();
            assertSame(current, field(InspectScenarioHost.class, "current").get(f.nativeHost));
            assertEquals(1, fieldInt(f.runner, "inspectOrdinal")); assertTrue(f.outcomes.isEmpty());
            assertTrue(f.commands.releaseNativeStop()); assertFalse(f.commands.requestStop());
            assertEquals(0, f.nativeEffects.get()); assertTrue(f.queue.isEmpty());
        }
    }

    private Fixture fixture(ScenarioLaunchReceipt receipts, Consumer<FinalOutcome> observe) throws Exception {
        return fixture(receipts, observe, operation -> { });
    }
    private Fixture fixture(ScenarioLaunchReceipt receipts, Consumer<FinalOutcome> observe, Consumer<Operation> header) throws Exception {
        return fixture(receipts, observe, header, false);
    }
    private Fixture fixture(ScenarioLaunchReceipt receipts, Consumer<FinalOutcome> observe, Consumer<Operation> header,
                            boolean failRelease) throws Exception {
        return fixture(receipts, observe, header, failRelease, scope -> { });
    }
    private Fixture fixture(ScenarioLaunchReceipt receipts, Consumer<FinalOutcome> observe, Consumer<Operation> header,
                            boolean failRelease, Consumer<Scope> onNeutral) throws Exception {
        return fixture(receipts, observe, header, failRelease, onNeutral,
                action -> fail("No native client action in lifecycle fixture"));
    }
    private Fixture fixture(ScenarioLaunchReceipt receipts, Consumer<FinalOutcome> observe, Consumer<Operation> header,
                            boolean failRelease, Consumer<Scope> onNeutral, Consumer<Runnable> clientQueue) throws Exception {
        var outcomes = new ArrayList<FinalOutcome>(); var neutral = new ArrayList<Scope>();
        var nativeThread = ThreadLocal.withInitial(() -> false); var nativeEffects = new AtomicInteger();
        var nativeHost = new InspectScenarioHost(() -> 1L, nativeThread::get, () -> false,
                action -> { nativeEffects.incrementAndGet(); clientQueue.accept(action); },
                action -> { nativeEffects.incrementAndGet(); fail("No native server action in lifecycle fixture"); },
                outcome -> { outcomes.add(outcome); if (observe != null) observe.accept(outcome); }, receipts, Instant::now);
        // Only native chat observation is substituted. All scope/evidence/admission/settlement owners are real.
        var host = (Inspect) Proxy.newProxyInstance(Inspect.class.getClassLoader(), new Class<?>[]{Inspect.class}, (proxy, method, arguments) -> {
            if (method.getName().equals("awaitNeutralChat")) {
                var current = (Scope) field(InspectScenarioHost.class, "current").get(nativeHost);
                onNeutral.accept(current);
                var op = current.operation();
                neutral.add(nativeHost.requireScope(op, Kind.WORK)); return null;
            }
            try { return method.invoke(nativeHost, arguments); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        var module = receipts == null ? Files.createTempDirectory(directory, "module-") : receiptModule;
        var build = Files.createDirectories(module.resolve("build"));
        var game = Files.createDirectories(build.resolve("run/inspect"));
        var evidence = build.resolve("scenario-results");
        var roots = new ScenarioRunner.Roots(module, game, build, evidence, game.resolve("saves"));
        var watch = new ScenarioObserver.Watch();
        var runner = failRelease ? new ScenarioRunner(host, watch, roots,
                () -> { throw new IllegalStateException("owned release unavailable"); }, watch::freeze)
                : new ScenarioRunner(host, watch, roots, header);
        var queue = new ArrayDeque<Runnable>(); var commands = new ScenarioCommands(queue::add, host);
        set(commands, "runner", runner); set(commands, "ready", true);
        return new Fixture(commands, runner, queue, outcomes, neutral, nativeHost, nativeThread, nativeEffects);
    }

    private Path launch() throws Exception {
        if (receiptModule == null) receiptModule = Files.createTempDirectory(directory, "receipt-module-");
        var root = Files.createDirectories(receiptModule.resolve("build/scenario-results"));
        return Files.createDirectory(root.resolve("launch-" + UUID.randomUUID()));
    }
    private static Path loadedModule() throws Exception {
        var source = Path.of(ScenarioRunner.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        return source.getParent().getParent().getParent().getParent();
    }
    private static InspectScenarioHost nativeHost() {
        return new InspectScenarioHost(() -> 1L, () -> false, () -> false, Runnable::run, Runnable::run, outcome -> { });
    }
    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void set(Object owner, String name, Object value) throws Exception { field(owner.getClass(), name).set(owner, value); }
    private static int fieldInt(Object owner, String name) {
        try { return field(owner.getClass(), name).getInt(owner); } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static void invoke(Object owner, String name, Class<?>[] types, Object... arguments) {
        try { var method = owner.getClass().getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(owner, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Error fatal) throw fatal; throw (RuntimeException) failure.getCause();
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private record Fixture(ScenarioCommands commands, ScenarioRunner runner, ArrayDeque<Runnable> queue,
                           List<FinalOutcome> outcomes, List<Scope> neutralScopes, InspectScenarioHost nativeHost,
                           ThreadLocal<Boolean> nativeThread, AtomicInteger nativeEffects) { }
}
