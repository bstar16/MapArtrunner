package com.example.mapart.render;

import com.example.mapart.player.ManualAirPlaceModule;
import com.example.mapart.player.ManualAirPlacePlan;
import com.example.mapart.player.ManualAirPlaceState;
import com.example.mapart.runtime.MapArtRuntime;
import com.example.mapart.settings.MapartSettings;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

public class ManualAirPlaceOverlayRenderer implements WorldRenderEvents.BeforeTranslucent {
    private static final double OUTLINE_EXPANSION = 0.004D;
    private static final VoxelShape OUTLINE_SHAPE = VoxelShapes.cuboid(
            -OUTLINE_EXPANSION, -OUTLINE_EXPANSION, -OUTLINE_EXPANSION,
            1.0D + OUTLINE_EXPANSION, 1.0D + OUTLINE_EXPANSION, 1.0D + OUTLINE_EXPANSION
    );

    @Override
    public void beforeTranslucent(WorldRenderContext context) {
        if (MapArtRuntime.settingsStore() == null) {
            return;
        }

        MapartSettings settings = MapArtRuntime.settingsStore().current();
        if (!settings.manualAirPlaceEnabled() || !settings.manualAirPlaceRender()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) {
            return;
        }

        ManualAirPlacePlan plan = ManualAirPlaceModule.resolveCurrentTarget(client);
        BlockPos targetPos = plan.targetPos();
        if (targetPos == null) {
            return;
        }

        MatrixStack matrices = context.matrices();
        VertexConsumer lines = context.consumers().getBuffer(RenderLayers.secondaryBlockOutline());
        Vec3d camera = client.gameRenderer.getCamera().getCameraPos();
        int color = color(plan.state());
        double minX = targetPos.getX() - camera.x;
        double minY = targetPos.getY() - camera.y;
        double minZ = targetPos.getZ() - camera.z;
        VertexRendering.drawOutline(matrices, lines, OUTLINE_SHAPE, minX, minY, minZ, color, 1.75f);
    }

    private static int color(ManualAirPlaceState state) {
        return switch (state) {
            case VALID -> 0xD94CAF50;
            case NO_VALID_SUPPORT -> 0xD9FF9800;
            case TARGET_NOT_REPLACEABLE -> 0xD9EF5350;
            case NO_BLOCK_IN_HAND, GUI_OPEN, REQUIRE_SNEAK, RUNNER_ACTIVE_DISABLED, OUT_OF_RANGE, MODULE_DISABLED -> 0xD990A4AE;
        };
    }
}
