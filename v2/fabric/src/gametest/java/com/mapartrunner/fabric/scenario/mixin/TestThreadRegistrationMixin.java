package com.mapartrunner.fabric.scenario.mixin;

import com.mapartrunner.fabric.scenario.threading.RegistrationAcknowledgement;
import net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.time.Duration;
import java.util.concurrent.Phaser;

@Mixin(value = ThreadingImpl.class, remap = false)
public abstract class TestThreadRegistrationMixin {
    @Unique
    private static final RegistrationAcknowledgement mapart$registration = new RegistrationAcknowledgement();

    @Redirect(method = "runTestThread(Ljava/lang/Runnable;)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;start()V", remap = false),
            require = 1, allow = 1, remap = false)
    private static void mapart$startRegisteredThread(Thread child) {
        System.out.println("MapArtRunner START_ENTER childId=" + child.threadId()
                + " childIdentity=" + System.identityHashCode(child) + " nanoTime=" + System.nanoTime());
        mapart$registration.startAndAwait(child, Duration.ofSeconds(5));
        System.out.println("MapArtRunner START_ACKNOWLEDGED childId=" + child.threadId()
                + " childIdentity=" + System.identityHashCode(child) + " nanoTime=" + System.nanoTime());
    }

    @Redirect(method = "lambda$runTestThread$1(Ljava/lang/Runnable;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/Phaser;register()I", remap = false),
            require = 1, allow = 1, remap = false)
    private static int mapart$registerTestThread(Phaser phaser) {
        return mapart$registration.registerAndSignal(() -> {
            int phase = phaser.register();
            Thread child = Thread.currentThread();
            System.out.println("MapArtRunner REGISTERED childId=" + child.threadId()
                    + " childIdentity=" + System.identityHashCode(child) + " nanoTime=" + System.nanoTime()
                    + " returnedPhase=" + phase + " phase=" + phaser.getPhase()
                    + " parties=" + phaser.getRegisteredParties() + " unarrived=" + phaser.getUnarrivedParties());
            return phase;
        });
    }
}
