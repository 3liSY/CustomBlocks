// 
// Decompiled by Procyon v0.6.0
// 

package com.customblocks.client.texture;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Iterator;
import net.minecraft.class_1060;
import com.customblocks.CustomBlocksMod;
import net.minecraft.class_1044;
import net.minecraft.class_310;
import net.minecraft.class_2960;
import net.minecraft.class_1043;
import java.io.InputStream;
import net.minecraft.class_1011;
import java.io.ByteArrayInputStream;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public class TextureCache
{
    private static final Map<String, TexInfo> CACHE;
    
    public static TexInfo getOrLoad(final String customId, final byte[] textureBytes) {
        if (TextureCache.CACHE.containsKey(customId)) {
            return TextureCache.CACHE.get(customId);
        }
        if (textureBytes == null || textureBytes.length == 0) {
            return getMissing();
        }
        try {
            final class_1011 image = class_1011.method_4309((InputStream)new ByteArrayInputStream(textureBytes));
            final int w = image.method_4307();
            final int h = image.method_4323();
            final class_1043 tex = new class_1043(image);
            final class_2960 texId = class_2960.method_60655("customblocks", "dynamic/" + customId);
            final class_1060 tm = class_310.method_1551().method_1531();
            try {
                tm.method_4615(texId);
            }
            catch (final Exception ex) {}
            tm.method_4616(texId, (class_1044)tex);
            tex.method_23207();
            final TexInfo info = new TexInfo(texId, w, h);
            TextureCache.CACHE.put(customId, info);
            return info;
        }
        catch (final Exception e) {
            CustomBlocksMod.LOGGER.error("[CustomBlocks] Failed to load GUI texture for '{}': {}", (Object)customId, (Object)e.getMessage());
            return getMissing();
        }
    }
    
    public static void invalidate(final String customId) {
        final TexInfo old = TextureCache.CACHE.remove(customId);
        if (old != null) {
            try {
                class_310.method_1551().method_1531().method_4615(old.id());
            }
            catch (final Exception ex) {}
        }
    }
    
    public static void invalidateAll() {
        final class_1060 tm = class_310.method_1551().method_1531();
        for (final TexInfo info : TextureCache.CACHE.values()) {
            try {
                tm.method_4615(info.id());
            }
            catch (final Exception ex) {}
        }
        TextureCache.CACHE.clear();
    }
    
    public static int cacheSize() {
        return TextureCache.CACHE.size();
    }
    
    private static TexInfo getMissing() {
        return new TexInfo(class_2960.method_60655("minecraft", "textures/misc/unknown_pack.png"), 64, 64);
    }
    
    static {
        CACHE = new ConcurrentHashMap<String, TexInfo>();
    }
    
    record TexInfo(class_2960 id, int width, int height) {}
}
