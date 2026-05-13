package com.example.mapart.plan.placement;

import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Builds vanilla-valid BlockHitResult values for survival/dedicated-server block placement.
 *
 * <p>The server validates that the hit vector lies on the face of the reported hit block.
 * The correct model is:
 * <pre>
 *   supportPos = adjacent real block being clicked
 *   side       = direction from supportPos toward targetPos
 *   hitVec     = center(supportPos) + side.vector * 0.5   ← on the clicked face
 *   BlockHitResult(hitVec, side, supportPos, false)
 * </pre>
 *
 * <p>Using {@code center(targetPos) + side.vector * 0.5} places the hit vector one block
 * beyond the face, which dedicated servers reject with "Location … too far away from hit block".
 */
public final class ServerPlacementHitResultFactory {

    private ServerPlacementHitResultFactory() {}

    /**
     * Constructs a server-valid BlockHitResult for placing a block at {@code targetPos}
     * by clicking the {@code side} face of {@code supportPos}.
     *
     * <p>Precondition: {@code supportPos.offset(side).equals(targetPos)}.
     *
     * @param supportPos the existing block whose face is being clicked
     * @param side       the direction from supportPos toward targetPos
     * @param targetPos  the air position where the new block will appear
     * @return a BlockHitResult whose hit vector lies exactly on the clicked face of supportPos
     * @throws IllegalArgumentException if supportPos.offset(side) != targetPos
     */
    public static BlockHitResult build(BlockPos supportPos, Direction side, BlockPos targetPos) {
        if (!supportPos.offset(side).equals(targetPos)) {
            throw new IllegalArgumentException(
                    "supportPos.offset(side) must equal targetPos. " +
                    "supportPos=" + supportPos.toShortString() +
                    " side=" + side +
                    " offset=" + supportPos.offset(side).toShortString() +
                    " targetPos=" + targetPos.toShortString()
            );
        }
        Vec3d hitVec = Vec3d.ofCenter(supportPos).add(
                side.getOffsetX() * 0.5,
                side.getOffsetY() * 0.5,
                side.getOffsetZ() * 0.5
        );
        return new BlockHitResult(hitVec, side, supportPos, false);
    }
}
