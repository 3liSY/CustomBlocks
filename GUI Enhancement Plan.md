# GUI Enhancement & Stability Master Plan — CustomBlocks

> **Date:** April 19, 2026  
> **Mod:** CustomBlocks v1.0.0 — Fabric 0.18.6 / MC 1.21.1  
> **Directive:** Follows `customblocks_master_directive.md` — Layered Defense, Atomicity, Immutability, Royal UX

---

## Executive Summary

After deep-diving into every relevant source file, **11 confirmed issues** have been identified across 5 categories. This plan addresses each with root-cause analysis, surgical fix strategy, and defensive safeguards.

### Files Analyzed
- `GuiManager.java` (2753 lines) — Server-side GUI engine
- `AnimBlockScreen.java` (361 lines) — Client-side animation settings screen
- `CbScreenHandler.java` (77 lines) — Screen handler with ESC/close logic
- `GuiState.java` (141 lines) — Immutable GUI state records
- `GuiMode.java` (31 lines) — GUI mode enum
- `AnvilPromptManager.java` (168 lines) — Anvil input system
- `SlotManager.java` (1051 lines) — Core data management
- `SlotData.java` (213 lines) — Immutable block data
- `CustomBlocksMod.java` (599 lines) — Main mod entry, AnimSettings handler
- `SlotBlock.java` — Block right-click handler

---

## Phase 1: Critical Bugs (Zero-Tolerance)

### 1.1 — BUG: Collision Toggle Double-Negation (BROKEN — 2 locations)

**Severity:** CRITICAL — The collision toggle **literally does nothing**. Clicking it appears to work (GUI refreshes, message sent) but the underlying data never changes.

**Root Cause:**  
`SlotManager.setCollision(String id, boolean collision)` internally calls `d.withNoCollision(!collision)` — it already negates the input.

The callers pass `!d.noCollision`, creating a **double-negation** that cancels itself out:

```
d.noCollision = false (hitbox ON)
  → setCollision(id, !false) = setCollision(id, true)
  → withNoCollision(!true) = withNoCollision(false)
  → noCollision stays false → NO CHANGE

d.noCollision = true (hitbox OFF)
  → setCollision(id, !true) = setCollision(id, false)
  → withNoCollision(!false) = withNoCollision(true)
  → noCollision stays true → NO CHANGE
```

**Location 1 — Shape Editor (line 1572):**
```java
// FILE: GuiManager.java:1572
// CURRENT (BROKEN):
SlotManager.setCollision(id, !d.noCollision);
// FIX:
SlotManager.setCollision(id, d.noCollision);
```

**Location 2 — Properties Menu (line 1687):**
```java
// FILE: GuiManager.java:1687
// CURRENT (BROKEN):
SlotManager.setCollision(id, !d.noCollision);
// FIX:
SlotManager.setCollision(id, d.noCollision);
```

**Proof of fix:**
```
d.noCollision = false (hitbox ON, user wants to turn OFF)
  → setCollision(id, false)
  → withNoCollision(!false) = withNoCollision(true)
  → noCollision = true → hitbox OFF ✓

d.noCollision = true (hitbox OFF, user wants to turn ON)
  → setCollision(id, true)
  → withNoCollision(!true) = withNoCollision(false)
  → noCollision = false → hitbox ON ✓
```

**GUI display logic is CORRECT — no change needed there:**
```java
// GuiManager.java:2483 — already correct
inv.setStack(8, d.noCollision
    ? uiGlint(Items.BARRIER, "§c⊘ Hitbox: §lOFF", ...)
    : uiGlint(Items.SLIME_BLOCK, "§a✔ Hitbox: §lON", ...));
```

**Risk:** Minimal — single-character change per location.

---

### 1.2 — BUG: Anim GUI Chat Message Always Says "FPS Updated"

**Severity:** HIGH — Misleading feedback when toggling smooth blending.

**Root Cause:**  
`applyAnimSettings()` always sends one hardcoded message regardless of what changed:

```java
// GuiManager.java:1885
ChatHelper.success(player, "Animation speed updated for '§f" + d.displayName + "§a' (" + String.format("%.1f", fps) + " fps)");
```

