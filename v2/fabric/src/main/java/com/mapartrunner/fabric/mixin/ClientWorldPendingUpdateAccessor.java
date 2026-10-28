package com.mapartrunner.fabric.mixin;

import net.minecraft.client.network.PendingUpdateManager;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access only the current captured world's manager; never retain it across world replacement. */
@Mixin(ClientWorld.class)
public interface ClientWorldPendingUpdateAccessor {
    @Accessor("pendingUpdateManager")
    PendingUpdateManager mapart$getPendingUpdateManager();
}
