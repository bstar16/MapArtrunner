package com.mapartrunner.fabric.scenario;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.GridPos;
import com.mapartrunner.core.run.BuildEngine;
import com.mapartrunner.core.run.RunLifecycle.*;
import com.mapartrunner.fabric.MapArtClient;
import com.mapartrunner.fabric.mixin.ClientWorldPendingUpdateAccessor;
import com.mapartrunner.fabric.prediction.PendingPredictionView;
import com.mapartrunner.fabric.runtime.FabricBuildAdapter;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.ClientConnection;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Identifier;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Fixed two-lane fixture owner. No production reset API and no observation-time repairs. */
final class ScenarioRunner implements AutoCloseable {
    record ScenarioResult(UUID runId, BuildEngine.Snapshot terminal, String definitionHash,
                          String observedStableStartHash, List<String> mismatches, Path artifacts) {
        ScenarioResult { mismatches = List.copyOf(mismatches); }
    }
    private record Roots(Path module, Path game, Path build, Path evidence, Path saves) { }
    private record Side(JsonObject state, int age, long time, int serverTick, List<String> problems) { }
    private record Checkpoint(Side client, Side server, FabricBuildAdapter.Status status) { }
    private record Prior(FabricBuildAdapter.Status status, List<ScenarioObserver.Sample> samples,
                         PredictionFaults.Snapshot fault, PredictionFaults.StateSnapshot stateFault) { }
    private final ClientGameTestContext context;
    private final ScenarioObserver.Watch watch;
    private final UUID invocation = UUID.randomUUID();
    private final Roots roots;
    private final Path artifacts;
    private final boolean callerAutoJump;
    private TestSingleplayerContext world;
    private MinecraftServer server;
    private ServerWorld serverWorld;
    private ServerPlayerEntity serverPlayer;
    private ClientWorld clientWorld;
    private ClientPlayerEntity clientPlayer;
    private ClientPlayNetworkHandler handler;
    private ClientConnection connection;
    private Path save;
    private ScenarioDefinition admitted;
    private BuildPlan plan;
    private GridPos origin;
    private BuildEngine.Settings settings;
    private String observedHash;
    private Checkpoint stable;
    private ScenarioResult previous;
    private int resetOrdinal;
    private boolean ready;
    private boolean closed;
    private boolean optionsOwned;
    private final Set<UUID> runs = new HashSet<>();

    ScenarioRunner(ClientGameTestContext context, ScenarioObserver.Watch watch) {
        this.context = Objects.requireNonNull(context); this.watch = Objects.requireNonNull(watch);
        roots = roots(); // Read-only loaded-module authority precedes evidence/world creation.
        callerAutoJump = context.computeOnClient(c -> {
            require(c.world == null && c.getServer() == null && c.player == null,
                    "Runner must create its own world; cannot adopt an open world");
            return c.options.getAutoJump().getValue();
        });
        verifyResetGuards();
        artifacts = roots.evidence.resolve("two-lane-" + invocation);
        checkedDirectory(roots, artifacts);
    }

    /** F4 calls before world creation; these are always-active checks, never Java assert. */
    static void verifyResetGuards() {
        var r = roots();
        var d = ScenarioDefinition.load();
        var token = UUID.randomUUID();
        var count = new int[1];
        Runnable mutation = () -> count[0]++;
        rejected("wrong invocation", () -> guarded(token, UUID.randomUUID(), r, r.game,
                d.definitionHash(), d.definitionHash(), d.data(), mutation));
        rejected("escaped game", () -> guarded(token, token, r, r.game.resolve("../.."),
                d.definitionHash(), d.definitionHash(), d.data(), mutation));
        rejected("redirected root", () -> guarded(token, token, r, r.module,
                d.definitionHash(), d.definitionHash(), d.data(), mutation));
        rejected("wrong definition/hash", () -> guarded(token, token, r, r.game,
                d.definitionHash(), "0".repeat(64), d.data(), mutation));
        var wrongDefinition=d.data(); wrongDefinition.getAsJsonObject("player").addProperty("selectedSlot",2);
        rejected("wrong definition values with copied hash", () -> guarded(token,token,r,r.game,
                d.definitionHash(),d.definitionHash(),wrongDefinition,mutation));
        var badRegion = d.data(); badRegion.getAsJsonObject("world").getAsJsonObject("ownedRegion")
                .getAsJsonObject("max").addProperty("x", 14);
        rejected("outside bounded region", () -> guarded(token, token, r, r.game,
                d.definitionHash(), d.definitionHash(), badRegion, mutation));
        var container = d.data(); container.getAsJsonObject("world").getAsJsonArray("containers").add(new JsonObject());
        rejected("container", () -> guarded(token, token, r, r.game,
                d.definitionHash(), d.definitionHash(), container, mutation));
        require(count[0] == 0, "Refused preflight reached a mutation callback");
        guarded(token,token,r,r.game,d.definitionHash(),d.definitionHash(),d.data(),mutation);
        require(count[0]==1,"Valid preflight did not reach positive-control callback");
        var creations=new int[1];
        rejected("escaped saves parent before world creation", () -> guardedWorldCreation(r,r.game,r.game,() -> creations[0]++));
        rejected("redirected saves parent before world creation", () -> guardedWorldCreation(r,r.saves,r.module,() -> creations[0]++));
        require(creations[0]==0,"Rejected saves parent reached native world-creation callback");
        guardedWorldCreation(r,r.saves,r.saves,() -> creations[0]++);
        require(creations[0]==1,"Valid saves-parent positive control did not reach creation callback");
        verifyCloseGuards();
        var restores=new int[1];
        var check = r.evidence.resolve("archive-self-check-" + token);
        checkedDirectory(r, check);
        rejected("archive failure aborts restoration", () -> archiveThenRestore(() -> {
            writeNew(r, check.resolve("incomplete.txt"), "retained injected archive failure");
            throw new UncheckedIOException(new IOException("fixture archive-write injection"));
        }, () -> restores[0]++));
        require(restores[0] == 0 && !Files.exists(check.resolve("COMMITTED")),
                "Failed archive permitted reset restoration/commit");
        System.out.println("ScenarioRunner pre-world guard/archive self-checks passed; artifacts=" + check);
    }