If the user only toggled smooth blending (interpolation), the message incorrectly says "Animation speed updated."

**Fix — Context-Aware Messages:**

Modify `applyAnimSettings()` to accept the **original** FPS and interp values, then compare:

```java
// Step 1: Store original params when opening the GUI
// In openAnimGui(), already done: ANIM_PARAMS.put(uuid, new AnimParams(...))
// ADD: ANIM_ORIGINAL_PARAMS.put(uuid, new AnimParams(...))

// Step 2: In handleAnimGuiClick case 49 (Save & Apply):
// Pass original params to applyAnimSettings

// Step 3: In applyAnimSettings, build context-aware message:
AnimParams original = ANIM_ORIGINAL_PARAMS.remove(player.getUuid());
boolean fpsChanged = original == null || Math.abs(original.fps() - fps) > 0.05f;
boolean interpChanged = original == null || original.interpolate() != interp;

if (fpsChanged && interpChanged) {
    ChatHelper.success(player, "Animation updated for '§f" + d.displayName 
        + "§a' (§f" + String.format("%.1f", fps) + " fps§a, blending §f" 
        + (interp ? "ON" : "OFF") + "§a)");
} else if (fpsChanged) {
    ChatHelper.success(player, "Animation speed updated for '§f" + d.displayName 
        + "§a' (§f" + String.format("%.1f", fps) + " fps§a)");
} else if (interpChanged) {
    ChatHelper.success(player, "Smooth blending §f" + (interp ? "enabled" : "disabled") 
        + "§a for '§f" + d.displayName + "§a'");
} else {
    ChatHelper.success(player, "Animation settings saved for '§f" + d.displayName + "§a' (no changes)");
}
```

**New field required:**
```java
private static final Map<UUID, AnimParams> ANIM_ORIGINAL_PARAMS = new ConcurrentHashMap<>();
```

**Cleanup:** Remove from `ANIM_ORIGINAL_PARAMS` in `onPlayerDisconnect()` and after apply.

---

## Phase 2: Navigation & Back Button Reliability

### 2.1 — AnimGui Loses returnPage (Always Returns to Page 0)

**Root Cause:**  
`GuiState.animGui(id)` hardcodes `page = 0`:

```java
// GuiState.java:70-72
public static GuiState animGui(String editingId) {
    return new GuiState(GuiMode.ANIM_GUI, editingId, 0, false, 0, false);
}
```

And `openAnimGui()` doesn't accept a `returnPage`:

```java
// GuiManager.java:1807
public static void openAnimGui(ServerPlayerEntity player, String id) {
```

**Impact:** When returning from AnimGui → Editor → Picker, the picker always opens at page 0 instead of the page the user was on.

**Fix:**
1. Add `returnPage` to `animGui` factory:
   ```java
   public static GuiState animGui(String editingId, int returnPage) {
       return new GuiState(GuiMode.ANIM_GUI, editingId, returnPage, false, 0, false);
   }
   ```
2. Add `returnPage` to `openAnimGui`:
   ```java
   public static void openAnimGui(ServerPlayerEntity player, String id, int returnPage) {
       // ... existing code ...
       STATES.put(player.getUuid(), GuiState.animGui(id, returnPage));
       // ...
   }
   ```
3. Update all callers:
   - `handleEditorClick` slot 31: `openAnimGui(player, id, rp)`
   - `SlotBlock.onUse`: `GuiManager.openAnimGui(sp, data.customId, 0)` (no return page context)
   - `restoreState` case ANIM_GUI: `openAnimGui(player, state.editingId(), state.page())`
4. In `handleAnimGuiClick` slots 0, 45: `openEditor(player, id, state.page())` — now `state.page()` carries the correct returnPage.

---

### 2.2 — AnimGui Missing `pushBackStack` Call

**Root Cause:**  
`openAnimGui()` does NOT call `pushBackStack()` before setting the new state:

