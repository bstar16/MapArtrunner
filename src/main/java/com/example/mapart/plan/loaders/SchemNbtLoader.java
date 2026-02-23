package com.example.mapart.plan.loaders;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.PlanLoader;
import com.example.mapart.plan.Region;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3i;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    public BuildPlan load(Path path, ServerCommandSource source) throws IOException {
        NbtCompound root = NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes());
        if (root == null) {
            throw new IllegalArgumentException("Could not read NBT payload from " + path);
        }

        int width = getDimension(root, "Width");
        int height = getDimension(root, "Height");
        int length = getDimension(root, "Length");
        Vec3i dimensions = new Vec3i(width, height, length);

        Map<Integer, Block> palette = parsePalette(root.getCompound("Palette"));
        int[] blockIndices = decodeBlockData(root, width * height * length);
        List<Placement> placements = toPlacements(blockIndices, palette, width, length);
        Map<Block, Integer> materialCounts = countMaterials(placements);
        List<Region> regions = splitIntoRegions(placements);

        return new BuildPlan(formatId(), path, dimensions, placements, materialCounts, regions);
    }

    private int getDimension(NbtCompound root, String key) {
        if (!root.contains(key)) {
            throw new IllegalArgumentException("Missing schematic field: " + key);
        }
        return root.getShort(key);
    }

    private Map<Integer, Block> parsePalette(NbtCompound paletteNbt) {
        Map<Integer, Block> palette = new HashMap<>();
        for (String key : paletteNbt.getKeys()) {
            int index = paletteNbt.getInt(key);
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

    private int[] decodeBlockData(NbtCompound root, int expectedSize) {
        if (root.contains("BlockData", NbtElement.INT_ARRAY_TYPE)) {
            int[] raw = root.getIntArray("BlockData");
            if (raw.length != expectedSize) {
                throw new IllegalArgumentException("BlockData size mismatch; expected " + expectedSize + " but got " + raw.length);
            }
            return raw;
        }

        byte[] encoded = root.getByteArray("BlockData");
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
