package com.customblocks.client.gui;

import com.customblocks.network.AnimSettingsPayload;
import com.google.gson.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Right-click GUI for animated custom blocks.
 * Lets the player control FPS, interpolation, frame range, and speed presets.
 * Opens when a player right-clicks any animated CustomBlock (GIF/APNG/animated).
 */
@Environment(EnvType.CLIENT)
public class AnimBlockScreen extends Screen {

    // ── Data ──────────────────────────────────────────────────────────────────
    private final String customId;
    private final String blockName;
    private       String animMeta;
    private       int    frameCount;

    // Parsed state
    private int[]   frameTimes;   // per-frame tick durations
    private boolean interpolate;
    private boolean pingPong;     // back-and-forth loop

    // Live edit state
    private float targetFps;
    private boolean settingsChanged = false;

    // ── Colors ────────────────────────────────────────────────────────────────
    private static final int C_BG      = 0xE5_0A0A12;
    private static final int C_PANEL   = 0xFF_141428;
    private static final int C_BORDER  = 0xFF_3344AA;
    private static final int C_BORDER2 = 0xFF_5566CC;
    private static final int C_ACCENT  = 0xFF_FFB300;
    private static final int C_GREEN   = 0xFF_44FF88;
    private static final int C_RED     = 0xFF_FF4455;
    private static final int C_BLUE    = 0xFF_66AAFF;
    private static final int C_GREY    = 0xFF_888899;
    private static final int C_WHITE   = 0xFF_FFFFFF;
    private static final int C_ANIM_ORANGE = 0xFF_FF8844;

    // ── Layout ────────────────────────────────────────────────────────────────
    private int panelX, panelY, panelW, panelH;

    // ── Widgets ───────────────────────────────────────────────────────────────
    private TextFieldWidget fpsField;
    private ButtonWidget    btnInterpolate, btnPingPong;
    private ButtonWidget    btnApply, btnCancel;
    private ButtonWidget    btnPresetSlow, btnPresetNorm, btnPresetFast, btnPresetUltra;
    private ButtonWidget    btnFpsUp, btnFpsDown;
    private ButtonWidget    btnFpsUp10, btnFpsDown10;

    // ── Pulse animation ───────────────────────────────────────────────────────
    private long openTime;
    private int  dotFrame = 0;
    private long lastDotUpdate = 0;

    // ── Constructor ───────────────────────────────────────────────────────────

    public AnimBlockScreen(String customId, String blockName, String animMeta, int frameCount) {
        super(Text.literal("Animation Settings"));
        this.customId   = customId;
        this.blockName  = blockName;
        this.animMeta   = animMeta;
        this.frameCount = frameCount;
        parseAnimMeta();
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private void parseAnimMeta() {
        try {
            JsonObject root = JsonParser.parseString(animMeta).getAsJsonObject();
            JsonObject anim = root.getAsJsonObject("animation");
            interpolate = anim.has("interpolate") && anim.get("interpolate").getAsBoolean();
            pingPong    = false; // not standard MC but we track it for future use

            JsonArray frames = anim.has("frames") ? anim.getAsJsonArray("frames") : null;
            if (frames != null && !frames.isEmpty()) {
                frameCount = frames.size();
                frameTimes = new int[frameCount];
                long totalTicks = 0;
                for (int i = 0; i < frameCount; i++) {
                    JsonElement el = frames.get(i);
                    if (el.isJsonObject()) {
                        frameTimes[i] = el.getAsJsonObject().has("time")
                                ? el.getAsJsonObject().get("time").getAsInt() : 1;
                    } else {
                        frameTimes[i] = 1;
                    }
                    totalTicks += frameTimes[i];
                }
                float avgTicks = (float) totalTicks / frameCount;
                targetFps = Math.round((20.0f / avgTicks) * 10) / 10.0f;
            } else {
                // Simple frametime-based
                int frametime = anim.has("frametime") ? anim.get("frametime").getAsInt() : 1;
                frameTimes  = new int[frameCount];
                for (int i = 0; i < frameCount; i++) frameTimes[i] = frametime;
                targetFps = Math.round((20.0f / frametime) * 10) / 10.0f;
            }
        } catch (Exception e) {
            frameTimes  = new int[Math.max(1, frameCount)];
            for (int i = 0; i < frameTimes.length; i++) frameTimes[i] = 2;
            targetFps   = 10.0f;
            interpolate = false;
        }
    }

    private float getEffectiveFps() {
        if (frameTimes == null || frameTimes.length == 0) return targetFps;
        double total = 0;
        for (int t : frameTimes) total += t;
        float avg = (float)(total / frameTimes.length);
        return Math.round((20.0f / avg) * 10) / 10.0f;
    }

    /**
     * Build a new animMeta JSON from the given FPS and settings.
     * Each frame gets time = round(20 / fps), minimum 1 tick.
     */
    private String buildAnimMeta(float fps, boolean interp) {
        int tickTime = Math.max(1, Math.round(20.0f / fps));
        StringBuilder sb = new StringBuilder("{\"animation\":{");
        if (interp) sb.append("\"interpolate\":true,");
        sb.append("\"frames\":[");
        for (int i = 0; i < frameCount; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"index\":").append(i).append(",\"time\":").append(tickTime).append("}");
        }
        sb.append("]}}");
        return sb.toString();
    }

