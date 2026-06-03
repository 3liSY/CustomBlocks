# The Color System (COL1-12, NF4)

# 01_COL12

## COL12: Random blocks say "No texture data to recolour"

[x] **Code Written**
[x] **Tested In-Game** — CONFIRMED 2026-06-02

**State:** ✅ DONE — disk fallback in `ColorVariantService.resolvePrimaryTexture()` confirmed working in-game.



# Active Batch: COL5, COL12

*This batch was moved from the main backlog. View the full backlog here: [MASTERPLAN.md](../MASTERPLAN.md)*

---

## 1. COL5 → Merged into COL8 (2026-06-02)

[x] **Code Written**
[x] **Tested In-Game** — CONFIRMED 2026-06-02 (fresh items only)

**State:** ✅ DONE for fresh items. Old items in inventory show double tooltip because they have outdated lore baked in: "Right-click a placed custom block: remove its background color", "Mode: Edge fill (perimeter) or Full fill (everywhere)", "Switch mode: /cb trianglemode edge or /cb trianglemode full", "Adjust sensitivity: /cb tolerance <10-80>". No code fix — developer drops old items and picks fresh ones from creative. Confirmed 2026-06-02.

**Files:** `item/ColorSquareItem.java`, `item/ColorTriangleItem.java`

---

## 2. COL12: Random blocks say "No texture data to recolour"

[x] **Code Written**
[x] **Tested In-Game** — CONFIRMED 2026-06-02

**State:** ✅ DONE — disk fallback in `ColorVariantService.resolvePrimaryTexture()` confirmed working in-game.



# Active Batch: COL Square Detection Fix, LANG1

*This batch was moved from the main backlog. View the full backlog here: [MASTERPLAN.md](../MASTERPLAN.md)*

---

## 1. COL Square — Wrong "Already [Color]" on Non-Matching Blocks

[x] **Code Written**
[ ] **Tested In-Game**

**State:** ⏳ NEEDS IN-GAME TEST — two separate bugs fixed 2026-06-03. Previously confirmed broken in-game 2026-06-02.

**Files:** `item/ColorSquareItem.java`, `core/ColorDetection.java`, `CustomBlocksConfig.java`

**Original bug (confirmed 2026-06-02):** Every color square said "Already [color]" regardless of the block's actual background. Root cause: `ColorDetection.detect()` was averaging ALL pixels instead of edge-only.
- `ColorDetection.detect()` was **already fixed** to sample only outer edge pixels (top row, bottom row, left col, right col) — the fix was in the code before this session.

**New bug found 2026-06-03 (screenshot proof):** A block with a black background and no color word in its ID (e.g. `testpixl`) showed "No black variant found" when using the Black Square, instead of "Already Black."
- Root cause: `matchesColor()` in `ColorSquareItem` only checked `cachedColorFamily` and ID segments. If `cachedColorFamily` was null/stale and the ID had no color word, it always returned false — even if the texture was correctly black.
- Fix (2026-06-03): Added texture-based detection fallback as a third check in `matchesColor()`. If `cachedColorFamily` and ID scan both miss, it now calls `ColorDetection.detect(block.texture)` directly. Only a CONFIDENT result triggers "Already [color]" — ambiguous results still say "No variant found."
- File: `item/ColorSquareItem.java` — `matchesColor()` method

**Additional fix (2026-06-03) — Color Square no longer requires bg mode configured:**
- Previously the Square showed "color tool not configured" if `colorToolBackgroundMode` was `"unset"`.
- The Square only swaps to an existing variant — it never does background removal. The guard was wrong here; it belongs only on the Triangle.
- Fix: removed the `isColorToolModeConfigured()` check from `ColorSquareItem.useOnBlock()`.
- File: `item/ColorSquareItem.java` — `useOnBlock()` method

