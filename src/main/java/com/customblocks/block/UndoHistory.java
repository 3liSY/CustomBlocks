// 
// Decompiled by Procyon v0.6.0
// 

package com.customblocks.block;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.class_1937;
import com.customblocks.CustomBlocksMod;
import java.util.ArrayDeque;
import net.minecraft.class_2338;
import net.minecraft.class_3222;
import java.util.Deque;
import java.util.UUID;
import java.util.Map;

public class UndoHistory
{
    private static final int MAX = 50;
    private static final Map<UUID, Deque<Entry>> UNDO;
    private static final Map<UUID, Deque<Entry>> REDO;
    
    public static void push(final class_3222 player, final class_2338 pos, final String fromSlot, final String toSlot) {
        final UUID id = player.method_5667();
        final Deque<Entry> undo = UndoHistory.UNDO.computeIfAbsent(id, k -> new ArrayDeque());
        undo.push(new Entry(pos, fromSlot, toSlot));
        if (undo.size() > 50) {
            undo.removeLast();
        }
        UndoHistory.REDO.computeIfAbsent(id, k -> new ArrayDeque()).clear();
    }
    
    public static boolean undo(final class_3222 player) {
        final Deque<Entry> undo = UndoHistory.UNDO.getOrDefault(player.method_5667(), new ArrayDeque<Entry>());
        if (undo.isEmpty()) {
            return false;
        }
        final Entry e = undo.pop();
        apply(player, e.pos(), e.fromSlot());
        UndoHistory.REDO.computeIfAbsent(player.method_5667(), k -> new ArrayDeque()).push(e);
        return true;
    }
    
    public static boolean redo(final class_3222 player) {
        final Deque<Entry> redo = UndoHistory.REDO.getOrDefault(player.method_5667(), new ArrayDeque<Entry>());
        if (redo.isEmpty()) {
            return false;
        }
        final Entry e = redo.pop();
        apply(player, e.pos(), e.toSlot());
        UndoHistory.UNDO.computeIfAbsent(player.method_5667(), k -> new ArrayDeque()).push(e);
        return true;
    }
    
    private static void apply(final class_3222 player, final class_2338 pos, final String slotKey) {
        final class_1937 world = player.method_37908();
        if (world == null) {
            return;
        }
        for (final SlotBlock block : CustomBlocksMod.SLOT_BLOCKS) {
            if (block != null && block.getSlotKey().equals(slotKey)) {
                world.method_8652(pos, block.method_9564(), 3);
                return;
            }
        }
    }
    
    static {
        UNDO = new ConcurrentHashMap<UUID, Deque<Entry>>();
        REDO = new ConcurrentHashMap<UUID, Deque<Entry>>();
    }
    
    record Entry(class_2338 pos, String fromSlot, String toSlot) {}
}
