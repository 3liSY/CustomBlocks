package com.customblocks.client;

import com.customblocks.client.gui.AnimBlockScreen;
import com.customblocks.network.AnimSettingsPayload;
import com.customblocks.network.OpenAnimGuiPayload;
import com.customblocks.network.SyncCompletePayload;
import com.customblocks.CustomBlocksMod;
import com.customblocks.SlotManager;
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
    private static final AtomicBoolean reloadInFlight    = new AtomicBoolean(false);
    private static final AtomicBoolean generateRunning   = new AtomicBoolean(false);
    private static final AtomicLong    lastPacketTime    = new AtomicLong(0);
    // True while processing the initial join burst — suppresses individual packet reloads
    private static volatile boolean    joinBurst         = false;

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
            ResourcePackGenerator.generate(client.runDirectory);
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

        // §1 — FullSyncPayload: receive metadata, wait passively for SyncCompletePayload.
        //       Server sends SyncCompletePayload when queue hits 0 (no more 4000ms guessing).
        ClientPlayNetworking.registerGlobalReceiver(FullSyncPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> {
                joinBurst = true;  // suppress per-packet reloads during initial burst
                SlotManager.clearAll();
                TextureCache.invalidateAll();
                for (FullSyncPayload.SlotEntry e : payload.entries()) {
                    SlotManager.assignAtIndex(e.index(), e.customId(), e.displayName(), null);
                    SlotManager.setProperties(e.customId(), e.lightLevel(), e.hardness(), e.soundType());
                    // Restore animMeta so ResourcePackGenerator writes .mcmeta on join
                    if (e.animMeta() != null && !e.animMeta().isEmpty())
                        SlotManager.setAnimMeta(e.customId(), e.animMeta());
                }
                if (payload.tabIconTexture() != null)
                    SlotManager.setTabIconTexture(payload.tabIconTexture());
                // §1: Do NOT schedule reload here — wait for explicit SyncCompletePayload.
            });
        });

        // §1 — SyncCompletePayload: explicit completion signal from server.
        //       Triggers exactly ONE guaranteed reloadResources() with no Netty blocking.
        ClientPlayNetworking.registerGlobalReceiver(SyncCompletePayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> scheduleGenerateAndReload(client, 200L));
        });


        // ── SlotUpdatePayload ─────────────────────────────────────────────
        ClientPlayNetworking.registerGlobalReceiver(SlotUpdatePayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> {
                switch (payload.action()) {
                    case "add" -> {
                        if (SlotManager.getById(payload.customId()) != null)
                            SlotManager.updateTexture(payload.customId(), payload.texture());
                        else
                            SlotManager.assignAtIndex(payload.slotIndex(), payload.customId(),
                                    payload.displayName(), payload.texture());
                        SlotManager.setProperties(payload.customId(),
                                payload.lightLevel(), payload.hardness(), payload.soundType());
                        // KEY FIX: apply animMeta so .mcmeta file gets written
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
                        // Pure animMeta update — no texture change, just reload .mcmeta
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
                            java.util.List<SlotManager.ShapeBox> boxes = new java.util.ArrayList<>();
                            if (!payload.shapeData().equals("full")) {
                                for (String part : payload.shapeData().split(";")) {
                                    try { boxes.add(SlotManager.ShapeBox.parse(part)); } catch (Exception ignored) {}
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
                // and only when NOT in the initial join burst (which uses its own deferred reload)
                if (!joinBurst) {
                    String action = payload.action();
                    boolean needsReload = action.equals("add") || action.equals("retexture")
                            || action.equals("remove") || action.equals("setface")
                            || action.equals("clearface") || action.equals("clearfaces")
                            || action.equals("setshape") || action.equals("animsettings");
                    if (needsReload) scheduleGenerateAndReload(client, 2000L);
                } else {
                    // Still in join burst — keep refreshing the debounce timer
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

        // ── HUD overlay ───────────────────────────────────────────────────
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null || client.player == null) return;
            if (!(client.crosshairTarget instanceof BlockHitResult bhr)) return;
            var state = client.world.getBlockState(bhr.getBlockPos());
            if (!(state.getBlock() instanceof SlotBlock sb)) return;
            SlotManager.SlotData data = SlotManager.getBySlot(sb.getSlotKey());
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
     * Debounced generate+reload. debounceMs is how long to wait after the
     * last call before actually doing the work. Join burst uses 4000ms so
     * all initial texture packets settle before the single reload fires.
     * Live edits use 2000ms so they feel responsive.
     */
    private static void scheduleGenerateAndReload(MinecraftClient client, long debounceMs) {
        lastPacketTime.set(System.currentTimeMillis());
        if (generateRunning.compareAndSet(false, true)) {
            Thread t = new Thread(() -> {
                // Wait for silence
                while (true) {
                    long remaining = debounceMs - (System.currentTimeMillis() - lastPacketTime.get());
                    if (remaining <= 0) break;
                    try { Thread.sleep(Math.max(50, remaining)); } catch (InterruptedException ignored) { break; }
                }
                SlotManager.saveToClientDir(client.runDirectory);
                ResourcePackGenerator.generate(client.runDirectory);
                client.execute(() -> {
                    injectPackIfNeeded(client);
                    joinBurst = false;  // join burst is definitely over by now
                    generateRunning.set(false);
                    // Only fire one reload at a time — if one is already in flight,
                    // the files are already written and it will pick them up
                    if (reloadInFlight.compareAndSet(false, true)) {
                        client.reloadResources().thenRun(() ->
                            client.execute(() -> {
                                reloadInFlight.set(false);
                                CustomBlocksMod.LOGGER.info("[CustomBlocks] Resources reloaded.");
                                pendingCreativeRefresh = true;
                            })
                        );
                    } else {
                        pendingCreativeRefresh = true;
                    }
                });
            }, "CustomBlocks-GenerateReload");
            t.setDaemon(true);
            t.start();
        }
        // If generateRunning is already true, the running thread will see
        // the updated lastPacketTime and extend its wait — no new thread needed
    }

    private static void injectPackIfNeeded(MinecraftClient client) {
        if (!client.options.resourcePacks.contains(PACK_ENTRY)) {
            client.options.resourcePacks.add(PACK_ENTRY);
            client.options.write();
        }
    }
}
