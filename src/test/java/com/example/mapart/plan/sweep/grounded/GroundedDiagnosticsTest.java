package com.example.mapart.plan.sweep.grounded;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GroundedDiagnosticsTest {
    @Test
    void diagnosticsEnabledByDefaultAndToggle() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new com.example.mapart.baritone.NoOpBaritoneFacade());
        assertTrue(runner.diagnosticsEnabled());
        runner.setDiagnosticsEnabled(false);
        assertFalse(runner.diagnosticsEnabled());
    }

    @Test
    void cadenceHelperEvery20Ticks() {
        assertFalse(GroundedSingleLaneDebugRunner.shouldEmitGroundedSnapshotForTick(19));
        assertTrue(GroundedSingleLaneDebugRunner.shouldEmitGroundedSnapshotForTick(20));
    }

    @Test
    void jsonLinesValidAndHeaderAndTruncation() throws Exception {
        Path temp = Files.createTempFile("mapart-diag", ".log");
        Files.deleteIfExists(temp);
        GroundedDiagnostics diagnostics = new GroundedDiagnostics(temp);
        Map<String,Object> snap = new LinkedHashMap<>();
        snap.put("tickNumber", 20);
        diagnostics.writeSnapshot(snap, java.util.stream.IntStream.range(0, 60).mapToObj(i -> "e" + i).toList());
        List<String> lines = Files.readAllLines(temp);
        assertEquals(2, lines.size());
        JsonObject header = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertEquals("header", header.get("type").getAsString());
        JsonObject row = JsonParser.parseString(lines.get(1)).getAsJsonObject();
        assertEquals("snapshot", row.get("type").getAsString());
        assertTrue(row.get("events_truncated").getAsBoolean());
        assertEquals(50, row.getAsJsonArray("events").size());
    }
}
