package com.customblocks.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable snapshot of a single custom-block slot.
 * <p>
 * All mutation is done via {@code with*()} copy methods that return a new instance.
 * This makes the type inherently thread-safe and ideal for undo snapshots —
 * a snapshot is just a reference to an old {@code SlotData}.
 */
public final class SlotData {

    // ── Fields (all effectively final after construction) ─────────────────────
    public final int index;
    public final String customId;
    public final String displayName;
    public final byte[] texture;          // may be null
    public final int lightLevel;
    public final float hardness;
    public final String soundType;
    public final Map<String, byte[]> faceTextures;   // unmodifiable view
    public final String animMeta;         // null if not animated
    public final List<ShapeBox> shapeBoxes;           // null = full cube
    public final boolean noCollision;

    // ── Shape box record ─────────────────────────────────────────────────────
    public record ShapeBox(float x1, float y1, float z1, float x2, float y2, float z2) {
        public static ShapeBox parse(String csv) {
            String[] p = csv.split(",");
            if (p.length != 6) throw new IllegalArgumentException("Expected 6 comma-separated floats: " + csv);
            return new ShapeBox(
                    Float.parseFloat(p[0].trim()), Float.parseFloat(p[1].trim()), Float.parseFloat(p[2].trim()),
                    Float.parseFloat(p[3].trim()), Float.parseFloat(p[4].trim()), Float.parseFloat(p[5].trim()));
        }

        public String toDisplayString() {
            return String.format("%.1f,%.1f,%.1f → %.1f,%.1f,%.1f", x1, y1, z1, x2, y2, z2);
        }

        public String toSerialString() {
            return String.format("%.1f,%.1f,%.1f,%.1f,%.1f,%.1f", x1, y1, z1, x2, y2, z2);
        }
    }

    // ── Face keys constant ───────────────────────────────────────────────────
    public static final Set<String> FACE_KEYS = Set.of("top", "bottom", "north", "south", "east", "west");

    // ── Constructor ──────────────────────────────────────────────────────────

    public SlotData(int index, String customId, String displayName, byte[] texture,
                    int lightLevel, float hardness, String soundType,
                    Map<String, byte[]> faceTextures, String animMeta,
                    List<ShapeBox> shapeBoxes, boolean noCollision) {
        this.index        = index;
        this.customId     = customId;
        this.displayName  = displayName;
        this.texture      = texture != null ? texture.clone() : null;
        this.lightLevel   = lightLevel;
        this.hardness     = hardness;
        this.soundType    = soundType != null ? soundType : "stone";
        this.animMeta     = animMeta;
        this.noCollision  = noCollision;

        // Deep-copy face textures
        if (faceTextures != null && !faceTextures.isEmpty()) {
            Map<String, byte[]> copy = new ConcurrentHashMap<>();
            faceTextures.forEach((k, v) -> copy.put(k, v.clone()));
            this.faceTextures = Collections.unmodifiableMap(copy);
        } else {
            this.faceTextures = Collections.emptyMap();
        }

        // Deep-copy shape boxes
        this.shapeBoxes = shapeBoxes != null ? List.copyOf(shapeBoxes) : null;
    }

    /** Minimal constructor for fresh assignment. */
    public SlotData(int index, String customId, String displayName, byte[] texture) {
        this(index, customId, displayName, texture, 0, 1.5f, "stone",
                null, null, null, false);
    }

    // ── Query helpers ────────────────────────────────────────────────────────

    public boolean isAnimated() { return animMeta != null && !animMeta.isEmpty(); }
    public boolean hasFaces()   { return !faceTextures.isEmpty(); }
    public boolean isShaped()   { return shapeBoxes != null && !shapeBoxes.isEmpty(); }

    public String shapeLabel() {
        if (shapeBoxes == null || shapeBoxes.isEmpty()) return "Full Cube";
        return shapeBoxes.size() + " box" + (shapeBoxes.size() == 1 ? "" : "es");
    }

    public String slotKey() { return "slot_" + index; }

    // ── Copy-with methods (return new immutable instance) ────────────────────

    public SlotData withDisplayName(String name) {
        return new SlotData(index, customId, name, texture, lightLevel, hardness,
                soundType, faceTextures, animMeta, shapeBoxes, noCollision);
    }

    public SlotData withCustomId(String newId) {
        return new SlotData(index, newId, displayName, texture, lightLevel, hardness,
                soundType, faceTextures, animMeta, shapeBoxes, noCollision);
    }

    public SlotData withTexture(byte[] tex) {
        return new SlotData(index, customId, displayName, tex, lightLevel, hardness,
                soundType, faceTextures, animMeta, shapeBoxes, noCollision);
    }

    public SlotData withLightLevel(int level) {
        return new SlotData(index, customId, displayName, texture, level, hardness,
                soundType, faceTextures, animMeta, shapeBoxes, noCollision);
    }

    public SlotData withHardness(float h) {
        return new SlotData(index, customId, displayName, texture, lightLevel, h,
                soundType, faceTextures, animMeta, shapeBoxes, noCollision);
    }

    public SlotData withSoundType(String sound) {
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness,
                sound, faceTextures, animMeta, shapeBoxes, noCollision);
    }

    public SlotData withAnimMeta(String meta) {
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness,
                soundType, faceTextures, meta, shapeBoxes, noCollision);
    }

    public SlotData withShapeBoxes(List<ShapeBox> boxes) {
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness,
                soundType, faceTextures, animMeta, boxes, noCollision);
    }

    public SlotData withNoCollision(boolean nc) {
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness,
                soundType, faceTextures, animMeta, shapeBoxes, nc);
    }

    public SlotData withFaceTexture(String face, byte[] tex) {
        Map<String, byte[]> newFaces = new ConcurrentHashMap<>(faceTextures);
        newFaces.put(face, tex.clone());
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness,
                soundType, newFaces, animMeta, shapeBoxes, noCollision);
    }

    public SlotData withoutFaceTexture(String face) {
        Map<String, byte[]> newFaces = new ConcurrentHashMap<>(faceTextures);
        newFaces.remove(face);
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness,
                soundType, newFaces, animMeta, shapeBoxes, noCollision);
    }

    public SlotData withClearedFaces() {
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness,
                soundType, Collections.emptyMap(), animMeta, shapeBoxes, noCollision);
    }

    public SlotData withIndex(int newIndex) {
        return new SlotData(newIndex, customId, displayName, texture, lightLevel, hardness,
                soundType, faceTextures, animMeta, shapeBoxes, noCollision);
    }

    public SlotData withProperties(int light, float hard, String sound) {
        return new SlotData(index, customId, displayName, texture, light, hard,
                sound, faceTextures, animMeta, shapeBoxes, noCollision);
    }

    /** Full deep copy — same as constructing from all fields. */
    public SlotData deepCopy() {
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness,
                soundType, faceTextures, animMeta, shapeBoxes, noCollision);
    }

    @Override
    public String toString() {
        return "SlotData{" + customId + " #" + index + " '" + displayName + "'}";
    }
}
