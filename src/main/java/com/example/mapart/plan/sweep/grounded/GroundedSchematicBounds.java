package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.plan.BuildPlan;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

import java.util.Objects;

public record GroundedSchematicBounds(
        BlockPos worldMinInclusive,
        BlockPos worldMaxInclusive,
        BlockPos relativeMinInclusive,
        BlockPos relativeMaxInclusive
) {
    public GroundedSchematicBounds {
        Objects.requireNonNull(worldMinInclusive, "worldMinInclusive");
        Objects.requireNonNull(worldMaxInclusive, "worldMaxInclusive");
        Objects.requireNonNull(relativeMinInclusive, "relativeMinInclusive");
        Objects.requireNonNull(relativeMaxInclusive, "relativeMaxInclusive");

        if (worldMinInclusive.getX() > worldMaxInclusive.getX()
                || worldMinInclusive.getY() > worldMaxInclusive.getY()
                || worldMinInclusive.getZ() > worldMaxInclusive.getZ()) {
            throw new IllegalArgumentException("world min bounds must be <= world max bounds");
        }

        if (relativeMinInclusive.getX() > relativeMaxInclusive.getX()
                || relativeMinInclusive.getY() > relativeMaxInclusive.getY()
                || relativeMinInclusive.getZ() > relativeMaxInclusive.getZ()) {
            throw new IllegalArgumentException("relative min bounds must be <= relative max bounds");
        }
    }

    public static GroundedSchematicBounds from(BuildPlan plan, BlockPos origin) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(origin, "origin");

        Vec3i dimensions = plan.dimensions();
        if (dimensions.getX() <= 0 || dimensions.getY() <= 0 || dimensions.getZ() <= 0) {
            throw new IllegalArgumentException("plan dimensions must be positive");
        }

        BlockPos relativeMin = BlockPos.ORIGIN;
        BlockPos relativeMax = new BlockPos(dimensions.getX() - 1, dimensions.getY() - 1, dimensions.getZ() - 1);

        BlockPos worldMin = origin;
        BlockPos worldMax = origin.add(relativeMax);

        return new GroundedSchematicBounds(worldMin, worldMax, relativeMin, relativeMax);
    }

    public int xSpan() {
        return worldMaxInclusive.getX() - worldMinInclusive.getX() + 1;
    }

    public int zSpan() {
        return worldMaxInclusive.getZ() - worldMinInclusive.getZ() + 1;
    }
}
