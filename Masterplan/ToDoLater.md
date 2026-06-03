# To Do Later (Wishlist & Backlog)

## Group 9 — Backlog

Real issues, not urgent. Do not start until Groups 1–8 are cleared.

| ID | Issue | File |
|----|-------|------|
| BL-R9 | `/cb config ai-key`, `ai-provider`, `ai-variations`, `ai-style` subcommands | `CustomBlockCommand.java` |
| BL-R11 | ColorTriangleItem recolor preview GUI (Phase 3.5) | `ColorTriangleItem.java` |
| BL-R12 | Script vs. Macro storage separation (share MacroManager + dir now) | `MacroManager` |
| BL-R13 | WELCOME_MENU content (currently minimal) | `GuiManager.java` |
| BL-R14 | Verify `DropConfigManager.load()` is called at server startup | `DropConfigManager.java` |
| BL-R15 | Race condition in `getPackUrl()` volatile double-read | `ResourcePackServer.java` |
| BL-R16 | HTTP connection leak in `getExternalIp()` — no try-with-resources, no timeout | `NetworkManager.java` |
| BL-R18 | DiagnosticsHelper GUI audit uses hardcoded stub list | `DiagnosticsHelper.java` |
| BL-R19 | `FACE_IMPORTS` map entries not TTL-evicted for crashed clients | `GuiManager.java` |
| BL-R27 | `validateUrlSecurity()` passes URLs when DNS resolution fails (SSRF edge case) | `ImageProcessor.java` |
| BL-R28 | `generateSingleSlot()` stale per-face/variant file cleanup | `ResourcePackGenerator.java` |
| BL-R29 | Client-side ResourcePackGenerator skips power-of-2 validation | `ResourcePackGenerator.java` |
| BL-R31 | POST /pack Cloudflare Worker route has no rate limiting | `cloud-vault-worker/src/index.js` |
| BL-R32 | KV pack TTL is 24h — should be removed or extended to 30d | `cloud-vault-worker/src/index.js` |
| BL-H2a | `bulkConfirmThreshold` config field + second-confirm click for bulk ops | `GuiManager.java` |
| BL-H2b | Safe delete undo link for `/cb delete` COMMAND path (single, not bulk) | `CustomBlockCommand.java` |
| BL-Q1 | `/cb recover` — deleted blocks from UndoManager with restore buttons | `CustomBlockCommand.java` |
| BL-Q2 | `/cb panic` — timestamp-based 5-second re-confirm window | `CustomBlockCommand.java` |

---

### G7 — Unified /cb history Screen (Undo + Redo, Split View, Paginated)
**State:** ❌ NOT STARTED.
**Files:** `gui/GuiManager.java`, `command/CustomBlockCommand.java`
**Priority:** 🟡

Kill the existing view-only `/cb history` (`buildHistoryGui`) and `buildUndoPicker`. Replace both with one unified screen. `/cb history`, `/cb undogui`, `/cb redogui`, `/cb historygui` all open it.

**Layout (54-slot):**
```
Row 0: [glass][§6↩UNDO label][glass][glass][header][glass][glass][§b↪REDO label][glass]
Row 1: [u0][u1][u2][u3] | [divider] | [r0][r1][r2][r3]
Row 2: [u4][u5][u6][u7] | [divider] | [r4][r5][r6][r7]
Row 3: [u8][u9][u10][u11] | [divider] | [r8][r9][r10][r11]
Row 4: [u12][u13][u14][u15] | [divider] | [r12][r13][r14][r15]
Row 5: [back][glass][glass][prev][page-info][next][glass][glass][glass]
```
- 16 undo (left, cols 0–3) + 16 redo (right, cols 5–8) per page; divider at col 4.
- Click undo entry N: apply all undos from top down to N. Click redo entry N: apply redo N from top.
- On click: apply, refresh in place.

---

---

### G8 — Help GUI Redesign
**State:** ❌ NOT STARTED.
**File:** `gui/GuiManager.java` (`buildHelpGui()`, `buildMain()`)
**Priority:** 🟡

