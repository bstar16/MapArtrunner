package com.mapartrunner.fabric.scenario;

import com.mapartrunner.fabric.scenario.mixin.InspectKeyboardInvoker;
import com.mapartrunner.fabric.MapArtClient;
import com.mapartrunner.core.run.BuildEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;

import java.util.*;
import java.util.function.*;
import java.time.Instant;
import com.google.gson.JsonObject;

import static com.mapartrunner.fabric.scenario.ScenarioCommands.*;
import static com.mapartrunner.fabric.scenario.ScenarioHost.Command;

/** Fixed fixture-driven game-code input; neither physical keyboard input nor normal-close proof. */
final class InspectAcceptanceDriver {
    private static final long INPUT_NANOS = 30_000_000_000L;
    private static final long IDLE_NANOS = 600_000_000_000L;
    private static final int ENTER = 257;
    private static final Command[] MUTATIONS = {Command.RUN, Command.RESET, Command.RUN, Command.RERUN, Command.RERUN};
    private static final Command[] DISPLACED = {Command.RUN, Command.RESET, Command.RUN, Command.RESET, Command.RUN};
    private static final Command[] RESET_ARCHIVE = {Command.RESET, Command.RUN, Command.RESET, Command.RUN, Command.RESET, Command.RUN, Command.RESET, Command.RUN};
    private enum Stage { WAIT_STARTUP, NEXT_COMMAND, MOVING, OPEN_CHAT, WAIT_HANDOFF, WAIT_COMPLETION, IDLE, IDLE_FINISH_PENDING, STOPPED }

    // This driver alone owns its sequence and synthetic key. No Runner/Host state is changed here.
    private final Supplier<InspectObservation> observations;
    private final ChatBoundary input;
    private final LongSupplier clock;
    private final BooleanSupplier enabled;
    private final Consumer<String> log;
    private final Consumer<PublishedCompletion> idleGuard;
    private final Function<PublishedCompletion, ScenarioRunner.InspectIdleObservation> idleObservation;
    private final Set<UUID> operations = new HashSet<>(), runs = new LinkedHashSet<>();
    private final Set<String> directories = new HashSet<>(), summaries = new HashSet<>();
    private Stage stage = Stage.WAIT_STARTUP;
    private int index, heldKey = -1;
    private Object chat, heldScreen;
    private long inputStarted;
    private PublishedCompletion retained, pending;
    private UUID session;
    private boolean advancing, failed, stopRequested;
    private boolean selected, displaced, resetArchive, moved, middleLast, carryInputDeadline;
    private int resetArchiveLast = -1;
    private int movementCallbacks;
    private Object heldForward;
    private Pose movementStart;
    private Idle idle;

    // Only Driver owns this interval. Origins never renew; its before value is cleared after the final comparison.
    private static final class Idle {
        final String kind;
        final long originNanos;
        final Instant originUtc;
        final IdleIdentity identity;
        final ScenarioRunner.InspectIdleObservation before;
        int callbacks, qualifyingCallbacks;
        Idle(String kind,long originNanos,Instant originUtc,IdleIdentity identity,ScenarioRunner.InspectIdleObservation before) {
            this.kind=kind; this.originNanos=originNanos; this.originUtc=originUtc; this.identity=identity; this.before=before;
        }
    }

    InspectAcceptanceDriver(MinecraftClient client, ScenarioCommands commands) {
        this(commands::observeInspect, new NativeChat(client), System::nanoTime, InspectAcceptanceDriver::enabled,
                message -> System.out.println(message), completion -> commands.requireAcceptanceIdle(client, completion),
                completion -> commands.observeAcceptanceIdle(client, completion));
    }

    /** Only this fixed policy has a simulated boundary in unit tests; it grants no native acceptance. */
    InspectAcceptanceDriver(Supplier<InspectObservation> observations, ChatBoundary input, LongSupplier clock,
                            BooleanSupplier enabled, Consumer<String> log) {
        this(observations, input, clock, enabled, log, completion -> { });
    }

    InspectAcceptanceDriver(Supplier<InspectObservation> observations, ChatBoundary input, LongSupplier clock,
                            BooleanSupplier enabled, Consumer<String> log, Consumer<PublishedCompletion> idleGuard) {
        this(observations,input,clock,enabled,log,idleGuard,completion -> { throw new IllegalStateException("No idle observation boundary"); });
    }

