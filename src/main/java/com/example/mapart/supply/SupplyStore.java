package com.example.mapart.supply;

import com.example.mapart.MapArtMod;
import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class SupplyStore {
    private static final Gson GSON = new Gson();

    private final Path storagePath;
    private final List<SupplyPoint> supplies = new ArrayList<>();
    private int nextId = 1;

    public SupplyStore() {
        this(defaultPath());
    }

    public SupplyStore(Path storagePath) {
        this.storagePath = storagePath;
        loadFromDisk();
    }

    public synchronized SupplyPoint add(BlockPos pos, String dimensionKey, String name) {
        SupplyPoint point = new SupplyPoint(nextId++, pos.toImmutable(), dimensionKey, name);
        supplies.add(point);
        saveToDisk();
        return point;
    }

    public synchronized List<SupplyPoint> list() {
        return supplies.stream().sorted(Comparator.comparingInt(SupplyPoint::id)).toList();
    }

    public synchronized boolean removeById(int id) {
        boolean removed = supplies.removeIf(point -> point.id() == id);
        if (removed) {
            saveToDisk();
        }
        return removed;
    }

    public synchronized int clear() {
        int removed = supplies.size();
        if (removed > 0) {
            supplies.clear();
            saveToDisk();
        }
        return removed;
    }

    private void loadFromDisk() {
        if (!Files.exists(storagePath)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(storagePath)) {
            StoredSupplies stored = GSON.fromJson(reader, StoredSupplies.class);
            if (stored == null || stored.supplies == null) {
                return;
            }

            supplies.clear();
            for (StoredSupplyPoint point : stored.supplies) {
                if (point == null || point.pos == null || point.dimensionKey == null) {
                    continue;
                }
                int id = point.id == null ? nextId : point.id;
                supplies.add(new SupplyPoint(id, point.pos.toBlockPos(), point.dimensionKey, point.name));
                nextId = Math.max(nextId, id + 1);
            }
        } catch (RuntimeException exception) {
            MapArtMod.LOGGER.warn("Supplies file {} is malformed; using empty list.", storagePath, exception);
            supplies.clear();
            nextId = 1;
        } catch (IOException exception) {
            MapArtMod.LOGGER.warn("Failed to read supplies file {}; using empty list.", storagePath, exception);
            supplies.clear();
            nextId = 1;
        }
    }

    private void saveToDisk() {
        StoredSupplies stored = new StoredSupplies();
        stored.supplies = supplies.stream().map(StoredSupplyPoint::from).toList();

        try {
            Path parent = storagePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tempPath = storagePath.resolveSibling(storagePath.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tempPath)) {
                GSON.toJson(stored, writer);
            }
            Files.move(tempPath, storagePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            MapArtMod.LOGGER.warn("Failed to save supplies file {}.", storagePath, exception);
        }
    }

    private static Path defaultPath() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("mapartrunner").resolve("supplies.json");
        } catch (Exception ignored) {
            return Path.of("config", "mapartrunner", "supplies.json");
        }
    }

    private static final class StoredSupplies {
        List<StoredSupplyPoint> supplies;
    }

    private static final class StoredSupplyPoint {
        Integer id;
        StoredPos pos;
        String dimensionKey;
        String name;

        static StoredSupplyPoint from(SupplyPoint point) {
            StoredSupplyPoint stored = new StoredSupplyPoint();
            stored.id = point.id();
            stored.pos = StoredPos.from(point.pos());
            stored.dimensionKey = point.dimensionKey();
            stored.name = point.name();
            return stored;
        }
    }

    private record StoredPos(int x, int y, int z) {
        static StoredPos from(BlockPos pos) {
            return new StoredPos(pos.getX(), pos.getY(), pos.getZ());
        }

        BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }
    }
}
