package com.example.mapart.plan.compare;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.state.BuildSession;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public class PlacementStatusResolver {
    public List<PlacementStatusSnapshot> resolve(
            ClientWorld world,
            BuildSession session,
            Predicate<PlacementStatusSnapshot> filter
    ) {
        BuildPlan plan = session.getPlan();
        BlockPos origin = session.getOrigin();
        List<PlacementStatusSnapshot> snapshots = new ArrayList<>();
        int nextIndex = session.getProgress().getCurrentPlacementIndex();

        for (int i = 0; i < plan.placements().size(); i++) {
            Placement placement = plan.placements().get(i);
            BlockPos absolutePos = origin.add(placement.relativePos());
            PlacementStatus status = resolveStatus(world, placement, absolutePos, i, nextIndex);
            PlacementStatusSnapshot snapshot = new PlacementStatusSnapshot(i, placement, absolutePos, status, i == nextIndex);
            if (filter.test(snapshot)) {
                snapshots.add(snapshot);
            }
        }

        return snapshots;
    }

    public Optional<PlacementStatusSnapshot> nextTarget(ClientWorld world, BuildSession session) {
        int nextIndex = session.getProgress().getCurrentPlacementIndex();
        if (nextIndex < 0 || nextIndex >= session.getPlan().placements().size() || session.getOrigin() == null) {
            return Optional.empty();
        }

        Placement placement = session.getPlan().placements().get(nextIndex);
        BlockPos absolutePos = session.getOrigin().add(placement.relativePos());
        PlacementStatus status = resolveStatus(world, placement, absolutePos, nextIndex, nextIndex);
        return Optional.of(new PlacementStatusSnapshot(nextIndex, placement, absolutePos, status, true));
    }

    private PlacementStatus resolveStatus(ClientWorld world, Placement expected, BlockPos absolutePos, int index, int nextIndex) {
        if (!world.isChunkLoaded(absolutePos.getX() >> 4, absolutePos.getZ() >> 4)) {
            return PlacementStatus.PENDING;
        }

        if (world.getBlockState(absolutePos).isOf(expected.block())) {
            return PlacementStatus.CORRECT;
        }

        if (world.getBlockState(absolutePos).isOf(Blocks.AIR)) {
            return PlacementStatus.MISSING;
        }

        if (index >= nextIndex) {
            return PlacementStatus.INCORRECT;
        }

        return PlacementStatus.PENDING;
    }
}
