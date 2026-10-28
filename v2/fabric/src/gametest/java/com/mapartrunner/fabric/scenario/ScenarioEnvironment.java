package com.mapartrunner.fabric.scenario;

import com.mapartrunner.fabric.mixin.ClientWorldPendingUpdateAccessor;
import com.mapartrunner.fabric.prediction.PendingPredictionView;
import com.mapartrunner.fabric.runtime.WorldEvidenceReader;
import com.mapartrunner.fabric.runtime.WorldEvidenceReader.CapturedEvidence;
import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.core.placement.PlacementLedger;
import com.mapartrunner.core.placement.PlacementObservation;
import com.mapartrunner.core.placement.PlacementObservation.Evidence;
import com.mapartrunner.core.run.RunLifecycle;

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
import java.util.HexFormat;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.stream.IntStream;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.EmptyChunk;

/** Test-thread-owned, one disposable world per invocation; no existing-world reset API. */
final class ScenarioEnvironment {
    private static final int TIMEOUT_TICKS = ClientGameTestContext.DEFAULT_TIMEOUT;
    private static final BlockPos SUPPORT = new BlockPos(0, 64, 0);
    private static final BlockPos TARGET = SUPPORT.up();
    private static final BlockPos CHEST = new BlockPos(2, 65, 0);
    private static final String FIXTURE = "v1;seed=1;flat;floor=-3..3,64,-3..5:bedrock;target=0,65,0:air;"
            + "chest=2,65,0:stone16;player=0.5,65,2.5,180,60;creative;slot0=stone8;"
            + "reader=v1,support41,target7;excursion=2047..2049,64,-1..1:temporary-bedrock;tracking=2,restore";
    private final ClientGameTestContext context;
    private final Path artifacts;
    private final Block expected;
    private boolean failureScreenshotAttempted;
    private long sampleTick;

    ScenarioEnvironment(ClientGameTestContext context) {
        this.context = context;
        Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        if (!gameDir.getParent().getFileName().toString().equals("run")
                || !gameDir.getParent().getParent().getFileName().toString().equals("build")) {
            throw new IllegalStateException("Probe requires isolated build/run/<name>, got " + gameDir);
        }
        artifacts = gameDir.getParent().getParent().resolve("scenario-results")
                .resolve("environment-probe-" + UUID.randomUUID());
        expected = "1".equals(System.getenv("MAPART_PROBE_WRONG_EXPECTED_BLOCK"))
                ? Blocks.GOLD_BLOCK : Blocks.STONE;
        try {
            Files.createDirectories(artifacts);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create probe artifacts at " + artifacts, e);
        }
        System.out.println("MapArtRunner environment probe artifacts: " + artifacts);
        note("START " + Instant.now() + "\ngameDir=" + gameDir
                + "\nfixture=" + FIXTURE + "\nfixtureSha256=" + fixtureHash() + "; target=" + TARGET
                + "; expected=" + Registries.BLOCK.getId(expected)
                + "; timeoutTicks=" + TIMEOUT_TICKS + "; builder=absent; Baritone=absent");
        for (String mod : new String[] {"minecraft", "fabricloader", "fabric-api", "mapart", "mapart-test"}) {
            note(mod + "=" + FabricLoader.getInstance().getModContainer(mod).orElseThrow()
                    .getMetadata().getVersion().getFriendlyString());
        }
    }

