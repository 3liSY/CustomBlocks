package com.customblocks.client;



import com.customblocks.client.gui.AnimBlockScreen;

import com.customblocks.network.OpenAnimGuiPayload;

import com.customblocks.CustomBlocksMod;

import com.customblocks.core.SlotData;

import com.customblocks.core.SlotManager;

import com.customblocks.block.SlotBlock;

import com.customblocks.client.texture.TextureCache;

import com.customblocks.network.FullSyncPayload;

import com.customblocks.network.SlotUpdatePayload;

import net.fabricmc.api.ClientModInitializer;

import net.fabricmc.api.EnvType;

import net.fabricmc.api.Environment;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

import net.minecraft.client.MinecraftClient;

import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.util.hit.BlockHitResult;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;


import com.customblocks.network.ChunkedTexturePayload;



import java.io.File;

import java.io.IOException;

import java.nio.charset.StandardCharsets;

import java.nio.file.Files;

import java.nio.file.Path;

import java.security.MessageDigest;

import java.util.HexFormat;

import java.util.concurrent.ConcurrentHashMap;

import java.util.concurrent.atomic.AtomicBoolean;

import java.util.concurrent.atomic.AtomicLong;



@Environment(EnvType.CLIENT)

public class CustomBlocksClient implements ClientModInitializer {



    private static final String PACK_ENTRY = "file/CustomBlocks";

    private static final AtomicBoolean reloadInFlight  = new AtomicBoolean(false);

    private static final AtomicBoolean generateRunning   = new AtomicBoolean(false);

    private static final AtomicBoolean pendingFullReload = new AtomicBoolean(false);

    private static final AtomicLong    lastPacketTime    = new AtomicLong(0);



    // True while processing the initial join burst — suppresses individual packet reloads.

    private static volatile boolean joinBurst        = false;



    // Set to true when the server's "sync_done" sentinel arrives, signalling that all

    // join textures have been enqueued. The waiting debounce thread breaks immediately

    // on this flag rather than waiting out a fixed timer that can fire mid-burst on

    // slow internet connections and cause cascading resource-pack reloads / disconnect.

    private static volatile boolean syncDoneReceived = false;



    public static volatile boolean pendingCreativeRefresh = false;

    /** When true, texture writes happen but visible reload is deferred until resume. */
    private static volatile boolean rpPaused = false;
    /** Set when a reload was suppressed during pause — triggers reload on resume. */
    private static volatile boolean rpDirtyWhilePaused = false;



    /** Server's maxSlots, synced via FullSyncPayload. 0 = not yet received. */

    private static volatile int serverMaxSlots = 0;

    // Phase D4: configurable instant-click aggressiveness (0..10000 ms).
    private static long instantAggressivenessMs() {
        int raw = com.customblocks.CustomBlocksConfig.instantClickAggressivenessMs;
        return Math.max(0L, Math.min(10_000L, raw));
    }

    private static long fastReloadDebounceMs() {
        return instantAggressivenessMs();
    }

    private static long fullReloadDebounceMs() {
        // Keep full-reload debounce above quick actions to avoid thrash.
        return Math.max(800L, instantAggressivenessMs() * 4L);
    }



    /** Returns the effective maxSlots for resource pack generation. */

    public static int effectiveMaxSlots() {

        return serverMaxSlots > 0 ? serverMaxSlots : com.customblocks.CustomBlocksConfig.maxSlots;

    }



    // ── Chunk reassembly state ───────────────────────────────────────────────

    /** Max concurrent chunk transfers (safety cap). */

    private static final int MAX_CONCURRENT_TRANSFERS = 5;

    /** Timeout for incomplete chunk transfers (ms). */

    private static final long CHUNK_TIMEOUT_MS = 30_000;

    /** Max reassembled texture size (bytes). */

    private static final int MAX_REASSEMBLY_SIZE = 10 * 1024 * 1024; // 10MB



    /** Buffers in-flight chunk transfers, keyed by transferId. */

    private static final ConcurrentHashMap<String, ChunkBuffer> CHUNK_BUFFERS = new ConcurrentHashMap<>();



    /** Holds the state for a single chunked transfer. */

    private static final class ChunkBuffer {

        final int totalChunks;

        final byte[][] chunks;

        final long startTime;

        // Metadata from chunk 0

        String action;

        int slotIndex;

        String customId;

        String displayName;

        int lightLevel;

        float hardness;

        String soundType;

        String face;

        String animMeta;

        int receivedCount;



        ChunkBuffer(int totalChunks) {

            this.totalChunks = totalChunks;

            this.chunks = new byte[totalChunks][];

            this.startTime = System.currentTimeMillis();

        }



        boolean isComplete() { return receivedCount >= totalChunks; }



        boolean isTimedOut() { return System.currentTimeMillis() - startTime > CHUNK_TIMEOUT_MS; }



        byte[] reassemble() {

            int total = 0;

            for (byte[] c : chunks) {

                if (c == null) return null;

                total += c.length;

            }

            if (total > MAX_REASSEMBLY_SIZE) return null;

            byte[] result = new byte[total];

            int offset = 0;

            for (byte[] c : chunks) {

                System.arraycopy(c, 0, result, offset, c.length);

                offset += c.length;

            }

            return result;

        }

    }



    private static KeyBinding devConsoleKey;

    @Override

