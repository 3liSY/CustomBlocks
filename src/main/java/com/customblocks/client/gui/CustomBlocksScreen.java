// 
// Decompiled by Procyon v0.6.0
// 

package com.customblocks.client.gui;

import java.lang.invoke.CallSite;
import java.lang.reflect.UndeclaredThrowableException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.StringConcatFactory;
import java.lang.invoke.MethodType;
import java.lang.invoke.MethodHandles;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Collection;
import net.minecraft.class_1935;
import net.minecraft.class_1799;
import com.customblocks.CustomBlocksMod;
import com.customblocks.client.texture.TextureCache;
import net.minecraft.class_332;
import net.minecraft.class_364;
import java.util.ArrayList;
import net.minecraft.class_2561;
import net.minecraft.class_342;
import net.minecraft.class_4185;
import com.customblocks.SlotManager;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.class_437;

@Environment(EnvType.CLIENT)
public class CustomBlocksScreen extends class_437
{
    private static final int CELL = 68;
    private static final int GAP = 5;
    private static final int PAD = 10;
    private static final int RIGHT_W = 230;
    private int cols;
    private boolean darkTheme;
    private static final int C_GOLD = -10496;
    private static final int C_GREEN = -12255420;
    private static final int C_RED = -48060;
    private static final int C_YELLOW = -13312;
    private static final int C_BLUE = -10048769;
    private static final int C_ORANGE = -26368;
    private static final int C_GLOW = -6080;
    private int px;
    private int py;
    private int pw;
    private int ph;
    private String selectedId;
    private int scroll;
    private String search;
    private int sortMode;
    private boolean sortAsc;
    private final List<SlotManager.SlotData> filtered;
    private boolean bulkDeleteMode;
    private final List<String> bulkSelected;
    private String statusMsg;
    private int statusColor;
    private long statusUntil;
    private Panel activePanel;
    private class_4185 btnCreate;
    private class_4185 btnGive1;
    private class_4185 btnGive64;
    private class_4185 btnGivePlayer;
    private class_4185 btnRename;
    private class_4185 btnRetexture;
    private class_4185 btnProperties;
    private class_4185 btnCopyId;
    private class_4185 btnBulkDelete;
    private class_4185 btnUrlList;
    private class_4185 btnExport;
    private class_4185 btnReload;
    private class_4185 btnDelete;
    private class_4185 btnTheme;
    private class_4185 btnSortName;
    private class_4185 btnSortSlot;
    private class_4185 btnSortGlow;
    private class_4185 btnSortSound;
    private class_4185 btnSortDir;
    private class_4185 btnColsM;
    private class_4185 btnColsP;
    private class_342 fldCreateId;
    private class_342 fldCreateName;
    private class_342 fldCreateUrl;
    private class_4185 btnCreateOk;
    private class_4185 btnCreateCancel;
    private class_342 fldRenameNew;
    private class_4185 btnRenameOk;
    private class_4185 btnRenameCancel;
    private class_342 fldRetextureUrl;
    private class_4185 btnRetextureOk;
    private class_4185 btnRetextureCancel;
    private class_342 fldGivePlayer;
    private class_4185 btnGivePlayerOk;
    private class_4185 btnGivePlayerCancel;
    private boolean showGivePlayerPanel;
    private class_4185 btnGlowM;
    private class_4185 btnGlowP;
    private class_4185 btnH0;
    private class_4185 btnHSoft;
    private class_4185 btnHNorm;
    private class_4185 btnHHard;
    private class_4185 btnHMax;
    private class_4185 btnSStone;
    private class_4185 btnSWood;
    private class_4185 btnSMetal;
    private class_4185 btnSGlass;
    private class_4185 btnSGrass;
    private class_4185 btnSSand;
    private class_4185 btnSWool;
    private class_4185 btnPropClose;
    private class_342[] fldUrlLines;
    private class_4185 btnUrlOk;
    private class_4185 btnUrlCancel;
    private class_342 fldSearch;
    
    private int cBg() {
        return this.darkTheme ? -234091500 : -219617040;
    }
    
    private int cPanel() {
        return this.darkTheme ? -15461344 : -3092256;
    }
    
    private int cPanel2() {
        return this.darkTheme ? -15856102 : -4144936;
    }
    
    private int cBorder() {
        return this.darkTheme ? -14540232 : -8355680;
    }
    
    private int cBorderHi() {
        return this.darkTheme ? -11184658 : -14527028;
    }
    
    private int cSelected() {
        return this.darkTheme ? -15851504 : -5177424;
    }
    
    private int cSelBdr() {
        return this.darkTheme ? -12255420 : -16742400;
    }
    
    private int cHovered() {
        return this.darkTheme ? -15066570 : -2565889;
    }
    
    private int cText() {
        return this.darkTheme ? -1 : -15658735;
    }
    
    private int cGrey() {
        return this.darkTheme ? -5592406 : -12303258;
    }
    
    private int cDim() {
        return this.darkTheme ? -11184777 : -7829351;
    }
    
    private int gridW() {
        return this.cols * 73 - 5;
    }
    
    public CustomBlocksScreen() {
        super((class_2561)class_2561.method_43470("Custom Blocks"));
        this.cols = 5;
        this.darkTheme = true;
        this.selectedId = null;
        this.scroll = 0;
        this.search = "";
        this.sortMode = 0;
        this.sortAsc = true;
        this.filtered = new ArrayList<SlotManager.SlotData>();
        this.bulkDeleteMode = false;
        this.bulkSelected = new ArrayList<String>();
        this.statusMsg = "";
        this.statusColor = -12255420;
        this.statusUntil = 0L;
        this.activePanel = Panel.NONE;
        this.showGivePlayerPanel = false;
        this.fldUrlLines = new class_342[5];
    }
    
