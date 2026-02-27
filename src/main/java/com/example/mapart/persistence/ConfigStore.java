package com.example.mapart.persistence;

import com.example.mapart.MapArtMod;
import com.example.mapart.plan.BuildPlan;
import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

public class ConfigStore {
    private static final Gson GSON = new Gson();

    private final Path storagePath;
    private Path lastLoadedPlanPath;
    private BlockPos lastOrigin;

    public ConfigStore() {
        this(defaultPath());
    }

    public ConfigStore(Path storagePath) {
        this.storagePath = storagePath;
        loadFromDisk();
    }

    public Optional<Path> getLastLoadedPlanPath() {
        return Optional.ofNullable(lastLoadedPlanPath);
    }

    public void rememberLoadedPlan(BuildPlan plan) {
        this.lastLoadedPlanPath = plan.sourcePath();
        saveToDisk();
    }

    public void rememberOrigin(BlockPos origin) {
        this.lastOrigin = origin.toImmutable();
        saveToDisk();
    }

    public Optional<BlockPos> getLastOrigin() {
        return Optional.ofNullable(lastOrigin);
    }

    public void clearRememberedState() {
        lastLoadedPlanPath = null;
        lastOrigin = null;
        saveToDisk();
    }

    private void loadFromDisk() {
        if (!Files.exists(storagePath)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(storagePath)) {
            StoredConfig stored = GSON.fromJson(reader, StoredConfig.class);
            if (stored == null) {
                return;
            }

            this.lastLoadedPlanPath = stored.lastLoadedPlanPath == null ? null : Path.of(stored.lastLoadedPlanPath);
            this.lastOrigin = stored.lastOrigin == null ? null : stored.lastOrigin.toBlockPos();
        } catch (RuntimeException exception) {
            MapArtMod.LOGGER.warn("Config file {} is malformed; using defaults.", storagePath, exception);
        } catch (IOException exception) {
            MapArtMod.LOGGER.warn("Failed to read config file {}; using defaults.", storagePath, exception);
        }
    }

    private void saveToDisk() {
        StoredConfig stored = new StoredConfig(
                lastLoadedPlanPath == null ? null : lastLoadedPlanPath.toString(),
                lastOrigin == null ? null : StoredOrigin.from(lastOrigin)
        );

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
            MapArtMod.LOGGER.warn("Failed to save config file {}.", storagePath, exception);
        }
    }

    private static Path defaultPath() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("mapartrunner-config.json");
        } catch (Exception ignored) {
            return Path.of("config", "mapartrunner-config.json");
        }
    }

    private record StoredConfig(String lastLoadedPlanPath, StoredOrigin lastOrigin) {
    }

    private record StoredOrigin(int x, int y, int z) {
        static StoredOrigin from(BlockPos pos) {
            return new StoredOrigin(pos.getX(), pos.getY(), pos.getZ());
        }

        BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }
    }
}
