package com.customblocks;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
// §3: needed for isBroken texture check cached in SlotData constructor
import com.customblocks.ImageProcessor;

public class SlotManager {

    public static final int MAX_SLOTS = 2048;
    private static final Logger LOGGER = LoggerFactory.getLogger("CustomBlocks/SlotManager");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<String, SlotData> SLOTS      = new ConcurrentHashMap<>();
    private static final Map<String, String>   ID_TO_SLOT = new ConcurrentHashMap<>();
    private static byte[] tabIconTexture = null;

    /** Valid face keys. */
    public static final List<String> FACE_KEYS = List.of("top","bottom","north","south","east","west");

    // ── Shape system ──────────────────────────────────────────────────────────

    /** One bounding box in pixel units (0–16). Immutable. */
    public record ShapeBox(float x1, float y1, float z1, float x2, float y2, float z2) {
        public String toCoordString() {
            return String.format("%.6g,%.6g,%.6g,%.6g,%.6g,%.6g", x1, y1, z1, x2, y2, z2);
        }
        public String toDisplayString() {
            return String.format("(%.1f,%.1f,%.1f → %.1f,%.1f,%.1f)", x1, y1, z1, x2, y2, z2);
        }
        /** Parse "x1,y1,z1,x2,y2,z2" (pixel units). */
        public static ShapeBox parse(String s) {
            String[] p = s.split(",");
            if (p.length != 6) throw new IllegalArgumentException("Need 6 comma-separated numbers");
            return new ShapeBox(Float.parseFloat(p[0].trim()), Float.parseFloat(p[1].trim()),
                                Float.parseFloat(p[2].trim()), Float.parseFloat(p[3].trim()),
                                Float.parseFloat(p[4].trim()), Float.parseFloat(p[5].trim()));
        }
        public boolean valid() {
            return x1 >= 0 && y1 >= 0 && z1 >= 0 && x2 <= 16 && y2 <= 16 && z2 <= 16
                && x2 > x1 && y2 > y1 && z2 > z1;
        }
    }

    /** Built-in shape presets. */
    public static final Map<String, List<ShapeBox>> SHAPE_PRESETS;
    static {
        Map<String, List<ShapeBox>> m = new LinkedHashMap<>();
        m.put("full",     List.of(new ShapeBox(0,0,0,16,16,16)));
        m.put("slab",     List.of(new ShapeBox(0,0,0,16,8,16)));
        m.put("thin",     List.of(new ShapeBox(0,0,0,16,4,16)));
        m.put("carpet",   List.of(new ShapeBox(0,0,0,16,1,16)));
        m.put("pillar",   List.of(new ShapeBox(4,0,4,12,16,12)));
        m.put("small",    List.of(new ShapeBox(2,2,2,14,14,14)));
        m.put("micro",    List.of(new ShapeBox(4,4,4,12,12,12)));
        m.put("pane",     List.of(new ShapeBox(7,0,0,9,16,16)));
        m.put("trapdoor", List.of(new ShapeBox(0,0,0,16,3,16)));
        m.put("fence",    List.of(new ShapeBox(6,0,6,10,16,10), new ShapeBox(7,10,7,9,16,9)));
        m.put("stairs",   List.of(new ShapeBox(0,0,0,16,8,16), new ShapeBox(0,8,0,16,16,8)));
        m.put("cross",    List.of(new ShapeBox(7,0,0,9,16,16), new ShapeBox(0,0,7,16,16,9)));
        SHAPE_PRESETS = Collections.unmodifiableMap(m);
    }

    /** User-saved shape templates: name → list of boxes. Persisted separately. */
    private static final Map<String, List<ShapeBox>> SHAPE_TEMPLATES = new ConcurrentHashMap<>();

    // ── Undo / Redo stacks ───────────────────────────────────────────────────
    private static final Deque<UndoEntry> UNDO_STACK = new ArrayDeque<>();
    private static final Deque<UndoEntry> REDO_STACK = new ArrayDeque<>();
    private static final int MAX_UNDO = 20;

    /**
     * wasDeleted = true  → block was deleted; undo must re-insert it.
     * previousState = null → block was just created; undo must delete it.
     */
    public record UndoEntry(
            String customId,
            SlotData previousState,
            String description,
            boolean wasDeleted
    ) {}

