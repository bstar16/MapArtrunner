package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class GroundedSweepLanePlanner {

    public List<GroundedSweepLane> planLanes(GroundedSchematicBounds bounds, GroundedSweepSettings settings) {
        Orientation orientation = chooseOrientation(bounds, settings.preferLongerAxis());
        int minSweep = orientation.minSweep(bounds);
        int maxSweep = orientation.maxSweep(bounds);

        List<Integer> centerlines = computeCenterlines(minSweep, maxSweep, settings.sweepHalfWidth(), settings.laneStride());
        List<GroundedSweepLane> lanes = new ArrayList<>(centerlines.size());

        for (int laneIndex = 0; laneIndex < centerlines.size(); laneIndex++) {
            int centerline = centerlines.get(laneIndex);
            GroundedLaneDirection direction = orientation.directionForLane(laneIndex);

            BlockPos start = orientation.start(bounds, bounds.minY(), centerline, direction);
            BlockPos end = orientation.end(bounds, bounds.minY(), centerline, direction);

            GroundedLaneCorridorBounds corridor = orientation.corridor(bounds, centerline, settings.sweepHalfWidth());
            lanes.add(new GroundedSweepLane(
                    laneIndex,
                    centerline,
                    direction,
                    start,
                    end,
                    corridor,
                    settings.endpointTolerance()
            ));
        }

        return List.copyOf(lanes);
    }

    private static List<Integer> computeCenterlines(int minSweep, int maxSweep, int halfWidth, int laneStride) {
        int firstCenter = clamp(minSweep + halfWidth, minSweep, maxSweep);
        int finalCenter = clamp(maxSweep - halfWidth, minSweep, maxSweep);

        List<Integer> centers = new ArrayList<>();
        centers.add(firstCenter);

        int cursor = firstCenter;
        while (cursor + laneStride <= finalCenter) {
            cursor += laneStride;
            centers.add(cursor);
        }

        if (centers.getLast() != finalCenter) {
            centers.add(finalCenter);
        }

        return centers;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Orientation chooseOrientation(GroundedSchematicBounds bounds, boolean preferLongerAxis) {
        if (!preferLongerAxis || bounds.xSpan() >= bounds.zSpan()) {
            return Orientation.EAST_WEST;
        }
        return Orientation.NORTH_SOUTH;
    }

    private enum Orientation {
        EAST_WEST {
            @Override
            int minSweep(GroundedSchematicBounds bounds) {
                return bounds.minZ();
            }

            @Override
            int maxSweep(GroundedSchematicBounds bounds) {
                return bounds.maxZ();
            }

            @Override
            GroundedLaneDirection directionForLane(int laneIndex) {
                return laneIndex % 2 == 0 ? GroundedLaneDirection.EAST : GroundedLaneDirection.WEST;
            }

            @Override
            BlockPos start(GroundedSchematicBounds bounds, int y, int centerline, GroundedLaneDirection direction) {
                int x = direction == GroundedLaneDirection.EAST ? bounds.minX() : bounds.maxX();
                return new BlockPos(x, y, centerline);
            }

            @Override
            BlockPos end(GroundedSchematicBounds bounds, int y, int centerline, GroundedLaneDirection direction) {
                int x = direction == GroundedLaneDirection.EAST ? bounds.maxX() : bounds.minX();
                return new BlockPos(x, y, centerline);
            }

            @Override
            GroundedLaneCorridorBounds corridor(GroundedSchematicBounds bounds, int centerline, int halfWidth) {
                return new GroundedLaneCorridorBounds(
                        bounds.minX(),
                        bounds.maxX(),
                        clamp(centerline - halfWidth, bounds.minZ(), bounds.maxZ()),
                        clamp(centerline + halfWidth, bounds.minZ(), bounds.maxZ())
                );
            }
        },
        NORTH_SOUTH {
            @Override
            int minSweep(GroundedSchematicBounds bounds) {
                return bounds.minX();
            }

            @Override
            int maxSweep(GroundedSchematicBounds bounds) {
                return bounds.maxX();
            }

            @Override
            GroundedLaneDirection directionForLane(int laneIndex) {
                return laneIndex % 2 == 0 ? GroundedLaneDirection.SOUTH : GroundedLaneDirection.NORTH;
            }

            @Override
            BlockPos start(GroundedSchematicBounds bounds, int y, int centerline, GroundedLaneDirection direction) {
                int z = direction == GroundedLaneDirection.SOUTH ? bounds.minZ() : bounds.maxZ();
                return new BlockPos(centerline, y, z);
            }

            @Override
            BlockPos end(GroundedSchematicBounds bounds, int y, int centerline, GroundedLaneDirection direction) {
                int z = direction == GroundedLaneDirection.SOUTH ? bounds.maxZ() : bounds.minZ();
                return new BlockPos(centerline, y, z);
            }

            @Override
            GroundedLaneCorridorBounds corridor(GroundedSchematicBounds bounds, int centerline, int halfWidth) {
                return new GroundedLaneCorridorBounds(
                        clamp(centerline - halfWidth, bounds.minX(), bounds.maxX()),
                        clamp(centerline + halfWidth, bounds.minX(), bounds.maxX()),
                        bounds.minZ(),
                        bounds.maxZ()
                );
            }
        };

        abstract int minSweep(GroundedSchematicBounds bounds);

        abstract int maxSweep(GroundedSchematicBounds bounds);

        abstract GroundedLaneDirection directionForLane(int laneIndex);

        abstract BlockPos start(GroundedSchematicBounds bounds, int y, int centerline, GroundedLaneDirection direction);

        abstract BlockPos end(GroundedSchematicBounds bounds, int y, int centerline, GroundedLaneDirection direction);

        abstract GroundedLaneCorridorBounds corridor(GroundedSchematicBounds bounds, int centerline, int halfWidth);
    }
}
