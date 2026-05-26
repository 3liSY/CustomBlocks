package com.customblocks;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;



import com.customblocks.block.ShowcaseBlock;
import com.customblocks.block.SlotBlock;

import com.customblocks.command.CustomBlockCommand;

import com.customblocks.core.SlotData;

import com.customblocks.core.SlotManager;

import com.customblocks.core.UndoManager;

import com.customblocks.gui.ChatHelper;
import com.customblocks.gui.GuiManager;
import com.customblocks.gui.ShowcaseManager;

import com.customblocks.item.ColorSquareItem;

import com.customblocks.item.ColorTriangleItem;

import com.customblocks.item.RectangleToolItem;

import com.customblocks.item.GoldenHexagonItem;

import com.customblocks.item.LuminaBrushItem;

import com.customblocks.item.AmethystChiselItem;

import com.customblocks.item.DiamondTriangleItem;

import com.customblocks.network.FullSyncPayload;

import com.customblocks.network.NetworkManager;

import com.customblocks.network.ResourcePackServer;

import com.customblocks.network.SlotUpdatePayload;

import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;

import net.minecraft.block.AbstractBlock;

import net.minecraft.block.MapColor;

import net.minecraft.entity.ItemEntity;

import net.minecraft.item.BlockItem;

import net.minecraft.item.Item;

import net.minecraft.item.ItemStack;

import net.minecraft.item.Items;

import net.minecraft.registry.Registries;

import net.minecraft.registry.Registry;

import net.minecraft.registry.RegistryKey;

import net.minecraft.registry.RegistryKeys;

import net.minecraft.server.MinecraftServer;

import net.minecraft.server.network.ServerPlayerEntity;

import net.minecraft.text.Text;

import net.minecraft.util.Identifier;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;



/**

 * Server-side entrypoint for CustomBlocks.

 * <p>

 * Registers blocks, items, payloads, and event handlers.

 * Delegates networking to {@link NetworkManager} and data to {@link SlotManager}.

 */

@SuppressFBWarnings("PA_PUBLIC_PRIMITIVE_ATTRIBUTE")
public class CustomBlocksMod implements ModInitializer {



    public static final String MOD_ID = "customblocks";

    public static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks");



    // Block and item registries — sized at startup from config (private; use safeSlotBlock/safeSlotItem)

    private static SlotBlock[]          SLOT_BLOCKS;

    private static SlotBlock.SlotItem[] SLOT_ITEMS;

    /** X4 — set by async update-check on SERVER_STARTED; null = up-to-date or check pending. */
    public static volatile String UPDATE_AVAILABLE_VERSION = null;



    public static final RegistryKey<net.minecraft.item.ItemGroup> CUSTOM_BLOCKS_TAB =

            RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.of(MOD_ID, "blocks"));

    // Tab icon — a flat item whose texture is the server-chosen tab image.
    // Registered once at startup; texture is swapped via resource pack reload.
    @SuppressFBWarnings("MS_PKGPROTECT")
    public static Item TAB_ICON_ITEM;

    // Phase 4C — Showcase Display Block
    @SuppressFBWarnings("MS_PKGPROTECT")
    public static ShowcaseBlock SHOWCASE_BLOCK;
    public static BlockItem SHOWCASE_ITEM;



    @Override
    @SuppressFBWarnings("ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
    public void onInitialize() {



        // ── Load config first ────────────────────────────────────────────────

        CustomBlocksConfig.load();

        int maxSlots = CustomBlocksConfig.maxSlots;



        // ── Start HTTP Server ────────────────────────────────────────────────

        ResourcePackServer.start();



        SLOT_BLOCKS = new SlotBlock[maxSlots];

        SLOT_ITEMS  = new SlotBlock.SlotItem[maxSlots];



        // ── Register slot blocks ─────────────────────────────────────────────

        for (int i = 0; i < maxSlots; i++) {

            final int idx = i;



            AbstractBlock.Settings settings = AbstractBlock.Settings.create()

                    .strength(1.5f, 6.0f)

                    .dynamicBounds()

                    .luminance(state -> SlotManager.getLightCached(idx));



            SlotBlock          block = new SlotBlock(i, settings);

            Identifier         id    = Identifier.of(MOD_ID, "slot_" + i);

            SlotBlock.SlotItem item  = new SlotBlock.SlotItem(block, new Item.Settings());



            Registry.register(Registries.BLOCK, id, block);

            Registry.register(Registries.ITEM, id, item);

            SLOT_BLOCKS[i] = block;

            SLOT_ITEMS[i]  = item;

        }



        // ── Color Square items ───────────────────────────────────────────────

        String[][] squares = {{"black", "Black"}, {"yellow", "Yellow"}, {"green", "Green"}};

        for (String[] sq : squares) {

            Identifier sqId = Identifier.of(MOD_ID, sq[0] + "_square");

            ColorSquareItem sqItem = new ColorSquareItem(sq[0], sq[1], new Item.Settings().maxCount(1));

            Registry.register(Registries.ITEM, sqId, sqItem);

        }

        Identifier customSquareId = Identifier.of(MOD_ID, ColorSquareItem.CUSTOM_SQUARE_REGISTRY_ID);
        ColorSquareItem customSquareItem = new ColorSquareItem("custom", "Custom", new Item.Settings().maxCount(1));
        Registry.register(Registries.ITEM, customSquareId, customSquareItem);



        // ── Color Triangle items ─────────────────────────────────────────────

        int[][] triColors = {{10,10,10}, {240,200,20}, {30,140,30}};

        String[][] triMeta = {{"black", "Black"}, {"yellow", "Yellow"}, {"green", "Green"}};

        for (int i = 0; i < triMeta.length; i++) {

            Identifier trId = Identifier.of(MOD_ID, triMeta[i][0] + "_triangle");

            ColorTriangleItem trItem = new ColorTriangleItem(

                triColors[i][0], triColors[i][1], triColors[i][2],

                triMeta[i][1], new Item.Settings().maxCount(1));

            Registry.register(Registries.ITEM, trId, trItem);

        }

        Identifier customTriangleId = Identifier.of(MOD_ID, ColorTriangleItem.CUSTOM_TRIANGLE_REGISTRY_ID);
        ColorTriangleItem customTriangleItem = new ColorTriangleItem(255, 255, 255, "Custom", new Item.Settings().maxCount(1));
        Registry.register(Registries.ITEM, customTriangleId, customTriangleItem);



        // ── Rainbow Rectangle ────────────────────────────────────────────────

        Identifier rectId = Identifier.of(MOD_ID, "rainbow_rectangle");

        RectangleToolItem rectItem = new RectangleToolItem(new Item.Settings().maxCount(1));

        Registry.register(Registries.ITEM, rectId, rectItem);



        // ── Golden Hexagon (UV Face Rotate/Flip) ─────────────────────────────

        Identifier hexId = Identifier.of(MOD_ID, "golden_hexagon");

        GoldenHexagonItem hexItem = new GoldenHexagonItem(new Item.Settings().maxCount(1));

        Registry.register(Registries.ITEM, hexId, hexItem);



        // ── Lumina Brush (Property Painter) ──────────────────────────────────

        Identifier brushId = Identifier.of(MOD_ID, "lumina_brush");

        LuminaBrushItem brushItem = new LuminaBrushItem(new Item.Settings().maxCount(1));

        Registry.register(Registries.ITEM, brushId, brushItem);



        // ── Amethyst Chisel (Shape Editor Shortcut) ─────────────────────────

        Identifier chiselId = Identifier.of(MOD_ID, "amethyst_chisel");

        AmethystChiselItem chiselItem = new AmethystChiselItem(new Item.Settings().maxCount(1));

        Registry.register(Registries.ITEM, chiselId, chiselItem);



        // ── Diamond Triangle (Background Studio Master) ──────────────────

        Identifier diamondId = Identifier.of(MOD_ID, "diamond_triangle");

        DiamondTriangleItem diamondItem = new DiamondTriangleItem(new Item.Settings().maxCount(1));

        Registry.register(Registries.ITEM, diamondId, diamondItem);



        // ── Tab icon item ────────────────────────────────────────────────────
        TAB_ICON_ITEM = Registry.register(
                Registries.ITEM,
                Identifier.of(MOD_ID, "tab_icon"),
                new net.minecraft.item.Item(new Item.Settings()));

        // ── Phase 4C: Showcase Display Block ─────────────────────────────────
        SHOWCASE_BLOCK = Registry.register(
                Registries.BLOCK,
                Identifier.of(MOD_ID, "showcase"),
                new ShowcaseBlock(AbstractBlock.Settings.create()
                        .mapColor(MapColor.PURPLE)
                        .strength(1.5f, 6.0f)
                        .requiresTool()));
        SHOWCASE_ITEM = Registry.register(
                Registries.ITEM,
                Identifier.of(MOD_ID, "showcase"),
                new BlockItem(SHOWCASE_BLOCK, new Item.Settings()));



        // ── Chat intercept ───────────────────────────────────────────────────

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {

            String content = message.getContent().getString();


            if (GuiManager.handleChatInput(sender, content)) return false;

            return !RectangleToolItem.handleChatInput(sender, content);

        });



        // ── Network payloads ─────────────────────────────────────────────────

        PayloadTypeRegistry.playS2C().register(FullSyncPayload.ID, FullSyncPayload.CODEC);

        PayloadTypeRegistry.playS2C().register(SlotUpdatePayload.ID, SlotUpdatePayload.CODEC);

        PayloadTypeRegistry.playS2C().register(
                com.customblocks.network.SyncCompletePayload.ID,
                com.customblocks.network.SyncCompletePayload.CODEC);

        PayloadTypeRegistry.playS2C().register(
                com.customblocks.network.ChunkedTexturePayload.ID,
                com.customblocks.network.ChunkedTexturePayload.CODEC);

        PayloadTypeRegistry.playS2C().register(

                com.customblocks.network.OpenAnimGuiPayload.ID,

                com.customblocks.network.OpenAnimGuiPayload.CODEC);

        PayloadTypeRegistry.playS2C().register(
                com.customblocks.network.OpenHudEditorPayload.ID,
                com.customblocks.network.OpenHudEditorPayload.CODEC);

        PayloadTypeRegistry.playC2S().register(
                com.customblocks.network.AnimSettingsPayload.ID,
                com.customblocks.network.AnimSettingsPayload.CODEC);

        PayloadTypeRegistry.playC2S().register(
                com.customblocks.network.SyncRequestPayload.ID,
                com.customblocks.network.SyncRequestPayload.CODEC);

        PayloadTypeRegistry.playS2C().register(
                com.customblocks.network.RpPausePayload.ID,
                com.customblocks.network.RpPausePayload.CODEC);

        // ── AnimSettings C2S handler ─────────────────────────────────────────

        ServerPlayNetworking.registerGlobalReceiver(
                com.customblocks.network.AnimSettingsPayload.ID,

                (payload, context) -> {
                    // 1.26 — rate-limit: silently discard if player is spamming
                    if (!com.customblocks.network.NetworkManager.checkAnimSettingsCooldown(
                            context.player().getUuid())) {
                        return;
                    }
                    // 7.27 — permission check (C2S packets bypass command-level guards)
                    if (!com.customblocks.command.PermissionHelper.canEdit(context.player().getCommandSource())) return;

                    context.server().execute(() -> {

                        String cid  = payload.customId();

                        String meta = payload.animMeta();

                        if (cid == null || meta == null || meta.isEmpty()) return;
                        // 7.27 — size cap: 8 KB is far more than any real mcmeta needs
                        if (meta.length() > 8192) { LOGGER.warn("[CustomBlocks] Rejected oversized animMeta from {}: {} chars", context.player().getName().getString(), meta.length()); return; }

                        if (!SlotManager.hasId(cid)) return;



                        SlotData snap = SlotManager.getById(cid);

                        UndoManager.pushUndoMutation(cid, snap, "animsettings",

                                context.player().getUuid());

                        SlotManager.setAnimMeta(cid, meta);

                        SlotManager.saveAll();



                        SlotData d = SlotManager.getById(cid);

                        if (d == null) return;

                        SlotUpdatePayload pkt = new SlotUpdatePayload(

                                "animsettings", d.index, cid, d.displayName,

                                null, d.lightLevel, d.hardness, d.soundType,

                                null, null, meta);

                        NetworkManager.broadcastUpdate(context.server(), pkt);

                        LOGGER.info("[CustomBlocks] animMeta updated for '{}' by {}", cid,

                                context.player().getName().getString());

                    });

                }

        );



        // ── SyncRequest C2S handler (Fix 7: client-initiated sync) ────────────
        ServerPlayNetworking.registerGlobalReceiver(
                com.customblocks.network.SyncRequestPayload.ID,
                (payload, context) -> {
                    context.server().execute(() -> {
                        LOGGER.info("[CustomBlocks] Received sync request from {} (hash={})",
                                context.player().getName().getString(),
                                payload.textureHash() != null && payload.textureHash().length() > 12
                                    ? payload.textureHash().substring(0, 12) + "..." : payload.textureHash());
                        NetworkManager.onSyncRequest(context.player(), payload.textureHash(), payload.slotHashesJson());
                    });
                }
        );

        // ── Creative tab ─────────────────────────────────────────────────────

        Registry.register(Registries.ITEM_GROUP, CUSTOM_BLOCKS_TAB,

                FabricItemGroup.builder()

                        .displayName(Text.translatable("itemGroup.customblocks.blocks"))

                        .icon(() -> {
                            // If a tab icon texture has been uploaded, use the dedicated item.
                            // Its texture is backed by the resource pack — no slot lookup needed.
                            if (SlotManager.getTabIconTexture() != null
                                    && SlotManager.getTabIconTexture().length > 0) {
                                return new ItemStack(TAB_ICON_ITEM);
                            }
                            // Fallback: first available slot block
                            for (SlotData d : SlotManager.allSlots()) {
                                Item si = safeSlotItem(d.index);
                                if (si != null) return new ItemStack(si);
                            }
                            return new ItemStack(Items.BOOKSHELF);
                        })

                        .entries((ctx, entries) -> {

                            for (SlotData d : SlotManager.allSlots())

                                if (!"tab_icon".equals(d.customId))

                                    if (safeSlotItem(d.index) != null) entries.add(safeSlotItem(d.index));

                            for (String col : new String[]{"black", "yellow", "green"}) {

                                Item sq = Registries.ITEM.get(Identifier.of(MOD_ID, col + "_square"));

                                if (sq != null && sq != Items.AIR) entries.add(sq);

                            }

                            for (String col : new String[]{"black", "yellow", "green"}) {

                                Item tr = Registries.ITEM.get(Identifier.of(MOD_ID, col + "_triangle"));

                                if (tr != null && tr != Items.AIR) entries.add(tr);

                            }

                            Item customSquare = Registries.ITEM.get(Identifier.of(MOD_ID, ColorSquareItem.CUSTOM_SQUARE_REGISTRY_ID));
                            if (customSquare != null && customSquare != Items.AIR) entries.add(ColorSquareItem.createCustomStack(customSquare, 0x55CCFF));

                            Item customTriangle = Registries.ITEM.get(Identifier.of(MOD_ID, ColorTriangleItem.CUSTOM_TRIANGLE_REGISTRY_ID));
                            if (customTriangle != null && customTriangle != Items.AIR) entries.add(ColorTriangleItem.createCustomStack(customTriangle, 0x55CCFF));

                            for (String tool : new String[]{"rainbow_rectangle", "golden_hexagon", "lumina_brush", "amethyst_chisel", "diamond_triangle"}) {
                                Item item = Registries.ITEM.get(Identifier.of(MOD_ID, tool));
                                if (item != null && item != Items.AIR) entries.add(item);
                            }

                        })

                        .build()

        );



        // ── Guard: cancel unauthorized custom-block breaks ───────────────────

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
            if (world.isClient()) return true;
            if (!(state.getBlock() instanceof SlotBlock)) return true;
            if (com.customblocks.command.PermissionHelper.canDelete(player.getCommandSource())) return true;
            player.sendMessage(com.customblocks.command.PermissionHelper.toolPermissionDeniedMessage(), true);
            if (player instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
                com.customblocks.gui.GuiManager.playError(sp);
            }
            return false;
        });

        // ── Block drop in survival ───────────────────────────────────────────

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {

            if (!(state.getBlock() instanceof SlotBlock)) return;

            if (player.isCreative()) return;

            world.spawnEntity(new ItemEntity(world,

                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,

                    new ItemStack(state.getBlock())));

        });



        // ── Player join → full sync via NetworkManager ───────────────────────

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            UndoManager.loadPlayer(handler.player.getUuid());
            try {
                NetworkManager.onPlayerJoin(handler.player);
                com.customblocks.core.WelcomeManager.checkAndWelcome(handler.player);
                com.customblocks.core.AchievementManager.onJoin(handler.player); // R1 offline delivery
                // X4 — notify admins about pending update on join
                String pending = UPDATE_AVAILABLE_VERSION;
                if (pending != null && handler.player.hasPermissionLevel(CustomBlocksConfig.permissionLevelAdmin)) {
                    ChatHelper.warn(handler.player,
                        "§6§l[CB Update] §r§eNew version §f" + pending + " §eavailable — §7modrinth.com/mod/customblocks");
                }
            } catch (Exception e) {
                LOGGER.error("[CustomBlocks] Error during player join for {}",
                        handler.player.getName().getString(), e);
                // 1.22 — Notify the player so they know why blocks may be invisible
                try {
                    com.customblocks.gui.ChatHelper.warn(handler.player,
                            "§c[CB] Block sync incomplete — some blocks may be invisible. §7Type §f/cb sync §7to retry.");
                } catch (Exception ignored) {}
            }
        });



        // ── Player disconnect → cleanup ──────────────────────────────────────

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {

            NetworkManager.onPlayerDisconnect(handler.player);

            UndoManager.savePlayer(handler.player.getUuid());
            UndoManager.releasePlayer(handler.player.getUuid());

            RectangleToolItem.onPlayerDisconnect(handler.player.getUuid());

            com.customblocks.gui.FeedbackHelper.clearBossBar(handler.player);

            GuiManager.onPlayerDisconnect(handler.player.getUuid());

            com.customblocks.core.DraftManager.drop(handler.player.getUuid());

            com.customblocks.command.DidYouMean.onPlayerDisconnect(handler.player.getUuid());

            com.customblocks.core.MacroManager.onPlayerDisconnect(handler.player.getUuid());

            com.customblocks.core.SnapshotManager.clearPanic(handler.player.getUuid());

        });



        ServerTickEvents.END_SERVER_TICK.register(server -> {

            NetworkManager.onServerTick(server);
            SlotManager.tickStartupLoad();

            RectangleToolItem.tickSessionCleanup();

            GuiManager.checkPendingFaceImports(server);

            // 1.32 — flush deferred pack pushes for players who closed their GUI
            com.customblocks.network.ResourcePackServer.tickPendingPackPushes(server);

            // Phase 4C — advance showcase block displays
            ShowcaseManager.tick(server);

        });





        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            UndoManager.saveGlobal();
            SlotManager.flushSave();
            com.customblocks.core.PlacementStats.save(); // K1
            com.customblocks.core.AchievementManager.save(); // R1
            com.customblocks.core.DropConfigManager.save(); // Phase 12.2
            com.customblocks.core.FavoritesManager.flushSave();
            com.customblocks.core.CategoryManager.saveAll();
            com.customblocks.core.AutoCategorizeManager.saveAll();
            com.customblocks.core.CategoryDisplayBlockManager.saveAll();
            com.customblocks.core.LockManager.save();
            com.customblocks.core.BlockNotesManager.save();
            com.customblocks.core.TemplateManager.save();
            com.customblocks.core.WelcomeManager.save();
            com.customblocks.core.SnapshotManager.stop();
            com.customblocks.core.DraftManager.dropAll();
        });

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            UndoManager.loadGlobal();
            // Phase 9.1 — register action-bar flash + 80% warning callback for undo pushes
            com.customblocks.core.UndoManager.onUndoPushed = (uuid, description) -> {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                if (p == null) return;
                int size = com.customblocks.core.UndoManager.undoSize(uuid);
                int max  = com.customblocks.CustomBlocksConfig.maxUndoDepth;
                String bar = "§a↩ §fUndoable: §e" + description + " §8(" + size + "/" + max + ")";
                server.execute(() -> p.sendMessage(net.minecraft.text.Text.literal(bar), true));
                // 80% capacity warning (only send once — when size crosses the threshold upward)
                if (max > 0 && size == (int) (max * 0.8)) {
                    server.execute(() -> com.customblocks.gui.ChatHelper.warn(p,
                        "Undo stack is 80% full (" + size + "/" + max + "). Oldest entries will be dropped soon."));
                }
            };
            // Phase 12.1 — broadcast achievement unlocks to all online players
            com.customblocks.core.AchievementManager.onBroadcast = (playerName, achievement) -> {
                String msg = "§6§l🏆 §r§e" + playerName + " §7unlocked: §f" + achievement.title;
                server.execute(() -> {
                    for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                        com.customblocks.gui.ChatHelper.info(p, msg);
                    }
                });
            };
            ResourcePackServer.setServer(server);
            com.customblocks.core.SampleBlocksLoader.maybeLoadSamples(server); // X7/D1
            // Phase A6: one-time migration nudge so admins review hardened permission gates.
            try {
                java.nio.file.Path marker = java.nio.file.Path.of("config", "customblocks", ".permissions_hardened_notice_v2");
                if (!java.nio.file.Files.exists(marker)) {
                    for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                        if (p.hasPermissionLevel(CustomBlocksConfig.permissionLevelAdmin)) {
                            ChatHelper.warn(p, "Permissions hardened in v2 — review /cb config -> Permissions.");
                        }
                    }
                    java.nio.file.Files.createDirectories(marker.getParent());
                    java.nio.file.Files.writeString(
                        marker,
                        "shown=true\n",
                        java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
                    );
                }
            } catch (Exception ex) {
                LOGGER.warn("[CustomBlocks] Could not write permission migration marker", ex);
            }

            // X4 — async Modrinth update check (daemon — never blocks startup)
            Thread updateChecker = new Thread(() -> {
                try {
                    String current = "2.0.0";
                    java.net.HttpURLConnection con = (java.net.HttpURLConnection)
                        new java.net.URL("https://api.modrinth.com/v2/project/customblocks/version" +
                            "?game_versions=%5B%221.21.1%22%5D&loaders=%5B%22fabric%22%5D")
                        .openConnection();
                    con.setRequestMethod("GET");
                    con.setConnectTimeout(6000);
                    con.setReadTimeout(6000);
                    con.setRequestProperty("User-Agent", "CustomBlocks/" + current + " (update-check; github.com/3liSY/CustomBlocks)");
                    if (con.getResponseCode() == 200) {
                        String body = new String(con.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(body).getAsJsonArray();
                        if (!arr.isEmpty()) {
                            String latest = arr.get(0).getAsJsonObject().get("version_number").getAsString();
                            if (!latest.equals(current)) {
                                UPDATE_AVAILABLE_VERSION = latest;
                                LOGGER.info("[CustomBlocks] §eUpdate available: §f{} → {} | modrinth.com/mod/customblocks", current, latest);
                            } else {
                                LOGGER.info("[CustomBlocks] Up to date ({}).", current);
                            }
                        }
                    }
                } catch (Exception ex) {
                    LOGGER.debug("[CustomBlocks] Update check skipped: {}", ex.getMessage());
                }
            }, "CustomBlocks-UpdateCheck");
            updateChecker.setDaemon(true);
            updateChecker.start();
        });

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> {

            ResourcePackServer.stop();

        });



        // ── Commands & Data ──────────────────────────────────────────────────

        CustomBlockCommand.register();

        SlotManager.loadAll();
        SlotManager.initLightCache(maxSlots); // O7 — prime flat luminance cache

        // 1.16 Guard 4 — startup registry integrity check
        LOGGER.info("[CustomBlocks] Registered {} block slots (slot_0 to slot_{}).", maxSlots, maxSlots - 1);
        int orphaned = SlotManager.countOrphanedBlocks();
        if (orphaned > 0) {
            int needed = SlotManager.highestUsedSlotIndex() + 1;
            LOGGER.warn("[CustomBlocks] ⚠ WARNING: {} block definition(s) have indices above maxSlots ({}). "
                + "These blocks exist in config but cannot be placed or seen. "
                + "Raise maxSlots to at least {} in config.json and restart to restore them.",
                orphaned, maxSlots, needed);
        }
        com.customblocks.core.PlacementStats.load(); // K1
        com.customblocks.core.AchievementManager.load(); // R1
        com.customblocks.core.DropConfigManager.load(); // Phase 12.2
        com.customblocks.core.FavoritesManager.load();
        com.customblocks.core.CategoryManager.loadAll();
        com.customblocks.core.AutoCategorizeManager.loadAll();
        com.customblocks.core.CategoryDisplayBlockManager.loadAll();
        com.customblocks.core.LockManager.load();
        com.customblocks.core.BlockNotesManager.load();
        com.customblocks.core.TemplateManager.load();
        com.customblocks.core.WelcomeManager.load();
        com.customblocks.core.SnapshotManager.start(CustomBlocksConfig.autoSnapshotMinutes);
        com.customblocks.core.TrashManager.load(); // V4-18

        // ── Display Block Hooks ──────────────────────────────────────────────
        // Detect placement of a tagged display block, intercept right-clicks
        // to open the category browser, and intercept sneak+right-click to
        // pick the block back up.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient()) return net.minecraft.util.ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity sp)) return net.minecraft.util.ActionResult.PASS;

            net.minecraft.util.math.BlockPos hitPos = hit.getBlockPos();
            String dimId = sp.getServerWorld().getRegistryKey().getValue().toString();

            String existingCat = com.customblocks.core.CategoryDisplayBlockManager.getCategoryAt(dimId, hitPos);
            if (existingCat != null) {
                if (sp.isSneaking()) {
                    com.customblocks.core.Category cat = com.customblocks.core.CategoryManager.getCategory(existingCat);
                    if (cat != null) {
                        ItemStack giveBack = com.customblocks.core.CategoryDisplayBlockManager.createDisplayBlockStack(cat);
                        if (!sp.getInventory().insertStack(giveBack)) {
                            sp.dropItem(giveBack, false);
                        }
                    }
                    com.customblocks.core.CategoryDisplayBlockManager.unregister(dimId, hitPos);
                    sp.getServerWorld().breakBlock(hitPos, false, sp);
                    sp.sendMessage(ChatHelper.rawPrefixed(ChatHelper.formattedKey("cmd.display_block_pickup")), true);
                    return net.minecraft.util.ActionResult.SUCCESS;
                } else {
                    com.customblocks.core.Category cat = com.customblocks.core.CategoryManager.getCategory(existingCat);
                    if (cat != null) {
                        GuiManager.openCategoryDetail(sp, existingCat, 0);
                    } else {
                        sp.sendMessage(ChatHelper.rawPrefixed(ChatHelper.formattedKey("cmd.display_block_category_gone")), false);
                    }
                    return net.minecraft.util.ActionResult.SUCCESS;
                }
            }

            ItemStack held = sp.getStackInHand(hand);
            String catKey = com.customblocks.core.CategoryDisplayBlockManager.readCategoryFromStack(held);
            if (catKey != null) {
                net.minecraft.util.math.BlockPos placePos = hitPos.offset(hit.getSide());
                if (sp.getServerWorld().getBlockState(placePos).isReplaceable()) {
                    com.customblocks.core.CategoryDisplayBlockManager.register(dimId, placePos, catKey);
                }
            }
            return net.minecraft.util.ActionResult.PASS;
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (world.isClient()) return;
            String dimId = world.getRegistryKey().getValue().toString();
            com.customblocks.core.CategoryDisplayBlockManager.unregister(dimId, pos);
        });



        // Regenerate the server resource pack now that slot data is loaded.

        // ResourcePackServer.start() runs before loadAll(), so the initial

        // pack ZIP is empty — this rebuild populates it with real textures.

        ResourcePackServer.updatePack();



        LOGGER.info("[CustomBlocks] Initialized. {} slot(s) loaded, maxSlots={}.",

                SlotManager.usedSlots(), maxSlots);

    }



    // ── Public helpers ───────────────────────────────────────────────────────



    /** Safe SLOT_ITEMS accessor — returns null if index is out of range. */

    public static SlotBlock.SlotItem safeSlotItem(int index) {

        if (SLOT_ITEMS == null || index < 0 || index >= SLOT_ITEMS.length || SLOT_ITEMS[index] == null) return null;

        return SLOT_ITEMS[index];

    }

    /** Safe SLOT_BLOCKS accessor — returns null if index is out of range. */
    public static SlotBlock safeSlotBlock(int index) {
        if (SLOT_BLOCKS == null || index < 0 || index >= SLOT_BLOCKS.length || SLOT_BLOCKS[index] == null) return null;
        return SLOT_BLOCKS[index];
    }

    /** Returns the registered SLOT_ITEMS length (0 if not yet initialised). */
    public static int slotRegistrySize() {
        return SLOT_ITEMS == null ? 0 : SLOT_ITEMS.length;
    }



    // ── Legacy bridge methods (delegate to NetworkManager) ───────────────────



    /**

     * Broadcast a slot update to all players.

     * @deprecated Use {@link NetworkManager#broadcastUpdate(MinecraftServer, SlotUpdatePayload)} directly.

     */

    @Deprecated

    public static void broadcastUpdate(MinecraftServer server, SlotUpdatePayload payload) {

        NetworkManager.broadcastUpdate(server, payload);

    }



    /**

     * Broadcast full sync to all players.

     * @deprecated Use {@link NetworkManager#broadcastFullSync(MinecraftServer)} directly.

     */

    @Deprecated

    public static void broadcastFullSync(MinecraftServer server) {

        NetworkManager.broadcastFullSync(server);

    }

}

