package com.customblocks.client;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;



import com.customblocks.client.gui.AnimBlockScreen;
import com.customblocks.client.HudConfig;

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



    // Set to true when SyncCompletePayload (or legacy "sync_done") arrives, signalling
    // that all join textures have been enqueued. The waiting thread breaks immediately.

    private static volatile boolean syncDoneReceived = false;

    // 1.20 — Count-verified sync fields (set by SyncCompletePayload).
    // expectedPayloadCount == -1 means not yet received (legacy server without count support).
    private static volatile int expectedPayloadCount = -1;
    private static final java.util.concurrent.atomic.AtomicInteger receivedPayloadCount =
            new java.util.concurrent.atomic.AtomicInteger(0);

    // 1.22 — Set on disconnect if sync was incomplete; cleared after warning is shown on next join.
    private static volatile boolean syncIncompleteWarningPending = false;



    @SuppressFBWarnings("PA_PUBLIC_PRIMITIVE_ATTRIBUTE")
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



        // 7.13 — max 200 chunks (200 × 512 KB = 100 MB); prevents OOM from corrupted packets
        static final int MAX_CHUNKS = 200;

        ChunkBuffer(int totalChunks) {
            if (totalChunks <= 0 || totalChunks > MAX_CHUNKS)
                throw new IllegalArgumentException("Invalid totalChunks: " + totalChunks);
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
    private static KeyBinding hudToggleKey;

    @Override
    @SuppressFBWarnings("ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
    public void onInitializeClient() {

        HudConfig.load(); // also calls HudConfig.loadPresets()

        devConsoleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.customblocks.devconsole",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_D,
            "category.customblocks"
        ));

        hudToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.customblocks.hudtoggle",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            "category.customblocks"
        ));

        // ── Pause menu HUD Editor button — positioned below lowest existing button ─
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register(
                (client2, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof net.minecraft.client.gui.screen.GameMenuScreen) {
                // Find the bottom edge of the lowest existing button
                int lowestBottom = scaledHeight / 4 + 48;
                for (net.minecraft.client.gui.Element el : screen.children()) {
                    if (el instanceof net.minecraft.client.gui.widget.ClickableWidget cw) {
                        lowestBottom = Math.max(lowestBottom, cw.getY() + cw.getHeight());
                    }
                }
                net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(screen).add(
                    net.minecraft.client.gui.widget.ButtonWidget
                        .builder(net.minecraft.text.Text.literal("§b✦ HUD Editor"), btn ->
                            client2.setScreen(new com.customblocks.client.gui.HudEditorScreen()))
                        .dimensions(scaledWidth / 2 - 100, lowestBottom + 4, 200, 20)
                        .build()
                );
            }
        });

        // ── HudConfigSyncPayload — apply server-wide config on receive ────────
        // payload.configJson() carries a base64 string produced by HudConfig.exportCode()
        ClientPlayNetworking.registerGlobalReceiver(
                com.customblocks.network.HudConfigSyncPayload.ID, (payload, context) ->
            context.client().execute(() -> HudConfig.importCode(payload.configJson())));

        // Tint custom_square and custom_triangle icons with their NBT hex color.
        // The texture PNGs are white so the tint becomes the full displayed color.
        net.minecraft.item.Item customSquareItem = net.minecraft.registry.Registries.ITEM.get(
            net.minecraft.util.Identifier.of(com.customblocks.CustomBlocksMod.MOD_ID,
                com.customblocks.item.ColorSquareItem.CUSTOM_SQUARE_REGISTRY_ID));
        if (customSquareItem != null && customSquareItem != net.minecraft.item.Items.AIR) {
            net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry.ITEM.register(
                (stack, tintIndex) -> {
                    if (tintIndex != 0) return -1;
                    net.minecraft.component.type.NbtComponent nbt = stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
                    if (nbt == null) return 0x55CCFF | 0xFF000000;
                    net.minecraft.nbt.NbtCompound compound = nbt.copyNbt();
                    if (!compound.contains("cb_square_rgb")) return 0x55CCFF | 0xFF000000;
                    return compound.getInt("cb_square_rgb") | 0xFF000000;
                },
                customSquareItem);
        }
        net.minecraft.item.Item customTriangleItem = net.minecraft.registry.Registries.ITEM.get(
            net.minecraft.util.Identifier.of(com.customblocks.CustomBlocksMod.MOD_ID,
                com.customblocks.item.ColorTriangleItem.CUSTOM_TRIANGLE_REGISTRY_ID));
        if (customTriangleItem != null && customTriangleItem != net.minecraft.item.Items.AIR) {
            net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry.ITEM.register(
                (stack, tintIndex) -> {
                    if (tintIndex != 0) return -1;
                    net.minecraft.component.type.NbtComponent nbt = stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
                    if (nbt == null) return 0x55CCFF | 0xFF000000;
                    net.minecraft.nbt.NbtCompound compound = nbt.copyNbt();
                    if (!compound.contains("cb_triangle_rgb")) return 0x55CCFF | 0xFF000000;
                    return compound.getInt("cb_triangle_rgb") | 0xFF000000;
                },
                customTriangleItem);
        }

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {

            SlotManager.loadFromClientDir(client.runDirectory);

            ResourcePackGenerator.generate(client);

            injectPackIfNeeded(client);

        });



        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            // HUD show/hide keybind (default H)
            while (hudToggleKey.wasPressed()) {
                HudConfig.hudVisible = !HudConfig.hudVisible;
                HudConfig.save();
            }

            // Fade alpha update — runs every tick (20/sec)
            float fadeTarget = 0f;
            if (HudConfig.hudVisible && client.world != null && client.player != null
                    && client.currentScreen == null) {
                boolean lookingAtBlock = false;
                if (client.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult bhr) {
                    var bs = client.world.getBlockState(bhr.getBlockPos());
                    if (bs.getBlock() instanceof com.customblocks.block.SlotBlock sb2) {
                        if (com.customblocks.core.SlotManager.getBySlot(sb2.getSlotKey()) != null) {
                            lookingAtBlock = true;
                            HudConfig.lastSawBlockMs = System.currentTimeMillis();
                        }
                    }
                }
                // Sticky mode — keep showing for N seconds after looking away
                if (!lookingAtBlock && HudConfig.stickyMode) {
                    long elapsed = System.currentTimeMillis() - HudConfig.lastSawBlockMs;
                    if (elapsed < (long)(HudConfig.stickySeconds * 1000L)) {
                        lookingAtBlock = true;
                    }
                }
                fadeTarget = lookingAtBlock ? 1f : 0f;
            }

            if (HudConfig.fadeEnabled && HudConfig.fadeTicks > 0) {
                float step = 1.0f / Math.max(1, HudConfig.fadeTicks);
                if (fadeTarget > HudConfig.currentAlpha) {
                    HudConfig.currentAlpha = Math.min(HudConfig.currentAlpha + step, fadeTarget);
                } else {
                    HudConfig.currentAlpha = Math.max(HudConfig.currentAlpha - step, fadeTarget);
                }
            } else {
                HudConfig.currentAlpha = fadeTarget;
            }

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

            // 1.22 — Detect incomplete sync before resetting state
            boolean wasIncomplete = joinBurst && (!syncDoneReceived
                    || (expectedPayloadCount > 0 && receivedPayloadCount.get() < expectedPayloadCount));
            if (wasIncomplete) {
                syncIncompleteWarningPending = true;
                CustomBlocksMod.LOGGER.warn(
                        "[CustomBlocks] 1.22 Sync incomplete at disconnect: received={} expected={}",
                        receivedPayloadCount.get(), expectedPayloadCount);
            }

            joinBurst        = false;

            syncDoneReceived = false;

            expectedPayloadCount = -1;

            receivedPayloadCount.set(0);

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

                expectedPayloadCount = -1;

                receivedPayloadCount.set(0);

                joinBurst        = true;

                // 1.22 — Warn player if previous connection's sync was incomplete
                if (syncIncompleteWarningPending) {
                    syncIncompleteWarningPending = false;
                    if (client.player != null) {
                        client.player.sendMessage(net.minecraft.text.Text.literal(
                                "§c[CB] §7Previous sync was incomplete — some blocks may be invisible."
                                + " §7Type §f/cb sync §7if blocks look broken."), false);
                    }
                }



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

                // COL1d — Pre-warm cachedColorFamily for blocks that already have textures
                // (retained from a previous session). Runs on a daemon thread so it never
                // blocks the render thread. New blocks (null texture) are skipped here and
                // get their family computed when their texture arrives via SlotUpdatePayload.
                Thread prewarm = new Thread(() -> {
                    for (SlotData d : SlotManager.allSlots()) {
                        if (d.texture != null && d.texture.length > 0 && d.cachedColorFamily == null) {
                            com.customblocks.core.ColorDetection.DetectionResult r =
                                com.customblocks.core.ColorDetection.detect(d.texture);
                            if (r != null && r.confident() && r.primary != null) {
                                d.cachedColorFamily = r.primary;
                            }
                        }
                    }
                });
                prewarm.setDaemon(true);
                prewarm.setName("cb-color-prewarm");
                prewarm.start();

                // 1.20 — Start the sync-wait thread. The SyncCompletePayload (sent by the
                // server after all textures are enqueued) will wake this thread early via
                // syncDoneReceived. A 30-second hard timeout prevents hanging if the server
                // is old and never sends SyncCompletePayload.

                scheduleGenerateAndReload(client, 30_000L);

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

        // ── OpenHudEditorPayload — open HUD editor screen ────────────────────
        ClientPlayNetworking.registerGlobalReceiver(com.customblocks.network.OpenHudEditorPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> {
                if (client.currentScreen == null) {
                    client.setScreen(new com.customblocks.client.gui.HudEditorScreen());
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



                // 7.13 — validate totalChunks before allocation (prevents OOM from bad packet)
                if (payload.totalChunks() <= 0 || payload.totalChunks() > ChunkBuffer.MAX_CHUNKS) {
                    CustomBlocksMod.LOGGER.warn("[CustomBlocks] Rejected chunk packet — invalid totalChunks: {}", payload.totalChunks());
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



        // ── SyncCompletePayload — 1.20 count-verified sync ───────────────────
        ClientPlayNetworking.registerGlobalReceiver(
                com.customblocks.network.SyncCompletePayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                expectedPayloadCount = payload.payloadCount();
                // Save server hash for next join
                if (payload.serverHash() != null && !payload.serverHash().isEmpty()) {
                    saveServerHash(context.client().runDirectory, payload.serverHash());
                }
                // Save SHA-256 manifest to disk so we can verify integrity on next join
                if (payload.manifestJson() != null && !payload.manifestJson().isEmpty()
                        && !payload.manifestJson().equals("{}")) {
                    savePackManifest(context.client().runDirectory, payload.manifestJson());
                }
                CustomBlocksMod.LOGGER.info(
                        "[CustomBlocks] 1.20 SyncComplete: expected={} received={} hash={}",
                        payload.payloadCount(), receivedPayloadCount.get(),
                        payload.serverHash() != null && payload.serverHash().length() >= 8
                                ? payload.serverHash().substring(0, 8) : payload.serverHash());
                syncDoneReceived = true;
            });
        });

        // ── RpPausePayload — server says pause/resume reloads ────────────────

        // Config sync — server changed a hex colour; update local config and regenerate item textures
        ClientPlayNetworking.registerGlobalReceiver(com.customblocks.network.ConfigSyncPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            if (!payload.blackHex().isEmpty())  com.customblocks.CustomBlocksConfig.triangleBlackHex  = payload.blackHex();
            if (!payload.yellowHex().isEmpty()) com.customblocks.CustomBlocksConfig.triangleYellowHex = payload.yellowHex();
            if (!payload.greenHex().isEmpty())  com.customblocks.CustomBlocksConfig.triangleGreenHex  = payload.greenHex();
            if (!payload.redHex().isEmpty())    com.customblocks.CustomBlocksConfig.triangleRedHex    = payload.redHex();
            scheduleGenerateAndReload(client, fastReloadDebounceMs());
        });

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
                                try { colorIntVal = (int)(Long.parseLong(colorHex.replace("#", ""), 16) | 0xFF000000); } catch (NumberFormatException ignored) {}
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

        // ── HUD overlay — Phase 2: multi-style, opacity, fade ────────────────

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {

            float alpha = HudConfig.currentAlpha;
            if (alpha <= 0.01f) return; // fully hidden — skip all rendering

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null || client.player == null) return;
            if (!(client.crosshairTarget instanceof BlockHitResult bhr)) return;
            var state = client.world.getBlockState(bhr.getBlockPos());
            if (!(state.getBlock() instanceof SlotBlock sb)) return;
            SlotData data = SlotManager.getBySlot(sb.getSlotKey());
            if (data == null) return;

            // Build text lines — template mode or chip mode
            String faceName = bhr.getSide().getName();
            String categoryName = getCategoryName(data.customId);
            java.util.List<String> lines;
            if (HudConfig.contentMode == 1) {
                // Template mode — each non-empty line is rendered separately
                java.util.List<String> tLines = new java.util.ArrayList<>();
                for (String tpl : HudConfig.templateLines) {
                    if (tpl == null || tpl.isBlank()) continue;
                    tLines.add(tpl
                        .replace("{name}",      data.displayName)
                        .replace("{id}",        data.customId)
                        .replace("{light}",     String.valueOf(data.lightLevel))
                        .replace("{hardness}",  String.valueOf(data.hardness))
                        .replace("{sound}",     data.soundType != null ? data.soundType : "none")
                        .replace("{collision}", data.noCollision ? "OFF" : "ON")
                        .replace("{face}",      faceName)
                        .replace("{health}",    data.blockHealth.name())
                        .replace("{anim}",      data.isAnimated() ? "Animated" : "Static")
                        .replace("{category}", categoryName));
                }
                lines = tLines.isEmpty() ? java.util.List.of() : tLines;
            } else {
                // Chip mode — respect chipOrder
                java.util.List<String> tokens = new java.util.ArrayList<>();
                for (int fieldIdx : HudConfig.chipOrder) {
                    if (!HudConfig.isChipEnabled(fieldIdx)) continue;
                    tokens.add(buildHudToken(fieldIdx, data, faceName, categoryName));
                }
                boolean broken = data.blockHealth == com.customblocks.core.SlotData.BlockHealth.CORRUPT
                              || data.blockHealth == com.customblocks.core.SlotData.BlockHealth.LOAD_FAILURE;
                if (broken) tokens.add("§c⚠ " + data.blockHealth.name());
                lines = packTokens(tokens, client.textRenderer, 240);
            }
            if (lines.isEmpty()) return;

            // Layout
            float scale = Math.max(0.5f, Math.min(2.0f, HudConfig.scale));
            int lineH = client.textRenderer.fontHeight + 2;
            int maxW  = 0;
            for (String l : lines) maxW = Math.max(maxW, client.textRenderer.getWidth(net.minecraft.text.Text.literal(l)));
            int totalH = lines.size() * lineH + 4;
            int boxW   = maxW + 10;
            int boxLeft = (HudConfig.x >= 0) ? HudConfig.x : (ctx.getScaledWindowWidth() / 2 - (int)(boxW * scale / 2));
            int boxTop  = (HudConfig.y >= 0) ? HudConfig.y : 34;

            // Alpha helpers
            int a = Math.min(255, (int)(alpha * 255));
            int bgA   = Math.min(255, (int)(alpha * HudConfig.bgOpacity   * 255));
            int txtA  = Math.min(255, (int)(alpha * HudConfig.textOpacity * 255));
            int accent = (HudConfig.accentColor & 0x00FFFFFF) | (a << 24);

            ctx.getMatrices().push();
            ctx.getMatrices().translate(boxLeft, boxTop, 0);
            ctx.getMatrices().scale(scale, scale, 1f);

            if (HudConfig.style == 2) {
                // ── Plain Text — no background, just text with shadow ─────────
                for (int i = 0; i < lines.size(); i++) {
                    int textColor = (txtA << 24) | 0x00FFFFFF;
                    ctx.drawTextWithShadow(client.textRenderer,
                        net.minecraft.text.Text.literal(lines.get(i)),
                        5, 2 + i * lineH, textColor);
                }
            } else if (HudConfig.style == 1) {
                // ── Glow Box — rounded-ish corners, glowing border ───────────
                int bg = (bgA << 24) | 0x00080818;
                ctx.fill(2, 2, boxW - 2, totalH - 2, bg);
                // Top/bottom border lines
                ctx.fill(2, 0, boxW - 2, 2, accent);
                ctx.fill(2, totalH - 2, boxW - 2, totalH, accent);
                // Left/right border lines
                ctx.fill(0, 2, 2, totalH - 2, accent);
                ctx.fill(boxW - 2, 2, boxW, totalH - 2, accent);
                // Inner glow strip along top
                int glowColor = (Math.min(255, bgA + 40) << 24) | (HudConfig.accentColor & 0x00FFFFFF);
                ctx.fill(2, 2, boxW - 2, 4, glowColor);
                for (int i = 0; i < lines.size(); i++) {
                    int textColor = (txtA << 24) | 0x00FFFFFF;
                    ctx.drawTextWithShadow(client.textRenderer,
                        net.minecraft.text.Text.literal(lines.get(i)),
                        5, 2 + i * lineH, textColor);
                }
            } else {
                // ── Pill (default) — compact bar, Lunar-style ─────────────────
                int bg = (bgA << 24) | 0x00000000;
                ctx.fill(0, 0, boxW, totalH, bg);
                // Left accent bar (3px wide, full height)
                ctx.fill(0, 0, 3, totalH, accent);
                for (int i = 0; i < lines.size(); i++) {
                    int textColor = (txtA << 24) | 0x00FFFFFF;
                    ctx.drawTextWithShadow(client.textRenderer,
                        net.minecraft.text.Text.literal(lines.get(i)),
                        7, 2 + i * lineH, textColor);
                }
            }

            ctx.getMatrices().pop();
        });

    }



    private static String getCategoryName(String blockId) {
        java.util.Set<String> cats = com.customblocks.core.CategoryManager.getCategoriesForBlock(blockId);
        if (cats == null || cats.isEmpty()) return "None";
        String key = cats.iterator().next();
        com.customblocks.core.Category cat = com.customblocks.core.CategoryManager.getCategory(key);
        return cat != null ? cat.displayName() : key;
    }

    private static String buildHudToken(int fieldIdx, com.customblocks.core.SlotData data,
                                        String faceName, String categoryName) {
        return switch (fieldIdx) {
            case 0 -> "§f❖ " + data.displayName;
            case 1 -> "§8[" + data.customId + "]";
            case 2 -> "§7Light:§f" + data.lightLevel;
            case 3 -> "§7Hard:§f" + data.hardness;
            case 4 -> "§7🔊§f" + (data.soundType != null ? data.soundType : "none");
            case 5 -> "§7Coll:" + (data.noCollision ? "§cOFF" : "§aON");
            case 6 -> "§7Face:§f" + faceName;
            case 7 -> "§7Cat:§f" + categoryName;
            case 8 -> switch (data.blockHealth) {
                case HEALTHY -> "§aHEALTHY";
                case CORRUPT -> "§c⚠CORRUPT";
                case LOAD_FAILURE -> "§e⚠LOAD_FAIL";
                case PLACEHOLDER -> "§8NO_TEX";
            };
            case 9 -> data.isAnimated() ? "§bAnimated" : "§7Static";
            default -> "";
        };
    }

    private static java.util.List<String> packTokens(java.util.List<String> tokens,
                                                     net.minecraft.client.font.TextRenderer tr,
                                                     int maxPx) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curW = 0;
        for (String tok : tokens) {
            int tokW = tr.getWidth(net.minecraft.text.Text.literal(tok));
            if (!cur.isEmpty() && curW + 16 + tokW > maxPx) {
                lines.add(cur.toString().trim());
                cur.setLength(0);
                curW = 0;
            }
            if (!cur.isEmpty()) { cur.append("  "); curW += 16; }
            cur.append(tok);
            curW += tokW;
        }
        if (!cur.isEmpty()) lines.add(cur.toString().trim());
        return lines;
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

                // COL1d — After all "add" payloads have arrived, compute cachedColorFamily
                // for any block that now has a texture but hasn't been analysed yet.
                Thread prewarmPost = new Thread(() -> {
                    for (SlotData d : SlotManager.allSlots()) {
                        if (d.texture != null && d.texture.length > 0 && d.cachedColorFamily == null) {
                            com.customblocks.core.ColorDetection.DetectionResult r =
                                com.customblocks.core.ColorDetection.detect(d.texture);
                            if (r != null && r.confident() && r.primary != null) {
                                d.cachedColorFamily = r.primary;
                            }
                        }
                    }
                });
                prewarmPost.setDaemon(true);
                prewarmPost.setName("cb-color-prewarm-post");
                prewarmPost.start();

                return;

            }



            case "add" -> {

                // 1.20 — count every texture payload received for count-verified sync
                if (joinBurst) receivedPayloadCount.incrementAndGet();

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

                // COL1d — real-time update outside join burst (single block add)
                if (!joinBurst && payload.texture() != null && payload.texture().length > 0) {
                    final String addId = payload.customId();
                    final byte[] addTex = payload.texture();
                    Thread t = new Thread(() -> {
                        SlotData d = SlotManager.getById(addId);
                        if (d != null && d.cachedColorFamily == null) {
                            com.customblocks.core.ColorDetection.DetectionResult r =
                                com.customblocks.core.ColorDetection.detect(addTex);
                            if (r != null && r.confident() && r.primary != null) {
                                d.cachedColorFamily = r.primary;
                            }
                        }
                    });
                    t.setDaemon(true);
                    t.setName("cb-color-prewarm-add");
                    t.start();
                }

            }

            case "retexture" -> {

                SlotManager.updateTexture(payload.customId(), payload.texture());

                if (payload.animMeta() != null && !payload.animMeta().isEmpty())

                    SlotManager.setAnimMeta(payload.customId(), payload.animMeta());

                if (TextureCache.invalidateIfChanged(payload.customId(), payload.texture()))
                    TextureCache.schedulePreload(payload.customId(), payload.texture());

                // COL1d — update color family when texture changes
                if (payload.texture() != null && payload.texture().length > 0) {
                    final String rtxId = payload.customId();
                    final byte[] rtxTex = payload.texture();
                    Thread t = new Thread(() -> {
                        SlotData d = SlotManager.getById(rtxId);
                        if (d != null) {
                            com.customblocks.core.ColorDetection.DetectionResult r =
                                com.customblocks.core.ColorDetection.detect(rtxTex);
                            if (r != null && r.confident() && r.primary != null) {
                                d.cachedColorFamily = r.primary;
                            }
                        }
                    });
                    t.setDaemon(true);
                    t.setName("cb-color-prewarm-rtx");
                    t.start();
                }

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

                // 1.20 — Wait for SyncCompletePayload (count-verified) OR debounce silence
                // (fallback for live edits where no SyncCompletePayload is sent).
                while (true) {
                    if (syncDoneReceived) {
                        // Count-verified: both SyncComplete received AND all "add" payloads received.
                        // expectedPayloadCount == -1 means legacy server without count support — proceed.
                        if (expectedPayloadCount < 0
                                || receivedPayloadCount.get() >= expectedPayloadCount) break;
                        // Counts don't match yet — "add" payloads still in flight, wait briefly
                        try { Thread.sleep(50); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt(); break;
                        }
                        continue;
                    }

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

                // 1.20 — SHA-256 manifest verification replaces the folder-exists check.
                // Every PNG in the manifest must exist on disk with a matching hash.
                boolean packIntact = verifyPackManifest(client.runDirectory);

                if (currentHash.equals(cachedHash) && packIntact) {

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

                            "[CustomBlocks] Texture cache MISS (cur={}, cached={}, packIntact={}). Regenerating.",

                            currentHash.substring(0, Math.min(12, currentHash.length())),

                            cachedHash  != null ? cachedHash.substring(0, Math.min(12, cachedHash.length())) : "null",

                            packIntact);

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

    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
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

    // ── 1.20 SHA-256 pack manifest helpers ─────────────────────────────────

    private static final String PACK_MANIFEST_FILE = "customblocks_pack_manifest.json";

    /** Save the server's SHA-256 manifest to disk after a successful sync. */
    private static void savePackManifest(File runDir, String manifestJson) {
        try {
            Path f = runDir.toPath().resolve(PACK_MANIFEST_FILE);
            Files.writeString(f, manifestJson, StandardCharsets.UTF_8);
        } catch (IOException e) {
            CustomBlocksMod.LOGGER.warn("[CustomBlocks] Could not save pack manifest: {}", e.getMessage());
        }
    }

    /**
     * 1.20 — Verify that every texture in the stored SHA-256 manifest matches
     * the corresponding PNG file on disk. Returns true if all textures are intact
     * (or if no manifest exists — treat as first join).
     */
    private static boolean verifyPackManifest(File runDir) {
        Path manifestPath = runDir.toPath().resolve(PACK_MANIFEST_FILE);
        if (!java.nio.file.Files.exists(manifestPath)) {
            // No manifest yet (first install or manifest was deleted) — treat as intact
            // so the existing hash-based check still triggers a full sync if needed.
            File assetsDir = new File(runDir, "resourcepacks/CustomBlocks/assets");
            return assetsDir.isDirectory();
        }
        try {
            String json = Files.readString(manifestPath, StandardCharsets.UTF_8);
            com.google.gson.JsonObject manifest =
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (manifest.size() == 0) return true;

            java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
            File assetsDir = new File(runDir, "resourcepacks/CustomBlocks/assets/customblocks/textures/block");

            for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : manifest.entrySet()) {
                String customId      = entry.getKey();
                String expectedHash  = entry.getValue().getAsString();

                // Resolve PNG by looking up slot index from customId
                com.customblocks.core.SlotData slot = SlotManager.getById(customId);
                if (slot == null) return false; // slot missing from client — needs sync

                File png = new File(assetsDir, "slot_" + slot.index + ".png");
                if (!png.exists()) return false;

                sha256.reset();
                byte[] fileBytes = Files.readAllBytes(png.toPath());
                byte[] hashBytes = sha256.digest(fileBytes);
                StringBuilder sb = new StringBuilder();
                for (byte b : hashBytes) sb.append(String.format("%02x", b));
                if (!sb.toString().equals(expectedHash)) return false;
            }
            return true;
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.warn("[CustomBlocks] Pack manifest verification failed: {}", e.getMessage());
            return false; // on any error, force rebuild
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

