# CustomBlocks Lightweight Architecture Plan

**Goal**: Transform CustomBlocks from a memory-heavy mod into a fast, lightweight, crash-proof system.

**Author**: Cascade (AI Engineer) — for review and approval by 3liSY  
**Date**: April 21, 2026  
**Status**: AWAITING APPROVAL — Nothing will be implemented until you say "go."

---

## The Problem (What's Wrong Right Now)

### Current Data Flow (Every Single Block Change)

```
User creates 1 block with triangle
  → SlotManager.assign() stores it in RAM
  → SlotManager.saveAll() fires
    → markDirty() starts 2-second debounce timer
    → saveAllAsync() runs on IO thread:
      → getSnapshot() copies references to ALL 488 slots
      → Loops ALL 488 slots, Base64-encodes EVERY texture
      → Builds a 200MB+ JSON string in memory
      → Writes 200MB to disk (slots.json.tmp → slots.json)
      → ResourcePackServer.updatePackWithSnapshot():
        → Loops ALL 488 slots AGAIN
        → Writes ALL textures into a ZIP file
        → ~200MB ZIP rebuild
  → NetworkManager.broadcastUpdate() sends the 1 new texture to online players ✓ (this part is fine)
```

**Client side (after receiving the 1 new texture)**:
```
scheduleGenerateAndReload(client, 2000ms debounce)
  → Waits 2 seconds
  → SlotManager.saveToClientDir():
    → Loops ALL 488 slots, Base64-encodes EVERY texture
    → Writes 200MB+ slots.json to .minecraft/customblocks_data/
  → ResourcePackGenerator.generate():
    → Loops ALL 512 (maxSlots) iterations
    → Writes 488 PNG files + 488 blockstate JSONs + 488 model JSONs + face textures
    → ~2000+ files written to disk
  → client.reloadResources():
    → Minecraft reloads ALL resources (vanilla + all mods + all resource packs)
    → Takes 5-15 seconds depending on hardware
```

### Current Join Flow

```
Player connects → client sends SyncRequestPayload
  → Server runs sendFullSync():
    → Sends FullSyncPayload (metadata only, no textures)
    → Loops ALL 488 slots, enqueues texture for EACH into TextureQueue
    → Sends sync_done sentinel
  → Drip-feed: 256KB/tick = ~5MB/sec
    → 488 textures × avg ~50KB each = ~24MB total
    → Takes ~19 seconds to complete
  → Client waits for sync_done, then:
    → saveToClientDir() writes 200MB slots.json
    → ResourcePackGenerator.generate() writes ~2000 files
    → client.reloadResources() takes 5-15 seconds
  → TOTAL: 19 + 5 + 10 = ~34 seconds from connect to playing
```

### What's Wasting Memory and Time

| Problem | Where | Impact |
|---------|-------|--------|
| All 488 textures Base64-encoded on every save | `saveAllAsync()` line 681-708 | 200-400MB RAM spike → OOM crash |
| Same 200MB JSON on client save | `saveToClientDir()` line 745-765 | Freezes client |
| Full ZIP rebuild on every save | `updatePackWithSnapshot()` | CPU + disk + RAM waste |
| 2000+ files written for 1 texture change | `ResourcePackGenerator.generate()` | 5+ seconds of disk I/O |
| All 488 textures re-sent on every join | `sendFullSync()` | 19 seconds of network I/O |
| `slots.json` loaded as one giant String | `loadAll()` line 532 | 200MB allocation on startup |
| `SlotData.texture` cloned on every constructor | `SlotData` line 79 | Doubles RAM for each mutation |

---

## The Solution (10 Phases, Each Independent)

### Phase 0: Already Done ✅
**Streaming JSON writer** — `saveAllAsync()` now streams one slot at a time instead of building a 200MB tree in memory. Committed as `12b8c1a`.

---

### Phase 1: Separate Texture Files (Server Save)

**What changes**: Textures are no longer stored inside `slots.json`. Each texture is its own `.dat` file.

**New disk layout**:
```
config/customblocks/
├── slots.json              ← metadata only (~50KB, not 200MB)
├── textures/
│   ├── slot_0.dat          ← raw PNG bytes (written ONCE when created)
│   ├── slot_0_north.dat    ← face texture override
│   ├── slot_406.dat        ← written when triangle creates this block
│   └── ...
├── textured_count.txt      ← safety counter (already exists)
```

**Files modified**: `SlotManager.java` only

**Exact changes**:

1. **`serializeSlot()`** — REMOVE these lines:
   ```java
   // REMOVE: if (d.texture != null)
   //             obj.addProperty("texture", Base64.getEncoder().encodeToString(d.texture));
   // REMOVE: if (d.hasFaces()) { ... faceTextures Base64 ... }
   ```
   The JSON now contains ONLY metadata (index, customId, displayName, lightLevel, hardness, soundType, animMeta, noCollision, shapeBoxes). Size: ~100 bytes per slot instead of ~50KB.

2. **Add `TEXTURES_DIR` constant and helper methods**:
   ```java
   private static final String TEXTURES_DIR = DATA_DIR + "/textures";

   /** Write a single texture file to disk. Called when a texture changes — NOT on every save. */
   private static void writeTextureFile(int slotIndex, byte[] data) {
       try {
           Path dir = Path.of(TEXTURES_DIR);
           Files.createDirectories(dir);
           if (data != null && data.length > 0) {
               Files.write(dir.resolve("slot_" + slotIndex + ".dat"), data);
           } else {
               Files.deleteIfExists(dir.resolve("slot_" + slotIndex + ".dat"));
           }
       } catch (Exception e) {
           LOGGER.error("[CustomBlocks] Failed to write texture for slot_{}", slotIndex, e);
       }
   }

   /** Write a face texture file. */
   private static void writeFaceTextureFile(int slotIndex, String face, byte[] data) {
       try {
           Path dir = Path.of(TEXTURES_DIR);
           Files.createDirectories(dir);
           if (data != null && data.length > 0) {
               Files.write(dir.resolve("slot_" + slotIndex + "_" + face + ".dat"), data);
           } else {
               Files.deleteIfExists(dir.resolve("slot_" + slotIndex + "_" + face + ".dat"));
           }
       } catch (Exception e) {
           LOGGER.error("[CustomBlocks] Failed to write face texture for slot_{}_{}", slotIndex, face, e);
       }
   }

   /** Delete all texture files for a slot. */
   private static void deleteTextureFiles(int slotIndex) {
       try {
           Path dir = Path.of(TEXTURES_DIR);
           Files.deleteIfExists(dir.resolve("slot_" + slotIndex + ".dat"));
           for (String face : SlotData.FACE_KEYS) {
               Files.deleteIfExists(dir.resolve("slot_" + slotIndex + "_" + face + ".dat"));
           }
       } catch (Exception e) {
           LOGGER.error("[CustomBlocks] Failed to delete textures for slot_{}", slotIndex, e);
       }
   }

   /** Read a texture file from disk. Returns null if not found. */
   private static byte[] readTextureFile(int slotIndex) {
       try {
           Path file = Path.of(TEXTURES_DIR, "slot_" + slotIndex + ".dat");
           return Files.exists(file) ? Files.readAllBytes(file) : null;
       } catch (Exception e) {
           LOGGER.error("[CustomBlocks] Failed to read texture for slot_{}", slotIndex, e);
           return null;
       }
   }
   ```

