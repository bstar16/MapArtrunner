package com.example.mapart.render;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.Region;
import com.example.mapart.plan.compare.PlacementStatusResolver;
import com.example.mapart.plan.state.BuildSession;
import com.example.mapart.settings.MapartSettings;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SchematicOverlayRendererTest {
    @Test
    void currentRegionOnlyUsesPlayerPositionNotSchematicOrigin() {
        BuildSession session = sessionWithPlacements(new BlockPos(0, 64, 0),
                placement(0, 0),
                placement(32, 0));

        SchematicOverlayRenderer.OverlayCache cache = SchematicOverlayRenderer.buildOverlayCache(
                cacheKey(session, settings(true, false)),
                session);
        SchematicOverlayRenderer.OverlayRegion playerRegion = SchematicOverlayRenderer.currentPlayerOverlayRegion(new BlockPos(32, 64, 0));

        List<SchematicOverlayRenderer.OverlayTarget> targets = cache.targetsForRegion(playerRegion);

        assertEquals(1, targets.size());
        assertEquals(new BlockPos(32, 64, 0), targets.getFirst().absolutePos());
    }

    @Test
    void movingPlayerToDifferentRegionChangesRenderedRegion() {
        BuildSession session = sessionWithPlacements(new BlockPos(0, 64, 0),
                placement(0, 0),
                placement(16, 0));
        SchematicOverlayRenderer.OverlayCache cache = SchematicOverlayRenderer.buildOverlayCache(
                cacheKey(session, settings(true, false)),
                session);

        SchematicOverlayRenderer.OverlayRegion firstRegion = SchematicOverlayRenderer.currentPlayerOverlayRegion(new BlockPos(15, 64, 0));
        SchematicOverlayRenderer.OverlayRegion secondRegion = SchematicOverlayRenderer.currentPlayerOverlayRegion(new BlockPos(16, 64, 0));

        assertNotEquals(firstRegion, secondRegion);
        assertEquals(new BlockPos(0, 64, 0), cache.targetsForRegion(firstRegion).getFirst().absolutePos());
        assertEquals(new BlockPos(16, 64, 0), cache.targetsForRegion(secondRegion).getFirst().absolutePos());
    }

    @Test
    void disablingCurrentRegionOnlyKeepsBroadOverlayTargetsAvailable() {
        BuildSession session = sessionWithPlacements(new BlockPos(0, 64, 0),
                placement(0, 0),
                placement(32, 0),
                placement(64, 0));

        SchematicOverlayRenderer.OverlayCache cache = SchematicOverlayRenderer.buildOverlayCache(
                cacheKey(session, settings(false, false)),
                session);

        assertEquals(3, cache.allTargets().size());
        assertEquals(3, cache.regionCount());
    }

    @Test
    void overlayCacheInvalidatesWhenSessionPlanChanges() {
        SchematicOverlayRenderer renderer = new SchematicOverlayRenderer(new PlacementStatusResolver());
        BuildSession first = sessionWithPlacements(new BlockPos(0, 64, 0), placement(0, 0));
        BuildSession second = sessionWithPlacements(new BlockPos(0, 64, 0), placement(16, 0));
        MapartSettings settings = settings(true, false);

        SchematicOverlayRenderer.OverlayCache firstCache = renderer.rebuildOverlayCacheIfNeeded(first, "minecraft:overworld", settings);
        SchematicOverlayRenderer.OverlayCache secondCache = renderer.rebuildOverlayCacheIfNeeded(second, "minecraft:overworld", settings);

        assertNotSame(firstCache, secondCache);
        assertEquals(new BlockPos(16, 64, 0), secondCache.allTargets().getFirst().absolutePos());
    }

    @Test
    void overlayCacheInvalidatesWhenRelevantSettingsChange() {
        SchematicOverlayRenderer renderer = new SchematicOverlayRenderer(new PlacementStatusResolver());
        BuildSession session = sessionWithPlacements(new BlockPos(0, 64, 0), placement(0, 0));

        SchematicOverlayRenderer.OverlayCache firstCache = renderer.rebuildOverlayCacheIfNeeded(session, "minecraft:overworld", settings(true, false));
        SchematicOverlayRenderer.OverlayCache secondCache = renderer.rebuildOverlayCacheIfNeeded(session, "minecraft:overworld", settings(true, true));

        assertNotSame(firstCache, secondCache);
    }

    @Test
    void cachedPlayerRegionUpdatesAcrossChunkBoundary() {
        SchematicOverlayRenderer.OverlayRegion beforeBoundary = SchematicOverlayRenderer.currentPlayerOverlayRegion(new BlockPos(15, 64, 15));
        SchematicOverlayRenderer.OverlayRegion afterBoundary = SchematicOverlayRenderer.currentPlayerOverlayRegion(new BlockPos(16, 64, 15));

        assertEquals(new SchematicOverlayRenderer.OverlayRegion(0, 0), beforeBoundary);
        assertEquals(new SchematicOverlayRenderer.OverlayRegion(1, 0), afterBoundary);
    }

    @Test
    void fullOverlayFilteringCanSkipRegionsOutsideRenderDistance() {
        SchematicOverlayRenderer.OverlayRegion playerRegion = new SchematicOverlayRenderer.OverlayRegion(0, 0);

        assertTrue(SchematicOverlayRenderer.isWithinRenderDistance(new SchematicOverlayRenderer.OverlayRegion(2, 0), playerRegion, 2));
        assertFalse(SchematicOverlayRenderer.isWithinRenderDistance(new SchematicOverlayRenderer.OverlayRegion(3, 0), playerRegion, 2));
        assertFalse(SchematicOverlayRenderer.isWithinRenderDistance(new SchematicOverlayRenderer.OverlayRegion(0, -3), playerRegion, 2));
    }

    private static SchematicOverlayRenderer.CacheKey cacheKey(BuildSession session, MapartSettings settings) {
        return new SchematicOverlayRenderer.CacheKey(
                session.getPlan(),
                session.getOrigin(),
                "minecraft:overworld",
                settings.overlayCurrentRegionOnly(),
                settings.overlayShowOnlyIncorrect());
    }

    private static BuildSession sessionWithPlacements(BlockPos origin, Placement... placements) {
        BuildPlan plan = new BuildPlan(
                "test",
                Path.of("overlay-test.schem"),
                new Vec3i(128, 1, 128),
                List.of(placements),
                Map.of(),
                List.of(new Region(null, List.of(placements))));
        BuildSession session = new BuildSession(plan);
        session.setOrigin(origin);
        return session;
    }

    private static Placement placement(int relativeX, int relativeZ) {
        return new Placement(new BlockPos(relativeX, 0, relativeZ), null);
    }

    private static MapartSettings settings(boolean currentRegionOnly, boolean showOnlyIncorrect) {
        MapartSettings defaults = MapartSettings.defaults();
        return new MapartSettings(
                defaults.showHud(),
                defaults.showSchematicOverlay(),
                currentRegionOnly,
                showOnlyIncorrect,
                defaults.hudCompact(),
                defaults.clientTimerSpeed(),
                defaults.clientTimerEnabled(),
                defaults.hudX(),
                defaults.hudY(),
                defaults.sweepHalfWidth(),
                defaults.sweepTotalWidth(),
                defaults.laneStride(),
                defaults.forwardLookaheadSteps(),
                defaults.trivialBehindCleanupSteps(),
                defaults.groundedSweepConstantSprint(),
                defaults.placementDelayTicks(),
                defaults.inventoryClickDelayTicks(),
                defaults.reservedHotbarSlots(),
                defaults.torchGridEnabled(),
                defaults.torchGridSpacing(),
                defaults.torchGridWarnMissingTorches(),
                defaults.torchGridMaxPlacementsPerTick(),
                defaults.manualAirPlaceEnabled(),
                defaults.manualAirPlaceRender(),
                defaults.manualAirPlaceUseCustomRange(),
                defaults.manualAirPlaceCustomRange(),
                defaults.manualAirPlaceRequireSneak(),
                defaults.manualAirPlaceDisableWhileRunnerActive());
    }
}