    private static SlotData snapshot(String customId) {
        SlotData c = getById(customId);
        if (c == null) return null;
        Map<String, byte[]> facesCopy = new ConcurrentHashMap<>();
        c.faceTextures.forEach((k, v) -> facesCopy.put(k, v.clone()));
        return new SlotData(c.index, c.customId, c.displayName,
                c.texture != null ? c.texture.clone() : null,
                c.lightLevel, c.hardness, c.soundType, facesCopy, c.animMeta,
                c.shapeBoxes != null ? new ArrayList<>(c.shapeBoxes) : null, c.noCollision);
    }

    private static void pushRaw(UndoEntry entry) {
        synchronized (UNDO_STACK) {
            UNDO_STACK.push(entry);
            while (UNDO_STACK.size() > MAX_UNDO) UNDO_STACK.pollLast();
        }
        // New mutation clears redo history
        synchronized (REDO_STACK) { REDO_STACK.clear(); }
    }

    /** Push current state before a mutating operation (retexture, setface, setglow…). */
    public static void pushUndo(String customId, String description) {
        SlotData snap = snapshot(customId);
        if (snap == null) return;
        pushRaw(new UndoEntry(customId, snap, description, false));
    }

    /** Push state before deleting a block so it can be fully restored. */
    public static void pushUndoDelete(String customId) {
        SlotData snap = snapshot(customId);
        if (snap == null) return;
        pushRaw(new UndoEntry(customId, snap, "delete", true));
    }

    /** Push a marker after creating a block so undo can remove it. */
    public static void pushUndoCreate(String customId) {
        pushRaw(new UndoEntry(customId, null, "create", false));
    }

    /** Pop and return the most recent undo entry (null if stack empty). */
    public static UndoEntry popUndo() {
        synchronized (UNDO_STACK) {
            return UNDO_STACK.isEmpty() ? null : UNDO_STACK.pop();
        }
    }

    public static int undoStackSize() {
        synchronized (UNDO_STACK) { return UNDO_STACK.size(); }
    }

    public static String peekUndoDescription() {
        synchronized (UNDO_STACK) {
            if (UNDO_STACK.isEmpty()) return "";
            UndoEntry e = UNDO_STACK.peek();
            return e.description() + " on " + e.customId();
        }
    }

    // ── Redo stack ────────────────────────────────────────────────────────────

    /** Push a redo entry (called internally when undo is executed). */
    public static void pushRedo(UndoEntry entry) {
        synchronized (REDO_STACK) {
            REDO_STACK.push(entry);
            while (REDO_STACK.size() > MAX_UNDO) REDO_STACK.pollLast();
        }
    }

    public static UndoEntry popRedo() {
        synchronized (REDO_STACK) {
            return REDO_STACK.isEmpty() ? null : REDO_STACK.pop();
        }
    }

    public static int redoStackSize() {
        synchronized (REDO_STACK) { return REDO_STACK.size(); }
    }

    public static String peekRedoDescription() {
        synchronized (REDO_STACK) {
            if (REDO_STACK.isEmpty()) return "";
            UndoEntry e = REDO_STACK.peek();
            return e.description() + " on " + e.customId();
        }
    }

    /** Push to undo stack WITHOUT clearing redo — used internally when applying redo. */
    public static void pushUndoForRedo(UndoEntry entry) {
        synchronized (UNDO_STACK) {
            UNDO_STACK.push(entry);
            while (UNDO_STACK.size() > MAX_UNDO) UNDO_STACK.pollLast();
        }
    }

    // ── Data class ────────────────────────────────────────────────────────────

    public static class SlotData {
        public final int    index;
        public final String customId;
        public final String displayName;
        public       byte[] texture;
        public       int    lightLevel;
        public       float  hardness;
        public       String soundType;
        /** Per-face overrides. Keys: top bottom north south east west. Never null. */
        public final Map<String, byte[]> faceTextures;
        /** JSON string for Minecraft animated texture .mcmeta. Null if not animated. */
        public       String animMeta;
        /** Custom shape boxes (pixel units 0-16). Null or empty = full cube. */
        public       List<ShapeBox> shapeBoxes;
        /** If true, no collision (can walk through). */
        public       boolean noCollision;
        /** §3: Cached broken-texture flag — set once on upload, O(1) reads afterward. */
        public       boolean isBroken;