    InspectAcceptanceDriver(Supplier<InspectObservation> observations, ChatBoundary input, LongSupplier clock,
                            BooleanSupplier enabled, Consumer<String> log, Consumer<PublishedCompletion> idleGuard,
                            Function<PublishedCompletion,ScenarioRunner.InspectIdleObservation> idleObservation) {
        this.observations = observations; this.input = input; this.clock = clock; this.enabled = enabled; this.log = log;
        this.idleGuard = idleGuard; this.idleObservation = idleObservation;
    }

    static boolean enabled() {
        return enabled(System.getProperty("mapart.test.inspect"), System.getProperty("mapart.test.inspect.acceptance"),
                System.getProperty("fabric.client.gametest"));
    }
    static boolean enabled(String inspect, String acceptance, String automatic) {
        return Boolean.parseBoolean(inspect) && Boolean.parseBoolean(acceptance) && automatic == null;
    }

    void tick(MinecraftClient current) {
        if (advancing) return;
        if (!enabled.getAsBoolean() && heldForward == null && idle == null && !failed) return;
        boolean validEntry = false;
        advancing = true;
        try {
            input.requireClient(current); validEntry = true;
            require(enabled.getAsBoolean(), "Acceptance gate disabled during driver activity");
            if (!selected) {
                var value = System.getProperty("mapart.test.inspect.acceptance.case", "baseline");
                require(value.equals("baseline") || value.equals("displaced") || value.equals("reset-archive"), "Unknown acceptance case: " + value);
                displaced = value.equals("displaced"); resetArchive = value.equals("reset-archive"); selected = true;
            }
            if(resetArchive) require("reset-archive".equals(System.getProperty("mapart.test.inspect.acceptance.case")),"Reset-archive acceptance case changed");
            if (stage == Stage.STOPPED) { requestStop(current); return; }
            var observed = observations.get();
            require(!observed.restartRequired(), "Inspect session unavailable; restart required");
            if (stage == Stage.WAIT_STARTUP) {
                if (!observed.ready() || observed.busy()) return; // Original startup owner retains its bound.
                require(observed.last().isEmpty(), "Initial last must be absent");
                input.captureBinding();
                marker("ACTIVATED fixture-driven Keyboard callbacks; inspect=true acceptance=true automatic=absent");
                beginInput(observed);
                return;
            }
            input.requireBinding();
            require(observed.ready(), "Inspect session unavailable");
            switch (stage) {
                case NEXT_COMMAND -> beginInput(observed);
                case MOVING -> advanceMovement(observed);
                case IDLE, IDLE_FINISH_PENDING -> advanceIdle(observed);
                case OPEN_CHAT -> {
                    inputDeadline(); requireUnchanged(observed);
                    require(!observed.busy(), "Unexpected admission before Enter");
                    chat = input.screen();
                    if (chat == null) return;
                    input.requireChat(chat, "");
                    // Latch the whole send before any character/Enter callback; exceptions never retry it.
                    stage = Stage.WAIT_HANDOFF;
                    var text = commandText();
                    for (int i = 0; i < text.length(); i++) {
                        inputDeadline(); input.requireChat(chat, text.substring(0, i));
                        input.character(text.charAt(i));
                    }
                    inputDeadline(); input.requireChat(chat, text);
                    pairedKey(ENTER, chat);
                    marker("ENTER delivered; awaiting natural handoff/admission");
                }
                case WAIT_HANDOFF -> {
                    inputDeadline();
                    if (input.screen() != null) { input.requireChat(chat, commandText()); return; }
                    if (isLast()) {
                        requireUnchanged(observed); require(!observed.busy(), "Unexpected LAST admission");
                        marker("LAST submitted; response requires external retained CHAT check");
                        if (resetArchive && index < RESET_ARCHIVE.length) {
                            resetArchiveLast = index; stage = Stage.NEXT_COMMAND; return;
                        }
                        if (displaced && index == 3 && !middleLast) {
                            middleLast = true; stage = Stage.NEXT_COMMAND; return;
                        }
                        marker("SEQUENCE_COMPLETE; published results observed; normal close remains external");
                        stage = Stage.STOPPED; requestStop(current);
                    } else {
                        var completion = nextCompletion(observed);
                        if (observed.busy() || completion != null) {
                            stage = Stage.WAIT_COMPLETION; // No input time is granted to WORK/CLEANUP.
                            acceptSettled(observed, completion);
                        }
                    }
                }
                case WAIT_COMPLETION -> {
                    require(input.screen() == null, "Unexpected completion screen");
                    acceptSettled(observed, nextCompletion(observed));
                }
                default -> throw new IllegalStateException("Unexpected driver stage");
            }
        } catch (RuntimeException | Error failure) {
            fail(failure, validEntry);
            if (validEntry) requestStop(current);
        } finally { advancing = false; }
    }