    protected void method_25426() {
        this.activePanel = Panel.NONE;
        this.showGivePlayerPanel = false;
        this.bulkDeleteMode = false;
        this.bulkSelected.clear();
        this.pw = this.gridW() + 230 + 30;
        this.ph = Math.min(this.field_22790 - 16, 520);
        this.px = (this.field_22789 - this.pw) / 2;
        this.py = (this.field_22790 - this.ph) / 2;
        this.rebuildFiltered();
        (this.fldSearch = new class_342(this.field_22793, this.px + 10, this.py + 10 + 14, this.gridW() - 2, 16, (class_2561)class_2561.method_43470(""))).method_47404((class_2561)class_2561.method_43470("Search blocks..."));
        this.fldSearch.method_1863(s -> {
            this.search = s;
            this.scroll = 0;
            this.rebuildFiltered();
            return;
        });
        this.method_37063((class_364)this.fldSearch);
        final int sy = this.py + 10 + 32;
        final int sbase = this.px + 10;
        final int sw = (this.gridW() - 2 - 9 - 22 - 22 - 3) / 4;
        this.btnSortName = this.mkBtn(sbase, sy, sw, "Name", b -> this.setSort(0));
        this.btnSortSlot = this.mkBtn(sbase + sw + 3, sy, sw, "Slot", b -> this.setSort(1));
        this.btnSortGlow = this.mkBtn(sbase + (sw + 3) * 2, sy, sw, "Glow", b -> this.setSort(2));
        this.btnSortSound = this.mkBtn(sbase + (sw + 3) * 3, sy, sw, "Sound", b -> this.setSort(3));
        this.btnSortDir = this.mkBtn(sbase + (sw + 3) * 4 + 1, sy, 18, "^", b -> {
            this.sortAsc = !this.sortAsc;
            this.rebuildFiltered();
        });
        this.btnColsM = this.mkBtn(sbase + (sw + 3) * 4 + 22, sy, 18, "-", b -> {
            if (this.cols > 3) {
                --this.cols;
                this.reinit();
            }
        });
        this.btnColsP = this.mkBtn(sbase + (sw + 3) * 4 + 43, sy, 18, "+", b -> {
            if (this.cols < 8) {
                ++this.cols;
                this.reinit();
            }
        });
        for (final class_4185 b : new class_4185[] { this.btnSortName, this.btnSortSlot, this.btnSortGlow, this.btnSortSound, this.btnSortDir, this.btnColsM, this.btnColsP }) {
            this.method_37063((class_364)b);
        }
        final int bx = this.px + 10 + this.gridW() + 10;
        final int by = this.py + 10 + 14;
        final int bw = 220;
        final int half = bw / 2 - 2;
        this.btnTheme = this.mkBtn(bx, by, bw, this.darkTheme ? "Light Mode" : "Dark Mode", b -> {
            this.darkTheme = !this.darkTheme;
            this.reinit();
        });
        this.btnCreate = this.mkBtn(bx, by + 26, bw, "New Block", b -> this.openPanel(Panel.CREATE));
        this.btnGive1 = this.mkBtn(bx, by + 52, half, "Give x1", b -> this.doGive(1, null));
        this.btnGive64 = this.mkBtn(bx + half + 4, by + 52, half, "Give x64", b -> this.doGive(64, null));
        this.btnGivePlayer = this.mkBtn(bx, by + 78, bw, "Give to Player", b -> this.toggleGivePlayerPanel());
        this.btnRename = this.mkBtn(bx, by + 104, bw, "Rename", b -> this.openPanel(Panel.RENAME));
        this.btnRetexture = this.mkBtn(bx, by + 130, bw, "Change Texture", b -> this.openPanel(Panel.RETEXTURE));
        this.btnProperties = this.mkBtn(bx, by + 156, bw, "Properties", b -> this.openPanel(Panel.PROPERTIES));
        this.btnCopyId = this.mkBtn(bx, by + 182, bw, "Copy ID", b -> this.doCopyId());
        this.btnUrlList = this.mkBtn(bx, by + 208, half, "URL Import", b -> this.openPanel(Panel.URL_LIST));
        this.btnExport = this.mkBtn(bx + half + 4, by + 208, half, "Export", b -> this.doExport());
        this.btnBulkDelete = this.mkBtn(bx, by + 234, half, "Bulk Delete", b -> this.toggleBulkDelete());
        this.btnReload = this.mkBtn(bx + half + 4, by + 234, half, "Reload Tex", b -> this.doReloadTex());
        this.btnDelete = this.mkBtn(bx, by + 260, bw, "Delete Block", b -> this.doDelete());
        for (final class_4185 b2 : new class_4185[] { this.btnTheme, this.btnCreate, this.btnGive1, this.btnGive64, this.btnGivePlayer, this.btnRename, this.btnRetexture, this.btnProperties, this.btnCopyId, this.btnUrlList, this.btnExport, this.btnBulkDelete, this.btnReload, this.btnDelete }) {
            this.method_37063((class_364)b2);
        }
        this.updateButtonStates();
        this.fldGivePlayer = this.mkField(bx, by + 80, bw, "Player name");
        this.btnGivePlayerOk = this.mkBtn(bx, by + 98, half, "Give", b -> this.doGiveToPlayer());
        this.btnGivePlayerCancel = this.mkBtn(bx + half + 4, by + 98, half, "Cancel", b -> this.hideGivePlayerPanel());
        final int spY = this.py + this.ph - 118;
        this.fldCreateId = this.mkField(this.px + 10, spY, this.gridW() - 2, "id \u2014 letters/numbers/underscores only");
        this.fldCreateName = this.mkField(this.px + 10, spY + 22, this.gridW() - 2, "Display Name");
        (this.fldCreateUrl = this.mkField(this.px + 10, spY + 44, this.gridW() - 2, "https://image-url.png")).method_1880(512);
        this.btnCreateOk = this.mkBtn(this.px + 10, spY + 66, 80, "Create", b -> this.doCreate());
        this.btnCreateCancel = this.mkBtn(this.px + 10 + 84, spY + 66, 70, "Cancel", b -> this.closePanel());
        this.fldRenameNew = this.mkField(this.px + 10, spY + 22, this.gridW() - 2, "New display name");
        this.btnRenameOk = this.mkBtn(this.px + 10, spY + 44, 80, "Rename", b -> this.doRename());
        this.btnRenameCancel = this.mkBtn(this.px + 10 + 84, spY + 44, 70, "Cancel", b -> this.closePanel());
        (this.fldRetextureUrl = this.mkField(this.px + 10, spY + 22, this.gridW() - 2, "https://new-image.png")).method_1880(512);
        this.btnRetextureOk = this.mkBtn(this.px + 10, spY + 44, 80, "Apply", b -> this.doRetexture());
        this.btnRetextureCancel = this.mkBtn(this.px + 10 + 84, spY + 44, 70, "Cancel", b -> this.closePanel());
        final int ulY = spY - 10;
        for (int i = 0; i < 5; ++i) {
            (this.fldUrlLines[i] = this.mkField(this.px + 10, ulY + i * 20, this.gridW() - 2, "id name https://url.png  (line " + (i + 1))).method_1880(512);
        }
        this.btnUrlOk = this.mkBtn(this.px + 10, ulY + 102, 80, "Import", b -> this.doUrlListImport());
        this.btnUrlCancel = this.mkBtn(this.px + 10 + 84, ulY + 102, 70, "Cancel", b -> this.closePanel());
        final int propY = by + 300;
        this.btnGlowM = this.mkBtn(bx, propY + 18, 20, "-", b -> this.adjustGlow(-1));
        this.btnGlowP = this.mkBtn(bx + 140, propY + 18, 20, "+", b -> this.adjustGlow(1));
        final int hw = (bw - 8) / 5;
        this.btnH0 = this.mkBtn(bx, propY + 44, hw, "0", b -> this.setHard(0.0f));
        this.btnHSoft = this.mkBtn(bx + (hw + 2), propY + 44, hw, "Soft", b -> this.setHard(0.5f));
        this.btnHNorm = this.mkBtn(bx + (hw + 2) * 2, propY + 44, hw, "Norm", b -> this.setHard(1.5f));
        this.btnHHard = this.mkBtn(bx + (hw + 2) * 3, propY + 44, hw, "Hard", b -> this.setHard(5.0f));
        this.btnHMax = this.mkBtn(bx + (hw + 2) * 4, propY + 44, hw, "MAX", b -> this.setHard(-1.0f));
        final int sw2 = (bw - 12) / 7;
        this.btnSStone = this.mkBtn(bx, propY + 70, sw2, "Stn", b -> this.setSound("stone"));
        this.btnSWood = this.mkBtn(bx + (sw2 + 2), propY + 70, sw2, "Wd", b -> this.setSound("wood"));
        this.btnSMetal = this.mkBtn(bx + (sw2 + 2) * 2, propY + 70, sw2, "Mtl", b -> this.setSound("metal"));
        this.btnSGlass = this.mkBtn(bx + (sw2 + 2) * 3, propY + 70, sw2, "Gls", b -> this.setSound("glass"));
        this.btnSGrass = this.mkBtn(bx + (sw2 + 2) * 4, propY + 70, sw2, "Grs", b -> this.setSound("grass"));
        this.btnSSand = this.mkBtn(bx + (sw2 + 2) * 5, propY + 70, sw2, "Snd", b -> this.setSound("sand"));
        this.btnSWool = this.mkBtn(bx + (sw2 + 2) * 6, propY + 70, sw2, "Wl", b -> this.setSound("wool"));
        this.btnPropClose = this.mkBtn(bx, propY + 96, bw, "Done", b -> this.closePanel());
    }
    
