package com.mapartrunner.core.run;

import com.mapartrunner.core.model.BuildPlan.GridPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.mapartrunner.core.run.RunLifecycle.*;
import static org.junit.jupiter.api.Assertions.*;

class RunLifecycleTest {
    private final RunLifecycle lifecycle = new RunLifecycle();
    private final Context context = context(UUID.randomUUID());

    @Test
    void beginsIdleWithNoRunOrOutcome() {
        assertEquals(Phase.IDLE, lifecycle.snapshot().phase());
        assertTrue(lifecycle.snapshot().context().isEmpty());
        assertTrue(lifecycle.snapshot().activity().isEmpty());
        assertTrue(lifecycle.snapshot().outcome().isEmpty());
        assertFalse(lifecycle.updateActivity(context.runId(), Activity.BUILD));
    }

    @Test
    void startBindsImmutableContextAndBeginsApproach() {
        var idle = lifecycle.snapshot();
        lifecycle.start(context);
        assertEquals(Phase.RUNNING, lifecycle.snapshot().phase());
        assertEquals(Optional.of(context), lifecycle.snapshot().context());
        assertEquals(Optional.of(Activity.APPROACH), lifecycle.snapshot().activity());
        assertTrue(lifecycle.snapshot().outcome().isEmpty());
        assertEquals(Phase.IDLE, idle.phase());
    }

    @ParameterizedTest
    @EnumSource(Activity.class)
    void pauseRetainsContextAndActivityAndResumeRestoresThem(Activity activity) {
        lifecycle.start(context);
        assertTrue(lifecycle.updateActivity(context.runId(), activity));
        lifecycle.pause();
        var paused = lifecycle.snapshot();
        assertEquals(Phase.PAUSED, paused.phase());
        assertEquals(Optional.of(context), paused.context());
        assertEquals(Optional.of(activity), paused.activity());
        assertTrue(paused.outcome().isEmpty());
        assertFalse(lifecycle.updateActivity(context.runId(), Activity.RECOVER));
        assertEquals(paused, lifecycle.snapshot());

        lifecycle.resume(context(context.runId()));
        assertEquals(Phase.RUNNING, lifecycle.snapshot().phase());
        assertEquals(Optional.of(activity), lifecycle.snapshot().activity());
        assertEquals(Phase.PAUSED, paused.phase());
    }

    @Test
    void illegalPhaseTransitionsLeaveStateUntouched() {
        assertThrows(IllegalStateException.class, lifecycle::pause);
        assertThrows(IllegalStateException.class, () -> lifecycle.resume(context));
        assertThrows(IllegalStateException.class, () -> lifecycle.stop(Outcome.CANCELLED));
        lifecycle.start(context);
        var running = lifecycle.snapshot();
        assertThrows(IllegalStateException.class, () -> lifecycle.start(context(UUID.randomUUID())));
        assertThrows(IllegalStateException.class, () -> lifecycle.resume(context));
        assertEquals(running, lifecycle.snapshot());
        lifecycle.pause();
        var paused = lifecycle.snapshot();
        assertThrows(IllegalStateException.class, lifecycle::pause);
        assertThrows(IllegalStateException.class, () -> lifecycle.start(context(UUID.randomUUID())));
        assertEquals(paused, lifecycle.snapshot());
        lifecycle.stop(Outcome.CANCELLED);
        var terminal = lifecycle.snapshot();
        assertThrows(IllegalStateException.class, lifecycle::pause);
        assertThrows(IllegalStateException.class, () -> lifecycle.resume(context));
        assertEquals(terminal, lifecycle.snapshot());
    }

    @Test
    void changedBindingCannotRestorePausedSnapshot() {
        lifecycle.start(context);
        lifecycle.updateActivity(context.runId(), Activity.SHIFT);
        lifecycle.pause();
        var paused = lifecycle.snapshot();
        var changedContexts = List.of(
                new Context(UUID.randomUUID(), "connection", "world", "minecraft:overworld", "plan", context.origin(), "settings"),
                new Context(context.runId(), "rejoined-connection", "world", "minecraft:overworld", "plan", context.origin(), "settings"),
                new Context(context.runId(), "connection", "other-world", "minecraft:overworld", "plan", context.origin(), "settings"),
                new Context(context.runId(), "connection", "world", "minecraft:the_nether", "plan", context.origin(), "settings"),
                new Context(context.runId(), "connection", "world", "minecraft:overworld", "other-plan", context.origin(), "settings"),
                new Context(context.runId(), "connection", "world", "minecraft:overworld", "plan", new GridPos(1, 64, 0), "settings"),
                new Context(context.runId(), "connection", "world", "minecraft:overworld", "plan", context.origin(), "other-settings"));
        for (var changed : changedContexts) {
            assertThrows(IllegalArgumentException.class, () -> lifecycle.resume(changed));
            assertEquals(paused, lifecycle.snapshot());
        }
        lifecycle.stop(Outcome.CANCELLED);
        var newBinding = changedContexts.get(1);
        var freshRun = new Context(UUID.randomUUID(), newBinding.connectionId(), newBinding.worldId(),
                newBinding.dimension(), newBinding.planFingerprint(), newBinding.origin(), newBinding.settingsFingerprint());
        lifecycle.start(freshRun);
        assertEquals(Optional.of(freshRun), lifecycle.snapshot().context());
        assertEquals(Optional.of(Activity.APPROACH), lifecycle.snapshot().activity());
    }

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void stopRetainsFirstTerminalResultAndNewStartResetsIt(Outcome outcome) {
        lifecycle.start(context);
        lifecycle.updateActivity(context.runId(), Activity.REFILL);
        lifecycle.pause();
        lifecycle.stop(outcome);
        var terminal = lifecycle.snapshot();
        assertEquals(Phase.TERMINAL, terminal.phase());
        assertEquals(Optional.of(context), terminal.context());
        assertEquals(Optional.of(outcome), terminal.outcome());
        assertTrue(terminal.activity().isEmpty());
        lifecycle.stop(Outcome.FAILED);
        assertEquals(terminal, lifecycle.snapshot());
        assertFalse(lifecycle.updateActivity(context.runId(), Activity.BUILD));

        var next = context(UUID.randomUUID());
        lifecycle.start(next);
        assertEquals(Optional.of(next), lifecycle.snapshot().context());
        assertEquals(Phase.RUNNING, lifecycle.snapshot().phase());
        assertEquals(Optional.of(Activity.APPROACH), lifecycle.snapshot().activity());
        assertTrue(lifecycle.snapshot().outcome().isEmpty());
        assertEquals(Optional.of(outcome), terminal.outcome());
    }

