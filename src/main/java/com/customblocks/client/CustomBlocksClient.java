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

    private static final String PACK_ENTRY = "file/customblocks_generated";
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

        // ── FullSyncPayload — initial join ────────────────────────────────────
        ClientPlayNetworking.registerGlobalReceiver(FullSyncPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> {
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
                        return;
                    }
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
