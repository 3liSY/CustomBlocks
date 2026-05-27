package com.customblocks.client.gui;

import com.customblocks.client.HudConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * HUD Editor — Phase 2 full implementation.
 *
 * Click detection uses a shared "clickMap" list built during render(),
 * so button positions in render and mouseClicked are always identical.
 */
@Environment(EnvType.CLIENT)
public class HudEditorScreen extends Screen {

    // ── Layout ────────────────────────────────────────────────────────────────
    private static final int SW       = 180;   // sidebar width
    private static final int BTN_H    = 18;
    private static final int GAP      = 4;
    private static final int CHIP_H   = 20;
    private static final int DRAG_W   = 14;    // chip drag handle width
    private static final int TITLE_H  = 14;

    // ── Button IDs used in clickMap ───────────────────────────────────────────
    private static final int ID_VISIBLE     = 1;
    private static final int ID_STYLE       = 2;
    private static final int ID_MODE_FIELDS = 3;
    private static final int ID_MODE_TMPL   = 4;
    private static final int ID_SAVE_CLOSE  = 5;
    private static final int ID_RESET       = 6;
    private static final int ID_SCOPE       = 7;
    private static final int ID_PRESET_SAVE = 8;
    private static final int ID_PRESET_LOAD_TOGGLE = 9;
    private static final int ID_COPY        = 10;
    private static final int ID_CONFIRM_SAVE= 11;
    private static final int SLD_BG         = 20;
    private static final int SLD_TEXT       = 21;
    private static final int SLD_SCALE      = 22;
    private static final int SLD_FADE       = 23;
    private static final int SLD_STICKY     = 24;
    private static final int ID_STICKY_TOGGLE = 25;
    private static final int ID_SNAP_TL     = 30;
    private static final int ID_SNAP_TC     = 31;
    private static final int ID_SNAP_TR     = 32;
    private static final int ID_SNAP_BL     = 33;
    private static final int ID_SNAP_BC     = 34;
    private static final int ID_SNAP_BR     = 35;
    private static final int ID_FIELD_TOGGLE_BASE = 100; // + slot
    private static final int ID_FIELD_DRAG_BASE   = 200; // + slot
    private static final int ID_PALETTE_BASE       = 300; // + i
    private static final int ID_PRESET_LOAD_BASE   = 400; // + i
    private static final int ID_PRESET_DEL_BASE    = 500; // + i
    private static final int ID_TF_LINE0   = 600;
    private static final int ID_TF_LINE1   = 601;
    private static final int ID_TF_LINE2   = 602;

    private static final int[] PALETTE = {
        0xFF5B8DFF, 0xFF44D97E, 0xFFFF6B6B, 0xFFFFD93D,
        0xFFFF9F43, 0xFFAB55F5, 0xFFFF6BC6, 0xFFFFFFFF
    };

    // ── Click map — built every render frame ──────────────────────────────────
    private final List<int[]> clickMap = new ArrayList<>(); // {x, y, w, h, id}

    // ── Sidebar ───────────────────────────────────────────────────────────────
    private int  sidebarX;
    private boolean sidebarDragging;
    private int  sidebarDragOff;
    private int  sidebarScroll;

    // ── HUD box drag ─────────────────────────────────────────────────────────
    private int  hudX, hudY;
    private boolean hudDragging;
    private int  hudDragOffX, hudDragOffY;
    private int  previewW = 160, previewH = 20;

    // ── Slider state ─────────────────────────────────────────────────────────
    private int activeSlider = -1;

    // ── Field chip drag (reorder) ─────────────────────────────────────────────
    private int dragChipSlot = -1;
    private int dragMouseY;

    // ── Live config state ─────────────────────────────────────────────────────
    private int   style, contentMode;
    private float bgOp, textOp, scale;
    private int   fadeTicks;
    private int   accentColor;
    private boolean hudVisible, scopeAllPlayers, stickyMode;
    private float stickySeconds;
    private final String[] tplLines = new String[3];
    private int[] chipOrder;
    private boolean[] chipEnabled;

    // ── Text fields for template lines ────────────────────────────────────────
    private TextFieldWidget[] lineFields;
    private int focusedLine = -1; // -1 = none

    // ── Preset UI state ───────────────────────────────────────────────────────
    private boolean savingPreset;
    private TextFieldWidget presetNameField;
    private boolean showPresets;

    // ── Originals for change detection ────────────────────────────────────────
    private final int origX, origY, origStyle, origContentMode, origAccent, origFadeTicks;
    private final float origBgOp, origTextOp, origScale;
    private final boolean origVisible, origScope, origSticky;
    private final float origStickySeconds;
    private final String[] origTplLines = new String[3];
    private final int[] origChipOrder;
    private final boolean[] origChipEnabled;

