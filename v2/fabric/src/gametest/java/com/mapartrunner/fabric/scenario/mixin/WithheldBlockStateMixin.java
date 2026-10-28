package com.mapartrunner.fabric.scenario.mixin;

import com.mapartrunner.fabric.scenario.PredictionFaults;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Withhold only actual target-state applications; the full-chunk precondition never cancels import. */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class WithheldBlockStateMixin {
    @Redirect(method="onBlockUpdate(Lnet/minecraft/network/packet/s2c/play/BlockUpdateS2CPacket;)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/client/world/ClientWorld;handleBlockUpdate(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)V"),
            require=1,allow=1)
    private void mapart$single(ClientWorld world,BlockPos pos,BlockState state,int flags) {
        if(!PredictionFaults.withholdState((ClientPlayNetworkHandler)(Object)this,world,pos,state,flags,PredictionFaults.StateRoute.SINGLE))
            world.handleBlockUpdate(pos,state,flags);
    }

    @Redirect(method="method_34007(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/client/world/ClientWorld;handleBlockUpdate(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)V"),
            require=1,allow=1)
    private void mapart$delta(ClientWorld world,BlockPos pos,BlockState state,int flags) {
        if(!PredictionFaults.withholdState((ClientPlayNetworkHandler)(Object)this,world,pos,state,flags,PredictionFaults.StateRoute.DELTA))
            world.handleBlockUpdate(pos,state,flags);
    }

    @Inject(method="loadChunk(IILnet/minecraft/network/packet/s2c/play/ChunkData;)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/client/world/ClientChunkManager;loadChunkFromPacket(IILnet/minecraft/network/PacketByteBuf;Ljava/util/Map;Ljava/util/function/Consumer;)Lnet/minecraft/world/chunk/WorldChunk;"),
            require=1,allow=1)
    private void mapart$beforeImport(int x,int z,ChunkData data,CallbackInfo callback) {
        PredictionFaults.beforeChunkImport((ClientPlayNetworkHandler)(Object)this,x,z);
    }
}
