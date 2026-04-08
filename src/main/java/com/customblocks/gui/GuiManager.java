package com.customblocks.gui;

import com.customblocks.CustomBlocksMod;
import com.customblocks.ImageProcessor;
import com.customblocks.SlotManager;
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
 * Server-side GUI state machine for /cb gui.
 *
 * Two screens:
 *   MAIN   — paginated block list + Create / Undo
 *   EDITOR — full editor for one block (texture, faces, glow, hardness, sound, give, delete)
 *
 * URL and text inputs are collected via chat (same pattern as RectangleToolItem).
 */
public class GuiManager {

    // ── State ─────────────────────────────────────────────────────────────────

    public enum GuiMode { MAIN, EDITOR }

    public record GuiState(GuiMode mode, int page, String editingId, boolean confirmDelete) {
        static GuiState main(int page)    { return new GuiState(GuiMode.MAIN,   page, null, false); }
        static GuiState editor(String id, int page) { return new GuiState(GuiMode.EDITOR, page, id, false); }
        public GuiState withConfirmDelete() { return new GuiState(mode, page, editingId, true); }
    }

    // Multi-step chat input
    public enum InputAction {
        CREATE_ID, CREATE_NAME, CREATE_URL,   // 3-step wizard
        RETEXTURE_URL, SETFACE_URL, RENAME_TEXT
    }

    public record PendingInput(
            InputAction action,
            String  blockId,
            String  face,
            String  tempId,
            String  tempName,
            int     returnPage
    ) {}

    private static final Map<UUID, GuiState>     STATES  = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingInput> PENDING = new ConcurrentHashMap<>();

    private static final int BLOCKS_PER_PAGE = 36;  // 4 rows × 9

    // Hardness cycle values
    private static final float[] HARD_CYCLE = {-1f, 0f, 0.5f, 1.5f, 3f, 5f, 10f, 20f, 50f};

    // ── Public API ────────────────────────────────────────────────────────────

