package com.customblocks.client.gui;

import com.customblocks.CustomBlocksMod;
import com.customblocks.SlotManager;
import com.customblocks.client.texture.TextureCache;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Environment(EnvType.CLIENT)
public class CustomBlocksScreen extends Screen {

    // ── Layout ────────────────────────────────────────────────────────────────
    private static final int CELL   = 68;
    private static final int GAP    = 5;
    private static final int PAD    = 10;
    private static final int RIGHT_W = 230;
    private int cols = 5;

    // ── Themes ────────────────────────────────────────────────────────────────
    private boolean darkTheme = true;

    private int cBg()        { return darkTheme ? 0xF2_0C0C14 : 0xF2_E8E8F0; }
    private int cPanel()     { return darkTheme ? 0xFF_141420 : 0xFF_D0D0E0; }
    private int cPanel2()    { return darkTheme ? 0xFF_0E0E1A : 0xFF_C0C0D8; }
    private int cBorder()    { return darkTheme ? 0xFF_222238 : 0xFF_8080A0; }
    private int cBorderHi()  { return darkTheme ? 0xFF_5555EE : 0xFF_2255CC; }
    private int cSelected()  { return darkTheme ? 0xFF_0E2010 : 0xFF_B0FFB0; }
    private int cSelBdr()    { return darkTheme ? 0xFF_44FF44 : 0xFF_008800; }
    private int cHovered()   { return darkTheme ? 0xFF_1A1A36 : 0xFF_D8D8FF; }
    private int cText()      { return darkTheme ? 0xFF_FFFFFF : 0xFF_111111; }
    private int cGrey()      { return darkTheme ? 0xFF_AAAAAA : 0xFF_444466; }
    private int cDim()       { return darkTheme ? 0xFF_555577 : 0xFF_888899; }
    private static final int C_GOLD   = 0xFF_FFD700;
    private static final int C_GREEN  = 0xFF_44FF44;
    private static final int C_RED    = 0xFF_FF4444;
    private static final int C_YELLOW = 0xFF_FFCC00;
    private static final int C_BLUE   = 0xFF_66AAFF;
    private static final int C_ORANGE = 0xFF_FF9900;
    private static final int C_GLOW   = 0xFF_FFE840;

    // ── Window geometry ───────────────────────────────────────────────────────
    private int px, py, pw, ph;
    private int gridW() { return cols * (CELL + GAP) - GAP; }

    // ── State ─────────────────────────────────────────────────────────────────
    private String selectedId = null;
    private int scroll = 0;
    private String search = "";
    private int sortMode = 0;
    private boolean sortAsc = true;
    private final List<SlotManager.SlotData> filtered = new ArrayList<>();
    private boolean bulkDeleteMode = false;
    private final List<String> bulkSelected = new ArrayList<>();

    private String statusMsg = "";
    private int statusColor = C_GREEN;
    private long statusUntil = 0;

    private enum Panel { NONE, CREATE, RENAME, RETEXTURE, PROPERTIES, URL_LIST }
    private Panel activePanel = Panel.NONE;

    // ── Right-panel buttons ───────────────────────────────────────────────────
    private ButtonWidget btnCreate, btnGive1, btnGive64, btnGivePlayer,
                         btnRename, btnRetexture, btnProperties,
                         btnCopyId, btnBulkDelete, btnUrlList,
                         btnExport, btnReload, btnDelete, btnTheme;

    // ── Sort / view ───────────────────────────────────────────────────────────
    private ButtonWidget btnSortName, btnSortSlot, btnSortGlow, btnSortSound,
                         btnSortDir, btnColsM, btnColsP;

    // ── CREATE panel ──────────────────────────────────────────────────────────
    private TextFieldWidget fldCreateId, fldCreateName, fldCreateUrl;
    private ButtonWidget    btnCreateOk, btnCreateCancel;

    // ── RENAME panel ──────────────────────────────────────────────────────────
    private TextFieldWidget fldRenameNew;
    private ButtonWidget    btnRenameOk, btnRenameCancel;

    // ── RETEXTURE panel ───────────────────────────────────────────────────────
    private TextFieldWidget fldRetextureUrl;
    private ButtonWidget    btnRetextureOk, btnRetextureCancel;

    // ── GIVE TO PLAYER panel (inline in right panel) ──────────────────────────
    private TextFieldWidget fldGivePlayer;
    private ButtonWidget    btnGivePlayerOk, btnGivePlayerCancel;
    private boolean         showGivePlayerPanel = false;

    // ── PROPERTIES panel ──────────────────────────────────────────────────────
    private ButtonWidget btnGlowM, btnGlowP;
    private ButtonWidget btnH0, btnHSoft, btnHNorm, btnHHard, btnHMax;
    private ButtonWidget btnSStone, btnSWood, btnSMetal, btnSGlass,
                         btnSGrass, btnSSand, btnSWool;
    private ButtonWidget btnPropClose;

    // ── URL LIST panel ────────────────────────────────────────────────────────
    // Format: one "id name url" per line (5 lines)
    private TextFieldWidget[] fldUrlLines = new TextFieldWidget[5];
    private ButtonWidget btnUrlOk, btnUrlCancel;

