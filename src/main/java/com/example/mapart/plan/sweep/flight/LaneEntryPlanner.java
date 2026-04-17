package com.example.mapart.plan.sweep.flight;

import com.example.mapart.plan.sweep.BuildLane;
import com.example.mapart.plan.sweep.LaneAxis;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;

public final class LaneEntryPlanner {
    public LaneEntryPlan plan(BuildLane lane, Vec3d playerPosition, double centerTolerance, double startTolerance) {
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(playerPosition, "playerPosition");

        double laneCenter = lane.fixedCoordinate() + 0.5;
        double lateralOffset = lane.axis() == LaneAxis.X
                ? playerPosition.z - laneCenter
                : playerPosition.x - laneCenter;
        double entryProgress = lane.axis() == LaneAxis.X
                ? lane.entryPoint().getX() + 0.5
                : lane.entryPoint().getZ() + 0.5;
        double playerProgress = lane.axis() == LaneAxis.X
                ? playerPosition.x
                : playerPosition.z;
        double progressOffsetFromEntry = playerProgress - entryProgress;

        boolean aligned = Math.abs(lateralOffset) <= centerTolerance;
        boolean startAligned = Math.abs(progressOffsetFromEntry) <= startTolerance;

        return new LaneEntryPlan(lateralOffset, centerTolerance, progressOffsetFromEntry, startTolerance, aligned, startAligned);
    }

    public record LaneEntryPlan(double lateralOffset,
                                double centerTolerance,
                                double progressOffsetFromEntry,
                                double startTolerance,
                                boolean aligned,
                                boolean startAligned) {
    }
}
