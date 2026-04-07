// 
// Decompiled by Procyon v0.6.0
// 

package com.customblocks.item;

import java.util.Iterator;
import net.minecraft.class_2248;
import net.minecraft.class_2680;
import net.minecraft.class_1657;
import net.minecraft.class_2338;
import net.minecraft.class_1937;
import com.customblocks.block.UndoHistory;
import com.customblocks.CustomBlocksMod;
import net.minecraft.class_3222;
import com.customblocks.SlotManager;
import com.customblocks.block.SlotBlock;
import net.minecraft.class_1269;
import net.minecraft.class_1838;
import net.minecraft.class_1799;
import net.minecraft.class_2561;
import net.minecraft.class_1792;

public class ColorSquareItem extends class_1792
{
    private static final String[] COLORS;
    private final String colorToken;
    private final String colorName;
    
    public ColorSquareItem(final String colorToken, final String colorName, final class_1792.class_1793 settings) {
        super(settings);
        this.colorToken = colorToken;
        this.colorName = colorName;
    }
    
    public class_2561 method_7848() {
        return (class_2561)class_2561.method_43470(this.colorName + " Square");
    }
    
    public class_2561 method_7864(final class_1799 stack) {
        return this.method_7848();
    }
    
    public class_1269 method_7884(final class_1838 ctx) {
        final class_1937 world = ctx.method_8045();
        final class_2338 pos = ctx.method_8037();
        final class_1657 player = ctx.method_8036();
        if (world.field_9236) {
            return class_1269.field_5811;
        }
        final class_2680 state = world.method_8320(pos);
        final class_2248 method_26204 = state.method_26204();
        if (!(method_26204 instanceof SlotBlock)) {
            return class_1269.field_5811;
        }
        final SlotBlock sb = (SlotBlock)method_26204;
        if (player != null && !player.method_5687(2)) {
            player.method_7353((class_2561)class_2561.method_43470("§c[CustomBlocks] You need OP (level 2) to use color squares."), true);
            return class_1269.field_5814;
        }
        final SlotManager.SlotData current = SlotManager.getBySlot(sb.getSlotKey());
        if (current == null) {
            return class_1269.field_5811;
        }
        final String currentId = current.customId;
        final String currentColor = colorOf(currentId);
        if (this.colorToken.equals(currentColor)) {
            if (player != null) {
                player.method_7353((class_2561)class_2561.method_43470("§7[CustomBlocks] Already " + this.colorName), true);
            }
            return class_1269.field_5812;
        }
        // Try to find the target color variant
        final SlotManager.SlotData target = findColorVariant(currentId, currentColor, this.colorToken);
        if (target == null) {
            if (player != null) {
                // Show what we tried to find
                String attemptedId = (currentColor != null) 
                    ? replaceColor(currentId, currentColor, this.colorToken)
                    : this.colorToken + "_" + currentId;
                player.method_7353((class_2561)class_2561.method_43470("§c[CustomBlocks] '" + attemptedId + "' doesn't exist. Create it first or check available variants."), true);
            }
            return class_1269.field_5814;
        }
        if (player instanceof final class_3222 sp) {
            UndoHistory.push(sp, pos, sb.getSlotKey(), CustomBlocksMod.SLOT_BLOCKS[target.index].getSlotKey());
        }
        world.method_8652(pos, CustomBlocksMod.SLOT_BLOCKS[target.index].method_9564(), 3);
        if (player != null) {
            player.method_7353((class_2561)class_2561.method_43470("§a[CustomBlocks] Swapped to §f" + target.displayName + "§a!"), true);
        }
        return class_1269.field_5812;
    }
    
