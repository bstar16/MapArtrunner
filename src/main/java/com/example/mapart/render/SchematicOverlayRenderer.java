package com.example.mapart.render;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.compare.PlacementStatus;
import com.example.mapart.plan.compare.PlacementStatusResolver;
import com.example.mapart.plan.compare.PlacementStatusSnapshot;
import com.example.mapart.plan.state.BuildSession;
import com.example.mapart.runtime.MapArtRuntime;
import com.example.mapart.settings.MapartSettings;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class SchematicOverlayRenderer implements WorldRenderEvents.BeforeTranslucent {
    private static final double OUTLINE_EXPANSION = 0.002D;
    private static final int RENDER_DISTANCE_MARGIN_CHUNKS = 1;
    private static final net.minecraft.util.shape.VoxelShape OUTLINE_SHAPE = VoxelShapes.cuboid(
            -OUTLINE_EXPANSION, -OUTLINE_EXPANSION, -OUTLINE_EXPANSION,
            1.0D + OUTLINE_EXPANSION, 1.0D + OUTLINE_EXPANSION, 1.0D + OUTLINE_EXPANSION
    );
    private final PlacementStatusResolver statusResolver;
    private OverlayCache overlayCache = OverlayCache.empty();

    public SchematicOverlayRenderer(PlacementStatusResolver statusResolver) {
        this.statusResolver = statusResolver;
    }

    @Override
    public void beforeTranslucent(WorldRenderContext context) {
        if (MapArtRuntime.buildPlanService() == null || MapArtRuntime.settingsStore() == null) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        Optional<BuildSession> sessionOptional = MapArtRuntime.buildPlanService().currentSession();
        if (client.world == null || sessionOptional.isEmpty()) {
            return;
        }

        MapartSettings settings = MapArtRuntime.settingsStore().current();
        if (!settings.showSchematicOverlay()) {
            return;
        }

        BuildSession session = sessionOptional.get();
        BlockPos origin = session.getOrigin();
        if (origin == null) {
            ClientPlayerEntity player = client.player;
            if (player == null) {
                return;
            }
            origin = player.getBlockPos();
            session = cloneWithPreviewOrigin(session, origin);
        }

        String dimensionKey = client.world.getRegistryKey().getValue().toString();
        OverlayCache cache = rebuildOverlayCacheIfNeeded(session, dimensionKey, settings);
        if (cache.isEmpty()) {
            return;
        }

        MatrixStack matrices = context.matrices();
        VertexConsumer lines = context.consumers().getBuffer(RenderLayers.secondaryBlockOutline());
        Vec3d camera = client.gameRenderer.getCamera().getCameraPos();
        int nextIndex = session.getCurrentPlacementIndex();

        ClientPlayerEntity player = client.player;
        OverlayRegion playerRegion = player == null ? null : currentPlayerOverlayRegion(player.getBlockPos());
        int renderDistance = client.options.getViewDistance().getValue() + RENDER_DISTANCE_MARGIN_CHUNKS;

        if (settings.overlayCurrentRegionOnly()) {
            if (playerRegion == null) {
                return;
            }

            renderTargets(cache.targetsForRegion(playerRegion), client, session, settings, matrices, lines, camera, nextIndex);
            return;
        }

        for (Map.Entry<OverlayRegion, List<OverlayTarget>> entry : cache.targetsByRegion().entrySet()) {
            OverlayRegion region = entry.getKey();
            if (playerRegion != null && !isWithinRenderDistance(region, playerRegion, renderDistance)) {
                continue;
            }
            if (!client.world.isChunkLoaded(region.x, region.z)) {
                continue;
            }

            renderTargets(entry.getValue(), client, session, settings, matrices, lines, camera, nextIndex);
        }
    }

    private void renderTargets(
            List<OverlayTarget> targets,
            MinecraftClient client,
            BuildSession session,
            MapartSettings settings,
            MatrixStack matrices,
            VertexConsumer lines,
            Vec3d camera,
            int nextIndex
    ) {
        if (targets.isEmpty() || client.world == null) {
            return;
        }

        for (OverlayTarget target : targets) {
            if (!shouldRenderOverlayBlock(client, target)) {
                continue;
            }

            PlacementStatusSnapshot snapshot = statusResolver.resolveSnapshot(
                    client.world,
                    target.placement(),
                    target.absolutePos(),
                    target.index(),
                    nextIndex
            );
            if (settings.overlayShowOnlyIncorrect() && snapshot.status() == PlacementStatus.CORRECT && !snapshot.nextTarget()) {
                continue;
            }

            int baseColor = pickColor(snapshot);
            float r = ((baseColor >> 16) & 0xFF) / 255.0f;
            float g = ((baseColor >> 8) & 0xFF) / 255.0f;
            float b = (baseColor & 0xFF) / 255.0f;

            BlockPos pos = snapshot.absolutePos();
            double minX = pos.getX() - camera.x;
            double minY = pos.getY() - camera.y;
            double minZ = pos.getZ() - camera.z;
            int argbColor = (0xD9 << 24) | (((int) (r * 255.0f)) << 16) | (((int) (g * 255.0f)) << 8) | ((int) (b * 255.0f));
            VertexRendering.drawOutline(matrices, lines, OUTLINE_SHAPE, minX, minY, minZ, argbColor, 1.5f);
        }
    }

    private static int pickColor(PlacementStatusSnapshot snapshot) {
        if (snapshot.nextTarget()) {
            return 0xFFD54F;
        }

        return switch (snapshot.status()) {
            case CORRECT -> 0x4CAF50;
            case INCORRECT, MISSING -> 0xEF5350;
            case PENDING -> 0x90A4AE;
        };
    }

    OverlayCache rebuildOverlayCacheIfNeeded(BuildSession session, String dimensionKey, MapartSettings settings) {
        CacheKey key = new CacheKey(
                session.getPlan(),
                session.getOrigin(),
                dimensionKey,
                settings.overlayCurrentRegionOnly(),
                settings.overlayShowOnlyIncorrect()
        );
        if (!overlayCache.matches(key)) {
            overlayCache = buildOverlayCache(key, session);
        }

        return overlayCache;
    }

    static OverlayCache buildOverlayCache(CacheKey key, BuildSession session) {
        BuildPlan plan = session.getPlan();
        BlockPos origin = session.getOrigin();
        List<OverlayTarget> allTargets = new ArrayList<>(plan.placements().size());
        Map<OverlayRegion, List<OverlayTarget>> targetsByRegion = new LinkedHashMap<>();

        for (int i = 0; i < plan.placements().size(); i++) {
            Placement placement = plan.placements().get(i);
            if (placement.relativePos() == null) {
                continue;
            }

            BlockPos absolutePos = origin.add(placement.relativePos());
            OverlayRegion region = overlayRegionForBlock(absolutePos);
            OverlayTarget target = new OverlayTarget(i, placement, absolutePos, region);
            allTargets.add(target);
            targetsByRegion.computeIfAbsent(region, ignored -> new ArrayList<>()).add(target);
        }

        return new OverlayCache(key, List.copyOf(allTargets), freezeBuckets(targetsByRegion), allTargets.size());
    }

    private static Map<OverlayRegion, List<OverlayTarget>> freezeBuckets(Map<OverlayRegion, List<OverlayTarget>> targetsByRegion) {
        Map<OverlayRegion, List<OverlayTarget>> frozen = new LinkedHashMap<>(targetsByRegion.size());
        for (Map.Entry<OverlayRegion, List<OverlayTarget>> entry : targetsByRegion.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(frozen);
    }

    static OverlayRegion currentPlayerOverlayRegion(BlockPos playerPos) {
        // "Current region" is the player's current render chunk region, not the
        // schematic origin, run start, lane start, or build-progress region.
        return overlayRegionForBlock(playerPos);
    }

    static OverlayRegion overlayRegionForBlock(BlockPos pos) {
        return new OverlayRegion(pos.getX() >> 4, pos.getZ() >> 4);
    }

    static boolean isWithinRenderDistance(OverlayRegion region, OverlayRegion playerRegion, int renderDistanceChunks) {
        return Math.abs(region.x - playerRegion.x) <= renderDistanceChunks
                && Math.abs(region.z - playerRegion.z) <= renderDistanceChunks;
    }

    static boolean shouldRenderOverlayBlock(MinecraftClient client, OverlayTarget target) {
        if (client.world == null) {
            return false;
        }

        OverlayRegion region = target.region();
        return client.world.isChunkLoaded(region.x, region.z);
    }

    private static BuildSession cloneWithPreviewOrigin(BuildSession session, BlockPos origin) {
        BuildSession preview = new BuildSession(session.getPlan());
        preview.setCurrentPlacementIndex(session.getCurrentPlacementIndex());
        preview.setCurrentRegionIndex(session.getCurrentRegionIndex());
        preview.setOrigin(origin);
        return preview;
    }

    record OverlayRegion(int x, int z) {
    }

    record OverlayTarget(int index, Placement placement, BlockPos absolutePos, OverlayRegion region) {
    }

    static final class CacheKey {
        private final BuildPlan plan;
        private final BlockPos origin;
        private final String dimensionKey;
        private final boolean currentRegionOnly;
        private final boolean showOnlyIncorrect;

        CacheKey(BuildPlan plan, BlockPos origin, String dimensionKey, boolean currentRegionOnly, boolean showOnlyIncorrect) {
            this.plan = plan;
            this.origin = origin;
            this.dimensionKey = dimensionKey;
            this.currentRegionOnly = currentRegionOnly;
            this.showOnlyIncorrect = showOnlyIncorrect;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof CacheKey cacheKey)) {
                return false;
            }
            return plan == cacheKey.plan
                    && currentRegionOnly == cacheKey.currentRegionOnly
                    && showOnlyIncorrect == cacheKey.showOnlyIncorrect
                    && Objects.equals(origin, cacheKey.origin)
                    && Objects.equals(dimensionKey, cacheKey.dimensionKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(System.identityHashCode(plan), origin, dimensionKey, currentRegionOnly, showOnlyIncorrect);
        }
    }

    static final class OverlayCache {
        private static final OverlayCache EMPTY = new OverlayCache(null, List.of(), Map.of(), 0);

        private final CacheKey key;
        private final List<OverlayTarget> allTargets;
        private final Map<OverlayRegion, List<OverlayTarget>> targetsByRegion;
        private final int totalTargets;

        private OverlayCache(
                CacheKey key,
                List<OverlayTarget> allTargets,
                Map<OverlayRegion, List<OverlayTarget>> targetsByRegion,
                int totalTargets
        ) {
            this.key = key;
            this.allTargets = allTargets;
            this.targetsByRegion = targetsByRegion;
            this.totalTargets = totalTargets;
        }

        static OverlayCache empty() {
            return EMPTY;
        }

        boolean matches(CacheKey key) {
            return Objects.equals(this.key, key);
        }

        boolean isEmpty() {
            return totalTargets == 0;
        }

        List<OverlayTarget> allTargets() {
            return allTargets;
        }

        List<OverlayTarget> targetsForRegion(OverlayRegion region) {
            return targetsByRegion.getOrDefault(region, List.of());
        }

        Map<OverlayRegion, List<OverlayTarget>> targetsByRegion() {
            return targetsByRegion;
        }

        int regionCount() {
            return targetsByRegion.size();
        }
    }
}
