package com.mapartrunner.fabric.scenario;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.core.movement.MovementPolicy;
import com.mapartrunner.core.placement.PlacementObservation;
import com.mapartrunner.core.run.BuildEngine;
import com.mapartrunner.core.run.RunLifecycle.*;
import com.mapartrunner.fabric.MapArtClient;
import com.mapartrunner.fabric.mixin.ClientWorldPendingUpdateAccessor;
import com.mapartrunner.fabric.prediction.PendingPredictionView;
import com.mapartrunner.fabric.scenario.GroundedSingleLaneGameTest.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.Hand;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.GameMode;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static com.mapartrunner.core.placement.PlacementObservation.Evidence.*;

/** One disposable fixture; no reset, server-to-production observations or post-start physics edits. */
final class SingleLaneScenario {
    enum Kind { BASELINE, STOP, FAILURE, DISCONNECT, REPLACEMENT, WITHHELD_RESPONSE, POST_FAULT_BASELINE,
        WITHHELD_OBSERVATION, POST_OBSERVATION_BASELINE, COMPUTED_STOP, COMPUTED_PAUSE_RESUME,
        WITHHELD_STATE, POST_STATE_BASELINE, REPEATED_STATE, POST_REPEATED_STATE_BASELINE }
    private record Fixture(int missingTarget, Item usableItem, int stock, int selected) { }
    private static final int WAIT = ClientGameTestContext.DEFAULT_TIMEOUT;
    private static final GridPos ORIGIN = new GridPos(0,65,0);
    private static final BuildEngine.Settings SETTINGS = new BuildEngine.Settings(2,5,false,1);
    private static final BlockSpec STONE = new BlockSpec("minecraft:stone", Map.of());
    private static final BuildPlan PLAN = SingleLaneFixture.plan();
    private final ClientGameTestContext context;
    private final Watch watch;
    private final Kind kind;
    private final Fixture fixture;
    private final Path artifacts;
    private TestSingleplayerContext world;
    private ClientWorld bound;
    private ClientPlayNetworkHandler boundHandler;
    private ClientConnection boundConnection;
    private UUID runId;
    private boolean closeAttempted;
    private boolean transferred;
    private int writtenSamples;
    private double clientReach;
    private double serverReach;
    private double maxClientX = .5;
    private double maxServerX = .5;
    private Step stateSettled;

    SingleLaneScenario(ClientGameTestContext context, Watch watch, Kind kind) {
        this.context = context; this.watch = watch; this.kind = kind;
        fixture = switch(kind) {
            case WITHHELD_RESPONSE, WITHHELD_STATE -> new Fixture(SingleLaneFixture.targetId(PLAN,new GridPos(0,0,0)),Items.STONE,64,0);
            case WITHHELD_OBSERVATION, COMPUTED_STOP, COMPUTED_PAUSE_RESUME -> new Fixture(SingleLaneFixture.targetId(PLAN,new GridPos(11,0,0)),Items.STONE,64,0);
            case REPEATED_STATE -> new Fixture(SingleLaneFixture.targetId(PLAN,new GridPos(0,0,0)),Items.SNOW,2,1);
            default -> new Fixture(-1,Items.STONE,kind==Kind.FAILURE?12:64,0);
        };
        var game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        require(game.getParent().getFileName().toString().equals("run")
                && game.getParent().getParent().getFileName().toString().equals("build"), "Unmanaged game directory: " + game);
        artifacts = game.getParent().getParent().resolve("scenario-results").resolve("single-lane-" + kind + "-" + UUID.randomUUID());
        try { Files.createDirectories(artifacts); } catch (IOException e) { throw new UncheckedIOException(e); }
        System.out.println("MapArtRunner single lane artifacts: " + artifacts);
        note("START " + Instant.now() + "; case=" + kind + "; game=" + game + "; Baritone=absent/unavailable; untested");
        for (String mod : List.of("minecraft", "fabricloader", "fabric-api", "mapart", "mapart-test")) {
            note(mod + "=" + FabricLoader.getInstance().getModContainer(mod).orElseThrow().getMetadata().getVersion());
        }
        note("settings=" + SETTINGS.fingerprint() + "; Java=" + System.getProperty("java.version"));
        var manifest = manifest();
        write("fixture.txt", manifest);
        note("fixtureSha256=" + hash(manifest) + "; plan=" + PLAN.fingerprint());
    }

    void run(SingleLaneScenario disconnected) {
        Throwable primary = null;
        try {
            world = context.worldBuilder().create();
            note("save=" + world.getWorldSave().getSaveDirectory().toAbsolutePath());
            setup();
            if (disconnected != null) {
                idlePhysics("disconnect fresh-world idle", 20);
                disconnected.note("PASS after owned close and fresh-world 20 idle physics ticks; followup=" + artifacts);
            }
            start();
            if (kind == Kind.BASELINE || kind == Kind.POST_FAULT_BASELINE || kind == Kind.POST_OBSERVATION_BASELINE
                    || kind==Kind.POST_STATE_BASELINE || kind==Kind.POST_REPEATED_STATE_BASELINE) baseline();
            else if (kind == Kind.WITHHELD_RESPONSE) withheldResponse();
            else if (kind == Kind.WITHHELD_OBSERVATION) withheldObservation();
            else if (kind == Kind.WITHHELD_STATE) withheldState();
            else if (kind == Kind.REPEATED_STATE) repeatedState();
            else if (computedCase()) computedBarrier();
            else if (kind == Kind.FAILURE) {
                await(s -> s.client().status().engine().lifecycle().phase() == Phase.TERMINAL, 1200, "material-starved failure");
                var terminal = read().client().status();
                require(terminal.engine().lifecycle().outcome().orElseThrow() == Outcome.FAILED, "Expected core failure: " + terminal);
                var diagnostic = terminal.engine().diagnostic().orElseThrow();
                require(diagnostic.reason() == BuildEngine.Reason.MOVEMENT_FAILED && diagnostic.detail().equals("SUPPORT_TIMEOUT"),
                        "Wrong starvation outcome: " + terminal);
                idlePhysics("core failure", 20);
            } else {
                await(s -> s.client().pose().x() > .8 && s.client().keys().getFirst(), WAIT, "actual native movement");
                if (kind == Kind.STOP) {
                    context.runOnClient(client -> MapArtClient.stop());
                    cancelled("stop"); idlePhysics("stop", 20);
                } else if (kind == Kind.DISCONNECT) {
                    screenshot("before-disconnect");
                    require(read().client().status().engine().lifecycle().phase() == Phase.RUNNING, "Run ended before disconnect");
                    closeOwned();
                    context.waitTicks(20);
                    drain(); cancelled("disconnect close");
                    note("CLOSED; awaiting next owned world's 20 actual idle physics ticks");
                    return;
                } else replaceWorld();
            }
            screenshot("success");
            drain();
            closeOwned();
            drain();
            note("PASS after normal world close; run=" + runId);
        } catch (RuntimeException | Error error) {
            primary = error;
            try { noteFailure(error); } catch (RuntimeException | Error logging) { error.addSuppressed(logging); }
            try { screenshot("failure"); } catch (RuntimeException | Error capture) { error.addSuppressed(capture); }
            throw error;
        } finally {
            if (world != null && !closeAttempted) {
                try { closeOwned(); } catch (RuntimeException | Error cleanup) {
                    if (primary == null) throw cleanup;
                    primary.addSuppressed(cleanup);
                }
            }
            if (primary != null) {
                try { noteFailure(primary); } catch (RuntimeException | Error logging) { primary.addSuppressed(logging); }
            }
            try { retainLog(); } catch (RuntimeException | Error logging) {
                if(primary==null) throw logging;
                primary.addSuppressed(logging);
            }
        }
    }

