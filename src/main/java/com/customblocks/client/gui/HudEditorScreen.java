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

@Environment(EnvType.CLIENT)
public class HudEditorScreen extends Screen {

    // ── Layout constants ──────────────────────────────────────────────────────
    private static final int SW        = 180;   // sidebar width
    private static final int BTN_H     = 18;    // standard button height
    private static final int GAP       = 4;
    private static final int CHIP_H    = 20;    // chip row height
    private static final int DRAG_W    = 14;    // drag handle width on each chip
    private static final int TITLE_H   = 14;    // sidebar title bar height

    // Accent palette
    private static final int[] PALETTE = {
        0xFF5B8DFF, 0xFF44D97E, 0xFFFF6B6B, 0xFFFFD93D,
        0xFFFF9F43, 0xFFAB55F5, 0xFFFF6BC6, 0xFFFFFFFF
    };

    // ── Saved originals for change-detection ─────────────────────────────────
    private final int    origX, origY;
    private final int    origStyle, origContentMode;
    private final float  origBgOp, origTextOp, origScale;
    private final int    origAccent;
    private final boolean origVisible;
    private final String origTemplate;
    private final int[]  origChipOrder;
    private final boolean[] origChipEnabled;

    // ── Live state (mirrors HudConfig while editor is open) ───────────────────
    private int   hudX, hudY;
    private int   style, contentMode;
    private float bgOp, textOp, scale;
    private int   accentColor;
    private boolean hudVisible;
    private String template;
    private int[]     chipOrder;   // display order
    private boolean[] chipEnabled; // per-field toggle

    // ── HUD box drag ─────────────────────────────────────────────────────────
    private boolean hudDragging;
    private int     hudDragOffX, hudDragOffY;
    private int     previewW = 160, previewH = 20;

    // ── Sidebar position/drag ─────────────────────────────────────────────────
    private int  sidebarX;
    private boolean sidebarDragging;
    private int  sidebarDragOff;

    // ── Slider drag (0=bg, 1=text, 2=scale, -1=none) ─────────────────────────
    private int activeSlider = -1;

    // ── Chip drag (drag to reorder) ───────────────────────────────────────────
    private int dragChipSlot = -1;  // slot index in chipOrder
    private int dragMouseY;

    // ── Template text field ───────────────────────────────────────────────────
    private TextFieldWidget templateField;

    // ── Preset save ───────────────────────────────────────────────────────────
    private boolean savingPreset;
    private TextFieldWidget presetNameField;

    // ── Preset list visibility ────────────────────────────────────────────────
    private boolean showPresets;

    public HudEditorScreen() {
        super(Text.literal("HUD Editor"));
        hudX = HudConfig.x;
        hudY = HudConfig.y;
        style        = HudConfig.style;
        contentMode  = HudConfig.contentMode;
        bgOp         = HudConfig.bgOpacity;
        textOp       = HudConfig.textOpacity;
        scale        = HudConfig.scale;
        accentColor  = HudConfig.accentColor;
        hudVisible   = HudConfig.hudVisible;
        template     = HudConfig.template;
        chipOrder    = Arrays.copyOf(HudConfig.chipOrder, 7);
        chipEnabled  = new boolean[7];
        for (int i = 0; i < 7; i++) chipEnabled[i] = HudConfig.isChipEnabled(i);

        origX           = hudX; origY = hudY;
        origStyle       = style; origContentMode = contentMode;
        origBgOp        = bgOp; origTextOp = textOp; origScale = scale;
        origAccent      = accentColor;
        origVisible     = hudVisible;
        origTemplate    = template;
        origChipOrder   = Arrays.copyOf(chipOrder, 7);
        origChipEnabled = Arrays.copyOf(chipEnabled, 7);
    }

