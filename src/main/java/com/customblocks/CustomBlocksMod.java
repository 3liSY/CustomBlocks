package com.customblocks;

import com.customblocks.block.SlotBlock;
import com.customblocks.item.ColorSquareItem;
import com.customblocks.command.CustomBlockCommand;
import com.customblocks.network.FullSyncPayload;
import com.customblocks.network.SlotUpdatePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
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
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final SlotBlock[]      SLOT_BLOCKS = new SlotBlock[SlotManager.MAX_SLOTS];
    public static final SlotBlock.SlotItem[] SLOT_ITEMS = new SlotBlock.SlotItem[SlotManager.MAX_SLOTS];

    // Batch texture sending — ConcurrentHashMap so broadcastUpdate is safe from any thread
    private static final Map<UUID, ConcurrentLinkedQueue<SlotUpdatePayload>> PENDING_TEXTURES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, Integer> SEND_DELAY = new java.util.concurrent.ConcurrentHashMap<>();
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
        // 3 special items: right-click a CustomBlock to swap it to the same ID with a new color prefix
        String[][] squares = {{"black_",  "Black"},  {"yellow_", "Yellow"}, {"green_",  "Green"}};
        for (String[] sq : squares) {
            Identifier sqId = Identifier.of(MOD_ID, sq[0] + "square");
            ColorSquareItem sqItem = new ColorSquareItem(sq[0], sq[1],
                    new Item.Settings().maxCount(1));
            Registry.register(Registries.ITEM, sqId, sqItem);
        }

        // Network
        PayloadTypeRegistry.playS2C().register(FullSyncPayload.ID, FullSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SlotUpdatePayload.ID, SlotUpdatePayload.CODEC);

        // Creative tab
        Registry.register(Registries.ITEM_GROUP, CUSTOM_BLOCKS_TAB,
                FabricItemGroup.builder()
                        .displayName(Text.literal("Custom Blocks"))
                        .icon(() -> {
                            SlotManager.SlotData icon = SlotManager.getById("tab_icon");
                            if (icon != null) return new ItemStack(SLOT_ITEMS[icon.index]);
                            for (SlotManager.SlotData d : SlotManager.allSlots())
                                if (!d.customId.equals("tab_icon"))
                                    return new ItemStack(SLOT_ITEMS[d.index]);
                            return new ItemStack(Items.BOOKSHELF);
                        })
                        .entries((ctx, entries) -> {
                            for (SlotManager.SlotData d : SlotManager.allSlots())
                                if (!d.customId.equals("tab_icon"))
                                    entries.add(SLOT_ITEMS[d.index]);
                            // Add the 3 color square items at the end of the tab
                            for (String col : new String[]{"black_", "yellow_", "green_"}) {
                                net.minecraft.item.Item sq = Registries.ITEM.get(Identifier.of(MOD_ID, col + "square"));
                                if (sq != null && sq != net.minecraft.item.Items.AIR)
                                    entries.add(sq);
                            }
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
                    .filter(d -> d.texture != null && d.texture.length > 0)
                    .sorted(Comparator.comparingInt(d -> d.index))
                    .forEach(d -> queue.add(new SlotUpdatePayload("add", d.index, d.customId, d.displayName,
                            d.texture, d.lightLevel, d.hardness, d.soundType)));
            UUID uuid = handler.player.getUuid();
            PENDING_TEXTURES.put(uuid, queue);
            SEND_DELAY.put(uuid, DELAY_TICKS);

        });

        // On disconnect: clean up
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.player.getUuid();
            PENDING_TEXTURES.remove(uuid);
            SEND_DELAY.remove(uuid);
        });

        // Each tick: drip-feed queued textures (4 per tick, after 3s delay)
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                UUID uuid = player.getUuid();
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

        LOGGER.info("[CustomBlocks] Initialized. {} slot(s) loaded.", SlotManager.usedSlots());
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
