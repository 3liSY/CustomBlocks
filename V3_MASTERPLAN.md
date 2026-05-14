# CustomBlocks v3 Masterplan

> This plan is about making the mod feel GOOD to use — not just fixing bugs,
> but making every interaction smoother, faster, and more satisfying.

---

## Phase 1 — Fix What's Broken

### 1.1 Fix color detection (the black block bug)
The square tool figures out a block's color by reading its NAME. If a block is
visually black but its ID is "obsidian_polished" instead of "obsidian_black",
the tool says "variant not found" and does nothing.

**Fix:** When the name doesn't contain a color word, look at the actual texture
pixels. Sample the dominant color and match it to the nearest color family.
The name-based check stays as a fast path, pixel analysis is the fallback.

**Files:** `ColorSquareItem.java`, `ColorPickerHelper.java`

### 1.2 Color tools should just work
Right now, color triangles/squares silently fail with "not configured" if the
player hasn't manually set `colorToolBackgroundMode` in config. Nobody knows
this config exists.

**Fix:** Default to `"corners_only"` instead of `"unset"`. Delete the "unset" state.

**Files:** `CustomBlocksConfig.java`

### 1.3 More than 3 colors
Only "black", "yellow", "green" are recognized. That's embarrassingly few.

**Fix:** Add: white, red, orange, blue, purple, pink, brown, gray/grey, dark, light.
Add aliases so "charcoal" maps to black, "crimson" maps to red, etc.

**Files:** `ColorSquareItem.java`, `ColorTriangleItem.java`

---

## Phase 2 — The Bulk Operations Hub

### 2.1 `/cb bulkgui` — one place for ALL bulk operations
Right now bulk operations are scattered across commands. You have to memorize
`/cb bulkdelete`, `/cb bulkrecolor`, `/cb bulkblockadd` separately.

**Fix:** Add `/cb bulkgui` that opens a single hub GUI:

```
Row 1:  [Bulk Delete]  [Bulk Recolor]  [Bulk Rename]  [Bulk Re-ID]
Row 2:  [Bulk Properties]  [Bulk Move Category]  [Bulk Export]  [Bulk Duplicate]
Row 3:  [Bulk Lock/Unlock]  [Bulk Favorite]  [Bulk Shape]  [Bulk Sound]
```

Each button opens its own sub-GUI with:
- A block selector (search + filter + category picker + select all)
- The operation-specific controls
- A preview of what will happen
- Confirm / Cancel

This becomes THE place you go when you want to do anything to multiple blocks.
Every bulk operation — existing and new — lives here.

**Files:** `GuiManager.java`, `CustomBlockCommand.java`

### 2.2 New bulk operations to add
These don't exist yet and should all be accessible from the bulk hub:

- **Bulk Rename** — prefix, suffix, or find/replace across display names
- **Bulk Re-ID** — pattern-based ID renaming (e.g. "mob_" → "creature_")
- **Bulk Properties** — set sound, glow, hardness, or collision on many blocks at once
- **Bulk Export** — export a whole selection as one package
- **Bulk Move Category** — move blocks FROM one category TO another (not just add)
- **Bulk Duplicate** — clone multiple blocks with a suffix
- **Bulk Lock/Unlock** — lock or unlock editing on many blocks
- **Bulk Favorite** — star/unstar multiple blocks
- **Bulk Shape** — apply a shape preset to many blocks
- **Bulk Sound** — set sound type across a selection

**Files:** `CustomBlockCommand.java`, `GuiManager.java`

### 2.3 Smart selection in all bulk GUIs
Every bulk GUI should have the same powerful selection tools:

- **Search bar** — type to filter blocks by name/ID as you select
- **Category filter** — show only blocks from a specific category
- **Select All / Deselect All** — one click, affects ALL pages not just current
- **Select by pattern** — "all blocks containing 'marble'" 
- **Selection counter** — always show "§e12 selected" so you know what you're about to do

**Files:** `GuiManager.java`

---

## Phase 3 — Color System Overhaul

The mod already has Color Studio (7 tints), Palette Generator (16 hues),
AI Smart Suggest (18 presets), Dress Overlays (5 effects), and Gradients.
But they're scattered, some are command-only, and hex input is the only way
to get custom colors. This phase makes the whole color experience seamless.

