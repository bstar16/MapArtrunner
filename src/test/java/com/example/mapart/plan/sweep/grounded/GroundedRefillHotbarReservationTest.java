package com.example.mapart.plan.sweep.grounded;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundedRefillHotbarReservationTest {
    @Test
    void refillTransferPreservesCurrentInventoryBehaviorWhenNoneReserved() {
        assertEquals(
                9,
                GroundedRefillController.chooseRefillTransferInventorySlotForTests(0, Set.of(9, 10), Map.of())
                        .orElseThrow()
        );
    }

    @Test
    void refillTransferPrefersMainInventoryOverUnreservedHotbar() {
        assertEquals(
                9,
                GroundedRefillController.chooseRefillTransferInventorySlotForTests(8, Set.of(8, 9), Map.of())
                        .orElseThrow()
        );
    }

    @Test
    void refillTransferCanUseMainInventoryWhenOnlyAllowedHotbarSlotIsFull() {
        assertEquals(
                12,
                GroundedRefillController.chooseRefillTransferInventorySlotForTests(8, Set.of(12), Map.of())
                        .orElseThrow()
        );
    }

    @Test
    void refillTransferAvoidsReservedHotbarSlots() {
        assertEquals(
                5,
                GroundedRefillController.chooseRefillTransferInventorySlotForTests(5, Set.of(0, 1, 2, 3, 4, 5), Map.of())
                        .orElseThrow()
        );
    }

    @Test
    void refillTransferDoesNotRelyOnlyOnUnreservedHotbarStagingSlots() {
        assertEquals(
                15,
                GroundedRefillController.chooseRefillTransferInventorySlotForTests(8, Set.of(15, 16), Map.of())
                        .orElseThrow()
        );
    }

    @Test
    void refillTransferDoesNotReportFullWhileMainInventoryCanMerge() {
        assertEquals(
                20,
                GroundedRefillController.chooseRefillTransferInventorySlotForTests(8, Set.of(), Map.of(8, true, 20, true))
                        .orElseThrow()
        );
    }

    @Test
    void refillTransferNeverChoosesReservedMatchingHotbarStack() {
        assertEquals(
                6,
                GroundedRefillController.chooseRefillTransferInventorySlotForTests(5, Set.of(), Map.of(2, true, 6, true))
                        .orElseThrow()
        );
    }

    @Test
    void refillTransferReportsEmptyOnlyWhenAllowedHotbarAndMainInventoryAreFull() {
        assertTrue(GroundedRefillController.chooseRefillTransferInventorySlotForTests(8, Set.of(), Map.of()).isEmpty());
    }
}