    private void beginInput(InspectObservation observed) {
        requireUnchanged(observed); require(!observed.busy(), "Unexpected busy before input");
        require(input.screen() == null, "Unexpected input screen");
        if (displaced && index == 2 && !moved) { beginMovement(); return; }
        if (!carryInputDeadline) inputStarted = clock.getAsLong();
        carryInputDeadline = false;
        inputDeadline(); stage = Stage.OPEN_CHAT; chat = null; pending = null;
        marker("OPEN_CHAT"); pairedKey(input.chatKey(), null);
    }

    private void beginMovement() {
        inputStarted = clock.getAsLong(); movementCallbacks = 0;
        idleGuard.accept(retained); input.requireBinding();
        movementStart = input.pose(); requireFinite(movementStart);
        var binding = input.forwardBinding();
        // Acquire debt before the callback, which may apply the key and then throw/reenter.
        heldForward = binding; stage = Stage.MOVING;
        idleGuard.accept(retained); inputDeadline(); input.forward(binding, true);
        marker("FORWARD_PRESS start=" + movementStart);
    }

    private void advanceMovement(InspectObservation observed) {
        inputDeadline(); requireUnchanged(observed); require(!observed.busy(), "Unexpected admission while moving");
        idleGuard.accept(retained); input.requireBinding();
        var now = input.pose(); requireFinite(now); movementCallbacks++;
        double distance = Math.hypot(now.x() - movementStart.x(), now.z() - movementStart.z());
        require(Double.isFinite(distance), "Nonfinite displacement");
        if (distance >= 0.5) {
            releaseForward(); moved = true; carryInputDeadline = true; stage = Stage.NEXT_COMMAND;
            marker("FORWARD_RELEASE distance=" + distance + " callbacks=" + movementCallbacks + " pose=" + now);
        } else require(movementCallbacks < 20, "Forward displacement exhausted 20 END callbacks");
    }

    private static void requireFinite(Pose pose) {
        require(Double.isFinite(pose.x()) && Double.isFinite(pose.z()), "Nonfinite forward pose");
    }

    private void releaseForward() {
        if (heldForward == null) return;
        if (!input.forwardPressed(heldForward)) { heldForward = null; return; }
        Throwable primary = null;
        try {
            require(enabled.getAsBoolean(), "Acceptance gate disabled while forward held");
            idleGuard.accept(retained); input.forward(heldForward, false);
        }
        catch (RuntimeException | Error failure) { primary = failure; }
        try {
            if (input.forwardPressed(heldForward)) input.clearForward(heldForward);
            require(!input.forwardPressed(heldForward), "Forward release remains pressed");
            heldForward = null; // Clear only after the exact captured binding is verified false.
        } catch (RuntimeException | Error release) {
            if (primary != null) primary.addSuppressed(release); else throw release;
        }
        if (primary instanceof RuntimeException failure) throw failure;
        if (primary instanceof Error failure) throw failure;
    }

    /** Used by the existing CLIENT_STOPPING listener even when no later END callback arrives. */
    void releaseOnStopping(MinecraftClient current) {
        if (advancing) return;
        boolean validEntry = false;
        advancing = true;
        try { input.requireClient(current); validEntry = true; releaseForward(); }
        catch (RuntimeException | Error failure) { fail(failure, validEntry); }
        finally { advancing = false; }
    }

    private void inputDeadline() {
        require(clock.getAsLong() - inputStarted < INPUT_NANOS, "Input deadline expired (original 30000ms)");
    }

    private void requireUnchanged(InspectObservation observed) {
        require(Objects.equals(retained, observed.last().orElse(null)), "Retained completion changed");
    }

