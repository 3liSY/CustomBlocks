# Active Session — Session 9 → Session 10 Handoff

---

## 🤝 NEXT AI — READ THIS ENTIRE FILE BEFORE DOING ANYTHING

You are inheriting a session that had multiple failures and re-fixes on 2026-06-03. The JAR is built. Some things are confirmed in-game. Some are not. The developer filled in the test checklist below — read it before touching any code.

**Mandatory reading order:**
1. This file top to bottom
2. `../MASTERPLAN.md` — priority queue + issue registry
3. `08_SNP1.md` — SNP1 is top priority, read before touching snapshot code
4. `01_COL1-12_NF4_COL-LIMITS.md` — full COL9/NF4 failure history and current code state
5. `../THE_ROYAL_DIRECTIVE.md` — sacred rules

**Codebase:** `C:\Users\66664\OneDrive\Desktop\Coding\CustomBlockss\`

**Critical rules added this session (do NOT violate):**
- NEVER call `recolourTextureForPlayer` in batch operations — player PLAYER_MODE can be "full" and will destroy designs. Use `recolourTextureForBatch` or `recolourTextureDirectSwap` instead.
- ConfigSyncPayload must fire AFTER block texture updates complete (end of runHexUpdateBatch), not on config save. Otherwise pack reloads too early with stale block textures.
- Never touch `ResourcePackServer.java` carelessly — causes purple/missing textures for ALL players.
- Never inject curly quotes — breaks Java compiler.

---

## 📋 DEVELOPER TEST RESULTS — FILL THIS IN BEFORE STARTING NEW SESSION

*Developer: go through each test, mark ✅ PASS or ❌ FAIL, add notes if something was wrong.*

### COL9 — Hex Update Wizard

| # | Test | Result | Notes |
|---|------|--------|-------|
| 1 | Change `triangleYellowHex` in `/cb config` → wizard opens immediately | | |
| 2 | Slot 53 (bottom-right) cycles: Background Only → Background + Holes → No Removal | | |
| 3 | After Confirm: placed yellow blocks change color | | |
| 4 | After Confirm: Yellow Triangle/Square in hotbar shows new color (same reload, no reconnect) | | |
| 5 | After Confirm: item name shows new hex (e.g. `[#FF00FF]`) | | |
| 6 | Dry Run button → chat summary shows → Close Preview returns to wizard, does NOT update blocks | | |
| 7 | Test 1 Block → only the FIRST selected block updates | | |
| 8 | Cancel → no blocks updated, but item texture STILL updates | | |
| 9 | `/cb undogui` → ONE undo entry covers all blocks | | |
| 10 | Block designs (Ferrari logo, Arabic letters, etc.) stay intact after update | | |

### COL Square

| # | Test | Result | Notes |
|---|------|--------|-------|
| 11 | Black Square on a black-background block with no color word in its name → says "Already Black" | | |
| 12 | Red Square on a block that has no red variant → says "No red variant found" NOT "Already Red" | | |
| 13 | Use any Color Square without bg mode configured (set to `unset`) → works, no "not configured" error | | |

### bg mode persistence

| # | Test | Result | Notes |
|---|------|--------|-------|
| 14 | Set `colorToolBackgroundMode` to `none`, restart server → still `none` after restart | | |

### SNP1 — Snapshots

| # | Test | Result | Notes |
|---|------|--------|-------|
| 15 | Use `/cb snapshots`, select one, restart server → does the correct snapshot load? | | |
| 16 | Are blocks going concrete after using `/cb snapshots`? | | |

---

## ✅ Confirmed Done (2026-06-02) — Do Not Re-Test

| Task ID | What | Confirmed |
|---------|------|-----------|
| PACK2 | Modded client vanilla pack guard | ✅ 2026-06-02 |
| COL12 | Disk fallback for texture data | ✅ 2026-06-02 |
| G1 | 2 back button violations | ✅ 2026-06-02 |
| REL1 | /cb reload no data loss | ✅ 2026-06-02 |
| COL1b/c/d | Tool responsiveness instant | ✅ 2026-06-02 |
| G3 | 10 restoreState() cases | ✅ 2026-06-02 |
| IMG2 | "None" bg mode toggle | ✅ 2026-06-02 |
| IMG1 | CDN headers | ✅ 2026-06-02 |
| PIX1 | Bicubic scaling | ✅ 2026-06-02 |
| COL3 | Enclosed hole filling | ✅ 2026-06-02 |
| TOL1 | Tolerance effect | ✅ 2026-06-02 |
| COL4 | Yellow outline | ✅ 2026-06-02 |
| BGR1 | Background rework | ✅ 2026-06-02 |
| COL5/COL8 | Tooltips on fresh items | ✅ 2026-06-02 (fresh items only) |

---

## ⚠️ Built 2026-06-03 — Awaiting Developer Test Results Above

### What was built and in what order (with failures):

**COL9 Round 1 — Wizard trigger + Dry Run safety**
- Fix: `Color.decode(normalizeHexInput(text))` replaced `Color.decode(text.trim())` — raw input without `#` was silently failing
- Fix: `if (uiPage == 9999)` guard on slot 49 — Dry Run "Close Preview" was firing the real batch update
- Result: Wizard confirmed opening in-game ✅

**COL9 Round 2 — Blocks turning to yellow concrete**
- Failure confirmed in-game: blocks lost designs, became solid yellow
- Root cause: `runHexUpdateBatch` called `recolourTextureForPlayer(uuid)` which reads player's `PLAYER_MODE`. Player had PLAYER_MODE="full" → replaced ALL matching pixels everywhere including design elements
- Fix: Added `recolourTextureForBatch` to `ColorTriangleItem.java` — always forces "edge" mode, ignores PLAYER_MODE
- New method added: `ColorTriangleItem.recolourTextureForBatch(src, r, g, b, fillTrapped, uuid)`

**COL9 Round 3 — Items updated but blocks didn't / two pack reloads**
- Failure confirmed in-game: item color/name changed but placed blocks stayed old color
- Root cause A: `broadcastConfigSync` fired immediately on config save (before wizard). Client did a pack reload → items updated. Then batch ran and blocks updated via packets. Two separate reloads.
- Root cause B: Edge flood fill too conservative — missed background pixels not reachable from edges
- Fix A: Moved `broadcastConfigSync` to END of `runHexUpdateBatch` completion + Cancel handler. One combined debounce → one pack reload covering both items and blocks.
- Fix B: Added `recolourTextureDirectSwap` to `ColorTriangleItem.java` — direct per-pixel comparison. For each pixel, if RGB distance from stored old hex ≤ 30, replace with new hex. No flood fill. Old hex captured via `storeOldHex()` BEFORE config is overwritten, stored in `HEX_RECOLOR_OLD_RGB` map.

**NF4 Round 4 — Item textures never reached modded client**
- Failure confirmed in-game: ServerPackGenerator was fixed but items still old color
- Root cause: PACK2 guard in `ResourcePackServer.sendPackToPlayer()` never sends HTTP pack to modded clients. Modded client runs `ResourcePackGenerator.generate(client)` locally from its own stale `CustomBlocksConfig`
- Fix: `ConfigSyncPayload` (new S2C packet) broadcasts new hex values (black/yellow/green/red) to all online modded clients. Client handler updates `CustomBlocksConfig` fields then calls `scheduleGenerateAndReload(client, fastReloadDebounceMs())` → brief texture flash → new item textures applied

**BG mode selector (built cleanly)**
- Slot 53 in hex wizard — cycles `corners_only` → `corners_and_trapped` → `none`
- Stored in `HEX_RECOLOR_BG_MODE` map per player — NEVER touches global `/cb config` bg mode
- `runHexUpdateBatch` receives bgMode as parameter; if "none" → skips texture update entirely

**COL Square fixes (built cleanly)**
- `matchesColor()` in `ColorSquareItem.java` — added 3rd check: if cachedColorFamily and ID segments both miss, calls `ColorDetection.detect(block.texture)` directly. Only confident results trigger "Already [color]"
- Removed `isColorToolModeConfigured()` guard from `ColorSquareItem.useOnBlock()` — Square never does bg removal, guard was wrong

**bg mode "none" persistence (built cleanly)**
- `CustomBlocksConfig.java` validation whitelist was missing "none" — setting it in GUI then restarting server reset it to "unset"
- Fixed by adding "none" to the validation whitelist

---

## 🔴 Priority Queue for Next Session

| Priority | Task ID | Problem | Action |
|----------|---------|---------|--------|
| 🔴 #1 | **SNP1** | Snapshots broken — blocks going concrete, wrong state on restart | READ `08_SNP1.md` first. Investigate `SnapshotManager.java` fully before writing ANY code. |
| 🔴 #2 | **RT1** | Purple block 30+ seconds after rectangle tool | `05_PACK1-2_REL1_RT1.md` |
| 🟡 #3 | **COL9/NF4** | Confirm or fix based on developer test results above | Only if tests above show failures |
| 🟡 #4 | **LANG1** | `[<unknown_cb_tail>]` in action bar | `02_LANG1.md` — simple rename fix |
| 🔵 #5 | **MM1** | Mod menu full rework | `03_MM1_LIC1.md` |
| 🔵 #6 | **AR1** | Arabic blocks not detected | `04_AR1-3.md` |

**DO NOT build without discussing first:** LIC1, AR2, AR3, IMG6

---

## 📋 All Subplans

| File | Contents | Status |
|------|----------|--------|
| `01_COL1-12_NF4_COL-LIMITS.md` | Color logic, COL9, NF4 — full failure history | Active |
| `02_LANG1.md` | UI text fixes | Active |
| `03_MM1_LIC1.md` | Mod menu + licensing | Active |
| `04_AR1-3.md` | Arabic system | Active |
| `05_PACK1-2_REL1_RT1.md` | Networking + RT1 | Active |
| `06_IMG1-6_S3_TOL1_PIX1_BGR1.md` | Image processing | Active |
| `07_RECENT1.md` | Recent command | Backlogged |
| `08_SNP1.md` | Snapshots — BROKEN, top priority | 🔴 Active |
| `09_GUI1-3.md` | GUI navigation | Active |
| `10_NF2.md` | Deleter tool | Backlogged |
| `11_UND1-2_REDO1-2_G7.md` | Undo/Redo | Backlogged |
| `12_CMD1.md` | Command aliases | Backlogged |
