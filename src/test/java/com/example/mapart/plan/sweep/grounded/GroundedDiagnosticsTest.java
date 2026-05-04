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
}
