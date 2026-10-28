package com.mapartrunner.fabric.runtime;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.core.movement.MovementPolicy.SupportObservation;
import com.mapartrunner.core.movement.MovementPolicy.SupportSample;
import com.mapartrunner.core.placement.PlacementObservation;
import com.mapartrunner.core.run.RunLifecycle;
import com.mapartrunner.fabric.mixin.ClientWorldPendingUpdateAccessor;
import com.mapartrunner.fabric.prediction.PendingPredictionView;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;

import static com.mapartrunner.core.placement.PlacementObservation.Evidence.*;

/** Stateless client-thread capture; the adapter owns binding, clocks, generation and cleanup. */
public final class WorldEvidenceReader {
    private static final SupportSample UNKNOWN_SAMPLE = new SupportSample(UNKNOWN, Optional.empty());

    private WorldEvidenceReader() { }

    /** Bound references may be null. Sample outside interaction/reconciliation stacks in production. */
    public static CapturedEvidence capture(MinecraftClient client, BuildPlan plan, RunLifecycle.Context run,
                                           ClientWorld boundWorld, ClientPlayNetworkHandler boundNetworkHandler, long tick) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(run, "run");
        if (!client.isOnThread()) throw new IllegalStateException("Evidence capture requires the client thread");
        var world = client.world;
        var handler = client.getNetworkHandler();
        boolean current = world != null && world == boundWorld && handler != null && handler == boundNetworkHandler
                && handler.getWorld() == world && handler.getConnection() == boundNetworkHandler.getConnection()
                && handler.getConnection().isOpen()
                && world.getRegistryKey().getValue().toString().equals(run.dimension())
                && plan.fingerprint().equals(run.planFingerprint());
        PendingPredictionView prediction = null;
        if (current && world instanceof ClientWorldPendingUpdateAccessor accessor) {
            Object manager = accessor.mapart$getPendingUpdateManager();
            if (manager instanceof PendingPredictionView view) prediction = view;
        }
        PendingPredictionView capturedPrediction = prediction;
        return captureTargets(run.runId(), tick, plan, run.origin(), pos -> {
            if (!current || capturedPrediction == null) return UNKNOWN_SAMPLE;
            // FULL,false returns null rather than vanilla's default EmptyChunk when unavailable.
            var chunk = world.getChunkManager().getChunk(Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16),
                    ChunkStatus.FULL, false);
            return chunk == null ? UNKNOWN_SAMPLE : sample(chunk.getBlockState(pos), capturedPrediction, pos);
        });
    }

    /** The production placement-list seam; support remains the same independently captured facts. */
    public static List<PlacementObservation> placementObservations(CapturedEvidence captured) {
        Objects.requireNonNull(captured, "captured");
        return captured.targets.stream().map(target -> {
            var sample = captured.support.cells().get(target.relativePos());
            return new PlacementObservation(captured.runId, target.id(), OptionalLong.empty(), captured.tick,
                    sample.evidence(), sample.state());
        }).toList();
    }

    static CapturedEvidence captureTargets(UUID runId, long tick, BuildPlan plan, GridPos origin,
                                           Function<BlockPos, SupportSample> sample) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(sample, "sample");
        if (tick < 0) throw new IllegalArgumentException("Sample tick must be nonnegative");
        var cells = new HashMap<GridPos, SupportSample>();
        for (var target : plan.targets()) {
            var relative = target.relativePos();
            var position = new BlockPos(Math.addExact(origin.x(), relative.x()), Math.addExact(origin.y(), relative.y()),
                    Math.addExact(origin.z(), relative.z()));
            cells.put(relative, Objects.requireNonNull(sample.apply(position), "target sample"));
        }
        return new CapturedEvidence(runId, tick, plan.targets(), new SupportObservation(cells));
    }

    static SupportSample sample(BlockState loadedState, PendingPredictionView prediction, BlockPos pos) {
        if (loadedState == null || prediction == null) return UNKNOWN_SAMPLE;
        boolean pending = prediction.mapart$hasPendingPrediction(pos);
        return new SupportSample(pending ? PREDICTED : OBSERVED, Optional.of(blockSpec(loadedState)));
    }

    public static BlockSpec blockSpec(BlockState state) {
        var properties = new HashMap<String, String>();
        for (var property : state.getProperties()) addProperty(properties, state, property);
        return new BlockSpec(Registries.BLOCK.getId(state.getBlock()).toString(), properties);
    }

    private static <T extends Comparable<T>> void addProperty(Map<String, String> properties, BlockState state, Property<T> property) {
        properties.put(property.getName(), property.name(state.get(property)));
    }

    /** Created per callback, retained read-only by its consumer, discarded with that callback/run. */
    public static final class CapturedEvidence {
        private final UUID runId;
        private final long tick;
        private final List<Target> targets;
        private final SupportObservation support;

        private CapturedEvidence(UUID runId, long tick, List<Target> targets, SupportObservation support) {
            this.runId = runId;
            this.tick = tick;
            this.targets = List.copyOf(targets);
            this.support = support;
        }

        public SupportObservation supportObservation() { return support; }
    }
}
