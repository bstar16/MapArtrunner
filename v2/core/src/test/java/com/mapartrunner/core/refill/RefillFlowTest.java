package com.mapartrunner.core.refill;

import com.mapartrunner.core.model.BuildPlan.GridPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.mapartrunner.core.refill.RefillFlow.*;
import static com.mapartrunner.core.refill.RefillPlanTest.*;
import static org.junit.jupiter.api.Assertions.*;

class RefillFlowTest {
    private final UUID run = UUID.randomUUID();
    private long callback;
    private long millis;
    private Map<RefillPlan.Material, Long> needed = Map.of(A, 20L);
    private boolean executable;

    @Test
    void configCopiesSupplyOrderAndValidatesTrustBoundary() {
        var supplies = new ArrayList<>(List.of(supply(2), supply(1)));
        var config = new Config(supplies, 4, new Limits());
        supplies.clear();
        assertEquals(List.of(2, 1), config.orderedSupplies().stream().map(Supply::id).toList());
        assertEquals(4, Config.defaults(List.of()).inventoryClickDelayTicks());
        assertThrows(UnsupportedOperationException.class, () -> config.orderedSupplies().clear());
        for (int pacing : List.of(-1, 11)) assertThrows(IllegalArgumentException.class, () -> new Config(List.of(), pacing, new Limits()));
        for (int pacing : List.of(0, 10)) assertEquals(pacing, new Config(List.of(), pacing, new Limits()).inventoryClickDelayTicks());
        assertThrows(IllegalArgumentException.class, () -> Config.defaults(List.of(supply(1), supply(1))));
        assertThrows(IllegalArgumentException.class, () -> new Supply(0, "minecraft:overworld", new GridPos(0, 0, 0), "stock"));
        assertThrows(IllegalArgumentException.class, () -> new Supply(1, "", new GridPos(0, 0, 0), "stock"));
    }

    @Test
    void checkedTripArithmeticHasEveryReleaseReserve() {
        assertEquals(640, Limits.maxTripCallbacks(0));
        assertEquals(2480, Limits.maxTripCallbacks(1));
        assertEquals(4320, Limits.maxTripCallbacks(2));
        assertThrows(IllegalArgumentException.class, () -> Limits.maxTripCallbacks(-1));
        assertThrows(ArithmeticException.class, () -> Limits.maxTripCallbacks(Long.MAX_VALUE));
    }

    @Test
    void completeRequestUsesCheckedNavigationAndKeepsCoordinatesNeutral() {
        var flow = started(1);
        assertEquals(Stage.INITIAL_RELEASE, flow.snapshot().stage());
        assertEquals(new GridPos(0, 0, 0), flow.snapshot().anchorFloor());
        step(flow, Result.RELEASED, 10);
        assertEquals(Intent.START_OUTBOUND, flow.decision().intent());
        assertEquals(new GridPos(-6, 65, 0), flow.decision().supply().orElseThrow().worldPosition());
        step(flow, Result.ACCEPTED, 10);
        step(flow, Result.ARRIVED, 0);
        step(flow, Result.RELEASED, 0);
        step(flow, Result.OPENED, 0);
        needed = Map.of(A, 0L); executable = true;
        step(flow, Result.ACKNOWLEDGED, 0);
        assertEquals(Stage.CLOSING, flow.snapshot().stage());
        step(flow, Result.CLOSED, 0);
        step(flow, Result.RELEASED, 0);
        assertEquals(Intent.START_RETURN, flow.decision().intent());
        step(flow, Result.ACCEPTED, 10);
        step(flow, Result.ARRIVED, 0);
        step(flow, Result.RELEASED, 0);
        assertEquals(Outcome.SATISFIED, flow.snapshot().outcome().orElseThrow());
        assertEquals(Stage.DONE, flow.snapshot().stage());
        needed = Map.of(A, 20L);
        step(flow, Result.WAITING, 0);
        assertEquals(Intent.NONE, flow.decision().intent());
        assertEquals(Stage.DONE, flow.snapshot().stage());
        assertThrows(IllegalStateException.class, () -> flow.start(request(1)));
    }