**Additional fix (2026-06-03) — bg mode `"none"` now persists across server restart:**
- Setting bg mode to `"none"` in the GUI, then restarting the server, reset it back to `"unset"`.
- Root cause: the validation block in `CustomBlocksConfig` whitelisted `"unset"`, `"corners_only"`, `"corners_and_trapped"` but NOT `"none"`. On load, `"none"` failed validation and was reset.
- Fix: added `"none"` to the validation whitelist.
- File: `CustomBlocksConfig.java` — config validation block (~line 535)

**Test:**
1. Use Red Square on a block with a black background that has NO red variant → should say "No red variant found" NOT "Already Red"
2. Use Black Square on a block with a black background and no color word in its name → should say "Already Black"
3. Use any Square without configuring bg mode → should work (no "not configured" error)
4. Set bg mode to `none` in config, restart server → should still be `none`

---



# Active Batch: COL9 Rework

*This batch was moved from the main backlog. View the full backlog here: [MASTERPLAN.md](../MASTERPLAN.md)*

---

## 1. COL9: Hex Change → Update Existing Blocks (Full Rework)

[x] **Code Written** — multiple iterations (see failure history below)
[ ] **Tested In-Game** — partial (wizard confirmed opening; full end-to-end not confirmed)

**State:** ⏳ NEEDS FULL IN-GAME TEST — went through 4 rounds of fixes on 2026-06-03. Current code is the 4th attempt. Read failure history below before touching anything.

**Files modified:**
- `gui/GuiManager.java` — `openHexRecolorConfirmGui`, `handleHexRecolorConfirmClick`, `runHexUpdateBatch`, maps at top of file
- `item/ColorTriangleItem.java` — `recolourTextureForBatch`, `recolourTextureDirectSwap` (new methods)
- `network/ConfigSyncPayload.java` — new file
- `network/NetworkManager.java` — `broadcastConfigSync` helper
- `client/CustomBlocksClient.java` — ConfigSyncPayload receiver
- `CustomBlocksMod.java` — payload registration
- `network/ServerPackGenerator.java` — colored item PNG generation

**State maps in GuiManager (all cleared in session cleanup ~line 375):**
- `HEX_RECOLOR_SELECTED: Map<UUID, Set<String>>` — which blocks are selected
- `HEX_RECOLOR_CANCEL: Map<UUID, Boolean>` — cancel flag for running batch
- `HEX_RECOLOR_BG_MODE: Map<UUID, String>` — per-batch bg mode (never touches global config)
- `HEX_RECOLOR_OLD_RGB: Map<UUID, Integer>` — packed old hex captured before config overwrite

---

### ⚠️ FAILURE HISTORY — READ BEFORE TOUCHING THIS CODE

**Round 1 — Wizard never opened (fixed 2026-06-03)**
- Bug: `Color.decode(text.trim())` at ~line 1900. Raw input without `#` threw silently, fell back to config with no message.
- Fix: `Color.decode(normalizeHexInput(text))`.
- Also fixed: Dry Run "Close Preview" was at slot 49 (same as Confirm Update) → fired real batch. Fixed with `if (uiPage == 9999)` guard.
- In-game result: Wizard opened ✅

**Round 2 — Blocks turned to yellow concrete (fixed 2026-06-03)**
- Bug: `runHexUpdateBatch` called `recolourTextureForPlayer(uuid)` which reads `PLAYER_MODE` from map. Player had mode "full" (replace ALL matching pixels everywhere). Design pixels destroyed.
- Fix: Added `recolourTextureForBatch` to `ColorTriangleItem.java` — ALWAYS forces `"edge"` mode, ignores player PLAYER_MODE. Swapped call in batch runner.
- **RULE FOR NEXT AI:** NEVER call `recolourTextureForPlayer` in batch operations. Always use `recolourTextureForBatch` or `recolourTextureDirectSwap`.
- In-game result: Designs no longer destroyed ✅ but new problem found (Round 3)

