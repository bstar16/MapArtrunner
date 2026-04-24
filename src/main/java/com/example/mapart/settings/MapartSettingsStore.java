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
                case "showhud" -> settings = with(showHud(parseBoolean(value)));
                case "showschematicoverlay" -> settings = with(showSchematicOverlay(parseBoolean(value)));
                case "overlaycurrentregiononly" -> settings = with(overlayCurrentRegionOnly(parseBoolean(value)));
                case "overlayshowonlyincorrect" -> settings = with(overlayShowOnlyIncorrect(parseBoolean(value)));
                case "hudcompact" -> settings = with(hudCompact(parseBoolean(value)));
                case "clienttimerspeed" -> settings = with(clientTimerSpeed(parseClientTimerSpeed(value)));
                case "hudx" -> settings = with(hudX(parseInt(value)));
                case "hudy" -> settings = with(hudY(parseInt(value)));
                case "preferlongeraxis" -> settings = with(preferLongerAxis(parseBoolean(value)));
                case "sweephalfwidth" -> settings = with(sweepHalfWidth(parseNonNegativeInt(value, "sweepHalfWidth")));
                case "sweeptotalwidth" -> settings = with(sweepTotalWidth(parsePositiveInt(value, "sweepTotalWidth")));
                case "lanestride" -> settings = with(laneStride(parsePositiveInt(value, "laneStride")));
                case "forwardlookaheadsteps" -> settings = with(forwardLookaheadSteps(parseNonNegativeInt(value, "forwardLookaheadSteps")));
                case "trivialbehindcleanupsteps" -> settings = with(trivialBehindCleanupSteps(parseNonNegativeInt(value, "trivialBehindCleanupSteps")));
                case "groundedsweepconstantsprint" -> settings = with(groundedSweepConstantSprint(parseBoolean(value)));
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

    private MapartSettings with(SettingsMutator mutator) {
        MapartSettings current = settings;
        MapartSettings updated = new MapartSettings(
                mutator.showHud != null ? mutator.showHud : current.showHud(),
                mutator.showSchematicOverlay != null ? mutator.showSchematicOverlay : current.showSchematicOverlay(),
                mutator.overlayCurrentRegionOnly != null ? mutator.overlayCurrentRegionOnly : current.overlayCurrentRegionOnly(),
                mutator.overlayShowOnlyIncorrect != null ? mutator.overlayShowOnlyIncorrect : current.overlayShowOnlyIncorrect(),
                mutator.hudCompact != null ? mutator.hudCompact : current.hudCompact(),
                mutator.clientTimerSpeed != null ? mutator.clientTimerSpeed : current.clientTimerSpeed(),
                mutator.hudX != null ? mutator.hudX : current.hudX(),
                mutator.hudY != null ? mutator.hudY : current.hudY(),
                mutator.preferLongerAxis != null ? mutator.preferLongerAxis : current.preferLongerAxis(),
                mutator.sweepHalfWidth != null ? mutator.sweepHalfWidth : current.sweepHalfWidth(),
                mutator.sweepTotalWidth != null ? mutator.sweepTotalWidth : current.sweepTotalWidth(),
                mutator.laneStride != null ? mutator.laneStride : current.laneStride(),
                mutator.forwardLookaheadSteps != null ? mutator.forwardLookaheadSteps : current.forwardLookaheadSteps(),
                mutator.trivialBehindCleanupSteps != null ? mutator.trivialBehindCleanupSteps : current.trivialBehindCleanupSteps(),
                mutator.groundedSweepConstantSprint != null ? mutator.groundedSweepConstantSprint : current.groundedSweepConstantSprint()
        );

        validateGroundedSweepWidths(updated.sweepHalfWidth(), updated.sweepTotalWidth());
        return updated;
    }

    private static SettingsMutator showHud(boolean value) { return new SettingsMutator().showHud(value); }
    private static SettingsMutator showSchematicOverlay(boolean value) { return new SettingsMutator().showSchematicOverlay(value); }
    private static SettingsMutator overlayCurrentRegionOnly(boolean value) { return new SettingsMutator().overlayCurrentRegionOnly(value); }
    private static SettingsMutator overlayShowOnlyIncorrect(boolean value) { return new SettingsMutator().overlayShowOnlyIncorrect(value); }
    private static SettingsMutator hudCompact(boolean value) { return new SettingsMutator().hudCompact(value); }
    private static SettingsMutator clientTimerSpeed(int value) { return new SettingsMutator().clientTimerSpeed(value); }
    private static SettingsMutator hudX(int value) { return new SettingsMutator().hudX(value); }
    private static SettingsMutator hudY(int value) { return new SettingsMutator().hudY(value); }
    private static SettingsMutator preferLongerAxis(boolean value) { return new SettingsMutator().preferLongerAxis(value); }
    private static SettingsMutator sweepHalfWidth(int value) { return new SettingsMutator().sweepHalfWidth(value); }
    private static SettingsMutator sweepTotalWidth(int value) { return new SettingsMutator().sweepTotalWidth(value); }
    private static SettingsMutator laneStride(int value) { return new SettingsMutator().laneStride(value); }
    private static SettingsMutator forwardLookaheadSteps(int value) { return new SettingsMutator().forwardLookaheadSteps(value); }
    private static SettingsMutator trivialBehindCleanupSteps(int value) { return new SettingsMutator().trivialBehindCleanupSteps(value); }
    private static SettingsMutator groundedSweepConstantSprint(boolean value) { return new SettingsMutator().groundedSweepConstantSprint(value); }

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

    private static int parseNonNegativeInt(String value, String key) {
        int parsed = parseInt(value);
        if (parsed < 0) {
            throw new IllegalArgumentException(key + " must be >= 0.");
        }
        return parsed;
    }

    private static int parsePositiveInt(String value, String key) {
        int parsed = parseInt(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException(key + " must be > 0.");
        }
        return parsed;
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

    private static void validateGroundedSweepWidths(int halfWidth, int totalWidth) {
        int expectedTotal = (halfWidth * 2) + 1;
        if (totalWidth != expectedTotal) {
            throw new IllegalArgumentException("sweepTotalWidth must equal (sweepHalfWidth * 2) + 1.");
        }
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
            int sweepHalfWidth = stored.sweepHalfWidth == null ? defaults.sweepHalfWidth() : parseNonNegativeInt(Integer.toString(stored.sweepHalfWidth), "sweepHalfWidth");
            int sweepTotalWidth = stored.sweepTotalWidth == null ? defaults.sweepTotalWidth() : parsePositiveInt(Integer.toString(stored.sweepTotalWidth), "sweepTotalWidth");
            validateGroundedSweepWidths(sweepHalfWidth, sweepTotalWidth);

            settings = new MapartSettings(
                    stored.showHud == null ? defaults.showHud() : stored.showHud,
                    stored.showSchematicOverlay == null ? defaults.showSchematicOverlay() : stored.showSchematicOverlay,
                    stored.overlayCurrentRegionOnly == null ? defaults.overlayCurrentRegionOnly() : stored.overlayCurrentRegionOnly,
                    stored.overlayShowOnlyIncorrect == null ? defaults.overlayShowOnlyIncorrect() : stored.overlayShowOnlyIncorrect,
                    stored.hudCompact == null ? defaults.hudCompact() : stored.hudCompact,
                    stored.clientTimerSpeed == null ? defaults.clientTimerSpeed() : parseClientTimerSpeed(Integer.toString(stored.clientTimerSpeed)),
                    stored.hudX == null ? defaults.hudX() : stored.hudX,
                    stored.hudY == null ? defaults.hudY() : stored.hudY,
                    stored.preferLongerAxis == null ? defaults.preferLongerAxis() : stored.preferLongerAxis,
                    sweepHalfWidth,
                    sweepTotalWidth,
                    stored.laneStride == null ? defaults.laneStride() : parsePositiveInt(Integer.toString(stored.laneStride), "laneStride"),
                    stored.forwardLookaheadSteps == null ? defaults.forwardLookaheadSteps() : parseNonNegativeInt(Integer.toString(stored.forwardLookaheadSteps), "forwardLookaheadSteps"),
                    stored.trivialBehindCleanupSteps == null ? defaults.trivialBehindCleanupSteps() : parseNonNegativeInt(Integer.toString(stored.trivialBehindCleanupSteps), "trivialBehindCleanupSteps"),
                    stored.groundedSweepConstantSprint == null ? defaults.groundedSweepConstantSprint() : stored.groundedSweepConstantSprint
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

    private static final class SettingsMutator {
        private Boolean showHud;
        private Boolean showSchematicOverlay;
        private Boolean overlayCurrentRegionOnly;
        private Boolean overlayShowOnlyIncorrect;
        private Boolean hudCompact;
        private Integer clientTimerSpeed;
        private Integer hudX;
        private Integer hudY;
        private Boolean preferLongerAxis;
        private Integer sweepHalfWidth;
        private Integer sweepTotalWidth;
        private Integer laneStride;
        private Integer forwardLookaheadSteps;
        private Integer trivialBehindCleanupSteps;
        private Boolean groundedSweepConstantSprint;

        SettingsMutator showHud(boolean value) { this.showHud = value; return this; }
        SettingsMutator showSchematicOverlay(boolean value) { this.showSchematicOverlay = value; return this; }
        SettingsMutator overlayCurrentRegionOnly(boolean value) { this.overlayCurrentRegionOnly = value; return this; }
        SettingsMutator overlayShowOnlyIncorrect(boolean value) { this.overlayShowOnlyIncorrect = value; return this; }
        SettingsMutator hudCompact(boolean value) { this.hudCompact = value; return this; }
        SettingsMutator clientTimerSpeed(int value) { this.clientTimerSpeed = value; return this; }
        SettingsMutator hudX(int value) { this.hudX = value; return this; }
        SettingsMutator hudY(int value) { this.hudY = value; return this; }
        SettingsMutator preferLongerAxis(boolean value) { this.preferLongerAxis = value; return this; }
        SettingsMutator sweepHalfWidth(int value) { this.sweepHalfWidth = value; return this; }
        SettingsMutator sweepTotalWidth(int value) { this.sweepTotalWidth = value; return this; }
        SettingsMutator laneStride(int value) { this.laneStride = value; return this; }
        SettingsMutator forwardLookaheadSteps(int value) { this.forwardLookaheadSteps = value; return this; }
        SettingsMutator trivialBehindCleanupSteps(int value) { this.trivialBehindCleanupSteps = value; return this; }
        SettingsMutator groundedSweepConstantSprint(boolean value) { this.groundedSweepConstantSprint = value; return this; }
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
}
