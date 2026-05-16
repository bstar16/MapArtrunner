package com.example.mapart.player;

import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ManualAirPlaceTargetingTest {
    private static final BlockPos TARGET = new BlockPos(10, 64, 10);

    @Test
    void moduleDisabledDoesNothing() {
        ManualAirPlacePlan plan = ManualAirPlaceTargeting.resolve(
                context(false, true, false, false, TARGET, 4.0, 5.0),
                world(Set.of(TARGET))
        );

        assertEquals(ManualAirPlaceState.MODULE_DISABLED, plan.state());
        assertFalse(plan.valid());
        assertNull(plan.hitResult());
    }

    @Test
    void disabledWhileRunnerActiveRejectsPlacement() {
        ManualAirPlacePlan plan = ManualAirPlaceTargeting.resolve(
                context(true, true, false, true, TARGET, 4.0, 5.0),
                world(Set.of(TARGET))
        );

        assertEquals(ManualAirPlaceState.RUNNER_ACTIVE_DISABLED, plan.state());
        assertFalse(plan.valid());
    }

    @Test
    void blockItemRequired() {
        ManualAirPlacePlan plan = ManualAirPlaceTargeting.resolve(
                context(true, false, false, false, TARGET, 4.0, 5.0),
                world(Set.of(TARGET))
        );

        assertEquals(ManualAirPlaceState.NO_BLOCK_IN_HAND, plan.state());
        assertFalse(plan.valid());
    }

    @Test
    void customRangeRespected() {
        ManualAirPlacePlan plan = ManualAirPlaceTargeting.resolve(
                context(true, true, false, false, TARGET, 5.1, 5.0),
                world(Set.of(TARGET))
        );

        assertEquals(ManualAirPlaceState.OUT_OF_RANGE, plan.state());
        assertFalse(plan.valid());
    }

    @Test
    void unsupportedAirTargetIsValidWhenReplaceableAndInRange() {
        ManualAirPlacePlan plan = ManualAirPlaceTargeting.resolve(
                context(true, true, false, false, TARGET, 4.0, 5.0),
                world(Set.of(TARGET))
        );

        assertEquals(ManualAirPlaceState.VALID, plan.state());
        assertTrue(plan.valid());
    }

    @Test
    void reservedHotbarSettingDoesNotDisableManualAirPlaceTargeting() {
        ManualAirPlacePlan plan = ManualAirPlaceTargeting.resolve(
                context(true, true, false, false, TARGET, 4.0, 5.0),
                world(Set.of(TARGET))
        );

        assertEquals(ManualAirPlaceState.VALID, plan.state());
        assertTrue(plan.valid());
    }

    @Test
    void noAdjacentSupportStillAllowsManualAirPlace() {
        ManualAirPlacePlan plan = ManualAirPlaceTargeting.resolve(
                context(true, true, false, false, TARGET, 4.0, 5.0),
                world(Set.of(TARGET))
        );

        assertEquals(ManualAirPlaceState.VALID, plan.state());
        assertTrue(plan.valid());
    }

    @Test
    void targetNotReplaceableRejectsPlacement() {
        ManualAirPlacePlan plan = ManualAirPlaceTargeting.resolve(
                context(true, true, false, false, TARGET, 4.0, 5.0),
                world(Set.of())
        );

        assertEquals(ManualAirPlaceState.TARGET_NOT_REPLACEABLE, plan.state());
        assertFalse(plan.valid());
    }

    @Test
    void manualAirPlaceHitResultUsesTargetBlockPos() {
        ManualAirPlacePlan plan = ManualAirPlaceTargeting.resolve(
                context(true, true, false, false, TARGET, 4.0, 5.0),
                world(Set.of(TARGET))
        );

        BlockHitResult hit = plan.hitResult();
        assertEquals(TARGET, hit.getBlockPos());
        assertEquals(Direction.NORTH, hit.getSide());
        assertEquals(Vec3d.ofCenter(TARGET), hit.getPos());
    }

    private static ManualAirPlaceTargeting.TargetContext context(
            boolean enabled,
            boolean blockInHand,
            boolean requireSneak,
            boolean runnerActive,
            BlockPos targetPos,
            double targetDistance,
            double range
    ) {
        return new ManualAirPlaceTargeting.TargetContext(
                enabled,
                false,
                blockInHand,
                requireSneak,
                false,
                true,
                runnerActive,
                targetPos,
                targetDistance,
                range,
                Direction.NORTH
        );
    }

    private static ManualAirPlaceTargeting.WorldLookup world(Set<BlockPos> replaceable) {
        return replaceable::contains;
    }
}