**Round 3 — Items updated but blocks didn't / two separate pack reloads (fixed 2026-06-03)**
- Bug A: `broadcastConfigSync` was called immediately when config saved, BEFORE wizard opened. Client pack reloaded early (item textures updated). Then wizard ran and blocks updated via packets. Two separate reloads — user saw items change but blocks appeared not to change.
- Bug B: Edge-mode flood fill didn't replace ALL old yellow pixels — some background pixels not reachable from edges stayed as old color. User wanted ALL old hex pixels replaced.
- Fix A: Moved `broadcastConfigSync` call to END of `runHexUpdateBatch` completion block (and to Cancel handler). Client receives block texture packets + config sync in same debounce window → ONE combined pack reload.
- Fix B: Added `recolourTextureDirectSwap` to `ColorTriangleItem.java` — direct per-pixel comparison. For each pixel, if RGB distance from old hex ≤ 30, replace with new hex. No flood fill, no tolerance dependency. Old hex captured in `storeOldHex()` BEFORE config is overwritten, stored in `HEX_RECOLOR_OLD_RGB` map.
- In-game result: NOT YET CONFIRMED — this is the current state.

**Round 4 — Item textures never reached modded client (fixed 2026-06-03)**
- Bug: `ServerPackGenerator` was pointing colored tools to vanilla dyes. Fixed to generate colored PNGs. But items STILL didn't update — PACK2 guard prevents HTTP pack from reaching modded clients entirely.
- Root cause: Modded clients run `ResourcePackGenerator.generate(client)` LOCALLY from their own stale `CustomBlocksConfig`. Server changes never reach client config.
- Fix: `ConfigSyncPayload` (new packet) — broadcasts new hex values to all online modded clients on batch completion. Client updates `CustomBlocksConfig`, calls `scheduleGenerateAndReload()` → brief texture flash → new item colors applied.
- Cancel also sends ConfigSyncPayload so item textures update even if user cancels wizard.
- In-game result: NOT YET CONFIRMED — timing fix built after initial test.

---

### 🤝 CURRENT CODE STATE (what the batch actually does now)

**`openHexRecolorConfirmGui` (~line 4810):**
- Paginated 54-slot GUI — shows all affected blocks (36 per page)
- Each block slot: name, ✅ Selected / ❌ Skipped, ⚠ if hasFaces()
- Slot 4: "N of M selected" header
- Slot 45: Cancel → clears maps, sends ConfigSyncPayload, returns to config
- Slot 46/52: Pagination
- Slot 47: Test 1 Block → runs batch on `.limit(1)` with current bgMode
- Slot 49: Confirm Update → snapshot, then batch with current bgMode. If uiPage==9999 (dry run sentinel) → reopens wizard instead
- Slot 51: Dry Run → chat summary + read-only preview GUI
- Slot 53: BG mode cycle button — `corners_only` (Lime Dye) → `corners_and_trapped` (Lime Concrete) → `none` (Light Gray) → back. Never affects /cb config.
- Triggers only for `triangleRedHex`, `triangleGreenHex`, `triangleYellowHex`
- Filters blocks by `customId.contains("_yellow")` etc.

**`runHexUpdateBatch` (~line 5060, signature: `player, toUpdate, newR, newG, newB, bgMode`):**
- Early exit if bgMode == "none": sends "textures not changed" message, returns to config, still sends ConfigSyncPayload for item update
- `useTrapped = "corners_and_trapped".equals(bgMode)`
- Reads `HEX_RECOLOR_OLD_RGB` map → gets old RGB packed int → unpacks to oldR/G/B → removes from map
- If old RGB available: calls `recolourTextureDirectSwap(texture, oldR, oldG, oldB, newR, newG, newB, useTrapped)` — direct pixel swap, threshold 30
- If old RGB not available (fallback): calls `recolourTextureForBatch(texture, newR, newG, newB, useTrapped, uuid)` — edge mode flood fill
- Collects UndoEntry per block into undoBatch
- On completion: `pushUndoBatch("Hex Update: N block(s) to #RRGGBB")`, `scheduleRebuild`, `broadcastConfigSync` (triggers combined pack reload), returns to config

