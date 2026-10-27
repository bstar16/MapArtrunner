package com.mapartrunner.core.movement;

import com.mapartrunner.core.model.BuildPlan.BlockSpec;
import com.mapartrunner.core.model.BuildPlan.GridPos;
import com.mapartrunner.core.model.BuildPlan.Target;
import com.mapartrunner.core.placement.PlacementObservation.Evidence;
import com.mapartrunner.core.traversal.LanePlanner.Direction;
import com.mapartrunner.core.traversal.LanePlanner.Lane;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure lane decisions in plan-relative coordinates; this class owns no keys, clock or run state.
 * The owner starts counters at zero, routes returned context once per eligible tick and retains it
 * unchanged through pause. Stop/world change releases effects before discarding context. Stage
 * changes retain unresolved support; only an evaluated ready footprint or termination clears it.
 */
public final class MovementPolicy {
    public MovementIntent decide(PlayerObservation player, LaneEntry entry, SupportObservation support) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(support, "support");
        Lane lane = entry.lane();
        if (player.y() < lane.start().y()) return terminal(entry, Failure.BELOW_BUILD_PLANE);
        var corridor = lane.corridor();
        if (player.x() < corridor.minX() - .5 || player.x() > corridor.maxX() + 1.5
                || player.z() < corridor.minZ() - .5 || player.z() > corridor.maxZ() + 1.5) {
            return terminal(entry, Failure.OUTSIDE_CORRIDOR);
        }
        return entry.stage() == Stage.WALK ? walk(player, entry, support) : enter(player, entry, support);
    }

    private MovementIntent enter(PlayerObservation player, LaneEntry entry, SupportObservation support) {
        Lane lane = entry.lane();
        Direction direction = lane.direction();
        boolean positioned = withinEntry(player.x(), lane.start().x() + .5)
                && withinEntry(player.z(), lane.start().z() + .5);
        Command command = null;
        SupportResult floor = new SupportResult(false, false, entry.supportHoldTicks());
        if (entry.stage() == Stage.ENTER_POSITION && !positioned) {
            command = entryCommand(player, lane);
            Cell current = new Cell((long) Math.floor(player.x()), (long) Math.floor(player.z()));
            floor = checkSupport(Set.of(current, new Cell(current.x() + command.dx(), current.z() + command.dz())), entry, support);
            if (floor.expired()) return terminal(entry, Failure.SUPPORT_TIMEOUT);
        }
        if (entry.entryElapsedTicks() >= 52) return terminal(entry, Failure.ENTRY_TIMEOUT);
        int total = entry.entryElapsedTicks() + 1; // Validated and compared before incrementing.
        if (entry.stage() == Stage.ENTER_POSITION) {
            if (positioned) {
                return intent(entry, Status.ALIGNING_YAW, false, false, Strafe.NONE, false,
                        Stage.ENTER_YAW, 0, total, 0, floor.nextTicks(), 0);
            }
            if (entry.stageElapsedTicks() >= 40) return terminal(entry, Failure.ENTRY_TIMEOUT);
            boolean aligned = yawAligned(player, direction);
            Status status = aligned && !floor.ready() ? Status.HOLDING_SUPPORT : Status.ENTERING;
            boolean translate = aligned && floor.ready();
            return intent(entry, status, translate && command.forward(), translate && command.back(),
                    translate ? command.strafe() : Strafe.NONE, false, Stage.ENTER_POSITION,
                    entry.stageElapsedTicks() + 1, total, 0, floor.nextTicks(), 0);
        }
        if (!positioned) {
            return intent(entry, Status.ENTERING, false, false, Strafe.NONE, false,
                    Stage.ENTER_POSITION, 0, total, 0, floor.nextTicks(), 0);
        }
        boolean aligned = yawAligned(player, direction);
        if (aligned && entry.consecutiveYawAlignedTicks() == 1) {
            return intent(entry, Status.READY_TO_WALK, false, false, Strafe.NONE, false,
                    Stage.WALK, 0, 0, 0, floor.nextTicks(), 0);
        }
        if (entry.stageElapsedTicks() >= 10) return terminal(entry, Failure.YAW_TIMEOUT);
        return intent(entry, Status.ALIGNING_YAW, false, false, Strafe.NONE, false, Stage.ENTER_YAW,
                entry.stageElapsedTicks() + 1, total, aligned ? 1 : 0, floor.nextTicks(), 0);
    }

    private MovementIntent walk(PlayerObservation player, LaneEntry entry, SupportObservation support) {
        Lane lane = entry.lane();
        Direction direction = lane.direction();
        double progress = direction.progressCoordinate(player.x(), player.z());
        double end = direction.progressCoordinate(lane.end().x(), lane.end().z()) + .5;
        if ((end - progress) * direction.forwardSign() <= 1.0) return terminal(entry, Failure.NONE);

        double lateral = direction.lateralCoordinate(player.x(), player.z());
        long nextProgress = (long) Math.floor(progress) + direction.forwardSign();
        long actualLateral = (long) Math.floor(lateral);
        long center = direction.alongX() ? lane.start().z() : lane.start().x();
        var footprint = new HashSet<Cell>();
        footprint.add(cell(direction, nextProgress, center));
        footprint.add(cell(direction, nextProgress, actualLateral));
        // Compare coordinate boundaries directly: subtracting a block index rounds exact .4/.6.
        if (lateral < actualLateral + .4) footprint.add(cell(direction, nextProgress, actualLateral - 1));
        if (lateral > actualLateral + .6) footprint.add(cell(direction, nextProgress, actualLateral + 1));
        SupportResult floor = checkSupport(footprint, entry, support);
        boolean aligned = yawAligned(player, direction);
        boolean yawExpired = !aligned && entry.yawHoldTicks() >= 19;
        int yawTicks = aligned || yawExpired ? 0 : entry.yawHoldTicks() + 1;
        if (floor.expired()) return terminal(entry, Failure.SUPPORT_TIMEOUT);
        if (yawExpired) return terminal(entry, Failure.YAW_UNALIGNED);
        if (!aligned) {
            return intent(entry, Status.HOLDING_YAW, false, false, Strafe.NONE, false,
                    Stage.WALK, 0, 0, 0, floor.nextTicks(), yawTicks);
        }
        Strafe strafe = correction(lateral, center + .5, .08, direction);
        return intent(entry, floor.ready() ? Status.WALKING : Status.HOLDING_SUPPORT,
                floor.ready(), false, strafe, floor.ready() && entry.constantSprint(),
                Stage.WALK, 0, 0, 0, floor.nextTicks(), 0);
    }

    private static Command entryCommand(PlayerObservation player, Lane lane) {
        Direction direction = lane.direction();
        double progress = direction.progressCoordinate(player.x(), player.z());
        double start = direction.progressCoordinate(lane.start().x(), lane.start().z()) + .5;
        if (!withinEntry(progress, start)) {
            boolean forward = (start - progress) * direction.forwardSign() > 0;
            int delta = direction.forwardSign() * (forward ? 1 : -1);
            return new Command(forward, !forward, Strafe.NONE, direction.alongX() ? delta : 0, direction.alongX() ? 0 : delta);
        }
        double lateral = direction.lateralCoordinate(player.x(), player.z());
        double center = direction.lateralCoordinate(lane.start().x(), lane.start().z()) + .5;
        Strafe strafe = correction(lateral, center, .25, direction);
        int delta = direction.forwardSign() * (strafe == Strafe.LEFT ? 1 : -1);
        return new Command(false, false, strafe, direction.alongX() ? 0 : delta, direction.alongX() ? -delta : 0);
    }

    private static Strafe correction(double lateral, double center, double tolerance, Direction direction) {
        // Inclusive coordinate thresholds preserve the declared deadband at representable boundaries.
        if (lateral > center + tolerance) return direction.lateralStrafeSign() > 0 ? Strafe.LEFT : Strafe.RIGHT;
        if (lateral < center - tolerance) return direction.lateralStrafeSign() > 0 ? Strafe.RIGHT : Strafe.LEFT;
        return Strafe.NONE;
    }

    private static boolean withinEntry(double coordinate, double center) {
        return coordinate >= center - .25 && coordinate <= center + .25;
    }

    private static boolean yawAligned(PlayerObservation player, Direction direction) {
        double error = (player.yawDegrees() % 360 - direction.yawDegrees()) % 360;
        if (error > 180) error -= 360;
        if (error < -180) error += 360;
        return Math.abs(error) <= 1.5;
    }

    private static Cell cell(Direction direction, long progress, long lateral) {
        return direction.alongX() ? new Cell(progress, lateral) : new Cell(lateral, progress);
    }

    private static SupportResult checkSupport(Set<Cell> footprint, LaneEntry entry, SupportObservation support) {
        int limit = 0;
        for (Cell cell : footprint) {
            // Grid edges may have off-plan neighbors beyond int range; never wrap them into the plan.
            if (cell.x() < Integer.MIN_VALUE || cell.x() > Integer.MAX_VALUE
                    || cell.z() < Integer.MIN_VALUE || cell.z() > Integer.MAX_VALUE) continue;
            var position = new GridPos((int) cell.x(), entry.lane().start().y(), (int) cell.z());
            Target target = entry.lane().expectedFloor().targetsByPosition().get(position);
            if (target == null) continue; // Deliberate off-plan exemption, not a physical support guarantee.
            SupportSample sample = support.cells().get(position);
            if (sample != null && sample.evidence() == Evidence.OBSERVED && sample.state().orElseThrow().equals(target.block())) continue;
            int cellLimit = sample == null || sample.evidence() != Evidence.OBSERVED
                    || entry.pendingTargetIds().contains(target.id()) ? 80 : 40;
            limit = limit == 0 ? cellLimit : Math.min(limit, cellLimit);
        }
        if (limit == 0) return new SupportResult(true, false, 0);
        if (entry.supportHoldTicks() >= limit - 1) return new SupportResult(false, true, 0);
        return new SupportResult(false, false, entry.supportHoldTicks() + 1);
    }

    private static MovementIntent terminal(LaneEntry entry, Failure failure) {
        return new MovementIntent(failure == Failure.NONE ? Status.COMPLETE : Status.FAILED, failure,
                entry.lane().direction().yawDegrees(), false, false, Strafe.NONE, false, entry.stage(), 0, 0, 0, 0, 0);
    }

    private static MovementIntent intent(LaneEntry entry, Status status, boolean forward, boolean back, Strafe strafe,
                                         boolean sprint, Stage stage, int local, int total, int aligned, int support, int yaw) {
        return new MovementIntent(status, Failure.NONE, entry.lane().direction().yawDegrees(), forward, back, strafe,
                sprint, stage, local, total, aligned, support, yaw);
    }

    private record Cell(long x, long z) { }
    private record Command(boolean forward, boolean back, Strafe strafe, int dx, int dz) { }
    private record SupportResult(boolean ready, boolean expired, int nextTicks) { }

    public enum Stage { ENTER_POSITION, ENTER_YAW, WALK }
    public enum Status { ENTERING, ALIGNING_YAW, READY_TO_WALK, WALKING, HOLDING_SUPPORT, HOLDING_YAW, COMPLETE, FAILED }
    public enum Failure { NONE, ENTRY_TIMEOUT, YAW_TIMEOUT, OUTSIDE_CORRIDOR, BELOW_BUILD_PLANE, SUPPORT_TIMEOUT, YAW_UNALIGNED }
    public enum Strafe { NONE, LEFT, RIGHT }

    public record PlayerObservation(double x, double y, double z, double yawDegrees) {
        public PlayerObservation {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Double.isFinite(yawDegrees)) {
                throw new IllegalArgumentException("Player coordinates and yaw must be finite");
            }
        }
    }

    public record LaneEntry(Lane lane, Stage stage, int stageElapsedTicks, int entryElapsedTicks,
                            int consecutiveYawAlignedTicks, int supportHoldTicks, int yawHoldTicks,
                            Set<Integer> pendingTargetIds, boolean constantSprint) {
        public LaneEntry {
            Objects.requireNonNull(lane, "lane");
            Objects.requireNonNull(stage, "stage");
            pendingTargetIds = Set.copyOf(pendingTargetIds);
            if (stageElapsedTicks < 0 || entryElapsedTicks < 0 || entryElapsedTicks > 52
                    || consecutiveYawAlignedTicks < 0 || consecutiveYawAlignedTicks > 1
                    || consecutiveYawAlignedTicks > stageElapsedTicks
                    || supportHoldTicks < 0 || supportHoldTicks > 79 || yawHoldTicks < 0 || yawHoldTicks > 19
                    || (stage != Stage.ENTER_YAW && consecutiveYawAlignedTicks != 0)
                    || (stage == Stage.WALK && (stageElapsedTicks != 0 || entryElapsedTicks != 0))
                    || (stage != Stage.WALK && yawHoldTicks != 0)
                    || (stage == Stage.ENTER_POSITION && stageElapsedTicks > 40)
                    || (stage == Stage.ENTER_YAW && stageElapsedTicks > 10)) {
                throw new IllegalArgumentException("Counters must describe a bounded reachable lane-entry context");
            }
            for (int id : pendingTargetIds) {
                if (id < 0 || !lane.expectedFloor().containsTargetId(id)) {
                    throw new IllegalArgumentException("Pending target ID must belong to the immutable expected floor: " + id);
                }
            }
        }
    }

    public record SupportSample(Evidence evidence, Optional<BlockSpec> state) {
        public SupportSample {
            Objects.requireNonNull(evidence, "evidence");
            Objects.requireNonNull(state, "state");
            if ((evidence == Evidence.UNKNOWN) != state.isEmpty()) {
                throw new IllegalArgumentException("UNKNOWN requires no state; PREDICTED and OBSERVED require state");
            }
        }
    }

    public record SupportObservation(Map<GridPos, SupportSample> cells) {
        public SupportObservation {
            cells = Map.copyOf(cells);
        }
    }

    public record MovementIntent(Status status, Failure failure, double desiredYaw,
                                 boolean forward, boolean back, Strafe strafe, boolean sprint,
                                 Stage nextStage, int nextStageElapsedTicks, int nextEntryElapsedTicks,
                                 int nextYawAlignedTicks, int nextSupportHoldTicks, int nextYawHoldTicks) { }
}
