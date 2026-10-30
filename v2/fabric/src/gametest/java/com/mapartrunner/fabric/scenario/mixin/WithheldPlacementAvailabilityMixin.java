package com.mapartrunner.fabric.scenario.mixin;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.run.BuildEngine;
import com.mapartrunner.core.run.RunLifecycle;
import com.mapartrunner.fabric.runtime.FabricPlacementEffects;
import com.mapartrunner.fabric.scenario.PredictionFaults;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/** Simulated one-cell eligibility gate; real support, observations and interaction remain native. */
@Mixin(value=FabricPlacementEffects.class,remap=false)
public abstract class WithheldPlacementAvailabilityMixin {
    @Inject(method="availability(Lnet/minecraft/client/MinecraftClient;Lcom/mapartrunner/core/model/BuildPlan;Lcom/mapartrunner/core/run/RunLifecycle$Context;Lcom/mapartrunner/core/run/BuildEngine$Settings;)Ljava/util/Map;",
            at=@At("RETURN"),cancellable=true,require=1,allow=1,remap=false)
    private static void mapart$gateSupport(MinecraftClient client,BuildPlan plan,RunLifecycle.Context run,BuildEngine.Settings settings,
            CallbackInfoReturnable<Map<Integer,BuildEngine.PlacementAvailability>> callback) {
        callback.setReturnValue(PredictionFaults.gateSupportAvailability(client,plan,run,settings,callback.getReturnValue()));
    }
}
