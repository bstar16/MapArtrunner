package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.plan.BuildPlan;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GroundedSchematicBoundsTest {
    @Test
    void derivesWorldBoundsFromLoadedPlanBoundsAndOrigin() {
        BuildPlan plan = new BuildPlan("schem", Path.of("demo.schem"), new Vec3i(7, 1, 9), List.of(), Map.of(), List.of());

        LoadedPlanBounds loaded = LoadedPlanBounds.fromPlan(plan);
        GroundedSchematicBounds bounds = GroundedSchematicBounds.from(loaded, new BlockPos(10, 64, -4));

        assertEquals(0, loaded.minX());
        assertEquals(6, loaded.maxX());
        assertEquals(0, loaded.minZ());
        assertEquals(8, loaded.maxZ());

        assertEquals(new BlockPos(10, 64, -4), bounds.worldMinInclusive());
        assertEquals(new BlockPos(16, 64, 4), bounds.worldMaxInclusive());
    }
}