    public HudEditorScreen() {
        super(Text.literal("HUD Editor"));
        hudX         = HudConfig.x;
        hudY         = HudConfig.y;
        style        = HudConfig.style;
        contentMode  = HudConfig.contentMode;
        bgOp         = HudConfig.bgOpacity;
        textOp       = HudConfig.textOpacity;
        scale        = HudConfig.scale;
        fadeTicks    = HudConfig.fadeTicks;
        accentColor  = HudConfig.accentColor;
        hudVisible   = HudConfig.hudVisible;
        scopeAllPlayers = HudConfig.scopeAllPlayers;
        stickyMode   = HudConfig.stickyMode;
        stickySeconds = HudConfig.stickySeconds;
        System.arraycopy(HudConfig.templateLines, 0, tplLines, 0, 3);
        chipOrder    = Arrays.copyOf(HudConfig.chipOrder, 10);
        chipEnabled  = new boolean[10];
        for (int i = 0; i < 10; i++) chipEnabled[i] = HudConfig.isChipEnabled(i);

        origX = hudX; origY = hudY; origStyle = style; origContentMode = contentMode;
        origBgOp = bgOp; origTextOp = textOp; origScale = scale; origFadeTicks = fadeTicks;
        origAccent = accentColor; origVisible = hudVisible; origScope = scopeAllPlayers;
        origSticky = stickyMode; origStickySeconds = stickySeconds;
        System.arraycopy(tplLines, 0, origTplLines, 0, 3);
        origChipOrder   = Arrays.copyOf(chipOrder, 10);
        origChipEnabled = Arrays.copyOf(chipEnabled, 10);
    }

