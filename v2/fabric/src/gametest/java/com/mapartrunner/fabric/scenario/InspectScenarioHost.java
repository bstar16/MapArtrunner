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
    private Scope current;
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
                current = null; changed.signalAll();
            } finally { settling = false; }
        } finally { lock.unlock(); }
    }
    @Override public void cancelSession(Throwable primary) {
        var cause = primary == null ? new IllegalStateException("Inspect session cancelled") : primary;
        cancellationFailure.compareAndSet(null, cause);
        retain(cause); cancelled = true;
        // Native callbacks never wait for phase filesystem IO; worker condition waits already poll within 50ms.
        if (lock.tryLock()) try { changed.signalAll(); } finally { lock.unlock(); }
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
        queue.accept(() -> {
            lock.lock();
            try {
                valid(scope); if (stopped) throwOriginal();
                result.complete(action.get());
            } catch (RuntimeException | Error error) { result.completeExceptionally(error); }
            finally { changed.signalAll(); lock.unlock(); }
        });
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

    @Override public void createWorld() { throw new IllegalStateException("Inspect creation requires Runner prospective-save guard"); }
    @Override public void createWorld(BiConsumer<MinecraftClient, Path> beforeCreate) {
        var scope = dispatchScope(); check(scope.kind() == Kind.STARTUP, "Creation requires STARTUP"); Objects.requireNonNull(beforeCreate);
        runOnClient(c -> {
            check(c.world==null && c.getServer()==null && c.player==null,"Creation cannot open over a foreign world");
            beforeCreate.accept(c,c.getLevelStorage().getSavesDirectory().resolve("mapart-inspect-"+scope.operation().sessionId()).toAbsolutePath().normalize());
            valid(scope);
            CreateWorldScreen.show(c, () -> cancelSession(new IllegalStateException("World creation cancelled")));
        });
        CreateWorldScreen screen;
        while ((screen = computeOnClient(c -> c.currentScreen instanceof CreateWorldScreen s ? s : null)) == null) waitTick();
        var actualScreen = screen;
        runOnClient(c -> {
            check(c.currentScreen == actualScreen, "Creation screen replaced");
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
            var buttons = new ArrayList<ButtonWidget>();
            discover(actualScreen, Collections.newSetFromMap(new IdentityHashMap<>()), buttons);
            String create = Text.translatable("selectWorld.create").getString();
            var matches = buttons.stream().filter(b -> b.getMessage().getString().equals(create)).toList();
            check(matches.size() == 1 && matches.getFirst().active && matches.getFirst().visible, "Expected one active visible create button");
            valid(scope);
            check(c.currentScreen == actualScreen && actualScreen.getWorldCreator() == creator
                    && c.getLevelStorage().getSavesDirectory().resolve(creator.getWorldDirectoryName()).toAbsolutePath().normalize().equals(prospective),
                    "Creation screen/creator/save changed");
            beforeCreate.accept(c, prospective); // Runner authority; no wait or native action before the press.
            matches.getFirst().onPress(new MouseInput(0, 0));
        });
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
            },() -> c.world.disconnect(Text.translatable("menu.savingLevel")),() -> c.disconnect(new TitleScreen(), false));
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
            runOnClient(c -> { beforeCapture.accept(c); valid(scope); capture.accept(c,image -> receiveImage(scope,image,result)); });
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
