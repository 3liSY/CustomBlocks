tbat# CRITICAL FIXES PLAN - CustomBlocks

## Executive Summary
This plan addresses 4 critical issues:
1. **GIF loading failure** (2+ weeks, persistent)
2. **Server crash when changing face to GIF** (critical stability)
3. **Export code format wrong** (`CB3!` → `CB~`, duplicate output)
4. **GUI layout messy** (poor spacing, visual clutter)

---

## Issue 1: GIF Processing Overhaul

### Current Problems
- `ImageProcessor.processAnimation()` uses unreliable ImageIO GIF decoding
- No disposal method handling (causes frame bleeding/corruption)
- No memory limits (OOM crashes on large GIFs)
- Face textures with GIF bypass animation pipeline
- Synchronous processing blocks server thread

### Root Cause
```java
// Current code extracts frames but doesn't handle:
// - Graphic Control Extension (disposal method)
// - Frame bounds smaller than canvas
// - Memory safety limits
```

### The Fix

#### Step 1: Add GIF Metadata Parser
**File:** `ImageProcessor.java`
**New Method:** `parseGifMetadata()`
- Handle disposal methods: 0=undefined, 1=none, 2=restoreToBackground, 3=restoreToPrevious
- Validate frame count against MAX_FRAMES (100)

#### Step 2: Implement Safe Frame Extraction
**File:** `ImageProcessor.java`  
**Modify:** `processAnimation()`
```java
// Add safety guards:
- Max frames: 100
- Max dimensions: 512x512 per frame  
- Max total pixels: 512 * 512 * 100 = 26M pixels
- Timeout: 30 seconds
- Memory pre-check: Runtime.freeMemory()
```

#### Step 3: Fix Face Texture Pipeline
**File:** `SlotManager.java`, `GuiManager.java`
**Root Cause:** Face textures call `ImageProcessor.download()` directly, not `downloadAndProcess()`
**Fix:** Route all face texture downloads through `downloadAndProcess()` to ensure GIF handling

#### Step 4: Add Async GIF Processing
**File:** `CustomBlockCommand.java`, `GuiManager.java`
- Move GIF processing off main thread
- Add progress feedback for large files
- Handle timeout gracefully

---

## Issue 2: Server Crash on Face GIF Change

### Investigation Points
Crash likely occurs in:
1. `SlotManager.setFaceTexture()` - concurrent modification
2. `ImageProcessor.processAnimation()` - OOM
3. `NetworkManager.broadcastUpdate()` - packet size overflow
4. `SlotData.withFaceTexture()` - null texture handling

### Fix Tasks

#### Task 2.1: Add Null Safety
**File:** `SlotData.java:168-173`
```java
// Current:
public SlotData withFaceTexture(String face, byte[] tex) {
    Map<String, byte[]> newFaces = new ConcurrentHashMap<>(faceTextures);
    newFaces.put(face, tex.clone()); // CRASH: tex could be null!
    ...
}
```
**Fix:** Add null check before `.clone()`

#### Task 2.2: Add Packet Size Validation
**File:** `NetworkManager.java`
- Check payload size before broadcasting
- Split large face textures across multiple packets
- Log warning if texture > 500KB

#### Task 2.3: Add SlotManager Safety
**File:** `SlotManager.java`
- Synchronize `setFaceTexture()` properly
- Validate texture data before storing
- Check for null/empty arrays

---

## Issue 3: Export Code Format Fix

### Current Output (Lines 634-641 in CustomBlockCommand.java)
```java
String code = "CB3!" + hash;
ChatHelper.success(src, "Export code for '§f" + d.customId + "§a':");
src.sendMessage(Text.literal("§7Import with: §b/cb importblock " + code));  // NOT CLICKABLE - REMOVE
net.minecraft.text.MutableText msg = Text.literal("§b§n" + code)  // CLICKABLE
    .styled(s -> s
        .withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.COPY_TO_CLIPBOARD, code))
        .withHoverEvent(new net.minecraft.text.HoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT, Text.literal("§eClick to copy code"))));
src.sendMessage(msg);
```

### Fixed Output
```java
String code = "CB~" + hash;  // Changed from CB3! to CB~
MutableText clickable = Text.literal("§b§n" + code)
    .styled(s -> s
        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("§eClick to copy"))));
ChatHelper.success(src, Text.literal("§a[Share] §fBlock '") + d.customId + "' ready! " + clickable);
```

### Also Update Import Validation
**File:** `CustomBlockCommand.java:650`
```java
// Change from:
if (!code.startsWith("CB!") && !code.startsWith("CB2!") && !code.startsWith("CB3!"))
// To:
if (!code.startsWith("CB!") && !code.startsWith("CB2!") && !code.startsWith("CB3!") && !code.startsWith("CB~"))
```

---

## Issue 4: GUI Layout Cleanup

