package com.mapartrunner.core.traversal;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.GridPos;
import com.mapartrunner.core.model.BuildPlan.Target;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure source-relative geometry; expected targets are snapshotted once and shared by all lanes. */
public final class LanePlanner {
    public List<Lane> plan(BuildPlan plan, int halfWidth, int stride) {
        Objects.requireNonNull(plan, "plan");
        long corridorWidth = 2L * halfWidth + 1;
        if (halfWidth < 0 || stride <= 0 || stride > corridorWidth) {
            throw new IllegalArgumentException("Require halfWidth >= 0 and 0 < stride <= corridor width");
        }
        if (plan.targets().isEmpty()) return List.of();

        var targets = new HashMap<GridPos, Target>();
        for (Target target : plan.targets()) targets.put(target.relativePos(), target);
        var floor = new ExpectedFloor(targets);
        int maxX = plan.dimensions().width() - 1;
        int maxZ = plan.dimensions().depth() - 1;
        long first = plan.dimensions().depth() <= corridorWidth ? Math.min(halfWidth, maxZ) : halfWidth;
        long last = plan.dimensions().depth() <= corridorWidth ? first : (long) maxZ - halfWidth;
        var lanes = new ArrayList<Lane>();
        for (long center = first; ; center = Math.min(center + stride, last)) {
            int id = lanes.size();
            Direction direction = id % 2 == 0 ? Direction.EAST : Direction.WEST;
            GridPos start = new GridPos(id % 2 == 0 ? 0 : maxX, floor.planeY, (int) center);
            GridPos end = new GridPos(id % 2 == 0 ? maxX : 0, floor.planeY, (int) center);
            var corridor = new Corridor(0, maxX, (int) Math.max(0, center - halfWidth),
                    (int) Math.min(maxZ, center + halfWidth));
            lanes.add(new Lane(id, direction, start, end, corridor, floor));
            if (center == last) break;
        }
        return List.copyOf(lanes);
    }

    public enum Direction {
        EAST, WEST, SOUTH, NORTH;

        public double yawDegrees() {
            return switch (this) {
                case EAST -> -90;
                case WEST -> 90;
                case SOUTH -> 0;
                case NORTH -> 180;
            };
        }

        public boolean alongX() {
            return switch (this) {
                case EAST, WEST -> true;
                case SOUTH, NORTH -> false;
            };
        }

        public double progressCoordinate(double x, double z) {
            return switch (this) {
                case EAST, WEST -> x;
                case SOUTH, NORTH -> z;
            };
        }

        public double lateralCoordinate(double x, double z) {
            return switch (this) {
                case EAST, WEST -> z;
                case SOUTH, NORTH -> x;
            };
        }

        public int forwardSign() {
            return switch (this) {
                case EAST, SOUTH -> 1;
                case WEST, NORTH -> -1;
            };
        }

        /** Positive lateral error needs LEFT for EAST/NORTH, RIGHT for WEST/SOUTH. */
        public int lateralStrafeSign() {
            return switch (this) {
                case EAST, NORTH -> 1;
                case WEST, SOUTH -> -1;
            };
        }
    }

    public record Corridor(int minX, int maxX, int minZ, int maxZ) {
        public Corridor {
            if (minX > maxX || minZ > maxZ) {
                throw new IllegalArgumentException("Corridor minimum must not exceed maximum");
            }
        }

        private boolean contains(GridPos position) {
            return position.x() >= minX && position.x() <= maxX
                    && position.z() >= minZ && position.z() <= maxZ;
        }
    }

    /** Whole-plan expectations, independent of pending work and retained with traversal snapshots. */
    public static final class ExpectedFloor {
        private final Map<GridPos, Target> targetsByPosition;
        private final Set<Integer> targetIds;
        private final int planeY;

        public ExpectedFloor(Map<GridPos, Target> targetsByPosition) {
            this.targetsByPosition = Map.copyOf(targetsByPosition);
            var ids = new HashSet<Integer>();
            Integer commonY = null;
            for (var entry : this.targetsByPosition.entrySet()) {
                Target target = entry.getValue();
                if (!entry.getKey().equals(target.relativePos())) {
                    throw new IllegalArgumentException("Expected-floor key must equal target position: " + entry.getKey());
                }
                if (!ids.add(target.id())) {
                    throw new IllegalArgumentException("Duplicate expected-floor target ID: " + target.id());
                }
                if (commonY != null && commonY != entry.getKey().y()) {
                    throw new IllegalArgumentException("Expected-floor targets must share one Y level");
                }
                commonY = entry.getKey().y();
            }
            targetIds = Set.copyOf(ids);
            planeY = commonY == null ? 0 : commonY;
        }

        public Map<GridPos, Target> targetsByPosition() {
            return targetsByPosition;
        }

        public boolean containsTargetId(int targetId) {
            return targetIds.contains(targetId);
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof ExpectedFloor floor
                    && targetsByPosition.equals(floor.targetsByPosition);
        }

        @Override
        public int hashCode() {
            return targetsByPosition.hashCode();
        }
    }

    public record Lane(int id, Direction direction, GridPos start, GridPos end,
                       Corridor corridor, ExpectedFloor expectedFloor) {
        public Lane {
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(corridor, "corridor");
            Objects.requireNonNull(expectedFloor, "expectedFloor");
            if (id < 0) throw new IllegalArgumentException("Lane ID must be nonnegative");
            if (start.y() != end.y()) throw new IllegalArgumentException("Lane endpoints must share Y");
            if (!expectedFloor.targetsByPosition.isEmpty() && start.y() != expectedFloor.planeY) {
                throw new IllegalArgumentException("Lane endpoint Y must match expected floor Y");
            }
            boolean aligned = direction.alongX() ? start.z() == end.z() : start.x() == end.x();
            boolean ordered = switch (direction) {
                case EAST -> start.x() <= end.x();
                case WEST -> start.x() >= end.x();
                case SOUTH -> start.z() <= end.z();
                case NORTH -> start.z() >= end.z();
            };
            if (!aligned || !ordered) {
                throw new IllegalArgumentException("Lane endpoints must align with direction " + direction);
            }
            if (!corridor.contains(start) || !corridor.contains(end)) {
                throw new IllegalArgumentException("Corridor must contain both lane endpoints");
            }
        }
    }
}
