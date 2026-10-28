package com.mapartrunner.fabric.scenario;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.core.run.BuildEngine;
import com.mapartrunner.core.run.RunLifecycle.*;
import com.mapartrunner.fabric.MapArtClient;
import com.mapartrunner.fabric.runtime.FabricBuildAdapter;
import com.mapartrunner.fabric.schematic.SchematicPlanImporter;
import com.mapartrunner.fabric.scenario.GroundedSingleLaneGameTest.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.network.ClientConnection;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

/** Real resource metadata and a facade-owned empty run in one fresh disposable world. */
public final class SchematicImportGameTest implements FabricClientGameTest {
    private static final int WAIT=ClientGameTestContext.DEFAULT_TIMEOUT;
    private static final GridPos ORIGIN=new GridPos(0,65,0);
    private static final BuildEngine.Settings SETTINGS=new BuildEngine.Settings(2,5,false,1);
    private final Watch watch=new Watch();
    private ClientGameTestContext context;
    private Path artifacts;
    private TestSingleplayerContext world;
    private ClientWorld boundWorld;
    private ClientPlayerEntity boundPlayer;
    private ClientPlayNetworkHandler boundHandler;
    private ClientConnection boundConnection;
    private MinecraftServer boundServer;
    private UUID runId;
    private FabricBuildAdapter.Status terminal;
    private Boolean autoJump;
    private boolean closed;
    private int writtenSamples;

    @Override public void runTest(ClientGameTestContext context) {
        this.context=context;
        var game=FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        require(game.getParent().getFileName().toString().equals("run")
                && game.getParent().getParent().getFileName().toString().equals("build"),"Unmanaged import game directory: "+game);
        artifacts=game.getParent().getParent().resolve("scenario-results").resolve("schematic-import-"+UUID.randomUUID());
        try { Files.createDirectories(artifacts); } catch(IOException error) { throw new UncheckedIOException(error); }
        System.out.println("MapArtRunner schematic import artifacts: "+artifacts);
        Throwable primary=null;
        try {
            var air=metadata();
            ClientTickEvents.START_CLIENT_TICK.register(client -> watch.record(client,"START"));
            ClientTickEvents.END_CLIENT_TICK.register(client -> watch.record(client,"END"));
            autoJump=context.computeOnClient(client -> client.options.getAutoJump().getValue());
            context.runOnClient(client -> client.options.getAutoJump().setValue(false));
            world=context.worldBuilder().create();
            note("save="+world.getWorldSave().getSaveDirectory().toAbsolutePath()+"; settings="+SETTINGS.fingerprint());
            emptyRun(air);
            screenshot("success");
            closeOwned();
            note("PASS after normal owned world close; empty run="+runId);
        } catch(RuntimeException | Error error) {
            primary=error;
            try { failure(error); } catch(RuntimeException | Error logging) { error.addSuppressed(logging); }
            try { screenshot("failure"); } catch(RuntimeException | Error capture) { error.addSuppressed(capture); }
            throw error;
        } finally {
            if(!closed) {
                try { closeOwned(); } catch(RuntimeException | Error cleanup) {
                    if(primary==null) throw cleanup;
                    primary.addSuppressed(cleanup);
                }
            }
            try {
                if(primary!=null) failure(primary);
                Files.copy(game.resolve("logs/latest.log"),artifacts.resolve("minecraft-latest.log"),StandardCopyOption.REPLACE_EXISTING);
            } catch(IOException | RuntimeException | Error logging) {
                if(primary!=null) primary.addSuppressed(logging);
                else if(logging instanceof IOException io) throw new UncheckedIOException(io);
                else if(logging instanceof RuntimeException runtime) throw runtime;
                else throw (Error)logging;
            }
        }
    }

