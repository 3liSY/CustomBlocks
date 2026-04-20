package com.customblocks.client;

import com.customblocks.client.gui.AnimBlockScreen;
import com.customblocks.network.AnimSettingsPayload;
import com.customblocks.network.OpenAnimGuiPayload;
import com.customblocks.CustomBlocksMod;
import com.customblocks.core.SlotData;
import com.customblocks.core.SlotManager;
import com.customblocks.block.SlotBlock;
import com.customblocks.client.gui.CustomBlocksScreen;
import com.customblocks.client.texture.TextureCache;
import com.customblocks.network.FullSyncPayload;
import com.customblocks.network.SlotUpdatePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.hit.BlockHitResult;
import java.util.Map;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Environment(EnvType.CLIENT)
public class CustomBlocksClient implements ClientModInitializer {

    private static final String PACK_ENTRY = "file/CustomBlocks";
    private static final AtomicBoolean reloadInFlight  = new AtomicBoolean(false);
    private static final AtomicBoolean generateRunning = new AtomicBoolean(false);
    private static final AtomicLong    lastPacketTime  = new AtomicLong(0);

    // True while processing the initial join burst — suppresses individual packet reloads.
    private static volatile boolean joinBurst        = false;

    // Set to true when the server's "sync_done" sentinel arrives, signalling that all
    // join textures have been enqueued. The waiting debounce thread breaks immediately
    // on this flag rather than waiting out a fixed timer that can fire mid-burst on
    // slow internet connections and cause cascading resource-pack reloads / disconnect.
    private static volatile boolean syncDoneReceived = false;

    public static volatile boolean pendingCreativeRefresh = false;

    private static KeyBinding openGuiKey;

    @Override
    public void onInitializeClient() {

        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.customblocks.open_gui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                "category.customblocks"
        ));

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            SlotManager.loadFromClientDir(client.runDirectory);
            ResourcePackGenerator.generate(client);
            injectPackIfNeeded(client);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuiKey.wasPressed()) {
                if (client.currentScreen == null)
                    client.setScreen(new CustomBlocksScreen());
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
            ClientPlayNetworking.send(new com.customblocks.network.SyncRequestPayload());
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
            client.execute(() -> {
                switch (payload.action()) {

                    // ── sync_done sentinel ────────────────────────────────────
                    // The server enqueues this as the very last item after all join
                    // textures have been queued. On receipt, we signal the waiting
                    // debounce thread to break out of its sleep loop and fire the
                    // resource-pack reload immediately (within one 200ms poll cycle).
                    //
                    // This is the primary fix for the disconnect regression: previously
                    // the 4-second debounce would fire mid-burst on internet connections,
                    // setting joinBurst=false and causing every subsequent texture packet
                    // to trigger its own 2-second reload. Multiple concurrent reloads
                    // while ResourcePackGenerator was still writing files to disk
                    // produced a malformed resource pack and a server-sent TCP RST.
                    case "sync_done" -> {
                        syncDoneReceived = true;
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
                        TextureCache.invalidate(payload.customId());
                    }
                    case "retexture" -> {
                        SlotManager.updateTexture(payload.customId(), payload.texture());
                        if (payload.animMeta() != null && !payload.animMeta().isEmpty())
                            SlotManager.setAnimMeta(payload.customId(), payload.animMeta());
                        TextureCache.invalidate(payload.customId());
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
                        if (!joinBurst) scheduleGenerateAndReload(client, 2000L);
                        return;
                    }
                }

                // Only trigger reload for actions that actually change rendered appearance,
                // and only when NOT in the initial join burst (which uses sync_done or the
                // fallback debounce for its single deferred reload).
                if (!joinBurst) {
                    String action = payload.action();
                    boolean needsReload = action.equals("add") || action.equals("retexture")
                            || action.equals("remove") || action.equals("setface")
                            || action.equals("clearface") || action.equals("clearfaces");
                    if (needsReload) scheduleGenerateAndReload(client, 2000L);
                } else {
                    // Still in join burst — refresh the fallback debounce timer so it
                    // doesn't fire if sync_done is somehow delayed beyond joinDebounceMs.
                    lastPacketTime.set(System.currentTimeMillis());
                }
            });
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
                    catch (InterruptedException ignored) { break; }
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
                        pendingCreativeRefresh = true;
                    });
                } else {
                    // CACHE MISS — regenerate pack and reload resources
                    CustomBlocksMod.LOGGER.info(
                            "[CustomBlocks] Texture cache MISS (cur={}, cached={}, packExists={}). Regenerating.",
                            currentHash.substring(0, Math.min(12, currentHash.length())),
                            cachedHash  != null ? cachedHash.substring(0, Math.min(12, cachedHash.length())) : "null",
                            packExists);
                    SlotManager.saveToClientDir(client.runDirectory);
                    ResourcePackGenerator.generate(client);
                    saveCachedHash(client.runDirectory, currentHash);
                    client.execute(() -> {
                        injectPackIfNeeded(client);
                        joinBurst        = false;
                        syncDoneReceived = false;
                        generateRunning.set(false);

                        if (reloadInFlight.compareAndSet(false, true)) {
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
        }
        // If generateRunning is already true, the running thread will see the updated
        // lastPacketTime on its next 200ms poll and extend or break its wait as needed.
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

    /** Compute a SHA-256 hash of all slot IDs + texture bytes in the client SlotManager. */
    private static String computeTextureHash() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (SlotData data : SlotManager.allSlots()) {
                md.update(data.customId.getBytes(StandardCharsets.UTF_8));
                if (data.texture != null) md.update(data.texture);
                if (data.animMeta != null) md.update(data.animMeta.getBytes(StandardCharsets.UTF_8));
                for (var entry : data.faceTextures.entrySet()) {
                    md.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                    md.update(entry.getValue());
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
