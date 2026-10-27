package com.mapartrunner.core.placement;

import com.mapartrunner.core.model.BuildPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import static com.mapartrunner.core.model.BuildPlan.*;
import static com.mapartrunner.core.placement.PlacementLedger.Outcome.*;
import static com.mapartrunner.core.placement.PlacementObservation.Evidence.*;
import static org.junit.jupiter.api.Assertions.*;

class PlacementLedgerTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final BlockSpec LOG = new BlockSpec("minecraft:oak_log", Map.of("axis", "y"));
    private static final BlockSpec STONE = new BlockSpec("minecraft:stone", Map.of());
    private static final BlockSpec AIR = new BlockSpec("minecraft:air", Map.of());
    private static final Target SEVEN = new Target(7, new GridPos(0, 0, 0), LOG);
    private static final Target TWO = new Target(2, new GridPos(1, 0, 0), STONE);
    private static final BuildPlan PLAN = new BuildPlan("plan-hash", new GridSize(2, 1, 1), List.of(SEVEN, TWO));

    @Test
    void expectedTargetsSurviveFailedAndMissedAttempts() {
        var ledger = boundLedger(A);
        assertTrue(ledger.recordAttempt(A, 10, 7, 5));
        assertTrue(ledger.observe(observed(A, 7, 10, 6, AIR)));
        assertTrue(ledger.recordAttempt(A, 11, 7, 7));
        assertEquals(List.of(SEVEN, TWO), ledger.unfinishedTargets());
        assertEquals(List.of(SEVEN, TWO), PLAN.targets());
        assertEquals(INCOMPLETE, ledger.outcome());
    }

    @Test
    void unknownAndUnloadedNeverConfirm() {
        var ledger = boundLedger(A);
        assertTrue(ledger.observe(unknown(A, 7, OptionalLong.empty(), 0)));
        assertTrue(ledger.recordAttempt(A, 10, 7, 1));
        assertTrue(ledger.observe(unknown(A, 7, OptionalLong.of(10), 2)));
        assertTrue(ledger.observe(sample(A, 2, 0, STONE)));
        assertEquals(List.of(SEVEN), ledger.unfinishedTargets());
        assertEquals(INCOMPLETE, ledger.outcome());
    }

    @Test
    void predictedMatchNeverConfirms() {
        var ledger = boundLedger(A);
        assertTrue(ledger.recordAttempt(A, 10, 7, 5));
        assertTrue(ledger.observe(new PlacementObservation(A, 7, OptionalLong.of(10), 6, PREDICTED, Optional.of(LOG))));
        assertEquals(List.of(SEVEN, TWO), ledger.unfinishedTargets());
        assertEquals(INCOMPLETE, ledger.outcome());
    }

    @Test
    void staleRunCannotMutateBoundLedger() {
        var ledger = boundLedger(B);
        assertFalse(ledger.recordAttempt(A, 99, 7, 100));
        assertFalse(ledger.observe(sample(A, 7, 0, AIR)));
        assertTrue(ledger.observe(sample(B, 7, 0, LOG)));
        assertFalse(ledger.observe(sample(A, 7, 1, AIR)));
        assertTrue(ledger.observe(sample(B, 7, 1, LOG)));
        assertTrue(ledger.recordAttempt(B, 0, 2, 0));
        assertEquals(List.of(TWO), ledger.unfinishedTargets());
    }

    @Test
    void staleActionCannotConfirmOrReopen() {
        var ledger = boundLedger(A);
        assertFalse(ledger.observe(observed(A, 2, 10, 100, STONE)));
        assertTrue(ledger.observe(sample(A, 2, 0, STONE)));
        assertTrue(ledger.recordAttempt(A, 10, 7, 5));
        assertTrue(ledger.recordAttempt(A, 11, 7, 6));
        assertFalse(ledger.observe(observed(A, 7, 10, 7, LOG)));
        assertTrue(ledger.observe(observed(A, 7, 11, 7, LOG)));
        assertFalse(ledger.observe(observed(A, 7, 10, 8, AIR)));
        assertEquals(ALL_CONFIRMED, ledger.outcome());
        assertTrue(ledger.observe(observed(A, 7, 11, 8, LOG)));
        assertEquals(ALL_CONFIRMED, ledger.outcome());
    }

    @Test
    void delayedConfirmationSurvivesWithoutInventedTimeout() {
        var ledger = boundLedger(A);
        assertTrue(ledger.recordAttempt(A, 10, 7, 5));
        assertTrue(ledger.observe(new PlacementObservation(A, 7, OptionalLong.of(10), 6, PREDICTED, Optional.of(LOG))));
        assertTrue(ledger.observe(unknown(A, 7, OptionalLong.of(10), 7)));
        assertEquals(List.of(SEVEN, TWO), ledger.unfinishedTargets());
        assertTrue(ledger.observe(observed(A, 7, 10, 100, LOG)));
        assertEquals(List.of(TWO), ledger.unfinishedTargets());
    }

    @Test
    void allTargetsRequiredForAllConfirmed() {
        var ledger = boundLedger(A);
        assertTrue(ledger.observe(sample(A, 7, 0, LOG)));
        assertEquals(List.of(TWO), ledger.unfinishedTargets());
        assertEquals(INCOMPLETE, ledger.outcome());
        assertTrue(ledger.recordAttempt(A, 10, 2, 1));
        assertEquals(INCOMPLETE, ledger.outcome());
        assertTrue(ledger.observe(observed(A, 2, 10, 2, STONE)));
        assertEquals(List.of(), ledger.unfinishedTargets());
        assertEquals(ALL_CONFIRMED, ledger.outcome());
    }

    @Test
    void existingWorldMatchNeedsNoAction() {
        var ledger = boundLedger(A);
        assertTrue(ledger.observe(sample(A, 7, 0, LOG)));
        assertEquals(List.of(TWO), ledger.unfinishedTargets());
        assertTrue(ledger.recordAttempt(A, 0, 7, 1));
        assertEquals(List.of(SEVEN, TWO), ledger.unfinishedTargets());
        assertTrue(ledger.observe(sample(A, 7, 2, LOG)));
        assertEquals(List.of(TWO), ledger.unfinishedTargets());
    }

    @ParameterizedTest
    @EnumSource(Mismatch.class)
    void knownMismatchReopensConfirmedTarget(Mismatch mismatch) {
        var ledger = boundLedger(A);
        assertTrue(ledger.observe(sample(A, 7, 0, LOG)));
        assertTrue(ledger.observe(sample(A, 2, 0, STONE)));
        assertEquals(ALL_CONFIRMED, ledger.outcome());
        assertTrue(ledger.observe(sample(A, 7, 1, mismatch.block)));
        assertEquals(List.of(SEVEN), ledger.unfinishedTargets());
        assertEquals(INCOMPLETE, ledger.outcome());
        assertEquals(LOG, PLAN.targets().getFirst().block());
        assertTrue(ledger.observe(sample(A, 7, 2, LOG)));
        assertEquals(ALL_CONFIRMED, ledger.outcome());
    }

    @ParameterizedTest
    @EnumSource(value = PlacementObservation.Evidence.class, names = {"UNKNOWN", "PREDICTED"})
    void freshUnknownOrPredictionReopensConfirmation(PlacementObservation.Evidence evidence) {
        var ledger = boundLedger(A);
        assertTrue(ledger.observe(sample(A, 7, 0, LOG)));
        assertTrue(ledger.observe(sample(A, 2, 0, STONE)));
        var state = evidence == UNKNOWN ? Optional.<BlockSpec>empty() : Optional.of(LOG);
        assertTrue(ledger.observe(new PlacementObservation(A, 7, OptionalLong.empty(), 1, evidence, state)));
        assertEquals(List.of(SEVEN), ledger.unfinishedTargets());
        assertEquals(INCOMPLETE, ledger.outcome());
        assertTrue(ledger.observe(sample(A, 7, 2, LOG)));
        assertEquals(ALL_CONFIRMED, ledger.outcome());
    }

    @Test
    void sampleTicksOrderEvidencePerTarget() {
        var ledger = boundLedger(A);
        assertTrue(ledger.observe(sample(A, 7, 10, AIR)));
        assertFalse(ledger.observe(sample(A, 7, 9, LOG)));
        assertFalse(ledger.observe(sample(A, 7, 10, LOG)));
        assertEquals(List.of(SEVEN, TWO), ledger.unfinishedTargets());
        assertTrue(ledger.observe(sample(A, 7, 11, LOG)));
        assertFalse(ledger.observe(sample(A, 7, 10, AIR)));
        assertFalse(ledger.observe(sample(A, 7, 11, AIR)));
        assertTrue(ledger.observe(sample(A, 2, 0, STONE)));
        assertEquals(ALL_CONFIRMED, ledger.outcome());
        assertTrue(ledger.recordAttempt(A, 0, 7, 11));
        assertFalse(ledger.observe(observed(A, 7, 0, 11, LOG)));
        assertTrue(ledger.observe(observed(A, 7, 0, 12, LOG)));
        assertEquals(ALL_CONFIRMED, ledger.outcome());
        assertTrue(ledger.observe(sample(A, 7, 13, AIR)));
        assertEquals(List.of(SEVEN), ledger.unfinishedTargets());
    }

    @Test
    void repeatedAttemptIsIdempotentAndCannotRewriteItsTick() {
        var ledger = boundLedger(A);
        assertTrue(ledger.recordAttempt(A, 10, 7, 5));
        assertTrue(ledger.observe(observed(A, 7, 10, 6, LOG)));
        assertTrue(ledger.recordAttempt(A, 10, 7, 5));
        assertEquals(List.of(TWO), ledger.unfinishedTargets());
        assertFalse(ledger.recordAttempt(A, 10, 7, 7));
        assertFalse(ledger.recordAttempt(A, 9, 7, 7));
        assertFalse(ledger.recordAttempt(A, 11, 7, 4));
        assertFalse(ledger.recordAttempt(A, 11, 7, 5));
        assertEquals(List.of(TWO), ledger.unfinishedTargets());
        assertFalse(ledger.observe(observed(A, 7, 10, 6, AIR)));
        assertTrue(ledger.observe(observed(A, 7, 10, 7, LOG)));
        assertTrue(ledger.recordAttempt(A, 11, 7, 7));
        assertEquals(List.of(SEVEN, TWO), ledger.unfinishedTargets());
        assertTrue(ledger.recordAttempt(A, 11, 2, 0));
        assertTrue(ledger.observe(observed(A, 2, 11, 1, STONE)));
        assertEquals(List.of(SEVEN), ledger.unfinishedTargets());
    }

    @Test
    void requiresExplicitRunBindingBeforeMutation() {
        var ledger = new PlacementLedger(PLAN);
        assertEquals(INCOMPLETE, ledger.outcome());
        assertEquals(List.of(SEVEN, TWO), ledger.unfinishedTargets());
        assertThrows(IllegalStateException.class, () -> ledger.recordAttempt(B, 0, 7, 0));
        assertThrows(IllegalStateException.class, () -> ledger.observe(sample(B, 7, 0, LOG)));
        ledger.bindRun(A);
        ledger.bindRun(A);
        assertThrows(IllegalStateException.class, () -> ledger.bindRun(B));
        assertFalse(ledger.observe(sample(B, 7, 0, LOG)));
        assertTrue(ledger.observe(sample(A, 7, 0, LOG)));
        assertEquals(List.of(TWO), ledger.unfinishedTargets());
    }

    @Test
    void unfinishedSnapshotsKeepPlanOrderAndCannotBeMutated() {
        var ledger = boundLedger(A);
        var before = ledger.unfinishedTargets();
        assertThrows(UnsupportedOperationException.class, before::clear);
        assertTrue(ledger.observe(sample(A, 7, 0, LOG)));
        var after = ledger.unfinishedTargets();
        assertEquals(List.of(SEVEN, TWO), before);
        assertEquals(List.of(TWO), after);
        assertNotSame(before, after);
        assertSame(TWO, after.getFirst());
        assertTrue(ledger.observe(sample(A, 7, 1, AIR)));
        assertEquals(List.of(SEVEN, TWO), ledger.unfinishedTargets());
        assertEquals(List.of(TWO), after);
    }

    @Test
    void emptyPlanReportsNothingToBuild() {
        var ledger = new PlacementLedger(new BuildPlan("empty", new GridSize(1, 1, 1), List.of()));
        assertEquals(NOTHING_TO_BUILD, ledger.outcome());
        assertEquals(List.of(), ledger.unfinishedTargets());
        ledger.bindRun(A);
        assertFalse(ledger.recordAttempt(A, 0, 7, 0));
        assertFalse(ledger.observe(sample(A, 7, 0, LOG)));
        assertEquals(NOTHING_TO_BUILD, ledger.outcome());
        assertEquals(List.of(), ledger.unfinishedTargets());
    }

    @Test
    void invalidInputsAreRejectedWithoutMutation() {
        assertThrows(NullPointerException.class, () -> new PlacementLedger(null));
        var ledger = new PlacementLedger(PLAN);
        assertThrows(NullPointerException.class, () -> ledger.bindRun(null));
        assertThrows(NullPointerException.class, () -> ledger.observe(null));
        assertThrows(NullPointerException.class, () -> ledger.recordAttempt(null, 0, 7, 0));
        assertThrows(IllegalArgumentException.class, () -> ledger.recordAttempt(A, -1, 7, 0));
        assertThrows(IllegalArgumentException.class, () -> ledger.recordAttempt(A, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> ledger.recordAttempt(A, 0, 7, -1));
        assertThrows(NullPointerException.class, () -> new PlacementObservation(null, 7, OptionalLong.empty(), 0, OBSERVED, Optional.of(LOG)));
        assertThrows(NullPointerException.class, () -> new PlacementObservation(A, 7, null, 0, OBSERVED, Optional.of(LOG)));
        assertThrows(NullPointerException.class, () -> new PlacementObservation(A, 7, OptionalLong.empty(), 0, null, Optional.of(LOG)));
        assertThrows(NullPointerException.class, () -> new PlacementObservation(A, 7, OptionalLong.empty(), 0, OBSERVED, null));
        assertThrows(IllegalArgumentException.class, () -> sample(A, -1, 0, LOG));
        assertThrows(IllegalArgumentException.class, () -> sample(A, 7, -1, LOG));
        assertThrows(IllegalArgumentException.class, () -> observed(A, 7, -1, 0, LOG));
        assertThrows(IllegalArgumentException.class, () -> new PlacementObservation(A, 7, OptionalLong.empty(), 0, UNKNOWN, Optional.of(LOG)));
        for (var evidence : List.of(PREDICTED, OBSERVED)) {
            assertThrows(IllegalArgumentException.class, () -> new PlacementObservation(A, 7, OptionalLong.empty(), 0, evidence, Optional.empty()));
        }
        ledger.bindRun(A);
        assertTrue(ledger.recordAttempt(A, 10, 7, 0));
        assertTrue(ledger.observe(observed(A, 7, 10, 1, LOG)));
        assertThrows(IllegalArgumentException.class, () -> ledger.recordAttempt(A, 11, 7, -1));
        assertThrows(NullPointerException.class, () -> ledger.observe(null));
        assertFalse(ledger.recordAttempt(A, 99, 999, 100));
        assertFalse(ledger.observe(sample(A, 999, 100, AIR)));
        assertEquals(List.of(TWO), ledger.unfinishedTargets());
        assertTrue(ledger.observe(observed(A, 7, 10, 2, LOG)));
        assertTrue(ledger.recordAttempt(A, 0, 2, 0));
        assertTrue(ledger.observe(observed(A, 2, 0, 1, STONE)));
        assertEquals(ALL_CONFIRMED, ledger.outcome());
    }

    @Test
    void pendingQueriesRequireBindingAndDistinguishUnattemptedFromUnknownTargets() {
        var ledger = new PlacementLedger(PLAN);
        assertThrows(IllegalStateException.class, ledger::pendingTargetIds);
        assertThrows(IllegalStateException.class, () -> ledger.latestAttempt(7));
        assertThrows(IllegalStateException.class, () -> ledger.recordIssuedAttempt(A, 0, 7, 0, 0));
        ledger.bindRun(A);
        assertEquals(Set.of(), ledger.pendingTargetIds());
        assertEquals(Optional.empty(), ledger.latestAttempt(7));
        assertThrows(IllegalArgumentException.class, () -> ledger.latestAttempt(-1));
        var unknown = assertThrows(IllegalArgumentException.class, () -> ledger.latestAttempt(999));
        assertTrue(unknown.getMessage().contains("999"));
        assertTrue(ledger.observe(sample(A, 7, 0, LOG)));
        assertTrue(ledger.observe(unknown(A, 7, OptionalLong.empty(), 1)));
        assertTrue(ledger.observe(new PlacementObservation(A, 2, OptionalLong.empty(), 0, PREDICTED, Optional.of(STONE))));
        assertEquals(Set.of(), ledger.pendingTargetIds());
        assertEquals(Optional.empty(), ledger.latestAttempt(7));
        assertEquals(Optional.empty(), ledger.latestAttempt(2));
    }

    @ParameterizedTest
    @EnumSource(Mismatch.class)
    void onlyAcceptedObservedEvidenceResolvesPendingAndRetryReopensIt(Mismatch mismatch) {
        var ledger = boundLedger(A);
        assertTrue(ledger.recordIssuedAttempt(A, 10, 7, 5, 2));
        var issued = new PlacementLedger.AttemptSnapshot(10, 5, OptionalLong.of(2), 1, true);
        assertEquals(issued, ledger.latestAttempt(7).orElseThrow());
        assertEquals(Set.of(7), ledger.pendingTargetIds());
        assertTrue(ledger.observe(unknown(A, 7, OptionalLong.of(10), 6)));
        assertTrue(ledger.observe(new PlacementObservation(A, 7, OptionalLong.empty(), 7, PREDICTED, Optional.of(LOG))));
        assertEquals(issued, ledger.latestAttempt(7).orElseThrow());
        assertEquals(Set.of(7), ledger.pendingTargetIds());
        assertTrue(ledger.observe(sample(A, 7, 8, mismatch.block)));
        assertEquals(Set.of(), ledger.pendingTargetIds());
        assertEquals(new PlacementLedger.AttemptSnapshot(10, 5, OptionalLong.of(2), 1, false), ledger.latestAttempt(7).orElseThrow());
        assertEquals(List.of(SEVEN, TWO), ledger.unfinishedTargets());
        assertTrue(ledger.recordIssuedAttempt(A, 11, 7, 8, 6));
        assertEquals(new PlacementLedger.AttemptSnapshot(11, 8, OptionalLong.of(6), 2, true), ledger.latestAttempt(7).orElseThrow());
        assertTrue(ledger.observe(observed(A, 7, 11, 9, LOG)));
        assertEquals(Set.of(), ledger.pendingTargetIds());
        assertEquals(List.of(TWO), ledger.unfinishedTargets());
        assertTrue(ledger.observe(new PlacementObservation(A, 7, OptionalLong.empty(), 10, PREDICTED, Optional.of(LOG))));
        assertTrue(ledger.observe(unknown(A, 7, OptionalLong.empty(), 11)));
        assertFalse(ledger.latestAttempt(7).orElseThrow().awaitingObservation());
        assertEquals(2, ledger.latestAttempt(7).orElseThrow().count());
        assertEquals(Set.of(), ledger.pendingTargetIds());
        assertEquals(List.of(SEVEN, TWO), ledger.unfinishedTargets());
    }

    @Test
    void mixedExactDuplicatesPreserveMetadataCountAndResolution() {
        var ledger = boundLedger(A);
        assertTrue(ledger.recordIssuedAttempt(A, 10, 7, 5, 100));
        for (boolean resolved : List.of(false, true)) {
            if (resolved) {
                assertTrue(ledger.observe(observed(A, 7, 10, 6, LOG)));
            }
            var before = ledger.latestAttempt(7);
            assertTrue(ledger.recordAttempt(A, 10, 7, 5));
            assertTrue(ledger.recordIssuedAttempt(A, 10, 7, 5, 100));
            assertFalse(ledger.recordIssuedAttempt(A, 10, 7, 5, 99));
            assertFalse(ledger.recordIssuedAttempt(A, 10, 7, 5, 101));
            assertEquals(before, ledger.latestAttempt(7));
            assertEquals(resolved ? Set.of() : Set.of(7), ledger.pendingTargetIds());
            assertEquals(resolved ? List.of(TWO) : List.of(SEVEN, TWO), ledger.unfinishedTargets());
        }
    }

    @Test
    void legacyAttemptMetadataStaysAbsentAndCannotBeEnrichedByDuplicate() {
        var ledger = boundLedger(A);
        assertTrue(ledger.recordAttempt(A, 10, 7, 5));
        var legacy = new PlacementLedger.AttemptSnapshot(10, 5, OptionalLong.empty(), 1, true);
        assertTrue(ledger.recordAttempt(A, 10, 7, 5));
        assertFalse(ledger.recordIssuedAttempt(A, 10, 7, 5, 0));
        assertEquals(legacy, ledger.latestAttempt(7).orElseThrow());
        assertTrue(ledger.observe(sample(A, 7, 6, LOG)));
        assertFalse(ledger.recordIssuedAttempt(A, 10, 7, 5, 5));
        assertFalse(ledger.latestAttempt(7).orElseThrow().awaitingObservation());
        assertTrue(ledger.recordIssuedAttempt(A, 11, 7, 6, 0));
        // Legacy freshness permits a higher action at the same sample tick before later evidence.
        assertTrue(ledger.recordAttempt(A, 12, 7, 6));
        assertEquals(new PlacementLedger.AttemptSnapshot(12, 6, OptionalLong.empty(), 3, true), ledger.latestAttempt(7).orElseThrow());
    }

    @Test
    void eligibleHighWaterSurvivesLegacyAttemptsWithoutComparingTheTwoClocks() {
        var ledger = boundLedger(A);
        assertTrue(ledger.recordIssuedAttempt(A, 10, 7, 5, 100));
        assertTrue(ledger.observe(sample(A, 7, 6, LOG)));
        ledger.bindRun(A); // Same ledger survives a pause; sample time can advance while eligibility freezes.
        assertTrue(ledger.recordAttempt(A, 11, 7, 1_000_000));
        var legacy = ledger.latestAttempt(7).orElseThrow();
        assertEquals(OptionalLong.empty(), legacy.issuedEligibleTick());
        assertFalse(ledger.recordIssuedAttempt(A, 12, 7, 1_000_001, 99));
        assertEquals(legacy, ledger.latestAttempt(7).orElseThrow());
        assertTrue(ledger.recordIssuedAttempt(A, 12, 7, 1_000_001, 100));
        assertTrue(ledger.recordAttempt(A, 13, 7, 1_000_002));
        assertTrue(ledger.recordIssuedAttempt(A, 14, 7, 1_000_003, 101));
        assertEquals(new PlacementLedger.AttemptSnapshot(14, 1_000_003, OptionalLong.of(101), 5, true), ledger.latestAttempt(7).orElseThrow());
        assertEquals(OptionalLong.empty(), legacy.issuedEligibleTick());
        assertTrue(ledger.recordIssuedAttempt(A, 0, 2, 0, 0));
        assertEquals(Set.of(7, 2), ledger.pendingTargetIds());
    }

    @Test
    void staleInputsCannotMutatePendingHistoryOrPoisonFreshness() {
        var ledger = boundLedger(A);
        assertTrue(ledger.recordIssuedAttempt(A, 10, 7, 5, 10));
        assertTrue(ledger.observe(unknown(A, 7, OptionalLong.empty(), 6)));
        var before = ledger.latestAttempt(7);
        assertFalse(ledger.recordIssuedAttempt(B, 99, 7, 100, 100));
        assertFalse(ledger.recordIssuedAttempt(A, 99, 999, 100, 100));
        assertFalse(ledger.recordIssuedAttempt(A, 9, 7, 100, 100));
        assertFalse(ledger.recordIssuedAttempt(A, 10, 7, 100, 100));
        assertFalse(ledger.recordIssuedAttempt(A, 11, 7, 4, 100));
        assertFalse(ledger.recordIssuedAttempt(A, 11, 7, 5, 100));
        assertFalse(ledger.observe(observed(B, 7, 10, 100, LOG)));
        assertFalse(ledger.observe(observed(A, 999, 10, 100, LOG)));
        assertFalse(ledger.observe(observed(A, 7, 9, 100, LOG)));
        assertFalse(ledger.observe(observed(A, 7, 10, 5, LOG)));
        assertFalse(ledger.observe(observed(A, 7, 10, 6, LOG)));
        assertEquals(before, ledger.latestAttempt(7));
        assertEquals(Set.of(7), ledger.pendingTargetIds());
        assertTrue(ledger.observe(observed(A, 7, 10, 7, LOG)));
        before = ledger.latestAttempt(7);
        assertFalse(ledger.observe(observed(A, 7, 9, 100, AIR)));
        assertFalse(ledger.recordIssuedAttempt(A, 11, 7, 6, 100));
        assertEquals(before, ledger.latestAttempt(7));
        assertEquals(Set.of(), ledger.pendingTargetIds());
        assertEquals(List.of(TWO), ledger.unfinishedTargets());
        assertTrue(ledger.recordIssuedAttempt(A, 11, 7, 7, 11));
    }

    @Test
    void pendingSetsAndAttemptSnapshotsAreImmutableDetachedViews() {
        var ledger = boundLedger(A);
        assertTrue(ledger.recordIssuedAttempt(A, 10, 7, 5, 2));
        var pending = ledger.pendingTargetIds();
        var attempt = ledger.latestAttempt(7).orElseThrow();
        assertThrows(UnsupportedOperationException.class, pending::clear);
        assertTrue(ledger.observe(sample(A, 7, 6, LOG)));
        assertTrue(ledger.recordAttempt(A, 0, 2, 0));
        assertEquals(Set.of(7), pending);
        assertEquals(Set.of(2), ledger.pendingTargetIds());
        assertEquals(new PlacementLedger.AttemptSnapshot(10, 5, OptionalLong.of(2), 1, true), attempt);
        assertFalse(ledger.latestAttempt(7).orElseThrow().awaitingObservation());
    }

    @Test
    void issuanceAndSnapshotValidationFollowCoreValueConventions() {
        var ledger = boundLedger(A);
        assertThrows(NullPointerException.class, () -> ledger.recordIssuedAttempt(null, 0, 7, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ledger.recordIssuedAttempt(A, -1, 7, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ledger.recordIssuedAttempt(A, 0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ledger.recordIssuedAttempt(A, 0, 7, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> ledger.recordIssuedAttempt(A, 0, 7, 0, -1));
        assertThrows(NullPointerException.class, () -> new PlacementLedger.AttemptSnapshot(0, 0, null, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new PlacementLedger.AttemptSnapshot(-1, 0, OptionalLong.empty(), 1, true));
        assertThrows(IllegalArgumentException.class, () -> new PlacementLedger.AttemptSnapshot(0, -1, OptionalLong.empty(), 1, true));
        assertThrows(IllegalArgumentException.class, () -> new PlacementLedger.AttemptSnapshot(0, 0, OptionalLong.of(-1), 1, true));
        assertThrows(IllegalArgumentException.class, () -> new PlacementLedger.AttemptSnapshot(0, 0, OptionalLong.empty(), 0, true));
        assertThrows(IllegalArgumentException.class, () -> new PlacementLedger.AttemptSnapshot(0, 0, OptionalLong.empty(), -1, true));
        assertEquals(Optional.empty(), ledger.latestAttempt(7));
        assertEquals(Set.of(), ledger.pendingTargetIds());
    }

    @Test
    void checkedCountOverflowLeavesAllHistoryAndConfirmationUntouched() throws ReflectiveOperationException {
        var ledger = boundLedger(A);
        assertTrue(ledger.recordIssuedAttempt(A, 10, 7, 5, 100));
        assertTrue(ledger.observe(sample(A, 7, 6, LOG)));
        // Seed the otherwise unreachable boundary without adding a production mutation hook.
        var targets = PlacementLedger.class.getDeclaredField("targets");
        targets.setAccessible(true);
        var state = ((Map<?, ?>) targets.get(ledger)).get(7);
        var count = state.getClass().getDeclaredField("attemptCount");
        count.setAccessible(true);
        count.setLong(state, Long.MAX_VALUE);
        var before = ledger.latestAttempt(7);
        assertTrue(ledger.recordAttempt(A, 10, 7, 5));
        assertTrue(ledger.recordIssuedAttempt(A, 10, 7, 5, 100));
        assertFalse(ledger.recordIssuedAttempt(A, 9, 7, 100, 1_000));
        assertThrows(ArithmeticException.class, () -> ledger.recordIssuedAttempt(A, 11, 7, 100, 1_000));
        assertThrows(ArithmeticException.class, () -> ledger.recordAttempt(A, 11, 7, 100));
        assertEquals(before, ledger.latestAttempt(7));
        assertEquals(Set.of(), ledger.pendingTargetIds());
        assertEquals(List.of(TWO), ledger.unfinishedTargets());
        assertTrue(ledger.observe(sample(A, 7, 7, LOG)));
        count.setLong(state, 1);
        assertTrue(ledger.recordIssuedAttempt(A, 11, 7, 7, 101));
        assertEquals(new PlacementLedger.AttemptSnapshot(11, 7, OptionalLong.of(101), 2, true), ledger.latestAttempt(7).orElseThrow());
    }

    private static PlacementLedger boundLedger(UUID runId) {
        var ledger = new PlacementLedger(PLAN);
        ledger.bindRun(runId);
        return ledger;
    }

    private static PlacementObservation sample(UUID runId, int targetId, long tick, BlockSpec state) {
        return new PlacementObservation(runId, targetId, OptionalLong.empty(), tick, OBSERVED, Optional.of(state));
    }

    private static PlacementObservation observed(UUID runId, int targetId, long actionId, long tick, BlockSpec state) {
        return new PlacementObservation(runId, targetId, OptionalLong.of(actionId), tick, OBSERVED, Optional.of(state));
    }

    private static PlacementObservation unknown(UUID runId, int targetId, OptionalLong actionId, long tick) {
        return new PlacementObservation(runId, targetId, actionId, tick, UNKNOWN, Optional.empty());
    }

    private enum Mismatch {
        WRONG_BLOCK(STONE), EMPTY(AIR), WRONG_PROPERTY(new BlockSpec("minecraft:oak_log", Map.of("axis", "x")));

        private final BlockSpec block;

        Mismatch(BlockSpec block) {
            this.block = block;
        }
    }
}