    private BuildPlan metadata() {
        try {
            var factory=SingleLaneFixture.verifyImportedFactory();
            var lanePath=copy("grounded-single-lane.schem","7c194b89faf8339bf03b20b122b7b3b17f22064f1fb4e4139e1781bb4664ce0a");
            var lane=SchematicPlanImporter.importPlan(lanePath);
            require(factory.equals(lane),"Actual lane factory differs from checked-in import");
            var stone=new BlockSpec("minecraft:stone",Map.of());
            int[] ids={0,11,12,23,24,48,59};
            var positions=List.of(new GridPos(0,0,0),new GridPos(11,0,0),new GridPos(11,0,1),new GridPos(0,0,1),
                    new GridPos(0,0,2),new GridPos(0,0,4),new GridPos(11,0,4));
            for(int i=0;i<ids.length;i++) require(lane.targets().get(ids[i]).relativePos().equals(positions.get(i)),"Lane literal anchor "+ids[i]);
            for(int i=0;i<60;i++) require(lane.targets().get(i).id()==i && lane.targets().get(i).block().equals(stone),"Lane full state/ID "+i);
            checkPlan("lane",lane,new GridSize(12,1,5),"3163e482e8af48950898a172829747f62090c43697b6a69c76a3229ede63047a",
                    Map.of(Items.STONE,60L),Map.of(List.of(0,0),60L));

            var asymmetricPath=copy("flat-asymmetric.nbt","5cf68ed66fa3f0062bb7996961a86423668ecc11896d762dcd43fc8e9f5bdec6");
            var asymmetric=SchematicPlanImporter.importPlan(asymmetricPath);
            var log=new BlockSpec("minecraft:oak_log",Map.of("axis","x"));
            require(asymmetric.targets().equals(List.of(new Target(0,new GridPos(0,1,0),stone),new Target(1,new GridPos(17,1,0),log),
                    new Target(2,new GridPos(16,1,2),log),new Target(3,new GridPos(1,1,2),stone),new Target(4,new GridPos(3,1,4),stone))),
                    "Asymmetric literal positions/IDs/full states");
            checkPlan("asymmetric",asymmetric,new GridSize(18,3,5),"3a05428d7956c4b10d02f9d1bb7dd9560e60a4898d2459dac8c3e355e9151414",
                    Map.of(Items.STONE,3L,Items.OAK_LOG,2L),Map.of(List.of(0,0),3L,List.of(1,0),2L));
            var changed=NbtIo.readCompressed(asymmetricPath,NbtSizeTracker.forLevel());
            changed.getList("palette").orElseThrow().getCompound(0).orElseThrow().getCompound("Properties").orElseThrow().putString("axis","z");
            var changedPath=artifacts.resolve("changed-axis.nbt");
            NbtIo.writeCompressed(changed,changedPath);
            var changedPlan=SchematicPlanImporter.importPlan(changedPath);
            require(!changedPlan.fingerprint().equals(asymmetric.fingerprint())
                    && changedPlan.targets().get(1).block().equals(new BlockSpec("minecraft:oak_log",Map.of("axis","z"))),"Changed required state/hash was lost");
            note("changed sourceSha256="+sha(Files.readAllBytes(changedPath))+"; semantic="+changedPlan.fingerprint());

            var airPath=copy("all-air.schem","afe1224408ad2757eae8e285a9a3c013f1fdf7021dca19bca99adf172e97ec54");
            var nbt=NbtIo.readCompressed(airPath,NbtSizeTracker.forLevel());
            require(nbt.getByteArray("BlockData").orElseThrow().length==24 && nbt.getCompound("Palette").orElseThrow().getInt("minecraft:air").orElseThrow()==0,
                    "Air fixture must encode all 24 valid air cells");
            for(byte cell:nbt.getByteArray("BlockData").orElseThrow()) require(cell==0,"Air data contains a non-air palette reference");
            var air=SchematicPlanImporter.importPlan(airPath);
            require(air.targets().isEmpty(),"Air fixture contains targets");
            checkPlan("air",air,new GridSize(3,2,4),"5bd2e7c59bea4ec529380a518c4ae2f5f88281028f3979d65484eadeab932d0e",Map.of(),Map.of());
            nbt.getCompound("Palette").orElseThrow().putInt("minecraft:unknown_unused_import_test",1);
            var malformed=artifacts.resolve("malformed-unused-palette.schem");
            NbtIo.writeCompressed(nbt,malformed);
            context.runOnClient(client -> {
                var before=MapArtClient.status();
                boolean reachedStart=false;
                try {
                    var invalid=SchematicPlanImporter.importPlan(malformed);
                    reachedStart=true;
                    MapArtClient.start(invalid,ORIGIN,SETTINGS);
                } catch(IllegalArgumentException expected) {
                    require(expected.getMessage().contains(malformed.toString()) && expected.getMessage().contains("Palette"),"Wrong malformed context: "+expected);
                    require(!reachedStart && MapArtClient.status().equals(before),"Malformed input reached start or changed facade state");
                    note("MALFORMED rejected before start; unchangedStatus="+before+"; exception="+expected);
                    return;
                } catch(IOException error) { throw new UncheckedIOException(error); }
                throw new AssertionError("Malformed unused palette was accepted");
            });
            return air;
        } catch(IOException error) { throw new UncheckedIOException(error); }
    }

