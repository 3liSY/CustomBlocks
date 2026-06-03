# CustomBlocks — Master Plan (Kanban)

## ▶ Resume Here (Session 9 ended — start fresh session for Session 10)

**Confirmed in-game (2026-06-02):** PACK2, COL12, G1, REL1, COL1b/c/d, G3, IMG1, IMG2, PIX1, COL3, NF4, TOL1, COL4, BGR1.
**Partially confirmed:** COL5/COL8 tooltips — fresh items only; old inventory drop+repick from creative.

**Built 2026-06-03 — NONE confirmed in-game yet:**
| Task ID | What | Failure history |
|---------|------|----------------|
| COL9 | Wizard trigger fix | Round 1: Color.decode raw input → fixed |
| COL9 | Dry Run safety | Round 1: Close Preview fired real batch → fixed |
| COL9 | BG mode selector slot 53 | Built cleanly first try |
| COL9 | recolourTextureForBatch (edge mode forced) | Round 2: player PLAYER_MODE "full" destroyed designs → fixed |
| COL9 | recolourTextureDirectSwap (direct old-hex swap) | Round 3: edge flood fill missed pixels → replaced with direct comparison |
| COL9 | Config sync timing fix | Round 3: ConfigSyncPayload fired before batch → moved to after batch |
| NF4 | ConfigSyncPayload (live item update) | Round 4: server pack never reached modded client → new packet built |
| NF4 | ServerPackGenerator colored PNGs | Built early but alone wasn't enough — needed ConfigSyncPayload too |
| COL Square | matchesColor() texture fallback | Built cleanly |
| COL Square | bg mode guard removed | Built cleanly |
| bg mode | "none" persists on restart | Built cleanly — "none" was missing from validation whitelist |

**Still broken (priority order):**
| Priority | Task ID | One-liner |
|----------|---------|-----------|
| 🔴 #1 | **SNP1** | Snapshots revert to wrong state, blocks go concrete — BROKEN. Read 08_SNP1.md before touching. |
| 🔴 #2 | **RT1** | Purple block 30+ seconds after rectangle tool |
| 🟡 #3 | **COL9/NF4** | Full hex update system — needs complete end-to-end in-game confirmation |
| 🟡 #4 | **LANG1** | `[<unknown_cb_tail>]` in action bar |
| 🔵 #5 | **MM1** | Full rework |
| 🔵 #6 | **AR1** | Arabic blocks not detected in /cb create, importfolder |

**DO NOT build without discussing first:** LIC1, AR2, AR3, IMG6.

**Latest JAR:** End of 2026-06-03. See active_session.md for full failure+fix history. NOTHING from 2026-06-03 confirmed in-game.

---

## State Legend

- ✅ **BUILT AND TESTED IN GAME** — developer confirmed working in-game. The ONLY "done".
- 🏗️ **UNDER CONSTRUCTION AND FIXING** — currently writing code or patching.
- 🔴 **BROKEN** — confirmed broken in-game, or a regression we introduced. Must fix before shipping.
- ⏳ **READY FOR IN-GAME VERIFICATION** — code written, build passes, NOT tested in-game. State unknown.
- 🧪 **UNKNOWN** — built in an earlier session, never tested in-game. Cannot claim it works.
- 🔍 **INVESTIGATE** — root cause not found; read the code before any fix.
- 💬 **DISCUSS** — needs a design decision from the developer first.
- ⏸ **WAITING** — blocked on developer input (screenshot, answer).
- ❌ **NOT STARTED** — no code written yet.
- ⚠️ **PARTIAL** — works for some cases, broken for others.

---

# Session Log

*Short dated history. Full issue detail lives in the Issue Registry above — this is just "what happened each day."*