    // ── Screen lifecycle ──────────────────────────────────────────────────────

    @Override
    protected void init() {
        openTime    = System.currentTimeMillis();
        panelW      = 260;
        panelH      = 240;
        panelX      = (width  - panelW) / 2;
        panelY      = (height - panelH) / 2;

        int cx   = panelX + panelW / 2;
        int row1 = panelY + 78;   // FPS input row
        int row2 = panelY + 102;  // +/- buttons
        int row3 = panelY + 122;  // presets
        int row4 = panelY + 143;  // toggles
        int row5 = panelY + 167;  // apply/cancel

        // ── FPS text field (anvil-style box) ─────────────────────────────────
        fpsField = new TextFieldWidget(textRenderer,
                cx - 28, row1, 56, 20,
                Text.literal("FPS"));
        fpsField.setMaxLength(8);
        fpsField.setText(String.format("%.1f", targetFps));
        fpsField.setChangedListener(s -> settingsChanged = true);
        addDrawableChild(fpsField);

        // ── FPS nudge buttons ─────────────────────────────────────────────────
        btnFpsDown10 = addBtn(Text.literal("<<"), cx - 95, row2, 22, 16, b -> nudgeFps(-10));
        btnFpsDown   = addBtn(Text.literal("<"),  cx - 70, row2, 22, 16, b -> nudgeFps(-1));
        btnFpsUp     = addBtn(Text.literal(">"),  cx + 48, row2, 22, 16, b -> nudgeFps(+1));
        btnFpsUp10   = addBtn(Text.literal(">>"), cx + 73, row2, 22, 16, b -> nudgeFps(+10));

        // ── Speed presets ─────────────────────────────────────────────────────
        btnPresetSlow  = addBtn(Text.literal("5fps"),  cx - 95, row3, 40, 14, b -> setFps(5));
        btnPresetNorm  = addBtn(Text.literal("10fps"), cx - 52, row3, 40, 14, b -> setFps(10));
        btnPresetFast  = addBtn(Text.literal("20fps"), cx + 13, row3, 40, 14, b -> setFps(20));
        btnPresetUltra = addBtn(Text.literal("30fps"), cx + 56, row3, 40, 14, b -> setFps(30));

        // ── Interpolate toggle ────────────────────────────────────────────────
        btnInterpolate = addBtn(interpLabel(), cx - 95, row4, 88, 16, b -> {
            interpolate = !interpolate;
            settingsChanged = true;
            b.setMessage(interpLabel());
        });

        // ── Ping-pong toggle ──────────────────────────────────────────────────
        btnPingPong = addBtn(pingLabel(), cx + 8, row4, 88, 16, b -> {
            pingPong = !pingPong;
            settingsChanged = true;
            b.setMessage(pingLabel());
        });

        // ── Apply / Cancel ────────────────────────────────────────────────────
        btnApply  = addBtn(Text.literal("Apply"),  cx - 60, row5, 52, 18, b -> applySettings());
        btnCancel = addBtn(Text.literal("Cancel"), cx + 8,  row5, 52, 18, b -> close());

        fpsField.setFocused(true);
    }

    private ButtonWidget addBtn(Text label, int x, int y, int w, int h,
                                ButtonWidget.PressAction action) {
        return addDrawableChild(ButtonWidget.builder(label, action).dimensions(x, y, w, h).build());
    }

    private Text interpLabel() {
        return Text.literal("Interp: " + (interpolate ? "ON" : "OFF"));
    }

    private Text pingLabel() {
        return Text.literal("PingPong: " + (pingPong ? "ON" : "OFF"));
    }

    // ── Input helpers ─────────────────────────────────────────────────────────

    private void setFps(float fps) {
        targetFps = fps;
        fpsField.setText(String.format("%.1f", fps));
        settingsChanged = true;
    }

    private void nudgeFps(float delta) {
        try {
            float current = Float.parseFloat(fpsField.getText().trim());
            setFps(Math.max(0.5f, Math.min(60f, current + delta)));
        } catch (NumberFormatException e) {
            setFps(Math.max(0.5f, Math.min(60f, targetFps + delta)));
        }
    }

    private void applySettings() {
        float fps;
        try {
            fps = Float.parseFloat(fpsField.getText().trim());
            fps = Math.max(0.5f, Math.min(60f, fps));
        } catch (NumberFormatException e) {
            fps = targetFps;
        }

        String newMeta = buildAnimMeta(fps, interpolate);

        // Send to server
        if (client != null && client.getNetworkHandler() != null) {
            ClientPlayNetworking.send(new AnimSettingsPayload(customId, newMeta));
        }
        close();
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx, mx, my, delta);

