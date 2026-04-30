# 👑 CustomBlocks Ultimate Master Plan (Royal Directive v2)

> **Generated after exhaustive, quintuple-checked forensic analysis of the codebase.**
> **This document is the absolute source of truth. Any AI reading this must execute it with 100% precision, atomic commits, and ZERO assumptions.**

---

## § 1. CORE BUG FORENSICS & RESOLUTION

### 1A. The "Resume" Resource Pack Loading Bug (CRITICAL)
- **Symptom:** `/cb rp resume` fails to trigger the red reloading screen.
- **Root Cause (Forensic Analysis):** When `rpPaused` is `true`, texture modifications update the `CACHE_HASH_FILE` silently on disk. When the server sends an `RpPausePayload(false)` (resume), `CustomBlocksClient.java` checks `rpDirtyWhilePaused` and calls `scheduleGenerateAndReload(500L)`. However, `scheduleGenerateAndReload` calculates the current texture hash and compares it against `loadCachedHash()`. Since they match (updated during the pause), the method returns early, skipping the reload.
- **Required Fix (Precision):** 
  1. Open `CustomBlocksClient.java`.
  2. Implement a new method: `private static void clearCachedHash(File runDir)` that deletes `CACHE_HASH_FILE`.
  3. Inject `clearCachedHash(context.client().runDirectory);` inside the `RpPausePayload` receiver exactly before calling `scheduleGenerateAndReload(context.client(), 500L);`.

### 1B. The `sanitize()` Hyphen Inconsistency (HIGH)
- **Symptom:** Capital letters and hyphens fail silently or throw errors in commands.
- **Root Cause (Forensic Analysis):** `CustomBlockCommand.java` has a `sanitize(String id)` method that uses `replaceAll("[^a-z0-9_]", "_")`, strictly stripping hyphens. Furthermore, methods like `cmdReId` bypass `sanitize()` and hard-fail on `newId.matches("[a-z0-9_\\-]+")` if capital letters are present.
- **Required Fix (Precision):** 
  1. Update `sanitize(String id)` to return `id.toLowerCase().replaceAll("[^a-z0-9_\\-]", "_")`.
  2. Refactor `cmdReId` to apply `String newId = sanitize(rawNewId);` immediately upon entry, removing the hardcoded regex rejection.
  3. Ensure all commands (`cmdDelete`, `cmdRename`, `cmdRetexture`, `cmdSetGlow`, `cmdGive`, `cmdAdd`) pipe their raw IDs through `sanitize()`.

### 1C. Comprehensive Brigadier Autocomplete Overhaul (MEDIUM)
- **Symptom:** Multi-argument commands like `bulkdelete` are tedious, and `setshape` lacks auto-complete for presets.
- **Root Cause (Forensic Analysis):** Brigadier requires custom `SuggestionProvider` logic for space-separated list arguments and dynamic preset keys.
- **Required Fix (Precision):**
  1. Implement a `MultiBlockSuggestionProvider` for `/cb bulkdelete`. It must parse `ctx.getInput()`, split by spaces, filter out already-typed IDs, and suggest the remaining valid block IDs.
  2. Implement `SHAPE_SUGGESTIONS` mapping directly to `SlotManager.SHAPE_PRESETS.keySet()` for `/cb setshape`.
  3. Audit the entire `CommandRegistrationCallback` tree to ensure no argument is left without a suggestion provider.

### 1D. GUI Back-Stack ESC Multi-Press Bug (MEDIUM)
- **Symptom:** Pressing ESC to close nested GUIs requires multiple presses, causing UI stack corruption.
- **Root Cause (Forensic Analysis):** The `handleEscBack()` logic in `GuiManager.java` uses `ArrayDeque` with an asynchronous `REOPENING_SCREENS` guard. Rapid `ESC` presses push/pop faster than the Minecraft client's render tick can update `currentScreen`, causing race conditions.
- **Required Fix (Precision):** Implement a debouncer (`System.currentTimeMillis()`) and a strict lock. Do not allow a stack pop unless `client.currentScreen` perfectly matches the expected active screen ID.

