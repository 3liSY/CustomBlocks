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
* **The 0-100% Scale Issue:** The GUI slider (0-100) doesn't mathematically map to 0-100% of the color spectrum properly. A 90% tolerance wasn't actually covering 90% of colors because the YCbCr luma/chroma multipliers (`1.8` and `0.85`) fall short of the true maximums.
* **Black Border Issue:** The flood-fill algorithm requires the white background to actually touch the absolute edges of the image. Images with black borders (like the T-Rex test) will block the flood-fill entirely.
**The Fix:** 
1. Remove the hard cap and separate the `autoTolerance` logic from the manual config tolerance. (Done)
2. **Rewrite the tolerance math** so that 0-100 on the slider correctly maps to 0-100% of the full YCbCr / Lab color space, ensuring 90% tolerance actually behaves like 90%.

---

## 2. PIX1: New Blocks Come Out Pixelated
[ ] **Code Written**
[ ] **Tested In-Game**

**State:** 🔴 BROKEN — Needs smart scaling.
**Files:** `core/ImageProcessor.java`
**Technical Details:** 
* We previously forced everything to use Bicubic interpolation. While this makes photos look smooth and beautiful, it ruins small pixel art (like 16x16 logos) by making them blurry.
* We need a smart approach that scales based on the input size, so pixel art stays sharp and photos stay smooth.
**The Fix:** Implement an auto-detect in `ImageProcessor.resizeTo()`. If both `srcWidth` and `srcHeight` are `<= 64`, use `NEAREST_NEIGHBOR` (sharp). Otherwise, use `BICUBIC` (smooth).

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
