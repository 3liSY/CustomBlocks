# CustomBlocks V3 Testing Guide

This guide covers **only items implemented in the V3 Masterplan**.  
Pre-existing features (block creation, deletion, rename, etc.) are not listed here.

---

## Setup Before Testing

1. Build and deploy the mod jar to your server's `mods/` folder.
2. Join the server and run `/cb reload` if prompted.
3. Make sure color tools are configured: `/cb config` → set a colour fill mode (e.g. pixel-based).
4. You need at least one existing custom block placed in the world.

---

## V3-01 · Color System — 16 Families + Pixel Analysis + Custom Hex Items
**Masterplan refs:** 1.1, 1.3

### What was added
- The mod now recognises 16 color families (red, blue, green, yellow, black, white, purple, pink, cyan, orange, brown, gray, lightgray, lime, magenta, teal) plus ~40 aliases.
- The Color Triangle auto-detects the dominant color in a block's texture using HSB pixel clustering and can create a recolored variant in a single right-click.
- `/cb customcolor square <hex>` and `/cb customcolor triangle <hex>` give a custom-hex-colored tool for any 24-bit color.

### Setup
```
/cb customcolor square FF0000
/cb customcolor triangle 00FF00
```

### Tests

| # | What To Do | Where | Expected |
|---|-----------|-------|----------|
| 1 | Run `/cb customcolor square FF0000` | Chat | Given custom Square + Triangle #FF0000! |
| 2 | Check your inventory — look at the Square item icon | Inventory | Item icon is **red**, not light blue |
| 3 | Check the Triangle item icon for the Triangle given above | Inventory | Item icon is **green** (#00FF00), not light blue |
| 4 | Hover over the custom Square item | Inventory | Name shows **Hex #FF0000 Square** in red text |
| 5 | Run `/cb customcolor triangle 8B4513` | Chat | Given custom Square + Triangle #8B4513! |
| 6 | Check that Triangle's icon | Inventory | Item icon is a brownish saddlebrown color |
| 7 | Right-click a custom block with the Color Triangle | World | New hex-colored variant is created and given to you |
| 8 | Check the chat output after right-clicking with Triangle | Chat | `Created <name> and added it to your inventory!` |

### Edge Cases

| # | Edge | Expected |
|---|------|----------|
| E1 | Run `/cb customcolor square ZZZZZZ` (bad hex) | Command is rejected — usage error shown |
| E2 | Right-click with Triangle on a block with no texture data | `This block has no texture data to recolour.` |
| E3 | Right-click with Triangle on a block that's already that color | `This block is already <color>.` |

---

## V3-02 · Empty Slot Placeholder (Checkerboard)
**Masterplan ref:** 1.8

### What was added
When a custom block's slot is empty (block was deleted but the slot index still exists in the world), the block now renders as a **grey checkerboard** instead of the vanilla missing-model purple/black pattern. No missing-model log spam.

### Setup
1. Note the slot number of an existing custom block (use `/cb list` or look at the block in the editor).
2. Delete that block via `/cb delete <id>`.
3. Do **not** run `/cb reload` yet — the placed block in the world should remain.

### Tests

| # | What To Do | Where | Expected |
|---|-----------|-------|----------|
| 1 | After deleting the block, look at the placed version in the world | World | Block renders as a grey/dark checkerboard pattern |
| 2 | Check server console / latest.log | Console | No `Missing model` or `missing texture` lines for that slot |
| 3 | Run `/cb reload` and look again | World | Block still shows checkerboard (not broken model) |
| 4 | Place a new block in that same slot (re-create with same slot) | World | Normal block texture shows again |

### Edge Cases

| # | Edge | Expected |
|---|------|----------|
| E1 | Join the server with an empty slot block placed | World | Checkerboard visible from first load — no log errors |
| E2 | 500+ blocks registered, one slot empty | World | Only the deleted slot shows checkerboard; others unaffected |

---

## V3-03 · Flood-Fill Tolerance
**Masterplan ref:** 3.6

### What was added
- `/cb tolerance` — view your current flood-fill tolerance.
- `/cb tolerance <10–80>` — set your personal tolerance (controls how aggressively the Color Triangle recolors).
- `/cb tolerance reset` — revert to the server default.

### Setup
```
/cb tolerance
```

### Tests

| # | What To Do | Where | Expected |
|---|-----------|-------|----------|
| 1 | Run `/cb tolerance` | Chat | `Flood-fill tolerance: 35 (default) — range 10–80. Set with /cb tolerance <value>.` |
| 2 | Run `/cb tolerance 10` | Chat | `Tolerance set to 10. Range: 10–80.` |
| 3 | Run `/cb tolerance` again | Chat | Shows `10 (custom)` |
| 4 | Right-click a block with the Color Triangle | World | Recolor uses the narrower tolerance (less color bleed) |
| 5 | Run `/cb tolerance 80` then right-click same block | World | More aggressive recolor (wider tolerance) |
| 6 | Run `/cb tolerance reset` | Chat | `Tolerance reset to default (35).` |
| 7 | Run `/cb tolerance` | Chat | Shows `35 (default)` again |

### Edge Cases

| # | Edge | Expected |
|---|------|----------|
| E1 | Run `/cb tolerance 9` (below min) | Chat | Command rejected — argument out of range |
| E2 | Run `/cb tolerance 81` (above max) | Chat | Command rejected — argument out of range |
| E3 | Two players set different tolerances | Each player | Each player's triangle uses their own value |

---

## V3-04 · Color Square Fallback (No Auto-Create)
**Masterplan ref:** Appendix 1.28

### What was added
When you use a Color Square on a block and the exact color variant doesn't exist, the square now falls back to the **base block** (name without color suffix) instead of doing nothing or auto-creating. If neither target nor base exists, a helpful error is shown — including a specific message when you use a plain-color square on a hex-colored block.

### Setup
Have a custom block with a color in its name, e.g. `marble_black`. Make sure `marble_yellow` does **not** exist.

### Tests

| # | What To Do | Where | Expected |
|---|-----------|-------|----------|
| 1 | Hold a Yellow Square and right-click `marble_black` (when `marble_yellow` exists) | World | Block swaps to `marble_yellow` |
| 2 | Right-click `marble_black` when `marble` (no suffix) exists but `marble_yellow` doesn't | World | Block swaps to the base `marble` block |
| 3 | Right-click a block whose ID ends in `_hex_FF0004` while holding a Black Square | Chat | `No Black variant exists for this hex block. Use /cb customcolor square <hex> to get a matching color square.` |
| 4 | Right-click a block when both the color variant AND base don't exist | Chat | `No block found for '<targetId>'. Try a different color.` |
| 5 | Right-click a block that is already the square's color | Chat | `Already <color>.` |

### Edge Cases

| # | Edge | Expected |
|---|------|----------|
| E1 | Block ID has an alias color segment (e.g. `crimson`) | World | Alias resolved to family (red) → target built correctly |
| E2 | Block ID has no color segment at all | World | Target key appended as suffix, tried; fallback if not found |

---

## V3-05 · Custom Hex Item Icon
**Masterplan ref:** Appendix 1.31 (Fix 7)

### What was added
Custom hex Square and Triangle items obtained via `/cb customcolor square/triangle <hex>` now display their **real hex color** in the item icon. Previously all custom items showed light blue (#55CCFF) regardless of the chosen hex.

> Note: This fix requires a resource pack reload after the mod is deployed for the first time. If icons still look blue, run `/cb reload` and re-download the pack.

### Setup
```
/cb customcolor square FF0000
/cb customcolor square 00FF00
/cb customcolor square 0000FF
/cb customcolor triangle FFAA00
```

### Tests

| # | What To Do | Where | Expected |
|---|-----------|-------|----------|
| 1 | Obtain a red Square (#FF0000) and look at it in inventory | Inventory | Icon color is **red** |
| 2 | Obtain a green Square (#00FF00) | Inventory | Icon color is **green** |
| 3 | Obtain a blue Square (#0000FF) | Inventory | Icon color is **blue** |
| 4 | Obtain an orange Triangle (#FFAA00) | Inventory | Triangle icon is **orange** |
| 5 | Drop all four on the ground and look at them as dropped items | World | Each item entity shows its correct hex color |
| 6 | Put two differently-colored Squares in the same inventory row | Inventory | Both show distinct colors next to each other |
| 7 | Hold a custom Square in your main hand | Hotbar | Hotbar icon matches the hex color |

### Edge Cases

| # | Edge | Expected |
|---|------|----------|
| E1 | Built-in Black/Yellow/Green Squares (non-custom) | Inventory | No glint effect; icon shows the normal item texture |
| E2 | Custom square that has no NBT data (very old item) | Inventory | Falls back to light blue — no crash |

---

## V3-06 · Bulk Recolor Wizard
**Masterplan ref:** 3.8

### What was added
`/cb bulkrecolor` opens a GUI wizard that lets you recolor many blocks at once. Supports all 16 color families, their aliases, and custom hex values. Includes a preview step before applying.

### Setup
Have several blocks whose IDs share a common color segment (e.g. `stone_black`, `brick_black`, `tile_black`).

### Tests

| # | What To Do | Where | Expected |
|---|-----------|-------|----------|
| 1 | Run `/cb bulkrecolor` | Chat / GUI | Bulk Recolor wizard opens |
| 2 | In the wizard, choose a scope and target color "yellow", then Preview | GUI | `Preview only. Matched: <N>, Excluded: <M>, Invalid tokens: 0.` |
| 3 | Choose a scope where no blocks match | GUI | `No blocks matched scope '<scope>'.` |
| 4 | Apply the recolor (click Apply/Confirm in wizard) | GUI | `Bulk recolor complete. Created: <N>, already existed: <M>, skipped: <K>, excluded: <J>, invalid: 0.` |
| 5 | Check the recolored blocks in the world | World | Block textures updated to the new color variant |
| 6 | Run `/cb undo` after a bulk recolor | Chat | Each created variant can be individually undone |

### Edge Cases

| # | Edge | Expected |
|---|------|----------|
| E1 | Use an alias color name (e.g. "crimson" instead of "red") | GUI/Chat | Alias resolved; correct family used |
| E2 | Use a hex value as target color (e.g. #FF5500) | GUI/Chat | Hex-based variants created with `_hex_FF5500` suffix |
| E3 | Scope matches 0 blocks | GUI/Chat | `No blocks matched scope '<scope>'.` — nothing created |
| E4 | All target variants already exist | GUI/Chat | `Created: 0, already existed: <N>` — no duplicates |

---

## V3-07 · Join Stability (500+ Blocks)
**Masterplan ref:** Network stability fixes

### What was added
Players joining a server with a large number of custom blocks (500+) no longer disconnect during join. The resource pack and block registry loading was optimized to avoid timeout errors on join.

### Setup
Requires a server with 200+ custom blocks registered. If testing locally, import blocks via Cloud Vault or duplicating until you have enough.

### Tests

| # | What To Do | Where | Expected |
|---|-----------|-------|----------|
| 1 | Join the server fresh (no cached pack) | Client | Join completes — no disconnect, no timeout |
| 2 | Join again with the cached resource pack | Client | Join is faster; no disconnect |
| 3 | Have a second player join while the first is already in | Both clients | Both players connect successfully |
| 4 | Check the server log during join | Console | No `Connection reset` or `Timed out` lines for joining players |

### Edge Cases

| # | Edge | Expected |
|---|------|----------|
| E1 | Join with 500+ blocks and slow connection | Client | May take a moment to download pack, but does not disconnect |
| E2 | Rejoin immediately after leaving | Client | Clean rejoin — no lingering session error |

---

## Quick Report Shorthand

When reporting results, use this format:

```
V3-01 Color System: PASS / FAIL — <notes>
V3-02 Empty Slot:   PASS / FAIL — <notes>
V3-03 Tolerance:    PASS / FAIL — <notes>
V3-04 Square FB:    PASS / FAIL — <notes>
V3-05 Hex Icon:     PASS / FAIL — <notes>
V3-06 Bulk Recolor: PASS / FAIL — <notes>
V3-07 Join Stable:  PASS / FAIL — <notes>
```
