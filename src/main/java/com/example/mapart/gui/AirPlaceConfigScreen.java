package com.example.mapart.gui;

import com.example.mapart.settings.MapartSettings;
import com.example.mapart.settings.MapartSettingsStore;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.function.DoubleConsumer;

public class AirPlaceConfigScreen extends Screen {
    private static final int COL_W = 220;
    private static final int BTN_H = 20;
    private static final int ROW_STRIDE = 24;
    private static final int SECTION_TOP = 42;

    private final MapartSettingsStore settingsStore;
    private final Screen parent;

    private boolean manualAirPlaceEnabled;
    private boolean manualAirPlaceRender;
    private boolean manualAirPlaceUseCustomRange;
    private double manualAirPlaceCustomRange;
    private boolean manualAirPlaceRequireSneak;
    private boolean manualAirPlaceDisableWhileRunnerActive;

    public AirPlaceConfigScreen(MapartSettingsStore settingsStore, Screen parent) {
        super(Text.literal("Air Place Settings"));
        this.settingsStore = settingsStore;
        this.parent = parent;
        loadFromStore();
    }

    @Override
    protected void init() {
        int x = (this.width - COL_W) / 2;
        int y = SECTION_TOP;

        addToggle(x, y, "Manual Air Place", manualAirPlaceEnabled, v -> manualAirPlaceEnabled = v);
        y += ROW_STRIDE;
        addToggle(x, y, "Air Place Overlay", manualAirPlaceRender, v -> manualAirPlaceRender = v);
        y += ROW_STRIDE;
        addToggle(x, y, "Custom Range", manualAirPlaceUseCustomRange, v -> manualAirPlaceUseCustomRange = v);
        y += ROW_STRIDE;
        addDrawableChild(new DoubleSlider(x, y, COL_W, BTN_H, 0.0, 6.0, manualAirPlaceCustomRange,
                "Air Place Range", v -> manualAirPlaceCustomRange = v));
        y += ROW_STRIDE;
        addToggle(x, y, "Require Sneak", manualAirPlaceRequireSneak, v -> manualAirPlaceRequireSneak = v);
        y += ROW_STRIDE;
        addToggle(x, y, "Disable During Runner", manualAirPlaceDisableWhileRunnerActive,
                v -> manualAirPlaceDisableWhileRunnerActive = v);

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
        this.manualAirPlaceEnabled = s.manualAirPlaceEnabled();
        this.manualAirPlaceRender = s.manualAirPlaceRender();
        this.manualAirPlaceUseCustomRange = s.manualAirPlaceUseCustomRange();
        this.manualAirPlaceCustomRange = s.manualAirPlaceCustomRange();
        this.manualAirPlaceRequireSneak = s.manualAirPlaceRequireSneak();
        this.manualAirPlaceDisableWhileRunnerActive = s.manualAirPlaceDisableWhileRunnerActive();
    }

    private void save() {
        settingsStore.set("manualAirPlaceEnabled", String.valueOf(manualAirPlaceEnabled));
        settingsStore.set("manualAirPlaceRender", String.valueOf(manualAirPlaceRender));
        settingsStore.set("manualAirPlaceUseCustomRange", String.valueOf(manualAirPlaceUseCustomRange));
        settingsStore.set("manualAirPlaceCustomRange", String.format(Locale.ROOT, "%.2f", manualAirPlaceCustomRange));
        settingsStore.set("manualAirPlaceRequireSneak", String.valueOf(manualAirPlaceRequireSneak));
        settingsStore.set("manualAirPlaceDisableWhileRunnerActive", String.valueOf(manualAirPlaceDisableWhileRunnerActive));
        if (parent instanceof MapArtConfigScreen configScreen) {
            configScreen.refreshAirPlaceFromStore();
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

    private static final class DoubleSlider extends SliderWidget {
        private final double min;
        private final double max;
        private final String label;
        private final DoubleConsumer onChange;

        DoubleSlider(int x, int y, int width, int height, double min, double max, double initial, String label, DoubleConsumer onChange) {
            super(x, y, width, height, Text.empty(), (initial - min) / (max - min));
            this.min = min;
            this.max = max;
            this.label = label;
            this.onChange = onChange;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(label + ": " + String.format(Locale.ROOT, "%.1f", value())));
        }

        @Override
        protected void applyValue() {
            onChange.accept(value());
        }

        private double value() {
            return min + (this.value * (max - min));
        }
    }
}