    private void reinit() {
        this.method_25410(this.field_22787, this.field_22789, this.field_22790);
    }
    
    public void method_25394(final class_332 ctx, final int mx, final int my, final float delta) {
        this.method_25420(ctx, mx, my, delta);
        ctx.method_25294(this.px - 3, this.py - 3, this.px + this.pw + 3, this.py + this.ph + 3, 861230591);
        ctx.method_25294(this.px - 1, this.py - 1, this.px + this.pw + 1, this.py + this.ph + 1, this.cBorderHi());
        ctx.method_25294(this.px, this.py, this.px + this.pw, this.py + this.ph, this.cBg());
        ctx.method_25296(this.px, this.py, this.px + this.pw, this.py + 16, this.darkTheme ? -15066560 : -12566358, this.cBg());
        ctx.method_25300(this.field_22793, "Custom Blocks", this.px + this.pw / 2, this.py + 4, -10496);
        ctx.method_25294(this.px + 10 + this.gridW() + 10 - 1, this.py + 16, this.px + 10 + this.gridW() + 10, this.py + this.ph - 4, this.cBorder());
        ctx.method_25303(this.field_22793, "Blocks  (" + this.filtered.size() + " shown)", this.px + 10, this.py + 10 + 3, this.cGrey());
        ctx.method_25303(this.field_22793, "Sort: " + (new String[] { "Name", "Slot", "Glow", "Sound" })[this.sortMode] + (this.sortAsc ? " ^" : " v"), this.px + 10 + this.gridW() - 55, this.py + 10 + 3, this.cDim());
        final int used = SlotManager.usedSlots();
        final int max = 512;
        final float pct = used / (float)max;
        final int barX = this.px + 10;
        final int barY = this.py + this.ph - 8;
        final int barW = this.gridW() - 2;
        ctx.method_25294(barX, barY, barX + barW, barY + 4, this.darkTheme ? -15066578 : -5592372);
        final int fillClr = (pct < 0.6f) ? -12272828 : ((pct < 0.9f) ? -13312 : -48060);
        ctx.method_25294(barX, barY, barX + (int)(barW * pct), barY + 4, fillClr);
        ctx.method_25303(this.field_22793, used + "/" + max + " slots", barX, barY - 9, this.cDim());
        if (this.bulkDeleteMode) {
            ctx.method_25294(this.px, this.py + this.ph - 20, this.px + this.pw, this.py + this.ph, -866844672);
            ctx.method_25300(this.field_22793, "BULK DELETE MODE \u2014 click blocks to select (" + this.bulkSelected.size() + " selected) \u2014 press DELETE to confirm, ESC to cancel", this.px + this.pw / 2, this.py + this.ph - 16, -48060);
        }
        this.drawGrid(ctx, mx, my);
        this.drawRightPanel(ctx, mx, my);
        if (System.currentTimeMillis() < this.statusUntil) {
            ctx.method_25300(this.field_22793, this.statusMsg, this.px + this.pw / 2, this.py + this.ph + 6, this.statusColor);
        }
        this.drawActivePanel(ctx);
        super.method_25394(ctx, mx, my, delta);
    }
    