- **2026-05-29 — Session 2 (color tools + image import batch).** Built COL1, COL1b, COL1d, COL2, COL6 (A/B), COL8 fix, IMG2, IMG3, IMG4, IMG5, NF4. Confirmed in-game: G2; COL10 command (hub pending). Discovered the curly-quote gotcha. Not committed.
- **2026-05-30 — Session 3 (metadata + new-issue triage).** Did LIC1/MM1 metadata edits (`fabric.mod.json`, `en_us.json`): license MIT→All Rights Reserved, version "1", author, Discord/YouTube via `custom.modmenu.links`. Logged a big batch of in-game issues (BGR1, COL11, RT1, UND1, PIX1, REL1) and agreed the BGR1 design + build order. Icon still "?".
- **2026-05-30 — Session 4 (Tier 1 investigation + builds).** Built REL1, RT1, UND1. UND1 partially confirmed in-game. Discovered the IMG4-S3 regression, REDO1, REDO2, and the UND1b gap.
- **2026-05-30 — Session 5 (regression fixes).** Fixed IMG4-S3 (confirmed in-game). Built REDO1 + REDO2 fixes. COL11 silent fix (later found wrong).
- **2026-05-30 — Session 5b (in-game results + root causes).** Confirmed in-game: IMG4-S3, REDO2, UND1. Built REDO1 second fix (missing UUID). Fully confirmed the PACK1 root cause (blocks REL1 + RT1). Confirmed the TOL1 root cause. Noted the COL11 correction. UND1b design confirmed.
- **2026-05-31 — Session 6 (quick features + NF2).** Confirmed in-game: CMD1 (`/cb settings` primary, `/cb config` alias), COL8b (red shade hex editor), AR1 (Arabic letter browser). Built COL9 (hex change → bulk recolor confirm screen using `recolourTextureForPlayer` + new `HEX_RECOLOR_CONFIRM` GuiMode). Built NF2 (Deleter Tool — `DeleterItem.java`, `/cb deleter` command, confirm GUI, shift=instant delete, trash fix for bulk delete, clickable undo link). Also discussed and cleared: NF2, AR2, AR3, COL8b, COL9, CMD1. SNP1 parked. AR2+AR3 deferred to a fresh session. Not committed.
- **2026-06-01 — Session 7 (PACK1 investigation & testing).** Confirmed in-game: NF2, COL1/2, PACK1, PACK2, COL11. Fixed and verified PACK2, COL11, and NF2 polish in game.
- **2026-06-01 — Session 8 (TOL1, PIX1, COL3/4).** Fixed TOL1 (auto-detect cap), PIX1 (pixelation due to nearest neighbor), and COL3/4 (BFS traversal + 1.5x expand threshold).
- **2026-06-02 — Session 9 (audit + batch testing).** Deep audit of 7 issues. Fixed: PACK2 (modded client guard), COL12 (disk fallback), G1 (2 back buttons), LANG1 partial (size_label rename), COL5 tooltips, RT1 broadcast order. Confirmed in-game: PACK2, COL12, G1, REL1, COL1b/c/d, G3, IMG1, IMG2, PIX1, COL3, NF4, TOL1, COL4, BGR1. Still broken: RT1 (30+ seconds), COL Square detection, LANG1 unknown_cb_tail. New subplans created: 05–11.
- **2026-06-03 — Session 9 continued (COL9 + NF4 — 4 rounds of fixes, nothing confirmed).** ROUND 1: COL9 wizard never opened (Color.decode raw input silent fail) + Dry Run Close Preview fired real batch (slot 49 collision with uiPage==9999 sentinel) — both fixed. Wizard confirmed opening in-game. ROUND 2: After confirming, blocks turned to yellow concrete — player PLAYER_MODE="full" ate all design pixels via recolourTextureForPlayer. Fixed: recolourTextureForBatch forces edge mode always. NEW RULE: never call recolourTextureForPlayer in batch ops. ROUND 3: Items updated but blocks didn't / two pack reloads — ConfigSyncPayload fired before wizard, causing early item-only reload. Fixed: moved broadcastConfigSync to end of runHexUpdateBatch. Also: edge flood fill missed background pixels → replaced with recolourTextureDirectSwap (direct per-pixel comparison against stored old hex, threshold 30). ROUND 4: Item textures never reached modded client — PACK2 guard blocks HTTP pack entirely. Modded clients generate textures locally from stale config. Fixed: ConfigSyncPayload broadcasts new hex values after batch; client updates config + calls scheduleGenerateAndReload → one combined pack reload. Also built this session: BG mode selector in wizard slot 53 (never touches global config), COL Square matchesColor() texture fallback, bg mode "none" persists on restart. End of session: developer reported /cb snapshots broken (blocks going concrete) — not investigated, SNP1 moved to top priority.
- **Git:** working tree DIRTY + UNCOMMITTED. JAR built end of 2026-06-03. NOTHING confirmed in-game.

---

## 2. 🟢 Ready for Testing (Built)

---
Task ID: TOL1
---
### TOL1 — Tolerance Has No Effect
**State:** ✅ CONFIRMED IN-GAME (2026-06-02) — Tolerance correctly samples edge pixels (including non-white backgrounds like grey). Mode prompt logic implemented for setting tolerance > 0.
**File:** `core/ImageProcessor.java`, `gui/GuiManager.java`

---
Task ID: PIX1
---
### PIX1 — New Blocks Come Out Pixelated
**State:** ✅ CONFIRMED IN-GAME (2026-06-02) — bicubic scaling working. Slight pixelation when zoomed very close is a Minecraft platform limit. Accepted, no further fix.
**Files:** `core/ImageProcessor.java`

---
Task ID: COL3
---
### COL3 — Enclosed Holes Not Recoloring
**State:** ✅ CONFIRMED IN-GAME (2026-06-02) — enclosed holes fill correctly.
**Files:** `item/ColorTriangleItem.java`

---
Task ID: COL4
---
### COL4 — Yellow Pixel Outline After Recoloring
**State:** ✅ CONFIRMED IN-GAME (2026-06-02) — edge halo removal confirmed working alongside the TOL1 fix.
**Files:** `item/ColorTriangleItem.java`

---

## COL12 — Random Blocks Lose Texture Data
**State:** ✅ CONFIRMED IN-GAME (2026-06-02) — disk fallback in `ColorVariantService.resolvePrimaryTexture()` working. No further action needed.

---

---

---
Task ID: LANG1
---
### LANG1 — `[<unknown_cb_tail>]` Shows in Command Action Bar
**State:** 🔴 BROKEN — confirmed in-game 2026-06-02. Typing `/cb resize` shows `[<unknown_cb_tail>]` in the action bar hint.
**Priority:** 🟡
**File:** `command/DidYouMean.java` (line 52)
**Subplan:** `02_LANG1.md`

**Root cause:** `DidYouMean.java` registers a catch-all argument literally named `"unknown_cb_tail"`. Brigadier displays argument names directly, so this shows verbatim.
**Fix:** Rename `"unknown_cb_tail"` → `"subcommand"` in both the argument definition (line 52) and retrieval (line 55).

**Note:** The `size_text` → `size_label` rename in `CustomBlockCommand.java` was already applied in this session (2026-06-02). That part is done.

