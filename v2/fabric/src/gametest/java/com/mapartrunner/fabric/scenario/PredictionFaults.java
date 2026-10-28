package com.mapartrunner.fabric.scenario;

import com.mapartrunner.core.run.RunLifecycle.Phase;
import com.mapartrunner.fabric.MapArtClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Client-thread-only simulated network fault. Scope dies before world teardown; diagnostics retain no native objects. */
public final class PredictionFaults {
    private static final int WINDOW = 200;
    private static Scope scope;
    private static String failure;
    private static final List<Response> responses = new ArrayList<>();

    private PredictionFaults() { }

    record Response(UUID runId, String save, int serverIdentity, int worldIdentity, int handlerIdentity,
                    int connectionIdentity, int sequence, int playerAge, long eligibleTick) { }
    record Snapshot(boolean active, Optional<String> failure, List<Response> responses) { }
    private record Scope(MinecraftServer server, ClientWorld world, ClientPlayNetworkHandler handler,
                         ClientConnection connection, UUID runId, Path save, int age) { }

    static void arm(MinecraftClient client, MinecraftServer ownedServer, Path ownedSave, UUID runId) {
        requireThread(client);
        if(scope!=null || failure!=null) throw new IllegalStateException("Fault already active or failed");
        var game=FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        var save=ownedSave.toAbsolutePath().normalize();
        var state=MapArtClient.status().engine().lifecycle();
        var handler=client.getNetworkHandler();
        if(!game.getParent().getFileName().toString().equals("run")
                || !game.getParent().getParent().getFileName().toString().equals("build")
                || !save.startsWith(game.resolve("saves")) || save.equals(game.resolve("saves"))
                || ownedServer==null || client.getServer()!=ownedServer
                || !ownedServer.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize().equals(save)
                || client.world==null || client.player==null || client.player.getEntityWorld()!=client.world
                || handler==null || !handler.getConnection().isOpen() || client.isPaused() || client.currentScreen!=null
                || state.phase()!=Phase.RUNNING || state.context().isEmpty()
                || !state.context().orElseThrow().runId().equals(runId)) {
            throw new IllegalStateException("Refused unmanaged save/server/world/connection/run");
        }
        if(!withinWindow(0,0) || !withinWindow(WINDOW,0) || withinWindow(WINDOW+1,0) || withinWindow(-1,0))
            throw new AssertionError("Fault window boundary self-check failed");
        responses.clear();
        scope=new Scope(ownedServer,client.world,handler,handler.getConnection(),runId,save,client.player.age);
    }

    /** Called at the original incoming INVOKE, after vanilla main-thread dispatch; never queues or replays a response. */
    public static boolean withhold(ClientPlayNetworkHandler handler, ClientWorld world, int sequence) {
        var client=MinecraftClient.getInstance();
        requireThread(client);
        var owned=scope;
        if(owned==null || handler!=owned.handler() || world!=owned.world()) return false;
        check(client);
        if(scope==null) return false;
        if(responses.size()>=WINDOW) {
            fail("Response event bound exceeded");
            return false;
        }
        responses.add(new Response(owned.runId(),owned.save().toString(),System.identityHashCode(owned.server()),
                System.identityHashCode(world),System.identityHashCode(handler),System.identityHashCode(owned.connection()),
                sequence,client.player.age,MapArtClient.status().engine().eligibleTicks()));
        return true;
    }

    static Snapshot snapshot() {
        var client=MinecraftClient.getInstance();
        requireThread(client);
        check(client);
        return new Snapshot(scope!=null,Optional.ofNullable(failure),List.copyOf(responses));
    }

    static void clear() {
        requireThread(MinecraftClient.getInstance());
        scope=null; // Invalidate before disposing native owners; immutable response evidence survives cleanup.
    }

    private static void check(MinecraftClient client) {
        var owned=scope;
        if(owned==null) return;
        var state=MapArtClient.status().engine().lifecycle();
        if(client.getServer()!=owned.server() || client.world!=owned.world() || client.getNetworkHandler()!=owned.handler()
                || owned.handler().getConnection()!=owned.connection() || !owned.connection().isOpen()
                || client.player==null || client.player.getEntityWorld()!=owned.world()
                || !owned.server().getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize().equals(owned.save())
                || state.context().isEmpty() || !state.context().orElseThrow().runId().equals(owned.runId())
                || (state.phase()!=Phase.RUNNING && state.phase()!=Phase.TERMINAL)
                || client.isPaused() || client.currentScreen!=null) fail("Owned fault scope lost");
        else if(!withinWindow(client.player.age,owned.age())) fail("Owned fault window exceeded "+WINDOW+" physics ticks");
    }

    private static boolean withinWindow(int age,int initialAge) {
        long elapsed=(long)age-initialAge;
        return elapsed>=0 && elapsed<=WINDOW;
    }

    private static void fail(String detail) {
        if(failure==null) failure=detail;
        scope=null;
    }

    private static void requireThread(MinecraftClient client) {
        if(!client.isOnThread()) throw new IllegalStateException("Fault accessed outside client thread");
    }
}
