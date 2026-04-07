// 
// Decompiled by Procyon v0.6.0
// 

package com.customblocks.network;

import net.minecraft.class_2960;
import net.minecraft.class_2540;
import net.minecraft.class_9139;
import net.minecraft.class_8710;

record SlotUpdatePayload(String action, int slotIndex, String customId, String displayName, byte[] texture, int lightLevel, float hardness, String soundType) implements class_8710 {
    public static final class_8710.class_9154<SlotUpdatePayload> ID;
    public static final class_9139<class_2540, SlotUpdatePayload> CODEC;
    
    public class_8710.class_9154<? extends class_8710> method_56479() {
        return (class_8710.class_9154<? extends class_8710>)SlotUpdatePayload.ID;
    }
    
    static {
        ID = new class_8710.class_9154(class_2960.method_60655("customblocks", "slot_update"));
        CODEC = class_9139.method_56438((value, buf) -> {
            buf.method_10814(value.action());
            buf.method_10804(value.slotIndex());
            buf.method_10814((value.customId() != null) ? value.customId() : "");
            buf.method_10814((value.displayName() != null) ? value.displayName() : "");
            buf.method_10813((value.texture() != null) ? value.texture() : new byte[0]);
            buf.method_10804(value.lightLevel());
            buf.method_52941(value.hardness());
            buf.method_10814((value.soundType() != null) ? value.soundType() : "stone");
        }, buf -> {
            final String action = buf.method_19772();
            final int index = buf.method_10816();
            final String id = buf.method_19772();
            final String name = buf.method_19772();
            final byte[] tex = buf.method_10803(10485760);
            final int lightLevel = buf.method_10816();
            final float hardness = buf.readFloat();
            final String soundType = buf.method_19772();
            if (buf.readableBytes() > 0) {
                buf.method_52994(buf.readableBytes());
            }
            return new SlotUpdatePayload(action, index, id.isEmpty() ? null : id, name.isEmpty() ? null : name, (byte[])((tex.length > 0) ? tex : null), lightLevel, hardness, soundType.isEmpty() ? "stone" : soundType);
        });
    }
}