    private void drawGrid(final class_332 ctx, final int mx, final int my) {
        final int gx = this.px + 10;
        final int gy = this.py + 52;
        final int gh = this.py + this.ph - 20;
        ctx.method_44379(gx, gy, gx + this.gridW(), gh);
        for (int i = 0; i < this.filtered.size(); ++i) {
            final int col = i % this.cols;
            final int row = i / this.cols;
            final int cx = gx + col * 73;
            final int cy = gy + row * 73 - this.scroll;
            if (cy + 68 >= gy) {
                if (cy <= gh) {
                    final SlotManager.SlotData data = this.filtered.get(i);
                    final boolean sel = this.bulkDeleteMode ? this.bulkSelected.contains(data.customId) : data.customId.equals(this.selectedId);
                    final boolean hov = mx >= cx && mx < cx + 68 && my >= cy && my < cy + 68;
                    final int bg = sel ? this.cSelected() : (hov ? this.cHovered() : this.cPanel());
                    ctx.method_25294(cx, cy, cx + 68, cy + 68, bg);
                    final int bdr = sel ? this.cSelBdr() : (hov ? this.cBorderHi() : this.cBorder());
                    ctx.method_49601(cx, cy, 68, 68, bdr);
                    final int pad = 4;
                    final int tw = 68 - pad * 2 - 12;
                    if (data.texture != null && data.texture.length > 0) {
                        final TextureCache.TexInfo tex = TextureCache.getOrLoad(data.customId, data.texture);
                        ctx.method_25290(tex.id(), cx + pad, cy + pad, 0.0f, 0.0f, tw, tw, tex.width(), tex.height());
                    }
                    else {
                        final int tileColor = stringToColor(data.customId);
                        ctx.method_25294(cx + pad, cy + pad, cx + pad + tw, cy + pad + tw, tileColor);
                        final String ltr = data.displayName.isEmpty() ? "?" : String.valueOf(data.displayName.charAt(0)).toUpperCase();
                        ctx.method_25300(this.field_22793, ltr, cx + pad + tw / 2, cy + pad + tw / 2 - 4, -1);
                    }
                    if (data.lightLevel > 0) {
                        ctx.method_25294(cx + 68 - 15, cy, cx + 68, cy + 11, -570439680);
                        ctx.method_25303(this.field_22793, String.valueOf(data.lightLevel), cx + 68 - 13, cy + 2, -16777216);
                    }
                    if (data.hardness < 0.0f) {
                        ctx.method_25294(cx, cy, cx + 11, cy + 11, -570477773);
                        ctx.method_25303(this.field_22793, "U", cx + 2, cy + 2, -1);
                    }
                    final String lbl = (data.displayName.length() > 8) ? data.displayName.substring(0, 7) : data.displayName;
                    ctx.method_25300(this.field_22793, lbl, cx + 34, cy + 68 - 10, sel ? -12255420 : this.cText());
                    final String soundType = data.soundType;
                    final int soundColor = switch (soundType) {
                        case "wood" -> -5605581;
                        case "metal" -> -5588020;
                        case "glass" -> -7803137;
                        case "grass" -> -11154347;
                        case "sand" -> -2241400;
                        case "wool" -> -30533;
                        default -> -7829351;
                    };
                    ctx.method_25294(cx + 2, cy + 68 - 5, cx + 6, cy + 68 - 1, soundColor);
                }
            }
        }
        ctx.method_44380();
        if (this.filtered.isEmpty()) {
            ctx.method_25300(this.field_22793, this.search.isEmpty() ? "No blocks yet \u2014 press New Block!" : ("No match for \"" + this.search), gx + this.gridW() / 2, this.py + 52 + 60, this.cGrey());
        }
    }
    
