package com.example.mapart.plan.sweep.grounded;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GroundedDiagnosticsTest {

    @TempDir
    Path tempDir;

    @Test
    void diagnosticsEnabledByDefaultAndToggleWorks() {
        GroundedDiagnostics diagnostics = new GroundedDiagnostics(tempDir.resolve("logs/mapart-diagnostics.log"));
        assertTrue(diagnostics.enabled());
        diagnostics.setEnabled(false);
        assertFalse(diagnostics.enabled());
        diagnostics.setEnabled(true);
        assertTrue(diagnostics.enabled());
    }

    @Test
    void writesValidJsonLinesWithHeader() throws Exception {
        GroundedDiagnostics diagnostics = new GroundedDiagnostics(tempDir.resolve("logs/mapart-diagnostics.log"));
        JsonObject snap = new JsonObject();
        snap.addProperty("type", "snapshot");
        diagnostics.writeSnapshot(snap);

        List<String> lines = Files.readAllLines(diagnostics.path());
        assertEquals(2, lines.size());
        lines.forEach(line -> assertDoesNotThrow(() -> JsonParser.parseString(line)));
        assertEquals("header", JsonParser.parseString(lines.get(0)).getAsJsonObject().get("type").getAsString());
    }
}
