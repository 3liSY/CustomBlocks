// 
// Decompiled by Procyon v0.6.0
// 

package com.customblocks.block;

import net.minecraft.class_1799;
import net.minecraft.class_1792;
import net.minecraft.class_1747;
import net.minecraft.class_2338;
import net.minecraft.class_1922;
import net.minecraft.class_1657;
import net.minecraft.class_2498;
import net.minecraft.class_2680;
import net.minecraft.class_2561;
import com.customblocks.SlotManager;
import net.minecraft.class_5250;
import net.minecraft.class_4970;
import net.minecraft.class_2248;

public class SlotBlock extends class_2248
{
    private final int slotIndex;
    
    public SlotBlock(final int slotIndex, final class_4970.class_2251 settings) {
        super(settings);
        this.slotIndex = slotIndex;
    }
    
    public int getSlotIndex() {
        return this.slotIndex;
    }
    
    public String getSlotKey() {
        return "slot_" + this.slotIndex;
    }
    
    public class_5250 method_9518() {
        final String name = SlotManager.getDisplayName(this.getSlotKey());
        return class_2561.method_43470((name != null) ? name : ("Custom Block " + this.slotIndex));
    }
    
    public class_2498 method_9573(final class_2680 state) {
        final SlotManager.SlotData d = SlotManager.getBySlot(this.getSlotKey());
        if (d == null) {
            return class_2498.field_11544;
        }
        final String soundType = d.soundType;
        return switch (soundType) {
            case "wood" -> class_2498.field_11547;
            case "grass" -> class_2498.field_11535;
            case "metal" -> class_2498.field_11533;
            case "glass" -> class_2498.field_11537;
            case "sand" -> class_2498.field_11526;
            case "wool" -> class_2498.field_11543;
            default -> class_2498.field_11544;
        };
    }
    
    public float method_9594(final class_2680 state, final class_1657 player, final class_1922 world, final class_2338 pos) {
        final SlotManager.SlotData d = SlotManager.getBySlot(this.getSlotKey());
        final float hardness = (d != null) ? d.hardness : 1.5f;
        if (hardness < 0.0f) {
            return 0.0f;
        }
        if (hardness == 0.0f) {
            return 1.0f;
        }
        final float speed = player.method_7351(state);
        final boolean correctTool = speed > 1.0f;
        return correctTool ? (speed / hardness / 30.0f) : (1.0f / hardness / 100.0f);
    }
    
    public static class SlotItem extends class_1747
    {
        private final int slotIndex;
        
        public SlotItem(final SlotBlock block, final class_1792.class_1793 settings) {
            super((class_2248)block, settings);
            this.slotIndex = block.getSlotIndex();
        }
        
        private String getSlotKey() {
            return "slot_" + this.slotIndex;
        }
        
        public class_2561 method_7848() {
            final String name = SlotManager.getDisplayName(this.getSlotKey());
            return (class_2561)class_2561.method_43470((name != null) ? name : "Custom Block");
        }
        
        public class_2561 method_7864(final class_1799 stack) {
            return this.method_7848();
        }
    }
}
