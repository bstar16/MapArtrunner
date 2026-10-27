package com.mapartrunner.fabric.scenario;

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

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
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

/** Test-thread-owned, one disposable world per invocation; no existing-world reset API. */
final class ScenarioEnvironment {
    private static final int TIMEOUT_TICKS = ClientGameTestContext.DEFAULT_TIMEOUT;
    private static final BlockPos SUPPORT = new BlockPos(0, 64, 0);
    private static final BlockPos TARGET = SUPPORT.up();
    private static final BlockPos CHEST = new BlockPos(2, 65, 0);
    private static final String FIXTURE = "v1;seed=1;flat;floor=-3..3,64,-3..5:bedrock;target=0,65,0:air;"
            + "chest=2,65,0:stone16;player=0.5,65,2.5,180,60;creative;slot0=stone8";
    private final ClientGameTestContext context;
    private final Path artifacts;
    private final Block expected;
    private boolean failureScreenshotAttempted;

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
                    String result = context.computeOnClient(client -> client.interactionManager.interactBlock(
                            client.player, Hand.MAIN_HAND,
                            new BlockHitResult(new Vec3d(0.5, 65, 0.5), Direction.UP, SUPPORT, false)).toString());
                    note("client interactBlock result=" + result);
                    assertServerBlock(world);
                    context.waitFor(client -> client.world != null
                            && client.world.getBlockState(TARGET).isOf(Blocks.STONE), TIMEOUT_TICKS);
                    note("client observed server-confirmed stone");
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
