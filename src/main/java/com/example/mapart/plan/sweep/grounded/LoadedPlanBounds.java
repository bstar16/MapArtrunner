package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.plan.BuildPlan;
import net.minecraft.util.math.Vec3i;

public record LoadedPlanBounds(
        int minX,
        int maxX,
        int minY,
        int maxY,
        int minZ,
        int maxZ
) {
    public LoadedPlanBounds {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("min bounds must be <= max bounds");
        }
    }

    public static LoadedPlanBounds fromPlan(BuildPlan plan) {
        Vec3i dimensions = plan.dimensions();
        if (dimensions.getX() < 1 || dimensions.getY() < 1 || dimensions.getZ() < 1) {
            throw new IllegalArgumentException("plan dimensions must all be >= 1");
        }

        return new LoadedPlanBounds(0, dimensions.getX() - 1, 0, dimensions.getY() - 1, 0, dimensions.getZ() - 1);
    }

    public int xSpan() {
        return maxX - minX + 1;
    }

    public int zSpan() {
        return maxZ - minZ + 1;
    }
}
