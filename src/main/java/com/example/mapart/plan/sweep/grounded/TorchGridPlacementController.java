package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.inventory.HotbarSlotReservations;
import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.state.PlacementExecutor;
import com.example.mapart.plan.state.PlacementResult;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class TorchGridPlacementController {
    private static final int PLACE_RANGE = 4;

    private final TorchGridSettings settings;
    private final Map<BlockPos, Block> expectedSupportBlocks;
    private final LinkedHashSet<TorchGridTarget> pendingTargets;
    private final Set<BlockPos> completedTorchPositions = new LinkedHashSet<>();
    private boolean missingTorchesWarningSent;
    private int placedCount;
    private int skippedNoTorchCount;
    private int skippedNotReadyCount;

    public TorchGridPlacementController(BuildPlan plan, BlockPos origin, GroundedSchematicBounds bounds, TorchGridSettings settings) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(bounds, "bounds");
        this.settings = Objects.requireNonNull(settings, "settings");

        TorchGridPlanner planner = new TorchGridPlanner();
        List<TorchGridTarget> plannedTargets = planner.plan(bounds, settings);
        Map<BlockPos, Block> supportBlocks = expectedBlocksByWorldPos(plan, origin);
        this.expectedSupportBlocks = new LinkedHashMap<>();
        this.pendingTargets = new LinkedHashSet<>();
        for (TorchGridTarget target : plannedTargets) {
            Block expectedSupportBlock = supportBlocks.get(target.supportPos());
            if (expectedSupportBlock == null) {
                continue;
            }
            this.expectedSupportBlocks.put(target.supportPos(), expectedSupportBlock);
            this.pendingTargets.add(target);
        }
    }

    public static TorchGridPlacementController disabled() {
        return new TorchGridPlacementController();
    }

    private TorchGridPlacementController() {
        this.settings = TorchGridSettings.disabled();
        this.expectedSupportBlocks = Map.of();
        this.pendingTargets = new LinkedHashSet<>();
    }

    public void tick(MinecraftClient client, PlacementExecutor placementExecutor, int reservedHotbarSlots) {
        if (!settings.enabled() || client == null || client.player == null || client.world == null || pendingTargets.isEmpty()) {
            return;
        }
        HotbarSlotReservations.validateReservedHotbarSlots(reservedHotbarSlots);
        int attempts = 0;
        Iterator<TorchGridTarget> iterator = pendingTargets.iterator();
        while (iterator.hasNext() && attempts < settings.maxPlacementsPerTick()) {
            TorchGridTarget target = iterator.next();
            if (!isNearEnough(client.player.getBlockPos(), target.torchPos())) {
                continue;
            }
            if (!client.world.isPosLoaded(target.supportPos()) || !client.world.isPosLoaded(target.torchPos())) {
                continue;
            }

            Block expectedSupportBlock = expectedSupportBlocks.get(target.supportPos());
            BlockState supportState = client.world.getBlockState(target.supportPos());
            if (!supportState.isSideSolidFullSquare(client.world, target.supportPos(), Direction.UP)) {
                skippedNotReadyCount++;
                attempts++;
                continue;
            }
            TorchGridReadiness readiness = evaluateReadiness(
                    expectedSupportBlock,
                    supportState,
                    client.world.getBlockState(target.torchPos())
            );
            if (readiness == TorchGridReadiness.ALREADY_PRESENT) {
                completedTorchPositions.add(target.torchPos());
                iterator.remove();
                attempts++;
                continue;
            }
            if (readiness != TorchGridReadiness.READY) {
                skippedNotReadyCount++;
                attempts++;
                continue;
            }

            PlacementResult result = placementExecutor.executeUtilityBlockAllowingReservedExistingHotbarItem(
                    client,
                    target.torchPos(),
                    Items.TORCH,
                    Blocks.TORCH,
                    reservedHotbarSlots
            );
            switch (result.status()) {
                case PLACED -> {
                    placedCount++;
                    completedTorchPositions.add(target.torchPos());
                    iterator.remove();
                }
                case ALREADY_CORRECT -> {
                    completedTorchPositions.add(target.torchPos());
                    iterator.remove();
                }
                case MISSING_ITEM -> {
                    skippedNoTorchCount++;
                    warnMissingTorchesOnce(client);
                    return;
                }
                case HOTBAR_SWAP_PENDING, ACCEPTED_PENDING_VERIFICATION, RETRY, MOVE_REQUIRED, ERROR -> skippedNotReadyCount++;
            }
            attempts++;
        }
    }

    public static TorchGridReadiness evaluateReadiness(Block expectedSupportBlock, BlockState supportState, BlockState targetState) {
        if (expectedSupportBlock == null || supportState == null || targetState == null) {
            return TorchGridReadiness.NOT_READY;
        }
        return evaluateReadiness(supportState.isOf(expectedSupportBlock), targetState.isReplaceable(), targetState.isOf(Blocks.TORCH));
    }

    public static TorchGridReadiness evaluateReadiness(boolean supportMatches, boolean targetReplaceable, boolean targetAlreadyTorch) {
        if (targetAlreadyTorch) {
            return TorchGridReadiness.ALREADY_PRESENT;
        }
        if (!supportMatches) {
            return TorchGridReadiness.NOT_READY;
        }
        if (!targetReplaceable) {
            return TorchGridReadiness.NOT_READY;
        }
        return TorchGridReadiness.READY;
    }

    public TorchGridDiagnostics diagnostics() {
        return new TorchGridDiagnostics(
                settings.enabled(),
                expectedSupportBlocks.size(),
                placedCount,
                skippedNoTorchCount,
                skippedNotReadyCount,
                pendingTargets.size()
        );
    }

    public int plannedCount() {
        return expectedSupportBlocks.size();
    }

    private void warnMissingTorchesOnce(MinecraftClient client) {
        if (!settings.warnMissingTorches() || missingTorchesWarningSent || client.player == null) {
            return;
        }
        missingTorchesWarningSent = true;
        client.player.sendMessage(Text.literal("[Mapart grounded] Torch grid is enabled, but no automated-usable torches are available. Continuing build."), false);
    }

    private static boolean isNearEnough(BlockPos playerPos, BlockPos targetPos) {
        return Math.abs(playerPos.getX() - targetPos.getX()) <= PLACE_RANGE
                && Math.abs(playerPos.getY() - targetPos.getY()) <= PLACE_RANGE
                && Math.abs(playerPos.getZ() - targetPos.getZ()) <= PLACE_RANGE;
    }

    private static Map<BlockPos, Block> expectedBlocksByWorldPos(BuildPlan plan, BlockPos origin) {
        Map<BlockPos, Block> expected = new LinkedHashMap<>();
        for (Placement placement : plan.placements()) {
            if (placement.block() != null) {
                expected.put(origin.add(placement.relativePos()), placement.block());
            }
        }
        return expected;
    }

    public enum TorchGridReadiness {
        READY,
        ALREADY_PRESENT,
        NOT_READY
    }

    public record TorchGridDiagnostics(
            boolean enabled,
            int plannedCount,
            int placedCount,
            int skippedNoTorchCount,
            int skippedNotReadyCount,
            int pendingCount
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("enabled", enabled);
            map.put("plannedCount", plannedCount);
            map.put("placedCount", placedCount);
            map.put("skippedNoTorchCount", skippedNoTorchCount);
            map.put("skippedNotReadyCount", skippedNotReadyCount);
            map.put("pendingCount", pendingCount);
            return map;
        }
    }
}
