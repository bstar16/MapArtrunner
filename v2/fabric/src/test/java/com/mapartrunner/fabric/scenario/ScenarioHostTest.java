package com.mapartrunner.fabric.scenario;

import com.mapartrunner.fabric.scenario.threading.PacketHandlerAwait;
import com.mapartrunner.fabric.scenario.threading.RegistrationAcknowledgement;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldBuilder;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.fabric.impl.client.gametest.screenshot.TestScreenshotOptionsImpl;
import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.commons.lang3.function.FailableFunction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.io.TempDir;

import static com.mapartrunner.fabric.scenario.ScenarioHost.*;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioHostTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }
    @TempDir Path temporary;

    @Test void startupReadinessUsesCurrentClientStateAndActsExactlyOnce() throws Exception {
        try (var h = new DispatchHarness()) {
            var startup = h.host.beginStartup(startupOperation());
            var ready = new AtomicBoolean(); var effects = new AtomicInteger();
            var loading = h.worker.submit(() -> h.host.inScope(startup,
                    () -> h.host.runWhenClientReady(c -> ready.get(), c -> effects.incrementAndGet())));
            var first = h.client.poll(2, TimeUnit.SECONDS); assertNotNull(first); h.nativeCall(first);
            assertFalse(loading.get(2, TimeUnit.SECONDS)); assertEquals(0, effects.get());
            var finishing = h.worker.submit(() -> h.host.inScope(startup,
                    () -> h.host.runWhenClientReady(c -> ready.get(), c -> effects.incrementAndGet())));
            var current = h.client.poll(2, TimeUnit.SECONDS); assertNotNull(current);
            ready.set(true); h.nativeCall(current);
            assertTrue(finishing.get(2, TimeUnit.SECONDS)); assertEquals(1, effects.get());
            assertTrue(h.client.isEmpty()); assertEquals(600_000_000_001L, startup.deadlineNanos());
        }
    }

    @Test void startupReadinessAndActionPrecedeQueuedReplacementWithoutRedispatch() throws Exception {
        try (var h = new DispatchHarness()) {
            var startup = h.host.beginStartup(startupOperation());
            var current = new AtomicReference<>("ready"); var calls = new CopyOnWriteArrayList<String>();
            Runnable completed = () -> { };
            var pending = h.worker.submit(() -> {
                try {
                    return h.host.inScope(startup, () -> h.host.runWhenClientReady(c -> {
                        calls.add("ready");
                        h.client.add(() -> { current.set("replacement"); calls.add("replacement"); });
                        return current.get().equals("ready");
                    }, c -> {
                        assertEquals("ready", current.get(), "Creation action lost its freshly observed readiness");
                        calls.add("action");
                    }));
                } finally { h.client.add(completed); }
            });
            Runnable queued;
            while ((queued = h.client.poll(2, TimeUnit.SECONDS)) != completed) {
                assertNotNull(queued); h.nativeCall(queued);
            }
            assertTrue(pending.get(2, TimeUnit.SECONDS));
            assertEquals(List.of("ready", "action", "replacement"), calls);
            assertTrue(h.client.isEmpty());
        }
    }

    @Test void queuedStartupCancellationAndExpiryHaveNoReadinessOrActionEffects() throws Exception {
        for (boolean expired : List.of(false, true)) try (var h = new DispatchHarness()) {
            var startup = h.host.beginStartup(startupOperation());
            var observations = new AtomicInteger(); var effects = new AtomicInteger();
            var original = new IllegalStateException("cancelled startup sentinel");
            var pending = h.worker.submit(() -> h.host.inScope(startup,
                    () -> h.host.runWhenClientReady(c -> { observations.incrementAndGet(); return true; }, c -> effects.incrementAndGet())));
            var queued = h.client.poll(2, TimeUnit.SECONDS); assertNotNull(queued);
            if (expired) h.now.set(startup.deadlineNanos()); else h.host.cancelSession(original);
            h.nativeCall(queued);
            if (expired) assertInstanceOf(ScopeTimeoutException.class, failure(pending)); else assertSame(original, failure(pending));
            assertEquals(0, observations.get()); assertEquals(0, effects.get()); assertTrue(h.client.isEmpty());
            assertEquals(600_000_000_001L, startup.deadlineNanos());
        }
    }

    @Test void startupLoadingWaitRetainsItsOriginalAbsoluteDeadline() throws Exception {
        try (var h = new DispatchHarness()) {
            var startup = h.host.beginStartup(startupOperation()); var effects = new AtomicInteger(); var attempts = new AtomicInteger();
            var pending = h.worker.submit(() -> h.host.inScope(startup, () -> {
                while (!h.host.runWhenClientReady(c -> false, c -> effects.incrementAndGet())) {
                    attempts.incrementAndGet(); h.host.waitTick();
                }
            }));
            var loading = h.client.poll(2, TimeUnit.SECONDS); assertNotNull(loading); h.nativeCall(loading);
            await(() -> attempts.get() == 1 && h.host.waitingCount() == 1);
            h.now.set(600_000_000_000L); h.host.onTick();
            var last = h.client.poll(2, TimeUnit.SECONDS); assertNotNull(last);
            h.now.set(600_000_000_001L); h.nativeCall(last);
            assertInstanceOf(ScopeTimeoutException.class, failure(pending)); assertEquals(0, effects.get());
            assertThrows(ScopeTimeoutException.class, () -> h.host.inScope(startup, () -> fail("Expired startup renewed")));
            assertEquals(600_000_000_001L, startup.deadlineNanos()); assertTrue(h.client.isEmpty());
        }
    }

    @Test void startupReadinessActionPropagatesOriginalErrorsWithoutRedispatch() throws Exception {
        for (Throwable original : List.of(new IllegalStateException("startup action"), new AssertionError("startup action"))) {
            try (var h = new DispatchHarness()) {
                var startup = h.host.beginStartup(startupOperation());
                var pending = h.worker.submit(() -> h.host.inScope(startup,
                        () -> h.host.runWhenClientReady(c -> true, c -> rethrow(original))));
                var action = h.client.poll(2, TimeUnit.SECONDS); assertNotNull(action); h.nativeCall(action);
                assertSame(original, failure(pending)); assertTrue(h.client.isEmpty());
            }
        }
    }

    @Test void directCancellationReturnsBeforeRealBeginPublicationReleasesLockAndRetainsFirstCause() throws Exception {
        var source = Path.of(ScenarioHost.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        var module = source.getParent().getParent().getParent().getParent();
        var evidence = Files.createDirectories(module.resolve("build/scenario-results"));
        var launchId = UUID.randomUUID(); var session = UUID.randomUUID(); var id = UUID.randomUUID();
        var launch = Files.createDirectory(evidence.resolve("launch-" + launchId));
        var owned = Files.createDirectory(evidence.resolve("two-lane-" + session));
        var directory = Files.createDirectories(owned.resolve("inspect-operations/op-1-" + id));
        var operation = new Operation(session, 1, id, Command.RUN, directory);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var returned = new CountDownLatch(1);
        var nativeThread = ThreadLocal.withInitial(() -> false); var effects = new AtomicInteger();
        var callbackFailure = new AtomicReference<Throwable>(); var original = new IllegalStateException("first cancellation");
        var receipts = new ScenarioLaunchReceipt(launchId.toString(), launch.toString(), path -> {
            entered.countDown(); assertDoesNotThrow(() -> assertTrue(release.await(10, TimeUnit.SECONDS)));
        }, false);
        var host = new InspectScenarioHost(() -> 1L, nativeThread::get, () -> false,
                action -> effects.incrementAndGet(), action -> effects.incrementAndGet(), null, receipts, java.time.Instant::now);
        var worker = Executors.newSingleThreadExecutor();
        var callback = new Thread(() -> {
            nativeThread.set(true);
            try { host.cancelSession(original); host.cancelSession(new IllegalStateException("later cancellation")); }
            catch (Throwable failure) { callbackFailure.set(failure); }
            finally { nativeThread.remove(); returned.countDown(); }
        }, "inspect-direct-native-cancel-test");
        try {
            var admission = worker.submit(() -> host.beginWork(operation));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS)); callback.start();
                assertTrue(returned.await(2, TimeUnit.SECONDS), "Direct cancellation blocked on phase filesystem IO");
                assertEquals(1, release.getCount()); assertNull(callbackFailure.get());
            } finally { release.countDown(); callback.join(5000); assertFalse(callback.isAlive()); }
            var work = admission.get(2, TimeUnit.SECONDS);
            assertEquals(600_000_000_001L, work.deadlineNanos());
            assertSame(original, assertThrows(IllegalStateException.class, () -> host.inScope(work,
                    () -> host.runOnClient(client -> effects.incrementAndGet()))));
            assertEquals(0, effects.get());
            assertThrows(IllegalStateException.class, () -> host.beginWork(new Operation(session, 2, UUID.randomUUID(), Command.RUN, directory)));
        } finally {
            release.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            for (var root : List.of(owned, launch)) {
                assertEquals(evidence, root.getParent()); assertEquals(root, root.toRealPath());
                try (var paths = Files.walk(root)) {
                    for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }

    @Test void inspectQueuedExpiredAndCancelledActionsHaveZeroEffects() throws Exception {
        try (var h = new DispatchHarness()) {
            var op = operation(1);
            var work = h.host.beginWork(op);
            var effects = new AtomicInteger();
            var result = h.worker.submit(() -> h.host.inScope(work, () -> h.host.runOnClient(c -> effects.incrementAndGet())));
            var queued = h.client.poll(2, TimeUnit.SECONDS); assertNotNull(queued);
            h.now.set(work.deadlineNanos());
            h.nativeCall(queued);
            assertInstanceOf(ScopeTimeoutException.class, failure(result));
            assertEquals(0, effects.get());
            var cleanup = h.host.beginCleanup(work, null);
            assertNotSame(work, cleanup);
            assertEquals(work.deadlineNanos() + TimeUnit.SECONDS.toNanos(10), cleanup.deadlineNanos());
            assertThrows(IllegalStateException.class, () -> h.host.beginCleanup(work, null));
            h.host.inScope(cleanup, () -> assertSame(cleanup, h.host.requireScope(op, Kind.CLEANUP)));
            h.host.settleCleanup(cleanup, new FinalOutcome(op, Verdict.TIMEOUT, CleanupStatus.SETTLED));
            var next = h.host.beginWork(operation(2));
            var pending = h.worker.submit(() -> h.host.inScope(next, () -> h.host.runOnClient(c -> effects.incrementAndGet())));
            var late = h.client.poll(2, TimeUnit.SECONDS); assertNotNull(late);
            var original = new IllegalStateException("cancelled original");
            h.host.cancelSession(original);
            h.nativeCall(late);
            assertSame(original, failure(pending));
            assertEquals(0, effects.get());
            assertThrows(IllegalStateException.class, () -> h.host.beginWork(operation(3)));
        }
    }

    @Test void inspectRequiresExactBoundHandleAndRejectsBlockingOnNativeThreads() throws Exception {
        try (var h = new DispatchHarness()) {
            var op = operation(1);
            var work = h.host.beginWork(op);
            assertThrows(IllegalStateException.class, () -> h.host.runOnClient(c -> fail()));
            var equal = new Scope(op, Kind.WORK, work.deadlineNanos());
            assertThrows(IllegalStateException.class, () -> h.host.inScope(equal, () -> fail()));
            h.nativeCall(() -> {
                assertThrows(IllegalStateException.class, () -> h.host.inScope(work, () -> fail("native scope binding")));
                assertThrows(IllegalStateException.class, () -> h.host.runOnClient(c -> fail()));
                assertThrows(IllegalStateException.class, () -> h.host.runOnServer(s -> fail()));
                assertThrows(IllegalStateException.class, h.host::waitTick);
            });
            h.serverThread.set(true);
            try {
                assertThrows(IllegalStateException.class, () -> h.host.inScope(work, () -> fail("server scope binding")));
                assertThrows(IllegalStateException.class, h.host::waitTick);
            }
            finally { h.serverThread.remove(); }
            assertTrue(h.client.isEmpty());
        }
    }

    @Test void inspectTickRequiresAnActualLaterNotificationAndStoppedClientWakesWaiter() throws Exception {
        try (var h = new DispatchHarness()) {
            var work = h.host.beginWork(operation(1));
            h.host.onTick(); // An earlier tick cannot satisfy the next wait.
            var waiting = h.worker.submit(() -> h.host.inScope(work, h.host::waitTick));
            await(() -> h.host.waitingCount() == 1);
            assertFalse(waiting.isDone());
            h.host.onTick();
            waiting.get(2, TimeUnit.SECONDS);
            var next = h.worker.submit(() -> h.host.inScope(work, h.host::waitTick));
            await(() -> h.host.waitingCount() == 1);
            h.nativeCall(() -> h.host.onStopping(null));
            assertInstanceOf(IllegalStateException.class, failure(next));
        }
    }

    @Test void physicsWaitChargesAnOutstandingClientStallAndKeepsTheOriginalDeadline() throws Exception {
        try(var h=new DispatchHarness()) {
            var fixture=new ScenarioClientTransitionTest.QueueFixture(); fixture.engine.pause();
            var held=ScenarioClientTransitionTest.clocks(fixture.sample(),100,100,100,100,100,100);
            var work=h.host.beginWork(operation(1)); var allowance=new ScenarioRunner.PhysicsAllowance(40);
            var rows=new CopyOnWriteArrayList<ScenarioRunner.PhysicsAttempt>();
            var pending=h.worker.submit(() -> h.host.inScope(work,() -> ScenarioRunner.awaitPhysics(held,held,fixture.context.runId(),allowance,false,0,
                    h.host::waitTick,() -> fail("No complete sample after client stall"),() -> fail("No guard without sample"),rows::add)));
            await(() -> h.host.waitingCount()==1);
            assertFalse(pending.isDone()); assertEquals(1,allowance.consumed());
            h.now.set(work.deadlineNanos()); h.host.onTick();
            assertInstanceOf(ScopeTimeoutException.class,failure(pending)); assertEquals(1,allowance.consumed());
            assertEquals(1,rows.size()); assertNull(rows.getFirst().candidate());
            assertEquals(600_000_000_001L,work.deadlineNanos());
            assertThrows(ScopeTimeoutException.class,() -> h.host.inScope(work,() -> fail("Expired original scope renewed")));
        }
    }

    @Test void physicsSamplingCannotAcceptExpiryBeforeDispatchDuringCaptureOrAfterServerCompletion() throws Exception {
        for(int stage=0;stage<4;stage++) try(var h=new DispatchHarness()) {
            int expiry=stage; var fixture=new ScenarioClientTransitionTest.QueueFixture(); fixture.engine.pause();
            var held=ScenarioClientTransitionTest.clocks(fixture.sample(),100,100,100,100,100,100);
            var next=ScenarioClientTransitionTest.clocks(held,101,101,101,101,101,101);
            var work=h.host.beginWork(operation(1)); var allowance=new ScenarioRunner.PhysicsAllowance(40);
            var rows=new CopyOnWriteArrayList<ScenarioRunner.PhysicsAttempt>(); var effects=new AtomicInteger();
            var pending=h.worker.submit(() -> h.host.inScope(work,() -> ScenarioRunner.awaitPhysics(held,held,fixture.context.runId(),allowance,false,0,
                    () -> { if(expiry==0) h.now.set(work.deadlineNanos()); h.host.waitTick(); },
                    () -> {
                        h.host.computeOnClient(c -> { effects.incrementAndGet(); if(expiry==1) h.now.set(work.deadlineNanos()); return held; });
                        return h.host.computeOnServer(s -> { effects.incrementAndGet(); if(expiry==2) h.now.set(work.deadlineNanos()); return next; });
                    },() -> h.host.runOnClient(c -> { effects.incrementAndGet(); h.now.set(work.deadlineNanos()); }),rows::add)));
            if(expiry>0) {
                await(() -> h.host.waitingCount()==1); h.host.onTick();
                var client=h.client.poll(2,TimeUnit.SECONDS); assertNotNull(client); h.nativeCall(client);
                if(expiry>1) {
                    var server=h.server.poll(2,TimeUnit.SECONDS); assertNotNull(server);
                    h.serverThread.set(true); try { server.run(); } finally { h.serverThread.remove(); }
                    if(expiry==3) { var fresh=h.client.poll(2,TimeUnit.SECONDS); assertNotNull(fresh); h.nativeCall(fresh); }
                }
            }
            var error=failure(pending); assertInstanceOf(ScopeTimeoutException.class,error);
            assertEquals(1,allowance.consumed()); assertEquals(1,rows.size()); assertFalse(rows.getFirst().accepted());
            if(expiry==3) assertEquals(101,rows.getFirst().candidate().observerTick()); else assertNull(rows.getFirst().candidate());
            assertEquals(expiry,effects.get()); assertEquals(600_000_000_001L,work.deadlineNanos());
            assertTrue(List.of(error.getSuppressed()).stream().anyMatch(t -> t.getMessage().startsWith("Physics diagnostic:")));
        }
    }

    @Test void physicsWaitRefusesEqualStaleAndCancelledScopesAndLateCallbacksWithoutRetry() throws Exception {
        for(String kind:List.of("equal","stale","cancelled","binding","stopped")) try(var h=new DispatchHarness()) {
            var fixture=new ScenarioClientTransitionTest.QueueFixture(); fixture.engine.pause();
            var held=ScenarioClientTransitionTest.clocks(fixture.sample(),100,100,100,100,100,100);
            var work=h.host.beginWork(operation(1)); var allowance=new ScenarioRunner.PhysicsAllowance(40);
            var rows=new CopyOnWriteArrayList<ScenarioRunner.PhysicsAttempt>(); var effects=new AtomicInteger();
            var original=new IllegalStateException(kind+" original sentinel");
            if(kind.equals("equal") || kind.equals("stale")) {
                Scope refused=kind.equals("equal")?new Scope(work.operation(),Kind.WORK,work.deadlineNanos()):work;
                if(kind.equals("stale")) h.host.beginCleanup(work,null);
                var error=assertThrows(IllegalStateException.class,() -> ScenarioRunner.awaitPhysics(held,held,fixture.context.runId(),allowance,false,0,
                        () -> h.host.inScope(refused,h.host::waitTick),() -> fail("Invalid handle sampled"),() -> { },rows::add));
                assertEquals("Unbound/foreign/stale inspect scope",error.getMessage());
            } else {
                var pending=h.worker.submit(() -> h.host.inScope(work,() -> ScenarioRunner.awaitPhysics(held,held,fixture.context.runId(),allowance,false,0,
                        h.host::waitTick,() -> h.host.computeOnClient(c -> { effects.incrementAndGet(); throw original; }),() -> { },rows::add)));
                await(() -> h.host.waitingCount()==1);
                if(kind.equals("stopped")) h.nativeCall(() -> h.host.onStopping(null));
                else {
                    h.host.onTick(); var late=h.client.poll(2,TimeUnit.SECONDS); assertNotNull(late);
                    if(kind.equals("cancelled")) h.host.cancelSession(original);
                    h.nativeCall(late);
                }
                var error=failure(pending);
                if(kind.equals("stopped")) assertInstanceOf(IllegalStateException.class,error); else assertSame(original,error);
                assertEquals(kind.equals("binding")?1:0,effects.get());
            }
            assertEquals(1,allowance.consumed()); assertEquals(1,rows.size()); assertNull(rows.getFirst().candidate());
            assertFalse(rows.getFirst().accepted()); assertTrue(h.client.isEmpty()); assertTrue(h.server.isEmpty());
        }
    }

    @Test void inspectStoppingClaimsOnlyExistingCleanupAndSettlementWaitsForClaim() throws Exception {
        try (var h = new DispatchHarness()) {
            var op = operation(1);
            var work = h.host.beginWork(op);
            var original = new IllegalStateException("work failed");
            var cleanup = h.host.beginCleanup(work, original);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var calls = new AtomicInteger();
            h.host.bindStoppingOwner((client, scope) -> {
                assertSame(cleanup, scope);
                h.host.requireStoppingScope(scope);
                assertThrows(IllegalStateException.class, () -> h.host.requireStoppingScope(new Scope(op,Kind.CLEANUP,scope.deadlineNanos())));
                calls.incrementAndGet(); entered.countDown();
                assertDoesNotThrow(() -> assertTrue(release.await(2, TimeUnit.SECONDS)));
            });
            assertThrows(IllegalStateException.class, () -> h.host.requireStoppingScope(cleanup));
            var stopper = new Thread(() -> h.nativeCall(() -> h.host.onStopping(null)));
            stopper.start(); assertTrue(entered.await(2, TimeUnit.SECONDS));
            var settled = h.worker.submit(() -> h.host.settleCleanup(cleanup,
                    new FinalOutcome(op, Verdict.FAILURE, CleanupStatus.SETTLED)));
            await(() -> h.host.waitingCount() == 1);
            assertFalse(settled.isDone()); assertTrue(h.published.isEmpty());
            release.countDown(); stopper.join(2000); assertFalse(stopper.isAlive());
            settled.get(2, TimeUnit.SECONDS);
            assertEquals(List.of(new FinalOutcome(op, Verdict.FAILURE, CleanupStatus.SETTLED)), h.published);
            h.nativeCall(() -> h.host.onStopping(null));
            assertEquals(1, calls.get());
            assertThrows(IllegalStateException.class, () -> h.host.beginWork(operation(2)));
        }
    }

    @Test void inspectStoppingDuringWorkOrAfterCleanupExpiryCannotMintPermission() throws Exception {
        for (boolean expiredCleanup : List.of(false, true)) try (var h = new DispatchHarness()) {
            var calls = new AtomicInteger();
            h.host.bindStoppingOwner((client, scope) -> calls.incrementAndGet());
            var work = h.host.beginWork(operation(1));
            if (expiredCleanup) {
                var cleanup = h.host.beginCleanup(work, null); h.now.set(cleanup.deadlineNanos());
            }
            h.nativeCall(() -> h.host.onStopping(null));
            assertEquals(0, calls.get());
            assertThrows(IllegalStateException.class, () -> h.host.beginShutdownCleanup(operation(2), new Exception()));
        }
    }

    @Test void cleanupDeadlineCannotBeExtendedWhileStoppingClaimIsInFlight() throws Exception {
        try (var h=new DispatchHarness()) {
            var op=operation(1); var cleanup=h.host.beginCleanup(h.host.beginWork(op),null);
            var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var refused=new AtomicInteger();
            h.host.bindStoppingOwner((client,scope) -> {
                h.host.requireStoppingScope(scope); entered.countDown();
                assertDoesNotThrow(() -> assertTrue(release.await(2,TimeUnit.SECONDS)));
                assertThrows(ScopeTimeoutException.class,() -> h.host.requireStoppingScope(scope)); refused.incrementAndGet();
            });
            var stopper=new Thread(() -> h.nativeCall(() -> h.host.onStopping(null)));
            stopper.start(); assertTrue(entered.await(2,TimeUnit.SECONDS));
            var settlement=h.worker.submit(() -> h.host.settleCleanup(cleanup,new FinalOutcome(op,Verdict.FAILURE,CleanupStatus.UNRESOLVED)));
            await(() -> h.host.waitingCount()==1); h.now.set(cleanup.deadlineNanos()); h.host.onTick();
            assertInstanceOf(ScopeTimeoutException.class,failure(settlement)); assertTrue(h.published.isEmpty());
            release.countDown(); stopper.join(2000); assertFalse(stopper.isAlive()); assertEquals(1,refused.get());
            assertThrows(IllegalStateException.class,() -> h.host.beginShutdownCleanup(operation(2),new Exception()));
        }
    }

    @Test void inspectOutcomeIdentityAndPublicationFailureRetainBusyScope() throws Exception {
        try (var h = new DispatchHarness()) {
            var op = operation(1);
            var cleanup = h.host.beginCleanup(h.host.beginWork(op), null);
            var equal = new Operation(op.sessionId(), op.ordinal(), op.operationId(), op.command(), op.evidenceDirectory());
            assertThrows(IllegalStateException.class, () -> h.host.settleCleanup(cleanup,
                    new FinalOutcome(equal, Verdict.SUCCESS, CleanupStatus.SETTLED)));
            var error = new IllegalStateException("publisher failed");
            h.publicationFailure.set(error);
            assertSame(error, assertThrows(IllegalStateException.class, () -> h.host.settleCleanup(cleanup,
                    new FinalOutcome(op, Verdict.FAILURE, CleanupStatus.UNRESOLVED))));
            assertThrows(IllegalStateException.class, () -> h.host.beginWork(operation(2)));
            h.host.inScope(cleanup, () -> assertSame(cleanup, h.host.requireScope(op, Kind.CLEANUP)));
            h.publicationFailure.set(null);
            assertThrows(IllegalStateException.class, () -> h.host.settleCleanup(cleanup,
                    new FinalOutcome(op,Verdict.SUCCESS,CleanupStatus.SETTLED)));
            assertTrue(h.published.isEmpty());
        }
    }

    @Test void unresolvedSettlementCannotBecomeFailedAdmissionShutdownOrSuccessor() throws Exception {
        try (var h=new DispatchHarness()) {
            var op=operation(1); var cleanup=h.host.beginCleanup(h.host.beginWork(op),null);
            h.host.settleCleanup(cleanup,new FinalOutcome(op,Verdict.FAILURE,CleanupStatus.UNRESOLVED));
            assertThrows(IllegalStateException.class,() -> h.host.beginWork(operation(2)));
            assertThrows(IllegalStateException.class,() -> h.host.beginShutdownCleanup(operation(2),new Exception()));
            assertThrows(IllegalStateException.class,() -> h.host.inScope(cleanup,() -> fail("settled cleanup reused")));
        }
    }

    @Test void startupAndFailedAdmissionScopesNeverFabricateOrdinaryEnd() throws Exception {
        try(var h=new DispatchHarness()) {
            var startup=new Operation(inspectSession,0,UUID.randomUUID(),Command.STARTUP,temporary.resolve("startup"));
            var scope=h.host.beginStartup(startup); h.host.cancelSession(new IllegalStateException("startup failure"));
            var cleanup=h.host.beginCleanup(scope,null);
            h.host.settleCleanup(cleanup,new FinalOutcome(startup,Verdict.FAILURE,CleanupStatus.SETTLED));
            assertTrue(h.published.isEmpty());
        }
        try(var h=new DispatchHarness()) {
            var failed=operation(1); h.host.cancelSession(new IllegalStateException("header failed"));
            var cleanup=h.host.beginShutdownCleanup(failed,null);
            h.host.settleCleanup(cleanup,new FinalOutcome(failed,Verdict.FAILURE,CleanupStatus.SETTLED));
            assertTrue(h.published.isEmpty());
            assertThrows(IllegalStateException.class,() -> h.host.beginShutdownCleanup(operation(2),new Exception("No second N1 budget")));
        }
    }

    @Test void closeWorkAndCleanupShareOnlyOneTenSecondCeiling() throws Exception {
        try(var h=new DispatchHarness()) {
            var close=new Operation(inspectSession,1,UUID.randomUUID(),Command.CLOSE,temporary.resolve("close"));
            var work=h.host.beginWork(close);
            assertEquals(10_000_000_001L,work.deadlineNanos());
            h.now.set(3_000_000_001L); var cleanup=h.host.beginCleanup(work,null);
            assertEquals(10_000_000_001L,cleanup.deadlineNanos());
            h.now.set(10_000_000_001L);
            assertThrows(ScopeTimeoutException.class,() -> h.host.inScope(cleanup,() -> fail("renewed close budget")));
        }
    }

    @Test void executingNativeActionMayFinishButLateSuccessfulCompletionIsRefused() throws Exception {
        try(var h=new DispatchHarness()) {
            var work=h.host.beginWork(operation(1)); var effects=new AtomicInteger();
            var result=h.worker.submit(() -> h.host.inScope(work,() -> h.host.computeOnClient(client -> {
                effects.incrementAndGet(); h.now.set(work.deadlineNanos()); return "late completion";
            })));
            var action=h.client.poll(2,TimeUnit.SECONDS); assertNotNull(action); h.nativeCall(action);
            assertInstanceOf(ScopeTimeoutException.class,failure(result)); assertEquals(1,effects.get());
        }
    }

    @Test void visibleEndReturningAfterCleanupDeadlineCannotSettleOrRetry() throws Exception {
        try(var h=new DispatchHarness()) {
            var op=operation(1); var cleanup=h.host.beginCleanup(h.host.beginWork(op),null);
            h.publicationEffect.set(() -> h.now.set(cleanup.deadlineNanos()));
            assertThrows(ScopeTimeoutException.class,() -> h.host.settleCleanup(cleanup,new FinalOutcome(op,Verdict.SUCCESS,CleanupStatus.SETTLED)));
            assertEquals(1,h.published.size()); // Post-link truth remains visible, while local settlement failed.
            assertThrows(IllegalStateException.class,() -> h.host.beginWork(operation(2)));
            assertThrows(ScopeTimeoutException.class,() -> h.host.settleCleanup(cleanup,new FinalOutcome(op,Verdict.SUCCESS,CleanupStatus.SETTLED)));
            assertEquals(1,h.published.size());
        }
    }

    @Test void imageAlreadyDeliveredToAnExpiredWaitIsClosedWithoutBeingWritten() throws Exception {
        try(var h=new DispatchHarness()) {
            var work=h.host.beginWork(operation(1)); var closed=new AtomicInteger();
            var image=CompletableFuture.<AutoCloseable>completedFuture(closed::incrementAndGet);
            h.now.set(work.deadlineNanos());
            assertThrows(ScopeTimeoutException.class,() -> h.host.awaitImage(work,image));
            assertEquals(1,closed.get());
        }
    }

    @Test void imageDeliveredBeforeLateCaptureDispatchReturnsIsStillClosed() throws Exception {
        try(var h=new DispatchHarness()) {
            var work=h.host.beginWork(operation(1)); var closed=new AtomicInteger(); var image=new CompletableFuture<AutoCloseable>();
            var pending=h.worker.submit(() -> h.host.inScope(work,() -> h.host.captureImage(work,client -> { },(client,receiver) -> {
                receiver.accept(closed::incrementAndGet); h.now.set(work.deadlineNanos());
            },image)));
            var action=h.client.poll(2,TimeUnit.SECONDS); assertNotNull(action); h.nativeCall(action);
            assertInstanceOf(ScopeTimeoutException.class,failure(pending)); assertEquals(1,closed.get());
        }
    }

    @Test void priorSettledFailureCannotReplaceCurrentOperationsOriginalCause() throws Exception {
        try (var h=new DispatchHarness()) {
            var first=operation(1); var firstError=new IllegalStateException("old settled error");
            var firstCleanup=h.host.beginCleanup(h.host.beginWork(first),firstError);
            h.host.settleCleanup(firstCleanup,new FinalOutcome(first,Verdict.FAILURE,CleanupStatus.SETTLED));
            var next=operation(2); var work=h.host.beginWork(next);
            var original=new IllegalStateException("current work error"); var cleanup=h.host.beginCleanup(work,original);
            var queued=h.worker.submit(() -> h.host.inScope(cleanup,() -> h.host.runOnServer(server -> fail("late server action"))));
            var action=h.server.poll(2,TimeUnit.SECONDS); assertNotNull(action);
            h.nativeCall(() -> h.host.onStopping(null));
            assertSame(original,failure(queued));
            action.run();
        }
    }

    @Test void failedAdmissionAfterSettledFailureOwnsN1PrimaryWithoutContaminatingPriorOperation() throws Exception {
        try(var h=new DispatchHarness()) {
            var first=operation(1); var firstError=new IllegalStateException("completed A failure");
            var firstCleanup=h.host.beginCleanup(h.host.beginWork(first),firstError);
            h.host.settleCleanup(firstCleanup,new FinalOutcome(first,Verdict.FAILURE,CleanupStatus.SETTLED));
            var priorPublications=List.copyOf(h.published);
            // Closed fixture bytes and the original Throwable must survive the later transport shutdown unchanged.
            var closed=Files.createDirectories(first.evidenceDirectory()).resolve("closed-A.txt");
            Files.writeString(closed,"closed operation A evidence"); var priorBytes=Files.readAllBytes(closed);

            var failed=operation(2); var admissionError=new IllegalStateException("B header/BEGIN admission failure");
            h.host.cancelSession(admissionError); // B deliberately never receives WORK/issue().
            var cleanup=h.host.beginShutdownCleanup(failed,admissionError);
            assertSame(failed,cleanup.operation()); assertEquals(Kind.CLEANUP,cleanup.kind());
            assertEquals(10_000_000_001L,cleanup.deadlineNanos());
            assertThrows(IllegalStateException.class,() -> h.host.beginShutdownCleanup(operation(3),new Exception("cannot replace live B")));
            var effects=new AtomicInteger();
            var queued=h.worker.submit(() -> h.host.inScope(cleanup,() -> h.host.runOnClient(client -> {
                effects.incrementAndGet(); assertDoesNotThrow(() -> Files.writeString(closed,"contaminated A"));
            })));
            var action=h.client.poll(2,TimeUnit.SECONDS); assertNotNull(action);
            h.nativeCall(() -> h.host.onStopping(null));
            assertSame(admissionError,failure(queued));
            h.nativeCall(action);
            assertEquals(0,effects.get()); assertEquals(priorPublications,h.published);
            assertEquals(1,h.published.size()); assertSame(first,h.published.getFirst().operation());
            assertArrayEquals(priorBytes,Files.readAllBytes(closed));
            assertEquals(0,firstError.getSuppressed().length); assertEquals(0,admissionError.getSuppressed().length);
        }
    }

    @Test void inspectCreationRequiresStartupAndAnExplicitOwnerGuardBeforeAnyNativeAction() throws Exception {
        try (var h=new DispatchHarness()) {
            assertThrows(IllegalStateException.class,h.host::createWorld);
            var work=h.host.beginWork(operation(1));
            h.host.inScope(work,() -> assertThrows(IllegalStateException.class,() -> h.host.createWorld((client,path) -> fail())));
            assertTrue(h.client.isEmpty());
        }
    }

    @Test void inspectCaptureRechecksOwnerInsideNativeActionAndClosesLateImage() throws Exception {
        try (var h=new DispatchHarness()) {
            var work=h.host.beginWork(operation(1)); var failure=new IllegalStateException("replacement world");
            var captures=new AtomicInteger(); var closed=new AtomicInteger();
            var image=new CompletableFuture<AutoCloseable>();
            var rejected=h.worker.submit(() -> h.host.inScope(work,() -> h.host.captureImage(work,
                    client -> { throw failure; },(client,receiver) -> captures.incrementAndGet(),image)));
            var action=h.client.poll(2,TimeUnit.SECONDS); assertNotNull(action); h.nativeCall(action);
            assertSame(failure,failure(rejected)); assertEquals(0,captures.get());
            assertTrue(image.isCancelled());
            var successful=new CompletableFuture<AutoCloseable>();
            var receiver=new AtomicReference<java.util.function.Consumer<AutoCloseable>>();
            var queued=h.worker.submit(() -> h.host.inScope(work,() -> h.host.captureImage(work,
                    client -> { },(client,callback) -> { captures.incrementAndGet(); receiver.set(callback); },successful)));
            var capture=h.client.poll(2,TimeUnit.SECONDS); assertNotNull(capture); h.nativeCall(capture);
            queued.get(2,TimeUnit.SECONDS); assertEquals(1,captures.get());
            h.now.set(work.deadlineNanos()); receiver.get().accept(closed::incrementAndGet);
            assertEquals(1,closed.get()); assertTrue(successful.isCompletedExceptionally());
            assertThrows(IllegalStateException.class,() -> h.host.takeScreenshot(temporary,"unguarded"));
        }
    }

    @Test void inspectCloseRequiresCleanupAndRechecksOwnerInActualDisconnectAction() throws Exception {
        try (var h=new DispatchHarness()) {
            var op=operation(1); var work=h.host.beginWork(op);
            assertThrows(IllegalStateException.class,h.host::closeWorld);
            h.host.inScope(work,() -> assertThrows(IllegalStateException.class,() -> h.host.closeWorld(client -> fail())));
            assertTrue(h.client.isEmpty());
            var cleanup=h.host.beginCleanup(work,null); var replacement=new IllegalStateException("replaced connection");
            var closing=h.worker.submit(() -> h.host.inScope(cleanup,() -> h.host.closeWorld(client -> { throw replacement; })));
            var action=h.client.poll(2,TimeUnit.SECONDS); assertNotNull(action); h.nativeCall(action);
            assertSame(replacement,failure(closing)); assertTrue(h.client.isEmpty());
        }
    }

    @Test void nativeCloseChecksFreshAuthorityThenDisconnectsConnectionBeforeScreen() throws Exception {
        try(var h=new DispatchHarness()) {
            var cleanup=h.host.beginCleanup(h.host.beginWork(operation(1)),null);
            var calls=new ArrayList<String>();
            var closing=h.worker.submit(() -> h.host.inScope(cleanup,() -> h.host.runOnClient(client ->
                    h.host.disconnectOwnedWorld(cleanup,() -> calls.add("authority"),
                            () -> calls.add("connection"),() -> calls.add("screen")))));
            var action=h.client.poll(2,TimeUnit.SECONDS); assertNotNull(action); assertTrue(calls.isEmpty());
            h.nativeCall(action); closing.get(2,TimeUnit.SECONDS);
            assertEquals(List.of("authority","connection","screen"),calls);
        }
    }

    @Test void nativeCloseRefusedOrExpiredDuringFreshAuthorityCheckHasZeroEffects() throws Exception {
        for(boolean expired:List.of(false,true)) try(var h=new DispatchHarness()) {
            var cleanup=h.host.beginCleanup(h.host.beginWork(operation(1)),null);
            var refusal=new IllegalStateException("replacement world/connection"); var effects=new AtomicInteger();
            var closing=h.worker.submit(() -> h.host.inScope(cleanup,() -> h.host.runOnClient(client ->
                    h.host.disconnectOwnedWorld(cleanup,() -> {
                        if(expired) h.now.set(cleanup.deadlineNanos()); else throw refusal;
                    },effects::incrementAndGet,effects::incrementAndGet))));
            var action=h.client.poll(2,TimeUnit.SECONDS); assertNotNull(action); h.nativeCall(action);
            if(expired) assertInstanceOf(ScopeTimeoutException.class,failure(closing));
            else assertSame(refusal,failure(closing));
            assertEquals(0,effects.get()); assertTrue(h.client.isEmpty());
        }
    }

    @Test void nativeConnectionCloseFailureDoesNotEnterWaitingScreenDisconnect() throws Exception {
        try(var h=new DispatchHarness()) {
            var cleanup=h.host.beginCleanup(h.host.beginWork(operation(1)),null);
            var first=new IllegalStateException("channel close failed"); var calls=new ArrayList<String>();
            var closing=h.worker.submit(() -> h.host.inScope(cleanup,() -> h.host.runOnClient(client ->
                    h.host.disconnectOwnedWorld(cleanup,() -> calls.add("authority"),
                            () -> { calls.add("connection"); throw first; },() -> calls.add("screen")))));
            var action=h.client.poll(2,TimeUnit.SECONDS); assertNotNull(action); h.nativeCall(action);
            assertSame(first,failure(closing)); assertEquals(List.of("authority","connection"),calls);
        }
    }

    @Test void expectedNativeDisconnectCancellationCompletesOriginalCloseWithoutNewPermission() throws Exception {
        try(var h=new DispatchHarness()) {
            var close=new Operation(inspectSession,1,UUID.randomUUID(),Command.CLOSE,temporary.resolve("close"));
            var work=h.host.beginWork(close); var cleanup=h.host.beginCleanup(work,null);
            var original=new IllegalStateException("expected DISCONNECT"); var calls=new ArrayList<String>();
            var closing=h.worker.submit(() -> h.host.inScope(cleanup,() -> h.host.runOnClient(client ->
                    h.host.disconnectOwnedWorld(cleanup,() -> calls.add("authority"),() -> {
                        calls.add("connection"); h.host.cancelSession(original);
                        h.host.cancelSession(new IllegalStateException("duplicate exit"));
                    },() -> calls.add("screen")))));
            var action=h.client.poll(2,TimeUnit.SECONDS); assertNotNull(action); h.nativeCall(action);
            closing.get(2,TimeUnit.SECONDS); assertEquals(List.of("authority","connection","screen"),calls);
            assertEquals(10_000_000_001L,cleanup.deadlineNanos());
            h.host.settleCleanup(cleanup,new FinalOutcome(close,Verdict.SUCCESS,CleanupStatus.SETTLED));
            assertThrows(IllegalStateException.class,() -> h.host.inScope(cleanup,() -> fail("second close")));
            assertThrows(IllegalStateException.class,() -> h.host.beginCleanup(work,original));
            assertThrows(IllegalStateException.class,() -> h.host.beginWork(operation(2)));
            assertEquals(1,h.published.size()); assertTrue(h.client.isEmpty());
        }
    }

    @Test void nativeCloseReturningAtOriginalDeadlineCannotSettleOrRenewCleanup() throws Exception {
        try(var h=new DispatchHarness()) {
            var close=new Operation(inspectSession,1,UUID.randomUUID(),Command.CLOSE,temporary.resolve("late-close"));
            var work=h.host.beginWork(close); var cleanup=h.host.beginCleanup(work,null); var effects=new AtomicInteger();
            var closing=h.worker.submit(() -> h.host.inScope(cleanup,() -> h.host.runOnClient(client ->
                    h.host.disconnectOwnedWorld(cleanup,() -> { },effects::incrementAndGet,() -> {
                        effects.incrementAndGet(); h.now.set(cleanup.deadlineNanos());
                    }))));
            var action=h.client.poll(2,TimeUnit.SECONDS); assertNotNull(action); h.nativeCall(action);
            assertInstanceOf(ScopeTimeoutException.class,failure(closing)); assertEquals(2,effects.get());
            assertThrows(ScopeTimeoutException.class,() -> h.host.settleCleanup(cleanup,
                    new FinalOutcome(close,Verdict.SUCCESS,CleanupStatus.SETTLED)));
            assertThrows(IllegalStateException.class,() -> h.host.beginCleanup(work,null));
            assertThrows(IllegalStateException.class,() -> h.host.beginWork(operation(2)));
            assertTrue(h.published.isEmpty()); assertEquals(work.deadlineNanos(),cleanup.deadlineNanos());
        }
    }

    @Test void inspectStaleImagesAreClosedAndFinalImageNeverReplaced() throws Exception {
        try (var h = new DispatchHarness()) {
            var op = operation(1); var work = h.host.beginWork(op);
            var closed = new AtomicInteger();
            var received = new CompletableFuture<AutoCloseable>();
            h.now.set(work.deadlineNanos());
            h.host.receiveImage(work, closed::incrementAndGet, received);
            assertEquals(1, closed.get()); assertTrue(received.isCompletedExceptionally());
            var cleanup = h.host.beginCleanup(work, null);
            Files.createDirectories(op.evidenceDirectory());
            var dir = op.evidenceDirectory().toRealPath();
            var target = dir.resolve("failure.png"); Files.writeString(target, "original");
            assertThrows(RuntimeException.class, () -> h.host.writeImage(cleanup, closed::incrementAndGet, dir, "failure",
                    path -> assertDoesNotThrow(() -> Files.writeString(path, "replacement"))));
            assertEquals("original", Files.readString(target)); assertEquals(2, closed.get());
            try (var paths = Files.list(dir)) { assertEquals(List.of(target), paths.toList()); }
        }
    }

    @Test void previousOperationsImageCannotCompleteIntoNewScope() throws Exception {
        try (var h=new DispatchHarness()) {
            var op=operation(1); var work=h.host.beginWork(op); var cleanup=h.host.beginCleanup(work,null);
            h.host.settleCleanup(cleanup,new FinalOutcome(op,Verdict.SUCCESS,CleanupStatus.SETTLED));
            var current=h.host.beginWork(operation(2)); var closed=new AtomicInteger(); var image=new CompletableFuture<AutoCloseable>();
            h.host.receiveImage(work,closed::incrementAndGet,image);
            assertEquals(1,closed.get()); assertTrue(image.isCompletedExceptionally());
            h.host.inScope(current,() -> assertSame(current,h.host.requireScope(current.operation(),Kind.WORK)));
        }
    }

    @Test void inspectImageOutputIsBoundToItsScopeAndExpiryDuringEncodingLeavesNoFinal() throws Exception {
        try (var h = new DispatchHarness()) {
            var op = operation(1); var work = h.host.beginWork(op);
            var dir = Files.createDirectories(op.evidenceDirectory()).toRealPath();
            var other = Files.createDirectory(temporary.resolve("other")).toRealPath();
            var closed = new AtomicInteger();
            assertThrows(IllegalStateException.class, () -> h.host.writeImage(work, closed::incrementAndGet, other, "foreign",
                    path -> assertDoesNotThrow(() -> Files.writeString(path, "wrong scope"))));
            try (var files = Files.list(other)) { assertEquals(0, files.count()); }
            assertEquals(dir.resolve("first.png"), h.host.writeImage(work, closed::incrementAndGet, dir, "first",
                    path -> assertDoesNotThrow(() -> Files.writeString(path, "first image"))));
            assertEquals("first image", Files.readString(dir.resolve("first.png")));
            assertThrows(ScopeTimeoutException.class, () -> h.host.writeImage(work, closed::incrementAndGet, dir, "late", path -> {
                assertDoesNotThrow(() -> Files.writeString(path, "expired image")); h.now.set(work.deadlineNanos());
            }));
            assertEquals(3, closed.get());
            try (var files = Files.list(dir)) { assertEquals(List.of(dir.resolve("first.png")), files.toList()); }
        }
    }

    @Test void stoppedCleanupWaitRetainsOriginalWorkFailureAndCannotRenewExpiredClaim() throws Exception {
        try (var h = new DispatchHarness()) {
            var op=operation(1); var work=h.host.beginWork(op);
            var original=new IllegalStateException("first work error"); var cleanup=h.host.beginCleanup(work,original);
            var queued=h.worker.submit(() -> h.host.inScope(cleanup,() -> h.host.runOnClient(c -> fail("late cleanup action"))));
            var action=h.client.poll(2,TimeUnit.SECONDS); assertNotNull(action);
            h.nativeCall(() -> h.host.onStopping(null));
            assertSame(original,failure(queued)); h.nativeCall(action);
            h.now.set(cleanup.deadlineNanos());
            assertThrows(ScopeTimeoutException.class,() -> h.host.settleCleanup(cleanup,new FinalOutcome(op,Verdict.FAILURE,CleanupStatus.UNRESOLVED)));
            assertThrows(IllegalStateException.class,() -> h.host.beginCleanup(work,original));
            assertThrows(IllegalStateException.class,() -> h.host.beginShutdownCleanup(operation(2),original));
            assertTrue(h.published.isEmpty());
        }
    }

    private final UUID inspectSession = UUID.randomUUID();
    private Operation startupOperation() {
        return new Operation(inspectSession, 0, UUID.randomUUID(), Command.STARTUP, temporary.resolve("startup"));
    }
    private Operation operation(int ordinal) {
        return new Operation(inspectSession, ordinal, UUID.randomUUID(), Command.RUN, temporary.resolve("op-" + ordinal));
    }

    private static Throwable failure(Future<?> future) throws Exception {
        return assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS)).getCause();
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.onSpinWait();
        assertTrue(condition.getAsBoolean(), "worker did not reach the deterministic barrier");
    }

    private static final class DispatchHarness implements AutoCloseable {
        final AtomicLong now = new AtomicLong(1);
        final ThreadLocal<Boolean> clientThread = ThreadLocal.withInitial(() -> false);
        final ThreadLocal<Boolean> serverThread = ThreadLocal.withInitial(() -> false);
        final BlockingQueue<Runnable> client = new LinkedBlockingQueue<>();
        final BlockingQueue<Runnable> server = new LinkedBlockingQueue<>();
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final List<FinalOutcome> published = new CopyOnWriteArrayList<>();
        final AtomicReference<RuntimeException> publicationFailure = new AtomicReference<>();
        final AtomicReference<Runnable> publicationEffect = new AtomicReference<>(() -> { });
        final InspectScenarioHost host = new InspectScenarioHost(now::get, clientThread::get, serverThread::get,
                action -> { client.add(action); }, action -> { server.add(action); }, outcome -> {
                    if (publicationFailure.get() != null) throw publicationFailure.get();
                    published.add(outcome);
                    publicationEffect.get().run();
                });
        void nativeCall(Runnable action) { clientThread.set(true); try { action.run(); } finally { clientThread.remove(); } }
        @Override public void close() { worker.shutdownNow(); }
    }
    @Test void automaticClientAndServerCallbacksKeepResultsAndCallOrder() {
        var nativeHost = new NativeContext();
        var host = ScenarioHost.automatic(nativeHost.client);
        var result = new Object();
        host.runOnClient(client -> { assertNull(client); nativeHost.calls.add("client action"); });
        assertSame(result, host.computeOnClient(client -> {
            assertNull(client); nativeHost.calls.add("client result"); return result;
        }));
        host.createWorld();
        host.runOnServer(server -> { assertNull(server); nativeHost.calls.add("server action"); });
        assertSame(result, host.computeOnServer(server -> {
            assertNull(server); nativeHost.calls.add("server result"); return result;
        }));
        assertEquals(List.of("ClientGameTestContext.runOnClient", "client action", "ClientGameTestContext.computeOnClient",
                "client result", "ClientGameTestContext.worldBuilder", "TestWorldBuilder.create",
                "TestSingleplayerContext.getServer", "TestServerContext.runOnServer", "server action",
                "TestSingleplayerContext.getServer", "TestServerContext.computeOnServer", "server result"), nativeHost.calls);
    }

    @Test void automaticTickSaveScreenshotAndCloseUseOriginalNativeRoutes() {
        var nativeHost = new NativeContext();
        var host = ScenarioHost.automatic(nativeHost.client);
        host.createWorld();
        assertSame(nativeHost.saveDirectory, host.saveDirectory());
        host.waitTick();
        var directory = Path.of("bounded-evidence");
        assertSame(nativeHost.screenshot, host.takeScreenshot(directory, "before-reset"));
        assertSame(directory, nativeHost.options.destinationDir);
        assertEquals("before-reset", nativeHost.options.name);
        assertFalse(nativeHost.options.counterPrefix);
        assertEquals(1, nativeHost.options.tickDelta);
        assertNull(nativeHost.options.size);
        host.closeWorld();
        assertEquals(List.of("ClientGameTestContext.worldBuilder", "TestWorldBuilder.create",
                "TestSingleplayerContext.getWorldSave", "TestWorldSave.getSaveDirectory", "ClientGameTestContext.waitTick",
                "ClientGameTestContext.takeScreenshot", "TestSingleplayerContext.close"), nativeHost.calls);
    }

    @Test void successfulCreationKeepsNativeHandleWhenFirstServerValidationThrows() {
        var nativeHost = new NativeContext();
        var host = ScenarioHost.automatic(nativeHost.client);
        var failure = new AssertionError("first server validation");
        host.createWorld();
        assertSame(failure, assertThrows(AssertionError.class, () -> host.computeOnServer(server -> { throw failure; })));
        assertSame(nativeHost.saveDirectory, host.saveDirectory());
        host.closeWorld();
        assertEquals(List.of("ClientGameTestContext.worldBuilder", "TestWorldBuilder.create",
                "TestSingleplayerContext.getServer", "TestServerContext.computeOnServer",
                "TestSingleplayerContext.getWorldSave", "TestWorldSave.getSaveDirectory", "TestSingleplayerContext.close"), nativeHost.calls);
    }

    @Test void throwingCreationDoesNotDispatchOrCloseAnUncreatedWorld() {
        var nativeHost = new NativeContext();
        var host = ScenarioHost.automatic(nativeHost.client);
        var failure = new IllegalStateException("create failed");
        nativeHost.failureAt = "TestWorldBuilder.create";
        nativeHost.failure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class, host::createWorld));
        assertEquals(List.of("ClientGameTestContext.worldBuilder", "TestWorldBuilder.create"), nativeHost.calls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"clientRun", "clientCompute", "serverRun", "serverCompute"})
    void automaticCallbacksPropagateOriginalFailure(String route) {
        var nativeHost = new NativeContext();
        var host = ScenarioHost.automatic(nativeHost.client);
        host.createWorld();
        for (Throwable failure : List.of(new IllegalStateException("callback"), new AssertionError("callback"))) {
            assertSame(failure, assertThrows(Throwable.class, () -> {
                switch (route) {
                    case "clientRun" -> host.runOnClient(client -> rethrow(failure));
                    case "clientCompute" -> host.computeOnClient(client -> { rethrow(failure); return null; });
                    case "serverRun" -> host.runOnServer(server -> rethrow(failure));
                    case "serverCompute" -> host.computeOnServer(server -> { rethrow(failure); return null; });
                    default -> throw new AssertionError(route);
                }
            }));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ClientGameTestContext.runOnClient", "ClientGameTestContext.computeOnClient",
            "ClientGameTestContext.worldBuilder", "TestWorldBuilder.create", "TestSingleplayerContext.getServer",
            "TestServerContext.runOnServer", "TestServerContext.computeOnServer", "ClientGameTestContext.waitTick",
            "TestSingleplayerContext.getWorldSave", "TestWorldSave.getSaveDirectory",
            "ClientGameTestContext.takeScreenshot", "TestSingleplayerContext.close"})
    void automaticTransportPropagatesOriginalFailure(String boundary) {
        for (Throwable failure : List.of(new IllegalStateException(boundary), new AssertionError(boundary))) {
            var nativeHost = new NativeContext();
            var host = ScenarioHost.automatic(nativeHost.client);
            if (!boundary.equals("ClientGameTestContext.worldBuilder") && !boundary.equals("TestWorldBuilder.create"))
                host.createWorld();
            nativeHost.failureAt = boundary;
            nativeHost.failure = failure;
            assertSame(failure, assertThrows(Throwable.class, () -> {
                switch (boundary) {
                    case "ClientGameTestContext.runOnClient" -> host.runOnClient(client -> fail("must not dispatch"));
                    case "ClientGameTestContext.computeOnClient" -> host.computeOnClient(client -> fail("must not dispatch"));
                    case "ClientGameTestContext.worldBuilder", "TestWorldBuilder.create" -> host.createWorld();
                    case "TestSingleplayerContext.getServer", "TestServerContext.runOnServer" -> host.runOnServer(server -> fail("must not dispatch"));
                    case "TestServerContext.computeOnServer" -> host.computeOnServer(server -> fail("must not dispatch"));
                    case "ClientGameTestContext.waitTick" -> host.waitTick();
                    case "TestSingleplayerContext.getWorldSave", "TestWorldSave.getSaveDirectory" -> host.saveDirectory();
                    case "ClientGameTestContext.takeScreenshot" -> host.takeScreenshot(Path.of("bounded-evidence"), "failure");
                    case "TestSingleplayerContext.close" -> host.closeWorld();
                    default -> throw new AssertionError(boundary);
                }
            }));
        }
    }

    @Test void fixtureHelpersHaveOneGametestOwnedOrigin() throws Exception {
        for (Class<?> helper : List.of(RegistrationAcknowledgement.class, PacketHandlerAwait.class, TraversalCapture.class)) {
            String resource = helper.getName().replace('.', '/') + ".class";
            var origins = Collections.list(helper.getClassLoader().getResources(resource));
            var loadedFrom = helper.getProtectionDomain().getCodeSource().getLocation();
            System.out.println(helper.getName() + " loaded=" + loadedFrom + "; resources=" + origins);
            assertEquals(1, origins.size(), helper.getName() + " must have exactly one class resource");
            assertTrue(loadedFrom.getPath().endsWith("/fabric/build/classes/java/gametest/"),
                    () -> helper.getName() + " must load from gametest output: " + loadedFrom);
            assertEquals(loadedFrom.toExternalForm() + resource, origins.getFirst().toExternalForm());
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error error) throw error;
        throw (RuntimeException) failure;
    }

    /** Native interface doubles only; every test calls the production automatic adapter. */
    private static final class NativeContext implements InvocationHandler {
        final List<String> calls = new ArrayList<>();
        final Path saveDirectory = Path.of("native-save");
        final Path screenshot = Path.of("native-screenshot.png");
        final ClientGameTestContext client = proxy(ClientGameTestContext.class);
        final TestWorldBuilder builder = proxy(TestWorldBuilder.class);
        final TestSingleplayerContext world = proxy(TestSingleplayerContext.class);
        final TestServerContext server = proxy(TestServerContext.class);
        final TestWorldSave save = proxy(TestWorldSave.class);
        String failureAt;
        Throwable failure;
        TestScreenshotOptionsImpl options;

        private <T> T proxy(Class<T> type) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, this));
        }

        @Override public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
            String boundary = method.getDeclaringClass().getSimpleName() + "." + method.getName();
            calls.add(boundary);
            if (boundary.equals(failureAt)) throw failure;
            return switch (method.getName()) {
                case "runOnClient", "runOnServer" -> { ((FailableConsumer<?, ?>) arguments[0]).accept(null); yield null; }
                case "computeOnClient", "computeOnServer" -> ((FailableFunction<?, ?, ?>) arguments[0]).apply(null);
                case "worldBuilder" -> builder;
                case "create" -> world;
                case "getServer" -> server;
                case "getWorldSave" -> save;
                case "getSaveDirectory" -> saveDirectory;
                case "waitTick", "close" -> null;
                case "takeScreenshot" -> { options = assertInstanceOf(TestScreenshotOptionsImpl.class, arguments[0]); yield screenshot; }
                default -> throw new AssertionError("Unexpected native call: " + boundary);
            };
        }
    }
}
