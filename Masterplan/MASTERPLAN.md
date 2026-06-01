# CustomBlocks — Master Plan (Kanban)

## ▶ Resume Here (end of Session 7 — 2026-06-01)

**Confirmed in-game this session:** NF2, COL1/2, PACK1, PACK2, COL11.

**Built this session — needs in-game test:**
| ID | What was built |
|----|----------------|
| REL1 | `/cb reload` data-loss fix — PACK1 now confirmed, ready to test |
| RT1 | Rectangle tool race fix — PACK1 now confirmed, ready to test |
| COL9 | Hex change → "Update N blocks?" confirm screen (built Session 6, never tested) |
| IMG1 | Download headers + auto-detect tolerance (built Session 6, never tested) |

**Still broken (fix these first next session):**
| ID | One-liner | Note |
|----|-----------|------|
| TOL1 | Tolerance 80 = same as 30 — `Math.min` caps manual value | Fix + 0–100 scale + toggle in `/cb settings` GUI. |
| PIX1 | New blocks come out pixelated | Root cause confirmed (Nearest Neighbor). |
| COL3/4 | Enclosed holes not filling, halos remain | Enclosed logic skips colored pixels. |
| COL12 | Random blocks say 'No texture data to recolour' | Needs deep investigation on NBT/SlotManager disconnect. |
| IMG2 | Uploading with background + enclosed mode broken | Re-test now that PACK1 is confirmed fixed. |
| NF4 | Config tool colors turns tools into dyes, delayed | Model generator fallback issue. |
| LANG1 | `<unknown_cb_tail>` and missing/modified `[CB]` prefixes | Missing translation keys. |
| COL5 | Tooltips wrong/incomplete across all tools | Tooltips show garbage/commands instead of useful info. |
| COL8 | Red default tools broken | Wrong hex, dye texture, bad lore. |
| AR1 | Arabic letter import + browser GUI | Clunky folder structure, confusing dye items in GUI. |

**PACK1 confirmed — REL1 and RT1 are now unblocked.** Both built and ready for in-game test.

