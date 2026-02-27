package com.example.mapart.persistence;

import com.example.mapart.MapArtMod;
import com.example.mapart.plan.state.BuildPlanState;
import com.example.mapart.plan.state.BuildSession;
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

public class ProgressStore {
    private static final Gson GSON = new Gson();

    private final Path storagePath;
    private Snapshot snapshot;

    public ProgressStore() {
        this(defaultPath());
    }

    public ProgressStore(Path storagePath) {
        this.storagePath = storagePath;
        loadFromDisk();
    }

    public void initializePlanProgress(BuildSession session) {
        saveProgress(session);
    }

    public void saveProgress(BuildSession session) {
        snapshot = new Snapshot(
                session.getPlan().sourcePath().toString(),
                session.getOrigin(),
                session.getState().name(),
                session.getProgress().getCurrentRegionIndex(),
                session.getProgress().getCurrentPlacementIndex(),
                session.getTotalCompletedPlacements()
        );
        saveToDisk();
    }

    public Optional<Snapshot> getSnapshot() {
        return Optional.ofNullable(snapshot);
    }

    public void clearProgress() {
        snapshot = null;
        saveToDisk();
    }

    private void loadFromDisk() {
        if (!Files.exists(storagePath)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(storagePath)) {
            StoredSnapshot stored = GSON.fromJson(reader, StoredSnapshot.class);
            if (stored == null || stored.loadedPlanId == null || stored.loadedPlanId.isBlank()) {
                return;
            }

            this.snapshot = new Snapshot(
                    stored.loadedPlanId,
                    stored.origin == null ? null : stored.origin.toBlockPos(),
                    stored.state,
                    Math.max(0, stored.currentRegionIndex),
                    Math.max(0, stored.currentPlacementIndex),
                    Math.max(0, stored.totalCompletedPlacements)
            );
        } catch (RuntimeException exception) {
            MapArtMod.LOGGER.warn("Progress file {} is malformed; skipping restore.", storagePath, exception);
            snapshot = null;
        } catch (IOException exception) {
            MapArtMod.LOGGER.warn("Failed to read progress file {}; skipping restore.", storagePath, exception);
            snapshot = null;
        }
    }

    private void saveToDisk() {
        StoredSnapshot stored = snapshot == null
                ? new StoredSnapshot(null, null, null, 0, 0, 0)
                : new StoredSnapshot(
                        snapshot.loadedPlanId(),
                        snapshot.origin() == null ? null : StoredOrigin.from(snapshot.origin()),
                        snapshot.state(),
                        snapshot.currentRegionIndex(),
                        snapshot.currentPlacementIndex(),
                        snapshot.totalCompletedPlacements()
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
            MapArtMod.LOGGER.warn("Failed to save progress file {}.", storagePath, exception);
        }
    }

    private static Path defaultPath() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("mapartrunner-progress.json");
        } catch (Exception ignored) {
            return Path.of("config", "mapartrunner-progress.json");
        }
    }

    public record Snapshot(
            String loadedPlanId,
            BlockPos origin,
            String state,
            int currentRegionIndex,
            int currentPlacementIndex,
            int totalCompletedPlacements
    ) {
        public Optional<BuildPlanState> parsedState() {
            if (state == null || state.isBlank()) {
                return Optional.empty();
            }

            try {
                return Optional.of(BuildPlanState.valueOf(state));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }

        public Optional<BlockPos> originPos() {
            return Optional.ofNullable(origin);
        }
    }

    private record StoredSnapshot(
            String loadedPlanId,
            StoredOrigin origin,
            String state,
            int currentRegionIndex,
            int currentPlacementIndex,
            int totalCompletedPlacements
    ) {
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
