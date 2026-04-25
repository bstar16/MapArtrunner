package com.example.mapart.plan.sweep.grounded;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.sound.SoundEvents;

public final class GroundedDisplacementAlert {
    private static final int ALERT_INTERVAL_TICKS = 12;

    private boolean alertActive;
    private int alertTicker;
    private float lastYaw;
    private float lastPitch;

    public void tick(MinecraftClient client, boolean shouldAlert) {
        if (client == null || client.player == null) {
            stop();
            return;
        }

        if (!shouldAlert) {
            stop();
            cacheLook(client);
            return;
        }

        if (!alertActive) {
            alertActive = true;
            alertTicker = 0;
            cacheLook(client);
        }

        if (dismissRequested(client)) {
            stop();
            cacheLook(client);
            return;
        }

        if (alertTicker <= 0) {
            client.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.75f);
            alertTicker = ALERT_INTERVAL_TICKS;
        } else {
            alertTicker--;
        }
    }

    public void stop() {
        alertActive = false;
        alertTicker = 0;
    }

    private boolean dismissRequested(MinecraftClient client) {
        if (client.options == null) {
            return false;
        }

        if (isMovementKeyPressed(client.options.forwardKey)
                || isMovementKeyPressed(client.options.backKey)
                || isMovementKeyPressed(client.options.leftKey)
                || isMovementKeyPressed(client.options.rightKey)
                || isMovementKeyPressed(client.options.jumpKey)
                || isMovementKeyPressed(client.options.sneakKey)) {
            return true;
        }

        float yaw = client.player.getYaw();
        float pitch = client.player.getPitch();
        boolean movedMouse = Math.abs(yaw - lastYaw) > 0.01f || Math.abs(pitch - lastPitch) > 0.01f;
        if (movedMouse) {
            return true;
        }

        return false;
    }

    private void cacheLook(MinecraftClient client) {
        lastYaw = client.player.getYaw();
        lastPitch = client.player.getPitch();
    }

    private static boolean isMovementKeyPressed(KeyBinding key) {
        return key != null && key.isPressed();
    }
}