        public SlotData(int index, String customId, String displayName, byte[] texture,
                        int lightLevel, float hardness, String soundType,
                        Map<String, byte[]> faceTextures, String animMeta,
                        List<ShapeBox> shapeBoxes, boolean noCollision) {
            this.index       = index;
            this.customId    = customId;
            this.displayName = displayName;
            this.texture     = texture;
            this.lightLevel  = Math.max(0, Math.min(15, lightLevel));
            this.hardness    = hardness;
            this.soundType   = (soundType != null && !soundType.isEmpty()) ? soundType : "stone";
            this.faceTextures = (faceTextures != null)
                    ? new ConcurrentHashMap<>(faceTextures) : new ConcurrentHashMap<>();
            this.animMeta    = animMeta;
            this.shapeBoxes  = (shapeBoxes != null && !shapeBoxes.isEmpty())
                    ? new ArrayList<>(shapeBoxes) : null;
            this.noCollision = noCollision;
            // §3: Cache isBroken once so GuiManager can query it in O(1)
            this.isBroken    = (texture != null && texture.length > 0)
                    && ImageProcessor.isBrokenTexture(texture);
        }

        // Legacy constructors (no shape)
        public SlotData(int index, String customId, String displayName, byte[] texture,
                        int lightLevel, float hardness, String soundType,
                        Map<String, byte[]> faceTextures, String animMeta) {
            this(index, customId, displayName, texture, lightLevel, hardness, soundType, faceTextures, animMeta, null, false);
        }

        public SlotData(int index, String customId, String displayName, byte[] texture,
                        int lightLevel, float hardness, String soundType,
                        Map<String, byte[]> faceTextures) {
            this(index, customId, displayName, texture, lightLevel, hardness, soundType, faceTextures, null, null, false);
        }

        public SlotData(int index, String customId, String displayName, byte[] texture,
                        int lightLevel, float hardness, String soundType) {
            this(index, customId, displayName, texture, lightLevel, hardness, soundType, null, null, null, false);
        }

        public SlotData(int index, String customId, String displayName, byte[] texture) {
            this(index, customId, displayName, texture, 0, 1.5f, "stone", null, null, null, false);
        }

