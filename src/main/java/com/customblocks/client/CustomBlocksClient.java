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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Environment(EnvType.CLIENT)
public class CustomBlocksClient implements ClientModInitializer {

    private static final String PACK_ENTRY = "file/customblocks_generated";
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

                SlotManager.clearAll();
                TextureCache.invalidateAll();
                for (FullSyncPayload.SlotEntry e : payload.entries()) {
                    SlotManager.assignAtIndex(e.index(), e.customId(), e.displayName(), null);
                    SlotManager.setProperties(e.customId(), e.lightLevel(), e.hardness(), e.soundType());
                    if (e.animMeta() != null && !e.animMeta().isEmpty())
                        SlotManager.setAnimMeta(e.customId(), e.animMeta());
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
                            || action.equals("clearface") || action.equals("clearfaces")
                            || action.equals("setshape") || action.equals("animsettings");
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
                SlotManager.saveToClientDir(client.runDirectory);
                ResourcePackGenerator.generate(client);
                client.execute(() -> {
                    injectPackIfNeeded(client);
                    joinBurst        = false;   // burst is definitively over
                    syncDoneReceived = false;   // reset for next join
                    generateRunning.set(false); // allow future threads

                    // Only fire one reload at a time — if one is already in flight,
                    // the files are already written and it will pick them up.
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
            }, "CustomBlocks-GenerateReload");
            t.setDaemon(true);
            t.start();
        }
        // If generateRunning is already true, the running thread will see the updated
        // lastPacketTime on its next 200ms poll and extend or break its wait as needed.
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
