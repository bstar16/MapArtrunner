package com.mapartrunner.fabric;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.run.BuildEngine;
import com.mapartrunner.fabric.runtime.FabricBuildAdapter;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;

import java.util.UUID;

/** Java lifecycle facade; command, import and UI wiring belong to later parity work. */
public final class MapArtClient implements ClientModInitializer {
    private static FabricBuildAdapter adapter;

    @Override public void onInitializeClient() {
        if (adapter != null) throw new IllegalStateException("MapArtRunner client already initialized");
        adapter = new FabricBuildAdapter(MinecraftClient.getInstance());
        ClientTickEvents.END_CLIENT_TICK.register(client -> adapter.tick());
        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((client, world) -> adapter.onWorldChanged(world));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            var connection = handler.getConnection();
            // Fabric can signal from channelInactive on Netty; capture the old pair before queueing.
            if (client.isOnThread()) adapter.onDisconnect(handler, connection);
            else client.execute(() -> adapter.onDisconnect(handler, connection));
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> adapter.onClientStopping());
        ScreenEvents.BEFORE_INIT.register((client, screen, width, height) -> adapter.onScreenOpened());
    }

    public static UUID start(BuildPlan plan, BuildPlan.GridPos origin, BuildEngine.Settings settings) {
        return instance().start(plan, origin, settings);
    }
    public static void pause() { instance().pause(); }
    public static void resume() { instance().resume(); }
    public static void stop() { instance().stop(); }
    public static FabricBuildAdapter.Status status() { return instance().status(); }

    private static FabricBuildAdapter instance() {
        if (adapter == null) throw new IllegalStateException("MapArtRunner client is not initialized");
        return adapter;
    }
}
