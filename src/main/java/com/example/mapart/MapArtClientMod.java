package com.example.mapart;

import com.example.mapart.baritone.BaritoneFacade;
import com.example.mapart.baritone.BaritoneFacadeFactory;
import com.example.mapart.command.MapArtCommand;
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
import com.example.mapart.render.SchematicOverlayRenderer;
import com.example.mapart.runtime.DebugReporter;
import com.example.mapart.runtime.MapArtRuntime;
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
        GroundedSingleLaneDebugRunner groundedSingleLaneDebugRunner = new GroundedSingleLaneDebugRunner();
        MapArtRuntime.initialize(buildPlanService, configStore, progressStore, settingsStore, supplyStore, baritoneFacade, debugReporter, singleLaneSweepDebugRunner, groundedSingleLaneDebugRunner);
        KeyBinding panicKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                PANIC_KEY_TRANSLATION,
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                KeyBinding.Category.create(Identifier.of("mapart", "general"))
        ));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(MapArtCommand.create(buildPlanService, settingsStore, supplyStore, supplyInteractionTracker, baritoneFacade));
            dispatcher.register(MapArtCommand.createAlias(buildPlanService, settingsStore, supplyStore, supplyInteractionTracker, baritoneFacade));
            dispatcher.register(MapArtCommand.createRunnerAlias(buildPlanService, settingsStore, supplyStore, supplyInteractionTracker, baritoneFacade));
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (panicKeyBinding.wasPressed()) {
                if (client.player != null) {
                    boolean didAnything = MapArtCommand.triggerPanic(buildPlanService).didAnything();
                    client.player.sendMessage(Text.literal(didAnything
                            ? "MapArt panic button triggered."
                            : "MapArt panic button pressed, but nothing was active."), true);
                }
            }

            int clientTimerSpeed = settingsStore.current().clientTimerSpeed();
            for (int iteration = 0; iteration < clientTimerSpeed; iteration++) {
                singleLaneSweepDebugRunner.tick(client);
                groundedSingleLaneDebugRunner.tick(client);
                BuildCoordinator.AssistedStepResult assistedStep = buildCoordinator.tickAssisted(client);
                if (!assistedStep.didWork()) {
                    break;
                }

                if (client.player != null && !assistedStep.message().isBlank()) {
                    debugReporter.logToFile("Assisted tick: " + assistedStep.message());
                }

                if (assistedStep.done() || assistedStep.failed()) {
                    break;
                }
            }
        });

        PlacementStatusResolver resolver = new PlacementStatusResolver();
        HudRenderCallback.EVENT.register(new HudRenderer(resolver));
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(new SchematicOverlayRenderer(resolver));

        debugReporter.logToFile("Debug log file: " + debugReporter.logPath().toAbsolutePath());
        MapArtMod.LOGGER.info("Initialized mapart client command pipeline with /mapart, /maprunner, and /mapartrunner");
        MapArtMod.LOGGER.info("Baritone facade backend: {}", baritoneFacade.getClass().getSimpleName());
    }
}
