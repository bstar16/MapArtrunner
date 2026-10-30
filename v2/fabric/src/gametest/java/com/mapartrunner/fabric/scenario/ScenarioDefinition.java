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
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Immutable admitted fixture data; no world access or restoration effects. */
final class ScenarioDefinition {
    enum Fixture {
        TWO_LANE("grounded-two-lane",12,10,120,9,
                "81fad1bb9c4e356c941455f0cd50e7fd6a82dcc4f56e22d0740a1ec670337127",
                "0e31805276e369b7d05be4cd5a05d3275d32f3e01ee7676f64c67d96043d8f92",
                "730c69f9849b886dc5c456fa394594e63477ecc796c366a202fa59d2dc41a436"),
        RESUME_21("traversal-resume-21",3,105,315,126,
                "f27205a41ef3eb73aa7aa8cdbf2fa6d2243b5e99f06c9fcd45e322b83ed745ea",
                "986faf642fe529e82ddd9ed35242b57f341c1f1904e6bbd861c427f69e66d6e1",
                "781529ba3f528c8f3ab91722d2abd91b72959bc051a9294b829d5eb5665b90d5"),
        REVERSE_ODD("traversal-reverse-odd",12,15,180,126,
                "e87c400b443e7e55b87e9aa5b04d44b34dcb27eeeefb8def1e4ece43f2b30bfc",
                "b64b033937d160bc2385840b851a82c0a3fe3dd66ff68bcfc1965ed4f3039c48",
                "8a70a76a79075bc381eb3f2bb479b939027398e62599468c07e3e91f85ee0cc6"),
        REVERSE_EVEN("traversal-reverse-even",12,20,240,126,
                "09ead26cc29971b81d9d536a5122c66cf2eef393a629a8f654e8307ac5796fe2",
                "536f67248eb87506353a3e2486126936f5b4ba5140e733fcb806ea59a7193f3b",
                "4c18b40851d6f7becfa4e9e88710dbf5c57f774706a897b56293930417dc1db4"),
        REVERSE_MULTI("traversal-reverse-multi",12,25,300,125,
                "4967ac9114bfee36543c55d23143f7efc4ddbd40ab0bee1ab23886f7c8cfc6aa",
                "ca01e60b829e80a587434fad62403bd6203d51404488d72882daffb0365cc9b5",
                "e0e80afb1551f037b806bf84db3917a155627db2b33a0febdcd142d1515b0151"),
        NARROW("traversal-narrow",1,3,3,127,
                "642e032ffda138fb23fb5b9ee65107c370e93e0142138199d5b3d6a61e5167b2",
                "54460ffd734780b3ae2f9af09b31c4f9cacd54d8b2ed42f44c442403f2535f04",
                "4957b1d3621ce8e33bcabeb996aa5c4f9901f8dd7c9fce455dfdef151b9ac751"),
        SPARSE_PARTIAL("traversal-sparse-partial",3,11,4,125,
                "913bf18d73cb4fbeb77f4301d171d4ed9bc858cab643cf89445020f586d28dbc",
                "30d673421819829ae6528f1585b71d2f06711a75b7a0605842d28d8235c10ff2",
                "d8623a8d4e5f93233054827d9ce591853cedb7c36c3759673c4330147f5890af"),
        SCALE_128("traversal-scale-128",128,128,16384,100,
                "62646be30be46a1dee74b68a5c7c78a0da19f44cb096ee868cb5031f539598a1",
                "2323eb38294fc2b357d6cbd8238e51bc10f77a06b4ce20f37d5dd9c697cc93f5",
                "e3df3b474e658d012264b501e5afffb8c30af42b5b73dfc1d586aad83ac778ff");

        final String directory, canonicalHash, sourceHash, fingerprint;
        final int width, depth, targetCount, finalUsable;
        Fixture(String directory,int width,int depth,int targetCount,int finalUsable,
                String canonicalHash,String sourceHash,String fingerprint) {
            this.directory=directory; this.width=width; this.depth=depth; this.targetCount=targetCount;
            this.finalUsable=finalUsable; this.canonicalHash=canonicalHash; this.sourceHash=sourceHash; this.fingerprint=fingerprint;
        }
        GridSize dimensions() { return new GridSize(width,1,depth); }
        GridPos min() { return new GridPos(-2,64,-2); }
        GridPos max() { return new GridPos(width+1,70,depth+1); }
        String directory() { return "/"+directory+"/"; }
        String sourceName() { return directory+".schem"; }
        Set<GridPos> removals() {
            return switch(this) {
                case REVERSE_ODD, REVERSE_EVEN -> Set.of(new GridPos(6,0,5));
                case REVERSE_MULTI -> Set.of(new GridPos(6,0,5),new GridPos(6,0,15));
                default -> Set.of();
            };
        }
        Map<GridPos,BlockSpec> targets() {
            var result=new HashMap<GridPos,BlockSpec>(); var stone=new BlockSpec("minecraft:stone",Map.of());
            if(this==SPARSE_PARTIAL) {
                for(var p:List.of(new GridPos(0,0,0),new GridPos(2,0,4),new GridPos(2,0,8),new GridPos(1,0,10))) result.put(p,stone);
            } else for(int x=0;x<width;x++) for(int z=0;z<depth;z++) result.put(new GridPos(x,0,z),stone);
            return Map.copyOf(result);
        }
    }

