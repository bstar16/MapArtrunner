package com.mapartrunner.fabric.schematic;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

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
        rejects(structure, "structure");
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
        var path = temporary.resolve(filename);
        try (var source = getClass().getResourceAsStream("/schematic/grounded-single-lane.schem")) {
            assertNotNull(source, "checked-in fixture must be on the test classpath");
            Files.copy(source, path);
        }
        return path;
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