```java
// GuiManager.java:1807-1837
public static void openAnimGui(ServerPlayerEntity player, String id) {
    // ... parse data ...
    ANIM_PARAMS.put(player.getUuid(), new AnimParams(fps, interp, frameCount));
    STATES.put(player.getUuid(), GuiState.animGui(id));  // ← no pushBackStack!
    openScreen(player, ...);
}
```

Every other `open*` method calls `pushBackStack()` first. Without it, ESC from AnimGui doesn't navigate back to the Editor — it jumps to whatever stale state was on the stack (Picker, Main, or nothing).

**Fix:** Add `pushBackStack(player.getUuid());` as the first line after the null check:

```java
public static void openAnimGui(ServerPlayerEntity player, String id, int returnPage) {
    SlotData d = SlotManager.getById(id);
    if (d == null || !d.isAnimated()) return;
    pushBackStack(player.getUuid());  // ← ADD THIS
    // ... rest of method ...
}
```

---

### 2.3 — `handleEscBack` Aggressively Clears Stack After One Pop

**Root Cause:**  
Line 153 does `stack.clear()` after a single pop:

```java
// GuiManager.java:149-153
if (stack != null && !stack.isEmpty()) {
    GuiState prev = stack.pop();
    stack.clear();           // ← KILLS all remaining navigation history
    restoreState(player, prev);
}
```

The comment says "Back once, then exit entirely" — but this prevents natural multi-level ESC navigation (Shape Editor → Editor → Picker → Main).

**Fix:**
1. **Remove `stack.clear()`** — let the stack pop naturally
2. **Add a max depth guard** in `pushBackStack` to prevent unbounded growth:

```java
private static final int MAX_BACK_STACK_DEPTH = 10;

private static void pushBackStack(UUID uuid) {
    if (RESTORING.contains(uuid)) return;
    GuiState current = STATES.get(uuid);
    if (current != null) {
        Deque<GuiState> stack = BACK_STACK.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        stack.push(current);
        // Trim to prevent unbounded growth
        while (stack.size() > MAX_BACK_STACK_DEPTH) stack.removeLast();
    }
}
```

3. **Updated `handleEscBack`:**
```java
public static void handleEscBack(ServerPlayerEntity player) {
    UUID uuid = player.getUuid();
    PENDING.remove(uuid);
    GuiState state = STATES.get(uuid);
    if (state == null) return;

    Deque<GuiState> stack = BACK_STACK.get(uuid);
    if (stack != null && !stack.isEmpty()) {
        GuiState prev = stack.pop();
        // NO stack.clear() — let it pop naturally
        restoreState(player, prev);
    } else {
        // At root — fully close
        STATES.remove(uuid);
    }
}
```

---

## Phase 3: FPS Cap & Custom Anvil Input

### 3.1 — Raise FPS Cap from 60 to 100

**All locations that clamp at 60:**

| # | File | Line | Current | Change To |
|---|------|------|---------|-----------|
| 1 | `AnimBlockScreen.java` | 226 | `Math.min(60f, current + delta)` | `Math.min(100f, current + delta)` |
| 2 | `AnimBlockScreen.java` | 228 | `Math.min(60f, targetFps + delta)` | `Math.min(100f, targetFps + delta)` |
| 3 | `AnimBlockScreen.java` | 236 | `Math.min(60f, fps)` | `Math.min(100f, fps)` |
| 4 | `GuiManager.java` | 1847 | `Math.min(60f, fps + 1)` | `Math.min(100f, fps + 1)` |
| 5 | `GuiManager.java` | 1848 | `Math.min(60f, fps + 5)` | `Math.min(100f, fps + 5)` |

**Risk:** Minimal — only changes upper bound. The `applyAnimSettings` tickTime calculation (`20/fps`) already handles any positive float.

---

### 3.2 — Add Custom FPS Anvil Input Slot in Server-Side AnimGui

**Current AnimGui preset slots:** 28 (5fps), 29 (10fps), 30 (20fps), 31 (40fps). Slots 32–34 are glass filler.

**Changes:**

1. **New `InputAction` enum value:**
   ```java
   // GuiManager.java, inside InputAction enum
   ANIM_CUSTOM_FPS
   ```

