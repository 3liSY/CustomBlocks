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

    // Sidebar width
    private static final int SIDEBAR_W = 148;

    // Toggle labels in order matching HudConfig fields
    private static final String[] TOGGLE_LABELS = {
        "Name", "ID", "Light", "Hardness", "Sound", "Collision", "Face"
    };

    // Saved-on-open values for change detection
    private final int   origX, origY;
    private final boolean[] origToggles = new boolean[7];

    // Live state
    private int   hudX, hudY;
    private final boolean[] fieldStates = new boolean[7];

    // Drag state
    private boolean dragging  = false;
    private int     dragOffX  = 0;
    private int     dragOffY  = 0;

    // Preview panel size (approximation — recomputed each render)
    private int previewW = 160;
    private int previewH = 52;

    private ButtonWidget[] toggleBtns;

    public HudEditorScreen() {
        super(Text.literal("HUD Editor"));
        this.hudX = HudConfig.x;
        this.hudY = HudConfig.y;
        this.origX = HudConfig.x;
        this.origY = HudConfig.y;
        fieldStates[0] = HudConfig.showName;
        fieldStates[1] = HudConfig.showId;
        fieldStates[2] = HudConfig.showLight;
        fieldStates[3] = HudConfig.showHardness;
        fieldStates[4] = HudConfig.showSound;
        fieldStates[5] = HudConfig.showCollision;
        fieldStates[6] = HudConfig.showFace;
        System.arraycopy(fieldStates, 0, origToggles, 0, 7);
    }

    @Override
    protected void init() {
        int sideX = this.width - SIDEBAR_W;
        int y = 20;

        toggleBtns = new ButtonWidget[7];
        for (int i = 0; i < 7; i++) {
            final int idx = i;
            toggleBtns[i] = ButtonWidget.builder(toggleText(i), btn -> {
                fieldStates[idx] = !fieldStates[idx];
                btn.setMessage(toggleText(idx));
            }).dimensions(sideX + 6, y + 20 + i * 22, SIDEBAR_W - 12, 20).build();
            this.addDrawableChild(toggleBtns[i]);
        }

        int btnY = y + 20 + 7 * 22 + 8;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Save & Close"), btn -> saveAndClose())
                .dimensions(sideX + 6, btnY, SIDEBAR_W - 12, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Reset to Default"), btn -> {
            hudX = -1; hudY = -1;
            for (int i = 0; i < 7; i++) fieldStates[i] = true;
            refreshToggleLabels();
        }).dimensions(sideX + 6, btnY + 24, SIDEBAR_W - 12, 20).build());
    }

    private Text toggleText(int i) {
        return Text.literal((fieldStates[i] ? "§a[ON]  " : "§c[OFF] ") + "§f" + TOGGLE_LABELS[i]);
    }

    private void refreshToggleLabels() {
        if (toggleBtns == null) return;
        for (int i = 0; i < 7; i++) toggleBtns[i].setMessage(toggleText(i));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // World visible behind — no background fill

        int screenW = this.width;
        int screenH = this.height;
        int sideX   = screenW - SIDEBAR_W;

        // Sidebar background
        ctx.fill(sideX, 0, screenW, screenH, 0xCC000000);

        // Sidebar title
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("§e§lHUD Editor"), sideX + 8, 6, 0xFFFFFFFF);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("§7Drag the gold box to reposition."), sideX + 8, 16, 0xFFAAAAAA);

        // Draw toggle buttons and other widgets
        super.render(ctx, mouseX, mouseY, delta);

        // Compute preview panel position
        int resolvedX = (hudX >= 0) ? hudX : (screenW / 2 - previewW / 2);
        int resolvedY = (hudY >= 0) ? hudY : 34;

        // Preview text lines
        java.util.List<String> lines = buildPreviewLines();
        int lineH = this.textRenderer.fontHeight + 2;
        int maxW  = 10;
        for (String l : lines) maxW = Math.max(maxW, this.textRenderer.getWidth(Text.literal(l)));
        previewW = maxW + 10;
        previewH = lines.size() * lineH + 4;

        // Box fill
        ctx.fill(resolvedX, resolvedY, resolvedX + previewW, resolvedY + previewH, 0x99000000);

        // Gold border (editor-mode indicator)
        ctx.drawBorder(resolvedX - 1, resolvedY - 1, previewW + 2, previewH + 2, 0xFFFFAA00);

        // Text
        for (int i = 0; i < lines.size(); i++) {
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(lines.get(i)),
                    resolvedX + 5, resolvedY + 2 + i * lineH, 0xFFFFFFFF);
        }

        // Drag hint
        ctx.drawTextWithShadow(this.textRenderer,
                Text.literal("§7Click & drag the box to move it"),
                4, screenH - 12, 0xFFAAAAAA);
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
        String d = detail.toString().trim();
        if (!d.isEmpty()) lines.add(d);
        StringBuilder status = new StringBuilder();
        if (fieldStates[5]) status.append("§7Collision: §aON  ");
        if (fieldStates[6]) status.append("§7Face: §fnorth");
        String s = status.toString().trim();
        if (!s.isEmpty()) lines.add(s);
        if (lines.isEmpty()) lines.add("§8(all fields hidden)");
        return lines;
    }

    // ── Mouse drag ────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            int resolvedX = (hudX >= 0) ? hudX : (this.width / 2 - previewW / 2);
            int resolvedY = (hudY >= 0) ? hudY : 34;
            if (mx >= resolvedX && mx <= resolvedX + previewW && my >= resolvedY && my <= resolvedY + previewH) {
                dragging  = true;
                dragOffX  = (int) mx - resolvedX;
                dragOffY  = (int) my - resolvedY;
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging && button == 0) {
            hudX = (int) mx - dragOffX;
            hudY = (int) my - dragOffY;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging && button == 0) {
            dragging = false;
            // Snap to edges within 18px
            if (hudX < 18) hudX = 0;
            if (hudY < 18) hudY = 0;
            if (this.width - (hudX + previewW) < 18) hudX = this.width - previewW;
            if (this.height - (hudY + previewH) < 18) hudY = this.height - previewH;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    // ── ESC handling ─────────────────────────────────────────────────────────

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (hasChanges()) {
                this.client.setScreen(new ConfirmScreen(
                    confirmed -> {
                        if (confirmed) saveAndClose();
                        else this.client.setScreen(null);
                    },
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
        for (int i = 0; i < 7; i++) if (fieldStates[i] != origToggles[i]) return true;
        return false;
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
        HudConfig.save();
        this.client.setScreen(null);
    }

}
