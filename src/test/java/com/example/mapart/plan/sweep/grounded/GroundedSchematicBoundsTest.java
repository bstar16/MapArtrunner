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
    void derivesWorldBoundsFromPlanDimensionsAndOrigin() {
        BuildPlan plan = new BuildPlan("schem", Path.of("dummy.schem"), new Vec3i(128, 1, 129), List.of(), Map.of(), List.of());
        BlockPos origin = new BlockPos(10, 64, -20);

        GroundedSchematicBounds bounds = GroundedSchematicBounds.fromPlan(plan, origin);

        assertEquals(new BlockPos(10, 64, -20), bounds.worldMinInclusive());
        assertEquals(new BlockPos(137, 64, 108), bounds.worldMaxInclusive());
    }
}
