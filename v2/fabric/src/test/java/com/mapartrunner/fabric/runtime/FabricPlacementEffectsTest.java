package com.mapartrunner.fabric.runtime;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.core.run.BuildEngine.*;
import com.mapartrunner.core.run.RunLifecycle;
import com.mapartrunner.fabric.runtime.FabricPlacementEffects.*;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.StairShape;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BlockStateComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

import static com.mapartrunner.core.run.BuildEngine.ControlOwner.*;
import static com.mapartrunner.core.run.BuildEngine.EffectStatus.*;
import static com.mapartrunner.core.run.BuildEngine.PlacementAvailability.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricPlacementEffectsTest {
    private static final BlockSpec STONE = new BlockSpec("minecraft:stone", Map.of());
    private static final GridPos ORIGIN = new GridPos(-17, 64, -11);
    private final UUID runId = UUID.randomUUID();
    private final Settings settings = new Settings(2, 5, true, 1);
    private final Target target = new Target(91, new GridPos(2, 0, 2), STONE);
    private final BuildPlan plan = new BuildPlan("placement", new GridSize(5, 1, 5), List.of(target));
    private final RunLifecycle.Context run = new RunLifecycle.Context(runId, "connection", "world", "minecraft:overworld",
            plan.fingerprint(), ORIGIN, settings.fingerprint());
    private final PlacementIntent intent = new PlacementIntent(runId, 12, 91, 100);
    private final ControlArbiter arbiter = new ControlArbiter(new ControlArbiter.Sink() {
        @Override public boolean acquire(ControlOwner owner) { return true; }
        @Override public void setPressed(ControlArbiter.Control control, boolean pressed) { }
        @Override public void release(ControlOwner owner) { }
    });
    private final ControlArbiter.Binding binding = arbiter.bindRun(runId);
    private final RecordingAccess access = new RecordingAccess();

    @BeforeAll
    static void bootstrapMappedRegistries() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @ParameterizedTest
    @CsvSource({"-2,-13", "0,-11", "2,-9"})
    void centerAndTwoBlockOffsetsClickTheirOwnSupport(int offset, int expectedZ) {
        var targetPos = new BlockPos(13, 64, -11 + offset);
        var hit = FabricPlacementEffects.supportHit(targetPos.down(), Direction.UP, targetPos);
        assertEquals(new BlockPos(13, 63, expectedZ), hit.getBlockPos());
        assertEquals(Direction.UP, hit.getSide());
        assertEquals(new Vec3d(13.5, 64, expectedZ + 0.5), hit.getPos());
        assertEquals(targetPos, hit.getBlockPos().offset(hit.getSide()));
        assertFalse(hit.isInsideBlock());
    }

    @ParameterizedTest
    @EnumSource(Direction.class)
    void everySupportSideUsesTheClickedSupportFace(Direction side) {
        var support = new BlockPos(-17, 63, -9);
        var hit = FabricPlacementEffects.supportHit(support, side, support.offset(side));
        assertEquals(support, hit.getBlockPos());
        assertEquals(side, hit.getSide());
        assertEquals(-16.5 + side.getOffsetX() * 0.5, hit.getPos().x);
        assertEquals(63.5 + side.getOffsetY() * 0.5, hit.getPos().y);
        assertEquals(-8.5 + side.getOffsetZ() * 0.5, hit.getPos().z);
        assertThrows(IllegalArgumentException.class,
                () -> FabricPlacementEffects.supportHit(support, side, support.offset(side).up(2)));
    }

    @Test
    void clickDefensivelyFreezesMutableSupportPosition() {
        var mutable = new BlockPos.Mutable(-1, 63, -1);
        var click = new Click(1, new BlockHitResult(new Vec3d(-0.5, 64, -0.5), Direction.UP, mutable, false));
        mutable.set(99, 99, 99);
        assertEquals(new BlockPos(-1, 63, -1), click.hit().getBlockPos());
    }

    @Test
    void resolvesExactNativeStatesIncludingAllNondefaultProperties() {
        assertEquals(Blocks.STONE.getDefaultState(), FabricPlacementEffects.expectedState(STONE).orElseThrow());
        var expected = new BlockSpec("minecraft:oak_stairs", Map.of(
                "facing", "east", "half", "top", "shape", "inner_left", "waterlogged", "true"));
        var actual = FabricPlacementEffects.expectedState(expected).orElseThrow();
        assertEquals(Direction.EAST, actual.get(StairsBlock.FACING));
        assertEquals(BlockHalf.TOP, actual.get(StairsBlock.HALF));
        assertEquals(StairShape.INNER_LEFT, actual.get(StairsBlock.SHAPE));
        assertTrue(actual.get(StairsBlock.WATERLOGGED));
        assertEquals(expected, WorldEvidenceReader.blockSpec(actual));
    }

    static Stream<BlockSpec> unexpressibleSpecs() {
        return Stream.of(new BlockSpec("minecraft:missing_block", Map.of()),
                new BlockSpec("minecraft:stone", Map.of("facing", "north")),
                new BlockSpec("minecraft:oak_log", Map.of("axis", "diagonal")),
                new BlockSpec("minecraft:oak_log", Map.of()),
                new BlockSpec("minecraft:oak_stairs", Map.of("facing", "east")));
    }

    @ParameterizedTest
    @MethodSource("unexpressibleSpecs")
    void neverSubstitutesDefaultStateForUnknownOrIncompleteProperties(BlockSpec expected) {
        assertTrue(FabricPlacementEffects.expectedState(expected).isEmpty());
    }

    @Test
    void stackStateComponentChangesTheProductionPreviewWithoutMutatingStackOrNativeState() {
        var stack = new ItemStack(Items.OAK_LOG, 8);
        var nativeState = Blocks.OAK_LOG.getDefaultState();
        var expected = new BlockSpec("minecraft:oak_log", Map.of("axis", "x"));
        assertNotEquals(expected, WorldEvidenceReader.blockSpec(FabricPlacementEffects.itemState(stack, nativeState)));
        var component = new BlockStateComponent(Map.of("axis", "x"));
        stack.set(DataComponentTypes.BLOCK_STATE, component);
        assertEquals(expected, WorldEvidenceReader.blockSpec(FabricPlacementEffects.itemState(stack, nativeState)));
        assertEquals(new BlockSpec("minecraft:oak_log", Map.of("axis", "y")), WorldEvidenceReader.blockSpec(nativeState));
        assertSame(component, stack.get(DataComponentTypes.BLOCK_STATE));
        assertEquals(8, stack.getCount());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    void firstNReservationsExcludeMatchingReservedItems(int reserved) {
        var hotbar = new ArrayList<ItemStack>();
        for (int i = 0; i < 9; i++) hotbar.add(new ItemStack(Items.STONE, 8));
        int selected = FabricPlacementEffects.hotbarSlot(0, reserved, slot -> hotbar.get(slot).isOf(Items.STONE));
        assertEquals(reserved, selected);
        assertEquals(8, hotbar.getFirst().getCount());
        assertEquals(8, hotbar.get(selected).getCount());
    }

    @Test
    void stableSelectedUsableSlotWinsOverEarlierMatchingSlots() {
        assertEquals(7, FabricPlacementEffects.hotbarSlot(7, 1, slot -> true));
    }

    @Test
    void onlyReservedEmptyZeroOrWrongMaterialIsNotUsable() {
        var hotbar = List.of(new ItemStack(Items.STONE, 8), ItemStack.EMPTY, new ItemStack(Items.STONE, 0),
                new ItemStack(Items.DIRT, 8), ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
        var visited = new ArrayList<Integer>();
        assertEquals(-1, FabricPlacementEffects.hotbarSlot(0, 1, slot -> {
            visited.add(slot);
            return !hotbar.get(slot).isEmpty() && hotbar.get(slot).isOf(Items.STONE);
        }));
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), visited);
        assertEquals(8, hotbar.getFirst().getCount());
    }

    @Test
    void wrongStateInAnEarlierSlotDoesNotHideLaterUsableNativeStack() {
        var wrong = new ItemStack(Items.OAK_LOG, 8);
        var usable = new ItemStack(Items.OAK_LOG, 4);
        usable.set(DataComponentTypes.BLOCK_STATE, new BlockStateComponent(Map.of("axis", "x")));
        var hotbar = List.of(new ItemStack(Items.STONE, 8), wrong, usable,
                ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
        var expected = new BlockSpec("minecraft:oak_log", Map.of("axis", "x"));
        assertEquals(2, FabricPlacementEffects.hotbarSlot(1, 1, slot -> {
            var stack = hotbar.get(slot);
            return stack.isOf(Items.OAK_LOG) && expected.equals(WorldEvidenceReader.blockSpec(
                    FabricPlacementEffects.itemState(stack, Blocks.OAK_LOG.getDefaultState())));
        }));
        assertEquals(8, wrong.getCount());
        assertEquals(4, usable.getCount());
    }

    @Test
    void availabilityRetainsEverySparseTargetAndCannotSelectOrInteract() {
        var second = new Target(7, new GridPos(4, 0, 4), STONE);
        var sparsePlan = new BuildPlan(plan.fingerprint(), plan.dimensions(), List.of(target, second));
        access.second = unavailable(NO_USABLE_ITEM, "no unreserved material");
        var snapshot = FabricPlacementEffects.availability(access, sparsePlan, run, settings);
        assertEquals(Map.of(91, READY, 7, NO_USABLE_ITEM), snapshot);
        assertEquals(List.of(target, second), access.inspectedTargets);
        assertEquals(ORIGIN, access.origin);
        assertEquals(1, access.reserved);
        assertEquals(List.of("inspect", "inspect"), access.events);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put(91, UNSUPPORTED));
    }

    @Test
    void unavailableWorldAndMismatchedContextStillAccountForAllTargetsWithoutWorldReads() {
        access.current = false;
        assertEquals(Map.of(91, NO_VALID_HIT), FabricPlacementEffects.availability(access, plan, run, settings));
        assertTrue(access.events.isEmpty());
        access.current = true;
        var otherPlan = new BuildPlan("another-plan", plan.dimensions(), plan.targets());
        assertEquals(Map.of(91, NO_VALID_HIT), FabricPlacementEffects.availability(access, otherPlan, run, settings));
        assertTrue(access.events.isEmpty());
    }

    @Test
    void emptyAvailabilityPlanDoesNotInventTargetsOrReadWorld() {
        var empty = new BuildPlan(plan.fingerprint(), plan.dimensions(), List.of());
        assertTrue(FabricPlacementEffects.availability(access, empty, run, settings).isEmpty());
        assertTrue(access.events.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = PlacementAvailability.class, names = {"READY"}, mode = EnumSource.Mode.EXCLUDE)
    void unavailablePreflightCannotSelectOrInteract(PlacementAvailability availability) {
        access.first = unavailable(availability, "actual " + availability);
        var result = apply(() -> true);
        assertResult(result, UNAVAILABLE);
        assertTrue(result.detail().contains(availability.name()));
        assertEquals(List.of("inspect"), access.events);
    }

    @ParameterizedTest
    @EnumSource(value = ControlOwner.class, names = {"NATIVE", "NONE"})
    void validNativeAndVerifyNoneActionsSelectRecheckAndInteractOnce(ControlOwner owner) {
        if (owner == NATIVE) arbiter.acquire(binding, NATIVE);
        var result = apply(() -> true);
        assertResult(result, ACCEPTED);
        assertEquals(List.of("inspect", "selected", "select:1", "inspect", "selected", "interact"), access.events);
        assertEquals(target, access.inspectedTarget);
        assertEquals(ORIGIN, access.origin);
        assertEquals(1, access.reserved);
        assertEquals(new BlockPos(-15, 63, -9), access.interaction.getBlockPos());
        assertEquals(Direction.UP, access.interaction.getSide());
        assertEquals(new Vec3d(-14.5, 64, -8.5), access.interaction.getPos());
        assertFalse(result.detail().isBlank());
    }

    @Test
    void noSetterIsIssuedWhenTheUsableSlotIsAlreadySelected() {
        access.selected = 1;
        assertResult(apply(() -> true), ACCEPTED);
        assertEquals(List.of("inspect", "selected", "inspect", "selected", "interact"), access.events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PASS", "FAIL"})
    void nativeRejectionIsCorrelatedAndNeverCalledConfirmation(String nativeResult) {
        access.result = nativeResult.equals("PASS") ? ActionResult.PASS : ActionResult.FAIL;
        assertResult(apply(() -> true), REJECTED);
        assertEquals(1, access.events.stream().filter("interact"::equals).count());
    }

    @Test
    void unknownTargetIsRejectedWithoutRetargeting() {
        var unknown = new PlacementIntent(runId, 12, 7, 100);
        var result = FabricPlacementEffects.apply(access, plan, run, settings, unknown, arbiter, binding, () -> true);
        assertEquals(UNAVAILABLE, result.effect().status());
        assertEquals(7, result.effect().targetId());
        assertTrue(result.detail().contains("target"));
        assertTrue(access.events.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"action", "world", "foreign_binding", "old_same_run", "run", "plan", "settings", "travel"})
    void staleRunBindingActionOrControlCannotReachEvenTheSlotSetter(String invalid) {
        ControlArbiter.Binding captured = binding;
        RunLifecycle.Context context = run;
        BooleanSupplier current = () -> true;
        switch (invalid) {
            case "action" -> current = () -> false;
            case "world" -> access.current = false;
            case "foreign_binding" -> captured = new ControlArbiter(new NoopSink()).bindRun(runId);
            case "old_same_run" -> { arbiter.invalidate(); arbiter.bindRun(runId); }
            case "run" -> context = new RunLifecycle.Context(UUID.randomUUID(), "connection", "world", run.dimension(),
                    plan.fingerprint(), ORIGIN, settings.fingerprint());
            case "plan" -> context = new RunLifecycle.Context(runId, "connection", "world", run.dimension(),
                    "different", ORIGIN, settings.fingerprint());
            case "settings" -> context = new RunLifecycle.Context(runId, "connection", "world", run.dimension(),
                    plan.fingerprint(), ORIGIN, "different");
            case "travel" -> arbiter.acquire(binding, TRAVEL);
            default -> fail("Unknown case");
        }
        assertResult(FabricPlacementEffects.apply(access, plan, context, settings, intent, arbiter, captured, current), STALE);
        assertTrue(access.events.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void invalidationDuringEitherInspectionPreventsEveryLaterEffect(int inspection) {
        access.onInspect = count -> { if (count == inspection) arbiter.invalidate(); };
        assertResult(apply(() -> true), STALE);
        assertFalse(access.events.contains("interact"));
        assertEquals(inspection == 1 ? List.of("inspect")
                : List.of("inspect", "selected", "select:1", "inspect"), access.events);
    }

    @Test
    void invalidationAfterSlotChangeDoesNotInteractOrRestoreOldSelection() {
        access.onSelect = arbiter::invalidate;
        assertResult(apply(() -> true), STALE);
        assertEquals(1, access.selected);
        assertEquals(List.of("inspect", "selected", "select:1"), access.events);
    }

    @Test
    void adapterActionGuardIsRecheckedAfterSlotChange() {
        var current = new AtomicBoolean(true);
        access.onSelect = () -> current.set(false);
        assertResult(apply(current::get), STALE);
        assertEquals(List.of("inspect", "selected", "select:1"), access.events);
    }

    @ParameterizedTest
    @EnumSource(value = PlacementAvailability.class, names = {"READY"}, mode = EnumSource.Mode.EXCLUDE)
    void volatileMaterialSupportReachOrFullStateMustPassTheSecondPreflight(PlacementAvailability unavailable) {
        access.second = unavailable(unavailable, "changed " + unavailable);
        var result = apply(() -> true);
        assertResult(result, UNAVAILABLE);
        assertTrue(result.detail().contains("changed"));
        assertEquals(List.of("inspect", "selected", "select:1", "inspect"), access.events);
    }

    @Test
    void aDifferentUsableSlotAfterSelectionDoesNotCauseAnotherSelectionOrInteraction() {
        access.second = ready(2);
        assertResult(apply(() -> true), UNAVAILABLE);
        assertEquals(List.of("inspect", "selected", "select:1", "inspect"), access.events);
    }

    @Test
    void externalSelectionChangeAfterRecheckCannotUseThePreparedClick() {
        access.onInspect = count -> { if (count == 2) access.selected = 2; };
        assertResult(apply(() -> true), UNAVAILABLE);
        assertFalse(access.events.contains("interact"));
        assertEquals(2, access.selected);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void guardsAfterSelectedSlotReadsDefeatLastMomentInvalidation(int read) {
        access.onSelectedRead = count -> { if (count == read) arbiter.invalidate(); };
        assertResult(apply(() -> true), STALE);
        assertFalse(access.events.contains("interact"));
    }

    @Test
    void consumedActionCannotBeAppliedTwiceEvenWithTheSameCurrentBinding() {
        var current = new AtomicBoolean(true);
        assertResult(apply(current::get), ACCEPTED);
        current.set(false);
        access.events.clear();
        assertResult(apply(current::get), STALE);
        assertTrue(access.events.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"inspect1", "select", "inspect2", "interact"})
    void unexpectedFailuresPropagateUnchangedWithoutLaterEffects(String stage) {
        var failure = new IllegalStateException(stage);
        access.onInspect = count -> { if (stage.equals("inspect" + count)) throw failure; };
        access.onSelect = () -> { if (stage.equals("select")) throw failure; };
        access.onInteract = () -> { if (stage.equals("interact")) throw failure; };
        assertSame(failure, assertThrows(IllegalStateException.class, () -> apply(() -> true)));
        assertEquals(stage.equals("interact"), access.events.contains("interact"));
        assertTrue(access.events.stream().filter(event -> event.startsWith("select:")).count() <= 1);
        assertEquals(stage.equals("inspect1") ? 0 : 1, access.selected);
    }

    @Test
    void validatesPreparedInvariantAndReservationBounds() {
        assertThrows(IllegalArgumentException.class, () -> new Prepared(READY, Optional.empty(), "missing click"));
        assertThrows(IllegalArgumentException.class, () -> new Prepared(NO_VALID_HIT, ready(1).click(), "unexpected click"));
        assertThrows(IllegalArgumentException.class, () -> new Click(-1, ready(1).click().orElseThrow().hit()));
        assertThrows(IllegalArgumentException.class, () -> new Click(9, ready(1).click().orElseThrow().hit()));
        assertThrows(IllegalArgumentException.class, () -> FabricPlacementEffects.hotbarSlot(0, -1, slot -> true));
        assertThrows(IllegalArgumentException.class, () -> FabricPlacementEffects.hotbarSlot(0, 9, slot -> true));
    }

    private Result apply(BooleanSupplier current) {
        return FabricPlacementEffects.apply(access, plan, run, settings, intent, arbiter, binding, current);
    }

    private void assertResult(Result result, EffectStatus status) {
        assertEquals(new PlacementEffectResult(runId, 12, 91, 100, status), result.effect());
        assertFalse(result.detail().isBlank());
    }

    private static Prepared ready(int slot) {
        return new Prepared(READY, Optional.of(new Click(slot,
                new BlockHitResult(new Vec3d(-14.5, 64, -8.5), Direction.UP, new BlockPos(-15, 63, -9), false))), "ready");
    }

    private static Prepared unavailable(PlacementAvailability availability, String detail) {
        return new Prepared(availability, Optional.empty(), detail);
    }

    private static final class RecordingAccess implements Access {
        final List<String> events = new ArrayList<>();
        final List<Target> inspectedTargets = new ArrayList<>();
        boolean current = true;
        int selected;
        int inspections;
        int selectedReads;
        Prepared first = ready(1);
        Prepared second = ready(1);
        ActionResult result = ActionResult.SUCCESS;
        IntConsumer onInspect = count -> { };
        IntConsumer onSelectedRead = count -> { };
        Runnable onSelect = () -> { };
        Runnable onInteract = () -> { };
        Target inspectedTarget;
        GridPos origin;
        int reserved;
        BlockHitResult interaction;

        @Override public boolean isCurrent() { return current; }
        @Override public Prepared inspect(Target target, GridPos origin, int reservedSlots) {
            events.add("inspect");
            inspectedTarget = target;
            inspectedTargets.add(target);
            this.origin = origin;
            reserved = reservedSlots;
            onInspect.accept(++inspections);
            return inspections == 1 ? first : second;
        }
        @Override public int selectedSlot() {
            events.add("selected");
            onSelectedRead.accept(++selectedReads);
            return selected;
        }
        @Override public void selectSlot(int slot) {
            events.add("select:" + slot);
            selected = slot;
            onSelect.run();
        }
        @Override public ActionResult interactBlock(BlockHitResult hit) {
            events.add("interact");
            interaction = hit;
            onInteract.run();
            return result;
        }
    }

    private static final class NoopSink implements ControlArbiter.Sink {
        @Override public boolean acquire(ControlOwner owner) { return true; }
        @Override public void setPressed(ControlArbiter.Control control, boolean pressed) { }
        @Override public void release(ControlOwner owner) { }
    }
}
