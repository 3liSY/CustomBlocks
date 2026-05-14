package com.customblocks.core;

import com.customblocks.CustomBlocksMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Phase H₁ — /cb history. Records a rolling log of the last 200 block mutations
 * per server session (who did what, when). Cleared on restart.
 */
public final class HistoryTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks/History");
    private static final int MAX_ENTRIES = 200;

    public record Entry(Instant when, UUID actorId, String actorName,
                        String action, String blockId, String detail) {
        public String toDisplayString() {
            String time = when.toString().substring(11, 19); // HH:mm:ss
            return "§7[" + time + "] §f" + actorName + " §7" + action + " §e" + blockId
                    + (detail != null && !detail.isEmpty() ? " §8(" + detail + ")" : "");
        }
    }

    private static final Deque<Entry> LOG = new ConcurrentLinkedDeque<>();

    private HistoryTracker() {}

    /** Record a mutation. */
    public static void record(UUID actorId, String actorName, String action, String blockId, String detail) {
        LOG.addFirst(new Entry(Instant.now(), actorId, actorName, action, blockId, detail));
        while (LOG.size() > MAX_ENTRIES) LOG.removeLast();
    }

    /** Convenience — record with no extra detail. */
    public static void record(UUID actorId, String actorName, String action, String blockId) {
        record(actorId, actorName, action, blockId, null);
    }

    /** Get latest entries (newest first), up to {@code limit}. */
    public static List<Entry> latest(int limit) {
        List<Entry> result = new ArrayList<>();
        int count = 0;
        for (Entry e : LOG) {
            if (count++ >= limit) break;
            result.add(e);
        }
        return result;
    }

    /** Total entries in the log. */
    public static int size() { return LOG.size(); }

    /** Clear the session log. */
    public static void clear() { LOG.clear(); }
}
