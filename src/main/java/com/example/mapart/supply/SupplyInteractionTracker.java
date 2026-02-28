package com.example.mapart.supply;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SupplyInteractionTracker {
    private final SupplyStore supplyStore;
    private final Map<UUID, PendingRegistration> pendingByPlayer = new ConcurrentHashMap<>();

    public SupplyInteractionTracker(SupplyStore supplyStore) {
        this.supplyStore = supplyStore;
    }

    public void registerCallbacks() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient()) {
                return ActionResult.PASS;
            }

            PendingRegistration pending = pendingByPlayer.get(player.getUuid());
            if (pending == null) {
                return ActionResult.PASS;
            }

            BlockPos pos = hitResult.getBlockPos();
            if (!(world.getBlockEntity(pos) instanceof Inventory)) {
                player.sendMessage(Text.literal("That block is not a container. Right-click a container to register your supply point."), false);
                return ActionResult.PASS;
            }

            String dimension = world.getRegistryKey().getValue().toString();
            BlockPos savedPos = pos.toImmutable();
            SupplyPoint point = supplyStore.add(savedPos, dimension, pending.name());
            pendingByPlayer.remove(player.getUuid());

            player.sendMessage(Text.literal("Added supply #" + point.id() + " at " + savedPos.toShortString() + " in " + dimension
                    + (pending.name() == null ? "" : " (" + pending.name() + ")")), false);
            return ActionResult.PASS;
        });
    }

    public void beginRegistration(ClientPlayerEntity player, String name) {
        pendingByPlayer.put(player.getUuid(), new PendingRegistration(name));
    }

    private record PendingRegistration(String name) {
    }
}
