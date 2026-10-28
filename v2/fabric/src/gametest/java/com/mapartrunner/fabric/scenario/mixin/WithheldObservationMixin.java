package com.mapartrunner.fabric.scenario.mixin;

import com.mapartrunner.core.placement.PlacementObservation;
import com.mapartrunner.fabric.runtime.WorldEvidenceReader;
import com.mapartrunner.fabric.scenario.PredictionFaults;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Omit one producer result without changing the same capture's honest support. */
@Mixin(value=WorldEvidenceReader.class,remap=false)
public abstract class WithheldObservationMixin {
    @Inject(method="placementObservations(Lcom/mapartrunner/fabric/runtime/WorldEvidenceReader$CapturedEvidence;)Ljava/util/List;",
            at=@At("RETURN"),cancellable=true,require=1,allow=1,remap=false)
    private static void mapart$omitObservation(WorldEvidenceReader.CapturedEvidence captured,
                                              CallbackInfoReturnable<List<PlacementObservation>> callback) {
        callback.setReturnValue(PredictionFaults.omit(captured,callback.getReturnValue()));
    }
}