**Change A — Add `?` Help button in buildMain().** Slot 8 is in use (HUD Editor). Read `buildMain()` fully to find an empty slot, then:
```java
inv.setStack(/* verified empty slot */, ui(Items.WRITTEN_BOOK, "§b§l? Help", "§7What can I do on this screen?"));
```
Its click handler should call `openHelpGui(player)`.

**Change B — Replace buildHelpGui() content.** Remove any item showing raw `/cb` syntax, any "Keyboard Shortcuts" item, any `minecraft:writable_book` named item. Replace with 5 plain-English category buttons:
| Slot | Label | Explains |
|------|-------|---------|
| 1 | How do I change a block's color? | Color square/triangle tools |
| 2 | How do I make a block glow or change its sound? | Properties screen |
| 3 | How do I fix a broken or purple block? | Broken Blocks screen |
| 4 | How do I undo something? | Undo button in main GUI |
| 5 | How do I share or back up my blocks? | Export, snapshots, trash |

Each category screen: plain English only. No command syntax, no raw IDs.

---

---

### G9 — Bulk Op Picker Full Upgrade
**State:** ❌ NOT STARTED.
**File:** `gui/GuiManager.java` (`buildBulkOpPicker`, `handleBulkOpPickerClick`)
**Priority:** 🔵

Applies to ALL bulk operations (delete, recolor, rename) since they share the builder + handler.

**New top bar (row 0, slots 0–8):**
```
[0:←Back] [1:🔍Search] [2:🔤Pattern] [3:🔀Sort] [4:📂Category] [5:🏷PropFilter] [6:✓All] [7:✗All] [8:▶Execute]
```
Block grid: slots 9–44. Nav row: 45 (prev), 49 (page info), 53 (next).

**New data structures (add to GuiManager):**
```java
private static final Map<UUID, String>  BULK_SEARCH_FILTER = new ConcurrentHashMap<>();
private static final Map<UUID, String>  BULK_PROP_FILTER   = new ConcurrentHashMap<>();
private static final Map<UUID, String>  BULK_SORT_MODE     = new ConcurrentHashMap<>();
private static final Map<UUID, Integer> BULK_LAST_CLICKED  = new ConcurrentHashMap<>();
```
Add cleanup for all four in `onPlayerDisconnect()`. New GuiMode values: `BULK_DELETE_REVIEW`, `BULK_CAT_JUMP` (add to `GuiMode.java` + factory methods in `GuiState.java`).