    @ParameterizedTest
    @EnumSource(value = Result.class, names = {"REJECTED", "STOPPED", "UNAVAILABLE", "FAILED"})
    void everyRejectedReturnAdmissionFailsExplicitly(Result result) {
        var flow = atReturn();
        step(flow, result, 10);
        assertEquals(Stage.FAILED, flow.snapshot().stage());
        assertTrue(flow.snapshot().reason().contains("RETURNING"));
        assertTrue(flow.snapshot().reason().contains(result.name()));
    }

    @Test
    void firstNavigationWindowUsesAdmittedEntryDistance() {
        var flow = atOutbound();
        step(flow, Result.ACCEPTED, 10);
        for (int i = 0; i < 199; i++) step(flow, Result.WAITING, 9.5);
        assertEquals(Stage.OUTBOUND, flow.snapshot().stage());
        step(flow, Result.WAITING, 9.5);
        assertEquals(Stage.FAILED, flow.snapshot().stage());
        assertTrue(flow.snapshot().reason().contains("progress"));
        var progressing = atOutbound();
        step(progressing, Result.ACCEPTED, 10);
        for (int i = 0; i < 200; i++) step(progressing, Result.WAITING, 9);
        assertEquals(Stage.OUTBOUND, progressing.snapshot().stage());
    }

    @Test
    void duplicateCallbacksChargeOnceAndCannotAcknowledgeAnotherStage() {
        var flow = started(1);
        var observation = observation(flow, Result.RELEASED, 10, true, true);
        flow.tick(observation);
        var state = flow.snapshot();
        flow.tick(observation);
        assertEquals(state, flow.snapshot());
        assertEquals(1, state.totalCallbacks());
        var wrongRun = new Observation(UUID.randomUUID(), ++callback, millis, true, false,
                new CheckedResult(Stage.OUTBOUND, Result.ACCEPTED), 10, needed, false);
        flow.tick(wrongRun);
        assertEquals(state, flow.snapshot());
    }

    @Test
    void pauseRetainsStageAndProgressAllowancesWhileCleanupActuallyAges() {
        var flow = atOutbound(); step(flow, Result.ACCEPTED, 10);
        for (int i = 0; i < 100; i++) step(flow, Result.WAITING, 10);
        var before = flow.snapshot();
        flow.pause(millis);
        for (int i = 0; i < 300; i++) flow.tick(observation(flow, Result.WAITING, 10, true, false));
        assertEquals(before.totalCallbacks(), flow.snapshot().totalCallbacks());
        assertEquals(before.stageCallbacks(), flow.snapshot().stageCallbacks());
        flow.tick(observation(flow, Result.RELEASED, 10, false, true));
        flow.resume();
        assertEquals(before.stageCallbacks(), flow.snapshot().stageCallbacks());
        // A fresh authority admission sets a distance baseline, but cannot renew the old progress age.
        step(flow, Result.ACCEPTED, 10);
        for (int i = 0; i < 99; i++) step(flow, Result.WAITING, 10);
        step(flow, Result.WAITING, 10);
        assertEquals(Stage.FAILED, flow.snapshot().stage());
    }

    @Test
    void revisionsRetainBudgetsAndConsumptionAndCannotTopUp() {
        var flow = atTransfer();
        for (int i = 0; i < 10; i++) step(flow, Result.WAITING, 0);
        var before = flow.snapshot();
        var fresh = facts(List.of(), List.of(slot(10, RefillPlan.Area.MAIN, true, false, 64, A)));
        flow.revise(fresh, Map.of(9, 20));
        assertTrue(flow.decision().plan().reservations().isEmpty());
        assertEquals(Map.of(A, 20L), flow.decision().plan().targetInventoryTotals());
        assertEquals(20, flow.decision().plan().unmet().getFirst().amount());
        assertEquals(before.totalCallbacks(), flow.snapshot().totalCallbacks());
        assertEquals(before.stageCallbacks(), flow.snapshot().stageCallbacks());
        assertEquals(Map.of(A, 20L), flow.snapshot().frozenTotals());
        assertThrows(IllegalArgumentException.class, () -> flow.revise(fresh, Map.of(9, 0)));
        executable = true;
        step(flow, Result.STOCK_EXHAUSTED, 0);
        finishReturn(flow);
        assertEquals(Outcome.PARTIAL_EXECUTABLE, flow.snapshot().outcome().orElseThrow());
        assertTrue(flow.snapshot().capacityLimited());
        assertTrue(flow.snapshot().exhausted());
        step(flow, Result.WAITING, 0);
        assertEquals(Stage.DONE, flow.snapshot().stage());
    }

