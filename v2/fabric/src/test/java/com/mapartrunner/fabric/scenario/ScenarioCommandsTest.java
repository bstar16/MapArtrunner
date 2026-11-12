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
    final List<Path> ownedTrees = new ArrayList<>();
    Path ownedInspectRoot;
    Object ownedInspectKey;
    java.nio.file.attribute.FileTime ownedInspectCreation;

    @AfterEach void removeOnlyFreshFixtureEvidence() throws Exception {
        try {
            var evidence = loadedModule().resolve("build/scenario-results");
            for (var root : ownedTrees) if (Files.exists(root)) {
                assertEquals(evidence, root.getParent()); assertEquals(root, root.toRealPath());
                try (var files = Files.walk(root)) {
                    for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
                }
            }
        } finally {
            if (ownedInspectRoot != null) {
                assertEquals(loadedModule().resolve("build/run/inspect"), ownedInspectRoot);
                assertEquals(ownedInspectRoot, ownedInspectRoot.toRealPath());
                var attributes = Files.readAttributes(ownedInspectRoot,
                        java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                assertTrue(attributes.isDirectory()); assertFalse(attributes.isSymbolicLink()); assertFalse(attributes.isOther());
                if (ownedInspectKey != null) assertEquals(ownedInspectKey, attributes.fileKey());
                assertEquals(ownedInspectCreation, attributes.creationTime());
                try (var files = Files.list(ownedInspectRoot)) { assertEquals(0, files.count(), "Preserve unexpected fixture-root files"); }
                // Nonrecursive deletion refuses to remove any files that appeared in this empty fixture root.
                Files.delete(ownedInspectRoot);
            }
        }
    }

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

    @Test void inactivePropertyIsInertAndUsesStandardBooleanSemantics() {
        var previous = System.getProperty("mapart.test.inspect");
        try {
            for (var value : List.of("false", "1", "yes", "")) {
                System.setProperty("mapart.test.inspect", value);
                assertDoesNotThrow(() -> new ScenarioClientInitializer().onInitializeClient());
            }
            System.clearProperty("mapart.test.inspect");
            assertDoesNotThrow(() -> new ScenarioClientInitializer().onInitializeClient());
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
                        throw new IllegalStateException("after native hard link"); }, false);
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
            // Exercise the existing Runner capability only: native pre-stop integration belongs to the follow-up slice.
            f.runner.close();
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
        for (boolean stopping : List.of(false, true)) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var returned = new CountDownLatch(1); var holder = new Fixture[1];
            var launch = launch();
            var receipts = new ScenarioLaunchReceipt(launch.getFileName().toString().substring(7), launch.toString(), path -> {
                if (!path.getFileName().toString().equals("inspect-op-1.begin.json")) return;
                assertTrue(assertDoesNotThrow(() -> (java.util.concurrent.locks.ReentrantLock)
                        field(InspectScenarioHost.class, "lock").get(holder[0].nativeHost)).isHeldByCurrentThread());
                entered.countDown(); assertDoesNotThrow(() -> assertTrue(release.await(10, TimeUnit.SECONDS)));
            }, false);
            var f = fixture(receipts, null); holder[0] = f;
            var callbackFailure = new AtomicReference<Throwable>(); var expected = new AtomicReference<Throwable>();
            var original = new IllegalStateException("native cancellation original");
            var callback = new Thread(() -> {
                f.nativeThread.set(true);
                try {
                    if (stopping) f.nativeHost.onStopping(null);
                    f.commands.cancel(original); // Same synchronous owner call as the initializer lifecycle callbacks.
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
                if (!stopping) assertSame(original, expected.get());
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
        var queue = new ArrayDeque<Runnable>(); var commands = new ScenarioCommands(queue::add, nativeHost());
        var stops = new AtomicInteger();
        commands.initialize(new ScenarioObserver.Watch(), new ScenarioLaunchReceipt(null, null), stops::incrementAndGet);
        assertEquals(1, queue.size()); assertFalse(commands.isReady()); assertEquals(0, stops.get());
        // Unit launcher has no inspect game root: real factory refuses before any native world action.
        queue.remove().run(); assertEquals(1, stops.get()); assertFalse(commands.isReady());
        assertEquals(0, commands.enqueue(Command.RUN, message -> { }));
        assertThrows(IllegalStateException.class, () -> commands.initialize(new ScenarioObserver.Watch(), null, () -> { }));
    }

    private Fixture fixture(ScenarioLaunchReceipt receipts, Consumer<FinalOutcome> observe) throws Exception {
        return fixture(receipts, observe, operation -> { });
    }
    private Fixture fixture(ScenarioLaunchReceipt receipts, Consumer<FinalOutcome> observe, Consumer<Operation> header) throws Exception {
        return fixture(receipts, observe, header, false);
    }
    private Fixture fixture(ScenarioLaunchReceipt receipts, Consumer<FinalOutcome> observe, Consumer<Operation> header,
                            boolean failRelease) throws Exception {
        var outcomes = new ArrayList<FinalOutcome>(); var neutral = new ArrayList<Scope>();
        var nativeThread = ThreadLocal.withInitial(() -> false); var nativeEffects = new AtomicInteger();
        var nativeHost = new InspectScenarioHost(() -> 1L, nativeThread::get, () -> false,
                action -> { nativeEffects.incrementAndGet(); fail("No native client action in lifecycle fixture"); },
                action -> { nativeEffects.incrementAndGet(); fail("No native server action in lifecycle fixture"); },
                outcome -> { outcomes.add(outcome); if (observe != null) observe.accept(outcome); }, receipts, Instant::now);
        // Only native chat observation is substituted. All scope/evidence/admission/settlement owners are real.
        var host = (Inspect) Proxy.newProxyInstance(Inspect.class.getClassLoader(), new Class<?>[]{Inspect.class}, (proxy, method, arguments) -> {
            if (method.getName().equals("awaitNeutralChat")) {
                var op = ((Scope) field(InspectScenarioHost.class, "current").get(nativeHost)).operation();
                neutral.add(nativeHost.requireScope(op, Kind.WORK)); return null;
            }
            try { return method.invoke(nativeHost, arguments); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        var module = receipts == null ? Files.createTempDirectory(directory, "module-") : loadedModule();
        var build = receipts == null ? Files.createDirectory(module.resolve("build")) : module.resolve("build");
        var game = receipts == null ? Files.createDirectories(build.resolve("run/inspect")) : emptyInspectRoot();
        var evidence = build.resolve("scenario-results");
        var roots = new ScenarioRunner.Roots(module, game, build, evidence, game.resolve("saves"));
        var watch = new ScenarioObserver.Watch();
        var runner = failRelease ? new ScenarioRunner(host, watch, roots,
                () -> { throw new IllegalStateException("owned release unavailable"); }, watch::freeze)
                : new ScenarioRunner(host, watch, roots, header);
        if (receipts != null) ownedTrees.add(evidence.resolve("two-lane-" + runner.inspectSessionId()));
        var queue = new ArrayDeque<Runnable>(); var commands = new ScenarioCommands(queue::add, host);
        set(commands, "runner", runner); set(commands, "ready", true);
        return new Fixture(commands, runner, queue, outcomes, neutral, nativeHost, nativeThread, nativeEffects);
    }

    private Path launch() throws Exception {
        var root = Files.createDirectories(loadedModule().resolve("build/scenario-results"));
        var launch = Files.createDirectory(root.resolve("launch-" + UUID.randomUUID())); ownedTrees.add(launch); return launch;
    }
    private Path emptyInspectRoot() throws Exception {
        var module = loadedModule(); var build = module.resolve("build"); var run = build.resolve("run");
        assertTrue(module.endsWith(Path.of("v2", "fabric"))); assertTrue(Files.isRegularFile(module.resolve("build.gradle")));
        for (var ancestor : List.of(module, build, run)) {
            assertTrue(Files.isDirectory(ancestor, LinkOption.NOFOLLOW_LINKS)); assertEquals(ancestor, ancestor.toRealPath());
        }
        var game = run.resolve("inspect");
        if (!Files.exists(game, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(game); ownedInspectRoot = game;
            var attributes = Files.readAttributes(game, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            ownedInspectKey = attributes.fileKey(); ownedInspectCreation = attributes.creationTime();
        }
        assertTrue(Files.isDirectory(game, LinkOption.NOFOLLOW_LINKS)); assertEquals(game, game.toRealPath());
        try (var files = Files.list(game)) { assertEquals(0, files.count(), "Never adopt a nonempty inspect game root"); }
        return game;
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
