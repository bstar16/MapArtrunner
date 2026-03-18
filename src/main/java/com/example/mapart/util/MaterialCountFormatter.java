package com.example.mapart.util;

import net.minecraft.item.Item;

public final class MaterialCountFormatter {
    private MaterialCountFormatter() {
    }

    public static String formatCount(int count, Item item) {
        int safeCount = Math.max(0, count);
        int stackSize = 64;
        if (item != null) {
            stackSize = Math.max(1, item.getMaxCount());
        }

        return (safeCount / stackSize) + "+" + (safeCount % stackSize);
    }
}
