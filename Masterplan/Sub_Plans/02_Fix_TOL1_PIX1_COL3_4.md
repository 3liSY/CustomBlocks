# Active Batch: TOL1, PIX1, COL3/4

*This batch was moved from the main backlog. View the full backlog here: [MASTERPLAN.md](../../../../MASTERPLAN.md)*

---

## 1. TOL1: Tolerance 80 = same as 30
[x] **Code Written**
[ ] **Tested In-Game**

**State:** 🟢 FIXED — `Math.min(autoTol, cfgTol)` was improperly capping the config value.
**Files:** `core/ImageProcessor.java`, `gui/GuiManager.java`
**Technical Details:** 
* When trying to set tolerance higher than 30, it behaved the same as 30 because the code used `Math.min(autoTol, cfgTol)` under the hood where `autoTol` was typically around 30.
* The `/cb settings` GUI has a toggle for auto-detect which bypasses this, but we fixed the math logic so it correctly uses `autoTol` only when the toggle is enabled, and scales to 0-100 properly.
**The Fix:** Removed the hard cap and separated the `autoTolerance` logic from the manual config tolerance.

---

## 2. PIX1: New Blocks Come Out Pixelated
[x] **Code Written**
[ ] **Tested In-Game**

**State:** 🟢 FIXED — `< 64` check removed.
**Files:** `core/ImageProcessor.java`
**Technical Details:** 
* `ImageProcessor.resizeTo()` had an adaptive check `(srcWidth < 64 || srcHeight < 64) ? NEAREST_NEIGHBOR : BICUBIC`.
* If an uploaded image was smaller than 64x64, it used Nearest Neighbor to scale it up to 128x128. Since 128 is rarely a clean multiple, Nearest Neighbor produced badly distorted, jagged pixels.
**The Fix:** Removed the `< 64` check and forced `RenderingHints.VALUE_INTERPOLATION_BICUBIC` for all resizing.

---

## 3. COL3/4: Enclosed holes not filling, halos remain
[x] **Code Written**
[ ] **Tested In-Game**

**State:** 🟢 FIXED — BFS traversal fixed and expand threshold corrected.
**Files:** `item/ColorTriangleItem.java`
**Technical Details:** 
* For enclosed regions, `fillTrappedBackgroundRegions()` skipped BFS traversal if `isHoleCandidate()` failed initially.
* Anti-aliased border pixels (halos) were escaping because `expandThreshold` was set to `labThreshold * 1.2` instead of `1.5` as intended.
**The Fix:** Fixed BFS to start from any unvisited pixel (only filling if it contains a candidate) and increased `expandThreshold` to `1.5x` to eliminate 1px halos.
