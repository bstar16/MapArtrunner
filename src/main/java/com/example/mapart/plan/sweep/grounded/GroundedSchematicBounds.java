package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.BlockPos;

public record GroundedSchematicBounds(
        LoadedPlanBounds loadedPlanBounds,
        BlockPos origin,
        BlockPos worldMinInclusive,
        BlockPos worldMaxInclusive
) {
    public GroundedSchematicBounds {
        if (worldMinInclusive.getX() > worldMaxInclusive.getX()
                || worldMinInclusive.getY() > worldMaxInclusive.getY()
                || worldMinInclusive.getZ() > worldMaxInclusive.getZ()) {
            throw new IllegalArgumentException("world bounds min must be <= max");
        }
    }

    public static GroundedSchematicBounds from(LoadedPlanBounds loadedPlanBounds, BlockPos origin) {
        BlockPos worldMin = origin.add(loadedPlanBounds.minX(), loadedPlanBounds.minY(), loadedPlanBounds.minZ());
        BlockPos worldMax = origin.add(loadedPlanBounds.maxX(), loadedPlanBounds.maxY(), loadedPlanBounds.maxZ());
        return new GroundedSchematicBounds(loadedPlanBounds, origin.toImmutable(), worldMin.toImmutable(), worldMax.toImmutable());
    }

    public int minX() {
        return worldMinInclusive.getX();
    }

    public int maxX() {
        return worldMaxInclusive.getX();
    }

    public int minY() {
        return worldMinInclusive.getY();
    }

    public int minZ() {
        return worldMinInclusive.getZ();
    }

    public int maxZ() {
        return worldMaxInclusive.getZ();
    }

    public int xSpan() {
        return loadedPlanBounds.xSpan();
    }

    public int zSpan() {
        return loadedPlanBounds.zSpan();
    }
}