    private void checkPlan(String name,BuildPlan plan,GridSize dimensions,String fingerprint,Map<?,Long> materials,Map<List<Integer>,Long> regions) {
        require(plan.dimensions().equals(dimensions) && plan.fingerprint().equals(fingerprint),"Wrong "+name+" dimensions/semantic SHA: "+plan);
        var actualMaterials=plan.targets().stream().collect(groupingBy(target -> Registries.BLOCK.getOptionalValue(
                Identifier.of(target.block().id())).orElseThrow().asItem(),counting()));
        var actualRegions=plan.targets().stream().collect(groupingBy(target -> List.of(target.relativePos().x()>>4,target.relativePos().z()>>4),counting()));
        require(actualMaterials.equals(materials) && actualRegions.equals(regions),"Wrong "+name+" materials/regions");
        note("IMPORT "+name+"; dimensions="+dimensions+"; semantic="+fingerprint+"; materials="+actualMaterials+"; regions="+actualRegions+"; targets="+plan.targets());
    }

    private Path copy(String name,String expectedSha) throws IOException {
        var path=artifacts.resolve(name);
        try(var stream=getClass().getResourceAsStream("/schematic/"+name)) {
            require(stream!=null,"Missing classpath fixture "+name);
            Files.copy(stream,path);
        }
        var bytes=Files.readAllBytes(path);
        String actual=sha(bytes);
        note("SOURCE resource=/schematic/"+name+"; path="+path+"; bytes="+bytes.length+"; rawSha256="+actual);
        require(actual.equals(expectedSha),"Wrong resource SHA "+name+": "+actual);
        return path;
    }