        public String  slotKey()     { return "slot_" + index; }
        public boolean hasFaces()    { return !faceTextures.isEmpty(); }
        public boolean isAnimated()  { return animMeta != null && !animMeta.isEmpty(); }
        public boolean isShaped()    { return shapeBoxes != null && !shapeBoxes.isEmpty(); }
        public String  shapeLabel()  {
            if (!isShaped()) return "full";
            for (Map.Entry<String, List<ShapeBox>> e : SHAPE_PRESETS.entrySet())
                if (e.getValue().equals(shapeBoxes)) return e.getKey();
            return shapeBoxes.size() + " box" + (shapeBoxes.size() == 1 ? "" : "es");
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public static SlotData getBySlot(String slotKey)  { return SLOTS.get(slotKey); }
    public static SlotData getById(String customId) {
        String k = ID_TO_SLOT.get(customId);
        return k != null ? SLOTS.get(k) : null;
    }
    public static Collection<SlotData> allSlots()     { return Collections.unmodifiableCollection(SLOTS.values()); }
    public static Set<String>          allCustomIds() { return Collections.unmodifiableSet(ID_TO_SLOT.keySet()); }
    public static boolean              hasId(String id)   { return ID_TO_SLOT.containsKey(id); }
    public static int                  usedSlots()    { return SLOTS.size(); }
    public static int                  freeSlots()    { return MAX_SLOTS - SLOTS.size(); }
    public static byte[]               getTabIconTexture() { return tabIconTexture; }
    public static void                 setTabIconTexture(byte[] t) { tabIconTexture = t; }

    public static void clearAll() {
        SLOTS.clear();
        ID_TO_SLOT.clear();
        tabIconTexture = null;
    }

    public static String getDisplayName(String slotKey) {
        SlotData d = SLOTS.get(slotKey);
        return d != null ? d.displayName : null;
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    public static SlotData assign(String customId, String displayName, byte[] texture) {
        for (int i = 0; i < MAX_SLOTS; i++) {
            String key = "slot_" + i;
            if (!SLOTS.containsKey(key)) {
                SlotData data = new SlotData(i, customId, displayName, texture);
                SLOTS.put(key, data);
                ID_TO_SLOT.put(customId, key);
                return data;
            }
        }
        return null;
    }

    public static SlotData assignAtIndex(int index, String customId, String displayName, byte[] texture) {
        if (index < 0 || index >= MAX_SLOTS) return null;
        String key = "slot_" + index;
        SlotData existing = SLOTS.get(key);
        Map<String, byte[]> faces = (existing != null) ? existing.faceTextures : null;
        String animMeta = (existing != null) ? existing.animMeta : null;
        SlotData data = new SlotData(index, customId, displayName, texture, 0, 1.5f, "stone", faces, animMeta);
        SLOTS.put(key, data);
        ID_TO_SLOT.put(customId, key);
        return data;
    }

    public static boolean remove(String customId) {
        String k = ID_TO_SLOT.remove(customId);
        if (k == null) return false;
        SLOTS.remove(k);
        return true;
    }

    public static boolean rename(String customId, String newName) {
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        SLOTS.put(k, new SlotData(o.index, o.customId, newName, o.texture,
                o.lightLevel, o.hardness, o.soundType, o.faceTextures, o.animMeta,
                o.shapeBoxes, o.noCollision));
        return true;
    }

    /** Re-ID: change the customId of a block. Returns false if oldId not found or newId already taken. */
    public static boolean reId(String oldId, String newId) {
        if (!ID_TO_SLOT.containsKey(oldId)) return false;
        if (ID_TO_SLOT.containsKey(newId)) return false;
        String k = ID_TO_SLOT.remove(oldId);
        SlotData o = SLOTS.get(k);
        SlotData updated = new SlotData(o.index, newId, o.displayName, o.texture,
                o.lightLevel, o.hardness, o.soundType, o.faceTextures, o.animMeta,
                o.shapeBoxes, o.noCollision);
        SLOTS.put(k, updated);
        ID_TO_SLOT.put(newId, k);
        return true;
    }

    public static boolean updateTexture(String customId, byte[] texture) {
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, texture,
                o.lightLevel, o.hardness, o.soundType, o.faceTextures, o.animMeta,
                o.shapeBoxes, o.noCollision));
        return true;
    }

