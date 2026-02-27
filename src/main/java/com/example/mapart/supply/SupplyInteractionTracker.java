package com.example.mapart.supply;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.inventory.Inventory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SupplyInteractionTracker {
    private final Map<UUID, InteractionSnapshot> lastContainerByPlayer = new ConcurrentHashMap<>();

    public void registerCallbacks() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient()) {
                return ActionResult.PASS;
            }

            BlockPos pos = hitResult.getBlockPos();
            if (world.getBlockEntity(pos) instanceof Inventory) {
                lastContainerByPlayer.put(player.getUuid(), new InteractionSnapshot(world, pos.toImmutable()));
            }

            return ActionResult.PASS;
        });
    }

    public Optional<BlockPos> lastContainer(ServerPlayerEntity player, World world) {
        InteractionSnapshot snapshot = lastContainerByPlayer.get(player.getUuid());
        if (snapshot == null) {
            return Optional.empty();
        }

        if (!snapshot.dimension().equals(world.getRegistryKey().getValue().toString())) {
            return Optional.empty();
        }

        return Optional.of(snapshot.pos());
    }

    private record InteractionSnapshot(String dimension, BlockPos pos) {
        private InteractionSnapshot(World world, BlockPos pos) {
            this(world.getRegistryKey().getValue().toString(), pos);
        }
    }
}