    // ── Search ────────────────────────────────────────────────────────────────
    private TextFieldWidget fldSearch;

    public CustomBlocksScreen() { super(Text.literal("Custom Blocks")); }

    // ── Init ──────────────────────────────────────────────────────────────────
    @Override
    protected void init() {
        activePanel = Panel.NONE;
        showGivePlayerPanel = false;
        bulkDeleteMode = false;
        bulkSelected.clear();

        pw = gridW() + RIGHT_W + PAD * 3;
        ph = Math.min(height - 16, 520);
        px = (width  - pw) / 2;
        py = (height - ph) / 2;

        rebuildFiltered();

        // Search
        fldSearch = new TextFieldWidget(textRenderer, px + PAD, py + PAD + 14, gridW() - 2, 16, Text.literal(""));
        fldSearch.setPlaceholder(Text.literal("Search blocks..."));
        fldSearch.setChangedListener(s -> { search = s; scroll = 0; rebuildFiltered(); });
        addDrawableChild(fldSearch);

        // Sort row
        int sy = py + PAD + 32;
        int sbase = px + PAD;
        int sw = (gridW() - 2 - 3*3 - 22 - 22 - 3) / 4;
        btnSortName  = mkBtn(sbase,             sy, sw,   "Name",  b -> setSort(0));
        btnSortSlot  = mkBtn(sbase + sw+3,      sy, sw,   "Slot",  b -> setSort(1));
        btnSortGlow  = mkBtn(sbase + (sw+3)*2,  sy, sw,   "Glow",  b -> setSort(2));
        btnSortSound = mkBtn(sbase + (sw+3)*3,  sy, sw,   "Sound", b -> setSort(3));
        btnSortDir   = mkBtn(sbase + (sw+3)*4+1,sy, 18,   "^",     b -> { sortAsc=!sortAsc; rebuildFiltered(); });
        btnColsM     = mkBtn(sbase + (sw+3)*4+22,sy, 18,  "-",     b -> { if(cols>3){cols--;reinit();} });
        btnColsP     = mkBtn(sbase + (sw+3)*4+43,sy, 18,  "+",     b -> { if(cols<8){cols++;reinit();} });
        for (ButtonWidget b : new ButtonWidget[]{btnSortName,btnSortSlot,btnSortGlow,btnSortSound,btnSortDir,btnColsM,btnColsP})
            addDrawableChild(b);

        // Right-panel buttons
        int bx = px + PAD + gridW() + PAD;
        int by = py + PAD + 14;
        int bw = RIGHT_W - PAD;
        int half = bw/2 - 2;
        btnTheme      = mkBtn(bx, by,       bw,    darkTheme ? "Light Mode" : "Dark Mode", b -> { darkTheme=!darkTheme; reinit(); });
        btnCreate     = mkBtn(bx, by+26,    bw,    "New Block",      b -> openPanel(Panel.CREATE));
        btnGive1      = mkBtn(bx, by+52,    half,  "Give x1",        b -> doGive(1, null));
        btnGive64     = mkBtn(bx+half+4,by+52,half,"Give x64",       b -> doGive(64, null));
        btnGivePlayer = mkBtn(bx, by+78,    bw,    "Give to Player", b -> toggleGivePlayerPanel());
        btnRename     = mkBtn(bx, by+104,   bw,    "Rename",         b -> openPanel(Panel.RENAME));
        btnRetexture  = mkBtn(bx, by+130,   bw,    "Change Texture", b -> openPanel(Panel.RETEXTURE));
        btnProperties = mkBtn(bx, by+156,   bw,    "Properties",     b -> openPanel(Panel.PROPERTIES));
        btnCopyId     = mkBtn(bx, by+182,   bw,    "Copy ID",        b -> doCopyId());
        btnUrlList    = mkBtn(bx, by+208,   half,  "URL Import",     b -> openPanel(Panel.URL_LIST));
        btnExport     = mkBtn(bx+half+4,by+208,half,"Export",        b -> doExport());
        btnBulkDelete = mkBtn(bx, by+234,   half,  "Bulk Delete",    b -> toggleBulkDelete());
        btnReload     = mkBtn(bx+half+4,by+234,half,"Reload Tex",    b -> doReloadTex());
        btnDelete     = mkBtn(bx, by+260,   bw,    "Delete Block",   b -> doDelete());

        for (ButtonWidget b : new ButtonWidget[]{btnTheme,btnCreate,btnGive1,btnGive64,
                btnGivePlayer,btnRename,btnRetexture,btnProperties,btnCopyId,
                btnUrlList,btnExport,btnBulkDelete,btnReload,btnDelete})
            addDrawableChild(b);
        updateButtonStates();

        // Give-to-player inline panel (under the button)
        fldGivePlayer    = mkField(bx, by+80, bw, "Player name");
        btnGivePlayerOk  = mkBtn(bx,       by+98, half, "Give",   b -> doGiveToPlayer());
        btnGivePlayerCancel = mkBtn(bx+half+4, by+98, half, "Cancel", b -> hideGivePlayerPanel());

        // Sub-panels (added to children only when opened)
        int spY = py + ph - 118;
        fldCreateId   = mkField(px+PAD, spY,      gridW()-2, "id — letters/numbers/underscores only");
        fldCreateName = mkField(px+PAD, spY+22,   gridW()-2, "Display Name");
        fldCreateUrl  = mkField(px+PAD, spY+44,   gridW()-2, "https://image-url.png");
        fldCreateUrl.setMaxLength(512);
        btnCreateOk     = mkBtn(px+PAD,      spY+66, 80,  "Create", b -> doCreate());
        btnCreateCancel = mkBtn(px+PAD+84,   spY+66, 70,  "Cancel", b -> closePanel());

        fldRenameNew    = mkField(px+PAD, spY+22,  gridW()-2, "New display name");
        btnRenameOk     = mkBtn(px+PAD,     spY+44, 80,  "Rename", b -> doRename());
        btnRenameCancel = mkBtn(px+PAD+84,  spY+44, 70,  "Cancel", b -> closePanel());

        fldRetextureUrl = mkField(px+PAD, spY+22, gridW()-2, "https://new-image.png");
        fldRetextureUrl.setMaxLength(512);
        btnRetextureOk     = mkBtn(px+PAD,    spY+44, 80, "Apply",  b -> doRetexture());
        btnRetextureCancel = mkBtn(px+PAD+84, spY+44, 70, "Cancel", b -> closePanel());

        // URL list panel (5 lines of "id name url")
        int ulY = spY - 10;
        for (int i = 0; i < 5; i++) {
            fldUrlLines[i] = mkField(px+PAD, ulY + i*20, gridW()-2, "id name https://url.png  (line "+(i+1)+")");
            fldUrlLines[i].setMaxLength(512);
        }
        btnUrlOk     = mkBtn(px+PAD,    ulY+102, 80,  "Import", b -> doUrlListImport());
        btnUrlCancel = mkBtn(px+PAD+84, ulY+102, 70,  "Cancel", b -> closePanel());

        // Properties panel
        int propY = by + 300;
        btnGlowM  = mkBtn(bx,      propY+18, 20, "-", b -> adjustGlow(-1));
        btnGlowP  = mkBtn(bx+140,  propY+18, 20, "+", b -> adjustGlow(+1));
        int hw = (bw-8)/5;
        btnH0     = mkBtn(bx,          propY+44, hw, "0",    b -> setHard(0f));
        btnHSoft  = mkBtn(bx+(hw+2),   propY+44, hw, "Soft", b -> setHard(0.5f));
        btnHNorm  = mkBtn(bx+(hw+2)*2, propY+44, hw, "Norm", b -> setHard(1.5f));
        btnHHard  = mkBtn(bx+(hw+2)*3, propY+44, hw, "Hard", b -> setHard(5.0f));
        btnHMax   = mkBtn(bx+(hw+2)*4, propY+44, hw, "MAX",  b -> setHard(-1f));
        int sw2 = (bw-12)/7;
        btnSStone = mkBtn(bx,          propY+70, sw2, "Stn", b -> setSound("stone"));
        btnSWood  = mkBtn(bx+(sw2+2),  propY+70, sw2, "Wd",  b -> setSound("wood"));
        btnSMetal = mkBtn(bx+(sw2+2)*2,propY+70, sw2, "Mtl", b -> setSound("metal"));
        btnSGlass = mkBtn(bx+(sw2+2)*3,propY+70, sw2, "Gls", b -> setSound("glass"));
        btnSGrass = mkBtn(bx+(sw2+2)*4,propY+70, sw2, "Grs", b -> setSound("grass"));
        btnSSand  = mkBtn(bx+(sw2+2)*5,propY+70, sw2, "Snd", b -> setSound("sand"));
        btnSWool  = mkBtn(bx+(sw2+2)*6,propY+70, sw2, "Wl",  b -> setSound("wool"));
        btnPropClose = mkBtn(bx, propY+96, bw, "Done", b -> closePanel());
    }