    @Test
    void visitsEachSupplyOnceThenReturnsUsefulPartialOrExplicitExhaustion() {
        var flow = started(2);
        reachTransfer(flow);
        executable = true;
        step(flow, Result.STOCK_EXHAUSTED, 0);
        step(flow, Result.CLOSED, 0); step(flow, Result.RELEASED, 0);
        assertEquals(List.of(1, 2), flow.snapshot().visitedSupplyIds());
        step(flow, Result.ACCEPTED, 10); step(flow, Result.ARRIVED, 0); step(flow, Result.RELEASED, 0); step(flow, Result.OPENED, 0);
        step(flow, Result.STOCK_EXHAUSTED, 0);
        finishReturn(flow);
        assertEquals(Outcome.PARTIAL_EXECUTABLE, flow.snapshot().outcome().orElseThrow());
        assertEquals(List.of(1, 2), flow.snapshot().visitedSupplyIds());
        executable = false;
        var exhausted = atTransfer();
        step(exhausted, Result.STOCK_EXHAUSTED, 0); finishReturn(exhausted);
        assertEquals(Outcome.FAILED, exhausted.snapshot().outcome().orElseThrow());
        assertTrue(exhausted.snapshot().reason().contains("exhausted"));
    }

    @Test
    void zeroDemandDoesNotTravelAndCapacityLimitIsNotExhaustion() {
        var flow = new RefillFlow();
        var empty = RefillPlan.allocate(List.of(), facts(List.of(), List.of()));
        flow.start(new Request(run, Config.defaults(List.of()), empty, new GridPos(0, 0, 0), Trigger.INITIAL_PREFLIGHT, Map.of(), 0));
        assertEquals(Stage.DONE, flow.snapshot().stage());
        assertEquals(Intent.NONE, flow.decision().intent());
        var noCapacity = RefillPlan.allocate(List.of(demand(0, A, 20)), facts(List.of(), List.of()));
        var blocked = new RefillFlow();
        blocked.start(new Request(run, Config.defaults(List.of(supply(1))), noCapacity, new GridPos(0, 0, 0), Trigger.INITIAL_PREFLIGHT, Map.of(A, 0L), 0));
        assertEquals(Stage.FAILED, blocked.snapshot().stage());
        assertTrue(blocked.snapshot().capacityLimited());
        assertFalse(blocked.snapshot().exhausted());
        assertThrows(IllegalArgumentException.class, () -> new Request(run, Config.defaults(List.of()), empty, new GridPos(0, 0, 0), Trigger.ACTIONABLE_SHORTAGE, Map.of(), 0));
    }

    @Test
    void validatesFiniteDistanceMonotonicTimeAndCheckedStageResults() {
        var flow = atOutbound();
        for (double invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertThrows(IllegalArgumentException.class, () -> observation(flow, Result.ACCEPTED, invalid, true, false));
        }
        assertThrows(IllegalArgumentException.class, () -> new Observation(run, -1, 0, true, false, new CheckedResult(Stage.OUTBOUND, Result.WAITING), 0, needed, false));
        assertThrows(IllegalArgumentException.class, () -> flow.tick(new Observation(run, ++callback, -1, true, false, new CheckedResult(Stage.OUTBOUND, Result.WAITING), 0, needed, false)));
        var wrongStage = new Observation(run, ++callback, millis, true, false, new CheckedResult(Stage.OPENING, Result.OPENED), 0, needed, false);
        flow.tick(wrongStage);
        assertEquals(Stage.OUTBOUND, flow.snapshot().stage());
    }

