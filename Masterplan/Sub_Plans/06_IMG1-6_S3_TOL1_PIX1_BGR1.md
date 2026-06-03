# Image Processing (IMG, TOL, PIX, BGR)

# 04_Link_Parsing_Fixes

---

## 1. IMG1 — CDN Download Headers

[x] **Code Written**
[x] **Tested In-Game** — CONFIRMED 2026-06-02

**State:** ✅ DONE — Chrome User-Agent + Accept headers working. Discord and most CDNs download correctly.
**Known limitation:** WixMP (`images-wixmp-ed30a86b8c4ca887773594c2.wixmp.com`) permanently blocked at datacenter level. No fix possible without a proxy. Error message already guides users to use Discord or Imgur instead.

---

## 2. IMG6 — Tenor / Giphy HTML Scraper

[ ] **Code Written**
[ ] **Tested In-Game**

**State:** 💬 AWAITING APPROVAL — not implemented. Discussed but not approved yet.

**Problem:** Players paste Tenor/Giphy page URLs instead of direct image links. Plugin downloads HTML (`3C 21 44 4F` = `<!DOCTYPE html>`) and fails.

**Proposed fix:**
* If downloaded bytes start with `<!DO` (HTML), scan for `<meta property="og:image" content="...">` tag
* Extract the direct image URL from that tag and re-download
* Works for Tenor, Giphy, Imgur, Reddit — any site that sets og:image

**Status:** Needs developer approval before any code is written.



# Active Batch: TOL1, PIX1, COL3/4, BGR1

*This batch was moved from the main backlog. View the full backlog here: [MASTERPLAN.md](../MASTERPLAN.md)*

---

## 1. TOL1: Tolerance Has No Effect (80 = same as 30)

[x] **Code Written**
[x] **Tested In-Game** — CONFIRMED 2026-06-02

**State:** ✅ DONE — Tolerance correctly samples edge pixels (including non-white backgrounds like grey). Mode prompt logic implemented for setting tolerance > 0.
**Files:** `core/ImageProcessor.java`, `gui/GuiManager.java`, `command/CustomBlockCommand.java`

**Technical Details:**
* Previous attempted fix: `effectiveTol = (cfgTol > 0) ? cfgTol : autoTol` in `replaceBackground()`.
* In-game result: no visible difference between low and high tolerance — fix did not land OR a second override exists elsewhere in the call chain.
* Auto-detect has been REMOVED by decision (see BGR1 section). Plugin must use only the manual `/cb tolerance` value.

**Investigation required before any fix:**
1. Trace the FULL call chain from `/cb tolerance <n>` → config save → `replaceBackground()` → where `effectiveTol` is actually used
2. Confirm `bgRemovalAutoDetect` is NOT defaulting to `true` and overriding manual value
3. Find if there is a second `Math.min()` or clamp anywhere in the chain
4. Confirm `bgRemovalTolerance` is actually being written to config and read back correctly on next import

**New behaviour to add (confirmed 2026-06-02):**
* **Tolerance 0 = No Background Removal.** If the player sets tolerance to 0, the plugin must treat it identically to "No Background Removal" mode and show a message: *"Tolerance is 0 — background removal is off. Set a value above 0 to remove backgrounds."*

**Note:** Fixing TOL1 will also fix COL3/COL4 — do NOT touch COL3/4 separately until TOL1 is confirmed working.

---

## 2. PIX1: New Blocks Come Out Pixelated

[x] **Code Written**
[x] **Tested In-Game** — CONFIRMED 2026-06-02

**State:** ✅ ACCEPTED — bicubic scaling applied. Smooth at normal viewing distance. Slight pixelation when zoomed very close is a Minecraft platform limit. No further fix planned.

---



## 4. BGR1: Background Removal System

[x] **Code Written**
[x] **Tested In-Game** — CONFIRMED 2026-06-02

**State:** ✅ DONE (Changes A & B applied. Future smart system C pending separate plan).

### A. Remove Auto-Detect (immediate)
**Decision:** Remove `autoTolerance()` from the import pipeline. Plugin always uses the player's manual `/cb tolerance` value. No guessing.
* In `ImageProcessor.replaceBackground()`: remove the `bgRemovalAutoDetect` branch. Always use `CustomBlocksConfig.bgRemovalTolerance`.
* In `/cb config` GUI: remove the Auto-Detect ON/OFF toggle.

### B. Background Mode Renames (confirmed — also fixes IMG2 pending rename)
Rename the three background mode labels everywhere they appear in the UI:
* `corners_only` → **"Remove Background"** — *"Removes background from edges. Trapped holes kept."*
* `corners_and_trapped` → **"Remove Background + Holes"** — *"Removes background AND matching colors trapped inside."*
* `none` → **"No Background Removal"** — *"Imports exactly as-is. No colors deleted."*

**Files for rename:** `gui/GuiManager.java` (config UI, mode labels), `command/CustomBlockCommand.java` (any mode text in commands)

### C. Future — Full Smart System (separate plan when ready)
* Smart border-color sampling
* Slider filmstrip preview
* Quick feedback buttons
* Before/after preview



### IMG2 — Background Removal "None" Mode Toggle
**State:** ✅ CONFIRMED IN-GAME (2026-06-02) — "None" mode working. Pending: UI label renames in subplan `02_Ultimate_Background_Fix_TOL1_PIX1_COL3_4_BGR1.md` (BGR1 section B).
**Files:** `gui/GuiManager.java`
**Pending rename:** `corners_only` → "Remove Background", `corners_and_trapped` → "Remove Background + Holes", `none` → "No Background Removal".

### IMG4 — Transparent Pixels Wrongly Treated as Background
**State:** 🔴 BROKEN (Fake Transparency)
**File:** `core/ImageProcessor.java`
**Priority:** 🔴

**Problem:** The original fix successfully prevents real transparent pixels from seeding the flood-fill (confirmed in IMG4-S3). However, when users upload "fake transparent" images (JPEGs/PNGs where the grey-and-white checkerboard is baked into the actual pixels), the background removal fails because the checkerboard is not a uniform solid color. 
**Knowledge (2026-05-31):** The user uploaded a fake-transparent sheep image. The tool correctly removed the white squares of the checkerboard, but left the grey lines intact, resulting in a mesh background. This is technically expected behavior for fake transparency, but logged as broken because the user expects it to be removed. May need BGR1's AI to solve.

### IMG5 — Per-Upload Shift Key: Skip BG Removal for One Block
**State:** 🔴 SCRAPPED — confusing/unusable, superseded by BGR1.
**File:** `item/RectangleToolItem.java`
**Priority:** 🟡

Hold Shift while uploading a URL → background removal skipped for that one block, even if the global toggle is ON.
- `PendingSession` gained `boolean skipBgRemoval`. `useOnBlock()` captures `player.isSneaking()` and passes it in (+ a hint line when true). `handleChatInput()` wraps the call: `if (!skipBgRemoval) faceBytes = ImageProcessor.replaceBackground(faceBytes);`

**Test:** Normal upload → bg removed. Shift during the URL prompt → same image keeps its background, only that one upload.

### IMG4-S3 Regression
Extracted from history.
