package com.customblocks.gui;

import com.customblocks.CustomBlocksMod;
import com.customblocks.ImageProcessor;
import com.customblocks.SlotManager;
import com.customblocks.block.SlotBlock;
import com.customblocks.network.SlotUpdatePayload;
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

/**
 * CustomBlocks — server-side chest GUI state machine.
 *
 * Screens:
 *   MAIN        — paginated block list, Undo, Create, tab icon
 *   EDITOR      — full editor (texture, faces, glow, hardness, sound, give, delete)
 *   FACE_EDITOR — dedicated per-face editor with variant-create options
 *
 * URL + text inputs are collected via chat (same as RectangleToolItem).
 */
public class GuiManager {

    public enum GuiMode { MAIN, EDITOR, FACE_EDITOR, SHAPE_EDITOR }

    public record GuiState(GuiMode mode, int page, String editingId, boolean confirmDelete, int shapeBoxPage) {
        static GuiState main(int page)              { return new GuiState(GuiMode.MAIN,         page, null, false, 0); }
        static GuiState editor(String id, int p)    { return new GuiState(GuiMode.EDITOR,       p,    id,   false, 0); }
        static GuiState faceEditor(String id, int p){ return new GuiState(GuiMode.FACE_EDITOR,  p,    id,   false, 0); }
        static GuiState shapeEditor(String id, int p){ return new GuiState(GuiMode.SHAPE_EDITOR, p,   id,   false, 0); }
        public GuiState withConfirmDelete()         { return new GuiState(mode, page, editingId, true, shapeBoxPage); }
        public GuiState withShapeBoxPage(int bp)    { return new GuiState(mode, page, editingId, confirmDelete, bp); }
    }

    public enum InputAction {
        CREATE_ID, CREATE_NAME, CREATE_URL,
        RETEXTURE_URL, SETFACE_URL, RENAME_TEXT,
        SETFACE_VARIANT_URL,   // creates a new variant block (like Rectangle)
        SETTABICON_URL,
        ADDSHAPE_COORDS,       // adds a box to the shape editor
        REID_TEXT              // re-id a block
    }

    public record PendingInput(
            InputAction action,
            String  blockId,
            String  face,
            String  partialId,
            String  partialName,
            int     returnPage
    ) {}

    // ── State storage ──────────────────────────────────────────────────────────
    private static final Map<UUID, GuiState>    STATES  = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingInput> PENDING = new ConcurrentHashMap<>();

    // hardness cycle values
    private static final float[] HARD_CYCLE = { -1f, 0f, 0.5f, 1.5f, 3f, 5f, 10f, 50f };
    private static final int BLOCKS_PER_PAGE = 36;

    // ── Public API ─────────────────────────────────────────────────────────────

