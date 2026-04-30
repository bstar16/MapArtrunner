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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GroundedRefillControllerTest {

    @Test
    void missingItemDuringSweptTriggersRefillController() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults());

        SupplyPoint supply = new SupplyPoint(1, new BlockPos(5, 64, 5), "minecraft:overworld", "chest");
        runner.triggerRefillForTests((Item) null, List.of(supply), baritone);

        assertTrue(runner.getRefillController().isActive());
        assertEquals(GroundedRefillController.RefillState.NAVIGATING, runner.getRefillController().state());
    }

    @Test
    void noRegisteredSupplyInDimensionFailsClearly() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedRefillController controller = new GroundedRefillController();

        controller.initiateWithSuppliesForTests(List.of(), (Item) null, baritone);

        assertFalse(controller.isActive());
        assertEquals(GroundedRefillController.RefillState.FAILED, controller.state());
        assertTrue(controller.failureMessage().isPresent());
        assertFalse(controller.failureMessage().get().isBlank());
        assertEquals(0, baritone.goToCalls);
    }

    @Test
    void emptySupplyListDoesNotTriggerGoTo() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults());

        runner.triggerRefillForTests((Item) null, List.of(), baritone);

        assertFalse(runner.getRefillController().isActive());
        assertEquals(0, baritone.goToCalls);
    }

    @Test
    void baritoneGoToCalledWithSupplyPointPosition() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedRefillController controller = new GroundedRefillController();
        BlockPos supplyPos = new BlockPos(100, 64, 200);
        SupplyPoint supply = new SupplyPoint(1, supplyPos, "minecraft:overworld", "chest");

        controller.initiateWithSuppliesForTests(List.of(supply), (Item) null, baritone);

        assertEquals(1, baritone.goToCalls);
        assertEquals(supplyPos, baritone.lastGoToTarget);
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
        runner.triggerRefillForTests((Item) null, List.of(supply), new NoOpBaritoneFacade());
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

        controller.initiateWithSuppliesForTests(List.of(supply), (Item) null, baritone);
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

        controller.initiateWithSuppliesForTests(List.of(supply), (Item) null, baritone);
        assertEquals(GroundedRefillController.RefillState.NAVIGATING, controller.state());
        int cancelCallsBefore = baritone.cancelCalls;

        controller.cancel(baritone);

        assertFalse(controller.isActive());
        assertEquals(GroundedRefillController.RefillState.FAILED, controller.state());
        assertTrue(baritone.cancelCalls > cancelCallsBefore);
        assertNotEquals(GroundedRefillController.RefillState.DONE, controller.state());
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
        int cancelCalls;

        @Override
        public CommandResult goTo(BlockPos target) {
            lastGoToTarget = target;
            goToCalls++;
            return CommandResult.success("ok");
        }

        @Override
        public CommandResult goNear(BlockPos target, int range) {
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
