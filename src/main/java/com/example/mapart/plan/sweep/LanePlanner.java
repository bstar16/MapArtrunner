package com.example.mapart.plan.sweep;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class LanePlanner {

    public List<BuildLane> planLanes(BlockPos minInclusive, BlockPos maxInclusive, LanePlannerSettings settings) {
        int minX = Math.min(minInclusive.getX(), maxInclusive.getX());
        int maxX = Math.max(minInclusive.getX(), maxInclusive.getX());
        int minY = Math.min(minInclusive.getY(), maxInclusive.getY());
        int minZ = Math.min(minInclusive.getZ(), maxInclusive.getZ());
        int maxZ = Math.max(minInclusive.getZ(), maxInclusive.getZ());

        LaneAxis primaryAxis = choosePrimaryAxis(minX, maxX, minZ, maxZ);
        LaneAxis sweepAxis = primaryAxis.perpendicular();

        int minProgress = primaryAxis == LaneAxis.X ? minX : minZ;
        int maxProgress = primaryAxis == LaneAxis.X ? maxX : maxZ;

        int minSweep = sweepAxis == LaneAxis.X ? minX : minZ;
        int maxSweep = sweepAxis == LaneAxis.X ? maxX : maxZ;

        List<BuildLane> lanes = new ArrayList<>(maxSweep - minSweep + 1);
        for (int laneIndex = 0; laneIndex <= maxSweep - minSweep; laneIndex++) {
            int fixedCoordinate = minSweep + laneIndex;
            LaneDirection direction = laneIndex % 2 == 0 ? LaneDirection.FORWARD : LaneDirection.REVERSE;

            BlockPos entryPoint = createPoint(primaryAxis, direction == LaneDirection.FORWARD ? minProgress : maxProgress,
                    fixedCoordinate, minY);
            BlockPos endPoint = createPoint(primaryAxis, direction == LaneDirection.FORWARD ? maxProgress : minProgress,
                    fixedCoordinate, minY);

            lanes.add(new BuildLane(
                    laneIndex,
                    primaryAxis,
                    direction,
                    fixedCoordinate,
                    minProgress,
                    maxProgress,
                    settings.primaryHalfWidth(),
                    settings.edgeHalfWidth(),
                    entryPoint,
                    endPoint,
                    settings.endpointTolerance()
            ));
        }

        return List.copyOf(lanes);
    }

    private static LaneAxis choosePrimaryAxis(int minX, int maxX, int minZ, int maxZ) {
        int xSpan = maxX - minX + 1;
        int zSpan = maxZ - minZ + 1;
        return xSpan >= zSpan ? LaneAxis.X : LaneAxis.Z;
    }

    private static BlockPos createPoint(LaneAxis primaryAxis, int progressCoordinate, int fixedCoordinate, int y) {
        if (primaryAxis == LaneAxis.X) {
            return new BlockPos(progressCoordinate, y, fixedCoordinate);
        }
        return new BlockPos(fixedCoordinate, y, progressCoordinate);
    }
}
