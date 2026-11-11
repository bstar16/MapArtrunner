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

    @Test void commandAuthorityRefusalHasNoOrdinalEvidenceOrTransportEffects() throws Exception {
        var host=new InspectTransport(); var runner=runner(host);
        var session=runner.inspectSessionId();
        assertThrows(AssertionError.class,() -> runner.requireInspectCommandWorld(null));
        assertEquals(0,host.effects); assertEquals(0,runnerField("inspectOrdinal").getInt(runner));
        assertNull(runnerField("inspectOperation").get(runner)); assertFalse(Files.exists(rootsDirectory(runner).evidence()));
        var operation=runner.beginInspectOperation(Command.RUN);
        assertEquals(session,operation.sessionId()); assertEquals(1,operation.ordinal());
    }

    private static ScenarioRunner.Roots rootsDirectory(ScenarioRunner runner) throws Exception {
        return (ScenarioRunner.Roots)runnerField("roots").get(runner);
    }

    @Test void realStoppingHostResultsAreConsumedOnceBeforeWorkerSealsEvidence() throws Exception {
        var nativeThread=ThreadLocal.withInitial(() -> false); var outcomes=new java.util.ArrayList<FinalOutcome>();
        var host=new InspectScenarioHost(() -> 1L,nativeThread::get,() -> false,
                action -> fail("Stopped host must not enqueue"),action -> fail("Stopped server must not enqueue"),outcomes::add);
        var attempts=new int[2]; var watch=new ScenarioObserver.Watch();
        var runner=new ScenarioRunner(host,watch,roots(),() -> attempts[0]++,() -> attempts[1]++);
        var op=runner.beginInspectOperation(Command.RUN); var work=host.beginWork(op);
        var original=new IllegalStateException("original work failure"); var cleanup=host.beginCleanup(work,original);
        runnerField("worldCreated").setBoolean(runner,true);
        nativeThread.set(true);
        try { host.onStopping(null); } finally { nativeThread.remove(); }
        // No installed native binding: actual Runner refuses all release/capture effects, freezes its old Watch.
        assertTrue(field("frozen").getBoolean(watch));
        assertSame(original,assertThrows(IllegalStateException.class,() -> host.inScope(cleanup,
                () -> runner.finishInspectOperation(op,cleanup,original))));
        assertArrayEquals(new int[]{0,0},attempts); assertEquals(CleanupStatus.UNRESOLVED,outcomes.getFirst().cleanup());
        assertEquals(Verdict.FAILURE,outcomes.getFirst().verdict());
        var closed=closedFileHashes(op.evidenceDirectory());
        nativeThread.set(true); try { host.onStopping(null); } finally { nativeThread.remove(); }
        assertEquals(closed,closedFileHashes(op.evidenceDirectory())); assertTrue(runner.lastInspectCompletion().isEmpty());
    }

    @Test void startupReservesOrdinalZeroAndInitializationBindsItsScopeInternally() throws Exception {
        var host=new InspectTransport(); var runner=runner(host);
        invokeRunner(runner,"reserveInspectStartup");
        assertEquals(Kind.STARTUP,host.current.kind()); assertEquals(0,host.current.operation().ordinal());
        assertEquals(Command.STARTUP,host.current.operation().command());
        assertTrue(Files.isRegularFile(host.current.operation().evidenceDirectory().resolve("operation.json")));
        assertThrows(AssertionError.class,() -> runner.initializeInspectWorld(() -> fail("Publication before binding"))); // Native double refuses first client query.
        assertEquals(1,host.effects); assertNull(host.bound);
        assertThrows(AssertionError.class,() -> invokeRunner(runner,"reserveInspectStartup"));
    }

    @Test void startupPublicationFailurePreservesClosedFilesAndNeverCreatesOrdinaryLastOrShutdownLineage() throws Exception {
        for(boolean linked : List.of(false,true)) {
            var host=new InspectTransport(); var runner=runner(host); invokeRunner(runner,"reserveInspectStartup");
            var startup=host.current; var op=startup.operation();
            Files.writeString(op.evidenceDirectory().resolve("completion.json"),"closed owned binding evidence");
            var oldFiles=closedFileHashes(op.evidenceDirectory()); var receipt=directory.resolve("ready-"+linked);
            var original=new IllegalStateException("publication failed "+linked);
            Runnable publish=() -> {
                assertSame(startup,host.requireScope(op,Kind.STARTUP));
                if(linked) assertDoesNotThrow(() -> Files.writeString(receipt,"visible immutable READY"));
                throw original;
            };
            // Synthetic completed-binding envelope exercises production publication/failure ordering only.
            Runnable initialization=() -> invokeRunner(runner,"publishInspectStartup",publish);
            assertSame(original,assertThrows(IllegalStateException.class,() -> invokeRunner(runner,"initializeInspectStartup",initialization)));
            assertEquals(linked,Files.exists(receipt)); if(linked) assertEquals("visible immutable READY",Files.readString(receipt));
            for(var entry:oldFiles.entrySet()) assertEquals(entry.getValue(),closedFileHashes(op.evidenceDirectory()).get(entry.getKey()));
            assertEquals(Verdict.FAILURE,host.outcome.verdict()); assertEquals(CleanupStatus.SETTLED,host.outcome.cleanup());
            assertSame(op,host.outcome.operation()); assertTrue(runner.lastInspectCompletion().isEmpty());
            assertEquals(0,host.shutdownScopes);
            for(var command:List.of(Command.RESET,Command.RUN,Command.CLOSE)) assertThrows(AssertionError.class,() -> runner.beginInspectOperation(command));
            assertThrows(AssertionError.class,runner::close);
            assertThrows(AssertionError.class,() -> runner.failInspectAdmission(op,new Exception("No N1 reclassification")));
            assertThrows(AssertionError.class,() -> invokeRunner(runner,"initializeInspectStartup",initialization));
            assertEquals(1,host.settlements);
        }
    }

    @Test void startupSettlementPrecedesReturnAndLaterErrorCannotReviveItsPermission() throws Exception {
        var host=new InspectTransport(); var runner=runner(host); invokeRunner(runner,"reserveInspectStartup");
        var startup=host.current; var original=new IllegalStateException("after startup settlement");
        Runnable initialization=() -> {
            invokeRunner(runner,"publishInspectStartup",() -> assertSame(startup,host.current));
            assertNull(host.current); throw original;
        };
        assertSame(original,assertThrows(IllegalStateException.class,() -> invokeRunner(runner,"initializeInspectStartup",initialization)));
        assertEquals(0,host.settlements); assertNull(host.outcome); assertEquals(0,host.effects); assertEquals(0,host.shutdownScopes);
        assertTrue(runner.lastInspectCompletion().isEmpty()); assertThrows(AssertionError.class,runner::close);
        assertThrows(AssertionError.class,() -> runner.beginInspectOperation(Command.RUN));
    }

    @Test void prospectiveSaveRejectsForeignNamesParentsAndExistingEntries() throws Exception {
        var roots = roots(); Files.createDirectory(roots.saves());
        var session = UUID.randomUUID();
        var prospective = roots.saves().resolve("mapart-inspect-" + session);
        ScenarioRunner.requireProspectiveInspectSave(roots, session, prospective);
        assertThrows(AssertionError.class, () -> ScenarioRunner.requireProspectiveInspectSave(roots, session, roots.saves().resolve("other")));
        assertThrows(AssertionError.class, () -> ScenarioRunner.requireProspectiveInspectSave(roots, session, roots.game().resolve(prospective.getFileName())));
        Files.writeString(prospective, "existing save entry");
        assertThrows(AssertionError.class, () -> ScenarioRunner.requireProspectiveInspectSave(roots, session, prospective));
        assertEquals("existing save entry", Files.readString(prospective));
    }

    @Test void cleanupEffectsKeepOriginalObservationsAndAttemptFailuresOnlyOnceBeforeSeal() {
        var op = new Operation(UUID.randomUUID(), 1, UUID.randomUUID(), Command.RUN, directory);
        var scope = new Scope(op, Kind.CLEANUP, Long.MAX_VALUE);
        var effects = new ScenarioRunner.InspectEffects();
        var order = new java.util.ArrayList<String>();
        var release = new IllegalStateException("original release failure");
        var result = effects.attempt(scope, () -> { order.add("observe"); return "original status/watch/fault"; },
                () -> { order.add("release"); throw release; }, () -> order.add("freeze"), () -> "aftermath");
        assertSame(release, result.releaseFailure());
        assertEquals("original status/watch/fault", result.before());
        assertSame(result, effects.seal(scope, null));
        assertSame(result, effects.attempt(scope, () -> fail("sealed observation"), () -> fail("retried release"),
                () -> fail("retried freeze"), () -> fail("sealed aftermath")));
        assertEquals(List.of("observe", "release", "freeze"), order);
        assertThrows(AssertionError.class, () -> effects.seal(new Scope(op, Kind.CLEANUP, Long.MAX_VALUE), null));
    }

    @Test void finalOutcomeReportsTypedTimeoutAndCleanupOnlyFailure() throws Exception {
        var host = new InspectTransport(); var runner = runner(host);
        var operation = runner.beginInspectOperation(Command.RUN);
        var work = host.beginWork(operation); var timeout = new ScopeTimeoutException();
        var cleanup = host.beginCleanup(work, timeout);
        var result = host.inScope(cleanup, () -> runner.finishInspectOperation(operation, cleanup, timeout));
        assertSame(timeout, result.failure()); assertSame(operation, host.outcome.operation());
        assertEquals(Verdict.TIMEOUT, host.outcome.verdict()); assertEquals(CleanupStatus.SETTLED, host.outcome.cleanup());
    }

    @Test void cleanupOnlyTimeoutIsFailureAndUnresolvedRatherThanSuccessfulWorkTimeout() throws Exception {
        // Synthetic asserted success, real owner finalization; no world/control acceptance is claimed.
        var host=new InspectTransport(); var watch=new ScenarioObserver.Watch(); var cleanupFailure=new ScopeTimeoutException();
        var runner=new ScenarioRunner(host,watch,roots(),() -> { throw cleanupFailure; },watch::freeze);
        var op=runner.beginInspectOperation(Command.RUN); var work=host.beginWork(op);
        var success=successfulOwnerState(runner,op,checkpoint(),"synthetic-success");
        host.inScope(work,() -> runner.assertResult(success));
        runnerField("worldCreated").setBoolean(runner,true);
        var cleanup=host.beginCleanup(work,null);
        assertSame(cleanupFailure,assertThrows(ScopeTimeoutException.class,() -> host.inScope(cleanup,
                () -> runner.finishInspectOperation(op,cleanup,null))));
        assertEquals(Verdict.FAILURE,host.outcome.verdict()); assertEquals(CleanupStatus.UNRESOLVED,host.outcome.cleanup());
        assertTrue(runner.lastInspectCompletion().isEmpty());
    }

    @Test void completedNativeCloseCannotEraseEarlierUnresolvedOwnedEffects() throws Exception {
        var host=new InspectTransport(); var runner=runner(host); var op=runner.beginInspectOperation(Command.CLOSE);
        var cleanup=host.beginCleanup(host.beginWork(op),null); var failedRelease=new IllegalStateException("unverified close release");
        // Synthetic native-close outcome only; test owner latch retention, never world shutdown acceptance.
        runnerField("worldCreated").setBoolean(runner,true); runnerField("closed").setBoolean(runner,true);
        runnerField("normalClose").setBoolean(runner,true); runnerField("inspectUnresolvedCleanupFailure").set(runner,failedRelease);
        assertSame(failedRelease,assertThrows(IllegalStateException.class,() -> host.inScope(cleanup,
                () -> runner.finishInspectOperation(op,cleanup,null))));
        assertEquals(CleanupStatus.UNRESOLVED,host.outcome.cleanup()); assertEquals(Verdict.FAILURE,host.outcome.verdict());
        assertTrue(runner.lastInspectCompletion().isEmpty()); assertThrows(AssertionError.class,() -> runner.beginInspectOperation(Command.RESET));
    }

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

    @Test void expiredWorkWithRefusedOwnedCleanupRemainsUnresolvedAfterTransportSettlement() throws Exception {
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
        assertSame(timeout, assertThrows(IllegalStateException.class,
                () -> host.inScope(cleanup, () -> runner.finishInspectOperation(operation, cleanup, timeout))));
        assertTrue(timeout.getSuppressed().length > 0);
        assertNull(host.current); // Transport settlement returned normally; Runner still owns unresolved cleanup.
        assertTrue(runner.lastInspectCompletion().isEmpty());
        int effects = host.effects;
        int ordinal = runnerField("inspectOrdinal").getInt(runner);
        for (var command : List.of(Command.RESET, Command.RUN, Command.RERUN))
            assertThrows(AssertionError.class, () -> runner.beginInspectOperation(command));
        assertEquals(ordinal, runnerField("inspectOrdinal").getInt(runner));
        assertEquals(effects, host.effects);
    }

    @Test void releaseFailureKeepsPriorLastAndBlocksSuccessorAndRenewedShutdown() throws Exception {
        unresolvedOwnedCleanup("release");
    }

    @Test void freezeFailureKeepsPriorLastAndBlocksSuccessorAndRenewedShutdown() throws Exception {
        unresolvedOwnedCleanup("freeze");
    }

    private void unresolvedOwnedCleanup(String failingEffect) throws Exception {
        // Doubled owner effects test actual Runner orchestration; they confer no native release/physics credit.
        var host = new InspectTransport();
        var watch = new ScenarioObserver.Watch();
        var active = new Operation[1];
        var expectedCleanup = new Scope[1];
        var attempts = new int[2];
        var releaseFailure = new IllegalStateException("owned release could not be verified");
        var freezeFailure = new IllegalStateException("owned Watch freeze dispatch failed");
        var runner = new ScenarioRunner(host, watch, roots(), () -> {
            assertSame(expectedCleanup[0], host.requireScope(active[0], Kind.CLEANUP));
            attempts[0]++;
            if (failingEffect.equals("release")) throw releaseFailure;
        }, () -> {
            assertSame(expectedCleanup[0], host.requireScope(active[0], Kind.CLEANUP));
            attempts[1]++;
            if (failingEffect.equals("freeze")) throw freezeFailure;
            watch.freeze();
        });
        var prior = settledPrior(runner, host);
        var priorFiles = closedFileHashes(prior.evidenceDirectory());
        var operation = runner.beginInspectOperation(Command.RUN);
        active[0] = operation;
        var work = host.beginWork(operation);
        var original = new IllegalStateException("original scenario failure before cleanup");
        var cleanup = host.beginCleanup(work, original);
        expectedCleanup[0] = cleanup;
        runnerField("worldCreated").setBoolean(runner, true);
        field("frozen").setBoolean(watch, false);
        assertSame(original, assertThrows(IllegalStateException.class,
                () -> host.inScope(cleanup, () -> runner.finishInspectOperation(operation, cleanup, original))));
        assertNull(host.current); // Successful transport settlement cannot prove either owned effect.
        assertEquals(CleanupStatus.UNRESOLVED,host.outcome.cleanup());
        assertEquals(Verdict.FAILURE,host.outcome.verdict());
        assertEquals(2, host.settlements);
        assertArrayEquals(new int[]{1, 1}, attempts);
        assertTrue(List.of(original.getSuppressed()).contains(failingEffect.equals("release") ? releaseFailure : freezeFailure));
        assertSame(original, runnerField("inspectUnresolvedCleanupFailure").get(runner));
        assertSame(operation, runnerField("inspectOperation").get(runner));
        assertEquals(failingEffect.equals("release"), field("frozen").getBoolean(watch));
        var failedFiles = closedFileHashes(operation.evidenceDirectory());
        var manifest = com.google.gson.JsonParser.parseString(Files.readString(operation.evidenceDirectory().resolve("completion.json"))).getAsJsonObject();
        assertEquals("FAILED", manifest.get("cleanupStatus").getAsString());
        assertTrue(manifest.get("failure").getAsString().contains(original.getMessage()));
        int ordinal = runnerField("inspectOrdinal").getInt(runner);
        int effects = host.effects;
        for (var command : List.of(Command.RESET, Command.RUN, Command.RERUN, Command.CLOSE))
            assertThrows(AssertionError.class, () -> runner.beginInspectOperation(command));
        assertThrows(AssertionError.class, () -> runner.run(ScenarioDefinition.load()));
        assertThrows(AssertionError.class, () -> runner.failInspectAdmission(operation, new IllegalStateException("cannot reclassify cleanup as BEGIN failure")));
        assertThrows(AssertionError.class, runner::close);
        assertNull(host.shutdownOperation);
        assertEquals(0, host.shutdownScopes);
        assertEquals(effects, host.effects);
        assertEquals(ordinal, runnerField("inspectOrdinal").getInt(runner));
        assertSame(prior, runner.lastInspectCompletion().orElseThrow());
        assertEquals(priorFiles, closedFileHashes(prior.evidenceDirectory()));
        assertEquals(failedFiles, closedFileHashes(operation.evidenceDirectory()));
    }

    @Test void expiredWorkAndEvidenceOnlyFailureCanSettleAfterSuccessfulOwnedCleanup() throws Exception {
        // These scoped effect doubles execute successfully; real native ownership remains a later gate.
        var host = new InspectTransport();
        var watch = new ScenarioObserver.Watch();
        var active = new Operation[1];
        var attempts = new int[2];
        var runner = new ScenarioRunner(host, watch, roots(), () -> {
            host.requireScope(active[0], Kind.CLEANUP); attempts[0]++;
        }, () -> {
            host.requireScope(active[0], Kind.CLEANUP); attempts[1]++; watch.freeze();
        });
        var prior = settledPrior(runner, host);
        var priorFiles = closedFileHashes(prior.evidenceDirectory());
        var operation = runner.beginInspectOperation(Command.RUN);
        active[0] = operation;
        var work = host.beginWork(operation);
        host.expiredWork = true;
        assertThrows(AssertionError.class, () -> host.inScope(work, () -> runner.run(ScenarioDefinition.load())));
        var timeout = new IllegalStateException("original absolute WORK timeout");
        var cleanup = host.beginCleanup(work, timeout);
        assertNotSame(work, cleanup);
        runnerField("worldCreated").setBoolean(runner, true);
        var completion = host.inScope(cleanup, () -> runner.finishInspectOperation(operation, cleanup, timeout));
        assertSame(timeout, completion.failure());
        assertTrue(timeout.getSuppressed().length > 0); // Native evidence reads were refused by the test transport.
        assertFalse(completion.evidenceComplete());
        assertEquals("RELEASED", completion.cleanupStatus());
        assertTrue(completion.cleanupSettled());
        assertArrayEquals(new int[]{1, 1}, attempts);
        assertTrue(field("frozen").getBoolean(watch));
        assertNull(runnerField("inspectUnresolvedCleanupFailure").get(runner));
        assertSame(completion, runner.lastInspectCompletion().orElseThrow());
        int effects = host.effects;
        assertEquals(operation.ordinal() + 1, runner.beginInspectOperation(Command.RESET).ordinal());
        assertEquals(effects, host.effects);
        assertEquals(priorFiles, closedFileHashes(prior.evidenceDirectory()));
    }

    private static ScenarioRunner.InspectCompletion settledPrior(ScenarioRunner runner, InspectTransport host) {
        var operation = runner.beginInspectOperation(Command.RESET);
        var work = host.beginWork(operation);
        var failure = new IllegalStateException("previous settled scenario failure");
        var cleanup = host.beginCleanup(work, failure);
        return host.inScope(cleanup, () -> runner.finishInspectOperation(operation, cleanup, failure));
    }

    private static java.util.Map<String, String> closedFileHashes(Path directory) throws Exception {
        var hashes = new java.util.TreeMap<String, String>();
        try (var paths = Files.walk(directory)) {
            for (var file : paths.filter(Files::isRegularFile).toList())
                hashes.put(directory.relativize(file).toString(), Files.size(file) + ":" + java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        }
        return hashes;
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
    private static void invokeRunner(ScenarioRunner runner,String name,Runnable action) {
        try {
            var method=ScenarioRunner.class.getDeclaredMethod(name,Runnable.class); method.setAccessible(true); method.invoke(runner,action);
        } catch(java.lang.reflect.InvocationTargetException error) {
            if(error.getCause() instanceof Error fatal) throw fatal;
            throw (RuntimeException)error.getCause();
        } catch(ReflectiveOperationException error) { throw new AssertionError(error); }
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
        int settlements, shutdownScopes;
        FinalOutcome outcome;
        public void bindStoppingOwner(BiConsumer<MinecraftClient, Scope> owner) { }
        public void requireStoppingScope(Scope cleanup) { fail("No native stopping claim"); }
        public void createWorld(BiConsumer<MinecraftClient, Path> guard) { effects++; fail("No native world available"); }
        public Path takeScreenshot(Path directory,String name,Consumer<MinecraftClient> guard) { effects++; return fail("No native world available"); }
        public void closeWorld(Consumer<MinecraftClient> guard) { effects++; fail("No native world available"); }
        public void awaitNeutralChat() { effects++; fail("No native world available"); }
        public Scope beginStartup(Operation op) { return current = new Scope(op, Kind.STARTUP, Long.MAX_VALUE); }
        public void settleStartup(Scope scope) { assertSame(current, scope); current = null; }
        public Scope beginWork(Operation op) { assertFalse(cancelled); return current = new Scope(op, Kind.WORK, Long.MAX_VALUE); }
        public Scope beginCleanup(Scope work, Throwable primary) {
            assertSame(current, work); return current = new Scope(work.operation(), Kind.CLEANUP, Long.MAX_VALUE);
        }
        public void settleCleanup(Scope scope, FinalOutcome outcome) {
            assertSame(current, scope);
            assertSame(scope.operation(), outcome.operation()); this.outcome = outcome;
            if (settlementFailure != null) throw (RuntimeException) settlementFailure;
            settlements++;
            current = null;
        }
        public void cancelSession(Throwable primary) { cancelled = true; }
        public Scope beginShutdownCleanup(Operation failed, Throwable primary) {
            shutdownScopes++;
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
        public void runOnClient(Consumer<MinecraftClient> action) { requireScope(current.operation(),current.kind()); effects++; fail("No native world available"); }
        public <V> V computeOnClient(Function<MinecraftClient, V> action) { requireScope(current.operation(),current.kind()); effects++; return fail("No native world available"); }
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
