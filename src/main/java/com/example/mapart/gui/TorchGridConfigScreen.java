package com.example.mapart.gui;

import com.example.mapart.plan.sweep.grounded.TorchGridSettings;
import com.example.mapart.settings.MapartSettings;
import com.example.mapart.settings.MapartSettingsStore;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.IntConsumer;

public class TorchGridConfigScreen extends Screen {
    private static final int COL_W = 220;
    private static final int BTN_H = 20;
    private static final int ROW_STRIDE = 24;
    private static final int SECTION_TOP = 42;

    private final MapartSettingsStore settingsStore;
    private final Screen parent;

    private boolean torchGridEnabled;
    private boolean torchGridWarnMissingTorches;
    private int torchGridMaxPlacementsPerTick;

    public TorchGridConfigScreen(MapartSettingsStore settingsStore, Screen parent) {
        super(Text.literal("Torch Grid Settings"));
        this.settingsStore = settingsStore;
        this.parent = parent;
        loadFromStore();
    }

    @Override
    protected void init() {
        int x = (this.width - COL_W) / 2;
        int y = SECTION_TOP;

        addToggle(x, y, "Torch Grid", torchGridEnabled, v -> torchGridEnabled = v);
        y += ROW_STRIDE;
        addToggle(x, y, "Warn Missing Torches", torchGridWarnMissingTorches,
                v -> torchGridWarnMissingTorches = v);
        y += ROW_STRIDE;
        addDrawableChild(new IntSlider(x, y, COL_W, BTN_H,
                TorchGridSettings.MIN_MAX_PLACEMENTS_PER_TICK,
                TorchGridSettings.MAX_MAX_PLACEMENTS_PER_TICK,
                torchGridMaxPlacementsPerTick,
                "Torch Max/Tick",
                v -> torchGridMaxPlacementsPerTick = v));
        y += ROW_STRIDE;
        addDrawableChild(ButtonWidget.builder(
                        Text.literal("Spacing: " + TorchGridSettings.FIXED_SPACING + " (fixed)"),
                        b -> {
                        })
                .dimensions(x, y, COL_W, BTN_H)
                .build());

        int cx = this.width / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> save())
                .dimensions(cx - 105, this.height - 27, 100, BTN_H)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> close())
                .dimensions(cx + 5, this.height - 27, 100, BTN_H)
                .build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }

    private void loadFromStore() {
        MapartSettings s = settingsStore.current();
        this.torchGridEnabled = s.torchGridEnabled();
        this.torchGridWarnMissingTorches = s.torchGridWarnMissingTorches();
        this.torchGridMaxPlacementsPerTick = s.torchGridMaxPlacementsPerTick();
    }

    private void save() {
        settingsStore.set("torchGridEnabled", String.valueOf(torchGridEnabled));
        settingsStore.set("torchGridWarnMissingTorches", String.valueOf(torchGridWarnMissingTorches));
        settingsStore.set("torchGridMaxPlacementsPerTick", String.valueOf(torchGridMaxPlacementsPerTick));
        if (parent instanceof MapArtConfigScreen configScreen) {
            configScreen.refreshTorchGridFromStore();
        }
        this.client.setScreen(parent);
    }

    private void addToggle(int x, int y, String label, boolean initial, java.util.function.Consumer<Boolean> onChange) {
        boolean[] state = {initial};
        ButtonWidget btn = ButtonWidget.builder(
                Text.literal(label + ": " + onOff(state[0])),
                b -> {
                    state[0] = !state[0];
                    onChange.accept(state[0]);
                    b.setMessage(Text.literal(label + ": " + onOff(state[0])));
                })
                .dimensions(x, y, COL_W, BTN_H)
                .build();
        addDrawableChild(btn);
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    private static final class IntSlider extends SliderWidget {
        private final int min;
        private final int max;
        private final String label;
        private final IntConsumer onChange;

        IntSlider(int x, int y, int width, int height, int min, int max, int initial, String label, IntConsumer onChange) {
            super(x, y, width, height, Text.empty(), (double) (initial - min) / (max - min));
            this.min = min;
            this.max = max;
            this.label = label;
            this.onChange = onChange;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int val = min + (int) Math.round(this.value * (max - min));
            setMessage(Text.literal(label + ": " + val));
        }

        @Override
        protected void applyValue() {
            int val = min + (int) Math.round(this.value * (max - min));
            onChange.accept(val);
        }
    }
}