2. **Add new presets + anvil input in `buildAnimGui`:**
   ```java
   // Add to buildAnimGui, after existing presets:
   inv.setStack(32, ui(Items.AMETHYST_CLUSTER, "§b60 FPS", "§7Smooth"));
   inv.setStack(33, ui(Items.AMETHYST_CLUSTER, "§b80 FPS", "§7Very fast"));
   inv.setStack(34, uiGlint(Items.ANVIL, "§e§lCustom FPS", 
       "§7Type any value from §f0.5§7 to §f100",
       "§8Opens an input field"));
   ```

3. **Handle new slots in `handleAnimGuiClick`:**
   ```java
   case 32 -> { fps = 60f; playClick(player); }
   case 33 -> { fps = 80f; playClick(player); }
   case 34 -> {
       // Open anvil prompt for custom FPS
       openShortInputPrompt(player,
           new PendingInput(InputAction.ANIM_CUSTOM_FPS, id, null, null, null, state.page()),
           "§eCustom FPS (0.5-100)",
           new ItemStack(Items.CLOCK),
           String.format("%.1f", fps));
       return;
   }
   ```

4. **Handle input in `handleChatInput`:**
   ```java
   case ANIM_CUSTOM_FPS -> {
       try {
           float customFps = Float.parseFloat(text.trim());
           customFps = Math.max(0.5f, Math.min(100f, customFps));
           customFps = Math.round(customFps * 10f) / 10f;
           AnimParams old = ANIM_PARAMS.getOrDefault(player.getUuid(), new AnimParams(10f, false, 1));
           ANIM_PARAMS.put(player.getUuid(), new AnimParams(customFps, old.interpolate(), old.frameCount()));
           send(player, "§a[Anim] FPS set to §f" + String.format("%.1f", customFps));
           // Reopen the AnimGui with updated params
           openAnimGui(player, blockId, rp);
       } catch (NumberFormatException e) {
           send(player, "§c[Anim] Invalid number. Enter a value like §f20§c or §f7.5");
           openAnimGui(player, blockId, rp);
       }
       return true;
   }
   ```

---

## Phase 4: Chat → Anvil GUI Conversions

### Inventory of Remaining `closeForPrompt` (Chat-Based) Inputs

| # | Location | Action | Short Text? | Convert to Anvil? |
|---|----------|--------|-------------|-------------------|
| 1 | Main GUI slot 23 | Search query | ✅ Yes | **✅ YES** |
| 2 | Editor slot 8 | Retexture URL | ❌ URLs long | ⚠️ Keep as chat |
| 3 | Editor slot 17 | Web Link Cast URL | ❌ URLs long | ⚠️ Keep as chat |
| 4 | Tools slot 21 | Color Square | ✅ Yes | **✅ YES** |
| 5 | Tools slot 22 | Color Triangle | ✅ Yes | **✅ YES** |
| 6 | Tab Icon slot 11 | Tab icon URL/ID | ❌ Could be URL | ⚠️ Keep as chat |
| 7 | Face Editor (×12) | Face texture URL | ❌ URLs long | ⚠️ Keep as chat |
| 8 | Config (non-anvil) | Config values | Mixed | Convert short ones |
| 9 | CREATE_URL flow | Image URL | ❌ URLs long | ⚠️ Keep as chat |

**Rationale for NOT converting URLs:** Minecraft anvil input has a ~50 character limit. URLs frequently exceed this (e.g., Discord CDN links are 80+ chars). Truncating URLs would be **worse UX** than the current chat prompt. The master directive says "professionalism" — a broken URL input is less professional than a working chat prompt.

### 4.1 — Convert Search Query to Anvil

**Current (chat-based):**
```java
// GuiManager.java:1334-1337
case 23 -> {
    PENDING.put(uuid, new PendingInput(InputAction.REID_TEXT, "__search__", null, null, null, state.page()));
    closeForPrompt(player);
    send(player, "§6[GUI] §eType a search query (or §ccancel§e):");
}
```

