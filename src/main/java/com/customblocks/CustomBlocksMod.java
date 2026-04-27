package com.customblocks;



import com.customblocks.block.SlotBlock;

import com.customblocks.command.CustomBlockCommand;

import com.customblocks.core.SlotData;

import com.customblocks.core.SlotManager;

import com.customblocks.core.UndoManager;

import com.customblocks.gui.GuiManager;

import com.customblocks.item.ColorSquareItem;

import com.customblocks.item.ColorTriangleItem;

import com.customblocks.item.RectangleToolItem;

import com.customblocks.network.FullSyncPayload;

import com.customblocks.network.NetworkManager;

import com.customblocks.network.ResourcePackServer;

import com.customblocks.network.SlotUpdatePayload;

import com.customblocks.network.SyncCompletePayload;

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

import net.minecraft.entity.ItemEntity;

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

public class CustomBlocksMod implements ModInitializer {



    public static final String MOD_ID = "customblocks";

    public static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks");



    // Block and item registries — sized at startup from config

    public static SlotBlock[]          SLOT_BLOCKS;

    public static SlotBlock.SlotItem[] SLOT_ITEMS;



    public static final RegistryKey<net.minecraft.item.ItemGroup> CUSTOM_BLOCKS_TAB =

            RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.of(MOD_ID, "blocks"));



    @Override

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

                    .luminance(state -> {

                        SlotData d = SlotManager.getByIndex(idx);

                        return d != null ? d.lightLevel : 0;

                    });



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



        // ── Rainbow Rectangle ────────────────────────────────────────────────

        Identifier rectId = Identifier.of(MOD_ID, "rainbow_rectangle");

        RectangleToolItem rectItem = new RectangleToolItem(new Item.Settings().maxCount(1));

        Registry.register(Registries.ITEM, rectId, rectItem);



        // ── Chat intercept ───────────────────────────────────────────────────

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {

            String content = message.getContent().getString();

            if (com.customblocks.assistant.AssistantManager.handleChatCommand(sender, content)) return false;

            if (GuiManager.handleChatInput(sender, content)) return false;

            return !RectangleToolItem.handleChatInput(sender, content);

        });



        // ── Network payloads ─────────────────────────────────────────────────

        PayloadTypeRegistry.playS2C().register(FullSyncPayload.ID, FullSyncPayload.CODEC);

        PayloadTypeRegistry.playS2C().register(SlotUpdatePayload.ID, SlotUpdatePayload.CODEC);

        PayloadTypeRegistry.playS2C().register(SyncCompletePayload.ID, SyncCompletePayload.CODEC);

        PayloadTypeRegistry.playS2C().register(
                com.customblocks.network.ChunkedTexturePayload.ID,
                com.customblocks.network.ChunkedTexturePayload.CODEC);

        PayloadTypeRegistry.playS2C().register(

                com.customblocks.network.OpenAnimGuiPayload.ID,

                com.customblocks.network.OpenAnimGuiPayload.CODEC);

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

                    context.server().execute(() -> {

                        String cid  = payload.customId();

                        String meta = payload.animMeta();

                        if (cid == null || meta == null || meta.isEmpty()) return;

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
                        NetworkManager.onSyncRequest(context.player(), payload.textureHash());
                    });
                }
        );

        // ── Creative tab ─────────────────────────────────────────────────────

        Registry.register(Registries.ITEM_GROUP, CUSTOM_BLOCKS_TAB,

                FabricItemGroup.builder()

                        .displayName(Text.literal("CustomBlocks"))

                        .icon(() -> {

                            SlotData icon = SlotManager.getById("tab_icon");

                            if (icon != null && safeSlotItem(icon.index) != null)

                                return new ItemStack(safeSlotItem(icon.index));

                            for (SlotData d : SlotManager.allSlots())

                                if (!"tab_icon".equals(d.customId))

                                    return safeSlotItem(d.index) != null

                                            ? new ItemStack(safeSlotItem(d.index)) : ItemStack.EMPTY;

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

                            Item rect = Registries.ITEM.get(Identifier.of(MOD_ID, "rainbow_rectangle"));

                            if (rect != null && rect != Items.AIR) entries.add(rect);

                        })

                        .build()

        );



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
            try {
                NetworkManager.onPlayerJoin(handler.player);
            } catch (Exception e) {
                LOGGER.error("[CustomBlocks] Error during player join for {}",
                        handler.player.getName().getString(), e);
            }
        });



        // ── Player disconnect → cleanup ──────────────────────────────────────

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {

            NetworkManager.onPlayerDisconnect(handler.player);

            UndoManager.clearPlayer(handler.player.getUuid());

            RectangleToolItem.onPlayerDisconnect(handler.player.getUuid());

            GuiManager.onPlayerDisconnect(handler.player.getUuid());

        });



        ServerTickEvents.END_SERVER_TICK.register(server -> {

            com.customblocks.assistant.AssistantManager.tick(server);

            NetworkManager.onServerTick(server);

            RectangleToolItem.tickSessionCleanup();

            GuiManager.checkPendingFaceImports(server);

        });



        // ── Assistant Interaction (Right-Click NPC) ──────────────────────────

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {

            if (!world.isClient
                    && player instanceof ServerPlayerEntity serverPlayer
                    && com.customblocks.assistant.AssistantManager.isManagedAssistant(entity)) {

                GuiManager.openAssistantControl(serverPlayer);

                return net.minecraft.util.ActionResult.SUCCESS;

            }

            return net.minecraft.util.ActionResult.PASS;

        });

        // ── Assistant Diamond Command (Go Here) ──────────────────────────────

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {

            if (!world.isClient && player.getStackInHand(hand).isOf(Items.DIAMOND)) {

                if (com.customblocks.assistant.AssistantManager.isSpawned()) {

                    net.minecraft.util.math.BlockPos pos = hitResult.getBlockPos().offset(hitResult.getSide());

                    com.customblocks.assistant.AssistantManager.setFollowing(false, null);

                    com.customblocks.assistant.AssistantManager.orderMoveTo(pos);

                    player.sendMessage(Text.literal("§0§l[§b§lAI§0§l] §fMoving there. §a✔"), false);

                }

            }

            return net.minecraft.util.ActionResult.PASS;

        });



        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            SlotManager.flushSave();
            com.customblocks.assistant.AssistantManager.hide();

        });

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ResourcePackServer.setServer(server);
        });

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> {

            ResourcePackServer.stop();

        });



        // ── Commands & Data ──────────────────────────────────────────────────

        CustomBlockCommand.register();

        SlotManager.loadAll();



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

