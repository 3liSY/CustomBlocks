package com.customblocks.client.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;


/**
 * Developer Console (Phase S)
 * Client-side keybind opens chat-overlay-sized GUI.
 * Tabs: Logs / Performance / Eval / Inspect / Simulate.
 */
@Environment(EnvType.CLIENT)
public class DevConsoleScreen extends Screen {

    private int activeTab = 0; // 0=Logs, 1=Performance, 2=Eval, 3=Inspect, 4=Simulate
    private final String[] TABS = {"Logs", "Performance", "Eval", "Inspect", "Simulate"};
    
    private int panelX, panelY, panelW, panelH;

    public DevConsoleScreen() {
        super(Text.literal("Developer Console"));
    }

    @Override
    protected void init() {
        panelW = 320;
        panelH = 200;
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        // Tab buttons
        int tabW = panelW / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            final int tabIndex = i;
            addDrawableChild(ButtonWidget.builder(Text.literal(TABS[i]), btn -> {
                activeTab = tabIndex;
                this.clearAndInit();
            }).dimensions(panelX + (i * tabW), panelY + 10, tabW - 2, 20).build());
        }

        // Add some action buttons based on tab
        int btnY = panelY + panelH - 30;
        if (activeTab == 0) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Generate Diagnostics"), btn -> {
                if (client != null && client.player != null) {
                    client.player.networkHandler.sendCommand("cb diagnostics");
                    this.close();
                }
            }).dimensions(panelX + 10, btnY, 140, 20).build());
        } else if (activeTab == 1) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Run GC"), btn -> {
                System.gc();
            }).dimensions(panelX + 10, btnY, 80, 20).build());
        } else if (activeTab == 4) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Panic Rollback Test"), btn -> {
                if (client != null && client.player != null) {
                    client.player.networkHandler.sendCommand("cb panic");
                    this.close();
                }
            }).dimensions(panelX + 10, btnY, 140, 20).build());
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), btn -> {
            this.close();
        }).dimensions(panelX + panelW - 60, btnY, 50, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        // Semi-transparent background overlay (like chat)
        renderBackground(ctx, mx, my, delta);
        
        // Draw main console background
        ctx.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xDD000000);
        ctx.drawBorder(panelX, panelY, panelW, panelH, 0xFF00FF00); // Matrix green border

        // Tab content area
        int contentY = panelY + 35;
        ctx.fill(panelX + 5, contentY, panelX + panelW - 5, panelY + panelH - 35, 0x88000000);

        ctx.drawTextWithShadow(textRenderer, "DEV CONSOLE - " + TABS[activeTab], panelX + 10, contentY + 5, 0xFF00FF00);

        if (activeTab == 0) {
            ctx.drawTextWithShadow(textRenderer, "> Tail of latest.log...", panelX + 10, contentY + 25, 0xFFAAAAAA);
            ctx.drawTextWithShadow(textRenderer, "Run '/cb diagnostics' for a full ZIP export.", panelX + 10, contentY + 40, 0xFFFFFF55);
        } else if (activeTab == 1) {
            Runtime rt = Runtime.getRuntime();
            long maxMem = rt.maxMemory() / 1024 / 1024;
            long totalMem = rt.totalMemory() / 1024 / 1024;
            long freeMem = rt.freeMemory() / 1024 / 1024;
            long usedMem = totalMem - freeMem;
            
            ctx.drawTextWithShadow(textRenderer, "> Memory Usage: " + usedMem + "MB / " + maxMem + "MB", panelX + 10, contentY + 25, 0xFF00FFFF);
            ctx.drawTextWithShadow(textRenderer, "> Active Chunk Buffers: ?", panelX + 10, contentY + 40, 0xFF00FFFF);
        } else if (activeTab == 2) {
            ctx.drawTextWithShadow(textRenderer, "> Eval REPL (Not connected)", panelX + 10, contentY + 25, 0xFFFF5555);
            ctx.drawTextWithShadow(textRenderer, "> JavaScript / Groovy engine required.", panelX + 10, contentY + 40, 0xFFAAAAAA);
        } else if (activeTab == 3) {
            ctx.drawTextWithShadow(textRenderer, "> Look at a CustomBlock to inspect NBT.", panelX + 10, contentY + 25, 0xFF55FF55);
            ctx.drawTextWithShadow(textRenderer, "> (Requires crosshair target)", panelX + 10, contentY + 40, 0xFFAAAAAA);
        } else if (activeTab == 4) {
            ctx.drawTextWithShadow(textRenderer, "> Simulate server-side load / packet storms", panelX + 10, contentY + 25, 0xFFFFAA00);
        }

        super.render(ctx, mx, my, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
