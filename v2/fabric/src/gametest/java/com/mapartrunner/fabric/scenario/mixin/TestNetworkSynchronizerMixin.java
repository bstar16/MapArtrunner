package com.mapartrunner.fabric.scenario.mixin;

import com.google.common.collect.ConcurrentHashMultiset;
import com.mapartrunner.fabric.scenario.threading.PacketHandlerAwait;
import net.fabricmc.fabric.impl.client.gametest.threading.NetworkSynchronizer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;

/** Exact seams for pinned fabric-client-gametest-api-v1 4.3.5+bfe9fcf43e. */
@Mixin(value = NetworkSynchronizer.class, remap = false)
public abstract class TestNetworkSynchronizerMixin {
    @Shadow @Final private AtomicInteger inFlightPackets;
    @Shadow @Final private ConcurrentHashMultiset<?> mainThreadPacketHandlers;
    @Shadow @Final private AtomicBoolean invalid;

    @Redirect(method = "waitForPacketHandlers(Lnet/minecraft/util/thread/ThreadExecutor;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/locks/Condition;await(JLjava/util/concurrent/TimeUnit;)Z",
                    remap = false), require = 1, allow = 1, remap = false)
    private boolean mapart$awaitCurrentPacketPredicate(Condition condition, long timeout, TimeUnit unit)
            throws InterruptedException {
        // Fabric already holds morePacketsLock here and retains its drain, invalidation and unlock paths.
        return PacketHandlerAwait.await(condition, timeout, unit,
                inFlightPackets::get, mainThreadPacketHandlers::isEmpty, invalid::get);
    }

    @Inject(method = "markInvalid()V",
            at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;error(Ljava/lang/String;)V",
                    shift = At.Shift.AFTER, remap = false), require = 1, allow = 1, remap = false)
    private void mapart$logFirstInvalidPacketState(CallbackInfo callback) {
        String direction = (Object) this == NetworkSynchronizer.CLIENTBOUND ? "CLIENTBOUND"
                : (Object) this == NetworkSynchronizer.SERVERBOUND ? "SERVERBOUND" : "UNKNOWN";
        String thread = Thread.currentThread().getName();
        thread = thread.substring(0, Math.min(thread.length(), 64)).replace('\r', '_').replace('\n', '_');
        // The original invalid.getAndSet guard bounds this to one line per synchronizer lifetime.
        System.out.println("MapArtRunner PACKET_INVALID direction=" + direction
                + " inFlight=" + inFlightPackets.get() + " queuedHandlers=" + mainThreadPacketHandlers.size()
                + " invalid=" + invalid.get() + " thread=" + thread);
    }
}