        long now = System.currentTimeMillis();

        // Animated dot ticker
        if (now - lastDotUpdate > 350) {
            dotFrame = (dotFrame + 1) % 4;
            lastDotUpdate = now;
        }

        // ── Outer panel ───────────────────────────────────────────────────────
        ctx.fill(panelX, panelY, panelX + panelW, panelY + panelH, C_PANEL);
        drawBorder(ctx, panelX, panelY, panelW, panelH, C_BORDER, C_BORDER2);

        int cx = panelX + panelW / 2;

        // ── Header bar ────────────────────────────────────────────────────────
        ctx.fill(panelX, panelY, panelX + panelW, panelY + 28, 0xFF_0C0C22);

        String dots = ".".repeat(dotFrame + 1) + "   ".substring(dotFrame);
        String header = "ANIM SETTINGS " + dots;
        ctx.drawCenteredTextWithShadow(textRenderer, header, cx, panelY + 6, C_ANIM_ORANGE);
        ctx.drawCenteredTextWithShadow(textRenderer, blockName, cx, panelY + 18, C_WHITE);

        // ── Info row ──────────────────────────────────────────────────────────
        float curFps = getEffectiveFps();
        String fpsStr = curFps == (int) curFps
                ? String.valueOf((int) curFps)
                : String.format("%.1f", curFps);

        ctx.drawCenteredTextWithShadow(textRenderer,
                "Frames: " + frameCount
                + "   Current: " + fpsStr + " fps"
                + "   " + (1000 / Math.max(1, curFps * 50) > 0
                        ? Math.round(20 / Math.max(0.5f, curFps)) + " ticks/frame" : ""),
                cx, panelY + 34, C_GREY);

        // ── FPS input section ─────────────────────────────────────────────────
        // Anvil-style label
        int anvilX = panelX + panelW / 2 - 28;
        int anvilY = panelY + 60;
        ctx.fill(anvilX - 2, anvilY - 12, anvilX + 58, anvilY + 26, 0xFF_0A0A0A);
        ctx.drawBorder(anvilX - 2, anvilY - 12, 60, 38, C_ACCENT);
        ctx.drawCenteredTextWithShadow(textRenderer, "New FPS", panelX + panelW / 2, anvilY - 10, C_ACCENT);

        // Tick-equivalent info
        float parsedFps = targetFps;
        try { parsedFps = Float.parseFloat(fpsField != null ? fpsField.getText() : String.valueOf(targetFps)); }
        catch (NumberFormatException ignored) {}
        int ticks = Math.max(1, Math.round(20.0f / Math.max(0.5f, parsedFps)));
        ctx.drawCenteredTextWithShadow(textRenderer,
                "= " + ticks + " tick" + (ticks == 1 ? "" : "s") + "/frame  (20 ticks = 1 sec)",
                cx, panelY + 103, C_GREY);

        // ── Presets label ─────────────────────────────────────────────────────
        ctx.drawCenteredTextWithShadow(textRenderer, "Speed Presets:", cx, panelY + 112, C_GREY);

        // ── Section dividers ──────────────────────────────────────────────────
        int dy = panelY + 160;
        ctx.fill(panelX + 10, dy, panelX + panelW - 10, dy + 1, C_BORDER);

        super.render(ctx, mx, my, delta);

        // Tooltip on nudge buttons
        if (btnFpsDown != null && btnFpsDown.isHovered())
            ctx.drawTooltip(textRenderer, Text.literal("-1 fps"), mx, my);
        if (btnFpsUp != null && btnFpsUp.isHovered())
            ctx.drawTooltip(textRenderer, Text.literal("+1 fps"), mx, my);
        if (btnFpsDown10 != null && btnFpsDown10.isHovered())
            ctx.drawTooltip(textRenderer, Text.literal("-10 fps"), mx, my);
        if (btnFpsUp10 != null && btnFpsUp10.isHovered())
            ctx.drawTooltip(textRenderer, Text.literal("+10 fps"), mx, my);
        if (btnInterpolate != null && btnInterpolate.isHovered())
            ctx.drawTooltip(textRenderer, Text.literal("Smooth transition between frames"), mx, my);
        if (btnPingPong != null && btnPingPong.isHovered())
            ctx.drawTooltip(textRenderer, Text.literal("Play forward then backward (future feature)"), mx, my);
    }

    /** Draw a 2-tone border (inner + outer line for depth). */
    private void drawBorder(DrawContext ctx, int x, int y, int w, int h, int colorOuter, int colorInner) {
        // Outer
        ctx.drawBorder(x, y, w, h, colorOuter);
        // Inner
        ctx.drawBorder(x + 1, y + 1, w - 2, h - 2, colorInner);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter = apply
        if (keyCode == 257 || keyCode == 335) { // ENTER or NUMPAD_ENTER
            applySettings();
            return true;
        }
        // Escape = cancel
        if (keyCode == 256) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public boolean shouldCloseOnEsc() { return true; }
}
