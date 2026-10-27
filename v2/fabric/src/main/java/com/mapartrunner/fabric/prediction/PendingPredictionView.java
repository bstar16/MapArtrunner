package com.mapartrunner.fabric.prediction;

import net.minecraft.util.math.BlockPos;

/**
 * Read-only view of vanilla's per-position pending prediction state.
 * Query only on the client thread through the current captured world's manager;
 * never retain this view across world replacement or change reconciliation state.
 */
public interface PendingPredictionView {
    /**
     * Returns whether this position has an outstanding vanilla prediction.
     * A false result alone does not establish loaded state, matching state or server success.
     */
    boolean mapart$hasPendingPrediction(BlockPos pos);
}