**Fixed (anvil-based):**
```java
case 23 -> {
    openShortInputPrompt(
        player,
        new PendingInput(InputAction.REID_TEXT, "__search__", null, null, null, state.page()),
        "§b🔍 Search Blocks",
        new ItemStack(Items.SPYGLASS),
        ""
    );
}
```

No other code changes needed — the `REID_TEXT` handler already checks for `"__search__"` blockId and routes to `openSearchPicker`.

### 4.2 — Convert Color Square Prompt to Anvil

**Current:**
```java
// GuiManager.java:1261-1264
case 21 -> {
    PENDING.put(player.getUuid(), new PendingInput(InputAction.REID_TEXT, "__givesquare__", null, null, null, state.page()));
    closeForPrompt(player);
    send(player, "§6[GUI] §eType color: §fblack §7| §fyellow §7| §fgreen§e:");
}
```

**Fixed:**
```java
case 21 -> {
    openShortInputPrompt(
        player,
        new PendingInput(InputAction.REID_TEXT, "__givesquare__", null, null, null, state.page()),
        "§eSquare Color (black/yellow/green)",
        new ItemStack(Items.YELLOW_WOOL),
        ""
    );
}
```

### 4.3 — Convert Color Triangle Prompt to Anvil

**Current:**
```java
// GuiManager.java:1266-1269
case 22 -> {
    PENDING.put(player.getUuid(), new PendingInput(InputAction.REID_TEXT, "__givetriangle__", null, null, null, state.page()));
    closeForPrompt(player);
    send(player, "§6[GUI] §eType color: §fblack §7| §fyellow §7| §fgreen§e:");
}
```

**Fixed:**
```java
case 22 -> {
    openShortInputPrompt(
        player,
        new PendingInput(InputAction.REID_TEXT, "__givetriangle__", null, null, null, state.page()),
        "§eTriangle Color (black/yellow/green)",
        new ItemStack(Items.YELLOW_WOOL),
        ""
    );
}
```

---

## Phase 5: ESC Confirmation GUI for Animation Editing

### 5.1 — Show Confirmation When ESC Pressed with Unsaved Animation Changes

**Current behavior:** ESC immediately closes the AnimGui without confirmation.

**New behavior:** If animation params have been modified (FPS or interpolation differ from originals), show a 27-slot confirmation GUI instead of closing.

#### Required New Components:

1. **New `GuiMode` enum value:**
   ```java
   // GuiMode.java
   ANIM_CONFIRM_ABANDON
   ```

2. **New `GuiState` factory:**
   ```java
   // GuiState.java
   public static GuiState animConfirmAbandon(String editingId, int returnPage) {
       return new GuiState(GuiMode.ANIM_CONFIRM_ABANDON, editingId, returnPage, false, 0, false);
   }
   ```

3. **New map for original params (already mentioned in 1.2):**
   ```java
   private static final Map<UUID, AnimParams> ANIM_ORIGINAL_PARAMS = new ConcurrentHashMap<>();
   ```

4. **Dirty-check helper:**
   ```java
   private static boolean isAnimDirty(UUID uuid) {
       AnimParams current = ANIM_PARAMS.get(uuid);
       AnimParams original = ANIM_ORIGINAL_PARAMS.get(uuid);
       if (current == null || original == null) return false;
       return Math.abs(current.fps() - original.fps()) > 0.05f
           || current.interpolate() != original.interpolate();
   }
   ```

5. **Modified `handleEscBack` for ANIM_GUI state:**
   ```java
   public static void handleEscBack(ServerPlayerEntity player) {
       UUID uuid = player.getUuid();
       PENDING.remove(uuid);
       GuiState state = STATES.get(uuid);
       if (state == null) return;

       // ── Special: Anim GUI dirty check ──
       if (state.mode() == GuiMode.ANIM_GUI && isAnimDirty(uuid)) {
           openAnimConfirmAbandon(player, state.editingId(), state.page());
           return;
       }

       Deque<GuiState> stack = BACK_STACK.get(uuid);
       if (stack != null && !stack.isEmpty()) {
           GuiState prev = stack.pop();
           restoreState(player, prev);
       } else {
           STATES.remove(uuid);
       }
   }
   ```