### Current Problems
- No visual breathing room between sections
- Inconsistent slot spacing
- Glass panes fill all empty slots (cluttered)
- Action buttons not grouped visually

### Layout Principles
1. **Section breaks:** 1-2 row gaps between functional groups
2. **Visual hierarchy:** Title → Actions → Navigation
3. **Consistent padding:** Same slot count between related items
4. **Group containment:** Related actions in same row/column cluster

### Specific Fixes

#### Main GUI (`buildMain`)
**Current:** Items crammed in rows 1-3 with no spacing
**Fixed:** 
- Row 0: Title + back button
- Row 2: Primary actions (spaced by 1 slot)
- Row 4: Secondary actions  
- Row 6: Navigation
- Fill remaining with invisible gray panes (no name)

#### Block Editor (`buildEditor`)
**Current:** All 54 slots filled densely
**Fixed:**
- Center column (slot 4, 13, 22, 31, 40): Main display item
- Left cluster (col 0-2): Navigation & block ops
- Right cluster (col 6-8): Texture & animation
- Bottom row: Danger actions (delete) separated

#### Assistant Control (`buildAssistantControl`)
**Current:** Slots 19-25 packed in one row
**Fixed:**
- Slot 4: Status display (center top)
- Row 2 (slots 11-15): Core controls with 1-slot gaps
- Row 4 (slots 29-33): Secondary actions
- Row 5: Style presets

---

## Implementation Order

### Phase 1: Critical Stability (Do First)
1. Fix `SlotData.withFaceTexture()` null safety
2. Add `SlotManager.setFaceTexture()` validation
3. Add packet size check in `NetworkManager`

### Phase 2: GIF Processing
4. Implement GIF metadata parser
5. Add disposal method handling
6. Add memory safety limits
7. Fix face texture pipeline to use `downloadAndProcess()`

### Phase 3: Polish
8. Fix export code format (`CB3!` → `CB~`)
9. Remove duplicate export message
10. Update import to accept `CB~`
11. Clean up GUI layouts

---

## Testing Checklist

### GIF Tests
- [ ] Upload 10-frame GIF to main texture → Works
- [ ] Upload 100-frame GIF → Should cap at 100 frames with warning
- [ ] Upload 1024x1024 GIF → Should resize with warning
- [ ] Set GIF as face texture (top) → Works without crash
- [ ] Set GIF as face texture while server under load → No crash
- [ ] Cancel GIF upload mid-processing → Graceful abort

### Export Tests
- [ ] `/cb exportblock test` → Shows `CB~xxx` (not CB3!)
- [ ] Click code → Copies to clipboard
- [ ] Only ONE message printed (not two)
- [ ] `/cb importblock CB~xxx` → Works
- [ ] `/cb importblock CB3!xxx` → Still works (backward compat)

### GUI Tests
- [ ] `/cb gui` → Main menu has visible spacing
- [ ] Open block editor → Actions grouped logically
- [ ] AI Assistant GUI → Clean layout, no crowding
- [ ] Config GUI → Warning screen clean, config screen organized

---

## Success Criteria
1. **GIF files load reliably** without visual corruption
2. **No server crashes** when setting face textures
3. **Export shows `CB~`** with single clickable message
4. **GUIs have breathing room** - visually organized
5. **All legacy codes** (`CB!`, `CB2!`, `CB3!`) still import

---