    private void drawRightPanel(final class_332 ctx, final int mx, final int my) {
        final int rx = this.px + 10 + this.gridW() + 10 + 2;
        final int ry = this.py + 10;
        final int rw = 216;
        final SlotManager.SlotData data = (this.selectedId != null) ? SlotManager.getById(this.selectedId) : null;
        ctx.method_25294(rx - 2, ry, rx + rw + 2, ry + 14, this.darkTheme ? -15856088 : -5197608);
        ctx.method_25300(this.field_22793, (data != null) ? data.displayName : "No Selection", rx + rw / 2, ry + 3, -10496);
        if (data == null) {
            ctx.method_25300(this.field_22793, "select a block from the grid", rx + rw / 2, ry + 30, this.cDim());
            return;
        }
        final int pvX = rx + (rw - 80) / 2;
        final int pvY = ry + 18;
        ctx.method_25294(pvX - 3, pvY - 3, pvX + 83, pvY + 83, this.cBorder());
        ctx.method_25294(pvX - 2, pvY - 2, pvX + 82, pvY + 82, this.cPanel2());
        ctx.method_51448().method_22903();
        final float scale = 5.0f;
        ctx.method_51448().method_22905(scale, scale, 1.0f);
        final class_1799 stack = new class_1799((class_1935)CustomBlocksMod.SLOT_ITEMS[data.index]);
        ctx.method_51427(stack, (int)(pvX / scale), (int)(pvY / scale));
        ctx.method_51448().method_22909();
        final int iy = pvY + 86;
        ctx.method_25303(this.field_22793, "ID: ", rx, iy, this.cDim());
        ctx.method_25303(this.field_22793, data.customId, rx + 18, iy, this.cGrey());
        ctx.method_25303(this.field_22793, "Slot: " + data.index, rx, iy + 11, this.cDim());
        final int tagY = iy + 24;
        ctx.method_25294(rx, tagY, rx + rw, tagY + 30, this.darkTheme ? 872415231 : 855638016);
        final String glowStr = (data.lightLevel > 0) ? ("Glow " + data.lightLevel) : "No glow";
        ctx.method_25303(this.field_22793, glowStr, rx + 3, tagY + 2, (data.lightLevel > 0) ? -6080 : this.cDim());
        final String hardStr = (data.hardness < 0.0f) ? "Unbreakable" : ((data.hardness == 0.0f) ? "Instant" : ((data.hardness <= 0.5f) ? "Soft" : ((data.hardness <= 2.5f) ? "Normal" : "Hard")));
        ctx.method_25303(this.field_22793, hardStr, rx + 3, tagY + 13, -10048769);
        ctx.method_25303(this.field_22793, cap(data.soundType), rx + rw - 40, tagY + 13, -26368);
        if (this.showGivePlayerPanel) {
            final int gpy2 = iy + 56;
            ctx.method_25294(rx - 2, gpy2 - 2, rx + rw + 2, gpy2 + 40, this.darkTheme ? -301131251 : -289351488);
            ctx.method_49601(rx - 2, gpy2 - 2, rw + 4, 42, -12255420);
            ctx.method_25303(this.field_22793, "Player:", rx, gpy2, this.cGrey());
        }
        if (this.activePanel == Panel.PROPERTIES) {
            final int bx = rx - 2;
            final int propY = this.py + 10 + 14 + 300;
            ctx.method_25294(bx, propY - 18, bx + rw + 4, propY + 110, this.darkTheme ? -301134558 : -288568082);
            ctx.method_49601(bx, propY - 18, rw + 4, 128, this.cBorderHi());
            ctx.method_25300(this.field_22793, "Properties", rx + rw / 2, propY - 14, -5579265);
            ctx.method_25303(this.field_22793, "Glow: " + data.lightLevel + " / 15", rx, propY + 2, -6080);
            ctx.method_25303(this.field_22793, "Hardness:", rx, propY + 30, -10048769);
            ctx.method_25303(this.field_22793, "Sound:", rx, propY + 56, -26368);
        }
    }
    
    private void drawActivePanel(final class_332 ctx) {
        if (this.activePanel == Panel.NONE || this.activePanel == Panel.PROPERTIES) {
            return;
        }
        int spY = this.py + this.ph - 118;
        final int spW = this.gridW() + 2;
        int bdrColor = 0;
        String title = "";
        switch (this.activePanel.ordinal()) {
            case 1: {
                bdrColor = this.cBorderHi();
                title = "Create New Block";
                break;
            }
            case 2: {
                bdrColor = -12277180;
                title = "Rename: " + this.selectedId;
                break;
            }
            case 3: {
                bdrColor = -26368;
                title = "Change Texture: " + this.selectedId;
                break;
            }
            case 5: {
                bdrColor = -5618518;
                title = "URL List Import  (format: id name https://url.png)";
                spY = this.py + this.ph - 130;
                break;
            }
            default: {
                return;
            }
        }
        final int top = (this.activePanel == Panel.URL_LIST) ? (spY - 12) : (spY - 16);
        ctx.method_25294(this.px + 10 - 2, top, this.px + 10 + spW, this.py + this.ph - 4, this.darkTheme ? -301134562 : -287449601);
        ctx.method_25294(this.px + 10 - 2, top, this.px + 10 + spW, top + 1, bdrColor);
        ctx.method_25294(this.px + 10 - 2, this.py + this.ph - 5, this.px + 10 + spW, this.py + this.ph - 4, bdrColor);
        ctx.method_25303(this.field_22793, title, this.px + 10, top + 3, bdrColor);
        if (this.activePanel == Panel.CREATE) {
            ctx.method_25303(this.field_22793, "ID:", this.px + 10, spY - 2, this.cGrey());
            ctx.method_25303(this.field_22793, "Name:", this.px + 10, spY + 20, this.cGrey());
            ctx.method_25303(this.field_22793, "URL:", this.px + 10, spY + 42, this.cGrey());
        }
        if (this.activePanel == Panel.RENAME) {
            ctx.method_25303(this.field_22793, "New name:", this.px + 10, spY + 20, this.cGrey());
        }
        if (this.activePanel == Panel.RETEXTURE) {
            ctx.method_25303(this.field_22793, "Image URL:", this.px + 10, spY + 20, this.cGrey());
        }
        if (this.activePanel == Panel.URL_LIST) {
            for (int i = 0; i < 5; ++i) {
                ctx.method_25303(this.field_22793, "" + (i + 1), this.px + 10, spY + i * 20 - 2, this.cDim());
            }
        }
    }
    
    public boolean method_25402(final double mx, final double my, final int btn) {
        final int gx = this.px + 10;
        final int gy = this.py + 52;
        final int gh = this.py + this.ph - 20;
        if ((this.activePanel == Panel.NONE || this.activePanel == Panel.PROPERTIES) && mx >= gx && mx < gx + this.gridW() && my >= gy && my < gh) {
            final int col = ((int)mx - gx) / 73;
            final int row = ((int)my - gy + this.scroll) / 73;
            final int idx = row * this.cols + col;
            if (col < this.cols && idx >= 0 && idx < this.filtered.size()) {
                final String id = this.filtered.get(idx).customId;
                if (this.bulkDeleteMode) {
                    if (this.bulkSelected.contains(id)) {
                        this.bulkSelected.remove(id);
                    }
                    else {
                        this.bulkSelected.add(id);
                    }
                }
                else {
                    this.selectedId = id;
                    if (this.activePanel == Panel.PROPERTIES) {
                        this.closePanel();
                    }
                    this.updateButtonStates();
                }
                return true;
            }
        }
        return super.method_25402(mx, my, btn);
    }
    
