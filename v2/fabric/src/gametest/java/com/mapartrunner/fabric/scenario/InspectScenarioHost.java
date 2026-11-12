package com.mapartrunner.fabric.scenario;

import com.mapartrunner.core.run.BuildEngine;
import com.mapartrunner.fabric.MapArtClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.Widget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.PlayerInput;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.rule.GameRules;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.*;
import java.util.function.*;

import static com.mapartrunner.fabric.scenario.ScenarioHost.*;

/** Ordinary native transport. Registration and the single command worker belong to the session initializer. */
final class InspectScenarioHost implements Inspect {
    private static final long WORK_NANOS = TimeUnit.MILLISECONDS.toNanos(600_000);
    private static final long CLEANUP_NANOS = TimeUnit.MILLISECONDS.toNanos(10_000);
    private final MinecraftClient client;
    private final LongSupplier clock;
    private final BooleanSupplier clientThread, serverThread;
    private final Consumer<Runnable> clientQueue, serverQueue;
    private final Consumer<FinalOutcome> publish;
    private final ScenarioLaunchReceipt receipts;
    private final Supplier<Instant> utc;
    // Created before BEGIN publication; retained through cleanup/END, replaced only by the next admitted operation.
    private Instant phaseStarted, workDeadline, phaseDeadline;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final ThreadLocal<Scope> bound = new ThreadLocal<>();
    private final Set<Operation> issued = Collections.newSetFromMap(new IdentityHashMap<>());
    private final AtomicReference<Throwable> originalFailure = new AtomicReference<>();
    // Session cancellation survives issue() clearing the per-operation cause after a contended BEGIN publication.
    private final AtomicReference<Throwable> cancellationFailure = new AtomicReference<>();
    private final AtomicBoolean stopNotified = new AtomicBoolean();
    private volatile Scope current;
    private enum Lineage { STARTUP, WORK, FAILED_ADMISSION }
    private Lineage lineage;
    private UUID session;
    private int lastOrdinal = -1;
    private boolean startupIssued, shutdownIssued, settling, settlementAttempted, stopClaimed, unresolved;
    private volatile Thread stoppingThread;
    private Scope stoppingScope;
    private BiConsumer<MinecraftClient, Scope> stoppingOwner;
    private final AtomicLong ticks = new AtomicLong();
    private volatile boolean stopDecisionSettled;
    private int waiting;
    private volatile boolean cancelled, stopped;
    private volatile MinecraftServer server;
    private volatile Path save;

    // WORK owns wrapper/read retirement; A owns suppression until actual terminal release. CLEANUP owns only the busy rendezvous.
    private static final class ExpiryBoundary {
        final Operation operation;
        final PredictionFaults.ExpiryIdentity identity;
        final long deadline, cutoff;
        Scope work, cleanup;
        ScenarioRunner runner;
        Runnable continuation, retireWrapper;
        CompletableFuture<?> result;
        ExpiryRead read;
        String stage="ARMED";
        int wrapperEntries, actionEntries, replayCount, busyRefusals;
        long cleanupDeadline, busyRequest;
        boolean retired, replayRejected;
        Throwable primary, replayFailure;
        ScenarioCommands.ExpiryBusyAck ack;
        ExpiryBoundary(Operation operation,Scope work,PredictionFaults.ExpiryIdentity identity,ScenarioRunner runner) {
            this.operation=operation; this.work=work; this.identity=identity; this.runner=runner;
            deadline=work.deadlineNanos(); cutoff=deadline+CLEANUP_NANOS;
        }
    }
    /** Fixed read token only: no caller-supplied action or effect permission. */
    static final class ExpiryRead {
        final Operation operation;
        final PredictionFaults.ExpiryIdentity identity;
        final int ordinal;
        final long deadline, cutoff;
        final Object boundary;
        String step="CLIENT";
        boolean queued;
        ScenarioRunner.ExpiryClient local;
        ScenarioRunner.ExpirySide remote;
        ScenarioRunner.ExpiryPair accepted;
        Throwable failure;
        long attempts;
        ScenarioRunner.ExpiryClocks latestClocks;
        ExpiryRead(ExpiryBoundary b,int ordinal) {
            boundary=b; operation=b.operation; identity=b.identity; this.ordinal=ordinal; deadline=b.deadline; cutoff=b.cutoff;
        }
    }
    private ExpiryBoundary expiryBoundary;
    private final AtomicBoolean expiryAbandoned=new AtomicBoolean();
    private volatile ScenarioCommands.ExpiryObservation expiryObservation;