    @ParameterizedTest
    @EnumSource(value = Stage.class, names = {"OUTBOUND", "OPENING", "TRANSFERRING", "CLOSING", "RETURNING"})
    void everyOrdinaryStageExpiresWithoutSuccessfulEvidence(Stage stage) {
        var flow = atStage(stage);
        int limit = stage == Stage.OUTBOUND || stage == Stage.RETURNING ? 400 : stage == Stage.TRANSFERRING ? 1200 : 40;
        for (int i = 0; i < limit - 1; i++) step(flow, Result.WAITING, 0);
        assertEquals(stage, flow.snapshot().stage());
        step(flow, Result.WAITING, 0);
        assertEquals(Stage.FAILED, flow.snapshot().stage());
        assertTrue(flow.snapshot().reason().contains(stage.name()));
    }

    @ParameterizedTest
    @EnumSource(value = Stage.class, names = {"INITIAL_RELEASE", "OUTBOUND_RELEASE", "TRANSACTION_RELEASE", "RETURN_RELEASE", "FAILED"})
    void everyCleanupStageExpiresAt80ActualUniqueCallbacks(Stage stage) {
        var flow = atStage(stage);
        for (int i = 0; i < 79; i++) flow.tick(observation(flow, Result.WAITING, 0, false, true));
        assertEquals(Intent.RELEASE, flow.decision().intent());
        var duplicate = new Observation(run, callback, millis, false, true, new CheckedResult(flow.snapshot().stage(), Result.WAITING), 0, needed, false);
        flow.tick(duplicate);
        assertEquals(Intent.RELEASE, flow.decision().intent());
        flow.tick(observation(flow, Result.WAITING, 0, false, true));
        assertEquals(Stage.FAILED, flow.snapshot().stage());
        assertEquals(Intent.NONE, flow.decision().intent());
    }

    @ParameterizedTest
    @EnumSource(value = Stage.class, names = {"INITIAL_RELEASE", "OUTBOUND_RELEASE", "TRANSACTION_RELEASE", "RETURN_RELEASE", "FAILED"})
    void everyCleanupStageExpiresByWallTimeWithoutCallbackAging(Stage stage) {
        var flow = atStage(stage);
        long before = flow.snapshot().totalCallbacks();
        millis += 10_000;
        flow.tick(new Observation(run, callback, millis, false, false, new CheckedResult(stage, Result.WAITING), 0, needed, false));
        assertEquals(Stage.FAILED, flow.snapshot().stage());
        assertEquals(Intent.NONE, flow.decision().intent());
        assertEquals(before, flow.snapshot().totalCallbacks());
    }

    @Test
    void acknowledgementDeadlineAndEntireTransferAreIndependentOfPacingAndNewClicks() {
        var ack = atTransfer();
        step(ack, Result.CLICK_ISSUED, 0);
        for (int i = 0; i < 39; i++) step(ack, Result.WAITING, 0);
        assertEquals(Stage.TRANSFERRING, ack.snapshot().stage());
        step(ack, Result.WAITING, 0);
        assertEquals(Stage.FAILED, ack.snapshot().stage());
        assertTrue(ack.snapshot().reason().contains("acknowledgement"));
        var transfer = atTransfer();
        for (int i = 0; i < 1199; i++) {
            Result result = transfer.decision().intent() == Intent.TRANSFER ? Result.CLICK_ISSUED :
                    transfer.snapshot().acknowledgementCallbacks() == 0 ? Result.ACKNOWLEDGED : Result.WAITING;
            step(transfer, result, 0);
        }
        assertEquals(Stage.TRANSFERRING, transfer.snapshot().stage());
        step(transfer, Result.WAITING, 0);
        assertEquals(Stage.FAILED, transfer.snapshot().stage());
        assertTrue(transfer.snapshot().reason().contains("TRANSFERRING"));
    }

    @Test
    void pauseAcknowledgementAndCleanupBudgetsNeverRenewAndPrimaryFailureSurvives() {
        var flow = atTransfer();
        step(flow, Result.CLICK_ISSUED, 0);
        for (int i = 0; i < 20; i++) step(flow, Result.WAITING, 0);
        flow.pause(millis);
        for (int i = 0; i < 39; i++) flow.tick(observation(flow, Result.WAITING, 0, false, true));
        flow.tick(observation(flow, Result.RELEASED, 0, false, true));
        flow.resume();
        assertEquals(20, flow.snapshot().acknowledgementCallbacks());
        flow.pause(millis);
        for (int i = 0; i < 39; i++) flow.tick(observation(flow, Result.WAITING, 0, false, true));
        flow.tick(observation(flow, Result.WAITING, 0, false, true));
        assertEquals(Stage.FAILED, flow.snapshot().stage());
        assertEquals(Intent.NONE, flow.decision().intent());
        String primary = flow.snapshot().reason();
        flow.cancel(millis);
        assertEquals(primary, flow.snapshot().reason());
    }