    private void emptyRun(BuildPlan air) {
        boundServer=world.getServer().computeOnServer(server -> {
            require(server.getOverworld().getSeed()==1,"Empty world seed is not 1");
            require(server.getOverworld().getChunkManager().getChunkGenerator() instanceof FlatChunkGenerator,"Empty world is not FLAT");
            return server;
        });
        final Path ownedSave;
        try {
            ownedSave=world.getWorldSave().getSaveDirectory().toRealPath();
            var saves=FabricLoader.getInstance().getGameDir().resolve("saves").toRealPath();
            require(ownedSave.startsWith(saves) && !ownedSave.equals(saves)
                    && boundServer.getSavePath(WorldSavePath.ROOT).toRealPath().equals(ownedSave),"Unowned empty save: "+ownedSave);
        } catch(IOException error) { throw new UncheckedIOException(error); }
        // Bind this creation before writes; closeOwned clears it after native disposal.
        var ownedPlayer=context.computeOnClient(client -> {
            require(client.getServer()==boundServer && client.world!=null && client.world.getRegistryKey().equals(World.OVERWORLD)
                    && client.player!=null && client.player.getEntityWorld()==client.world && client.getNetworkHandler()!=null,"Missing owned empty client binding");
            boundWorld=client.world; boundPlayer=client.player; boundHandler=client.getNetworkHandler(); boundConnection=boundHandler.getConnection();
            checkFixtureBinding(client);
            return boundPlayer.getUuid();
        });
        var beforeProblem=context.computeOnClient(client -> { checkFixtureBinding(client); return fixtureProblem(client.world); });
        note("FIXTURE BEFORE writes; save="+ownedSave+"; consistent FLAT/seed=1; problem="+beforeProblem);
        require(beforeProblem!=null,"Fresh owned empty fixture unexpectedly ready before writes");
        world.getServer().runOnServer(server -> {
            require(server==boundServer && server.getPlayerManager().getPlayerList().getFirst().getUuid().equals(ownedPlayer),"Empty setup server/player replaced");
            var level=server.getOverworld();
            require(level.getSeed()==1,"Empty world seed is not 1");
            for(int x=-2;x<=4;x++) for(int z=-2;z<=5;z++) for(int y=64;y<=70;y++)
                level.setBlockState(new BlockPos(x,y,z),(y==64?Blocks.BEDROCK:Blocks.AIR).getDefaultState());
            var player=server.getPlayerManager().getPlayerList().getFirst();
            player.changeGameMode(GameMode.SURVIVAL); player.setHealth(20); player.getHungerManager().setFoodLevel(20);
            player.getInventory().clear(); player.getInventory().setStack(0,new ItemStack(Items.STONE,7));
            player.getInventory().setStack(1,new ItemStack(Items.STONE,64)); player.getInventory().setSelectedSlot(1);
            player.getInventory().markDirty(); player.currentScreenHandler.sendContentUpdates();
            require(player.teleport(level,.5,65,2.5,java.util.Set.of(),0,60,false),"Empty setup teleport rejected");
        });
        context.runOnClient(client -> { checkFixtureBinding(client); client.player.getInventory().setSelectedSlot(1); });
        final int setupElapsed;
        try {
            setupElapsed=context.waitFor(client -> {
                checkFixtureBinding(client);
                return client.interactionManager!=null && !client.isPaused() && client.currentScreen==null && client.player.isOnGround()
                        && client.interactionManager.getCurrentGameMode()==GameMode.SURVIVAL
                        && client.player.squaredDistanceTo(.5,65,2.5)<.0001 && Math.abs(client.player.getYaw())<.01
                        && client.player.getInventory().getSelectedSlot()==1 && client.player.getInventory().getStack(0).isOf(Items.STONE)
                        && client.player.getInventory().getStack(0).getCount()==7 && client.player.getInventory().getStack(1).isOf(Items.STONE)
                        && client.player.getInventory().getStack(1).getCount()==64 && fixtureProblem(client.world)==null;
            },WAIT);
        } catch(RuntimeException | Error primary) {
            try { note("FIXTURE readiness failed; waitFor did not return; budgetTicks="+WAIT+"; before="+beforeProblem+"; "
                    +context.computeOnClient(this::fixtureDiagnostic)); }
            catch(RuntimeException | Error diagnostic) { primary.addSuppressed(diagnostic); }
            throw primary;
        }
        context.runOnClient(client -> {
            checkBinding(client);
            var problem=fixtureProblem(client.world);
            require(problem==null,"Empty client fixture not synchronized: "+problem);
            for(int slot=2;slot<client.player.getInventory().size();slot++) require(client.player.getInventory().getStack(slot).isEmpty(),"Extra client inventory");
            var gate=PredictionFaults.snapshot();
            require(!gate.active() && gate.failure().isEmpty(),"Fault leaked into empty run");
        });
        note("FIXTURE READY; elapsedTicks="+setupElapsed+"; before="+beforeProblem+"; "+context.computeOnClient(this::fixtureDiagnostic));
        var before=serverSample();
        context.runOnClient(client -> {
            checkBinding(client);
            require(client.player.getUuid().equals(before.player()),"Empty client/server player identity differs");
            runId=MapArtClient.start(air,ORIGIN,SETTINGS);
            terminal=MapArtClient.status();
            watch.arm(client,air);
            assertEmpty(watch.sample(client,"IMMEDIATE"));
            note("EMPTY IMMEDIATE run="+runId+"; status="+terminal);
        });
        var previousClient=clientSample();
        var previousServer=before;
        int actual=0;
        for(int waited=0;waited<60 && actual<20;waited++) {
            context.waitTick();
            var client=clientSample();
            var server=serverSample();
            require(server.player().equals(before.player()) && server.blocks().equals(before.blocks()) && server.selected()==before.selected(),"Empty server identity/blocks/selection changed");
            for(int slot=0;slot<before.inventory().size();slot++) require(ItemStack.areEqual(before.inventory().get(slot),server.inventory().get(slot)),"Empty server inventory changed at "+slot);
            if(client.pose().age()>previousClient.pose().age() && client.pose().worldTime()>previousClient.pose().worldTime()
                    && server.age()>previousServer.age() && server.tick()>previousServer.tick() && server.time()>previousServer.time()) actual++;
            previousClient=client; previousServer=server;
        }
        require(actual==20,"Missing 20 actual empty client/server physics ticks: "+actual);
        note("EMPTY RELEASE PHYSICS actualTicks="+actual+"; firstServer="+before+"; lastServer="+previousServer+"; lastClient="+previousClient.line());
    }

