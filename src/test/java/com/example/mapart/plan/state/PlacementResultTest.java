package com.example.mapart.plan.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlacementResultTest {

    @Test
    void placedStatusStillRepresentsImmediatelyVisibleAcceptedPlacement() {
        PlacementResult result = PlacementResult.placed("visible");

        assertEquals(PlacementResult.Status.PLACED, result.status());
    }

    @Test
    void acceptedButNotYetVisiblePlacementHasPendingVerificationStatus() {
        PlacementResult result = PlacementResult.acceptedPendingVerification("accepted but waiting");

        assertEquals(PlacementResult.Status.ACCEPTED_PENDING_VERIFICATION, result.status());
    }
}