    public boolean method_25401(final double mx, final double my, final double hx, final double vy) {
        final int rows = (int)Math.ceil(this.filtered.size() / (double)this.cols);
        final int gy = this.py + 52;
        final int gh = this.py + this.ph - 20;
        final int vis = (gh - gy) / 73;
        this.scroll = (int)Math.max(0.0, Math.min(Math.max(0, rows - vis) * 73, this.scroll - vy * 73.0));
        return true;
    }
    
    public boolean method_25404(final int key, final int scan, final int mods) {
        if (key == 256) {
            if (this.bulkDeleteMode) {
                this.bulkDeleteMode = false;
                this.bulkSelected.clear();
                return true;
            }
            if (this.showGivePlayerPanel) {
                this.hideGivePlayerPanel();
                return true;
            }
            if (this.activePanel != Panel.NONE) {
                this.closePanel();
                return true;
            }
            this.method_25419();
            return true;
        }
        else {
            if (key == 261 && this.bulkDeleteMode && !this.bulkSelected.isEmpty()) {
                this.doConfirmBulkDelete();
                return true;
            }
            return super.method_25404(key, scan, mods);
        }
    }
    
    private void openPanel(final Panel p) {
        this.closePanel();
        this.activePanel = p;
        switch (p.ordinal()) {
            case 1: {
                this.fldCreateId.method_1852("");
                this.fldCreateName.method_1852("");
                this.fldCreateUrl.method_1852("");
                this.method_37063((class_364)this.fldCreateId);
                this.method_37063((class_364)this.fldCreateName);
                this.method_37063((class_364)this.fldCreateUrl);
                this.method_37063((class_364)this.btnCreateOk);
                this.method_37063((class_364)this.btnCreateCancel);
                this.method_25395((class_364)this.fldCreateId);
                break;
            }
            case 2: {
                final SlotManager.SlotData d = SlotManager.getById(this.selectedId);
                this.fldRenameNew.method_1852((d != null) ? d.displayName : "");
                this.method_37063((class_364)this.fldRenameNew);
                this.method_37063((class_364)this.btnRenameOk);
                this.method_37063((class_364)this.btnRenameCancel);
                this.method_25395((class_364)this.fldRenameNew);
                break;
            }
            case 3: {
                this.fldRetextureUrl.method_1852("");
                this.method_37063((class_364)this.fldRetextureUrl);
                this.method_37063((class_364)this.btnRetextureOk);
                this.method_37063((class_364)this.btnRetextureCancel);
                this.method_25395((class_364)this.fldRetextureUrl);
                break;
            }
            case 5: {
                for (final class_342 f : this.fldUrlLines) {
                    f.method_1852("");
                    this.method_37063((class_364)f);
                }
                this.method_37063((class_364)this.btnUrlOk);
                this.method_37063((class_364)this.btnUrlCancel);
                this.method_25395((class_364)this.fldUrlLines[0]);
                break;
            }
            case 4: {
                for (final class_4185 b : new class_4185[] { this.btnGlowM, this.btnGlowP, this.btnH0, this.btnHSoft, this.btnHNorm, this.btnHHard, this.btnHMax, this.btnSStone, this.btnSWood, this.btnSMetal, this.btnSGlass, this.btnSGrass, this.btnSSand, this.btnSWool, this.btnPropClose }) {
                    this.method_37063((class_364)b);
                }
                break;
            }
        }
    }
    
    private void closePanel() {
        this.activePanel = Panel.NONE;
        this.method_37066((class_364)this.fldCreateId);
        this.method_37066((class_364)this.fldCreateName);
        this.method_37066((class_364)this.fldCreateUrl);
        this.method_37066((class_364)this.btnCreateOk);
        this.method_37066((class_364)this.btnCreateCancel);
        this.method_37066((class_364)this.fldRenameNew);
        this.method_37066((class_364)this.btnRenameOk);
        this.method_37066((class_364)this.btnRenameCancel);
        this.method_37066((class_364)this.fldRetextureUrl);
        this.method_37066((class_364)this.btnRetextureOk);
        this.method_37066((class_364)this.btnRetextureCancel);
        for (final class_342 f : this.fldUrlLines) {
            this.method_37066((class_364)f);
        }
        this.method_37066((class_364)this.btnUrlOk);
        this.method_37066((class_364)this.btnUrlCancel);
        this.method_37066((class_364)this.btnGlowM);
        this.method_37066((class_364)this.btnGlowP);
        this.method_37066((class_364)this.btnH0);
        this.method_37066((class_364)this.btnHSoft);
        this.method_37066((class_364)this.btnHNorm);
        this.method_37066((class_364)this.btnHHard);
        this.method_37066((class_364)this.btnHMax);
        this.method_37066((class_364)this.btnSStone);
        this.method_37066((class_364)this.btnSWood);
        this.method_37066((class_364)this.btnSMetal);
        this.method_37066((class_364)this.btnSGlass);
        this.method_37066((class_364)this.btnSGrass);
        this.method_37066((class_364)this.btnSSand);
        this.method_37066((class_364)this.btnSWool);
        this.method_37066((class_364)this.btnPropClose);
    }
    
