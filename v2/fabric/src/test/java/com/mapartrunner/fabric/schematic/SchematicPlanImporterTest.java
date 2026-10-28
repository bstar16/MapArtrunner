package com.mapartrunner.fabric.schematic;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.Items;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

class SchematicPlanImporterTest {
    @TempDir Path temporary;
    private int fileNumber;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @Test
    void importsCheckedInSponge() throws Exception {
        // Authored offline on 2026-10-28 with Python 3.14 stdlib struct/gzip (mtime=0).
        // Actual big-endian Sponge-v2-shaped gzip NBT: SHORT 12x1x5, palette stone=0,
        // 60 zero byte-varints, X fastest then Z then Y; no exporter or game run claimed.
        var source = copyFixture("lane.SCHEM");
        assertEquals("7c194b89faf8339bf03b20b122b7b3b17f22064f1fb4e4139e1781bb4664ce0a",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))));
        var plan = SchematicPlanImporter.importPlan(source);
        assertEquals(new GridSize(12, 1, 5), plan.dimensions());
        assertEquals(60, plan.targets().size());
        int[] ids = {0, 11, 12, 23, 24, 48, 59};
        var positions = List.of(new GridPos(0, 0, 0), new GridPos(11, 0, 0), new GridPos(11, 0, 1),
                new GridPos(0, 0, 1), new GridPos(0, 0, 2), new GridPos(0, 0, 4), new GridPos(11, 0, 4));
        for (int i = 0; i < ids.length; i++) assertEquals(positions.get(i), plan.targets().get(ids[i]).relativePos());
        for (int i = 0; i < 60; i++) {
            assertEquals(i, plan.targets().get(i).id());
            assertEquals(new BlockSpec("minecraft:stone", Map.of()), plan.targets().get(i).block());
        }
        assertThrows(UnsupportedOperationException.class, () -> plan.targets().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.targets().getFirst().block().properties().put("x", "y"));
        assertTrue(plan.fingerprint().matches("[0-9a-f]{64}"));
    }

    @Test
    void importsCheckedInStructure() throws Exception {
        // Authored offline on 2026-10-28 with Python 3.14 stdlib struct/gzip (mtime=0).
        // Real 174-byte gzip NBT: INT-list 18x3x5, palette log[axis=x]=0/stone=1;
        // record order (3,1,4),(17,1,0),(1,1,2),(0,1,0),(16,1,2), empty entities.
        var source = copyFixture("flat-asymmetric.nbt", "asymmetric.NBT");
        assertEquals("5cf68ed66fa3f0062bb7996961a86423668ecc11896d762dcd43fc8e9f5bdec6",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))));
        var plan = SchematicPlanImporter.importPlan(source);
        assertEquals(new GridSize(18, 3, 5), plan.dimensions());
        assertEquals(List.of(new GridPos(0, 1, 0), new GridPos(17, 1, 0), new GridPos(16, 1, 2),
                new GridPos(1, 1, 2), new GridPos(3, 1, 4)), plan.targets().stream().map(Target::relativePos).toList());
        var stone = new BlockSpec("minecraft:stone", Map.of());
        var log = new BlockSpec("minecraft:oak_log", Map.of("axis", "x"));
        assertEquals(List.of(stone, log, log, stone, stone), plan.targets().stream().map(Target::block).toList());
        assertEquals(List.of(0, 1, 2, 3, 4), plan.targets().stream().map(Target::id).toList());
        assertEquals(Map.of(Items.STONE, 3L, Items.OAK_LOG, 2L), plan.targets().stream().collect(groupingBy(
                target -> Registries.BLOCK.getOptionalValue(Identifier.of(target.block().id())).orElseThrow().asItem(), counting())));
        assertEquals(Map.of(List.of(0, 0), 3L, List.of(1, 0), 2L), plan.targets().stream().collect(groupingBy(
                target -> List.of(target.relativePos().x() >> 4, target.relativePos().z() >> 4), counting())));
    }

    @Test
    void structureAndSpongeHaveSameNormalizedPlan() throws Exception {
        var original = NbtIo.readCompressed(copyFixture("flat-asymmetric.nbt", "original.nbt"), NbtSizeTracker.forLevel());
        var expected = read(original);
        var sponge = schematic(18, 3, 5, "minecraft:air", new byte[270]);
        var palette = sponge.getCompound("Palette").orElseThrow();
        palette.putInt("minecraft:stone", 1);
        palette.putInt("minecraft:oak_log[axis=x]", 2);
        byte[] data = new byte[270];
        data[90] = 1;
        data[107] = 2;
        data[127] = 1;
        data[142] = 2;
        data[165] = 1;
        sponge.putByteArray("BlockData", data);
        assertEquals(expected, read(sponge));
        var alternate = original.copy();
        var entries = alternate.getList("palette").orElseThrow();
        Collections.reverse(entries);
        var records = alternate.getList("blocks").orElseThrow();
        Collections.reverse(records);
        for (var element : records) {
            var block = (NbtCompound) element;
            block.putInt("state", 1 - block.getInt("state").orElseThrow());
        }
        alternate.putInt("DataVersion", Integer.MAX_VALUE);
        alternate.putIntArray("Offset", new int[]{90, -20, 5});
        alternate.putString("author", "ignored producer metadata");
        var wrapper = new NbtCompound();
        wrapper.put("Schematic", alternate);
        for (String suffix : List.of(".schem", ".SCHEM.NBT", ".NbT")) {
            assertEquals(expected, SchematicPlanImporter.importPlan(write(wrapper, suffix)));
        }
        var changed = original.copy();
        changed.getList("palette").orElseThrow().getCompound(0).orElseThrow()
                .getCompound("Properties").orElseThrow().putString("axis", "z");
        assertNotEquals(expected.fingerprint(), read(changed).fingerprint());
        changed = original.copy();
        changed.getList("blocks").orElseThrow().getCompound(0).orElseThrow().put("pos", intList(4, 1, 4));
        assertNotEquals(expected.fingerprint(), read(changed).fingerprint());
        changed = original.copy();
        changed.put("size", intList(19, 3, 5));
        assertNotEquals(expected.fingerprint(), read(changed).fingerprint());
        changed = original.copy();
        changed.getList("palette").orElseThrow().getCompound(1).orElseThrow().putString("Name", "minecraft:dirt");
        assertNotEquals(expected.fingerprint(), read(changed).fingerprint());
    }

    @Test
    void structurePreservesNativeDefaultsPropertiesItemsAndAir() throws Exception {
        var stairs = structure(1, 1, 1, "minecraft:oak_stairs");
        var expected = read(schematic(1, 1, 1, "minecraft:oak_stairs", new byte[]{0}));
        assertEquals(expected, read(stairs));
        var properties = new NbtCompound();
        properties.putString("waterlogged", "false");
        properties.putString("shape", "straight");
        properties.putString("half", "bottom");
        properties.putString("facing", "north");
        stairs.getList("palette").orElseThrow().getCompound(0).orElseThrow().put("Properties", properties);
        assertEquals(expected, read(stairs));
        var reversed = new NbtCompound();
        reversed.putString("facing", "north");
        reversed.putString("half", "bottom");
        reversed.putString("shape", "straight");
        reversed.putString("waterlogged", "false");
        stairs.getList("palette").orElseThrow().getCompound(0).orElseThrow().put("Properties", reversed);
        assertEquals(expected, read(stairs));
        var wallSign = read(structure(1, 1, 1, "minecraft:oak_wall_sign")).targets().getFirst().block();
        assertEquals("minecraft:oak_wall_sign", wallSign.id());
        var item = Registries.BLOCK.getOptionalValue(Identifier.of(wallSign.id())).orElseThrow().asItem();
        assertEquals("minecraft:oak_sign", Registries.ITEM.getId(item).toString());
        assertEquals(1, read(structure(1, 1, 1, "minecraft:chest")).targets().size());
        for (String air : List.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air")) {
            var empty = structure(1, 2, 1, air);
            empty.put("entities", new NbtList());
            var record = empty.getList("blocks").orElseThrow().getCompound(0).orElseThrow();
            record.put("nbt", new NbtCompound());
            empty.getList("blocks").orElseThrow().add(structureBlock(0, 0, 1, 0));
            var plan = read(empty);
            assertEquals(new GridSize(1, 2, 1), plan.dimensions());
            assertTrue(plan.targets().isEmpty());
            record.putString("nbt", "wrong even on air");
            rejects(empty, "blocks[0].nbt");
            record.put("nbt", new NbtCompound());
            record.getCompound("nbt").orElseThrow().putInt("x", 0);
            rejects(empty, "blocks[0].nbt");
        }
    }

    @Test
    void structureWorkIsProportionalToRecordsAndValidatesEmptyInput() throws Exception {
        var sparse = structure(Integer.MAX_VALUE, 1, 1, "minecraft:stone");
        sparse.getList("blocks").orElseThrow().getCompound(0).orElseThrow()
                .put("pos", intList(Integer.MAX_VALUE - 1, 0, 0));
        var plan = read(sparse);
        assertEquals(new GridSize(Integer.MAX_VALUE, 1, 1), plan.dimensions());
        assertEquals(new GridPos(Integer.MAX_VALUE - 1, 0, 0), plan.targets().getFirst().relativePos());
        sparse.put("blocks", new NbtList());
        assertTrue(read(sparse).targets().isEmpty());
        sparse.getList("palette").orElseThrow().getCompound(0).orElseThrow().putString("Name", "minecraft:not_real");
        rejects(sparse, "palette[0].Name");
    }

    @Test
    void rejectsMalformedStructure() throws Exception {
        for (String field : List.of("size", "palette", "blocks")) {
            rejectsStructure(nbt -> nbt.remove(field), field);
            rejectsStructure(nbt -> nbt.putString(field, "wrong"), field);
            rejectsStructure(nbt -> nbt.putIntArray(field, new int[]{1, 1, 1}), field);
        }
        for (String field : List.of("size", "pos")) {
            for (NbtElement bad : List.of(intList(1, 1), intList(1, 1, 1, 1), NbtString.of("1,1,1"),
                    new NbtIntArray(new int[]{1, 1, 1}), nbtList(NbtShort.of((short) 1), NbtInt.of(1), NbtInt.of(1)),
                    nbtList(NbtInt.of(1), NbtString.of("1"), NbtInt.of(1)))) {
                rejectsStructure(nbt -> {
                    if (field.equals("size")) nbt.put(field, bad);
                    else nbt.getList("blocks").orElseThrow().getCompound(0).orElseThrow().put(field, bad);
                }, field);
            }
        }
        for (int axis = 0; axis < 3; axis++) {
            for (int bad : List.of(0, -1)) {
                var size = intList(1, 1, 1);
                size.set(axis, NbtInt.of(bad));
                rejectsStructure(nbt -> nbt.put("size", size), "size");
            }
            for (int bad : List.of(-1, 1)) {
                var pos = intList(0, 0, 0);
                pos.set(axis, NbtInt.of(bad));
                rejectsStructure(nbt -> nbt.getList("blocks").orElseThrow().getCompound(0).orElseThrow().put("pos", pos), "blocks[0].pos");
            }
        }
        rejectsStructure(nbt -> nbt.put("size", intList(Integer.MAX_VALUE, 2, 1)), "volume");
        rejectsStructure(nbt -> nbt.put("palette", new NbtList()), "palette");
        for (String field : List.of("palette", "blocks")) {
            rejectsStructure(nbt -> nbt.put(field, nbtList(NbtInt.of(0))), field + "[0]");
        }
        rejectsStructure(nbt -> nbt.getList("palette").orElseThrow().getCompound(0).orElseThrow().remove("Name"), "palette[0].Name");
        rejectsStructure(nbt -> nbt.getList("palette").orElseThrow().getCompound(0).orElseThrow().putInt("Name", 0), "palette[0].Name");
        for (String name : List.of("minecraft:not_real", "minecraft:oak_log[axis=x]", "minecraft:stone garbage", "", "Minecraft:stone")) {
            rejectsStructure(nbt -> nbt.getList("palette").orElseThrow().add(structureState(name)), "palette[1].Name");
        }
        rejectsStructure(nbt -> nbt.getList("palette").orElseThrow().add(structureState("minecraft:water")), "palette[1]");
        for (NbtElement bad : List.of(NbtString.of("axis=x"), new NbtList(), NbtInt.of(0))) {
            rejectsStructure(nbt -> nbt.getList("palette").orElseThrow().getCompound(0).orElseThrow().put("Properties", bad), "Properties");
        }
        for (NbtElement value : List.of(NbtInt.of(1), NbtString.of("bad"), NbtString.of("x,axis=y"))) {
            var state = structureState("minecraft:oak_log");
            var properties = new NbtCompound();
            properties.put("axis", value);
            state.put("Properties", properties);
            rejectsStructure(nbt -> nbt.getList("palette").orElseThrow().add(state), "palette[1].Properties.axis");
        }
        var unknownProperty = structureState("minecraft:stone");
        var properties = new NbtCompound();
        properties.putString("axis", "x");
        unknownProperty.put("Properties", properties);
        rejectsStructure(nbt -> nbt.getList("palette").orElseThrow().add(unknownProperty), "palette[1].Properties.axis");
        for (String field : List.of("state", "pos")) {
            rejectsStructure(nbt -> nbt.getList("blocks").orElseThrow().getCompound(0).orElseThrow().remove(field), "blocks[0]." + field);
        }
        for (NbtElement state : List.of(NbtInt.of(-1), NbtInt.of(1), NbtShort.of((short) 0), NbtString.of("0"))) {
            rejectsStructure(nbt -> nbt.getList("blocks").orElseThrow().getCompound(0).orElseThrow().put("state", state), "blocks[0].state");
        }
        for (String air : List.of("minecraft:stone", "minecraft:air", "minecraft:cave_air", "minecraft:void_air")) {
            var duplicate = structure(1, 1, 1, air);
            duplicate.getList("blocks").orElseThrow().add(structureBlock(0, 0, 0, 0));
            rejects(duplicate, "blocks[1].pos");
            var outside = structure(1, 1, 1, air);
            outside.getList("blocks").orElseThrow().getCompound(0).orElseThrow().put("pos", intList(1, 0, 0));
            rejects(outside, "blocks[0].pos");
        }
        var mixedDuplicate = structure(1, 1, 1, "minecraft:stone");
        mixedDuplicate.getList("palette").orElseThrow().add(structureState("minecraft:air"));
        mixedDuplicate.getList("blocks").orElseThrow().add(structureBlock(1, 0, 0, 0));
        rejects(mixedDuplicate, "blocks[1].pos");
        var multipleY = structure(1, 2, 1, "minecraft:stone");
        multipleY.getList("blocks").orElseThrow().add(structureBlock(0, 0, 1, 0));
        rejects(multipleY, "blocks[1]");
        rejectsStructure(nbt -> nbt.put("palettes", new NbtList()), "palettes");
        rejectsStructure(nbt -> nbt.putInt("Width", 1), "ambiguous");
        for (NbtElement payload : List.of(new NbtCompound(), NbtString.of("wrong"),
                nbtList(new NbtCompound()), nbtList(NbtInt.of(0)))) {
            rejectsStructure(nbt -> nbt.put("entities", payload), "entities");
        }
        for (NbtElement payload : List.of(new NbtList(), NbtString.of("wrong"))) {
            rejectsStructure(nbt -> nbt.getList("blocks").orElseThrow().getCompound(0).orElseThrow().put("nbt", payload), "blocks[0].nbt");
        }
        var payload = new NbtCompound();
        payload.putString("id", "minecraft:chest");
        rejectsStructure(nbt -> nbt.getList("blocks").orElseThrow().getCompound(0).orElseThrow().put("nbt", payload), "blocks[0].nbt");
        var empty = structure(1, 3, 1, "minecraft:stone");
        empty.put("blocks", new NbtList());
        empty.putString("entities", "wrong even without blocks");
        rejects(empty, "entities");
    }

    @Test
    void normalizesEquivalentSponge() throws Exception {
        var original = schematic(2, 1, 2, "minecraft:oak_log", new byte[]{0, 0, 0, 0});
        var expected = read(original);
        var changed = original.copy();
        var palette = new NbtCompound();
        palette.putInt("minecraft:oak_log[axis=y]", 300);
        changed.put("Palette", palette);
        changed.putIntArray("BlockData", new int[]{300, 300, 300, 300});
        changed.putInt("Version", Integer.MAX_VALUE);
        changed.putString("Author", "ignored producer metadata");
        changed.putInt("Width", 2);
        changed.putIntArray("Offset", new int[]{100, -20, 300});
        changed.putInt("WEOffsetX", 40);
        var wrapper = new NbtCompound();
        wrapper.put("Schematic", changed);
        for (String suffix : List.of(".schem", ".SCHEM.NBT", ".NbT")) {
            assertEquals(expected, SchematicPlanImporter.importPlan(write(wrapper, suffix)));
        }
        var multibyte = changed.copy();
        multibyte.putByteArray("BlockData", new byte[]{(byte) 0xac, 2, (byte) 0xac, 2, (byte) 0xac, 2, (byte) 0xac, 2});
        assertEquals(expected, read(multibyte));
        var reordered = schematic(1, 1, 1,
                "minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]", new byte[]{0});
        var alternate = schematic(1, 1, 1,
                "minecraft:oak_stairs[waterlogged=false,shape=straight,half=top,facing=east]", new byte[]{0});
        assertEquals(read(reordered), read(alternate));
        var source = write(original, ".schem");
        var gzipChanged = Files.readAllBytes(source);
        gzipChanged[4] = 42; // gzip timestamp changes do not change normalized construction.
        Files.write(source, gzipChanged);
        assertEquals(expected, SchematicPlanImporter.importPlan(source));
        var mixed = schematic(2, 1, 1, "minecraft:oak_log[axis=x]", new byte[]{0, 1});
        mixed.getCompound("Palette").orElseThrow().putInt("minecraft:stone", 1);
        var renumbered = mixed.copy();
        var reversedPalette = new NbtCompound();
        reversedPalette.putInt("minecraft:stone", 42);
        reversedPalette.putInt("minecraft:oak_log[axis=x]", 7);
        renumbered.put("Palette", reversedPalette);
        renumbered.putIntArray("BlockData", new int[]{7, 42});
        assertEquals(read(mixed), read(renumbered));
    }

    @Test
    void fingerprintChangesWithDimensionsStateBlockAndCoordinate() throws Exception {
        var base = schematic(2, 1, 1, "minecraft:stone", new byte[]{0, 1});
        base.getCompound("Palette").orElseThrow().putInt("minecraft:air", 1);
        var expected = read(base);
        var coordinate = base.copy();
        coordinate.putByteArray("BlockData", new byte[]{1, 0});
        assertNotEquals(expected.fingerprint(), read(coordinate).fingerprint());
        var dimension = base.copy();
        dimension.putInt("Width", 1);
        dimension.putInt("Length", 2);
        assertNotEquals(expected.fingerprint(), read(dimension).fingerprint());
        assertNotEquals(read(schematic(1, 1, 1, "minecraft:stone", new byte[]{0})).fingerprint(),
                read(schematic(1, 1, 1, "minecraft:dirt", new byte[]{0})).fingerprint());
        assertNotEquals(read(schematic(1, 1, 1, "minecraft:oak_log[axis=x]", new byte[]{0})).fingerprint(),
                read(schematic(1, 1, 1, "minecraft:oak_log[axis=z]", new byte[]{0})).fingerprint());
    }

    @Test
    void rejectsMalformedSponge() throws Exception {
        for (String key : List.of("Width", "Height", "Length", "Palette", "BlockData")) {
            rejects(nbt -> nbt.remove(key), key);
        }
        rejects(nbt -> nbt.putString("Palette", "bad"), "Palette");
        rejects(nbt -> nbt.put("Palette", new NbtCompound()), "Palette");
        rejects(nbt -> nbt.putString("BlockData", "bad"), "BlockData");
        rejects(nbt -> nbt.putString("Schematic", "bad"), "Schematic");
        for (String key : List.of("Width", "Height", "Length")) {
            rejects(nbt -> nbt.putByte(key, (byte) 1), key);
            rejects(nbt -> nbt.putLong(key, 1), key);
            rejects(nbt -> nbt.putFloat(key, 1), key);
            rejects(nbt -> nbt.putString(key, "1"), key);
            rejects(nbt -> nbt.putInt(key, 0), key);
            rejects(nbt -> nbt.putShort(key, (short) -1), key);
        }
        rejects(nbt -> { nbt.putInt("Width", Integer.MAX_VALUE); nbt.putInt("Length", 2); }, "volume");
        rejects(nbt -> { nbt.putInt("Width", 65536); nbt.putInt("Height", 65536); nbt.putInt("Length", 65536); }, "volume");
        rejects(nbt -> nbt.putInt("Width", Integer.MAX_VALUE), "BlockData");
    }

    @Test
    void dimensionsAreNotCoercedOrTruncated() throws Exception {
        byte[] data = new byte[65537];
        var largeWidth = schematic(65537, 1, 1, "minecraft:air", data);
        assertEquals(new GridSize(65537, 1, 1), read(largeWidth).dimensions());
        var shorts = schematic(1, 1, 1, "minecraft:stone", new byte[]{0});
        shorts.putShort("Width", (short) 1);
        shorts.putShort("Height", (short) 1);
        shorts.putShort("Length", (short) 1);
        assertEquals(new GridSize(1, 1, 1), read(shorts).dimensions());
    }

    @Test
    void rejectsInvalidPaletteEvenWhenUnused() throws Exception {
        rejects(nbt -> nbt.getCompound("Palette").orElseThrow().putInt("minecraft:missing_block", 1), "Palette");
        rejects(nbt -> nbt.getCompound("Palette").orElseThrow().putInt("minecraft:dirt", 0), "Palette");
        rejects(nbt -> nbt.getCompound("Palette").orElseThrow().putInt("minecraft:dirt", -1), "Palette");
        rejects(nbt -> nbt.getCompound("Palette").orElseThrow().putShort("minecraft:dirt", (short) 1), "Palette");
        rejects(nbt -> nbt.getCompound("Palette").orElseThrow().putString("minecraft:dirt", "1"), "Palette");
        for (String state : List.of("minecraft:stone garbage", "minecraft:stone{}", "minecraft:stone[axis=x]",
                "minecraft:oak_log[axis=bad]", "minecraft:oak_log[axis=x,axis=y]", "minecraft:water")) {
            rejects(nbt -> nbt.getCompound("Palette").orElseThrow().putInt(state, 1), "Palette[" + state + "]");
        }
    }

    @Test
    void rejectsMalformedDenseDataIncludingVarintTails() throws Exception {
        for (byte[] bytes : List.of(new byte[]{}, new byte[]{0, 0}, new byte[]{1}, new byte[]{(byte) 0x80},
                new byte[]{0, (byte) 0x80}, new byte[]{(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 8},
                new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x7f},
                new byte[]{(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0})) {
            rejects(nbt -> nbt.putByteArray("BlockData", bytes), "BlockData");
        }
        for (int[] ints : List.of(new int[]{}, new int[]{0, 0}, new int[]{-1}, new int[]{1})) {
            rejects(nbt -> nbt.putIntArray("BlockData", ints), "BlockData");
        }
        var largestId = schematic(1, 1, 1, "minecraft:stone", new byte[]{(byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, 7});
        largestId.getCompound("Palette").orElseThrow().putInt("minecraft:stone", Integer.MAX_VALUE);
        assertEquals(1, read(largestId).targets().size());
        assertEquals(1, read(schematic(1, 1, 1, "minecraft:stone", new byte[]{(byte) 0x80, 0})).targets().size());
    }

    @Test
    void preservesPropertiesAndAirSemantics() throws Exception {
        var log = read(schematic(1, 1, 1, "minecraft:oak_log[axis=x]", new byte[]{0}));
        assertEquals(new BlockSpec("minecraft:oak_log", Map.of("axis", "x")), log.targets().getFirst().block());
        assertEquals(Map.of("facing", "north", "half", "bottom", "shape", "straight", "waterlogged", "false"),
                read(schematic(1, 1, 1, "minecraft:oak_stairs", new byte[]{0})).targets().getFirst().block().properties());
        assertEquals(1, read(schematic(1, 1, 1, "minecraft:chest", new byte[]{0})).targets().size());
        for (String air : List.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air")) {
            var allAir = schematic(1, 1, 1, air, new byte[]{0});
            assertTrue(read(allAir).targets().isEmpty());
            allAir.putByteArray("BlockData", new byte[]{0, (byte) 0x80});
            rejects(allAir, "BlockData");
            allAir.putByteArray("BlockData", new byte[]{0});
            allAir.getCompound("Palette").orElseThrow().putInt("minecraft:not_real", 5);
            rejects(allAir, "Palette");
        }
    }

    @Test
    void retainsPaddedHeightAndOccupiedRowParity() throws Exception {
        var padded = schematic(3, 3, 3, "minecraft:air", new byte[27]);
        padded.getCompound("Palette").orElseThrow().putInt("minecraft:stone", 1);
        byte[] cells = new byte[27];
        cells[9] = 1;
        cells[11] = 1;
        cells[15] = 1;
        cells[17] = 1;
        padded.putByteArray("BlockData", cells);
        var plan = read(padded);
        assertEquals(new GridSize(3, 3, 3), plan.dimensions());
        assertEquals(List.of(new GridPos(0, 1, 0), new GridPos(2, 1, 0), new GridPos(2, 1, 2), new GridPos(0, 1, 2)),
                plan.targets().stream().map(Target::relativePos).toList());
        cells[0] = 1;
        padded.putByteArray("BlockData", cells);
        rejects(padded, "BlockData[9]");
    }

    @Test
    void rejectsUnsupportedPayloads() throws Exception {
        for (String key : List.of("Entities", "BlockEntities", "TileEntities")) {
            rejects(nbt -> nbt.put(key, new NbtCompound()), key);
            var entities = new NbtList();
            entities.add(new NbtCompound());
            rejects(nbt -> nbt.put(key, entities), key);
            var wrongElements = new NbtList();
            wrongElements.add(NbtInt.of(1));
            rejects(nbt -> nbt.put(key, wrongElements), key);
            var valid = schematic(1, 1, 1, "minecraft:stone", new byte[]{0});
            valid.put(key, new NbtList());
            assertEquals(1, read(valid).targets().size());
        }
        rejects(nbt -> nbt.putByteArray("Blocks", new byte[]{1}), "Blocks");
        rejects(nbt -> nbt.put("Blocks", new NbtCompound()), "Blocks");
        rejects(nbt -> nbt.putByteArray("Data", new byte[]{1}), "Data");
        rejects(nbt -> nbt.put("palettes", new NbtList()), "palettes");
        rejects(nbt -> { nbt.put("size", new NbtList()); nbt.put("palette", new NbtList()); nbt.put("blocks", new NbtList()); }, "ambiguous");
        var structure = new NbtCompound();
        structure.put("size", new NbtList());
        structure.put("palette", new NbtList());
        structure.put("blocks", new NbtList());
        rejects(structure, "size");
        rejects(new NbtCompound(), "shape");
        var doubleWrapper = new NbtCompound();
        var innerWrapper = new NbtCompound();
        innerWrapper.put("Schematic", schematic(1, 1, 1, "minecraft:stone", new byte[]{0}));
        doubleWrapper.put("Schematic", innerWrapper);
        rejects(doubleWrapper, "shape");
    }

    @Test
    void rejectsUnsupportedExtensionsAndInvalidCompression() throws Exception {
        for (String suffix : List.of(".schematic", ".litematic", ".txt", "")) {
            var path = write(schematic(1, 1, 1, "minecraft:stone", new byte[]{0}), suffix);
            var error = assertThrows(IllegalArgumentException.class, () -> SchematicPlanImporter.importPlan(path));
            assertTrue(error.getMessage().contains(path.toAbsolutePath().normalize().toString()));
            assertTrue(error.getMessage().contains("extension"));
        }
        var raw = temporary.resolve("raw.nbt");
        Files.write(raw, new byte[]{10, 0, 0, 0});
        assertThrows(IOException.class, () -> SchematicPlanImporter.importPlan(raw));
        assertThrows(IOException.class, () -> SchematicPlanImporter.importPlan(temporary.resolve("missing.schem")));
        var truncated = copyFixture("truncated.schem");
        Files.write(truncated, Arrays.copyOf(Files.readAllBytes(truncated), 30));
        assertThrows(IOException.class, () -> SchematicPlanImporter.importPlan(truncated));
    }

    @Test
    void boundsNbtInput() throws Exception {
        // Compact raw writer bypasses native write depth limits, not the production read bound.
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(new byte[]{10, 0, 0});
        for (int depth = 0; depth < 520; depth++) bytes.writeBytes(new byte[]{10, 0, 1, 'x'});
        bytes.writeBytes(new byte[521]);
        var path = temporary.resolve("deep.schem");
        try (var out = new GZIPOutputStream(Files.newOutputStream(path))) { out.write(bytes.toByteArray()); }
        var error = assertThrows(IllegalArgumentException.class, () -> SchematicPlanImporter.importPlan(path));
        assertTrue(error.getMessage().contains(path.toAbsolutePath().normalize().toString()));
        assertTrue(error.getMessage().contains("NBT"));
        assertInstanceOf(NbtException.class, error.getCause());
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 11})
    void contextualizesNegativeNativeArrayLengths(int arrayType) throws Exception {
        var path = temporary.resolve("negative-array-" + arrayType + ".schem");
        try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))) {
            out.writeByte(10);
            out.writeUTF("");
            out.writeByte(arrayType);
            out.writeUTF("BlockData");
            out.writeInt(-1);
            out.writeByte(0);
        }
        var error = assertThrows(IllegalArgumentException.class, () -> SchematicPlanImporter.importPlan(path));
        assertEquals(IllegalArgumentException.class, error.getClass());
        assertAll(
                () -> assertTrue(error.getMessage().contains(path.toAbsolutePath().normalize().toString()), error.getMessage()),
                () -> assertTrue(error.getMessage().contains("NBT"), error.getMessage()),
                () -> assertInstanceOf(IllegalArgumentException.class, error.getCause()));
    }

    private void rejects(Consumer<NbtCompound> mutate, String context) throws Exception {
        var nbt = schematic(1, 1, 1, "minecraft:stone", new byte[]{0});
        mutate.accept(nbt);
        rejects(nbt, context);
    }

    private void rejects(NbtCompound nbt, String context) throws Exception {
        var path = write(nbt, ".schem");
        var error = assertThrows(IllegalArgumentException.class, () -> SchematicPlanImporter.importPlan(path), context);
        assertTrue(error.getMessage().contains(path.toAbsolutePath().normalize().toString()), error.getMessage());
        assertTrue(error.getMessage().contains(context), error.getMessage());
    }

    private BuildPlan read(NbtCompound nbt) throws IOException {
        return SchematicPlanImporter.importPlan(write(nbt, ".schem"));
    }

    private Path write(NbtCompound nbt, String suffix) throws IOException {
        var path = temporary.resolve("variant-" + fileNumber++ + suffix);
        NbtIo.writeCompressed(nbt, path);
        return path;
    }

    private Path copyFixture(String filename) throws IOException {
        return copyFixture("grounded-single-lane.schem", filename);
    }

    private Path copyFixture(String resource, String filename) throws IOException {
        var path = temporary.resolve(filename);
        try (var source = getClass().getResourceAsStream("/schematic/" + resource)) {
            assertNotNull(source, "checked-in fixture must be on the test classpath");
            Files.copy(source, path);
        }
        return path;
    }

    private void rejectsStructure(Consumer<NbtCompound> mutate, String context) throws Exception {
        var nbt = structure(1, 1, 1, "minecraft:stone");
        mutate.accept(nbt);
        rejects(nbt, context);
    }

    private static NbtCompound structure(int width, int height, int depth, String state) {
        var nbt = new NbtCompound();
        nbt.put("size", intList(width, height, depth));
        nbt.put("palette", nbtList(structureState(state)));
        nbt.put("blocks", nbtList(structureBlock(0, 0, 0, 0)));
        return nbt;
    }

    private static NbtCompound structureState(String name) {
        var state = new NbtCompound();
        state.putString("Name", name);
        return state;
    }

    private static NbtCompound structureBlock(int state, int x, int y, int z) {
        var block = new NbtCompound();
        block.putInt("state", state);
        block.put("pos", intList(x, y, z));
        return block;
    }

    private static NbtList intList(int... values) {
        var list = new NbtList();
        for (int value : values) list.add(NbtInt.of(value));
        return list;
    }

    private static NbtList nbtList(NbtElement... elements) {
        var list = new NbtList();
        list.addAll(Arrays.asList(elements));
        return list;
    }

    private static NbtCompound schematic(int width, int height, int length, String state, byte[] data) {
        var nbt = new NbtCompound();
        nbt.putInt("Width", width);
        nbt.putInt("Height", height);
        nbt.putInt("Length", length);
        var palette = new NbtCompound();
        palette.putInt(state, 0);
        nbt.put("Palette", palette);
        nbt.putByteArray("BlockData", data);
        return nbt;
    }
}
