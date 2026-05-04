package com.example.mapart.plan.sweep.grounded;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GroundedDiagnostics {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_EVENTS_PER_SNAPSHOT = 50;
    private final Path path;
    private boolean enabled = true;

    public GroundedDiagnostics() {
        this(defaultPath());
    }

    private static Path defaultPath() {
        try {
            return FabricLoader.getInstance().getGameDir().resolve("logs/mapart-diagnostics.log");
        } catch (IllegalStateException ex) {
            return Path.of("run/logs/mapart-diagnostics.log");
        }
    }

    GroundedDiagnostics(Path path) {
        this.path = path;
    }

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Path path() { return path; }

    public synchronized void writeSnapshot(Map<String, Object> snapshot, List<String> eventsSinceLastSnapshot) {
        if (!enabled || snapshot == null) {
            return;
        }
        try {
            ensureHeader();
            Map<String, Object> row = new LinkedHashMap<>(snapshot);
            row.put("type", "snapshot");
            row.putIfAbsent("timestamp", Instant.now().toString());
            boolean truncated = eventsSinceLastSnapshot != null && eventsSinceLastSnapshot.size() > MAX_EVENTS_PER_SNAPSHOT;
            List<String> events = eventsSinceLastSnapshot == null ? List.of()
                    : (truncated ? eventsSinceLastSnapshot.subList(eventsSinceLastSnapshot.size() - MAX_EVENTS_PER_SNAPSHOT, eventsSinceLastSnapshot.size()) : eventsSinceLastSnapshot);
            row.put("events", events);
            row.put("events_truncated", truncated);
            Files.writeString(path, GSON.toJson(row) + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private void ensureHeader() throws IOException {
        Files.createDirectories(path.getParent());
        if (!Files.exists(path) || Files.size(path) == 0) {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("type", "header");
            header.put("format", "mapart-diagnostics-jsonl");
            header.put("version", 1);
            header.put("description", "Each following line is one grounded sweep diagnostic snapshot.");
            Files.writeString(path, GSON.toJson(header) + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }
}