    @Override
    protected void init() {
        sidebarX = this.width - SW;
        sidebarScroll = 0;

        // Template line fields — added to children so Ctrl+Z, Ctrl+A, etc. work natively
        lineFields = new TextFieldWidget[3];
        for (int i = 0; i < 3; i++) {
            lineFields[i] = new TextFieldWidget(textRenderer, 0, 0, SW - 12, 14,
                    Text.literal("Line " + (i + 1)));
            lineFields[i].setMaxLength(300);
            lineFields[i].setText(tplLines[i] != null ? tplLines[i] : "");
            addSelectableChild(lineFields[i]);
        }

        presetNameField = new TextFieldWidget(textRenderer, 0, 0, SW - 12, 14, Text.literal("Name"));
        presetNameField.setMaxLength(32);
        addSelectableChild(presetNameField);
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Sync template lines from widgets before preview renders so changes show immediately
        if (lineFields != null) {
            for (int i = 0; i < 3; i++) {
                if (lineFields[i] != null) tplLines[i] = lineFields[i].getText();
            }
        }
        clickMap.clear();

        // Dark overlay over game area so preview stands out clearly
        ctx.fill(0, 0, sidebarX, this.height, 0xAA000000);

        // Center guide lines while dragging HUD box
        if (hudDragging) {
            int cx = this.width / 2, cy = this.height / 2;
            ctx.fill(cx - 1, 0, cx + 1, this.height, 0x55FFFFFF);
            ctx.fill(0, cy - 1, this.width, cy + 1, 0x55FFFFFF);
        }

        drawHudPreview(ctx);
        drawSidebar(ctx, mouseX, mouseY);

        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Drag preview to place HUD  ·  H = toggle"),
                4, this.height - 12, 0xFF777788);
    }


    // ── Sidebar ───────────────────────────────────────────────────────────────

    private void drawSidebar(DrawContext ctx, int mx, int my) {
        int sx = sidebarX;
        ctx.fill(sx, 0, sx + SW, this.height, 0xDD000000);
        ctx.fill(sx, 0, sx + 2, this.height, accentColor | 0xFF000000);

        // Title bar (drag handle)
        boolean tHov = mx >= sx && mx < sx + SW && my < TITLE_H;
        ctx.fill(sx + 2, 0, sx + SW, TITLE_H, tHov ? 0xFF222244 : 0xFF111133);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§e§lHUD Editor §8≡drag"), sx + 6, 3, 0xFFFFFFFF);
        // Title bar is not in clickMap — dragging is detected directly

        // Clip content below title bar using scissor
        int contentStartY = TITLE_H;
        int contentH      = this.height - contentStartY;
        ctx.enableScissor(sx, contentStartY, sx + SW, this.height);

        int y = contentStartY + GAP - sidebarScroll;

        y = placeBtn(ctx, mx, my, sx + 6, y, SW - 12, BTN_H,
                hudVisible ? "§aHUD: Visible" : "§cHUD: Hidden", ID_VISIBLE) + GAP;

        y = placeBtn(ctx, mx, my, sx + 6, y, SW - 12, BTN_H,
                "§bStyle: §f" + new String[]{"Pill","Glow Box","Plain Text"}[style % 3], ID_STYLE) + GAP;

        // Scope toggle
        y = placeBtn(ctx, mx, my, sx + 6, y, SW - 12, BTN_H,
                scopeAllPlayers ? "§6Scope: All Players" : "§7Scope: Personal only", ID_SCOPE) + GAP;

        // Mode buttons
        int halfW = (SW - 14) / 2;
        placeBtn(ctx, mx, my, sx + 6,         y, halfW, BTN_H,
                contentMode == 0 ? "§a[Fields]" : "§7 Fields", ID_MODE_FIELDS);
        placeBtn(ctx, mx, my, sx + 8 + halfW, y, halfW, BTN_H,
                contentMode == 1 ? "§a[Template]" : "§7 Template", ID_MODE_TMPL);
        y += BTN_H + GAP;

        if (contentMode == 0) {
            y = drawFieldsArea(ctx, mx, my, sx, y) + GAP;
        } else {
            y = drawTemplateArea(ctx, mx, my, sx, y) + GAP;
        }

        // Divider
        ctx.fill(sx + 4, y, sx + SW - 4, y + 1, 0xFF334466); y += 5;

        // Sliders
        y = drawSlider(ctx, mx, my, sx, y, "BG Opacity",   bgOp,                   SLD_BG)   + 4;
        y = drawSlider(ctx, mx, my, sx, y, "Text Opacity", textOp,                 SLD_TEXT) + 4;
        y = drawSlider(ctx, mx, my, sx, y, "Scale",        (scale - 0.5f) / 1.5f,  SLD_SCALE)+ 4;
        // Fade speed: slider maps 0=instant (fadeTicks=0) to 1=slow (fadeTicks=30)
        y = drawSlider(ctx, mx, my, sx, y, "Fade Speed",   fadeTicks / 30f,         SLD_FADE) + 4;

        // Sticky mode toggle + duration
        y = placeBtn(ctx, mx, my, sx + 6, y, SW - 12, BTN_H,
                stickyMode ? "§aStickyMode: ON" : "§7StickyMode: OFF", ID_STICKY_TOGGLE) + GAP;
        if (stickyMode) {
            // stickySeconds 0.5s–10s mapped to 0–1
            y = drawSlider(ctx, mx, my, sx, y, "Sticky Duration",
                    (stickySeconds - 0.5f) / 9.5f, SLD_STICKY) + 4;
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal("§8" + String.format("%.1fs", stickySeconds)),
                    sx + 6, y, 0xFF666666);
            y += 12;
        }
        y += 4;

        // Color palette
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Accent Color"), sx + 6, y, 0xFFAAAAAA);
        y += 10;
        for (int i = 0; i < PALETTE.length; i++) {
            int px = sx + 6 + i * 20;
            ctx.fill(px, y, px + 16, y + 14, PALETTE[i] | 0xFF000000);
            if (accentColor == PALETTE[i]) ctx.drawBorder(px - 1, y - 1, 18, 16, 0xFFFFFFFF);
            reg(px, y, 16, 14, ID_PALETTE_BASE + i);
        }
        y += 20;

        ctx.fill(sx + 4, y, sx + SW - 4, y + 1, 0xFF334466); y += 5;

        // Snap buttons
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Snap to corner:"), sx + 6, y, 0xFFAAAAAA);
        y += 10;
        int sb = (SW - 16) / 3;
        placeBtn(ctx, mx, my, sx + 6,            y, sb, 14, "TL", ID_SNAP_TL);
        placeBtn(ctx, mx, my, sx + 8 + sb,       y, sb, 14, "TC", ID_SNAP_TC);
        placeBtn(ctx, mx, my, sx + 10 + sb * 2,  y, sb, 14, "TR", ID_SNAP_TR);
        y += 18;
        placeBtn(ctx, mx, my, sx + 6,            y, sb, 14, "BL", ID_SNAP_BL);
        placeBtn(ctx, mx, my, sx + 8 + sb,       y, sb, 14, "BC", ID_SNAP_BC);
        placeBtn(ctx, mx, my, sx + 10 + sb * 2,  y, sb, 14, "BR", ID_SNAP_BR);
        y += 20;

        ctx.fill(sx + 4, y, sx + SW - 4, y + 1, 0xFF334466); y += 5;

        // Presets
        int pw = (SW - 14) / 3;
        placeBtn(ctx, mx, my, sx + 6,            y, pw, BTN_H, "§aSave",  ID_PRESET_SAVE);
        placeBtn(ctx, mx, my, sx + 8 + pw,       y, pw, BTN_H, "§bLoad",  ID_PRESET_LOAD_TOGGLE);
        placeBtn(ctx, mx, my, sx + 10 + pw * 2,  y, pw, BTN_H, "§dCopy",  ID_COPY);
        y += BTN_H + GAP;

        if (savingPreset) {
            presetNameField.setX(sx + 6);
            presetNameField.setY(y);
            presetNameField.setWidth(SW - 12);
            presetNameField.render(ctx, mx, my, 0);
            reg(sx + 6, y, SW - 12, 14, -1); // reserve area (no click action — field handles it)
            y += 16;
            y = placeBtn(ctx, mx, my, sx + 6, y, SW - 12, BTN_H, "§aConfirm Save", ID_CONFIRM_SAVE) + GAP;
        }

        if (showPresets) {
            Map<String, ?> presets = HudConfig.getPresets();
            if (presets.isEmpty()) {
                ctx.drawTextWithShadow(textRenderer, Text.literal("§8No presets yet."), sx + 8, y, 0xFF666666);
                y += 12;
            }
            int pi = 0;
            for (String name : presets.keySet()) {
                placeBtn(ctx, mx, my, sx + 6, y, SW - 26, BTN_H, "§f" + name, ID_PRESET_LOAD_BASE + pi);
                placeBtn(ctx, mx, my, sx + SW - 20, y, 14, BTN_H, "§c✕", ID_PRESET_DEL_BASE + pi);
                y += BTN_H + 2;
                if (++pi >= 10) break;
            }
            y += 4;
        }

        ctx.fill(sx + 4, y, sx + SW - 4, y + 1, 0xFF334466); y += 5;

        placeBtn(ctx, mx, my, sx + 6, y, SW - 12, BTN_H, "§aSave & Close", ID_SAVE_CLOSE);
        y += BTN_H + GAP;
        placeBtn(ctx, mx, my, sx + 6, y, SW - 12, BTN_H, "§cReset Defaults", ID_RESET);

        ctx.disableScissor();

        // Scroll indicator
        int totalH = y + sidebarScroll - (TITLE_H + GAP);
        if (totalH > this.height - TITLE_H) {
            float ratio = (float)sidebarScroll / Math.max(1, totalH - (this.height - TITLE_H));
            int trackH = this.height - TITLE_H - 4;
            int thumbH = Math.max(20, (int)((this.height - TITLE_H) / (float)totalH * trackH));
            int thumbY = TITLE_H + 2 + (int)(ratio * (trackH - thumbH));
            ctx.fill(sx + SW - 3, TITLE_H, sx + SW, this.height, 0xFF222222);
            ctx.fill(sx + SW - 3, thumbY, sx + SW, thumbY + thumbH, 0xFF5B8DFF);
        }
    }

    private int drawFieldsArea(DrawContext ctx, int mx, int my, int sx, int startY) {
        int y = startY;
        int chipAreaTop = y;
        for (int slot = 0; slot < 10; slot++) {
            if (slot == dragChipSlot) {
                ctx.fill(sx + 6, y + CHIP_H / 2 - 1, sx + SW - 6, y + CHIP_H / 2 + 1, 0xFF4488FF);
                y += CHIP_H + 2;
                continue;
            }
            int fieldIdx = chipOrder[slot];
            boolean on = chipEnabled[fieldIdx];

            // Drag handle
            boolean hh = mx >= sx + 6 && mx < sx + 6 + DRAG_W && my >= y && my < y + CHIP_H;
            ctx.fill(sx + 6, y, sx + 6 + DRAG_W, y + CHIP_H, hh ? 0xFF334466 : 0xFF1A2233);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("§8≡"),
                    sx + 6 + DRAG_W / 2, y + CHIP_H / 2 - 4, 0xFF888888);
            reg(sx + 6, y, DRAG_W, CHIP_H, ID_FIELD_DRAG_BASE + slot);

            // Chip body
            int cx = sx + 6 + DRAG_W + 2, cw = SW - 12 - DRAG_W - 2;
            boolean ch = mx >= cx && mx < cx + cw && my >= y && my < y + CHIP_H;
            ctx.fill(cx, y, cx + cw, y + CHIP_H,
                    on ? (ch ? 0xFF244D1E : 0xFF1A3616) : (ch ? 0xFF3D1A1A : 0xFF2A1212));
            ctx.drawBorder(cx, y, cw, CHIP_H, on ? 0xFF44CC44 : 0xFFAA3333);
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal((on ? "§a✔ §f" : "§c✗ §7") + HudConfig.CHIP_LABELS[fieldIdx]),
                    cx + 4, y + CHIP_H / 2 - 4, 0xFFFFFFFF);
            reg(cx, y, cw, CHIP_H, ID_FIELD_TOGGLE_BASE + slot);

            y += CHIP_H + 2;
        }
        // Draw dragged chip floating at mouse
        if (dragChipSlot >= 0) {
            int fieldIdx = chipOrder[dragChipSlot];
            int fy = dragMouseY - CHIP_H / 2;
            ctx.fill(sx + 6 + DRAG_W + 2, fy, sx + SW - 6, fy + CHIP_H, 0xFF334488);
            ctx.drawBorder(sx + 6 + DRAG_W + 2, fy, SW - 12 - DRAG_W - 2, CHIP_H, 0xFF88AAFF);
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal((chipEnabled[fieldIdx] ? "§a✔ §f" : "§c✗ §7") + HudConfig.CHIP_LABELS[fieldIdx]),
                    sx + 6 + DRAG_W + 6, fy + CHIP_H / 2 - 4, 0xFFFFFFFF);
            // Store chipAreaTop for drop calculation
            this.chipAreaTopY = chipAreaTop;
        }
        return y;
    }

    private int chipAreaTopY; // set during drawFieldsArea when dragging

    private int drawTemplateArea(DrawContext ctx, int mx, int my, int sx, int startY) {
        int y = startY;
        String[] lineLabels = {"Line 1:", "Line 2:", "Line 3:"};
        int[] tfIds = {ID_TF_LINE0, ID_TF_LINE1, ID_TF_LINE2};
        for (int i = 0; i < 3; i++) {
            ctx.drawTextWithShadow(textRenderer, Text.literal("§7" + lineLabels[i]), sx + 6, y, 0xFFAAAAAA);
            y += 10;
            lineFields[i].setX(sx + 6);
            lineFields[i].setY(y);
            lineFields[i].setWidth(SW - 12);
            lineFields[i].render(ctx, mx, my, 0);
            reg(sx + 6, y, SW - 12, 14, tfIds[i]);
            y += 18;
            tplLines[i] = lineFields[i].getText();
        }
        // Variable hints — two short rows
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8{name} {id} {light} {hard}"), sx + 6, y, 0xFF666666);
        y += 10;
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8{sound} {collision} {face}"), sx + 6, y, 0xFF666666);
        y += 10;
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8{health} {anim} {category}"), sx + 6, y, 0xFF666666);
        y += 12;
        return y;
    }

    private int drawSlider(DrawContext ctx, int mx, int my, int sx, int y,
                           String label, float value, int sliderIdx) {
        int slX = sx + 6, slW = SW - 12;
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7" + label), slX, y, 0xFFAAAAAA);
        y += 10;
        ctx.fill(slX, y + 3, slX + slW, y + 9, 0xFF333333);
        int fillW = Math.max(0, Math.min(slW, (int)(value * slW)));
        ctx.fill(slX, y + 3, slX + fillW, y + 9, accentColor | 0xFF000000);
        int tx = slX + fillW - 3;
        ctx.fill(tx, y, tx + 6, y + 15, 0xFFEEEEEE);
        reg(slX, y - 2, slW, 18, sliderIdx); // generous hit area
        return y + 18;
    }

    /** Register a clickable area (used in mouseClicked). */
    private void reg(int x, int y, int w, int h, int id) {
        clickMap.add(new int[]{x, y, w, h, id});
    }

    /** Draw a button and register its hit area. Returns bottom Y. */
    private int placeBtn(DrawContext ctx, int mx, int my,
                         int x, int y, int w, int h, String label, int id) {
        boolean hov = mx >= x && mx < x + w && my >= y && my < y + h;
        ctx.fill(x, y, x + w, y + h, hov ? 0xFF2A3B5A : 0xFF182030);
        ctx.drawBorder(x, y, w, h, hov ? (accentColor | 0xFF000000) : 0xFF334466);
        int maxChars = (w - 8) / 6; // approx
        String lbl = label;
        String plain = lbl.replaceAll("§.", "");
        if (plain.length() > maxChars) lbl = lbl.substring(0, Math.min(lbl.length(), maxChars + 2)) + "…";
        ctx.drawTextWithShadow(textRenderer, Text.literal(lbl), x + 4, y + (h - 8) / 2, 0xFFFFFFFF);
        reg(x, y, w, h, id);
        return y + h;
    }

    // ── HUD preview box ───────────────────────────────────────────────────────

    private void drawHudPreview(DrawContext ctx) {
        List<String> lines = buildPreviewLines();
        if (lines.isEmpty()) lines = List.of("§8(nothing to show)");
        int lineH = textRenderer.fontHeight + 2;
        int rawW  = 10;
        for (String l : lines) rawW = Math.max(rawW, textRenderer.getWidth(Text.literal(l)));
        // Compute scaled dimensions for direct fill() calls
        int unscW = rawW + 12, unscH = lines.size() * lineH + 4;
        previewW = Math.max(1, (int)(unscW * scale));
        previewH = Math.max(1, (int)(unscH * scale));
        int bx = (hudX >= 0) ? hudX : (this.width / 2 - previewW / 2);
        int by = (hudY >= 0) ? hudY : 40;
        int acc = accentColor | 0xFF000000;
        int bgA = Math.max(0, Math.min(255, (int)(bgOp   * 255)));
        int txA = Math.max(0, Math.min(255, (int)(textOp * 255)));
        int bg  = (bgA << 24);
        int textColor = (txA << 24) | 0xFFFFFF;

        // PREVIEW label above the box
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§7— PREVIEW —"), bx + previewW / 2, by - 12, 0xFF888899);

        // Backgrounds drawn with direct screen coordinates
        switch (style % 3) {
            case 1 -> { // Glow Box
                ctx.fill(bx+2,by+2,bx+previewW-2,by+previewH-2, bg | 0x080818);
                ctx.fill(bx+2,by,bx+previewW-2,by+2,acc);
                ctx.fill(bx+2,by+previewH-2,bx+previewW-2,by+previewH,acc);
                ctx.fill(bx,by+2,bx+2,by+previewH-2,acc);
                ctx.fill(bx+previewW-2,by+2,bx+previewW,by+previewH-2,acc);
            }
            case 2 -> {} // Plain text — no background
            default -> { // Pill
                ctx.fill(bx,by,bx+previewW,by+previewH, bg);
                ctx.fill(bx,by,bx+(int)(3*scale),by+previewH,acc);
            }
        }
        ctx.drawBorder(bx - 1, by - 1, previewW + 2, previewH + 2, 0xFFFFAA00);

        // Text rendered via matrix scale; translate in screen space
        int txOff = (style % 3 == 0) ? (int)(7 * scale) : (int)(5 * scale);
        ctx.getMatrices().push();
        ctx.getMatrices().translate(bx + txOff, by + (int)(2 * scale), 0);
        ctx.getMatrices().scale(scale, scale, 1f);
        for (int i = 0; i < lines.size(); i++) {
            ctx.drawTextWithShadow(textRenderer, Text.literal(lines.get(i)),
                    0, i * lineH, textColor);
        }
        ctx.getMatrices().pop();
    }


    private List<String> buildPreviewLines() {
        if (contentMode == 1) {
            List<String> out = new ArrayList<>();
            for (String t : tplLines) {
                if (t == null || t.isBlank()) continue;
                out.add(t.replace("{name}","Example Block").replace("{id}","example_block")
                         .replace("{light}","8").replace("{hardness}","1.5").replace("{hard}","1.5")
                         .replace("{sound}","stone").replace("{collision}","ON")
                         .replace("{face}","north").replace("{health}","HEALTHY")
                         .replace("{anim}","Static").replace("{category}","None"));
            }
            return out.isEmpty() ? List.of("§8(all lines empty)") : out;
        }
        // Fields mode
        List<String> tokens = new ArrayList<>();
        for (int f : chipOrder) {
            if (!chipEnabled[f]) continue;
            tokens.add(switch(f) {
                case 0 -> "§f❖ Example Block";
                case 1 -> "§8[example_block]";
                case 2 -> "§7Light:§f8";
                case 3 -> "§7Hard:§f1.5";
                case 4 -> "§7🔊§fstone";
                case 5 -> "§7Coll:§aON";
                case 6 -> "§7Face:§fnorth";
                case 7 -> "§7Cat:§fNone";
                case 8 -> "§aHEALTHY";
                case 9 -> "§7Anim:§fStatic";
                default -> "";
            });
        }
        if (tokens.isEmpty()) return List.of("§8(all fields hidden)");
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder(); int curW = 0;
        for (String tok : tokens) {
            int tw = textRenderer.getWidth(Text.literal(tok));
            if (!cur.isEmpty() && curW + 14 + tw > 230) { lines.add(cur.toString().trim()); cur.setLength(0); curW = 0; }
            if (!cur.isEmpty()) { cur.append("  "); curW += 14; }
            cur.append(tok); curW += tw;
        }
        if (!cur.isEmpty()) lines.add(cur.toString().trim());
        return lines;
    }

    // ── Mouse ─────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        int ix = (int)mx, iy = (int)my;

        // Sidebar title bar drag
        if (ix >= sidebarX && ix < sidebarX + SW && iy < TITLE_H) {
            sidebarDragging = true;
            sidebarDragOff  = ix - sidebarX;
            return true;
        }

        // Defocus text fields on any click outside their area
        if (focusedLine >= 0) {
            boolean clickedOnField = false;
            for (int[] r : clickMap) {
                if (r[4] >= ID_TF_LINE0 && r[4] <= ID_TF_LINE2) {
                    if (ix >= r[0] && ix < r[0]+r[2] && iy >= r[1] && iy < r[1]+r[3]) {
                        clickedOnField = true; break;
                    }
                }
            }
            if (!clickedOnField) { lineFields[focusedLine].setFocused(false); focusedLine = -1; }
        }
        if (savingPreset && focusedLine < 0) {
            boolean onPNF = false;
            for (int[] r : clickMap) {
                if (r[4] == -1 && ix >= r[0] && ix < r[0]+r[2] && iy >= r[1] && iy < r[1]+r[3]) { onPNF = true; break; }
            }
            if (!onPNF) presetNameField.setFocused(false);
        }

        // Check clickMap
        for (int[] r : clickMap) {
            if (ix >= r[0] && ix < r[0]+r[2] && iy >= r[1] && iy < r[1]+r[3]) {
                if (r[4] > 0) { handleClick(r[4], ix, iy); return true; }
                // id == -1: preset name field area
                if (r[4] == -1) {
                    presetNameField.mouseClicked(ix, iy, button);
                    presetNameField.setFocused(true);
                    return true;
                }
            }
        }

        // HUD box drag
        int bx = (hudX >= 0) ? hudX : (this.width / 2 - previewW / 2);
        int by = (hudY >= 0) ? hudY : 34;
        if (ix >= bx && ix <= bx + previewW && iy >= by && iy <= by + previewH) {
            hudDragging = true; hudDragOffX = ix - bx; hudDragOffY = iy - by;
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private void handleClick(int id, int ix, int iy) {
        if (id == ID_VISIBLE)       { hudVisible = !hudVisible; return; }
        if (id == ID_STICKY_TOGGLE) { stickyMode = !stickyMode; return; }
        if (id == ID_STYLE)       { style = (style + 1) % 3; return; }
        if (id == ID_SCOPE)       { scopeAllPlayers = !scopeAllPlayers; return; }
        if (id == ID_MODE_FIELDS) { contentMode = 0; return; }
        if (id == ID_MODE_TMPL)   { contentMode = 1; return; }
        if (id == ID_SAVE_CLOSE)  { saveAndClose(); return; }
        if (id == ID_RESET)       { resetToDefaults(); return; }
        if (id == ID_PRESET_SAVE) { startSavePreset(); return; }
        if (id == ID_PRESET_LOAD_TOGGLE) { showPresets = !showPresets; return; }
        if (id == ID_COPY)        { copyCode(); return; }
        if (id == ID_CONFIRM_SAVE){ confirmSavePreset(); return; }
        if (id == ID_SNAP_TL) { snapTo(0); return; }
        if (id == ID_SNAP_TC) { snapTo(1); return; }
        if (id == ID_SNAP_TR) { snapTo(2); return; }
        if (id == ID_SNAP_BL) { snapTo(3); return; }
        if (id == ID_SNAP_BC) { snapTo(4); return; }
        if (id == ID_SNAP_BR) { snapTo(5); return; }
        if (id >= ID_FIELD_TOGGLE_BASE && id < ID_FIELD_DRAG_BASE) {
            int slot = id - ID_FIELD_TOGGLE_BASE;
            chipEnabled[chipOrder[slot]] = !chipEnabled[chipOrder[slot]]; return;
        }
        if (id >= ID_FIELD_DRAG_BASE && id < ID_PALETTE_BASE) {
            dragChipSlot = id - ID_FIELD_DRAG_BASE; dragMouseY = iy; return;
        }
        if (id >= ID_PALETTE_BASE && id < ID_PRESET_LOAD_BASE) {
            accentColor = PALETTE[id - ID_PALETTE_BASE]; return;
        }
        if (id >= ID_PRESET_LOAD_BASE && id < ID_PRESET_DEL_BASE) {
            HudConfig.loadPreset(new ArrayList<>(HudConfig.getPresets().keySet()).get(id - ID_PRESET_LOAD_BASE));
            syncFromConfig(); return;
        }
        if (id >= SLD_BG && id <= SLD_STICKY) { activeSlider = id; updateSlider(id, ix); return; }
        if (id >= ID_TF_LINE0 && id <= ID_TF_LINE2) {
            int li = id - ID_TF_LINE0;
            if (focusedLine >= 0 && focusedLine != li) lineFields[focusedLine].setFocused(false);
            focusedLine = li;
            lineFields[li].setFocused(true);
            lineFields[li].mouseClicked(ix, iy, 0);
            return;
        }
        if (id >= ID_PRESET_DEL_BASE) {
            HudConfig.deletePreset(new ArrayList<>(HudConfig.getPresets().keySet()).get(id - ID_PRESET_DEL_BASE)); return;
        }
    }

    private void updateSlider(int id, int ix) {
        int slX = sidebarX + 6, slW = SW - 12;
        float v = Math.max(0f, Math.min(1f, (float)(ix - slX) / slW));
        switch (id) {
            case SLD_BG     -> bgOp         = v;
            case SLD_TEXT   -> textOp       = v;
            case SLD_SCALE  -> scale        = 0.5f + v * 1.5f;
            case SLD_FADE   -> fadeTicks    = Math.round(v * 30);
            case SLD_STICKY -> stickySeconds = 0.5f + v * 9.5f;
        }
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button != 0) return super.mouseDragged(mx, my, button, dx, dy);
        int ix = (int)mx, iy = (int)my;
        if (sidebarDragging) {
            sidebarX = Math.max(this.width / 2, Math.min(this.width - SW, ix - sidebarDragOff));
            return true;
        }
        if (activeSlider >= 0) { updateSlider(activeSlider, ix); return true; }
        if (dragChipSlot >= 0) { dragMouseY = iy; return true; }
        if (hudDragging) {
            hudX = ix - hudDragOffX;
            hudY = iy - hudDragOffY;
            // Magnetic snap to center (within 15px)
            int cx = this.width / 2 - previewW / 2;
            int cy = this.height / 2 - previewH / 2;
            if (Math.abs(hudX - cx) < 15) hudX = cx;
            if (Math.abs(hudY - cy) < 15) hudY = cy;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button != 0) return super.mouseReleased(mx, my, button);
        sidebarDragging = false;
        activeSlider    = -1;
        if (hudDragging) {
            hudDragging = false;
            int pad = 18;
            if (hudX < pad) hudX = 0;
            if (hudY < pad) hudY = 0;
            if (this.width  - (hudX + previewW) < pad) hudX = this.width  - previewW;
            if (this.height - (hudY + previewH) < pad) hudY = this.height - previewH;
            return true;
        }
        if (dragChipSlot >= 0) {
            int targetSlot = Math.max(0, Math.min(9,
                    (dragMouseY - chipAreaTopY) / (CHIP_H + 2)));
            if (targetSlot != dragChipSlot) {
                int[] newOrder = new int[10];
                int src = chipOrder[dragChipSlot];
                int di = 0;
                for (int i = 0; i < 10; i++) {
                    if (i == dragChipSlot) continue;
                    if (di == targetSlot) newOrder[di++] = src;
                    newOrder[di++] = chipOrder[i];
                }
                if (di == targetSlot) newOrder[di] = src;
                chipOrder = newOrder;
            }
            dragChipSlot = -1;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (mx >= sidebarX) {
            sidebarScroll = Math.max(0, sidebarScroll - (int)(vertical * 14));
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    // ── Keyboard ─────────────────────────────────────────────────────────────

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Forward all keys to focused template line field
        if (focusedLine >= 0 && lineFields[focusedLine].isFocused()) {
            if (lineFields[focusedLine].keyPressed(keyCode, scanCode, modifiers)) {
                tplLines[focusedLine] = lineFields[focusedLine].getText();
                return true;
            }
        }
        // Forward to preset name field
        if (savingPreset && presetNameField.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                confirmSavePreset(); return true;
            }
            if (presetNameField.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (hasChanges()) {
                this.client.setScreen(new ConfirmScreen(
                    confirmed -> { if (confirmed) saveAndClose(); else this.client.setScreen(null); },
                    Text.literal("Save Changes?"), Text.literal("Save your HUD settings?")));
            } else {
                this.client.setScreen(null);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (focusedLine >= 0 && lineFields[focusedLine].isFocused()) {
            if (lineFields[focusedLine].charTyped(chr, modifiers)) {
                tplLines[focusedLine] = lineFields[focusedLine].getText();
                return true;
            }
        }
        if (savingPreset && presetNameField.isFocused())
            return presetNameField.charTyped(chr, modifiers);
        return super.charTyped(chr, modifiers);
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void snapTo(int corner) {
        int pad = 4;
        hudX = switch(corner) { case 0,3 -> pad; case 1,4 -> this.width/2 - previewW/2; default -> this.width - previewW - pad; };
        hudY = switch(corner) { case 0,1,2 -> pad; default -> this.height - previewH - pad; };
    }

    private void startSavePreset() {
        savingPreset = true;
        presetNameField.setText("");
        presetNameField.setFocused(true);
    }

    private void confirmSavePreset() {
        String name = presetNameField.getText().trim();
        if (!name.isEmpty()) { applyToHudConfig(); HudConfig.saveCurrentAsPreset(name); }
        savingPreset = false;
        presetNameField.setFocused(false);
    }

    private void copyCode() {
        applyToHudConfig();
        String code = HudConfig.exportCode();
        if (!code.isEmpty() && this.client != null) this.client.keyboard.setClipboard(code);
    }

    private void syncFromConfig() {
        style = HudConfig.style; contentMode = HudConfig.contentMode;
        bgOp = HudConfig.bgOpacity; textOp = HudConfig.textOpacity;
        scale = HudConfig.scale; fadeTicks = HudConfig.fadeTicks;
        accentColor = HudConfig.accentColor; hudVisible = HudConfig.hudVisible;
        scopeAllPlayers = HudConfig.scopeAllPlayers;
        stickyMode = HudConfig.stickyMode; stickySeconds = HudConfig.stickySeconds;
        System.arraycopy(HudConfig.templateLines, 0, tplLines, 0, 3);
        chipOrder = Arrays.copyOf(HudConfig.chipOrder, 7);
        for (int i = 0; i < 10; i++) chipEnabled[i] = HudConfig.isChipEnabled(i);
        if (lineFields != null) for (int i = 0; i < 3; i++) lineFields[i].setText(tplLines[i] != null ? tplLines[i] : "");
    }

    private void applyToHudConfig() {
        HudConfig.x            = hudX;
        HudConfig.y            = hudY;
        HudConfig.style        = style;
        HudConfig.contentMode  = contentMode;
        HudConfig.bgOpacity    = bgOp;
        HudConfig.textOpacity  = textOp;
        HudConfig.scale        = scale;
        HudConfig.fadeTicks    = fadeTicks;
        HudConfig.accentColor  = accentColor;
        HudConfig.hudVisible   = hudVisible;
        HudConfig.scopeAllPlayers = scopeAllPlayers;
        HudConfig.stickyMode   = stickyMode;
        HudConfig.stickySeconds = stickySeconds;
        System.arraycopy(tplLines, 0, HudConfig.templateLines, 0, 3);
        HudConfig.chipOrder    = Arrays.copyOf(chipOrder, 7);
        for (int i = 0; i < 10; i++) HudConfig.setChipEnabled(i, chipEnabled[i]);
    }

    private void saveAndClose() {
        applyToHudConfig();
        HudConfig.save();
        // Broadcast if scope = all players
        if (scopeAllPlayers && this.client != null && this.client.player != null) {
            String code = HudConfig.exportCode();
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                    new com.customblocks.network.HudConfigSyncPayload(code));
        }
        this.client.setScreen(null);
    }

    private void resetToDefaults() {
        HudConfig.resetToDefaults(); syncFromConfig();
        hudX = HudConfig.x; hudY = HudConfig.y;
    }

    private boolean hasChanges() {
        if (hudX != origX || hudY != origY || style != origStyle) return true;
        if (contentMode != origContentMode || fadeTicks != origFadeTicks) return true;
        if (bgOp != origBgOp || textOp != origTextOp || scale != origScale) return true;
        if (accentColor != origAccent || hudVisible != origVisible || scopeAllPlayers != origScope) return true;
        if (stickyMode != origSticky || stickySeconds != origStickySeconds) return true;
        for (int i = 0; i < 3; i++) if (!Objects.equals(tplLines[i], origTplLines[i])) return true;
        if (!Arrays.equals(chipOrder, origChipOrder) || !Arrays.equals(chipEnabled, origChipEnabled)) return true;
        return false;
    }
}