    @Override
    protected void init() {
        sidebarX = this.width - SW;

        templateField = new TextFieldWidget(textRenderer, sidebarX + 6, 0, SW - 12, 14,
                Text.literal("Template"));
        templateField.setMaxLength(300);
        templateField.setText(template);

        presetNameField = new TextFieldWidget(textRenderer, sidebarX + 6, 0, SW - 12, 14,
                Text.literal("Preset name"));
        presetNameField.setMaxLength(32);
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // World visible behind — intentionally no background fill

        // Center guide lines while dragging HUD box
        if (hudDragging) {
            int cx = this.width / 2;
            int cy = this.height / 2;
            ctx.fill(cx - 1, 0, cx + 1, this.height, 0x55FFFFFF);
            ctx.fill(0, cy - 1, this.width, cy + 1, 0x55FFFFFF);
        }

        drawHudPreview(ctx, mouseX, mouseY);
        drawSidebar(ctx, mouseX, mouseY);

        // Drag hint
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Drag the box · H = toggle HUD"),
                4, this.height - 12, 0xFF666666);
    }

    // ── Sidebar ───────────────────────────────────────────────────────────────

    private int sidebarY() { return 0; }

    private void drawSidebar(DrawContext ctx, int mx, int my) {
        int sx = sidebarX;
        int sh = this.height;

        // Background
        ctx.fill(sx, 0, sx + SW, sh, 0xDD000000);
        // Accent left border
        ctx.fill(sx, 0, sx + 2, sh, accentColor | 0xFF000000);

        // Title bar (drag handle)
        boolean titleHovered = mx >= sx && mx < sx + SW && my >= 0 && my < TITLE_H;
        ctx.fill(sx + 2, 0, sx + SW, TITLE_H, titleHovered ? 0xFF222244 : 0xFF111133);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§e§lHUD Editor §8(drag)"),
                sx + 6, 3, 0xFFFFFFFF);

        int y = TITLE_H + GAP;

        // Visible toggle
        y = drawBtn(ctx, mx, my, sx + 6, y, SW - 12, BTN_H,
                hudVisible ? "§aHUD: Visible" : "§cHUD: Hidden", 0) + GAP;

        // Style cycle
        y = drawBtn(ctx, mx, my, sx + 6, y, SW - 12, BTN_H,
                "§bStyle: §f" + new String[]{"Pill","Glow Box","Plain Text"}[style % 3], 1) + GAP;

        // Content mode
        int halfW = (SW - 14) / 2;
        drawBtn(ctx, mx, my, sx + 6,          y, halfW, BTN_H,
                contentMode == 0 ? "§a[Chips]" : "§7 Chips", 2);
        drawBtn(ctx, mx, my, sx + 8 + halfW,  y, halfW, BTN_H,
                contentMode == 1 ? "§a[Template]" : "§7 Template", 3);
        y += BTN_H + GAP;

        // Content area
        if (contentMode == 0) {
            y = drawChips(ctx, mx, my, sx, y) + GAP;
        } else {
            y = drawTemplateArea(ctx, mx, my, sx, y) + GAP;
        }

        // Divider
        ctx.fill(sx + 4, y, sx + SW - 4, y + 1, 0xFF333355);
        y += 5;

        // Sliders
        y = drawSliderRow(ctx, mx, my, sx, y, "BG Opacity",   bgOp,                 4) + 4;
        y = drawSliderRow(ctx, mx, my, sx, y, "Text Opacity", textOp,               5) + 4;
        y = drawSliderRow(ctx, mx, my, sx, y, "Scale",        (scale - 0.5f) / 1.5f, 6) + 6;

        // Color palette
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Accent Color"), sx + 6, y, 0xFFAAAAAA);
        y += 10;
        for (int i = 0; i < PALETTE.length; i++) {
            int px = sx + 6 + i * 20;
            boolean selected = (accentColor == PALETTE[i]);
            ctx.fill(px, y, px + 16, y + 14, PALETTE[i] | 0xFF000000);
            if (selected) ctx.drawBorder(px - 1, y - 1, 18, 16, 0xFFFFFFFF);
        }
        y += 18;

        // Divider
        ctx.fill(sx + 4, y, sx + SW - 4, y + 1, 0xFF333355);
        y += 5;

        // Snap buttons  [TL][TC][TR]  [BL][BC][BR]
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Snap to corner:"), sx + 6, y, 0xFFAAAAAA);
        y += 10;
        int sb = (SW - 12) / 3 - 2;
        drawBtn(ctx, mx, my, sx + 6,            y, sb, 14, "TL", 10);
        drawBtn(ctx, mx, my, sx + 8 + sb,       y, sb, 14, "TC", 11);
        drawBtn(ctx, mx, my, sx + 10 + sb * 2,  y, sb, 14, "TR", 12);
        y += 18;
        drawBtn(ctx, mx, my, sx + 6,            y, sb, 14, "BL", 13);
        drawBtn(ctx, mx, my, sx + 8 + sb,       y, sb, 14, "BC", 14);
        drawBtn(ctx, mx, my, sx + 10 + sb * 2,  y, sb, 14, "BR", 15);
        y += 18;

        // Divider
        ctx.fill(sx + 4, y, sx + SW - 4, y + 1, 0xFF333355);
        y += 5;

        // Presets
        int ph = (SW - 12) / 3 - 2;
        drawBtn(ctx, mx, my, sx + 6,            y, ph, BTN_H, "§aSave", 20);
        drawBtn(ctx, mx, my, sx + 8 + ph,       y, ph, BTN_H, "§bLoad", 21);
        drawBtn(ctx, mx, my, sx + 10 + ph * 2,  y, ph, BTN_H, "§dCopy", 22);
        y += BTN_H + GAP;

        if (savingPreset) {
            presetNameField.setX(sx + 6);
            presetNameField.setY(y);
            presetNameField.render(ctx, mx, my, 0);
            y += 18;
            drawBtn(ctx, mx, my, sx + 6, y, SW - 12, BTN_H, "§aConfirm Save", 23);
            y += BTN_H + GAP;
        }

        if (showPresets) {
            Map<String, ?> presets = HudConfig.getPresets();
            if (presets.isEmpty()) {
                ctx.drawTextWithShadow(textRenderer, Text.literal("§8No presets saved."),
                        sx + 8, y, 0xFFAAAAAA);
                y += 12;
            }
            int pi = 0;
            for (String name : presets.keySet()) {
                drawBtn(ctx, mx, my, sx + 6, y, SW - 26, BTN_H, "§f" + name, 30 + pi);
                drawBtn(ctx, mx, my, sx + SW - 18, y, 12, BTN_H, "§c✕", 50 + pi);
                y += BTN_H + 2;
                pi++;
                if (pi >= 10) break;
            }
        }

        // Divider
        ctx.fill(sx + 4, y, sx + SW - 4, y + 1, 0xFF333355);
        y += 5;

        // Bottom buttons
        drawBtn(ctx, mx, my, sx + 6, y, SW - 12, BTN_H, "§aSave & Close", 98);
        y += BTN_H + GAP;
        drawBtn(ctx, mx, my, sx + 6, y, SW - 12, BTN_H, "§cReset Defaults", 99);
    }

    /** Draws a manual button rect, returns the button's bottom Y. btnId is used for click detection. */
    private int drawBtn(DrawContext ctx, int mx, int my, int x, int y, int w, int h, String label, int btnId) {
        boolean hovered = mx >= x && mx < x + w && my >= y && my < y + h;
        int bg = hovered ? 0xFF2A3B5A : 0xFF182030;
        ctx.fill(x, y, x + w, y + h, bg);
        ctx.drawBorder(x, y, w, h, hovered ? 0xFF5B8DFF : 0xFF334466);
        ctx.drawTextWithShadow(textRenderer, Text.literal(label),
                x + 4, y + (h - 8) / 2, 0xFFFFFFFF);
        return y + h;
    }

    // ── Chips area ────────────────────────────────────────────────────────────

    private int drawChips(DrawContext ctx, int mx, int my, int sx, int startY) {
        int y = startY;
        for (int slot = 0; slot < 7; slot++) {
            if (slot == dragChipSlot) {
                // Draw insertion indicator
                ctx.fill(sx + 6, y + CHIP_H / 2 - 1, sx + SW - 6, y + CHIP_H / 2 + 1, 0xFF4488FF);
                continue;
            }
            int fieldIdx = chipOrder[slot];
            boolean enabled = chipEnabled[fieldIdx];

            // Drag handle
            boolean handleHovered = mx >= sx + 6 && mx < sx + 6 + DRAG_W && my >= y && my < y + CHIP_H;
            ctx.fill(sx + 6, y, sx + 6 + DRAG_W, y + CHIP_H,
                    handleHovered ? 0xFF334466 : 0xFF1A2233);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("§8≡"),
                    sx + 6 + DRAG_W / 2, y + CHIP_H / 2 - 4, 0xFFAAAAAA);

            // Chip body
            int chipX = sx + 6 + DRAG_W + 2;
            int chipW = SW - 12 - DRAG_W - 2;
            boolean chipHov = mx >= chipX && mx < chipX + chipW && my >= y && my < y + CHIP_H;
            int chipBg = enabled ? (chipHov ? 0xFF244D1E : 0xFF1A3616) : (chipHov ? 0xFF3D1A1A : 0xFF2A1212);
            ctx.fill(chipX, y, chipX + chipW, y + CHIP_H, chipBg);
            ctx.drawBorder(chipX, y, chipW, CHIP_H, enabled ? 0xFF44CC44 : 0xFFAA3333);
            String chip = (enabled ? "§a✔ §f" : "§c✗ §7") + HudConfig.CHIP_LABELS[fieldIdx];
            ctx.drawTextWithShadow(textRenderer, Text.literal(chip), chipX + 4, y + CHIP_H / 2 - 4, 0xFFFFFFFF);

            y += CHIP_H + 2;
        }

        // Draw dragged chip floating at mouse
        if (dragChipSlot >= 0) {
            int fieldIdx = chipOrder[dragChipSlot];
            boolean enabled = chipEnabled[fieldIdx];
            int chipY = dragMouseY - CHIP_H / 2;
            ctx.fill(sx + 6 + DRAG_W + 2, chipY, sx + SW - 6, chipY + CHIP_H, 0xFF334488);
            ctx.drawBorder(sx + 6 + DRAG_W + 2, chipY, SW - 12 - DRAG_W - 2, CHIP_H, 0xFF88AAFF);
            String chip = (enabled ? "§a✔ §f" : "§c✗ §7") + HudConfig.CHIP_LABELS[fieldIdx];
            ctx.drawTextWithShadow(textRenderer, Text.literal(chip),
                    sx + 6 + DRAG_W + 6, chipY + CHIP_H / 2 - 4, 0xFFFFFFFF);
        }

        return y;
    }

    // ── Template area ─────────────────────────────────────────────────────────

    private int drawTemplateArea(DrawContext ctx, int mx, int my, int sx, int startY) {
        int y = startY;
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Format:"), sx + 6, y, 0xFFAAAAAA);
        y += 10;
        templateField.setX(sx + 6);
        templateField.setY(y);
        templateField.setWidth(SW - 12);
        templateField.render(ctx, mx, my, 0);
        y += 16;

        // Variable hints — two rows
        String row1 = "§8{name} {id} {light} {hardness}";
        String row2 = "§8{sound} {collision} {face} {health} {anim}";
        ctx.drawTextWithShadow(textRenderer, Text.literal(row1), sx + 6, y, 0xFF888888);
        y += 10;
        ctx.drawTextWithShadow(textRenderer, Text.literal(row2), sx + 6, y, 0xFF888888);
        y += 12;
        return y;
    }

    // ── Slider ────────────────────────────────────────────────────────────────

    private int drawSliderRow(DrawContext ctx, int mx, int my, int sx, int y,
                              String label, float value, int sliderIdx) {
        int sliderX = sx + 6;
        int sliderW = SW - 12;
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7" + label), sliderX, y, 0xFFAAAAAA);
        y += 10;
        // Track
        ctx.fill(sliderX, y + 3, sliderX + sliderW, y + 9, 0xFF333333);
        // Fill
        int fillW = Math.max(0, Math.min(sliderW, (int)(value * sliderW)));
        ctx.fill(sliderX, y + 3, sliderX + fillW, y + 9, accentColor | 0xFF000000);
        // Thumb
        int tx = sliderX + fillW - 3;
        ctx.fill(tx, y, tx + 6, y + 15, 0xFFEEEEEE);
        return y + 18;
    }

    // ── HUD preview box ───────────────────────────────────────────────────────

    private void drawHudPreview(DrawContext ctx, int mx, int my) {
        List<String> lines = buildPreviewLines();
        int lineH = textRenderer.fontHeight + 2;
        int maxW  = 10;
        for (String l : lines) maxW = Math.max(maxW, textRenderer.getWidth(Text.literal(l)));
        previewW = maxW + 12;
        previewH = lines.size() * lineH + 4;

        int bx = (hudX >= 0) ? hudX : (this.width / 2 - previewW / 2);
        int by = (hudY >= 0) ? hudY : 34;
        int acc = accentColor | 0xFF000000;

        switch (style % 3) {
            case 1 -> { // Glow Box
                ctx.fill(bx + 2, by + 2, bx + previewW - 2, by + previewH - 2, 0xAA080818);
                ctx.fill(bx + 2, by,     bx + previewW - 2, by + 2,            acc);
                ctx.fill(bx + 2, by + previewH - 2, bx + previewW - 2, by + previewH, acc);
                ctx.fill(bx,     by + 2, bx + 2, by + previewH - 2, acc);
                ctx.fill(bx + previewW - 2, by + 2, bx + previewW, by + previewH - 2, acc);
            }
            case 2 -> {} // Plain text — no background
            default -> { // Pill
                ctx.fill(bx, by, bx + previewW, by + previewH, 0x99000000);
                ctx.fill(bx, by, bx + 3, by + previewH, acc);
            }
        }

        // Gold editor border always visible
        ctx.drawBorder(bx - 1, by - 1, previewW + 2, previewH + 2, 0xFFFFAA00);

        int textX = (style % 3 == 0) ? bx + 7 : bx + 5;
        for (int i = 0; i < lines.size(); i++) {
            ctx.drawTextWithShadow(textRenderer, Text.literal(lines.get(i)),
                    textX, by + 2 + i * lineH, 0xFFFFFFFF);
        }
    }

    private List<String> buildPreviewLines() {
        if (contentMode == 1) {
            String result = template
                .replace("{name}",      "Example Block")
                .replace("{id}",        "example_block")
                .replace("{light}",     "8")
                .replace("{hardness}",  "1.5")
                .replace("{sound}",     "stone")
                .replace("{collision}", "ON")
                .replace("{face}",      "north")
                .replace("{health}",    "HEALTHY")
                .replace("{anim}",      "Static")
                .replace("{category}", "None");
            return List.of(result.isEmpty() ? "§8(empty template)" : result);
        }
        // Chips mode — build tokens in chipOrder
        List<String> tokens = new ArrayList<>();
        for (int fieldIdx : chipOrder) {
            if (!chipEnabled[fieldIdx]) continue;
            tokens.add(buildChipToken(fieldIdx, "Example Block", "example_block", 8, 1.5f, "stone", false, "north", "HEALTHY", "Static", "None"));
        }
        if (tokens.isEmpty()) return List.of("§8(all chips hidden)");
        // Pack tokens into lines
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String tok : tokens) {
            String plain = tok.replaceAll("§.", "");
            String curPlain = cur.toString().replaceAll("§.", "");
            if (!cur.isEmpty() && curPlain.length() + plain.length() > 48) {
                lines.add(cur.toString().trim());
                cur.setLength(0);
            }
            if (!cur.isEmpty()) cur.append("  ");
            cur.append(tok);
        }
        if (!cur.isEmpty()) lines.add(cur.toString().trim());
        return lines;
    }

    private String buildChipToken(int fieldIdx,
            String name, String id, int light, float hard,
            String sound, boolean noCollision, String face,
            String health, String anim, String category) {
        return switch (fieldIdx) {
            case 0 -> "§f❖ " + name;
            case 1 -> "§8[" + id + "]";
            case 2 -> "§7Light:§f" + light;
            case 3 -> "§7Hard:§f" + hard;
            case 4 -> "§7🔊§f" + sound;
            case 5 -> "§7Coll:" + (noCollision ? "§cOFF" : "§aON");
            case 6 -> "§7Face:§f" + face;
            default -> "";
        };
    }

    // ── Mouse ─────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        int ix = (int)mx, iy = (int)my;
        int sx = sidebarX;

        // ── Sidebar title bar — start sidebar drag ────────────────────────────
        if (ix >= sx && ix < sx + SW && iy >= 0 && iy < TITLE_H) {
            sidebarDragging = true;
            sidebarDragOff  = ix - sx;
            return true;
        }

        // ── Sidebar clicks ────────────────────────────────────────────────────
        if (ix >= sx && ix < sx + SW) {
            return handleSidebarClick(ix, iy, sx);
        }

        // ── HUD box click — start HUD drag ────────────────────────────────────
        int bx = (hudX >= 0) ? hudX : (this.width / 2 - previewW / 2);
        int by = (hudY >= 0) ? hudY : 34;
        if (ix >= bx && ix <= bx + previewW && iy >= by && iy <= by + previewH) {
            hudDragging = true;
            hudDragOffX = ix - bx;
            hudDragOffY = iy - by;
            return true;
        }

        return super.mouseClicked(mx, my, button);
    }

    private boolean handleSidebarClick(int ix, int iy, int sx) {
        // Recompute button layout to find which one was clicked
        int y = TITLE_H + GAP;

        // Visible toggle
        if (inBtn(ix, iy, sx + 6, y, SW - 12, BTN_H)) { hudVisible = !hudVisible; return true; }
        y += BTN_H + GAP;

        // Style cycle
        if (inBtn(ix, iy, sx + 6, y, SW - 12, BTN_H)) { style = (style + 1) % 3; return true; }
        y += BTN_H + GAP;

        // Content mode buttons
        int halfW = (SW - 14) / 2;
        if (inBtn(ix, iy, sx + 6, y, halfW, BTN_H))         { contentMode = 0; return true; }
        if (inBtn(ix, iy, sx + 8 + halfW, y, halfW, BTN_H)) { contentMode = 1; return true; }
        y += BTN_H + GAP;

        // Chips or template area
        if (contentMode == 0) {
            // Chip toggle clicks (chip body, not drag handle)
            for (int slot = 0; slot < 7; slot++) {
                int chipRowY = y + slot * (CHIP_H + 2);
                int fieldIdx = chipOrder[slot];
                int chipX = sx + 6 + DRAG_W + 2;
                int chipW = SW - 12 - DRAG_W - 2;
                if (inBtn(ix, iy, chipX, chipRowY, chipW, CHIP_H)) {
                    chipEnabled[fieldIdx] = !chipEnabled[fieldIdx];
                    return true;
                }
                // Drag handle — start chip drag
                if (inBtn(ix, iy, sx + 6, chipRowY, DRAG_W, CHIP_H)) {
                    dragChipSlot = slot;
                    dragMouseY   = iy;
                    return true;
                }
            }
            y += 7 * (CHIP_H + 2) + GAP;
        } else {
            // Template field click
            if (inBtn(ix, iy, sx + 6, y + 10, SW - 12, 16)) {
                templateField.setFocused(true);
                templateField.mouseClicked(ix, iy + 10, 0);
                return true;
            }
            y += 48 + GAP;
        }

        // Divider skip
        y += 6;

        // Sliders
        int sliderX = sx + 6;
        int sliderW = SW - 12;
        // BG opacity slider
        int s0y = y + 10;
        if (inBtn(ix, iy, sliderX, s0y - 3, sliderW, 18)) {
            activeSlider = 0;
            bgOp = Math.max(0, Math.min(1f, (float)(ix - sliderX) / sliderW));
            return true;
        }
        y += 22;
        // Text opacity slider
        int s1y = y + 10;
        if (inBtn(ix, iy, sliderX, s1y - 3, sliderW, 18)) {
            activeSlider = 1;
            textOp = Math.max(0, Math.min(1f, (float)(ix - sliderX) / sliderW));
            return true;
        }
        y += 22;
        // Scale slider
        int s2y = y + 10;
        if (inBtn(ix, iy, sliderX, s2y - 3, sliderW, 18)) {
            activeSlider = 2;
            float norm = Math.max(0, Math.min(1f, (float)(ix - sliderX) / sliderW));
            scale = 0.5f + norm * 1.5f;
            return true;
        }
        y += 28;

        // Color palette
        y += 10;
        for (int i = 0; i < PALETTE.length; i++) {
            int px = sx + 6 + i * 20;
            if (inBtn(ix, iy, px, y, 16, 14)) { accentColor = PALETTE[i]; return true; }
        }
        y += 24;

        // Divider skip + Snap buttons
        y += 6;
        y += 10;
        int sb = (SW - 12) / 3 - 2;
        if (inBtn(ix, iy, sx + 6,           y, sb, 14)) { snapTo(0);  return true; }
        if (inBtn(ix, iy, sx + 8 + sb,      y, sb, 14)) { snapTo(1);  return true; }
        if (inBtn(ix, iy, sx + 10 + sb * 2, y, sb, 14)) { snapTo(2);  return true; }
        y += 18;
        if (inBtn(ix, iy, sx + 6,           y, sb, 14)) { snapTo(3);  return true; }
        if (inBtn(ix, iy, sx + 8 + sb,      y, sb, 14)) { snapTo(4);  return true; }
        if (inBtn(ix, iy, sx + 10 + sb * 2, y, sb, 14)) { snapTo(5);  return true; }
        y += 24;

        // Divider + Preset buttons
        y += 6;
        int ph = (SW - 12) / 3 - 2;
        if (inBtn(ix, iy, sx + 6,            y, ph, BTN_H)) { startSavePreset(); return true; }
        if (inBtn(ix, iy, sx + 8 + ph,       y, ph, BTN_H)) { showPresets = !showPresets; return true; }
        if (inBtn(ix, iy, sx + 10 + ph * 2,  y, ph, BTN_H)) { copyCode(); return true; }
        y += BTN_H + GAP;

        if (savingPreset) {
            if (inBtn(ix, iy, sx + 6, y + 18, SW - 12, BTN_H)) { confirmSavePreset(); return true; }
            y += 18 + BTN_H + GAP;
        }

        if (showPresets) {
            int pi = 0;
            for (String name : HudConfig.getPresets().keySet()) {
                if (inBtn(ix, iy, sx + 6, y, SW - 26, BTN_H)) { HudConfig.loadPreset(name); syncFromHudConfig(); return true; }
                if (inBtn(ix, iy, sx + SW - 18, y, 12, BTN_H)) { HudConfig.deletePreset(name); return true; }
                y += BTN_H + 2;
                pi++;
                if (pi >= 10) break;
            }
        }

        // Divider + bottom buttons
        y += 6;
        if (inBtn(ix, iy, sx + 6, y, SW - 12, BTN_H))         { saveAndClose(); return true; }
        y += BTN_H + GAP;
        if (inBtn(ix, iy, sx + 6, y, SW - 12, BTN_H))         { resetToDefaults(); return true; }

        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button != 0) return super.mouseDragged(mx, my, button, dx, dy);
        int ix = (int)mx, iy = (int)my;

        // Sidebar drag
        if (sidebarDragging) {
            sidebarX = Math.max(this.width / 2, Math.min(this.width - SW, ix - sidebarDragOff));
            return true;
        }

        // HUD box drag
        if (hudDragging) {
            hudX = ix - hudDragOffX;
            hudY = iy - hudDragOffY;
            return true;
        }

        // Slider drag
        int sliderX = sidebarX + 6;
        int sliderW = SW - 12;
        if (activeSlider == 0) { bgOp  = clamp01((float)(ix - sliderX) / sliderW); return true; }
        if (activeSlider == 1) { textOp = clamp01((float)(ix - sliderX) / sliderW); return true; }
        if (activeSlider == 2) { scale  = 0.5f + clamp01((float)(ix - sliderX) / sliderW) * 1.5f; return true; }

        // Chip drag
        if (dragChipSlot >= 0) {
            dragMouseY = iy;
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
            // Edge snap
            int pad = 18;
            if (hudX < pad) hudX = 0;
            if (hudY < pad) hudY = 0;
            if (this.width  - (hudX + previewW) < pad) hudX = this.width  - previewW;
            if (this.height - (hudY + previewH) < pad) hudY = this.height - previewH;
            return true;
        }

        if (dragChipSlot >= 0) {
            // Find target slot from mouse Y
            int chipsStartY = chipAreaStartY();
            int targetSlot = Math.max(0, Math.min(6,
                    (dragMouseY - chipsStartY) / (CHIP_H + 2)));
            if (targetSlot != dragChipSlot) {
                // Move chip in chipOrder
                int[] newOrder = new int[7];
                int src = chipOrder[dragChipSlot];
                int di = 0;
                for (int i = 0; i < 7; i++) {
                    if (i == dragChipSlot) continue;
                    if (di == targetSlot) di++;
                    newOrder[di++] = chipOrder[i];
                }
                newOrder[targetSlot] = src;
                chipOrder = newOrder;
            }
            dragChipSlot = -1;
            return true;
        }

        return super.mouseReleased(mx, my, button);
    }

    private int chipAreaStartY() {
        return TITLE_H + GAP + BTN_H + GAP + BTN_H + GAP + BTN_H + GAP;
    }

    // ── Keyboard ─────────────────────────────────────────────────────────────

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Forward to focused text fields first
        if (contentMode == 1 && templateField != null && templateField.isFocused()) {
            if (templateField.keyPressed(keyCode, scanCode, modifiers)) {
                template = templateField.getText();
                return true;
            }
        }
        if (savingPreset && presetNameField != null && presetNameField.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                confirmSavePreset();
                return true;
            }
            if (presetNameField.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (hasChanges()) {
                this.client.setScreen(new ConfirmScreen(
                    confirmed -> { if (confirmed) saveAndClose(); else this.client.setScreen(null); },
                    Text.literal("Save Changes?"),
                    Text.literal("Save your HUD settings before closing?")));
            } else {
                this.client.setScreen(null);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (contentMode == 1 && templateField != null && templateField.isFocused()) {
            if (templateField.charTyped(chr, modifiers)) {
                template = templateField.getText();
                return true;
            }
        }
        if (savingPreset && presetNameField != null && presetNameField.isFocused()) {
            return presetNameField.charTyped(chr, modifiers);
        }
        return super.charTyped(chr, modifiers);
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void snapTo(int corner) {
        int pad = 4;
        int cx  = this.width  / 2 - previewW / 2;
        int cy  = this.height / 2 - previewH / 2;
        hudX = switch(corner) {
            case 0, 3 -> pad;               // left
            case 1, 4 -> cx;               // center H
            default   -> this.width - previewW - pad; // right
        };
        hudY = switch(corner) {
            case 0, 1, 2 -> pad;           // top
            default       -> this.height - previewH - pad; // bottom
        };
    }

    private void startSavePreset() {
        savingPreset = true;
        presetNameField.setText("");
        presetNameField.setFocused(true);
    }

    private void confirmSavePreset() {
        String name = presetNameField.getText().trim();
        if (!name.isEmpty()) {
            // Push current live state into HudConfig then snapshot
            applyToHudConfig();
            HudConfig.saveCurrentAsPreset(name);
        }
        savingPreset = false;
        presetNameField.setFocused(false);
    }

    private void copyCode() {
        applyToHudConfig();
        String code = HudConfig.exportCode();
        if (!code.isEmpty() && this.client != null) {
            this.client.keyboard.setClipboard(code);
        }
    }

    private void syncFromHudConfig() {
        style        = HudConfig.style;
        contentMode  = HudConfig.contentMode;
        bgOp         = HudConfig.bgOpacity;
        textOp       = HudConfig.textOpacity;
        scale        = HudConfig.scale;
        accentColor  = HudConfig.accentColor;
        hudVisible   = HudConfig.hudVisible;
        template     = HudConfig.template;
        chipOrder    = Arrays.copyOf(HudConfig.chipOrder, 7);
        for (int i = 0; i < 7; i++) chipEnabled[i] = HudConfig.isChipEnabled(i);
        if (templateField != null) templateField.setText(template);
    }

    private void applyToHudConfig() {
        HudConfig.x            = hudX;
        HudConfig.y            = hudY;
        HudConfig.style        = style;
        HudConfig.contentMode  = contentMode;
        HudConfig.bgOpacity    = bgOp;
        HudConfig.textOpacity  = textOp;
        HudConfig.scale        = scale;
        HudConfig.accentColor  = accentColor;
        HudConfig.hudVisible   = hudVisible;
        HudConfig.template     = template;
        HudConfig.chipOrder    = Arrays.copyOf(chipOrder, 7);
        for (int i = 0; i < 7; i++) HudConfig.setChipEnabled(i, chipEnabled[i]);
    }

    private void saveAndClose() {
        applyToHudConfig();
        HudConfig.save();
        this.client.setScreen(null);
    }

    private void resetToDefaults() {
        HudConfig.resetToDefaults();
        syncFromHudConfig();
        hudX = HudConfig.x;
        hudY = HudConfig.y;
    }

    private boolean hasChanges() {
        if (hudX != origX || hudY != origY || style != origStyle) return true;
        if (contentMode != origContentMode || !template.equals(origTemplate)) return true;
        if (bgOp != origBgOp || textOp != origTextOp || scale != origScale) return true;
        if (accentColor != origAccent || hudVisible != origVisible) return true;
        if (!Arrays.equals(chipOrder, origChipOrder) || !Arrays.equals(chipEnabled, origChipEnabled)) return true;
        return false;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private boolean inBtn(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
}
