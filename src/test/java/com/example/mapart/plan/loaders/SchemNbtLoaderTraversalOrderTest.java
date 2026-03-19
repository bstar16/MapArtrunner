package com.example.mapart.plan.loaders;

import com.example.mapart.plan.Placement;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchemNbtLoaderTraversalOrderTest {

    @Test
    void ordersRowsInSerpentineTraversal() {
        Block block = allocateBlock();
        List<Placement> ordered = SchemNbtLoader.orderPlacementsForTraversal(List.of(
                new Placement(new BlockPos(2, 0, 1), block),
                new Placement(new BlockPos(0, 0, 0), block),
                new Placement(new BlockPos(1, 0, 1), block),
                new Placement(new BlockPos(2, 0, 0), block),
                new Placement(new BlockPos(1, 0, 0), block),
                new Placement(new BlockPos(0, 0, 1), block)
        ));

        assertEquals(List.of(
                new BlockPos(0, 0, 0),
                new BlockPos(1, 0, 0),
                new BlockPos(2, 0, 0),
                new BlockPos(2, 0, 1),
                new BlockPos(1, 0, 1),
                new BlockPos(0, 0, 1)
        ), ordered.stream().map(Placement::relativePos).toList());
    }

    @Test
    void keepsLayerOrderingBeforeStartingNextLayer() {
        Block block = allocateBlock();
        List<Placement> ordered = SchemNbtLoader.orderPlacementsForTraversal(List.of(
                new Placement(new BlockPos(1, 1, 0), block),
                new Placement(new BlockPos(0, 0, 0), block),
                new Placement(new BlockPos(0, 1, 0), block),
                new Placement(new BlockPos(1, 0, 0), block)
        ));

        assertEquals(List.of(
                new BlockPos(0, 0, 0),
                new BlockPos(1, 0, 0),
                new BlockPos(0, 1, 0),
                new BlockPos(1, 1, 0)
        ), ordered.stream().map(Placement::relativePos).toList());
    }

    private static Block allocateBlock() {
        try {
            java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            return (Block) unsafe.allocateInstance(Block.class);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to allocate block", exception);
        }
    }
}
