package com.example.mapart.inventory;

import java.util.OptionalInt;

public final class HotbarSlotReservations {
    public static final int MIN_RESERVED_HOTBAR_SLOTS = 0;
    public static final int MAX_RESERVED_HOTBAR_SLOTS = 8;
    public static final int HOTBAR_SIZE = 9;

    private HotbarSlotReservations() {
    }

    public static int validateReservedHotbarSlots(int reservedHotbarSlots) {
        if (reservedHotbarSlots < MIN_RESERVED_HOTBAR_SLOTS || reservedHotbarSlots > MAX_RESERVED_HOTBAR_SLOTS) {
            throw new IllegalArgumentException("reservedHotbarSlots must be between 0 and 8.");
        }
        return reservedHotbarSlots;
    }

    public static boolean isAutomatedHotbarSlot(int hotbarSlot, int reservedHotbarSlots) {
        validateReservedHotbarSlots(reservedHotbarSlots);
        return hotbarSlot >= reservedHotbarSlots && hotbarSlot < HOTBAR_SIZE;
    }

    public static boolean isReservedHotbarSlot(int hotbarSlot, int reservedHotbarSlots) {
        validateReservedHotbarSlots(reservedHotbarSlots);
        return hotbarSlot >= 0 && hotbarSlot < reservedHotbarSlots;
    }

    public static boolean isAutomatedInventorySlot(int inventorySlot, int reservedHotbarSlots) {
        if (inventorySlot < 0) {
            return false;
        }
        return inventorySlot >= HOTBAR_SIZE || isAutomatedHotbarSlot(inventorySlot, reservedHotbarSlots);
    }

    public static OptionalInt firstAutomatedHotbarSlot(int reservedHotbarSlots) {
        validateReservedHotbarSlots(reservedHotbarSlots);
        return reservedHotbarSlots < HOTBAR_SIZE ? OptionalInt.of(reservedHotbarSlots) : OptionalInt.empty();
    }

}
