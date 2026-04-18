# CRITICAL FIXES PLAN - CustomBlocks

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
- Extract frame delays from Graphic Control Extension
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
