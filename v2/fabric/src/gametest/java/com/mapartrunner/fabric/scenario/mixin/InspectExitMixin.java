package com.mapartrunner.fabric.scenario.mixin;

import com.mapartrunner.fabric.scenario.ScenarioClientInitializer;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep the live loop available for the installed inspect owner's bounded cleanup. */
@Mixin(MinecraftClient.class)
public abstract class InspectExitMixin {
    @Inject(method="scheduleStop()V",at=@At("HEAD"),cancellable=true,require=1,allow=1)
    private void mapart$deferInspectExit(CallbackInfo callback) {
        if (ScenarioClientInitializer.deferStop((MinecraftClient)(Object)this)) callback.cancel();
    }
}