**Next up after broken items:** REDO1 (add buttons), then BGR1 (dev's #1), then UND1b, then AR2 + AR3.

**AR2 + AR3 — start in a FRESH conversation.** The Arabic word generator (Java2D rendering, font bundling, 8-step GUI) and auto-joining system are large. Start a new chat after testing this session's builds so accuracy stays high.

**DO NOT build without discussing first:** SNP1, LIC1 (also "do not build until dev says build").

**Git state:** Working tree DIRTY + UNCOMMITTED. All Session 2–7 changes uncommitted. Last commit `a456aad`. Rebuild before next in-game test.

---

## State Legend

- 🎮 **BUILT AND TESTED IN GAME** — developer confirmed working in-game. The ONLY "done".
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
- **Git:** working tree DIRTY + UNCOMMITTED through Session 7. Rebuild before the next in-game test.

---

## 2. 🔴 Broken / Fix First (The Queue)
### PIX1 — New Blocks Come Out Pixelated
**State:** 🔴 BROKEN — root cause confirmed. Not built.
**Files:** `core/ImageProcessor.java`
**Priority:** 🔴

**Screenshot Proof & Knowledge (2026-05-31):**
- The screenshot of the sheep shows heavy aliasing/pixelation (sharp, jagged edges) instead of smooth interpolation.
- **Root cause (confirmed):** `ImageProcessor.resizeTo()` has an adaptive check `(srcWidth < 64 || srcHeight < 64) ? NEAREST_NEIGHBOR : BICUBIC`. If an uploaded image is smaller than 64x64, it uses Nearest Neighbor to scale it up to 128x128. Since 128 is rarely a clean multiple of the original size, Nearest Neighbor produces badly distorted, jagged pixels.
- **Fix:** Remove the `< 64` check and ALWAYS use `RenderingHints.VALUE_INTERPOLATION_BICUBIC` for resizing, producing smooth scaling regardless of input size.

---

---

### COL12 — Random Blocks Lose Texture Data
**State:** 🔴 BROKEN — investigate
**Priority:** 🔴

**Screenshot Proof & Knowledge (2026-05-31):**
- A newly imported Discord block throws `This block has no texture data to recolour` when using the color triangle on it.
- **Root cause understanding:** This is highly concerning. It indicates that the `SlotManager` randomly lost the PNG bytes or disconnected the block data for a block that was just imported via `/cb create`. Needs deep investigation.

---

---

### LANG1 — Missing Translation Keys & Hardcoded Prefixes
**State:** 🔴 BROKEN
**Priority:** 🔴

**Screenshot Proof & Knowledge (2026-05-31):**
- Redo hover text: Shows `<unknown_cb_tail>`, missing `en_us.json` translation.
- Deleter prefix: Shows plain red `[CB]` instead of standard Aqua/White formatting (`§8[§bCB§8]`).

---

---

### ~~[MOVED to Fix_NF2_COL11_PACK2.md] PACK2 — /cb rp pause Broken; Magic Items Become Dyes~~
**State:** 🔴 BROKEN — root cause fully confirmed by deep investigation. Not built yet.
**Files:** `network/ResourcePackServer.java`
**Priority:** 🔴 #1 (fix immediately after PACK1)

---

#### Symptoms (what the developer sees)
- After `/cb rp pause`, deleting or modifying any block makes placed custom blocks turn **transparent**.
- Custom tools and magic items in the inventory turn into **vanilla dyes**.
- `/cb reload` requires a **full rejoin** to see blocks again instead of applying instantly.
- Running `/cb rp resume` does NOT fix the dyes/transparency — the damage is already done.

---

#### Why PACK1 Made This Worse
Before PACK1: the server pack had a race condition — it sent a download URL before the ZIP was ready, so clients got HTTP 404 and silently ignored the pack. Blocks stayed on the local mod pack. Everything *looked* fine (accidentally).

After PACK1: the race is fixed — clients now **successfully download and apply** the server pack. This is correct for vanilla clients, but it exposed the real PACK2 bug: **modded clients should never receive the vanilla fallback pack at all.**

---

#### Root Cause (fully confirmed — traced through the code)

**Step 1 — What the server pack contains:**
`ServerPackGenerator.java` builds the ZIP with fallback item models for non-modded clients. For example:
- `color_square_black` → maps to `minecraft:item/black_dye`
- `color_triangle_red` → maps to `minecraft:item/red_dye`
- Custom block slots → missing textures (show as purple/black or transparent)

These fallbacks exist intentionally so vanilla players who join don't crash. They are WRONG for modded clients.

**Step 2 — What happens during pause:**
```
1. Developer runs /cb rp pause
   → cmdRpPause() sends RpPausePayload(true) to all clients
   → Clients set rpPaused = true  ✓
   → SERVER does nothing else — no state stored, no guard added

2. Developer deletes or modifies a block
   → SlotManager.saveSlots() runs async
   → Sees packDirty = true
   → Line ~1455: calls ResourcePackServer.updatePackWithSnapshot(snapshot)
   → NO PAUSE CHECK — pack rebuilds anyway  ✗

3. Rebuild completes inside PACK_BUILDER thread
   → currentPackFile and currentHash are updated
   → sendUpdateToAllPlayers() is called (lines 264 / 268 / 272)
   → ResourcePackSendS2CPacket is sent to EVERY connected player

4. Modded client receives the packet
   → Has rpPaused = true from step 1
   → Tries to suppress the reload... but it's too late
   → Minecraft has already queued the pack download
   → Pack downloads and APPLIES (thanks to PACK1 fix)
   → Vanilla fallback models load: dyes instead of tools, transparent blocks

5. Developer runs /cb rp resume
   → Clients try to reload their local mod pack
   → But the server pack has higher priority in Minecraft's pack stack
   → Local mod pack is still overridden
   → Blocks remain transparent, tools remain dyes
```

**The real problem in one sentence:** `sendPackToPlayer()` sends the vanilla fallback pack to ALL clients, including modded ones who have their own local pack and never need the fallback.

---

#### The Fix (small, surgical, no risk)

**Where:** `network/ResourcePackServer.java` — method `sendPackToPlayer(ServerPlayerEntity player)`

**What to add:** Before sending the packet, check if the player has the CustomBlocks mod installed. If yes, skip. Modded clients generate their own pack locally and must NEVER receive the server's vanilla fallback.

**How to detect a modded client:** `ServerPlayNetworking.canSend(player, SlotUpdatePayload.ID)` — returns `true` only if the client can receive the `SlotUpdatePayload` custom packet, which is a CustomBlocks-only packet that vanilla Minecraft doesn't know about.

**Exact code change — find this method:**
```java
private static boolean sendPackToPlayer(net.minecraft.server.network.ServerPlayerEntity player) {
    String hash = currentHash;
    if (hash == null || hash.isEmpty()) return false;
    String url = getPackUrl(serverInstance);
    // ... rest of method
```

**Add these lines immediately after the hash null-check:**
```java
// PACK2: Never send the vanilla fallback pack to modded clients.
// Modded clients have the CustomBlocks mod installed (proven by ability to receive
// SlotUpdatePayload) and generate their own local resource pack. Sending the server's
// fallback pack to them causes the vanilla models (dyes, missing textures) to override
// the mod pack, which has lower priority in Minecraft's pack stack.
if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
        .canSend(player, com.customblocks.network.SlotUpdatePayload.ID)) {
    CustomBlocksMod.LOGGER.info("[CustomBlocks] PACK2: skipping vanilla fallback for modded client {}",
        player.getName().getString());
    return false;
}
```

**Verify this import exists at the top of `ResourcePackServer.java`** (it should already be there since the class uses Fabric networking elsewhere):
```java
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
```

**Also verify `SlotUpdatePayload` import path** — grep the codebase for `class SlotUpdatePayload` to get the exact package, then add the import.

---

#### Edge Cases

**Vanilla clients (no mod installed):** `canSend(SlotUpdatePayload.ID)` returns `false` → pack IS sent → they see dyes/fallbacks → correct, expected behavior for non-modded players.

**Mixed server (some modded, some vanilla):** Modded players skip the pack. Vanilla players get it. Both work independently. No conflict.

**New player joining during pause:** The check runs at send-time, not at pause-time — a new modded player who joins while paused will correctly skip the pack, just like existing players.

**Player disconnects and rejoins:** On reconnect, `sendPackToPlayer()` is called again. Check still runs. Modded client still skips. Correct.

**`/cb rp resume` after the fix:** Since the vanilla pack was never sent to modded clients in the first place, resume just triggers a normal local-pack reload — no override to fight against. Should work cleanly.

---

#### Test Plan (in-game, in this order)

**Test 1 — Basic pause/resume:**
1. Join with mod installed. Place 3 custom blocks. Confirm they're visible.
2. `/cb rp pause`
3. Delete one block via `/cb delete <id>`
4. ✅ Remaining 2 blocks stay visible (no transparency)
5. ✅ Tools in inventory stay colored (no dyes)
6. `/cb rp resume`
7. ✅ Everything still correct

**Test 2 — Pack rebuild during pause:**
1. `/cb rp pause`
2. Import a new block: `/cb create <url>`
3. ✅ New block appears immediately (no purple/missing texture)
4. ✅ Existing blocks unaffected
5. `/cb rp resume` → ✅ All blocks still correct

**Test 3 — `/cb reload` during pause:**
1. `/cb rp pause`
2. `/cb reload`
3. ✅ All blocks stay visible — no rejoin required
4. `/cb rp resume` → ✅ Still correct

**Test 4 — `/cb reload` outside of pause (regression check):**
1. No pause active
2. `/cb reload`
3. ✅ All blocks reload correctly, no rejoin needed (PACK1 + PACK2 both working)

**Test 5 — Log confirmation:**
After any of the above tests, check server console. Should see:
```
[CustomBlocks] PACK2: skipping vanilla fallback for modded client [yourname]
```
If this line never appears, the check isn't running.

**Test 6 — Regression: blocks still appear on first join:**
1. Relog completely
2. ✅ All custom blocks load their textures on join (no purple)
3. ✅ Tools show correct colors (not dyes)
This confirms the fix didn't accidentally break normal pack delivery.

---

---

### COL1 — Color Square Client-Side Prediction (Instant Feel)
**State:** 🔨 BUILT — investigate.
**File:** `item/ColorSquareItem.java` (`useOnBlock()`)
**Priority:** 🟠

**Root cause:** Visual delay is network latency (100–300ms). Server processes instantly but the client waits for `BlockUpdateS2CPacket`. Fix: swap the block on the CLIENT the moment the player clicks, then the server confirms.

Uses Layer 1 (name scan) only — no `ImageIO.read()` on the client thread. The client guard `if (world.isClient) return ActionResult.PASS;` was replaced with:
```java
if (world.isClient) {
    BlockState state = world.getBlockState(pos);
    if (!(state.getBlock() instanceof SlotBlock sb)) return ActionResult.PASS;
    SlotData current = SlotManager.getBySlot(sb.getSlotKey());
    if (current == null) return ActionResult.PASS;
    SquareColor color = resolveColor(ctx.getStack());
    String targetId = resolveTargetId(current.customId, color.key(), null, current.cachedColorFamily);
    if (!targetId.equals(current.customId)) {
        SlotData target = SlotManager.getById(targetId);
        if (target != null) {
            com.customblocks.block.SlotBlock targetBlock =
                com.customblocks.CustomBlocksMod.safeSlotBlock(target.index);
            if (targetBlock != null) {
                world.setBlockState(pos, targetBlock.getDefaultState(),
                    Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            }
        }
    }
    return ActionResult.SUCCESS;
}
```
**Why safe:** Client and server run identical `resolveTargetId` logic on identical data → always agree → no snap-back. Server path untouched. `cachedColorFamily` is `transient` and NOT sent over the network — see COL1d for the client pre-warm that makes first use work.

**Test:** Right-click a custom block with a color square — zero visual delay. Test rapidly on 5 blocks in a row.

**User Confirmation (2026-05-31):**
- The user clarified that the "glitch/double-click" actually manifests as the tool being "broken again and slow and delay".
- **Root cause understanding:** The client-side prediction is failing, forcing the client to wait for the server round-trip. This perfectly matches the symptoms of `COL1d` and `COL2` where the client lacks the `cachedColorFamily` pre-warm data, falling back to server-only processing.

---

---

### COL3 — Landlocked Same-Color Areas Not Recoloring
**State:** 🔴 BROKEN — holes do not fill properly.
**File:** `item/ColorTriangleItem.java` (`recolourBackground()`)
**Priority:** 🟡

**Problem:** For enclosed regions (the hole inside a "9" or "8"), `fillTrappedBackgroundRegions()` uses `isHoleCandidate()`, which only returns `true` for transparent / near-black / neutral-grey pixels — never colored pixels. So a red enclosed area inside a red-bg "9" is silently skipped in `corners_and_trapped` mode.

**Fix (verified already present in source):** Inside the `fillTrapped` block, a Pass 1 recolors any unvisited pixel matching the background color (using `isBackgroundLab`, respecting tolerance), then Pass 2 runs the existing `fillTrappedBackgroundRegions`:
```java
if (fillTrapped) {
    for (int x = 0; x < w; x++) {
        for (int y = 0; y < h; y++) {
            if (!visited[x][y] && isBackgroundLab(img, x, y, bgA, bgLab, labThreshold)) {
                img.setRGB(x, y, newArgb);
                visited[x][y] = true;
            }
        }
    }
    fillTrappedBackgroundRegions(img, visited, newArgb);
}
```

**Test:** Set `corners_and_trapped`, recolor a "9"/"8" block with an enclosed same-color region — both outer and enclosed areas change. A block with no enclosed areas still recolors with no regression.

---

---

### COL4 — Edge Halo After Recoloring
**State:** 🔴 BROKEN — faint halos remain on edges.
**File:** `item/ColorTriangleItem.java` (`recolourBackground()`)
**Priority:** 🟡

**Problem:** The BFS only recolors pixels where `isBackgroundLab()` is true. Anti-aliased border pixels are close to the bg color but past `labThreshold`, leaving a faint fringe of old color.

**Fix (already present, as an edge blend pass before the PNG write):**
```java
double blendThreshold = labThreshold * 1.5;
for (int x = 0; x < w; x++) {
    for (int y = 0; y < h; y++) {
        if (visited[x][y]) continue;
        int px = img.getRGB(x, y);
        int pa = (px >> 24) & 0xFF;
        if (pa < 50 || bgA < 50) continue;
        int pr = (px >> 16) & 0xFF, pg = (px >> 8) & 0xFF, pb = px & 0xFF;
        double[] pLab = rgbToLab(pr, pg, pb);
        double dE = Math.sqrt(
            (pLab[0]-bgLab[0])*(pLab[0]-bgLab[0]) +
            (pLab[1]-bgLab[1])*(pLab[1]-bgLab[1]) +
            (pLab[2]-bgLab[2])*(pLab[2]-bgLab[2]));
        if (dE > blendThreshold) continue;
        double t = 1.0 - (dE / blendThreshold);
        int blendR = (int) Math.round(newR * t + pr * (1.0 - t));
        int blendG = (int) Math.round(newG * t + pg * (1.0 - t));
        int blendB = (int) Math.round(newB * t + pb * (1.0 - t));
        img.setRGB(x, y, (pa << 24) | (blendR << 16) | (blendG << 8) | blendB);
    }
}
```

**Test:** Recolor a sharp-edged design on a solid background — clean border, no halo. A clean block stays clean (no regression).

---

---

### COL5 — Color Tool Tooltips Show Mode + Tolerance
**State:** 🔴 BROKEN — Tooltips show garbage/commands instead of useful info.
**Files:** `item/ColorTriangleItem.java`, `item/ColorSquareItem.java`
**Priority:** 🟡

> `formatColorToolMode()` returning plain-English names is already done in GuiManager ("Background + Enclosed Areas", "Background Only", "Not Configured").

Both items already add the `Mode: ... Tolerance: ...` lore line + the `/cb config` hint, and both `inventoryTick()` methods call `FirstUseHints.hint(...)`. Helpers `formatModeForTooltip()` / `getToleranceForTooltip()` exist in both. (`FirstUseHints` signature is `hint(UUID, String)` — read it before using.)

**Test:** Hover a custom triangle — tooltip shows current mode + tolerance. New player picking up a triangle gets a one-time chat hint that doesn't reappear on relog.

---

---

### COL8 — Red Triangle / Red Square Defaults Broken
**State:** 🔴 BROKEN — hex not reflecting config, pink dye texture, garbage lore, doesn't match creative menu.
**Files:** `command/CustomBlockCommand.java`, `network/ServerPackGenerator.java`, `client/ResourcePackGenerator.java`
**Priority:** 🟡

The items `red_square`/`red_triangle` were already registered. The breakage was hardcoded black/yellow/green in three places, all fixed:
- `CustomBlockCommand.java`: `cmdGiveSquareInternal` + `cmdGiveTriangleInternal` validation now also allow `"red"`; `.suggests(...)` for `/cb square`, `/cb triangle`, and bulkrecolor/bulkcolor all add `b.suggest("red")`. (Bulk recolor color resolution already handled red via ColorLibrary fallback.)
- `ServerPackGenerator.java` `addGeneratedItemModel(...)`: added `red_square`/`red_triangle` → `minecraft:item/red_dye`.
- `client/ResourcePackGenerator.java`: red added to the `squares`/`triangles` arrays — note these arrays were LATER changed by NF4 to be generated from config hex, so red still flows through.

> `CustomBlocksConfig.triangleRedHex` default is `"#EE3333"`. COL8b adds a Red hex editor in `/cb config`. See also CMD1.

**Test:** `/cb square red`, `/cb triangle red` — items appear red, swap blocks correctly.

---

---

### COL9 — Hex Change → Prompt to Update Existing Blocks
**State:** 🔴 BROKEN — prompt doesn't show at all.
**File:** `gui/GuiManager.java`
**Priority:** 🟡

After saving a new green/yellow/red hex (hook the hex-save block in the config text handler — read it fully first):
1. Scan `SlotManager.allSlots()` for blocks whose `customId` contains the color key (`_green`, `_red`, `_yellow`).
2. If any found, show a GUI confirmation: "N blocks have '[color]' in their name. Update their textures to the new shade? [Confirm] [Skip]".
3. Confirm → re-run texture generation per block + rebuild pack. Skip → just save config.

Build new `buildHexRecolorConfirmGui()` + `handleHexRecolorConfirmClick()`. Read `SlotManager.java` for the correct retexture method first.

---

---

### IMG2 — Background Removal "None" Mode Toggle
**State:** 🔴 BROKEN — actually caused by PACK1 404, not the mode itself.
**Files:** `gui/GuiManager.java`, `item/ColorTriangleItem.java`, `item/ColorSquareItem.java`, `core/ImageProcessor.java`
**Priority:** 🟡

**Screenshot Proof & Knowledge (2026-05-31):**
- Uploading an image with the `corners_and_trapped` or `none` mode results in a purple/black missing texture block.
- **Root cause understanding:** The top right of the screenshot shows `1 out of 1 pack(s) failed to download`. The image uploaded perfectly on the server, but the client 404'd when trying to download the new texture pack due to the **PACK1** race condition. Fix PACK1 first to see if IMG2 actually works.
**Priority:** 🟡

Adds a `"none"` state to the bg-mode cycle so imports can leave a background fully alone.
- `GuiManager.formatColorToolMode()`: `"none"` → "No Background Removal".
- Mode cycle: `corners_only → corners_and_trapped → none → corners_only`; invalid/`unset` snaps to `corners_only`. Buttons at slots 10/13/16 (barrier = none), back at 22. Config text handler accepts `"none"`.
- `ImageProcessor.replaceBackground()` / `replaceBackgroundWithColor()` / `replaceBackgroundWithFringeTolerance()`: `if ("none".equals(...colorToolBackgroundMode)) return pngBytes;` at the top (must also cover the animated-GIF frame loop).
- `ColorTriangleItem` / `ColorSquareItem`: in "none" mode, action-bar "Color tools require background removal. Change mode in /cb config." and return PASS.

**Test:** `/cb config` → mode "No Background Removal" → import keeps the background; a color triangle shows the message and does nothing. Switch back → works normally.

---

---

### IMG4 — Transparent Pixels Wrongly Treated as Background
**State:** 🔴 BROKEN (Fake Transparency)
**File:** `core/ImageProcessor.java`
**Priority:** 🔴

**Problem:** The original fix successfully prevents real transparent pixels from seeding the flood-fill (confirmed in IMG4-S3). However, when users upload "fake transparent" images (JPEGs/PNGs where the grey-and-white checkerboard is baked into the actual pixels), the background removal fails because the checkerboard is not a uniform solid color. 
**Knowledge (2026-05-31):** The user uploaded a fake-transparent sheep image. The tool correctly removed the white squares of the checkerboard, but left the grey lines intact, resulting in a mesh background. This is technically expected behavior for fake transparency, but logged as broken because the user expects it to be removed. May need BGR1's AI to solve.

---

---

### ~~[MOVED to Fix_NF2_COL11_PACK2.md] NF2 — Deleter Tool Item~~
**State:** 🔴 BROKEN — missing texture, block doesn't delete on client, prefix modified.
**Priority:** 🔴

**Screenshot Proof & Knowledge (2026-05-31):**
- Tooltip shows `item.customblocks.deleter` (missing lang key).
- Chat prefix shows plain red `[CB]` instead of standard Aqua/White formatting.
- The block does not visually delete on the client-side despite the "Deleted" chat message (requires server to actually erase the block visually).

**Behavior:** Right-click a placed custom block without shift → confirmation GUI; with shift → instant delete. Lore shows both. Item `customblocks:deleter`, always glinted.

**Confirmation GUI:** block info at slot 13, `§c§l💀 Confirm Delete` at slot 29, `§a§l◀ Keep Block` at slot 33 (glass elsewhere).

**On delete (both paths):** permission check → lock check (`§c⚿ Block is locked — unlock first`) → `UndoManager.pushUndoMutation` → `TrashManager.addToTrash(data)` → `SlotManager.remove(id)` + `LockManager.onBlockDeleted(id)` + category unassign → broadcast delete → schedule pack rebuild → action bar `§c§l✗ §r§cDeleted §f[name]` → chat with `[Click to undo]` (`ClickEvent.Action.RUN_COMMAND` → `/cb undo`).

**Also fix:** `executeBulkOpFromGui` case `"delete"` currently skips `TrashManager.addToTrash` — add it so bulk-deleted blocks appear in `/cb deletedblocks`.

**Texture (generated):** new Gradle task `generateItemTextures` runs `GenerateDeleterTexture.java` → `assets/customblocks/textures/item/deleter.png` (16×16 red recycle bin; palette outline `#330000`, shadow `#881100`, body `#CC2200`, highlight `#FF4422`, symbol `#FFFFFF`). Model `models/item/deleter.json` (`item/generated`, `layer0` → `customblocks:item/deleter`). PNG committed after first task run.

**New command:** `/cb deleter` (like `/cb chisel`).

**Files to create:** `item/DeleterItem.java`, `texturegen/GenerateDeleterTexture.java`, `models/item/deleter.json`, `textures/item/deleter.png`.
**Files to modify:** `build.gradle` (task), `CustomBlocksMod.java` (register), `CustomBlockCommand.java` (command), `GuiManager.java` (confirm GUI + handler + Magic Items + Give All + the bulk trash bug).

---

---

### NF4 — Configurable Tool Colors
**State:** 🔴 BROKEN — delayed application, turns tools into colored dyes.
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

---

---

### AR1 — Import Pre-made Letter PNGs + Browser GUI
**State:** 🔴 BROKEN — Import command folder structure is too strict/clunky. GUI uses confusing vanilla dye items instead of proper icons.

`/cb arabic import <base_path>` scans `BLACK/ YELLOW/ GREEN/ RED/` for `<letter>_<color>.png` + `arabic_numbers_png/` for `a<digit>_<color>.png`, reads bytes directly (no processing), calls `SlotManager.assign(customId, displayName, bytes)`, sets letter metadata (`isLetter=true`, `letterGroup="arabic_<name>"`, `letterForm="isolated"`, `letterConnectsLeft` per letter — alef/dal/ra/waw etc. do not join), saves the registry, rebuilds the pack.

`/cb arabic` or `/cb arabic gui [color]` — 54-slot browser: color tabs (Black/Yellow/Green/Red), letter grid, pagination, click a letter → receive 1 block, Back returns. `/cb arabic give <letter> <color>` gives one. `/cb arabic text <color> <text>` parses Arabic text → one block per character in placement order.

**Test:** deploy → `/cb arabic import C:\Users\66664\OneDrive\Desktop` → wait ~10s + reconnect → `/cb arabic` opens → click a letter → place it (matches the PNG) → `/cb arabic give ba black` gives the Ba black block.

---

---

## 3. 🏗️ Under Construction


---

## 4. ⏳ Ready for In-Game Verification
### REL1 — `/cb reload` Data-Loss / Blocks Break Visually
**State:** 🔨 BUILT — blocked by PACK1. Retest after PACK1 is fixed.
**Files:** `core/SlotManager.java`, `command/CustomBlockCommand.java`
**Priority:** 🔴

**Root cause:** `flushSave()` called `IO_EXECUTOR.shutdown()`, permanently killing the IO thread mid-session. Plus a tick-based batch-loader race (the pack was rebuilt before all blocks finished loading).

**Fix built:** New `flushSaveForReload()` that saves without shutting down IO + a wait loop for `startupLoadInProgress = false` before the pack rebuild + a `RELOAD_IN_PROGRESS` lock to prevent concurrent reloads. Data now saves correctly (rejoin proves it). The remaining visual breakage is PACK1 — the pack never reaches the client after reload.

**Test (after PACK1):** `/cb reload` → all blocks survive and stay visible, no rejoin needed.

---

---

### G1 — All Back Buttons Call openMain Instead of handleEscBack
**State:** 🧪 UNKNOWN — built, never tested in-game.
**File:** `gui/GuiManager.java`
**Priority:** 🔴

**Rule:** If a tooltip says `◀ Back` → change to `handleEscBack(player)`. If it says `Main Menu` → leave as `openMain`. Read the tooltip text at each location before changing.

Known locations (from live source): slots near lines 558, 644, 805, 2249, 2643, 2693, 2871, 2949, 3021, 3035, 3510, 3544, 4058, 4154, 9396 (each currently `openMain`). After fixing, search the file for `"§c◀ Back"` + `openMain` to catch any others.

**Test:** Open any screen with a Back button. Click it. Should go to the previous screen, not always main menu.

---

---

### G3 — 10 Missing Cases in restoreState()
**State:** 🧪 UNKNOWN — built, never tested in-game.
**File:** `gui/GuiManager.java` (`restoreState()`)
**Priority:** 🟠

These GuiMode values exist in `GuiMode.java` but were missing from the `restoreState()` switch: `BULK_ASSIGN_PICKER`, `BULK_RECOLOR_CONFIRM`, `BULK_RECOLOR_WIZARD`, `CATEGORY_BLOCK_CONTEXT`, `CATEGORY_ICON_PICKER`, `CATEGORY_STATS`, `DELETE_CATEGORY_MENU`, `IMPORT_CONFLICT`, `MERGE_CATEGORY_PICKER_TARGET`, `SORT_BLOCKS_MENU`.

For each: find the corresponding `open*()` method, read its real parameter signature, then add the case using parameters from `state`. Do NOT copy signatures from this plan — verify each by reading the method.

**Test:** Enter each of the 10 screens, press ESC. Should return to the previous screen, not main menu.

---

---

### COL1b — Remove Client Skip from ALL 7 Tools
**State:** 🧪 UNKNOWN — built, never tested.
**Files:** `ColorSquareItem`, `ColorTriangleItem`, `RectangleToolItem`, `AmethystChiselItem`, `LuminaBrushItem`, `GoldenHexagonItem`, `DiamondTriangleItem`
**Priority:** 🟠

Each tool had `if (world.isClient) return ActionResult.PASS;` in `useOnBlock()`, causing delay. For each: delete that line, and wrap every `player.sendMessage(...)` / `playSound(...)` with `if (!world.isClient)` to avoid duplicate feedback. Each method self-gates via `if (!(player instanceof ServerPlayerEntity sp)) return PASS;`. For tools with a heavy server-only path (uses `world.getServer()`), add a fresh `if (world.isClient) return ActionResult.PASS;` AFTER the sneak/permission block so only the prediction runs on the client. (COL1 gives ColorSquareItem full prediction; the other 6 just need the guard removed.)

**Test:** Right-click each tool — feels instant, no duplicate chat/sounds.

---

---

### COL1c — Client Permission Bypass
**State:** 🧪 UNKNOWN — built, never tested.
**File:** `command/PermissionHelper.java` (`canUseTool`)
**Priority:** 🟠

Add `if (player.getWorld().isClient) return true;` as the FIRST line of `canUseTool()`. The server still validates and reverts if unauthorized. Without this, the client blocks its own prediction (permission can only be evaluated server-side).

---

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

### COL2 — Remove Runtime ImageIO Fallback in resolveTargetId
**State:** 🧪 UNKNOWN — built, never tested.
**File:** `item/ColorSquareItem.java` (`resolveTargetId()`)
**Priority:** 🟠

Removed the `if (dominantFamily == null && textureBytes != null && textureBytes.length > 0) { ColorDetection.detect(...) }` block. Now it uses only the passed-in `cachedFamily`. The cached value from `postProcessLoadedSlots()` is authoritative; lazy detect at click time produced the identical "not confident" result and wasted 50–200ms on the server thread.

**Test:** Right-click a custom block with a color square — zero delay every click, including first click after restart.

---

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

## Known Undiagnosable Issue

**Random texture breaks (purple/black blocks)** — Cannot fix without server logs. When it happens: note the block ID and check `logs/latest.log` for `[ResourcePackServer]` or `[CB]` lines.

---

---

## 5. 💬 Blocked / Needs Discussion
### RT1 — Rectangle Tool Block Flash / Stays Purple
**State:** 🔨 BUILT (race fixed) — blocked by PACK1. Brief purple during pack rebuild is expected.
**Files:** `item/RectangleToolItem.java`
**Priority:** 🔴

**Root cause:** `setBlockState` was called before `broadcastUpdate("add")`, so clients briefly saw an unknown block slot.

**Fix built:** Swapped the order — broadcast the variant to all clients FIRST, then place the block. A brief purple flash *during* the pack rebuild is EXPECTED (the new slot has no pack texture until the rebuild completes). The block staying purple *even after rejoin* is PACK1 (the new slot texture never gets delivered).

**Test (after PACK1):** Rectangle tool block appears with its texture, no lasting purple. (RT1b: discuss whether a faster pack trigger is worth it — the rebuild window feels long.)

---

---

### RECENT1 — /cb recent Full Rework
**State:** 💬 DISCUSS — developer wants a full rework, not enough detail yet. Flagged for next session.
**Priority:** 🟠

---

---

### SNP1 — Snapshots Complete Rework
**State:** 💬 DISCUSS.
**File:** `core/SnapshotManager.java`
**Priority:** 🔵

**Current system:** a snapshot = full GZip JSON of ALL slots (`SlotManager.serializeSnapshotToJson`), stored in `config/customblocks/snapshots/`, max 20, auto every N min + manual + pre-op. `/cb snapshots` → `openSnapshotsGui`; `/cb panic` = 2-step rollback; restore is all-or-nothing.

**Developer wants:** per-BLOCK rollback (not all-or-nothing), a GUI button to CREATE a snapshot, better renaming, a better naming system (timestamps are bad), and a search system. Deeper design discussion still pending.

---

## Group 7 — Branding & License

---

---

## 6. ❌ Backlog / Not Started
### REDO1 — `/cb redo` Says "Nothing to Redo" After Undoing a Deletion
**State:** ✅ CONFIRMED — logic works perfectly, but missing translation key <unknown_cb_tail> (LANG1).
**Files:** `command/CustomBlockCommand.java` (`cmdUndo`)
**Priority:** 🔴

**Root cause (two bugs):**
1. *(First, pre-existing.)* In `cmdUndo`, `curForRedo = SlotManager.getById(prev.customId)` was captured BEFORE `restoreSnapshot()`. For deleted blocks the block doesn't exist yet → returns null → `pushRedo` was skipped. Fix: for `wasDeleted = true`, push the redo entry AFTER `restoreSnapshot()` using `new UndoEntry(id, null, "delete", false)` (`previousState=null` is the signal `cmdRedo` uses to re-delete the block).
2. *(Second, found Session 5b.)* Both redo pushes used the 4-arg `UndoEntry` constructor → `playerUuid=null`. `pushRedo` skips null-UUID entries in per-player mode → redo stack stayed empty → "Nothing to redo." Fix: both pushes now use the 5-arg constructor passing `uuid`.

**Test:** Delete a block → `/cb undo` → `/cb redo` → block disappears again.
**Note:** Also need to add a `[Click to Redo]` chat button after `/cb undo`, and a `[Click to Undo]` button after `/cb redo`.

---



---

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

## Group 3 — Undo / Redo / History

> See also **REDO1** (broken), **REDO2** (confirmed) in Group 1, and **G7** (history GUI) in Group 2.

---

---

### UND1b — Rich Batch Undo/Redo Message
**State:** ❌ NOT STARTED — design confirmed by developer. Build after REDO1 is confirmed.
**Priority:** 🟠

The batch undo/redo chat message currently shows a count only. Desired:
- Show the list of block names (not just a count).
- Right-click in chat to unfold the full list; show category info per block.
- Visible in `/cb history`, `/cb undogui`, and the `/cb redo` confirm dialog.

---

## Group 4 — Color Tools

---

---

### ~~[MOVED to Fix_NF2_COL11_PACK2.md] COL11 — Color Tool on a Base Block: Graceful Variant Matching~~
**State:** 🔨 BUILT — my Session 5 silent fix was wrong.
**Files:** `item/ColorSquareItem.java` (`resolveTargetId` + self-same check), `item/ColorTriangleItem.java` (equivalent)
**Priority:** 🔴

**Symptom (verbatim from screenshot):** `[CB] No Black variant found for 'BonBon'.` The block `BonBon` is a BASE block (no color prefix/suffix). Using a black square produced this error instead of working.

**Root cause (confirmed):** When trying to color a base block that has no variant, it correctly falls back to itself. However, it currently hits `return ActionResult.SUCCESS;` silently, providing no feedback to the user.
**Fix:** Before returning success on the self-same check, send `player.sendMessage(Text.literal(ChatHelper.formattedKey("cmd.tool_square_already_color", color.label())), true)` so it prints `[CB] Already [color]` in chat.

**The correction needed:** My Session 5 fix made the no-variant case silently do nothing. Correct behavior: show the same `"Already [color]"` action-bar message as the genuine same-color case. One-line change in the `ColorSquareItem.java` self-same check.

> Read `resolveTargetId()` fully before writing the prefix/suffix-aware matcher.

**Test:** Black square on a base block → no error, graceful fallback / "Already [color]" message. Suffix and prefix both swap correctly. Triangle preserves color position.

---

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

### BGR1 — Smart Background Removal Rework ⭐ Developer's #1
**State:** 💬 Design AGREED — build in small TESTED pieces, one at a time. NOT started.
**Files:** `core/ImageProcessor.java` (`replaceBackground()`), `item/ColorTriangleItem.java` (`recolourBackground()`), `gui/GuiManager.java`, new `bg-learning.json`
**Priority:** 🟠 ⭐

**The problem — spans TWO code paths:**
- **Import path** (`replaceBackground`, e.g. the SHEEP via `/cb create`): the remover ONLY erases pixels near WHITE (`isNearWhiteYcbcr` / `deltaE` from `LAB_WHITE`). It never samples the real background color. White sheep bars survive because the white isn't pure enough at the current tolerance.
- **Triangle recolor path** (`ColorTriangleItem.recolourBackground`, e.g. the BASKETBALL via a red triangle): a different code path that eats the subject's black lines.

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

### IMG4-S3 — Transparent-Background Images Showed White (Regression)
**State:** ✅ CONFIRMED FIXED (2026-05-30, Session 5, screenshot — Discord logo now black).
**File:** `core/ImageProcessor.java`

**Root cause:** The IMG4 fix correctly stopped transparent pixels from seeding the flood fill, but Stage 3 of `replaceBackground()` composited ALL remaining transparent pixels against WHITE (`bgR/G/B = 255`). So a transparent-background PNG (Discord logo) was never flood-filled → never set to black → composited white. **Fix:** in Stage 3, `if (a == 0) { argb.setRGB(x, y, BLACK); continue; }` before the white composite. Same fix in `replaceBackgroundWithFringeTolerance()`.

---

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

### IMG5+ — Extend Shift-Skip to /cb create and /cb importfolder
**State:** 🔴 SCRAPPED — superseded by BGR1.
**File:** `command/CustomBlockCommand.java`
**Priority:** 🟡

Thread the `skipBgRemoval` flag through the `/cb create` and `/cb importfolder` call chains down to `ImageProcessor.replaceBackground()`. Find the upload trigger, read the full call chain, add the parameter to each signature.

---

## Group 6 — New Features

---

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

### MM1 — Mod Menu Entry (icon + extras)
**State:** 🔨 PARTIAL — fields scaffolded (Session 3); needs rebuild + in-game Mod Menu confirmation. **Icon still missing → shows "?".**
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
### PACK1 — Pack Download Fails (HTTP 404) After Any Rebuild
**State:** 🔨 BUILT — root cause fully confirmed (Session 5b). **Blocks REL1 and RT1.**
**Files:** `network/ResourcePackServer.java`, `network/ResourcePackManager.java`
**Priority:** 🔴 #1

**Symptom:** After any pack rebuild (bulk delete, `/cb reload`, new block via rectangle tool), the client gets "1 out of 1 pack(s) failed to download." Rejoining fixes it.

**Root cause (confirmed by reading both files):** `scheduleRebuild()` does this with no synchronization:
```
1. updatePack()              → queues ZIP build on a background thread, returns immediately
2. sendUpdateToAllPlayers()  → starts a notify thread, sleeps 2 seconds, then sends the pack URL to all clients
```
The ZIP build takes **3–5+ seconds** with many blocks. The notify fires at the 2s mark. The client receives the URL, tries to download, and the server returns **HTTP 404** because the file isn't finished yet. Rejoin works because by then the ZIP is done and the client re-requests it successfully.

**Fix:** 
1. Remove the call to `sendUpdateToAllPlayers()` from `ResourcePackManager.scheduleRebuild()`.
2. Move it inside `ResourcePackServer.updatePackWithSnapshot()`, AFTER `currentPackFile` and `currentHash` are updated.
3. **CRITICAL CLOUD SYNC:** If `cloudShareEnabled` is true, the notification must be placed *inside* the `PACK_UPLOADER.submit()` callback so it waits until the upload to Cloud Vault is fully complete. Otherwise, players get a 404 from the cloud.

---

---

### C1 — Null Check Crashes (21 locations)
**State:** ✅ CONFIRMED WORKING IN-GAME
**File:** `gui/GuiManager.java`
**Priority:** 🔴

Fix pattern for every location:
```java
SlotData d = SlotManager.getById(id);
if (d == null) { send(player, "Block not found."); return; }
// ... rest of logic using d
```
Lines that need this fix (verified): `601, 1422, 1648, 1695, 1696, 1698, 1719, 1738, 3353, 3375, 3463, 3474, 3479, 3487, 3488, 3496, 3524, 3774, 4228, 4567, 5762, 7216`

Already safe — DO NOT touch: lines 3327 (`if (dd == null) return;`), 3850 / 4471 / 5991 (ternary null guards), 6954 (`if (target == null || source == null)`), 7525 (`d != null &&`).

> Before editing any line, read the surrounding 10 lines to confirm the exact variable and method name. Line numbers may shift.

**Test:** Delete a block while a GUI referencing it is open. Click buttons. Server should not crash — should show "Block not found."

---

## Group 2 — GUI Navigation

---

---

### G2 — ESC from Bulk Delete Closes Everything
**State:** ✅ CONFIRMED (2026-05-29, Session 2).
**File:** `gui/GuiManager.java` (`openBulkOpPicker`)
**Priority:** 🔴 — 1 line

`openBulkOpPicker` set state directly without calling `pushBackStack`, so BULK_HUB was never saved → ESC found an empty stack → closed everything. Fixed by adding `pushBackStack(player.getUuid());` as the FIRST line of `openBulkOpPicker`.

---

---

### G4 — Bulk Delete Has Two Entry Points
**State:** ✅ CONFIRMED WORKING IN-GAME
**Files:** `gui/GuiManager.java`, `command/CustomBlockCommand.java`
**Priority:** 🟠 — 2 lines

- `CustomBlockCommand.java`: `GuiManager.openBulkDelete(p, 0)` → `GuiManager.openBulkHub(p)`
- `GuiManager.java` (main GUI slot 19): `openBulkDelete(player, 0)` → `openBulkHub(player)`

Keep `openBulkDelete` and `buildBulkDeleteGui` in code — just remove the entry points.

---

---

### G5 — Remove /cb helpgui
**State:** ✅ CONFIRMED WORKING IN-GAME
**File:** `command/CustomBlockCommand.java`
**Priority:** 🟠 — 4 lines deleted

Delete the `.then(CommandManager.literal("helpgui")...)` block. `/cb help` already does the same thing.

---

---

### G6 — Unify /cb, /cb gui, /cb menu → openWelcomeGui
**State:** ✅ CONFIRMED WORKING IN-GAME
**File:** `command/CustomBlockCommand.java`
**Priority:** 🟠

- `cmdGui()`: always call `GuiManager.openWelcomeGui(player)` regardless of onboarding status — remove the if/else branch.
- `/cb menu`: change whatever it calls → `GuiManager.openWelcomeGui(p)`.

---

---

### UND1 — Bulk Undo as a Single Batch (with Confirm Dialog)
**State:** ✅ CONFIRMED (2026-05-30, Session 5b). UND1b (rich message) still pending.
**Files:** `core/UndoManager.java`, `gui/GuiManager.java`, `command/CustomBlockCommand.java`

**Root cause:** Each deleted block pushed its own undo entry in a loop, so a bulk delete needed many `/cb undo`s.

**Fix:** New `BatchDelta` type in UndoManager + `pushUndoBatch()` in the GuiManager bulk-delete path + a double-`/cb undo` confirm dialog. Confirmed working end-to-end.

---

---

### REDO2 — Bulk Redo After Batch Undo
**State:** ✅ CONFIRMED (2026-05-30, Session 5b, screenshot).

**Root cause:** `cmdUndoBatch()` restored all blocks but never pushed anything to the redo stack.

**Fix:** After restoring, push a `BatchDelta` to the redo stack via `pushRedoBatch()` + batch detection + confirm dialog in `/cb redo`. Confirmed: bulk delete → `/cb undo` (confirm) → blocks back → `/cb redo` (confirm) → blocks gone again.

---

---

### COL6 — Variant Naming ("Dark Red", not "Hex #8B0000")
**State:** ✅ CONFIRMED (Parts A/B) — actually names "Maroon", but acceptable. Part C ❌ NOT STARTED.
**Files:** `item/ColorTriangleItem.java`, `item/ColorSquareItem.java`
**Priority:** 🟡

**Parts A/B (done, found present):** `NBT_CUSTOM_NAME` override in `resolveColor()` + a ColorLibrary delta-E `labelForRgb()` in both items. The only Session-2 change: `labelForRgb()` previously returned `""` when no library color was within delta-E 25 (names rendered as just " Triangle"/" Square"). Now falls back to `"Hex #" + hex`. Reference matcher:
```java
private static String labelForRgb(int rgb) {
    int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
    String closest = null; double bestDist = Double.MAX_VALUE;
    for (com.customblocks.gui.ColorLibrary.LibColor c : com.customblocks.gui.ColorLibrary.ALL) {
        int[] cr = com.customblocks.gui.ColorPickerHelper.hexToRgb(c.hex());
        if (cr == null) continue;
        double[] labA = rgbToLab(r, g, b), labB = rgbToLab(cr[0], cr[1], cr[2]);
        double dE = Math.sqrt(
            (labA[0]-labB[0])*(labA[0]-labB[0]) +
            (labA[1]-labB[1])*(labA[1]-labB[1]) +
            (labA[2]-labB[2])*(labA[2]-labB[2]));
        if (dE < bestDist) { bestDist = dE; closest = c.name(); }
    }
    return (closest != null && bestDist < 25.0) ? closest : "Hex #" + hexForRgb(rgb);
}
```

**Part C — Retroactive rename prompt (NOT started):** When a tool is renamed via the `/cb colors` hub (COL10), search `SlotManager.allSlots()` for blocks created with that tool, show a count + GUI prompt "Update X existing blocks? [Yes] [No]"; Yes → update `displayName` + rebuild pack. Read `SlotManager.java` for the correct rename/update method first.

**Test:** Create a #8B0000 triangle → auto-named "Dark Red". Rename to "Blood Red" → new blocks use it. When renaming, get prompted about existing blocks → Yes updates them.

---

---

### COL7 — Glint Always On
**State:** ✅ CONFIRMED WORKING IN-GAME
**Files:** `item/ColorSquareItem.java`, `item/ColorTriangleItem.java`

**Knowledge (2026-05-31):**
- Glint (shiny purple enchantment effect) successfully appears on all custom squares and triangles in the inventory.
*(Note: The tooltip missing issue is tracked entirely under COL5)*

---

---

### COL8b — Red Hex Editor in /cb config
**State:** ✅ CONFIRMED WORKING IN-GAME
**File:** `gui/GuiManager.java`
**Priority:** 🟡

Green/yellow already have a hex editor in the config GUI; red does not. Add a Red Shade hex config button (verify an empty slot by reading the config GUI builder before placing).

---

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

### CMD1 — /cb settings Alias for /cb config
**State:** ✅ CONFIRMED WORKING IN-GAME
**File:** `command/CustomBlockCommand.java`
**Priority:** 🟡

Add `/cb settings` as an alias that opens the same screen as `/cb config`.

---

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

### IMG1 — Download Headers + Auto-Detect Tolerance
**State:** 🔨 BUILT — needs in-game test. CDN headers CONFIRMED working (2026-05-29); WixMP blocked at datacenter level (unfixable without a proxy).
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

### IMG3 — bgRemovalEnabled Global Toggle
**State:** ✅ CONFIRMED WORKING IN-GAME (2026-05-31)
**Files:** `CustomBlocksConfig.java`, `core/ImageProcessor.java`
**Priority:** 🟡

- `CustomBlocksConfig`: `public static volatile boolean bgRemovalEnabled = true;` wired into load/save.
- `ImageProcessor`: all three `replaceBackground*` variants get `if (!CustomBlocksConfig.bgRemovalEnabled) return pngBytes;` at the very top (above the "none" check).

**Test:** `bgRemovalEnabled: false` → any image arrives as-is. `true` → removal works.

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

## Reference

### Build Command
```powershell
# Run at the start of every session before deploying
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
Set-Location "C:\Users\66664\OneDrive\Desktop\Coding\CustomBlockss"
.\gradlew.bat build
# Deploy: build/libs/customblocks-1.0.0.jar  (NOT -dev or -sources)
# The live server may be running an older version — always build fresh first.
# SpotBugs exits 1 as usual — not a failure, the build still succeeds.
```

### Critical Gotchas

**Read these before touching any code. Violating them causes silent bugs or build failures.**

**1. UTF-8 BOM (MOST IMPORTANT)** — The Edit tool sometimes writes files with a BOM (`﻿`), causing `illegal character` compile errors on every import line. Check after any edit:
```powershell
$bytes = [System.IO.File]::ReadAllBytes("path\to\File.java")
$bytes[0]  # Must be 112 ('p') — if it's 239, you have a BOM
```
Strip it if present:
```powershell
$path = "path\to\File.java"
$content = [System.IO.File]::ReadAllText($path).TrimStart([char]0xFEFF)
[System.IO.File]::WriteAllText($path, $content, [System.Text.UTF8Encoding]::new($false))
```

**1b. Curly-Quote Corruption (discovered Session 2)** — The Edit tool sometimes converts ASCII `"` into Unicode curly quotes `U+201C "` / `U+201D "` inside string literals. Java won't compile these — you get a cascade of `';' expected`, `illegal character: '“'`, `not a statement` errors that look unrelated. The `verifyMojibake` gradle task does NOT catch it. If a build dies with illegal-character errors right after a GUI/lore edit, run this on the file before debugging the logic:
```powershell
$path = "path\to\File.java"
$content = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
$fixed = $content.Replace([char]0x201C, [char]0x0022).Replace([char]0x201D, [char]0x0022)
[System.IO.File]::WriteAllText($path, $fixed, (New-Object System.Text.UTF8Encoding($false)))
```

**2. SoundEvents Type Split (Yarn 1.21.1+build.3)**
- `SoundEvents.BLOCK_NOTE_BLOCK_*` → type is `RegistryEntry<SoundEvent>` → **needs `.value()`**
- All other SoundEvents constants (ENTITY_EXPERIENCE_ORB_PICKUP, BLOCK_AMETHYST_BLOCK_CHIME, BLOCK_BEACON_ACTIVATE, etc.) → bare `SoundEvent` → **must NOT use `.value()`**

**3. Key Types — Do Not Guess These**
- `DraftManager.Draft` is a record — use `.payload()` **not** `.data()`
- `DraftManager.take(uuid)` returns `Optional<Draft>` — check `.isPresent()` first
- `SlotData` is **NOT a record** — use `d.customId` (public field), **not** `d.id()` or `d.customId()`
- `GuiState` is a record with exactly these fields in order: `(GuiMode mode, String editingId, int page, boolean confirmDelete, int shapeBoxPage, boolean fromCommand)`

### Key File Map
| File | Role |
|------|------|
| `gui/GuiManager.java` | All GUI open/build/click logic (~8000+ lines) |
| `gui/FeedbackHelper.java` | Sound/particle/actionBar/title/bossBar feedback |
| `gui/CbScreenHandler.java` | Screen handler; `refreshWith(SimpleInventory)` |
| `gui/GuiMode.java` | Enum of all GUI screen types |
| `gui/GuiState.java` | Immutable state record per player |
| `core/DraftManager.java` | Save/restore multi-step flow state across disconnect |
| `core/UndoManager.java` | Undo/redo stacks (global + per-player); 10k depth |
| `core/LockManager.java` | Per-block lock persistence (`locks.json`) |
| `core/FavoritesManager.java` | Per-player favorites (`favorites.json.gz`) |
| `core/SlotManager.java` | Block slot CRUD, persistence |
| `core/SlotData.java` | Block data object (NOT a record) |
| `core/ImageProcessor.java` | Image download + background removal |
| `client/texture/TextureCache.java` | GPU texture cache with CRC32 skip + pre-decode pool |
| `command/CustomBlockCommand.java` | All `/cb` subcommands (~3000 lines) |
| `command/ChatHelper.java` | Branded message helpers |
| `command/PermissionHelper.java` | LuckPerms/OP permission checks |
| `network/ResourcePackServer.java` | Serves the pack ZIP over HTTP |
| `network/ResourcePackManager.java` | Schedules + builds the pack, notifies clients |
| `CustomBlocksConfig.java` | Config persistence (JSON); `maxUndoDepth=10000` |

### Code Patterns Reference
```java
// Send action bar overlay
FeedbackHelper.actionBar(player, "§a§l✔ §r§aCreated: §f" + name);

// Send full-screen title
FeedbackHelper.title(player, "§a§l✔ Created!", "§f" + name);

// Save draft on disconnect/ESC
DraftManager.save(uuid, DraftManager.Kind.SESSION_SHELL, Map.of(
    "guiMode", state.mode().name(),
    "editingId", state.editingId() != null ? state.editingId() : "",
    "page", state.page(),
    "shapeBoxPage", state.shapeBoxPage(),
    "fromCommand", state.fromCommand(),
    "confirmDelete", state.confirmDelete()));

// Restore draft
Optional<DraftManager.Draft> opt = DraftManager.take(uuid);
Map<String, Object> data = opt.get().payload(); // use .payload() NOT .data()

// Clickable link in chat
new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.OPEN_URL, url)
// (see ChatHelper.java; CustomBlockCommand uses this in several places)

// Lock check in editor handler
if (LockManager.isLocked(id) && slot != 44 && slot != 0 && slot != 2 && slot != 43 && slot != 45) {
    playError(player);
    FeedbackHelper.actionBar(player, "§c§l🔒 §r§cLocked — /cb unlock " + id);
    return;
}
```