    private void reinit() { resize(client, this.width, this.height); }

    // ── Render ────────────────────────────────────────────────────────────────
    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx, mx, my, delta);

        // Outer glow
        ctx.fill(px-3,py-3,px+pw+3,py+ph+3, 0x33_5555FF);
        ctx.fill(px-1,py-1,px+pw+1,py+ph+1, cBorderHi());
        ctx.fill(px,py,px+pw,py+ph, cBg());

        // Title bar
        ctx.fillGradient(px,py,px+pw,py+16, darkTheme?0xFF_1A1A40:0xFF_4040AA, cBg());
        ctx.drawCenteredTextWithShadow(textRenderer, "Custom Blocks", px+pw/2, py+4, C_GOLD);

        // Divider
        ctx.fill(px+PAD+gridW()+PAD-1, py+16, px+PAD+gridW()+PAD, py+ph-4, cBorder());

        // Header labels
        ctx.drawTextWithShadow(textRenderer, "Blocks  (" + filtered.size() + " shown)", px+PAD, py+PAD+3, cGrey());
        ctx.drawTextWithShadow(textRenderer,
            "Sort: " + new String[]{"Name","Slot","Glow","Sound"}[sortMode] + (sortAsc?" ^":" v"),
            px+PAD+gridW()-55, py+PAD+3, cDim());

        // Slot usage bar
        int used=SlotManager.usedSlots(), max=SlotManager.MAX_SLOTS;
        float pct=used/(float)max;
        int barX=px+PAD, barY=py+ph-8, barW=gridW()-2;
        ctx.fill(barX,barY,barX+barW,barY+4, darkTheme?0xFF_1A1A2E:0xFF_AAAACC);
        int fillClr = pct<.6f?0xFF_44BB44:pct<.9f?C_YELLOW:C_RED;
        ctx.fill(barX,barY,barX+(int)(barW*pct),barY+4, fillClr);
        ctx.drawTextWithShadow(textRenderer, used+"/"+max+" slots", barX, barY-9, cDim());

        // Bulk delete banner
        if (bulkDeleteMode) {
            ctx.fill(px,py+ph-20,px+pw,py+ph, 0xCC_550000);
            ctx.drawCenteredTextWithShadow(textRenderer,
                "BULK DELETE MODE — click blocks to select ("+bulkSelected.size()+" selected) — press DELETE to confirm, ESC to cancel",
                px+pw/2, py+ph-16, C_RED);
        }

        drawGrid(ctx, mx, my);
        drawRightPanel(ctx, mx, my);

        // Status toast
        if (System.currentTimeMillis() < statusUntil)
            ctx.drawCenteredTextWithShadow(textRenderer, statusMsg, px+pw/2, py+ph+6, statusColor);

        drawActivePanel(ctx);
        super.render(ctx, mx, my, delta);
    }

    private void drawGrid(DrawContext ctx, int mx, int my) {
        int gx = px+PAD;
        int gy = py+52;    // below search + sort buttons
        int gh = py+ph-20;

        ctx.enableScissor(gx, gy, gx+gridW(), gh);

        for (int i=0; i<filtered.size(); i++) {
            int col = i%cols;
            int row = i/cols;
            int cx  = gx+col*(CELL+GAP);
            int cy  = gy+row*(CELL+GAP)-scroll;
            if (cy+CELL<gy || cy>gh) continue;

            SlotManager.SlotData data = filtered.get(i);
            boolean sel = bulkDeleteMode
                ? bulkSelected.contains(data.customId)
                : data.customId.equals(selectedId);
            boolean hov = mx>=cx&&mx<cx+CELL&&my>=cy&&my<cy+CELL;

            // Cell bg
            int bg = sel ? cSelected() : (hov ? cHovered() : cPanel());
            ctx.fill(cx,cy,cx+CELL,cy+CELL, bg);

            // Border
            int bdr = sel ? cSelBdr() : (hov ? cBorderHi() : cBorder());
            ctx.drawBorder(cx,cy,CELL,CELL,bdr);

            // Texture or colored placeholder
            int pad=4, tw=CELL-pad*2-12;
            if (data.texture != null && data.texture.length > 0) {
                TextureCache.TexInfo tex = TextureCache.getOrLoad(data.customId, data.texture);
                ctx.drawTexture(tex.id(), cx+pad, cy+pad, 0f, 0f, tw, tw, tex.width(), tex.height());
            } else {
                // Placeholder: colored tile + first letter
                int tileColor = stringToColor(data.customId);
                ctx.fill(cx+pad, cy+pad, cx+pad+tw, cy+pad+tw, tileColor);
                String ltr = data.displayName.isEmpty() ? "?" : String.valueOf(data.displayName.charAt(0)).toUpperCase();
                ctx.drawCenteredTextWithShadow(textRenderer, ltr, cx+pad+tw/2, cy+pad+tw/2-4, 0xFFFFFFFF);
            }

            // Badges
            if (data.lightLevel > 0) {
                ctx.fill(cx+CELL-15,cy,cx+CELL,cy+11, 0xDD_FFC800);
                ctx.drawTextWithShadow(textRenderer, String.valueOf(data.lightLevel), cx+CELL-13, cy+2, 0xFF_000000);
            }
            if (data.hardness < 0) {
                ctx.fill(cx,cy,cx+11,cy+11, 0xDD_FF3333);
                ctx.drawTextWithShadow(textRenderer, "U", cx+2, cy+2, 0xFF_FFFFFF);
            }

            // Name label
            String lbl = data.displayName.length()>8 ? data.displayName.substring(0,7)+"." : data.displayName;
            ctx.drawCenteredTextWithShadow(textRenderer, lbl, cx+CELL/2, cy+CELL-10, sel?C_GREEN:cText());

            // Sound dot (bottom-left)
            int soundColor = switch(data.soundType) {
                case "wood"  -> 0xFF_AA7733;
                case "metal" -> 0xFF_AABBCC;
                case "glass" -> 0xFF_88EEFF;
                case "grass" -> 0xFF_55CC55;
                case "sand"  -> 0xFF_DDCC88;
                case "wool"  -> 0xFF_FF88BB;
                default      -> 0xFF_888899;
            };
            ctx.fill(cx+2,cy+CELL-5,cx+6,cy+CELL-1, soundColor);
        }

        ctx.disableScissor();

        if (filtered.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                search.isEmpty() ? "No blocks yet — press New Block!" : "No match for \""+search+"\"",
                gx+gridW()/2, py+52+60, cGrey());
        }
    }

    private void drawRightPanel(DrawContext ctx, int mx, int my) {
        int rx = px+PAD+gridW()+PAD+2;
        int ry = py+PAD;
        int rw = RIGHT_W-PAD-4;

        SlotManager.SlotData data = selectedId!=null ? SlotManager.getById(selectedId) : null;

        // Header
        ctx.fill(rx-2, ry, rx+rw+2, ry+14, darkTheme?0xFF_0E0E28:0xFF_B0B0D8);
        ctx.drawCenteredTextWithShadow(textRenderer, data!=null ? data.displayName : "No Selection", rx+rw/2, ry+3, C_GOLD);

        if (data == null) {
            ctx.drawCenteredTextWithShadow(textRenderer, "select a block from the grid", rx+rw/2, ry+30, cDim());
            return;
        }

        // 3D item preview (uses baked model — shows actual in-game look)
        int pvX = rx + (rw-80)/2;
        int pvY = ry+18;
        ctx.fill(pvX-3,pvY-3,pvX+83,pvY+83, cBorder());
        ctx.fill(pvX-2,pvY-2,pvX+82,pvY+82, cPanel2());
        // Scale up 5x the 16x16 item render for a big crisp preview
        ctx.getMatrices().push();
        float scale = 5.0f;
        ctx.getMatrices().scale(scale, scale, 1f);
        ItemStack stack = new ItemStack(CustomBlocksMod.SLOT_ITEMS[data.index]);
        ctx.drawItem(stack, (int)((pvX)/scale), (int)((pvY)/scale));
        ctx.getMatrices().pop();

        // Info rows
        int iy = pvY+86;
        ctx.drawTextWithShadow(textRenderer, "ID: ", rx, iy, cDim());
        ctx.drawTextWithShadow(textRenderer, data.customId, rx+18, iy, cGrey());

        ctx.drawTextWithShadow(textRenderer, "Slot: " + data.index, rx, iy+11, cDim());

        // Properties bar
        int tagY = iy+24;
        ctx.fill(rx,tagY,rx+rw,tagY+30, darkTheme?0x33_FFFFFF:0x33_000000);

        String glowStr = data.lightLevel>0 ? "Glow "+data.lightLevel : "No glow";
        ctx.drawTextWithShadow(textRenderer, glowStr, rx+3, tagY+2, data.lightLevel>0?C_GLOW:cDim());

        String hardStr = data.hardness<0 ? "Unbreakable"
                       : data.hardness==0 ? "Instant"
                       : data.hardness<=0.5f ? "Soft"
                       : data.hardness<=2.5f ? "Normal" : "Hard";
        ctx.drawTextWithShadow(textRenderer, hardStr, rx+3, tagY+13, C_BLUE);
        ctx.drawTextWithShadow(textRenderer, cap(data.soundType), rx+rw-40, tagY+13, C_ORANGE);

        // Give-to-player panel
        if (showGivePlayerPanel) {
            int gpy2 = iy + 56;
            ctx.fill(rx-2, gpy2-2, rx+rw+2, gpy2+40, darkTheme?0xEE_0D1A0D:0xEE_C0D8C0);
            ctx.drawBorder(rx-2, gpy2-2, rw+4, 42, C_GREEN);
            ctx.drawTextWithShadow(textRenderer, "Player:", rx, gpy2, cGrey());
        }

        // Properties overlay
        if (activePanel==Panel.PROPERTIES) {
            int bx = rx-2;
            int propY = py+PAD+14 + 300;
            ctx.fill(bx, propY-18, bx+rw+4, propY+110, darkTheme?0xEE_0D0D22:0xEE_CCCCEE);
            ctx.drawBorder(bx, propY-18, rw+4, 128, cBorderHi());
            ctx.drawCenteredTextWithShadow(textRenderer, "Properties", rx+rw/2, propY-14, 0xFF_AADDFF);
            ctx.drawTextWithShadow(textRenderer, "Glow: "+data.lightLevel+" / 15", rx, propY+2, C_GLOW);
            ctx.drawTextWithShadow(textRenderer, "Hardness:", rx, propY+30, C_BLUE);
            ctx.drawTextWithShadow(textRenderer, "Sound:", rx, propY+56, C_ORANGE);
        }
    }

    private void drawActivePanel(DrawContext ctx) {
        if (activePanel==Panel.NONE||activePanel==Panel.PROPERTIES) return;
        int spY = py+ph-118;
        int spW = gridW()+2;
        int bdrColor = 0;
        String title = "";
        switch (activePanel) {
            case CREATE -> { bdrColor=cBorderHi(); title="Create New Block"; }
            case RENAME -> { bdrColor=0xFF_44AA44; title="Rename: "+selectedId; }
            case RETEXTURE -> { bdrColor=C_ORANGE; title="Change Texture: "+selectedId; }
            case URL_LIST -> { bdrColor=0xFF_AA44AA; title="URL List Import  (format: id name https://url.png)"; spY=py+ph-130; }
            default -> { return; }
        }
        int top = activePanel==Panel.URL_LIST ? spY-12 : spY-16;
        ctx.fill(px+PAD-2, top, px+PAD+spW, py+ph-4, darkTheme?0xEE_0D0D1E:0xEE_DDDDFF);
        ctx.fill(px+PAD-2, top, px+PAD+spW, top+1, bdrColor);
        ctx.fill(px+PAD-2, py+ph-5, px+PAD+spW, py+ph-4, bdrColor);
        ctx.drawTextWithShadow(textRenderer, title, px+PAD, top+3, bdrColor);
        if (activePanel==Panel.CREATE) {
            ctx.drawTextWithShadow(textRenderer, "ID:",    px+PAD, spY-2,  cGrey());
            ctx.drawTextWithShadow(textRenderer, "Name:",  px+PAD, spY+20, cGrey());
            ctx.drawTextWithShadow(textRenderer, "URL:",   px+PAD, spY+42, cGrey());
        }
        if (activePanel==Panel.RENAME)    ctx.drawTextWithShadow(textRenderer, "New name:", px+PAD, spY+20, cGrey());
        if (activePanel==Panel.RETEXTURE) ctx.drawTextWithShadow(textRenderer, "Image URL:", px+PAD, spY+20, cGrey());
        if (activePanel==Panel.URL_LIST) {
            for (int i=0;i<5;i++)
                ctx.drawTextWithShadow(textRenderer, (i+1)+":", px+PAD, spY+i*20-2, cDim());
        }
    }

    // ── Input ─────────────────────────────────────────────────────────────────
    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        int gx=px+PAD, gy=py+52, gh=py+ph-20;
        if (activePanel==Panel.NONE||activePanel==Panel.PROPERTIES) {
            if (mx>=gx&&mx<gx+gridW()&&my>=gy&&my<gh) {
                int col=((int)mx-gx)/(CELL+GAP);
                int row=((int)my-gy+scroll)/(CELL+GAP);
                int idx=row*cols+col;
                if (col<cols&&idx>=0&&idx<filtered.size()) {
                    String id=filtered.get(idx).customId;
                    if (bulkDeleteMode) {
                        if (bulkSelected.contains(id)) bulkSelected.remove(id);
                        else bulkSelected.add(id);
                    } else {
                        selectedId=id;
                        if (activePanel==Panel.PROPERTIES) closePanel();
                        updateButtonStates();
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        int rows=(int)Math.ceil(filtered.size()/(double)cols);
        int gy=py+52, gh=py+ph-20;
        int vis=(gh-gy)/(CELL+GAP);
        scroll=(int)Math.max(0,Math.min(Math.max(0,rows-vis)*(CELL+GAP), scroll-vy*(CELL+GAP)));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        // ESC
        if (key==256) {
            if (bulkDeleteMode) { bulkDeleteMode=false; bulkSelected.clear(); return true; }
            if (showGivePlayerPanel) { hideGivePlayerPanel(); return true; }
            if (activePanel!=Panel.NONE) { closePanel(); return true; }
            close(); return true;
        }
        // DELETE key in bulk mode
        if (key==261 && bulkDeleteMode && !bulkSelected.isEmpty()) {
            doConfirmBulkDelete();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    // ── Panel management ──────────────────────────────────────────────────────
    private void openPanel(Panel p) {
        closePanel();
        activePanel=p;
        switch (p) {
            case CREATE -> {
                fldCreateId.setText(""); fldCreateName.setText(""); fldCreateUrl.setText("");
                addDrawableChild(fldCreateId); addDrawableChild(fldCreateName);
                addDrawableChild(fldCreateUrl); addDrawableChild(btnCreateOk); addDrawableChild(btnCreateCancel);
                setFocused(fldCreateId);
            }
            case RENAME -> {
                SlotManager.SlotData d=SlotManager.getById(selectedId);
                fldRenameNew.setText(d!=null?d.displayName:"");
                addDrawableChild(fldRenameNew); addDrawableChild(btnRenameOk); addDrawableChild(btnRenameCancel);
                setFocused(fldRenameNew);
            }
            case RETEXTURE -> {
                fldRetextureUrl.setText("");
                addDrawableChild(fldRetextureUrl); addDrawableChild(btnRetextureOk); addDrawableChild(btnRetextureCancel);
                setFocused(fldRetextureUrl);
            }
            case URL_LIST -> {
                for (TextFieldWidget f : fldUrlLines) { f.setText(""); addDrawableChild(f); }
                addDrawableChild(btnUrlOk); addDrawableChild(btnUrlCancel);
                setFocused(fldUrlLines[0]);
            }
            case PROPERTIES -> {
                for (ButtonWidget b : new ButtonWidget[]{btnGlowM,btnGlowP,btnH0,btnHSoft,btnHNorm,
                        btnHHard,btnHMax,btnSStone,btnSWood,btnSMetal,btnSGlass,btnSGrass,
                        btnSSand,btnSWool,btnPropClose})
                    addDrawableChild(b);
            }
        }
    }

    private void closePanel() {
        activePanel=Panel.NONE;
        remove(fldCreateId); remove(fldCreateName); remove(fldCreateUrl);
        remove(btnCreateOk); remove(btnCreateCancel);
        remove(fldRenameNew); remove(btnRenameOk); remove(btnRenameCancel);
        remove(fldRetextureUrl); remove(btnRetextureOk); remove(btnRetextureCancel);
        for (TextFieldWidget f : fldUrlLines) remove(f);
        remove(btnUrlOk); remove(btnUrlCancel);
        remove(btnGlowM); remove(btnGlowP);
        remove(btnH0); remove(btnHSoft); remove(btnHNorm); remove(btnHHard); remove(btnHMax);
        remove(btnSStone); remove(btnSWood); remove(btnSMetal); remove(btnSGlass);
        remove(btnSGrass); remove(btnSSand); remove(btnSWool); remove(btnPropClose);
    }

    private void toggleGivePlayerPanel() {
        if (selectedId==null) return;
        showGivePlayerPanel=!showGivePlayerPanel;
        if (showGivePlayerPanel) {
            fldGivePlayer.setText("");
            addDrawableChild(fldGivePlayer); addDrawableChild(btnGivePlayerOk); addDrawableChild(btnGivePlayerCancel);
            setFocused(fldGivePlayer);
        } else {
            hideGivePlayerPanel();
        }
    }

    private void hideGivePlayerPanel() {
        showGivePlayerPanel=false;
        remove(fldGivePlayer); remove(btnGivePlayerOk); remove(btnGivePlayerCancel);
    }

    private void toggleBulkDelete() {
        bulkDeleteMode=!bulkDeleteMode;
        bulkSelected.clear();
        btnBulkDelete.setMessage(Text.literal(bulkDeleteMode?"Cancel Bulk":"Bulk Delete"));
        closePanel();
    }

    // ── Actions ───────────────────────────────────────────────────────────────
    private void doCreate() {
        String id=fldCreateId.getText().trim().toLowerCase().replaceAll("[^a-z0-9_]","_");
        String name=fldCreateName.getText().trim();
        String url=fldCreateUrl.getText().trim();
        if (id.isEmpty())   { status("Enter an ID!",C_RED); return; }
        if (name.isEmpty()) { status("Enter a name!",C_RED); return; }
        if (url.isEmpty())  { status("Paste a URL!",C_RED); return; }
        if (name.contains("_")) { status("Name cannot contain underscores — use spaces.",C_RED); return; }
        if (SlotManager.hasId(id)) { status("'"+id+"' already exists!",C_RED); return; }
        if (SlotManager.freeSlots()==0) { status("All "+SlotManager.MAX_SLOTS+" slots full!",C_RED); return; }
        closePanel();
        status("Downloading...",C_YELLOW);
        // name uses _ as word separator in the command (server replaces _ back to spaces)
        send("customblock createurl "+id+" "+name.replace(" ","_")+" "+url);
    }

    private void doRename() {
        if (selectedId==null) return;
        String name=fldRenameNew.getText().trim();
        if (name.isEmpty()) { status("Enter a name!",C_RED); return; }
        if (name.contains("_")) { status("Name cannot contain underscores — use spaces.",C_RED); return; }
        closePanel();
        send("customblock rename "+selectedId+" "+name.replace(" ","_"));
        status("Renamed!",C_GREEN);
        rebuildFiltered();
    }

    private void doRetexture() {
        if (selectedId==null) return;
        String url=fldRetextureUrl.getText().trim();
        if (url.isEmpty()) { status("Paste a URL!",C_RED); return; }
        closePanel();
        send("customblock retexture "+selectedId+" "+url);
        status("Downloading texture...",C_YELLOW);
    }

    private void doGive(int amount, String player) {
        if (selectedId==null) return;
        String cmd="customblock give "+selectedId+" "+amount;
        if (player!=null&&!player.isEmpty()) cmd+=" "+player;
        send(cmd);
        status("Gave "+amount+"x "+selectedId+(player!=null?" to "+player:""),C_GREEN);
    }

    private void doGiveToPlayer() {
        if (selectedId==null) return;
        String player=fldGivePlayer.getText().trim();
        if (player.isEmpty()) { status("Enter player name!",C_RED); return; }
        doGive(1, player);
        hideGivePlayerPanel();
    }

    private void doDelete() {
        if (selectedId==null) return;
        String idToDelete = selectedId;
        send("customblock delete "+idToDelete);
        status("Deleted '"+idToDelete+"'",C_RED);
        // Optimistic client-side removal so block disappears immediately from grid
        // (server will confirm via "remove" SlotUpdatePayload shortly after)
        TextureCache.invalidate(idToDelete);
        SlotManager.remove(idToDelete);
        selectedId=null;
        rebuildFiltered();
        updateButtonStates();
    }

    private void doConfirmBulkDelete() {
        for (String id : new ArrayList<>(bulkSelected)) {
            send("customblock delete "+id);
            TextureCache.invalidate(id);
        }
        status("Deleted "+bulkSelected.size()+" blocks",C_RED);
        bulkSelected.clear();
        bulkDeleteMode=false;
        btnBulkDelete.setMessage(Text.literal("Bulk Delete"));
        selectedId=null;
        rebuildFiltered();
        updateButtonStates();
    }

    private void doCopyId() {
        if (selectedId==null) return;
        client.keyboard.setClipboard(selectedId);
        status("Copied: "+selectedId,C_GREEN);
    }

    private void doExport() {
        send("customblock export");
        status("Exported to config/customblocks/export.json",C_GREEN);
    }

    private void doReloadTex() {
        TextureCache.invalidateAll();
        status("All textures cleared — will reload on next render",C_YELLOW);
    }

    private void doUrlListImport() {
        int count=0;
        for (TextFieldWidget f : fldUrlLines) {
            String line=f.getText().trim();
            if (line.isEmpty()) continue;
            String[] parts=line.split("\\s+",3);
            if (parts.length<3) { status("Line format: id name url",C_RED); return; }
            String id=parts[0].toLowerCase().replaceAll("[^a-z0-9_]","_");
            String name=parts[1].replace("_"," ");
            String url=parts[2];
            if (SlotManager.hasId(id)) continue;
            send("customblock createurl "+id+" "+name.replace(" ","_")+" "+url);
            count++;
        }
        closePanel();
        status("Queued "+count+" download(s)...",C_YELLOW);
    }

    private void adjustGlow(int d) {
        if (selectedId==null) return;
        SlotManager.SlotData data=SlotManager.getById(selectedId);
        if (data==null) return;
        send("customblock setglow "+selectedId+" "+Math.max(0,Math.min(15,data.lightLevel+d)));
    }
    private void setHard(float v) { if (selectedId!=null) send("customblock sethardness "+selectedId+" "+v); }
    private void setSound(String t) { if (selectedId!=null) send("customblock setsound "+selectedId+" "+t); }
    private void setSort(int m) {
        if (sortMode==m) sortAsc=!sortAsc; else { sortMode=m; sortAsc=true; }
        rebuildFiltered();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private void rebuildFiltered() {
        filtered.clear();
        String q=search.toLowerCase();
        for (SlotManager.SlotData d : SlotManager.allSlots()) {
            if (d.customId.equals("tab_icon")) continue; // never show tab_icon in grid
            if (q.isEmpty()||d.customId.contains(q)||d.displayName.toLowerCase().contains(q))
                filtered.add(d);
        }
        Comparator<SlotManager.SlotData> cmp = switch(sortMode) {
            case 1 -> Comparator.comparingInt(d -> d.index);
            case 2 -> Comparator.comparingInt((SlotManager.SlotData d)->d.lightLevel).reversed();
            case 3 -> Comparator.comparing(d -> d.soundType);
            default -> Comparator.comparing(d -> d.displayName.toLowerCase());
        };
        if (!sortAsc) cmp=cmp.reversed();
        filtered.sort(cmp);
    }

    private void updateButtonStates() {
        boolean has=selectedId!=null&&SlotManager.getById(selectedId)!=null;
        for (ButtonWidget b : new ButtonWidget[]{btnGive1,btnGive64,btnGivePlayer,btnRename,
                btnRetexture,btnProperties,btnCopyId,btnDelete})
            b.active=has;
    }

    /** Deterministic colour from string — used for no-texture placeholder tiles */
    private static int stringToColor(String s) {
        int hash=s.hashCode();
        int r=60+((hash>>16)&0xFF)%120;
        int g=60+((hash>>8)&0xFF)%120;
        int b=60+(hash&0xFF)%120;
        return 0xFF_000000|(r<<16)|(g<<8)|b;
    }

    private void status(String msg, int color) {
        statusMsg=msg; statusColor=color; statusUntil=System.currentTimeMillis()+3500;
    }

    private void send(String cmd) {
        if (client.player == null || client.player.networkHandler == null) {
            status("Not connected to server!", C_RED);
            return;
        }
        client.player.networkHandler.sendChatCommand(cmd);
    }

    private static String cap(String s) {
        return (s==null||s.isEmpty())?"":Character.toUpperCase(s.charAt(0))+s.substring(1);
    }

    private ButtonWidget mkBtn(int x,int y,int w,String lbl,ButtonWidget.PressAction a) {
        return ButtonWidget.builder(Text.literal(lbl),a).dimensions(x,y,w,20).build();
    }

    private TextFieldWidget mkField(int x,int y,int w,String placeholder) {
        TextFieldWidget f=new TextFieldWidget(textRenderer,x,y,w,16,Text.literal(""));
        f.setPlaceholder(Text.literal(placeholder));
        return f;
    }

    @Override public boolean shouldPause() { return false; }
}
