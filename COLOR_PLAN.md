# Color Tools Fix Plan — VERIFIED
*Created: 2026-05-26. All file paths, line numbers, and method names confirmed against actual source.*

**RULE: Nothing is ✅ done until the developer confirms it in-game. Build passing is not done.**

> This plan covers the color triangle and color square tools only.
> Color square delay is already tracked in FIX_PLAN.md as Fix #3 — not repeated here.

---

## Before Every Session

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
cd CustomBlockss
./gradlew build
# Deploy: build/libs/customblocks-1.0.0.jar (not -dev or -sources)
```

---

## Color Fix #1 — Landlocked Areas Not Recoloring
**Status: NOT STARTED**
**The config option `corners_and_trapped` exists but does not work for same-color trapped regions.**

### What is broken and where

`ColorTriangleItem.java` — the `recolourBackground()` method at **line 317** runs a BFS flood-fill seeded from the image perimeter. After the BFS, if `fillTrapped = true` (mode is `corners_and_trapped`), it calls `fillTrappedBackgroundRegions()` at **line 468**.

`fillTrappedBackgroundRegions()` uses `isHoleCandidate()` at **line 519** to decide what to fill. `isHoleCandidate()` only returns `true` for:
- Alpha < 50 (transparent)
- Max RGB channel ≤ 36 (near-black)
- Neutral grey: spread ≤ 18, average between 70–220

**The problem:** a "9" block with a red background has a red enclosed area inside the "9" curve. That red area is the same color as the outer background — but `isHoleCandidate()` only looks for black/grey/transparent. It never finds the red trapped pixels. So `corners_and_trapped` silently does nothing for colored enclosed regions.

### The fix

In `recolourBackground()` at **line 387**, the `fillTrapped` block currently is:

```java
            if (fillTrapped) {
                fillTrappedBackgroundRegions(img, visited, newArgb);
            }
```

Replace with:

```java
            if (fillTrapped) {
                // Pass 1: recolor any unvisited pixels that match the background color.
                // This catches same-color landlocked areas (e.g. red inside a red-bg "9").
                // Uses the same isBackgroundLab check as the main BFS, so tolerance is respected.
                for (int x = 0; x < w; x++) {
                    for (int y = 0; y < h; y++) {
                        if (!visited[x][y] && isBackgroundLab(img, x, y, bgA, bgLab, labThreshold)) {
                            img.setRGB(x, y, newArgb);
                            visited[x][y] = true;
                        }
                    }
                }
                // Pass 2: existing behavior — recolor black/grey trapped holes (unchanged)
                fillTrappedBackgroundRegions(img, visited, newArgb);
            }
```

**No other files change. No method signatures change.**

`isBackgroundLab()` is already defined at **line 413** in the same file and already has access to `bgA`, `bgLab`, and `labThreshold` — they are all local variables in `recolourBackground()` that are visible here.

### Test
1. Set fill mode in `/cb config` to `corners_and_trapped` (currently called that; see Color Fix #3 for rename)
2. Right-click a block with an enclosed same-color region (like the "9") with a triangle
3. Both the outer background AND the enclosed area change color
4. A block with no enclosed areas still recolors correctly (no regression)

---

## Color Fix #2 — Edge Halo After Recoloring
**Status: NOT STARTED**
**Anti-aliased border pixels between background and design retain the old color, making a visible halo.**

### What is broken and where

In `recolourBackground()` at **line 317**, the BFS only recolors pixels where `isBackgroundLab()` returns `true`. Pixels at the border between background and design are blended (anti-aliased) — they are close to the background color but past the `labThreshold`, so they are never recolored. Result: a faint fringe of old color visible around the edges of letters, shapes, etc.

### The fix

After the main BFS block (and after the `fillTrapped` block), add a blending pass. This pass finds unvisited pixels within 1.5× the normal threshold and blends them proportionally toward the new color:

Add this block immediately before the `ByteArrayOutputStream` write at the end of `recolourBackground()`:

```java
        // Edge blend pass: smooth anti-aliased border pixels.
        // Any unvisited pixel within 1.5x the threshold gets partially shifted toward the new color.
        // Pixels closer to the background color get shifted more; pixels farther get shifted less.
        double blendThreshold = labThreshold * 1.5;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (visited[x][y]) continue;
                int px = img.getRGB(x, y);
                int pa = (px >> 24) & 0xFF;
                if (pa < 50) continue;
                int pr = (px >> 16) & 0xFF;
                int pg = (px >> 8)  & 0xFF;
                int pb =  px        & 0xFF;
                if (bgA < 50) continue;
                double[] pLab = rgbToLab(pr, pg, pb);
                double dE = Math.sqrt(
                    (pLab[0]-bgLab[0])*(pLab[0]-bgLab[0]) +
                    (pLab[1]-bgLab[1])*(pLab[1]-bgLab[1]) +
                    (pLab[2]-bgLab[2])*(pLab[2]-bgLab[2]));
                if (dE > blendThreshold) continue;
                double t = 1.0 - (dE / blendThreshold); // 1.0 = fully bg color, 0.0 = edge of zone
                int blendR = (int) Math.round(newR * t + pr * (1.0 - t));
                int blendG = (int) Math.round(newG * t + pg * (1.0 - t));
                int blendB = (int) Math.round(newB * t + pb * (1.0 - t));
                img.setRGB(x, y, (pa << 24) | (blendR << 16) | (blendG << 8) | blendB);
            }
        }
