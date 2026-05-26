# CustomBlocks Fix Plan
*Last updated: 2026-05-26. Every file path and line number verified against actual source.*

---

## Before Every Session

Build a fresh jar first. The live server may be running an older version.

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
./gradlew build
# Deploy: build/libs/customblocks-*.jar (not the -dev or -sources one)
```

Nothing is fixed until the developer deploys the jar and confirms it in-game.

---

## Fix #1 — HUD Editor (Lunar-Style)
**Status: NOT BUILT**
**Developer said: "i want the lunar editing screen its so powerful cool and amazing"**

### The problem
`/cb edithud` just prints "coming soon." The overlay showing block info when looking at a block exists but is completely hardcoded — position, size, which fields show — none of it can be changed.

- Overlay code: `client/CustomBlocksClient.java` lines 970–1020
- Stub command: `command/CustomBlockCommand.java` lines 972–979
- No `HudConfig.java` exists. No `HudEditorScreen.java` exists.

### What to build

**1. `core/HudConfig.java` (new file)**
Saves/loads from `config/customblocks/hud-config.json`.
Fields: `int x`, `int y`, booleans for each display field (`showName`, `showId`, `showLight`, `showHardness`, `showSound`, `showCollision`, `showFace`).
Static `load()` and `save()` methods.

**2. `client/gui/HudEditorScreen.java` (new file)**
A Fabric client-side `Screen`. World renders in background.
Shows the HUD panel as a draggable rectangle — click and drag to reposition.
Toggle buttons for each field: click to show/hide that field.
`Save & Close` button → `HudConfig.save()` → close screen.
`Reset to Default` button → restore defaults.
ESC closes without saving.

**3. Modify `client/CustomBlocksClient.java` lines 970–1020**
Replace hardcoded `boxTop = 34` and `cx = width/2` with `HudConfig.x` and `HudConfig.y`.
Replace hardcoded field list with checks against `HudConfig.showName` etc.

**4. Modify `command/CustomBlockCommand.java` lines 972–979**
Send a network packet to the client to open `HudEditorScreen`.
New payload: `network/OpenHudEditorPayload.java`.
Register in `network/NetworkManager.java`.

### Test
`/cb edithud` opens a screen. World still visible behind it. Drag the block-info panel to a new spot. Save. Look at a custom block — panel is in the new position. Restart the game — still in that position.

---

## Fix #2 — Help GUI Redesign
**Status: WRONG — built around commands, not GUI actions**
**Developer said: "shows stuff that's general knowledge, stuff i cant use in guis and are commands, overall dumb and needs more improving and be everywhere"**

### The problem (confirmed by screenshot 2026-05-26)
The current help GUI at `GuiManager.java:5158` (`buildHelpGui()`) shows:
- Command syntax like `/cb create <id> <name> <url>` — useless when you're already in a GUI
- Keyboard shortcuts like "ESC → back / close GUI" — general knowledge everyone already knows
- Items floating in scattered slots with most of the GUI empty
- No contextual help — same help no matter where you are in the mod

### What it should be

**Rule: Help should tell you what you can do RIGHT NOW on THIS screen, not dump a command manual.**

**Part A — Replace the centralized help with task-based help**
- Current categories: "Creating Blocks", "Textures & Design", "Shapes & Collision", "Utilities", "Server & Data" — all showing command syntax
- New categories: answer real questions players have
  - "How do I change a block's color?" → explains color square/triangle tools + how to use them in the GUI
  - "How do I make a block glow or change its sound?" → explains the Properties screen, click-by-click
  - "How do I fix a broken/purple block?" → explains the Broken Blocks screen
  - "How do I undo something?" → explains undo button in main GUI + `/cb undo`
  - "How do I share or back up my blocks?" → explains export, snapshots, trash
- No raw command syntax. Plain English. What to click, not what to type.

**Part B — Add a ? button to EVERY major GUI screen**
Every screen should have a `?` button (slot 8, top-right corner) that opens a help screen specific to that screen.

Screens that need a ? button added:
- Main GUI (`buildMain()` line 4033) — slot 8
- Block editor — slot 8
- Properties screen — slot 8
- Bulk delete screen — slot 8
- Bulk recolor screen — slot 8
- Broken blocks screen — slot 8
- Deleted blocks screen — slot 8

Each ? opens a small 3×9 overlay (27 slots) explaining just that screen: what each button does, what to do if something looks wrong.

**Part C — Remove useless content**
- Remove the Keyboard Shortcuts item — ESC to close a GUI is not help content
- Remove raw command syntax from all help items
- Never show `minecraft:writable_book` as the hover item ID — that's a raw tooltip from Minecraft and means the item has no custom name set properly

### Files touched
- `gui/GuiManager.java` — `buildHelpGui()` at line 5158, `buildHelpCategory()` after it
- `gui/GuiManager.java` — add slot 8 `?` button to `buildMain()`, `buildPropertiesGui()`, and other key screens

### Test
Open `/cb help`. All 5 category buttons visible, no empty slots. Click a category — see plain English explanation with no command syntax. Open the main GUI — slot 8 has a `?` button. Click it — a small overlay explains each button in the main GUI.

---

## Fix #3 — Color Square Delay
**Status: ROOT CAUSE FOUND**
**Developer said: "takes a big delay between coloring, ai tried to fix it many times but didnt"**

### The problem
- `item/ColorSquareItem.java` `useOnBlock()` line 111
- Line 142: calls `resolveTargetId(current.customId, color.key(), current.texture)`
- `resolveTargetId()` line 234: Layer 2 fallback calls `ColorDetection.detect(textureBytes)` line 249
- `core/ColorDetection.java` `detect()` line 60: calls `ImageIO.read()` line 71
- **`ImageIO.read()` is slow — 50–300ms per call — and runs on the main server thread**
- This blocks the entire server tick every time a player right-clicks with a color square

### The fix
Pre-compute the detection result when blocks load, cache it in SlotData. Never run `ImageIO` on a right-click.

1. `core/SlotData.java` — add `transient String cachedColorFamily = null`
2. `core/SlotManager.java` `loadAll()` — after loading each block, run `ColorDetection.detect(d.texture)` and store result in `d.cachedColorFamily`
3. `item/ColorSquareItem.java` `resolveTargetId()` line 248 — check `textureBytes` param, but also accept a `cachedFamily` parameter. If cached value exists, skip `ImageIO` entirely.

Startup will be slightly slower (runs detection once per block on load). Every right-click after that is instant.

### Test
Right-click a custom block with a color square. The block should swap color with no noticeable delay. Test rapidly on 5 different blocks in a row.

---

## Fix #4 — Null Check Crashes (27 locations)
**Status: IDENTIFIED — not yet fixed**
**Risk: any of these can crash the server or silently corrupt state**

All 27 are the same pattern: `SlotManager.getById(someId)` result used without checking if it's null.
File: `gui/GuiManager.java`

Locations:
- Line 601 — `getById("tab_icon")` → `.texture`
- Line 1422 — `getById(delId)` → `.deepCopy()`
- Line 1648 — `getById("tab_icon")` → `.texture`
- Line 1695 — `getById(blockId)` → passed to UndoManager
- Line 1696 — `getById(blockId)` → `.customId`
- Line 1698 — `getById(newId)` → `.index`
- Line 1719 — `getById(blockId)` → `syncProp()`
- Line 1738 — `getById(blockId)` → `syncProp()`
- Line 3353 — `getById(id)` → `.texture`
- Line 3375 — `getById(id)` → broadcast call
- Line 3463 — `buildPropertiesGui(getById(id))`
- Line 3474 — `buildPropertiesGui(getById(id))`
- Line 3479–3496 — multiple `buildPropertiesGui(getById(id))`
- Line 3524 — `buildSoundMenu(getById(id))`
- Line 3774 — `getById(id)` → `pushUndoMutation()`
- Line 3850 — `getById(id)` → properties accessed
- Line 4228 — `getById(customId)` → used directly
- Line 4471 — `getById(job.sourceId())` → accessed
- Line 4567 — `getById(customId)` → used directly
- Line 5762 — `getById(id)` → broadcast call
- Line 5991 — `getById(id)` → properties accessed
- Line 6954 — `getById(targetId)` → used directly
- Line 7216 — `getById(bId)` → call chain
- Line 7525 — `getById(c.iconCustomBlockId())` → direct use

Fix pattern for each: `SlotData d = SlotManager.getById(id); if (d == null) { send(player, "Block not found."); return; }`

### Test
Delete a block while a GUI is open, then click buttons in that GUI. Server should not crash — should show "Block not found." message.

---

## Fix #5 — Broken Back-Navigation (10 screens)
**Status: IDENTIFIED — not yet fixed**
**File:** `gui/GuiManager.java` — `restoreState()` method

These screens dump the player to the main menu when they press ESC instead of going back to where they came from:

- `BULK_ASSIGN_PICKER`
- `BULK_RECOLOR_CONFIRM`
- `BULK_RECOLOR_WIZARD`
- `CATEGORY_BLOCK_CONTEXT`
- `CATEGORY_ICON_PICKER`
- `CATEGORY_STATS`
- `DELETE_CATEGORY_MENU`
- `IMPORT_CONFLICT`
- `MERGE_CATEGORY_PICKER_TARGET`
- `SORT_BLOCKS_MENU`

Fix: add a `case SCREEN_NAME -> openPreviousScreen(player)` for each in `restoreState()`.

### Test
Enter each screen, press ESC — should return to the previous screen, not the main menu.

---

## Fix #6 — Random Texture Breaks
**Status: CANNOT FIX — need server log**

Some blocks randomly go purple/black. Cannot diagnose without logs.

When it happens: note the block ID and check `logs/latest.log` on the server for errors around that time. Look for `[ResourcePackServer]` or `[CB]` warning/error lines.

---

## Work Order

Fix in this order. Do not start the next fix until the developer confirms the previous one works.

| # | Fix | Done when |
|---|-----|-----------|
| 1 | Deploy fresh jar | Developer confirms server is on new jar |
| 2 | HUD editor | Developer drags panel, saves, restarts, position remembered |
| 3 | Help GUI redesign | All categories show plain English, ? button works on main GUI |
| 4 | Color square delay | Instant swap on right-click, no delay |
| 5 | Null check crashes | Delete a block, click its GUI, no crash |
| 6 | Back-navigation | ESC from each screen goes back, not to main menu |
| 7 | Texture breaks | Zero purple blocks in 30-min session (need log first) |

Nothing is ✅ until the developer confirms it. Not the build. Not the code. The developer.