    private PublishedCompletion nextCompletion(InspectObservation observed) {
        var completion = observed.last().orElse(null);
        if (Objects.equals(completion, retained)) {
            require(pending == null, "Published completion regressed"); return null;
        }
        require(completion != null, "Retained completion changed");
        require(retained == null || completion.ordinal() != retained.ordinal(), "Retained completion changed");
        require(completion.ordinal() == index + 1 && completion.command() == mutations()[index]
                && completion.sessionId() != null && (session == null || session.equals(completion.sessionId()))
                && completion.operationId() != null && !operations.contains(completion.operationId())
                && completion.evidenceDirectory() != null && !completion.evidenceDirectory().isBlank()
                && !directories.contains(completion.evidenceDirectory()) && completion.summaryPath() != null
                && !completion.summaryPath().isBlank() && !summaries.contains(completion.summaryPath())
                && completion.evidenceComplete() && completion.cleanupSettled() && "RELEASED".equals(completion.cleanupStatus()),
                "Invalid completion identity/evidence/cleanup");
        boolean expected;
        if (resetArchive && (index == 0 || index == 4)) {
            expected = "FAIL".equals(completion.verdict()) && completion.runId().isEmpty()
                    && completion.failure().filter(f -> "com.mapartrunner.fabric.scenario.ScenarioRunner$SimulatedInspectResetFailure".equals(f.className())
                    && (index == 0 ? "SIMULATED_INSPECT_RESET_AFTER_RESTORE_BEFORE_READINESS"
                    : "SIMULATED_INSPECT_ARCHIVE_BEFORE_COMMITTED").equals(f.message())).isPresent();
        } else if (resetArchive && (index == 1 || index == 5) || !resetArchive && (index == 0 || displaced && index == 2)) {
            expected = "FAIL".equals(completion.verdict()) && completion.runId().isEmpty()
                    && completion.failure().filter(f -> "java.lang.AssertionError".equals(f.className())
                    && (resetArchive || index == 0 ? "Run requires this invocation's successful reset" : "Start state changed without physics").equals(f.message())).isPresent();
        } else {
            expected = "PASS".equals(completion.verdict()) && completion.failure().isEmpty()
                    && (mutations()[index] == Command.RESET ? completion.runId().isEmpty() : completion.runId().filter(id -> !runs.contains(id)).isPresent());
        }
        require(expected, "Unexpected completion verdict/failure/run");
        require(pending == null || pending.equals(completion), "Published completion changed before settlement");
        pending = completion;
        return completion;
    }

    private void acceptSettled(InspectObservation observed, PublishedCompletion completion) {
        if (completion == null || observed.busy()) return;
        session = completion.sessionId(); retained = completion;
        operations.add(completion.operationId()); directories.add(completion.evidenceDirectory()); summaries.add(completion.summaryPath());
        completion.runId().ifPresent(runs::add);
        marker("RESULT command=" + completion.command() + " ordinal=" + completion.ordinal() + " verdict=" + completion.verdict()
                + " operation=" + completion.operationId() + " run=" + completion.runId().map(Object::toString).orElse("none")
                + " evidence=" + completion.evidenceDirectory() + " summary=" + completion.summaryPath());
        // Retain the exact result through every successor; only this sequence's transient input state clears.
        index++; chat = null; pending = null; stage = Stage.NEXT_COMMAND;
        if (displaced && (index == 3 || index == 5)) beginIdle(observed);
    }

    private void requireIdle(InspectObservation observed) {
        require("displaced".equals(System.getProperty("mapart.test.inspect.acceptance.case")), "Idle acceptance case changed");
        requireUnchanged(observed); require(!observed.busy(), "Unexpected admission during idle");
        require(heldKey == -1 && heldForward == null, "Idle requires no synthetic input debt");
        idleGuard.accept(retained); input.requireBinding(); input.requireNeutral();
    }

    private void beginIdle(InspectObservation observed) {
        requireIdle(observed);
        var before = idleObservation.apply(retained);
        require(index != 5 || before.samples() > 0 && before.witnesses() > 0 && before.bound(),
                "Terminal idle requires retained samples, witnesses and binding");
        idle = new Idle(index == 3 ? "failed" : "terminal",clock.getAsLong(),input.utc(),input.idleIdentity(),before);
        stage = Stage.IDLE; idleMarker("BEGIN",0,null);
    }

