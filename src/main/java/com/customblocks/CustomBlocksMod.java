// 
// Decompiled by Procyon v0.6.0
// 

package com.customblocks;

import net.minecraft.class_7924;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.LoggerFactory;
import net.minecraft.class_1297;
import net.minecraft.class_1542;
import net.minecraft.class_2586;
import net.minecraft.class_2680;
import net.minecraft.class_2338;
import net.minecraft.class_1657;
import net.minecraft.class_1937;
import java.util.List;
import java.util.Comparator;
import java.util.ArrayList;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.class_3244;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.class_3222;
import net.minecraft.server.MinecraftServer;
import java.util.Iterator;
import com.customblocks.command.CustomBlockCommand;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.class_1802;
import net.minecraft.class_1935;
import net.minecraft.class_1799;
import net.minecraft.class_2561;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.class_9139;
import net.minecraft.class_8710;
import com.customblocks.network.FullSyncPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import com.customblocks.item.ColorSquareItem;
import com.customblocks.item.ColorTriangleItem;
import net.minecraft.class_2378;
import net.minecraft.class_7923;
import net.minecraft.class_1792;
import net.minecraft.class_2960;
import net.minecraft.class_4970;
import net.minecraft.class_1761;
import net.minecraft.class_5321;
import com.customblocks.network.SlotUpdatePayload;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.UUID;
import java.util.Map;
import com.customblocks.block.SlotBlock;
import org.slf4j.Logger;
import net.fabricmc.api.ModInitializer;

public class CustomBlocksMod implements ModInitializer
{
    public static final String MOD_ID = "customblocks";
    public static final Logger LOGGER;
    public static final SlotBlock[] SLOT_BLOCKS;
    public static final SlotBlock.SlotItem[] SLOT_ITEMS;
    private static final Map<UUID, ConcurrentLinkedQueue<SlotUpdatePayload>> PENDING_TEXTURES;
    private static final Map<UUID, Integer> SEND_DELAY;
    private static final int DELAY_TICKS = 60;
    private static final int BATCH_SIZE = 4;
    public static final class_5321<class_1761> CUSTOM_BLOCKS_TAB;
    
