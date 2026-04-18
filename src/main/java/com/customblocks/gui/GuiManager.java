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
import net.minecraft.server.world.ServerWorld;
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
        CONFIG_VALUE
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
    private static final Map<UUID, Deque<String>> RECENT_BLOCKS = new ConcurrentHashMap<>();
    private static final int MAX_RECENT = 3;

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
        BULK_DELETE_SELECTIONS.remove(uuid);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public static void openToolsGui(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.tools());
        openScreen(player, new SimpleNamedScreenHandlerFactory((syncId, playerInv, p) -> new CbScreenHandler(syncId, playerInv, buildToolsGui(player)), Text.literal("§d§lMagic Items & Tools")));
    }

    public static void openMain(ServerPlayerEntity player, int page) {
        STATES.put(player.getUuid(), GuiState.main(page));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildMain(player, STATES.getOrDefault(player.getUuid(), GuiState.main(0)).page())),
            Text.literal("§b§l✦ §r§fCustomBlocks Dashboard")));
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
            Text.literal("§b§l▶ §r§fPick a Block")));
    }

    public static void openEditor(ServerPlayerEntity player, String id, int returnPage, boolean fromCommand) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        trackRecentBlock(player.getUuid(), id);
        if (!fromCommand) pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), fromCommand
            ? GuiState.editorFromCommand(id)
            : GuiState.editor(id, returnPage));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildEditor(d, false)),
            Text.literal("§e§l✎ §r§fBlock Editor §8— " + d.displayName)));
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
            Text.literal("§d§l⬡ §r§fFace Editor §8— " + d.displayName)));
    }

    public static void openShapeEditor(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.shapeEditor(id, returnPage));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildShapeEditor(d, 0)),
            Text.literal("§5§l⬡ §r§fShape Editor §8— " + d.displayName)));
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
        STATES.put(player.getUuid(), GuiState.searchPicker(page));
        final int fp = page;
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildSearchPicker(fp, q)),
            Text.literal("§b§l🔍 §r§fSearch: §7" + query + " §8(" + total + " found)")));
    }

    public static void openMaintenanceMenu(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.maintenance());
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildMaintenanceMenu(player)),
            Text.literal("§b§l✦ §r§fServer Tools")));
    }

    private static SimpleInventory buildResourceHub(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        boolean httpUp = com.customblocks.network.ResourcePackServer.isRunning();
        int port = com.customblocks.network.ResourcePackServer.getPort();
        int texCount = SlotManager.usedSlots();

        inv.setStack(4, uiGlint(Items.COMPASS, "§b§lResource Pack Hub",
            "§7Textures registered: §f" + texCount,
            "§7HTTP Server: " + (httpUp ? "§a✔ Running §7(port §f" + port + "§7)" : "§c✖ Stopped"),
            "§7RP Enforce: " + (CustomBlocksConfig.rpEnforceOnJoin ? "§aON" : "§cOFF")));

        inv.setStack(20, uiGlint(Items.ECHO_SHARD, "§b§lGet Download Link",
            "§7Creates a shareable URL for your texture pack",
            "§b§nClick to broadcast to chat"));

        inv.setStack(22, uiGlint(Items.NETHER_STAR, "§a§lForce Sync",
            "§7Sends latest textures to all players",
            "§e§lClick to broadcast"));

        inv.setStack(24, toggleItem("RP Enforce on Join", CustomBlocksConfig.rpEnforceOnJoin,
            "Send resource pack as required when players join"));

        inv.setStack(45, uiGlint(Items.RED_CONCRETE, "§c◀ Back"));
        return inv;
    }

    public static void openAssistantControl(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.assistantControl());
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, px) -> new CbScreenHandler(s, pi, buildAssistantControl(player)),
            Text.literal("§b§l✦ §r§fAI Assistant")));
    }

    private static SimpleInventory buildAssistantControl(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        boolean active = com.customblocks.assistant.AssistantManager.isSpawned();
        boolean following = com.customblocks.assistant.AssistantManager.isFollowing();
        boolean holo = CustomBlocksConfig.aiHologram;
        String currentStyle = com.customblocks.assistant.AssistantManager.normalizeStyle(CustomBlocksConfig.aiStyle);

        inv.setStack(4, uiGlint(Items.PLAYER_HEAD, "§b§lAssistant Hub",
            "§7Status: " + (active ? "§aSpawned" : "§cHidden"),
            "§7Mode: " + (following ? "§bFollowing you" : "§7Staying in place"),
            "§7Name: §f" + CustomBlocksConfig.aiName,
            "§7Style: §d" + currentStyle));

        // Row 1: core controls
        inv.setStack(19, uiGlint(active ? Items.ENDER_EYE : Items.ENDER_PEARL,
            active ? "§a§l✔ Spawned" : "§c§l✖ Hidden",
            "§7Turn the AI assistant ON or OFF.",
            "§7When active, it appears near you in-world."));

        inv.setStack(20, uiGlint(following ? Items.RECOVERY_COMPASS : Items.COMPASS,
            "§e§lMode: " + (following ? "Following" : "Staying"),
            "§7When following, the assistant floats after you.",
            "§7When staying, it waits in place."));

        inv.setStack(22, uiGlint(Items.ENDER_CHEST, "§b§lGo To AI",
            "§7Teleport yourself to where the assistant is.",
            active ? "§aAI assistant is active" : "§cAI assistant is not spawned"));

        inv.setStack(24, uiGlint(Items.NETHER_STAR, "§6§lScan for Broken Blocks",
            "§7Searches nearby area for blocks with missing textures.",
            "§7The assistant highlights broken blocks."));

        inv.setStack(25, uiGlint(holo ? Items.END_CRYSTAL : Items.GLASS,
            holo ? "§d§lHologram: §aON" : "§7§lHologram: §cOFF",
            "§7Floating status label above the assistant.",
            "§8Click to toggle"));

        // Row 2: identity
        inv.setStack(31, uiGlint(Items.PAINTING, "§f§lRename AI",
            "§7Current: §b" + CustomBlocksConfig.aiName,
            "§8Click to edit the assistant name"));

        // Row 3: Style Presets
        List<String> styles = com.customblocks.assistant.AssistantManager.availableStyles();
        for (int i = 0; i < styles.size() && i < 7; i++) {
            String style = styles.get(i);
            boolean current = currentStyle.equalsIgnoreCase(style);
            inv.setStack(37 + i, uiGlint(
                com.customblocks.assistant.AssistantManager.getStyleDisplayItem(style),
                (current ? "§a§l✔ " : "§b§l") + style,
                current ? "§aCurrent AI style" : "§7Click to use this style"));
        }

        inv.setStack(45, uiGlint(Items.RED_CONCRETE, "§c◀ Back"));
        return inv;
    }

    public static void openHelpGui(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.help());
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildHelpGui()),
            Text.literal("§a§l✦ §r§fHelp & Commands")));
    }

    public static void openPropertiesGui(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.properties(id, returnPage));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildPropertiesGui(d)),
            Text.literal("§6§l⚙ §r§fBlock Properties §8— " + d.displayName)));
    }

    public static void openSoundMenu(ServerPlayerEntity player, String id, int returnPage) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { openMain(player, returnPage); return; }
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.sound(id, returnPage));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildSoundMenu(d)),
            Text.literal("§e§l♫ §r§fSound Selector §8— " + d.displayName)));
    }

    public static void openTabIconPicker(ServerPlayerEntity player, int page) {
        int total = sortedBlocks().size();
        int max   = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        STATES.put(player.getUuid(), GuiState.picker(page));
        final int fp = page;
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildPicker(fp, false)),
            Text.literal("§b§l▶ §r§fPick Tab Icon §7(ESC = back)")));
    }

    public static void openResourceHub(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.resourceCenter());
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildResourceHub(player)),
            Text.literal("§b§l✦ §r§fResource Pack")));
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
            Text.literal("§6§l✦ §r§fBroken Block Finder")));
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
                case RESOURCE_CENTER -> openResourceHub(player);
                case ASSISTANT_CONTROL -> openAssistantControl(player);
                case ANIM_GUI -> openAnimGui(player, state.editingId());
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
                case RESOURCE_CENTER -> handleResourceHubClick(player, state, slot);
                case ASSISTANT_CONTROL -> handleAssistantControlClick(player, state, slot);
                case EDITOR       -> handleEditorClick(player, state, slot, button);
                case FACE_EDITOR  -> handleFaceEditorClick(player, state, slot, button);
                case SHAPE_EDITOR -> handleShapeEditorClick(player, state, slot, button);
                case MAINTENANCE_MENU -> handleMaintenanceClick(player, state, slot);
                case HELP_MENU      -> handleHelpClick(player, state, slot);
                case TOOLS_GUI      -> handleToolsClick(player, state, slot);
                case PROPERTIES_MENU -> handlePropertiesClick(player, state, slot);
                case SOUND_MENU     -> handleSoundClick(player, state, slot);
                case ANIM_GUI       -> handleAnimGuiClick(player, state, slot);
                case BULK_DELETE     -> handleBulkDeleteClick(player, state, slot);
                case SEARCH_PICKER  -> handleSearchPickerClick(player, state, slot);
                case MAGIC_ITEMS    -> handleMagicItemsClick(player, state, slot);
                case CONFIG_WARNING -> handleConfigWarningClick(player, state, slot);
                case CONFIG_GUI     -> handleConfigGuiClick(player, state, slot);
                case UNDO_PICKER    -> handleUndoPickerClick(player, state, slot);
                case HELP_CATEGORY  -> handleHelpCategoryClick(player, state, slot);
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
            switch (pending.action()) {
                case CONFIG_VALUE -> openConfigGui(player, false);
                case ADMIN_CUSTOM_TITLE -> {
                    if ("ai_name".equals(blockId)) openAssistantControl(player);
                    else openMain(player, rp);
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
                send(player, "§e[CB] Downloading '" + name + "'…");
                MinecraftServer srv = player.getServer();
                thread(player, () -> { try {
                    byte[] raw = ImageProcessor.download(text);
                    ImageProcessor.ProcessResult result = ImageProcessor.isAnimatedImage(raw) ? ImageProcessor.processAnimation(raw, CustomBlocksConfig.defaultTextureSize) : new ImageProcessor.ProcessResult(ImageProcessor.resizeTo(ImageProcessor.replaceBackground(ImageProcessor.padToSquare(ImageProcessor.toPng(raw))), CustomBlocksConfig.defaultTextureSize), null, 1);
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
                String convertedText = text.replace("_"," ").replace("&", "§");
                if (convertedText.length() > 100) convertedText = convertedText.substring(0, 100);
                UndoManager.pushUndoMutation(blockId, d, "rename", player.getUuid());
                SlotManager.rename(blockId, convertedText); SlotManager.saveAll();
                NetworkManager.broadcastUpdate(player.getServer(), new SlotUpdatePayload("rename", d.index, blockId, convertedText, null, 0, 0, "stone"));
                send(player, "§a[CB] Renamed to '§f" + convertedText + "§a'.");
                player.getServerWorld().playSound(null, player.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_ANVIL_USE, net.minecraft.sound.SoundCategory.MASTER, 1f, 1f);
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
                if ("ai_name".equals(blockId)) {
                    CustomBlocksConfig.aiName = text.replace("&", "§");
                    CustomBlocksConfig.save();
                    com.customblocks.assistant.AssistantManager.refreshFromConfig();
                    send(player, "§0§l[§b§lAI§0§l] §fCall me '§b" + text + "§f' from now on. §a✔");
                    openAssistantControl(player);
                    return true;
                }
                send(player, "§7[CustomBlocks] Action cancelled.");
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
                    send(player, "§a[CB] Light level set to " + light + ".");
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
                    send(player, "§a[CB] Hardness set to " + hardness + ".");
                    openPropertiesGui(player, blockId, rp);
                } catch (NumberFormatException e) {
                    send(player, "§cInvalid hardness value.");
                    openPropertiesGui(player, blockId, rp);
                }
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
                        case "aiName" -> CustomBlocksConfig.aiName = text.replace("&", "§");
                        case "rpPromptMessage" -> CustomBlocksConfig.rpPromptMessage = text;
                        case "rpKickMessage" -> CustomBlocksConfig.rpKickMessage = text;
                        case "undoMode" -> {
                            String v = text.toLowerCase().trim();
                            if (List.of("global", "per_player", "both").contains(v)) CustomBlocksConfig.undoMode = v;
                            else { send(player, "§cMust be: global / per_player / both"); openConfigGui(player, false); return true; }
                        }
                        case "aiStyle" -> CustomBlocksConfig.aiStyle = com.customblocks.assistant.AssistantManager.normalizeStyle(text);
                        default -> { send(player, "§cUnknown config key."); openConfigGui(player, false); return true; }
                    }
                    CustomBlocksConfig.save();
                    if ("aiName".equals(key) || "aiStyle".equals(key)) {
                        com.customblocks.assistant.AssistantManager.refreshFromConfig();
                    }
                    send(player, "§a[Config] §f" + key + " §a= §e" + text);
                } catch (NumberFormatException e) {
                    send(player, "§cInvalid number.");
                }
                openConfigGui(player, false);
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
        }
        return false;
    }

    public static boolean hasPending(ServerPlayerEntity player)  { return PENDING.containsKey(player.getUuid()); }
    public static void clearState(ServerPlayerEntity player) { STATES.remove(player.getUuid()); PENDING.remove(player.getUuid()); BACK_STACK.remove(player.getUuid()); }

    // ── Click handlers ────────────────────────────────────────────────────────

    private static void handleResourceHubClick(ServerPlayerEntity player, GuiState state, int slot) {
        playClick(player);
        if (slot == 45) { openMaintenanceMenu(player); return; }
        if (slot == 20) { // Copy Link
            String url = com.customblocks.network.ResourcePackServer.getPackUrl(player.getServer());
            player.closeHandledScreen();
            player.sendMessage(Text.literal("§0§l[§b§lCB§0§l] §fDownload Link: ")
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
        if (slot == 24) { // Toggle RP enforce
            CustomBlocksConfig.rpEnforceOnJoin = !CustomBlocksConfig.rpEnforceOnJoin;
            CustomBlocksConfig.save();
            send(player, "§a[Config] rpEnforceOnJoin = " + CustomBlocksConfig.rpEnforceOnJoin);
            openResourceHub(player);
        }
    }

    private static void handleAssistantControlClick(ServerPlayerEntity player, GuiState state, int slot) {
        playClick(player);
        if (slot == 45) { openMain(player, 0); return; }

        if (slot == 19) { // Toggle Spawn
            if (com.customblocks.assistant.AssistantManager.isSpawned()) {
                com.customblocks.assistant.AssistantManager.hide();
                send(player, "§0§l[§b§lAI§0§l] §7Hidden. §c✖");
            } else {
                com.customblocks.assistant.AssistantManager.spawn(player.getServer(), (net.minecraft.server.world.ServerWorld)player.getWorld(), player.getX(), player.getY(), player.getZ());
                send(player, "§0§l[§b§lAI§0§l] §fReady to help. §a✔");
            }
            openAssistantControl(player);
        }
        if (slot == 20) { // Toggle Follow
            boolean f = !com.customblocks.assistant.AssistantManager.isFollowing();
            com.customblocks.assistant.AssistantManager.setFollowing(f, player.getUuid());
            send(player, "§0§l[§b§lAI§0§l] §fMode: " + (f ? "§bFollowing" : "§7Staying"));
            openAssistantControl(player);
        }
        if (slot == 22) { // Go To AI
            if (!com.customblocks.assistant.AssistantManager.isSpawned()) {
                send(player, "§c[AI] Not spawned. Spawn the AI assistant first.");
                return;
            }
            player.closeHandledScreen();
            com.customblocks.assistant.AssistantManager.teleportPlayerToHelper(player);
            return;
        }
        if (slot == 24) { // Sanity Scan
            player.closeHandledScreen();
            com.customblocks.assistant.AssistantManager.runSanityScan(player);
            return;
        }
        if (slot == 25) { // Hologram toggle
            CustomBlocksConfig.aiHologram = !CustomBlocksConfig.aiHologram;
            CustomBlocksConfig.save();
            com.customblocks.assistant.AssistantManager.refreshFromConfig();
            send(player, "§0§l[§b§lAI§0§l] §fStatus halo: " + (CustomBlocksConfig.aiHologram ? "§aON" : "§cOFF"));
            openAssistantControl(player);
            return;
        }
        if (slot == 31) { // Rename
            openShortInputPrompt(
                player,
                new PendingInput(InputAction.ADMIN_CUSTOM_TITLE, "ai_name", null, null, null, 0),
                "§bAI Name",
                new ItemStack(Items.NAME_TAG),
                stripFormattingCodes(CustomBlocksConfig.aiName)
            );
            return;
        }
        // Style Presets
        if (slot >= 37 && slot <= 43) {
            List<String> skins = com.customblocks.assistant.AssistantManager.availableStyles();
            int si = slot - 37;
            if (si < skins.size()) {
                CustomBlocksConfig.aiStyle = skins.get(si);
                CustomBlocksConfig.save();
                com.customblocks.assistant.AssistantManager.refreshFromConfig();
                send(player, "§0§l[§b§lAI§0§l] §fStyle: §b" + skins.get(si) + " §a✔");
                openAssistantControl(player);
            }
        }
    }

    // ── Magic Items GUI ───────────────────────────────────────────────────────

    public static void openMagicItemsGui(ServerPlayerEntity player) {
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.magicItems());
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, px) -> new CbScreenHandler(s, pi, buildMagicItemsGui()),
            Text.literal("§6§l✦ §r§fMagic Items")));
    }

    private static SimpleInventory buildMagicItemsGui() {
        SimpleInventory inv = new SimpleInventory(27);
        for (int i = 0; i < 27; i++) inv.setStack(i, glass());
        inv.setStack(10, uiGlint(Items.GREEN_CONCRETE, "§a§lGreen Square", "§7Click to receive"));
        inv.setStack(11, uiGlint(Items.YELLOW_CONCRETE, "§e§lYellow Square", "§7Click to receive"));
        inv.setStack(12, uiGlint(Items.BLACK_CONCRETE, "§8§lBlack Square", "§7Click to receive"));
        inv.setStack(13, uiGlint(Items.EMERALD, "§a§l▶ Give All", "§7Click to get every magic item at once"));
        inv.setStack(14, uiGlint(Items.GREEN_TERRACOTTA, "§a§lGreen Triangle", "§7Click to receive"));
        inv.setStack(15, uiGlint(Items.YELLOW_TERRACOTTA, "§e§lYellow Triangle", "§7Click to receive"));
        inv.setStack(16, uiGlint(Items.BLACK_TERRACOTTA, "§8§lBlack Triangle", "§7Click to receive"));
        inv.setStack(22, uiGlint(Items.PAINTING, "§6§lRainbow Rectangle", "§7Click to receive the face-painting wand"));
        inv.setStack(18, uiGlint(Items.RED_CONCRETE, "§c◀ Back"));
        return inv;
    }

    private static void handleMagicItemsClick(ServerPlayerEntity player, GuiState state, int slot) {
        net.minecraft.server.command.ServerCommandSource src = player.getCommandSource();
        switch (slot) {
            case 10 -> { com.customblocks.command.CustomBlockCommand.cmdGiveSquareInternal(src, "green"); openMagicItemsGui(player); }
            case 11 -> { com.customblocks.command.CustomBlockCommand.cmdGiveSquareInternal(src, "yellow"); openMagicItemsGui(player); }
            case 12 -> { com.customblocks.command.CustomBlockCommand.cmdGiveSquareInternal(src, "black"); openMagicItemsGui(player); }
            case 13 -> {
                com.customblocks.command.CustomBlockCommand.cmdGiveSquareInternal(src, "green");
                com.customblocks.command.CustomBlockCommand.cmdGiveSquareInternal(src, "yellow");
                com.customblocks.command.CustomBlockCommand.cmdGiveSquareInternal(src, "black");
                com.customblocks.command.CustomBlockCommand.cmdGiveTriangleInternal(src, "green");
                com.customblocks.command.CustomBlockCommand.cmdGiveTriangleInternal(src, "yellow");
                com.customblocks.command.CustomBlockCommand.cmdGiveTriangleInternal(src, "black");
                com.customblocks.command.CustomBlockCommand.cmdGiveRectangleInternal(src);
                send(player, "§a[GUI] All magic items granted!");
                openMagicItemsGui(player);
            }
            case 14 -> { com.customblocks.command.CustomBlockCommand.cmdGiveTriangleInternal(src, "green"); openMagicItemsGui(player); }
            case 15 -> { com.customblocks.command.CustomBlockCommand.cmdGiveTriangleInternal(src, "yellow"); openMagicItemsGui(player); }
            case 16 -> { com.customblocks.command.CustomBlockCommand.cmdGiveTriangleInternal(src, "black"); openMagicItemsGui(player); }
            case 22 -> { com.customblocks.command.CustomBlockCommand.cmdGiveRectangleInternal(src); openMagicItemsGui(player); }
            case 18 -> openMain(player, 0);
        }
    }

    // ── Config GUI ────────────────────────────────────────────────────────────

    public static void openConfigWarningGui(ServerPlayerEntity player) {
        openConfigWarningGui(player, true);
    }

    public static void openConfigWarningGui(ServerPlayerEntity player, boolean pushBack) {
        if (pushBack) pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.configWarning());
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, px) -> new CbScreenHandler(s, pi, buildConfigWarningGui()),
            Text.literal("§6§l⚠ §r§fServer Config Warning")));
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
        STATES.put(player.getUuid(), GuiState.configGui());
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, px) -> new CbScreenHandler(s, pi, buildConfigGui()),
            Text.literal("§6§l⚙ §r§fServer Config")));
    }

    private static SimpleInventory buildConfigGui() {
        SimpleInventory inv = new SimpleInventory(54);
        for (int i = 0; i < 54; i++) inv.setStack(i, glass());
        // Row 1: Toggles
        inv.setStack(10, toggleItem("Auto-Send Texture Pack", CustomBlocksConfig.rpEnforceOnJoin, "Require the texture pack when players join"));
        inv.setStack(11, toggleItem("AI System Ready", CustomBlocksConfig.aiEnabled, "Keep the AI assistant system enabled"));
        inv.setStack(12, toggleItem("AI Status Halo", CustomBlocksConfig.aiHologram, "Show a floating status label above the assistant"));
        // Row 2: Numbers
        inv.setStack(19, numItem("Block Capacity", CustomBlocksConfig.maxSlots, "How many custom blocks this server can hold (restart required)"));
        inv.setStack(20, numItem("Texture Quality", CustomBlocksConfig.defaultTextureSize, "Default resolution used when new textures are processed"));
        inv.setStack(21, numItem("Background Cleanup", CustomBlocksConfig.bgRemovalTolerance, "How strongly imported images remove their background"));
        inv.setStack(22, numItem("History Depth", CustomBlocksConfig.maxUndoDepth, "How many undo and redo steps each player can keep"));
        inv.setStack(23, numItem("Download Timeout", CustomBlocksConfig.downloadTimeoutSeconds, "How long texture downloads may wait before failing"));
        inv.setStack(24, numItem("Texture Burst Rate", CustomBlocksConfig.texturePayloadsPerTick, "How many texture packets are sent each server tick"));
        inv.setStack(25, numItem("Communication Door", CustomBlocksConfig.resourcePackPort, "Port used by the local texture server (0 disables it)"));
        inv.setStack(26, numItem("Pack Rebuild Delay", CustomBlocksConfig.reloadDebounceMs, "How long to wait before rebuilding the pack again"));
        // Row 3: Strings
        inv.setStack(28, strItem("AI Display Name", CustomBlocksConfig.aiName, "The name shown above your assistant"));
        inv.setStack(29, strItem("Pack Invite Message", truncate(CustomBlocksConfig.rpPromptMessage, 30), "Message players see when the texture pack prompt appears"));
        inv.setStack(30, strItem("Pack Required Message", truncate(CustomBlocksConfig.rpKickMessage, 30), "Message shown if the server requires the texture pack"));
        inv.setStack(31, strItem("History Mode", CustomBlocksConfig.undoMode, "Choose whether undo history is shared or per-player"));
        inv.setStack(32, strItem("AI Style", CustomBlocksConfig.aiStyle, "Visual style for the assistant AI"));
        // Row 5: Back
        inv.setStack(45, uiGlint(Items.RED_CONCRETE, "§c◀ Back"));
        return inv;
    }

    private static ItemStack toggleItem(String label, boolean on, String desc) {
        return uiGlint(on ? Items.LIME_DYE : Items.GRAY_DYE,
            (on ? "§a§l" : "§7§l") + label + (on ? " §a✔ ON" : " §c✘ OFF"),
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

    private static void handleConfigGuiClick(ServerPlayerEntity player, GuiState state, int slot) {
        switch (slot) {
            // Toggles
            case 10 -> {
                CustomBlocksConfig.rpEnforceOnJoin = !CustomBlocksConfig.rpEnforceOnJoin;
                CustomBlocksConfig.save();
                send(player, "§a[Config] rpEnforceOnJoin = " + CustomBlocksConfig.rpEnforceOnJoin);
                openConfigGui(player, false);
            }
            case 11 -> {
                CustomBlocksConfig.aiEnabled = !CustomBlocksConfig.aiEnabled;
                if (!CustomBlocksConfig.aiEnabled && com.customblocks.assistant.AssistantManager.isSpawned()) {
                    com.customblocks.assistant.AssistantManager.hide();
                }
                CustomBlocksConfig.save();
                if (CustomBlocksConfig.aiEnabled) {
                    com.customblocks.assistant.AssistantManager.refreshFromConfig();
                }
                send(player, "§a[Config] aiEnabled = " + CustomBlocksConfig.aiEnabled);
                openConfigGui(player, false);
            }
            case 12 -> {
                CustomBlocksConfig.aiHologram = !CustomBlocksConfig.aiHologram;
                CustomBlocksConfig.save();
                com.customblocks.assistant.AssistantManager.refreshFromConfig();
                send(player, "§a[Config] aiHologram = " + CustomBlocksConfig.aiHologram);
                openConfigGui(player, false);
            }
            // Numbers
            case 19 -> configPrompt(player, "maxSlots", "Block Capacity (1-8192):");
            case 20 -> configPrompt(player, "defaultTextureSize", "Texture Quality (16-256):");
            case 21 -> configPrompt(player, "bgRemovalTolerance", "Background Cleanup (0-100):");
            case 22 -> configPrompt(player, "maxUndoDepth", "History Depth (1-100):");
            case 23 -> configPrompt(player, "downloadTimeoutSeconds", "Download Timeout (1-120):");
            case 24 -> configPrompt(player, "texturePayloadsPerTick", "Texture Burst Rate (1-50):");
            case 25 -> configPrompt(player, "resourcePackPort", "Communication Door (0 disables it):");
            case 26 -> configPrompt(player, "reloadDebounceMs", "Pack Rebuild Delay (500-10000 ms):");
            // Strings
            case 28 -> configPrompt(player, "aiName", "AI Display Name:");
            case 29 -> configPrompt(player, "rpPromptMessage", "Pack Invite Message:");
            case 30 -> configPrompt(player, "rpKickMessage", "Pack Required Message:");
            case 31 -> configPrompt(player, "undoMode", "History Mode (global / per_player / both):");
            case 32 -> configPrompt(player, "aiStyle", "AI Style:");
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
        STATES.put(player.getUuid(), GuiState.undoPicker(page));
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, px) -> new CbScreenHandler(s, pi, buildUndoPicker(player)),
            Text.literal("§6§l↩ §r§fUndo / Redo History")));
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
        if (slot == 8 && brokenOnly) {
            List<SlotData> broken = brokenBlocks();
            if (broken.isEmpty()) { send(player, "§7No broken blocks to delete."); return; }
            MinecraftServer srv = player.getServer();
            int count = 0;
            for (SlotData d : broken) {
                UndoManager.pushUndoDeletion(d.customId, d.deepCopy(), player.getUuid());
                SlotManager.remove(d.customId);
                NetworkManager.broadcastUpdate(srv, new SlotUpdatePayload("remove", d.index, d.customId, null, null, 0, 0, "stone"));
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
                openEditor(player, blocks.get(idx).customId, page);
            }
        }
    }

    
    private static void handleMainClick(ServerPlayerEntity player, GuiState state, int slot) {
        UUID uuid = player.getUuid();
        switch (slot) {
            // Row 1: main actions
            case 10 -> openEditorPicker(player, 0);
            case 12 -> openShortInputPrompt(
                player,
                new PendingInput(InputAction.CREATE_ID, null, null, null, null, state.page()),
                "§6New Block ID",
                new ItemStack(Items.COMMAND_BLOCK),
                ""
            );
            case 14 -> { PENDING.put(uuid, new PendingInput(InputAction.REID_TEXT, "__search__", null, null, null, state.page())); closeForPrompt(player); send(player, "§6[GUI] §eType a search query (or §ccancel§e):"); }
            case 16 -> openMagicItemsGui(player);
            // Row 2: utilities
            case 19 -> openAssistantControl(player);
            case 20 -> openMaintenanceMenu(player);
            case 21 -> {
                int undoSz = UndoManager.undoSize(uuid);
                if (undoSz == 0) { send(player, "§7Nothing to undo."); refreshScreen(player, buildMain(player, state.page())); return; }
                UndoManager.UndoEntry entry = UndoManager.popUndo(uuid);
                if (entry == null) { refreshScreen(player, buildMain(player, state.page())); return; }
                applyUndoEntry(player, entry);
                refreshScreen(player, buildMain(player, state.page()));
            }
            case 23 -> {
                int redoSz = UndoManager.redoSize(uuid);
                if (redoSz == 0) { send(player, "§7Nothing to redo."); refreshScreen(player, buildMain(player, state.page())); return; }
                UndoManager.UndoEntry entry = UndoManager.popRedo(uuid);
                if (entry == null) { refreshScreen(player, buildMain(player, state.page())); return; }
                applyRedoEntry(player, entry);
                refreshScreen(player, buildMain(player, state.page()));
            }
            case 22 -> openUndoPicker(player, 0);
            case 24 -> openBulkDelete(player, 0);
            case 25 -> openHelpGui(player);
            // Row 3
            case 28 -> openConfigWarningGui(player);
            // Recent blocks (slots 32-34)
            case 32, 33, 34 -> {
                Deque<String> recent = RECENT_BLOCKS.getOrDefault(uuid, new ArrayDeque<>());
                int ri = slot - 32;
                int idx = 0;
                for (String rid : recent) {
                    if (idx == ri && SlotManager.hasId(rid)) { openEditor(player, rid, state.page()); return; }
                    idx++;
                }
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
            case 0, 45 -> openEditorPicker(player, rp);
            case 2  -> { player.getInventory().insertStack(CustomBlocksMod.safeSlotItem(d.index)!=null?new ItemStack(CustomBlocksMod.safeSlotItem(d.index),1):ItemStack.EMPTY); send(player,"§a[GUI] Given 1x §f"+d.displayName); openEditor(player,id,rp); }
            case 8  -> { PENDING.put(uuid,new PendingInput(InputAction.RETEXTURE_URL,id,null,null,null,rp)); closeForPrompt(player); send(player,"§6[GUI] §ePaste image URL for ALL faces of '§f"+id+"§e' (or §ccancel§e):"); }
            case 17 -> { PENDING.put(uuid, new PendingInput(InputAction.WEB_LINK_CAST, id, null, null, null, rp)); closeForPrompt(player); send(player, "§0§l[§b§lCB§0§l] §ePaste the §fWeb-Link URL§e to cast onto this block (or §ccancel§e):"); }
            case 19 -> openFaceEditor(player, id, rp);
            case 21 -> openShapeEditor(player, id, rp);
            case 23 -> openPropertiesGui(player, id, rp);
            case 25 -> openSoundMenu(player, id, rp);
            case 31 -> { if (d.isAnimated()) openAnimGui(player, id); }
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
                NetworkManager.broadcastUpdate(player.getServer(),
                    new SlotUpdatePayload("add", created.index, newId, created.displayName, texCopy,
                        created.lightLevel, created.hardness, created.soundType, null, null, d.animMeta));
                send(player, "§a[GUI] Duplicated to §f" + newId + "§a!");
                openEditor(player, newId, rp);
            }
            case 43 -> {
                // Share button — GZIP-compressed export with texture bytes
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
                try {
                    byte[] json = obj.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(baos)) { gz.write(json); }
                    String code = "CB2!" + java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
                    player.sendMessage(Text.literal("§a[CB] Share code for '§f" + d.displayName + "§a':"), false);
                    player.sendMessage(Text.literal("§7Import with: §b/cb importblock <code>"), false);
                    player.sendMessage(Text.literal("§b§n" + code).styled(s -> s
                        .withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                        .withHoverEvent(new net.minecraft.text.HoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT, Text.literal("§eClick to copy to clipboard")))), false);
                    playSuccess(player);
                } catch (Exception ex) { send(player, "§c[CB] Share failed: " + ex.getMessage()); }
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
        else if(slot == 25) openAssistantControl(player);
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
        STATES.put(player.getUuid(), GuiState.helpCategory(category));
        String title = switch (category) {
            case 1 -> "§e§l✦ §r§fCreating Blocks";
            case 2 -> "§b§l✦ §r§fTextures & Design";
            case 3 -> "§5§l✦ §r§fShapes & Collision";
            case 4 -> "§6§l✦ §r§fUtilities & Commands";
            case 5 -> "§a§l✦ §r§fServer & Data";
            default -> "§f§lHelp";
        };
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildHelpCategory(category)),
            Text.literal(title)));
    }

    private static void handleHelpCategoryClick(ServerPlayerEntity player, GuiState state, int slot) {
        if (slot == 0 || slot == 45) openHelpGui(player);
    }

    private static void handlePropertiesClick(ServerPlayerEntity player, GuiState state, int slot) {
        if(slot == 0) { openEditor(player, state.editingId(), state.page()); return; }
        String id = state.editingId(); int rp = state.page();
        SlotData d = SlotManager.getById(id);
        if(d == null) { openMain(player, rp); return; }
        UUID uuid = player.getUuid();
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
        ChatHelper.success(player, "Animation speed updated for '§f" + d.displayName + "§a' (" + String.format("%.1f", fps) + " fps)");
    }

    // ── Bulk Delete GUI ────────────────────────────────────────────────────────

    public static void openBulkDelete(ServerPlayerEntity player, int page) {
        int total = sortedBlocks().size();
        int max = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        pushBackStack(player.getUuid());
        STATES.put(player.getUuid(), GuiState.bulkDelete(page));
        Set<String> selected = BULK_DELETE_SELECTIONS.computeIfAbsent(player.getUuid(), k -> ConcurrentHashMap.newKeySet());
        final int fp = page;
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildBulkDeleteGui(fp, selected)),
            Text.literal("§c§l⚠ §r§fBulk Delete §8— Select blocks to remove")));
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
        inv.setStack(4, uiGlint(Items.BRUSH, "§d§l🎨 Magic Items & Tools", "§7Click items to get them."));
        inv.setStack(20, uiGlint(Items.BLAZE_ROD, "§6Rainbow Rectangle Wand", "§7Paints blocks with rainbow colors."));
        inv.setStack(21, uiGlint(Items.WHITE_CONCRETE, "§fColor Square Wand", "§7Paints a solid-color area."));
        inv.setStack(22, uiGlint(Items.WHITE_CARPET, "§fColor Triangle Wand", "§7Paints a solid-color triangle."));
        inv.setStack(24, uiGlint(Items.PAINTING, "§eSet Tab Icon", "§7Opens the tab icon picker."));
        
        inv.setStack(45, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Main Menu"));
        return inv;
    }

    private static SimpleInventory buildMain(ServerPlayerEntity player, int page) {
        SimpleInventory inv = new SimpleInventory(54);
        UUID uuid = player.getUuid();
        int undoSz = UndoManager.undoSize(uuid);
        int redoSz = UndoManager.redoSize(uuid);
        int blockCount = sortedBlocks().size();
        int brokenCount = brokenBlocks().size();

        for (int i = 0; i < 54; i++) inv.setStack(i, glass());

        // Row 0: header
        inv.setStack(4, uiGlint(Items.DIAMOND, "§b§lCustomBlocks Dashboard", "§7Total blocks: §f" + blockCount,
            brokenCount > 0 ? "§cBroken: §f" + brokenCount : "§aAll textures OK",
            "§8Type /cb help for commands"));

        // Row 1: main actions
        inv.setStack(10, uiGlint(Items.CRAFTING_TABLE, "§e§lBlock Manager", "§7Browse, edit, or create blocks", "§8" + blockCount + " block(s) registered"));
        inv.setStack(12, uiGlint(Items.EMERALD, "§a§l+ Create New Block", "§7Create a new custom block", "§8Type an ID in chat"));
        inv.setStack(14, uiGlint(Items.SPYGLASS, "§f§lSearch Blocks", "§7Find a block by name or ID", "§8Type a query in chat"));
        inv.setStack(16, uiGlint(Items.BRUSH, "§d§lMagic Items", "§7Wands, color squares, triangles"));

        // Row 2: utilities
        inv.setStack(19, uiGlint(Items.ARMOR_STAND, "§b§lAssistant Hub", "§7Spawn, control, and configure the AI assistant"));
        inv.setStack(20, uiGlint(Items.STRUCTURE_VOID, "§6§lServer Tools", "§7Broken blocks, resource pack, data", brokenCount > 0 ? "§c" + brokenCount + " broken" : "§aAll OK"));
        inv.setStack(21, undoSz > 0 ? uiGlint(Items.GOLDEN_PICKAXE, "§6§l↩ Undo §e(" + undoSz + ")", "§7Click to undo last action") : ui(Items.GRAY_STAINED_GLASS_PANE, "§8Undo (Empty)"));
        inv.setStack(22, (undoSz + redoSz) > 0 ? uiGlint(Items.KNOWLEDGE_BOOK, "§6§lHistory §7(" + (undoSz + redoSz) + ")", "§7Browse undo/redo entries", "§8Click to open picker") : ui(Items.GRAY_STAINED_GLASS_PANE, "§8History (Empty)"));
        inv.setStack(23, redoSz > 0 ? uiGlint(Items.DIAMOND_PICKAXE, "§b§l↪ Redo §3(" + redoSz + ")", "§7Click to redo last undone action") : ui(Items.GRAY_STAINED_GLASS_PANE, "§8Redo (Empty)"));
        inv.setStack(24, uiGlint(Items.LAVA_BUCKET, "§c§l⚠ Bulk Delete", "§7Select and delete multiple blocks"));
        inv.setStack(25, uiGlint(Items.BOOK, "§a§lHelp & Info", "§7Interactive help guide"));

        // Row 3: config + recent
        inv.setStack(28, uiGlint(Items.COMPARATOR, "§6§l⚙ Config", "§7View and edit server-wide settings"));

        // Recent blocks strip (slots 32-34)
        Deque<String> recent = RECENT_BLOCKS.getOrDefault(uuid, new ArrayDeque<>());
        int ri = 0;
        for (String rid : recent) {
            if (ri >= MAX_RECENT) break;
            SlotData rd = SlotManager.getById(rid);
            if (rd == null) continue;
            inv.setStack(32 + ri, uiGlint(Items.CLOCK, "§7§lRecent: §f" + rd.displayName, "§7ID: §f" + rd.customId, "§8Click to edit"));
            ri++;
        }
        if (ri == 0) inv.setStack(32, ui(Items.GRAY_STAINED_GLASS_PANE, "§8No recent blocks"));

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
        
        inv.setStack(19, ui(Items.QUARTZ,"§c◀ Less Glow §8(-1)","§7Current: §e"+d.lightLevel, "§aLight level 15 is max brightness."));
        inv.setStack(20, uiGlint(Items.AMETHYST_CLUSTER,"§e✦ Light Level: §f"+d.lightLevel,"§70=off • 7=torch • 15=max", "§aMatches Minecraft light levels.", "§e§lClick to type value manually"));
        inv.setStack(21, ui(Items.GLOWSTONE_DUST,"§a▶ More Glow §8(+1)","§7Current: §e"+d.lightLevel));
        
        inv.setStack(23, ui(Items.FLINT,"§c◀ Softer §8(-)","§7Current: §f"+hardnessLabel(d.hardness), "§aHardness 0 breaks instantly."));
        inv.setStack(24, uiGlint(Items.NETHERITE_INGOT,"§b⚙ Hardness: §f"+hardnessLabel(d.hardness),"§7-1=Bedrock • 0=Instant • 1.5=Stone", "§aDetermines how fast players mine this block.", "§e§lClick to type value manually"));
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
                List<String> ll = new ArrayList<>(List.of("§7Unique ID: §b"+d.customId,"§7Shape: §5"+d.shapeLabel()+" §8• §7Light: §e"+d.lightLevel,"§7Sound: §f"+d.soundType,"§aClick to open the Block Editor"));
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

    private static List<SlotData> searchBlocks(String query) {
        return sortedBlocks().stream()
            .filter(d -> d.customId.toLowerCase().contains(query) || d.displayName.toLowerCase().contains(query))
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
        inv.setStack(8, uiGlint(Items.MAP,"§b§l⬛ Retexture Block","§7Update the main texture of this block","§aPaste a URL from Imgur, Discord, etc."));
        inv.setStack(17, uiGlint(Items.ECHO_SHARD, "§b§lApply Image from URL", "§7Instantly cast an image/GIF onto this", "§7block via a URL link. (Web-Linker)", "", "§e§l▶ Click to cast."));
        
        inv.setStack(19, uiGlint(Items.PAINTING, "§d§l⬡ Edit Faces", "§7Apply textures to individual faces","§aChange Top, Bottom, or Side textures separately."));
        inv.setStack(21, uiGlint(Items.ENDER_PEARL, "§5§l⬡ Edit Shape", "§7Presets, custom boxes, and collisions","§aMake slabs, stairs, or custom hitboxes."));
        inv.setStack(23, uiGlint(Items.REDSTONE, "§6§l⚙ Properties", "§7Adjust light glow & mining hardness","§aAdjust how the block feels in-world."));
        inv.setStack(25, uiGlint(Items.NOTE_BLOCK, "§e§l♫ Sound", "§7Change placement & break sounds","§aSimulate stone, glass, dirt, etc."));
        
        inv.setStack(31, d.isAnimated()
            ? uiGlint(Items.CLOCK, "§b§l⟳ Animation Settings", "§7This block is currently animated","§aYou can adjust frame speed (FPS) here.")
            : ui(Items.GRAY_DYE, "§7§l⟳ Animation", "§8No animation detected","§aAnimations are auto-enabled for GIF textures."));
        
        inv.setStack(37, uiGlint(Items.NAME_TAG,"§e§l✎ Rename Block","§7Current: §f"+d.displayName,"§aThis is the name everyone sees in the inventory."));
        inv.setStack(39, uiGlint(Items.COMMAND_BLOCK,"§b§l⇄ Re-ID Block","§7Current: §b"+d.customId,"§aChanging the unique ID updates all current builds."));
        inv.setStack(41, uiGlint(Items.COMPARATOR,"§e§l⧉ Duplicate Block","§7Create an identical copy of this block","§aGreat for making similar block sets quickly."));
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

        inv.setStack(0, uiGlint(Items.RED_CONCRETE, "§c◀ Back to Editor", "§8Closes without saving"));

        inv.setStack(4, uiGlint(Items.NETHER_STAR, "§b§l▶ Animation Settings",
            "§7Block: §f" + blockName,
            "§7Frames: §f" + frameCount,
            "§7Current Speed: §b" + String.format("%.1f", fps) + " Hz"));

        // ── Temporal Refinement ──────────────────────────────────────────────────
        inv.setStack(19, ui(Items.OBSIDIAN, "§c§l« §r§cSlower §8(-5 FPS)", "§7Slows the animation down"));
        inv.setStack(20, ui(Items.ARROW, "§c§l‹ §r§cSlower §8(-1 FPS)"));

        inv.setStack(22, uiGlint(Items.ECHO_SHARD, "§e§lAnimation Speed",
            "§7Current Speed: §b" + String.format("%.1f", fps) + " FPS",
            "§7Tick Delay: §f" + ticks + " §7ticks per frame",
            "",
            "§r"));

        inv.setStack(24, ui(Items.ARROW, "§a+1 FPS §l›", "§7Slight increase"));
        inv.setStack(25, ui(Items.GOLD_INGOT, "§a+5 FPS §l»", "§7Speeds the animation up"));

        // ── Frequency Nodes (Presets) ───────────────────────────────────────────
        inv.setStack(28, ui(Items.AMETHYST_SHARD, "§d5 FPS", "§7Very slow"));
        inv.setStack(29, ui(Items.AMETHYST_SHARD, "§d10 FPS", "§7Slow"));
        inv.setStack(30, ui(Items.AMETHYST_CLUSTER, "§b20 FPS", "§7Normal"));
        inv.setStack(31, ui(Items.AMETHYST_CLUSTER, "§b40 FPS", "§7Fast"));

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
        return switch (key) {
            case "rpPromptMessage", "rpKickMessage" -> false;
            default -> true;
        };
    }

    private static ItemStack shortPromptItemForConfig(String key) {
        Item item = switch (key) {
            case "maxSlots", "defaultTextureSize", "bgRemovalTolerance", "maxUndoDepth",
                 "downloadTimeoutSeconds", "texturePayloadsPerTick", "resourcePackPort",
                 "reloadDebounceMs" -> Items.REPEATER;
            case "undoMode" -> Items.COMPARATOR;
            case "aiStyle" -> com.customblocks.assistant.AssistantManager.getStyleDisplayItem(CustomBlocksConfig.aiStyle);
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
            case "aiName" -> stripFormattingCodes(CustomBlocksConfig.aiName);
            case "rpPromptMessage" -> CustomBlocksConfig.rpPromptMessage;
            case "rpKickMessage" -> CustomBlocksConfig.rpKickMessage;
            case "undoMode" -> CustomBlocksConfig.undoMode;
            case "aiStyle" -> com.customblocks.assistant.AssistantManager.normalizeStyle(CustomBlocksConfig.aiStyle);
            default -> "";
        };
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