    private void checkFixtureBinding(MinecraftClient client) {
        require(client.getServer()==boundServer && client.world==boundWorld && client.player==boundPlayer
                && boundPlayer.getEntityWorld()==boundWorld && client.getNetworkHandler()==boundHandler
                && boundHandler.getWorld()==boundWorld && boundHandler.getConnection()==boundConnection
                && boundConnection.isOpen(),"Owned empty fixture binding replaced");
    }

    private String fixtureProblem(ClientWorld level) {
        if(level==null) return "world=null; expected owned overworld";
        for(int x=-1;x<=0;x++) for(int z=-1;z<=0;z++)
            if(level.getChunkManager().getChunk(x,z,ChunkStatus.FULL,false)==null)
                return "chunk="+x+","+z+"; expected=FULL; actual=unloaded";
        for(int x=-2;x<=4;x++) for(int z=-2;z<=5;z++) for(int y=64;y<=70;y++) {
            var pos=new BlockPos(x,y,z);
            var actual=level.getBlockState(pos);
            var expected=(y==64?Blocks.BEDROCK:Blocks.AIR).getDefaultState();
            if(!actual.equals(expected)) return "chunk="+(x>>4)+","+(z>>4)+"; pos="+pos+"; expected="+expected+"; actual="+actual;
        }
        return null;
    }

    private String fixtureDiagnostic(MinecraftClient client) {
        var chunks=new StringBuilder();
        for(int x=-1;x<=0;x++) for(int z=-1;z<=0;z++) chunks.append(x).append(',').append(z).append('=')
                .append(client.world!=null && client.world.getChunkManager().getChunk(x,z,ChunkStatus.FULL,false)!=null).append(';');
        return "serverMatches="+(client.getServer()==boundServer)+"; world="+System.identityHashCode(client.world)
                +"; ownedWorld="+System.identityHashCode(boundWorld)+"; playerMatches="+(client.player==boundPlayer)
                +"; playerWorldMatches="+(boundPlayer.getEntityWorld()==boundWorld)+"; handlerMatches="+(client.getNetworkHandler()==boundHandler)
                +"; connectionMatches="+(client.getNetworkHandler()!=null && client.getNetworkHandler().getConnection()==boundConnection)
                +"; connectionOpen="+boundConnection.isOpen()+"; FULL chunks="+chunks+"; problem="+fixtureProblem(client.world);
    }

    private Sample clientSample() {
        return context.computeOnClient(client -> {
            checkBinding(client);
            drain();
            var sample=watch.sample(client,"POLL"); assertEmpty(sample); note(sample.line());
            return sample;
        });
    }

    private void checkBinding(MinecraftClient client) {
        require(client.world==boundWorld && client.player==boundPlayer && client.getNetworkHandler()==boundHandler
                && boundHandler.getConnection()==boundConnection && boundConnection.isOpen() && boundPlayer.getEntityWorld()==boundWorld
                && boundHandler.getWorld()==boundWorld && !boundPlayer.getAbilities().allowFlying
                && !client.isPaused() && client.currentScreen==null && client.interactionManager.getCurrentGameMode()==GameMode.SURVIVAL,"Empty client binding lost");
    }

    private void assertEmpty(Sample sample) {
        var status=sample.status(); var engine=status.engine();
        require(status.equals(terminal) && status.failure().isEmpty() && status.controlOwner()==BuildEngine.ControlOwner.NONE
                && engine.lifecycle().phase()==Phase.TERMINAL && engine.lifecycle().outcome().orElseThrow()==Outcome.SUCCESS
                && engine.lifecycle().context().orElseThrow().runId().equals(runId)
                && engine.diagnostic().orElseThrow().reason()==BuildEngine.Reason.NOTHING_TO_BUILD
                && engine.eligibleTicks()==0 && engine.verifyTicks()==0 && engine.effectResults().isEmpty()
                && engine.pendingTargetIds().isEmpty() && engine.unfinishedTargetIds().isEmpty() && engine.laneEntry().isEmpty()
                && sample.keys().size()==9 && sample.keys().stream().noneMatch(Boolean::booleanValue)
                && sample.originalWorld() && !sample.paused() && sample.pose()!=null,"Empty effects/status changed: "+sample.line());
        var pose=sample.pose();
        require(!pose.flying() && !pose.sprinting() && pose.selectedSlot()==1 && pose.reserved()==7 && pose.usable()==64
                && Math.abs(pose.x()-.5)<.05 && Math.abs(pose.y()-65)<.05 && Math.abs(pose.z()-2.5)<.05,"Empty client physics/inventory changed: "+sample.line());
    }