6. **Confirmation GUI builder:**
   ```java
   private static void openAnimConfirmAbandon(ServerPlayerEntity player, String id, int returnPage) {
       STATES.put(player.getUuid(), GuiState.animConfirmAbandon(id, returnPage));
       AnimParams current = ANIM_PARAMS.getOrDefault(player.getUuid(), new AnimParams(10f, false, 1));
       AnimParams original = ANIM_ORIGINAL_PARAMS.getOrDefault(player.getUuid(), current);

       SimpleInventory inv = new SimpleInventory(27);
       for (int i = 0; i < 27; i++) inv.setStack(i, glass());

       // Center info item (slot 13)
       inv.setStack(13, uiGlint(Items.WRITABLE_BOOK, "§e§lUnsaved Changes",
           "§7FPS: §f" + String.format("%.1f", original.fps()) + " §7→ §b" + String.format("%.1f", current.fps()),
           "§7Blending: §f" + (original.interpolate() ? "ON" : "OFF") + " §7→ §b" + (current.interpolate() ? "ON" : "OFF"),
           "",
           "§cDiscard these changes?"));

       // Yes — Discard (slot 11)
       inv.setStack(11, uiGlint(Items.LIME_WOOL, "§a§lYes — Discard",
           "§7Abandon changes and go back"));

       // No — Go Back (slot 15)
       inv.setStack(15, uiGlint(Items.RED_WOOL, "§c§lNo — Keep Editing",
           "§7Return to animation settings"));

       openScreen(player, new SimpleNamedScreenHandlerFactory(
           (s, pi, p) -> new CbScreenHandler(s, pi, inv),
           Text.literal("§c§l⚠ §r§fAbandon Changes?")));
   }
   ```

7. **Click handler:**
   ```java
   private static void handleAnimConfirmAbandonClick(ServerPlayerEntity player, GuiState state, int slot) {
       String id = state.editingId();
       int rp = state.page();
       switch (slot) {
           case 11 -> {
               // Yes — discard and go back
               ANIM_PARAMS.remove(player.getUuid());
               ANIM_ORIGINAL_PARAMS.remove(player.getUuid());
               openEditor(player, id, rp);
           }
           case 15 -> {
               // No — return to anim GUI
               AnimParams p = ANIM_PARAMS.getOrDefault(player.getUuid(), new AnimParams(10f, false, 1));
               STATES.put(player.getUuid(), GuiState.animGui(id, rp));
               openScreen(player, new SimpleNamedScreenHandlerFactory(
                   (s, pi, pp) -> new CbScreenHandler(s, pi, buildAnimGui(id, p.fps(), p.interpolate(), p.frameCount())),
                   Text.literal("§b§l▶ §r§fAnimation Settings")));
           }
       }
   }
   ```

8. **Register in `handleClick` dispatch:**
   ```java
   case ANIM_CONFIRM_ABANDON -> handleAnimConfirmAbandonClick(player, state, slot);
   ```

9. **Register in `restoreState`:**
   ```java
   case ANIM_CONFIRM_ABANDON -> openAnimGui(player, state.editingId(), state.page());
   ```

---

## Phase 6: Shape Creation Crash Prevention

### 6.1 — Rate-Limit Shape Variant Creation

**Problem:** Rapid clicking creates multiple variants simultaneously. Each one:
- Clones full texture bytes (potentially 100KB+ per GIF)
- Assigns a slot
- Copies all 6 face textures
- Copies shape, collision, anim meta
- Calls `saveAll()`
- Broadcasts 3–4 network packets

**Defensive measures:**

1. **Per-player cooldown (500ms):**
   ```java
   private static final Map<UUID, Long> SHAPE_CREATE_COOLDOWN = new ConcurrentHashMap<>();
   private static final long SHAPE_COOLDOWN_MS = 500;

   // At top of createShapeVariant:
   long now = System.currentTimeMillis();
   Long last = SHAPE_CREATE_COOLDOWN.get(player.getUuid());
   if (last != null && now - last < SHAPE_COOLDOWN_MS) {
       send(player, "§e[Shape] Please wait a moment...");
       reopenShapeEditor(player, id, rp, boxPage);
       return;
   }
   SHAPE_CREATE_COOLDOWN.put(player.getUuid(), now);
   ```

