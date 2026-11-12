package com.mapartrunner.fabric.scenario;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static com.mapartrunner.fabric.scenario.ScenarioHost.*;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** One worker owns startup and mutations. Native callbacks only check authority and enqueue. */
final class ScenarioCommands {
    record InspectObservation(boolean ready, boolean busy, boolean restartRequired, Optional<PublishedCompletion> last) { }
    record PublishedCompletion(UUID sessionId, UUID operationId, int ordinal, Command command, Optional<UUID> runId,
                               String verdict, boolean evidenceComplete, String cleanupStatus, boolean cleanupSettled,
                               String evidenceDirectory, String summaryPath, Optional<FailureObservation> failure) { }
    record FailureObservation(String className, String message) { }

    private final Executor worker;
    private final Inspect host;
    private ScenarioRunner runner;
    // Guarded by this: queued remains true until the matching cleanup has returned settled.
    private boolean queued, ready, cancelled;
    private Throwable terminal;
    private Operation active;
    private ScenarioRunner.InspectCompletion last;
    private final LongSupplier clock = System::nanoTime;
    // First intent closes admission permanently; worker settlement or the fixed cutoff releases native stop.
    private enum Exit { OPEN, REQUESTED, RELEASE_NATIVE }
    private Exit exit = Exit.OPEN;
    private long exitDeadline;

    ScenarioCommands(Executor worker, Inspect host) {
        this.worker = Objects.requireNonNull(worker);
        this.host = Objects.requireNonNull(host);
    }

