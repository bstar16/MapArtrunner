package com.example.mapart.gui;

import com.example.mapart.settings.MapartSettings;
import com.example.mapart.settings.MapartSettingsStore;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.function.IntConsumer;

public class MapArtConfigScreen extends Screen {
    private static final int COL_W = 200;
    private static final int BTN_H = 20;
    private static final int ROW_STRIDE = 24;
    private static final int SECTION_TOP = 42;
    private static final int LABEL_Y = 32;

    private final MapartSettingsStore settingsStore;
    private final Screen parent;

    private boolean showHud;
    private boolean hudCompact;
    private boolean showSchematicOverlay;
    private boolean overlayCurrentRegionOnly;
    private boolean overlayShowOnlyIncorrect;
    private boolean groundedSweepConstantSprint;
    private int placementDelayTicks;
    private int inventoryClickDelayTicks;
    private int clientTimerSpeed;
    private boolean clientTimerEnabled;

    private TextFieldWidget timerSpeedField;
    private ButtonWidget clientTimerBtn;

    public MapArtConfigScreen(MapartSettingsStore settingsStore, Screen parent) {
        super(Text.literal("MapArt Settings"));
        this.settingsStore = settingsStore;
        this.parent = parent;
        MapartSettings s = settingsStore.current();
        this.showHud = s.showHud();
        this.hudCompact = s.hudCompact();
        this.showSchematicOverlay = s.showSchematicOverlay();
        this.overlayCurrentRegionOnly = s.overlayCurrentRegionOnly();
        this.overlayShowOnlyIncorrect = s.overlayShowOnlyIncorrect();
        this.groundedSweepConstantSprint = s.groundedSweepConstantSprint();
        this.placementDelayTicks = s.placementDelayTicks();
        this.inventoryClickDelayTicks = s.inventoryClickDelayTicks();
        this.clientTimerSpeed = s.clientTimerSpeed();
        this.clientTimerEnabled = s.clientTimerEnabled();
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int leftX = cx - COL_W - 5;
        int rightX = cx + 5;

        // --- DISPLAY column ---
        int y = SECTION_TOP;

        addToggle(leftX, y, "Show HUD", showHud, v -> showHud = v);
        y += ROW_STRIDE;
        addToggle(leftX, y, "HUD Compact", hudCompact, v -> hudCompact = v);
        y += ROW_STRIDE;
        addToggle(leftX, y, "Schematic Overlay", showSchematicOverlay, v -> showSchematicOverlay = v);
        y += ROW_STRIDE;
        addToggle(leftX, y, "Current Region Only", overlayCurrentRegionOnly, v -> overlayCurrentRegionOnly = v);
        y += ROW_STRIDE;
        addToggle(leftX, y, "Incorrect Only", overlayShowOnlyIncorrect, v -> overlayShowOnlyIncorrect = v);

        // --- SWEEP column ---
        y = SECTION_TOP;

        addToggle(rightX, y, "Constant Sprint", groundedSweepConstantSprint, v -> groundedSweepConstantSprint = v);
        y += ROW_STRIDE;

        addDrawableChild(new IntSlider(rightX, y, COL_W, BTN_H, 0, 10, placementDelayTicks,
                "Placement Delay", v -> placementDelayTicks = v));
        y += ROW_STRIDE;

        addDrawableChild(new IntSlider(rightX, y, COL_W, BTN_H, 0, 10, inventoryClickDelayTicks,
                "Inventory Delay", v -> inventoryClickDelayTicks = v));
        y += ROW_STRIDE;

        // Timer speed field + enabled toggle on same row
        timerSpeedField = addDrawableChild(new TextFieldWidget(
                this.textRenderer, rightX, y, 90, BTN_H, Text.literal("Timer speed")));
        timerSpeedField.setMaxLength(3);
        timerSpeedField.setText(String.valueOf(clientTimerSpeed));
        timerSpeedField.setEditable(clientTimerEnabled);
        timerSpeedField.setChangedListener(text -> {
            try {
                int v = Integer.parseInt(text.trim());
                if (v >= 1 && v <= 20) clientTimerSpeed = v;
            } catch (NumberFormatException ignored) {
            }
        });

        clientTimerBtn = addDrawableChild(ButtonWidget.builder(
                Text.literal("Timer: " + onOff(clientTimerEnabled)),
                b -> {
                    clientTimerEnabled = !clientTimerEnabled;
                    b.setMessage(Text.literal("Timer: " + onOff(clientTimerEnabled)));
                    timerSpeedField.setEditable(clientTimerEnabled);
                })
                .dimensions(rightX + 94, y, COL_W - 94, BTN_H)
                .build());

        // Done / Cancel
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> save())
                .dimensions(cx - 105, this.height - 27, 100, BTN_H)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> close())
                .dimensions(cx + 5, this.height - 27, 100, BTN_H)
                .build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        int cx = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 8, 0xFFFFFF);
        context.drawText(this.textRenderer, "DISPLAY", cx - COL_W - 5, LABEL_Y, 0xAAAAAA, true);
        context.drawText(this.textRenderer, "SWEEP", cx + 5, LABEL_Y, 0xAAAAAA, true);
        // Label above timer speed field
        int timerLabelY = SECTION_TOP + ROW_STRIDE * 3;
        context.drawText(this.textRenderer, "Speed (1-20):", cx + 5, timerLabelY - 10, 0xAAAAAA, false);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }

    private void save() {
        settingsStore.set("showHud", String.valueOf(showHud));
        settingsStore.set("hudCompact", String.valueOf(hudCompact));
        settingsStore.set("showSchematicOverlay", String.valueOf(showSchematicOverlay));
        settingsStore.set("overlayCurrentRegionOnly", String.valueOf(overlayCurrentRegionOnly));
        settingsStore.set("overlayShowOnlyIncorrect", String.valueOf(overlayShowOnlyIncorrect));
        settingsStore.set("groundedSweepConstantSprint", String.valueOf(groundedSweepConstantSprint));
        settingsStore.set("placementDelayTicks", String.valueOf(placementDelayTicks));
        settingsStore.set("inventoryClickDelayTicks", String.valueOf(inventoryClickDelayTicks));
        settingsStore.set("clientTimerEnabled", String.valueOf(clientTimerEnabled));
        settingsStore.set("clientTimerSpeed", String.valueOf(clientTimerSpeed));
        this.client.setScreen(parent);
    }

    private void addToggle(int x, int y, String label, boolean initial, java.util.function.Consumer<Boolean> onChange) {
        // Store current value in array so lambda can capture mutable reference
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
