package com.example.mapart;

import com.example.mapart.command.MapArtCommand;
import com.example.mapart.persistence.ConfigStore;
import com.example.mapart.persistence.ProgressStore;
import com.example.mapart.plan.PlanLoaderRegistry;
import com.example.mapart.plan.compare.PlacementStatusResolver;
import com.example.mapart.plan.loaders.SchemNbtLoader;
import com.example.mapart.plan.state.BuildCoordinator;
import com.example.mapart.plan.state.BuildPlanService;
import com.example.mapart.plan.state.WorldPlacementResolver;
import com.example.mapart.render.HudRenderer;
import com.example.mapart.render.SchematicOverlayRenderer;
import com.example.mapart.runtime.MapArtRuntime;
import com.example.mapart.settings.MapartSettingsStore;
import com.example.mapart.supply.SupplyInteractionTracker;
import com.example.mapart.supply.SupplyStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

public class MapArtClientMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PlanLoaderRegistry loaderRegistry = new PlanLoaderRegistry();
        loaderRegistry.register(new SchemNbtLoader());

        ConfigStore configStore = new ConfigStore();
        ProgressStore progressStore = new ProgressStore();
        MapartSettingsStore settingsStore = new MapartSettingsStore();
        SupplyStore supplyStore = new SupplyStore();
        SupplyInteractionTracker supplyInteractionTracker = new SupplyInteractionTracker(supplyStore);
        supplyInteractionTracker.registerCallbacks();
        BuildCoordinator buildCoordinator = new BuildCoordinator(new WorldPlacementResolver(), configStore, progressStore);
        BuildPlanService buildPlanService = new BuildPlanService(loaderRegistry, buildCoordinator);
        MapArtRuntime.initialize(buildPlanService, configStore, progressStore, settingsStore, supplyStore);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(MapArtCommand.create(buildPlanService, settingsStore, supplyStore, supplyInteractionTracker));
            dispatcher.register(MapArtCommand.createAlias(buildPlanService, settingsStore, supplyStore, supplyInteractionTracker));
            dispatcher.register(MapArtCommand.createRunnerAlias(buildPlanService, settingsStore, supplyStore, supplyInteractionTracker));
        });

        PlacementStatusResolver resolver = new PlacementStatusResolver();
        HudRenderCallback.EVENT.register(new HudRenderer(resolver));
        WorldRenderEvents.AFTER_TRANSLUCENT.register(new SchematicOverlayRenderer(resolver));

        MapArtMod.LOGGER.info("Initialized mapart client command pipeline with /mapart, /maprunner, and /mapartrunner");
    }
}
