package com.example.mapart.mixin;

import com.example.mapart.player.ManualAirPlaceModule;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientManualAirPlaceMixin {
    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void mapart$manualAirPlace(CallbackInfo ci) {
        if (ManualAirPlaceModule.tryManualPlace((MinecraftClient) (Object) this)) {
            ci.cancel();
        }
    }
}
