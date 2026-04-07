// 
// Decompiled by Procyon v0.6.0
// 

package com.customblocks.network;

import net.minecraft.class_2960;
import java.util.Iterator;
import java.util.ArrayList;
import net.minecraft.class_2540;
import net.minecraft.class_9139;
import java.util.List;
import net.minecraft.class_8710;

record FullSyncPayload(List<SlotEntry> entries, byte[] tabIconTexture) implements class_8710 {
    public static final class_8710.class_9154<FullSyncPayload> ID;
    public static final class_9139<class_2540, FullSyncPayload> CODEC;
    
    public class_8710.class_9154<? extends class_8710> method_56479() {
        return (class_8710.class_9154<? extends class_8710>)FullSyncPayload.ID;
    }
    
    static {
        ID = new class_8710.class_9154(class_2960.method_60655("customblocks", "full_sync"));
        CODEC = class_9139.method_56438((value, buf) -> {
            buf.method_10804(value.entries().size());
            for (final SlotEntry e : value.entries()) {
                buf.method_10804(e.index());
                buf.method_10814(e.customId());
                buf.method_10814(e.displayName());
                buf.method_10813((e.texture() != null) ? e.texture() : new byte[0]);
                buf.method_10804(e.lightLevel());
                buf.method_52941(e.hardness());
                buf.method_10814((e.soundType() != null) ? e.soundType() : "stone");
            }
            buf.method_10813((value.tabIconTexture() != null) ? value.tabIconTexture() : new byte[0]);
        }, buf -> {
            final int size = buf.method_10816();
            final List<SlotEntry> entries = new ArrayList<SlotEntry>();
            for (int i = 0; i < size; ++i) {
                final int index = buf.method_10816();
                final String id = buf.method_19772();
                final String name = buf.method_19772();
                final byte[] tex = buf.method_10803(10485760);
                final int lightLevel = buf.method_10816();
                final float hardness = buf.readFloat();
                final String soundType = buf.method_19772();
                entries.add(new SlotEntry(index, id, name, (byte[])((tex.length > 0) ? tex : null), lightLevel, hardness, soundType));
            }
            final byte[] tabIcon = buf.method_10803(10485760);
            if (buf.readableBytes() > 0) {
                buf.method_52994(buf.readableBytes());
            }
            return new FullSyncPayload(entries, (byte[])((tabIcon.length > 0) ? tabIcon : null));
        });
    }
    
    record SlotEntry(int index, String customId, String displayName, byte[] texture, int lightLevel, float hardness, String soundType) {}
}
