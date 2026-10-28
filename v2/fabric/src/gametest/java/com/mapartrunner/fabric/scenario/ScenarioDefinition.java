package com.mapartrunner.fabric.scenario;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.core.run.BuildEngine;
import com.mapartrunner.fabric.schematic.SchematicPlanImporter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Immutable admitted fixture data; no world access or restoration effects. */
final class ScenarioDefinition {
    private static final String RESOURCE = "/grounded-two-lane/";
    private static final Comparator<JsonElement> POSITION_ORDER = Comparator
            .comparingInt((JsonElement e) -> position(e.getAsJsonObject().getAsJsonObject("position")).x())
            .thenComparingInt(e -> position(e.getAsJsonObject().getAsJsonObject("position")).y())
            .thenComparingInt(e -> position(e.getAsJsonObject().getAsJsonObject("position")).z());
    private final String canonicalDefinition;
    private final String definitionHash;
    private final byte[] sourceBytes;

    private ScenarioDefinition(JsonObject data, byte[] source) {
        validate(data, source);
        canonicalDefinition = canonical(data);
        definitionHash = sha(canonicalDefinition.getBytes(StandardCharsets.UTF_8));
        sourceBytes = source.clone();
    }

    static ScenarioDefinition load() {
        try {
            var data = JsonParser.parseString(new String(resource("definition.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            return new ScenarioDefinition(data, resource("grounded-two-lane.schem"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load two-lane fixture", e);
        }
    }

    String definitionHash() { return definitionHash; }

    /** The caller owns this detached copy; mutations cannot change the admitted definition or its hash. */
    JsonObject data() { return JsonParser.parseString(canonicalDefinition).getAsJsonObject(); }

    BuildPlan importPlan(Path retainedCopy) {
        try {
            // Never overwrite an earlier run's evidence; retain the exact admitted bytes before importing.
            Files.write(retainedCopy, sourceBytes, StandardOpenOption.CREATE_NEW);
            var plan = SchematicPlanImporter.importPlan(retainedCopy);
            var schematic = data().getAsJsonObject("schematic");
            require(plan.dimensions().equals(dimensions(schematic)), "Imported source dimensions differ from definition");
            require(plan.fingerprint().equals(schematic.get("normalizedFingerprint").getAsString()),
                    "Imported normalized fingerprint differs from definition: " + plan.fingerprint());
            var expected = schematic.getAsJsonArray("expectedTargets");
            require(plan.targets().size() == expected.size(), "Imported target count differs from definition");
            for (var entry : expected) {
                var target = entry.getAsJsonObject();
                var pos = position(target.getAsJsonObject("position"));
                int id = SingleLaneFixture.targetId(plan, pos);
                require(plan.targets().stream().filter(t -> t.id() == id).findFirst().orElseThrow().block()
                        .equals(block(target.getAsJsonObject("block"))), "Imported state differs at " + pos);
            }
            return plan;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot retain/import two-lane fixture at " + retainedCopy, e);
        }
    }

    /** F4 invokes this before world creation and retains the expected rejection diagnostics. */
    static void verifyDefinition() {
        var valid = load();
        rejected(valid, "inverted region", d -> d.getAsJsonObject("world").getAsJsonObject("ownedRegion")
                .getAsJsonObject("min").addProperty("x", 14));
        rejected(valid, "outside region", d -> d.getAsJsonObject("world").getAsJsonArray("blocks")
                .get(0).getAsJsonObject().getAsJsonObject("position").addProperty("x", -3));
        rejected(valid, "missing owned coordinate", d -> d.getAsJsonObject("world").getAsJsonArray("blocks").remove(0));
        rejected(valid, "missing inventory slot", d -> d.getAsJsonObject("player").getAsJsonArray("inventory").remove(0));
        rejected(valid, "duplicate inventory slot", d -> d.getAsJsonObject("player").getAsJsonArray("inventory")
                .get(1).getAsJsonObject().addProperty("slot", 0));
        for (int slot : new int[]{41,42}) {
            rejected(valid, "missing native slot " + slot, d -> d.getAsJsonObject("player").getAsJsonArray("inventory").remove(slot));
            rejected(valid, "duplicate native slot " + slot, d -> d.getAsJsonObject("player").getAsJsonArray("inventory")
                    .get(3).getAsJsonObject().addProperty("slot", slot));
        }
        for (String field : new String[]{"cursorEmpty","craftingInputEmpty","craftingOutputEmpty"})
            rejected(valid, "nonempty player screen " + field, d -> d.getAsJsonObject("player")
                    .getAsJsonObject("playerScreen").addProperty(field, false));
        rejected(valid, "unsupported container", d -> d.getAsJsonObject("world").getAsJsonArray("containers").add(new JsonObject()));
        rejected(valid, "source dimensions", d -> d.getAsJsonObject("schematic").getAsJsonObject("dimensions").addProperty("depth", 9));
        rejected(valid, "source hash", d -> d.getAsJsonObject("schematic").addProperty("rawSourceSha256", "0".repeat(64)));
        rejected(valid, "nonfinite value", d -> d.getAsJsonObject("player").addProperty("health", Double.NaN));
        rejected(valid, "absent expected coordinate", d -> d.getAsJsonObject("schematic").getAsJsonArray("expectedTargets").remove(0));
        rejected(valid, "duplicate expected coordinate", d -> {
            var targets = d.getAsJsonObject("schematic").getAsJsonArray("expectedTargets");
            targets.set(1, targets.get(0).deepCopy());
        });
        hashChanges(valid, "block", d -> d.getAsJsonObject("world").getAsJsonArray("blocks")
                .get(0).getAsJsonObject().getAsJsonObject("block").addProperty("id", "minecraft:stone"));
        hashChanges(valid, "property", d -> d.getAsJsonObject("world").getAsJsonArray("blocks")
                .get(0).getAsJsonObject().getAsJsonObject("block").getAsJsonObject("properties").addProperty("axis", "x"));
        hashChanges(valid, "inventory count", d -> d.getAsJsonObject("player").getAsJsonArray("inventory")
                .get(0).getAsJsonObject().addProperty("count", 6));
        hashChanges(valid, "velocity contract", d -> d.getAsJsonObject("physics").getAsJsonObject("server")
                .getAsJsonObject("expectedVelocity").addProperty("y", 0.0));
        hashChanges(valid, "budget", d -> d.getAsJsonObject("budgets").addProperty("runTicks", 1201));
        hashChanges(valid, "player screen", d -> d.getAsJsonObject("player").getAsJsonObject("playerScreen").addProperty("cursorEmpty", false));
        var reordered = valid.data();
        var blocks = reordered.getAsJsonObject("world").getAsJsonArray("blocks");
        var first = blocks.get(0); blocks.set(0, blocks.get(1)); blocks.set(1, first);
        require(valid.definitionHash.equals(sha(canonical(reordered).getBytes(StandardCharsets.UTF_8))),
                "Position enumeration order changed definition identity");
        var detached = valid.data(); detached.addProperty("schema", "changed");
        require(valid.data().get("schema").getAsString().equals("mapartrunner-grounded-two-lane-v1"),
                "Detached data changed immutable definition");
        SingleLaneFixture.verifyCoordinateLookup();
        System.out.println("ScenarioDefinition self-checks passed; definitionSha256=" + valid.definitionHash);
    }

    private static void validate(JsonObject d, byte[] source) {
        finite(d);
        require(d.get("schema").getAsString().equals("mapartrunner-grounded-two-lane-v1"), "Unsupported definition schema");
        for (String section : new String[]{"world","schematic","player","settings","physics","expectedResult","budgets","provenance"})
            require(d.has(section) && d.get(section).isJsonObject(), "Missing definition section: " + section);
        var world = d.getAsJsonObject("world");
        require(integer(world,"seed") == 1 && world.get("dimension").getAsString().equals("minecraft:overworld")
                && world.get("generator").getAsString().equals("flat"), "Unsupported world definition");
        var region = world.getAsJsonObject("ownedRegion");
        var min = position(region.getAsJsonObject("min")); var max = position(region.getAsJsonObject("max"));
        require(min.x() <= max.x() && min.y() <= max.y() && min.z() <= max.z(), "Inverted owned region");
        require(min.equals(new GridPos(-2,64,-2)) && max.equals(new GridPos(13,70,11)), "Unapproved owned region");
        var seen = new HashSet<GridPos>();
        for (var entry : world.getAsJsonArray("blocks")) {
            var cell = entry.getAsJsonObject(); var p = position(cell.getAsJsonObject("position"));
            require(p.x() >= min.x() && p.x() <= max.x() && p.y() >= min.y() && p.y() <= max.y()
                    && p.z() >= min.z() && p.z() <= max.z(), "Block outside owned region: " + p);
            require(seen.add(p), "Duplicate owned block coordinate: " + p);
            block(cell.getAsJsonObject("block"));
        }
        require(seen.size() == 16 * 7 * 14, "Missing owned block coordinates");
        require(world.getAsJsonArray("containers").isEmpty(), "Containers are unsupported in this fixture");
        require(world.getAsJsonObject("entities").get("initialBoundedNonPlayer").getAsString().equals("absent")
                && world.getAsJsonObject("entities").get("removable").getAsString().equals("reset-owned-dropped-items-only"),
                "Unsupported entity ownership policy");
        var schematic = d.getAsJsonObject("schematic");
        require(schematic.get("resource").getAsString().equals(RESOURCE + "grounded-two-lane.schem"), "Unapproved source resource");
        require(dimensions(schematic).equals(new GridSize(12,1,10)), "Source dimensions must be 12x1x10");
        require(position(schematic.getAsJsonObject("origin")).equals(new GridPos(0,65,0)), "Unapproved source origin");
        require(sha(source).equals(schematic.get("rawSourceSha256").getAsString()), "Source SHA-256 mismatch");
        require(schematic.get("normalizedFingerprint").getAsString().matches("[0-9a-f]{64}"), "Invalid semantic fingerprint");
        seen.clear();
        for (var entry : schematic.getAsJsonArray("expectedTargets")) {
            var target = entry.getAsJsonObject(); var p = position(target.getAsJsonObject("position"));
            require(p.x() >= 0 && p.x() < 12 && p.y() == 0 && p.z() >= 0 && p.z() < 10, "Expected coordinate outside source: " + p);
            require(seen.add(p), "Duplicate expected coordinate: " + p);
            require(block(target.getAsJsonObject("block")).equals(new BlockSpec("minecraft:stone", Map.of())),
                    "Expected target must be stone: " + p);
        }
        require(seen.size() == 120, "Absent expected coordinates");
        var player = d.getAsJsonObject("player");
        var slots = new HashSet<Integer>();
        for (var entry : player.getAsJsonArray("inventory")) {
            var slot = entry.getAsJsonObject(); int id = integer(slot, "slot"), count = integer(slot, "count");
            require(id >= 0 && id <= 42 && slots.add(id), "Invalid/duplicate inventory slot: " + id);
            var item = new BlockSpec(slot.get("item").getAsString(), Map.of());
            require(count >= 0 && count <= 64 && (item.id().equals("minecraft:air") == (count == 0)), "Invalid inventory count/item: " + id);
            require(slot.get("reserved").getAsJsonPrimitive().isBoolean() && slot.get("reserved").getAsBoolean() == (id == 0),
                    "Invalid reservation: " + id);
        }
        require(slots.size() == 43, "Missing inventory slots (main, armor, offhand, body or saddle)");
        var playerScreen = player.getAsJsonObject("playerScreen");
        require(playerScreen != null && playerScreen.has("handler")
                && playerScreen.get("handler").getAsString().equals("player"), "Expected player screen handler");
        for (String field : new String[]{"cursorEmpty","craftingInputEmpty","craftingOutputEmpty"})
            require(playerScreen.has(field) && playerScreen.get(field).isJsonPrimitive()
                    && playerScreen.get(field).getAsJsonPrimitive().isBoolean() && playerScreen.get(field).getAsBoolean(),
                    "Expected empty player screen state: " + field);
        require(integer(player,"selectedSlot") >= 0 && integer(player,"selectedSlot") <= 8, "Invalid selected slot");
        for (String axis : new String[]{"x","y","z"}) number(player.getAsJsonObject("position"), axis);
        for (String angle : new String[]{"yaw","headYaw","bodyYaw","pitch"}) number(player.getAsJsonObject("rotation"), angle);
        for (String field : new String[]{"health","hunger","saturation","exhaustion"}) require(number(player,field) >= 0, "Negative player " + field);
        var settings = d.getAsJsonObject("settings");
        new BuildEngine.Settings(integer(settings,"halfWidth"),integer(settings,"stride"),
                settings.get("constantSprint").getAsBoolean(),integer(settings,"reservedHotbarSlots"));
        var physics = d.getAsJsonObject("physics");
        require(physics.get("formula").getAsString().equals("(0.0D-0.08D)*(double)0.98F")
                && Double.doubleToRawLongBits((0.0D-0.08D)*(double)0.98F) == 0xBFB41205C28F5C29L,
                "Wrong pinned ordinary-physics formula");
        require(!physics.get("forceVelocityDuringReadiness").getAsBoolean(), "Readiness must use ordinary physics");
        require(integer(physics,"consecutiveCheckpoints") == 2, "Require two per-side physics checkpoints");
        for (String side : new String[]{"client","server"}) {
            var contract = physics.getAsJsonObject(side); var velocity = contract.getAsJsonObject("expectedVelocity");
            require(number(velocity,"x") == 0.0 && number(velocity,"z") == 0.0
                    && Double.doubleToRawLongBits(number(velocity,"y")) == 0xBFB41205C28F5C29L,
                    "Unsupported " + side + " velocity contract");
            require(contract.get("yRawDoubleBits").getAsString().equals("0xBFB41205C28F5C29"), "Wrong velocity bit provenance");
            var guards = contract.getAsJsonObject("guards");
            require(guards.get("supportBlock").getAsString().equals("minecraft:stone"), "Unsupported physics support");
            for (String flag : new String[]{"loadedChunk","onGround","noGravity","noDrag","levitation","slowFalling",
                    "otherMotionModifier","vehicle","touchingWater","inLava","climbing","gliding","flying","jumping","screenOpen","simulationPaused"})
                require(guards.has(flag) && guards.get(flag).isJsonPrimitive() && guards.get(flag).getAsJsonPrimitive().isBoolean()
                        && guards.get(flag).getAsBoolean() == (flag.equals("loadedChunk") || flag.equals("onGround")),
                        "Unsupported " + side + " physics guard: " + flag);
            require(number(guards,"gravityAttribute") == 0.08 && number(guards,"effectiveGravity") == 0.08,
                    "Unsupported " + side + " gravity contract");
        }
        var budgets = d.getAsJsonObject("budgets");
        for (String budget : new String[]{"readinessTicks","runTicks","transitionTicks","releaseTicks","repeatMaximum"})
            require(integer(budgets,budget) > 0, "Invalid budget: " + budget);
    }

    private static GridSize dimensions(JsonObject schematic) {
        var d = schematic.getAsJsonObject("dimensions");
        return new GridSize(integer(d,"width"),integer(d,"height"),integer(d,"depth"));
    }

    private static GridPos position(JsonObject p) { return new GridPos(integer(p,"x"),integer(p,"y"),integer(p,"z")); }

    private static BlockSpec block(JsonObject b) {
        var properties = new TreeMap<String,String>();
        b.getAsJsonObject("properties").entrySet().forEach(e -> {
            require(e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isString(), "Block property must be text");
            properties.put(e.getKey(),e.getValue().getAsString());
        });
        return new BlockSpec(b.get("id").getAsString(),properties);
    }

    private static int integer(JsonObject o, String key) {
        number(o,key);
        try { return o.get(key).getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Expected exact integer: " + key,e); }
    }

    private static double number(JsonObject o, String key) {
        var value = o.get(key);
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                && Double.isFinite(value.getAsDouble()), "Expected finite number: " + key);
        return value.getAsDouble();
    }

    private static void finite(JsonElement value) {
        if (value.isJsonObject()) value.getAsJsonObject().entrySet().forEach(e -> finite(e.getValue()));
        else if (value.isJsonArray()) value.getAsJsonArray().forEach(ScenarioDefinition::finite);
        else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())
            require(Double.isFinite(value.getAsDouble()), "Nonfinite definition value: " + value);
    }

    private static String canonical(JsonObject data) {
        var copy = data.deepCopy();
        sort(copy.getAsJsonObject("world"),"blocks",POSITION_ORDER);
        sort(copy.getAsJsonObject("schematic"),"expectedTargets",POSITION_ORDER);
        sort(copy.getAsJsonObject("player"),"inventory",Comparator.comparingInt(e -> integer(e.getAsJsonObject(),"slot")));
        return sortedKeys(copy).toString();
    }

    private static void sort(JsonObject parent, String name, Comparator<JsonElement> order) {
        var entries = new ArrayList<JsonElement>(); parent.getAsJsonArray(name).forEach(entries::add);
        entries.sort(order); var array = new JsonArray(); entries.forEach(array::add); parent.add(name,array);
    }

    private static JsonElement sortedKeys(JsonElement value) {
        if (value.isJsonObject()) {
            var result = new JsonObject();
            new TreeMap<>(value.getAsJsonObject().asMap()).forEach((key,element) -> result.add(key,sortedKeys(element)));
            return result;
        }
        if (value.isJsonArray()) { var result = new JsonArray(); value.getAsJsonArray().forEach(e -> result.add(sortedKeys(e))); return result; }
        return value.deepCopy();
    }

    private static void rejected(ScenarioDefinition valid, String label, Consumer<JsonObject> mutation) {
        var changed = valid.data(); mutation.accept(changed);
        try { new ScenarioDefinition(changed,valid.sourceBytes); }
        catch (IllegalArgumentException e) { System.out.println("ScenarioDefinition EXPECTED REJECTION " + label + ": " + e.getMessage()); return; }
        throw new AssertionError("Definition accepted " + label);
    }

    private static void hashChanges(ScenarioDefinition valid, String label, Consumer<JsonObject> mutation) {
        var changed = valid.data(); mutation.accept(changed);
        require(!valid.definitionHash.equals(sha(canonical(changed).getBytes(StandardCharsets.UTF_8))), "Hash omitted " + label);
    }

    private static byte[] resource(String name) throws IOException {
        try (var input = ScenarioDefinition.class.getResourceAsStream(RESOURCE + name)) {
            if (input == null) throw new IOException("Missing fixture resource: " + RESOURCE + name);
            return input.readAllBytes();
        }
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static void require(boolean condition, String detail) { if (!condition) throw new IllegalArgumentException(detail); }
}
