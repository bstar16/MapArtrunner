package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.plan.BuildPlan;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

import java.util.Objects;

public record GroundedSchematicBounds(
        BlockPos origin,
        BlockPos worldMinInclusive,
        BlockPos worldMaxInclusive
) {
    public GroundedSchematicBounds {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(worldMinInclusive, "worldMinInclusive");
        Objects.requireNonNull(worldMaxInclusive, "worldMaxInclusive");

        if (worldMinInclusive.getX() > worldMaxInclusive.getX()) {
            throw new IllegalArgumentException("worldMinInclusive.x must be <= worldMaxInclusive.x");
        }
        if (worldMinInclusive.getY() > worldMaxInclusive.getY()) {
            throw new IllegalArgumentException("worldMinInclusive.y must be <= worldMaxInclusive.y");
        }
        if (worldMinInclusive.getZ() > worldMaxInclusive.getZ()) {
            throw new IllegalArgumentException("worldMinInclusive.z must be <= worldMaxInclusive.z");
        }
    }

    public static GroundedSchematicBounds fromPlan(BuildPlan plan, BlockPos origin) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(origin, "origin");

        Vec3i dimensions = plan.dimensions();
        if (dimensions.getX() <= 0 || dimensions.getY() <= 0 || dimensions.getZ() <= 0) {
            throw new IllegalArgumentException("plan dimensions must be positive");
        }

        BlockPos min = origin.toImmutable();
        BlockPos max = origin.add(dimensions.getX() - 1, dimensions.getY() - 1, dimensions.getZ() - 1).toImmutable();
        return new GroundedSchematicBounds(origin.toImmutable(), min, max);
    }

    public int minX() { return worldMinInclusive.getX(); }
    public int maxX() { return worldMaxInclusive.getX(); }
    public int minY() { return worldMinInclusive.getY(); }
    public int maxY() { return worldMaxInclusive.getY(); }
    public int minZ() { return worldMinInclusive.getZ(); }
    public int maxZ() { return worldMaxInclusive.getZ(); }

    public int xSpan() { return maxX() - minX() + 1; }
    public int zSpan() { return maxZ() - minZ() + 1; }
}
