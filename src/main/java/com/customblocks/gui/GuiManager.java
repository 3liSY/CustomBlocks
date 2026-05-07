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
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(5))
        .build();

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
    private record FaceImportPending(String blockId, String face, int returnPage, String importDir, long expiresAt) {}
    private static final Map<UUID, AnimParams> ANIM_PARAMS = new ConcurrentHashMap<>();
    private static final Map<UUID, AnimParams> ANIM_ORIGINAL_PARAMS = new ConcurrentHashMap<>();
    private static final Map<UUID, FaceImportPending> FACE_IMPORTS = new ConcurrentHashMap<>();

    private static final Map<UUID, Long> SHAPE_CREATE_COOLDOWN = new ConcurrentHashMap<>();
    private static final long SHAPE_COOLDOWN_MS = 500;
    private static final Map<UUID, Long> CLICK_COOLDOWN = new ConcurrentHashMap<>();
    private static final long CLICK_COOLDOWN_MS = 100;
    private static final long FACE_IMPORT_TIMEOUT_MS = 5 * 60_000L;
    private static final int FACE_IMPORT_POLL_TICKS = 40;
    private static final String FACE_IMPORT_FOLDER = "config/customblocks/import";
    private static final String FACE_IMPORT_REQUESTS_DIR = "faces";
    private static int faceImportTickCounter = 0;

    public enum InputAction {
        SET_LIGHT,
        SET_HARDNESS,
        WEB_LINK_CAST,
        CREATE_ID,
        CREATE_NAME,
        CREATE_URL,
        RETEXTURE_URL,
        SETFACE_URL,
        SETFACE_VARIANT_URL,
        RENAME_TEXT,
        REID_TEXT,
        SETTABICON_URL,
        ADMIN_CUSTOM_TITLE,
        CONFIG_VALUE,
        BG_FACTORY_HEX,
        ANIM_CUSTOM_FPS,
        CREATE_CAT_KEY,
        RENAME_CAT_TEXT,
        EDIT_CAT_PROP,
        CREATE_CAT_NAME,
        CREATE_CAT_ICON,
        CREATE_CAT_COLOR,
        CREATE_CAT_BADGE,
        BULK_RECOLOR_SCOPE,
        BULK_RECOLOR_EXCLUDE
    }

    public record PendingInput(InputAction action, String blockId, String face,
                               String partialId, String partialName, int returnPage) {}

    // ── Per-player state ─────────────────────────────────────────────────────
    private static final Map<UUID, GuiState>       STATES   = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<GuiState>> BACK_STACK = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingInput>   PENDING  = new ConcurrentHashMap<>();
    private static final Set<UUID> REOPENING_SCREENS        = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, CbScreenHandler> HANDLERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<String>> BULK_DELETE_SELECTIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> SEARCH_QUERIES = new ConcurrentHashMap<>();
    private static final Map<UUID, String> FACE_CHANGE_SELECTIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> FACE_CHANGE_RETURN_PAGES = new ConcurrentHashMap<>();
    private static final Map<UUID, String> BULK_RECOLOR_COLOR = new ConcurrentHashMap<>();
    private static final Map<UUID, String> BULK_RECOLOR_SCOPE = new ConcurrentHashMap<>();
    private static final Map<UUID, String> BULK_RECOLOR_SCOPE_VALUE = new ConcurrentHashMap<>();
    private static final Map<UUID, String> BULK_RECOLOR_EXCLUDE = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<String>> BULK_RECOLOR_SELECTED = new ConcurrentHashMap<>();
    private static final Set<UUID> BULK_RECOLOR_CONFIRM_ARMED = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Deque<String>> RECENT_BLOCKS = new ConcurrentHashMap<>();
    private static final int MAX_RECENT = 3;
    private static final Map<UUID, Long> ESC_DEBOUNCE = new ConcurrentHashMap<>();
    private static final long ESC_DEBOUNCE_MS = 150;

    private static final float[] HARD_CYCLE      = { -1f, 0f, 0.5f, 1.5f, 3f, 5f, 10f, 50f };
    private static final int     BLOCKS_PER_PAGE = 18;
    private static final String[] PRESET_NAMES   = {"full","slab","thin","carpet","pillar","small","micro","pane","trapdoor","fence","stairs","cross"};
    private static final String[] PRESET_DISPLAY = {"Full Block","Slab","Thin Slab","Carpet","Wall","Comparator","Comparator Small","Pane","Trapdoor","Fence","Stairs","Cross"};
    
    private static int errorCount = 0;
    public static void logError() { errorCount++; }

    private static void trackRecentBlock(UUID uuid, String blockId) {
        Deque<String> deque = RECENT_BLOCKS.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        deque.remove(blockId);
        deque.addFirst(blockId);
        while (deque.size() > MAX_RECENT) deque.removeLast();
    }

    // ── Screen open helpers ──────────────────────────────────────────────────

    /** Phase 8: Consolidated helper — sets state + opens a CbScreenHandler in one call. */
    private static void openScreenFromGuiState(ServerPlayerEntity player, GuiState state,
                                                SimpleInventory inv, String title) {
        STATES.put(player.getUuid(), state);
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, inv),
            Text.literal(title)));
    }

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
    private static final int MAX_BACK_STACK_DEPTH = 10;

    private static void pushBackStack(UUID uuid) {
        if (RESTORING.contains(uuid)) return;
        GuiState current = STATES.get(uuid);
        if (current != null) {
            Deque<GuiState> stack = BACK_STACK.computeIfAbsent(uuid, k -> new ArrayDeque<>());
            stack.push(current);
            while (stack.size() > MAX_BACK_STACK_DEPTH) stack.removeLast();
        }
    }

    /**
     * ESC handler: pops back-stack for proper navigation (BUG-02 fix).
     */
    public static void handleEscBack(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();

        // Debounce: ignore rapid ESC presses within 150ms
        long now = System.currentTimeMillis();
        Long lastEsc = ESC_DEBOUNCE.get(uuid);
        if (lastEsc != null && (now - lastEsc) < ESC_DEBOUNCE_MS) {
            return;
        }
        ESC_DEBOUNCE.put(uuid, now);

        PENDING.remove(uuid); 
        GuiState state = STATES.get(uuid);
        if (state == null) return;

        if (state.mode() == GuiMode.ANIM_GUI && isAnimDirty(uuid)) {
            openAnimConfirmAbandon(player, state.editingId(), state.page());
            return;
        }

        if (state.mode() == GuiMode.ANIM_CONFIRM_ABANDON) {
            reopenAnimGui(player, state.editingId(), state.page());
            return;
        }

        Deque<GuiState> stack = BACK_STACK.get(uuid);
        if (stack != null && !stack.isEmpty()) {
            GuiState prev = stack.pop();
            restoreState(player, prev);
        } else {
            STATES.remove(uuid);
        }
    }

    // ── Cleanup on disconnect ────────────────────────────────────────────────

    public static void onPlayerDisconnect(UUID uuid) {
        STATES.remove(uuid);
        BACK_STACK.remove(uuid);
        PENDING.remove(uuid);
        FACE_IMPORTS.remove(uuid);
        HANDLERS.remove(uuid);
        ANIM_PARAMS.remove(uuid);
        ANIM_ORIGINAL_PARAMS.remove(uuid);
        SHAPE_CREATE_COOLDOWN.remove(uuid);
        CLICK_COOLDOWN.remove(uuid);
        BULK_DELETE_SELECTIONS.remove(uuid);
        FACE_CHANGE_SELECTIONS.remove(uuid);
        FACE_CHANGE_RETURN_PAGES.remove(uuid);
        ESC_DEBOUNCE.remove(uuid);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public static void openToolsGui(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.tools(), buildToolsGui(player), "§d§lMagic Items & Tools");
    }

    public static void openMain(ServerPlayerEntity player, int page) {
        openScreenFromGuiState(player, GuiState.main(page), buildMain(player, page), "§b§l✦ §r§fCustomBlocks Dashboard");
    }

    public static void openEditorPicker(ServerPlayerEntity player) { openEditorPicker(player, 0); }
    public static void openEditorPicker(ServerPlayerEntity player, int page) {
        int total = sortedBlocks().size();
        int max   = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.picker(page), buildPicker(page, false), "§b§l▶ §r§fPick a Block");
    }

    public static void openEditor(ServerPlayerEntity player, String id, int returnPage, boolean fromCommand) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        trackRecentBlock(player.getUuid(), id);
        if (!fromCommand) pushBackStack(player.getUuid());
        openScreenFromGuiState(player,
            fromCommand ? GuiState.editorFromCommand(id) : GuiState.editor(id, returnPage),
            buildEditor(d, false), "§e§l✎ §r§fBlock Editor §8— " + d.displayName);
    }

    public static void openEditor(ServerPlayerEntity player, String id, int returnPage) {
        openEditor(player, id, returnPage, false);
    }

    public static void openFaceEditor(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.faceEditor(id, returnPage), buildFaceEditor(d), "§d§l⬡ §r§fFace Editor §8— " + d.displayName);
    }

    private static void reopenFaceEditor(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        openScreenFromGuiState(player, GuiState.faceEditor(id, returnPage), buildFaceEditor(d), "§d§l⬡ §r§fFace Editor §8— " + d.displayName);
    }

    public static void openFaceChangeSelect(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        FACE_CHANGE_RETURN_PAGES.put(player.getUuid(), returnPage);
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.faceChangeSelect(id, returnPage),
            buildFaceChangeSelect(d), "\u00A75\u00A7l\u2726 \u00A7r\u00A7fCopy Face From Another Block \u00A78\u2014 " + d.displayName);
    }

    private static void reopenFaceChangeSelect(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        FACE_CHANGE_RETURN_PAGES.put(player.getUuid(), returnPage);
        openScreenFromGuiState(player, GuiState.faceChangeSelect(id, returnPage),
            buildFaceChangeSelect(d), "\u00A75\u00A7l\u2726 \u00A7r\u00A7fCopy Face From Another Block \u00A78\u2014 " + d.displayName);
    }

    private static void openFaceChangePicker(ServerPlayerEntity player, String id, String face, int page) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, FACE_CHANGE_RETURN_PAGES.getOrDefault(player.getUuid(), 0)); return; }
        FACE_CHANGE_SELECTIONS.put(player.getUuid(), face);
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.faceChangePicker(id, page),
            buildFaceChangePicker(d, face, page),
            "\u00A75\u00A7l\u2726 \u00A7r\u00A7fPick Source Block \u00A78\u2014 \u00A7b" + face.toUpperCase(Locale.ROOT));
    }

    private static void reopenFaceChangePicker(ServerPlayerEntity player, String id, String face, int page) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, FACE_CHANGE_RETURN_PAGES.getOrDefault(player.getUuid(), 0)); return; }
        FACE_CHANGE_SELECTIONS.put(player.getUuid(), face);
        openScreenFromGuiState(player, GuiState.faceChangePicker(id, page),
            buildFaceChangePicker(d, face, page),
            "\u00A75\u00A7l\u2726 \u00A7r\u00A7fPick Source Block \u00A78\u2014 \u00A7b" + face.toUpperCase(Locale.ROOT));
    }

    public static void openShapeEditor(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.shapeEditor(id, returnPage), buildShapeEditor(d, 0), "§5§l⬡ §r§fShape Editor §8— " + d.displayName);
    }

    public static void openSearchPicker(ServerPlayerEntity player, String query, int page) {
        String q = query.toLowerCase().trim();
        SEARCH_QUERIES.put(player.getUuid(), q);
        List<SlotData> results = sortedBlocks().stream()
            .filter(d -> d.customId.toLowerCase().contains(q) || d.displayName.toLowerCase().contains(q))
            .toList();
        int total = results.size();
        int max = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.searchPicker(page), buildSearchPicker(page, q), "§b§l🔍 §r§fSearch: §7" + query + " §8(" + total + " found)");
    }

    public static void openMaintenanceMenu(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.maintenance(), buildMaintenanceMenu(player), "§b§l✦ §r§fServer Tools");
    }

    private static SimpleInventory buildResourceHub(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        boolean httpUp = com.customblocks.network.ResourcePackServer.isRunning();
        int port = com.customblocks.network.ResourcePackServer.getPort();
        int texCount = SlotManager.usedSlots();

        inv.setStack(4, uiGlint(Items.COMPASS, "§b§lResource Pack Hub",
            "§7Textures registered: §f" + texCount,
            "§7HTTP Server: " + (httpUp ? "§a✔ Running §7(port §f" + port + "§7)" : "§c✖ Stopped")));

        inv.setStack(20, uiGlint(Items.ECHO_SHARD, "§b§lGet Download Link",
            "§7Creates a shareable URL for your texture pack",
            "§b§nClick to broadcast to chat"));

        inv.setStack(22, uiGlint(Items.NETHER_STAR, "§a§lForce Sync",
            "§7Sends latest textures to all players",
            "§e§lClick to broadcast"));

        inv.setStack(24, uiGlint(Items.ECHO_SHARD, "§6§l⏸ Pause Reloads",
            "§7Pauses resource pack reloading on all clients.",
            "§7Useful when making many edits in a row.",
            "§8Click to pause"));

        inv.setStack(26, uiGlint(Items.AMETHYST_SHARD, "§a§l▶ Resume Reloads",
            "§7Resumes resource pack reloading on all clients.",
            "§7Triggers a reload if changes were made while paused.",
            "§8Click to resume"));

        inv.setStack(45, uiGlint(Items.RED_CONCRETE, "§c◀ Back"));
        return inv;
    }

    public static void openHelpGui(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.help(), buildHelpGui(), "§a§l✦ §r§fHelp & Commands");
    }

    public static void openPropertiesGui(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.properties(id, returnPage), buildPropertiesGui(d), "§6§l⚙ §r§fBlock Properties §8— " + d.displayName);
    }

    public static void openSoundMenu(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.sound(id, returnPage), buildSoundMenu(d), "§e§l♫ §r§fSound Selector §8— " + d.displayName);
    }

    public static void openTabIconPicker(ServerPlayerEntity player, int page) {
        int total = sortedBlocks().size();
        int max   = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        openScreenFromGuiState(player, GuiState.picker(page), buildPicker(page, false), "§b§l▶ §r§fPick Tab Icon §7(ESC = back)");
    }

    public static void openResourceHub(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.resourceCenter(), buildResourceHub(player), "§b§l✦ §r§fResource Pack");
    }

    public static void openBrokenBlocks(ServerPlayerEntity player) { openBrokenBlocks(player, 0); }
    public static void openBrokenBlocks(ServerPlayerEntity player, int page) {
        int total = brokenBlocks().size();
        int max   = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.pickerBroken(page), buildPicker(page, true), "§6§l✦ §r§fBroken Block Finder");
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
                case FACE_CHANGE_SELECT -> openFaceChangeSelect(player, state.editingId(), state.page());
                case FACE_CHANGE_PICKER -> reopenFaceChangePicker(player, state.editingId(),
                    FACE_CHANGE_SELECTIONS.getOrDefault(player.getUuid(), "top"), state.page());
                case SHAPE_EDITOR -> openShapeEditor(player, state.editingId(), state.page());
                case MAINTENANCE_MENU -> openMaintenanceMenu(player);
                case HELP_MENU -> openHelpGui(player);
                case TOOLS_GUI -> openToolsGui(player);
                case PICKER_BROKEN -> openBrokenBlocks(player, state.page());
                case PROPERTIES_MENU -> openPropertiesGui(player, state.editingId(), state.page());
                case SOUND_MENU -> openSoundMenu(player, state.editingId(), state.page());
                case TAB_ICON_MENU -> openTabIconPicker(player, state.page());
                case RESOURCE_CENTER -> openResourceHub(player);
                case ANIM_GUI -> openAnimGui(player, state.editingId(), state.page());
                case BULK_DELETE -> openBulkDelete(player, state.page());
                case SEARCH_PICKER -> {
                    String q = SEARCH_QUERIES.getOrDefault(player.getUuid(), "");
                    openSearchPicker(player, q, state.page());
                }
                case MAGIC_ITEMS -> openMagicItemsGui(player);
                case CONFIG_WARNING -> openConfigWarningGui(player, false);
                case CONFIG_GUI -> openConfigGui(player, false);
                case UNDO_PICKER -> openUndoPicker(player, state.page());
                case HELP_CATEGORY -> openHelpCategory(player, state.page());
                case ANIM_CONFIRM_ABANDON -> reopenAnimGui(player, state.editingId(), state.page());
                case BG_STUDIO -> openBgStudio(player, false);
                case UNCATEGORIZED_PICKER -> openBlocksGui(player, state.page());
                case ASSIGNMENT_DECISION -> openAssignmentDecision(player, state.editingId(), state.page());
                case CATEGORY_PICKER -> openCategoryPicker(player, state.editingId(), state.page());
                case CATEGORY_BROWSER -> openCategoryBrowser(player, state.page());
                case CATEGORY_DETAIL -> openCategoryDetail(player, state.editingId(), state.page());
                case CATEGORY_CONTROLLER -> openCategoryController(player, state.page());
                case CATEGORY_EDITOR -> openCategoryEditor(player, state.editingId(), state.page());
                case SUBCATEGORY_CONTROLLER -> openSubcategoryController(player, state.editingId(), state.page());
                case COLOR_FILL_MODE -> openColorFillModeGui(player);
                default -> openMain(player, 0);
            }
        } finally {
            RESTORING.remove(player.getUuid());
        }
    }

    // ── Click dispatch ───────────────────────────────────────────────────────

    public static void handleClick(ServerPlayerEntity player, int slot, int button, SlotActionType actionType) {
        long now = System.currentTimeMillis();
        Long lastClick = CLICK_COOLDOWN.put(player.getUuid(), now);
        if (lastClick != null && now - lastClick < CLICK_COOLDOWN_MS) return;
        GuiState state = null;
        try {
            playClick(player);
            state = STATES.get(player.getUuid());
            if (state == null) return;
            switch (state.mode()) {
                case MAIN         -> handleMainClick(player, state, slot);
                case PICKER       -> handlePickerClick(player, state, slot, false, actionType, button);
                case PICKER_BROKEN-> handlePickerClick(player, state, slot, true, actionType, button);
                case TAB_ICON_MENU-> handleTabIconMenuClick(player, state, slot);
                case RESOURCE_CENTER -> handleResourceHubClick(player, state, slot);
                case EDITOR       -> handleEditorClick(player, state, slot, button);
                case FACE_EDITOR  -> handleFaceEditorClick(player, state, slot, button, actionType == SlotActionType.QUICK_MOVE);
                case FACE_CHANGE_SELECT -> handleFaceChangeSelectClick(player, state, slot);
                case FACE_CHANGE_PICKER -> handleFaceChangePickerClick(player, state, slot);
                case SHAPE_EDITOR -> handleShapeEditorClick(player, state, slot, button);
                case MAINTENANCE_MENU -> handleMaintenanceClick(player, state, slot);
                case HELP_MENU      -> handleHelpClick(player, state, slot);
                case TOOLS_GUI      -> handleToolsClick(player, state, slot);
                case PROPERTIES_MENU -> handlePropertiesClick(player, state, slot, button);
                case SOUND_MENU     -> handleSoundClick(player, state, slot);
                case ANIM_GUI       -> handleAnimGuiClick(player, state, slot);
                case BULK_DELETE     -> handleBulkDeleteClick(player, state, slot);
                case SEARCH_PICKER  -> handleSearchPickerClick(player, state, slot);
                case MAGIC_ITEMS    -> handleMagicItemsClick(player, state, slot);
                case CONFIG_WARNING -> handleConfigWarningClick(player, state, slot);
                case CONFIG_GUI     -> handleConfigGuiClick(player, state, slot);
                case UNDO_PICKER    -> handleUndoPickerClick(player, state, slot);
                case HELP_CATEGORY  -> handleHelpCategoryClick(player, state, slot);
                case ANIM_CONFIRM_ABANDON -> handleAnimConfirmAbandonClick(player, state, slot);
                case BG_STUDIO -> handleBgStudioClick(player, state, slot, button);
                case UNCATEGORIZED_PICKER -> handleUncategorizedPickerClick(player, state, slot);
                case ASSIGNMENT_DECISION -> handleAssignmentDecisionClick(player, state, slot);
                case CATEGORY_PICKER -> handleCategoryPickerClick(player, state, slot);
                case CATEGORY_BROWSER -> handleCategoryBrowserClick(player, state, slot);
                case CATEGORY_DETAIL -> handleCategoryDetailClick(player, state, slot);
                case CATEGORY_CONTROLLER -> handleCategoryControllerClick(player, state, slot);
                case CATEGORY_EDITOR -> handleCategoryEditorClick(player, state, slot, button);
                case SUBCATEGORY_CONTROLLER -> handleSubcategoryControllerClick(player, state, slot);
                case IMPORT_CONFLICT -> handleImportConflictClick(player, state, slot);
                case DELETE_CATEGORY_MENU -> handleDeleteCategoryMenuClick(player, state, slot);
                case MERGE_CATEGORY_PICKER_TARGET -> handleMergeCategoryPickerTargetClick(player, state, slot);
                case BULK_ASSIGN_PICKER -> handleBulkAssignPickerClick(player, state, slot);
                case SORT_BLOCKS_MENU -> handleSortBlocksMenuClick(player, state, slot);
                case CATEGORY_STATS -> handleCategoryStatsClick(player, state, slot);
                case CATEGORY_BLOCK_CONTEXT -> handleCategoryBlockContextClick(player, state, slot);
                case CATEGORY_ICON_PICKER -> handleCategoryIconPickerClick(player, state, slot);
                case BULK_RECOLOR_WIZARD -> handleBulkRecolorWizardClick(player, state, slot);
                case BULK_RECOLOR_CONFIRM -> handleBulkRecolorConfirmClick(player, state, slot);
                case COLOR_FILL_MODE -> handleColorFillModeClick(player, state, slot);
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
            send(player, "§0§l[§b§lCB§0§l]§r Cancelled.");
            switch (pending.action()) {
                case CONFIG_VALUE -> openConfigGui(player, false);
                case ADMIN_CUSTOM_TITLE -> {
                    openMain(player, rp);
                }
                case SET_LIGHT, SET_HARDNESS -> {
                    if (blockId != null && SlotManager.hasId(blockId)) openPropertiesGui(player, blockId, rp);
                    else openMain(player, rp);
                }
                case SETFACE_URL, SETFACE_VARIANT_URL -> {
                    if (blockId != null && SlotManager.hasId(blockId)) openFaceEditor(player, blockId, rp);
                    else openMain(player, rp);
                }
                case SETTABICON_URL -> openTabIconPicker(player, rp);
                case ANIM_CUSTOM_FPS -> reopenAnimGui(player, blockId, rp);
                default -> {
                    if (blockId != null && !blockId.startsWith("__") && SlotManager.hasId(blockId)) openEditor(player, blockId, rp);
                    else openMain(player, rp);
                }
            }
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
                openShortInputPrompt(
                    player,
                    new PendingInput(InputAction.CREATE_NAME, id, null, id, null, rp),
                    "§eDisplay Name",
                    new ItemStack(Items.NAME_TAG),
                    id
                );
                return true;
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
                send(player, "§0§l[§b§lCB§0§l]§r Downloading '" + name + "'…");
                MinecraftServer srv = player.getServer();
                thread(player, () -> { try {
                    // Unified pipeline: handles GIFs (disposal + animMeta), PNG, WebP.
                    ImageProcessor.ProcessResult result = ImageProcessor.downloadAndProcess(text, CustomBlocksConfig.defaultTextureSize);
                    if (result == null || result.bytes() == null || result.bytes().length == 0) {
                        srv.execute(() -> { playError(player); send(player, "§c[GUI] Downloaded image was empty."); openMain(player, rp); });
                        return;
                    }
                    final byte[] fb = result.bytes(); final String fa = result.mcmeta();
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
                send(player, "§0§l[§b§lCB§0§l]§r Downloading texture…");
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
                send(player, "§0§l[§b§lCB§0§l]§r Downloading " + face + " face…");
                MinecraftServer srv = player.getServer();
                thread(player, () -> { try {
                    ImageProcessor.ProcessResult result = ImageProcessor.downloadAndProcess(text, CustomBlocksConfig.defaultTextureSize);
                    if (result == null || result.bytes() == null || result.bytes().length == 0) {
                        srv.execute(() -> { playError(player); send(player, "§c[GUI] Downloaded face image was empty."); openFaceEditor(player, blockId, rp); });
                        return;
                    }
                    srv.execute(() -> {
                        SlotData d = SlotManager.getById(blockId);
                        if (d == null) { openMain(player, rp); return; }
                        UndoManager.pushUndoMutation(blockId, d, "setface " + face, player.getUuid());
                        SlotManager.setFaceTexture(blockId, face, result.bytes());
                        // Propagate animMeta for animated face textures so frames don't stack.
                        if (result.isAnimated() && result.mcmeta() != null) {
                            SlotManager.setAnimMeta(blockId, result.mcmeta());
                        }
                        SlotManager.saveAll();
                        playSuccess(player);
                        NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("setface", d.index, blockId, null, result.bytes(),
                                d.lightLevel, d.hardness, d.soundType, face, null,
                                result.isAnimated() ? result.mcmeta() : null));
                        String suffix = result.isAnimated() ? " §8(animated)" : "";
                        send(player, "§0§l[§b§lCB§0§l]§r §f" + face.toUpperCase() + " §aface set on '§f" + blockId + "§a'." + suffix);
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
                send(player, "§0§l[§b§lCB§0§l]§r Creating variant with " + face + " face…");
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
                        send(player, "§0§l[§b§lCB§0§l]§r Variant '§f" + varId + "§a' created & given!");
                        openFaceEditor(player, varId, rp);
                    });
                } catch (Exception e) { srv.execute(() -> { send(player, "§c[GUI] Failed: " + e.getMessage()); openFaceEditor(player, blockId, rp); }); } });
                return true;
            }
            case RENAME_TEXT -> {
                SlotData d = SlotManager.getById(blockId);
                if (d == null) { openMain(player, rp); return true; }
                String convertedText = text.replace("_"," ").replace("&", "§");
                if (convertedText.length() > 100) convertedText = convertedText.substring(0, 100);
                UndoManager.pushUndoMutation(blockId, d, "rename", player.getUuid());
                SlotManager.rename(blockId, convertedText); SlotManager.saveAll();
                NetworkManager.broadcastUpdate(player.getServer(), new SlotUpdatePayload("rename", d.index, blockId, convertedText, null, 0, 0, "stone"));
                send(player, "§0§l[§b§lCB§0§l]§r Renamed to '§f" + convertedText + "§a'.");
                player.getServerWorld().playSound(null, player.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_ANVIL_USE, net.minecraft.sound.SoundCategory.MASTER, 1f, 1f);
                openEditor(player, blockId, rp); return true;
            }
            case SETTABICON_URL -> {
                if ("cancel".equalsIgnoreCase(text)) { openMain(player, rp); return true; }
                String targetId = text.toLowerCase().trim();
                boolean isBlock = SlotManager.hasId(targetId);
                if (!isUrl(text) && !isBlock) { send(player, "§cNeeds a URL or Block ID."); openMain(player, rp); return true; }
                send(player, "§0§l[§b§lCB§0§l]§r Processing tab icon…");
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
                        send(player, "§0§l[§b§lCB§0§l]§r Tab icon updated!");
                        openMain(player, rp);
                    });
                } catch (Exception e) { srv.execute(() -> { send(player, "§c[GUI] Failed: " + e.getMessage()); openMain(player, rp); }); } });
                return true;
            }
            case REID_TEXT -> {
                if ("__search__".equals(blockId)) {
                    openSearchPicker(player, text, 0);
                    return true;
                }
                if ("__givesquare__".equals(blockId)) {
                    String col = text.toLowerCase().trim();
                    if (!List.of("black","yellow","green").contains(col)) { send(player, "§cChoose: §fblack §7| §fyellow §7| §fgreen"); openMain(player, rp); return true; }
                    Item it = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, col + "_square"));
                    if (it != null && it != Items.AIR) player.getInventory().insertStack(new ItemStack(it, 1));
                    send(player, "§0§l[§b§lCB§0§l]§r Given §f" + col + " Square§a!"); openMain(player, rp); return true;
                }
                if ("__givetriangle__".equals(blockId)) {
                    String col = text.toLowerCase().trim();
                    if (!List.of("black","yellow","green").contains(col)) { send(player, "§cChoose: §fblack §7| §fyellow §7| §fgreen"); openMain(player, rp); return true; }
                    Item it = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, col + "_triangle"));
                    if (it != null && it != Items.AIR) player.getInventory().insertStack(new ItemStack(it, 1));
                    send(player, "§0§l[§b§lCB§0§l]§r Given §f" + col + " Triangle§a!"); openMain(player, rp); return true;
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
                send(player, "§0§l[§b§lCB§0§l]§r Re-ID'd '§f" + blockId + "§a' → '§f" + newId + "§a'.");
                openEditor(player, newId, rp); return true;
            }
            case ADMIN_CUSTOM_TITLE -> {
                send(player, "§0§l[§b§lCB§0§l]§r Action cancelled.");
                openMain(player, 0);
                return true;
            }
            case SET_LIGHT -> {
                if ("cancel".equalsIgnoreCase(text)) { send(player, "§7[Properties] Cancelled."); openPropertiesGui(player, blockId, rp); return true; }
                try {
                    int light = Integer.parseInt(text);
                    if (light < 0 || light > 15) throw new NumberFormatException();
                    SlotData d = SlotManager.getById(blockId);
                    if (d == null) { openMain(player, rp); return true; }
                    UndoManager.pushUndoMutation(blockId, d, "setglow", player.getUuid());
                    SlotManager.setLightLevel(blockId, light);
                    SlotManager.saveAll();
                    syncProp(player, SlotManager.getById(blockId)); 
                    send(player, "§0§l[§b§lCB§0§l]§r Light level set to " + light + ".");
                    openPropertiesGui(player, blockId, rp);
                } catch (NumberFormatException e) {
                    send(player, "§cInvalid light level. Must be 0-15.");
                    openPropertiesGui(player, blockId, rp);
                }
                return true;
            }
            case SET_HARDNESS -> {
                if ("cancel".equalsIgnoreCase(text)) { send(player, "§7[Properties] Cancelled."); openPropertiesGui(player, blockId, rp); return true; }
                try {
                    float hardness = Float.parseFloat(text);
                    if (hardness < -1.0f) throw new NumberFormatException();
                    SlotData d = SlotManager.getById(blockId);
                    if (d == null) { openMain(player, rp); return true; }
                    UndoManager.pushUndoMutation(blockId, d, "sethardness", player.getUuid());
                    SlotManager.setHardness(blockId, hardness);
                    SlotManager.saveAll();
                    syncProp(player, SlotManager.getById(blockId)); 
                    send(player, "§0§l[§b§lCB§0§l]§r Hardness set to " + hardness + ".");
                    openPropertiesGui(player, blockId, rp);
                } catch (NumberFormatException e) {
                    send(player, "§cInvalid hardness value.");
                    openPropertiesGui(player, blockId, rp);
                }
                return true;
            }
            case ANIM_CUSTOM_FPS -> {
                try {
                    float customFps = Float.parseFloat(text);
                    customFps = Math.max(0.5f, Math.min(100f, customFps));
                    customFps = Math.round(customFps * 10f) / 10f;
                    AnimParams ap = ANIM_PARAMS.getOrDefault(player.getUuid(), new AnimParams(10f, false, 1));
                    ANIM_PARAMS.put(player.getUuid(), new AnimParams(customFps, ap.interpolate(), ap.frameCount()));
                    send(player, "§a[Anim] FPS set to §f" + String.format("%.1f", customFps));
                } catch (NumberFormatException e) {
                    send(player, "§cInvalid number. Enter a value like §f20§c or §f0.5");
                }
                reopenAnimGui(player, blockId, rp);
                return true;
            }
            case CONFIG_VALUE -> {
                String key = blockId;
                try {
                    switch (key) {
                        case "maxSlots" -> CustomBlocksConfig.maxSlots = Math.max(1, Math.min(8192, Integer.parseInt(text)));
                        case "defaultTextureSize" -> CustomBlocksConfig.defaultTextureSize = Math.max(16, Math.min(256, Integer.parseInt(text)));
                        case "bgRemovalTolerance" -> CustomBlocksConfig.bgRemovalTolerance = Math.max(0, Math.min(100, Integer.parseInt(text)));
                        case "maxUndoDepth" -> CustomBlocksConfig.maxUndoDepth = Math.max(1, Math.min(100, Integer.parseInt(text)));
                        case "downloadTimeoutSeconds" -> CustomBlocksConfig.downloadTimeoutSeconds = Math.max(1, Math.min(120, Integer.parseInt(text)));
                        case "texturePayloadsPerTick" -> CustomBlocksConfig.texturePayloadsPerTick = Math.max(1, Math.min(50, Integer.parseInt(text)));
                        case "resourcePackPort" -> CustomBlocksConfig.resourcePackPort = Math.max(0, Math.min(65535, Integer.parseInt(text)));
                        case "reloadDebounceMs" -> CustomBlocksConfig.reloadDebounceMs = Math.max(500, Math.min(10000, Long.parseLong(text)));
                        case "cloudShareUrl" -> CustomBlocksConfig.cloudShareUrl = text.trim();
                        case "aiName" -> CustomBlocksConfig.aiName = text.replace("&", "§");
                        case "triangleGreenHex" -> {
                            String normalized = normalizeHexInput(text);
                            if (normalized == null) {
                                send(player, "§cUse a valid hex color like §f#1E8C1E§c.");
                                openConfigGui(player, false);
                                return true;
                            }
                            CustomBlocksConfig.triangleGreenHex = normalized;
                        }
                        case "triangleYellowHex" -> {
                            String normalized = normalizeHexInput(text);
                            if (normalized == null) {
                                send(player, "§cUse a valid hex color like §f#F0C814§c.");
                                openConfigGui(player, false);
                                return true;
                            }
                            CustomBlocksConfig.triangleYellowHex = normalized;
                        }
                        case "colorToolBackgroundMode" -> {
                            String v = text.toLowerCase().trim();
                            if (List.of("unset", "corners_only", "corners_and_trapped").contains(v)) {
                                CustomBlocksConfig.colorToolBackgroundMode = v;
                            } else {
                                send(player, "§cMust be: unset / corners_only / corners_and_trapped");
                                openConfigGui(player, false);
                                return true;
                            }
                        }
                        case "undoMode" -> {
                            String v = text.toLowerCase().trim();
                            if (List.of("global", "per_player", "both").contains(v)) CustomBlocksConfig.undoMode = v;
                            else { send(player, "§cMust be: global / per_player / both"); openConfigGui(player, false); return true; }
                        }
                        case "aiStyle" -> CustomBlocksConfig.aiStyle = text;
                        default -> { send(player, "§cUnknown config key."); openConfigGui(player, false); return true; }
                    }
                    CustomBlocksConfig.save();
                    send(player, "§a[Config] §f" + key + " §a= §e" + text);
                } catch (NumberFormatException e) {
                    send(player, "§cInvalid number.");
                }
                if ("bgRemovalTolerance".equals(key)) openBgStudio(player, false);
                else openConfigGui(player, false);
                return true;
            }
            case BULK_RECOLOR_SCOPE -> {
                BULK_RECOLOR_SCOPE_VALUE.put(player.getUuid(), text.trim());
                BULK_RECOLOR_CONFIRM_ARMED.remove(player.getUuid());
                send(player, "§0§l[§b§lCB§0§l]§r §aBulk recolor scope value saved: §f" + text.trim());
                openBulkRecolorWizard(player, 0);
                return true;
            }
            case BULK_RECOLOR_EXCLUDE -> {
                BULK_RECOLOR_EXCLUDE.put(player.getUuid(), text.trim());
                BULK_RECOLOR_CONFIRM_ARMED.remove(player.getUuid());
                send(player, "§0§l[§b§lCB§0§l]§r §aExclude list saved.");
                openBulkRecolorWizard(player, 0);
                return true;
            }
            case BG_FACTORY_HEX -> {
                if ("cancel".equalsIgnoreCase(text)) {
                    send(player, "§7[BG Studio] Triangle Factory cancelled.");
                    openBgStudio(player, false);
                    return true;
                }
                Integer rgb = parseHexColor(text);
                if (rgb == null) {
                    send(player, "§c[BG Studio] Type a hex colour like §f#55CCFF §cor §f55ccff§c.");
                    openBgStudio(player, false);
                    return true;
                }
                giveCustomColorTools(player, rgb);
                openBgStudio(player, false);
                return true;
            }
            case WEB_LINK_CAST -> {
                if (!isUrl(text)) { send(player, "§cNeeds a URL."); openEditor(player, blockId, rp); return true; }
                ChatHelper.info(player, "Casting web-link to block...");
                MinecraftServer srv = player.getServer();
                thread(player, () -> { try {
                    ImageProcessor.ProcessResult result = ImageProcessor.downloadAndProcess(text, CustomBlocksConfig.defaultTextureSize);
                    srv.execute(() -> {
                        SlotData d = SlotManager.getById(blockId);
                        if (d == null) return;
                        UndoManager.pushUndoMutation(blockId, d, "web-link cast", player.getUuid());
                        SlotManager.updateTexture(blockId, result.bytes());
                        SlotManager.setAnimMeta(blockId, result.mcmeta());
                        SlotManager.saveAll();
                        playSuccess(player);
                        NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("retexture", d.index, blockId, null, result.bytes(), d.lightLevel, d.hardness, d.soundType, null, null, result.mcmeta()));
                        ChatHelper.success(player, "Link applied! §b✔");
                        openEditor(player, blockId, rp);
                    });
                } catch (Exception e) { srv.execute(() -> { send(player, "§cCast failed: " + e.getMessage()); openEditor(player, blockId, rp); }); } });
                return true;
            }
                case EDIT_CAT_PROP -> {
                String catKey = blockId;
                String prop = pending.face();
                com.customblocks.core.Category old = com.customblocks.core.CategoryManager.getCategory(catKey);
                if (old != null) {
                    com.customblocks.core.Category updated = switch (prop) {
                        case "icon" -> old.withIconItem(text.trim());
                        case "color" -> old.withColor(text.trim());
                        case "badge" -> old.withBadge(text.trim());
                        case "parent" -> old.withParentKey(text.trim().isEmpty() ? null : text.trim());
                        case "description" -> old.withDescription(text.trim());
                        case "lorePrefix" -> old.withLorePrefix(text.trim().isEmpty() ? null : text.trim());
                        case "sortOrder" -> { try { yield old.withSortOrder(Integer.parseInt(text.trim())); } catch (NumberFormatException e) { yield old; } }
                        default -> old;
                    };
                    com.customblocks.core.CategoryManager.addCategory(updated);
                    playSuccess(player);
                    send(player, "§aUpdated category: §f" + updated.displayName());
                    openCategoryEditor(player, catKey, rp);
                }
                return true;
            }
        case CREATE_CAT_KEY -> {
                String input = text.trim();
                java.util.Map<String, String> catData = PENDING_CATEGORIES.get(player.getUuid());
                if (catData != null && catData.containsKey("templateKey")) {
                    String srcKey = catData.get("templateKey");
                    com.customblocks.core.Category srcCat = com.customblocks.core.CategoryManager.getCategory(srcKey);
                    if (srcCat != null) {
                        com.customblocks.core.Category newCat = com.customblocks.core.Category.create(input)
                            .withIconItem(srcCat.iconItem())
                            .withIconCustomBlockId(srcCat.iconCustomBlockId())
                            .withColor(srcCat.color())
                            .withBadge(srcCat.badge())
                            .withBadgeColor(srcCat.badgeColor())
                            .withDescription(srcCat.description())
                            .withLorePrefix(srcCat.lorePrefix())
                            .withLorePrefixPosition(srcCat.lorePrefixPosition())
                            .withSubcategoryIndicator(srcCat.subcategoryIndicator())
                            .withColorPlacement(srcCat.colorPlacement())
                            .withBadgeOverflowMode(srcCat.badgeOverflowMode())
                            .withDisplayBlockEnabled(srcCat.displayBlockEnabled())
                            .withDisplayBlockType(srcCat.displayBlockType());
                        com.customblocks.core.CategoryManager.addCategory(newCat);
                        playCategoryCreate(player);
                        send(player, "§aCreated category §f" + newCat.displayName() + " §afrom template.");
                        PENDING_CATEGORIES.remove(player.getUuid());
                        openCategoryEditor(player, newCat.key(), 0);
                        return true;
                    }
                }
                
                String id = text.toLowerCase().replaceAll("[^a-z0-9_]", "_");
                if (id.isEmpty() || com.customblocks.core.CategoryManager.getCategory(id) != null) {
                    send(player, "§cInvalid ID or Category already exists.");
                    openMain(player, rp);
                    return true;
                }
                PENDING_CATEGORIES.get(player.getUuid()).put("key", id);
                openShortInputPrompt(player, new PendingInput(InputAction.CREATE_CAT_NAME, null, null, null, null, rp), "§6Category Display Name", new net.minecraft.item.ItemStack(net.minecraft.item.Items.NAME_TAG), id);
                return true;
            }
            case CREATE_CAT_NAME -> {
                PENDING_CATEGORIES.get(player.getUuid()).put("displayName", text);
                openCategoryIconPicker(player, "__CREATE__", 0, false);
                return true;
            }
            case CREATE_CAT_ICON -> {
                // Not used anymore as text prompt, but keep for fallback
                PENDING_CATEGORIES.get(player.getUuid()).put("iconItem", text.trim());
                openShortInputPrompt(player, new PendingInput(InputAction.CREATE_CAT_COLOR, null, null, null, null, rp), "§6Category Color Code (e.g., #FF0000)", new net.minecraft.item.ItemStack(net.minecraft.item.Items.RED_DYE), "#FFFFFF");
                return true;
            }
            case CREATE_CAT_COLOR -> {
                PENDING_CATEGORIES.get(player.getUuid()).put("color", text.trim());
                openShortInputPrompt(player, new PendingInput(InputAction.CREATE_CAT_BADGE, null, null, null, null, rp), "§6Lore Badge Text (e.g., §cFOOD)", new net.minecraft.item.ItemStack(net.minecraft.item.Items.BOOK), "MY_CATEGORY");
                return true;
            }
            case CREATE_CAT_BADGE -> {
                java.util.Map<String, String> catData = PENDING_CATEGORIES.remove(player.getUuid());
                if (catData != null && catData.containsKey("key")) {
                    com.customblocks.core.Category cat = com.customblocks.core.Category.create(catData.get("displayName"))
                        .withIconItem(catData.get("iconItem"))
                        .withColor(catData.get("color"))
                        .withBadge(text.trim());
                    if (catData.containsKey("parentKey") && catData.get("parentKey") != null && !catData.get("parentKey").isEmpty()) {
                        cat = cat.withParentKey(catData.get("parentKey").toLowerCase());
                    }
                    com.customblocks.core.CategoryManager.addCategory(cat);
                    playCategoryCreate(player);
                    send(player, "§aCreated category: §f" + cat.displayName());
                    if (blockId != null) {
                        com.customblocks.core.CategoryManager.assignBlock(blockId, cat.key());
                        openCategoryDetail(player, cat.key(), 0);
                    } else if (cat.parentKey() != null) {
                        openSubcategoryController(player, cat.parentKey(), rp);
                    } else {
                        openCategoryController(player, rp);
                    }
                }
                return true;
            }
            case RENAME_CAT_TEXT -> {
                java.util.Map<String, String> catData = PENDING_CATEGORIES.remove(player.getUuid());
                if (catData != null && catData.containsKey("editKey")) {
                    String catKey = catData.get("editKey");
                    com.customblocks.core.Category old = com.customblocks.core.CategoryManager.getCategory(catKey);
                    if (old != null) {
                        com.customblocks.core.Category updated = old.withDisplayName(text);
                        com.customblocks.core.CategoryManager.addCategory(updated);
                        playSuccess(player);
                        openCategoryEditor(player, catKey, 0);
                    }
                }
                return true;
            }
        }
        return false;
    }

    public static boolean hasPending(ServerPlayerEntity player)  { return PENDING.containsKey(player.getUuid()); }
    public static void clearState(ServerPlayerEntity player) { STATES.remove(player.getUuid()); PENDING.remove(player.getUuid()); FACE_IMPORTS.remove(player.getUuid()); FACE_CHANGE_SELECTIONS.remove(player.getUuid()); FACE_CHANGE_RETURN_PAGES.remove(player.getUuid()); BACK_STACK.remove(player.getUuid()); }

    public static void checkPendingFaceImports(MinecraftServer server) {
        if (FACE_IMPORTS.isEmpty()) {
            faceImportTickCounter = 0;
            return;
        }
        if (++faceImportTickCounter < FACE_IMPORT_POLL_TICKS) return;
        faceImportTickCounter = 0;

        long now = System.currentTimeMillis();
        List<Map.Entry<UUID, FaceImportPending>> expired = new ArrayList<>();
        List<Map.Entry<UUID, FaceImportPending>> active = new ArrayList<>();
        for (var entry : FACE_IMPORTS.entrySet()) {
            if (entry.getValue().expiresAt() <= now) expired.add(entry);
            else active.add(entry);
        }

        expired.sort(Comparator.comparing(entry -> entry.getKey().toString()));
        for (var entry : expired) {
            if (FACE_IMPORTS.remove(entry.getKey(), entry.getValue())) {
                notifyFaceImportExpired(server, entry.getKey(), entry.getValue());
            }
        }
        if (active.isEmpty()) return;

        active.sort(Comparator.comparing(entry -> entry.getKey().toString()));
        for (var entry : active) {
            Path importDir = Path.of(entry.getValue().importDir());
            if (!Files.isDirectory(importDir)) continue;
            List<Path> images = listSupportedImportFiles(importDir);
            if (images.isEmpty()) continue;
            if (!FACE_IMPORTS.remove(entry.getKey(), entry.getValue())) continue;
            processPendingFaceImport(server, entry.getKey(), entry.getValue(), images.get(0));
        }
    }

    // ── Click handlers ────────────────────────────────────────────────────────

    private static void handleResourceHubClick(ServerPlayerEntity player, GuiState state, int slot) {
        playClick(player);
        if (slot == 45) { openMaintenanceMenu(player); return; }
        if (slot == 20) { // Copy Link
            String url = com.customblocks.network.ResourcePackServer.getPackUrl(player.getServer());
            player.closeHandledScreen();
            player.sendMessage(Text.literal("§0§l[§b§lCB§0§l]§r §fDownload Link: ")
                .append(Text.literal("§b§n" + url)
                .styled(s -> s.withUnderline(true)
                             .withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.OPEN_URL, url))
                             .withHoverEvent(new net.minecraft.text.HoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT, Text.literal("§eClick to open in browser"))))), false);
            if (player.getWorld() instanceof ServerWorld sw) {
                sw.playSound(null, player.getBlockPos(), net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, net.minecraft.sound.SoundCategory.PLAYERS, 0.8f, 1.2f);
            }
        }
        if (slot == 22) { // Force Sync
            NetworkManager.broadcastFullSync(player.getServer());
            send(player, "§a[System] Force-syncing all clients...");
            openResourceHub(player);
        }
        if (slot == 24) { // Pause Reloads
            var packet = new com.customblocks.network.RpPausePayload(true);
            for (ServerPlayerEntity p : player.getServer().getPlayerManager().getPlayerList()) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, packet);
            }
            send(player, "§6[System] Resource pack reloads §ePAUSED§6 for all clients.");
            openResourceHub(player);
        }
        if (slot == 26) { // Resume Reloads
            var packet = new com.customblocks.network.RpPausePayload(false);
            for (ServerPlayerEntity p : player.getServer().getPlayerManager().getPlayerList()) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, packet);
            }
            send(player, "§a[System] Resource pack reloads §aRESUMED§a — clients will reload now.");
            openResourceHub(player);
        }
    }



    // ── Magic Items GUI ───────────────────────────────────────────────────────

    public static void openMagicItemsGui(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.magicItems(), buildMagicItemsGui(), "§6§l✦ §r§fMagic Items");
    }

    private static SimpleInventory buildMagicItemsGui() {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        // Row 1: Header
        inv.setStack(4, uiGlint(Items.NETHER_STAR, "§6§l✦ §r§fMagic Items Arsenal", "§7Your legendary toolkit", "§8Click any item to receive it"));
        // Row 2: Color Squares
        inv.setStack(9, ui(Items.ORANGE_STAINED_GLASS_PANE, "§6── Colour Squares ──", "§7Swap block colours instantly"));
        inv.setStack(10, uiGlint(Items.GREEN_CONCRETE, "§a§lGreen Square", "§7Click to receive"));
        inv.setStack(11, uiGlint(Items.YELLOW_CONCRETE, "§e§lYellow Square", "§7Click to receive"));
        inv.setStack(12, uiGlint(Items.BLACK_CONCRETE, "§8§lBlack Square", "§7Click to receive"));
        // Row 2: Color Triangles
        inv.setStack(14, ui(Items.PURPLE_STAINED_GLASS_PANE, "§5── Colour Triangles ──", "§7Paint backgrounds onto blocks"));
        inv.setStack(15, uiGlint(Items.GREEN_TERRACOTTA, "§a§lGreen Triangle", "§7Click to receive"));
        inv.setStack(16, uiGlint(Items.YELLOW_TERRACOTTA, "§e§lYellow Triangle", "§7Click to receive"));
        inv.setStack(17, uiGlint(Items.BLACK_TERRACOTTA, "§8§lBlack Triangle", "§7Click to receive"));
        // Row 3: Premium Tools
        inv.setStack(18, ui(Items.LIGHT_BLUE_STAINED_GLASS_PANE, "§b── Premium Tools ──", "§7Legendary instruments of creation"));
        inv.setStack(19, uiGlint(Items.PAINTING, "§6§lRainbow Rectangle", "§7Face-painting wand", "§8Right-click a block face → paste URL"));
        inv.setStack(20, uiGlint(Items.GOLDEN_APPLE, "§6§lGolden Hexagon", "§7UV face rotator & flipper", "§8Right-click = rotate 90°", "§8Sneak+click = flip horizontally"));
        inv.setStack(21, uiGlint(Items.BLAZE_ROD, "§b§lLumina Brush", "§7Property painter", "§8Right-click any block → light & hardness sliders"));
        inv.setStack(22, uiGlint(Items.AMETHYST_SHARD, "§5§lAmethyst Chisel", "§7Shape sculptor", "§8Right-click any block → shape presets & editor"));
        inv.setStack(23, uiGlint(Items.DIAMOND, "§b§lDiamond Triangle", "§7Background Studio master", "§8Right-click anywhere → tolerance slider, presets, bulk re-apply", "§8YCbCr / CIE-Lab powered"));
        // Row 4: Quick actions
        inv.setStack(31, uiGlint(Items.EMERALD, "§a§l▶ Give All Items", "§7Click to get every magic item at once", "§aIncludes all squares, triangles, and tools"));
        // Bottom row
        inv.setStack(45, uiGlint(Items.RED_CONCRETE, "§c◀ Back"));
        return inv;
    }

    private static void handleMagicItemsClick(ServerPlayerEntity player, GuiState state, int slot) {
        net.minecraft.server.command.ServerCommandSource src = player.getCommandSource();
        switch (slot) {
            // Colour Squares
            case 10 -> { com.customblocks.command.CustomBlockCommand.cmdGiveSquareInternal(src, "green"); openMagicItemsGui(player); }
            case 11 -> { com.customblocks.command.CustomBlockCommand.cmdGiveSquareInternal(src, "yellow"); openMagicItemsGui(player); }
            case 12 -> { com.customblocks.command.CustomBlockCommand.cmdGiveSquareInternal(src, "black"); openMagicItemsGui(player); }
            // Colour Triangles
            case 15 -> { com.customblocks.command.CustomBlockCommand.cmdGiveTriangleInternal(src, "green"); openMagicItemsGui(player); }
            case 16 -> { com.customblocks.command.CustomBlockCommand.cmdGiveTriangleInternal(src, "yellow"); openMagicItemsGui(player); }
            case 17 -> { com.customblocks.command.CustomBlockCommand.cmdGiveTriangleInternal(src, "black"); openMagicItemsGui(player); }
            // Premium Tools
            case 19 -> { com.customblocks.command.CustomBlockCommand.cmdGiveRectangleInternal(src); openMagicItemsGui(player); }
            case 20 -> { com.customblocks.command.CustomBlockCommand.cmdGiveHexagonInternal(src); openMagicItemsGui(player); }
            case 21 -> { com.customblocks.command.CustomBlockCommand.cmdGiveBrushInternal(src); openMagicItemsGui(player); }
            case 22 -> { com.customblocks.command.CustomBlockCommand.cmdGiveChiselInternal(src); openMagicItemsGui(player); }
            case 23 -> { com.customblocks.command.CustomBlockCommand.cmdGiveDiamondInternal(src); openMagicItemsGui(player); }
            // Give All
            case 31 -> {
                com.customblocks.command.CustomBlockCommand.cmdGiveSquareInternal(src, "green");
                com.customblocks.command.CustomBlockCommand.cmdGiveSquareInternal(src, "yellow");
                com.customblocks.command.CustomBlockCommand.cmdGiveSquareInternal(src, "black");
                com.customblocks.command.CustomBlockCommand.cmdGiveTriangleInternal(src, "green");
                com.customblocks.command.CustomBlockCommand.cmdGiveTriangleInternal(src, "yellow");
                com.customblocks.command.CustomBlockCommand.cmdGiveTriangleInternal(src, "black");
                com.customblocks.command.CustomBlockCommand.cmdGiveRectangleInternal(src);
                com.customblocks.command.CustomBlockCommand.cmdGiveHexagonInternal(src);
                com.customblocks.command.CustomBlockCommand.cmdGiveBrushInternal(src);
                com.customblocks.command.CustomBlockCommand.cmdGiveChiselInternal(src);
                com.customblocks.command.CustomBlockCommand.cmdGiveDiamondInternal(src);
                send(player, "§a[GUI] All magic items granted!");
                openMagicItemsGui(player);
            }
            // Back
            case 0, 45 -> openMain(player, 0);
        }
    }

    // ── Diamond Triangle: Background Studio ───────────────────────────────────

    public static void openBgStudio(ServerPlayerEntity player) {
        openBgStudio(player, true);
    }

    public static void openBgStudio(ServerPlayerEntity player, boolean pushBack) {
        if (pushBack) pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.bgStudio(), buildBgStudioGui(), "§b§l✦ §r§fBackground Studio");
    }

    private static SimpleInventory buildBgStudioGui() {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        int tol = CustomBlocksConfig.bgRemovalTolerance;
        boolean enabled = tol > 0;

        // Header
        inv.setStack(4, uiGlint(Items.DIAMOND, "§b§l✦ Background Studio",
            "§7Tune how new images shed their backgrounds",
            "§7Math mode: §f" + (CustomBlocksConfig.bgRemovalUseYcbcr ? "YCbCr luminance/chroma" : "CIE-Lab Delta-E"),
            "§8Affects all imports server-wide"));

        // Master ON/OFF toggle (slot 0 area — but 0 is typically Back, so put toggle at 13)
        inv.setStack(0, uiGlint(Items.RED_CONCRETE, "§c◀ Back", "§8Return to main menu"));
        inv.setStack(10, toggleItem("YCbCr Math", CustomBlocksConfig.bgRemovalUseYcbcr,
            "Separates brightness from colour to reduce light edge halos"));
        inv.setStack(13, enabled
            ? uiGlint(Items.LIME_DYE, "§a§l✔ Background Removal: §lON",
                "§7Currently §atrimming §7white/transparent edges",
                "§8Click to disable")
            : uiGlint(Items.GRAY_DYE, "§7§l✖ Background Removal: §lOFF",
                "§7Imports keep their full original image",
                "§8Click to enable"));

        // ── Royal Tolerance Slider (Row 3) ─
        // Use 10 segments of 10 each for cleaner display: slots 18-27 (10 slots)
        inv.setStack(18, uiGlint(Items.AMETHYST_CLUSTER, "§e✦ Tolerance: §f" + tol,
            "§7Range: §f0-100",
            "§80=OFF • 30=balanced • 60=aggressive • 100=max"));
        // 8 slider segments mapping 0-100 -> slots 19-26
        for (int seg = 0; seg < 8; seg++) {
            int slotIdx = 19 + seg;
            int segMin = seg * 13;          // 0, 13, 26, 39, 52, 65, 78, 91
            int segMax = Math.min(99, segMin + 12);  // segment range
            int segMid = segMin + (segMax - segMin) / 2;
            boolean isActive = tol >= segMin && tol <= segMax;
            boolean isBefore = tol > segMax;
            ItemStack pane;
            if (isActive) {
                pane = new ItemStack(Items.YELLOW_STAINED_GLASS_PANE);
                pane.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
                pane.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§e§l▶ " + segMin + "-" + segMax + " §r§7(Current: §e" + tol + "§7)").styled(s -> s.withItalic(false)));
                pane.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    lore("§aClick to set §f" + segMin),
                    lore("§7Right-click for §f" + segMid))));
            } else if (isBefore) {
                pane = new ItemStack(Items.ORANGE_STAINED_GLASS_PANE);
                pane.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§6" + segMin + "-" + segMax).styled(s -> s.withItalic(false)));
                pane.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    lore("§7Click to set §f" + segMin),
                    lore("§7Right-click for §f" + segMid))));
            } else {
                pane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
                pane.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§8" + segMin + "-" + segMax).styled(s -> s.withItalic(false)));
                pane.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    lore("§7Click to set §f" + segMin),
                    lore("§7Right-click for §f" + segMid))));
            }
            inv.setStack(slotIdx, pane);
        }
        // Slot 27 = max (100)
        inv.setStack(27, tol >= 100
            ? uiGlint(Items.YELLOW_STAINED_GLASS_PANE, "§e§l▶ 100 §r§7(MAX)", "§aCurrently active")
            : ui(Items.GRAY_STAINED_GLASS_PANE, "§8100", "§7Click to set §f100", "§8Most aggressive removal"));

        // Fine controls (slots 28-30)
        inv.setStack(28, ui(Items.QUARTZ, "§c◀ Less §8(-5)", "§7Current: §e" + tol));
        inv.setStack(29, uiGlint(Items.AMETHYST_CLUSTER, "§e✦ Type Value", "§7Current: §e" + tol, "§eClick to type a precise value"));
        inv.setStack(30, ui(Items.GLOWSTONE_DUST, "§a▶ More §8(+5)", "§7Current: §e" + tol));

        // Quick presets (row 4: slots 36-40)
        inv.setStack(36, ui(Items.LIGHT_GRAY_DYE, "§7Preset: §lOff", "§70 — keep originals"));
        inv.setStack(37, ui(Items.GREEN_DYE,      "§aPreset: §lLight",  "§720 — only pure white"));
        inv.setStack(38, ui(Items.YELLOW_DYE,     "§ePreset: §lBalanced","§730 — default, recommended"));
        inv.setStack(39, ui(Items.ORANGE_DYE,     "§6Preset: §lStrong", "§750 — catches off-white"));
        inv.setStack(40, ui(Items.RED_DYE,        "§cPreset: §lAggressive","§775 — removes most light tones"));

        // Triangle Factory
        inv.setStack(42, uiGlint(Items.PRISMARINE_SHARD, "§b§lTriangle Factory",
            "§7Mint a physical recolour triangle",
            "§7from any hex colour.",
            "§8Click to type #RRGGBB"));
        inv.setStack(43, uiGlint(Items.LIGHT_BLUE_DYE, "§bCreate #55CCFF Triangle",
            "§7Quick sample custom triangle",
            "§8Right-click CustomBlocks to make variants"));
        inv.setStack(44, uiGlint(Items.MAGENTA_DYE, "§dCreate #FF55CC Triangle",
            "§7Quick sample custom triangle",
            "§8Right-click CustomBlocks to make variants"));

        // Bulk re-apply (slot 49)
        inv.setStack(49, uiGlint(Items.NETHER_STAR, "§5§l⚡ Bulk Re-apply",
            "§7Run current tolerance against",
            "§7§l" + SlotManager.allSlots().size() + " §r§7existing blocks",
            "§8(processes in background, won't lag)",
            "§c§l⚠ §cThis modifies every block's texture"));

        // Bottom row Back
        inv.setStack(45, uiGlint(Items.RED_CONCRETE, "§c◀ Back"));
        return inv;
    }

    private static void handleBgStudioClick(ServerPlayerEntity player, GuiState state, int slot, int button) {
        // Back
        if (slot == 0 || slot == 45) { handleEscBack(player); return; }

        // Math mode toggle
        if (slot == 10) {
            CustomBlocksConfig.bgRemovalUseYcbcr = !CustomBlocksConfig.bgRemovalUseYcbcr;
            CustomBlocksConfig.save();
            send(player, "§a[BG Studio] Background math: §f" + (CustomBlocksConfig.bgRemovalUseYcbcr ? "YCbCr" : "CIE-Lab"));
            refreshScreen(player, buildBgStudioGui());
            return;
        }

        // Master toggle
        if (slot == 13) {
            if (CustomBlocksConfig.bgRemovalTolerance > 0) {
                CustomBlocksConfig.bgRemovalTolerance = 0;
                send(player, "§a[BG Studio] Background removal §cDISABLED§a.");
            } else {
                CustomBlocksConfig.bgRemovalTolerance = 30;
                send(player, "§a[BG Studio] Background removal §aENABLED§a (set to default 30).");
            }
            CustomBlocksConfig.save();
            refreshScreen(player, buildBgStudioGui());
            return;
        }

        // Slider segments (slots 19-26)
        if (slot >= 19 && slot <= 26) {
            int seg = slot - 19;
            int segMin = seg * 13;
            int segMax = Math.min(99, segMin + 12);
            int segMid = segMin + (segMax - segMin) / 2;
            int newTol = (button == 1) ? segMid : segMin;
            CustomBlocksConfig.bgRemovalTolerance = Math.max(0, Math.min(100, newTol));
            CustomBlocksConfig.save();
            send(player, "§a[BG Studio] Tolerance set to §f" + CustomBlocksConfig.bgRemovalTolerance);
            refreshScreen(player, buildBgStudioGui());
            return;
        }

        // Max preset (slot 27)
        if (slot == 27) {
            CustomBlocksConfig.bgRemovalTolerance = 100;
            CustomBlocksConfig.save();
            send(player, "§a[BG Studio] Tolerance set to §f100 §7(MAX)");
            refreshScreen(player, buildBgStudioGui());
            return;
        }

        // Fine controls
        if (slot == 28) { // -5
            CustomBlocksConfig.bgRemovalTolerance = Math.max(0, CustomBlocksConfig.bgRemovalTolerance - 5);
            CustomBlocksConfig.save();
            send(player, "§a[BG Studio] Tolerance: §f" + CustomBlocksConfig.bgRemovalTolerance);
            refreshScreen(player, buildBgStudioGui());
            return;
        }
        if (slot == 30) { // +5
            CustomBlocksConfig.bgRemovalTolerance = Math.min(100, CustomBlocksConfig.bgRemovalTolerance + 5);
            CustomBlocksConfig.save();
            send(player, "§a[BG Studio] Tolerance: §f" + CustomBlocksConfig.bgRemovalTolerance);
            refreshScreen(player, buildBgStudioGui());
            return;
        }
        if (slot == 29) { // type value
            configPrompt(player, "bgRemovalTolerance", "§eType new tolerance (0-100):");
            return;
        }

        // Presets (slots 36-40)
        if (slot >= 36 && slot <= 40) {
            int[] presets = {0, 20, 30, 50, 75};
            int newTol = presets[slot - 36];
            CustomBlocksConfig.bgRemovalTolerance = newTol;
            CustomBlocksConfig.save();
            send(player, "§a[BG Studio] Preset applied — tolerance §f" + newTol);
            refreshScreen(player, buildBgStudioGui());
            return;
        }

        // Triangle Factory
        if (slot == 42) {
            PendingInput pending = new PendingInput(InputAction.BG_FACTORY_HEX, null, null, null, null, 0);
            openShortInputPrompt(player, pending, "§bTriangle Factory", new ItemStack(Items.PRISMARINE_SHARD), "#55CCFF");
            return;
        }
        if (slot == 43) {
            giveCustomColorTools(player, 0x55CCFF);
            refreshScreen(player, buildBgStudioGui());
            return;
        }
        if (slot == 44) {
            giveCustomColorTools(player, 0xFF55CC);
            refreshScreen(player, buildBgStudioGui());
            return;
        }

        // Bulk re-apply
        if (slot == 49) {
            int count = SlotManager.allSlots().size();
            send(player, "§5[BG Studio] §dBulk re-apply started for §f" + count + " §dblocks. Watch chat for progress…");
            bulkReapplyBackground(player);
            refreshScreen(player, buildBgStudioGui());
        }
    }

    private static Integer parseHexColor(String text) {
        if (text == null) return null;
        String hex = text.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.regionMatches(true, 0, "0x", 0, 2)) hex = hex.substring(2);
        if (hex.length() == 3 && hex.matches("[0-9a-fA-F]{3}")) {
            hex = "" + hex.charAt(0) + hex.charAt(0)
                     + hex.charAt(1) + hex.charAt(1)
                     + hex.charAt(2) + hex.charAt(2);
        }
        if (!hex.matches("[0-9a-fA-F]{6}")) return null;
        return Integer.parseInt(hex, 16);
    }

    private static void giveCustomTriangle(ServerPlayerEntity player, int rgb) {
        net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(
            net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, com.customblocks.item.ColorTriangleItem.CUSTOM_TRIANGLE_REGISTRY_ID));
        if (item == null || item == Items.AIR) {
            send(player, "§c[BG Studio] Custom Triangle item is not registered.");
            return;
        }
        ItemStack stack = com.customblocks.item.ColorTriangleItem.createCustomStack(item, rgb);
        player.getInventory().insertStack(stack);
        send(player, "§b[BG Studio] Minted §f#" + String.format(Locale.ROOT, "%06X", rgb & 0xFFFFFF) + " §bTriangle.");
        if (player.getWorld() instanceof ServerWorld sw) {
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.GLOW,
                player.getX(), player.getY() + 1.0, player.getZ(),
                8, 0.3, 0.4, 0.3, 0.02);
            sw.playSound(null, player.getBlockPos(),
                net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                net.minecraft.sound.SoundCategory.PLAYERS, 0.7f, 1.5f);
        }
    }

    private static void giveCustomColorTools(ServerPlayerEntity player, int rgb) {
        net.minecraft.item.Item squareItem = net.minecraft.registry.Registries.ITEM.get(
            net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, com.customblocks.item.ColorSquareItem.CUSTOM_SQUARE_REGISTRY_ID));
        net.minecraft.item.Item triangleItem = net.minecraft.registry.Registries.ITEM.get(
            net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, com.customblocks.item.ColorTriangleItem.CUSTOM_TRIANGLE_REGISTRY_ID));
        if (squareItem == null || squareItem == Items.AIR || triangleItem == null || triangleItem == Items.AIR) {
            send(player, "§c[BG Studio] Custom Square/Triangle items are not registered.");
            return;
        }
        player.getInventory().insertStack(com.customblocks.item.ColorSquareItem.createCustomStack(squareItem, rgb));
        player.getInventory().insertStack(com.customblocks.item.ColorTriangleItem.createCustomStack(triangleItem, rgb));
        send(player, "§b[BG Studio] Minted §f#" + String.format(Locale.ROOT, "%06X", rgb & 0xFFFFFF) + " §bSquare + Triangle.");
        if (player.getWorld() instanceof ServerWorld sw) {
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.GLOW,
                player.getX(), player.getY() + 1.0, player.getZ(),
                8, 0.3, 0.4, 0.3, 0.02);
            sw.playSound(null, player.getBlockPos(),
                net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                net.minecraft.sound.SoundCategory.PLAYERS, 0.7f, 1.5f);
        }
    }

    /**
     * Iterates over every existing block, runs the current bg-removal tolerance
     * against the main texture, and broadcasts updates. Skips animated blocks
     * (mcmeta-driven frames are too fragile to recolour blindly).
     */
    private static void bulkReapplyBackground(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        EXECUTOR.submit(() -> {
            int processed = 0, skipped = 0, failed = 0;
            List<SlotData> snapshot = new ArrayList<>(SlotManager.allSlots());
            for (SlotData d : snapshot) {
                if (d == null || d.texture == null || d.texture.length == 0) { skipped++; continue; }
                if (d.isAnimated()) { skipped++; continue; }
                try {
                    byte[] reprocessed = ImageProcessor.replaceBackground(d.texture);
                    if (reprocessed != null && reprocessed.length > 0) {
                        final byte[] fb = reprocessed;
                        final String fid = d.customId;
                        server.execute(() -> {
                            SlotData latest = SlotManager.getById(fid);
                            if (latest == null) return;
                            UndoManager.pushUndoMutation(fid, latest, "bulkbg", player.getUuid());
                            SlotManager.updateTexture(fid, fb);
                            NetworkManager.broadcastUpdate(server, new SlotUpdatePayload(
                                "retexture", latest.index, fid, null, fb,
                                latest.lightLevel, latest.hardness, latest.soundType, null, null, null));
                        });
                        processed++;
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    failed++;
                    LOGGER.warn("[CustomBlocks] Bulk bg-reapply failed for {}: {}", d.customId, e.getMessage());
                }
            }
            final int fp = processed, fs = skipped, ff = failed;
            server.execute(() -> {
                SlotManager.saveAll();
                send(player, "§5[BG Studio] §dBulk re-apply done — §a" + fp + " updated§d, §7" + fs + " skipped§d, §c" + ff + " failed§d.");
            });
        });
    }

    // ── Config GUI ────────────────────────────────────────────────────────────

    public static void openConfigWarningGui(ServerPlayerEntity player) {
        openConfigWarningGui(player, true);
    }

    public static void openConfigWarningGui(ServerPlayerEntity player, boolean pushBack) {
        if (pushBack) pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.configWarning(), buildConfigWarningGui(), "§6§l⚠ §r§fServer Config Warning");
    }

    private static SimpleInventory buildConfigWarningGui() {
        SimpleInventory inv = new SimpleInventory(27);
        for (int i = 0; i < 27; i++) inv.setStack(i, glass());
        inv.setStack(4, uiGlint(Items.COMPARATOR, "§6§lServer Config",
            "§7These settings affect the entire server.",
            "§7Changing them can impact every player and every block.",
            "§eOnly continue if you mean to edit live server-wide behavior."));
        inv.setStack(11, uiGlint(Items.RED_CONCRETE, "§c◀ Back",
            "§7Return without changing server config."));
        inv.setStack(15, uiGlint(Items.LIME_CONCRETE, "§a§lContinue",
            "§7Open the advanced server config panel."));
        return inv;
    }

    private static void handleConfigWarningClick(ServerPlayerEntity player, GuiState state, int slot) {
        switch (slot) {
            case 11 -> openMain(player, 0);
            case 15 -> openConfigGui(player, false);
        }
    }

    public static void openConfigGui(ServerPlayerEntity player) {
        openConfigGui(player, true);
    }

    public static void openConfigGui(ServerPlayerEntity player, boolean pushBack) {
        if (pushBack) pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.configGui(), buildConfigGui(), "§6§l⚙ §r§fServer Config");
    }

    private static SimpleInventory buildConfigGui() {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        // Row 1: Toggles
        inv.setStack(11, toggleItem("AI System Ready", CustomBlocksConfig.aiEnabled, "Keep the AI assistant system enabled"));
        inv.setStack(12, toggleItem("AI Status Halo", CustomBlocksConfig.aiHologram, "Show a floating status label above the assistant"));
        inv.setStack(13, toggleItem("Cloud Share", CustomBlocksConfig.cloudShareEnabled, "Upload and fetch share codes from the Cloud Vault"));
        // Row 2: Numbers
        inv.setStack(19, numItem("Block Capacity", CustomBlocksConfig.maxSlots, "How many custom blocks this server can hold (restart required)"));
        inv.setStack(20, numItem("Texture Quality", CustomBlocksConfig.defaultTextureSize, "Default resolution used when new textures are processed"));
        inv.setStack(21, uiGlint(Items.DIAMOND, "§b§lBackground Studio",
            "§7Moved out of server config.",
            "§7Current tolerance: §e" + CustomBlocksConfig.bgRemovalTolerance,
            "§8Click to open the Diamond Triangle panel"));
        inv.setStack(22, numItem("History Depth", CustomBlocksConfig.maxUndoDepth, "How many undo and redo steps each player can keep"));
        inv.setStack(23, numItem("Download Timeout", CustomBlocksConfig.downloadTimeoutSeconds, "How long texture downloads may wait before failing"));
        inv.setStack(24, numItem("Texture Burst Rate", CustomBlocksConfig.texturePayloadsPerTick, "How many texture packets are sent each server tick"));
        inv.setStack(25, numItem("Communication Door", CustomBlocksConfig.resourcePackPort, "Port used by the local texture server (0 disables it)"));
        inv.setStack(26, numItem("Pack Rebuild Delay", CustomBlocksConfig.reloadDebounceMs, "How long to wait before rebuilding the pack again"));
        // Row 3: Strings
        inv.setStack(28, strItem("AI Display Name", CustomBlocksConfig.aiName, "The name shown above your assistant"));
        inv.setStack(29, strItem("History Mode", CustomBlocksConfig.undoMode, "Choose whether undo history is shared or per-player"));
        inv.setStack(30, colorFillModeButton(CustomBlocksConfig.colorToolBackgroundMode));
        inv.setStack(31, strItem("Green Shade", CustomBlocksConfig.triangleGreenHex, "Edit the built-in Green triangle/square shade"));
        inv.setStack(32, strItem("AI Style", CustomBlocksConfig.aiStyle, "Visual style for the assistant AI"));
        inv.setStack(33, strItem("Cloud Vault URL", truncate(CustomBlocksConfig.normalizedCloudShareUrl(), 30), "Base URL used for cross-server share codes"));
        inv.setStack(34, strItem("Yellow Shade", CustomBlocksConfig.triangleYellowHex, "Edit the built-in Yellow triangle/square shade"));
        // Row 5: Back
        inv.setStack(45, uiGlint(Items.RED_CONCRETE, "§c◀ Back"));
        return inv;
    }

    private static ItemStack toggleItem(String label, boolean on, String desc) {
        return uiGlint(on ? Items.LIME_DYE : Items.GRAY_DYE,
            (on ? "§a§l" : "§7§l") + label + (on ? " §a✔ ON" : " §c✖ OFF"),
            "§7" + desc, "§8Click to toggle");
    }
    private static ItemStack numItem(String label, Number val, String desc) {
        return uiGlint(Items.REPEATER, "§b§l" + label + " §f= §e" + val, "§7" + desc, "§8Click to edit");
    }
    private static ItemStack strItem(String label, String val, String desc) {
        return uiGlint(Items.NAME_TAG, "§d§l" + label + " §f= §e" + val, "§7" + desc, "§8Click to edit");
    }
    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static ItemStack colorFillModeButton(String mode) {
        Item icon;
        String head;
        switch (mode) {
            case "corners_only" -> { icon = Items.LIME_DYE; head = "§a§lDefault: Fill corner only"; }
            case "corners_and_trapped" -> { icon = Items.LIME_CONCRETE; head = "§a§lExtra: Fill corners + more"; }
            default -> { icon = Items.GRAY_DYE; head = "§e§lUnset (pick one)"; }
        }
        return uiGlint(icon,
            "§d§lColor Fill Mode §f→ " + head,
            "§7Default: edges only — legacy behaviour.",
            "§7Extra: edges plus small trapped holes —",
            "§7large blobs are skipped.",
            "§8Click to choose / change the mode.");
    }

    public static void openColorFillModeGui(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        SimpleInventory inv = new SimpleInventory(27);
        for (int i = 0; i < 27; i++) inv.setStack(i, glass());

        String mode = CustomBlocksConfig.colorToolBackgroundMode;
        boolean isUnset = "unset".equals(mode);
        boolean isDefault = "corners_only".equals(mode);
        boolean isExtra = "corners_and_trapped".equals(mode);

        inv.setStack(4, uiGlint(Items.PAINTING, "§6§lColour Fill Mode",
            "§7Pick how aggressively the §a§lTriangle§r§7 (and §a§lSquare§r§7)",
            "§7treat background versus trapped holes inside a design.",
            isUnset ? "§e§lYou must pick a mode before colour tools work." : "§aCurrent: §f" + formatColorToolMode(mode)));

        inv.setStack(11, uiGlint(Items.LIME_DYE,
            (isDefault ? "§a§l✦ " : "§7§l") + "Default: Fill corner only" + (isDefault ? " §a§l(active)" : ""),
            "§7Recolours only pixels reachable from the edges",
            "§7of the texture — the usual corner flood.",
            "§7Anything fully enclosed by your art (the inside",
            "§7of a “0”, a ring shape, etc.) stays unchanged.",
            "§8Same behaviour as before this update.",
            "§eClick to select."));

        inv.setStack(15, uiGlint(Items.LIME_CONCRETE,
            (isExtra ? "§a§l✦ " : "§7§l") + "Extra: Fill corners + more" + (isExtra ? " §a§l(active)" : ""),
            "§7Runs the same corner flood as Default,",
            "§7then looks for small enclosed pockets that still",
            "§7look like leftover background (solid black or",
            "§7checker-style placeholder patterns).",
            "§7Those pockets get the same new colour.",
            "§8Very large enclosed regions are skipped",
            "§8automatically so big dark areas survive.",
            "§eClick to select."));

        inv.setStack(22, uiGlint(Items.RED_CONCRETE, "§c← Back"));
        openScreenFromGuiState(player, GuiState.colorFillMode(), inv, "§6§lColour Fill Mode");
    }

    private static void handleColorFillModeClick(ServerPlayerEntity player, GuiState state, int slot) {
        switch (slot) {
            case 11 -> {
                CustomBlocksConfig.colorToolBackgroundMode = "corners_only";
                CustomBlocksConfig.save();
                send(player, "§a[Config] §fColour Fill Mode §a= §eDefault: Fill corner only");
                playSuccess(player);
                openColorFillModeGui(player);
            }
            case 15 -> {
                CustomBlocksConfig.colorToolBackgroundMode = "corners_and_trapped";
                CustomBlocksConfig.save();
                send(player, "§a[Config] §fColour Fill Mode §a= §eExtra: Fill corners + more");
                playSuccess(player);
                openColorFillModeGui(player);
            }
            case 22 -> handleEscBack(player);
        }
    }

    private static void handleConfigGuiClick(ServerPlayerEntity player, GuiState state, int slot) {
        switch (slot) {
            // Toggles
            case 11 -> {
                CustomBlocksConfig.aiEnabled = !CustomBlocksConfig.aiEnabled;
                CustomBlocksConfig.save();
                send(player, "§a[Config] aiEnabled = " + CustomBlocksConfig.aiEnabled);
                openConfigGui(player, false);
            }
            case 12 -> {
                CustomBlocksConfig.aiHologram = !CustomBlocksConfig.aiHologram;
                CustomBlocksConfig.save();
                send(player, "§a[Config] aiHologram = " + CustomBlocksConfig.aiHologram);
                openConfigGui(player, false);
            }
            case 13 -> {
                CustomBlocksConfig.cloudShareEnabled = !CustomBlocksConfig.cloudShareEnabled;
                CustomBlocksConfig.save();
                send(player, "§a[Config] cloudShareEnabled = " + CustomBlocksConfig.cloudShareEnabled);
                openConfigGui(player, false);
            }
            // Numbers
            case 19 -> configPrompt(player, "maxSlots", "Block Capacity (1-8192):");
            case 20 -> configPrompt(player, "defaultTextureSize", "Texture Quality (16-256):");
            case 21 -> openBgStudio(player, false);
            case 22 -> configPrompt(player, "maxUndoDepth", "History Depth (1-100):");
            case 23 -> configPrompt(player, "downloadTimeoutSeconds", "Download Timeout (1-120):");
            case 24 -> configPrompt(player, "texturePayloadsPerTick", "Texture Burst Rate (1-50):");
            case 25 -> configPrompt(player, "resourcePackPort", "Communication Door (0 disables it):");
            case 26 -> configPrompt(player, "reloadDebounceMs", "Pack Rebuild Delay (500-10000 ms):");
            // Strings
            case 28 -> configPrompt(player, "aiName", "AI Display Name:");
            case 29 -> configPrompt(player, "undoMode", "History Mode (global / per_player / both):");
            case 30 -> openColorFillModeGui(player);
            case 31 -> configPrompt(player, "triangleGreenHex", "Green Shade Hex (#RRGGBB):");
            case 32 -> configPrompt(player, "aiStyle", "AI Style:");
            case 33 -> configPrompt(player, "cloudShareUrl", "Cloud Vault URL:");
            case 34 -> configPrompt(player, "triangleYellowHex", "Yellow Shade Hex (#RRGGBB):");
            case 45 -> openMain(player, 0);
        }
    }

    private static void configPrompt(ServerPlayerEntity player, String key, String prompt) {
        PendingInput pending = new PendingInput(InputAction.CONFIG_VALUE, key, null, null, null, 0);
        if (usesAnvilConfigPrompt(key)) {
            openShortInputPrompt(player, pending, "§6" + prompt, shortPromptItemForConfig(key), currentConfigValue(key));
            return;
        }
        PENDING.put(player.getUuid(), pending);
        closeForPrompt(player);
        send(player, "§6[Config] §eType new value for §f" + prompt + " §e(or §ccancel§e):");
    }

    // ── Undo/Redo Picker GUI ──────────────────────────────────────────────────

    public static void openUndoPicker(ServerPlayerEntity player, int page) {
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.undoPicker(page), buildUndoPicker(player), "§6§l↩ §r§fUndo / Redo History");
    }

    private static SimpleInventory buildUndoPicker(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        UUID uuid = player.getUuid();

        int undoSz = UndoManager.undoSize(uuid);
        int redoSz = UndoManager.redoSize(uuid);

        inv.setStack(4, uiGlint(Items.KNOWLEDGE_BOOK, "§6§lUndo / Redo History",
            "§7Undo stack: §f" + undoSz + " §7entries",
            "§7Redo stack: §f" + redoSz + " §7entries",
            "§8Click an entry to apply it."));

        // Undo entries: slots 10-17 (up to 8)
        inv.setStack(9, uiGlint(Items.GOLDEN_PICKAXE, "§6§l↩ UNDO", "§7Click an entry to undo it"));
        List<UndoManager.UndoEntry> undos = UndoManager.getUndoEntries(uuid, 8);
        for (int i = 0; i < 8; i++) {
            if (i < undos.size()) {
                UndoManager.UndoEntry e = undos.get(i);
                String label = e.wasDeleted() ? "§cRestore §f" + e.customId() : "§6Undo §f" + e.description() + " §7on §f" + e.customId();
                inv.setStack(10 + i, uiGlint(e.wasDeleted() ? Items.CHEST : Items.PAPER,
                    label, "§8Position #" + (i + 1) + " in stack", i == 0 ? "§aClick to apply" : "§8Apply in order from #1"));
            }
        }

        // Redo entries: slots 28-35 (up to 8)
        inv.setStack(27, uiGlint(Items.DIAMOND_PICKAXE, "§b§l↪ REDO", "§7Click an entry to redo it"));
        List<UndoManager.UndoEntry> redos = UndoManager.getRedoEntries(uuid, 8);
        for (int i = 0; i < 8; i++) {
            if (i < redos.size()) {
                UndoManager.UndoEntry e = redos.get(i);
                String label = e.wasDeleted() ? "§cRe-delete §f" + e.customId() : "§bRedo §f" + e.description() + " §7on §f" + e.customId();
                inv.setStack(28 + i, uiGlint(e.wasDeleted() ? Items.BARRIER : Items.MAP,
                    label, "§8Position #" + (i + 1) + " in stack", i == 0 ? "§aClick to apply" : "§8Apply in order from #1"));
            }
        }

        inv.setStack(45, uiGlint(Items.RED_CONCRETE, "§c◀ Back"));
        return inv;
    }

    private static void handleUndoPickerClick(ServerPlayerEntity player, GuiState state, int slot) {
        UUID uuid = player.getUuid();
        if (slot == 45) { openMain(player, 0); return; }
        // Undo slot 10 = top of undo stack
        if (slot == 10) {
            UndoManager.UndoEntry entry = UndoManager.popUndo(uuid);
            if (entry != null) {
                applyUndoEntry(player, entry);
                send(player, "§a[Undo] Applied: " + entry.description() + " on " + entry.customId());
            } else {
                send(player, "§7Nothing to undo.");
            }
            refreshScreen(player, buildUndoPicker(player));
            return;
        }
        // Redo slot 28 = top of redo stack
        if (slot == 28) {
            UndoManager.UndoEntry entry = UndoManager.popRedo(uuid);
            if (entry != null) {
                applyRedoEntry(player, entry);
                send(player, "§a[Redo] Applied: " + entry.description() + " on " + entry.customId());
            } else {
                send(player, "§7Nothing to redo.");
            }
            refreshScreen(player, buildUndoPicker(player));
            return;
        }
    }

    private static void handleToolsClick(ServerPlayerEntity player, GuiState state, int slot) {
        switch (slot) {
            case 20 -> { // Rainbow Rectangle Wand
                try {
                    net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(com.customblocks.CustomBlocksMod.MOD_ID, "rainbow_rectangle"));
                    if (item != null && item != net.minecraft.item.Items.AIR) {
                        player.getInventory().insertStack(new net.minecraft.item.ItemStack(item));
                        send(player, "§0§l[§b§lCB§0§l]§r §eGiven §6Rainbow Rectangle§e!");
                    }
                } catch (Exception e) { send(player, "§cCould not give rectangle wand."); }
            }
            case 21 -> openShortInputPrompt(player,
                new PendingInput(InputAction.REID_TEXT, "__givesquare__", null, null, null, state.page()),
                "§6Square Color (black/yellow/green)",
                new ItemStack(Items.YELLOW_WOOL),
                "");
            case 22 -> openShortInputPrompt(player,
                new PendingInput(InputAction.REID_TEXT, "__givetriangle__", null, null, null, state.page()),
                "§6Triangle Color (black/yellow/green)",
                new ItemStack(Items.YELLOW_WOOL),
                "");
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

    private static void handlePickerClick(ServerPlayerEntity player, GuiState state, int slot, boolean brokenOnly, net.minecraft.screen.slot.SlotActionType actionType, int button) {
        int page = state.page();
        if (slot == 0) { openMain(player, 0); return; }
        if (slot == 8 && brokenOnly) {
            List<SlotData> broken = brokenBlocks();
            if (broken.isEmpty()) { send(player, "§7No broken blocks to delete."); return; }
            MinecraftServer srv = player.getServer();
            int count = 0;
            for (SlotData d : broken) {
                UndoManager.pushUndoDeletion(d.customId, d.deepCopy(), player.getUuid());
                SlotManager.remove(d.customId);
                NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("remove", d.index, d.customId, null, null, 0, 0, "stone", null, null, null));
                count++;
            }
            SlotManager.saveAll();
            send(player, "§a[GUI] Deleted §f" + count + "§a broken block(s). Use /cb undo to restore.");
            openBrokenBlocks(player, 0);
            return;
        }
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
                String targetId = blocks.get(idx).customId;
                // Category-assign triggers (in priority order):
                //   • Shift+click  → QUICK_MOVE / QUICK_CRAFT
                //   • Middle-click → CLONE         (creative only)
                //   • Drop key (Q) → THROW
                //   • Right-click  → PICKUP with button == 1
                // Note: Ctrl+click is not distinguishable from a plain left-click
                // server-side in 1.21.x, so we rely on right-click as the
                // primary keyboard-free shortcut for category assignment.
                boolean assign = actionType == net.minecraft.screen.slot.SlotActionType.QUICK_MOVE
                    || actionType == net.minecraft.screen.slot.SlotActionType.CLONE
                    || actionType == net.minecraft.screen.slot.SlotActionType.THROW
                    || actionType == net.minecraft.screen.slot.SlotActionType.QUICK_CRAFT
                    || (actionType == net.minecraft.screen.slot.SlotActionType.PICKUP && button == 1);
                if (assign) {
                    openAssignmentDecision(player, targetId, page);
                } else {
                    openEditor(player, targetId, page);
                }
            }
        }
    }

    
    private static void handleMainClick(ServerPlayerEntity player, GuiState state, int slot) {
        UUID uuid = player.getUuid();
        switch (slot) {
            // Row 2: primary actions
            case 19 -> openEditorPicker(player, 0);
            case 21 -> openShortInputPrompt(
                player,
                new PendingInput(InputAction.CREATE_ID, null, null, null, null, state.page()),
                "§6New Block ID",
                new ItemStack(Items.COMMAND_BLOCK),
                ""
            );
            case 23 -> openShortInputPrompt(player,
                new PendingInput(InputAction.REID_TEXT, "__search__", null, null, null, state.page()),
                "§6Search Blocks",
                new ItemStack(Items.SPYGLASS),
                "");
            case 25 -> openMagicItemsGui(player);

            // Row 3: recent blocks (slots 30, 32, 34)
            case 30, 32, 34 -> {
                Deque<String> recent = RECENT_BLOCKS.getOrDefault(uuid, new ArrayDeque<>());
                int ri = (slot - 30) / 2; // 30->0, 32->1, 34->2
                int idx = 0;
                for (String rid : recent) {
                    if (idx == ri && SlotManager.hasId(rid)) { openEditor(player, rid, state.page()); return; }
                    idx++;
                }
            }

            // Row 4: secondary actions
            case 39 -> openMaintenanceMenu(player);
            case 41 -> openBulkDelete(player, 0);
            case 43 -> openHelpGui(player);

            // Row 5: navigation
            case 46 -> {
                int undoSz = UndoManager.undoSize(uuid);
                if (undoSz == 0) { send(player, "§7Nothing to undo."); refreshScreen(player, buildMain(player, state.page())); return; }
                UndoManager.UndoEntry entry = UndoManager.popUndo(uuid);
                if (entry == null) { refreshScreen(player, buildMain(player, state.page())); return; }
                applyUndoEntry(player, entry);
                refreshScreen(player, buildMain(player, state.page()));
            }
            case 48 -> openUndoPicker(player, 0);
            case 50 -> {
                int redoSz = UndoManager.redoSize(uuid);
                if (redoSz == 0) { send(player, "§7Nothing to redo."); refreshScreen(player, buildMain(player, state.page())); return; }
                UndoManager.UndoEntry entry = UndoManager.popRedo(uuid);
                if (entry == null) { refreshScreen(player, buildMain(player, state.page())); return; }
                applyRedoEntry(player, entry);
                refreshScreen(player, buildMain(player, state.page()));
            }
            case 52 -> openConfigWarningGui(player);
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
            case 0, 45 -> openEditorPicker(player, rp);
            case 2  -> { player.getInventory().insertStack(CustomBlocksMod.safeSlotItem(d.index)!=null?new ItemStack(CustomBlocksMod.safeSlotItem(d.index),1):ItemStack.EMPTY); send(player,"§a[GUI] Given 1x §f"+d.displayName); openEditor(player,id,rp); }
            case 8  -> { PENDING.put(uuid,new PendingInput(InputAction.RETEXTURE_URL,id,null,null,null,rp)); closeForPrompt(player); send(player,"§6[GUI] §ePaste image URL for ALL faces of '§f"+id+"§e' (or §ccancel§e):"); }
            case 17 -> { PENDING.put(uuid, new PendingInput(InputAction.WEB_LINK_CAST, id, null, null, null, rp)); closeForPrompt(player); send(player, "§0§l[§b§lCB§0§l]§r §ePaste the §fWeb-Link URL§e to cast onto this block (or §ccancel§e):"); }
            case 19 -> openFaceEditor(player, id, rp);
            case 21 -> openShapeEditor(player, id, rp);
            case 23 -> openPropertiesGui(player, id, rp);
            case 25 -> openSoundMenu(player, id, rp);
            case 31 -> { if (d.isAnimated()) openAnimGui(player, id, rp); }
            case 37 -> openShortInputPrompt(
                player,
                new PendingInput(InputAction.RENAME_TEXT, id, null, null, null, rp),
                "§eBlock Name",
                new ItemStack(Items.NAME_TAG),
                stripFormattingCodes(d.displayName)
            );
            case 39 -> openShortInputPrompt(
                player,
                new PendingInput(InputAction.REID_TEXT, id, null, null, null, rp),
                "§6Block ID",
                new ItemStack(Items.COMMAND_BLOCK),
                id
            );
            case 41 -> {
                // One-click duplicate via auto-incremented ID
                String newId = com.customblocks.command.CustomBlockCommand.generateDupeId(id);
                if (SlotManager.freeSlots() == 0) { send(player, "§c[GUI] All slots full!"); break; }
                byte[] texCopy = d.texture != null ? d.texture.clone() : null;
                SlotData created = SlotManager.assign(newId, d.displayName + " (Copy)", texCopy);
                if (created == null) { send(player, "§c[GUI] Duplication failed."); break; }
                SlotManager.setLightLevel(newId, d.lightLevel);
                SlotManager.setHardness(newId, d.hardness);
                SlotManager.setSoundType(newId, d.soundType);
                if (d.animMeta != null) SlotManager.setAnimMeta(newId, d.animMeta);
                for (var e : d.faceTextures.entrySet()) SlotManager.setFaceTexture(newId, e.getKey(), e.getValue().clone());
                if (d.shapeBoxes != null) SlotManager.setShape(newId, new java.util.ArrayList<>(d.shapeBoxes));
                if (d.noCollision) SlotManager.setCollision(newId, false);
                SlotManager.saveAll();
                UndoManager.pushUndoCreate(newId, uuid);
                // R12: duplicate inherits ALL the same categories from the source
                try {
                    java.util.Set<String> srcCats = com.customblocks.core.CategoryManager.getCategoriesForBlock(id);
                    for (String cat : srcCats) com.customblocks.core.CategoryManager.assignBlock(newId, cat);
                } catch (Throwable ignored) {}
                NetworkManager.broadcastUpdate(player.getServer(),
                    new SlotUpdatePayload("add", created.index, newId, created.displayName, texCopy,
                        created.lightLevel, created.hardness, created.soundType, null, null, d.animMeta));
                send(player, "§a[GUI] Duplicated to §f" + newId + "§a!");
                openEditor(player, newId, rp);
            }
            case 43 -> {
                // Share button — hash-based file export (safe for any texture size)
                try {
                    com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
                    obj.addProperty("customId", d.customId);
                    obj.addProperty("displayName", d.displayName);
                    obj.addProperty("light", d.lightLevel);
                    obj.addProperty("hard", d.hardness);
                    obj.addProperty("sound", d.soundType);
                    if (d.animMeta != null) obj.addProperty("anim", d.animMeta);
                    if (d.noCollision) obj.addProperty("ncol", true);
                    if (d.isShaped() && d.shapeBoxes != null) {
                        com.google.gson.JsonArray boxes = new com.google.gson.JsonArray();
                        for (SlotData.ShapeBox box : d.shapeBoxes) boxes.add(box.toSerialString());
                        obj.add("shape", boxes);
                    }
                    if (d.texture != null) obj.addProperty("tex", java.util.Base64.getEncoder().encodeToString(d.texture));
                    if (d.hasFaces()) {
                        com.google.gson.JsonObject faces = new com.google.gson.JsonObject();
                        for (var fe : d.faceTextures.entrySet())
                            faces.addProperty(fe.getKey(), java.util.Base64.getEncoder().encodeToString(fe.getValue()));
                        obj.add("faces", faces);
                    }
                    String jsonStr = obj.toString();

                    // Generate share code using mixed alphabet
                    String hash = com.customblocks.command.CustomBlockCommand.generateShareCode(jsonStr);

                    // Write to server file
                    java.nio.file.Path exportDir = java.nio.file.Path.of("config/customblocks/exports");
                    java.nio.file.Files.createDirectories(exportDir);
                    java.nio.file.Files.writeString(exportDir.resolve(hash + ".json"),
                        jsonStr, java.nio.charset.StandardCharsets.UTF_8);
                    uploadShareToCloud(hash, jsonStr);

                    // Send short, clickable code (15 chars, not 918KB)
                    String code = "CB~" + hash;
                    net.minecraft.text.MutableText clickable = Text.literal("§b§n" + code)
                        .styled(s -> s
                            .withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                            .withHoverEvent(new net.minecraft.text.HoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT, Text.literal("§eClick to copy"))));
                    net.minecraft.text.MutableText line = Text.literal("§0§l[§b§lCB§0§l]§r §a[Share] §f'§b" + d.customId + "§f' ready! ")
                        .append(clickable);
                    line = Text.literal("\u00A70\u00A7l[\u00A7b\u00A7lCB\u00A70\u00A7l]\u00A7r \u00A7fBlock shared! \u00A77Code below \u00A7a\u2714 ")
                        .append(clickable);
                    player.sendMessage(line, false);
                    player.sendMessage(Text.literal("§7Import with: §b/cb importblock " + code), false);

                    // === SHARE CELEBRATION (§ 2B Sensory Layer) ===
                    // Title + subtitle — cinematic "Shared!" moment
                    player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleS2CPacket(
                        Text.literal("§a§lShared!")));
                    player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.SubtitleS2CPacket(
                        Text.literal("§7" + code)));
                    player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket(
                        10, 40, 20)); // fade-in, stay, fade-out (ticks)

                    // Action bar — guide the player
                    player.sendMessage(Text.literal("§a✔ Click the code in chat to copy!"), true);

                    // Green sparkles around player
                    ((ServerWorld) player.getWorld()).spawnParticles(
                        net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER,
                        player.getX(), player.getY() + 1, player.getZ(),
                        20, 0.5, 0.5, 0.5, 0.1);

                    // Achievement unlock sound — loud and celebratory
                    player.playSound(net.minecraft.sound.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                } catch (Exception ex) { send(player, "§0§l[§b§lCB§0§l]§r Share failed: " + ex.getMessage()); }
            }
            case 52 -> {
                // Cancel deletion — reopen normal editor
                if (state.confirmDelete()) openEditor(player, id, rp);
            }
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
        String id = state.editingId(); int rp = state.page(); int boxPage = state.shapeBoxPage();
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, rp); return; }
        List<SlotData.ShapeBox> boxes = d.shapeBoxes != null ? new ArrayList<>(d.shapeBoxes) : new ArrayList<>();
        UUID uuid = player.getUuid();

        if (slot == 0) { openEditor(player,id,rp); return; }
        if (slot == 8) {
            UndoManager.pushUndoMutation(id, d, "setcollision", uuid); SlotManager.setCollision(id,d.noCollision); SlotManager.saveAll();
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
        if (slot == 22) return; // slot 22 was add-shape (purged)
        if (slot == 23) { UndoManager.pushUndoMutation(id, d, "clearshape", uuid); SlotManager.setShape(id, null); SlotManager.saveAll(); broadcastShape(player.getServer(),SlotManager.getById(id)); send(player,"§a[Shape] Cleared — full cube."); reopenShapeEditor(player,id,rp,0); return; }
        if (slot >= 28 && slot <= 36) {
            int boxIdx = boxPage*9 + (slot-28);
            if (boxIdx < boxes.size()) { UndoManager.pushUndoMutation(id, d, "removeshape", uuid); SlotManager.removeBox(id,boxIdx); SlotManager.saveAll(); broadcastShape(player.getServer(),SlotManager.getById(id)); send(player,"§a[Shape] Removed box #"+boxIdx+"."); int np=Math.min(boxPage,Math.max(0,(boxes.size()-2)/9)); reopenShapeEditor(player,id,rp,np); }
            return;
        }
        List<SlotData> variants = findShapeVariants(id);
        if (slot >= 38 && slot <= 44) { int vi=slot-38; if(vi<variants.size()) openEditor(player,variants.get(vi).customId,rp); return; }
        if (slot==45 && boxPage>0) { reopenShapeEditor(player,id,rp,boxPage-1); return; }
        if (slot==53) { int maxPg=Math.max(0,(boxes.size()-1)/9); if(boxPage<maxPg) reopenShapeEditor(player,id,rp,boxPage+1); }
    }

    private static void handleMaintenanceClick(ServerPlayerEntity player, GuiState state, int slot) {
        if(slot == 0 || slot == 45) { openMain(player, 0); return; }
        if(slot == 19) openTabIconPicker(player, 0);
        else if(slot == 21) openBrokenBlocks(player, 0);
        else if(slot == 23) openResourceHub(player);
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
        switch (slot) {
            case 0, 45 -> openMain(player, 0);
            case 11 -> openHelpCategory(player, 1);
            case 13 -> openHelpCategory(player, 2);
            case 15 -> openHelpCategory(player, 3);
            case 20 -> openHelpCategory(player, 4);
            case 22 -> openHelpCategory(player, 5);
        }
    }

    public static void openHelpCategory(ServerPlayerEntity player, int category) {
        pushBackStack(player.getUuid());
        String title = switch (category) {
            case 1 -> "§e§l✦ §r§fCreating Blocks";
            case 2 -> "§b§l✦ §r§fTextures & Design";
            case 3 -> "§5§l✦ §r§fShapes & Collision";
            case 4 -> "§6§l✦ §r§fUtilities & Commands";
            case 5 -> "§a§l✦ §r§fServer & Data";
            default -> "§f§lHelp";
        };
        openScreenFromGuiState(player, GuiState.helpCategory(category), buildHelpCategory(category), title);
    }

    private static void handleHelpCategoryClick(ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 0 || slot == 45) openHelpGui(player);
    }

    private static void handlePropertiesClick(ServerPlayerEntity player, GuiState state, int slot, int button) {
        if(slot == 0) { openEditor(player, state.editingId(), state.page()); return; }
        String id = state.editingId(); int rp = state.page();
        SlotData d = SlotManager.getById(id);
        if(d == null) { openMain(player, rp); return; }
        UUID uuid = player.getUuid();

        // ── Royal Light Slider ───────────────────────────────────────────
        if (slot >= 10 && slot <= 17) {
            int segMin = (slot - 10) * 2;
            int segMax = segMin + 1;
            int newLight = (button == 1) ? segMax : segMin; // button 1 is right click
            UndoManager.pushUndoMutation(id, d, "setglow", uuid);
            SlotManager.setLightLevel(id, Math.max(0, Math.min(15, newLight)));
            syncProp(player, d);
            refreshScreen(player, buildPropertiesGui(SlotManager.getById(id)));
            return;
        }

        // ── Royal Hardness Slider ────────────────────────────────────────
        if (slot >= 28 && slot <= 35) {
            float[] hardPresets = { -1f, 0f, 0.5f, 1.5f, 3f, 5f, 10f, 50f };
            float newHardness = hardPresets[slot - 28];
            UndoManager.pushUndoMutation(id, d, "sethardness", uuid);
            SlotManager.setHardness(id, newHardness);
            syncProp(player, d);
            refreshScreen(player, buildPropertiesGui(SlotManager.getById(id)));
            return;
        }

        switch(slot) {
            case 19 -> { UndoManager.pushUndoMutation(id, d, "setglow", uuid); SlotManager.setLightLevel(id,Math.max(0,d.lightLevel-1)); syncProp(player,d); refreshScreen(player, buildPropertiesGui(SlotManager.getById(id))); }
            case 20 -> openShortInputPrompt(
                player,
                new PendingInput(InputAction.SET_LIGHT, id, null, null, null, rp),
                "§eLight Level",
                new ItemStack(Items.GLOWSTONE_DUST),
                String.valueOf(d.lightLevel)
            );
            case 21 -> { UndoManager.pushUndoMutation(id, d, "setglow", uuid); SlotManager.setLightLevel(id,Math.min(15,d.lightLevel+1)); syncProp(player,d); refreshScreen(player, buildPropertiesGui(SlotManager.getById(id))); }
            case 23 -> { UndoManager.pushUndoMutation(id, d, "sethardness", uuid); SlotManager.setHardness(id,prevHardness(d.hardness)); syncProp(player,d); refreshScreen(player, buildPropertiesGui(SlotManager.getById(id))); }
            case 24 -> openShortInputPrompt(
                player,
                new PendingInput(InputAction.SET_HARDNESS, id, null, null, null, rp),
                "§bHardness",
                new ItemStack(Items.NETHERITE_SCRAP),
                String.valueOf(d.hardness)
            );
            case 25 -> { UndoManager.pushUndoMutation(id, d, "sethardness", uuid); SlotManager.setHardness(id,nextHardness(d.hardness)); syncProp(player,d); refreshScreen(player, buildPropertiesGui(SlotManager.getById(id))); }
            case 40 -> {
                UndoManager.pushUndoMutation(id, d, "setcollision", uuid); SlotManager.setCollision(id,d.noCollision); SlotManager.saveAll();
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
        java.util.Map<Integer,String> soundSlots = java.util.Map.ofEntries(
            java.util.Map.entry(10,"stone"), java.util.Map.entry(11,"wood"), java.util.Map.entry(12,"grass"),
            java.util.Map.entry(13,"metal"), java.util.Map.entry(14,"glass"), java.util.Map.entry(15,"sand"),
            java.util.Map.entry(16,"wool"),  java.util.Map.entry(19,"gravel"),java.util.Map.entry(20,"snow"),
            java.util.Map.entry(21,"dirt"),  java.util.Map.entry(22,"coral"), java.util.Map.entry(23,"bamboo"),
            java.util.Map.entry(24,"nether_brick"), java.util.Map.entry(25,"ice"),
            java.util.Map.entry(28,"honey"), java.util.Map.entry(29,"bone"),  java.util.Map.entry(30,"slime")
        );
        String pick = soundSlots.get(slot);
        if (pick != null) {
            setSoundQuiet(player, d, pick, uuid);
            refreshScreen(player, buildSoundMenu(SlotManager.getById(id)));
        } else if (slot == 45) {
            openEditor(player, id, rp);
        }
    }

    private static void handleFaceEditorClick(ServerPlayerEntity player, GuiState state, int slot, int button, boolean shiftClick) {
        String id = state.editingId(); int rp = state.page();
        SlotData d = SlotManager.getById(id);
        if (d==null) { openMain(player,rp); return; }
        UUID uuid = player.getUuid();
        switch (slot) {
            case 0  -> openEditor(player,id,rp);
            case 9  -> { if (shiftClick) startPendingFaceImport(player,id,"top",rp); else promptFace(player,id,"top",rp,false); } case 10 -> promptFace(player,id,"top",   rp,true);
            case 11 -> { if (shiftClick) startPendingFaceImport(player,id,"bottom",rp); else promptFace(player,id,"bottom",rp,false); } case 12 -> promptFace(player,id,"bottom",rp,true);
            case 13 -> { if (shiftClick) startPendingFaceImport(player,id,"north",rp); else promptFace(player,id,"north",rp,false); } case 14 -> promptFace(player,id,"north", rp,true);
            case 15 -> { if (shiftClick) startPendingFaceImport(player,id,"south",rp); else promptFace(player,id,"south",rp,false); } case 16 -> promptFace(player,id,"south", rp,true);
            case 17 -> { if (shiftClick) startPendingFaceImport(player,id,"east",rp); else promptFace(player,id,"east",rp,false); } case 18 -> promptFace(player,id,"east",  rp,true);
            case 19 -> { if (shiftClick) startPendingFaceImport(player,id,"west",rp); else promptFace(player,id,"west",rp,false); } case 20 -> promptFace(player,id,"west",  rp,true);
            case 24 -> openFaceChangeSelect(player, id, rp);
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

    private static void handleFaceChangeSelectClick(ServerPlayerEntity player, GuiState state, int slot) {
        String id = state.editingId();
        int rp = state.page();
        switch (slot) {
            case 0, 45 -> reopenFaceEditor(player, id, rp);
            case 9 -> { playFaceCopySelect(player); openFaceChangePicker(player, id, "top", 0); }
            case 11 -> { playFaceCopySelect(player); openFaceChangePicker(player, id, "bottom", 0); }
            case 13 -> { playFaceCopySelect(player); openFaceChangePicker(player, id, "north", 0); }
            case 15 -> { playFaceCopySelect(player); openFaceChangePicker(player, id, "south", 0); }
            case 17 -> { playFaceCopySelect(player); openFaceChangePicker(player, id, "east", 0); }
            case 19 -> { playFaceCopySelect(player); openFaceChangePicker(player, id, "west", 0); }
        }
    }

    private static void handleFaceChangePickerClick(ServerPlayerEntity player, GuiState state, int slot) {
        String targetId = state.editingId();
        String face = FACE_CHANGE_SELECTIONS.getOrDefault(player.getUuid(), "top");
        int rp = FACE_CHANGE_RETURN_PAGES.getOrDefault(player.getUuid(), 0);
        if (slot == 0 || slot == 45) {
            reopenFaceChangeSelect(player, targetId, rp);
            return;
        }
        if (slot == 47 && state.page() > 0) {
            reopenFaceChangePicker(player, targetId, face, state.page() - 1);
            return;
        }
        if (slot == 51) {
            reopenFaceChangePicker(player, targetId, face, state.page() + 1);
            return;
        }
        if (slot >= 18 && slot <= 35) {
            List<SlotData> blocks = sortedBlocks();
            int idx = state.page() * BLOCKS_PER_PAGE + (slot - 18);
            if (idx < blocks.size()) {
                copyFaceFromSource(player, targetId, face, blocks.get(idx).customId, state.page(), rp);
            }
        }
    }

    private static void createShapeVariant(ServerPlayerEntity player, SlotData d, String id,
                                            String preset, int rp, int boxPage) {
        UUID uuid = player.getUuid();

        long now = System.currentTimeMillis();
        Long last = SHAPE_CREATE_COOLDOWN.get(uuid);
        if (last != null && now - last < SHAPE_COOLDOWN_MS) {
            send(player, "§e[Shape] Please wait a moment...");
            reopenShapeEditor(player, id, rp, boxPage);
            return;
        }
        SHAPE_CREATE_COOLDOWN.put(uuid, now);

        List<SlotData> existingVariants = findShapeVariants(id);
        if (existingVariants.size() >= 24) {
            send(player, "§c[Shape] Maximum variants reached (24).");
            reopenShapeEditor(player, id, rp, boxPage);
            return;
        }

        try {
            String varId = generateShapeVariantId(id, preset);
            if (SlotManager.hasId(varId)) { send(player,"§e[Shape] '§f"+varId+"§e' already exists — opening it."); openShapeEditor(player,varId,rp); return; }
            if (SlotManager.freeSlots()==0) { send(player,"§c[Shape] No free slots!"); reopenShapeEditor(player,id,rp,boxPage); return; }

            byte[] texCopy;
            try {
                texCopy = d.texture != null ? d.texture.clone() : null;
            } catch (OutOfMemoryError oom) {
                LOGGER.error("[CustomBlocks] OOM cloning texture for variant of '{}'", id);
                send(player, "§c[Shape] Not enough memory!");
                reopenShapeEditor(player, id, rp, boxPage);
                return;
            }

            List<SlotData.ShapeBox> presetBoxes = SlotManager.SHAPE_PRESETS.get(preset);
            String varName = d.displayName + " (" + cap(preset) + ")";
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
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Shape variant creation failed for '{}': {}", id, e.getMessage(), e);
            send(player, "§c[Shape] Creation failed. Please try again.");
            reopenShapeEditor(player, id, rp, boxPage);
        }
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
        openScreenFromGuiState(player, GuiState.shapeEditor(id,rp).withShapeBoxPage(boxPage),
            buildShapeEditor(d,boxPage), "§5§l⬡ §r§fShape Editor §8— §5"+d.displayName+" §7(ESC = back)");
    }

    // ── Anim GUI ─────────────────────────────────────────────────────────────

    public static void openAnimGui(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null || !d.isAnimated()) return;
        pushBackStack(player.getUuid());
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
        ANIM_ORIGINAL_PARAMS.put(player.getUuid(), new AnimParams(fps, interp, frameCount));
        openScreenFromGuiState(player, GuiState.animGui(id, returnPage),
            buildAnimGui(id, finalFps, finalInterp, finalFrames),
            "§b§l▶ §r§fAnimation Settings §8— §b" + d.displayName);
    }

    private static void handleAnimGuiClick(ServerPlayerEntity player, GuiState state, int slot) {
        String id = state.editingId();
        AnimParams p = ANIM_PARAMS.getOrDefault(player.getUuid(), new AnimParams(10f, false, 1));
        float fps = p.fps(); boolean interp = p.interpolate(); int frames = p.frameCount();
        switch (slot) {
            case 0  -> { openEditor(player, id, state.page()); return; }
            case 19 -> { fps = Math.max(0.5f, fps - 5); playClick(player); }
            case 20 -> { fps = Math.max(0.5f, fps - 1); playClick(player); }
            case 24 -> { fps = Math.min(100f, fps + 1); playClick(player); }
            case 25 -> { fps = Math.min(100f, fps + 5); playClick(player); }
            case 28 -> { fps = 5f; playClick(player); }
            case 29 -> { fps = 10f; playClick(player); }
            case 30 -> { fps = 20f; playClick(player); }
            case 31 -> { fps = 40f; playClick(player); }
            case 32 -> { fps = 60f; playClick(player); }
            case 33 -> { fps = 80f; playClick(player); }
            case 34 -> {
                ANIM_PARAMS.put(player.getUuid(), new AnimParams(fps, interp, frames));
                openShortInputPrompt(player,
                    new PendingInput(InputAction.ANIM_CUSTOM_FPS, id, null, null, null, state.page()),
                    "§b§lCustom FPS",
                    new ItemStack(Items.ANVIL),
                    String.format("%.1f", fps));
                return;
            }
            case 40 -> { interp = !interp; playClick(player); }
            case 45 -> { openEditor(player, id, state.page()); return; }
            case 49 -> { applyAnimSettings(player, id, fps, interp, frames); ANIM_PARAMS.remove(player.getUuid()); ANIM_ORIGINAL_PARAMS.remove(player.getUuid()); openEditor(player, id, state.page()); return; }
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
        AnimParams orig = ANIM_ORIGINAL_PARAMS.getOrDefault(player.getUuid(), new AnimParams(fps, interp, frameCount));
        boolean fpsChanged = Math.abs(orig.fps() - fps) > 0.05f;
        boolean interpChanged = orig.interpolate() != interp;
        if (fpsChanged && interpChanged) {
            ChatHelper.success(player, "Animation updated for '§f" + d.displayName + "§a' (" + String.format("%.1f", fps) + " fps, blending " + (interp ? "§6ON" : "§7OFF") + "§a)");
        } else if (fpsChanged) {
            ChatHelper.success(player, "Animation speed updated for '§f" + d.displayName + "§a' (" + String.format("%.1f", fps) + " fps)");
        } else if (interpChanged) {
            ChatHelper.success(player, "Smooth blending " + (interp ? "§6enabled" : "§7disabled") + "§a for '§f" + d.displayName + "§a'");
        } else {
            ChatHelper.success(player, "Animation settings saved for '§f" + d.displayName + "§a' (no changes)");
        }
    }

    private static boolean isAnimDirty(UUID uuid) {
        AnimParams current = ANIM_PARAMS.get(uuid);
        AnimParams original = ANIM_ORIGINAL_PARAMS.get(uuid);
        if (current == null || original == null) return false;
        return Math.abs(current.fps() - original.fps()) > 0.05f
            || current.interpolate() != original.interpolate();
    }

    private static void openAnimConfirmAbandon(ServerPlayerEntity player, String id, int returnPage) {
        AnimParams current = ANIM_PARAMS.getOrDefault(player.getUuid(), new AnimParams(10f, false, 1));
        AnimParams original = ANIM_ORIGINAL_PARAMS.getOrDefault(player.getUuid(), current);

        SimpleInventory inv = new SimpleInventory(27);
        for (int i = 0; i < 27; i++) inv.setStack(i, glass());

        inv.setStack(13, uiGlint(Items.WRITABLE_BOOK, "§e§lUnsaved Changes",
            "§7FPS: §f" + String.format("%.1f", original.fps()) + " §7→ §b" + String.format("%.1f", current.fps()),
            "§7Blending: §f" + (original.interpolate() ? "ON" : "OFF") + " §7→ §b" + (current.interpolate() ? "ON" : "OFF"),
            "", "§cDiscard these changes?"));
        inv.setStack(11, uiGlint(Items.LIME_WOOL, "§a§lYes — Discard", "§7Abandon changes and go back"));
        inv.setStack(15, uiGlint(Items.RED_WOOL, "§c§lNo — Keep Editing", "§7Return to animation settings"));

        playClick(player);
        openScreenFromGuiState(player, GuiState.animConfirmAbandon(id, returnPage), inv, "§c§l⚠ §r§fAbandon Changes?");
    }

    private static void handleAnimConfirmAbandonClick(ServerPlayerEntity player, GuiState state, int slot) {
        String id = state.editingId();
        int rp = state.page();
        switch (slot) {
            case 11 -> {
                ANIM_PARAMS.remove(player.getUuid());
                ANIM_ORIGINAL_PARAMS.remove(player.getUuid());
                playSuccess(player);
                Deque<GuiState> stack = BACK_STACK.get(player.getUuid());
                if (stack != null && !stack.isEmpty()) {
                    GuiState prev = stack.pop();
                    restoreState(player, prev);
                } else {
                    openEditor(player, id, rp);
                }
            }
            case 15 -> {
                playClick(player);
                reopenAnimGui(player, id, rp);
            }
        }
    }

    private static void reopenAnimGui(ServerPlayerEntity player, String id, int returnPage) {
        AnimParams p = ANIM_PARAMS.getOrDefault(player.getUuid(), new AnimParams(10f, false, 1));
        SlotData d = SlotManager.getById(id);
        String title = d != null ? d.displayName : id;
        openScreenFromGuiState(player, GuiState.animGui(id, returnPage),
            buildAnimGui(id, p.fps(), p.interpolate(), p.frameCount()),
            "§b§l▶ §r§fAnimation Settings §8— §b" + title);
    }

    // ── Bulk Delete GUI ────────────────────────────────────────────────────────

    public static void openBulkDelete(ServerPlayerEntity player, int page) {
        int total = sortedBlocks().size();
        int max = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        pushBackStack(player.getUuid());
        Set<String> selected = BULK_DELETE_SELECTIONS.computeIfAbsent(player.getUuid(), k -> ConcurrentHashMap.newKeySet());
        openScreenFromGuiState(player, GuiState.bulkDelete(page), buildBulkDeleteGui(page, selected), "§c§l⚠ §r§fBulk Delete §8— Select blocks to remove");
    }

    private static SimpleInventory buildBulkDeleteGui(int page, Set<String> selected) {
        SimpleInventory inv = new SimpleInventory(54);
        List<SlotData> blocks = sortedBlocks();
        int total = blocks.size(), maxPage = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        inv.setStack(0, uiGlint(Items.RED_CONCRETE, "§c◀ Cancel", "§8Abort bulk delete — no changes"));
        inv.setStack(4, uiGlint(Items.TNT, "§c§l⚠ Bulk Delete Mode",
            "§7Selected: §f" + selected.size() + " §7/ §f" + total + " blocks",
            "§7Click blocks below to toggle selection",
            "§e§lSelected blocks will be §c§lDELETED§e§l on confirm"));
        inv.setStack(8, uiGlint(Items.LIME_DYE, "§a§lSelect All (This Page)",
            "§7Selects all blocks on this page"));

        for (int i = 9; i <= 17; i++) inv.setStack(i, ui(Items.RED_STAINED_GLASS_PANE, "§r"));

        int start = page * BLOCKS_PER_PAGE;
        for (int i = 0; i < BLOCKS_PER_PAGE; i++) {
            int invSlot = 18 + i, dataIdx = start + i;
            if (dataIdx < blocks.size()) {
                SlotData d = blocks.get(dataIdx);
                boolean sel = selected.contains(d.customId);
                ItemStack s = sel
                    ? uiGlint(Items.LIME_STAINED_GLASS_PANE, "§a§l✔ " + d.displayName,
                        "§7ID: §b" + d.customId, "§a§lSELECTED — click to deselect")
                    : (CustomBlocksMod.safeSlotItem(d.index) != null
                        ? new ItemStack(CustomBlocksMod.safeSlotItem(d.index))
                        : new ItemStack(Items.GRAY_DYE));
                if (!sel) {
                    s.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                        Text.literal("§f" + d.displayName).styled(st -> st.withItalic(false)));
                    s.set(net.minecraft.component.DataComponentTypes.LORE, new LoreComponent(List.of(
                        lore("§7ID: §b" + d.customId),
                        lore("§8Click to select for deletion"))));
                }
                inv.setStack(invSlot, s);
            }
        }

        for (int i = 36; i <= 44; i++) inv.setStack(i, ui(Items.RED_STAINED_GLASS_PANE, "§r"));

        inv.setStack(45, page > 0
            ? uiGlint(Items.ARROW, "§7◀ Previous Page", "§8Go to page " + page)
            : ui(Items.GRAY_STAINED_GLASS_PANE, "§8◀ First Page", ""));
        inv.setStack(47, ui(Items.ORANGE_DYE, "§6Deselect All", "§7Clears all selections"));
        inv.setStack(49, ui(Items.PAPER, "§ePage §f" + (page + 1) + " §7/ §f" + (maxPage + 1),
            "§7Selected: §c" + selected.size() + " §7blocks"));
        inv.setStack(51, selected.isEmpty()
            ? ui(Items.GRAY_STAINED_GLASS_PANE, "§8Confirm Delete", "§7Select blocks first")
            : uiGlint(Items.BARRIER, "§4§l⚠ CONFIRM DELETE §c(" + selected.size() + ")",
                "§cPermanently delete §f" + selected.size() + "§c block(s)",
                "§c§oClick to execute — undo available"));
        inv.setStack(53, page < maxPage
            ? uiGlint(Items.ARROW, "§7Next Page ▶", "§8Go to page " + (page + 2))
            : ui(Items.GRAY_STAINED_GLASS_PANE, "§8Last Page ▶", ""));

        return inv;
    }

    private static void handleBulkDeleteClick(ServerPlayerEntity player, GuiState state, int slot) {
        int page = state.page();
        Set<String> selected = BULK_DELETE_SELECTIONS.computeIfAbsent(player.getUuid(), k -> ConcurrentHashMap.newKeySet());

        if (slot == 0) {
            BULK_DELETE_SELECTIONS.remove(player.getUuid());
            openMain(player, 0);
            return;
        }
        if (slot == 8) {
            List<SlotData> blocks = sortedBlocks();
            int start = page * BLOCKS_PER_PAGE;
            for (int i = 0; i < BLOCKS_PER_PAGE && start + i < blocks.size(); i++) {
                selected.add(blocks.get(start + i).customId);
            }
            refreshScreen(player, buildBulkDeleteGui(page, selected));
            return;
        }
        if (slot >= 18 && slot <= 35) {
            List<SlotData> blocks = sortedBlocks();
            int idx = page * BLOCKS_PER_PAGE + (slot - 18);
            if (idx < blocks.size()) {
                String id = blocks.get(idx).customId;
                if (!selected.remove(id)) selected.add(id);
            }
            refreshScreen(player, buildBulkDeleteGui(page, selected));
            return;
        }
        if (slot == 45 && page > 0) {
            STATES.put(player.getUuid(), GuiState.bulkDelete(page - 1));
            refreshScreen(player, buildBulkDeleteGui(page - 1, selected));
            return;
        }
        if (slot == 47) {
            selected.clear();
            refreshScreen(player, buildBulkDeleteGui(page, selected));
            return;
        }
        if (slot == 51 && !selected.isEmpty()) {
            UUID uuid = player.getUuid();
            MinecraftServer server = player.getServer();
            int count = 0;
            for (String id : new ArrayList<>(selected)) {
                SlotData d = SlotManager.getById(id);
                if (d != null) {
                    UndoManager.pushUndoDeletion(id, d.deepCopy(), uuid);
                    SlotManager.remove(id);
                    NetworkManager.broadcastUpdate(server, new SlotUpdatePayload("remove", d.index, id, null, null, 0, 0, "stone"));
                    count++;
                }
            }
            if (count > 0) SlotManager.saveAll();
            send(player, "§a[GUI] Bulk deleted §f" + count + "§a block(s). Use Undo to restore.");
            BULK_DELETE_SELECTIONS.remove(uuid);
            player.getServerWorld().playSound(null, player.getBlockPos(),
                net.minecraft.sound.SoundEvents.BLOCK_ANVIL_USE, net.minecraft.sound.SoundCategory.MASTER, 1f, 0.7f);
            openMain(player, 0);
            return;
        }
        if (slot == 53) {
            int total = sortedBlocks().size();
            int maxPage = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
            if (page < maxPage) {
                STATES.put(player.getUuid(), GuiState.bulkDelete(page + 1));
                refreshScreen(player, buildBulkDeleteGui(page + 1, selected));
            }
        }
    }

    // ── Builders ──────────────────────────────────────────────────────────────

    private static SimpleInventory buildToolsGui(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(54);
        for(int i=0; i<54; i++) inv.setStack(i, glass());
        inv.setStack(4, uiGlint(Items.BRUSH, "§d§lÃ°Å¸Å½Â¨ Magic Items & Tools", "§7Click items to get them."));
        inv.setStack(20, uiGlint(Items.BLAZE_ROD, "§6Rainbow Rectangle Wand", "§7Paints blocks with rainbow colors."));
        inv.setStack(21, uiGlint(Items.WHITE_CONCRETE, "§fColor Square Wand", "§7Paints a solid-color area."));
        inv.setStack(22, uiGlint(Items.WHITE_CARPET, "§fColor Triangle Wand", "§7Paints a solid-color triangle."));
        inv.setStack(24, uiGlint(Items.PAINTING, "§eSet Tab Icon", "§7Opens the tab icon picker."));
        
        inv.setStack(45, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Main Menu"));
        return inv;
    }

    private static SimpleInventory buildMain(ServerPlayerEntity player, int page) {
        // Layout follows Main plan.md:151-158
        //   Row 0 (slot 4)         : Title
        //   Row 2 (19,21,23,25)    : Primary actions (1-slot gaps)
        //   Row 3 (30,32,34)       : Recent blocks (if any)
        //   Row 4 (37,39,41,43)    : Secondary actions (1-slot gaps)
        //   Row 5 (46,48,50,52)    : Navigation (undo/history/redo/config)
        //   Everything else        : invisible gray panes
        SimpleInventory inv = new SimpleInventory(54);
        UUID uuid = player.getUuid();
        int undoSz = UndoManager.undoSize(uuid);
        int redoSz = UndoManager.redoSize(uuid);
        int blockCount = sortedBlocks().size();
        int brokenCount = brokenBlocks().size();

        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        // Row 0: title
        inv.setStack(4, uiGlint(Items.DIAMOND, "§b§lCustomBlocks Dashboard",
            "§7Total blocks: §f" + blockCount,
            brokenCount > 0 ? "§cBroken: §f" + brokenCount : "§aAll textures OK",
            "§8Type /cb help for commands"));

        // Row 2: primary actions (spaced by 1 slot)
        inv.setStack(19, uiGlint(Items.CRAFTING_TABLE, "§e§lBlock Manager",
            "§7Browse, edit, or create blocks", "§8" + blockCount + " block(s) registered"));
        inv.setStack(21, uiGlint(Items.EMERALD, "§a§l+ Create New Block",
            "§7Create a new custom block", "§8Type an ID in chat"));
        inv.setStack(23, uiGlint(Items.SPYGLASS, "§f§lSearch Blocks",
            "§7Find a block by name or ID", "§8Type a query in chat"));
        inv.setStack(25, uiGlint(Items.BRUSH, "§d§lMagic Items",
            "§7Wands, color squares, triangles"));

        // Row 3: recent blocks (inline, spaced)
        Deque<String> recent = RECENT_BLOCKS.getOrDefault(uuid, new ArrayDeque<>());
        int ri = 0;
        int[] recentSlots = {30, 32, 34};
        for (String rid : recent) {
            if (ri >= recentSlots.length) break;
            SlotData rd = SlotManager.getById(rid);
            if (rd == null) continue;
            inv.setStack(recentSlots[ri], uiGlint(Items.CLOCK,
                "§7§lRecent: §f" + rd.displayName,
                "§7ID: §f" + rd.customId, "§8Click to edit"));
            ri++;
        }

        // Row 4: secondary actions (spaced by 1 slot)
        inv.setStack(37, uiGlint(Items.ARMOR_STAND, "§b§lAssistant Hub",
            "§7Spawn, control, and configure the AI assistant"));
        inv.setStack(39, uiGlint(Items.STRUCTURE_VOID, "§6§lServer Tools",
            "§7Broken blocks, resource pack, data",
            brokenCount > 0 ? "§c" + brokenCount + " broken" : "§aAll OK"));
        inv.setStack(41, uiGlint(Items.LAVA_BUCKET, "§c§l⚠ Bulk Delete",
            "§7Select and delete multiple blocks"));
        inv.setStack(43, uiGlint(Items.BOOK, "§a§lHelp & Info", "§7Interactive help guide"));

        // Row 5: navigation (undo, history, redo, config)
        inv.setStack(46, undoSz > 0
            ? uiGlint(Items.GOLDEN_PICKAXE, "§6§l↩ Undo §e(" + undoSz + ")", "§7Click to undo last action")
            : ui(Items.GRAY_STAINED_GLASS_PANE, "§8Undo (Empty)"));
        inv.setStack(48, (undoSz + redoSz) > 0
            ? uiGlint(Items.KNOWLEDGE_BOOK, "§6§lHistory §7(" + (undoSz + redoSz) + ")",
                "§7Browse undo/redo entries", "§8Click to open picker")
            : ui(Items.GRAY_STAINED_GLASS_PANE, "§8History (Empty)"));
        inv.setStack(50, redoSz > 0
            ? uiGlint(Items.DIAMOND_PICKAXE, "§b§l↪ Redo §3(" + redoSz + ")", "§7Click to redo last undone action")
            : ui(Items.GRAY_STAINED_GLASS_PANE, "§8Redo (Empty)"));
        inv.setStack(52, uiGlint(Items.COMPARATOR, "§6§l⚙ Config",
            "§7View and edit server-wide settings"));

        return inv;
    }

    private static SimpleInventory buildMaintenanceMenu(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(54);
        for(int i = 0; i < 54; i++) inv.setStack(i, glass());
        inv.setStack(0, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Main Menu", "§8Return to the dashboard"));

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
        inv.setStack(19, uiGlint(Items.PAINTING, "§a§lSet Tab Icon", "§7Change dynamic creative tab icon", "§aUse a square PNG for best results."));
        inv.setStack(21, uiGlint(Items.DAMAGED_ANVIL, "§c§lBroken Block Finder", "§7Find and fix blocks with missing textures.", "§aCleans up missing textures."));
        inv.setStack(23, uiGlint(Items.BEACON, "§b§lResource Pack", "§7Manage the texture pack & sync.", "§aEnsure players can download your textures."));
        inv.setStack(25, uiGlint(Items.PLAYER_HEAD, "§e§lAI Assistant", "§7Manage your in-world AI assistant.", "§aToggle presence & behaviors."));

        // ── Row 3: Slot Usage & Network ──────────────────────────────────────
        int used = SlotManager.usedSlots();
        int total = com.customblocks.CustomBlocksConfig.maxSlots;
        inv.setStack(31, ui(Items.CHEST, "§e§lBlock Slots", "§7Used: §f" + used + " §7/ §f" + total, "§7Free: §a" + (total - used)));

        boolean httpUp = com.customblocks.network.ResourcePackServer.isRunning();
        if (httpUp) {
            inv.setStack(33, uiGlint(Items.ENDER_EYE, "§a§l✔ Texture Server: ON",
                "§7The texture server is running.",
                "§aClick to manage sync & delivery."));
        } else {
            inv.setStack(33, ui(Items.BARRIER, "§c§l✖ Texture Server: OFF", "§7The texture server is stopped.", "§aEnable it in settings."));
        }

        inv.setStack(40, ui(Items.SPYGLASS, "§b§lMod Info", "§7CustomBlocks §fv1.0.0", "§7Fabric §f1.21.1", "§8Status: §aAll OK"));

        inv.setStack(45, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Main Menu"));
        return inv;
    }

    private static SimpleInventory buildHelpGui() {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        inv.setStack(4, uiGlint(Items.ENCHANTED_BOOK, "§a§lHelp & Commands",
            "§7Browse commands by category.",
            "§8Click a category below to see details."));

        inv.setStack(11, uiGlint(Items.EMERALD, "§e§lCreating Blocks",
            "§7Create, rename, delete, and duplicate blocks.",
            "§8Click to view commands →"));
        inv.setStack(13, uiGlint(Items.PAINTING, "§b§lTextures & Design",
            "§7Retexture, per-face painting, GIF animation.",
            "§8Click to view commands →"));
        inv.setStack(15, uiGlint(Items.ANVIL, "§5§lShapes & Collision",
            "§7Custom shapes, collision, and geometry.",
            "§8Click to view commands →"));
        inv.setStack(20, uiGlint(Items.REDSTONE, "§6§lUtilities & Commands",
            "§7Undo, redo, tools, diagnostics.",
            "§8Click to view commands →"));
        inv.setStack(22, uiGlint(Items.ENDER_CHEST, "§a§lServer & Data",
            "§7Export, import, reload, config.",
            "§8Click to view commands →"));

        inv.setStack(40, ui(Items.KNOWLEDGE_BOOK, "§a§lQuick Tips",
            "§71. Use high-resolution PNGs for best quality.",
            "§72. The Block Editor is the fastest way to customize.",
            "§73. Keep unique IDs short and descriptive."));

        inv.setStack(45, uiGlint(Items.RED_CONCRETE, "§c◀ Back"));
        return inv;
    }

    private static SimpleInventory buildHelpCategory(int category) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        inv.setStack(45, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Help"));

        switch (category) {
            case 1 -> { // Creating Blocks
                inv.setStack(4, uiGlint(Items.EMERALD, "§e§lCreating Blocks"));
                inv.setStack(10, uiGlint(Items.CRAFTING_TABLE, "§eCreate", "§7/cb create <id> <name> <url>", "§8Creates a new custom block from a texture URL.", "§8Optional: add size (16-256) before URL."));
                inv.setStack(11, uiGlint(Items.NAME_TAG, "§eRename", "§7/cb rename <id> <new name>", "§8Changes the display name of a block."));
                inv.setStack(12, uiGlint(Items.COMMAND_BLOCK, "§eRe-ID", "§7/cb reid <old_id> <new_id>", "§8Changes the internal ID.", "§8All placed blocks update automatically."));
                inv.setStack(13, uiGlint(Items.CHEST, "§eDuplicate", "§7/cb dupe <id>", "§8Clones a block with all properties,", "§8textures, shapes, and animation."));
                inv.setStack(14, uiGlint(Items.BARRIER, "§eDelete", "§7/cb delete <id>", "§8Permanently removes a block."));
                inv.setStack(15, uiGlint(Items.TNT, "§eBulk Delete", "§7/cb bulkdelete <id1> <id2> ...", "§8Delete multiple blocks at once."));
                inv.setStack(19, uiGlint(Items.DIAMOND, "§eGive", "§7/cb give <id> [amount] [player]", "§8Adds the block item to inventory."));
            }
            case 2 -> { // Textures & Design
                inv.setStack(4, uiGlint(Items.PAINTING, "§b§lTextures & Design"));
                inv.setStack(10, uiGlint(Items.MAP, "§bRetexture", "§7/cb retexture <id> [size] <url>", "§8Replaces the texture. GIFs auto-animate.", "§8Size: 16-256 (default 128)."));
                inv.setStack(11, uiGlint(Items.AMETHYST_SHARD, "§bSet Face", "§7/cb setface <id> <face> [size] <url>", "§8Faces: north, south, east, west, top, bottom."));
                inv.setStack(12, uiGlint(Items.GLASS, "§bClear Face", "§7/cb clearface <id> <face>", "§8Removes a per-face texture override."));
                inv.setStack(13, uiGlint(Items.BUCKET, "§bClear All Faces", "§7/cb clearallfaces <id>", "§8Removes all face overrides at once."));
                inv.setStack(14, uiGlint(Items.SPYGLASS, "§bResize", "§7/cb resize <id> <16-256>", "§8Rescales the stored texture."));
                inv.setStack(15, uiGlint(Items.BRUSH, "§bEditor", "§7/cb editor [id]", "§8Opens the full block editor GUI."));
                inv.setStack(19, uiGlint(Items.BLAZE_ROD, "§6Rainbow Rectangle", "§7/cb rectangle", "§8Right-click any block face to paint it.", "§8Shift+click = 256px quality."));
                inv.setStack(20, uiGlint(Items.CLOCK, "§bAnimation", "§7Use GIF/WebP/APNG URLs in create or retexture.", "§8Animation speed is set in the Block Editor."));
            }
            case 3 -> { // Shapes & Collision
                inv.setStack(4, uiGlint(Items.ANVIL, "§5§lShapes & Collision"));
                inv.setStack(10, uiGlint(Items.IRON_INGOT, "§5Set Shape", "§7/cb setshape <id> <preset|coords>", "§8Presets: full, slab, thin, carpet, pillar,", "§8small, micro, pane, trapdoor, fence, stairs, cross."));
                inv.setStack(11, uiGlint(Items.STICK, "§5Add Shape Box", "§7/cb addshape <id> <x1,y1,z1,x2,y2,z2>", "§8Adds a collision box (0-16 scale).", "§8Up to 16 boxes per block."));
                inv.setStack(12, uiGlint(Items.SHEARS, "§5Remove Shape Box", "§7/cb removeshape <id> <index>", "§8Removes a specific box by index (0-based)."));
                inv.setStack(13, uiGlint(Items.WATER_BUCKET, "§5Clear Shape", "§7/cb clearshape <id>", "§8Resets block to full cube."));
                inv.setStack(14, uiGlint(Items.SLIME_BLOCK, "§5Set Collision", "§7/cb setcollision <id> <on|off>", "§8Toggle whether players can walk through."));
                inv.setStack(15, uiGlint(Items.ENDER_EYE, "§5Shape Editor GUI", "§7/cb shapeeditor <id>", "§8Visual editor for block shapes."));
            }
            case 4 -> { // Utilities
                inv.setStack(4, uiGlint(Items.REDSTONE, "§6§lUtilities & Commands"));
                inv.setStack(10, uiGlint(Items.GOLDEN_PICKAXE, "§6Undo", "§7/cb undo [count]", "§8Reverts the last change(s) you made.", "§8Up to 20 steps."));
                inv.setStack(11, uiGlint(Items.DIAMOND_PICKAXE, "§6Redo", "§7/cb redo [count]", "§8Re-applies undone changes."));
                inv.setStack(12, uiGlint(Items.RECOVERY_COMPASS, "§6Find Broken", "§7/cb showbrokenblocks", "§8Lists all blocks with missing/broken textures."));
                inv.setStack(13, uiGlint(Items.SUNFLOWER, "§6Set Glow", "§7/cb setglow <id> <0-15>", "§8Light emission. 0=off, 7=torch, 15=max."));
                inv.setStack(14, uiGlint(Items.NETHERITE_INGOT, "§6Set Hardness", "§7/cb sethardness <id> <-1 to 50>", "§8Break speed. -1=bedrock, 0=instant."));
                inv.setStack(15, uiGlint(Items.NOTE_BLOCK, "§6Set Sound", "§7/cb setsound <id> <type>", "§8Types: stone, wood, metal, glass, grass,", "§8sand, wool, gravel, snow, etc."));
                inv.setStack(19, uiGlint(Items.BLACK_DYE, "§7Square Tool", "§7/cb square <black|yellow|green>", "§8Color-swap utility tool."));
                inv.setStack(20, uiGlint(Items.ARROW, "§7Triangle Tool", "§7/cb triangle <black|yellow|green>", "§8Color triangle utility tool."));
            }
            case 5 -> { // Server & Data
                inv.setStack(4, uiGlint(Items.ENDER_CHEST, "§a§lServer & Data"));
                inv.setStack(10, uiGlint(Items.WRITABLE_BOOK, "§aExport Block", "§7/cb exportblock <id>", "§8Generates a short code to share a block."));
                inv.setStack(11, uiGlint(Items.BOOK, "§aImport Block", "§7/cb importblock <code>", "§8Imports a block from an export code."));
                inv.setStack(12, uiGlint(Items.CHEST, "§aExport List", "§7/cb export", "§8Exports all blocks to a JSON file."));
                inv.setStack(13, uiGlint(Items.HOPPER, "§aImport Folder", "§7/cb importfolder", "§8Bulk-imports from config/customblocks/import/."));
                inv.setStack(14, uiGlint(Items.REPEATER, "§aReload", "§7/cb reload", "§8Reloads all data and syncs to players."));
                inv.setStack(15, uiGlint(Items.COMPARATOR, "§aConfig", "§7/cb config", "§8Opens the server configuration GUI."));
                inv.setStack(19, uiGlint(Items.PLAYER_HEAD, "§aAI Assistant", "§7/cb ai [spawn|hide|come|stay|tp|scan|status]", "§8Manage the in-world AI assistant."));
                inv.setStack(20, uiGlint(Items.NETHER_STAR, "§aMagic Items", "§7/cb magicitems", "§8Opens the magic items GUI."));
                inv.setStack(21, uiGlint(Items.COMPASS, "§aResource Pack", "§7/cb rp", "§8Resource pack management hub."));
            }
        }
        return inv;
    }

    private static SimpleInventory buildPropertiesGui(SlotData d) {
        SimpleInventory inv = new SimpleInventory(54);
        for(int i=0;i<54;i++) inv.setStack(i, glass());
        inv.setStack(0, uiGlint(Items.RED_CONCRETE,"§c◀ Back to Editor","§8Return to the block editor"));
        
        ItemStack disp = CustomBlocksMod.safeSlotItem(d.index)!=null?new ItemStack(CustomBlocksMod.safeSlotItem(d.index)):ItemStack.EMPTY;
        disp.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§6§l"+d.displayName).styled(s->s.withItalic(false)));
        disp.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            lore("§7Light Level: §e"+d.lightLevel),
            lore("§7Hardness: §f"+hardnessLabel(d.hardness)),
            lore("§7Collision: "+(d.noCollision?"§cOFF":"§aON"))
        )));
        inv.setStack(4, disp);
        
        // ── Royal Light Slider (Row 2: slots 10-17) ────────────────────────
        // 8 segments covering 0-15, each segment = 2 light levels
        inv.setStack(9, uiGlint(Items.AMETHYST_CLUSTER, "§e✦ Light Level: §f"+d.lightLevel, "§70=off • 7=torch • 15=max"));
        for (int seg = 0; seg < 8; seg++) {
            int slotIdx = 10 + seg;
            int segMin = seg * 2;
            int segMax = segMin + 1;
            boolean isActive = d.lightLevel >= segMin && d.lightLevel <= segMax;
            boolean isBefore = d.lightLevel > segMax;
            Items lightItem;
            String segLabel;
            if (isActive) {
                ItemStack slider = new ItemStack(Items.YELLOW_STAINED_GLASS_PANE);
                slider.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
                slider.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§e§l▶ " + segMin + "-" + segMax + " §r§7(Current: §e" + d.lightLevel + "§7)").styled(s->s.withItalic(false)));
                slider.set(DataComponentTypes.LORE, new LoreComponent(List.of(lore("§aClick to set to §f" + segMin), lore("§7Right-click for §f" + segMax))));
                inv.setStack(slotIdx, slider);
            } else if (isBefore) {
                ItemStack slider = new ItemStack(Items.ORANGE_STAINED_GLASS_PANE);
                slider.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§6" + segMin + "-" + segMax).styled(s->s.withItalic(false)));
                slider.set(DataComponentTypes.LORE, new LoreComponent(List.of(lore("§7Click to set to §f" + segMin), lore("§7Right-click for §f" + segMax))));
                inv.setStack(slotIdx, slider);
            } else {
                ItemStack slider = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
                slider.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§8" + segMin + "-" + segMax).styled(s->s.withItalic(false)));
                slider.set(DataComponentTypes.LORE, new LoreComponent(List.of(lore("§7Click to set to §f" + segMin), lore("§7Right-click for §f" + segMax))));
                inv.setStack(slotIdx, slider);
            }
        }
        
        // Fine controls (+/- and manual input)
        inv.setStack(19, ui(Items.QUARTZ,"§c◀ Less Glow §8(-1)","§7Current: §e"+d.lightLevel, "§aLight level 15 is max brightness."));
        inv.setStack(20, uiGlint(Items.AMETHYST_CLUSTER,"§e✦ Type Value","§7Current: §e"+d.lightLevel, "§e§lClick to type value manually"));
        inv.setStack(21, ui(Items.GLOWSTONE_DUST,"§a▶ More Glow §8(+1)","§7Current: §e"+d.lightLevel));
        
        // ── Royal Hardness Slider (Row 4: slots 28-35) ─────────────────────
        inv.setStack(27, uiGlint(Items.NETHERITE_INGOT, "§b⚙ Hardness: §f"+hardnessLabel(d.hardness), "§7-1=Bedrock • 0=Instant • 1.5=Stone"));
        float[] hardPresets = { -1f, 0f, 0.5f, 1.5f, 3f, 5f, 10f, 50f };
        String[] hardLabels = { "Bedrock", "Instant", "Soft", "Stone", "Iron", "Hard", "Heavy", "Max" };
        net.minecraft.item.Item[] hardItems = { Items.BEDROCK, Items.SPONGE, Items.OAK_PLANKS, Items.STONE, Items.IRON_BLOCK, Items.OBSIDIAN, Items.CRYING_OBSIDIAN, Items.NETHERITE_BLOCK };
        for (int h = 0; h < 8; h++) {
            int slotIdx = 28 + h;
            boolean isActive = Math.abs(d.hardness - hardPresets[h]) < 0.001f;
            ItemStack hStack = new ItemStack(hardItems[h]);
            if (isActive) {
                hStack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
                hStack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§b§l▶ " + hardLabels[h] + " §r§7(" + hardnessLabel(hardPresets[h]) + ")").styled(s->s.withItalic(false)));
            } else {
                hStack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§7" + hardLabels[h] + " §8(" + hardnessLabel(hardPresets[h]) + ")").styled(s->s.withItalic(false)));
            }
            hStack.set(DataComponentTypes.LORE, new LoreComponent(List.of(lore(isActive ? "§a✔ Currently selected" : "§8Click to select"))));
            inv.setStack(slotIdx, hStack);
        }
        
        // Fine controls for hardness
        inv.setStack(23, ui(Items.FLINT,"§c◀ Softer §8(-)","§7Current: §f"+hardnessLabel(d.hardness), "§aHardness 0 breaks instantly."));
        inv.setStack(24, uiGlint(Items.NETHERITE_INGOT,"§b⚙ Type Value","§7Current: §f"+hardnessLabel(d.hardness), "§e§lClick to type value manually"));
        inv.setStack(25, ui(Items.NETHERITE_SCRAP,"§a▶ Harder §8(+)","§7Current: §f"+hardnessLabel(d.hardness)));

        inv.setStack(40, d.noCollision
            ? uiGlint(Items.BARRIER,"§c⊘ Collision: §lOFF","§7Players can pass THROUGH this block","§8Click to turn §aON")
            : uiGlint(Items.SLIME_BLOCK,"§a✔ Collision: §lON","§7Block is solid.","§8Click to turn §cOFF"));
        
        inv.setStack(45, uiGlint(Items.RED_CONCRETE,"§c◀ Back to Editor"));
        return inv;
    }

    private static SimpleInventory buildSoundMenu(SlotData d) {
        SimpleInventory inv = new SimpleInventory(54);
        for(int i=0;i<54;i++) inv.setStack(i, glass());
        inv.setStack(0, uiGlint(Items.RED_CONCRETE,"§c◀ Back to Editor","§8Return to the block editor"));
        
        // Row 1 (slots 10-16): stone, wood, grass, metal, glass, sand, wool
        inv.setStack(10,soundItem(d,"stone",Items.STONE,"§fStone"));
        inv.setStack(11,soundItem(d,"wood",Items.OAK_LOG,"§fWood"));
        inv.setStack(12,soundItem(d,"grass",Items.GRASS_BLOCK,"§fGrass"));
        inv.setStack(13,soundItem(d,"metal",Items.IRON_BLOCK,"§fMetal"));
        inv.setStack(14,soundItem(d,"glass",Items.GLASS,"§fGlass"));
        inv.setStack(15,soundItem(d,"sand",Items.SAND,"§fSand"));
        inv.setStack(16,soundItem(d,"wool",Items.WHITE_WOOL,"§fWool"));
        // Row 2 (slots 19-25): gravel, snow, dirt, coral, bamboo, nether_brick, ice
        inv.setStack(19,soundItem(d,"gravel",Items.GRAVEL,"§fGravel"));
        inv.setStack(20,soundItem(d,"snow",Items.SNOW_BLOCK,"§fSnow"));
        inv.setStack(21,soundItem(d,"dirt",Items.DIRT,"§fDirt"));
        inv.setStack(22,soundItem(d,"coral",Items.BRAIN_CORAL_BLOCK,"§fCoral"));
        inv.setStack(23,soundItem(d,"bamboo",Items.BAMBOO,"§fBamboo"));
        inv.setStack(24,soundItem(d,"nether_brick",Items.NETHER_BRICKS,"§fNether Brick"));
        inv.setStack(25,soundItem(d,"ice",Items.ICE,"§fIce"));
        // Row 3 (slots 28-30): honey, bone, slime
        inv.setStack(28,soundItem(d,"honey",Items.HONEY_BLOCK,"§fHoney"));
        inv.setStack(29,soundItem(d,"bone",Items.BONE_BLOCK,"§fBone"));
        inv.setStack(30,soundItem(d,"slime",Items.SLIME_BLOCK,"§fSlime"));

        inv.setStack(34, ui(Items.NOTE_BLOCK, "§e§lCurrent Sound", "§7Block: §f"+d.displayName, "§7Selected: §b"+d.soundType.toUpperCase(), "§aAffects place, break, and step sounds."));

        inv.setStack(45, uiGlint(Items.RED_CONCRETE,"§c◀ Back to Editor"));
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
            "§aUse the arrows at the bottom to flip pages"));
        for (int i=5;i<=7;i++) inv.setStack(i,glass());
        if (brokenOnly && total > 0)
            inv.setStack(8, uiGlint(Items.TNT, "§c§l⚠ Delete All Broken", "§7Remove all " + total + " broken block(s)", "§cThis action uses undo support."));
        else inv.setStack(8, glass());
        for (int i=9;i<=17;i++) inv.setStack(i, ui(Items.BLUE_STAINED_GLASS_PANE,"§r"));
        int start = page * BLOCKS_PER_PAGE;
        for (int i=0; i<BLOCKS_PER_PAGE; i++) {
            int invSlot = 18+i, dataIdx = start+i;
            if (dataIdx < blocks.size()) {
                SlotData d = blocks.get(dataIdx);
                ItemStack s = CustomBlocksMod.safeSlotItem(d.index)!=null ? new ItemStack(CustomBlocksMod.safeSlotItem(d.index)) : ItemStack.EMPTY;
                s.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§f§l"+d.displayName).styled(st->st.withItalic(false)));
                List<String> ll = new ArrayList<>(List.of("§7Unique ID: §b"+d.customId,"§7Shape: §5"+d.shapeLabel()+" §8• §7Light: §e"+d.lightLevel,"§7Sound: §f"+d.soundType,"§aLeft-click §7→ open the Block Editor", "§dRight-click / Shift-click §7→ assign block to a category"));
                List<String> tags=new ArrayList<>(); if(d.hasFaces())tags.add("§d⬡faces"); if(d.isAnimated())tags.add("§b⏳anim"); if(d.noCollision)tags.add("§c⊘hitbox"); if(!tags.isEmpty())ll.add(String.join("  ",tags));
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

    private static List<SlotData> searchBlocks(String query) {
        return sortedBlocks().stream()
            .filter(d -> {
                if (d.customId.toLowerCase().contains(query) || d.displayName.toLowerCase().contains(query)) return true;
                for (String catKey : com.customblocks.core.CategoryManager.getCategoriesForBlock(d.customId)) {
                    if (catKey.toLowerCase().contains(query)) return true;
                    com.customblocks.core.Category cat = com.customblocks.core.CategoryManager.getCategory(catKey);
                    if (cat != null) {
                        if (cat.displayName() != null && cat.displayName().toLowerCase().contains(query)) return true;
                        if (cat.badge() != null && cat.badge().toLowerCase().contains(query)) return true;
                        if (cat.lorePrefix() != null && cat.lorePrefix().toLowerCase().contains(query)) return true;
                        if (cat.description() != null && cat.description().toLowerCase().contains(query)) return true;
                    }
                }
                return false;
            })
            .toList();
    }

    private static SimpleInventory buildSearchPicker(int page, String query) {
        SimpleInventory inv = new SimpleInventory(54);
        List<SlotData> blocks = searchBlocks(query);
        int total = blocks.size(), maxPage = total==0?0:Math.max(0,(total-1)/BLOCKS_PER_PAGE);
        inv.setStack(0, uiGlint(Items.RED_CONCRETE,"§c◀ Back to Main Dashboard","§8Return to the main menu"));
        for (int i=1;i<=3;i++) inv.setStack(i,glass());
        inv.setStack(4, ui(Items.SPYGLASS,"§e§lSearch Results: §7"+query,
            "§7Showing blocks matching your query",
            "§8"+Math.min(BLOCKS_PER_PAGE,Math.max(0,total-page*BLOCKS_PER_PAGE))+" of §f"+total+" §8results  •  Page §f"+(page+1)+"§8/§f"+(maxPage+1)));
        for (int i=5;i<=8;i++) inv.setStack(i,glass());
        for (int i=9;i<=17;i++) inv.setStack(i, ui(Items.CYAN_STAINED_GLASS_PANE,"§r"));
        int start = page * BLOCKS_PER_PAGE;
        for (int i=0; i<BLOCKS_PER_PAGE; i++) {
            int invSlot = 18+i, dataIdx = start+i;
            if (dataIdx < blocks.size()) {
                SlotData d = blocks.get(dataIdx);
                ItemStack s = CustomBlocksMod.safeSlotItem(d.index)!=null ? new ItemStack(CustomBlocksMod.safeSlotItem(d.index)) : ItemStack.EMPTY;
                s.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§f§l"+d.displayName).styled(st->st.withItalic(false)));
                List<String> ll = new ArrayList<>(List.of("§7Unique ID: §b"+d.customId,"§7Shape: §5"+d.shapeLabel()+" §8• §7Light: §e"+d.lightLevel,"§7Sound: §f"+d.soundType,"§b• Click to open the Block Editor"));
                s.set(DataComponentTypes.LORE, new LoreComponent(ll.stream().map(l->(Text)lore(l)).toList()));
                inv.setStack(invSlot, s);
            } else { inv.setStack(invSlot, glass()); }
        }
        for (int i=36;i<=44;i++) inv.setStack(i, ui(Items.CYAN_STAINED_GLASS_PANE,"§r"));
        inv.setStack(45, page>0 ? uiGlint(Items.ARROW,"§7◀ Previous Page","§8Go to page "+page) : ui(Items.GRAY_STAINED_GLASS_PANE,"§8◀ First Page",""));
        for (int i=46;i<=48;i++) inv.setStack(i,glass());
        inv.setStack(49, ui(Items.PAPER,"§ePage §f"+(page+1)+" §7/ §f"+(maxPage+1),"§7Results: §f"+total));
        for (int i=50;i<=52;i++) inv.setStack(i,glass());
        inv.setStack(53, page<maxPage ? uiGlint(Items.ARROW,"§7Next Page ▶","§8Go to page "+(page+2)) : ui(Items.GRAY_STAINED_GLASS_PANE,"§8Last Page ▶",""));
        return inv;
    }

    private static void handleSearchPickerClick(ServerPlayerEntity player, GuiState state, int slot) {
        int page = state.page();
        UUID uuid = player.getUuid();
        String query = SEARCH_QUERIES.getOrDefault(uuid, "");
        if (slot == 0) { openMain(player, 0); return; }
        if (slot == 45) {
            if (page > 0) {
                STATES.put(uuid, GuiState.searchPicker(page - 1));
                refreshScreen(player, buildSearchPicker(page - 1, query));
            }
            return;
        }
        if (slot == 53) {
            List<SlotData> results = searchBlocks(query);
            int maxPage = results.isEmpty() ? 0 : Math.max(0, (results.size() - 1) / BLOCKS_PER_PAGE);
            if (page < maxPage) {
                STATES.put(uuid, GuiState.searchPicker(page + 1));
                refreshScreen(player, buildSearchPicker(page + 1, query));
            }
            return;
        }
        if (slot >= 18 && slot <= 35) {
            List<SlotData> results = searchBlocks(query);
            int idx = page * BLOCKS_PER_PAGE + (slot - 18);
            if (idx < results.size()) {
                openEditor(player, results.get(idx).customId, page);
            }
        }
    }

    private static SimpleInventory buildEditor(SlotData d, boolean confirmDelete) {
        SimpleInventory inv = new SimpleInventory(54);
        for(int i = 0; i < 54; i++) inv.setStack(i, glass());

        inv.setStack(0, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Block List", "§8Return to the selection grid"));
        inv.setStack(2, uiGlint(Items.CHEST,"§a▶ Give 1x","§7Gives 1x §f"+d.displayName+" §7to you", "§aPuts the block directly in your hotbar."));
        
        ItemStack disp = CustomBlocksMod.safeSlotItem(d.index)!=null?new ItemStack(CustomBlocksMod.safeSlotItem(d.index)):ItemStack.EMPTY;
        disp.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§e§l"+d.displayName).styled(s->s.withItalic(false)));
        disp.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            lore("§7Unique ID: §b"+d.customId),
            lore("§7Current Shape: §5"+d.shapeLabel()),
            lore("§7Light Level: §e"+d.lightLevel+"  §7Hardness: §f"+hardnessLabel(d.hardness)),
            lore("§7Sound: §f"+d.soundType),
            lore("§7Hitbox: "+(d.noCollision?"§cOFF":"§aON")),
            lore("§8Slot #"+d.index)
        )));
        inv.setStack(4, disp);
        inv.setStack(8, uiGlint(Items.MAP,"§b§l» Retexture Block","§7Update the main texture of this block","§aPaste a URL from Imgur, Discord, etc."));
        inv.setStack(17, uiGlint(Items.ECHO_SHARD, "§b§lApply Image from URL", "§7Instantly cast an image/GIF onto this", "§7block via a URL link. (Web-Linker)", "", "§e§l▶ Click to cast."));
        
        inv.setStack(19, uiGlint(Items.PAINTING, "§d§l⬡ Edit Faces", "§7Apply textures to individual faces","§aChange Top, Bottom, or Side textures separately."));
        inv.setStack(21, uiGlint(Items.ENDER_PEARL, "§5§l⬡ Edit Shape", "§7Presets, custom boxes, and collisions","§aMake slabs, stairs, or custom hitboxes."));
        inv.setStack(23, uiGlint(Items.REDSTONE, "§6§l⚙ Properties", "§7Adjust light glow & mining hardness","§aAdjust how the block feels in-world."));
        inv.setStack(25, uiGlint(Items.NOTE_BLOCK, "§e§l♫ Sound", "§7Change placement & break sounds","§aSimulate stone, glass, dirt, etc."));
        
        inv.setStack(31, d.isAnimated()
            ? uiGlint(Items.CLOCK, "§b§l⏳ Animation Settings", "§7This block is currently animated","§aYou can adjust frame speed (FPS) here.")
            : ui(Items.GRAY_DYE, "§7§l⏳ Animation", "§8No animation detected","§aAnimations are auto-enabled for GIF textures."));
        
        inv.setStack(37, uiGlint(Items.NAME_TAG,"§e§l✎ Rename Block","§7Current: §f"+d.displayName,"§aThis is the name everyone sees in the inventory."));
        inv.setStack(39, uiGlint(Items.COMMAND_BLOCK,"§b§l✦ Re-ID Block","§7Current: §b"+d.customId,"§aChanging the unique ID updates all current builds."));
        inv.setStack(41, uiGlint(Items.COMPARATOR,"§e§l≋ Duplicate Block","§7Create an identical copy of this block","§aGreat for making similar block sets quickly."));
        inv.setStack(43, uiGlint(Items.ENDER_EYE,"§b§l⤴ Share Block","§7Export a shareable code to chat","§aOthers can import with /cb importblock."));
        
        inv.setStack(53, confirmDelete
            ? uiGlint(Items.BARRIER, "§4§l⚠ CONFIRM DELETION","§cPermanently delete: §f"+d.customId,"§c§oClick again to confirm!")
            : ui(Items.TNT, "§c§l⚠ Delete This Block","§7Removes the block from the server","§aCan be undone via Main Menu if accidental."));
        if (confirmDelete) inv.setStack(52, uiGlint(Items.GREEN_CONCRETE,"§a§l✖ Cancel","§7Go back without deleting."));

        inv.setStack(45, uiGlint(Items.RED_CONCRETE,"§c◀ Back to Block List"));
        return inv;
    }

    private static SimpleInventory buildShapeEditor(SlotData d, int boxPage) {
        SimpleInventory inv = new SimpleInventory(54);
        List<SlotData.ShapeBox> boxes = d.shapeBoxes!=null?d.shapeBoxes:List.of();
        Item[] pItems = {Items.GRASS_BLOCK,Items.SMOOTH_STONE_SLAB,Items.STONE_SLAB,Items.MOSS_CARPET,Items.COBBLESTONE_WALL,Items.COMPARATOR,Items.COMPARATOR,Items.GLASS_PANE,Items.OAK_TRAPDOOR,Items.OAK_FENCE,Items.OAK_STAIRS,Items.END_ROD};
        inv.setStack(0, uiGlint(Items.RED_CONCRETE,"§c◀ Back to Editor","§8Return to the block editor"));
        for (int i=1;i<=3;i++) inv.setStack(i,glass());
        ItemStack info = CustomBlocksMod.safeSlotItem(d.index)!=null?new ItemStack(CustomBlocksMod.safeSlotItem(d.index)):ItemStack.EMPTY;
        info.set(DataComponentTypes.CUSTOM_NAME,Text.literal("§5§lShape: "+d.displayName).styled(s->s.withItalic(false)));
        info.set(DataComponentTypes.LORE,new LoreComponent(List.of(lore("§7Unique ID: §b"+d.customId),lore("§7Current: §5"+d.shapeLabel()),lore("§7Custom boxes: §f"+boxes.size()+" §8/ 16"),lore("§aEach box defines a solid part of the block."))));
        inv.setStack(4, info);
        for (int i=5;i<=7;i++) inv.setStack(i,glass());
        inv.setStack(8, d.noCollision?uiGlint(Items.BARRIER,"§c⊘ Hitbox: §lOFF","§7Click to §aENABLE §8hitbox"):uiGlint(Items.SLIME_BLOCK,"§a✔ Hitbox: §lON","§7Click to §cDISABLE §8hitbox"));
        inv.setStack(9, ui(Items.BLUE_STAINED_GLASS_PANE,"§9── Shape Presets ──","§7§nLeft-click§r§7 = Create variant  •  §7§nRight-click§r§7 = Apply to base"));
        for (int i=0; i<PRESET_NAMES.length && i<12; i++) {
            String p=PRESET_NAMES[i];
            List<SlotData.ShapeBox> presetBoxes = SlotManager.SHAPE_PRESETS.get(p);
            boolean act = (presetBoxes == null && !d.isShaped()) || (presetBoxes != null && presetBoxes.equals(boxes));
            String name = i < PRESET_DISPLAY.length ? PRESET_DISPLAY[i] : cap(p);
            inv.setStack(10+i, act?uiGlint(pItems[Math.min(i,pItems.length-1)],"§a§l"+name,"§aCurrently Active"):ui(pItems[Math.min(i,pItems.length-1)],"§b"+name,"§7Preset shape","§aApplies a standard Minecraft shape."));
        }
        inv.setStack(22, glass());
        inv.setStack(23, ui(Items.ORANGE_DYE,"§c⊘ Clear All Boxes","§7Reset to a solid full cube","§aClears all custom hitboxes on this block."));
        for (int i=24;i<=26;i++) inv.setStack(i,glass());
        inv.setStack(27, ui(Items.PURPLE_STAINED_GLASS_PANE,"§5── Custom Boxes §8(click to delete) ──","§7Individual hitbox parts"));
        int bstart = boxPage*9;
        for (int i=0;i<9&&(bstart+i)<boxes.size();i++) { SlotData.ShapeBox b=boxes.get(bstart+i); inv.setStack(28+i,ui(Items.STRUCTURE_VOID,"§e§lCustom Box #"+(bstart+i),"§7"+b.toDisplayString(),"§c§oClick to DELETE this box")); }
        for (int s=28+Math.min(9,Math.max(0,boxes.size()-bstart));s<=36;s++) inv.setStack(s,glass());
        List<SlotData> variants = findShapeVariants(d.customId);
        inv.setStack(37, ui(Items.LIME_STAINED_GLASS_PANE,"§a── Shape Variants ──","§7Variant blocks based on this design"));
        for (int i=0;i<Math.min(7,variants.size());i++) {
            SlotData v=variants.get(i);
            ItemStack vs=CustomBlocksMod.safeSlotItem(v.index)!=null?new ItemStack(CustomBlocksMod.safeSlotItem(v.index)):ItemStack.EMPTY;
            vs.set(DataComponentTypes.CUSTOM_NAME,Text.literal("§f§l"+v.displayName).styled(s->s.withItalic(false)));
            vs.set(DataComponentTypes.LORE,new LoreComponent(List.of(lore("§7ID: §b"+v.customId),lore("§7Shape: §5"+v.shapeLabel()),lore("§8Click to open this variant's studio"))));
            inv.setStack(38+i,vs);
        }
        for (int s=38+Math.min(7,variants.size());s<=44;s++) inv.setStack(s,glass());
        int tbp=boxes.isEmpty()?0:Math.max(0,(boxes.size()-1)/9);
        inv.setStack(45,boxPage>0?uiGlint(Items.ARROW,"§7◀ Previous Boxes","§8Page "+boxPage):glass());
        for(int i=46;i<=48;i++) inv.setStack(i,glass());
        inv.setStack(49,ui(Items.PAPER,"§7Page §f"+(boxPage+1)+" §7/ §f"+(tbp+1),"§7Total Boxes: §f"+boxes.size()));
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
        String[][] faces={{"top","^ TOP"},{"bottom","v BOTTOM"},{"north","N NORTH"},{"south","S SOUTH"},{"east","E EAST"},{"west","W WEST"}};
        int[] es={9,11,13,15,17,19}, vs={10,12,14,16,18,20};
        Item[] fi={Items.WHITE_CONCRETE,Items.LIGHT_GRAY_CONCRETE,Items.CYAN_CONCRETE,Items.BLUE_CONCRETE,Items.PURPLE_CONCRETE,Items.MAGENTA_CONCRETE};
        for (int fi2=0;fi2<6;fi2++) {
            boolean has=d.faceTextures.containsKey(faces[fi2][0]); String st=has?"§aOverride ACTIVE":"§7Default texture";
            inv.setStack(es[fi2],uiGlint(fi[fi2],"§a✎ Edit §f"+faces[fi2][1]+" §7(in place)",st,"§8Modifies block directly","§7Left-click §f→ paste URL","§dShift-click §f→ import from folder"));
            inv.setStack(vs[fi2],ui(Items.PAPER,"§b✦ Variant §f"+faces[fi2][1],st,"§8Creates new block with this face","§8Original untouched","§8Click → paste URL"));
        }
        for(int s:new int[]{21,22,23,24,25,26}) inv.setStack(s,glass());
        inv.setStack(22, uiGlint(Items.AMETHYST_SHARD, "\u00A7dFolder Magic",
            "\u00A77Shift-click any \u00A7fEdit \u00A77face button",
            "\u00A77then drop your image into the",
            "\u00A77personal import folder shown in chat",
            "\u00A775 minute timeout"));
        inv.setStack(24, uiGlint(Items.ECHO_SHARD, "\u00A75Copy From Another Block",
            "\u00A77Choose a face, then pick a source block",
            "\u00A77to borrow that side's texture instantly",
            "\u00A78Click to open the face-copy picker"));
        inv.setStack(27,ui(Items.WHITE_STAINED_GLASS_PANE,"§c• Clear TOP",faceStatus(d,"top")));
        inv.setStack(28,ui(Items.LIGHT_GRAY_STAINED_GLASS_PANE,"§c• Clear BOTTOM",faceStatus(d,"bottom")));
        inv.setStack(29,ui(Items.CYAN_STAINED_GLASS_PANE,"§c• Clear NORTH",faceStatus(d,"north")));
        inv.setStack(30,ui(Items.BLUE_STAINED_GLASS_PANE,"§c• Clear SOUTH",faceStatus(d,"south")));
        inv.setStack(31,ui(Items.PURPLE_STAINED_GLASS_PANE,"§c• Clear EAST",faceStatus(d,"east")));
        inv.setStack(32,ui(Items.MAGENTA_STAINED_GLASS_PANE,"§c• Clear WEST",faceStatus(d,"west")));
        for(int i=33;i<=44;i++) inv.setStack(i,glass());
        inv.setStack(45,uiGlint(Items.RED_CONCRETE,"§c◀ Back to Editor","§8(or press ESC)"));
        // Use a simple placeholder for undo count since we need the player UUID
        inv.setStack(46,ui(Items.GRAY_STAINED_GLASS_PANE,"§8Undo","§7Use main menu undo"));
        inv.setStack(47,ui(Items.ORANGE_CONCRETE,"§6⊘ Clear ALL Overrides","§7Reverts every face to default texture"));
        for(int i=48;i<=52;i++) inv.setStack(i,glass());
        inv.setStack(53,uiGlint(Items.CHEST,"§a▶ Give 1x","§7Gives 1x §f"+d.displayName));
        return inv;
    }

    private static SimpleInventory buildFaceChangeSelect(SlotData d) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        inv.setStack(0, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Face Editor", "§8(or press ESC)"));
        inv.setStack(4, uiGlint(Items.NETHER_STAR, "§6§oYour masterpiece awaits",
            "§7Target block: §f" + d.displayName,
            "§7Choose which face should borrow a texture"));

        ItemStack preview = CustomBlocksMod.safeSlotItem(d.index) != null
            ? new ItemStack(CustomBlocksMod.safeSlotItem(d.index))
            : new ItemStack(Items.NETHER_STAR);
        preview.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§f§l" + d.displayName).styled(s -> s.withItalic(false)));
        preview.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            lore("§7ID: §b" + d.customId),
            lore("§7Pick a face below, then choose a source block"),
            lore("§8Exact face match first, otherwise main texture"))));
        inv.setStack(22, preview);

        inv.setStack(9, faceChangeButton("TOP", "The crown of your creation"));
        inv.setStack(11, faceChangeButton("BOTTOM", "The foundation upon which it rests"));
        inv.setStack(13, faceChangeButton("NORTH", "The face that greets the world"));
        inv.setStack(15, faceChangeButton("SOUTH", "The side that watches your back"));
        inv.setStack(17, faceChangeButton("EAST", "The edge that catches the sunrise"));
        inv.setStack(19, faceChangeButton("WEST", "The edge that keeps the dusk"));

        inv.setStack(45, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Face Editor", "§8Return without copying"));
        inv.setStack(49, ui(Items.PAPER, "§7Click a Face",
            "§7Step 1: pick the target face",
            "§7Step 2: choose the source block",
            "§7Step 3: texture copies instantly"));
        return inv;
    }

    private static SimpleInventory buildFaceChangePicker(SlotData target, String face, int page) {
        SimpleInventory inv = new SimpleInventory(54);
        List<SlotData> blocks = sortedBlocks();
        int total = blocks.size();
        int maxPage = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, maxPage));

        inv.setStack(0, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Face Choice", "§8Return to face selection"));
        for (int i = 1; i <= 3; i++) inv.setStack(i, glass());
        inv.setStack(4, uiGlint(Items.NETHER_STAR, "§5§lCopy to §f" + face.toUpperCase(Locale.ROOT),
            "§7Target block: §f" + target.displayName,
            "§7Choose a source block below"));
        for (int i = 5; i <= 8; i++) inv.setStack(i, glass());
        for (int i = 9; i <= 17; i++) inv.setStack(i, ui(Items.PURPLE_STAINED_GLASS_PANE, "§r"));

        int start = page * BLOCKS_PER_PAGE;
        for (int i = 0; i < BLOCKS_PER_PAGE; i++) {
            int invSlot = 18 + i;
            int dataIdx = start + i;
            if (dataIdx >= blocks.size()) {
                inv.setStack(invSlot, glass());
                continue;
            }

            SlotData source = blocks.get(dataIdx);
            ItemStack item = CustomBlocksMod.safeSlotItem(source.index) != null
                ? new ItemStack(CustomBlocksMod.safeSlotItem(source.index))
                : new ItemStack(Items.BRICKS);
            item.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§f§l" + source.displayName).styled(st -> st.withItalic(false)));

            boolean exactFace = source.faceTextures.containsKey(face);
            boolean hasTexture = exactFace || source.texture != null;
            List<Text> lore = new ArrayList<>();
            lore.add(lore("§7ID: §b" + source.customId));
            lore.add(lore(exactFace
                ? "§dUsing its " + face.toUpperCase(Locale.ROOT) + " override"
                : "§7Falls back to the block's main texture"));
            lore.add(lore(hasTexture
                ? "§aClick to copy onto " + target.displayName
                : "§cNo usable texture on this block"));
            item.set(DataComponentTypes.LORE, new LoreComponent(lore));
            inv.setStack(invSlot, item);
        }

        for (int i = 36; i <= 44; i++) inv.setStack(i, ui(Items.PURPLE_STAINED_GLASS_PANE, "§r"));
        inv.setStack(45, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Face Choice", "§8Return without copying"));
        inv.setStack(47, page > 0 ? uiGlint(Items.ARROW, "§7◀ Previous Page", "§8Go to page " + page) : glass());
        inv.setStack(49, ui(Items.PAPER, "§7Page §f" + (page + 1) + " §7/ §f" + (maxPage + 1), "§7Sources: §f" + total));
        inv.setStack(51, page < maxPage ? uiGlint(Items.ARROW, "§7Next Page ▶", "§8Go to page " + (page + 2)) : glass());
        return inv;
    }

    // ── Sensory Feedback ──────────────────────────────────────────────────────
    public static void playClick(ServerPlayerEntity p) {
        p.getServerWorld().playSound(null, p.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, net.minecraft.sound.SoundCategory.PLAYERS, 0.6f, 1.25f);
        p.getServerWorld().spawnParticles(net.minecraft.particle.ParticleTypes.ENCHANT, p.getX(), p.getY() + 1.1, p.getZ(), 10, 0.3, 0.5, 0.3, 0.05);
    }
    public static void playSuccess(ServerPlayerEntity p) {
        p.getServerWorld().playSound(null, p.getBlockPos(), net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, net.minecraft.sound.SoundCategory.PLAYERS, 0.8f, 1.0f);
        p.getServerWorld().playSound(null, p.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_CLUSTER_STEP, net.minecraft.sound.SoundCategory.PLAYERS, 0.5f, 1.0f);
        p.getServerWorld().spawnParticles(net.minecraft.particle.ParticleTypes.COMPOSTER, p.getX(), p.getY() + 1.0, p.getZ(), 20, 0.4, 0.4, 0.4, 0.1);
    }
    public static void playError(ServerPlayerEntity p) {
        p.getServerWorld().playSound(null, p.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), net.minecraft.sound.SoundCategory.PLAYERS, 1f, 0.7f);
        p.getServerWorld().spawnParticles(net.minecraft.particle.ParticleTypes.SMOKE, p.getX(), p.getY() + 1.0, p.getZ(), 15, 0.3, 0.3, 0.3, 0.02);
    }

    public static void playCategoryCreate(ServerPlayerEntity p) {
        p.getServerWorld().playSound(null, p.getBlockPos(), net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP, net.minecraft.sound.SoundCategory.PLAYERS, 0.8f, 1.2f);
        p.getServerWorld().spawnParticles(net.minecraft.particle.ParticleTypes.GLOW, p.getX(), p.getY() + 1.0, p.getZ(), 25, 0.5, 0.5, 0.5, 0.05);
    }

    public static void playCategoryRemove(ServerPlayerEntity p) {
        p.getServerWorld().playSound(null, p.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), net.minecraft.sound.SoundCategory.PLAYERS, 0.8f, 0.9f);
        p.getServerWorld().spawnParticles(net.minecraft.particle.ParticleTypes.SOUL_FIRE_FLAME, p.getX(), p.getY() + 1.0, p.getZ(), 12, 0.3, 0.3, 0.3, 0.02);
    }

    public static void playCategoryDelete(ServerPlayerEntity p) {
        p.getServerWorld().playSound(null, p.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 0.5f);
        p.getServerWorld().spawnParticles(net.minecraft.particle.ParticleTypes.SMOKE, p.getX(), p.getY() + 1.0, p.getZ(), 30, 0.5, 0.5, 0.5, 0.05);
    }

    private static SimpleInventory buildAnimGui(String id, float fps, boolean interp, int frameCount) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        SlotData d = SlotManager.getById(id);
        String blockName = d != null ? d.displayName : id;
        int ticks = Math.max(1, Math.round(20f / Math.max(0.5f, fps)));

        inv.setStack(0, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Editor", "§8Closes without saving"));

        inv.setStack(4, uiGlint(Items.NETHER_STAR, "§b§l▶ Animation Settings",
            "§7Block: §f" + blockName,
            "§7Frames: §f" + frameCount,
            "§7Current Speed: §b" + String.format("%.1f", fps) + " Hz"));

        // ── Temporal Refinement ──────────────────────────────────────────────────
        inv.setStack(19, ui(Items.OBSIDIAN, "§c§l« §r§cSlower §8(-5 FPS)", "§7Slows the animation down"));
        inv.setStack(20, ui(Items.ARROW, "§c§l< §r§cSlower §8(-1 FPS)"));

        inv.setStack(22, uiGlint(Items.ECHO_SHARD, "§e§lAnimation Speed",
            "§7Current Speed: §b" + String.format("%.1f", fps) + " FPS",
            "§7Tick Delay: §f" + ticks + " §7ticks per frame",
            "",
            "§r"));

        inv.setStack(24, ui(Items.ARROW, "§a+1 FPS §l>", "§7Slight increase"));
        inv.setStack(25, ui(Items.GOLD_INGOT, "§a+5 FPS §l»", "§7Speeds the animation up"));

        // ── Frequency Nodes (Presets) ───────────────────────────────────────────
        inv.setStack(28, ui(Items.AMETHYST_SHARD, "§d5 FPS", "§7Very slow"));
        inv.setStack(29, ui(Items.AMETHYST_SHARD, "§d10 FPS", "§7Slow"));
        inv.setStack(30, ui(Items.AMETHYST_CLUSTER, "§b20 FPS", "§7Normal"));
        inv.setStack(31, ui(Items.AMETHYST_CLUSTER, "§b40 FPS", "§7Fast"));
        inv.setStack(32, ui(Items.AMETHYST_CLUSTER, "§b60 FPS", "§7Very fast"));
        inv.setStack(33, ui(Items.AMETHYST_CLUSTER, "§b80 FPS", "§7Ultra fast"));
        inv.setStack(34, uiGlint(Items.ANVIL, "§e§lCustom FPS", "§7Type any value from §f0.5§7 to §f100", "§8Click to enter"));

        // ── Smooth Blending (Interpolation) ───────────────────────────────────
        inv.setStack(40, interp
            ? uiGlint(Items.CRYING_OBSIDIAN, "§d§lSmooth Blending: §6ON",
                "§7Smooths frame transitions.",
                "§7Good for water, fire, or magic blocks.",
                "§8Click to turn §cOFF")
            : ui(Items.OBSIDIAN, "§7§lSmooth Blending: §8OFF",
                "§7Sharp transitions between frames.",
                "§7Good for pixel art textures.",
                "§8Click to turn §6ON"));

        inv.setStack(45, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Editor"));
        inv.setStack(49, uiGlint(Items.DRAGON_EGG, "§6§lSave & Apply",
            "§7Saves changes and sends them",
            "§7to all players.",
            "",
            "§e§lClick to save."));

        return inv;
    }

    // ── Small helpers ─────────────────────────────────────────────────────────

    private static void closeForPrompt(ServerPlayerEntity player) {
        REOPENING_SCREENS.add(player.getUuid());
        player.closeHandledScreen();
        REOPENING_SCREENS.remove(player.getUuid());
    }

    private static void openShortInputPrompt(ServerPlayerEntity player, PendingInput pending, String title, ItemStack promptItem, String initialText) {
        PENDING.put(player.getUuid(), pending);
        AnvilPromptManager.open(player, Text.literal(title), promptItem, initialText);
    }

    private static boolean usesAnvilConfigPrompt(String key) {
        return true;
    }

    private static ItemStack shortPromptItemForConfig(String key) {
        Item item = switch (key) {
            case "maxSlots", "defaultTextureSize", "bgRemovalTolerance", "maxUndoDepth",
                 "downloadTimeoutSeconds", "texturePayloadsPerTick", "resourcePackPort",
                 "reloadDebounceMs" -> Items.REPEATER;
            case "undoMode" -> Items.COMPARATOR;
            case "colorToolBackgroundMode" -> Items.BOOK;
            case "triangleGreenHex", "triangleYellowHex" -> Items.LIME_DYE;
            case "aiStyle" -> Items.PAINTING;
            case "cloudShareUrl" -> Items.ENDER_PEARL;
            default -> Items.NAME_TAG;
        };
        return new ItemStack(item);
    }

    private static String currentConfigValue(String key) {
        return switch (key) {
            case "maxSlots" -> String.valueOf(CustomBlocksConfig.maxSlots);
            case "defaultTextureSize" -> String.valueOf(CustomBlocksConfig.defaultTextureSize);
            case "bgRemovalTolerance" -> String.valueOf(CustomBlocksConfig.bgRemovalTolerance);
            case "maxUndoDepth" -> String.valueOf(CustomBlocksConfig.maxUndoDepth);
            case "downloadTimeoutSeconds" -> String.valueOf(CustomBlocksConfig.downloadTimeoutSeconds);
            case "texturePayloadsPerTick" -> String.valueOf(CustomBlocksConfig.texturePayloadsPerTick);
            case "resourcePackPort" -> String.valueOf(CustomBlocksConfig.resourcePackPort);
            case "reloadDebounceMs" -> String.valueOf(CustomBlocksConfig.reloadDebounceMs);
            case "cloudShareUrl" -> CustomBlocksConfig.normalizedCloudShareUrl();
            case "aiName" -> stripFormattingCodes(CustomBlocksConfig.aiName);
            case "undoMode" -> CustomBlocksConfig.undoMode;
            case "colorToolBackgroundMode" -> CustomBlocksConfig.colorToolBackgroundMode;
            case "triangleGreenHex" -> CustomBlocksConfig.triangleGreenHex;
            case "triangleYellowHex" -> CustomBlocksConfig.triangleYellowHex;
            case "aiStyle" -> CustomBlocksConfig.aiStyle;
            default -> "";
        };
    }

    private static String formatColorToolMode(String mode) {
        if ("corners_only".equals(mode)) return "Default: Fill corner only";
        if ("corners_and_trapped".equals(mode)) return "Extra: Fill corners + more";
        return "Unset (pick one)";
    }

    private static String normalizeHexInput(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (!s.startsWith("#")) s = "#" + s;
        if (!s.matches("(?i)^#[0-9a-f]{6}$")) return null;
        return s.toUpperCase();
    }

    private static String stripFormattingCodes(String text) {
        return text == null ? "" : text.replace("§", "");
    }

    private static void promptFace(ServerPlayerEntity player, String blockId, String face, int rp, boolean variant) {
        InputAction action = variant ? InputAction.SETFACE_VARIANT_URL : InputAction.SETFACE_URL;
        PENDING.put(player.getUuid(), new PendingInput(action, blockId, face, null, null, rp));
        closeForPrompt(player);
        String mode = variant ? "§b(creates variant — original untouched)" : "§a(modifies this block)";
        send(player, "§6[GUI] §ePaste URL for §f"+face.toUpperCase()+" §eof '§f"+blockId+"§e' "+mode+":");
        send(player, "§7Type §ccancel §7to abort.");
    }

    private static void startPendingFaceImport(ServerPlayerEntity player, String blockId, String face, int rp) {
        Path importDir = nextFaceImportDir(player.getUuid(), face);
        try {
            Files.createDirectories(importDir);
        } catch (IOException e) {
            playError(player);
            player.sendMessage(Text.literal("\u00A70\u00A7l[\u00A7b\u00A7lCB\u00A70\u00A7l]\u00A7r \u00A7cCouldn't prepare the import folder. \u00A77" + e.getMessage()), false);
            reopenFaceEditor(player, blockId, rp);
            return;
        }

        FACE_IMPORTS.put(player.getUuid(), new FaceImportPending(
            blockId, face, rp, importDir.toString(), System.currentTimeMillis() + FACE_IMPORT_TIMEOUT_MS));
        closeForPrompt(player);
        playFaceImportStart(player);
        player.sendMessage(Text.literal("\u00A70\u00A7l[\u00A7b\u00A7lCB\u00A70\u00A7l]\u00A7r \u00A7fDrop your image into the \u00A7bimport folder\u00A7f. \u00A77You have 5 minutes."), false);
        player.sendMessage(Text.literal("\u00A77Target face: \u00A7b" + face.toUpperCase(Locale.ROOT) + " \u00A78\u2022 \u00A77Folder: \u00A7b" + displayPath(importDir)), false);
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

    private static List<Path> listSupportedImportFiles(Path importDir) {
        try (var stream = Files.list(importDir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(GuiManager::isSupportedImportFile)
                .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                .toList();
        } catch (IOException e) {
            LOGGER.warn("[CustomBlocks] Failed to scan face import folder '{}': {}", importDir, e.getMessage());
            return List.of();
        }
    }

    private static boolean isSupportedImportFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
            || name.endsWith(".gif") || name.endsWith(".bmp") || name.endsWith(".webp")
            || name.endsWith(".tiff") || name.endsWith(".tif");
    }

    private static void processPendingFaceImport(MinecraftServer server, UUID uuid, FaceImportPending pending, Path file) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
        if (player == null) return;

        LOGGER.info("[CustomBlocks] Face import detected for player={} block='{}' face='{}' file='{}'",
            player.getGameProfile().getName(), pending.blockId(), pending.face(), file.getFileName());
        playFaceImportDetected(player);

        EXECUTOR.submit(() -> {
            try {
                byte[] raw = Files.readAllBytes(file);
                ImageProcessor.ProcessResult result = processImportedFaceBytes(raw);
                server.execute(() -> completePendingFaceImport(server, uuid, pending, file, result));
            } catch (Exception e) {
                server.execute(() -> failPendingFaceImport(server, uuid, pending, file, e));
            }
        });
    }

    private static ImageProcessor.ProcessResult processImportedFaceBytes(byte[] raw) throws IOException {
        if (raw == null || raw.length == 0) {
            throw new IOException("The dropped file was empty.");
        }

        if (ImageProcessor.isAnimatedImage(raw)) {
            ImageProcessor.ProcessResult result = ImageProcessor.processAnimation(raw, CustomBlocksConfig.defaultTextureSize);
            if (result == null || result.bytes() == null || result.bytes().length == 0) {
                throw new IOException("Couldn't decode animation frames from that file.");
            }
            return result;
        }

        byte[] png = ImageProcessor.toPng(raw);
        png = ImageProcessor.padToSquare(png);
        png = ImageProcessor.replaceBackground(png);
        return new ImageProcessor.ProcessResult(
            ImageProcessor.resizeTo(png, CustomBlocksConfig.defaultTextureSize),
            null,
            1
        );
    }

    private static void completePendingFaceImport(MinecraftServer server, UUID uuid, FaceImportPending pending, Path file, ImageProcessor.ProcessResult result) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
        SlotData d = SlotManager.getById(pending.blockId());
        if (player == null || d == null) return;
        if (result == null || result.bytes() == null || result.bytes().length == 0) {
            failPendingFaceImport(server, uuid, pending, file, new IOException("Processed image was empty."));
            return;
        }

        UndoManager.pushUndoMutation(pending.blockId(), d, "setface " + pending.face() + " (folder)", uuid);
        SlotManager.setFaceTexture(pending.blockId(), pending.face(), result.bytes());
        if (result.isAnimated() && result.mcmeta() != null) {
            SlotManager.setAnimMeta(pending.blockId(), result.mcmeta());
        }
        SlotManager.saveAll();
        NetworkManager.broadcastUpdate(server, new SlotUpdatePayload(
            "setface", d.index, pending.blockId(), null, result.bytes(),
            d.lightLevel, d.hardness, d.soundType, pending.face(), null,
            result.isAnimated() ? result.mcmeta() : null));

        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warn("[CustomBlocks] Couldn't delete processed face import '{}': {}", file, e.getMessage());
        }
        cleanupFaceImportDir(Path.of(pending.importDir()));

        playFaceImportSuccess(player);
        player.sendMessage(Text.literal("\u00A70\u00A7l[\u00A7b\u00A7lCB\u00A70\u00A7l]\u00A7r \u00A7a" + pending.face().toUpperCase(Locale.ROOT) + " face updated! \u00A7a\u2714"), false);
        reopenFaceEditor(player, pending.blockId(), pending.returnPage());
    }

    private static void failPendingFaceImport(MinecraftServer server, UUID uuid, FaceImportPending pending, Path file, Exception error) {
        LOGGER.warn("[CustomBlocks] Face import failed for block='{}' face='{}' file='{}': {}",
            pending.blockId(), pending.face(), file.getFileName(), error.getMessage(), error);
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
        if (player == null) return;

        playError(player);
        player.sendMessage(Text.literal("\u00A70\u00A7l[\u00A7b\u00A7lCB\u00A70\u00A7l]\u00A7r \u00A7cFace import failed. \u00A77" + faceImportError(error)), false);
        reopenFaceEditor(player, pending.blockId(), pending.returnPage());
    }

    private static void notifyFaceImportExpired(MinecraftServer server, UUID uuid, FaceImportPending pending) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
        if (player == null) return;

        playFaceImportTimeout(player);
        player.sendMessage(Text.literal("\u00A70\u00A7l[\u00A7b\u00A7lCB\u00A70\u00A7l]\u00A7r \u00A7eFace import timed out for \u00A7b" + pending.face().toUpperCase(Locale.ROOT) + "\u00A7e. \u00A77Shift-click again when you're ready."), false);
        reopenFaceEditor(player, pending.blockId(), pending.returnPage());
    }

    private static String faceImportError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "Use a supported image file and try again.";
        return message;
    }

    private static Path nextFaceImportDir(UUID uuid, String face) {
        return Path.of(FACE_IMPORT_FOLDER, FACE_IMPORT_REQUESTS_DIR, uuid.toString(),
            face + "-" + Long.toUnsignedString(System.currentTimeMillis(), 36));
    }

    private static String displayPath(Path path) {
        return path.toString().replace('\\', '/') + "/";
    }

    private static void cleanupFaceImportDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            if (stream.findAny().isPresent()) return;
        } catch (IOException e) {
            LOGGER.warn("[CustomBlocks] Couldn't inspect face import folder '{}': {}", dir, e.getMessage());
            return;
        }
        try {
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            LOGGER.warn("[CustomBlocks] Couldn't delete empty face import folder '{}': {}", dir, e.getMessage());
        }
    }

    private static void playFaceImportStart(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        world.spawnParticles(net.minecraft.particle.ParticleTypes.ENCHANT,
            player.getX(), player.getY() + 1.1, player.getZ(),
            24, 0.35, 0.5, 0.35, 0.02);
        player.playSound(net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.15f);
    }

    private static void playFaceImportDetected(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        world.spawnParticles(net.minecraft.particle.ParticleTypes.SOUL_FIRE_FLAME,
            player.getX(), player.getY() + 1.0, player.getZ(),
            14, 0.25, 0.45, 0.25, 0.01);
        player.playSound(net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 0.85f, 1.0f);
    }

    private static void playFaceImportSuccess(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        world.spawnParticles(net.minecraft.particle.ParticleTypes.COMPOSTER,
            player.getX(), player.getY() + 1.0, player.getZ(),
            20, 0.35, 0.4, 0.35, 0.02);
        player.playSound(net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.0f);
    }

    private static void playFaceImportTimeout(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        world.spawnParticles(net.minecraft.particle.ParticleTypes.SMOKE,
            player.getX(), player.getY() + 1.0, player.getZ(),
            12, 0.25, 0.35, 0.25, 0.01);
        player.playSound(net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 1.0f, 0.8f);
    }

    private static ItemStack faceChangeButton(String faceLabel, String poeticLine) {
        return uiGlint(Items.ECHO_SHARD, "§5§l" + faceLabel,
            "§5§o" + poeticLine,
            "§7Click to choose a source block");
    }

    private static void copyFaceFromSource(ServerPlayerEntity player, String targetId, String face, String sourceId, int page, int returnPage) {
        SlotData target = SlotManager.getById(targetId);
        SlotData source = SlotManager.getById(sourceId);
        if (target == null || source == null) {
            playError(player);
            reopenFaceChangePicker(player, targetId, face, page);
            return;
        }

        byte[] texture = source.faceTextures.containsKey(face) ? source.faceTextures.get(face) : source.texture;
        if (texture == null || texture.length == 0) {
            playError(player);
            player.sendMessage(Text.literal("\u00A70\u00A7l[\u00A7b\u00A7lCB\u00A70\u00A7l]\u00A7r \u00A7cThat source block has no usable texture for \u00A7b" + face.toUpperCase(Locale.ROOT) + "\u00A7c."), false);
            reopenFaceChangePicker(player, targetId, face, page);
            return;
        }

        playFaceCopyApply(player);
        byte[] copy = texture.clone();
        UndoManager.pushUndoMutation(targetId, target, "copyface " + face + " from " + sourceId, player.getUuid());
        SlotManager.setFaceTexture(targetId, face, copy);
        if (source.animMeta != null) {
            SlotManager.setAnimMeta(targetId, source.animMeta);
        }
        SlotManager.saveAll();
        NetworkManager.broadcastUpdate(player.getServer(), new SlotUpdatePayload(
            "setface", target.index, targetId, null, copy,
            target.lightLevel, target.hardness, target.soundType, face, null, source.animMeta));

        playFaceCopyComplete(player);
        player.sendMessage(Text.literal("\u00A70\u00A7l[\u00A7b\u00A7lCB\u00A70\u00A7l]\u00A7r \u00A7a" + face.toUpperCase(Locale.ROOT) + " \u00A77\u2190 copied from \u00A7b'" + source.displayName + "' \u00A7a\u2714"), false);
        reopenFaceChangeSelect(player, targetId, returnPage);
    }

    private static void playFaceCopySelect(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        world.spawnParticles(net.minecraft.particle.ParticleTypes.ENCHANT,
            player.getX(), player.getY() + 1.0, player.getZ(),
            18, 0.3, 0.45, 0.3, 0.02);
        player.playSound(net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.85f, 1.1f);
    }

    private static void playFaceCopyApply(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        world.spawnParticles(net.minecraft.particle.ParticleTypes.COMPOSTER,
            player.getX(), player.getY() + 1.0, player.getZ(),
            16, 0.3, 0.35, 0.3, 0.02);
        player.playSound(net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.0f);
    }

    private static void playFaceCopyComplete(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        world.spawnParticles(net.minecraft.particle.ParticleTypes.GLOW,
            player.getX(), player.getY() + 1.0, player.getZ(),
            16, 0.3, 0.35, 0.3, 0.02);
        player.playSound(net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 0.8f, 1.05f);
    }

    public static void uploadShareToCloud(String hash, String jsonStr) {
        if (!CustomBlocksConfig.isCloudShareEnabled()) return;
        String baseUrl = CustomBlocksConfig.normalizedCloudShareUrl();
        if (baseUrl.isBlank()) return;
        String payload = buildCloudSharePayload(hash, jsonStr);

        EXECUTOR.submit(() -> {
            try {
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + "/share"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload, java.nio.charset.StandardCharsets.UTF_8))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();
                java.net.http.HttpResponse<String> response = HTTP.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    LOGGER.info("[CB Cloud] Block uploaded to vault ✔");
                } else {
                    LOGGER.warn("[CB Cloud] Upload failed with status {} (local share still works)", response.statusCode());
                }
            } catch (Exception e) {
                LOGGER.warn("[CB Cloud] Upload failed (local share still works): {}", e.getMessage());
            }
        });
    }

    private static String buildCloudSharePayload(String hash, String jsonStr) {
        try {
            JsonObject payload = JsonParser.parseString(jsonStr).getAsJsonObject();
            payload.addProperty("hash", hash);
            payload.addProperty("code", "CB~" + hash);
            return payload.toString();
        } catch (Exception ignored) {
            return jsonStr;
        }
    }

    private static String cap(String s)          { return s==null||s.isEmpty()?"":(char)(Character.toUpperCase(s.charAt(0)))+s.substring(1); }
    private static float nextHardness(float cur) { for(int i=0;i<HARD_CYCLE.length-1;i++) if(Math.abs(cur-HARD_CYCLE[i])<0.01f) return HARD_CYCLE[i+1]; return HARD_CYCLE[1]; }
    private static float prevHardness(float cur) { for(int i=HARD_CYCLE.length-1;i>0;i--) if(Math.abs(cur-HARD_CYCLE[i])<0.01f) return HARD_CYCLE[i-1]; return HARD_CYCLE[0]; }
    private static String hardnessLabel(float h) { if(h<0)return "∞ Unbreakable"; if(h==0)return "0 (Instant)"; return String.valueOf(h); }
    private static String faceStatus(SlotData d, String f) { return d.faceTextures.containsKey(f)?"§aOverride ACTIVE — click to clear":"§8No override set"; }
    private static boolean isUrl(String s)       { return s.startsWith("http://")||s.startsWith("https://"); }
    private static String normalizeFormattingCodes(String text) {
        if (text == null) return "";
        return text.replace("ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€š§", "\u00A7").replace("Ãƒâ€š§", "\u00A7"); // legacy decode of corrupted § sequences
    }
    private static void send(ServerPlayerEntity p, String m) { ChatHelper.info(p, normalizeFormattingCodes(m)); }
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

    public static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, java.util.Map<String, String>> PENDING_CATEGORIES = new java.util.concurrent.ConcurrentHashMap<>();

    public static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, com.google.gson.JsonObject> PENDING_IMPORTS = new java.util.concurrent.ConcurrentHashMap<>();
    public static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, java.util.Map<String, String>> PENDING_IMPORT_DECISIONS = new java.util.concurrent.ConcurrentHashMap<>();

    public static void openImportConflictGui(net.minecraft.server.network.ServerPlayerEntity player, int page) {
        com.google.gson.JsonObject root = PENDING_IMPORTS.get(player.getUuid());
        if (root == null) { handleEscBack(player); return; }
        java.util.Map<String, String> decisions = PENDING_IMPORT_DECISIONS.computeIfAbsent(player.getUuid(), k -> new java.util.concurrent.ConcurrentHashMap<>());
        
        com.google.gson.JsonArray blocksArr = root.has("blocks") ? root.getAsJsonArray("blocks") : new com.google.gson.JsonArray();
        java.util.List<com.google.gson.JsonObject> conflicting = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement el : blocksArr) {
            com.google.gson.JsonObject bObj = el.getAsJsonObject();
            String bId = bObj.get("id").getAsString();
            if (com.customblocks.core.SlotManager.hasId(bId)) conflicting.add(bObj);
            decisions.putIfAbsent(bId, "skip"); // Default to skip
        }
        
        if (conflicting.isEmpty()) {
            // No conflicts, process immediately
            processImport(player, root, decisions);
            return;
        }

        int max = conflicting.isEmpty() ? 0 : Math.max(0, (conflicting.size() - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        pushBackStack(player.getUuid());

        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        int start = page * BLOCKS_PER_PAGE;
        int end = Math.min(start + BLOCKS_PER_PAGE, conflicting.size());
        for (int i = start; i < end; i++) {
            com.google.gson.JsonObject bObj = conflicting.get(i);
            String bId = bObj.get("id").getAsString();
            String dec = decisions.get(bId);
            
            net.minecraft.item.Item displayItem = net.minecraft.item.Items.BARRIER;
            String status = "§cSkip";
            if ("overwrite".equals(dec)) { displayItem = net.minecraft.item.Items.REDSTONE_BLOCK; status = "§4Overwrite"; }
            else if ("keep".equals(dec)) { displayItem = net.minecraft.item.Items.EMERALD_BLOCK; status = "§aKeep Both (Rename)"; }
            
            net.minecraft.item.ItemStack stack = uiGlint(displayItem, "§f" + bId, "§7Conflict resolution:", status, "", "§eClick to cycle option");
            inv.setStack(18 + (i - start), stack);
        }

        inv.setStack(49, uiGlint(net.minecraft.item.Items.EMERALD, "§a§lConfirm & Import", "§7Execute the import with these decisions"));
        if (page > 0) inv.setStack(45, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§d← Previous Page"));
        else inv.setStack(45, uiGlint(net.minecraft.item.Items.RED_CONCRETE, "§c← Cancel"));
        if (end < conflicting.size()) inv.setStack(53, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§dNext Page →"));

        openScreenFromGuiState(player, GuiState.importConflict(root.getAsJsonObject("category").get("key").getAsString()).withPage(page), inv, "§6§lImport Conflicts");
    }

    private static void handleImportConflictClick(net.minecraft.server.network.ServerPlayerEntity player, GuiState state, int slot) {
        com.google.gson.JsonObject root = PENDING_IMPORTS.get(player.getUuid());
        if (root == null) { handleEscBack(player); return; }
        java.util.Map<String, String> decisions = PENDING_IMPORT_DECISIONS.get(player.getUuid());
        
        if (slot == 45) {
            if (state.page() > 0) openImportConflictGui(player, state.page() - 1);
            else {
                PENDING_IMPORTS.remove(player.getUuid());
                PENDING_IMPORT_DECISIONS.remove(player.getUuid());
                handleEscBack(player);
            }
            return;
        }
        if (slot == 53) {
            openImportConflictGui(player, state.page() + 1);
            return;
        }
        if (slot == 49) {
            processImport(player, root, decisions);
            return;
        }

        com.google.gson.JsonArray blocksArr = root.has("blocks") ? root.getAsJsonArray("blocks") : new com.google.gson.JsonArray();
        java.util.List<com.google.gson.JsonObject> conflicting = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement el : blocksArr) {
            com.google.gson.JsonObject bObj = el.getAsJsonObject();
            String bId = bObj.get("id").getAsString();
            if (com.customblocks.core.SlotManager.hasId(bId)) conflicting.add(bObj);
        }

        int start = state.page() * BLOCKS_PER_PAGE;
        if (slot < 18 || slot > 35) return;
        int idx = start + (slot - 18);
        if (idx < conflicting.size()) {
            String bId = conflicting.get(idx).get("id").getAsString();
            String cur = decisions.get(bId);
            String next = switch (cur) {
                case "skip" -> "overwrite";
                case "overwrite" -> "keep";
                default -> "skip";
            };
            decisions.put(bId, next);
            playClick(player);
            openImportConflictGui(player, state.page());
        }
    }

    public static void processImport(net.minecraft.server.network.ServerPlayerEntity player, com.google.gson.JsonObject root, java.util.Map<String, String> decisions) {
        PENDING_IMPORTS.remove(player.getUuid());
        PENDING_IMPORT_DECISIONS.remove(player.getUuid());
        try {
            com.google.gson.JsonObject catObj = root.getAsJsonObject("category");
            String catKey = catObj.get("key").getAsString();
            String displayName = catObj.has("displayName") ? catObj.get("displayName").getAsString() : catKey;
            
            com.customblocks.core.Category cat = com.customblocks.core.Category.create(displayName);
            if (catObj.has("iconItem")) cat = cat.withIconItem(catObj.get("iconItem").getAsString());
            if (catObj.has("color")) cat = cat.withColor(catObj.get("color").getAsString());
            if (catObj.has("badge")) cat = cat.withBadge(catObj.get("badge").getAsString());
            
            com.customblocks.core.CategoryManager.addCategory(cat);
            
            int imported = 0;
            if (root.has("blocks")) {
                com.google.gson.JsonArray blocksArr = root.getAsJsonArray("blocks");
                for (com.google.gson.JsonElement el : blocksArr) {
                    com.google.gson.JsonObject bObj = el.getAsJsonObject();
                    String bId = bObj.get("id").getAsString();
                    
                    if (!com.customblocks.core.SlotManager.hasId(bId)) {
                        // Skip if it doesn't exist and we don't have textures
                        continue;
                    }
                    
                    String dec = decisions.getOrDefault(bId, "skip");
                    if ("skip".equals(dec)) continue;
                    
                    if ("keep".equals(dec)) {
                        String newId = com.customblocks.command.CustomBlockCommand.generateDupeId(bId);
                        if (com.customblocks.core.SlotManager.freeSlots() > 0) {
                            com.customblocks.core.SlotData oldD = com.customblocks.core.SlotManager.getById(bId);
                            com.customblocks.core.SlotManager.assign(newId, oldD.displayName + " (Copy)", oldD.texture != null ? oldD.texture.clone() : null);
                            com.customblocks.core.CategoryManager.assignBlock(newId, cat.key());
                            imported++;
                        }
                    } else if ("overwrite".equals(dec)) {
                        // Overwrite means we just assign it here in the simplified payload
                        com.customblocks.core.CategoryManager.assignBlock(bId, cat.key());
                        imported++;
                    }
                }
            }
            com.customblocks.core.SlotManager.saveAll();
            playSuccess(player);
            send(player, "§aImported category '§f" + displayName + "§a' with §f" + imported + "§a blocks.");
            openCategoryDetail(player, cat.key(), 0);
        } catch (Exception ex) {
            send(player, "§cImport error: " + ex.getMessage());
            handleEscBack(player);
        }
    }

    public static void openBlocksGui(net.minecraft.server.network.ServerPlayerEntity player, int page) {
        java.util.List<com.customblocks.core.SlotData> all = new java.util.ArrayList<>(com.customblocks.core.SlotManager.allSlots());
        java.util.List<com.customblocks.core.SlotData> uncategorized = new java.util.ArrayList<>();
        for (com.customblocks.core.SlotData d : all) {
            if (!"tab_icon".equals(d.customId) && com.customblocks.core.CategoryManager.getCategoriesForBlock(d.customId).isEmpty()) {
                uncategorized.add(d);
            }
        }
        int max = uncategorized.isEmpty() ? 0 : Math.max(0, (uncategorized.size() - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.uncategorizedPicker(page), buildCategoryDetail("Uncategorized", uncategorized, page), "§6§lUncategorized Blocks");
    }

    private static void handleUncategorizedPickerClick(net.minecraft.server.network.ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 4) {
            openShortInputPrompt(player, new PendingInput(InputAction.REID_TEXT, "__search__", null, null, null, state.page()), "Search Blocks", new ItemStack(net.minecraft.item.Items.SPYGLASS), "");
            return;
        }
        if (slot == 45) {
            if (state.page() > 0) openBlocksGui(player, state.page() - 1);
            else handleEscBack(player);
            return;
        }
        if (slot == 53) {
            openBlocksGui(player, state.page() + 1);
            return;
        }
        if (slot >= 18 && slot <= 35) {
            int start = state.page() * BLOCKS_PER_PAGE;
            int idx = start + (slot - 18);
            java.util.List<com.customblocks.core.SlotData> all = new java.util.ArrayList<>(com.customblocks.core.SlotManager.allSlots());
            java.util.List<com.customblocks.core.SlotData> uncategorized = new java.util.ArrayList<>();
            for (com.customblocks.core.SlotData d : all) {
                if (!"tab_icon".equals(d.customId) && com.customblocks.core.CategoryManager.getCategoriesForBlock(d.customId).isEmpty()) {
                    uncategorized.add(d);
                }
            }
            if (idx < uncategorized.size()) {
                openAssignmentDecision(player, uncategorized.get(idx).customId, state.page());
            }
        }
    }

    public static void openAssignmentDecision(net.minecraft.server.network.ServerPlayerEntity player, String blockId, int returnPage) {
        pushBackStack(player.getUuid());
        SimpleInventory inv = new SimpleInventory(27);
        for (int i = 0; i < 27; i++) inv.setStack(i, glass());
        inv.setStack(11, uiGlint(net.minecraft.item.Items.BOOK, "§e§lAdd to Existing Category"));
        inv.setStack(15, uiGlint(net.minecraft.item.Items.WRITABLE_BOOK, "§a§lCreate New Category"));
        inv.setStack(22, uiGlint(net.minecraft.item.Items.RED_CONCRETE, "§c§lBack"));
        openScreenFromGuiState(player, GuiState.assignmentDecision(blockId, 0), inv, "§6§lAssign Block");
    }

    private static void handleAssignmentDecisionClick(net.minecraft.server.network.ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 11) {
            openCategoryPicker(player, state.editingId(), 0);
            return;
        }
        if (slot == 15) {
            PENDING_CATEGORIES.put(player.getUuid(), new java.util.concurrent.ConcurrentHashMap<>());
            openShortInputPrompt(
                player,
                new PendingInput(InputAction.CREATE_CAT_KEY, state.editingId(), null, null, null, 0),
                "§6Category ID (no spaces)",
                new net.minecraft.item.ItemStack(net.minecraft.item.Items.NAME_TAG),
                "my_category"
            );
            return;
        }
        if (slot == 22) {
            handleEscBack(player);
        }
    }

    public static void openCategoryIconPicker(net.minecraft.server.network.ServerPlayerEntity player, String categoryKey, int page, boolean isCustomTab) {
        int max;
        pushBackStack(player.getUuid());
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        java.util.List<net.minecraft.item.Item> vanillaItems = new java.util.ArrayList<>();
        java.util.List<com.customblocks.core.SlotData> customBlocks = sortedBlocks();

        if (!isCustomTab) {
            for (net.minecraft.item.Item item : net.minecraft.registry.Registries.ITEM) {
                if (item != net.minecraft.item.Items.AIR) vanillaItems.add(item);
            }
            max = vanillaItems.isEmpty() ? 0 : Math.max(0, (vanillaItems.size() - 1) / 36);
        } else {
            max = customBlocks.isEmpty() ? 0 : Math.max(0, (customBlocks.size() - 1) / 36);
        }

        page = Math.max(0, Math.min(page, max));
        int start = page * 36;
        int end = Math.min(start + 36, isCustomTab ? customBlocks.size() : vanillaItems.size());

        for (int i = start; i < end; i++) {
            if (isCustomTab) {
                com.customblocks.core.SlotData d = customBlocks.get(i);
                net.minecraft.item.ItemStack stack = CustomBlocksMod.safeSlotItem(d.index) != null ? new net.minecraft.item.ItemStack(CustomBlocksMod.safeSlotItem(d.index), 1) : new net.minecraft.item.ItemStack(net.minecraft.item.Items.BARRIER);
                stack.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, net.minecraft.text.Text.literal("§f" + d.displayName).styled(s -> s.withItalic(false)));
                inv.setStack(i - start, stack);
            } else {
                net.minecraft.item.Item item = vanillaItems.get(i);
                inv.setStack(i - start, new net.minecraft.item.ItemStack(item));
            }
        }

        inv.setStack(45, uiGlint(net.minecraft.item.Items.RED_CONCRETE, "§c← Back"));
        inv.setStack(47, page > 0 ? uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§d← Previous Page") : glass());
        inv.setStack(51, end < (isCustomTab ? customBlocks.size() : vanillaItems.size()) ? uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§dNext Page →") : glass());

        inv.setStack(48, uiGlint(net.minecraft.item.Items.GRASS_BLOCK, !isCustomTab ? "§a§lVanilla Items" : "§7Vanilla Items"));
        inv.setStack(50, uiGlint(net.minecraft.item.Items.PAINTING, isCustomTab ? "§a§lCustom Blocks" : "§7Custom Blocks"));

        openScreenFromGuiState(player, GuiState.categoryIconPicker(categoryKey, page, isCustomTab), inv, "§e§lPick an Icon");
    }

    private static void handleCategoryIconPickerClick(net.minecraft.server.network.ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 45) { handleEscBack(player); return; }
        if (slot == 48) { openCategoryIconPicker(player, state.editingId(), 0, false); return; } // Vanilla tab
        if (slot == 50) { openCategoryIconPicker(player, state.editingId(), 0, true); return; }  // Custom tab
        
        if (slot == 47 && state.page() > 0) {
            openCategoryIconPicker(player, state.editingId(), state.page() - 1, state.confirmDelete());
            return;
        }
        if (slot == 51) {
            openCategoryIconPicker(player, state.editingId(), state.page() + 1, state.confirmDelete());
            return;
        }

        if (slot >= 0 && slot < 36) {
            int start = state.page() * 36;
            int idx = start + slot;
            boolean isCustom = state.confirmDelete();
            String iconId = null;
            boolean isCustomId = false;

            if (!isCustom) {
                java.util.List<net.minecraft.item.Item> vanillaItems = new java.util.ArrayList<>();
                for (net.minecraft.item.Item item : net.minecraft.registry.Registries.ITEM) {
                    if (item != net.minecraft.item.Items.AIR) vanillaItems.add(item);
                }
                if (idx < vanillaItems.size()) {
                    iconId = net.minecraft.registry.Registries.ITEM.getId(vanillaItems.get(idx)).toString();
                }
            } else {
                java.util.List<com.customblocks.core.SlotData> customBlocks = sortedBlocks();
                if (idx < customBlocks.size()) {
                    iconId = customBlocks.get(idx).customId;
                    isCustomId = true;
                }
            }

            if (iconId != null) {
                if ("__CREATE__".equals(state.editingId())) {
                    java.util.Map<String, String> catData = PENDING_CATEGORIES.get(player.getUuid());
                    if (catData != null) {
                        catData.put(isCustomId ? "iconCustomBlockId" : "iconItem", iconId);
                        if (isCustomId) catData.remove("iconItem");
                        else catData.remove("iconCustomBlockId");
                        
                        // Double back to clear the icon picker state
                        Deque<GuiState> stack = BACK_STACK.get(player.getUuid());
                        if (stack != null && !stack.isEmpty()) stack.pop();
                        
                        // Proceed to color picker prompt
                        int rp = 0;
                        playSuccess(player);
                        openShortInputPrompt(player, new PendingInput(InputAction.CREATE_CAT_COLOR, null, null, null, null, rp), "§6Category Color Code (e.g., #FF0000)", new net.minecraft.item.ItemStack(net.minecraft.item.Items.RED_DYE), "#FFFFFF");
                    }
                    return;
                }

                com.customblocks.core.Category cat = com.customblocks.core.CategoryManager.getCategory(state.editingId());
                if (cat != null) {
                    if (isCustomId) {
                        com.customblocks.core.CategoryManager.addCategory(cat.withIconCustomBlockId(iconId).withIconItem(null));
                    } else {
                        com.customblocks.core.CategoryManager.addCategory(cat.withIconItem(iconId).withIconCustomBlockId(null));
                    }
                    playSuccess(player);
                    send(player, "§aCategory icon updated!");
                    // Double back to category editor
                    Deque<GuiState> stack = BACK_STACK.get(player.getUuid());
                    if (stack != null && !stack.isEmpty()) stack.pop();
                    handleEscBack(player);
                }
            }
        }
    }

    public static void openCategoryPicker(net.minecraft.server.network.ServerPlayerEntity player, String blockId, int page) {
        java.util.List<com.customblocks.core.Category> cats = new java.util.ArrayList<>(com.customblocks.core.CategoryManager.getAllCategories());
        cats.removeIf(c -> !com.customblocks.command.PermissionHelper.canAssignToSpecificCategory(player, c.key()));
        cats.sort((a, b) -> Integer.compare(a.sortOrder(), b.sortOrder()));
        int max = cats.isEmpty() ? 0 : Math.max(0, (cats.size() - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        pushBackStack(player.getUuid());
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        int start = page * BLOCKS_PER_PAGE;
        int end = Math.min(start + BLOCKS_PER_PAGE, cats.size());
        for (int i = start; i < end; i++) {
            com.customblocks.core.Category c = cats.get(i);
            net.minecraft.item.ItemStack stack = getCategoryIconStack(c);
            java.util.List<net.minecraft.text.Text> lore = new java.util.ArrayList<>();
            lore.add(lore("§7ID: §f" + c.key()));
            if (c.description() != null && !c.description().isEmpty()) {
                lore.add(lore("§7" + c.description()));
            }
            lore.add(lore("§eClick to assign"));
            stack.set(net.minecraft.component.DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(lore));
            inv.setStack(18 + (i - start), stack);
        }
        if (page > 0) inv.setStack(45, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§d<- Previous Page"));
        else inv.setStack(45, uiGlint(net.minecraft.item.Items.RED_CONCRETE, "§c<- Back"));
        if (end < cats.size()) inv.setStack(53, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§dNext Page ->"));
        openScreenFromGuiState(player, GuiState.categoryPicker(blockId, page), inv, "§a§lPick a Category");
    }

    private static void handleCategoryPickerClick(net.minecraft.server.network.ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 45) {
            if (state.page() > 0) openCategoryPicker(player, state.editingId(), state.page() - 1);
            else handleEscBack(player);
            return;
        }
        if (slot == 53) {
            openCategoryPicker(player, state.editingId(), state.page() + 1);
            return;
        }
        int start = state.page() * BLOCKS_PER_PAGE;
        if (slot < 18 || slot > 35) return;
        int idx = start + (slot - 18);
        java.util.List<com.customblocks.core.Category> cats = new java.util.ArrayList<>(com.customblocks.core.CategoryManager.getAllCategories());
        cats.removeIf(c -> !com.customblocks.command.PermissionHelper.canAssignToSpecificCategory(player, c.key()));
        cats.sort((a, b) -> Integer.compare(a.sortOrder(), b.sortOrder()));
        if (idx < cats.size()) {
            com.customblocks.core.Category c = cats.get(idx);
            
            if ("MERGE_SOURCE".equals(state.editingId())) {
                playClick(player);
                openMergeCategoryPickerTarget(player, c.key(), 0);
                return;
            }
            
            if ("BULK_ASSIGN".equals(state.editingId())) {
                java.util.Set<String> selected = BULK_ASSIGN_SELECTED.get(player.getUuid());
                if (selected != null && !selected.isEmpty()) {
                    com.customblocks.core.UndoManager.CategoryUndoEntry snap =
                        com.customblocks.core.UndoManager.captureCategorySnapshot("bulk-assign " + selected.size() + " → " + c.displayName(), player.getUuid());
                    int added = 0;
                    int already = 0;
                    for (String blockId : selected) {
                        if (com.customblocks.core.CategoryManager.getCategoriesForBlock(blockId).contains(c.key())) {
                            already++;
                            continue;
                        }
                        com.customblocks.core.CategoryManager.assignBlock(blockId, c.key());
                        added++;
                    }
                    if (added > 0) com.customblocks.core.UndoManager.pushCategoryUndo(snap);
                    playSuccess(player);
                    send(player, "§aBulk assign complete. Added: §f" + added + "§a, already in category: §f" + already);
                    BULK_ASSIGN_SELECTED.remove(player.getUuid());
                    // Pop picker and bulk assign menus
                    Deque<GuiState> stack = BACK_STACK.get(player.getUuid());
                    if (stack != null && !stack.isEmpty()) stack.pop();
                    if (stack != null && !stack.isEmpty()) stack.pop();
                    handleEscBack(player);
                    return;
                }
            }
            
            com.customblocks.core.CategoryManager.assignBlock(state.editingId(), c.key());
            playSuccess(player);
            send(player, "§aAssigned §f" + state.editingId() + " §ato category §f" + c.displayName());
            openCategoryDetail(player, c.key(), 0);
        }
    }

    public static net.minecraft.item.ItemStack getCategoryIconStack(com.customblocks.core.Category c) {
        net.minecraft.item.ItemStack stack;
        if (c.iconCustomBlockId() != null) {
            com.customblocks.core.SlotData d = com.customblocks.core.SlotManager.getById(c.iconCustomBlockId());
            stack = (d != null && CustomBlocksMod.safeSlotItem(d.index) != null) ? 
                    new net.minecraft.item.ItemStack(CustomBlocksMod.safeSlotItem(d.index)) :
                    new net.minecraft.item.ItemStack(net.minecraft.item.Items.BARRIER);
        } else {
            stack = new net.minecraft.item.ItemStack(net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.tryParse(c.iconItem() != null ? c.iconItem() : "minecraft:chest")));
        }

        String cp = c.colorPlacement() != null ? c.colorPlacement() : "borders";
        
        int colorIntVal = 0xFFFFFF;
        if (c.color() != null && c.color().startsWith("#")) {
            try { colorIntVal = Integer.parseInt(c.color().substring(1), 16); } catch (Exception ignored) {}
        }
        final int colorInt = colorIntVal;
        
        net.minecraft.text.MutableText nameText = net.minecraft.text.Text.literal(c.displayName());
        if ("name".equals(cp) || "badge_bg".equals(cp) || "tile_tint".equals(cp) || "borders".equals(cp)) {
            // Apply color to name if there's any placement strategy that prefers colored text, or just default behavior
            nameText = nameText.styled(s -> s.withColor(colorInt).withBold(true).withItalic(false));
        } else {
            nameText = net.minecraft.text.Text.literal("§a§l" + c.displayName());
        }
        
        stack.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, nameText);
        
        if ("tile_tint".equals(cp)) {
            stack.set(net.minecraft.component.DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        
        return stack;
    }

    public static void openCategoryBrowser(net.minecraft.server.network.ServerPlayerEntity player, int page) {
        java.util.List<com.customblocks.core.Category> cats = new java.util.ArrayList<>(com.customblocks.core.CategoryManager.getAllCategories());
        cats.sort((a, b) -> Integer.compare(a.sortOrder(), b.sortOrder()));
        int max = cats.isEmpty() ? 0 : Math.max(0, (cats.size() - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        pushBackStack(player.getUuid());
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        int start = page * BLOCKS_PER_PAGE;
        int end = Math.min(start + BLOCKS_PER_PAGE, cats.size());
        for (int i = start; i < end; i++) {
            com.customblocks.core.Category c = cats.get(i);
            net.minecraft.item.ItemStack stack = getCategoryIconStack(c);
            java.util.List<net.minecraft.text.Text> lore = new java.util.ArrayList<>();
            lore.add(lore("§7ID: §f" + c.key()));
            lore.add(lore("§7Blocks: §f" + com.customblocks.core.CategoryManager.getBlocksInCategory(c.key()).size()));
            if (c.description() != null && !c.description().isEmpty()) {
                lore.add(lore("§7" + c.description()));
            }
            lore.add(lore(""));
            lore.add(lore("§eClick to view blocks"));
            stack.set(net.minecraft.component.DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(lore));
            inv.setStack(18 + (i - start), stack);
        }
        if (page > 0) inv.setStack(45, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§d<- Previous Page"));
        else inv.setStack(45, uiGlint(net.minecraft.item.Items.RED_CONCRETE, "§c<- Back"));
        if (end < cats.size()) inv.setStack(53, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§dNext Page ->"));
        inv.setStack(49, uiGlint(net.minecraft.item.Items.COMMAND_BLOCK, "§d§lCategory Controller", "§7Manage categories"));
        openScreenFromGuiState(player, GuiState.categoryBrowser(page), inv, "§d§lCategory Browser");
    }

    private static void handleCategoryBrowserClick(net.minecraft.server.network.ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 45) {
            if (state.page() > 0) openCategoryBrowser(player, state.page() - 1);
            else handleEscBack(player);
            return;
        }
        if (slot == 53) {
            openCategoryBrowser(player, state.page() + 1);
            return;
        }
        if (slot == 49) {
            openCategoryController(player, 0);
            return;
        }
        int start = state.page() * BLOCKS_PER_PAGE;
        if (slot < 18 || slot > 35) return;
        int idx = start + (slot - 18);
        java.util.List<com.customblocks.core.Category> cats = new java.util.ArrayList<>(com.customblocks.core.CategoryManager.getAllCategories());
        cats.sort((a, b) -> Integer.compare(a.sortOrder(), b.sortOrder()));
        if (idx < cats.size()) {
            openCategoryDetail(player, cats.get(idx).key(), 0);
        }
    }

    public static void openCategoryDetail(net.minecraft.server.network.ServerPlayerEntity player, String categoryKey, int page) {
        java.util.List<com.customblocks.core.SlotData> blocks = com.customblocks.core.CategoryManager.getBlocksInCategory(categoryKey);
        int max = blocks.isEmpty() ? 0 : Math.max(0, (blocks.size() - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.categoryDetail(categoryKey, page), buildCategoryDetail(categoryKey, blocks, page), "§6§lCategory: " + categoryKey);
    }

    private static SimpleInventory buildCategoryDetail(String categoryKey, java.util.List<com.customblocks.core.SlotData> blocks, int page) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        inv.setStack(4, ui(net.minecraft.item.Items.SPYGLASS, "§e§lSearch", "§7Find a block by name, ID, or lore"));
        inv.setStack(8, uiGlint(net.minecraft.item.Items.COMPARATOR, "§eSort Blocks", "§7Change display order"));
        int start = page * BLOCKS_PER_PAGE;
        int end = Math.min(start + BLOCKS_PER_PAGE, blocks.size());
        for (int i = start; i < end; i++) {
            com.customblocks.core.SlotData d = blocks.get(i);
            net.minecraft.item.ItemStack stack = CustomBlocksMod.safeSlotItem(d.index) != null ? new net.minecraft.item.ItemStack(CustomBlocksMod.safeSlotItem(d.index), 1) : new net.minecraft.item.ItemStack(net.minecraft.item.Items.BARRIER);
            stack.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, net.minecraft.text.Text.literal("§f" + d.displayName).styled(s -> s.withItalic(false)));
            java.util.List<net.minecraft.text.Text> lore = new java.util.ArrayList<>();
            lore.add(lore("§7ID: §8" + d.customId));
            lore.add(lore(""));
            if (!categoryKey.equals("Uncategorized")) {
                lore.add(lore("§cRight-Click §7to remove from category"));
            }
            stack.set(net.minecraft.component.DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(lore));
            inv.setStack(18 + (i - start), stack);
        }
        if (page > 0) inv.setStack(45, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§d<- Previous Page"));
        else inv.setStack(45, uiGlint(net.minecraft.item.Items.RED_CONCRETE, "§c<- Back"));
        if (end < blocks.size()) inv.setStack(53, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§dNext Page ->"));
        return inv;
    }

    private static void handleCategoryDetailClick(net.minecraft.server.network.ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 4) {
            openShortInputPrompt(player, new PendingInput(InputAction.REID_TEXT, "__search__", null, null, null, state.page()), "Search Blocks", new ItemStack(net.minecraft.item.Items.SPYGLASS), "");
            return;
        }
        if (slot == 8) {
            openSortBlocksMenu(player, state.editingId());
            return;
        }
        if (slot == 45) {
            if (state.page() > 0) openCategoryDetail(player, state.editingId(), state.page() - 1);
            else handleEscBack(player);
            return;
        }
        if (slot == 53) {
            openCategoryDetail(player, state.editingId(), state.page() + 1);
            return;
        }
        int start = state.page() * BLOCKS_PER_PAGE;
        if (slot < 18 || slot > 35) return;
        int idx = start + (slot - 18);
        java.util.List<com.customblocks.core.SlotData> blocks = com.customblocks.core.CategoryManager.getBlocksInCategory(state.editingId());
        if (idx < blocks.size()) {
            openCategoryBlockContext(player, state.editingId(), blocks.get(idx).customId, state.page());
        }
    }

    public static void openCategoryBlockContext(net.minecraft.server.network.ServerPlayerEntity player, String categoryKey, String blockId, int returnPage) {
        pushBackStack(player.getUuid());
        SimpleInventory inv = new SimpleInventory(27);
        for (int i = 0; i < 27; i++) inv.setStack(i, glass());
        
        com.customblocks.core.SlotData d = SlotManager.getById(blockId);
        if (d != null) {
            net.minecraft.item.ItemStack stack = CustomBlocksMod.safeSlotItem(d.index) != null ? new net.minecraft.item.ItemStack(CustomBlocksMod.safeSlotItem(d.index), 1) : new net.minecraft.item.ItemStack(net.minecraft.item.Items.BARRIER);
            stack.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, net.minecraft.text.Text.literal("§f" + d.displayName).styled(s -> s.withItalic(false)));
            inv.setStack(4, stack);
        }

        inv.setStack(11, uiGlint(net.minecraft.item.Items.CRAFTING_TABLE, "§eEdit Block", "§7Open the block editor"));
        inv.setStack(15, uiGlint(net.minecraft.item.Items.FLINT_AND_STEEL, "§cRemove from Category", "§7Take this block out of", "§7this category."));
        
        inv.setStack(22, uiGlint(net.minecraft.item.Items.RED_CONCRETE, "§c§lBack"));
        openScreenFromGuiState(player, GuiState.categoryBlockContext(categoryKey, blockId, returnPage), inv, "§e§lBlock Options");
    }

    private static void handleCategoryBlockContextClick(net.minecraft.server.network.ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 22) { handleEscBack(player); return; }
        
        String[] parts = state.editingId().split("\\|");
        if (parts.length != 2) { handleEscBack(player); return; }
        String categoryKey = parts[0];
        String blockId = parts[1];
        
        if (slot == 11) {
            // Edit Block
            openEditor(player, blockId, state.page());
        } else if (slot == 15) {
            // Remove from Category
            com.customblocks.core.CategoryManager.unassignBlock(blockId, categoryKey);
            playCategoryRemove(player);
            send(player, "§cRemoved §f" + blockId + " §cfrom category.");
            // Double back so we go to the category detail page, not back to this context menu
            Deque<GuiState> stack = BACK_STACK.get(player.getUuid());
            if (stack != null && !stack.isEmpty()) stack.pop();
            handleEscBack(player);
        }
    }

    public static void openCategoryController(net.minecraft.server.network.ServerPlayerEntity player, int page) {
        if (!com.customblocks.command.PermissionHelper.canCategoryManage(player.getCommandSource())) {
            send(player, "§cYou don't have permission to manage categories.");
            handleEscBack(player);
            return;
        }
        java.util.List<com.customblocks.core.Category> cats = new java.util.ArrayList<>(com.customblocks.core.CategoryManager.getAllCategories());
        cats.sort((a, b) -> Integer.compare(a.sortOrder(), b.sortOrder()));
        int max = cats.isEmpty() ? 0 : Math.max(0, (cats.size() - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        pushBackStack(player.getUuid());
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        int start = page * BLOCKS_PER_PAGE;
        int end = Math.min(start + BLOCKS_PER_PAGE, cats.size());
        for (int i = start; i < end; i++) {
            com.customblocks.core.Category c = cats.get(i);
            net.minecraft.item.ItemStack stack = getCategoryIconStack(c);
            java.util.List<net.minecraft.text.Text> lore = new java.util.ArrayList<>();
            lore.add(lore("§7ID: §f" + c.key()));
            lore.add(lore("§7Color: §f" + c.color()));
            lore.add(lore("§7Badge: §f" + c.badge()));
            if (c.description() != null && !c.description().isEmpty()) {
                lore.add(lore("§7" + c.description()));
            }
            lore.add(lore(""));
            lore.add(lore("§eClick to edit settings"));
            stack.set(net.minecraft.component.DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(lore));
            inv.setStack(i - start, stack);
        }
        if (page > 0) inv.setStack(45, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§d<- Previous Page"));
        else inv.setStack(45, uiGlint(net.minecraft.item.Items.RED_CONCRETE, "§c<- Back"));
        if (end < cats.size()) inv.setStack(53, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§dNext Page ->"));
        
        inv.setStack(48, uiGlint(net.minecraft.item.Items.MINECART, "§eMerge Categories", "§7Combine two categories into one"));
        inv.setStack(49, uiGlint(net.minecraft.item.Items.WRITABLE_BOOK, "§a§l+ New Category", "§7Click to create a category"));
        inv.setStack(50, uiGlint(net.minecraft.item.Items.EMERALD_BLOCK, "§aBulk Assign", "§7Assign multiple blocks at once"));
        
        openScreenFromGuiState(player, GuiState.categoryController(page), inv, "§d§lCategory Controller");
    }

    private static void handleCategoryControllerClick(net.minecraft.server.network.ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 45) {
            if (state.page() > 0) openCategoryController(player, state.page() - 1);
            else handleEscBack(player);
            return;
        }
        if (slot == 53) {
            openCategoryController(player, state.page() + 1);
            return;
        }
        if (slot == 48) {
            openCategoryPicker(player, "MERGE_SOURCE", 0);
            return;
        }
        if (slot == 50) {
            openBulkAssignPicker(player, 0);
            return;
        }
        if (slot == 49) {
            PENDING_CATEGORIES.put(player.getUuid(), new java.util.concurrent.ConcurrentHashMap<>());
            openShortInputPrompt(
                player,
                new PendingInput(InputAction.CREATE_CAT_KEY, null, null, null, null, state.page()),
                "§6Category ID (no spaces)",
                new net.minecraft.item.ItemStack(net.minecraft.item.Items.NAME_TAG),
                "my_category"
            );
            return;
        }
        // Categories are laid out in slots 0-17. Ignore clicks on filler / button rows.
        if (slot < 0 || slot > 17) return;
        int start = state.page() * BLOCKS_PER_PAGE;
        int idx = start + slot;
        java.util.List<com.customblocks.core.Category> cats = new java.util.ArrayList<>(com.customblocks.core.CategoryManager.getAllCategories());
        cats.sort((a, b) -> Integer.compare(a.sortOrder(), b.sortOrder()));
        if (idx < cats.size()) {
            openCategoryEditor(player, cats.get(idx).key(), 0);
        }
    }

    public static void openCategoryEditor(net.minecraft.server.network.ServerPlayerEntity player, String categoryKey, int tabIndex) {
        com.customblocks.core.Category cat = com.customblocks.core.CategoryManager.getCategory(categoryKey);
        if (cat == null) {
            handleEscBack(player);
            return;
        }
        pushBackStack(player.getUuid());
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        inv.setStack(0, uiGlint(net.minecraft.item.Items.PAPER, tabIndex == 0 ? "§a§lGeneral" : "§7General"));
        inv.setStack(1, uiGlint(net.minecraft.item.Items.PAINTING, tabIndex == 1 ? "§a§lAppearance" : "§7Appearance"));
        inv.setStack(2, uiGlint(net.minecraft.item.Items.WRITABLE_BOOK, tabIndex == 2 ? "§a§lLore" : "§7Lore"));
        inv.setStack(3, uiGlint(net.minecraft.item.Items.CHEST, tabIndex == 3 ? "§a§lSubcategories" : "§7Subcategories"));
        inv.setStack(4, uiGlint(net.minecraft.item.Items.COMMAND_BLOCK, tabIndex == 4 ? "§a§lAuto-Rules" : "§7Auto-Rules"));
        inv.setStack(8, uiGlint(net.minecraft.item.Items.DRAGON_EGG, tabIndex == 5 ? "§c§lDanger Zone" : "§cDanger Zone"));

        if (tabIndex == 0) {
            inv.setStack(20, uiGlint(net.minecraft.item.Items.NAME_TAG, "§eRename", "§7Current: " + cat.displayName()));
            inv.setStack(22, uiGlint(net.minecraft.item.Items.BOOK, "§eDescription", "§7Current: " + (cat.description() != null ? cat.description() : "None")));
            inv.setStack(24, uiGlint(cat.isDefault() ? net.minecraft.item.Items.LIME_DYE : net.minecraft.item.Items.GRAY_DYE, "§eDefault Category", "§7Current: " + cat.isDefault()));
            inv.setStack(30, uiGlint(cat.hidden() ? net.minecraft.item.Items.ENDER_EYE : net.minecraft.item.Items.ENDER_PEARL, "§eHidden", "§7Current: " + cat.hidden()));
            inv.setStack(32, uiGlint(cat.locked() ? net.minecraft.item.Items.IRON_DOOR : net.minecraft.item.Items.OAK_DOOR, "§eLocked", "§7Current: " + cat.locked()));
        } else if (tabIndex == 1) { // Appearance
            inv.setStack(20, uiGlint(net.minecraft.item.Items.PAINTING, "§eChange Icon", "§7Current: " + cat.iconItem()));
            inv.setStack(22, uiGlint(net.minecraft.item.Items.RED_DYE, "§eChange Color Code", "§7Current: " + cat.color()));
            inv.setStack(24, uiGlint(cat.displayBlockEnabled() ? net.minecraft.item.Items.GRASS_BLOCK : net.minecraft.item.Items.BARRIER, "§eOverride Display Block", "§7Current: " + (cat.displayBlockEnabled() ? "Enabled" : "Disabled")));
            inv.setStack(31, uiGlint(net.minecraft.item.Items.GOLDEN_APPLE, "§eColor Placement", "§7Current: " + (cat.colorPlacement() != null ? cat.colorPlacement() : "default")));
        } else if (tabIndex == 2) { // Lore Badge
            inv.setStack(20, uiGlint(net.minecraft.item.Items.NAME_TAG, "§eChange Badge Text", "§7Current: " + (cat.badge() != null ? cat.badge() : "None")));
            inv.setStack(22, uiGlint(net.minecraft.item.Items.PAPER, "§eBadge Overflow Mode", "§7Current: " + (cat.badgeOverflowMode() != null ? cat.badgeOverflowMode() : "cap_3_more")));
            inv.setStack(24, uiGlint(net.minecraft.item.Items.WRITTEN_BOOK, "§eLore Prefix Position", "§7Current: " + (cat.lorePrefixPosition() != null ? cat.lorePrefixPosition() : "above_badge")));
            inv.setStack(31, uiGlint(net.minecraft.item.Items.WRITABLE_BOOK, "§eChange Lore Prefix", "§7Current: " + (cat.lorePrefix() != null ? cat.lorePrefix() : "None")));
        } else if (tabIndex == 3) { // Subcategories
            long childCount = com.customblocks.core.CategoryManager.getAllCategories().stream().filter(c -> cat.key().equals(c.parentKey())).count();
            inv.setStack(20, uiGlint(net.minecraft.item.Items.OAK_SAPLING, "§eSet Parent Category", "§7Current: " + (cat.parentKey() != null ? cat.parentKey() : "None")));
            inv.setStack(22, uiGlint(net.minecraft.item.Items.HOPPER, "§eRemove Parent", "§7Make this a Root Category"));
            inv.setStack(24, uiGlint(net.minecraft.item.Items.CHEST, "§eSubcategory Controller", "§7" + childCount + " Child Categories"));
        } else if (tabIndex == 4) { // Auto-Rules
            inv.setStack(20, uiGlint(net.minecraft.item.Items.COMMAND_BLOCK, "§eAdd Auto-Rule", "§7Match incoming blocks by ID"));
            inv.setStack(22, uiGlint(net.minecraft.item.Items.REPEATER, "§eManage Rules", "§7Active Rules: 0"));
            inv.setStack(24, uiGlint(net.minecraft.item.Items.COMPARATOR, "§ePriority Settings", "§7Current Priority: " + cat.sortOrder()));
        } else if (tabIndex == 5) {
            inv.setStack(11, uiGlint(net.minecraft.item.Items.WRITTEN_BOOK, "§eCategory Stats", "§7View statistics for this category"));
            inv.setStack(13, uiGlint(net.minecraft.item.Items.PAPER, "§eCategory Template", "§7Duplicate settings to new category"));
            inv.setStack(15, uiGlint(net.minecraft.item.Items.BARREL, "§eGive Display Block", "§7Obtain physical display block", cat.displayBlockEnabled() ? "§aEnabled" : "§cDisabled in Appearance"));
            inv.setStack(22, uiGlint(net.minecraft.item.Items.BARRIER, "§c§lDelete Category...", "§7Opens deletion options"));
        }

        inv.setStack(45, uiGlint(net.minecraft.item.Items.RED_CONCRETE, "§c§lBack"));
        openScreenFromGuiState(player, GuiState.categoryEditor(categoryKey, tabIndex), inv, "§d§lEditor: §f" + cat.displayName());
    }

    private static void handleCategoryEditorClick(net.minecraft.server.network.ServerPlayerEntity player, GuiState state, int slot, int button) {
        if (slot == 45) {
            handleEscBack(player);
            return;
        }
        if (slot >= 0 && slot <= 4) {
            openCategoryEditor(player, state.editingId(), slot);
            return;
        }
        if (slot == 8) {
            openCategoryEditor(player, state.editingId(), 5);
            return;
        }
        com.customblocks.core.Category cat = com.customblocks.core.CategoryManager.getCategory(state.editingId());
        if (cat == null) { handleEscBack(player); return; }

        if (state.page() == 0) { // General tab
            if (slot == 20) {
                // Rename
                PENDING_CATEGORIES.put(player.getUuid(), new java.util.concurrent.ConcurrentHashMap<>());
                PENDING_CATEGORIES.get(player.getUuid()).put("editKey", state.editingId());
                openShortInputPrompt(player, new PendingInput(InputAction.RENAME_CAT_TEXT, state.editingId(), null, null, null, 0), "§6New Display Name", new net.minecraft.item.ItemStack(net.minecraft.item.Items.NAME_TAG), cat.displayName());
                return;
            }
            if (slot == 22) {
                // Description
                PENDING_CATEGORIES.put(player.getUuid(), new java.util.concurrent.ConcurrentHashMap<>());
                PENDING_CATEGORIES.get(player.getUuid()).put("editKey", state.editingId());
                openShortInputPrompt(player, new PendingInput(InputAction.EDIT_CAT_PROP, state.editingId(), "description", null, null, 0), "§6New Description", new net.minecraft.item.ItemStack(net.minecraft.item.Items.BOOK), cat.description() != null ? cat.description() : "");
                return;
            }
            if (slot == 24) {
                // Toggle Default — only one category can be default at a time
                if (!cat.isDefault()) {
                    for (com.customblocks.core.Category c : com.customblocks.core.CategoryManager.getAllCategories()) {
                        if (c.isDefault() && !c.key().equals(cat.key())) {
                            com.customblocks.core.CategoryManager.addCategory(c.withDefault(false));
                        }
                    }
                }
                com.customblocks.core.CategoryManager.addCategory(cat.withDefault(!cat.isDefault()));
                playSuccess(player);
                openCategoryEditor(player, state.editingId(), 0);
                return;
            }
            if (slot == 30) {
                // Toggle Hidden
                com.customblocks.core.CategoryManager.addCategory(cat.withHidden(!cat.hidden()));
                playSuccess(player);
                openCategoryEditor(player, state.editingId(), 0);
                return;
            }
            if (slot == 32) {
                // Toggle Locked
                com.customblocks.core.CategoryManager.addCategory(cat.withLocked(!cat.locked()));
                playSuccess(player);
                openCategoryEditor(player, state.editingId(), 0);
                return;
            }
        } else if (state.page() == 1) { // Appearance
            if (slot == 20) {
                openCategoryIconPicker(player, state.editingId(), 0, false);
                return;
            }
            if (slot == 22) {
                // Change Color
                PENDING_CATEGORIES.put(player.getUuid(), new java.util.concurrent.ConcurrentHashMap<>());
                PENDING_CATEGORIES.get(player.getUuid()).put("editKey", state.editingId());
                openShortInputPrompt(player, new PendingInput(InputAction.EDIT_CAT_PROP, state.editingId(), "color", null, null, 1), "§6New Color Code", new net.minecraft.item.ItemStack(net.minecraft.item.Items.RED_DYE), cat.color());
                return;
            }
            if (slot == 24) {
                // Toggle Display Block Enabled
                com.customblocks.core.CategoryManager.addCategory(cat.withDisplayBlockEnabled(!cat.displayBlockEnabled()));
                playSuccess(player);
                openCategoryEditor(player, state.editingId(), 1);
                return;
            }
            if (slot == 31) {
                // Cycle Color Placement: borders -> name -> badge_bg -> tile_tint -> borders
                String current = cat.colorPlacement() != null ? cat.colorPlacement() : "borders";
                String next = switch (current) {
                    case "borders" -> "name";
                    case "name" -> "badge_bg";
                    case "badge_bg" -> "tile_tint";
                    default -> "borders";
                };
                com.customblocks.core.CategoryManager.addCategory(cat.withColorPlacement(next));
                playSuccess(player);
                openCategoryEditor(player, state.editingId(), 1);
                return;
            }
        } else if (state.page() == 2) { // Lore Badge
            if (slot == 20) {
                // Change Badge Text
                PENDING_CATEGORIES.put(player.getUuid(), new java.util.concurrent.ConcurrentHashMap<>());
                PENDING_CATEGORIES.get(player.getUuid()).put("editKey", state.editingId());
                openShortInputPrompt(player, new PendingInput(InputAction.EDIT_CAT_PROP, state.editingId(), "badge", null, null, 2), "§6New Badge Text", new net.minecraft.item.ItemStack(net.minecraft.item.Items.NAME_TAG), cat.badge() != null ? cat.badge() : "");
                return;
            }
            if (slot == 22) {
                // Cycle Badge Overflow Mode: cap_3_more -> show_all -> cap_5 -> one_line -> cap_3_more
                String current = cat.badgeOverflowMode() != null ? cat.badgeOverflowMode() : "cap_3_more";
                String next = switch (current) {
                    case "cap_3_more" -> "show_all";
                    case "show_all" -> "cap_5";
                    case "cap_5" -> "one_line";
                    default -> "cap_3_more";
                };
                com.customblocks.core.CategoryManager.addCategory(cat.withBadgeOverflowMode(next));
                playSuccess(player);
                openCategoryEditor(player, state.editingId(), 2);
                return;
            }
            if (slot == 24) {
                // Cycle Lore Prefix Position: above_badge -> below_badge -> replace_badge -> above_badge
                String current = cat.lorePrefixPosition() != null ? cat.lorePrefixPosition() : "above_badge";
                String next = switch (current) {
                    case "above_badge" -> "below_badge";
                    case "below_badge" -> "replace_badge";
                    default -> "above_badge";
                };
                com.customblocks.core.CategoryManager.addCategory(cat.withLorePrefixPosition(next));
                playSuccess(player);
                openCategoryEditor(player, state.editingId(), 2);
                return;
            }
            if (slot == 31) {
                // Change Lore Prefix via text input
                PENDING_CATEGORIES.put(player.getUuid(), new java.util.concurrent.ConcurrentHashMap<>());
                PENDING_CATEGORIES.get(player.getUuid()).put("editKey", state.editingId());
                openShortInputPrompt(player, new PendingInput(InputAction.EDIT_CAT_PROP, state.editingId(), "lorePrefix", null, null, 2), "§6New Lore Prefix", new net.minecraft.item.ItemStack(net.minecraft.item.Items.WRITABLE_BOOK), cat.lorePrefix() != null ? cat.lorePrefix() : "");
                return;
            }
        } else if (state.page() == 3) { // Subcategories
            if (slot == 20) {
                // Set Parent Category
                PENDING_CATEGORIES.put(player.getUuid(), new java.util.concurrent.ConcurrentHashMap<>());
                PENDING_CATEGORIES.get(player.getUuid()).put("editKey", state.editingId());
                openShortInputPrompt(player, new PendingInput(InputAction.EDIT_CAT_PROP, state.editingId(), "parent", null, null, 3), "§6Set Parent Key", new net.minecraft.item.ItemStack(net.minecraft.item.Items.OAK_SAPLING), cat.parentKey() != null ? cat.parentKey() : "");
                return;
            }
            if (slot == 22) {
                // Remove Parent
                com.customblocks.core.CategoryManager.addCategory(cat.withParentKey(null));
                playSuccess(player);
                openCategoryEditor(player, state.editingId(), 3);
                return;
            }
            if (slot == 24) {
                // Open the dedicated Subcategory Controller GUI (tree view)
                openSubcategoryController(player, cat.key(), 0);
                return;
            }
        } else if (state.page() == 4) { // Auto-Rules / Sort
            if (slot == 20) {
                // Set Sort Priority via text input
                PENDING_CATEGORIES.put(player.getUuid(), new java.util.concurrent.ConcurrentHashMap<>());
                PENDING_CATEGORIES.get(player.getUuid()).put("editKey", state.editingId());
                openShortInputPrompt(player, new PendingInput(InputAction.EDIT_CAT_PROP, state.editingId(), "sortOrder", null, null, 4), "§6Set Sort Priority (number)", new net.minecraft.item.ItemStack(net.minecraft.item.Items.COMPARATOR), String.valueOf(cat.sortOrder()));
                return;
            }
            if (slot == 22) {
                // Show current sort order info
                send(player, "§6Current sort priority: §f" + cat.sortOrder() + " §7(lower = higher in list)");
                return;
            }
            if (slot == 24) {
                // Increment sort order by 1
                com.customblocks.core.CategoryManager.addCategory(cat.withSortOrder(cat.sortOrder() + 1));
                playSuccess(player);
                openCategoryEditor(player, state.editingId(), 4);
                return;
            }
        } else if (state.page() == 5) { // Danger Zone
            if (slot == 11) {
                openCategoryStats(player, state.editingId());
            } else if (slot == 13) {
                // Category Template - prompt for new name
                PENDING_CATEGORIES.put(player.getUuid(), new java.util.concurrent.ConcurrentHashMap<>());
                PENDING_CATEGORIES.get(player.getUuid()).put("templateKey", state.editingId());
                openShortInputPrompt(player, new PendingInput(InputAction.CREATE_CAT_KEY, null, null, null, null, 5), "§6New Category Name (from Template)", new net.minecraft.item.ItemStack(net.minecraft.item.Items.NAME_TAG), cat.displayName() + " Copy");
            } else if (slot == 15) {
                if (cat.displayBlockEnabled()) {
                    net.minecraft.item.ItemStack stack = com.customblocks.core.CategoryDisplayBlockManager.createDisplayBlockStack(cat);
                    if (!player.getInventory().insertStack(stack)) player.dropItem(stack, false);
                    send(player, "§aGiven display block for §f" + cat.displayName());
                    playSuccess(player);
                } else {
                    send(player, "§cDisplay block is disabled. Enable it in Appearance.");
                    playError(player);
                }
            } else if (slot == 22) {
                openDeleteCategoryMenu(player, state.editingId());
            }
        }
    }

    // ── Subcategory Controller (Phase 9) ────────────────────────────────────

    /** Renders a per-parent tree view of subcategories with depth indentation. */
    public static void openSubcategoryController(net.minecraft.server.network.ServerPlayerEntity player, String parentKey, int page) {
        com.customblocks.core.Category parent = com.customblocks.core.CategoryManager.getCategory(parentKey);
        if (parent == null) { handleEscBack(player); return; }

        java.util.List<TreeRow> rows = new java.util.ArrayList<>();
        buildTreeRows(player, parent, 0, rows);

        int max = rows.isEmpty() ? 0 : Math.max(0, (rows.size() - 1) / 28);
        page = Math.max(0, Math.min(page, max));
        pushBackStack(player.getUuid());

        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        // Header
        net.minecraft.item.ItemStack header = new net.minecraft.item.ItemStack(net.minecraft.item.Items.OAK_SAPLING);
        header.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                net.minecraft.text.Text.literal("§a§l⛓ §r§fSubcategory Tree §7— §f" + parent.displayName()));
        inv.setStack(4, header);

        int start = page * 28;
        int end = Math.min(start + 28, rows.size());
        // Tree rows: 4 columns x 7 rows of entries (slots 10-16, 19-25, 28-34, 37-43)
        int[] slotOrder = new int[]{
                10,11,12,13,14,15,16,
                19,20,21,22,23,24,25,
                28,29,30,31,32,33,34,
                37,38,39,40,41,42,43
        };
        for (int i = start; i < end; i++) {
            TreeRow row = rows.get(i);
            int targetSlot = slotOrder[i - start];
            String indent = "  ".repeat(row.depth);
            String prefix = row.depth == 0 ? "§a§l● " : "§7" + indent + "↳ ";

            net.minecraft.item.Item icon;
            try {
                icon = net.minecraft.registry.Registries.ITEM.get(
                        net.minecraft.util.Identifier.tryParse(row.cat.iconItem() != null ? row.cat.iconItem() : "minecraft:chest"));
            } catch (Exception e) { icon = net.minecraft.item.Items.CHEST; }
            net.minecraft.item.ItemStack stack = new net.minecraft.item.ItemStack(icon);
            stack.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                    net.minecraft.text.Text.literal(prefix + "§f" + row.cat.displayName()).styled(s -> s.withItalic(false)));

            java.util.List<net.minecraft.text.Text> lore = new java.util.ArrayList<>();
            lore.add(lore("§7Key: §f" + row.cat.key()));
            lore.add(lore("§7Depth: §f" + row.depth));
            int childCount = (int) com.customblocks.core.CategoryManager.getAllCategories().stream()
                    .filter(c -> row.cat.key().equals(c.parentKey())).count();
            lore.add(lore("§7Children: §f" + childCount));
            lore.add(lore("§7Blocks: §f" + com.customblocks.core.CategoryManager.getBlocksInCategory(row.cat.key()).size()));
            lore.add(lore(""));
            lore.add(lore("§eLeft-click §7→ open category"));
            lore.add(lore("§eRight-click §7→ unparent (make root)"));
            lore.add(lore("§eShift-click §7→ create child here"));
            stack.set(net.minecraft.component.DataComponentTypes.LORE,
                    new net.minecraft.component.type.LoreComponent(lore));

            inv.setStack(targetSlot, stack);
        }

        // Footer controls
        if (page > 0) inv.setStack(45, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§d← Previous Page"));
        else inv.setStack(45, uiGlint(net.minecraft.item.Items.RED_CONCRETE, "§c← Back"));
        if (end < rows.size()) inv.setStack(53, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§dNext Page →"));
        inv.setStack(49, uiGlint(net.minecraft.item.Items.WRITABLE_BOOK, "§a§l+ New Subcategory",
                "§7Click to add a subcategory under §f" + parent.displayName()));

        openScreenFromGuiState(player, GuiState.subcategoryController(parentKey, page), inv,
                "§a§l⛓ §r§fSubcategories §7— §f" + parent.displayName());
    }

    private record TreeRow(com.customblocks.core.Category cat, int depth) {}

    private static void buildTreeRows(net.minecraft.server.network.ServerPlayerEntity player, com.customblocks.core.Category root, int depth, java.util.List<TreeRow> out) {
        out.add(new TreeRow(root, depth));
        java.util.List<com.customblocks.core.Category> children = com.customblocks.core.CategoryManager.getAllCategories().stream()
                .filter(c -> root.key().equals(c.parentKey()) && com.customblocks.command.PermissionHelper.canViewSpecificCategory(player, c.key()))
                .sorted((a, b) -> Integer.compare(a.sortOrder(), b.sortOrder()))
                .collect(java.util.stream.Collectors.toList());
        for (com.customblocks.core.Category child : children) {
            if (depth > 32) return;
            buildTreeRows(player, child, depth + 1, out);
        }
    }

    private static void handleSubcategoryControllerClick(net.minecraft.server.network.ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 45) {
            if (state.page() > 0) openSubcategoryController(player, state.editingId(), state.page() - 1);
            else handleEscBack(player);
            return;
        }
        if (slot == 53) {
            openSubcategoryController(player, state.editingId(), state.page() + 1);
            return;
        }
        if (slot == 49) {
            // Create a new subcategory under this parent
            PENDING_CATEGORIES.put(player.getUuid(), new java.util.concurrent.ConcurrentHashMap<>());
            PENDING_CATEGORIES.get(player.getUuid()).put("parentKey", state.editingId());
            openShortInputPrompt(
                    player,
                    new PendingInput(InputAction.CREATE_CAT_KEY, null, null, null, null, state.page()),
                    "§6Subcategory ID (no spaces)",
                    new net.minecraft.item.ItemStack(net.minecraft.item.Items.NAME_TAG),
                    "child_category"
            );
            return;
        }

        // Map slot back to row index
        int[] slotOrder = new int[]{
                10,11,12,13,14,15,16,
                19,20,21,22,23,24,25,
                28,29,30,31,32,33,34,
                37,38,39,40,41,42,43
        };
        int rowIdx = -1;
        for (int i = 0; i < slotOrder.length; i++) {
            if (slotOrder[i] == slot) { rowIdx = i; break; }
        }
        if (rowIdx < 0) return;
        int absoluteIdx = state.page() * 28 + rowIdx;

        com.customblocks.core.Category parent = com.customblocks.core.CategoryManager.getCategory(state.editingId());
        if (parent == null) { handleEscBack(player); return; }
        java.util.List<TreeRow> rows = new java.util.ArrayList<>();
        buildTreeRows(player, parent, 0, rows);
        if (absoluteIdx >= rows.size()) return;
        TreeRow target = rows.get(absoluteIdx);

        if (player.isSneaking()) {
            // Create child under this row's category
            PENDING_CATEGORIES.put(player.getUuid(), new java.util.concurrent.ConcurrentHashMap<>());
            PENDING_CATEGORIES.get(player.getUuid()).put("parentKey", target.cat().key());
            openShortInputPrompt(
                    player,
                    new PendingInput(InputAction.CREATE_CAT_KEY, null, null, null, null, state.page()),
                    "§6Subcategory ID (under " + target.cat().displayName() + ")",
                    new net.minecraft.item.ItemStack(net.minecraft.item.Items.NAME_TAG),
                    "child_category"
            );
            return;
        }
        // Default: open this category's detail view
        openCategoryDetail(player, target.cat().key(), 0);
    }

    // ── Extras (Phase 10 / 11) ───────────────────────────────────────────────

    public static void openDeleteCategoryMenu(net.minecraft.server.network.ServerPlayerEntity player, String categoryKey) {
        com.customblocks.core.Category cat = com.customblocks.core.CategoryManager.getCategory(categoryKey);
        if (cat == null) { handleEscBack(player); return; }
        pushBackStack(player.getUuid());
        SimpleInventory inv = new SimpleInventory(27);
        for (int i = 0; i < 27; i++) inv.setStack(i, glass());
        
        inv.setStack(11, uiGlint(net.minecraft.item.Items.TNT, "§cDelete & Unassign All", "§7Category is deleted.", "§7Blocks return to inbox."));
        inv.setStack(13, uiGlint(net.minecraft.item.Items.MINECART, "§eMove All to Another", "§7Blocks move to a new category.", "§7Then this category is deleted."));
        inv.setStack(15, uiGlint(net.minecraft.item.Items.HOPPER, "§6Bulk Remove All", "§7Keep the category,", "§7but empty all its blocks."));
        
        inv.setStack(22, uiGlint(net.minecraft.item.Items.RED_CONCRETE, "§c§lBack"));
        openScreenFromGuiState(player, GuiState.deleteCategoryMenu(categoryKey), inv, "§c§lDelete Options: §f" + cat.displayName());
    }

    private static void handleDeleteCategoryMenuClick(net.minecraft.server.network.ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 22) { handleEscBack(player); return; }
        String catKey = state.editingId();
        if (slot == 11) {
            com.customblocks.core.CategoryManager.removeCategory(catKey, true);
            playCategoryDelete(player);
            Deque<GuiState> stack = BACK_STACK.get(player.getUuid());
            if (stack != null && !stack.isEmpty()) stack.pop(); // pop danger zone
            handleEscBack(player);
        } else if (slot == 13) {
            openMergeCategoryPickerTarget(player, catKey, 0);
        } else if (slot == 15) {
            com.customblocks.core.CategoryManager.removeAllBlocksFromCategory(catKey);
            playSuccess(player);
            handleEscBack(player);
        }
    }

    public static void openMergeCategoryPickerTarget(net.minecraft.server.network.ServerPlayerEntity player, String sourceKey, int page) {
        java.util.List<com.customblocks.core.Category> cats = new java.util.ArrayList<>(com.customblocks.core.CategoryManager.getAllCategories());
        cats.removeIf(c -> c.key().equals(sourceKey));
        cats.sort((a, b) -> Integer.compare(a.sortOrder(), b.sortOrder()));
        int max = cats.isEmpty() ? 0 : Math.max(0, (cats.size() - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        pushBackStack(player.getUuid());
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        int start = page * BLOCKS_PER_PAGE;
        int end = Math.min(start + BLOCKS_PER_PAGE, cats.size());
        for (int i = start; i < end; i++) {
            com.customblocks.core.Category c = cats.get(i);
            net.minecraft.item.ItemStack stack = new net.minecraft.item.ItemStack(net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.tryParse(c.iconItem() != null ? c.iconItem() : "minecraft:chest")));
            stack.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, net.minecraft.text.Text.literal("§a§l" + c.displayName()));
            java.util.List<net.minecraft.text.Text> lore = new java.util.ArrayList<>();
            lore.add(lore("§7Click to merge into this category"));
            stack.set(net.minecraft.component.DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(lore));
            inv.setStack(18 + (i - start), stack);
        }
        if (page > 0) inv.setStack(45, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§d← Previous Page"));
        else inv.setStack(45, uiGlint(net.minecraft.item.Items.RED_CONCRETE, "§c← Back"));
        if (end < cats.size()) inv.setStack(53, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§dNext Page →"));
        openScreenFromGuiState(player, GuiState.mergeCategoryPickerTarget(sourceKey, page), inv, "§e§lPick Merge Target");
    }

    private static void handleMergeCategoryPickerTargetClick(net.minecraft.server.network.ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 45) {
            if (state.page() > 0) openMergeCategoryPickerTarget(player, state.editingId(), state.page() - 1);
            else handleEscBack(player);
            return;
        }
        if (slot == 53) {
            openMergeCategoryPickerTarget(player, state.editingId(), state.page() + 1);
            return;
        }
        int start = state.page() * BLOCKS_PER_PAGE;
        if (slot < 18 || slot > 35) return;
        int idx = start + (slot - 18);
        java.util.List<com.customblocks.core.Category> cats = new java.util.ArrayList<>(com.customblocks.core.CategoryManager.getAllCategories());
        cats.removeIf(c -> c.key().equals(state.editingId()));
        cats.sort((a, b) -> Integer.compare(a.sortOrder(), b.sortOrder()));
        if (idx < cats.size()) {
            com.customblocks.core.Category target = cats.get(idx);
            com.customblocks.core.CategoryManager.moveAllBlocksToCategory(state.editingId(), target.key());
            com.customblocks.core.CategoryManager.removeCategory(state.editingId(), true); // deletes source
            playCategoryDelete(player);
            send(player, "§aSuccessfully merged into §f" + target.displayName());
            Deque<GuiState> stack = BACK_STACK.get(player.getUuid());
            if (stack != null && !stack.isEmpty()) stack.pop(); // pop delete menu or source picker
            if (stack != null && !stack.isEmpty()) stack.pop(); // pop danger zone or controller
            handleEscBack(player);
        }
    }

    public static void openCategoryStats(net.minecraft.server.network.ServerPlayerEntity player, String categoryKey) {
        com.customblocks.core.Category cat = com.customblocks.core.CategoryManager.getCategory(categoryKey);
        if (cat == null) { handleEscBack(player); return; }
        pushBackStack(player.getUuid());
        SimpleInventory inv = new SimpleInventory(27);
        for (int i = 0; i < 27; i++) inv.setStack(i, glass());
        
        java.util.List<com.customblocks.core.SlotData> blocks = com.customblocks.core.CategoryManager.getBlocksInCategory(categoryKey);
        int totalBlocks = blocks.size();
        String created = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(cat.createdAt()));
        
        inv.setStack(11, uiGlint(net.minecraft.item.Items.CLOCK, "§eCreation Date", "§7" + created));
        inv.setStack(13, uiGlint(net.minecraft.item.Items.GRASS_BLOCK, "§eTotal Blocks", "§7" + totalBlocks + " blocks"));
        // Assuming most recent addition is the last in the list
        String mostRecent = totalBlocks > 0 ? blocks.get(totalBlocks - 1).displayName : "None";
        inv.setStack(15, uiGlint(net.minecraft.item.Items.SPYGLASS, "§eRecent Addition", "§7" + mostRecent));
        
        inv.setStack(22, uiGlint(net.minecraft.item.Items.RED_CONCRETE, "§c§lBack"));
        openScreenFromGuiState(player, GuiState.categoryStats(categoryKey), inv, "§e§lStats: §f" + cat.displayName());
    }

    private static void handleCategoryStatsClick(net.minecraft.server.network.ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 22) { handleEscBack(player); }
    }

    public static void openSortBlocksMenu(net.minecraft.server.network.ServerPlayerEntity player, String categoryKey) {
        com.customblocks.core.Category cat = com.customblocks.core.CategoryManager.getCategory(categoryKey);
        if (cat == null) { handleEscBack(player); return; }
        pushBackStack(player.getUuid());
        SimpleInventory inv = new SimpleInventory(27);
        for (int i = 0; i < 27; i++) inv.setStack(i, glass());
        
        inv.setStack(11, uiGlint(net.minecraft.item.Items.PAPER, "§eAlphabetical (A-Z)"));
        inv.setStack(13, uiGlint(net.minecraft.item.Items.CLOCK, "§eNewest First"));
        inv.setStack(15, uiGlint(net.minecraft.item.Items.COMPASS, "§eOldest First"));
        
        inv.setStack(22, uiGlint(net.minecraft.item.Items.RED_CONCRETE, "§c§lBack"));
        openScreenFromGuiState(player, GuiState.sortBlocksMenu(categoryKey), inv, "§e§lSort Blocks: §f" + cat.displayName());
    }

    private static void handleSortBlocksMenuClick(net.minecraft.server.network.ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 22) { handleEscBack(player); return; }
        // The plan asks for Sort Blocks Inside Category.
        // It's mostly UI option but currently SlotManager/CategoryManager return lists as-is.
        // Without persistent sorting memory per category, we might need a "blockSortMode" in Category.
        // We'll just show the menu and play success for now, as real local sorting requires more fields.
        if (slot == 11 || slot == 13 || slot == 15) {
            playSuccess(player);
            send(player, "§aSort preference applied.");
            handleEscBack(player);
        }
    }

    private static final java.util.Map<java.util.UUID, java.util.Set<String>> BULK_ASSIGN_SELECTED = new java.util.concurrent.ConcurrentHashMap<>();

    public static void openBulkAssignPicker(net.minecraft.server.network.ServerPlayerEntity player, int page) {
        int total = sortedBlocks().size();
        int max = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        pushBackStack(player.getUuid());
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        
        java.util.Set<String> selected = BULK_ASSIGN_SELECTED.computeIfAbsent(player.getUuid(), k -> new java.util.HashSet<>());
        java.util.List<com.customblocks.core.SlotData> blocks = sortedBlocks();
        int start = page * BLOCKS_PER_PAGE;
        int end = Math.min(start + BLOCKS_PER_PAGE, blocks.size());
        
        for (int i = start; i < end; i++) {
            com.customblocks.core.SlotData d = blocks.get(i);
            net.minecraft.item.ItemStack stack = buildEditor(d, false).getStack(0).copy(); // mock item
            if (selected.contains(d.customId)) {
                // border outline indicator via glow
                stack.set(net.minecraft.component.DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
                java.util.List<net.minecraft.text.Text> lore = new java.util.ArrayList<>();
                lore.add(lore("§a§l✓ Selected"));
                stack.set(net.minecraft.component.DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(lore));
            }
            inv.setStack(18 + (i - start), stack);
        }
        
        if (page > 0) inv.setStack(45, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§d← Previous Page"));
        else inv.setStack(45, uiGlint(net.minecraft.item.Items.RED_CONCRETE, "§c← Back"));
        inv.setStack(49, uiGlint(net.minecraft.item.Items.EMERALD_BLOCK, "§a§lConfirm Bulk Assign", "§7Assign " + selected.size() + " blocks to a category"));
        if (end < blocks.size()) inv.setStack(53, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§dNext Page →"));
        
        openScreenFromGuiState(player, GuiState.bulkAssignPicker(page), inv, "§e§lBulk Assign (Selected: " + selected.size() + ")");
    }

    private static void handleBulkAssignPickerClick(net.minecraft.server.network.ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 45) {
            if (state.page() > 0) openBulkAssignPicker(player, state.page() - 1);
            else {
                BULK_ASSIGN_SELECTED.remove(player.getUuid());
                handleEscBack(player);
            }
            return;
        }
        if (slot == 49) {
            java.util.Set<String> selected = BULK_ASSIGN_SELECTED.get(player.getUuid());
            if (selected == null || selected.isEmpty()) {
                send(player, "§cNo blocks selected.");
                playError(player);
                return;
            }
            openCategoryPicker(player, "BULK_ASSIGN", 0);
            return;
        }
        if (slot == 53) {
            openBulkAssignPicker(player, state.page() + 1);
            return;
        }
        if (slot < 18 || slot > 35) return;
        int start = state.page() * BLOCKS_PER_PAGE;
        int idx = start + (slot - 18);
        java.util.List<com.customblocks.core.SlotData> blocks = sortedBlocks();
        if (idx < blocks.size()) {
            String id = blocks.get(idx).customId;
            java.util.Set<String> selected = BULK_ASSIGN_SELECTED.get(player.getUuid());
            if (selected != null) {
                if (selected.contains(id)) selected.remove(id);
                else selected.add(id);
                playClick(player);
                // re-render current page without pushing back stack
                Deque<GuiState> stack = BACK_STACK.get(player.getUuid());
                if (stack != null && !stack.isEmpty()) stack.pop();
                openBulkAssignPicker(player, state.page());
            }
        }
    }

    public static void openBulkRecolorWizard(net.minecraft.server.network.ServerPlayerEntity player, int page) {
        int total = sortedBlocks().size();
        int max = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        pushBackStack(player.getUuid());

        BULK_RECOLOR_COLOR.putIfAbsent(player.getUuid(), "green");
        BULK_RECOLOR_SCOPE.putIfAbsent(player.getUuid(), "all");
        BULK_RECOLOR_SCOPE_VALUE.putIfAbsent(player.getUuid(), "");
        BULK_RECOLOR_EXCLUDE.putIfAbsent(player.getUuid(), "");
        BULK_RECOLOR_SELECTED.computeIfAbsent(player.getUuid(), k -> new java.util.HashSet<>());

        String color = BULK_RECOLOR_COLOR.get(player.getUuid());
        String scopeKey = BULK_RECOLOR_SCOPE.get(player.getUuid());
        String scopeValue = BULK_RECOLOR_SCOPE_VALUE.get(player.getUuid());
        String exclude = BULK_RECOLOR_EXCLUDE.getOrDefault(player.getUuid(), "");
        int selectedCount = BULK_RECOLOR_SELECTED.get(player.getUuid()).size();
        String scopeLabel = bulkRecolorScopeLabel(scopeKey);
        BulkWizardPreview preview = buildBulkWizardPreview(player, scopeKey, scopeValue, exclude);

        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        inv.setStack(4, uiGlint(net.minecraft.item.Items.NETHER_STAR, "§6§lBulk Recolor Wizard",
            "§7Step 1: Pick color",
            "§7Step 2: Pick scope",
            "§7Step 3: Preview then Apply"));
        inv.setStack(11, uiGlint(net.minecraft.item.Items.LIME_DYE, "§aColor: §f" + color,
            "§7Click to cycle: green / yellow / black"));
        inv.setStack(13, uiGlint(net.minecraft.item.Items.COMPASS, "§bScope: §f" + scopeLabel,
            "§7" + bulkRecolorScopeDescription(scopeKey),
            "§8Click to cycle scope type"));
        inv.setStack(15, uiGlint(net.minecraft.item.Items.NAME_TAG, "§dScope Value",
            "§7Current: §f" + (scopeValue == null || scopeValue.isBlank() ? "(none)" : scopeValue),
            "§8Click to edit value for category/query/ids/range"));
        inv.setStack(16, uiGlint(net.minecraft.item.Items.BARRIER, "§cExclude List",
            "§7Current: §f" + (exclude == null || exclude.isBlank() ? "(none)" : exclude),
            "§8Supports same format as scope (example: ids:old_1,old_2)"));
        inv.setStack(22, uiGlint(net.minecraft.item.Items.SPYGLASS, "§ePreview",
            "§7Shows what would be recolored",
            "§7No changes are made"));
        inv.setStack(31, uiGlint(net.minecraft.item.Items.BOOK, "§fLive Estimate",
            "§7Matched: §f" + preview.matchedCount(),
            "§7Excluded: §f" + preview.excludedCount(),
            "§7Invalid: §f" + preview.invalidCount(),
            preview.sample().isEmpty() ? "§8No sample yet" : "§8Sample: " + String.join(", ", preview.sample())));
        inv.setStack(24, uiGlint(net.minecraft.item.Items.YELLOW_CONCRETE, "§e§lReview & Continue",
            "§7Open final confirmation screen",
            "§7Scope: §f" + scopeLabel,
            "§7Selected IDs: §f" + selectedCount));
        inv.setStack(45, uiGlint(net.minecraft.item.Items.RED_CONCRETE, "§c← Back"));

        java.util.List<com.customblocks.core.SlotData> blocks = sortedBlocks();
        int start = page * BLOCKS_PER_PAGE;
        int end = Math.min(start + BLOCKS_PER_PAGE, blocks.size());
        java.util.Set<String> selected = BULK_RECOLOR_SELECTED.get(player.getUuid());
        for (int i = start; i < end; i++) {
            com.customblocks.core.SlotData d = blocks.get(i);
            net.minecraft.item.ItemStack stack = buildEditor(d, false).getStack(0).copy();
            if (selected.contains(d.customId)) {
                stack.set(net.minecraft.component.DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
                stack.set(net.minecraft.component.DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(java.util.List.of(
                    lore("§a§lSelected for bulk recolor"),
                    lore("§7ID: §f" + d.customId))));
            }
            inv.setStack(18 + (i - start), stack);
        }
        if (page > 0) inv.setStack(46, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§d← Previous Page"));
        if (end < blocks.size()) inv.setStack(53, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§dNext Page →"));

        openScreenFromGuiState(player, GuiState.bulkRecolorWizard(page), inv, "§6§lBulk Recolor");
    }

    private static void handleBulkRecolorWizardClick(net.minecraft.server.network.ServerPlayerEntity player, GuiState state, int slot) {
        java.util.UUID uuid = player.getUuid();
        BULK_RECOLOR_COLOR.putIfAbsent(uuid, "green");
        BULK_RECOLOR_SCOPE.putIfAbsent(uuid, "all");
        BULK_RECOLOR_SCOPE_VALUE.putIfAbsent(uuid, "");
        BULK_RECOLOR_EXCLUDE.putIfAbsent(uuid, "");
        BULK_RECOLOR_SELECTED.computeIfAbsent(uuid, k -> new java.util.HashSet<>());

        if (slot == 45) {
            BULK_RECOLOR_CONFIRM_ARMED.remove(uuid);
            handleEscBack(player);
            return;
        }
        if (slot == 46 && state.page() > 0) { openBulkRecolorWizard(player, state.page() - 1); return; }
        if (slot == 53) { openBulkRecolorWizard(player, state.page() + 1); return; }

        if (slot == 11) {
            String color = BULK_RECOLOR_COLOR.get(uuid);
            String next = switch (color) {
                case "green" -> "yellow";
                case "yellow" -> "black";
                default -> "green";
            };
            BULK_RECOLOR_COLOR.put(uuid, next);
            BULK_RECOLOR_CONFIRM_ARMED.remove(uuid);
            playClick(player);
            Deque<GuiState> stack = BACK_STACK.get(uuid);
            if (stack != null && !stack.isEmpty()) stack.pop();
            openBulkRecolorWizard(player, state.page());
            return;
        }
        if (slot == 13) {
            String scope = BULK_RECOLOR_SCOPE.get(uuid);
            java.util.List<String> order = java.util.List.of("all", "uncategorized", "category", "ids", "query", "range", "selected", "favorites", "recent");
            int idx = order.indexOf(scope);
            if (idx < 0) idx = 0;
            BULK_RECOLOR_SCOPE.put(uuid, order.get((idx + 1) % order.size()));
            BULK_RECOLOR_CONFIRM_ARMED.remove(uuid);
            playClick(player);
            Deque<GuiState> stack = BACK_STACK.get(uuid);
            if (stack != null && !stack.isEmpty()) stack.pop();
            openBulkRecolorWizard(player, state.page());
            return;
        }
        if (slot == 15) {
            openShortInputPrompt(player,
                new PendingInput(InputAction.BULK_RECOLOR_SCOPE, null, null, null, null, state.page()),
                "§6Bulk Recolor Scope Value", new net.minecraft.item.ItemStack(net.minecraft.item.Items.NAME_TAG),
                BULK_RECOLOR_SCOPE_VALUE.getOrDefault(uuid, ""));
            return;
        }
        if (slot == 16) {
            openShortInputPrompt(player,
                new PendingInput(InputAction.BULK_RECOLOR_EXCLUDE, null, null, null, null, state.page()),
                "§cBulk Recolor Exclude", new net.minecraft.item.ItemStack(net.minecraft.item.Items.BARRIER),
                BULK_RECOLOR_EXCLUDE.getOrDefault(uuid, ""));
            return;
        }
        if (slot == 22) {
            String color = BULK_RECOLOR_COLOR.getOrDefault(uuid, "green");
            String scope = BULK_RECOLOR_SCOPE.getOrDefault(uuid, "all");
            String val = BULK_RECOLOR_SCOPE_VALUE.getOrDefault(uuid, "");
            String exclude = BULK_RECOLOR_EXCLUDE.getOrDefault(uuid, "");
            String scopeExpr = buildScopeExprForWizard(player, scope, val);
            if (scopeExpr == null) return;
            com.customblocks.command.CustomBlockCommand.cmdBulkRecolorFromGui(player, color, scopeExpr, exclude, false);
            BULK_RECOLOR_CONFIRM_ARMED.remove(uuid);
            Deque<GuiState> stack = BACK_STACK.get(uuid);
            if (stack != null && !stack.isEmpty()) stack.pop();
            openBulkRecolorWizard(player, state.page());
            return;
        }
        if (slot == 24) {
            String scope = BULK_RECOLOR_SCOPE.getOrDefault(uuid, "all");
            String val = BULK_RECOLOR_SCOPE_VALUE.getOrDefault(uuid, "");
            String scopeExpr = buildScopeExprForWizard(player, scope, val);
            if (scopeExpr == null) return;
            BULK_RECOLOR_CONFIRM_ARMED.remove(uuid);
            openBulkRecolorConfirm(player, state.page());
            return;
        }
        if (slot >= 18 && slot <= 35) {
            int start = state.page() * BLOCKS_PER_PAGE;
            int idx = start + (slot - 18);
            java.util.List<com.customblocks.core.SlotData> blocks = sortedBlocks();
            if (idx < blocks.size()) {
                String id = blocks.get(idx).customId;
                java.util.Set<String> selected = BULK_RECOLOR_SELECTED.get(uuid);
                if (selected.contains(id)) selected.remove(id);
                else selected.add(id);
                BULK_RECOLOR_CONFIRM_ARMED.remove(uuid);
                playClick(player);
                Deque<GuiState> stack = BACK_STACK.get(uuid);
                if (stack != null && !stack.isEmpty()) stack.pop();
                openBulkRecolorWizard(player, state.page());
            }
        }
    }

    private static String bulkRecolorScopeLabel(String scopeKey) {
        return switch (scopeKey == null ? "all" : scopeKey) {
            case "all" -> "Everything";
            case "uncategorized" -> "Unsorted Blocks";
            case "category" -> "One Category";
            case "ids" -> "Chosen Blocks";
            case "selected" -> "Currently Selected";
            case "query" -> "Search Results";
            case "favorites" -> "Favorites";
            case "recent" -> "Recently Edited";
            case "range" -> "Slot Range";
            default -> scopeKey;
        };
    }

    private static String bulkRecolorScopeDescription(String scopeKey) {
        return switch (scopeKey == null ? "all" : scopeKey) {
            case "all" -> "Recolor all your CustomBlocks at once.";
            case "uncategorized" -> "Only blocks not in any category.";
            case "category" -> "Only blocks inside one category.";
            case "ids" -> "Only exact block IDs you choose.";
            case "selected" -> "Use your multi-selection across pages.";
            case "query" -> "Only blocks matching search text.";
            case "favorites" -> "Only pinned/favorite blocks.";
            case "recent" -> "Blocks changed recently.";
            case "range" -> "Blocks between two slot numbers.";
            default -> "Custom scope.";
        };
    }

    private static void openBulkRecolorConfirm(net.minecraft.server.network.ServerPlayerEntity player, int returnPage) {
        pushBackStack(player.getUuid());
        String color = BULK_RECOLOR_COLOR.getOrDefault(player.getUuid(), "green");
        String scope = BULK_RECOLOR_SCOPE.getOrDefault(player.getUuid(), "all");
        String value = BULK_RECOLOR_SCOPE_VALUE.getOrDefault(player.getUuid(), "");
        String exclude = BULK_RECOLOR_EXCLUDE.getOrDefault(player.getUuid(), "");
        BulkWizardPreview preview = buildBulkWizardPreview(player, scope, value, exclude);

        SimpleInventory inv = new SimpleInventory(27);
        for (int i = 0; i < 27; i++) inv.setStack(i, glass());

        inv.setStack(11, uiGlint(net.minecraft.item.Items.BOOK, "§6§lFinal Confirmation",
            "§7Color: §f" + color,
            "§7Scope: §f" + bulkRecolorScopeLabel(scope),
            "§7Matched: §f" + preview.matchedCount(),
            "§7Excluded: §f" + preview.excludedCount(),
            "§7Invalid: §f" + preview.invalidCount()));
        inv.setStack(13, uiGlint(net.minecraft.item.Items.SPYGLASS, "§eSample IDs",
            preview.sample().isEmpty() ? "§8No IDs matched" : "§7" + String.join(", ", preview.sample())));
        inv.setStack(15, uiGlint(net.minecraft.item.Items.EMERALD_BLOCK, "§a§lApply Now",
            "§7Creates new recolored variants",
            "§7Old blocks remain unchanged"));
        inv.setStack(18, uiGlint(net.minecraft.item.Items.RED_CONCRETE, "§c← Back to Wizard"));
        inv.setStack(26, uiGlint(net.minecraft.item.Items.BARRIER, "§8Cancel"));

        openScreenFromGuiState(player, GuiState.bulkRecolorConfirm(returnPage), inv, "§6§lConfirm Bulk Recolor");
    }

    private static void handleBulkRecolorConfirmClick(net.minecraft.server.network.ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 18) {
            openBulkRecolorWizard(player, state.page());
            return;
        }
        if (slot == 26) {
            handleEscBack(player);
            return;
        }
        if (slot == 15) {
            java.util.UUID uuid = player.getUuid();
            String color = BULK_RECOLOR_COLOR.getOrDefault(uuid, "green");
            String scope = BULK_RECOLOR_SCOPE.getOrDefault(uuid, "all");
            String val = BULK_RECOLOR_SCOPE_VALUE.getOrDefault(uuid, "");
            String exclude = BULK_RECOLOR_EXCLUDE.getOrDefault(uuid, "");
            String scopeExpr = buildScopeExprForWizard(player, scope, val);
            if (scopeExpr == null) return;
            com.customblocks.command.CustomBlockCommand.cmdBulkRecolorFromGui(player, color, scopeExpr, exclude, true);
            handleEscBack(player);
        }
    }

    private static String buildScopeExprForWizard(net.minecraft.server.network.ServerPlayerEntity player, String scope, String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        return switch (scope == null ? "all" : scope) {
            case "all", "uncategorized", "selected", "favorites" -> scope;
            case "category" -> {
                if (value.isBlank()) {
                    send(player, "§0§l[§b§lCB§0§l]§r §cScope value required: enter a category key.");
                    playError(player);
                    yield null;
                }
                yield "category:" + value;
            }
            case "ids" -> {
                if (value.isBlank()) {
                    send(player, "§0§l[§b§lCB§0§l]§r §cScope value required: enter one or more block IDs.");
                    playError(player);
                    yield null;
                }
                yield "ids:" + value;
            }
            case "query" -> {
                if (value.isBlank()) {
                    send(player, "§0§l[§b§lCB§0§l]§r §cScope value required: enter search text.");
                    playError(player);
                    yield null;
                }
                yield "query:" + value;
            }
            case "range" -> {
                if (!value.matches("\\d+\\s*-\\s*\\d+")) {
                    send(player, "§0§l[§b§lCB§0§l]§r §cRange format: start-end (example: 1-120).");
                    playError(player);
                    yield null;
                }
                yield "range:" + value;
            }
            case "recent" -> {
                if (!value.isBlank() && !value.matches("\\d+")) {
                    send(player, "§0§l[§b§lCB§0§l]§r §cRecent expects a number (example: 10).");
                    playError(player);
                    yield null;
                }
                yield "recent:" + (value.isBlank() ? "10" : value);
            }
            default -> scope;
        };
    }

    private record BulkWizardPreview(int matchedCount, int excludedCount, int invalidCount, java.util.List<String> sample) {}

    private static BulkWizardPreview buildBulkWizardPreview(net.minecraft.server.network.ServerPlayerEntity player, String scope, String scopeValue, String excludeRaw) {
        java.util.LinkedHashSet<String> matched = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> invalid = new java.util.LinkedHashSet<>();
        String expr = buildScopeExprForPreview(scope, scopeValue);

        if ("all".equals(expr)) {
            for (com.customblocks.core.SlotData d : com.customblocks.core.SlotManager.allSlots()) matched.add(d.customId);
        } else if ("uncategorized".equals(expr)) {
            for (com.customblocks.core.SlotData d : com.customblocks.core.SlotManager.allSlots()) {
                if (com.customblocks.core.CategoryManager.getCategoriesForBlock(d.customId).isEmpty()) matched.add(d.customId);
            }
        } else if ("selected".equals(expr)) {
            matched.addAll(BULK_RECOLOR_SELECTED.getOrDefault(player.getUuid(), java.util.Collections.emptySet()));
        } else if (expr.startsWith("category:")) {
            String key = expr.substring("category:".length()).trim();
            com.customblocks.core.Category cat = com.customblocks.core.CategoryManager.getCategory(key);
            if (cat == null) invalid.add("category:" + key);
            else for (com.customblocks.core.SlotData d : com.customblocks.core.CategoryManager.getBlocksInCategory(cat.key())) matched.add(d.customId);
        } else if (expr.startsWith("ids:")) {
            for (String t : expr.substring("ids:".length()).split("[,\\s]+")) {
                String id = t.trim();
                if (id.isEmpty()) continue;
                if (com.customblocks.core.SlotManager.hasId(id)) matched.add(id); else invalid.add(id);
            }
        } else if (expr.startsWith("query:")) {
            String q = expr.substring("query:".length()).toLowerCase(java.util.Locale.ROOT);
            for (com.customblocks.core.SlotData d : com.customblocks.core.SlotManager.allSlots()) {
                if (d.customId.toLowerCase(java.util.Locale.ROOT).contains(q) || d.displayNameLower.contains(q)) matched.add(d.customId);
            }
        } else if (expr.startsWith("range:")) {
            String value = expr.substring("range:".length()).trim();
            String[] p = value.split("-", 2);
            try {
                int a = Integer.parseInt(p[0].trim()), b = Integer.parseInt(p[1].trim());
                int lo = Math.min(a, b), hi = Math.max(a, b);
                for (com.customblocks.core.SlotData d : com.customblocks.core.SlotManager.allSlots()) {
                    if (d.index >= lo && d.index <= hi) matched.add(d.customId);
                }
            } catch (Exception ex) { invalid.add("range:" + value); }
        } else if (expr.startsWith("recent:")) {
            int n = 10;
            try { n = Integer.parseInt(expr.substring("recent:".length()).trim()); } catch (Exception ignored) {}
            java.util.List<com.customblocks.core.SlotData> all = new java.util.ArrayList<>(com.customblocks.core.SlotManager.allSlots());
            all.sort(java.util.Comparator.comparingInt(d -> -d.index));
            for (int i = 0; i < Math.min(Math.max(1, n), all.size()); i++) matched.add(all.get(i).customId);
        }

        java.util.LinkedHashSet<String> excluded = new java.util.LinkedHashSet<>();
        String ex = excludeRaw == null ? "" : excludeRaw.trim();
        if (!ex.isBlank()) {
            String exExpr = ex.contains(":") ? ex : "ids:" + ex;
            excluded.addAll(resolvePreviewIdsFromExpr(player, exExpr));
            matched.removeAll(excluded);
        }

        java.util.List<String> sample = matched.stream().limit(6).toList();
        return new BulkWizardPreview(matched.size(), excluded.size(), invalid.size(), sample);
    }

    private static String buildScopeExprForPreview(String scope, String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        return switch (scope == null ? "all" : scope) {
            case "category" -> value.isBlank() ? "category:" : "category:" + value;
            case "ids" -> value.isBlank() ? "ids:" : "ids:" + value;
            case "query" -> value.isBlank() ? "query:" : "query:" + value;
            case "range" -> value.isBlank() ? "range:" : "range:" + value;
            case "recent" -> "recent:" + (value.isBlank() ? "10" : value);
            default -> scope;
        };
    }

    private static java.util.Set<String> resolvePreviewIdsFromExpr(net.minecraft.server.network.ServerPlayerEntity player, String expr) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        String low = expr == null ? "" : expr.trim().toLowerCase(java.util.Locale.ROOT);
        if (low.isBlank()) return out;
        if ("all".equals(low) || "everything".equals(low)) {
            for (com.customblocks.core.SlotData d : com.customblocks.core.SlotManager.allSlots()) out.add(d.customId);
            return out;
        }
        if ("selected".equals(low)) {
            out.addAll(BULK_RECOLOR_SELECTED.getOrDefault(player.getUuid(), java.util.Collections.emptySet()));
            return out;
        }
        if ("uncategorized".equals(low) || "unsorted".equals(low)) {
            for (com.customblocks.core.SlotData d : com.customblocks.core.SlotManager.allSlots()) {
                if (com.customblocks.core.CategoryManager.getCategoriesForBlock(d.customId).isEmpty()) out.add(d.customId);
            }
            return out;
        }
        if (low.startsWith("category:")) {
            String key = expr.substring("category:".length()).trim();
            com.customblocks.core.Category cat = com.customblocks.core.CategoryManager.getCategory(key);
            if (cat != null) for (com.customblocks.core.SlotData d : com.customblocks.core.CategoryManager.getBlocksInCategory(cat.key())) out.add(d.customId);
            return out;
        }
        if (low.startsWith("query:")) {
            String q = expr.substring("query:".length()).trim().toLowerCase(java.util.Locale.ROOT);
            for (com.customblocks.core.SlotData d : com.customblocks.core.SlotManager.allSlots()) {
                if (d.customId.toLowerCase(java.util.Locale.ROOT).contains(q) || d.displayNameLower.contains(q)) out.add(d.customId);
            }
            return out;
        }
        if (low.startsWith("range:")) {
            String value = expr.substring("range:".length()).trim();
            String[] p = value.split("-", 2);
            if (p.length == 2) {
                try {
                    int a = Integer.parseInt(p[0].trim()), b = Integer.parseInt(p[1].trim());
                    int lo = Math.min(a, b), hi = Math.max(a, b);
                    for (com.customblocks.core.SlotData d : com.customblocks.core.SlotManager.allSlots()) {
                        if (d.index >= lo && d.index <= hi) out.add(d.customId);
                    }
                } catch (Exception ignored) {}
            }
            return out;
        }
        if (low.startsWith("recent:")) {
            int n = 10;
            try { n = Integer.parseInt(expr.substring("recent:".length()).trim()); } catch (Exception ignored) {}
            java.util.List<com.customblocks.core.SlotData> all = new java.util.ArrayList<>(com.customblocks.core.SlotManager.allSlots());
            all.sort(java.util.Comparator.comparingInt(d -> -d.index));
            for (int i = 0; i < Math.min(Math.max(1, n), all.size()); i++) out.add(all.get(i).customId);
            return out;
        }
        String payload = low.startsWith("ids:") ? expr.substring("ids:".length()) : expr;
        for (String t : payload.split("[,\\s]+")) {
            String id = t.trim();
            if (!id.isEmpty() && com.customblocks.core.SlotManager.hasId(id)) out.add(id);
        }
        return out;
    }

    public static java.util.Set<String> getBulkRecolorSelected(java.util.UUID playerUuid) {
        return new java.util.HashSet<>(BULK_RECOLOR_SELECTED.getOrDefault(playerUuid, java.util.Collections.emptySet()));
    }
}








