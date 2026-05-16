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
    void manualAirPlaceDefaults() {
        Path settingsPath = tempDir.resolve("settings-defaults.json");
        MapartSettingsStore store = new MapartSettingsStore(settingsPath);

        assertEquals(0, store.current().reservedHotbarSlots());
        assertFalse(store.current().torchGridEnabled());
        assertEquals(12, store.current().torchGridSpacing());
        assertTrue(store.current().torchGridWarnMissingTorches());
        assertEquals(1, store.current().torchGridMaxPlacementsPerTick());
        assertFalse(store.current().manualAirPlaceEnabled());
        assertTrue(store.current().manualAirPlaceRender());
        assertFalse(store.current().manualAirPlaceUseCustomRange());
        assertEquals(5.0, store.current().manualAirPlaceCustomRange());
        assertFalse(store.current().manualAirPlaceRequireSneak());
        assertTrue(store.current().manualAirPlaceDisableWhileRunnerActive());
    }

    @Test
    void settingsStorePersistsValues() {
        Path settingsPath = tempDir.resolve("settings.json");
        MapartSettingsStore store = new MapartSettingsStore(settingsPath);
        assertTrue(store.set("showHud", "false").isEmpty());
        assertTrue(store.set("hudX", "42").isEmpty());
        assertTrue(store.set("placementDelayTicks", "5").isEmpty());
        assertTrue(store.set("inventoryClickDelayTicks", "3").isEmpty());
        assertTrue(store.set("reservedHotbarSlots", "5").isEmpty());
        assertTrue(store.set("torchGridEnabled", "true").isEmpty());
        assertTrue(store.set("torchGridWarnMissingTorches", "false").isEmpty());
        assertTrue(store.set("torchGridMaxPlacementsPerTick", "3").isEmpty());
        assertTrue(store.set("clientTimerEnabled", "false").isEmpty());
        assertTrue(store.set("manualAirPlaceEnabled", "true").isEmpty());
        assertTrue(store.set("manualAirPlaceRender", "false").isEmpty());
        assertTrue(store.set("manualAirPlaceUseCustomRange", "true").isEmpty());
        assertTrue(store.set("manualAirPlaceCustomRange", "4.5").isEmpty());
        assertTrue(store.set("manualAirPlaceRequireSneak", "true").isEmpty());
        assertTrue(store.set("manualAirPlaceDisableWhileRunnerActive", "false").isEmpty());

        MapartSettingsStore restored = new MapartSettingsStore(settingsPath);
        assertFalse(restored.current().showHud());
        assertEquals(42, restored.current().hudX());
        assertEquals(5, restored.current().placementDelayTicks());
        assertEquals(3, restored.current().inventoryClickDelayTicks());
        assertEquals(5, restored.current().reservedHotbarSlots());
        assertTrue(restored.current().torchGridEnabled());
        assertEquals(12, restored.current().torchGridSpacing());
        assertFalse(restored.current().torchGridWarnMissingTorches());
        assertEquals(3, restored.current().torchGridMaxPlacementsPerTick());
        assertFalse(restored.current().clientTimerEnabled());
        assertTrue(restored.current().manualAirPlaceEnabled());
        assertFalse(restored.current().manualAirPlaceRender());
        assertTrue(restored.current().manualAirPlaceUseCustomRange());
        assertEquals(4.5, restored.current().manualAirPlaceCustomRange());
        assertTrue(restored.current().manualAirPlaceRequireSneak());
        assertFalse(restored.current().manualAirPlaceDisableWhileRunnerActive());
    }

    @Test
    void manualAirPlaceCustomRangeBounds() {
        Path settingsPath = tempDir.resolve("settings-air-place-range.json");
        MapartSettingsStore store = new MapartSettingsStore(settingsPath);

        assertTrue(store.set("manualAirPlaceCustomRange", "0.0").isEmpty());
        assertEquals(0.0, store.current().manualAirPlaceCustomRange());

        assertTrue(store.set("manualAirPlaceCustomRange", "6.0").isEmpty());
        assertEquals(6.0, store.current().manualAirPlaceCustomRange());

        assertTrue(store.set("manualAirPlaceCustomRange", "-0.1").isPresent());
        assertTrue(store.set("manualAirPlaceCustomRange", "6.1").isPresent());
    }

    @Test
    void reservedHotbarSlotsAcceptsBoundsAndRejectsOutsideRange() {
        Path settingsPath = tempDir.resolve("settings-reserved-hotbar.json");
        MapartSettingsStore store = new MapartSettingsStore(settingsPath);

        assertTrue(store.set("reservedHotbarSlots", "0").isEmpty());
        assertEquals(0, store.current().reservedHotbarSlots());

        assertTrue(store.set("reservedHotbarSlots", "8").isEmpty());
        assertEquals(8, store.current().reservedHotbarSlots());

        assertTrue(store.set("reservedHotbarSlots", "-1").isPresent());
        assertTrue(store.set("reservedHotbarSlots", "9").isPresent());
        assertEquals(8, store.current().reservedHotbarSlots());
    }

    @Test
    void torchGridSpacingIsFixedAndNotUserConfigurable() {
        Path settingsPath = tempDir.resolve("settings-torch-grid-spacing-fixed.json");
        MapartSettingsStore store = new MapartSettingsStore(settingsPath);

        assertEquals(12, store.current().torchGridSpacing());
        assertTrue(store.set("torchGridSpacing", "8").isPresent());
        assertEquals(12, store.current().torchGridSpacing());
        assertTrue(store.set("torchGridSpacing", "13").isPresent());
        assertEquals(12, store.current().torchGridSpacing());
        assertTrue(store.set("torchGridSpacing", "16").isPresent());
        assertEquals(12, store.current().torchGridSpacing());
    }

    @Test
    void oldStoredTorchGridSpacingValuesNormalizeToFixedSpacing() throws Exception {
        Path settingsPath = tempDir.resolve("settings-old-torch-grid-spacing.json");
        Files.writeString(settingsPath, """
                {
                  "torchGridEnabled": true,
                  "torchGridSpacing": 16,
                  "torchGridWarnMissingTorches": false,
                  "torchGridMaxPlacementsPerTick": 4
                }
                """);

        MapartSettingsStore store = new MapartSettingsStore(settingsPath);

        assertTrue(store.current().torchGridEnabled());
        assertEquals(12, store.current().torchGridSpacing());
        assertFalse(store.current().torchGridWarnMissingTorches());
        assertEquals(4, store.current().torchGridMaxPlacementsPerTick());
    }

    @Test
    void torchGridMaxPlacementsPerTickAcceptsBoundsAndRejectsOutsideRange() {
        Path settingsPath = tempDir.resolve("settings-torch-grid-max.json");
        MapartSettingsStore store = new MapartSettingsStore(settingsPath);

        assertTrue(store.set("torchGridMaxPlacementsPerTick", "1").isEmpty());
        assertEquals(1, store.current().torchGridMaxPlacementsPerTick());

        assertTrue(store.set("torchGridMaxPlacementsPerTick", "4").isEmpty());
        assertEquals(4, store.current().torchGridMaxPlacementsPerTick());

        assertTrue(store.set("torchGridMaxPlacementsPerTick", "0").isPresent());
        assertTrue(store.set("torchGridMaxPlacementsPerTick", "5").isPresent());
        assertEquals(4, store.current().torchGridMaxPlacementsPerTick());
    }

    @Test
    void clientTimerSpeedClampsAtBounds() {
        Path settingsPath = tempDir.resolve("settings-timer.json");
        MapartSettingsStore store = new MapartSettingsStore(settingsPath);

        assertTrue(store.set("clientTimerSpeed", "1").isEmpty());
        assertEquals(1, store.current().clientTimerSpeed());

        assertTrue(store.set("clientTimerSpeed", "20").isEmpty());
        assertEquals(20, store.current().clientTimerSpeed());

        assertTrue(store.set("clientTimerSpeed", "0").isPresent(), "speed 0 should be rejected");
        assertTrue(store.set("clientTimerSpeed", "21").isPresent(), "speed 21 should be rejected");
    }

    @Test
    void clientTimerEnabledPersistsAcrossReload() {
        Path settingsPath = tempDir.resolve("settings-timer-enabled.json");
        MapartSettingsStore store = new MapartSettingsStore(settingsPath);
        assertTrue(store.set("clientTimerEnabled", "false").isEmpty());
        assertTrue(store.set("clientTimerSpeed", "8").isEmpty());

        MapartSettingsStore restored = new MapartSettingsStore(settingsPath);
        assertFalse(restored.current().clientTimerEnabled());
        assertEquals(8, restored.current().clientTimerSpeed());
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