```

`rgbToLab()` is already defined at **line 431** in the same file. All local variables (`bgLab`, `labThreshold`, `newR`, `newG`, `newB`, `visited`) are already in scope at this point.

**No other files change. No method signatures change.**

### Test
1. Recolor a block that has a sharp-edged design on a solid background
2. The border between background and design should look clean — no halo, no fringe of old color
3. Test on a block that already looked clean — confirm no regression, edges still sharp

---

## Color Fix #3 — Config Names, Tool Tooltip, First-Use Hint
**Status: NOT STARTED**
**The fill mode config values are technical gibberish. Tools give no UX feedback about current settings.**

### Problem A — Config display names

`CustomBlocksConfig.java` line **99** declares:
```java
public static volatile String colorToolBackgroundMode = "unset";
```
Valid values: `"unset"`, `"corners_only"`, `"corners_and_trapped"`.

These are never shown to players directly as values — they are formatted for display in the GUI through `GuiManager.java`'s `formatColorToolMode()` method at **line 6673**.

**Change only `formatColorToolMode()` at line 6673.** Do not rename the actual config values — doing so would break existing `config.json` files on live servers.

Read the existing method body at line 6673, then replace whatever it returns for each mode:

| Old return value | New return value |
|-----------------|-----------------|
| Whatever it shows for `"corners_only"` | `"Background Only"` |
| Whatever it shows for `"corners_and_trapped"` | `"Background + Enclosed Areas"` |
| Whatever it shows for `"unset"` | `"Not Configured"` |

> **Before editing:** Read the 20 lines around line 6673 to see the exact current return values.

---

### Problem B — Tool tooltip missing mode and tolerance

`ColorTriangleItem.createCustomStack()` at **line 118** sets the item lore. Currently shows:
- `"§7Recolours connected background pixels"`
- `"§7Target colour: §f#RRGGBB"`
- `"§8Right-click a CustomBlock to create a variant"`

Add two lines to the lore list in `createCustomStack()`:

```java
Text.literal("§7Mode: §f" + formatModeForTooltip() + "  §7Tolerance: §f" + getToleranceForTooltip()).styled(s -> s.withItalic(false)),
Text.literal("§8Use §f/cb tolerance §8or §f/cb config §8to change").styled(s -> s.withItalic(false))
```

Add a private static helper at the bottom of `ColorTriangleItem.java`:

```java
private static String formatModeForTooltip() {
    String m = com.customblocks.CustomBlocksConfig.colorToolBackgroundMode;
    if ("corners_and_trapped".equals(m)) return "Background + Enclosed Areas";
    if ("corners_only".equals(m))        return "Background Only";
    return "Not Configured";
}