    void run() {
        try {
            // All context calls stay on the gametest thread; only game effects enter the marshalled lambdas.
            try (var world = context.worldBuilder().create()) {
                try {
                    note("world=" + world.getWorldSave().getSaveDirectory().toAbsolutePath());
                    setUp(world);
                    context.waitFor(client -> client.world != null && client.player != null
                            && client.interactionManager != null
                            && client.player.squaredDistanceTo(0.5, 65, 2.5) < 0.25
                            && client.world.getBlockState(SUPPORT).isOf(Blocks.BEDROCK)
                            && client.world.getBlockState(TARGET).isAir()
                            && client.world.getBlockState(CHEST).isOf(Blocks.CHEST)
                            && client.player.getMainHandStack().isOf(Items.STONE), TIMEOUT_TICKS);
                    note("client observed fixture and inventory");
                    EvidenceProbe evidence = probeEvidenceAndUnload(world);
                    nextSampleTick();
                    String result = context.computeOnClient(client -> {
                        var clientWorld = client.world;
                        if (clientWorld == null || client.player == null || client.interactionManager == null) {
                            throw new AssertionError("Client fixture disappeared before interaction");
                        }
                        var interaction = client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND,
                                new BlockHitResult(new Vec3d(0.5, 65, 0.5), Direction.UP, SUPPORT, false));
                        var prediction = (PendingPredictionView) ((ClientWorldPendingUpdateAccessor) clientWorld)
                                .mapart$getPendingUpdateManager();
                        var predictedState = clientWorld.getBlockState(TARGET);
                        if (!predictedState.equals(Blocks.STONE.getDefaultState())) {
                            throw new AssertionError("Immediate predicted target was not stone: " + predictedState);
                        }
                        // Stay inside this interaction callback so reconciliation cannot obscure the real prediction.
                        for (int query = 1; query <= 3; query++) {
                            if (!prediction.mapart$hasPendingPrediction(TARGET)
                                    || !clientWorld.getBlockState(TARGET).equals(predictedState)) {
                                throw new AssertionError("Pending prediction/query stability failed at query " + query);
                            }
                            assertShared(WorldEvidenceReader.capture(client, evidence.plan(), evidence.run(),
                                    evidence.world(), evidence.handler(), sampleTick), Evidence.PREDICTED);
                        }
                        return "interaction=" + interaction + "; target=" + TARGET + "; pending=true; state="
                                + predictedState + "; queryCount=3; visibleStateUnchanged=true";
                    });
                    note("client immediate prediction: " + result);
                    assertServerBlock(world);
                    nextSampleTick();
                    context.waitFor(client -> {
                        var clientWorld = client.world;
                        if (clientWorld == null) {
                            return false;
                        }
                        var prediction = (PendingPredictionView) ((ClientWorldPendingUpdateAccessor) clientWorld)
                                .mapart$getPendingUpdateManager();
                        return !prediction.mapart$hasPendingPrediction(TARGET)
                                && clientWorld.getBlockState(TARGET).equals(Blocks.STONE.getDefaultState());
                    }, TIMEOUT_TICKS);
                    String reconciled = context.computeOnClient(client -> {
                        var clientWorld = client.world;
                        if (clientWorld == null) {
                            throw new AssertionError("Client world disappeared after pending wait");
                        }
                        var prediction = (PendingPredictionView) ((ClientWorldPendingUpdateAccessor) clientWorld)
                                .mapart$getPendingUpdateManager();
                        boolean pending = prediction.mapart$hasPendingPrediction(TARGET);
                        var state = clientWorld.getBlockState(TARGET);
                        if (pending || !state.equals(Blocks.STONE.getDefaultState())) {
                            throw new AssertionError("Nonpending matching state was lost: pending=" + pending
                                    + "; state=" + state);
                        }
                        return "target=" + TARGET + "; pending=" + pending + "; state=" + state;
                    });
                    note("client later nonpending sample: " + reconciled);
                    var confirmed = readEvidence(evidence);
                    assertShared(confirmed, Evidence.OBSERVED);
                    routeEvidence(evidence, confirmed);
                    if (evidence.ledger().outcome() != PlacementLedger.Outcome.ALL_CONFIRMED) {
                        throw new AssertionError("Real nonpending reader evidence did not confirm both targets");
                    }
                    note("reader final: actionless OBSERVED stone, shared support, ALL_CONFIRMED; sampleTick=" + sampleTick);
                    world.getClientWorld().waitForChunksRender(TIMEOUT_TICKS);
                    screenshot("success");
                } catch (RuntimeException | Error failure) {
                    captureFailure(failure); // Capture the world before try-with-resources disconnects it.
                    throw failure;
                }
            }
            retainGameLog();
            note("PASS: round trip, independent server assertion, screenshot, world close");
        } catch (RuntimeException | Error failure) {
            if (!failureScreenshotAttempted) {
                captureFailure(failure); // Also covers world creation and world-close failures.
            }
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            try {
                note("FAIL " + Instant.now() + "\n" + trace);
                retainGameLog();
            } catch (RuntimeException evidenceFailure) {
                failure.addSuppressed(evidenceFailure);
            }
            throw failure; // Fabric must observe the failure and exit unsuccessfully.
        }
    }

    private record EvidenceProbe(ClientWorld world, ClientPlayNetworkHandler handler, RunLifecycle.Context run,
                                 BuildPlan plan, PlacementLedger ledger) { }

    private record ExcursionState(int viewDistance, Vec3d position, float yaw, float pitch, GameMode mode,
                                  List<ItemStack> inventory, int selectedSlot, Map<BlockPos, BlockState> remote) { }

    private EvidenceProbe probeEvidenceAndUnload(TestSingleplayerContext world) {
        var plan = new BuildPlan("fixture-reader-v1", new GridSize(1, 2, 1), List.of(
                new Target(41, new GridPos(0, 0, 0), new BlockSpec("minecraft:bedrock", Map.of())),
                new Target(7, new GridPos(0, 1, 0), new BlockSpec("minecraft:stone", Map.of()))));
        UUID runId = UUID.randomUUID();
        var ledger = new PlacementLedger(plan);
        ledger.bindRun(runId);
        EvidenceProbe probe = context.computeOnClient(client -> {
            var run = new RunLifecycle.Context(runId, "probe-session-" + runId, "probe-world-" + runId,
                    client.world.getRegistryKey().getValue().toString(), plan.fingerprint(),
                    new GridPos(0, 64, 0), "fixture-settings-v1");
            return new EvidenceProbe(client.world, client.getNetworkHandler(), run, plan, ledger);
        });
        var initial = readEvidence(probe);
        assertShared(initial, Evidence.OBSERVED);
        routeEvidence(probe, initial);
        assertExistingSupportOnly(probe);
        note("reader existing correct: actionless bedrock confirmed, target OBSERVED air; tick=" + sampleTick);
        context.runOnClient(client -> {
            var missing = WorldEvidenceReader.capture(client, plan, probe.run(), null, probe.handler(), sampleTick);
            assertUnknown(missing);
            var run = probe.run();
            var changed = new RunLifecycle.Context(run.runId(), run.connectionId(), run.worldId(), "minecraft:the_nether",
                    run.planFingerprint(), run.origin(), run.settingsFingerprint());
            assertUnknown(WorldEvidenceReader.capture(client, plan, changed, probe.world(), probe.handler(), sampleTick));
        });
        note("reader binding: missing world and mismatched dimension yield shared UNKNOWN");
        ExcursionState saved = world.getServer().computeOnServer(server -> {
            var player = server.getPlayerManager().getPlayerList().getFirst();
            var remote = new LinkedHashMap<BlockPos, BlockState>();
            for (int x = 2047; x <= 2049; x++) for (int z = -1; z <= 1; z++) {
                var pos = new BlockPos(x, 64, z);
                remote.put(pos, server.getOverworld().getBlockState(pos));
            }
            return new ExcursionState(server.getPlayerManager().getViewDistance(),
                    new Vec3d(player.getX(), player.getY(), player.getZ()), player.getYaw(), player.getPitch(),
                    player.interactionManager.getGameMode(), IntStream.range(0, player.getInventory().size())
                    .mapToObj(slot -> player.getInventory().getStack(slot).copy()).toList(),
                    player.getInventory().getSelectedSlot(), Map.copyOf(remote));
        });
        Throwable primary = null;
        try {
            world.getServer().runOnServer(server -> {
                var level = server.getOverworld();
                saved.remote().keySet().forEach(pos -> level.setBlockState(pos, Blocks.BEDROCK.getDefaultState()));
                server.getPlayerManager().setViewDistance(2);
                if (!server.getPlayerManager().getPlayerList().getFirst().teleport(level,
                        2048.5, 65, 0.5, java.util.Set.of(), 180, 60, false)) {
                    throw new AssertionError("Excursion teleport rejected");
                }
            });
            boolean unloaded = false;
            for (int waited = 0; waited <= TIMEOUT_TICKS; waited++) {
                unloaded = context.computeOnClient(client -> client.world == probe.world() && client.player != null
                        && client.player.squaredDistanceTo(2048.5, 65, 0.5) < 0.25
                        && client.world.getChunkManager().getChunk(0, 0, ChunkStatus.FULL, false) == null);
                if (unloaded) break;
                if (waited < TIMEOUT_TICKS) nextSampleTick();
            }
            if (!unloaded) throw new AssertionError("Original client chunk did not unload within " + TIMEOUT_TICKS);
            var unavailable = context.computeOnClient(client -> {
                var chunks = client.world.getChunkManager();
                if (chunks.getChunk(0, 0, ChunkStatus.FULL, false) != null
                        || !(chunks.getChunk(0, 0, ChunkStatus.FULL, true) instanceof EmptyChunk)) {
                    throw new AssertionError("Real unavailable chunk/default-empty distinction lost");
                }
                return WorldEvidenceReader.capture(client, plan, probe.run(), probe.world(), probe.handler(), sampleTick);
            });
            assertUnknown(unavailable);
            routeEvidence(probe, unavailable);
            if (ledger.unfinishedTargets().size() != 2 || !ledger.pendingTargetIds().isEmpty()) {
                throw new AssertionError("Unloaded UNKNOWN failed to reopen existing support without pending invention");
            }
            note("reader unloaded: real FULL,false=null, FULL,true=EmptyChunk; both UNKNOWN; bedrock reopened; tick=" + sampleTick);
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                world.getServer().runOnServer(server -> {
                    var player = server.getPlayerManager().getPlayerList().getFirst();
                    saved.remote().forEach((pos, state) -> server.getOverworld().setBlockState(pos, state));
                    server.getPlayerManager().setViewDistance(saved.viewDistance());
                    player.changeGameMode(saved.mode());
                    player.getInventory().clear();
                    for (int slot = 0; slot < saved.inventory().size(); slot++) {
                        player.getInventory().setStack(slot, saved.inventory().get(slot).copy());
                    }
                    player.getInventory().setSelectedSlot(saved.selectedSlot());
                    player.getInventory().markDirty();
                    player.currentScreenHandler.sendContentUpdates();
                    if (!player.teleport(server.getOverworld(), saved.position().x, saved.position().y, saved.position().z,
                            java.util.Set.of(), saved.yaw(), saved.pitch(), false)) throw new AssertionError("Return teleport rejected");
                });
                boolean restored = false;
                for (int waited = 0; waited <= TIMEOUT_TICKS; waited++) {
                    restored = context.computeOnClient(client -> client.world == probe.world() && client.player != null
                            && client.player.squaredDistanceTo(saved.position()) < 0.25
                            && Math.abs(client.player.getYaw() - saved.yaw()) < 0.01
                            && Math.abs(client.player.getPitch() - saved.pitch()) < 0.01
                            && client.world.getChunkManager().getChunk(0, 0, ChunkStatus.FULL, false) != null
                            && client.world.getBlockState(SUPPORT).isOf(Blocks.BEDROCK)
                            && client.world.getBlockState(TARGET).isAir()
                            && client.player.getInventory().getSelectedSlot() == saved.selectedSlot()
                            && client.player.getInventory().getStack(0).isOf(Items.STONE)
                            && client.player.getInventory().getStack(0).getCount() == 8);
                    if (restored) break;
                    if (waited < TIMEOUT_TICKS) nextSampleTick();
                }
                if (!restored) throw new AssertionError("Client fixture did not reload/restore within " + TIMEOUT_TICKS);
                validateRestored(world, saved);
                nextSampleTick();
                var restoredEvidence = readEvidence(probe);
                assertShared(restoredEvidence, Evidence.OBSERVED);
                routeEvidence(probe, restoredEvidence);
                assertExistingSupportOnly(probe);
                note("reader restored before interaction: chunk reloaded, actionless bedrock reconfirmed; target air; "
                        + "floor/chest/player/inventory/remote blocks/tracking validated; tracking=" + saved.viewDistance()
                        + "; origin=" + probe.run().origin() + "; plan=" + plan.fingerprint()
                        + "; settings=" + probe.run().settingsFingerprint() + "; tick=" + sampleTick);
            } catch (RuntimeException | Error restoration) {
                if (primary == null) throw restoration;
                primary.addSuppressed(restoration);
            }
        }
        return probe;
    }

    private void validateRestored(TestSingleplayerContext world, ExcursionState saved) {
        world.getServer().runOnServer(server -> {
            var level = server.getOverworld();
            var player = server.getPlayerManager().getPlayerList().getFirst();
            if (server.getPlayerManager().getViewDistance() != saved.viewDistance()
                    || player.squaredDistanceTo(saved.position()) > 0.01 || player.getYaw() != saved.yaw()
                    || player.getPitch() != saved.pitch() || player.interactionManager.getGameMode() != saved.mode()
                    || player.getInventory().getSelectedSlot() != saved.selectedSlot() || !level.getBlockState(TARGET).isAir()
                    || !(level.getBlockEntity(CHEST) instanceof ChestBlockEntity chest)
                    || !chest.getStack(0).isOf(Items.STONE) || chest.getStack(0).getCount() != 16) {
                throw new AssertionError("Server fixture restoration validation failed");
            }
            for (int slot = 0; slot < saved.inventory().size(); slot++) {
                if (!ItemStack.areEqual(saved.inventory().get(slot), player.getInventory().getStack(slot))) {
                    throw new AssertionError("Inventory restoration mismatch at slot " + slot);
                }
            }
            for (int x = -3; x <= 3; x++) for (int z = -3; z <= 5; z++) {
                if (!level.getBlockState(new BlockPos(x, 64, z)).isOf(Blocks.BEDROCK)) throw new AssertionError("Floor changed");
            }
            saved.remote().forEach((pos, state) -> {
                if (!level.getBlockState(pos).equals(state)) throw new AssertionError("Remote block restoration mismatch: " + pos);
            });
        });
    }

    private CapturedEvidence readEvidence(EvidenceProbe probe) {
        long tick = sampleTick;
        return context.computeOnClient(client -> WorldEvidenceReader.capture(client, probe.plan(), probe.run(),
                probe.world(), probe.handler(), tick));
    }

    private static void assertShared(CapturedEvidence captured, Evidence targetEvidence) {
        var observations = WorldEvidenceReader.placementObservations(captured);
        if (observations.size() != 2) throw new AssertionError("Reader omitted expected target");
        for (var observation : observations) {
            var position = new GridPos(0, observation.targetId() == 41 ? 0 : 1, 0);
            var support = captured.supportObservation().cells().get(position);
            if (support == null || support.evidence() != observation.evidence()
                    || !support.state().equals(observation.observedState()) || observation.actionId().isPresent()
                    || observation.evidence() != (observation.targetId() == 41 ? Evidence.OBSERVED : targetEvidence)) {
                throw new AssertionError("Support/placement classification disagreed: " + observation);
            }
        }
    }

    private static void assertUnknown(CapturedEvidence captured) {
        var observations = WorldEvidenceReader.placementObservations(captured);
        if (observations.size() != 2 || captured.supportObservation().cells().size() != 2
                || observations.stream().anyMatch(o -> o.evidence() != Evidence.UNKNOWN || o.observedState().isPresent())
                || captured.supportObservation().cells().values().stream().anyMatch(s -> s.evidence() != Evidence.UNKNOWN || s.state().isPresent())) {
            throw new AssertionError("Missing binding/chunk was not entirely UNKNOWN");
        }
    }

    private static void routeEvidence(EvidenceProbe probe, CapturedEvidence captured) {
        for (var observation : WorldEvidenceReader.placementObservations(captured)) {
            if (!probe.ledger().observe(observation)) throw new AssertionError("Reader supplied stale/misbound sample: " + observation);
            if (probe.ledger().latestAttempt(observation.targetId()).isPresent()) throw new AssertionError("Read invented an attempt");
        }
    }

    private static void assertExistingSupportOnly(EvidenceProbe probe) {
        if (!probe.ledger().unfinishedTargets().stream().map(Target::id).toList().equals(List.of(7))
                || !probe.ledger().pendingTargetIds().isEmpty()) throw new AssertionError("Existing correct/air accounting failed");
    }

    private void nextSampleTick() {
        context.waitTick();
        sampleTick++;
    }

    private void setUp(TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            var level = server.getOverworld();
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 5; z++) {
                    level.setBlockState(new BlockPos(x, 64, z), Blocks.BEDROCK.getDefaultState());
                }
            }
            level.setBlockState(TARGET, Blocks.AIR.getDefaultState());
            level.setBlockState(CHEST, Blocks.CHEST.getDefaultState());
            if (!(level.getBlockEntity(CHEST) instanceof ChestBlockEntity chest)) {
                throw new AssertionError("Chest fixture missing at " + CHEST);
            }
            chest.setStack(0, new ItemStack(Items.STONE, 16));
            chest.markDirty();
            var player = server.getPlayerManager().getPlayerList().getFirst();
            player.changeGameMode(GameMode.CREATIVE);
            player.getInventory().clear();
            player.getInventory().setStack(0, new ItemStack(Items.STONE, 8));
            player.getInventory().setSelectedSlot(0);
            player.getInventory().markDirty();
            player.currentScreenHandler.sendContentUpdates();
            player.teleport(level, 0.5, 65, 2.5, java.util.Set.of(), 180, 60, false);
            if (chest.getStack(0).getCount() != 16 || player.getInventory().getStack(0).getCount() != 8
                    || !level.getBlockState(TARGET).isAir()) {
                throw new AssertionError("Server fixture inventory/chest/air validation failed");
            }
        });
        note("server fixture ready: bedrock support, air target, creative slot0=8 stone, chest slot0=16 stone");
    }

    private void assertServerBlock(TestSingleplayerContext world) {
        String actual = "not observed";
        for (int tick = 0; tick <= TIMEOUT_TICKS; tick++) {
            // Never call a server context inside a client waitFor predicate (that would violate thread ownership).
            var state = world.getServer().computeOnServer(server -> server.getOverworld().getBlockState(TARGET));
            actual = Registries.BLOCK.getId(state.getBlock()).toString();
            if (state.isOf(expected)) {
                note("authoritative server block=" + actual + "; waitedTicks=" + tick);
                return;
            }
            if (tick < TIMEOUT_TICKS) {
                context.waitTick();
            }
        }
        throw new AssertionError("Server block timeout after " + TIMEOUT_TICKS + " ticks at " + TARGET
                + ": expected=" + Registries.BLOCK.getId(expected) + ", actual=" + actual);
    }

    private void captureFailure(Throwable failure) {
        failureScreenshotAttempted = true;
        try {
            screenshot("failure");
        } catch (RuntimeException | Error screenshotFailure) {
            failure.addSuppressed(screenshotFailure);
        }
    }

    private void screenshot(String name) {
        Path screenshot = context.takeScreenshot(TestScreenshotOptions.of(name)
                .withDestinationDir(artifacts).disableCounterPrefix());
        if (!Files.isRegularFile(screenshot)) {
            throw new AssertionError("Screenshot was not written: " + screenshot);
        }
        note("screenshot=" + screenshot.toAbsolutePath());
    }

    private void note(String message) {
        try {
            Files.writeString(artifacts.resolve("probe.log"), message + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write probe evidence at " + artifacts, e);
        }
    }

    private void retainGameLog() {
        Path log = FabricLoader.getInstance().getGameDir().resolve("logs/latest.log");
        try {
            Files.copy(log, artifacts.resolve("minecraft-latest.log"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot retain Minecraft log " + log, e);
        }
    }

    private static String fixtureHash() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(FIXTURE.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