---

---

---
Task ID: PACK2
---
### PACK2 — Modded Clients Receiving Vanilla Fallback Pack
**State:** ✅ CONFIRMED IN-GAME (2026-06-02) — guard in `ResourcePackServer.sendPackToPlayer()` working. Tools no longer show as dyes. Survives `/cb reload`. Also resolved NF4 as side effect.
**Files:** `network/ResourcePackServer.java`
**Subplan:** `05_PACK1-2_REL1_RT1.md`

---

---

---
Task ID: COL3
---
### COL3 — Landlocked Same-Color Areas Not Recoloring
**State:** ✅ CONFIRMED IN-GAME (2026-06-02) — enclosed holes fill correctly.
**File:** `item/ColorTriangleItem.java`

---

---

---
Task ID: COL4
---
### COL4 — Yellow Pixel Outline After Recoloring
**State:** ✅ CONFIRMED IN-GAME (2026-06-02) — confirmed working.
**File:** `item/ColorTriangleItem.java`

---

---

---
Task ID: COL5
---
### ~~COL5~~ — Merged into COL8 (2026-06-02)
**State:** ✅ Tooltip/lore work done — absorbed into COL8 scope.

---

---

---
Task ID: COL8
---
### COL8 — All Magic Items Turn Into Dyes (was: Red only)
**State:** ✅ PACK2 CONFIRMED IN-GAME (2026-06-02). COL8 tooltip ⚠️ PARTIAL — double tooltip on old inventory items (old LoreComponent still cached). Fresh items from creative show correctly.
**Files:** `network/ResourcePackServer.java` (via PACK2 fix)
**Priority:** 🟡 (blocked by PACK2)

**Merged from COL5 (2026-06-02):** Tooltip/lore is ⏳ done (needs in-game confirm) — unified `appendTooltip` on all Triangle/Square items, spammy chat hints removed from `inventoryTick`.

**Audit note (2026-06-02):** Dye mappings in `ServerPackGenerator.java` are intentional vanilla fallbacks. Bug is that modded clients receive this pack via `sendPackToPlayer()` with no mod-check guard. PACK2 subplan: `05_PACK1-2_REL1_RT1.md`.

> `CustomBlocksConfig.triangleRedHex` default is `"#EE3333"`. COL8b adds a Red hex editor in `/cb config`. See also CMD1.

**Test (after PACK2):** Pick up any color triangle or square — shows correct colored tool texture (not a dye). `/cb square red` appears red and swaps blocks correctly.

---

---

---
Task ID: COL9
---
### COL9 — Hex Change → Update Existing Blocks
**State:** ⏳ READY FOR IN-GAME TEST — full rework was written in previous session; two bugs found and fixed 2026-06-03. All 10 flaws addressed in code. See subplan `01_COL1-12_NF4_COL-LIMITS.md` for full detail and test steps.
**File:** `gui/GuiManager.java`
**Priority:** 🟡
**Subplan:** `01_COL1-12_NF4_COL-LIMITS.md`

**Bugs fixed 2026-06-03:**
1. Wizard never opened — `Color.decode(text.trim())` failed silently on inputs without `#`; fixed to `Color.decode(normalizeHexInput(text))`
2. Dry Run "Close Preview" (slot 49) fired the real batch update — state sentinel `uiPage==9999` collided with Confirm handler; fixed with guard at top of slot 49 branch

---

---

---
Task ID: IMG2
---
### IMG2 — Background Removal "None" Mode Toggle
**State:** ✅ CONFIRMED IN-GAME (2026-06-02) — "None" mode working. Pending: UI label renames in subplan `06_IMG1-6_S3_TOL1_PIX1_BGR1.md` (BGR1 section B).
**Files:** `gui/GuiManager.java`
**Pending rename:** `corners_only` → "Remove Background", `corners_and_trapped` → "Remove Background + Holes", `none` → "No Background Removal".

---

---

---
Task ID: IMG4
---
### IMG4 — Transparent Pixels Wrongly Treated as Background
**State:** 🔴 BROKEN (Fake Transparency)
**File:** `core/ImageProcessor.java`
**Priority:** 🔴

**Problem:** The original fix successfully prevents real transparent pixels from seeding the flood-fill (confirmed in IMG4-S3). However, when users upload "fake transparent" images (JPEGs/PNGs where the grey-and-white checkerboard is baked into the actual pixels), the background removal fails because the checkerboard is not a uniform solid color. 
**Knowledge (2026-05-31):** The user uploaded a fake-transparent sheep image. The tool correctly removed the white squares of the checkerboard, but left the grey lines intact, resulting in a mesh background. This is technically expected behavior for fake transparency, but logged as broken because the user expects it to be removed. May need BGR1's AI to solve.

---

---

---
Task ID: NF2
---
### NF2 — Deleter Tool Item
**State:** ✅ CONFIRMED IN-GAME (Session 7, 2026-06-01) — Deleter tool working correctly.

---

---

---
Task ID: NF4
---
### NF4 — Configurable Tool Colors
**State:** 🔴 BROKEN for modded clients (confirmed 2026-06-03). ServerPackGenerator fix only helps vanilla clients. Modded clients generate textures locally and need a config sync packet.
**Files:** `CustomBlocksConfig.java`, `CustomBlocksMod.java`, `client/ResourcePackGenerator.java`, `network/ServerPackGenerator.java`, `item/ColorSquareItem.java`, `item/ColorTriangleItem.java`
**Priority:** 🔵

