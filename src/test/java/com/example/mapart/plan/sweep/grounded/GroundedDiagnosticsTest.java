package com.example.mapart.plan.sweep.grounded;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GroundedDiagnosticsTest {
    @TempDir Path temp;

    @Test
    void enabledByDefaultAndToggleWorks() {
        GroundedDiagnostics d = new GroundedDiagnostics(temp.resolve("a.log"));
        assertTrue(d.enabled());
        d.setEnabled(false);
        assertFalse(d.enabled());
    }

    @Test
    void cadenceIsEvery20Ticks() {
        assertFalse(GroundedDiagnostics.shouldEmitSnapshotForTick(19));
        assertTrue(GroundedDiagnostics.shouldEmitSnapshotForTick(20));
        assertFalse(GroundedDiagnostics.shouldEmitSnapshotForTick(21));
    }

    @Test
    void writesValidJsonLinesAndTruncatesEvents() throws Exception {
        GroundedDiagnostics d = new GroundedDiagnostics(temp.resolve("d.log"));
        LinkedHashMap<String,Object> payload = new LinkedHashMap<>();
        payload.put("x", 1);
        d.writeSnapshot(20, payload, List.of("e1","e2"));
        d.writeSnapshot(40, payload, java.util.stream.IntStream.range(0,60).mapToObj(i->"e"+i).toList());
        List<String> lines = Files.readAllLines(temp.resolve("d.log"));
        assertTrue(lines.size() >= 3);
        lines.forEach(l -> assertDoesNotThrow(() -> JsonParser.parseString(l)));
        var second = JsonParser.parseString(lines.get(2)).getAsJsonObject();
        assertTrue(second.get("events_truncated").getAsBoolean());
        assertEquals(50, second.getAsJsonArray("events").size());
    }

    @Test
    void nullishFieldsDoNotCrash() {
        GroundedDiagnostics d = new GroundedDiagnostics(temp.resolve("n.log"));
        assertDoesNotThrow(() -> d.writeSnapshot(20, new LinkedHashMap<>(), List.of()));
    }

    @Test
    void resetForLaunchTruncatesExistingFile() throws Exception {
        Path log = temp.resolve("reset.log");
        Files.writeString(log, "old content that should be gone\n");
        GroundedDiagnostics d = new GroundedDiagnostics(log);
        d.resetForLaunch();
        String content = Files.readString(log);
        assertFalse(content.contains("old content"), "existing content must be erased");
    }

    @Test
    void resetForLaunchWritesExactlyOneHeader() throws Exception {
        Path log = temp.resolve("header.log");
        GroundedDiagnostics d = new GroundedDiagnostics(log);
        d.resetForLaunch();
        List<String> lines = Files.readAllLines(log);
        assertEquals(1, lines.size(), "exactly one header line after reset");
        var obj = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertEquals("header", obj.get("type").getAsString());
        assertEquals("mapart-diagnostics-jsonl", obj.get("format").getAsString());
    }

    @Test
    void afterResetWriteSnapshotAppendsAfterHeader() throws Exception {
        Path log = temp.resolve("snap.log");
        GroundedDiagnostics d = new GroundedDiagnostics(log);
        d.resetForLaunch();
        d.writeSnapshot(20, new LinkedHashMap<>(), List.of("e1"));
        List<String> lines = Files.readAllLines(log);
        assertEquals(2, lines.size());
        assertEquals("header", JsonParser.parseString(lines.get(0)).getAsJsonObject().get("type").getAsString());
        assertEquals("snapshot", JsonParser.parseString(lines.get(1)).getAsJsonObject().get("type").getAsString());
    }

    @Test
    void afterResetWriteEventAppendsAfterHeader() throws Exception {
        Path log = temp.resolve("event.log");
        GroundedDiagnostics d = new GroundedDiagnostics(log);
        d.resetForLaunch();
        d.writeEvent(new LinkedHashMap<>(java.util.Map.of("type", "run_summary")));
        List<String> lines = Files.readAllLines(log);
        assertEquals(2, lines.size());
        assertEquals("header", JsonParser.parseString(lines.get(0)).getAsJsonObject().get("type").getAsString());
        assertEquals("run_summary", JsonParser.parseString(lines.get(1)).getAsJsonObject().get("type").getAsString());
    }

    @Test
    void normalWriteMethodsDoNotTruncateFile() throws Exception {
        Path log = temp.resolve("notrun.log");
        GroundedDiagnostics d = new GroundedDiagnostics(log);
        d.writeSnapshot(20, new LinkedHashMap<>(), List.of("a"));
        d.writeSnapshot(40, new LinkedHashMap<>(), List.of("b"));
        List<String> lines = Files.readAllLines(log);
        // header + 2 snapshots — nothing should be lost
        assertEquals(3, lines.size());
        assertEquals("header", JsonParser.parseString(lines.get(0)).getAsJsonObject().get("type").getAsString());
        assertEquals("snapshot", JsonParser.parseString(lines.get(1)).getAsJsonObject().get("type").getAsString());
        assertEquals("snapshot", JsonParser.parseString(lines.get(2)).getAsJsonObject().get("type").getAsString());
    }
}