    private static final Comparator<JsonElement> POSITION_ORDER = Comparator
            .comparingInt((JsonElement e) -> position(e.getAsJsonObject().getAsJsonObject("position")).x())
            .thenComparingInt(e -> position(e.getAsJsonObject().getAsJsonObject("position")).y())
            .thenComparingInt(e -> position(e.getAsJsonObject().getAsJsonObject("position")).z());
    private final Fixture fixture;
    private final String canonicalPlayer;
    private final GridPos origin;
    private final Map<GridPos,BlockSpec> worldCells;
    private final Map<GridPos,BlockSpec> expectedTargets;
    private final int initiallyAbsentStoneTargets;
    private final int initialUsable;
    private final String canonicalDefinition;
    private final String definitionHash;
    private final byte[] sourceBytes;

    private ScenarioDefinition(JsonObject data, byte[] source) { this(Fixture.TWO_LANE,data,source); }

    private ScenarioDefinition(Fixture fixture,JsonObject data,byte[] source) {
        this.fixture=java.util.Objects.requireNonNull(fixture);
        validate(fixture,data, source);
        canonicalDefinition = canonical(data);
        definitionHash = sha(canonicalDefinition.getBytes(StandardCharsets.UTF_8));
        require(definitionHash.equals(fixture.canonicalHash),"Unreviewed canonical definition for "+fixture+": "+definitionHash);
        sourceBytes = source.clone();
        canonicalPlayer=data.getAsJsonObject("player").toString();
        int usable=0;
        for(var value:data.getAsJsonObject("player").getAsJsonArray("inventory")) {
            var slot=value.getAsJsonObject(); int id=integer(slot,"slot");
            if(id==1 || id==2) {
                require(slot.get("item").getAsString().equals("minecraft:stone"),"Usable fixture stock must be stone");
                usable+=integer(slot,"count");
            }
            if(id==0) require(integer(slot,"count")==7 && slot.get("item").getAsString().equals("minecraft:stone"),"Reserved fixture stone must remain7");
        }
        initialUsable=usable;
        require(initialUsable==128,"Closed fixtures require initial usable128");
        worldCells=cells(data.getAsJsonObject("world").getAsJsonArray("blocks"));
        expectedTargets=cells(data.getAsJsonObject("schematic").getAsJsonArray("expectedTargets"));
        origin=position(data.getAsJsonObject("schematic").getAsJsonObject("origin"));
        int absent=0;
        for(var entry:expectedTargets.entrySet()) {
            var p=entry.getKey(); var at=new GridPos(origin.x()+p.x(),origin.y()+p.y(),origin.z()+p.z());
            require(worldCells.containsKey(at),"Expected target absent from owned region: "+at);
            if(!worldCells.get(at).equals(entry.getValue())) absent++;
        }
        initiallyAbsentStoneTargets=absent;
        for(var p:removalTargets()) {
            var at=new GridPos(origin.x()+p.x(),origin.y()+p.y(),origin.z()+p.z());
            require(expectedTargets.containsKey(p) && expectedTargets.get(p).equals(worldCells.get(at)),
                    "Declared removal must name an initially correct required target: "+p);
        }
        require(finalUsable()==fixture.finalUsable,"Wrong closed fixture stock delta");
        var result=data.getAsJsonObject("expectedResult");
        require(integer(result,"targets")==expectedTargets.size() && integer(result,"seededTargets")==expectedTargets.size()-absent
                && integer(result,"placements")==absent+removalTargets().size()
                && integer(result,"usableRemainder")==finalUsable() && integer(result,"reservedRemainder")==7,
                "Wrong expected inventory/target accounting");
    }

    static ScenarioDefinition load() { return load(Fixture.TWO_LANE); }