    private void toggleGivePlayerPanel() {
        if (this.selectedId == null) {
            return;
        }
        this.showGivePlayerPanel = !this.showGivePlayerPanel;
        if (this.showGivePlayerPanel) {
            this.fldGivePlayer.method_1852("");
            this.method_37063((class_364)this.fldGivePlayer);
            this.method_37063((class_364)this.btnGivePlayerOk);
            this.method_37063((class_364)this.btnGivePlayerCancel);
            this.method_25395((class_364)this.fldGivePlayer);
        }
        else {
            this.hideGivePlayerPanel();
        }
    }
    
    private void hideGivePlayerPanel() {
        this.showGivePlayerPanel = false;
        this.method_37066((class_364)this.fldGivePlayer);
        this.method_37066((class_364)this.btnGivePlayerOk);
        this.method_37066((class_364)this.btnGivePlayerCancel);
    }
    
    private void toggleBulkDelete() {
        this.bulkDeleteMode = !this.bulkDeleteMode;
        this.bulkSelected.clear();
        this.btnBulkDelete.method_25355((class_2561)class_2561.method_43470(this.bulkDeleteMode ? "Cancel Bulk" : "Bulk Delete"));
        this.closePanel();
    }
    
    private void doCreate() {
        final String id = this.fldCreateId.method_1882().trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
        final String name = this.fldCreateName.method_1882().trim();
        final String url = this.fldCreateUrl.method_1882().trim();
        if (id.isEmpty()) {
            this.status("Enter an ID!", -48060);
            return;
        }
        if (name.isEmpty()) {
            this.status("Enter a name!", -48060);
            return;
        }
        if (url.isEmpty()) {
            this.status("Paste a URL!", -48060);
            return;
        }
        if (name.contains("_")) {
            this.status("Name cannot contain underscores \u2014 use spaces.", -48060);
            return;
        }
        if (SlotManager.hasId(id)) {
            this.status("'" + id + "' already exists!", -48060);
            return;
        }
        if (SlotManager.freeSlots() == 0) {
            this.status("All 512 slots full!", -48060);
            return;
        }
        this.closePanel();
        this.status("Downloading...", -13312);
        this.send("customblock createurl " + id + " " + name.replace(" ", "_") + " " + url);
    }
    
    private void doRename() {
        if (this.selectedId == null) {
            return;
        }
        final String name = this.fldRenameNew.method_1882().trim();
        if (name.isEmpty()) {
            this.status("Enter a name!", -48060);
            return;
        }
        if (name.contains("_")) {
            this.status("Name cannot contain underscores \u2014 use spaces.", -48060);
            return;
        }
        this.closePanel();
        this.send("customblock rename " + this.selectedId + " " + name.replace(" ", "_"));
        this.status("Renamed!", -12255420);
        this.rebuildFiltered();
    }
    
    private void doRetexture() {
        if (this.selectedId == null) {
            return;
        }
        final String url = this.fldRetextureUrl.method_1882().trim();
        if (url.isEmpty()) {
            this.status("Paste a URL!", -48060);
            return;
        }
        this.closePanel();
        this.send("customblock retexture " + this.selectedId + " " + url);
        this.status("Downloading texture...", -13312);
    }
    
    private void doGive(final int amount, final String player) {
        if (this.selectedId == null) {
            return;
        }
        String cmd = "customblock give " + this.selectedId + " " + amount;
        if (player != null && !player.isEmpty()) {
            cmd = cmd + " " + player;
        }
        this.send(cmd);
        this.status("Gave " + amount + "x " + this.selectedId + ((player != null) ? /* invokedynamic(!) */ProcyonInvokeDynamicHelper_1.invoke(player) : ""), -12255420);
    }
    
    private void doGiveToPlayer() {
        if (this.selectedId == null) {
            return;
        }
        final String player = this.fldGivePlayer.method_1882().trim();
        if (player.isEmpty()) {
            this.status("Enter player name!", -48060);
            return;
        }
        this.doGive(1, player);
        this.hideGivePlayerPanel();
    }
    
    private void doDelete() {
        if (this.selectedId == null) {
            return;
        }
        final String idToDelete = this.selectedId;
        this.send("customblock delete " + idToDelete);
        this.status("Deleted '" + idToDelete, -48060);
        TextureCache.invalidate(idToDelete);
        SlotManager.remove(idToDelete);
        this.selectedId = null;
        this.rebuildFiltered();
        this.updateButtonStates();
    }
    
    private void doConfirmBulkDelete() {
        for (String id : new ArrayList(this.bulkSelected)) {
            this.send("customblock delete " + id);
            TextureCache.invalidate(id);
        }
        this.status("Deleted " + this.bulkSelected.size() + " blocks", -48060);
        this.bulkSelected.clear();
        this.bulkDeleteMode = false;
        this.btnBulkDelete.method_25355((class_2561)class_2561.method_43470("Bulk Delete"));
        this.selectedId = null;
        this.rebuildFiltered();
        this.updateButtonStates();
    }
    
    private void doCopyId() {
        if (this.selectedId == null) {
            return;
        }
        this.field_22787.field_1774.method_1455(this.selectedId);
        this.status("Copied: " + this.selectedId, -12255420);
    }
    
    private void doExport() {
        this.send("customblock export");
        this.status("Exported to config/customblocks/export.json", -12255420);
    }
    
    private void doReloadTex() {
        TextureCache.invalidateAll();
        this.status("All textures cleared \u2014 will reload on next render", -13312);
    }
    
    private void doUrlListImport() {
        int count = 0;
        final class_342[] fldUrlLines = this.fldUrlLines;
        for (int length = fldUrlLines.length, i = 0; i < length; ++i) {
            final class_342 f = fldUrlLines[i];
            final String line = f.method_1882().trim();
            if (!line.isEmpty()) {
                final String[] parts = line.split("\\s+", 3);
                if (parts.length < 3) {
                    this.status("Line format: id name url", -48060);
                    return;
                }
                final String id = parts[0].toLowerCase().replaceAll("[^a-z0-9_]", "_");
                final String name = parts[1].replace("_", " ");
                final String url = parts[2];
                if (!SlotManager.hasId(id)) {
                    this.send("customblock createurl " + id + " " + name.replace(" ", "_") + " " + url);
                    ++count;
                }
            }
        }
        this.closePanel();
        this.status("Queued " + count + " download(s)...", -13312);
    }
    
