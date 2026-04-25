package com.example.mapart.plan.sweep.grounded;

import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundEvents;

public final class GroundedDisplacementAlert {
    private static final int ALERT_REPEAT_TICKS = 20;

    private int cooldownTicks;
    private boolean acknowledged;
    private boolean hasMouseSample;
    private double lastMouseX;
    private double lastMouseY;

    public void tick(MinecraftClient client, boolean groundedSweepActive, boolean displacedBelowBuildArea) {
        if (client == null || client.player == null) {
            reset();
            return;
        }

        if (!groundedSweepActive) {
            reset();
            sampleMouse(client);
            return;
        }

        if (hasUserActivity(client)) {
            acknowledged = true;
        }

        if (!displacedBelowBuildArea) {
            cooldownTicks = 0;
            return;
        }

        if (acknowledged) {
            return;
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        client.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.1f);
        cooldownTicks = ALERT_REPEAT_TICKS;
    }

    public void reset() {
        cooldownTicks = 0;
        acknowledged = false;
    }

    private boolean hasUserActivity(MinecraftClient client) {
        if (client.options == null) {
            sampleMouse(client);
            return false;
        }

        boolean keyActivity = client.options.forwardKey.isPressed()
                || client.options.backKey.isPressed()
                || client.options.leftKey.isPressed()
                || client.options.rightKey.isPressed()
                || client.options.jumpKey.isPressed()
                || client.options.sneakKey.isPressed()
                || client.options.attackKey.isPressed()
                || client.options.useKey.isPressed();

        double mouseX = client.mouse.getX();
        double mouseY = client.mouse.getY();
        boolean mouseActivity = hasMouseSample && (mouseX != lastMouseX || mouseY != lastMouseY);
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        hasMouseSample = true;

        return keyActivity || mouseActivity;
    }

    private void sampleMouse(MinecraftClient client) {
        lastMouseX = client.mouse.getX();
        lastMouseY = client.mouse.getY();
        hasMouseSample = true;
    }
}