### 1E. Complete Removal of AI Allay (HIGH)
- **Symptom:** Code bloat, entity lag, and unused commands.
- **Root Cause:** Obsolete `AssistantManager.java` and `/cb ai` command tree.
- **Required Fix (Precision):** Ensure `AssistantManager.java` is permanently purged. Strip `cmdHelperSpawn`, `cmdHelperHide`, `cmdHelperCome`, etc., and the `.then(CommandManager.literal("ai"))` branch from `CustomBlockCommand.java`.

---

## § 2. THE ARTIST & PREMIUM UX OVERHAUL

### 2A. The Diamond Triangle (Master Control Panel)
- **Symptom:** `ColorTriangleItem` hardcodes RGB checks (`TOLERANCE = 35`), leaving ugly green halos, and `/cb config` provides basic settings.
- **Required Fix (Precision):**
  1. Delete global BG tolerance from `/cb config`.
  2. Create the **Diamond Triangle**. Right-clicking opens a Master GUI with three Royal Categories:
     - **Global Advanced Settings:** YCbCr Math toggles and Tolerance Slider. YCbCr perfectly separates luminance from chrominance to destroy edge halos.
     - **Triangle Factory:** Hex Code picker -> "Create" -> grants a physical Custom Triangle item.
     - **Bulk Updater:** Scans all `_green` (or other suffix) variants, fetches their base blocks, and re-cuts their backgrounds using the new YCbCr logic, silently updating textures without altering block IDs.

### 2B. True 3D Models for Shapes (The Geometry Update)
- **Symptom:** `/cb setshape slab` works for collision but renders as a full stretched cube.
- **Required Fix (Precision):** Refactor `ResourcePackGenerator.generateSingleSlot`. Instead of defaulting to `cube_all` JSON models, intercept shaped blocks. Generate exact 3D JSON multi-element models matching `d.shapeBoxes`. Calculate dynamic UV mapping bounds so textures fit perfectly without squashing.

### 2C. The Golden Hexagon
- **Goal:** Unprecedented UV face manipulation.
- **Required Fix:** A premium item that, when right-clicked on a face, opens a menu to Rotate (90, 180, 270) and Flip (Horizontal, Vertical) the UV coordinates of that specific face.

### 2D. The Royal UI Elements (Sliders & Brushes)
- **Goal:** Abandon blocky Minecraft buttons.
- **Required Fix:** 
  1. Build `RoyalSliderWidget` and `RoyalToggleWidget` with smooth drag physics, satisfying hover states, and Amethyst chime feedback.
  2. Implement **The Visual Shape Editor (Amethyst Chisel):** A GUI utilizing X/Y/Z Royal Sliders to physically mold hitbox dimensions in real-time.
  3. Implement **The Lumina Brush:** Right-click a block, drag a Royal Slider to dynamically update its Light Level (Glow) from 0-15 in real-time.
  4. Inject Echo Shard (Pause) and Amethyst Shard (Resume) into slots 24 & 26 of `GuiManager.buildResourceHub()`.

---

## § 3. EXECUTION PHASING (STRICT PROTOCOL)

**Any AI executing this plan must adhere to the 9-Layer Shield Doctrine (Atomic commits, `./gradlew build` verification between every step).**

1. **Phase 1: Foundation & Cleansing**
   - Cache-bust fix (`CustomBlocksClient.java`).
   - `sanitize()` overhaul & `CustomBlockCommand.java` AI cleanup.
   - Brigadier Autocomplete Overhaul (`MultiBlockSuggestionProvider` & `SHAPE_SUGGESTIONS`).
   - Resource Hub Pause/Resume UI buttons.

2. **Phase 2: The Geometry Update**
   - `ResourcePackGenerator` 3D JSON generation for all 12 presets.
   - Dynamic UV calculations.
   - GUI Back-Stack ESC Debouncing fix.

3. **Phase 3: The Royal UI Toolkit**
   - Build `RoyalSliderWidget` and `RoyalToggleWidget`.
   - Build the Visual Shape Editor GUI (Amethyst Chisel).
   - Build the Lumina Brush.

4. **Phase 4: The Master Control Tools**
   - The Diamond Triangle (Master GUI, YCbCr, Factory, Bulk Updater).
   - The Golden Hexagon (UV Face Rotation/Flipping).
   - Smart Color Fallback GUI.

> **End of Plan.**
> **Rule of Engagement:** Proceed sequentially. NEVER jump to Phase 2 before Phase 1 is flawlessly verified. No assumptions. No regressions. Absolute perfection.
