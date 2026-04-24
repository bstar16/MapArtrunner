package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.sweep.LaneAxis;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class GroundedLanePlanner {

    public GroundedLanePlan plan(BuildPlan plan, BlockPos origin, GroundedSweepSettings settings) {
        return plan(GroundedSchematicBounds.from(plan, origin), settings);
    }

    public GroundedLanePlan plan(GroundedSchematicBounds bounds, GroundedSweepSettings settings) {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(settings, "settings");

        LaneAxis primaryAxis = choosePrimaryAxis(bounds, settings.preferLongerAxis());
        int minProgress = primaryAxis == LaneAxis.X ? bounds.worldMinInclusive().getX() : bounds.worldMinInclusive().getZ();
        int maxProgress = primaryAxis == LaneAxis.X ? bounds.worldMaxInclusive().getX() : bounds.worldMaxInclusive().getZ();

        LaneAxis sweepAxis = primaryAxis.perpendicular();
        int minSweep = sweepAxis == LaneAxis.X ? bounds.worldMinInclusive().getX() : bounds.worldMinInclusive().getZ();
        int maxSweep = sweepAxis == LaneAxis.X ? bounds.worldMaxInclusive().getX() : bounds.worldMaxInclusive().getZ();

        List<Integer> centerlines = computeCenterlines(minSweep, maxSweep, settings.sweepHalfWidth(), settings.laneStride());
        List<GroundedSweepLane> lanes = new ArrayList<>(centerlines.size());

        for (int laneIndex = 0; laneIndex < centerlines.size(); laneIndex++) {
            int centerline = centerlines.get(laneIndex);
            GroundedSweepDirection direction = directionFor(primaryAxis, laneIndex);
            int startProgress = direction.isPositive() ? minProgress : maxProgress;
            int endProgress = direction.isPositive() ? maxProgress : minProgress;

            BlockPos startPoint = createPoint(primaryAxis, startProgress, centerline, bounds.worldMinInclusive().getY());
            BlockPos endPoint = createPoint(primaryAxis, endProgress, centerline, bounds.worldMinInclusive().getY());

            lanes.add(new GroundedSweepLane(
                    laneIndex,
                    primaryAxis,
                    direction,
                    centerline,
                    startPoint,
                    endPoint,
                    centerline - settings.sweepHalfWidth(),
                    centerline + settings.sweepHalfWidth(),
                    settings.endpointTolerance()
            ));
        }

        return new GroundedLanePlan(bounds, primaryAxis, lanes);
    }

    private static LaneAxis choosePrimaryAxis(GroundedSchematicBounds bounds, boolean preferLongerAxis) {
        if (!preferLongerAxis) {
            return LaneAxis.X;
        }
        return bounds.xSpan() >= bounds.zSpan() ? LaneAxis.X : LaneAxis.Z;
    }

    private static List<Integer> computeCenterlines(int minSweep, int maxSweep, int halfWidth, int stride) {
        if (maxSweep - minSweep + 1 < (halfWidth * 2 + 1)) {
            throw new IllegalArgumentException("sweep span is too small for configured sweep width");
        }

        int firstCenterline = minSweep + halfWidth;
        int clampedLastCenterline = maxSweep - halfWidth;

        List<Integer> centerlines = new ArrayList<>();
        for (int current = firstCenterline; current <= clampedLastCenterline; current += stride) {
            centerlines.add(current);
        }

        int last = centerlines.getLast();
        if (last + halfWidth < maxSweep && last != clampedLastCenterline) {
            centerlines.add(clampedLastCenterline);
        }

        return List.copyOf(centerlines);
    }

    private static GroundedSweepDirection directionFor(LaneAxis primaryAxis, int laneIndex) {
        boolean even = laneIndex % 2 == 0;
        if (primaryAxis == LaneAxis.X) {
            return even ? GroundedSweepDirection.EAST : GroundedSweepDirection.WEST;
        }
        return even ? GroundedSweepDirection.SOUTH : GroundedSweepDirection.NORTH;
    }

    private static BlockPos createPoint(LaneAxis primaryAxis, int progressCoordinate, int sweepCoordinate, int y) {
        if (primaryAxis == LaneAxis.X) {
            return new BlockPos(progressCoordinate, y, sweepCoordinate);
        }
        return new BlockPos(sweepCoordinate, y, progressCoordinate);
    }
}