    void reset(ScenarioDefinition definition) {
        preflight(definition); // Stale authority cannot invalidate readiness, stop, or archive.
        ready = false;
        if (world == null) createOwnedWorld();
        preflight(definition);
        Prior prior = context.computeOnClient(c -> {
            requireClient(c);
            MapArtClient.stop();
            requireReleased(c);
            // Copy before clear/arm: both owners retain diagnostics across stop.
            return new Prior(MapArtClient.status(), watch.copy(), PredictionFaults.snapshot(), PredictionFaults.stateSnapshot());
        });
        var stage = artifacts.resolve("reset-" + (++resetOrdinal));
        archiveThenRestore(() -> archive(stage, prior), () -> {
            require(!prior.fault.active() && prior.fault.failure().isEmpty()
                            && !prior.stateFault.active() && prior.stateFault.failure().isEmpty(),
                    "Unsupported active/failed fault; archived, retained without clearing");
            boolean ownHeldResponse = prior.fault.responses().stream().anyMatch(r -> runs.contains(r.runId()));
            require(!ownHeldResponse, "Owned withheld RESPONSE requires F4 original-invocation release after archive");
            context.runOnClient(c -> { requireClient(c); PredictionFaults.clear(); watch.clear(); });
            restore(definition);
            plan = definition.importPlan(stage.resolve("retained-source.schem"));
            var source = definition.data().getAsJsonObject("schematic");
            origin = grid(source.getAsJsonObject("origin"));
            var s = definition.data().getAsJsonObject("settings");
            settings = new BuildEngine.Settings(i(s,"halfWidth"), i(s,"stride"), b(s,"constantSprint"), i(s,"reservedHotbarSlots"));
            admitted = definition;
            awaitStable(stage);
            ready = true; // Only two independent advancing post-physics checkpoints grant start authority.
        });
    }

    ScenarioResult run(ScenarioDefinition definition) {
        preflight(definition);
        require(ready && admitted != null && definition.definitionHash().equals(admitted.definitionHash()),
                "Run requires this invocation's successful reset");
        require(runs.size() < i(definition.data().getAsJsonObject("budgets"),"repeatMaximum"), "Repeat bound exceeded");
        ready = false; // Consume before facade reentry; failure cannot reuse readiness.
        UUID id = context.computeOnClient(c -> {
            requireClient(c); requireReleased(c);
            var now = readClient(c, true);
            require(now.problems.isEmpty() && now.state.equals(stable.client.state), "Start state changed without physics");
            UUID fresh = MapArtClient.start(plan, origin, settings);
            require(runs.add(fresh), "Facade reused a run UUID");
            var bound = MapArtClient.status().engine().lifecycle().context().orElseThrow();
            require(bound.runId().equals(fresh) && bound.planFingerprint().equals(plan.fingerprint())
                    && bound.origin().equals(origin) && bound.settingsFingerprint().equals(settings.fingerprint())
                    && bound.dimension().equals("minecraft:overworld"), "Facade bound another start request");
            watch.arm(c, plan);
            return fresh;
        });
        var problems = new ArrayList<String>();
        BuildEngine.Snapshot terminal = null;
        int budget = i(definition.data().getAsJsonObject("budgets"),"runTicks");
        for (int tick=0; tick<budget; tick++) {
            context.waitTick();
            var status = context.computeOnClient(c -> { requireClient(c); enforceActionScope(); return MapArtClient.status(); });
            requireServerCheckpoint();
            if (status.engine().lifecycle().phase() == Phase.TERMINAL) { terminal = status.engine(); break; }
        }
        if (terminal == null) {
            problems.add("Run exceeded " + budget + " real physics ticks");
            terminal = context.computeOnClient(c -> { requireClient(c); MapArtClient.stop(); return MapArtClient.status().engine(); });
        }
        if (terminal.lifecycle().outcome().orElse(null) != Outcome.SUCCESS) problems.add("Terminal was not SUCCESS: " + terminal);
        problems.addAll(world.getServer().computeOnServer(s -> {
            requireServer(s);
            var errors = new ArrayList<String>();
            for (var target : plan.targets()) {
                var p = target.relativePos(); var at = new BlockPos(origin.x()+p.x(), origin.y()+p.y(), origin.z()+p.z());
                if (!s.getOverworld().getBlockState(at).equals(Blocks.STONE.getDefaultState())) errors.add("Final server target mismatch: " + at);
            }
            if (serverPlayer.getInventory().getStack(0).getCount()!=7
                    || serverPlayer.getInventory().getStack(1).getCount()+serverPlayer.getInventory().getStack(2).getCount()!=9)
                errors.add("Final reserved/usable stock differs");
            return errors;
        }));
        previous = new ScenarioResult(id, terminal, definition.definitionHash(), observedHash, problems, artifacts);
        writeNew(roots, artifacts.resolve("result-" + id + ".txt"), previous.toString());
        return previous;
    }

    void assertResult(ScenarioResult result) {
        require(result != null && runs.contains(result.runId()) && result.artifacts().equals(artifacts)
                && admitted != null && result.definitionHash().equals(admitted.definitionHash()), "Foreign result");
        require(result.mismatches().isEmpty() && result.terminal().lifecycle().outcome().orElse(null)==Outcome.SUCCESS,
                "Scenario failed: " + result);
    }

