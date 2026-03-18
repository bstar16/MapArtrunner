package com.example.mapart.plan.state;

import com.example.mapart.baritone.NoOpBaritoneFacade;
import com.example.mapart.persistence.ConfigStore;
import com.example.mapart.persistence.ProgressStore;
import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.Region;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BuildSessionStateTest {

    @TempDir
    Path tempDir;

    @Test
    void transitionsUseSingleValidatedModel() {
        BuildSession session = new BuildSession(plan(tempDir.resolve("one.nbt")));

        session.transitionTo(BuildPlanState.LOADED);
        assertThrows(IllegalStateException.class, () -> session.transitionTo(BuildPlanState.PAUSED));

        session.transitionTo(BuildPlanState.BUILDING);
        assertDoesNotThrow(() -> session.transitionTo(BuildPlanState.PAUSED));
    }

    @Test
    void originResolutionUsesSessionOrigin() {
        WorldPlacementResolver resolver = new WorldPlacementResolver();
        BuildSession session = new BuildSession(plan(tempDir.resolve("two.nbt")));
        session.transitionTo(BuildPlanState.LOADED);
        session.setOrigin(new BlockPos(10, 64, 20));

        Placement placement = session.getPlan().placements().getFirst();
        BlockPos absolute = resolver.resolveAbsolute(session, placement).orElseThrow();

        assertEquals(new BlockPos(11, 64, 22), absolute);
    }

    @Test
    void coordinatorExposesStatusSnapshot() {
        BuildCoordinator coordinator = new BuildCoordinator(
                new WorldPlacementResolver(),
                new ConfigStore(tempDir.resolve("config.json")),
                new ProgressStore(tempDir.resolve("progress.json")),
                new com.example.mapart.supply.SupplyStore(tempDir.resolve("supplies.json")),
                new NoOpBaritoneFacade()
        );

        coordinator.loadPlan(plan(tempDir.resolve("three.nbt")));
        coordinator.setOrigin(new BlockPos(0, 70, 0));

        BuildCoordinator.SessionStatus status = coordinator.sessionStatus().orElseThrow();
        assertEquals("three.nbt", status.planId());
        assertEquals(BuildPlanState.LOADED, status.state());
        assertEquals(new BlockPos(0, 70, 0), status.origin());
        assertEquals(0, status.currentPlacementIndex());
        assertEquals(1, status.totalPlacements());
        assertTrue(status.nextTarget().isPresent());
    }


    @Test
    void coordinatorRestoresPersistedProgressForSamePlan() {
        Path configPath = tempDir.resolve("config-restore.json");
        Path progressPath = tempDir.resolve("progress-restore.json");
        BuildPlan plan = plan(tempDir.resolve("restore.nbt"));

        BuildCoordinator firstCoordinator = new BuildCoordinator(
                new WorldPlacementResolver(),
                new ConfigStore(configPath),
                new ProgressStore(progressPath),
                new com.example.mapart.supply.SupplyStore(tempDir.resolve("supplies-restore.json")),
                new NoOpBaritoneFacade()
        );

        BuildSession firstSession = firstCoordinator.loadPlan(plan);
        firstSession.setOrigin(new BlockPos(8, 64, 8));
        firstSession.setCurrentPlacementIndex(1);
        firstSession.setCurrentRegionIndex(1);
        firstSession.getProgress().setTotalCompletedPlacements(1);
        firstSession.transitionTo(BuildPlanState.BUILDING);
        firstCoordinator.pause();

        BuildCoordinator restoredCoordinator = new BuildCoordinator(
                new WorldPlacementResolver(),
                new ConfigStore(configPath),
                new ProgressStore(progressPath),
                new com.example.mapart.supply.SupplyStore(tempDir.resolve("supplies-restore.json")),
                new NoOpBaritoneFacade()
        );
        BuildSession restoredSession = restoredCoordinator.loadPlan(plan);

        assertEquals(new BlockPos(8, 64, 8), restoredSession.getOrigin());
        assertEquals(1, restoredSession.getCurrentPlacementIndex());
        assertEquals(1, restoredSession.getCurrentRegionIndex());
        assertEquals(1, restoredSession.getTotalCompletedPlacements());
        assertEquals(BuildPlanState.PAUSED, restoredSession.getState());
    }


    @Test
    void setOriginPersistsExplicitCoordinates() {
        BuildCoordinator coordinator = new BuildCoordinator(
                new WorldPlacementResolver(),
                new ConfigStore(tempDir.resolve("config-explicit.json")),
                new ProgressStore(tempDir.resolve("progress-explicit.json")),
                new com.example.mapart.supply.SupplyStore(tempDir.resolve("supplies-explicit.json")),
                new NoOpBaritoneFacade()
        );

        coordinator.loadPlan(plan(tempDir.resolve("explicit.nbt")));

        Optional<String> error = coordinator.setOrigin(new BlockPos(12, 80, -6));

        assertTrue(error.isEmpty());
        assertEquals(new BlockPos(12, 80, -6), coordinator.sessionStatus().orElseThrow().origin());
    }

    @Test
    void setOriginFailsWithoutLoadedPlan() {
        BuildCoordinator coordinator = new BuildCoordinator(
                new WorldPlacementResolver(),
                new ConfigStore(tempDir.resolve("config2.json")),
                new ProgressStore(tempDir.resolve("progress2.json")),
                new com.example.mapart.supply.SupplyStore(tempDir.resolve("supplies2.json")),
                new NoOpBaritoneFacade()
        );

        assertTrue(coordinator.setOrigin(BlockPos.ORIGIN).isPresent());
    }

    private static BuildPlan plan(Path sourcePath) {
        Block block = allocateBlock();
        Placement placement = new Placement(new BlockPos(1, 0, 2), block);
        return new BuildPlan(
                "test",
                sourcePath,
                new Vec3i(2, 1, 3),
                List.of(placement),
                Map.of(block, 1),
                List.of(new Region(null, List.of(placement)))
        );
    }

    private static Block allocateBlock() {
        try {
            java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            return (Block) unsafe.allocateInstance(Block.class);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to allocate block", exception);
        }
    }
}
