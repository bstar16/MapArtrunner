package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.baritone.BaritoneFacade;
import com.example.mapart.baritone.NoOpBaritoneFacade;
import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.state.BuildSession;
import com.example.mapart.supply.SupplyPoint;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GroundedRefillControllerTest {

    @Test
    void missingItemDuringSweptTriggersRefillController() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults());

        SupplyPoint supply = new SupplyPoint(1, new BlockPos(5, 64, 5), "minecraft:overworld", "chest");
        runner.triggerRefillForTests(List.of(), List.of(supply), baritone);

        assertTrue(runner.getRefillController().isActive());
        assertEquals(GroundedRefillController.RefillState.NAVIGATING, runner.getRefillController().state());
    }

    @Test
    void noRegisteredSupplyInDimensionFailsClearly() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedRefillController controller = new GroundedRefillController();

        controller.initiateWithSuppliesForTests(List.of(), List.of(), baritone);

        assertFalse(controller.isActive());
        assertEquals(GroundedRefillController.RefillState.FAILED, controller.state());
        assertTrue(controller.failureMessage().isPresent());
        assertFalse(controller.failureMessage().get().isBlank());
        assertEquals(0, baritone.goNearCalls);
    }

    @Test
    void emptySupplyListDoesNotTriggerGoTo() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults());

        runner.triggerRefillForTests(List.of(), List.of(), baritone);

        assertFalse(runner.getRefillController().isActive());
        assertEquals(0, baritone.goNearCalls);
    }

    @Test
    void baritoneGoNearCalledWithSupplyPointPosition() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedRefillController controller = new GroundedRefillController();
        BlockPos supplyPos = new BlockPos(100, 64, 200);
        SupplyPoint supply = new SupplyPoint(1, supplyPos, "minecraft:overworld", "chest");

        controller.initiateWithSuppliesForTests(List.of(supply), List.of(), baritone);

        assertEquals(1, baritone.goNearCalls);
        assertEquals(supplyPos, baritone.lastGoNearTarget);
        assertEquals(4, baritone.lastGoNearRange);
        assertEquals(GroundedRefillController.RefillState.NAVIGATING, controller.state());
    }

    @Test
    void baritoneNotCalledForRefillDuringNormalRunnerStart() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);

        runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults());

        assertFalse(runner.getRefillController().isActive());
    }

    @Test
    void afterSimulatedRestockSmartResumeIsTriggered() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = sessionWithOrigin();
        runner.start(session, 0, GroundedSweepSettings.defaults());

        SupplyPoint supply = new SupplyPoint(1, new BlockPos(5, 64, 5), "minecraft:overworld", "chest");
        runner.triggerRefillForTests(List.of(), List.of(supply), new NoOpBaritoneFacade());
        assertTrue(runner.getRefillController().isActive());

        runner.simulateRefillCompleteForTests();

        assertFalse(runner.getRefillController().isActive());
        assertTrue(runner.isActive());
    }

    @Test
    void navigationTimeoutFailsClearly() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedRefillController controller = new GroundedRefillController();
        SupplyPoint supply = new SupplyPoint(1, new BlockPos(1000, 64, 1000), "minecraft:overworld", "far");

        controller.initiateWithSuppliesForTests(List.of(supply), List.of(), baritone);
        assertEquals(GroundedRefillController.RefillState.NAVIGATING, controller.state());

        controller.simulateNavTimeoutForTests();
        GroundedRefillController.TickResult result = controller.tick(null, baritone);

        assertEquals(GroundedRefillController.TickResult.FAILED, result);
        assertEquals(GroundedRefillController.RefillState.FAILED, controller.state());
        assertTrue(controller.failureMessage().isPresent());
    }

    @Test
    void refillCancelStopsSafelyWithoutResuming() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedRefillController controller = new GroundedRefillController();
        SupplyPoint supply = new SupplyPoint(1, new BlockPos(50, 64, 50), "minecraft:overworld", "chest");

        controller.initiateWithSuppliesForTests(List.of(supply), List.of(), baritone);
        assertEquals(GroundedRefillController.RefillState.NAVIGATING, controller.state());
        int cancelCallsBefore = baritone.cancelCalls;

        controller.cancel(baritone);

        assertFalse(controller.isActive());
        assertEquals(GroundedRefillController.RefillState.FAILED, controller.state());
        assertTrue(baritone.cancelCalls > cancelCallsBefore);
        assertNotEquals(GroundedRefillController.RefillState.DONE, controller.state());
    }

    @Test
    void multipleItemsNeededAllInContainerAllPulledBeforeDone() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedRefillController controller = new GroundedRefillController();
        SupplyPoint supply = new SupplyPoint(1, new BlockPos(50, 64, 50), "minecraft:overworld", "chest");

        // Two needed items represented as null placeholders (Item requires game registry;
        // simulateRefillingForTests uses index-based sets to avoid constructing live Item instances).
        List<Item> neededItems = new ArrayList<>();
        neededItems.add(null); // slot 0
        neededItems.add(null); // slot 1

        controller.initiateWithSuppliesForTests(List.of(supply), neededItems, baritone);
        assertEquals(GroundedRefillController.RefillState.NAVIGATING, controller.state());

        // Player has nothing; container has both needed items (indices 0 and 1)
        Set<Integer> playerHeld = new HashSet<>();
        Set<Integer> containerItems = new HashSet<>(Set.of(0, 1));

        GroundedRefillController.TickResult result = controller.simulateRefillingForTests(playerHeld, containerItems, baritone);

        assertEquals(GroundedRefillController.TickResult.DONE, result);
        assertEquals(GroundedRefillController.RefillState.DONE, controller.state());
        assertTrue(playerHeld.contains(0), "first needed item should have been pulled from container");
        assertTrue(playerHeld.contains(1), "second needed item should have been pulled from container");
    }

    // ---- Helpers ----

    private static BuildSession sessionWithOrigin() {
        BuildPlan plan = new BuildPlan(
                "test",
                Path.of("plan.schem"),
                new Vec3i(5, 1, 5),
                List.of(
                        new Placement(new BlockPos(0, 0, 0), null),
                        new Placement(new BlockPos(1, 0, 0), null),
                        new Placement(new BlockPos(2, 0, 0), null),
                        new Placement(new BlockPos(3, 0, 0), null),
                        new Placement(new BlockPos(4, 0, 0), null)
                ),
                Map.of(),
                List.of()
        );
        BuildSession session = new BuildSession(plan);
        session.setOrigin(new BlockPos(10, 64, 10));
        return session;
    }

    private static final class RecordingBaritoneFacade implements BaritoneFacade {
        BlockPos lastGoToTarget;
        int goToCalls;
        BlockPos lastGoNearTarget;
        int lastGoNearRange;
        int goNearCalls;
        int cancelCalls;

        @Override
        public CommandResult goTo(BlockPos target) {
            lastGoToTarget = target;
            goToCalls++;
            return CommandResult.success("ok");
        }

        @Override
        public CommandResult goNear(BlockPos target, int range) {
            lastGoNearTarget = target;
            lastGoNearRange = range;
            goNearCalls++;
            return CommandResult.success("ok");
        }

        @Override
        public CommandResult pause() {
            return CommandResult.success("ok");
        }

        @Override
        public CommandResult resume() {
            return CommandResult.success("ok");
        }

        @Override
        public CommandResult cancel() {
            cancelCalls++;
            return CommandResult.success("ok");
        }

        @Override
        public boolean isBusy() {
            return false;
        }
    }
}