    private void createOwnedWorld() {
        checkRoots(roots);
        context.runOnClient(this::requireUncreatedClient);
        // WorldCreator's constructor already probes save names: guard the entire builder entry.
        try {
            if (!Files.exists(roots.saves,LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(roots.saves);
        } catch (IOException e) { throw new UncheckedIOException(e); }
        guardedWorldCreation(roots,roots.saves,real(roots.saves),() -> world=context.worldBuilder().create());
        server = world.getServer().computeOnServer(s -> {
            require(s.getOverworld().getSeed()==1 && s.getOverworld().getChunkManager().getChunkGenerator() instanceof FlatChunkGenerator,
                    "Owned world must be seed1/flat");
            require(s.getPlayerManager().getPlayerList().size()==1, "Fixture requires exactly one player");
            serverWorld=s.getOverworld();
            serverPlayer = s.getPlayerManager().getPlayerList().getFirst();
            return s;
        });
        save = real(world.getWorldSave().getSaveDirectory());
        require(save.getParent().equals(roots.saves)
                && real(server.getSavePath(WorldSavePath.ROOT)).equals(save), "Framework-created save escaped game authority");
        context.runOnClient(c -> {
            require(c.getServer()==server && c.world!=null && c.world.getRegistryKey().equals(World.OVERWORLD)
                    && c.player!=null && c.getNetworkHandler()!=null, "Missing creation binding");
            clientWorld=c.world; clientPlayer=c.player; handler=c.getNetworkHandler(); connection=handler.getConnection();
            requireClient(c);
        });
        writeNew(roots, artifacts.resolve("authority.txt"), "invocation=" + invocation + "; roots=" + roots + "; save=" + save
                + "; server=" + System.identityHashCode(server) + "; serverPlayer=" + System.identityHashCode(serverPlayer)
                + "; clientWorld=" + System.identityHashCode(clientWorld) + "; clientPlayer=" + System.identityHashCode(clientPlayer)
                + "; handler=" + System.identityHashCode(handler) + "; connection=" + System.identityHashCode(connection));
    }

    private void preflight(ScenarioDefinition d) {
        Objects.requireNonNull(d);
        guarded(invocation, invocation, roots, FabricLoader.getInstance().getGameDir(),
                ScenarioDefinition.load().definitionHash(), d.definitionHash(), d.data(), () -> { });
        require(!closed, "Invocation closed");
        if (admitted!=null) require(admitted.definitionHash().equals(d.definitionHash()), "Repeated start changed definition");
        if (world!=null) {
            context.runOnClient(this::requireClient);
            requireServerCheckpoint();
        } else context.runOnClient(this::requireUncreatedClient);
    }

    private void requireUncreatedClient(MinecraftClient c) {
        require(c.isOnThread() && !closed && world==null,"Invalid pre-creation invocation"); checkRoots(roots);
        require(c.world==null && c.getServer()==null && c.player==null,"Cannot create into a foreign open world");
        require(c.getLevelStorage().getSavesDirectory().toAbsolutePath().equals(roots.saves),"Foreign native saves directory");
    }

    private void requireClient(MinecraftClient c) {
        require(c.isOnThread(), "Not client thread"); checkSave();
        require(c.getServer()==server && c.world==clientWorld && c.player==clientPlayer && c.getNetworkHandler()==handler
                && handler.getConnection()==connection && connection.isOpen() && handler.getWorld()==clientWorld
                && clientPlayer.getEntityWorld()==clientWorld && clientWorld.getRegistryKey().equals(World.OVERWORLD)
                && c.getLevelStorage().getSavesDirectory().toAbsolutePath().equals(roots.saves), "Stale client authority");
    }

    private void requireServer(MinecraftServer s) {
        checkSave();
        require(s==server && s.getOverworld()==serverWorld && serverWorld==serverPlayer.getEntityWorld() && serverWorld.getRegistryKey().equals(World.OVERWORLD)
                && s.getPlayerManager().getPlayerList().size()==1 && s.getPlayerManager().getPlayerList().getFirst()==serverPlayer
                && serverPlayer.getUuid().equals(clientPlayer.getUuid()), "Stale server/player/dimension authority");
    }

    private void requireServerCheckpoint() { world.getServer().runOnServer(this::requireServer); }

    private void checkSave() {
        require(!closed, "Invocation closed"); checkRoots(roots);
        require(real(world.getWorldSave().getSaveDirectory()).equals(save)
                && real(server.getSavePath(WorldSavePath.ROOT)).equals(save)
                && real(roots.saves).equals(roots.saves) && save.getParent().equals(roots.saves), "Save authority redirected");
    }

    private void archive(Path stage, Prior prior) {
        preflight(admitted == null ? ScenarioDefinition.load() : admitted);
        checkedDirectory(roots, stage);
        writeNew(roots, stage.resolve("prior-result.txt"), String.valueOf(previous));
        writeNew(roots, stage.resolve("prior-status-transitions.txt"), prior.status.toString());
        writeNew(roots, stage.resolve("prior-observer.txt"), prior.samples.stream().map(ScenarioObserver.Sample::line).reduce("", (a,b) -> a+b+"\n"));
        writeNew(roots, stage.resolve("prior-fault.txt"), prior.fault + "\n" + prior.stateFault);
        writeNew(roots, stage.resolve("prior-mismatches.txt"), previous==null ? "no previous result" : previous.mismatches.toString());
        Path screenshot = context.takeScreenshot(TestScreenshotOptions.of("before-reset").withDestinationDir(stage).disableCounterPrefix());
        require(real(screenshot).startsWith(real(stage)) && Files.isRegularFile(screenshot), "Archive screenshot absent/escaped");
        copyNew(roots, roots.game.resolve("logs/latest.log"), stage.resolve("minecraft-latest.log"));
        // COMMITTED is last; any archive exception leaves observer/fault/world untouched after stop.
        writeNew(roots, stage.resolve("COMMITTED"), "archive complete; invocation=" + invocation);
    }

    private static void archiveThenRestore(Runnable archive, Runnable restore) { archive.run(); restore.run(); }

    private void restore(ScenarioDefinition d) {
        var data=d.data(); var player=data.getAsJsonObject("player");
        world.getServer().runOnServer(s -> {
            requireServer(s);
            var level=s.getOverworld();
            var entities=level.getOtherEntities(serverPlayer, regionBox(), e -> true);
            // This fixture never breaks blocks or drops items; no arbitrary entity gets reset ownership.
            require(entities.isEmpty(), "Unowned bounded entities: " + entities);
            for (var cell:data.getAsJsonObject("world").getAsJsonArray("blocks")) {
                var value=cell.getAsJsonObject(); level.setBlockState(pos(value.getAsJsonObject("position")), block(value.getAsJsonObject("block")));
            }
            var p=serverPlayer;
            p.closeHandledScreen(); p.changeGameMode(GameMode.SURVIVAL);
            restorePlayer(p, player, true);
            p.setPlayerInput(PlayerInput.DEFAULT);
            var xyz=player.getAsJsonObject("position"); var rotation=player.getAsJsonObject("rotation");
            require(p.teleport(level,n(xyz,"x"),n(xyz,"y"),n(xyz,"z"),Set.of(),(float)n(rotation,"yaw"),(float)n(rotation,"pitch"),false), "Reset teleport rejected");
            p.sendAbilitiesUpdate(); p.getInventory().markDirty(); p.playerScreenHandler.syncState();
        });
        context.runOnClient(c -> {
            requireClient(c);
            optionsOwned=true;
            c.options.getAutoJump().setValue(b(player,"autoJump"));
            // Reset owns private local hunger/menu/input/angles; subsequent readiness performs reads only.
            c.setScreen(null);
            restorePlayer(c.player, player, false);
            c.player.getInventory().setSelectedSlot(i(player,"selectedSlot"));
            c.player.input.playerInput=PlayerInput.DEFAULT;
        });
    }

    private static void restorePlayer(PlayerEntity p, JsonObject expected, boolean serverOwned) {
        p.dismountVehicle(); p.stopGliding(); p.clearStatusEffects(); p.setSwimming(false); p.setSprinting(false); p.setSneaking(false);
        p.setNoGravity(false); p.setNoDrag(false); p.setJumping(false); p.setVelocity(Vec3d.ZERO); p.setOnGround(true);
        p.fallDistance=0; p.stopUsingItem();
        if (serverOwned) { p.setHealth((float)n(expected,"health")); p.getAbilities().unpack(abilities(expected)); }
        var defaults=PlayerEntity.createPlayerAttributes().build();
        for (var attr:List.of(EntityAttributes.GRAVITY,EntityAttributes.MOVEMENT_SPEED,EntityAttributes.JUMP_STRENGTH,
                EntityAttributes.FLYING_SPEED,EntityAttributes.SNEAKING_SPEED)) {
            var instance=p.getAttributeInstance(attr);
            require(instance!=null, "Missing baseline player attribute " + attr);
            instance.clearModifiers(); instance.setBaseValue(defaults.getBaseValue(attr));
        }
        for (var instance:p.getAttributes().getAttributesToSend()) {
            require(defaults.has(instance.getAttribute()),"Unknown tracked player attribute " + instance.getAttribute());
            instance.clearModifiers(); instance.setBaseValue(defaults.getBaseValue(instance.getAttribute()));
        }
        var reporter=new ErrorReporter.Impl(); var hunger=new NbtCompound();
        hunger.putInt("foodLevel",i(expected,"hunger")); hunger.putInt("foodTickTimer",0);
        hunger.putFloat("foodSaturationLevel",(float)n(expected,"saturation")); hunger.putFloat("foodExhaustionLevel",(float)n(expected,"exhaustion"));
        p.getHungerManager().readData(NbtReadView.create(reporter,p.getEntityWorld().getRegistryManager(),hunger));
        require(reporter.isEmpty(), "Hunger restore codec error: " + reporter.getErrorsAsString());
        require(p.currentScreenHandler==p.playerScreenHandler, "Reset must close non-player handler");
        p.playerScreenHandler.setCursorStack(ItemStack.EMPTY); p.playerScreenHandler.getCraftingInput().clear();
        p.playerScreenHandler.getOutputSlot().setStack(ItemStack.EMPTY);
        if (serverOwned) {
            p.getInventory().clear();
            for (var slot:expected.getAsJsonArray("inventory")) {
                var value=slot.getAsJsonObject(); p.getInventory().setStack(i(value,"slot"),stack(value));
            }
        }
        p.getInventory().setSelectedSlot(i(expected,"selectedSlot"));
        var r=expected.getAsJsonObject("rotation");
        p.setYaw((float)n(r,"yaw")); p.lastYaw=p.getYaw(); p.setPitch((float)n(r,"pitch")); p.lastPitch=p.getPitch();
        p.setHeadYaw((float)n(r,"headYaw")); p.lastHeadYaw=p.headYaw;
        p.setBodyYaw((float)n(r,"bodyYaw")); p.lastBodyYaw=p.bodyYaw;
    }

    private void awaitStable(Path stage) {
        Checkpoint previousCheckpoint=null;
        int budget=i(admitted.data().getAsJsonObject("budgets"),"readinessTicks");
        for (int tick=1; tick<=budget; tick++) {
            context.waitTick();
            // waitTick returns in Fabric TEST; each callback finishes before the other is requested.
            var client=context.computeOnClient(c -> { requireClient(c); return readClient(c,true); });
            var status=context.computeOnClient(c -> { requireClient(c); return MapArtClient.status(); });
            var remote=world.getServer().computeOnServer(s -> { requireServer(s); return readPlayer(serverPlayer,true,s.getTicks()); });
            var checkpoint=new Checkpoint(client,remote,status);
            writeNew(roots,stage.resolve("readiness-"+tick+".json"), evidence(checkpoint).toString());
            boolean passing=client.problems.isEmpty() && remote.problems.isEmpty();
            if (passing && previousCheckpoint!=null && advancing(previousCheckpoint,checkpoint)) {
                var first=checkpointJson(previousCheckpoint); var second=checkpointJson(checkpoint);
                observedHash=hash(second.toString()); stable=checkpoint;
                writeNew(roots,stage.resolve("stable-start.json"), "{\"firstHash\":\""+hash(first.toString())+"\",\"secondHash\":\""+observedHash
                        +"\",\"byteEqual\":"+first.equals(second)+",\"rawDelta\":"+deltas(previousCheckpoint,checkpoint)+",\"first\":"+evidence(previousCheckpoint)+",\"second\":"+evidence(checkpoint)+"}");
                return;
            }
            previousCheckpoint=passing ? checkpoint : null;
        }
        throw new AssertionError("Readiness failed within " + budget + " actual physics ticks; retained " + stage);
    }

    private Side readClient(MinecraftClient c, boolean initial) {
        var side=readPlayer(c.player,initial,-1); var errors=new ArrayList<>(side.problems);
        var state=side.state;
        var sample=watch.sample(c,"TEST");
        state.add("keys",new Gson().toJsonTree(sample.keys()));
        state.addProperty("paused",c.isPaused()); state.addProperty("screenOpen",c.currentScreen!=null);
        state.addProperty("autoJump",c.options.getAutoJump().getValue());
        state.addProperty("mode",c.interactionManager==null ? "missing" : c.interactionManager.getCurrentGameMode().name());
        state.addProperty("input",c.player.input.playerInput.toString());
        state.addProperty("lastInput",c.player.getLastPlayerInput().toString());
        state.addProperty("movementInput",c.player.input.getMovementInput().toString());
        if (c.currentScreen!=null || c.isPaused() || c.options.getAutoJump().getValue() || sample.keys().stream().anyMatch(Boolean::booleanValue)
                || c.interactionManager==null || c.interactionManager.getCurrentGameMode()!=GameMode.SURVIVAL
                || !c.player.input.playerInput.equals(PlayerInput.DEFAULT) || !c.player.getLastPlayerInput().equals(PlayerInput.DEFAULT)
                || c.player.input.getMovementInput().lengthSquared()!=0) errors.add("Client screen/options/mode/input/keys not neutral");
        var status=MapArtClient.status();
        state.addProperty("controlOwner",status.controlOwner().name());
        state.addProperty("productionActive",status.engine().lifecycle().phase()==Phase.RUNNING || status.engine().lifecycle().phase()==Phase.PAUSED);
        if (status.controlOwner()!=BuildEngine.ControlOwner.NONE || b(state,"productionActive")) errors.add("Production active/controls owned");
        var fault=PredictionFaults.snapshot(); var stateFault=PredictionFaults.stateSnapshot();
        state.addProperty("faultActive",fault.active() || stateFault.active());
        state.addProperty("faultFailure",fault.failure().orElse("")+stateFault.failure().orElse(""));
        if (fault.active() || stateFault.active() || fault.failure().isPresent() || stateFault.failure().isPresent()) errors.add("Fixture fault active/failed");
        Object pending=((ClientWorldPendingUpdateAccessor)c.world).mapart$getPendingUpdateManager();
        require(pending instanceof PendingPredictionView, "Read-only scoped pending view unavailable");
        var predicted=new JsonArray();
        for (var cell:admitted.data().getAsJsonObject("world").getAsJsonArray("blocks")) {
            var position=pos(cell.getAsJsonObject().getAsJsonObject("position"));
            if (((PendingPredictionView)pending).mapart$hasPendingPrediction(position)) predicted.add(position.toShortString());
        }
        state.add("pendingOwnedPositions",predicted);
        state.addProperty("predictionScope","all 1568 authorized region positions; fixture-only interactions, sole player; no global queue claim");
        if (!predicted.isEmpty()) errors.add("Owned predictions have not drained naturally");
        return new Side(state,side.age,side.time,-1,List.copyOf(errors));
    }

    private Side readPlayer(PlayerEntity p, boolean initial, int serverTick) {
        var errors=new ArrayList<String>(); var actual=new JsonObject();
        var expected=admitted.data().getAsJsonObject("player"); var level=p.getEntityWorld();
        actual.addProperty("dimension",level.getRegistryKey().getValue().toString());
        var xyz=expected.getAsJsonObject("position"); var rot=expected.getAsJsonObject("rotation");
        String[] components={"x","y","z","vx","vy","vz","yaw","headYaw","bodyYaw","pitch"};
        double[] values={p.getX(),p.getY(),p.getZ(),p.getVelocity().x,p.getVelocity().y,p.getVelocity().z,p.getYaw(),p.headYaw,p.bodyYaw,p.getPitch()};
        for (int k=0;k<components.length;k++) {
            actual.addProperty(components[k],Double.toHexString(values[k]));
            if (!Double.isFinite(values[k])) errors.add("Nonfinite " + components[k]);
        }
        for (var axis:List.of("x","y","z")) if (Math.abs(Double.valueOf(actual.get(axis).getAsString())-n(xyz,axis))>1e-6) errors.add("Position " + axis);
        for (var angle:List.of("yaw","headYaw","bodyYaw","pitch")) if (Math.abs(MathHelper.wrapDegrees(Double.valueOf(actual.get(angle).getAsString())-n(rot,angle)))>1e-4) errors.add("Rotation " + angle);
        if (Math.abs(p.getVelocity().x)>1e-7 || Math.abs(p.getVelocity().z)>1e-7
                || Double.doubleToRawLongBits(p.getVelocity().y)!=0xBFB41205C28F5C29L) errors.add("Post-physics velocity (exact Y required)");
        actual.addProperty("health",p.getHealth()); actual.addProperty("abilities",p.getAbilities().pack().toString());
        if (p.getHealth()!=(float)n(expected,"health") || !p.getAbilities().pack().equals(abilities(expected))) errors.add("Health/complete abilities");
        var reporter=new ErrorReporter.Impl(); var hunger=NbtWriteView.create(reporter,level.getRegistryManager()); p.getHungerManager().writeData(hunger);
        require(reporter.isEmpty(),"Hunger observation codec error: "+reporter.getErrorsAsString());
        var hungerData=hunger.getNbt(); actual.addProperty("completeHunger",hungerData.toString());
        if (hungerData.getInt("foodLevel",-1)!=i(expected,"hunger") || hungerData.getInt("foodTickTimer",-1)!=0
                || hungerData.getFloat("foodSaturationLevel",-1)!=(float)n(expected,"saturation")
                || hungerData.getFloat("foodExhaustionLevel",-1)!=(float)n(expected,"exhaustion")) errors.add("Complete hunger");
        var inventory=new JsonArray();
        require(p.getInventory().size()==43,"Pinned native inventory is not 43 slots");
        for (var slot:expected.getAsJsonArray("inventory")) {
            var value=slot.getAsJsonObject(); var found=p.getInventory().getStack(i(value,"slot"));
            inventory.add(ItemStack.OPTIONAL_CODEC.encodeStart(level.getRegistryManager().getOps(JsonOps.INSTANCE),found).getOrThrow());
            if (!ItemStack.areEqual(found,stack(value))) errors.add("Full inventory/components slot " + i(value,"slot"));
        }
        actual.add("inventory",inventory); actual.addProperty("selectedSlot",p.getInventory().getSelectedSlot());
        if (p.getInventory().getSelectedSlot()!=i(expected,"selectedSlot")) errors.add("Selected slot");
        var menu=new JsonArray();
        menu.add(ItemStack.OPTIONAL_CODEC.encodeStart(level.getRegistryManager().getOps(JsonOps.INSTANCE),p.currentScreenHandler.getCursorStack()).getOrThrow());
        for (int k=0;k<p.playerScreenHandler.getCraftingInput().size();k++) menu.add(ItemStack.OPTIONAL_CODEC.encodeStart(level.getRegistryManager().getOps(JsonOps.INSTANCE),p.playerScreenHandler.getCraftingInput().getStack(k)).getOrThrow());
        menu.add(ItemStack.OPTIONAL_CODEC.encodeStart(level.getRegistryManager().getOps(JsonOps.INSTANCE),p.playerScreenHandler.getOutputSlot().getStack()).getOrThrow());
        actual.add("cursorAndCrafting",menu); actual.addProperty("playerHandler",p.currentScreenHandler==p.playerScreenHandler);
        if (p.currentScreenHandler!=p.playerScreenHandler || !p.currentScreenHandler.getCursorStack().isEmpty()
                || !p.playerScreenHandler.getCraftingInput().isEmpty() || !p.playerScreenHandler.getOutputSlot().getStack().isEmpty()) errors.add("Player cursor/crafting/handler");
        var guards=new JsonObject();
        guards.addProperty("onGround",p.isOnGround()); guards.addProperty("noGravity",p.hasNoGravity()); guards.addProperty("noDrag",p.hasNoDrag());
        guards.addProperty("jumping",p.isJumping()); guards.addProperty("vehicle",p.hasVehicle()); guards.addProperty("fluid",p.isInFluid());
        guards.addProperty("water",p.isTouchingWater()); guards.addProperty("submerged",p.isSubmergedInWater()); guards.addProperty("lava",p.isInLava());
        guards.addProperty("climbing",p.isClimbing()); guards.addProperty("climbingPos",p.getClimbingPos().toString()); guards.addProperty("gliding",p.isGliding());
        guards.addProperty("swimming",p.isSwimming()); guards.addProperty("sprinting",p.isSprinting()); guards.addProperty("flying",p.getAbilities().flying);
        guards.addProperty("sneaking",p.isSneaking()); guards.addProperty("usingItem",p.isUsingItem()); guards.addProperty("fallDistance",p.fallDistance);
        guards.addProperty("effects",p.getStatusEffects().toString());
        guards.addProperty("gravityBase",p.getAttributeBaseValue(EntityAttributes.GRAVITY)); guards.addProperty("gravityValue",p.getAttributeValue(EntityAttributes.GRAVITY));
        guards.addProperty("finalGravity",p.getFinalGravity());
        // Pinned effective getter differs only with slow-falling; all effects are independently absent here.
        guards.addProperty("sourceDerivedEffectiveGravity",p.getStatusEffects().isEmpty() && !p.hasNoGravity() ? Double.toHexString(p.getFinalGravity()) : "guards failed: unknown");
        var attrs=new TreeMap<String,String>();
        var defaults=PlayerEntity.createPlayerAttributes().build();
        for (var attr:p.getAttributes().getAttributesToSend()) {
            attrs.put(attr.getAttribute().getIdAsString(),"base="+Double.toHexString(attr.getBaseValue())+";value="+Double.toHexString(attr.getValue())
                    +";modifiers="+attr.getModifiers().stream().map(Object::toString).sorted().toList());
            if (!defaults.has(attr.getAttribute()) || attr.getBaseValue()!=defaults.getBaseValue(attr.getAttribute())
                    || attr.getValue()!=defaults.getValue(attr.getAttribute()) || !attr.getModifiers().isEmpty()) errors.add("Tracked attribute/modifier " + attr.getAttribute());
        }
        guards.add("trackedAttributes",new Gson().toJsonTree(attrs));
        for (var attr:List.of(EntityAttributes.GRAVITY,EntityAttributes.MOVEMENT_SPEED,EntityAttributes.JUMP_STRENGTH,EntityAttributes.FLYING_SPEED,EntityAttributes.SNEAKING_SPEED)) {
            if (p.getAttributeBaseValue(attr)!=defaults.getBaseValue(attr) || p.getAttributeValue(attr)!=defaults.getValue(attr)
                    || p.getAttributes().getAttributesToSend().stream().anyMatch(a -> a.getAttribute().equals(attr) && !a.getModifiers().isEmpty())) errors.add("Motion attribute/modifier " + attr);
        }
        if (!p.isOnGround() || p.hasNoGravity() || p.hasNoDrag() || p.isJumping() || p.hasVehicle() || p.isInFluid() || p.isTouchingWater()
                || p.isSubmergedInWater() || p.isInLava() || p.isClimbing() || p.isGliding() || p.isSwimming() || p.isSprinting()
                || p.getAbilities().flying || p.isSneaking() || p.isUsingItem() || p.fallDistance!=0 || !p.getStatusEffects().isEmpty() || p.getAttributeBaseValue(EntityAttributes.GRAVITY)!=.08
                || p.getAttributeValue(EntityAttributes.GRAVITY)!=.08 || p.getFinalGravity()!=.08) errors.add("Ordinary physics guards");
        var support=new BlockPos(0,65,2);
        boolean loaded=level.getChunkManager().getChunk(0,0,ChunkStatus.FULL,false)!=null;
        guards.addProperty("supportLoaded",loaded); guards.addProperty("support",level.getBlockState(support).toString());
        guards.addProperty("supportFullCube",level.getBlockState(support).isFullCube(level,support));
        guards.addProperty("supportingBlock",p.supportingBlockPos.toString()); guards.addProperty("supportedByStone",p.isSupportedBy(support));
        if (!loaded || !level.getBlockState(support).equals(Blocks.STONE.getDefaultState()) || !level.getBlockState(support).isFullCube(level,support)
                || !p.isSupportedBy(support) || !level.getBlockState(support.up()).isAir() || !level.getBlockState(support.up(2)).isAir()) errors.add("Loaded full-cube support/clearance");
        actual.add("physicsGuards",guards);
        var blocks=new JsonArray();
        for (var cell:admitted.data().getAsJsonObject("world").getAsJsonArray("blocks")) {
            var value=cell.getAsJsonObject(); var at=pos(value.getAsJsonObject("position"));
            boolean chunk=level.getChunkManager().getChunk(Math.floorDiv(at.getX(),16),Math.floorDiv(at.getZ(),16),ChunkStatus.FULL,false)!=null;
            var found=level.getBlockState(at); blocks.add(at.toShortString()+"="+found+";loaded="+chunk+";blockEntity="+(level.getBlockEntity(at)!=null));
            if (!chunk || level.getBlockEntity(at)!=null || (initial && !found.equals(block(value.getAsJsonObject("block"))))) errors.add("Owned block/loaded/block entity " + at);
        }
        actual.add("blocks",blocks); actual.add("containers",new JsonArray());
        var entities=level.getOtherEntities(p,regionBox(),e -> true);
        actual.add("boundedEntities",new Gson().toJsonTree(entities.stream().map(e -> e.getType().toString()+":"+e.getUuid()).sorted().toList()));
        if (!entities.isEmpty()) errors.add("Unexpected bounded non-player entities");
        if (p instanceof ServerPlayerEntity sp) {
            actual.addProperty("mode",sp.getGameMode().name()); actual.addProperty("input",sp.getPlayerInput().toString());
            if (sp.getGameMode()!=GameMode.SURVIVAL || !sp.getPlayerInput().equals(PlayerInput.DEFAULT)) errors.add("Server mode/input");
        }
        return new Side(actual,p.age,level.getTime(),serverTick,List.copyOf(errors));
    }

    private JsonObject checkpointJson(Checkpoint cp) {
        var value=new JsonObject(); value.add("client",cp.client.state); value.add("server",cp.server.state);
        var request=new JsonObject(); request.addProperty("provenance","actual retained/imported runner-held request; production binding asserted once after start");
        request.addProperty("plan",plan.fingerprint()); request.addProperty("origin",origin.toString()); request.addProperty("settings",settings.fingerprint());
        value.add("startRequest",request); value.addProperty("definitionHash",admitted.definitionHash());
        return sorted(value).getAsJsonObject();
    }

    private JsonObject evidence(Checkpoint cp) {
        var out=new JsonObject(); out.add("equalityState",checkpointJson(cp));
        for (String side:List.of("client","server")) {
            var sample=side.equals("client")?cp.client:cp.server; var clocks=new JsonObject();
            clocks.addProperty("age",sample.age); clocks.addProperty("worldTime",sample.time); clocks.addProperty("serverTick",sample.serverTick);
            clocks.add("problems",new Gson().toJsonTree(sample.problems)); out.add(side+"Checkpoint",clocks);
        }
        out.addProperty("invocation",invocation.toString()); out.addProperty("productionStatus",cp.status.toString());
        return out;
    }

    private static boolean advancing(Checkpoint a, Checkpoint b) {
        return b.client.age>a.client.age && b.client.time>a.client.time && b.server.age>a.server.age
                && b.server.time>a.server.time && b.server.serverTick>a.server.serverTick;
    }

    private static JsonObject deltas(Checkpoint a, Checkpoint b) {
        var result=new JsonObject();
        for (String side:List.of("client","server")) {
            var first=side.equals("client")?a.client:a.server; var second=side.equals("client")?b.client:b.server;
            var delta=new JsonObject();
            for (String key:List.of("x","y","z","vx","vy","vz","yaw","headYaw","bodyYaw","pitch"))
                delta.addProperty(key,Double.toHexString(Double.valueOf(second.state.get(key).getAsString())-Double.valueOf(first.state.get(key).getAsString())));
            delta.addProperty("ageDelta",second.age-first.age); delta.addProperty("timeDelta",second.time-first.time);
            delta.addProperty("serverTickDelta",second.serverTick-first.serverTick); result.add(side,delta);
        }
        return result;
    }

    private void enforceActionScope() {
        for (var sample:watch.copy()) for (var effect:sample.status().engine().effectResults()) {
            var target=plan.targets().stream().filter(t -> t.id()==effect.targetId()).findFirst().orElseThrow();
            var p=target.relativePos(); require(regionBox().contains(origin.x()+p.x()+.5,origin.y()+p.y()+.5,origin.z()+p.z()+.5), "Issued action escaped prediction scope");
        }
    }

    private static void requireReleased(MinecraftClient c) {
        var o=c.options;
        require(List.of(o.forwardKey,o.backKey,o.leftKey,o.rightKey,o.jumpKey,o.sneakKey,o.sprintKey,o.useKey,o.attackKey)
                .stream().noneMatch(k -> k.isPressed()) && MapArtClient.status().controlOwner()==BuildEngine.ControlOwner.NONE,
                "Stop did not release nine keys/NONE");
    }

    @Override public void close() {
        if (closed) return;
        closeOwned(world!=null,
                () -> preflight(admitted==null?ScenarioDefinition.load():admitted),
                () -> {
                    ready=false;
                    context.runOnClient(c -> { requireClient(c); MapArtClient.stop(); requireReleased(c); });
                },
                () -> context.runOnClient(c -> {
                    requireClient(c);
                    if (optionsOwned) { c.options.getAutoJump().setValue(callerAutoJump); optionsOwned=false; }
                }),
                () -> context.runOnClient(c -> { requireClient(c); watch.closeWorld(); }),
                () -> { world.close(); closed=true; }); // Keep identities through native close, even if an earlier cleanup failed.
        closed=true;
    }

    private static void closeOwned(boolean created, Runnable authority, Runnable stop, Runnable options,
                                   Runnable observer, Runnable close) {
        if (!created) return; // An unused invocation never owned an option mutation or a world.
        authority.run(); // Initial foreign authority forbids every cleanup callback.
        Throwable error=null;
        try { authority.run(); stop.run(); } catch (RuntimeException | Error cleanup) { error=retain(error,cleanup); }
        try { authority.run(); options.run(); } catch (RuntimeException | Error cleanup) { error=retain(error,cleanup); }
        try { authority.run(); observer.run(); } catch (RuntimeException | Error cleanup) { error=retain(error,cleanup); }
        try { authority.run(); close.run(); } catch (RuntimeException | Error cleanup) { error=retain(error,cleanup); }
        if (error instanceof Error fatal) throw fatal;
        if (error!=null) throw (RuntimeException)error;
    }

    private static Throwable retain(Throwable first, Throwable next) {
        if (first==null) return next;
        if (first!=next) {
            for (var prior:first.getSuppressed()) if (prior==next) return first;
            first.addSuppressed(next);
        }
        return first;
    }

    private static void verifyCloseGuards() {
        var mutations=new int[1]; Runnable mutation=() -> mutations[0]++;
        Runnable foreign=() -> { throw new AssertionError("changed close authority"); };
        closeOwned(false,foreign,mutation,mutation,mutation,mutation);
        require(mutations[0]==0,"Unused close reached an option/native mutation under changed authority");
        rejected("foreign close authority",() -> closeOwned(true,foreign,mutation,mutation,mutation,mutation));
        require(mutations[0]==0,"Foreign close reached a cleanup mutation");
        closeOwned(true,() -> { },mutation,mutation,mutation,mutation);
        require(mutations[0]==4,"Valid close positive control did not attempt all four cleanup steps");
        var steps=new ArrayList<String>();
        var first=new IllegalStateException("test-owned stop/release failure");
        var later=new AssertionError("test-owned observer cleanup failure");
        Throwable observed=null;
        try {
            closeOwned(true,() -> { },() -> { steps.add("stop"); throw first; },
                    () -> steps.add("options restored"),() -> { steps.add("observer"); throw later; },() -> steps.add("world close attempted"));
        } catch (RuntimeException | Error error) { observed=error; }
        require(observed==first && Arrays.equals(first.getSuppressed(),new Throwable[]{later})
                && steps.equals(List.of("stop","options restored","observer","world close attempted")),
                "Close lost primary/suppressed failure or skipped independently safe cleanup");
        var valid=new boolean[]{true}; var attempts=new int[1]; var writes=new int[1];
        Runnable changing=() -> { attempts[0]++; require(valid[0],"close authority changed between steps"); };
        rejected("changed authority between cleanup steps",() -> closeOwned(true,changing,
                () -> valid[0]=false,() -> writes[0]++,() -> writes[0]++,() -> writes[0]++));
        require(attempts[0]==5 && writes[0]==0,"Cleanup failed to recheck changed authority before remaining writes");
        System.out.println("ScenarioRunner unused/failed/foreign close self-checks passed");
    }

    private static Roots roots() {
        try {
            var source=Path.of(ScenarioRunner.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            require(Files.isDirectory(source) && source.endsWith(Path.of("build","classes","java","gametest")), "Unsupported loaded fixture origin: " + source);
            require(source.equals(source.toRealPath()), "Loaded module origin redirected");
            var module=source.getParent().getParent().getParent().getParent().toRealPath();
            require(module.endsWith(Path.of("v2","fabric")) && Files.isRegularFile(module.resolve("build.gradle")), "Unsupported module root");
            var definition=Path.of(ScenarioDefinition.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            require(source.equals(definition), "Definition loaded from foreign module");
            var build=module.resolve("build").toRealPath(); var game=build.resolve("run/clientGameTest").toRealPath();
            var actual=FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
            require(actual.equals(game) && actual.toRealPath().equals(game) && game.equals(build.resolve("run/clientGameTest")), "Unmanaged/redirected game root");
            return new Roots(module,game,build,build.resolve("scenario-results"),game.resolve("saves"));
        } catch (java.net.URISyntaxException | IOException e) { throw new IllegalStateException("Cannot establish loaded-module authority",e); }
    }

    private static void guarded(UUID owner, UUID token, Roots r, Path game, String expectedHash, String hash, JsonObject d, Runnable mutation) {
        require(owner.equals(token),"Wrong invocation token"); checkRoots(r);
        require(game.toAbsolutePath().normalize().equals(r.game) && real(game).equals(r.game),"Wrong/redirected game path");
        require(expectedHash.equals(hash),"Wrong definition hash");
        require(d.equals(ScenarioDefinition.load().data()), "Wrong definition values");
        var w=d.getAsJsonObject("world"); var region=w.getAsJsonObject("ownedRegion");
        require(grid(region.getAsJsonObject("min")).equals(new GridPos(-2,64,-2))
                && grid(region.getAsJsonObject("max")).equals(new GridPos(13,70,11)), "Region exceeds authority");
        require(w.getAsJsonArray("containers").isEmpty(),"Containers unsupported");
        for (var cell:w.getAsJsonArray("blocks")) {
            var at=pos(cell.getAsJsonObject().getAsJsonObject("position"));
            require(at.getX()>=-2 && at.getX()<=13 && at.getY()>=64 && at.getY()<=70 && at.getZ()>=-2 && at.getZ()<=11,"Block outside authority");
        }
        mutation.run();
    }

    private static void checkRoots(Roots r) {
        require(real(r.module).equals(r.module) && real(r.build).equals(r.build) && real(r.game).equals(r.game)
                && r.build.equals(r.module.resolve("build")) && r.game.equals(r.build.resolve("run/clientGameTest")),"Redirected invocation roots");
        if (Files.exists(r.evidence)) require(real(r.evidence).equals(r.evidence),"Redirected evidence root");
        require(r.saves.equals(r.game.resolve("saves")),"Saves parent escaped authorized game root");
        if (Files.exists(r.saves,LinkOption.NOFOLLOW_LINKS))
            require(Files.isDirectory(r.saves) && real(r.saves).equals(r.saves),"Redirected saves parent");
    }
    private static void guardedWorldCreation(Roots r, Path requested, Path resolved, Runnable create) {
        checkRoots(r);
        require(requested.toAbsolutePath().normalize().equals(r.saves) && resolved.equals(r.saves),"Wrong/redirected saves parent before native creation");
        create.run();
    }
    private static void checkedDirectory(Roots r, Path path) {
        checkRoots(r); require(path.toAbsolutePath().normalize().startsWith(r.evidence),"Evidence path escape");
        try {
            // Validate each existing parent before creating the next directory, including reparse resolution.
            Path current=r.build;
            for (var segment:r.build.relativize(path)) {
                current=current.resolve(segment);
                if (!Files.exists(current)) Files.createDirectory(current);
                require(real(current).equals(current),"Redirected archive directory: "+current);
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private static void writeNew(Roots r, Path file, String text) {
        checkRoots(r); require(real(file.getParent()).equals(file.getParent()) && file.startsWith(r.evidence),"Archive write escaped");
        try { Files.writeString(file,text,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private static void copyNew(Roots r, Path from, Path to) {
        checkRoots(r); require(real(to.getParent()).equals(to.getParent()) && to.startsWith(r.evidence)
                && real(from).startsWith(r.game),"Archive copy escaped");
        try { Files.copy(from,to); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private static Path real(Path path) { try { return path.toRealPath(); } catch (IOException e) { throw new UncheckedIOException(e); } }
    private static Box regionBox() { return new Box(-2,64,-2,14,71,12); }
    private static GridPos grid(JsonObject p) { return new GridPos(i(p,"x"),i(p,"y"),i(p,"z")); }
    private static BlockPos pos(JsonObject p) { return new BlockPos(i(p,"x"),i(p,"y"),i(p,"z")); }
    private static int i(JsonObject o,String key) { return o.get(key).getAsInt(); }
    private static double n(JsonObject o,String key) { return o.get(key).getAsDouble(); }
    private static boolean b(JsonObject o,String key) { return o.get(key).getAsBoolean(); }
    private static ItemStack stack(JsonObject slot) {
        int count=i(slot,"count"); if (count==0) return ItemStack.EMPTY;
        var id=Identifier.of(slot.get("item").getAsString()); require(Registries.ITEM.containsId(id),"Unknown fixture item");
        return new ItemStack(Registries.ITEM.get(id),count);
    }
    private static BlockState block(JsonObject value) {
        require(value.getAsJsonObject("properties").isEmpty(),"Fixed fixture block properties unsupported");
        return switch (value.get("id").getAsString()) {
            case "minecraft:air" -> Blocks.AIR.getDefaultState(); case "minecraft:stone" -> Blocks.STONE.getDefaultState();
            case "minecraft:bedrock" -> Blocks.BEDROCK.getDefaultState(); default -> throw new AssertionError("Unsupported fixed fixture block");
        };
    }
    private static PlayerAbilities.Packed abilities(JsonObject p) {
        var a=p.getAsJsonObject("abilities"); return new PlayerAbilities.Packed(b(a,"invulnerable"),b(a,"flying"),b(a,"allowFlying"),
                b(a,"creativeMode"),b(a,"allowModifyWorld"),(float)n(a,"flySpeed"),(float)n(a,"walkSpeed"));
    }
    private static JsonElement sorted(JsonElement e) {
        if (e.isJsonObject()) { var o=new JsonObject(); new TreeMap<>(e.getAsJsonObject().asMap()).forEach((k,v) -> o.add(k,sorted(v))); return o; }
        if (e.isJsonArray()) { var a=new JsonArray(); e.getAsJsonArray().forEach(v -> a.add(sorted(v))); return a; } return e;
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static void rejected(String name,Runnable action) {
        try { action.run(); } catch (RuntimeException | AssertionError expected) {
            System.out.println("ScenarioRunner expected refusal ["+name+"]: "+expected); return;
        } throw new AssertionError("Expected refusal: " + name);
    }
    private static void require(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