## Files to Modify
1. `ImageProcessor.java` - GIF processing overhaul
2. `SlotData.java` - Null safety
3. `SlotManager.java` - Validation & thread safety
4. `CustomBlockCommand.java` - Export format, import validation
5. `GuiManager.java` - GUI layouts, face texture pipeline
6. `NetworkManager.java` - Packet size validation
il.ArrayList;
```
(`SlotData` is already imported at line 7.)

**Verified method signatures (all exist in `SlotManager.java`):**
- `clearAll()` — line 331 (will be REMOVED from call site)
- `getById(String customId)` — line 139, returns `SlotData`
- `allSlots()` — line 171, returns `Collection<SlotData>`
- `remove(String customId)` — line 317, returns `SlotData`
- `assignAtIndex(int index, String customId, String displayName, byte[] texture)` — line 261
- `setProperties(String id, int light, float hard, String sound)` — line 406
- `setAnimMeta(String id, String meta)` — line 377

**Verified `FullSyncPayload.SlotEntry` fields (line 22-31 of `FullSyncPayload.java`):**
- `index()` — int
- `customId()` — String
- `displayName()` — String
- `texture()` — byte[] (always null in full sync)
- `lightLevel()` — int
- `hardness()` — float
- `soundType()` — String
- `animMeta()` — String

#### Edge Cases
| Scenario | What happens |
|---|---|
| **Returning player, nothing changed** | Merge keeps all 439 textures. `computeTextureHash()` matches cached hash. `packExists` now correctly checks `resourcepacks/` path → true. Cache HIT → **instant join, no loading screen.** |
| **Returning player, 3 blocks retextured** | Merge keeps 436, drip-feed delivers 3 new. Cache MISS → regenerate once. |
| **Returning player, 5 blocks deleted on server** | Step 2 removes 5 stale, keeps 434. Cache MISS → regenerate once. |
| **New player, no local cache** | All 439 arrive via drip-feed (same as current). |
| **Player disconnects mid-drip-feed, reconnects** | Partial cache preserved, drip-feed fills gaps. |
| **Block re-IDed on server** | Old ID removed in step 2, new ID created in step 3. |
| **Slot index reassigned** | Step 3 detects `existing.index != e.index()`, removes and re-assigns with cached texture. |
| **maxSlots lowered (e.g. 2048→500)** | Old stale files for slots 500-2048 remain on disk. `ResourcePackGenerator.generate()` only writes 0-499 on next run. Stale PNGs cause "Corrupt PNG" errors on reload. **Mitigation:** user should delete `resourcepacks/customblocks_generated` folder after changing maxSlots. |

#### Verification
**Server log:**
```
[CustomBlocks] Received sync request from 3liSY          ← Fix 7: client-initiated
[CustomBlocks] Drip-feed queued for 3liSY: 439 textures  ← drip-feed still runs
```
**Client log (returning player):**
```
[CustomBlocks] Texture cache HIT (hash=a1b2c3d4e5). Skipping generation + reload.
```
**Client log (new player):**
```
[CustomBlocks] Texture cache MISS (cur=a1b2, cached=null, packExists=false). Regenerating.
```

---

### Fix 3 (HIGH): Optimize the safety check in `saveAllAsync`

**File:** `SlotManager.java` lines 645-719

**Problem:** Every save reads the ENTIRE existing `slots.json` (30-50MB) via `Files.readString()` + `JsonParser.parseString()` to count textured slots. Creates ~60-100MB of temporary heap allocations per save. On shared hosting with 512MB-1GB RAM, doing this rapidly (e.g. creating 20 blocks in a row) triggers an OOM timeout and kicks players.

**Fix:** Do NOT remove the safety check (violates Layered Defense). Instead, make it cost zero memory:
- When saving `slots.json`, also write a tiny `textured_count.txt` containing just an integer (e.g., `428`).
- On the *next* save, read `textured_count.txt` (a 3-byte file) instead of parsing the 50MB JSON.
- If `textured_count.txt` is missing, fallback to parsing the JSON once to recreate it.

**Impact:** Keeps the catastrophic data loss protection, but reduces memory usage during saves from 60-100MB down to literally 0MB. Allows the server to survive extreme pressure (20+ rapid saves).

---

### Fix 4 (HIGH): Fix `/cb reload` — don't block server thread

**File:** `CustomBlockCommand.java`

**Problem:** `cmdReload` calls `loadAll()` synchronously on the server thread. `loadAll()` reads and parses a 30-50MB JSON file, blocking ticks for 10+ seconds. Also race condition: `flushSave()` is async but `loadAll()` reads immediately after.

**Fix:**
- Submit the entire reload sequence to `IO_EXECUTOR`:
  1. Wait for pending save to complete
  2. Read and parse `slots.json` off the server thread
  3. Then `server.execute(() -> { /* swap maps + broadcastFullSync */ })`
- Send "Reloading..." chat message immediately, "Done" on completion

**Impact:** Prevents `/cb reload` from crashing/timing out players.

---

### Fix 6 + Fix 8 (MEDIUM): Data corruption cleanup

**Fix 6 — Duplicate slot_316:**
`SlotManager.java` — Two customIds (`_atest` and `zomoruda`) share index 316. In `assignAtIndex()` (line 261), existing duplicates are already handled. One-time manual cleanup: remove `_atest` from `slots.json`.

**Fix 8 — Sanitize `&` in input:**
`GuiManager.java` — Rename converts `&` → `§`. Re-ID converts `&atest` → `_atest`. Strip `§` and `&` from display names. Validate resulting ID isn't empty or a single `_`.

---

### Fix 11 (MEDIUM): Universal block recoloring — remove "black" name requirement

**Files:** `ColorSquareItem.java`, `ColorTriangleItem.java`

**Problem:** The Color Square and Color Triangle tools currently only recognize a block as recolorable if its name or ID contains "black" (the assumed base color). This means players cannot right-click and recolor blocks that were created without "black" in their identifier.

**Expected behavior:** ANY custom block should be treated as recolorable — the tools should apply the selected color regardless of the block's name/ID. If a block has no color keyword in its name, treat it as if it were "black" (the default/base color).

**Fix:**
- Remove or bypass the "black" name/ID check in both `ColorSquareItem` and `ColorTriangleItem`
- When the target block has no recognized color in its name, default to treating it as `black` (base color)
- This allows right-clicking any custom block to apply a color overlay or change its background

**Impact:** All custom blocks become recolorable on right-click, not just those named with "black." Significantly improves creative workflow.

---

### Fix 10 (LOW): Rename generated resource pack to "CustomBlocks"

**4 locations (2 files):**
| File | Line | Current | New |
|------|------|---------|-----|
| `ResourcePackGenerator.java` | 41 | `"resourcepacks/customblocks_generated"` | `"resourcepacks/CustomBlocks"` |
| `CustomBlocksClient.java` | 44 | `"file/customblocks_generated"` | `"file/CustomBlocks"` |
| `CustomBlocksClient.java` | 357 | `"resourcepacks/customblocks_generated/assets"` | `"resourcepacks/CustomBlocks/assets"` |
| `CustomBlocksClient.java` | 424 | `"resourcepacks/customblocks_generated"` | `"resourcepacks/CustomBlocks"` |

**Note:** Lines 357 and 424 will already be fixed to include the `resourcepacks/` prefix in Fix 9. Fix 10 just changes the folder name itself.

**User action required:** Delete old `resourcepacks/customblocks_generated` folder after updating.

---

## Implementation Order

```
Fix 2 ✅ DONE
Fix 1 ✅ DONE
Fix 5 ✅ DONE
    ↓