3. **`assign()`** — after `put(data)`, add:
   ```java
   IO_EXECUTOR.submit(() -> writeTextureFile(data.index, data.texture));
   ```

4. **`update()`** — detect texture changes and write:
   ```java
   // After put(updated):
   if (old.texture != updated.texture) {
       IO_EXECUTOR.submit(() -> writeTextureFile(updated.index, updated.texture));
   }
   // Check face texture changes
   for (String face : SlotData.FACE_KEYS) {
       byte[] oldFace = old.faceTextures.get(face);
       byte[] newFace = updated.faceTextures.get(face);
       if (oldFace != newFace) {
           IO_EXECUTOR.submit(() -> writeFaceTextureFile(updated.index, face, newFace));
       }
   }
   ```

5. **`remove()`** — after removing from maps, add:
   ```java
   IO_EXECUTOR.submit(() -> deleteTextureFiles(data.index));
   ```

6. **`loadAll()`** — after deserializing metadata from JSON, load textures from files:
   ```java
   // After put(data):
   // If texture is null (new format), load from file
   if (data.texture == null) {
       byte[] tex = readTextureFile(data.index);
       if (tex != null) {
           SlotData withTex = data.withTexture(tex);
           put(withTex); // overwrites in maps
       }
   }
   // Same for face textures — check for face files on disk
   ```

7. **Migration** — The first time `loadAll()` reads an old `slots.json` with inline Base64 textures, those textures arrive via `deserializeSlot()` (which still READS Base64 if present). The next `saveAllAsync()` writes the metadata-only JSON. Meanwhile, `assign()`/`update()` calls write individual texture files. So migration is automatic:
   - Old format loads normally (Base64 is still read)
   - Textures are written to files on first mutation or via a one-time migration sweep
   - We add a migration sweep at the end of `loadAll()`:
   ```java
   // One-time migration: if textures dir doesn't exist but slots have textures,
   // write all textures to files
   Path texDir = Path.of(TEXTURES_DIR);
   if (!Files.exists(texDir)) {
       Files.createDirectories(texDir);
       for (SlotData d : byId.values()) {
           if (d.texture != null && d.texture.length > 0) {
               writeTextureFile(d.index, d.texture);
           }
           for (var face : d.faceTextures.entrySet()) {
               writeFaceTextureFile(d.index, face.getKey(), face.getValue());
           }
       }
       LOGGER.info("[CustomBlocks] Migration complete: wrote {} texture files.", byId.size());
   }
   ```

**What this fixes**:
- `slots.json` drops from 200MB → ~50KB
- `saveAllAsync()` writes ~50KB metadata instead of 200MB (streaming writer now writes trivial data)
- No more Base64 encoding on saves (the textures are already on disk)
- Texture files are only written when they CHANGE, not on every save

**What stays the same**:
- In-memory `SlotData.texture` is still `byte[]` — all networking, GUI, rendering code unchanged
- `loadAll()` still loads everything into RAM on startup (Phase 4 addresses this)
- `deserializeSlot()` still READS Base64 if present (backward compatibility)

**Risk**: LOW — the JSON format changes (textures removed), but old format is still readable. All runtime behavior is identical since textures stay in RAM.

**Build & test**: After implementation, verify:
- [ ] Server starts and loads existing `slots.json` with inline textures
- [ ] `textures/` directory is created with individual files
- [ ] New `slots.json` is ~50KB (no Base64 blobs)
- [ ] Creating a block with triangle works, saves fast, no lag
- [ ] Existing blocks still have textures after restart

---

### Phase 2: Single-Slot Client Resource Pack Update

**What changes**: When 1 block is created/retextured, only that 1 PNG file is written to the resource pack. The full `generate()` is skipped.

**Files modified**: `ResourcePackGenerator.java`, `CustomBlocksClient.java`

**Exact changes**:

1. **Add `generateSingleSlot()` to `ResourcePackGenerator.java`**:
   ```java
   /**
    * Write ONLY the texture PNG + mcmeta + model for a single slot.
    * Blockstate + model files already exist from the initial full generate.
    * This is 1 file write instead of 2000+.
    */
   public static void generateSingleSlot(MinecraftClient client, int slotIndex) {
       try {
           File packRoot = new File(client.runDirectory, "resourcepacks/CustomBlocks");
           File assets = new File(packRoot, "assets/" + MOD_ID);
           String slotKey = "slot_" + slotIndex;
           SlotData data = SlotManager.getBySlot(slotKey);

           // Write the PNG
           File texDest = new File(assets, "textures/block/" + slotKey + ".png");
           File mcmetaDest = new File(assets, "textures/block/" + slotKey + ".png.mcmeta");
           if (data != null && data.texture != null && data.texture.length > 0) {
               writePng(data.texture, texDest);
               // Handle animation mcmeta (same logic as full generate)
               int frames = com.customblocks.ImageProcessor.getVerticalFrames(data.texture);
               if (frames > 1) {
                   String effectiveMeta = (data.animMeta != null && !data.animMeta.isEmpty())
                       ? data.animMeta
                       : com.customblocks.ImageProcessor.synthesizeDefaultMcmeta(frames);
                   try (java.io.FileWriter fw = new java.io.FileWriter(mcmetaDest, java.nio.charset.StandardCharsets.UTF_8)) {
                       fw.write(effectiveMeta);
                   }
               } else {
                   if (mcmetaDest.exists()) mcmetaDest.delete();
               }
           } else {
               Files.write(texDest.toPath(), PLACEHOLDER_PNG);
               if (mcmetaDest.exists()) mcmetaDest.delete();
           }

           // Write face textures if any
           if (data != null && data.hasFaces()) {
               for (Map.Entry<String, byte[]> face : data.faceTextures.entrySet()) {
                   File faceDest = new File(assets, "textures/block/" + slotKey + "_" + face.getKey() + ".png");
                   writePng(face.getValue(), faceDest);
               }
               // Inherit main texture for faces without overrides
               if (data.texture != null && data.texture.length > 0) {
                   for (String face : SlotData.FACE_KEYS) {
                       if (!data.faceTextures.containsKey(face)) {
                           File inheritDest = new File(assets, "textures/block/" + slotKey + "_" + face + ".png");
                           writePng(data.texture, inheritDest);
                       }
                   }
               }
           }

           // Write blockstate + model if not yet on disk (new slot from Rectangle tool, etc.)
           File bsFile = new File(assets, "blockstates/" + slotKey + ".json");
           if (!bsFile.exists()) {
               // Generate blockstate + block model + item model (same logic as full generate)
               writeBlockstateAndModel(assets, slotKey, data);
           }
           CustomBlocksMod.LOGGER.info("[CustomBlocks] Single-slot generate for slot_{} complete.", slotIndex);
       } catch (Exception e) {
           CustomBlocksMod.LOGGER.error("[CustomBlocks] Failed to generate single slot_{}", slotIndex, e);
       }
   }
   ```

