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
        MapartSettings current = settings;

        try {
            switch (normalized) {
                case "showhud" -> settings = copyWith(current, parseBoolean(value), current.showSchematicOverlay(), current.overlayCurrentRegionOnly(),
                        current.overlayShowOnlyIncorrect(), current.hudCompact(), current.clientTimerSpeed(), current.hudX(), current.hudY(),
                        current.preferLongerAxis(), current.sweepHalfWidth(), current.sweepTotalWidth(), current.laneStride(),
                        current.forwardLookaheadSteps(), current.trivialBehindCleanupSteps(), current.groundedSweepConstantSprint());
                case "showschematicoverlay" -> settings = copyWith(current, current.showHud(), parseBoolean(value), current.overlayCurrentRegionOnly(),
                        current.overlayShowOnlyIncorrect(), current.hudCompact(), current.clientTimerSpeed(), current.hudX(), current.hudY(),
                        current.preferLongerAxis(), current.sweepHalfWidth(), current.sweepTotalWidth(), current.laneStride(),
                        current.forwardLookaheadSteps(), current.trivialBehindCleanupSteps(), current.groundedSweepConstantSprint());
                case "overlaycurrentregiononly" -> settings = copyWith(current, current.showHud(), current.showSchematicOverlay(), parseBoolean(value),
                        current.overlayShowOnlyIncorrect(), current.hudCompact(), current.clientTimerSpeed(), current.hudX(), current.hudY(),
                        current.preferLongerAxis(), current.sweepHalfWidth(), current.sweepTotalWidth(), current.laneStride(),
                        current.forwardLookaheadSteps(), current.trivialBehindCleanupSteps(), current.groundedSweepConstantSprint());
                case "overlayshowonlyincorrect" -> settings = copyWith(current, current.showHud(), current.showSchematicOverlay(), current.overlayCurrentRegionOnly(),
                        parseBoolean(value), current.hudCompact(), current.clientTimerSpeed(), current.hudX(), current.hudY(),
                        current.preferLongerAxis(), current.sweepHalfWidth(), current.sweepTotalWidth(), current.laneStride(),
                        current.forwardLookaheadSteps(), current.trivialBehindCleanupSteps(), current.groundedSweepConstantSprint());
                case "hudcompact" -> settings = copyWith(current, current.showHud(), current.showSchematicOverlay(), current.overlayCurrentRegionOnly(),
                        current.overlayShowOnlyIncorrect(), parseBoolean(value), current.clientTimerSpeed(), current.hudX(), current.hudY(),
                        current.preferLongerAxis(), current.sweepHalfWidth(), current.sweepTotalWidth(), current.laneStride(),
                        current.forwardLookaheadSteps(), current.trivialBehindCleanupSteps(), current.groundedSweepConstantSprint());
                case "clienttimerspeed" -> settings = copyWith(current, current.showHud(), current.showSchematicOverlay(), current.overlayCurrentRegionOnly(),
                        current.overlayShowOnlyIncorrect(), current.hudCompact(), parseClientTimerSpeed(value), current.hudX(), current.hudY(),
                        current.preferLongerAxis(), current.sweepHalfWidth(), current.sweepTotalWidth(), current.laneStride(),
                        current.forwardLookaheadSteps(), current.trivialBehindCleanupSteps(), current.groundedSweepConstantSprint());
                case "hudx" -> settings = copyWith(current, current.showHud(), current.showSchematicOverlay(), current.overlayCurrentRegionOnly(),
                        current.overlayShowOnlyIncorrect(), current.hudCompact(), current.clientTimerSpeed(), parseInt(value), current.hudY(),
                        current.preferLongerAxis(), current.sweepHalfWidth(), current.sweepTotalWidth(), current.laneStride(),
                        current.forwardLookaheadSteps(), current.trivialBehindCleanupSteps(), current.groundedSweepConstantSprint());
                case "hudy" -> settings = copyWith(current, current.showHud(), current.showSchematicOverlay(), current.overlayCurrentRegionOnly(),
                        current.overlayShowOnlyIncorrect(), current.hudCompact(), current.clientTimerSpeed(), current.hudX(), parseInt(value),
                        current.preferLongerAxis(), current.sweepHalfWidth(), current.sweepTotalWidth(), current.laneStride(),
                        current.forwardLookaheadSteps(), current.trivialBehindCleanupSteps(), current.groundedSweepConstantSprint());
                case "preferlongeraxis" -> settings = copyWith(current, current.showHud(), current.showSchematicOverlay(), current.overlayCurrentRegionOnly(),
                        current.overlayShowOnlyIncorrect(), current.hudCompact(), current.clientTimerSpeed(), current.hudX(), current.hudY(),
                        parseBoolean(value), current.sweepHalfWidth(), current.sweepTotalWidth(), current.laneStride(),
                        current.forwardLookaheadSteps(), current.trivialBehindCleanupSteps(), current.groundedSweepConstantSprint());
                case "sweephalfwidth" -> settings = copyWith(current, current.showHud(), current.showSchematicOverlay(), current.overlayCurrentRegionOnly(),
                        current.overlayShowOnlyIncorrect(), current.hudCompact(), current.clientTimerSpeed(), current.hudX(), current.hudY(),
                        current.preferLongerAxis(), parseInt(value), current.sweepTotalWidth(), current.laneStride(),
                        current.forwardLookaheadSteps(), current.trivialBehindCleanupSteps(), current.groundedSweepConstantSprint());
                case "sweeptotalwidth" -> settings = copyWith(current, current.showHud(), current.showSchematicOverlay(), current.overlayCurrentRegionOnly(),
                        current.overlayShowOnlyIncorrect(), current.hudCompact(), current.clientTimerSpeed(), current.hudX(), current.hudY(),
                        current.preferLongerAxis(), current.sweepHalfWidth(), parseInt(value), current.laneStride(),
                        current.forwardLookaheadSteps(), current.trivialBehindCleanupSteps(), current.groundedSweepConstantSprint());
                case "lanestride" -> settings = copyWith(current, current.showHud(), current.showSchematicOverlay(), current.overlayCurrentRegionOnly(),
                        current.overlayShowOnlyIncorrect(), current.hudCompact(), current.clientTimerSpeed(), current.hudX(), current.hudY(),
                        current.preferLongerAxis(), current.sweepHalfWidth(), current.sweepTotalWidth(), parseInt(value),
                        current.forwardLookaheadSteps(), current.trivialBehindCleanupSteps(), current.groundedSweepConstantSprint());
                case "forwardlookaheadsteps" -> settings = copyWith(current, current.showHud(), current.showSchematicOverlay(), current.overlayCurrentRegionOnly(),
                        current.overlayShowOnlyIncorrect(), current.hudCompact(), current.clientTimerSpeed(), current.hudX(), current.hudY(),
                        current.preferLongerAxis(), current.sweepHalfWidth(), current.sweepTotalWidth(), current.laneStride(),
                        parseInt(value), current.trivialBehindCleanupSteps(), current.groundedSweepConstantSprint());
                case "trivialbehindcleanupsteps" -> settings = copyWith(current, current.showHud(), current.showSchematicOverlay(), current.overlayCurrentRegionOnly(),
                        current.overlayShowOnlyIncorrect(), current.hudCompact(), current.clientTimerSpeed(), current.hudX(), current.hudY(),
                        current.preferLongerAxis(), current.sweepHalfWidth(), current.sweepTotalWidth(), current.laneStride(),
                        current.forwardLookaheadSteps(), parseInt(value), current.groundedSweepConstantSprint());
                case "groundedsweepconstantsprint" -> settings = copyWith(current, current.showHud(), current.showSchematicOverlay(), current.overlayCurrentRegionOnly(),
                        current.overlayShowOnlyIncorrect(), current.hudCompact(), current.clientTimerSpeed(), current.hudX(), current.hudY(),
                        current.preferLongerAxis(), current.sweepHalfWidth(), current.sweepTotalWidth(), current.laneStride(),
                        current.forwardLookaheadSteps(), current.trivialBehindCleanupSteps(), parseBoolean(value));
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
            settings = copyWith(defaults,
                    stored.showHud == null ? defaults.showHud() : stored.showHud,
                    stored.showSchematicOverlay == null ? defaults.showSchematicOverlay() : stored.showSchematicOverlay,
                    stored.overlayCurrentRegionOnly == null ? defaults.overlayCurrentRegionOnly() : stored.overlayCurrentRegionOnly,
                    stored.overlayShowOnlyIncorrect == null ? defaults.overlayShowOnlyIncorrect() : stored.overlayShowOnlyIncorrect,
                    stored.hudCompact == null ? defaults.hudCompact() : stored.hudCompact,
                    stored.clientTimerSpeed == null ? defaults.clientTimerSpeed() : parseClientTimerSpeed(Integer.toString(stored.clientTimerSpeed)),
                    stored.hudX == null ? defaults.hudX() : stored.hudX,
                    stored.hudY == null ? defaults.hudY() : stored.hudY,
                    stored.preferLongerAxis == null ? defaults.preferLongerAxis() : stored.preferLongerAxis,
                    stored.sweepHalfWidth == null ? defaults.sweepHalfWidth() : stored.sweepHalfWidth,
                    stored.sweepTotalWidth == null ? defaults.sweepTotalWidth() : stored.sweepTotalWidth,
                    stored.laneStride == null ? defaults.laneStride() : stored.laneStride,
                    stored.forwardLookaheadSteps == null ? defaults.forwardLookaheadSteps() : stored.forwardLookaheadSteps,
                    stored.trivialBehindCleanupSteps == null ? defaults.trivialBehindCleanupSteps() : stored.trivialBehindCleanupSteps,
                    stored.groundedSweepConstantSprint == null
                            ? defaults.groundedSweepConstantSprint()
                            : stored.groundedSweepConstantSprint
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
        Boolean preferLongerAxis;
        Integer sweepHalfWidth;
        Integer sweepTotalWidth;
        Integer laneStride;
        Integer forwardLookaheadSteps;
        Integer trivialBehindCleanupSteps;
        Boolean groundedSweepConstantSprint;

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
            this.preferLongerAxis = settings.preferLongerAxis();
            this.sweepHalfWidth = settings.sweepHalfWidth();
            this.sweepTotalWidth = settings.sweepTotalWidth();
            this.laneStride = settings.laneStride();
            this.forwardLookaheadSteps = settings.forwardLookaheadSteps();
            this.trivialBehindCleanupSteps = settings.trivialBehindCleanupSteps();
            this.groundedSweepConstantSprint = settings.groundedSweepConstantSprint();
        }
    }

    private static MapartSettings copyWith(MapartSettings current,
                                           boolean showHud,
                                           boolean showSchematicOverlay,
                                           boolean overlayCurrentRegionOnly,
                                           boolean overlayShowOnlyIncorrect,
                                           boolean hudCompact,
                                           int clientTimerSpeed,
                                           int hudX,
                                           int hudY,
                                           boolean preferLongerAxis,
                                           int sweepHalfWidth,
                                           int sweepTotalWidth,
                                           int laneStride,
                                           int forwardLookaheadSteps,
                                           int trivialBehindCleanupSteps,
                                           boolean groundedSweepConstantSprint) {
        return new MapartSettings(showHud, showSchematicOverlay, overlayCurrentRegionOnly, overlayShowOnlyIncorrect, hudCompact,
                clientTimerSpeed, hudX, hudY, preferLongerAxis, sweepHalfWidth, sweepTotalWidth, laneStride,
                forwardLookaheadSteps, trivialBehindCleanupSteps, groundedSweepConstantSprint);
    }
}