    @Test
    void totalBudgetCountsExtraPauseCleanupAndRevokesEffectsAtExactBound() {
        var flow = started(1);
        waitThen(flow, 80, Result.RELEASED);
        waitThen(flow, 399, Result.WAITING); // admission pending still spends the outbound allowance
        // Success at callback400 is valid, but must be admitted before an arrival.
        step(flow, Result.ACCEPTED, 1000);
        assertEquals(Stage.FAILED, flow.snapshot().stage());
        assertTrue(flow.snapshot().reason().contains("OUTBOUND"));
        // Exercise the whole-request reserve through real policy transitions, without custom test limits.
        flow = started(1);
        waitThen(flow, 80, Result.RELEASED);
        flow.pause(millis);
        for (int i = 0; i < 79; i++) flow.tick(observation(flow, Result.WAITING, 0, false, true));
        flow.tick(observation(flow, Result.RELEASED, 0, false, true));
        flow.resume();
        waitNavigation(flow, 400);
        waitThen(flow, 80, Result.RELEASED);
        waitThen(flow, 40, Result.OPENED);
        waitThen(flow, 1200, Result.STOCK_EXHAUSTED);
        waitThen(flow, 40, Result.CLOSED);
        waitThen(flow, 80, Result.RELEASED);
        waitNavigation(flow, 400);
        // The extra80 pause callbacks spend the terminal reserve rather than creating another allowance.
        step(flow, Result.REJECTED, 0);
        for (int i = 0; i < 2480 && flow.snapshot().totalCallbacks() < 2480; i++) flow.tick(observation(flow, Result.WAITING, 0, false, true));
        assertEquals(2480, flow.snapshot().totalCallbacks());
        assertEquals(Stage.FAILED, flow.snapshot().stage());
        assertEquals(Intent.NONE, flow.decision().intent());
    }

    @Test
    void releaseObservedDuringPauseIsRetainedAndWallExpiryStillRunsWhenPaused() {
        var flow = started(1);
        flow.pause(millis);
        flow.tick(observation(flow, Result.RELEASED, 0, false, true));
        flow.resume();
        step(flow, Result.WAITING, 0);
        assertEquals(Stage.OUTBOUND, flow.snapshot().stage());
        flow.pause(millis);
        long total = flow.snapshot().totalCallbacks();
        millis += 10_000;
        flow.tick(new Observation(run, ++callback, millis, false, false, new CheckedResult(Stage.OUTBOUND, Result.WAITING), 0, needed, false));
        assertEquals(Stage.FAILED, flow.snapshot().stage());
        assertEquals(Intent.NONE, flow.decision().intent());
        assertEquals(total, flow.snapshot().totalCallbacks());
        assertThrows(IllegalStateException.class, flow::resume);
    }

    @Test
    void noSupplyFailsWithoutInventingTravelAndStoppedAdmittedReturnAlsoFails() {
        var noSupply = started(0);
        step(noSupply, Result.RELEASED, 0);
        assertEquals(Stage.FAILED, noSupply.snapshot().stage());
        assertTrue(noSupply.snapshot().exhausted());
        assertTrue(noSupply.snapshot().visitedSupplyIds().isEmpty());
        var returning = atReturn(); step(returning, Result.ACCEPTED, 10); step(returning, Result.STOPPED, 9);
        assertEquals(Stage.FAILED, returning.snapshot().stage());
        assertTrue(returning.snapshot().reason().contains("STOPPED"));
    }