    private void advanceIdle(InspectObservation observed) {
        requireIdle(observed);
        long now = clock.getAsLong(), elapsed = now - idle.originNanos;
        require(elapsed >= 0, "Idle monotonic clock regressed");
        idle.callbacks++;
        if (stage == Stage.IDLE) {
            if (elapsed > IDLE_NANOS && idle.callbacks > 6000) {
                idle.qualifyingCallbacks = idle.callbacks;
                // Driver END precedes Watch END: observe only on the following actual callback.
                stage = Stage.IDLE_FINISH_PENDING;
            }
            return;
        }
        require(elapsed > IDLE_NANOS, "Idle monotonic interval regressed before observation");
        var after = idleObservation.apply(retained);
        require(idle.before.equals(after), "Retained observer changed during idle");
        idleMarker("END",now,after);
        idle = null; stage = Stage.NEXT_COMMAND; // Input gets its ordinary origin on a later callback.
    }

    private void idleMarker(String event,long endNanos,ScenarioRunner.InspectIdleObservation after) {
        var json = new JsonObject();
        json.addProperty("schema","mapartrunner.inspect.idle.v1"); json.addProperty("event",event);
        json.addProperty("kind",idle.kind); json.addProperty("mode","inspect"); json.addProperty("acceptanceCase","displaced");
        json.addProperty("nativePid",idle.identity.nativePid()); json.addProperty("nativeStartedUtc",idle.identity.nativeStartedUtc().toString());
        json.addProperty("launchId",idle.identity.launchId().toString()); json.addProperty("sessionId",retained.sessionId().toString());
        json.addProperty("operationId",retained.operationId().toString()); json.addProperty("ordinal",retained.ordinal());
        json.addProperty("command",retained.command().name()); json.addProperty("verdict",retained.verdict());
        json.addProperty("runId",retained.runId().map(UUID::toString).orElse(null));
        json.addProperty("originNanos",idle.originNanos); json.addProperty("originUtc",idle.originUtc.toString());
        json.addProperty("beforeSamples",idle.before.samples()); json.addProperty("beforeWitnesses",idle.before.witnesses());
        json.addProperty("beforeBound",idle.before.bound());
        if (after != null) {
            json.addProperty("endNanos",endNanos); json.addProperty("endUtc",input.utc().toString());
            json.addProperty("elapsedNanos",endNanos-idle.originNanos);
            json.addProperty("qualifyingCallbacks",idle.qualifyingCallbacks); json.addProperty("observationCallbacks",idle.callbacks);
            json.addProperty("afterSamples",after.samples()); json.addProperty("afterWitnesses",after.witnesses());
            json.addProperty("afterBound",after.bound()); json.addProperty("retainedEqual",true);
        }
        var line = "INSPECT_ACCEPTANCE_IDLE " + json;
        require(line.length() <= 4096,"Idle marker exceeds log cap"); log.accept(line);
    }

    private void pairedKey(int code, Object expectedScreen) {
        require(heldKey == -1, "Driver already owns a synthetic key");
        heldKey = code; heldScreen = expectedScreen;
        try { input.key(code, true, expectedScreen); }
        catch (RuntimeException | Error failure) {
            try { releaseKey(); } catch (RuntimeException | Error release) { failure.addSuppressed(release); }
            throw failure;
        }
        releaseKey();
    }

    private void releaseKey() {
        if (heldKey == -1) return;
        int code = heldKey; var screen = heldScreen;
        heldKey = -1; heldScreen = null; // Latch before callback: never release/resend twice after reentrancy or exceptions.
        input.key(code, false, screen);
    }

    private void fail(Throwable failure, boolean validEntry) {
        // A rejected current argument retains debt even if captured-client checks would succeed.
        if (validEntry) try { releaseForward(); } catch (RuntimeException | Error release) { if (release != failure) failure.addSuppressed(release); }
        if (failed) return;
        failed = true;
        if (validEntry) try { releaseKey(); } catch (RuntimeException | Error release) { failure.addSuppressed(release); }
        marker("DRIVER_FAIL " + failure.getClass().getName() + ": " + failure.getMessage());
        idle = null;
        stage = Stage.STOPPED;
    }

