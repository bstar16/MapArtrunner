package com.example.mapart.plan.sweep.grounded;

import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundEvents;

public final class GroundedDisplacementAlert {
    private static final int ALERT_INTERVAL_TICKS = 20;

    private boolean armed;
    private boolean acknowledged;
    private int ticksSinceAlert;
    private double lastMouseX;
    private double lastMouseY;
    private boolean hasMouseSample;

    public void tick(MinecraftClient client, boolean activeGroundedBuildMode, boolean displacedBelowArea) {
        if (client == null || client.player == null || client.options == null) {
            reset();
            return;
        }

        if (!activeGroundedBuildMode) {
            reset();
            return;
        }

        if (!displacedBelowArea) {
            armed = false;
            acknowledged = false;
            ticksSinceAlert = 0;
            sampleMouse(client);
            return;
        }

        if (!armed) {
            armed = true;
            acknowledged = false;
            ticksSinceAlert = ALERT_INTERVAL_TICKS;
            sampleMouse(client);
        }

        if (!acknowledged && userInteracted(client)) {
            acknowledged = true;
        }
        if (acknowledged) {
            return;
        }

        ticksSinceAlert++;
        if (ticksSinceAlert >= ALERT_INTERVAL_TICKS) {
            ticksSinceAlert = 0;
            client.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.40f, 1.0f);
        }
    }

    private boolean userInteracted(MinecraftClient client) {
        boolean keyActivity = client.options.forwardKey.isPressed()
                || client.options.backKey.isPressed()
                || client.options.leftKey.isPressed()
                || client.options.rightKey.isPressed()
                || client.options.jumpKey.isPressed()
                || client.options.sneakKey.isPressed();

        double currentX = client.mouse.getX();
        double currentY = client.mouse.getY();
        boolean mouseMoved = hasMouseSample && (Double.compare(currentX, lastMouseX) != 0 || Double.compare(currentY, lastMouseY) != 0);
        lastMouseX = currentX;
        lastMouseY = currentY;
        hasMouseSample = true;
        return keyActivity || mouseMoved;
    }

    private void sampleMouse(MinecraftClient client) {
        lastMouseX = client.mouse.getX();
        lastMouseY = client.mouse.getY();
        hasMouseSample = true;
    }

    private void reset() {
        armed = false;
        acknowledged = false;
        ticksSinceAlert = 0;
        hasMouseSample = false;
    }
}
