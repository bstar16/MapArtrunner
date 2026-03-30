package com.example.mapart.plan.sweep.air;

import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirPlacementEngineTest {

    @Test
    void rejectsWrongHeldItem() {
        assertEquals(AirPlacementOutcome.WRONG_ITEM, AirPlacementEngine.classifyHeldItemCompatibility(false));
        assertNull(AirPlacementEngine.classifyHeldItemCompatibility(true));
    }

    @Test
    void rejectsInvalidRequests() {
        AirPlacementRequest nullTarget = new AirPlacementRequest(null, null, 4.5, Hand.MAIN_HAND, true);
        AirPlacementRequest invalidRange = new AirPlacementRequest(new BlockPos(0, 64, 0), null, 0.0, Hand.MAIN_HAND, true);
        AirPlacementRequest missingItem = new AirPlacementRequest(new BlockPos(0, 64, 0), null, 4.5, Hand.MAIN_HAND, true);

        assertEquals(AirPlacementOutcome.INVALID_TARGET, AirPlacementEngine.validateRequest(nullTarget));
        assertEquals(AirPlacementOutcome.INVALID_TARGET, AirPlacementEngine.validateRequest(invalidRange));
        assertEquals(AirPlacementOutcome.INVALID_TARGET, AirPlacementEngine.validateRequest(missingItem));
    }

    @Test
    void classifiesOutOfRangeDeterministically() {
        Vec3d eyePos = new Vec3d(0.5, 64.0, 0.5);
        BlockPos target = new BlockPos(5, 64, 0);

        assertFalse(AirPlacementEngine.isWithinRange(eyePos, target, 4.0));
        assertTrue(AirPlacementEngine.isWithinRange(eyePos, target, 6.0));
    }

    @Test
    void mapsInteractionResultsDeterministically() {
        assertEquals(AirPlacementOutcome.SUCCESS, AirPlacementEngine.mapInteractionResult(ActionResult.SUCCESS));
        assertEquals(AirPlacementOutcome.SUCCESS, AirPlacementEngine.mapInteractionResult(ActionResult.CONSUME));
        assertEquals(AirPlacementOutcome.FAILED_INTERACTION, AirPlacementEngine.mapInteractionResult(ActionResult.PASS));
        assertEquals(AirPlacementOutcome.FAILED_INTERACTION, AirPlacementEngine.mapInteractionResult(ActionResult.FAIL));
    }

    @Test
    void requestAndOutcomeStructureSanity() {
        AirPlacementRequest request = new AirPlacementRequest(new BlockPos(1, 70, 2), null, 4.5, Hand.MAIN_HAND, true);

        assertEquals(new BlockPos(1, 70, 2), request.targetPos());
        assertEquals(Hand.MAIN_HAND, request.hand());
        assertTrue(request.swingOnSuccess());
        assertTrue(request.maxPlaceDistance() > 0);

        for (AirPlacementOutcome outcome : AirPlacementOutcome.values()) {
            assertNotNull(outcome.name());
        }

        assertNotNull(AirPlacementOutcome.valueOf("SUCCESS"));
        assertNotNull(AirPlacementOutcome.valueOf("OUT_OF_RANGE"));
        assertNotNull(AirPlacementOutcome.valueOf("WRONG_ITEM"));
        assertNotNull(AirPlacementOutcome.valueOf("BLOCKED"));
        assertNotNull(AirPlacementOutcome.valueOf("FAILED_INTERACTION"));
        assertNotNull(AirPlacementOutcome.valueOf("INVALID_TARGET"));
    }
}
