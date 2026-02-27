package com.example.mapart.plan.compare;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.state.BuildSession;
import com.example.mapart.plan.state.WorldPlacementResolver;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public class PlacementStatusResolver {
    private final WorldPlacementResolver placementResolver = new WorldPlacementResolver();

    public List<PlacementStatusSnapshot> resolve(
            ClientWorld world,
            BuildSession session,
            Predicate<PlacementStatusSnapshot> filter
    ) {
        BuildPlan plan = session.getPlan();
        List<PlacementStatusSnapshot> snapshots = new ArrayList<>();
        int nextIndex = session.getCurrentPlacementIndex();

        for (int i = 0; i < plan.placements().size(); i++) {
            Placement placement = plan.placements().get(i);
            Optional<BlockPos> absolutePos = placementResolver.resolveAbsolute(session, placement);
            if (absolutePos.isEmpty()) {
                continue;
            }

            PlacementStatus status = resolveStatus(world, placement, absolutePos.get(), i, nextIndex);
            PlacementStatusSnapshot snapshot = new PlacementStatusSnapshot(i, placement, absolutePos.get(), status, i == nextIndex);
            if (filter.test(snapshot)) {
                snapshots.add(snapshot);
            }
        }

        return snapshots;
    }

    public Optional<PlacementStatusSnapshot> nextTarget(ClientWorld world, BuildSession session) {
        int nextIndex = session.getCurrentPlacementIndex();
        if (nextIndex < 0 || nextIndex >= session.getPlan().placements().size()) {
            return Optional.empty();
        }

        Placement placement = session.getPlan().placements().get(nextIndex);
        Optional<BlockPos> absolutePos = placementResolver.resolveAbsolute(session, placement);
        if (absolutePos.isEmpty()) {
            return Optional.empty();
        }

        PlacementStatus status = resolveStatus(world, placement, absolutePos.get(), nextIndex, nextIndex);
        return Optional.of(new PlacementStatusSnapshot(nextIndex, placement, absolutePos.get(), status, true));
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