### 3.1 Ready-to-use color library (no hex required)
Instead of forcing users to type "#FF5500", give them a visual color picker
with named colors they can just CLICK.

**Add a Color Library GUI** accessible from Magic Items, Color Studio, and
anywhere hex is currently required:

```
Row 1 (Basics):     Red    Orange   Yellow   Lime    Green   Cyan
Row 2 (Basics):     Blue   Purple   Magenta  Pink    White   Black
Row 3 (Neutrals):   Light Gray  Gray  Dark Gray  Brown  Tan  Cream
Row 4 (Rich):       Crimson  Gold  Forest  Navy  Indigo  Coral
Row 5 (Pastels):    Baby Blue  Lavender  Mint  Peach  Rose  Butter
```

Each color shows a colored item with its name and hex code in the lore.
Click = instant. No typing. No memorizing hex codes.

**Also:** Anywhere in the mod that currently asks for a hex code should ALSO
accept color names: `/cb triangle red`, `/cb square "baby blue"`, etc.

**Files:** New `ColorLibrary.java`, `GuiManager.java`, `ColorPickerHelper.java`,
`CustomBlockCommand.java`

### 3.2 Favorite colors / custom palette
Let players save colors they use often.

- Click a color with shift = save to your personal palette
- Personal palette shows up as a row at the top of the Color Library
- `/cb palette add "My Red" #CC3333` — save with a custom name
- `/cb palette list` — see your saved colors
- Palette persists per-player, survives restarts

**Files:** New `PlayerPaletteManager.java`, `GuiManager.java`

### 3.3 Dress Overlays in GUI (not just commands)
The 5 dress overlays (cracked, mossy, weathered, glowing, frosted) are
awesome but ONLY accessible via `/cb dress <id> <type>`. Most users don't
know they exist.

**Fix:** Add a "Dress & Effects" button in the Block Editor that opens a GUI:

```
Row 1:  [Cracked]  [Mossy]  [Weathered]  [Glowing]  [Frosted]
Row 2:  [Strength: Low]  [Strength: Medium]  [Strength: High]
Row 3:  [Preview]  [Apply]  [Cancel]
```

Click an effect → see a preview → adjust strength → apply or cancel.

**Files:** `GuiManager.java`, `ColorVariantService.java`

### 3.4 Gradient Generator in GUI (not just commands)
Gradients are also command-only (`/cb gradient from to steps`). Should be visual.

**Fix:** Add "Gradient" button in Block Editor or Color Studio:

1. Pick block A (source)
2. Pick block B (target) from a block picker
3. Slider: how many steps (2-16)
4. Preview row shows the gradient colors
5. Click "Create" → generates all variants

**Files:** `GuiManager.java`, `ColorVariantService.java`

### 3.5 Triangle recolor preview
When you right-click a block with a color triangle, the recolor happens
immediately with no way to see the result first.

**Fix:** Shift+right-click = preview mode. Shows the recolored texture as a
held item or in a small confirmation GUI. Player confirms or cancels.

**Files:** `ColorTriangleItem.java`, `GuiManager.java`

### 3.6 Adjustable flood-fill tolerance
Background detection tolerance is hardcoded at 35. Some textures need more,
some need less, and there's no way to adjust it per-use.

**Fix:** Add a tolerance slider in the Background Studio that the triangle
respects. Or: shift+scroll while holding a triangle to adjust tolerance
before clicking.

**Files:** `ColorTriangleItem.java`, `CustomBlocksConfig.java`

### 3.7 Smarter background detection
Triangle only samples the top-left pixel as "background". Fails when the
design extends to corners.

**Fix:** Sample all 4 corners + 4 edge midpoints. Use the most common color
among those 8 samples. Much more reliable.

**Files:** `ColorTriangleItem.java`

### 3.8 Bulk hex recolor
`/cb bulkrecolor` only supports the 3 built-in colors. Can't bulk-apply hex.

**Fix:** Accept hex values and color names: `/cb bulkrecolor red category:walls`
or `/cb bulkrecolor #FF5500 category:walls`. Also accessible from bulkgui.

**Files:** `CustomBlockCommand.java`

---

