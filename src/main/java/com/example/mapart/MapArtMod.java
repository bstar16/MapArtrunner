package com.example.mapart;

import com.example.mapart.command.MapArtCommand;
import com.example.mapart.persistence.ConfigStore;
import com.example.mapart.persistence.ProgressStore;
import com.example.mapart.runtime.MapArtRuntime;
import com.example.mapart.settings.MapartSettingsStore;
import com.example.mapart.supply.SupplyInteractionTracker;
import com.example.mapart.supply.SupplyStore;
import com.example.mapart.plan.PlanLoaderRegistry;
import com.example.mapart.plan.loaders.SchemNbtLoader;
import com.example.mapart.plan.state.BuildCoordinator;
import com.example.mapart.plan.state.BuildPlanService;
import com.example.mapart.plan.state.WorldPlacementResolver;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MapArtMod implements ModInitializer {
    public static final String MOD_ID = "mapart";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
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

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(MapArtCommand.create(buildPlanService, settingsStore, supplyStore, supplyInteractionTracker));
            dispatcher.register(MapArtCommand.createAlias(buildPlanService, settingsStore, supplyStore, supplyInteractionTracker));
            dispatcher.register(MapArtCommand.createRunnerAlias(buildPlanService, settingsStore, supplyStore, supplyInteractionTracker));
        });

        LOGGER.info("Initialized mapart command pipeline with /mapart, /maprunner, and /mapartrunner");
    }
}
