package com.mapartrunner.fabric.scenario.mixin;

import com.mapartrunner.core.run.BuildEngine;
import com.mapartrunner.fabric.runtime.FabricBuildAdapter;
import com.mapartrunner.fabric.scenario.PredictionFaults;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value=FabricBuildAdapter.class, remap=false)
public abstract class ComputedDecisionBarrierMixin {
    @ModifyVariable(method="tick()V", at=@At("STORE"), ordinal=0, require=1, allow=1, remap=false)
    private BuildEngine.TickDecision mapart$afterDecision(BuildEngine.TickDecision decision) {
        PredictionFaults.afterDecision(decision);
        return decision;
    }
}
