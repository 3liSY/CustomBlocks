// 
// Decompiled by Procyon v0.6.0
// 

package com.customblocks;

import com.google.gson.GsonBuilder;
import org.slf4j.LoggerFactory;
import java.io.Reader;
import com.google.gson.JsonParser;
import java.io.FileReader;
import java.util.Iterator;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.util.HashSet;
import java.io.IOException;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import com.google.gson.JsonElement;
import java.util.function.Consumer;
import java.util.Objects;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.Collections;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import com.google.gson.Gson;
import org.slf4j.Logger;

public class SlotManager
{
    public static final int MAX_SLOTS = 512;
    private static final Logger LOGGER;
    private static final Gson GSON;
    private static final Map<String, SlotData> SLOTS;
    private static final Map<String, String> ID_TO_SLOT;
    private static byte[] tabIconTexture;
    public static final List<String> FACE_KEYS;
    
    public static SlotData getBySlot(final String slotKey) {
        return SlotManager.SLOTS.get(slotKey);
    }
    
    public static SlotData getById(final String customId) {
        final String k = SlotManager.ID_TO_SLOT.get(customId);
        return (k != null) ? SlotManager.SLOTS.get(k) : null;
    }
    
    public static Collection<SlotData> allSlots() {
        return Collections.unmodifiableCollection((Collection<? extends SlotData>)SlotManager.SLOTS.values());
    }
    
    public static Set<String> allCustomIds() {
        return Collections.unmodifiableSet((Set<? extends String>)SlotManager.ID_TO_SLOT.keySet());
    }
    
    public static boolean hasId(final String id) {
        return SlotManager.ID_TO_SLOT.containsKey(id);
    }
    
    public static int usedSlots() {
        return SlotManager.SLOTS.size();
    }
    
    public static int freeSlots() {
        return 512 - SlotManager.SLOTS.size();
    }
    
    public static byte[] getTabIconTexture() {
        return SlotManager.tabIconTexture;
    }
    
    public static void setTabIconTexture(final byte[] t) {
        SlotManager.tabIconTexture = t;
    }
    
    public static void clearAll() {
        SlotManager.SLOTS.clear();
        SlotManager.ID_TO_SLOT.clear();
        SlotManager.tabIconTexture = null;
    }
    
    public static String getDisplayName(final String slotKey) {
        final SlotData d = SlotManager.SLOTS.get(slotKey);
        return (d != null) ? d.displayName : null;
    }
    
    public static SlotData assign(final String customId, final String displayName, final byte[] texture) {
        for (int i = 0; i < 512; ++i) {
            final String key = "slot_" + i;
            if (!SlotManager.SLOTS.containsKey(key)) {
                final SlotData data = new SlotData(i, customId, displayName, texture);
                SlotManager.SLOTS.put(key, data);
                SlotManager.ID_TO_SLOT.put(customId, key);
                return data;
            }
        }
        return null;
    }
    
    public static SlotData assignAtIndex(final int index, final String customId, final String displayName, final byte[] texture) {
        if (index < 0 || index >= 512) {
            return null;
        }
        final String key = "slot_" + index;
        final SlotData existing = SlotManager.SLOTS.get(key);
        final Map<String, byte[]> faces = (existing != null) ? existing.faceTextures : null;
        final SlotData data = new SlotData(index, customId, displayName, texture, 0, 1.5f, "stone", faces);
        SlotManager.SLOTS.put(key, data);
        SlotManager.ID_TO_SLOT.put(customId, key);
        return data;
    }
    
    public static boolean remove(final String customId) {
        final String k = SlotManager.ID_TO_SLOT.remove(customId);
        if (k == null) {
            return false;
        }
        SlotManager.SLOTS.remove(k);
        return true;
    }
    
    public static boolean rename(final String customId, final String newName) {
        final String k = SlotManager.ID_TO_SLOT.get(customId);
        if (k == null) {
            return false;
        }
        final SlotData o = SlotManager.SLOTS.get(k);
        SlotManager.SLOTS.put(k, new SlotData(o.index, o.customId, newName, o.texture, o.lightLevel, o.hardness, o.soundType, o.faceTextures));
        return true;
    }
    