**Sub-features:**
- **Search (slot 1):** anvil prompt → filter grid by name/ID (case-insensitive), stored in `BULK_SEARCH_FILTER`. Label shows `§aSearch: §f<keyword>` + result count. Empty = clear.
- **Pattern-select (slot 2):** anvil prompt → ADD every block whose ID contains the keyword to selection (doesn't replace). Action bar: `§aSelected N blocks matching '§f<pattern>§a'`.
- **Sort (slot 3):** cycles Default → A→Z → Z→A → By Category. Stored in `BULK_SORT_MODE`. Applied before pagination.
- **Category jump (slot 4):** opens `BULK_CAT_JUMP` 54-slot picker (one category per slot + count, "All" at slot 0). Click → filter + return.
- **Property filter (slot 5):** cycles All → Animated → Locked → Favorites → Broken. Stored in `BULK_PROP_FILTER`.
- **Shift-select range (slot 6/7 + grid):** left-click toggles single + records `lastClickedIdx`; shift+click (`slotActionType == QUICK_MOVE`) selects all between last and current (inclusive), across pages (indices into filtered+sorted pool).
- **Lock protection:** locked blocks shown as `RED_STAINED_GLASS_PANE` + lore `§c⚿ Locked`; clicking shows action bar; skipped on pattern-select; removed from selection on Execute + `Skipped N locked blocks`.
- **Review before execute (delete only):** when `opId == "delete"` and Execute clicked → review screen (read-only block list, Confirm at slot 53 always visible, Cancel at 45 preserves selection).
- **Clickable undo link after delete:** chat `§c§l[CustomBlocks] §r§cDeleted §f47§c blocks. §e§n[Click to undo]§r (30s)` using `ClickEvent.Action.RUN_COMMAND` → `/cb undo`.

---

### UND1b — Rich Batch Undo/Redo Message
**State:** ❌ NOT STARTED — design confirmed by developer. Build after REDO1 is confirmed.
**Priority:** 🟠

The batch undo/redo chat message currently shows a count only. Desired:
- Show the list of block names (not just a count).
- Right-click in chat to unfold the full list; show category info per block.
- Visible in `/cb history`, `/cb undogui`, and the `/cb redo` confirm dialog.

---

### NF3 — High-Speed Animation System (>20 fps)
**State:** ❌ NOT STARTED — complex, dedicated session.
**Priority:** 🔵

**Root cause:** MC's MCMETA animation is tick-based (`tickTime = max(1, round(20/fps))`). All fps ≥ 20 produce `tickTime = 1` — identical. Setting 100fps does nothing.

**Architecture:**
- **2a:** blocks with fps > 20 get `"highFps": true` + `"targetFps": 60` in animMeta; `frametime` still 1 for compatibility, but the high-fps system takes over.
- **2b — new `client/HighFpsAnimManager.java`:** `Map<Integer, HighFpsAnim>` keyed by slot index. `HighFpsAnim` fields: `NativeImage[] frames`, `int targetFps`, `int currentFrame`, `long lastUpdateNs`, `int atlasX/Y/Width/Height`. Methods: `register(...)`, `tick(long nowNs)` (advances when `(nowNs - lastUpdateNs) >= 1_000_000_000L / targetFps`, uploads via `GlStateManager._texSubImage2D`).
- **2c:** register in `CustomBlocksClient.java` via `WorldRenderEvents.END` / `HudRenderCallback.EVENT` — fires every rendered frame.
- **2d:** on `SlotUpdatePayload` with `"highFps": true`, extract frames and call `register()`.
- **2e — AnimGui:** cap standard MCMETA at 20fps; values above route to high-fps; relabel ("60 fps (high-speed)" vs "10 fps (standard)"); remove 40/60/80/100 from the standard slider; add a "High Speed" section.

**Files:** `client/CustomBlocksClient.java`, new `client/HighFpsAnimManager.java`, `gui/GuiManager.java`.

---

---

### NF1 — HUD Editor Phase 2 (Full Rework)
**State:** ❌ NOT STARTED — large, dedicated session. Phase 1 confirmed working in-game.
**Priority:** 🔵

**Phase 1 (done + confirmed):** `client/HudConfig.java` (saves x/y + 7 toggles to `hud-config.json`), `network/OpenHudEditorPayload.java` (S2C signal), `client/gui/HudEditorScreen.java` (drag editor, toggles, ESC confirm), client wiring, `/cb edithud` sends the packet.

**Phase 2 — Positioning & guides:** draggable sidebar; quick-snap TL/TC/TR/BL/BC/BR; center crosshair guides while dragging; magnetic grid overlay; edge snap zones (within 18px); snap to other HUD elements; all guides individually toggleable.

**Phase 2 — Appearance:** style switcher (Pill / Glow box / Plain text, live); background opacity slider; separate text opacity; font size 50–200%; accent color picker; rounded vs sharp corners; gradient option.

**Phase 2 — Content (dual mode):**
- *Visual chips (default):* toggleable, draggable chips: `[★][Name][ID][Light][Hardness][Sound][Collision][Face][Category][Creator][Frames][Health]`, live preview.
- *Template text (power user):* `{variable}` placeholders — `{name} {id} {light} {hardness} {sound} {collision} {face} {category} {creator} {frames} {health}`.
- Extras (both): block thumbnail in corner, animation status + frame number, category, creator, custom per-line prefix.

**Phase 2 — Behavior:** fade in/out on look; sticky mode (default 3s); show/hide keybind (default H, rebindable); auto-hide when a GUI is open.

**Phase 2 — Presets:** named layouts ("Staff Mode", "Clean", "Minimal"); load/switch in-editor; share as a copy-paste code.

**Phase 2 — Access:** pause-menu button (top priority, most Lunar-like); `/cb edithud` (done); H keybind; main `/cb` GUI slot.

---

---


