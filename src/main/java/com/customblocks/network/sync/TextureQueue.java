package com.customblocks.network.sync;

import com.customblocks.network.ChunkedTexturePayload;
import com.customblocks.network.SlotUpdatePayload;
import net.minecraft.network.packet.CustomPayload;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Thread-safe, deduplicated queue for outbound texture / update payloads.
 * <p>
 * If a slot is queued multiple times before being sent, only the latest
 * payload is kept. This prevents the queue from growing unboundedly during
 * rapid edits.
 */
public final class TextureQueue {

    /** Ordered queue of payloads waiting to be sent (SlotUpdatePayload or ChunkedTexturePayload). */
    private final ConcurrentLinkedDeque<CustomPayload> queue = new ConcurrentLinkedDeque<>();

    /**
     * Tracks the latest payload per slot-action key to enable deduplication.
     * Key = "slotIndex:action:face" e.g. "42:retexture:", "42:setface:top"
     * <p>
     * The face component is included so that multiple per-face textures on the
     * same slot (e.g. top, north, south) are treated as separate entries and
     * not incorrectly collapsed into a single payload (which would silently
     * discard all but the last face texture).
     */
    private final ConcurrentHashMap<String, SlotUpdatePayload> latest = new ConcurrentHashMap<>();

    /**
     * Enqueue a payload for broadcast. If a payload with the same slot+action+face
     * is already queued, it is replaced (deduplicated).
     */
    public void enqueue(SlotUpdatePayload payload) {
        String key = dedupeKey(payload);
        SlotUpdatePayload old = latest.put(key, payload);
        if (old != null) {
            queue.remove(old);  // remove the stale entry
        }
        queue.addLast(payload);
    }

    /**
     * Enqueue a chunk payload. Chunks are NEVER deduplicated — each chunk
     * is a unique piece of a larger transfer.
     */
    public void enqueueChunk(ChunkedTexturePayload chunk) {
        queue.addLast(chunk);
    }

    /**
     * Drain up to {@code maxCount} payloads from the queue.
     * Returns them in FIFO order.
     */
    public CustomPayload[] drain(int maxCount) {
        int count = Math.min(maxCount, queue.size());
        if (count <= 0) return new CustomPayload[0];

        CustomPayload[] result = new CustomPayload[count];
        for (int i = 0; i < count; i++) {
            CustomPayload p = queue.pollFirst();
            if (p == null) {
                CustomPayload[] trimmed = new CustomPayload[i];
                System.arraycopy(result, 0, trimmed, 0, i);
                return trimmed;
            }
            result[i] = p;
            if (p instanceof SlotUpdatePayload sup) {
                latest.remove(dedupeKey(sup));
            }
        }
        return result;
    }

    /**
     * Push a payload back to the FRONT of the queue. Used when the
     * bytes-per-tick budget is exceeded — the payload will be sent next tick.
     */
    public void requeueFront(CustomPayload payload) {
        queue.addFirst(payload);
    }

    /** Check if the queue is empty. */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /** Number of pending payloads. */
    public int size() {
        return queue.size();
    }

    /** Clear all pending payloads. */
    public void clear() {
        queue.clear();
        latest.clear();
    }

    /**
     * Extract the byte size of a payload for budget tracking.
     * Works for both {@link SlotUpdatePayload} and {@link ChunkedTexturePayload}.
     */
    public static int payloadByteSize(CustomPayload payload) {
        if (payload instanceof SlotUpdatePayload sup) {
            return sup.texture() != null ? sup.texture().length : 0;
        } else if (payload instanceof ChunkedTexturePayload chunk) {
            return chunk.chunkData() != null ? chunk.chunkData().length : 0;
        }
        return 0;
    }

    /**
     * Build a deduplication key that uniquely identifies a payload by its
     * slot index, action, and (for setface/clearface) the target face name.
     * <p>
     * Previously the key omitted the face component, which caused all
     * per-face payloads for the same slot to share a key. Enqueuing
     * "top", "north", and "south" for slot 42 would collapse to only the
     * last one enqueued — silently dropping the other faces.
     */
    private static String dedupeKey(SlotUpdatePayload p) {
        return p.slotIndex() + ":" + p.action() + ":" + (p.face() != null ? p.face() : "");
    }
}