    void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        var root = literal("maparttest");
        for (var command : new Command[]{Command.RESET, Command.RUN, Command.RERUN}) {
            root.then(literal(command.name().toLowerCase(java.util.Locale.ROOT))
                    .then(literal("grounded-two-lane").executes(context -> {
                        var source = context.getSource();
                        return submit(command, source.getClient(), message -> source.getClient().execute(
                                () -> source.sendFeedback(Text.literal(message))));
                    })));
        }
        root.then(literal("last").executes(context -> {
            context.getSource().sendFeedback(Text.literal(lastDescription())); return 1;
        }));
        dispatcher.register(root);
    }

    void initialize(ScenarioObserver.Watch watch, ScenarioLaunchReceipt receipts, Runnable stop) {
        synchronized (this) {
            if (queued || runner != null || cancelled || terminal != null) throw new IllegalStateException("Inspect initialization is single-use");
            queued = true;
        }
        worker.execute(() -> {
            try {
                synchronized (this) { if (exit != Exit.OPEN) return; }
                var created = ScenarioRunner.inspect(host, watch);
                synchronized (this) { runner = created; }
                created.initializeInspectWorld(() -> receipts.inspect(created.inspectSessionId().toString()));
                synchronized (this) { if (!cancelled && terminal == null) ready = true; }
            } catch (RuntimeException | Error failure) {
                terminate(failure);
                System.err.println("Inspect startup failed; ordinary client stop requested");
                failure.printStackTrace(System.err);
                synchronized (this) { exit = Exit.RELEASE_NATIVE; }
                stop.run();
            } finally { workerFinished(); }
        });
    }

    int submit(Command command, MinecraftClient client, Consumer<String> reply) {
        synchronized (this) {
            String refusal = refusal();
            if (refusal != null) { reply.accept(refusal); return 0; }
            try { runner.requireInspectCommandWorld(client); }
            catch (RuntimeException | Error failure) {
                reply.accept("Inspect command refused: " + failure.getMessage()); return 0;
            }
            return enqueue(command, reply);
        }
    }

    /** Called only after the native read-only guard; tests exercise this shared worker path without a game. */
    synchronized int enqueue(Command command, Consumer<String> reply) {
        if (command != Command.RESET && command != Command.RUN && command != Command.RERUN)
            throw new IllegalArgumentException("Not a public mutation");
        String refusal = refusal();
        if (refusal != null) { reply.accept(refusal); return 0; }
        queued = true;
        try { worker.execute(() -> execute(command, reply)); }
        catch (RuntimeException | Error failure) { terminate(failure); queued = false; throw failure; }
        return 1;
    }

    private String refusal() {
        if (exit != Exit.OPEN || terminal != null || cancelled) return "Inspect session unavailable; restart required";
        if (queued) return "Inspect is busy through cleanup; use /maparttest last";
        if (!ready) return "Inspect startup is not ready";
        return null;
    }

    private void execute(Command command, Consumer<String> reply) {
        try {
            synchronized (this) { if (exit != Exit.OPEN || cancelled || terminal != null) return; }
            var operation = runner.beginInspectOperation(command);
            synchronized (this) { active = operation; }
            final Scope work;
            try { work = host.beginWork(operation); }
            catch (RuntimeException | Error failure) {
                runner.failInspectAdmission(operation, failure); throw failure;
            }
            Throwable primary = null;
            try {
                host.inScope(work, () -> {
                    host.awaitNeutralChat();
                    var definition = ScenarioDefinition.load();
                    if (command == Command.RESET || command == Command.RERUN) runner.reset(definition);
                    if (command == Command.RUN || command == Command.RERUN) runner.assertResult(runner.run(definition));
                });
            } catch (RuntimeException | Error failure) { primary = failure; }
            var cleanup = host.beginCleanup(work, primary);
            var failure = primary;
            var completion = host.inScope(cleanup, () -> runner.finishInspectOperation(operation, cleanup, failure));
            feedback(reply, publish(operation, completion) ? describe(completion) : "Inspect session unavailable; restart required");
        } catch (RuntimeException | Error failure) {
            terminate(failure);
            feedback(reply, "Inspect session unavailable; restart required: " + failure.getMessage());
        } finally {
            // A terminal failure still clears scheduling state, never the terminal admission latch or retained last.
            workerFinished();
        }
    }

    private synchronized boolean publish(Operation operation, ScenarioRunner.InspectCompletion completion) {
        if (cancelled || terminal != null) return false;
        if (active != operation || !completion.cleanupSettled()
                || !completion.sessionId().equals(operation.sessionId())
                || !completion.operationId().equals(operation.operationId()) || completion.ordinal() != operation.ordinal()
                || completion.command() != operation.command()
                || last != null && completion.ordinal() <= last.ordinal())
            throw new IllegalStateException("Foreign/stale or unsettled inspect completion");
        last = completion;
        active = null;
        return true;
    }

    synchronized String lastDescription() {
        return (active == null ? queued ? "Inspect startup/work queued" : "Inspect idle" : "Inspect active " + identity(active))
                + (terminal != null || cancelled ? "; restart required" : "")
                + "; last: " + (last == null ? "none" : describe(last));
    }

    synchronized boolean isReady() { return exit == Exit.OPEN && ready && !cancelled && terminal == null; }

    /** Commands publication can precede worker settlement; paths describe evidence without granting authority. */
    synchronized InspectObservation observeInspect() {
        var published = Optional.ofNullable(last).map(completion -> new PublishedCompletion(
                completion.sessionId(), completion.operationId(), completion.ordinal(), completion.command(), completion.runId(),
                completion.verdict(), completion.evidenceComplete(), completion.cleanupStatus(), completion.cleanupSettled(),
                completion.evidenceDirectory().toString(), completion.summaryPath().toString(),
                Optional.ofNullable(completion.failure()).map(failure -> new FailureObservation(failure.getClass().getName(), failure.getMessage()))));
        return new InspectObservation(isReady(), queued, exit != Exit.OPEN || cancelled || terminal != null, published);
    }

    /** Native intent only: no scope, IO, wait, interrupt or replacement worker. */
    synchronized boolean requestStop() {
        if (releaseNativeStop()) return false;
        if (exit == Exit.REQUESTED) return true;
        boolean damagedAdmission = runner != null && runner.inspectAdmissionFailure().isPresent()
                && runner.failedInspectAdmission().isPresent();
        if (cancelled || terminal != null && !damagedAdmission || !queued && !ready && !damagedAdmission) return false;
        exit = Exit.REQUESTED;
        exitDeadline = clock.getAsLong() + 10_000_000_000L;
        if (queued) {
            // STARTUP/WORK unwind into their original cleanup; CLEANUP retains only its remaining permission.
            cancel(new IllegalStateException("Inspect application exit requested"));
        } else {
            queued = true;
            try { worker.execute(this::closeForExit); }
            catch (RuntimeException | Error failure) { terminate(failure); workerFinished(); }
        }
        return exit == Exit.REQUESTED;
    }

    synchronized boolean releaseNativeStop() {
        if (exit == Exit.REQUESTED && clock.getAsLong() - exitDeadline >= 0) exit = Exit.RELEASE_NATIVE;
        return exit == Exit.RELEASE_NATIVE;
    }

    private synchronized void workerFinished() {
        queued = false;
        if (exit == Exit.REQUESTED) exit = Exit.RELEASE_NATIVE;
    }

    private void closeForExit() {
        boolean damagedCloseAttempted = false;
        try {
            synchronized (this) { if (releaseNativeStop() || cancelled) return; }
            // Retained N1 capability is independent of mutation readiness/terminal state; Runner guards effects.
            if (runner.inspectAdmissionFailure().isPresent() && runner.failedInspectAdmission().isPresent()) {
                damagedCloseAttempted = true;
                runner.close(); return;
            }
            var operation = runner.beginInspectOperation(Command.CLOSE);
            synchronized (this) { active = operation; }
            final Scope work;
            if (releaseNativeStop()) return;
            try { work = host.beginWork(operation); }
            catch (RuntimeException | Error failure) { runner.failInspectAdmission(operation, failure); throw failure; }
            if (releaseNativeStop()) return;
            var cleanup = host.beginCleanup(work, null);
            var completion = host.inScope(cleanup, () -> {
                if (releaseNativeStop()) throw new ScopeTimeoutException();
                Throwable primary = null;
                try { runner.close(); } catch (RuntimeException | Error failure) { primary = failure; }
                if (releaseNativeStop()) throw new ScopeTimeoutException();
                return runner.finishInspectOperation(operation, cleanup, primary);
            });
            synchronized (this) {
                if (active != operation || !completion.cleanupSettled() || completion.command() != Command.CLOSE
                        || !completion.sessionId().equals(operation.sessionId())
                        || !completion.operationId().equals(operation.operationId()) || completion.ordinal() != operation.ordinal())
                    throw new IllegalStateException("Foreign/stale or unsettled inspect CLOSE completion");
                // Expected native DISCONNECT cancels publication. Consume the settled CLOSE, retaining prior last.
                active = null;
            }
        } catch (RuntimeException | Error failure) {
            terminate(failure);
            // CLOSE allocation/BEGIN failure has the same retained N1 authority, never a successor ordinal.
            if (!damagedCloseAttempted && !releaseNativeStop() && runner.inspectAdmissionFailure().isPresent()
                    && runner.failedInspectAdmission().isPresent()) {
                try { runner.close(); }
                catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            }
        }
        finally { workerFinished(); }
    }

    void checkWorld(MinecraftClient client) {
        ScenarioRunner owned;
        synchronized (this) { if (!isReady()) return; owned = runner; }
        try { owned.requireInspectCommandWorld(client); }
        catch (RuntimeException | Error failure) { cancel(failure); }
    }

    void cancel(Throwable failure) {
        synchronized (this) { cancelled = true; ready = false; if (terminal == null) terminal = failure; }
        host.cancelSession(failure);
    }

    private void terminate(Throwable failure) {
        synchronized (this) { ready = false; if (terminal == null) terminal = failure; }
        host.cancelSession(failure);
    }

    private static String identity(Operation operation) {
        return operation.command() + " #" + operation.ordinal() + " " + operation.operationId();
    }

    private static String describe(ScenarioRunner.InspectCompletion completion) {
        return completion.command() + " #" + completion.ordinal() + " " + completion.operationId()
                + " " + completion.verdict() + "; run=" + completion.runId().map(Object::toString).orElse("none")
                + "; evidence=" + completion.evidenceDirectory() + "; complete=" + completion.evidenceComplete()
                + "; cleanup=" + completion.cleanupStatus() + "; summary=" + completion.summaryPath();
    }

    private static void feedback(Consumer<String> reply, String message) {
        try { reply.accept(message); }
        catch (RuntimeException failure) { System.err.println("Inspect feedback unavailable: " + failure); }
    }
}
