package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class GroundedLaneTransitionSupportPlanner {
    private static final int DEFAULT_TRANSITION_PAD_HALF_WIDTH = 1;
    private static final int DEFAULT_FORWARD_PAD_RADIUS = 1;

    public List<SupportTarget> buildSupportTargets(
            BuildPlan plan,
            BlockPos origin,
            GroundedSchematicBounds bounds,
            GroundedSweepLane fromLane,
            GroundedSweepLane toLane
    ) {
        return buildSupportTargets(plan, origin, bounds, fromLane, toLane, DEFAULT_TRANSITION_PAD_HALF_WIDTH, DEFAULT_FORWARD_PAD_RADIUS);
    }

    List<SupportTarget> buildSupportTargets(
            BuildPlan plan,
            BlockPos origin,
            GroundedSchematicBounds bounds,
            GroundedSweepLane fromLane,
            GroundedSweepLane toLane,
            int transitionPadHalfWidth,
            int forwardPadRadius
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(fromLane, "fromLane");
        Objects.requireNonNull(toLane, "toLane");

        int planeY = bounds.minY();
        int minCenterline = Math.min(fromLane.centerlineCoordinate(), toLane.centerlineCoordinate());
        int maxCenterline = Math.max(fromLane.centerlineCoordinate(), toLane.centerlineCoordinate());

        boolean alongX = toLane.direction().alongX();
        int fromForward = alongX ? fromLane.endPoint().getX() : fromLane.endPoint().getZ();
        int toForward = alongX ? toLane.startPoint().getX() : toLane.startPoint().getZ();
        int minForward = Math.min(fromForward, toForward) - Math.max(0, forwardPadRadius);
        int maxForward = Math.max(fromForward, toForward) + Math.max(0, forwardPadRadius);

        int minLateral = minCenterline - Math.max(0, transitionPadHalfWidth);
        int maxLateral = maxCenterline + Math.max(0, transitionPadHalfWidth);

        List<SupportTarget> targets = new ArrayList<>();
        for (int i = 0; i < plan.placements().size(); i++) {
            Placement placement = plan.placements().get(i);
            BlockPos worldPos = origin.add(placement.relativePos());
            if (!insideBounds(worldPos, bounds)) {
                continue;
            }
            if (worldPos.getY() != planeY) {
                continue;
            }

            int forward = alongX ? worldPos.getX() : worldPos.getZ();
            int lateral = alongX ? worldPos.getZ() : worldPos.getX();
            if (forward < minForward || forward > maxForward) {
                continue;
            }
            if (lateral < minLateral || lateral > maxLateral) {
                continue;
            }
            if (lateral < minCenterline || lateral > maxCenterline) {
                continue;
            }

            targets.add(new SupportTarget(i, worldPos, placement));
        }

        return List.copyOf(targets);
    }

    private static boolean insideBounds(BlockPos worldPos, GroundedSchematicBounds bounds) {
        return worldPos.getX() >= bounds.minX() && worldPos.getX() <= bounds.maxX()
                && worldPos.getY() >= bounds.minY() && worldPos.getY() <= bounds.maxY()
                && worldPos.getZ() >= bounds.minZ() && worldPos.getZ() <= bounds.maxZ();
    }

    public record SupportTarget(int placementIndex, BlockPos worldPos, Placement placement) {
        public SupportTarget {
            Objects.requireNonNull(worldPos, "worldPos");
        }
    }
}
