package com.customblocks.client.texture;

import com.customblocks.CustomBlocksMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.Map;
import java.util.zip.CRC32;

@Environment(EnvType.CLIENT)
public class TextureCache {

    public record TexInfo(Identifier id, int width, int height) {}

    private static final Map<String, TexInfo>     CACHE     = new ConcurrentHashMap<>();
    private static final Map<String, Long>         HASH_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, NativeImage>  DECODED   = new ConcurrentHashMap<>();

    private static final ExecutorService DECODE_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CB-TextureDecode");
        t.setDaemon(true);
        return t;
    });

    /**
     * D5: Schedule off-thread pre-decode of texture bytes so the GPU upload in
     * {@link #getOrLoad} can skip the CPU-bound NativeImage.read() step.
     * Safe to call from any thread. No-op if already cached or already queued.
     */
    public static void schedulePreload(String customId, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        if (CACHE.containsKey(customId) || DECODED.containsKey(customId)) return;
        DECODE_POOL.submit(() -> {
            if (CACHE.containsKey(customId) || DECODED.containsKey(customId)) return;
            try {
                NativeImage img = NativeImage.read(new ByteArrayInputStream(bytes));
                DECODED.putIfAbsent(customId, img);
            } catch (IOException e) {
                CustomBlocksMod.LOGGER.debug("[CB] Preload decode failed for {}: {}", customId, e.getMessage());
            }
        });
    }

    /** Load (or return cached) GPU texture for a block. Must be called on render thread. */
    public static TexInfo getOrLoad(String customId, byte[] textureBytes) {
        TexInfo existing = CACHE.get(customId);
        if (existing != null) return existing;
        if (textureBytes == null || textureBytes.length == 0) return getMissing();
        try {
            NativeImage image = DECODED.remove(customId);
            if (image == null) image = NativeImage.read(new ByteArrayInputStream(textureBytes));
            int w = image.getWidth();
            int h = image.getHeight();
            NativeImageBackedTexture tex = new NativeImageBackedTexture(image);
            Identifier texId = Identifier.of(CustomBlocksMod.MOD_ID, "dynamic/" + customId);
            var tm = MinecraftClient.getInstance().getTextureManager();
            try { tm.destroyTexture(texId); } catch (Exception e) { CustomBlocksMod.LOGGER.debug("[CB] destroyTexture {}: {}", texId, e.getMessage()); }
            tm.registerTexture(texId, tex);
            TexInfo info = new TexInfo(texId, w, h);
            CACHE.put(customId, info);
            HASH_CACHE.put(customId, crc32(textureBytes));
            return info;
        } catch (Exception e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Failed to load GUI texture for '{}': {}", customId, e.getMessage());
            return getMissing();
        }
    }

    /**
     * D3: Invalidate only if {@code newBytes} differ from what is currently cached.
     * Returns {@code true} if invalidated, {@code false} if the content was unchanged (skip).
     */
    public static boolean invalidateIfChanged(String customId, byte[] newBytes) {
        if (newBytes != null && newBytes.length > 0) {
            Long cachedHash = HASH_CACHE.get(customId);
            if (cachedHash != null && cachedHash == crc32(newBytes)) {
                return false;
            }
        }
        invalidate(customId);
        return true;
    }

    public static void invalidate(String customId) {
        HASH_CACHE.remove(customId);
        NativeImage decoded = DECODED.remove(customId);
        if (decoded != null) decoded.close();
        TexInfo old = CACHE.remove(customId);
        if (old != null) {
            try { MinecraftClient.getInstance().getTextureManager().destroyTexture(old.id()); }
            catch (Exception ignored) {}
        }
    }

    public static void invalidateAll() {
        HASH_CACHE.clear();
        DECODED.values().forEach(NativeImage::close);
        DECODED.clear();
        var tm = MinecraftClient.getInstance().getTextureManager();
        for (TexInfo info : CACHE.values()) {
            try { tm.destroyTexture(info.id()); } catch (Exception e) { CustomBlocksMod.LOGGER.debug("[CB] destroyTexture {}: {}", info.id(), e.getMessage()); }
        }
        CACHE.clear();
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private static TexInfo getMissing() {
        return new TexInfo(Identifier.of("minecraft", "textures/misc/unknown_pack.png"), 64, 64);
    }
}