    public static void openMain(ServerPlayerEntity player, int page) {
        int maxPage = Math.max(0, (sortedBlocks().size() - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, maxPage));
        STATES.put(player.getUuid(), GuiState.main(page));
        SimpleInventory inv = buildMain(page);
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, inv),
            Text.literal("§6§l✦ §r§fCustomBlocks")));
    }

    public static void openEditor(ServerPlayerEntity player, String id, int returnPage) {
        SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        STATES.put(player.getUuid(), GuiState.editor(id, returnPage));
        SimpleInventory inv = buildEditor(d, false);
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, inv),
            Text.literal("§6§l✦ §r§f" + d.displayName)));
    }

    public static void openFaceEditor(ServerPlayerEntity player, String id, int returnPage) {
        SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        STATES.put(player.getUuid(), GuiState.faceEditor(id, returnPage));
        SimpleInventory inv = buildFaceEditor(d);
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, inv),
            Text.literal("§d§l⬡ §r§fFace Editor — §e" + d.displayName)));
    }

    public static void openShapeEditor(ServerPlayerEntity player, String id, int returnPage) {
        SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        STATES.put(player.getUuid(), GuiState.shapeEditor(id, returnPage));
        SimpleInventory inv = buildShapeEditor(d, 0);
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, inv),
            Text.literal("§5§l⬡ §r§fShape Editor — §e" + d.displayName)));
    }

    public static void handleClick(ServerPlayerEntity player, int slot, int button) {
        GuiState state = STATES.get(player.getUuid());
        if (state == null) return;
        switch (state.mode()) {
            case MAIN         -> handleMainClick(player, state, slot);
            case EDITOR       -> handleEditorClick(player, state, slot, button);
            case FACE_EDITOR  -> handleFaceEditorClick(player, state, slot, button);
            case SHAPE_EDITOR -> handleShapeEditorClick(player, state, slot, button);
        }
    }

    public static boolean handleChatInput(ServerPlayerEntity player, String message) {
        PendingInput pending = PENDING.remove(player.getUuid());
        if (pending == null) return false;

        String text    = message.trim();
        String blockId = pending.blockId();
        int    rp      = pending.returnPage();

        if (text.equalsIgnoreCase("cancel")) {
            send(player, "§7[CustomBlocks] Cancelled.");
            if (blockId != null) openEditor(player, blockId, rp);
            else openMain(player, rp);
            return true;
        }

        switch (pending.action()) {

            case CREATE_ID -> {
                String id = text.toLowerCase().replaceAll("[^a-z0-9_]", "_");
                if (id.isEmpty())           { send(player, "§cInvalid ID."); openMain(player, rp); return true; }
                if (SlotManager.hasId(id))  { send(player, "§c'" + id + "' already exists."); openMain(player, rp); return true; }
                PENDING.put(player.getUuid(), new PendingInput(InputAction.CREATE_NAME, id, null, id, null, rp));
                send(player, "§6[GUI] §eNow type a §fdisplay name§e for '" + id + "' (or §ccancel§e):");
                return true;
            }

            case CREATE_NAME -> {
                String name = text.replace("_", " ");
                PENDING.put(player.getUuid(), new PendingInput(InputAction.CREATE_URL, blockId, null, pending.partialId(), name, rp));
                send(player, "§6[GUI] §ePaste the §fimage URL§e for '" + name + "' (or §ccancel§e):");
                return true;
            }

            case CREATE_URL -> {
                if (!isUrl(text)) { send(player, "§cNeeds a URL starting with http:// or https://"); return true; }
                String id   = pending.partialId();
                String name = pending.partialName();
                if (id == null || name == null) { openMain(player, rp); return true; }
                if (SlotManager.freeSlots() == 0) { send(player, "§cAll slots full!"); openMain(player, rp); return true; }
                send(player, "§e[GUI] Downloading '" + name + "'…");
                MinecraftServer server = player.getServer();
                thread(player, () -> {
                    try {
                        byte[] raw = ImageProcessor.download(text);
                        ImageProcessor.GifResult gif = ImageProcessor.isAnimatedGif(raw) ? ImageProcessor.processGif(raw) : null;
                        byte[] bytes; String anim = null;
                        if (gif != null) { bytes = gif.stripPng(); anim = gif.mcmeta(); }
                        else { bytes = ImageProcessor.toPng(raw); bytes = ImageProcessor.padToSquare(bytes); bytes = ImageProcessor.replaceBackground(bytes); bytes = ImageProcessor.resizeTo(bytes, ImageProcessor.DEFAULT_SIZE); }
                        final byte[] fb = bytes; final String fa = anim;
                        server.execute(() -> {
                            if (SlotManager.hasId(id)) { send(player, "§c'" + id + "' already exists."); openMain(player, rp); return; }
                            SlotManager.SlotData d = SlotManager.assign(id, name, fb);
                            if (d == null) { send(player, "§cNo free slots!"); openMain(player, rp); return; }
                            if (fa != null) SlotManager.setAnimMeta(id, fa);
                            SlotManager.pushUndoCreate(id);
                            SlotManager.saveAll();
                            CustomBlocksMod.broadcastUpdate(server, new SlotUpdatePayload("add", d.index, id, name, fb, d.lightLevel, d.hardness, d.soundType));
                            send(player, "§a[GUI] Created '§f" + name + "§a'! §7(slot #" + d.index + ")");
                            openEditor(player, id, rp);
                        });
                    } catch (Exception e) { server.execute(() -> { send(player, "§c[GUI] Failed: " + e.getMessage()); openMain(player, rp); }); }
                });
                return true;
            }

            case RETEXTURE_URL -> {
                if (!isUrl(text)) { send(player, "§cNeeds a URL."); reopenEditor(player, blockId, rp); return true; }
                SlotManager.SlotData d = SlotManager.getById(blockId);
                if (d == null) { openMain(player, rp); return true; }
                send(player, "§e[GUI] Downloading texture…");
                MinecraftServer server = player.getServer();
                thread(player, () -> {
                    try {
                        byte[] raw = ImageProcessor.download(text);
                        ImageProcessor.GifResult gif = ImageProcessor.isAnimatedGif(raw) ? ImageProcessor.processGif(raw) : null;
                        byte[] bytes; String anim = null;
                        if (gif != null) { bytes = gif.stripPng(); anim = gif.mcmeta(); }
                        else { bytes = ImageProcessor.toPng(raw); bytes = ImageProcessor.padToSquare(bytes); bytes = ImageProcessor.replaceBackground(bytes); bytes = ImageProcessor.resizeTo(bytes, ImageProcessor.DEFAULT_SIZE); }
                        final byte[] fb = bytes; final String fa = anim;
                        server.execute(() -> {
                            SlotManager.pushUndo(blockId, "retexture");
                            SlotManager.SlotData dd = SlotManager.getById(blockId);
                            if (dd == null) { openMain(player, rp); return; }
                            SlotManager.updateTexture(blockId, fb);
                            if (fa != null) SlotManager.setAnimMeta(blockId, fa);
                            SlotManager.saveAll();
                            CustomBlocksMod.broadcastUpdate(server, new SlotUpdatePayload("retexture", dd.index, blockId, null, fb, dd.lightLevel, dd.hardness, dd.soundType));
                            send(player, "§a[GUI] Texture updated for §f'" + blockId + "'§a.");
                            reopenEditor(player, blockId, rp);
                        });
                    } catch (Exception e) { server.execute(() -> { send(player, "§c[GUI] Failed: " + e.getMessage()); reopenEditor(player, blockId, rp); }); }
                });
                return true;
            }

            case SETFACE_URL -> {
                if (!isUrl(text)) { send(player, "§cNeeds a URL."); openFaceEditor(player, blockId, rp); return true; }
                String face = pending.face();
                SlotManager.SlotData d = SlotManager.getById(blockId);
                if (d == null) { openMain(player, rp); return true; }
                send(player, "§e[GUI] Downloading " + face + " face…");
                MinecraftServer server = player.getServer();
                thread(player, () -> {
                    try {
                        byte[] fb = ImageProcessor.toPng(ImageProcessor.download(text));
                        fb = ImageProcessor.padToSquare(fb); fb = ImageProcessor.replaceBackground(fb); fb = ImageProcessor.resizeTo(fb, ImageProcessor.DEFAULT_SIZE);
                        final byte[] finalFb = fb;
                        server.execute(() -> {
                            SlotManager.pushUndo(blockId, "setface " + face);
                            SlotManager.SlotData dd = SlotManager.getById(blockId);
                            if (dd == null) { openMain(player, rp); return; }
                            SlotManager.setFaceTexture(blockId, face, finalFb);
                            SlotManager.saveAll();
                            CustomBlocksMod.broadcastUpdate(server, new SlotUpdatePayload("setface", dd.index, blockId, null, finalFb, dd.lightLevel, dd.hardness, dd.soundType, face));
                            send(player, "§a[GUI] §f" + face.toUpperCase() + " §aface set on '§f" + blockId + "§a'.");
                            openFaceEditor(player, blockId, rp);
                        });
                    } catch (Exception e) { server.execute(() -> { send(player, "§c[GUI] Failed: " + e.getMessage()); openFaceEditor(player, blockId, rp); }); }
                });
                return true;
            }

            case SETFACE_VARIANT_URL -> {
                // Like Rectangle tool: creates a new variant block instead of modifying original
                if (!isUrl(text)) { send(player, "§cNeeds a URL."); openFaceEditor(player, blockId, rp); return true; }
                String face = pending.face();
                SlotManager.SlotData original = SlotManager.getById(blockId);
                if (original == null) { openMain(player, rp); return true; }
                send(player, "§e[GUI] Creating variant with " + face + " face…");
                MinecraftServer server = player.getServer();
                thread(player, () -> {
                    try {
                        byte[] fb = ImageProcessor.toPng(ImageProcessor.download(text));
                        fb = ImageProcessor.padToSquare(fb); fb = ImageProcessor.replaceBackground(fb); fb = ImageProcessor.resizeTo(fb, ImageProcessor.DEFAULT_SIZE);
                        final byte[] finalFb = fb;
                        server.execute(() -> {
                            if (SlotManager.freeSlots() == 0) { send(player, "§cNo free slots!"); openFaceEditor(player, blockId, rp); return; }
                            String varId = generateVariantId(blockId, face);
                            String varName = original.displayName + " (" + cap(face) + ")";
                            byte[] texCopy = original.texture != null ? original.texture.clone() : null;
                            SlotManager.SlotData newBlock = SlotManager.assign(varId, varName, texCopy);
                            if (newBlock == null) { send(player, "§cNo free slots!"); openFaceEditor(player, blockId, rp); return; }
                            SlotManager.setLightLevel(varId, original.lightLevel);
                            SlotManager.setHardness(varId, original.hardness);
                            SlotManager.setSoundType(varId, original.soundType);
                            if (original.animMeta != null) SlotManager.setAnimMeta(varId, original.animMeta);
                            for (var e : original.faceTextures.entrySet())
                                SlotManager.setFaceTexture(varId, e.getKey(), e.getValue().clone());
                            SlotManager.setFaceTexture(varId, face, finalFb);
                            SlotManager.pushUndoCreate(varId);
                            SlotManager.saveAll();
                            SlotManager.SlotData fresh = SlotManager.getById(varId);
                            if (fresh != null) {
                                CustomBlocksMod.broadcastUpdate(server, new SlotUpdatePayload("add", fresh.index, varId, varName, texCopy, fresh.lightLevel, fresh.hardness, fresh.soundType));
                                for (var fe : fresh.faceTextures.entrySet())
                                    CustomBlocksMod.broadcastUpdate(server, new SlotUpdatePayload("setface", fresh.index, varId, null, fe.getValue(), fresh.lightLevel, fresh.hardness, fresh.soundType, fe.getKey()));
                            }
                            player.getInventory().insertStack(new ItemStack(CustomBlocksMod.SLOT_ITEMS[newBlock.index], 1));
                            send(player, "§a[GUI] Variant '§f" + varId + "§a' created & given! Original untouched.");
                            openFaceEditor(player, varId, rp);
                        });
                    } catch (Exception e) { server.execute(() -> { send(player, "§c[GUI] Failed: " + e.getMessage()); openFaceEditor(player, blockId, rp); }); }
                });
                return true;
            }

            case RENAME_TEXT -> {
                String name = text.replace("_", " ");
                SlotManager.SlotData d = SlotManager.getById(blockId);
                if (d == null) { openMain(player, rp); return true; }
                SlotManager.pushUndo(blockId, "rename");
                SlotManager.rename(blockId, name);
                SlotManager.saveAll();
                CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload("rename", d.index, blockId, name, null, 0, 0, "stone"));
                send(player, "§a[GUI] Renamed to '§f" + name + "§a'.");
                reopenEditor(player, blockId, rp);
                return true;
            }

            case SETTABICON_URL -> {
                if (!isUrl(text)) { send(player, "§cNeeds a URL."); openMain(player, rp); return true; }
                send(player, "§e[GUI] Downloading tab icon…");
                MinecraftServer server = player.getServer();
                thread(player, () -> {
                    try {
                        byte[] bytes = ImageProcessor.downloadAndProcess(text);
                        server.execute(() -> {
                            SlotManager.setTabIconTexture(bytes);
                            SlotManager.saveAll();
                            CustomBlocksMod.broadcastUpdate(server, new SlotUpdatePayload("tabicon", -1, null, null, bytes, 0, 0, "stone"));
                            send(player, "§a[GUI] Tab icon updated! §7(Resource pack reloading…)");
                            openMain(player, rp);
                        });
                    } catch (Exception e) { server.execute(() -> { send(player, "§c[GUI] Failed: " + e.getMessage()); openMain(player, rp); }); }
                });
                return true;
            }

            case ADDSHAPE_COORDS -> {
                try {
                    SlotManager.ShapeBox box = SlotManager.ShapeBox.parse(text);
                    if (!box.valid()) { send(player, "§cInvalid coords (0–16, x2>x1 etc)."); openShapeEditor(player, blockId, rp); return true; }
                    SlotManager.pushUndo(blockId, "addshape");
                    if (!SlotManager.addBox(blockId, box)) { send(player, "§cMax 16 boxes reached."); openShapeEditor(player, blockId, rp); return true; }
                    SlotManager.saveAll();
                    SlotManager.SlotData d = SlotManager.getById(blockId);
                    broadcastShape(player.getServer(), d);
                    send(player, "§a[CustomBlocks] Box added! Total: §f" + d.shapeBoxes.size());
                } catch (Exception e) { send(player, "§cBad coords. Use: x1,y1,z1,x2,y2,z2 (0–16)"); }
                openShapeEditor(player, blockId, rp);
                return true;
            }

            case REID_TEXT -> {
                String newId = text.toLowerCase().replaceAll("[^a-z0-9_\\-]", "_");
                if (newId.isEmpty()) { send(player, "§cInvalid ID."); openEditor(player, blockId, rp); return true; }
                if (SlotManager.hasId(newId)) { send(player, "§c'" + newId + "' already taken."); openEditor(player, blockId, rp); return true; }
                SlotManager.pushUndo(blockId, "reid");
                SlotManager.SlotData d = SlotManager.getById(blockId);
                SlotManager.reId(blockId, newId);
                SlotManager.saveAll();
                SlotManager.SlotData updated = SlotManager.getById(newId);
                CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload("remove", d.index, blockId, null, null, 0, 0, "stone"));
                CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload("add", updated.index, newId, updated.displayName, updated.texture, updated.lightLevel, updated.hardness, updated.soundType));
                send(player, "§a[CustomBlocks] Re-ID'd '§f" + blockId + "§a' → '§f" + newId + "§a'.");
                openEditor(player, newId, rp);
                return true;
            }
        }
        return false;
    }

    private static void broadcastShape(net.minecraft.server.MinecraftServer server, SlotManager.SlotData d) {
        List<SlotManager.ShapeBox> boxes = d.shapeBoxes;
        String data = (boxes == null || boxes.isEmpty()) ? "full" :
            boxes.stream().map(SlotManager.ShapeBox::toCoordString).reduce((a, b) -> a + ";" + b).orElse("full");
        CustomBlocksMod.broadcastUpdate(server, new SlotUpdatePayload(
            "setshape", d.index, d.customId, null, null, 0, 0, "stone", null, data));
    }

    public static boolean hasPending(ServerPlayerEntity player) { return PENDING.containsKey(player.getUuid()); }
    public static void clearState(ServerPlayerEntity player) {
        STATES.remove(player.getUuid());
        PENDING.remove(player.getUuid());
    }

    // ── Click handlers ────────────────────────────────────────────────────────

    private static void handleMainClick(ServerPlayerEntity player, GuiState state, int slot) {
        int page = state.page();

        // Row 0: controls
        if (slot == 0) {   // Create new block
            PENDING.put(player.getUuid(), new PendingInput(InputAction.CREATE_ID, null, null, null, null, page));
            player.closeHandledScreen();
            send(player, "§6[GUI] §eEnter a block §fID§e (letters/numbers/underscores, e.g. §fmy_block§e) or §ccancel§e:");
            return;
        }
        if (slot == 4) {   // Set tab icon
            PENDING.put(player.getUuid(), new PendingInput(InputAction.SETTABICON_URL, null, null, null, null, page));
            player.closeHandledScreen();
            send(player, "§6[GUI] §ePaste an image URL for the §fcreative tab icon§e (or §ccancel§e):");
            return;
        }
        if (slot == 8) {   // Undo
            if (SlotManager.undoStackSize() == 0) { send(player, "§7[CustomBlocks] Nothing to undo."); openMain(player, page); return; }
            SlotManager.UndoEntry entry = SlotManager.popUndo();
            if (entry == null) { openMain(player, page); return; }
            MinecraftServer guiServer = player.getServer();

            if (entry.previousState() == null) {
                // undo create
                SlotManager.SlotData cd = SlotManager.getById(entry.customId());
                if (cd != null) {
                    int cidx = cd.index;
                    SlotManager.remove(entry.customId()); SlotManager.saveAll();
                    CustomBlocksMod.broadcastUpdate(guiServer, new SlotUpdatePayload("remove", cidx, entry.customId(), null, null, 0, 0, "stone"));
                    send(player, "§a[GUI] Undid create of §f" + entry.customId());
                }
                openMain(player, page); return;
            }

            if (SlotManager.restoreSnapshot(entry.previousState(), entry.wasDeleted())) {
                SlotManager.saveAll();
                SlotManager.SlotData d = SlotManager.getById(entry.previousState().customId);
                if (d != null) {
                    if (entry.wasDeleted()) {
                        CustomBlocksMod.broadcastUpdate(guiServer, new SlotUpdatePayload("add", d.index, d.customId, d.displayName, d.texture, d.lightLevel, d.hardness, d.soundType));
                    } else {
                        if (d.texture != null) CustomBlocksMod.broadcastUpdate(guiServer, new SlotUpdatePayload("retexture", d.index, d.customId, null, d.texture, d.lightLevel, d.hardness, d.soundType));
                        CustomBlocksMod.broadcastUpdate(guiServer, new SlotUpdatePayload("clearfaces", d.index, d.customId, null, null, d.lightLevel, d.hardness, d.soundType));
                    }
                    for (var fe : d.faceTextures.entrySet())
                        CustomBlocksMod.broadcastUpdate(guiServer, new SlotUpdatePayload("setface", d.index, d.customId, null, fe.getValue(), d.lightLevel, d.hardness, d.soundType, fe.getKey()));
                    CustomBlocksMod.broadcastUpdate(guiServer, new SlotUpdatePayload("setprop", d.index, d.customId, null, null, d.lightLevel, d.hardness, d.soundType));
                    CustomBlocksMod.broadcastUpdate(guiServer, new SlotUpdatePayload("rename", d.index, d.customId, d.displayName, null, 0, 0, "stone"));
                }
                send(player, "§a[GUI] Undid §f\"" + entry.description() + "\"§a on §f" + entry.customId() + " §7(" + SlotManager.undoStackSize() + " left)");
            }
            openMain(player, page); return;
        }
        if (slot == 45) { openMain(player, page - 1); return; }
        if (slot == 53) { openMain(player, page + 1); return; }

        // Blocks in list (slots 9–44)
        if (slot >= 9 && slot <= 44) {
            List<SlotManager.SlotData> blocks = sortedBlocks();
            int idx = page * BLOCKS_PER_PAGE + (slot - 9);
            if (idx < blocks.size()) openEditor(player, blocks.get(idx).customId, page);
        }
    }

    private static void handleEditorClick(ServerPlayerEntity player, GuiState state, int slot, int button) {
        String id = state.editingId();
        int returnPage = state.page();
        SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }

        switch (slot) {
            // ── Row 0 ──────────────────────────────────────────────────────
            case 0 -> openMain(player, returnPage);
            case 3 -> { /* display only */ }
            case 4 -> { // Give
                player.getInventory().insertStack(new ItemStack(CustomBlocksMod.SLOT_ITEMS[d.index], 1));
                send(player, "§a[GUI] Given 1x §f" + d.displayName + "§a.");
                reopenEditor(player, id, returnPage);
            }
            case 5 -> { // Rename
                PENDING.put(player.getUuid(), new PendingInput(InputAction.RENAME_TEXT, id, null, null, null, returnPage));
                player.closeHandledScreen();
                send(player, "§6[GUI] §eType a new name for '§f" + id + "§e' (or §ccancel§e):");
            }
            case 6 -> { // Re-ID
                PENDING.put(player.getUuid(), new PendingInput(InputAction.REID_TEXT, id, null, null, null, returnPage));
                player.closeHandledScreen();
                send(player, "§6[GUI] §eType a new ID for '§f" + id + "§e' (lowercase, a-z 0-9 _ -) (or §ccancel§e):");
            }
            case 8 -> { // Delete
                if (state.confirmDelete()) {
                    SlotManager.pushUndoDelete(id);
                    SlotManager.remove(id); SlotManager.saveAll();
                    CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload("remove", d.index, id, null, null, 0, 0, "stone"));
                    send(player, "§a[GUI] '§f" + id + "§a' deleted.");
                    openMain(player, returnPage);
                } else {
                    STATES.put(player.getUuid(), state.withConfirmDelete());
                    player.getServer().execute(() -> {
                        SlotManager.SlotData dd = SlotManager.getById(id);
                        if (dd == null) return;
                        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                            (s, pi, p) -> new CbScreenHandler(s, pi, buildEditor(dd, true)),
                            Text.literal("§c§l⚠ Confirm? — §r§f" + dd.displayName)));
                    });
                }
            }

            // ── Row 1: Texture / Faces ────────────────────────────────────
            case 9 -> { // Retexture all
                PENDING.put(player.getUuid(), new PendingInput(InputAction.RETEXTURE_URL, id, null, null, null, returnPage));
                player.closeHandledScreen();
                send(player, "§6[GUI] §ePaste an image URL for ALL faces of '§f" + id + "§e' (or §ccancel§e):");
            }
            case 10, 11, 12, 13, 14, 15 -> openFaceEditor(player, id, returnPage); // face btns
            case 16 -> openFaceEditor(player, id, returnPage); // face editor shortcut
            case 17 -> { // Clear all faces
                SlotManager.pushUndo(id, "clearallfaces"); SlotManager.clearAllFaces(id); SlotManager.saveAll();
                broadcastClearAllFaces(player, d); reopenEditor(player, id, returnPage);
            }

            // ── Row 2: Clear individual faces ────────────────────────────
            case 19 -> clearFace(player, d, "top");
            case 20 -> clearFace(player, d, "north");
            case 21 -> clearFace(player, d, "south");
            case 22 -> clearFace(player, d, "east");
            case 23 -> clearFace(player, d, "west");
            case 24 -> clearFace(player, d, "bottom");

            // ── Row 3: Light / Hard / Shape / Collision ───────────────────
            case 27 -> { SlotManager.pushUndo(id,"setglow"); SlotManager.setLightLevel(id,Math.max(0,d.lightLevel-1)); syncProp(player,d); reopenEditor(player,id,returnPage); }
            case 29 -> { SlotManager.pushUndo(id,"setglow"); SlotManager.setLightLevel(id,Math.min(15,d.lightLevel+1)); syncProp(player,d); reopenEditor(player,id,returnPage); }
            case 30 -> { SlotManager.pushUndo(id,"sethardness"); SlotManager.setHardness(id,prevHardness(d.hardness)); syncProp(player,d); reopenEditor(player,id,returnPage); }
            case 32 -> { SlotManager.pushUndo(id,"sethardness"); SlotManager.setHardness(id,nextHardness(d.hardness)); syncProp(player,d); reopenEditor(player,id,returnPage); }
            case 34 -> openShapeEditor(player, id, returnPage);
            case 35 -> { // Toggle collision
                SlotManager.pushUndo(id,"setcollision");
                SlotManager.setCollision(id, d.noCollision); // noCollision true → set to false (collision on), etc.
                SlotManager.saveAll();
                SlotManager.SlotData updated = SlotManager.getById(id);
                CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload(
                    "setcollision", updated.index, id, null, null, 0, 0, "stone", null,
                    updated.noCollision ? "false" : "true"));
                reopenEditor(player, id, returnPage);
            }

            // ── Row 4: Sounds ─────────────────────────────────────────────
            case 36 -> setSoundAndRefresh(player, d, "stone",        returnPage);
            case 37 -> setSoundAndRefresh(player, d, "wood",         returnPage);
            case 38 -> setSoundAndRefresh(player, d, "grass",        returnPage);
            case 39 -> setSoundAndRefresh(player, d, "metal",        returnPage);
            case 40 -> setSoundAndRefresh(player, d, "glass",        returnPage);
            case 41 -> setSoundAndRefresh(player, d, "sand",         returnPage);
            case 42 -> setSoundAndRefresh(player, d, "gravel",       returnPage);
            case 43 -> setSoundAndRefresh(player, d, "wool",         returnPage);
            case 44 -> setSoundAndRefresh(player, d, "snow",         returnPage);

            // ── Row 5: More sounds + Dupe ─────────────────────────────────
            case 45 -> setSoundAndRefresh(player, d, "dirt",         returnPage);
            case 46 -> setSoundAndRefresh(player, d, "coral",        returnPage);
            case 47 -> setSoundAndRefresh(player, d, "bamboo",       returnPage);
            case 48 -> setSoundAndRefresh(player, d, "nether_brick", returnPage);
            case 49 -> setSoundAndRefresh(player, d, "ice",          returnPage);
            case 50 -> setSoundAndRefresh(player, d, "honey",        returnPage);
            case 51 -> setSoundAndRefresh(player, d, "bone",         returnPage);
            case 52 -> setSoundAndRefresh(player, d, "slime",        returnPage);
            case 53 -> { // Dupe
                PENDING.put(player.getUuid(), new PendingInput(InputAction.CREATE_ID, id, null, null, null, returnPage));
                player.closeHandledScreen();
                send(player, "§6[GUI] §eType a new ID to duplicate '§f" + id + "§e' into (or §ccancel§e):");
            }
        }
    }

    private static void handleShapeEditorClick(ServerPlayerEntity player, GuiState state, int slot, int button) {
        String id = state.editingId();
        int returnPage = state.page();
        int boxPage = state.shapeBoxPage();
        SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        List<SlotManager.ShapeBox> boxes = d.shapeBoxes != null ? new ArrayList<>(d.shapeBoxes) : new ArrayList<>();
        String[] presets = {"full","slab","thin","carpet","pillar","small","micro","pane","trapdoor","fence","stairs","cross"};

        // Row 0: back + collision toggle
        if (slot == 0) { openEditor(player, id, returnPage); return; }
        if (slot == 8) { // toggle collision
            SlotManager.pushUndo(id, "setcollision");
            SlotManager.setCollision(id, d.noCollision);
            SlotManager.saveAll();
            SlotManager.SlotData upd = SlotManager.getById(id);
            CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload(
                "setcollision", upd.index, id, null, null, 0, 0, "stone", null,
                upd.noCollision ? "false" : "true"));
            reopenShapeEditor(player, id, returnPage, boxPage);
            return;
        }

        // Row 1: presets 0-8 (slots 9-17)
        if (slot >= 9 && slot <= 17) {
            int pi = slot - 9;
            if (pi < presets.length) applyPreset(player, d, id, presets[pi], returnPage, boxPage);
            return;
        }
        // Row 2: presets 9-11 (slots 18-20), add box (21), clear all (22)
        if (slot >= 18 && slot <= 20) {
            int pi = slot - 18 + 9;
            if (pi < presets.length) applyPreset(player, d, id, presets[pi], returnPage, boxPage);
            return;
        }
        if (slot == 21) {
            PENDING.put(player.getUuid(), new PendingInput(InputAction.ADDSHAPE_COORDS, id, null, null, null, returnPage));
            player.closeHandledScreen();
            send(player, "§6[Shape] §eType coords for new box (or §ccancel§e):");
            send(player, "§7Format: §fx1,y1,z1,x2,y2,z2  §8(pixel units 0–16)");
            return;
        }
        if (slot == 22) {
            SlotManager.pushUndo(id, "clearshape");
            SlotManager.clearShape(id);
            SlotManager.saveAll();
            broadcastShape(player.getServer(), SlotManager.getById(id));
            send(player, "§a[Shape] Cleared — full cube.");
            reopenShapeEditor(player, id, returnPage, 0);
            return;
        }

        // Rows 3-4: box list (slots 27-35) → click to remove
        if (slot >= 27 && slot <= 35) {
            int boxIdx = boxPage * 9 + (slot - 27);
            if (boxIdx < boxes.size()) {
                SlotManager.pushUndo(id, "removeshape");
                SlotManager.removeBox(id, boxIdx);
                SlotManager.saveAll();
                broadcastShape(player.getServer(), SlotManager.getById(id));
                send(player, "§a[Shape] Removed box #" + boxIdx + ".");
                int newPage = Math.min(boxPage, Math.max(0, (boxes.size() - 2) / 9));
                reopenShapeEditor(player, id, returnPage, newPage);
            }
            return;
        }

        // Row 5: pagination
        if (slot == 45 && boxPage > 0) { reopenShapeEditor(player, id, returnPage, boxPage - 1); return; }
        if (slot == 53) {
            int maxPage = Math.max(0, (boxes.size() - 1) / 9);
            if (boxPage < maxPage) { reopenShapeEditor(player, id, returnPage, boxPage + 1); }
        }
    }

    private static void applyPreset(ServerPlayerEntity player, SlotManager.SlotData d, String id,
                                     String preset, int returnPage, int boxPage) {
        List<SlotManager.ShapeBox> boxes = SlotManager.SHAPE_PRESETS.get(preset);
        SlotManager.pushUndo(id, "setshape");
        SlotManager.setShape(id, boxes != null ? new ArrayList<>(boxes) : null);
        SlotManager.saveAll();
        broadcastShape(player.getServer(), SlotManager.getById(id));
        send(player, "§a[Shape] Applied preset '§f" + preset + "§a'.");
        reopenShapeEditor(player, id, returnPage, boxPage);
    }

    private static void reopenShapeEditor(ServerPlayerEntity player, String id, int returnPage, int boxPage) {
        SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        STATES.put(player.getUuid(), GuiState.shapeEditor(id, returnPage).withShapeBoxPage(boxPage));
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildShapeEditor(d, boxPage)),
            Text.literal("§5§l⬡ §r§fShape Editor — §e" + d.displayName)));
    }

    private static void handleFaceEditorClick(ServerPlayerEntity player, GuiState state, int slot, int button) {
        String id = state.editingId();
        int returnPage = state.page();
        SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }

        // face slots (rows 1-2): each face has two buttons — EDIT IN PLACE and CREATE VARIANT
        // Layout (6 faces × 2 buttons):
        //  row 0: back | | block display | | | face editor title | | | give
        //  row 1: TOP_EDIT | TOP_VARIANT | BTM_EDIT | BTM_VARIANT | N_EDIT | N_VARIANT | S_EDIT | S_VARIANT | E_EDIT | E_VARIANT | W_EDIT(9) | W_VARIANT(no,next row)
        // Simpler: 2 rows for 6 faces, each face = left(edit in-place) + right(create variant)
        //  slot  9 = top edit      | 10 = top variant
        //  slot 11 = bottom edit   | 12 = bottom variant
        //  slot 13 = north edit    | 14 = north variant
        //  slot 15 = south edit    | 16 = south variant
        //  slot 17 = east edit     | 18 = east variant
        //  slot 19 = west edit     | 20 = west variant
        //  slot 27+ = clear faces row
        //  slot 45-47 = back | undo | clear all faces

        switch (slot) {
            case 0  -> openEditor(player, id, returnPage);   // Back to editor

            // ── Edit in-place ──────────────────────────────────────────────
            case 9  -> promptFace(player, id, "top",    returnPage, false);
            case 11 -> promptFace(player, id, "bottom", returnPage, false);
            case 13 -> promptFace(player, id, "north",  returnPage, false);
            case 15 -> promptFace(player, id, "south",  returnPage, false);
            case 17 -> promptFace(player, id, "east",   returnPage, false);
            case 19 -> promptFace(player, id, "west",   returnPage, false);

            // ── Create variant ─────────────────────────────────────────────
            case 10 -> promptFace(player, id, "top",    returnPage, true);
            case 12 -> promptFace(player, id, "bottom", returnPage, true);
            case 14 -> promptFace(player, id, "north",  returnPage, true);
            case 16 -> promptFace(player, id, "south",  returnPage, true);
            case 18 -> promptFace(player, id, "east",   returnPage, true);
            case 20 -> promptFace(player, id, "west",   returnPage, true);

            // ── Clear individual faces ──────────────────────────────────────
            case 27 -> clearFace(player, d, "top");
            case 28 -> clearFace(player, d, "bottom");
            case 29 -> clearFace(player, d, "north");
            case 30 -> clearFace(player, d, "south");
            case 31 -> clearFace(player, d, "east");
            case 32 -> clearFace(player, d, "west");

            // ── Bottom row ─────────────────────────────────────────────────
            case 45 -> openEditor(player, id, returnPage);       // Back
            case 46 -> {
                // Undo
                if (SlotManager.undoStackSize() > 0) {
                    SlotManager.UndoEntry entry = SlotManager.popUndo();
                    if (entry != null && entry.previousState() != null) {
                        SlotManager.restoreSnapshot(entry.previousState(), entry.wasDeleted());
                        SlotManager.saveAll();
                        SlotManager.SlotData dd = SlotManager.getById(id);
                        if (dd != null) CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload("clearfaces", dd.index, id, null, null, dd.lightLevel, dd.hardness, dd.soundType));
                        send(player, "§a[GUI] Undid '" + entry.description() + "'.");
                    }
                }
                openFaceEditor(player, id, returnPage);
            }
            case 47 -> {
                // Clear ALL faces
                SlotManager.pushUndo(id, "clearallfaces");
                SlotManager.clearAllFaces(id); SlotManager.saveAll();
                broadcastClearAllFaces(player, d);
                send(player, "§a[GUI] All face overrides cleared.");
                openFaceEditor(player, id, returnPage);
            }
            case 53 -> {
                // Give 1x to self
                player.getInventory().insertStack(new ItemStack(CustomBlocksMod.SLOT_ITEMS[d.index], 1));
                send(player, "§a[GUI] Given 1x §f" + d.displayName + "§a.");
            }
        }
        // If we reopened face editor, rebuild it
    }

    // ── Inventory builders ────────────────────────────────────────────────────

    private static SimpleInventory buildMain(int page) {
        SimpleInventory inv = new SimpleInventory(54);

        // Row 0: toolbar
        inv.setStack(0, uiGlint(Items.LIME_CONCRETE,  "§a§l+ New Block",  "§7Create a new custom block"));
        for (int i = 1; i <= 3; i++) inv.setStack(i, glass());
        inv.setStack(4, ui(Items.PAINTING, "§e✦ Set Tab Icon", "§7Click → paste image URL in chat"));
        for (int i = 5; i <= 7; i++) inv.setStack(i, glass());
        inv.setStack(8, SlotManager.undoStackSize() > 0
            ? uiGlint(Items.ARROW, "§6↩ Undo", "§7" + SlotManager.undoStackSize() + " action(s) available")
            : ui(Items.GRAY_STAINED_GLASS_PANE, "§8Undo", "§7Nothing to undo"));

        // Rows 1–4: block list (up to 36 per page)
        List<SlotManager.SlotData> blocks = sortedBlocks();
        int start = page * BLOCKS_PER_PAGE;
        for (int i = 0; i < BLOCKS_PER_PAGE; i++) {
            int invSlot = 9 + i;
            int dataIdx = start + i;
            if (dataIdx < blocks.size()) {
                SlotManager.SlotData d = blocks.get(dataIdx);
                ItemStack stack = new ItemStack(CustomBlocksMod.SLOT_ITEMS[d.index]);
                List<Text> lore = new ArrayList<>();
                lore.add(lore("§7ID: §f" + d.customId));
                lore.add(lore("§8#" + d.index + (d.lightLevel > 0 ? "  §6✦" + d.lightLevel : "") + (d.hardness < 0 ? "  §c∞" : "") + (d.isAnimated() ? "  §b⟳" : "") + (d.hasFaces() ? "  §d⬡faces" : "")));
                lore.add(lore("§7Sound: §f" + d.soundType));
                stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§f§l" + d.displayName).styled(s -> s.withItalic(false)));
                stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
                inv.setStack(invSlot, stack);
            } else {
                inv.setStack(invSlot, glass());
            }
        }

        // Row 5: navigation
        int total = blocks.size();
        int maxPage = Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        inv.setStack(45, page > 0
            ? ui(Items.ARROW, "§7◀ Page " + page + " / " + (maxPage + 1), "§8Previous page")
            : ui(Items.GRAY_STAINED_GLASS_PANE, "§8◀ No Previous", ""));
        for (int i = 46; i <= 48; i++) inv.setStack(i, glass());
        inv.setStack(49, ui(Items.BOOK, "§ePage §f" + (page + 1) + " §7/ §f" + (maxPage + 1),
            "§7Showing " + Math.min(BLOCKS_PER_PAGE, total - page * BLOCKS_PER_PAGE) + " / " + total + " blocks",
            "§7Total slots used: §f" + SlotManager.usedSlots() + " / " + SlotManager.MAX_SLOTS));
        for (int i = 50; i <= 52; i++) inv.setStack(i, glass());
        inv.setStack(53, page < maxPage
            ? ui(Items.ARROW, "§7Page " + (page + 2) + " ▶", "§8Next page")
            : ui(Items.GRAY_STAINED_GLASS_PANE, "§8No Next Page ▶", ""));
        return inv;
    }

    private static SimpleInventory buildEditor(SlotManager.SlotData d, boolean confirmDelete) {
        SimpleInventory inv = new SimpleInventory(54);

        // ── Row 0: Header / Info / Delete ──────────────────────────────────
        inv.setStack(0, uiGlint(Items.ARROW, "§c◀ Back to List", "§7Return to block list"));
        inv.setStack(1, glass()); inv.setStack(2, glass());
        ItemStack disp = new ItemStack(CustomBlocksMod.SLOT_ITEMS[d.index]);
        disp.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§e§l" + d.displayName).styled(s -> s.withItalic(false)));
        disp.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            lore("§7ID: §f" + d.customId),
            lore("§7Shape: §b" + d.shapeLabel()),
            lore("§7Collision: " + (d.noCollision ? "§cOFF" : "§aON")),
            lore("§7Light: §e" + d.lightLevel + "  §7Hard: §e" + hardnessLabel(d.hardness)),
            lore("§8Slot #" + d.index)
        )));
        inv.setStack(3, disp);
        inv.setStack(4, uiGlint(Items.CHEST, "§a▶ Give 1x", "§7Gives §f1x " + d.displayName + " §7to you"));
        inv.setStack(5, uiGlint(Items.NAME_TAG, "§e✎ Rename", "§7Current: §f" + d.displayName, "§8Click → type new name in chat"));
        inv.setStack(6, uiGlint(Items.COMMAND_BLOCK, "§b⇄ Re-ID", "§7Current: §f" + d.customId, "§8Click → type new ID in chat"));
        inv.setStack(7, glass());
        inv.setStack(8, confirmDelete
            ? uiGlint(Items.BARRIER, "§4§l⚠ CONFIRM DELETE", "§cThis is permanent!", "§4Click again to delete.")
            : ui(Items.TNT, "§c§l⚠ Delete Block", "§7First click arms it.", "§8Second click confirms."));

        // ── Row 1: Texture controls ─────────────────────────────────────────
        inv.setStack(9,  uiGlint(Items.PAINTING, "§b⬛ Retexture (All Faces)", "§7Replace main texture on all faces", "§8Click → paste image URL in chat"));
        inv.setStack(10, faceBtn(d, Items.WHITE_CONCRETE,       "top",    "§f▲ TOP"));
        inv.setStack(11, faceBtn(d, Items.CYAN_CONCRETE,        "north",  "§b▶ NORTH"));
        inv.setStack(12, faceBtn(d, Items.BLUE_CONCRETE,        "south",  "§9▶ SOUTH"));
        inv.setStack(13, faceBtn(d, Items.PURPLE_CONCRETE,      "east",   "§5▶ EAST"));
        inv.setStack(14, faceBtn(d, Items.MAGENTA_CONCRETE,     "west",   "§d▶ WEST"));
        inv.setStack(15, faceBtn(d, Items.LIGHT_GRAY_CONCRETE,  "bottom", "§7▼ BOTTOM"));
        inv.setStack(16, uiGlint(Items.ITEM_FRAME, "§d⬡ Face Editor", "§7Full per-face editor with variants"));
        inv.setStack(17, ui(Items.ORANGE_CONCRETE, "§6⊘ Clear ALL Faces", "§7Reset all faces to default texture"));

        // ── Row 2: Per-face clear ───────────────────────────────────────────
        inv.setStack(18, glass());
        inv.setStack(19, clearFaceBtn(d, Items.WHITE_STAINED_GLASS_PANE,      "top",    "§f✕ TOP"));
        inv.setStack(20, clearFaceBtn(d, Items.CYAN_STAINED_GLASS_PANE,       "north",  "§b✕ NORTH"));
        inv.setStack(21, clearFaceBtn(d, Items.BLUE_STAINED_GLASS_PANE,       "south",  "§9✕ SOUTH"));
        inv.setStack(22, clearFaceBtn(d, Items.PURPLE_STAINED_GLASS_PANE,     "east",   "§5✕ EAST"));
        inv.setStack(23, clearFaceBtn(d, Items.MAGENTA_STAINED_GLASS_PANE,    "west",   "§d✕ WEST"));
        inv.setStack(24, clearFaceBtn(d, Items.LIGHT_GRAY_STAINED_GLASS_PANE, "bottom", "§7✕ BOTTOM"));
        inv.setStack(25, glass()); inv.setStack(26, glass());

        // ── Row 3: Light / Glow ─────────────────────────────────────────────
        inv.setStack(27, ui(Items.RED_DYE,          "§c▼ Light -1", "§7Now: §e" + d.lightLevel));
        inv.setStack(28, uiGlint(Items.GLOWSTONE_DUST, "§e✦ Light Level: §f" + d.lightLevel,
            "§70=off · 7=torch · 14=sea lantern · 15=max"));
        inv.setStack(29, ui(Items.YELLOW_DYE,       "§a▲ Light +1", "§7Now: §e" + d.lightLevel));
        inv.setStack(30, ui(Items.RED_DYE,          "§c▼ Hardness -", "§7Now: §f" + hardnessLabel(d.hardness)));
        inv.setStack(31, ui(Items.IRON_PICKAXE,     "§b⚙ Hardness: §f" + hardnessLabel(d.hardness),
            "§7-1=Unbreakable  0=Instant  1.5=Default"));
        inv.setStack(32, ui(Items.LIME_DYE,         "§a▲ Hardness +", "§7Now: §f" + hardnessLabel(d.hardness)));
        inv.setStack(33, glass());
        inv.setStack(34, uiGlint(Items.ENDER_PEARL, "§5⬡ Shape Editor", "§7Shape: §b" + d.shapeLabel(),
            "§8Opens full shape editor"));
        inv.setStack(35, d.noCollision
            ? uiGlint(Items.BARRIER, "§c⊘ No Collision (OFF)", "§7Click to ENABLE collision")
            : ui(Items.SLIME_BLOCK,  "§a✔ Collision ON", "§7Click to DISABLE collision"));

        // ── Row 4: Sound ────────────────────────────────────────────────────
        inv.setStack(36, soundItem(d, "stone",  Items.STONE,       "§fStone"));
        inv.setStack(37, soundItem(d, "wood",   Items.OAK_LOG,     "§fWood"));
        inv.setStack(38, soundItem(d, "grass",  Items.GRASS_BLOCK, "§fGrass"));
        inv.setStack(39, soundItem(d, "metal",  Items.IRON_BLOCK,  "§fMetal"));
        inv.setStack(40, soundItem(d, "glass",  Items.GLASS,       "§fGlass"));
        inv.setStack(41, soundItem(d, "sand",   Items.SAND,        "§fSand"));
        inv.setStack(42, soundItem(d, "gravel", Items.GRAVEL,      "§fGravel"));
        inv.setStack(43, soundItem(d, "wool",   Items.WHITE_WOOL,  "§fWool"));
        inv.setStack(44, soundItem(d, "snow",   Items.SNOW_BLOCK,  "§fSnow"));

        // ── Row 5: More sounds + Dupe + Resize ─────────────────────────────
        inv.setStack(45, soundItem(d, "dirt",         Items.DIRT,          "§fDirt"));
        inv.setStack(46, soundItem(d, "coral",        Items.TUBE_CORAL_BLOCK,    "§fCoral"));
        inv.setStack(47, soundItem(d, "bamboo",       Items.BAMBOO,        "§fBamboo"));
        inv.setStack(48, soundItem(d, "nether_brick", Items.NETHER_BRICKS, "§fNether Brick"));
        inv.setStack(49, soundItem(d, "ice",          Items.ICE,           "§fIce"));
        inv.setStack(50, soundItem(d, "honey",        Items.HONEY_BLOCK,   "§fHoney"));
        inv.setStack(51, soundItem(d, "bone",         Items.BONE_BLOCK,    "§fBone"));
        inv.setStack(52, soundItem(d, "slime",        Items.SLIME_BLOCK,   "§fSlime"));
        inv.setStack(53, uiGlint(Items.COMPARATOR, "§e⧉ Dupe Block", "§7Create a copy of §f" + d.customId,
            "§8Click → type new ID in chat"));

        return inv;
    }

    private static ItemStack faceBtn(SlotManager.SlotData d, Item item, String face, String label) {
        boolean hasOverride = d.faceTextures.containsKey(face);
        return hasOverride
            ? uiGlint(item, label + " Face", "§a✔ Override active", "§8Click → open Face Editor for this face")
            : ui(item, label + " Face", "§7Using default texture", "§8Click → open Face Editor for this face");
    }

    private static ItemStack clearFaceBtn(SlotManager.SlotData d, Item item, String face, String label) {
        boolean hasOverride = d.faceTextures.containsKey(face);
        return hasOverride
            ? uiGlint(item, label, "§a✔ Override active — click to clear")
            : ui(item, label, "§8No override set");
    }

    /** Shape Editor screen (6×9 = 54 slots) */
    private static SimpleInventory buildShapeEditor(SlotManager.SlotData d, int boxPage) {
        SimpleInventory inv = new SimpleInventory(54);
        List<SlotManager.ShapeBox> boxes = d.shapeBoxes != null ? d.shapeBoxes : List.of();
        String[] presets = {"full","slab","thin","carpet","pillar","small","micro","pane","trapdoor","fence","stairs","cross"};
        Item[]   pItems  = {Items.GRASS_BLOCK,Items.SMOOTH_STONE_SLAB,Items.STONE_SLAB,Items.MOSS_CARPET,
                            Items.COBBLESTONE_WALL,Items.COMPARATOR,Items.COMPARATOR,Items.OAK_TRAPDOOR,
                            Items.OAK_TRAPDOOR,Items.OAK_FENCE,Items.OAK_STAIRS,Items.TALL_GRASS};

        // Row 0: Back, block info, collision, clear shape
        inv.setStack(0, uiGlint(Items.ARROW, "§c◀ Back to Editor", "§7Return to block editor"));
        ItemStack info = new ItemStack(CustomBlocksMod.SLOT_ITEMS[d.index]);
        info.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§e§l" + d.displayName).styled(s -> s.withItalic(false)));
        info.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            lore("§7Shape: §b" + d.shapeLabel()),
            lore("§7Boxes: §f" + boxes.size() + " §8/ 16"),
            lore("§7Collision: " + (d.noCollision ? "§cOFF" : "§aON"))
        )));
        inv.setStack(4, info);
        inv.setStack(1, glass()); inv.setStack(2, glass()); inv.setStack(3, glass());
        inv.setStack(5, glass()); inv.setStack(6, glass()); inv.setStack(7, glass());
        inv.setStack(8, d.noCollision
            ? uiGlint(Items.BARRIER,     "§c⊘ No Collision", "§7Click to ENABLE collision")
            : ui(Items.SLIME_BLOCK,       "§a✔ Collision ON", "§7Click to DISABLE collision"));

        // Row 1: Preset buttons (12 presets across slots 9-20)
        for (int i = 0; i < Math.min(presets.length, 9); i++) {
            String preset = presets[i];
            boolean active = boxes.equals(SlotManager.SHAPE_PRESETS.get(preset));
            inv.setStack(9 + i, active
                ? uiGlint(pItems[i], "§a§l" + preset.toUpperCase(), "§a✔ Currently active", "§8Click to reapply")
                : ui(pItems[i], "§b" + preset, "§7Apply preset shape"));
        }

        // Row 2: More presets + add box + clear
        for (int i = 9; i < presets.length; i++) {
            String preset = presets[i];
            boolean active = boxes.equals(SlotManager.SHAPE_PRESETS.get(preset));
            inv.setStack(9 + i, active
                ? uiGlint(pItems[i], "§a§l" + preset.toUpperCase(), "§a✔ Currently active")
                : ui(pItems[i], "§b" + preset, "§7Apply preset shape"));
        }
        inv.setStack(21, uiGlint(Items.LIME_DYE, "§a➕ Add Custom Box",
            "§7Click → type coords in chat", "§8Format: x1,y1,z1,x2,y2,z2  (0–16)"));
        inv.setStack(22, ui(Items.ORANGE_DYE, "§6⊘ Clear All Boxes", "§7Reset to full cube"));

        // Row 3: spacer
        for (int i = 27; i < 36; i++) inv.setStack(i, glass());

        // Rows 3-4: List current boxes (up to 9 per page)
        int start = boxPage * 9;
        for (int i = 0; i < 9 && (start + i) < boxes.size(); i++) {
            SlotManager.ShapeBox b = boxes.get(start + i);
            int boxIdx = start + i;
            inv.setStack(27 + i, ui(Items.STRUCTURE_VOID,
                "§e§lBox #" + boxIdx,
                "§7" + b.toDisplayString(),
                "§8Click → remove this box"));
        }

        // Row 5: pagination + save template
        inv.setStack(45, boxPage > 0
            ? uiGlint(Items.ARROW, "§7◀ Prev boxes", "§8Page " + boxPage)
            : glass());
        inv.setStack(46, glass()); inv.setStack(47, glass()); inv.setStack(48, glass());
        int totalBoxPages = Math.max(0, (boxes.size() - 1) / 9);
        inv.setStack(49, ui(Items.PAPER, "§7Boxes page §f" + (boxPage+1) + " §7of §f" + (totalBoxPages+1)));
        inv.setStack(50, glass()); inv.setStack(51, glass()); inv.setStack(52, glass());
        inv.setStack(53, boxPage < totalBoxPages
            ? uiGlint(Items.ARROW, "§7Next boxes ▶", "§8Page " + (boxPage+2))
            : glass());

        return inv;
    }

    /** Dedicated face-by-face editor screen. */
    private static SimpleInventory buildFaceEditor(SlotManager.SlotData d) {
        SimpleInventory inv = new SimpleInventory(54);

        // Row 0: header
        inv.setStack(0, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Editor", "§7Return to block editor"));
        for (int i = 1; i <= 3; i++) inv.setStack(i, glass());
        ItemStack disp = new ItemStack(CustomBlocksMod.SLOT_ITEMS[d.index]);
        disp.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§d§l⬡ §r§f" + d.displayName).styled(s -> s.withItalic(false)));
        disp.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            lore("§7ID: §f" + d.customId),
            lore("§7Left button  = §aedit in place §7(modifies this block)"),
            lore("§7Right button = §bcreate variant §7(keeps original)"))));
        inv.setStack(4, disp);
        for (int i = 5; i <= 8; i++) inv.setStack(i, glass());

        // Face rows — each face: [Edit in-place] [Create Variant] [status pane] [spacer] per row
        // Pairs: slots 9-10, 11-12, 13-14, 15-16, 17-18, 19-20
        String[][] faces = {{"top","▲ TOP"},{"bottom","▼ BOTTOM"},{"north","N NORTH"},{"south","S SOUTH"},{"east","E EAST"},{"west","W WEST"}};
        int[] editSlots    = {9, 11, 13, 15, 17, 19};
        int[] variantSlots = {10,12, 14, 16, 18, 20};
        Item[] faceItems = {Items.WHITE_CONCRETE,Items.LIGHT_GRAY_CONCRETE,Items.CYAN_CONCRETE,Items.BLUE_CONCRETE,Items.PURPLE_CONCRETE,Items.MAGENTA_CONCRETE};

        for (int fi = 0; fi < 6; fi++) {
            String faceKey = faces[fi][0];
            String faceLabel = faces[fi][1];
            boolean hasOverride = d.faceTextures.containsKey(faceKey);
            String statusLine = hasOverride ? "§aOverride ACTIVE" : "§7Using default texture";

            inv.setStack(editSlots[fi], uiGlint(faceItems[fi],
                "§a✏ Edit §f" + faceLabel + " §7(in place)",
                statusLine,
                "§8Modifies this block directly",
                "§8Click → paste URL"));

            inv.setStack(variantSlots[fi], ui(Items.PAPER,
                "§b✦ Variant §f" + faceLabel,
                statusLine,
                "§8Creates new block with this face",
                "§8Original stays unchanged",
                "§8Click → paste URL"));
        }

        // Spacers between pairs
        for (int s : new int[]{21,22,23,24,25,26}) inv.setStack(s, glass());

        // Row 3: Clear individual faces
        inv.setStack(27, ui(Items.WHITE_STAINED_GLASS_PANE,      "§c✕ Clear TOP",    faceStatus(d,"top")));
        inv.setStack(28, ui(Items.LIGHT_GRAY_STAINED_GLASS_PANE, "§c✕ Clear BOTTOM", faceStatus(d,"bottom")));
        inv.setStack(29, ui(Items.CYAN_STAINED_GLASS_PANE,       "§c✕ Clear NORTH",  faceStatus(d,"north")));
        inv.setStack(30, ui(Items.BLUE_STAINED_GLASS_PANE,       "§c✕ Clear SOUTH",  faceStatus(d,"south")));
        inv.setStack(31, ui(Items.PURPLE_STAINED_GLASS_PANE,     "§c✕ Clear EAST",   faceStatus(d,"east")));
        inv.setStack(32, ui(Items.MAGENTA_STAINED_GLASS_PANE,    "§c✕ Clear WEST",   faceStatus(d,"west")));
        for (int i = 33; i <= 44; i++) inv.setStack(i, glass());

        // Row 5: Bottom controls
        inv.setStack(45, uiGlint(Items.RED_CONCRETE,     "§c◀ Back to Editor", "§7Return"));
        inv.setStack(46, SlotManager.undoStackSize() > 0
            ? uiGlint(Items.ARROW, "§6↩ Undo", "§7" + SlotManager.undoStackSize() + " left")
            : ui(Items.GRAY_STAINED_GLASS_PANE, "§8Undo", "§7Nothing to undo"));
        inv.setStack(47, ui(Items.ORANGE_CONCRETE, "§6⊘ Clear ALL Faces", "§7Revert every face override"));
        for (int i = 48; i <= 52; i++) inv.setStack(i, glass());
        inv.setStack(53, uiGlint(Items.CHEST, "§a Give 1x", "§7Gives §f" + d.displayName));

        return inv;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void promptFace(ServerPlayerEntity player, String blockId, String face, int returnPage, boolean createVariant) {
        InputAction action = createVariant ? InputAction.SETFACE_VARIANT_URL : InputAction.SETFACE_URL;
        PENDING.put(player.getUuid(), new PendingInput(action, blockId, face, null, null, returnPage));
        player.closeHandledScreen();
        String mode = createVariant ? "§b(creates new variant block — original stays unchanged)" : "§a(modifies this block in place)";
        send(player, "§6[GUI] §ePaste image URL for §f" + face.toUpperCase() + " §eof '§f" + blockId + "§e'  " + mode + ":");
        send(player, "§7Type §ccancel §7to abort.");
    }

    private static void clearFace(ServerPlayerEntity player, SlotManager.SlotData d, String face) {
        SlotManager.pushUndo(d.customId, "clearface " + face);
        SlotManager.clearFaceTexture(d.customId, face);
        SlotManager.saveAll();
        CustomBlocksMod.broadcastUpdate(player.getServer(),
            new SlotUpdatePayload("clearface", d.index, d.customId, null, null, d.lightLevel, d.hardness, d.soundType, face));
        GuiState st = STATES.get(player.getUuid());
        if (st != null && st.mode() == GuiMode.FACE_EDITOR) openFaceEditor(player, d.customId, st.page());
        else reopenEditor(player, d.customId, STATES.getOrDefault(player.getUuid(), GuiState.main(0)).page());
    }

    private static void broadcastClearAllFaces(ServerPlayerEntity player, SlotManager.SlotData d) {
        CustomBlocksMod.broadcastUpdate(player.getServer(),
            new SlotUpdatePayload("clearfaces", d.index, d.customId, null, null, d.lightLevel, d.hardness, d.soundType));
    }

    private static void setSoundAndRefresh(ServerPlayerEntity player, SlotManager.SlotData d, String sound, int rp) {
        SlotManager.pushUndo(d.customId, "setsound");
        SlotManager.setSoundType(d.customId, sound); SlotManager.saveAll();
        CustomBlocksMod.broadcastUpdate(player.getServer(),
            new SlotUpdatePayload("setprop", d.index, d.customId, null, null, d.lightLevel, d.hardness, sound));
        reopenEditor(player, d.customId, rp);
    }

    private static void syncProp(ServerPlayerEntity player, SlotManager.SlotData dOld) {
        SlotManager.SlotData d = SlotManager.getById(dOld.customId);
        if (d == null) return;
        SlotManager.saveAll();
        CustomBlocksMod.broadcastUpdate(player.getServer(),
            new SlotUpdatePayload("setprop", d.index, d.customId, null, null, d.lightLevel, d.hardness, d.soundType));
    }

    private static void reopenEditor(ServerPlayerEntity player, String id, int returnPage) {
        player.getServer().execute(() -> openEditor(player, id, returnPage));
    }

    private static List<SlotManager.SlotData> sortedBlocks() {
        List<SlotManager.SlotData> list = new ArrayList<>(SlotManager.allSlots());
        list.removeIf(d -> "tab_icon".equals(d.customId));
        list.sort(Comparator.comparingInt(d -> d.index));
        return list;
    }

    private static String generateVariantId(String baseId, String face) {
        String candidate = baseId + "_" + face;
        if (!SlotManager.hasId(candidate)) return candidate;
        for (int i = 2; i <= 99; i++) { String c = candidate + "_" + i; if (!SlotManager.hasId(c)) return c; }
        return candidate + "_" + (System.currentTimeMillis() % 10000);
    }

    private static String cap(String s) { return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    private static float nextHardness(float cur) { for (int i = 0; i < HARD_CYCLE.length - 1; i++) if (Math.abs(cur - HARD_CYCLE[i]) < 0.01f) return HARD_CYCLE[i + 1]; return HARD_CYCLE[1]; }
    private static float prevHardness(float cur) { for (int i = HARD_CYCLE.length - 1; i > 0; i--) if (Math.abs(cur - HARD_CYCLE[i]) < 0.01f) return HARD_CYCLE[i - 1]; return HARD_CYCLE[0]; }
    private static String hardnessLabel(float h) { if (h < 0) return "∞ Unbreakable"; if (h == 0) return "0 (Instant)"; return String.valueOf(h); }
    private static String faceStatus(SlotManager.SlotData d, String face) { return d.faceTextures.containsKey(face) ? "§aOverride ACTIVE" : "§7No override (default)"; }
    private static ItemStack soundItem(SlotManager.SlotData d, String sound, Item item, String label) {
        return sound.equals(d.soundType)
            ? uiGlint(item, label + " §a✔", "§aCurrenly active")
            : ui(item, label, "§7Click to set");
    }
    private static boolean isUrl(String s) { return s.startsWith("http://") || s.startsWith("https://"); }
    private static void send(ServerPlayerEntity p, String msg) { p.sendMessage(Text.literal(msg), false); }
    private static void thread(ServerPlayerEntity player, Runnable r) { Thread t = new Thread(r, "PB-GUI"); t.setDaemon(true); t.start(); }

    private static ItemStack ui(Item item, String name, String... lore) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name).styled(st -> st.withItalic(false)));
        if (lore.length > 0) {
            List<Text> lines = new ArrayList<>();
            for (String l : lore) lines.add(lore(l));
            s.set(DataComponentTypes.LORE, new LoreComponent(lines));
        }
        return s;
    }
    private static ItemStack uiGlint(Item item, String name, String... lore) {
        ItemStack s = ui(item, name, lore);
        s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return s;
    }
    private static Text lore(String text) { return Text.literal(text).styled(s -> s.withItalic(false)); }
    private static ItemStack glass() { return ui(Items.GRAY_STAINED_GLASS_PANE, "§r"); }
}
