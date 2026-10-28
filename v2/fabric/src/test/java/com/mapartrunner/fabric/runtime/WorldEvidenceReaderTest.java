package com.mapartrunner.fabric.runtime;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.core.movement.MovementPolicy.SupportSample;
import com.mapartrunner.core.placement.PlacementLedger;
import com.mapartrunner.core.placement.PlacementObservation;
import com.mapartrunner.fabric.prediction.PendingPredictionView;
import net.minecraft.block.Blocks;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.StairShape;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mapartrunner.core.placement.PlacementObservation.Evidence.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldEvidenceReaderTest {
    private final UUID runId = UUID.randomUUID();
    private static final BlockSpec STONE = new BlockSpec("minecraft:stone", Map.of());
    private static final GridPos ZERO = new GridPos(0, 0, 0);

    @BeforeAll
    static void bootstrapMappedRegistries() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @Test
    void convertsActualIdAndEverySerializedProperty() {
        assertEquals(STONE, WorldEvidenceReader.blockSpec(Blocks.STONE.getDefaultState()));
        assertEquals(new BlockSpec("minecraft:air", Map.of()),
                WorldEvidenceReader.blockSpec(Blocks.AIR.getDefaultState()));
        var stairs = Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.EAST)
                .with(StairsBlock.HALF, BlockHalf.TOP).with(StairsBlock.SHAPE, StairShape.INNER_LEFT)
                .with(StairsBlock.WATERLOGGED, true);
        var expected = new BlockSpec("minecraft:oak_stairs", Map.of(
                "facing", "east", "half", "top", "shape", "inner_left", "waterlogged", "true"));
        assertEquals(expected, WorldEvidenceReader.blockSpec(stairs));
        assertNotEquals(expected, WorldEvidenceReader.blockSpec(stairs.with(StairsBlock.WATERLOGGED, false)));
        assertThrows(UnsupportedOperationException.class,
                () -> WorldEvidenceReader.blockSpec(stairs).properties().put("facing", "west"));
    }

    @Test
    void visitsAllSparseTargetsOnceWithCheckedNegativeOriginAndSharesCapturedFacts() {
        var targets = new ArrayList<>(List.of(new Target(91, ZERO, STONE),
                new Target(7, new GridPos(17, 0, 1), STONE)));
        var plan = new BuildPlan("positions", new GridSize(18, 1, 2), targets);
        var visited = new ArrayList<BlockPos>();
        var observed = new SupportSample(OBSERVED, Optional.of(STONE));
        var unknown = new SupportSample(UNKNOWN, Optional.empty());
        var captured = WorldEvidenceReader.captureTargets(runId, 12, plan, new GridPos(-17, 64, -1), pos -> {
            visited.add(pos);
            return pos.getX() < 0 ? observed : unknown;
        });
        targets.clear();
        var placements = WorldEvidenceReader.placementObservations(captured);
        assertEquals(List.of(new BlockPos(-17, 64, -1), new BlockPos(0, 64, 0)), visited);
        assertEquals(List.of(91, 7), placements.stream().map(PlacementObservation::targetId).toList());
        assertSame(observed, captured.supportObservation().cells().get(ZERO));
        assertSame(observed.state().orElseThrow(), placements.getFirst().observedState().orElseThrow());
        for (int i = 0; i < placements.size(); i++) {
            var placement = placements.get(i);
            var support = captured.supportObservation().cells().get(plan.targets().get(i).relativePos());
            assertEquals(runId, placement.runId());
            assertEquals(12, placement.tick());
            assertTrue(placement.actionId().isEmpty());
            assertEquals(support.evidence(), placement.evidence());
            assertEquals(support.state(), placement.observedState());
        }
        assertEquals(placements, WorldEvidenceReader.placementObservations(captured));
        assertEquals(2, visited.size());
        assertThrows(UnsupportedOperationException.class, () -> placements.clear());
        assertThrows(UnsupportedOperationException.class, () -> captured.supportObservation().cells().clear());
    }

    @Test
    void missingStateOrQueryIsUnknownWithoutCallingThePredicate() {
        var calls = new AtomicInteger();
        PendingPredictionView view = pos -> { calls.incrementAndGet(); return false; };
        assertEquals(new SupportSample(UNKNOWN, Optional.empty()), WorldEvidenceReader.sample(null, view, BlockPos.ORIGIN));
        assertEquals(new SupportSample(UNKNOWN, Optional.empty()),
                WorldEvidenceReader.sample(Blocks.STONE.getDefaultState(), null, BlockPos.ORIGIN));
        assertEquals(0, calls.get());
    }

    @Test
    void loadedAirAndMismatchesAreObservedAndQueryErrorsCannotBecomeObserved() {
        assertEquals(new BlockSpec("minecraft:air", Map.of()),
                WorldEvidenceReader.sample(Blocks.AIR.getDefaultState(), pos -> false, BlockPos.ORIGIN)
                        .state().orElseThrow());
        var wrong = WorldEvidenceReader.sample(Blocks.GOLD_BLOCK.getDefaultState(), pos -> false, BlockPos.ORIGIN);
        assertEquals(OBSERVED, wrong.evidence());
        assertNotEquals(STONE, wrong.state().orElseThrow());
        var failure = new IllegalStateException("query failed");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> WorldEvidenceReader.sample(Blocks.STONE.getDefaultState(), pos -> { throw failure; }, BlockPos.ORIGIN)));
    }

    @Test
    void repeatedPositionStaysPredictedUntilTheActualSuppliedPredicateClears() {
        var pending = new AtomicBoolean(true);
        var queries = new AtomicInteger();
        PendingPredictionView view = pos -> { assertEquals(new BlockPos(-1, 65, 0), pos); queries.incrementAndGet(); return pending.get(); };
        var state = Blocks.STONE.getDefaultState();
        for (int read = 0; read < 3; read++) {
            var sample = WorldEvidenceReader.sample(state, view, new BlockPos(-1, 65, 0));
            assertEquals(PREDICTED, sample.evidence());
            assertEquals(STONE, sample.state().orElseThrow());
        }
        pending.set(false);
        assertEquals(OBSERVED, WorldEvidenceReader.sample(state, view, new BlockPos(-1, 65, 0)).evidence());
        assertEquals(4, queries.get());
    }

    @ParameterizedTest
    @EnumSource(value = PlacementObservation.Evidence.class, names = {"UNKNOWN", "PREDICTED"})
    void actionlessFreshEvidenceConfirmsReopensAndRestoresWithoutInventingAttempts(PlacementObservation.Evidence uncertain) {
        var plan = plan();
        var ledger = new PlacementLedger(plan);
        ledger.bindRun(runId);
        var first = observation(plan, 4, new SupportSample(OBSERVED, Optional.of(STONE)));
        assertTrue(ledger.observe(first));
        assertEquals(PlacementLedger.Outcome.ALL_CONFIRMED, ledger.outcome());
        assertFalse(ledger.observe(first));
        assertFalse(ledger.observe(observation(plan, 3, new SupportSample(UNKNOWN, Optional.empty()))));
        var sample = new SupportSample(uncertain, uncertain == UNKNOWN ? Optional.empty() : Optional.of(STONE));
        assertTrue(ledger.observe(observation(plan, 5, sample)));
        assertFalse(ledger.unfinishedTargets().isEmpty());
        assertTrue(ledger.pendingTargetIds().isEmpty());
        assertTrue(ledger.latestAttempt(9).isEmpty());
        assertTrue(ledger.observe(observation(plan, 6, new SupportSample(OBSERVED, Optional.of(STONE)))));
        assertEquals(PlacementLedger.Outcome.ALL_CONFIRMED, ledger.outcome());
    }

    @Test
    void emptyPlanHasNoFabricatedSamplesAndInvalidCaptureInputsFailExplicitly() {
        var empty = new BuildPlan("empty", new GridSize(1, 1, 1), List.of());
        var captured = WorldEvidenceReader.captureTargets(runId, 0, empty, ZERO, pos -> fail("unvisited target"));
        assertTrue(WorldEvidenceReader.placementObservations(captured).isEmpty());
        assertTrue(captured.supportObservation().cells().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> WorldEvidenceReader.captureTargets(runId, -1, plan(), ZERO,
                pos -> fail("negative tick")));
        assertThrows(ArithmeticException.class, () -> WorldEvidenceReader.captureTargets(runId, 0,
                new BuildPlan("overflow", new GridSize(2, 1, 1), List.of(new Target(1, new GridPos(1, 0, 0), STONE))),
                new GridPos(Integer.MAX_VALUE, 0, 0), pos -> fail("wrapped origin")));
        assertThrows(NullPointerException.class, () -> WorldEvidenceReader.captureTargets(runId, 0, plan(), ZERO, pos -> null));
    }

    private PlacementObservation observation(BuildPlan plan, long tick, SupportSample sample) {
        return WorldEvidenceReader.placementObservations(WorldEvidenceReader.captureTargets(runId, tick, plan, ZERO,
                pos -> sample)).getFirst();
    }

    private static BuildPlan plan() {
        return new BuildPlan("stone", new GridSize(1, 1, 1), List.of(new Target(9, ZERO, STONE)));
    }
}
