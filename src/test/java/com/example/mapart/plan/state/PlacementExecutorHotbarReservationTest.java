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
    void automatedPlacementSwapNeverChoosesReservedHotbarSlot() {
        boolean[] emptyHotbar = {true, true, true, true, true, false, false, false, false};

        assertEquals(5, PlacementExecutor.firstPreferredSwapHotbarSlotForTests(0, emptyHotbar, 5));
    }

    @Test
    void automatedPlacementSwapCanUseOnlySlotNineWhenEightReserved() {
        boolean[] emptyHotbar = {true, true, true, true, true, true, true, true, true};

        assertEquals(8, PlacementExecutor.firstPreferredSwapHotbarSlotForTests(0, emptyHotbar, 8));
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
