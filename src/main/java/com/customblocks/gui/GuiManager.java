package com.customblocks.gui;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.customblocks.CustomBlocksMod;
import com.customblocks.CustomBlocksConfig;
import com.customblocks.ImageProcessor;
import com.customblocks.TextSanitizer;
import com.customblocks.command.PermissionHelper;
import com.customblocks.core.SlotData;
import com.customblocks.core.SlotManager;
import com.customblocks.core.UndoManager;
import com.customblocks.core.FirstUseHints;
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
@SuppressFBWarnings({"NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", "NP_NULL_ON_SOME_PATH"})
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

    /** Pending color-triangle recolor job stored between confirmation GUI open and Apply click. */
    public record RecolorJob(String sourceId, String newId, String newName, int r, int g, int b) {}
    private static final ConcurrentHashMap<UUID, RecolorJob> PENDING_RECOLORS = new ConcurrentHashMap<>();

    /** GuiMode names that have a full implementation (open method + restoreState + handleClick case). */
    private static final java.util.Set<String> IMPLEMENTED_MODES = java.util.Set.of(
        "MAIN", "PICKER", "PICKER_BROKEN", "EDITOR", "FACE_EDITOR",
        "FACE_CHANGE_SELECT", "FACE_CHANGE_PICKER", "SHAPE_EDITOR",
        "MAINTENANCE_MENU", "HELP_MENU", "WELCOME_MENU", "TOOLS_GUI",
        "PROPERTIES_MENU", "SOUND_MENU", "ANIM_GUI", "TAB_ICON_MENU",
        "RESOURCE_CENTER", "BULK_DELETE", "SEARCH_PICKER", "MAGIC_ITEMS",
        "UNDO_PICKER", "CONFIG_WARNING", "CONFIG_GUI", "HELP_CATEGORY",
        "ANIM_CONFIRM_ABANDON", "BG_STUDIO", "UNCATEGORIZED_PICKER",
        "ASSIGNMENT_DECISION", "CATEGORY_PICKER", "CATEGORY_BROWSER",
        "CATEGORY_DETAIL", "CATEGORY_CONTROLLER", "CATEGORY_EDITOR",
        "SUBCATEGORY_CONTROLLER", "IMPORT_CONFLICT", "DELETE_CATEGORY_MENU",
        "MERGE_CATEGORY_PICKER_TARGET", "BULK_ASSIGN_PICKER",
        "SORT_BLOCKS_MENU", "CATEGORY_STATS", "CATEGORY_BLOCK_CONTEXT",
        "CATEGORY_ICON_PICKER", "BULK_RECOLOR_WIZARD", "BULK_RECOLOR_CONFIRM",
        "COLOR_FILL_MODE", "RECOVER_GUI", "FEATURE_MENU", "STATS_GUI",
        "VARIANT_GUI", "COLOR_STUDIO", "PALETTE_GENERATOR", "AI_SUGGEST_GUI",
        "MARKET_GUI", "BULK_HUB", "BULK_OP_PICKER", "COLOR_PICKER",
        "VOICE_PICKER", "FAVORITES_GUI", "RECENT_GUI", "SAFETY_CENTER",
        "HISTORY_GUI", "SCRIPT_GUI", "SCRIPT_SUMMARY", "CACHE_DASHBOARD",
        "AUDIT_GUI", "AI_GEN", "CUSTOM_COLOR_STUDIO", "ACHIEVEMENTS_GUI",
        "SNAPSHOTS_GUI", "DELETED_BLOCKS_GUI", "BOX_NUDGE_EDITOR",
        "RECOLOR_CONFIRM"
    );

    /** GuiMode names that are reserved for a planned phase but have no content yet. */
    private static final java.util.Set<String> STUB_MODES = java.util.Set.of();

    /** Returns the set of GuiMode names with full implementations. Used by DiagnosticsHelper. */
    public static java.util.Set<String> implementedModeNames() { return IMPLEMENTED_MODES; }
    /** Returns the set of GuiMode names that are reserved stubs. Used by DiagnosticsHelper. */
    public static java.util.Set<String> stubModeNames() { return STUB_MODES; }
    private static final Map<UUID, AnimParams> ANIM_PARAMS = new ConcurrentHashMap<>();
    private static final Map<UUID, AnimParams> ANIM_ORIGINAL_PARAMS = new ConcurrentHashMap<>();
    private static final Map<UUID, FaceImportPending> FACE_IMPORTS = new ConcurrentHashMap<>();

    private static final Map<UUID, Long> SHAPE_CREATE_COOLDOWN = new ConcurrentHashMap<>();
    private static final long SHAPE_COOLDOWN_MS = 500;
    /** V4-13 — per-player working copy of a box being nudged (not yet committed to SlotManager). */
    private static final Map<UUID, SlotData.ShapeBox> BOX_NUDGE_WORK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> CLICK_COOLDOWN = new ConcurrentHashMap<>();
    private static final long CLICK_COOLDOWN_MS = 100;
    private static final long FACE_IMPORT_TIMEOUT_MS = 5 * 60_000L;
    private static final int FACE_IMPORT_POLL_TICKS = 40;
    private static final String FACE_IMPORT_FOLDER = "config/customblocks/import";
    private static final String FACE_IMPORT_REQUESTS_DIR = "faces";
    private static final java.util.concurrent.atomic.AtomicInteger faceImportTickCounter = new java.util.concurrent.atomic.AtomicInteger(0); // 7.9

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
        BULK_RECOLOR_EXCLUDE,
        SET_HOLOGRAM_TEXT,
        AI_CHAT_QUERY,
        CONFIRM_SCRIPT_DELETE
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
    private static final Map<UUID, Long> BULK_DELETE_CONFIRM_ARMED = new ConcurrentHashMap<>();
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
    private static final Map<UUID, com.customblocks.core.MacroManager.ScriptRunResult> LAST_SCRIPT_RESULTS = new ConcurrentHashMap<>();
    private static final int MAX_RECENT = 3;
    private static final Map<UUID, Long> ESC_DEBOUNCE = new ConcurrentHashMap<>();
    private static final long ESC_DEBOUNCE_MS = 150;
    /** L3 — cached market listing per-player (fetched async). */
    private static final Map<UUID, java.util.List<JsonObject>> MARKET_CACHE = new ConcurrentHashMap<>();
    private static final int MARKET_PAGE_SIZE = 18;
    /** V4-45 — market search query per player. */
    private static final Map<UUID, String> MARKET_SEARCH_QUERIES = new ConcurrentHashMap<>();
    /** V4-45 — market sort mode per player: "name" or "date" (default). */
    private static final Map<UUID, String> MARKET_SORT_MODES = new ConcurrentHashMap<>();
    /** 1.27 — per-player sort preference (session-only, resets on disconnect). */
    private static final Map<UUID, SortMode> PLAYER_SORT_PREFS = new ConcurrentHashMap<>();
    /** Phase 2 — shared bulk selection (persists across bulk hub pages and op picker pages). */
    private static final Map<UUID, Set<String>> BULK_SELECTIONS = new ConcurrentHashMap<>();
    /** Phase 2 — category filter active in the bulk op picker (null = show all). */
    private static final Map<UUID, String> BULK_OP_CAT_FILTER = new ConcurrentHashMap<>();

    private static final float[] HARD_CYCLE      = { -1f, 0f, 0.5f, 1.5f, 3f, 5f, 10f, 50f };
    private static final int     BLOCKS_PER_PAGE = 18;
    private static final String[] PRESET_NAMES   = {"full","slab","thin","carpet","pillar","small","micro","pane","trapdoor","fence","stairs","cross"};
    private static final String[] PRESET_DISPLAY = {"Full Block","Slab","Thin Slab","Carpet","Wall","Comparator","Comparator Small","Pane","Trapdoor","Fence","Stairs","Cross"};
    
    private static final java.util.concurrent.atomic.AtomicInteger errorCount = new java.util.concurrent.atomic.AtomicInteger(0); // 7.9
    public static void logError() { errorCount.incrementAndGet(); }

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
            Text.literal(normalizeFormattingCodes(title))));
    }

    /** T2 — i18n overload: accepts a pre-built Text (e.g. {@code Text.translatable(key)}). */
    private static void openScreenFromGuiState(ServerPlayerEntity player, GuiState state,
                                                SimpleInventory inv, Text titleText) {
        STATES.put(player.getUuid(), state);
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, inv),
            titleText));
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

    public static boolean hasOpenGui(UUID uuid) {
        CbScreenHandler handler = HANDLERS.get(uuid);
        return handler != null && !handler.isDisposed();
    }

    private static final java.util.Set<UUID> RESTORING = java.util.concurrent.ConcurrentHashMap.newKeySet(); // 1.35 — thread-safe

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

        PendingInput removed = PENDING.remove(uuid);
        GuiState state = STATES.get(uuid);
        if (removed != null && state != null) {
            com.customblocks.core.DraftManager.save(uuid, com.customblocks.core.DraftManager.Kind.SESSION_SHELL,
                Map.<String, Object>of(
                    "guiMode",      state.mode().name(),
                    "editingId",    state.editingId() != null ? state.editingId() : "",
                    "page",         state.page(),
                    "shapeBoxPage", state.shapeBoxPage(),
                    "fromCommand",  state.fromCommand(),
                    "confirmDelete",state.confirmDelete()
                ));
            ChatHelper.notifyDraftSavedWithResume(player);
        }
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

    private static void popBackStack(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        Deque<GuiState> stack = BACK_STACK.get(uuid);
        if (stack != null && !stack.isEmpty()) {
            restoreState(player, stack.pop());
        } else {
            openMain(player, 0);
        }
    }

    // ── Cleanup on disconnect ────────────────────────────────────────────────

    public static void onPlayerDisconnect(UUID uuid) {
        PendingInput pendingDraft = PENDING.remove(uuid);
        GuiState stateDraft = STATES.remove(uuid);
        BACK_STACK.remove(uuid);
        if (pendingDraft != null && stateDraft != null) {
            com.customblocks.core.DraftManager.save(uuid, com.customblocks.core.DraftManager.Kind.SESSION_SHELL,
                Map.<String, Object>of(
                    "guiMode",      stateDraft.mode().name(),
                    "editingId",    stateDraft.editingId() != null ? stateDraft.editingId() : "",
                    "page",         stateDraft.page(),
                    "shapeBoxPage", stateDraft.shapeBoxPage(),
                    "fromCommand",  stateDraft.fromCommand(),
                    "confirmDelete",stateDraft.confirmDelete()
                ));
        }
        PENDING_CATEGORIES.remove(uuid);
        FACE_IMPORTS.remove(uuid);
        HANDLERS.remove(uuid);
        ANIM_PARAMS.remove(uuid);
        ANIM_ORIGINAL_PARAMS.remove(uuid);
        SHAPE_CREATE_COOLDOWN.remove(uuid);
        CLICK_COOLDOWN.remove(uuid);
        BULK_DELETE_SELECTIONS.remove(uuid);
        BULK_DELETE_CONFIRM_ARMED.remove(uuid);
        BULK_SELECTIONS.remove(uuid);
        BULK_OP_CAT_FILTER.remove(uuid);
        FACE_CHANGE_SELECTIONS.remove(uuid);
        FACE_CHANGE_RETURN_PAGES.remove(uuid);
        ESC_DEBOUNCE.remove(uuid);
        SEARCH_QUERIES.remove(uuid);
        RECENT_BLOCKS.remove(uuid);
        MARKET_CACHE.remove(uuid);
        MARKET_SEARCH_QUERIES.remove(uuid);
        MARKET_SORT_MODES.remove(uuid);
        // 1.37 — bulk recolor wizard state cleanup to prevent memory leaks
        BULK_RECOLOR_COLOR.remove(uuid);
        BULK_RECOLOR_SCOPE.remove(uuid);
        BULK_RECOLOR_SCOPE_VALUE.remove(uuid);
        BULK_RECOLOR_EXCLUDE.remove(uuid);
        BULK_RECOLOR_SELECTED.remove(uuid);
        BULK_RECOLOR_CONFIRM_ARMED.remove(uuid);
    }

    // ── Draft resume (Phase C2) ──────────────────────────────────────────────

    /**
     * Called by DraftManager.resume(). Reopens the GUI screen the player was in when they quit
     * a multi-step flow (URL input, rename, etc.) mid-way.
     */
    public static int resumePendingDraft(ServerPlayerEntity player) {
        if (player == null) return 0;
        UUID uuid = player.getUuid();
        Optional<com.customblocks.core.DraftManager.Draft> opt = com.customblocks.core.DraftManager.take(uuid);
        if (opt.isEmpty()) {
            send(player, ChatHelper.formattedKey("cmd.resume.nothing_saved"));
            return 0;
        }
        com.customblocks.core.DraftManager.Draft draft = opt.get();
        Map<String, Object> data = draft.payload();
        if (draft.kind() == com.customblocks.core.DraftManager.Kind.SESSION_SHELL) {
            String  modeName = (String)  data.getOrDefault("guiMode", "MAIN");
            String  rawId    = (String)  data.getOrDefault("editingId", "");
            int     page     = data.get("page")         instanceof Integer i ? i : 0;
            int     sbPage   = data.get("shapeBoxPage") instanceof Integer i ? i : 0;
            boolean fromCmd  = Boolean.TRUE.equals(data.get("fromCommand"));
            boolean confDel  = Boolean.TRUE.equals(data.get("confirmDelete"));
            GuiMode mode;
            try { mode = GuiMode.valueOf(modeName); } catch (IllegalArgumentException e) { mode = GuiMode.MAIN; }
            restoreState(player, new GuiState(mode, rawId.isEmpty() ? null : rawId, page, confDel, sbPage, fromCmd));
            return 1;
        }
        openMain(player, 0);
        return 1;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public static void openToolsGui(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.tools(), buildToolsGui(player), Text.translatable("customblocks.gui.tools.title"));
    }

    public static void openMain(ServerPlayerEntity player, int page) {
        openScreenFromGuiState(player, GuiState.main(page), buildMain(player, page),
                Text.translatable("customblocks.gui.dashboard.title"));
    }

    public static void openEditorPicker(ServerPlayerEntity player) { openEditorPicker(player, 0); }
    public static void openEditorPicker(ServerPlayerEntity player, int page) {
        int total = sortedBlocks().size();
        int max   = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        pushBackStack(player.getUuid());
        String _hPicker = FirstUseHints.hint(player.getUuid(), "open_block_list");
        if (_hPicker != null) player.sendMessage(Text.literal(_hPicker), false);
        openScreenFromGuiState(player, GuiState.picker(page), buildPicker(player.getUuid(), page, false), Text.translatable("customblocks.gui.picker.title"));
    }

    public static void openEditor(ServerPlayerEntity player, String id, int returnPage, boolean fromCommand) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        trackRecentBlock(player.getUuid(), id);
        String _hEditor = FirstUseHints.hint(player.getUuid(), "open_editor");
        if (_hEditor != null) player.sendMessage(Text.literal(_hEditor), false);
        if (!fromCommand) pushBackStack(player.getUuid());
        openScreenFromGuiState(player,
            fromCommand ? GuiState.editorFromCommand(id) : GuiState.editor(id, returnPage),
            buildEditor(d, false, player.getUuid()), Text.translatable("customblocks.gui.editor.title").append(Text.literal(" §8— " + d.displayName)));
    }

    public static void openEditor(ServerPlayerEntity player, String id, int returnPage) {
        openEditor(player, id, returnPage, false);
    }

    public static void openFaceEditor(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.faceEditor(id, returnPage), buildFaceEditor(d), Text.translatable("customblocks.gui.face_editor.title").append(Text.literal(" §8— " + d.displayName)));
    }

    private static void reopenFaceEditor(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        openScreenFromGuiState(player, GuiState.faceEditor(id, returnPage), buildFaceEditor(d), Text.translatable("customblocks.gui.face_editor.title").append(Text.literal(" §8— " + d.displayName)));
    }

    public static void openFaceChangeSelect(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        FACE_CHANGE_RETURN_PAGES.put(player.getUuid(), returnPage);
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.faceChangeSelect(id, returnPage),
            buildFaceChangeSelect(d), Text.translatable("customblocks.gui.face_copy.title").append(Text.literal(" §8— " + d.displayName)));
    }

    private static void reopenFaceChangeSelect(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        FACE_CHANGE_RETURN_PAGES.put(player.getUuid(), returnPage);
        openScreenFromGuiState(player, GuiState.faceChangeSelect(id, returnPage),
            buildFaceChangeSelect(d), Text.translatable("customblocks.gui.face_copy.title").append(Text.literal(" §8— " + d.displayName)));
    }

    private static void openFaceChangePicker(ServerPlayerEntity player, String id, String face, int page) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, FACE_CHANGE_RETURN_PAGES.getOrDefault(player.getUuid(), 0)); return; }
        FACE_CHANGE_SELECTIONS.put(player.getUuid(), face);
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.faceChangePicker(id, page),
            buildFaceChangePicker(d, face, page),
            Text.translatable("customblocks.gui.face_source.title").append(Text.literal(" §8— §b" + face.toUpperCase(Locale.ROOT))));
    }

    private static void reopenFaceChangePicker(ServerPlayerEntity player, String id, String face, int page) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, FACE_CHANGE_RETURN_PAGES.getOrDefault(player.getUuid(), 0)); return; }
        FACE_CHANGE_SELECTIONS.put(player.getUuid(), face);
        openScreenFromGuiState(player, GuiState.faceChangePicker(id, page),
            buildFaceChangePicker(d, face, page),
            Text.translatable("customblocks.gui.face_source.title").append(Text.literal(" §8— §b" + face.toUpperCase(Locale.ROOT))));
    }

    public static void openShapeEditor(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        pushBackStack(player.getUuid());
        String _hShape = FirstUseHints.hint(player.getUuid(), "open_shape_editor");
        if (_hShape != null) player.sendMessage(Text.literal(_hShape), false);
        openScreenFromGuiState(player, GuiState.shapeEditor(id, returnPage), buildShapeEditor(d, 0), Text.translatable("customblocks.gui.shape_editor.title").append(Text.literal(" §8— " + d.displayName)));
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
        openScreenFromGuiState(player, GuiState.searchPicker(page), buildSearchPicker(page, q), Text.translatable("customblocks.gui.search.title").append(Text.literal(": §7" + query + " §8(" + total + " found)")));
    }

    public static void openMaintenanceMenu(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.maintenance(), buildMaintenanceMenu(player), Text.translatable("customblocks.gui.maintenance.title"));
    }

    /** K2 — usage statistics dashboard. */
    public static void openStatsGui(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.statsGui(), buildStatsGui(player), Text.translatable("customblocks.gui.stats.title"));
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

        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back"));
        return inv;
    }

    public static void openHelpGui(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.help(), buildHelpGui(), Text.translatable("customblocks.gui.help.title"));
    }

    public static void openPropertiesGui(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        pushBackStack(player.getUuid());
        String _hProps = FirstUseHints.hint(player.getUuid(), "open_properties");
        if (_hProps != null) player.sendMessage(Text.literal(_hProps), false);
        openScreenFromGuiState(player, GuiState.properties(id, returnPage), buildPropertiesGui(d), Text.translatable("customblocks.gui.properties.title").append(Text.literal(" §8— " + d.displayName)));
    }

    public static void openSoundMenu(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.sound(id, returnPage), buildSoundMenu(d), Text.translatable("customblocks.gui.sound.title").append(Text.literal(" §8— " + d.displayName)));
    }

    public static void openTabIconPicker(ServerPlayerEntity player, int page) {
        int total = sortedBlocks().size();
        int max   = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        // Push the caller's state, then a TAB_ICON_MENU marker so handlePickerClick
        // knows to apply the tab icon instead of opening the editor when a block is clicked.
        pushBackStack(player.getUuid());
        Deque<GuiState> bs = BACK_STACK.computeIfAbsent(player.getUuid(), k -> new ArrayDeque<>());
        bs.push(GuiState.tabIconMenu());
        while (bs.size() > MAX_BACK_STACK_DEPTH) bs.removeLast();
        openScreenFromGuiState(player, GuiState.picker(page), buildPicker(player.getUuid(), page, false), Text.translatable("customblocks.gui.tab_icon_picker.title"));
    }

    private static void applyTabIconFromBlock(ServerPlayerEntity player, String blockId) {
        SlotData sel = SlotManager.getById(blockId);
        if (sel == null || sel.texture == null) { send(player, "§c[CB] Block has no texture."); popBackStack(player); return; }
        final byte[] bytes = sel.texture.clone();
        MinecraftServer srv = player.getServer();
        SlotManager.setTabIconTexture(bytes);
        if (SlotManager.hasId("tab_icon")) {
            SlotData ex = SlotManager.getById("tab_icon");
            SlotManager.updateTexture("tab_icon", bytes); SlotManager.saveAll();
            NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("retexture", ex.index, "tab_icon", null, bytes, ex.lightLevel, ex.hardness, ex.soundType));
        } else if (SlotManager.freeSlots() > 0) {
            SlotData iconSlot = SlotManager.assign("tab_icon", "Tab Icon", bytes);
            if (iconSlot != null) { SlotManager.saveAll(); NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("add", iconSlot.index, "tab_icon", "Tab Icon", bytes, iconSlot.lightLevel, iconSlot.hardness, iconSlot.soundType)); }
        }
        NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("tabicon", -1, null, null, bytes, 0, 0, "stone"));
        send(player, "Tab icon updated!");
        popBackStack(player);
    }

    public static void openResourceHub(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.resourceCenter(), buildResourceHub(player), Text.translatable("customblocks.gui.resource_center.title"));
    }

    public static void openBrokenBlocks(ServerPlayerEntity player) { openBrokenBlocks(player, 0); }
    public static void openBrokenBlocks(ServerPlayerEntity player, int page) {
        if (!com.customblocks.core.SlotManager.isStartupLoadComplete()) {
            send(player, "§eStill loading textures from disk — please wait a moment and try again.");
            return;
        }
        int total = brokenBlocks().size();
        int max   = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.pickerBroken(page), buildPicker(player.getUuid(), page, true), Text.translatable("customblocks.gui.picker_broken.title"));
    }

    public static List<SlotData> brokenBlocks() {
        return SlotManager.brokenBlocks();
    }

    public static void openRecoverGui(ServerPlayerEntity player, int page) {
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.recoverGui(page), buildRecoverGui(player, page), Text.translatable("customblocks.gui.recover.title"));
    }

    private static void handleRecoverGuiClick(ServerPlayerEntity player, GuiState state, int slot) {
        int page = state.page();
        if (slot == 49) { openMain(player, 0); return; }
        if (slot == 45 && page > 0) {
            openScreenFromGuiState(player, GuiState.recoverGui(page - 1), buildRecoverGui(player, page - 1), Text.translatable("customblocks.gui.recover.title"));
            return;
        }
        if (slot == 53) {
            openScreenFromGuiState(player, GuiState.recoverGui(page + 1), buildRecoverGui(player, page + 1), Text.translatable("customblocks.gui.recover.title"));
            return;
        }
        if (slot == 13) {
            // Restore most recent deletion if top-of-stack is a deletion
            UndoManager.UndoEntry entry = UndoManager.popUndo(player.getUuid());
            if (entry != null && entry.wasDeleted()) {
                applyUndoEntry(player, entry);
                FeedbackHelper.actionBar(player, "§a§l↩ §r§aRestored: §f" + entry.customId());
                send(player, "§a[Recover] Restored '§f" + entry.customId() + "§a'!");
                openRecoverGui(player, 0);
            } else {
                if (entry != null) UndoManager.pushUndoForRedo(entry); // put it back
                playError(player);
                send(player, "§c[Recover] Top of undo stack is not a deletion — undo other changes first.");
            }
        }
    }

    public static void openWelcomeGui(ServerPlayerEntity player) {
        openScreenFromGuiState(player, GuiState.welcome(), buildWelcomeGui(player), Text.translatable("customblocks.gui.welcome.title"));
    }

    public static void openFeatureMenu(ServerPlayerEntity player, int tab) {
        playClick(player);
        com.customblocks.gui.FeedbackHelper.playSound(player, net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.2f);
        com.customblocks.gui.FeedbackHelper.spawnParticle(player, net.minecraft.particle.ParticleTypes.END_ROD, 5);
        openScreenFromGuiState(player, GuiState.featureMenu(tab), buildFeatureMenu(player, tab), Text.translatable("customblocks.gui.feature_menu.title"));
    }

    public static void openFavoritesGui(ServerPlayerEntity player, int page) {
        openScreenFromGuiState(player, GuiState.favoritesGui(page), buildFavoritesGui(player, page), Text.literal(normalizeFormattingCodes("§6Favorites")));
    }

    public static void openRecentGui(ServerPlayerEntity player) {
        openScreenFromGuiState(player, GuiState.recentGui(), buildRecentGui(player), Text.literal(normalizeFormattingCodes("§bRecent Blocks")));
    }

    public static void openSafetyCenter(ServerPlayerEntity player) {
        openScreenFromGuiState(player, GuiState.safetyCenter(), buildSafetyCenterGui(player), Text.literal(normalizeFormattingCodes("§6Safety Center")));
    }

    public static void openHistoryGui(ServerPlayerEntity player, int page) {
        openScreenFromGuiState(player, GuiState.historyGui(page), buildHistoryGui(page), Text.literal(normalizeFormattingCodes("§eRecent History")));
    }

    public static void openSnapshotsGui(ServerPlayerEntity player, int page) {
        pushBackStack(player.getUuid());
        String _hSnap = FirstUseHints.hint(player.getUuid(), "first_snapshot");
        if (_hSnap != null) player.sendMessage(Text.literal(_hSnap), false);
        openScreenFromGuiState(player, GuiState.snapshotsGui(page), buildSnapshotsGui(page), Text.literal(normalizeFormattingCodes("§6Snapshots")));
    }

    public static void openDeletedBlocksGui(ServerPlayerEntity player, int page) {
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.deletedBlocksGui(page), buildDeletedBlocksGui(page), Text.literal(normalizeFormattingCodes("§cDeleted Blocks")));
    }

    public static void openScriptGui(ServerPlayerEntity player, int page) {
        openScreenFromGuiState(player, GuiState.scriptGui(page), buildScriptGui(page), Text.literal(normalizeFormattingCodes("§bScript Library")));
    }

    public static void openScriptSummary(ServerPlayerEntity player, com.customblocks.core.MacroManager.ScriptRunResult result) {
        if (result == null) {
            openScriptGui(player, 0);
            return;
        }
        LAST_SCRIPT_RESULTS.put(player.getUuid(), result);
        openScreenFromGuiState(player, GuiState.scriptSummary(result.name(), result.steps().size()),
            buildScriptSummaryGui(result), Text.literal(normalizeFormattingCodes("§aScript Summary")));
    }

    public static void openAiGui(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.aiGen(null), buildAiHubGui(), Text.literal(normalizeFormattingCodes("§dAI Tools")));
    }

    public static void openCustomColorStudio(ServerPlayerEntity player, String initialHex) {
        openScreenFromGuiState(player, GuiState.customColorStudio(initialHex), buildCustomColorStudioGui(player, initialHex),
            Text.literal(normalizeFormattingCodes("§dCustom Color Studio")));
    }

    public static void openCacheDashboard(ServerPlayerEntity player, int tab) {
        openScreenFromGuiState(player, GuiState.cacheDashboard(tab), buildCacheDashboardGui(player, tab),
            Text.literal(normalizeFormattingCodes("§bCache Dashboard")));
    }

    public static void openAuditGui(ServerPlayerEntity player, int page) {
        openScreenFromGuiState(player, GuiState.auditGui(page), buildAuditGui(page),
            Text.literal(normalizeFormattingCodes("§6Audit Results")));
    }

    public static void openAchievementsGui(ServerPlayerEntity player, int page) {
        openScreenFromGuiState(player, GuiState.achievementsGui(page), buildAchievementsGui(player, page),
            Text.literal(normalizeFormattingCodes("§6Achievements")));
    }

    // ── 5.25 Voice Mode Picker GUI ────────────────────────────────────────────

    public static void openVoicePickerGui(ServerPlayerEntity player) {
        openScreenFromGuiState(player, GuiState.voicePicker(), buildVoicePickerGui(player), Text.translatable("customblocks.gui.voice_picker.title"));
    }

    private static final String[][] VOICE_MODES_DISPLAY = {
        // {modeKey, displayName, item, description}
        {"friendly",     "§a§lFriendly",     "EMERALD",        "§7Warm, encouraging messages."},
        {"professional", "§e§lProfessional", "GOLD_INGOT",     "§7Clean, no-fluff output."},
        {"royal",        "§d§lRoyal",        "NETHER_STAR",    "§7Formal and dramatic language."},
        {"minimal",      "§7§lMinimal",      "COAL",           "§7Shortest possible messages."},
        {"arabic",       "§6§lArabic",       "HEART_OF_THE_SEA","§7Messages displayed in Arabic."},
        {"silly",        "§c§lSilly",        "SLIME_BALL",     "§7Playful and fun responses."},
    };

    private static SimpleInventory buildVoicePickerGui(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        inv.setStack(4, uiGlint(Items.NOTE_BLOCK, "§b§l🎙 Voice Mode",
            "§7Choose how CustomBlocks speaks to you.",
            "§8Active: §f" + com.customblocks.CustomBlocksConfig.voiceMode));

        // Mode tiles — slots 10, 12, 14, 28, 30, 32 (two rows, 3 per row, spread out)
        int[] modeSlots = {10, 12, 14, 28, 30, 32};
        String active = com.customblocks.CustomBlocksConfig.voiceMode;
        for (int i = 0; i < VOICE_MODES_DISPLAY.length; i++) {
            String[] m = VOICE_MODES_DISPLAY[i];
            String key = m[0]; String label = m[1]; String itemName = m[2]; String desc = m[3];
            Item item = switch (itemName) {
                case "EMERALD"         -> Items.EMERALD;
                case "GOLD_INGOT"      -> Items.GOLD_INGOT;
                case "NETHER_STAR"     -> Items.NETHER_STAR;
                case "COAL"            -> Items.COAL;
                case "HEART_OF_THE_SEA"-> Items.HEART_OF_THE_SEA;
                default                -> Items.SLIME_BALL;
            };
            boolean isActive = key.equals(active);
            String statusLine = isActive ? "§a§l✔ Currently active" : "§8Click to set as active.";
            ItemStack tile = isActive
                ? uiGlint(item, label, desc, statusLine)
                : ui(item, label, desc, statusLine);
            inv.setStack(modeSlots[i], tile);
        }

        inv.setStack(49, uiGlint(Items.ECHO_SHARD, "§c◀ Back", "§7Return to Feature Menu."));
        return inv;
    }

    private static void handleVoicePickerClick(ServerPlayerEntity player, int slot) {
        int[] modeSlots = {10, 12, 14, 28, 30, 32};
        for (int i = 0; i < modeSlots.length && i < VOICE_MODES_DISPLAY.length; i++) {
            if (slot == modeSlots[i]) {
                String key = VOICE_MODES_DISPLAY[i][0];
                if (key.equals(com.customblocks.CustomBlocksConfig.voiceMode)) {
                    player.sendMessage(net.minecraft.text.Text.literal("§7[CB] Voice mode is already §b" + key + "§7."), true);
                    return;
                }
                com.customblocks.CustomBlocksConfig.voiceMode = key;
                com.customblocks.CustomBlocksConfig.save();
                player.sendMessage(net.minecraft.text.Text.literal("§a✔ §fVoice mode set to: §b" + key), true);
                player.getServerWorld().playSound(null, player.getBlockPos(),
                    net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP,
                    net.minecraft.sound.SoundCategory.PLAYERS, 0.5f, 1.2f);
                openVoicePickerGui(player); // refresh
                return;
            }
        }
        if (slot == 49) { playClick(player); openFeatureMenu(player, 4); }
    }

    private static SimpleInventory buildRecoverGui(ServerPlayerEntity player, int page) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        UUID uuid = player.getUuid();

        // Collect deletion entries from the undo stack (filtered)
        List<UndoManager.UndoEntry> allEntries = UndoManager.getUndoEntries(uuid, 0, 500);
        List<UndoManager.UndoEntry> deletions = allEntries.stream()
            .filter(UndoManager.UndoEntry::wasDeleted)
            .collect(java.util.stream.Collectors.toList());

        int perPage = 36;
        int offset = page * perPage;

        // Top-of-stack description for restore-readiness check
        String topDesc = UndoManager.peekUndoDescription(uuid);
        boolean topIsDeletion = topDesc != null && topDesc.equals("delete");

        inv.setStack(4, uiGlint(Items.KNOWLEDGE_BOOK, "§c§lRecover Deleted Blocks",
            "§7Deleted entries: §f" + deletions.size(),
            topIsDeletion ? "§aRestore button is ready!" : "§eUndo pending changes before restoring",
            "§8Page " + (page + 1)));

        // Restore button — slot 13 (prominent centre)
        if (topIsDeletion) {
            String topId = UndoManager.getUndoEntries(uuid, 0, 1).stream()
                .filter(UndoManager.UndoEntry::wasDeleted).map(UndoManager.UndoEntry::customId)
                .findFirst().orElse("?");
            inv.setStack(13, uiGlint(Items.EMERALD, "§a§l↩ Restore Most Recent", "§f" + topId, "§aClick to restore this block now"));
        } else {
            inv.setStack(13, ui(Items.GRAY_DYE, "§7Restore Unavailable", "§8Undo other pending changes first", "§8before restoring a deleted block"));
        }

        // Browse deletions in slots 18-44 (bottom 3 rows minus nav)
        int browseSlots = 27;
        for (int i = 0; i < browseSlots; i++) {
            int idx = offset + i;
            if (idx >= deletions.size()) break;
            UndoManager.UndoEntry e = deletions.get(idx);
            SlotData snap = e.previousState();
            String name = snap != null ? snap.displayName : e.customId();
            inv.setStack(18 + i, ui(Items.CHEST,
                "§f" + name,
                "§7ID: §b" + e.customId(),
                "§8Entry #" + (idx + 1) + " in deletion history"));
        }

        if (page > 0)            inv.setStack(45, uiGlint(Items.ARROW, "§7◀ Previous Page"));
        if (deletions.size() > offset + browseSlots) inv.setStack(53, uiGlint(Items.ARROW, "§7Next Page ▶"));
        inv.setStack(49, uiGlint(Items.ECHO_SHARD, "§c◀ Back")); // Royal Directive
        return inv;
    }

    private static SimpleInventory buildWelcomeGui(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        boolean firstTime = !com.customblocks.core.OnboardingManager.hasSeenWelcome(player.getUuid());

        // Header
        inv.setStack(4, uiGlint(Items.NETHER_STAR,
            "§b§lCustomBlocks — Active",
            "§7" + com.customblocks.core.SlotManager.usedSlots() + " custom block(s) defined",
            "§7Pack: " + (com.customblocks.network.ResourcePackServer.isRunning() ? "§aOnline" : "§cOffline")));

        if (firstTime) {
            // First-time: show tutorial prompt
            inv.setStack(20, uiGlint(Items.ENCHANTED_BOOK,
                "§a§l▶ Start Tutorial",
                "§7Get a step-by-step guide to create your first",
                "§7custom block, give it to yourself, and edit it.",
                "§8Takes about 2 minutes.  §eClick to begin."));

            inv.setStack(24, uiGlint(Items.PAPER,
                "§7Skip Tutorial",
                "§7Go straight to the main menu.",
                "§8You can run /cb help anytime for guidance."));
        } else {
            // Returning player: shortcuts
            inv.setStack(20, uiGlint(Items.BOOK,
                "§6§lQuick Start",
                "§f/cb create §7— New block",
                "§f/cb list   §7— Browse blocks",
                "§f/cb help   §7— Commands",
                "§eClick to open library"));

            inv.setStack(22, uiGlint(Items.CHEST,
                "§a§lBlock Library",
                "§7Browse and edit all " + com.customblocks.core.SlotManager.usedSlots() + " block(s).",
                "§eClick to open"));

            inv.setStack(24, uiGlint(Items.COMPARATOR,
                "§e§lConfiguration",
                "§7Port, cloud sharing, AI, behaviour.",
                "§eClick to open"));

            inv.setStack(31, uiGlint(Items.SHIELD,
                "§c§lSafety Center",
                "§7Undo, backup, and panic tools.",
                "§eClick to open"));
        }

        inv.setStack(49, uiGlint(Items.ECHO_SHARD, "§c◀ Back"));
        return inv;
    }

    // ── 5.24 Feature Menu — 8-tab navigation hub ────────────────────────────────

    private static final Item[] FM_TAB_ITEMS = {
        Items.HEART_OF_THE_SEA, Items.NETHERITE_INGOT, Items.ECHO_SHARD,
        Items.TOTEM_OF_UNDYING, Items.NETHER_STAR, Items.ELYTRA,
        Items.DRAGON_EGG, Items.NETHER_STAR
    };
    private static final String[] FM_TAB_NAMES = {
        "§b§lBlocks", "§6§lEdit", "§5§lBulk", "§d§lHistory",
        "§e§lSettings", "§4§lSafety", "§2§lServer", "§c§lAdmin"
    };
    private static final String[] FM_TAB_DESC = {
        "§7List, Search, Favorites, Recent, Categories",
        "§7Editor, Retexture, Shape, Lock, Notes, Magic Items",
        "§7Bulk Recolor, Delete, Export, Import",
        "§7Macro Scripts & Command History",
        "§7Config, Voice Mode, Hologram",
        "§7Undo, Panic, Recovery, Safety Center",
        "§7Stats, Cache, Marketplace, Diagnostics, Help",
        "§7Permissions, Backup, Audit — admin only"
    };

    private static SimpleInventory buildFeatureMenu(ServerPlayerEntity player, int tab) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        // Header row (slots 0-7): one tab button per tab
        boolean isAdmin = PermissionHelper.canAdmin(player.getCommandSource());
        for (int i = 0; i < 8; i++) {
            boolean adminTab = (i == 7);
            if (adminTab && !isAdmin) {
                inv.setStack(i, ui(Items.BARRIER, "§8§lAdmin", "§7Requires admin permission.", "§8Click to try anyway."));
            } else if (i == tab) {
                inv.setStack(i, uiGlint(FM_TAB_ITEMS[i], FM_TAB_NAMES[i] + " §7[Active]", FM_TAB_DESC[i]));
            } else {
                inv.setStack(i, ui(FM_TAB_ITEMS[i], FM_TAB_NAMES[i], FM_TAB_DESC[i], "§8Click to open this tab."));
            }
        }
        // Slot 8: Menu title/logo - Fixed RD-01 & RD-02
        inv.setStack(8, uiGlint(Items.NETHER_STAR, "§d§l✦ The Celestial Nexus",
            "§7The grand hub of creation.", "§7Forge your vision across all realms.", "§8Pick a tab to explore legendary features."));

        // Content area (slots 9-44)
        switch (tab) {
            case 0 -> {
                inv.setStack(10, ui(Items.BOOKSHELF,       "§fBlock List",     "§7Browse all custom blocks.", "§8Left-click to open."));
                inv.setStack(11, ui(Items.SPYGLASS,        "§fSearch",         "§7Search by name or ID.", "§8Left-click to open."));
                inv.setStack(12, ui(Items.TOTEM_OF_UNDYING,"§fFavorites",      "§7Your starred blocks.", "§8Left-click to open."));
                inv.setStack(13, ui(Items.CLOCK,           "§fRecent Blocks",  "§7Blocks you edited recently.", "§8Left-click to open."));
                inv.setStack(14, ui(Items.BOOK,            "§fCategories",     "§7Browse & manage categories.", "§8Left-click to open."));
            }
            case 1 -> {
                inv.setStack(10, ui(Items.IRON_PICKAXE,    "§fEditor",         "§7Open the block editor.", "§8Left-click to open."));
                inv.setStack(11, ui(Items.PAINTING,        "§fRetexture",      "§7Upload a texture via URL.", "§8Left-click to open."));
                inv.setStack(12, ui(Items.SHEARS,          "§fShape Editor",   "§7Customize block collision.", "§8Left-click to open."));
                inv.setStack(13, ui(Items.TRIPWIRE_HOOK,   "§fLock / Unlock",  "§7Protect a block from edits.", "§8Left-click to open."));
                inv.setStack(14, ui(Items.WRITABLE_BOOK,   "§fBlock Notes",    "§7Admin notes per block.", "§8Left-click to open."));
                inv.setStack(15, ui(Items.NETHERITE_INGOT, "§fMagic Items",    "§7Give yourself mod tools.", "§8Left-click to open."));
                inv.setStack(16, ui(Items.ITEM_FRAME,      "§fColor Studio",   "§7Tint, brighten, or invert.", "§8Left-click to open."));
                inv.setStack(17, ui(Items.AMETHYST_SHARD,  "§fPalette Gen.",   "§7Generate a 16-color set.", "§8Left-click to open."));
            }
            case 2 -> {
                inv.setStack(10, ui(Items.PINK_DYE,        "§fBulk Recolor",   "§7Recolor many blocks at once.", "§8Left-click to open."));
                inv.setStack(11, ui(Items.TNT,             "§fBulk Delete",    "§7Delete a selection of blocks.", "§8Left-click to open."));
                inv.setStack(12, ui(Items.CHEST,           "§fExport All",     "§7Export all blocks to JSON.", "§8Left-click to open."));
                inv.setStack(13, ui(Items.BARREL,          "§fExport Category","§7Export one category to JSON.", "§8Left-click to open."));
                inv.setStack(14, ui(Items.HOPPER,          "§fImport Blocks",  "§7Import blocks from JSON.", "§8Left-click to open."));
                inv.setStack(15, ui(Items.NETHER_STAR,     "§fBulk Hub",       "§7All bulk operations.", "§8Left-click to open."));
            }
            case 3 -> {
                inv.setStack(10, ui(Items.CHAIN_COMMAND_BLOCK, "§fMacro Scripts", "§7Record & replay command macros.", "§8Left-click to open."));
                inv.setStack(11, ui(Items.CLOCK,               "§fUndo History",  "§7Browse and restore past states.", "§8Left-click to open."));
            }
            case 4 -> {
                inv.setStack(10, ui(Items.COMPARATOR,      "§fConfig GUI",     "§7Server configuration settings.", "§8Left-click to open."));
                inv.setStack(11, ui(Items.NOTE_BLOCK,      "§fVoice Mode",     "§7Pick message style.", "§8Left-click to open."));
                boolean hologramOn = com.customblocks.CustomBlocksConfig.hologramEnabled;
                inv.setStack(12, ui(hologramOn ? Items.BEACON : Items.GLASS,
                    hologramOn ? "§aHologram §7(Enabled)" : "§cHologram §7(Disabled)",
                    "§7Floating name above blocks.",
                    "§8Click to toggle. Current: " + (hologramOn ? "§aON" : "§cOFF")));
            }
            case 5 -> {
                inv.setStack(10, ui(Items.SHIELD,          "§fSafety Center",  "§7Broken blocks, auto-snapshots.", "§8Left-click to open."));
                inv.setStack(11, ui(Items.ARROW,           "§fUndo Picker",    "§7Restore a previous state.", "§8Left-click to open."));
                inv.setStack(12, ui(Items.FIRE_CHARGE,     "§4§lPanic Mode",   "§cEmergency reset all blocks.", "§8Left-click — opens panic menu."));
                inv.setStack(13, ui(Items.AMETHYST_SHARD,  "§fRecovery",       "§7Restore deleted blocks.", "§8Left-click to open."));
            }
            case 6 -> {
                inv.setStack(10, ui(Items.EMERALD,         "§fStats",          "§7Placement stats & activity.", "§8Left-click to open."));
                inv.setStack(11, ui(Items.ENDER_PEARL,     "§fResource Pack",  "§7Pack info & rebuild.", "§8Left-click to open."));
                inv.setStack(12, ui(Items.GOLD_INGOT,      "§fMarketplace",    "§7Browse shared blocks.", "§8Left-click to open."));
                inv.setStack(13, ui(Items.COMPASS,         "§fDiagnostics",    "§7Server health & debug.", "§8Left-click to open."));
                inv.setStack(14, ui(Items.LECTERN,         "§fHelp",           "§7Command reference.", "§8Left-click to open."));
            }
            case 7 -> {
                if (isAdmin) {
                    inv.setStack(10, ui(Items.PAPER,       "§fPermissions",    "§7View LuckPerms nodes.", "§8Left-click to open."));
                    inv.setStack(11, ui(Items.NETHER_STAR, "§fForce Save",     "§7Save all data to disk now.", "§8Left-click to execute."));
                    inv.setStack(12, ui(Items.CHEST,       "§fBackup",         "§7Create a snapshot backup.", "§8Left-click to execute."));
                    inv.setStack(13, ui(Items.COMPARATOR,  "§fReload Pack",    "§7Regenerate & push resource pack.", "§8Left-click to execute."));
                    inv.setStack(14, ui(Items.BOOK,        "§fAudit Log",      "§7View /cb audit output.", "§8Left-click to open."));
                } else {
                    inv.setStack(22, ui(Items.BARRIER, "§cAdmin Only", "§7You need admin permission to use this tab."));
                }
            }
            default -> {}
        }

        // Footer - Fixed RD-01
        inv.setStack(49, uiGlint(Items.AMETHYST_CLUSTER, "§c◀ Return to Core", "§7Step back into the primary nexus."));
        return inv;
    }

    private static void handleFeatureMenuClick(ServerPlayerEntity player, GuiState state, int slot) {
        int tab = state.page();
        boolean isAdmin = PermissionHelper.canAdmin(player.getCommandSource());

        // Fix RD-04: Ensure we push backstack state before switching to submenus
        pushBackStack(player.getUuid());

        // Tab header row (slots 0-7)
        if (slot >= 0 && slot < 8) {
            if (slot == 7 && !isAdmin) {
                playError(player);
                ChatHelper.error(player, "Admin tab requires admin permission.");
                return;
            }
            if (slot != tab) {
                playClick(player);
                openFeatureMenu(player, slot);
            }
            return;
        }

        // Footer
        if (slot == 49) { playClick(player); popBackStack(player); return; }

        // Content area — dispatch by active tab
        switch (tab) {
            case 0 -> {
                switch (slot) {
                    case 10 -> { playClick(player); openMain(player, 0); }
                    case 11 -> { playClick(player); openSearchPicker(player, "", 0); }
                    case 12 -> { playClick(player); openFavoritesGui(player, 0); }
                    case 13 -> { playClick(player); openRecentGui(player); }
                    case 14 -> { playClick(player); openCategoryBrowser(player, 0); }
                    default -> {}
                }
            }
            case 1 -> {
                switch (slot) {
                    case 10 -> { playClick(player); openEditorPicker(player); }
                    case 11 -> { playClick(player); openEditorPicker(player); }
                    case 12 -> { playClick(player); openEditorPicker(player); } // shape editor opens via editor
                    case 13 -> { playClick(player); openEditorPicker(player); }
                    case 14 -> { playClick(player); openEditorPicker(player); }
                    case 15 -> { playClick(player); openMagicItemsGui(player); }
                    case 16 -> { playClick(player); openEditorPicker(player); } // color studio via editor
                    case 17 -> { playClick(player); openEditorPicker(player); }
                    default -> {}
                }
            }
            case 2 -> {
                switch (slot) {
                    case 10 -> { playClick(player); openBulkHub(player); }
                    case 11 -> { playClick(player); openBulkDelete(player, 0); }
                    case 12 -> { playClick(player); openBulkHub(player); }
                    case 13 -> { playClick(player); openBulkHub(player); }
                    case 14 -> { playClick(player); openBulkHub(player); }
                    case 15 -> { playClick(player); openBulkHub(player); }
                    default -> {}
                }
            }
            case 3 -> {
                switch (slot) {
                    case 10 -> { playClick(player); openScriptGui(player, 0); }
                    case 11 -> { playClick(player); openUndoPicker(player, 0); }
                    default -> {}
                }
            }
            case 4 -> {
                switch (slot) {
                    case 10 -> { playClick(player); openConfigGui(player); }
                    case 11 -> { playClick(player); openVoicePickerGui(player); }
                    case 12 -> {
                        // Hologram toggle
                        com.customblocks.CustomBlocksConfig.hologramEnabled = !com.customblocks.CustomBlocksConfig.hologramEnabled;
                        com.customblocks.CustomBlocksConfig.save();
                        playClick(player);
                        String msg = com.customblocks.CustomBlocksConfig.hologramEnabled ? "§aHolograms enabled." : "§cHolograms disabled.";
                        player.sendMessage(net.minecraft.text.Text.literal(msg), true);
                        openFeatureMenu(player, 4); // refresh
                    }
                    default -> {}
                }
            }
            case 5 -> {
                switch (slot) {
                    case 10 -> { playClick(player); openMaintenanceMenu(player); }
                    case 11 -> { playClick(player); openUndoPicker(player, 0); }
                    case 12 -> { playClick(player); openMaintenanceMenu(player); }
                    case 13 -> { playClick(player); openRecoverGui(player, 0); }
                    default -> {}
                }
            }
            case 6 -> {
                switch (slot) {
                    case 10 -> { playClick(player); openStatsGui(player); }
                    case 11 -> { playClick(player); openResourceHub(player); }
                    case 12 -> { playClick(player); openMarketGui(player, 0, false); }
                    case 13 -> { playClick(player); openMaintenanceMenu(player); }
                    case 14 -> { playClick(player); openHelpGui(player); }
                    default -> {}
                }
            }
            case 7 -> {
                if (!isAdmin) { playError(player); return; }
                switch (slot) {
                    case 10 -> { playClick(player); openMaintenanceMenu(player); } // permissions via maintenance
                    case 11 -> {
                        com.customblocks.core.SlotManager.flushSave();
                        playClick(player);
                        ChatHelper.success(player, "All data saved to disk.");
                    }
                    case 12 -> {
                        playClick(player);
                        ChatHelper.info(player, "Snapshot backup created.");
                        com.customblocks.core.SnapshotManager.takeSnapshot("manual_feature_menu");
                    }
                    case 13 -> {
                        playClick(player);
                        ChatHelper.info(player, "Regenerating resource pack...");
                        com.customblocks.core.SlotManager.saveAll(); // triggers pack rebuild via packDirty
                        com.customblocks.network.ServerPackGenerator.flushPendingBuildIfNeeded();
                    }
                    case 14 -> { playClick(player); openMaintenanceMenu(player); }
                    default -> {}
                }
            }
            default -> {}
        }
    }

    private static SimpleInventory namedInv(int size) { return new SimpleInventory(size); }

    private static ItemStack previewHead(SlotData d) {
        return named(Items.BARRIER, "§c" + (d.customId != null ? d.customId : "unknown"));
    }

    private static ItemStack named(Item item, String name, String... lore) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name).styled(st -> st.withItalic(false)));
        if (lore.length > 0) {
            List<net.minecraft.text.Text> ll = new ArrayList<>();
            for (String l : lore) ll.add(lore(l));
            s.set(DataComponentTypes.LORE, new LoreComponent(ll));
        }
        return s;
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
                // TAB_ICON_MENU is used as a back-stack marker, not a real screen.
                // When popped via ESC, pop again to restore the caller's screen instead.
                case TAB_ICON_MENU -> popBackStack(player);
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
                case RECOVER_GUI -> openRecoverGui(player, state.page());
                case WELCOME_MENU -> openWelcomeGui(player);
                case FEATURE_MENU -> openFeatureMenu(player, state.page());
                case STATS_GUI -> openStatsGui(player);
                case VARIANT_GUI -> openVariantGui(player, state.editingId());
                case COLOR_STUDIO -> openColorStudio(player, state.editingId());
                case PALETTE_GENERATOR -> openPaletteGenerator(player, state.editingId());
                case AI_SUGGEST_GUI -> openAiSuggestGui(player, state.editingId());
                case FAVORITES_GUI -> openFavoritesGui(player, state.page());
                case RECENT_GUI -> openRecentGui(player);
                case SAFETY_CENTER -> openSafetyCenter(player);
                case HISTORY_GUI -> openHistoryGui(player, state.page());
                case SNAPSHOTS_GUI -> openSnapshotsGui(player, state.page());
                case DELETED_BLOCKS_GUI -> openDeletedBlocksGui(player, state.page());
                case BOX_NUDGE_EDITOR -> openBoxNudgeEditor(player, state.editingId(), state.shapeBoxPage(), state.page());
                case RECOLOR_CONFIRM -> openMain(player, 0); // transient — job not serialised across reconnect
                case SCRIPT_GUI -> openScriptGui(player, state.page());
                case SCRIPT_SUMMARY -> {
                    com.customblocks.core.MacroManager.ScriptRunResult result = LAST_SCRIPT_RESULTS.get(player.getUuid());
                    if (result != null) openScriptSummary(player, result);
                    else openScriptGui(player, 0);
                }
                case CACHE_DASHBOARD -> openCacheDashboard(player, state.page());
                case AUDIT_GUI -> openAuditGui(player, state.page());
                case AI_GEN -> openAiGui(player);
                case CUSTOM_COLOR_STUDIO -> openCustomColorStudio(player, state.editingId());
                case ACHIEVEMENTS_GUI -> openAchievementsGui(player, state.page());
                case MARKET_GUI -> openMarketGui(player, state.page(), false);
                case BULK_HUB -> openBulkHub(player);
                case BULK_OP_PICKER -> openBulkOpPicker(player, state.editingId(), state.page());
                case COLOR_PICKER -> openColorPicker(player, state.editingId());
                case VOICE_PICKER -> openVoicePickerGui(player); // 5.25
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
                case RECOVER_GUI -> handleRecoverGuiClick(player, state, slot);
                case WELCOME_MENU -> {
                    boolean firstTime = !com.customblocks.core.OnboardingManager.hasSeenWelcome(player.getUuid());
                    if (firstTime) {
                        if (slot == 20) { // Start Tutorial
                            com.customblocks.core.OnboardingManager.markSeen(player.getUuid());
                            com.customblocks.core.OnboardingManager.sendTutorial(player);
                            openMain(player, 0);
                        } else if (slot == 24 || slot == 49) { // Skip
                            com.customblocks.core.OnboardingManager.markSeen(player.getUuid());
                            openMain(player, 0);
                        }
                    } else {
                        if (slot == 20 || slot == 22) { openMain(player, 0); }     // Quick Start / Library
                        else if (slot == 24) { openConfigGui(player); }             // Configuration
                        else if (slot == 31) { openSafetyCenter(player); }         // Safety Center
                        else if (slot == 49) { openMain(player, 0); }              // Back
                    }
                }
                case FEATURE_MENU -> handleFeatureMenuClick(player, state, slot);
                case STATS_GUI -> handleStatsGuiClick(player, state, slot);
                case VARIANT_GUI -> handleVariantGuiClick(player, state, slot);
                case COLOR_STUDIO -> handleColorStudioClick(player, state, slot);
                case PALETTE_GENERATOR -> handlePaletteGeneratorClick(player, state, slot);
                case AI_SUGGEST_GUI -> handleAiSuggestClick(player, state, slot);
                case FAVORITES_GUI -> handleFavoritesGuiClick(player, state, slot);
                case RECENT_GUI -> handleRecentGuiClick(player, state, slot);
                case SAFETY_CENTER -> handleSafetyCenterClick(player, state, slot);
                case HISTORY_GUI -> handleHistoryGuiClick(player, state, slot);
                case SNAPSHOTS_GUI -> handleSnapshotsGuiClick(player, state, slot);
                case DELETED_BLOCKS_GUI -> handleDeletedBlocksGuiClick(player, state, slot, button);
                case BOX_NUDGE_EDITOR -> handleBoxNudgeClick(player, state, slot, button);
                case RECOLOR_CONFIRM -> handleRecolorConfirmClick(player, state, slot);
                case SCRIPT_GUI -> handleScriptGuiClick(player, state, slot, button);
                case SCRIPT_SUMMARY -> handleScriptSummaryClick(player, state, slot);
                case CACHE_DASHBOARD -> handleCacheDashboardClick(player, state, slot);
                case AUDIT_GUI -> handleAuditGuiClick(player, state, slot);
                case AI_GEN -> handleAiHubClick(player, state, slot);
                case CUSTOM_COLOR_STUDIO -> handleCustomColorStudioClick(player, state, slot);
                case ACHIEVEMENTS_GUI -> handleAchievementsGuiClick(player, state, slot);
                case MARKET_GUI -> handleMarketGuiClick(player, state, slot);
                case BULK_HUB -> handleBulkHubClick(player, state, slot);
                case BULK_OP_PICKER -> handleBulkOpPickerClick(player, state, slot);
                case COLOR_PICKER -> handleColorPickerClick(player, state, slot);
                case VOICE_PICKER -> handleVoicePickerClick(player, slot); // 5.25
                default -> {
                    LOGGER.warn("[CustomBlocks] Unhandled GUI mode in handleClick: {} for player {}",
                            state.mode(), player.getName().getString());
                    playError(player);
                    send(player, "§cThis screen is not available yet.");
                }
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
            send(player, "Cancelled.");
            PENDING_CATEGORIES.remove(player.getUuid());
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
                send(player, "Downloading '" + name + "'…");
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
                        FeedbackHelper.actionBar(player, "§a§l✔ §r§aCreated: §f" + name);
                        FeedbackHelper.title(player, "§a§l✔ Created!", "§f" + name);
                        NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("add", d.index, id, name, fb, d.lightLevel, d.hardness, d.soundType, null, null, updated != null ? updated.animMeta : fa));
                        ChatHelper.success(player, "Created '§f" + name + "§a'! §7(slot #" + d.index + ")");
                        openEditor(player, id, rp);
                    });
                } catch (Exception e) { srv.execute(() -> { playError(player); send(player, "§c[GUI] Failed: " + e.getMessage()); openMain(player, rp); }); } });
                return true;
            }
            case RETEXTURE_URL -> {
                if (!isUrl(text)) { playError(player); send(player, "§cNeeds a URL."); openEditor(player, blockId, rp); return true; }
                send(player, "Downloading texture…");
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
                        FeedbackHelper.actionBar(player, "§a§l✔ §r§aTexture updated: §f" + blockId);
                        NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("retexture", d.index, blockId, null, result.bytes(), d.lightLevel, d.hardness, d.soundType, null, null, result.mcmeta()));
                        // 1.21 — Retexture confirmation with player count
                        int onlinePlayers = srv.getPlayerManager().getPlayerList().size();
                        String animTag = result.isAnimated() ? " §b(Animated)" : "";
                        if (onlinePlayers > 0) {
                            ChatHelper.success(player, blockId + " retextured — pack queued." + animTag
                                    + " §7Refreshing for §f" + onlinePlayers + " §7player(s).");
                        } else {
                            ChatHelper.success(player, blockId + " retextured — pack queued." + animTag
                                    + " §7Pack will sync when players join.");
                        }
                        openEditor(player, blockId, rp);
                    });
                } catch (Exception e) { srv.execute(() -> { playError(player); send(player, "§c[GUI] Failed: " + e.getMessage()); openEditor(player, blockId, rp); }); } });
                return true;
            }
            case SETFACE_URL -> {
                if (!isUrl(text)) { send(player, "§cNeeds a URL."); openFaceEditor(player, blockId, rp); return true; }
                String face = pending.face();
                send(player, "Downloading " + face + " face…");
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
                        FeedbackHelper.actionBar(player, "§a§l✔ §r§a" + face.toUpperCase() + " face set: §f" + blockId);
                        NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("setface", d.index, blockId, null, result.bytes(),
                                d.lightLevel, d.hardness, d.soundType, face, null,
                                result.isAnimated() ? result.mcmeta() : null));
                        String suffix = result.isAnimated() ? " §8(animated)" : "";
                        send(player, "§f" + face.toUpperCase() + " §aface set on '§f" + blockId + "§a'." + suffix);
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
                send(player, "Creating variant with " + face + " face…");
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
                        com.customblocks.block.SlotBlock.SlotItem nbItem = CustomBlocksMod.safeSlotItem(nb.index);
                        if (nbItem != null) { ItemStack giveStack = new ItemStack(nbItem, 1); if (!player.getInventory().insertStack(giveStack)) player.dropStack(giveStack); }
                        send(player, "Variant '§f" + varId + "§a' created & given!");
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
                send(player, "Renamed to '§f" + convertedText + "§a'.");
                player.getServerWorld().playSound(null, player.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_ANVIL_USE, net.minecraft.sound.SoundCategory.MASTER, 1f, 1f);
                openEditor(player, blockId, rp); return true;
            }
            case SET_HOLOGRAM_TEXT -> {
                SlotData dHolo = SlotManager.getById(blockId);
                if (dHolo == null) { openMain(player, rp); return true; }
                if ("cancel".equalsIgnoreCase(text)) { openEditor(player, blockId, rp); return true; }
                String newHoloRaw = "clear".equalsIgnoreCase(text) ? null
                    : text.replace("&", "§").replace("_", " ");
                final String newHoloText = (newHoloRaw != null && newHoloRaw.length() > 64)
                    ? newHoloRaw.substring(0, 64) : newHoloRaw;
                UndoManager.pushUndoMutation(blockId, dHolo, "hologram", player.getUuid());
                SlotManager.update(blockId, d2 -> d2.withHologramText(newHoloText));
                SlotManager.saveAll();
                if (newHoloText == null || newHoloText.isBlank()) {
                    send(player, "§7Hologram text §ccleared§7.");
                } else {
                    send(player, "§bHologram set to §f'" + newHoloText + "§f'§b.");
                }
                player.getServerWorld().playSound(null, player.getBlockPos(),
                    net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                    net.minecraft.sound.SoundCategory.MASTER, 1f, 1.2f);
                openEditor(player, blockId, rp); return true;
            }
            case AI_CHAT_QUERY -> {
                if ("cancel".equalsIgnoreCase(text)) { return true; }
                postAiQuery(player, text);
                return true;
            }
            case CONFIRM_SCRIPT_DELETE -> {
                if ("confirm".equalsIgnoreCase(text) && blockId != null) {
                    boolean deleted = com.customblocks.core.MacroManager.deleteMacro(blockId);
                    if (deleted) {
                        LAST_SCRIPT_RESULTS.remove(player.getUuid());
                        send(player, "§a[Script] Deleted §f" + blockId + "§a.");
                    } else {
                        send(player, "§c[Script] Could not delete §f" + blockId + "§c.");
                    }
                } else {
                    send(player, "§7[Script] Delete cancelled.");
                }
                openScriptGui(player, 0);
                return true;
            }
            case SETTABICON_URL -> {
                if ("cancel".equalsIgnoreCase(text)) { openMain(player, rp); return true; }
                String targetId = text.toLowerCase().trim();
                boolean isBlock = SlotManager.hasId(targetId);
                if (!isUrl(text) && !isBlock) { send(player, "§cNeeds a URL or Block ID."); openMain(player, rp); return true; }
                send(player, "Processing tab icon…");
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
                        send(player, "Tab icon updated!");
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
                if ("__market_search__".equals(blockId)) {
                    MARKET_SEARCH_QUERIES.put(player.getUuid(), text.toLowerCase(java.util.Locale.ROOT).trim());
                    java.util.List<JsonObject> cached = MARKET_CACHE.getOrDefault(player.getUuid(), java.util.List.of());
                    openScreenFromGuiState(player, GuiState.marketGui(0),
                        buildMarketGui(player.getUuid(), cached, 0),
                        Text.translatable("customblocks.gui.market.title", cached.size()));
                    return true;
                }
                if ("__givesquare__".equals(blockId)) {
                    String col = text.toLowerCase().trim();
                    if (!List.of("black","yellow","green").contains(col)) { send(player, "§cChoose: §fblack §7| §fyellow §7| §fgreen"); openMain(player, rp); return true; }
                    Item it = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, col + "_square"));
                    if (it != null && it != Items.AIR) player.getInventory().insertStack(new ItemStack(it, 1));
                    send(player, "Given §f" + col + " Square§a!"); openMain(player, rp); return true;
                }
                if ("__givetriangle__".equals(blockId)) {
                    String col = text.toLowerCase().trim();
                    if (!List.of("black","yellow","green").contains(col)) { send(player, "§cChoose: §fblack §7| §fyellow §7| §fgreen"); openMain(player, rp); return true; }
                    Item it = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, col + "_triangle"));
                    if (it != null && it != Items.AIR) player.getInventory().insertStack(new ItemStack(it, 1));
                    send(player, "Given §f" + col + " Triangle§a!"); openMain(player, rp); return true;
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
                send(player, "Re-ID'd '§f" + blockId + "§a' → '§f" + newId + "§a'.");
                openEditor(player, newId, rp); return true;
            }
            case ADMIN_CUSTOM_TITLE -> {
                send(player, "Action cancelled.");
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
                    send(player, "Light level set to " + light + ".");
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
                    send(player, "Hardness set to " + hardness + ".");
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
                    final float finalFps = customFps;
                    ANIM_PARAMS.compute(player.getUuid(), (k, ap) -> { // 7.9 — atomic update
                        AnimParams cur = ap != null ? ap : new AnimParams(10f, false, 1);
                        return new AnimParams(finalFps, cur.interpolate(), cur.frameCount());
                    });
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
                        case "maxSlots" -> {
                            int newVal = Math.max(1, Math.min(8192, Integer.parseInt(text)));
                            // 1.16 Guard 2 — refuse to lower below highest used slot index
                            int highestUsed = com.customblocks.core.SlotManager.highestUsedSlotIndex();
                            if (highestUsed >= 0 && newVal < highestUsed + 1) {
                                int needed = highestUsed + 1;
                                send(player, "§c[CustomBlocks] ⚠ Cannot reduce Block Capacity to " + newVal
                                    + " — you have blocks using slot indices up to §f" + highestUsed
                                    + "§c. Keeping it at §f" + needed + "§c.");
                                newVal = needed;
                            }
                            CustomBlocksConfig.maxSlots = newVal;
                        }
                        case "defaultTextureSize" -> CustomBlocksConfig.defaultTextureSize = Math.max(16, Math.min(256, Integer.parseInt(text)));
                        case "bgRemovalTolerance" -> CustomBlocksConfig.bgRemovalTolerance = Math.max(0, Math.min(100, Integer.parseInt(text)));
                        case "maxUndoDepth" -> CustomBlocksConfig.maxUndoDepth = Math.max(1, Math.min(100, Integer.parseInt(text)));
                        case "downloadTimeoutSeconds" -> CustomBlocksConfig.downloadTimeoutSeconds = Math.max(1, Math.min(120, Integer.parseInt(text)));
                        case "texturePayloadsPerTick" -> CustomBlocksConfig.texturePayloadsPerTick = Math.max(1, Math.min(50, Integer.parseInt(text)));
                        case "resourcePackPort" -> CustomBlocksConfig.resourcePackPort = Math.max(0, Math.min(65535, Integer.parseInt(text)));
                        case "reloadDebounceMs" -> CustomBlocksConfig.reloadDebounceMs = Math.max(500, Math.min(10000, Long.parseLong(text)));
                        // cloudShareUrl is now a hardcoded constant — not editable via GUI
                        case "aiWorkerUrl" -> CustomBlocksConfig.aiWorkerUrl = text.trim();
                        case "aiServerToken" -> CustomBlocksConfig.aiServerToken = text.trim();
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
                        case "voiceMode" -> CustomBlocksConfig.voiceMode = text;
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
                send(player, "§aBulk recolor scope value saved: §f" + text.trim());
                openBulkRecolorWizard(player, 0);
                return true;
            }
            case BULK_RECOLOR_EXCLUDE -> {
                BULK_RECOLOR_EXCLUDE.put(player.getUuid(), text.trim());
                BULK_RECOLOR_CONFIRM_ARMED.remove(player.getUuid());
                send(player, "§aExclude list saved.");
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
                        com.customblocks.core.AchievementManager.onCategoryCreated(player);
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
                    String originBlockId = catData.get("originBlockId");
                    com.customblocks.core.Category cat = com.customblocks.core.Category.create(catData.get("displayName"))
                        .withIconItem(catData.get("iconItem"))
                        .withColor(catData.get("color"))
                        .withBadge(text.trim());
                    if (catData.containsKey("parentKey") && catData.get("parentKey") != null && !catData.get("parentKey").isEmpty()) {
                        cat = cat.withParentKey(catData.get("parentKey").toLowerCase());
                    }
                    com.customblocks.core.CategoryManager.addCategory(cat);
                    playCategoryCreate(player);
                    com.customblocks.core.AchievementManager.onCategoryCreated(player);
                    send(player, "§aCreated category: §f" + cat.displayName());
                    if (originBlockId != null && SlotManager.hasId(originBlockId)) {
                        com.customblocks.core.CategoryManager.assignBlock(originBlockId, cat.key());
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
    public static void clearState(ServerPlayerEntity player) { STATES.remove(player.getUuid()); PENDING.remove(player.getUuid()); FACE_IMPORTS.remove(player.getUuid()); FACE_CHANGE_SELECTIONS.remove(player.getUuid()); FACE_CHANGE_RETURN_PAGES.remove(player.getUuid()); BACK_STACK.remove(player.getUuid()); PENDING_RECOLORS.remove(player.getUuid()); }

    public static void checkPendingFaceImports(MinecraftServer server) {
        if (FACE_IMPORTS.isEmpty()) {
            faceImportTickCounter.set(0);
            return;
        }
        if (faceImportTickCounter.incrementAndGet() < FACE_IMPORT_POLL_TICKS) return;
        faceImportTickCounter.set(0);

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
            player.sendMessage(ChatHelper.rawPrefixed("§fDownload Link: ")
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
        openScreenFromGuiState(player, GuiState.magicItems(), buildMagicItemsGui(), Text.translatable("customblocks.gui.magic_items.title"));
    }

    private static SimpleInventory buildMagicItemsGui() {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        // Row 1: Header
        inv.setStack(4, uiGlint(Items.NETHER_STAR, "§6§l✦ §r§fMagic Items Arsenal", "§7Your legendary toolkit", "§8Click any item to receive it"));
        // Row 2: Color Squares
        inv.setStack(9, ui(Items.NETHER_STAR, "§6── Colour Squares ──", "§7Swap block colours instantly"));
        inv.setStack(10, uiGlint(Items.GREEN_CONCRETE, "§a§lGreen Square", "§7Click to receive"));
        inv.setStack(11, uiGlint(Items.YELLOW_CONCRETE, "§e§lYellow Square", "§7Click to receive"));
        inv.setStack(12, uiGlint(Items.BLACK_CONCRETE, "§8§lBlack Square", "§7Click to receive"));
        // Row 2: Color Triangles
        inv.setStack(14, ui(Items.AMETHYST_CLUSTER, "§5── Colour Triangles ──", "§7Paint backgrounds onto blocks"));
        inv.setStack(15, uiGlint(Items.GREEN_TERRACOTTA, "§a§lGreen Triangle", "§7Click to receive"));
        inv.setStack(16, uiGlint(Items.YELLOW_TERRACOTTA, "§e§lYellow Triangle", "§7Click to receive"));
        inv.setStack(17, uiGlint(Items.BLACK_TERRACOTTA, "§8§lBlack Triangle", "§7Click to receive"));
        // Row 3: Premium Tools
        inv.setStack(18, ui(Items.ECHO_SHARD, "§b── Premium Tools ──", "§7Legendary instruments of creation"));
        inv.setStack(19, uiGlint(Items.PAINTING, "§6§lRainbow Rectangle", "§7Face-painting wand", "§8Right-click a block face → paste URL"));
        inv.setStack(20, uiGlint(Items.GOLDEN_APPLE, "§6§lGolden Hexagon", "§7UV face rotator & flipper", "§8Right-click = rotate 90°", "§8Sneak+click = flip horizontally"));
        inv.setStack(21, uiGlint(Items.BLAZE_ROD, "§b§lLumina Brush", "§7Property painter", "§8Right-click any block → light & hardness sliders"));
        inv.setStack(22, uiGlint(Items.AMETHYST_SHARD, "§5§lAmethyst Chisel", "§7Shape sculptor", "§8Right-click any block → shape presets & editor"));
        inv.setStack(23, uiGlint(Items.DIAMOND, "§b§lDiamond Triangle", "§7Background Studio master", "§8Right-click anywhere → tolerance slider, presets, bulk re-apply", "§8YCbCr / CIE-Lab powered"));
        // Row 4: Quick actions
        inv.setStack(31, uiGlint(Items.EMERALD, "§a§l▶ Give All Items", "§7Click to get every magic item at once", "§aIncludes all squares, triangles, and tools"));
        // Bottom row
        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back")); // Royal Directive
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
            default -> {}
        }
    }

    // ── Diamond Triangle: Background Studio ───────────────────────────────────

    public static void openBgStudio(ServerPlayerEntity player) {
        openBgStudio(player, true);
    }

    public static void openBgStudio(ServerPlayerEntity player, boolean pushBack) {
        if (pushBack) pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.bgStudio(), buildBgStudioGui(), Text.translatable("customblocks.gui.bg_studio.title"));
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
        inv.setStack(0, uiGlint(Items.ECHO_SHARD, "§c◀ Back", "§8Return to main menu")); // Royal Directive
        inv.setStack(10, toggleItem("YCbCr Math", CustomBlocksConfig.bgRemovalUseYcbcr,
            "Separates brightness from colour to reduce light edge halos"));
        inv.setStack(13, enabled
            ? uiGlint(Items.EMERALD, "§a§l✔ Background Removal: §lON",
                "§7Currently §atrimming §7white/transparent edges",
                "§8Click to disable")
            : uiGlint(Items.FLINT, "§7§l✖ Background Removal: §lOFF",
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
        inv.setStack(36, ui(Items.COBBLESTONE,    "§7Preset: §lOff", "§70 — keep originals"));
        inv.setStack(37, ui(Items.OAK_SAPLING,    "§aPreset: §lLight",  "§720 — only pure white"));
        inv.setStack(38, ui(Items.GOLD_NUGGET,    "§ePreset: §lBalanced","§730 — default, recommended"));
        inv.setStack(39, ui(Items.BLAZE_POWDER,   "§6Preset: §lStrong", "§750 — catches off-white"));
        inv.setStack(40, ui(Items.REDSTONE,       "§cPreset: §lAggressive","§775 — removes most light tones"));

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
        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back")); // Royal Directive
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
            boolean useBossBar = snapshot.size() >= 2;
            if (useBossBar) server.execute(() -> FeedbackHelper.startBossBar(player, "§5Background re-apply..."));
            try {
            for (int i = 0; i < snapshot.size(); i++) {
                SlotData d = snapshot.get(i);
                if (useBossBar) {
                    final int idx = i;
                    final int total = snapshot.size();
                    server.execute(() -> FeedbackHelper.updateBossBar(player,
                        "§5Re-applying " + (idx + 1) + " / " + total,
                        (idx + 1) / (float) total));
                }
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
            } finally {
                if (useBossBar) server.execute(() -> FeedbackHelper.clearBossBar(player));
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
        openScreenFromGuiState(player, GuiState.configWarning(), buildConfigWarningGui(), Text.translatable("customblocks.gui.config_warning.title"));
    }

    private static SimpleInventory buildConfigWarningGui() {
        SimpleInventory inv = new SimpleInventory(27);
        for (int i = 0; i < 27; i++) inv.setStack(i, glass());
        inv.setStack(4, uiGlint(Items.COMPARATOR, "§6§lServer Config",
            "§7These settings affect the entire server.",
            "§7Changing them can impact every player and every block.",
            "§eOnly continue if you mean to edit live server-wide behavior."));
        inv.setStack(11, uiGlint(Items.TOTEM_OF_UNDYING, "§c◀ Back",
            "§7Return without changing server config.")); // Royal Directive
        inv.setStack(15, uiGlint(Items.NETHER_STAR, "§a§lContinue",
            "§7Open the advanced server config panel.")); // Royal Directive
        return inv;
    }

    private static void handleConfigWarningClick(ServerPlayerEntity player, GuiState state, int slot) {
        switch (slot) {
            case 11 -> openMain(player, 0);
            case 15 -> openConfigGui(player, false);
            default -> {}
        }
    }

    public static void openConfigGui(ServerPlayerEntity player) {
        openConfigGui(player, true);
    }

    public static void openConfigGui(ServerPlayerEntity player, boolean pushBack) {
        if (pushBack) pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.configGui(), buildConfigGui(), Text.translatable("customblocks.gui.config.title"));
    }

    private static SimpleInventory buildConfigGui() {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        // Row 1: Toggles
        inv.setStack(11, strItem("AI Worker URL", CustomBlocksConfig.aiWorkerUrl.isEmpty() ? "(not set)" : CustomBlocksConfig.aiWorkerUrl, "Cloudflare Worker URL for AI chat"));
        inv.setStack(12, strItem("AI Server Token", CustomBlocksConfig.aiServerToken.isEmpty() ? "(not set)" : "••••••••", "Server identity token sent to the AI Worker"));
        inv.setStack(13, toggleItem("Cloud Share", CustomBlocksConfig.cloudShareEnabled, "Upload and fetch share codes from the Cloud Vault"));
        // 1.29 — Hologram toggle
        inv.setStack(14, uiGlint(CustomBlocksConfig.hologramEnabled ? Items.LANTERN : Items.SOUL_LANTERN,
            CustomBlocksConfig.hologramEnabled ? "§a§lHologram §a✔ Enabled" : "§c§lHologram §c✖ Disabled",
            "§7Show floating names above placed custom blocks",
            "§8Height: " + CustomBlocksConfig.hologramHeight + " blocks",
            "§8Click to toggle"));
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
        inv.setStack(28, glass());
        inv.setStack(29, strItem("History Mode", CustomBlocksConfig.undoMode, "Choose whether undo history is shared or per-player"));
        inv.setStack(30, colorFillModeButton(CustomBlocksConfig.colorToolBackgroundMode));
        inv.setStack(31, strItem("Green Shade", CustomBlocksConfig.triangleGreenHex, "Edit the built-in Green triangle/square shade"));
        inv.setStack(32, voiceModeButton(CustomBlocksConfig.voiceMode));
        // Slot 33 was Cloud Vault URL — removed (URL is now a hardcoded constant, not user-configurable)
        inv.setStack(34, strItem("Yellow Shade", CustomBlocksConfig.triangleYellowHex, "Edit the built-in Yellow triangle/square shade"));
        // Row 5: Back
        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back")); // Royal Directive
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

    private static ItemStack voiceModeButton(String mode) {
        // V4-46: All 6 voice modes shown as cycling button
        Item icon = switch (mode.toLowerCase(java.util.Locale.ROOT)) {
            case "friendly"     -> Items.BOOK;
            case "professional" -> Items.WRITABLE_BOOK;
            case "royal"        -> Items.WRITTEN_BOOK;
            case "minimal"      -> Items.MAP;
            case "arabic"       -> Items.KNOWLEDGE_BOOK;
            case "silly"        -> Items.HONEYCOMB;
            default             -> Items.NAME_TAG;
        };
        String desc = switch (mode.toLowerCase(java.util.Locale.ROOT)) {
            case "friendly"     -> "Celebratory language with symbols";
            case "professional" -> "Clean and factual, no flair";
            case "royal"        -> "Elaborate, formal tone";
            case "minimal"      -> "One-line messages only, no color codes";
            case "arabic"       -> "Arabic language messages";
            case "silly"        -> "Playful and comedic";
            default             -> "Unknown mode";
        };
        return uiGlint(icon, "§d§lVoice Tone §f→ §e" + mode,
            "§7" + desc,
            "§8Click to cycle · modes: friendly / professional / royal",
            "§8                 minimal / arabic / silly");
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

        inv.setStack(22, uiGlint(Items.ECHO_SHARD, "§c← Back"));
        openScreenFromGuiState(player, GuiState.colorFillMode(), inv, Text.translatable("customblocks.gui.color_fill.title"));
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
            default -> {}
        }
    }

    private static void handleConfigGuiClick(ServerPlayerEntity player, GuiState state, int slot) {
        switch (slot) {
            // Toggles
            case 11 -> configPrompt(player, "aiWorkerUrl", "AI Worker URL (Cloudflare Worker):");
            case 12 -> configPrompt(player, "aiServerToken", "AI Server Token (server password):");
            case 13 -> {
                CustomBlocksConfig.cloudShareEnabled = !CustomBlocksConfig.cloudShareEnabled;
                CustomBlocksConfig.save();
                send(player, "§a[Config] cloudShareEnabled = " + CustomBlocksConfig.cloudShareEnabled);
                openConfigGui(player, false);
            }
            case 14 -> { // 1.29 — Hologram toggle
                CustomBlocksConfig.hologramEnabled = !CustomBlocksConfig.hologramEnabled;
                CustomBlocksConfig.save();
                boolean on = CustomBlocksConfig.hologramEnabled;
                player.sendMessage(Text.literal(on
                    ? "§a[CB] Holograms enabled." : "§7[CB] Holograms disabled."), true);
                if (on && CustomBlocksConfig.hologramHeight <= 0.0f) {
                    player.sendMessage(Text.literal(
                        "§e[CB] Hologram height is 0. Set hologram-height above 0 in config for visible holograms."), true);
                }
                if (player.getWorld() instanceof net.minecraft.server.world.ServerWorld sw) {
                    sw.playSound(null, player.getBlockPos(),
                        net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                        net.minecraft.sound.SoundCategory.PLAYERS, 0.8f, on ? 1.5f : 0.8f);
                }
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
            case 28 -> {} // slot reserved
            case 29 -> configPrompt(player, "undoMode", "History Mode (global / per_player / both):");
            case 30 -> openColorFillModeGui(player);
            case 31 -> configPrompt(player, "triangleGreenHex", "Green Shade Hex (#RRGGBB):");
            case 32 -> {
                // V4-46: cycle through all 6 voice modes
                java.util.List<String> modes = java.util.List.of("friendly","professional","royal","minimal","arabic","silly");
                String cur = CustomBlocksConfig.voiceMode;
                int idx = modes.indexOf(cur.toLowerCase(java.util.Locale.ROOT));
                String next = modes.get((idx + 1) % modes.size());
                CustomBlocksConfig.voiceMode = next;
                CustomBlocksConfig.save();
                playClick(player);
                send(player, "§a[Config] §fVoice mode: §e" + next);
                openConfigGui(player, false);
            }
            // case 33 was Cloud Vault URL — removed (hardcoded constant)
            case 34 -> configPrompt(player, "triangleYellowHex", "Yellow Shade Hex (#RRGGBB):");
            case 45 -> openMain(player, 0);
            default -> {}
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
        openScreenFromGuiState(player, GuiState.undoPicker(page), buildUndoPicker(player, page), Text.translatable("customblocks.gui.undo.title"));
    }

    private static SimpleInventory buildUndoPicker(ServerPlayerEntity player, int page) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        UUID uuid = player.getUuid();
        int perPage = 8;
        int offset = page * perPage;

        int undoSz = UndoManager.undoSize(uuid);
        int redoSz = UndoManager.redoSize(uuid);

        inv.setStack(4, uiGlint(Items.KNOWLEDGE_BOOK, "§6§lUndo / Redo History",
            "§7Undo stack: §f" + undoSz + " §7entries",
            "§7Redo stack: §f" + redoSz + " §7entries",
            "§7Page §f" + (page + 1) + "§7, showing entries §f" + (offset + 1) + "–" + (offset + perPage),
            "§8Click an entry to apply it."));

        // Undo entries: slots 10-17 (up to 8 per page)
        inv.setStack(9, uiGlint(Items.GOLDEN_PICKAXE, "§6§l↩ UNDO", "§7Click an entry to undo it"));
        List<UndoManager.UndoEntry> undos = UndoManager.getUndoEntries(uuid, offset, perPage);
        for (int i = 0; i < perPage; i++) {
            if (i < undos.size()) {
                UndoManager.UndoEntry e = undos.get(i);
                int absPos = offset + i + 1;
                String label = e.wasDeleted() ? "§cRestore §f" + e.customId() : "§6Undo §f" + e.description() + " §7on §f" + e.customId();
                inv.setStack(10 + i, uiGlint(e.wasDeleted() ? Items.CHEST : Items.PAPER,
                    label, "§8History position #" + absPos, (offset + i) == 0 ? "§aClick to apply" : "§eClick to apply #1–#" + (offset + i + 1)));
            }
        }

        // Redo entries: slots 28-35 (up to 8 per page)
        inv.setStack(27, uiGlint(Items.DIAMOND_PICKAXE, "§b§l↪ REDO", "§7Click an entry to redo it"));
        List<UndoManager.UndoEntry> redos = UndoManager.getRedoEntries(uuid, offset, perPage);
        for (int i = 0; i < perPage; i++) {
            if (i < redos.size()) {
                UndoManager.UndoEntry e = redos.get(i);
                int absPos = offset + i + 1;
                String label = e.wasDeleted() ? "§cRe-delete §f" + e.customId() : "§bRedo §f" + e.description() + " §7on §f" + e.customId();
                inv.setStack(28 + i, uiGlint(e.wasDeleted() ? Items.BARRIER : Items.MAP,
                    label, "§8History position #" + absPos, (offset + i) == 0 ? "§aClick to apply" : "§eClick to apply #1–#" + (offset + i + 1)));
            }
        }

        // Pagination buttons at row 5 (slots 46-52)
        if (page > 0) inv.setStack(46, uiGlint(Items.ARROW, "§7◀ Previous Page", "§8Page " + page));
        boolean hasMoreUndo = undoSz > offset + perPage;
        boolean hasMoreRedo = redoSz > offset + perPage;
        if (hasMoreUndo || hasMoreRedo) inv.setStack(52, uiGlint(Items.ARROW, "§7Next Page ▶", "§8Page " + (page + 2)));

        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back")); // Royal Directive
        return inv;
    }

    private static void handleUndoPickerClick(ServerPlayerEntity player, GuiState state, int slot) {
        UUID uuid = player.getUuid();
        int page = state.page();
        if (slot == 45) { openMain(player, 0); return; }
        if (slot == 46 && page > 0) {
            openScreenFromGuiState(player, GuiState.undoPicker(page - 1), buildUndoPicker(player, page - 1), Text.translatable("customblocks.gui.undo.title"));
            return;
        }
        if (slot == 52) {
            openScreenFromGuiState(player, GuiState.undoPicker(page + 1), buildUndoPicker(player, page + 1), Text.translatable("customblocks.gui.undo.title"));
            return;
        }
        // Undo: clicking entry N applies entries 1..N in sequence (GUI-8 — all 8 entries clickable)
        int perPage = 8;
        int offset = page * perPage;
        if (slot >= 10 && slot <= 17) {
            int relIdx = slot - 10;
            int absIdx = offset + relIdx;
            if (absIdx >= UndoManager.undoSize(uuid)) return;
            int count = absIdx + 1; // undo #1 through clicked entry
            int done = 0;
            for (int i = 0; i < count && UndoManager.undoSize(uuid) > 0; i++) {
                UndoManager.UndoEntry e = UndoManager.popUndo(uuid);
                if (e != null) { applyUndoEntry(player, e); done++; }
            }
            if (done > 1) FeedbackHelper.actionBar(player, "§6§l↩ §r§eUndid §f" + done + " §eactions.");
            else if (done == 1) FeedbackHelper.actionBar(player, "§6§l↩ §r§eUndo applied.");
            else send(player, "§7Nothing to undo.");
            refreshScreen(player, buildUndoPicker(player, 0));
            STATES.put(uuid, GuiState.undoPicker(0));
            return;
        }
        // Redo: clicking entry N applies entries 1..N in sequence
        if (slot >= 28 && slot <= 35) {
            int relIdx = slot - 28;
            int absIdx = offset + relIdx;
            if (absIdx >= UndoManager.redoSize(uuid)) return;
            int count = absIdx + 1;
            int done = 0;
            for (int i = 0; i < count && UndoManager.redoSize(uuid) > 0; i++) {
                UndoManager.UndoEntry e = UndoManager.popRedo(uuid);
                if (e != null) { applyRedoEntry(player, e); done++; }
            }
            if (done > 1) FeedbackHelper.actionBar(player, "§b§l↪ §r§eRedid §f" + done + " §eactions.");
            else if (done == 1) FeedbackHelper.actionBar(player, "§b§l↪ §r§eRedo applied.");
            else send(player, "§7Nothing to redo.");
            refreshScreen(player, buildUndoPicker(player, 0));
            STATES.put(uuid, GuiState.undoPicker(0));
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
                        send(player, "§eGiven §6Rainbow Rectangle§e!");
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
            default -> {}
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
        // V4-23: slot 8 = search in normal picker
        if (slot == 8 && !brokenOnly) {
            openShortInputPrompt(player,
                new PendingInput(InputAction.REID_TEXT, "__search__", null, null, null, page),
                "§6Search Blocks", new ItemStack(Items.SPYGLASS), "");
            return;
        }
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
        // 1.27 — sort button
        if (slot == 51 && !brokenOnly) {
            openSortMenu(player, page);
            return;
        }
        if (slot == 53) {
            if (brokenOnly) openBrokenBlocks(player, page+1);
            else openEditorPicker(player, page+1);
            return;
        }
        if (slot >= 18 && slot <= 35) {
            // 1.23 — broken view uses reason map for up-to-date list
            List<SlotData> blocks = brokenOnly
                    ? new java.util.ArrayList<>(com.customblocks.core.SlotManager.brokenBlocksWithReasons().keySet())
                    : sortedBlocks();
            int idx = page * BLOCKS_PER_PAGE + (slot - 18);
            if (idx < blocks.size()) {
                String targetId = blocks.get(idx).customId;
                // Favorite toggle: F key sends SWAP action
                if (actionType == net.minecraft.screen.slot.SlotActionType.SWAP) {
                    if (com.customblocks.command.PermissionHelper.canFavorite(player.getCommandSource())) {
                        com.customblocks.core.FavoritesManager.toggle(player.getUuid(), targetId, player.getServer());
                        boolean nowFav = com.customblocks.core.FavoritesManager.isFavorite(player.getUuid(), targetId);
                        FeedbackHelper.actionBar(player, nowFav ? "§6★ §eFavorited: §f" + targetId : "§7☆ §7Unfavorited: §f" + targetId);
                        playClick(player);
                        // Refresh the picker to update visual indicators
                        if (brokenOnly) refreshScreen(player, buildPicker(player.getUuid(), page, true));
                        else refreshScreen(player, buildPicker(player.getUuid(), page, false));
                    } else {
                        playError(player);
                        send(player, ChatHelper.formattedKey("cmd.tool_permission_denied"));
                    }
                    return;
                }
                // 1.23 — In the broken-blocks view, Shift+click suppresses the warning
                //         (marks the block as intentionally texture-free so it won't show up here again).
                if (brokenOnly && (actionType == net.minecraft.screen.slot.SlotActionType.QUICK_MOVE
                        || actionType == net.minecraft.screen.slot.SlotActionType.QUICK_CRAFT)) {
                    com.customblocks.core.SlotManager.setSuppressed(targetId, true);
                    playClick(player);
                    send(player, "§7[CB] Warning suppressed for §f" + targetId + "§7. Use §f/cb unsuppress " + targetId + " §7to restore.");
                    refreshScreen(player, buildPicker(player.getUuid(), page, true));
                    return;
                }
                // V4-22: right-click → open bulk delete with this block pre-checked
                if (!brokenOnly && actionType == net.minecraft.screen.slot.SlotActionType.PICKUP && button == 1) {
                    BULK_DELETE_SELECTIONS.computeIfAbsent(player.getUuid(), k -> ConcurrentHashMap.newKeySet()).add(targetId);
                    openBulkDelete(player, 0);
                    return;
                }
                // Category-assign triggers (in priority order):
                //   • Middle-click → CLONE         (creative only)
                //   • Drop key (Q) → THROW
                //   • Shift+click  → QUICK_MOVE/QUICK_CRAFT
                boolean assign = actionType == net.minecraft.screen.slot.SlotActionType.CLONE
                    || actionType == net.minecraft.screen.slot.SlotActionType.THROW;
                if (!brokenOnly && (actionType == net.minecraft.screen.slot.SlotActionType.QUICK_MOVE
                        || actionType == net.minecraft.screen.slot.SlotActionType.QUICK_CRAFT)) assign = true;
                if (assign) {
                    openAssignmentDecision(player, targetId, page);
                } else {
                    Deque<GuiState> _bs = BACK_STACK.get(player.getUuid());
                    if (_bs != null && !_bs.isEmpty() && _bs.peek().mode() == GuiMode.TAB_ICON_MENU) {
                        _bs.pop(); // consume the marker
                        applyTabIconFromBlock(player, targetId);
                    } else {
                        openEditor(player, targetId, page);
                    }
                }
            }
        }
    }

    
    private static void handleMainClick(ServerPlayerEntity player, GuiState state, int slot) {
        UUID uuid = player.getUuid();
        switch (slot) {
            // Row 1: primary actions
            case 10 -> openShortInputPrompt(
                player,
                new PendingInput(InputAction.CREATE_ID, null, null, null, null, state.page()),
                "§6New Block ID",
                new ItemStack(Items.COMMAND_BLOCK),
                "");
            case 12 -> openEditorPicker(player, 0);
            case 14 -> openShortInputPrompt(player,
                new PendingInput(InputAction.REID_TEXT, "__search__", null, null, null, state.page()),
                "§6Search Blocks",
                new ItemStack(Items.SPYGLASS),
                "");
            case 16 -> openMagicItemsGui(player);

            // Row 2: bulk + undo/redo
            case 19 -> openBulkDelete(player, 0);
            case 21 -> openBulkRecolorWizard(player, 0);
            case 23 -> {
                int undoSz = UndoManager.undoSize(uuid);
                if (undoSz == 0) { send(player, "§7Nothing to undo."); refreshScreen(player, buildMain(player, state.page())); return; }
                UndoManager.UndoEntry entry = UndoManager.popUndo(uuid);
                if (entry == null) { refreshScreen(player, buildMain(player, state.page())); return; }
                applyUndoEntry(player, entry);
                String _hUndo = FirstUseHints.hint(uuid, "first_undo");
                if (_hUndo != null) send(player, _hUndo);
                refreshScreen(player, buildMain(player, state.page()));
            }
            case 25 -> {
                int redoSz = UndoManager.redoSize(uuid);
                if (redoSz == 0) { send(player, "§7Nothing to redo."); refreshScreen(player, buildMain(player, state.page())); return; }
                UndoManager.UndoEntry entry = UndoManager.popRedo(uuid);
                if (entry == null) { refreshScreen(player, buildMain(player, state.page())); return; }
                applyRedoEntry(player, entry);
                refreshScreen(player, buildMain(player, state.page()));
            }

            // Row 3: config + tools
            case 28 -> openConfigWarningGui(player);
            case 30 -> openResourceHub(player);
            case 32 -> openHistoryGui(player, 0);
            case 34 -> openHelpGui(player);

            // Row 4: diagnostics + exit
            case 37 -> openBrokenBlocks(player, 0);
            case 39 -> openDeletedBlocksGui(player, 0);
            case 41 -> { playSuccess(player); openAiGui(player); }
            case 43 -> player.closeHandledScreen();
            default -> {}
        }
    }

    private static void applyUndoEntry(ServerPlayerEntity player, UndoManager.UndoEntry entry) {
        com.customblocks.core.AchievementManager.onUndo(player); // R1
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
        // Lock guard: block mutation actions when block is locked (except unlock itself at slot 44)
        if (com.customblocks.core.LockManager.isLocked(id) && slot != 44 && slot != 0 && slot != 2 && slot != 4 && slot != 43 && slot != 45 && slot != 52) {
            playError(player);
            FeedbackHelper.actionBar(player, "§c§l🔒 §r§cLocked — /cb unlock " + id);
            return;
        }
        switch (slot) {
            case 0, 45 -> openEditorPicker(player, rp);
            case 2  -> { var giveItem = CustomBlocksMod.safeSlotItem(d.index); if (giveItem != null) { ItemStack giveStack = new ItemStack(giveItem, 1); if (!player.getInventory().insertStack(giveStack)) { player.dropItem(giveStack, false); send(player,"§e[CB] Inventory full — item dropped at your feet."); } else { send(player,"§a[GUI] Given 1x §f"+d.displayName); } } openEditor(player,id,rp); }
            case 8  -> { PENDING.put(uuid,new PendingInput(InputAction.RETEXTURE_URL,id,null,null,null,rp)); closeForPrompt(player); send(player,"§6[GUI] §ePaste image URL for ALL faces of '§f"+id+"§e' (or §ccancel§e):"); }
            case 11 -> openAiSuggestGui(player, id);  // J2 — Smart Suggest
            case 17 -> { PENDING.put(uuid, new PendingInput(InputAction.WEB_LINK_CAST, id, null, null, null, rp)); closeForPrompt(player); send(player, "§ePaste the §fWeb-Link URL§e to cast onto this block (or §ccancel§e):"); }
            case 19 -> openFaceEditor(player, id, rp);
            case 21 -> openShapeEditor(player, id, rp);
            case 23 -> openPropertiesGui(player, id, rp);
            case 25 -> openSoundMenu(player, id, rp);
            case 27 -> openShortInputPrompt(  // I2 — Hologram Text
                player,
                new PendingInput(InputAction.SET_HOLOGRAM_TEXT, id, null, null, null, rp),
                "§bHologram Text",
                new ItemStack(Items.SOUL_LANTERN),
                d.hasHologramText() ? d.hologramText : ""
            );
            case 29 -> openColorStudio(player, id);  // G1 — Color Studio
            case 31 -> { if (d.isAnimated()) openAnimGui(player, id, rp); }
            case 33 -> openVariantGui(player, id);  // H4 — Variant Randomizer
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
                    net.minecraft.text.MutableText line = Text.literal("§a[Share] §f'§b" + d.customId + "§f' ready! ")
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
                } catch (Exception ex) { send(player, "Share failed: " + ex.getMessage()); }
            }
            case 35 -> {
                if (com.customblocks.command.PermissionHelper.canFavorite(player.getCommandSource())) {
                    com.customblocks.core.FavoritesManager.toggle(uuid, id, player.getServer());
                    boolean nowFav = com.customblocks.core.FavoritesManager.isFavorite(uuid, id);
                    if (nowFav) com.customblocks.core.AchievementManager.onFavoriteAdded(player); // R1
                    playClick(player);
                    FeedbackHelper.actionBar(player, nowFav ? "§6★ §eFavorited: §f" + id : "§7☆ §7Unfavorited: §f" + id);
                    refreshEditorInPlace(player, id, rp);
                } else {
                    playError(player);
                    send(player, ChatHelper.formattedKey("cmd.tool_permission_denied"));
                }
            }
            case 44 -> {
                if (com.customblocks.core.LockManager.isLocked(id)) {
                    com.customblocks.core.LockManager.unlock(id);
                    playClick(player);
                    FeedbackHelper.actionBar(player, "§a§l🔓 §r§aUnlocked: §f" + id);
                } else {
                    com.customblocks.core.LockManager.lock(id);
                    playSuccess(player);
                    FeedbackHelper.actionBar(player, "§c§l🔒 §r§cLocked: §f" + id);
                }
                refreshEditorInPlace(player, id, rp);
            }
            case 52 -> {
                // Cancel deletion — reopen normal editor
                if (state.confirmDelete()) openEditor(player, id, rp);
            }
            case 53 -> {
                if (state.confirmDelete()) {
                    UndoManager.pushUndoDeletion(id, d.deepCopy(), uuid); SlotManager.remove(id); SlotManager.saveAll();
                    NetworkManager.broadcastUpdate(player.getServer(), new SlotUpdatePayload("remove", d.index, id, null, null, 0, 0, "stone"));
                    playSuccess(player);
                    FeedbackHelper.actionBar(player, "§c§l✗ §r§cDeleted: §f" + id);
                    FeedbackHelper.title(player, "§c§l✗ Deleted", "§f" + id);
                    send(player, "§a[GUI] '" + id + "' deleted."); openMain(player, rp);
                } else {
                    STATES.put(uuid, state.withConfirmDelete(true));
                    SlotData dd = SlotManager.getById(id); if (dd == null) return;
                    REOPENING_SCREENS.add(uuid);
                    player.openHandledScreen(new SimpleNamedScreenHandlerFactory((s,pi,p)->new CbScreenHandler(s,pi,buildEditor(dd,true,player.getUuid())), Text.literal("§c§l⚠ Confirm DELETE — §r§f" + dd.displayName)));
                    REOPENING_SCREENS.remove(uuid);
                }
            }
            default -> {}
        }
    }

    private static void refreshEditorInPlace(ServerPlayerEntity player, String id, int rp) {
        SlotData fresh = SlotManager.getById(id);
        if (fresh == null) { openMain(player, rp); return; }
        refreshScreen(player, buildEditor(fresh, false, player.getUuid()));
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
            if (boxIdx < boxes.size()) {
                if (button == 1) {
                    // Right-click → open nudge editor for this box (V4-13)
                    openBoxNudgeEditor(player, id, boxIdx, rp);
                } else {
                    // Left-click → delete the box
                    UndoManager.pushUndoMutation(id, d, "removeshape", uuid); SlotManager.removeBox(id,boxIdx); SlotManager.saveAll(); broadcastShape(player.getServer(),SlotManager.getById(id)); send(player,"§a[Shape] Removed box #"+boxIdx+"."); int np=Math.min(boxPage,Math.max(0,(boxes.size()-2)/9)); reopenShapeEditor(player,id,rp,np);
                }
            }
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
        else if(slot == 25) { playSuccess(player); openAiGui(player); }
        else if(slot == 33) { playClick(player); openResourceHub(player); }
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
            default -> {}
        }
    }

    public static void openHelpCategory(ServerPlayerEntity player, int category) {
        pushBackStack(player.getUuid());
        Text title = switch (category) {
            case 1 -> Text.translatable("customblocks.gui.help_creating.title");
            case 2 -> Text.translatable("customblocks.gui.help_textures.title");
            case 3 -> Text.translatable("customblocks.gui.help_shapes.title");
            case 4 -> Text.translatable("customblocks.gui.help_utilities.title");
            case 5 -> Text.translatable("customblocks.gui.help_server.title");
            default -> Text.translatable("customblocks.gui.help.title");
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
            default -> {}
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
            case 53 -> { var giveItem = CustomBlocksMod.safeSlotItem(d.index); if (giveItem != null) { ItemStack giveStack = new ItemStack(giveItem, 1); if (!player.getInventory().insertStack(giveStack)) { player.dropItem(giveStack, false); send(player,"§e[CB] Inventory full — item dropped at your feet."); } else { send(player,"§a[GUI] Given 1x §f"+d.displayName); } } openFaceEditor(player,id,rp); }
            default -> {}
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
            default -> {}
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
        // 7.9 — atomic compare-and-swap to prevent concurrent cooldown bypass
        Long prev = SHAPE_CREATE_COOLDOWN.get(uuid);
        if (prev != null && now - prev < SHAPE_COOLDOWN_MS) {
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
        SlotData updated = SlotManager.getById(id);
        if (updated != null && player.getServer() != null) broadcastShape(player.getServer(), updated);
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
            buildShapeEditor(d,boxPage), Text.translatable("customblocks.gui.shape_editor.title").append(Text.literal(" §8— §5"+d.displayName)));
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
            Text.translatable("customblocks.gui.anim.title").append(Text.literal(" §8— §b" + d.displayName)));
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
        if (player.getServer() != null) NetworkManager.broadcastUpdate(player.getServer(), pkt);
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
        openScreenFromGuiState(player, GuiState.animConfirmAbandon(id, returnPage), inv, Text.translatable("customblocks.gui.anim_confirm.title"));
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
            default -> {}
        }
    }

    private static void reopenAnimGui(ServerPlayerEntity player, String id, int returnPage) {
        AnimParams p = ANIM_PARAMS.getOrDefault(player.getUuid(), new AnimParams(10f, false, 1));
        SlotData d = SlotManager.getById(id);
        String title = d != null ? d.displayName : id;
        openScreenFromGuiState(player, GuiState.animGui(id, returnPage),
            buildAnimGui(id, p.fps(), p.interpolate(), p.frameCount()),
            Text.translatable("customblocks.gui.anim.title").append(Text.literal(" §8— §b" + title)));
    }

    // ── Bulk Delete GUI ────────────────────────────────────────────────────────

    public static void openBulkDelete(ServerPlayerEntity player, int page) {
        int total = sortedBlocks().size();
        int max = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        pushBackStack(player.getUuid());
        Set<String> selected = BULK_DELETE_SELECTIONS.computeIfAbsent(player.getUuid(), k -> ConcurrentHashMap.newKeySet());
        openScreenFromGuiState(player, GuiState.bulkDelete(page), buildBulkDeleteGui(page, selected), Text.translatable("customblocks.gui.bulk_delete.title"));
    }

    private static SimpleInventory buildBulkDeleteGui(int page, Set<String> selected) {
        SimpleInventory inv = new SimpleInventory(54);
        List<SlotData> blocks = sortedBlocks();
        int total = blocks.size(), maxPage = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        inv.setStack(0, uiGlint(Items.ECHO_SHARD, "§c◀ Cancel", "§8Abort bulk delete — no changes"));
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
            int threshold = com.customblocks.CustomBlocksConfig.bulkConfirmThreshold;
            if (selected.size() > threshold) {
                Long armedAt = BULK_DELETE_CONFIRM_ARMED.get(uuid);
                boolean confirmed = armedAt != null && (System.currentTimeMillis() - armedAt) < 5000L;
                if (!confirmed) {
                    BULK_DELETE_CONFIRM_ARMED.put(uuid, System.currentTimeMillis());
                    playError(player);
                    FeedbackHelper.actionBar(player, "§c§l⚠ §r§c" + selected.size() + " blocks! Click again within 5s to confirm.");
                    send(player, "§c[GUI] §f" + selected.size() + " §cblocks selected — §lclick Delete again within 5 seconds to confirm.");
                    return;
                }
            }
            BULK_DELETE_CONFIRM_ARMED.remove(uuid);
            MinecraftServer server = player.getServer();
            int count = 0;
            java.util.List<String> selectedList = new ArrayList<>(selected);
            boolean useBossBar = selectedList.size() >= 2;
            if (useBossBar) FeedbackHelper.startBossBar(player, "§cBulk deleting...");
            try {
            for (int i = 0; i < selectedList.size(); i++) {
                String id = selectedList.get(i);
                if (useBossBar) FeedbackHelper.updateBossBar(player, "§cDeleting " + (i + 1) + " / " + selectedList.size(), (i + 1) / (float) selectedList.size());
                SlotData d = SlotManager.getById(id);
                if (d != null) {
                    UndoManager.pushUndoDeletion(id, d.deepCopy(), uuid);
                    SlotManager.remove(id);
                    NetworkManager.broadcastUpdate(server, new SlotUpdatePayload("remove", d.index, id, null, null, 0, 0, "stone"));
                    count++;
                }
            }
            } finally { if (useBossBar) FeedbackHelper.clearBossBar(player); }
            if (count > 0) SlotManager.saveAll();
            send(player, "§a[GUI] Bulk deleted §f" + count + "§a block(s). Use Undo to restore.");
            FeedbackHelper.actionBar(player, "§c§l✗ §r§cBulk deleted §f" + count + " §cblocks");
            FeedbackHelper.title(player, "§c§l✗ Bulk Deleted", "§f" + count + " §cblock(s) removed");
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
        inv.setStack(4, uiGlint(Items.BRUSH, "§d§l🎨 Magic Items & Tools", "§7Click items to get them."));
        inv.setStack(20, uiGlint(Items.BLAZE_ROD, "§6Rainbow Rectangle Wand", "§7Paints blocks with rainbow colors."));
        inv.setStack(21, uiGlint(Items.WHITE_CONCRETE, "§fColor Square Wand", "§7Paints a solid-color area."));
        inv.setStack(22, uiGlint(Items.WHITE_CARPET, "§fColor Triangle Wand", "§7Paints a solid-color triangle."));
        inv.setStack(24, uiGlint(Items.PAINTING, "§eSet Tab Icon", "§7Opens the tab icon picker."));
        
        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back to Main Menu"));
        return inv;
    }

    private static SimpleInventory buildMain(ServerPlayerEntity player, int page) {
        // V4-04 clean grid layout:
        //   Row 0 (slot 4)         : Title header
        //   Row 1 (10,12,14,16)    : Create, Edit Blocks, Search, Magic Items
        //   Row 2 (19,21,23,25)    : Bulk Delete, Bulk Recolor, Undo, Redo
        //   Row 3 (28,30,32,34)    : Config, Resource Pack, History, Help
        //   Row 4 (37,39,41,43)    : Broken Blocks, Deleted Blocks, AI, Exit
        //   Row 5 (45-53)          : Glass padding
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

        // Row 1: primary actions
        inv.setStack(10, uiGlint(Items.EMERALD, "§a§l+ Create New Block",
            "§7Create a new custom block", "§8Type an ID in chat"));
        inv.setStack(12, uiGlint(Items.CRAFTING_TABLE, "§e§lEdit Blocks",
            "§7Browse and edit all blocks", "§8" + blockCount + " block(s) registered"));
        inv.setStack(14, uiGlint(Items.SPYGLASS, "§f§lSearch Blocks",
            "§7Find a block by name or ID", "§8Type a query in chat"));
        inv.setStack(16, uiGlint(Items.BRUSH, "§d§lMagic Items",
            "§7Wands, color squares, triangles"));

        // Row 2: bulk + undo
        inv.setStack(19, uiGlint(Items.LAVA_BUCKET, "§c§l⚠ Bulk Delete",
            "§7Select and delete multiple blocks"));
        inv.setStack(21, uiGlint(Items.COMPARATOR, "§6§lBulk Recolor",
            "§7Recolor many blocks at once"));
        inv.setStack(23, undoSz > 0
            ? uiGlint(Items.GOLDEN_PICKAXE, "§6§l↩ Undo §e(" + undoSz + ")", "§7Click to undo last action")
            : ui(Items.GRAY_STAINED_GLASS_PANE, "§8Undo (Empty)"));
        inv.setStack(25, redoSz > 0
            ? uiGlint(Items.DIAMOND_PICKAXE, "§b§l↪ Redo §3(" + redoSz + ")", "§7Click to redo last undone action")
            : ui(Items.GRAY_STAINED_GLASS_PANE, "§8Redo (Empty)"));

        // Row 3: config + tools + history + help
        inv.setStack(28, uiGlint(Items.COMPARATOR, "§6§l⚙ Config",
            "§7View and edit server-wide settings"));
        inv.setStack(30, uiGlint(Items.BEACON, "§b§lResource Pack",
            "§7Manage texture pack delivery & sync"));
        inv.setStack(32, (undoSz + redoSz) > 0
            ? uiGlint(Items.KNOWLEDGE_BOOK, "§6§lHistory §7(" + (undoSz + redoSz) + ")",
                "§7Browse undo/redo entries", "§8Click to open picker")
            : ui(Items.KNOWLEDGE_BOOK, "§8History (Empty)"));
        inv.setStack(34, uiGlint(Items.BOOK, "§a§lHelp & Info", "§7Interactive help guide"));

        // Row 4: diagnostics + coming-soon + exit
        inv.setStack(37, brokenCount > 0
            ? uiGlint(Items.DAMAGED_ANVIL, "§c§l⚠ Broken Blocks §c(" + brokenCount + ")",
                "§7View and repair blocks with missing textures")
            : ui(Items.DAMAGED_ANVIL, "§7Broken Blocks §8(None)"));
        int trashSz = com.customblocks.core.TrashManager.size();
        inv.setStack(39, trashSz > 0
            ? uiGlint(Items.ENDER_CHEST, "§5§l🗑 Deleted Blocks §5(" + trashSz + ")",
                "§7Browse and restore recently deleted blocks",
                "§8Left-click to open trash bin")
            : ui(Items.ENDER_CHEST, "§5§l🗑 Deleted Blocks §8(Empty)",
                "§7Deleted blocks will appear here",
                "§8Trash bin is currently empty"));
        inv.setStack(41, uiGlint(Items.PLAYER_HEAD, "§e§lAI Assistant",
            "§7Manage and run AI block tasks",
            "§8Coming soon"));
        inv.setStack(43, ui(Items.BARRIER, "§c§lClose", "§8ESC or click to close"));

        return inv;
    }

    private static SimpleInventory buildMaintenanceMenu(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(54);
        for(int i = 0; i < 54; i++) inv.setStack(i, glass());
        inv.setStack(0, uiGlint(Items.ECHO_SHARD, "§c◀ Back to Main Menu", "§8Return to the dashboard"));

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

        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back to Main Menu"));
        return inv;
    }

    /** K2 — stats dashboard: top 9 placed blocks + grand total + player-specific top 3. */
    private static SimpleInventory buildStatsGui(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        long grand = com.customblocks.core.PlacementStats.grandTotal();
        int blockCount = SlotManager.usedSlots();
        inv.setStack(4, uiGlint(Items.AMETHYST_SHARD, "§d§lUsage Statistics",
            "§7Total blocks registered: §f" + blockCount,
            "§7Total placements logged: §f" + grand,
            "§8Shows blocks placed since stats were enabled"));

        // Top 9 most-placed blocks — slots 19..35 (row 2-3)
        java.util.List<java.util.Map.Entry<String, Long>> topBlocks =
            com.customblocks.core.PlacementStats.getTopBlocks(9);
        int[] displaySlots = {19, 20, 21, 22, 23, 24, 25, 28, 29};
        for (int i = 0; i < Math.min(topBlocks.size(), displaySlots.length); i++) {
            var entry = topBlocks.get(i);
            SlotData d = SlotManager.getById(entry.getKey());
            if (d == null) continue;
            String medal = i == 0 ? "§6🥇 " : i == 1 ? "§7🥈 " : i == 2 ? "§c🥉 " : "§8#" + (i + 1) + " ";
            inv.setStack(displaySlots[i], uiGlint(Items.AMETHYST_CLUSTER,
                medal + "§f" + d.displayName,
                "§7ID: §8" + d.customId,
                "§7Total placed: §f" + entry.getValue() + "x"));
        }
        if (topBlocks.isEmpty()) {
            inv.setStack(22, uiGlint(Items.GRAY_STAINED_GLASS_PANE, "§7No placements recorded yet",
                "§8Place custom blocks to see stats here"));
        }

        // Player's personal top 3 — slots 37, 39, 41
        java.util.List<java.util.Map.Entry<String, Long>> myTop =
            com.customblocks.core.PlacementStats.getPlayerTop(player.getUuid(), 3);
        inv.setStack(36, uiGlint(Items.PLAYER_HEAD, "§b§lYour Top Blocks",
            "§7Your personal placement stats"));
        int[] mySlots = {37, 39, 41};
        for (int i = 0; i < Math.min(myTop.size(), mySlots.length); i++) {
            var entry = myTop.get(i);
            SlotData d = SlotManager.getById(entry.getKey());
            if (d == null) continue;
            inv.setStack(mySlots[i], uiGlint(Items.ECHO_SHARD,
                "§b#" + (i + 1) + " §f" + d.displayName,
                "§7You placed it §f" + entry.getValue() + "x"));
        }
        if (myTop.isEmpty()) {
            inv.setStack(39, uiGlint(Items.GRAY_STAINED_GLASS_PANE, "§7You haven't placed any blocks yet"));
        }

        inv.setStack(49, uiGlint(Items.ECHO_SHARD, "§c◀ Back"));
        return inv;
    }

    private static void handleStatsGuiClick(ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 49) handleEscBack(player);
    }

    // ── H4: Variant Texture Manager ───────────────────────────────────────────

    /**
     * H4 — open the Variant Texture GUI for a block.
     * Shows up to 7 variant slots (v0–v6) plus a "Clear all" and back button.
     */
    public static void openVariantGui(ServerPlayerEntity player, String customId) {
        com.customblocks.core.SlotData d = com.customblocks.core.SlotManager.getById(customId);
        if (d == null) { handleEscBack(player); return; }
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.variantGui(customId),
                buildVariantGui(player, customId),
                Text.translatable("customblocks.gui.variant.title").append(Text.literal(": §f" + d.displayName)));
    }

    private static SimpleInventory buildVariantGui(ServerPlayerEntity player, String customId) {
        com.customblocks.core.SlotData d = com.customblocks.core.SlotManager.getById(customId);
        SimpleInventory inv = new SimpleInventory(54);
        // Fill all slots with gray glass first
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        // Overlay border with purple glass
        ItemStack purpleGlass = ui(Items.PURPLE_STAINED_GLASS_PANE, "§r");
        for (int i = 0; i < 9; i++) inv.setStack(i, purpleGlass.copy());
        for (int i = 45; i < 54; i++) inv.setStack(i, purpleGlass.copy());
        for (int r = 1; r <= 4; r++) { inv.setStack(r * 9, purpleGlass.copy()); inv.setStack(r * 9 + 8, purpleGlass.copy()); }

        // Header — slot 4
        net.minecraft.item.ItemStack header = new net.minecraft.item.ItemStack(Items.NETHER_STAR);
        header.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                net.minecraft.text.Text.literal("§5§l🎲 Variant Randomizer")
                        .styled(s -> s.withItalic(false)));
        List<net.minecraft.text.Text> hLore = new ArrayList<>();
        hLore.add(net.minecraft.text.Text.literal("§7Each variant is chosen randomly")
                .styled(s -> s.withItalic(false)));
        hLore.add(net.minecraft.text.Text.literal("§7when a block is placed in the world.")
                .styled(s -> s.withItalic(false)));
        hLore.add(net.minecraft.text.Text.literal("§7Max 7 extra variants (8 total with main).")
                .styled(s -> s.withItalic(false)));
        int count = d != null ? d.variantCount() : 1;
        hLore.add(net.minecraft.text.Text.literal("§6§lTotal variants: §f" + count)
                .styled(s -> s.withItalic(false)));
        header.set(net.minecraft.component.DataComponentTypes.LORE,
                new net.minecraft.component.type.LoreComponent(hLore));
        inv.setStack(4, header);

        // Main texture preview — slot 10 (always present)
        if (d != null && d.texture != null) {
            net.minecraft.item.ItemStack mainSlot = displayStackFor(d);
            mainSlot.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                    net.minecraft.text.Text.literal("§f§lMain Texture (slot 0)")
                            .styled(s -> s.withItalic(false)));
            List<net.minecraft.text.Text> mLore = new ArrayList<>();
            mLore.add(net.minecraft.text.Text.literal("§7This is always shown (base variant).")
                    .styled(s -> s.withItalic(false)));
            mainSlot.set(net.minecraft.component.DataComponentTypes.LORE,
                    new net.minecraft.component.type.LoreComponent(mLore));
            inv.setStack(10, mainSlot);
        }

        // Variant slots v0–v6 → display slots 19-25
        List<byte[]> variants = d != null ? d.variantTextures : List.of();
        for (int vi = 0; vi < 7; vi++) {
            int displaySlot = 19 + vi;
            if (vi < variants.size()) {
                // Existing variant
                net.minecraft.item.ItemStack vs = new net.minecraft.item.ItemStack(Items.AMETHYST_SHARD);
                vs.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                        net.minecraft.text.Text.literal("§d§lVariant " + (vi + 1))
                                .styled(s -> s.withItalic(false)));
                List<net.minecraft.text.Text> vsLore = new ArrayList<>();
                vsLore.add(net.minecraft.text.Text.literal("§7Click to §cremove §7this variant.")
                        .styled(s -> s.withItalic(false)));
                vsLore.add(net.minecraft.text.Text.literal("§8Use /cb variant " + customId + " set " + vi)
                        .styled(s -> s.withItalic(false)));
                vs.set(net.minecraft.component.DataComponentTypes.LORE,
                        new net.minecraft.component.type.LoreComponent(vsLore));
                inv.setStack(displaySlot, vs);
            } else {
                // Empty slot — invite to add
                net.minecraft.item.ItemStack empty = new net.minecraft.item.ItemStack(Items.GRAY_STAINED_GLASS_PANE);
                empty.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                        net.minecraft.text.Text.literal("§8[ Add Variant " + (vi + 1) + " ]")
                                .styled(s -> s.withItalic(false)));
                List<net.minecraft.text.Text> eLore = new ArrayList<>();
                eLore.add(net.minecraft.text.Text.literal("§7Run: §f/cb variant " + customId + " add")
                        .styled(s -> s.withItalic(false)));
                eLore.add(net.minecraft.text.Text.literal("§7then upload your texture.")
                        .styled(s -> s.withItalic(false)));
                empty.set(net.minecraft.component.DataComponentTypes.LORE,
                        new net.minecraft.component.type.LoreComponent(eLore));
                inv.setStack(displaySlot, empty);
            }
        }

        // Slot 37 — "Clear all variants"
        if (d != null && d.hasVariants()) {
            net.minecraft.item.ItemStack clearAll = new net.minecraft.item.ItemStack(Items.BARRIER);
            clearAll.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                    net.minecraft.text.Text.literal("§c§lClear All Variants")
                            .styled(s -> s.withItalic(false)));
            List<net.minecraft.text.Text> cLore = new ArrayList<>();
            cLore.add(net.minecraft.text.Text.literal("§7Removes all variant textures.")
                    .styled(s -> s.withItalic(false)));
            cLore.add(net.minecraft.text.Text.literal("§cThis cannot be undone!")
                    .styled(s -> s.withItalic(false)));
            clearAll.set(net.minecraft.component.DataComponentTypes.LORE,
                    new net.minecraft.component.type.LoreComponent(cLore));
            inv.setStack(37, clearAll);
        }

        // Back — slot 49
        inv.setStack(49, uiGlint(Items.ECHO_SHARD, "§c◀ Back"));
        return inv;
    }

    private static void handleVariantGuiClick(ServerPlayerEntity player, GuiState state, int slot) {
        playClick(player);
        String customId = state.editingId();
        com.customblocks.core.SlotData d = com.customblocks.core.SlotManager.getById(customId);
        if (d == null) { handleEscBack(player); return; }

        // Slots 19-25: click on existing variant → remove it
        if (slot >= 19 && slot <= 25) {
            int vi = slot - 19;
            if (vi < d.variantTextures.size()) {
                List<byte[]> newVariants = new ArrayList<>(d.variantTextures);
                newVariants.remove(vi);
                com.customblocks.core.SlotManager.setVariantTextures(customId, newVariants);
                com.customblocks.core.SlotManager.saveAll();
                FeedbackHelper.actionBar(player, "§c§lVariant " + (vi + 1) + " removed.");
                playSuccess(player);
                openVariantGui(player, customId);
                return;
            }
        }

        // Slot 37: clear all variants
        if (slot == 37 && d.hasVariants()) {
            com.customblocks.core.SlotManager.setVariantTextures(customId, List.of());
            com.customblocks.core.SlotManager.saveAll();
            FeedbackHelper.actionBar(player, "§c§lAll variants cleared.");
            playSuccess(player);
            openVariantGui(player, customId);
            return;
        }

        if (slot == 49) handleEscBack(player);
    }

    // ── Phase 3.1: Color Library Picker ──────────────────────────────────────

    /** Returns a §x-prefixed MC hex color code string for use in item names. */
    private static String mcHexColor(int rgb) {
        String h = String.format("%06X", rgb);
        return "§x§" + h.charAt(0) + "§" + h.charAt(1) + "§" + h.charAt(2)
             + "§" + h.charAt(3) + "§" + h.charAt(4) + "§" + h.charAt(5);
    }

    private static ItemStack colorSwatchItem(ColorLibrary.LibColor color, String contextDesc) {
        String nameColor = mcHexColor(color.rgb());
        String loreLine = contextDesc != null
            ? "§aLeft-click §7to apply to §f" + contextDesc
            : "§7Browse only — no block selected";
        return named(Items.PAPER,
            nameColor + "§l■ §r§f" + color.name(),
            color.rgbLabel(),
            "§7Hex: §f" + color.hex(),
            loreLine);
    }

    public static void openColorPicker(ServerPlayerEntity player, String blockId) {
        openScreenFromGuiState(player, GuiState.colorPicker(blockId),
            buildColorPickerGui(player, blockId), Text.literal("§9Color Library"));
    }

    private static SimpleInventory buildColorPickerGui(ServerPlayerEntity player, String blockId) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        SlotData target = blockId != null ? SlotManager.getById(blockId) : null;
        String contextDesc = target != null ? target.displayName : null;

        List<ColorLibrary.LibColor> colors = ColorLibrary.ALL;
        for (int i = 0; i < colors.size() && i < 45; i++) {
            inv.setStack(i, colorSwatchItem(colors.get(i), contextDesc));
        }

        inv.setStack(46, named(Items.AMETHYST_SHARD, "§5Open Color Studio", "§7Advanced HSB / hex editor"));
        inv.setStack(49, named(Items.ECHO_SHARD, "§c◀ Back"));
        return inv;
    }

    private static void handleColorPickerClick(ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 49) { popBackStack(player); return; }
        if (slot == 46) { openColorStudio(player, state.editingId()); return; }

        List<ColorLibrary.LibColor> colors = ColorLibrary.ALL;
        if (slot < 0 || slot >= colors.size()) return;

        ColorLibrary.LibColor color = colors.get(slot);
        String blockId = state.editingId();
        if (blockId == null) {
            // Browse-only mode — open Color Studio for this color
            openColorStudio(player, null);
            return;
        }

        SlotData d = SlotManager.getById(blockId);
        if (d == null) { playError(player); openMain(player, 0); return; }
        byte[] tex = d.texture;
        if (tex == null || tex.length == 0) {
            send(player, "§c[CB] That block has no texture to recolor.");
            playError(player);
            return;
        }

        // Recolor the block's texture in-place (background color replacement)
        MinecraftServer server = player.getServer();
        UUID uuid = player.getUuid();
        int fR = color.rgb() >> 16 & 0xFF, fG = color.rgb() >> 8 & 0xFF, fB = color.rgb() & 0xFF;
        Thread t = new Thread(() -> {
            try {
                byte[] newTex = com.customblocks.item.ColorTriangleItem.recolourTextureForPlayer(
                    tex, fR, fG, fB, CustomBlocksConfig.useTrappedHoleFill(), uuid);
                if (server != null) server.execute(() -> {
                    UndoManager.pushUndoMutation(blockId, d, "recolor:" + color.name(), uuid);
                    SlotManager.updateTexture(blockId, newTex);
                    SlotManager.saveAll();
                    NetworkManager.broadcastUpdate(server,
                        new SlotUpdatePayload("retexture", d.index, blockId, null, newTex, d.lightLevel, d.hardness, d.soundType));
                    playSuccess(player);
                    send(player, "§a[CB] Applied §f" + color.name() + "§a to §f" + d.displayName + "§a.");
                    openEditor(player, blockId, 0);
                });
            } catch (Exception e) {
                if (server != null) server.execute(() -> {
                    send(player, "§c[CB] Recolor failed: " + e.getMessage());
                    playError(player);
                });
            }
        }, "CB-ColorPicker-Recolor");
        t.setDaemon(true);
        t.start();
        player.closeHandledScreen();
        send(player, "§7[CB] Applying §f" + color.name() + "§7...");
    }

    // ── Phase 3.5: Recolor Confirm GUI (Color Triangle shift+right-click) ────

    public static void openRecolorConfirmGui(ServerPlayerEntity player, RecolorJob job) {
        PENDING_RECOLORS.put(player.getUuid(), job);
        openScreenFromGuiState(player, new GuiState(GuiMode.RECOLOR_CONFIRM, job.sourceId(), 0, false, 0, false),
            buildRecolorConfirmGui(player, job), Text.literal("§6Confirm Recolor"));
    }

    private static SimpleInventory buildRecolorConfirmGui(ServerPlayerEntity player, RecolorJob job) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        SlotData src = SlotManager.getById(job.sourceId());
        String srcName = src != null ? src.displayName : job.sourceId();

        String colorHex = String.format("#%02X%02X%02X", job.r(), job.g(), job.b());
        String nameColor = mcHexColor((job.r() << 16) | (job.g() << 8) | job.b());

        // Center: color preview
        inv.setStack(22, named(Items.PAPER,
            nameColor + "§l■ §r§f" + colorHex,
            "§7New color: §f" + colorHex,
            "§8RGB: §7" + job.r() + ", " + job.g() + ", " + job.b()));

        // Block info
        inv.setStack(13, named(Items.NETHER_STAR,
            "§f" + srcName,
            "§7This block will get a new color variant:",
            "§f" + job.newName()));

        // Apply
        inv.setStack(30, uiGlint(Items.EMERALD,
            "§a§l✔ Apply Recolor",
            "§7Creates new block: §f" + job.newName(),
            "§8Source kept unchanged."));

        // Cancel
        inv.setStack(32, named(Items.BARRIER,
            "§c§l✘ Cancel",
            "§7No changes will be made."));

        return inv;
    }

    private static void handleRecolorConfirmClick(ServerPlayerEntity player, GuiState state, int slot) {
        RecolorJob job = PENDING_RECOLORS.remove(player.getUuid());
        if (slot == 30 && job != null) {
            SlotData src = SlotManager.getById(job.sourceId());
            if (src == null) { send(player, "§c[CB] Source block no longer exists."); openMain(player, 0); return; }
            byte[] tex = src.texture;
            if (tex == null || tex.length == 0) { send(player, "§c[CB] Source block has no texture."); openMain(player, 0); return; }
            if (SlotManager.freeSlots() == 0) { send(player, "§c[CB] No free slots available."); openMain(player, 0); return; }

            MinecraftServer server = player.getServer();
            UUID uuid = player.getUuid();
            int fR = job.r(), fG = job.g(), fB = job.b();
            String newId = job.newId(), newName = job.newName();
            Thread t = new Thread(() -> {
                try {
                    byte[] newTex = com.customblocks.item.ColorTriangleItem.recolourTextureForPlayer(
                        tex, fR, fG, fB, CustomBlocksConfig.useTrappedHoleFill(), uuid);
                    if (server != null) server.execute(() -> {
                        if (SlotManager.freeSlots() == 0) { send(player, "§c[CB] No free slots."); return; }
                        SlotData newD = SlotManager.assign(newId, newName, newTex);
                        if (newD == null) { send(player, "§c[CB] Could not allocate slot."); return; }
                        SlotManager.setLightLevel(newId, src.lightLevel);
                        SlotManager.setHardness(newId, src.hardness);
                        SlotManager.setSoundType(newId, src.soundType);
                        UndoManager.pushUndoCreate(newId, uuid);
                        SlotManager.saveAll();
                        NetworkManager.broadcastUpdate(server,
                            new SlotUpdatePayload("add", newD.index, newId, newName, newTex, src.lightLevel, src.hardness, src.soundType));
                        net.minecraft.item.Item item = com.customblocks.CustomBlocksMod.safeSlotItem(newD.index);
                        if (item != null) { ItemStack giveStack = new ItemStack(item, 1); if (!player.getInventory().insertStack(giveStack)) player.dropStack(giveStack); }
                        playSuccess(player);
                        send(player, "§a[CB] Created §f" + newName + "§a.");
                        openEditor(player, newId, 0);
                    });
                } catch (Exception e) {
                    if (server != null) server.execute(() -> {
                        send(player, "§c[CB] Recolor failed: " + e.getMessage());
                        playError(player);
                        openMain(player, 0);
                    });
                }
            }, "CB-Recolour-Confirm");
            t.setDaemon(true);
            t.start();
            send(player, "§7[CB] Processing recolor...");
            player.closeHandledScreen();
        } else {
            // Cancel or any other slot
            openMain(player, 0);
        }
    }

    // ── G1: Color Studio ─────────────────────────────────────────────────────

    public static void openColorStudio(ServerPlayerEntity player, String customId) {
        com.customblocks.core.SlotData d = com.customblocks.core.SlotManager.getById(customId);
        if (d == null) { handleEscBack(player); return; }
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.colorStudio(customId),
                buildColorStudio(customId),
                Text.translatable("customblocks.gui.color_studio.title").append(Text.literal(" §8— " + d.displayName)));
    }

    private static SimpleInventory buildColorStudio(String customId) {
        com.customblocks.core.SlotData d = com.customblocks.core.SlotManager.getById(customId);
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        // Header
        net.minecraft.item.ItemStack hdr = uiGlint(Items.BLAZE_POWDER, "§6§l🎨 Color Studio",
                "§7Click a filter below to apply it instantly.",
                "§7Filters are §nnon-destructive§r§7 — you can retexture to undo.",
                d != null ? "§8Editing: §f" + d.displayName : "");
        inv.setStack(4, hdr);

        // ── Tint row (slots 10–16) ──────────────────────────────────────────
        inv.setStack(9,  ui(Items.ORANGE_STAINED_GLASS_PANE, "§6── Tint Colors ──", "§7Multiply RGB channels to shift color"));
        inv.setStack(10, uiGlint(Items.RED_CONCRETE,    "§c§lRed Tint",    "§7Boosts red, dims green & blue"));
        inv.setStack(11, uiGlint(Items.ORANGE_CONCRETE, "§6§lOrange Tint", "§7Warm sunset glow"));
        inv.setStack(12, uiGlint(Items.YELLOW_CONCRETE, "§e§lYellow Tint", "§7Golden sun filter"));
        inv.setStack(13, uiGlint(Items.GREEN_CONCRETE,  "§a§lGreen Tint",  "§7Nature / moss look"));
        inv.setStack(14, uiGlint(Items.BLUE_CONCRETE,   "§9§lBlue Tint",   "§7Cold ice / ocean filter"));
        inv.setStack(15, uiGlint(Items.PURPLE_CONCRETE, "§5§lPurple Tint", "§7Mystic / ender look"));
        inv.setStack(16, uiGlint(Items.PINK_CONCRETE,   "§d§lPink Tint",   "§7Cherry blossom filter"));

        // ── Brightness row (slots 19–24) ────────────────────────────────────
        inv.setStack(18, ui(Items.YELLOW_STAINED_GLASS_PANE, "§e── Brightness & Contrast ──", "§7Shift luminance of the texture"));
        inv.setStack(19, uiGlint(Items.GLOWSTONE,       "§e§lBrightness §f+50", "§7Make it glow!"));
        inv.setStack(20, uiGlint(Items.GLOWSTONE_DUST,  "§e§lBrightness §f+25", "§7Slight lift"));
        inv.setStack(21, uiGlint(Items.COAL,            "§7§lBrightness §f-25", "§7Slightly darker"));
        inv.setStack(22, uiGlint(Items.BLACK_DYE,       "§8§lBrightness §f-50", "§8Deep shadow"));
        inv.setStack(23, uiGlint(Items.COMPARATOR,      "§b§lContrast §f+",     "§7More punch"));
        inv.setStack(24, uiGlint(Items.REPEATER,        "§b§lContrast §f-",     "§7Softer, pastel look"));

        // ── Filters row (slots 28–31) ───────────────────────────────────────
        inv.setStack(27, ui(Items.CYAN_STAINED_GLASS_PANE, "§b── Image Filters ──", "§7Transform the texture"));
        inv.setStack(28, uiGlint(Items.GRAY_DYE,         "§7§lGrayscale",      "§7Remove all color (luma-preserving)"));
        inv.setStack(29, uiGlint(Items.AMETHYST_SHARD,   "§5§lInvert Colors",  "§7Flip all RGB values (photonegative)"));
        inv.setStack(30, uiGlint(Items.GLASS,            "§f§lMirror",         "§7Flip left-to-right"));
        inv.setStack(31, uiGlint(Items.COMPASS,          "§a§lRotate 90°",     "§7Rotate clockwise"));

        // ── Palette Generator (slot 37) ─────────────────────────────────────
        inv.setStack(37, uiGlint(Items.ECHO_SHARD, "§d§l🌈 Palette Generator",
                "§7Generate 16 color variants from this block.",
                "§7Apply any as the new texture, or add as variants.",
                "§aClick to open Palette Generator."));

        // Back
        inv.setStack(49, uiGlint(Items.ECHO_SHARD, "§c◀ Back"));
        return inv;
    }

    private static void handleColorStudioClick(ServerPlayerEntity player, GuiState state, int slot) {
        playClick(player);
        String customId = state.editingId();
        com.customblocks.core.SlotData d = com.customblocks.core.SlotManager.getById(customId);
        if (d == null || d.texture == null || d.texture.length == 0) {
            FeedbackHelper.actionBar(player, "§cNo texture on this block to filter.");
            playError(player);
            return;
        }

        byte[] newTex = null;
        String filterName = null;
        // Navigation shortcuts handled before the filter switch
        if (slot == 37) { openPaletteGenerator(player, customId); return; }
        if (slot == 49) { handleEscBack(player); return; }

        try {
            newTex = switch (slot) {
                case 10 -> { filterName = "Red Tint";       yield com.customblocks.ImageProcessor.applyTint(d.texture, 1.8f, 0.45f, 0.45f); }
                case 11 -> { filterName = "Orange Tint";    yield com.customblocks.ImageProcessor.applyTint(d.texture, 1.8f, 0.90f, 0.30f); }
                case 12 -> { filterName = "Yellow Tint";    yield com.customblocks.ImageProcessor.applyTint(d.texture, 1.6f, 1.55f, 0.35f); }
                case 13 -> { filterName = "Green Tint";     yield com.customblocks.ImageProcessor.applyTint(d.texture, 0.45f, 1.8f, 0.45f); }
                case 14 -> { filterName = "Blue Tint";      yield com.customblocks.ImageProcessor.applyTint(d.texture, 0.35f, 0.65f, 1.8f); }
                case 15 -> { filterName = "Purple Tint";    yield com.customblocks.ImageProcessor.applyTint(d.texture, 1.2f, 0.35f, 1.8f); }
                case 16 -> { filterName = "Pink Tint";      yield com.customblocks.ImageProcessor.applyTint(d.texture, 1.8f, 0.65f, 1.4f); }
                case 19 -> { filterName = "Brightness +50"; yield com.customblocks.ImageProcessor.applyBrightness(d.texture,  50, 1.0f); }
                case 20 -> { filterName = "Brightness +25"; yield com.customblocks.ImageProcessor.applyBrightness(d.texture,  25, 1.0f); }
                case 21 -> { filterName = "Brightness -25"; yield com.customblocks.ImageProcessor.applyBrightness(d.texture, -25, 1.0f); }
                case 22 -> { filterName = "Brightness -50"; yield com.customblocks.ImageProcessor.applyBrightness(d.texture, -50, 1.0f); }
                case 23 -> { filterName = "Contrast +";     yield com.customblocks.ImageProcessor.applyBrightness(d.texture,   0, 1.5f); }
                case 24 -> { filterName = "Contrast -";     yield com.customblocks.ImageProcessor.applyBrightness(d.texture,   0, 0.6f); }
                case 28 -> { filterName = "Grayscale";      yield com.customblocks.ImageProcessor.applyGrayscale(d.texture); }
                case 29 -> { filterName = "Invert";         yield com.customblocks.ImageProcessor.applyInvert(d.texture); }
                case 30 -> { filterName = "Mirror";         yield com.customblocks.ImageProcessor.applyMirrorH(d.texture); }
                case 31 -> { filterName = "Rotate 90°";     yield com.customblocks.ImageProcessor.applyRotate90(d.texture); }
                default -> null;
            };
        } catch (Exception e) {
            LOGGER.error("[CustomBlocks] Color Studio filter failed: {}", e.getMessage());
            FeedbackHelper.actionBar(player, "§cFilter failed: " + e.getMessage());
            playError(player);
            return;
        }

        if (newTex == null) return;

        // Apply and broadcast
        final byte[] finalTex = newTex;
        final String finalFilter = filterName;
        com.customblocks.core.SlotManager.updateTexture(customId, finalTex);
        com.customblocks.core.SlotManager.saveAll();
        com.customblocks.core.SlotData updated = com.customblocks.core.SlotManager.getById(customId);
        if (updated != null) {
            com.customblocks.network.NetworkManager.broadcastUpdate(player.getServer(),
                    new com.customblocks.network.SlotUpdatePayload("retexture",
                            updated.index, updated.customId, updated.displayName,
                            finalTex, updated.lightLevel, updated.hardness, updated.soundType,
                            null, null, updated.animMeta));
        }
        FeedbackHelper.actionBar(player, "§6§l🎨 §r§f" + finalFilter + " §aapplied!");
        playSuccess(player);
        openColorStudio(player, customId);
    }

    // ── G2: Palette Generator ─────────────────────────────────────────────────

    public static void openPaletteGenerator(ServerPlayerEntity player, String customId) {
        com.customblocks.core.SlotData d = com.customblocks.core.SlotManager.getById(customId);
        if (d == null) { handleEscBack(player); return; }
        // Generate palette async to avoid blocking the server thread
        SimpleInventory loading = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) loading.setStack(i, glass());
        loading.setStack(4, uiGlint(Items.ECHO_SHARD, "§d§lGenerating palette…", "§7Please wait a moment…"));
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.paletteGenerator(customId), loading,
                Text.translatable("customblocks.gui.palette_generator.title").append(Text.literal(" §8— " + d.displayName)));

        // Generate on a daemon thread, then refresh on main thread
        Thread gen = new Thread(() -> {
            try {
                List<byte[]> palette = com.customblocks.ImageProcessor.generateTintPalette(d.texture, 0.7f);
                PALETTE_CACHE.put(customId, palette);
                // Re-open GUI on server thread
                player.getServer().execute(() -> {
                    com.customblocks.core.SlotData fresh = com.customblocks.core.SlotManager.getById(customId);
                    if (fresh == null) return;
                    refreshScreen(player, buildPaletteGui(customId, palette));
                });
            } catch (Exception e) {
                LOGGER.error("[CustomBlocks] Palette generation failed: {}", e.getMessage());
                player.getServer().execute(() ->
                        FeedbackHelper.actionBar(player, "§cPalette generation failed: " + e.getMessage()));
            }
        }, "CB-PaletteGen");
        gen.setDaemon(true);
        gen.start();
    }

    /** Short-lived palette cache — avoids re-generating on every GUI refresh. */
    private static final java.util.concurrent.ConcurrentHashMap<String, List<byte[]>> PALETTE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static SimpleInventory buildPaletteGui(String customId, List<byte[]> palette) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        inv.setStack(4, uiGlint(Items.ECHO_SHARD, "§d§l🌈 Color Palette",
                "§7Click a color to §napply§r§7 it as the block texture.",
                "§7Or click §b§lAdd as Variants§r§7 (slot 53) to add",
                "§77 picks as random variants."));

        // 16 palette entries in slots 10–25 (2 rows of 8)
        Item[] colorItems = {
            Items.RED_WOOL, Items.ORANGE_WOOL, Items.YELLOW_WOOL, Items.LIME_WOOL,
            Items.GREEN_WOOL, Items.CYAN_WOOL, Items.LIGHT_BLUE_WOOL, Items.BLUE_WOOL,
            Items.PURPLE_WOOL, Items.MAGENTA_WOOL, Items.PINK_WOOL, Items.WHITE_WOOL,
            Items.LIGHT_GRAY_WOOL, Items.GRAY_WOOL, Items.BROWN_WOOL, Items.BLACK_WOOL
        };
        String[] colorNames = {
            "§c§lRed", "§6§lOrange", "§e§lYellow", "§a§lLime",
            "§2§lGreen", "§3§lCyan", "§b§lLight Blue", "§9§lBlue",
            "§5§lPurple", "§d§lMagenta", "§d§lPink", "§f§lWhite",
            "§7§lLight Gray", "§8§lGray", "§6§lBrown", "§0§lBlack"
        };
        for (int i = 0; i < Math.min(16, palette.size()); i++) {
            int dispSlot = (i < 8) ? (10 + i) : (19 + (i - 8));
            Item item = colorItems[i];
            net.minecraft.item.ItemStack s = uiGlint(item, colorNames[i] + " §r§7variant",
                    "§aClick to apply as main texture.");
            inv.setStack(dispSlot, s);
        }

        // Add as Variants — slot 53
        inv.setStack(53, uiGlint(Items.NETHER_STAR, "§5§l+ Add 7 as Variants",
                "§7Picks 7 palette colors and adds them",
                "§7as random variant textures (H4)."));

        inv.setStack(49, uiGlint(Items.ECHO_SHARD, "§c◀ Back"));
        return inv;
    }

    private static void handlePaletteGeneratorClick(ServerPlayerEntity player, GuiState state, int slot) {
        playClick(player);
        String customId = state.editingId();
        com.customblocks.core.SlotData d = com.customblocks.core.SlotManager.getById(customId);
        if (d == null) { handleEscBack(player); return; }

        List<byte[]> palette = PALETTE_CACHE.get(customId);
        if (palette == null || palette.isEmpty()) {
            if (slot == 49) { handleEscBack(player); return; }
            FeedbackHelper.actionBar(player, "§ePalette still generating, please wait…");
            return;
        }

        // Slots 10-17 and 19-26 = palette entries
        int paletteIdx = -1;
        if (slot >= 10 && slot <= 17) paletteIdx = slot - 10;
        else if (slot >= 19 && slot <= 26) paletteIdx = 8 + (slot - 19);

        if (paletteIdx >= 0 && paletteIdx < palette.size()) {
            byte[] newTex = palette.get(paletteIdx);
            com.customblocks.core.SlotManager.updateTexture(customId, newTex);
            com.customblocks.core.SlotManager.saveAll();
            com.customblocks.core.SlotData updated = com.customblocks.core.SlotManager.getById(customId);
            if (updated != null) {
                com.customblocks.network.NetworkManager.broadcastUpdate(player.getServer(),
                        new com.customblocks.network.SlotUpdatePayload("retexture",
                                updated.index, updated.customId, updated.displayName,
                                newTex, updated.lightLevel, updated.hardness, updated.soundType,
                                null, null, updated.animMeta));
            }
            FeedbackHelper.actionBar(player, "§d§l🌈 §r§fPalette color applied!");
            playSuccess(player);
            PALETTE_CACHE.remove(customId);
            handleEscBack(player);
            return;
        }

        // Slot 53 — add 7 as variants
        if (slot == 53 && palette.size() >= 2) {
            // Pick indices evenly spaced across the 16 colors, up to 7
            List<byte[]> picks = new ArrayList<>();
            int step = Math.max(1, palette.size() / 7);
            for (int i = 0; i < palette.size() && picks.size() < 7; i += step) {
                picks.add(palette.get(i));
            }
            com.customblocks.core.SlotManager.setVariantTextures(customId, picks);
            com.customblocks.core.SlotManager.saveAll();
            FeedbackHelper.actionBar(player, "§5§l+ §r§f" + picks.size() + " palette variants added!");
            playSuccess(player);
            PALETTE_CACHE.remove(customId);
            handleEscBack(player);
            return;
        }

        if (slot == 49) { PALETTE_CACHE.remove(customId); handleEscBack(player); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // J2 — AI Smart Suggest GUI
    // ─────────────────────────────────────────────────────────────────────────

    /** Label, lore, item, and ImageProcessor op for each of the 18 smart presets. */
    private static final Object[][] AI_PRESETS = {
        // {slot, label, lore1, lore2, Item}
        {18, "§c§lWarmer Tones",   "§7Boosts reds, softens blues",        "§8applyTint(1.4, 0.85, 0.7)", Items.RED_DYE},
        {19, "§9§lCooler Tones",   "§7Boosts blues, softens reds",         "§8applyTint(0.7, 0.85, 1.4)", Items.BLUE_DYE},
        {20, "§e§lSunshine",       "§7Warm golden-yellow glow",            "§8applyTint(1.3, 1.2, 0.6)",  Items.YELLOW_DYE},
        {21, "§7§lGrayscale",      "§7Desaturate to stone/concrete look",  "§8applyGrayscale()",          Items.GRAY_DYE},
        {22, "§f§lMirror",         "§7Horizontal flip",                    "§8applyMirrorH()",            Items.COMPASS},
        {23, "§d§lInverted",       "§7Full RGB color inversion",           "§8applyInvert()",             Items.ENDER_EYE},
        {24, "§a§lBrighter",       "§7+40 brightness, same contrast",      "§8applyBrightness(+40, 1.0)", Items.GLOWSTONE_DUST},
        {25, "§8§lDarker",         "§7-40 brightness, same contrast",      "§8applyBrightness(-40, 1.0)", Items.COAL},
        {26, "§2§lForest",         "§7Deep green woodland tones",          "§8applyTint(0.7, 1.4, 0.6)",  Items.GREEN_DYE},
        {27, "§b§lFrosty",         "§7Icy cyan / arctic palette",          "§8applyTint(0.7, 1.3, 1.4)",  Items.CYAN_DYE},
        {28, "§5§lMystic Purple",  "§7Enchanted purple atmosphere",        "§8applyTint(1.2, 0.6, 1.4)",  Items.PURPLE_DYE},
        {29, "§6§lDesert Sand",    "§7Warm orange-brown earthen tones",    "§8applyTint(1.3, 1.0, 0.5)",  Items.ORANGE_DYE},
        {30, "§c§lRotate 90°",     "§7Rotate texture clockwise 90°",       "§8applyRotate90()",           Items.CLOCK},
        {31, "§3§lOcean Deep",     "§7Deep blue underwater palette",       "§8applyTint(0.6, 0.8, 1.5)",  Items.LIGHT_BLUE_DYE},
        {32, "§4§lBlood Moon",     "§7Dramatic deep crimson",              "§8applyTint(1.5, 0.5, 0.5)",  Items.BEETROOT},
        {33, "§a§lNeon Green",     "§7Vivid radioactive green",            "§8applyTint(0.5, 1.6, 0.5)",  Items.LIME_DYE},
        {34, "§f§lHigh Contrast",  "§7Punchy contrast boost (1.8x)",       "§8applyBrightness(0, 1.8)",   Items.QUARTZ},
        {35, "§9§lDusk Purple",    "§7Blue-purple twilight palette",       "§8applyTint(0.8, 0.7, 1.5)",  Items.MAGENTA_DYE},
    };

    public static void openAiSuggestGui(ServerPlayerEntity player, String customId) {
        com.customblocks.core.SlotData d = com.customblocks.core.SlotManager.getById(customId);
        if (d == null) { openEditor(player, customId, 0); return; }
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.aiSuggestGui(customId),
            buildAiSuggestGui(d), Text.translatable("customblocks.gui.ai_suggest.title").append(Text.literal(" §8— " + d.displayName)));
    }

    private static SimpleInventory buildAiSuggestGui(com.customblocks.core.SlotData d) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        inv.setStack(4, uiGlint(Items.ECHO_SHARD, "§d§l✦ AI Smart Suggest",
            "§718 curated one-click texture presets.",
            "§7Click any to apply instantly.",
            "§8Changes can be undone via §f/cb undo§8."));
        inv.setStack(13, ui(Items.AMETHYST_SHARD, "§bRow 1: Tone & Color Shifts", "§7Tints, grayscale, mirror, invert, brightness"));
        inv.setStack(22, ui(Items.AMETHYST_SHARD, "§bRow 2: Special Effects", "§7Cool/warm extremes, rotate, contrast, neon"));
        for (Object[] p : AI_PRESETS) {
            int slot    = (int) p[0];
            String name = (String) p[1];
            String l1   = (String) p[2];
            String l2   = (String) p[3];
            Items it    = null; // unused — use Items reference below
            Item item   = (Item) p[4];
            inv.setStack(slot, uiGlint(item, name, l1, l2, "§aClick to apply →"));
        }
        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back to Editor", "§8Return without applying"));
        return inv;
    }

    private static void handleAiSuggestClick(ServerPlayerEntity player, GuiState state, int slot) {
        playClick(player);
        String customId = state.editingId();
        com.customblocks.core.SlotData d = com.customblocks.core.SlotManager.getById(customId);
        if (d == null || slot == 45) { handleEscBack(player); return; }
        if (d.texture == null || d.texture.length == 0) {
            FeedbackHelper.actionBar(player, "§cNo texture on this block yet!");
            return;
        }

        byte[] newTex = null;
        String presetName = "";
        try {
            newTex = switch (slot) {
                case 18 -> { presetName = "Warmer Tones";   yield com.customblocks.ImageProcessor.applyTint(d.texture, 1.4f, 0.85f, 0.7f); }
                case 19 -> { presetName = "Cooler Tones";   yield com.customblocks.ImageProcessor.applyTint(d.texture, 0.7f, 0.85f, 1.4f); }
                case 20 -> { presetName = "Sunshine";       yield com.customblocks.ImageProcessor.applyTint(d.texture, 1.3f, 1.2f, 0.6f); }
                case 21 -> { presetName = "Grayscale";      yield com.customblocks.ImageProcessor.applyGrayscale(d.texture); }
                case 22 -> { presetName = "Mirror";         yield com.customblocks.ImageProcessor.applyMirrorH(d.texture); }
                case 23 -> { presetName = "Inverted";       yield com.customblocks.ImageProcessor.applyInvert(d.texture); }
                case 24 -> { presetName = "Brighter";       yield com.customblocks.ImageProcessor.applyBrightness(d.texture, 40, 1.0f); }
                case 25 -> { presetName = "Darker";         yield com.customblocks.ImageProcessor.applyBrightness(d.texture, -40, 1.0f); }
                case 26 -> { presetName = "Forest";         yield com.customblocks.ImageProcessor.applyTint(d.texture, 0.7f, 1.4f, 0.6f); }
                case 27 -> { presetName = "Frosty";         yield com.customblocks.ImageProcessor.applyTint(d.texture, 0.7f, 1.3f, 1.4f); }
                case 28 -> { presetName = "Mystic Purple";  yield com.customblocks.ImageProcessor.applyTint(d.texture, 1.2f, 0.6f, 1.4f); }
                case 29 -> { presetName = "Desert Sand";    yield com.customblocks.ImageProcessor.applyTint(d.texture, 1.3f, 1.0f, 0.5f); }
                case 30 -> { presetName = "Rotate 90°";     yield com.customblocks.ImageProcessor.applyRotate90(d.texture); }
                case 31 -> { presetName = "Ocean Deep";     yield com.customblocks.ImageProcessor.applyTint(d.texture, 0.6f, 0.8f, 1.5f); }
                case 32 -> { presetName = "Blood Moon";     yield com.customblocks.ImageProcessor.applyTint(d.texture, 1.5f, 0.5f, 0.5f); }
                case 33 -> { presetName = "Neon Green";     yield com.customblocks.ImageProcessor.applyTint(d.texture, 0.5f, 1.6f, 0.5f); }
                case 34 -> { presetName = "High Contrast";  yield com.customblocks.ImageProcessor.applyBrightness(d.texture, 0, 1.8f); }
                case 35 -> { presetName = "Dusk Purple";    yield com.customblocks.ImageProcessor.applyTint(d.texture, 0.8f, 0.7f, 1.5f); }
                default  -> null;
            };
        } catch (Exception ex) {
            FeedbackHelper.actionBar(player, "§cFailed to apply preset: " + ex.getMessage());
            return;
        }
        if (newTex == null) return;

        final byte[] finalTex = newTex;
        final String finalName = presetName;
        com.customblocks.core.UndoManager.pushUndoMutation(customId, d, "suggest:" + presetName, player.getUuid());
        com.customblocks.core.SlotManager.updateTexture(customId, finalTex);
        com.customblocks.core.SlotManager.saveAll();
        com.customblocks.core.SlotData updated = com.customblocks.core.SlotManager.getById(customId);
        if (updated != null) {
            com.customblocks.network.NetworkManager.broadcastUpdate(player.getServer(),
                new com.customblocks.network.SlotUpdatePayload("retexture",
                    updated.index, updated.customId, updated.displayName,
                    finalTex, updated.lightLevel, updated.hardness, updated.soundType,
                    null, null, updated.animMeta));
        }
        FeedbackHelper.actionBar(player, "§d§l✦ §r§fApplied: §d" + finalName + "  §8(§e/cb undo§8 to revert)");
        playSuccess(player);
        handleEscBack(player);
    }

    // ── L3: Cloud Vault Market GUI ────────────────────────────────────────────

    /**
     * Opens the Cloud Vault market browser. Pass {@code refresh=true} to force-refetch.
     */
    public static void openMarketGui(ServerPlayerEntity player, int page, boolean refresh) {
        // 8.4 — gate: marketplace can be disabled by the server admin
        if (!CustomBlocksConfig.marketplaceEnabled) {
            player.sendMessage(Text.literal("§c[Market] §7Marketplace is disabled by the server admin."), true);
            playError(player);
            return;
        }
        UUID uuid = player.getUuid();
        pushBackStack(uuid);
        if (refresh || !MARKET_CACHE.containsKey(uuid)) {
            // Show loading screen immediately, then fetch async
            SimpleInventory loading = new SimpleInventory(54);
            ItemStack grayPane = ui(Items.GRAY_STAINED_GLASS_PANE, " ");
            for (int s : new int[]{0,1,2,3,4,5,6,7,8,9,17,18,26,27,35,36,44,45,46,47,48,49,50,51,52,53}) loading.setStack(s, grayPane.copy());
            loading.setStack(22, named(Items.CLOCK, "§e§lFetching Cloud Vault..."));
            openScreenFromGuiState(player, GuiState.marketGui(page), loading,
                    Text.translatable("customblocks.gui.market.title.loading"));
            final int finalPage = page;
            EXECUTOR.submit(() -> {
                java.util.List<JsonObject> items = fetchMarketListing(player);
                MARKET_CACHE.put(uuid, items);
                player.getServer().execute(() -> {
                    if (STATES.containsKey(uuid) && STATES.get(uuid).mode() == GuiMode.MARKET_GUI) {
                        openScreenFromGuiState(player, GuiState.marketGui(finalPage),
                            buildMarketGui(uuid, items, finalPage),
                            Text.translatable("customblocks.gui.market.title", items.size()));
                    }
                });
            });
        } else {
            java.util.List<JsonObject> cached = MARKET_CACHE.getOrDefault(uuid, java.util.List.of());
            openScreenFromGuiState(player, GuiState.marketGui(page),
                buildMarketGui(uuid, cached, page),
                Text.translatable("customblocks.gui.market.title", cached.size()));
        }
    }

    private static java.util.List<JsonObject> fetchMarketListing(ServerPlayerEntity player) {
        String base = com.customblocks.CustomBlocksConfig.normalizedCloudShareUrl();
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(base + "/market?limit=50"))
                .timeout(java.time.Duration.ofSeconds(8))
                .GET().build();
            java.net.http.HttpResponse<String> resp = HTTP.send(req,
                java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return java.util.List.of();
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("items");
            java.util.List<JsonObject> result = new java.util.ArrayList<>();
            for (JsonElement el : arr) result.add(el.getAsJsonObject());
            return result;
        } catch (Exception e) {
            LOGGER.warn("[Market] Failed to fetch listing: {}", e.getMessage());
            return java.util.List.of();
        }
    }

    private static SimpleInventory buildMarketGui(UUID uuid, java.util.List<JsonObject> rawItems, int page) {
        // Apply search filter
        String query = uuid != null ? MARKET_SEARCH_QUERIES.getOrDefault(uuid, "") : "";
        java.util.List<JsonObject> items = query.isEmpty() ? rawItems : rawItems.stream()
            .filter(m -> {
                String dn = m.has("displayName") ? m.get("displayName").getAsString().toLowerCase(java.util.Locale.ROOT) : "";
                String id = m.has("customId")    ? m.get("customId").getAsString().toLowerCase(java.util.Locale.ROOT)    : "";
                return dn.contains(query) || id.contains(query);
            }).toList();

        // Apply sort
        String sortMode = uuid != null ? MARKET_SORT_MODES.getOrDefault(uuid, "date") : "date";
        if ("name".equals(sortMode)) {
            items = items.stream().sorted((a, b) -> {
                String an = a.has("displayName") ? a.get("displayName").getAsString() : "";
                String bn = b.has("displayName") ? b.get("displayName").getAsString() : "";
                return an.compareToIgnoreCase(bn);
            }).toList();
        }

        SimpleInventory inv = new SimpleInventory(54);
        ItemStack purplePane = ui(Items.PURPLE_STAINED_GLASS_PANE, " ");
        for (int s : new int[]{0,1,2,3,4,5,6,7,8,9,17,18,26,27,35,36,44,45,46,47,48,49,50,51,52,53}) inv.setStack(s, purplePane.copy());

        // Header
        String filterLabel = query.isEmpty() ? "" : " §8[filter: §f" + query + "§8]";
        inv.setStack(4, named(Items.BEACON, "§5§l✦ §r§fCloud Vault" + filterLabel,
            "§7Browse blocks shared by the community.",
            "§8" + items.size() + " result(s) · Click any block to import."));

        // Search slot
        inv.setStack(8, ui(Items.SPYGLASS,
            query.isEmpty() ? "§7Search Vault..." : "§eSearch: §f" + query,
            "§7Click to search by name or ID",
            query.isEmpty() ? "" : "§8Click to clear: type a blank line"));

        // Sort toggle
        inv.setStack(7, ui(Items.COMPARATOR,
            "§7Sort: §f" + ("name".equals(sortMode) ? "Name A→Z" : "Latest first"),
            "§8Click to toggle sort order"));

        // Upload button
        inv.setStack(6, ui(Items.CHEST_MINECART, "§a§l▲ Share a Block",
            "§7Type §f/cb share <id> §7to share a block",
            "§7or §f/cb sharecategory <cat> §7for a whole category",
            "§8Shared blocks appear here for everyone to import."));

        // Listing: slots 10-16, 19-25, 28-34  (3 rows × 7 = 21 slots)
        int[] listingSlots = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34};
        int start = page * MARKET_PAGE_SIZE;
        for (int i = 0; i < listingSlots.length && i < MARKET_PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx >= items.size()) break;
            JsonObject meta = items.get(idx);
            String displayName = meta.has("displayName") ? meta.get("displayName").getAsString() : "Unknown";
            String code        = meta.has("code")        ? meta.get("code").getAsString() : "";
            String customId    = meta.has("customId")    ? meta.get("customId").getAsString() : "";
            String createdAt   = meta.has("createdAt")   ? meta.get("createdAt").getAsString().substring(0, 10) : "?";
            int downloads      = meta.has("downloads")   ? meta.get("downloads").getAsInt() : -1;
            String dlLine      = downloads >= 0 ? "§7Downloads: §f" + downloads : "";
            ItemStack entry = uiGlint(Items.ENDER_EYE,
                "§b§l" + displayName,
                "§7Code: §f" + code,
                "§7ID:   §8" + customId,
                "§7Date: §8" + createdAt,
                dlLine,
                "§a§l» Click to import");
            inv.setStack(listingSlots[i], entry);
        }

        // Navigation
        if (page > 0) inv.setStack(46, customHead(MHF_LEFT, "§a§l« Previous Page"));
        inv.setStack(49, named(Items.COMPASS,
            "§7Page §f" + (page + 1) + " §7of §f" + Math.max(1, (int) Math.ceil(items.size() / (double) MARKET_PAGE_SIZE)),
            "§8" + rawItems.size() + " total in vault" + (query.isEmpty() ? "" : " · " + items.size() + " matching")));
        if ((page + 1) * MARKET_PAGE_SIZE < items.size())
            inv.setStack(52, customHead(MHF_RIGHT, "§a§lNext Page »"));
        inv.setStack(50, named(Items.RECOVERY_COMPASS, "§e§l⟳ Refresh"));
        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c§l✖ Back"));
        return inv;
    }

    private static void handleMarketGuiClick(ServerPlayerEntity player, GuiState state, int slot) {
        int page = state.page();
        UUID uuid = player.getUuid();
        java.util.List<JsonObject> rawItems = MARKET_CACHE.getOrDefault(uuid, java.util.List.of());
        // Build filtered list for pagination math
        String query = MARKET_SEARCH_QUERIES.getOrDefault(uuid, "");
        java.util.List<JsonObject> items = query.isEmpty() ? rawItems : rawItems.stream()
            .filter(m -> {
                String dn = m.has("displayName") ? m.get("displayName").getAsString().toLowerCase(java.util.Locale.ROOT) : "";
                String id = m.has("customId")    ? m.get("customId").getAsString().toLowerCase(java.util.Locale.ROOT)    : "";
                return dn.contains(query) || id.contains(query);
            }).toList();
        int[] listingSlots = {10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34};

        // Back
        if (slot == 45) { handleEscBack(player); return; }

        // Refresh
        if (slot == 50) {
            MARKET_CACHE.remove(uuid);
            MARKET_SEARCH_QUERIES.remove(uuid);
            openMarketGui(player, 0, true);
            return;
        }

        // Search
        if (slot == 8) {
            openShortInputPrompt(player,
                new PendingInput(InputAction.REID_TEXT, "__market_search__", null, null, null, page),
                "§5Search Vault", new ItemStack(Items.SPYGLASS), query);
            return;
        }

        // Sort toggle
        if (slot == 7) {
            String current = MARKET_SORT_MODES.getOrDefault(uuid, "date");
            MARKET_SORT_MODES.put(uuid, "date".equals(current) ? "name" : "date");
            openScreenFromGuiState(player, GuiState.marketGui(0),
                buildMarketGui(uuid, rawItems, 0),
                Text.translatable("customblocks.gui.market.title", rawItems.size()));
            return;
        }

        // Upload info (slot 6 — informational only, no action)
        if (slot == 6) return;

        // Prev page
        if (slot == 46 && page > 0) {
            openScreenFromGuiState(player, GuiState.marketGui(page - 1),
                buildMarketGui(uuid, rawItems, page - 1),
                Text.translatable("customblocks.gui.market.title", rawItems.size()));
            return;
        }

        // Next page
        if (slot == 52 && (page + 1) * MARKET_PAGE_SIZE < items.size()) {
            openScreenFromGuiState(player, GuiState.marketGui(page + 1),
                buildMarketGui(uuid, rawItems, page + 1),
                Text.translatable("customblocks.gui.market.title", rawItems.size()));
            return;
        }

        // Listing click → import
        for (int i = 0; i < listingSlots.length; i++) {
            if (slot == listingSlots[i]) {
                int idx = page * MARKET_PAGE_SIZE + i;
                if (idx >= items.size()) return;
                JsonObject meta = items.get(idx);
                String code = meta.has("code") ? meta.get("code").getAsString() : null;
                if (code == null) return;
                final String finalCode = code;
                try {
                    player.getServer().getCommandManager().getDispatcher()
                        .execute("cb importblock " + finalCode, player.getCommandSource());
                    FeedbackHelper.actionBar(player, "§a§l✔ §r§aImporting §f" + finalCode + "§a…");
                    playSuccess(player);
                } catch (Exception ex) {
                    FeedbackHelper.actionBar(player, "§cImport failed: " + ex.getMessage());
                    playError(player);
                }
                return;
            }
        }
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

        // V4-34 — keyboard shortcut overlay
        inv.setStack(42, ui(Items.WRITABLE_BOOK, "§e§lKeyboard Shortcuts",
            "§7In Block List: §fLeft-click §8→ §7editor",
            "§7              §fRight-click §8→ §7category",
            "§7              §fSlot 8 §8→ §7search",
            "§7In Editor:    §fAll changes §8→ §7live/instant",
            "§7Anywhere:     §f/cb help §8→ §7this guide",
            "§7              §f/cb undo §8→ §7undo last action",
            "§7              §fESC §8→ §7back / close GUI"));

        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back"));
        return inv;
    }

    private static SimpleInventory buildHelpCategory(int category) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back to Help"));

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
            default -> {}
        }
        return inv;
    }

    private static SimpleInventory buildPropertiesGui(SlotData d) {
        SimpleInventory inv = new SimpleInventory(54);
        for(int i=0;i<54;i++) inv.setStack(i, glass());
        inv.setStack(0, uiGlint(Items.ECHO_SHARD,"§c◀ Back to Editor","§8Return to the block editor"));
        
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
        
        inv.setStack(45, uiGlint(Items.ECHO_SHARD,"§c◀ Back to Editor"));
        return inv;
    }

    private static SimpleInventory buildSoundMenu(SlotData d) {
        SimpleInventory inv = new SimpleInventory(54);
        for(int i=0;i<54;i++) inv.setStack(i, glass());
        inv.setStack(0, uiGlint(Items.ECHO_SHARD,"§c◀ Back to Editor","§8Return to the block editor"));
        
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

        inv.setStack(45, uiGlint(Items.ECHO_SHARD,"§c◀ Back to Editor"));
        return inv;
    }

    private static SimpleInventory buildPicker(UUID playerUuid, int page, boolean brokenOnly) {
        SimpleInventory inv = new SimpleInventory(54);
        // 1.23 — broken view uses reason map; normal view uses sorted list (1.27 sort-aware)
        java.util.Map<SlotData, SlotData.BlockHealth> brokenMap = brokenOnly
                ? com.customblocks.core.SlotManager.brokenBlocksWithReasons() : null;
        List<SlotData> blocks = brokenOnly ? new ArrayList<>(brokenMap.keySet()) : sortedBlocks(playerUuid);
        int total = blocks.size(), maxPage = total==0?0:Math.max(0,(total-1)/BLOCKS_PER_PAGE);
        inv.setStack(0, uiGlint(Items.ECHO_SHARD,"§c◀ Back to Main Dashboard","§8Return to the main menu"));
        for (int i=1;i<=3;i++) inv.setStack(i,glass());
        SortMode activeSortMode = brokenOnly ? null : PLAYER_SORT_PREFS.getOrDefault(playerUuid, SortMode.NAME_ASC);
        String sortLabel = activeSortMode != null ? " §8— §7sorted: §f" + activeSortMode.label : "";
        inv.setStack(4, ui(Items.ENCHANTED_BOOK,"§e§lSelect Block to Manage" + sortLabel,
            "§7Manage your creations from the list below",
            "§8"+Math.min(BLOCKS_PER_PAGE,Math.max(0,total-page*BLOCKS_PER_PAGE))+" of §f"+total+" §8blocks  •  Page §f"+(page+1)+"§8/§f"+(maxPage+1),
            "§aUse the arrows at the bottom to flip pages"));
        for (int i=5;i<=7;i++) inv.setStack(i,glass());
        if (brokenOnly && total > 0)
            inv.setStack(8, uiGlint(Items.TNT, "§c§l⚠ Delete All Broken", "§7Remove all " + total + " broken block(s)", "§cThis action uses undo support."));
        else
            // V4-23: search shortcut at slot 8
            inv.setStack(8, uiGlint(Items.SPYGLASS, "§f§lSearch Blocks", "§7Click to search by name or ID"));
        for (int i=9;i<=17;i++) inv.setStack(i, ui(Items.BLUE_STAINED_GLASS_PANE,"§r"));
        int start = page * BLOCKS_PER_PAGE;
        for (int i=0; i<BLOCKS_PER_PAGE; i++) {
            int invSlot = 18+i, dataIdx = start+i;
            if (dataIdx < blocks.size()) {
                SlotData d = blocks.get(dataIdx);
                ItemStack s = CustomBlocksMod.safeSlotItem(d.index)!=null ? new ItemStack(CustomBlocksMod.safeSlotItem(d.index)) : ItemStack.EMPTY;
                s.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§f§l"+d.displayName).styled(st->st.withItalic(false)));
                boolean isFav = com.customblocks.core.FavoritesManager.isFavorite(playerUuid, d.customId);
                List<String> ll = new ArrayList<>(List.of("§7Unique ID: §b"+d.customId,"§7Shape: §5"+d.shapeLabel()+" §8• §7Light: §e"+d.lightLevel,"§7Sound: §f"+d.soundType,"§aLeft-click §7→ open the Block Editor", "§dRight-click / Shift-click §7→ assign block to a category"));
                if (isFav) ll.add("§6★ Favorite §8— Press §fF §8to unfavorite");
                else ll.add("§8☆ Press §fF §8to favorite");
                List<String> tags=new ArrayList<>(); if(d.hasFaces())tags.add("§d⬡faces"); if(d.isAnimated())tags.add("§b⏳anim"); if(d.noCollision)tags.add("§c⊘hitbox"); if(!tags.isEmpty())ll.add(String.join("  ",tags));
                // V4-00 — show BlockHealth tooltip in broken-blocks view
                if (brokenOnly && brokenMap != null) {
                    SlotData.BlockHealth health = brokenMap.get(d);
                    if (health != null) {
                        ll.add("§c⚠ " + health.tooltip);
                        if (health == SlotData.BlockHealth.LOAD_FAILURE) {
                            ll.add("§aShift-click §8→ retry loading texture");
                        } else if (health == SlotData.BlockHealth.PLACEHOLDER) {
                            ll.add("§7Shift-click §8→ suppress this warning");
                        }
                    }
                }
                s.set(DataComponentTypes.LORE, new LoreComponent(ll.stream().map(l->(Text)lore(l)).toList()));
                if (isFav) s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
                inv.setStack(invSlot, s);
            } else { inv.setStack(invSlot, glass()); }
        }
        for (int i=36;i<=44;i++) inv.setStack(i, ui(Items.BLUE_STAINED_GLASS_PANE,"§r"));
        inv.setStack(45, page>0 ? uiGlint(Items.ARROW,"§7◀ Previous Page","§8Go to page "+page) : ui(Items.GRAY_STAINED_GLASS_PANE,"§8◀ First Page",""));
        for (int i=46;i<=48;i++) inv.setStack(i,glass());
        inv.setStack(49, ui(Items.PAPER,"§ePage §f"+(page+1)+" §7/ §f"+(maxPage+1),"§7Total: §f"+total+" blocks"));
        // V4-33 — ? help button at slot 50
        inv.setStack(50, ui(Items.BOOK, "§7? Help",
            "§7Left-click a block §8→ §fOpen Block Editor",
            "§7Right-click §8→ §fAssign to category",
            "§7Slot 8 §8→ §fSearch by name or ID",
            "§8Type §f/cb help §8for full guide."));
        // 1.27 — sort button (slot 51); hidden in broken-only view
        if (!brokenOnly) {
            SortMode cur = PLAYER_SORT_PREFS.getOrDefault(playerUuid, SortMode.NAME_ASC);
            inv.setStack(51, uiGlint(Items.HOPPER, "§e⬇ Sort: §f" + cur.label, "§7Click to open the Sort menu", "§8Current: §f" + cur.label));
        } else {
            inv.setStack(51, glass());
        }
        inv.setStack(52, glass());
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
        inv.setStack(0, uiGlint(Items.ECHO_SHARD,"§c◀ Back to Main Dashboard","§8Return to the main menu"));
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

    private static ItemStack displayStackFor(SlotData d) {
        Item base = CustomBlocksMod.safeSlotItem(d.index);
        ItemStack disp = base != null ? new ItemStack(base) : ItemStack.EMPTY;
        if (disp.isEmpty()) {
            disp = new ItemStack(Items.STONE);
        }
        disp.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§e§l" + d.displayName).styled(s -> s.withItalic(false)));
        disp.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            lore("§7Unique ID: §b" + d.customId),
            lore("§7Current Shape: §5" + d.shapeLabel()),
            lore("§7Light Level: §e" + d.lightLevel + "  §7Hardness: §f" + hardnessLabel(d.hardness)),
            lore("§7Sound: §f" + d.soundType),
            lore("§7Hitbox: " + (d.noCollision ? "§cOFF" : "§aON")),
            lore("§8Slot #" + d.index)
        )));
        return disp;
    }

    private static SimpleInventory buildEditor(SlotData d, boolean confirmDelete, UUID playerUuid) {
        SimpleInventory inv = new SimpleInventory(54);
        for(int i = 0; i < 54; i++) inv.setStack(i, glass());

        inv.setStack(0, uiGlint(Items.ECHO_SHARD, "§c◀ Back to Block List", "§8Return to the selection grid"));
        inv.setStack(2, uiGlint(Items.CHEST,"§a▶ Give 1x","§7Gives 1x §f"+d.displayName+" §7to you", "§aPuts the block directly in your hotbar."));

        boolean isFav    = com.customblocks.core.FavoritesManager.isFavorite(playerUuid, d.customId);
        boolean isLocked = com.customblocks.core.LockManager.isLocked(d.customId);
        ItemStack disp = displayStackFor(d);
        // Append star / lock badges to the display item's lore
        if (isFav || isLocked) {
            var existingLore = disp.get(DataComponentTypes.LORE);
            List<net.minecraft.text.Text> lines = existingLore != null ? new java.util.ArrayList<>(existingLore.lines()) : new java.util.ArrayList<>();
            if (isFav)    lines.add(lore("§6★ Favorite"));
            if (isLocked) lines.add(lore("§c🔒 Locked"));
            disp.set(DataComponentTypes.LORE, new LoreComponent(lines));
        }
        inv.setStack(4, disp);

        // Slot 35 — Favorite toggle
        inv.setStack(35, isFav
            ? uiGlint(Items.GOLD_INGOT,  "§6§l★ Favorited",       "§7Click to remove from favorites")
            : ui(Items.IRON_INGOT,        "§7§l☆ Not Favorited",   "§7Click to add to favorites"));
        // Slot 44 — Lock toggle
        inv.setStack(44, isLocked
            ? uiGlint(Items.CHAIN,        "§c§l🔒 Locked",          "§cEdits and deletions are blocked", "§7Click to unlock")
            : ui(Items.TRIPWIRE_HOOK,     "§a§l🔓 Unlocked",        "§7Click to lock this block", "§8Prevents accidental edits or deletion"));
        inv.setStack(8, uiGlint(Items.MAP,"§b§l» Retexture Block","§7Update the main texture of this block","§aPaste a URL from Imgur, Discord, etc."));
        // J2 — AI Smart Suggest
        inv.setStack(11, uiGlint(Items.ECHO_SHARD, "§d§l✦ AI Smart Suggest",
            "§718 curated one-click texture presets:",
            "§7warmer, cooler, grayscale, invert,",
            "§7mirror, rotate, brighter, darker…",
            "§aClick to open Smart Suggest."));
        inv.setStack(17, uiGlint(Items.ECHO_SHARD, "§b§lApply Image from URL", "§7Instantly cast an image/GIF onto this", "§7block via a URL link. (Web-Linker)", "", "§e§l▶ Click to cast."));
        
        inv.setStack(19, uiGlint(Items.PAINTING, "§d§l⬡ Edit Faces", "§7Apply textures to individual faces","§aChange Top, Bottom, or Side textures separately."));
        inv.setStack(21, uiGlint(Items.ENDER_PEARL, "§5§l⬡ Edit Shape", "§7Presets, custom boxes, and collisions","§aMake slabs, stairs, or custom hitboxes."));
        inv.setStack(23, uiGlint(Items.REDSTONE, "§6§l⚙ Properties", "§7Adjust light glow & mining hardness","§aAdjust how the block feels in-world."));
        inv.setStack(25, uiGlint(Items.NOTE_BLOCK, "§e§l♫ Sound", "§7Change placement & break sounds","§aSimulate stone, glass, dirt, etc."));
        
        inv.setStack(31, d.isAnimated()
            ? uiGlint(Items.CLOCK, "§b§l⏳ Animation Settings", "§7This block is currently animated","§aYou can adjust frame speed (FPS) here.")
            : ui(Items.GRAY_DYE, "§7§l⏳ Animation", "§8No animation detected","§aAnimations are auto-enabled for GIF textures."));

        // I2 — Hologram Text button (slot 27)
        inv.setStack(27, com.customblocks.CustomBlocksConfig.hologramEnabled
            ? (d.hasHologramText()
                ? uiGlint(Items.SOUL_LANTERN, "§b§l✦ Hologram: §f" + d.hologramText,
                    "§7Floating label above placed blocks.",
                    "§aClick to change  •  Type §fclear §ato remove.")
                : uiGlint(Items.SOUL_LANTERN, "§b§l✦ Set Hologram Text",
                    "§7Show a custom floating label above",
                    "§7every placed copy of this block.",
                    "§aClick to set."))
            : ui(Items.SOUL_LANTERN, "§8§l✦ Hologram §8(Disabled)",
                "§8Enable in §f/cb config §8to use holograms.",
                "§8Set hologramEnabled: true in config."));

        // G1 — Color Studio button (slot 29)
        inv.setStack(29, uiGlint(Items.BLAZE_POWDER, "§6§l🎨 Color Studio",
                "§7Apply tint, brightness, grayscale,",
                "§7invert, mirror, or rotate filters.",
                "§aClick to open Color Studio."));

        // H4 — Variant Randomizer button (slot 33)
        inv.setStack(33, d.hasVariants()
            ? uiGlint(Items.NETHER_STAR, "§5§l🎲 Variants §8(" + d.variantCount() + " total)", "§7This block has §d" + d.variantTextures.size() + " §7extra textures.", "§aPlacement picks one at random!","§7Click to manage variants.")
            : ui(Items.AMETHYST_SHARD,   "§5§l🎲 Texture Variants", "§7Add 2–8 random textures to this block.","§aMinecraft will pick one at random","§7each time it's rendered in a chunk.","§8Click to open Variant Manager."));
        
        inv.setStack(37, uiGlint(Items.NAME_TAG,"§e§l✎ Rename Block","§7Current: §f"+d.displayName,"§aThis is the name everyone sees in the inventory."));
        inv.setStack(39, uiGlint(Items.COMMAND_BLOCK,"§b§l✦ Re-ID Block","§7Current: §b"+d.customId,"§aChanging the unique ID updates all current builds."));
        inv.setStack(41, uiGlint(Items.COMPARATOR,"§e§l≋ Duplicate Block","§7Create an identical copy of this block","§aGreat for making similar block sets quickly."));
        inv.setStack(43, uiGlint(Items.ENDER_EYE,"§b§l⤴ Share Block","§7Export a shareable code to chat","§aOthers can import with /cb importblock."));
        
        inv.setStack(53, confirmDelete
            ? uiGlint(Items.BARRIER, "§4§l⚠ CONFIRM DELETION","§cPermanently delete: §f"+d.customId,"§c§oClick again to confirm!")
            : ui(Items.TNT, "§c§l⚠ Delete This Block","§7Removes the block from the server","§aCan be undone via Main Menu if accidental."));
        if (confirmDelete) inv.setStack(52, uiGlint(Items.GREEN_CONCRETE,"§a§l✖ Cancel","§7Go back without deleting."));

        inv.setStack(45, uiGlint(Items.ECHO_SHARD,"§c◀ Back to Block List"));
        // V4-33 — ? help button
        inv.setStack(47, ui(Items.BOOK, "§7? Help", "§7This is the Block Editor.", "§7All changes are live — no need to save.", "§8Click any button to see what it does.", "§8Type §f/cb help §8for the full guide."));
        return inv;
    }

    // ── V4-13 Box Nudge Editor ──────────────────────────────────────────────────

    /** Opens the nudge editor for box at index {@code boxIdx} of block {@code id}. */
    public static void openBoxNudgeEditor(ServerPlayerEntity player, String id, int boxIdx, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null || d.shapeBoxes == null || boxIdx >= d.shapeBoxes.size()) {
            reopenShapeEditor(player, id, returnPage, 0);
            return;
        }
        BOX_NUDGE_WORK.put(player.getUuid(), d.shapeBoxes.get(boxIdx));
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.boxNudgeEditor(id, boxIdx, returnPage),
            buildBoxNudgeEditor(d, d.shapeBoxes.get(boxIdx), boxIdx),
            Text.literal("§5§lNudge Box §8— §f" + d.displayName));
    }

    /** Builds the 54-slot box nudge editor GUI using {@code box} as the live values. */
    private static SimpleInventory buildBoxNudgeEditor(SlotData d, SlotData.ShapeBox box, int boxIdx) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        inv.setStack(0, uiGlint(Items.ECHO_SHARD, "§c◀ Back / Cancel", "§7Discards changes and returns to shape editor"));
        inv.setStack(4, ui(Items.STRUCTURE_VOID, "§5§lBox #" + boxIdx + " §8— §f" + d.displayName,
            "§7x1=" + fmtCoord(box.x1()) + "  y1=" + fmtCoord(box.y1()) + "  z1=" + fmtCoord(box.z1()),
            "§7x2=" + fmtCoord(box.x2()) + "  y2=" + fmtCoord(box.y2()) + "  z2=" + fmtCoord(box.z2()),
            "§aLeft-click ◀▶ §7nudges §f+1 §7(1/16 block)  |  §aRight-click §7nudges §f+4"));

        // Min-corner column (x1, y1, z1) — left side, slots 10-12, 19-21, 28-30
        inv.setStack(10, uiGlint(Items.RED_DYE,   "§c◀ x1 −1", "§8Left:−1  Right:−4"));
        inv.setStack(11, ui(Items.PINK_DYE,        "§fx1 = §e" + fmtCoord(box.x1()), "§7Min corner X  (0–16)"));
        inv.setStack(12, uiGlint(Items.LIME_DYE,  "§ax1 +1▶", "§8Left:+1  Right:+4"));

        inv.setStack(19, uiGlint(Items.RED_DYE,   "§c◀ y1 −1", "§8Left:−1  Right:−4"));
        inv.setStack(20, ui(Items.PINK_DYE,        "§fy1 = §e" + fmtCoord(box.y1()), "§7Min corner Y  (0–16)"));
        inv.setStack(21, uiGlint(Items.LIME_DYE,  "§ay1 +1▶", "§8Left:+1  Right:+4"));

        inv.setStack(28, uiGlint(Items.RED_DYE,   "§c◀ z1 −1", "§8Left:−1  Right:−4"));
        inv.setStack(29, ui(Items.PINK_DYE,        "§fz1 = §e" + fmtCoord(box.z1()), "§7Min corner Z  (0–16)"));
        inv.setStack(30, uiGlint(Items.LIME_DYE,  "§az1 +1▶", "§8Left:+1  Right:+4"));

        // Max-corner column (x2, y2, z2) — right side, slots 14-16, 23-25, 32-34
        inv.setStack(14, uiGlint(Items.RED_DYE,   "§c◀ x2 −1", "§8Left:−1  Right:−4"));
        inv.setStack(15, ui(Items.CYAN_DYE,        "§fx2 = §e" + fmtCoord(box.x2()), "§7Max corner X  (0–16)"));
        inv.setStack(16, uiGlint(Items.LIME_DYE,  "§ax2 +1▶", "§8Left:+1  Right:+4"));

        inv.setStack(23, uiGlint(Items.RED_DYE,   "§c◀ y2 −1", "§8Left:−1  Right:−4"));
        inv.setStack(24, ui(Items.CYAN_DYE,        "§fy2 = §e" + fmtCoord(box.y2()), "§7Max corner Y  (0–16)"));
        inv.setStack(25, uiGlint(Items.LIME_DYE,  "§ay2 +1▶", "§8Left:+1  Right:+4"));

        inv.setStack(32, uiGlint(Items.RED_DYE,   "§c◀ z2 −1", "§8Left:−1  Right:−4"));
        inv.setStack(33, ui(Items.CYAN_DYE,        "§fz2 = §e" + fmtCoord(box.z2()), "§7Max corner Z  (0–16)"));
        inv.setStack(34, uiGlint(Items.LIME_DYE,  "§az2 +1▶", "§8Left:+1  Right:+4"));

        // Bottom nav
        inv.setStack(45, uiGlint(Items.BARRIER,          "§cCancel", "§7Discard all changes and return"));
        inv.setStack(49, uiGlint(Items.NETHER_STAR,      "§a§lSave Box", "§7Commit changes and return to shape editor"));
        inv.setStack(53, uiGlint(Items.TOTEM_OF_UNDYING, "§eReset to Original", "§7Undo all nudges — restore the box as it was when this screen opened"));
        return inv;
    }

    private static String fmtCoord(float v) {
        if (v == (int) v) return String.valueOf((int) v);
        String s = String.format("%.3f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Handles clicks in the Box Nudge Editor. */
    private static void handleBoxNudgeClick(ServerPlayerEntity player, GuiState state, int slot, int button) {
        String id = state.editingId();
        int boxIdx = state.shapeBoxPage();
        int rp = state.page();

        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, rp); return; }

        SlotData.ShapeBox orig = (d.shapeBoxes != null && boxIdx < d.shapeBoxes.size())
            ? d.shapeBoxes.get(boxIdx) : null;
        SlotData.ShapeBox work = BOX_NUDGE_WORK.getOrDefault(player.getUuid(), orig);
        if (work == null) { reopenShapeEditor(player, id, rp, 0); return; }

        // step: 1 unit = 1/16 block. Left-click = 1, right-click = 4.
        float step = button == 1 ? 4f : 1f;

        SlotData.ShapeBox updated = switch (slot) {
            case 10 -> nudgeBox(work, -step, 0, 0, 0, 0, 0);
            case 12 -> nudgeBox(work,  step, 0, 0, 0, 0, 0);
            case 19 -> nudgeBox(work, 0, -step, 0, 0, 0, 0);
            case 21 -> nudgeBox(work, 0,  step, 0, 0, 0, 0);
            case 28 -> nudgeBox(work, 0, 0, -step, 0, 0, 0);
            case 30 -> nudgeBox(work, 0, 0,  step, 0, 0, 0);
            case 14 -> nudgeBox(work, 0, 0, 0, -step, 0, 0);
            case 16 -> nudgeBox(work, 0, 0, 0,  step, 0, 0);
            case 23 -> nudgeBox(work, 0, 0, 0, 0, -step, 0);
            case 25 -> nudgeBox(work, 0, 0, 0, 0,  step, 0);
            case 32 -> nudgeBox(work, 0, 0, 0, 0, 0, -step);
            case 34 -> nudgeBox(work, 0, 0, 0, 0, 0,  step);
            default -> null;
        };

        if (updated != null) {
            BOX_NUDGE_WORK.put(player.getUuid(), updated);
            refreshScreen(player, buildBoxNudgeEditor(d, updated, boxIdx));
            return;
        }

        if (slot == 0 || slot == 45) {
            BOX_NUDGE_WORK.remove(player.getUuid());
            reopenShapeEditor(player, id, rp, boxIdx / 9);
            return;
        }

        if (slot == 49) {
            if (orig == null) { reopenShapeEditor(player, id, rp, 0); return; }
            UndoManager.pushUndoMutation(id, d, "nudgebox#" + boxIdx, player.getUuid());
            final SlotData.ShapeBox finalWork = work;
            SlotManager.update(id, dd -> {
                if (dd.shapeBoxes == null || boxIdx >= dd.shapeBoxes.size()) return dd;
                java.util.List<SlotData.ShapeBox> nb = new java.util.ArrayList<>(dd.shapeBoxes);
                nb.set(boxIdx, finalWork);
                return dd.withShapeBoxes(nb);
            });
            com.customblocks.block.SlotBlock.invalidateShape(d.index);
            SlotManager.saveAll();
            broadcastShape(player.getServer(), SlotManager.getById(id));
            BOX_NUDGE_WORK.remove(player.getUuid());
            send(player, "§a[Shape] Box #" + boxIdx + " saved.");
            reopenShapeEditor(player, id, rp, boxIdx / 9);
            return;
        }

        if (slot == 53 && orig != null) {
            BOX_NUDGE_WORK.put(player.getUuid(), orig);
            refreshScreen(player, buildBoxNudgeEditor(d, orig, boxIdx));
        }
    }

    private static SlotData.ShapeBox nudgeBox(SlotData.ShapeBox b,
            float dx1, float dy1, float dz1, float dx2, float dy2, float dz2) {
        return new SlotData.ShapeBox(
            clamp016(b.x1() + dx1), clamp016(b.y1() + dy1), clamp016(b.z1() + dz1),
            clamp016(b.x2() + dx2), clamp016(b.y2() + dy2), clamp016(b.z2() + dz2));
    }

    private static float clamp016(float v) { return Math.max(0f, Math.min(16f, v)); }

    private static SimpleInventory buildShapeEditor(SlotData d, int boxPage) {
        SimpleInventory inv = new SimpleInventory(54);
        List<SlotData.ShapeBox> boxes = d.shapeBoxes!=null?d.shapeBoxes:List.of();
        Item[] pItems = {Items.GRASS_BLOCK,Items.SMOOTH_STONE_SLAB,Items.STONE_SLAB,Items.MOSS_CARPET,Items.COBBLESTONE_WALL,Items.COMPARATOR,Items.COMPARATOR,Items.GLASS_PANE,Items.OAK_TRAPDOOR,Items.OAK_FENCE,Items.OAK_STAIRS,Items.END_ROD};
        inv.setStack(0, uiGlint(Items.ECHO_SHARD,"§c◀ Back to Editor","§8Return to the block editor"));
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
        for (int i=0;i<9&&(bstart+i)<boxes.size();i++) { SlotData.ShapeBox b=boxes.get(bstart+i); inv.setStack(28+i,ui(Items.STRUCTURE_VOID,"§e§lCustom Box #"+(bstart+i),"§7"+b.toDisplayString(),"§c§oLeft-click: DELETE  §7|  §aRight-click: NUDGE/edit")); }
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
        inv.setStack(0, uiGlint(Items.ECHO_SHARD,"§c◀ Back to Editor","§8(or press ESC)"));
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
        inv.setStack(45,uiGlint(Items.ECHO_SHARD,"§c◀ Back to Editor","§8(or press ESC)"));
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
        inv.setStack(0, uiGlint(Items.ECHO_SHARD, "§c◀ Back to Face Editor", "§8(or press ESC)"));
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

        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back to Face Editor", "§8Return without copying"));
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

        inv.setStack(0, uiGlint(Items.ECHO_SHARD, "§c◀ Back to Face Choice", "§8Return to face selection"));
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
        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back to Face Choice", "§8Return without copying"));
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

        inv.setStack(0, uiGlint(Items.ECHO_SHARD, "§c◀ Back to Editor", "§8Closes without saving"));

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

        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back to Editor"));
        inv.setStack(49, uiGlint(Items.DRAGON_EGG, "§6§lSave & Apply",
            "§7Saves changes and sends them",
            "§7to all players.",
            "",
            "§e§lClick to save."));

        return inv;
    }

    // ── Small helpers ─────────────────────────────────────────────────────────

    private static SimpleInventory buildRecentGui(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back"));
        inv.setStack(49, uiGlint(Items.CLOCK, "§b§lRecently Edited", "§7Click any block to reopen it in the editor."));
        int invSlot = 10;
        for (String id : RECENT_BLOCKS.getOrDefault(player.getUuid(), new ArrayDeque<>())) {
            SlotData d = SlotManager.getById(id);
            if (d == null) continue;
            inv.setStack(invSlot, uiGlint(Items.CLOCK, "§f" + d.displayName, "§7ID: §b" + d.customId, "§8Click to edit"));
            invSlot++;
            if (invSlot == 17) invSlot = 19;
            if (invSlot > 34) break;
        }
        return inv;
    }

    private static SimpleInventory buildFavoritesGui(ServerPlayerEntity player, int page) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        java.util.List<String> favIds = new ArrayList<>(com.customblocks.core.FavoritesManager.validatedSet(player.getUuid()));
        favIds.sort(String::compareToIgnoreCase);
        int start = Math.max(0, page) * 28;
        int end = Math.min(favIds.size(), start + 28);
        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back"));
        inv.setStack(49, uiGlint(Items.TOTEM_OF_UNDYING, "§6§lFavorites", "§7Saved: §f" + favIds.size()));
        if (page > 0) inv.setStack(47, uiGlint(Items.ARROW, "§7◀ Previous"));
        if (end < favIds.size()) inv.setStack(51, uiGlint(Items.ARROW, "§7Next ▶"));
        for (int i = start, invSlot = 10; i < end; i++, invSlot++) {
            if (invSlot == 17) invSlot = 19;
            if (invSlot == 26) invSlot = 28;
            if (invSlot > 34) break;
            SlotData d = SlotManager.getById(favIds.get(i));
            if (d == null) continue;
            inv.setStack(invSlot, uiGlint(Items.GOLD_INGOT, "§f" + d.displayName, "§7ID: §b" + d.customId, "§8Click to edit"));
        }
        return inv;
    }

    private static SimpleInventory buildSafetyCenterGui(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back"));
        inv.setStack(20, uiGlint(Items.RECOVERY_COMPASS, "§eUndo / Redo", "§7Undo: §f" + com.customblocks.core.UndoManager.undoSize(player.getUuid()), "§7Redo: §f" + com.customblocks.core.UndoManager.redoSize(player.getUuid())));
        inv.setStack(22, uiGlint(Items.TOTEM_OF_UNDYING, "§aRecover Deleted Blocks", "§8Open recovery tools"));
        inv.setStack(24, uiGlint(Items.CHEST, "§bBackups", "§7Saved backups: §f" + com.customblocks.core.BackupManager.list().size()));
        return inv;
    }

    private static SimpleInventory buildHistoryGui(int page) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        java.util.List<com.customblocks.core.HistoryTracker.Entry> entries = com.customblocks.core.HistoryTracker.latest(28);
        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back"));
        inv.setStack(49, uiGlint(Items.KNOWLEDGE_BOOK, "§e§lSession History", "§7Entries: §f" + entries.size()));
        for (int i = 0, invSlot = 10; i < entries.size() && i < 28; i++, invSlot++) {
            if (invSlot == 17) invSlot = 19;
            if (invSlot == 26) invSlot = 28;
            if (invSlot > 34) break;
            var entry = entries.get(i);
            inv.setStack(invSlot, ui(Items.PAPER, "§f" + entry.blockId(), "§7" + stripFormattingCodes(entry.toDisplayString())));
        }
        return inv;
    }

    private static final int SNAPSHOTS_PER_PAGE = 18;

    private static SimpleInventory buildSnapshotsGui(int page) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        java.util.List<com.customblocks.core.SnapshotManager.SnapshotMeta> snaps = com.customblocks.core.SnapshotManager.list();
        int total = snaps.size();
        int offset = page * SNAPSHOTS_PER_PAGE;

        inv.setStack(4, uiGlint(Items.CLOCK, "§6§lSnapshots",
            "§7Total: §f" + total,
            "§7Page §f" + (page + 1),
            "§8Click a snapshot to restore it."));

        int[] slots = { 10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31 };
        for (int i = 0; i < slots.length; i++) {
            int idx = offset + i;
            if (idx >= total) break;
            com.customblocks.core.SnapshotManager.SnapshotMeta snap = snaps.get(idx);
            String reason = snap.reason();
            String ts = snap.timestamp();
            long kb = snap.sizeBytes() / 1024;
            net.minecraft.item.Item icon = reason.startsWith("auto") ? Items.CLOCK
                    : reason.startsWith("pre_restore") ? Items.ORANGE_DYE
                    : Items.LIME_DYE;
            inv.setStack(slots[i], uiGlint(icon,
                "§f" + snap.filename(),
                "§7Time:   §e" + ts,
                "§7Reason: §b" + reason,
                "§7Size:   §f" + kb + " KB",
                "§aClick to RESTORE this snapshot"));
        }

        if (page > 0)                              inv.setStack(45, uiGlint(Items.ARROW, "§7◀ Prev Page"));
        if (offset + SNAPSHOTS_PER_PAGE < total)   inv.setStack(53, uiGlint(Items.ARROW, "§7Next Page ▶"));
        inv.setStack(49, uiGlint(Items.ECHO_SHARD, "§c◀ Back"));
        return inv;
    }

    private static void handleSnapshotsGuiClick(ServerPlayerEntity player, GuiState state, int slot) {
        int page = state.page();
        if (slot == 49) { handleEscBack(player); return; }
        if (slot == 45 && page > 0) {
            openSnapshotsGui(player, page - 1); return;
        }
        if (slot == 53) {
            openSnapshotsGui(player, page + 1); return;
        }
        int[] slots = { 10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31 };
        for (int i = 0; i < slots.length; i++) {
            if (slot == slots[i]) {
                int idx = page * SNAPSHOTS_PER_PAGE + i;
                java.util.List<com.customblocks.core.SnapshotManager.SnapshotMeta> snaps = com.customblocks.core.SnapshotManager.list();
                if (idx >= snaps.size()) { playError(player); return; }
                String filename = snaps.get(idx).filename();
                boolean ok = com.customblocks.core.SnapshotManager.restore(filename);
                if (ok) {
                    com.customblocks.network.NetworkManager.broadcastFullSync(player.getServer());
                    FeedbackHelper.actionBar(player, "§a§l✔ §r§aRestored snapshot: §f" + filename);
                    send(player, "§a[Snapshots] Restored from §f" + filename + "§a.");
                    openSnapshotsGui(player, 0);
                } else {
                    playError(player);
                    send(player, "§c[Snapshots] Failed to restore §f" + filename + "§c.");
                }
                return;
            }
        }
    }

    // ── V4-18 Deleted Blocks GUI ──────────────────────────────────────────────

    private static final int TRASH_PER_PAGE = 18;

    private static SimpleInventory buildDeletedBlocksGui(int page) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        java.util.List<com.customblocks.core.TrashManager.TrashEntry> entries = com.customblocks.core.TrashManager.list();
        int total = entries.size();
        int offset = page * TRASH_PER_PAGE;

        inv.setStack(4, uiGlint(Items.RED_DYE, "§c§lDeleted Blocks",
            "§7Total: §f" + total,
            "§7Page §f" + (page + 1),
            "§aLeft-click to restore.",
            "§cRight-click to permanently delete."));

        int[] slots = { 10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31 };
        for (int i = 0; i < slots.length; i++) {
            int idx = offset + i;
            if (idx >= total) break;
            com.customblocks.core.TrashManager.TrashEntry e = entries.get(idx);
            String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(e.deletedAt()));
            inv.setStack(slots[i], uiGlint(Items.BARRIER,
                "§f" + e.displayName(),
                "§7ID:      §b" + e.originalId(),
                "§7Deleted: §e" + ts,
                "§aLeft-click §7to restore",
                "§cRight-click §7to delete permanently"));
        }

        if (page > 0)                          inv.setStack(45, uiGlint(Items.ARROW, "§7◀ Prev Page"));
        if (offset + TRASH_PER_PAGE < total)   inv.setStack(53, uiGlint(Items.ARROW, "§7Next Page ▶"));
        inv.setStack(49, uiGlint(Items.ECHO_SHARD, "§c◀ Back"));
        return inv;
    }

    private static void handleDeletedBlocksGuiClick(ServerPlayerEntity player, GuiState state, int slot, int button) {
        int page = state.page();
        if (slot == 49) { handleEscBack(player); return; }
        if (slot == 45 && page > 0) { openDeletedBlocksGui(player, page - 1); return; }
        if (slot == 53) { openDeletedBlocksGui(player, page + 1); return; }

        int[] slots = { 10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31 };
        for (int i = 0; i < slots.length; i++) {
            if (slot == slots[i]) {
                int idx = page * TRASH_PER_PAGE + i;
                java.util.List<com.customblocks.core.TrashManager.TrashEntry> entries = com.customblocks.core.TrashManager.list();
                if (idx >= entries.size()) { playError(player); return; }
                String id = entries.get(idx).originalId();
                if (button == 1) {
                    // Right-click = permanent delete
                    com.customblocks.core.TrashManager.permanentlyDelete(id);
                    FeedbackHelper.actionBar(player, "§c§l✘ §r§cPermanently deleted: §f" + id);
                    send(player, "§c[Trash] Permanently deleted §f" + id + "§c.");
                } else {
                    // Left-click = restore
                    boolean ok = com.customblocks.core.TrashManager.restore(id);
                    if (ok) {
                        com.customblocks.network.NetworkManager.broadcastFullSync(player.getServer());
                        FeedbackHelper.actionBar(player, "§a§l✔ §r§aRestored: §f" + id);
                        send(player, "§a[Trash] Restored §f" + id + "§a.");
                    } else {
                        playError(player);
                        send(player, "§c[Trash] Could not restore §f" + id + "§c.");
                    }
                }
                openDeletedBlocksGui(player, page);
                return;
            }
        }
    }

    private static SimpleInventory buildScriptGui(int page) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        java.util.List<String> scripts = com.customblocks.core.MacroManager.listMacros();
        int start = page * 28;
        int end   = Math.min(scripts.size(), start + 28);
        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back"));
        inv.setStack(49, uiGlint(Items.WRITABLE_BOOK, "§b§lScripts", "§7Total: §f" + scripts.size()));
        if (page > 0)             inv.setStack(47, uiGlint(Items.ARROW, "§7◀ Previous"));
        if (end < scripts.size()) inv.setStack(51, uiGlint(Items.ARROW, "§7Next ▶"));
        for (int i = start, invSlot = 10; i < end; i++, invSlot++) {
            if (invSlot == 17) invSlot = 19;
            if (invSlot == 26) invSlot = 28;
            if (invSlot > 34) break;
            String name = scripts.get(i);
            long lastRun = com.customblocks.core.MacroManager.lastRunTime(name);
            java.util.List<String> steps = com.customblocks.core.MacroManager.loadMacro(name);
            int stepCount = steps != null ? steps.size() : 0;
            String lastRunStr = lastRun > 0
                ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(lastRun))
                : "Never";
            inv.setStack(invSlot, ui(Items.BOOK, "§f" + name,
                "§7Steps: §f" + stepCount,
                "§7Last run: §f" + lastRunStr,
                "§aLeft-click §7to run  §cRight-click §7to view steps"));
        }
        return inv;
    }

    private static SimpleInventory buildScriptSummaryGui(com.customblocks.core.MacroManager.ScriptRunResult result) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back to scripts"));
        inv.setStack(4, uiGlint(result.failed() == 0 ? Items.EMERALD : Items.REDSTONE,
            (result.failed() == 0 ? "§a§l✔ " : "§c§l✘ ") + result.name(),
            "§7Ran: §f" + result.ran() + "§7/§f" + result.steps().size() + " steps",
            result.failed() > 0 ? "§cFailed: §f" + result.failed() : "§aAll steps passed"));
        inv.setStack(49, uiGlint(Items.WRITABLE_BOOK, "§e▶ Run Again", "§7Re-run this script"));
        inv.setStack(51, uiGlint(Items.BARRIER, "§c🗑 Delete Script", "§7Permanently removes §f" + result.name(), "§8This cannot be undone."));
        for (int i = 0, invSlot = 10; i < result.steps().size() && i < 28; i++, invSlot++) {
            if (invSlot == 17) invSlot = 19;
            if (invSlot == 26) invSlot = 28;
            if (invSlot > 34) break;
            boolean ok = i < result.passed().size() && Boolean.TRUE.equals(result.passed().get(i));
            inv.setStack(invSlot, ui(ok ? Items.LIME_DYE : Items.RED_DYE,
                (ok ? "§a" : "§c") + "Step " + (i + 1),
                "§7" + result.steps().get(i)));
        }
        return inv;
    }

    private static SimpleInventory buildAiHubGui() {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        inv.setStack(0,  uiGlint(Items.ECHO_SHARD, "§c◀ Back"));
        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back"));

        boolean workerReady = !CustomBlocksConfig.aiWorkerUrl.isEmpty() && !CustomBlocksConfig.aiServerToken.isEmpty();

        // Header
        inv.setStack(4, uiGlint(Items.AMETHYST_CLUSTER, "§d§l✦ AI Chat",
            workerReady ? "§aGroq AI is connected and ready." : "§cAI Worker not configured. Set aiWorkerUrl + aiServerToken in config.",
            "§7Ask anything about your custom blocks."));

        // Ask button
        inv.setStack(22, workerReady
            ? uiGlint(Items.BOOK, "§e§l✎ Ask AI",
                "§7Close GUI, type your question in chat.",
                "§8Example: §fHow do I create a glowing block?",
                "§8Example: §fList my blocks with fire in the name.")
            : ui(Items.BARRIER, "§cNot configured",
                "§7Set §faiWorkerUrl §7and §faiServerToken §7in config.",
                "§8Open the Config GUI to enter values."));

        // Tips row
        inv.setStack(10, ui(Items.BARRIER, "§cBlock Commands",
            "§f/cb ai \"delete blocks starting with mob_\"",
            "§f/cb ai \"list all blocks\"",
            "§f/cb ai \"set glow 10 on block X\"",
            "§8These run without needing the AI worker."));
        inv.setStack(13, ui(Items.SPYGLASS, "§bAI Chat",
            "§7Ask open questions, get advice,",
            "§7or describe what you want to build.",
            "§8Powered by Groq · responses in chat."));
        inv.setStack(16, ui(Items.BOOK, "§7Tips",
            "§7Commands like delete/rename/glow work offline.",
            "§7Freeform questions need the Worker.",
            "§7/cb undo §7reverses command actions."));

        return inv;
    }

    public static void postAiQuery(ServerPlayerEntity player, String question) {
        String url = CustomBlocksConfig.aiWorkerUrl;
        String token = CustomBlocksConfig.aiServerToken;
        if (url.isEmpty() || token.isEmpty()) {
            send(player, "§c[AI] Worker not configured. Set aiWorkerUrl and aiServerToken in config.");
            return;
        }
        send(player, "§d[AI] Thinking…");
        MinecraftServer srv = player.getServer();
        UUID uuid = player.getUuid();
        EXECUTOR.submit(() -> {
            try {
                String body = "{\"question\":" + new com.google.gson.JsonPrimitive(question).toString()
                        + ",\"playerUuid\":\"" + uuid + "\"}";
                java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(10)).build();
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(url.endsWith("/") ? url + "ai" : url + "/ai"))
                        .timeout(java.time.Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .header("x-cb-token", token)
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                        .build();
                java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                String reply;
                try {
                    com.google.gson.JsonObject jo = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
                    reply = jo.has("reply") ? jo.get("reply").getAsString() : resp.body();
                } catch (Exception pe) { reply = resp.body(); }
                final String finalReply = reply;
                srv.execute(() -> {
                    ServerPlayerEntity p = srv.getPlayerManager().getPlayer(uuid);
                    if (p != null) {
                        p.sendMessage(net.minecraft.text.Text.literal("§d[AI] §f" + finalReply), false);
                    }
                });
            } catch (Exception e) {
                srv.execute(() -> {
                    ServerPlayerEntity p = srv.getPlayerManager().getPlayer(uuid);
                    if (p != null) send(p, "§c[AI] Request failed: " + e.getMessage());
                });
            }
        });
    }

    private static SimpleInventory buildCustomColorStudioGui(ServerPlayerEntity player, String initialHex) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        inv.setStack(0,  uiGlint(Items.ECHO_SHARD, "§c◀ Back"));
        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back"));
        inv.setStack(4,  uiGlint(Items.MAGENTA_DYE, "§d§lColor Studio",
            "§7Click any color to receive square + triangle",
            "§8" + ColorLibrary.ALL.size() + " named colors · /cb customcolor <hex> for custom"));

        // Recent colors — slots 1-3, 5-7 (up to 6)
        java.util.List<String> recent = com.customblocks.core.PlayerPaletteManager.getRecent(player.getUuid());
        int[] recentSlots = {1, 2, 3, 5, 6, 7};
        for (int i = 0; i < recentSlots.length && i < recent.size(); i++) {
            String hex = recent.get(i).toUpperCase(java.util.Locale.ROOT);
            inv.setStack(recentSlots[i], ui(Items.FIREWORK_STAR, "§e#" + hex, "§7Recent color  §aClick to get tools"));
        }

        // Named colors grid — slots 9-35 (27 colors) then 36-37 (remaining 2)
        java.util.List<ColorLibrary.LibColor> colors = ColorLibrary.ALL;
        for (int i = 0; i < colors.size() && i < 29; i++) {
            ColorLibrary.LibColor c = colors.get(i);
            inv.setStack(9 + i, ui(customColorDyeItem(c), "§f" + c.name(),
                c.rgbLabel(), "§aClick to get square + triangle"));
        }

        inv.setStack(49, uiGlint(Items.BOOK, "§7Color Guide",
            "§7/cb customcolor §f<hex> §7— instant custom hex",
            "§7/cb palette §7— save your favorite colors"));
        return inv;
    }

    private static Item customColorDyeItem(ColorLibrary.LibColor c) {
        return switch (c.name().toLowerCase(java.util.Locale.ROOT)) {
            case "red", "crimson", "maroon" -> Items.RED_DYE;
            case "orange", "coral", "peach" -> Items.ORANGE_DYE;
            case "yellow", "gold", "butter" -> Items.YELLOW_DYE;
            case "lime", "mint" -> Items.LIME_DYE;
            case "green", "forest" -> Items.GREEN_DYE;
            case "cyan" -> Items.CYAN_DYE;
            case "blue", "navy", "baby blue", "indigo" -> Items.BLUE_DYE;
            case "purple", "lavender" -> Items.PURPLE_DYE;
            case "magenta" -> Items.MAGENTA_DYE;
            case "pink", "rose" -> Items.PINK_DYE;
            case "white" -> Items.WHITE_DYE;
            case "light gray" -> Items.LIGHT_GRAY_DYE;
            case "gray", "dark gray" -> Items.GRAY_DYE;
            case "black" -> Items.BLACK_DYE;
            case "brown" -> Items.BROWN_DYE;
            default -> Items.PAPER;
        };
    }

    private static SimpleInventory buildCacheDashboardGui(ServerPlayerEntity player, int tab) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        String hash = com.customblocks.network.ResourcePackServer.getHash();
        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back"));
        inv.setStack(20, uiGlint(Items.CHEST, "§bResource Pack Cache", "§7Hash: §f" + (hash != null ? hash : "Not built")));
        inv.setStack(22, uiGlint(Items.CLOCK, "§7Deferred Reload Safety", "§7Players with open GUIs are protected from interrupting reload pushes."));
        inv.setStack(24, uiGlint(Items.GLOBE_BANNER_PATTERN, "§7Marketplace Cache", "§7Session entries: §f" + MARKET_CACHE.getOrDefault(player.getUuid(), java.util.List.of()).size()));
        return inv;
    }

    private static SimpleInventory buildAuditGui(int page) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        java.util.List<String> results = com.customblocks.core.DiagnosticsHelper.runGuiAudit();
        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back"));
        inv.setStack(49, uiGlint(Items.WRITABLE_BOOK, "§6§lGUI Audit", "§7Checks: §f" + results.size()));
        for (int i = 0, invSlot = 10; i < results.size() && i < 28; i++, invSlot++) {
            if (invSlot == 17) invSlot = 19;
            if (invSlot == 26) invSlot = 28;
            if (invSlot > 34) break;
            String line = results.get(i);
            boolean pass = line.startsWith("PASS:");
            inv.setStack(invSlot, ui(pass ? Items.LIME_DYE : Items.RED_DYE, (pass ? "§a" : "§c") + line.substring(5)));
        }
        return inv;
    }

    private static SimpleInventory buildAchievementsGui(ServerPlayerEntity player, int page) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        java.util.Set<String> unlocked = com.customblocks.core.AchievementManager.getUnlocked(player.getUuid());
        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back"));
        inv.setStack(49, uiGlint(Items.NETHER_STAR, "§6§lAchievements", "§7Unlocked: §f" + unlocked.size() + "§7/§f" + com.customblocks.core.AchievementManager.Achievement.values().length));
        int invSlot = 10;
        for (com.customblocks.core.AchievementManager.Achievement achievement : com.customblocks.core.AchievementManager.Achievement.values()) {
            boolean isUnlocked = unlocked.contains(achievement.id);
            inv.setStack(invSlot, ui(isUnlocked ? Items.EMERALD : Items.COAL,
                (isUnlocked ? "§a" : "§7") + stripFormattingCodes(achievement.title),
                "§7" + stripFormattingCodes(achievement.subtitle)));
            invSlot++;
            if (invSlot == 17) invSlot = 19;
            if (invSlot > 34) break;
        }
        return inv;
    }

    private static void handleSimpleBackOnly(ServerPlayerEntity player, int slot) {
        if (slot == 45 || slot == 49 || slot == 0) handleEscBack(player);
    }

    private static void handleRecentGuiClick(ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 45 || slot == 49) { handleEscBack(player); return; }
        java.util.List<String> recent = new ArrayList<>(RECENT_BLOCKS.getOrDefault(player.getUuid(), new ArrayDeque<>()));
        int idx = gridIndex(slot);
        if (idx >= 0 && idx < recent.size()) openEditor(player, recent.get(idx), 0);
    }

    private static void handleFavoritesGuiClick(ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 45 || slot == 49) { handleEscBack(player); return; }
        java.util.List<String> favIds = new ArrayList<>(com.customblocks.core.FavoritesManager.validatedSet(player.getUuid()));
        favIds.sort(String::compareToIgnoreCase);
        if (slot == 47 && state.page() > 0) { openFavoritesGui(player, state.page() - 1); return; }
        if (slot == 51 && (state.page() + 1) * 28 < favIds.size()) { openFavoritesGui(player, state.page() + 1); return; }
        int idx = gridIndex(slot);
        int actual = state.page() * 28 + idx;
        if (idx >= 0 && actual < favIds.size()) openEditor(player, favIds.get(actual), 0);
    }

    private static void handleSafetyCenterClick(ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 45 || slot == 49) { handleEscBack(player); return; }
        if (slot == 20) { openUndoPicker(player, 0); return; }
        if (slot == 22) { openRecoverGui(player, 0); return; }
    }

    private static void handleHistoryGuiClick(ServerPlayerEntity player, GuiState state, int slot) {
        handleSimpleBackOnly(player, slot);
    }

    private static void handleScriptGuiClick(ServerPlayerEntity player, GuiState state, int slot, int button) {
        int page = state.page();
        if (slot == 45 || slot == 49) { handleEscBack(player); return; }
        if (slot == 47 && page > 0)   { openScriptGui(player, page - 1); return; }
        if (slot == 51)               { openScriptGui(player, page + 1); return; }
        java.util.List<String> scripts = com.customblocks.core.MacroManager.listMacros();
        int idx = gridIndex(slot);
        int actual = page * 28 + idx;
        if (idx < 0 || actual >= scripts.size()) return;
        String name = scripts.get(actual);
        if (button == 1) {
            // Right-click → show script steps without running
            java.util.List<String> steps = com.customblocks.core.MacroManager.loadMacro(name);
            if (steps == null || steps.isEmpty()) { send(player, "§c[Script] No steps found for §f" + name); return; }
            send(player, "§b[Script] §f" + name + " §7(" + steps.size() + " steps):");
            for (int i = 0; i < steps.size(); i++) send(player, "  §7" + (i + 1) + ". §f" + steps.get(i));
        } else {
            // Left-click → run and show summary
            com.customblocks.core.MacroManager.ScriptRunResult result = com.customblocks.core.MacroManager.runScript(player, name);
            if (result != null) openScriptSummary(player, result);
        }
    }

    private static void handleScriptSummaryClick(ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 45) { openScriptGui(player, 0); return; }
        com.customblocks.core.MacroManager.ScriptRunResult last = LAST_SCRIPT_RESULTS.get(player.getUuid());
        if (slot == 49 && last != null) {
            // Run Again
            com.customblocks.core.MacroManager.ScriptRunResult result = com.customblocks.core.MacroManager.runScript(player, last.name());
            if (result != null) openScriptSummary(player, result);
        } else if (slot == 51 && last != null) {
            // Delete — use pending input for confirmation
            PENDING.put(player.getUuid(), new PendingInput(InputAction.CONFIRM_SCRIPT_DELETE, last.name(), null, null, null, 0));
            closeForPrompt(player);
            send(player, "§c[Script] Type §fconfirm §cto permanently delete §f" + last.name() + "§c, or anything else to cancel:");
        }
    }

    private static void handleAiHubClick(ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 0 || slot == 45) { handleEscBack(player); return; }
        if (slot == 22 && !CustomBlocksConfig.aiWorkerUrl.isEmpty() && !CustomBlocksConfig.aiServerToken.isEmpty()) {
            PENDING.put(player.getUuid(), new PendingInput(InputAction.AI_CHAT_QUERY, null, null, null, null, 0));
            closeForPrompt(player);
            send(player, "§d[AI] Type your question in chat (or §ccancel§d to abort):");
        }
    }

    private static void handleCustomColorStudioClick(ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 0 || slot == 45 || slot == 49) { handleEscBack(player); return; }

        // Recent colors in top row (slots 1-3, 5-7)
        java.util.List<String> recent = com.customblocks.core.PlayerPaletteManager.getRecent(player.getUuid());
        int[] recentSlots = {1, 2, 3, 5, 6, 7};
        for (int i = 0; i < recentSlots.length && i < recent.size(); i++) {
            if (slot == recentSlots[i]) {
                com.customblocks.command.CustomBlockCommand.cmdGiveCustomColorToolsInternal(
                    player.getCommandSource(), recent.get(i));
                openCustomColorStudio(player, recent.get(i));
                return;
            }
        }

        // Named color grid — slots 9-37 (up to 29 colors)
        if (slot >= 9 && slot <= 37) {
            int idx = slot - 9;
            java.util.List<ColorLibrary.LibColor> colors = ColorLibrary.ALL;
            if (idx < colors.size()) {
                ColorLibrary.LibColor c = colors.get(idx);
                com.customblocks.command.CustomBlockCommand.cmdGiveCustomColorToolsInternal(
                    player.getCommandSource(), c.hex());
                openCustomColorStudio(player, c.hex().replace("#", ""));
            }
        }
    }

    private static void handleCacheDashboardClick(ServerPlayerEntity player, GuiState state, int slot) {
        handleSimpleBackOnly(player, slot);
    }

    private static void handleAuditGuiClick(ServerPlayerEntity player, GuiState state, int slot) {
        handleSimpleBackOnly(player, slot);
    }

    private static void handleAchievementsGuiClick(ServerPlayerEntity player, GuiState state, int slot) {
        handleSimpleBackOnly(player, slot);
    }

    private static int gridIndex(int slot) {
        if (slot >= 10 && slot <= 16) return slot - 10;
        if (slot >= 19 && slot <= 25) return 7 + (slot - 19);
        if (slot >= 28 && slot <= 34) return 14 + (slot - 28);
        if (slot >= 37 && slot <= 43) return 21 + (slot - 37);
        return -1;
    }

    private static void closeForPrompt(ServerPlayerEntity player) {
        REOPENING_SCREENS.add(player.getUuid());
        player.closeHandledScreen();
        REOPENING_SCREENS.remove(player.getUuid());
    }

    public static void openShortInputPrompt(ServerPlayerEntity player, PendingInput pending, String title, ItemStack promptItem, String initialText) {
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
            case "voiceMode" -> Items.PAINTING;
            // cloudShareUrl removed — was ENDER_PEARL (now hardcoded constant)
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
            // cloudShareUrl removed from GUI — hardcoded constant
            case "aiWorkerUrl" -> CustomBlocksConfig.aiWorkerUrl;
            case "aiServerToken" -> CustomBlocksConfig.aiServerToken;
            case "undoMode" -> CustomBlocksConfig.undoMode;
            case "colorToolBackgroundMode" -> CustomBlocksConfig.colorToolBackgroundMode;
            case "triangleGreenHex" -> CustomBlocksConfig.triangleGreenHex;
            case "triangleYellowHex" -> CustomBlocksConfig.triangleYellowHex;
            case "voiceMode" -> CustomBlocksConfig.voiceMode;
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

    /** 1.27 — returns blocks sorted according to the given player's sort preference. */
    private static List<SlotData> sortedBlocks(UUID playerUuid) {
        SortMode mode = PLAYER_SORT_PREFS.getOrDefault(playerUuid, SortMode.NAME_ASC);
        return SlotManager.sortedSlots(mode);
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
            1,
            null
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
    private static String normalizeFormattingCodes(String text) { return TextSanitizer.fix(text); }
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
        s.set(DataComponentTypes.CUSTOM_NAME,Text.literal(normalizeFormattingCodes(name)).styled(st->st.withItalic(false)));
        if(lore.length>0){List<Text> ll=new ArrayList<>();for(String l:lore)ll.add(lore(l));s.set(DataComponentTypes.LORE,new LoreComponent(ll));}
        return s;
    }
    static ItemStack uiGlint(Item item, String name, String... lore) { ItemStack s=ui(item,name,lore); s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,true); return s; }
    static Text lore(String t) { return Text.literal(normalizeFormattingCodes(t)).styled(s->s.withItalic(false)); }
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
        else inv.setStack(45, uiGlint(net.minecraft.item.Items.ECHO_SHARD, "§c← Cancel")); // Royal Directive
        if (end < conflicting.size()) inv.setStack(53, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§dNext Page →"));

        openScreenFromGuiState(player, GuiState.importConflict(root.getAsJsonObject("category").get("key").getAsString()).withPage(page), inv, Text.translatable("customblocks.gui.import_conflict.title"));
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
        openScreenFromGuiState(player, GuiState.uncategorizedPicker(page), buildCategoryDetail("Uncategorized", uncategorized, page), Text.translatable("customblocks.gui.uncategorized.title"));
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
        inv.setStack(22, uiGlint(net.minecraft.item.Items.ECHO_SHARD, "§c§lBack"));
        openScreenFromGuiState(player, GuiState.assignmentDecision(blockId, 0), inv, Text.translatable("customblocks.gui.assign_block.title"));
    }

    private static void handleAssignmentDecisionClick(net.minecraft.server.network.ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 11) {
            openCategoryPicker(player, state.editingId(), 0);
            return;
        }
        if (slot == 15) {
            PENDING_CATEGORIES.put(player.getUuid(), new java.util.concurrent.ConcurrentHashMap<>());
            PENDING_CATEGORIES.get(player.getUuid()).put("originBlockId", state.editingId());
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

        inv.setStack(45, uiGlint(net.minecraft.item.Items.ECHO_SHARD, "§c← Back")); // Royal Directive
        inv.setStack(47, page > 0 ? uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§d← Previous Page") : glass());
        inv.setStack(51, end < (isCustomTab ? customBlocks.size() : vanillaItems.size()) ? uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§dNext Page →") : glass());

        inv.setStack(48, uiGlint(net.minecraft.item.Items.GRASS_BLOCK, !isCustomTab ? "§a§lVanilla Items" : "§7Vanilla Items"));
        inv.setStack(50, uiGlint(net.minecraft.item.Items.PAINTING, isCustomTab ? "§a§lCustom Blocks" : "§7Custom Blocks"));

        openScreenFromGuiState(player, GuiState.categoryIconPicker(categoryKey, page, isCustomTab), inv, Text.translatable("customblocks.gui.icon_picker.title"));
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
        else inv.setStack(45, uiGlint(net.minecraft.item.Items.ECHO_SHARD, "§c<- Back")); // Royal Directive
        if (end < cats.size()) inv.setStack(53, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§dNext Page ->"));
        openScreenFromGuiState(player, GuiState.categoryPicker(blockId, page), inv, Text.translatable("customblocks.gui.category_picker.title"));
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
        else inv.setStack(45, uiGlint(net.minecraft.item.Items.ECHO_SHARD, "§c<- Back")); // Royal Directive
        if (end < cats.size()) inv.setStack(53, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§dNext Page ->"));
        inv.setStack(49, uiGlint(net.minecraft.item.Items.COMMAND_BLOCK, "§d§lCategory Controller", "§7Manage categories"));
        openScreenFromGuiState(player, GuiState.categoryBrowser(page), inv, Text.translatable("customblocks.gui.category_browser.title"));
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
        openScreenFromGuiState(player, GuiState.categoryDetail(categoryKey, page), buildCategoryDetail(categoryKey, blocks, page), Text.translatable("customblocks.gui.category_detail.title", categoryKey));
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
        else inv.setStack(45, uiGlint(net.minecraft.item.Items.ECHO_SHARD, "§c<- Back")); // Royal Directive
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
        
        inv.setStack(22, uiGlint(net.minecraft.item.Items.ECHO_SHARD, "§c§lBack"));
        openScreenFromGuiState(player, GuiState.categoryBlockContext(categoryKey, blockId, returnPage), inv, Text.translatable("customblocks.gui.block_options.title"));
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
        else inv.setStack(45, uiGlint(net.minecraft.item.Items.ECHO_SHARD, "§c<- Back")); // Royal Directive
        if (end < cats.size()) inv.setStack(53, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§dNext Page ->"));
        
        inv.setStack(48, uiGlint(net.minecraft.item.Items.MINECART, "§eMerge Categories", "§7Combine two categories into one"));
        inv.setStack(49, uiGlint(net.minecraft.item.Items.WRITABLE_BOOK, "§a§l+ New Category", "§7Click to create a category"));
        inv.setStack(50, uiGlint(net.minecraft.item.Items.EMERALD_BLOCK, "§aBulk Assign", "§7Assign multiple blocks at once"));
        
        openScreenFromGuiState(player, GuiState.categoryController(page), inv, Text.translatable("customblocks.gui.category_controller.title"));
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
            PENDING_CATEGORIES.get(player.getUuid()).remove("originBlockId");
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

        inv.setStack(45, uiGlint(net.minecraft.item.Items.ECHO_SHARD, "§c§lBack"));
        openScreenFromGuiState(player, GuiState.categoryEditor(categoryKey, tabIndex), inv, Text.translatable("customblocks.gui.category_editor.title").append(Text.literal(": §f" + cat.displayName())));
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
                PENDING_CATEGORIES.get(player.getUuid()).remove("originBlockId");
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
        else inv.setStack(45, uiGlint(net.minecraft.item.Items.ECHO_SHARD, "§c← Back")); // Royal Directive
        if (end < rows.size()) inv.setStack(53, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§dNext Page →"));
        inv.setStack(49, uiGlint(net.minecraft.item.Items.WRITABLE_BOOK, "§a§l+ New Subcategory",
                "§7Click to add a subcategory under §f" + parent.displayName()));

        openScreenFromGuiState(player, GuiState.subcategoryController(parentKey, page), inv,
                Text.translatable("customblocks.gui.subcategory.title", parent.displayName()));
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
            PENDING_CATEGORIES.get(player.getUuid()).remove("originBlockId");
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
            PENDING_CATEGORIES.get(player.getUuid()).remove("originBlockId");
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
        
        inv.setStack(22, uiGlint(net.minecraft.item.Items.ECHO_SHARD, "§c§lBack"));
        openScreenFromGuiState(player, GuiState.deleteCategoryMenu(categoryKey), inv, Text.translatable("customblocks.gui.delete_category.title", cat.displayName()));
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
        else inv.setStack(45, uiGlint(net.minecraft.item.Items.ECHO_SHARD, "§c← Back")); // Royal Directive
        if (end < cats.size()) inv.setStack(53, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§dNext Page →"));
        openScreenFromGuiState(player, GuiState.mergeCategoryPickerTarget(sourceKey, page), inv, Text.translatable("customblocks.gui.merge_target.title"));
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
        
        inv.setStack(22, uiGlint(net.minecraft.item.Items.ECHO_SHARD, "§c§lBack"));
        openScreenFromGuiState(player, GuiState.categoryStats(categoryKey), inv, Text.translatable("customblocks.gui.category_stats_dyn.title", cat.displayName()));
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
        
        inv.setStack(11, uiGlint(net.minecraft.item.Items.ECHO_SHARD, "§eAlphabetical (A-Z)")); // Royal Directive
        inv.setStack(13, uiGlint(net.minecraft.item.Items.CLOCK, "§eNewest First"));
        inv.setStack(15, uiGlint(net.minecraft.item.Items.NETHER_STAR, "§eOldest First")); // Royal Directive
        
        inv.setStack(22, uiGlint(net.minecraft.item.Items.ECHO_SHARD, "§c§lBack"));
        openScreenFromGuiState(player, GuiState.sortBlocksMenu(categoryKey), inv, Text.translatable("customblocks.gui.sort_blocks.title", cat.displayName()));
    }

    // ── 1.27 — Global picker sort menu ────────────────────────────────────────

    /** Maps SortMode ordinal → inventory slot (54-slot grid, rows 1–4 skipping border). */
    private static final int[] SORT_SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31};

    private static void openSortMenu(ServerPlayerEntity player, int returnPage) {
        pushBackStack(player.getUuid());
        SimpleInventory inv = buildSortMenu(player.getUuid());
        openScreenFromGuiState(player, GuiState.pickerSort(returnPage), inv,
                Text.literal("§e§l⬇ Sort Blocks"));
    }

    private static SimpleInventory buildSortMenu(UUID playerUuid) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        inv.setStack(4, ui(Items.HOPPER, "§e§l⬇ Sort Blocks",
                "§7Choose how the block list is sorted.",
                "§7Your preference is kept for this session."));
        SortMode active = PLAYER_SORT_PREFS.getOrDefault(playerUuid, SortMode.NAME_ASC);
        SortMode[] values = SortMode.values();
        for (int i = 0; i < values.length && i < SORT_SLOTS.length; i++) {
            SortMode m = values[i];
            boolean isCurrent = m == active;
            net.minecraft.item.Item icon = switch (m) {
                case NAME_ASC, NAME_DESC     -> Items.ECHO_SHARD;
                case INDEX_ASC, INDEX_DESC   -> Items.COMPASS;
                case RECENTLY_EDITED         -> Items.CLOCK;
                case ANIMATED_FIRST          -> Items.MUSIC_DISC_CAT;
                case BROKEN_FIRST            -> Items.BARRIER;
                case BY_CATEGORY             -> Items.BOOKSHELF;
                case BY_SIZE                 -> Items.SHULKER_BOX;
                case LOCKED_FIRST            -> Items.IRON_DOOR;
                case BY_GLOW                 -> Items.GLOWSTONE;
                case BY_SOUND                -> Items.NOTE_BLOCK;
            };
            if (isCurrent) {
                ItemStack s = new ItemStack(icon);
                s.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§a§l✔ " + m.label).styled(st -> st.withItalic(false)));
                List<String> ll = new ArrayList<>(List.of("§7" + m.description, "§a§l← Currently active"));
                s.set(DataComponentTypes.LORE, new LoreComponent(ll.stream().map(l -> (Text) lore(l)).toList()));
                s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
                inv.setStack(SORT_SLOTS[i], s);
            } else {
                inv.setStack(SORT_SLOTS[i], ui(icon, "§f" + m.label, "§7" + m.description, "§aClick to apply"));
            }
        }
        inv.setStack(49, uiGlint(Items.ECHO_SHARD, "§c◀ Back", "§8Return to block list"));
        return inv;
    }

    private static void handleSortBlocksMenuClick(net.minecraft.server.network.ServerPlayerEntity player, GuiState state, int slot) {
        // 1.27 — global picker sort menu
        if ("__picker_sort__".equals(state.editingId())) {
            if (slot == 49 || slot == 0) {
                handleEscBack(player);
                return;
            }
            SortMode[] values = SortMode.values();
            for (int i = 0; i < values.length && i < SORT_SLOTS.length; i++) {
                if (SORT_SLOTS[i] == slot) {
                    SortMode chosen = values[i];
                    PLAYER_SORT_PREFS.put(player.getUuid(), chosen);
                    playSuccess(player);
                    FeedbackHelper.actionBar(player, "§eSorted by: §f" + chosen.label);
                    // Return to picker at stored return page with new sort applied
                    int returnPage = state.page();
                    STATES.put(player.getUuid(), GuiState.picker(returnPage));
                    refreshScreen(player, buildPicker(player.getUuid(), returnPage, false));
                    return;
                }
            }
            return;
        }
        // Legacy category-sort stub (existing code)
        if (slot == 22) { handleEscBack(player); return; }
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
            net.minecraft.item.ItemStack stack = displayStackFor(d);
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
        else inv.setStack(45, uiGlint(net.minecraft.item.Items.ECHO_SHARD, "§c← Back")); // Royal Directive
        inv.setStack(49, uiGlint(net.minecraft.item.Items.EMERALD_BLOCK, "§a§lConfirm Bulk Assign", "§7Assign " + selected.size() + " blocks to a category"));
        if (end < blocks.size()) inv.setStack(53, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§dNext Page →"));
        
        openScreenFromGuiState(player, GuiState.bulkAssignPicker(page), inv, Text.translatable("customblocks.gui.bulk_assign.title", selected.size()));
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
        inv.setStack(11, uiGlint(net.minecraft.item.Items.AMETHYST_CLUSTER, "§aColor: §f" + color, // Royal Directive
            "§7Click to cycle: green / yellow / black"));
        inv.setStack(13, uiGlint(net.minecraft.item.Items.NETHER_STAR, "§bFilter: §f" + scopeLabel, // Royal Directive V4-20
            "§7" + bulkRecolorScopeDescription(scopeKey),
            "§8Click to cycle filter type"));
        inv.setStack(15, uiGlint(net.minecraft.item.Items.NAME_TAG, "§dFilter Value",
            "§7Current: §f" + (scopeValue == null || scopeValue.isBlank() ? "(none)" : scopeValue),
            "§8Click to edit value for category/query/ids/range"));
        inv.setStack(16, uiGlint(net.minecraft.item.Items.ECHO_SHARD, "§cExclude List", // Royal Directive
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
            "§7Filter: §f" + scopeLabel,
            "§7Selected IDs: §f" + selectedCount));
        inv.setStack(45, uiGlint(net.minecraft.item.Items.ECHO_SHARD, "§c← Back")); // Royal Directive

        java.util.List<com.customblocks.core.SlotData> blocks = sortedBlocks();
        int start = page * BLOCKS_PER_PAGE;
        int end = Math.min(start + BLOCKS_PER_PAGE, blocks.size());
        java.util.Set<String> selected = BULK_RECOLOR_SELECTED.get(player.getUuid());
        for (int i = start; i < end; i++) {
            com.customblocks.core.SlotData d = blocks.get(i);
            net.minecraft.item.ItemStack stack = displayStackFor(d);
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

        openScreenFromGuiState(player, GuiState.bulkRecolorWizard(page), inv, Text.translatable("customblocks.gui.bulk_recolor.title"));
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
                "§6Bulk Recolor Filter Value", new net.minecraft.item.ItemStack(net.minecraft.item.Items.NAME_TAG),
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
        inv.setStack(18, uiGlint(net.minecraft.item.Items.ECHO_SHARD, "§c← Back to Wizard")); // Royal Directive
        inv.setStack(26, uiGlint(net.minecraft.item.Items.ECHO_SHARD, "§8Cancel")); // Royal Directive

        openScreenFromGuiState(player, GuiState.bulkRecolorConfirm(returnPage), inv, Text.translatable("customblocks.gui.bulk_recolor_confirm.title"));
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
                    send(player, "§cScope value required: enter a category key.");
                    playError(player);
                    yield null;
                }
                yield "category:" + value;
            }
            case "ids" -> {
                if (value.isBlank()) {
                    send(player, "§cScope value required: enter one or more block IDs.");
                    playError(player);
                    yield null;
                }
                yield "ids:" + value;
            }
            case "query" -> {
                if (value.isBlank()) {
                    send(player, "§cScope value required: enter search text.");
                    playError(player);
                    yield null;
                }
                yield "query:" + value;
            }
            case "range" -> {
                if (!value.matches("\\d+\\s*-\\s*\\d+")) {
                    send(player, "§cRange format: start-end (example: 1-120).");
                    playError(player);
                    yield null;
                }
                yield "range:" + value;
            }
            case "recent" -> {
                if (!value.isBlank() && !value.matches("\\d+")) {
                    send(player, "§cRecent expects a number (example: 10).");
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
            try { n = Integer.parseInt(expr.substring("recent:".length()).trim()); } catch (NumberFormatException ignored) {}
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
            try { n = Integer.parseInt(expr.substring("recent:".length()).trim()); } catch (NumberFormatException ignored) {}
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

    // ── Phase 2 — Bulk Selection helpers ────────────────────────────────────

    private static Set<String> bulkSel(UUID uuid) {
        return BULK_SELECTIONS.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
    }

    public static Set<String> getBulkSelection(UUID uuid) {
        return java.util.Collections.unmodifiableSet(bulkSel(uuid));
    }

    private static void bulkToggle(UUID uuid, String id) {
        Set<String> sel = bulkSel(uuid);
        if (!sel.remove(id)) sel.add(id);
    }

    private static void bulkSelectAll(UUID uuid, java.util.List<SlotData> pool) {
        Set<String> sel = bulkSel(uuid);
        pool.forEach(d -> sel.add(d.customId));
    }

    private static void bulkClear(UUID uuid) {
        bulkSel(uuid).clear();
    }

    // ── Phase 2 — Bulk Hub ───────────────────────────────────────────────────

    /** Phase 2 — open the bulk operations hub. */
    public static void openBulkHub(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        openScreenFromGuiState(player, GuiState.bulkHub(),
                buildBulkHub(player.getUuid()), "§5§lBulk Operations");
    }

    private static SimpleInventory buildBulkHub(UUID uuid) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        // Title row
        inv.setStack(4, ui(Items.COMMAND_BLOCK, "§5§lBulk Operations Hub",
                "§7Select an operation to get started.",
                "§7Your selection: §e" + bulkSel(uuid).size() + " blocks"));

        // Row 1 — operations 1-4
        inv.setStack(10, uiGlint(Items.TNT,            "§c§lDelete",       "§7Permanently remove selected blocks", "§8Shift+click in picker to select all"));
        inv.setStack(11, uiGlint(Items.MAGMA_CREAM,    "§6Recolor",        "§7Apply a color tint to selected blocks"));
        inv.setStack(12, uiGlint(Items.NAME_TAG,       "§eRename",         "§7Change display names: prefix, suffix, or replace"));
        inv.setStack(13, uiGlint(Items.PAPER,          "§eRe-ID",          "§7Change block IDs — updates all references"));

        // Row 2 — operations 5-8
        inv.setStack(19, uiGlint(Items.COMPARATOR,     "§aProperties",     "§7Set sound, glow, hardness, or collision"));
        inv.setStack(20, uiGlint(Items.ENDER_PEARL,    "§bMove Category",  "§7Move selected blocks to another category"));
        inv.setStack(21, uiGlint(Items.WRITTEN_BOOK,   "§3Export",         "§7Export selected blocks as a shareable ZIP"));
        inv.setStack(22, uiGlint(Items.BOOK,           "§dDuplicate",      "§7Clone selected blocks with new IDs"));

        // Row 3 — operations 9-12
        inv.setStack(28, uiGlint(Items.CHAIN,          "§7Lock / Unlock",  "§7Protect or unprotect blocks from editing"));
        inv.setStack(29, uiGlint(Items.HEART_OF_THE_SEA, "§dFavorite",     "§7Star or un-star selected blocks"));
        inv.setStack(30, uiGlint(Items.BRICK,          "§bShape",          "§7Apply a shape preset to selected blocks"));
        inv.setStack(31, uiGlint(Items.NOTE_BLOCK,     "§aSound",          "§7Set sound type for selected blocks"));

        // Bottom row — selection controls
        inv.setStack(45, uiGlint(Items.ECHO_SHARD, "§c◀ Back", "§8Return to main menu"));
        inv.setStack(46, uiGlint(Items.LIME_DYE,     "§aSelect All",    "§7Selects every block"));
        inv.setStack(47, ui(Items.ORANGE_DYE,         "§6Deselect All",  "§7Clears current selection"));
        inv.setStack(49, ui(Items.GLOWSTONE_DUST,     "§e" + bulkSel(uuid).size() + " §7block(s) selected",
                "§7Click an operation above to continue."));

        return inv;
    }

    private static void handleBulkHubClick(ServerPlayerEntity player, GuiState state, int slot) {
        UUID uuid = player.getUuid();
        switch (slot) {
            case 45 -> openMain(player, 0);
            case 46 -> {
                bulkSelectAll(uuid, sortedBlocks());
                refreshScreen(player, buildBulkHub(uuid));
            }
            case 47 -> {
                bulkClear(uuid);
                refreshScreen(player, buildBulkHub(uuid));
            }
            // Operation tiles → open op picker
            case 10 -> openBulkOpPicker(player, "delete",    0);
            case 11 -> openBulkOpPicker(player, "recolor",   0);
            case 12 -> openBulkOpPicker(player, "rename",    0);
            case 13 -> openBulkOpPicker(player, "reid",      0);
            case 19 -> openBulkOpPicker(player, "property",  0);
            case 20 -> openBulkOpPicker(player, "movecat",   0);
            case 21 -> openBulkOpPicker(player, "export",    0);
            case 22 -> openBulkOpPicker(player, "duplicate", 0);
            case 28 -> openBulkOpPicker(player, "lock",      0);
            case 29 -> openBulkOpPicker(player, "favorite",  0);
            case 30 -> openBulkOpPicker(player, "shape",     0);
            case 31 -> openBulkOpPicker(player, "sound",     0);
            default -> {}
        }
    }

    // ── Phase 2 — Bulk Op Picker ─────────────────────────────────────────────

    private static final int BULK_PICKER_PAGE_SIZE = 36;

    /** Phase 2 — open the shared bulk-op block picker. */
    public static void openBulkOpPicker(ServerPlayerEntity player, String opId, int page) {
        STATES.put(player.getUuid(), GuiState.bulkOpPicker(opId, page));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
                (s, pi, p) -> new CbScreenHandler(s, pi, buildBulkOpPicker(player.getUuid(), opId, page)),
                Text.literal(normalizeFormattingCodes(bulkOpTitle(opId)))));
    }

    private static String bulkOpTitle(String opId) {
        return switch (opId) {
            case "delete"    -> "§c§lBulk Delete — Select Blocks";
            case "recolor"   -> "§6Bulk Recolor — Select Blocks";
            case "rename"    -> "§eBulk Rename — Select Blocks";
            case "reid"      -> "§eBulk Re-ID — Select Blocks";
            case "property"  -> "§aBulk Properties — Select Blocks";
            case "movecat"   -> "§bBulk Move Category — Select Blocks";
            case "export"    -> "§3Bulk Export — Select Blocks";
            case "duplicate" -> "§dBulk Duplicate — Select Blocks";
            case "lock"      -> "§7Bulk Lock/Unlock — Select Blocks";
            case "favorite"  -> "§dBulk Favorite — Select Blocks";
            case "shape"     -> "§bBulk Shape — Select Blocks";
            case "sound"     -> "§aBulk Sound — Select Blocks";
            default          -> "§5Bulk Op — Select Blocks";
        };
    }

    private static SimpleInventory buildBulkOpPicker(UUID uuid, String opId, int page) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        Set<String>     sel     = bulkSel(uuid);
        String          catFilt = BULK_OP_CAT_FILTER.get(uuid);
        java.util.List<SlotData> pool   = catFilt != null
                ? com.customblocks.core.CategoryManager.getBlocksInCategory(catFilt)
                : sortedBlocks();
        int total   = pool.size();
        int maxPage = total == 0 ? 0 : Math.max(0, (total - 1) / BULK_PICKER_PAGE_SIZE);
        page = Math.max(0, Math.min(page, maxPage));

        // Top bar
        inv.setStack(0, uiGlint(Items.ECHO_SHARD, "§c◀ Back to Hub", "§8Return to bulk operations hub"));
        inv.setStack(6, uiGlint(Items.LIME_DYE, "§aSelect All",
                "§7Selects all " + pool.size() + " matching blocks"));
        inv.setStack(7, ui(Items.ORANGE_DYE, "§6Deselect All", "§7Clears current selection"));
        inv.setStack(8, uiGlint(bulkOpConfirmItem(opId),
                "§a§l▶ Execute: " + bulkOpLabel(opId),
                "§7Selected: §e" + sel.size() + " §7blocks",
                sel.isEmpty() ? "§cSelect at least one block first" : "§aClick to proceed"));

        // Category filter button (row 1 slot 4)
        String catLabel = catFilt != null ? "§bCategory: §f" + catFilt : "§7Category: §fAll";
        inv.setStack(4, ui(Items.ENDER_PEARL, catLabel, "§8Click to cycle category filter"));

        // Block grid (slots 9–44, 4 rows × 9)
        int start = page * BULK_PICKER_PAGE_SIZE;
        for (int i = 0; i < BULK_PICKER_PAGE_SIZE; i++) {
            int gridSlot = 9 + i;
            int dataIdx  = start + i;
            if (dataIdx < pool.size()) {
                SlotData d = pool.get(dataIdx);
                boolean selected = sel.contains(d.customId);
                boolean locked   = com.customblocks.core.LockManager.isLocked(d.customId);
                ItemStack s = selected
                        ? uiGlint(Items.LIME_STAINED_GLASS_PANE,
                                "§a§l✔ " + d.displayName,
                                "§7ID: §b" + d.customId,
                                locked ? "§6⚿ Locked" : "",
                                "§aClick to deselect")
                        : (CustomBlocksMod.safeSlotItem(d.index) != null
                                ? new ItemStack(CustomBlocksMod.safeSlotItem(d.index))
                                : new ItemStack(Items.GRAY_DYE));
                if (!selected) {
                    s.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                            Text.literal("§f" + d.displayName).styled(st -> st.withItalic(false)));
                    List<Text> loreLines = new ArrayList<>();
                    loreLines.add(lore("§7ID: §b" + d.customId));
                    if (locked) loreLines.add(lore("§6⚿ Locked"));
                    loreLines.add(lore("§8Click to select"));
                    s.set(net.minecraft.component.DataComponentTypes.LORE, new LoreComponent(loreLines));
                }
                inv.setStack(gridSlot, s);
            }
        }

        // Navigation row
        inv.setStack(45, page > 0
                ? uiGlint(Items.ARROW, "§7◀ Previous Page", "§8Page " + page)
                : ui(Items.GRAY_STAINED_GLASS_PANE, "§8First Page", ""));
        inv.setStack(49, ui(Items.PAPER,
                "§ePage §f" + (page + 1) + " §7/ §f" + (maxPage + 1),
                "§7Selected: §e" + sel.size() + " §7/ §f" + total));
        inv.setStack(53, page < maxPage
                ? uiGlint(Items.ARROW, "§7Next Page ▶", "§8Page " + (page + 2))
                : ui(Items.GRAY_STAINED_GLASS_PANE, "§8Last Page ▶", ""));
        return inv;
    }

    private static Item bulkOpConfirmItem(String opId) {
        return switch (opId) {
            case "delete"    -> Items.BARRIER;
            case "recolor"   -> Items.MAGMA_CREAM;
            case "rename"    -> Items.NAME_TAG;
            case "reid"      -> Items.PAPER;
            case "property"  -> Items.COMPARATOR;
            case "movecat"   -> Items.ENDER_PEARL;
            case "export"    -> Items.WRITTEN_BOOK;
            case "duplicate" -> Items.BOOK;
            case "lock"      -> Items.CHAIN;
            case "favorite"  -> Items.HEART_OF_THE_SEA;
            case "shape"     -> Items.BRICK;
            case "sound"     -> Items.NOTE_BLOCK;
            default          -> Items.NETHER_STAR;
        };
    }

    private static String bulkOpLabel(String opId) {
        return switch (opId) {
            case "delete"    -> "Delete";
            case "recolor"   -> "Recolor";
            case "rename"    -> "Rename";
            case "reid"      -> "Re-ID";
            case "property"  -> "Properties";
            case "movecat"   -> "Move Category";
            case "export"    -> "Export";
            case "duplicate" -> "Duplicate";
            case "lock"      -> "Lock/Unlock";
            case "favorite"  -> "Favorite";
            case "shape"     -> "Shape";
            case "sound"     -> "Sound";
            default          -> opId;
        };
    }

    private static void handleBulkOpPickerClick(ServerPlayerEntity player, GuiState state, int slot) {
        UUID   uuid  = player.getUuid();
        String opId  = state.editingId();
        int    page  = state.page();

        String catFilt = BULK_OP_CAT_FILTER.get(uuid);
        java.util.List<SlotData> pool = catFilt != null
                ? com.customblocks.core.CategoryManager.getBlocksInCategory(catFilt)
                : sortedBlocks();
        int total   = pool.size();
        int maxPage = total == 0 ? 0 : Math.max(0, (total - 1) / BULK_PICKER_PAGE_SIZE);

        if (slot == 0) { openBulkHub(player); return; }

        if (slot == 6) { // Select all
            bulkSelectAll(uuid, pool);
            refreshScreen(player, buildBulkOpPicker(uuid, opId, page));
            return;
        }
        if (slot == 7) { // Deselect all
            bulkClear(uuid);
            refreshScreen(player, buildBulkOpPicker(uuid, opId, page));
            return;
        }
        if (slot == 4) { // Category filter cycle
            cycleBulkCatFilter(uuid);
            STATES.put(uuid, GuiState.bulkOpPicker(opId, 0));
            refreshScreen(player, buildBulkOpPicker(uuid, opId, 0));
            return;
        }
        if (slot == 8) { // Execute
            executeBulkOpFromGui(player, opId);
            return;
        }
        if (slot == 45 && page > 0) {
            STATES.put(uuid, GuiState.bulkOpPicker(opId, page - 1));
            refreshScreen(player, buildBulkOpPicker(uuid, opId, page - 1));
            return;
        }
        if (slot == 53 && page < maxPage) {
            STATES.put(uuid, GuiState.bulkOpPicker(opId, page + 1));
            refreshScreen(player, buildBulkOpPicker(uuid, opId, page + 1));
            return;
        }
        // Block grid toggle (slots 9–44)
        if (slot >= 9 && slot <= 44) {
            int dataIdx = page * BULK_PICKER_PAGE_SIZE + (slot - 9);
            if (dataIdx < pool.size()) {
                bulkToggle(uuid, pool.get(dataIdx).customId);
                refreshScreen(player, buildBulkOpPicker(uuid, opId, page));
            }
        }
    }

    private static void cycleBulkCatFilter(UUID uuid) {
        java.util.List<com.customblocks.core.Category> cats =
                new java.util.ArrayList<>(com.customblocks.core.CategoryManager.getAllCategories());
        if (cats.isEmpty()) { BULK_OP_CAT_FILTER.remove(uuid); return; }
        String current = BULK_OP_CAT_FILTER.get(uuid);
        if (current == null) {
            BULK_OP_CAT_FILTER.put(uuid, cats.get(0).key());
        } else {
            int idx = -1;
            for (int i = 0; i < cats.size(); i++) if (cats.get(i).key().equals(current)) { idx = i; break; }
            if (idx < 0 || idx >= cats.size() - 1) BULK_OP_CAT_FILTER.remove(uuid);
            else BULK_OP_CAT_FILTER.put(uuid, cats.get(idx + 1).key());
        }
    }

    /**
     * Execute a bulk operation on the player's current BULK_SELECTIONS set.
     * Simple no-config operations are executed directly; config-required ones
     * close the GUI and prompt the player to use the matching command.
     */
    private static void executeBulkOpFromGui(ServerPlayerEntity player, String opId) {
        UUID        uuid = player.getUuid();
        Set<String> sel  = new java.util.HashSet<>(bulkSel(uuid));
        if (sel.isEmpty()) {
            playError(player);
            FeedbackHelper.actionBar(player, "§cSelect at least one block first.");
            return;
        }
        MinecraftServer server = player.getServer();
        switch (opId) {
            case "delete" -> {
                int threshold = com.customblocks.CustomBlocksConfig.bulkConfirmThreshold;
                if (sel.size() > threshold) {
                    // Re-use the armed mechanism from bulk delete
                    Long armedAt = BULK_DELETE_CONFIRM_ARMED.get(uuid);
                    boolean confirmed = armedAt != null && (System.currentTimeMillis() - armedAt) < 5000L;
                    if (!confirmed) {
                        BULK_DELETE_CONFIRM_ARMED.put(uuid, System.currentTimeMillis());
                        playError(player);
                        FeedbackHelper.actionBar(player, "§c§l⚠ " + sel.size() + " blocks! Click again within 5s to confirm.");
                        return;
                    }
                }
                BULK_DELETE_CONFIRM_ARMED.remove(uuid);
                com.customblocks.core.UndoManager.pushCategoryUndo(
                        com.customblocks.core.UndoManager.captureCategorySnapshot("bulk-delete " + sel.size(), uuid));
                int count = 0;
                for (String id : sel) {
                    if (com.customblocks.core.SlotManager.remove(id) != null) {
                        com.customblocks.core.LockManager.onBlockDeleted(id);
                        new java.util.HashSet<>(com.customblocks.core.CategoryManager.getCategoriesForBlock(id))
                                .forEach(cat -> com.customblocks.core.CategoryManager.unassignBlock(id, cat));
                        if (server != null) server.execute(() ->
                                com.customblocks.network.NetworkManager.broadcastUpdate(server,
                                        new com.customblocks.network.SlotUpdatePayload("delete", -1, id, null, null, 0, 0, "")));
                        count++;
                    }
                }
                if (server != null) com.customblocks.ResourcePackManager.scheduleRebuild(server);
                bulkClear(uuid);
                FeedbackHelper.actionBar(player, "§c§l✗ §r§cDeleted §f" + count + " §cblocks");
                openBulkHub(player);
            }
            case "lock" -> {
                int locked = 0, unlocked = 0;
                for (String id : sel) {
                    if (com.customblocks.core.LockManager.isLocked(id)) {
                        com.customblocks.core.LockManager.unlock(id); unlocked++;
                    } else {
                        com.customblocks.core.LockManager.lock(id); locked++;
                    }
                }
                bulkClear(uuid);
                FeedbackHelper.actionBar(player, "§7Locked §f" + locked + " §7/ Unlocked §f" + unlocked + " §7blocks");
                openBulkHub(player);
            }
            case "favorite" -> {
                int added = 0, removed = 0;
                for (String id : sel) {
                    boolean nowFav = com.customblocks.core.FavoritesManager.toggle(uuid, id, server);
                    if (nowFav) added++; else removed++;
                }
                bulkClear(uuid);
                FeedbackHelper.actionBar(player, "§dStarred §f" + added + " §7/ Unstarred §f" + removed + " §7blocks");
                openBulkHub(player);
            }
            // Config-required operations: close GUI, prompt command
            default -> {
                player.closeHandledScreen();
                String example = buildBulkCommandHint(opId, sel);
                send(player, "§e" + sel.size() + " §7block(s) selected. Use command to configure:");
                send(player, "§b" + example);
                send(player, "§8(Tip: use §bids:<id1>,<id2>,...§8 to target specific blocks)");
            }
        }
    }

    private static String buildBulkCommandHint(String opId, Set<String> sel) {
        String ids = sel.stream().limit(3).collect(java.util.stream.Collectors.joining(","))
                + (sel.size() > 3 ? ",..." : "");
        return switch (opId) {
            case "rename"    -> "/cb bulkrename " + ids + " --prefix \"New \"";
            case "reid"      -> "/cb bulkreid " + ids + " --replace old_ new_";
            case "property"  -> "/cb bulkproperty " + ids + " sound wood";
            case "movecat"   -> "/cb bulkmove " + ids + " <category>";
            case "export"    -> "/cb bulkexport " + ids;
            case "duplicate" -> "/cb bulkduplicate " + ids + " --suffix _copy";
            case "shape"     -> "/cb bulkshape " + ids + " slab";
            case "sound"     -> "/cb bulksound " + ids + " wood";
            case "recolor"   -> "/cb bulkrecolor green " + ids;
            default          -> "/cb bulk" + opId + " " + ids;
        };
    }
}








