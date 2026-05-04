but# Join Timeout Fix — Pack Generation Optimization

## Problem
YoCube1 (and any client with stale cache) gets kicked on join: **"Timed out"**.

`ResourcePackGenerator.generate()` writes **~8000 files** (2048 slots × 4 files each) including ~1500 placeholder PNGs for empty slots. Takes **~60 seconds**. The server keepalive timeout is 30 seconds → kicked.

3liSY works because their texture hash matches (cache HIT → generation skipped entirely).

## Evidence (YoCube1's client log)
- `maxSlots=2048` (client config) vs `maxSlots=600` (server), only 548 slots have data
- 1530 `Corrupt PNG` errors from old session files
- `Cleanup: deleted 6056 stale slot files`
- Pack generation: 16:35:38 → 16:36:42 = **64 seconds**
- `Texture cache MISS → Regenerating` at 16:47:25
- `Stopping!` at 16:47:28 (server kicked for timeout)

---

## Phase 1 — Don't write placeholder PNGs for empty slots

**File:** `ResourcePackGenerator.java` lines 215–221

**Before:**
```java
} else {
    Files.write(texDest.toPath(), PLACEHOLDER_PNG);
    if (mcmetaDest.exists()) mcmetaDest.delete();
}
```

**After:**
```java
} else {
    // No texture data — delete any stale file, don't write placeholder.
    // Empty slots show Minecraft's default missing texture (never visible to players).
    if (texDest.exists()) texDest.delete();
    if (mcmetaDest.exists()) mcmetaDest.delete();
}
```

**Impact:** Eliminates ~1500 PNG file writes. Biggest single speedup.

**Build + test after this phase.**

---

## Phase 2 — Skip blockstate/model JSON writes if file already exists

**File:** `ResourcePackGenerator.java` lines 325–477

Wrap each `writeJson(...)` in an existence check:

```java
File bsFile = new File(assets, "blockstates/" + slotKey + ".json");
if (!bsFile.exists()) writeJson(bs, bsFile);

File bmFile = new File(assets, "models/block/" + slotKey + ".json");
if (!bmFile.exists()) writeJson(bm, bmFile);

File imFile = new File(assets, "models/item/" + slotKey + ".json");
if (!imFile.exists()) writeJson(im, imFile);
```

**Note:** Model JSON for shaped/faced blocks CAN change when the shape or faces change. Add a condition: skip only if the slot data hasn't changed (shape/faces match what's on disk). Simplest approach: always write for slots with data (they're only ~548), skip for empty slots that already have a generic model file.

**Impact:** Eliminates ~4500 redundant JSON writes on reconnect.

**Build + test after this phase.**

---

## Phase 3 — Validate texture bytes before NativeImage decode

**File:** `ResourcePackGenerator.java` → `writePng()` method (line 1012)

Add a minimum-size guard after the PNG magic byte check:

```java
private static void writePng(byte[] imageBytes, File dest) {
    try {
        dest.getParentFile().mkdirs();

        // Reject obviously corrupt data — don't even try NativeImage
        if (imageBytes == null || imageBytes.length < 67) {
            Files.write(dest.toPath(), PLACEHOLDER_PNG);
            CustomBlocksMod.LOGGER.warn("[CustomBlocks] Texture too small ({} bytes) for {}, wrote placeholder",
                    imageBytes != null ? imageBytes.length : 0, dest.getName());
            return;
        }

        // PNG signature check (existing)
        if (imageBytes[0] == (byte) 0x89 && imageBytes[1] == (byte) 0x50
                && imageBytes[2] == (byte) 0x4E && imageBytes[3] == (byte) 0x47) {
            Files.write(dest.toPath(), imageBytes);
        } else {
            try (NativeImage img = NativeImage.read(new ByteArrayInputStream(imageBytes))) {
                img.writeTo(dest.toPath());
            }
        }
    } catch (Exception e) {
        try { Files.write(dest.toPath(), PLACEHOLDER_PNG); }
        catch (Exception ignored) {}
        CustomBlocksMod.LOGGER.warn("[CustomBlocks] Could not decode image for {}, wrote placeholder", dest.getName());
    }
}
```

**Impact:** Corrupt textures are caught instantly — no NativeImage hang, no cascade of 1530 errors.

**Build + test after this phase.**

---

## Phase 4 — `assignAtIndex()` writes .dat files (prevents future corruption)

**File:** `SlotManager.java` lines 270–282

**Before:**
```java
public static synchronized SlotData assignAtIndex(int index, String customId, String displayName, byte[] texture) {
    SlotData existing = bySlot.get("slot_" + index);
    if (existing != null) remove(existing.customId);
    SlotData data = SlotData.createTrusted(index, customId, displayName, texture);
    put(data);
    return data;
}
```

**After:**
```java
public static synchronized SlotData assignAtIndex(int index, String customId, String displayName, byte[] texture) {
    SlotData existing = bySlot.get("slot_" + index);
    if (existing != null) remove(existing.customId);
    SlotData data = SlotData.createTrusted(index, customId, displayName, texture);
    put(data);
    // Write texture to .dat file (same as assign()) — prevents loss on crash
    if (data.texture != null && data.texture.length > 0) {
        final int slotIdx = data.index;
        final byte[] texCopy = data.texture.clone();
        IO_EXECUTOR.submit(() -> writeTextureFile(slotIdx, texCopy));
    }
    return data;
}
```

**Impact:** Textures persist even if the client crashes before `saveToClientDir()`. Prevents the stale/corrupt file problem that caused YoCube1's 1530 errors in the first place.

**Build + test after this phase.**

---

## Expected Results

| Metric | Before | After |
|---|---|---|
| PNG files written | ~2048 | ~548 (only real textures) |
| JSON files written | ~6144 | ~548 first time, 0 on reconnect |
| Generation time | ~60s | <5s |
| Server timeout kick | YES | NO |
| Corrupt PNG errors | 1530 | 0 |
| Texture loss on crash | Possible | Prevented |

## Immediate Workaround (tell YoCube1 now)
Delete these folders in `.minecraft/`:
```
customblocks_data/
resourcepacks/CustomBlocks/
```
