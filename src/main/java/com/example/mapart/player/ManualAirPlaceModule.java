package com.example.mapart.player;

import com.example.mapart.plan.sweep.grounded.GroundedSingleLaneDebugRunner;
import com.example.mapart.runtime.MapArtRuntime;
import com.example.mapart.settings.MapartSettings;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class ManualAirPlaceModule {
    private static ManualAirPlacePlan lastPlan =
            new ManualAirPlacePlan(ManualAirPlaceState.MODULE_DISABLED, null, null);

    private ManualAirPlaceModule() {
    }

    public static boolean tryManualPlace(MinecraftClient client) {
        Hand hand = selectBlockHand(client);
        ManualAirPlacePlan plan = resolveCurrentTarget(client, hand);
        lastPlan = plan;
        if (!plan.valid() || hand == null || client == null || client.player == null || client.interactionManager == null) {
            return false;
        }

        ActionResult result = client.interactionManager.interactBlock(client.player, hand, plan.hitResult());
        if (result != null && result.isAccepted()) {
            client.player.swingHand(hand);
            return true;
        }
        return false;
    }

    public static ManualAirPlacePlan resolveCurrentTarget(MinecraftClient client) {
        return resolveCurrentTarget(client, selectBlockHand(client));
    }

    public static ManualAirPlacePlan lastPlan() {
        return lastPlan;
    }

    private static ManualAirPlacePlan resolveCurrentTarget(MinecraftClient client, Hand hand) {
        MapartSettings settings = MapArtRuntime.settingsStore() == null
                ? MapartSettings.defaults()
                : MapArtRuntime.settingsStore().current();

        if (client == null || client.player == null || client.world == null) {
            return new ManualAirPlacePlan(ManualAirPlaceState.OUT_OF_RANGE, null, null);
        }

        double range = settings.manualAirPlaceUseCustomRange()
                ? settings.manualAirPlaceCustomRange()
                : vanillaBlockInteractionRange(client.player);
        RayTarget rayTarget = rayTarget(client, range);
        boolean blockInHand = hand != null && stackInHand(client.player, hand).getItem() instanceof BlockItem;

        ManualAirPlaceTargeting.TargetContext context = new ManualAirPlaceTargeting.TargetContext(
                settings.manualAirPlaceEnabled(),
                client.currentScreen != null,
                blockInHand,
                settings.manualAirPlaceRequireSneak(),
                client.player.isSneaking(),
                settings.manualAirPlaceDisableWhileRunnerActive(),
                runnerActive(),
                rayTarget.targetPos(),
                rayTarget.distance(),
                range,
                rayTarget.side()
        );
        return ManualAirPlaceTargeting.resolve(context, new ClientWorldLookup(client.world));
    }

    private static Hand selectBlockHand(MinecraftClient client) {
        if (client == null || client.player == null) {
            return null;
        }
        if (client.player.getMainHandStack().getItem() instanceof BlockItem) {
            return Hand.MAIN_HAND;
        }
        if (client.player.getOffHandStack().getItem() instanceof BlockItem) {
            return Hand.OFF_HAND;
        }
        return null;
    }

    private static ItemStack stackInHand(ClientPlayerEntity player, Hand hand) {
        return hand == Hand.OFF_HAND ? player.getOffHandStack() : player.getMainHandStack();
    }

    private static double vanillaBlockInteractionRange(ClientPlayerEntity player) {
        return player.getBlockInteractionRange();
    }

    private static RayTarget rayTarget(MinecraftClient client, double range) {
        Entity cameraEntity = client.getCameraEntity();
        if (cameraEntity == null || Double.isNaN(range) || range < 0.0) {
            return new RayTarget(null, Double.NaN, null);
        }

        HitResult hit = cameraEntity.raycast(range, 1.0f, false);
        if (hit == null) {
            return new RayTarget(null, Double.NaN, null);
        }

        BlockPos targetPos = BlockPos.ofFloored(hit.getPos());
        double distance = client.player.getEyePos().distanceTo(hit.getPos());
        Vec3d look = cameraEntity.getRotationVec(1.0f);
        Direction side = Direction.getFacing(look.x, look.y, look.z).getOpposite();
        return new RayTarget(targetPos, distance, side);
    }

    private static boolean runnerActive() {
        GroundedSingleLaneDebugRunner runner = MapArtRuntime.groundedSingleLaneDebugRunner();
        return runner != null
                && (runner.status().active()
                || runner.getRefillController().isActive()
                || runner.getRecoveryState().isActive());
    }

    private record RayTarget(BlockPos targetPos, double distance, Direction side) {
    }

    private record ClientWorldLookup(ClientWorld world) implements ManualAirPlaceTargeting.WorldLookup {
        @Override
        public boolean isReplaceable(BlockPos pos) {
            BlockState state = world.getBlockState(pos);
            return state.isReplaceable();
        }
    }
}
