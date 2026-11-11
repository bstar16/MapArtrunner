package com.mapartrunner.fabric.scenario;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** One owned launch's first startup publication; no scenario or world work is performed here. */
final class ScenarioLaunchReceipt {
    private final String launchId;
    private final String directory;
    private boolean published;

    ScenarioLaunchReceipt(String launchId, String directory) {
        this.launchId = launchId;
        this.directory = directory;
    }

    static ScenarioLaunchReceipt fromEnvironment() {
        return new ScenarioLaunchReceipt(System.getenv("MAPART_TEST_LAUNCH_ID"), System.getenv("MAPART_TEST_LAUNCH_DIR"));
    }

    synchronized void automatic(String scenario, int repeat, String provider) {
        if (!publicationRequested()) return;
        var receipt = identity("mapartrunner-test-start-v1");
        receipt.addProperty("scenario", scenario);
        receipt.addProperty("repeat", repeat);
        receipt.addProperty("provider", provider);
        receipt.addProperty("event", "SELECTED_PROVIDER_RUNTEST_ENTERED");
        publish(receipt);
    }

    /** Called only after native inspect binding and Commands installation; this is not reset/start success. */
    synchronized void inspect(String sessionId) {
        if (sessionId == null || !UUID.fromString(sessionId).toString().equals(sessionId))
            throw new IllegalArgumentException("Noncanonical inspect session UUID");
        require(launchId != null || directory != null, "Inspect readiness requires an owned launch environment");
        if (!publicationRequested()) return;
        var receipt = identity("mapartrunner-inspect-ready-v1");
        receipt.addProperty("scenario", "grounded-two-lane");
        receipt.addProperty("mode", "inspect");
        receipt.addProperty("sessionId", sessionId);
        receipt.addProperty("event", "INSPECT_WORLD_BOUND_COMMANDS_READY");
        publish(receipt);
    }

    private JsonObject identity(String schema) {
        var receipt = new JsonObject();
        receipt.addProperty("schema", schema);
        receipt.addProperty("launchId", launchId);
        var process = ProcessHandle.current();
        receipt.addProperty("pid", process.pid());
        receipt.addProperty("startedUtc", process.info().startInstant().orElseThrow(
                () -> new IllegalStateException("Native game creation time unavailable")).toString());
        return receipt;
    }

    private boolean publicationRequested() {
        if (launchId == null && directory == null) return false;
        require(launchId != null && directory != null, "Incomplete owned test launch environment");
        require(UUID.fromString(launchId).toString().equals(launchId), "Noncanonical test launch UUID");
        return !published;
    }

    private void publish(JsonObject receipt) {
        try {
            var source = Path.of(ScenarioLaunchReceipt.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            require(Files.isDirectory(source) && source.endsWith(Path.of("build", "classes", "java", "gametest"))
                    && source.equals(source.toRealPath()), "Unsupported/redirected loaded test module");
            var module = source.getParent().getParent().getParent().getParent();
            require(module.endsWith(Path.of("v2", "fabric")) && Files.isRegularFile(module.resolve("build.gradle")), "Wrong test module root");
            var root = module.resolve("build/scenario-results");
            require(root.equals(root.toRealPath()), "Redirected scenario-results root");
            var launch = Path.of(directory);
            require(launch.isAbsolute() && launch.equals(launch.normalize()) && launch.getParent().equals(root)
                    && launch.getFileName().toString().equals("launch-" + launchId) && Files.isDirectory(launch, LinkOption.NOFOLLOW_LINKS)
                    && launch.equals(launch.toRealPath()), "Wrong/redirected owned launch directory");
            require(System.getProperty("os.name").startsWith("Windows") && Files.getFileStore(launch).type().equals("NTFS"),
                    "Owned test receipts require the configured Windows/NTFS backend");
            byte[] bytes = receipt.toString().getBytes(StandardCharsets.UTF_8);
            require(bytes.length <= 4096, "Test-start receipt exceeds4096bytes");
            var temporary = launch.resolve("test-start-" + UUID.randomUUID() + ".tmp");
            // CREATE_NEW owns this temporary only after open succeeds; never remove another publisher's file.
            boolean temporaryOwned = false;
            Throwable publicationFailure = null;
            try {
                try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    temporaryOwned = true;
                    var buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                require(launch.equals(launch.toRealPath()), "Launch directory redirected before publication");
                Files.createLink(launch.resolve("test-start.json"), temporary);
            } catch (IOException | RuntimeException | Error failure) {
                publicationFailure = failure;
                throw failure;
            } finally {
                if (temporaryOwned) try { Files.delete(temporary); }
                catch (IOException cleanup) {
                    if (publicationFailure == null) throw cleanup;
                    publicationFailure.addSuppressed(cleanup);
                }
            }
            // Only a completed first publication sets this guard; no later provider/session replaces it.
            published = true;
        } catch (IOException error) { throw new UncheckedIOException("Cannot publish owned startup", error); }
        catch (java.net.URISyntaxException error) { throw new IllegalStateException("Cannot identify loaded test module", error); }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
