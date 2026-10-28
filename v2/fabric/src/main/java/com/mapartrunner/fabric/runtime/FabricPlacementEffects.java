package com.mapartrunner.fabric.runtime;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.core.run.BuildEngine.*;
import com.mapartrunner.core.run.RunLifecycle;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BlockStateComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;

import static com.mapartrunner.core.run.BuildEngine.EffectStatus.*;
import static com.mapartrunner.core.run.BuildEngine.PlacementAvailability.*;

/** Stateless client-thread effects; the adapter owns action consumption, feedback and failure cleanup. */
public final class FabricPlacementEffects {
    private static final Direction[] SUPPORT_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP
    };

    private FabricPlacementEffects() { }

    public static Map<Integer, PlacementAvailability> availability(MinecraftClient client, BuildPlan plan,
                                                                  RunLifecycle.Context run, Settings settings) {
        validate(plan, run, settings);
        return availability(new MinecraftAccess(client, run.dimension()), plan, run, settings);
    }

    static Map<Integer, PlacementAvailability> availability(Access access, BuildPlan plan,
                                                           RunLifecycle.Context run, Settings settings) {
        validate(plan, run, settings);
        Objects.requireNonNull(access, "access");
        var result = new LinkedHashMap<Integer, PlacementAvailability>();
        for (var target : plan.targets()) {
            result.put(target.id(), contextMatches(plan, run, settings) && access.isCurrent()
                    ? access.inspect(target, run.origin(), settings.reservedHotbarSlots()).availability() : NO_VALID_HIT);
        }
        return Collections.unmodifiableMap(result);
    }

    /** Capture Binding before snapshot/core work; currentAction must validate the adapter's exact unapplied decision. */
    public static Result apply(MinecraftClient client, BuildPlan plan, RunLifecycle.Context run, Settings settings,
                               PlacementIntent intent, ControlArbiter arbiter, ControlArbiter.Binding capturedBinding,
                               BooleanSupplier currentAction) {
        Objects.requireNonNull(run, "run");
        return apply(new MinecraftAccess(client, run.dimension()), plan, run, settings, intent,
                arbiter, capturedBinding, currentAction);
    }

    static Result apply(Access access, BuildPlan plan, RunLifecycle.Context run, Settings settings,
                        PlacementIntent intent, ControlArbiter arbiter, ControlArbiter.Binding capturedBinding,
                        BooleanSupplier currentAction) {
        validate(plan, run, settings);
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(arbiter, "arbiter");
        Objects.requireNonNull(capturedBinding, "capturedBinding");
        Objects.requireNonNull(currentAction, "currentAction");
        BooleanSupplier current = () -> arbiter.isCurrent(capturedBinding)
                && capturedBinding.runId().equals(intent.runId()) && run.runId().equals(intent.runId())
                && contextMatches(plan, run, settings) && arbiter.owner() != ControlOwner.TRAVEL
                && access.isCurrent() && currentAction.getAsBoolean();
        if (!current.getAsBoolean()) return result(intent, STALE, "Stale run, binding, control or action");
        var target = plan.targets().stream().filter(candidate -> candidate.id() == intent.targetId()).findFirst();
        if (target.isEmpty()) return result(intent, UNAVAILABLE, "Unknown target ID " + intent.targetId());

        var prepared = access.inspect(target.orElseThrow(), run.origin(), settings.reservedHotbarSlots());
        if (!current.getAsBoolean()) return result(intent, STALE, "Invalidated during placement preflight");
        if (prepared.availability() != READY) return result(intent, UNAVAILABLE, prepared.detail());
        int slot = prepared.click().orElseThrow().slot();
        if (access.selectedSlot() != slot) {
            if (!current.getAsBoolean()) return result(intent, STALE, "Invalidated before slot selection");
            access.selectSlot(slot);
        }

        // Never restore a slot after invalidation: that restoration would be another stale effect.
        if (!current.getAsBoolean()) return result(intent, STALE, "Invalidated after slot selection");
        var fresh = access.inspect(target.orElseThrow(), run.origin(), settings.reservedHotbarSlots());
        if (!current.getAsBoolean()) return result(intent, STALE, "Invalidated during placement recheck");
        if (fresh.availability() != READY) return result(intent, UNAVAILABLE, fresh.detail());
        var click = fresh.click().orElseThrow();
        if (click.slot() != slot || access.selectedSlot() != slot) {
            return result(intent, UNAVAILABLE, "Usable selected slot changed during placement recheck");
        }
        if (!current.getAsBoolean()) return result(intent, STALE, "Invalidated before interaction");
        var nativeResult = access.interactBlock(click.hit());
        return result(intent, nativeResult.isAccepted() ? ACCEPTED : REJECTED, "Native interaction: " + nativeResult);
    }

    public record Result(PlacementEffectResult effect, String detail) {
        public Result {
            Objects.requireNonNull(effect, "effect");
            requireDetail(detail);
        }
    }

    interface Access {
        boolean isCurrent();
        Prepared inspect(Target target, GridPos origin, int reservedSlots);
        int selectedSlot();
        void selectSlot(int slot);
        ActionResult interactBlock(BlockHitResult hit);
    }

    record Click(int slot, BlockHitResult hit) {
        Click {
            if (slot < 0 || slot >= PlayerInventory.getHotbarSize()) throw new IllegalArgumentException("Not a hotbar slot");
            Objects.requireNonNull(hit, "hit");
            hit = new BlockHitResult(hit.getPos(), hit.getSide(), hit.getBlockPos().toImmutable(), hit.isInsideBlock());
        }
    }

    record Prepared(PlacementAvailability availability, Optional<Click> click, String detail) {
        Prepared {
            Objects.requireNonNull(availability, "availability");
            Objects.requireNonNull(click, "click");
            requireDetail(detail);
            if ((availability == READY) != click.isPresent()) throw new IllegalArgumentException("Only READY has a click");
        }
    }

    static BlockHitResult supportHit(BlockPos support, Direction side, BlockPos target) {
        Objects.requireNonNull(support, "support");
        Objects.requireNonNull(side, "side");
        if (!support.offset(side).equals(Objects.requireNonNull(target, "target"))) {
            throw new IllegalArgumentException("Clicked support face must lead to target");
        }
        return new BlockHitResult(Vec3d.ofCenter(support).add(side.getOffsetX() * 0.5,
                side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5), side, support.toImmutable(), false);
    }

    static Optional<BlockState> expectedState(BlockSpec expected) {
        Objects.requireNonNull(expected, "expected");
        var block = Registries.BLOCK.getOptionalValue(Identifier.of(expected.id()));
        if (block.isEmpty()) return Optional.empty();
        var state = block.orElseThrow().getDefaultState();
        for (var entry : expected.properties().entrySet()) {
            var property = block.orElseThrow().getStateManager().getProperty(entry.getKey());
            if (property == null) return Optional.empty();
            var parsed = parseProperty(state, property, entry.getValue());
            if (parsed.isEmpty()) return Optional.empty();
            state = parsed.orElseThrow();
        }
        return WorldEvidenceReader.blockSpec(state).equals(expected) ? Optional.of(state) : Optional.empty();
    }

    private static <T extends Comparable<T>> Optional<BlockState> parseProperty(BlockState state, Property<T> property, String value) {
        return property.parse(value).map(parsed -> state.with(property, parsed));
    }

    static BlockState itemState(ItemStack stack, BlockState nativeState) {
        return stack.getOrDefault(DataComponentTypes.BLOCK_STATE, BlockStateComponent.DEFAULT).applyToState(nativeState);
    }

    static int hotbarSlot(int selected, int reservedSlots, IntPredicate usableSlot) {
        Objects.requireNonNull(usableSlot, "usableSlot");
        if (reservedSlots < 0 || reservedSlots > 8) throw new IllegalArgumentException("Reserved slots must be in 0..8");
        int size = PlayerInventory.getHotbarSize();
        if (selected >= reservedSlots && selected < size && usableSlot.test(selected)) return selected;
        for (int slot = reservedSlots; slot < size; slot++) {
            if (slot != selected && usableSlot.test(slot)) return slot;
        }
        return -1;
    }

    private static Result result(PlacementIntent intent, EffectStatus status, String detail) {
        return new Result(new PlacementEffectResult(intent.runId(), intent.actionId(), intent.targetId(), intent.tick(), status), detail);
    }

    private static Prepared unavailable(PlacementAvailability availability, String detail) {
        return new Prepared(availability, Optional.empty(), availability + ": " + detail);
    }

    private static void validate(BuildPlan plan, RunLifecycle.Context run, Settings settings) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(settings, "settings");
    }

    private static boolean contextMatches(BuildPlan plan, RunLifecycle.Context run, Settings settings) {
        return plan.fingerprint().equals(run.planFingerprint()) && settings.fingerprint().equals(run.settingsFingerprint());
    }

    private static void requireDetail(String detail) {
        if (Objects.requireNonNull(detail, "detail").isBlank()) throw new IllegalArgumentException("Detail must not be blank");
    }

    /** References live only for this synchronous call; no world/player or prepared click survives to another tick. */
    private static final class MinecraftAccess implements Access {
        private final MinecraftClient client;
        private final ClientWorld world;
        private final ClientPlayerEntity player;
        private final ClientPlayNetworkHandler handler;
        private final ClientPlayerInteractionManager manager;
        private final String dimension;

        private MinecraftAccess(MinecraftClient client, String dimension) {
            this.client = Objects.requireNonNull(client, "client");
            if (!client.isOnThread()) throw new IllegalStateException("Placement effects require the client thread");
            world = client.world;
            player = client.player;
            handler = client.getNetworkHandler();
            manager = client.interactionManager;
            this.dimension = dimension;
        }

        @Override public boolean isCurrent() {
            return world != null && player != null && handler != null && manager != null
                    && client.world == world && client.player == player && client.getNetworkHandler() == handler
                    && client.interactionManager == manager && player.getEntityWorld() == world
                    && handler.getWorld() == world && handler.getConnection().isOpen()
                    && world.getRegistryKey().getValue().toString().equals(dimension);
        }

        @Override public int selectedSlot() { return player.getInventory().getSelectedSlot(); }
        @Override public void selectSlot(int slot) { player.getInventory().setSelectedSlot(slot); }
        @Override public ActionResult interactBlock(BlockHitResult hit) { return manager.interactBlock(player, Hand.MAIN_HAND, hit); }

        @Override public Prepared inspect(Target target, GridPos origin, int reservedSlots) {
            var expected = expectedState(target.block());
            if (expected.isEmpty()) return unavailable(UNSUPPORTED, "Unknown or incomplete expected block state");
            if (!isCurrent() || !player.isAlive() || player.isSpectator() || player.isUsingItem()
                    || manager.isBreakingBlock() || client.currentScreen != null) {
                return unavailable(NO_VALID_HIT, "Client player or interaction is unavailable");
            }
            var state = expected.orElseThrow();
            var item = state.getBlock().asItem();
            // shortcut: specialized BlockItem previews remain pending parity work before v1 replacement.
            if (item == Items.AIR || item.getClass() != BlockItem.class || !item.isEnabled(world.getEnabledFeatures())
                    || !state.getBlock().isEnabled(world.getEnabledFeatures())) {
                return unavailable(UNSUPPORTED, "Expected state requires an unsupported or disabled item route");
            }
            var relative = target.relativePos();
            var position = new BlockPos(Math.addExact(origin.x(), relative.x()), Math.addExact(origin.y(), relative.y()),
                    Math.addExact(origin.z(), relative.z()));
            if (loadedState(position) == null || !world.isInBuildLimit(position) || !world.getWorldBorder().contains(position)) {
                return unavailable(NO_VALID_HIT, "Target is unloaded or outside world limits");
            }
            var prepared = new Prepared[PlayerInventory.getHotbarSize()];
            int selected = hotbarSlot(selectedSlot(), reservedSlots, slot -> {
                var stack = player.getInventory().getStack(slot);
                if (stack.isEmpty() || !stack.isOf(item) || player.getItemCooldownManager().isCoolingDown(stack)) return false;
                prepared[slot] = inspectSlot(target.block(), position, slot, stack, (BlockItem) item);
                return prepared[slot].availability() == READY;
            });
            if (selected >= 0) return prepared[selected];
            Prepared failure = unavailable(NO_USABLE_ITEM, "No usable unreserved hotbar material");
            for (var candidate : prepared) {
                if (candidate == null) continue;
                if (failure.availability() == NO_USABLE_ITEM || candidate.availability() == OUT_OF_REACH
                        || (candidate.availability() == UNSUPPORTED && failure.availability() != OUT_OF_REACH)) failure = candidate;
            }
            return failure;
        }

        private Prepared inspectSlot(BlockSpec expected, BlockPos target, int slot, ItemStack stack, BlockItem item) {
            Prepared failure = unavailable(NO_VALID_HIT, "No loaded support with a valid clicked face");
            boolean wrongState = false;
            boolean outOfReach = false;
            for (var offset : SUPPORT_ORDER) {
                var support = target.offset(offset);
                var supportState = loadedState(support);
                if (supportState == null || supportState.isAir() || supportState.isReplaceable()
                        || !world.isInBuildLimit(support) || !world.getWorldBorder().contains(support)) continue;
                var hit = supportHit(support, offset.getOpposite(), target);
                var surface = supportState.getOutlineShape(world, support, ShapeContext.of(player))
                        .raycast(Vec3d.ofCenter(target), Vec3d.ofCenter(support), support);
                if (surface == null || !surface.getBlockPos().equals(support) || surface.getSide() != hit.getSide()
                        || !surface.getPos().equals(hit.getPos())) continue;
                var context = new ItemPlacementContext(player, Hand.MAIN_HAND, stack, hit);
                context = item.getPlacementContext(context);
                if (context == null || !context.getBlockPos().equals(target) || !context.canPlace()
                        || !player.canPlaceOn(target, hit.getSide(), stack)) {
                    failure = unavailable(NO_VALID_HIT, "Target cannot be replaced or placement permission is denied");
                    continue;
                }
                var nativeState = item.getBlock().getPlacementState(context);
                if (nativeState == null || !legal(nativeState, target)) {
                    failure = unavailable(NO_VALID_HIT, "Native placement state is illegal or collides with an entity");
                    continue;
                }
                var produced = itemState(stack, nativeState);
                if (!legal(produced, target)) {
                    failure = unavailable(NO_VALID_HIT, "Component placement state is illegal or collides with an entity");
                    continue;
                }
                if (!WorldEvidenceReader.blockSpec(produced).equals(expected)) { wrongState = true; continue; }
                double reach = player.getBlockInteractionRange();
                if (!Double.isFinite(reach) || reach <= 0 || !player.canInteractWithBlockAt(support, 0)
                        || player.getEyePos().squaredDistanceTo(hit.getPos()) > reach * reach) {
                    outOfReach = true;
                    continue;
                }
                return new Prepared(READY, Optional.of(new Click(slot, hit)), "Native full-state placement is ready");
            }
            if (outOfReach) return unavailable(OUT_OF_REACH, "Clicked face exceeds the player's actual block interaction range");
            if (wrongState) return unavailable(UNSUPPORTED, "Actual item, clicked face and player orientation cannot produce the full expected state");
            return failure;
        }

        private boolean legal(BlockState state, BlockPos pos) {
            return state.canPlaceAt(world, pos) && world.canPlace(state, pos, ShapeContext.ofPlacement(player));
        }

        private BlockState loadedState(BlockPos pos) {
            var chunk = world.getChunkManager().getChunk(Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16),
                    ChunkStatus.FULL, false);
            return chunk == null ? null : chunk.getBlockState(pos);
        }
    }
}
