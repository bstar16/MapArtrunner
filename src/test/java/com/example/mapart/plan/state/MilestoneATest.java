package com.example.mapart.plan.state;

import com.example.mapart.baritone.NoOpBaritoneFacade;
import com.example.mapart.persistence.ConfigStore;
import com.example.mapart.persistence.ProgressStore;
import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.PlanLoader;
import com.example.mapart.plan.PlanLoaderRegistry;
import com.example.mapart.plan.Region;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MilestoneATest {

    @TempDir
    Path tempDir;

    @Test
    void validPlanLoadSucceeds() throws Exception {
        BuildPlanService service = createService(tempDir);
        Path fixturePath = writeFixture(tempDir.resolve("valid.fixture"));

        BuildPlan plan = service.load(fixturePath);

        assertNotNull(plan);
        assertEquals(fixturePath, plan.sourcePath());
    }

    @Test
    void validFixtureHasNonEmptyPlacements() throws Exception {
        BuildPlanService service = createService(tempDir);
        BuildPlan plan = service.load(writeFixture(tempDir.resolve("placements.fixture")));

        assertFalse(plan.placements().isEmpty());
    }

    @Test
    void regionSplittingIsChunkAligned() throws Exception {
        BuildPlanService service = createService(tempDir);
        BuildPlan plan = service.load(writeFixture(tempDir.resolve("regions.fixture")));

        assertTrue(plan.regions().size() >= 2, "fixture should span at least two chunks");
        for (Region region : plan.regions()) {
            assertFalse(region.placements().isEmpty());
            Placement first = region.placements().getFirst();
            int expectedChunkX = first.relativePos().getX() >> 4;
            int expectedChunkZ = first.relativePos().getZ() >> 4;
            region.placements().forEach(placement -> {
                assertEquals(expectedChunkX, placement.relativePos().getX() >> 4);
                assertEquals(expectedChunkZ, placement.relativePos().getZ() >> 4);
            });
        }
    }

    @Test
    void materialCountsAreComputed() throws Exception {
        BuildPlanService service = createService(tempDir);
        BuildPlan plan = service.load(writeFixture(tempDir.resolve("materials.fixture")));

        assertEquals(2, plan.materialCounts().getOrDefault(TestFixturePlanLoader.STONE_BLOCK, 0));
        assertEquals(1, plan.materialCounts().getOrDefault(TestFixturePlanLoader.DIRT_BLOCK, 0));
    }

    @Test
    void loadedPlanInfoEquivalentIsAvailableFromService() throws Exception {
        BuildPlanService service = createService(tempDir);
        service.load(writeFixture(tempDir.resolve("info.fixture")));

        assertTrue(service.currentPlan().isPresent());
        assertTrue(service.currentSession().isPresent());
    }

    @Test
    void unloadClearsCurrentPlan() throws Exception {
        BuildPlanService service = createService(tempDir);
        service.load(writeFixture(tempDir.resolve("unload.fixture")));

        assertTrue(service.unload());
        assertTrue(service.currentPlan().isEmpty());
        assertTrue(service.currentSession().isEmpty());
    }

    @Test
    void invalidOrUnsupportedInputFailsSafely() throws Exception {
        BuildPlanService service = createService(tempDir);

        Path unsupportedPath = tempDir.resolve("unsupported.txt");
        Files.writeString(unsupportedPath, "0,0,0,stone\n");
        assertThrows(IllegalArgumentException.class, () -> service.load(unsupportedPath));

        Path malformedPath = tempDir.resolve("malformed.fixture");
        Files.writeString(malformedPath, "not,valid\n");
        assertThrows(IllegalArgumentException.class, () -> service.load(malformedPath));
    }

    private static BuildPlanService createService(Path tempDir) {
        PlanLoaderRegistry registry = new PlanLoaderRegistry();
        registry.register(new TestFixturePlanLoader());

        BuildCoordinator coordinator = new BuildCoordinator(
                new WorldPlacementResolver(),
                new ConfigStore(tempDir.resolve("config.json")),
                new ProgressStore(tempDir.resolve("progress.json")),
                new com.example.mapart.supply.SupplyStore(tempDir.resolve("supplies.json")),
                new NoOpBaritoneFacade()
        );
        return new BuildPlanService(registry, coordinator);
    }

    private static Path writeFixture(Path path) throws Exception {
        Files.writeString(path, String.join("\n",
                "0,0,0,stone",
                "1,0,0,stone",
                "17,0,1,dirt"
        ));
        return path;
    }

    private static final class TestFixturePlanLoader implements PlanLoader {
        private static final Block STONE_BLOCK = allocateBlock();
        private static final Block DIRT_BLOCK = allocateBlock();


        private static Block allocateBlock() {
            try {
                java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);
                sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
                return (Block) unsafe.allocateInstance(Block.class);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Failed to allocate block for test fixture", exception);
            }
        }

        @Override
        public boolean supports(Path path) {
            return path.getFileName().toString().endsWith(".fixture");
        }

        @Override
        public String formatId() {
            return "test.fixture";
        }

        @Override
        public BuildPlan load(Path path) throws Exception {
            List<String> lines = Files.readAllLines(path);
            List<Placement> placements = new ArrayList<>();
            Map<Block, Integer> counts = new LinkedHashMap<>();

            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }

                String[] parts = line.split(",");
                if (parts.length != 4) {
                    throw new IllegalArgumentException("Malformed fixture line: " + line);
                }

                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                Block block = switch (parts[3]) {
                    case "stone" -> STONE_BLOCK;
                    case "dirt" -> DIRT_BLOCK;
                    default -> throw new IllegalArgumentException("Unknown block token: " + parts[3]);
                };

                Placement placement = new Placement(new BlockPos(x, y, z), block);
                placements.add(placement);
                counts.merge(block, 1, Integer::sum);
            }

            Map<String, List<Placement>> grouped = new HashMap<>();
            for (Placement placement : placements) {
                int chunkX = placement.relativePos().getX() >> 4;
                int chunkZ = placement.relativePos().getZ() >> 4;
                grouped.computeIfAbsent(chunkX + ":" + chunkZ, ignored -> new ArrayList<>()).add(placement);
            }

            List<Region> regions = grouped.values().stream()
                    .sorted(Comparator.comparingInt((List<Placement> l) -> l.getFirst().relativePos().getX())
                            .thenComparingInt(l -> l.getFirst().relativePos().getZ()))
                    .map(regionPlacements -> new Region(null, regionPlacements))
                    .toList();

            return new BuildPlan("test.fixture", path, new Vec3i(18, 1, 2), placements, counts, regions);
        }
    }
}