## Phase 4 — Search & Discovery

### 4.1 `/cb search` overhaul — actually useful search
Current search is basic text matching. Make it powerful:

- `/cb search marble` — name/ID search (already exists)
- `/cb search category:stone` — filter by category
- `/cb search animated:yes` — only animated blocks
- `/cb search glow:15` — only blocks with light level 15
- `/cb search hardness:>1.5` — blocks harder than 1.5
- `/cb search sound:wood` — blocks with wood sound
- `/cb search locked:yes` — only locked blocks
- `/cb search favorite:yes` — only favorites
- `/cb search created:today` — blocks made today
- `/cb search created:thisweek` — blocks made this week

Combine them: `/cb search category:stone glow:>0 animated:yes`

**Files:** `CustomBlockCommand.java`, `SearchIndex.java`

### 4.2 Search GUI with visual filters
The search GUI should have clickable filter buttons, not require typing syntax:

```
Row 1:  [Search: ___________]  [Clear]
Row 2:  [Category ▼]  [Animated ○]  [Locked ○]  [Favorites ○]  [Has Glow ○]
Row 3:  [Sort: Name ▼]  (options: Name, ID, Category, Date, Glow, Hardness)
Row 4+: [Results...]
```

Toggle filters on/off by clicking. Results update instantly.

**Files:** `GuiManager.java`, `SearchIndex.java`

### 4.3 Recent blocks & quick access
`MAX_RECENT = 3` is too few. And there's no quick way to get back to blocks
you were just editing.

**Fix:**
- Increase to 10 recent blocks
- Add `/cb recent` command that opens a GUI of your last 10 edited blocks
- Show recent blocks at the top of the main dashboard
- Recent blocks persist per-player across sessions

**Files:** `GuiManager.java`, `CustomBlockCommand.java`

### 4.4 `/cb find` — find blocks in the world
Different from search (which finds block definitions). This finds PLACED blocks.

- `/cb find <id>` — highlights all placed instances of a block within render distance
- `/cb find <id> --count` — just tells you how many are placed
- Shows glowing outlines or particle markers at each location

**Files:** `CustomBlockCommand.java`, new `BlockFinder.java`

---

## Phase 5 — Quality of Life (the "oh that's nice" stuff)

### 5.1 Sound preview before applying
The sound menu shows 16 options but you can't hear them. You apply one, place
the block, break it, and THEN find out if you liked it. Annoying.

**Fix:** Right-click a sound option = play a preview. Left-click = apply it.
Highlight the currently active sound with enchantment glint.

**Files:** `GuiManager.java`

### 5.2 One-click quick actions in block picker
When browsing blocks in the picker, you currently have to: click block → open
editor → find the button → click it. For common actions this is too many clicks.

**Fix:** Add shift-click and right-click shortcuts in ANY block list:
- **Right-click** = quick menu (give, rename, delete, favorite, edit)
- **Shift-click** = give yourself 1 of that block instantly
- **Ctrl-click** = toggle favorite

**Files:** `GuiManager.java`

### 5.3 Block editor: "You might also like" section
When editing a block, show its variants, same-category siblings, and visually
similar blocks at the bottom. Makes discovery natural.

**Files:** `GuiManager.java`

### 5.4 Category improvements
- **Category colors** — each category gets a colored glass pane icon (already exists but make it more visible)
- **Block count badge** — show "(42 blocks)" next to each category name
- **Quick stats** — "12 animated, 5 glowing, 3 locked" at the top of category view
- **Drag to reorder** — well, click-based reorder (up/down arrows on categories)

**Files:** `GuiManager.java`, `CategoryManager.java`

### 5.5 Undo shows what happened
Undo history entries should say "Created: stone_v2" or "Deleted: oak_log"
with timestamps, not just be anonymous entries.

**Files:** `GuiManager.java`, `UndoManager.java`

### 5.6 Better delete confirmation
Delete requires clicking twice within 5 seconds with no visible warning.

**Fix:** First click changes the button to "§c§l⚠ CLICK AGAIN TO DELETE"
with a red flashing effect. Extend timeout to 10 seconds.

**Files:** `GuiManager.java`

### 5.7 Scope expressions documented everywhere
The `category:plants` filter syntax is powerful but invisible. Nobody discovers it.

