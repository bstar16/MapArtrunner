package com.example.mapart;

import com.example.mapart.command.MapArtCommand;
import com.example.mapart.persistence.ConfigStore;
import com.example.mapart.persistence.ProgressStore;
import com.example.mapart.plan.PlanLoaderRegistry;
import com.example.mapart.plan.loaders.SchemNbtLoader;
import com.example.mapart.plan.state.BuildPlanService;
import com.example.mapart.plan.state.BuildPlanState;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

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

        registerCommandCallback(buildPlanService);

        LOGGER.info("Initialized mapart Milestone A command and plan loader pipeline");
    }

    private static void registerCommandCallback(BuildPlanService buildPlanService) {
        try {
            Class<?> callbackClass = Class.forName("net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback");
            Field eventField = callbackClass.getField("EVENT");
            Object event = eventField.get(null);

            Object listener = Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass},
                    (proxy, method, args) -> {
                        if (!"register".equals(method.getName()) || args == null || args.length == 0) {
                            return null;
                        }

                        @SuppressWarnings("unchecked")
                        com.mojang.brigadier.CommandDispatcher<net.minecraft.server.command.ServerCommandSource> dispatcher =
                                (com.mojang.brigadier.CommandDispatcher<net.minecraft.server.command.ServerCommandSource>) args[0];
                        dispatcher.register(MapArtCommand.create(buildPlanService));
                        dispatcher.register(MapArtCommand.createAlias(buildPlanService));
                        return null;
                    }
            );

            event.getClass().getMethod("register", Object.class).invoke(event, listener);
        } catch (Throwable throwable) {
            LOGGER.warn("Skipping command registration because the runtime command API is unavailable", throwable);
        }
    }
}