2. **Add `scheduleSingleSlotReload()` to `CustomBlocksClient.java`**:
   ```java
   /**
    * Fast path: write ONLY the changed slot's texture to the pack directory,
    * skip saveToClientDir (heavy), skip full generate (heavy), then reload.
    */
   private static void scheduleSingleSlotReload(MinecraftClient client, int slotIndex, long debounceMs) {
       lastPacketTime.set(System.currentTimeMillis());
       if (generateRunning.compareAndSet(false, true)) {
           Thread t = new Thread(() -> {
               // Short debounce — wait for any rapid follow-up packets
               try { Thread.sleep(debounceMs); } catch (InterruptedException ignored) {}

               // Write ONLY this slot's texture to the pack
               ResourcePackGenerator.generateSingleSlot(client, slotIndex);

               // Update the texture hash
               String currentHash = computeTextureHash();
               saveCachedHash(client.runDirectory, currentHash);

               // Trigger reload
               client.execute(() -> {
                   generateRunning.set(false);
                   if (reloadInFlight.compareAndSet(false, true)) {
                       client.reloadResources().thenRun(() ->
                           client.execute(() -> {
                               reloadInFlight.set(false);
                               CustomBlocksMod.LOGGER.info("[CustomBlocks] Single-slot reload complete for slot_{}.", slotIndex);
                               pendingCreativeRefresh = true;
                           })
                       ).exceptionally(ex -> {
                           client.execute(() -> {
                               reloadInFlight.set(false);
                               CustomBlocksMod.LOGGER.error("[CustomBlocks] Single-slot reload failed.", ex);
                           });
                           return null;
                       });
                   }
               });
           }, "CustomBlocks-SingleSlotReload");
           t.setDaemon(true);
           t.start();
       }
   }
   ```

3. **Modify the `"add"` and `"retexture"` handlers in `CustomBlocksClient.java`**:
   - Currently at line 279-284, `"add"` and `"retexture"` call `scheduleGenerateAndReload(client, 2000L)`
   - Change to: `scheduleSingleSlotReload(client, payload.slotIndex(), 500L)` (500ms debounce instead of 2000ms)
   - The full `scheduleGenerateAndReload` is still used for `"remove"`, `"setface"`, `"clearface"`, `"clearfaces"` (which may affect model files)

**What this fixes**:
- Creating 1 block writes 1-2 files instead of 2000+
- Generation step: 5+ seconds → ~10ms
- Debounce reduced from 2s → 500ms
- Total time to see new texture: ~500ms debounce + ~10ms write + 5-10s Minecraft reload = **~6-10 seconds** (down from ~20 seconds)

**What stays the same**:
- Full `generate()` is still used on join (initial pack build)
- `reloadResources()` time is unchanged (Minecraft limitation)
- Full `generate()` still used for removals and complex face operations

**Risk**: LOW — we're adding a NEW method alongside the existing one. If single-slot generate fails, the next full generate will fix it.

**Build & test**: After implementation, verify:
- [ ] Create block with triangle → texture appears after ~6-10s (not 20s)
- [ ] Retexture a block → new texture appears after ~6-10s
- [ ] Tab icon change still works (uses full generate path)
- [ ] /cb reload still works (uses full generate path)

---

### Phase 3: Smart Join Sync (Skip What Client Already Has)

**What changes**: The client tells the server what hash it has. If the hash matches, the server skips the drip-feed entirely.

**Files modified**: `CustomBlocksClient.java`, `NetworkManager.java`, `SyncRequestPayload.java`

**Exact changes**:

1. **Modify `SyncRequestPayload`** — add a `textureHash` field:
   ```java
   // Currently: SyncRequestPayload is probably empty or minimal
   // Add: String textureHash — the client's cached hash (or "" if no cache)
   ```

2. **Client sends hash with sync request** — when sending `SyncRequestPayload`, include the cached texture hash:
   ```java
   String cachedHash = loadCachedHash(client.runDirectory);
   // Send SyncRequestPayload with cachedHash
   ```

3. **Server compares hash** — in `NetworkManager.onSyncRequest()`:
   ```java
   // Compute server-side hash of all textures
   String serverHash = computeServerTextureHash();

   if (clientHash.equals(serverHash) && !clientHash.isEmpty()) {
       // CACHE HIT — client already has all textures
       // Send metadata-only FullSyncPayload + immediate sync_done
       // Skip the entire drip-feed
       LOGGER.info("[CustomBlocks] Hash match for {} — skipping drip-feed", player.getName().getString());
   } else {
       // CACHE MISS — full drip-feed as before
       sendFullSync(player);
   }
   ```

4. **Server-side hash computation** — add to `SlotManager` or `NetworkManager`:
   ```java
   // Same SHA-256 algorithm as client side
   // Hash all slot IDs + texture bytes + animMeta + face textures
   ```

**What this fixes**:
- Rejoining when nothing changed: 19 seconds → **instant** (0 textures sent)
- Only sends textures when they've actually changed
- Server-side hash is computed once and cached (invalidated on any slot change)

**What stays the same**:
- First join is still full drip-feed (client has no cache yet)
- If textures changed, full drip-feed still happens

