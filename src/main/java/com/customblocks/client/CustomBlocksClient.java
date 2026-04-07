package com.customblocks.client;

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
    private static final AtomicBoolean reloadScheduled   = new AtomicBoolean(false);
    private static final AtomicBoolean generateRunning   = new AtomicBoolean(false);
    // Timestamp of last incoming packet — debounce fires 2s after THE LAST packet, not the first
    private static final AtomicLong    lastPacketTime    = new AtomicLong(0);

    // Set to true after reload — creative tab will refresh on next tick if open
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
            // Open GUI on B press
            while (openGuiKey.wasPressed()) {
                if (client.currentScreen == null)
                    client.setScreen(new CustomBlocksScreen());
            }

            // After resource reload, bust the ItemGroup icon cache and refresh creative tab
            if (pendingCreativeRefresh && client.player != null) {
                pendingCreativeRefresh = false;
                bustItemGroupIconCache();
                // Reopen creative screen to rebuild display stacks
                if (client.currentScreen instanceof CreativeInventoryScreen) {
                    client.setScreen(new CreativeInventoryScreen(
                            client.player,
                            client.player.networkHandler.getEnabledFeatures(),
                            false
                    ));
                }
            }
        });

        // ── FullSyncPayload ─────────────────────────────────────────────────────
        ClientPlayNetworking.registerGlobalReceiver(FullSyncPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> {
                // Clear stale data from previous session
                SlotManager.clearAll();
                TextureCache.invalidateAll();  // prevent stale GPU textures after rejoin
                for (FullSyncPayload.SlotEntry e : payload.entries()) {
                    SlotManager.assignAtIndex(e.index(), e.customId(), e.displayName(), null);
                    SlotManager.setProperties(e.customId(), e.lightLevel(), e.hardness(), e.soundType());
                }
                if (payload.tabIconTexture() != null)
                    SlotManager.setTabIconTexture(payload.tabIconTexture());
                // Trigger a regeneration so the resource pack reflects current slot assignments.
                // This is especially important when all blocks have no textures (new server).
                // Texture packets arriving shortly after will debounce into the same reload cycle.
                scheduleGenerateAndReload(client);
            });
        });

        // ── SlotUpdatePayload ───────────────────────────────────────────────────
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
                        TextureCache.invalidate(payload.customId());
                    }
                    case "remove" -> {
                        TextureCache.invalidate(payload.customId());
                        SlotManager.remove(payload.customId());
                    }
                    case "rename"  -> SlotManager.rename(payload.customId(), payload.displayName());
                    case "setprop" -> SlotManager.setProperties(payload.customId(),
                            payload.lightLevel(), payload.hardness(), payload.soundType());
                    case "retexture" -> {
                        SlotManager.updateTexture(payload.customId(), payload.texture());
                        TextureCache.invalidate(payload.customId());
                    }
                    case "tabicon" -> {
                        SlotManager.setTabIconTexture(payload.texture());
                        scheduleGenerateAndReload(client);
                        return;
                    }
                }
                String action = payload.action();
                boolean needsReload = action.equals("add") || action.equals("retexture")
                        || action.equals("remove");
                if (needsReload) scheduleGenerateAndReload(client);
            });
        });

        // ── HUD overlay: show block name only (no ID) ───────────────────────────
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

    /** Clear the cached icon on our ItemGroup so MC re-calls our supplier lambda. */
    private static void bustItemGroupIconCache() {
        try {
            net.minecraft.item.ItemGroup group =
                net.minecraft.registry.Registries.ITEM_GROUP.get(CustomBlocksMod.CUSTOM_BLOCKS_TAB);
            if (group == null) return;
            // Try by common Yarn-mapped field names first, then fall back to type scan
            String[] candidates = {"icon", "field_24603", "iconStack"};
            for (String name : candidates) {
                try {
                    java.lang.reflect.Field f = net.minecraft.item.ItemGroup.class.getDeclaredField(name);
                    f.setAccessible(true);
                    if (f.get(group) instanceof net.minecraft.item.ItemStack) {
                        f.set(group, net.minecraft.item.ItemStack.EMPTY);
                        CustomBlocksMod.LOGGER.info("[CustomBlocks] Tab icon cache cleared via field '{}'.", name);
                        return;
                    }
                } catch (NoSuchFieldException ignored) {}
            }
            // Fallback: scan all ItemStack fields
            for (java.lang.reflect.Field f : net.minecraft.item.ItemGroup.class.getDeclaredFields()) {
                if (f.getType() == net.minecraft.item.ItemStack.class) {
                    f.setAccessible(true);
                    f.set(group, net.minecraft.item.ItemStack.EMPTY);
                    CustomBlocksMod.LOGGER.info("[CustomBlocks] Tab icon cache cleared via type scan.");
                    return;
                }
            }
            CustomBlocksMod.LOGGER.warn("[CustomBlocks] Could not find ItemGroup icon field — tab icon may not update.");
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] bustItemGroupIconCache failed: {}", e.getMessage());
        }
    }

    /**
     * True debounce: stamps current time on every call.
     * Background thread waits until 2s of silence, does I/O off-thread,
     * then reloads. generateRunning is cleared BEFORE reload so new
     * packets that arrive during reload start a fresh cycle.
     */
    private static void scheduleGenerateAndReload(MinecraftClient client) {
        lastPacketTime.set(System.currentTimeMillis());
        if (generateRunning.compareAndSet(false, true)) {
            Thread t = new Thread(() -> {
                // Wait until 2s of silence since last packet
                while (true) {
                    long remaining = 2000L - (System.currentTimeMillis() - lastPacketTime.get());
                    if (remaining <= 0) break;
                    try { Thread.sleep(Math.max(50, remaining)); } catch (InterruptedException ignored) { break; }
                }
                // Heavy I/O off main thread
                SlotManager.saveToClientDir(client.runDirectory);
                ResourcePackGenerator.generate(client);
                // Back to main thread for reload
                client.execute(() -> {
                    injectPackIfNeeded(client);
                    // Reset flag HERE so packets arriving during reload can start a new cycle
                    generateRunning.set(false);
                    if (reloadScheduled.compareAndSet(false, true)) {
                        client.reloadResources().thenRun(() ->
                            client.execute(() -> {
                                reloadScheduled.set(false);
                                CustomBlocksMod.LOGGER.info("[CustomBlocks] Resources reloaded.");
                                pendingCreativeRefresh = true;
                            })
                        );
                    } else {
                        // Reload already in flight — files are written, it will pick them up
                        pendingCreativeRefresh = true;
                    }
                });
            }, "CustomBlocks-GenerateReload");
            t.setDaemon(true);
            t.start();
        }
    }

    private static void injectPackIfNeeded(MinecraftClient client) {
        if (!client.options.resourcePacks.contains(PACK_ENTRY)) {
            client.options.resourcePacks.add(PACK_ENTRY);
            client.options.write();
        }
    }
}
