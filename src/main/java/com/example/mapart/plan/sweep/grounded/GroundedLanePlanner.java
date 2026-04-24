package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class GroundedLanePlanner {
    private static final double DEFAULT_ENDPOINT_TOLERANCE = 0.25D;

    public List<GroundedLanePlan> planLanes(GroundedSchematicBounds bounds, GroundedSweepSettings settings) {
        boolean runAlongX = shouldRunAlongX(bounds, settings.preferLongerAxis());
        List<Integer> centerlines = computeCenterlines(
                runAlongX ? bounds.minZ() : bounds.minX(),
                runAlongX ? bounds.maxZ() : bounds.maxX(),
                settings.sweepHalfWidth(),
                settings.laneStride());

        List<GroundedLanePlan> lanes = new ArrayList<>(centerlines.size());
        for (int i = 0; i < centerlines.size(); i++) {
            int center = centerlines.get(i);
            GroundedLaneDirection direction = directionForLane(runAlongX, i);
            BlockPos start = startPoint(bounds, runAlongX, center, direction);
            BlockPos end = endPoint(bounds, runAlongX, center, direction);
            GroundedCorridorBounds corridor = corridorBounds(bounds, runAlongX, center, settings.sweepHalfWidth());

            lanes.add(new GroundedLanePlan(i, direction, center, start, end, corridor, DEFAULT_ENDPOINT_TOLERANCE));
        }

        return List.copyOf(lanes);
    }

    private static boolean shouldRunAlongX(GroundedSchematicBounds bounds, boolean preferLongerAxis) {
        if (!preferLongerAxis) {
            return true;
        }
        return bounds.xSpan() >= bounds.zSpan();
    }

    private static List<Integer> computeCenterlines(int min, int max, int halfWidth, int stride) {
        List<Integer> centerlines = new ArrayList<>();
        int first = clamp(min + halfWidth, min, max);
        centerlines.add(first);

        int next = first + stride;
        int preferredLast = clamp(max - halfWidth, min, max);
        while (next <= preferredLast) {
            centerlines.add(next);
            next += stride;
        }

        if (centerlines.getLast() != preferredLast) {
            centerlines.add(preferredLast);
        }

        return centerlines;
    }

    private static GroundedLaneDirection directionForLane(boolean runAlongX, int laneIndex) {
        if (runAlongX) {
            return laneIndex % 2 == 0 ? GroundedLaneDirection.EAST : GroundedLaneDirection.WEST;
        }
        return laneIndex % 2 == 0 ? GroundedLaneDirection.SOUTH : GroundedLaneDirection.NORTH;
    }

    private static BlockPos startPoint(GroundedSchematicBounds bounds,
                                       boolean runAlongX,
                                       int center,
                                       GroundedLaneDirection direction) {
        int y = bounds.minY();
        if (runAlongX) {
            int x = direction == GroundedLaneDirection.EAST ? bounds.minX() : bounds.maxX();
            return new BlockPos(x, y, center);
        }

        int z = direction == GroundedLaneDirection.SOUTH ? bounds.minZ() : bounds.maxZ();
        return new BlockPos(center, y, z);
    }

    private static BlockPos endPoint(GroundedSchematicBounds bounds,
                                     boolean runAlongX,
                                     int center,
                                     GroundedLaneDirection direction) {
        int y = bounds.minY();
        if (runAlongX) {
            int x = direction == GroundedLaneDirection.EAST ? bounds.maxX() : bounds.minX();
            return new BlockPos(x, y, center);
        }

        int z = direction == GroundedLaneDirection.SOUTH ? bounds.maxZ() : bounds.minZ();
        return new BlockPos(center, y, z);
    }

    private static GroundedCorridorBounds corridorBounds(GroundedSchematicBounds bounds,
                                                         boolean runAlongX,
                                                         int center,
                                                         int halfWidth) {
        if (runAlongX) {
            return new GroundedCorridorBounds(
                    bounds.minX(),
                    bounds.maxX(),
                    clamp(center - halfWidth, bounds.minZ(), bounds.maxZ()),
                    clamp(center + halfWidth, bounds.minZ(), bounds.maxZ())
            );
        }

        return new GroundedCorridorBounds(
                clamp(center - halfWidth, bounds.minX(), bounds.maxX()),
                clamp(center + halfWidth, bounds.minX(), bounds.maxX()),
                bounds.minZ(),
                bounds.maxZ()
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
