package com.example.mapart.render;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.compare.PlacementStatusResolver;
import com.example.mapart.plan.compare.PlacementStatusSnapshot;
import com.example.mapart.plan.state.BuildSession;
import com.example.mapart.runtime.MapArtRuntime;
import com.example.mapart.settings.MapartSettings;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class HudRenderer implements HudRenderCallback {
    private final PlacementStatusResolver statusResolver;

    public HudRenderer(PlacementStatusResolver statusResolver) {
        this.statusResolver = statusResolver;
    }

    @Override
    public void onHudRender(DrawContext drawContext, net.minecraft.client.render.RenderTickCounter tickCounter) {
        if (MapArtRuntime.settingsStore() == null || MapArtRuntime.buildPlanService() == null) {
            return;
        }

        MapartSettings settings = MapArtRuntime.settingsStore().current();
        if (!settings.showHud()) {
            return;
        }

        Optional<BuildSession> sessionOptional = MapArtRuntime.buildPlanService().currentSession();
        MinecraftClient client = MinecraftClient.getInstance();
        if (sessionOptional.isEmpty() || client.world == null) {
            return;
        }

        BuildSession session = sessionOptional.get();
        BuildPlan plan = session.getPlan();
        List<String> lines = new ArrayList<>();

        lines.add("MapArtRunner");
        lines.add("State: " + session.getState());
        lines.add("Plan: " + plan.sourcePath().getFileName());
        lines.add("Size: " + plan.dimensions().getX() + "x" + plan.dimensions().getY() + "x" + plan.dimensions().getZ());
        lines.add("Region: " + session.getProgress().getCurrentRegionIndex() + "/" + plan.regions().size());
        lines.add("Placement: " + session.getProgress().getCurrentPlacementIndex() + "/" + plan.placements().size());

        if (session.getOrigin() == null) {
            lines.add("Origin: not set");
        } else {
            lines.add("Origin: set @ " + session.getOrigin().toShortString());
        }

        Optional<PlacementStatusSnapshot> nextTarget = statusResolver.nextTarget(client.world, session);
        if (nextTarget.isPresent()) {
            PlacementStatusSnapshot snapshot = nextTarget.get();
            lines.add("Next block: " + Registries.BLOCK.getId(snapshot.placement().block()));
            lines.add("Next pos: " + snapshot.absolutePos().toShortString());
        } else {
            lines.add("Next block: n/a");
        }

        if (settings.hudCompact() && lines.size() > 6) {
            lines = lines.subList(0, 6);
        }

        int x = settings.hudX();
        int y = settings.hudY();
        int lineHeight = 10;
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, client.textRenderer.getWidth(line));
        }

        int height = lines.size() * lineHeight + 6;
        drawContext.fill(x - 4, y - 4, x + width + 4, y + height, 0x66000000);
        for (int i = 0; i < lines.size(); i++) {
            drawContext.drawText(client.textRenderer, Text.literal(lines.get(i)), x, y + (i * lineHeight), 0xFFFFFF, false);
        }
    }
}
