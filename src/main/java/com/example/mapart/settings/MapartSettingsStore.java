package com.example.mapart.settings;

import com.example.mapart.MapArtMod;
import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;

public class MapartSettingsStore {
    private static final Gson GSON = new Gson();

    private final Path storagePath;
    private MapartSettings settings;

    public MapartSettingsStore() {
        this(defaultPath());
    }

    public MapartSettingsStore(Path storagePath) {
        this.storagePath = storagePath;
        this.settings = MapartSettings.defaults();
        loadFromDisk();
    }

    public MapartSettings current() {
        return settings;
    }

    public Optional<String> set(String key, String value) {
        String normalized = key.toLowerCase(Locale.ROOT);

        try {
            switch (normalized) {
                case "showhud" -> settings = new MapartSettings(parseBoolean(value), settings.showSchematicOverlay(), settings.overlayCurrentRegionOnly(),
                        settings.overlayShowOnlyIncorrect(), settings.hudCompact(), settings.clientTimerSpeed(), settings.hudX(), settings.hudY());
                case "showschematicoverlay" -> settings = new MapartSettings(settings.showHud(), parseBoolean(value), settings.overlayCurrentRegionOnly(),
                        settings.overlayShowOnlyIncorrect(), settings.hudCompact(), settings.clientTimerSpeed(), settings.hudX(), settings.hudY());
                case "overlaycurrentregiononly" -> settings = new MapartSettings(settings.showHud(), settings.showSchematicOverlay(), parseBoolean(value),
                        settings.overlayShowOnlyIncorrect(), settings.hudCompact(), settings.clientTimerSpeed(), settings.hudX(), settings.hudY());
                case "overlayshowonlyincorrect" -> settings = new MapartSettings(settings.showHud(), settings.showSchematicOverlay(), settings.overlayCurrentRegionOnly(),
                        parseBoolean(value), settings.hudCompact(), settings.clientTimerSpeed(), settings.hudX(), settings.hudY());
                case "hudcompact" -> settings = new MapartSettings(settings.showHud(), settings.showSchematicOverlay(), settings.overlayCurrentRegionOnly(),
                        settings.overlayShowOnlyIncorrect(), parseBoolean(value), settings.clientTimerSpeed(), settings.hudX(), settings.hudY());
                case "clienttimerspeed" -> settings = new MapartSettings(settings.showHud(), settings.showSchematicOverlay(), settings.overlayCurrentRegionOnly(),
                        settings.overlayShowOnlyIncorrect(), settings.hudCompact(), parseClientTimerSpeed(value), settings.hudX(), settings.hudY());
                case "hudx" -> settings = new MapartSettings(settings.showHud(), settings.showSchematicOverlay(), settings.overlayCurrentRegionOnly(),
                        settings.overlayShowOnlyIncorrect(), settings.hudCompact(), settings.clientTimerSpeed(), parseInt(value), settings.hudY());
                case "hudy" -> settings = new MapartSettings(settings.showHud(), settings.showSchematicOverlay(), settings.overlayCurrentRegionOnly(),
                        settings.overlayShowOnlyIncorrect(), settings.hudCompact(), settings.clientTimerSpeed(), settings.hudX(), parseInt(value));
                default -> {
                    return Optional.of("Unknown settings key: " + key);
                }
            }
        } catch (IllegalArgumentException exception) {
            return Optional.of(exception.getMessage());
        }

        saveToDisk();
        return Optional.empty();
    }

    private static boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        throw new IllegalArgumentException("Expected boolean, got: " + value);
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Expected integer, got: " + value);
        }
    }

    private static int parseClientTimerSpeed(String value) {
        int parsed = parseInt(value);
        if (parsed < 1) {
            throw new IllegalArgumentException("clientTimerSpeed must be >= 1.");
        }
        if (parsed > 20) {
            throw new IllegalArgumentException("clientTimerSpeed must be <= 20.");
        }
        return parsed;
    }

    private void loadFromDisk() {
        if (!Files.exists(storagePath)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(storagePath)) {
            StoredSettings stored = GSON.fromJson(reader, StoredSettings.class);
            if (stored == null) {
                return;
            }

            MapartSettings defaults = MapartSettings.defaults();
            settings = new MapartSettings(
                    stored.showHud == null ? defaults.showHud() : stored.showHud,
                    stored.showSchematicOverlay == null ? defaults.showSchematicOverlay() : stored.showSchematicOverlay,
                    stored.overlayCurrentRegionOnly == null ? defaults.overlayCurrentRegionOnly() : stored.overlayCurrentRegionOnly,
                    stored.overlayShowOnlyIncorrect == null ? defaults.overlayShowOnlyIncorrect() : stored.overlayShowOnlyIncorrect,
                    stored.hudCompact == null ? defaults.hudCompact() : stored.hudCompact,
                    stored.clientTimerSpeed == null ? defaults.clientTimerSpeed() : parseClientTimerSpeed(Integer.toString(stored.clientTimerSpeed)),
                    stored.hudX == null ? defaults.hudX() : stored.hudX,
                    stored.hudY == null ? defaults.hudY() : stored.hudY
            );
        } catch (RuntimeException exception) {
            MapArtMod.LOGGER.warn("Settings file {} is malformed; using defaults.", storagePath, exception);
            settings = MapartSettings.defaults();
        } catch (IOException exception) {
            MapArtMod.LOGGER.warn("Failed to read settings file {}; using defaults.", storagePath, exception);
            settings = MapartSettings.defaults();
        }
    }

    private void saveToDisk() {
        StoredSettings stored = new StoredSettings(settings);

        try {
            Path parent = storagePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tempPath = storagePath.resolveSibling(storagePath.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tempPath)) {
                GSON.toJson(stored, writer);
            }
            Files.move(tempPath, storagePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            MapArtMod.LOGGER.warn("Failed to save settings file {}.", storagePath, exception);
        }
    }

    private static Path defaultPath() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("mapartrunner").resolve("settings.json");
        } catch (Exception ignored) {
            return Path.of("config", "mapartrunner", "settings.json");
        }
    }

    private static final class StoredSettings {
        Boolean showHud;
        Boolean showSchematicOverlay;
        Boolean overlayCurrentRegionOnly;
        Boolean overlayShowOnlyIncorrect;
        Boolean hudCompact;
        Integer clientTimerSpeed;
        Integer hudX;
        Integer hudY;

        StoredSettings() {
        }

        StoredSettings(MapartSettings settings) {
            this.showHud = settings.showHud();
            this.showSchematicOverlay = settings.showSchematicOverlay();
            this.overlayCurrentRegionOnly = settings.overlayCurrentRegionOnly();
            this.overlayShowOnlyIncorrect = settings.overlayShowOnlyIncorrect();
            this.hudCompact = settings.hudCompact();
            this.clientTimerSpeed = settings.clientTimerSpeed();
            this.hudX = settings.hudX();
            this.hudY = settings.hudY();
        }
    }
}
