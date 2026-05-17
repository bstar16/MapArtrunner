package com.example.mapart.plan.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlacementExecutorHotbarReservationTest {
    @Test
    void automatedPlacementSwapPreservesCurrentSlotBehaviorWhenNoneReserved() {
        boolean[] emptyHotbar = {true, false, false, false, false, false, false, false, false};

        assertEquals(0, PlacementExecutor.firstPreferredSwapHotbarSlotForTests(0, emptyHotbar, 0));
    }

    @Test
    void normalPlacementSelectionRefusesMatchingReservedHotbarSlot() {
        boolean[] matchingHotbar = {true, false, false, false, false, false, false, false, false};

        assertEquals(-1, PlacementExecutor.selectMatchingHotbarSlotForTests(0, matchingHotbar, 5, false));
    }

    @Test
    void automatedPlacementSwapNeverChoosesReservedHotbarSlot() {
        boolean[] emptyHotbar = {true, true, true, true, true, false, false, false, false};

        assertEquals(5, PlacementExecutor.firstPreferredSwapHotbarSlotForTests(0, emptyHotbar, 5));
    }

    @Test
    void normalPlacementSwapNeverMovesMapartBlockIntoReservedHotbarSlot() {
        boolean[] emptyHotbar = {true, true, true, true, true, true, false, false, false};

        assertEquals(5, PlacementExecutor.firstPreferredSwapHotbarSlotForTests(0, emptyHotbar, 5));
    }

    @Test
    void automatedPlacementSwapCanUseOnlySlotNineWhenEightReserved() {
        boolean[] emptyHotbar = {true, true, true, true, true, true, true, true, true};

        assertEquals(8, PlacementExecutor.firstPreferredSwapHotbarSlotForTests(0, emptyHotbar, 8));
    }

    @Test
    void torchGridSelectionCanUseTorchAlreadyInReservedHotbarSlot() {
        boolean[] matchingHotbar = {true, false, false, false, false, false, false, false, false};

        assertEquals(0, PlacementExecutor.selectMatchingHotbarSlotForTests(0, matchingHotbar, 5, true));
    }

    @Test
    void torchGridSwapDoesNotMoveTorchIntoReservedHotbarSlot() {
        boolean[] emptyHotbar = {true, true, true, true, true, false, false, false, false};

        assertEquals(5, PlacementExecutor.firstPreferredSwapHotbarSlotForTests(0, emptyHotbar, 5));
    }

    @Test
    void torchGridSelectionLeavesNonTorchReservedSlotAlone() {
        boolean[] matchingHotbar = {false, false, false, false, false, true, false, false, false};

        assertEquals(5, PlacementExecutor.selectMatchingHotbarSlotForTests(0, matchingHotbar, 5, true));
    }

    @Test
    void torchGridSelectionDoesNotTreatNonTorchReservedSlotAsUsable() {
        boolean[] matchingHotbar = {false, false, false, false, false, false, false, false, false};

        assertEquals(-1, PlacementExecutor.selectMatchingHotbarSlotForTests(0, matchingHotbar, 5, true));
    }

    @Test
    void torchGridSelectionStillUsesUnreservedHotbarTorch() {
        boolean[] matchingHotbar = {false, false, false, false, false, false, true, false, false};

        assertEquals(6, PlacementExecutor.selectMatchingHotbarSlotForTests(0, matchingHotbar, 5, true));
    }

    @Test
    void torchGridMainInventoryTorchStillSwapsOnlyIntoUnreservedHotbarSlot() {
        boolean[] emptyHotbar = {true, true, true, true, true, false, true, false, false};

        assertEquals(6, PlacementExecutor.firstPreferredSwapHotbarSlotForTests(0, emptyHotbar, 5));
    }

    @Test
    void torchGridSelectionAllowsReservedTorchWhenEightSlotsReserved() {
        boolean[] matchingHotbar = {false, false, false, false, false, false, false, true, false};

        assertEquals(7, PlacementExecutor.selectMatchingHotbarSlotForTests(0, matchingHotbar, 8, true));
    }

    @Test
    void normalPlacementSelectionDoesNotUseReservedSlotForSchematicBlocksWhenEightSlotsReserved() {
        boolean[] matchingHotbar = {false, false, false, false, false, false, false, true, false};

        assertEquals(-1, PlacementExecutor.selectMatchingHotbarSlotForTests(0, matchingHotbar, 8, false));
    }

    @Test
    void alreadySelectedRequiredItemDoesNotRequireHotbarSwapDelay() {
        assertEquals(false, PlacementExecutor.shouldDelayPlacementAfterAutomatedHotbarSwapForTests(true, false));
    }

    @Test
    void mainInventorySwapRequiresHotbarSwapDelay() {
        assertEquals(true, PlacementExecutor.shouldDelayPlacementAfterAutomatedHotbarSwapForTests(true, true));
    }

    @Test
    void unavailableSelectionDoesNotMaskMissingItemAsHotbarSwapDelay() {
        assertEquals(false, PlacementExecutor.shouldDelayPlacementAfterAutomatedHotbarSwapForTests(false, true));
    }
}