Fix 7 + Fix 9  →  build + test
    ↓
    First-attempt join + instant returning joins + path bug fixes
    ↓
Fix 3  →  build + test
    ↓
    Saves 60-100MB peak memory per save
    ↓
Fix 4  →  build + test
    ↓
    /cb reload no longer freezes server
    ↓
Fix 6 + Fix 8  →  build + test
    ↓
    Data corruption cleanup
    ↓
Fix 10  →  build + test
    ↓
    Rename to "CustomBlocks"  →  DONE
```

Each fix is a single surgical edit. Build after EVERY edit. No commits or pushes without explicit approval.

---

## Files to Modify

| File | Fixes | Changes |
|---|---|---|
| `SyncRequestPayload.java` | 7 | **NEW** — C2S marker payload for client-initiated sync |
| `CustomBlocksMod.java` | 7 | Register `SyncRequestPayload` C2S type + handler |
| `NetworkManager.java` | 7 | Remove `sendFullSync(player)` from `onPlayerJoin` (line 212) |
| `CustomBlocksClient.java` | 7, 9 | Send `SyncRequestPayload` on join; replace `clearAll()` with smart merge; fix 2 wrong paths (lines 357, 424) |
| `SlotManager.java` | 3, 6 | Remove safety check (lines 645-719); manual cleanup of `_atest` |
| `CustomBlockCommand.java` | 4 | Move `/cb reload` off server thread |
| `GuiManager.java` | 8 | Sanitize `&`/`§` in rename/re-id |
| `ResourcePackGenerator.java` | 10 | Rename folder path (line 41) |

---

## Testing Checklist

### Join Flow (Fix 7 + Fix 9)
- [ ] First join attempt works (no Pipeline error)
- [ ] Returning player: cache HIT → no loading screen
- [ ] New player: all textures arrive via drip-feed → pack generates → blocks display correctly
- [ ] Player disconnects mid-drip-feed, reconnects: partial cache preserved, gaps filled
- [ ] Server log shows `Received sync request from PlayerName`
- [ ] Client log shows `Texture cache HIT` for returning players
- [ ] Animation mcmeta changes apply immediately (path fix verified)

### Triangle Spam (Fix 1 ✅ + Fix 2 ✅)
- [ ] Create 6 blocks with triangles → server stays alive
- [ ] Only 1 ZIP build runs at a time
- [ ] Memory stays under 1GB

### /cb reload (Fix 4)
- [ ] Run `/cb reload` with 2 players online → no crash
- [ ] Players don't get timed out during reload
- [ ] Blocks correctly re-synced after

### Stability & Pressure Handling
- [ ] Server runs for 30+ minutes with active block editing → no crash
- [ ] No "Duplicate SlotData" warnings after Fix 6
- [ ] **Extreme Stress Test:** Create 20+ blocks rapidly using triangles. Server MUST queue them cleanly, memory MUST stay stable, and players MUST NOT be kicked/timed out.

---

## Success Criteria
1. **Join works on first attempt** — no Pipeline errors, no reconnecting
2. **Returning players join instantly** — cache HIT, no loading screen
3. **Server survives EXTREME pressure** (20+ simultaneous blocks without crashing or timing out)
4. **`/cb reload` works** without freezing or timing out players
5. **New players see textures** on first join (not purple/black)
6. **Memory stays under 1GB** during saves and ZIP generation
7. **No data corruption** warnings in logs