**Risk**: MEDIUM — adds a new payload field. Must verify backward compatibility (old clients without hash field should still work). The hash comparison must be correct — a false positive would skip needed textures.

**Mitigation**: If hash is empty or comparison fails, fall back to full drip-feed (current behavior). Never skip drip-feed if client sends empty hash.

**Build & test**: After implementation, verify:
- [ ] First join: full drip-feed (same as before)
- [ ] Disconnect + reconnect with no changes: instant join (no drip-feed)
- [ ] Another player creates a block, you reconnect: only new texture is sent (hash mismatch triggers full drip-feed)
- [ ] Old client without hash support: full drip-feed (backward compatible)

---

### Phase 4: Server RAM Reduction (Lazy Texture Loading)

**What changes**: The server doesn't keep all 488 textures in RAM at all times. It loads them from disk on demand.

**This is the biggest change and the most risky. Should only be done AFTER Phases 1-3 are stable.**

**Files modified**: `SlotData.java`, `SlotManager.java`, `NetworkManager.java`

**Concept**:
- `SlotData.texture` becomes `null` after loading (textures live on disk, not RAM)
- When networking code needs a texture (drip-feed, broadcast), it calls `SlotManager.loadTexture(slotIndex)` which reads from disk
- When items need to recolor a texture (triangle, square), the source texture is loaded from disk
- After processing, the texture is released (not kept in RAM)

**Memory impact**:
- Currently: 488 slots × avg 50KB texture = **~24MB minimum, up to 100MB+ with large textures**
- After: 488 slots × ~200 bytes metadata = **~100KB**
- Texture loaded on demand: 1 texture in RAM at a time = **~50KB peak**

**What this fixes**:
- Server base RAM reduced by 24-100MB+
- Faster startup (textures not all loaded into memory)
- Less GC pressure (no large byte arrays sitting in old gen)

**Risk**: HIGH — touches many code paths. Every place that accesses `d.texture` must handle the null case or call a load method. Requires careful auditing.

**Mitigation**: Phase 1 must be complete first (textures on disk). Extensive testing required. Can be reverted independently.

**Detailed implementation deferred until Phases 1-3 are stable.**

---

### Phase 5: Drip-Feed Budget Increase

**What changes**: One line. Double the network budget.

**File**: `NetworkManager.java` line 158

**Change**:
```java
// Before:
private static final int BYTES_PER_TICK_BUDGET = 256 * 1024; // 256KB
// After:
private static final int BYTES_PER_TICK_BUDGET = 512 * 1024; // 512KB
```

**What this fixes**: Join drip-feed ~19s → ~10s

**Risk**: ZERO — purely network bandwidth. No memory implications. Shared hosting bandwidth can handle it (game already transfers chunks at higher rates).

**Can be done at any time, independently of other phases.**

---

### Phase 6: Remove TwelveMonkeys Library (JAR Size Reduction)

**What changes**: The TwelveMonkeys imaging library (5 bundled JARs, 433 KB) is removed. WebP images are handled via URL rewriting and a lightweight proxy fallback instead.

**Current JAR breakdown** (730 KB total):

| Component | Size | % of JAR |
|-----------|------|----------|
| TwelveMonkeys JARs (WebP library) | 433 KB | 60% |
| GuiManager.class | 66 KB | 9% |
| CustomBlockCommand.class | 37 KB | 5% |
| All other mod code | ~194 KB | 26% |

TwelveMonkeys provides WebP support via `javax.imageio`. Java already handles PNG, JPG, GIF, and BMP natively — the entire library exists for one format.

**How WebP still works without it**:

