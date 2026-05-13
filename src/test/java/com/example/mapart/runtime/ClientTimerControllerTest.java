package com.example.mapart.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClientTimerControllerTest {

    @BeforeEach
    void resetState() {
        ClientTimerController.reset();
    }

    @Test
    void defaultMultiplierIsOne() {
        assertEquals(1.0, ClientTimerController.getMultiplier());
    }

    @Test
    void applySettingsEnabledSetsMultiplier() {
        ClientTimerController.applySettings(true, 5);
        assertEquals(5.0, ClientTimerController.getMultiplier());
    }

    @Test
    void applySettingsDisabledResetsToOne() {
        ClientTimerController.applySettings(true, 10);
        ClientTimerController.applySettings(false, 10);
        assertEquals(1.0, ClientTimerController.getMultiplier());
    }

    @Test
    void applySettingsReturnsTrueOnChange() {
        assertTrue(ClientTimerController.applySettings(true, 3));
    }

    @Test
    void applySettingsReturnsFalseWhenUnchanged() {
        ClientTimerController.applySettings(true, 3);
        assertFalse(ClientTimerController.applySettings(true, 3));
    }

    @Test
    void applySettingsDisabledIgnoresSpeedValue() {
        // Speed is irrelevant when disabled; multiplier should be 1.0 regardless
        ClientTimerController.applySettings(false, 15);
        assertEquals(1.0, ClientTimerController.getMultiplier());
    }

    @Test
    void modeConstantIsRealClientTimer() {
        assertEquals("REAL_CLIENT_TIMER", ClientTimerController.MODE);
    }
}