    private void adjustGlow(final int d) {
        if (this.selectedId == null) {
            return;
        }
        final SlotManager.SlotData data = SlotManager.getById(this.selectedId);
        if (data == null) {
            return;
        }
        this.send("customblock setglow " + this.selectedId + " " + Math.max(0, Math.min(15, data.lightLevel + d)));
    }
    
    private void setHard(final float v) {
        if (this.selectedId != null) {
            this.send("customblock sethardness " + this.selectedId + " " + v);
        }
    }
    
    private void setSound(final String t) {
        if (this.selectedId != null) {
            this.send("customblock setsound " + this.selectedId + " " + t);
        }
    }
    
    private void setSort(final int m) {
        if (this.sortMode == m) {
            this.sortAsc = !this.sortAsc;
        }
        else {
            this.sortMode = m;
            this.sortAsc = true;
        }
        this.rebuildFiltered();
    }
    
    private void rebuildFiltered() {
        this.filtered.clear();
        final String q = this.search.toLowerCase();
        final Iterator<SlotManager.SlotData> iterator = SlotManager.allSlots().iterator();
        SlotManager.SlotData d = null;
        while (iterator.hasNext()) {
            d = iterator.next();
            if (d.customId.equals("tab_icon")) {
                continue;
            }
            if (!q.isEmpty() && !d.customId.contains(q) && !d.displayName.toLowerCase().contains(q)) {
                continue;
            }
            this.filtered.add(d);
        }
        Comparator<SlotManager.SlotData> cmp = switch (this.sortMode) {
            case 1 -> Comparator.comparingInt(d -> d.index);
            case 2 -> Comparator.comparingInt(d -> d.lightLevel).reversed();
            case 3 -> Comparator.comparing(d -> d.soundType);
            default -> Comparator.comparing(d -> d.displayName.toLowerCase());
        };
        if (!this.sortAsc) {
            cmp = cmp.reversed();
        }
        this.filtered.sort(cmp);
    }
    
    private void updateButtonStates() {
        final boolean has = this.selectedId != null && SlotManager.getById(this.selectedId) != null;
        for (final class_4185 b : new class_4185[] { this.btnGive1, this.btnGive64, this.btnGivePlayer, this.btnRename, this.btnRetexture, this.btnProperties, this.btnCopyId, this.btnDelete }) {
            b.field_22763 = has;
        }
    }
    
    private static int stringToColor(final String s) {
        final int hash = s.hashCode();
        final int r = 60 + (hash >> 16 & 0xFF) % 120;
        final int g = 60 + (hash >> 8 & 0xFF) % 120;
        final int b = 60 + (hash & 0xFF) % 120;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
    
    private void status(final String msg, final int color) {
        this.statusMsg = msg;
        this.statusColor = color;
        this.statusUntil = System.currentTimeMillis() + 3500L;
    }
    
    private void send(final String cmd) {
        if (this.field_22787.field_1724 == null || this.field_22787.field_1724.field_3944 == null) {
            this.status("Not connected to server!", -48060);
            return;
        }
        this.field_22787.field_1724.field_3944.method_45730(cmd);
    }
    
    private static String cap(final String s) {
        return (s == null || s.isEmpty()) ? "" : (Character.toUpperCase(s.charAt(0)) + s.substring(1));
    }
    
    private class_4185 mkBtn(final int x, final int y, final int w, final String lbl, final class_4185.class_4241 a) {
        return class_4185.method_46430((class_2561)class_2561.method_43470(lbl), a).method_46434(x, y, w, 20).method_46431();
    }
    
    private class_342 mkField(final int x, final int y, final int w, final String placeholder) {
        final class_342 f = new class_342(this.field_22793, x, y, w, 16, (class_2561)class_2561.method_43470(""));
        f.method_47404((class_2561)class_2561.method_43470(placeholder));
        return f;
    }
    
    public boolean method_25421() {
        return false;
    }
    
    private enum Panel
    {
        NONE, 
        CREATE, 
        RENAME, 
        RETEXTURE, 
        PROPERTIES, 
        URL_LIST;
    }
    
    // This helper class was generated by Procyon to approximate the behavior of an
    // 'invokedynamic' instruction that it doesn't know how to interpret.
    private static final class ProcyonInvokeDynamicHelper_1
    {
        private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
        private static MethodHandle handle;
        private static volatile int fence;
        
        private static MethodHandle handle() {
            final MethodHandle handle = ProcyonInvokeDynamicHelper_1.handle;
            if (handle != null)
                return handle;
            return ProcyonInvokeDynamicHelper_1.ensureHandle();
        }
        
        private static MethodHandle ensureHandle() {
            ProcyonInvokeDynamicHelper_1.fence = 0;
            MethodHandle handle = ProcyonInvokeDynamicHelper_1.handle;
            if (handle == null) {
                MethodHandles.Lookup lookup = ProcyonInvokeDynamicHelper_1.LOOKUP;
                try {
                    handle = ((CallSite)StringConcatFactory.makeConcatWithConstants(lookup, "makeConcatWithConstants", MethodType.methodType(String.class, String.class), " to \u0001")).dynamicInvoker();
                }
                catch (Throwable t) {
                    throw new UndeclaredThrowableException(t);
                }
                ProcyonInvokeDynamicHelper_1.fence = 1;
                ProcyonInvokeDynamicHelper_1.handle = handle;
                ProcyonInvokeDynamicHelper_1.fence = 0;
            }
            return handle;
        }
        
        private static String invoke(String p0) {
            try {
                return ProcyonInvokeDynamicHelper_1.handle().invokeExact(p0);
            }
            catch (Throwable t) {
                throw new UndeclaredThrowableException(t);
            }
        }
    }
}
