package com.customblocks.client.texture;

import com.customblocks.CustomBlocksMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Environment(EnvType.CLIENT)
public class TextureCache {

    public record TexInfo(Identifier id, int width, int height) {}

    private static final Map<String, TexInfo> CACHE = new ConcurrentHashMap<>();

    /** Load (or return cached) GPU texture for a block. Must be called on render thread. */
    public static TexInfo getOrLoad(String customId, byte[] textureBytes) {
        if (CACHE.containsKey(customId)) return CACHE.get(customId);
        if (textureBytes == null || textureBytes.length == 0) return getMissing();
        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(textureBytes));
            int w = image.getWidth();
            int h = image.getHeight();
            NativeImageBackedTexture tex = new NativeImageBackedTexture(image);
            Identifier texId = Identifier.of(CustomBlocksMod.MOD_ID, "dynamic/" + customId);
            var tm = MinecraftClient.getInstance().getTextureManager();
            try { tm.destroyTexture(texId); } catch (Exception ignored) {}
            tm.registerTexture(texId, tex);
            // Force immediate GPU upload while on the render thread
            tex.bindTexture();
            TexInfo info = new TexInfo(texId, w, h);
            CACHE.put(customId, info);
            return info;
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Failed to load GUI texture for '{}': {}", customId, e.getMessage());
            return getMissing();
        }
    }

    public static void invalidate(String customId) {
        TexInfo old = CACHE.remove(customId);
        if (old != null) {
            try { MinecraftClient.getInstance().getTextureManager().destroyTexture(old.id()); }
            catch (Exception ignored) {}
        }
    }

    public static void invalidateAll() {
        var tm = MinecraftClient.getInstance().getTextureManager();
        for (TexInfo info : CACHE.values()) {
            try { tm.destroyTexture(info.id()); } catch (Exception ignored) {}
        }
        CACHE.clear();
    }

    public static int cacheSize() { return CACHE.size(); }

    private static TexInfo getMissing() {
        return new TexInfo(Identifier.of("minecraft", "textures/misc/unknown_pack.png"), 64, 64);
    }
}