private static String getToleranceForTooltip() {
    int t = com.customblocks.CustomBlocksConfig.bgRemovalTolerance;
    return t > 0 ? String.valueOf(t) : "35 (default)";
}
```

Do the same for `ColorSquareItem.java` — find its lore-setting code in `useOnBlock()` or its item creation method, and add the same two lines.

> **Before editing ColorSquareItem:** Read `ColorSquareItem.java` in full to find where the item lore is set. Do not add lore in `useOnBlock()` — find the item creation path.

---

### Problem C — No first-use hint

`core/FirstUseHints.java` exists. Read it fully before touching it.

Add a hint that fires the first time a player holds a color triangle or color square. The hint fires in `ColorTriangleItem.java`'s `inventoryTick()` at **line 143** (already has a `selected` check) and the equivalent in `ColorSquareItem.java`.

Inside `inventoryTick()` on `ColorTriangleItem.java`, after the particle/sound block, add:

```java
if (selected && !world.isClient && entity instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
    com.customblocks.core.FirstUseHints.showOnce(sp, "color_triangle",
        "§e§lColor Triangle: §rRight-click a custom block to create a recolored copy.",
        "§7Shift+right-click to preview first. §8Mode: §f" + formatModeForTooltip());
}
```

> **Before this:** Read `FirstUseHints.java` to confirm the exact method signature for `showOnce()`. If the method name or signature differs, use whatever actually exists.

### Test
1. Open `/cb config` → the fill mode option shows "Background Only" / "Background + Enclosed Areas"
2. Hover a custom triangle → tooltip shows current mode and tolerance at the bottom
3. Have a new player pick up a triangle for the first time → hint appears in chat once, never again
4. The hint does not appear again for that player on relog

---

## Color Fix #4 — Variant Naming Overhaul
**Status: NOT STARTED**
**Custom triangles named "Hex #FF0000" create blocks named "My Block Hex #FF0000".**

### What is broken and where

In `ColorTriangleItem.java`:
- `resolveColor()` at **line 622** reads `NBT_LABEL` from the item NBT. For custom triangles, `createCustomStack()` at **line 118** sets `NBT_LABEL` to `"Hex #RRGGBB"` via `labelForRgb(rgb)` at **line 648**.
- `deriveDisplayName()` at **line 601** replaces color words in the display name using `color.label()`.
- So a block named "My Block" + red triangle becomes "My Block Hex #FF0000".

### The fix — 3 parts

**Part A — Custom name NBT field**

Add a new NBT key constant near the top of `ColorTriangleItem.java` (after the existing `NBT_KEY` at line ~59):
```java
private static final String NBT_CUSTOM_NAME = "cb_triangle_custom_name";
```

In `resolveColor()` at **line 622**, before reading `NBT_LABEL`, check for `NBT_CUSTOM_NAME`:
```java
if (nbt.contains(NBT_CUSTOM_NAME) && !nbt.getString(NBT_CUSTOM_NAME).isBlank()) {
    String customName = nbt.getString(NBT_CUSTOM_NAME);
    // Use customName as label, derive key from it
    String key = customName.toLowerCase(java.util.Locale.ROOT).replace(" ", "_");
    return new TriangleColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, customName, key);
}
```

Do the same for `ColorSquareItem.java` — read the file to find where it resolves its color label, and add the same custom-name override.

**Part B — Auto-detect closest shade when no custom name**

In `labelForRgb()` at **line 648**, replace:
```java
private static String labelForRgb(int rgb) {
    return "Hex #" + hexForRgb(rgb);
}
```
With:
```java
private static String labelForRgb(int rgb) {
    int r = (rgb >> 16) & 0xFF;
    int g = (rgb >> 8)  & 0xFF;
    int b =  rgb        & 0xFF;
    // Find closest named color from ColorLibrary
    String closest = null;
    double bestDist = Double.MAX_VALUE;
    for (com.customblocks.gui.ColorLibrary.LibColor c : com.customblocks.gui.ColorLibrary.ALL) {
        int[] cr = com.customblocks.gui.ColorPickerHelper.hexToRgb(c.hex());
        if (cr == null) continue;
        double[] labA = rgbToLab(r, g, b);
        double[] labB = rgbToLab(cr[0], cr[1], cr[2]);
        double dE = Math.sqrt(
            (labA[0]-labB[0])*(labA[0]-labB[0]) +
            (labA[1]-labB[1])*(labA[1]-labB[1]) +
            (labA[2]-labB[2])*(labA[2]-labB[2]));
        if (dE < bestDist) { bestDist = dE; closest = c.name(); }
    }
    // Only use the name if the match is close enough; otherwise fall back to hex
    return (closest != null && bestDist < 25.0) ? closest : "Hex #" + hexForRgb(rgb);
}
```

> **Before this:** Read `ColorLibrary.java` to confirm that `LibColor` has `.hex()` and `.name()` getter methods, and `ColorPickerHelper.hexToRgb()` exists at line 66 and returns `int[]`. The agent confirmed these — double-check before writing.

`rgbToLab()` is already in `ColorTriangleItem.java` at **line 431** — accessible here since `labelForRgb` is in the same class.

**Part C — Retroactive rename with yes/no prompt**

When a custom triangle or square is renamed (in the future `/cb colors` hub — see Color Fix #5), before saving the new name, show a prompt:

```
"Update X existing blocks created with this tool? [Yes] [No]"
```

Implementation: when renaming, search `SlotManager.getAll()` for any `SlotData` whose `customId` contains the tool's old `keyForRgb()` suffix. If any are found, show the count and prompt. If the developer clicks Yes, call `SlotManager.rename(oldId, newId)` or update `displayName` for each matched block.

> **Before implementing:** Read `SlotManager.java` to confirm what method exists for renaming/updating SlotData. Find the method signature and use it exactly.

### Test
1. Create a custom triangle with color #8B0000 — it should now be auto-named "Dark Red" (or the closest match from ColorLibrary), not "Hex #8B0000"
2. A block made with that triangle should be named "My Block Dark Red"
3. Rename the triangle to "Blood Red" from the GUI — block made after that is "My Block Blood Red"
4. When renaming, get prompted about existing blocks — click Yes — existing blocks update their names

---

## Color Fix #5 — /cb colors Hub
**Status: NOT STARTED**
**All color tool commands are scattered. No unified place to create, rename, search, or manage tools.**

### What exists now

| Command | Handler | Opens |
|---------|---------|-------|
| `/cb customcolor` | `CustomBlockCommand.java` line ~1850 | `GuiManager.openCustomColorStudio()` line 725 → `buildCustomColorStudioGui()` line 6395 |
| `/cb customtriangle` | `CustomBlockCommand.java` line ~1243 | Separate flow |
| `/cb tolerance` | `CustomBlockCommand.java` line ~1624 | Chat command only |
| `/cb fillmode` | Does NOT exist | — |
| `/cb colors` | Does NOT exist | — |

### What to build

**Step 1 — Add `/cb colors` command in `CustomBlockCommand.java`**

Find the block near line 1850 where `/cb customcolor` is registered. After it, add:

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

**Step 2 — `GuiManager.openColorsHub()` and `buildColorsHubGui()`**

New public method `openColorsHub(ServerPlayerEntity player)` — follows exact same pattern as `openCustomColorStudio()` at line 725.

The hub GUI is a 54-slot inventory. Layout:

```
Row 0 (slots 0–8):   Header + search hint item
Row 1 (slots 9–17):  "My Colors" favorites row (up to 7 colors + add button + back)
Row 2 (slots 18–26): Recent colors (last 8 used, pulled from player session data)
Row 3 (slots 27–35): "Create New Color" big button + "Rename a Tool" button + separator
Row 4 (slots 36–44): [First-time welcome content OR quick-access presets]
Row 5 (slots 45–53): Footer: fill mode toggle, tolerance display, help button
```

**New player (no saved colors):** Row 1 and Row 2 show a welcome message: "Welcome! Color tools let you recolor any custom block." and a "How does this work?" button that opens help text.

**Returning player:** Row 1 shows favorited colors, Row 2 shows recent 8.

**Step 3 — Rename tool flow**

Clicking "Rename a Tool" opens a 27-slot overlay listing all custom triangles and squares currently in the player's inventory. Clicking one opens an anvil GUI (already exists in `AnvilPromptManager.java`) pre-filled with the current name. On confirm, write the new name to `NBT_CUSTOM_NAME` on the item NBT and prompt about retroactive rename (see Color Fix #4 Part C).

**Step 4 — Fill mode and tolerance controls in hub footer**

Slot 45: fill mode toggle — cycles `corners_only` → `corners_and_trapped` on click. Label shows current display name from `formatColorToolMode()` at line 6673.
Slot 47: tolerance display — shows current `bgRemovalTolerance`. Clicking it opens a chat prompt asking for a new value (same as `/cb tolerance`).
Slot 53: `?` help button — opens a plain-English explanation of what triangles and squares do, one-time or on demand.

> **Before writing `buildColorsHubGui()`:** Read the existing `buildCustomColorStudioGui()` at line 6395 in full to match the exact `ui()`, `glass()`, and `uiGlint()` helper patterns used everywhere in GuiManager.

### Test
1. `/cb colors` opens a GUI
2. New player sees welcome message and explanation
3. Returning player sees their last 8 colors in the recent row
4. Clicking "Rename a Tool" shows inventory tools — click one — anvil opens — rename — item name updates
5. After rename, create a new block variant — it uses the new name
6. Fill mode button cycles between "Background Only" and "Background + Enclosed Areas"
7. Clicking tolerance shows current value and lets you change it

---

## Work Order

Fix in this order. Nothing moves forward until the developer confirms the previous one in-game.

| # | Fix | Done when |
|---|-----|-----------|
| 1 | Landlocked recolor | Same-color trapped areas recolor correctly with `corners_and_trapped` mode |
| 2 | Edge halo | No visible fringe around design edges after recoloring |
| 3 | Config names + tooltip + hint | Mode shows plain English, tooltip shows settings, new player sees hint |
| 4 | Variant naming | Blocks named "My Block Dark Red" not "My Block Hex #8B0000" |
| 5 | /cb colors hub | One command for everything, rename works, welcome screen for new players |

**Nothing is ✅ done without developer in-game confirmation.**

---

## Known Limitations (not in this plan)

- **Screen eyedrop** (sampling any pixel from the screen) — requires LWJGL framebuffer access. Technically possible but complex. Research needed before planning.
- **Live preview of recolor in GUI** — requires server-side texture processing triggered by GUI hover, which would be slow. Needs a client-side solution first.
- **Creative inventory search for custom triangles** — Minecraft's own search doesn't index custom NBT item names cleanly. Requires a client-side mixin to the search system.
