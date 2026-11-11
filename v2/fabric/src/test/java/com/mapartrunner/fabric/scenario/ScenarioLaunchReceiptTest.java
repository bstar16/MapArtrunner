package com.mapartrunner.fabric.scenario;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioLaunchReceiptTest {
    private final List<Path> launches = new ArrayList<>();

    @AfterEach void removeOnlyFreshFixtureFiles() throws Exception {
        for (var launch : launches) {
            try (var files = Files.list(launch)) {
                for (var file : files.toList()) Files.delete(file);
            }
            Files.delete(launch);
        }
    }

    @Test void automaticPublishesOriginalEightFieldsAndNativeIdentity() throws Exception {
        var launch = launch();
        publisher(launch).automatic("all", 2, "schematic-import");
        var receipt = read(launch);
        assertEquals(Set.of("schema", "launchId", "pid", "startedUtc", "scenario", "repeat", "provider", "event"), receipt.keySet());
        assertEquals("mapartrunner-test-start-v1", receipt.get("schema").getAsString());
        assertEquals("SELECTED_PROVIDER_RUNTEST_ENTERED", receipt.get("event").getAsString());
        assertEquals("all", receipt.get("scenario").getAsString());
        assertEquals(2, receipt.get("repeat").getAsInt());
        assertEquals("schematic-import", receipt.get("provider").getAsString());
        assertIdentity(launch, receipt);
        assertOnlyFinal(launch);
    }

    @Test void firstCompletedAutomaticPublicationKeepsFirstProviderBytes() throws Exception {
        var launch = launch();
        var publisher = publisher(launch);
        publisher.automatic("all", 2, "environment-probe");
        var first = Files.readAllBytes(launch.resolve("test-start.json"));
        publisher.automatic("all", 2, "grounded-traversal");
        assertArrayEquals(first, Files.readAllBytes(launch.resolve("test-start.json")));
        assertOnlyFinal(launch);
    }

    @Test void inspectPublishesOnlyTypedReadyFieldsWithoutAutomaticSelection() throws Exception {
        var launch = launch();
        var session = UUID.randomUUID().toString();
        publisher(launch).inspect(session);
        var receipt = read(launch);
        assertEquals(Set.of("schema", "launchId", "pid", "startedUtc", "scenario", "mode", "sessionId", "event"), receipt.keySet());
        assertEquals("mapartrunner-inspect-ready-v1", receipt.get("schema").getAsString());
        assertEquals("INSPECT_WORLD_BOUND_COMMANDS_READY", receipt.get("event").getAsString());
        assertEquals("grounded-two-lane", receipt.get("scenario").getAsString());
        assertEquals("inspect", receipt.get("mode").getAsString());
        assertEquals(session, receipt.get("sessionId").getAsString());
        assertIdentity(launch, receipt);
        assertOnlyFinal(launch);
    }

    @Test void malformedInspectSessionCannotPublish() throws Exception {
        var launch = launch();
        var publisher = publisher(launch);
        for (var session : List.of("", "wrong", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA", "1-1-1-1-1"))
            assertThrows(IllegalArgumentException.class, () -> publisher.inspect(session));
        assertFalse(Files.exists(launch.resolve("test-start.json")));
        publisher.inspect(UUID.randomUUID().toString());
        assertOnlyFinal(launch);
    }

    @Test void existingFinalIsNeverReplacedAndFailedPublisherCanRetry() throws Exception {
        var launch = launch();
        var publisher = publisher(launch);
        Files.writeString(launch.resolve("test-start.json"), "preexisting");
        var foreignTemp = launch.resolve("test-start-foreign.tmp");
        Files.writeString(foreignTemp, "foreign");
        assertThrows(UncheckedIOException.class, () -> publisher.automatic("all", 2, "single-lane"));
        assertEquals("preexisting", Files.readString(launch.resolve("test-start.json")));
        try (var files = Files.list(launch)) { assertEquals(2, files.count()); }
        assertEquals("foreign", Files.readString(foreignTemp));
        Files.delete(launch.resolve("test-start.json"));
        publisher.automatic("grounded-two-lane", 1, "grounded-two-lane");
        assertEquals(1, read(launch).get("repeat").getAsInt());
    }

    @Test void absentEnvironmentKeepsAutomaticUnownedUseButPartialEnvironmentFails() {
        assertDoesNotThrow(() -> new ScenarioLaunchReceipt(null, null).automatic("all", 2, "single-lane"));
        assertThrows(AssertionError.class, () -> new ScenarioLaunchReceipt(null, null).inspect(UUID.randomUUID().toString()));
        assertThrows(AssertionError.class, () -> new ScenarioLaunchReceipt(UUID.randomUUID().toString(), null).automatic("all", 2, "single-lane"));
        assertThrows(AssertionError.class, () -> new ScenarioLaunchReceipt(null, "directory").automatic("all", 2, "single-lane"));
    }

    @Test void invalidLaunchIdentityOrPathCannotPublish() throws Exception {
        var launch = launch();
        var id = id(launch);
        assertThrows(IllegalArgumentException.class, () -> new ScenarioLaunchReceipt("wrong", launch.toString()).automatic("all", 2, "single-lane"));
        for (var bad : List.of("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA", "1-1-1-1-1"))
            assertThrows(AssertionError.class, () -> new ScenarioLaunchReceipt(bad, launch.toString()).automatic("all", 2, "single-lane"));
        for (var path : List.of(launch.resolve(".").toString(), launch.getFileName().toString(), launch.getParent().toString()))
            assertThrows(AssertionError.class, () -> new ScenarioLaunchReceipt(id, path).automatic("all", 2, "single-lane"));
        assertFalse(Files.exists(launch.resolve("test-start.json")));
    }

    private Path launch() throws Exception {
        var source = Path.of(ScenarioLaunchReceipt.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var root = source.getParent().getParent().getParent().getParent().resolve("build/scenario-results");
        Files.createDirectories(root);
        var launch = Files.createDirectory(root.resolve("launch-" + UUID.randomUUID()));
        launches.add(launch);
        return launch;
    }

    private static String id(Path launch) { return launch.getFileName().toString().substring("launch-".length()); }
    private static ScenarioLaunchReceipt publisher(Path launch) { return new ScenarioLaunchReceipt(id(launch), launch.toString()); }
    private static JsonObject read(Path launch) throws Exception { return JsonParser.parseString(Files.readString(launch.resolve("test-start.json"))).getAsJsonObject(); }
    private static void assertOnlyFinal(Path launch) throws Exception {
        try (var files = Files.list(launch)) { assertEquals(List.of("test-start.json"), files.map(path -> path.getFileName().toString()).toList()); }
    }
    private static void assertIdentity(Path launch, JsonObject receipt) throws Exception {
        assertEquals(id(launch), receipt.get("launchId").getAsString());
        assertEquals(ProcessHandle.current().pid(), receipt.get("pid").getAsLong());
        assertEquals(ProcessHandle.current().info().startInstant().orElseThrow().toString(), receipt.get("startedUtc").getAsString());
        assertTrue(Files.size(launch.resolve("test-start.json")) <= 4096);
    }
}