**`recolourTextureDirectSwap` in `ColorTriangleItem.java`:**
- Decodes PNG, iterates all pixels
- For each pixel: computes Euclidean RGB distance from (oldR, oldG, oldB)
- If distance ≤ 30 → replace with (newR, newG, newB), preserving alpha
- Re-encodes to PNG
- Does NOT use flood fill, does NOT read tolerance, does NOT read player mode
- The 30-unit threshold catches JPEG/PNG compression artifacts without eating unrelated design colors

**Known limitations:**
- `none` bg mode in the wizard means "don't change textures at all" — might be confusing but documented
- Dry Run preview uses placeholder LIME_DYE for new blocks (can't show real recolored texture without saving)
- No cancel button once batch is running (HEX_RECOLOR_CANCEL map exists but nothing sets it to true mid-batch)

---

### All 10 Original Flaws — Status

| # | Flaw | Status |
|---|------|--------|
| 1 | Force no bg removal on batch | ✅ `recolourTextureDirectSwap` — direct swap, no bg removal algorithm |
| 2 | Snapshot announcement | ✅ Created before batch + action bar message |
| 3 | One undo entry | ✅ `pushUndoBatch` groups all into one |
| 4 | Preview list before confirm | ✅ Paginated selection GUI |
| 5 | Block selection checkboxes | ✅ Toggle per block |
| 6 | Progress during update | ✅ Action bar "Updating block X of Y..." |
| 7 | Test on 1 block first | ✅ Slot 47 "Test 1 Block" |
| 8 | Warn custom face textures | ✅ Lore shows ⚠ warning |
| 9 | Cancel mid-update | ⚠️ Flag exists but no in-progress cancel button |
| 10 | Dry run mode | ✅ Slot 51, placeholder preview (known limit) |

---

**To test (full end-to-end):**
1. `/cb config` → change `triangleYellowHex` → save → wizard opens immediately
2. Slot 53 (bottom-right): cycles BG mode — check label changes
3. Confirm Update → blocks change color, designs intact
4. One pack reload (brief flash) → item in hotbar shows new color AND new hex in name
5. Cancel → item textures still update (ConfigSyncPayload sent on cancel)
6. `/cb undogui` → ONE undo entry covers all blocks

---

## 2. NF4 / Item Texture Colors

[x] **Code Written** — multiple iterations (see COL9 failure history above for full detail)
[ ] **Tested In-Game**

**State:** ⏳ NEEDS IN-GAME TEST — ConfigSyncPayload built and integrated. Timing fixed (fires after batch, not before).

**What was built:**
- `network/ConfigSyncPayload.java` — new S2C packet carrying `blackHex`, `yellowHex`, `greenHex`, `redHex`
- `network/NetworkManager.broadcastConfigSync(server)` — sends to all online modded clients
- `client/CustomBlocksClient.java` — receiver updates `CustomBlocksConfig` then calls `scheduleGenerateAndReload(client, fastReloadDebounceMs())`
- `CustomBlocksMod.java` — payload registered in `PayloadTypeRegistry.playS2C()`
- `network/ServerPackGenerator.java` — generates real colored 16×16 PNGs for all 8 tool items (4 squares + 4 triangles) from config hex, replaces vanilla dye fallbacks

**Key point — timing:** ConfigSyncPayload is sent at the END of `runHexUpdateBatch` completion AND on Cancel. NOT immediately on config save. This ensures block texture packets (`SlotUpdatePayload`) arrive on client first, then ConfigSyncPayload arrives, and one combined debounce window triggers one pack reload covering both.

**COL9 — Per-Batch BG Mode Selector (built 2026-06-03):**
- Slot 53 in wizard: cycles `corners_only` → `corners_and_trapped` → `none`
- Stored in `HEX_RECOLOR_BG_MODE: Map<UUID, String>` — never touches global config
- Default: matches current `colorToolBackgroundMode` (or "corners_only" if unset)
- `runHexUpdateBatch` receives bgMode as parameter and uses it

**Files changed summary:**
- `gui/GuiManager.java` — all wizard + batch logic
- `item/ColorTriangleItem.java` — `recolourTextureForBatch`, `recolourTextureDirectSwap`
- `network/ConfigSyncPayload.java` — new
- `network/NetworkManager.java` — `broadcastConfigSync`
- `client/CustomBlocksClient.java` — receiver
- `CustomBlocksMod.java` — registration
- `network/ServerPackGenerator.java` — colored PNG generation
- `CustomBlocksConfig.java` — "none" added to validation whitelist (bg mode persistence fix)



## 3. COL3/4: Enclosed Holes + Edge Halo (Yellow Outline)

[x] **Code Written**
[x] **Tested In-Game** — CONFIRMED 2026-06-02

**State:** ✅ DONE — COL3 (enclosed hole filling) and COL4 (edge halo removal) confirmed working alongside the TOL1 fix.

**Exact symptom confirmed in-game (2026-06-02):**
After recoloring a yellow-background block to baby blue using the Color Triangle, **yellow pixels from the original background remain as an outline around the design elements** (the black lines/shapes). The flood-fill missed the edge yellow pixels that sit adjacent to the black outline — those pixels are close enough to the design that they fall outside the tolerance threshold and are not replaced with blue.

**Root cause:** Tied directly to TOL1. The edge-blend threshold is derived from `labThreshold`, which behaves incorrectly until TOL1 is fixed. Re-test COL4 after TOL1 is confirmed working — the yellow outline should disappear when tolerance applies correctly.

---



### COL1b — Remove Client Skip from ALL 7 Tools
**State:** ✅ CONFIRMED IN-GAME (2026-06-02) — tools feel instant, no delay.
**Files:** `ColorSquareItem`, `ColorTriangleItem`, `RectangleToolItem`, `AmethystChiselItem`, `LuminaBrushItem`, `GoldenHexagonItem`, `DiamondTriangleItem`
**Priority:** 🟠

Each tool had `if (world.isClient) return ActionResult.PASS;` in `useOnBlock()`, causing delay. For each: delete that line, and wrap every `player.sendMessage(...)` / `playSound(...)` with `if (!world.isClient)` to avoid duplicate feedback. Each method self-gates via `if (!(player instanceof ServerPlayerEntity sp)) return PASS;`. For tools with a heavy server-only path (uses `world.getServer()`), add a fresh `if (world.isClient) return ActionResult.PASS;` AFTER the sneak/permission block so only the prediction runs on the client. (COL1 gives ColorSquareItem full prediction; the other 6 just need the guard removed.)

**Test:** Right-click each tool — feels instant, no duplicate chat/sounds.

### COL1c — Client Permission Bypass
**State:** 🧪 UNKNOWN — built, never tested.
**File:** `command/PermissionHelper.java` (`canUseTool`)
**Priority:** 🟠

Add `if (player.getWorld().isClient) return true;` as the FIRST line of `canUseTool()`. The server still validates and reverts if unauthorized. Without this, the client blocks its own prediction (permission can only be evaluated server-side).

### COL1d — Pre-warm cachedColorFamily on Client
**State:** 🧪 UNKNOWN — built, never tested. COL1's first-click prediction depends on this.
**File:** `client/CustomBlocksClient.java`
**Priority:** 🟠

`cachedColorFamily` is `transient` on `SlotData` → arrives null on the client → COL1 always falls through on first use. Fix: on the client, compute it with `ColorDetection.detect(d.texture)` on background daemon threads at four points:
- FullSyncPayload receiver (after `serverMaxSlots` is set) — thread `cb-color-prewarm`.
- `processSlotUpdatePayload` `case "sync_done"` — thread `cb-color-prewarm-post` (the main one that makes first-click work).
- `case "add"` (when `!joinBurst` and a texture is present) — thread `cb-color-prewarm-add`.
- `case "retexture"` — thread `cb-color-prewarm-rtx`.

All four use the same `ColorDetection.detect()` the server uses in `postProcessLoadedSlots()`.

**Test:** Join fresh, immediately right-click a color square — no delay on the very first click.

### COL10 — /cb colors Command + Hub Finish
**State:** ⚠️ PARTIAL — command CONFIRMED working (2026-05-29 s2); hub layout/features pending ("needs some working on").
**Files:** `command/CustomBlockCommand.java`, `gui/GuiManager.java`
**Priority:** 🟡

> `openColorsHub()` already exists in GuiManager. Read it fully before doing anything.

**Step 1 — command (done):**
```java
.then(CommandManager.literal("colors")
    .requires(PermissionHelper::canUse)
    .executes(ctx -> {
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p == null) { ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only")); return 1; }
        GuiManager.openColorsHub(p);
        return 1;
    }))
```

**Step 2 — hub layout (pending):** 54-slot — header + search (row 0), "My Colors" favorites (row 1), recent colors / last 8 used (row 2), "Create New Color" + "Rename a Tool" (row 3), welcome content or presets (row 4), footer (row 5). Footer: slot 45 fill-mode toggle (`corners_only → corners_and_trapped → none`, label from `formatColorToolMode()`), slot 47 tolerance display (click → chat prompt like `/cb tolerance`), slot 53 `?` help.

**Step 3 — rename tool flow:** "Rename a Tool" → 27-slot overlay of triangles/squares in inventory → click → anvil (use `AnvilPromptManager.java`) pre-filled with current name → write to `NBT_CUSTOM_NAME` + trigger COL6 Part C.

> Match existing `ui()`/`glass()`/`uiGlint()` patterns from `buildCustomColorStudioGui()` — don't invent new ones.

### COL11 — Color Tool on a Base Block
**State:** ✅ CONFIRMED IN-GAME (Session 7, 2026-06-01) — graceful fallback working.

**New issue found (2026-06-02) — COL Square detection bug:** Color square wrongly says "Already [color]" on blocks whose background does NOT match that color. `ColorDetection.detect()` reads all pixels including design elements instead of only edge/corner pixels. Fix in subplan `08_Fix_COL_Square_LANG1.md`. Correct behavior when no variant exists: `"No [color] variant found — use the [Color] Triangle to create one."`

### NF4 — Configurable Tool Colors
**State:** ✅ CONFIRMED IN-GAME (2026-06-02) — PACK2 resolved NF4 as side effect.
**Files:** `CustomBlocksConfig.java`, `CustomBlocksMod.java`, `client/ResourcePackGenerator.java`, `item/ColorSquareItem.java`, `item/ColorTriangleItem.java`
**Priority:** 🔵

**Screenshot Proof & Knowledge (2026-05-31):**
- Changing the config hex caused the triangles/squares to turn into Lapis Lazuli / Cyan Dye textures.
- **Root cause understanding:** The custom model generator failed or was delayed, causing Minecraft to fall back to the base item texture (which is a Dye item for these tools).

- **3a:** `CustomBlocksConfig` — `triangleBlackHex = "#0A0A0A"` (next to green/yellow/red), wired into load/save with `normalizeHexColor(...)`.
- **3b:** `CustomBlocksMod` — new helper `parseHexRgb(String hex, int dR, int dG, int dB)`; `triColors` built from `parseHexRgb(CustomBlocksConfig.triangleBlack/Yellow/Green/RedHex, ...)` instead of hardcoded ints. (Config loads before item registration — safe.)
- **3c:** `ResourcePackGenerator` — `squares`/`triangles` arrays built from `parseHexRgb(...)` of the four config hex strings; changing a hex + restart regenerates icons.
- **3e:** `ColorSquareItem.getName()` appends ` §8[hex]` via `builtInHex(colorWord)`; `ColorTriangleItem.getName()` appends ` §8[#RRGGBB]` from the item's RGB.

**Test:** Change `triangleRedHex` to `#FF6600`, restart → orange triangle icon + name shows `[#FF6600]`, paints orange.

### COL-LIMITS

**State:** Pending tracking. Extracted from history.

### COL8b — Red shade hex editor
Extracted from history.