    static ScenarioDefinition load(Fixture fixture) {
        try {
            var data=JsonParser.parseString(new String(resource(fixture,"definition.json"),StandardCharsets.UTF_8)).getAsJsonObject();
            return new ScenarioDefinition(fixture,data,resource(fixture,fixture.sourceName()));
        } catch(IOException e) { throw new UncheckedIOException("Cannot load fixture "+fixture,e); }
    }

    Fixture fixture() { return fixture; }
    GridPos ownedMin() { return fixture.min(); }
    GridPos ownedMax() { return fixture.max(); }
    String resourceDirectory() { return fixture.directory(); }
    Map<GridPos,BlockSpec> worldCells() { return worldCells; }
    Map<GridPos,BlockSpec> expectedTargets() { return expectedTargets; }
    JsonObject playerData() { return JsonParser.parseString(canonicalPlayer).getAsJsonObject(); }
    GridPos origin() { return origin; }
    Set<GridPos> removalTargets() { return fixture.removals(); }
    int initiallyAbsentStoneTargets() { return initiallyAbsentStoneTargets; }
    int initialUsable() { return initialUsable; }
    int finalUsable() { return initialUsable-(initiallyAbsentStoneTargets+removalTargets().size()); }
    boolean sourceMatches(byte[] source) { return Arrays.equals(sourceBytes,source); }

    void requireValues(Fixture selected,String hash,JsonObject values) {
        require(fixture==selected && definitionHash.equals(hash) && data().equals(values),"Wrong fixture/hash/definition values");
    }