    private record ServerSample(int tick,int age,long time,UUID player,List<BlockState> blocks,List<ItemStack> inventory,int selected,double x,double y,double z) { }
    private ServerSample serverSample() {
        return world.getServer().computeOnServer(server -> {
            require(server==boundServer,"Empty owned server changed");
            var player=server.getPlayerManager().getPlayerList().getFirst();
            require(player.getEntityWorld()==server.getOverworld() && player.interactionManager.getGameMode()==GameMode.SURVIVAL
                    && !player.getAbilities().flying && !player.getAbilities().allowFlying && !player.isSprinting()
                    && Math.abs(player.getX()-.5)<.05 && Math.abs(player.getY()-65)<.05 && Math.abs(player.getZ()-2.5)<.05,"Empty server binding/physics changed");
            var blocks=new ArrayList<BlockState>();
            for(int x=-2;x<=4;x++) for(int z=-2;z<=5;z++) for(int y=64;y<=70;y++) {
                var state=server.getOverworld().getBlockState(new BlockPos(x,y,z));
                require(state.equals((y==64?Blocks.BEDROCK:Blocks.AIR).getDefaultState()),"Empty server physical fixture changed at "+x+","+y+","+z);
                blocks.add(state);
            }
            var inventory=IntStream.range(0,player.getInventory().size()).mapToObj(slot -> player.getInventory().getStack(slot).copy()).toList();
            require(inventory.get(0).isOf(Items.STONE) && inventory.get(0).getCount()==7 && inventory.get(1).isOf(Items.STONE)
                    && inventory.get(1).getCount()==64 && player.getInventory().getSelectedSlot()==1,"Wrong empty server stock");
            for(int slot=2;slot<inventory.size();slot++) require(inventory.get(slot).isEmpty(),"Extra empty server inventory");
            var sample=new ServerSample(server.getTicks(),player.age,player.getEntityWorld().getTime(),player.getUuid(),List.copyOf(blocks),inventory,
                    player.getInventory().getSelectedSlot(),player.getX(),player.getY(),player.getZ());
            note("SERVER "+sample); return sample;
        });
    }

    private void closeOwned() {
        require(!closed,"Empty world double close"); closed=true;
        Throwable failure=null;
        try {
            context.runOnClient(client -> {
                try {
                    if(runId!=null) {
                        drain();
                        require(MapArtClient.status().engine().lifecycle().context().orElseThrow().runId().equals(runId),"Empty cleanup run replaced");
                        MapArtClient.stop();
                        require(MapArtClient.status().equals(terminal),"Empty stop changed terminal summary");
                    }
                } finally {
                    watch.clear(); // Observer dies before owned world teardown; terminal evidence is already retained.
                    if(autoJump!=null) client.options.getAutoJump().setValue(autoJump);
                }
            });
        } catch(RuntimeException | Error error) { failure=error; }
        try { if(world!=null) world.close(); }
        catch(RuntimeException | Error error) { if(failure==null) failure=error; else failure.addSuppressed(error); }
        finally { world=null; boundWorld=null; boundPlayer=null; boundHandler=null; boundConnection=null; boundServer=null; }
        if(failure instanceof RuntimeException runtime) throw runtime;
        if(failure instanceof Error error) throw error;
        note("WORLD CLOSED normally; run="+runId);
    }

    private void drain() {
        var samples=watch.copy();
        for(;writtenSamples<samples.size();writtenSamples++) {
            var sample=samples.get(writtenSamples); assertEmpty(sample); note(sample.line());
        }
    }

    private void screenshot(String name) {
        if(name.equals("success")) context.waitFor(client -> client.player!=null && client.worldRenderer.isRenderingReady(client.player.getBlockPos().down()),WAIT);
        var path=context.takeScreenshot(TestScreenshotOptions.of(name).withDestinationDir(artifacts).disableCounterPrefix());
        require(Files.isRegularFile(path),"Missing import screenshot "+path); note("screenshot="+path);
    }
    private void note(String message) {
        try { Files.writeString(artifacts.resolve("import.log"),message+System.lineSeparator(),StandardOpenOption.CREATE,StandardOpenOption.APPEND); }
        catch(IOException error) { throw new UncheckedIOException(error); }
    }
    private void failure(Throwable error) {
        var text=new StringWriter(); error.printStackTrace(new PrintWriter(text)); note("FAIL "+text);
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
