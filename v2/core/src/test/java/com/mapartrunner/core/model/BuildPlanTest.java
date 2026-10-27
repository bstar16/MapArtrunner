package com.mapartrunner.core.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.mapartrunner.core.model.BuildPlan.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildPlanTest {
    private static final GridSize SIZE = new GridSize(2, 1, 3);
    private static final BlockSpec STONE = new BlockSpec("minecraft:stone", Map.of());

    @Test
    void snapshotsCallerCollectionsWithoutChangingThem() {
        var properties = new HashMap<>(Map.of("axis", "y"));
        var block = new BlockSpec("minecraft:oak_log", properties);
        var target = new Target(7, new GridPos(1, 0, 2), block);
        var targets = new ArrayList<>(List.of(target));
        var plan = new BuildPlan("content-hash", SIZE, targets);

        assertEquals(List.of(target), targets);
        assertEquals(Map.of("axis", "y"), properties);
        targets.clear();
        properties.put("axis", "x");
        assertEquals(List.of(target), plan.targets());
        assertEquals(Map.of("axis", "y"), plan.targets().getFirst().block().properties());
        assertThrows(UnsupportedOperationException.class, () -> plan.targets().clear());
        assertThrows(UnsupportedOperationException.class, () -> block.properties().put("axis", "z"));
        assertEquals("content-hash", plan.fingerprint());
        assertEquals(SIZE, plan.dimensions());
    }

    @Test
    void emptyRequiredTargetsAreRepresentable() {
        assertTrue(new BuildPlan("empty-hash", SIZE, List.of()).targets().isEmpty());
    }

    @Test
    void duplicateIdsAreRejectedWithoutModifyingCallerList() {
        var targets = new ArrayList<>(List.of(target(2, 0, 0, 0), target(2, 1, 0, 1)));
        var original = List.copyOf(targets);
        assertThrows(IllegalArgumentException.class, () -> new BuildPlan("hash", SIZE, targets));
        assertEquals(original, targets);
    }

    @ParameterizedTest
    @ValueSource(strings = {"stone", "Minecraft:stone", "minecraft:", ":stone", "minecraft:stone:extra", "minecraft:bad block", " minecraft:stone"})
    void invalidBlockIdentifiersAreRejected(String id) {
        assertThrows(IllegalArgumentException.class, () -> new BlockSpec(id, Map.of()));
    }

    @Test
    void namespacedModIdentifiersAndPropertiesRemainNeutralValues() {
        var block = new BlockSpec("test_mod:decor/stone-1.2", Map.of("facing", "north"));
        assertEquals("test_mod:decor/stone-1.2", block.id());
        assertEquals(Map.of("facing", "north"), block.properties());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void fingerprintMustBePresent(String fingerprint) {
        Class<? extends RuntimeException> expected = fingerprint == null ? NullPointerException.class : IllegalArgumentException.class;
        assertThrows(expected, () -> new BuildPlan(fingerprint, SIZE, List.of()));
    }

    @Test
    void dimensionsMustBePositiveOnEveryAxis() {
        for (int invalid : new int[]{0, -1}) {
            assertThrows(IllegalArgumentException.class, () -> new GridSize(invalid, 1, 1));
            assertThrows(IllegalArgumentException.class, () -> new GridSize(1, invalid, 1));
            assertThrows(IllegalArgumentException.class, () -> new GridSize(1, 1, invalid));
        }
    }

    @Test
    void targetPositionsMustBeWithinSourceDimensions() {
        for (GridPos pos : List.of(new GridPos(-1, 0, 0), new GridPos(0, -1, 0),
                new GridPos(0, 0, -1), new GridPos(2, 0, 0), new GridPos(0, 1, 0), new GridPos(0, 0, 3))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new BuildPlan("hash", SIZE, List.of(new Target(0, pos, STONE))));
        }
    }

    @Test
    void rejectsNullModelPartsAndNegativeIds() {
        assertThrows(NullPointerException.class, () -> new BuildPlan("hash", null, List.of()));
        assertThrows(NullPointerException.class, () -> new BuildPlan("hash", SIZE, null));
        var nullTargets = new ArrayList<Target>();
        nullTargets.add(null);
        assertThrows(NullPointerException.class, () -> new BuildPlan("hash", SIZE, nullTargets));
        assertThrows(IllegalArgumentException.class, () -> target(-1, 0, 0, 0));
        assertThrows(NullPointerException.class, () -> new Target(0, null, STONE));
        assertThrows(NullPointerException.class, () -> new Target(0, new GridPos(0, 0, 0), null));
        assertThrows(NullPointerException.class, () -> new BlockSpec(null, Map.of()));
        assertThrows(NullPointerException.class, () -> new BlockSpec("minecraft:stone", null));
    }

    @Test
    void propertyNamesAndValuesMustBePresentWithoutMutatingCallerMap() {
        for (String invalid : new String[]{null, "", " "}) {
            Class<? extends RuntimeException> expected = invalid == null ? NullPointerException.class : IllegalArgumentException.class;
            var properties = new HashMap<String, String>();
            properties.put(invalid, "north");
            assertThrows(expected, () -> new BlockSpec("minecraft:stone", properties));
            assertEquals(1, properties.size());
            properties.clear();
            properties.put("facing", invalid);
            assertThrows(expected, () -> new BlockSpec("minecraft:stone", properties));
            assertEquals(1, properties.size());
        }
    }

    private static Target target(int id, int x, int y, int z) {
        return new Target(id, new GridPos(x, y, z), STONE);
    }
}
