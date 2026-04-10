package com.customblocks;

import com.customblocks.block.SlotBlock;
import com.customblocks.gui.GuiManager;
import com.customblocks.item.ColorSquareItem;
import com.customblocks.item.ColorTriangleItem;
import com.customblocks.item.RectangleToolItem;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import com.customblocks.command.CustomBlockCommand;
import com.customblocks.network.FullSyncPayload;
import com.customblocks.network.SlotUpdatePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class CustomBlocksMod implements ModInitializer {

    public static final String MOD_ID = "customblocks";
    public static final String DOWNLOAD_URL = "https://github.com/3liSY/CustomBlockss/releases/latest";
    public static final net.minecraft.util.Identifier VERSION_CHANNEL = net.minecraft.util.Identifier.of(MOD_ID, "version_check");
    public static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks");

    public static final SlotBlock[]      SLOT_BLOCKS = new SlotBlock[SlotManager.MAX_SLOTS];
    public static final SlotBlock.SlotItem[] SLOT_ITEMS = new SlotBlock.SlotItem[SlotManager.MAX_SLOTS];

    // Batch texture sending — ConcurrentHashMap so broadcastUpdate is safe from any thread
    private static final Map<UUID, ConcurrentLinkedQueue<SlotUpdatePayload>> PENDING_TEXTURES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, Integer> SEND_DELAY = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Text> PENDING_KICK_MESSAGES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, Integer> KICK_TIMERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int DELAY_TICKS = 60;  // 3s after join before sending textures
    private static final int BATCH_SIZE   = 4;  // textures per tick — slow & steady, no kick risk

    public static final RegistryKey<net.minecraft.item.ItemGroup> CUSTOM_BLOCKS_TAB =
            RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.of(MOD_ID, "blocks"));

    @Override
    public void onInitialize() {

        // Register 512 slot blocks — ALWAYS the same 512, no runtime changes = no registry mismatch
        for (int i = 0; i < SlotManager.MAX_SLOTS; i++) {
            final int idx = i;

            // Dynamic luminance — reads SlotManager at runtime, no restart needed
            AbstractBlock.Settings settings = AbstractBlock.Settings.create()
                    .strength(1.5f, 6.0f)
                    .luminance(state -> {
                        SlotManager.SlotData d = SlotManager.getBySlot("slot_" + idx);
                        return d != null ? d.lightLevel : 0;
                    });

            SlotBlock       block = new SlotBlock(i, settings);
            Identifier      id    = Identifier.of(MOD_ID, "slot_" + i);
            SlotBlock.SlotItem item  = new SlotBlock.SlotItem(block,
                    new Item.Settings());

            Registry.register(Registries.BLOCK, id, block);
            Registry.register(Registries.ITEM, id, item);
            SLOT_BLOCKS[i] = block;
            SLOT_ITEMS[i]  = item;
        }

        // ── Color Square items ────────────────────────────────────────────────
        // colorWord is just "black"/"yellow"/"green" — no underscore.
        // Item IDs stay "black_square" etc. for backwards compat.
        String[][] squares = {{"black", "Black"}, {"yellow", "Yellow"}, {"green", "Green"}};
        for (String[] sq : squares) {
            Identifier sqId = Identifier.of(MOD_ID, sq[0] + "_square");
            ColorSquareItem sqItem = new ColorSquareItem(sq[0], sq[1],
                    new Item.Settings().maxCount(1));
            Registry.register(Registries.ITEM, sqId, sqItem);
        }

        // ── Color Triangle items ─────────────────────────────────────────────────
        int[][] triColors = {{10,10,10}, {240,200,20}, {30,140,30}};
        String[][] triMeta = {{"black", "Black"}, {"yellow", "Yellow"}, {"green", "Green"}};
        for (int i = 0; i < triMeta.length; i++) {
            Identifier trId = Identifier.of(MOD_ID, triMeta[i][0] + "_triangle");
            ColorTriangleItem trItem = new ColorTriangleItem(
                triColors[i][0], triColors[i][1], triColors[i][2],
                triMeta[i][1], new Item.Settings().maxCount(1));
            Registry.register(Registries.ITEM, trId, trItem);
        }

        // ── Rainbow Rectangle — unique face-paint tool ───────────────────────
        Identifier rectId = Identifier.of(MOD_ID, "rainbow_rectangle");
        RectangleToolItem rectItem = new RectangleToolItem(new Item.Settings().maxCount(1));
        Registry.register(Registries.ITEM, rectId, rectItem);

        // ── Chat intercept for Rectangle URL sessions ────────────────────────
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            String content = message.getContent().getString();
            // GUI chat input takes priority
            if (GuiManager.handleChatInput(sender, content)) return false;
            // Rectangle face-paint sessions
            return !RectangleToolItem.handleChatInput(sender, content);
        });

        // Version check — store pending kick, delivered as clickable chat on JOIN
        ServerLoginNetworking.registerGlobalReceiver(VERSION_CHANNEL, (server, handler, understood, buf, synchronizer, responseSender) -> {
            String playerName = getProfileName(handler);
            if (playerName == null) return;
            if (!understood) {
                PENDING_KICK_MESSAGES.put(playerName, Text.empty()
                    .append(Text.literal("§cThis server requires §fCustomBlocks§c! "))
                    .append(Text.literal("§b§nClick here to download it")
                        .styled(s -> s
                            .withClickEvent(new net.minecraft.text.ClickEvent(
                                net.minecraft.text.ClickEvent.Action.OPEN_URL, DOWNLOAD_URL))
                            .withUnderline(true)
                            .withColor(net.minecraft.util.Formatting.AQUA))));
                return;
            }
            String clientVersion = buf.readString(32767);
            String serverVersion = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("0.0.0");
            if (!clientVersion.equals(serverVersion)) {
                PENDING_KICK_MESSAGES.put(playerName, Text.empty()
                    .append(Text.literal("§cYour CustomBlocks is outdated! §7Server: §a" + serverVersion + " §7| You: §c" + clientVersion + " "))
                    .append(Text.literal("§b§nClick here to update")
                        .styled(s -> s
                            .withClickEvent(new net.minecraft.text.ClickEvent(
                                net.minecraft.text.ClickEvent.Action.OPEN_URL, DOWNLOAD_URL))
                            .withUnderline(true)
                            .withColor(net.minecraft.util.Formatting.AQUA))));
            }
        });

        ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) ->
            sender.sendPacket(VERSION_CHANNEL, new net.minecraft.network.PacketByteBuf(io.netty.buffer.Unpooled.buffer())));

        // Network
        PayloadTypeRegistry.playS2C().register(FullSyncPayload.ID, FullSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SlotUpdatePayload.ID, SlotUpdatePayload.CODEC);

        // Creative tab
        Registry.register(Registries.ITEM_GROUP, CUSTOM_BLOCKS_TAB,
                FabricItemGroup.builder()
                        .displayName(Text.literal("CustomBlocks"))
                        .icon(() -> {
                            SlotManager.SlotData icon = SlotManager.getById("tab_icon");
                            if (icon != null && safeSlotItem(icon.index) != null) return new ItemStack(safeSlotItem(icon.index));
                            for (SlotManager.SlotData d : SlotManager.allSlots())
                                if (!d.customId.equals("tab_icon"))
                                    return safeSlotItem(d.index) != null ? new ItemStack(safeSlotItem(d.index)) : ItemStack.EMPTY;
                            return new ItemStack(Items.BOOKSHELF);
                        })
                        .entries((ctx, entries) -> {
                            for (SlotManager.SlotData d : SlotManager.allSlots())
                                if (!d.customId.equals("tab_icon"))
                                    if (safeSlotItem(d.index) != null) entries.add(safeSlotItem(d.index));
                            // Color square items
                            for (String col : new String[]{"black", "yellow", "green"}) {
                                net.minecraft.item.Item sq = Registries.ITEM.get(Identifier.of(MOD_ID, col + "_square"));
                                if (sq != null && sq != net.minecraft.item.Items.AIR)
                                    entries.add(sq);
                            }
                            // Color triangle items
                            for (String col : new String[]{"black", "yellow", "green"}) {
                                net.minecraft.item.Item tr = Registries.ITEM.get(Identifier.of(MOD_ID, col + "_triangle"));
                                if (tr != null && tr != net.minecraft.item.Items.AIR)
                                    entries.add(tr);
                            }
                            // Rainbow Rectangle
                            net.minecraft.item.Item rect = Registries.ITEM.get(Identifier.of(MOD_ID, "rainbow_rectangle"));
                            if (rect != null && rect != net.minecraft.item.Items.AIR)
                                entries.add(rect);
                        })
                        .build()
        );

        // Survival block drop
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (!(state.getBlock() instanceof SlotBlock)) return;
            if (player.isCreative()) return;
            world.spawnEntity(new ItemEntity(world,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    new ItemStack(state.getBlock())));
        });

        // On join: send metadata immediately, queue textures for delayed batch sending
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            // Check if this player needs to be kicked (missing/outdated mod)
            String playerName = handler.player.getName().getString();
            Text kickMessage = PENDING_KICK_MESSAGES.remove(playerName);
            if (kickMessage != null) {
                handler.player.sendMessage(kickMessage, false);
                KICK_TIMERS.put(handler.player.getUuid(), 40); // kick after 2 seconds
                return;
            }
            List<FullSyncPayload.SlotEntry> meta = new ArrayList<>();
            for (SlotManager.SlotData d : SlotManager.allSlots()) {
                meta.add(new FullSyncPayload.SlotEntry(
                        d.index, d.customId, d.displayName, null,
                        d.lightLevel, d.hardness, d.soundType));
            }
            ServerPlayNetworking.send(handler.player,
                    new FullSyncPayload(meta, SlotManager.getTabIconTexture()));

            ConcurrentLinkedQueue<SlotUpdatePayload> queue = new ConcurrentLinkedQueue<>();
            SlotManager.allSlots().stream()
                    .sorted(Comparator.comparingInt(d -> d.index))
                    .forEach(d -> {
                        // Queue main texture
                        if (d.texture != null && d.texture.length > 0)
                            queue.add(new SlotUpdatePayload("add", d.index, d.customId, d.displayName,
                                    d.texture, d.lightLevel, d.hardness, d.soundType));
                        // Queue each face texture override so the joining player sees them
                        for (var faceEntry : d.faceTextures.entrySet()) {
                            queue.add(new SlotUpdatePayload("setface", d.index, d.customId, null,
                                    faceEntry.getValue(), d.lightLevel, d.hardness, d.soundType,
                                    faceEntry.getKey()));
                        }
                    });
            UUID uuid = handler.player.getUuid();
            PENDING_TEXTURES.put(uuid, queue);
            SEND_DELAY.put(uuid, DELAY_TICKS);

        });

        // On disconnect: clean up
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.player.getUuid();
            PENDING_TEXTURES.remove(uuid);
            SEND_DELAY.remove(uuid);
            KICK_TIMERS.remove(uuid);
        });

        // Each tick: process delayed kicks + drip-feed queued textures
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                UUID uuid = player.getUuid();
                // Delayed kick for missing/outdated mod
                Integer kickTimer = KICK_TIMERS.get(uuid);
                if (kickTimer != null) {
                    if (kickTimer <= 0) {
                        KICK_TIMERS.remove(uuid);
                        player.networkHandler.disconnect(Text.literal("§cDownload CustomBlocks: " + DOWNLOAD_URL));
                    } else {
                        KICK_TIMERS.put(uuid, kickTimer - 1);
                    }
                    continue;
                }
                Integer delay = SEND_DELAY.get(uuid);
                if (delay == null) continue;
                if (delay > 0) { SEND_DELAY.put(uuid, delay - 1); continue; }
                ConcurrentLinkedQueue<SlotUpdatePayload> queue = PENDING_TEXTURES.get(uuid);
                if (queue == null) { SEND_DELAY.remove(uuid); continue; }
                int sent = 0;
                while (sent < BATCH_SIZE) {
                    // Re-fetch from map each time so we use the latest queue if broadcastUpdate swapped it
                    ConcurrentLinkedQueue<SlotUpdatePayload> current = PENDING_TEXTURES.get(uuid);
                    if (current == null || current.isEmpty()) break;
                    SlotUpdatePayload pkt = current.poll();
                    if (pkt != null) { ServerPlayNetworking.send(player, pkt); sent++; }
                }
                ConcurrentLinkedQueue<SlotUpdatePayload> afterQueue = PENDING_TEXTURES.get(uuid);
                if (afterQueue == null || afterQueue.isEmpty()) {
                    PENDING_TEXTURES.remove(uuid);
                    SEND_DELAY.remove(uuid);
                }
            }
        });

        CustomBlockCommand.register();
        SlotManager.loadAll();

        LOGGER.info("[CustomBlocks] [CustomBlocks] Initialized. {} slot(s) loaded.", SlotManager.usedSlots());
    }

    /** Re-send full sync + all textures to every connected player (used by /cb reload). */
    public static void broadcastFullSync(MinecraftServer server) {
        for (var player : server.getPlayerManager().getPlayerList()) {
            List<FullSyncPayload.SlotEntry> meta = new java.util.ArrayList<>();
            for (SlotManager.SlotData d : SlotManager.allSlots())
                meta.add(new FullSyncPayload.SlotEntry(d.index, d.customId, d.displayName, null, d.lightLevel, d.hardness, d.soundType));
            ServerPlayNetworking.send(player, new FullSyncPayload(meta, SlotManager.getTabIconTexture()));
            for (SlotManager.SlotData d : SlotManager.allSlots()) {
                if (d.texture != null && d.texture.length > 0)
                    ServerPlayNetworking.send(player, new SlotUpdatePayload("add", d.index, d.customId, d.displayName, d.texture, d.lightLevel, d.hardness, d.soundType));
                for (var fe : d.faceTextures.entrySet())
                    ServerPlayNetworking.send(player, new SlotUpdatePayload("setface", d.index, d.customId, null, fe.getValue(), d.lightLevel, d.hardness, d.soundType, fe.getKey()));
            }
        }
    }

    /** Safe SLOT_ITEMS accessor — returns null if index is out of range. */
    /** Gets player name from login handler via reflection (GameProfile is set after auth). */
    private static String getProfileName(net.minecraft.server.network.ServerLoginNetworkHandler handler) {
        for (java.lang.reflect.Field f : handler.getClass().getDeclaredFields()) {
            if (f.getType() == com.mojang.authlib.GameProfile.class) {
                f.setAccessible(true);
                try {
                    com.mojang.authlib.GameProfile gp = (com.mojang.authlib.GameProfile) f.get(handler);
                    if (gp != null) return gp.getName();
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /** Safe SLOT_ITEMS accessor — returns null if index is out of range. */
    public static SlotBlock.SlotItem safeSlotItem(int index) {
        if (index < 0 || index >= SLOT_ITEMS.length || SLOT_ITEMS[index] == null) return null;
        return SLOT_ITEMS[index];
    }

    public static void broadcastUpdate(MinecraftServer server, SlotUpdatePayload payload) {
        for (var player : server.getPlayerManager().getPlayerList()) {
            UUID uuid = player.getUuid();
            ConcurrentLinkedQueue<SlotUpdatePayload> oldQueue = PENDING_TEXTURES.get(uuid);
            if (oldQueue != null && !oldQueue.isEmpty()) {
                // Player is mid-sync — build a new queue replacing matching "add"/"retexture" entry
                ConcurrentLinkedQueue<SlotUpdatePayload> newQueue = new ConcurrentLinkedQueue<>();
                boolean replaced = false;
                for (SlotUpdatePayload queued : oldQueue) {
                    if (queued.customId() != null && queued.customId().equals(payload.customId())) {
                        newQueue.add(payload);
                        replaced = true;
                    } else {
                        newQueue.add(queued);
                    }
                }
                PENDING_TEXTURES.put(uuid, newQueue);
                if (!replaced) {
                    ServerPlayNetworking.send(player, payload);
                }
            } else {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }
}
