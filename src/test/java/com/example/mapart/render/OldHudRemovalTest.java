package com.example.mapart.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class OldHudRemovalTest {
    @Test
    void oldHudRendererClassIsRemoved() {
        assertFalse(Files.exists(Path.of("src/main/java/com/example/mapart/render/HudRenderer.java")));
    }

    @Test
    void deprecatedHudRenderCallbackIsNoLongerUsed() throws Exception {
        String clientMod = Files.readString(Path.of("src/main/java/com/example/mapart/MapArtClientMod.java"));

        assertFalse(clientMod.contains("HudRenderCallback"));
        assertFalse(clientMod.contains("HudRenderer"));
    }

    @Test
    void configScreenNoLongerShowsOldHudControls() throws Exception {
        String configScreen = Files.readString(Path.of("src/main/java/com/example/mapart/gui/MapArtConfigScreen.java"));

        assertFalse(configScreen.contains("Show HUD"));
        assertFalse(configScreen.contains("HUD Compact"));
        assertFalse(configScreen.contains("showHud"));
        assertFalse(configScreen.contains("hudCompact"));
    }

    @Test
    void settingsOutputNoLongerPrintsOldHudEntries() throws Exception {
        String command = Files.readString(Path.of("src/main/java/com/example/mapart/command/MapArtCommand.java"));

        assertFalse(command.contains("showHud="));
        assertFalse(command.contains("hudCompact="));
    }
}
