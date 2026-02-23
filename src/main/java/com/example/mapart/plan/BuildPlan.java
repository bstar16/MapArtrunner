package com.example.mapart.plan;

import net.minecraft.block.Block;
import net.minecraft.util.math.Vec3i;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record BuildPlan(
        String sourceFormat,
        Path sourcePath,
        Vec3i dimensions,
        List<Placement> placements,
        Map<Block, Integer> materialCounts,
        List<Region> regions
) {
}
