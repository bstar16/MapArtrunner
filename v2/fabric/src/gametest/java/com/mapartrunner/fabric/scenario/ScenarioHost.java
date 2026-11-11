package com.mapartrunner.fabric.scenario;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.BiConsumer;

/** Fixture transport only. The runner retains world, player, connection and save authority. */
interface ScenarioHost {
    void runOnClient(Consumer<MinecraftClient> action);
    <V> V computeOnClient(Function<MinecraftClient, V> function);
    void runOnServer(Consumer<MinecraftServer> action);
    <V> V computeOnServer(Function<MinecraftServer, V> function);
    void waitTick();
    void createWorld();
    Path saveDirectory();
    Path takeScreenshot(Path directory, String name);
    void closeWorld();

    enum Command { RESET, RUN, RERUN, STARTUP, CLOSE }
    enum Kind { STARTUP, WORK, CLEANUP }
    enum Verdict { SUCCESS, FAILURE, TIMEOUT }
    enum CleanupStatus { SETTLED, UNRESOLVED }
    record FinalOutcome(Operation operation, Verdict verdict, CleanupStatus cleanup) {
        public FinalOutcome {
            Objects.requireNonNull(operation); Objects.requireNonNull(verdict); Objects.requireNonNull(cleanup);
        }
    }
    final class ScopeTimeoutException extends IllegalStateException {
        ScopeTimeoutException() { super("Inspect scope absolute deadline expired"); }
    }

    record Operation(UUID sessionId, int ordinal, UUID operationId, Command command, Path evidenceDirectory) {
        public Operation {
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(operationId);
            Objects.requireNonNull(command);
            Objects.requireNonNull(evidenceDirectory);
        }
    }

    /** Identity handle issued by an inspect host; value equality never grants dispatch permission. */
    final class Scope {
        private final Operation operation;
        private final Kind kind;
        private final long deadlineNanos;

        Scope(Operation operation, Kind kind, long deadlineNanos) {
            this.operation = Objects.requireNonNull(operation);
            this.kind = Objects.requireNonNull(kind);
            this.deadlineNanos = deadlineNanos;
        }

        public Operation operation() { return operation; }
        public Kind kind() { return kind; }
        public long deadlineNanos() { return deadlineNanos; }
    }

    /**
     * Declaration for the later ordinary host; automatic runs never enter this lifecycle.
     * The issuing host owns scope identity, validity and monotonic expiry. WORK alone permits reset/start;
     * CLEANUP permits finalization. Cleanup settles before a new operation can begin; cancellation invalidates work.
     * inScope binds only the calling worker until it returns. Every transport dispatch must capture that exact
     * scope before waiting and recheck it inside the queued action, never substitute a newer current scope.
     */
    interface Inspect extends ScenarioHost {
        Scope beginStartup(Operation operation);
        void settleStartup(Scope scope);
        Scope beginWork(Operation operation);
        Scope beginCleanup(Scope expiredOrCompleted, Throwable primary);
        void settleCleanup(Scope scope, FinalOutcome outcome);
        void createWorld(BiConsumer<MinecraftClient, Path> beforeCreate);
        Path takeScreenshot(Path directory, String name, Consumer<MinecraftClient> beforeCapture);
        void closeWorld(Consumer<MinecraftClient> beforeClose);
        void awaitNeutralChat();
        void bindStoppingOwner(BiConsumer<MinecraftClient, Scope> owner);
        void requireStoppingScope(Scope cleanup);
        void cancelSession(Throwable primary);
        Scope beginShutdownCleanup(Operation failedAdmission, Throwable primary);
        /**
         * Returns the exact live scope currently bound to this calling worker and issued by this host,
         * only if its operation is expected by identity, its kind is expectedKind, and it remains valid/unexpired.
         * Refuses unbound, foreign, stale, expired or wrong-kind calls without creating, rebinding or renewing a scope.
         * Finalization also compares the returned handle by identity with its supplied cleanup scope.
         */
        Scope requireScope(Operation expected, Kind expectedKind);
        void inScope(Scope scope, Runnable action);
        <V> V inScope(Scope scope, Supplier<V> action);
    }

    static ScenarioHost automatic(ClientGameTestContext context) { return new Automatic(context); }

    final class Automatic implements ScenarioHost {
        private final ClientGameTestContext context;
        // Assignment follows native creation; retain through validation failure and native close, as before.
        private TestSingleplayerContext world;

        private Automatic(ClientGameTestContext context) { this.context = Objects.requireNonNull(context); }

        @Override public void runOnClient(Consumer<MinecraftClient> action) { context.runOnClient(action::accept); }
        @Override public <V> V computeOnClient(Function<MinecraftClient, V> function) { return context.computeOnClient(function::apply); }
        @Override public void runOnServer(Consumer<MinecraftServer> action) { world.getServer().runOnServer(action::accept); }
        @Override public <V> V computeOnServer(Function<MinecraftServer, V> function) { return world.getServer().computeOnServer(function::apply); }
        @Override public void waitTick() { context.waitTick(); }
        @Override public void createWorld() { world = context.worldBuilder().create(); }
        @Override public Path saveDirectory() { return world.getWorldSave().getSaveDirectory(); }
        @Override public Path takeScreenshot(Path directory, String name) {
            return context.takeScreenshot(TestScreenshotOptions.of(name).withDestinationDir(directory).disableCounterPrefix());
        }
        @Override public void closeWorld() { world.close(); }
    }
}