    @Test
    void staleResultsAndReusedRunIdentitiesCannotAffectNewRun() {
        lifecycle.start(context);
        lifecycle.stop(Outcome.CANCELLED);
        var next = context(UUID.randomUUID());
        lifecycle.start(next);
        var running = lifecycle.snapshot();
        assertFalse(lifecycle.updateActivity(context.runId(), Activity.VERIFY));
        assertFalse(lifecycle.updateActivity(UUID.randomUUID(), Activity.VERIFY));
        assertEquals(running, lifecycle.snapshot());
        assertTrue(lifecycle.updateActivity(next.runId(), Activity.BUILD));
        assertEquals(Optional.of(Activity.BUILD), lifecycle.snapshot().activity());
        lifecycle.stop(Outcome.PARTIAL);
        var terminal = lifecycle.snapshot();
        assertThrows(IllegalArgumentException.class, () -> lifecycle.start(context));
        assertThrows(IllegalArgumentException.class, () -> lifecycle.start(next));
        assertEquals(terminal, lifecycle.snapshot());
    }

    @Test
    void stopAlsoWorksDirectlyFromRunning() {
        lifecycle.start(context);
        lifecycle.stop(Outcome.SUCCESS);
        assertEquals(Optional.of(Outcome.SUCCESS), lifecycle.snapshot().outcome());
        assertTrue(lifecycle.snapshot().activity().isEmpty());
    }

    @Test
    void nullLifecycleArgumentsCannotChangeValidState() {
        assertThrows(NullPointerException.class, () -> lifecycle.start(null));
        lifecycle.start(context);
        var running = lifecycle.snapshot();
        assertThrows(NullPointerException.class, () -> lifecycle.stop(null));
        assertThrows(NullPointerException.class, () -> lifecycle.updateActivity(null, Activity.BUILD));
        assertThrows(NullPointerException.class, () -> lifecycle.updateActivity(context.runId(), null));
        assertEquals(running, lifecycle.snapshot());
        lifecycle.pause();
        var paused = lifecycle.snapshot();
        assertThrows(NullPointerException.class, () -> lifecycle.resume(null));
        assertEquals(paused, lifecycle.snapshot());
    }

    @Test
    void contextRejectsNullAndBlankBindingFields() {
        assertThrows(NullPointerException.class, () -> context(null));
        assertThrows(NullPointerException.class, () -> new Context(context.runId(), "c", "w", "d", "p", null, "s"));
        for (String invalid : new String[]{null, "", " "}) {
            Class<? extends RuntimeException> expected = invalid == null ? NullPointerException.class : IllegalArgumentException.class;
            assertThrows(expected, () -> new Context(context.runId(), invalid, "w", "d", "p", context.origin(), "s"));
            assertThrows(expected, () -> new Context(context.runId(), "c", invalid, "d", "p", context.origin(), "s"));
            assertThrows(expected, () -> new Context(context.runId(), "c", "w", invalid, "p", context.origin(), "s"));
            assertThrows(expected, () -> new Context(context.runId(), "c", "w", "d", invalid, context.origin(), "s"));
            assertThrows(expected, () -> new Context(context.runId(), "c", "w", "d", "p", context.origin(), invalid));
        }
        assertEquals(new GridPos(-10, 64, -20), context.origin());
    }

    @Test
    void snapshotsRejectMissingPartsAndInconsistentPhaseData() {
        assertThrows(NullPointerException.class, () -> new Snapshot(null, Optional.empty(), Optional.empty(), Optional.empty()));
        assertThrows(NullPointerException.class, () -> new Snapshot(Phase.IDLE, null, Optional.empty(), Optional.empty()));
        assertThrows(NullPointerException.class, () -> new Snapshot(Phase.IDLE, Optional.empty(), null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new Snapshot(Phase.IDLE, Optional.empty(), Optional.empty(), null));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(Phase.IDLE, Optional.of(context), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(Phase.RUNNING, Optional.empty(), Optional.of(Activity.BUILD), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(Phase.RUNNING, Optional.of(context), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(Phase.PAUSED, Optional.of(context), Optional.of(Activity.BUILD), Optional.of(Outcome.FAILED)));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(Phase.TERMINAL, Optional.of(context), Optional.of(Activity.BUILD), Optional.of(Outcome.FAILED)));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(Phase.TERMINAL, Optional.of(context), Optional.empty(), Optional.empty()));
    }

    private static Context context(UUID runId) {
        return new Context(runId, "connection", "world", "minecraft:overworld", "plan", new GridPos(-10, 64, -20), "settings");
    }
}
