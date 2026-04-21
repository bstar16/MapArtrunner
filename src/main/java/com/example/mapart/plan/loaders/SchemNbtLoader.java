package com.example.mapart.plan.loaders;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.PlanLoader;
import com.example.mapart.plan.Region;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3i;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class SchemNbtLoader implements PlanLoader {
    @Override
    public boolean supports(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".schem") || fileName.endsWith(".nbt") || fileName.endsWith(".schem.nbt");
    }

    @Override
    public String formatId() {
        return "schem.nbt";
    }

    @Override
    public BuildPlan load(Path path) throws IOException {
        NbtCompound fileRoot = NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes());
        if (fileRoot == null) {
            throw new IllegalArgumentException("Could not read NBT payload from " + path);
        }

        NbtCompound root = unwrapSchematicRoot(fileRoot);

        if (isStructureNbt(root)) {
            return loadStructureNbt(path, root);
        }

        int width = getDimension(root, "Width");
        int height = getDimension(root, "Height");
        int length = getDimension(root, "Length");
        Vec3i dimensions = new Vec3i(width, height, length);

        Map<Integer, Block> palette = parsePalette(root.getCompound("Palette").orElseThrow(
                () -> new IllegalArgumentException("Missing schematic field: Palette")
        ));
        int[] blockIndices = decodeBlockData(root, width * height * length);
        List<Placement> placements = orderPlacementsForTraversal(toPlacements(blockIndices, palette, width, length));
        Map<Block, Integer> materialCounts = countMaterials(placements);
        List<Region> regions = splitIntoRegions(placements);

        return new BuildPlan(formatId(), path, dimensions, placements, materialCounts, regions);
    }

    private BuildPlan loadStructureNbt(Path path, NbtCompound root) {
        Vec3i dimensions = getStructureDimensions(root);
        NbtList paletteList = root.getList("palette").orElseThrow(
                () -> new IllegalArgumentException("Missing structure field: palette")
        );
        NbtList blockList = root.getList("blocks").orElseThrow(
                () -> new IllegalArgumentException("Missing structure field: blocks")
        );
        Map<Integer, Block> palette = parseStructurePalette(paletteList);
        List<Placement> placements = orderPlacementsForTraversal(parseStructurePlacements(blockList, palette));
        Map<Block, Integer> materialCounts = countMaterials(placements);
        List<Region> regions = splitIntoRegions(placements);

        return new BuildPlan(formatId(), path, dimensions, placements, materialCounts, regions);
    }

    private NbtCompound unwrapSchematicRoot(NbtCompound root) {
        Optional<NbtCompound> wrapped = root.getCompound("Schematic");
        if (wrapped.isPresent()) {
            return wrapped.get();
        }
        return root;
    }

    private boolean isStructureNbt(NbtCompound root) {
        return root.getList("size").isPresent()
                && root.getList("palette").isPresent()
                && root.getList("blocks").isPresent();
    }

    private Vec3i getStructureDimensions(NbtCompound root) {
        NbtList size = root.getList("size").orElseThrow(
                () -> new IllegalArgumentException("Missing structure field: size")
        );
        if (size.size() != 3) {
            throw new IllegalArgumentException("Structure size must contain exactly 3 integers");
        }
        return new Vec3i(size.getInt(0, 0), size.getInt(1, 0), size.getInt(2, 0));
    }

    private int getDimension(NbtCompound root, String key) {
        Optional<Short> shortValue = root.getShort(key);
        if (shortValue.isPresent()) {
            return shortValue.get();
        }
        Optional<Integer> intValue = root.getInt(key);
        if (intValue.isPresent()) {
            return intValue.get();
        }
        if (!root.contains(key)) {
            throw new IllegalArgumentException("Missing schematic field: " + key);
        }
        throw new IllegalArgumentException("Schematic field " + key + " must be a short or int");
    }

    private Map<Integer, Block> parsePalette(NbtCompound paletteNbt) {
        Map<Integer, Block> palette = new HashMap<>();
        for (String key : paletteNbt.getKeys()) {
            int index = paletteNbt.getInt(key).orElse(-1);
            if (index < 0) {
                continue;
            }
            String blockId = key.contains("[") ? key.substring(0, key.indexOf('[')) : key;

            Identifier identifier = Identifier.tryParse(blockId);
            if (identifier == null) {
                continue;
            }

            if (Registries.BLOCK.containsId(identifier)) {
                palette.put(index, Registries.BLOCK.get(identifier));
            }
        }

        if (palette.isEmpty()) {
            throw new IllegalArgumentException("Palette is empty or invalid");
        }

        return palette;
    }

    private Map<Integer, Block> parseStructurePalette(NbtList paletteList) {
        Map<Integer, Block> palette = new HashMap<>();
        for (int i = 0; i < paletteList.size(); i++) {
            NbtCompound entry = paletteList.getCompound(i).orElse(new NbtCompound());
            String blockName = entry.getString("Name").orElse("");
            Identifier identifier = Identifier.tryParse(blockName);
            if (identifier != null && Registries.BLOCK.containsId(identifier)) {
                palette.put(i, Registries.BLOCK.get(identifier));
            }
        }

        if (palette.isEmpty()) {
            throw new IllegalArgumentException("Palette is empty or invalid");
        }
        return palette;
    }

    private int[] decodeBlockData(NbtCompound root, int expectedSize) {
        Optional<int[]> intArray = root.getIntArray("BlockData");
        if (intArray.isPresent()) {
            int[] raw = intArray.get();
            if (raw.length != expectedSize) {
                throw new IllegalArgumentException("BlockData size mismatch; expected " + expectedSize + " but got " + raw.length);
            }
            return raw;
        }

        byte[] encoded = root.getByteArray("BlockData").orElse(new byte[0]);
        if (encoded.length == 0) {
            throw new IllegalArgumentException("BlockData is empty");
        }

        List<Integer> indices = new ArrayList<>(expectedSize);
        int value = 0;
        int shift = 0;
        for (byte datum : encoded) {
            value |= (datum & 0x7F) << shift;
            if ((datum & 0x80) == 0) {
                indices.add(value);
                value = 0;
                shift = 0;
            } else {
                shift += 7;
                if (shift > 35) {
                    throw new IllegalArgumentException("Invalid varint in BlockData");
                }
            }
        }

        if (indices.size() != expectedSize) {
            throw new IllegalArgumentException("Decoded block count mismatch; expected " + expectedSize + " but got " + indices.size());
        }

        return indices.stream().mapToInt(Integer::intValue).toArray();
    }

    private List<Placement> toPlacements(int[] indices, Map<Integer, Block> palette, int width, int length) {
        List<Placement> placements = new ArrayList<>(indices.length);
        for (int i = 0; i < indices.length; i++) {
            Block block = palette.get(indices[i]);
            if (block == null || block == Blocks.AIR) {
                continue;
            }

            int x = i % width;
            int y = i / (width * length);
            int z = (i / width) % length;
            placements.add(new Placement(new BlockPos(x, y, z), block));
        }
        return placements;
    }

    private List<Placement> parseStructurePlacements(NbtList blocks, Map<Integer, Block> palette) {
        List<Placement> placements = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            NbtCompound blockNbt = blocks.getCompound(i).orElse(new NbtCompound());
            int stateIndex = blockNbt.getInt("state").orElse(-1);
            if (stateIndex < 0) {
                continue;
            }
            Block block = palette.get(stateIndex);
            if (block == null || block == Blocks.AIR) {
                continue;
            }

            NbtList pos = blockNbt.getList("pos").orElse(new NbtList());
            if (pos.size() != 3) {
                continue;
            }

            int x = pos.getInt(0, 0);
            int y = pos.getInt(1, 0);
            int z = pos.getInt(2, 0);
            placements.add(new Placement(new BlockPos(x, y, z), block));
        }
        return placements;
    }

    static List<Placement> orderPlacementsForTraversal(List<Placement> placements) {
        List<Placement> ordered = new ArrayList<>(placements);
        ordered.sort(Comparator
                .comparingInt((Placement placement) -> placement.relativePos().getY())
                .thenComparingInt(placement -> placement.relativePos().getZ())
                .thenComparingInt(placement -> placement.relativePos().getX()));

        List<Placement> serpentine = new ArrayList<>(ordered.size());
        int index = 0;
        int currentLayer = Integer.MIN_VALUE;
        int rowNumber = 0;
        while (index < ordered.size()) {
            Placement first = ordered.get(index);
            int y = first.relativePos().getY();
            int z = first.relativePos().getZ();
            if (y != currentLayer) {
                currentLayer = y;
                rowNumber = 0;
            }
            int rowEnd = index + 1;
            while (rowEnd < ordered.size()) {
                Placement candidate = ordered.get(rowEnd);
                if (candidate.relativePos().getY() != y || candidate.relativePos().getZ() != z) {
                    break;
                }
                rowEnd++;
            }

            List<Placement> row = new ArrayList<>(ordered.subList(index, rowEnd));
            if ((rowNumber & 1) == 1) {
                Collections.reverse(row);
            }
            serpentine.addAll(row);
            index = rowEnd;
            rowNumber++;
        }

        return serpentine;
    }

    private Map<Block, Integer> countMaterials(List<Placement> placements) {
        Map<Block, Integer> counts = new LinkedHashMap<>();
        for (Placement placement : placements) {
            counts.merge(placement.block(), 1, Integer::sum);
        }
        return counts;
    }

    private List<Region> splitIntoRegions(List<Placement> placements) {
        Map<ChunkPos, List<Placement>> grouped = new HashMap<>();
        for (Placement placement : placements) {
            ChunkPos chunkPos = new ChunkPos(placement.relativePos().getX() >> 4, placement.relativePos().getZ() >> 4);
            grouped.computeIfAbsent(chunkPos, ignored -> new ArrayList<>()).add(placement);
        }

        List<Map.Entry<ChunkPos, List<Placement>>> entries = new ArrayList<>(grouped.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<ChunkPos, List<Placement>> entry) -> entry.getKey().x)
                .thenComparingInt(entry -> entry.getKey().z));

        List<Region> regions = new ArrayList<>(entries.size());
        for (Map.Entry<ChunkPos, List<Placement>> entry : entries) {
            regions.add(new Region(entry.getKey(), entry.getValue()));
        }
        return regions;
    }
}
