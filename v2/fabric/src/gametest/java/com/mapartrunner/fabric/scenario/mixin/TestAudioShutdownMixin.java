package com.mapartrunner.fabric.scenario.mixin;

import net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dispose after the last sound tick, before Fabric rethrows its stored test failure. */
@Mixin(MinecraftClient.class)
public abstract class TestAudioShutdownMixin {
    @Inject(method="run()V",at=@At("RETURN"),require=1,allow=1)
    private void mapart$closeTestAudio(CallbackInfo callback) {
        // Fabric's pinned implementation owns this failure; recheck the read-only dependency on upgrades.
        Throwable primary = ThreadingImpl.testFailureException;
        String thread = Thread.currentThread().getName();
        int primaryIdentity = System.identityHashCode(primary);
        System.out.println("MapArtRunner late audio disposal BEGIN; thread=" + thread + "; primary=" + primaryIdentity);
        try {
            ((MinecraftClient)(Object)this).getSoundManager().close();
        } catch (RuntimeException | Error cleanup) {
            System.out.println("MapArtRunner late audio disposal FAILED; thread=" + thread + "; primary=" + primaryIdentity);
            if (primary != null && primary != cleanup) primary.addSuppressed(cleanup);
            throw cleanup;
        }
        System.out.println("MapArtRunner late audio disposal COMPLETE; thread=" + thread + "; primary=" + primaryIdentity);
    }
}