2. **OOM-safe texture clone:**
   ```java
   byte[] texCopy;
   try {
       texCopy = d.texture != null ? d.texture.clone() : null;
   } catch (OutOfMemoryError oom) {
       LOGGER.error("[CustomBlocks] OOM cloning texture for shape variant of '{}'", id);
       send(player, "§c[Shape] Not enough memory to create variant!");
       reopenShapeEditor(player, id, rp, boxPage);
       return;
   }
   ```

3. **Max variants per base block (cap at 24):**
   ```java
   List<SlotData> existingVariants = findShapeVariants(id);
   if (existingVariants.size() >= 24) {
       send(player, "§c[Shape] Maximum variants reached (24).");
       reopenShapeEditor(player, id, rp, boxPage);
       return;
   }
   ```

4. **Full try-catch wrapper:**
   ```java
   private static void createShapeVariant(ServerPlayerEntity player, SlotData d, String id,
                                           String preset, int rp, int boxPage) {
       try {
           // ... existing logic with above guards ...
       } catch (Exception e) {
           LOGGER.error("[CustomBlocks] Shape variant creation failed for '{}': {}", id, e.getMessage(), e);
           send(player, "§c[Shape] Creation failed: " + e.getMessage());
           reopenShapeEditor(player, id, rp, boxPage);
       }
   }
   ```

### 6.2 — Global Click Debounce

**Add per-player click cooldown at the top of `handleClick`:**

```java
private static final Map<UUID, Long> CLICK_COOLDOWN = new ConcurrentHashMap<>();
private static final long CLICK_COOLDOWN_MS = 100;

public static void handleClick(ServerPlayerEntity player, int slot, int button) {
    // Debounce rapid clicks
    long now = System.currentTimeMillis();
    Long last = CLICK_COOLDOWN.put(player.getUuid(), now);
    if (last != null && now - last < CLICK_COOLDOWN_MS) return;
    
    // ... existing logic ...
}
```

**Cleanup:** Add `CLICK_COOLDOWN.remove(uuid)` and `SHAPE_CREATE_COOLDOWN.remove(uuid)` to `onPlayerDisconnect()`.

---

## Phase 7: Additional Polish

### 7.1 — Client-Side AnimBlockScreen Preset Update

Currently the client presets only go to 30fps:
```java
// AnimBlockScreen.java:179
btnPresetUltra = addBtn(Text.literal("30fps"), cx + 56, row3, 40, 14, b -> setFps(30));
```

**Change** to match the new server-side options. Add 60fps and 100fps presets, or change the existing "30fps" button to "60fps" since the server now supports up to 100.

### 7.2 — Ping-Pong Toggle (Future Feature — Low Priority)

Currently client-only and not persisted. Full implementation requires:
1. Add `pingPong` to `AnimParams` record
2. Store in animMeta JSON
3. Add slot in `buildAnimGui`
4. Handle in `handleAnimGuiClick`
5. Parse in `openAnimGui`

**Recommendation:** Defer to a future update unless user explicitly requests.

---

## Implementation Order (Step-by-Step)

| Step | Task | Priority | Risk | Lines Changed |
|------|------|----------|------|---------------|
| **1** | Fix collision double-negation (2 locations) | 🔴 Critical | Minimal | ~2 |
| **2** | Fix anim chat message (context-aware) | 🔴 Critical | Low | ~25 |
| **3** | Add `pushBackStack` to `openAnimGui` | 🟡 High | Minimal | ~1 |
| **4** | Remove `stack.clear()` in `handleEscBack` + add depth guard | 🟡 High | Medium | ~10 |
| **5** | Add returnPage to AnimGui state + callers | 🟡 High | Low | ~15 |
| **6** | Raise FPS cap to 100 (5 locations) | 🟡 High | Minimal | ~5 |
| **7** | Add 60/80fps presets + custom FPS anvil input | 🟢 Medium | Low | ~40 |
| **8** | Convert search to anvil | 🟢 Medium | Minimal | ~5 |
| **9** | Convert color prompts to anvil | 🟢 Medium | Minimal | ~10 |
| **10** | ESC confirmation GUI for anim editing | 🟢 Medium | Medium | ~80 |
| **11** | Shape creation rate-limit + crash guards | 🟡 High | Low | ~30 |
| **12** | Global click debounce | 🟢 Medium | Low | ~10 |