    @Test
    void pendingAcknowledgementAndTransferAgeSurviveRevisionAndPause() {
        var flow = atTransfer(); step(flow, Result.CLICK_ISSUED, 0);
        for (int i = 0; i < 39; i++) step(flow, Result.WAITING, 0);
        int age = flow.snapshot().stageCallbacks();
        flow.revise(facts(List.of(), List.of(slot(10, RefillPlan.Area.MAIN, true, false, 64, A))), Map.of(9, 7));
        assertEquals(age, flow.snapshot().stageCallbacks());
        assertEquals(39, flow.snapshot().acknowledgementCallbacks());
        flow.pause(millis); flow.tick(observation(flow, Result.RELEASED, 0, false, true)); flow.resume();
        step(flow, Result.WAITING, 0);
        assertEquals(Stage.FAILED, flow.snapshot().stage());
        assertTrue(flow.snapshot().reason().contains("acknowledgement"));
    }

    @Test
    void clickPacingIsUsedByActualTransferDecisionAtZeroAndTen() {
        for (int delay : List.of(0, 10)) {
            var request = request(1);
            var flow = new RefillFlow();
            flow.start(new Request(run, new Config(request.config().orderedSupplies(), delay, new Limits()),
                    request.plan(), request.anchorFloor(), request.trigger(), request.additionalNeeded(), millis));
            reachTransfer(flow);
            assertEquals(Intent.TRANSFER, flow.decision().intent());
            var click = observation(flow, Result.CLICK_ISSUED, 0, true, false);
            flow.tick(click);
            assertEquals(Intent.WAIT, flow.decision().intent());
            long count = flow.snapshot().totalCallbacks();
            flow.tick(click);
            assertEquals(count, flow.snapshot().totalCallbacks());
            step(flow, Result.ACKNOWLEDGED, 0);
            for (int i = 1; i < delay; i++) {
                assertEquals(Intent.WAIT, flow.decision().intent());
                step(flow, Result.WAITING, 0);
            }
            assertEquals(Intent.TRANSFER, flow.decision().intent());
        }
    }

    @Test
    void correctedFreshHeldFactsCanSatisfyFrozenTotalsWithoutMintingReservations() {
        var flow = atTransfer();
        var empty = facts(List.of(), List.of(slot(10, RefillPlan.Area.MAIN, true, false, 64, A)));
        flow.revise(empty, Map.of(9, 20));
        assertTrue(flow.snapshot().capacityLimited());
        flow.revise(facts(List.of(held(9, 20, false, A)), empty.slots()), Map.of(9, 20));
        assertFalse(flow.snapshot().capacityLimited());
        needed = Map.of(A, 0L); executable = true;
        step(flow, Result.ACKNOWLEDGED, 0); finishReturn(flow);
        assertEquals(Outcome.SATISFIED, flow.snapshot().outcome().orElseThrow());
        assertThrows(IllegalStateException.class, () -> flow.revise(empty, Map.of(9, 20)));
    }

    @Test
    void repeatedPauseUsesOnlyRemainingCleanupWallAllowance() {
        var flow = atTransfer();
        flow.pause(millis);
        millis += 1000;
        flow.tick(observation(flow, Result.RELEASED, 0, false, true));
        flow.resume();
        // Active work between settled cleanup intervals spends no physical cleanup wall allowance.
        millis += 20_000;
        step(flow, Result.WAITING, 0);
        assertEquals(Stage.TRANSFERRING, flow.snapshot().stage());
        flow.pause(millis);
        millis += 8998;
        flow.tick(new Observation(run, ++callback, millis, false, false, new CheckedResult(Stage.TRANSFERRING, Result.WAITING), 0, needed, false));
        assertTrue(flow.snapshot().paused());
        millis++;
        flow.tick(new Observation(run, ++callback, millis, false, false, new CheckedResult(Stage.TRANSFERRING, Result.WAITING), 0, needed, false));
        assertEquals(Stage.FAILED, flow.snapshot().stage());
        assertEquals(Intent.NONE, flow.decision().intent());
    }

    @Test
    void pendingAdmissionWaitsWithoutReissuingOrRenewingAndResumeRequiresRelease() {
        var flow = atOutbound();
        assertEquals(Intent.START_OUTBOUND, flow.decision().intent());
        step(flow, Result.WAITING, 10);
        assertEquals(Intent.WAIT, flow.decision().intent());
        int age = flow.snapshot().stageCallbacks();
        flow.pause(millis);
        assertThrows(IllegalStateException.class, flow::resume);
        flow.tick(observation(flow, Result.RELEASED, 10, false, true));
        flow.resume();
        assertEquals(age, flow.snapshot().stageCallbacks());
        assertEquals(Intent.START_OUTBOUND, flow.decision().intent());
        var returning = atReturn();
        assertEquals(Intent.START_RETURN, returning.decision().intent());
        step(returning, Result.WAITING, 10);
        assertEquals(Intent.WAIT, returning.decision().intent());
    }

