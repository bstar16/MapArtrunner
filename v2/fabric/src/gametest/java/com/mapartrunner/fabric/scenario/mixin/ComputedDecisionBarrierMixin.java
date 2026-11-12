package com.mapartrunner.fabric.scenario.mixin;

import com.mapartrunner.core.run.BuildEngine;
import com.mapartrunner.fabric.runtime.FabricBuildAdapter;
import com.mapartrunner.fabric.scenario.PredictionFaults;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=FabricBuildAdapter.class, remap=false)
public abstract class ComputedDecisionBarrierMixin {
    @Inject(method="tick()V", at=@At("HEAD"), cancellable=true, require=1, allow=1, remap=false)
    private void mapart$deferOwnedExpiry(CallbackInfo ci) {
        if(PredictionFaults.deferAdapterTick((FabricBuildAdapter)(Object)this)) ci.cancel();
    }

    @Inject(method="tick()V", at=@At("RETURN"), require=1, remap=false)
    private void mapart$afterAdapterTick(CallbackInfo ci) {
        PredictionFaults.afterAdapterTick((FabricBuildAdapter)(Object)this);
    }

    @ModifyVariable(method="tick()V", at=@At("STORE"), ordinal=0, require=1, allow=1, remap=false)
    private BuildEngine.TickDecision mapart$afterDecision(BuildEngine.TickDecision decision) {
        PredictionFaults.afterDecision(decision);
        return decision;
    }
}