    static boolean expiryAcceptanceEnabled() {
        return PredictionFaults.expiryEnabled(System.getProperty("mapart.test.inspect"),System.getProperty("mapart.test.inspect.acceptance"),
                System.getProperty("fabric.client.gametest"),System.getProperty("mapart.test.inspect.acceptance.case"));
    }
    void awaitExpiryStart(Operation operation) {
        rejectNativeWait();
        var work=requireScope(operation,Kind.WORK);
        check(expiryAcceptanceEnabled() && operation.command()==Command.RUN && operation.ordinal()==2,"Expiry start requires RUN2");
        lock.lock();
        try {
            while(clock.getAsLong()<work.deadlineNanos()-TimeUnit.MILLISECONDS.toNanos(30_000)) {
                valid(work); check(expiryAcceptanceEnabled() && !stopped && !stopNotified.get(),"Expiry start disabled/stopped"); pause(work);
            }
            valid(work); check(expiryAcceptanceEnabled() && !stopped && !stopNotified.get(),"Expiry start disabled/stopped");
        } finally { lock.unlock(); }
    }
    void armExpiryPause(Operation operation,PredictionFaults.ExpiryIdentity identity,ScenarioRunner runner) {
        rejectNativeWait();
        var work=requireScope(operation,Kind.WORK);
        lock.lock();
        try {
            check(expiryAcceptanceEnabled() && foreign==null && expiryBoundary==null && operation.command()==Command.RUN && operation.ordinal()==2
                    && identity!=null && identity.workDeadlineNanos()==work.deadlineNanos() && runner!=null && runner.usesInspectHost(this)
                    && runner.inspectSessionId().equals(operation.sessionId()),"Expiry pause requires original RUN2 WORK/identity/owner");
            expiryBoundary=new ExpiryBoundary(operation,work,identity,Objects.requireNonNull(runner)); projectExpiry();
        } finally { lock.unlock(); }
    }
    private void projectExpiry() {
        var b=expiryBoundary;
        expiryObservation=b==null?null:new ScenarioCommands.ExpiryObservation(b.operation.sessionId(),b.operation.operationId(),b.identity.context().runId(),
                b.operation.ordinal(),b.stage,b.deadline,b.cutoff,b.wrapperEntries,b.actionEntries,b.replayCount,b.replayRejected,
                b.retired,b.cleanupDeadline,b.busyRequest,b.busyRefusals,b.ack,
                b.primary==null?null:new ScenarioCommands.FailureObservation(b.primary.getClass().getName(),b.primary.getMessage()),
                b.replayFailure==null?null:new ScenarioCommands.FailureObservation(b.replayFailure.getClass().getName(),b.replayFailure.getMessage()),
                b.replayFailure!=null && b.replayFailure!=b.primary);
    }
    ScenarioCommands.ExpiryObservation observeExpiryAcceptance() { return expiryObservation; }
    private void requireExpiryOpen(ExpiryBoundary b) {
        check(expiryBoundary==b && expiryAcceptanceEnabled() && !expiryAbandoned.get() && !cancelled && !stopped && !stopNotified.get(),
                "Expiry acceptance disabled/cancelled/stopped");
        if(clock.getAsLong()-b.cutoff>=0) throw new ScopeTimeoutException();
    }
    /** Uses the absolute outer cutoff, never valid/pause on the expired WORK. Condition releases the owner lock. */
    private void pauseExpiry(ExpiryBoundary b) {
        requireExpiryOpen(b);
        waiting++;
        try { changed.awaitNanos(Math.min(b.cutoff-clock.getAsLong(),TimeUnit.MILLISECONDS.toNanos(50))); }
        catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("Inspect worker interrupted",error); }
        finally { waiting--; }
    }
    private void awaitExpiryRead(ExpiryBoundary b,int ordinal) {
        check(b.read==null,"Expiry read already pending");
        var request=b.read=new ExpiryRead(b,ordinal);
        b.stage=ordinal==1?"BEFORE_REPLAY_READ":"AFTER_REPLAY_READ"; projectExpiry();
        while(request.accepted==null && request.failure==null) { requireExpiryOpen(b); pauseExpiry(b); }
        if(request.failure instanceof Error fatal) throw fatal;
        if(request.failure instanceof RuntimeException error) throw error;
        check(request.accepted!=null,"Expiry read missing pair");
        b.read=null;
    }
    /** Commands calls before its original single beginCleanup; the actual await exception stays primary. */
    void awaitExpiryReplayBeforeCleanup(Scope work,Throwable primary) {
        rejectNativeWait(); lock.lock();
        try {
            var b=expiryBoundary;
            if(b==null || b.operation!=work.operation()) return;
            check(b.work==work && current==work && primary instanceof ScopeTimeoutException && clock.getAsLong()>=b.deadline
                    && b.stage.equals("HELD"),"Expiry lacks actual current-WORK await timeout/entered wrapper");
            b.primary=primary; b.stage="EXPIRED"; projectExpiry(); requireExpiryOpen(b);
            awaitExpiryRead(b,1);
            lock.unlock();
            try { replayExpiryWrapper(work,primary); } finally { lock.lock(); }
            awaitExpiryRead(b,2); b.stage="POST_REPLAY_MEASURED"; projectExpiry();
        } finally { lock.unlock(); }
    }
    /** Non-authorizing replay mechanics; the joined owner calls only after its fixed E read. */
    void replayExpiryWrapper(Scope work,Throwable primary) {
        rejectNativeWait(); lock.lock();
        try {
            var b=expiryBoundary;
            check(b!=null && b.work==work && current==work && !b.retired && primary instanceof ScopeTimeoutException
                    && clock.getAsLong()>=b.deadline && Set.of("HELD","BEFORE_REPLAY_READ").contains(b.stage),
                    "Expiry replay requires exact entered expired current WORK");
            requireExpiryOpen(b); b.primary=primary;
            var replay=b.continuation;
            check(replay!=null && b.replayCount==0,"Expiry continuation already consumed");
            b.continuation=null; b.replayCount++; b.stage="REPLAY_QUEUED"; projectExpiry();
            lock.unlock();
            try { clientQueue.accept(replay); } finally { lock.lock(); }
            while(b.replayFailure==null) { requireExpiryOpen(b); check(!b.stage.equals("REPLAY_FAILED"),"Expiry replay entered action"); pauseExpiry(b); }
            check(b.replayFailure instanceof ScopeTimeoutException && b.replayFailure!=primary && b.stage.equals("REPLAY_REJECTED"),
                    "Expiry replay did not reject original current expired WORK");
        } finally { lock.unlock(); }
    }
    void retireExpiryReplay(Scope work) {
        rejectNativeWait(); lock.lock();
        try {
            var b=expiryBoundary;
            if(b==null || b.operation!=work.operation() || b.retired) return;
            b.retired=true; b.read=null; b.continuation=null;
            try { if(b.retireWrapper!=null) b.retireWrapper.run(); }
            finally {
                b.retireWrapper=null; b.result=null; b.work=null; b.runner=null;
                b.stage=b.stage.equals("POST_REPLAY_MEASURED")?"CLEANUP_PENDING":"INCOMPLETE"; projectExpiry(); changed.signalAll();
            }
        } finally { lock.unlock(); }
    }
    void abandonExpiryAcceptance() {
        expiryAbandoned.set(true);
        if(lock.tryLock()) try {
            var b=expiryBoundary;
            if(b!=null && !b.stage.equals("SEALED_BEFORE_END")) {
                b.read=null; b.continuation=null; b.stage="INCOMPLETE"; projectExpiry();
            }
            changed.signalAll();
        } finally { lock.unlock(); }
    }
    private boolean expiryReadCurrent(ExpiryRead r,boolean local,String step) {
        var b=expiryBoundary;
        return b!=null && r.boundary==b && b.read==r && !b.retired && current==b.work && b.work!=null && r.operation==b.operation
                && r.identity==b.identity && r.step.equals(step) && expiryAcceptanceEnabled() && !expiryAbandoned.get()
                && !cancelled && !stopped && !stopNotified.get() && clock.getAsLong()<r.cutoff
                && (local?clientThread:serverThread).getAsBoolean();
    }
    /** Invoked by natural Host ticks. A too-early pair retains this exact request until a later tick. */
    private void scheduleExpiryRead() {
        if(!lock.tryLock()) return;
        ExpiryRead r;
        try {
            var b=expiryBoundary; r=b==null?null:b.read;
            if(r==null || r.queued || r.failure!=null || r.accepted!=null || !expiryReadCurrent(r,true,"CLIENT")) return;
            r.queued=true;
        } finally { lock.unlock(); }
        try { clientQueue.accept(() -> readExpiryClient(r)); }
        catch(RuntimeException | Error error) { failExpiryRead(r,error); }
    }
    private boolean claimExpiryRead(ExpiryRead r,boolean local,String step) {
        if(!lock.tryLock()) return false;
        try { return expiryReadCurrent(r,local,step); } finally { lock.unlock(); }
    }
    private void failExpiryRead(ExpiryRead r,Throwable failure) {
        if(!lock.tryLock()) return; // Worker cutoff remains authoritative even if a native commit is contended.
        try { if(expiryBoundary!=null && expiryBoundary.read==r) { r.failure=failure; changed.signalAll(); } }
        finally { lock.unlock(); }
    }
    private void readExpiryClient(ExpiryRead r) {
        if(!claimExpiryRead(r,true,"CLIENT")) return;
        try {
            var value=expiryBoundary.runner.readExpiryClient(r,client);
            if(!lock.tryLock()) return;
            try { if(!expiryReadCurrent(r,true,"CLIENT")) return; r.local=value; r.step="SERVER"; }
            finally { lock.unlock(); }
            serverQueue.accept(() -> readExpiryServer(r));
        } catch(RuntimeException | Error error) { failExpiryRead(r,error); }
    }
    private void readExpiryServer(ExpiryRead r) {
        if(!claimExpiryRead(r,false,"SERVER")) return;
        try {
            var value=expiryBoundary.runner.readExpiryServer(r,server);
            if(!lock.tryLock()) return;
            try { if(!expiryReadCurrent(r,false,"SERVER")) return; r.remote=value; r.step="ACCEPT"; }
            finally { lock.unlock(); }
            clientQueue.accept(() -> acceptExpiryRead(r));
        } catch(RuntimeException | Error error) { failExpiryRead(r,error); }
    }
    private void acceptExpiryRead(ExpiryRead r) {
        if(!claimExpiryRead(r,true,"ACCEPT")) return;
        try {
            var observed=expiryBoundary.runner.acceptExpiryPair(r,client,r.local,r.remote);
            if(!lock.tryLock()) return;
            try {
                if(!expiryReadCurrent(r,true,"ACCEPT")) return;
                r.attempts++; r.latestClocks=ScenarioRunner.ExpiryClocks.of(observed.pair());
                if(observed.advanced()) { expiryBoundary.runner.retainExpiryPair(r,observed.pair()); r.accepted=observed.pair(); }
                else { r.local=null; r.remote=null; r.step="CLIENT"; r.queued=false; }
                changed.signalAll();
            } finally { lock.unlock(); }
        } catch(RuntimeException | Error error) { failExpiryRead(r,error); }
    }
    void awaitExpiryBusyProof(Operation operation,Scope cleanup) {
        rejectNativeWait();
        check(requireScope(operation,Kind.CLEANUP)==cleanup,"Expiry busy proof requires exact CLEANUP");
        lock.lock(); boolean complete=false;
        try {
            var b=expiryBoundary;
            check(b!=null && b.operation==operation && b.retired && b.stage.equals("CLEANUP_PENDING") && b.actionEntries==0 && b.replayCount==1
                    && b.wrapperEntries==2 && b.replayRejected,"Expiry busy proof lacks measured original replay/zero action entry");
            requireExpiryOpen(b); b.cleanup=cleanup; b.cleanupDeadline=cleanup.deadlineNanos(); b.busyRequest=1; b.stage="BUSY_REQUIRED"; projectExpiry();
            while(b.ack==null) { requireExpiryOpen(b); valid(cleanup); pause(cleanup); }
            requireExpiryOpen(b); valid(cleanup); complete=true;
        } finally {
            if(expiryBoundary!=null && expiryBoundary.operation==operation) {
                expiryBoundary.cleanup=null; expiryBoundary.runner=null;
                expiryBoundary.stage=complete?"SEALED_BEFORE_END":"INCOMPLETE"; projectExpiry();
            }
            lock.unlock();
        }
    }
    void recordExpiryBusyRefusal() {
        if(!lock.tryLock()) return;
        try {
            var b=expiryBoundary;
            if(b!=null && b.cleanup!=null && current==b.cleanup && b.stage.equals("BUSY_REQUIRED") && expiryAcceptanceEnabled()
                    && !cancelled && !stopped && !expiryAbandoned.get() && clock.getAsLong()<b.cleanup.deadlineNanos()) {
                if(b.busyRefusals<Integer.MAX_VALUE) b.busyRefusals++; projectExpiry();
            }
        } finally { lock.unlock(); }
    }
    boolean acknowledgeExpiryBusyProof(MinecraftClient nativeClient,ScenarioCommands.ExpiryBusyAck ack) {
        if(ack==null || !clientThread.getAsBoolean() || client!=null && nativeClient!=client || !lock.tryLock()) return false;
        try {
            var b=expiryBoundary;
            if(b==null || b.cleanup==null || current!=b.cleanup
                    || !expiryAcceptanceEnabled() || cancelled || stopped || stopNotified.get() || expiryAbandoned.get()
                    || !expiryBusyAckMatches(expiryObservation,ack,clock.getAsLong())) return false;
            b.ack=ack; projectExpiry(); changed.signalAll(); return true;
        } finally { lock.unlock(); }
    }
    /** Detached value validation only; native thread/binding/current-CLEANUP authority remains at the owner entry. */
    static boolean expiryBusyAckMatches(ScenarioCommands.ExpiryObservation request,ScenarioCommands.ExpiryBusyAck ack,long now) {
        return request!=null && ack!=null && request.stage().equals("BUSY_REQUIRED") && request.boundaryRetired()
                && request.requestOrdinal()==1 && request.busyRefusals()>0 && request.acknowledgement()==null
                && now<request.cleanupDeadlineNanos() && now<request.outerCutoffNanos()
                && request.operationId().equals(ack.operationId()) && request.requestOrdinal()==ack.requestOrdinal()
                && request.cleanupDeadlineNanos()==ack.cleanupDeadlineNanos();
    }

    // One finite probe: arm -> entered hold -> worker consumes/replays -> measured -> cleanup proof -> retire.
    // Native callers only claim detached requests. WORK cancellation never authorizes an action or another scope.
    private static final class ForeignBoundary {
        final Operation operation;
        final long workDeadline;
        Scope work;
        Runnable continuation;
        String stage="ARMED";
        boolean entered, replayRejected, measured, retired;
        long request;
        long cleanupDeadline;
        Scope cleanup;
        ScenarioCommands.ForeignProofAck ack;
        ScenarioCommands.ForeignFinalFacts facts;
        ForeignBoundary(Operation operation,Scope work) { this.operation=operation; this.work=work; workDeadline=work.deadlineNanos(); }
    }
    private ForeignBoundary foreign;
    private final Map<String,int[]> foreignGuards=new TreeMap<>();
    private final AtomicBoolean foreignAbandoned=new AtomicBoolean();
    private volatile ScenarioCommands.ForeignObservation foreignObservation;

    static boolean foreignAcceptanceEnabled() {
        return Boolean.getBoolean("mapart.test.inspect") && Boolean.getBoolean("mapart.test.inspect.acceptance")
                && System.getProperty("fabric.client.gametest")==null
                && "foreign-world".equals(System.getProperty("mapart.test.inspect.acceptance.case"));
    }
    void armForeignResetStop(Operation operation) {
        var work=requireScope(operation,Kind.WORK);
        lock.lock();
        try {
            check(foreignAcceptanceEnabled() && foreign==null && expiryBoundary==null && operation.ordinal()==3 && operation.command()==Command.RERUN,
                    "Foreign boundary requires original RERUN3 reset-stop");
            foreign=new ForeignBoundary(operation,work); projectForeign();
        } finally { lock.unlock(); }
    }
    private void projectForeign() {
        var f=foreign;
        foreignObservation=f==null?null:new ScenarioCommands.ForeignObservation(f.operation.sessionId(),f.operation.operationId(),
                f.operation.ordinal(),f.stage,f.workDeadline,f.entered,f.replayRejected,cancelled,f.retired,
                f.request,f.cleanupDeadline,foreignGuardFacts(),f.facts,f.ack);
    }
    List<ScenarioCommands.ForeignGuardFact> foreignGuardFacts() {
        lock.lock();
        try { return foreignGuards.entrySet().stream().map(e -> new ScenarioCommands.ForeignGuardFact(
                e.getKey(),e.getValue()[0],e.getValue()[1],e.getValue()[2],e.getValue()[3])).toList(); }
        finally { lock.unlock(); }
    }
    void foreignGuard(String site,Runnable guard) {
        lock.lock();
        try {
            if(foreign==null) { guard.run(); return; }
            var counts=foreignGuards.computeIfAbsent(site,key -> new int[4]); counts[0]++;
            try { guard.run(); counts[2]++; }
            catch(RuntimeException | Error failure) { counts[1]++; throw failure; }
            finally { projectForeign(); }
        } finally { lock.unlock(); }
    }
    void foreignEffect(String site) {
        lock.lock();
        try { if(foreign!=null) { foreignGuards.computeIfAbsent(site,key -> new int[4])[3]++; projectForeign(); } }
        finally { lock.unlock(); }
    }
    ScenarioCommands.ForeignObservation observeForeignAcceptance() { return foreignObservation; }
    private void requireForeignOpen(ForeignBoundary f,long deadline) {
        check(foreign==f && foreignAcceptanceEnabled() && !foreignAbandoned.get() && !stopped && !stopNotified.get(),
                "Foreign acceptance abandoned/stopped/disabled");
        if(clock.getAsLong()-deadline>=0) throw new ScopeTimeoutException();
    }
    private boolean nativeForeignClaim(MinecraftClient nativeClient) {
        return clientThread.getAsBoolean() && (client==null || nativeClient==client) && foreignAcceptanceEnabled()
                && !foreignAbandoned.get() && !stopped && !stopNotified.get();
    }
    boolean releaseForeignAcceptanceBoundary(MinecraftClient nativeClient) {
        if(!nativeForeignClaim(nativeClient) || !lock.tryLock()) return false;
        try {
            var f=foreign;
            if(f==null || !(f.stage.equals("HELD") || f.stage.equals("CANCELLED_HOLD")) || current!=f.work
                    || !cancelled || clock.getAsLong()-f.workDeadline>=0) return false;
            f.stage="RELEASE_REQUESTED"; projectForeign(); changed.signalAll(); return true;
        } finally { lock.unlock(); }
    }
    boolean acknowledgeForeignReplayMeasurement(MinecraftClient nativeClient,UUID operationId,long workDeadline) {
        if(!nativeForeignClaim(nativeClient) || !lock.tryLock()) return false;
        try {
            var f=foreign;
            if(f==null || !f.stage.equals("REPLAY_REJECTED") || !f.operation.operationId().equals(operationId)
                    || f.workDeadline!=workDeadline || clock.getAsLong()-workDeadline>=0) return false;
            f.measured=true; f.stage="POST_REPLAY_MEASURED"; projectForeign(); changed.signalAll(); return true;
        } finally { lock.unlock(); }
    }
    /** Scheduling wait only; do not validate/rebind/dispatch cancelled WORK here. */
    void awaitForeignReplacementBeforeCleanup(Scope work,Throwable primary) {
        rejectNativeWait(); lock.lock();
        try {
            var f=foreign;
            if(f==null || f.operation!=work.operation()) return;
            check(f.work==work && f.entered && cancelled && primary==cancellationFailure.get(),"Foreign hold lacks original cancellation");
            if(f.stage.equals("HELD")) { f.stage="CANCELLED_HOLD"; projectForeign(); }
            while(!f.stage.equals("RELEASE_REQUESTED")) { requireForeignOpen(f,f.workDeadline); pause(work); }
            requireForeignOpen(f,f.workDeadline);
            var replay=f.continuation;
            check(replay!=null,"Foreign continuation already consumed");
            f.continuation=null; f.stage="REPLAY_QUEUED"; projectForeign();
            // Consume under the owner lock before submission; no native callback can consume this twice.
            lock.unlock();
            try { clientQueue.accept(replay); }
            finally { lock.lock(); }
            while(!f.measured) {
                requireForeignOpen(f,f.workDeadline);
                check(!f.stage.equals("REPLAY_FAILED"),"Original wrapper did not reject live cancellation"); pause(work);
            }
            requireForeignOpen(f,f.workDeadline);
            check(f.replayRejected,"No original-wrapper cancellation acknowledgement");
        } finally { lock.unlock(); }
    }
    void retireForeignReplacementBoundary(Scope work) {
        rejectNativeWait(); lock.lock();
        try {
            var f=foreign;
            if(f==null || f.operation!=work.operation() || f.retired) return;
            f.retired=true; f.work=null; f.continuation=null;
            f.stage=f.measured?"CLEANUP_PENDING":"ABANDONED";
            projectForeign(); changed.signalAll();
        } finally { lock.unlock(); }
    }
    /** Nonblocking native exit notification, including the cancelled Commands early return. */
    void abandonForeignAcceptance() {
        var observation=foreignObservation;
        if(observation==null || Set.of("INCOMPLETE","ABANDONED").contains(observation.stage())
                || observation.stage().equals("SEALED_BEFORE_END") && current==null) return;
        foreignAbandoned.set(true);
        if(lock.tryLock()) try {
            if(foreign!=null) {
                foreign.continuation=null; foreign.stage=foreign.request==0?"ABANDONED":"INCOMPLETE"; projectForeign();
            }
            changed.signalAll();
        } finally { lock.unlock(); }
    }
    void awaitForeignFinalProof(Operation operation,Scope cleanup,ScenarioCommands.ForeignFinalFacts facts) {
        check(requireScope(operation,Kind.CLEANUP)==cleanup,"Foreign final proof requires exact cleanup");
        boolean completed=false;
        lock.lock();
        try {
            var f=foreign;
            check(f!=null && f.operation==operation && f.retired && f.measured && f.replayRejected && f.request==0,
                    "Foreign final proof lacks measured original replay");
            requireForeignOpen(f,cleanup.deadlineNanos());
            f.cleanup=cleanup; f.cleanupDeadline=cleanup.deadlineNanos(); f.facts=Objects.requireNonNull(facts);
            f.request=1; f.stage="FINAL_NATIVE_READ_REQUIRED";
            projectForeign(); changed.signalAll();
            while(f.ack==null) { requireForeignOpen(f,cleanup.deadlineNanos()); pause(cleanup); }
            requireForeignOpen(f,cleanup.deadlineNanos());
            check(requireScope(operation,Kind.CLEANUP)==cleanup,"Foreign final cleanup changed");
            completed=true;
        } finally {
            if(foreign!=null && foreign.operation==operation) {
                foreign.cleanup=null; foreign.continuation=null;
                foreign.stage=completed?"SEALED_BEFORE_END":"INCOMPLETE"; projectForeign();
            }
            lock.unlock();
        }
    }
    boolean acknowledgeForeignFinalProof(MinecraftClient nativeClient,ScenarioCommands.ForeignProofAck ack) {
        if(ack==null || !nativeForeignClaim(nativeClient) || !lock.tryLock()) return false;
        try {
            var f=foreign;
            if(f==null || !f.stage.equals("FINAL_NATIVE_READ_REQUIRED") || f.cleanup==null || current!=f.cleanup || f.ack!=null
                    || !f.operation.operationId().equals(ack.operationId()) || f.request!=ack.requestOrdinal()
                    || clock.getAsLong()-f.cleanup.deadlineNanos()>=0 || ack.rows()<=0 || ack.bytes()<=0
                    || ack.sha256()==null || !ack.sha256().matches("[0-9a-f]{64}")
                    || ack.finalObservationIdentity()==null || ack.finalObservationIdentity().isBlank()) return false;
            f.ack=ack; f.stage="SEAL_ACKNOWLEDGED"; projectForeign(); changed.signalAll(); return true;
        } finally { lock.unlock(); }
    }

    InspectScenarioHost(MinecraftClient client) { this(client, null); }

    InspectScenarioHost(MinecraftClient client, Consumer<FinalOutcome> publish) {
        this.client = Objects.requireNonNull(client);
        clock = System::nanoTime; clientThread = client::isOnThread;
        serverThread = () -> server != null && server.isOnThread();
        clientQueue = client::execute;
        serverQueue = action -> {
            var owned = server;
            check(owned != null, "Owned server is unavailable"); owned.execute(action);
        };
        this.publish = publish; receipts = ScenarioLaunchReceipt.fromEnvironment(); utc = Instant::now;
    }

    /** Native queues/clock only: tests exercise the same issuing/dispatch/settlement policies. */
    InspectScenarioHost(LongSupplier clock, BooleanSupplier clientThread, BooleanSupplier serverThread,
                        Consumer<Runnable> clientQueue, Consumer<Runnable> serverQueue, Consumer<FinalOutcome> publish) {
        this(clock, clientThread, serverThread, clientQueue, serverQueue, publish, null, Instant::now);
    }
    InspectScenarioHost(LongSupplier clock, BooleanSupplier clientThread, BooleanSupplier serverThread,
                        Consumer<Runnable> clientQueue, Consumer<Runnable> serverQueue, Consumer<FinalOutcome> publish,
                        ScenarioLaunchReceipt receipts, Supplier<Instant> utc) {
        client = null; this.clock = clock; this.clientThread = clientThread; this.serverThread = serverThread;
        this.clientQueue = clientQueue; this.serverQueue = serverQueue; this.publish = publish;
        this.receipts = receipts; this.utc = utc;
    }

    @Override public Scope beginStartup(Operation operation) {
        rejectNativeWait(); lock.lock();
        try {
            check(!startupIssued && operation.ordinal() == 0 && operation.command() == Command.STARTUP, "Startup is single-use");
            var scope = issue(operation, Kind.STARTUP, WORK_NANOS); startupIssued = true; return scope;
        } finally { lock.unlock(); }
    }
    @Override public Scope beginWork(Operation operation) {
        rejectNativeWait(); lock.lock();
        try {
            check(operation.ordinal() > 0 && operation.command() != Command.STARTUP, "Invalid work operation");
            return issue(operation, Kind.WORK, operation.command()==Command.CLOSE?CLEANUP_NANOS:WORK_NANOS);
        } finally { lock.unlock(); }
    }
    private Scope issue(Operation operation, Kind kind, long budget) {
        check(!cancelled && !stopped && current == null, "Inspect admission is unavailable/busy");
        check(!issued.contains(operation) && operation.ordinal() > lastOrdinal, "Operation already issued/stale");
        check(session == null || session.equals(operation.sessionId()), "Foreign session");
        long deadline = clock.getAsLong() + budget;
        if (kind == Kind.WORK && receipts != null) {
            phaseStarted = utc.get(); workDeadline = phaseDeadline = phaseStarted.plusNanos(budget);
            try { receipts.operation(operation, "WORK", phaseStarted, phaseDeadline, null, null); }
            catch (RuntimeException | Error failure) { cancelSession(failure); throw failure; }
        }
        session = operation.sessionId(); lastOrdinal = operation.ordinal(); issued.add(operation);
        originalFailure.set(null); settlementAttempted=false;
        lineage=kind==Kind.STARTUP?Lineage.STARTUP:Lineage.WORK;
        return current = new Scope(operation, kind, deadline);
    }
    @Override public Scope beginCleanup(Scope work, Throwable primary) {
        rejectNativeWait(); lock.lock();
        try {
            check(current == work && work != null && work.kind() != Kind.CLEANUP, "Cleanup transition already used/foreign");
            retain(primary);
            long now = clock.getAsLong();
            long deadline = now + CLEANUP_NANOS;
            if (work.operation().command()==Command.CLOSE) deadline=work.deadlineNanos();
            else if (now - work.deadlineNanos() >= 0) deadline = work.deadlineNanos() + CLEANUP_NANOS;
            current = new Scope(work.operation(), Kind.CLEANUP, deadline);
            if (lineage == Lineage.WORK && receipts != null) {
                Instant nowUtc = utc.get();
                check(!nowUtc.isBefore(phaseStarted), "Phase UTC moved backwards");
                phaseStarted = nowUtc;
                // Project the retained monotonic deadline; never grant an extra interval after publication.
                phaseDeadline = nowUtc.plusNanos(deadline - now);
                Instant cap = work.operation().command()==Command.CLOSE ? workDeadline : workDeadline.plusMillis(10000);
                if (phaseDeadline.isAfter(cap)) phaseDeadline = cap;
                receipts.operation(work.operation(), "CLEANUP", phaseStarted, phaseDeadline,
                        primary instanceof ScopeTimeoutException ? Verdict.TIMEOUT : primary == null ? Verdict.SUCCESS : Verdict.FAILURE, null);
            }
            changed.signalAll(); return current;
        } finally { lock.unlock(); }
    }
    @Override public Scope beginShutdownCleanup(Operation operation, Throwable primary) {
        rejectNativeWait(); lock.lock();
        try {
            check(cancelled && !stopped && !unresolved && !shutdownIssued && current == null && !issued.contains(operation), "Shutdown cleanup requires failed admission without a scope");
            check(session == null || session.equals(operation.sessionId()), "Foreign shutdown session");
            check(operation.ordinal() > lastOrdinal, "Stale failed admission");
            // N1 bypasses issue(); this newly authenticated admission owns its cause, not the prior settled command.
            originalFailure.set(primary);
            issued.add(operation); session = operation.sessionId(); lastOrdinal = operation.ordinal(); settlementAttempted=false;
            lineage=Lineage.FAILED_ADMISSION;
            shutdownIssued=true;
            return current = new Scope(operation, Kind.CLEANUP, clock.getAsLong() + CLEANUP_NANOS);
        } finally { lock.unlock(); }
    }
    @Override public void settleStartup(Scope scope) {
        rejectNativeWait(); lock.lock();
        try { valid(scope); check(scope.kind() == Kind.STARTUP && !stopped, "Invalid startup settlement"); current = null; }
        finally { lock.unlock(); }
    }
    @Override public void settleCleanup(Scope scope, FinalOutcome outcome) {
        rejectNativeWait(); lock.lock();
        try {
            waitForStopping(scope); valid(scope);
            check(scope.kind() == Kind.CLEANUP && outcome.operation() == scope.operation(), "Foreign final outcome");
            check(!settlementAttempted, "Settlement already attempted"); settling = true; settlementAttempted=true;
            try {
                // STARTUP and exceptional failed admission never had an ordinary phase stream to complete.
                if(lineage==Lineage.WORK) {
                    if (receipts != null) {
                        Instant now = utc.get(); check(!now.isBefore(phaseStarted), "Phase UTC moved backwards");
                        receipts.operation(outcome.operation(), "SETTLED", now, null, outcome.verdict(), outcome.cleanup());
                    }
                    if (publish != null) publish.accept(outcome);
                }
                valid(scope); // A visible late END cannot turn an expired cleanup into local settlement.
                if (outcome.cleanup() == CleanupStatus.UNRESOLVED) { cancelled = true; unresolved=true; }
                if(foreign!=null && foreign.operation==scope.operation() && foreign.request!=0 && foreignAbandoned.get()) {
                    foreign.stage="INCOMPLETE"; projectForeign();
                }
                current = null; changed.signalAll();
            } finally { settling = false; }
        } finally { lock.unlock(); }
    }
    @Override public void cancelSession(Throwable primary) {
        var cause = primary == null ? new IllegalStateException("Inspect session cancelled") : primary;
        cancellationFailure.compareAndSet(null, cause);
        retain(cause); cancelled = true;
        // Native callbacks never wait for phase filesystem IO; worker condition waits already poll within 50ms.
        if (lock.tryLock()) try { projectForeign(); changed.signalAll(); } finally { lock.unlock(); }
    }
    private void retain(Throwable failure) { if (failure != null) originalFailure.compareAndSet(null, failure); }

    @Override public void bindStoppingOwner(BiConsumer<MinecraftClient, Scope> owner) {
        lock.lock(); try { check(stoppingOwner == null, "Stopping owner already bound"); stoppingOwner = Objects.requireNonNull(owner); }
        finally { lock.unlock(); }
    }
    void onStopping(MinecraftClient nativeClient) {
        check(clientThread.getAsBoolean(), "Stopping callback is not on client thread");
        check(client == null || nativeClient == client, "Foreign client stopping callback");
        if (!stopNotified.compareAndSet(false, true)) return;
        abandonExpiryAcceptance();
        abandonForeignAcceptance();
        cancelSession(new IllegalStateException("Inspect client is stopping"));
        stopped = true;
        // Never wait on the native thread. Contention refuses effects; bounded waits also poll stopped.
        if (!lock.tryLock()) { stopDecisionSettled = true; return; }
        Scope claim;
        try {
            changed.signalAll();
            if (stopClaimed || settling || settlementAttempted || current == null || current.kind() != Kind.CLEANUP
                    || clock.getAsLong() - current.deadlineNanos() >= 0 || stoppingOwner == null) return;
            stopClaimed = true; stoppingThread = Thread.currentThread(); stoppingScope = claim = current;
        } finally { stopDecisionSettled = true; changed.signalAll(); lock.unlock(); }
        try { stoppingOwner.accept(nativeClient, claim); }
        catch (RuntimeException | Error error) { retain(error); }
        finally {
            stoppingScope = null; stoppingThread = null;
            if (lock.tryLock()) try { changed.signalAll(); } finally { lock.unlock(); }
        }
    }
    @Override public void requireStoppingScope(Scope cleanup) {
        check(lock.tryLock(), "Stopping permission is contended");
        try {
            check(clientThread.getAsBoolean() && Thread.currentThread() == stoppingThread && cleanup == stoppingScope,
                    "No exact client stopping claim");
            valid(cleanup); check(cleanup.kind() == Kind.CLEANUP && !settling, "Stopping scope unavailable");
        } finally { lock.unlock(); }
    }
    @Override public Scope requireScope(Operation operation, Kind kind) {
        var scope = bound.get(); lock.lock();
        try {
            if (!clientThread.getAsBoolean() && !serverThread.getAsBoolean()) waitForStopping(scope);
            valid(scope); check(scope.operation() == operation && scope.kind() == kind, "Wrong bound operation/kind"); return scope;
        } finally { lock.unlock(); }
    }
    @Override public boolean observationScopeValid(Scope scope) {
        return scope != null && scope == current && scope.kind() == Kind.WORK
                && !cancelled && !stopped && !stopNotified.get()
                && clock.getAsLong() - scope.deadlineNanos() < 0;
    }
    @Override public void inScope(Scope scope, Runnable action) { inScope(scope, () -> { action.run(); return null; }); }
    @Override public <V> V inScope(Scope scope, Supplier<V> action) {
        rejectNativeWait();
        lock.lock(); try { valid(scope); } finally { lock.unlock(); }
        check(bound.get() == null, "Nested inspect scope binding");
        bound.set(scope); try { return action.get(); } finally { bound.remove(); }
    }
    private void valid(Scope scope) {
        check(scope != null && scope == current, "Unbound/foreign/stale inspect scope");
        if (clock.getAsLong() - scope.deadlineNanos() >= 0) throw new ScopeTimeoutException();
        if (scope.kind() != Kind.CLEANUP && cancelled) throwOriginal();
    }
    private void waitForStopping(Scope scope) {
        while ((stopNotified.get() && !stopDecisionSettled) || stoppingThread != null) { valid(scope); pause(scope); }
    }
    private void rejectNativeWait() { check(!clientThread.getAsBoolean() && !serverThread.getAsBoolean(), "Blocking transport called on native thread"); }
    private void throwOriginal() {
        var failure = originalFailure.get();
        if (failure == null) failure = cancellationFailure.get();
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        throw new IllegalStateException("Inspect session cancelled/stopped", failure);
    }
    private Scope dispatchScope() {
        rejectNativeWait(); var scope = bound.get();
        lock.lock(); try { waitForStopping(scope); valid(scope); if (stopped) throwOriginal(); return scope; }
        finally { lock.unlock(); }
    }
    private <V> V dispatch(Consumer<Runnable> queue, Supplier<V> action) {
        var scope = dispatchScope(); var result = new CompletableFuture<V>();
        class QueuedAction implements Runnable {
            private Supplier<V> pending;
            private boolean held, expiryHeld, consumed;
            QueuedAction(Supplier<V> supplied) { pending=supplied; }
            @Override public void run() {
                lock.lock();
                try {
                    if(consumed) return;
                    var f=foreign;
                    var b=expiryBoundary;
                    if(queue==clientQueue && b!=null && b.work==scope && b.stage.equals("ARMED")) {
                        check(clientThread.getAsBoolean(),"Expiry wrapper requires native client thread"); valid(scope); requireExpiryOpen(b);
                        b.wrapperEntries++; b.continuation=this; b.result=result; b.stage="HELD"; expiryHeld=true;
                        b.retireWrapper=() -> {
                            consumed=true; pending=null;
                            result.completeExceptionally(new IllegalStateException("Expiry wrapper retired"));
                        };
                        projectExpiry(); return;
                    }
                    if(expiryHeld && (b==null || b.work!=scope || !b.stage.equals("REPLAY_QUEUED") || !clientThread.getAsBoolean())) return;
                    if(queue==clientQueue && f!=null && f.work==scope && f.stage.equals("ARMED")) {
                        valid(scope); requireForeignOpen(f,scope.deadlineNanos());
                        f.entered=true; f.continuation=this; f.stage="HELD"; held=true; projectForeign(); return;
                    }
                    // Re-entry is accepted only after the worker consumed the retained continuation.
                    if(held && (f==null || f.work!=scope || !f.stage.equals("REPLAY_QUEUED"))) {
                        if(f==null || f.retired || foreignAbandoned.get()) {
                            consumed=true; pending=null; result.completeExceptionally(new IllegalStateException("Foreign wrapper retired"));
                        }
                        return;
                    }
                    if(expiryHeld) b.wrapperEntries++;
                    consumed=true;
                    try {
                        valid(scope); if (stopped) throwOriginal();
                        if(expiryHeld) { b.actionEntries++; projectExpiry(); }
                        result.complete(pending.get());
                        if(held) { f.stage="REPLAY_FAILED"; projectForeign(); }
                        if(expiryHeld) { b.stage="REPLAY_FAILED"; projectExpiry(); }
                    } catch(RuntimeException | Error error) {
                        if(expiryHeld) {
                            b.replayFailure=error;
                            boolean rejected=current==scope && expiryAcceptanceEnabled() && !expiryAbandoned.get() && error instanceof ScopeTimeoutException && error!=b.primary && !cancelled && !stopped
                                    && !stopNotified.get() && clock.getAsLong()>=scope.deadlineNanos();
                            b.replayRejected=rejected; b.stage=rejected?"REPLAY_REJECTED":"REPLAY_FAILED"; projectExpiry();
                        }
                        if(held) {
                            f.replayRejected=cancelled && error==cancellationFailure.get() && !stopped && !stopNotified.get()
                                    && clock.getAsLong()-scope.deadlineNanos()<0;
                            f.stage=f.replayRejected?"REPLAY_REJECTED":"REPLAY_FAILED"; projectForeign();
                        }
                        result.completeExceptionally(error);
                    } finally { pending=null; }
                } catch(RuntimeException | Error error) { consumed=true; pending=null; result.completeExceptionally(error); }
                finally { changed.signalAll(); lock.unlock(); }
            }
        }
        queue.accept(new QueuedAction(action));
        return await(scope, result);
    }
    private <V> V await(Scope scope, CompletableFuture<V> result) {
        lock.lock();
        try {
            while (!result.isDone()) { valid(scope); if (stopped) throwOriginal(); pause(scope); }
            try {
                V value=result.join(); valid(scope); if(stopped) throwOriginal(); return value;
            }
            catch (CompletionException error) {
                if (error.getCause() instanceof Error fatal) throw fatal;
                if (error.getCause() instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(error.getCause());
            }
        } finally { lock.unlock(); }
    }
    private void pause(Scope scope) {
        long remaining = scope.deadlineNanos() - clock.getAsLong();
        if (remaining <= 0) throw new ScopeTimeoutException();
        waiting++;
        try { changed.awaitNanos(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50))); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("Inspect worker interrupted", error); }
        finally { waiting--; }
    }
    int waitingCount() { lock.lock(); try { return waiting; } finally { lock.unlock(); } }
    void onTick() {
        ticks.incrementAndGet();
        if (lock.tryLock()) try { changed.signalAll(); } finally { lock.unlock(); }
        scheduleExpiryRead();
    }
    @Override public void waitTick() {
        var scope = dispatchScope(); lock.lock();
        try {
            long earlier = ticks.get();
            while (ticks.get() == earlier) { valid(scope); if (stopped) throwOriginal(); pause(scope); }
            valid(scope);
        } finally { lock.unlock(); }
    }
    @Override public void runOnClient(Consumer<MinecraftClient> action) { computeOnClient(c -> { action.accept(c); return null; }); }
    @Override public <V> V computeOnClient(Function<MinecraftClient, V> action) { return dispatch(clientQueue, () -> action.apply(client)); }
    @Override public void runOnServer(Consumer<MinecraftServer> action) { computeOnServer(s -> { action.accept(s); return null; }); }
    @Override public <V> V computeOnServer(Function<MinecraftServer, V> action) { return dispatch(serverQueue, () -> action.apply(server)); }

    boolean runWhenClientReady(Predicate<MinecraftClient> ready, Consumer<MinecraftClient> action) {
        return computeOnClient(c -> {
            if (!ready.test(c)) return false;
            action.accept(c); return true;
        });
    }

    @Override public void createWorld() { throw new IllegalStateException("Inspect creation requires Runner prospective-save guard"); }
    @Override public void createWorld(BiConsumer<MinecraftClient, Path> beforeCreate) {
        var scope = dispatchScope(); check(scope.kind() == Kind.STARTUP, "Creation requires STARTUP"); Objects.requireNonNull(beforeCreate);
        // CLIENT_STARTED still has the initial splash; its continuation can replace a screen until the overlay clears.
        while (!runWhenClientReady(c -> c.isFinishedLoading() && c.getOverlay() == null, c -> {
            check(c.world==null && c.getServer()==null && c.player==null,"Creation cannot open over a foreign world");
            beforeCreate.accept(c,c.getLevelStorage().getSavesDirectory().resolve("mapart-inspect-"+scope.operation().sessionId()).toAbsolutePath().normalize());
            valid(scope);
            check(c.world==null && c.getServer()==null && c.player==null,"Creation cannot open over a foreign world");
            CreateWorldScreen.show(c, () -> cancelSession(new IllegalStateException("World creation cancelled")));
            // Pinned show() installs the screen synchronously but pumps native tasks; retain only the original authority.
            valid(scope);
            check(c.world==null && c.getServer()==null && c.player==null,"Creation opened over a foreign world");
            check(c.currentScreen instanceof CreateWorldScreen, "Creation screen replaced");
            var actualScreen = (CreateWorldScreen)c.currentScreen;
            var creator = actualScreen.getWorldCreator();
            creator.setWorldName("mapart-inspect-" + scope.operation().sessionId());
            creator.setGameMode(WorldCreator.Mode.SURVIVAL); creator.setCheatsEnabled(false);
            var flat = creator.getGeneratorOptionsHolder().getCombinedRegistryManager().getOrThrow(RegistryKeys.WORLD_PRESET).getOrThrow(WorldPresets.FLAT);
            creator.setWorldType(new WorldCreator.WorldType(flat));
            creator.setSeed("1"); creator.setGenerateStructures(false);
            creator.getGameRules().setValue(GameRules.ADVANCE_TIME, false, null);
            creator.getGameRules().setValue(GameRules.ADVANCE_WEATHER, false, null);
            creator.getGameRules().setValue(GameRules.DO_MOB_SPAWNING, false, null);
            Path prospective = c.getLevelStorage().getSavesDirectory().resolve(creator.getWorldDirectoryName()).toAbsolutePath().normalize();
            beforeCreate.accept(c, prospective); // Runner authority; only fresh validation remains before the press.
            valid(scope);
            check(c.world==null && c.getServer()==null && c.player==null,"Creation cannot press over a foreign world");
            check(c.currentScreen == actualScreen && actualScreen.getWorldCreator() == creator
                    && c.getLevelStorage().getSavesDirectory().resolve(creator.getWorldDirectoryName()).toAbsolutePath().normalize().equals(prospective),
                    "Creation screen/creator/save changed");
            var buttons = new ArrayList<ButtonWidget>();
            discover(actualScreen, Collections.newSetFromMap(new IdentityHashMap<>()), buttons);
            String create = Text.translatable("selectWorld.create").getString();
            var matches = buttons.stream().filter(b -> b.getMessage().getString().equals(create)).toList();
            check(matches.size() == 1 && matches.getFirst().active && matches.getFirst().visible, "Expected one active visible create button");
            valid(scope);
            matches.getFirst().onPress(new MouseInput(0, 0));
        })) waitTick();
        while (!computeOnClient(c -> {
            if (c.currentScreen != null || c.getServer() == null || c.world == null || c.player == null
                    || c.getNetworkHandler() == null || !c.getNetworkHandler().getConnection().isOpen()) return false;
            server = c.getServer(); save = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize(); return true;
        })) waitTick();
    }
    private static void discover(Object element, Set<Object> seen, List<ButtonWidget> buttons) {
        if (!seen.add(element)) return;
        if (element instanceof ButtonWidget button) buttons.add(button);
        if (element instanceof ParentElement parent) for (Element child : parent.children()) discover(child, seen, buttons);
        if (element instanceof Widget widget) widget.forEachChild(child -> discover(child, seen, buttons));
    }
    @Override public Path saveDirectory() { check(save != null, "No actual owned save"); return save; }
    @Override public void closeWorld() { throw new IllegalStateException("Inspect close requires Runner's fresh binding guard"); }
    @Override public void closeWorld(Consumer<MinecraftClient> beforeClose) {
        var scope=dispatchScope(); check(scope.kind()==Kind.CLEANUP,"Inspect close requires CLEANUP"); Objects.requireNonNull(beforeClose);
        runOnClient(c -> {
            String operation=scope.operation().command()+" #"+scope.operation().ordinal()+" "+scope.operation().operationId()
                    +"; session="+scope.operation().sessionId();
            disconnectOwnedWorld(scope,() -> {
                beforeClose.accept(c); check(c.getServer() == server && server != null, "Foreign close server");
                var connection=c.getNetworkHandler().getConnection();
                System.out.println("INSPECT_NATIVE_CLOSE ENTER; "+operation+"; world="+System.identityHashCode(c.world)
                        +"; server="+System.identityHashCode(c.getServer())+"; connection="+System.identityHashCode(connection)
                        +"; connectionOpen="+connection.isOpen());
            },() -> { foreignEffect("CLOSE_NATIVE_WORLD"); c.world.disconnect(Text.translatable("menu.savingLevel")); },
                    () -> { foreignEffect("CLOSE_NATIVE_CLIENT"); c.disconnect(new TitleScreen(), false); });
            var connection=c.getNetworkHandler()==null?null:c.getNetworkHandler().getConnection();
            System.out.println("INSPECT_NATIVE_CLOSE RETURN; "+operation+"; world="+System.identityHashCode(c.world)
                    +"; server="+System.identityHashCode(c.getServer())+"; connection="+System.identityHashCode(connection)
                    +"; connectionOpen="+(connection!=null && connection.isOpen()));
        });
        while (!computeOnClient(c -> c.getServer() == null && c.world == null && c.player == null)) waitTick();
    }
    void disconnectOwnedWorld(Scope scope, Runnable beforeDisconnect, Runnable disconnectConnection, Runnable disconnectScreen) {
        check(clientThread.getAsBoolean() && scope.kind()==Kind.CLEANUP,"Native close requires client CLEANUP");
        beforeDisconnect.run(); valid(scope);
        // One owned teardown: vanilla's screen disconnect waits for the server logout caused by this connection close.
        // Expected DISCONNECT cancellation must retain this cleanup through the second half, without a live-connection recheck.
        disconnectConnection.run();
        disconnectScreen.run();
    }
    @Override public void awaitNeutralChat() {
        var scope = dispatchScope(); check(scope.kind() == Kind.WORK, "Neutral chat wait requires WORK");
        int successive = 0;
        while (successive < 2) {
            waitTick();
            boolean neutral = computeOnClient(c -> c.currentScreen == null && !c.isPaused() && c.player != null
                    && List.of(c.options.forwardKey,c.options.backKey,c.options.leftKey,c.options.rightKey,c.options.jumpKey,
                    c.options.sneakKey,c.options.sprintKey,c.options.attackKey,c.options.useKey).stream().noneMatch(k -> k.isPressed())
                    && MapArtClient.status().controlOwner() == BuildEngine.ControlOwner.NONE
                    && c.player.input.playerInput.equals(PlayerInput.DEFAULT) && c.player.getLastPlayerInput().equals(PlayerInput.DEFAULT)
                    && c.player.input.getMovementInput().lengthSquared() == 0);
            successive = neutral ? successive + 1 : 0;
        }
    }
    @Override public Path takeScreenshot(Path directory, String name) { throw new IllegalStateException("Inspect capture requires Runner's fresh binding guard"); }
    @Override public Path takeScreenshot(Path directory, String name, Consumer<MinecraftClient> beforeCapture) {
        var scope = dispatchScope(); var result = new CompletableFuture<NativeImage>();
        captureImage(scope,beforeCapture,(c,receiver) -> ScreenshotRecorder.takeScreenshot(c.getFramebuffer(),receiver),result);
        NativeImage image = awaitImage(scope, result);
        return writeImage(scope, image, directory, name, path -> {
            // Reuse compact capture's standard PNG/MemoryCache route; the exclusive temp never follows a reparse entry.
            var pixels = new BufferedImage(image.getWidth(),image.getHeight(),BufferedImage.TYPE_INT_ARGB);
            pixels.setRGB(0,0,image.getWidth(),image.getHeight(),image.copyPixelsArgb(),0,image.getWidth());
            var writers=ImageIO.getImageWritersByFormatName("PNG");
            check(writers.hasNext(),"PNG encoder unavailable"); var writer=writers.next();
            try (var bytes=Files.newOutputStream(path,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
                 var output=new MemoryCacheImageOutputStream(bytes)) {
                writer.setOutput(output); writer.write(pixels); output.flush();
            } catch (IOException error) { throw new UncheckedIOException(error); }
            finally { writer.dispose(); pixels.flush(); }
        });
    }
    <I extends AutoCloseable> I awaitImage(Scope scope, CompletableFuture<I> result) {
        rejectNativeWait();
        try { return await(scope,result); }
        catch(RuntimeException | Error original) {
            abandonImage(result,original); throw original;
        }
    }
    <I extends AutoCloseable> void captureImage(Scope scope, Consumer<MinecraftClient> beforeCapture,
                                              BiConsumer<MinecraftClient,Consumer<I>> capture, CompletableFuture<I> result) {
        try {
            check(dispatchScope()==scope,"Foreign capture scope"); Objects.requireNonNull(beforeCapture);
            runOnClient(c -> {
                foreignGuard("SCREENSHOT",() -> { beforeCapture.accept(c); valid(scope); });
                foreignEffect("SCREENSHOT"); capture.accept(c,image -> receiveImage(scope,image,result));
            });
        } catch(RuntimeException | Error original) { abandonImage(result,original); throw original; }
    }
    private static <I extends AutoCloseable> void abandonImage(CompletableFuture<I> result,Throwable original) {
        // Capture dispatch itself can fail after synchronous image delivery, before the worker reaches awaitImage.
        if(!result.cancel(false)) {
            I image=null;
            try { image=result.getNow(null); } catch(CompletionException | CancellationException unavailable) { }
            if(image!=null) try { closeImage(image); }
            catch(RuntimeException | Error closing) { if(closing!=original) original.addSuppressed(closing); }
        }
    }
    <I extends AutoCloseable> void receiveImage(Scope scope, I image, CompletableFuture<I> result) {
        lock.lock();
        try {
            try { valid(scope); if (stopped) throwOriginal(); }
            catch (RuntimeException | Error error) {
                try { closeImage(image); } catch (RuntimeException | Error closing) { if (closing!=error) error.addSuppressed(closing); }
                result.completeExceptionally(error); changed.signalAll(); return;
            }
            if (!result.complete(image)) closeImage(image);
            changed.signalAll();
        } finally { lock.unlock(); }
    }
    <I extends AutoCloseable> Path writeImage(Scope scope, I image, Path directory, String name, Consumer<Path> encode) {
        Path temp = null;
        Throwable primary = null;
        try (image) {
            check(name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"), "Invalid image name");
            check(directory.isAbsolute() && directory.normalize().equals(directory) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    && directory.toRealPath().equals(directory) && directory.startsWith(scope.operation().evidenceDirectory()), "Redirected/foreign image parent");
            Path target = directory.resolve(name + ".png");
            lock.lock();
            try { valid(scope); check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS), "Image already exists"); }
            finally { lock.unlock(); }
            temp = Files.createTempFile(directory, ".inspect-image-", ".png");
            encode.accept(temp);
            lock.lock();
            try {
                valid(scope); if (stopped) throwOriginal();
                check(directory.toRealPath().equals(directory) && Files.isRegularFile(temp, LinkOption.NOFOLLOW_LINKS)
                        && temp.toRealPath().equals(temp), "Image paths redirected");
                Files.createLink(target, temp); // Atomic no replacement, including a target reparse entry.
                return target;
            } finally { lock.unlock(); }
        } catch (IOException error) { var wrapped=new UncheckedIOException(error); primary=wrapped; throw wrapped; }
        catch (RuntimeException | Error error) { primary=error; throw error; }
        catch (Exception error) { var wrapped=new IllegalStateException("Image close failed", error); primary=wrapped; throw wrapped; }
        finally {
            if (temp != null) try { Files.delete(temp); }
            catch (IOException error) { if (primary!=null) primary.addSuppressed(error); else throw new UncheckedIOException(error); }
        }
    }
    private static void closeImage(AutoCloseable image) {
        try { image.close(); } catch (Exception error) { throw new IllegalStateException("Late image close failed", error); }
    }
    private static void check(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
}