    public static boolean setProperties(String customId, int lightLevel, float hardness, String soundType) {
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture,
                lightLevel, hardness, soundType, o.faceTextures, o.animMeta));
        return true;
    }

    public static boolean setLightLevel(String customId, int level) {
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture,
                level, o.hardness, o.soundType, o.faceTextures, o.animMeta));
        return true;
    }

    public static boolean setHardness(String customId, float hardness) {
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture,
                o.lightLevel, hardness, o.soundType, o.faceTextures, o.animMeta));
        return true;
    }

    public static boolean setSoundType(String customId, String soundType) {
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture,
                o.lightLevel, o.hardness, soundType, o.faceTextures, o.animMeta));
        return true;
    }

    public static boolean setAnimMeta(String customId, String animMeta) {
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture,
                o.lightLevel, o.hardness, o.soundType, o.faceTextures, animMeta,
                o.shapeBoxes, o.noCollision));
        return true;
    }

    public static boolean setFaceTexture(String customId, String face, byte[] texture) {
        if (!FACE_KEYS.contains(face)) return false;
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        Map<String, byte[]> faces = new ConcurrentHashMap<>(o.faceTextures);
        faces.put(face, texture);
        SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture,
                o.lightLevel, o.hardness, o.soundType, faces, o.animMeta,
                o.shapeBoxes, o.noCollision));
        return true;
    }

    public static boolean clearFaceTexture(String customId, String face) {
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        Map<String, byte[]> faces = new ConcurrentHashMap<>(o.faceTextures);
        faces.remove(face);
        SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture,
                o.lightLevel, o.hardness, o.soundType, faces, o.animMeta,
                o.shapeBoxes, o.noCollision));
        return true;
    }

    public static boolean clearAllFaces(String customId) {
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture,
                o.lightLevel, o.hardness, o.soundType, null, o.animMeta,
                o.shapeBoxes, o.noCollision));
        return true;
    }

    // ── Shape mutations ───────────────────────────────────────────────────────

    /** Replace all shape boxes (null/empty = full cube). */
    public static boolean setShape(String customId, List<ShapeBox> boxes) {
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture,
                o.lightLevel, o.hardness, o.soundType, o.faceTextures, o.animMeta,
                boxes, o.noCollision));
        return true;
    }

    /** Add one more box (max 16). Returns false if block not found or already at 16 boxes. */
    public static boolean addBox(String customId, ShapeBox box) {
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        List<ShapeBox> boxes = o.shapeBoxes != null ? new ArrayList<>(o.shapeBoxes) : new ArrayList<>();
        if (boxes.size() >= 16) return false;
        boxes.add(box);
        SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture,
                o.lightLevel, o.hardness, o.soundType, o.faceTextures, o.animMeta,
                boxes, o.noCollision));
        return true;
    }

    /** Remove box at 0-based index. */
    public static boolean removeBox(String customId, int index) {
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        if (o.shapeBoxes == null || index < 0 || index >= o.shapeBoxes.size()) return false;
        List<ShapeBox> boxes = new ArrayList<>(o.shapeBoxes);
        boxes.remove(index);
        SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture,
                o.lightLevel, o.hardness, o.soundType, o.faceTextures, o.animMeta,
                boxes.isEmpty() ? null : boxes, o.noCollision));
        return true;
    }

    /** Reset to full cube shape. */
    public static boolean clearShape(String customId) {
        return setShape(customId, null);
    }

    /** Toggle collision. */
    public static boolean setCollision(String customId, boolean collision) {
        String k = ID_TO_SLOT.get(customId);
        if (k == null) return false;
        SlotData o = SLOTS.get(k);
        SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture,
                o.lightLevel, o.hardness, o.soundType, o.faceTextures, o.animMeta,
                o.shapeBoxes, !collision));
        return true;
    }

    /** Save current shape of a block as a named template. */
    public static boolean saveTemplate(String templateName, String customId) {
        SlotData d = getById(customId);
        if (d == null) return false;
        List<ShapeBox> boxes = d.shapeBoxes != null ? new ArrayList<>(d.shapeBoxes)
                : SHAPE_PRESETS.get("full");
        SHAPE_TEMPLATES.put(templateName, boxes);
        saveTemplates();
        return true;
    }

    /** Apply a named template to a block. */
    public static boolean loadTemplate(String customId, String templateName) {
        List<ShapeBox> boxes = SHAPE_TEMPLATES.get(templateName);
        if (boxes == null) boxes = SHAPE_PRESETS.get(templateName);
        if (boxes == null) return false;
        return setShape(customId, new ArrayList<>(boxes));
    }

    public static Set<String> allTemplateNames() {
        Set<String> all = new LinkedHashSet<>(SHAPE_PRESETS.keySet());
        all.addAll(SHAPE_TEMPLATES.keySet());
        return all;
    }

    /** Build a VoxelShape from a block's shape data. Returns null to mean "use default full cube". */
    public static net.minecraft.util.shape.VoxelShape buildVoxelShape(String slotKey) {
        SlotData d = getBySlot(slotKey);
        if (d == null || !d.isShaped()) return null;
        net.minecraft.util.shape.VoxelShape shape = net.minecraft.util.shape.VoxelShapes.empty();
        for (ShapeBox b : d.shapeBoxes) {
            shape = net.minecraft.util.shape.VoxelShapes.union(shape,
                    net.minecraft.util.shape.VoxelShapes.cuboid(
                            b.x1()/16f, b.y1()/16f, b.z1()/16f,
                            b.x2()/16f, b.y2()/16f, b.z2()/16f));
        }
        return shape;
    }

    /**
     * Restore a block to a previous snapshot (used by /cb undo).
     * wasDeleted=true: the block was deleted; re-insert it at its original index.
     * wasDeleted=false: the block still exists; overwrite its data in place.
     */
    public static boolean restoreSnapshot(SlotData snapshot, boolean wasDeleted) {
        if (wasDeleted) {
            String k = "slot_" + snapshot.index;
            SlotData occupant = SLOTS.get(k);
            if (occupant != null && !occupant.customId.equals(snapshot.customId)) return false;
            SLOTS.put(k, snapshot);
            ID_TO_SLOT.put(snapshot.customId, k);
            return true;
        }
        String k = ID_TO_SLOT.get(snapshot.customId);
        if (k == null) return false;
        SLOTS.put(k, snapshot);
        return true;
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private static File getConfigDir() { return new File("config/customblocks"); }

    public static void saveAll() {
        File dir = getConfigDir();
        dir.mkdirs();
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (SlotData d : SLOTS.values()) {
            JsonObject e = new JsonObject();
            e.addProperty("index",       d.index);
            e.addProperty("customId",    d.customId);
            e.addProperty("displayName", d.displayName);
            e.addProperty("lightLevel",  d.lightLevel);
            e.addProperty("hardness",    d.hardness);
            e.addProperty("soundType",   d.soundType);
            if (d.animMeta != null) e.addProperty("animMeta", d.animMeta);
            if (!d.faceTextures.isEmpty()) {
                JsonArray faces = new JsonArray();
                d.faceTextures.keySet().forEach(faces::add);
                e.add("faces", faces);
            }
            if (d.isShaped()) {
                JsonArray shapeArr = new JsonArray();
                for (ShapeBox b : d.shapeBoxes) shapeArr.add(b.toCoordString());
                e.add("shapeBoxes", shapeArr);
            }
            if (d.noCollision) e.addProperty("noCollision", true);
            arr.add(e);
        }
        root.add("slots", arr);
        try (FileWriter fw = new FileWriter(new File(dir, "slots.json"), StandardCharsets.UTF_8)) {
            GSON.toJson(root, fw);
        } catch (IOException ex) { LOGGER.error("Failed to save slots.json", ex); }

        java.util.Set<String> validFiles = new java.util.HashSet<>();
        for (SlotData d : SLOTS.values()) {
            if (d.texture != null && d.texture.length > 0) {
                try {
                    Files.write(new File(dir, d.slotKey() + ".png").toPath(), d.texture);
                    validFiles.add(d.slotKey() + ".png");
                } catch (IOException ex) { LOGGER.error("Failed to save texture for {}", d.customId, ex); }
            }
            for (Map.Entry<String, byte[]> face : d.faceTextures.entrySet()) {
                String faceFile = d.slotKey() + "_" + face.getKey() + ".png";
                try { Files.write(new File(dir, faceFile).toPath(), face.getValue()); }
                catch (IOException ex) { LOGGER.error("Failed to save face {} for {}", face.getKey(), d.customId, ex); }
                validFiles.add(faceFile);
            }
        }
        File[] pngs = dir.listFiles((d2, n) -> n.matches("slot_\\d+(_[a-z]+)?\\.png"));
        if (pngs != null) {
            for (File f : pngs) {
                if (!validFiles.contains(f.getName())) {
                    try { Files.deleteIfExists(f.toPath()); } catch (IOException ignored) {}
                }
            }
        }
        if (tabIconTexture != null) {
            try { Files.write(new File(dir, "tab_icon.png").toPath(), tabIconTexture); }
            catch (IOException ex) { LOGGER.error("Failed to save tab icon", ex); }
        }
    }

    public static void loadAll() {
        File dir = getConfigDir();
        if (!dir.exists()) return;
        File slotsFile = new File(dir, "slots.json");
        if (!slotsFile.exists()) return;
        try {
            JsonObject root = JsonParser.parseReader(new FileReader(slotsFile, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray slots = root.getAsJsonArray("slots");
            for (JsonElement el : slots) {
                JsonObject e = el.getAsJsonObject();
                int    index       = e.get("index").getAsInt();
                String customId    = e.get("customId").getAsString();
                String displayName = e.get("displayName").getAsString();
                int    lightLevel  = e.has("lightLevel") ? e.get("lightLevel").getAsInt()   : 0;
                float  hardness    = e.has("hardness")   ? e.get("hardness").getAsFloat()   : 1.5f;
                String soundType   = e.has("soundType")  ? e.get("soundType").getAsString() : "stone";
                String animMeta    = e.has("animMeta")   ? e.get("animMeta").getAsString()  : null;
                File   texFile     = new File(dir, "slot_" + index + ".png");
                byte[] texture     = texFile.exists() ? Files.readAllBytes(texFile.toPath()) : null;
                Map<String, byte[]> faces = new ConcurrentHashMap<>();
                if (e.has("faces")) {
                    for (JsonElement faceEl : e.getAsJsonArray("faces")) {
                        String face = faceEl.getAsString();
                        File faceFile = new File(dir, "slot_" + index + "_" + face + ".png");
                        if (faceFile.exists()) faces.put(face, Files.readAllBytes(faceFile.toPath()));
                    }
                }
                List<ShapeBox> shapeBoxes = null;
                if (e.has("shapeBoxes")) {
                    shapeBoxes = new ArrayList<>();
                    for (JsonElement bEl : e.getAsJsonArray("shapeBoxes")) {
                        try { shapeBoxes.add(ShapeBox.parse(bEl.getAsString())); }
                        catch (Exception ignored) {}
                    }
                    if (shapeBoxes.isEmpty()) shapeBoxes = null;
                }
                boolean noCollision = e.has("noCollision") && e.get("noCollision").getAsBoolean();
                SlotData data = new SlotData(index, customId, displayName, texture,
                        lightLevel, hardness, soundType, faces, animMeta, shapeBoxes, noCollision);
                SLOTS.put("slot_" + index, data);
                ID_TO_SLOT.put(customId, "slot_" + index);
            }
            LOGGER.info("[CustomBlocks] Loaded {} slot(s).", SLOTS.size());
        } catch (Exception ex) { LOGGER.error("Failed to load slots.json", ex); }

        File tabFile = new File(dir, "tab_icon.png");
        if (tabFile.exists()) {
            try { tabIconTexture = Files.readAllBytes(tabFile.toPath()); }
            catch (IOException ex) { LOGGER.error("Failed to load tab icon", ex); }
        }
        loadTemplates();
    }

    // ── Client-side persistence ───────────────────────────────────────────────

    public static void saveTemplates() {
        File dir = getConfigDir(); dir.mkdirs();
        JsonObject root = new JsonObject();
        for (Map.Entry<String, List<ShapeBox>> entry : SHAPE_TEMPLATES.entrySet()) {
            JsonArray arr = new JsonArray();
            for (ShapeBox b : entry.getValue()) arr.add(b.toCoordString());
            root.add(entry.getKey(), arr);
        }
        try (FileWriter fw = new FileWriter(new File(dir, "shape_templates.json"), StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(root, fw);
        } catch (IOException ex) { LOGGER.error("Failed to save shape_templates.json", ex); }
    }

    public static void loadTemplates() {
        File f = new File(getConfigDir(), "shape_templates.json");
        if (!f.exists()) return;
        try {
            JsonObject root = JsonParser.parseReader(new FileReader(f, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : root.entrySet()) {
                List<ShapeBox> boxes = new ArrayList<>();
                for (JsonElement el : entry.getValue().getAsJsonArray()) {
                    try { boxes.add(ShapeBox.parse(el.getAsString())); } catch (Exception ignored) {}
                }
                if (!boxes.isEmpty()) SHAPE_TEMPLATES.put(entry.getKey(), boxes);
            }
        } catch (Exception ex) { LOGGER.error("Failed to load shape_templates.json", ex); }
    }

    public static void loadFromClientDir(File mcDir) {
        File dir = new File(mcDir, "config/customblocks");
        if (!dir.exists()) return;
        File slotsFile = new File(dir, "slots.json");
        if (!slotsFile.exists()) return;
        try {
            SLOTS.clear();
            ID_TO_SLOT.clear();
            JsonObject root = JsonParser.parseReader(new FileReader(slotsFile, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray slots = root.getAsJsonArray("slots");
            for (JsonElement el : slots) {
                JsonObject e = el.getAsJsonObject();
                int    index       = e.get("index").getAsInt();
                String customId    = e.get("customId").getAsString();
                String displayName = e.get("displayName").getAsString();
                int    lightLevel  = e.has("lightLevel") ? e.get("lightLevel").getAsInt()   : 0;
                float  hardness    = e.has("hardness")   ? e.get("hardness").getAsFloat()   : 1.5f;
                String soundType   = e.has("soundType")  ? e.get("soundType").getAsString() : "stone";
                String animMeta    = e.has("animMeta")   ? e.get("animMeta").getAsString()  : null;
                File   texFile     = new File(dir, "slot_" + index + ".png");
                byte[] texture     = texFile.exists() ? Files.readAllBytes(texFile.toPath()) : null;
                Map<String, byte[]> faces = new ConcurrentHashMap<>();
                if (e.has("faces")) {
                    for (JsonElement faceEl : e.getAsJsonArray("faces")) {
                        String face = faceEl.getAsString();
                        File faceFile = new File(dir, "slot_" + index + "_" + face + ".png");
                        if (faceFile.exists()) faces.put(face, Files.readAllBytes(faceFile.toPath()));
                    }
                }
                List<ShapeBox> shapeBoxes2 = null;
                if (e.has("shapeBoxes")) {
                    shapeBoxes2 = new ArrayList<>();
                    for (JsonElement bEl : e.getAsJsonArray("shapeBoxes")) {
                        try { shapeBoxes2.add(ShapeBox.parse(bEl.getAsString())); }
                        catch (Exception ignored) {}
                    }
                    if (shapeBoxes2.isEmpty()) shapeBoxes2 = null;
                }
                boolean noCollision2 = e.has("noCollision") && e.get("noCollision").getAsBoolean();
                SLOTS.put("slot_" + index, new SlotData(index, customId, displayName, texture,
                        lightLevel, hardness, soundType, faces, animMeta, shapeBoxes2, noCollision2));
                ID_TO_SLOT.put(customId, "slot_" + index);
            }
            File tabFile = new File(dir, "tab_icon.png");
            if (tabFile.exists()) tabIconTexture = Files.readAllBytes(tabFile.toPath());
        } catch (Exception ex) { LOGGER.error("[CustomBlocks] Client failed to load slots", ex); }
    }

    public static void saveToClientDir(File mcDir) {
        File dir = new File(mcDir, "config/customblocks");
        dir.mkdirs();
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (SlotData d : SLOTS.values()) {
            JsonObject e = new JsonObject();
            e.addProperty("index",       d.index);
            e.addProperty("customId",    d.customId);
            e.addProperty("displayName", d.displayName);
            e.addProperty("lightLevel",  d.lightLevel);
            e.addProperty("hardness",    d.hardness);
            e.addProperty("soundType",   d.soundType);
            if (d.animMeta != null) e.addProperty("animMeta", d.animMeta);
            if (!d.faceTextures.isEmpty()) {
                JsonArray faces = new JsonArray();
                d.faceTextures.keySet().forEach(faces::add);
                e.add("faces", faces);
            }
            if (d.isShaped()) {
                JsonArray shapeArr = new JsonArray();
                for (ShapeBox b : d.shapeBoxes) shapeArr.add(b.toCoordString());
                e.add("shapeBoxes", shapeArr);
            }
            if (d.noCollision) e.addProperty("noCollision", true);
            arr.add(e);
        }
        root.add("slots", arr);
        try (FileWriter fw = new FileWriter(new File(dir, "slots.json"), StandardCharsets.UTF_8)) {
            GSON.toJson(root, fw);
        } catch (IOException ex) { LOGGER.error("Failed to write client slots.json", ex); }
        for (SlotData d : SLOTS.values()) {
            if (d.texture != null) {
                try { Files.write(new File(dir, d.slotKey() + ".png").toPath(), d.texture); }
                catch (IOException ignored) {}
            }
            for (Map.Entry<String, byte[]> face : d.faceTextures.entrySet()) {
                try { Files.write(new File(dir, d.slotKey() + "_" + face.getKey() + ".png").toPath(), face.getValue()); }
                catch (IOException ignored) {}
            }
        }
        if (tabIconTexture != null) {
            try { Files.write(new File(dir, "tab_icon.png").toPath(), tabIconTexture); }
            catch (IOException ignored) {}
        }
        java.util.Set<String> clientValid = new java.util.HashSet<>();
        for (SlotData d : SLOTS.values()) {
            if (d.texture != null && d.texture.length > 0) clientValid.add(d.slotKey() + ".png");
            for (String face : d.faceTextures.keySet()) clientValid.add(d.slotKey() + "_" + face + ".png");
        }
        File[] clientPngs = dir.listFiles((d2, n) -> n.matches("slot_\\d+(_[a-z]+)?\\.png"));
        if (clientPngs != null) {
            for (File f : clientPngs) {
                if (!clientValid.contains(f.getName())) {
                    try { Files.deleteIfExists(f.toPath()); } catch (IOException ignored) {}
                }
            }
        }
    }
}
