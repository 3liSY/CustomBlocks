package com.customblocks.client.gui;

import com.customblocks.client.HudConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public class HudEditorScreen extends Screen {

    private static final int SIDEBAR_W = 160;
    private static final String[] STYLE_NAMES = { "Pill", "Glow Box", "Plain Text" };
    private static final String[] TOGGLE_LABELS = {
        "Name", "ID", "Light", "Hardness", "Sound", "Collision", "Face"
    };

    // Snapshot on open for change detection
    private final int   origX, origY;
    private final boolean[] origToggles = new boolean[7];
    private final int   origStyle;
    private final float origBgOpacity, origTextOpacity, origScale;
    private final boolean origVisible;

    // Live state
    private int   hudX, hudY;
    private final boolean[] fieldStates = new boolean[7];
    private int   style;
    private float bgOpacity, textOpacity, scale;
    private boolean hudVisible;

    // Drag state
    private boolean dragging = false;
    private int     dragOffX = 0, dragOffY = 0;

    // Slider drag state: -1 = none, 0 = bg opacity, 1 = text opacity, 2 = scale
    private int activeSlider = -1;

    private int previewW = 160, previewH = 52;

    private ButtonWidget[] toggleBtns;
    private ButtonWidget   styleCycleBtn;
    private ButtonWidget   visibleBtn;

    public HudEditorScreen() {
        super(Text.literal("HUD Editor"));
        hudX = HudConfig.x;
        hudY = HudConfig.y;
        origX = HudConfig.x;
        origY = HudConfig.y;
        style        = HudConfig.style;
        bgOpacity    = HudConfig.bgOpacity;
        textOpacity  = HudConfig.textOpacity;
        scale        = HudConfig.scale;
        hudVisible   = HudConfig.hudVisible;
        origStyle       = style;
        origBgOpacity   = bgOpacity;
        origTextOpacity = textOpacity;
        origScale       = scale;
        origVisible     = hudVisible;
        for (int i = 0; i < 7; i++) {
            fieldStates[i] = switch (i) {
                case 0 -> HudConfig.showName;
                case 1 -> HudConfig.showId;
                case 2 -> HudConfig.showLight;
                case 3 -> HudConfig.showHardness;
                case 4 -> HudConfig.showSound;
                case 5 -> HudConfig.showCollision;
                default -> HudConfig.showFace;
            };
            origToggles[i] = fieldStates[i];
        }
    }

    @Override
    protected void init() {
        int sideX = this.width - SIDEBAR_W;
        int y = 6;

        // Visible toggle button
        visibleBtn = ButtonWidget.builder(visibleText(), btn -> {
            hudVisible = !hudVisible;
            btn.setMessage(visibleText());
        }).dimensions(sideX + 6, y, SIDEBAR_W - 12, 18).build();
        addDrawableChild(visibleBtn);
        y += 22;

        // Style cycle button
        styleCycleBtn = ButtonWidget.builder(styleText(), btn -> {
            style = (style + 1) % 3;
            btn.setMessage(styleText());
        }).dimensions(sideX + 6, y, SIDEBAR_W - 12, 18).build();
        addDrawableChild(styleCycleBtn);
        y += 26;

        // Field toggles
        toggleBtns = new ButtonWidget[7];
        for (int i = 0; i < 7; i++) {
            final int idx = i;
            toggleBtns[i] = ButtonWidget.builder(toggleText(i), btn -> {
                fieldStates[idx] = !fieldStates[idx];
                btn.setMessage(toggleText(idx));
            }).dimensions(sideX + 6, y + i * 20, SIDEBAR_W - 12, 18).build();
            addDrawableChild(toggleBtns[i]);
        }
        y += 7 * 20 + 6;

        // Sliders are drawn manually — no buttons for them

        // Save / Reset buttons at bottom
        int btnY = this.height - 48;
        addDrawableChild(ButtonWidget.builder(Text.literal("§aSave & Close"), btn -> saveAndClose())
                .dimensions(sideX + 6, btnY, SIDEBAR_W - 12, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("§cReset Defaults"), btn -> resetToDefaults())
                .dimensions(sideX + 6, btnY + 22, SIDEBAR_W - 12, 18).build());
    }

    // ── Label helpers ─────────────────────────────────────────────────────────

    private Text toggleText(int i) {
        return Text.literal((fieldStates[i] ? "§a[ON]  " : "§c[OFF] ") + "§f" + TOGGLE_LABELS[i]);
    }

    private Text styleText() {
        return Text.literal("§bStyle: §f" + STYLE_NAMES[style % 3]);
    }

    private Text visibleText() {
        return Text.literal(hudVisible ? "§aHUD: Visible" : "§cHUD: Hidden");
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int screenW = this.width;
        int screenH = this.height;
        int sideX   = screenW - SIDEBAR_W;

        // Sidebar background
        ctx.fill(sideX, 0, screenW, screenH, 0xCC000000);
        ctx.fill(sideX, 0, sideX + 1, screenH, 0xFF5B8DFF); // blue left edge

        // Title
        ctx.drawTextWithShadow(textRenderer, Text.literal("§e§lHUD Editor"), sideX + 8, -12, 0xFFFFFFFF);

        // Draw buttons
        super.render(ctx, mouseX, mouseY, delta);

        // Slider section label
        int sliderY = sideX > 10 ? 6 + 22 + 26 + 7 * 20 + 10 : 200;
        int sliderSideX = sideX + 6;
        int sliderW = SIDEBAR_W - 12;
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Background Opacity"), sliderSideX, sliderY, 0xFFAAAAAA);
        drawSlider(ctx, sliderSideX, sliderY + 10, sliderW, bgOpacity, mouseX, mouseY, 0);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Text Opacity"), sliderSideX, sliderY + 28, 0xFFAAAAAA);
        drawSlider(ctx, sliderSideX, sliderY + 38, sliderW, textOpacity, mouseX, mouseY, 1);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Scale"), sliderSideX, sliderY + 56, 0xFFAAAAAA);
        float scaleNorm = (scale - 0.5f) / 1.5f; // 0.5–2.0 mapped to 0–1
        drawSlider(ctx, sliderSideX, sliderY + 66, sliderW, scaleNorm, mouseX, mouseY, 2);

        // HUD preview
        int resolvedX = (hudX >= 0) ? hudX : (screenW / 2 - previewW / 2);
        int resolvedY = (hudY >= 0) ? hudY : 34;
        drawHudPreview(ctx, resolvedX, resolvedY);

        // Drag hint
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Drag the box to move it"), 4, screenH - 12, 0xFF888888);
    }

    private void drawSlider(DrawContext ctx, int x, int y, int w, float value, int mx, int my, int sliderIdx) {
        // Track
        ctx.fill(x, y + 3, x + w, y + 9, 0xFF444444);
        // Fill
        int fillW = (int)(value * w);
        ctx.fill(x, y + 3, x + fillW, y + 9, 0xFF5B8DFF);
        // Thumb
        int thumbX = x + fillW - 3;
        ctx.fill(thumbX, y, thumbX + 6, y + 15, 0xFFFFFFFF);
    }

    private void drawHudPreview(DrawContext ctx, int bx, int by) {
        java.util.List<String> lines = buildPreviewLines();
        int lineH = textRenderer.fontHeight + 2;
        int maxW  = 10;
        for (String l : lines) maxW = Math.max(maxW, textRenderer.getWidth(Text.literal(l)));
        previewW = maxW + 10;
        previewH = lines.size() * lineH + 4;

        int accent = HudConfig.accentColor | 0xFF000000;

        if (style == 2) {
            // Plain text
            for (int i = 0; i < lines.size(); i++)
                ctx.drawTextWithShadow(textRenderer, Text.literal(lines.get(i)), bx + 5, by + 2 + i * lineH, 0xFFFFFFFF);
        } else if (style == 1) {
            // Glow Box
            ctx.fill(bx + 2, by + 2, bx + previewW - 2, by + previewH - 2, 0xAA080818);
            ctx.fill(bx + 2, by,     bx + previewW - 2, by + 2, accent);
            ctx.fill(bx + 2, by + previewH - 2, bx + previewW - 2, by + previewH, accent);
            ctx.fill(bx,     by + 2, bx + 2, by + previewH - 2, accent);
            ctx.fill(bx + previewW - 2, by + 2, bx + previewW, by + previewH - 2, accent);
            for (int i = 0; i < lines.size(); i++)
                ctx.drawTextWithShadow(textRenderer, Text.literal(lines.get(i)), bx + 5, by + 2 + i * lineH, 0xFFFFFFFF);
        } else {
            // Pill
            ctx.fill(bx, by, bx + previewW, by + previewH, 0x99000000);
            ctx.fill(bx, by, bx + 3, by + previewH, accent);
            // Gold editor border
            ctx.drawBorder(bx - 1, by - 1, previewW + 2, previewH + 2, 0xFFFFAA00);
            for (int i = 0; i < lines.size(); i++)
                ctx.drawTextWithShadow(textRenderer, Text.literal(lines.get(i)), bx + 7, by + 2 + i * lineH, 0xFFFFFFFF);
        }

        // Always show gold border in editor so the box is findable
        if (style != 0) {
            ctx.drawBorder(bx - 1, by - 1, previewW + 2, previewH + 2, 0xFFFFAA00);
        }
    }

    private java.util.List<String> buildPreviewLines() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (fieldStates[0] || fieldStates[1]) {
            StringBuilder sb = new StringBuilder();
            if (fieldStates[0]) sb.append("§f✦ Example Block ");
            if (fieldStates[1]) sb.append("§8[example_block]");
            lines.add(sb.toString().trim());
        }
        StringBuilder detail = new StringBuilder();
        if (fieldStates[2]) detail.append("§7Light: §f8  ");
        if (fieldStates[3]) detail.append("§7Hard: §f1.5  ");
        if (fieldStates[4]) detail.append("§7🔊 §fstone");
        if (!detail.toString().trim().isEmpty()) lines.add(detail.toString().trim());
        StringBuilder status = new StringBuilder();
        if (fieldStates[5]) status.append("§7Collision: §aON  ");
        if (fieldStates[6]) status.append("§7Face: §fnorth");
        if (!status.toString().trim().isEmpty()) lines.add(status.toString().trim());
        if (lines.isEmpty()) lines.add("§8(all fields hidden)");
        return lines;
    }

    // ── Mouse drag (HUD box + sliders) ───────────────────────────────────────

    private int sliderBaseY() {
        return 6 + 22 + 26 + 7 * 20 + 10;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            int sideX     = this.width - SIDEBAR_W;
            int sliderSideX = sideX + 6;
            int sliderW   = SIDEBAR_W - 12;
            int sy        = sliderBaseY();

            // Check slider 0 (bg opacity) at sy+10
            if (mx >= sliderSideX && mx <= sliderSideX + sliderW && my >= sy + 7 && my <= sy + 18) {
                activeSlider = 0;
                bgOpacity = Math.max(0f, Math.min(1f, (float)(mx - sliderSideX) / sliderW));
                return true;
            }
            // Check slider 1 (text opacity) at sy+38
            if (mx >= sliderSideX && mx <= sliderSideX + sliderW && my >= sy + 35 && my <= sy + 46) {
                activeSlider = 1;
                textOpacity = Math.max(0f, Math.min(1f, (float)(mx - sliderSideX) / sliderW));
                return true;
            }
            // Check slider 2 (scale) at sy+66
            if (mx >= sliderSideX && mx <= sliderSideX + sliderW && my >= sy + 63 && my <= sy + 74) {
                activeSlider = 2;
                float norm = Math.max(0f, Math.min(1f, (float)(mx - sliderSideX) / sliderW));
                scale = 0.5f + norm * 1.5f;
                return true;
            }

            // Check HUD box drag
            int resolvedX = (hudX >= 0) ? hudX : (this.width / 2 - previewW / 2);
            int resolvedY = (hudY >= 0) ? hudY : 34;
            if (mx >= resolvedX && mx <= resolvedX + previewW && my >= resolvedY && my <= resolvedY + previewH) {
                dragging = true;
                dragOffX = (int)mx - resolvedX;
                dragOffY = (int)my - resolvedY;
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button == 0) {
            int sideX       = this.width - SIDEBAR_W;
            int sliderSideX = sideX + 6;
            int sliderW     = SIDEBAR_W - 12;
            if (activeSlider == 0) {
                bgOpacity = Math.max(0f, Math.min(1f, (float)(mx - sliderSideX) / sliderW));
                return true;
            }
            if (activeSlider == 1) {
                textOpacity = Math.max(0f, Math.min(1f, (float)(mx - sliderSideX) / sliderW));
                return true;
            }
            if (activeSlider == 2) {
                float norm = Math.max(0f, Math.min(1f, (float)(mx - sliderSideX) / sliderW));
                scale = 0.5f + norm * 1.5f;
                return true;
            }
            if (dragging) {
                hudX = (int)mx - dragOffX;
                hudY = (int)my - dragOffY;
                return true;
            }
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0) {
            activeSlider = -1;
            if (dragging) {
                dragging = false;
                if (hudX < 18) hudX = 0;
                if (hudY < 18) hudY = 0;
                if (this.width - (hudX + previewW) < 18)  hudX = this.width  - previewW;
                if (this.height - (hudY + previewH) < 18) hudY = this.height - previewH;
                return true;
            }
        }
        return super.mouseReleased(mx, my, button);
    }

    // ── ESC ──────────────────────────────────────────────────────────────────

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (hasChanges()) {
                this.client.setScreen(new ConfirmScreen(
                    confirmed -> { if (confirmed) saveAndClose(); else this.client.setScreen(null); },
                    Text.literal("Save Changes?"),
                    Text.literal("Save your HUD layout before closing?")
                ));
            } else {
                this.client.setScreen(null);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean hasChanges() {
        if (hudX != origX || hudY != origY) return true;
        if (style != origStyle) return true;
        if (bgOpacity != origBgOpacity || textOpacity != origTextOpacity || scale != origScale) return true;
        if (hudVisible != origVisible) return true;
        for (int i = 0; i < 7; i++) if (fieldStates[i] != origToggles[i]) return true;
        return false;
    }

    private void resetToDefaults() {
        HudConfig.resetToDefaults();
        hudX = HudConfig.x; hudY = HudConfig.y;
        style = HudConfig.style; bgOpacity = HudConfig.bgOpacity;
        textOpacity = HudConfig.textOpacity; scale = HudConfig.scale;
        hudVisible = HudConfig.hudVisible;
        for (int i = 0; i < 7; i++) fieldStates[i] = true;
        if (toggleBtns != null) for (int i = 0; i < 7; i++) toggleBtns[i].setMessage(toggleText(i));
        if (styleCycleBtn != null) styleCycleBtn.setMessage(styleText());
        if (visibleBtn != null) visibleBtn.setMessage(visibleText());
    }

    private void saveAndClose() {
        HudConfig.x             = hudX;
        HudConfig.y             = hudY;
        HudConfig.showName      = fieldStates[0];
        HudConfig.showId        = fieldStates[1];
        HudConfig.showLight     = fieldStates[2];
        HudConfig.showHardness  = fieldStates[3];
        HudConfig.showSound     = fieldStates[4];
        HudConfig.showCollision = fieldStates[5];
        HudConfig.showFace      = fieldStates[6];
        HudConfig.style         = style;
        HudConfig.bgOpacity     = bgOpacity;
        HudConfig.textOpacity   = textOpacity;
        HudConfig.scale         = scale;
        HudConfig.hudVisible    = hudVisible;
        HudConfig.save();
        this.client.setScreen(null);
    }
}
