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
import java.time.Instant;
import java.util.function.Consumer;

import static com.mapartrunner.fabric.scenario.ScenarioHost.*;

/** One owned launch's first startup publication; no scenario or world work is performed here. */
final class ScenarioLaunchReceipt {
    private final String launchId;
    private final String directory;
    private boolean published;
    private final Consumer<Path> afterLink;
    private final boolean requireWindowsBackend;

    ScenarioLaunchReceipt(String launchId, String directory) {
        this(launchId, directory, null);
    }

    /** Fault boundary for bounded publisher tests; never selected by environment or a launch option. */
    ScenarioLaunchReceipt(String launchId, String directory, Consumer<Path> afterLink) {
        this(launchId, directory, afterLink, true);
    }

    /** Portable filesystem policy fixture only; all native/environment constructors enforce Windows/NTFS. */
    ScenarioLaunchReceipt(String launchId, String directory, Consumer<Path> afterLink, boolean requireWindowsBackend) {
        this.launchId = launchId;
        this.directory = directory;
        this.afterLink = afterLink;
        this.requireWindowsBackend = requireWindowsBackend;
    }

    synchronized void operation(Operation operation, String phase, Instant started, Instant deadline,
                                Verdict verdict, CleanupStatus cleanup) {
        require(launchId != null && directory != null, "Operation requires an owned launch");
        publicationRequested(); // Validate launch identity; startup's one-shot guard does not suppress phases.
        require(operation.ordinal() > 0 && operation.command() != Command.STARTUP, "Invalid ordinary operation");
        String suffix = switch (phase) {
            case "WORK" -> "begin";
            case "CLEANUP" -> "cleanup";
            case "SETTLED" -> "end";
            default -> throw new IllegalArgumentException("Unknown operation phase");
        };
        require(started != null && (phase.equals("SETTLED") ? deadline == null && verdict != null && cleanup != null
                : deadline != null && cleanup == null && (phase.equals("WORK") ? verdict == null : verdict != null)), "Invalid phase values");
        if (phase.equals("WORK")) require(deadline.equals(started.plusMillis(operation.command() == Command.CLOSE ? 10000 : 600000)), "Invalid WORK deadline");
        if (phase.equals("CLEANUP")) require(!deadline.isBefore(started) && !deadline.isAfter(started.plusMillis(10000)), "Invalid CLEANUP deadline");
        var receipt = identity("mapartrunner-inspect-operation-v1");
        receipt.addProperty("mode", "inspect"); receipt.addProperty("sessionId", operation.sessionId().toString());
        receipt.addProperty("ordinal", operation.ordinal()); receipt.addProperty("operationId", operation.operationId().toString());
        receipt.addProperty("command", operation.command().name()); receipt.addProperty("phase", phase);
        receipt.addProperty("phaseStartedUtc", started.toString()); receipt.addProperty("phaseDeadlineUtc", deadline == null ? null : deadline.toString());
        receipt.addProperty("evidenceDir", operation.evidenceDirectory().toString());
        receipt.addProperty("verdict", verdict == null ? null : verdict.name());
        receipt.addProperty("cleanupStatus", cleanup == null ? null : cleanup.name());
        publish(receipt, "inspect-op-" + operation.ordinal() + "." + suffix + ".json", operation);
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
        publish(receipt, "test-start.json", null);
        // Only a completely returned startup publication sets the first-provider guard.
        published = true;
    }

    private void publish(JsonObject receipt, String filename, Operation operation) {
        try {
            var source = Path.of(ScenarioLaunchReceipt.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            require(Files.isDirectory(source) && source.endsWith(Path.of("build", "classes", "java", "gametest"))
                    && source.equals(source.toRealPath()), "Unsupported/redirected loaded test module");
            var module = source.getParent().getParent().getParent().getParent();
            require(module.endsWith(Path.of("v2", "fabric")) && Files.isRegularFile(module.resolve("build.gradle")), "Wrong test module root");
            var root = module.resolve("build/scenario-results");
            require(root.equals(root.toRealPath()), "Redirected scenario-results root");
            if (operation != null) {
                Path expected = root.resolve("two-lane-" + operation.sessionId()).resolve("inspect-operations")
                        .resolve("op-" + operation.ordinal() + "-" + operation.operationId());
                require(expected.equals(operation.evidenceDirectory()) && expected.equals(expected.toRealPath()), "Wrong/redirected operation evidence");
                for (Path ancestor = expected; !ancestor.equals(root); ancestor = ancestor.getParent())
                    require(!Files.isSymbolicLink(ancestor) && ancestor.equals(ancestor.toRealPath()), "Redirected operation ancestry");
            }
            var launch = Path.of(directory);
            require(launch.isAbsolute() && launch.equals(launch.normalize()) && launch.getParent().equals(root)
                    && launch.getFileName().toString().equals("launch-" + launchId) && Files.isDirectory(launch, LinkOption.NOFOLLOW_LINKS)
                    && launch.equals(launch.toRealPath()), "Wrong/redirected owned launch directory");
            require(!requireWindowsBackend || System.getProperty("os.name").startsWith("Windows") && Files.getFileStore(launch).type().equals("NTFS"),
                    "Owned test receipts require the configured Windows/NTFS backend");
            byte[] bytes = receipt.toString().getBytes(StandardCharsets.UTF_8);
            require(bytes.length <= 4096, "Test-start receipt exceeds4096bytes");
            var temporary = launch.resolve(filename + "-" + UUID.randomUUID() + ".tmp");
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
                Files.createLink(launch.resolve(filename), temporary);
                if (afterLink != null) afterLink.accept(launch.resolve(filename));
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
        } catch (IOException error) { throw new UncheckedIOException("Cannot publish owned startup", error); }
        catch (java.net.URISyntaxException error) { throw new IllegalStateException("Cannot identify loaded test module", error); }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