    void requireSame(ScenarioDefinition candidate) {
        require(fixture==candidate.fixture && canonicalDefinition.equals(candidate.canonicalDefinition)
                && Arrays.equals(sourceBytes,candidate.sourceBytes),"Changed admitted fixture/definition/source");
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
            var imported=new HashMap<GridPos,BlockSpec>();
            for(var target:plan.targets()) require(imported.put(target.relativePos(),target.block())==null,"Duplicate imported target coordinate");
            require(imported.equals(expectedTargets),"Imported full-state targets differ from admitted definition");
            return plan;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot retain/import admitted fixture at " + retainedCopy, e);
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

    private static void validate(Fixture fixture,JsonObject d, byte[] source) {
        finite(d);
        require(d.get("schema").getAsString().equals("mapartrunner-"+fixture.directory+"-v1"), "Unsupported definition schema");
        for (String section : new String[]{"world","schematic","player","settings","physics","expectedResult","budgets","provenance"})
            require(d.has(section) && d.get(section).isJsonObject(), "Missing definition section: " + section);
        var world = d.getAsJsonObject("world");
        require(integer(world,"seed") == 1 && world.get("dimension").getAsString().equals("minecraft:overworld")
                && world.get("generator").getAsString().equals("flat"), "Unsupported world definition");
        var region = world.getAsJsonObject("ownedRegion");
        var min = position(region.getAsJsonObject("min")); var max = position(region.getAsJsonObject("max"));
        require(min.x() <= max.x() && min.y() <= max.y() && min.z() <= max.z(), "Inverted owned region");
        require(min.equals(fixture.min()) && max.equals(fixture.max()), "Unapproved owned region");
        var seen = new HashSet<GridPos>();
        for (var entry : world.getAsJsonArray("blocks")) {
            var cell = entry.getAsJsonObject(); var p = position(cell.getAsJsonObject("position"));
            require(p.x() >= min.x() && p.x() <= max.x() && p.y() >= min.y() && p.y() <= max.y()
                    && p.z() >= min.z() && p.z() <= max.z(), "Block outside owned region: " + p);
            require(seen.add(p), "Duplicate owned block coordinate: " + p);
            block(cell.getAsJsonObject("block"));
        }
        require(seen.size() == (long)(max.x()-min.x()+1)*(max.y()-min.y()+1)*(max.z()-min.z()+1), "Missing owned block coordinates");
        require(world.getAsJsonArray("containers").isEmpty(), "Containers are unsupported in this fixture");
        require(world.getAsJsonObject("entities").get("initialBoundedNonPlayer").getAsString().equals("absent")
                && world.getAsJsonObject("entities").get("removable").getAsString().equals("reset-owned-dropped-items-only"),
                "Unsupported entity ownership policy");
        var schematic = d.getAsJsonObject("schematic");
        require(schematic.get("resource").getAsString().equals(fixture.directory()+fixture.sourceName()), "Unapproved source resource");
        require(dimensions(schematic).equals(fixture.dimensions()), "Source dimensions differ from closed fixture");
        require(position(schematic.getAsJsonObject("origin")).equals(new GridPos(0,65,0)), "Unapproved source origin");
        require(sha(source).equals(fixture.sourceHash) && sha(source).equals(schematic.get("rawSourceSha256").getAsString()), "Source SHA-256 mismatch");
        require(schematic.get("normalizedFingerprint").getAsString().equals(fixture.fingerprint), "Invalid semantic fingerprint");
        seen.clear();
        for (var entry : schematic.getAsJsonArray("expectedTargets")) {
            var target = entry.getAsJsonObject(); var p = position(target.getAsJsonObject("position"));
            require(p.x() >= 0 && p.x() < fixture.width && p.y() == 0 && p.z() >= 0 && p.z() < fixture.depth, "Expected coordinate outside source: " + p);
            require(seen.add(p), "Duplicate expected coordinate: " + p);
            require(block(target.getAsJsonObject("block")).equals(new BlockSpec("minecraft:stone", Map.of())),
                    "Expected target must be stone: " + p);
        }
        require(seen.size() == fixture.targetCount && cells(schematic.getAsJsonArray("expectedTargets")).equals(fixture.targets()), "Absent/changed expected coordinates or full states");
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
        require(integer(budgets,"runTicks")== (fixture==Fixture.SCALE_128?109468:1200)
                && integer(budgets,"releaseTicks")==20,"Wrong fixture physics budget");
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

    private static byte[] resource(Fixture fixture,String name) throws IOException {
        try (var input = ScenarioDefinition.class.getResourceAsStream(fixture.directory() + name)) {
            if (input == null) throw new IOException("Missing fixture resource: " + fixture.directory() + name);
            return input.readAllBytes();
        }
    }

    private static Map<GridPos,BlockSpec> cells(JsonArray values) {
        var result=new HashMap<GridPos,BlockSpec>();
        for(var value:values) {
            var cell=value.getAsJsonObject(); var p=position(cell.getAsJsonObject("position"));
            require(result.put(p,block(cell.getAsJsonObject("block")))==null,"Duplicate full-state cell: "+p);
        }
        return Map.copyOf(result);
    }

    static void verifyFixtureGuards() {
        var baseline=load(); var mutations=new int[1];
        for(var fixture:Fixture.values()) {
            var admitted=load(fixture);
            require(admitted.definitionHash.equals(fixture.canonicalHash),"Reviewed canonicalizer receipt differs: "+fixture);
            System.out.println("ScenarioDefinition admitted receipt: fixture="+fixture+"; canonical="+admitted.definitionHash
                    +"; source="+sha(admitted.sourceBytes)+"; fingerprint="+fixture.fingerprint);
        }
        refusedFixture("wrong fixture with copied hash",() -> baseline.requireValues(Fixture.NARROW,baseline.definitionHash,baseline.data()),mutations);
        var changed=baseline.sourceBytes.clone(); changed[0]^=1;
        refusedFixture("changed source bytes",() -> new ScenarioDefinition(Fixture.TWO_LANE,baseline.data(),changed),mutations);
        var wrongRegion=baseline.data(); wrongRegion.getAsJsonObject("world").getAsJsonObject("ownedRegion")
                .getAsJsonObject("max").addProperty("x",14);
        refusedFixture("changed admitted region",() -> baseline.requireValues(Fixture.TWO_LANE,baseline.definitionHash,wrongRegion),mutations);
        var missing=baseline.data(); missing.getAsJsonObject("schematic").getAsJsonArray("expectedTargets").remove(0);
        refusedFixture("missing expected target",() -> new ScenarioDefinition(Fixture.TWO_LANE,missing,baseline.sourceBytes),mutations);
        refusedFixture("changed definition during reset",() -> baseline.requireSame(load(Fixture.NARROW)),mutations);
        baseline.requireSame(load()); mutations[0]++;
        require(mutations[0]==1,"Fixture refusal mutated or positive control did not execute");
    }

    private static void refusedFixture(String name,Runnable check,int[] mutations) {
        try { check.run(); } catch(RuntimeException | AssertionError expected) {
            require(mutations[0]==0,"Refused fixture reached mutation callback");
            System.out.println("ScenarioDefinition expected refusal ["+name+"]: "+expected); return;
        }
        mutations[0]++; throw new AssertionError("Expected fixture refusal: "+name);
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static void require(boolean condition, String detail) { if (!condition) throw new IllegalArgumentException(detail); }
}