    public static boolean updateTexture(final String customId, final byte[] texture) {
        final String k = SlotManager.ID_TO_SLOT.get(customId);
        if (k == null) {
            return false;
        }
        final SlotData o = SlotManager.SLOTS.get(k);
        SlotManager.SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, texture, o.lightLevel, o.hardness, o.soundType, o.faceTextures));
        return true;
    }
    
    public static boolean setProperties(final String customId, final int lightLevel, final float hardness, final String soundType) {
        final String k = SlotManager.ID_TO_SLOT.get(customId);
        if (k == null) {
            return false;
        }
        final SlotData o = SlotManager.SLOTS.get(k);
        SlotManager.SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture, lightLevel, hardness, soundType, o.faceTextures));
        return true;
    }
    
    public static boolean setLightLevel(final String customId, final int level) {
        final String k = SlotManager.ID_TO_SLOT.get(customId);
        if (k == null) {
            return false;
        }
        final SlotData o = SlotManager.SLOTS.get(k);
        SlotManager.SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture, level, o.hardness, o.soundType, o.faceTextures));
        return true;
    }
    
    public static boolean setHardness(final String customId, final float hardness) {
        final String k = SlotManager.ID_TO_SLOT.get(customId);
        if (k == null) {
            return false;
        }
        final SlotData o = SlotManager.SLOTS.get(k);
        SlotManager.SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture, o.lightLevel, hardness, o.soundType, o.faceTextures));
        return true;
    }
    
    public static boolean setSoundType(final String customId, final String soundType) {
        final String k = SlotManager.ID_TO_SLOT.get(customId);
        if (k == null) {
            return false;
        }
        final SlotData o = SlotManager.SLOTS.get(k);
        SlotManager.SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture, o.lightLevel, o.hardness, soundType, o.faceTextures));
        return true;
    }
    
    public static boolean setFaceTexture(final String customId, final String face, final byte[] texture) {
        if (!SlotManager.FACE_KEYS.contains(face)) {
            return false;
        }
        final String k = SlotManager.ID_TO_SLOT.get(customId);
        if (k == null) {
            return false;
        }
        final SlotData o = SlotManager.SLOTS.get(k);
        final Map<String, byte[]> faces = new ConcurrentHashMap<String, byte[]>(o.faceTextures);
        faces.put(face, texture);
        SlotManager.SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture, o.lightLevel, o.hardness, o.soundType, faces));
        return true;
    }
    
    public static boolean clearFaceTexture(final String customId, final String face) {
        final String k = SlotManager.ID_TO_SLOT.get(customId);
        if (k == null) {
            return false;
        }
        final SlotData o = SlotManager.SLOTS.get(k);
        final Map<String, byte[]> faces = new ConcurrentHashMap<String, byte[]>(o.faceTextures);
        faces.remove(face);
        SlotManager.SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture, o.lightLevel, o.hardness, o.soundType, faces));
        return true;
    }
    
    public static boolean clearAllFaces(final String customId) {
        final String k = SlotManager.ID_TO_SLOT.get(customId);
        if (k == null) {
            return false;
        }
        final SlotData o = SlotManager.SLOTS.get(k);
        SlotManager.SLOTS.put(k, new SlotData(o.index, o.customId, o.displayName, o.texture, o.lightLevel, o.hardness, o.soundType, null));
        return true;
    }
    
    private static File getConfigDir() {
        return new File("config/customblocks");
    }
    
    public static void saveAll() {
        final File dir = getConfigDir();
        dir.mkdirs();
        final JsonObject root = new JsonObject();
        final JsonArray arr = new JsonArray();
        for (final SlotData d : SlotManager.SLOTS.values()) {
            final JsonObject e = new JsonObject();
            e.addProperty("index", (Number)d.index);
            e.addProperty("customId", d.customId);
            e.addProperty("displayName", d.displayName);
            e.addProperty("lightLevel", (Number)d.lightLevel);
            e.addProperty("hardness", (Number)d.hardness);
            e.addProperty("soundType", d.soundType);
            if (!d.faceTextures.isEmpty()) {
                final JsonArray faces = new JsonArray();
                final Set<String> keySet = d.faceTextures.keySet();
                final JsonArray obj = faces;
                Objects.requireNonNull(obj);
                keySet.forEach(obj::add);
                e.add("faces", (JsonElement)faces);
            }
            arr.add((JsonElement)e);
        }
        root.add("slots", (JsonElement)arr);
        try (final FileWriter fw = new FileWriter(new File(dir, "slots.json"), StandardCharsets.UTF_8)) {
            SlotManager.GSON.toJson((JsonElement)root, (Appendable)fw);
        }
        catch (final IOException ex) {
            SlotManager.LOGGER.error("Failed to save slots.json", (Throwable)ex);
        }
        final Set<String> validFiles = new HashSet<String>();
        for (SlotData d2 : SlotManager.SLOTS.values()) {
            if (d2.texture != null && d2.texture.length > 0) {
                try {
                    Files.write(new File(dir, d2.slotKey() + ".png").toPath(), d2.texture, new OpenOption[0]);
                    validFiles.add(d2.slotKey() + ".png");
                }
                catch (final IOException ex2) {
                    SlotManager.LOGGER.error("Failed to save texture for {}", (Object)d2.customId, (Object)ex2);
                }
            }
            for (Map.Entry<String, byte[]> face : d2.faceTextures.entrySet()) {
                final String faceFile = d2.slotKey() + "_" + (String)face.getKey() + ".png";
                try {
                    Files.write(new File(dir, faceFile).toPath(), face.getValue(), new OpenOption[0]);
                }
                catch (final IOException ex3) {
                    SlotManager.LOGGER.error("Failed to save face texture {} for {}", new Object[] { face.getKey(), d2.customId, ex3 });
                }
                validFiles.add(faceFile);
            }
        }
        final File[] pngs = dir.listFiles((d2, n) -> n.matches("slot_\\d+(_[a-z]+)?\\.png"));
        if (pngs != null) {
            for (final File f : pngs) {
                if (!validFiles.contains(f.getName())) {
                    try {
                        Files.deleteIfExists(f.toPath());
                    }
                    catch (final IOException ex5) {}
                }
            }
        }
        if (SlotManager.tabIconTexture != null) {
            try {
                Files.write(new File(dir, "tab_icon.png").toPath(), SlotManager.tabIconTexture, new OpenOption[0]);
            }
            catch (final IOException ex4) {
                SlotManager.LOGGER.error("Failed to save tab icon", (Throwable)ex4);
            }
        }
    }
    
    public static void loadAll() {
        final File dir = getConfigDir();
        if (!dir.exists()) {
            return;
        }
        final File slotsFile = new File(dir, "slots.json");
        if (!slotsFile.exists()) {
            return;
        }
        try {
            final JsonObject root = JsonParser.parseReader((Reader)new FileReader(slotsFile, StandardCharsets.UTF_8)).getAsJsonObject();
            final JsonArray slots = root.getAsJsonArray("slots");
            for (JsonElement el : slots) {
                final JsonObject e = el.getAsJsonObject();
                final int index = e.get("index").getAsInt();
                final String customId = e.get("customId").getAsString();
                final String displayName = e.get("displayName").getAsString();
                final int lightLevel = e.has("lightLevel") ? e.get("lightLevel").getAsInt() : 0;
                final float hardness = e.has("hardness") ? e.get("hardness").getAsFloat() : 1.5f;
                final String soundType = e.has("soundType") ? e.get("soundType").getAsString() : "stone";
                final File texFile = new File(dir, "slot_" + index + ".png");
                final byte[] texture = (byte[])(texFile.exists() ? Files.readAllBytes(texFile.toPath()) : null);
                final Map<String, byte[]> faces = new ConcurrentHashMap<String, byte[]>();
                if (e.has("faces")) {
                    for (JsonElement faceEl : e.getAsJsonArray("faces")) {
                        final String face = faceEl.getAsString();
                        final File faceFile = new File(dir, "slot_" + index + "_" + face + ".png");
                        if (faceFile.exists()) {
                            faces.put(face, Files.readAllBytes(faceFile.toPath()));
                        }
                    }
                }
                final SlotData data = new SlotData(index, customId, displayName, texture, lightLevel, hardness, soundType, faces);
                SlotManager.SLOTS.put("slot_" + index, data);
                SlotManager.ID_TO_SLOT.put(customId, "slot_" + index);
            }
            SlotManager.LOGGER.info("[CustomBlocks] Loaded {} slot(s).", (Object)SlotManager.SLOTS.size());
        }
        catch (final Exception ex) {
            SlotManager.LOGGER.error("Failed to load slots.json", (Throwable)ex);
        }
        final File tabFile = new File(dir, "tab_icon.png");
        if (tabFile.exists()) {
            try {
                SlotManager.tabIconTexture = Files.readAllBytes(tabFile.toPath());
            }
            catch (final IOException ex2) {
                SlotManager.LOGGER.error("Failed to load tab icon", (Throwable)ex2);
            }
        }
    }
    
    public static void loadFromClientDir(final File mcDir) {
        final File dir = new File(mcDir, "config/customblocks");
        if (!dir.exists()) {
            return;
        }
        final File slotsFile = new File(dir, "slots.json");
        if (!slotsFile.exists()) {
            return;
        }
        try {
            SlotManager.SLOTS.clear();
            SlotManager.ID_TO_SLOT.clear();
            final JsonObject root = JsonParser.parseReader((Reader)new FileReader(slotsFile, StandardCharsets.UTF_8)).getAsJsonObject();
            final JsonArray slots = root.getAsJsonArray("slots");
            for (JsonElement el : slots) {
                final JsonObject e = el.getAsJsonObject();
                final int index = e.get("index").getAsInt();
                final String customId = e.get("customId").getAsString();
                final String displayName = e.get("displayName").getAsString();
                final int lightLevel = e.has("lightLevel") ? e.get("lightLevel").getAsInt() : 0;
                final float hardness = e.has("hardness") ? e.get("hardness").getAsFloat() : 1.5f;
                final String soundType = e.has("soundType") ? e.get("soundType").getAsString() : "stone";
                final File texFile = new File(dir, "slot_" + index + ".png");
                final byte[] texture = (byte[])(texFile.exists() ? Files.readAllBytes(texFile.toPath()) : null);
                final Map<String, byte[]> faces = new ConcurrentHashMap<String, byte[]>();
                if (e.has("faces")) {
                    for (JsonElement faceEl : e.getAsJsonArray("faces")) {
                        final String face = faceEl.getAsString();
                        final File faceFile = new File(dir, "slot_" + index + "_" + face + ".png");
                        if (faceFile.exists()) {
                            faces.put(face, Files.readAllBytes(faceFile.toPath()));
                        }
                    }
                }
                SlotManager.SLOTS.put("slot_" + index, new SlotData(index, customId, displayName, texture, lightLevel, hardness, soundType, faces));
                SlotManager.ID_TO_SLOT.put(customId, "slot_" + index);
            }
            final File tabFile = new File(dir, "tab_icon.png");
            if (tabFile.exists()) {
                SlotManager.tabIconTexture = Files.readAllBytes(tabFile.toPath());
            }
        }
        catch (final Exception ex) {
            SlotManager.LOGGER.error("[CustomBlocks] Client failed to load slots", (Throwable)ex);
        }
    }
    
    public static void saveToClientDir(final File mcDir) {
        final File dir = new File(mcDir, "config/customblocks");
        dir.mkdirs();
        final JsonObject root = new JsonObject();
        final JsonArray arr = new JsonArray();
        for (final SlotData d : SlotManager.SLOTS.values()) {
            final JsonObject e = new JsonObject();
            e.addProperty("index", (Number)d.index);
            e.addProperty("customId", d.customId);
            e.addProperty("displayName", d.displayName);
            e.addProperty("lightLevel", (Number)d.lightLevel);
            e.addProperty("hardness", (Number)d.hardness);
            e.addProperty("soundType", d.soundType);
            if (!d.faceTextures.isEmpty()) {
                final JsonArray faces = new JsonArray();
                final Set<String> keySet = d.faceTextures.keySet();
                final JsonArray obj = faces;
                Objects.requireNonNull(obj);
                keySet.forEach(obj::add);
                e.add("faces", (JsonElement)faces);
            }
            arr.add((JsonElement)e);
        }
        root.add("slots", (JsonElement)arr);
        try (final FileWriter fw = new FileWriter(new File(dir, "slots.json"), StandardCharsets.UTF_8)) {
            SlotManager.GSON.toJson((JsonElement)root, (Appendable)fw);
        }
        catch (final IOException ex) {
            SlotManager.LOGGER.error("Failed to write client slots.json", (Throwable)ex);
        }
        for (SlotData d : SlotManager.SLOTS.values()) {
            if (d.texture != null) {
                try {
                    Files.write(new File(dir, d.slotKey() + ".png").toPath(), d.texture, new OpenOption[0]);
                }
                catch (final IOException ex2) {}
            }
            for (Map.Entry<String, byte[]> face : d.faceTextures.entrySet()) {
                try {
                    Files.write(new File(dir, d.slotKey() + "_" + (String)face.getKey() + ".png").toPath(), face.getValue(), new OpenOption[0]);
                }
                catch (final IOException ex3) {}
            }
        }
        if (SlotManager.tabIconTexture != null) {
            try {
                Files.write(new File(dir, "tab_icon.png").toPath(), SlotManager.tabIconTexture, new OpenOption[0]);
            }
            catch (final IOException ex4) {}
        }
        final Set<String> clientValid = new HashSet<String>();
        for (SlotData d2 : SlotManager.SLOTS.values()) {
            if (d2.texture != null && d2.texture.length > 0) {
                clientValid.add(d2.slotKey() + ".png");
            }
            for (String face2 : d2.faceTextures.keySet()) {
                clientValid.add(d2.slotKey() + "_" + face2 + ".png");
            }
        }
        final File[] clientPngs = dir.listFiles((d2, n) -> n.matches("slot_\\d+(_[a-z]+)?\\.png"));
        if (clientPngs != null) {
            for (final File f : clientPngs) {
                if (!clientValid.contains(f.getName())) {
                    try {
                        Files.deleteIfExists(f.toPath());
                    }
                    catch (final IOException ex5) {}
                }
            }
        }
    }
    
    static {
        LOGGER = LoggerFactory.getLogger("CustomBlocks/SlotManager");
        GSON = new GsonBuilder().setPrettyPrinting().create();
        SLOTS = new ConcurrentHashMap<String, SlotData>();
        ID_TO_SLOT = new ConcurrentHashMap<String, String>();
        SlotManager.tabIconTexture = null;
        FACE_KEYS = List.of("top", "bottom", "north", "south", "east", "west");
    }
    
    public static class SlotData
    {
        public final int index;
        public final String customId;
        public final String displayName;
        public byte[] texture;
        public int lightLevel;
        public float hardness;
        public String soundType;
        public final Map<String, byte[]> faceTextures;
        
        public SlotData(final int index, final String customId, final String displayName, final byte[] texture, final int lightLevel, final float hardness, final String soundType, final Map<String, byte[]> faceTextures) {
            this.index = index;
            this.customId = customId;
            this.displayName = displayName;
            this.texture = texture;
            this.lightLevel = Math.max(0, Math.min(15, lightLevel));
            this.hardness = hardness;
            this.soundType = ((soundType != null && !soundType.isEmpty()) ? soundType : "stone");
            this.faceTextures = ((faceTextures != null) ? new ConcurrentHashMap<String, byte[]>(faceTextures) : new ConcurrentHashMap<String, byte[]>());
        }
        
        public SlotData(final int index, final String customId, final String displayName, final byte[] texture, final int lightLevel, final float hardness, final String soundType) {
            this(index, customId, displayName, texture, lightLevel, hardness, soundType, null);
        }
        
        public SlotData(final int index, final String customId, final String displayName, final byte[] texture) {
            this(index, customId, displayName, texture, 0, 1.5f, "stone", null);
        }
        
        public String slotKey() {
            return "slot_" + this.index;
        }
        
        public boolean hasFaces() {
            return !this.faceTextures.isEmpty();
        }
    }
}
