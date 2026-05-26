# CustomBlocks Fix Plan — VERIFIED
*Last updated: 2026-05-26. Every file path, line number, and method name confirmed against actual source.*

**RULE: Nothing is ✅ done until the developer confirms it in-game. Not the build. Not the code. The developer.**

---

## Before Every Session

Build a fresh jar first. The live server may be running an older version.

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
cd CustomBlockss
./gradlew build
# Deploy: build/libs/customblocks-1.0.0.jar (not -dev or -sources)
```

---

## Fix #1 — HUD Editor (Lunar-Style)
**Status: PHASE 1 CONFIRMED WORKING ✅ — Phase 2 (full rework) NOT STARTED**
**Developer said: "its amazing rn, but needs soooooooooo much optimization, improvements, reworking"**

---

### Phase 1 — Already built and confirmed working in-game

| What | File | Status |
|------|------|--------|
| `HudConfig.java` — saves x/y + 7 field toggles to `hud-config.json` | `client/HudConfig.java` | ✅ built |
| `OpenHudEditorPayload.java` — empty S2C signal packet | `network/OpenHudEditorPayload.java` | ✅ built |
| `HudEditorScreen.java` — basic drag editor, 7 toggle buttons, ESC confirm dialog | `client/gui/HudEditorScreen.java` | ✅ built |
| Payload registered in mod init | `CustomBlocksMod.java` line ~364 | ✅ built |
| `HudConfig.load()` on client init, receiver registered, HUD render uses HudConfig | `client/CustomBlocksClient.java` | ✅ built |
| `/cb edithud` sends packet instead of "coming soon" | `command/CustomBlockCommand.java` line ~972 | ✅ built |

---

### Phase 2 — Full rework (NOT STARTED — everything below needs building)

**Developer confirmed wants from conversation:**
- Both visual chip mode AND advanced `{variable}` template mode — ease of use is top priority
- Style switcher with live preview inside the editor (3 styles)
- All alignment guides, all toggleable individually
- Opacity, scale, color, corners — full appearance control
- Presets (save/load/share)
- All 4 access methods
- Fade, sticky, show/hide keybind

---

#### Positioning & Guides

- [ ] Floating sidebar — the sidebar panel itself is also draggable, not fixed to the right edge
- [ ] Quick-snap buttons — TL / TC / TR / BL / BC / BR — one click to jump to a corner or edge
- [ ] Center crosshair guides — H + V lines appear **while dragging** so you can snap to dead center
- [ ] Grid overlay — faint grid while dragging, magnetic snap to grid points
- [ ] Edge snap zones — panel snaps to screen edges and hotbar when within 18px
- [ ] Snap to other HUD elements (hotbar, health bar, XP bar)
- [ ] All guides individually toggleable via buttons in the editor sidebar

#### Appearance

- [ ] Style switcher — 3 styles, cycles live inside the editor:
  - **Pill** (default) — compact horizontal bar, Lunar-style
  - **Glow box** — rounded corners, glowing border color matches block light level
  - **Plain text** — no background, just text + shadow
- [ ] Background opacity slider (0–100%)
- [ ] Text opacity slider (separately from background)
- [ ] Font size / scale slider (50%–200%)
- [ ] Custom accent color picker (border, star, highlights)
- [ ] Rounded vs sharp corners toggle
- [ ] Gradient background option

#### Content / Template System

Dual-mode — both available, toggle between them in the editor:

**Mode 1 — Visual chips (default, newbie-friendly):**
- Row of chips: `[★]` `[Name]` `[ID]` `[Light]` `[Hardness]` `[Sound]` `[Collision]` `[Face]` `[Category]` `[Creator]` `[Frames]` `[Health]`
- Click chip to toggle on/off
- Drag chips to reorder
- Preview updates live

**Mode 2 — Template text (power user):**
- Type your own format using `{variable}` placeholders
- Full list of variables:
  - `{name}` — block display name
  - `{id}` — block custom ID
  - `{light}` — light level
  - `{hardness}` — hardness value
  - `{sound}` — sound type
  - `{collision}` — ON or OFF
  - `{face}` — face the crosshair is on
  - `{category}` — block category name
  - `{creator}` — player who created the block
  - `{frames}` — frame count (animated blocks)
  - `{health}` — block health status

**Extra fields to add to both modes:**
- [ ] Block thumbnail/icon — tiny texture preview in corner of HUD box
- [ ] Animation status — shows if animated, current frame
- [ ] Category name
- [ ] Creator name (who placed/created it)
- [ ] Custom prefix per line — replace ✦ star with any emoji or text

#### Behavior / Visibility

- [ ] Fade in/out — smoothly fades in when looking at a block, fades out when looking away
- [ ] Sticky mode — stays visible for N seconds after looking away (default 3s, user-configurable)
- [ ] Show/hide toggle keybind (default: H — rebindable in Minecraft controls)
- [ ] Auto-hide when inventory/GUI is open

#### Presets

- [ ] Save named layouts (e.g. "Staff Mode", "Clean", "Minimal")
- [ ] Load/switch presets from inside the editor
- [ ] Share preset as a copy-paste code

#### Access Methods

- [ ] Pause menu button — button on the Esc screen, most Lunar-like (**top priority**)
- [ ] `/cb edithud` command — already done ✅
- [ ] Show/hide keybind (H by default, rebindable)
- [ ] Slot in `/cb` main GUI dashboard

---

### Test for Phase 2 (once built)
1. Press Esc — HUD Editor button visible in pause menu
2. Click it — editor opens, world visible behind
3. Drag HUD box — alignment guides appear, center lines shown, magnetic grid snaps
4. Switch styles — Pill / Glow / Plain — updates live
5. Toggle a chip off — HUD updates immediately
6. Switch to Advanced mode — type `{name} | Light {light}` — HUD shows that format
7. Adjust opacity slider — background fades
8. Save preset named "Staff" — switch to another preset — switch back — settings restored
9. Press H — HUD hides. Press H again — shows. Look at block — fades in smoothly
10. Restart game — position, style, and preset still there

---

## Fix #2 — Help GUI Redesign
**Status: NOT STARTED**
**Developer said: "shows stuff that's general knowledge, stuff i cant use in guis and are commands, overall dumb and needs more improving and be everywhere"**

### Problem (verified in source)

| Claim | File | Line | Verified |
|-------|------|------|---------|
| `buildHelpGui()` exists | `gui/GuiManager.java` | 5158 | ✅ |
| `buildMain()` exists | `gui/GuiManager.java` | 4033 | ✅ |
| Slot 8 is empty on main GUI | `gui/GuiManager.java` | 4033–4040 | ✅ row 0 only uses slot 4 |

### What to change

**Change A — `GuiManager.java` `buildMain()` at line 4033 — add `?` button at slot 8**

Find where slot 4 is set in `buildMain()` header row. After it, add:
```java
inv.setStack(8, ui(Items.WRITTEN_BOOK, "§b§l? Help", "§7What can I do on this screen?"));
```

The click handler for slot 8 in `handleMainClick()` should call `openHelpGui(player)`.

**Change B — `GuiManager.java` `buildHelpGui()` at line 5158 — replace content**

Remove:
- Any item showing raw `/cb` command syntax
- Any "Keyboard Shortcuts" item
- Any item with `minecraft:writable_book` as its displayed name

Replace the 5 category buttons with:
| Button | Label | Description |
|--------|-------|-------------|
| 1 | How do I change a block's color? | Explains color square/triangle tools in the GUI |
| 2 | How do I make a block glow or change its sound? | Explains the Properties screen, click-by-click |
| 3 | How do I fix a broken or purple block? | Explains the Broken Blocks screen |
| 4 | How do I undo something? | Explains undo button in main GUI |
| 5 | How do I share or back up my blocks? | Explains export, snapshots, trash |

Each category screen: plain English sentences. No command syntax. No raw IDs.

### Test
1. `/cb help` — 5 category buttons visible, no empty slots
2. Click a category — plain English explanation, no command syntax
3. Open main GUI — slot 8 has `?` button
4. Click `?` — overlay explains each button on the main GUI

---

## Fix #3 — Color Square Delay
**Status: NOT STARTED**
**Developer said: "takes a big delay between coloring, ai tried to fix it many times but didnt"**

### Problem (verified in source)

| Claim | File | Line | Verified |
|-------|------|------|---------|
| `useOnBlock()` entry | `item/ColorSquareItem.java` | 111 | ✅ |
| `resolveTargetId()` call | `item/ColorSquareItem.java` | 142 | ✅ |
| `resolveTargetId()` definition | `item/ColorSquareItem.java` | 234 | ✅ |
| `ColorDetection.detect()` call inside | `item/ColorSquareItem.java` | 249 | ✅ |
| `ColorDetection.detect()` definition | `core/ColorDetection.java` | 60 | ✅ |
| `ImageIO.read()` call | `core/ColorDetection.java` | **67** | ✅ |

The call chain on every right-click: `useOnBlock()` → `resolveTargetId()` → `ColorDetection.detect()` → `ImageIO.read()`. This blocks the server tick for 50–300ms.

### The fix

Pre-compute color detection when blocks load. Cache in `SlotData`. Skip `ImageIO` entirely on right-click.

**Change A — `core/SlotData.java` — add transient cache field**

Find where other transient fields are declared (around line 30). Add:
```java
public transient String cachedColorFamily = null;
```

**Change B — `core/SlotManager.java` `loadAll()` — populate cache after loading**

Find the loop that loads each `SlotData` object. After each block is loaded, add:
```java
if (d.texture != null && d.texture.length > 0) {
    com.customblocks.core.ColorDetection.DetectionResult r = com.customblocks.core.ColorDetection.detect(d.texture);
    if (r != null && r.confident()) {
        d.cachedColorFamily = r.family();
    }
}
```

**Change C — `item/ColorSquareItem.java` `resolveTargetId()` at line 234 — add cache parameter**

Change method signature from:
```java
static String resolveTargetId(String currentId, String targetColorKey, byte[] textureBytes) {
```
To:
```java
static String resolveTargetId(String currentId, String targetColorKey, byte[] textureBytes, String cachedFamily) {
```

In the body, find the Layer 2 fallback (line ~249):
```java
        if (textureBytes != null && textureBytes.length > 0) {
            ColorDetection.DetectionResult result = ColorDetection.detect(textureBytes);
```

Replace with:
```java
        if (cachedFamily != null) {
            ColorDetection.DetectionResult result = new ColorDetection.DetectionResult(cachedFamily, true);
            // continue with result — identical to what detect() would have returned
        } else if (textureBytes != null && textureBytes.length > 0) {
            ColorDetection.DetectionResult result = ColorDetection.detect(textureBytes);
```

> **NOTE:** Before writing this, read `ColorDetection.DetectionResult` to confirm its constructor signature. Do not guess the constructor.

**Change D — `item/ColorSquareItem.java` line 142 — update caller**

Old:
```java
        String targetId = resolveTargetId(current.customId, color.key(), current.texture);
```

New:
```java
        String targetId = resolveTargetId(current.customId, color.key(), current.texture, current.cachedColorFamily);
```

### Test
Right-click a custom block with a color square. Color swaps with zero delay. Test rapidly on 5 different blocks in a row.

---

## Fix #4 — Null Check Crashes
**Status: NOT STARTED**
**File: `gui/GuiManager.java`**

### Verification result

The original plan listed 27 locations. 6 already have null checks — do not touch them.

**Already safe (DO NOT touch):**
- Line 3327 — already has `if (dd == null) return;`
- Line 3850 — already has `d != null ?` ternary
- Line 4471 — already has `src != null ?` ternary
- Line 5991 — already has `d != null ?` ternary
- Line 6954 — already has `if (target == null || source == null)`
- Line 7525 — already has `d != null &&` check

**Need fixing (21 locations) — fix pattern for each:**

```java
SlotData d = SlotManager.getById(id);
if (d == null) { send(player, "Block not found."); return; }
// ... rest of logic using d
```

Lines that need this fix:
601, 1422, 1648, 1695, 1696, 1698, 1719, 1738, 3353, 3375, 3463, 3474, 3479, 3487, 3488, 3496, 3524, 3774, 4228, 4567, 5762, 7216

> Before editing any line, read the surrounding 10 lines to confirm the exact variable name and method name. Line numbers may have shifted slightly since verification.

### Test
Delete a block while a GUI referencing it is open. Click buttons in that GUI. Server should not crash — should show "Block not found."

---

## Fix #5 — Broken Back-Navigation (10 screens)
**Status: NOT STARTED**
**File: `gui/GuiManager.java` — `restoreState()` at line 1184**

### Verification result

All 10 screen names exist in `gui/GuiMode.java`:
- `BULK_ASSIGN_PICKER` ✅
- `BULK_RECOLOR_CONFIRM` ✅
- `BULK_RECOLOR_WIZARD` ✅
- `CATEGORY_BLOCK_CONTEXT` ✅
- `CATEGORY_ICON_PICKER` ✅
- `CATEGORY_STATS` ✅
- `DELETE_CATEGORY_MENU` ✅
- `IMPORT_CONFLICT` ✅
- `MERGE_CATEGORY_PICKER_TARGET` ✅
- `SORT_BLOCKS_MENU` ✅

### The fix

In `restoreState()` at line 1184, find the switch statement. These 10 cases are missing from it.

Before adding each case:
1. Find the corresponding `open*()` method in `GuiManager.java`
2. Check its actual parameter signature (some take `editingId`, some take only `page`, some take both)
3. Add the case calling that exact method with the parameters available from `state`

Do NOT blindly copy method signatures — read each `open*()` method first to confirm parameters.

### Test
Enter each of the 10 screens. Press ESC. Should return to the previous screen, not the main menu.

---

## Work Order

Do NOT start the next fix until the developer confirms the previous one works in-game.

| # | Fix | Status |
|---|-----|--------|
| 1a | HUD Editor Phase 1 — basic drag, toggles, position save | ✅ Confirmed working in-game |
| 1b | HUD Editor Phase 2 — full rework (styles, templates, guides, presets, keybind, pause menu) | NOT STARTED |
| 2 | Help GUI redesign | NOT STARTED |
| 3 | Color square delay | NOT STARTED |
| 4 | Null check crashes | NOT STARTED |
| 5 | Back-navigation | NOT STARTED |

**Nothing is ✅ until the developer says so. The build passing is not done. The code looking right is not done.**

---

## Known Undiagnosable Issue (Not in work order)

**Random texture breaks** — Some blocks go purple/black. Cannot fix without server logs. When it happens: note the block ID and check `logs/latest.log` for `[ResourcePackServer]` or `[CB]` lines.
