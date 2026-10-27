package com.mapartrunner.fabric.scenario;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.core.movement.MovementPolicy;
import com.mapartrunner.core.placement.PlacementObservation;
import com.mapartrunner.core.run.BuildEngine;
import com.mapartrunner.core.run.RunLifecycle.*;
import com.mapartrunner.fabric.MapArtClient;
import com.mapartrunner.fabric.scenario.GroundedSingleLaneGameTest.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;

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
    enum Kind { BASELINE, STOP, FAILURE, DISCONNECT, REPLACEMENT }
    private static final int WAIT = ClientGameTestContext.DEFAULT_TIMEOUT;
    private static final GridPos ORIGIN = new GridPos(0,65,0);
    private static final BuildEngine.Settings SETTINGS = new BuildEngine.Settings(2,5,false,1);
    private static final BlockSpec STONE = new BlockSpec("minecraft:stone", Map.of());
    private static final BuildPlan PLAN = plan();
    private final ClientGameTestContext context;
    private final Watch watch;
    private final Kind kind;
    private final int stock;
    private final Path artifacts;
    private TestSingleplayerContext world;
    private ClientWorld bound;
    private UUID runId;
    private boolean closeAttempted;
    private boolean transferred;
    private int writtenSamples;
    private double clientReach;
    private double serverReach;
    private double maxClientX = .5;
    private double maxServerX = .5;

    SingleLaneScenario(ClientGameTestContext context, Watch watch, Kind kind) {
        this.context = context; this.watch = watch; this.kind = kind;
        stock = kind == Kind.FAILURE ? 12 : 64;
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
            if (kind == Kind.BASELINE) baseline();
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
        world.getServer().runOnServer(server -> {
            var level = server.getOverworld();
            require(level.getSeed() == 1, "Fixture seed is not 1: " + level.getSeed());
            for (int x=-2;x<=13;x++) for (int z=-2;z<=6;z++) {
                level.setBlockState(new BlockPos(x,64,z), Blocks.BEDROCK.getDefaultState());
                boolean target = x>=0 && x<12 && z>=0 && z<5;
                level.setBlockState(new BlockPos(x,65,z), !target || x==0 && z==2 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
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
            p.getInventory().setStack(1,new ItemStack(Items.STONE,stock)); p.getInventory().setSelectedSlot(0);
            p.getInventory().markDirty(); p.currentScreenHandler.sendContentUpdates();
            require(p.teleport(level,.5,66,2.5,java.util.Set.of(),0,60,false), "Setup teleport rejected");
        });
        context.waitFor(client -> client.player != null && client.interactionManager != null && client.currentScreen == null
                && !client.isPaused() && client.interactionManager.getCurrentGameMode() == GameMode.SURVIVAL
                && client.player.squaredDistanceTo(.5,66,2.5) < .0001 && Math.abs(client.player.getYaw()) < .01
                && client.player.getInventory().getSelectedSlot()==0 && client.player.getInventory().getStack(0).isOf(Items.STONE)
                && client.player.getInventory().getStack(0).getCount()==7 && client.player.getInventory().getStack(1).isOf(Items.STONE)
                && client.player.getInventory().getStack(1).getCount()==stock && client.player.isOnGround(), WAIT);
        context.runOnClient(client -> {
            bound = client.world;
            clientReach = client.player.getBlockInteractionRange();
            require(!client.player.getAbilities().flying && !client.player.getAbilities().allowFlying, "Not ordinary survival");
            for (int x=-2;x<=13;x++) for (int z=-2;z<=6;z++) {
                require(client.world.getBlockState(new BlockPos(x,64,z)).isOf(Blocks.BEDROCK), "Client floor mismatch");
                boolean stone = x<0 || x>=12 || z<0 || z>=5 || x==0 && z==2;
                require(stone ? client.world.getBlockState(new BlockPos(x,65,z)).isOf(Blocks.STONE)
                        : client.world.getBlockState(new BlockPos(x,65,z)).isAir(), "Client initial target/apron mismatch");
                for (int y=66;y<=70;y++) require(client.world.getBlockState(new BlockPos(x,y,z)).isAir(), "Client clearance mismatch");
            }
            for (int slot=2;slot<client.player.getInventory().size();slot++) require(client.player.getInventory().getStack(slot).isEmpty(), "Client extra inventory");
        });
        serverReach = world.getServer().computeOnServer(server -> {
            var p = server.getPlayerManager().getPlayerList().getFirst();
            require(p.interactionManager.getGameMode()==GameMode.SURVIVAL && !p.getAbilities().flying
                    && !p.getAbilities().allowFlying && p.getInventory().getSelectedSlot()==0
                    && p.squaredDistanceTo(.5,66,2.5)<.0001 && Math.abs(p.getYaw())<.01, "Server survival/pose/inventory mismatch");
            require(p.getInventory().getStack(0).isOf(Items.STONE) && p.getInventory().getStack(0).getCount()==7
                    && p.getInventory().getStack(1).isOf(Items.STONE) && p.getInventory().getStack(1).getCount()==stock, "Server stock mismatch");
            for (int slot=2;slot<p.getInventory().size();slot++) require(p.getInventory().getStack(slot).isEmpty(), "Server extra inventory");
            for (int x=-2;x<=13;x++) for (int z=-2;z<=6;z++) {
                var level=server.getOverworld(); boolean stone=x<0||x>=12||z<0||z>=5||x==0&&z==2;
                require(level.getBlockState(new BlockPos(x,64,z)).isOf(Blocks.BEDROCK), "Server floor mismatch");
                require(stone ? level.getBlockState(new BlockPos(x,65,z)).isOf(Blocks.STONE)
                        : level.getBlockState(new BlockPos(x,65,z)).isAir(), "Server target/apron mismatch");
                for(int y=66;y<=70;y++) require(level.getBlockState(new BlockPos(x,y,z)).isAir(), "Server clearance mismatch");
            }
            return p.getBlockInteractionRange();
        });
        context.waitTicks(3);
        note("INITIAL VALIDATED: survival; seed=1; player=.5,66,2.5; yaw=0; selected=0; matching stone sentinel=7; usable="
                + stock + "; measured clientReach=" + clientReach + "; serverReach=" + serverReach);
    }

    private void start() {
        context.runOnClient(client -> {
            require(client.world == bound && !client.isPaused() && client.currentScreen == null, "Binding changed before start");
            runId = MapArtClient.start(PLAN,ORIGIN,SETTINGS);
            watch.arm(client,PLAN);
        });
        writtenSamples=0;
        note("START PRODUCTION run=" + runId + "; origin=" + ORIGIN + "; settings=" + SETTINGS.fingerprint());
        read();
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
        for(int id : List.of(0,4)) {
            var predicted=samples.stream().filter(s -> s.phase().equals("END") && evidence(s,id,PREDICTED)).findFirst().orElseThrow();
            var observed=samples.stream().filter(s -> s.tick()>predicted.tick() && evidence(s,id,OBSERVED)).findFirst().orElseThrow();
            var action=actions.stream().filter(a -> a.targetId()==id && a.status()==BuildEngine.EffectStatus.ACCEPTED).findFirst().orElseThrow();
            require(action.runId().equals(runId) && predicted.pose().selectedSlot()==1, "Outer placement identity/usable slot mismatch");
            double distance=predicted.pose().eyes().distanceTo(new Vec3d(.5,65,id+.5));
            require(distance<=clientReach, "Outer deterministic fixture top hit exceeded real reach");
            outerActions.add(action.actionId());
            note("OUTER actual accepted="+action+"; deterministic C6 support=(0,64,"+id+"),UP; hit=(.5,65,"+(id+.5)
                    +"); distance="+distance+"; predicted="+predicted.line()+"; observed="+observed.line());
        }
        require(!outerActions.getFirst().equals(outerActions.getLast()), "Outer columns shared action identity");
        boolean chain=false;
        for(var hold:samples) {
            var entry=hold.status().engine().laneEntry();
            if(hold.pose()==null || entry.isEmpty() || entry.orElseThrow().stage()!=MovementPolicy.Stage.WALK
                    || entry.orElseThrow().supportHoldTicks()==0 || hold.keys().getFirst()) continue;
            int id=((int)Math.floor(hold.pose().x())+1)*5+2;
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
            require(p.getInventory().getStack(1).isEmpty() || p.getInventory().getStack(1).isOf(Items.STONE),"Usable server material changed");
            for(int slot=2;slot<p.getInventory().size();slot++) require(p.getInventory().getStack(slot).isEmpty(),"Unexpected server inventory at "+slot);
            return new ServerSample(s.getTicks(),p.age,p.getEntityWorld().getTime(),p.getEntityWorld().getRegistryKey().getValue().toString(),
                    p.getX(),p.getY(),p.getZ(),p.getVelocity(),p.getInventory().getStack(0).getCount(),p.getInventory().getStack(1).getCount(),blocks);
        });
        if(runId!=null) {
            safety(client); position(server.x(),server.y(),server.z(),transferred,"server");
            maxServerX=Math.max(maxServerX,server.x());
        }
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
        try { world.close(); }
        catch(RuntimeException | Error failure) { primary=failure; throw failure; }
        finally {
            try { context.runOnClient(client -> watch.closeWorld()); }
            catch(RuntimeException | Error cleanup) {
                if(primary==null) throw cleanup;
                primary.addSuppressed(cleanup);
            } finally { world=null; bound=null; }
        }
        note("WORLD CLOSED normally; run="+runId);
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
                +SETTINGS.fingerprint()+";slot0=stone7,selected,reserved;slot1=stone"+stock+";all-other-slots=empty;autoJump=false\n");
        for(int x=-2;x<=13;x++) for(int z=-2;z<=6;z++) for(int y=64;y<=70;y++) text.append(x).append(',').append(y).append(',').append(z).append('=')
                .append(y==64?"bedrock":y==65 && (x<0||x>=12||z<0||z>=5||x==0&&z==2)?"stone":"air").append('\n');
        for(var target:PLAN.targets()) text.append("target=").append(target).append('\n');
        if(kind==Kind.REPLACEMENT) for(int x=-4;x<=4;x++) for(int z=-4;z<=4;z++) for(int y=64;y<=70;y++)
            text.append("minecraft:the_nether:").append(x).append(',').append(y).append(',').append(z).append('=')
                    .append(y==64?"bedrock":y==65?"stone":"air").append('\n');
        return text.toString();
    }

    private static BuildPlan plan() {
        var targets=new ArrayList<Target>();
        for(int x=0;x<12;x++) for(int z=0;z<5;z++) targets.add(new Target(x*5+z,new GridPos(x,0,z),STONE));
        return new BuildPlan(hash("single-lane-v1;12,1,5;"+targets),new GridSize(12,1,5),targets);
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private void noteFailure(Throwable error) { var text=new StringWriter(); error.printStackTrace(new PrintWriter(text)); note("FAIL "+text); }
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
