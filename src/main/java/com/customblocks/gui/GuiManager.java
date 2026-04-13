package com.customblocks.gui;

import com.customblocks.CustomBlocksMod;
import com.customblocks.CustomBlocksConfig;
import com.customblocks.ImageProcessor;
import com.customblocks.core.SlotData;
import com.customblocks.core.SlotManager;
import com.customblocks.core.UndoManager;
import com.customblocks.network.NetworkManager;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Server-side chest-GUI manager with back-stack navigation.
 * <p>
 * Uses {@link GuiState} records for immutable state snapshots and
 * {@link UndoManager} for undo/redo operations.
 */
public class GuiManager {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);

    private static final String MHF_LEFT = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzdhZWU5YTc1YmYwZGY3ODk3MTgzMDE1Y2NhMGIyYTdkNzU1YzYzMzg4ZmYwMTc1MmQ1ZjQ0MTlmYzY0NSJ9fX0=";
    private static final String MHF_RIGHT = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjgyYWQxYjljYjRkZDIxMjU5YzBkNzVhYTMxNWZmMzg5YzNjZWY3NTJiZTM5NDkzMzgxNjRiYWM4NGE5NmUifX19";

    private static net.minecraft.item.ItemStack customHead(String b64, String name, String... lore) {
        net.minecraft.item.ItemStack s = new net.minecraft.item.ItemStack(net.minecraft.item.Items.PLAYER_HEAD);
        s.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, net.minecraft.text.Text.literal(name).styled(st -> st.withItalic(false)));
        if(lore.length > 0){ java.util.List<net.minecraft.text.Text> ll = new java.util.ArrayList<>(); for(String l:lore)ll.add(lore(l)); s.set(net.minecraft.component.DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(ll)); }
        com.mojang.authlib.properties.PropertyMap properties = new com.mojang.authlib.properties.PropertyMap();
        properties.put("textures", new com.mojang.authlib.properties.Property("textures", b64));
        s.set(net.minecraft.component.DataComponentTypes.PROFILE, new net.minecraft.component.type.ProfileComponent(java.util.Optional.empty(), java.util.Optional.empty(), properties));
        return s;
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

    // ── Per-player state ─────────────────────────────────────────────────────
    private static final Map<UUID, GuiState>       STATES   = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<GuiState>> BACK_STACK = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingInput>   PENDING  = new ConcurrentHashMap<>();
    private static final Set<UUID> REOPENING_SCREENS        = ConcurrentHashMap.newKeySet();
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

    private static void refreshScreen(ServerPlayerEntity player, SimpleInventory newInv) {
        CbScreenHandler h = HANDLERS.get(player.getUuid());
        if (h != null && !h.isDisposed() && player.currentScreenHandler == h) {
            h.refreshWith(newInv);
        }
    }

    public static boolean isReopeningScreen(UUID uuid) { return REOPENING_SCREENS.contains(uuid); }

    private static final java.util.Set<UUID> RESTORING = new java.util.HashSet<>();

    /**
     * Push current state to back-stack before navigating away.
     */
    private static void pushBackStack(UUID uuid) {
        if (RESTORING.contains(uuid)) return;
        GuiState current = STATES.get(uuid);
        if (current != null) {
            BACK_STACK.computeIfAbsent(uuid, k -> new ArrayDeque<>()).push(current);
        }
    }

    /**
     * ESC handler: pops back-stack for proper navigation (BUG-02 fix).
     */
    public static void handleEscBack(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        PENDING.remove(uuid); // Clear any ghost pending inputs
        GuiState state = STATES.get(uuid);
        if (state == null) return;

        Deque<GuiState> stack = BACK_STACK.get(uuid);
        if (stack != null && !stack.isEmpty()) {
            GuiState prev = stack.pop();
            restoreState(player, prev);
        } else {
            // At root - fully close
            STATES.remove(uuid);
        }
    }

    // ── Cleanup on disconnect ────────────────────────────────────────────────

    public static void onPlayerDisconnect(UUID uuid) {
        STATES.remove(uuid);
        BACK_STACK.remove(uuid);
        PENDING.remove(uuid);
        HANDLERS.remove(uuid);
        ANIM_PARAMS.remove(uuid);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public static void openToolsGui(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.tools());
        openScreen(player, new SimpleNamedScreenHandlerFactory((syncId, playerInv, p) -> new CbScreenHandler(syncId, playerInv, buildToolsGui(player)), Text.literal("§d§lTools & Cosmetics")));
    }

    public static void openMain(ServerPlayerEntity player, int page) {
        STATES.put(player.getUuid(), GuiState.main(page));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildMain(player, STATES.getOrDefault(player.getUuid(), GuiState.main(0)).page())),
            Text.literal("§8CustomBlocks - Main Menu")));
    }

    public static void openEditorPicker(ServerPlayerEntity player) { openEditorPicker(player, 0); }
    public static void openEditorPicker(ServerPlayerEntity player, int page) {
        int total = sortedBlocks().size();
        int max   = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.picker(page));
        final int fp = page;
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildPicker(fp, false)),
            Text.literal("§b§l▶ §r§fChoose a block §7(ESC = back)")));
    }

    public static void openEditor(ServerPlayerEntity player, String id, int returnPage, boolean fromCommand) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        if (!fromCommand) pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), fromCommand
            ? GuiState.editorFromCommand(id)
            : GuiState.editor(id, returnPage));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildEditor(d, false)),
            Text.literal("§e§l✎ §r§fEditor §8— §e" + d.displayName + " §7(ESC = back)")));
    }

    public static void openEditor(ServerPlayerEntity player, String id, int returnPage) {
        openEditor(player, id, returnPage, false);
    }

    public static void openFaceEditor(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.faceEditor(id, returnPage));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildFaceEditor(d)),
            Text.literal("§d§l⬡ §r§fFace Editor §8— §d" + d.displayName + " §7(ESC = back)")));
    }

    public static void openShapeEditor(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.shapeEditor(id, returnPage));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildShapeEditor(d, 0)),
            Text.literal("§5§l⬡ §r§fShape Editor §8— §5" + d.displayName + " §7(ESC = back)")));
    }

    public static void openMaintenanceMenu(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.maintenance());
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildMaintenanceMenu()),
            Text.literal("§b§l✦ §r§fServer Maintenance §7(ESC = back)")));
    }


    public static void openHelpGui(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.help());
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildHelpGui()),
            Text.literal("§a§l✦ §r§fHelp & Info §7(ESC = back)")));
    }

    public static void openPropertiesGui(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.properties(id, returnPage));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildPropertiesGui(d)),
            Text.literal("§6§l⚙ §r§fProperties §8— §6" + d.displayName + " §7(ESC = back)")));
    }

    public static void openSoundMenu(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.sound(id, returnPage));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildSoundMenu(d)),
            Text.literal("§e§l♫ §r§fBlock Sounds §8— §e" + d.displayName + " §7(ESC = back)")));
    }

    public static void openTabIconPicker(ServerPlayerEntity player, int page) {
        int total = sortedBlocks().size();
        int max   = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        STATES.put(player.getUuid(), GuiState.picker(page));
        final int fp = page;
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildPicker(fp, false)),
            Text.literal("§b§l▶ §r§fChoose Tab Icon §7(ESC = back)")));
    }

    public static void openPortConfigMenu(ServerPlayerEntity player) {
        STATES.put(player.getUuid(), GuiState.findPortGui());
        SimpleInventory inv = new SimpleInventory(27);
        for(int i=0; i<27; i++) inv.setStack(i, glass());
        inv.setStack(10, uiGlint(net.minecraft.item.Items.REDSTONE, "§ePort 8000", "§7Standard port for most servers", "§cClick to set port to 8000"));
        inv.setStack(12, uiGlint(net.minecraft.item.Items.REDSTONE_BLOCK, "§ePort 8080", "§7Alternative standard port", "§cClick to set port to 8080"));
        inv.setStack(14, uiGlint(net.minecraft.item.Items.COMPARATOR, "§ePort 25565", "§7Minecraft server port", "§cClick to set port to 25565"));
        inv.setStack(16, uiGlint(net.minecraft.item.Items.REPEATER, "§ePort 24454", "§7Default CustomBlocks port", "§cClick to set port to 24454"));
        inv.setStack(22, ui(net.minecraft.item.Items.RED_CONCRETE, "§c◀ Back to Main Menu"));
        openScreen(player, new SimpleNamedScreenHandlerFactory((s, pi, p) -> new CbScreenHandler(s, pi, inv), net.minecraft.text.Text.literal("§6§l⚙ §r§fResource Pack Port")));
    }

    public static void openBrokenBlocks(ServerPlayerEntity player) { openBrokenBlocks(player, 0); }
    public static void openBrokenBlocks(ServerPlayerEntity player, int page) {
        int total = brokenBlocks().size();
        int max   = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.pickerBroken(page));
        final int fp = page;
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildPicker(fp, true)),
            Text.literal("§c§l▶ §r§fBroken Blocks §7(ESC = back)")));
    }

    public static List<SlotData> brokenBlocks() {
        List<SlotData> list = new ArrayList<>();
        for (SlotData d : sortedBlocks()) {
            if (d.texture != null && ImageProcessor.isBrokenTexture(d.texture)) {
                list.add(d);
            }
        }
        return list;
    }

    private static void restoreState(ServerPlayerEntity player, GuiState state) {
        RESTORING.add(player.getUuid());
        try {
            switch (state.mode()) {
                case MAIN -> openMain(player, state.page());
                case PICKER -> openEditorPicker(player, state.page());
                case EDITOR -> openEditor(player, state.editingId(), state.page(), state.fromCommand());
                case FACE_EDITOR -> openFaceEditor(player, state.editingId(), state.page());
                case SHAPE_EDITOR -> openShapeEditor(player, state.editingId(), state.page());
                case MAINTENANCE_MENU -> openMaintenanceMenu(player);
                case HELP_MENU -> openHelpGui(player);
                case TOOLS_GUI -> openToolsGui(player);
                case PICKER_BROKEN -> openBrokenBlocks(player, state.page());
                case PROPERTIES_MENU -> openPropertiesGui(player, state.editingId(), state.page());
                case SOUND_MENU -> openSoundMenu(player, state.editingId(), state.page());
                case TAB_ICON_MENU -> openTabIconPicker(player, state.page());
                case FIND_PORT_GUI -> openPortConfigMenu(player);
                case ANIM_GUI -> openAnimGui(player, state.editingId());
                default -> openMain(player, 0);
            }
        } finally {
            RESTORING.remove(player.getUuid());
        }
    }

    // ── Click dispatch ───────────────────────────────────────────────────────

    public static void handleClick(ServerPlayerEntity player, int slot, int button) {
        GuiState state = STATES.get(player.getUuid());
        if (state == null) return;
        switch (state.mode()) {
            case MAIN         -> handleMainClick(player, state, slot);
            case PICKER       -> handlePickerClick(player, state, slot, false);
            case PICKER_BROKEN-> handlePickerClick(player, state, slot, true);
            case TAB_ICON_MENU-> handleTabIconMenuClick(player, state, slot);
            case FIND_PORT_GUI-> handlePortGuiClick(player, state, slot);
            case EDITOR       -> handleEditorClick(player, state, slot, button);
            case FACE_EDITOR  -> handleFaceEditorClick(player, state, slot, button);
            case SHAPE_EDITOR -> handleShapeEditorClick(player, state, slot, button);
            case MAINTENANCE_MENU -> handleMaintenanceClick(player, state, slot);
            case HELP_MENU      -> handleHelpClick(player, state, slot);
            case TOOLS_GUI      -> handleToolsClick(player, state, slot);
            case PROPERTIES_MENU-> handlePropertiesClick(player, state, slot);
            case SOUND_MENU     -> handleSoundClick(player, state, slot);
            case ANIM_GUI     -> handleAnimGuiClick(player, state, slot);
        }
    }

    // ── Chat input handler ───────────────────────────────────────────────────

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
                    SlotData dd = SlotManager.getById(delId);
                    UndoManager.pushUndoDeletion(delId, dd.deepCopy(), player.getUuid());
                    SlotManager.remove(delId); SlotManager.saveAll();
                    NetworkManager.broadcastUpdate(player.getServer(), new SlotUpdatePayload("remove", dd.index, delId, null, null, 0, 0, "stone"));
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
                    else { bytes = ImageProcessor.toPng(raw); bytes = ImageProcessor.padToSquare(bytes); bytes = ImageProcessor.replaceBackground(bytes); bytes = ImageProcessor.resizeTo(bytes, CustomBlocksConfig.defaultTextureSize); }
                    final byte[] fb = bytes; final String fa = anim;
                    srv.execute(() -> {
                        if (SlotManager.hasId(id)) { send(player, "§c'" + id + "' already exists."); openMain(player, rp); return; }
                        SlotData d = SlotManager.assign(id, name, fb);
                        if (d == null) { send(player, "§cNo free slots!"); openMain(player, rp); return; }
                        if (fa != null) SlotManager.setAnimMeta(id, fa);
                        UndoManager.pushUndoCreate(id, player.getUuid()); SlotManager.saveAll();
                        SlotData updated = SlotManager.getById(id);
                        NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("add", d.index, id, name, fb, d.lightLevel, d.hardness, d.soundType, null, null, updated != null ? updated.animMeta : fa));
                        send(player, "§a[GUI] Created '§f" + name + "§a'! §7(slot #" + d.index + ")");
                        openEditor(player, id, rp);
                    });
                } catch (Exception e) { srv.execute(() -> { send(player, "§c[GUI] Failed: " + e.getMessage()); openMain(player, rp); }); } });
                return true;
            }
            case RETEXTURE_URL -> {
                if (!isUrl(text)) { send(player, "§cNeeds a URL."); openEditor(player, blockId, rp); return true; }
                SlotData d = SlotManager.getById(blockId);
                if (d == null) { openMain(player, rp); return true; }
                send(player, "§e[GUI] Downloading texture…");
                MinecraftServer srv = player.getServer();
                thread(player, () -> { try {
                    byte[] raw = ImageProcessor.download(text);
                    ImageProcessor.GifResult gif = ImageProcessor.isAnimatedGif(raw) ? ImageProcessor.processGif(raw) : null;
                    byte[] bytes; String anim = null;
                    if (gif != null) { bytes = gif.stripPng(); anim = gif.mcmeta(); }
                    else { bytes = ImageProcessor.toPng(raw); bytes = ImageProcessor.padToSquare(bytes); bytes = ImageProcessor.replaceBackground(bytes); bytes = ImageProcessor.resizeTo(bytes, CustomBlocksConfig.defaultTextureSize); }
                    final byte[] fb = bytes; final String fa = anim;
                    srv.execute(() -> {
                        UndoManager.pushUndoMutation(blockId, SlotManager.getById(blockId), "retexture", player.getUuid());
                        SlotData dd = SlotManager.getById(blockId);
                        if (dd == null) { openMain(player, rp); return; }
                        SlotManager.updateTexture(blockId, fb);
                        if (fa != null) SlotManager.setAnimMeta(blockId, fa);
                        SlotManager.saveAll();
                        NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("retexture", dd.index, blockId, null, fb, dd.lightLevel, dd.hardness, dd.soundType));
                        send(player, "§a[GUI] Texture updated for '§f" + blockId + "§a'.");
                        openEditor(player, blockId, rp);
                    });
                } catch (Exception e) { srv.execute(() -> { send(player, "§c[GUI] Failed: " + e.getMessage()); openEditor(player, blockId, rp); }); } });
                return true;
            }
            case SETFACE_URL -> {
                if (!isUrl(text)) { send(player, "§cNeeds a URL."); openFaceEditor(player, blockId, rp); return true; }
                String face = pending.face();
                SlotData d = SlotManager.getById(blockId);
                if (d == null) { openMain(player, rp); return true; }
                send(player, "§e[GUI] Downloading " + face + " face…");
                MinecraftServer srv = player.getServer();
                thread(player, () -> { try {
                    byte[] fb = ImageProcessor.toPng(ImageProcessor.download(text));
                    fb = ImageProcessor.padToSquare(fb); fb = ImageProcessor.replaceBackground(fb); fb = ImageProcessor.resizeTo(fb, CustomBlocksConfig.defaultTextureSize);
                    final byte[] ffb = fb;
                    srv.execute(() -> {
                        UndoManager.pushUndoMutation(blockId, SlotManager.getById(blockId), "setface " + face, player.getUuid());
                        SlotData dd = SlotManager.getById(blockId);
                        if (dd == null) { openMain(player, rp); return; }
                        SlotManager.setFaceTexture(blockId, face, ffb); SlotManager.saveAll();
                        NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("setface", dd.index, blockId, null, ffb, dd.lightLevel, dd.hardness, dd.soundType, face));
                        send(player, "§a[GUI] §f" + face.toUpperCase() + " §aface set on '§f" + blockId + "§a'.");
                        openFaceEditor(player, blockId, rp);
                    });
                } catch (Exception e) { srv.execute(() -> { send(player, "§c[GUI] Failed: " + e.getMessage()); openFaceEditor(player, blockId, rp); }); } });
                return true;
            }
            case SETFACE_VARIANT_URL -> {
                if (!isUrl(text)) { send(player, "§cNeeds a URL."); openFaceEditor(player, blockId, rp); return true; }
                String face = pending.face();
                SlotData orig = SlotManager.getById(blockId);
                if (orig == null) { openMain(player, rp); return true; }
                send(player, "§e[GUI] Creating variant with " + face + " face…");
                MinecraftServer srv = player.getServer();
                thread(player, () -> { try {
                    byte[] fb = ImageProcessor.toPng(ImageProcessor.download(text));
                    fb = ImageProcessor.padToSquare(fb); fb = ImageProcessor.replaceBackground(fb); fb = ImageProcessor.resizeTo(fb, CustomBlocksConfig.defaultTextureSize);
                    final byte[] ffb = fb;
                    srv.execute(() -> {
                        if (SlotManager.freeSlots() == 0) { send(player, "§cNo free slots!"); openFaceEditor(player, blockId, rp); return; }
                        String varId = generateVariantId(blockId, face);
                        String varName = orig.displayName + " (" + cap(face) + ")";
                        byte[] texCopy = orig.texture != null ? orig.texture.clone() : null;
                        SlotData nb = SlotManager.assign(varId, varName, texCopy);
                        if (nb == null) { send(player, "§cNo free slots!"); openFaceEditor(player, blockId, rp); return; }
                        SlotManager.setLightLevel(varId, orig.lightLevel); SlotManager.setHardness(varId, orig.hardness);
                        SlotManager.setSoundType(varId, orig.soundType);
                        if (orig.animMeta != null) SlotManager.setAnimMeta(varId, orig.animMeta);
                        for (var e : orig.faceTextures.entrySet()) SlotManager.setFaceTexture(varId, e.getKey(), e.getValue().clone());
                        SlotManager.setFaceTexture(varId, face, ffb);
                        UndoManager.pushUndoCreate(varId, player.getUuid()); SlotManager.saveAll();
                        SlotData fresh = SlotManager.getById(varId);
                        if (fresh != null) {
                            NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("add", fresh.index, varId, varName, texCopy, fresh.lightLevel, fresh.hardness, fresh.soundType, null, null, fresh.animMeta));
                            for (var fe : fresh.faceTextures.entrySet())
                                NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("setface", fresh.index, varId, null, fe.getValue(), fresh.lightLevel, fresh.hardness, fresh.soundType, fe.getKey()));
                        }
                        player.getInventory().insertStack(nb.index < CustomBlocksMod.SLOT_ITEMS.length && CustomBlocksMod.SLOT_ITEMS[nb.index] != null ? new ItemStack(CustomBlocksMod.SLOT_ITEMS[nb.index], 1) : ItemStack.EMPTY);
                        send(player, "§a[GUI] Variant '§f" + varId + "§a' created & given!");
                        openFaceEditor(player, varId, rp);
                    });
                } catch (Exception e) { srv.execute(() -> { send(player, "§c[GUI] Failed: " + e.getMessage()); openFaceEditor(player, blockId, rp); }); } });
                return true;
            }
            case RENAME_TEXT -> {
                SlotData d = SlotManager.getById(blockId);
                if (d == null) { openMain(player, rp); return true; }
                UndoManager.pushUndoMutation(blockId, d, "rename", player.getUuid());
                SlotManager.rename(blockId, text.replace("_"," ")); SlotManager.saveAll();
                NetworkManager.broadcastUpdate(player.getServer(), new SlotUpdatePayload("rename", d.index, blockId, text.replace("_"," "), null, 0, 0, "stone"));
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
                    byte[] finalBytes;
                    if (isBlock) {
                        SlotData dd = SlotManager.getById(targetId);
                        if (dd.texture != null) finalBytes = dd.texture.clone();
                        else throw new Exception("Block has no texture");
                    } else { finalBytes = ImageProcessor.downloadAndProcess(text); }
                    final byte[] bytes = finalBytes;
                    srv.execute(() -> {
                        SlotManager.setTabIconTexture(bytes);
                        if (SlotManager.hasId("tab_icon")) {
                            SlotData ex = SlotManager.getById("tab_icon");
                            SlotManager.updateTexture("tab_icon", bytes); SlotManager.saveAll();
                            NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("retexture", ex.index, "tab_icon", null, bytes, ex.lightLevel, ex.hardness, ex.soundType));
                        } else if (SlotManager.freeSlots() > 0) {
                            SlotData iconSlot = SlotManager.assign("tab_icon", "Tab Icon", bytes);
                            if (iconSlot != null) {
                                SlotManager.saveAll();
                                NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("add", iconSlot.index, "tab_icon", "Tab Icon", bytes, iconSlot.lightLevel, iconSlot.hardness, iconSlot.soundType));
                            }
                        }
                        NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("tabicon", -1, null, null, bytes, 0, 0, "stone"));
                        send(player, "§a[GUI] Tab icon updated!");
                        openMain(player, rp);
                    });
                } catch (Exception e) { srv.execute(() -> { send(player, "§c[GUI] Failed: " + e.getMessage()); openMain(player, rp); }); } });
                return true;
            }
            case ADDSHAPE_COORDS -> {
                try {
                    SlotData.ShapeBox box = SlotData.ShapeBox.parse(text);
                    UndoManager.pushUndoMutation(blockId, SlotManager.getById(blockId), "addshape", player.getUuid());
                    SlotManager.addBox(blockId, box);
                    SlotManager.saveAll();
                    SlotData d = SlotManager.getById(blockId);
                    broadcastShape(player.getServer(), d);
                    send(player, "§a[Shape] Box added! Total: §f" + (d.shapeBoxes != null ? d.shapeBoxes.size() : 0));
                } catch (Exception e) { send(player, "§cBad coords. Use: x1,y1,z1,x2,y2,z2 (0–16)"); }
                openShapeEditor(player, blockId, rp); return true;
            }
            case REID_TEXT -> {
                if ("__givesquare__".equals(blockId)) {
                    String col = text.toLowerCase().trim();
                    if (!List.of("black","yellow","green").contains(col)) { send(player, "§cChoose: §fblack §7| §fyellow §7| §fgreen"); openMain(player, rp); return true; }
                    Item it = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, col + "_square"));
                    if (it != null && it != Items.AIR) player.getInventory().insertStack(new ItemStack(it, 1));
                    send(player, "§a[GUI] Given §f" + col + " Square§a!"); openMain(player, rp); return true;
                }
                if ("__givetriangle__".equals(blockId)) {
                    String col = text.toLowerCase().trim();
                    if (!List.of("black","yellow","green").contains(col)) { send(player, "§cChoose: §fblack §7| §fyellow §7| §fgreen"); openMain(player, rp); return true; }
                    Item it = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, col + "_triangle"));
                    if (it != null && it != Items.AIR) player.getInventory().insertStack(new ItemStack(it, 1));
                    send(player, "§a[GUI] Given §f" + col + " Triangle§a!"); openMain(player, rp); return true;
                }
                String newId = text.toLowerCase().replaceAll("[^a-z0-9_\\-]", "_");
                if (newId.isEmpty())          { send(player, "§cInvalid ID."); openEditor(player, blockId, rp); return true; }
                if (SlotManager.hasId(newId)) { send(player, "§c'" + newId + "' already taken."); openEditor(player, blockId, rp); return true; }
                UndoManager.pushUndoMutation(blockId, SlotManager.getById(blockId), "reid", player.getUuid());
                SlotData d = SlotManager.getById(blockId);
                SlotManager.reId(blockId, newId); SlotManager.saveAll();
                SlotData upd = SlotManager.getById(newId);
                NetworkManager.broadcastUpdate(player.getServer(), new SlotUpdatePayload("remove", d.index, blockId, null, null, 0, 0, "stone"));
                NetworkManager.broadcastUpdate(player.getServer(), new SlotUpdatePayload("add", upd.index, newId, upd.displayName, upd.texture, upd.lightLevel, upd.hardness, upd.soundType, null, null, upd.animMeta));
                send(player, "§a[CustomBlocks] Re-ID'd '§f" + blockId + "§a' → '§f" + newId + "§a'.");
                openEditor(player, newId, rp); return true;
            }
            case ADMIN_CUSTOM_TITLE -> {
                send(player, "§7[CustomBlocks] Action cancelled.");
                openMain(player, 0);
                return true;
            }
        }
        return false;
    }

    public static boolean hasPending(ServerPlayerEntity player)  { return PENDING.containsKey(player.getUuid()); }
    public static void clearState(ServerPlayerEntity player) { STATES.remove(player.getUuid()); PENDING.remove(player.getUuid()); BACK_STACK.remove(player.getUuid()); }

    // ── Click handlers ────────────────────────────────────────────────────────

    private static void handlePortGuiClick(ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 10) { com.customblocks.CustomBlocksConfig.setResourcePackPort(8000); send(player, "§aPort set to 8000. Please restart server for changes to take effect."); openMain(player, 0); }
        if (slot == 12) { com.customblocks.CustomBlocksConfig.setResourcePackPort(8080); send(player, "§aPort set to 8080. Please restart server for changes to take effect."); openMain(player, 0); }
        if (slot == 14) { com.customblocks.CustomBlocksConfig.setResourcePackPort(25565); send(player, "§aPort set to 25565. Please restart server for changes to take effect."); openMain(player, 0); }
        if (slot == 16) { com.customblocks.CustomBlocksConfig.setResourcePackPort(24454); send(player, "§aPort set to 24454. Please restart server for changes to take effect."); openMain(player, 0); }
        if (slot == 22) { openMain(player, 0); }
    }

    private static void handleToolsClick(ServerPlayerEntity player, GuiState state, int slot) {
        switch (slot) {
            case 20 -> { // Rainbow Rectangle Wand
                try {
                    net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(com.customblocks.CustomBlocksMod.MOD_ID, "rainbow_rectangle"));
                    if (item != null && item != net.minecraft.item.Items.AIR) {
                        player.getInventory().insertStack(new net.minecraft.item.ItemStack(item));
                        send(player, "§6[CustomBlocks] §eGiven §6Rainbow Rectangle§e!");
                    }
                } catch (Exception e) { send(player, "§cCould not give rectangle wand."); }
            }
            case 21 -> { // Color Square - prompt for color
                PENDING.put(player.getUuid(), new PendingInput(InputAction.REID_TEXT, "__givesquare__", null, null, null, state.page()));
                closeForPrompt(player);
                send(player, "§6[GUI] §eType color: §fblack §7| §fyellow §7| §fgreen§e:");
            }
            case 22 -> { // Color Triangle - prompt for color
                PENDING.put(player.getUuid(), new PendingInput(InputAction.REID_TEXT, "__givetriangle__", null, null, null, state.page()));
                closeForPrompt(player);
                send(player, "§6[GUI] §eType color: §fblack §7| §fyellow §7| §fgreen§e:");
            }
            case 24 -> openTabIconPicker(player, 0); // Tab Icon
            case 45 -> openMain(player, 0);     // Back
        }
    }

    private static void handleTabIconMenuClick(ServerPlayerEntity player, GuiState state, int slot) {
        int page = state.page();
        PENDING.remove(player.getUuid());
        if (slot == 11) { PENDING.put(player.getUuid(), new PendingInput(InputAction.SETTABICON_URL, null, null, null, null, page)); closeForPrompt(player); send(player, "§6[GUI] §ePaste URL or Block ID for the tab icon (or §ccancel§e):"); }
        if (slot == 15) { openTabIconPicker(player, 0); }
    }

    private static void handlePickerClick(ServerPlayerEntity player, GuiState state, int slot, boolean brokenOnly) {
        int page = state.page();
        if (slot == 0) { openMain(player, 0); return; }
        if (slot == 45) {
            if (brokenOnly) openBrokenBlocks(player, Math.max(0, page-1));
            else openEditorPicker(player, Math.max(0, page-1));
            return;
        }
        if (slot == 53) {
            if (brokenOnly) openBrokenBlocks(player, page+1);
            else openEditorPicker(player, page+1);
            return;
        }
        if (slot >= 18 && slot <= 35) {
            List<SlotData> blocks = brokenOnly ? brokenBlocks() : sortedBlocks();
            int idx = page * BLOCKS_PER_PAGE + (slot - 18);
            if (idx < blocks.size()) {
                openEditor(player, blocks.get(idx).customId, page);
            }
        }
    }

    
    private static void handleMainClick(ServerPlayerEntity player, GuiState state, int slot) {
        UUID uuid = player.getUuid();
        switch (slot) {
            case 10 -> openEditorPicker(player, 0); 
            case 12 -> openToolsGui(player); 
            case 14 -> openMaintenanceMenu(player); 
            case 16 -> openHelpGui(player); 
            case 21 -> {
                int undoSz = UndoManager.undoSize(uuid);
                if (undoSz == 0) { send(player, "§7Nothing to undo."); refreshScreen(player, buildMain(player, state.page())); return; }
                UndoManager.UndoEntry entry = UndoManager.popUndo(uuid);
                if (entry == null) { refreshScreen(player, buildMain(player, state.page())); return; }
                applyUndoEntry(player, entry);
                refreshScreen(player, buildMain(player, state.page()));
            }
            case 22 -> { PENDING.put(uuid, new PendingInput(InputAction.CREATE_ID, null, null, null, null, state.page())); closeForPrompt(player); send(player, "§6[GUI] §eType a block §fID §e(a-z 0-9 _ only) or §ccancel§e:"); }
            case 23 -> {
                int redoSz = UndoManager.redoSize(uuid);
                if (redoSz == 0) { send(player, "§7Nothing to redo."); refreshScreen(player, buildMain(player, state.page())); return; }
                UndoManager.UndoEntry entry = UndoManager.popRedo(uuid);
                if (entry == null) { refreshScreen(player, buildMain(player, state.page())); return; }
                applyRedoEntry(player, entry);
                refreshScreen(player, buildMain(player, state.page()));
            }
        }
    }

    private static void applyUndoEntry(ServerPlayerEntity player, UndoManager.UndoEntry entry) {
        MinecraftServer gsrv = player.getServer();
        if (entry.previousState() == null) {
            SlotData cd = SlotManager.getById(entry.customId());
            if (cd != null) {
                UndoManager.pushRedo(new UndoManager.UndoEntry(entry.customId(), cd.deepCopy(), "create", true, player.getUuid()));
                SlotManager.remove(entry.customId()); SlotManager.saveAll();
                NetworkManager.broadcastUpdate(gsrv, new SlotUpdatePayload("remove", cd.index, entry.customId(), null, null, 0, 0, "stone"));
                send(player, "§a[GUI] Undid create of §f" + entry.customId());
            }
            return;
        }
        SlotData prev = entry.previousState();
        SlotData curForRedo = SlotManager.getById(prev.customId);
        if (curForRedo != null) {
            UndoManager.pushRedo(new UndoManager.UndoEntry(entry.customId(), curForRedo.deepCopy(), entry.description(), entry.wasDeleted(), player.getUuid()));
        }
        if (SlotManager.restoreSnapshot(prev, entry.wasDeleted())) {
            SlotManager.saveAll();
            SlotData d = SlotManager.getById(prev.customId);
            if (d != null) {
                if (entry.wasDeleted()) {
                    NetworkManager.broadcastUpdate(gsrv, new SlotUpdatePayload("add", d.index, d.customId, d.displayName, d.texture, d.lightLevel, d.hardness, d.soundType, null, null, d.animMeta));
                    for (var fe : d.faceTextures.entrySet()) NetworkManager.broadcastUpdate(gsrv, new SlotUpdatePayload("setface", d.index, d.customId, null, fe.getValue(), d.lightLevel, d.hardness, d.soundType, fe.getKey()));
                } else {
                    if (d.texture != null) NetworkManager.broadcastUpdate(gsrv, new SlotUpdatePayload("retexture", d.index, d.customId, null, d.texture, d.lightLevel, d.hardness, d.soundType));
                    NetworkManager.broadcastUpdate(gsrv, new SlotUpdatePayload("clearfaces", d.index, d.customId, null, null, d.lightLevel, d.hardness, d.soundType));
                    for (var fe : d.faceTextures.entrySet()) NetworkManager.broadcastUpdate(gsrv, new SlotUpdatePayload("setface", d.index, d.customId, null, fe.getValue(), d.lightLevel, d.hardness, d.soundType, fe.getKey()));
                    NetworkManager.broadcastUpdate(gsrv, new SlotUpdatePayload("setprop", d.index, d.customId, null, null, d.lightLevel, d.hardness, d.soundType));
                    NetworkManager.broadcastUpdate(gsrv, new SlotUpdatePayload("rename", d.index, d.customId, d.displayName, null, 0, 0, "stone"));
                }
            }
            send(player, "§a[GUI] Undid §f\"" + entry.description() + "\"§a on §f" + entry.customId() + " §7(" + UndoManager.undoSize(player.getUuid()) + " left)");
        }
    }

    private static void applyRedoEntry(ServerPlayerEntity player, UndoManager.UndoEntry entry) {
        MinecraftServer gsrv = player.getServer();
        if (entry.previousState() == null) {
            SlotData cd = SlotManager.getById(entry.customId());
            if (cd != null) {
                UndoManager.pushUndoForRedo(new UndoManager.UndoEntry(entry.customId(), cd.deepCopy(), "delete", true, player.getUuid()));
                SlotManager.remove(entry.customId()); SlotManager.saveAll();
                NetworkManager.broadcastUpdate(gsrv, new SlotUpdatePayload("remove", cd.index, entry.customId(), null, null, 0, 0, "stone"));
                send(player, "§a[GUI] Redid delete of §f" + entry.customId());
            }
            return;
        }
        SlotData prev = entry.previousState();
        SlotData curForUndo = SlotManager.getById(prev.customId);
        if (curForUndo != null) {
            UndoManager.pushUndoForRedo(new UndoManager.UndoEntry(entry.customId(), curForUndo.deepCopy(), entry.description(), entry.wasDeleted(), player.getUuid()));
        }
        if (SlotManager.restoreSnapshot(prev, entry.wasDeleted())) {
            SlotManager.saveAll();
            SlotData d = SlotManager.getById(prev.customId);
            if (d != null) {
                if (entry.wasDeleted()) {
                    NetworkManager.broadcastUpdate(gsrv, new SlotUpdatePayload("add", d.index, d.customId, d.displayName, d.texture, d.lightLevel, d.hardness, d.soundType, null, null, d.animMeta));
                } else {
                    if (d.texture != null) NetworkManager.broadcastUpdate(gsrv, new SlotUpdatePayload("retexture", d.index, d.customId, null, d.texture, d.lightLevel, d.hardness, d.soundType));
                    NetworkManager.broadcastUpdate(gsrv, new SlotUpdatePayload("clearfaces", d.index, d.customId, null, null, d.lightLevel, d.hardness, d.soundType));
                    for (var fe : d.faceTextures.entrySet()) NetworkManager.broadcastUpdate(gsrv, new SlotUpdatePayload("setface", d.index, d.customId, null, fe.getValue(), d.lightLevel, d.hardness, d.soundType, fe.getKey()));
                    NetworkManager.broadcastUpdate(gsrv, new SlotUpdatePayload("setprop", d.index, d.customId, null, null, d.lightLevel, d.hardness, d.soundType));
                    NetworkManager.broadcastUpdate(gsrv, new SlotUpdatePayload("rename", d.index, d.customId, d.displayName, null, 0, 0, "stone"));
                }
            }
            send(player, "§a[GUI] Redid §f\"" + entry.description() + "\"§a on §f" + entry.customId() + " §7(" + UndoManager.redoSize(player.getUuid()) + " redo left)");
        }
    }

    private static void handleEditorClick(ServerPlayerEntity player, GuiState state, int slot, int button) {
        String id = state.editingId(); int rp = state.page();
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, rp); return; }
        UUID uuid = player.getUuid();
        switch (slot) {
            case 0  -> openEditorPicker(player, rp);
            case 2  -> { player.getInventory().insertStack(CustomBlocksMod.safeSlotItem(d.index)!=null?new ItemStack(CustomBlocksMod.safeSlotItem(d.index),1):ItemStack.EMPTY); send(player,"§a[GUI] Given 1x §f"+d.displayName); openEditor(player,id,rp); }
            case 6  -> {
                UndoManager.pushUndoMutation(id, d, "setcollision", uuid); SlotManager.setCollision(id,!d.noCollision); SlotManager.saveAll();
                SlotData upd = SlotManager.getById(id);
                NetworkManager.broadcastUpdate(player.getServer(), new SlotUpdatePayload("setcollision",upd.index,id,null,null,0,0,"stone",null,upd.noCollision?"false":"true"));
                send(player,"§a[GUI] Collision: §f"+(upd.noCollision?"§cOFF":"§aON")); refreshEditorInPlace(player, id, rp);
            }
            case 8  -> { PENDING.put(uuid,new PendingInput(InputAction.RETEXTURE_URL,id,null,null,null,rp)); closeForPrompt(player); send(player,"§6[GUI] §ePaste image URL for ALL faces of '§f"+id+"§e' (or §ccancel§e):"); }
            case 10 -> openFaceEditor(player, id, rp);
            case 12 -> openShapeEditor(player, id, rp);
            case 14 -> openPropertiesGui(player, id, rp);
            case 16 -> openSoundMenu(player, id, rp);
            case 22 -> { if (d.isAnimated()) openAnimGui(player, id); }
            case 28 -> { PENDING.put(uuid,new PendingInput(InputAction.RENAME_TEXT,id,null,null,null,rp)); closeForPrompt(player); send(player,"§6[GUI] §eType new name for '§f"+id+"§e' (or §ccancel§e):"); }
            case 29 -> { PENDING.put(uuid,new PendingInput(InputAction.REID_TEXT,id,null,null,null,rp)); closeForPrompt(player); send(player,"§6[GUI] §eType new ID for '§f"+id+"§e' (a-z 0-9 _ -) (or §ccancel§e):"); }
            case 30 -> { PENDING.put(uuid,new PendingInput(InputAction.CREATE_ID,id,null,null,null,rp)); closeForPrompt(player); send(player,"§6[GUI] §eType new ID to duplicate '§f"+id+"§e' into (or §ccancel§e):"); }
            case 34 -> {
                if (state.confirmDelete()) {
                    UndoManager.pushUndoDeletion(id, d.deepCopy(), uuid); SlotManager.remove(id); SlotManager.saveAll();
                    NetworkManager.broadcastUpdate(player.getServer(), new SlotUpdatePayload("remove", d.index, id, null, null, 0, 0, "stone"));
                    send(player, "§a[GUI] '" + id + "' deleted."); openMain(player, rp);
                } else {
                    STATES.put(uuid, state.withConfirmDelete(true));
                    SlotData dd = SlotManager.getById(id); if (dd == null) return;
                    REOPENING_SCREENS.add(uuid);
                    player.openHandledScreen(new SimpleNamedScreenHandlerFactory((s,pi,p)->new CbScreenHandler(s,pi,buildEditor(dd,true)), Text.literal("§c§l⚠ Confirm DELETE — §r§f" + dd.displayName)));
                    REOPENING_SCREENS.remove(uuid);
                }
            }
        }
    }

    private static void refreshEditorInPlace(ServerPlayerEntity player, String id, int rp) {
        SlotData fresh = SlotManager.getById(id);
        if (fresh == null) { openMain(player, rp); return; }
        refreshScreen(player, buildEditor(fresh, false));
    }

    private static void handleShapeEditorClick(ServerPlayerEntity player, GuiState state, int slot, int button) {
        String id = state.editingId(); int rp = state.page(); int boxPage = state.shapeBoxPage();
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, rp); return; }
        List<SlotData.ShapeBox> boxes = d.shapeBoxes != null ? new ArrayList<>(d.shapeBoxes) : new ArrayList<>();
        UUID uuid = player.getUuid();

        if (slot == 0) { openEditor(player,id,rp); return; }
        if (slot == 8) {
            UndoManager.pushUndoMutation(id, d, "setcollision", uuid); SlotManager.setCollision(id,!d.noCollision); SlotManager.saveAll();
            SlotData upd = SlotManager.getById(id);
            NetworkManager.broadcastUpdate(player.getServer(), new SlotUpdatePayload("setcollision",upd.index,id,null,null,0,0,"stone",null,upd.noCollision?"false":"true"));
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
        if (slot == 22) { PENDING.put(uuid, new PendingInput(InputAction.ADDSHAPE_COORDS,id,null,null,null,rp)); closeForPrompt(player); send(player,"§6[Shape] §eType coords (or §ccancel§e): §7x1,y1,z1,x2,y2,z2  §8(0–16)"); return; }
        if (slot == 23) { UndoManager.pushUndoMutation(id, d, "clearshape", uuid); SlotManager.setShape(id, null); SlotManager.saveAll(); broadcastShape(player.getServer(),SlotManager.getById(id)); send(player,"§a[Shape] Cleared — full cube."); reopenShapeEditor(player,id,rp,0); return; }
        if (slot >= 28 && slot <= 35) {
            int boxIdx = boxPage*9 + (slot-28);
            if (boxIdx < boxes.size()) { UndoManager.pushUndoMutation(id, d, "removeshape", uuid); SlotManager.removeBox(id,boxIdx); SlotManager.saveAll(); broadcastShape(player.getServer(),SlotManager.getById(id)); send(player,"§a[Shape] Removed box #"+boxIdx+"."); int np=Math.min(boxPage,Math.max(0,(boxes.size()-2)/9)); reopenShapeEditor(player,id,rp,np); }
            return;
        }
        List<SlotData> variants = findShapeVariants(id);
        if (slot >= 37 && slot <= 44) { int vi=slot-37; if(vi<variants.size()) openEditor(player,variants.get(vi).customId,rp); return; }
        if (slot==45 && boxPage>0) { reopenShapeEditor(player,id,rp,boxPage-1); return; }
        if (slot==53) { int maxPg=Math.max(0,(boxes.size()-1)/9); if(boxPage<maxPg) reopenShapeEditor(player,id,rp,boxPage+1); }
    }

    private static void handleMaintenanceClick(ServerPlayerEntity player, GuiState state, int slot) {
        if(slot == 0) { openMain(player, 0); return; }
        if(slot == 10) openTabIconPicker(player, 0);
        else if(slot == 12) openBrokenBlocks(player, 0);
        else if(slot == 14) openPortConfigMenu(player);
        else if(slot == 16) { player.closeHandledScreen(); player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb export"); }
    }

    private static void handleHelpClick(ServerPlayerEntity player, GuiState state, int slot) {
        if(slot == 0) openMain(player, 0);
    }

    private static void handlePropertiesClick(ServerPlayerEntity player, GuiState state, int slot) {
        if(slot == 0) { openEditor(player, state.editingId(), state.page()); return; }
        String id = state.editingId(); int rp = state.page();
        SlotData d = SlotManager.getById(id);
        if(d == null) { openMain(player, rp); return; }
        UUID uuid = player.getUuid();
        switch(slot) {
            case 10 -> { UndoManager.pushUndoMutation(id, d, "setglow", uuid); SlotManager.setLightLevel(id,Math.max(0,d.lightLevel-1)); syncProp(player,d); refreshScreen(player, buildPropertiesGui(SlotManager.getById(id))); }
            case 12 -> { UndoManager.pushUndoMutation(id, d, "setglow", uuid); SlotManager.setLightLevel(id,Math.min(15,d.lightLevel+1)); syncProp(player,d); refreshScreen(player, buildPropertiesGui(SlotManager.getById(id))); }
            case 14 -> { UndoManager.pushUndoMutation(id, d, "sethardness", uuid); SlotManager.setHardness(id,prevHardness(d.hardness)); syncProp(player,d); refreshScreen(player, buildPropertiesGui(SlotManager.getById(id))); }
            case 16 -> { UndoManager.pushUndoMutation(id, d, "sethardness", uuid); SlotManager.setHardness(id,nextHardness(d.hardness)); syncProp(player,d); refreshScreen(player, buildPropertiesGui(SlotManager.getById(id))); }
            case 22 -> {
                UndoManager.pushUndoMutation(id, d, "setcollision", uuid); SlotManager.setCollision(id,!d.noCollision); SlotManager.saveAll();
                SlotData upd = SlotManager.getById(id);
                NetworkManager.broadcastUpdate(player.getServer(), new SlotUpdatePayload("setcollision",upd.index,id,null,null,0,0,"stone",null,upd.noCollision?"false":"true"));
                send(player,"§a[GUI] Collision: §f"+(upd.noCollision?"§cOFF":"§aON")); refreshScreen(player, buildPropertiesGui(upd));
            }
        }
    }

    private static void handleSoundClick(ServerPlayerEntity player, GuiState state, int slot) {
        if(slot == 0) { openEditor(player, state.editingId(), state.page()); return; }
        String id = state.editingId(); int rp = state.page();
        SlotData d = SlotManager.getById(id);
        if(d == null) { openMain(player, rp); return; }
        UUID uuid = player.getUuid();
        switch(slot) {
            case 10->{ setSoundQuiet(player,d,"stone",uuid); refreshScreen(player, buildSoundMenu(SlotManager.getById(id))); }
            case 11->{ setSoundQuiet(player,d,"wood",uuid);  refreshScreen(player, buildSoundMenu(SlotManager.getById(id))); }
            case 12->{ setSoundQuiet(player,d,"grass",uuid); refreshScreen(player, buildSoundMenu(SlotManager.getById(id))); }
            case 13->{ setSoundQuiet(player,d,"metal",uuid); refreshScreen(player, buildSoundMenu(SlotManager.getById(id))); }
            case 14->{ setSoundQuiet(player,d,"glass",uuid); refreshScreen(player, buildSoundMenu(SlotManager.getById(id))); }
            case 15->{ setSoundQuiet(player,d,"sand",uuid);  refreshScreen(player, buildSoundMenu(SlotManager.getById(id))); }
            case 16->{ setSoundQuiet(player,d,"gravel",uuid);refreshScreen(player, buildSoundMenu(SlotManager.getById(id))); }
        }
    }

    private static void handleFaceEditorClick(ServerPlayerEntity player, GuiState state, int slot, int button) {
        String id = state.editingId(); int rp = state.page();
        SlotData d = SlotManager.getById(id);
        if (d==null) { openMain(player,rp); return; }
        UUID uuid = player.getUuid();
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
                if (UndoManager.undoSize(uuid)>0) { UndoManager.UndoEntry e=UndoManager.popUndo(uuid); if(e!=null&&e.previousState()!=null){SlotManager.restoreSnapshot(e.previousState(),e.wasDeleted());SlotManager.saveAll();SlotData dd=SlotManager.getById(id);if(dd!=null)NetworkManager.broadcastUpdate(player.getServer(),new SlotUpdatePayload("clearfaces",dd.index,id,null,null,dd.lightLevel,dd.hardness,dd.soundType));send(player,"§a[GUI] Undid '"+e.description()+"'.");} }
                openFaceEditor(player,id,rp);
            }
            case 47 -> { UndoManager.pushUndoMutation(id, d, "clearallfaces", uuid); SlotManager.clearAllFaces(id); SlotManager.saveAll(); broadcastClearAllFaces(player,d); send(player,"§a[GUI] All face overrides cleared."); openFaceEditor(player,id,rp); }
            case 53 -> { player.getInventory().insertStack(CustomBlocksMod.safeSlotItem(d.index)!=null?new ItemStack(CustomBlocksMod.safeSlotItem(d.index),1):ItemStack.EMPTY); send(player,"§a[GUI] Given 1x §f"+d.displayName); openFaceEditor(player,id,rp); }
        }
    }

    // ── Shape helpers ────────────────────────────────────────────────────────

    private static void createShapeVariant(ServerPlayerEntity player, SlotData d, String id,
                                            String preset, int rp, int boxPage) {
        UUID uuid = player.getUuid();
        String varId = generateShapeVariantId(id, preset);
        if (SlotManager.hasId(varId)) { send(player,"§e[Shape] '§f"+varId+"§e' already exists — opening it."); openShapeEditor(player,varId,rp); return; }
        if (SlotManager.freeSlots()==0) { send(player,"§c[Shape] No free slots!"); reopenShapeEditor(player,id,rp,boxPage); return; }
        List<SlotData.ShapeBox> presetBoxes = SlotManager.SHAPE_PRESETS.get(preset);
        String varName = d.displayName + " (" + cap(preset) + ")";
        byte[] texCopy = d.texture != null ? d.texture.clone() : null;
        SlotData nb = SlotManager.assign(varId, varName, texCopy);
        if (nb == null) { send(player,"§c[Shape] Assign failed!"); reopenShapeEditor(player,id,rp,boxPage); return; }
        SlotManager.setLightLevel(varId,d.lightLevel); SlotManager.setHardness(varId,d.hardness); SlotManager.setSoundType(varId,d.soundType);
        if (d.animMeta!=null) SlotManager.setAnimMeta(varId,d.animMeta);
        for (var e : d.faceTextures.entrySet()) SlotManager.setFaceTexture(varId,e.getKey(),e.getValue().clone());
        SlotManager.setShape(varId, presetBoxes!=null ? new ArrayList<>(presetBoxes) : null);
        if (d.noCollision) SlotManager.setCollision(varId, false);
        UndoManager.pushUndoCreate(varId, uuid); SlotManager.saveAll();
        SlotData fresh = SlotManager.getById(varId);
        if (fresh != null) {
            NetworkManager.broadcastUpdate(player.getServer(), new SlotUpdatePayload("add",fresh.index,varId,varName,texCopy,fresh.lightLevel,fresh.hardness,fresh.soundType,null,null,fresh.animMeta));
            for (var fe : fresh.faceTextures.entrySet()) NetworkManager.broadcastUpdate(player.getServer(), new SlotUpdatePayload("setface",fresh.index,varId,null,fe.getValue(),fresh.lightLevel,fresh.hardness,fresh.soundType,fe.getKey()));
            broadcastShape(player.getServer(), fresh);
            if (fresh.noCollision) NetworkManager.broadcastUpdate(player.getServer(), new SlotUpdatePayload("setcollision",fresh.index,varId,null,null,0,0,"stone",null,"false"));
        }
        send(player,"§a[Shape] ✔ Created '§f"+varName+"§a' (ID: §f"+varId+"§a)");
        openShapeEditor(player, varId, rp);
    }

    private static void applyPresetToCurrent(ServerPlayerEntity player, SlotData d, String id,
                                              String preset, int rp, int boxPage) {
        List<SlotData.ShapeBox> boxes = SlotManager.SHAPE_PRESETS.get(preset);
        UndoManager.pushUndoMutation(id, d, "setshape", player.getUuid());
        SlotManager.setShape(id, boxes!=null ? new ArrayList<>(boxes) : null); SlotManager.saveAll();
        broadcastShape(player.getServer(), SlotManager.getById(id));
        send(player,"§a[Shape] Applied '§f"+preset+"§a' to current block.");
        reopenShapeEditor(player,id,rp,boxPage);
    }

    private static List<SlotData> findShapeVariants(String baseId) {
        List<SlotData> result = new ArrayList<>();
        for (String p : PRESET_NAMES) {
            for (int n = 0; n <= 9; n++) {
                String cand = n==0 ? (baseId+"_"+p) : (baseId+"_"+p+"_"+n);
                SlotData v = SlotManager.getById(cand);
                if (v != null) result.add(v);
            }
        }
        return result;
    }

    private static void reopenShapeEditor(ServerPlayerEntity player, String id, int rp, int boxPage) {
        SlotData d = SlotManager.getById(id);
        if (d==null) { openMain(player,rp); return; }
        STATES.put(player.getUuid(), GuiState.shapeEditor(id,rp).withShapeBoxPage(boxPage));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s,pi,p)->new CbScreenHandler(s,pi,buildShapeEditor(d,boxPage)),
            Text.literal("§5§l⬡ §r§fShape Editor §8— §5"+d.displayName+" §7(ESC = back)")));
    }

    // ── Anim GUI ─────────────────────────────────────────────────────────────

    public static void openAnimGui(ServerPlayerEntity player, String id) {
        SlotData d = SlotManager.getById(id);
        if (d == null || !d.isAnimated()) return;
        float fps = 10f; boolean interp = false; int frameCount = 1;
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
                        int t = el.isJsonObject() && el.getAsJsonObject().has("time") ? el.getAsJsonObject().get("time").getAsInt() : 1;
                        totalTicks += t;
                    }
                    fps = Math.round((20f / ((float) totalTicks / frameCount)) * 10f) / 10f;
                }
            } else if (anim.has("frametime")) {
                int ft = anim.get("frametime").getAsInt();
                fps = Math.round((20f / ft) * 10f) / 10f;
            }
        } catch (Exception ignored) {}
        final float finalFps = fps; final boolean finalInterp = interp; final int finalFrames = frameCount;
        ANIM_PARAMS.put(player.getUuid(), new AnimParams(fps, interp, frameCount));
        STATES.put(player.getUuid(), GuiState.animGui(id));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildAnimGui(id, finalFps, finalInterp, finalFrames)),
            Text.literal("§b§l▶ §r§fAnimation Settings §8— §b" + d.displayName)));
    }

    private static void handleAnimGuiClick(ServerPlayerEntity player, GuiState state, int slot) {
        String id = state.editingId();
        AnimParams p = ANIM_PARAMS.getOrDefault(player.getUuid(), new AnimParams(10f, false, 1));
        float fps = p.fps(); boolean interp = p.interpolate(); int frames = p.frameCount();
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
            case 46 -> { applyAnimSettings(player, id, fps, interp, frames); ANIM_PARAMS.remove(player.getUuid()); STATES.remove(player.getUuid()); player.closeHandledScreen(); return; }
            case 49 -> { ANIM_PARAMS.remove(player.getUuid()); STATES.remove(player.getUuid()); player.closeHandledScreen(); return; }
            default -> { return; }
        }
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
        sb.append("]}}");;
        String newMeta = sb.toString();
        UndoManager.pushUndoMutation(id, SlotManager.getById(id), "animsettings", player.getUuid());
        SlotManager.setAnimMeta(id, newMeta);
        SlotManager.saveAll();
        SlotData d = SlotManager.getById(id);
        if (d == null) return;
        SlotUpdatePayload pkt = new SlotUpdatePayload("animsettings", d.index, id, d.displayName,
                null, d.lightLevel, d.hardness, d.soundType, null, null, newMeta);
        NetworkManager.broadcastUpdate(player.getServer(), pkt);
        send(player, "§a[CustomBlocks] Animation saved for '§f" + d.displayName + "§a'  §7(" + String.format("%.1f", fps) + " fps)");
    }

    // ── Builders ──────────────────────────────────────────────────────────────

    private static SimpleInventory buildToolsGui(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(54);
        for(int i=0; i<54; i++) inv.setStack(i, glass());
        inv.setStack(4, uiGlint(Items.BRUSH, "§d§l🎨 Cosmetics & Tools", "§7Help GUI organized selection"));
        inv.setStack(20, uiGlint(Items.BLAZE_ROD, "§6Rainbow Rectangle Wand", "§7Face-paint wand"));
        inv.setStack(21, uiGlint(Items.WHITE_CONCRETE, "§fColor Square Wand", "§7Flat-color region"));
        inv.setStack(22, uiGlint(Items.WHITE_CARPET, "§fColor Triangle Wand", "§7Triangle region"));
        inv.setStack(24, uiGlint(Items.PAINTING, "§eSet Tab Icon", "§7Opens tab icon UI"));
        
        inv.setStack(45, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Main Menu"));
        return inv;
    }

    private static SimpleInventory buildMain(ServerPlayerEntity player, int page) {
        SimpleInventory inv = new SimpleInventory(27);
        UUID uuid = player.getUuid();
        int undoSz = UndoManager.undoSize(uuid);
        int redoSz = UndoManager.redoSize(uuid);
        int blockCount = sortedBlocks().size();
        int brokenCount = brokenBlocks().size();
        
        for(int i = 0; i < 27; i++) inv.setStack(i, ui(Items.BLACK_STAINED_GLASS_PANE, "§r"));
        
        inv.setStack(0, ui(Items.BLUE_STAINED_GLASS_PANE, "§r"));
        inv.setStack(4, uiGlint(Items.DIAMOND, "§b§lCustomBlocks", "§7Total blocks: §f"+blockCount,
            brokenCount > 0 ? "§cBroken: §f"+brokenCount : "§aAll textures OK",
            "§8Type /cb help for commands"));
        inv.setStack(8, ui(Items.BLUE_STAINED_GLASS_PANE, "§r"));
        inv.setStack(18, ui(Items.BLUE_STAINED_GLASS_PANE, "§r"));
        inv.setStack(26, ui(Items.BLUE_STAINED_GLASS_PANE, "§r"));
        
        inv.setStack(10, uiGlint(Items.CRAFTING_TABLE, "§e§lBlock Manager", "§7List, Edit, or Create Custom Blocks", "§8"+blockCount+" block(s) registered"));
        inv.setStack(12, uiGlint(Items.BRUSH, "§d§lCosmetics & Magic Items", "§7Access Wands, Colors, etc."));
        inv.setStack(14, uiGlint(Items.STRUCTURE_VOID, "§b§lServer Maintenance", "§7Manage broken textures, resource pack, etc.", brokenCount > 0 ? "§c"+brokenCount+" broken texture(s)" : "§aAll OK"));
        inv.setStack(16, uiGlint(Items.BOOK, "§a§lHelp & Info", "§7Read Interactive Help Guide"));
        
        inv.setStack(21, undoSz > 0 ? uiGlint(Items.GOLDEN_PICKAXE, "§6§l↩ UNDO §e("+undoSz+")", "§7Click to undo last action") : ui(Items.GRAY_STAINED_GLASS_PANE, "§8Undo (Empty)", ""));
        inv.setStack(22, uiGlint(Items.EMERALD, "§a§l+ Create New Block", "§7Click to create a new custom block", "§8Type an ID in chat"));
        inv.setStack(23, redoSz > 0 ? uiGlint(Items.DIAMOND_PICKAXE, "§b§l↪ REDO §3("+redoSz+")", "§7Click to redo last undone action") : ui(Items.GRAY_STAINED_GLASS_PANE, "§8Redo (Empty)", ""));

        return inv;
    }

    private static SimpleInventory buildMaintenanceMenu() {
        SimpleInventory inv = new SimpleInventory(27);
        for(int i = 0; i < 27; i++) inv.setStack(i, glass());
        inv.setStack(0, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Main Menu", "§8(or press ESC)"));
        
        inv.setStack(10, uiGlint(Items.PAINTING, "§a§lTab Icon Settings", "§7Change dynamic creative tab icon"));
        inv.setStack(12, uiGlint(Items.ANVIL, "§c§lBroken Blocks Cache", "§7Find and fix broken textures"));
        inv.setStack(14, uiGlint(Items.REDSTONE_TORCH, "§6§lResource Pack Host Config", "§7Change embedded web server port"));
        inv.setStack(16, uiGlint(Items.PAPER, "§f§lExport Data", "§7Export JSON block structure data"));

        return inv;
    }

    private static SimpleInventory buildHelpGui() {
        SimpleInventory inv = new SimpleInventory(27);
        for(int i = 0; i < 27; i++) inv.setStack(i, glass());
        inv.setStack(0, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Main Menu", "§8(or press ESC)"));
        
        inv.setStack(10, uiGlint(Items.BOOK, "§e§lGUI Navigation", 
            "§7ESC — go back one screen",
            "§7Click items to interact",
            "§7Main Menu → Sub-menus → Editors"));
        inv.setStack(11, ui(Items.WRITABLE_BOOK, "§a§lBasic Commands",
            "§7/cb gui §8— Open main menu",
            "§7/cb create <id> §8— Create block",
            "§7/cb list §8— List all blocks",
            "§7/cb help §8— Full command list"));
        inv.setStack(12, ui(Items.PAINTING, "§b§lTexture Tips",
            "§7Accepts PNG, JPG, GIF, WebP URLs",
            "§7Auto-converts to square PNG",
            "§7GIF → animated block textures!",
            "§7Discord CDN URLs work great"));
        inv.setStack(14, ui(Items.CLOCK, "§d§lAnimation Workflow",
            "§71. Upload a GIF as texture",
            "§72. Open block editor → Animation",
            "§73. Adjust FPS and interpolation",
            "§8Supports APNG and animated WebP too"));
        inv.setStack(15, ui(Items.ENDER_PEARL, "§5§lShape System",
            "§712 built-in presets",
            "§7Custom hitboxes (up to 16)",
            "§8Left-click preset = new variant",
            "§8Right-click preset = apply to block"));
        inv.setStack(16, ui(Items.GOLDEN_PICKAXE, "§6§lUndo / Redo",
            "§7All changes are undoable",
            "§7Available from Main Menu",
            "§8Undo stack persists per-player"));
        return inv;
    }

    private static SimpleInventory buildPropertiesGui(SlotData d) {
        SimpleInventory inv = new SimpleInventory(27);
        for(int i=0;i<27;i++) inv.setStack(i, glass());
        inv.setStack(0, uiGlint(Items.RED_CONCRETE,"§c◀ Back to Editor","§8(or press ESC)"));
        
        ItemStack disp = CustomBlocksMod.safeSlotItem(d.index)!=null?new ItemStack(CustomBlocksMod.safeSlotItem(d.index)):ItemStack.EMPTY;
        disp.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§6§l"+d.displayName).styled(s->s.withItalic(false)));
        disp.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            lore("§7Light: §e"+d.lightLevel),
            lore("§7Hardness: §f"+hardnessLabel(d.hardness)),
            lore("§7Collision: "+(d.noCollision?"§cOFF":"§aON"))
        )));
        inv.setStack(4, disp);
        
        inv.setStack(10, ui(Items.RED_DYE,"§c◀ Light -1","§7Now: §e"+d.lightLevel));
        inv.setStack(11, uiGlint(Items.GLOWSTONE_DUST,"§e✦ Light: §f"+d.lightLevel,"§70=off • 7=torch • 14=sea lantern • 15=max"));
        inv.setStack(12, ui(Items.YELLOW_DYE,"§a▶ Light +1","§7Now: §e"+d.lightLevel));
        
        inv.setStack(14, ui(Items.RED_DYE,"§c◀ Hardness -","§7Now: §f"+hardnessLabel(d.hardness)));
        inv.setStack(15, ui(Items.IRON_PICKAXE,"§b⚙ Hardness: §f"+hardnessLabel(d.hardness),"§7-1=Unbreakable • 0=Instant • 1.5=Default"));
        inv.setStack(16, ui(Items.LIME_DYE,"§a▶ Hardness +","§7Now: §f"+hardnessLabel(d.hardness)));

        inv.setStack(22, d.noCollision
            ? uiGlint(Items.BARRIER,"§c⊘ Collision: §lOFF","§7Block has NO hitbox","§8Click to ENABLE collision")
            : uiGlint(Items.SLIME_BLOCK,"§a✔ Collision: §lON","§7Block has normal collision","§8Click to DISABLE collision"));
        
        return inv;
    }

    private static SimpleInventory buildSoundMenu(SlotData d) {
        SimpleInventory inv = new SimpleInventory(27);
        for(int i=0;i<27;i++) inv.setStack(i, glass());
        inv.setStack(0, uiGlint(Items.RED_CONCRETE,"§c◀ Back to Editor","§8(or press ESC)"));
        
        inv.setStack(10,soundItem(d,"stone",Items.STONE,"§fStone"));
        inv.setStack(11,soundItem(d,"wood",Items.OAK_LOG,"§fWood"));
        inv.setStack(12,soundItem(d,"grass",Items.GRASS_BLOCK,"§fGrass"));
        inv.setStack(13,soundItem(d,"metal",Items.IRON_BLOCK,"§fMetal"));
        inv.setStack(14,soundItem(d,"glass",Items.GLASS,"§fGlass"));
        inv.setStack(15,soundItem(d,"sand",Items.SAND,"§fSand"));
        inv.setStack(16,soundItem(d,"gravel",Items.GRAVEL,"§fGravel"));
        return inv;
    }

    private static SimpleInventory buildPicker(int page, boolean brokenOnly) {
        SimpleInventory inv = new SimpleInventory(54);
        List<SlotData> blocks = brokenOnly ? brokenBlocks() : sortedBlocks();
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
                SlotData d = blocks.get(dataIdx);
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

    private static SimpleInventory buildEditor(SlotData d, boolean confirmDelete) {
        SimpleInventory inv = new SimpleInventory(36);
        for(int i = 0; i < 36; i++) inv.setStack(i, glass());

        inv.setStack(0, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Block List", "§8(or press ESC)"));
        inv.setStack(2, uiGlint(Items.CHEST,"§a▶ Give 1x","§7Gives 1x §f"+d.displayName+" §7to you"));
        
        ItemStack disp = CustomBlocksMod.safeSlotItem(d.index)!=null?new ItemStack(CustomBlocksMod.safeSlotItem(d.index)):ItemStack.EMPTY;
        disp.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§e§l"+d.displayName).styled(s->s.withItalic(false)));
        disp.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            lore("§7ID: §b"+d.customId),
            lore("§7Shape: §5"+d.shapeLabel()),
            lore("§7Light: §e"+d.lightLevel+"  §7Hard: §f"+hardnessLabel(d.hardness)),
            lore("§7Sound: §f"+d.soundType),
            lore("§7Collision: "+(d.noCollision?"§cOFF":"§aON")),
            lore("§8Slot #"+d.index)
        )));
        inv.setStack(4, disp);
        inv.setStack(6, d.noCollision
            ? uiGlint(Items.BARRIER,"§c⊘ Collision: §lOFF","§7Block has NO hitbox","§8Click to ENABLE collision")
            : uiGlint(Items.SLIME_BLOCK,"§a✔ Collision: §lON","§7Block has normal collision","§8Click to DISABLE collision"));
        inv.setStack(8, uiGlint(Items.MAP,"§b⬛ Retexture All Faces","§7Replace texture on ALL faces","§8Click → paste URL in chat"));
        
        inv.setStack(10, uiGlint(Items.PAINTING, "§b⬛ Texture Editing", "§7Per-face textures & variants","§8Open full face editor"));
        inv.setStack(12, uiGlint(Items.ENDER_PEARL, "§5⬡ Shape Editing", "§7Current: §b"+d.shapeLabel(),"§8Presets, custom boxes, collision"));
        inv.setStack(14, uiGlint(Items.REDSTONE, "§6⚙ Properties", "§7Light: §e"+d.lightLevel+"  §7Hard: §f"+hardnessLabel(d.hardness),"§8Adjust glow & hardness"));
        inv.setStack(16, uiGlint(Items.NOTE_BLOCK, "§e♫ Sounds", "§7Current: §f"+d.soundType,"§8Change block break/place sounds"));
        
        inv.setStack(22, d.isAnimated()
            ? uiGlint(Items.CLOCK, "§b⟳ Animation Settings", "§7This block is animated","§8Click to configure FPS/interpolation")
            : ui(Items.GRAY_DYE, "§7⟳ Animation", "§8Not animated","§7Use an animated texture (GIF) to enable"));
        
        inv.setStack(28, uiGlint(Items.NAME_TAG,"§e✎ Rename","§7Current: §f"+d.displayName,"§8Click → type in chat"));
        inv.setStack(29, uiGlint(Items.COMMAND_BLOCK,"§b⇄ Re-ID","§7Current: §b"+d.customId,"§8Click → type in chat"));
        inv.setStack(30, uiGlint(Items.COMPARATOR,"§e⧉ Duplicate","§7Creates a copy of this block","§8Click → type new ID"));
        
        inv.setStack(34, confirmDelete
            ? uiGlint(Items.BARRIER, "§4§l⚠ CLICK AGAIN TO CONFIRM DELETE","§cThis will permanently delete: §f"+d.customId,"§c(Use Undo in main menu to restore)")
            : ui(Items.TNT, "§c§l⚠ Delete This Block","§7First click arms.  Second click deletes.","§8Undo available in main menu."));

        return inv;
    }

    private static SimpleInventory buildShapeEditor(SlotData d, int boxPage) {
        SimpleInventory inv = new SimpleInventory(54);
        List<SlotData.ShapeBox> boxes = d.shapeBoxes!=null?d.shapeBoxes:List.of();
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
        for (int i=0; i<PRESET_NAMES.length && i<12; i++) {
            String p=PRESET_NAMES[i];
            List<SlotData.ShapeBox> presetBoxes = SlotManager.SHAPE_PRESETS.get(p);
            boolean act = (presetBoxes == null && !d.isShaped()) || (presetBoxes != null && presetBoxes.equals(boxes));
            inv.setStack(10+i, act?uiGlint(pItems[Math.min(i,pItems.length-1)],"§a§l"+p.toUpperCase()+" §a✔","§aActive • §8Left=new block  Right=apply here"):ui(pItems[Math.min(i,pItems.length-1)],"§b"+cap(p),"§7Preset shape","§8Left-click=new block  •  Right-click=apply here"));
        }
        inv.setStack(22, uiGlint(Items.LIME_DYE,"§a➕ Add Custom Box","§7Click → type coords","§8Format: x1,y1,z1,x2,y2,z2  (0–16)","§8Up to 16 boxes"));
        inv.setStack(23, ui(Items.ORANGE_DYE,"§6⊘ Clear All Boxes","§7Reset to full cube","§8Removes all custom shape boxes"));
        for (int i=24;i<=26;i++) inv.setStack(i,glass());
        inv.setStack(27, ui(Items.PURPLE_STAINED_GLASS_PANE,"§5── Custom Boxes §8(click = remove) ──","§7Defines the block's physical shape / hitbox"));
        int bstart = boxPage*9;
        for (int i=0;i<8&&(bstart+i)<boxes.size();i++) { SlotData.ShapeBox b=boxes.get(bstart+i); inv.setStack(28+i,ui(Items.STRUCTURE_VOID,"§e§lBox #"+(bstart+i),"§7"+b.toDisplayString(),"§8Click to remove")); }
        for (int s=28+Math.min(8,Math.max(0,boxes.size()-bstart));s<=35;s++) inv.setStack(s,glass());
        List<SlotData> variants = findShapeVariants(d.customId);
        inv.setStack(36, ui(Items.LIME_STAINED_GLASS_PANE,"§a── Shape Variants §8(click to edit) ──","§7Blocks created from this block via presets","§8"+variants.size()+" variant(s)"));
        for (int i=0;i<Math.min(8,variants.size());i++) {
            SlotData v=variants.get(i);
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

    private static SimpleInventory buildFaceEditor(SlotData d) {
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
        inv.setStack(27,ui(Items.WHITE_STAINED_GLASS_PANE,"§c✕ Clear TOP",faceStatus(d,"top")));
        inv.setStack(28,ui(Items.LIGHT_GRAY_STAINED_GLASS_PANE,"§c✕ Clear BOTTOM",faceStatus(d,"bottom")));
        inv.setStack(29,ui(Items.CYAN_STAINED_GLASS_PANE,"§c✕ Clear NORTH",faceStatus(d,"north")));
        inv.setStack(30,ui(Items.BLUE_STAINED_GLASS_PANE,"§c✕ Clear SOUTH",faceStatus(d,"south")));
        inv.setStack(31,ui(Items.PURPLE_STAINED_GLASS_PANE,"§c✕ Clear EAST",faceStatus(d,"east")));
        inv.setStack(32,ui(Items.MAGENTA_STAINED_GLASS_PANE,"§c✕ Clear WEST",faceStatus(d,"west")));
        for(int i=33;i<=44;i++) inv.setStack(i,glass());
        inv.setStack(45,uiGlint(Items.RED_CONCRETE,"§c◀ Back to Editor","§8(or press ESC)"));
        // Use a simple placeholder for undo count since we need the player UUID
        inv.setStack(46,ui(Items.GRAY_STAINED_GLASS_PANE,"§8Undo","§7Use main menu undo"));
        inv.setStack(47,ui(Items.ORANGE_CONCRETE,"§6⊘ Clear ALL Overrides","§7Reverts every face to default texture"));
        for(int i=48;i<=52;i++) inv.setStack(i,glass());
        inv.setStack(53,uiGlint(Items.CHEST,"§a▶ Give 1x","§7Gives 1x §f"+d.displayName));
        return inv;
    }

    private static SimpleInventory buildAnimGui(String id, float fps, boolean interp, int frameCount) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        SlotData d = SlotManager.getById(id);
        String blockName = d != null ? d.displayName : id;
        int ticks = Math.max(1, Math.round(20f / Math.max(0.5f, fps)));
        inv.setStack(4, uiGlint(Items.PAPER, "§e§l" + blockName,
            "§7Frames: §f" + frameCount, "§7Current FPS: §f" + String.format("%.1f", fps),
            "§7" + ticks + " tick(s)/frame  §8(20 ticks = 1 sec)", "§7Interpolate: " + (interp ? "§aON" : "§cOFF")));
        inv.setStack(9,  ui(Items.SPECTRAL_ARROW, "§c§l<< §r§c-10 fps"));
        inv.setStack(10, ui(Items.ARROW, "§c§l<  §r§c-1 fps"));
        inv.setStack(13, uiGlint(Items.CLOCK, "§e" + String.format("%.1f", fps) + " fps", "§7= " + ticks + " tick(s)/frame"));
        inv.setStack(16, ui(Items.ARROW, "§a+1 fps  §l>"));
        inv.setStack(17, ui(Items.SPECTRAL_ARROW, "§a+10 fps §l>>"));
        inv.setStack(18, ui(Items.LIME_DYE, "§a5 fps  §8— Slow"));
        inv.setStack(20, ui(Items.YELLOW_DYE, "§e10 fps §8— Normal"));
        inv.setStack(22, ui(Items.GOLD_INGOT, "§620 fps §8— Fast"));
        inv.setStack(24, ui(Items.BLAZE_ROD, "§c30 fps §8— Ultra"));
        inv.setStack(27, interp
            ? uiGlint(Items.LIME_WOOL, "§aInterpolate: ON", "§7Smooth transition between frames", "§8Click to toggle OFF")
            : ui(Items.RED_WOOL, "§cInterpolate: OFF", "§7No smoothing between frames", "§8Click to toggle ON"));
        inv.setStack(46, uiGlint(Items.EMERALD, "§a§lAPPLY SETTINGS", "§7Saves and broadcasts to all players"));
        inv.setStack(49, ui(Items.BARRIER, "§c§lCLOSE", "§7Discard changes and close"));
        return inv;
    }

    // ── Small helpers ─────────────────────────────────────────────────────────

    private static void closeForPrompt(ServerPlayerEntity player) {
        REOPENING_SCREENS.add(player.getUuid());
        player.closeHandledScreen();
        REOPENING_SCREENS.remove(player.getUuid());
    }

    private static void promptFace(ServerPlayerEntity player, String blockId, String face, int rp, boolean variant) {
        InputAction action = variant ? InputAction.SETFACE_VARIANT_URL : InputAction.SETFACE_URL;
        PENDING.put(player.getUuid(), new PendingInput(action, blockId, face, null, null, rp));
        closeForPrompt(player);
        String mode = variant ? "§b(creates variant — original untouched)" : "§a(modifies this block)";
        send(player, "§6[GUI] §ePaste URL for §f"+face.toUpperCase()+" §eof '§f"+blockId+"§e' "+mode+":");
        send(player, "§7Type §ccancel §7to abort.");
    }

    private static void clearFace(ServerPlayerEntity player, SlotData d, String face) {
        UndoManager.pushUndoMutation(d.customId, d, "clearface " + face, player.getUuid());
        SlotManager.clearFaceTexture(d.customId, face); SlotManager.saveAll();
        NetworkManager.broadcastUpdate(player.getServer(), new SlotUpdatePayload("clearface", d.index, d.customId, null, null, d.lightLevel, d.hardness, d.soundType, face));
        GuiState st = STATES.get(player.getUuid());
        if (st != null && st.mode() == GuiMode.FACE_EDITOR) openFaceEditor(player, d.customId, st.page());
        else openEditor(player, d.customId, STATES.getOrDefault(player.getUuid(), GuiState.main(0)).page());
    }

    private static void broadcastClearAllFaces(ServerPlayerEntity player, SlotData d) {
        NetworkManager.broadcastUpdate(player.getServer(), new SlotUpdatePayload("clearfaces", d.index, d.customId, null, null, d.lightLevel, d.hardness, d.soundType));
    }

    private static void broadcastShape(MinecraftServer server, SlotData d) {
        List<SlotData.ShapeBox> boxes = d.shapeBoxes;
        String data = (boxes == null || boxes.isEmpty()) ? "full" :
            boxes.stream().map(SlotData.ShapeBox::toSerialString).reduce((a,b)->a+";"+b).orElse("full");
        NetworkManager.broadcastUpdate(server, new SlotUpdatePayload("setshape", d.index, d.customId, null, null, 0, 0, "stone", null, data));
    }

    private static void setSoundQuiet(ServerPlayerEntity player, SlotData d, String sound, UUID uuid) {
        UndoManager.pushUndoMutation(d.customId, d, "setsound", uuid);
        SlotManager.setSoundType(d.customId, sound); SlotManager.saveAll();
        NetworkManager.broadcastUpdate(player.getServer(), new SlotUpdatePayload("setprop", d.index, d.customId, null, null, d.lightLevel, d.hardness, sound));
    }

    private static void syncProp(ServerPlayerEntity player, SlotData dOld) {
        SlotData d = SlotManager.getById(dOld.customId); if(d==null) return;
        SlotManager.saveAll();
        NetworkManager.broadcastUpdate(player.getServer(), new SlotUpdatePayload("setprop", d.index, d.customId, null, null, d.lightLevel, d.hardness, d.soundType));
    }

    private static List<SlotData> sortedBlocks() {
        return SlotManager.sortedSlots();
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
    private static String faceStatus(SlotData d, String f) { return d.faceTextures.containsKey(f)?"§aOverride ACTIVE — click to clear":"§8No override set"; }
    private static boolean isUrl(String s)       { return s.startsWith("http://")||s.startsWith("https://"); }
    private static void send(ServerPlayerEntity p,String m) { p.sendMessage(Text.literal(m),false); }
    private static void thread(ServerPlayerEntity p, Runnable r) { EXECUTOR.submit(r); }

    private static ItemStack soundItem(SlotData d, String sound, Item item, String label) {
        return sound.equals(d.soundType)?uiGlint(item,label+" §a✔","§aCurrently active"):ui(item,label,"§7Click to use §f"+sound+" §7sound");
    }
    private static ItemStack faceBtn(SlotData d, Item item, String face, String label) {
        boolean h=d.faceTextures.containsKey(face);
        return h?uiGlint(item,label+" §a✔","§aOverride active","§8Click to open Face Editor"):ui(item,label,"§7Default texture","§8Click to open Face Editor");
    }
    private static ItemStack clearFaceBtn(SlotData d, Item item, String face, String label) {
        boolean h=d.faceTextures.containsKey(face);
        return h?uiGlint(item,label,"§aOverride active — click to clear"):ui(item,label,"§8No override set");
    }
    static ItemStack ui(Item item, String name, String... lore) {
        ItemStack s=new ItemStack(item);
        s.set(DataComponentTypes.CUSTOM_NAME,Text.literal(name).styled(st->st.withItalic(false)));
        if(lore.length>0){List<Text> ll=new ArrayList<>();for(String l:lore)ll.add(lore(l));s.set(DataComponentTypes.LORE,new LoreComponent(ll));}
        return s;
    }
    static ItemStack uiGlint(Item item, String name, String... lore) { ItemStack s=ui(item,name,lore); s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,true); return s; }
    static Text lore(String t) { return Text.literal(t).styled(s->s.withItalic(false)); }
    static ItemStack glass()   { return ui(Items.GRAY_STAINED_GLASS_PANE,"§r"); }
}