**Fix:** Show example syntax in every bulk command's error message.
Add `/cb help scopes` with full documentation. Show hints in bulk GUIs.

**Files:** `CustomBlockCommand.java`

### 5.8 Block templates / presets
Creating similar blocks means re-doing the same property setup every time.

**Fix:** Save a block's properties as a reusable template:
- `/cb template save "my_style" <blockId>` — saves sound, glow, hardness, shape, collision
- `/cb template apply "my_style" <newId> <url>` — creates new block with those properties
- Template picker GUI accessible from the create flow

**Files:** New `TemplateManager.java`, `CustomBlockCommand.java`, `GuiManager.java`

---

## Phase 6 — Consistency & Polish

### 6.1 Standardize all GUI layouts
Back buttons: sometimes slot 0, sometimes slot 45, sometimes both.
Borders: inconsistent glass pane usage. Title: different positions.

**Fix:** One layout standard everywhere:
- Slot 0 = Back
- Slot 4 = Title
- Content = rows 2-5
- Footer = slots 45-53 for navigation

**Files:** `GuiManager.java`

### 6.2 Standardize feedback messages
Some use `§a[GUI]`, some `§c[BG Studio]`, some nothing. Inconsistent channels
(some in chat, some actionbar, some title).

**Fix:** All mod messages use `§8[CB]` prefix. Quick confirmations = actionBar.
Detailed info = chat. Big events = title.

**Files:** `GuiManager.java`, `CustomBlockCommand.java`, `FeedbackHelper.java`

### 6.3 Editor GUI grouping
17 buttons crammed together with no visual separation.

**Fix:** Add glass pane section headers:
- "Textures" section: Retexture, AI Suggest, Color Studio, Faces
- "Properties" section: Shape, Sound, Light, Hardness
- "Manage" section: Rename, Re-ID, Duplicate, Export, Delete

**Files:** `GuiManager.java`

### 6.4 Clean up command aliases
`bulkrecolor` and `bulkcolor` are duplicates. Remove confusing aliases.

**Files:** `CustomBlockCommand.java`

---

## Phase 7 — Performance (invisible but important)

### 7.1 Incremental resource pack updates
Every tiny edit rebuilds the ENTIRE resource pack ZIP for all 600+ blocks.

**Fix:** Only rebuild changed slots. Patch the ZIP incrementally.

**Files:** `ServerPackGenerator.java`, `ResourcePackServer.java`

### 7.2 Lazy texture loading
All textures loaded into RAM on startup (~300MB for 600 blocks).

**Fix:** Load textures on-demand. Keep only metadata in memory.

**Files:** `SlotManager.java`, `SlotData.java`

### 7.3 Incremental search index
Full index rebuilt on every slot change. Just update the changed entry.

**Files:** `SearchIndex.java`

### 7.4 Persist undo history across restarts
Undo stacks are lost on server restart.

**Fix:** Save to disk on shutdown, restore on startup.

**Files:** `UndoManager.java`

---

## Phase 8 — Cleanup

### 8.1 Remove dead config stubs
`marketplaceEnabled` and `instantClickAggressivenessMs` are deprecated TODOs
that aren't connected to anything. Remove them.

**Files:** `CustomBlocksConfig.java`

---

## Summary

| Phase | Items | What it feels like |
|-------|-------|--------------------|
| 1 | 3 | "Color tools actually work now" |
| 2 | 3 | "I can do ANYTHING in bulk from one place" |
| 3 | 8 | "Colors are beautiful and easy — click, not type" |
| 4 | 4 | "I can find any block instantly" |
| 5 | 8 | "Everything feels smooth and thoughtful" |
| 6 | 4 | "The whole mod looks and feels consistent" |
| 7 | 4 | "It's faster and uses less memory" |
| 8 | 1 | "No more dead code" |
| **Total** | **35** | |

---

## What's NOT in this plan

- New block types — out of scope, the mod has enough features
- Plugin API — premature, stabilize first
- Per-face animation — niche, not a pain point
- Animation keyframe editor — cool but nobody asked for it

---

*v3 is about making everything that already exists feel FINISHED.
The mod has a massive feature set. v3 makes it a joy to use.*
