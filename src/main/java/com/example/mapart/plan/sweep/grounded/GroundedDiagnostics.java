package com.example.mapart.plan.sweep.grounded;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class GroundedDiagnostics {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final JsonObject HEADER = buildHeader();

    private final Path logPath;
    private boolean enabled = true;

    public GroundedDiagnostics() {
        this(defaultPath());
    }

    private static Path defaultPath() {
        try {
            return FabricLoader.getInstance().getGameDir().resolve("logs/mapart-diagnostics.log");
        } catch (RuntimeException ex) {
            return Path.of("logs/mapart-diagnostics.log");
        }
    }

    GroundedDiagnostics(Path logPath) {
        this.logPath = logPath;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Path path() {
        return logPath;
    }

    public void writeSnapshot(JsonObject snapshot) {
        if (!enabled || snapshot == null) {
            return;
        }
        try {
            ensureHeader();
            appendLine(snapshot);
        } catch (IOException ignored) {
        }
    }

    private void ensureHeader() throws IOException {
        if (Files.exists(logPath) && Files.size(logPath) > 0L) {
            return;
        }
        Files.createDirectories(logPath.getParent());
        appendLine(HEADER);
    }

    private void appendLine(JsonObject object) throws IOException {
        Files.writeString(logPath, GSON.toJson(object) + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static JsonObject buildHeader() {
        JsonObject header = new JsonObject();
        header.addProperty("type", "header");
        header.addProperty("format", "mapart-diagnostics-jsonl");
        header.addProperty("version", 1);
        header.addProperty("description", "Each following line is one grounded sweep diagnostic snapshot.");
        return header;
    }
}
