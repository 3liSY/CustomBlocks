package com.customblocks.gui;

import com.customblocks.CustomBlocksMod;
import com.customblocks.ImageProcessor;
import com.customblocks.SlotManager;
import com.customblocks.block.SlotBlock;
import com.customblocks.network.SlotUpdatePayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GuiManager {

    public enum GuiMode { MAIN, EDITOR, FACE_EDITOR, SHAPE_EDITOR, ADMIN_GUI, ANIM_GUI }

    public record GuiState(GuiMode mode, int page, String editingId, boolean confirmDelete,
                           int shapeBoxPage, boolean fromCommand) {
        static GuiState main(int page)                     { return new GuiState(GuiMode.MAIN,         page, null,         false, 0, false); }
        static GuiState picker(int page)                   { return new GuiState(GuiMode.MAIN,         page, "__picker__", false, 0, false); }
        static GuiState pickerTabIcon(int page)            { return new GuiState(GuiMode.MAIN,         page, "__picker_tabicon__", false, 0, false); }
        static GuiState pickerBroken(int page)             { return new GuiState(GuiMode.MAIN,         page, "__picker_broken__", false, 0, false); }
        static GuiState editor(String id, int p, boolean cmd) { return new GuiState(GuiMode.EDITOR,    p,    id,           false, 0, cmd);   }
        static GuiState faceEditor(String id, int p)       { return new GuiState(GuiMode.FACE_EDITOR,  p,    id,           false, 0, false); }
        static GuiState shapeEditor(String id, int p)      { return new GuiState(GuiMode.SHAPE_EDITOR, p,    id,           false, 0, false); }
        static GuiState adminGui()                         { return new GuiState(GuiMode.ADMIN_GUI,    0,    null,         false, 0, false); }
        static GuiState animGui(String id)                 { return new GuiState(GuiMode.ANIM_GUI,     0,    id,           false, 0, false); }
        public GuiState withConfirmDelete()                { return new GuiState(mode, page, editingId, true,  shapeBoxPage, fromCommand); }
        public GuiState withShapeBoxPage(int bp)           { return new GuiState(mode, page, editingId, confirmDelete, bp, fromCommand); }
    }

    private record AnimParams(float fps, boolean interpolate, int frameCount) {}
    private static final Map<UUID, AnimParams> ANIM_PARAMS = new ConcurrentHashMap<>();

    public enum InputAction {
        CREATE_ID, CREATE_NAME, CREATE_URL,
        RETEXTURE_URL, SETFACE_URL, RENAME_TEXT,
        SETFACE_VARIANT_URL,
        SETTABICON_URL,
        ADDSHAPE_COORDS,
        REID_TEXT,
        ADMIN_CUSTOM_TITLE
    }

    public record PendingInput(InputAction action, String blockId, String face,
                               String partialId, String partialName, int returnPage) {}

    private static final Map<UUID, GuiState>       STATES    = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingInput>   PENDING   = new ConcurrentHashMap<>();
    private static final Set<UUID>  REOPENING_SCREENS        = ConcurrentHashMap.newKeySet();
    /** Tracks the active CbScreenHandler per player for in-place refresh (no cursor jump). */
    private static final Map<UUID, CbScreenHandler> HANDLERS = new ConcurrentHashMap<>();

    private static final float[] HARD_CYCLE      = { -1f, 0f, 0.5f, 1.5f, 3f, 5f, 10f, 50f };
    private static final int     BLOCKS_PER_PAGE = 18;
    private static final String[] PRESET_NAMES   = {"full","slab","thin","carpet","pillar","small","micro","pane","trapdoor","fence","stairs","cross"};

    // ── Screen open helpers ──────────────────────────────────────────────────

    private static void openScreen(ServerPlayerEntity player, SimpleNamedScreenHandlerFactory factory) {
        REOPENING_SCREENS.add(player.getUuid());
        player.openHandledScreen(factory);
        if (player.currentScreenHandler instanceof CbScreenHandler h) {
            HANDLERS.put(player.getUuid(), h);
        }
        REOPENING_SCREENS.remove(player.getUuid());
    }

    /**
     * Refresh the current screen IN PLACE — updates slot stacks without reopening,
     * so the player's mouse cursor stays exactly where it is.
     * Falls back to a full openScreen() if the handler has changed.
     */
    private static void refreshScreen(ServerPlayerEntity player, SimpleInventory newInv) {
        CbScreenHandler h = HANDLERS.get(player.getUuid());
        if (h != null && !h.isDisposed() && player.currentScreenHandler == h) {
            h.refreshWith(newInv);
        }
        // If disposed or mismatched we do nothing — the next explicit openScreen call will handle it
    }

    public static boolean isReopeningScreen(UUID uuid) { return REOPENING_SCREENS.contains(uuid); }

    public static void handleEscBack(ServerPlayerEntity player) {
        GuiState state = STATES.get(player.getUuid());
        if (state == null) return;
        switch (state.mode()) {
            case MAIN -> {
                if ("__picker__".equals(state.editingId()) || "__picker_broken__".equals(state.editingId())) {
                    openMain(player, 0);
                } else if ("__picker_tabicon__".equals(state.editingId())) {
                    openTabIconMenu(player);
                } else {
                    STATES.remove(player.getUuid());
                    PENDING.remove(player.getUuid());
                }
            }
            case EDITOR -> {
                // If editor was opened directly from command AND player hasn't navigated
                // into a sub-screen, ESC exits entirely (1-press close)
                if (state.fromCommand()) {
                    STATES.remove(player.getUuid());
                    PENDING.remove(player.getUuid());
                } else {
                    openEditorPicker(player, state.page());
                }
            }
            case FACE_EDITOR  -> {
                // Go back to editor; from here ESC will exit (fromCommand stays false)
                openEditor(player, state.editingId(), state.page(), false);
            }
            case SHAPE_EDITOR -> {
                openEditor(player, state.editingId(), state.page(), false);
            }
            case ADMIN_GUI -> {
                STATES.remove(player.getUuid());
                PENDING.remove(player.getUuid());
            }
            case ANIM_GUI -> {
                ANIM_PARAMS.remove(player.getUuid());
                STATES.remove(player.getUuid());
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public static void openMain(ServerPlayerEntity player, int page) {
        STATES.put(player.getUuid(), GuiState.main(page));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildMain()),
            Text.literal("§6§l✦ §r§6CustomBlocks §7— Main Menu")));
    }

    public static void openEditorPicker(ServerPlayerEntity player) { openEditorPicker(player, 0); }
    public static void openEditorPicker(ServerPlayerEntity player, int page) {
        int total = sortedBlocks().size();
        int max   = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        STATES.put(player.getUuid(), GuiState.picker(page));
        final int fp = page;
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildPicker(fp, false)),
            Text.literal("§b§l▶ §r§fChoose a block §7(ESC = back)")));
    }

    /** Open editor; fromCommand=true when invoked via /cb editor <id> directly. */
    public static void openEditor(ServerPlayerEntity player, String id, int returnPage, boolean fromCommand) {
        SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        STATES.put(player.getUuid(), GuiState.editor(id, returnPage, fromCommand));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildEditor(d, false)),
            Text.literal("§e§l✎ §r§fEditor §8— §e" + d.displayName + " §7(ESC = back)")));
    }

    /** Convenience: open editor without the fromCommand flag (navigated internally). */
    public static void openEditor(ServerPlayerEntity player, String id, int returnPage) {
        openEditor(player, id, returnPage, false);
    }

    public static void openFaceEditor(ServerPlayerEntity player, String id, int returnPage) {
        SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        STATES.put(player.getUuid(), GuiState.faceEditor(id, returnPage));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildFaceEditor(d)),
            Text.literal("§d§l⬡ §r§fFace Editor §8— §d" + d.displayName + " §7(ESC = back)")));
    }

    public static void openShapeEditor(ServerPlayerEntity player, String id, int returnPage) {
        SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        STATES.put(player.getUuid(), GuiState.shapeEditor(id, returnPage));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildShapeEditor(d, 0)),
            Text.literal("§5§l⬡ §r§fShape Editor §8— §5" + d.displayName + " §7(ESC = back)")));
    }

    public static void openAdminGui(ServerPlayerEntity player) {
        STATES.put(player.getUuid(), GuiState.adminGui());
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildAdminGui(0)),
            Text.literal("§4§l⚙ §r§cAdmin Control Panel §7— All Servers")));
    }

    public static void openTabIconMenu(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(27);
        for(int i=0;i<27;i++) inv.setStack(i, glass());
        inv.setStack(11, uiGlint(Items.PAINTING, "§eType in Chat", "§7Provide a URL or Custom Block ID"));
        inv.setStack(15, uiGlint(Items.CHEST, "§bPick from Menu", "§7Select an existing block visually"));
        STATES.put(player.getUuid(), GuiState.main(0));
        PENDING.put(player.getUuid(), new PendingInput(InputAction.SETTABICON_URL, "__tab_icon_menu__", null, null, null, 0));
        openScreen(player, new SimpleNamedScreenHandlerFactory((s, pi, p) -> new CbScreenHandler(s, pi, inv), Text.literal("§e§l🎨 Choose Tab Icon")));
    }

    public static void openTabIconPicker(ServerPlayerEntity player, int page) {
        int total = sortedBlocks().size();
        int max   = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        STATES.put(player.getUuid(), GuiState.pickerTabIcon(page));
        final int fp = page;
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildPicker(fp, false)),
            Text.literal("§b§l▶ §r§fChoose Tab Icon §7(ESC = back)")));
    }

    public static void openBrokenBlocks(ServerPlayerEntity player) {
        openBrokenBlocks(player, 0);
    }
    public static void openBrokenBlocks(ServerPlayerEntity player, int page) {
        int total = brokenBlocks().size();
        int max   = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        STATES.put(player.getUuid(), GuiState.pickerBroken(page));
        final int fp = page;
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildPicker(fp, true)),
            Text.literal("§c§l▶ §r§fBroken Blocks §7(ESC = back)")));
    }

    public static List<SlotManager.SlotData> brokenBlocks() {
        List<SlotManager.SlotData> list = new ArrayList<>();
        for (SlotManager.SlotData d : sortedBlocks()) {
            if (d.texture != null && ImageProcessor.isBrokenTexture(d.texture)) {
                list.add(d);
            }
        }
        return list;
    }

    public static void handleClick(ServerPlayerEntity player, int slot, int button) {
        GuiState state = STATES.get(player.getUuid());
        if (state == null) return;
        switch (state.mode()) {
            case MAIN         -> handleMainClick(player, state, slot);
            case EDITOR       -> handleEditorClick(player, state, slot, button);
            case FACE_EDITOR  -> handleFaceEditorClick(player, state, slot, button);
            case SHAPE_EDITOR -> handleShapeEditorClick(player, state, slot, button);
            case ADMIN_GUI    -> handleAdminGuiClick(player, state, slot, button);
            case ANIM_GUI     -> handleAnimGuiClick(player, state, slot);
        }
    }

    public static boolean handleChatInput(ServerPlayerEntity player, String message) {
        PendingInput pending = PENDING.remove(player.getUuid());
        if (pending == null) return false;
        String text = message.trim(), blockId = pending.blockId();
        int rp = pending.returnPage();

        if (text.equalsIgnoreCase("cancel")) {
            send(player, "§7[CustomBlocks] Cancelled.");
            if (blockId != null && !blockId.startsWith("__") && SlotManager.hasId(blockId)) openEditor(player, blockId, rp);
            else openMain(player, rp);
            return true;
        }

        switch (pending.action()) {
            case CREATE_ID -> {
                if ("__delete__".equals(blockId)) {
                    String delId = text.toLowerCase().trim();
                    if (!SlotManager.hasId(delId)) { send(player, "§c'" + delId + "' not found."); openMain(player, rp); return true; }
                    SlotManager.SlotData dd = SlotManager.getById(delId);
                    SlotManager.pushUndoDelete(delId); SlotManager.remove(delId); SlotManager.saveAll();
                    CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload("remove", dd.index, delId, null, null, 0, 0, "stone"));
                    send(player, "§a[GUI] Deleted '§f" + delId + "§a'. Use /cb undo to restore.");
                    openMain(player, rp); return true;
                }
                String id = text.toLowerCase().replaceAll("[^a-z0-9_]", "_");
                if (id.isEmpty())          { send(player, "§cInvalid ID."); openMain(player, rp); return true; }
                if (SlotManager.hasId(id)) { send(player, "§c'" + id + "' already exists."); openMain(player, rp); return true; }
                PENDING.put(player.getUuid(), new PendingInput(InputAction.CREATE_NAME, id, null, id, null, rp));
                send(player, "§6[GUI] §eNow type a §fdisplay name§e for '" + id + "' (or §ccancel§e):"); return true;
            }
            case CREATE_NAME -> {
                PENDING.put(player.getUuid(), new PendingInput(InputAction.CREATE_URL, blockId, null, pending.partialId(), text.replace("_"," "), rp));
                send(player, "§6[GUI] §ePaste the §fimage URL§e for '" + text + "' (or §ccancel§e):"); return true;
            }
            case CREATE_URL -> {
                if (!isUrl(text)) { send(player, "§cNeeds a URL starting with http:// or https://"); return true; }
                String id = pending.partialId(), name = pending.partialName();
                if (id == null || name == null) { openMain(player, rp); return true; }
                if (SlotManager.freeSlots() == 0) { send(player, "§cAll slots full!"); openMain(player, rp); return true; }
                send(player, "§e[GUI] Downloading '" + name + "'…");
                MinecraftServer srv = player.getServer();
                thread(player, () -> { try {
                    byte[] raw = ImageProcessor.download(text);
                    ImageProcessor.GifResult gif = ImageProcessor.isAnimatedGif(raw) ? ImageProcessor.processGif(raw) : null;
                    byte[] bytes; String anim = null;
                    if (gif != null) { bytes = gif.stripPng(); anim = gif.mcmeta(); }
                    else { bytes = ImageProcessor.toPng(raw); bytes = ImageProcessor.padToSquare(bytes); bytes = ImageProcessor.replaceBackground(bytes); bytes = ImageProcessor.resizeTo(bytes, ImageProcessor.DEFAULT_SIZE); }
                    final byte[] fb = bytes; final String fa = anim;
                    srv.execute(() -> {
                        if (SlotManager.hasId(id)) { send(player, "§c'" + id + "' already exists."); openMain(player, rp); return; }
                        SlotManager.SlotData d = SlotManager.assign(id, name, fb);
                        if (d == null) { send(player, "§cNo free slots!"); openMain(player, rp); return; }
                        if (fa != null) SlotManager.setAnimMeta(id, fa);
                        SlotManager.pushUndoCreate(id); SlotManager.saveAll();
                        SlotManager.SlotData updated = SlotManager.getById(id);
                        CustomBlocksMod.broadcastUpdate(srv, new SlotUpdatePayload("add", d.index, id, name, fb, d.lightLevel, d.hardness, d.soundType, null, null, updated != null ? updated.animMeta : fa));
                        send(player, "§a[GUI] Created '§f" + name + "§a'! §7(slot #" + d.index + ")");
                        openEditor(player, id, rp);
                    });
                } catch (Exception e) { srv.execute(() -> { send(player, "§c[GUI] Failed: " + e.getMessage()); openMain(player, rp); }); } });
                return true;
            }
            case RETEXTURE_URL -> {
                if (!isUrl(text)) { send(player, "§cNeeds a URL."); openEditor(player, blockId, rp); return true; }
                SlotManager.SlotData d = SlotManager.getById(blockId);
                if (d == null) { openMain(player, rp); return true; }
                send(player, "§e[GUI] Downloading texture…");
                MinecraftServer srv = player.getServer();
                thread(player, () -> { try {
                    byte[] raw = ImageProcessor.download(text);
                    ImageProcessor.GifResult gif = ImageProcessor.isAnimatedGif(raw) ? ImageProcessor.processGif(raw) : null;
                    byte[] bytes; String anim = null;
                    if (gif != null) { bytes = gif.stripPng(); anim = gif.mcmeta(); }
                    else { bytes = ImageProcessor.toPng(raw); bytes = ImageProcessor.padToSquare(bytes); bytes = ImageProcessor.replaceBackground(bytes); bytes = ImageProcessor.resizeTo(bytes, ImageProcessor.DEFAULT_SIZE); }
                    final byte[] fb = bytes; final String fa = anim;
                    srv.execute(() -> {
                        SlotManager.pushUndo(blockId, "retexture");
                        SlotManager.SlotData dd = SlotManager.getById(blockId);
                        if (dd == null) { openMain(player, rp); return; }
                        SlotManager.updateTexture(blockId, fb);
                        if (fa != null) SlotManager.setAnimMeta(blockId, fa);
                        SlotManager.saveAll();
                        CustomBlocksMod.broadcastUpdate(srv, new SlotUpdatePayload("retexture", dd.index, blockId, null, fb, dd.lightLevel, dd.hardness, dd.soundType));
                        send(player, "§a[GUI] Texture updated for '§f" + blockId + "§a'.");
                        openEditor(player, blockId, rp);
                    });
                } catch (Exception e) { srv.execute(() -> { send(player, "§c[GUI] Failed: " + e.getMessage()); openEditor(player, blockId, rp); }); } });
                return true;
            }
            case SETFACE_URL -> {
                if (!isUrl(text)) { send(player, "§cNeeds a URL."); openFaceEditor(player, blockId, rp); return true; }
                String face = pending.face();
                SlotManager.SlotData d = SlotManager.getById(blockId);
                if (d == null) { openMain(player, rp); return true; }
                send(player, "§e[GUI] Downloading " + face + " face…");
                MinecraftServer srv = player.getServer();
                thread(player, () -> { try {
                    byte[] fb = ImageProcessor.toPng(ImageProcessor.download(text));
                    fb = ImageProcessor.padToSquare(fb); fb = ImageProcessor.replaceBackground(fb); fb = ImageProcessor.resizeTo(fb, ImageProcessor.DEFAULT_SIZE);
                    final byte[] ffb = fb;
                    srv.execute(() -> {
                        SlotManager.pushUndo(blockId, "setface " + face);
                        SlotManager.SlotData dd = SlotManager.getById(blockId);
                        if (dd == null) { openMain(player, rp); return; }
                        SlotManager.setFaceTexture(blockId, face, ffb); SlotManager.saveAll();
                        CustomBlocksMod.broadcastUpdate(srv, new SlotUpdatePayload("setface", dd.index, blockId, null, ffb, dd.lightLevel, dd.hardness, dd.soundType, face));
                        send(player, "§a[GUI] §f" + face.toUpperCase() + " §aface set on '§f" + blockId + "§a'.");
                        openFaceEditor(player, blockId, rp);
                    });
                } catch (Exception e) { srv.execute(() -> { send(player, "§c[GUI] Failed: " + e.getMessage()); openFaceEditor(player, blockId, rp); }); } });
                return true;
            }
            case SETFACE_VARIANT_URL -> {
                if (!isUrl(text)) { send(player, "§cNeeds a URL."); openFaceEditor(player, blockId, rp); return true; }
                String face = pending.face();
                SlotManager.SlotData orig = SlotManager.getById(blockId);
                if (orig == null) { openMain(player, rp); return true; }
                send(player, "§e[GUI] Creating variant with " + face + " face…");
                MinecraftServer srv = player.getServer();
                thread(player, () -> { try {
                    byte[] fb = ImageProcessor.toPng(ImageProcessor.download(text));
                    fb = ImageProcessor.padToSquare(fb); fb = ImageProcessor.replaceBackground(fb); fb = ImageProcessor.resizeTo(fb, ImageProcessor.DEFAULT_SIZE);
                    final byte[] ffb = fb;
                    srv.execute(() -> {
                        if (SlotManager.freeSlots() == 0) { send(player, "§cNo free slots!"); openFaceEditor(player, blockId, rp); return; }
                        String varId = generateVariantId(blockId, face);
                        String varName = orig.displayName + " (" + cap(face) + ")";
                        byte[] texCopy = orig.texture != null ? orig.texture.clone() : null;
                        SlotManager.SlotData nb = SlotManager.assign(varId, varName, texCopy);
                        if (nb == null) { send(player, "§cNo free slots!"); openFaceEditor(player, blockId, rp); return; }
                        SlotManager.setLightLevel(varId, orig.lightLevel); SlotManager.setHardness(varId, orig.hardness);
                        SlotManager.setSoundType(varId, orig.soundType);
                        if (orig.animMeta != null) SlotManager.setAnimMeta(varId, orig.animMeta);
                        for (var e : orig.faceTextures.entrySet()) SlotManager.setFaceTexture(varId, e.getKey(), e.getValue().clone());
                        SlotManager.setFaceTexture(varId, face, ffb);
                        SlotManager.pushUndoCreate(varId); SlotManager.saveAll();
                        SlotManager.SlotData fresh = SlotManager.getById(varId);
                        if (fresh != null) {
                            CustomBlocksMod.broadcastUpdate(srv, new SlotUpdatePayload("add", fresh.index, varId, varName, texCopy, fresh.lightLevel, fresh.hardness, fresh.soundType, null, null, fresh.animMeta));
                            for (var fe : fresh.faceTextures.entrySet())
                                CustomBlocksMod.broadcastUpdate(srv, new SlotUpdatePayload("setface", fresh.index, varId, null, fe.getValue(), fresh.lightLevel, fresh.hardness, fresh.soundType, fe.getKey()));
                        }
                        player.getInventory().insertStack(nb.index < CustomBlocksMod.SLOT_ITEMS.length && CustomBlocksMod.SLOT_ITEMS[nb.index] != null ? new ItemStack(CustomBlocksMod.SLOT_ITEMS[nb.index], 1) : ItemStack.EMPTY);
                        send(player, "§a[GUI] Variant '§f" + varId + "§a' created & given!");
                        openFaceEditor(player, varId, rp);
                    });
                } catch (Exception e) { srv.execute(() -> { send(player, "§c[GUI] Failed: " + e.getMessage()); openFaceEditor(player, blockId, rp); }); } });
                return true;
            }
            case RENAME_TEXT -> {
                SlotManager.SlotData d = SlotManager.getById(blockId);
                if (d == null) { openMain(player, rp); return true; }
                SlotManager.pushUndo(blockId, "rename"); SlotManager.rename(blockId, text.replace("_"," ")); SlotManager.saveAll();
                CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload("rename", d.index, blockId, text.replace("_"," "), null, 0, 0, "stone"));
                send(player, "§a[GUI] Renamed to '§f" + text + "§a'.");
                openEditor(player, blockId, rp); return true;
            }
            case SETTABICON_URL -> {
                if ("cancel".equalsIgnoreCase(text)) { openMain(player, rp); return true; }
                String targetId = text.toLowerCase().trim();
                boolean isBlock = SlotManager.hasId(targetId);
                if (!isUrl(text) && !isBlock) { send(player, "§cNeeds a URL or Block ID."); openMain(player, rp); return true; }
                
                send(player, "§e[GUI] Processing tab icon…");
                MinecraftServer srv = player.getServer();
                thread(player, () -> { try {
                    byte[] finalBytes = null;
                    if (isBlock) {
                        SlotManager.SlotData dd = SlotManager.getById(targetId);
                        if (dd.texture != null) finalBytes = dd.texture.clone();
                        else throw new Exception("Block has no texture");
                    } else {
                        finalBytes = ImageProcessor.downloadAndProcess(text);
                    }
                    final byte[] bytes = finalBytes;
                    srv.execute(() -> {
                        SlotManager.setTabIconTexture(bytes);
                        if (SlotManager.hasId("tab_icon")) {
                            SlotManager.SlotData ex = SlotManager.getById("tab_icon");
                            SlotManager.updateTexture("tab_icon", bytes); SlotManager.saveAll();
                            CustomBlocksMod.broadcastUpdate(srv, new SlotUpdatePayload("retexture", ex.index, "tab_icon", null, bytes, ex.lightLevel, ex.hardness, ex.soundType));
                        } else if (SlotManager.freeSlots() > 0) {
                            SlotManager.SlotData iconSlot = SlotManager.assign("tab_icon", "Tab Icon", bytes);
                            if (iconSlot != null) {
                                SlotManager.saveAll();
                                CustomBlocksMod.broadcastUpdate(srv, new SlotUpdatePayload("add", iconSlot.index, "tab_icon", "Tab Icon", bytes, iconSlot.lightLevel, iconSlot.hardness, iconSlot.soundType));
                            }
                        } else { send(player, "§e[GUI] §7Warning: all slots full — icon stored but not as a slot."); }
                        CustomBlocksMod.broadcastUpdate(srv, new SlotUpdatePayload("tabicon", -1, null, null, bytes, 0, 0, "stone"));
                        send(player, "§a[GUI] Tab icon updated! §7(Resource pack reloading…)");
                        openMain(player, rp);
                    });
                } catch (Exception e) { srv.execute(() -> { send(player, "§c[GUI] Failed: " + e.getMessage()); openMain(player, rp); }); } });
                return true;
            }
            case ADDSHAPE_COORDS -> {
                try {
                    SlotManager.ShapeBox box = SlotManager.ShapeBox.parse(text);
                    if (!box.valid()) { send(player, "§cInvalid coords."); openShapeEditor(player, blockId, rp); return true; }
                    SlotManager.pushUndo(blockId, "addshape");
                    if (!SlotManager.addBox(blockId, box)) { send(player, "§cMax 16 boxes!"); openShapeEditor(player, blockId, rp); return true; }
                    SlotManager.saveAll();
                    SlotManager.SlotData d = SlotManager.getById(blockId);
                    broadcastShape(player.getServer(), d);
                    send(player, "§a[Shape] Box added! Total: §f" + d.shapeBoxes.size());
                } catch (Exception e) { send(player, "§cBad coords. Use: x1,y1,z1,x2,y2,z2 (0–16)"); }
                openShapeEditor(player, blockId, rp); return true;
            }
            case REID_TEXT -> {
                if ("__givesquare__".equals(blockId)) {
                    String col = text.toLowerCase().trim();
                    if (!List.of("black","yellow","green").contains(col)) { send(player, "§cChoose: §fblack §7| §fyellow §7| §fgreen"); openMain(player, rp); return true; }
                    net.minecraft.item.Item it = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, col + "_square"));
                    if (it != null && it != Items.AIR) player.getInventory().insertStack(new ItemStack(it, 1));
                    send(player, "§a[GUI] Given §f" + col + " Square§a!"); openMain(player, rp); return true;
                }
                if ("__givetriangle__".equals(blockId)) {
                    String col = text.toLowerCase().trim();
                    if (!List.of("black","yellow","green").contains(col)) { send(player, "§cChoose: §fblack §7| §fyellow §7| §fgreen"); openMain(player, rp); return true; }
                    net.minecraft.item.Item it = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, col + "_triangle"));
                    if (it != null && it != Items.AIR) player.getInventory().insertStack(new ItemStack(it, 1));
                    send(player, "§a[GUI] Given §f" + col + " Triangle§a!"); openMain(player, rp); return true;
                }
                String newId = text.toLowerCase().replaceAll("[^a-z0-9_\\-]", "_");
                if (newId.isEmpty())           { send(player, "§cInvalid ID."); openEditor(player, blockId, rp); return true; }
                if (SlotManager.hasId(newId))  { send(player, "§c'" + newId + "' already taken."); openEditor(player, blockId, rp); return true; }
                SlotManager.pushUndo(blockId, "reid");
                SlotManager.SlotData d = SlotManager.getById(blockId);
                SlotManager.reId(blockId, newId); SlotManager.saveAll();
                SlotManager.SlotData upd = SlotManager.getById(newId);
                CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload("remove", d.index, blockId, null, null, 0, 0, "stone"));
                CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload("add", upd.index, newId, upd.displayName, upd.texture, upd.lightLevel, upd.hardness, upd.soundType, null, null, upd.animMeta));
                send(player, "§a[CustomBlocks] Re-ID'd '§f" + blockId + "§a' → '§f" + newId + "§a'.");
                openEditor(player, newId, rp); return true;
            }
            case ADMIN_CUSTOM_TITLE -> {
                // Reserved for future admin GUI custom title input — gracefully cancel and reopen admin panel
                send(player, "§7[CustomBlocks] Action cancelled.");
                openAdminGui(player);
                return true;
            }
        }
        return false;
    }

    private static void broadcastShape(MinecraftServer server, SlotManager.SlotData d) {
        List<SlotManager.ShapeBox> boxes = d.shapeBoxes;
        String data = (boxes == null || boxes.isEmpty()) ? "full" :
            boxes.stream().map(SlotManager.ShapeBox::toCoordString).reduce((a,b)->a+";"+b).orElse("full");
        CustomBlocksMod.broadcastUpdate(server, new SlotUpdatePayload("setshape", d.index, d.customId, null, null, 0, 0, "stone", null, data));
    }

    public static boolean hasPending(ServerPlayerEntity player)  { return PENDING.containsKey(player.getUuid()); }
    public static void clearState(ServerPlayerEntity player) { STATES.remove(player.getUuid()); PENDING.remove(player.getUuid()); }

    // ── Click handlers ────────────────────────────────────────────────────────

    private static void handleMainClick(ServerPlayerEntity player, GuiState state, int slot) {
        int page = state.page();
        boolean isPicker = state.editingId() != null && state.editingId().startsWith("__picker");
        
        if ("__tab_icon_menu__".equals(state.editingId())) {
            PENDING.remove(player.getUuid());
            if (slot == 11) { PENDING.put(player.getUuid(), new PendingInput(InputAction.SETTABICON_URL, null, null, null, null, page)); player.closeHandledScreen(); send(player, "§6[GUI] §ePaste URL or Block ID for the tab icon (or §ccancel§e):"); }
            if (slot == 15) { openTabIconPicker(player, 0); }
            return;
        }

        if (isPicker) {
            String mode = state.editingId();
            if (slot == 0) { 
                if (mode.equals("__picker_tabicon__")) openTabIconMenu(player);
                else openMain(player, 0); 
                return; 
            }
            if (slot == 45) { 
                if (mode.equals("__picker_tabicon__")) openTabIconPicker(player, Math.max(0, page-1));
                else if (mode.equals("__picker_broken__")) openBrokenBlocks(player, Math.max(0, page-1));
                else openEditorPicker(player, Math.max(0, page-1)); 
                return; 
            }
            if (slot == 53) { 
                if (mode.equals("__picker_tabicon__")) openTabIconPicker(player, page+1);
                else if (mode.equals("__picker_broken__")) openBrokenBlocks(player, page+1);
                else openEditorPicker(player, page+1); 
                return; 
            }
            if (slot >= 18 && slot <= 35) {
                List<SlotManager.SlotData> blocks = mode.equals("__picker_broken__") ? brokenBlocks() : sortedBlocks();
                int idx = page * BLOCKS_PER_PAGE + (slot - 18);
                if (idx < blocks.size()) {
                    if (mode.equals("__picker_tabicon__")) {
                        SlotManager.SlotData dd = blocks.get(idx);
                        if (dd.texture != null) {
                            SlotManager.setTabIconTexture(dd.texture.clone());
                            SlotManager.saveAll();
                            CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload("tabicon", -1, null, null, dd.texture.clone(), 0, 0, "stone"));
                            send(player, "§a[GUI] Tab icon updated to " + dd.displayName);
                            player.closeHandledScreen();
                        } else {
                            send(player, "§cBlock has no texture.");
                        }
                    } else {
                        openEditor(player, blocks.get(idx).customId, page);
                    }
                }
            }
            return;
        }

        switch (slot) {
            // Row 1 — primary actions
            case 0 -> { PENDING.put(player.getUuid(), new PendingInput(InputAction.CREATE_ID, null, null, null, null, page)); player.closeHandledScreen(); send(player, "§6[GUI] §eType a block §fID §e(a-z 0-9 _ only) or §ccancel§e:"); }
            case 2 -> openEditorPicker(player, 0);
            case 4 -> {
                if (SlotManager.undoStackSize() == 0) { send(player, "§7Nothing to undo."); refreshScreen(player, buildMain()); return; }
                SlotManager.UndoEntry entry = SlotManager.popUndo();
                if (entry == null) { refreshScreen(player, buildMain()); return; }
                applyUndoEntry(player, entry);
                if (SlotManager.undoStackSize() > 0) {
                    send(player, "§8  → Next undo: §7\"" + SlotManager.peekUndoDescription() + "\" §8(" + SlotManager.undoStackSize() + " left)");
                }
                refreshScreen(player, buildMain());
            }
            case 6 -> {
                if (SlotManager.redoStackSize() == 0) { send(player, "§7Nothing to redo."); refreshScreen(player, buildMain()); return; }
                SlotManager.UndoEntry entry = SlotManager.popRedo();
                if (entry == null) { refreshScreen(player, buildMain()); return; }
                applyRedoEntry(player, entry);
                if (SlotManager.redoStackSize() > 0) {
                    send(player, "§8  → Next redo: §7\"" + SlotManager.peekRedoDescription() + "\" §8(" + SlotManager.redoStackSize() + " left)");
                }
                refreshScreen(player, buildMain());
            }
            case 8 -> { PENDING.put(player.getUuid(), new PendingInput(InputAction.CREATE_ID, "__delete__", null, null, null, page)); player.closeHandledScreen(); send(player, "§6[GUI] §eType the §fID §eof the block to delete (or §ccancel§e):"); }
            
            // Row 2 actions
            case 9 -> openTabIconMenu(player);
            case 11 -> { player.closeHandledScreen(); player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb export"); }
            case 13 -> { player.closeHandledScreen(); player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb importfolder"); }
            case 15 -> { player.closeHandledScreen(); player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb reload"); }
            case 17 -> openBrokenBlocks(player);

            // Row 4 — tools (shifted by +9)
            case 19 -> { player.getInventory().insertStack(new ItemStack(net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, "rainbow_rectangle")), 1)); send(player, "§6[GUI] Given §6Rainbow Rectangle§e!"); refreshScreen(player, buildMain()); }
            case 20 -> { PENDING.put(player.getUuid(), new PendingInput(InputAction.REID_TEXT, "__givesquare__", null, null, null, page)); player.closeHandledScreen(); send(player, "§6[GUI] §eType color: §fblack §7| §fyellow §7| §fgreen§e:"); }
            case 21 -> { PENDING.put(player.getUuid(), new PendingInput(InputAction.REID_TEXT, "__givetriangle__", null, null, null, page)); player.closeHandledScreen(); send(player, "§6[GUI] §eType color: §fblack §7| §fyellow §7| §fgreen§e:"); }
            case 22 -> openAdminGui(player);
            case 23 -> { player.closeHandledScreen(); player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb list"); }
            case 24 -> { player.closeHandledScreen(); player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb help"); }
        }
    }

    private static void applyUndoEntry(ServerPlayerEntity player, SlotManager.UndoEntry entry) {
        MinecraftServer gsrv = player.getServer();

        if (entry.previousState() == null) {
            // Undo a creation → delete the block; push delete to redo
            SlotManager.SlotData cd = SlotManager.getById(entry.customId());
            if (cd != null) {
                // Save current state for redo
                SlotManager.UndoEntry redoEntry = new SlotManager.UndoEntry(entry.customId(), snapshotPublic(cd), "create", true);
                SlotManager.pushRedo(redoEntry);
                SlotManager.remove(entry.customId()); SlotManager.saveAll();
                CustomBlocksMod.broadcastUpdate(gsrv, new SlotUpdatePayload("remove", cd.index, entry.customId(), null, null, 0, 0, "stone"));
                send(player, "§a[GUI] Undid create of §f" + entry.customId());
            }
            return;
        }

        SlotManager.SlotData prev = entry.previousState();
        // Save current state for redo before restoring
        SlotManager.SlotData curForRedo = SlotManager.getById(prev.customId);
        if (curForRedo != null) {
            SlotManager.UndoEntry redoEntry = new SlotManager.UndoEntry(entry.customId(), snapshotPublic(curForRedo), entry.description(), entry.wasDeleted());
            SlotManager.pushRedo(redoEntry);
        }

        if (SlotManager.restoreSnapshot(prev, entry.wasDeleted())) {
            SlotManager.saveAll();
            SlotManager.SlotData d = SlotManager.getById(prev.customId);
            if (d != null) {
                if (entry.wasDeleted()) {
                    CustomBlocksMod.broadcastUpdate(gsrv, new SlotUpdatePayload("add", d.index, d.customId, d.displayName, d.texture, d.lightLevel, d.hardness, d.soundType, null, null, d.animMeta));
                    for (var fe : d.faceTextures.entrySet()) CustomBlocksMod.broadcastUpdate(gsrv, new SlotUpdatePayload("setface", d.index, d.customId, null, fe.getValue(), d.lightLevel, d.hardness, d.soundType, fe.getKey()));
                } else {
                    if (d.texture != null) CustomBlocksMod.broadcastUpdate(gsrv, new SlotUpdatePayload("retexture", d.index, d.customId, null, d.texture, d.lightLevel, d.hardness, d.soundType));
                    CustomBlocksMod.broadcastUpdate(gsrv, new SlotUpdatePayload("clearfaces", d.index, d.customId, null, null, d.lightLevel, d.hardness, d.soundType));
                    for (var fe : d.faceTextures.entrySet()) CustomBlocksMod.broadcastUpdate(gsrv, new SlotUpdatePayload("setface", d.index, d.customId, null, fe.getValue(), d.lightLevel, d.hardness, d.soundType, fe.getKey()));
                    CustomBlocksMod.broadcastUpdate(gsrv, new SlotUpdatePayload("setprop", d.index, d.customId, null, null, d.lightLevel, d.hardness, d.soundType));
                    CustomBlocksMod.broadcastUpdate(gsrv, new SlotUpdatePayload("rename", d.index, d.customId, d.displayName, null, 0, 0, "stone"));
                }
            }
            send(player, "§a[GUI] Undid §f\"" + entry.description() + "\"§a on §f" + entry.customId() + " §7(" + SlotManager.undoStackSize() + " left)");
        }
    }

    private static void applyRedoEntry(ServerPlayerEntity player, SlotManager.UndoEntry entry) {
        MinecraftServer gsrv = player.getServer();

        if (entry.previousState() == null) {
            // Redo a deletion (block was deleted, redo deletes again)
            SlotManager.SlotData cd = SlotManager.getById(entry.customId());
            if (cd != null) {
                SlotManager.UndoEntry undoEntry = new SlotManager.UndoEntry(entry.customId(), snapshotPublic(cd), "delete", true);
                SlotManager.pushUndoForRedo(undoEntry);
                SlotManager.remove(entry.customId()); SlotManager.saveAll();
                CustomBlocksMod.broadcastUpdate(gsrv, new SlotUpdatePayload("remove", cd.index, entry.customId(), null, null, 0, 0, "stone"));
                send(player, "§a[GUI] Redid delete of §f" + entry.customId());
            }
            return;
        }

        SlotManager.SlotData prev = entry.previousState();
        SlotManager.SlotData curForUndo = SlotManager.getById(prev.customId);
        if (curForUndo != null) {
            SlotManager.UndoEntry undoEntry = new SlotManager.UndoEntry(entry.customId(), snapshotPublic(curForUndo), entry.description(), entry.wasDeleted());
            SlotManager.pushUndoForRedo(undoEntry);
        }

        if (SlotManager.restoreSnapshot(prev, entry.wasDeleted())) {
            SlotManager.saveAll();
            SlotManager.SlotData d = SlotManager.getById(prev.customId);
            if (d != null) {
                if (entry.wasDeleted()) {
                    CustomBlocksMod.broadcastUpdate(gsrv, new SlotUpdatePayload("add", d.index, d.customId, d.displayName, d.texture, d.lightLevel, d.hardness, d.soundType, null, null, d.animMeta));
                } else {
                    if (d.texture != null) CustomBlocksMod.broadcastUpdate(gsrv, new SlotUpdatePayload("retexture", d.index, d.customId, null, d.texture, d.lightLevel, d.hardness, d.soundType));
                    CustomBlocksMod.broadcastUpdate(gsrv, new SlotUpdatePayload("clearfaces", d.index, d.customId, null, null, d.lightLevel, d.hardness, d.soundType));
                    for (var fe : d.faceTextures.entrySet()) CustomBlocksMod.broadcastUpdate(gsrv, new SlotUpdatePayload("setface", d.index, d.customId, null, fe.getValue(), d.lightLevel, d.hardness, d.soundType, fe.getKey()));
                    CustomBlocksMod.broadcastUpdate(gsrv, new SlotUpdatePayload("setprop", d.index, d.customId, null, null, d.lightLevel, d.hardness, d.soundType));
                    CustomBlocksMod.broadcastUpdate(gsrv, new SlotUpdatePayload("rename", d.index, d.customId, d.displayName, null, 0, 0, "stone"));
                }
            }
            send(player, "§a[GUI] Redid §f\"" + entry.description() + "\"§a on §f" + entry.customId() + " §7(" + SlotManager.redoStackSize() + " redo left)");
        }
    }

    /** Create a SlotData snapshot accessible from GuiManager (mirrors SlotManager's private snapshot). */
    private static SlotManager.SlotData snapshotPublic(SlotManager.SlotData d) {
        Map<String, byte[]> facesCopy = new java.util.concurrent.ConcurrentHashMap<>();
        d.faceTextures.forEach((k, v) -> facesCopy.put(k, v.clone()));
        return new SlotManager.SlotData(d.index, d.customId, d.displayName,
                d.texture != null ? d.texture.clone() : null,
                d.lightLevel, d.hardness, d.soundType, facesCopy, d.animMeta,
                d.shapeBoxes != null ? new ArrayList<>(d.shapeBoxes) : null, d.noCollision);
    }

    private static void handleEditorClick(ServerPlayerEntity player, GuiState state, int slot, int button) {
        String id = state.editingId(); int rp = state.page();
        SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, rp); return; }
        switch (slot) {
            case 0  -> openEditorPicker(player, rp);
            case 2  -> { player.getInventory().insertStack(CustomBlocksMod.safeSlotItem(d.index) != null ? new ItemStack(CustomBlocksMod.safeSlotItem(d.index),1) : ItemStack.EMPTY); send(player, "§a[GUI] Given 1x §f" + d.displayName); openEditor(player,id,rp); }
            case 3  -> { PENDING.put(player.getUuid(), new PendingInput(InputAction.RENAME_TEXT,id,null,null,null,rp)); player.closeHandledScreen(); send(player, "§6[GUI] §eType new name for '§f" + id + "§e' (or §ccancel§e):"); }
            case 4  -> { PENDING.put(player.getUuid(), new PendingInput(InputAction.REID_TEXT,id,null,null,null,rp)); player.closeHandledScreen(); send(player, "§6[GUI] §eType new ID for '§f" + id + "§e' (a-z 0-9 _ -) (or §ccancel§e):"); }
            case 6  -> { PENDING.put(player.getUuid(), new PendingInput(InputAction.CREATE_ID,id,null,null,null,rp)); player.closeHandledScreen(); send(player, "§6[GUI] §eType new ID to duplicate '§f" + id + "§e' into (or §ccancel§e):"); }
            case 8  -> {
                if (state.confirmDelete()) {
                    SlotManager.pushUndoDelete(id); SlotManager.remove(id); SlotManager.saveAll();
                    CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload("remove", d.index, id, null, null, 0, 0, "stone"));
                    send(player, "§a[GUI] '§f" + id + "§a' deleted."); openMain(player, rp);
                } else {
                    STATES.put(player.getUuid(), state.withConfirmDelete());
                    SlotManager.SlotData dd = SlotManager.getById(id); if (dd == null) return;
                    REOPENING_SCREENS.add(player.getUuid());
                    player.openHandledScreen(new SimpleNamedScreenHandlerFactory((s,pi,p)->new CbScreenHandler(s,pi,buildEditor(dd,true)), Text.literal("§c§l⚠ Confirm DELETE — §r§f" + dd.displayName)));
                    REOPENING_SCREENS.remove(player.getUuid());
                }
            }
            case 9  -> { PENDING.put(player.getUuid(), new PendingInput(InputAction.RETEXTURE_URL,id,null,null,null,rp)); player.closeHandledScreen(); send(player, "§6[GUI] §ePaste image URL for ALL faces of '§f" + id + "§e' (or §ccancel§e):"); }
            case 10, 11, 12, 13, 14, 15, 16 -> openFaceEditor(player, id, rp);
            case 17 -> { SlotManager.pushUndo(id,"clearallfaces"); SlotManager.clearAllFaces(id); SlotManager.saveAll(); broadcastClearAllFaces(player,d); send(player,"§a[GUI] All face overrides cleared."); openEditor(player,id,rp); }
            case 19 -> clearFace(player,d,"top");
            case 20 -> clearFace(player,d,"north");
            case 21 -> clearFace(player,d,"south");
            case 22 -> clearFace(player,d,"east");
            case 23 -> clearFace(player,d,"west");
            case 24 -> clearFace(player,d,"bottom");
            case 27 -> { SlotManager.pushUndo(id,"setglow"); SlotManager.setLightLevel(id,Math.max(0,d.lightLevel-1)); syncProp(player,d); refreshEditorInPlace(player, id, rp); }
            case 29 -> { SlotManager.pushUndo(id,"setglow"); SlotManager.setLightLevel(id,Math.min(15,d.lightLevel+1)); syncProp(player,d); refreshEditorInPlace(player, id, rp); }
            case 30 -> { SlotManager.pushUndo(id,"sethardness"); SlotManager.setHardness(id,prevHardness(d.hardness)); syncProp(player,d); refreshEditorInPlace(player, id, rp); }
            case 32 -> { SlotManager.pushUndo(id,"sethardness"); SlotManager.setHardness(id,nextHardness(d.hardness)); syncProp(player,d); refreshEditorInPlace(player, id, rp); }
            case 34 -> openShapeEditor(player,id,rp);
            case 35 -> {
                SlotManager.pushUndo(id,"setcollision"); SlotManager.setCollision(id,!d.noCollision); SlotManager.saveAll();
                SlotManager.SlotData upd = SlotManager.getById(id);
                CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload("setcollision",upd.index,id,null,null,0,0,"stone",null,upd.noCollision?"false":"true"));
                send(player,"§a[GUI] Collision: §f" + (upd.noCollision?"§cOFF":"§aON")); refreshEditorInPlace(player, id, rp);
            }
            case 36->{ setSoundQuiet(player,d,"stone"); refreshEditorInPlace(player,id,rp); }
            case 37->{ setSoundQuiet(player,d,"wood");  refreshEditorInPlace(player,id,rp); }
            case 38->{ setSoundQuiet(player,d,"grass"); refreshEditorInPlace(player,id,rp); }
            case 39->{ setSoundQuiet(player,d,"metal"); refreshEditorInPlace(player,id,rp); }
            case 40->{ setSoundQuiet(player,d,"glass"); refreshEditorInPlace(player,id,rp); }
            case 41->{ setSoundQuiet(player,d,"sand");  refreshEditorInPlace(player,id,rp); }
            case 42->{ setSoundQuiet(player,d,"gravel");refreshEditorInPlace(player,id,rp); }
            case 43->{ setSoundQuiet(player,d,"wool");  refreshEditorInPlace(player,id,rp); }
            case 44->{ setSoundQuiet(player,d,"snow");  refreshEditorInPlace(player,id,rp); }
            case 45->{ setSoundQuiet(player,d,"dirt");  refreshEditorInPlace(player,id,rp); }
            case 46->{ setSoundQuiet(player,d,"coral"); refreshEditorInPlace(player,id,rp); }
            case 47->{ setSoundQuiet(player,d,"bamboo");refreshEditorInPlace(player,id,rp); }
            case 48->{ setSoundQuiet(player,d,"nether_brick"); refreshEditorInPlace(player,id,rp); }
            case 49->{ setSoundQuiet(player,d,"ice");   refreshEditorInPlace(player,id,rp); }
            case 50->{ setSoundQuiet(player,d,"honey"); refreshEditorInPlace(player,id,rp); }
            case 51->{ setSoundQuiet(player,d,"bone");  refreshEditorInPlace(player,id,rp); }
            case 52->{ setSoundQuiet(player,d,"slime"); refreshEditorInPlace(player,id,rp); }
        }
    }

    /** Refresh the editor screen IN PLACE — no cursor jump. */
    private static void refreshEditorInPlace(ServerPlayerEntity player, String id, int rp) {
        SlotManager.SlotData fresh = SlotManager.getById(id);
        if (fresh == null) { openMain(player, rp); return; }
        refreshScreen(player, buildEditor(fresh, false));
    }

    private static void handleShapeEditorClick(ServerPlayerEntity player, GuiState state, int slot, int button) {
        String id = state.editingId(); int rp = state.page(); int boxPage = state.shapeBoxPage();
        SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, rp); return; }
        List<SlotManager.ShapeBox> boxes = d.shapeBoxes != null ? new ArrayList<>(d.shapeBoxes) : new ArrayList<>();

        if (slot == 0) { openEditor(player,id,rp); return; }
        if (slot == 8) {
            SlotManager.pushUndo(id,"setcollision"); SlotManager.setCollision(id,!d.noCollision); SlotManager.saveAll();
            SlotManager.SlotData upd = SlotManager.getById(id);
            CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload("setcollision",upd.index,id,null,null,0,0,"stone",null,upd.noCollision?"false":"true"));
            send(player,"§a[Shape] Collision: §f"+(upd.noCollision?"§cOFF":"§aON")); reopenShapeEditor(player,id,rp,boxPage); return;
        }
        if (slot >= 10 && slot <= 21) {
            int pi = slot - 10;
            if (pi < PRESET_NAMES.length) {
                if (button == 1) applyPresetToCurrent(player,d,id,PRESET_NAMES[pi],rp,boxPage);
                else             createShapeVariant(player,d,id,PRESET_NAMES[pi],rp,boxPage);
            }
            return;
        }
        if (slot == 22) { PENDING.put(player.getUuid(), new PendingInput(InputAction.ADDSHAPE_COORDS,id,null,null,null,rp)); player.closeHandledScreen(); send(player,"§6[Shape] §eType coords (or §ccancel§e): §7x1,y1,z1,x2,y2,z2  §8(0–16)"); return; }
        if (slot == 23) { SlotManager.pushUndo(id,"clearshape"); SlotManager.clearShape(id); SlotManager.saveAll(); broadcastShape(player.getServer(),SlotManager.getById(id)); send(player,"§a[Shape] Cleared — full cube."); reopenShapeEditor(player,id,rp,0); return; }
        if (slot >= 28 && slot <= 35) {
            int boxIdx = boxPage*9 + (slot-28);
            if (boxIdx < boxes.size()) { SlotManager.pushUndo(id,"removeshape"); SlotManager.removeBox(id,boxIdx); SlotManager.saveAll(); broadcastShape(player.getServer(),SlotManager.getById(id)); send(player,"§a[Shape] Removed box #"+boxIdx+"."); int np=Math.min(boxPage,Math.max(0,(boxes.size()-2)/9)); reopenShapeEditor(player,id,rp,np); }
            return;
        }
        List<SlotManager.SlotData> variants = findShapeVariants(id);
        if (slot >= 37 && slot <= 44) { int vi=slot-37; if(vi<variants.size()) openEditor(player,variants.get(vi).customId,rp); return; }
        if (slot==45 && boxPage>0) { reopenShapeEditor(player,id,rp,boxPage-1); return; }
        if (slot==53) { int maxPg=Math.max(0,(boxes.size()-1)/9); if(boxPage<maxPg) reopenShapeEditor(player,id,rp,boxPage+1); }
    }

    private static void handleAdminGuiClick(ServerPlayerEntity player, GuiState state, int slot, int button) {
        switch (slot) {
            case 0 -> openMain(player, 0);
            // Row 1: block management
            case 10 -> { PENDING.put(player.getUuid(), new PendingInput(InputAction.CREATE_ID, null, null, null, null, 0)); player.closeHandledScreen(); send(player, "§6[Admin] §eType new block §fID§e (or §ccancel§e):"); }
            case 11 -> openEditorPicker(player, 0);
            case 12 -> { PENDING.put(player.getUuid(), new PendingInput(InputAction.CREATE_ID, "__delete__", null, null, null, 0)); player.closeHandledScreen(); send(player, "§6[Admin] §eType block ID to §cdelete §e(or §ccancel§e):"); }
            case 13 -> { player.closeHandledScreen(); player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb reload"); }
            case 14 -> { player.closeHandledScreen(); player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb list"); }
            case 15 -> { player.closeHandledScreen(); player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb export"); }
            case 16 -> { player.closeHandledScreen(); player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb importfolder"); }
            // Row 2: undo/redo controls
            case 19 -> {
                if (SlotManager.undoStackSize() == 0) { send(player, "§7Nothing to undo."); refreshScreen(player, buildAdminGui(0)); return; }
                SlotManager.UndoEntry entry = SlotManager.popUndo();
                if (entry != null) {
                    applyUndoEntry(player, entry);
                    if (SlotManager.undoStackSize() > 0) send(player, "§8  → Next: §7\"" + SlotManager.peekUndoDescription() + "\"");
                }
                refreshScreen(player, buildAdminGui(0));
            }
            case 20 -> {
                if (SlotManager.redoStackSize() == 0) { send(player, "§7Nothing to redo."); refreshScreen(player, buildAdminGui(0)); return; }
                SlotManager.UndoEntry entry = SlotManager.popRedo();
                if (entry != null) {
                    applyRedoEntry(player, entry);
                    if (SlotManager.redoStackSize() > 0) send(player, "§8  → Next: §7\"" + SlotManager.peekRedoDescription() + "\"");
                }
                refreshScreen(player, buildAdminGui(0));
            }
            // Block list — click any block to jump straight to editor
            default -> {
                if (slot >= 27 && slot <= 53) {
                    List<SlotManager.SlotData> blocks = sortedBlocks();
                    int idx = slot - 27;
                    if (idx < blocks.size()) openEditor(player, blocks.get(idx).customId, 0);
                }
            }
        }
    }

    private static void createShapeVariant(ServerPlayerEntity player, SlotManager.SlotData d, String id,
                                            String preset, int rp, int boxPage) {
        String varId = generateShapeVariantId(id, preset);
        if (SlotManager.hasId(varId)) { send(player,"§e[Shape] '§f"+varId+"§e' already exists — opening it."); openShapeEditor(player,varId,rp); return; }
        if (SlotManager.freeSlots()==0) { send(player,"§c[Shape] No free slots!"); reopenShapeEditor(player,id,rp,boxPage); return; }
        List<SlotManager.ShapeBox> presetBoxes = SlotManager.SHAPE_PRESETS.get(preset);
        String varName = d.displayName + " (" + cap(preset) + ")";
        byte[] texCopy = d.texture != null ? d.texture.clone() : null;
        SlotManager.SlotData nb = SlotManager.assign(varId, varName, texCopy);
        if (nb == null) { send(player,"§c[Shape] Assign failed!"); reopenShapeEditor(player,id,rp,boxPage); return; }
        SlotManager.setLightLevel(varId,d.lightLevel); SlotManager.setHardness(varId,d.hardness); SlotManager.setSoundType(varId,d.soundType);
        if (d.animMeta!=null) SlotManager.setAnimMeta(varId,d.animMeta);
        for (var e : d.faceTextures.entrySet()) SlotManager.setFaceTexture(varId,e.getKey(),e.getValue().clone());
        SlotManager.setShape(varId, presetBoxes!=null ? new ArrayList<>(presetBoxes) : null);
        if (d.noCollision) SlotManager.setCollision(varId, false);
        SlotManager.pushUndoCreate(varId); SlotManager.saveAll();
        SlotManager.SlotData fresh = SlotManager.getById(varId);
        if (fresh != null) {
            CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload("add",fresh.index,varId,varName,texCopy,fresh.lightLevel,fresh.hardness,fresh.soundType,null,null,fresh.animMeta));
            for (var fe : fresh.faceTextures.entrySet()) CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload("setface",fresh.index,varId,null,fe.getValue(),fresh.lightLevel,fresh.hardness,fresh.soundType,fe.getKey()));
            broadcastShape(player.getServer(), fresh);
            if (fresh.noCollision) CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload("setcollision",fresh.index,varId,null,null,0,0,"stone",null,"false"));
        }
        send(player,"§a[Shape] ✔ Created '§f"+varName+"§a' (ID: §f"+varId+"§a)");
        send(player,"§7Use §fRetexture §7in the editor to tweak the texture for this shape.");
        openShapeEditor(player, varId, rp);
    }

    private static void applyPresetToCurrent(ServerPlayerEntity player, SlotManager.SlotData d, String id,
                                              String preset, int rp, int boxPage) {
        List<SlotManager.ShapeBox> boxes = SlotManager.SHAPE_PRESETS.get(preset);
        SlotManager.pushUndo(id,"setshape"); SlotManager.setShape(id, boxes!=null ? new ArrayList<>(boxes) : null); SlotManager.saveAll();
        broadcastShape(player.getServer(), SlotManager.getById(id));
        send(player,"§a[Shape] Applied '§f"+preset+"§a' to current block (§7right-click action§a).");
        reopenShapeEditor(player,id,rp,boxPage);
    }

    private static List<SlotManager.SlotData> findShapeVariants(String baseId) {
        List<SlotManager.SlotData> result = new ArrayList<>();
        for (String p : PRESET_NAMES) {
            for (int n = 0; n <= 9; n++) {
                String cand = n==0 ? (baseId+"_"+p) : (baseId+"_"+p+"_"+n);
                SlotManager.SlotData v = SlotManager.getById(cand);
                if (v != null) result.add(v);
            }
        }
        return result;
    }

    private static void reopenShapeEditor(ServerPlayerEntity player, String id, int rp, int boxPage) {
        SlotManager.SlotData d = SlotManager.getById(id);
        if (d==null) { openMain(player,rp); return; }
        STATES.put(player.getUuid(), GuiState.shapeEditor(id,rp).withShapeBoxPage(boxPage));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s,pi,p)->new CbScreenHandler(s,pi,buildShapeEditor(d,boxPage)),
            Text.literal("§5§l⬡ §r§fShape Editor §8— §5"+d.displayName+" §7(ESC = back)")));
    }

    private static void handleFaceEditorClick(ServerPlayerEntity player, GuiState state, int slot, int button) {
        String id = state.editingId(); int rp = state.page();
        SlotManager.SlotData d = SlotManager.getById(id);
        if (d==null) { openMain(player,rp); return; }
        switch (slot) {
            case 0  -> openEditor(player,id,rp);
            case 9  -> promptFace(player,id,"top",   rp,false); case 10 -> promptFace(player,id,"top",   rp,true);
            case 11 -> promptFace(player,id,"bottom",rp,false); case 12 -> promptFace(player,id,"bottom",rp,true);
            case 13 -> promptFace(player,id,"north", rp,false); case 14 -> promptFace(player,id,"north", rp,true);
            case 15 -> promptFace(player,id,"south", rp,false); case 16 -> promptFace(player,id,"south", rp,true);
            case 17 -> promptFace(player,id,"east",  rp,false); case 18 -> promptFace(player,id,"east",  rp,true);
            case 19 -> promptFace(player,id,"west",  rp,false); case 20 -> promptFace(player,id,"west",  rp,true);
            case 27 -> clearFace(player,d,"top");    case 28 -> clearFace(player,d,"bottom");
            case 29 -> clearFace(player,d,"north");  case 30 -> clearFace(player,d,"south");
            case 31 -> clearFace(player,d,"east");   case 32 -> clearFace(player,d,"west");
            case 45 -> openEditor(player,id,rp);
            case 46 -> {
                if (SlotManager.undoStackSize()>0) { SlotManager.UndoEntry e=SlotManager.popUndo(); if(e!=null&&e.previousState()!=null){SlotManager.restoreSnapshot(e.previousState(),e.wasDeleted());SlotManager.saveAll();SlotManager.SlotData dd=SlotManager.getById(id);if(dd!=null)CustomBlocksMod.broadcastUpdate(player.getServer(),new SlotUpdatePayload("clearfaces",dd.index,id,null,null,dd.lightLevel,dd.hardness,dd.soundType));send(player,"§a[GUI] Undid '"+e.description()+"'.");} }
                openFaceEditor(player,id,rp);
            }
            case 47 -> { SlotManager.pushUndo(id,"clearallfaces"); SlotManager.clearAllFaces(id); SlotManager.saveAll(); broadcastClearAllFaces(player,d); send(player,"§a[GUI] All face overrides cleared."); openFaceEditor(player,id,rp); }
            case 53 -> { player.getInventory().insertStack(CustomBlocksMod.safeSlotItem(d.index)!=null?new ItemStack(CustomBlocksMod.safeSlotItem(d.index),1):ItemStack.EMPTY); send(player,"§a[GUI] Given 1x §f"+d.displayName); openFaceEditor(player,id,rp); }
        }
    }

    // ── Builders ──────────────────────────────────────────────────────────────

    private static SimpleInventory buildMain() {
        SimpleInventory inv = new SimpleInventory(54);
        int undoSz = SlotManager.undoStackSize();
        int redoSz = SlotManager.redoStackSize();

        // ── Row 1 & 2: Core block actions spaced out ──────────────────────────
        inv.setStack(0, uiGlint(Items.LIME_CONCRETE,  "§a§l➕ Create New Block",
            "§7Click → type ID, name, URL in chat",
            "§8Creates a brand-new custom block"));
        inv.setStack(1, glass());
        inv.setStack(2, uiGlint(Items.CHEST,          "§b§l📂 Browse & Edit Blocks",
            "§7Browse all blocks and open the full editor"));
        inv.setStack(3, glass());
        inv.setStack(4, undoSz > 0
            ? uiGlint(Items.GOLDEN_PICKAXE, "§6§l↩ UNDO  §e(" + undoSz + " left)",
                "§eUndo: §f\"" + SlotManager.peekUndoDescription() + "\"",
                "§8Click to undo this action")
            : ui(Items.GRAY_STAINED_GLASS_PANE, "§8↩ Undo §7(nothing to undo)", "§7Make a change first"));
        inv.setStack(5, glass());
        inv.setStack(6, redoSz > 0
            ? uiGlint(Items.DIAMOND_PICKAXE, "§b§l↪ REDO  §3(" + redoSz + " left)",
                "§3Redo: §f\"" + SlotManager.peekRedoDescription() + "\"",
                "§8Click to re-apply this action")
            : ui(Items.GRAY_STAINED_GLASS_PANE, "§8↪ Redo §7(nothing to redo)", "§7Undo something first"));
        inv.setStack(7, glass());
        inv.setStack(8, ui(Items.TNT,                 "§c§l❌ Delete a Block",
            "§7Click → type block ID in chat", "§8Use Undo to restore"));

        inv.setStack(9, uiGlint(Items.PAINTING,       "§e§l🎨 Set Tab Icon",
            "§7Click → open Tab Icon Menu"));
        inv.setStack(10, glass());
        inv.setStack(11, uiGlint(Items.PAPER,          "§f§l📤 Export Blocks",
            "§7Export block list to JSON", "§8Runs: /cb export"));
        inv.setStack(12, glass());
        inv.setStack(13, uiGlint(Items.HOPPER,         "§f§l📥 Import Folder",
            "§7Bulk-import from config/customblocks/import/", "§8Runs: /cb importfolder"));
        inv.setStack(14, glass());
        inv.setStack(15, uiGlint(Items.NETHER_STAR,    "§f§l🔄 Reload All Data",
            "§7Reload & sync to all players", "§8Runs: /cb reload"));
        inv.setStack(16, glass());
        inv.setStack(17, uiGlint(Items.ANVIL,          "§6§l🔍 View Broken Blocks", "§7Search for broken/missing textures"));

        // ── Separator ─────────────────────────────────────────────────────────
        inv.setStack(18, ui(Items.BLUE_STAINED_GLASS_PANE,  "§b§l── Tools & Quick Actions ──", "§7Block tools and shortcut commands below"));

        // ── Row 4: Tools ──────────────────────────────────────────────────────
        inv.setStack(19, uiGlint(Items.BLAZE_ROD,     "§6Rainbow Rectangle",
            "§7Face-paint wand — right-click face → URL"));
        inv.setStack(20, uiGlint(Items.WHITE_CONCRETE,"§fColor Square",
            "§7Flat-color region painter"));
        inv.setStack(21, uiGlint(Items.WHITE_CARPET,  "§fColor Triangle",
            "§7Triangle region painter"));
        inv.setStack(22, uiGlint(Items.COMMAND_BLOCK, "§4§l⚙ Admin Control Panel",
            "§7Full server-wide GUI control"));
        inv.setStack(23, uiGlint(Items.BOOK,          "§b/cb list",
            "§7Shows all custom block IDs in chat"));
        inv.setStack(24, uiGlint(Items.WRITABLE_BOOK, "§b/cb help",
            "§7Shows all /cb commands and usage"));
        inv.setStack(25, ui(Items.CYAN_STAINED_GLASS_PANE, "§r"));
        // Stats on right
        inv.setStack(26, ui(Items.EMERALD, "§a§lStats",
            "§7Blocks used: §f"+SlotManager.usedSlots()+" §7/ §f"+SlotManager.MAX_SLOTS,
            "§7Free slots:  §f"+SlotManager.freeSlots(),
            "§7Undo depth:  §e"+undoSz+" §7/ Redo: §b"+redoSz,
            "§8Press §fESC §8to close this menu"));

        // ── Undo/Redo prominent banner ─────────────────────────────────────────
        for (int i = 27; i <= 35; i++) {
            if (i == 27) inv.setStack(i, ui(Items.ORANGE_STAINED_GLASS_PANE, "§6§l── Undo / Redo History ──",
                "§eUndo stack: §f"+undoSz+" actions", "§3Redo stack: §f"+redoSz+" actions"));
            else if (i == 28 && undoSz > 0) inv.setStack(i, uiGlint(Items.GOLDEN_PICKAXE, "§6↩ Undo Next",
                "§e\"" + SlotManager.peekUndoDescription() + "\"", "§8Slot 2 also undoes"));
            else if (i == 29 && redoSz > 0) inv.setStack(i, uiGlint(Items.DIAMOND_PICKAXE, "§b↪ Redo Next",
                "§3\"" + SlotManager.peekRedoDescription() + "\"", "§8Slot 3 also redoes"));
            else inv.setStack(i, glass());
        }

        for (int i = 36; i <= 53; i++) inv.setStack(i, glass());
        inv.setStack(49, ui(Items.COMPASS, "§7Server Info",
            "§7Blocks: §f"+SlotManager.usedSlots()+" §8/ §f"+SlotManager.MAX_SLOTS,
            "§7Free: §f"+SlotManager.freeSlots(),
            "§7Undo: §e"+undoSz+" §7actions | Redo: §b"+redoSz));
        return inv;
    }

    /** Peek the description of the 2nd item in the undo stack (after the top). */
    private static String peekSecondUndo() {
        // We can't directly peek position 2 from a Deque easily via the public API,
        // but we can get a rough description from the stack size
        return SlotManager.undoStackSize() > 1 ? "(+" + (SlotManager.undoStackSize()-1) + " more)" : "";
    }

    private static SimpleInventory buildAdminGui(int blockPage) {
        SimpleInventory inv = new SimpleInventory(54);
        List<SlotManager.SlotData> blocks = sortedBlocks();
        int undoSz = SlotManager.undoStackSize();
        int redoSz = SlotManager.redoStackSize();

        inv.setStack(0, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Main Menu", "§8(or press ESC)"));
        inv.setStack(1, glass()); inv.setStack(2, glass());
        inv.setStack(3, ui(Items.BEACON, "§4§l⚙ Admin Control Panel",
            "§7Server-wide CustomBlocks control",
            "§8Blocks: §f" + SlotManager.usedSlots() + " §7/ §f" + SlotManager.MAX_SLOTS,
            "§8Undo: §e" + undoSz + " §7| Redo: §b" + redoSz));
        inv.setStack(4, glass()); inv.setStack(5, glass()); inv.setStack(6, glass()); inv.setStack(7, glass());
        inv.setStack(8, glass());

        // Row 1: Quick actions
        inv.setStack(9, ui(Items.PURPLE_STAINED_GLASS_PANE, "§5§l── Quick Commands ──", "§7Click any to run"));
        inv.setStack(10, uiGlint(Items.LIME_CONCRETE,   "§a§l➕ Create Block",    "§7Type ID/name/URL in chat"));
        inv.setStack(11, uiGlint(Items.CHEST,            "§b§l📂 Browse All Blocks","§7Open the block picker"));
        inv.setStack(12, ui(Items.TNT,                   "§c§l❌ Delete Block",     "§7Type block ID in chat"));
        inv.setStack(13, uiGlint(Items.NETHER_STAR,      "§f§l🔄 Reload",          "§7/cb reload"));
        inv.setStack(14, uiGlint(Items.BOOK,             "§b/cb list",             "§7List all blocks in chat"));
        inv.setStack(15, uiGlint(Items.PAPER,            "§f§l📤 Export",          "§7/cb export"));
        inv.setStack(16, uiGlint(Items.HOPPER,           "§f§l📥 Import Folder",   "§7/cb importfolder"));
        inv.setStack(17, glass());

        // Row 2: Undo/Redo
        inv.setStack(18, ui(Items.ORANGE_STAINED_GLASS_PANE, "§6§l── Undo / Redo ──",
            "§eUndo: §f" + undoSz + " actions", "§3Redo: §f" + redoSz + " actions"));
        inv.setStack(19, undoSz > 0
            ? uiGlint(Items.GOLDEN_PICKAXE, "§6§l↩ UNDO  §e(" + undoSz + " left)",
                "§eNext: §f\"" + SlotManager.peekUndoDescription() + "\"")
            : ui(Items.GRAY_STAINED_GLASS_PANE, "§8Undo §7(empty)", ""));
        inv.setStack(20, redoSz > 0
            ? uiGlint(Items.DIAMOND_PICKAXE, "§b§l↪ REDO  §3(" + redoSz + " left)",
                "§3Next: §f\"" + SlotManager.peekRedoDescription() + "\"")
            : ui(Items.GRAY_STAINED_GLASS_PANE, "§8Redo §7(empty)", ""));
        for (int i = 21; i <= 26; i++) inv.setStack(i, glass());

        // Block list — slots 27-53 (up to 27 blocks quick-access)
        inv.setStack(27, ui(Items.LIME_STAINED_GLASS_PANE, "§a§l── All Blocks (click to edit) ──",
            "§7" + blocks.size() + " blocks total"));
        for (int i = 0; i < Math.min(26, blocks.size()); i++) {
            SlotManager.SlotData d = blocks.get(i);
            ItemStack s = CustomBlocksMod.safeSlotItem(d.index) != null
                ? new ItemStack(CustomBlocksMod.safeSlotItem(d.index)) : ItemStack.EMPTY;
            s.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§f§l" + d.displayName).styled(st -> st.withItalic(false)));
            s.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                lore("§7ID: §b" + d.customId),
                lore("§7Shape: §5" + d.shapeLabel() + "  §7Light: §e" + d.lightLevel),
                lore("§8Click to open editor"))));
            inv.setStack(28 + i, s);
        }
        for (int i = 28 + Math.min(26, blocks.size()); i <= 53; i++) inv.setStack(i, glass());
        return inv;
    }

    private static SimpleInventory buildPicker(int page, boolean brokenOnly) {
        SimpleInventory inv = new SimpleInventory(54);
        List<SlotManager.SlotData> blocks = brokenOnly ? brokenBlocks() : sortedBlocks();
        int total = blocks.size(), maxPage = total==0?0:Math.max(0,(total-1)/BLOCKS_PER_PAGE);
        inv.setStack(0, uiGlint(Items.RED_CONCRETE,"§c◀ Back to Main Menu","§8(or press ESC)"));
        for (int i=1;i<=3;i++) inv.setStack(i,glass());
        inv.setStack(4, ui(Items.ENCHANTED_BOOK,"§e§lChoose a Block to Edit",
            "§7Click any block below to open its full editor",
            "§8"+Math.min(BLOCKS_PER_PAGE,Math.max(0,total-page*BLOCKS_PER_PAGE))+" of §f"+total+" §8blocks  •  Page §f"+(page+1)+"§8/§f"+(maxPage+1)));
        for (int i=5;i<=8;i++) inv.setStack(i,glass());
        for (int i=9;i<=17;i++) inv.setStack(i, ui(Items.BLUE_STAINED_GLASS_PANE,"§r"));
        int start = page * BLOCKS_PER_PAGE;
        for (int i=0; i<BLOCKS_PER_PAGE; i++) {
            int invSlot = 18+i, dataIdx = start+i;
            if (dataIdx < blocks.size()) {
                SlotManager.SlotData d = blocks.get(dataIdx);
                ItemStack s = CustomBlocksMod.safeSlotItem(d.index)!=null ? new ItemStack(CustomBlocksMod.safeSlotItem(d.index)) : ItemStack.EMPTY;
                s.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§f§l"+d.displayName).styled(st->st.withItalic(false)));
                List<String> ll = new ArrayList<>(List.of("§7ID: §b"+d.customId,"§7Shape: §5"+d.shapeLabel()+" §8• §7Light: §e"+d.lightLevel,"§7Sound: §f"+d.soundType+" §8• §7Hard: §f"+hardnessLabel(d.hardness)));
                List<String> tags=new ArrayList<>(); if(d.hasFaces())tags.add("§d⬡faces"); if(d.isAnimated())tags.add("§b⟳anim"); if(d.noCollision)tags.add("§c⊘nocol"); if(!tags.isEmpty())ll.add(String.join("  ",tags));
                ll.add("§8§oClick to open editor");
                s.set(DataComponentTypes.LORE, new LoreComponent(ll.stream().map(l->(Text)lore(l)).toList()));
                inv.setStack(invSlot, s);
            } else { inv.setStack(invSlot, glass()); }
        }
        for (int i=36;i<=44;i++) inv.setStack(i, ui(Items.BLUE_STAINED_GLASS_PANE,"§r"));
        inv.setStack(45, page>0 ? uiGlint(Items.ARROW,"§7◀ Previous Page","§8Page "+page+" / "+(maxPage+1)) : ui(Items.GRAY_STAINED_GLASS_PANE,"§8◀ First Page",""));
        for (int i=46;i<=48;i++) inv.setStack(i,glass());
        inv.setStack(49, ui(Items.PAPER,"§ePage §f"+(page+1)+" §7/ §f"+(maxPage+1),"§7Total: §f"+total+" blocks"));
        for (int i=50;i<=52;i++) inv.setStack(i,glass());
        inv.setStack(53, page<maxPage ? uiGlint(Items.ARROW,"§7Next Page ▶","§8Page "+(page+2)+" / "+(maxPage+1)) : ui(Items.GRAY_STAINED_GLASS_PANE,"§8Last Page ▶",""));
        return inv;
    }

    private static SimpleInventory buildEditor(SlotManager.SlotData d, boolean confirmDelete) {
        SimpleInventory inv = new SimpleInventory(54);
        inv.setStack(0, uiGlint(Items.RED_CONCRETE,"§c◀ Back to Block List","§8(or press ESC)"));
        ItemStack disp = CustomBlocksMod.safeSlotItem(d.index)!=null?new ItemStack(CustomBlocksMod.safeSlotItem(d.index)):ItemStack.EMPTY;
        disp.set(DataComponentTypes.CUSTOM_NAME,Text.literal("§e§l"+d.displayName).styled(s->s.withItalic(false)));
        disp.set(DataComponentTypes.LORE,new LoreComponent(List.of(lore("§7ID: §b"+d.customId),lore("§7Shape: §5"+d.shapeLabel()),lore("§7Collision: "+(d.noCollision?"§cOFF":"§aON")),lore("§7Light: §e"+d.lightLevel+"  §7Hard: §f"+hardnessLabel(d.hardness)),lore("§7Sound: §f"+d.soundType),lore("§8Slot #"+d.index))));
        inv.setStack(1, disp);
        inv.setStack(2, uiGlint(Items.CHEST,       "§a▶ Give 1x",                        "§7Gives 1x §f"+d.displayName+" §7to you"));
        inv.setStack(3, uiGlint(Items.NAME_TAG,    "§e✎ Rename",                          "§7Current: §f"+d.displayName, "§8Click → type in chat"));
        inv.setStack(4, uiGlint(Items.COMMAND_BLOCK,"§b⇄ Re-ID",                          "§7Current: §b"+d.customId,   "§8Click → type in chat"));
        inv.setStack(5, glass());
        inv.setStack(6, uiGlint(Items.COMPARATOR,  "§e⧉ Duplicate",                       "§7Creates a copy of this block",               "§8Click → type new ID"));
        inv.setStack(7, glass());
        inv.setStack(8, confirmDelete
            ? uiGlint(Items.BARRIER, "§4§l⚠ CLICK AGAIN TO CONFIRM DELETE",               "§cThis will permanently delete: §f"+d.customId, "§c(Use Undo in main menu to restore)")
            : ui(Items.TNT,          "§c§l⚠ Delete This Block",                            "§7First click arms.  Second click deletes.",    "§8Undo available in main menu."));
        inv.setStack(9,  uiGlint(Items.PAINTING,              "§b⬛ Retexture All Faces",  "§7Replace texture on all faces",               "§8Click → paste URL"));
        inv.setStack(10, uiGlint(Items.ITEM_FRAME,            "§d⬡ Open Full Face Editor","§7Per-face textures + variant creation",        "§8Supports 6 independent face textures"));
        inv.setStack(11, faceBtn(d,Items.WHITE_CONCRETE,       "top",    "§f▲ TOP"));
        inv.setStack(12, faceBtn(d,Items.CYAN_CONCRETE,        "north",  "§b▶ NORTH"));
        inv.setStack(13, faceBtn(d,Items.BLUE_CONCRETE,        "south",  "§9▶ SOUTH"));
        inv.setStack(14, faceBtn(d,Items.PURPLE_CONCRETE,      "east",   "§5▶ EAST"));
        inv.setStack(15, faceBtn(d,Items.MAGENTA_CONCRETE,     "west",   "§d▶ WEST"));
        inv.setStack(16, faceBtn(d,Items.LIGHT_GRAY_CONCRETE,  "bottom", "§7▼ BOTTOM"));
        inv.setStack(17, ui(Items.ORANGE_CONCRETE,             "§6⊘ Clear ALL Face Overrides","§7Resets all faces to default texture"));
        inv.setStack(18, ui(Items.CYAN_STAINED_GLASS_PANE,"§7── Clear Individual Faces ──","§8Click any face button to remove its override"));
        inv.setStack(19, clearFaceBtn(d,Items.WHITE_STAINED_GLASS_PANE,      "top",    "§f✕ Clear TOP"));
        inv.setStack(20, clearFaceBtn(d,Items.CYAN_STAINED_GLASS_PANE,       "north",  "§b✕ Clear NORTH"));
        inv.setStack(21, clearFaceBtn(d,Items.BLUE_STAINED_GLASS_PANE,       "south",  "§9✕ Clear SOUTH"));
        inv.setStack(22, clearFaceBtn(d,Items.PURPLE_STAINED_GLASS_PANE,     "east",   "§5✕ Clear EAST"));
        inv.setStack(23, clearFaceBtn(d,Items.MAGENTA_STAINED_GLASS_PANE,    "west",   "§d✕ Clear WEST"));
        inv.setStack(24, clearFaceBtn(d,Items.LIGHT_GRAY_STAINED_GLASS_PANE, "bottom", "§7✕ Clear BOTTOM"));
        inv.setStack(25, glass()); inv.setStack(26, glass());
        inv.setStack(27, ui(Items.RED_DYE,         "§c◀ Light -1",               "§7Now: §e"+d.lightLevel));
        inv.setStack(28, uiGlint(Items.GLOWSTONE_DUST,"§e✦ Light: §f"+d.lightLevel,"§70=off • 7=torch • 14=sea lantern • 15=max"));
        inv.setStack(29, ui(Items.YELLOW_DYE,      "§a▶ Light +1",               "§7Now: §e"+d.lightLevel));
        inv.setStack(30, ui(Items.RED_DYE,         "§c◀ Hardness -",             "§7Now: §f"+hardnessLabel(d.hardness)));
        inv.setStack(31, ui(Items.IRON_PICKAXE,    "§b⚙ Hardness: §f"+hardnessLabel(d.hardness),"§7-1=Unbreakable • 0=Instant • 1.5=Default"));
        inv.setStack(32, ui(Items.LIME_DYE,        "§a▶ Hardness +",             "§7Now: §f"+hardnessLabel(d.hardness)));
        inv.setStack(33, glass());
        inv.setStack(34, uiGlint(Items.ENDER_PEARL,"§5⬡ Shape Editor",           "§7Current: §b"+d.shapeLabel(),"§8Left-click preset=new block • Right-click=modify"));
        inv.setStack(35, d.noCollision
            ? uiGlint(Items.BARRIER,    "§c⊘ Collision: §lOFF","§7Block has NO hitbox","§8Click to ENABLE collision")
            : uiGlint(Items.SLIME_BLOCK,"§a✔ Collision: §lON", "§7Block has normal collision","§8Click to DISABLE collision"));
        inv.setStack(36,soundItem(d,"stone",       Items.STONE,             "§fStone"));
        inv.setStack(37,soundItem(d,"wood",        Items.OAK_LOG,           "§fWood"));
        inv.setStack(38,soundItem(d,"grass",       Items.GRASS_BLOCK,       "§fGrass"));
        inv.setStack(39,soundItem(d,"metal",       Items.IRON_BLOCK,        "§fMetal"));
        inv.setStack(40,soundItem(d,"glass",       Items.GLASS,             "§fGlass"));
        inv.setStack(41,soundItem(d,"sand",        Items.SAND,              "§fSand"));
        inv.setStack(42,soundItem(d,"gravel",      Items.GRAVEL,            "§fGravel"));
        inv.setStack(43,soundItem(d,"wool",        Items.WHITE_WOOL,        "§fWool"));
        inv.setStack(44,soundItem(d,"snow",        Items.SNOW_BLOCK,        "§fSnow"));
        inv.setStack(45,soundItem(d,"dirt",        Items.DIRT,              "§fDirt"));
        inv.setStack(46,soundItem(d,"coral",       Items.TUBE_CORAL_BLOCK,  "§fCoral"));
        inv.setStack(47,soundItem(d,"bamboo",      Items.BAMBOO,            "§fBamboo"));
        inv.setStack(48,soundItem(d,"nether_brick",Items.NETHER_BRICKS,     "§fNether Brick"));
        inv.setStack(49,soundItem(d,"ice",         Items.ICE,               "§fIce"));
        inv.setStack(50,soundItem(d,"honey",       Items.HONEY_BLOCK,       "§fHoney"));
        inv.setStack(51,soundItem(d,"bone",        Items.BONE_BLOCK,        "§fBone"));
        inv.setStack(52,soundItem(d,"slime",       Items.SLIME_BLOCK,       "§fSlime"));
        inv.setStack(53, glass());
        return inv;
    }

    private static SimpleInventory buildShapeEditor(SlotManager.SlotData d, int boxPage) {
        SimpleInventory inv = new SimpleInventory(54);
        List<SlotManager.ShapeBox> boxes = d.shapeBoxes!=null?d.shapeBoxes:List.of();
        Item[] pItems = {Items.GRASS_BLOCK,Items.SMOOTH_STONE_SLAB,Items.STONE_SLAB,Items.MOSS_CARPET,Items.COBBLESTONE_WALL,Items.COMPARATOR,Items.COMPARATOR,Items.OAK_TRAPDOOR,Items.OAK_TRAPDOOR,Items.OAK_FENCE,Items.OAK_STAIRS,Items.TALL_GRASS};
        inv.setStack(0, uiGlint(Items.RED_CONCRETE,"§c◀ Back to Editor","§8(or press ESC)"));
        for (int i=1;i<=3;i++) inv.setStack(i,glass());
        ItemStack info = CustomBlocksMod.safeSlotItem(d.index)!=null?new ItemStack(CustomBlocksMod.safeSlotItem(d.index)):ItemStack.EMPTY;
        info.set(DataComponentTypes.CUSTOM_NAME,Text.literal("§e§l"+d.displayName).styled(s->s.withItalic(false)));
        info.set(DataComponentTypes.LORE,new LoreComponent(List.of(lore("§7ID: §b"+d.customId),lore("§7Shape: §5"+d.shapeLabel()),lore("§7Custom boxes: §f"+boxes.size()+" §8/ 16"),lore("§7Collision: "+(d.noCollision?"§cOFF":"§aON")),lore("§8§o§nLeft-click§r§8§o preset = create NEW block with that shape"),lore("§8§o§nRight-click§r§8§o preset = apply shape to THIS block"))));
        inv.setStack(4, info);
        for (int i=5;i<=7;i++) inv.setStack(i,glass());
        inv.setStack(8, d.noCollision?uiGlint(Items.BARRIER,"§c⊘ Collision: §lOFF","§8Click to ENABLE"):uiGlint(Items.SLIME_BLOCK,"§a✔ Collision: §lON","§8Click to DISABLE"));
        inv.setStack(9, ui(Items.BLUE_STAINED_GLASS_PANE,"§9── Shape Presets ──","§7§nLeft-click§r§7 = new block  •  §7§nRight-click§r§7 = apply here"));
        for (int i=0; i<8 && i<PRESET_NAMES.length; i++) {
            String p=PRESET_NAMES[i]; boolean act=boxes.equals(SlotManager.SHAPE_PRESETS.get(p))||(boxes.isEmpty()&&"full".equals(p));
            inv.setStack(10+i, act?uiGlint(pItems[i],"§a§l"+p.toUpperCase()+" §a✔","§aActive • §8Left=new block  Right=apply here"):ui(pItems[i],"§b"+cap(p),"§7Preset shape","§8Left-click=new block  •  Right-click=apply here"));
        }
        for (int i=8; i<PRESET_NAMES.length && i<12; i++) {
            String p=PRESET_NAMES[i]; boolean act=boxes.equals(SlotManager.SHAPE_PRESETS.get(p));
            inv.setStack(10+i, act?uiGlint(pItems[i],"§a§l"+p.toUpperCase()+" §a✔","§aActive • §8Left=new block  Right=apply here"):ui(pItems[i],"§b"+cap(p),"§7Preset shape","§8Left-click=new block  •  Right-click=apply here"));
        }
        inv.setStack(22, uiGlint(Items.LIME_DYE,"§a➕ Add Custom Box","§7Click → type coords","§8Format: x1,y1,z1,x2,y2,z2  (0–16)","§8Up to 16 boxes"));
        inv.setStack(23, ui(Items.ORANGE_DYE,"§6⊘ Clear All Boxes","§7Reset to full cube","§8Removes all custom shape boxes"));
        for (int i=24;i<=26;i++) inv.setStack(i,glass());
        inv.setStack(27, ui(Items.PURPLE_STAINED_GLASS_PANE,"§5── Custom Boxes §8(click = remove) ──","§7Defines the block's physical shape / hitbox"));
        int bstart = boxPage*9;
        for (int i=0;i<8&&(bstart+i)<boxes.size();i++) { SlotManager.ShapeBox b=boxes.get(bstart+i); inv.setStack(28+i,ui(Items.STRUCTURE_VOID,"§e§lBox #"+(bstart+i),"§7"+b.toDisplayString(),"§8Click to remove")); }
        for (int s=28+Math.min(8,Math.max(0,boxes.size()-bstart));s<=35;s++) inv.setStack(s,glass());
        List<SlotManager.SlotData> variants = findShapeVariants(d.customId);
        inv.setStack(36, ui(Items.LIME_STAINED_GLASS_PANE,"§a── Shape Variants §8(click to edit) ──","§7Blocks created from this block via presets","§8"+variants.size()+" variant(s)"));
        for (int i=0;i<Math.min(8,variants.size());i++) {
            SlotManager.SlotData v=variants.get(i);
            ItemStack vs=CustomBlocksMod.safeSlotItem(v.index)!=null?new ItemStack(CustomBlocksMod.safeSlotItem(v.index)):ItemStack.EMPTY;
            vs.set(DataComponentTypes.CUSTOM_NAME,Text.literal("§f§l"+v.displayName).styled(s->s.withItalic(false)));
            vs.set(DataComponentTypes.LORE,new LoreComponent(List.of(lore("§7ID: §b"+v.customId),lore("§7Shape: §5"+v.shapeLabel()),lore("§8Click to open this variant's editor"))));
            inv.setStack(37+i,vs);
        }
        for (int s=37+Math.min(8,variants.size());s<=44;s++) inv.setStack(s,glass());
        int tbp=boxes.isEmpty()?0:Math.max(0,(boxes.size()-1)/9);
        inv.setStack(45,boxPage>0?uiGlint(Items.ARROW,"§7◀ Prev Boxes","§8Page "+boxPage):glass());
        for(int i=46;i<=48;i++) inv.setStack(i,glass());
        inv.setStack(49,ui(Items.PAPER,"§7Boxes §f"+(boxPage+1)+" §7/ §f"+(tbp+1),"§7Total: §f"+boxes.size()+" box(es)"));
        for(int i=50;i<=52;i++) inv.setStack(i,glass());
        inv.setStack(53,boxPage<tbp?uiGlint(Items.ARROW,"§7Next Boxes ▶","§8Page "+(boxPage+2)):glass());
        return inv;
    }

    private static SimpleInventory buildFaceEditor(SlotManager.SlotData d) {
        SimpleInventory inv = new SimpleInventory(54);
        inv.setStack(0, uiGlint(Items.RED_CONCRETE,"§c◀ Back to Editor","§8(or press ESC)"));
        for(int i=1;i<=3;i++) inv.setStack(i,glass());
        ItemStack disp=CustomBlocksMod.safeSlotItem(d.index)!=null?new ItemStack(CustomBlocksMod.safeSlotItem(d.index)):ItemStack.EMPTY;
        disp.set(DataComponentTypes.CUSTOM_NAME,Text.literal("§d§l⬡ §r§f"+d.displayName).styled(s->s.withItalic(false)));
        disp.set(DataComponentTypes.LORE,new LoreComponent(List.of(lore("§7ID: §b"+d.customId),lore("§a§nLeft button§r§7 = edit this face §8(modifies block)"),lore("§b§nRight button§r§7 = create variant §8(keeps original)"))));
        inv.setStack(4,disp);
        for(int i=5;i<=8;i++) inv.setStack(i,glass());
        String[][] faces={{"top","▲ TOP"},{"bottom","▼ BOTTOM"},{"north","N NORTH"},{"south","S SOUTH"},{"east","E EAST"},{"west","W WEST"}};
        int[] es={9,11,13,15,17,19}, vs={10,12,14,16,18,20};
        Item[] fi={Items.WHITE_CONCRETE,Items.LIGHT_GRAY_CONCRETE,Items.CYAN_CONCRETE,Items.BLUE_CONCRETE,Items.PURPLE_CONCRETE,Items.MAGENTA_CONCRETE};
        for (int fi2=0;fi2<6;fi2++) {
            boolean has=d.faceTextures.containsKey(faces[fi2][0]); String st=has?"§aOverride ACTIVE":"§7Default texture";
            inv.setStack(es[fi2],uiGlint(fi[fi2],"§a✏ Edit §f"+faces[fi2][1]+" §7(in place)",st,"§8Modifies block directly","§8Click → paste URL"));
            inv.setStack(vs[fi2],ui(Items.PAPER,"§b✦ Variant §f"+faces[fi2][1],st,"§8Creates new block with this face","§8Original untouched","§8Click → paste URL"));
        }
        for(int s:new int[]{21,22,23,24,25,26}) inv.setStack(s,glass());
        inv.setStack(27,ui(Items.WHITE_STAINED_GLASS_PANE,      "§c✕ Clear TOP",    faceStatus(d,"top")));
        inv.setStack(28,ui(Items.LIGHT_GRAY_STAINED_GLASS_PANE, "§c✕ Clear BOTTOM", faceStatus(d,"bottom")));
        inv.setStack(29,ui(Items.CYAN_STAINED_GLASS_PANE,       "§c✕ Clear NORTH",  faceStatus(d,"north")));
        inv.setStack(30,ui(Items.BLUE_STAINED_GLASS_PANE,       "§c✕ Clear SOUTH",  faceStatus(d,"south")));
        inv.setStack(31,ui(Items.PURPLE_STAINED_GLASS_PANE,     "§c✕ Clear EAST",   faceStatus(d,"east")));
        inv.setStack(32,ui(Items.MAGENTA_STAINED_GLASS_PANE,    "§c✕ Clear WEST",   faceStatus(d,"west")));
        for(int i=33;i<=44;i++) inv.setStack(i,glass());
        inv.setStack(45,uiGlint(Items.RED_CONCRETE,  "§c◀ Back to Editor","§8(or press ESC)"));
        inv.setStack(46,SlotManager.undoStackSize()>0?uiGlint(Items.GOLDEN_PICKAXE,"§6↩ Undo","§7"+SlotManager.undoStackSize()+" action(s) left"):ui(Items.GRAY_STAINED_GLASS_PANE,"§8Undo","§7Nothing to undo"));
        inv.setStack(47,ui(Items.ORANGE_CONCRETE,"§6⊘ Clear ALL Overrides","§7Reverts every face to default texture"));
        for(int i=48;i<=52;i++) inv.setStack(i,glass());
        inv.setStack(53,uiGlint(Items.CHEST,"§a▶ Give 1x","§7Gives 1x §f"+d.displayName));
        return inv;
    }

    // ── Anim GUI (chest-based animation settings) ─────────────────────────────

    public static void openAnimGui(ServerPlayerEntity player, String id) {
        SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null || !d.isAnimated()) return;

        // Parse current fps + interpolate from stored animMeta
        float fps = 10f;
        boolean interp = false;
        int frameCount = 1;
        try {
            JsonObject root = JsonParser.parseString(d.animMeta).getAsJsonObject();
            JsonObject anim = root.getAsJsonObject("animation");
            interp = anim.has("interpolate") && anim.get("interpolate").getAsBoolean();
            if (anim.has("frames")) {
                JsonArray framesArr = anim.getAsJsonArray("frames");
                frameCount = framesArr.size();
                if (frameCount > 0) {
                    long totalTicks = 0;
                    for (JsonElement el : framesArr) {
                        int t = el.isJsonObject() && el.getAsJsonObject().has("time")
                            ? el.getAsJsonObject().get("time").getAsInt() : 1;
                        totalTicks += t;
                    }
                    fps = Math.round((20f / ((float) totalTicks / frameCount)) * 10f) / 10f;
                }
            } else if (anim.has("frametime")) {
                int ft = anim.get("frametime").getAsInt();
                fps = Math.round((20f / ft) * 10f) / 10f;
            }
        } catch (Exception ignored) {}

        final float finalFps = fps;
        final boolean finalInterp = interp;
        final int finalFrames = frameCount;
        ANIM_PARAMS.put(player.getUuid(), new AnimParams(fps, interp, frameCount));
        STATES.put(player.getUuid(), GuiState.animGui(id));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildAnimGui(id, finalFps, finalInterp, finalFrames)),
            Text.literal("§b§l▶ §r§fAnimation Settings §8— §b" + d.displayName)));
    }

    private static SimpleInventory buildAnimGui(String id, float fps, boolean interp, int frameCount) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        SlotManager.SlotData d = SlotManager.getById(id);
        String blockName = d != null ? d.displayName : id;
        int ticks = Math.max(1, Math.round(20f / Math.max(0.5f, fps)));

        // Row 0 – info header
        inv.setStack(4, uiGlint(Items.PAPER,
            "§e§l" + blockName,
            "§7Frames: §f" + frameCount,
            "§7Current FPS: §f" + String.format("%.1f", fps),
            "§7" + ticks + " tick(s)/frame  §8(20 ticks = 1 sec)",
            "§7Interpolate: " + (interp ? "§aON" : "§cOFF")));

        // Row 1 – FPS adjust (slots 9-17)
        inv.setStack(9,  ui(Items.SPECTRAL_ARROW, "§c§l<< §r§c-10 fps"));
        inv.setStack(10, ui(Items.ARROW,           "§c§l<  §r§c-1 fps"));
        inv.setStack(13, uiGlint(Items.CLOCK,
            "§e" + String.format("%.1f", fps) + " fps",
            "§7= " + ticks + " tick(s)/frame"));
        inv.setStack(16, ui(Items.ARROW,           "§a+1 fps  §l>"));
        inv.setStack(17, ui(Items.SPECTRAL_ARROW,  "§a+10 fps §l>>"));

        // Row 2 – speed presets (slots 18-26)
        inv.setStack(18, ui(Items.LIME_DYE,   "§a5 fps  §8— Slow"));
        inv.setStack(20, ui(Items.YELLOW_DYE, "§e10 fps §8— Normal"));
        inv.setStack(22, ui(Items.GOLD_INGOT, "§620 fps §8— Fast"));
        inv.setStack(24, ui(Items.BLAZE_ROD,  "§c30 fps §8— Ultra"));

        // Row 3 – interpolate toggle (slot 27)
        inv.setStack(27, interp
            ? uiGlint(Items.LIME_WOOL,  "§aInterpolate: ON",  "§7Smooth transition between frames", "§8Click to toggle OFF")
            : ui(Items.RED_WOOL,        "§cInterpolate: OFF", "§7No smoothing between frames",      "§8Click to toggle ON"));

        // Row 5 – apply + close
        inv.setStack(46, uiGlint(Items.EMERALD, "§a§lAPPLY SETTINGS", "§7Saves and broadcasts to all players"));
        inv.setStack(49, ui(Items.BARRIER,       "§c§lCLOSE",          "§7Discard changes and close"));

        return inv;
    }

    private static void handleAnimGuiClick(ServerPlayerEntity player, GuiState state, int slot) {
        String id = state.editingId();
        AnimParams p = ANIM_PARAMS.getOrDefault(player.getUuid(), new AnimParams(10f, false, 1));
        float fps = p.fps();
        boolean interp = p.interpolate();
        int frames = p.frameCount();

        switch (slot) {
            case 9  -> fps = Math.max(0.5f, fps - 10);
            case 10 -> fps = Math.max(0.5f, fps - 1);
            case 16 -> fps = Math.min(60f,  fps + 1);
            case 17 -> fps = Math.min(60f,  fps + 10);
            case 18 -> fps = 5f;
            case 20 -> fps = 10f;
            case 22 -> fps = 20f;
            case 24 -> fps = 30f;
            case 27 -> interp = !interp;
            case 46 -> {
                applyAnimSettings(player, id, fps, interp, frames);
                ANIM_PARAMS.remove(player.getUuid());
                STATES.remove(player.getUuid());
                player.closeHandledScreen();
                return;
            }
            case 49 -> {
                ANIM_PARAMS.remove(player.getUuid());
                STATES.remove(player.getUuid());
                player.closeHandledScreen();
                return;
            }
            default -> { return; }
        }

        // Round fps to 1 decimal
        fps = Math.round(fps * 10f) / 10f;
        ANIM_PARAMS.put(player.getUuid(), new AnimParams(fps, interp, frames));
        refreshScreen(player, buildAnimGui(id, fps, interp, frames));
    }

    private static void applyAnimSettings(ServerPlayerEntity player, String id, float fps, boolean interp, int frameCount) {
        if (!SlotManager.hasId(id)) return;
        int tickTime = Math.max(1, Math.round(20f / Math.max(0.5f, fps)));
        StringBuilder sb = new StringBuilder("{\"animation\":{");
        if (interp) sb.append("\"interpolate\":true,");
        sb.append("\"frames\":[");
        for (int i = 0; i < frameCount; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"index\":").append(i).append(",\"time\":").append(tickTime).append("}");
        }
        sb.append("]}}");
        String newMeta = sb.toString();
        SlotManager.pushUndo(id, "animsettings");
        SlotManager.setAnimMeta(id, newMeta);
        SlotManager.saveAll();
        SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null) return;
        SlotUpdatePayload pkt = new SlotUpdatePayload("animsettings", d.index, id, d.displayName,
                null, d.lightLevel, d.hardness, d.soundType, null, null, newMeta);
        CustomBlocksMod.broadcastUpdate(player.getServer(), pkt);
        send(player, "§a[CustomBlocks] Animation saved for '§f" + d.displayName + "§a'  §7(" + String.format("%.1f", fps) + " fps, " + (interp ? "interpolate ON" : "interpolate OFF") + ")");
    }

    // ── Small helpers ─────────────────────────────────────────────────────────

    private static void promptFace(ServerPlayerEntity player, String blockId, String face, int rp, boolean variant) {
        InputAction action = variant ? InputAction.SETFACE_VARIANT_URL : InputAction.SETFACE_URL;
        PENDING.put(player.getUuid(), new PendingInput(action, blockId, face, null, null, rp));
        player.closeHandledScreen();
        String mode = variant ? "§b(creates variant — original untouched)" : "§a(modifies this block)";
        send(player, "§6[GUI] §ePaste URL for §f"+face.toUpperCase()+" §eof '§f"+blockId+"§e' "+mode+":");
        send(player, "§7Type §ccancel §7to abort.");
    }

    private static void clearFace(ServerPlayerEntity player, SlotManager.SlotData d, String face) {
        SlotManager.pushUndo(d.customId,"clearface "+face); SlotManager.clearFaceTexture(d.customId,face); SlotManager.saveAll();
        CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload("clearface",d.index,d.customId,null,null,d.lightLevel,d.hardness,d.soundType,face));
        GuiState st = STATES.get(player.getUuid());
        if (st!=null&&st.mode()==GuiMode.FACE_EDITOR) openFaceEditor(player,d.customId,st.page());
        else openEditor(player,d.customId, STATES.getOrDefault(player.getUuid(),GuiState.main(0)).page());
    }

    private static void broadcastClearAllFaces(ServerPlayerEntity player, SlotManager.SlotData d) {
        CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload("clearfaces",d.index,d.customId,null,null,d.lightLevel,d.hardness,d.soundType));
    }

    /** Set sound silently (no chat message) — used with in-place refresh. */
    private static void setSoundQuiet(ServerPlayerEntity player, SlotManager.SlotData d, String sound) {
        SlotManager.pushUndo(d.customId,"setsound"); SlotManager.setSoundType(d.customId,sound); SlotManager.saveAll();
        CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload("setprop",d.index,d.customId,null,null,d.lightLevel,d.hardness,sound));
    }

    private static void setSoundAndRefresh(ServerPlayerEntity player, SlotManager.SlotData d, String sound, int rp) {
        setSoundQuiet(player, d, sound);
        openEditor(player,d.customId,rp);
    }

    private static void syncProp(ServerPlayerEntity player, SlotManager.SlotData dOld) {
        SlotManager.SlotData d = SlotManager.getById(dOld.customId); if(d==null) return;
        SlotManager.saveAll();
        CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload("setprop",d.index,d.customId,null,null,d.lightLevel,d.hardness,d.soundType));
    }

    private static List<SlotManager.SlotData> sortedBlocks() {
        List<SlotManager.SlotData> list = new ArrayList<>(SlotManager.allSlots());
        list.removeIf(d->"tab_icon".equals(d.customId));
        list.sort(Comparator.comparingInt(d->d.index));
        return list;
    }

    private static String generateVariantId(String base, String face) {
        String c=base+"_"+face; if(!SlotManager.hasId(c))return c;
        for(int i=2;i<=99;i++){String x=c+"_"+i;if(!SlotManager.hasId(x))return x;}
        return c+"_"+(System.currentTimeMillis()%10000);
    }

    private static String generateShapeVariantId(String base, String preset) {
        String c=base+"_"+preset; if(!SlotManager.hasId(c))return c;
        for(int i=2;i<=99;i++){String x=c+"_"+i;if(!SlotManager.hasId(x))return x;}
        return c+"_"+(System.currentTimeMillis()%10000);
    }

    private static String cap(String s)          { return s==null||s.isEmpty()?"":(char)(Character.toUpperCase(s.charAt(0)))+s.substring(1); }
    private static float nextHardness(float cur) { for(int i=0;i<HARD_CYCLE.length-1;i++) if(Math.abs(cur-HARD_CYCLE[i])<0.01f) return HARD_CYCLE[i+1]; return HARD_CYCLE[1]; }
    private static float prevHardness(float cur) { for(int i=HARD_CYCLE.length-1;i>0;i--) if(Math.abs(cur-HARD_CYCLE[i])<0.01f) return HARD_CYCLE[i-1]; return HARD_CYCLE[0]; }
    private static String hardnessLabel(float h) { if(h<0)return "∞ Unbreakable"; if(h==0)return "0 (Instant)"; return String.valueOf(h); }
    private static String faceStatus(SlotManager.SlotData d, String f) { return d.faceTextures.containsKey(f)?"§aOverride ACTIVE — click to clear":"§8No override set"; }
    private static boolean isUrl(String s)       { return s.startsWith("http://")||s.startsWith("https://"); }
    private static void send(ServerPlayerEntity p,String m) { p.sendMessage(Text.literal(m),false); }
    private static void thread(ServerPlayerEntity p, Runnable r) { Thread t=new Thread(r,"PB-GUI"); t.setDaemon(true); t.start(); }

    private static ItemStack soundItem(SlotManager.SlotData d, String sound, Item item, String label) {
        return sound.equals(d.soundType)?uiGlint(item,label+" §a✔","§aCurrently active"):ui(item,label,"§7Click to use §f"+sound+" §7sound");
    }
    private static ItemStack faceBtn(SlotManager.SlotData d, Item item, String face, String label) {
        boolean h=d.faceTextures.containsKey(face);
        return h?uiGlint(item,label+" §a✔","§aOverride active","§8Click to open Face Editor"):ui(item,label,"§7Default texture","§8Click to open Face Editor");
    }
    private static ItemStack clearFaceBtn(SlotManager.SlotData d, Item item, String face, String label) {
        boolean h=d.faceTextures.containsKey(face);
        return h?uiGlint(item,label,"§aOverride active — click to clear"):ui(item,label,"§8No override set");
    }
    private static ItemStack ui(Item item, String name, String... lore) {
        ItemStack s=new ItemStack(item);
        s.set(DataComponentTypes.CUSTOM_NAME,Text.literal(name).styled(st->st.withItalic(false)));
        if(lore.length>0){List<Text> ll=new ArrayList<>();for(String l:lore)ll.add(lore(l));s.set(DataComponentTypes.LORE,new LoreComponent(ll));}
        return s;
    }
    private static ItemStack uiGlint(Item item, String name, String... lore) { ItemStack s=ui(item,name,lore); s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,true); return s; }
    private static Text lore(String t) { return Text.literal(t).styled(s->s.withItalic(false)); }
    private static ItemStack glass()   { return ui(Items.GRAY_STAINED_GLASS_PANE,"§r"); }
}