    public void onInitializeClient() {

        devConsoleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.customblocks.devconsole",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_D,
            "category.customblocks"
        ));

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {

            SlotManager.loadFromClientDir(client.runDirectory);

            ResourcePackGenerator.generate(client);

            injectPackIfNeeded(client);

        });



        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            while (devConsoleKey.wasPressed()) {
                if (net.minecraft.client.gui.screen.Screen.hasControlDown() && net.minecraft.client.gui.screen.Screen.hasShiftDown()) {
                    if (client.player != null && client.player.hasPermissionLevel(2)) { // Only if they have somewhat OP or we check server-side if it opens
                        client.setScreen(new com.customblocks.client.gui.DevConsoleScreen());
                    } else if (client.player != null) {
                        client.setScreen(new com.customblocks.client.gui.DevConsoleScreen()); // The plan requires permission, but since we can't cleanly check luckperms client-side without a payload, we open it and restrict actions server-side.
                    }
                }
            }

            if (pendingCreativeRefresh && client.player != null) {

                pendingCreativeRefresh = false;

                bustItemGroupIconCache();

                if (client.currentScreen instanceof CreativeInventoryScreen) {

                    client.setScreen(new CreativeInventoryScreen(

                            client.player,

                            client.player.networkHandler.getEnabledFeatures(),

                            false

                    ));

                }

            }

        });



        // ── Fix 7: Client-initiated sync — send "I'm ready" when connection is live

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {

            // Phase 3: send cached texture hash so server can skip drip-feed if unchanged.

            // Priority: server-provided hash (guaranteed match) > client cache > dynamic.

            // SAFETY: If the resource pack folder is missing, force a full drip-feed by

            // sending an empty hash. This prevents hash-match + deleted-pack = broken textures.

            boolean packExists = new java.io.File(client.runDirectory,

                    "resourcepacks/CustomBlocks/assets").isDirectory();

            String hashToSend;

            if (!packExists) {

                hashToSend = "";

                CustomBlocksMod.LOGGER.warn(

                        "[CustomBlocks] Resource pack missing on disk — sending empty hash to force full sync.");

            } else {

                hashToSend = loadServerHash(client.runDirectory);

                if (hashToSend == null || hashToSend.isEmpty()) {

                    hashToSend = loadCachedHash(client.runDirectory);

                }

                if (hashToSend == null || hashToSend.isEmpty()) {

                    hashToSend = computeTextureHash();

                    if (hashToSend != null && !hashToSend.isEmpty() && !SlotManager.allSlots().isEmpty()) {

                        saveCachedHash(client.runDirectory, hashToSend);

                        CustomBlocksMod.LOGGER.info("[CustomBlocks] Reconstructed missing cache hash dynamically.");

                    }

                }

            }

            CustomBlocksMod.LOGGER.info("[CustomBlocks] Sending hash to server: {}",

                    hashToSend != null && hashToSend.length() >= 12 ? hashToSend.substring(0, 12) : hashToSend);

            // N3 — compute per-slot CRC32 hashes for delta sync
            String slotHashesJson = "";
            if (packExists && !SlotManager.allSlots().isEmpty()) {
                try {
                    com.google.gson.JsonObject slotHashes = new com.google.gson.JsonObject();
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    for (com.customblocks.core.SlotData d : SlotManager.allSlots()) {
                        if (d.texture != null && d.texture.length > 0) {
                            crc.reset();
                            crc.update(d.texture);
                            slotHashes.addProperty(String.valueOf(d.index), Long.toHexString(crc.getValue()));
                        }
                    }
                    slotHashesJson = slotHashes.toString();
                } catch (Exception ex) {
                    CustomBlocksMod.LOGGER.debug("[CustomBlocks] Could not compute slot hashes: {}", ex.getMessage());
                }
            }

            ClientPlayNetworking.send(new com.customblocks.network.SyncRequestPayload(

                    hashToSend != null ? hashToSend : "", slotHashesJson));

        });



        // ── Disconnect → reset all join-burst state ────────────────────────────

        //

        // This is critical for players who experience a failed join and immediately

        // retry. Without resetting here, stale flags from the previous connection

        // attempt (joinBurst=true, reloadInFlight=true, etc.) bleed into the next

        // connection, causing the new FullSyncPayload's reload to be skipped entirely.

        //

        // lastPacketTime is set to Long.MAX_VALUE/2 rather than 0: this makes the

        // debounce thread's "remaining" calculation produce a huge positive value,

        // effectively pausing it indefinitely. The next FullSyncPayload handler will

        // reset lastPacketTime to System.currentTimeMillis(), resuming the thread

        // correctly for the new connection.

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {

            joinBurst        = false;

            syncDoneReceived = false;

            reloadInFlight.set(false);

            lastPacketTime.set(Long.MAX_VALUE / 2);

            // NOTE: generateRunning is intentionally NOT reset here.

            // Any running generate thread will see the paused lastPacketTime and

            // will not fire until the next FullSyncPayload arrives and resets it.

        });



        // ── FullSyncPayload — initial join ────────────────────────────────────

        ClientPlayNetworking.registerGlobalReceiver(FullSyncPayload.ID, (payload, context) -> {

            MinecraftClient client = context.client();

            client.execute(() -> {

                // Reset burst flags from any previous (failed) join attempt.

                syncDoneReceived = false;

                joinBurst        = true;



                // ── Fix 9: Smart merge instead of clearAll() ────────────────

                // Step 1: Build set of server-side IDs

                java.util.Set<String> serverIds = new java.util.HashSet<>();

                for (FullSyncPayload.SlotEntry e : payload.entries()) {

                    serverIds.add(e.customId());

                }



                // Step 2: Remove local blocks the server no longer has

                java.util.List<String> toRemove = new java.util.ArrayList<>();

                for (SlotData local : SlotManager.allSlots()) {

                    if (!serverIds.contains(local.customId)) {

                        toRemove.add(local.customId);

                    }

                }

                for (String id : toRemove) {

                    SlotManager.remove(id);

                }

                TextureCache.invalidateAll();  // render cache still needs refresh



                // Step 3: Merge — update metadata, KEEP existing textures

                for (FullSyncPayload.SlotEntry e : payload.entries()) {

                    SlotData existing = SlotManager.getById(e.customId());

                    if (existing != null) {

                        if (existing.index != e.index()) {

                            SlotManager.remove(e.customId());

                            SlotManager.assignAtIndex(e.index(), e.customId(), e.displayName(), existing.texture);

                        }

                        SlotManager.setProperties(e.customId(), e.lightLevel(), e.hardness(), e.soundType());

                        if (e.animMeta() != null && !e.animMeta().isEmpty())

                            SlotManager.setAnimMeta(e.customId(), e.animMeta());

                    } else {

                        SlotManager.assignAtIndex(e.index(), e.customId(), e.displayName(), null);

                        SlotManager.setProperties(e.customId(), e.lightLevel(), e.hardness(), e.soundType());

                        if (e.animMeta() != null && !e.animMeta().isEmpty())

                            SlotManager.setAnimMeta(e.customId(), e.animMeta());

                    }

                }

                if (payload.tabIconTexture() != null)

                    SlotManager.setTabIconTexture(payload.tabIconTexture());

                if (payload.maxSlots() > 0)

                    serverMaxSlots = payload.maxSlots();



                // Start the debounce thread. The sync_done sentinel (sent by the server

                // after all textures are enqueued) will wake this thread early via the

                // syncDoneReceived flag. The joinDebounceMs window acts as a fallback in

                // case the client is connected to an older server build that does not send

                // sync_done — so the join reload still fires eventually without getting stuck.

                long fallbackDebounce = com.customblocks.CustomBlocksConfig.joinDebounceMs > 0

                        ? com.customblocks.CustomBlocksConfig.joinDebounceMs : 4000L;

                scheduleGenerateAndReload(client, fallbackDebounce);

            });

        });



        // ── SlotUpdatePayload ─────────────────────────────────────────────────

        ClientPlayNetworking.registerGlobalReceiver(SlotUpdatePayload.ID, (payload, context) -> {

            MinecraftClient client = context.client();

            client.execute(() -> processSlotUpdatePayload(client, payload));

        });



        // ── OpenAnimGuiPayload — server tells client to open animation settings ──

        ClientPlayNetworking.registerGlobalReceiver(OpenAnimGuiPayload.ID, (payload, context) -> {

            MinecraftClient client = context.client();

            client.execute(() -> {

                if (client.currentScreen == null) {

                    client.setScreen(new AnimBlockScreen(

                            payload.customId(),

                            payload.displayName(),

                            payload.animMeta(),

                            payload.frameCount()

                    ));

                }

            });

        });



        // ── ChunkedTexturePayload — reassemble chunked textures ─────────────

        ClientPlayNetworking.registerGlobalReceiver(ChunkedTexturePayload.ID, (payload, context) -> {

            MinecraftClient client = context.client();

            client.execute(() -> {

                String tid = payload.transferId();



                // Keep debounce alive while chunks are arriving — prevents premature resource pack gen

                lastPacketTime.set(System.currentTimeMillis());



                // Clean up timed-out buffers

                CHUNK_BUFFERS.entrySet().removeIf(e -> e.getValue().isTimedOut());



                // Reject if too many concurrent transfers

                if (!CHUNK_BUFFERS.containsKey(tid) && CHUNK_BUFFERS.size() >= MAX_CONCURRENT_TRANSFERS) {

                    CustomBlocksMod.LOGGER.warn("[CustomBlocks] Chunk transfer rejected — too many concurrent transfers (max {})", MAX_CONCURRENT_TRANSFERS);

                    return;

                }



                // Validate chunk index

                if (payload.chunkIndex() < 0 || payload.chunkIndex() >= payload.totalChunks()) {

                    CustomBlocksMod.LOGGER.warn("[CustomBlocks] Invalid chunk index {} / {} for transfer {}",

                            payload.chunkIndex(), payload.totalChunks(), tid.substring(0, 8));

                    return;

                }



                ChunkBuffer buf = CHUNK_BUFFERS.computeIfAbsent(tid, k -> new ChunkBuffer(payload.totalChunks()));



                // Store metadata from chunk 0

                if (payload.chunkIndex() == 0) {

                    buf.action      = payload.action();

                    buf.slotIndex   = payload.slotIndex();

                    buf.customId    = payload.customId();

                    buf.displayName = payload.displayName();

                    buf.lightLevel  = payload.lightLevel();

                    buf.hardness    = payload.hardness();

                    buf.soundType   = payload.soundType();

                    buf.face        = payload.face();

                    buf.animMeta    = payload.animMeta();

                }



                // Store chunk data (skip if already received — idempotent)

                if (buf.chunks[payload.chunkIndex()] == null) {

                    buf.chunks[payload.chunkIndex()] = payload.chunkData();

                    buf.receivedCount++;

                }



                // Check if all chunks have arrived

                if (buf.isComplete()) {

                    CHUNK_BUFFERS.remove(tid);

                    byte[] fullTexture = buf.reassemble();

                    if (fullTexture == null) {

                        CustomBlocksMod.LOGGER.error("[CustomBlocks] Chunk reassembly failed for transfer {} (exceeded max size or missing chunks)", tid.substring(0, 8));

                        return;

                    }



                    CustomBlocksMod.LOGGER.info("[CustomBlocks] Chunk reassembly complete: transfer={}, {} bytes, action={}, slot={}, face={}",

                            tid.substring(0, 8), fullTexture.length, buf.action, buf.slotIndex, buf.face);



                    // Create a normal SlotUpdatePayload from the reassembled data

                    // and process through the EXISTING handler logic

                    SlotUpdatePayload reassembled = new SlotUpdatePayload(

                            buf.action, buf.slotIndex, buf.customId, buf.displayName,

                            fullTexture, buf.lightLevel, buf.hardness, buf.soundType,

                            buf.face, null, buf.animMeta);

                    processSlotUpdatePayload(client, reassembled);

                }

            });

        });



        // ── RpPausePayload — server says pause/resume reloads ────────────────

        ClientPlayNetworking.registerGlobalReceiver(com.customblocks.network.RpPausePayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                if (payload.paused()) {
                    rpPaused = true;
                    rpDirtyWhilePaused = false;
                    CustomBlocksMod.LOGGER.info("[CustomBlocks] RP reloads PAUSED by server.");
                } else {
                    rpPaused = false;
                    CustomBlocksMod.LOGGER.info("[CustomBlocks] RP reloads RESUMED by server.");
                    if (rpDirtyWhilePaused) {
                        rpDirtyWhilePaused = false;
                        CustomBlocksMod.LOGGER.info("[CustomBlocks] Deferred reload — triggering now.");
                        clearCachedHash(context.client().runDirectory);
                        scheduleGenerateAndReload(context.client(), fastReloadDebounceMs());
                    }
                }
            });
        });

        // ── Item Lore Badge Injection ──────────────────────────────────────────
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            if (stack.getItem() instanceof SlotBlock.SlotItem si) {
                if (com.customblocks.CustomBlocksConfig.hideCustomBlockText) {
                    lines.removeIf(text -> {
                        String str = text.getString();
                        return str.contains("Custom Blocks") && !str.equals(stack.getName().getString());
                    });
                }
                
                if (com.customblocks.CustomBlocksConfig.hideCategoryBadge) return;
                
                SlotData d = SlotManager.getBySlot(((SlotBlock) si.getBlock()).getSlotKey());
                if (d != null && d.customId != null) {
                    java.util.Set<String> catKeys = com.customblocks.core.CategoryManager.getCategoriesForBlock(d.customId);
                    
                    java.util.List<net.minecraft.text.Text> loreAbove = new java.util.ArrayList<>();
                    java.util.List<net.minecraft.text.Text> loreBelow = new java.util.ArrayList<>();
                    java.util.List<net.minecraft.text.MutableText> badges = new java.util.ArrayList<>();
                    
                    String globalOverflowMode = "cap_3_more";
                    
                    for (String key : catKeys) {
                        com.customblocks.core.Category category = com.customblocks.core.CategoryManager.getCategory(key);
                        if (category != null) {
                            if (category.badgeOverflowMode() != null) globalOverflowMode = category.badgeOverflowMode();
                            
                            boolean replaceBadge = false;
                            if (category.lorePrefix() != null && !category.lorePrefix().isEmpty()) {
                                net.minecraft.text.Text prefixText = net.minecraft.text.Text.literal(category.lorePrefix());
                                String pos = category.lorePrefixPosition() != null ? category.lorePrefixPosition() : "above_badge";
                                if ("above_badge".equals(pos)) loreAbove.add(prefixText);
                                else if ("below_badge".equals(pos)) loreBelow.add(prefixText);
                                else if ("replace_badge".equals(pos)) replaceBadge = true;
                            }
                            
                            if (!replaceBadge && category.badge() != null && !category.badge().isEmpty()) {
                                String colorHex = category.badgeColor() != null ? category.badgeColor() : category.color();
                                if (colorHex == null || !colorHex.startsWith("#")) colorHex = "#FFFFFF";
                                int colorIntVal = 0xFFFFFF;
                                try { colorIntVal = (int)(Long.parseLong(colorHex.replace("#", ""), 16) | 0xFF000000); } catch (Exception ignored) {}
                                final int colorInt = colorIntVal;
                                
                                String ind = category.parentKey() != null && category.subcategoryIndicator() != null ? category.subcategoryIndicator() : "";
                                
                                net.minecraft.text.MutableText badgeText = net.minecraft.text.Text.literal("§8[§r" + ind)
                                    .append(net.minecraft.text.Text.literal(category.badge()).styled(s -> s.withColor(colorInt)))
                                    .append(net.minecraft.text.Text.literal("§8]"));
                                badges.add(badgeText);
                            }
                        }
                    }
                    
                    int insertIndex = 1;
                    if (lines.size() <= 1) insertIndex = lines.size();
                    
                    for (net.minecraft.text.Text t : loreAbove) {
                        lines.add(insertIndex++, t);
                    }
                    
                    if (!badges.isEmpty()) {
                        if ("one_line".equals(globalOverflowMode)) {
                            net.minecraft.text.MutableText combined = net.minecraft.text.Text.literal("");
                            for (int i = 0; i < badges.size(); i++) {
                                combined.append(badges.get(i));
                                if (i < badges.size() - 1) combined.append(net.minecraft.text.Text.literal(" "));
                            }
                            lines.add(insertIndex++, combined);
                        } else if ("show_all".equals(globalOverflowMode)) {
                            net.minecraft.text.MutableText currentLine = net.minecraft.text.Text.literal("");
                            for (int i = 0; i < badges.size(); i++) {
                                currentLine.append(badges.get(i)).append(net.minecraft.text.Text.literal(" "));
                                if ((i + 1) % 3 == 0 || i == badges.size() - 1) {
                                    lines.add(insertIndex++, currentLine);
                                    currentLine = net.minecraft.text.Text.literal("");
                                }
                            }
                        } else if ("cap_5".equals(globalOverflowMode)) {
                            net.minecraft.text.MutableText currentLine = net.minecraft.text.Text.literal("");
                            for (int i = 0; i < Math.min(5, badges.size()); i++) {
                                currentLine.append(badges.get(i)).append(net.minecraft.text.Text.literal(" "));
                            }
                            if (badges.size() > 5) currentLine.append(net.minecraft.text.Text.literal("§8[+" + (badges.size() - 5) + "]"));
                            lines.add(insertIndex++, currentLine);
                        } else {
                            net.minecraft.text.MutableText currentLine = net.minecraft.text.Text.literal("");
                            for (int i = 0; i < Math.min(3, badges.size()); i++) {
                                currentLine.append(badges.get(i)).append(net.minecraft.text.Text.literal(" "));
                            }
                            if (badges.size() > 3) currentLine.append(net.minecraft.text.Text.literal("§8[+" + (badges.size() - 3) + "]"));
                            lines.add(insertIndex++, currentLine);
                        }
                    }
                    
                    for (net.minecraft.text.Text t : loreBelow) {
                        lines.add(insertIndex++, t);
                    }
                }
            }
        });

        // ── HUD overlay ───────────────────────────────────────────────────────

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {

            MinecraftClient client = MinecraftClient.getInstance();

            if (client.world == null || client.player == null) return;

            if (!(client.crosshairTarget instanceof BlockHitResult bhr)) return;

            var state = client.world.getBlockState(bhr.getBlockPos());

            if (!(state.getBlock() instanceof SlotBlock sb)) return;

            SlotData data = SlotManager.getBySlot(sb.getSlotKey());

            if (data == null) return;

            String name = data.displayName;

            int cx = ctx.getScaledWindowWidth() / 2;

            int w  = client.textRenderer.getWidth(name);

            ctx.fill(cx - w / 2 - 5, 38, cx + w / 2 + 5, 52, 0x88000000);

            ctx.drawCenteredTextWithShadow(client.textRenderer, name, cx, 42, 0xFFFFFFFF);

        });

    }



    private static void bustItemGroupIconCache() {

        try {

            net.minecraft.item.ItemGroup group =

                net.minecraft.registry.Registries.ITEM_GROUP.get(CustomBlocksMod.CUSTOM_BLOCKS_TAB);

            if (group == null) return;

            String[] candidates = {"icon", "field_24603", "iconStack"};

            for (String name : candidates) {

                try {

                    java.lang.reflect.Field f = net.minecraft.item.ItemGroup.class.getDeclaredField(name);

                    f.setAccessible(true);

                    if (f.get(group) instanceof net.minecraft.item.ItemStack) {

                        f.set(group, net.minecraft.item.ItemStack.EMPTY);

                        return;

                    }

                } catch (NoSuchFieldException ignored) {}

            }

            for (java.lang.reflect.Field f : net.minecraft.item.ItemGroup.class.getDeclaredFields()) {

                if (f.getType() == net.minecraft.item.ItemStack.class) {

                    f.setAccessible(true);

                    f.set(group, net.minecraft.item.ItemStack.EMPTY);

                    return;

                }

            }

        } catch (Exception e) {

            CustomBlocksMod.LOGGER.error("[CustomBlocks] bustItemGroupIconCache failed: {}", e.getMessage());

        }

    }



    /**

     * Processes a single {@link SlotUpdatePayload}. Extracted so both the direct

     * SlotUpdatePayload handler and the chunk-reassembly handler can share the

     * same logic without duplication.

     */

    private static void processSlotUpdatePayload(MinecraftClient client, SlotUpdatePayload payload) {

        switch (payload.action()) {



            // ── sync_done sentinel ────────────────────────────────────

            case "sync_done" -> {

                syncDoneReceived = true;

                // Server embeds its hash in customId — save so we echo it on next join

                String sHash = payload.customId();

                if (sHash != null && !sHash.isEmpty()) {

                    saveServerHash(client.runDirectory, sHash);

                    CustomBlocksMod.LOGGER.info("[CustomBlocks] Saved server hash: {}",

                            sHash.substring(0, Math.min(12, sHash.length())));

                }

                return;

            }



            case "add" -> {

                if (SlotManager.getById(payload.customId()) != null)

                    SlotManager.updateTexture(payload.customId(), payload.texture());

                else

                    SlotManager.assignAtIndex(payload.slotIndex(), payload.customId(),

                            payload.displayName(), payload.texture());

                SlotManager.setProperties(payload.customId(),

                        payload.lightLevel(), payload.hardness(), payload.soundType());

                if (payload.animMeta() != null && !payload.animMeta().isEmpty())

                    SlotManager.setAnimMeta(payload.customId(), payload.animMeta());

                if (TextureCache.invalidateIfChanged(payload.customId(), payload.texture()))
                    TextureCache.schedulePreload(payload.customId(), payload.texture());

                // N2: decode bundled per-face textures sent inline with the "add" payload.
                if (payload.facesJson() != null && !payload.facesJson().isEmpty()) {
                    try {
                        com.google.gson.JsonObject jo =
                                com.google.gson.JsonParser.parseString(payload.facesJson()).getAsJsonObject();
                        for (java.util.Map.Entry<String, com.google.gson.JsonElement> fe : jo.entrySet()) {
                            byte[] faceBytes = java.util.Base64.getDecoder()
                                    .decode(fe.getValue().getAsString());
                            SlotManager.setFaceTexture(payload.customId(), fe.getKey(), faceBytes);
                        }
                        TextureCache.invalidate(payload.customId());
                    } catch (Exception ignored) {}
                }

                // H4: decode bundled variant textures sent inline with the "add" payload.
                if (payload.variantsJson() != null && !payload.variantsJson().isEmpty()) {
                    try {
                        com.google.gson.JsonArray ja =
                                com.google.gson.JsonParser.parseString(payload.variantsJson()).getAsJsonArray();
                        java.util.List<byte[]> variants = new java.util.ArrayList<>(ja.size());
                        for (com.google.gson.JsonElement ve : ja) {
                            variants.add(java.util.Base64.getDecoder().decode(ve.getAsString()));
                        }
                        SlotManager.setVariantTextures(payload.customId(), variants);
                    } catch (Exception ignored) {}
                }

            }

            case "retexture" -> {

                SlotManager.updateTexture(payload.customId(), payload.texture());

                if (payload.animMeta() != null && !payload.animMeta().isEmpty())

                    SlotManager.setAnimMeta(payload.customId(), payload.animMeta());

                if (TextureCache.invalidateIfChanged(payload.customId(), payload.texture()))
                    TextureCache.schedulePreload(payload.customId(), payload.texture());

            }

            case "animsettings" -> {

                if (payload.animMeta() != null && !payload.animMeta().isEmpty())

                    SlotManager.setAnimMeta(payload.customId(), payload.animMeta());

                TextureCache.invalidate(payload.customId());

                if (!joinBurst) {

                    scheduleAnimMetaReload(client, payload.slotIndex(), payload.animMeta());

                    return;

                }

            }

            case "remove" -> {

                TextureCache.invalidate(payload.customId());

                SlotManager.remove(payload.customId());

            }

            case "rename"  -> SlotManager.rename(payload.customId(), payload.displayName());

            case "setprop" -> SlotManager.setProperties(payload.customId(),

                    payload.lightLevel(), payload.hardness(), payload.soundType());

            case "setface" -> {

                if (payload.face() != null && payload.texture() != null) {

                    SlotManager.setFaceTexture(payload.customId(), payload.face(), payload.texture());

                    TextureCache.invalidate(payload.customId());

                }

            }

            case "clearface" -> {

                if (payload.face() != null)

                    SlotManager.clearFaceTexture(payload.customId(), payload.face());

                TextureCache.invalidate(payload.customId());

            }

            case "clearfaces" -> {

                SlotManager.clearAllFaces(payload.customId());

                TextureCache.invalidate(payload.customId());

            }

            case "setshape" -> {

                if (payload.shapeData() != null) {

                    java.util.List<SlotData.ShapeBox> boxes = new java.util.ArrayList<>();

                    if (!payload.shapeData().equals("full")) {

                        for (String part : payload.shapeData().split(";")) {

                            try { boxes.add(SlotData.ShapeBox.parse(part)); } catch (Exception ignored) {}

                        }

                    }

                    SlotManager.setShape(payload.customId(), boxes.isEmpty() ? null : boxes);

                }

            }

            case "setcollision" -> {

                boolean hasCollision = !"false".equals(payload.shapeData());

                SlotManager.setCollision(payload.customId(), hasCollision);

            }

            case "tabicon" -> {

                SlotManager.setTabIconTexture(payload.texture());

                if (!joinBurst) scheduleGenerateAndReload(client, fullReloadDebounceMs());

                return;

            }

        }



        // Only trigger reload for actions that actually change rendered appearance,

        // and only when NOT in the initial join burst.

        if (!joinBurst) {

            String action = payload.action();

            // Phase 2: fast path for add/retexture — single-slot generate instead of full rebuild

            if (action.equals("add") || action.equals("retexture")) {

                scheduleSingleSlotReload(client, payload.slotIndex(), fastReloadDebounceMs());

            } else {

                boolean needsReload = action.equals("remove") || action.equals("setface")

                        || action.equals("clearface") || action.equals("clearfaces");

                if (needsReload) scheduleGenerateAndReload(client, fullReloadDebounceMs());

            }

        } else {

            // Still in join burst — refresh the fallback debounce timer

            lastPacketTime.set(System.currentTimeMillis());

        }

    }



    /**

     * Fast path: write ONLY the changed slot's texture to the pack directory,

     * skip saveToClientDir (heavy), skip full generate (heavy), then reload.

     */

    private static void scheduleSingleSlotReload(MinecraftClient client, int slotIndex, long debounceMs) {

        lastPacketTime.set(System.currentTimeMillis());

        if (generateRunning.compareAndSet(false, true)) {

            Thread t = new Thread(() -> {

                // Short debounce — wait for any rapid follow-up packets

                try { Thread.sleep(debounceMs); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    generateRunning.set(false);
                    return;
                }



                // Write ONLY this slot's texture to the pack

                ResourcePackGenerator.generateSingleSlot(client, slotIndex);



                // Save client-side data + update the texture hash

                SlotManager.saveToClientDir(client.runDirectory);

                String currentHash = computeTextureHash();

                saveCachedHash(client.runDirectory, currentHash);

                clearServerHash(client.runDirectory); // stale after local edit



                // Trigger reload

                client.execute(() -> {

                    generateRunning.set(false);

                    if (pendingFullReload.compareAndSet(true, false)) {

                        scheduleGenerateAndReload(client, fastReloadDebounceMs());

                        return;

                    }

                    if (rpPaused) {

                        rpDirtyWhilePaused = true;

                        CustomBlocksMod.LOGGER.info("[CustomBlocks] Single-slot reload deferred (RP paused).");

                    } else if (reloadInFlight.compareAndSet(false, true)) {

                        client.reloadResources().thenRun(() ->

                            client.execute(() -> {

                                reloadInFlight.set(false);

                                CustomBlocksMod.LOGGER.info("[CustomBlocks] Single-slot reload complete for slot_{}.", slotIndex);

                                pendingCreativeRefresh = true;

                            })

                        ).exceptionally(ex -> {

                            client.execute(() -> {

                                reloadInFlight.set(false);

                                CustomBlocksMod.LOGGER.error("[CustomBlocks] Single-slot reload failed.", ex);

                            });

                            return null;

                        });

                    }

                });

            }, "CustomBlocks-SingleSlotReload");

            t.setDaemon(true);

            t.start();
        } else {
            // If already generating, a single-slot overwrite might be missed,
            // so we flag a full reload to happen after the current cycle.
            pendingFullReload.set(true);
        }
    }



    /**

     * Debounced generate+reload.

     * <p>

     * The background thread polls every 200 ms for one of two exit conditions:

     * <ol>

     *   <li>{@link #syncDoneReceived} == true — server confirmed all join textures

     *       have been enqueued; fire the reload immediately (normal join path).</li>

     *   <li>Silence longer than {@code debounceMs} since the last {@link #lastPacketTime}

     *       update — fallback for live edits and for connecting to older server builds

     *       that do not send the sync_done sentinel.</li>

     * </ol>

     * <p>

     * Only one thread runs at a time; if {@link #generateRunning} is already true,

     * the running thread will see the updated {@link #lastPacketTime} (or the

     * {@link #syncDoneReceived} flag) on its next 200 ms poll and act accordingly.

     * No second thread is spawned.

     */

    private static void scheduleGenerateAndReload(MinecraftClient client, long debounceMs) {

        lastPacketTime.set(System.currentTimeMillis());

        if (generateRunning.compareAndSet(false, true)) {

            Thread t = new Thread(() -> {

                // Wait for sync_done signal OR debounce silence (capped poll at 200ms

                // so we check syncDoneReceived frequently even with a long debounceMs).

                while (true) {

                    if (syncDoneReceived) break;

                    long remaining = debounceMs - (System.currentTimeMillis() - lastPacketTime.get());

                    if (remaining <= 0) break;

                    try { Thread.sleep(Math.max(50, Math.min(remaining, 200))); }

                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                }

                // ── Texture cache check ──────────────────────────────────

                // Compute a hash of ALL received texture data. If it matches

                // the hash from the last successful generation, skip the

                // expensive generate + reloadResources() cycle entirely.

                // This makes reconnects instant (no 2-min freeze).

                String currentHash = computeTextureHash();

                String cachedHash  = loadCachedHash(client.runDirectory);

                boolean packExists = new File(client.runDirectory,

                        "resourcepacks/CustomBlocks/assets").isDirectory();



                if (currentHash.equals(cachedHash) && packExists) {

                    // CACHE HIT — textures unchanged, pack on disk is valid

                    CustomBlocksMod.LOGGER.info(

                            "[CustomBlocks] Texture cache HIT (hash={}). Skipping generation + reload.",

                            currentHash.substring(0, 12));

                    SlotManager.saveToClientDir(client.runDirectory);

                    client.execute(() -> {

                        injectPackIfNeeded(client);

                        joinBurst        = false;

                        syncDoneReceived = false;

                        generateRunning.set(false);

                        if (pendingFullReload.compareAndSet(true, false)) {

                            scheduleGenerateAndReload(client, fastReloadDebounceMs());

                            return;

                        }

                        pendingCreativeRefresh = true;

                    });

                } else {

                    // CACHE MISS — regenerate pack and reload resources

                    CustomBlocksMod.LOGGER.info(

                            "[CustomBlocks] Texture cache MISS (cur={}, cached={}, packExists={}). Regenerating.",

                            currentHash.substring(0, Math.min(12, currentHash.length())),

                            cachedHash  != null ? cachedHash.substring(0, Math.min(12, cachedHash.length())) : "null",

                            packExists);

                    saveCachedHash(client.runDirectory, currentHash);

                    SlotManager.saveToClientDir(client.runDirectory);

                    ResourcePackGenerator.generate(client);

                    client.execute(() -> {

                        injectPackIfNeeded(client);

                        joinBurst        = false;

                        syncDoneReceived = false;

                        generateRunning.set(false);

                        if (pendingFullReload.compareAndSet(true, false)) {

                            scheduleGenerateAndReload(client, fastReloadDebounceMs());

                            return;

                        }



                        if (rpPaused) {

                            rpDirtyWhilePaused = true;

                            CustomBlocksMod.LOGGER.info("[CustomBlocks] Full reload deferred (RP paused).");

                        } else if (reloadInFlight.compareAndSet(false, true)) {

                            client.reloadResources().thenRun(() ->

                                client.execute(() -> {

                                    reloadInFlight.set(false);

                                    CustomBlocksMod.LOGGER.info("[CustomBlocks] Resources reloaded.");

                                    pendingCreativeRefresh = true;

                                })

                            ).exceptionally(ex -> {

                                client.execute(() -> {

                                    reloadInFlight.set(false);

                                    CustomBlocksMod.LOGGER.error("[CustomBlocks] Resource reload failed, unlocking flag.", ex);

                                });

                                return null;

                            });

                        } else {

                            pendingCreativeRefresh = true;

                        }

                    });

                }

            }, "CustomBlocks-GenerateReload");

            t.setDaemon(true);

            t.start();

        } else {

            // generateRunning is already true — mark a pending full reload so it retries

            // after the current generate/reload cycle completes.

            pendingFullReload.set(true);

        }

    }



    // ── Lightweight anim-only reload ─────────────────────────────────────────



    /**

     * Fast path for animation setting changes: writes ONLY the .mcmeta file for

     * the affected slot to the existing pack directory, then triggers a resource

     * reload. Skips the expensive full ResourcePackGenerator.generate() that

     * rewrites all 410+ texture PNGs to disk.

     */

    private static void scheduleAnimMetaReload(MinecraftClient client, int slotIndex, String animMeta) {

        File packRoot = new File(client.runDirectory, "resourcepacks/CustomBlocks");

        File mcmetaFile = new File(packRoot, "assets/customblocks/textures/block/slot_" + slotIndex + ".png.mcmeta");



        if (animMeta != null && !animMeta.isEmpty()) {

            mcmetaFile.getParentFile().mkdirs();

            try (java.io.FileWriter fw = new java.io.FileWriter(mcmetaFile, StandardCharsets.UTF_8)) {

                fw.write(animMeta);

            } catch (Exception e) {

                CustomBlocksMod.LOGGER.warn("[CustomBlocks] Failed to write .mcmeta for slot_{}: {}", slotIndex, e.getMessage());

            }

        } else {

            if (mcmetaFile.exists()) mcmetaFile.delete();

        }



        // Also update the texture hash on disk so the next join doesn't see a stale cache

        String currentHash = computeTextureHash();

        saveCachedHash(client.runDirectory, currentHash);

        clearServerHash(client.runDirectory); // stale after local edit



        // Trigger resource reload to pick up the new animation timing

        if (reloadInFlight.compareAndSet(false, true)) {

            client.reloadResources().thenRun(() ->

                client.execute(() -> {

                    reloadInFlight.set(false);

                    CustomBlocksMod.LOGGER.info("[CustomBlocks] Anim-only reload complete.");

                    pendingCreativeRefresh = true;

                })

            ).exceptionally(ex -> {

                client.execute(() -> {

                    reloadInFlight.set(false);

                    CustomBlocksMod.LOGGER.error("[CustomBlocks] Anim-only reload failed.", ex);

                });

                return null;

            });

        }

    }



    // ── Texture cache helpers ────────────────────────────────────────────────



    private static final String CACHE_HASH_FILE = "customblocks_cache_hash.txt";

    private static final String SERVER_HASH_FILE = "customblocks_server_hash.txt";



    /** Compute a SHA-256 hash of all slot IDs + texture bytes in the client SlotManager. */

    private static String computeTextureHash() {

        try {

            MessageDigest md = MessageDigest.getInstance("SHA-256");

            // Sort by index for deterministic order — must match server-side algorithm.

            java.util.List<SlotData> sorted = new java.util.ArrayList<>(SlotManager.allSlots());

            sorted.sort(java.util.Comparator.comparingInt(d -> d.index));

            for (SlotData data : sorted) {

                md.update(data.customId.getBytes(StandardCharsets.UTF_8));

                if (data.texture != null) md.update(data.texture);

                if (data.animMeta != null) md.update(data.animMeta.getBytes(StandardCharsets.UTF_8));

                // Sort face keys for deterministic order (also ConcurrentHashMap)

                java.util.List<String> faceKeys = new java.util.ArrayList<>(data.faceTextures.keySet());

                java.util.Collections.sort(faceKeys);

                for (String faceKey : faceKeys) {

                    md.update(faceKey.getBytes(StandardCharsets.UTF_8));

                    md.update(data.faceTextures.get(faceKey));

                }

            }

            return HexFormat.of().formatHex(md.digest());

        } catch (Exception e) {

            CustomBlocksMod.LOGGER.warn("[CustomBlocks] Hash computation failed: {}", e.getMessage());

            return "";

        }

    }



    /** Load the cached texture hash from disk. Returns null if not found. */

    private static String loadCachedHash(File runDir) {

        try {

            Path hashFile = runDir.toPath().resolve(CACHE_HASH_FILE);

            if (Files.exists(hashFile)) {

                return Files.readString(hashFile, StandardCharsets.UTF_8).trim();

            }

        } catch (IOException e) {

            CustomBlocksMod.LOGGER.warn("[CustomBlocks] Could not read cache hash: {}", e.getMessage());

        }

        return null;

    }



    /** Save the texture hash to disk after successful generation. */

    private static void saveCachedHash(File runDir, String hash) {

        try {

            Path hashFile = runDir.toPath().resolve(CACHE_HASH_FILE);

            Files.writeString(hashFile, hash, StandardCharsets.UTF_8);

        } catch (IOException e) {

            CustomBlocksMod.LOGGER.warn("[CustomBlocks] Could not write cache hash: {}", e.getMessage());

        }

    }



    /** Load the server-provided hash from disk. Returns null if not found. */

    private static String loadServerHash(File runDir) {

        try {

            Path hashFile = runDir.toPath().resolve(SERVER_HASH_FILE);

            if (Files.exists(hashFile)) {

                return Files.readString(hashFile, StandardCharsets.UTF_8).trim();

            }

        } catch (IOException e) {

            CustomBlocksMod.LOGGER.warn("[CustomBlocks] Could not read server hash: {}", e.getMessage());

        }

        return null;

    }



    /** Save the server-provided hash to disk so the client echoes it back on next join. */

    private static void saveServerHash(File runDir, String hash) {

        try {

            Path hashFile = runDir.toPath().resolve(SERVER_HASH_FILE);

            Files.writeString(hashFile, hash, StandardCharsets.UTF_8);

        } catch (IOException e) {

            CustomBlocksMod.LOGGER.warn("[CustomBlocks] Could not write server hash: {}", e.getMessage());

        }

    }



    /** Clear the server hash file (data changed locally, stale). */
    private static void clearServerHash(File runDir) {
        try {
            Files.deleteIfExists(runDir.toPath().resolve(SERVER_HASH_FILE));
        } catch (IOException e) {
            CustomBlocksMod.LOGGER.warn("[CustomBlocks] Could not clear server hash: {}", e.getMessage());
        }
    }

    /** Clear the cached texture hash file to force a reload. */
    private static void clearCachedHash(File runDir) {
        try {
            Files.deleteIfExists(runDir.toPath().resolve(CACHE_HASH_FILE));
        } catch (IOException e) {
            CustomBlocksMod.LOGGER.warn("[CustomBlocks] Could not clear cache hash: {}", e.getMessage());
        }
    }



    private static void injectPackIfNeeded(MinecraftClient client) {

        if (!client.options.resourcePacks.contains(PACK_ENTRY)) {

            client.options.resourcePacks.add(PACK_ENTRY);

            client.options.write();

        }

        client.getResourcePackManager().scanPacks();

        CustomBlocksMod.LOGGER.info("[CustomBlocks] Pack inject: entry in options={}, scanPacks done, available profiles={}",

            client.options.resourcePacks.contains(PACK_ENTRY),

            client.getResourcePackManager().getProfiles().stream()

                .map(p -> p.getId()).collect(java.util.stream.Collectors.joining(", ")));

    }

}