    private void setup() {
        var ownedServer = world.getServer().computeOnServer(server -> {
            require(server.getOverworld().getSeed() == 1, "Fixture seed is not 1: " + server.getOverworld().getSeed());
            require(server.getOverworld().getChunkManager().getChunkGenerator() instanceof FlatChunkGenerator, "Fixture is not FLAT");
            return server;
        });
        final Path ownedSave;
        try {
            ownedSave = world.getWorldSave().getSaveDirectory().toRealPath();
            var saves = FabricLoader.getInstance().getGameDir().resolve("saves").toRealPath();
            require(ownedSave.startsWith(saves) && !ownedSave.equals(saves)
                    && ownedServer.getSavePath(WorldSavePath.ROOT).toRealPath().equals(ownedSave), "Unowned fixture save: " + ownedSave);
        } catch (IOException error) { throw new UncheckedIOException(error); }
        // Bind this creation before writes; closeOwned clears these only after native disposal.
        var ownedPlayer = context.computeOnClient(client -> {
            require(client.getServer() == ownedServer && client.world != null && client.world.getRegistryKey().equals(World.OVERWORLD)
                    && client.player != null && client.player.getEntityWorld() == client.world
                    && client.getNetworkHandler() != null, "Missing owned fixture client binding");
            bound = client.world; boundHandler = client.getNetworkHandler(); boundConnection = boundHandler.getConnection();
            require(boundConnection.isOpen(), "Closed owned fixture connection");
            return client.player;
        });
        var before = context.computeOnClient(client -> {
            requireFixtureBinding(client, ownedServer, ownedPlayer);
            return fixtureProblem(client.world);
        });
        note("FIXTURE BEFORE writes; save=" + ownedSave + "; consistent FLAT/seed=1; problem=" + before);
        require(before != null, "Fresh owned fixture unexpectedly ready before writes");
        world.getServer().runOnServer(server -> {
            var level = server.getOverworld();
            require(level.getSeed() == 1, "Fixture seed is not 1: " + level.getSeed());
            for (int x=-2;x<=13;x++) for (int z=-2;z<=6;z++) {
                level.setBlockState(new BlockPos(x,64,z), Blocks.BEDROCK.getDefaultState());
                level.setBlockState(new BlockPos(x,65,z), initiallyStone(x,z) ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                for (int y=66;y<=70;y++) level.setBlockState(new BlockPos(x,y,z), Blocks.AIR.getDefaultState());
            }
            if (kind == Kind.REPLACEMENT) {
                var destination = server.getWorld(World.NETHER);
                require(destination != null, "Missing destination dimension");
                for (int x=-4;x<=4;x++) for (int z=-4;z<=4;z++) {
                    destination.setBlockState(new BlockPos(x,64,z), Blocks.BEDROCK.getDefaultState());
                    destination.setBlockState(new BlockPos(x,65,z), Blocks.STONE.getDefaultState());
                    for (int y=66;y<=70;y++) destination.setBlockState(new BlockPos(x,y,z), Blocks.AIR.getDefaultState());
                    require(destination.getBlockState(new BlockPos(x,64,z)).isOf(Blocks.BEDROCK), "Destination floor mismatch");
                    require(destination.getBlockState(new BlockPos(x,65,z)).isOf(Blocks.STONE), "Destination pad mismatch");
                    for (int y=66;y<=70;y++) require(destination.getBlockState(new BlockPos(x,y,z)).isAir(), "Destination clearance mismatch");
                }
            }
            var p = server.getPlayerManager().getPlayerList().getFirst();
            p.changeGameMode(GameMode.SURVIVAL); p.setHealth(20); p.getHungerManager().setFoodLevel(20);
            p.getInventory().clear(); p.getInventory().setStack(0,new ItemStack(Items.STONE,7));
            p.getInventory().setStack(1,new ItemStack(fixture.usableItem(),fixture.stock())); p.getInventory().setSelectedSlot(fixture.selected());
            p.getInventory().markDirty(); p.currentScreenHandler.sendContentUpdates();
            require(p.teleport(level,.5,66,2.5,java.util.Set.of(),0,60,false), "Setup teleport rejected");
        });
        context.runOnClient(client -> {
            requireFixtureBinding(client, ownedServer, ownedPlayer);
            client.player.getInventory().setSelectedSlot(fixture.selected());
        });
        final int waited;
        try {
            waited = context.waitFor(client -> {
                requireFixtureBinding(client, ownedServer, ownedPlayer);
                return client.interactionManager != null && client.currentScreen == null
                        && !client.isPaused() && client.interactionManager.getCurrentGameMode() == GameMode.SURVIVAL
                        && client.player.squaredDistanceTo(.5,66,2.5) < .0001 && Math.abs(client.player.getYaw()) < .01
                        && client.player.getInventory().getSelectedSlot()==fixture.selected() && client.player.getInventory().getStack(0).isOf(Items.STONE)
                        && client.player.getInventory().getStack(0).getCount()==7 && client.player.getInventory().getStack(1).isOf(fixture.usableItem())
                        && client.player.getInventory().getStack(1).getCount()==fixture.stock() && client.player.isOnGround()
                        && fixtureProblem(client.world) == null;
            }, WAIT);
        } catch (RuntimeException | Error primary) {
            try {
                note("FIXTURE readiness failed; waitFor did not return; budgetTicks=" + WAIT + "; before=" + before
                        + "; " + context.computeOnClient(client -> fixtureDiagnostic(client, ownedServer, ownedPlayer)));
            } catch (RuntimeException | Error diagnostic) { primary.addSuppressed(diagnostic); }
            throw primary;
        }
        context.runOnClient(client -> {
            requireFixtureBinding(client, ownedServer, ownedPlayer);
            clientReach = client.player.getBlockInteractionRange();
            require(!client.player.getAbilities().flying && !client.player.getAbilities().allowFlying, "Not ordinary survival");
            var problem = fixtureProblem(client.world);
            require(problem == null, "Client fixture mismatch: " + problem);
            for (int slot=2;slot<client.player.getInventory().size();slot++) require(client.player.getInventory().getStack(slot).isEmpty(), "Client extra inventory");
        });
        note("FIXTURE READY; elapsedTicks=" + waited + "; before=" + before + "; "
                + context.computeOnClient(client -> fixtureDiagnostic(client, ownedServer, ownedPlayer)));
        serverReach = world.getServer().computeOnServer(server -> {
            var p = server.getPlayerManager().getPlayerList().getFirst();
            require(p.interactionManager.getGameMode()==GameMode.SURVIVAL && !p.getAbilities().flying
                    && !p.getAbilities().allowFlying && p.getInventory().getSelectedSlot()==fixture.selected()
                    && p.squaredDistanceTo(.5,66,2.5)<.0001 && Math.abs(p.getYaw())<.01, "Server survival/pose/inventory mismatch");
            require(p.getInventory().getStack(0).isOf(Items.STONE) && p.getInventory().getStack(0).getCount()==7
                    && p.getInventory().getStack(1).isOf(fixture.usableItem()) && p.getInventory().getStack(1).getCount()==fixture.stock(), "Server stock mismatch");
            for (int slot=2;slot<p.getInventory().size();slot++) require(p.getInventory().getStack(slot).isEmpty(), "Server extra inventory");
            for (int x=-2;x<=13;x++) for (int z=-2;z<=6;z++) {
                var level=server.getOverworld(); boolean stone=initiallyStone(x,z);
                require(level.getBlockState(new BlockPos(x,64,z)).isOf(Blocks.BEDROCK), "Server floor mismatch");
                require(stone ? level.getBlockState(new BlockPos(x,65,z)).isOf(Blocks.STONE)
                        : level.getBlockState(new BlockPos(x,65,z)).isAir(), "Server target/apron mismatch");
                for(int y=66;y<=70;y++) require(level.getBlockState(new BlockPos(x,y,z)).isAir(), "Server clearance mismatch");
            }
            return p.getBlockInteractionRange();
        });
        context.waitTicks(3);
        note("INITIAL VALIDATED: survival; seed=1; player=.5,66,2.5; yaw=0; selected="+fixture.selected()+"; matching stone sentinel=7; usable="
                + fixture.usableItem()+fixture.stock() + "; measured clientReach=" + clientReach + "; serverReach=" + serverReach);
    }

    private void requireFixtureBinding(MinecraftClient client, MinecraftServer ownedServer, ClientPlayerEntity ownedPlayer) {
        require(client.getServer() == ownedServer && client.world == bound && client.player == ownedPlayer
                && ownedPlayer.getEntityWorld() == bound && client.getNetworkHandler() == boundHandler
                && boundHandler.getConnection() == boundConnection && boundConnection.isOpen(), "Owned fixture binding replaced");
    }

    private String fixtureProblem(ClientWorld level) {
        if (level == null) return "world=null; expected owned overworld";
        for (int x=-1;x<=0;x++) for (int z=-1;z<=0;z++)
            if (level.getChunkManager().getChunk(x,z,ChunkStatus.FULL,false) == null)
                return "chunk=" + x + "," + z + "; expected=FULL; actual=unloaded";
        for (int x=-2;x<=13;x++) for (int z=-2;z<=6;z++) for (int y=64;y<=70;y++) {
            var pos = new BlockPos(x,y,z);
            var actual = level.getBlockState(pos);
            var expected = y==64 ? Blocks.BEDROCK : y==65 && initiallyStone(x,z) ? Blocks.STONE : Blocks.AIR;
            if (!(expected == Blocks.AIR ? actual.isAir() : actual.isOf(expected)))
                return "chunk=" + (x>>4) + "," + (z>>4) + "; pos=" + pos + "; expected=" + expected.getDefaultState() + "; actual=" + actual;
        }
        return null;
    }

    private String fixtureDiagnostic(MinecraftClient client, MinecraftServer ownedServer, ClientPlayerEntity ownedPlayer) {
        var chunks = new StringBuilder();
        for (int x=-1;x<=0;x++) for (int z=-1;z<=0;z++) chunks.append(x).append(',').append(z).append('=')
                .append(client.world != null && client.world.getChunkManager().getChunk(x,z,ChunkStatus.FULL,false) != null).append(';');
        return "serverMatches=" + (client.getServer()==ownedServer) + "; world=" + System.identityHashCode(client.world)
                + "; ownedWorld=" + System.identityHashCode(bound) + "; dimension=" + (client.world==null?null:client.world.getRegistryKey())
                + "; playerMatches=" + (client.player==ownedPlayer) + "; playerWorldMatches=" + (ownedPlayer.getEntityWorld()==bound)
                + "; handlerMatches=" + (client.getNetworkHandler()==boundHandler)
                + "; connectionMatches=" + (client.getNetworkHandler()!=null && client.getNetworkHandler().getConnection()==boundConnection)
                + "; connectionOpen=" + boundConnection.isOpen() + "; FULL chunks=" + chunks + "; problem=" + fixtureProblem(client.world);
    }

    private void start() {
        var ownedServer=world.getServer().computeOnServer(server -> server);
        var ownedSave=world.getWorldSave().getSaveDirectory().toAbsolutePath().normalize();
        context.runOnClient(client -> {
            require(client.world == bound && !client.isPaused() && client.currentScreen == null, "Binding changed before start");
            require(!PredictionFaults.snapshot().active(), "Fault leaked into fresh fixture");
            if(faultCase()) {
                var prediction=(PendingPredictionView)((ClientWorldPendingUpdateAccessor)bound).mapart$getPendingUpdateManager();
                for(var target:PLAN.targets()) require(!prediction.mapart$hasPendingPrediction(new BlockPos(
                        target.relativePos().x(),65,target.relativePos().z())), "Pre-start pending target");
            }
            runId = MapArtClient.start(PLAN,ORIGIN,SETTINGS);
            watch.arm(client,PLAN);
            if(stateCase()) {
                boolean repeated=kind==Kind.REPEATED_STATE;
                refused(() -> PredictionFaults.armState(client,ownedServer,ownedSave.resolve("unmanaged"),runId,PLAN,fixture.missingTarget(),repeated),"unmanaged save");
                refused(() -> PredictionFaults.armState(client,ownedServer,ownedSave,UUID.randomUUID(),PLAN,fixture.missingTarget(),repeated),"wrong run");
                PredictionFaults.armState(client,ownedServer,ownedSave,runId,PLAN,fixture.missingTarget(),repeated);
                var armed=PredictionFaults.stateSnapshot();
                refused(() -> PredictionFaults.armState(client,ownedServer,ownedSave,runId,PLAN,fixture.missingTarget(),repeated),"duplicate activation");
                refused(() -> PredictionFaults.arm(client,ownedServer,ownedSave,runId),"concurrent response mode");
                require(PredictionFaults.stateSnapshot().equals(armed),"Refused state activation mutated gate");
                if(repeated) {
                    var hit=new BlockHitResult(new Vec3d(.5,65,.5),Direction.UP,new BlockPos(0,64,0),false);
                    var result=client.interactionManager.interactBlock(client.player,Hand.MAIN_HAND,hit);
                    PredictionFaults.recordFirstNativePrediction(result.isAccepted());
                }
                note("STATE ARMED nativeOnly="+repeated+"; "+PredictionFaults.stateSnapshot());
            }
            if(kind==Kind.WITHHELD_RESPONSE) {
                refused(() -> PredictionFaults.arm(client,ownedServer,ownedSave.resolve("unmanaged"),runId),"unmanaged save");
                refused(() -> PredictionFaults.arm(client,ownedServer,ownedSave,UUID.randomUUID()),"wrong run");
                PredictionFaults.arm(client,ownedServer,ownedSave,runId);
                var armed=PredictionFaults.snapshot();
                refused(() -> PredictionFaults.arm(client,ownedServer,ownedSave,runId),"duplicate activation");
                require(PredictionFaults.snapshot().equals(armed),"Refused activation mutated gate");
                note("FAULT ARMED: simulated cumulative-response withholding; refusals=unmanaged-save,wrong-run,duplicate; "+armed);
            }
            if(computedCase()) {
                refused(() -> PredictionFaults.armComputed(client,ownedServer,ownedSave.resolve("unmanaged"),runId,PLAN,kind==Kind.COMPUTED_PAUSE_RESUME),"unmanaged save");
                refused(() -> PredictionFaults.armComputed(client,ownedServer,ownedSave,UUID.randomUUID(),PLAN,kind==Kind.COMPUTED_PAUSE_RESUME),"wrong run");
                refused(() -> PredictionFaults.armComputed(client,ownedServer,ownedSave,runId,new BuildPlan("wrong-fingerprint",PLAN.dimensions(),PLAN.targets()),false),"wrong plan fingerprint");
                refused(() -> PredictionFaults.armComputed(client,ownedServer,ownedSave,runId,new BuildPlan(PLAN.fingerprint(),PLAN.dimensions(),PLAN.targets().stream().filter(t -> !t.relativePos().equals(new GridPos(11,0,0))).toList()),false),"absent coordinate");
                PredictionFaults.armComputed(client,ownedServer,ownedSave,runId,PLAN,kind==Kind.COMPUTED_PAUSE_RESUME);
                var armed=PredictionFaults.snapshot();
                refused(() -> PredictionFaults.armComputed(client,ownedServer,ownedSave,runId,PLAN,true),"duplicate activation");
                refused(() -> PredictionFaults.arm(client,ownedServer,ownedSave,runId),"concurrent response mode");
                require(PredictionFaults.snapshot().equals(armed),"Refusal mutated computed gate");
                note("FAULT ARMED: single-use actual computed decision barrier; "+armed);
            }
            if(kind==Kind.WITHHELD_OBSERVATION) {
                refused(() -> PredictionFaults.armObservation(client,ownedServer,ownedSave.resolve("unmanaged"),runId,PLAN),"unmanaged save");
                refused(() -> PredictionFaults.armObservation(client,ownedServer,ownedSave,UUID.randomUUID(),PLAN),"wrong run");
                refused(() -> PredictionFaults.armObservation(client,ownedServer,ownedSave,runId,new BuildPlan("wrong-fingerprint",PLAN.dimensions(),PLAN.targets())),"wrong plan fingerprint");
                refused(() -> PredictionFaults.armObservation(client,ownedServer,ownedSave,runId,new BuildPlan(PLAN.fingerprint(),PLAN.dimensions(),PLAN.targets().stream().filter(t -> !t.relativePos().equals(new GridPos(11,0,0))).toList())),"absent coordinate");
                PredictionFaults.armObservation(client,ownedServer,ownedSave,runId,PLAN);
                var armed=PredictionFaults.snapshot();
                refused(() -> PredictionFaults.armObservation(client,ownedServer,ownedSave,runId,PLAN),"duplicate activation");
                refused(() -> PredictionFaults.arm(client,ownedServer,ownedSave,runId),"concurrent response mode");
                require(PredictionFaults.snapshot().equals(armed),"Refused activation mutated observation gate");
                note("FAULT ARMED: simulated producer omission"+fixture.missingTarget()+"; refusals=unmanaged-save,wrong-run,duplicate,response-mode; "+armed);
            }
        });
        writtenSamples=0;
        note("START PRODUCTION run=" + runId + "; origin=" + ORIGIN + "; settings=" + SETTINGS.fingerprint());
        read();
    }

    private static void refused(Runnable activation,String why) {
        var before=PredictionFaults.snapshot();
        try { activation.run(); } catch(IllegalStateException expected) { require(PredictionFaults.snapshot().equals(before),"Refused "+why+" mutated gate"); return; }
        throw new AssertionError("Fault accepted "+why);
    }

    private void verifyStateBoundaries(boolean repeated) {
        var state=context.computeOnClient(c -> PredictionFaults.stateSnapshot());
        require(state.active() && state.failure().isEmpty(),"State gate failed: "+state);
        var air=Blocks.AIR.getDefaultState().toString();
        var rolledBack=state.events().stream().filter(e -> e.boundary().equals("POST")
                && !e.sample().pending() && e.sample().state().equals(air)
                && state.events().stream().anyMatch(pre -> pre.boundary().equals("PRE") && pre.sequence()==e.sequence()
                    && pre.ordinal()<e.ordinal() && pre.sample().pending())).findFirst().orElseThrow();
        require(state.events().stream().anyMatch(e -> e.boundary().equals("PRE") && e.sequence()==rolledBack.sequence()
                && e.ordinal()<rolledBack.ordinal() && e.sample().pending()
                && e.sample().state().equals(repeated?PredictionFaults.snowState(2):Blocks.STONE.getDefaultState().toString())),"No actual normal PRE/POST rollback");
        if(repeated) {
            require(state.n2()>state.n1(),"Missing actual newer native sequence");
            var older=state.events().stream().filter(e -> e.boundary().equals("POST") && e.sequence()==state.n1()).findFirst().orElseThrow();
            var first=state.events().stream().filter(e -> e.boundary().equals("FIRST_NATIVE")).findFirst().orElseThrow();
            var pre=state.events().stream().filter(e -> e.boundary().equals("PRE") && e.sequence()==state.n1()).findFirst().orElseThrow();
            var second=state.events().stream().filter(e -> e.boundary().equals("SECOND_NATIVE")).findFirst().orElseThrow();
            require(older.sample().pending() && older.sample().state().equals(PredictionFaults.snowState(2))
                    && older.ordinal()<rolledBack.ordinal() && rolledBack.sequence()>=state.n2()
                    && first.ordinal()<pre.ordinal() && pre.ordinal()<second.ordinal() && second.ordinal()<older.ordinal()
                    && first.sample().managerSequence()==state.n1() && second.sample().managerSequence()==state.n2()
                    && state.stage().equals("COMPLETE"),"Older response lost latest prediction/original baseline");
            for(int layers=1;layers<=2;layers++) {
                String expected=PredictionFaults.snowState(layers);
                require(state.incoming().stream().anyMatch(e -> e.state().equals(expected)),"Missing actual suppressed "+expected);
            }
        } else require(state.incoming().stream().anyMatch(e -> e.state().equals(Blocks.STONE.getDefaultState().toString())),
                "Missing actual suppressed matching stone");
        note("STATE BOUNDARIES nativeOnly="+repeated+"; "+state);
    }

    private void withheldState() {
        String forced=System.getenv("MAPART_STATE_FAULT_FORCE_TARGET_RELOAD");
        require(forced==null || forced.isEmpty() || forced.equals("1"),"Invalid MAPART_STATE_FAULT_FORCE_TARGET_RELOAD");
        captureStateMismatch(false);
        verifyStateBoundaries(false);
        awaitStateServer(false);
        idlePhysics("ack-only real stop",20);
        stateDiagnostics();
        if("1".equals(forced)) forcedImportNegative();
    }

    private void repeatedState() {
        captureStateMismatch(true);
        verifyStateBoundaries(true);
        awaitStateServer(true);
        idlePhysics("native-only repeat real stop",20);
        stateDiagnostics();
    }

    private void captureStateMismatch(boolean repeated) {
        for(int waited=0;waited<200;waited++) {
            boolean captured=context.computeOnClient(c -> {
                var gate=PredictionFaults.stateSnapshot();
                require(gate.active() && gate.failure().isEmpty(),"State fault invalidated before production mismatch: "+gate);
                var samples=watch.copy();
                var accepted=samples.stream().flatMap(s -> s.status().engine().effectResults().stream()).distinct()
                        .filter(a -> a.runId().equals(runId) && a.status()==BuildEngine.EffectStatus.ACCEPTED).toList();
                require(accepted.size()<=(repeated?0:1),"Production retry/action obscured native proof: "+accepted);
                var rollback=gate.events().stream().filter(e -> e.boundary().equals("POST") && !e.sample().pending()
                        && e.sample().state().equals(Blocks.AIR.getDefaultState().toString())
                        && gate.events().stream().anyMatch(pre -> pre.boundary().equals("PRE") && pre.sequence()==e.sequence()
                            && pre.ordinal()<e.ordinal() && pre.sample().pending())).findFirst();
                if(rollback.isEmpty() || repeated && !gate.stage().equals("COMPLETE")) return false;
                var end=samples.stream().filter(s -> s.phase().equals("END")
                        && s.status().engine().eligibleTicks()>rollback.orElseThrow().sample().eligible()
                        && s.status().engine().pendingTargetIds().isEmpty() && s.status().engine().unfinishedTargetIds().equals(List.of(fixture.missingTarget()))
                        && s.observations().stream().anyMatch(o -> o.targetId()==fixture.missingTarget() && o.evidence()==OBSERVED
                            && o.observedState().orElseThrow().id().equals("minecraft:air"))).reduce((a,b) -> b);
                if(end.isEmpty()) return false;
                var actual=end.orElseThrow(); var engine=actual.status().engine();
                var before=samples.stream().filter(s -> s.phase().equals("START") && s.tick()==actual.tick()).findFirst().orElseThrow();
                require(engine.lifecycle().phase()==Phase.RUNNING && actual.status().failure().isEmpty()
                        && engine.eligibleTicks()==before.status().engine().eligibleTicks()+1
                        && MapArtClient.status().engine().equals(engine),"Mismatch was not the current original production callback");
                if(!repeated) {
                    require(accepted.size()==1 && accepted.getFirst().targetId()==fixture.missingTarget(),"Missing real production ACCEPTED"+fixture.missingTarget());
                    var action=accepted.getFirst();
                    var predicted=samples.stream().filter(s -> s.phase().equals("END") && evidence(s,fixture.missingTarget(),PREDICTED)
                            && s.status().engine().pendingTargetIds().equals(java.util.Set.of(fixture.missingTarget()))).findFirst().orElseThrow();
                    require(action.runId().equals(runId) && action.tick()>=0 && predicted.tick()<actual.tick()
                            && predicted.status().engine().eligibleTicks()<engine.eligibleTicks()
                            && before.status().engine().pendingTargetIds().contains(fixture.missingTarget()),"No original prediction→production mismatch chain");
                    note("STATE REAL ACCEPTED="+action+"; ORIGINAL PREDICTED END="+predicted.line());
                }
                note("STATE PRODUCTION MISMATCH nativeOnly="+repeated+"; ROLLBACK="+rollback.orElseThrow()
                        +"\nORIGINAL START="+before.line()+"\nORIGINAL END="+actual.line());
                MapArtClient.stop();
                return true;
            });
            if(captured) { cancelled("state mismatch captured"); return; }
            step();
        }
        throw new AssertionError("No unambiguous production OBSERVED air before retry within state window");
    }

    private void awaitStateServer(boolean repeated) {
        for(int waited=0;waited<40;waited++) {
            var s=read();
            boolean ready=repeated ? world.getServer().computeOnServer(server -> server.getOverworld().getBlockState(new BlockPos(0,65,0))
                    .toString().equals(PredictionFaults.snowState(2))) : s.server().blocks().stream().allMatch(Boolean::booleanValue);
            if(ready && s.server().usable()==(repeated?0:63) && s.client().pose().usable()==(repeated?0:63)) {
                stateSettled=s;
                note("STATE INDEPENDENT SERVER nativeOnly="+repeated+"; "+s.server()); return;
            }
            step();
        }
        throw new AssertionError("State proof server state/inventory did not converge");
    }

    private void stateTerminalCondition(Step s) {
        require(s.client().status().engine().lifecycle().outcome().orElseThrow()==Outcome.CANCELLED
                && s.client().pose().reserved()==7 && s.server().reserved()==7,"State stop/run/sentinel changed");
        require(s.client().observations().stream().anyMatch(o -> o.targetId()==fixture.missingTarget() && o.evidence()==OBSERVED
                && o.observedState().orElseThrow().id().equals("minecraft:air")),"Stopped state gate did not retain original air");
        context.runOnClient(c -> require(!((PendingPredictionView)((ClientWorldPendingUpdateAccessor)bound).mapart$getPendingUpdateManager())
                .mapart$hasPendingPrediction(new BlockPos(0,65,0)),"Stopped state target regained prediction"));
        if(kind==Kind.REPEATED_STATE) require(java.util.stream.IntStream.range(0,PLAN.targets().size()).filter(i -> PLAN.targets().get(i).id()!=fixture.missingTarget()).allMatch(i -> s.server().blocks().get(i)),"Repeated case changed other59 stone");
        if(stateSettled!=null) {
            require(s.server().blocks().equals(stateSettled.server().blocks()) && s.server().usable()==stateSettled.server().usable()
                    && s.client().pose().usable()==stateSettled.client().pose().usable(),"Settled state server truth/inventory changed during release");
            if(kind==Kind.REPEATED_STATE) require(world.getServer().computeOnServer(server -> server.getOverworld().getBlockState(new BlockPos(0,65,0))
                    .toString().equals(PredictionFaults.snowState(2))),"Server lost actual layers2 during release");
        }
    }

    private void stateDiagnostics() {
        var state=context.computeOnClient(c -> PredictionFaults.stateSnapshot());
        require(state.active() && state.failure().isEmpty(),"State gate failed during release");
        require(context.computeOnClient(c -> watch.copy()).stream().flatMap(s -> s.status().engine().effectResults().stream()).distinct()
                .filter(a -> a.runId().equals(runId) && a.status()==BuildEngine.EffectStatus.ACCEPTED).count()==(kind==Kind.REPEATED_STATE?0:1),
                "State run gained unexpected production ACCEPTED action");
        for(var route:PredictionFaults.StateRoute.values()) note("STATE ACTUAL ROUTE "+route+"="+state.incoming().stream().filter(e -> e.route()==route).count());
        write("state-diagnostics.txt",state.toString());
    }

    private record ReloadSample(PredictionFaults.StateSnapshot gate, String state, boolean pending, boolean sameBinding, Sample observer) { }

    private ReloadSample collectForcedReload() {
        return context.computeOnClient(c -> new ReloadSample(PredictionFaults.stateSnapshot(),c.world.getBlockState(new BlockPos(0,65,0)).toString(),
                ((PendingPredictionView)((ClientWorldPendingUpdateAccessor)c.world).mapart$getPendingUpdateManager()).mapart$hasPendingPrediction(new BlockPos(0,65,0)),
                c.world==bound && c.getNetworkHandler()==boundHandler && boundHandler.getConnection()==boundConnection && boundConnection.isOpen(),
                watch.sample(c,"FORCED_RELOAD_NEGATIVE_ONLY")));
    }

    private void forcedImportNegative() {
        var before=context.computeOnClient(c -> PredictionFaults.stateSnapshot());
        require(before.active() && before.failure().isEmpty(),"Forced resend requires still-valid gate after release20");
        var playerId=context.computeOnClient(c -> c.player.getUuid());
        world.getServer().runOnServer(server -> {
            var player=server.getPlayerManager().getPlayer(playerId);
            require(player!=null && server.getPlayerManager().getPlayerList().size()==1
                    && server.getPlayerManager().getPlayerList().getFirst()==player && player.getEntityWorld()==server.getOverworld(),"Forced resend wrong owned player/world");
            long chunkPos=ChunkPos.toLong(0,0);
            var chunk=server.getOverworld().getChunkManager().chunkLoadingManager.getPostProcessedChunk(chunkPos);
            require(chunk!=null && chunk.getPos().x==0 && chunk.getPos().z==0
                    && chunk.getBlockState(new BlockPos(0,65,0)).equals(Blocks.STONE.getDefaultState()),"Forced resend lacks actual loaded postprocessed stone chunk");
            var sender=player.networkHandler.chunkDataSender;
            require(!sender.isInNextBatch(chunkPos),"Target chunk already queued before forced resend");
            sender.add(chunk);
            require(sender.isInNextBatch(chunkPos),"Native sender did not queue actual target chunk");
        });
        note("TEST-FORCED NATIVE RESEND: actual loaded target chunk queued once; ordinary server ticks/ACKs drain sender");
        for(int waited=0;waited<200;waited++) {
            context.waitTick();
            var sample=collectForcedReload();
            require(sample.sameBinding(),"Forced negative lost world/connection scope");
            var gate=sample.gate();
            if(gate.failure().isPresent()) {
                require(gate.failure().orElseThrow().equals("TARGET_CHUNK_RELOAD") && !gate.active(),"Wrong forced negative fatal: "+gate);
                var imported=gate.imported().orElseThrow();
                require(imported.loaded() && imported.chunkX()==0 && imported.chunkZ()==0 && imported.identity().equals(before.identity())
                        && imported.age()-before.identity().armedAge()>=0 && imported.age()-before.identity().armedAge()<=200,"No actual armed/loaded BEFORE-import proof");
                require(sample.observer().status().engine().lifecycle().outcome().orElseThrow()==Outcome.CANCELLED,"Forced negative changed stopped outcome");
                released(sample.observer(),"forced negative");
                if(sample.state().equals(Blocks.STONE.getDefaultState().toString()) && !sample.pending()) {
                    note("FORCED IMPORT VERIFIED: first fatal retained despite later matching client state; no exclusive payload attribution; "+sample);
                    write("state-diagnostics.txt",gate.toString());
                    throw new AssertionError("DELIBERATE TARGET_CHUNK_RELOAD: armed loaded BEFORE-import guard remained fatal after matching client stone");
                }
            } else require(gate.active(),"Forced negative silently deactivated gate");
        }
        throw new AssertionError("Missing armed native import/matching state within original forced-negative window");
    }

    private void withheldResponse() {
        var first=await(s -> evidence(s.client(),fixture.missingTarget(),PREDICTED) && s.server().blocks().stream().allMatch(Boolean::booleanValue)
                && s.server().usable()==63 && s.client().pose().usable()==63
                && !context.computeOnClient(c -> PredictionFaults.snapshot().responses()).isEmpty(),
                WAIT,"real predicted placement, withheld response and all60 server blocks");
        long previous=first.client().status().engine().eligibleTicks();
        int activeSamples=0;
        var current=first;
        for(int waited=0;waited<120;waited++) {
            faultCondition(current);
            var engine=current.client().status().engine();
            if(engine.lifecycle().phase()==Phase.TERMINAL) break;
            require(engine.lifecycle().phase()==Phase.RUNNING && engine.eligibleTicks()==previous,
                    "Fault active sample was not the next eligible callback");
            activeSamples++;
            current=step();
            previous++;
        }
        var terminal=current.client().status().engine();
        require(activeSamples>=20,"Fewer than20 successive actual eligible matching-prediction samples: "+activeSamples);
        require(terminal.lifecycle().phase()==Phase.TERMINAL && terminal.lifecycle().outcome().orElseThrow()==Outcome.FAILED,
                "Held prediction did not reach actual FAILED: "+terminal);
        var diagnostic=terminal.diagnostic().orElseThrow();
        require(diagnostic.reason()==BuildEngine.Reason.PENDING_EVIDENCE_TIMEOUT && diagnostic.targetIds().equals(List.of(fixture.missingTarget()))
                && diagnostic.detail().equals("Pending evidence reached eligible age 80"),"Wrong fault timeout: "+terminal);
        var samples=context.computeOnClient(c -> watch.copy());
        var action=samples.stream().flatMap(s -> s.status().engine().effectResults().stream())
                .filter(r -> r.runId().equals(runId) && r.targetId()==fixture.missingTarget() && r.status()==BuildEngine.EffectStatus.ACCEPTED)
                .findFirst().orElseThrow();
        require(samples.stream().anyMatch(s -> s.phase().equals("END") && evidence(s,fixture.missingTarget(),PREDICTED)
                && s.status().engine().pendingTargetIds().equals(java.util.Set.of(fixture.missingTarget()))),"No original effect-phase matching prediction");
        idlePhysics("held prediction pending80 failure",20);
        var gate=context.computeOnClient(c -> PredictionFaults.snapshot());
        require(gate.active() && !gate.responses().isEmpty(),"No real withheld response invocation");
        note("FAULT VERIFIED all60 server blocks correct; activeSuccessiveSamples="+activeSamples+"; realAction="+action
                +"; terminal="+terminal+"; gate="+gate+"; issuance eligible age80 is the core diagnostic, not feedback receipt time");
    }

    private void faultCondition(Step s) {
        var engine=s.client().status().engine();
        require(s.client().status().failure().isEmpty() && engine.lifecycle().outcome().orElse(null)!=Outcome.SUCCESS,
                "Matching prediction became SUCCESS or adapter failure");
        require(engine.unfinishedTargetIds().equals(List.of(fixture.missingTarget())) && evidence(s.client(),fixture.missingTarget(),PREDICTED),
                "Prediction was confirmed, changed, or unrelated targets remain unfinished: "+s.client().line());
        if(engine.lifecycle().phase()==Phase.RUNNING) require(engine.pendingTargetIds().equals(java.util.Set.of(fixture.missingTarget())),"Missing pending ID"+fixture.missingTarget());
        require(s.server().blocks().stream().allMatch(Boolean::booleanValue) && s.server().reserved()==7 && s.server().usable()==63
                && s.client().pose().reserved()==7 && s.client().pose().usable()==63,"All60 server truth/inventory not correct: "+s);
        context.runOnClient(c -> {
            var prediction=(PendingPredictionView)((ClientWorldPendingUpdateAccessor)bound).mapart$getPendingUpdateManager();
            var target=new BlockPos(0,65,0);
            require(prediction.mapart$hasPendingPrediction(target) && prediction.mapart$hasPendingPrediction(target)
                    && bound.getBlockState(target).equals(Blocks.STONE.getDefaultState()),"Actual repeated read-only pending query lost matching prediction");
        });
    }

    private void withheldObservation() {
        String expected=System.getenv("MAPART_WITHHELD_OBSERVATION_EXPECT_SUCCESS");
        require(expected==null || expected.isEmpty() || expected.equals("1"),"Invalid MAPART_WITHHELD_OBSERVATION_EXPECT_SUCCESS");
        var current=await(s -> context.computeOnClient(c -> PredictionFaults.snapshot().omissions()).stream()
                        .anyMatch(o -> o.removed().evidence()==PREDICTED)
                && s.server().blocks().stream().allMatch(Boolean::booleanValue)
                && s.server().usable()==63 && s.client().pose().usable()==63,WAIT,"real ID"+fixture.missingTarget()+" prediction and all60 server truth");
        long previous=current.client().status().engine().eligibleTicks();
        int activeSamples=0,verifySamples=0;
        for(int waited=0;waited<120;waited++) {
            observationCondition(current);
            var engine=current.client().status().engine();
            if(engine.lifecycle().phase()==Phase.TERMINAL) break;
            require(engine.lifecycle().phase()==Phase.RUNNING && engine.eligibleTicks()==previous,"Nonconsecutive omission eligible sample");
            activeSamples++;
            if(verifySamples>0) require(engine.lifecycle().activity().orElseThrow()==Activity.VERIFY,"Left VERIFY before terminal");
            if(engine.lifecycle().activity().orElseThrow()==Activity.VERIFY) verifySamples++;
            current=step(); previous++;
        }
        var terminal=current.client().status().engine();
        require(terminal.lifecycle().phase()==Phase.TERMINAL && terminal.lifecycle().outcome().orElseThrow()==Outcome.FAILED,
                "Missing producer evidence did not fail: "+terminal);
        var diagnostic=terminal.diagnostic().orElseThrow();
        require(diagnostic.reason()==BuildEngine.Reason.PENDING_EVIDENCE_TIMEOUT && diagnostic.targetIds().equals(List.of(fixture.missingTarget()))
                && diagnostic.detail().equals("Pending evidence reached eligible age 80") && terminal.verifyTicks()>0,"Wrong omission timeout: "+terminal);
        var samples=context.computeOnClient(c -> watch.copy());
        var issuance=samples.stream().filter(s -> s.phase().equals("END") && s.status().engine().pendingTargetIds().contains(fixture.missingTarget())).findFirst().orElseThrow();
        var before=samples.stream().filter(s -> s.tick()==issuance.tick() && s.phase().equals("START")).findFirst().orElseThrow();
        long e=issuance.status().engine().eligibleTicks();
        require(!before.status().engine().pendingTargetIds().contains(fixture.missingTarget()) && before.status().engine().eligibleTicks()+1==e,
                "Issuance E not established by one real START/END callback");
        var action=samples.stream().flatMap(s -> s.status().engine().effectResults().stream()).distinct()
                .filter(r -> r.runId().equals(runId) && r.targetId()==fixture.missingTarget() && r.status()==BuildEngine.EffectStatus.ACCEPTED).toList();
        require(action.size()==1,"Expected one native ACCEPTED ID"+fixture.missingTarget()+" action: "+action);
        var verify=samples.stream().filter(s -> s.phase().equals("END") && s.status().engine().lifecycle().activity().orElse(null)==Activity.VERIFY)
                .findFirst().orElseThrow();
        long v=verify.status().engine().eligibleTicks(),t=terminal.eligibleTicks();
        require(e<=v && v<e+80 && t==e+80 && verifySamples>=20 && activeSamples>=verifySamples,
                "Wrong actual E/V/T or sustained VERIFY: "+e+"/"+v+"/"+t+" samples="+verifySamples);
        require(verify.status().engine().laneEntry().isPresent() && verify.status().engine().pendingTargetIds().equals(java.util.Set.of(fixture.missingTarget()))
                && verify.status().engine().unfinishedTargetIds().equals(List.of(fixture.missingTarget())) && !verify.keys().getFirst(),"No actual movement COMPLETE/VERIFY entry");
        require(maxClientX-.5>clientReach && maxServerX-.5>serverReach,"Omission motion did not exceed initial measured reach");
        note("OMISSION ISSUANCE E="+e+"; action/sample="+action+"; BEFORE="+before.line()+"\nISSUED="+issuance.line()
                +"\nMOVEMENT COMPLETE/VERIFY V="+v+"; "+verify.line()+"\nTERMINAL T="+t+"; "+terminal);
        idlePhysics("withheld observation pending80 failure",20);
        var gate=context.computeOnClient(c -> PredictionFaults.snapshot());
        require(gate.responses().isEmpty(),"Observation mode withheld a response");
        var rawPredicted=gate.omissions().stream().filter(o -> o.removed().evidence()==PREDICTED).findFirst().orElseThrow();
        var rawObserved=gate.omissions().stream().filter(o -> o.conversion()>rawPredicted.conversion() && o.removed().evidence()==OBSERVED
                && o.removed().observedState().orElseThrow().equals(STONE)).findFirst().orElseThrow();
        require(rawPredicted.eligibleTick()==e && rawPredicted.removed().observedState().orElseThrow().equals(STONE)
                && rawObserved.eligibleTick()<t,"No initial prediction then normal pending-key disappearance/raw OBSERVED stone");
        note("OMISSION VERIFIED: E="+e+"; V="+v+"; T="+t+"; consecutiveActive="+activeSamples+"; consecutiveVerify="+verifySamples
                +"; displacement client="+(maxClientX-.5)+" server="+(maxServerX-.5)+"; reaches="+clientReach+","+serverReach
                +"; firstRawPrediction="+rawPredicted+"; laterRawObserved="+rawObserved+"; conversionInvocations="+gate.omissions().size());
        for(var conversion:gate.omissions()) note("OMISSION CONVERSION (not adapter/sample/eligible tick) "+conversion);
        require(!"1".equals(expected) || terminal.lifecycle().outcome().orElseThrow()==Outcome.SUCCESS,
                "Deliberate false SUCCESS expectation: withheld ID"+fixture.missingTarget()+" producer evidence correctly caused PENDING_EVIDENCE_TIMEOUT after VERIFY");
    }

    private void observationCondition(Step s) {
        var engine=s.client().status().engine();
        require(s.client().status().failure().isEmpty() && engine.lifecycle().outcome().orElse(null)!=Outcome.SUCCESS
                && engine.unfinishedTargetIds().equals(List.of(fixture.missingTarget())),"Omission confirmed or unrelated unfinished work: "+s.client().line());
        if(engine.lifecycle().phase()==Phase.RUNNING) require(engine.pendingTargetIds().equals(java.util.Set.of(fixture.missingTarget())),"Missing real pending"+fixture.missingTarget());
        require(s.client().observations().size()==59 && s.client().observations().stream().noneMatch(o -> o.targetId()==fixture.missingTarget()),"Omitted"+fixture.missingTarget()+" escaped producer");
        require(s.server().blocks().stream().allMatch(Boolean::booleanValue) && s.server().reserved()==7 && s.server().usable()==63
                && s.client().pose().reserved()==7 && s.client().pose().usable()==63,"Omission server truth/inventory changed: "+s);
        context.runOnClient(c -> {
            var prediction=(PendingPredictionView)((ClientWorldPendingUpdateAccessor)bound).mapart$getPendingUpdateManager();
            if(engine.lifecycle().phase()==Phase.TERMINAL) require(!prediction.mapart$hasPendingPrediction(new BlockPos(11,65,0)),"Native prediction did not reconcile normally");
        });
    }

    private boolean computedCase() { return kind==Kind.COMPUTED_STOP || kind==Kind.COMPUTED_PAUSE_RESUME; }

    private boolean stateCase() { return kind==Kind.WITHHELD_STATE || kind==Kind.REPEATED_STATE; }

    private boolean faultCase() { return kind==Kind.WITHHELD_RESPONSE || kind==Kind.WITHHELD_OBSERVATION || computedCase() || stateCase(); }

    private void computedBarrier() {
        read();
        for(int ticks=0;ticks<200 && context.computeOnClient(c -> PredictionFaults.snapshot().barrier()).isEmpty();ticks++) step();
        var gate=context.computeOnClient(c -> PredictionFaults.snapshot());
        var barrier=gate.barrier().orElseThrow(() -> new AssertionError("No real translating/native/ID"+fixture.missingTarget()+" decision within owned window"));
        var decision=barrier.decision(); var before=barrier.before();
        var samples=context.computeOnClient(c -> watch.copy());
        var end=samples.stream().filter(s -> s.phase().equals("END") && s.pose()!=null && s.pose().age()==before.pose().age()).findFirst().orElseThrow();
        var start=samples.stream().filter(s -> s.phase().equals("START") && s.tick()==end.tick()).findFirst().orElseThrow();
        require(start.status().controlOwner()==BuildEngine.ControlOwner.NATIVE && start.keys().subList(0,4).stream().anyMatch(Boolean::booleanValue)
                && before.status().controlOwner()==BuildEngine.ControlOwner.NATIVE && before.keys().subList(0,4).stream().anyMatch(Boolean::booleanValue),
                "Barrier lacked prior actual native ownership/controls");
        require(decision.runId().orElseThrow().equals(runId) && decision.placement().orElseThrow().targetId()==fixture.missingTarget()
                && decision.placement().orElseThrow().tick()==decision.tick() && decision.owner()==BuildEngine.ControlOwner.NATIVE
                && decision.movement().filter(m -> m.forward() || m.back() || m.strafe()!=MovementPolicy.Strafe.NONE).isPresent(),"Wrong real computed opportunity");
        released(end,"computed stale callback END");
        require(end.status().engine().eligibleTicks()==decision.snapshot().eligibleTicks() && end.pose().selectedSlot()==0
                && end.pose().reserved()==7 && end.pose().usable()==64 && end.pose().yaw()==before.pose().yaw()
                && end.pose().headYaw()==before.pose().headYaw() && end.pose().bodyYaw()==before.pose().bodyYaw()
                && end.observations().stream().filter(o -> o.targetId()==fixture.missingTarget() && o.evidence()==OBSERVED && o.observedState().orElseThrow().id().equals("minecraft:air")).count()==1,
                "Stale callback changed yaw/slot/inventory/target: "+end.line());
        require(samples.stream().flatMap(s -> s.status().engine().effectResults().stream()).noneMatch(a -> a.runId().equals(runId)
                && a.actionId()==decision.placement().orElseThrow().actionId() && a.status()==BuildEngine.EffectStatus.ACCEPTED),"Captured stale action accepted");
        require(barrier.after().yaw().equals(before.yaw()) && !barrier.after().pending() && barrier.after().air(),"Immediate barrier changed native yaw/target");
        note("COMPUTED BARRIER="+barrier+"\nSAME CALLBACK START="+start.line()+"\nSAME CALLBACK END="+end.line());
        if(kind==Kind.COMPUTED_STOP) {
            require(end.status().engine().lifecycle().outcome().orElseThrow()==Outcome.CANCELLED,"Computed stop did not cancel");
            idlePhysics("computed stop",20);
        } else {
            require(end.status().engine().lifecycle().phase()==Phase.RUNNING && end.status().engine().lifecycle().context().orElseThrow().runId().equals(runId),"Same-UUID resume lost run");
            await(s -> s.client().status().engine().lifecycle().phase()==Phase.TERMINAL,WAIT,"fresh callback verified completion");
            var done=read(); var engine=done.client().status().engine();
            require(engine.lifecycle().outcome().orElseThrow()==Outcome.SUCCESS && engine.diagnostic().orElseThrow().reason()==BuildEngine.Reason.VERIFIED
                    && engine.unfinishedTargetIds().isEmpty() && engine.pendingTargetIds().isEmpty() && done.client().status().failure().isEmpty(),"Fresh callbacks did not verify");
            var later=context.computeOnClient(c -> PredictionFaults.snapshot().fresh());
            var accepted=context.computeOnClient(c -> watch.copy()).stream().flatMap(s -> s.status().engine().effectResults().stream()).distinct()
                    .filter(a -> a.targetId()==fixture.missingTarget() && a.status()==BuildEngine.EffectStatus.ACCEPTED).toList();
            require(accepted.size()==1 && accepted.getFirst().runId().equals(runId) && accepted.getFirst().actionId()>decision.placement().orElseThrow().actionId()
                    && accepted.getFirst().tick()>decision.tick() && later.stream().anyMatch(d -> d.tick()==accepted.getFirst().tick()
                    && d.placement().isPresent() && d.placement().orElseThrow().actionId()==accepted.getFirst().actionId()),"No correlated fresh real decision/accepted action: "+accepted);
            require(done.client().observations().size()==60 && done.client().observations().stream().allMatch(o -> o.evidence()==OBSERVED && o.observedState().orElseThrow().equals(STONE))
                    && done.server().blocks().stream().allMatch(Boolean::booleanValue) && done.server().usable()==63 && done.client().pose().usable()==63
                    && maxClientX-.5>clientReach && maxServerX-.5>serverReach,"Fresh completion truth/inventory/motion mismatch");
            note("FRESH COMPUTED PROGRESS: decisions="+later+"; accepted="+accepted+"; displacement="+(maxClientX-.5)+","+(maxServerX-.5));
            idlePhysics("computed pause/resume success",20);
        }
        gate=context.computeOnClient(c -> PredictionFaults.snapshot());
        require(gate.responses().isEmpty() && gate.omissions().isEmpty(),"Computed mode interfered with response/observation");
        write("fault-diagnostics.txt",gate.toString());
    }

    private void baseline() {
        await(s -> s.client().pose().x()>1.25 && s.client().keys().getFirst(), WAIT, "motion before pause");
        var before = read().client().status().engine();
        context.runOnClient(client -> MapArtClient.pause());
        idlePhysics("explicit pause",20);
        var paused = read().client().status().engine();
        require(paused.lifecycle().phase()==Phase.PAUSED && paused.eligibleTicks()==before.eligibleTicks()
                && paused.laneEntry().equals(before.laneEntry()), "Pause lost traversal/counters");
        context.runOnClient(client -> MapArtClient.resume());
        await(s -> s.client().keys().getFirst(), WAIT, "manual resume controls");
        context.setScreen(() -> new GameMenuScreen(false));
        require(context.computeOnClient(client -> MapArtClient.status().engine().lifecycle().phase())==Phase.PAUSED, "Screen did not pause production");
        var screenPause=context.computeOnClient(client -> watch.sample(client,"SCREEN_OPEN_RELEASE"));
        released(screenPause,"screen open"); note(screenPause.line());
        context.setScreen(() -> null);
        context.waitFor(client -> !client.isPaused(), WAIT);
        idlePhysics("closed screen manual-resume hold",20);
        require(read().client().status().engine().lifecycle().phase()==Phase.PAUSED, "Screen close auto-resumed run");
        context.runOnClient(client -> MapArtClient.resume());
        await(s -> s.client().status().engine().lifecycle().phase()==Phase.TERMINAL,1200,"full native build");
        var done=read(); var engine=done.client().status().engine();
        require(engine.lifecycle().outcome().orElseThrow()==Outcome.SUCCESS && done.client().status().failure().isEmpty()
                && engine.diagnostic().orElseThrow().reason()==BuildEngine.Reason.VERIFIED
                && engine.pendingTargetIds().isEmpty() && engine.unfinishedTargetIds().isEmpty(), "Not verified SUCCESS: " + done.client().status());
        require(maxClientX-.5>clientReach && maxServerX-.5>serverReach, "Native positive-X motion did not exceed measured survival reach");
        var observations=done.client().observations();
        require(observations.size()==60 && observations.stream().map(PlacementObservation::targetId).distinct().count()==60
                && observations.stream().allMatch(o -> o.evidence()==OBSERVED && o.observedState().orElseThrow().equals(STONE)), "Final client full scan mismatch");
        require(done.server().blocks().stream().allMatch(Boolean::booleanValue), "Independent final server blocks mismatch: " + done.server());
        await(s -> s.server().reserved()==7 && s.server().usable()==5 && s.client().pose().reserved()==7
                && s.client().pose().usable()==5, WAIT,"final exact inventory");
        proveChains();
        note("BASELINE VERIFIED: displacement client="+(maxClientX-.5)+" server="+(maxServerX-.5)
                + "; reaches="+clientReach+","+serverReach+"; 60 exact server blocks; reserved0=7; usable1=5");
        idlePhysics("success",20);
    }

    private void proveChains() {
        var samples=context.computeOnClient(client -> watch.copy());
        var verify=samples.stream().filter(s -> s.status().engine().lifecycle().activity().orElse(null)==Activity.VERIFY).findFirst().orElseThrow();
        var terminal=samples.stream().filter(s -> s.status().engine().lifecycle().outcome().orElse(null)==Outcome.SUCCESS).findFirst().orElseThrow();
        require(terminal.tick()>verify.tick() && terminal.status().engine().eligibleTicks()>verify.status().engine().eligibleTicks(), "No later final verification");
        note("VERIFY entry="+verify.line()+"\nLATER TERMINAL="+terminal.line());
        var actions=samples.stream().flatMap(s -> s.status().engine().effectResults().stream()).distinct().toList();
        var outerActions=new ArrayList<Long>();
        for(int z : List.of(0,4)) {
            int id=SingleLaneFixture.targetId(PLAN,new GridPos(0,0,z));
            var predicted=samples.stream().filter(s -> s.phase().equals("END") && evidence(s,id,PREDICTED)).findFirst().orElseThrow();
            var observed=samples.stream().filter(s -> s.tick()>predicted.tick() && evidence(s,id,OBSERVED)).findFirst().orElseThrow();
            var action=actions.stream().filter(a -> a.targetId()==id && a.status()==BuildEngine.EffectStatus.ACCEPTED).findFirst().orElseThrow();
            require(action.runId().equals(runId) && predicted.pose().selectedSlot()==1, "Outer placement identity/usable slot mismatch");
            double distance=predicted.pose().eyes().distanceTo(new Vec3d(.5,65,z+.5));
            require(distance<=clientReach, "Outer deterministic fixture top hit exceeded real reach");
            outerActions.add(action.actionId());
            note("OUTER actual accepted="+action+"; deterministic C6 support=(0,64,"+z+"),UP; hit=(.5,65,"+(z+.5)
                    +"); distance="+distance+"; predicted="+predicted.line()+"; observed="+observed.line());
        }
        require(!outerActions.getFirst().equals(outerActions.getLast()), "Outer columns shared action identity");
        boolean chain=false;
        for(var hold:samples) {
            var entry=hold.status().engine().laneEntry();
            if(hold.pose()==null || entry.isEmpty() || entry.orElseThrow().stage()!=MovementPolicy.Stage.WALK
                    || entry.orElseThrow().supportHoldTicks()==0 || hold.keys().getFirst()) continue;
            int id=SingleLaneFixture.targetId(PLAN,new GridPos((int)Math.floor(hold.pose().x())+1,0,2));
            var prediction=samples.stream().filter(s -> s.tick()>=hold.tick() && evidence(s,id,PREDICTED)
                    && s.status().engine().pendingTargetIds().contains(id)).findFirst();
            if(prediction.isEmpty()) continue;
            var observed=samples.stream().filter(s -> s.tick()>prediction.orElseThrow().tick() && evidence(s,id,OBSERVED)).findFirst();
            if(observed.isEmpty()) continue;
            var motion=samples.stream().filter(s -> s.pose()!=null && s.tick()>observed.orElseThrow().tick()
                    && s.keys().getFirst() && s.pose().x()>observed.orElseThrow().pose().x()+.01).findFirst();
            var action=actions.stream().filter(a -> a.targetId()==id && a.status()==BuildEngine.EffectStatus.ACCEPTED).findFirst();
            if(motion.isPresent() && action.isPresent()) {
                note("HOLD CHAIN target="+id+"; action="+action.orElseThrow()+"\nHOLD="+hold.line()+"\nPLACED="+prediction.orElseThrow().line()
                        +"\nOBSERVED="+observed.orElseThrow().line()+"\nRESUMED MOTION="+motion.orElseThrow().line());
                chain=true; break;
            }
        }
        require(chain,"No actual support hold -> placement/pending -> OBSERVED -> resumed native motion chain");
    }

    private static boolean evidence(Sample s,int id,PlacementObservation.Evidence evidence) {
        return s.observations().stream().anyMatch(o -> o.targetId()==id && o.evidence()==evidence
                && o.observedState().orElseThrow().equals(STONE));
    }

    private void replaceWorld() {
        note("SIMULATED LIFECYCLE FAULT: one real dimension transfer; no baseline motion/build/reach credit");
        world.getServer().runOnServer(server -> {
            var p=server.getPlayerManager().getPlayerList().getFirst();
            var velocity=p.getVelocity();
            require(p.teleportTo(new TeleportTarget(server.getWorld(World.NETHER),new Vec3d(.5,66,.5),velocity,
                    p.getYaw(),p.getPitch(),TeleportTarget.NO_OP))!=null,"Dimension transfer failed");
        });
        transferred=true;
        context.waitFor(client -> client.world!=null && client.world!=bound && client.player!=null
                && client.player.getEntityWorld()==client.world && client.currentScreen==null && !client.isPaused(),WAIT);
        var change=context.computeOnClient(client -> watch.copy()).stream().filter(s -> s.phase().equals("WORLD_CHANGE") && !s.originalWorld()).findFirst().orElseThrow();
        released(change,"synchronous world-change setup");
        require(change.status().engine().lifecycle().outcome().orElseThrow()==Outcome.CANCELLED
                && change.status().failure().isPresent(),"World replacement did not cancel old run with cause");
        note("IMMEDIATE WORLD RELEASE="+change.line());
        cancelled("replacement"); idlePhysics("replacement new-world idle",20);
    }

    private void cancelled(String why) {
        var s=context.computeOnClient(client -> watch.sample(client,"RELEASE:"+why));
        released(s,why);
        require(s.status().engine().lifecycle().outcome().orElseThrow()==Outcome.CANCELLED,"Expected CANCELLED on "+why+": "+s.status());
        require(s.status().engine().lifecycle().context().orElseThrow().runId().equals(runId),"Old run identity changed");
        note(s.line());
    }

    private void idlePhysics(String why,int required) {
        var initial=read(); released(initial.client(),why);
        long eligible=initial.client().status().engine().eligibleTicks();
        int count=0; var previous=initial;
        for(int waited=0;waited<60 && count<required;waited++) {
            var current=step(); released(current.client(),why);
            require(current.client().status().engine().eligibleTicks()==eligible,"Automation advanced after release: "+why);
            require(current.client().status().engine().lifecycle().equals(initial.client().status().engine().lifecycle()),
                    "Lifecycle/run binding changed during idle release: "+why);
            if(current.client().pose()!=null) position(current.client().pose().x(),current.client().pose().y(),current.client().pose().z(),transferred,"idle client "+why);
            position(current.server().x(),current.server().y(),current.server().z(),transferred,"idle server "+why);
            if(current.client().pose()!=null && previous.client().pose()!=null && !current.client().paused()
                    && current.client().pose().age()>previous.client().pose().age()
                    && current.client().pose().worldTime()>previous.client().pose().worldTime()
                    && current.server().age()>previous.server().age() && current.server().tick()>previous.server().tick()) count++;
            previous=current;
        }
        require(count==required,"Did not observe "+required+" actual post-release physics ticks: "+why+"; count="+count);
        note("RELEASE PHYSICS why="+why+"; actualTicks="+count+"; first="+initial+"; last="+previous);
    }

    private static void released(Sample s,String why) {
        require(s.status().controlOwner()==BuildEngine.ControlOwner.NONE && s.keys().stream().noneMatch(Boolean::booleanValue)
                && (s.pose()==null || !s.pose().sprinting()),"Controls not released: "+why+"; "+s.line());
    }

    private record ServerSample(int tick,int age,long time,String dimension,double x,double y,double z,
                                Vec3d velocity,int reserved,int usable,List<Boolean> blocks) { }
    private record Step(Sample client,ServerSample server) { }

    private Step read() {
        var client=context.computeOnClient(c -> watch.sample(c,"POLL"));
        var server=world.getServer().computeOnServer(s -> {
            var p=s.getPlayerManager().getPlayerList().getFirst();
            var blocks=PLAN.targets().stream().map(t -> s.getOverworld().getBlockState(new BlockPos(
                    t.relativePos().x(),65,t.relativePos().z())).equals(Blocks.STONE.getDefaultState())).toList();
            require(p.getInventory().getStack(0).isOf(Items.STONE) && p.getInventory().getStack(0).getCount()==7,"Reserved server stone was consumed");
            require(!p.getAbilities().flying && !p.getAbilities().allowFlying && p.interactionManager.getGameMode()==GameMode.SURVIVAL,"Server left ordinary survival");
            require(p.getInventory().getStack(1).isEmpty() || p.getInventory().getStack(1).isOf(fixture.usableItem()),"Usable server material changed");
            for(int slot=2;slot<p.getInventory().size();slot++) require(p.getInventory().getStack(slot).isEmpty(),"Unexpected server inventory at "+slot);
            return new ServerSample(s.getTicks(),p.age,p.getEntityWorld().getTime(),p.getEntityWorld().getRegistryKey().getValue().toString(),
                    p.getX(),p.getY(),p.getZ(),p.getVelocity(),p.getInventory().getStack(0).getCount(),p.getInventory().getStack(1).getCount(),blocks);
        });
        if(runId!=null) {
            safety(client); position(server.x(),server.y(),server.z(),transferred,"server");
            maxServerX=Math.max(maxServerX,server.x());
        }
        var gate=context.computeOnClient(c -> PredictionFaults.snapshot());
        require(gate.failure().isEmpty(),"Sticky fault fixture error: "+gate);
        if(faultCase() && runId!=null) require(gate.active(),"Fault deactivated before explicit cleanup");
        else require(!gate.active(),"Fault leaked to ordinary fixture");
        if(kind==Kind.WITHHELD_RESPONSE && client.status().engine().lifecycle().phase()==Phase.TERMINAL)
            faultCondition(new Step(client,server));
        if(kind==Kind.WITHHELD_OBSERVATION && client.status().engine().lifecycle().phase()==Phase.TERMINAL)
            observationCondition(new Step(client,server));
        if(computedCase() && client.status().engine().lifecycle().phase()==Phase.TERMINAL) {
            int usable=kind==Kind.COMPUTED_STOP?64:63;
            require(server.usable()==usable && client.pose().usable()==usable && server.reserved()==7 && client.pose().reserved()==7
                    && server.blocks().get(PLAN.targets().stream().map(Target::id).toList().indexOf(fixture.missingTarget()))==(kind==Kind.COMPUTED_PAUSE_RESUME),"Computed terminal target/inventory changed");
            context.runOnClient(c -> require(!((PendingPredictionView)((ClientWorldPendingUpdateAccessor)bound).mapart$getPendingUpdateManager())
                    .mapart$hasPendingPrediction(new BlockPos(11,65,0)),"Computed terminal native prediction remains"));
        }
        if(stateCase() && client.status().engine().lifecycle().phase()==Phase.TERMINAL) stateTerminalCondition(new Step(client,server));
        drain(); note("SERVER "+server);
        return new Step(client,server);
    }

    private Step step() { context.waitTick(); return read(); }

    private Step await(Predicate<Step> predicate,int budget,String why) {
        var s=read();
        for(int waited=0;waited<=budget;waited++) {
            if(predicate.test(s)) return s;
            require(s.client().status().engine().lifecycle().phase()!=Phase.TERMINAL
                    || s.client().status().engine().lifecycle().outcome().orElseThrow()==Outcome.SUCCESS,
                    "Premature failed terminal while waiting "+why+": "+s.client().status());
            if(waited<budget) s=step();
        }
        throw new AssertionError("Timeout after "+budget+" ordinary ticks: "+why+"; last="+s);
    }

    private void drain() {
        var samples=context.computeOnClient(client -> watch.copy());
        for(;writtenSamples<samples.size();writtenSamples++) {
            var sample=samples.get(writtenSamples); note(sample.line());
            if(runId!=null) {
                safety(sample);
                var phase=sample.status().engine().lifecycle().phase();
                if(phase==Phase.PAUSED || phase==Phase.TERMINAL) released(sample,"inactive observer phase");
            }
        }
    }

    private void safety(Sample s) {
        if(s.pose()==null) return;
        boolean destination=!s.originalWorld();
        if(destination && !transferred) return; // Closed disconnect's following world has its own pre-start setup.
        position(s.pose().x(),s.pose().y(),s.pose().z(),destination,"client "+s.phase());
        require(!s.pose().flying() && !s.pose().sprinting() && !s.keys().get(4),"Unexpected flight/sprint/jump: "+s.line());
        require(s.pose().reserved()==7,"Reserved client stone consumed: "+s.line());
        if(!destination) maxClientX=Math.max(maxClientX,s.pose().x());
    }

    private static void position(double x,double y,double z,boolean destination,String where) {
        require(Math.abs(y-66)<=.05,"Standing height lost: "+where+" pos="+x+","+y+","+z);
        require(destination ? x>=-3.7 && x<=4.7 && z>=-3.7 && z<=4.7
                : x>=-1.7 && x<=13.7 && z>=-1.7 && z<=6.7,"Escaped bounded support: "+where+" pos="+x+","+y+","+z);
    }

    private void closeOwned() {
        require(!closeAttempted,"Attempted double-close");
        closeAttempted=true;
        Throwable primary=null;
        try { if(faultCase()) context.runOnClient(client -> PredictionFaults.clear()); }
        catch(RuntimeException | Error failure) { primary=failure; }
        try { world.close(); }
        catch(RuntimeException | Error failure) {
            if(primary==null) primary=failure;
            else primary.addSuppressed(failure);
        }
        finally {
            try { context.runOnClient(client -> watch.closeWorld()); }
            catch(RuntimeException | Error cleanup) {
                if(primary==null) primary=cleanup;
                else primary.addSuppressed(cleanup);
            } finally { world=null; bound=null; boundHandler=null; boundConnection=null; }
        }
        if(primary instanceof RuntimeException runtime) throw runtime;
        if(primary instanceof Error error) throw error;
        note("WORLD CLOSED normally; run="+runId);
        if(faultCase()) {
            var gate=context.computeOnClient(c -> PredictionFaults.snapshot());
            require(!gate.active() && gate.failure().isEmpty(),"Fault cleanup retained native scope or sticky error: "+gate);
        }
    }

    private void screenshot(String name) {
        if(name.equals("success") && !closeAttempted) {
            context.waitFor(client -> client.player!=null && client.worldRenderer.isRenderingReady(client.player.getBlockPos().down()),WAIT);
        }
        Path path=context.takeScreenshot(TestScreenshotOptions.of(name).withDestinationDir(artifacts).disableCounterPrefix());
        require(Files.isRegularFile(path),"Missing screenshot: "+path); note("screenshot="+path.toAbsolutePath());
    }

    private String manifest() {
        var text=new StringBuilder("single-lane-v1;seed=1;flat;origin=0,65,0;dimensions=12,1,5;survival;player=.5,66,2.5;yaw=0;pitch=60;"
                +SETTINGS.fingerprint()+";slot0=stone7,"+(fixture.selected()==0?"selected,":"")+"reserved;slot1="
                +(fixture.usableItem()==Items.SNOW?"snow":"stone")+fixture.stock()+(fixture.selected()==1?",selected":"")+";all-other-slots=empty;autoJump=false\n");
        if(kind==Kind.WITHHELD_RESPONSE) text.append("SIMULATED RESPONSE FAULT;BEFORE-start all-but-ID"+fixture.missingTarget()+" prefill;no same-world recovery\n");
        if(kind==Kind.WITHHELD_OBSERVATION) text.append("SIMULATED PRODUCER OMISSION;BEFORE-start all-but-ID"+fixture.missingTarget()+" prefill;no full-building baseline credit\n");
        if(computedCase()) text.append("SIMULATED COMPUTED LIFECYCLE BARRIER;BEFORE-start all-but-ID"+fixture.missingTarget()+" prefill;no full-building baseline credit\n");
        if(stateCase()) text.append(kind==Kind.REPEATED_STATE?"NATIVE-ONLY REPEATED STATE;BEFORE-start snow2/selected1/all-but-ID"+fixture.missingTarget()+";no production action credit\n"
                :"SIMULATED STATE WITHHOLDING;BEFORE-start all-but-ID"+fixture.missingTarget()+" prefill;no same-world recovery\n");
        for(int x=-2;x<=13;x++) for(int z=-2;z<=6;z++) for(int y=64;y<=70;y++) text.append(x).append(',').append(y).append(',').append(z).append('=')
                .append(y==64?"bedrock":y==65 && initiallyStone(x,z)?"stone":"air").append('\n');
        for(var target:PLAN.targets()) text.append("target=").append(target).append('\n');
        if(kind==Kind.REPLACEMENT) for(int x=-4;x<=4;x++) for(int z=-4;z<=4;z++) for(int y=64;y<=70;y++)
            text.append("minecraft:the_nether:").append(x).append(',').append(y).append(',').append(z).append('=')
                    .append(y==64?"bedrock":y==65?"stone":"air").append('\n');
        return text.toString();
    }

    private boolean initiallyStone(int x,int z) {
        return x<0 || x>=12 || z<0 || z>=5 || (fixture.missingTarget()>=0 ? SingleLaneFixture.targetId(PLAN,new GridPos(x,0,z))!=fixture.missingTarget() : x==0 && z==2);
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private void noteFailure(Throwable error) {
        var text=new StringWriter(); error.printStackTrace(new PrintWriter(text)); note("FAIL "+text);
        if(kind==Kind.WITHHELD_OBSERVATION || computedCase()) write("fault-diagnostics.txt",context.computeOnClient(c -> PredictionFaults.snapshot()).toString());
        if(stateCase()) write("state-diagnostics.txt",context.computeOnClient(c -> PredictionFaults.stateSnapshot()).toString());
    }
    private void note(String value) {
        try { Files.writeString(artifacts.resolve("scenario.log"),value+System.lineSeparator(),StandardOpenOption.CREATE,StandardOpenOption.APPEND); }
        catch(IOException e) { throw new UncheckedIOException(e); }
    }
    private void write(String file,String value) {
        try { Files.writeString(artifacts.resolve(file),value); } catch(IOException e) { throw new UncheckedIOException(e); }
    }
    private void retainLog() {
        try { Files.copy(FabricLoader.getInstance().getGameDir().resolve("logs/latest.log"),artifacts.resolve("minecraft-latest.log"),StandardCopyOption.REPLACE_EXISTING); }
        catch(IOException e) { throw new UncheckedIOException(e); }
    }
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
