package com.example.mapart.persistence;

import com.example.mapart.settings.MapartSettingsStore;
import com.example.mapart.supply.SupplyPoint;
import com.example.mapart.supply.SupplyStore;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SettingsAndSupplyStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void settingsStorePersistsValues() {
        Path settingsPath = tempDir.resolve("settings.json");
        MapartSettingsStore store = new MapartSettingsStore(settingsPath);
        assertTrue(store.set("showHud", "false").isEmpty());
        assertTrue(store.set("hudX", "42").isEmpty());
        assertTrue(store.set("preferLongerAxis", "true").isEmpty());
        assertTrue(store.set("groundedDebugTrace", "true").isEmpty());

        MapartSettingsStore restored = new MapartSettingsStore(settingsPath);
        assertFalse(restored.current().showHud());
        assertEquals(42, restored.current().hudX());
        assertTrue(restored.current().preferLongerAxis());
        assertTrue(restored.current().groundedDebugTrace());
    }

    @Test
    void groundedTraceDefaultsDisabled() {
        Path settingsPath = tempDir.resolve("settings-defaults.json");
        MapartSettingsStore store = new MapartSettingsStore(settingsPath);

        assertFalse(store.current().groundedDebugTrace());
    }

    @Test
    void settingsStoreMalformedJsonFallsBackToDefaults() throws Exception {
        Path settingsPath = tempDir.resolve("settings-bad.json");
        Files.writeString(settingsPath, "{bad json");

        MapartSettingsStore store = new MapartSettingsStore(settingsPath);
        assertTrue(store.current().showHud());
    }

    @Test
    void supplyStorePersistsEntries() {
        Path suppliesPath = tempDir.resolve("supplies.json");
        SupplyStore store = new SupplyStore(suppliesPath);
        store.add(new BlockPos(1, 2, 3), "minecraft:overworld", "main");

        SupplyStore restored = new SupplyStore(suppliesPath);
        assertEquals(1, restored.list().size());
        assertEquals(new BlockPos(1, 2, 3), restored.list().getFirst().pos());
        assertEquals("main", restored.list().getFirst().name());
    }

    @Test
    void supplyStoreMalformedJsonFallsBackToEmpty() throws Exception {
        Path suppliesPath = tempDir.resolve("supplies-bad.json");
        Files.writeString(suppliesPath, "not-json");

        SupplyStore store = new SupplyStore(suppliesPath);
        assertTrue(store.list().isEmpty());
    }

    @Test
    void supplyStoreFindsNearestMatchingDimension() {
        Path suppliesPath = tempDir.resolve("supplies-nearest.json");
        SupplyStore store = new SupplyStore(suppliesPath);
        store.add(new BlockPos(100, 70, 100), "minecraft:overworld", "far");
        store.add(new BlockPos(5, 64, 5), "minecraft:overworld", "near");
        store.add(new BlockPos(1, 64, 1), "minecraft:the_nether", "wrong-dimension");

        var selected = store.findNearestInDimension("minecraft:overworld", new BlockPos(0, 64, 0)).orElseThrow();

        assertEquals("near", selected.name());
    }
    @Test
    void supplyStoreListsAllSuppliesInDimensionByDistance() {
        Path suppliesPath = tempDir.resolve("supplies-ordered.json");
        SupplyStore store = new SupplyStore(suppliesPath);
        store.add(new BlockPos(20, 64, 20), "minecraft:overworld", "third");
        store.add(new BlockPos(5, 64, 5), "minecraft:overworld", "first");
        store.add(new BlockPos(10, 64, 10), "minecraft:overworld", "second");
        store.add(new BlockPos(1, 64, 1), "minecraft:the_nether", "ignored");

        var ordered = store.listInDimensionByDistance("minecraft:overworld", new BlockPos(0, 64, 0));

        assertEquals(3, ordered.size());
        assertEquals(List.of("first", "second", "third"), ordered.stream().map(SupplyPoint::name).toList());
    }

}