The mod already rewrites Discord and Imgur URLs to request PNG instead of WebP (this code is already in `ImageProcessor.java`). For the rare case where WebP comes from another source, a free proxy service ([wsrv.nl](https://wsrv.nl)) converts it transparently:

```
User pastes image URL
  │
  ├─ Discord/Imgur .webp? → URL rewritten to .png (existing code, no proxy needed)
  │
  ├─ Download image, try Java's built-in ImageIO
  │   ├─ Success (PNG/JPG/GIF/BMP) → done
  │   └─ Failure → check if bytes are WebP (RIFF/WEBP header)
  │       ├─ Yes → re-download via wsrv.nl/?url=...&output=png → done
  │       │        (animated WebP → &output=gif&n=-1 → Java reads GIF natively)
  │       └─ No  → "Image format not supported, try PNG"
  │
  └─ WebP error message never appears
```

**About wsrv.nl**:
- Free, open-source image proxy (BSD 3-Clause license)
- Running since 2007 — **99.993% uptime** over 18 years
- Backed by Cloudflare CDN (300+ global datacenters)
- No API key, no signup, no payment
- Rate limit: 2,500 images per 10 minutes per IP (a Minecraft server won't hit this)
- Supports input: JPEG, PNG, GIF, TIFF, WebP, PDF, SVG — outputs any format
- Animated images supported
- Privacy: logs deleted after 7 days, images not stored beyond cache
- Self-hostable if needed: [github.com/weserv/images](https://github.com/weserv/images)

**Edge cases verified**:

| Scenario | Result |
|----------|--------|
| Discord WebP link | URL rewritten to PNG (existing code) — proxy never touched |
| Imgur WebP link | URL rewritten to PNG (existing code) — proxy never touched |
| Random site WebP link | Proxy converts to PNG silently |
| Animated WebP | Proxy converts to GIF, Java reads GIF natively |
| wsrv.nl is down (0.007% of the time) | PNG/JPG/GIF still work. Only random-site WebP fails with a friendly message |
| Signed/expiring Discord URLs | Works — proxy fetches immediately before expiry |
| URL with query parameters | URL-encoded before passing to proxy |

**Files modified**: `build.gradle`, `ImageProcessor.java`

**Exact changes**:

1. **`build.gradle`** — remove 5 dependency lines:
   ```gradle
   // REMOVE all of these:
   include implementation("com.twelvemonkeys.imageio:imageio-webp:3.10.1")
   include implementation("com.twelvemonkeys.imageio:imageio-core:3.10.1")
   include implementation("com.twelvemonkeys.common:common-lang:3.10.1")
   include implementation("com.twelvemonkeys.common:common-io:3.10.1")
   include implementation("com.twelvemonkeys.common:common-image:3.10.1")
   ```

2. **`ImageProcessor.java`** — add wsrv.nl fallback (~15 lines):
   - In `toPng()`: if `ImageIO.read()` returns null and bytes have WebP header → re-download through `https://wsrv.nl/?url=ENCODED_URL&output=png`
   - In `processAnimation()`: if no ImageReader found and bytes are WebP → re-download through `https://wsrv.nl/?url=ENCODED_URL&output=gif&n=-1`
   - Remove the `ImageIO.scanForPlugins()` call (no longer needed without TwelveMonkeys)
   - Update the comment and error message referencing TwelveMonkeys/WebP

**What this fixes**:
- JAR size drops from **730 KB → ~300 KB** (59% reduction)
- 5 fewer bundled JARs = simpler dependency tree
- WebP still works transparently for users

**What stays the same**:
- All existing PNG/JPG/GIF/BMP handling is unchanged
- Discord and Imgur URL rewriting is unchanged (already exists)
- All image processing (resize, background removal, animation) is unchanged

**Risk**: LOW — URL rewriting for Discord/Imgur already exists. The proxy fallback is only for edge cases. If the proxy is unreachable, Java's native formats still work perfectly.

**Build & test**: After implementation, verify:
- [ ] PNG/JPG/GIF image URLs still work
- [ ] Discord WebP link → downloads as PNG (URL rewrite)
- [ ] Imgur WebP link → downloads as PNG (URL rewrite)
- [ ] Random WebP link → converts via proxy
- [ ] Animated WebP → converts to animated GIF via proxy
- [ ] JAR size is ~300 KB or less

---

### Phase 7: Remove Duplicate Client-Side GUI (Dead Code)

**What changes**: One client-side GUI screen is removed. It duplicates functionality that the server-side chest GUI (`GuiManager`) already provides.

**⚠️ IMPORTANT**: `AnimBlockScreen.java` is **NOT dead code**. It is actively used by the `OpenAnimGuiPayload` handler in `CustomBlocksClient.java` (line 298). The server sends this payload to open the client-side animation settings GUI. **Deleting it would break animation settings.** Only `CustomBlocksScreen.java` can be removed.

**File removed**:

| File | Lines | Compiled size | What it does |
|------|-------|---------------|-------------|
| `CustomBlocksScreen.java` | 807 | 18 KB | Client-side block management GUI (keybind-opened). Create, rename, retexture, delete, bulk delete, give, export, properties — all via chat commands. Every feature already exists in the server-side chest GUI. |

**File NOT removed (still in use)**:

| File | Lines | Compiled size | Why it stays |
|------|-------|---------------|-------------|
| `AnimBlockScreen.java` | 361 | 7 KB | Used by `OpenAnimGuiPayload` handler — the server sends this payload to open the client-side animation GUI. Cannot be deleted. |

**Why `CustomBlocksScreen` is dead weight**:
- Sends every action as `send("customblock ...")` — it's a UI wrapper around commands that the chest GUI already handles natively
- Adds ~18 KB to the JAR and 807 lines of code to maintain
- Removing it reduces the attack surface for bugs

**Files modified**: `CustomBlocksClient.java` (remove keybind registration and `CustomBlocksScreen` reference only)

**Exact changes**:

1. **Delete** `src/main/java/com/customblocks/client/gui/CustomBlocksScreen.java`
2. **`CustomBlocksClient.java`** — remove:
   - The keybind registration (`openGuiKey`)
   - The `ClientTickEvents` handler that opens `CustomBlocksScreen`
   - The import for `CustomBlocksScreen`
3. **DO NOT** delete `AnimBlockScreen.java` or remove its references

**What this fixes**:
- ~18 KB less in the JAR
- 807 lines of duplicate code removed

**What stays the same**:
- Server-side chest GUI (`GuiManager`) is untouched — all features still accessible
- `/cb` command still opens the chest GUI
- All block management features remain available
- **`AnimBlockScreen` still works** — animation settings via `OpenAnimGuiPayload` unchanged

**Risk**: LOW — no features are lost, only a second UI path to the same features. Users who used the keybind will use `/cb` instead.

**Build & test**: After implementation, verify:
- [ ] `/cb` command still opens the main chest GUI
- [ ] All editor features work (rename, retexture, faces, shapes, animation, properties, sound)
- [ ] Animation settings still accessible from the editor chest GUI
- [ ] `AnimBlockScreen` still opens when server sends `OpenAnimGuiPayload`
- [ ] No compile errors from removed references

---

### Phase 8: GuiManager `openScreen` Refactor (Code Cleanup)

**What changes**: The repeated boilerplate pattern used to open GUI screens (30 call sites) is consolidated into a single helper method.

**Current pattern** (repeated 30 times across `GuiManager.java`):
```java
STATES.put(player.getUuid(), GuiState.tools());
openScreen(player, new SimpleNamedScreenHandlerFactory(
    (s, pi, p) -> new CbScreenHandler(s, pi, buildToolsGui(player)),
    Text.literal("§d§lMagic Items & Tools")));
```

**After refactor** (1 line per call site, 30 sites):
```java
openScreenFromGuiState(player, GuiState.tools(), buildToolsGui(player), "§d§lMagic Items & Tools");
```

**New helper method**:
```java
private static void openScreenFromGuiState(ServerPlayerEntity player, GuiState state,
                                            SimpleInventory inv, String title) {
    STATES.put(player.getUuid(), state);
    openScreen(player, new SimpleNamedScreenHandlerFactory(
        (s, pi, p) -> new CbScreenHandler(s, pi, inv),
        Text.literal(title)));
}
```

**30 call sites affected** (all `open*` methods in GuiManager — counted via `STATES.put` occurrences):
- `openToolsGui`, `openMain`, `openEditorPicker`, `openEditor`, `openFaceEditor`, `openShapeEditor`, `openSearchPicker`, `openMaintenanceMenu`, `openAssistantControl`, `openHelpGui`, `openPropertiesGui`, `openSoundMenu`, `openTabIconPicker`, `openResourceHub`, `openBrokenBlocks`, `openMagicItemsGui`, `openConfigWarningGui`, `openConfigGui`, `openUndoPicker`, `openHelpCategory`, `openAnimGui`, `openAnimConfirmAbandon`, `reopenAnimGui`, `openBulkDelete`, plus overloads and internal re-opens

**What this fixes**:
- ~90 lines of boilerplate removed
- Screen opening logic centralized — changes to the `REOPENING_SCREENS` guard or `STATES` management only need to happen in one place
- Reduces risk of future bugs from inconsistent copy-paste

**What stays the same**:
- Every screen opens and behaves identically
- Back-stack navigation unchanged
- No visual or behavioral difference for users

**Risk**: ZERO — pure refactor, no behavior change.

**Build & test**: After implementation, verify:
- [ ] All GUI screens open correctly
- [ ] ESC/back navigation works through the full back-stack
- [ ] No regressions in any click handler

---

### Phase 9: Video-to-Texture Support (MP4/MOV)

**What changes**: Users can paste a direct `.mp4` or `.mov` file URL. The mod downloads the video, extracts frames, and converts them into an animated block texture — the same PNG strip format already used for animated GIFs.

**Library**: [jcodec](https://github.com/jcodec/jcodec) — the only pure-Java MP4/H264 decoder that exists.

| | Details |
|---|---|
| Size | `jcodec.jar` = 2.0 MB, `jcodec-javase.jar` = 13 KB |
| Codecs | H.264 (covers 90%+ of MP4 files), MPEG-2, ProRes |
| Containers | MP4, MOV, MKV |
| License | FreeBSD (permissive, fine for distribution) |
| Dependencies | Zero — pure Java, no native code |

**Why jcodec and not something smaller**:

| Alternative | Size | Why it doesn't work |
|---|---|---|
| h264j | ~400 KB | Only decodes raw .264 streams — can't open .mp4 files |
| mp4parser | ~300 KB | Only parses the container — can't decode frames into images |
| h264j + mp4parser | ~700 KB | Both unmaintained, LGPL license, requires glue code |
| JavaCV / FFmpeg | 50-100 MB | Way too big |
| jcodec | 2.0 MB | ✅ Only complete pure-Java solution |

**How it works**:

```
User pastes .mp4 or .mov URL
  │
  ├─ Download the file
  ├─ jcodec opens MP4 container, reads H264 video track
  ├─ Extract frames (cap at ~200 frames max to prevent huge textures)
  ├─ Resize each frame to block texture size (default 128×128, max 256×256)
  ├─ Stack frames into vertical PNG strip (same format as animated GIF)
  ├─ Generate .mcmeta with frame timings
  └─ Store as animated block texture — identical to how GIFs work today
```

**Files modified**: `build.gradle`, `ImageProcessor.java`

**Exact changes**:

1. **`build.gradle`** — add 2 dependencies:
   ```gradle
   include implementation("org.jcodec:jcodec:0.2.5")
   include implementation("org.jcodec:jcodec-javase:0.2.5")
   ```

2. **`ImageProcessor.java`** — add video detection and frame extraction:
   - Detect MP4/MOV by file header (ftyp box: bytes 4-7 = `ftyp`)
   - Use `FrameGrab.createFrameGrab(channel)` to open the video
   - Extract up to 200 frames, resize to target size
   - Build vertical PNG strip + mcmeta (reuse existing animation strip logic)
   - Cap video length (e.g. first 10 seconds) to prevent abuse

**Safeguards**:
- Max 200 frames extracted (prevents memory abuse)
- Max 10 seconds of video processed
- Max 5 MB download size for video files
- Processing runs on background thread (same as current image processing)

**JAR size impact**:
```
Cleaned mod (after Phases 5-8):    ~270 KB
jcodec:                           +2,013 KB
                                  ──────────
Final:                            ~2,283 KB (2.2 MB)
```

**What this does NOT support**:
- YouTube, TikTok, X/Twitter links (these platforms block direct video downloads — no reliable way to extract the video)
- Audio (Minecraft textures are silent — only the visual frames are used)
- Videos longer than 10 seconds (capped to prevent abuse)

**Risk**: MEDIUM — adds a significant dependency (2 MB). jcodec is pure Java and stable, but video processing is inherently heavier than image processing. The frame extraction runs on a background thread to avoid blocking the server.

**Build & test**: After implementation, verify:
- [ ] Direct .mp4 URL → downloads, extracts frames, creates animated texture
- [ ] Direct .mov URL → same behavior
- [ ] Video > 10 seconds → only first 10 seconds used
- [ ] Video > 5 MB file → rejected with friendly message
- [ ] Animated texture plays correctly on the block
- [ ] Existing GIF/PNG/JPG support unchanged
- [ ] JAR size is ~2.2 MB

---

### Phase 10: Packet Chunking (Large Texture Fix)

**What changes**: Textures larger than 500 KB are automatically split into small chunks before sending over the network, and reassembled on the client. This eliminates the "packet too large" crash and the black/purple broken texture bug.

**The problem in detail**:

Minecraft has a hardcoded limit: **custom payload packets cannot exceed 1,048,576 bytes (1 MB)**. This is in vanilla Minecraft — no mod can change it without requiring a separate mod on both client and server.

The mod currently sends texture data as raw bytes inside `SlotUpdatePayload` packets. For static images (PNG/JPG), this is fine — a 128×128 PNG is ~30 KB. But for animated images (GIF), the mod stitches all frames into one tall vertical PNG strip:

| GIF | Strip dimensions | Strip PNG size | Fits in 1 MB packet? |
|-----|-----------------|---------------|---------------------|
| 10 frames × 128px | 128×1280 | ~100 KB | ✅ yes |
| 30 frames × 128px | 128×3840 | ~350 KB | ✅ yes |
| 50 frames × 128px | 128×6400 | ~500 KB | ⚠️ borderline |
| 50 frames × 256px (shift-click) | 256×12800 | ~1.5 MB | ❌ **crash** |
| 100 frames × 128px | 128×12800 | ~800 KB | ⚠️ risky |
| 100 frames × 256px (shift-click) | 256×25600 | ~3-5 MB | ❌ **crash** |

When the packet exceeds 1 MB, Minecraft kills the connection with "Payload may not be larger than 1048576 bytes." The player gets disconnected.

Even when the packet barely fits, there's a second bug: the **Rectangle tool** sends an `"add"` payload and then separate `"setface"` payloads. These go through the drip-feed queue (max 256 KB/tick). If the client's resource pack generation fires before all face texture chunks arrive, the model references texture files that don't exist on disk yet → black/purple missing texture.

**The fix — packet chunking**:

Instead of sending one giant payload, the server slices large textures into chunks (each under 500 KB) and the client reassembles them:

```
Server has a 2.5 MB animated face texture to send
  │
  ├─ Is it under 500 KB? → send as one normal SlotUpdatePayload (no change)
  │
  ├─ Is it over 500 KB? → split into chunks:
  │     ChunkedTexturePayload(transferId="abc", chunkIndex=0, totalChunks=5, data=[500KB])
  │     ChunkedTexturePayload(transferId="abc", chunkIndex=1, totalChunks=5, data=[500KB])
  │     ChunkedTexturePayload(transferId="abc", chunkIndex=2, totalChunks=5, data=[500KB])
  │     ChunkedTexturePayload(transferId="abc", chunkIndex=3, totalChunks=5, data=[500KB])
  │     ChunkedTexturePayload(transferId="abc", chunkIndex=4, totalChunks=5, data=[500KB])
  │
  ├─ Each chunk is sent through the existing drip-feed queue (respects BYTES_PER_TICK_BUDGET)
  │
  ├─ Client receives chunks, buffers them by transferId
  │     - When all 5 chunks arrive → reassemble into original 2.5 MB byte array
  │     - Process as a normal SlotUpdatePayload (same code path as today)
  │
  └─ Resource pack generation only triggers AFTER the reassembled payload is processed
     → no race condition, no missing textures
```

**New file**: `ChunkedTexturePayload.java`

```java
public record ChunkedTexturePayload(
    String transferId,    // unique ID for this transfer (UUID string)
    int    chunkIndex,    // which piece this is (0, 1, 2, ...)
    int    totalChunks,   // how many pieces total
    byte[] chunkData,     // the actual bytes for this piece
    // Metadata (only on chunk 0, so the client knows what to do after reassembly)
    String action,        // "add", "retexture", "setface", etc.
    int    slotIndex,
    String customId,
    String displayName,
    int    lightLevel,
    float  hardness,
    String soundType,
    String face,
    String animMeta
) implements CustomPayload { ... }
```

**Files modified**:

1. **`ChunkedTexturePayload.java`** (new) — the chunk payload record + codec
2. **`NetworkManager.java`** — add `sendChunked()` method:
   - Takes a `SlotUpdatePayload` with large texture
   - Splits `payload.texture()` into 500 KB chunks
   - Generates a random `transferId`
   - Enqueues each chunk into the player's drip-feed queue
   - Metadata (action, slotIndex, customId, face, etc.) is attached to chunk 0 only
3. **`NetworkManager.java`** — modify `enqueueForPlayer()`:
   - Before: if payload > 8 MB → silently drop (but Minecraft's REAL limit is 1 MB — the mod's 8 MB `PACKET_MAX_SIZE` check is useless, payloads between 1-8 MB pass the check but still crash Minecraft)
   - After: if payload > 500 KB → call `sendChunked()` instead of enqueueing directly
   - Also lower `PACKET_MAX_SIZE` from 8 MB to 900 KB to match Minecraft's 1 MB hard limit (with margin for packet overhead)
4. **`CustomBlocksClient.java`** — add chunk reassembly:
   - Register handler for `ChunkedTexturePayload`
   - Buffer incoming chunks in a `Map<String, ChunkBuffer>` keyed by `transferId`
   - When all chunks for a `transferId` arrive → concatenate bytes → create a `SlotUpdatePayload` → process through existing handler
   - **Critical: update `lastPacketTime` on EVERY incoming chunk** (not just after reassembly) — this keeps the debounce timer alive during chunk transfer, preventing premature resource pack generation
   - Timeout: if all chunks don't arrive within 30 seconds, discard the buffer
5. **`CustomBlocksMod.java`** — register the new `ChunkedTexturePayload` channel on both sides
6. **`RectangleToolItem.java`** — fix missing `animMeta` in the `"add"` broadcast:
   - Line 229-230 uses the 8-parameter `SlotUpdatePayload` constructor which sets `animMeta = null`
   - But line 211 already set `animMeta` on the server-side variant via `SlotManager.setAnimMeta(variantId, finalAnim)`
   - The client never receives the animation metadata → animated face textures render as stacked frames → black/purple
   - **Fix**: use the full 11-parameter constructor to include `fresh.animMeta`:
     ```java
     // BEFORE (broken — animMeta is null):
     new SlotUpdatePayload("add", fresh.index, variantId, variantName,
         texCopy, fresh.lightLevel, fresh.hardness, fresh.soundType)
     // AFTER (fixed — animMeta included):
     new SlotUpdatePayload("add", fresh.index, variantId, variantName,
         texCopy, fresh.lightLevel, fresh.hardness, fresh.soundType,
         null, null, fresh.animMeta)
     ```

**Safeguards**:

| Safeguard | What it prevents |
|-----------|------------------|
| Max chunk size: 500 KB | Each chunk is well under Minecraft's 1 MB limit |
| Max reassembly buffer: 10 MB per transfer | Prevents a malicious or corrupt stream from eating all client RAM |
| Max 5 concurrent transfers per player | Prevents queue flooding |
| 30-second chunk timeout | If chunks stop arriving (disconnect, lag), partial buffers are cleaned up |
| Transfer ID is a random UUID | Can't accidentally mix chunks from two different textures |
| Metadata only on chunk 0 | Reduces redundant data in later chunks |
| Existing drip-feed budget unchanged | Chunks respect the same 256 KB/tick limit — no network flooding |
| `lastPacketTime` updated per chunk | Prevents debounce from firing mid-transfer — resource pack gen waits for ALL data |
| `PACKET_MAX_SIZE` lowered to 900 KB | Matches Minecraft's real 1 MB limit — payloads that would crash now get chunked instead of silently passing |
| `animMeta` included in Rectangle broadcasts | Client receives animation metadata — animated face textures render correctly |

**How this fixes each bug**:

| Bug | Before (broken) | After (fixed) |
|-----|-----------------|---------------|
| "Packet too large" on GIF upload | 2.5 MB texture sent as one packet → Minecraft kills connection | 2.5 MB split into 5 × 500 KB chunks → all fit, connection survives |
| "Packet too large" on Rectangle + GIF | Same as above, but for `"setface"` payload | Same fix — face texture is chunked too |
| Black/purple texture after Rectangle | `"add"` triggers resource pack gen, `"setface"` arrives late + `animMeta` never sent to client | Client waits for ALL chunks before processing (debounce stays alive per-chunk) + `animMeta` now included in `"add"` broadcast → resource pack gen fires with complete data and correct animation metadata |
| High-quality (shift-click) GIF crash | 256px × 100 frames = 3-5 MB → instant disconnect | Chunked into 6-10 pieces → arrives safely |

**What stays the same**:
- Small textures (< 500 KB) still send as a single `SlotUpdatePayload` — zero overhead
- The drip-feed queue, tick budget, and cooldown logic are unchanged
- All image processing (resize, animation, background removal) is unchanged
- The HTTP resource pack server is unchanged
- `SlotUpdatePayload` format is unchanged — the chunking wraps around it
- Join sync flow is unchanged (full sync still uses the same drip-feed)

**Why not just use the HTTP resource pack server instead?**:
- Requires **port 8080** to be open — doesn't work on shared hosting (Aternos, mcserverhost, etc.)
- `rpEnforceOnJoin` is currently disabled due to stability issues
- Requires the player to accept a download prompt every time a texture changes
- Packet chunking works on **every server, every host, no ports needed, no prompts**

**Risk**: MEDIUM — this adds a new payload type and client-side reassembly logic. However:
- The chunking is a simple byte-array split (no compression, no encoding)
- The reassembly is a simple byte-array concatenation
- The existing `SlotUpdatePayload` handler is reused after reassembly — no new processing logic
- If chunking breaks, small textures (< 500 KB) still work perfectly via the old path

**Build & test**: After implementation, verify:
- [ ] Small static image (PNG < 500 KB) → sends as single packet (no chunking)
- [ ] Large static image (PNG > 500 KB, e.g. shift-click 256px) → chunked, arrives correctly
- [ ] Short GIF (10 frames × 128px, ~100 KB) → sends as single packet
- [ ] Long GIF (100 frames × 128px, ~800 KB) → chunked, arrives correctly
- [ ] High-quality GIF (100 frames × 256px, ~3 MB) → chunked, arrives correctly
- [ ] Rectangle tool + GIF link → face texture chunked, block displays correctly (no black/purple)
- [ ] Rectangle tool + GIF link → animation plays correctly (animMeta received by client)
- [ ] Rectangle tool + PNG link → face texture unchunked, block displays correctly
- [ ] Debounce does NOT fire while chunks are still arriving (check logs for generation timing)
- [ ] Multiple players online → each gets their own chunks, no cross-contamination
- [ ] Player disconnects mid-transfer → partial buffer cleaned up after 30 seconds
- [ ] Join sync with 400+ blocks → all textures arrive via drip-feed (chunks interleaved)
- [ ] No "Payload may not be larger than 1048576 bytes" error in any scenario
- [ ] `./gradlew build` passes with zero errors

---

## Implementation Order

```
Phase 10 ──→ Packet chunking (fixes "packet too large" + black/purple texture)
Phase 5  ──→ 1 line, zero risk, instant win (drip-feed speed)
Phase 6  ──→ Remove TwelveMonkeys, add proxy fallback (saves 433 KB)
Phase 7  ──→ Remove duplicate client GUIs (saves 25 KB + 1,168 lines)
Phase 8  ──→ GuiManager openScreen refactor (cleaner code)
Phase 1  ──→ Core fix: separate texture files (eliminates 200MB saves)
Phase 2  ──→ Single-slot client update (eliminates 2000-file rewrite)
Phase 3  ──→ Smart join sync (eliminates redundant drip-feed)
Phase 4  ──→ Optional: server RAM reduction (only if needed)
Phase 9  ──→ Video support via jcodec (MP4/MOV → animated textures)
```

Each phase is **independently useful** and **independently revertable**. If Phase 2 breaks something, Phase 1 still works.

---

## Safety Guarantees

1. **No data loss** — Old `slots.json` with inline textures is ALWAYS readable. Migration is automatic. Backup rotation (bak1/bak2/bak3) is untouched.
2. **Phase 10 is the only phase that changes networking** (besides Phase 3). Phases 1, 2, 5, 6, 7, 8, and 9 don't touch networking at all. Phase 10 is backwards-compatible — small payloads (< 500 KB) still use the old single-packet path. Phase 10 is first in the implementation order because it fixes active crashes.
3. **Build after EVERY edit** — not after every phase, after EVERY individual edit.
4. **One file at a time** — each edit touches exactly one file.
5. **No git push without your explicit command.**
6. **Git commit before every phase** — so any phase can be reverted instantly.

---

## Rollback Protocol

If any phase breaks something, tell the AI (Cascade or any other):

> "Phase [X] broke [describe what's wrong]. Revert it."

The AI should:
1. Run `git log --oneline -10` to find the commit before the phase
2. Run `git revert` or `git reset --hard` to undo it
3. Build and verify the problem is gone

**Every phase is independent.** Reverting Phase 6 does not affect Phase 5 or Phase 7. They don't depend on each other.

| Phase | How to revert |
|-------|---------------|
| Phase 5 | Change one number back in `NetworkManager.java` |
| Phase 6 | Re-add TwelveMonkeys to `build.gradle`, remove proxy code from `ImageProcessor.java` |
| Phase 7 | Restore `CustomBlocksScreen.java` from git, re-add keybind + `ClientTickEvents` handler in `CustomBlocksClient.java` |
| Phase 8 | Expand `openScreenFromGuiState` calls back to inline code (git revert) |
| Phase 9 | Remove jcodec from `build.gradle`, remove video code from `ImageProcessor.java` |
| Phase 10 | Delete `ChunkedTexturePayload.java`, revert changes in `NetworkManager.java`, `CustomBlocksClient.java`, `CustomBlocksMod.java`, and `RectangleToolItem.java` — old 8 MB limit restored, `animMeta` fix reverted |
| Phases 1-4 | Each modifies one file — git revert the commit |

---

## What Each Phase Fixes (Summary)

| Symptom | Phase that fixes it |
|---------|-------------------|
| Server OOM crash when creating blocks | Phase 0 ✅ (done) + Phase 1 |
| 20-second freeze after triangle | Phase 2 |
| 19-second join time | Phase 3 + Phase 5 |
| High server RAM usage | Phase 1 + Phase 4 |
| Slow saves to disk | Phase 1 |
| Unnecessary ZIP rebuilds | Phase 1 (metadata-only JSON means pack rebuild is skipped when rpEnforceOnJoin=false) |
| JAR file too large (730 KB) | Phase 6 (saves 433 KB) + Phase 7 (saves 18 KB) |
| Duplicate/dead code | Phase 7 (807 lines removed) + Phase 8 (90 lines cleaned up) |
| No video/MP4 support | Phase 9 (MP4/MOV → animated block textures via jcodec) |
| "Packet too large" crash on GIF/large images | Phase 10 (auto-chunking for payloads > 500 KB) |
| Black/purple broken texture after Rectangle tool | Phase 10 (client waits for all chunks before resource pack gen) |
