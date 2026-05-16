package com.example.mapart;

import com.example.mapart.baritone.BaritoneFacade;
import com.example.mapart.baritone.BaritoneFacadeFactory;
import com.example.mapart.command.MapArtCommand;
import com.example.mapart.gui.MapArtConfigScreen;
import com.example.mapart.persistence.ConfigStore;
import com.example.mapart.persistence.ProgressStore;
import com.example.mapart.plan.PlanLoaderRegistry;
import com.example.mapart.plan.compare.PlacementStatusResolver;
import com.example.mapart.plan.loaders.SchemNbtLoader;
import com.example.mapart.plan.sweep.SingleLaneSweepDebugRunner;
import com.example.mapart.plan.sweep.grounded.GroundedSingleLaneDebugRunner;
import com.example.mapart.plan.state.BuildCoordinator;
import com.example.mapart.plan.state.BuildPlanService;
import com.example.mapart.plan.state.WorldPlacementResolver;
import com.example.mapart.render.HudRenderer;
import com.example.mapart.render.ManualAirPlaceOverlayRenderer;
import com.example.mapart.render.SchematicOverlayRenderer;
import com.example.mapart.runtime.ClientTimerController;
import com.example.mapart.runtime.DebugReporter;
import com.example.mapart.runtime.MapArtRuntime;
import com.example.mapart.settings.MapartSettings;
import com.example.mapart.settings.MapartSettingsStore;
import com.example.mapart.supply.SupplyInteractionTracker;
import com.example.mapart.supply.SupplyStore;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class MapArtClientMod implements ClientModInitializer {
    private static final String PANIC_KEY_TRANSLATION = "key.mapart.panic";
    private static final String CONFIG_KEY_TRANSLATION = "key.mapart.config";

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
        DebugReporter debugReporter = new DebugReporter();
        BaritoneFacade baritoneFacade = BaritoneFacadeFactory.create();
        BuildCoordinator buildCoordinator = new BuildCoordinator(new WorldPlacementResolver(), configStore, progressStore, supplyStore, baritoneFacade);
        BuildPlanService buildPlanService = new BuildPlanService(loaderRegistry, buildCoordinator);
        SingleLaneSweepDebugRunner singleLaneSweepDebugRunner = new SingleLaneSweepDebugRunner();
        GroundedSingleLaneDebugRunner groundedSingleLaneDebugRunner = new GroundedSingleLaneDebugRunner(baritoneFacade, supplyStore);
        groundedSingleLaneDebugRunner.resetDiagnosticsForLaunch();
        MapArtRuntime.initialize(buildPlanService, configStore, progressStore, settingsStore, supplyStore, baritoneFacade, debugReporter, singleLaneSweepDebugRunner, groundedSingleLaneDebugRunner);
        KeyBinding.Category mapartCategory = KeyBinding.Category.create(Identifier.of("mapart", "general"));
        KeyBinding panicKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                PANIC_KEY_TRANSLATION,
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                mapartCategory
        ));
        KeyBinding configKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                CONFIG_KEY_TRANSLATION,
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                mapartCategory
        ));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(MapArtCommand.create(buildPlanService, settingsStore, supplyStore, supplyInteractionTracker));
            dispatcher.register(MapArtCommand.createAlias(buildPlanService, settingsStore, supplyStore, supplyInteractionTracker));
            dispatcher.register(MapArtCommand.createRunnerAlias(buildPlanService, settingsStore, supplyStore, supplyInteractionTracker));
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (panicKeyBinding.wasPressed()) {
                if (client.player != null) {
                    GroundedSingleLaneDebugRunner runner = MapArtRuntime.groundedSingleLaneDebugRunner();
                    boolean runnerWasActive = runner != null && runner.status().active();
                    if (runner != null) {
                        runner.cancelRefillAndStop();
                    }
                    boolean didAnything = MapArtCommand.triggerPanic(buildPlanService).didAnything() || runnerWasActive;
                    client.player.sendMessage(Text.literal(didAnything
                            ? "MapArt panic button triggered."
                            : "MapArt panic button pressed, but nothing was active."), true);
                }
            }

            while (configKeyBinding.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new MapArtConfigScreen(settingsStore, null));
                }
            }

            MapartSettings settings = settingsStore.current();

            // Sync the real RenderTickCounter mixin from settings. When enabled,
            // dynamicDeltaTicks is scaled up each frame so Minecraft fires more ticks
            // per second naturally — world/server/Baritone/network all advance together.
            // The old loop that called the runners N times per normal tick was NOT real
            // timer acceleration and has been removed.
            if (ClientTimerController.applySettings(settings.clientTimerEnabled(), settings.clientTimerSpeed())) {
                MapArtMod.LOGGER.info(
                        "MapArt client timer: {} speed={} mode={}",
                        settings.clientTimerEnabled() ? "ENABLED" : "DISABLED",
                        settings.clientTimerEnabled() ? settings.clientTimerSpeed() : 1,
                        ClientTimerController.MODE
                );
            }

            singleLaneSweepDebugRunner.tick(client);
            groundedSingleLaneDebugRunner.tick(client, settings);
        });

        PlacementStatusResolver resolver = new PlacementStatusResolver();
        HudRenderCallback.EVENT.register(new HudRenderer(resolver));
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(new SchematicOverlayRenderer(resolver));
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(new ManualAirPlaceOverlayRenderer());

        debugReporter.logToFile("Debug log file: " + debugReporter.logPath().toAbsolutePath());
        MapArtMod.LOGGER.info("Initialized mapart client command pipeline with /mapart, /maprunner, and /mapartrunner");
        MapArtMod.LOGGER.info("Baritone facade backend: {}", baritoneFacade.getClass().getSimpleName());
    }
}
