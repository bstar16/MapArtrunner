package com.example.mapart.plan.placement;

import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerPlacementHitResultFactoryTest {

    // Tolerance for floating-point face-position checks
    private static final double EPSILON = 1e-9;

    /**
     * Verifies that the hit vector lies exactly on the clicked face of the support block,
     * i.e. its coordinate along the side axis equals the face coordinate of supportPos.
     */
    private static void assertHitVecOnFace(BlockPos supportPos, Direction side, Vec3d hitVec) {
        // The face coordinate: center of support block shifted by +0.5 in the side direction
        double expectedFaceCoord = switch (side) {
            case EAST  -> supportPos.getX() + 1.0; // east face at x+1
            case WEST  -> supportPos.getX() + 0.0; // west face at x+0
            case UP    -> supportPos.getY() + 1.0;
            case DOWN  -> supportPos.getY() + 0.0;
            case SOUTH -> supportPos.getZ() + 1.0;
            case NORTH -> supportPos.getZ() + 0.0;
        };
        double actualAxisCoord = switch (side) {
            case EAST, WEST  -> hitVec.x;
            case UP,   DOWN  -> hitVec.y;
            case SOUTH, NORTH -> hitVec.z;
        };
        assertEquals(expectedFaceCoord, actualAxisCoord, EPSILON,
                "hitVec must lie on the " + side + " face of supportPos " + supportPos.toShortString());
    }

    private static void assertHitVecNotBeyondTarget(BlockPos targetPos, Direction side, Vec3d hitVec) {
        // The "bad" value (old bug): center(targetPos) + side*0.5
        Vec3d badVec = Vec3d.ofCenter(targetPos).add(
                side.getOffsetX() * 0.5,
                side.getOffsetY() * 0.5,
                side.getOffsetZ() * 0.5
        );
        // Check only the axis the direction affects — the other axes are identical between old/new.
        switch (side) {
            case EAST, WEST   -> assertNotEquals(badVec.x, hitVec.x, EPSILON,
                    "hitVec.x must NOT be the old buggy double-offset value");
            case UP,   DOWN   -> assertNotEquals(badVec.y, hitVec.y, EPSILON,
                    "hitVec.y must NOT be the old buggy double-offset value");
            case SOUTH, NORTH -> assertNotEquals(badVec.z, hitVec.z, EPSILON,
                    "hitVec.z must NOT be the old buggy double-offset value");
        }
    }

    // ── EAST face ──────────────────────────────────────────────────────────────
    // Server log example: hit block=-193,-59,65  bad location=-191.0,-58.5,65.5
    // Correct:            supportPos=-193,-59,65  side=EAST  hitVec.x=-192.0

    @Test
    void eastFace_hitVecOnFace() {
        BlockPos support = new BlockPos(-193, -59, 65);
        BlockPos target  = new BlockPos(-192, -59, 65);
        BlockHitResult hit = ServerPlacementHitResultFactory.build(support, Direction.EAST, target);

        assertEquals(support, hit.getBlockPos(), "blockPos must be supportPos");
        assertEquals(Direction.EAST, hit.getSide());
        assertEquals(-192.0, hit.getPos().x, EPSILON, "hitVec.x must be -192.0 (east face of -193)");
        assertHitVecOnFace(support, Direction.EAST, hit.getPos());
        assertHitVecNotBeyondTarget(target, Direction.EAST, hit.getPos());
    }

    @Test
    void eastFace_supportOffsetEqualsTarget() {
        BlockPos support = new BlockPos(-193, -59, 65);
        BlockPos target  = new BlockPos(-192, -59, 65);
        assertEquals(target, support.offset(Direction.EAST),
                "supportPos.offset(EAST) must equal targetPos");
    }

    // ── WEST face ──────────────────────────────────────────────────────────────

    @Test
    void westFace_hitVecOnFace() {
        BlockPos support = new BlockPos(-191, -59, 65);
        BlockPos target  = new BlockPos(-192, -59, 65);
        BlockHitResult hit = ServerPlacementHitResultFactory.build(support, Direction.WEST, target);

        assertEquals(support, hit.getBlockPos());
        assertEquals(Direction.WEST, hit.getSide());
        assertEquals(-191.0, hit.getPos().x, EPSILON, "hitVec.x must be -191.0 (west face of -191)");
        assertHitVecOnFace(support, Direction.WEST, hit.getPos());
        assertHitVecNotBeyondTarget(target, Direction.WEST, hit.getPos());
    }

    // ── SOUTH face ─────────────────────────────────────────────────────────────

    @Test
    void southFace_hitVecOnFace() {
        BlockPos support = new BlockPos(10, -59, 20);
        BlockPos target  = new BlockPos(10, -59, 21);
        BlockHitResult hit = ServerPlacementHitResultFactory.build(support, Direction.SOUTH, target);

        assertEquals(support, hit.getBlockPos());
        assertEquals(Direction.SOUTH, hit.getSide());
        assertEquals(21.0, hit.getPos().z, EPSILON, "hitVec.z must be 21.0 (south face of z=20)");
        assertHitVecOnFace(support, Direction.SOUTH, hit.getPos());
        assertHitVecNotBeyondTarget(target, Direction.SOUTH, hit.getPos());
    }

    // ── NORTH face ─────────────────────────────────────────────────────────────

    @Test
    void northFace_hitVecOnFace() {
        BlockPos support = new BlockPos(10, -59, 22);
        BlockPos target  = new BlockPos(10, -59, 21);
        BlockHitResult hit = ServerPlacementHitResultFactory.build(support, Direction.NORTH, target);

        assertEquals(support, hit.getBlockPos());
        assertEquals(Direction.NORTH, hit.getSide());
        assertEquals(22.0, hit.getPos().z, EPSILON, "hitVec.z must be 22.0 (north face of z=22)");
        assertHitVecOnFace(support, Direction.NORTH, hit.getPos());
        assertHitVecNotBeyondTarget(target, Direction.NORTH, hit.getPos());
    }

    // ── UP face ────────────────────────────────────────────────────────────────

    @Test
    void upFace_hitVecOnFace() {
        BlockPos support = new BlockPos(5, -60, 5);
        BlockPos target  = new BlockPos(5, -59, 5);
        BlockHitResult hit = ServerPlacementHitResultFactory.build(support, Direction.UP, target);

        assertEquals(support, hit.getBlockPos());
        assertEquals(Direction.UP, hit.getSide());
        assertEquals(-59.0, hit.getPos().y, EPSILON, "hitVec.y must be -59.0 (top face of y=-60)");
        assertHitVecOnFace(support, Direction.UP, hit.getPos());
        assertHitVecNotBeyondTarget(target, Direction.UP, hit.getPos());
    }

    // ── DOWN face ──────────────────────────────────────────────────────────────

    @Test
    void downFace_hitVecOnFace() {
        BlockPos support = new BlockPos(5, -58, 5);
        BlockPos target  = new BlockPos(5, -59, 5);
        BlockHitResult hit = ServerPlacementHitResultFactory.build(support, Direction.DOWN, target);

        assertEquals(support, hit.getBlockPos());
        assertEquals(Direction.DOWN, hit.getSide());
        assertEquals(-58.0, hit.getPos().y, EPSILON, "hitVec.y must be -58.0 (bottom face of y=-58)");
        assertHitVecOnFace(support, Direction.DOWN, hit.getPos());
        assertHitVecNotBeyondTarget(target, Direction.DOWN, hit.getPos());
    }

    // ── Precondition guard ─────────────────────────────────────────────────────

    @Test
    void throwsWhenSupportOffsetDoesNotEqualTarget() {
        BlockPos support = new BlockPos(0, 64, 0);
        BlockPos wrongTarget = new BlockPos(5, 64, 0); // not adjacent
        assertThrows(IllegalArgumentException.class,
                () -> ServerPlacementHitResultFactory.build(support, Direction.EAST, wrongTarget));
    }

    // ── AirPlacementEngine.buildSyntheticHit correctness (regression guard) ────

    @Test
    void airPlacementEngine_buildSyntheticHit_hitVecOnSupportFace() {
        // Simulate the corrected logic: center(neighborPos) + interactionSide*0.5
        // EAST case: target=(-192,-59,65), neighborPos=(-193,-59,65), interactionSide=EAST
        BlockPos targetPos   = new BlockPos(-192, -59, 65);
        BlockPos neighborPos = new BlockPos(-193, -59, 65);
        Direction interactionSide = Direction.EAST;

        Vec3d hitPos = Vec3d.ofCenter(neighborPos).add(
                interactionSide.getOffsetX() * 0.5,
                interactionSide.getOffsetY() * 0.5,
                interactionSide.getOffsetZ() * 0.5
        );

        // Must be -192.0 (east face of block at x=-193)
        assertEquals(-192.0, hitPos.x, EPSILON, "hitVec.x must be -192.0");
        // Y and Z unchanged from center of neighborPos
        assertEquals(-58.5, hitPos.y, EPSILON);
        assertEquals(65.5,  hitPos.z, EPSILON);

        // Verify it is NOT the old buggy value (-191.0, -58.5, 65.5)
        assertNotEquals(-191.0, hitPos.x, EPSILON, "must not be the old buggy x=-191.0");

        // Verify neighborPos.offset(interactionSide) == targetPos
        assertEquals(targetPos, neighborPos.offset(interactionSide),
                "neighborPos.offset(EAST) must equal targetPos");
    }

    @Test
    void placementExecutor_resolvePlacementHit_hitVecOnSupportFace() {
        // WEST case: target=(-192,-59,65), support=(-191,-59,65), interactionSide=WEST
        BlockPos targetPos   = new BlockPos(-192, -59, 65);
        BlockPos neighborPos = new BlockPos(-191, -59, 65);
        Direction interactionSide = Direction.WEST;

        Vec3d hitPos = Vec3d.ofCenter(neighborPos).add(
                interactionSide.getOffsetX() * 0.5,
                interactionSide.getOffsetY() * 0.5,
                interactionSide.getOffsetZ() * 0.5
        );

        // West face of block at x=-191 is at x=-191
        assertEquals(-191.0, hitPos.x, EPSILON, "hitVec.x must be -191.0");
        assertNotEquals(-193.0, hitPos.x, EPSILON, "must not be the old buggy x=-193.0");

        assertEquals(targetPos, neighborPos.offset(interactionSide),
                "neighborPos.offset(WEST) must equal targetPos");
    }
}
