package com.mapartrunner.core.run;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.Target;
import com.mapartrunner.core.traversal.LanePlanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.mapartrunner.core.run.BuildEngine.*;
import static com.mapartrunner.core.run.BuildEngineTest.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildEngineBudgetTest {
    @Test void fullMapBudgetOutlivesMinimumPlacementCount() {
        var plan = fullPlan(128, 128);
        var h = new Harness(plan);
        h.player = Optional.empty();
        for (int i = 0; i < 16384; i++) {
            var decision = h.step();
            assertTrue(decision.snapshot().diagnostic().isEmpty(),
                    () -> "eligible=" + decision.snapshot().eligibleTicks() + ": " + decision.snapshot().diagnostic());
            assertRunning(decision);
            assertTrue(decision.movement().isEmpty());
            assertTrue(decision.placement().isEmpty());
        }
        assertEquals(16384, h.engine.snapshot().eligibleTicks());
        assertEquals(plan.targets().stream().map(Target::id).toList(), h.engine.snapshot().unfinishedTargetIds());
        assertEquals(109468, runLimit(plan, h.settings));
    }

    @Test void smallFixtureStillFailsAt1200() {
        var h = new Harness(fullPlan(12, 10));
        h.player = Optional.empty();
        assertEquals(1200, runLimit(h.plan, h.settings));
        for (int i = 0; i < 1199; i++) {
            var decision = h.step();
            assertRunning(decision);
            assertTrue(decision.movement().isEmpty());
            assertTrue(decision.placement().isEmpty());
        }
        var failed = h.step();
        assertReason(failed, Reason.RUN_TIMEOUT);
        assertEquals(1200, failed.snapshot().eligibleTicks());
        assertEquals("Run reached 1200 active callbacks", failed.snapshot().diagnostic().orElseThrow().detail());
        assertEquals(h.plan.targets().stream().map(Target::id).toList(), failed.snapshot().unfinishedTargetIds());
    }

    @ParameterizedTest
    @CsvSource({"1,2,5", "2,2,5", "3,2,5", "4,2,5", "5,2,5", "9,2,5", "11,2,5",
            "128,2,5", "11,0,1", "11,1,1", "11,1,3", "11,2,1", "11,2,4", "11,2,5",
            "11,2147483647,2147483647"})
    void budgetLaneCountMatchesBoundedPlanner(int depth, int halfWidth, int stride) {
        var plan = plan(128, depth, target(91, 0, 0));
        var settings = new Settings(halfWidth, stride, false, 0);
        long lanes = new LanePlanner().plan(plan, halfWidth, stride).size();
        long expected = Math.max(1200, 3 + 16 * lanes * 127 + (lanes + 1) * 52 + 2 * (lanes - 1) * 120 + 80);
        assertEquals(expected, runLimit(plan, settings));
    }

    @Test void sparsePlansCountSourceRouteAndLimitsAreMonotone() {
        var settings = Settings.defaults();
        var sparse = plan(128, 128, target(91, 0, 0));
        long limit = runLimit(sparse, settings);
        assertEquals(60319, limit);
        assertTrue(runLimit(plan(129, 128, target(91, 0, 0)), settings) > limit);
        assertTrue(runLimit(plan(128, 134, target(91, 0, 0)), settings) > limit);
        assertEquals(limit + 3, runLimit(plan(128, 128, target(91, 0, 0), target(7, 1, 0)), settings));
        assertEquals(7487, runLimit(plan(1, 128, target(91, 0, 0)), settings));
    }

    @Test void frozenLargerDeadlineSurvivesConfirmationAndPause() {
        var h = new Harness(plan(128, 1, target(91, 0, 0)));
        h.player = Optional.empty();
        long limit = runLimit(h.plan, h.settings);
        assertEquals(2219, limit);
        h.observations = h.scan(STONE); h.step(); h.observations = List.of();
        assertTrue(h.engine.snapshot().unfinishedTargetIds().isEmpty());
        for (int i = 1; i < limit - 1; i++) assertRunning(h.step());
        h.engine.pause();
        for (int i = 0; i < 200; i++) h.step();
        assertEquals(limit - 1, h.engine.snapshot().eligibleTicks());
        h.engine.resume(h.context);
        var failed = h.step();
        assertReason(failed, Reason.RUN_TIMEOUT);
        assertEquals(limit, failed.snapshot().eligibleTicks());
        assertEquals("Run reached 2219 active callbacks", failed.snapshot().diagnostic().orElseThrow().detail());
        assertEquals(List.of(91), failed.snapshot().diagnostic().orElseThrow().targetIds());
    }

    @Test void emptyExtremePlanNeedsNoAllowanceOrLanes() {
        var h = new Harness(plan(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(0, runLimit(h.plan, h.settings));
        assertReason(h.step(), Reason.NOTHING_TO_BUILD);
        assertEquals(0, h.engine.snapshot().eligibleTicks());
    }

    @ParameterizedTest
    @CsvSource({"-1,1", "0,0", "0,2", "2,6"})
    void invalidGeometryIsRejectedBySettings(int halfWidth, int stride) {
        assertThrows(IllegalArgumentException.class, () -> new Settings(halfWidth, stride, false, 0));
    }

    @Test void integerDomainBoundaryIsAdmittedWithoutSaturation() {
        var plan = plan(134217717, 1, target(91, 0, 0));
        assertEquals(2147483643L, runLimit(plan, Settings.defaults()));
        assertEquals(RunLifecycle.Phase.RUNNING, new Harness(plan).engine.snapshot().lifecycle().phase());
        assertThrows(IllegalArgumentException.class,
                () -> runLimit(plan(134217718, 1, target(91, 0, 0)), Settings.defaults()));
    }

    @Test void extremeSparseWorkIsRejectedBeforeLaneAllocationAndRetainsIds() {
        for (var plan : List.of(plan(1, Integer.MAX_VALUE, target(91, 0, 0)),
                plan(Integer.MAX_VALUE, 1, target(91, 0, 0)),
                plan(Integer.MAX_VALUE, Integer.MAX_VALUE, target(91, 0, 0), target(7, 1, 0)))) {
            assertThrows(RuntimeException.class, () -> runLimit(plan, Settings.defaults()));
            var h = new Harness(plan);
            var failed = h.step();
            assertReason(failed, Reason.UNSUPPORTED_PLAN);
            assertEquals(plan.targets().stream().map(Target::id).sorted().toList(),
                    failed.snapshot().diagnostic().orElseThrow().targetIds());
            assertEquals(failed.snapshot().diagnostic().orElseThrow().targetIds(), failed.snapshot().unfinishedTargetIds());
            assertEquals(0, failed.snapshot().eligibleTicks());
            assertEquals("", failed.snapshot().diagnostic().orElseThrow().traversalDetail());
        }
    }

    @Test void longOverflowIsDistinctFromRepresentableWorkAboveIntegerDomain() {
        assertThrows(ArithmeticException.class,
                () -> runLimit(plan(Integer.MAX_VALUE, Integer.MAX_VALUE, target(91, 0, 0)), Settings.defaults()));
        assertThrows(IllegalArgumentException.class,
                () -> runLimit(plan(1, Integer.MAX_VALUE, target(91, 0, 0)), Settings.defaults()));
    }

    private static BuildPlan fullPlan(int width, int depth) {
        var targets = new ArrayList<Target>();
        for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++) {
            targets.add(target(targets.size(), x, z));
        }
        return plan(width, depth, targets.toArray(Target[]::new));
    }
}