    RefillFlow atStage(Stage stage) {
        if (stage == Stage.INITIAL_RELEASE) return started(1);
        if (stage == Stage.OUTBOUND) return atOutbound();
        if (stage == Stage.RETURNING) return atReturn();
        if (stage == Stage.FAILED) { var flow = atOutbound(); step(flow, Result.REJECTED, 0); return flow; }
        var flow = atOutbound(); step(flow, Result.ACCEPTED, 10); step(flow, Result.ARRIVED, 0);
        if (stage == Stage.OUTBOUND_RELEASE) return flow;
        step(flow, Result.RELEASED, 0);
        if (stage == Stage.OPENING) return flow;
        step(flow, Result.OPENED, 0);
        if (stage == Stage.TRANSFERRING) return flow;
        step(flow, Result.STOCK_EXHAUSTED, 0);
        if (stage == Stage.CLOSING) return flow;
        step(flow, Result.CLOSED, 0);
        if (stage == Stage.TRANSACTION_RELEASE) return flow;
        step(flow, Result.RELEASED, 0); step(flow, Result.ACCEPTED, 10); step(flow, Result.ARRIVED, 0);
        return flow;
    }
    void waitThen(RefillFlow flow, int count, Result last) { for (int i = 1; i < count; i++) step(flow, Result.WAITING, 0); step(flow, last, 0); }
    void waitNavigation(RefillFlow flow, int count) {
        step(flow, Result.ACCEPTED, 1000);
        for (int i = 1; i < count - 1; i++) step(flow, Result.WAITING, 1000 - i);
        step(flow, Result.ARRIVED, 0);
    }

    RefillFlow started(int supplies) { var flow = new RefillFlow(); flow.start(request(supplies)); return flow; }
    Request request(int count) {
        var supplies = java.util.stream.IntStream.rangeClosed(1, count).mapToObj(RefillFlowTest::supply).toList();
        var plan = RefillPlan.allocate(List.of(demand(0, A, 20)), facts(List.of(), List.of(slot(9, RefillPlan.Area.MAIN, true, false, 64, A))));
        return new Request(run, Config.defaults(supplies), plan, new GridPos(0, 0, 0), Trigger.INITIAL_PREFLIGHT, Map.of(A, 20L), millis);
    }
    static Supply supply(int id) { return new Supply(id, "minecraft:overworld", new GridPos(-6, 65, 0), "stock " + id); }
    RefillFlow atOutbound() { var flow = started(1); step(flow, Result.RELEASED, 10); return flow; }
    RefillFlow atTransfer() { var flow = started(1); reachTransfer(flow); return flow; }
    RefillFlow atReturn() { var flow = atTransfer(); step(flow, Result.STOCK_EXHAUSTED, 0); step(flow, Result.CLOSED, 0); step(flow, Result.RELEASED, 0); return flow; }
    void reachTransfer(RefillFlow flow) {
        step(flow, Result.RELEASED, 10); step(flow, Result.ACCEPTED, 10); step(flow, Result.ARRIVED, 0); step(flow, Result.RELEASED, 0); step(flow, Result.OPENED, 0);
    }
    void finishReturn(RefillFlow flow) {
        step(flow, Result.CLOSED, 0); step(flow, Result.RELEASED, 0); step(flow, Result.ACCEPTED, 10); step(flow, Result.ARRIVED, 0); step(flow, Result.RELEASED, 0);
    }
    void step(RefillFlow flow, Result result, double distance) { flow.tick(observation(flow, result, distance, true, isRelease(flow.snapshot().stage()))); }
    Observation observation(RefillFlow flow, Result result, double distance, boolean eligible, boolean cleanup) {
        return new Observation(run, ++callback, ++millis, eligible, cleanup, new CheckedResult(flow.snapshot().stage(), result), distance, needed, executable);
    }
}