    public void onInitialize() {
        for (int i = 0; i < 512; ++i) {
            final int idx = i;
            final class_4970.class_2251 settings = class_4970.class_2251.method_9637().method_9629(1.5f, 6.0f).method_9631(state -> {
                final SlotManager.SlotData d = SlotManager.getBySlot("slot_" + idx);
                return (d != null) ? d.lightLevel : 0;
            });
            final SlotBlock block = new SlotBlock(i, settings);
            final class_2960 id = class_2960.method_60655("customblocks", "slot_" + i);
            final SlotBlock.SlotItem item = new SlotBlock.SlotItem(block, new class_1792.class_1793());
            class_2378.method_10230((class_2378)class_7923.field_41175, id, (Object)block);
            class_2378.method_10230((class_2378)class_7923.field_41178, id, (Object)item);
            CustomBlocksMod.SLOT_BLOCKS[i] = block;
            CustomBlocksMod.SLOT_ITEMS[i] = item;
        }
        final String[][] array;
        final String[][] squares = array = new String[][] { { "black", "Black" }, { "yellow", "Yellow" }, { "green", "Green" } };
        for (int length = array.length, j = 0; j < length; ++j) {
            final String[] sq = array[j];
            final class_2960 sqId = class_2960.method_60655("customblocks", sq[0] + "_square");
            final ColorSquareItem sqItem = new ColorSquareItem(sq[0], sq[1], new class_1792.class_1793().method_7889(1));
            class_2378.method_10230((class_2378)class_7923.field_41178, sqId, (Object)sqItem);
        }
        // Register triangle items
        final String[][] triangles = new String[][] { { "black", "Black" }, { "yellow", "Yellow" }, { "green", "Green" } };
        for (int t = 0; t < triangles.length; ++t) {
            final String[] tri = triangles[t];
            final class_2960 triId = class_2960.method_60655("customblocks", tri[0] + "_triangle");
            final ColorTriangleItem triItem = new ColorTriangleItem(tri[0], tri[1], new class_1792.class_1793().method_7889(1));
            class_2378.method_10230((class_2378)class_7923.field_41178, triId, (Object)triItem);
        }
        PayloadTypeRegistry.playS2C().register((class_8710.class_9154)FullSyncPayload.ID, (class_9139)FullSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register((class_8710.class_9154)SlotUpdatePayload.ID, (class_9139)SlotUpdatePayload.CODEC);
        class_2378.method_39197(class_7923.field_44687, (class_5321)CustomBlocksMod.CUSTOM_BLOCKS_TAB, (Object)FabricItemGroup.builder().method_47321((class_2561)class_2561.method_43470("Custom Blocks")).method_47320(() -> {
            final SlotManager.SlotData icon = SlotManager.getById("tab_icon");
            if (icon != null) {
                return new class_1799((class_1935)CustomBlocksMod.SLOT_ITEMS[icon.index]);
            }
            else {
                SlotManager.allSlots().iterator();
                final Iterator iterator;
                while (iterator.hasNext()) {
                    final SlotManager.SlotData d2 = iterator.next();
                    if (!d2.customId.equals("tab_icon")) {
                        return new class_1799((class_1935)CustomBlocksMod.SLOT_ITEMS[d2.index]);
                    }
                }
                return new class_1799((class_1935)class_1802.field_8536);
            }
        }).method_47317((ctx, entries) -> {
            for (final SlotManager.SlotData d : SlotManager.allSlots()) {
                if (!d.customId.equals("tab_icon")) {
                    entries.method_45421((class_1935)CustomBlocksMod.SLOT_ITEMS[d.index]);
                }
            }
            final String[] array = { "black_", "yellow_", "green_" };
            for (int length = array.length, i = 0; i < length; ++i) {
                final String col = array[i];
                final class_1792 sq = (class_1792)class_7923.field_41178.method_10223(class_2960.method_60655("customblocks", col + "square"));
                if (sq != null && sq != class_1802.field_8162) {
                    entries.method_45421((class_1935)sq);
                }
            }
            // Add triangles to creative tab
            final String[] triColors = { "black_", "yellow_", "green_" };
            for (int t2 = 0; t2 < triColors.length; ++t2) {
                final String triCol = triColors[t2];
                final class_1792 tri = (class_1792)class_7923.field_41178.method_10223(class_2960.method_60655("customblocks", triCol + "triangle"));
                if (tri != null && tri != class_1802.field_8162) {
                    entries.method_45421((class_1935)tri);
                }
            }
        }).method_47324());
        PlayerBlockBreakEvents.AFTER.register((Object)((world, player, pos, state, be) -> {
            if (!(state.method_26204() instanceof SlotBlock)) {
                return;
            }
            if (player.method_7337()) {
                return;
            }
            world.method_8649((class_1297)new class_1542(world, pos.method_10263() + 0.5, pos.method_10264() + 0.5, pos.method_10260() + 0.5, new class_1799((class_1935)state.method_26204())));
        }));
        ServerPlayConnectionEvents.JOIN.register((Object)((handler, sender, server) -> {
            final List<FullSyncPayload.SlotEntry> meta = new ArrayList<FullSyncPayload.SlotEntry>();
            final Iterator<SlotManager.SlotData> iterator = SlotManager.allSlots().iterator();
            SlotManager.SlotData d = null;
            while (iterator.hasNext()) {
                d = iterator.next();
                meta.add(new FullSyncPayload.SlotEntry(d.index, d.customId, d.displayName, null, d.lightLevel, d.hardness, d.soundType));
            }
            ServerPlayNetworking.send(handler.field_14140, (class_8710)new FullSyncPayload(meta, SlotManager.getTabIconTexture()));
            final ConcurrentLinkedQueue<SlotUpdatePayload> queue = new ConcurrentLinkedQueue<SlotUpdatePayload>();
            SlotManager.allSlots().stream().filter(d -> d.texture != null && d.texture.length > 0).sorted(Comparator.comparingInt(d -> d.index)).forEach(d -> queue.add(new SlotUpdatePayload("add", d.index, d.customId, d.displayName, d.texture, d.lightLevel, d.hardness, d.soundType)));
            final UUID uuid = handler.field_14140.method_5667();
            CustomBlocksMod.PENDING_TEXTURES.put(uuid, queue);
            CustomBlocksMod.SEND_DELAY.put(uuid, 60);
        }));
        ServerPlayConnectionEvents.DISCONNECT.register((Object)((handler, server) -> {
            final UUID uuid = handler.field_14140.method_5667();
            CustomBlocksMod.PENDING_TEXTURES.remove(uuid);
            CustomBlocksMod.SEND_DELAY.remove(uuid);
        }));
        ServerTickEvents.END_SERVER_TICK.register((Object)(server -> {
            for (final class_3222 player : server.method_3760().method_14571()) {
                final UUID uuid = player.method_5667();
                final Integer delay = CustomBlocksMod.SEND_DELAY.get(uuid);
                if (delay == null) {
                    continue;
                }
                if (delay > 0) {
                    CustomBlocksMod.SEND_DELAY.put(uuid, delay - 1);
                }
                else {
                    final ConcurrentLinkedQueue<SlotUpdatePayload> queue = CustomBlocksMod.PENDING_TEXTURES.get(uuid);
                    if (queue == null) {
                        CustomBlocksMod.SEND_DELAY.remove(uuid);
                    }
                    else {
                        int sent = 0;
                        while (sent < 4) {
                            final ConcurrentLinkedQueue<SlotUpdatePayload> current = CustomBlocksMod.PENDING_TEXTURES.get(uuid);
                            if (current == null) {
                                break;
                            }
                            if (current.isEmpty()) {
                                break;
                            }
                            final SlotUpdatePayload pkt = current.poll();
                            if (pkt == null) {
                                continue;
                            }
                            ServerPlayNetworking.send(player, (class_8710)pkt);
                            ++sent;
                        }
                        final ConcurrentLinkedQueue<SlotUpdatePayload> afterQueue = CustomBlocksMod.PENDING_TEXTURES.get(uuid);
                        if (afterQueue != null && !afterQueue.isEmpty()) {
                            continue;
                        }
                        CustomBlocksMod.PENDING_TEXTURES.remove(uuid);
                        CustomBlocksMod.SEND_DELAY.remove(uuid);
                    }
                }
            }
        }));
        CustomBlockCommand.register();
        SlotManager.loadAll();
        CustomBlocksMod.LOGGER.info("[CustomBlocks] Initialized. {} slot(s) loaded.", (Object)SlotManager.usedSlots());
    }
    
    public static void broadcastUpdate(final MinecraftServer server, final SlotUpdatePayload payload) {
        for (final class_3222 player : server.method_3760().method_14571()) {
            final UUID uuid = player.method_5667();
            final ConcurrentLinkedQueue<SlotUpdatePayload> oldQueue = CustomBlocksMod.PENDING_TEXTURES.get(uuid);
            if (oldQueue != null && !oldQueue.isEmpty()) {
                final ConcurrentLinkedQueue<SlotUpdatePayload> newQueue = new ConcurrentLinkedQueue<SlotUpdatePayload>();
                boolean replaced = false;
                for (final SlotUpdatePayload queued : oldQueue) {
                    if (queued.customId() != null && queued.customId().equals(payload.customId())) {
                        newQueue.add(payload);
                        replaced = true;
                    }
                    else {
                        newQueue.add(queued);
                    }
                }
                CustomBlocksMod.PENDING_TEXTURES.put(uuid, newQueue);
                if (replaced) {
                    continue;
                }
                ServerPlayNetworking.send(player, (class_8710)payload);
            }
            else {
                ServerPlayNetworking.send(player, (class_8710)payload);
            }
        }
    }
    
    static {
        LOGGER = LoggerFactory.getLogger("customblocks");
        SLOT_BLOCKS = new SlotBlock[512];
        SLOT_ITEMS = new SlotBlock.SlotItem[512];
        PENDING_TEXTURES = new ConcurrentHashMap<UUID, ConcurrentLinkedQueue<SlotUpdatePayload>>();
        SEND_DELAY = new ConcurrentHashMap<UUID, Integer>();
        CUSTOM_BLOCKS_TAB = class_5321.method_29179(class_7924.field_44688, class_2960.method_60655("customblocks", "blocks"));
    }
}