    public static void openMain(ServerPlayerEntity player, int page) {
        List<SlotManager.SlotData> all = sortedBlocks();
        int maxPage = Math.max(0, (all.size() - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, maxPage));
        STATES.put(player.getUuid(), GuiState.main(page));
        final int fp = page;
        player.getServer().execute(() -> {
            SimpleInventory inv = buildMain(fp);
            int totalPages = Math.max(1, (int) Math.ceil(all.size() / (double) BLOCKS_PER_PAGE));
            player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                    (syncId, playerInv, p) -> new CbScreenHandler(syncId, playerInv, inv),
                    Text.literal("§6§l✦ §r§6Custom Blocks §8| §7" + SlotManager.usedSlots() + "/" + SlotManager.MAX_SLOTS + " used")));
        });
    }

    public static void openEditor(ServerPlayerEntity player, String id, int returnPage) {
        if (!SlotManager.hasId(id)) { openMain(player, returnPage); return; }
        STATES.put(player.getUuid(), GuiState.editor(id, returnPage));
        player.getServer().execute(() -> {
            SlotManager.SlotData d = SlotManager.getById(id);
            if (d == null) { openMain(player, returnPage); return; }
            SimpleInventory inv = buildEditor(d, false);
            player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                    (syncId, playerInv, p) -> new CbScreenHandler(syncId, playerInv, inv),
                    Text.literal("§6§l✦ §r§f" + d.displayName)));
        });
    }

    public static boolean hasPending(ServerPlayerEntity player) {
        return PENDING.containsKey(player.getUuid());
    }

    public static void clearState(ServerPlayerEntity player) {
        STATES.remove(player.getUuid());
    }

    // ── Click Handler ─────────────────────────────────────────────────────────

    public static void handleClick(ServerPlayerEntity player, int slot, int button) {
        GuiState state = STATES.get(player.getUuid());
        if (state == null) return;
        if (state.mode() == GuiMode.MAIN)   handleMainClick(player, state, slot);
        else                                 handleEditorClick(player, state, slot, button);
    }

    private static void handleMainClick(ServerPlayerEntity player, GuiState state, int slot) {
        int page = state.page();

        if (slot == 0) {                        // ── Create New ──
            PENDING.put(player.getUuid(), new PendingInput(InputAction.CREATE_ID, null, null, null, null, page));
            player.closeHandledScreen();
            send(player, "§6[GUI] §eEnter a block §fID §e(e.g. §fmy_block§e) in chat, or type §ccancel§e:");
            return;
        }
        if (slot == 8) {                        // ── Undo ──
            if (SlotManager.undoStackSize() == 0) { send(player, "§7[GUI] Nothing to undo."); openMain(player, page); return; }
            SlotManager.UndoEntry entry = SlotManager.popUndo();
            if (entry == null) { openMain(player, page); return; }
            MinecraftServer guiServer = player.getServer();

            // Undo of creation → delete the block
            if (entry.previousState() == null) {
                SlotManager.SlotData cd = SlotManager.getById(entry.customId());
                if (cd != null) {
                    int cidx = cd.index;
                    SlotManager.remove(entry.customId());
                    SlotManager.saveAll();
                    CustomBlocksMod.broadcastUpdate(guiServer,
                        new SlotUpdatePayload("remove", cidx, entry.customId(), null, null, 0, 0, "stone"));
                    send(player, "§a[GUI] Undid create of §f" + entry.customId());
                }
                openMain(player, page);
                return;
            }

            if (SlotManager.restoreSnapshot(entry.previousState(), entry.wasDeleted())) {
                SlotManager.saveAll();
                SlotManager.SlotData d = SlotManager.getById(entry.previousState().customId);
                if (d != null) {
                    if (entry.wasDeleted()) {
                        CustomBlocksMod.broadcastUpdate(guiServer,
                            new SlotUpdatePayload("add", d.index, d.customId, d.displayName, d.texture,
                                    d.lightLevel, d.hardness, d.soundType));
                    } else {
                        if (d.texture != null)
                            CustomBlocksMod.broadcastUpdate(guiServer,
                                new SlotUpdatePayload("retexture", d.index, d.customId, null, d.texture,
                                        d.lightLevel, d.hardness, d.soundType));
                        CustomBlocksMod.broadcastUpdate(guiServer,
                            new SlotUpdatePayload("clearfaces", d.index, d.customId, null, null,
                                    d.lightLevel, d.hardness, d.soundType));
                    }
                    for (var fe : d.faceTextures.entrySet())
                        CustomBlocksMod.broadcastUpdate(guiServer,
                            new SlotUpdatePayload("setface", d.index, d.customId, null, fe.getValue(),
                                    d.lightLevel, d.hardness, d.soundType, fe.getKey()));
                    CustomBlocksMod.broadcastUpdate(guiServer,
                        new SlotUpdatePayload("setprop", d.index, d.customId, null, null,
                                d.lightLevel, d.hardness, d.soundType));
                    CustomBlocksMod.broadcastUpdate(guiServer,
                        new SlotUpdatePayload("rename", d.index, d.customId, d.displayName, null, 0, 0, "stone"));
                }
                send(player, "§a[GUI] Undid §f\"" + entry.description() + "\"§a on §f" + entry.customId());
            }
            openMain(player, page);
            return;
        }
        if (slot == 45) { openMain(player, page - 1); return; }  // ── Prev ──
        if (slot == 53) { openMain(player, page + 1); return; }  // ── Next ──

        // ── Block in list (slots 9-44) ──
        if (slot >= 9 && slot <= 44) {
            List<SlotManager.SlotData> blocks = sortedBlocks();
            int idx = page * BLOCKS_PER_PAGE + (slot - 9);
            if (idx < blocks.size()) {
                openEditor(player, blocks.get(idx).customId, page);
            }
        }
    }

    private static void handleEditorClick(ServerPlayerEntity player, GuiState state, int slot, int button) {
        String id = state.editingId();
        int returnPage = state.page();
        SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }

        switch (slot) {
            case 0 ->  openMain(player, returnPage);                            // Back
            case 8 -> {                                                          // Delete
                if (state.confirmDelete()) {
                    // Confirmed — delete
                    SlotManager.pushUndoDelete(id);
                    SlotManager.remove(id);
                    SlotManager.saveAll();
                    CustomBlocksMod.broadcastUpdate(player.getServer(),
                        new SlotUpdatePayload("remove", d.index, id, null, null, 0, 0, "stone"));
                    send(player, "§a[GUI] '" + id + "' deleted.");
                    openMain(player, returnPage);
                } else {
                    // First click — arm confirm
                    STATES.put(player.getUuid(), state.withConfirmDelete());
                    player.getServer().execute(() -> {
                        SlotManager.SlotData dd = SlotManager.getById(id);
                        if (dd == null) return;
                        SimpleInventory inv = buildEditor(dd, true);
                        // Reuse open screen — replace inventory contents by reopening
                        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                                (s, pi, p) -> new CbScreenHandler(s, pi, inv),
                                Text.literal("§6§l✦ §r§f" + dd.displayName)));
                    });
                }
            }

            // ── Texture buttons — prompt for URL ──
            case 10 -> promptUrl(player, InputAction.RETEXTURE_URL, id, null, returnPage, "§e(ALL faces)");
            case 11 -> promptUrl(player, InputAction.SETFACE_URL, id, "top",    returnPage, "§e(TOP face)");
            case 12 -> promptUrl(player, InputAction.SETFACE_URL, id, "bottom", returnPage, "§e(BOTTOM face)");
            case 13 -> promptUrl(player, InputAction.SETFACE_URL, id, "north",  returnPage, "§e(NORTH face)");
            case 14 -> promptUrl(player, InputAction.SETFACE_URL, id, "south",  returnPage, "§e(SOUTH face)");
            case 15 -> promptUrl(player, InputAction.SETFACE_URL, id, "east",   returnPage, "§e(EAST face)");
            case 16 -> promptUrl(player, InputAction.SETFACE_URL, id, "west",   returnPage, "§e(WEST face)");

            // ── Clear face buttons ──
            case 19 -> { SlotManager.pushUndo(id,"clearallfaces"); SlotManager.clearAllFaces(id);
                         SlotManager.saveAll(); broadcastClearAllFaces(player, d); reopenEditor(player, id, returnPage); }
            case 20 -> clearFace(player, d, "top");
            case 21 -> clearFace(player, d, "bottom");
            case 22 -> clearFace(player, d, "north");
            case 23 -> clearFace(player, d, "south");
            case 24 -> clearFace(player, d, "east");
            case 25 -> clearFace(player, d, "west");

            // ── Rename ──
            case 28 -> {
                PENDING.put(player.getUuid(), new PendingInput(InputAction.RENAME_TEXT, id, null, null, null, returnPage));
                player.closeHandledScreen();
                send(player, "§6[GUI] §eType the new name for §f'" + id + "'§e in chat (or §ccancel§e):");
            }

            // ── Glow ──
            case 30 -> { SlotManager.pushUndo(id,"setglow"); SlotManager.setLightLevel(id, Math.max(0, d.lightLevel - 1));
                         syncProp(player, d); reopenEditor(player, id, returnPage); }
            case 32 -> { SlotManager.pushUndo(id,"setglow"); SlotManager.setLightLevel(id, Math.min(15, d.lightLevel + 1));
                         syncProp(player, d); reopenEditor(player, id, returnPage); }

            // ── Hardness ──
            case 39 -> { SlotManager.pushUndo(id,"sethardness"); SlotManager.setHardness(id, prevHardness(d.hardness));
                         syncProp(player, d); reopenEditor(player, id, returnPage); }
            case 41 -> { SlotManager.pushUndo(id,"sethardness"); SlotManager.setHardness(id, nextHardness(d.hardness));
                         syncProp(player, d); reopenEditor(player, id, returnPage); }

            // ── Give ──
            case 34 -> {
                ItemStack stack = new ItemStack(CustomBlocksMod.SLOT_ITEMS[d.index], 1);
                player.getInventory().insertStack(stack);
                send(player, "§a[GUI] Given 1x §f'" + d.displayName + "'§a.");
                reopenEditor(player, id, returnPage);
            }

            // ── Sound row (45-53, inner 46-52 = stone/wood/grass/metal/glass/sand/wool) ──
            case 46 -> setSoundAndRefresh(player, d, "stone",  returnPage);
            case 47 -> setSoundAndRefresh(player, d, "wood",   returnPage);
            case 48 -> setSoundAndRefresh(player, d, "grass",  returnPage);
            case 49 -> setSoundAndRefresh(player, d, "metal",  returnPage);
            case 50 -> setSoundAndRefresh(player, d, "glass",  returnPage);
            case 51 -> setSoundAndRefresh(player, d, "sand",   returnPage);
            case 52 -> setSoundAndRefresh(player, d, "wool",   returnPage);
        }
    }

    // ── Chat Input ────────────────────────────────────────────────────────────

    /**
     * Called by the chat event. Returns true if the message was consumed.
     */
    public static boolean handleChatInput(ServerPlayerEntity player, String message) {
        UUID uuid = player.getUuid();
        PendingInput p = PENDING.remove(uuid);
        if (p == null) return false;

        String text = message.trim();
        if (text.equalsIgnoreCase("cancel")) {
            send(player, "§7[GUI] Cancelled.");
            openMain(player, p.returnPage());
            return true;
        }

        switch (p.action()) {

            case CREATE_ID -> {
                String id = text.toLowerCase().replaceAll("[^a-z0-9_]", "_");
                if (id.isEmpty()) { send(player, "§c[GUI] Invalid ID. Try again or type cancel."); PENDING.put(uuid, p); return true; }
                if (SlotManager.hasId(id)) { send(player, "§c[GUI] ID '" + id + "' already exists."); PENDING.put(uuid, p); return true; }
                PENDING.put(uuid, new PendingInput(InputAction.CREATE_NAME, null, null, id, null, p.returnPage()));
                send(player, "§6[GUI] §eNow type the §fdisplay name §e(e.g. §fMy Block§e):");
            }
            case CREATE_NAME -> {
                String name = text.replace("_", " ");
                PENDING.put(uuid, new PendingInput(InputAction.CREATE_URL, null, null, p.tempId(), name, p.returnPage()));
                send(player, "§6[GUI] §ePaste the §fimage URL §e(PNG, JPG, GIF, WebP…):");
            }
            case CREATE_URL -> {
                if (!isUrl(text)) { send(player, "§c[GUI] That doesn't look like a URL. Must start with http(s)://"); PENDING.put(uuid, p); return true; }
                String finalId = p.tempId(), finalName = p.tempName();
                int returnPage = p.returnPage();
                if (SlotManager.freeSlots() == 0) { send(player, "§c[GUI] All slots full!"); openMain(player, returnPage); return true; }
                send(player, "§e[GUI] Downloading…");
                thread(player, () -> {
                    try {
                        byte[] raw = ImageProcessor.download(text);
                        ImageProcessor.GifResult gif = ImageProcessor.isAnimatedGif(raw) ? ImageProcessor.processGif(raw) : null;
                        byte[] bytes; String animMeta = null;
                        if (gif != null) { bytes = gif.stripPng(); animMeta = gif.mcmeta(); }
                        else { bytes = ImageProcessor.toPng(raw); bytes = ImageProcessor.padToSquare(bytes); bytes = ImageProcessor.replaceBackground(bytes); }
                        final byte[] fb = bytes; final String fa = animMeta;
                        player.getServer().execute(() -> {
                            SlotManager.SlotData d = SlotManager.assign(finalId, finalName, fb);
                            if (d == null) { send(player, "§c[GUI] No free slots!"); return; }
                            if (fa != null) SlotManager.setAnimMeta(finalId, fa);
                            SlotManager.saveAll();
                            CustomBlocksMod.broadcastUpdate(player.getServer(),
                                new SlotUpdatePayload("add", d.index, finalId, finalName, fb, d.lightLevel, d.hardness, d.soundType));
                            send(player, "§a[GUI] '" + finalName + "' created!");
                            openMain(player, returnPage);
                        });
                    } catch (Exception e) {
                        player.getServer().execute(() -> { send(player, "§c[GUI] Download failed: " + e.getMessage()); openMain(player, returnPage); });
                    }
                });
            }

            case RETEXTURE_URL -> {
                if (!isUrl(text)) { send(player, "§c[GUI] Not a valid URL."); PENDING.put(uuid, p); return true; }
                String bid = p.blockId(); int rp = p.returnPage();
                send(player, "§e[GUI] Downloading…");
                thread(player, () -> {
                    try {
                        byte[] raw = ImageProcessor.download(text);
                        ImageProcessor.GifResult gif = ImageProcessor.isAnimatedGif(raw) ? ImageProcessor.processGif(raw) : null;
                        byte[] bytes; String animMeta = null;
                        if (gif != null) { bytes = gif.stripPng(); animMeta = gif.mcmeta(); }
                        else { bytes = ImageProcessor.toPng(raw); bytes = ImageProcessor.padToSquare(bytes); bytes = ImageProcessor.replaceBackground(bytes); }
                        final byte[] fb = bytes; final String fa = animMeta;
                        player.getServer().execute(() -> {
                            SlotManager.pushUndo(bid, "retexture");
                            SlotManager.SlotData d = SlotManager.getById(bid);
                            if (d == null) { openMain(player, rp); return; }
                            SlotManager.updateTexture(bid, fb);
                            if (fa != null) SlotManager.setAnimMeta(bid, fa);
                            SlotManager.saveAll();
                            CustomBlocksMod.broadcastUpdate(player.getServer(),
                                new SlotUpdatePayload("retexture", d.index, bid, null, fb, d.lightLevel, d.hardness, d.soundType));
                            send(player, "§a[GUI] All faces retextured for §f'" + bid + "'§a.");
                            openEditor(player, bid, rp);
                        });
                    } catch (Exception e) {
                        player.getServer().execute(() -> { send(player, "§c[GUI] Failed: " + e.getMessage()); openEditor(player, bid, rp); });
                    }
                });
            }

            case SETFACE_URL -> {
                if (!isUrl(text)) { send(player, "§c[GUI] Not a valid URL."); PENDING.put(uuid, p); return true; }
                String bid = p.blockId(), face = p.face(); int rp = p.returnPage();
                send(player, "§e[GUI] Downloading…");
                thread(player, () -> {
                    try {
                        byte[] raw = ImageProcessor.download(text);
                        byte[] bytes = ImageProcessor.toPng(raw);
                        bytes = ImageProcessor.padToSquare(bytes);
                        bytes = ImageProcessor.replaceBackground(bytes);
                        final byte[] fb = bytes;
                        player.getServer().execute(() -> {
                            SlotManager.pushUndo(bid, "setface " + face);
                            SlotManager.SlotData d = SlotManager.getById(bid);
                            if (d == null) { openMain(player, rp); return; }
                            SlotManager.setFaceTexture(bid, face, fb);
                            SlotManager.saveAll();
                            CustomBlocksMod.broadcastUpdate(player.getServer(),
                                new SlotUpdatePayload("setface", d.index, bid, null, fb, d.lightLevel, d.hardness, d.soundType, face));
                            send(player, "§a[GUI] §f" + face.toUpperCase() + " §aface updated on §f'" + bid + "'§a.");
                            openEditor(player, bid, rp);
                        });
                    } catch (Exception e) {
                        player.getServer().execute(() -> { send(player, "§c[GUI] Failed: " + e.getMessage()); openEditor(player, bid, rp); });
                    }
                });
            }

            case RENAME_TEXT -> {
                String bid = p.blockId(); int rp = p.returnPage();
                SlotManager.pushUndo(bid, "rename");
                SlotManager.SlotData d = SlotManager.getById(bid);
                if (d == null) { openMain(player, rp); return true; }
                SlotManager.rename(bid, text.replace("_", " "));
                SlotManager.saveAll();
                CustomBlocksMod.broadcastUpdate(player.getServer(),
                    new SlotUpdatePayload("rename", d.index, bid, text.replace("_", " "), null, 0, 0, "stone"));
                send(player, "§a[GUI] Renamed to §f'" + text + "'§a.");
                openEditor(player, bid, rp);
            }
        }
        return true;
    }

    // ── Inventory Builders ────────────────────────────────────────────────────

    private static SimpleInventory buildMain(int page) {
        SimpleInventory inv = new SimpleInventory(54);
        List<SlotManager.SlotData> blocks = sortedBlocks();
        int totalPages = Math.max(1, (int) Math.ceil(blocks.size() / (double) BLOCKS_PER_PAGE));

        // Row 0
        inv.setStack(0, uiGlint(Items.LIME_CONCRETE, "§a§lCreate New Block", "§7Click to start the creation wizard"));
        for (int i = 1; i <= 7; i++) inv.setStack(i, glass());
        inv.setStack(8, SlotManager.undoStackSize() > 0
                ? uiGlint(Items.ORANGE_CONCRETE, "§6Undo Last Change",
                    "§7Stack: §f" + SlotManager.undoStackSize() + " §7undos available")
                : ui(Items.GRAY_CONCRETE, "§7Undo §8(nothing to undo)"));

        // Rows 1-4: block list
        int start = page * BLOCKS_PER_PAGE;
        for (int i = 0; i < BLOCKS_PER_PAGE; i++) {
            int bIdx = start + i;
            int slot = 9 + i;
            if (bIdx < blocks.size()) {
                SlotManager.SlotData d = blocks.get(bIdx);
                ItemStack stack = new ItemStack(CustomBlocksMod.SLOT_ITEMS[d.index], 1);
                stack.set(DataComponentTypes.CUSTOM_NAME,
                        Text.literal("§f§l" + d.displayName).styled(s -> s.withItalic(false)));
                List<Text> lore = new ArrayList<>();
                lore.add(lore("§7ID: §f" + d.customId));
                lore.add(lore("§7Slot: §8#" + d.index + "  §7Glow: §e" + d.lightLevel + "  §7Sound: §f" + d.soundType));
                if (d.hasFaces()) lore.add(lore("§d⬡ Has per-face overrides"));
                if (d.isAnimated()) lore.add(lore("§b⟳ Animated GIF"));
                lore.add(lore("§8Click to edit"));
                stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
                inv.setStack(slot, stack);
            } else {
                inv.setStack(slot, glass());
            }
        }

        // Row 5: navigation
        if (page > 0)
            inv.setStack(45, uiGlint(Items.ARROW, "§f◀ Previous Page", "§7Page " + page + " / " + totalPages));
        else
            inv.setStack(45, ui(Items.RED_STAINED_GLASS_PANE, "§8◀ No Previous Page"));
        for (int i = 46; i <= 48; i++) inv.setStack(i, glass());
        inv.setStack(49, ui(Items.PAPER,
                "§ePage §f" + (page + 1) + " §7/ §f" + totalPages,
                "§7Total blocks: §f" + blocks.size()));
        for (int i = 50; i <= 52; i++) inv.setStack(i, glass());
        if ((page + 1) < totalPages)
            inv.setStack(53, uiGlint(Items.ARROW, "§fNext Page ▶", "§7Page " + (page + 2) + " / " + totalPages));
        else
            inv.setStack(53, ui(Items.RED_STAINED_GLASS_PANE, "§8No Next Page ▶"));

        return inv;
    }

    private static SimpleInventory buildEditor(SlotManager.SlotData d, boolean confirmDelete) {
        SimpleInventory inv = new SimpleInventory(54);

        // Row 0
        inv.setStack(0,  uiGlint(Items.RED_CONCRETE, "§c◀ Back", "§7Return to block list"));
        for (int i = 1; i <= 3; i++) inv.setStack(i, glass());
        // Block display
        ItemStack disp = new ItemStack(CustomBlocksMod.SLOT_ITEMS[d.index]);
        disp.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§f§l" + d.displayName).styled(s -> s.withItalic(false)));
        disp.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                lore("§7ID: §f" + d.customId),
                lore("§7Slot: §8#" + d.index))));
        inv.setStack(4, disp);
        for (int i = 5; i <= 7; i++) inv.setStack(i, glass());
        inv.setStack(8, confirmDelete
                ? uiGlint(Items.BARRIER, "§4§l⚠ CONFIRM DELETE", "§cClick again to permanently delete!", "§7This cannot be undone.")
                : ui(Items.RED_CONCRETE, "§c§lDelete Block", "§7Click once to arm, click again to confirm."));

        // Row 1: Texture controls
        inv.setStack(9,  glass());
        inv.setStack(10, uiGlint(Items.PAINTING,    "§b✦ Retexture ALL Faces",    "§7Replaces the texture on every face", "§8Click → enter URL in chat"));
        inv.setStack(11, ui(Items.WHITE_CONCRETE,    "§e▲ Set TOP Face",            "§8Click → enter URL in chat"));
        inv.setStack(12, ui(Items.LIGHT_GRAY_CONCRETE,"§e▼ Set BOTTOM Face",        "§8Click → enter URL in chat"));
        inv.setStack(13, ui(Items.CYAN_CONCRETE,     "§3N Set NORTH Face",          "§8Click → enter URL in chat"));
        inv.setStack(14, ui(Items.BLUE_CONCRETE,     "§9S Set SOUTH Face",          "§8Click → enter URL in chat"));
        inv.setStack(15, ui(Items.PURPLE_CONCRETE,   "§5E Set EAST Face",           "§8Click → enter URL in chat"));
        inv.setStack(16, ui(Items.MAGENTA_CONCRETE,  "§dW Set WEST Face",           "§8Click → enter URL in chat"));
        inv.setStack(17, glass());

        // Row 2: Clear faces
        inv.setStack(18, glass());
        inv.setStack(19, ui(Items.ORANGE_CONCRETE, "§6⊘ Clear ALL Faces",         "§7Reverts every face to default texture"));
        inv.setStack(20, ui(Items.WHITE_STAINED_GLASS_PANE,   "§7Clear TOP",      faceStatus(d, "top")));
        inv.setStack(21, ui(Items.LIGHT_GRAY_STAINED_GLASS_PANE, "§7Clear BOTTOM",faceStatus(d, "bottom")));
        inv.setStack(22, ui(Items.CYAN_STAINED_GLASS_PANE,    "§7Clear NORTH",    faceStatus(d, "north")));
        inv.setStack(23, ui(Items.BLUE_STAINED_GLASS_PANE,    "§7Clear SOUTH",    faceStatus(d, "south")));
        inv.setStack(24, ui(Items.PURPLE_STAINED_GLASS_PANE,  "§7Clear EAST",     faceStatus(d, "east")));
        inv.setStack(25, ui(Items.MAGENTA_STAINED_GLASS_PANE, "§7Clear WEST",     faceStatus(d, "west")));
        inv.setStack(26, glass());

        // Row 3: Rename, Glow, Give
        inv.setStack(27, glass());
        inv.setStack(28, uiGlint(Items.NAME_TAG, "§e✎ Rename Block", "§7Current: §f" + d.displayName, "§8Click → type name in chat"));
        inv.setStack(29, glass());
        inv.setStack(30, ui(Items.RED_DYE,      "§c▼ Glow -1",  "§7Current: §e" + d.lightLevel));
        inv.setStack(31, uiGlint(Items.GLOWSTONE_DUST, "§e✦ Glow Level: §f" + d.lightLevel, "§70 = no light   §f15 = max"));
        inv.setStack(32, ui(Items.YELLOW_DYE,   "§a▲ Glow +1",  "§7Current: §e" + d.lightLevel));
        inv.setStack(33, glass());
        inv.setStack(34, uiGlint(Items.CHEST, "§a✦ Give to Me", "§7Gives 1x §f" + d.displayName));
        inv.setStack(35, glass());

        // Row 4: Hardness
        inv.setStack(36, glass());
        inv.setStack(37, glass());
        inv.setStack(38, glass());
        inv.setStack(39, ui(Items.RED_DYE,      "§c▼ Hardness -",  "§7Current: §f" + hardnessLabel(d.hardness)));
        inv.setStack(40, ui(Items.IRON_PICKAXE, "§b⚙ Hardness: §f" + hardnessLabel(d.hardness),
                "§7-1 = Unbreakable   §70 = Instant break", "§7Default = 1.5"));
        inv.setStack(41, ui(Items.LIME_DYE,     "§a▲ Hardness +",  "§7Current: §f" + hardnessLabel(d.hardness)));
        inv.setStack(42, glass());
        inv.setStack(43, glass());
        inv.setStack(44, glass());

        // Row 5: Sound
        inv.setStack(45, glass());
        inv.setStack(46, soundItem(d, "stone",  Items.STONE,      "§fStone"));
        inv.setStack(47, soundItem(d, "wood",   Items.OAK_LOG,    "§fWood"));
        inv.setStack(48, soundItem(d, "grass",  Items.GRASS_BLOCK,"§fGrass"));
        inv.setStack(49, soundItem(d, "metal",  Items.IRON_BLOCK, "§fMetal"));
        inv.setStack(50, soundItem(d, "glass",  Items.GLASS,      "§fGlass"));
        inv.setStack(51, soundItem(d, "sand",   Items.SAND,       "§fSand"));
        inv.setStack(52, soundItem(d, "wool",   Items.WHITE_WOOL, "§fWool"));
        inv.setStack(53, glass());

        return inv;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void promptUrl(ServerPlayerEntity player, InputAction action, String blockId, String face, int returnPage, String faceLabel) {
        PENDING.put(player.getUuid(), new PendingInput(action, blockId, face, null, null, returnPage));
        player.closeHandledScreen();
        send(player, "§6[GUI] §ePaste the image URL for §f" + faceLabel + "§e (or §ccancel§e):");
    }

    private static void clearFace(ServerPlayerEntity player, SlotManager.SlotData d, String face) {
        SlotManager.pushUndo(d.customId, "clearface " + face);
        SlotManager.clearFaceTexture(d.customId, face);
        SlotManager.saveAll();
        CustomBlocksMod.broadcastUpdate(player.getServer(),
            new SlotUpdatePayload("clearface", d.index, d.customId, null, null,
                    d.lightLevel, d.hardness, d.soundType, face));
        reopenEditor(player, d.customId, STATES.get(player.getUuid()).page());
    }

    private static void broadcastClearAllFaces(ServerPlayerEntity player, SlotManager.SlotData d) {
        CustomBlocksMod.broadcastUpdate(player.getServer(),
            new SlotUpdatePayload("clearfaces", d.index, d.customId, null, null,
                    d.lightLevel, d.hardness, d.soundType));
    }

    private static void setSoundAndRefresh(ServerPlayerEntity player, SlotManager.SlotData d, String sound, int rp) {
        SlotManager.pushUndo(d.customId, "setsound");
        SlotManager.setSoundType(d.customId, sound);
        SlotManager.saveAll();
        CustomBlocksMod.broadcastUpdate(player.getServer(),
            new SlotUpdatePayload("setprop", d.index, d.customId, null, null,
                    d.lightLevel, d.hardness, sound));
        reopenEditor(player, d.customId, rp);
    }

    private static void syncProp(ServerPlayerEntity player, SlotManager.SlotData dOld) {
        SlotManager.SlotData d = SlotManager.getById(dOld.customId);
        if (d == null) return;
        SlotManager.saveAll();
        CustomBlocksMod.broadcastUpdate(player.getServer(),
            new SlotUpdatePayload("setprop", d.index, d.customId, null, null,
                    d.lightLevel, d.hardness, d.soundType));
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

    private static float nextHardness(float cur) {
        for (int i = 0; i < HARD_CYCLE.length - 1; i++)
            if (Math.abs(cur - HARD_CYCLE[i]) < 0.01f) return HARD_CYCLE[i + 1];
        return HARD_CYCLE[1]; // default 0
    }
    private static float prevHardness(float cur) {
        for (int i = HARD_CYCLE.length - 1; i > 0; i--)
            if (Math.abs(cur - HARD_CYCLE[i]) < 0.01f) return HARD_CYCLE[i - 1];
        return HARD_CYCLE[0];
    }
    private static String hardnessLabel(float h) {
        if (h < 0) return "∞ Unbreakable";
        if (h == 0) return "0 (Instant)";
        return String.valueOf(h);
    }
    private static String faceStatus(SlotManager.SlotData d, String face) {
        return d.faceTextures.containsKey(face) ? "§aOverride active" : "§7Using default texture";
    }

    private static ItemStack soundItem(SlotManager.SlotData d, String sound, Item item, String label) {
        boolean active = sound.equals(d.soundType);
        ItemStack s = active
                ? uiGlint(item, label + (active ? " §a✔" : ""), active ? "§a▶ Currently active" : "§7Click to set")
                : ui(item, label, "§7Click to set");
        return s;
    }

    private static boolean isUrl(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
    }

    private static void send(ServerPlayerEntity p, String msg) {
        p.sendMessage(Text.literal(msg), false);
    }

    private static void thread(ServerPlayerEntity player, Runnable r) {
        Thread t = new Thread(r, "CB-GUI-Download");
        t.setDaemon(true);
        t.start();
    }

    // ── Item factory helpers ──────────────────────────────────────────────────

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

    private static Text lore(String text) {
        return Text.literal(text).styled(s -> s.withItalic(false));
    }

    // Returns a fresh pane each time — avoids sharing a single mutable ItemStack across inventories
    private static ItemStack glass() { return ui(Items.GRAY_STAINED_GLASS_PANE, "§r"); }
}
