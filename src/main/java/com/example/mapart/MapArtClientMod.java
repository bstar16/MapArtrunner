package com.example.mapart;

import com.example.mapart.plan.compare.PlacementStatusResolver;
import com.example.mapart.render.HudRenderer;
import com.example.mapart.render.SchematicOverlayRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

public class MapArtClientMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PlacementStatusResolver resolver = new PlacementStatusResolver();
        HudRenderCallback.EVENT.register(new HudRenderer(resolver));
        WorldRenderEvents.AFTER_TRANSLUCENT.register(new SchematicOverlayRenderer(resolver));
    }
}
