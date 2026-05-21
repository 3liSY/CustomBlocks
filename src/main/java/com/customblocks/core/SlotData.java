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
    /** H4 — additional texture variants for random blockstate selection. Never null; may be empty. */
    public final List<byte[]> variantTextures;
    /** I2 — per-block hologram label override. null = use displayName. Empty = no hologram for this block. */
    public final String hologramText;
    /** 1.27 — epoch-ms of the last mutation; 0 for blocks loaded from before this field existed. */
    public final long lastEditedAt;
    /** Phase 4A.1 — background mode for image import: "keep_transparent", "remove_auto", "fill_black", "fill_color". */
    public final String importBgMode;
    /** Phase 4A.1 — fringe removal aggressiveness: "off", "light", "normal", "aggressive". */
    public final String importFringe;
    /** Phase 4A.7 — target import size in pixels (64, 128, 256). 0 = use server default. */
    public final int importSize;
    public final transient boolean isBroken;
    public final transient String displayNameLower;

    // ── 1.23 Texture reason enum ─────────────────────────────────────────────
    /** Describes why a block's texture is unavailable, for the admin broken-blocks view. */
    public enum TextureReason {
        /** No texture has ever been uploaded for this slot. */
        NEVER_UPLOADED("No texture has been uploaded for this block yet."),
        /** A texture was previously saved to disk but the file is now missing. */
        FILE_MISSING("Texture file was lost — please re-upload."),
        /** The texture file exists but the PNG data is corrupted or too small to use. */
        CORRUPTED("Texture file is corrupted — please re-upload.");

        public final String tooltip;
        TextureReason(String tooltip) { this.tooltip = tooltip; }
    }

    // ── Shape box record ─────────────────────────────────────────────────────
    public record ShapeBox(float x1, float y1, float z1, float x2, float y2, float z2) {
        public static ShapeBox parse(String input) {
            String[] p = input.trim().split("[,\\s]+");
            if (p.length != 6) throw new IllegalArgumentException("Expected 6 values (x1 y1 z1 x2 y2 z2): " + input);
            float x1 = clamp(Float.parseFloat(p[0].trim()));
            float y1 = clamp(Float.parseFloat(p[1].trim()));
            float z1 = clamp(Float.parseFloat(p[2].trim()));
            float x2 = clamp(Float.parseFloat(p[3].trim()));
            float y2 = clamp(Float.parseFloat(p[4].trim()));
            float z2 = clamp(Float.parseFloat(p[5].trim()));
            return new ShapeBox(
                    Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                    Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
        }

        private static float clamp(float v) { return Math.max(0f, Math.min(16f, v)); }

        public String toDisplayString() {
            return String.format("%.1f,%.1f,%.1f → %.1f,%.1f,%.1f", x1, y1, z1, x2, y2, z2);
        }

        public String toSerialString() {
            return String.format("%.1f,%.1f,%.1f,%.1f,%.1f,%.1f", x1, y1, z1, x2, y2, z2);
        }
    }

    // ── Face keys constant ───────────────────────────────────────────────────
    public static final Set<String> FACE_KEYS = Set.of("top", "bottom", "north", "south", "east", "west");

    // ── Constructors ─────────────────────────────────────────────────────────

    public SlotData(int index, String customId, String displayName, byte[] texture,
                    int lightLevel, float hardness, String soundType,
                    Map<String, byte[]> faceTextures, String animMeta,
                    List<ShapeBox> shapeBoxes, boolean noCollision) {
        this(index, customId, displayName, texture, lightLevel, hardness, soundType,
                faceTextures, animMeta, shapeBoxes, noCollision,
                texture != null && com.customblocks.ImageProcessor.isBrokenTexture(texture), null, null, 0L);
    }

    /** Internal constructor — accepts precomputed isBroken, optional variantTextures, hologramText, and lastEditedAt. */
    SlotData(int index, String customId, String displayName, byte[] texture,
             int lightLevel, float hardness, String soundType,
             Map<String, byte[]> faceTextures, String animMeta,
             List<ShapeBox> shapeBoxes, boolean noCollision, boolean precomputedBroken,
             List<byte[]> variantTextures, String hologramText, long lastEditedAt) {
        this(index, customId, displayName, texture, lightLevel, hardness, soundType,
             faceTextures, animMeta, shapeBoxes, noCollision, precomputedBroken,
             variantTextures, hologramText, lastEditedAt, "keep_transparent", "normal", 0);
    }

    /** Full internal constructor — all fields including Phase 4A import settings. */
    SlotData(int index, String customId, String displayName, byte[] texture,
             int lightLevel, float hardness, String soundType,
             Map<String, byte[]> faceTextures, String animMeta,
             List<ShapeBox> shapeBoxes, boolean noCollision, boolean precomputedBroken,
             List<byte[]> variantTextures, String hologramText, long lastEditedAt,
             String importBgMode, String importFringe, int importSize) {
        this.index        = index;
        this.customId     = customId;
        this.displayName  = displayName;
        this.texture      = texture != null ? texture.clone() : null;
        this.lightLevel   = lightLevel;
        this.hardness     = hardness;
        this.soundType    = soundType != null ? soundType : "stone";
        this.animMeta     = animMeta;
        this.noCollision  = noCollision;
        this.hologramText = hologramText;
        this.lastEditedAt = lastEditedAt;
        this.importBgMode  = importBgMode  != null ? importBgMode  : "keep_transparent";
        this.importFringe  = importFringe  != null ? importFringe  : "normal";
        this.importSize    = importSize;

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

        // Deep-copy variant textures (H4)
        if (variantTextures != null && !variantTextures.isEmpty()) {
            List<byte[]> copy = new ArrayList<>(variantTextures.size());
            for (byte[] v : variantTextures) copy.add(v != null ? v.clone() : new byte[0]);
            this.variantTextures = Collections.unmodifiableList(copy);
        } else {
            this.variantTextures = List.of();
        }

        this.isBroken = precomputedBroken;
        this.displayNameLower = this.displayName != null ? this.displayName.toLowerCase() : "";
    }

    /** Minimal constructor for fresh assignment. */
    public SlotData(int index, String customId, String displayName, byte[] texture) {
        this(index, customId, displayName, texture, 0, 1.5f, "stone",
                null, null, null, false);
    }

    /** Trusted factory — skips isBrokenTexture() decode. Use for batch loads and drip-feed
     *  where texture bytes come from trusted storage, not user upload. */
    static SlotData createTrusted(int index, String customId, String displayName, byte[] texture) {
        return new SlotData(index, customId, displayName, texture, 0, 1.5f, "stone",
                null, null, null, false, false, null, null, 0L);
    }

    /** Trusted factory — full fields, skips isBrokenTexture(). For deserialization from JSON. */
    static SlotData createTrustedFull(int index, String customId, String displayName, byte[] texture,
                                      int lightLevel, float hardness, String soundType,
                                      Map<String, byte[]> faceTextures, String animMeta,
                                      List<ShapeBox> shapeBoxes, boolean noCollision) {
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness, soundType,
                faceTextures, animMeta, shapeBoxes, noCollision, false, null, null, 0L);
    }

    /** Trusted factory — full fields + variant textures, skips isBrokenTexture(). */
    static SlotData createTrustedFull(int index, String customId, String displayName, byte[] texture,
                                      int lightLevel, float hardness, String soundType,
                                      Map<String, byte[]> faceTextures, String animMeta,
                                      List<ShapeBox> shapeBoxes, boolean noCollision,
                                      List<byte[]> variantTextures) {
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness, soundType,
                faceTextures, animMeta, shapeBoxes, noCollision, false, variantTextures, null, 0L);
    }

    /** Trusted factory — full fields + variant textures + hologramText. */
    static SlotData createTrustedFull(int index, String customId, String displayName, byte[] texture,
                                      int lightLevel, float hardness, String soundType,
                                      Map<String, byte[]> faceTextures, String animMeta,
                                      List<ShapeBox> shapeBoxes, boolean noCollision,
                                      List<byte[]> variantTextures, String hologramText) {
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness, soundType,
                faceTextures, animMeta, shapeBoxes, noCollision, false, variantTextures, hologramText, 0L);
    }

    /** Trusted factory — full fields + variant textures + hologramText + lastEditedAt. For deserialization. */
    static SlotData createTrustedFull(int index, String customId, String displayName, byte[] texture,
                                      int lightLevel, float hardness, String soundType,
                                      Map<String, byte[]> faceTextures, String animMeta,
                                      List<ShapeBox> shapeBoxes, boolean noCollision,
                                      List<byte[]> variantTextures, String hologramText, long lastEditedAt) {
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness, soundType,
                faceTextures, animMeta, shapeBoxes, noCollision, false, variantTextures, hologramText, lastEditedAt);
    }

    // ── Query helpers ────────────────────────────────────────────────────────

    public boolean isAnimated() { return animMeta != null && !animMeta.isEmpty(); }
    public boolean hasFaces()   { return !faceTextures.isEmpty(); }
    public boolean isShaped()   { return shapeBoxes != null && !shapeBoxes.isEmpty(); }
    /** H4 — true when 1+ extra variant textures exist. */
    public boolean hasVariants()  { return !variantTextures.isEmpty(); }
    /** H4 — total number of visual variants (main + extras). */
    public int variantCount()     { return 1 + variantTextures.size(); }
    /** I2 — true when a custom hologram override is set for this block. */
    public boolean hasHologramText() { return hologramText != null && !hologramText.isBlank(); }

    public String shapeLabel() {
        if (shapeBoxes == null || shapeBoxes.isEmpty()) return "Full Cube";
        return shapeBoxes.size() + " box" + (shapeBoxes.size() == 1 ? "" : "es");
    }

    public String slotKey() { return "slot_" + index; }

    // ── Copy-with methods (return new immutable instance) ────────────────────

    public SlotData withDisplayName(String name) {
        return new SlotData(index, customId, name, texture, lightLevel, hardness,
                soundType, faceTextures, animMeta, shapeBoxes, noCollision, this.isBroken,
                this.variantTextures, this.hologramText, this.lastEditedAt,
                this.importBgMode, this.importFringe, this.importSize);
    }

    public SlotData withCustomId(String newId) {
        return new SlotData(index, newId, displayName, texture, lightLevel, hardness,
                soundType, faceTextures, animMeta, shapeBoxes, noCollision, this.isBroken,
                this.variantTextures, this.hologramText, this.lastEditedAt,
                this.importBgMode, this.importFringe, this.importSize);
    }

    public SlotData withTexture(byte[] tex) {
        boolean broken = tex != null && com.customblocks.ImageProcessor.isBrokenTexture(tex);
        return new SlotData(index, customId, displayName, tex, lightLevel, hardness,
                soundType, faceTextures, animMeta, shapeBoxes, noCollision, broken,
                this.variantTextures, this.hologramText, this.lastEditedAt,
                this.importBgMode, this.importFringe, this.importSize);
    }

    public SlotData withLightLevel(int level) {
        return new SlotData(index, customId, displayName, texture, level, hardness,
                soundType, faceTextures, animMeta, shapeBoxes, noCollision, this.isBroken,
                this.variantTextures, this.hologramText, this.lastEditedAt,
                this.importBgMode, this.importFringe, this.importSize);
    }

    public SlotData withHardness(float h) {
        return new SlotData(index, customId, displayName, texture, lightLevel, h,
                soundType, faceTextures, animMeta, shapeBoxes, noCollision, this.isBroken,
                this.variantTextures, this.hologramText, this.lastEditedAt,
                this.importBgMode, this.importFringe, this.importSize);
    }

    public SlotData withSoundType(String sound) {
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness,
                sound, faceTextures, animMeta, shapeBoxes, noCollision, this.isBroken,
                this.variantTextures, this.hologramText, this.lastEditedAt,
                this.importBgMode, this.importFringe, this.importSize);
    }

    public SlotData withAnimMeta(String meta) {
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness,
                soundType, faceTextures, meta, shapeBoxes, noCollision, this.isBroken,
                this.variantTextures, this.hologramText, this.lastEditedAt,
                this.importBgMode, this.importFringe, this.importSize);
    }

    public SlotData withShapeBoxes(List<ShapeBox> boxes) {
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness,
                soundType, faceTextures, animMeta, boxes, noCollision, this.isBroken,
                this.variantTextures, this.hologramText, this.lastEditedAt,
                this.importBgMode, this.importFringe, this.importSize);
    }

    public SlotData withNoCollision(boolean nc) {
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness,
                soundType, faceTextures, animMeta, shapeBoxes, nc, this.isBroken,
                this.variantTextures, this.hologramText, this.lastEditedAt,
                this.importBgMode, this.importFringe, this.importSize);
    }

    public SlotData withFaceTexture(String face, byte[] tex) {
        if (face == null || face.isEmpty() || tex == null || tex.length == 0) {
            return this;
        }
        Map<String, byte[]> newFaces = new ConcurrentHashMap<>(faceTextures);
        newFaces.put(face, tex.clone());
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness,
                soundType, newFaces, animMeta, shapeBoxes, noCollision, this.isBroken,
                this.variantTextures, this.hologramText, this.lastEditedAt,
                this.importBgMode, this.importFringe, this.importSize);
    }

    public SlotData withoutFaceTexture(String face) {
        Map<String, byte[]> newFaces = new ConcurrentHashMap<>(faceTextures);
        newFaces.remove(face);
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness,
                soundType, newFaces, animMeta, shapeBoxes, noCollision, this.isBroken,
                this.variantTextures, this.hologramText, this.lastEditedAt,
                this.importBgMode, this.importFringe, this.importSize);
    }

    public SlotData withClearedFaces() {
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness,
                soundType, Collections.emptyMap(), animMeta, shapeBoxes, noCollision, this.isBroken,
                this.variantTextures, this.hologramText, this.lastEditedAt,
                this.importBgMode, this.importFringe, this.importSize);
    }

    public SlotData withIndex(int newIndex) {
        return new SlotData(newIndex, customId, displayName, texture, lightLevel, hardness,
                soundType, faceTextures, animMeta, shapeBoxes, noCollision, this.isBroken,
                this.variantTextures, this.hologramText, this.lastEditedAt,
                this.importBgMode, this.importFringe, this.importSize);
    }

    public SlotData withProperties(int light, float hard, String sound) {
        return new SlotData(index, customId, displayName, texture, light, hard,
                sound, faceTextures, animMeta, shapeBoxes, noCollision, this.isBroken,
                this.variantTextures, this.hologramText, this.lastEditedAt,
                this.importBgMode, this.importFringe, this.importSize);
    }

    /** H4 — replace the variant texture list (0–7 extra variants). */
    public SlotData withVariantTextures(List<byte[]> variants) {
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness,
                soundType, faceTextures, animMeta, shapeBoxes, noCollision, this.isBroken,
                variants == null ? List.of() : variants, this.hologramText, this.lastEditedAt,
                this.importBgMode, this.importFringe, this.importSize);
    }

    /** I2 — set or clear the per-block hologram text override. null/blank = use displayName. */
    public SlotData withHologramText(String text) {
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness,
                soundType, faceTextures, animMeta, shapeBoxes, noCollision, this.isBroken,
                this.variantTextures, (text != null && !text.isBlank()) ? text : null, this.lastEditedAt,
                this.importBgMode, this.importFringe, this.importSize);
    }

    /** 1.27 — set the lastEditedAt timestamp (used by SlotManager.put() on every mutation). */
    public SlotData withLastEditedAt(long ts) {
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness,
                soundType, faceTextures, animMeta, shapeBoxes, noCollision, this.isBroken,
                this.variantTextures, this.hologramText, ts,
                this.importBgMode, this.importFringe, this.importSize);
    }

    /** Phase 4A.1 — set the background removal mode for this block's import settings. */
    public SlotData withImportBgMode(String mode) {
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness,
                soundType, faceTextures, animMeta, shapeBoxes, noCollision, this.isBroken,
                this.variantTextures, this.hologramText, this.lastEditedAt,
                mode, this.importFringe, this.importSize);
    }

    /** Phase 4A.1 — set the fringe removal aggressiveness ("off", "light", "normal", "aggressive"). */
    public SlotData withImportFringe(String fringe) {
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness,
                soundType, faceTextures, animMeta, shapeBoxes, noCollision, this.isBroken,
                this.variantTextures, this.hologramText, this.lastEditedAt,
                this.importBgMode, fringe, this.importSize);
    }

    /** Phase 4A.7 — set the target import size in pixels (64, 128, 256; 0 = server default). */
    public SlotData withImportSize(int size) {
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness,
                soundType, faceTextures, animMeta, shapeBoxes, noCollision, this.isBroken,
                this.variantTextures, this.hologramText, this.lastEditedAt,
                this.importBgMode, this.importFringe, size);
    }

    /** Full deep copy — same as constructing from all fields. */
    public SlotData deepCopy() {
        return new SlotData(index, customId, displayName, texture, lightLevel, hardness,
                soundType, faceTextures, animMeta, shapeBoxes, noCollision, this.isBroken,
                this.variantTextures, this.hologramText, this.lastEditedAt,
                this.importBgMode, this.importFringe, this.importSize);
    }

    @Override
    public String toString() {
        return "SlotData{" + customId + " #" + index + " '" + displayName + "'}";
    }
}
