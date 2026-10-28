package com.mapartrunner.fabric.schematic;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.fabric.runtime.WorldEvidenceReader;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.item.Items;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

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
import java.util.HashSet;
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
        if (structure) return structure(root, source);
        if (!sponge) throw invalid(source, "shape", "unsupported schematic shape");
        return sponge(root, source);
    }

    private static BuildPlan sponge(NbtCompound root, Path source) throws IOException {
        var dimensions = new GridSize(dimension(root, "Width", source), dimension(root, "Height", source),
                dimension(root, "Length", source));
        int volume = volume(dimensions, source);
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
            if (entry.air()) continue;
            var position = new GridPos(index % dimensions.width(), index / (dimensions.width() * dimensions.depth()),
                    (index / dimensions.width()) % dimensions.depth());
            addTarget(targets, position, entry, source, field);
        }
        if (bytes != null && cursor != bytes.length) throw invalid(source, "BlockData", "trailing bytes after " + volume + " cells");
        return normalize(dimensions, targets);
    }

    private static BuildPlan structure(NbtCompound root, Path source) throws IOException {
        int[] size = intTriple(root.get("size"), source, "size");
        for (int axis = 0; axis < size.length; axis++) {
            if (size[axis] <= 0) throw invalid(source, "size[" + axis + "]", "dimension must be positive");
        }
        var dimensions = new GridSize(size[0], size[1], size[2]);
        volume(dimensions, source);
        if (root.contains("entities") && (!(root.get("entities") instanceof NbtList entities) || !entities.isEmpty())) {
            throw invalid(source, "entities", "must be an empty entity list; payloads are unsupported");
        }
        if (!(root.get("palette") instanceof NbtList entries) || entries.isEmpty()) {
            throw invalid(source, "palette", "required nonempty list");
        }
        var palette = new ArrayList<PaletteEntry>();
        for (int index = 0; index < entries.size(); index++) {
            String field = "palette[" + index + "]";
            if (!(entries.get(index) instanceof NbtCompound entry)) throw invalid(source, field, "required compound");
            if (!(entry.get("Name") instanceof NbtString name)) throw invalid(source, field + ".Name", "required block identifier string");
            var id = Identifier.tryParse(name.value());
            var block = id == null ? null : Registries.BLOCK.getOptionalValue(id).orElse(null);
            if (block == null) throw invalid(source, field + ".Name", "unknown or invalid block identifier " + name.value());
            var state = block.getDefaultState();
            if (entry.contains("Properties")) {
                if (!(entry.get("Properties") instanceof NbtCompound properties)) {
                    throw invalid(source, field + ".Properties", "required compound");
                }
                for (var tag : properties.entrySet()) {
                    String propertyField = field + ".Properties." + tag.getKey();
                    var property = block.getStateManager().getProperty(tag.getKey());
                    if (property == null) throw invalid(source, propertyField, "unknown block property");
                    if (!(tag.getValue() instanceof NbtString value)) throw invalid(source, propertyField, "required string value");
                    state = property(state, property, value.value(), source, propertyField);
                }
            }
            palette.add(paletteEntry(state, source, field));
        }
        if (!(root.get("blocks") instanceof NbtList records)) throw invalid(source, "blocks", "required list");
        var targets = new ArrayList<Target>();
        var positions = new HashSet<GridPos>();
        // Sparse records alone own iteration/storage; air still participates in validation and uniqueness.
        for (int index = 0; index < records.size(); index++) {
            String field = "blocks[" + index + "]";
            if (!(records.get(index) instanceof NbtCompound record)) throw invalid(source, field, "required compound");
            if (!(record.get("state") instanceof NbtInt state) || state.intValue() < 0 || state.intValue() >= palette.size()) {
                throw invalid(source, field + ".state", "required INT referencing a palette entry");
            }
            int[] pos = intTriple(record.get("pos"), source, field + ".pos");
            if (pos[0] < 0 || pos[0] >= size[0] || pos[1] < 0 || pos[1] >= size[1] || pos[2] < 0 || pos[2] >= size[2]) {
                throw invalid(source, field + ".pos", "position outside source dimensions");
            }
            var position = new GridPos(pos[0], pos[1], pos[2]);
            if (!positions.add(position)) throw invalid(source, field + ".pos", "duplicate position " + position);
            if (record.contains("nbt") && (!(record.get("nbt") instanceof NbtCompound nbt) || !nbt.isEmpty())) {
                throw invalid(source, field + ".nbt", "must be an empty compound; block-entity payloads are unsupported");
            }
            addTarget(targets, position, palette.get(state.intValue()), source, field);
        }
        return normalize(dimensions, targets);
    }

    private static int[] intTriple(NbtElement element, Path source, String field) {
        if (!(element instanceof NbtList list) || list.size() != 3) throw invalid(source, field, "required list of three INTs");
        int[] values = new int[3];
        for (int axis = 0; axis < values.length; axis++) {
            if (!(list.get(axis) instanceof NbtInt number)) throw invalid(source, field + "[" + axis + "]", "required INT");
            values[axis] = number.intValue();
        }
        return values;
    }

    private static int volume(GridSize dimensions, Path source) {
        try {
            return Math.multiplyExact(Math.multiplyExact(dimensions.width(), dimensions.height()), dimensions.depth());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(source + ": volume: dimensions exceed dense integer indexing", e);
        }
    }

    private static <T extends Comparable<T>> BlockState property(BlockState state, Property<T> property, String value,
                                                                Path source, String field) {
        return state.with(property, property.parse(value).orElseThrow(() -> invalid(source, field, "invalid property value " + value)));
    }

    private static PaletteEntry paletteEntry(BlockState state, Path source, String field) {
        if (!state.isAir() && state.getBlock().asItem() == Items.AIR) throw invalid(source, field, "non-air block has no placement item");
        return new PaletteEntry(state.isAir(), WorldEvidenceReader.blockSpec(state));
    }

    private static void addTarget(List<Target> targets, GridPos position, PaletteEntry entry, Path source, String field) {
        if (entry.air()) return;
        if (!targets.isEmpty() && targets.getFirst().relativePos().y() != position.y()) {
            throw invalid(source, field, "multiple occupied Y planes are unsupported");
        }
        targets.add(new Target(0, position, entry.block()));
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
                palette.put(number.intValue(), paletteEntry(state, source, field));
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
