package com.mapartrunner.fabric.schematic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.core.traversal.LanePlanner;
import com.mapartrunner.core.traversal.LanePlanner.*;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TraversalFixtureTest {
    private static final BlockSpec STONE = new BlockSpec("minecraft:stone", Map.of());
    private static final Comparator<GridPos> XYZ = Comparator.comparingInt(GridPos::x)
            .thenComparingInt(GridPos::y).thenComparingInt(GridPos::z);
    @TempDir Path temporary;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @Test
    void resume21DataAndImportReceipts() throws Exception {
        verify("traversal-resume-21", 3, 105, 4, 106, 2.5, 102.5, 21,
                Set.of(new GridPos(0, 0, 0), new GridPos(2, 0, 104)),
                "f27205a41ef3eb73aa7aa8cdbf2fa6d2243b5e99f06c9fcd45e322b83ed745ea",
                "986faf642fe529e82ddd9ed35242b57f341c1f1904e6bbd861c427f69e66d6e1",
                "781529ba3f528c8f3ab91722d2abd91b72959bc051a9294b829d5eb5665b90d5");
    }

    @Test
    void narrowDataAndImportReceipts() throws Exception {
        verify("traversal-narrow", 1, 3, 2, 4, 0.5, 2.5, 1,
                Set.of(new GridPos(0, 0, 0)),
                "642e032ffda138fb23fb5b9ee65107c370e93e0142138199d5b3d6a61e5167b2",
                "54460ffd734780b3ae2f9af09b31c4f9cacd54d8b2ed42f44c442403f2535f04",
                "4957b1d3621ce8e33bcabeb996aa5c4f9901f8dd7c9fce455dfdef151b9ac751");
    }

    @Test
    void reverseOddDataAndImportReceipts() throws Exception {
        verify("traversal-reverse-odd", 12, 15, 13, 16, 0.5, 2.5, 3,
                Set.of(new GridPos(0, 0, 0)), JsonParser.parseString("""
                {
                  "arm": {
                    "laneId": 2, "pass": "FORWARD", "stage": "WALK",
                    "playerX": {"comparison": ">=", "value": 8.0},
                    "requireBeyondActualInteractionRange": true
                  },
                  "relativeTarget": {"x": 6, "y": 0, "z": 5},
                  "requiredForwardLaneIdsBeforeCleanup": [0, 1, 2],
                  "testCreatedReplacement": false
                }
                """).getAsJsonObject(),
                "e87c400b443e7e55b87e9aa5b04d44b34dcb27eeeefb8def1e4ece43f2b30bfc",
                "b64b033937d160bc2385840b851a82c0a3fe3dd66ff68bcfc1965ed4f3039c48",
                "8a70a76a79075bc381eb3f2bb479b939027398e62599468c07e3e91f85ee0cc6");
    }

    @Test
    void reverseEvenDataAndImportReceipts() throws Exception {
        verify("traversal-reverse-even", 12, 20, 13, 21, 0.5, 2.5, 4,
                Set.of(new GridPos(0, 0, 0)), JsonParser.parseString("""
                {
                  "arm": {
                    "laneId": 3, "pass": "FORWARD", "stage": "WALK",
                    "playerX": {"comparison": "<=", "value": 3.5},
                    "requireBeyondActualInteractionRange": true
                  },
                  "relativeTarget": {"x": 6, "y": 0, "z": 5},
                  "requiredForwardLaneIdsBeforeCleanup": [0, 1, 2, 3],
                  "testCreatedReplacement": false
                }
                """).getAsJsonObject(),
                "09ead26cc29971b81d9d536a5122c66cf2eef393a629a8f654e8307ac5796fe2",
                "536f67248eb87506353a3e2486126936f5b4ba5140e733fcb806ea59a7193f3b",
                "4c18b40851d6f7becfa4e9e88710dbf5c57f774706a897b56293930417dc1db4");
    }

    @Test
    void reverseMultiDataAndImportReceipts() throws Exception {
        verify("traversal-reverse-multi", 12, 25, 13, 26, 0.5, 2.5, 5,
                Set.of(new GridPos(0, 0, 0)), JsonParser.parseString("""
                {
                  "arm": {
                    "laneId": 4, "pass": "FORWARD", "stage": "WALK",
                    "playerX": {"comparison": ">=", "value": 8.0},
                    "requireBeyondActualInteractionRange": true
                  },
                  "checkpointOnlyLaneIds": [2],
                  "relativeTargets": [{"x": 6, "y": 0, "z": 5}, {"x": 6, "y": 0, "z": 15}],
                  "removeEachTargetOnce": true,
                  "requireIndependentReplacementVerification": true,
                  "requiredForwardLaneIdsBeforeCleanup": [0, 1, 2, 3, 4],
                  "requiredReverseLaneIds": [3, 1],
                  "testCreatedReplacement": false
                }
                """).getAsJsonObject(),
                "4967ac9114bfee36543c55d23143f7efc4ddbd40ab0bee1ab23886f7c8cfc6aa",
                "ca01e60b829e80a587434fad62403bd6203d51404488d72882daffb0365cc9b5",
                "e0e80afb1551f037b806bf84db3917a155627db2b33a0febdcd142d1515b0151");
    }

    @Test
    void sparsePartialDataAndImportReceipts() throws Exception {
        var expected = List.of(new Target(0, new GridPos(0, 0, 0), STONE),
                new Target(1, new GridPos(2, 0, 4), STONE),
                new Target(2, new GridPos(2, 0, 8), STONE),
                new Target(3, new GridPos(1, 0, 10), STONE));
        var support = new HashSet<GridPos>();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 11; z++) support.add(new GridPos(x, 0, z));
        }
        expected.forEach(target -> support.remove(target.relativePos()));
        assertEquals(29, support.size());
        var plan = verify("traversal-sparse-partial", 3, 11, 4, 12, 0.5, 2.5,
                expected, List.of(2, 7, 8),
                Set.of(new GridPos(0, 0, 0), new GridPos(2, 0, 8), new GridPos(1, 0, 10)),
                support, null,
                "913bf18d73cb4fbeb77f4301d171d4ed9bc858cab643cf89445020f586d28dbc",
                "30d673421819829ae6528f1585b71d2f06711a75b7a0605842d28d8235c10ff2",
                "d8623a8d4e5f93233054827d9ce591853cedb7c36c3759673c4330147f5890af");
        var lanes = new LanePlanner().plan(plan, 2, 5);
        assertEquals(List.of(1, 2), lanes.stream().filter(lane -> lane.corridor().minZ() <= 8
                && lane.corridor().maxZ() >= 8).map(Lane::id).toList(), "target 2 is rechecked in the overlap");
        for (var lane : lanes) {
            assertSame(lanes.getFirst().expectedFloor(), lane.expectedFloor());
            assertEquals(4, lane.expectedFloor().targetsByPosition().size(), "overlap and support create no targets");
            assertEquals(expected.get(2), lane.expectedFloor().targetsByPosition().get(new GridPos(2, 0, 8)));
        }
    }

    @Test
    void receiptPreconditionsRejectUnsortedKeysPositionsAndInventory() {
        assertThrows(AssertionError.class, () -> assertSortedKeys(JsonParser.parseString("{\"z\":0,\"a\":0}")));
        var positions = JsonParser.parseString("[{\"position\":{\"x\":1,\"y\":0,\"z\":0}},"
                + "{\"position\":{\"x\":0,\"y\":0,\"z\":0}}]").getAsJsonArray();
        assertThrows(AssertionError.class, () -> assertPositionOrder(positions));
        assertThrows(AssertionError.class, () -> assertInventoryOrder(
                JsonParser.parseString("[{\"slot\":1},{\"slot\":0}]").getAsJsonArray()));
    }

    private void verify(String name, int width, int depth, int maxX, int maxZ,
                        double playerX, double playerZ, int laneCount, Set<GridPos> missing,
                        String definitionHash, String sourceHash, String fingerprint) throws Exception {
        verify(name, width, depth, maxX, maxZ, playerX, playerZ, laneCount, missing, null,
                definitionHash, sourceHash, fingerprint);
    }

    private void verify(String name, int width, int depth, int maxX, int maxZ,
                        double playerX, double playerZ, int laneCount, Set<GridPos> missing,
                        JsonObject expectedLateRemoval, String definitionHash, String sourceHash, String fingerprint) throws Exception {
        var expected = new ArrayList<Target>();
        for (int z = 0; z < depth; z++) {
            for (int step = 0; step < width; step++) {
                int x = z % 2 == 0 ? step : width - 1 - step;
                expected.add(new Target(expected.size(), new GridPos(x, 0, z), STONE));
            }
        }
        var centers = new ArrayList<Integer>();
        for (int i = 0; i < laneCount; i++) centers.add(laneCount == 1 ? 2 : 2 + 5 * i);
        verify(name, width, depth, maxX, maxZ, playerX, playerZ, expected, centers, missing, Set.of(),
                expectedLateRemoval, definitionHash, sourceHash, fingerprint);
    }

    private BuildPlan verify(String name, int width, int depth, int maxX, int maxZ,
                             double playerX, double playerZ, List<Target> expected, List<Integer> centers,
                             Set<GridPos> missing, Set<GridPos> nonTargetSupport, JsonObject expectedLateRemoval,
                             String definitionHash, String sourceHash, String fingerprint) throws Exception {
        var definition = definition("/" + name + "/definition.json");
        assertSortedKeys(definition);
        assertPositionOrder(definition.getAsJsonObject("world").getAsJsonArray("blocks"));
        assertPositionOrder(definition.getAsJsonObject("schematic").getAsJsonArray("expectedTargets"));
        assertInventoryOrder(definition.getAsJsonObject("player").getAsJsonArray("inventory"));
        if (!nonTargetSupport.isEmpty()) assertPositionOrder(definition.getAsJsonObject("nonTargetSupport").getAsJsonArray("blocks"));
        var removals = new ArrayList<GridPos>();
        if (expectedLateRemoval != null) {
            var removal = definition.getAsJsonObject("lateRemoval");
            if (removal.has("relativeTarget")) removals.add(position(removal.getAsJsonObject("relativeTarget")));
            else removal.getAsJsonArray("relativeTargets").forEach(value -> removals.add(position(value.getAsJsonObject())));
            assertEquals(removals.stream().sorted(XYZ).toList(), removals, "removal positions ordered by (x,y,z)");
            assertEquals(removals.size(), new HashSet<>(removals).size(), "independent removal targets");
        }
        // Already ordered input is the canonicalizer's fixed point; hash compact Gson UTF-8.
        assertEquals(definitionHash, sha256(definition.toString().getBytes(StandardCharsets.UTF_8)));

        var baseline = definition("/grounded-two-lane/definition.json");
        var keys = new HashSet<>(baseline.keySet());
        if (!nonTargetSupport.isEmpty()) keys.add("nonTargetSupport");
        if (expectedLateRemoval != null) keys.add("lateRemoval");
        assertEquals(keys, definition.keySet());
        if (expectedLateRemoval != null) {
            assertEquals(expectedLateRemoval, definition.get("lateRemoval"), "closed late-removal metadata contract");
        }
        assertEquals("mapartrunner-" + name + "-v1", definition.get("schema").getAsString());
        for (String inherited : List.of("settings", "physics", "budgets")) {
            assertEquals(baseline.get(inherited), definition.get(inherited), inherited);
        }
        var player = baseline.getAsJsonObject("player").deepCopy();
        player.add("position", JsonParser.parseString("{\"x\":" + playerX + ",\"y\":66.0,\"z\":" + playerZ + "}"));
        assertEquals(player, definition.get("player"), "complete inherited player/inventory/readiness contract");
        var provenance = baseline.getAsJsonObject("provenance").deepCopy();
        provenance.addProperty("geometryStatus", "candidate traversal data; native traversal proof pending");
        assertEquals(provenance, definition.get("provenance"));

        var schematic = definition.getAsJsonObject("schematic");
        assertEquals(baseline.getAsJsonObject("schematic").keySet(), schematic.keySet());
        String resource = "/" + name + "/" + name + ".schem";
        assertEquals(resource, schematic.get("resource").getAsString());
        assertEquals(new GridSize(width, 1, depth), size(schematic.getAsJsonObject("dimensions")));
        assertEquals(new GridPos(0, 65, 0), position(schematic.getAsJsonObject("origin")));
        var source = temporary.resolve(name + ".schem");
        Files.write(source, resource(resource));
        assertEquals(sourceHash, sha256(Files.readAllBytes(source)));
        assertEquals(sourceHash, schematic.get("rawSourceSha256").getAsString());
        var plan = SchematicPlanImporter.importPlan(source);
        assertEquals(fingerprint, plan.fingerprint());
        assertEquals(fingerprint, schematic.get("normalizedFingerprint").getAsString());
        assertEquals(new GridSize(width, 1, depth), plan.dimensions());
        assertEquals(expected, plan.targets(), "importer IDs retain serpentine order, separate from JSON XYZ order");
        var targetStates = new HashMap<GridPos, BlockSpec>();
        for (Target target : expected) targetStates.put(target.relativePos(), target.block());
        assertEquals(targetStates, blocks(schematic.getAsJsonArray("expectedTargets")));
        assertTrue(nonTargetSupport.stream().noneMatch(targetStates::containsKey), "physical support remains source AIR");
        if (!nonTargetSupport.isEmpty()) {
            var support = definition.getAsJsonObject("nonTargetSupport");
            assertEquals(Set.of("blocks", "requireUnchangedAtEnd"), support.keySet());
            assertTrue(support.get("requireUnchangedAtEnd").getAsBoolean(), "independent final support check required");
            var supportStates = new HashMap<GridPos, BlockSpec>();
            nonTargetSupport.forEach(pos -> supportStates.put(new GridPos(pos.x(), 65 + pos.y(), pos.z()), STONE));
            assertEquals(supportStates, blocks(support.getAsJsonArray("blocks")), "declared physical support, not required targets");
        }

        var world = definition.getAsJsonObject("world");
        var inheritedWorld = baseline.getAsJsonObject("world").deepCopy();
        inheritedWorld.remove("blocks");
        inheritedWorld.remove("ownedRegion");
        var actualWorld = world.deepCopy();
        actualWorld.remove("blocks");
        actualWorld.remove("ownedRegion");
        assertEquals(inheritedWorld, actualWorld, "no containers, entities or extra setup effects");
        var region = world.getAsJsonObject("ownedRegion");
        assertEquals(new GridPos(-2, 64, -2), position(region.getAsJsonObject("min")));
        assertEquals(new GridPos(maxX, 70, maxZ), position(region.getAsJsonObject("max")));
        var worldBlocks = blocks(world.getAsJsonArray("blocks"));
        assertEquals((maxX + 3) * 7 * (maxZ + 3), worldBlocks.size());
        int seeded = 0;
        for (int x = -2; x <= maxX; x++) {
            for (int y = 64; y <= 70; y++) {
                for (int z = -2; z <= maxZ; z++) {
                    var relative = new GridPos(x, y - 65, z);
                    boolean stone = targetStates.containsKey(relative) && !missing.contains(relative);
                    String id = y == 64 ? "minecraft:bedrock"
                            : stone || nonTargetSupport.contains(relative) ? "minecraft:stone" : "minecraft:air";
                    assertEquals(new BlockSpec(id, Map.of()), worldBlocks.get(new GridPos(x, y, z)),
                            "pre-start world cell " + x + "," + y + "," + z);
                    if (stone) seeded++;
                }
            }
        }
        assertTrue(targetStates.keySet().containsAll(missing));
        assertEquals(expected.size() - missing.size(), seeded);
        int footX = (int) Math.floor(playerX), footZ = (int) Math.floor(playerZ);
        assertEquals(STONE, worldBlocks.get(new GridPos(footX, 65, footZ)));
        for (int y : List.of(66, 67)) {
            assertEquals(new BlockSpec("minecraft:air", Map.of()), worldBlocks.get(new GridPos(footX, y, footZ)));
        }
        var result = definition.getAsJsonObject("expectedResult");
        assertEquals(baseline.getAsJsonObject("expectedResult").keySet(), result.keySet());
        assertEquals(expected.size(), result.get("targets").getAsInt());
        assertEquals(seeded, result.get("seededTargets").getAsInt());
        if (expectedLateRemoval == null) {
            assertEquals(missing.size(), result.get("placements").getAsInt());
            assertEquals(128 - missing.size(), result.get("usableRemainder").getAsInt());
        } else {
            int placements = missing.size() + removals.size();
            assertEquals(placements, result.get("placements").getAsInt());
            assertEquals(128 - placements, result.get("usableRemainder").getAsInt());
        }
        assertEquals(7, result.get("reservedRemainder").getAsInt());

        var lanes = new LanePlanner().plan(plan, 2, 5);
        assertEquals(centers.size(), lanes.size());
        for (int i = 0; i < centers.size(); i++) {
            var lane = lanes.get(i);
            int center = centers.get(i);
            assertEquals(i, lane.id());
            assertEquals(i % 2 == 0 ? Direction.EAST : Direction.WEST, lane.direction());
            assertEquals(new GridPos(i % 2 == 0 ? 0 : width - 1, 0, center), lane.start());
            assertEquals(new GridPos(i % 2 == 0 ? width - 1 : 0, 0, center), lane.end());
            assertEquals(new Corridor(0, width - 1, Math.max(0, center - 2), Math.min(depth - 1, center + 2)), lane.corridor());
            assertEquals(targetStates.keySet(), lane.expectedFloor().targetsByPosition().keySet());
            for (Target target : expected) assertEquals(target, lane.expectedFloor().targetsByPosition().get(target.relativePos()));
        }
        for (var target : removals) {
            assertFalse(missing.contains(target), "late removal must not influence initial smart selection");
            assertEquals(STONE, targetStates.get(target));
            assertEquals(STONE, worldBlocks.get(new GridPos(target.x(), 65 + target.y(), target.z())));
            var owningLanes = lanes.stream().filter(lane -> target.x() >= lane.corridor().minX()
                    && target.x() <= lane.corridor().maxX() && target.z() >= lane.corridor().minZ()
                    && target.z() <= lane.corridor().maxZ()).map(Lane::id).toList();
            if (expectedLateRemoval.has("relativeTarget")) assertEquals(List.of(1), owningLanes);
            else assertEquals(List.of(target.z() / 5), owningLanes);
            for (var lane : lanes) {
                assertTrue(Math.abs(target.z() - lane.start().z()) > 1,
                        "removal avoids centerline and neighboring walking support cells");
                assertTrue(Math.abs(target.x() - lane.start().x()) > 1
                        && Math.abs(target.x() - lane.end().x()) > 1,
                        "removal avoids end-transition walking support cells");
            }
        }
        if (expectedLateRemoval != null && expectedLateRemoval.has("relativeTargets")) {
            assertEquals(List.of(3, 1), removals.stream().map(target -> target.z() / 5)
                    .sorted(Comparator.reverseOrder()).toList(), "selected reverse lanes, independent of XYZ JSON order");
            assertEquals(12, lanes.get(2).start().z(), "clean lane 2 remains a Z checkpoint");
            assertTrue(removals.stream().noneMatch(target -> target.z() >= lanes.get(2).corridor().minZ()
                    && target.z() <= lanes.get(2).corridor().maxZ()), "checkpoint lane has no removed work");
        }
        return plan;
    }

    private JsonObject definition(String path) throws IOException {
        return JsonParser.parseString(new String(resource(path), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private byte[] resource(String path) throws IOException {
        try (var input = getClass().getResourceAsStream(path)) {
            assertNotNull(input, "checked-in fixture must be on the test classpath: " + path);
            return input.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static GridSize size(JsonObject object) {
        return new GridSize(object.get("width").getAsInt(), object.get("height").getAsInt(), object.get("depth").getAsInt());
    }

    private static GridPos position(JsonObject object) {
        return new GridPos(object.get("x").getAsInt(), object.get("y").getAsInt(), object.get("z").getAsInt());
    }

    private static Map<GridPos, BlockSpec> blocks(JsonArray array) {
        var blocks = new HashMap<GridPos, BlockSpec>();
        for (var value : array) {
            var entry = value.getAsJsonObject();
            var state = entry.getAsJsonObject("block");
            var properties = new HashMap<String, String>();
            state.getAsJsonObject("properties").entrySet().forEach(e -> properties.put(e.getKey(), e.getValue().getAsString()));
            var position = position(entry.getAsJsonObject("position"));
            assertNull(blocks.put(position, new BlockSpec(state.get("id").getAsString(), properties)), "duplicate cell " + position);
        }
        return blocks;
    }

    private static void assertSortedKeys(JsonElement value) {
        if (value.isJsonObject()) {
            var keys = new ArrayList<>(value.getAsJsonObject().keySet());
            assertEquals(keys.stream().sorted().toList(), keys, "recursively sorted object keys");
            value.getAsJsonObject().entrySet().forEach(entry -> assertSortedKeys(entry.getValue()));
        } else if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(TraversalFixtureTest::assertSortedKeys);
        }
    }

    private static void assertPositionOrder(JsonArray array) {
        var positions = new ArrayList<GridPos>();
        array.forEach(value -> positions.add(position(value.getAsJsonObject().getAsJsonObject("position"))));
        assertEquals(positions.stream().sorted(XYZ).toList(), positions, "position arrays ordered by (x,y,z)");
    }

    private static void assertInventoryOrder(JsonArray inventory) {
        var slots = new ArrayList<Integer>();
        inventory.forEach(value -> slots.add(value.getAsJsonObject().get("slot").getAsInt()));
        assertEquals(slots.stream().sorted().toList(), slots, "inventory ordered by slot");
    }
}