    private static SlotManager.SlotData findColorVariant(final String currentId, final String currentColor, final String newColor) {
        // First try: direct replacement of current color with new color
        if (currentColor != null) {
            final String candidate = replaceColor(currentId, currentColor, newColor);
            final SlotManager.SlotData d = SlotManager.getById(candidate);
            if (d != null) {
                return d;
            }
        }
        
        // Second try: find by stripping current color and adding new color
        final String currentBase = (currentColor != null) ? stripColor(currentId, currentColor) : currentId;
        
        // Look for any slot that matches the base with the new color
        for (final SlotManager.SlotData d2 : SlotManager.allSlots()) {
            final String otherColor = colorOf(d2.customId);
            if (newColor.equals(otherColor)) {
                // Check if this slot's base matches our base
                final String otherBase = (otherColor != null) ? stripColor(d2.customId, otherColor) : d2.customId;
                if (otherBase.equals(currentBase) || otherBase.equalsIgnoreCase(currentBase)) {
                    return d2;
                }
            }
        }
        
        // Third try: look for prefix/suffix patterns
        // Try: newColor + "_" + strippedBase
        if (!currentBase.isEmpty()) {
            SlotManager.SlotData d = SlotManager.getById(newColor + "_" + currentBase);
            if (d != null) return d;
            
            // Try: strippedBase + "_" + newColor  
            d = SlotManager.getById(currentBase + "_" + newColor);
            if (d != null) return d;
        }
        
        // Fourth try: fuzzy match - any block with newColor in its name that seems related
        for (final SlotManager.SlotData d3 : SlotManager.allSlots()) {
            if (d3.customId.contains(newColor)) {
                // Check if the non-color parts have some similarity
                String otherNonColor = stripAllColors(d3.customId);
                String currentNonColor = stripAllColors(currentId);
                if (!otherNonColor.isEmpty() && !currentNonColor.isEmpty() && 
                    (otherNonColor.equals(currentNonColor) || 
                     otherNonColor.contains(currentNonColor) || 
                     currentNonColor.contains(otherNonColor))) {
                    return d3;
                }
            }
        }
        
        return null;
    }
    
    // Strip all known colors from an ID to get the base
    private static String stripAllColors(String id) {
        String result = id;
        for (String color : COLORS) {
            result = stripColor(result, color);
        }
        // Clean up
        result = result.replaceAll("_+", "_").replaceAll("^_", "").replaceAll("_$", "");
        return result;
    }
    
    public static String colorOf(final String id) {
        final String[] colors = ColorSquareItem.COLORS;
        for (int length = colors.length, i = 0; i < length; ++i) {
            final String c = colors[i];
            if (id.equals(c)) {
                return c;
            }
            if (id.startsWith(c)) {
                return c;
            }
            if (id.endsWith("_" + c)) {
                return c;
            }
            if (id.contains("_" + c)) {
                return c;
            }
        }
        return null;
    }
    
    private static String stripColor(final String id, final String color) {
        if (id.equals(color)) {
            return "";
        }
        if (id.startsWith(color)) {
            return id.substring(color.length() + 1);
        }
        if (id.endsWith("_" + color)) {
            return id.substring(0, id.length() - color.length() - 1);
        }
        final String mid = "_" + color;
        final int idx = id.indexOf(mid);
        if (idx >= 0) {
            return id.substring(0, idx) + id.substring(idx + color.length() + 1);
        }
        return id;
    }
    
    public static String replaceColor(final String id, final String oldColor, final String newColor) {
        if (id.equals(oldColor)) {
            return newColor;
        }
        if (id.startsWith(oldColor)) {
            return newColor + id.substring(oldColor.length());
        }
        if (id.endsWith("_" + oldColor)) {
            return id.substring(0, id.length() - oldColor.length()) + newColor;
        }
        final String mid = "_" + oldColor;
        final int idx = id.indexOf(mid);
        if (idx >= 0) {
            return id.substring(0, idx + 1) + newColor + id.substring(idx + 1 + oldColor.length());
        }
        return id;
    }
    
    static {
        COLORS = new String[] { "black", "yellow", "green", "white", "red", "blue", "purple", "orange", "pink", "gray" };
    }
}
