package com.mapartrunner.fabric.scenario;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Ordinary inspect bootstrap. Deliberately unregistered until the launcher/metadata slice. */
public final class ScenarioClientInitializer implements ClientModInitializer {
    private static final AtomicBoolean installed = new AtomicBoolean();

    @Override public void onInitializeClient() {
        if (!Boolean.getBoolean("mapart.test.inspect")) return;
        if (!installed.compareAndSet(false, true)) return;
        var client = MinecraftClient.getInstance();
        var host = new InspectScenarioHost(client);
        var worker = Executors.newSingleThreadExecutor(action -> {
            var thread = new Thread(action, "mapart-inspect-worker"); thread.setDaemon(true); return thread;
        });
        var commands = new ScenarioCommands(worker, host);
        var watch = ScenarioObserver.install(client);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> commands.register(dispatcher));
        ClientTickEvents.END_CLIENT_TICK.register(current -> { host.onTick(); commands.checkWorld(current); });
        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((current, world) -> commands.checkWorld(current));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, current) ->
                commands.cancel(new IllegalStateException("Inspect connection disconnected; restart required")));
        ClientLifecycleEvents.CLIENT_STOPPING.register(current -> {
            host.onStopping(current);
            commands.cancel(new IllegalStateException("Inspect client stopping"));
            worker.shutdown();
        });
        // Commands and lifecycle callbacks are installed before STARTED can enqueue world creation.
        ClientLifecycleEvents.CLIENT_STARTED.register(current -> commands.initialize(watch,
                ScenarioLaunchReceipt.fromEnvironment(), () -> current.execute(current::scheduleStop)));
    }
}
