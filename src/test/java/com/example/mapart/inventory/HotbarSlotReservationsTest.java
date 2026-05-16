package com.example.mapart.inventory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class HotbarSlotReservationsTest {
    @Test
    void reservedZeroPreservesCurrentUsableSlotBehavior() {
        List<Integer> usableHotbar = IntStream.range(0, HotbarSlotReservations.HOTBAR_SIZE)
                .filter(slot -> HotbarSlotReservations.isAutomatedHotbarSlot(slot, 0))
                .boxed()
                .toList();

        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8), usableHotbar);
    }

    @Test
    void reservedFiveExcludesInternalSlotsZeroThroughFour() {
        List<Integer> usableHotbar = IntStream.range(0, HotbarSlotReservations.HOTBAR_SIZE)
                .filter(slot -> HotbarSlotReservations.isAutomatedHotbarSlot(slot, 5))
                .boxed()
                .toList();

        assertEquals(List.of(5, 6, 7, 8), usableHotbar);
        assertTrue(IntStream.range(0, 5).allMatch(slot -> HotbarSlotReservations.isReservedHotbarSlot(slot, 5)));
    }

    @Test
    void reservedEightAllowsOnlyInternalSlotEight() {
        List<Integer> usableHotbar = IntStream.range(0, HotbarSlotReservations.HOTBAR_SIZE)
                .filter(slot -> HotbarSlotReservations.isAutomatedHotbarSlot(slot, 8))
                .boxed()
                .toList();

        assertEquals(List.of(8), usableHotbar);
        assertTrue(IntStream.range(0, 8).allMatch(slot -> HotbarSlotReservations.isReservedHotbarSlot(slot, 8)));
    }

    @Test
    void mainInventorySlotsRemainUsableForAutomatedCounts() {
        assertFalse(HotbarSlotReservations.isAutomatedInventorySlot(4, 5));
        assertTrue(HotbarSlotReservations.isAutomatedInventorySlot(5, 5));
        assertTrue(HotbarSlotReservations.isAutomatedInventorySlot(9, 8));
        assertTrue(HotbarSlotReservations.isAutomatedInventorySlot(35, 8));
    }

    @Test
    void rejectsValuesOutsideZeroThroughEight() {
        assertThrows(IllegalArgumentException.class, () -> HotbarSlotReservations.validateReservedHotbarSlots(-1));
        assertThrows(IllegalArgumentException.class, () -> HotbarSlotReservations.validateReservedHotbarSlots(9));
    }
}
