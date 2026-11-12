package com.mapartrunner.fabric.scenario;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static com.mapartrunner.fabric.scenario.ScenarioHost.*;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** One worker owns startup and mutations. Native callbacks only check authority and enqueue. */
final class ScenarioCommands {
    private final Executor worker;
    private final Inspect host;
    private ScenarioRunner runner;
    // Guarded by this: queued remains true until the matching cleanup has returned settled.
    private boolean queued, ready, cancelled;
    private Throwable terminal;
    private Operation active;
    private ScenarioRunner.InspectCompletion last;

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
                var created = ScenarioRunner.inspect(host, watch);
                synchronized (this) { runner = created; }
                created.initializeInspectWorld(() -> receipts.inspect(created.inspectSessionId().toString()));
                synchronized (this) { if (!cancelled && terminal == null) ready = true; }
            } catch (RuntimeException | Error failure) {
                terminate(failure);
                System.err.println("Inspect startup failed; ordinary client stop requested");
                failure.printStackTrace(System.err);
                stop.run();
            } finally { synchronized (this) { queued = false; } }
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
        if (terminal != null || cancelled) return "Inspect session unavailable; restart required";
        if (queued) return "Inspect is busy through cleanup; use /maparttest last";
        if (!ready) return "Inspect startup is not ready";
        return null;
    }

    private void execute(Command command, Consumer<String> reply) {
        try {
            synchronized (this) { if (cancelled || terminal != null) return; }
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
            synchronized (this) { queued = false; }
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

    synchronized boolean isReady() { return ready && !cancelled && terminal == null; }

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