    private void requestStop(MinecraftClient current) {
        if (stopRequested) return;
        // Validate this entry before cleanup, not merely the captured client's own cleanup boundary.
        try { input.requireClient(current); } catch (RuntimeException | Error unavailable) { return; }
        if (heldForward != null) {
            try { releaseForward(); } catch (RuntimeException | Error failure) { fail(failure, true); }
            if (heldForward != null) return;
        }
        stopRequested = true;
        try { input.stop(); } catch (RuntimeException | Error failure) { fail(failure, true); }
    }

    private String commandText() {
        return isLast() ? "/maparttest last" : "/maparttest "
                + mutations()[index].name().toLowerCase(Locale.ROOT) + " grounded-two-lane";
    }

    private Command[] mutations() { return resetArchive ? RESET_ARCHIVE : displaced ? DISPLACED : MUTATIONS; }
    private boolean isLast() { return index == mutations().length || displaced && index == 3 && !middleLast
            || resetArchive && (index == 1 || index == 4 || index == 5) && resetArchiveLast != index; }

    private void marker(String detail) {
        var message = ("[mapart-inspect-acceptance] stage=" + stage + " command=" + commandText()
                + " elapsedInputMs=" + (stage == Stage.WAIT_STARTUP ? 0 : (clock.getAsLong() - inputStarted) / 1_000_000)
                + " session=" + session + " lastOperation=" + (retained == null ? "none" : retained.operationId())
                + " runs=" + runs + " " + detail).replace('\n', ' ').replace('\r', ' ');
        log.accept(message.length() <= 4096 ? message : message.substring(0, 4096));
    }

    /** Narrow native boundary for this sequence, never an arbitrary command/input service. */
    interface ChatBoundary {
        void requireClient(MinecraftClient current);
        void captureBinding();
        void requireBinding();
        Object screen();
        void requireChat(Object expected, String draft);
        int chatKey();
        void key(int code, boolean press, Object expectedScreen);
        void character(int codepoint);
        void stop();
        Object forwardBinding();
        Pose pose();
        boolean forwardPressed(Object binding);
        void forward(Object binding, boolean press);
        void clearForward(Object binding);
        void requireNeutral();
        Instant utc();
        IdleIdentity idleIdentity();
    }

    record Pose(double x, double z) { }
    record IdleIdentity(long nativePid,Instant nativeStartedUtc,UUID launchId) { }

    static void requireForwardKeys(KeyBinding binding, KeyBinding[] allKeys, List<KeyBinding> controls,
                                   BuildEngine.ControlOwner owner) {
        require(binding.isDefault() && binding.getDefaultKey().getCategory() == InputUtil.Type.KEYSYM,
                "Unsupported forward binding");
        require(controls.stream().noneMatch(KeyBinding::isPressed) && owner == BuildEngine.ControlOwner.NONE,
                "Forward input requires neutral keys/control owner");
        var key = new KeyInput(binding.getDefaultKey().getCode(), 0, 0);
        require(Arrays.stream(allKeys).noneMatch(other -> other != binding && other.matchesKey(key)), "Forward binding collision");
    }

