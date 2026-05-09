package com.example.mapart.plan.sweep.grounded;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GroundedDiagnostics {
    private static final Logger LOGGER = LoggerFactory.getLogger(GroundedDiagnostics.class);
    private static final Gson GSON = new Gson();
    private static final int MAX_EVENTS_PER_SNAPSHOT = 50;

    private boolean enabled = true;
    private final Path overrideLogPath;

    public GroundedDiagnostics() {
        this.overrideLogPath = null;
    }

    GroundedDiagnostics(Path logPath) {
        this.overrideLogPath = logPath;
    }

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Path logPath() {
        if (overrideLogPath != null) return overrideLogPath;
        try {
            return FabricLoader.getInstance().getGameDir().resolve("logs/mapart-diagnostics.log");
        } catch (IllegalStateException ex) {
            return Path.of("run/logs/mapart-diagnostics.log");
        }
    }

    public void writeSnapshot(long tickNumber, Map<String, Object> payload, List<String> eventsSinceLastSnapshot) {
        if (!enabled) {
            return;
        }
        try {
            ensureHeader();
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("type", "snapshot");
            line.put("timestamp", Instant.now().toString());
            line.put("tickNumber", tickNumber);
            line.putAll(payload);
            boolean truncated = false;
            List<String> events = eventsSinceLastSnapshot;
            if (events.size() > MAX_EVENTS_PER_SNAPSHOT) {
                events = events.subList(events.size() - MAX_EVENTS_PER_SNAPSHOT, events.size());
                truncated = true;
            }
            line.put("events", events);
            line.put("events_truncated", truncated);
            appendJsonLine(line);
        } catch (IOException ignored) {
        }
    }

    static boolean shouldEmitSnapshotForTick(long tickCounter) {
        return GroundedSingleLaneDebugRunner.shouldEmitGroundedSnapshotForTick(tickCounter);
    }

    public void writeEvent(Map<String, Object> payload) {
        if (!enabled) return;
        try {
            ensureHeader();
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("timestamp", Instant.now().toString());
            line.putAll(payload);
            appendJsonLine(line);
        } catch (IOException ignored) {
        }
    }

    /**
     * Truncates the diagnostics log and writes a fresh header. Call once on mod/client init.
     * If the file cannot be reset, a warning is logged and diagnostics continue best-effort.
     */
    public void resetForLaunch() {
        try {
            Path logPath = logPath();
            Files.createDirectories(logPath.getParent());
            Files.writeString(logPath, "", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            writeHeader();
        } catch (IOException e) {
            LOGGER.warn("mapart-diagnostics: failed to reset log on launch: {}", e.getMessage());
        }
    }

    private void ensureHeader() throws IOException {
        Path logPath = logPath();
        Files.createDirectories(logPath.getParent());
        if (!Files.exists(logPath) || Files.size(logPath) == 0) {
            writeHeader();
        }
    }

    private void writeHeader() throws IOException {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("type", "header");
        header.put("format", "mapart-diagnostics-jsonl");
        header.put("version", 1);
        header.put("description", "Each following line is one grounded sweep diagnostic snapshot.");
        appendJsonLine(header);
    }

    private void appendJsonLine(Map<String, Object> data) throws IOException {
        Path logPath = logPath();
        Files.writeString(logPath, GSON.toJson(data) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
