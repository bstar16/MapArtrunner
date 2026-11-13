package com.mapartrunner.fabric.scenario;

import com.mapartrunner.fabric.scenario.mixin.InspectKeyboardInvoker;
import com.mapartrunner.fabric.MapArtClient;
import com.mapartrunner.core.run.BuildEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.Widget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.GameMode;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.block.BlockState;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;

import java.util.*;
import java.util.function.*;
import java.time.Instant;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonNull;
import net.minecraft.nbt.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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
    private static final Command[] FOREIGN = {Command.RESET, Command.RUN, Command.RERUN};
    private static final Command[] WORK_EXPIRY = {Command.RESET, Command.RUN, Command.RESET};
    private static final Command[] STUCK_CLEANUP = {Command.RESET};
    private enum Stage { WAIT_STARTUP, NEXT_COMMAND, MOVING, OPEN_CHAT, WAIT_HANDOFF, WAIT_COMPLETION, IDLE, IDLE_FINISH_PENDING, FOREIGN, STOPPED }

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
    private boolean selected, displaced, resetArchive, foreignCase, workExpiry, stuckCleanup, moved, middleLast, carryInputDeadline;
    private int resetArchiveLast = -1;
    private int movementCallbacks;
    private Object heldForward;
    private Pose movementStart;
    private Idle idle;
    private ForeignClaims foreignClaims;
    private Foreign foreign;
    private ExpiryClaims expiryClaims;
    private Expiry expiry;
    private String n1Case;
    private enum N1Step { WAIT_ADMISSION, NEXT_CHAT, OPEN_CHAT, WAIT_HANDOFF, STOP_READY }
    private N1Step n1Step;
    private int n1Chat;
    private ScenarioRunner.N1Observation n1Proof;
    private static final String[] N1_CHATS={"/maparttest reset grounded-two-lane","/maparttest run grounded-two-lane",
            "/maparttest rerun grounded-two-lane","/maparttest last"};
    private Supplier<ScenarioRunner.N1Observation> n1Observations = () -> null;
    // Keep RUN2's immutable result/proof after RESET3 replaces LAST. Only transient input/ack state retires.
    private PublishedCompletion expiryResult;
    private ExpiryObservation expiryProof;
    private boolean stopping;
    private Throwable driverFailure;

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
        this(commands::observeInspect, new NativeChat(client,commands::observeForeignAcceptance), System::nanoTime, InspectAcceptanceDriver::enabled,
                message -> System.out.println(message), completion -> commands.requireAcceptanceIdle(client, completion),
                completion -> commands.observeAcceptanceIdle(client, completion));
        n1Observations=commands::observeN1Acceptance;
        foreignClaims=new ForeignClaims() {
            public ForeignObservation observe() { return commands.observeForeignAcceptance(); }
            public boolean release(MinecraftClient current) { return commands.releaseForeignAcceptanceBoundary(current); }
            public boolean replay(MinecraftClient current,UUID operation,long deadline) { return commands.acknowledgeForeignReplayMeasurement(current,operation,deadline); }
            public boolean seal(MinecraftClient current,ForeignProofAck ack) { return commands.acknowledgeForeignFinalProof(current,ack); }
        };
        expiryClaims=new ExpiryClaims() {
            public ExpiryObservation observe() { return commands.observeExpiryAcceptance(); }
            public boolean acknowledge(MinecraftClient current,ExpiryBusyAck ack) { return commands.acknowledgeExpiryBusyProof(current,ack); }
        };
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

    InspectAcceptanceDriver(Supplier<InspectObservation> observations,ChatBoundary input,LongSupplier clock,
                            BooleanSupplier enabled,Consumer<String> log,ForeignClaims foreignClaims) {
        this(observations,input,clock,enabled,log); this.foreignClaims=foreignClaims;
    }

    InspectAcceptanceDriver(Supplier<InspectObservation> observations,ChatBoundary input,LongSupplier clock,
                            BooleanSupplier enabled,Consumer<String> log,ExpiryClaims expiryClaims) {
        this(observations,input,clock,enabled,log); this.expiryClaims=expiryClaims;
    }

    InspectAcceptanceDriver(Supplier<InspectObservation> observations, ChatBoundary input, LongSupplier clock,
                            BooleanSupplier enabled, Consumer<String> log, Consumer<PublishedCompletion> idleGuard,
                            Function<PublishedCompletion,ScenarioRunner.InspectIdleObservation> idleObservation,
                            Supplier<ScenarioRunner.N1Observation> n1Observations) {
        this(observations,input,clock,enabled,log,idleGuard,idleObservation);
        this.n1Observations=n1Observations;
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
        if (stopping && (workExpiry || stuckCleanup || n1Case!=null)) return;
        if (!enabled.getAsBoolean() && heldForward == null && idle == null && foreign==null && !workExpiry && !stuckCleanup && n1Case==null && !failed) return;
        boolean validEntry = false;
        advancing = true;
        try {
            input.requireClient(current); validEntry = true;
            require(enabled.getAsBoolean(), "Acceptance gate disabled during driver activity");
            if (!selected) {
                var value = System.getProperty("mapart.test.inspect.acceptance.case", "baseline");
                require(Set.of("baseline","displaced","reset-archive","foreign-world","work-expiry","stuck-cleanup","n1-header","n1-post-link").contains(value), "Unknown acceptance case: " + value);
                displaced = value.equals("displaced"); resetArchive = value.equals("reset-archive"); foreignCase=value.equals("foreign-world"); workExpiry=value.equals("work-expiry"); stuckCleanup=value.equals("stuck-cleanup"); n1Case=value.startsWith("n1-")?value:null; selected = true;
            }
            if(resetArchive) require("reset-archive".equals(System.getProperty("mapart.test.inspect.acceptance.case")),"Reset-archive acceptance case changed");
            if(workExpiry) require("work-expiry".equals(System.getProperty("mapart.test.inspect.acceptance.case")),"Expiry acceptance case changed");
            if(n1Case!=null) requireN1Case();
            if(stuckCleanup) requireStuckCase();
            if (stage == Stage.STOPPED) { requestStop(current); return; }
            var observed = observations.get();
            if(foreignCase) {
                require("foreign-world".equals(System.getProperty("mapart.test.inspect.acceptance.case")),"Foreign acceptance case changed");
                if(foreign!=null) { foreign.tick(current,observed); return; }
                if(index==2 && (stage==Stage.WAIT_HANDOFF || stage==Stage.WAIT_COMPLETION) && foreignClaims!=null) {
                    var held=foreignClaims.observe();
                    if(held!=null && held.queueEntered()) {
                        require(input.screen()==null && held.ordinal()==3 && Objects.equals(session,held.sessionId())
                                && held.operationId()!=null && !operations.contains(held.operationId())
                                && Set.of("HELD","CANCELLED_HOLD").contains(held.stage()) && !held.boundaryRetired()
                                && clock.getAsLong()-held.workDeadlineNanos()<0,"Foreign original RERUN3 boundary invalid");
                        input.requireBinding(); requireUnchanged(observed);
                        require(retained!=null && retained.ordinal()==2 && retained.command()==Command.RUN && retained.runId().isPresent(),"Foreign requires retained RUN2");
                        foreign=new Foreign(held); stage=Stage.FOREIGN; foreign.tick(current,observed); return;
                    }
                }
            }
            // Only the sent RESET3 role can enter this bounded failed-admission program.
            if(n1Step!=null) { advanceN1(current,observed); return; }
            require(!observed.restartRequired(), "Inspect session unavailable; restart required");
            if (stage == Stage.WAIT_STARTUP) {
                if (!observed.ready() || observed.busy()) return; // Original startup owner retains its bound.
                require(observed.last().isEmpty(), "Initial last must be absent");
                input.captureBinding();
                marker("ACTIVATED fixture-driven Keyboard callbacks; inspect=true acceptance=true automatic=absent");
                beginInput(current,observed);
                return;
            }
            input.requireBinding();
            require(observed.ready(), "Inspect session unavailable");
            if(workExpiry && index==1 && (stage==Stage.WAIT_HANDOFF || stage==Stage.WAIT_COMPLETION)) {
                if(expiry==null && input.screen()==null && observed.busy()) {
                    inputDeadline(); // RUN2 still owes its ordinary natural handoff before the long WORK wait.
                    requireUnchanged(observed);
                    require(expiryClaims!=null && retained!=null && retained.ordinal()==1 && retained.command()==Command.RESET,"Expiry requires RESET1/owner boundary");
                    expiry=new Expiry(); stage=Stage.WAIT_COMPLETION;
                    marker("EXPIRY_WAIT original RUN2 handoff complete; no input allowance for WORK");
                }
                if(expiry!=null) { expiry.tick(current,observed); return; }
            }
            switch (stage) {
                case NEXT_COMMAND -> beginInput(current,observed);
                case MOVING -> advanceMovement(observed);
                case IDLE, IDLE_FINISH_PENDING -> advanceIdle(observed);
                case OPEN_CHAT -> {
                    if(stuckCleanup) requireStuckInput(current);
                    inputDeadline(); requireUnchanged(observed);
                    require(!observed.busy(), "Unexpected admission before Enter");
                    chat = input.screen();
                    if (chat == null) return;
                    input.requireChat(chat, "");
                    // Latch the whole send before any character/Enter callback; exceptions never retry it.
                    stage = Stage.WAIT_HANDOFF;
                    if(n1Case!=null && index==2) n1Step=N1Step.WAIT_ADMISSION;
                    var text = commandText();
                    for (int i = 0; i < text.length(); i++) {
                        if(n1Case!=null) requireN1PreludeInput();
                        if(stuckCleanup) requireStuckInput(current);
                        inputDeadline(); input.requireChat(chat, text.substring(0, i));
                        input.character(text.charAt(i));
                    }
                    if(n1Case!=null) requireN1PreludeInput();
                    if(stuckCleanup) requireStuckInput(current);
                    inputDeadline(); input.requireChat(chat, text);
                    pairedKey(ENTER, chat);
                    marker("ENTER delivered; awaiting natural handoff/admission");
                }
                case WAIT_HANDOFF -> {
                    inputDeadline();
                    if(stuckCleanup && isLast()) requireStuckInput(current);
                    if (input.screen() != null) { input.requireChat(chat, commandText()); return; }
                    if(stuckCleanup) input.requireNeutral();
                    if (isLast()) {
                        requireUnchanged(observed); require(!observed.busy(), "Unexpected LAST admission");
                        marker("LAST submitted; response requires external retained CHAT check");
                        if (resetArchive && index < RESET_ARCHIVE.length) {
                            resetArchiveLast = index; stage = Stage.NEXT_COMMAND; return;
                        }
                        if (displaced && index == 3 && !middleLast) {
                            middleLast = true; stage = Stage.NEXT_COMMAND; return;
                        }
                        if(!stuckCleanup) marker("SEQUENCE_COMPLETE; published results observed; normal close remains external");
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

    private void requireN1Case() {
        require(enabled.getAsBoolean() && n1Case.equals(System.getProperty("mapart.test.inspect.acceptance.case")),"N1 gate/case changed");
    }

    private void requireStuckCase() {
        require(enabled.getAsBoolean() && "stuck-cleanup".equals(System.getProperty("mapart.test.inspect.acceptance.case")),"Stuck-cleanup gate/case changed");
    }

    /** RESET1 stays absent until settled, then its exact value survives LAST and the stop request. */
    private void requireStuckInput(MinecraftClient current) {
        requireStuckCase(); input.requireClient(current); input.requireBinding();
        var observed=observations.get(); requireUnchanged(observed);
        require(observed.ready() && !observed.busy() && !observed.restartRequired(),"Stuck-cleanup input unavailable");
        require(heldKey==-1 && heldForward==null,"Stuck-cleanup input debt remains");
        if(retained!=null) idleGuard.accept(retained);
    }

    private void requireN1PreludeInput() {
        requireN1Case(); input.requireBinding();
        var observed=observations.get(); requireUnchanged(observed);
        require(observed.ready() && !observed.restartRequired() && !observed.busy(),"N1 prelude input unavailable");
    }

    /** The first immutable admission value survives all four refused chats; it grants no owner authority. */
    private void authenticateN1(ScenarioRunner.N1Observation proof) {
        require(proof!=null && "ADMISSION_FAILED".equals(proof.state()) && proof.admission()!=null
                && proof.intent()==null && proof.close()==null && !proof.outputAttempted() && proof.outputSucceeded()==null,"N1 admission envelope invalid");
        var a=proof.admission(); var t=a.trigger(); var h=a.host(); var p=a.prior(); var f=a.failure();
        require(retained!=null && retained.ordinal()==2 && retained.command()==Command.RUN && "PASS".equals(retained.verdict())
                && retained.runId().isPresent() && retained.failure().isEmpty() && retained.evidenceComplete() && retained.cleanupSettled()
                && "RELEASED".equals(retained.cleanupStatus()),"N1 requires successful retained RUN2");
        require(n1Case.equals(a.selected()) && Objects.equals(session,a.sessionId()) && a.operationId()!=null
                && !operations.contains(a.operationId()) && a.operationDirectory()!=null && !a.operationDirectory().isBlank()
                && ScenarioRunner.n1Text(a.operationDirectory(),1024)!=null && !directories.contains(a.operationDirectory())
                && a.reservedOrdinal()==3 && a.resetOrdinal()==1 && a.countedStarts()==1,"N1 admission identity/counters invalid");
        boolean header=n1Case.equals("n1-header");
        require(t!=null && t.attempts()==1 && t.headerWriteEntered() && t.collisionCreated()==header && t.afterLinkReached()!=header
                && h!=null && !h.ordinaryScopeIssued() && h.currentScopeAbsent() && h.cancelled() && h.lastOrdinal()==2,"N1 trigger/Host invalid");
        require(p!=null && retained.operationId().equals(p.operationId()) && retained.runId().orElseThrow().equals(p.runId())
                && p.summarySha256()!=null && p.summarySha256().matches("[0-9a-fA-F]{64}") && p.closedFilesVerified() && p.lastSame()
                && p.watchSame() && p.samples()>0 && p.witnesses()>0,"N1 prior evidence invalid");
        require(a.diagnosticsComplete() && a.primaryIdentityRetained() && f!=null,"N1 diagnostics/primary invalid");
        ScenarioRunner.n1Text(f.className(),256); ScenarioRunner.n1Text(f.message(),512);
        ScenarioRunner.n1Text(f.causeClassName(),256); ScenarioRunner.n1Text(f.causeMessage(),512);
        require(header ? "java.io.UncheckedIOException".equals(f.className()) && f.causeClassName()!=null && !f.causeClassName().isBlank()
                : "com.mapartrunner.fabric.scenario.ScenarioLaunchReceipt$SimulatedN1PostLinkFailure".equals(f.className())
                && "SIMULATED_N1_AFTER_BEGIN_LINK_BEFORE_RETURN".equals(f.message()) && f.causeClassName()==null && f.causeMessage()==null,
                "N1 primary failure invalid");
        require(n1Proof==null || n1Proof.equals(proof),"N1 authenticated admission changed");
        if(n1Proof==null) n1Proof=proof;
    }

    private void requireN1Terminal(InspectObservation observed) {
        requireN1Case(); input.requireBinding(); requireUnchanged(observed);
        authenticateN1(n1Observations.get());
        require(!observed.ready() && observed.restartRequired() && !observed.busy(),"N1 terminal worker not settled");
        require(heldKey==-1 && heldForward==null,"N1 input debt remains");
    }

    private void requireN1ChatBoundary(String draft) {
        inputDeadline(); requireN1Terminal(observations.get()); input.requireChat(chat,draft);
    }

    private void advanceN1(MinecraftClient current,InspectObservation observed) {
        require(index==2 && stage==Stage.WAIT_HANDOFF,"N1 failed RESET3 role changed");
        if(n1Step==N1Step.WAIT_ADMISSION) {
            inputDeadline(); requireN1Case(); input.requireBinding(); requireUnchanged(observed);
            if(input.screen()!=null) input.requireChat(chat,commandText()); else input.requireNeutral();
            var proof=n1Observations.get();
            if(proof!=null && "ADMISSION_FAILED".equals(proof.state()) || observed.restartRequired() || n1Proof!=null) authenticateN1(proof);
            // Commands and Runner publish separately: a matching snapshot while queued is not settlement.
            if(observed.busy() || !observed.restartRequired()) return;
            requireN1Terminal(observed);
            if(input.screen()!=null) return;
            marker("N1_RESET3_HANDOFF authenticated failed admission; no third completion");
            n1Step=N1Step.NEXT_CHAT; chat=null; return;
        }
        requireN1Terminal(observed);
        switch(n1Step) {
            case NEXT_CHAT -> {
                input.requireNeutral(); inputStarted=clock.getAsLong(); inputDeadline();
                chat=null; n1Step=N1Step.OPEN_CHAT;
                marker("N1_CHAT_OPEN request="+n1Chat); pairedKey(input.chatKey(),null);
            }
            case OPEN_CHAT -> {
                inputDeadline(); chat=input.screen(); if(chat==null) return;
                input.requireChat(chat,"");
                // Consume send before callbacks, including apply-then-throw and recursive END/STOP.
                n1Step=N1Step.WAIT_HANDOFF;
                var text=commandText();
                for(int i=0;i<text.length();i++) { requireN1ChatBoundary(text.substring(0,i)); input.character(text.charAt(i)); }
                requireN1ChatBoundary(text); pairedKey(ENTER,chat);
                marker("N1_CHAT_REQUESTED request="+n1Chat+"; actual retained CHAT remains external");
            }
            case WAIT_HANDOFF -> {
                inputDeadline();
                if(input.screen()!=null) { input.requireChat(chat,commandText()); return; }
                input.requireNeutral();
                marker("N1_CHAT_HANDOFF request="+n1Chat+"; no output acknowledgement");
                chat=null; n1Chat++; n1Step=n1Chat==N1_CHATS.length?N1Step.STOP_READY:N1Step.NEXT_CHAT;
            }
            case STOP_READY -> {
                input.requireNeutral(); marker("N1_SEQUENCE_COMPLETE application stop requested; native proof remains external");
                stage=Stage.STOPPED; requestStop(current);
            }
            default -> throw new IllegalStateException("Unexpected N1 step");
        }
    }

    private void beginInput(MinecraftClient current,InspectObservation observed) {
        if(stuckCleanup) { requireStuckInput(current); input.requireNeutral(); }
        requireUnchanged(observed); require(!observed.busy(), "Unexpected busy before input");
        require(input.screen() == null, "Unexpected input screen");
        if(workExpiry && index>=2) require(expiryResult!=null && expiryResultMatches(expiryResult),"Expiry failed RUN2 result/proof not retained");
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
        try {
            input.requireClient(current); validEntry = true; stopping=true;
            if(foreign!=null) foreign.dispose();
            if(n1Case!=null) { n1Step=null; chat=null; stage=Stage.STOPPED; releaseKey(); }
            if(workExpiry) { if(expiry!=null) expiry.dispose(); stage=Stage.STOPPED; releaseKey(); }
            if(stuckCleanup || !selected && "stuck-cleanup".equals(System.getProperty("mapart.test.inspect.acceptance.case"))) {
                stuckCleanup=true; chat=null; pending=null; stage=Stage.STOPPED; releaseKey();
            }
            releaseForward();
        }
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
        if(stuckCleanup) {
            expected="PASS".equals(completion.verdict()) && completion.failure().isEmpty() && completion.runId().isEmpty();
        } else if(n1Case!=null) {
            expected="PASS".equals(completion.verdict()) && completion.failure().isEmpty()
                    && (index==0?completion.runId().isEmpty():completion.runId().filter(id -> !runs.contains(id)).isPresent());
        } else if (workExpiry) {
            expected = index==1 ? expiryResultMatches(completion)
                    : "PASS".equals(completion.verdict()) && completion.runId().isEmpty() && completion.failure().isEmpty();
        } else if (resetArchive && (index == 0 || index == 4)) {
            expected = "FAIL".equals(completion.verdict()) && completion.runId().isEmpty()
                    && completion.failure().filter(f -> "com.mapartrunner.fabric.scenario.ScenarioRunner$SimulatedInspectResetFailure".equals(f.className())
                    && (index == 0 ? "SIMULATED_INSPECT_RESET_AFTER_RESTORE_BEFORE_READINESS"
                    : "SIMULATED_INSPECT_ARCHIVE_BEFORE_COMMITTED").equals(f.message())).isPresent();
        } else if (!foreignCase && (resetArchive && (index == 1 || index == 5) || !resetArchive && (index == 0 || displaced && index == 2))) {
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

    private boolean expiryResultMatches(PublishedCompletion completion) {
        return expiryProof!=null && expiryProof.stage().equals("SEALED_BEFORE_END") && expiryProof.acknowledgement()!=null
                && Objects.equals(completion.sessionId(),expiryProof.sessionId())
                && Objects.equals(completion.operationId(),expiryProof.operationId())
                && completion.runId().filter(expiryProof.runId()::equals).isPresent()
                && "FAIL".equals(completion.verdict()) && completion.failure().filter(InspectAcceptanceDriver::expiryTimeout).isPresent();
    }

    private void acceptSettled(InspectObservation observed, PublishedCompletion completion) {
        if (completion == null || observed.busy()) return;
        if(workExpiry && index==1) {
            expiryResult=completion;
            marker("EXPIRY_RETAINED failed RUN2 operation="+completion.operationId()+" run="+completion.runId().orElseThrow()
                    +" evidence="+completion.evidenceDirectory()+" summary="+completion.summaryPath()+" proof=work-expiry-proof.json; retained phase/CHAT audits required");
            expiry.dispose(); expiry=null;
        }
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
        if (failed) { if(driverFailure!=null && driverFailure!=failure) driverFailure.addSuppressed(failure); return; }
        driverFailure=failure;
        failed = true;
        if(expiry!=null) expiry.dispose();
        if(foreign!=null) try { foreign.dispose(); }
        catch(RuntimeException | Error secondary) { if(secondary!=failure) failure.addSuppressed(secondary); }
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
        if(stuckCleanup && !failed) {
            requireStuckInput(current); input.requireNeutral();
            require(index==1 && retained!=null,"Stuck-cleanup requires settled RESET1 and LAST handoff");
        }
        stopRequested = true;
        try {
            if(stuckCleanup && !failed) marker("STUCK_CLEANUP_EXIT_REQUESTED RESET1 retained; LAST submitted; negative native outcome remains external");
            input.stop();
        } catch (RuntimeException | Error failure) { fail(failure, true); }
    }

    private String commandText() {
        if(n1Step!=null && n1Step!=N1Step.WAIT_ADMISSION) return N1_CHATS[Math.min(n1Chat,N1_CHATS.length-1)];
        return isLast() ? "/maparttest last" : "/maparttest "
                + mutations()[index].name().toLowerCase(Locale.ROOT) + " grounded-two-lane";
    }

    private Command[] mutations() { return stuckCleanup ? STUCK_CLEANUP : n1Case!=null ? WORK_EXPIRY : workExpiry ? WORK_EXPIRY : foreignCase ? FOREIGN : resetArchive ? RESET_ARCHIVE : displaced ? DISPLACED : MUTATIONS; }
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
        default void exitForeign() { throw new IllegalStateException("No foreign native exit boundary"); }
        default boolean createForeign(Runnable revalidate,UUID session) { throw new IllegalStateException("No foreign native creation boundary"); }
        default boolean bindForeign() { throw new IllegalStateException("No foreign native binding boundary"); }
        default boolean settleForeign() { throw new IllegalStateException("No foreign native settling boundary"); }
        default boolean foreignClockAdvanced(String previous) { throw new IllegalStateException("No foreign native clock boundary"); }
        default ForeignRead readForeign(long ordinal,long deadline,String previousServer,ForeignObservation expected) { throw new IllegalStateException("No foreign native read boundary"); }
        default void continueForeignRead(ForeignRead request) { }
        default void retireForeignRead(ForeignRead request) { request.retired.set(true); }
        default JsonObject foreignMetadata() { throw new IllegalStateException("No foreign native metadata boundary"); }
        default void flushForeign() { System.out.flush(); require(!System.out.checkError(),"Foreign stdout flush/check failed"); }
    }

    /** Detached owner facts only: this policy cannot create/cancel scopes or release native ownership. */
    interface ExpiryClaims {
        ExpiryObservation observe();
        boolean acknowledge(MinecraftClient current,ExpiryBusyAck ack);
    }

    private static boolean expiryTimeout(FailureObservation failure) {
        return failure!=null && "com.mapartrunner.fabric.scenario.ScenarioHost$ScopeTimeoutException".equals(failure.className())
                && "Inspect scope absolute deadline expired".equals(failure.message());
    }

    // RUN2 alone owns this finite input role. WORK wait has no input origin; BUSY_REQUIRED creates one.
    // Send/ack stages latch before callbacks. Failure/stop clear only local debt/ack; owners retain cleanup.
    private final class Expiry {
        private enum Step { WAIT_BUSY, OPEN, HANDOFF, ACK, RESULT, DISPOSED }
        private static final String BUSY_CHAT="/maparttest rerun grounded-two-lane";
        private Step step=Step.WAIT_BUSY;
        private ExpiryObservation original,request;
        private ExpiryBusyAck ack;
        private Object screen;
        private long chatOrigin;
        private boolean entered;

        private ExpiryObservation validate() {
            require(!stopping && !stopRequested && step!=Step.DISPOSED && enabled.getAsBoolean()
                    && "work-expiry".equals(System.getProperty("mapart.test.inspect.acceptance.case")),"Expiry gate/stopping changed");
            input.requireBinding();
            var observed=observations.get();
            require(observed.ready() && !observed.restartRequired(),"Expiry Commands unavailable");
            if(step!=Step.RESULT) { requireUnchanged(observed); require(observed.busy(),"Expiry original RUN2 no longer busy"); }
            var now=expiryClaims.observe();
            if(now==null) { require(original==null && step==Step.WAIT_BUSY,"Expiry projection lost"); return null; }
            require(now.ordinal()==2 && Objects.equals(session,now.sessionId()) && now.operationId()!=null && !operations.contains(now.operationId())
                    && now.runId()!=null && !runs.contains(now.runId())
                    && now.outerCutoffNanos()-now.workDeadlineNanos()==10_000_000_000L,"Expiry original identity/cutoff invalid");
            require(Set.of("ARMED","HELD","EXPIRED","BEFORE_REPLAY_READ","REPLAY_QUEUED","REPLAY_REJECTED",
                    "AFTER_REPLAY_READ","POST_REPLAY_MEASURED","CLEANUP_PENDING","BUSY_REQUIRED","SEALED_BEFORE_END").contains(now.stage()),"Expiry incomplete/replay-failed/unknown stage");
            if(original==null) {
                original=now;
                marker("EXPIRY_ORIGINAL operation="+now.operationId()+" run="+now.runId()+" workDeadlineNanos="+now.workDeadlineNanos()+" outerCutoffNanos="+now.outerCutoffNanos());
            }
            require(Objects.equals(now.sessionId(),original.sessionId()) && Objects.equals(now.operationId(),original.operationId())
                    && Objects.equals(now.runId(),original.runId()) && now.workDeadlineNanos()==original.workDeadlineNanos()
                    && now.outerCutoffNanos()==original.outerCutoffNanos(),"Expiry original identity/deadline changed");
            if(step!=Step.RESULT) require(clock.getAsLong()-original.outerCutoffNanos()<0,"Expiry original outer cutoff expired");
            if(request!=null || now.stage().equals("BUSY_REQUIRED") || now.stage().equals("SEALED_BEFORE_END")) {
                require(now.wrapperEntries()==2 && now.actionEntries()==0 && now.replayCount()==1 && now.replayRejected()
                        && now.distinctReplayFailure() && expiryTimeout(now.primary()) && expiryTimeout(now.replay()) && now.boundaryRetired()
                        && now.requestOrdinal()==1 && now.cleanupDeadlineNanos()-now.outerCutoffNanos()<=0
                        && now.cleanupDeadlineNanos()-now.workDeadlineNanos()>0,"Expiry replay/cleanup proof invalid");
                if(request!=null) require(now.cleanupDeadlineNanos()==request.cleanupDeadlineNanos()
                        && Objects.equals(now.primary(),request.primary()) && Objects.equals(now.replay(),request.replay()),"Expiry busy request changed");
                require(now.busyRefusals()>=0 && now.busyRefusals()<=1,"Expiry busy refusal count invalid");
                if(step==Step.RESULT) {
                    require(Objects.equals(ack,now.acknowledgement()) && now.busyRefusals()==1
                            && Set.of("BUSY_REQUIRED","SEALED_BEFORE_END").contains(now.stage()),"Expiry acknowledged proof changed");
                    require(expiryProof==null || expiryProof.equals(now),"Expiry sealed proof changed");
                } else {
                    require(now.stage().equals("BUSY_REQUIRED") && now.acknowledgement()==null,"Expiry busy request already sealed/acknowledged");
                    long time=clock.getAsLong();
                    require(time-original.workDeadlineNanos()>=0 && time-now.cleanupDeadlineNanos()<0,"Expiry original cleanup deadline invalid/expired");
                    require(entered || now.busyRefusals()==0,"Expiry refusal before busy Enter");
                }
            } else require(step==Step.WAIT_BUSY && now.requestOrdinal()==0 && now.busyRefusals()==0 && now.acknowledgement()==null,
                    "Expiry premature request/refusal/ack");
            return now;
        }

        private ExpiryObservation inputCheck() {
            var now=validate();
            require(now!=null && request!=null && clock.getAsLong()-chatOrigin<INPUT_NANOS,"Expiry busy input deadline expired (original 30000ms)");
            return now;
        }

        void tick(MinecraftClient current,InspectObservation observed) {
            var now=validate();
            switch(step) {
                case WAIT_BUSY -> {
                    require(input.screen()==null,"Unexpected expiry WORK screen");
                    if(now==null || !now.stage().equals("BUSY_REQUIRED")) return;
                    require(heldKey==-1 && heldForward==null,"Expiry busy input requires no synthetic debt");
                    request=now; chatOrigin=clock.getAsLong();
                    inputCheck(); input.requireNeutral();
                    step=Step.OPEN;
                    marker("EXPIRY_BUSY_REQUEST denied RERUN role text="+BUSY_CHAT+" request=1 cleanupDeadlineNanos="+request.cleanupDeadlineNanos());
                    inputCheck(); pairedKey(input.chatKey(),null);
                }
                case OPEN -> {
                    inputCheck(); screen=input.screen();
                    if(screen==null) return;
                    input.requireChat(screen,"");
                    step=Step.HANDOFF; // Whole send is single-use, including a character/Enter that applies then throws.
                    for(int i=0;i<BUSY_CHAT.length();i++) {
                        inputCheck(); input.requireChat(screen,BUSY_CHAT.substring(0,i)); input.character(BUSY_CHAT.charAt(i));
                    }
                    inputCheck(); input.requireChat(screen,BUSY_CHAT);
                    entered=true; pairedKey(ENTER,screen);
                    marker("EXPIRY_BUSY_ENTER denied RERUN delivered; awaiting natural handoff");
                }
                case HANDOFF -> {
                    now=inputCheck();
                    if(input.screen()!=null) { input.requireChat(screen,BUSY_CHAT); return; }
                    input.requireNeutral();
                    if(now.busyRefusals()==0) return; // Never retype to repair an unrecorded refusal.
                    ack=new ExpiryBusyAck(request.operationId(),request.requestOrdinal(),request.cleanupDeadlineNanos());
                    step=Step.ACK;
                    marker("EXPIRY_BUSY_HANDOFF one refusal observed; retained CHAT audit required");
                }
                case ACK -> {
                    now=inputCheck();
                    require(input.screen()==null && now.busyRefusals()==1,"Expiry ack requires closed chat/one refusal");
                    input.requireNeutral();
                    // False retries only this exact frozen value on a later callback. Exceptions retire it in fail().
                    if(expiryClaims.acknowledge(current,ack)) {
                        step=Step.RESULT;
                        marker("EXPIRY_BUSY_ACK operation="+ack.operationId()+" request="+ack.requestOrdinal()+" cleanupDeadlineNanos="+ack.cleanupDeadlineNanos());
                    }
                }
                case RESULT -> {
                    require(input.screen()==null,"Unexpected completion screen");
                    if(now.stage().equals("SEALED_BEFORE_END")) expiryProof=now;
                    acceptSettled(observed,nextCompletion(observed));
                }
                default -> throw new IllegalStateException("Expiry input retired");
            }
        }

        void dispose() { step=Step.DISPOSED; ack=null; screen=null; }
    }

    interface ForeignClaims {
        ForeignObservation observe();
        boolean release(MinecraftClient current);
        boolean replay(MinecraftClient current,UUID operation,long deadline);
        boolean seal(MinecraftClient current,ForeignProofAck ack);
    }

    // One program, one outstanding read and one frozen claim; originals clear only after successful consumption.
    private final class Foreign {
        private enum Step { EXIT, CREATE, BIND, SETTLE, READ, CHAT_OPEN, CHAT_SEND, CHAT_HANDOFF, RELEASE, REPLAY, REPLAY_ACK, FINAL, SEAL_ACK, AWAIT_END }
        private final ForeignObservation original;
        private final ForeignProof proof=new ForeignProof(log,input::flushForeign);
        private Step step=Step.EXIT;
        private long readOrdinal,chatOrigin;
        private int pair,chatIndex;
        private ForeignRead read;
        private String baselineClient,baselineServer,previousClient,previousServer;
        private ForeignObservation frozen;
        private ForeignProofAck seal;
        private Object chatScreen;
        Foreign(ForeignObservation original) { this.original=original; }
        private ForeignObservation validate(InspectObservation observed) {
            require(!stopping && !stopRequested && enabled.getAsBoolean() && "foreign-world".equals(System.getProperty("mapart.test.inspect.acceptance.case")),"Foreign gate/stopping changed");
            var now=foreignClaims.observe();
            require(now!=null && now.sessionId().equals(original.sessionId()) && now.operationId().equals(original.operationId())
                    && now.ordinal()==3 && now.queueEntered() && now.workDeadlineNanos()==original.workDeadlineNanos()
                    && !Set.of("ABANDONED","INCOMPLETE","REPLAY_FAILED").contains(now.stage()),"Foreign request changed/abandoned");
            requireUnchanged(observed);
            require(observed.busy() || step==Step.AWAIT_END,"Foreign original operation no longer busy");
            long deadline=now.requestOrdinal()==0?now.workDeadlineNanos():now.cleanupDeadlineNanos();
            require(clock.getAsLong()-deadline<0 || step==Step.AWAIT_END,"Foreign original deadline expired");
            require(now.guards().stream().allMatch(g -> FOREIGN_GUARDS.contains(g.site()) && g.effectCalled()==0
                    && g.entered()>=0 && g.refused()>=0 && g.passed()>=0 && g.entered()==g.refused()+g.passed()),"Managed effect/unknown guard fact after foreign arming");
            return now;
        }
        private void nativeCheck(InspectObservation observed) { validate(observed); input.requireBinding(); }
        void tick(MinecraftClient current,InspectObservation observed) {
            if(step==Step.AWAIT_END) return;
            var now=validate(observed);
            switch(step) {
                case EXIT -> {
                    require(!now.cancelled() && now.stage().equals("HELD"),"Foreign exit requires entered original held wrapper");
                    input.requireNeutral(); step=Step.CREATE; input.exitForeign();
                }
                case CREATE -> {
                    require(now.cancelled(),"Native exit did not cancel original WORK");
                    if(input.createForeign(() -> validate(observations.get()),original.sessionId())) step=Step.BIND;
                }
                case BIND -> { if(input.bindForeign()) step=Step.SETTLE; }
                case SETTLE -> {
                    nativeCheck(observed);
                    if(input.settleForeign()) {
                        var header=input.foreignMetadata(); var identity=input.idleIdentity();
                        header.addProperty("schema","mapartrunner.inspect.foreign.v1"); header.addProperty("version",1);
                        header.addProperty("nativePid",identity.nativePid()); header.addProperty("nativeStartedUtc",identity.nativeStartedUtc().toString());
                        header.addProperty("launchId",identity.launchId().toString()); header.addProperty("sessionId",original.sessionId().toString());
                        header.addProperty("operationId",original.operationId().toString()); header.addProperty("ordinal",3);
                        header.addProperty("run2Operation",retained.operationId().toString()); header.addProperty("run2",retained.runId().orElseThrow().toString());
                        header.addProperty("run2Summary",retained.summaryPath()); header.addProperty("run2Evidence",retained.evidenceDirectory());
                        header.addProperty("workDeadlineNanos",original.workDeadlineNanos()); header.addProperty("sourceJoin","Root retained immutable source/tree plus launch identity");
                        var exclusions=new JsonArray(); List.of("clocks.age","clocks.worldTime","clocks.timeOfDay","server.clocks.serverTick","player.hunger.value.foodTickTimer").forEach(exclusions::add);
                        header.add("exclusions",exclusions); proof.row("HEADER",header); step=Step.READ;
                    }
                }
                case READ -> {
                    nativeCheck(observed); input.requireNeutral();
                    if(read==null) {
                        if(previousClient!=null && !input.foreignClockAdvanced(previousClient)) return;
                        read=input.readForeign(++readOrdinal,now.requestOrdinal()==0?now.workDeadlineNanos():now.cleanupDeadlineNanos(),previousServer,now); return;
                    }
                    input.continueForeignRead(read);
                    var remote=read.consume(readOrdinal,clock.getAsLong()); if(remote==null) return;
                    nativeCheck(observations.get()); input.requireNeutral();
                    var local=read.client;
                    if(pair==0) {
                        protectedForeign(local,false); protectedForeign(remote,true); baselineClient=local; baselineServer=remote;
                    } else {
                        compareForeign(baselineClient,previousClient,local,false); compareForeign(baselineServer,previousServer,remote,true);
                    }
                    var label=PAIR_LABELS.get(pair); proof.pair(label,readOrdinal,local,remote);
                    previousClient=local; previousServer=remote; input.retireForeignRead(read); read=null; pair++;
                    if(pair>=2 && pair<=5) step=Step.CHAT_OPEN;
                    else if(pair==26) { frozen=now; step=Step.RELEASE; }
                    else if(pair==27) { frozen=now; step=Step.REPLAY_ACK; }
                    else if(pair==28) {
                        requireFinal(now);
                        var finalRows=foreignFacts(now); finalRows.addProperty("finalObservationIdentity",finalIdentity());
                        proof.row("FINAL_REQUEST",finalRows);
                        nativeCheck(observations.get()); frozen=now;
                        seal=proof.seal(now,finalIdentity(),clock.getAsLong()); step=Step.SEAL_ACK;
                    }
                }
                case CHAT_OPEN -> {
                    nativeCheck(observed); input.requireNeutral(); chatOrigin=clock.getAsLong(); chatScreen=null;
                    var row=new JsonObject(); row.addProperty("command",foreignCommand()); row.addProperty("beforePair",PAIR_LABELS.get(pair-1));
                    row.addProperty("expectedFeedbackKind",chatIndex<3?"actual native refusal; Root must verify retained vanilla chat bytes":"actual native LAST RUN2; Root must verify retained vanilla chat bytes");
                    proof.row("CHAT_REQUESTED",row); step=Step.CHAT_SEND; pairedKey(input.chatKey(),null);
                }
                case CHAT_SEND -> {
                    chatDeadline(); nativeCheck(observed); chatScreen=input.screen(); if(chatScreen==null) return;
                    input.requireChat(chatScreen,""); step=Step.CHAT_HANDOFF; var command=foreignCommand();
                    for(int i=0;i<command.length();i++) { chatDeadline(); nativeCheck(observations.get()); input.requireChat(chatScreen,command.substring(0,i)); input.character(command.charAt(i)); }
                    chatDeadline(); input.requireChat(chatScreen,command);
                    var row=new JsonObject(); row.addProperty("command",command); row.addProperty("typing","transient complete draft; Enter callback follows"); proof.row("CHAT_ENTER",row);
                    pairedKey(ENTER,chatScreen);
                }
                case CHAT_HANDOFF -> {
                    chatDeadline(); nativeCheck(observed);
                    if(input.screen()!=null) { input.requireChat(chatScreen,foreignCommand()); return; }
                    input.requireNeutral(); var row=new JsonObject(); row.addProperty("command",foreignCommand()); row.addProperty("nextPair","C"+(chatIndex+1));
                    row.addProperty("feedbackAuthority","independent retained vanilla ChatHud logger; this row is handoff only"); proof.row("CHAT_HANDOFF",row);
                    chatIndex++; step=Step.READ;
                }
                case RELEASE -> {
                    nativeCheck(observed); input.requireNeutral(); require(samePending(frozen,now,"CANCELLED_HOLD","HELD"),"Foreign release pending request changed");
                    if(foreignClaims.release(current)) { step=Step.REPLAY; frozen=null; }
                }
                case REPLAY -> {
                    nativeCheck(observed); input.requireNeutral();
                    if(now.stage().equals("REPLAY_REJECTED")) {
                        require(now.replayRejected() && now.cancelled() && !now.boundaryRetired(),"No actual original-wrapper refusal");
                        proof.row("REPLAY_REJECTED",foreignFacts(now)); step=Step.READ;
                    } else require(Set.of("RELEASE_REQUESTED","REPLAY_QUEUED").contains(now.stage()),"Unexpected original replay stage");
                }
                case REPLAY_ACK -> {
                    nativeCheck(observed); input.requireNeutral(); require(samePending(frozen,now,"REPLAY_REJECTED"),"Foreign P claim changed");
                    if(foreignClaims.replay(current,original.operationId(),original.workDeadlineNanos())) { step=Step.FINAL; frozen=null; }
                }
                case FINAL -> {
                    nativeCheck(observed); input.requireNeutral();
                    if(now.stage().equals("FINAL_NATIVE_READ_REQUIRED")) { requireFinal(now); proof.row("FINAL_REQUESTED",foreignFacts(now)); step=Step.READ; }
                    else require(Set.of("POST_REPLAY_MEASURED","CLEANUP_PENDING").contains(now.stage()),"Unexpected foreign cleanup stage");
                }
                case SEAL_ACK -> {
                    nativeCheck(observed); input.requireNeutral(); require(samePending(frozen,now,"FINAL_NATIVE_READ_REQUIRED"),"Foreign final claim changed");
                    requireFinal(now);
                    if(foreignClaims.seal(current,seal)) { dispose(); step=Step.AWAIT_END; }
                }
                case AWAIT_END -> { /* Original supervisor owns expected UNRESOLVED END/escalation; no close or further native proof. */ }
            }
        }
        private void chatDeadline() { require(clock.getAsLong()-chatOrigin<INPUT_NANOS,"Foreign original chat input deadline expired"); }
        private String foreignCommand() { return chatIndex==3?"/maparttest last":"/maparttest "+List.of("reset","run","rerun").get(chatIndex)+" grounded-two-lane"; }
        private String finalIdentity() { return sha256((previousClient+"\n"+previousServer+"\n").getBytes(StandardCharsets.UTF_8)); }
        private void requireFinal(ForeignObservation now) {
            require(now.stage().equals("FINAL_NATIVE_READ_REQUIRED") && now.requestOrdinal()==1 && now.cleanupDeadlineNanos()!=0
                    && now.boundaryRetired() && now.replayRejected() && now.cancelled() && now.finalFacts()!=null
                    && now.finalFacts().retainedWatchUnchanged() && now.finalFacts().closedRunEvidenceUnchanged()
                    && now.finalFacts().primaryClass()!=null && now.finalFacts().primaryMessage()!=null
                    && now.finalFacts().guards().equals(now.guards()),"Foreign final proof provenance unavailable");
            require(now.guards().stream().anyMatch(g -> g.site().equals("CLEANUP_PREFLIGHT") && g.entered()>0 && g.refused()==g.entered() && g.passed()==0),"Foreign cleanup refusal not reached");
        }
        void dispose() {
            var outstanding=read; read=null;
            frozen=null; seal=null; baselineClient=null; baselineServer=null; previousClient=null; previousServer=null;
            if(outstanding!=null) { outstanding.retired.set(true); input.retireForeignRead(outstanding); }
        }
    }
    private static boolean samePending(ForeignObservation frozen,ForeignObservation current,String... stages) {
        return Arrays.asList(stages).contains(current.stage()) && frozen.equals(current);
    }
    private static JsonObject foreignFacts(ForeignObservation value) {
        var out=new JsonObject(); out.addProperty("sessionId",value.sessionId().toString()); out.addProperty("operationId",value.operationId().toString());
        out.addProperty("ordinal",value.ordinal()); out.addProperty("stage",value.stage()); out.addProperty("workDeadlineNanos",value.workDeadlineNanos());
        out.addProperty("queueEntered",value.queueEntered()); out.addProperty("replayRejected",value.replayRejected()); out.addProperty("cancelled",value.cancelled());
        out.addProperty("boundaryRetired",value.boundaryRetired()); out.addProperty("requestOrdinal",value.requestOrdinal()); out.addProperty("cleanupDeadlineNanos",value.cleanupDeadlineNanos());
        var guards=new JsonArray(); for(var g:value.guards()) {
            var row=new JsonObject(); row.addProperty("site",g.site()); row.addProperty("entered",g.entered()); row.addProperty("refused",g.refused()); row.addProperty("passed",g.passed()); row.addProperty("effectCalled",g.effectCalled()); guards.add(row);
        } out.add("guards",guards);
        var unreached=new JsonArray(); FOREIGN_GUARDS.stream().filter(site -> value.guards().stream().noneMatch(g -> g.site().equals(site) && g.entered()>0))
                .forEach(unreached::add); out.add("unreachedGuardSites",unreached);
        if(value.finalFacts()!=null) {
            out.addProperty("retainedWatchUnchanged",value.finalFacts().retainedWatchUnchanged()); out.addProperty("closedRunEvidenceUnchanged",value.finalFacts().closedRunEvidenceUnchanged());
            out.addProperty("primaryClass",value.finalFacts().primaryClass()); out.addProperty("primaryMessage",value.finalFacts().primaryMessage());
        }
        return out;
    }
    private static final List<String> FOREIGN_GUARDS=List.of("RESET_STOP","RESET_CLEAR","RESTORE_SERVER","RESTORE_BLOCKS_PLAYER_INVENTORY",
            "RESTORE_CLIENT","RESTORE_POSE_INPUT_OPTIONS","START","LIVE_CLIENT","CAPTURE_FAULT","CAPTURE_SCREEN_PREFLIGHT","CAPTURE_WATCH",
            "SCREENSHOT","CLEANUP_PREFLIGHT","CLEANUP_RELEASE","CLEANUP_STOP","CLEANUP_FAULT_CANCEL","CLOSE_OPTIONS","CLOSE_NATIVE","CLOSE_NATIVE_WORLD","CLOSE_NATIVE_CLIENT");

    record Pose(double x, double z) { }
    record IdleIdentity(long nativePid,Instant nativeStartedUtc,UUID launchId) { }

    private static JsonElement canonical(JsonElement value) {
        if(value.isJsonObject()) {
            var out=new JsonObject();
            new TreeSet<>(value.getAsJsonObject().keySet()).forEach(k -> out.add(k,canonical(value.getAsJsonObject().get(k))));
            return out;
        }
        if(value.isJsonArray()) {
            var out=new JsonArray(); value.getAsJsonArray().forEach(v -> out.add(canonical(v))); return out;
        }
        return value.deepCopy();
    }
    static String canonicalText(JsonElement value) { return canonical(value).toString(); }
    static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static JsonObject floating(float value) {
        require(Float.isFinite(value),"Nonfinite float");
        var out=new JsonObject(); out.addProperty("type","f32"); out.addProperty("bits",Integer.toUnsignedString(Float.floatToRawIntBits(value),16));
        out.addProperty("hex",Float.toHexString(value)); return out;
    }
    static JsonObject floating(double value) {
        require(Double.isFinite(value),"Nonfinite double");
        var out=new JsonObject(); out.addProperty("type","f64"); out.addProperty("bits",Long.toUnsignedString(Double.doubleToRawLongBits(value),16));
        out.addProperty("hex",Double.toHexString(value)); return out;
    }
    /** Tagged recursive NBT: no NbtOps-to-JSON coercion, numeric widening or array/list conflation. */
    static JsonObject typedNbt(NbtElement element) {
        require(element!=null,"Missing typed NBT");
        var out=new JsonObject(); out.addProperty("type",element.getType());
        JsonElement value;
        if(element instanceof NbtCompound compound) {
            var fields=new JsonObject(); new TreeSet<>(compound.getKeys()).forEach(k -> fields.add(k,typedNbt(compound.get(k)))); value=fields;
        } else if(element instanceof NbtList list) {
            var items=new JsonArray(); list.forEach(v -> items.add(typedNbt(v))); value=items;
        } else if(element instanceof NbtByteArray array) {
            var items=new JsonArray(); for(byte n:array.getByteArray()) items.add(n); value=items;
        } else if(element instanceof NbtIntArray array) {
            var items=new JsonArray(); for(int n:array.getIntArray()) items.add(n); value=items;
        } else if(element instanceof NbtLongArray array) {
            var items=new JsonArray(); for(long n:array.getLongArray()) items.add(n); value=items;
        } else if(element instanceof NbtString text) value=new com.google.gson.JsonPrimitive(text.value());
        else if(element instanceof AbstractNbtNumber number) {
            value=switch(element.getType()) {
                case NbtElement.BYTE_TYPE -> new com.google.gson.JsonPrimitive(number.byteValue());
                case NbtElement.SHORT_TYPE -> new com.google.gson.JsonPrimitive(number.shortValue());
                case NbtElement.INT_TYPE -> new com.google.gson.JsonPrimitive(number.intValue());
                case NbtElement.LONG_TYPE -> new com.google.gson.JsonPrimitive(number.longValue());
                case NbtElement.FLOAT_TYPE -> floating(number.floatValue());
                case NbtElement.DOUBLE_TYPE -> floating(number.doubleValue());
                default -> throw new IllegalStateException("Unknown numeric NBT type");
            };
        } else if(element.getType()==NbtElement.END_TYPE) value=JsonNull.INSTANCE;
        else throw new IllegalStateException("Unknown NBT type: "+element.getType());
        out.add("value",value); return out;
    }

    /** One finite stdout stream; the seal excludes itself and permanently closes data emission. */
    static final class ForeignProof {
        static final String PREFIX="INSPECT_FOREIGN_PROOF ";
        static final int SIDE_BYTES=262144, CHUNK_BYTES=1536, LINE_BYTES=4096;
        private final Consumer<String> output;
        private final Runnable flush;
        private final MessageDigest digest;
        private long rows, bytes;
        private int pairs;
        private boolean sealed;
        ForeignProof(Consumer<String> output,Runnable flush) {
            this.output=output; this.flush=flush;
            try { digest=MessageDigest.getInstance("SHA-256"); }
            catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
        void row(String kind,JsonObject fields) {
            require(!sealed,"Foreign proof already sealed");
            var row=fields.deepCopy(); row.addProperty("kind",kind); row.addProperty("row",rows);
            var text=canonicalText(row); var raw=(text+"\n").getBytes(StandardCharsets.UTF_8);
            require((PREFIX+text+"\n").getBytes(StandardCharsets.UTF_8).length<=LINE_BYTES,"Foreign proof line overflow");
            output.accept(PREFIX+text); digest.update(raw); bytes=Math.addExact(bytes,raw.length); rows=Math.addExact(rows,1);
        }
        void pair(String label,long request,String client,String server) {
            require(pairs<28 && PAIR_LABELS.get(pairs).equals(label),"Foreign pair order/count changed");
            // Validate both sides before any partial pair escapes.
            var local=client.getBytes(StandardCharsets.UTF_8); var remote=server.getBytes(StandardCharsets.UTF_8);
            require(local.length>0 && remote.length>0 && local.length<=SIDE_BYTES && remote.length<=SIDE_BYTES,"Foreign snapshot empty/overflow");
            chunks(label,request,"client",local); chunks(label,request,"server",remote); pairs++;
        }
        private void chunks(String label,long request,String side,byte[] raw) {
            int count=(raw.length+CHUNK_BYTES-1)/CHUNK_BYTES;
            var sideHash=sha256(raw);
            for(int chunk=0;chunk<count;chunk++) {
                var data=new JsonObject(); data.addProperty("pair",label); data.addProperty("request",request); data.addProperty("side",side);
                data.addProperty("chunk",chunk); data.addProperty("chunks",count); data.addProperty("sideBytes",raw.length);
                data.addProperty("sideSha256",sideHash);
                data.addProperty("base64",Base64.getEncoder().encodeToString(Arrays.copyOfRange(raw,chunk*CHUNK_BYTES,Math.min(raw.length,(chunk+1)*CHUNK_BYTES))));
                row("SNAPSHOT",data);
            }
        }
        ForeignProofAck seal(ForeignObservation request,String identity,long beforeClaimNanos) {
            require(!sealed && pairs==28,"Foreign seal requires exactly 28 full pairs");
            sealed=true;
            var hash=HexFormat.of().formatHex(digest.digest());
            var seal=new JsonObject(); seal.addProperty("kind","SEAL"); seal.addProperty("rows",rows); seal.addProperty("bytes",bytes);
            seal.addProperty("sha256",hash); seal.addProperty("operationId",request.operationId().toString());
            seal.addProperty("requestOrdinal",request.requestOrdinal()); seal.addProperty("cleanupDeadlineNanos",request.cleanupDeadlineNanos());
            seal.addProperty("beforeClaimNanos",beforeClaimNanos); seal.addProperty("finalObservationIdentity",identity);
            var labels=new JsonArray(); PAIR_LABELS.forEach(labels::add); seal.add("pairs",labels); seal.addProperty("measurementVerdict","COMPLETE");
            var text=canonicalText(seal);
            require((PREFIX+text+"\n").getBytes(StandardCharsets.UTF_8).length<=LINE_BYTES,"Foreign seal overflow");
            output.accept(PREFIX+text); flush.run();
            return new ForeignProofAck(request.operationId(),request.requestOrdinal(),rows,bytes,hash,identity);
        }
    }
    static final List<String> PAIR_LABELS;
    static {
        var labels=new ArrayList<>(List.of("B0","B1","C1","C2","C3","C4"));
        for(int i=1;i<=20;i++) labels.add("D"+i); labels.add("P"); labels.add("F"); PAIR_LABELS=List.copyOf(labels);
    }

    static String protectedForeign(String text,boolean server) {
        var state=JsonParser.parseString(text).getAsJsonObject();
        var clocks=state.getAsJsonObject("clocks");
        require(clocks.keySet().equals(Set.of("age","worldTime","timeOfDay","serverTick")),"Unknown foreign clock schema");
        clocks.remove("age"); clocks.remove("worldTime"); clocks.remove("timeOfDay");
        if(server) clocks.remove("serverTick"); else require(clocks.get("serverTick").isJsonNull(),"Client cannot supply server clock credit");
        var hunger=state.getAsJsonObject("player").getAsJsonObject("hunger");
        require(hunger.get("type").getAsInt()==NbtElement.COMPOUND_TYPE,"Foreign hunger is not typed compound");
        var fields=hunger.getAsJsonObject("value"); var timer=fields.getAsJsonObject("foodTickTimer");
        require(timer!=null && timer.get("type").getAsInt()==NbtElement.INT_TYPE,"Missing typed hunger timer");
        fields.remove("foodTickTimer"); return canonicalText(state);
    }
    static void compareForeign(String baseline,String before,String after,boolean server) {
        require(protectedForeign(baseline,server).equals(protectedForeign(after,server))
                && protectedForeign(before,server).equals(protectedForeign(after,server)),"Foreign protected state changed");
        var a=JsonParser.parseString(before).getAsJsonObject().getAsJsonObject("clocks");
        var b=JsonParser.parseString(after).getAsJsonObject().getAsJsonObject("clocks");
        for(var key:server?List.of("age","worldTime","serverTick"):List.of("age","worldTime"))
            require(b.get(key).getAsLong()>a.get(key).getAsLong(),"Foreign independent clock did not advance: "+key);
    }

    /** Only detached strings cross the native read boundary; each request is entered and retired once. */
    static final class ForeignRead {
        private enum State { QUEUED, RUNNING, CLOCK_PENDING, REQUEUE_READY, CAPTURED }
        final long ordinal, deadline;
        final String client,previousServer;
        final java.util.concurrent.atomic.AtomicReference<String> server=new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<FailureObservation> failure=new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean entered=new java.util.concurrent.atomic.AtomicBoolean(),retired=new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicReference<State> state=new java.util.concurrent.atomic.AtomicReference<>(State.QUEUED);
        private ForeignClock lastClock;
        ForeignRead(long ordinal,long deadline,String client) { this(ordinal,deadline,client,null); }
        ForeignRead(long ordinal,long deadline,String client,String previousServer) {
            this.ordinal=ordinal; this.deadline=deadline; this.client=client; this.previousServer=previousServer;
        }
        void probeEntry() { require(!retired.get() && failure.get()==null && state.compareAndSet(State.QUEUED,State.RUNNING),"Late/duplicate foreign server callback"); }
        boolean admit(ForeignClock clock) {
            require(!retired.get() && state.get()==State.RUNNING && !entered.get(),"Foreign full capture already admitted");
            var previous=previousServer==null?null:foreignClock(previousServer);
            require(clock.age>=0 && clock.time>=0 && clock.tick>=0 && (previous==null || clock.nondecreasing(previous))
                    && (lastClock==null || clock.nondecreasing(lastClock)),"Foreign server clock regressed/unknown");
            lastClock=clock;
            if(previous!=null && !clock.advancing(previous)) {
                require(state.compareAndSet(State.RUNNING,State.CLOCK_PENDING),"Foreign pending clock result changed"); return false;
            }
            enter(); return true;
        }
        void enter() {
            require(!retired.get() && failure.get()==null && entered.compareAndSet(false,true),"Late/duplicate foreign native read");
            state.compareAndSet(State.QUEUED,State.RUNNING);
        }
        boolean requeue() { return !retired.get() && failure.get()==null && state.compareAndSet(State.REQUEUE_READY,State.QUEUED); }
        void complete(String value) {
            require(!retired.get() && entered.get() && state.get()==State.RUNNING && server.compareAndSet(null,value),"Late/duplicate foreign native result");
            require(state.compareAndSet(State.RUNNING,State.CAPTURED),"Foreign full native result changed");
        }
        String consume(long expected,long now) {
            require(ordinal==expected && now-deadline<0 && !retired.get(),"Stale/expired foreign native result");
            var problem=failure.get(); require(problem==null,"Foreign server read failed: "+problem);
            if(state.compareAndSet(State.CLOCK_PENDING,State.REQUEUE_READY)) return null;
            if(state.get()!=State.CAPTURED) return null;
            var result=server.get(); if(result==null) return null;
            require(state.get()==State.CAPTURED && entered.get() && retired.compareAndSet(false,true),"Foreign native result already consumed"); return result;
        }
    }
    record ForeignClock(long age,long time,long tick) {
        boolean nondecreasing(ForeignClock other) { return age>=other.age && time>=other.time && tick>=other.tick; }
        boolean advancing(ForeignClock other) { return age>other.age && time>other.time && tick>other.tick; }
    }
    private static ForeignClock foreignClock(String state) {
        var clocks=JsonParser.parseString(state).getAsJsonObject().getAsJsonObject("clocks");
        return new ForeignClock(clocks.get("age").getAsLong(),clocks.get("worldTime").getAsLong(),clocks.get("serverTick").getAsLong());
    }

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
        private final Supplier<ForeignObservation> foreignObservation;
        private Object world, server, player, handler, connection, window;
        private long handle;
        private NativeForeign replacement;
        NativeChat(MinecraftClient client,Supplier<ForeignObservation> foreignObservation) { this.client = client; this.foreignObservation=foreignObservation; }
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
            if(replacement!=null && replacement.bound) { replacement.checkBinding(); return; }
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
        @Override public void exitForeign() {
            requireNeutral(); require(replacement==null,"Foreign exit already attempted");
            replacement=new NativeForeign();
            client.openGameMenu(false);
            replacement.installed();
            require(client.world==world && client.getServer()==server && client.player==player
                    && client.getNetworkHandler()==handler && client.getNetworkHandler().getConnection()==connection,"Original binding changed before exit");
            require(client.currentScreen!=null && client.currentScreen.getClass()==GameMenuScreen.class
                    && ((GameMenuScreen)client.currentScreen).shouldShowMenu(),"Expected native pause menu");
            var screen=client.currentScreen;
            var button=uniqueButton(screen,ScreenTexts.returnToMenuOrDisconnect(client.isInSingleplayer()).getString());
            replacement.installed(); require(client.currentScreen==screen && client.world==world && client.getServer()==server,"Exit menu/binding replaced");
            require(button.active && button.visible,"Native exit button changed before press");
            button.onPress(new MouseInput(0,0));
        }
        @Override public boolean createForeign(Runnable revalidate,UUID session) { return replacement.create(revalidate,session); }
        @Override public boolean bindForeign() { return replacement.bind(); }
        @Override public boolean settleForeign() { return replacement.settle(); }
        @Override public boolean foreignClockAdvanced(String previous) {
            replacement.checkBinding();
            var clocks=JsonParser.parseString(previous).getAsJsonObject().getAsJsonObject("clocks");
            require(client.player.age>=clocks.get("age").getAsLong() && client.world.getTime()>=clocks.get("worldTime").getAsLong(),"Foreign client admission clock regressed");
            require(client.player.age>=replacement.clientAdmissionAge && client.world.getTime()>=replacement.clientAdmissionTime,"Foreign client clock regressed across admission checks");
            replacement.clientAdmissionAge=client.player.age; replacement.clientAdmissionTime=client.world.getTime();
            for(var pos:replacement.cells) loaded(client.world,pos);
            return client.player.age>clocks.get("age").getAsLong() && client.world.getTime()>clocks.get("worldTime").getAsLong();
        }
        @Override public ForeignRead readForeign(long ordinal,long deadline,String previousServer,ForeignObservation expected) { return replacement.read(ordinal,deadline,previousServer,expected); }
        @Override public void continueForeignRead(ForeignRead request) { replacement.continueRead(request); }
        @Override public void retireForeignRead(ForeignRead request) {
            request.retired.set(true);
            if(replacement!=null && replacement.nativeRead==request) { replacement.nativeRead=null; replacement.serverRead=null; replacement.readProjection=null; }
        }
        @Override public JsonObject foreignMetadata() { return replacement.metadata(); }

        private final class NativeForeign {
            final ScenarioDefinition definition=ScenarioDefinition.load();
            final List<BlockPos> cells=foreignCells(definition);
            final Path saves,oldSave;
            Path foreignSave;
            Object foreignWorld,foreignPlayer,foreignHandler,foreignConnection,interaction;
            MinecraftServer foreignServer;
            UUID uuid;
            // These two references are read/written only on the captured foreign server's own thread.
            ServerWorld serverWorld;
            ServerPlayerEntity serverPlayer;
            boolean created,bound,creationCancelled;
            long settleAge=-1;
            int stableAges;
            String settling;
            long clientAdmissionAge=-1,clientAdmissionTime=-1;
            ForeignRead nativeRead;
            ForeignObservation readProjection;
            Runnable serverRead;
            final java.util.concurrent.atomic.AtomicReference<FailureObservation> nativeReadFailure=new java.util.concurrent.atomic.AtomicReference<>();
            NativeForeign() {
                saves=real(client.getLevelStorage().getSavesDirectory());
                oldSave=real(client.getServer().getSavePath(WorldSavePath.ROOT));
                require(oldSave.getParent().equals(saves),"Original inspect save outside canonical saves root");
            }
            void installed() {
                requireClient(client);
                require(!creationCancelled && enabled() && InspectScenarioHost.foreignAcceptanceEnabled() && client.isRunning()
                        && client.getWindow()==window && handle!=0 && client.getWindow().getHandle()==handle
                        && client.isWindowFocused() && client.getOverlay()==null,"Foreign installed client/window/gate/focus unavailable");
            }
            boolean create(Runnable revalidate,UUID session) {
                installed(); require(!created,"Foreign world creation already attempted");
                if(client.world!=null || client.getServer()!=null || client.player!=null || client.getNetworkHandler()!=null) {
                    require(transitionScreen(client.currentScreen),"Unexpected exit interstitial/binding"); return false;
                }
                require(client.currentScreen!=null && client.currentScreen.getClass()==TitleScreen.class,"Expected title after native save/quit");
                if(!client.isFinishedLoading()) return false;
                revalidate.run();
                CreateWorldScreen.show(client,() -> creationCancelled=true);
                // show pumps native tasks: authenticate again in this same callback, before configuration or press.
                revalidate.run(); installed(); requirePreworld();
                require(client.currentScreen!=null && client.currentScreen.getClass()==CreateWorldScreen.class,"Foreign creation screen replaced");
                var screen=(CreateWorldScreen)client.currentScreen; var creator=screen.getWorldCreator();
                creator.setWorldName("mapart-ordinary-foreign-"+session);
                creator.setGameMode(WorldCreator.Mode.SURVIVAL); creator.setCheatsEnabled(false);
                var flat=creator.getGeneratorOptionsHolder().getCombinedRegistryManager().getOrThrow(RegistryKeys.WORLD_PRESET).getOrThrow(WorldPresets.FLAT);
                creator.setWorldType(new WorldCreator.WorldType(flat)); creator.setSeed("2");
                foreignSave=saves.resolve(creator.getWorldDirectoryName()).toAbsolutePath().normalize();
                require(real(client.getLevelStorage().getSavesDirectory()).equals(saves) && foreignSave.getParent().equals(saves)
                        && !foreignSave.equals(oldSave) && !Files.exists(foreignSave,java.nio.file.LinkOption.NOFOLLOW_LINKS),"Foreign destination exists/redirected/not distinct");
                var button=uniqueButton(screen,net.minecraft.text.Text.translatable("selectWorld.create").getString());
                revalidate.run(); installed(); requirePreworld();
                require(client.currentScreen==screen && screen.getWorldCreator()==creator
                        && client.getLevelStorage().getSavesDirectory().resolve(creator.getWorldDirectoryName()).toAbsolutePath().normalize().equals(foreignSave),"Foreign create screen/creator/save changed");
                require(button.active && button.visible,"Native create button changed before press");
                created=true; button.onPress(new MouseInput(0,0)); return true;
            }
            void requirePreworld() { require(client.world==null && client.getServer()==null && client.player==null && client.getNetworkHandler()==null,"Foreign creation over a world"); }
            boolean bind() {
                installed(); require(created && !bound,"Foreign binding phase invalid");
                if(client.currentScreen!=null || client.world==null || client.getServer()==null || client.player==null || client.getNetworkHandler()==null) {
                    require(transitionScreen(client.currentScreen),"Unexpected foreign loading interstitial"); return false;
                }
                require(client.world!=world && client.getServer()!=server && client.player!=player && client.getNetworkHandler()!=handler
                        && client.getNetworkHandler().getConnection()!=connection,"Foreign native identities not distinct");
                require(real(client.getServer().getSavePath(WorldSavePath.ROOT)).equals(foreignSave),"Actual foreign save changed/redirected");
                foreignWorld=client.world; foreignServer=client.getServer(); foreignPlayer=client.player; foreignHandler=client.getNetworkHandler();
                foreignConnection=client.getNetworkHandler().getConnection(); interaction=client.interactionManager; uuid=client.player.getUuid();
                bound=true; checkBinding(); return true;
            }
            void checkBinding() {
                installed();
                require(nativeReadFailure.get()==null,"Foreign native callback failed/duplicated: "+nativeReadFailure.get());
                require(bound && client.world==foreignWorld && client.getServer()==foreignServer && client.player==foreignPlayer
                        && client.getNetworkHandler()==foreignHandler && client.getNetworkHandler().getConnection()==foreignConnection
                        && client.getNetworkHandler().getConnection().isOpen() && client.player.getUuid().equals(uuid)
                        && client.interactionManager==interaction && !client.isPaused(),"Foreign binding/server/UUID/connection replaced");
                require(client.world.getRegistryKey().equals(World.OVERWORLD),"Foreign client dimension changed");
            }
            boolean settle() {
                checkBinding(); requireNeutral(); requireInactive(client.player);
                // Unknown/unloaded cells are hard failures even during setup; only physical settling may reset this count.
                for(var cell:cells) loaded(client.world,cell);
                var pose=poseAndVelocity(client.player); long age=client.player.age;
                require(age>=settleAge,"Foreign setup age regressed");
                if(age==settleAge) return false;
                settleAge=age;
                if(!client.player.isOnGround() || !Objects.equals(settling,pose)) { settling=pose; stableAges=client.player.isOnGround()?1:0; return false; }
                return ++stableAges>=20;
            }
            ForeignRead read(long ordinal,long deadline,String previousServer,ForeignObservation expected) {
                checkBinding(); requireNeutral(); require(System.nanoTime()-deadline<0,"Foreign native read expired before capture");
                require(nativeRead==null && serverRead==null,"Foreign native read already outstanding");
                requireReadProjection(expected,deadline); readProjection=expected;
                var capturedServer=foreignServer; var capturedWorld=client.world; var capturedPlayer=client.player;
                var value=readForeignPlayer(capturedPlayer,cells,-1,foreignSave);
                var local=JsonParser.parseString(value).getAsJsonObject(); local.add("client",clientState(client));
                var request=new ForeignRead(ordinal,deadline,canonicalText(local),previousServer); nativeRead=request;
                serverRead=() -> {
                    try {
                        request.probeEntry();
                        require(capturedServer.isOnThread() && System.nanoTime()-deadline<0 && InspectScenarioHost.foreignAcceptanceEnabled(),"Wrong foreign server thread/expired/disabled read");
                        requireReadProjection(expected,deadline);
                        require(capturedServer.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize().equals(foreignSave),"Foreign server save changed");
                        var level=capturedServer.getOverworld(); var players=capturedServer.getPlayerManager().getPlayerList();
                        var sole=capturedServer.getPlayerManager().getPlayer(uuid);
                        require(level!=null && level.getRegistryKey().equals(World.OVERWORLD) && players.size()==1
                                && sole!=null && players.getFirst()==sole && sole.getEntityWorld()==level && sole.getUuid().equals(uuid),"Foreign server sole-player/world/UUID mismatch");
                        if(serverWorld==null) { serverWorld=level; serverPlayer=sole; }
                        require(level==serverWorld && sole==serverPlayer,"Foreign server world/player replaced");
                        require(level.getSeed()==2 && level.getChunkManager().getChunkGenerator() instanceof net.minecraft.world.gen.chunk.FlatChunkGenerator
                                && !capturedServer.getSaveProperties().areCommandsAllowed() && sole.getGameMode()==GameMode.SURVIVAL,"Foreign world seed/preset/cheats/mode changed");
                        requireInactive(sole);
                        for(var pos:cells) loaded(level,pos);
                        var support=sole.getBlockPos().down(); loaded(level,support); loaded(level,support.up()); loaded(level,support.up(2));
                        var clocks=new ForeignClock(sole.age,level.getTime(),capturedServer.getTicks());
                        if(!request.admit(clocks)) return; // No full sample exists; only this same request may await natural clock readiness.
                        var state=JsonParser.parseString(readForeignPlayer(sole,cells,capturedServer.getTicks(),foreignSave)).getAsJsonObject();
                        require(foreignClock(canonicalText(state)).equals(clocks),"Foreign full capture clocks differ from admitted own-thread clocks");
                        var settings=new JsonObject(); settings.addProperty("seed",level.getSeed()); settings.addProperty("generatorClass",level.getChunkManager().getChunkGenerator().getClass().getName());
                        settings.addProperty("cheats",capturedServer.getSaveProperties().areCommandsAllowed()); settings.addProperty("solePlayerCount",players.size());
                        settings.addProperty("solePlayerUuid",sole.getUuid().toString()); settings.addProperty("serverWorld",System.identityHashCode(level)); settings.addProperty("serverPlayer",System.identityHashCode(sole));
                        state.add("serverWorld",settings); request.complete(canonicalText(state));
                    } catch(RuntimeException | Error failure) {
                        var detached=new FailureObservation(failure.getClass().getName(),failure.getMessage());
                        request.failure.compareAndSet(null,detached); nativeReadFailure.compareAndSet(null,detached);
                    }
                };
                capturedServer.execute(serverRead);
                // Re-entrant native execute implementations may have changed the client binding; never consume on that assumption.
                checkBinding(); require(client.world==capturedWorld && client.player==capturedPlayer && client.getServer()==capturedServer,"Foreign client replaced during read dispatch");
                return request;
            }
            void continueRead(ForeignRead request) {
                checkBinding(); requireNeutral();
                require(nativeRead==request && serverRead!=null && !request.retired.get() && System.nanoTime()-request.deadline<0,"Foreign pending native request changed/expired");
                requireReadProjection(readProjection,request.deadline);
                for(var pos:cells) loaded(client.world,pos);
                // consume() changes PENDING_CLOCK to REQUEUE_READY on the preceding END, never in this dispatch.
                if(request.requeue()) foreignServer.execute(serverRead);
            }
            void requireReadProjection(ForeignObservation expected,long deadline) {
                require(expected!=null && expected.equals(foreignObservation.get()) && expected.queueEntered() && expected.cancelled()
                        && Set.of("HELD","CANCELLED_HOLD","REPLAY_REJECTED","FINAL_NATIVE_READ_REQUIRED").contains(expected.stage())
                        && deadline==(expected.requestOrdinal()==0?expected.workDeadlineNanos():expected.cleanupDeadlineNanos())
                        && System.nanoTime()-deadline<0,"Foreign native request projection/stage/deadline changed");
            }
            JsonObject metadata() {
                checkBinding(); var out=new JsonObject(); out.addProperty("fixture",definition.fixture().name()); out.addProperty("fixtureHash",definition.definitionHash());
                out.addProperty("oldSave",oldSave.toString()); out.addProperty("foreignSave",foreignSave.toString());
                out.addProperty("oldWorld",System.identityHashCode(world)); out.addProperty("oldServer",System.identityHashCode(server));
                out.addProperty("oldPlayer",System.identityHashCode(player)); out.addProperty("oldHandler",System.identityHashCode(handler)); out.addProperty("oldConnection",System.identityHashCode(connection));
                out.addProperty("foreignWorld",System.identityHashCode(foreignWorld)); out.addProperty("foreignServer",System.identityHashCode(foreignServer));
                out.addProperty("foreignPlayer",System.identityHashCode(foreignPlayer)); out.addProperty("foreignHandler",System.identityHashCode(foreignHandler)); out.addProperty("foreignConnection",System.identityHashCode(foreignConnection));
                out.addProperty("playerUuid",uuid.toString()); out.addProperty("window",System.identityHashCode(window)); out.addProperty("windowHandle",handle);
                out.addProperty("cellSchema","sorted checked union of worldCells and origin+expectedTargets; support/clearance separate"); out.addProperty("cellCount",cells.size());
                var positions=new JsonArray(); cells.forEach(p -> positions.add(position(p))); out.addProperty("cellPositionsSha256",sha256(canonicalText(positions).getBytes(StandardCharsets.UTF_8)));
                out.addProperty("inventorySchema","actual size, every index; main0..35, FEET36 LEGS37 CHEST38 HEAD39 OFFHAND40 BODY41 SADDLE42; all equipment enums; all handler slots/cursor/crafting/output");
                out.addProperty("inventorySize",client.player.getInventory().size()); out.addProperty("playerHandlerType","native null type, exact PlayerScreenHandler");
                out.addProperty("setupReadiness","20 strictly advancing client ages, same real pose/full velocity, onGround/neutral/no activity; no server/proof credit");
                return out;
            }
        }
    }

    private static Path real(Path path) {
        var absolute=path.toAbsolutePath().normalize();
        try { require(absolute.toRealPath().equals(absolute),"Redirected canonical save path"); return absolute; }
        catch(IOException failure) { throw new UncheckedIOException(failure); }
    }
    private static boolean transitionScreen(Object screen) {
        return screen==null || Set.of("net.minecraft.client.gui.screen.MessageScreen","net.minecraft.client.gui.screen.ProgressScreen",
                "net.minecraft.client.gui.screen.DownloadingTerrainScreen","net.minecraft.client.gui.screen.world.LevelLoadingScreen")
                .contains(screen.getClass().getName());
    }
    static ButtonWidget uniqueButton(Object screen,String label) {
        var buttons=new ArrayList<ButtonWidget>(); discover(screen,Collections.newSetFromMap(new IdentityHashMap<>()),buttons);
        var matches=buttons.stream().filter(b -> b.getMessage().getString().equals(label)).toList();
        require(matches.size()==1 && matches.getFirst().active && matches.getFirst().visible,"Expected one enabled visible native button: "+label);
        return matches.getFirst();
    }
    private static void discover(Object element,Set<Object> seen,List<ButtonWidget> buttons) {
        if(!seen.add(element)) return;
        if(element instanceof ButtonWidget b) buttons.add(b);
        if(element instanceof ParentElement p) for(Element child:p.children()) discover(child,seen,buttons);
        if(element instanceof Widget w) w.forEachChild(child -> discover(child,seen,buttons));
    }

    static List<BlockPos> foreignCells(ScenarioDefinition definition) {
        var cells=new TreeSet<BlockPos>(Comparator.comparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ));
        definition.worldCells().keySet().forEach(p -> cells.add(new BlockPos(p.x(),p.y(),p.z())));
        var origin=definition.origin(); definition.expectedTargets().keySet().forEach(p -> cells.add(new BlockPos(
                Math.addExact(origin.x(),p.x()),Math.addExact(origin.y(),p.y()),Math.addExact(origin.z(),p.z()))));
        require(!cells.isEmpty(),"Empty foreign protected cell union"); return List.copyOf(cells);
    }
    private static JsonArray position(BlockPos p) { var out=new JsonArray(); out.add(p.getX()); out.add(p.getY()); out.add(p.getZ()); return out; }
    private static <T> JsonElement encode(Codec<T> codec,T value,World world) {
        return checkedEncode(codec,world.getRegistryManager().getOps(JsonOps.INSTANCE),value);
    }
    static <T> JsonElement checkedEncode(Codec<T> codec,com.mojang.serialization.DynamicOps<JsonElement> ops,T value) {
        var result=codec.encodeStart(ops,value);
        require(result.error().isEmpty(),"Foreign codec rejected complete value: "+result.error());
        return result.getOrThrow();
    }
    private static WorldChunk loaded(World world,BlockPos p) {
        int x=Math.floorDiv(p.getX(),16),z=Math.floorDiv(p.getZ(),16);
        WorldChunk chunk;
        if(world instanceof ServerWorld serverWorld) {
            require(serverWorld.getServer().isOnThread(),"Foreign block read off server thread");
            chunk=serverWorld.getChunkManager().getWorldChunk(x,z);
        } else {
            var client=MinecraftClient.getInstance(); require(client.isOnThread() && client.world==world,"Foreign block read off client thread");
            chunk=((net.minecraft.client.world.ClientWorld)world).getChunkManager().getChunk(x,z,ChunkStatus.FULL,false);
        }
        return checkedForeignChunk(chunk,x,z,p);
    }
    static WorldChunk checkedForeignChunk(WorldChunk chunk,int x,int z,BlockPos p) {
        require(chunk!=null && chunk.getStatus()==ChunkStatus.FULL && chunk.getPos().x==x && chunk.getPos().z==z,"Foreign protected cell lacks already-loaded FULL chunk: "+p);
        return chunk;
    }
    private static JsonObject cell(World world,BlockPos pos) {
        var chunk=loaded(world,pos); var out=new JsonObject(); out.add("position",position(pos)); out.addProperty("loaded",true);
        out.add("blockState",encode(BlockState.CODEC,chunk.getBlockState(pos),world));
        // CHECK and packed-NBT getters materialize pending block entities; inspect the two existing maps directly.
        var live=chunk.getBlockEntities().get(pos); var pending=chunk.getBlockEntityNbt(pos);
        require(chunk.getBlockEntityPositions().contains(pos)==(live!=null || pending!=null),"Foreign block-entity representation unknown");
        out.add("pending",pending==null?JsonNull.INSTANCE:typedNbt(pending));
        if(live==null) out.add("live",JsonNull.INSTANCE);
        else {
            require(!live.isRemoved() && live.getPos().equals(pos) && live.getWorld()==world,"Foreign live block entity removed/replaced");
            var reporter=new ErrorReporter.Impl(); var view=NbtWriteView.create(reporter,world.getRegistryManager()); live.writeFullData(view);
            require(reporter.isEmpty(),"Foreign live block entity serialization error: "+reporter.getErrorsAsString());
            var data=new JsonObject(); data.addProperty("type",net.minecraft.registry.Registries.BLOCK_ENTITY_TYPE.getId(live.getType()).toString());
            data.add("position",position(live.getPos())); data.addProperty("identity",System.identityHashCode(live)); data.add("data",typedNbt(view.getNbt())); out.add("live",data);
        }
        return out;
    }
    private static void requireInactive(PlayerEntity p) {
        require(p.getStatusEffects().isEmpty() && p.hurtTime==0 && p.deathTime==0 && !p.isDead() && p.isAlive()
                && !p.isUsingItem() && p.getItemUseTime()==0 && p.getItemUseTimeLeft()==0 && !p.handSwinging,"Foreign status/hurt/death/item-use/hand-swing activity");
    }
    private static String poseAndVelocity(PlayerEntity p) {
        var out=new JsonObject(); String[] names={"x","y","z","vx","vy","vz"};
        double[] values={p.getX(),p.getY(),p.getZ(),p.getVelocity().x,p.getVelocity().y,p.getVelocity().z};
        for(int i=0;i<names.length;i++) out.add(names[i],floating(values[i]));
        out.add("yaw",floating(p.getYaw())); out.add("headYaw",floating(p.headYaw)); out.add("bodyYaw",floating(p.bodyYaw)); out.add("pitch",floating(p.getPitch()));
        out.addProperty("pose",p.getPose().name()); out.addProperty("onGround",p.isOnGround()); return canonicalText(out);
    }
    private static String readForeignPlayer(PlayerEntity p,List<BlockPos> cells,int serverTick,Path save) {
        requireInactive(p); var world=p.getEntityWorld(); var state=new JsonObject();
        var binding=new JsonObject(); binding.addProperty("world",System.identityHashCode(world)); binding.addProperty("player",System.identityHashCode(p));
        binding.addProperty("uuid",p.getUuid().toString()); binding.addProperty("dimension",world.getRegistryKey().getValue().toString()); binding.addProperty("save",save.toString());
        state.add("binding",binding);
        var clocks=new JsonObject(); clocks.addProperty("age",p.age); clocks.addProperty("worldTime",world.getTime()); clocks.addProperty("timeOfDay",world.getTimeOfDay());
        if(serverTick<0) clocks.add("serverTick",JsonNull.INSTANCE); else clocks.addProperty("serverTick",serverTick); state.add("clocks",clocks);
        var player=JsonParser.parseString(poseAndVelocity(p)).getAsJsonObject(); state.add("player",player);
        player.add("health",floating(p.getHealth())); player.add("absorption",floating(p.getAbsorptionAmount())); player.add("fallDistance",floating(p.fallDistance));
        var abilities=p.getAbilities().pack(); var packed=new JsonObject();
        packed.addProperty("invulnerable",abilities.invulnerable()); packed.addProperty("flying",abilities.flying()); packed.addProperty("mayFly",abilities.mayFly());
        packed.addProperty("instabuild",abilities.instabuild()); packed.addProperty("mayBuild",abilities.mayBuild()); packed.add("flyingSpeed",floating(abilities.flyingSpeed())); packed.add("walkingSpeed",floating(abilities.walkingSpeed())); player.add("abilities",packed);
        var reporter=new ErrorReporter.Impl(); var hunger=NbtWriteView.create(reporter,world.getRegistryManager()); p.getHungerManager().writeData(hunger);
        require(reporter.isEmpty(),"Foreign hunger codec error: "+reporter.getErrorsAsString()); player.add("hunger",typedNbt(hunger.getNbt()));
        var activity=new JsonObject(); activity.addProperty("handSwinging",p.handSwinging); activity.addProperty("handSwingTicks",p.handSwingTicks);
        activity.add("lastHandSwingProgress",floating(p.lastHandSwingProgress)); activity.add("handSwingProgress",floating(p.handSwingProgress));
        activity.addProperty("hurtTime",p.hurtTime); activity.addProperty("maxHurtTime",p.maxHurtTime); activity.addProperty("deathTime",p.deathTime);
        activity.addProperty("stuckArrowTimer",p.stuckArrowTimer); activity.addProperty("stuckStingerTimer",p.stuckStingerTimer);
        activity.addProperty("itemUseTime",p.getItemUseTime()); activity.addProperty("itemUseTimeLeft",p.getItemUseTimeLeft());
        activity.addProperty("playerHitTimer",p.getPlayerHitTimer()); activity.addProperty("sleepTimer",p.getSleepTimer());
        activity.add("limbSpeed",floating(p.limbAnimator.getSpeed())); activity.add("limbProgress",floating(p.limbAnimator.getAnimationProgress())); player.add("activity",activity);
        var movement=new JsonObject(); movement.addProperty("noGravity",p.hasNoGravity()); movement.addProperty("noDrag",p.hasNoDrag()); movement.addProperty("jumping",p.isJumping());
        movement.addProperty("vehicle",p.hasVehicle()); movement.addProperty("inFluid",p.isInFluid()); movement.addProperty("touchingWater",p.isTouchingWater());
        movement.addProperty("submergedWater",p.isSubmergedInWater()); movement.addProperty("lava",p.isInLava()); movement.addProperty("climbing",p.isClimbing());
        movement.addProperty("gliding",p.isGliding()); movement.addProperty("swimming",p.isSwimming()); movement.addProperty("sprinting",p.isSprinting()); movement.addProperty("sneaking",p.isSneaking());
        movement.addProperty("usingItem",p.isUsingItem()); movement.add("finalGravity",floating(p.getFinalGravity())); player.add("movement",movement);
        var effects=new JsonArray(); p.getStatusEffects().forEach(effect -> effects.add(encode(StatusEffectInstance.CODEC,effect,world))); player.add("effects",effects);
        player.add("attributes",foreignAttributes(p));
        var inventory=p.getInventory();
        requireForeignInventorySchema(inventory.size(),inventory.getMainStacks().size(),net.minecraft.entity.player.PlayerInventory.getHotbarSize(),net.minecraft.entity.player.PlayerInventory.EQUIPMENT_SLOTS);
        var inventoryData=new JsonObject(); inventoryData.addProperty("identity",System.identityHashCode(inventory));
        inventoryData.addProperty("size",inventory.size()); inventoryData.addProperty("mainSize",inventory.getMainStacks().size()); inventoryData.addProperty("selected",inventory.getSelectedSlot());
        var slots=new JsonArray(); for(int i=0;i<inventory.size();i++) {
            var slot=new JsonObject(); slot.addProperty("index",i); slot.add("stack",encode(ItemStack.OPTIONAL_CODEC,inventory.getStack(i),world)); slots.add(slot);
        } inventoryData.add("slots",slots); player.add("inventory",inventoryData);
        var equipment=new JsonArray(); for(var slot:EquipmentSlot.values()) {
            var row=new JsonObject(); row.addProperty("slot",slot.getName()); row.add("stack",encode(ItemStack.OPTIONAL_CODEC,p.getEquippedStack(slot),world)); equipment.add(row);
        } player.add("equipment",equipment);
        require(p.currentScreenHandler==p.playerScreenHandler && p.playerScreenHandler.getClass()==PlayerScreenHandler.class,"Foreign neutral handler is not exact player handler");
        player.add("activeHandler",handler(p,p.currentScreenHandler)); player.add("playerHandler",handler(p,p.playerScreenHandler));
        var crafting=new JsonArray(); var input=p.playerScreenHandler.getCraftingInput();
        for(int i=0;i<input.size();i++) { var row=new JsonObject(); row.addProperty("index",i); row.add("stack",encode(ItemStack.OPTIONAL_CODEC,input.getStack(i),world)); crafting.add(row); }
        player.addProperty("craftingSize",input.size()); player.add("crafting",crafting); player.add("craftingOutput",encode(ItemStack.OPTIONAL_CODEC,p.playerScreenHandler.getOutputSlot().getStack(),world));
        var support=p.getBlockPos().down(); var supportData=new JsonObject(); supportData.add("block",cell(world,support)); supportData.add("clearance1",cell(world,support.up())); supportData.add("clearance2",cell(world,support.up(2)));
        supportData.addProperty("supported",p.isSupportedBy(support)); supportData.add("supportingBlock",p.supportingBlockPos.map(InspectAcceptanceDriver::position).orElse(null)); player.add("support",supportData);
        var blocks=new JsonArray(); for(var pos:cells) blocks.add(cell(world,pos)); state.add("cells",blocks);
        if(p instanceof ServerPlayerEntity sp) {
            player.addProperty("gameMode",sp.getGameMode().name()); player.addProperty("input",sp.getPlayerInput().toString());
            require(sp.getGameMode()==GameMode.SURVIVAL && sp.getPlayerInput().equals(PlayerInput.DEFAULT),"Foreign server mode/input not neutral");
            binding.addProperty("server",System.identityHashCode(((ServerWorld)world).getServer()));
        }
        return canonicalText(state);
    }
    static void requireForeignInventorySchema(int size,int main,int hotbar,Map<Integer,EquipmentSlot> equipment) {
        var expected=Map.of(36,EquipmentSlot.FEET,37,EquipmentSlot.LEGS,38,EquipmentSlot.CHEST,39,EquipmentSlot.HEAD,
                40,EquipmentSlot.OFFHAND,41,EquipmentSlot.BODY,42,EquipmentSlot.SADDLE);
        require(main==36 && hotbar==9 && equipment.equals(expected) && size==Math.addExact(main,equipment.size())
                && EquipmentSlot.values().length==8,"Pinned actual native inventory/equipment mapping changed");
    }
    private static JsonObject handler(PlayerEntity p,net.minecraft.screen.ScreenHandler handler) {
        var out=new JsonObject(); out.addProperty("class",handler.getClass().getName()); out.addProperty("identity",System.identityHashCode(handler)); out.addProperty("syncId",handler.syncId);
        require(handler.getClass()==PlayerScreenHandler.class,"Unexpected foreign handler class"); out.add("nativeType",JsonNull.INSTANCE);
        out.addProperty("sameAsPlayer",handler==p.playerScreenHandler); out.addProperty("sameAsActive",handler==p.currentScreenHandler);
        out.add("cursor",encode(ItemStack.OPTIONAL_CODEC,handler.getCursorStack(),p.getEntityWorld())); out.addProperty("slotCount",handler.slots.size());
        var slots=new JsonArray(); for(int i=0;i<handler.slots.size();i++) {
            var slot=handler.slots.get(i); var row=new JsonObject(); row.addProperty("listIndex",i); row.addProperty("id",slot.id); row.addProperty("inventoryIndex",slot.getIndex());
            row.addProperty("inventoryIdentity",System.identityHashCode(slot.inventory)); row.addProperty("inventoryClass",slot.inventory.getClass().getName());
            row.add("stack",encode(ItemStack.OPTIONAL_CODEC,slot.getStack(),p.getEntityWorld())); slots.add(row);
        } out.add("slots",slots); return out;
    }
    private static JsonArray foreignAttributes(PlayerEntity p) {
        var attributes=p.getAttributes(); var custom=new HashSet<String>();
        for(var packed:attributes.pack()) require(custom.add(packed.attribute().getKey().orElseThrow().getValue().toString()),"Duplicate packed custom attribute");
        var out=new JsonArray();
        var entries=p.getEntityWorld().getRegistryManager().getOrThrow(RegistryKeys.ATTRIBUTE).streamEntries()
                .sorted(Comparator.comparing(e -> e.registryKey().getValue().toString())).toList();
        for(var entry:entries) {
            String id=entry.registryKey().getValue().toString(); boolean supported=attributes.hasAttribute(entry),existing=custom.contains(id);
            var row=new JsonObject(); row.addProperty("id",id); row.addProperty("supported",supported); row.addProperty("custom",existing);
            require(!existing || supported,"Unsupported packed custom attribute");
            if(supported) {
                row.add("base",floating(attributes.getBaseValue(entry))); row.add("effective",floating(attributes.getValue(entry)));
                var all=new JsonArray(); var persistent=new JsonArray();
                if(existing) {
                    // pack listed this exact existing instance in this uninterrupted own-thread read; never create a default.
                    var instance=attributes.getCustomInstance(entry); require(instance!=null,"Packed custom attribute instance missing");
                    all=modifiers(instance.getModifiers()); persistent=modifiers(instance.getPersistentModifiers());
                }
                row.add("modifiers",all); row.add("persistentModifiers",persistent); row.addProperty("defaultProvenance",existing?"pack-listed existing native instance":"vanilla PLAYER default builder has no default modifiers");
            } out.add(row);
        }
        require(entries.stream().filter(e -> custom.contains(e.registryKey().getValue().toString())).count()==custom.size(),"Packed custom attribute absent from registry");
        return out;
    }
    private static JsonArray modifiers(Set<EntityAttributeModifier> modifiers) {
        var out=new JsonArray(); modifiers.stream().sorted(Comparator.comparing(m -> m.id().toString())).forEach(m -> {
            var row=new JsonObject(); row.addProperty("id",m.id().toString()); row.add("value",floating(m.value())); row.addProperty("operation",m.operation().name()); out.add(row);
        }); return out;
    }
    private static JsonObject clientState(MinecraftClient client) {
        var out=new JsonObject(); var keys=new JsonArray();
        for(var key:client.options.allKeys) {
            var row=new JsonObject(); row.addProperty("translationKey",key.getId()); row.addProperty("pressed",key.isPressed());
            row.addProperty("boundKey",key.getBoundKeyTranslationKey()); keys.add(row);
        } out.add("keys",keys); out.addProperty("paused",client.isPaused()); out.addProperty("screenOpen",client.currentScreen!=null);
        out.addProperty("autoJump",client.options.getAutoJump().getValue()); out.addProperty("viewDistanceRaw",client.options.getViewDistance().getValue()); out.addProperty("viewDistanceClamped",client.options.getClampedViewDistance());
        out.addProperty("focused",client.isWindowFocused()); out.addProperty("overlay",client.getOverlay()!=null);
        out.addProperty("mode",client.interactionManager.getCurrentGameMode().name()); out.addProperty("input",client.player.input.playerInput.toString());
        out.addProperty("lastInput",client.player.getLastPlayerInput().toString()); out.addProperty("movementInput",client.player.input.getMovementInput().toString());
        var status=MapArtClient.status(); out.addProperty("production",status.toString()); out.addProperty("controlOwner",status.controlOwner().name());
        var fault=PredictionFaults.resetSnapshot();
        require(!fault.active(),"Foreign read cannot validate or retire an active fault scope");
        // resetSnapshot proved scope absent on this uninterrupted client thread; stateSnapshot.check is then a no-op.
        var stateFault=PredictionFaults.stateSnapshot(); out.addProperty("fault",fault.toString()); out.addProperty("stateFault",stateFault.toString());
        require(status.controlOwner()==BuildEngine.ControlOwner.NONE && !fault.active() && !stateFault.active()
                && fault.failure().isEmpty() && stateFault.failure().isEmpty(),"Foreign production/fault ownership active");
        var phase=status.engine().lifecycle().phase(); require(phase!=com.mapartrunner.core.run.RunLifecycle.Phase.RUNNING && phase!=com.mapartrunner.core.run.RunLifecycle.Phase.PAUSED,"Foreign production active");
        require(client.interactionManager.getCurrentGameMode()==GameMode.SURVIVAL && client.player.input.playerInput.equals(PlayerInput.DEFAULT)
                && client.player.getLastPlayerInput().equals(PlayerInput.DEFAULT) && client.player.input.getMovementInput().lengthSquared()==0,"Foreign client mode/input not neutral");
        out.addProperty("handler",System.identityHashCode(client.getNetworkHandler())); out.addProperty("connection",System.identityHashCode(client.getNetworkHandler().getConnection()));
        out.addProperty("server",System.identityHashCode(client.getServer())); out.addProperty("window",System.identityHashCode(client.getWindow())); out.addProperty("windowHandle",client.getWindow().getHandle()); return out;
    }

    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
}
