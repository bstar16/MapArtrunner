package com.mapartrunner.fabric.scenario;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.run.BuildEngine;
import com.mapartrunner.core.run.RunLifecycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.nio.file.*;
import java.util.function.*;

import static com.mapartrunner.fabric.scenario.ScenarioHost.*;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioInspectLifecycleTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }
    @TempDir Path directory;

    @Test void admittedFailuresHaveDistinctClosedEvidenceWithoutRunUuid() throws Exception {
        var host = new InspectTransport();
        var runner = runner(host);
        var first = runner.beginInspectOperation(Command.RESET);
        var work = host.beginWork(first);
        var failure = new AssertionError("reset failed before UUID");
        var cleanup = host.beginCleanup(work, failure);
        var a = host.inScope(cleanup, () -> runner.finishInspectOperation(first, cleanup, failure));
        assertSame(failure, a.failure());
        assertTrue(a.runId().isEmpty());
        assertTrue(a.evidenceComplete());
        assertTrue(a.cleanupSettled());
        byte[] closed = Files.readAllBytes(a.summaryPath());
        var second = runner.beginInspectOperation(Command.RUN);
        assertEquals(first.ordinal() + 1, second.ordinal());
        assertNotEquals(first.operationId(), second.operationId());
        assertNotEquals(first.evidenceDirectory(), second.evidenceDirectory());
        var secondWork = host.beginWork(second);
        var secondCleanup = host.beginCleanup(secondWork, failure);
        var b = host.inScope(secondCleanup, () -> runner.finishInspectOperation(second, secondCleanup, failure));
        assertArrayEquals(closed, Files.readAllBytes(a.summaryPath()));
        assertEquals(second.evidenceDirectory(), b.evidenceDirectory());
        assertSame(b, runner.lastInspectCompletion().orElseThrow());
        assertThrows(AssertionError.class, () -> runner.finishInspectOperation(first, cleanup, failure));
        assertSame(b, runner.lastInspectCompletion().orElseThrow());
    }

    @Test void beginFailureClosesAdmissionWithoutSuccessorOrLastReplacement() throws Exception {
        for (boolean finalLinkVisible : List.of(false, true)) {
            var host = new InspectTransport();
            var runner = runner(host);
            var earlier = runner.beginInspectOperation(Command.RESET);
            var earlierWork = host.beginWork(earlier);
            var earlierFailure = new AssertionError("prior admitted reset failure");
            var earlierCleanup = host.beginCleanup(earlierWork, earlierFailure);
            var last = host.inScope(earlierCleanup, () -> runner.finishInspectOperation(earlier, earlierCleanup, earlierFailure));
            byte[] previous = Files.readAllBytes(last.summaryPath());
            var operation = runner.beginInspectOperation(Command.RUN);
            if (finalLinkVisible) Files.writeString(operation.evidenceDirectory().resolve("visible-BEGIN"), "ambiguous publication");
            var failure = new IllegalStateException("BEGIN publication failed");
            runner.failInspectAdmission(operation, failure);
            assertSame(failure, runner.inspectAdmissionFailure().orElseThrow());
            assertSame(operation, runner.failedInspectAdmission().orElseThrow());
            assertTrue(host.cancelled);
            for (var command : List.of(Command.RESET, Command.RUN, Command.RERUN))
                assertThrows(AssertionError.class, () -> runner.beginInspectOperation(command));
            assertSame(last, runner.lastInspectCompletion().orElseThrow());
            assertArrayEquals(previous, Files.readAllBytes(last.summaryPath()));
            assertEquals(0, host.effects);
            runner.close(); // Fresh shutdown scope, no successor ordinal and no game effects without an owned world.
            assertSame(operation, host.shutdownOperation);
        }
    }

    @Test void allocationFailureLatchesReservedIdentityAndCancelsHost() throws Exception {
        var host = new InspectTransport();
        var roots = roots();
        Files.writeString(roots.evidence(), "blocks evidence allocation");
        var runner = new ScenarioRunner(host, new ScenarioObserver.Watch(), roots);
        assertThrows(RuntimeException.class, () -> runner.beginInspectOperation(Command.RESET));
        var reserved = runner.failedInspectAdmission().orElseThrow();
        assertEquals(1, reserved.ordinal());
        assertTrue(host.cancelled);
        assertThrows(AssertionError.class, () -> runner.beginInspectOperation(Command.RUN));
        assertSame(reserved, runner.failedInspectAdmission().orElseThrow());
        assertEquals(0, host.effects);
    }

    @Test void headerCollisionLatchesOriginalCauseAndNeverRepairsNamespace() throws Exception {
        var host = new InspectTransport();
        var runner = new ScenarioRunner(host, new ScenarioObserver.Watch(), roots(), operation -> {
            assertDoesNotThrow(() -> Files.writeString(operation.evidenceDirectory().resolve("operation.json"), "existing header"));
        });
        var failure = assertThrows(java.io.UncheckedIOException.class, () -> runner.beginInspectOperation(Command.RERUN));
        assertInstanceOf(FileAlreadyExistsException.class, failure.getCause());
        var failed = runner.failedInspectAdmission().orElseThrow();
        assertSame(failure, runner.inspectAdmissionFailure().orElseThrow());
        assertEquals("existing header", Files.readString(failed.evidenceDirectory().resolve("operation.json")));
        assertTrue(host.cancelled);
        assertThrows(AssertionError.class, () -> runner.beginInspectOperation(Command.RESET));
        runner.close();
        assertSame(failed, host.shutdownOperation);
        assertEquals("existing header", Files.readString(failed.evidenceDirectory().resolve("operation.json")));
        assertEquals(0, host.effects);
    }

    @Test void finalizationCannotInventRunSuccessAndCaptureRunsOnlyOnce() throws Exception {
        var host = new InspectTransport();
        var runner = runner(host);
        var operation = runner.beginInspectOperation(Command.RUN);
        var work = host.beginWork(operation);
        var original = new IllegalStateException("before native start");
        host.inScope(work, () -> runner.captureFailure(original));
        byte[] first = Files.readAllBytes(operation.evidenceDirectory().resolve("failure.txt"));
        var cleanup = host.beginCleanup(work, original);
        var completion = host.inScope(cleanup, () -> runner.finishInspectOperation(operation, cleanup, null));
        assertSame(original, completion.failure());
        assertArrayEquals(first, Files.readAllBytes(operation.evidenceDirectory().resolve("failure.txt")));
        assertEquals(0, original.getSuppressed().length);

        var next = runner.beginInspectOperation(Command.RUN);
        var nextWork = host.beginWork(next);
        var nextCleanup = host.beginCleanup(nextWork, null);
        var failed = host.inScope(nextCleanup, () -> runner.finishInspectOperation(next, nextCleanup, null));
        assertEquals("FAIL", failed.verdict());
        assertTrue(failed.failure().getMessage().contains("independently asserted success"));
        assertTrue(failed.runId().isEmpty());
    }

    @Test void exactBoundScopesProtectWorkAndFinalizationAndSettlementGatesAdmission() throws Exception {
        var host = new InspectTransport();
        var runner = runner(host);
        var operation = runner.beginInspectOperation(Command.RUN);
        assertThrows(AssertionError.class, () -> runner.beginInspectOperation(Command.RESET));
        assertThrows(AssertionError.class, () -> runner.run(ScenarioDefinition.load()));
        var work = host.beginWork(operation);
        var cleanup = host.beginCleanup(work, null);
        var equal = new Scope(operation, Kind.CLEANUP, cleanup.deadlineNanos());
        assertThrows(AssertionError.class, () -> host.inScope(cleanup,
                () -> runner.finishInspectOperation(operation, equal, null)));
        host.settlementFailure = new IllegalStateException("END/settlement failure");
        var timeout = new IllegalStateException("original WORK timeout");
        assertSame(timeout, assertThrows(IllegalStateException.class,
                () -> host.inScope(cleanup, () -> runner.finishInspectOperation(operation, cleanup, timeout))));
        assertArrayEquals(new Throwable[]{host.settlementFailure}, timeout.getSuppressed());
        assertThrows(AssertionError.class, () -> runner.beginInspectOperation(Command.RESET));
        assertTrue(runner.lastInspectCompletion().isEmpty());
        assertEquals(0, host.effects);
    }

    @Test void resultIdentityUsesActualOperationDirectoryAndNeverRelabelsPriorResult() throws Exception {
        var host = new InspectTransport();
        var runner = runner(host);
        var operation = runner.beginInspectOperation(Command.RUN);
        var definition = ScenarioDefinition.load();
        var id = java.util.UUID.randomUUID();
        var result = new ScenarioRunner.ScenarioResult(id, new com.mapartrunner.core.run.BuildEngine().snapshot(),
                definition.definitionHash(), "independent-start", List.of(), operation.evidenceDirectory());
        ScenarioRunner.requireResultIdentity(result, result, java.util.Set.of(id), operation.evidenceDirectory(), definition);
        assertThrows(AssertionError.class, () -> ScenarioRunner.requireResultIdentity(result, result,
                java.util.Set.of(id), operation.evidenceDirectory().getParent(), definition));
        assertEquals(operation.evidenceDirectory(), result.artifacts());
    }

    @Test void archiveFailurePrecedesRestoreAndKeepsOriginalClosedBytes() throws Exception {
        var original = directory.resolve("original-closed.txt");
        Files.writeString(original, "closed original evidence");
        byte[] before = Files.readAllBytes(original);
        var failure = new IllegalStateException("archive failed before COMMITTED");
        var restores = new int[1];
        assertSame(failure, assertThrows(IllegalStateException.class, () -> ScenarioRunner.archiveThenRestore(() -> {
            assertArrayEquals(before, assertDoesNotThrow(() -> Files.readAllBytes(original)));
            throw failure;
        }, () -> restores[0]++)));
        assertEquals(0, restores[0]);
        assertArrayEquals(before, Files.readAllBytes(original));
    }

    @Test void retainedOperationHashMismatchBlocksTheRealArchiveValidationBeforeRestore() throws Exception {
        var host = new InspectTransport();
        var runner = runner(host);
        var operation = runner.beginInspectOperation(Command.RESET);
        var work = host.beginWork(operation);
        var failure = new IllegalStateException("owned temporary evidence");
        var cleanup = host.beginCleanup(work, failure);
        host.inScope(cleanup, () -> runner.finishInspectOperation(operation, cleanup, failure));
        invokeRunner(runner, "verifyInspectPriorEvidence");
        // Only this test's fresh TempDir evidence is altered; retained/native evidence is never touched.
        Files.writeString(operation.evidenceDirectory().resolve("failure.txt"), "changed closed fixture file");
        var restores = new int[1];
        assertThrows(AssertionError.class, () -> ScenarioRunner.archiveThenRestore(
                () -> invokeRunner(runner, "verifyInspectPriorEvidence"), () -> restores[0]++));
        assertEquals(0, restores[0]);
    }

    @Test void automaticRepeatLimitAndDirtyPreludeStayBoundedWhileInspectAttemptsContinue() {
        assertTrue(ScenarioRunner.startAllowed(false, true, 0, 2, true));
        assertTrue(ScenarioRunner.startAllowed(false, true, 1, 2, false));
        assertFalse(ScenarioRunner.startAllowed(false, true, 2, 2, false));
        assertFalse(ScenarioRunner.startAllowed(false, true, 1, 1, false));
        assertTrue(ScenarioRunner.startAllowed(true, true, 20, 2, false));
        assertFalse(ScenarioRunner.startAllowed(true, false, 0, 2, false));
    }

    @Test void successfulComparisonPairSurvivesAnInterveningFailedAttempt() throws Exception {
        // Synthetic owner state exercises assertion/accounting only, never native fixture success.
        var host = new InspectTransport();
        var runner = runner(host);
        var first = runner.beginInspectOperation(Command.RUN);
        var firstWork = host.beginWork(first);
        var firstStart = checkpoint();
        var a = successfulOwnerState(runner, first, firstStart, "start-A");
        host.inScope(firstWork, () -> runner.assertResult(a));
        var firstCleanup = host.beginCleanup(firstWork, null);
        var completion = host.inScope(firstCleanup, () -> runner.finishInspectOperation(first, firstCleanup, null));
        assertEquals("PASS", completion.verdict());
        var original = Files.readAllBytes(completion.summaryPath());

        var failed = runner.beginInspectOperation(Command.RERUN);
        var failedWork = host.beginWork(failed);
        runnerField("currentCountedStart").set(runner, checkpoint());
        var failure = new IllegalStateException("attempt B failed before assertions");
        var failedCleanup = host.beginCleanup(failedWork, failure);
        host.inScope(failedCleanup, () -> runner.finishInspectOperation(failed, failedCleanup, failure));
        assertSame(firstStart, runnerField("priorCountedStart").get(runner));
        assertSame(a, runnerField("priorCountedSuccess").get(runner));

        var next = runner.beginInspectOperation(Command.RUN);
        var nextWork = host.beginWork(next);
        var nextStart = checkpoint();
        var c = successfulOwnerState(runner, next, nextStart, "start-C");
        assertSame(a, runnerField("priorCountedSuccess").get(runner));
        host.inScope(nextWork, () -> runner.assertResult(c));
        assertSame(c, runnerField("priorCountedSuccess").get(runner));
        assertSame(nextStart, runnerField("priorCountedStart").get(runner));
        assertArrayEquals(original, Files.readAllBytes(completion.summaryPath()));
        assertEquals(0, host.effects);
    }

    @Test void expiredWorkCanOnlyFinalizeWithFreshCleanupAndRetainsTimeoutUnderCaptureFailure() throws Exception {
        var host = new InspectTransport();
        var runner = runner(host);
        var operation = runner.beginInspectOperation(Command.RUN);
        var work = host.beginWork(operation);
        host.expiredWork = true;
        assertThrows(AssertionError.class, () -> host.inScope(work, () -> runner.run(ScenarioDefinition.load())));
        assertEquals(0, host.effects);
        var timeout = new IllegalStateException("original absolute WORK timeout");
        var cleanup = host.beginCleanup(work, timeout);
        assertNotSame(work, cleanup);
        assertThrows(AssertionError.class, () -> host.beginCleanup(work, timeout));
        // The doubled native transport refuses every capture/release attempt, exercising primary retention.
        runnerField("worldCreated").setBoolean(runner, true);
        var result = host.inScope(cleanup, () -> runner.finishInspectOperation(operation, cleanup, timeout));
        assertSame(timeout, result.failure());
        assertTrue(timeout.getSuppressed().length > 0);
        assertFalse(result.evidenceComplete());
        assertEquals("FAILED", result.cleanupStatus());
        assertTrue(result.cleanupSettled());
        assertEquals("FAIL", result.verdict());
    }

    private static Object checkpoint() throws Exception {
        var constructor = runnerField("currentCountedStart").getType().getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(null, null, null);
    }

    private static ScenarioRunner.ScenarioResult successfulOwnerState(ScenarioRunner runner, Operation operation,
                                                                      Object checkpoint, String hash) throws Exception {
        var id = UUID.randomUUID();
        var context = new RunLifecycle.Context(id, "unit-connection", "unit-world", "minecraft:overworld", "unit-plan",
                new BuildPlan.GridPos(0, 65, 0), "unit-settings");
        var lifecycle = new RunLifecycle.Snapshot(RunLifecycle.Phase.TERMINAL, Optional.of(context), Optional.empty(),
                Optional.of(RunLifecycle.Outcome.SUCCESS));
        var snapshot = new BuildEngine.Snapshot(lifecycle, 0, 0, Optional.empty(), List.of(), Set.of(), Optional.empty(), List.of());
        var definition = ScenarioDefinition.load();
        var result = new ScenarioRunner.ScenarioResult(id, snapshot, definition.definitionHash(), hash, List.of(), operation.evidenceDirectory());
        @SuppressWarnings("unchecked") var runs = (Set<UUID>) runnerField("runs").get(runner);
        runs.add(id);
        runnerField("inspectRun").set(runner, id);
        runnerField("admitted").set(runner, definition);
        runnerField("previous").set(runner, result);
        runnerField("currentCountedStart").set(runner, checkpoint);
        return result;
    }

    private static Field runnerField(String name) throws Exception {
        var field = ScenarioRunner.class.getDeclaredField(name); field.setAccessible(true); return field;
    }

    private static void invokeRunner(ScenarioRunner runner, String name) {
        try {
            var method = ScenarioRunner.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(runner);
        } catch (java.lang.reflect.InvocationTargetException error) {
            if (error.getCause() instanceof Error fatal) throw fatal;
            throw (RuntimeException) error.getCause();
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private ScenarioRunner runner(InspectTransport host) throws Exception {
        return new ScenarioRunner(host, new ScenarioObserver.Watch(), roots());
    }

    private ScenarioRunner.Roots roots() throws Exception {
        var module = Files.createTempDirectory(directory, "module-");
        var build = Files.createDirectory(module.resolve("build"));
        var game = Files.createDirectories(build.resolve("run/inspect"));
        return new ScenarioRunner.Roots(module, game, build, build.resolve("scenario-results"), game.resolve("saves"));
    }

    /** Only dispatch permission is doubled. No world/physics/native claim comes from this transport. */
    private static final class InspectTransport implements Inspect {
        Scope bound, current;
        boolean cancelled;
        boolean expiredWork;
        int effects;
        Throwable settlementFailure;
        Operation shutdownOperation;
        public Scope beginStartup(Operation op) { return current = new Scope(op, Kind.STARTUP, Long.MAX_VALUE); }
        public void settleStartup(Scope scope) { assertSame(current, scope); current = null; }
        public Scope beginWork(Operation op) { assertFalse(cancelled); return current = new Scope(op, Kind.WORK, Long.MAX_VALUE); }
        public Scope beginCleanup(Scope work, Throwable primary) {
            assertSame(current, work); return current = new Scope(work.operation(), Kind.CLEANUP, Long.MAX_VALUE);
        }
        public void settleCleanup(Scope scope) {
            assertSame(current, scope);
            if (settlementFailure != null) throw (RuntimeException) settlementFailure;
            current = null;
        }
        public void cancelSession(Throwable primary) { cancelled = true; current = null; }
        public Scope beginShutdownCleanup(Operation failed, Throwable primary) {
            shutdownOperation = failed;
            return current = new Scope(failed, Kind.CLEANUP, Long.MAX_VALUE);
        }
        public Scope requireScope(Operation expected, Kind kind) {
            assertNotNull(bound); assertSame(current, bound); assertSame(expected, bound.operation());
            assertFalse(kind == Kind.WORK && expiredWork, "WORK expired");
            assertEquals(kind, bound.kind()); return bound;
        }
        public void inScope(Scope scope, Runnable action) { inScope(scope, () -> { action.run(); return null; }); }
        public <V> V inScope(Scope scope, Supplier<V> action) {
            var old = bound; bound = scope; try { return action.get(); } finally { bound = old; }
        }
        public void runOnClient(Consumer<MinecraftClient> action) { effects++; fail("No native world available"); }
        public <V> V computeOnClient(Function<MinecraftClient, V> action) { effects++; return fail("No native world available"); }
        public void runOnServer(Consumer<MinecraftServer> action) { effects++; fail("No native world available"); }
        public <V> V computeOnServer(Function<MinecraftServer, V> action) { effects++; return fail("No native world available"); }
        public void waitTick() { effects++; fail("No native world available"); }
        public void createWorld() { effects++; fail("No native world available"); }
        public Path saveDirectory() { effects++; return fail("No native world available"); }
        public Path takeScreenshot(Path path, String name) { effects++; return fail("No native world available"); }
        public void closeWorld() { effects++; fail("No native world available"); }
    }

    @Test void frozenWatchPreservesSamplesFailureAndBindingPastIdleBudget() throws Exception {
        var watch = new ScenarioObserver.Watch();
        var plan = new BuildPlan("retained", new BuildPlan.GridSize(1, 1, 1), List.of());
        var original = new AssertionError("original observer failure");
        field("plan").set(watch, plan);
        field("failure").set(watch, original);
        var sample = new ScenarioObserver.Sample(1, "END", 12, true, null, List.of(), false, null, List.of());
        @SuppressWarnings("unchecked") var samples = (List<ScenarioObserver.Sample>) field("samples").get(watch);
        samples.add(sample);
        watch.freeze();
        var retained = watch.retained();
        for (int i = 0; i < 12002; i++) watch.record(null, i % 2 == 0 ? "START" : "END");
        assertEquals(List.of(sample), watch.retained().samples());
        assertEquals(retained, watch.retained());
        assertSame(original, watch.retained().failure());
        assertSame(plan, field("plan").get(watch));
        assertThrows(UnsupportedOperationException.class, () -> retained.samples().clear());
        watch.clear(); // Only the explicit archive-before-clear/arm route restarts recording.
        assertTrue(watch.retained().samples().isEmpty());
        assertNull(watch.retained().failure());
    }

    @Test void frozenHealthyWatchDoesNotResumeRecordingDuringIdleCallbacks() throws Exception {
        var watch = new ScenarioObserver.Watch();
        field("plan").set(watch, new BuildPlan("healthy-retained", new BuildPlan.GridSize(1, 1, 1), List.of()));
        watch.freeze();
        var before = watch.retained();
        for (int i = 0; i < 12002; i++) watch.record(null, i % 2 == 0 ? "START" : "END");
        assertEquals(before, watch.retained());
        assertNull(watch.retained().failure());
        assertEquals(0L, field("tick").getLong(watch));
    }

    private static Field field(String name) throws Exception {
        var field = ScenarioObserver.Watch.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
