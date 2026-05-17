package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.inventory.HotbarSlotReservations;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TorchGridPlannerTest {
    @Test
    void producesDeterministicPositionsFor128Area() {
        GroundedSchematicBounds bounds = bounds128();
        TorchGridSettings settings = new TorchGridSettings(true, 12, true, 1);
        TorchGridPlanner planner = new TorchGridPlanner();

        List<TorchGridTarget> first = planner.plan(bounds, settings);
        List<TorchGridTarget> second = planner.plan(bounds, settings);

        assertEquals(first, second);
        assertEquals(121, first.size());
        assertEquals(new BlockPos(10, 65, 20), first.getFirst().torchPos());
        assertEquals(new BlockPos(10, 64, 20), first.getFirst().supportPos());
        assertEquals(new BlockPos(130, 65, 140), first.getLast().torchPos());
    }

    @Test
    void torchGridSpacingIsFixedAt12() {
        GroundedSchematicBounds bounds = bounds128();
        TorchGridSettings settings = new TorchGridSettings(true, 16, true, 1);

        List<TorchGridTarget> targets = new TorchGridPlanner().plan(bounds, settings);

        assertEquals(12, settings.spacing());
        assertEquals(121, targets.size());
        assertEquals(new BlockPos(130, 65, 140), targets.getLast().torchPos());
    }

    @Test
    void torchAndSupportYMatchBuildPlane() {
        List<TorchGridTarget> targets = new TorchGridPlanner().plan(
                bounds128(),
                new TorchGridSettings(true, 12, true, 1)
        );

        assertTrue(targets.stream().allMatch(target -> target.torchPos().getY() == 65));
        assertTrue(targets.stream().allMatch(target -> target.supportPos().getY() == 64));
    }

    @Test
    void readinessRequiresCorrectSupportAndReplaceableTarget() {
        assertEquals(
                TorchGridPlacementController.TorchGridReadiness.READY,
                TorchGridPlacementController.evaluateReadiness(true, true, false)
        );
        assertEquals(
                TorchGridPlacementController.TorchGridReadiness.NOT_READY,
                TorchGridPlacementController.evaluateReadiness(false, true, false)
        );
        assertEquals(
                TorchGridPlacementController.TorchGridReadiness.NOT_READY,
                TorchGridPlacementController.evaluateReadiness(true, false, false)
        );
        assertEquals(
                TorchGridPlacementController.TorchGridReadiness.ALREADY_PRESENT,
                TorchGridPlacementController.evaluateReadiness(false, false, true)
        );
    }

    @Test
    void torchGridTargetsAreNotSchematicPlacements() {
        List<TorchGridTarget> targets = new TorchGridPlanner().plan(
                bounds128(),
                new TorchGridSettings(true, 12, true, 1)
        );

        assertEquals(121, targets.size());
        assertTrue(targets.stream().noneMatch(target -> target.torchPos().equals(target.supportPos())));
    }

    @Test
    void globalHotbarReservationsStillTreatReservedSlotsAsUnavailableForAutomation() {
        assertTrue(HotbarSlotReservations.isReservedHotbarSlot(0, 1));
        assertFalse(HotbarSlotReservations.isAutomatedHotbarSlot(0, 1));
        assertTrue(HotbarSlotReservations.isAutomatedHotbarSlot(1, 1));
    }

    @Test
    void unavailableTorchesAreNonFatalControllerState() {
        TorchGridPlacementController disabled = TorchGridPlacementController.disabled();

        assertDoesNotThrow(() -> disabled.tick(null, null, 0));
        assertEquals(0, disabled.diagnostics().skippedNoTorchCount());
    }

    private static GroundedSchematicBounds bounds128() {
        return new GroundedSchematicBounds(
                new BlockPos(10, 64, 20),
                new BlockPos(10, 64, 20),
                new BlockPos(137, 64, 147)
        );
    }
}
