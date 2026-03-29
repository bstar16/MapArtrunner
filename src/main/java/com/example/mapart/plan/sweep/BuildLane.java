package com.example.mapart.plan.sweep;

import net.minecraft.util.math.BlockPos;

public record BuildLane(
        int laneIndex,
        LaneAxis axis,
        LaneDirection direction,
        int fixedCoordinate,
        int minProgress,
        int maxProgress,
        int primaryHalfWidth,
        int edgeHalfWidth,
        BlockPos entryPoint,
        BlockPos endPoint,
        double endpointTolerance
) {
    public BuildLane {
        if (laneIndex < 0) {
            throw new IllegalArgumentException("laneIndex must be >= 0");
        }
        if (minProgress > maxProgress) {
            throw new IllegalArgumentException("minProgress must be <= maxProgress");
        }
        if (primaryHalfWidth < 0 || edgeHalfWidth < 0) {
            throw new IllegalArgumentException("lane widths must be >= 0");
        }
        if (endpointTolerance < 0.0) {
            throw new IllegalArgumentException("endpointTolerance must be >= 0");
        }

        if (axis == LaneAxis.X) {
            validateLaneCoordinates(entryPoint.getX(), endPoint.getX(), minProgress, maxProgress, direction);
            if (entryPoint.getZ() != fixedCoordinate || endPoint.getZ() != fixedCoordinate) {
                throw new IllegalArgumentException("entry/end Z must match fixedCoordinate for X lanes");
            }
        } else {
            validateLaneCoordinates(entryPoint.getZ(), endPoint.getZ(), minProgress, maxProgress, direction);
            if (entryPoint.getX() != fixedCoordinate || endPoint.getX() != fixedCoordinate) {
                throw new IllegalArgumentException("entry/end X must match fixedCoordinate for Z lanes");
            }
        }
    }

    private static void validateLaneCoordinates(int entryProgress, int endProgress, int minProgress, int maxProgress,
                                                LaneDirection direction) {
        if (direction == LaneDirection.FORWARD) {
            if (entryProgress != minProgress || endProgress != maxProgress) {
                throw new IllegalArgumentException("FORWARD lane entry/end must align to min/max progress");
            }
            return;
        }

        if (entryProgress != maxProgress || endProgress != minProgress) {
            throw new IllegalArgumentException("REVERSE lane entry/end must align to max/min progress");
        }
    }
}