**What works:** `ServerPackGenerator` now generates real colored PNGs from config hex (built 2026-06-03). This works for vanilla (non-modded) clients.

**What's broken for modded clients — TWO root causes:**
1. **Name/hex text stale:** Client has its own `CustomBlocksConfig` copy. Server hex change never reaches client. `getName()` reads stale client config → old hex in name forever until reconnect.
2. **Visual texture stale:** PACK2 guard (`sendPackToPlayer` returns false for modded clients) means the new server pack ZIP never reaches the modded client. Modded client runs `ResourcePackGenerator` locally from its own stale config → generates old colored textures.

**Fix needed (not yet built):**
- `ConfigSyncPayload` — new network packet carrying the four hex values
- Server broadcasts it to all online clients when any hex changes in config
- Client handler updates `CustomBlocksConfig` → fixes name instantly
- Client then re-triggers `ResourcePackGenerator` → fixes visual (causes brief texture flash)
- Developer decision pending: accept the brief flash, or accept visual stays old until reconnect

**COL9 — Per-batch BG mode selector (planned, not yet built 2026-06-03):**
`runHexUpdateBatch` hardcodes `false` for `useTrappedHoleFill`. Developer wants a cycling button at slot 53 of the wizard to pick bg mode for this batch only — does NOT affect `/cb config`. Three states: `corners_only` (Lime Dye), `corners_and_trapped` (Lime Concrete), `none` (Light Gray Concrete). Default = current config value. Only `gui/GuiManager.java` changes. Full plan in subplan `01_COL1-12_NF4_COL-LIMITS.md`.

---

---

---
Task ID: AR1
---
### AR1 — Import Pre-made Letter PNGs + Browser GUI
**State:** ⚠️ PARTIAL — Browser GUI confirmed working in-game (2026-06-02). Known issue: Arabic blocks not auto-detected in `/cb create`, `/cb importfolder`, or existing block lists. Fix: unify Arabic letter detection across all import paths.

`/cb arabic import <base_path>` scans `BLACK/ YELLOW/ GREEN/ RED/` for `<letter>_<color>.png` + `arabic_numbers_png/` for `a<digit>_<color>.png`, reads bytes directly (no processing), calls `SlotManager.assign(customId, displayName, bytes)`, sets letter metadata (`isLetter=true`, `letterGroup="arabic_<name>"`, `letterForm="isolated"`, `letterConnectsLeft` per letter — alef/dal/ra/waw etc. do not join), saves the registry, rebuilds the pack.

`/cb arabic` or `/cb arabic gui [color]` — 54-slot browser: color tabs (Black/Yellow/Green/Red), letter grid, pagination, click a letter → receive 1 block, Back returns. `/cb arabic give <letter> <color>` gives one. `/cb arabic text <color> <text>` parses Arabic text → one block per character in placement order.

**Test:** deploy → `/cb arabic import C:\Users\66664\OneDrive\Desktop` → wait ~10s + reconnect → `/cb arabic` opens → click a letter → place it (matches the PNG) → `/cb arabic give ba black` gives the Ba black block.

---

---

## 3. 🏗️ Under Construction

---

## 4. ⏳ Ready for In-Game Verification
---
Task ID: REL1
---
### REL1 — `/cb reload` Data-Loss / Blocks Break Visually
**State:** ✅ CONFIRMED IN-GAME (2026-06-02) — /cb reload works, no data loss, no rejoin needed.
**Files:** `core/SlotManager.java`, `command/CustomBlockCommand.java`
**Priority:** 🔴

**Root cause:** `flushSave()` called `IO_EXECUTOR.shutdown()`, permanently killing the IO thread mid-session. Plus a tick-based batch-loader race (the pack was rebuilt before all blocks finished loading).

**Fix built:** New `flushSaveForReload()` that saves without shutting down IO + a wait loop for `startupLoadInProgress = false` before the pack rebuild + a `RELOAD_IN_PROGRESS` lock to prevent concurrent reloads. Data now saves correctly (rejoin proves it). The remaining visual breakage is PACK1 — the pack never reaches the client after reload.

**Test (after PACK1):** `/cb reload` → all blocks survive and stay visible, no rejoin needed.

---

---

---
Task ID: G1
---
### G1 — Back Buttons Go to Main Menu Instead of Previous Screen
**State:** ✅ CONFIRMED IN-GAME (2026-06-02) — both violations fixed, back buttons working.
**File:** `gui/GuiManager.java`
**Priority:** 🔴
**Subplan:** `09_GUI1-3.md`

**Violations (confirmed by audit 2026-06-02):**
- Line 1368: WELCOME_MENU back button calls `openMain(player, 0)` → should be `handleEscBack(player)`
- Line 2990: UNDO_PICKER back button calls `openMain(player, 0)` → should be `handleEscBack(player)`

**Test:** Open welcome screen → Back → goes to previous screen. Open `/cb undogui` → Back → goes to previous screen.

---

---

---
Task ID: G3
---
### G3 — 10 Missing Cases in restoreState()
**State:** ✅ CONFIRMED IN-GAME (2026-06-02) — Back/ESC working on all 10 screens.
**File:** `gui/GuiManager.java` (`restoreState()`)
**Priority:** 🟠

