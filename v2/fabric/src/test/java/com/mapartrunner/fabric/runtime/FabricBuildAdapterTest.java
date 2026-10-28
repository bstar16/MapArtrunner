package com.mapartrunner.fabric.runtime;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.core.movement.MovementPolicy.*;
import com.mapartrunner.core.placement.PlacementObservation;
import com.mapartrunner.core.placement.PlacementObservation.Evidence;
import com.mapartrunner.core.run.BuildEngine;
import com.mapartrunner.core.run.BuildEngine.*;
import com.mapartrunner.core.run.RunLifecycle;
import com.mapartrunner.core.run.ForwardTraversal.Substage;
import com.mapartrunner.fabric.runtime.FabricBuildAdapter.*;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.StickyKeyBinding;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.function.BooleanSupplier;

import static com.mapartrunner.core.run.BuildEngine.ControlOwner.*;
import static com.mapartrunner.core.run.BuildEngine.PlacementAvailability.*;
import static com.mapartrunner.core.run.RunLifecycle.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricBuildAdapterTest {
    private static final BlockSpec STONE = new BlockSpec("minecraft:stone", Map.of());
    private static final BlockSpec AIR = new BlockSpec("minecraft:air", Map.of());
    private static final GridPos ORIGIN = new GridPos(-17, 64, -11);

    @BeforeAll static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @Test void oneCallbackCapturesOnceThenUsesRealPlacementStagesAndDeliversFeedbackNextCallback() {
        var h = new Harness(12);
        h.ready = READY;
        h.start(); h.adapter.tick();
        assertEquals(List.of("capture", "acquire", "yaw", "inspect", "slot", "inspect", "interact",
                "key:FORWARD:false", "key:BACK:false", "key:LEFT:false", "key:RIGHT:false",
                "key:JUMP:false", "key:SNEAK:false", "key:SPRINT:false", "key:USE:false", "key:ATTACK:false"), h.events);
        assertEquals(1, h.adapter.status().engine().eligibleTicks());
        assertEquals(1, h.captures);
        assertTrue(h.adapter.status().engine().effectResults().isEmpty());
        assertEquals(Set.of(7), h.adapter.status().engine().pendingTargetIds());
        assertEquals(List.of(7), h.adapter.status().engine().unfinishedTargetIds());
        var issued = h.intent;
        h.events.clear(); h.adapter.tick();
        assertEquals(2, h.captures);
        assertEquals(2, h.adapter.status().engine().eligibleTicks());
        assertEquals(List.of(new PlacementEffectResult(issued.runId(), issued.actionId(), 7, issued.tick(), EffectStatus.ACCEPTED)),
                h.adapter.status().engine().effectResults());
        assertFalse(h.events.contains("interact"));
        h.adapter.tick();
        assertTrue(h.adapter.status().engine().effectResults().isEmpty());
    }

    @Test void supportedEntryApproachIsPreservedButAlignmentUsesActualLaterSamples() {
        var h = new Harness(12); h.pose = new PlayerObservation(1.5, 1, 2.5, -90);
        h.start(); h.adapter.tick();
        assertTrue(h.keys.get(ControlArbiter.Control.BACK));
        assertEquals(Stage.ENTER_POSITION, h.adapter.status().engine().laneEntry().orElseThrow().stage());
        h.pose = new PlayerObservation(.5, 1, 2.5, 0);
        h.adapter.tick(); h.assertNoTranslation();
        h.adapter.tick(); h.assertNoTranslation();
        assertEquals(0, h.adapter.status().engine().laneEntry().orElseThrow().consecutiveYawAlignedTicks());
        h.pose = new PlayerObservation(.5, 1, 2.5, -90);
        h.adapter.tick(); h.assertNoTranslation();
        assertEquals(1, h.adapter.status().engine().laneEntry().orElseThrow().consecutiveYawAlignedTicks());
        h.adapter.tick(); h.assertNoTranslation();
        assertEquals(Stage.WALK, h.adapter.status().engine().laneEntry().orElseThrow().stage());
        h.adapter.tick();
        assertTrue(h.keys.get(ControlArbiter.Control.FORWARD));
        assertTrue(h.keys.get(ControlArbiter.Control.SPRINT));
    }

    @Test void lateralEntryMustFinishBeforeWalkAndYawHoldCannotTranslate() {
        var h = new Harness(12); h.pose = new PlayerObservation(.5, 1, 1.5, -90);
        h.start(); h.adapter.tick();
        assertEquals(Stage.ENTER_POSITION, h.adapter.status().engine().laneEntry().orElseThrow().stage());
        assertTrue(h.keys.get(ControlArbiter.Control.RIGHT));
        assertFalse(h.keys.get(ControlArbiter.Control.FORWARD));
        h.pose = new PlayerObservation(.5, 1, 2.5, -90);
        h.adapter.tick(); h.adapter.tick(); h.adapter.tick(); h.adapter.tick();
        h.pose = new PlayerObservation(.5, 1, 2.5, 0);
        h.adapter.tick(); h.assertNoTranslation();
        assertEquals(1, h.adapter.status().engine().laneEntry().orElseThrow().yawHoldTicks());
    }

    @Test void verifyReleasesLeaseButRetainsBindingForPlacementThenLaterSuccessInvalidates() {
        var h = new Harness(2); h.start();
        h.adapter.tick(); h.adapter.tick(); h.adapter.tick();
        assertEquals(NATIVE, h.adapter.status().controlOwner());
        h.events.clear(); h.ready = READY; h.adapter.tick();
        assertEquals(Activity.VERIFY, h.adapter.status().engine().lifecycle().activity().orElseThrow());
        assertEquals(NONE, h.adapter.status().controlOwner());
        assertTrue(h.events.indexOf("release") < h.events.indexOf("inspect"));
        assertTrue(h.events.contains("interact"));
        assertTrue(h.arbiter.isCurrent(h.placementBinding));
        assertFalse(h.lastGuard.getAsBoolean(), "Consumed envelope cannot be reused after the callback");
        h.observed = STONE; h.adapter.tick();
        assertEquals(Outcome.SUCCESS, h.adapter.status().engine().lifecycle().outcome().orElseThrow());
        assertFalse(h.arbiter.isCurrent(h.placementBinding));
        assertTrue(h.adapter.status().failure().isEmpty());
    }

    @Test void replayingRealC6WithConsumedEnvelopeDoesNotSelectOrInteract() {
        var h = new Harness(12); h.ready = READY; h.start(); h.adapter.tick(); h.events.clear();
        var replay = FabricPlacementEffects.apply(h.placement, h.plan, h.run, Settings.defaults(), h.intent,
                h.arbiter, h.placementBinding, h.lastGuard);
        assertEquals(EffectStatus.STALE, replay.effect().status());
        assertTrue(h.events.isEmpty());
    }

    @Test void nativeShiftAppliesYawBeforePlacementBeforeKeys() {
        var h = twoLaneShift(); h.ready = READY; h.events.clear();
        h.sample(11.5, 3.5, 0);
        assertEquals(8, h.intent.targetId());
        assertEquals(List.of("capture", "yaw", "inspect", "slot", "inspect", "interact",
                "key:FORWARD:true", "key:BACK:false", "key:LEFT:false", "key:RIGHT:false",
                "key:JUMP:false", "key:SNEAK:false", "key:SPRINT:false", "key:USE:false", "key:ATTACK:false"), h.events);
        assertEquals(Activity.SHIFT, h.adapter.status().engine().lifecycle().activity().orElseThrow());
        h.ready = NO_USABLE_ITEM;
        h.sample(11, 6.5, 0);
        assertEquals(Substage.NEXT_ENTRY, h.adapter.status().engine().traversal().orElseThrow().substage());
        assertEquals(NONE, h.adapter.status().controlOwner()); h.assertAllReleased();
        h.sample(11, 6.5, 90);
        assertTrue(h.keys.get(ControlArbiter.Control.BACK));
        h.sample(11.5, 6.5, 90);
        assertTrue(h.keys.get(ControlArbiter.Control.LEFT));
        assertFalse(h.keys.get(ControlArbiter.Control.BACK));
        h.sample(11.5, 7.5, 90); h.assertNoTranslation();
        h.pose = new PlayerObservation(11.5, 1, 7.5, 0);
        h.adapter.tick(); h.assertNoTranslation();
        assertEquals(0, h.adapter.status().engine().laneEntry().orElseThrow().consecutiveYawAlignedTicks());
        h.sample(11.5, 7.5, 90); h.assertNoTranslation();
        h.sample(11.5, 7.5, 90); h.assertNoTranslation();
        var west = h.adapter.status().engine();
        assertEquals(Substage.LANE, west.traversal().orElseThrow().substage());
        assertEquals(1, west.traversal().orElseThrow().laneIndex());
        assertEquals(Stage.WALK, west.laneEntry().orElseThrow().stage());
        assertEquals(Activity.BUILD, west.lifecycle().activity().orElseThrow());
        h.sample(11.5, 7.5, 90);
        assertTrue(h.keys.get(ControlArbiter.Control.FORWARD));
        assertTrue(h.keys.get(ControlArbiter.Control.SPRINT));
        assertTrue(h.acquiredOwners.stream().allMatch(owner -> owner == NATIVE));
    }

    @ParameterizedTest @ValueSource(strings = {"yaw", "inspect", "slot", "interact"})
    void shiftPauseInvalidatesAlreadyComputedEffects(String at) {
        var h = twoLaneShift(); h.ready = READY; h.events.clear();
        h.hooks.put(at, h.adapter::pause);
        h.sample(11.5, 3.5, 0);
        var paused = h.adapter.status().engine();
        assertEquals(Phase.PAUSED, paused.lifecycle().phase());
        assertEquals(Substage.SHIFT, paused.traversal().orElseThrow().substage());
        assertEquals(Set.of(8), paused.pendingTargetIds());
        assertEquals(NONE, h.adapter.status().controlOwner()); h.assertAllReleased();
        assertEquals(9, h.events.stream().filter(e -> e.startsWith("key:")).count());
        if (!at.equals("interact")) assertFalse(h.events.contains("interact"));
        if (at.equals("yaw") || at.equals("inspect")) assertFalse(h.events.contains("slot"));
        assertFalse(h.lastGuard != null && h.lastGuard.getAsBoolean());
        h.events.clear(); h.adapter.tick();
        var stillPaused = h.adapter.status().engine();
        assertEquals(paused.lifecycle(), stillPaused.lifecycle());
        assertEquals(paused.eligibleTicks(), stillPaused.eligibleTicks());
        assertEquals(paused.traversal(), stillPaused.traversal());
        assertEquals(paused.unfinishedTargetIds(), stillPaused.unfinishedTargetIds());
        assertEquals(List.of("capture"), h.events);
        h.adapter.resume(); h.sample(11.5, 3.5, 0);
        assertTrue(h.adapter.status().engine().effectResults().isEmpty());
        assertTrue(h.acquiredOwners.stream().allMatch(owner -> owner == NATIVE));
    }

    @ParameterizedTest @ValueSource(strings = {"stop", "world"})
    void shiftStopAndWorldReplacementReleaseEveryKey(String path) {
        var h = twoLaneShift(); h.ready = READY; h.events.clear();
        for (var control : ControlArbiter.Control.values()) h.keys.put(control, true);
        h.hooks.put("slot", () -> {
            if (path.equals("stop")) h.adapter.stop();
            else { h.state = h.withWorld(new Object()); h.adapter.worldChanged(h.state.world()); }
            h.assertAllReleased();
        });
        h.sample(11.5, 3.5, 0);
        var stopped = h.adapter.status();
        assertEquals(Phase.TERMINAL, stopped.engine().lifecycle().phase());
        assertEquals(Outcome.CANCELLED, stopped.engine().lifecycle().outcome().orElseThrow());
        assertEquals(NONE, stopped.controlOwner()); h.assertAllReleased();
        assertEquals(9, h.events.stream().filter(e -> e.startsWith("key:")).count());
        assertTrue(h.events.contains("release")); assertFalse(h.events.contains("interact"));
        assertFalse(h.lastGuard.getAsBoolean()); assertFalse(h.arbiter.isCurrent(h.placementBinding));
        assertTrue(stopped.engine().traversal().isEmpty()); assertTrue(stopped.engine().pendingTargetIds().isEmpty());
        h.events.clear(); h.adapter.tick(); assertTrue(h.events.isEmpty());
        assertTrue(h.acquiredOwners.stream().allMatch(owner -> owner == NATIVE));
    }

    @Test void oldShiftEnvelopeCannotAcquireFreshRunControls() {
        var h = twoLaneShift(); h.ready = READY; h.events.clear();
        var oldRun = h.adapter.status().engine().lifecycle().context().orElseThrow();
        h.hooks.put("slot", () -> { h.adapter.stop(); h.start(); });
        h.sample(11.5, 3.5, 0);
        var oldBinding = h.placementBinding; var oldIntent = h.intent; var oldGuard = h.lastGuard;
        var fresh = h.adapter.status().engine();
        assertNotEquals(oldRun.runId(), fresh.lifecycle().context().orElseThrow().runId());
        assertEquals(0, fresh.eligibleTicks()); assertEquals(NONE, h.adapter.status().controlOwner());
        assertFalse(h.events.contains("interact")); h.assertAllReleased();
        h.ready = NO_USABLE_ITEM;
        h.sample(.5, 2.5, -90);
        assertEquals(NATIVE, h.adapter.status().controlOwner());
        h.events.clear();
        assertEquals(ControlArbiter.AcquireStatus.STALE, h.arbiter.acquire(oldBinding, NATIVE).status());
        var replay = FabricPlacementEffects.apply(h.placement, h.plan, oldRun, Settings.defaults(), oldIntent,
                h.arbiter, oldBinding, oldGuard);
        assertEquals(EffectStatus.STALE, replay.effect().status());
        assertTrue(h.events.isEmpty()); assertEquals(NATIVE, h.adapter.status().controlOwner());
        assertTrue(h.adapter.status().engine().pendingTargetIds().isEmpty());
        assertTrue(h.acquiredOwners.stream().allMatch(owner -> owner == NATIVE));
    }

    private static Harness twoLaneShift() {
        var h = new Harness(new BuildPlan("two lanes", new GridSize(12, 1, 10), List.of(
                new Target(7, new GridPos(0, 0, 0), STONE), new Target(8, new GridPos(10, 0, 3), STONE))));
        h.start(); h.walk();
        h.sample(11.5, 2.5, -90);
        assertEquals(Substage.ENDPOINT, h.adapter.status().engine().traversal().orElseThrow().substage());
        assertEquals(NONE, h.adapter.status().controlOwner()); h.assertAllReleased();
        for (int i = 0; i < 3; i++) h.sample(11.5, 2.5, -90);
        for (int i = 0; i < 3; i++) h.sample(11.5, 2.5, 0);
        var shift = h.adapter.status().engine().traversal().orElseThrow();
        assertEquals(Substage.SHIFT, shift.substage());
        assertEquals(com.mapartrunner.core.traversal.LanePlanner.Direction.SOUTH, shift.entry().lane().direction());
        assertEquals(Stage.WALK, shift.entry().stage());
        return h;
    }

    @Test void pauseAndResumeDuringCaptureCannotRefreshOldAuthority() {
        var h = new Harness(12); h.ready = READY; var id = h.start();
        h.hooks.put("capture", () -> { h.adapter.pause(); h.adapter.resume(); });
        h.adapter.tick();
        assertEquals(id, h.adapter.status().engine().lifecycle().context().orElseThrow().runId());
        assertEquals(List.of("capture"), h.events);
        assertEquals(1, h.adapter.status().engine().eligibleTicks());
        assertEquals(NONE, h.adapter.status().controlOwner());
        h.events.clear(); h.adapter.tick();
        assertTrue(h.events.contains("yaw"));
    }

    @Test void pauseDuringCaptureDiscardsFeedbackAlreadyTakenForThisCallback() {
        var h = new Harness(12); h.ready = READY; h.start(); h.adapter.tick();
        h.hooks.put("capture", () -> { h.adapter.pause(); h.adapter.resume(); });
        h.adapter.tick();
        assertTrue(h.adapter.status().engine().effectResults().isEmpty());
        h.adapter.tick(); assertTrue(h.adapter.status().engine().effectResults().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"yaw", "inspect", "slot", "interact"})
    void pauseResumeBetweenEffectsStopsAllLaterStages(String at) {
        var h = new Harness(12); h.ready = READY; h.start();
        h.hooks.put(at, () -> { h.adapter.pause(); h.adapter.resume(); });
        h.adapter.tick();
        assertEquals(NONE, h.adapter.status().controlOwner());
        h.assertNoTranslation();
        if (!at.equals("interact")) assertFalse(h.events.contains("interact"));
        if (at.equals("yaw") || at.equals("inspect")) assertFalse(h.events.contains("slot"));
        assertFalse(h.lastGuard != null && h.lastGuard.getAsBoolean());
        h.adapter.tick();
        assertTrue(h.adapter.status().engine().effectResults().isEmpty(), "Invalidated feedback is not delivered");
    }

    @Test void oldCapturedRunCannotTickOrReleaseNewRun() {
        var h = new Harness(12); var old = h.start();
        h.hooks.put("capture", () -> { h.adapter.stop(); h.start(); });
        h.adapter.tick();
        assertNotEquals(old, h.adapter.status().engine().lifecycle().context().orElseThrow().runId());
        assertEquals(0, h.adapter.status().engine().eligibleTicks());
        assertEquals(List.of("capture"), h.events);
        h.adapter.tick(); assertEquals(1, h.adapter.status().engine().eligibleTicks());
    }

    @ParameterizedTest @ValueSource(strings = {"capture", "yaw", "slot", "interact"})
    void oldCallbackExceptionCannotCancelOrContaminateNewRun(String at) {
        var h = new Harness(12); h.ready = READY; var old = h.start();
        var original = new IllegalStateException("old callback failed");
        h.hooks.put(at, () -> { h.adapter.stop(); h.start(); throw original; });
        assertSame(original, assertThrows(IllegalStateException.class, h.adapter::tick));
        var status = h.adapter.status();
        assertNotEquals(old, status.engine().lifecycle().context().orElseThrow().runId());
        assertEquals(Phase.RUNNING, status.engine().lifecycle().phase());
        assertEquals(0, status.engine().eligibleTicks());
        assertTrue(status.failure().isEmpty());
        h.adapter.tick();
        assertEquals(1, h.adapter.status().engine().eligibleTicks());
        assertEquals(NATIVE, h.adapter.status().controlOwner());
    }

    @ParameterizedTest @ValueSource(strings = {"capture", "acquire", "yaw", "slot", "interact", "key:FORWARD:false"})
    void failuresImmediatelyCancelReleaseAndRetainOriginalTrace(String at) {
        var h = new Harness(12); h.ready = READY; var id = h.start();
        var original = new IllegalStateException("failure at " + at);
        h.hooks.put(at, () -> { throw original; });
        assertSame(original, assertThrows(IllegalStateException.class, h.adapter::tick));
        var status = h.adapter.status();
        assertEquals(NONE, status.controlOwner());
        assertEquals(Outcome.CANCELLED, status.engine().lifecycle().outcome().orElseThrow());
        assertEquals(Reason.CANCELLED, status.engine().diagnostic().orElseThrow().reason());
        assertEquals(id, status.failure().orElseThrow().runId());
        assertTrue(status.failure().orElseThrow().trace().orElseThrow().contains(original.getMessage()));
        h.assertNoTranslation();
        if (at.equals("capture") || at.equals("acquire") || at.equals("yaw")) assertFalse(h.events.contains("slot"));
        if (!at.equals("interact") && !at.startsWith("key:")) assertFalse(h.events.contains("interact"));
        h.events.clear(); h.adapter.tick(); h.adapter.stop();
        assertTrue(h.events.isEmpty()); assertEquals(status.failure(), h.adapter.status().failure());
    }

    @Test void failedReleaseDoesNotPreventOtherCleanupOrHidePrimaryError() {
        var h = new Harness(12); h.ready = READY; h.start();
        var original = new AssertionError("native failure");
        var cleanup = new IllegalStateException("release failure");
        h.hooks.put("interact", () -> { throw original; });
        h.hooks.put("key:LEFT:false", () -> { throw cleanup; });
        assertSame(original, assertThrows(AssertionError.class, h.adapter::tick));
        assertArrayEquals(new Throwable[]{cleanup}, original.getSuppressed());
        assertTrue(h.events.contains("key:ATTACK:false")); assertTrue(h.events.contains("release"));
        assertEquals(NONE, h.adapter.status().controlOwner());
        assertEquals(Phase.TERMINAL, h.adapter.status().engine().lifecycle().phase());
        var trace = h.adapter.status().failure().orElseThrow().trace().orElseThrow();
        assertTrue(trace.contains("Suppressed")); assertTrue(trace.contains("release failure"));
    }

    @Test void reentrantTickFailsOnceWithoutAnotherCapture() {
        var h = new Harness(12); h.start(); h.hooks.put("capture", h.adapter::tick);
        assertThrows(IllegalStateException.class, h.adapter::tick);
        assertEquals(1, h.captures); assertEquals(0, h.adapter.status().engine().eligibleTicks());
        assertEquals(Phase.TERMINAL, h.adapter.status().engine().lifecycle().phase());
    }

    @ParameterizedTest @ValueSource(strings = {"pause", "stop", "terminal"})
    void cleanupErrorStillClearsOwnedStateAndRetainsTrace(String path) {
        var h = new Harness(12); h.start(); h.walk();
        var error = new IllegalStateException("release failed");
        h.hooks.put("key:SPRINT:false", () -> { throw error; });
        Runnable action = switch (path) {
            case "pause" -> h.adapter::pause;
            case "stop" -> h.adapter::stop;
            default -> {
                h.pose = new PlayerObservation(100, 1, 2.5, -90);
                yield h.adapter::tick;
            }
        };
        assertSame(error, assertThrows(IllegalStateException.class, action::run));
        assertEquals(NONE, h.adapter.status().controlOwner());
        assertEquals(Phase.TERMINAL, h.adapter.status().engine().lifecycle().phase());
        assertEquals(path.equals("terminal") ? Outcome.FAILED : Outcome.CANCELLED,
                h.adapter.status().engine().lifecycle().outcome().orElseThrow());
        assertTrue(h.adapter.status().failure().orElseThrow().trace().orElseThrow().contains("release failed"));
        h.start(); assertTrue(h.adapter.status().failure().isEmpty());
    }

    @Test void failedAcquisitionIsExplicitAndNeverAppliesYawOrPlacement() {
        var h = new Harness(12); h.ready = READY; h.start(); h.acquireAvailable = false;
        h.adapter.tick();
        assertEquals(List.of("capture", "acquire"), h.events);
        assertEquals(Outcome.CANCELLED, h.adapter.status().engine().lifecycle().outcome().orElseThrow());
        assertTrue(h.adapter.status().failure().orElseThrow().detail().contains("unavailable"));
    }

    @ParameterizedTest @ValueSource(strings = {"pause", "stop", "screen", "shutdown", "world", "disconnect"})
    void everyLifecycleReleasePathReleasesBeforeReturning(String path) {
        var h = new Harness(12); h.start(); h.walk(); h.events.clear();
        var bound = h.state;
        switch (path) {
            case "pause" -> h.adapter.pause();
            case "stop" -> h.adapter.stop();
            case "screen" -> h.adapter.onScreenOpened();
            case "shutdown" -> h.adapter.onClientStopping();
            case "world" -> h.adapter.worldChanged(new Object());
            case "disconnect" -> h.adapter.disconnected(bound.handler(), bound.connection());
        }
        assertEquals(NONE, h.adapter.status().controlOwner()); h.assertNoTranslation();
        assertEquals(9, h.events.stream().filter(e -> e.startsWith("key:")).count());
        assertTrue(h.events.contains("release"));
        assertEquals(path.equals("pause") || path.equals("screen") ? Phase.PAUSED : Phase.TERMINAL,
                h.adapter.status().engine().lifecycle().phase());
    }

    @Test void worldHookReleasesBeforeNewWorldPhysicsAndRejectsResume() {
        var h = new Harness(12); h.start(); h.walk(); h.events.clear();
        h.state = h.withWorld(new Object()); h.adapter.worldChanged(h.state.world());
        h.events.add("new-world-physics");
        assertTrue(h.events.indexOf("release") < h.events.indexOf("new-world-physics"));
        assertThrows(IllegalStateException.class, h.adapter::resume);
        assertTrue(h.adapter.status().failure().orElseThrow().detail().contains("world"));
    }

    @Test void lateOldDisconnectCannotCancelNewRunAndBothIdentitiesMustMatch() {
        var h = new Harness(12); h.start(); var old = h.state; h.adapter.stop();
        h.state = new ClientState(new Object(), new Object(), new Object(), new Object(), old.dimension(), true, false, false);
        var current = h.start(); h.walk(); h.events.clear();
        h.adapter.disconnected(old.handler(), old.connection());
        h.adapter.disconnected(h.state.handler(), old.connection());
        h.adapter.disconnected(old.handler(), h.state.connection());
        assertEquals(current, h.adapter.status().engine().lifecycle().context().orElseThrow().runId());
        assertEquals(NATIVE, h.adapter.status().controlOwner()); assertTrue(h.events.isEmpty());
    }

    @Test void pollingLossAndLossBetweenSlotAndInteractionCancelWithoutLaterEffects() {
        var h = new Harness(12); h.ready = READY; h.start();
        h.hooks.put("slot", () -> h.state = h.withWorld(new Object()));
        h.adapter.tick();
        assertFalse(h.events.contains("interact"));
        assertEquals(NONE, h.adapter.status().controlOwner());
        assertEquals(Outcome.CANCELLED, h.adapter.status().engine().lifecycle().outcome().orElseThrow());
        assertTrue(h.adapter.status().failure().isPresent());
        h.start(); h.walk(); h.state = new ClientState(h.state.world(), h.state.handler(), h.state.connection(),
                h.state.player(), h.state.dimension(), false, false, false);
        int captures = h.captures; h.adapter.tick();
        assertEquals(captures, h.captures); assertEquals(NONE, h.adapter.status().controlOwner());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void gamePauseOrScreenFreezesBudgetsAndNeedsManualResume(boolean screen) {
        var h = new Harness(2); h.start(); h.walk();
        h.state = new ClientState(h.state.world(), h.state.handler(), h.state.connection(), h.state.player(),
                h.state.dimension(), true, !screen, screen);
        h.adapter.tick(); var paused = h.adapter.status().engine();
        for (int i = 0; i < 1300; i++) h.adapter.tick();
        assertEquals(Phase.PAUSED, h.adapter.status().engine().lifecycle().phase());
        assertEquals(paused.eligibleTicks(), h.adapter.status().engine().eligibleTicks());
        assertEquals(paused.verifyTicks(), h.adapter.status().engine().verifyTicks());
        assertEquals(paused.laneEntry(), h.adapter.status().engine().laneEntry());
        assertThrows(IllegalStateException.class, h.adapter::resume);
        h.state = new ClientState(h.state.world(), h.state.handler(), h.state.connection(), h.state.player(),
                h.state.dimension(), true, false, false);
        h.adapter.tick(); assertEquals(Phase.PAUSED, h.adapter.status().engine().lifecycle().phase());
        h.adapter.resume(); h.adapter.tick();
        assertEquals(paused.eligibleTicks() + 1, h.adapter.status().engine().eligibleTicks());
    }

    @Test void absentPlayerConsumesActiveCallbacksToRunTimeoutWithoutEffects() {
        var h = new Harness(12); h.start(); h.pose = null;
        h.state = new ClientState(h.state.world(), h.state.handler(), h.state.connection(), null,
                h.state.dimension(), true, false, false);
        for (int i = 0; i < 1200; i++) h.adapter.tick();
        assertEquals(1200, h.adapter.status().engine().eligibleTicks());
        assertEquals(Reason.RUN_TIMEOUT, h.adapter.status().engine().diagnostic().orElseThrow().reason());
        assertEquals(1200, h.captures); assertEquals(NONE, h.adapter.status().controlOwner());
        assertFalse(h.events.contains("yaw")); assertFalse(h.events.contains("interact"));
    }

    @Test void temporarilyAbsentPlayerReleasesHeldControlsWhileConsumingTime() {
        var h = new Harness(12); h.start(); h.walk();
        var present = h.state; long ticks = h.adapter.status().engine().eligibleTicks();
        h.pose = null;
        h.state = new ClientState(present.world(), present.handler(), present.connection(), null, present.dimension(), true, false, false);
        h.adapter.tick();
        assertEquals(ticks + 1, h.adapter.status().engine().eligibleTicks());
        assertEquals(Phase.RUNNING, h.adapter.status().engine().lifecycle().phase());
        assertEquals(NONE, h.adapter.status().controlOwner()); h.assertNoTranslation();
        h.state = present; h.pose = new PlayerObservation(.5, 1, 2.5, -90); h.adapter.tick();
        assertEquals(NATIVE, h.adapter.status().controlOwner());
        assertTrue(h.keys.get(ControlArbiter.Control.FORWARD));
    }

    @Test void unavailableMaterialAndUnknownEvidenceDoNotFreezeVerifyTimeout() {
        var h = new Harness(2); h.start(); h.observed = null;
        for (int i = 0; i < 84; i++) h.adapter.tick();
        assertEquals(Reason.VERIFY_TIMEOUT, h.adapter.status().engine().diagnostic().orElseThrow().reason());
        assertEquals(80, h.adapter.status().engine().verifyTicks());
        assertEquals(Outcome.FAILED, h.adapter.status().engine().lifecycle().outcome().orElseThrow());
        assertTrue(h.adapter.status().failure().isEmpty());
    }

    @Test void idleNeverClearsManualKeysOrStickyScreenRestoration() {
        var h = new Harness(12); var sticky = new ScreenKey();
        sticky.setPressed(true); sticky.screenReset(); h.sticky = sticky;
        h.keys.put(ControlArbiter.Control.FORWARD, true);
        h.adapter.tick(); h.adapter.pause(); h.adapter.stop(); h.adapter.onScreenOpened(); h.adapter.onClientStopping();
        h.adapter.worldChanged(new Object()); h.adapter.disconnected(new Object(), new Object());
        assertTrue(h.events.isEmpty()); assertTrue(h.keys.get(ControlArbiter.Control.FORWARD));
        assertTrue(sticky.shouldRestoreOnScreenClose());
    }

    @Test void ownedScreenPauseConsumesSavedRestorationEvenIfScreenClosesBetweenTicks() {
        var h = new Harness(12); var sticky = new ScreenKey(); h.sticky = sticky;
        h.start(); h.walk(); assertTrue(sticky.isPressed());
        sticky.screenReset(); assertFalse(sticky.isPressed());
        h.adapter.onScreenOpened();
        assertFalse(sticky.shouldRestoreOnScreenClose());
        h.adapter.tick(); assertEquals(Phase.PAUSED, h.adapter.status().engine().lifecycle().phase());
        assertFalse(sticky.isPressed());
    }

    @Test void validatedFreshStartClearsFailureAndOldPausedWorldCannotResume() {
        var h = new Harness(12); var old = h.start(); h.adapter.pause();
        h.state = h.withWorld(new Object());
        assertThrows(IllegalStateException.class, h.adapter::resume);
        var failure = h.adapter.status().failure(); assertTrue(failure.isPresent());
        h.state = new ClientState(h.state.world(), h.state.handler(), h.state.connection(), h.state.player(),
                h.state.dimension(), false, false, false);
        assertThrows(IllegalStateException.class, h::start); assertEquals(failure, h.adapter.status().failure());
        h.state = new ClientState(h.state.world(), h.state.handler(), h.state.connection(), h.state.player(),
                h.state.dimension(), true, false, false);
        assertNotEquals(old, h.start()); assertTrue(h.adapter.status().failure().isEmpty());
    }

    @Test void emptyPlanNeverAcquiresAndMultiLaneStartsBeforeAcquisition() {
        var h = new Harness(12);
        h.adapter.start(new BuildPlan("empty", new GridSize(1, 1, 1), List.of()), ORIGIN, Settings.defaults());
        assertEquals(Reason.NOTHING_TO_BUILD, h.adapter.status().engine().diagnostic().orElseThrow().reason());
        h.adapter.tick(); assertTrue(h.events.isEmpty());
        h.adapter.start(new BuildPlan("two lanes", new GridSize(12, 1, 10),
                List.of(new Target(7, new GridPos(0, 0, 0), STONE), new Target(8, new GridPos(0, 0, 9), STONE))), ORIGIN, Settings.defaults());
        assertEquals(Phase.RUNNING, h.adapter.status().engine().lifecycle().phase());
        assertEquals(Activity.APPROACH, h.adapter.status().engine().lifecycle().activity().orElseThrow());
        assertEquals(NONE, h.adapter.status().controlOwner()); assertTrue(h.events.isEmpty());
        h.adapter.tick();
        assertEquals(1, h.captures); assertEquals(NATIVE, h.adapter.status().controlOwner());
    }

    @Test void activeStartRefusalDoesNotAlterCurrentRunOrKeys() {
        var h = new Harness(12); var id = h.start(); h.walk(); h.events.clear();
        assertThrows(IllegalStateException.class, h::start);
        assertEquals(id, h.adapter.status().engine().lifecycle().context().orElseThrow().runId());
        assertEquals(NATIVE, h.adapter.status().controlOwner()); assertTrue(h.events.isEmpty());
    }

    @Test void unexpectedResumeObservationFailureCancelsAndRetainsTheOriginalError() {
        var h = new Harness(12); h.start(); h.adapter.pause();
        var error = new IllegalStateException("resume client read failed");
        h.nextStateRead = () -> { throw error; };
        assertSame(error, assertThrows(IllegalStateException.class, h.adapter::resume));
        assertEquals(Outcome.CANCELLED, h.adapter.status().engine().lifecycle().outcome().orElseThrow());
        assertTrue(h.adapter.status().failure().orElseThrow().trace().orElseThrow().contains(error.getMessage()));
    }

    private static final class ScreenKey extends StickyKeyBinding {
        ScreenKey() { super("key.mapart.adapter-test", 75, KeyBinding.Category.MOVEMENT, () -> true, true); }
        void screenReset() { reset(); }
    }

    private static final class Harness implements FabricBuildAdapter.Access, ControlArbiter.Sink {
        final List<String> events = new ArrayList<>();
        final List<ControlOwner> acquiredOwners = new ArrayList<>();
        final Map<String, Runnable> hooks = new HashMap<>();
        final EnumMap<ControlArbiter.Control, Boolean> keys = new EnumMap<>(ControlArbiter.Control.class);
        final ControlArbiter arbiter = new ControlArbiter(this);
        final FabricBuildAdapter adapter = new FabricBuildAdapter(this, arbiter);
        final BuildPlan plan;
        ClientState state = new ClientState(new Object(), new Object(), new Object(), new Object(),
                "minecraft:overworld", true, false, false);
        PlayerObservation pose = new PlayerObservation(.5, 1, 2.5, -90);
        PlacementAvailability ready = NO_USABLE_ITEM;
        BlockSpec observed = AIR;
        int captures;
        int slot;
        float expectedYaw = -90;
        boolean acquireAvailable = true;
        Runnable nextStateRead;
        ScreenKey sticky;
        RunLifecycle.Context run;
        PlacementIntent intent;
        ControlArbiter.Binding placementBinding;
        BooleanSupplier lastGuard;
        Harness(int width) {
            this(new BuildPlan("adapter", new GridSize(width, 1, 5), List.of(new Target(7, new GridPos(0, 0, 0), STONE))));
        }
        Harness(BuildPlan plan) {
            this.plan = plan;
            for (var control : ControlArbiter.Control.values()) keys.put(control, false);
        }
        UUID start() { return adapter.start(plan, ORIGIN, Settings.defaults()); }
        void walk() { for (int i = 0; i < 4; i++) adapter.tick(); }
        void sample(double x, double z, float yaw) {
            pose = new PlayerObservation(x, 1, z, yaw); expectedYaw = yaw;
            long ticks = adapter.status().engine().eligibleTicks(); int before = captures;
            adapter.tick();
            assertEquals(before + 1, captures);
            if (adapter.status().engine().lifecycle().context().orElseThrow().runId().equals(run.runId())) {
                assertEquals(ticks + 1, adapter.status().engine().eligibleTicks());
            }
            assertNotEquals(TRAVEL, adapter.status().controlOwner());
        }
        void fire(String event) { events.add(event); var hook = hooks.remove(event); if (hook != null) hook.run(); }
        ClientState withWorld(Object world) { return new ClientState(world, state.handler(), state.connection(), state.player(), state.dimension(), true, false, false); }
        void assertNoTranslation() {
            for (var c : List.of(ControlArbiter.Control.FORWARD, ControlArbiter.Control.BACK, ControlArbiter.Control.LEFT, ControlArbiter.Control.RIGHT)) assertFalse(keys.get(c), c.name());
        }
        void assertAllReleased() {
            for (var control : ControlArbiter.Control.values()) assertFalse(keys.get(control), control.name());
        }
        @Override public void requireClientThread() { }
        @Override public ClientState state() {
            var read = nextStateRead; nextStateRead = null;
            if (read != null) read.run();
            return state;
        }
        @Override public Facts capture(BuildPlan plan, RunLifecycle.Context run, Settings settings, ClientState bound, long tick) {
            this.run = run; captures++; fire("capture");
            var evidence = observed == null ? Evidence.UNKNOWN : Evidence.OBSERVED;
            var samples = new HashMap<GridPos, SupportSample>();
            var observations = new ArrayList<PlacementObservation>();
            var available = new HashMap<Integer, PlacementAvailability>();
            for (var target : plan.targets()) {
                samples.put(target.relativePos(), new SupportSample(evidence, Optional.ofNullable(observed)));
                observations.add(new PlacementObservation(run.runId(), target.id(), OptionalLong.empty(), tick, evidence, Optional.ofNullable(observed)));
                available.put(target.id(), ready);
            }
            return new Facts(Optional.ofNullable(pose), new SupportObservation(samples), observations, available);
        }
        @Override public void alignYaw(ClientState bound, float yaw) { assertSame(state.player(), bound.player()); assertEquals(expectedYaw, yaw); fire("yaw"); }
        @Override public FabricPlacementEffects.Result place(BuildPlan plan, RunLifecycle.Context run, Settings settings,
                PlacementIntent intent, ControlArbiter arbiter, ControlArbiter.Binding binding, BooleanSupplier currentAction) {
            this.intent = intent; placementBinding = binding; lastGuard = currentAction;
            return FabricPlacementEffects.apply(placement, plan, run, settings, intent, arbiter, binding, currentAction);
        }
        final FabricPlacementEffects.Access placement = new FabricPlacementEffects.Access() {
            @Override public boolean isCurrent() { return true; }
            @Override public FabricPlacementEffects.Prepared inspect(Target target, GridPos origin, int reservedSlots) {
                fire("inspect");
                return new FabricPlacementEffects.Prepared(READY, Optional.of(new FabricPlacementEffects.Click(1,
                        FabricPlacementEffects.supportHit(new BlockPos(-17, 63, -11), Direction.UP, new BlockPos(-17, 64, -11)))), "ready");
            }
            @Override public int selectedSlot() { return slot; }
            @Override public void selectSlot(int selected) { slot = selected; fire("slot"); }
            @Override public ActionResult interactBlock(BlockHitResult hit) { fire("interact"); return ActionResult.SUCCESS; }
        };
        @Override public boolean acquire(ControlOwner owner) { acquiredOwners.add(owner); fire("acquire"); return acquireAvailable && owner == NATIVE; }
        @Override public void setPressed(ControlArbiter.Control control, boolean pressed) {
            fire("key:" + control + ":" + pressed); keys.put(control, pressed);
            if (sticky != null && control == ControlArbiter.Control.SPRINT) ControlArbiter.MinecraftSink.writeKey(sticky, pressed);
        }
        @Override public void release(ControlOwner owner) { fire("release"); }
    }
}
