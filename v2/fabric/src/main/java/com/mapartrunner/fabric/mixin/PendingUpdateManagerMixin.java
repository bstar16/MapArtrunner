package com.mapartrunner.fabric.mixin;

import com.mapartrunner.fabric.prediction.PendingPredictionView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.network.PendingUpdateManager;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(PendingUpdateManager.class)
abstract class PendingUpdateManagerMixin implements PendingPredictionView {
    @Shadow @Final
    private Long2ObjectOpenHashMap<?> blockPosToPendingUpdate;

    @Override
    public boolean mapart$hasPendingPrediction(BlockPos pos) {
        // Vanilla's similarly named hasPendingUpdate writes the reconciliation baseline.
        return blockPosToPendingUpdate.containsKey(pos.asLong());
    }
}
