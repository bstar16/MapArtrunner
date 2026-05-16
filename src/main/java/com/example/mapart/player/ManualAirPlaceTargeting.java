package com.example.mapart.player;

import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;

public final class ManualAirPlaceTargeting {
    private ManualAirPlaceTargeting() {
    }

    public static ManualAirPlacePlan resolve(TargetContext context, WorldLookup world) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(world, "world");

        if (!context.enabled()) {
            return rejected(ManualAirPlaceState.MODULE_DISABLED, context.targetPos());
        }
        if (context.guiOpen()) {
            return rejected(ManualAirPlaceState.GUI_OPEN, context.targetPos());
        }
        if (!context.blockInHand()) {
            return rejected(ManualAirPlaceState.NO_BLOCK_IN_HAND, context.targetPos());
        }
        if (context.requireSneak() && !context.sneaking()) {
            return rejected(ManualAirPlaceState.REQUIRE_SNEAK, context.targetPos());
        }
        if (context.disableWhileRunnerActive() && context.runnerActive()) {
            return rejected(ManualAirPlaceState.RUNNER_ACTIVE_DISABLED, context.targetPos());
        }
        if (context.targetPos() == null
                || Double.isNaN(context.targetDistance())
                || context.targetDistance() > context.range()) {
            return rejected(ManualAirPlaceState.OUT_OF_RANGE, context.targetPos());
        }
        if (!world.isReplaceable(context.targetPos())) {
            return rejected(ManualAirPlaceState.TARGET_NOT_REPLACEABLE, context.targetPos());
        }

        BlockHitResult hitResult = buildTargetHit(context.targetPos(), context.side());
        return new ManualAirPlacePlan(ManualAirPlaceState.VALID, context.targetPos(), hitResult);
    }

    private static ManualAirPlacePlan rejected(ManualAirPlaceState state, BlockPos targetPos) {
        return new ManualAirPlacePlan(state, targetPos, null);
    }

    static BlockHitResult buildTargetHit(BlockPos targetPos, Direction side) {
        Direction resolvedSide = side == null ? Direction.UP : side;
        return new BlockHitResult(Vec3d.ofCenter(targetPos), resolvedSide, targetPos, false);
    }

    public record TargetContext(
            boolean enabled,
            boolean guiOpen,
            boolean blockInHand,
            boolean requireSneak,
            boolean sneaking,
            boolean disableWhileRunnerActive,
            boolean runnerActive,
            BlockPos targetPos,
            double targetDistance,
            double range,
            Direction side
    ) {
    }

    public interface WorldLookup {
        boolean isReplaceable(BlockPos pos);
    }
}
