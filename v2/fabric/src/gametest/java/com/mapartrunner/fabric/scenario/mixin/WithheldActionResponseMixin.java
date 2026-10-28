package com.mapartrunner.fabric.scenario.mixin;

import com.mapartrunner.fabric.scenario.PredictionFaults;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Gate the actual invocation after vanilla dispatch, never the packet-handler HEAD. */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class WithheldActionResponseMixin {
    @Redirect(method="onPlayerActionResponse(Lnet/minecraft/network/packet/s2c/play/PlayerActionResponseS2CPacket;)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/client/world/ClientWorld;handlePlayerActionResponse(I)V"),
            require=1,allow=1)
    private void mapart$withholdResponse(ClientWorld world,int sequence) {
        if(!PredictionFaults.withhold((ClientPlayNetworkHandler)(Object)this,world,sequence)) {
            world.handlePlayerActionResponse(sequence);
            PredictionFaults.afterNormalResponse((ClientPlayNetworkHandler)(Object)this,world,sequence);
        }
    }
}
