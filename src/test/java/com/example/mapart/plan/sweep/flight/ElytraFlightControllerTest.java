package com.example.mapart.plan.sweep.flight;

import com.example.mapart.plan.sweep.BuildLane;
import com.example.mapart.plan.sweep.LaneAxis;
import com.example.mapart.plan.sweep.LaneDirection;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElytraFlightControllerTest {
    @Test
    void progressesThroughTakeoffAlignmentFollowAndCompletion() {
        BuildLane lane = lane(0, LaneDirection.FORWARD, 3, 0, 8);
        ElytraFlightController controller = controller(lane, settings(0));

        controller.tick(ElytraFlightController.FlightTickInput.currentLaneOnly(new Vec3d(0.5, 70.0, 0.5), false));
        assertEquals(ElytraFlightState.TAKEOFF, controller.state());

        controller.tick(ElytraFlightController.FlightTickInput.currentLaneOnly(new Vec3d(0.5, 70.0, 0.5), true));
        assertEquals(ElytraFlightState.LANE_ENTRY_ALIGNMENT, controller.state());

        controller.tick(ElytraFlightController.FlightTickInput.currentLaneOnly(new Vec3d(1.5, 70.0, 3.5), true));
        assertEquals(ElytraFlightState.LANE_FOLLOWING, controller.state());

        controller.tick(ElytraFlightController.FlightTickInput.currentLaneOnly(new Vec3d(7.0, 70.0, 3.5), true));
        assertEquals(ElytraFlightState.APPROACH_ENDPOINT, controller.state());

        controller.tick(ElytraFlightController.FlightTickInput.currentLaneOnly(new Vec3d(8.5, 70.5, 3.5), true));
        assertEquals(ElytraFlightState.COMPLETE, controller.state());
    }

    @Test
    void takeoffTimeoutFailsWhenRecoveryDisabled() {
        BuildLane lane = lane(0, LaneDirection.FORWARD, 3, 0, 8);
        ElytraFlightController controller = controller(lane,
                new ElytraFlightControllerSettings(65.0, 80.0, 0.75, 6.0, 1, 10, 10, 0));

        controller.tick(ElytraFlightController.FlightTickInput.currentLaneOnly(new Vec3d(0.5, 70.0, 0.5), false));
        controller.tick(ElytraFlightController.FlightTickInput.currentLaneOnly(new Vec3d(0.5, 70.0, 0.5), false));
        controller.tick(ElytraFlightController.FlightTickInput.currentLaneOnly(new Vec3d(0.5, 70.0, 0.5), false));

        ElytraFlightResult result = controller.result();
        assertEquals(ElytraFlightState.FAILED, result.finalState());
        assertEquals(Optional.of(FlightFailureReason.RECOVERY_EXHAUSTED), result.failureReason());
    }

    @Test
    void laneEntryPlannerClassifiesAlignmentDeterministically() {
        BuildLane lane = lane(2, LaneDirection.FORWARD, 6, 0, 8);
        LaneEntryPlanner planner = new LaneEntryPlanner();

        LaneEntryPlanner.LaneEntryPlan aligned = planner.plan(lane, new Vec3d(2.0, 71.0, 6.55), 0.75);
        LaneEntryPlanner.LaneEntryPlan misaligned = planner.plan(lane, new Vec3d(2.0, 71.0, 7.40), 0.75);

        assertTrue(aligned.aligned());
        assertFalse(misaligned.aligned());
        assertTrue(Math.abs(misaligned.lateralOffset()) > misaligned.centerTolerance());
    }

    @Test
    void altitudeBandClassificationUsesConfiguredBand() {
        ElytraFlightController controller = controller(lane(0, LaneDirection.FORWARD, 4, 0, 8), settings(0));

        assertEquals(ElytraFlightController.AltitudeBand.BELOW, controller.classifyAltitude(64.9));
        assertEquals(ElytraFlightController.AltitudeBand.IN_BAND, controller.classifyAltitude(65.0));
        assertEquals(ElytraFlightController.AltitudeBand.IN_BAND, controller.classifyAltitude(80.0));
        assertEquals(ElytraFlightController.AltitudeBand.ABOVE, controller.classifyAltitude(80.1));
    }

    @Test
    void endpointApproachDetectionRespectsApproachDistance() {
        BuildLane lane = lane(0, LaneDirection.FORWARD, 3, 0, 8);
        ElytraFlightController controller = controller(lane, settings(0));

        assertTrue(controller.isEndpointApproaching(new Vec3d(7.0, 70.0, 3.5)));
        assertFalse(controller.isEndpointApproaching(new Vec3d(1.0, 70.0, 3.5)));
    }

    @Test
    void laneEntryAlignmentHoldsPositionInsteadOfDrivingForward() {
        BuildLane lane = lane(0, LaneDirection.FORWARD, 3, 0, 8);
        ElytraFlightController controller = controller(lane, settings(0));

        controller.tick(ElytraFlightController.FlightTickInput.currentLaneOnly(new Vec3d(0.5, 70.0, 0.5), false));
        controller.tick(ElytraFlightController.FlightTickInput.currentLaneOnly(new Vec3d(0.5, 70.0, 0.5), true));

        ElytraFlightController.FlightControlCommand command = controller.currentCommand();
        assertEquals(ElytraFlightState.LANE_ENTRY_ALIGNMENT, controller.state());
        assertFalse(command.forwardPressed());
        assertFalse(command.backPressed());
    }

    @Test
    void failureResultPreservesTypedFailureMetadata() {
        BuildLane lane = lane(0, LaneDirection.FORWARD, 3, 0, 8);
        ElytraFlightController controller = controller(lane,
                new ElytraFlightControllerSettings(65.0, 80.0, 0.75, 6.0, 3, 1, 10, 0));

        controller.tick(ElytraFlightController.FlightTickInput.currentLaneOnly(new Vec3d(0.5, 70.0, 0.5), true));
        controller.tick(ElytraFlightController.FlightTickInput.currentLaneOnly(new Vec3d(1.0, 70.0, 10.0), true));
        controller.tick(ElytraFlightController.FlightTickInput.currentLaneOnly(new Vec3d(1.0, 70.0, 10.0), true));

        ElytraFlightResult result = controller.result();
        assertEquals(ElytraFlightState.FAILED, result.finalState());
        assertEquals(Optional.of(FlightFailureReason.RECOVERY_EXHAUSTED), result.failureReason());
        assertEquals(1, result.recoveryAttempts());
    }

    @Test
    void turnPlannerSanityChecksSerpentineDirectionFlip() {
        BuildLane current = lane(0, LaneDirection.FORWARD, 3, 0, 8);
        BuildLane nextValid = lane(1, LaneDirection.REVERSE, 4, 0, 8);
        BuildLane nextInvalid = lane(1, LaneDirection.FORWARD, 4, 0, 8);

        TurnPlanner planner = new TurnPlanner();

        TurnPlanner.TurnPlan validPlan = planner.planSerpentineTurn(current, nextValid, new Vec3d(8.5, 70.0, 3.5));
        TurnPlanner.TurnPlan invalidPlan = planner.planSerpentineTurn(current, nextInvalid, new Vec3d(8.5, 70.0, 3.5));

        assertTrue(validPlan.available());
        assertFalse(invalidPlan.available());
    }

    private static ElytraFlightController controller(BuildLane lane, ElytraFlightControllerSettings settings) {
        return new ElytraFlightController(lane, settings, new LaneEntryPlanner(), new TurnPlanner(), new FlightRecoveryHandler());
    }

    private static ElytraFlightControllerSettings settings(int maxRecoveryAttempts) {
        return new ElytraFlightControllerSettings(65.0, 80.0, 0.75, 6.0, 10, 10, 10, maxRecoveryAttempts);
    }

    private static BuildLane lane(int laneIndex, LaneDirection direction, int fixedCoordinate, int minProgress, int maxProgress) {
        BlockPos entry = direction == LaneDirection.FORWARD
                ? new BlockPos(minProgress, 70, fixedCoordinate)
                : new BlockPos(maxProgress, 70, fixedCoordinate);
        BlockPos end = direction == LaneDirection.FORWARD
                ? new BlockPos(maxProgress, 70, fixedCoordinate)
                : new BlockPos(minProgress, 70, fixedCoordinate);
        return new BuildLane(laneIndex, LaneAxis.X, direction, fixedCoordinate, minProgress, maxProgress, 1, 1, entry, end, 1.2);
    }
}