    private static final class NativeChat implements ChatBoundary {
        private final MinecraftClient client;
        private Object world, server, player, handler, connection, window;
        private long handle;
        NativeChat(MinecraftClient client) { this.client = client; }
        @Override public void requireClient(MinecraftClient current) {
            require(current == client && MinecraftClient.getInstance() == client && client.isOnThread(), "Wrong installed client/thread");
        }
        @Override public void captureBinding() {
            require(client.world != null && client.getServer() != null && client.player != null
                    && client.getNetworkHandler() != null, "Incomplete startup world binding");
            world = client.world; server = client.getServer(); player = client.player; handler = client.getNetworkHandler();
            connection = client.getNetworkHandler().getConnection(); window = client.getWindow(); handle = client.getWindow().getHandle();
            requireBinding(); require(client.currentScreen == null, "Unexpected startup screen");
            var mode = "[mapart-inspect-acceptance] NATIVE_MODE pid=" + ProcessHandle.current().pid()
                    + " java.version=" + System.getProperty("java.version")
                    + " inspect=" + System.getProperty("mapart.test.inspect")
                    + " acceptance=" + System.getProperty("mapart.test.inspect.acceptance")
                    + " automaticPropertyPresent=" + (System.getProperty("fabric.client.gametest") != null);
            System.out.println(mode.substring(0, Math.min(mode.length(), 4096)));
        }
        @Override public void requireBinding() {
            require(enabled() && client.isOnThread() && MinecraftClient.getInstance() == client && client.isRunning(), "Input gate/client/thread unavailable");
            require(window != null && client.getWindow() == window && handle != 0 && client.getWindow().getHandle() == handle, "Window replaced");
            require(world != null && client.world == world && client.getServer() == server && client.player == player
                    && client.getNetworkHandler() == handler && client.getNetworkHandler().getConnection() == connection
                    && client.getNetworkHandler().getConnection().isOpen(), "Native world/server/player/handler/connection replaced");
            require(!client.isPaused() && client.isWindowFocused() && client.getOverlay() == null, "Unexpected pause/focus/overlay");
        }
        @Override public Object screen() { return client.currentScreen; }
        @Override public void requireChat(Object expected, String draft) {
            requireBinding();
            require(client.currentScreen == expected && expected != null && expected.getClass() == ChatScreen.class, "Unexpected chat screen");
            require(((ChatScreen) expected).getFocused() instanceof TextFieldWidget field && field.isFocused()
                    && field.getText().equals(draft), "Unexpected chat focused field/draft");
        }
        @Override public int chatKey() {
            requireBinding();
            var binding = client.options.chatKey;
            require(binding.isDefault() && binding.getDefaultKey().getCategory() == InputUtil.Type.KEYSYM
                    && !binding.isPressed(), "Unsupported/pressed chat binding");
            return binding.getDefaultKey().getCode();
        }
        @Override public void key(int code, boolean press, Object expectedScreen) {
            requireBinding();
            require(client.currentScreen == expectedScreen || !press && code == ENTER && client.currentScreen == null,
                    "Unexpected screen before paired key callback");
            ((InspectKeyboardInvoker) client.keyboard).mapart$onKey(handle,
                    press ? InputUtil.GLFW_PRESS : InputUtil.GLFW_RELEASE, new KeyInput(code, 0, 0));
        }
        @Override public void character(int codepoint) {
            requireBinding();
            require(client.currentScreen != null && client.currentScreen.getClass() == ChatScreen.class, "Unexpected character screen");
            ((InspectKeyboardInvoker) client.keyboard).mapart$onChar(handle, new CharInput(codepoint, 0));
        }
        @Override public void stop() { requireClient(client); client.scheduleStop(); }
        @Override public Object forwardBinding() {
            requireBinding(); require(client.currentScreen == null, "Unexpected movement screen");
            var o = client.options; var binding = o.forwardKey;
            requireForwardKeys(binding, o.allKeys,
                    List.of(o.forwardKey,o.backKey,o.leftKey,o.rightKey,o.jumpKey,o.sneakKey,o.sprintKey,o.useKey,o.attackKey),
                    MapArtClient.status().controlOwner());
            return binding;
        }
        @Override public Pose pose() {
            requireBinding(); require(client.currentScreen == null, "Unexpected movement screen");
            return new Pose(client.player.getX(), client.player.getZ());
        }
        @Override public boolean forwardPressed(Object binding) { requireClient(client); return ((KeyBinding) binding).isPressed(); }
        @Override public void forward(Object binding, boolean press) {
            requireBinding(); require(binding == client.options.forwardKey, "Forward binding replaced");
            key(((KeyBinding) binding).getDefaultKey().getCode(), press, null);
        }
        @Override public void clearForward(Object binding) {
            requireClient(client); ((KeyBinding) binding).setPressed(false);
        }
        @Override public void requireNeutral() {
            requireBinding(); require(client.currentScreen == null,"Unexpected idle screen");
            var o = client.options;
            require(List.of(o.forwardKey,o.backKey,o.leftKey,o.rightKey,o.jumpKey,o.sneakKey,o.sprintKey,o.useKey,o.attackKey)
                    .stream().noneMatch(KeyBinding::isPressed) && MapArtClient.status().controlOwner() == BuildEngine.ControlOwner.NONE,
                    "Idle requires neutral keys/control owner");
        }
        @Override public Instant utc() { return Instant.now(); }
        @Override public IdleIdentity idleIdentity() {
            var process = ProcessHandle.current();
            return new IdleIdentity(process.pid(),process.info().startInstant().orElseThrow(),UUID.fromString(System.getenv("MAPART_TEST_LAUNCH_ID")));
        }
    }

    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
}