These GuiMode values exist in `GuiMode.java` but were missing from the `restoreState()` switch: `BULK_ASSIGN_PICKER`, `BULK_RECOLOR_CONFIRM`, `BULK_RECOLOR_WIZARD`, `CATEGORY_BLOCK_CONTEXT`, `CATEGORY_ICON_PICKER`, `CATEGORY_STATS`, `DELETE_CATEGORY_MENU`, `IMPORT_CONFLICT`, `MERGE_CATEGORY_PICKER_TARGET`, `SORT_BLOCKS_MENU`.

For each: find the corresponding `open*()` method, read its real parameter signature, then add the case using parameters from `state`. Do NOT copy signatures from this plan — verify each by reading the method.

**Test:** Enter each of the 10 screens, press ESC. Should return to the previous screen, not main menu.

---

---

---
Task ID: COL1b
---
### COL1b — Remove Client Skip from ALL 7 Tools
**State:** ✅ CONFIRMED IN-GAME (2026-06-02) — tools feel instant, no delay.
**Files:** `ColorSquareItem`, `ColorTriangleItem`, `RectangleToolItem`, `AmethystChiselItem`, `LuminaBrushItem`, `GoldenHexagonItem`, `DiamondTriangleItem`
**Priority:** 🟠

Each tool had `if (world.isClient) return ActionResult.PASS;` in `useOnBlock()`, causing delay. For each: delete that line, and wrap every `player.sendMessage(...)` / `playSound(...)` with `if (!world.isClient)` to avoid duplicate feedback. Each method self-gates via `if (!(player instanceof ServerPlayerEntity sp)) return PASS;`. For tools with a heavy server-only path (uses `world.getServer()`), add a fresh `if (world.isClient) return ActionResult.PASS;` AFTER the sneak/permission block so only the prediction runs on the client. (COL1 gives ColorSquareItem full prediction; the other 6 just need the guard removed.)

**Test:** Right-click each tool — feels instant, no duplicate chat/sounds.

---

---

---
Task ID: COL1c
---
### COL1c — Client Permission Bypass
**State:** 🧪 UNKNOWN — built, never tested.
**File:** `command/PermissionHelper.java` (`canUseTool`)
**Priority:** 🟠

Add `if (player.getWorld().isClient) return true;` as the FIRST line of `canUseTool()`. The server still validates and reverts if unauthorized. Without this, the client blocks its own prediction (permission can only be evaluated server-side).

---

---

---
Task ID: COL1d
---
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

---

---

