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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks");
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
    
    private static int errorCount = 0;
    public static void logError() { errorCount++; }

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
        PENDING.remove(uuid); 
        GuiState state = STATES.get(uuid);
        if (state == null) return;

        Deque<GuiState> stack = BACK_STACK.get(uuid);
        if (stack != null && !stack.isEmpty()) {
            GuiState prev = stack.pop();
            // Implement "Back once, then exit entirely":
            // We pop the previous menu, then clear the stack so the next ESC closes.
            stack.clear();
            restoreState(player, prev);
        } else {
            // At root or after one back-step - fully close
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
            Text.literal("§b§l✦ §r§fMain Dashboard")));
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
            Text.literal("§b§l▶ §r§fChoose a block")));
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
            Text.literal("§e§l✎ §r§fBlock Design Studio §8— " + d.displayName)));
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
            Text.literal("§d§l⬡ §r§fFace Mapping Forge §8— " + d.displayName)));
    }

    public static void openShapeEditor(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.shapeEditor(id, returnPage));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildShapeEditor(d, 0)),
            Text.literal("§5§l⬡ §r§fShape Sculptor §8— " + d.displayName)));
    }

    public static void openMaintenanceMenu(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.maintenance());
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildMaintenanceMenu(player)),
            Text.literal("§b§l✦ §r§fServer Maintenance")));
    }

    private static SimpleInventory buildResourceCenter(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        String ip = com.customblocks.network.ResourcePackServer.getExternalIp();
        int activePort = com.customblocks.network.ResourcePackServer.isRunning() ? com.customblocks.network.ResourcePackServer.activePort() : -1;
        String statusText = activePort > 0 ? "§aREADY" : "§cOFFLINE - Port Conflict";
        String statusColor = activePort > 0 ? "§a" : "§c";
        
        String url = activePort > 0 ? "http://" + ip + ":" + activePort + "/pack.zip" : "§7[Locked]";

        inv.setStack(4, uiGlint(Items.BEACON, "§b§lResource Hub",
            "§7Manage your texture distribution system.",
            "§8Mod Status: " + statusColor + (activePort > 0 ? "Working Perfectly" : "Action Required")));

        inv.setStack(20, uiGlint(activePort > 0 ? Items.REPEATER : Items.LEVER, "§e§lSystem Port",
            "§7The 'Door' used for data: §f" + (activePort > 0 ? activePort : "Blocked"),
            "§7(Standard: 8080)",
            "",
            "§b• Tip: §7If blocked, the mod will",
            "§7auto-choose a different port.",
            "§e§oStatus: " + statusText));

        inv.setStack(22, ui(Items.COMPASS, "§f§lYour Texture Link",
            "§7Friends need this link to see your textures.",
            "§bClick to get link in chat.",
            "",
            "§8Status: " + (activePort > 0 ? "§aOnline & Broadcasting" : "§cStopped")));

        inv.setStack(24, uiGlint(Items.NETHER_STAR, "§a§lSYNC ALL",
            "§7Force update textures for all players.",
            "§7Use this after changing block images.",
            "",
            "§e§l▶ CLICK TO SYNC EVERYONE"));

        inv.setStack(45, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Settings"));
        return inv;
    }


    public static void openHelpGui(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.help());
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildHelpGui()),
            Text.literal("§a§l✦ §r§fCommand Hub & Reference")));
    }

    public static void openPropertiesGui(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.properties(id, returnPage));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildPropertiesGui(d)),
            Text.literal("§6§l⚙ §r§fEngine Properties §8— " + d.displayName)));
    }

    public static void openSoundMenu(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.sound(id, returnPage));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildSoundMenu(d)),
            Text.literal("§e§l♫ §r§fAcoustic Tuner §8— " + d.displayName)));
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

    public static void openResourceCenter(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.resourceCenter());
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildResourceCenter(player)),
            Text.literal("§b§l✦ §r§fResource Hub")));
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
            Text.literal("§6§l✦ §r§fBroken Texture Fixer")));
    }

    public static List<SlotData> brokenBlocks() {
        return SlotManager.brokenBlocks();
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
                case RESOURCE_CENTER -> openResourceCenter(player);
                case ANIM_GUI -> openAnimGui(player, state.editingId());
                default -> openMain(player, 0);
            }
        } finally {
            RESTORING.remove(player.getUuid());
        }
    }

    // ── Click dispatch ───────────────────────────────────────────────────────

    public static void handleClick(ServerPlayerEntity player, int slot, int button) {
        GuiState state = null;
        try {
            playClick(player);
            state = STATES.get(player.getUuid());
            if (state == null) return;
            switch (state.mode()) {
                case MAIN         -> handleMainClick(player, state, slot);
                case PICKER       -> handlePickerClick(player, state, slot, false);
                case PICKER_BROKEN-> handlePickerClick(player, state, slot, true);
                case TAB_ICON_MENU-> handleTabIconMenuClick(player, state, slot);
                case RESOURCE_CENTER -> handleResourceCenterClick(player, state, slot);
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
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] GUI Command Error: {}", e.getMessage(), e);
            playError(player);
            send(player, "§c[GUI Error] A logic fault occurred. Resetting...");
            openMain(player, state != null ? state.page() : 0);
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
                send(player, "§e[CB] Downloading '" + name + "'…");
                MinecraftServer srv = player.getServer();
                thread(player, () -> { try {
                    byte[] raw = ImageProcessor.download(text);
                    ImageProcessor.GifResult gif = ImageProcessor.isAnimatedGif(raw) ? ImageProcessor.processGif(raw) : null;
                    byte[] bytes; String anim = null;
                    if (gif != null) { bytes = gif.stripPng(); anim = gif.mcmeta(); }
                    else { bytes = ImageProcessor.toPng(raw); bytes = ImageProcessor.padToSquare(bytes); bytes = ImageProcessor.replaceBackground(bytes); bytes = ImageProcessor.resizeTo(bytes, CustomBlocksConfig.defaultTextureSize); }
                    final byte[] fb = bytes; final String fa = anim;
                    srv.execute(() -> {
                        if (SlotManager.hasId(id)) { playError(player); send(player, "§c'" + id + "' already exists."); openMain(player, rp); return; }
                        SlotData d = SlotManager.assign(id, name, fb);
                        if (d == null) { playError(player); send(player, "§cNo free slots!"); openMain(player, rp); return; }
                        if (fa != null) SlotManager.setAnimMeta(id, fa);
                        UndoManager.pushUndoCreate(id, player.getUuid()); SlotManager.saveAll();
                        SlotData updated = SlotManager.getById(id);
                        playSuccess(player);
                        NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("add", d.index, id, name, fb, d.lightLevel, d.hardness, d.soundType, null, null, updated != null ? updated.animMeta : fa));
                        ChatHelper.success(player, "Created '§f" + name + "§a'! §7(slot #" + d.index + ")");
                        openEditor(player, id, rp);
                    });
                } catch (Exception e) { srv.execute(() -> { playError(player); send(player, "§c[GUI] Failed: " + e.getMessage()); openMain(player, rp); }); } });
                return true;
            }
            case RETEXTURE_URL -> {
                if (!isUrl(text)) { playError(player); send(player, "§cNeeds a URL."); openEditor(player, blockId, rp); return true; }
                send(player, "§e[CB] Downloading texture…");
                MinecraftServer srv = player.getServer();
                thread(player, () -> { try {
                    ImageProcessor.ProcessResult result = ImageProcessor.downloadAndProcess(text, CustomBlocksConfig.defaultTextureSize);
                    srv.execute(() -> {
                        SlotData d = SlotManager.getById(blockId);
                        if (d == null) { openMain(player, rp); return; }
                        UndoManager.pushUndoMutation(blockId, d, "retexture", player.getUuid());
                        SlotManager.updateTexture(blockId, result.bytes());
                        SlotManager.setAnimMeta(blockId, result.mcmeta());
                        SlotManager.saveAll();
                        playSuccess(player);
                        NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("retexture", d.index, blockId, null, result.bytes(), d.lightLevel, d.hardness, d.soundType, null, null, result.mcmeta()));
                        ChatHelper.success(player, "Texture updated! " + (result.isAnimated() ? "§b(Animated)" : "§7(Static)"));
                        openEditor(player, blockId, rp);
                    });
                } catch (Exception e) { srv.execute(() -> { playError(player); send(player, "§c[GUI] Failed: " + e.getMessage()); openEditor(player, blockId, rp); }); } });
                return true;
            }
            case SETFACE_URL -> {
                if (!isUrl(text)) { send(player, "§cNeeds a URL."); openFaceEditor(player, blockId, rp); return true; }
                String face = pending.face();
                send(player, "§e[CB] Downloading " + face + " face…");
                MinecraftServer srv = player.getServer();
                thread(player, () -> { try {
                    ImageProcessor.ProcessResult result = ImageProcessor.downloadAndProcess(text, CustomBlocksConfig.defaultTextureSize);
                    srv.execute(() -> {
                        SlotData d = SlotManager.getById(blockId);
                        if (d == null) { openMain(player, rp); return; }
                        UndoManager.pushUndoMutation(blockId, d, "setface " + face, player.getUuid());
                        SlotManager.setFaceTexture(blockId, face, result.bytes());
                        SlotManager.saveAll();
                        playSuccess(player);
                        NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("setface", d.index, blockId, null, result.bytes(), d.lightLevel, d.hardness, d.soundType, face));
                        send(player, "§a[CB] §f" + face.toUpperCase() + " §aface set on '§f" + blockId + "§a'.");
                        openFaceEditor(player, blockId, rp);
                    });
                } catch (Exception e) { srv.execute(() -> { playError(player); send(player, "§c[GUI] Failed: " + e.getMessage()); openFaceEditor(player, blockId, rp); }); } });
                return true;
            }
            case SETFACE_VARIANT_URL -> {
                if (!isUrl(text)) { send(player, "§cNeeds a URL."); openFaceEditor(player, blockId, rp); return true; }
                String face = pending.face();
                SlotData orig = SlotManager.getById(blockId);
                if (orig == null) { openMain(player, rp); return true; }
                send(player, "§e[CB] Creating variant with " + face + " face…");
                MinecraftServer srv = player.getServer();
                thread(player, () -> { try {
                    ImageProcessor.ProcessResult result = ImageProcessor.downloadAndProcess(text, CustomBlocksConfig.defaultTextureSize);
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
                        SlotManager.setFaceTexture(varId, face, result.bytes());
                        UndoManager.pushUndoCreate(varId, player.getUuid()); SlotManager.saveAll();
                        SlotData fresh = SlotManager.getById(varId);
                        if (fresh != null) {
                            NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("add", fresh.index, varId, varName, texCopy, fresh.lightLevel, fresh.hardness, fresh.soundType, null, null, fresh.animMeta));
                            for (var fe : fresh.faceTextures.entrySet())
                                NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("setface", fresh.index, varId, null, fe.getValue(), fresh.lightLevel, fresh.hardness, fresh.soundType, fe.getKey()));
                        }
                        player.getInventory().insertStack(nb.index < CustomBlocksMod.SLOT_ITEMS.length && CustomBlocksMod.SLOT_ITEMS[nb.index] != null ? new ItemStack(CustomBlocksMod.SLOT_ITEMS[nb.index], 1) : ItemStack.EMPTY);
                        send(player, "§a[CB] Variant '§f" + varId + "§a' created & given!");
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
                send(player, "§a[CB] Renamed to '§f" + text + "§a'.");
                openEditor(player, blockId, rp); return true;
            }
            case SETTABICON_URL -> {
                if ("cancel".equalsIgnoreCase(text)) { openMain(player, rp); return true; }
                String targetId = text.toLowerCase().trim();
                boolean isBlock = SlotManager.hasId(targetId);
                if (!isUrl(text) && !isBlock) { send(player, "§cNeeds a URL or Block ID."); openMain(player, rp); return true; }
                send(player, "§e[CB] Processing tab icon…");
                MinecraftServer srv = player.getServer();
                thread(player, () -> { try {
                    byte[] finalBytes;
                    if (isBlock) {
                        SlotData dd = SlotManager.getById(targetId);
                        if (dd.texture != null) finalBytes = dd.texture.clone();
                        else throw new Exception("Block has no texture");
                    } else { finalBytes = ImageProcessor.downloadAndProcess(text).bytes(); }
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
                        send(player, "§a[CB] Tab icon updated!");
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
                    send(player, "§a[Physics] Box added! Total: §f" + (d.shapeBoxes != null ? d.shapeBoxes.size() : 0));
                } catch (Exception e) { send(player, "§cBad coords. Use: x1,y1,z1,x2,y2,z2 (0–16)"); }
                openShapeEditor(player, blockId, rp); return true;
            }
            case REID_TEXT -> {
                if ("__givesquare__".equals(blockId)) {
                    String col = text.toLowerCase().trim();
                    if (!List.of("black","yellow","green").contains(col)) { send(player, "§cChoose: §fblack §7| §fyellow §7| §fgreen"); openMain(player, rp); return true; }
                    Item it = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, col + "_square"));
                    if (it != null && it != Items.AIR) player.getInventory().insertStack(new ItemStack(it, 1));
                    send(player, "§a[CB] Given §f" + col + " Square§a!"); openMain(player, rp); return true;
                }
                if ("__givetriangle__".equals(blockId)) {
                    String col = text.toLowerCase().trim();
                    if (!List.of("black","yellow","green").contains(col)) { send(player, "§cChoose: §fblack §7| §fyellow §7| §fgreen"); openMain(player, rp); return true; }
                    Item it = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, col + "_triangle"));
                    if (it != null && it != Items.AIR) player.getInventory().insertStack(new ItemStack(it, 1));
                    send(player, "§a[CB] Given §f" + col + " Triangle§a!"); openMain(player, rp); return true;
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
                send(player, "§a[CB] Re-ID'd '§f" + blockId + "§a' → '§f" + newId + "§a'.");
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

    private static void handleResourceCenterClick(ServerPlayerEntity player, GuiState state, int slot) {
        playClick(player);
        switch (slot) {
            case 0, 45 -> openMaintenanceMenu(player);
            case 20 -> { // Change Port
                int nextPort = CustomBlocksConfig.resourcePackPort == 8080 ? 24454 : 8080;
                CustomBlocksConfig.resourcePackPort = nextPort;
                CustomBlocksConfig.save();
                com.customblocks.network.ResourcePackServer.stop();
                com.customblocks.network.ResourcePackServer.start();
                send(player, "§a[Pipeline] System Port changed to " + nextPort + ". Rebooting...");
                openResourceCenter(player);
            }
            case 22 -> { // Share Address (Compass)
                player.closeHandledScreen();
                String ip = com.customblocks.network.ResourcePackServer.getExternalIp();
                int port = com.customblocks.network.ResourcePackServer.activePort();
                if (port <= 0) {
                    ChatHelper.error(player, "The bridge is broken! Open a port first.");
                    return;
                }
                String url = "http://" + ip + ":" + port + "/pack.zip";
                
                player.sendMessage(Text.literal("§b§l✦ §r§eYour Texture Link: ")
                    .append(Text.literal("§b§n" + url)
                        .styled(s -> s.withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.OPEN_URL, url))
                                     .withHoverEvent(new net.minecraft.text.HoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT, Text.literal("§eClick to copy/open"))))), false);
                
                ChatHelper.success(player, "Link sent to your chat. Copy and send it to friends!");
            }
            case 24 -> { // Force Sync (Push)
                player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb rp push");
                openResourceCenter(player);
            }
        }
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
            
            // Fixed mappings: match buildEditor slots (19, 21, 23, 25)
            case 19 -> openFaceEditor(player, id, rp);
            case 21 -> openShapeEditor(player, id, rp);
            case 23 -> openPropertiesGui(player, id, rp);
            case 25 -> openSoundMenu(player, id, rp);
            case 31 -> { if (d.isAnimated()) openAnimGui(player, id); }
            
            case 37 -> { PENDING.put(uuid,new PendingInput(InputAction.RENAME_TEXT,id,null,null,null,rp)); closeForPrompt(player); send(player,"§6[GUI] §eType new name for '§f"+id+"§e' (or §ccancel§e):"); }
            case 39 -> { PENDING.put(uuid,new PendingInput(InputAction.REID_TEXT,id,null,null,null,rp)); closeForPrompt(player); send(player,"§6[GUI] §eType new ID for '§f"+id+"§e' (a-z 0-9 _ -) (or §ccancel§e):"); }
            case 41 -> { PENDING.put(uuid,new PendingInput(InputAction.CREATE_ID,id,null,null,null,rp)); closeForPrompt(player); send(player,"§6[GUI] §eType new ID to duplicate '§f"+id+"§e' into (or §ccancel§e):"); }
            case 53 -> {
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
        String id = state.editingId(); int rp = state.page();
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, rp); return; }
        playClick(player);

        if (slot == 45) { openEditor(player, id, rp); return; }

        // Map slots to presets
        String preset = switch(slot) {
            case 10 -> "full";
            case 11 -> "slab";
            case 12 -> "thin";
            case 13 -> "carpet";
            case 19 -> "pillar";
            case 20 -> "small";
            case 21 -> "micro";
            case 22 -> "pane";
            case 28 -> "trapdoor";
            case 29 -> "fence";
            case 30 -> "stairs";
            case 31 -> "cross";
            default -> null;
        };

        if (preset != null) {
            java.util.UUID uuid = player.getUuid();
            UndoManager.pushUndoMutation(id, d, "setshape", uuid);
            SlotManager.setShape(id, com.customblocks.core.SlotManager.SHAPE_PRESETS.get(preset));
            SlotManager.saveAll();
            NetworkManager.broadcastUpdate(player.getServer(), new SlotUpdatePayload("setshape", d.index, id, null, null, 0, 0, "stone", null, preset));
            send(player, "§a[GUI] Shape set to §f" + preset);
            openShapeEditor(player, id, rp);
        }
    }

    private static void handleMaintenanceClick(ServerPlayerEntity player, GuiState state, int slot) {
        if(slot == 0) { openMain(player, 0); return; }
        if(slot == 10) openTabIconPicker(player, 0);
        else if(slot == 12) openBrokenBlocks(player, 0);
        else if(slot == 14) openResourceCenter(player);
        else if(slot == 16) { player.closeHandledScreen(); player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb export"); }
        else if(slot == 22) {
            // Friend Test — fetch external IP and display shareable URL
            if (!com.customblocks.network.ResourcePackServer.isRunning()) {
                ChatHelper.error(player, "HTTP server is not running. Set a port > 0 first.");
                return;
            }
            ChatHelper.info(player, "Detecting your public IP address…");
            MinecraftServer srv = player.getServer();
            EXECUTOR.submit(() -> {
                String ip = com.customblocks.network.ResourcePackServer.getExternalIp();
                int port = com.customblocks.network.ResourcePackServer.getPort();
                String url = "http://" + ip + ":" + port + "/pack.zip";
                srv.execute(() -> {
                    ChatHelper.success(player, "Your shareable pack URL:");
                    player.sendMessage(net.minecraft.text.Text.literal("§b§n" + url), false);
                    if ("127.0.0.1".equals(ip)) {
                        ChatHelper.warn(player, "Could not detect public IP — ensure port " + port + " is forwarded!");
                    } else {
                        ChatHelper.success(player, "Friends can connect if port §f" + port + "§a is forwarded on your router.");
                    }
                });
            });
        }
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
            case 0  -> { openEditor(player, id, state.page()); return; }
            case 19 -> { fps = Math.max(0.5f, fps - 5); playClick(player); }
            case 20 -> { fps = Math.max(0.5f, fps - 1); playClick(player); }
            case 24 -> { fps = Math.min(60f,  fps + 1); playClick(player); }
            case 25 -> { fps = Math.min(60f,  fps + 5); playClick(player); }
            case 28 -> { fps = 5f; playClick(player); }
            case 29 -> { fps = 10f; playClick(player); }
            case 30 -> { fps = 20f; playClick(player); }
            case 31 -> { fps = 40f; playClick(player); }
            case 40 -> { interp = !interp; playClick(player); }
            case 45 -> { openEditor(player, id, state.page()); return; }
            case 49 -> { applyAnimSettings(player, id, fps, interp, frames); ANIM_PARAMS.remove(player.getUuid()); openEditor(player, id, state.page()); return; }
            default -> { return; }
        }
        fps = Math.round(fps * 10f) / 10f;
        ANIM_PARAMS.put(player.getUuid(), new AnimParams(fps, interp, frames));
        refreshScreen(player, buildAnimGui(id, fps, interp, frames));
    }

    private static void applyAnimSettings(ServerPlayerEntity player, String id, float fps, boolean interp, int frameCount) {
        if (!SlotManager.hasId(id)) { playError(player); return; }
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
        UndoManager.pushUndoMutation(id, SlotManager.getById(id), "animsettings", player.getUuid());
        SlotManager.setAnimMeta(id, newMeta);
        SlotManager.saveAll();
        SlotData d = SlotManager.getById(id);
        if (d == null) { playError(player); return; }
        
        playSuccess(player);
        SlotUpdatePayload pkt = new SlotUpdatePayload("animsettings", d.index, id, d.displayName,
                null, d.lightLevel, d.hardness, d.soundType, null, null, newMeta);
        NetworkManager.broadcastUpdate(player.getServer(), pkt);
        NetworkManager.broadcastFullSync(player.getServer()); // The Golden Sync
        ChatHelper.success(player, "The Temporal Flux of '§f" + d.displayName + "§a' has been stabilized! (" + String.format("%.1f", fps) + " fps)");
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

    private static SimpleInventory buildMaintenanceMenu(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(54);
        for(int i = 0; i < 54; i++) inv.setStack(i, glass());
        inv.setStack(0, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Main Menu", "§8Exit settings"));

        MinecraftServer server = player.getServer();
        long maxMem = Runtime.getRuntime().maxMemory() / 1024 / 1024;
        long usedMem = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
        double mspt = server == null ? 0 : server.getAverageTickTime();
        double tps = server == null ? 20.0 : Math.min(20.0, 1000.0 / Math.max(0.1, mspt));
        int players = server == null ? 0 : server.getPlayerManager().getCurrentPlayerCount();

        // ── Row 1: The Status Dashboard ──────────────────────────────────────
        inv.setStack(4, uiGlint(Items.KNOWLEDGE_BOOK, "§b§lServer Performance",
            "§7Avg Tick: §f" + String.format("%.1f", mspt) + "ms",
            "§7TPS: §a" + String.format("%.1f", tps) + " §2/ 20.0",
            "§7Memory: §f" + usedMem + "§8/§7" + maxMem + "MB",
            "§7Players: §f" + players + " §7Online"));

        // ── Row 2: Tools ──────────────────────────────────────────────────────
        inv.setStack(19, uiGlint(Items.PAINTING, "§a§lTab Icon Settings", "§7Change your main creative tab icon"));
        inv.setStack(21, uiGlint(Items.DAMAGED_ANVIL, "§c§lBroken Texture Fixer", "§7Scan and fix blocks with missing images."));
        inv.setStack(23, uiGlint(Items.BEACON, "§b§lResource Hub", "§7Manage texture delivery & ports."));
        inv.setStack(25, uiGlint(Items.PAPER, "§f§lExport Data", "§7Save your block data to a JSON file."));

        // ── Row 3: Slot Usage & Network ──────────────────────────────────────
        int used = SlotManager.usedSlots();
        int total = com.customblocks.CustomBlocksConfig.maxSlots;
        inv.setStack(31, ui(Items.CHEST, "§e§lBlock Slots", "§7Used: §f" + used + " §7/ §f" + total, "§7Free: §a" + (total - used)));

        boolean httpUp = com.customblocks.network.ResourcePackServer.isRunning();
        if (httpUp) {
            inv.setStack(33, uiGlint(Items.ENDER_EYE, "§a§l✔ Mod Status: WORKING",
                "§7The texture system is healthy.",
                "§bClick to manage settings."));
        } else {
            inv.setStack(33, ui(Items.BARRIER, "§c§l✖ Mod Status: OFFLINE", "§7The texture system is disconnected.", "§bClick to enable in Resource Hub"));
        }

        inv.setStack(40, ui(Items.SPYGLASS, "§b§lMod Version Info", "§7CustomBlocks §fv1.0.0", "§7Fabric §f1.21.1", "§8All systems: Working Perfectly"));

        inv.setStack(45, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Main Menu"));
        return inv;
    }

    private static SimpleInventory buildHelpGui() {
        SimpleInventory inv = new SimpleInventory(54);
        for(int i = 0; i < 54; i++) inv.setStack(i, glass());
        inv.setStack(0, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Main Menu", "§8Return to the dashboard"));
        
        inv.setStack(4, uiGlint(Items.ENCHANTED_BOOK, "§a§lInteractive Command Hub", 
            "§7Master the CustomBlocks language", 
            "§f\"A guide for the master architect\"",
            "§b• Tip: §7Most commands can be clicked here!"));

        // ── Category: Creation & Identification ─────────────────────────────
        inv.setStack(10, uiGlint(Items.WRITABLE_BOOK, "§e§l1. Creation Protocol", "§7How to bring new blocks to life"));
        inv.setStack(19, uiGlint(Items.CRAFTING_TABLE, "§eCreate a Block", "§7/cb create <id> <name> <url>", "§b• Tip: §7Click this to start creation in chat."));
        inv.setStack(20, uiGlint(Items.NAME_TAG, "§eRename Block", "§7/cb rename <id> <new name>", "§b• Tip: §7Use underscores _ for spaces."));
        inv.setStack(21, uiGlint(Items.COMMAND_BLOCK, "§eRe-ID Block", "§7/cb reid <old_id> <new_id>", "§b• Tip: §7This updates all placements instantly."));

        // ── Category: Design & Animation ───────────────────────────────────
        inv.setStack(13, uiGlint(Items.PAINTING, "§b§l2. Design & Motion", "§7Aesthetics and temporal flow"));
        inv.setStack(22, uiGlint(Items.MAP, "§bChange Texture", "§7/cb retexture <id> <url>", "§b• Tip: §7GIFs are automatically animated!"));
        inv.setStack(23, uiGlint(Items.AMETHYST_SHARD, "§bFace Mapping", "§7/cb setface <id> <face> <url>", "§b• Tip: §7Customize faces: north, south, etc."));
        inv.setStack(24, uiGlint(Items.CLOCK, "§bAnimation Settings", "§7Access via Design Studio", "§b• Tip: §7Adjust FPS and frame interpolation."));

        // ── Category: Physics & Space ──────────────────────────────────────
        inv.setStack(16, uiGlint(Items.ENDER_EYE, "§5§l3. Physics & Hitboxes", "§7Shape and physical interaction"));
        inv.setStack(25, uiGlint(Items.STICK, "§5Modify Hitboxes", "§7/cb addshape <id> <coords>", "§b• Tip: §7Defines where players can walk."));
        inv.setStack(26, uiGlint(Items.BARRIER, "§5Toggle Collision", "§7/cb sethardness <id> 0", "§b• Tip: §7Make blocks pass-through or solid."));

        // ── Category: Utilities & Restoration ──────────────────────────────
        inv.setStack(37, uiGlint(Items.GOLDEN_PICKAXE, "§6Undo Progress", "§7/cb undo", "§b• Tip: §7Restores up to 20 recent steps."));
        inv.setStack(38, uiGlint(Items.DIAMOND_PICKAXE, "§6Redo Change", "§7/cb redo", "§b• Tip: §7Restores an undone change."));
        inv.setStack(39, uiGlint(Items.RECOVERY_COMPASS, "§6Integrity Scan", "§7/cb showbrokenblocks", "§b• Tip: §7Lists all blocks with missing textures."));
        inv.setStack(40, uiGlint(Items.REPEATER, "§6Mod Sync", "§7/cb reload", "§b• Tip: §7Force-syncs data to all players."));

        // ── Decorative Footer ──────────────────────────────────────────────
        inv.setStack(49, ui(Items.KNOWLEDGE_BOOK, "§a§lThe Architect's Handbook", 
            "§71. Upload high-res URLs for best quality.",
            "§72. Use §fDesign Studio §7for fine-tuning.",
            "§73. Keep your §fUnique IDs §7organized."));

        inv.setStack(45, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Main Menu"));
        return inv;
    }

    private static SimpleInventory buildPropertiesGui(SlotData d) {
        SimpleInventory inv = new SimpleInventory(54);
        for(int i=0;i<54;i++) inv.setStack(i, glass());
        inv.setStack(0, uiGlint(Items.RED_CONCRETE,"§c◀ Back to Studio","§8Return to the block editor"));
        
        ItemStack disp = CustomBlocksMod.safeSlotItem(d.index)!=null?new ItemStack(CustomBlocksMod.safeSlotItem(d.index)):ItemStack.EMPTY;
        disp.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§6§l"+d.displayName).styled(s->s.withItalic(false)));
        disp.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            lore("§7Light Level: §e"+d.lightLevel),
            lore("§7Hardness: §f"+hardnessLabel(d.hardness)),
            lore("§7Collision: "+(d.noCollision?"§cOFF":"§aON"))
        )));
        inv.setStack(4, disp);
        
        inv.setStack(19, ui(Items.QUARTZ,"§c◀ Decrease Glow §8(-1)","§7Current: §e"+d.lightLevel, "§b• Tip: §7Light level 15 is max brightness"));
        inv.setStack(20, uiGlint(Items.AMETHYST_CLUSTER,"§e✦ Light: §f"+d.lightLevel,"§70=off • 7=torch • 15=max", "§b• Tip: §7Matches Minecraft vanilla light values"));
        inv.setStack(21, ui(Items.GLOWSTONE_DUST,"§a▶ Increase Glow §8(+1)","§7Current: §e"+d.lightLevel));
        
        inv.setStack(23, ui(Items.FLINT,"§c◀ Softer §8(-)","§7Current: §f"+hardnessLabel(d.hardness), "§b• Tip: §7Hardness 0 breaks instantly"));
        inv.setStack(24, uiGlint(Items.NETHERITE_INGOT,"§b⚙ Hardness: §f"+hardnessLabel(d.hardness),"§7-1=Bedrock • 0=Instant • 1.5=Stone", "§b• Tip: §7Determines how fast players mine this"));
        inv.setStack(25, ui(Items.NETHERITE_SCRAP,"§a▶ Harder §8(+)","§7Current: §f"+hardnessLabel(d.hardness)));

        inv.setStack(40, d.noCollision
            ? uiGlint(Items.BARRIER,"§c⊘ Collision: §lOFF","§7Players can pass THROUGH this block","§8Click to §aENABLE §8hitbox")
            : uiGlint(Items.SLIME_BLOCK,"§a✔ Collision: §lON","§7Block acts as a solid object","§8Click to §cDISABLE §8hitbox"));
        
        inv.setStack(45, uiGlint(Items.RED_CONCRETE,"§c◀ Back to Studio"));
        return inv;
    }

    private static SimpleInventory buildSoundMenu(SlotData d) {
        SimpleInventory inv = new SimpleInventory(54);
        for(int i=0;i<54;i++) inv.setStack(i, glass());
        inv.setStack(0, uiGlint(Items.RED_CONCRETE,"§c◀ Back to Studio","§8Return to the block editor"));
        
        inv.setStack(19,soundItem(d,"stone",Items.STONE,"§fStone"));
        inv.setStack(20,soundItem(d,"wood",Items.OAK_LOG,"§fWood"));
        inv.setStack(21,soundItem(d,"grass",Items.GRASS_BLOCK,"§fGrass"));
        inv.setStack(22,soundItem(d,"metal",Items.IRON_BLOCK,"§fMetal"));
        inv.setStack(23,soundItem(d,"glass",Items.GLASS,"§fGlass"));
        inv.setStack(24,soundItem(d,"sand",Items.SAND,"§fSand"));
        inv.setStack(25,soundItem(d,"gravel",Items.GRAVEL,"§fGravel"));

        inv.setStack(31, ui(Items.NOTE_BLOCK, "§e§lAcoustic Profile", "§7Block: §f"+d.displayName, "§7Current Sound: §b"+d.soundType.toUpperCase(), "§b• Tip: §7This affects place, break, and step sounds."));

        inv.setStack(45, uiGlint(Items.RED_CONCRETE,"§c◀ Back to Studio"));
        return inv;
    }

    private static SimpleInventory buildPicker(int page, boolean brokenOnly) {
        SimpleInventory inv = new SimpleInventory(54);
        List<SlotData> blocks = brokenOnly ? brokenBlocks() : sortedBlocks();
        int total = blocks.size(), maxPage = total==0?0:Math.max(0,(total-1)/BLOCKS_PER_PAGE);
        inv.setStack(0, uiGlint(Items.RED_CONCRETE,"§c◀ Back to Main Dashboard","§8Return to the main menu"));
        for (int i=1;i<=3;i++) inv.setStack(i,glass());
        inv.setStack(4, ui(Items.ENCHANTED_BOOK,"§e§lSelect Block to Manage",
            "§7Manage your creations from the list below",
            "§8"+Math.min(BLOCKS_PER_PAGE,Math.max(0,total-page*BLOCKS_PER_PAGE))+" of §f"+total+" §8blocks  •  Page §f"+(page+1)+"§8/§f"+(maxPage+1),
            "§b• Tip: §7Use the arrows at the bottom to flip pages"));
        for (int i=5;i<=8;i++) inv.setStack(i,glass());
        for (int i=9;i<=17;i++) inv.setStack(i, ui(Items.BLUE_STAINED_GLASS_PANE,"§r"));
        int start = page * BLOCKS_PER_PAGE;
        for (int i=0; i<BLOCKS_PER_PAGE; i++) {
            int invSlot = 18+i, dataIdx = start+i;
            if (dataIdx < blocks.size()) {
                SlotData d = blocks.get(dataIdx);
                ItemStack s = CustomBlocksMod.safeSlotItem(d.index)!=null ? new ItemStack(CustomBlocksMod.safeSlotItem(d.index)) : ItemStack.EMPTY;
                s.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§f§l"+d.displayName).styled(st->st.withItalic(false)));
                List<String> ll = new ArrayList<>(List.of("§7Unique ID: §b"+d.customId,"§7Shape: §5"+d.shapeLabel()+" §8• §7Light: §e"+d.lightLevel,"§7Acoustics: §f"+d.soundType,"§b• Tip: §7Click to enter the Design Studio"));
                List<String> tags=new ArrayList<>(); if(d.hasFaces())tags.add("§d⬡faces"); if(d.isAnimated())tags.add("§b⟳anim"); if(d.noCollision)tags.add("§c⊘hitbox"); if(!tags.isEmpty())ll.add(String.join("  ",tags));
                s.set(DataComponentTypes.LORE, new LoreComponent(ll.stream().map(l->(Text)lore(l)).toList()));
                inv.setStack(invSlot, s);
            } else { inv.setStack(invSlot, glass()); }
        }
        for (int i=36;i<=44;i++) inv.setStack(i, ui(Items.BLUE_STAINED_GLASS_PANE,"§r"));
        inv.setStack(45, page>0 ? uiGlint(Items.ARROW,"§7◀ Previous Page","§8Go to page "+page) : ui(Items.GRAY_STAINED_GLASS_PANE,"§8◀ First Page",""));
        for (int i=46;i<=48;i++) inv.setStack(i,glass());
        inv.setStack(49, ui(Items.PAPER,"§ePage §f"+(page+1)+" §7/ §f"+(maxPage+1),"§7Total: §f"+total+" blocks"));
        for (int i=50;i<=52;i++) inv.setStack(i,glass());
        inv.setStack(53, page<maxPage ? uiGlint(Items.ARROW,"§7Next Page ▶","§8Go to page "+(page+2)) : ui(Items.GRAY_STAINED_GLASS_PANE,"§8Last Page ▶",""));
        return inv;
    }

    private static SimpleInventory buildEditor(SlotData d, boolean confirmDelete) {
        SimpleInventory inv = new SimpleInventory(54);
        for(int i = 0; i < 54; i++) inv.setStack(i, glass());

        inv.setStack(0, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Block List", "§8Return to the selection grid"));
        inv.setStack(2, uiGlint(Items.CHEST,"§a▶ Give 1x","§7Gives 1x §f"+d.displayName+" §7to you", "§b• Tip: §7Puts the block directly in your hotbar"));
        
        ItemStack disp = CustomBlocksMod.safeSlotItem(d.index)!=null?new ItemStack(CustomBlocksMod.safeSlotItem(d.index)):ItemStack.EMPTY;
        disp.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§e§l"+d.displayName).styled(s->s.withItalic(false)));
        disp.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            lore("§7Unique ID: §b"+d.customId),
            lore("§7Current Shape: §5"+d.shapeLabel()),
            lore("§7Light Level: §e"+d.lightLevel+"  §7Hardness: §f"+hardnessLabel(d.hardness)),
            lore("§7Acoustics: §f"+d.soundType),
            lore("§7Hitbox: "+(d.noCollision?"§cOFF":"§aON")),
            lore("§8Database Slot #"+d.index)
        )));
        inv.setStack(4, disp);
        inv.setStack(8, uiGlint(Items.MAP,"§b§l⬛ Retexture Block","§7Update the main texture of this block","§b• Tip: §7Paste a URL from Imgur, Discord, etc."));
        
        inv.setStack(19, uiGlint(Items.PAINTING, "§d§l⬡ Face Mapping Forge", "§7Apply textures to individual faces","§b• Tip: §7Change Top, Bottom, or Side textures separately"));
        inv.setStack(21, uiGlint(Items.ENDER_PEARL, "§5§l⬡ Shape Sculptor", "§7Presets, custom boxes, and collisions","§b• Tip: §7Make slabs, stairs, or custom hitboxes"));
        inv.setStack(23, uiGlint(Items.REDSTONE, "§6§l⚙ Engine Properties", "§7Adjust light glow & mining hardness","§b• Tip: §7Adjust how the block feels in-world"));
        inv.setStack(25, uiGlint(Items.NOTE_BLOCK, "§e§l♫ Acoustic Tuner", "§7Change placement & break sounds","§b• Tip: §7Simulate stone, glass, dirt, etc."));
        
        inv.setStack(31, d.isAnimated()
            ? uiGlint(Items.CLOCK, "§b§l⟳ Animation Settings", "§7This block is currently animated","§b• Tip: §7You can adjust frame speed (FPS) here")
            : ui(Items.GRAY_DYE, "§7§l⟳ Animation", "§8No animation detected","§b• Tip: §7Animations are auto-enabled for GIF textures"));
        
        inv.setStack(37, uiGlint(Items.NAME_TAG,"§e§l✎ Rename Block","§7Current: §f"+d.displayName,"§b• Tip: §7This is the name everyone sees in the inventory"));
        inv.setStack(39, uiGlint(Items.COMMAND_BLOCK,"§b§l⇄ Re-ID Block","§7Current: §b"+d.customId,"§b• Tip: §7Changing the unique ID updates all current builds"));
        inv.setStack(41, uiGlint(Items.COMPARATOR,"§e§l⧉ Duplicate Block","§7Create an identical copy of this block","§b• Tip: §7Great for making similar block sets quickly"));
        
        inv.setStack(53, confirmDelete
            ? uiGlint(Items.BARRIER, "§4§l⚠ CONFIRM DELETION","§cPermanently delete: §f"+d.customId,"§c§oClick again to confirm!")
            : ui(Items.TNT, "§c§l⚠ Delete This Block","§7Removes the block from the server","§b• Tip: §7Can be undone via Main Menu if accidental"));

        inv.setStack(45, uiGlint(Items.RED_CONCRETE,"§c◀ Back to Block List"));
        return inv;
    }

    private static SimpleInventory buildShapeEditor(SlotData d, int page) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        inv.setStack(4, uiGlint(Items.CHISEL != null ? Items.CHISEL : Items.GOLDEN_PICKAXE, "§5§lShape Sculptor", 
            "§7Current: §f" + d.shapeLabel(), "§8Choose a preset or design your own."));

        // ── Preset Groups ──────────────────────────────────────────────────
        // Basic Layers
        inv.setStack(10, ui(Items.STONE, "§f§lFull Cube", "§7Reset to standard block."));
        inv.setStack(11, ui(Items.SMOOTH_STONE_SLAB, "§f§lSlab", "§7Standard 8-pixel height."));
        inv.setStack(12, ui(Items.DAYLIGHT_DETECTOR, "§f§lThin Layer", "§7Flat 4-pixel height."));
        inv.setStack(13, ui(Items.WHITE_CARPET, "§f§lCarpet", "§7Ultra-thin 1-pixel height."));

        // Structural
        inv.setStack(19, ui(Items.DEEPSLATE_WALL, "§f§lPillar", "§7Solid 8x8 center pillar."));
        inv.setStack(20, ui(Items.PLAYER_HEAD, "§f§lSmall Box", "§7Small centered 12x12x12."));
        inv.setStack(21, ui(Items.GOLD_NUGGET, "§f§lMicro Block", "§7Tiny 8x8x8 centered cube."));
        inv.setStack(22, ui(Items.GLASS_PANE, "§f§lGlass Pane", "§7Vertical 2-pixel thin panel."));

        // Detail Shapes
        inv.setStack(28, ui(Items.OAK_TRAPDOOR, "§f§lTrapdoor", "§7Standard 3-pixel trapdoor."));
        inv.setStack(29, ui(Items.OAK_FENCE, "§f§lFence", "§7Centered 4x4 fence post."));
        inv.setStack(30, ui(Items.STONE_STAIRS, "§f§lStairs", "§7Dynamic stair-step shape."));
        inv.setStack(31, ui(Items.POPPY, "§f§lCross", "§7Diagonal 'X' shape (like flowers)."));

        // ── Actions ────────────────────────────────────────────────────────
        inv.setStack(40, uiGlint(Items.MAP, "§6§lManual Sculpting", "§7Coming soon: Build your", "§7own boxes pixel-by-pixel!"));
        inv.setStack(45, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Editor"));
        
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

    // ── Sensory Feedback ──────────────────────────────────────────────────────
    public static void playClick(ServerPlayerEntity p) { p.getServerWorld().playSound(null, p.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, net.minecraft.sound.SoundCategory.PLAYERS, 0.6f, 1.25f); }
    public static void playSuccess(ServerPlayerEntity p) { p.getServerWorld().playSound(null, p.getBlockPos(), net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, net.minecraft.sound.SoundCategory.PLAYERS, 0.8f, 1.0f); p.getServerWorld().playSound(null, p.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_CLUSTER_STEP, net.minecraft.sound.SoundCategory.PLAYERS, 0.5f, 1.0f); }
    public static void playError(ServerPlayerEntity p) { p.getServerWorld().playSound(null, p.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), net.minecraft.sound.SoundCategory.PLAYERS, 1f, 0.7f); }

    private static SimpleInventory buildAnimGui(String id, float fps, boolean interp, int frameCount) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        SlotData d = SlotManager.getById(id);
        String blockName = d != null ? d.displayName : id;
        int ticks = Math.max(1, Math.round(20f / Math.max(0.5f, fps)));

        inv.setStack(0, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Studio", "§8Abandon current observations"));

        inv.setStack(4, uiGlint(Items.NETHER_STAR, "§b§l▶ Chronos Animation Observatory",
            "§7Analyzing Block: §f" + blockName,
            "§7Detected Frames: §f" + frameCount,
            "§7Effective Frequency: §b" + String.format("%.1f", fps) + " Hz"));

        // ── Temporal Refinement ──────────────────────────────────────────────────
        inv.setStack(19, ui(Items.OBSIDIAN, "§c§l« §r§cSlow Flux §8(-5 FPS)", "§7Decrease the temporal speed"));
        inv.setStack(20, ui(Items.ARROW, "§c§l‹ §r§cMinor Reduction §8(-1 FPS)"));

        inv.setStack(22, uiGlint(Items.ECHO_SHARD, "§e§lThe Chronos Crystal",
            "§7Current Vibrations: §b" + String.format("%.1f", fps) + " FPS",
            "§7Tick Delay: §f" + ticks + " §7ticks per frame",
            "",
            "§3§o\"Harness the crystal's pulse to stabilize\"",
            "§3§o\"the block's existence in your world.\""));

        inv.setStack(24, ui(Items.ARROW, "§a+1 FPS §l›", "§7Minor Increase"));
        inv.setStack(25, ui(Items.GOLD_INGOT, "§a+5 FPS §l»", "§7Accelerate Flux"));

        // ── Frequency Nodes (Presets) ───────────────────────────────────────────
        inv.setStack(28, ui(Items.AMETHYST_SHARD, "§d5 FPS Node", "§7Cinematic temporal flow"));
        inv.setStack(29, ui(Items.AMETHYST_SHARD, "§d10 FPS Node", "§7Harmonized temporal flow"));
        inv.setStack(30, ui(Items.AMETHYST_CLUSTER, "§b20 FPS Node", "§7Vanilla temporal flow"));
        inv.setStack(31, ui(Items.AMETHYST_CLUSTER, "§b40 FPS Node", "§7Overclocked temporal flow"));

        // ── Chronos Shifting (Interpolation) ───────────────────────────────────
        inv.setStack(40, interp
            ? uiGlint(Items.CRYING_OBSIDIAN, "§d§lSmooth Chronos Shifting: §6ACTIVE",
                "§7Blends temporal frames together flawlessly.",
                "§3§oIdeal for water, magmatic, or energy blocks.",
                "§8Click to §cTERMINATE §8shifting")
            : ui(Items.OBSIDIAN, "§7§lSmooth Chronos Shifting: §8INACTIVE",
                "§7Sharp transitions between temporal states.",
                "§3§oIdeal for pixel art and legacy textures.",
                "§8Click to §6ACTIVATE §8shifting"));

        inv.setStack(45, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Studio"));
        inv.setStack(49, uiGlint(Items.DRAGON_EGG, "§6§l👑 EXECUTE GLOBAL SYNC",
            "§7Finalize temporal adjustments and",
            "§7broadcast the resonance to all players.",
            "",
            "§e§lClick to stabilize reality."));

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
    private static void send(ServerPlayerEntity p, String m) { ChatHelper.info(p, m); }
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