**Total estimated lines changed:** ~233

---

## Files to Modify

| File | Changes |
|------|---------|
| `GuiManager.java` | Collision fix, chat messages, back-stack, AnimGui returnPage, FPS cap, new presets, anvil inputs, confirmation GUI, rate limiting, debounce, new InputAction |
| `AnimBlockScreen.java` | FPS cap raise (3 locations), update client presets |
| `GuiState.java` | New `animGui(id, returnPage)` overload, new `animConfirmAbandon` factory |
| `GuiMode.java` | New `ANIM_CONFIRM_ABANDON` enum value |
| `SlotBlock.java` | Update `openAnimGui` call to include returnPage |

---

## Testing Checklist

### Collision Toggle
- [ ] Open Shape Editor → Click Hitbox toggle → State changes from ON→OFF
- [ ] Click again → State changes from OFF→ON
- [ ] Open Properties Menu → Click collision toggle → Same correct behavior
- [ ] Verify network packet sends correct state
- [ ] Place block → Verify collision matches the setting

### Animation GUI Messages
- [ ] Change only FPS → Message says "Animation speed updated ... (X fps)"
- [ ] Toggle only blending → Message says "Smooth blending enabled/disabled"
- [ ] Change both → Message mentions both
- [ ] Save without changes → Message says "no changes"

### Navigation / Back Button
- [ ] Open AnimGui from Editor → Press ESC → Returns to Editor (not Picker/Main)
- [ ] Navigate Main → Picker → Editor → AnimGui → ESC × 4 → Each step goes back correctly
- [ ] Open AnimGui → Back button (slot 0) → Returns to correct editor page
- [ ] Open AnimGui from block right-click → Back button → Goes to Editor at page 0

### FPS Cap
- [ ] Client: nudge FPS above 60 → Caps at 100
- [ ] Server: click +5 above 60 → Caps at 100
- [ ] Custom FPS anvil: type "100" → Accepted
- [ ] Custom FPS anvil: type "150" → Clamped to 100
- [ ] Custom FPS anvil: type "abc" → Error message, returns to AnimGui

### Anvil Conversions
- [ ] Main menu → Search → Opens anvil prompt, not chat
- [ ] Tools → Color Square → Opens anvil prompt
- [ ] Tools → Color Triangle → Opens anvil prompt
- [ ] Type "cancel" in any anvil → Returns to previous GUI

### ESC Confirmation
- [ ] Change FPS in AnimGui → Press ESC → Confirmation GUI appears
- [ ] Click "Yes — Discard" → Returns to Editor, changes lost
- [ ] Click "No — Keep Editing" → Returns to AnimGui with changes preserved
- [ ] Don't change anything → Press ESC → Closes normally (no confirmation)

### Shape Creation Crash Prevention
- [ ] Rapid-click a preset 10 times → Only 1–2 variants created, rest get cooldown message
- [ ] Create 24 variants → 25th blocked with "Maximum variants reached"
- [ ] No server crash under any rapid-click scenario

---

## Success Criteria

1. **Collision toggle works** — clicking it actually changes the state ✓
2. **Chat messages are accurate** — match the actual setting changed ✓
3. **Back button always works** — correct navigation at every level ✓
4. **FPS supports up to 100** — with anvil input for custom values ✓
5. **Search uses anvil GUI** — professional inline input ✓
6. **ESC shows confirmation** — when unsaved animation changes exist ✓
7. **No server crashes** — rate limiting and defensive guards prevent all crash vectors ✓
8. **Zero regressions** — all existing functionality preserved ✓
