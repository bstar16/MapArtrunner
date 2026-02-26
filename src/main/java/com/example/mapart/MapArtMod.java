package com.example.mapart;

import com.example.mapart.command.MapArtCommand;
import com.example.mapart.persistence.ConfigStore;
import com.example.mapart.persistence.ProgressStore;
import com.example.mapart.plan.PlanLoaderRegistry;
import com.example.mapart.plan.loaders.SchemNbtLoader;
import com.example.mapart.plan.state.BuildPlanService;
import com.example.mapart.plan.state.BuildPlanState;
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

        BuildPlanState buildPlanState = new BuildPlanState();
        BuildPlanService buildPlanService = new BuildPlanService(
                loaderRegistry,
                buildPlanState,
                new ConfigStore(),
                new ProgressStore()
        );

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(MapArtCommand.create(buildPlanService));
            dispatcher.register(MapArtCommand.createAlias(buildPlanService));
            dispatcher.register(MapArtCommand.createRunnerAlias(buildPlanService));
        });

        LOGGER.info("Initialized mapart command pipeline with /mapart, /maprunner, and /mapartrunner");
    }
}
