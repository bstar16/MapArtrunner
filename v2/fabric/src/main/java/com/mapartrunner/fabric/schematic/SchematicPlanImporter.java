package com.mapartrunner.fabric.schematic;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.fabric.runtime.WorldEvidenceReader;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.item.Items;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Pure file boundary: fully validate before publishing a normalized grounded plan. */
public final class SchematicPlanImporter {
    private SchematicPlanImporter() { }

    /** Source offsets are ignored; source dimensions/coordinates survive and run origin is applied later. */
    public static BuildPlan importPlan(Path path) throws IOException {
        Path source = path.toAbsolutePath().normalize();
        String filename = source.getFileName() == null ? "" : source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".schem") && !filename.endsWith(".nbt")) {
            throw invalid(source, "extension", "supported suffixes are .schem, .schem.nbt and .nbt");
        }
        NbtCompound root;
        try {
            // Native 100 MiB tracked-byte budget and depth bound, not unlimited NBT.
            root = NbtIo.readCompressed(source, NbtSizeTracker.forLevel());
        } catch (NbtException | IllegalArgumentException e) {
            // Negative native array lengths use plain IllegalArgumentException.
            throw new IllegalArgumentException(source + ": NBT: " + e.getMessage(), e);
        } catch (NbtCrashException e) {
            // Native compound reads wrap truncated tag I/O in this specific exception.
            if (e.getCause() instanceof IOException) throw new IOException(source + ": NBT: " + e.getMessage(), e);
            throw e;
        } catch (IOException e) {
            throw new IOException(source + ": gzip/NBT: " + e.getMessage(), e);
        }
        if (root.contains("Schematic")) {
            if (!(root.get("Schematic") instanceof NbtCompound schematic)) {
                throw invalid(source, "Schematic", "wrapper must be a compound");
            }
            root = schematic;
        }
        for (String field : List.of("Blocks", "Data", "AddBlocks", "palettes")) {
            if (root.contains(field)) throw invalid(source, field, "unsupported construction schema");
        }
        boolean sponge = root.contains("Width") || root.contains("Height") || root.contains("Length")
                || root.contains("Palette") || root.contains("BlockData");
        boolean structure = root.contains("size") || root.contains("palette") || root.contains("blocks");
        if (sponge && structure) throw invalid(source, "shape", "ambiguous Sponge and structure schemas");
        if (structure) throw invalid(source, "shape", "structure schema is not supported yet");
        if (!sponge) throw invalid(source, "shape", "unsupported schematic shape");
        return sponge(root, source);
    }

    private static BuildPlan sponge(NbtCompound root, Path source) throws IOException {
        var dimensions = new GridSize(dimension(root, "Width", source), dimension(root, "Height", source),
                dimension(root, "Length", source));
        int volume;
        try {
            volume = Math.multiplyExact(Math.multiplyExact(dimensions.width(), dimensions.height()), dimensions.depth());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(source + ": volume: dimensions exceed dense integer indexing", e);
        }
        for (String field : List.of("Entities", "BlockEntities", "TileEntities")) {
            if (root.contains(field) && (!(root.get(field) instanceof NbtList list) || !list.isEmpty())) {
                throw invalid(source, field, "must be an empty entity compound list; payloads are unsupported");
            }
        }
        var palette = palette(root, source);
        var data = root.get("BlockData");
        byte[] bytes = null;
        int[] ints = null;
        if (data instanceof NbtByteArray array) {
            bytes = array.getByteArray();
            if (bytes.length < volume || (long) bytes.length > (long) volume * 5) {
                throw invalid(source, "BlockData", "byte count cannot encode exactly " + volume + " cells");
            }
        } else if (data instanceof NbtIntArray array) {
            ints = array.getIntArray();
            if (ints.length != volume) throw invalid(source, "BlockData", "expected " + volume + " cells, found " + ints.length);
        } else {
            throw invalid(source, "BlockData", "required byte array or int array");
        }
        var targets = new ArrayList<Target>();
        int cursor = 0;
        int occupiedY = -1;
        for (int index = 0; index < volume; index++) {
            String field = "BlockData[" + index + "]";
            int paletteId;
            if (ints != null) {
                paletteId = ints[index];
            } else {
                paletteId = 0;
                for (int part = 0; ; part++) {
                    if (cursor == bytes.length) throw invalid(source, field, "truncated varint");
                    int value = bytes[cursor++] & 0xff;
                    if (part == 4 && (value & 0xf8) != 0) {
                        throw invalid(source, field, "varint exceeds nonnegative int");
                    }
                    paletteId |= (value & 0x7f) << (part * 7);
                    if ((value & 0x80) == 0) break;
                }
            }
            var entry = palette.get(paletteId);
            if (entry == null) throw invalid(source, field, "unknown or negative palette index " + paletteId);
            if (!entry.air()) {
                int y = index / (dimensions.width() * dimensions.depth());
                if (occupiedY != -1 && occupiedY != y) throw invalid(source, field, "multiple occupied Y planes are unsupported");
                occupiedY = y;
                var position = new GridPos(index % dimensions.width(), y, (index / dimensions.width()) % dimensions.depth());
                targets.add(new Target(0, position, entry.block()));
            }
        }
        if (bytes != null && cursor != bytes.length) throw invalid(source, "BlockData", "trailing bytes after " + volume + " cells");
        return normalize(dimensions, targets);
    }

    private static int dimension(NbtCompound root, String field, Path source) {
        NbtElement value = root.get(field);
        int size;
        if (value instanceof NbtShort number) size = number.intValue();
        else if (value instanceof NbtInt number) size = number.intValue();
        else throw invalid(source, field, "required SHORT or INT dimension");
        if (size <= 0) throw invalid(source, field, "dimension must be positive");
        return size;
    }

    private static Map<Integer, PaletteEntry> palette(NbtCompound root, Path source) {
        if (!(root.get("Palette") instanceof NbtCompound tags) || tags.isEmpty()) {
            throw invalid(source, "Palette", "required nonempty compound");
        }
        var palette = new HashMap<Integer, PaletteEntry>();
        for (var tag : tags.entrySet()) {
            String field = "Palette[" + tag.getKey() + "]";
            if (!(tag.getValue() instanceof NbtInt number) || number.intValue() < 0) {
                throw invalid(source, field, "palette index must be a nonnegative INT");
            }
            if (palette.containsKey(number.intValue())) throw invalid(source, field, "duplicate palette index " + number.intValue());
            var reader = new StringReader(tag.getKey());
            try {
                var state = BlockArgumentParser.block(Registries.BLOCK, reader, false).blockState();
                if (reader.canRead()) throw invalid(source, field, "trailing block-state text at " + reader.getCursor());
                if (!state.isAir() && state.getBlock().asItem() == Items.AIR) {
                    throw invalid(source, field, "non-air block has no placement item");
                }
                palette.put(number.intValue(), new PaletteEntry(state.isAir(), WorldEvidenceReader.blockSpec(state)));
            } catch (CommandSyntaxException e) {
                throw new IllegalArgumentException(source + ": " + field + ": " + e.getMessage(), e);
            }
        }
        return palette;
    }

    private static BuildPlan normalize(GridSize dimensions, List<Target> targets) throws IOException {
        targets.sort(Comparator.comparingInt((Target target) -> target.relativePos().y())
                .thenComparingInt(target -> target.relativePos().z()).thenComparingInt(target -> target.relativePos().x()));
        int previousY = -1;
        boolean reverse = false;
        for (int start = 0; start < targets.size();) {
            var position = targets.get(start).relativePos();
            if (position.y() != previousY) reverse = false;
            int end = start + 1;
            while (end < targets.size() && targets.get(end).relativePos().y() == position.y()
                    && targets.get(end).relativePos().z() == position.z()) end++;
            if (reverse) Collections.reverse(targets.subList(start, end));
            reverse = !reverse;
            previousY = position.y();
            start = end;
        }
        for (int i = 0; i < targets.size(); i++) {
            var target = targets.get(i);
            targets.set(i, new Target(i, target.relativePos(), target.block()));
        }
        return new BuildPlan(fingerprint(dimensions, targets), dimensions, targets);
    }

    private static String fingerprint(GridSize dimensions, List<Target> targets) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK SHA-256 is required", e);
        }
        // Versioned, length-delimited semantic schema; map iteration and source bytes cannot affect it.
        try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            text(out, "mapartrunner-plan-v1");
            out.writeInt(dimensions.width());
            out.writeInt(dimensions.height());
            out.writeInt(dimensions.depth());
            out.writeInt(targets.size());
            for (var target : targets) {
                out.writeInt(target.id());
                out.writeInt(target.relativePos().x());
                out.writeInt(target.relativePos().y());
                out.writeInt(target.relativePos().z());
                text(out, target.block().id());
                var properties = new TreeMap<>(target.block().properties());
                out.writeInt(properties.size());
                for (var property : properties.entrySet()) {
                    text(out, property.getKey());
                    text(out, property.getValue());
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static IllegalArgumentException invalid(Path source, String field, String reason) {
        return new IllegalArgumentException(source + ": " + field + ": " + reason);
    }

    private record PaletteEntry(boolean air, BlockSpec block) { }
}
