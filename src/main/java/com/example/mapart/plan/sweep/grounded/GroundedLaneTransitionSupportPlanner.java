package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class GroundedLaneTransitionSupportPlanner {
    private static final int TRANSITION_PAD_RADIUS = 1;

    List<GroundedSweepPlacementExecutor.PlacementTarget> planTargets(
            BuildPlan plan,
            BlockPos origin,
            GroundedSchematicBounds bounds,
            GroundedSweepLane fromLane,
            GroundedSweepLane toLane,
            Map<Integer, Placement> placementLookup
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(fromLane, "fromLane");
        Objects.requireNonNull(toLane, "toLane");
        Objects.requireNonNull(placementLookup, "placementLookup");

        List<GroundedSweepPlacementExecutor.PlacementTarget> targets = new ArrayList<>();
        int buildPlaneY = bounds.minY();
        int minCenterline = Math.min(fromLane.centerlineCoordinate(), toLane.centerlineCoordinate()) - TRANSITION_PAD_RADIUS;
        int maxCenterline = Math.max(fromLane.centerlineCoordinate(), toLane.centerlineCoordinate()) + TRANSITION_PAD_RADIUS;

        int fromProgress = fromLane.direction().alongX() ? fromLane.endPoint().getX() : fromLane.endPoint().getZ();
        int toProgress = toLane.direction().alongX() ? toLane.startPoint().getX() : toLane.startPoint().getZ();
        int minProgress = Math.min(fromProgress, toProgress) - TRANSITION_PAD_RADIUS;
        int maxProgress = Math.max(fromProgress, toProgress) + TRANSITION_PAD_RADIUS;

        for (int i = 0; i < plan.placements().size(); i++) {
            Placement placement = plan.placements().get(i);
            BlockPos worldPos = origin.add(placement.relativePos());
            if (!withinBounds(worldPos, bounds) || worldPos.getY() != buildPlaneY) {
                continue;
            }

            int progress = toLane.direction().alongX() ? worldPos.getX() : worldPos.getZ();
            int lateral = toLane.direction().alongX() ? worldPos.getZ() : worldPos.getX();
            if (progress < minProgress || progress > maxProgress) {
                continue;
            }
            if (lateral < minCenterline || lateral > maxCenterline) {
                continue;
            }

            placementLookup.put(i, placement);
            targets.add(new GroundedSweepPlacementExecutor.PlacementTarget(i, worldPos));
        }
        return List.copyOf(targets);
    }

    private static boolean withinBounds(BlockPos pos, GroundedSchematicBounds bounds) {
        return pos.getX() >= bounds.minX() && pos.getX() <= bounds.maxX()
                && pos.getY() >= bounds.minY() && pos.getY() <= bounds.maxY()
                && pos.getZ() >= bounds.minZ() && pos.getZ() <= bounds.maxZ();
    }
}