---
Task ID: LIC1
---
### LIC1 — License Display
**State:** 💬 DISCUSS — **DO NOT build until the developer says "build".** (The fabric.mod.json label fix is the one buildable part; the MM1 entry covers what's already done there.)
**Files:** `CustomBlockss/LICENSE` (+ `LICENSE-ar`), `fabric.mod.json`, `gui/GuiManager.java` (`buildMain`), `command/CustomBlockCommand.java`
**Priority:** 💬

**The real license is "All Rights Reserved"** (proprietary, Copyright Srb Gamer / 3liSY). Allows: play free, videos with credit + link, private edits (no sharing), unmodified modpack/server use with credit + link. Forbids: repost/reupload, using the code in other mods, claiming authorship, removing credit, public modified versions.

**Bug fixed (Session 3):** `fabric.mod.json` said `"license": "MIT"` — the OPPOSITE of the real license. Changed to "All Rights Reserved".

**LICENSE file to reconcile:** line 4 has a placeholder `[your link]` (official download URL); it's signed "Srb Gamer" while the author field is "3liSY" — confirm same person / pick one.

**Wanted (design in progress):**
- `/cb license` command — colored chat + clickable links (Official Download / GitHub / Full License, + Discord/YouTube if wanted). Public, no permission. Reuse the `ClickEvent` OPEN_URL pattern.
- "📜 License" button in the main `/cb` GUI (`buildMain` — find an empty slot first).
- Mod Menu: correct label + a clickable "License" link.
- Developer still choosing colors/theme, which links + URLs, extra content.

**Build plan (tested pieces):** Step 1 = label fix + `/cb license` command. Step 2 = GUI button + Mod Menu link.

---

---

---
Task ID: AR2
---
### AR2 — Word Block Generator
**State:** 💬 DESIGN APPROVED — ready to build samples. Font + style + background modes confirmed.
**Files:** new `item/ArabicWordBlockItem.java` (or `/cb arabic word` command path), new `texturegen/ArabicWordBlockRenderer.java`, `gui/GuiManager.java`, `CustomBlocksConfig.java`

**Design locked (2026-05-31, final round):**
- **Font:** `arabtype.ttf` bundled with mod.
- **Text size:** Small / Medium / Large picker in the GUI, with live preview showing each size on the block face.
- **Outline color:** Default = BLACK (matches letter PNGs). Optional: player can pick a custom outline color if they want.
- **Background:** 4 presets (Black / Green / Yellow / Red) + Transparent — shown as quick buttons. Custom hex color picker planned for later (not in first build).
- **Duplicate handling:** If word+style+colors exist → "Use existing or create a new variant?" (YES/NO).
- **Samples before build:** Generate 1–2 sample word blocks for approval first.

**Command:** `/cb arabic word` opens the creation GUI. **Flow:** (1) anvil: type word → (2) color picker: letter color → (3) color picker: background color (4 presets + transparent) → (4) thickness: None/Thin/Medium/Thick → (5) size: Small/Medium/Large (with preview of each) → (6) outline: Black (default) or pick color → (7) preview ("Looks good" / "Change something") → (8) anvil: name (default auto) → (9) confirm + receive.

**Rendering (server-side Java2D):** bundled `arabtype.ttf` → draw Arabic text RTL (Java handles joining automatically) → apply thickness style (outline stroke + color fill) → fill background (solid preset or transparent alpha) → 128×128 PNG on all 6 faces. **BUILD 1–2 samples first; show for approval before batch-generating new words.**

---

---

---
Task ID: AR3
---
### AR3 — Auto-Joining Letter Blocks (Toggleable)
**State:** 💬 DESIGN APPROVED — ready after AR2 confirmed. Form generation + neighbor detection locked.
**Files:** form generation task, `core/ArabicAutoJoinManager.java`, `core/SlotData.java` (add `letterForm`), `CustomBlocksConfig.java` (add `arabicAutoJoin` toggle)

**Design locked (2026-05-31):**
- **Form generation:** Show **Ba, Seen, Ain** in all 4 forms (Isolated/Initial/Medial/Final) using the letter PNG style for approval FIRST, then batch-generate all ~48 letters × 3 forms × 4 colors ≈ 576 blocks.
- **Placement detection:** On block placement, check east/west neighbors. If both are letter blocks in the same `letterGroup`, determine correct form for each and swap. Use metadata: `isLetter`, `letterConnectsLeft` (per letter), `letterGroup`, `letterForm`.
- **Non-joining rule:** Letters that do NOT connect left (alef, dal, ra, waw, etc.) remain Isolated always.
- **Toggle:** Config field `arabicAutoJoin` (boolean), default ON.

**Workflow:**
1. **Step 1a — render samples:** Use same renderer as letter PNGs (or Java2D replica) to generate **Ba, Seen, Ain** in all 4 forms.
2. **Step 1b — show for approval:** Developer reviews samples (shape, thickness, legibility).
3. **Step 2 — batch-generate:** After approval, generate all letters × 3 forms × 4 colors.
4. **Step 3 — placement logic:** On right-click placement of a letter block, `ArabicAutoJoinManager.onLetterPlaced(blockPos, letterBlock)` checks neighbors, updates form fields, broadcasts to all clients.

---

## Known Undiagnosable Issue

**Random texture breaks (purple/black blocks)** — Cannot fix without server logs. When it happens: note the block ID and check `logs/latest.log` for `[ResourcePackServer]` or `[CB]` lines.

---

---

## 5. 💬 Blocked / Needs Discussion
---
Task ID: RT1
---
### RT1 — Rectangle Tool Block Stays Purple 30+ Seconds
**State:** 🔴 BROKEN — confirmed in-game 2026-06-02. Block stays purple for 30+ seconds after placement or never resolves without rejoin. NOT normal rebuild delay. HIGH PRIORITY.
**Files:** `item/RectangleToolItem.java`, `network/ResourcePackServer.java`
**Priority:** 🔴
**Subplan:** `05_PACK1-2_REL1_RT1.md`

**Hypothesis:** 30-second debounce timer in ResourcePackServer is delaying the pack rebuild notification after rectangle tool use. Needs deep investigation of the full pack rebuild trigger chain.

---

---

---
Task ID: RECENT1
---
### RECENT1 — /cb recent Full Rework
**State:** 💬 DISCUSS — developer wants a full rework, not enough detail yet. Flagged for next session.
**Priority:** 🟠

---

---

---
Task ID: SNP1
---
### SNP1 — Snapshots: BROKEN + Full Rework Required
**State:** 🔴 BROKEN — confirmed in-game 2026-06-03. VERY BAD. Top priority. DO NOT TOUCH until investigation is complete.
**File:** `core/SnapshotManager.java`, `gui/GuiManager.java` (snapshots GUI)
**Priority:** 🔴 #1
**Subplan:** `08_SNP1.md`

**Confirmed bug (2026-06-03):** Snapshots keep reverting to a different one after every server restart or rejoin, regardless of which snapshot the developer selects. The system does not respect the chosen state — it keeps loading from a snapshot it "desires" on its own.

**This needs DEEP INVESTIGATION before any code is written.** Do NOT guess at a fix. Read `SnapshotManager.java` fully, trace the auto-restore path, and identify exactly why the wrong snapshot is being loaded on startup.

**Known questions to answer during investigation:**
- Is there an auto-restore on startup that ignores the manually selected snapshot?
- Is the "selected" snapshot being saved to disk, or only held in memory (lost on restart)?
- Is there a race condition between the auto-snapshot and the restore path?
- Is `/cb panic` or a pre-op snapshot silently overwriting the manual restore?

**Current system (what exists):** snapshot = full GZip JSON of ALL slots (`SlotManager.serializeSnapshotToJson`), stored in `config/customblocks/snapshots/`, max 20, auto every N min + manual + pre-op. `/cb snapshots` → `openSnapshotsGui`; `/cb panic` = 2-step rollback; restore is all-or-nothing.

**Developer wants (after the bug is fixed):** per-block rollback, a GUI button to CREATE a named snapshot, better naming system (timestamps are bad), search, and a "lock" so a snapshot can't be auto-overwritten.

---

## Group 7 — Branding & License

---

---

## 6. ❌ Backlog / Not Started
## Group 3 — Undo / Redo / History

> See also **REDO1** (broken), **REDO2** (confirmed) in Group 1, and **G7** (history GUI) in Group 2.

---

---

## Group 4 — Color Tools

---

---

---
Task ID: COL11
---
### COL11 — Color Tool on a Base Block
**State:** ✅ CONFIRMED IN-GAME (Session 7, 2026-06-01) — graceful fallback working.

**COL Square detection bugs (tracked separately — see COL Square entry and subplan `01_COL1-12_NF4_COL-LIMITS.md`):**
- Reverse detection ("No black variant found" on a genuinely black block) — fixed 2026-06-03 via texture fallback in `matchesColor()`
- Color Square no longer requires bg mode configured — fixed 2026-06-03
Both fixes are ⏳ pending in-game confirmation.

---

---

---
Task ID: COL-LIMITS
---
### Color Tools — Known Limitations (not planned)
Possible but not planned; research required before any planning:
- **Screen eyedrop** (sample any screen pixel) — requires LWJGL framebuffer access.
- **Live recolor preview in GUI** — server-side texture processing on hover is slow; needs a client-side solution.
- **Creative search for custom triangles** — MC search doesn't index custom NBT names; needs a client mixin.

---

## Group 5 — Image Import & Background Removal

> The BGR1 rework largely SUPERSEDES IMG2/IMG3/IMG4/IMG5 — confirm or fold those into BGR1 rather than re-testing separately.

---

---

---
Task ID: BGR1
---
### BGR1 — Smart Background Removal Rework
**State:** ✅ PARTIAL / DONE IN-GAME (2026-06-02) — Auto-detect removed, labels renamed. Future smart system C pending separate plan. See subplan `06_IMG1-6_S3_TOL1_PIX1_BGR1.md` (BGR1 section).
**Files:** `core/ImageProcessor.java`, `gui/GuiManager.java`
**Priority:** 🟠
**Subplan:** `06_IMG1-6_S3_TOL1_PIX1_BGR1.md`

**Immediate changes (confirmed 2026-06-02):**
1. Removed auto-detect — always use manual `/cb tolerance` value, no guessing
2. Renamed bg mode labels: `corners_only` → "Remove Background", `corners_and_trapped` → "Remove Background + Holes", `none` → "No Background Removal"

**Future smart system (not started):** Smart border-color sampling, slider filmstrip preview, feedback buttons, before/after preview.

Confirmed by developer: basketball = red triangle recolor; sheep = `/cb create` import. `tolerance <= 0` removes nothing. `padToSquare` runs before `replaceBackground` in the rectangle path, so padding is subject to removal.

**The plan:** Build ONE shared smart background detector — sample the real border/corner color, measure contrast to the subject, auto-pick a tolerance that erases the background but stops before the subject — and route BOTH the import path AND the triangle-recolor path through it so they behave identically.

**Agreed feature set (developer wants ALL, combined). HONEST SCOPE: not a neural net — smart heuristics + one remembered-corrections "brain" that gets smarter overall.**
- **Smart auto-detect** — sample real corner/border color, auto tolerance. (Fixes the sheep.)
- **Slider-pick** — pre-render a handful of strengths ("barely touched" → "aggressive"); scrub a filmstrip and pick the best (instant because pre-made).
- **Quick buttons after import** — "took too much / perfect / left too much" → nudge the ONE global brain (`bg-learning.json`).
- **Before/after preview** button.
- **Message feedback — BOTH:** (A) offline vocabulary, always-on, free, no internet — recognizes "too much", "still white on the left", "you ate the lines", "more/less". (B) optional real-AI for free-form sentences — OPT-IN ONLY (dev API key + internet + small per-use cost); stays OFF unless explicitly enabled; built DEAD LAST. (Developer has trojan/internet-wariness history.)

**Build order (do NOT skip ahead; test each in-game before the next):**
```
1. Smart auto-detect (fixes the sheep)
2. Slider-pick filmstrip
3. Quick buttons + one-brain memory
4. Before/after preview
5a. Offline message vocabulary
5b. Optional AI message feedback (last)
```
Also route the triangle recolor path through the same detector (its own tested step after piece 1) so the basketball stops eating black lines.

---

---

---
Task ID: IMG5
---
### IMG5 — Per-Upload Shift Key: Skip BG Removal for One Block
**State:** 🔴 SCRAPPED — confusing/unusable, superseded by BGR1.
**File:** `item/RectangleToolItem.java`
**Priority:** 🟡

Hold Shift while uploading a URL → background removal skipped for that one block, even if the global toggle is ON.
- `PendingSession` gained `boolean skipBgRemoval`. `useOnBlock()` captures `player.isSneaking()` and passes it in (+ a hint line when true). `handleChatInput()` wraps the call: `if (!skipBgRemoval) faceBytes = ImageProcessor.replaceBackground(faceBytes);`

**Test:** Normal upload → bg removed. Shift during the URL prompt → same image keeps its background, only that one upload.

---

---

---
Task ID: IMG5+
---
### IMG5+ — Extend Shift-Skip to /cb create and /cb importfolder
**State:** 🔴 SCRAPPED — superseded by BGR1.
**File:** `command/CustomBlockCommand.java`
**Priority:** 🟡

Thread the `skipBgRemoval` flag through the `/cb create` and `/cb importfolder` call chains down to `ImageProcessor.replaceBackground()`. Find the upload trigger, read the full call chain, add the parameter to each signature.

---

## Group 6 — New Features

---

---

---
Task ID: MM1
---
### MM1 — Mod Menu Entry (icon + extras)
**State:** 🔴 FULL REWORK NEEDED (2026-06-02) — everything wrong: name, author, description, icon (missing), links. Needs complete redesign and rebuild.
**Files:** `fabric.mod.json`, `assets/customblocks/icon.png` (missing), `en_us.json`
**Priority:** 🟡

**Done (Session 3 + developer edits):** `version="1"`, `license="All Rights Reserved"`, `description="Make any image or gif a working block :)"`, `authors=["3liSY / SrbGamer"]`, `contact` = homepage only (Modrinth). VERIFIED (web-checked): custom contact keys like "Our Discord" do NOT render in Mod Menu — only `homepage`/`sources`/`issues` + the `custom.modmenu.links` section show. So Discord + YouTube were moved into `custom.modmenu.links`: `modmenu.discord` (built-in "Discord" label) + `customblocks.youtube` (custom key; "YouTube" label added to `en_us.json`). Both files validated (no BOM, valid JSON).

**Pending:** the icon PNG at `assets/customblocks/icon.png` (developer provides a logo, or generate one). Optional/on request: wiki / CurseForge / donation links, a custom colored BADGE (needs ModMenuApi code, not pure JSON), a Configure button (ModMenuApi; LIMITATION: can only open settings while IN A WORLD, not from the title screen).

---

## Group 8 — Arabic Letter & Word Block System

*Started 2026-05-30. Same rule: nothing is ✅ DONE until confirmed in-game.*

**What exists in code now:** `arabic/ArabicLetterMap.java` (letter→Unicode data), `arabic/ArabicBlockRegistry.java` (saves (letter,color)→block ID to `config/customblocks/arabic-registry.json`, loads on start), one line in `CustomBlocksMod.java` to load it, `ARABIC_BROWSER` GuiMode, the browser GUI in `GuiManager.java`, and `/cb arabic import|gui|give|text`. Build passes, BOM-free, NOT deployed/tested. **Word generator (AR2) and auto-joining (AR3) do not exist yet.**

**Rule:** Do not start AR2 until AR1 is confirmed in-game. Do not start AR3 until AR2 is confirmed.

---

---

## 7. 🗄️ Archive (✅ WORKING)
## Group 2 — GUI Navigation

---

---

---
Task ID: COL10
---
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

---

---

---
Task ID: TOL1
---
### TOL1 — Tolerance Capped by Auto-Detect (80 == 30)
**State:** 🔴 BROKEN — root cause confirmed (Session 5b). Not built.
**File:** `core/ImageProcessor.java` (`replaceBackground()`), `gui/GuiManager.java` (`/cb config` GUI)
**Priority:** 🔴

**Root cause:** `effectiveTol = (cfgTol > 0) ? Math.min(autoTol, cfgTol) : autoTol;` — auto-detect computes ~20, the user sets 80, `Math.min(20, 80) = 20`. The manual setting is ignored.

**Fix:** `effectiveTol = (cfgTol > 0) ? cfgTol : autoTol;` — manual wins, auto is fallback only. Also add a 0–100 scale in config and an auto-detect on/off toggle in the `/cb config` GUI. (Tolerance sample previews = the BGR1 slider-pick, a separate feature.)

**Test:** Tolerance 80 vs 30 give clearly different results.

---

---

---
Task ID: IMG1
---
### IMG1 — Download Headers + Auto-Detect Tolerance
**State:** ✅ CONFIRMED DONE (2026-06-02). CDN headers work for Discord/most sites. WixMP permanently blocked at datacenter level — known limitation, error message already guides users. No further fix planned.
**File:** `core/ImageProcessor.java`
**Priority:** 🟡

**Built:**
- Download sends real Chrome User-Agent + Accept + Referer headers — fixes WixMP, DeviantArt, and most CDN hotlink 403s.
- Better 401/403 message: "open in browser → right-click → Copy image address → paste that".
- Config field `bgRemovalAutoDetect` (default `false`).
- BG Studio GUI: slot 11 toggle `Auto-Detect ON/OFF`; when ON, slot 18 label changes to "Max Cap" instead of "Tolerance".
- `autoTolerance(BufferedImage)` — NOT found in source during verification; check if it was built under a different name before assuming it still needs writing.

**Test:** A WixMP/DeviantArt URL downloads instead of "No permission". Auto-Detect ON preserves dark details on a white background; OFF is more aggressive. Cap at 15 with Auto-Detect ON → auto can't exceed 15.

---

---


## 8. Legacy Royal Directive Verification Data (2026-05-26)

### Verified Working
| Feature | Confirmed by |
|---------|-------------|
| Blocks load with correct textures on join | Screenshot |
| HUD overlay shows block info when looking at a custom block | Screenshot |
| Creating a new block | Developer confirmed |
| Block editor opens and shows correct info | Developer confirmed |
| Color square/triangle applies recolor | Developer confirmed (has delay issue) |
| Blocks survive server restart | Developer confirmed |
| Build compiles clean | `./gradlew build` passes |

### Known Broken or Missing
| # | Feature | Problem | Priority |
|---|---------|---------|----------|
| 1 | HUD editor (`/cb edithud`) | Not built — prints "coming soon" — developer wants Lunar-style drag editor | HIGH — developer explicitly said this is what they want most |
| 2 | Main GUI layout | Messy, items in wrong slots, mostly empty, "Celestial Nexus" tooltip | MEDIUM |
| 3 | Help GUI | Half-empty, items show raw Minecraft IDs on hover | MEDIUM |
| 4 | Color square delay | Noticeable delay between right-click and recolor applying | MEDIUM — AI tried to fix this multiple times and failed |
| 5 | Random texture breaks | Some blocks occasionally go purple/black | LOW — needs server log to diagnose, can't fix blind |

