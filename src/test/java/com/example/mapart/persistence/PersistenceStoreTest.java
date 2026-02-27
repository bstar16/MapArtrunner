package com.example.mapart.persistence;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.state.BuildSession;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void configStorePersistsAndRestoresValues() {
        Path configPath = tempDir.resolve("config.json");
        ConfigStore store = new ConfigStore(configPath);

        BuildPlan plan = new BuildPlan(
                "test",
                tempDir.resolve("fixture.nbt"),
                new Vec3i(1, 1, 1),
                List.of(),
                Map.of(),
                List.of()
        );

        store.rememberLoadedPlan(plan);
        store.rememberOrigin(new BlockPos(3, 64, 9));

        ConfigStore restored = new ConfigStore(configPath);
        assertEquals(plan.sourcePath(), restored.getLastLoadedPlanPath().orElseThrow());
        assertEquals(new BlockPos(3, 64, 9), restored.getLastOrigin().orElseThrow());
    }

    @Test
    void configStoreMalformedJsonFallsBackToDefaults() throws Exception {
        Path configPath = tempDir.resolve("config-malformed.json");
        Files.writeString(configPath, "{ invalid json");

        ConfigStore store = new ConfigStore(configPath);
        assertTrue(store.getLastLoadedPlanPath().isEmpty());
        assertTrue(store.getLastOrigin().isEmpty());
    }

    @Test
    void progressStorePersistsAndRestoresSnapshot() {
        Path progressPath = tempDir.resolve("progress.json");
        ProgressStore store = new ProgressStore(progressPath);

        BuildPlan plan = new BuildPlan(
                "test",
                tempDir.resolve("fixture.nbt"),
                new Vec3i(1, 1, 1),
                List.of(),
                Map.of(),
                List.of()
        );
        BuildSession session = new BuildSession(plan);
        session.getProgress().setCurrentRegionIndex(2);
        session.getProgress().setCurrentPlacementIndex(5);
        store.saveProgress(session);

        ProgressStore restored = new ProgressStore(progressPath);
        ProgressStore.Snapshot snapshot = restored.getSnapshot().orElseThrow();

        assertEquals(plan.sourcePath().toString(), snapshot.loadedPlanId());
        assertEquals("IDLE", snapshot.state());
        assertEquals(2, snapshot.currentRegionIndex());
        assertEquals(5, snapshot.currentPlacementIndex());
    }

    @Test
    void progressStoreMalformedJsonFallsBackSafely() throws Exception {
        Path progressPath = tempDir.resolve("progress-malformed.json");
        Files.writeString(progressPath, "not-json");

        ProgressStore store = new ProgressStore(progressPath);
        assertTrue(store.getSnapshot().isEmpty());
    }
}
