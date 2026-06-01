# CustomBlocks V2 Testing Guide
> Source: `CB_MASTERPLAN.md` — covers every phase A through T plus X quality gates.
> Last verified against codebase: 2026-05-16

---

## How to Use This Guide

Read each test row. Do what it says. Look where it says. If what you see matches Expected — move on. If not — report it using the format at the bottom.

**Reporting:** `A1: problem: describe what happened`
**Improvements:** `improve A1: what you want instead`

---

## Phase Overview

| Phase | Name | Status | Priority |
|-------|------|--------|----------|
| A | Critical Foundation Fixes | ✅ SHIPPED | **Test first** |
| B | Voice Modes, Did-You-Mean, Kill Vanilla Red | ✅/🔶 | **Test first** |
| C | Universal Resume (Drafts) | ✅ SHIPPED | High |
| D | Performance & Instant Click | ✅/🔶 | Medium |
| E | Bulk UI Fix (Visual Polish) | ✅/🔶 | High |
| F | Multi-Channel Feedback Layers | ✅ SHIPPED | High |
| G | Color Tools Expansion | 🔶 PARTIAL | Medium |
| H₁ | Tier-1 Block Features | ✅/⏳ | High |
| H₂ | Tier-2 Block Features | ✅/⏳ | High |
| I | Holograms System | 🔶 PARTIAL (config-only) | Medium |
| J | AI Block Generator | ⏳ NOT IMPLEMENTED | Skip |
| K | Stats Dashboard | 🔶 PARTIAL (data only) | Low |
| L | Marketplace | 🔶 PARTIAL | Low |
| M | Discord Webhooks | ✅ SHIPPED | Medium |
| N | Welcome Experience | ✅ SHIPPED | Medium |
| O | `/cb menu` Feature Gallery | ✅ SHIPPED | High |
| P | Macros (Power User) | ✅ SHIPPED | Medium |
| Q | Safety Nets | ✅ SHIPPED | **Test first** |
| R | Achievements | 🔶 PARTIAL (fires, no view) | Low |
| S | Developer Console | 🔶 PARTIAL | Low |
| T | Modrinth Release Polish | 🔶/⏳ | Low |
| X | Cross-Cutting Quality Gates | ✅/🔶 | After core |

---

## Start Here — 5 Quick Checks

Do these before anything else. If these fail, everything else is unreliable.

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb listgui` | GUI / Screen | Opens without crash or freeze |
| 2 | Click any block in listgui | Tooltip | Shows real block info — NOT `red_concrete, 8 component(s)` |
| 3 | Type `/cb create test_quick stone` | Chat | Confirmation message with exactly ONE `[CB]` prefix |
| 4 | Place the test block in the world, look at it | World | Correct texture — NOT purple/black checkerboard |
| 5 | Re-join the server, look at the same block | World | Texture still correct after rejoin |

> ⚠️ If test 4 or 5 fails, note which blocks are wrong and report: `start: missing texture: block X shows purple/black`

---

## Phase A — Critical Foundation Fixes ✅

---

### A1 — Bulk Pickers Render Real CustomBlock Textures

**Setup:** Have at least 5 custom blocks created before running these tests.

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb bulkblockadd` | GUI / Screen | Picker opens without crash |
| 2 | Look at each block icon in the picker | GUI slots | Every block shows its real texture — not magenta red_concrete |
| 3 | Hover any block slot | Tooltip | Shows: `§7Unique ID: §b<id>`, display name, shape, light level, hardness, sound type, slot index |
| 4 | Confirm tooltip does NOT contain | Tooltip | `minecraft:red_concrete` or `8 component(s)` are absent |
| 5 | Type `/cb bulkrecolor` | GUI / Screen | All blocks render with real textures here too |
| 6 | Click 3 different blocks to select them | GUI / Screen | Each selected block shows: enchantment glint + `[Selected]` lore + lime glass border on 8 surrounding slots |
| 7 | Navigate to next page (requires 10+ blocks) | GUI / Screen | Previously selected blocks stay selected (glint remains) |
| 8 | Navigate back to first page | GUI / Screen | All selections still visible |
| 9 | Type `/cb listgui`, compare textures | GUI / Screen | Icons in listgui match icons in bulkblockadd exactly |
| 🔊 | Select a block — listen | Sound | `BLOCK_AMETHYST_BLOCK_CHIME` plays |
| ✨ | Select a block — watch the block | Particles | `ENCHANT` particles burst from the selection |

**Report as:** `A1: problem: ___`

---

### A2 — Right-Click "Create New Category" Actually Adds the Block

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb create test_cat oak_log` | Chat | Block creation confirmed |
| 2 | Type `/cb listgui` → right-click `test_cat` | GUI / Screen | Assignment GUI opens with "Create new category" option |
| 3 | Click "Create new category" | GUI / Screen | Category creation flow opens |
| 4 | Enter: key `shine`, name `Shiny`, any icon, color `#FFD700`, badge `SHINY` | GUI / Screen | All inputs accepted without error |
| 5 | Confirm the creation | Chat | Single `[CB]` prefix; message says BOTH created AND added — e.g. `[CB] Created 'Shiny' and added §btest_cat§r ✔` |
| 6 | Type `/cb listgui` → open category `shine` | GUI / Screen | `test_cat` is present inside it |
| 7 | Repeat steps 2–4 but delete `test_cat` mid-flow before confirming | Chat | Graceful error message, no server crash |
| 🔊 | Complete the creation — listen | Sound | `ENTITY_EXPERIENCE_ORB_PICKUP` plays |
| ✨ | Complete the creation — watch screen | Particles | `COMPOSTER` 8-particle burst fires |

**Report as:** `A2: problem: ___`

---

### A3 — One `[CB]` Prefix Per Chat Line

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb listgui` → open any category → click search → press ESC | Chat | Exactly ONE `[CB]` prefix — e.g. `[CB] Search closed — nothing was searched.` |
| 2 | Look at the same message | Chat | A clickable `[Click to continue]` or resume link is present |
| 3 | Type `/cb create anycheckblock stone` | Chat | Confirmation has exactly ONE `[CB]` prefix |
| 4 | Type `/cb delete anycheckblock` | Chat | Confirmation has exactly ONE `[CB]` prefix |
| 5 | Type `/cb retexture <any id>` | Chat | Confirmation has exactly ONE `[CB]` prefix |
| 6 | Trigger a permission denied error (non-OP action) | Chat | Error has exactly ONE `[CB]` prefix |
| 7 | Run 5 different commands rapidly | Chat | Every single message — exactly one `[CB]`, never zero, never two |
| 🔊 | Press ESC to cancel — listen | Sound | `BLOCK_NOTE_BLOCK_BASS` plays |
| ✨ | Press ESC to cancel — watch | Particles | `SMOKE` 4-particle puff appears |

**Report as:** `A3: problem: ___`

---

### A4 — Real Favorites Backend

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Open block editor → click the star button | Chat + GUI | Star turns gold; `[CB] Favorited: <id>` appears in chat |
| 2 | Type `/cb favorite <id>` | Chat | Same result as clicking the star |
| 3 | Relog — open the same block's editor | GUI / Screen | Star is STILL gold — favorites persisted across relog |
| 4 | Type `/cb bulkrecolor` → select scope `favorites` | GUI / Screen | All favorited blocks appear in the list |
| 5 | Log in as a non-OP player, look for Favorites scope | GUI / Screen | Scope is hidden or inaccessible |
| 6 | Click the star again on a favorited block | GUI / Screen | Block removed from favorites |
| 7 | Type `/cb unfavorite <id>` | Chat | Same result as un-starring via GUI |
| 8 | Look at block icons in any picker | Tooltip | `GOLDEN_APPLE` icon = favorited, `APPLE` icon = not favorited |
| 🔊 | Toggle ON favorite — listen | Sound | `ENTITY_EXPERIENCE_ORB_PICKUP` plays |
| 🔊 | Toggle OFF favorite — listen | Sound | `BLOCK_AMETHYST_CLUSTER_STEP` plays |
| ✨ | Toggle ON — watch | Particles | `COMPOSTER` 8-particle burst |
| ✨ | Toggle OFF — watch | Particles | `ENCHANT` 4-particle burst |

**Report as:** `A4: problem: ___`

---

### A5 — Auto-Built `/cb help`

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb help` | Chat | All registered subcommands listed with their syntax |
| 2 | Find the `dupe` entry in the list | Chat | Shows correct `dupe` syntax |
| 3 | Scroll through every help entry | Chat | No missing commands, no stale removed commands |
| 4 | Click any help entry in chat | Chat Bar | Fills the chat input with that command's syntax |
| 5 | Navigate to the second page of help (if it exists) | Chat | Navigation buttons work correctly |
| 6 | Type `/cb help create` | Chat | Shows specific help text for the `create` subcommand only |

**Report as:** `A5: problem: ___`

---

### A6 — Permission Overhaul (LuckPerms + OP4 Fallback)

**Setup:** Have a non-OP player account and an OP4 admin account ready.

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | As non-OP: type `/cb delete <any id>` | Chat | Blocked — `[CB]`-prefixed permission denied message |
| 2 | As OP4 admin: type `/cb delete <any id>` | Chat | Succeeds |
| 3 | As non-OP: type `/cb create test_perm stone` | Chat | Blocked |
| 4 | As non-OP: type `/cb listgui` | GUI / Screen | Opens — this is use-level, allowed for everyone |
| 5 | First server start with v2 config | Chat | ONE-TIME message: `[CB] Permissions hardened in v2 — review /cb config → Permissions tab.` |
| 6a | As non-OP: type `/cb listgui` | Chat | Allowed (node: `customblocks.use`) |
| 6b | As non-OP: type `/cb create` | Chat | Blocked (node: `customblocks.create`) |
| 6c | As non-OP: type `/cb delete` | Chat | Blocked (node: `customblocks.delete`) |
| 6d | As non-OP: type `/cb bulkrecolor` | Chat | Blocked (node: `customblocks.bulk`) |
| 6e | As non-OP: type `/cb panic` | Chat | Blocked (node: `customblocks.panic`) |
| 6f | As non-OP: open block editor | GUI / Screen | Star/favorites button is hidden |
| 6g | As non-OP: type `/cb ai` | Chat | Blocked (node: `customblocks.ai`) |
| 7 | With LuckPerms: grant `customblocks.create` to non-OP | World | Non-OP can now create blocks — no restart needed |
| 🔊 | Trigger a permission denied — listen | Sound | `BLOCK_NOTE_BLOCK_BASS` plays |
| ✨ | Trigger a permission denied — watch | Particles | `SMOKE` 4-particle puff |

**Report as:** `A6: problem: ___`

---

## Phase B — Voice Modes, Did-You-Mean, Kill Vanilla Red ✅

---

### B1 — Rename `aiStyle` → `voiceMode` (Config Migration)

**Setup:** Requires a v1 config file containing `aiStyle = "Echo"`.

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Start server with old v1 config containing `aiStyle = "Echo"` | Console | No crash — server starts normally |
| 2 | Open `config/customblocks/config.json` after start | File | `voiceMode` key exists; `aiStyle` key is GONE |
| 3 | Check chat on that first start | Chat | ONE-TIME: `Voice mode migrated from aiStyle → voiceMode. Tweak in /cb menu → Settings.` |
| 4 | Confirm `"Echo"` mapped correctly | File | `voiceMode` value is `"royal"` |

**Report as:** `B1: problem: ___`

---

### B2 — Six Voice Modes

| # | Mode | What To Do | Channel | Expected |
|---|------|-----------|---------|----------|
| 1 | Friendly | `/cb config voiceMode friendly` → cancel a search | Chat | Casual, warm tone: `Search closed — nothing was searched.` |
| 2 | Professional | `/cb config voiceMode professional` → cancel a search | Chat | Formal tone: `Search aborted with no input.` |
| 3 | Royal | `/cb config voiceMode royal` → cancel a search | Chat | Dramatic: `By Thy will, the search has been dismissed.` |
| 4 | Minimal | `/cb config voiceMode minimal` → cancel a search | Chat | Brief: `Search closed.` |
| 5 | Arabic | `/cb config voiceMode arabic` → cancel a search | Chat | Arabic text: `تم إغلاق البحث بدون إدخال.` |
| 6 | Silly | `/cb config voiceMode silly` → cancel a search | Chat | Playful: `Got it, no spying today.` |
| 7 | All 6 modes | Perform 3 different actions in each mode | Chat | Every message reflects the correct voice tone consistently |
| 8 | Switch mid-session | Change from Royal to Friendly — no restart | Chat | Immediately uses Friendly tone on very next command |

**Report as:** `B2: problem: ___` or `B2 voice: wrong: ___`

---

### B3 — Hybrid Argument Strategy (Kill Vanilla Red)

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb setgow block 5` (intentional typo of `setglow`) | Chat | NO vanilla red error — voice-aware error or a Did-You-Mean suggestion |
| 2 | Type `/cb bulkrecolor` with no arguments | Chat | Voice-aware error explaining required args with an example |
| 3 | Type `/cb bulkblockadd extra_arg_1 extra_arg_2` | Chat | Voice-aware error, not a red crash message |
| 4 | Type `/cb totally_fake_command` | Chat | No vanilla red error — voice-aware unknown command message |
| 5 | Type `/cb create` with no arguments | Chat | Voice-aware message listing required args (`blockId`, `baseMaterial`) — not red |

**Report as:** `B3: problem: ___`

---

### B4 — Did-You-Mean (4 Modes)

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb setgow block 5` | Chat | Clickable suggestion appears: `[CB] Did you mean: /cb setglow {block} 5? Click to fill chat.` |
| 2 | Click the suggestion | Chat Bar | Input fills with `/cb setglow block 5` |
| 3 | Set `didYouMeanMode` to `off` → make any typo | Chat | No suggestion appears at all |
| 4 | Set to `strict` → type `/cb setgow` | Chat | Suggestion appears (1 edit distance: o→l) |
| 5 | Set to `strict` → type `/cb bulkrecollor` | Chat | No suggestion (too many edits for strict mode) |
| 6 | Set to `smart` (default, ≤3 edits) → type `/cb bulkrecollor` | Chat | Suggestion appears |
| 7 | Set to `genius` → make various typos | Chat | Smart suggestions + prefix matches + past-correction memory |

**Report as:** `B4: problem: ___`

---

### B5 — Voice Picker on Book Page 1 🔶

> ⚠️ PARTIAL — the welcome book exists but the interactive page-1 voice picker may not be functional yet. Test what is there.

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Use a fresh account and type `/cb` for the first time | Inventory | Welcome book auto-given |
| 2 | Type `/cb welcome` on an existing account | Inventory | Book given again |
| 3 | Open book and navigate to page 1 | Book | 6 lines visible, one per voice mode, each with a sample message |
| 4 | Click a voice mode line on page 1 | Chat | Voice mode sets to that mode immediately |
| 5 | Run any CB command after clicking | Chat | Output reflects the newly chosen voice tone |

**Report as:** `B5: problem: ___`

---

## Phase C — Universal Resume (Drafts) ✅

---

### C1 — DraftManager (Session-Only)

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Open `/cb bulkrecolor` → select 2 blocks → press ESC | Chat | Resume link appears; draft saved for this session |
| 2 | Click the resume link in chat | GUI / Screen | Wizard reopens at the EXACT step where you left off |
| 3 | Check the block selections | GUI / Screen | Both previously selected blocks are still selected |
| 4 | Disconnect from server and reconnect | Chat | No resume link — drafts are session-only, not persisted |
| 5 | Type `/cb bulkrecolor` after relog | GUI / Screen | Wizard starts fresh from step 1 |
| 🔊 | Click the resume link — listen | Sound | `BLOCK_AMETHYST_CLUSTER_STEP` plays |
| ✨ | Click the resume link — watch | Particles | `SOUL_FIRE_FLAME` 6-particle effect |

**Report as:** `C1: problem: ___`

---

### C2 — All Multi-Step Flows Are Wired

| # | Flow | What To Do | Channel | Expected |
|---|------|-----------|---------|----------|
| 1 | Anvil rename | Open rename prompt → press ESC mid-way | Chat | Resume link appears |
| 2 | Bulk Assign wizard | Enter wizard → reach step 2 → press ESC | Chat | Resume link; reopens at step 2 |
| 3 | Bulk Recolor wizard | Pick a color → press ESC | Chat | Resume link; reopens after the color step |
| 4 | Search within category | Start typing a search query → press ESC | Chat | Resume link |
| 5 | Block editor | Open editor → make a change → press ESC | Chat | Resume link |
| 6 | Marketplace upload | Start upload flow → press ESC mid-way | Chat | Resume link |

**Report as:** `C2: problem: ___` — include which flow broke

---

### C3 — `/cb resume` Command

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Open Bulk Recolor → select 3 blocks → reach step 2 → press ESC | Chat | Draft saved confirmation |
| 2 | Type `/cb resume` | GUI / Screen | Wizard reopens at step 2 with all 3 blocks still selected |
| 3 | Type `/cb resume` when no draft exists | Chat | Voice-aware "nothing to resume" message — no crash |
| 4 | Start two different flows, ESC both, then type `/cb resume` | GUI / Screen | The most recently interrupted flow opens |

**Report as:** `C3: problem: ___`

---

## Phase D — Performance & Instant Click ✅

---

### D1 — Texture Pre-Cache

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Hold a color tool, hover over a block for 1–2 seconds | World | Pre-cache begins loading nearby color variants in background |
| 2 | Right-click to apply a color | World | Texture applies instantly — no visible delay or freeze |

---

### D2 — Async Generation Pipeline

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Change a block's texture | World | Block updates visually right away (optimistic swap) |
| 2 | Watch action bar after the change | Action Bar | RP regen message appears shortly — no server freeze while it runs |

---

### D3 — Smart Skip

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Apply a texture change to a block | World | Block updates |
| 2 | Apply the exact same change again immediately | World | Completes in under a second — skips regen, no delay |

---

### D4 — Configurable Instant Slider

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb config` → find `instantClickAggressivenessMs` | GUI / Screen | Value visible between 0–10000; default is 300 |
| 2 | Set to `0` | World | Maximum speed — texture applies instantly on click |
| 3 | Set to `10000` | World | Noticeable delay before texture applies |
| 4 | Set back to `300` | World | Normal behavior restored |

---

### D5–D11 — Observe While Doing Other Tests

| # | What To Watch For | Channel | Expected |
|---|------------------|---------|----------|
| D5 | Open listgui with 50+ blocks | GUI / Screen | Only the current page loads immediately — pages appear as you navigate |
| D6 | Jump to page 5 with 200+ blocks | GUI / Screen | No full inventory flicker — only changed slots update |
| D7 | Open listgui with 500+ blocks | GUI / Screen | Opens in under 2 seconds |
| D8 | Check the server `config/customblocks/` folder | File | `blocks.json.gz` exists (compressed format, not plain JSON) |
| D9 | Make 10 rapid block edits in 5 seconds | File | Only 1–2 save operations fire to disk (not 10 individual saves) |
| D10 | Restart server with 100+ blocks | Console | Server becomes available quickly — no long freeze on startup |
| D11 | Rapidly generate 20 color variants | Console | No `OutOfMemoryError` in server log |

**Report as:** `D5: weird: ___` or `D11: crash: ___` etc.

---

## Phase E — Bulk UI Fix (Visual Polish) ✅/🔶

---

### E1 — Selection Visual

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb bulkrecolor` | GUI / Screen | Picker opens |
| 2 | Click any block to select it | GUI / Screen | **All 3 indicators appear simultaneously:** enchantment glint on the item icon, `[Selected]` lore on hover, lime glass pane border on all 8 surrounding slots |
| 3 | Click a second block | GUI / Screen | Both blocks show all 3 indicators |
| 4 | Click a selected block again | GUI / Screen | Deselects — all 3 indicators removed immediately |
| 5 | Select blocks on page 1, navigate to page 2, navigate back | GUI / Screen | Page 1 selections still show all 3 indicators on return |
| 🔊 | Select any block — listen | Sound | `BLOCK_AMETHYST_BLOCK_CHIME` plays |
| ✨ | Select any block — watch | Particles | `ENCHANT` particles burst |

**Report as:** `E1: problem: ___`

---

### E2 — Bulk Recolor Wizard §0.6 Compliance 🔶

| # | Rule | Where to Check | Expected |
|---|------|---------------|----------|
| 1 | Header banner | Rows 0–8 on each wizard screen | Tab icons visible across all steps; current step tab has glint |
| 2 | Content rows | Rows 9–35 | Only actual content here — no action buttons mixed into content area |
| 3 | Footer rows | Rows 45–53 | Only Back / Forward (dimmed if not ready) / Confirm buttons |
| 4 | Clickable item count | Any single wizard screen | ≤18 clickable items visible at one time |
| 5 | Slot lore | Hover any interactive slot | Lore explains: left-click, right-click, shift-click actions |
| 6 | Tab click | Click between wizard tabs | `BLOCK_AMETHYST_BLOCK_CHIME` sound + `ENCHANT` particles |
| 7 | Home button | Every wizard screen | Present in footer; clicking returns to `/cb menu` |

**Report as:** `E2: problem: ___` — include which rule number

---

## Phase F — Multi-Channel Feedback Layers ✅

---

### F1 — Action Bar

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Open block editor → click the lock button | Action Bar | Brief confirmation appears ABOVE THE HOTBAR — not just in chat |
| 2 | Click the star button on any block | Action Bar | Favorite confirmation on action bar |
| 3 | Apply a quick color change using a color tool | Action Bar | Shows `Applied color: #RRGGBB` or similar |

---

### F2 — Boss Bar

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Start `/cb bulkrecolor` on 10+ blocks and confirm | Bossbar | A progress bar appears at top of screen: e.g. `Processing... 3/10` |
| 2 | Wait for operation to finish | Bossbar | Boss bar disappears automatically |
| 3 | Start a bulk operation, then cancel it mid-way | Bossbar | Boss bar disappears on cancel |

---

### F3 — Title Screen

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Complete a bulk recolor of 10+ blocks | Title | Large title flashes on screen: `Done! 10 blocks recolored` or similar |
| 2 | Earn an achievement (see Phase R for how) | Title | `§6§l🏆 Achievement Unlocked!` as title, achievement name as subtitle |

---

### F4 — Sounds (Per-Category Toggle)

| # | Category | What To Do | Channel | Expected |
|---|----------|-----------|---------|----------|
| 1 | Success | `/cb config` → soundsEnabled → disable **Success** → trigger any success action | Sound | Completely silent — no confirmation beep |
| 2 | Error | Disable **Error** → trigger permission denied or invalid command | Sound | Silent |
| 3 | GUI | Disable **GUI** → open and close any GUI | Sound | Silent on open and close |
| 4 | Selection | Disable **Selection** → select a block in bulk picker | Sound | Silent |
| 5 | BulkComplete | Disable **BulkComplete** → finish a bulk operation | Sound | Silent |
| 6 | All categories | Re-enable all → trigger each type | Sound | Sounds play correctly for every category |
| 7 | General sweep | Use GUIs normally with all categories on | Sound | No randomly silent actions — every button, save, and completion has a sound |

> ⚠️ Some sounds may still be silent for unknown reasons. If you find one, note the exact action.
**Report as:** `F4: wrong: <which action had no sound>`

---

### F5 — Particles (Per-Category Toggle)

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | `/cb config` → particlesEnabled → disable each category one at a time → trigger that action | Particles | No particles for the disabled category |
| 2 | Re-enable all categories | Particles | All particle effects fire correctly |

---

## Phase G — Color Tools Expansion 🔶

**Setup:** Have a custom block placed in the world. Have the relevant tool item in your inventory.

---

### G1 — Color Square & Color Triangle

| # | Tool | What To Do | Channel | Expected |
|---|------|-----------|---------|----------|
| 1 | Color Square (Black) | Right-click a custom block | World | Block swaps to the black color variant (must already exist) |
| 2 | Color Square (Yellow) | Right-click a block | World | Block swaps to yellow variant |
| 3 | Color Square (Custom hex) | Shift-right-click to set a hex color → right-click a block | World | Block swaps to the matching hex color variant |
| 4 | Color Triangle (Black) | Right-click a block | World | CREATES a new black recolored variant of the block |
| 5 | Color Triangle (Yellow) | Right-click a block | World | Creates new yellow variant |
| 6 | Color Triangle (Custom hex) | Set hex → right-click | World | Creates new variant with that hex background color |
| 7 | Diamond Triangle | Shift-click to set tolerance → right-click a block | World | Creates variant with tolerance-controlled background replacement |

---

### G2 — Rectangle Tool (Face Painter)

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Hold Rectangle Tool → right-click a custom block | GUI / Screen | Face paint GUI opens |
| 2 | Click a face (top / bottom / north / south / east / west) | GUI / Screen | Selected face highlights |
| 3 | Enter an image URL for that face | World | That face's texture updates to the URL image |
| 4 | Apply different image URLs to 3 different faces | World | Each face shows its own distinct texture |

---

### G3 — Golden Hexagon (UV Rotate / Flip)

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Hold Golden Hexagon → right-click a custom block | GUI / Screen | Face rotation GUI opens |
| 2 | Select a face → click rotate 90° | World | That face's texture rotates 90° |
| 3 | Click flip horizontal | World | Face texture flips horizontally |
| 4 | Check the resource pack files | File | Model JSON for the block reflects the rotation and flip values |

---

### G4 — Lumina Brush & Amethyst Chisel

| # | Tool | What To Do | Channel | Expected |
|---|------|-----------|---------|----------|
| 1 | Lumina Brush | Right-click a custom block | GUI / Screen | Property picker opens — shows light level, hardness, collision options |
| 2 | Lumina Brush | Change light level to 10 → apply | World | Block glows at level 10 |
| 3 | Amethyst Chisel | Right-click a custom block | GUI / Screen | Shape editor opens |
| 4 | Amethyst Chisel | Select "slab" preset → apply | World | Block shape becomes a slab |

**Report as:** `G1: problem: ___` through `G4: problem: ___`

---

## Phase H₁ — Tier-1 Block Features ✅/⏳

---

### H₁ — Per-Block Lock

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Block editor → click the lock icon button | GUI / Screen | Block locked; padlock icon visible on the block's slot |
| 2 | Type `/cb delete <locked-id>` | Chat | Blocked — voice-aware message mentioning the block is locked |
| 3 | Run `/cb bulkdelete` with a locked block in scope | Chat | Locked block SKIPPED; all other blocks in scope proceed |
| 4 | Type `/cb lock <id>` from chat directly | Chat | Same result as clicking the GUI lock button |
| 5 | Type `/cb unlock <id>` | Chat | Block unlocked; `/cb delete` now succeeds |
| 6 | Hover a locked block in any picker | Tooltip | Padlock icon visible in tooltip |
| 7 | Run `/cb bulkrecolor` with locked block in scope | Chat | Locked block skipped with a note in the result message |
| 🔊 | Lock a block — listen | Sound | `BLOCK_ANVIL_USE` plays |
| 🔊 | Unlock a block — listen | Sound | `BLOCK_AMETHYST_BLOCK_CHIME` plays |
| ✨ | Lock a block — watch | Particles | `ENCHANT` 6-particle burst |
| ✨ | Unlock a block — watch | Particles | `ENCHANT` 4-particle burst |

**Report as:** `H₁ lock: problem: ___`

---

### H₁ — Favorites

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Block editor → click the star button | GUI / Screen | Star turns gold |
| 2 | Press `F` keybind while hovering a block in listgui | GUI / Screen | Toggles favorite state |
| 3 | Type `/cb favorite <id>` and `/cb unfavorite <id>` | Chat | Both commands work as expected |
| 4 | Look at block icons in any picker | Tooltip | `GOLDEN_APPLE` = favorited, `APPLE` = not favorited |
| 5 | Log in as a non-OP, look for Favorites scope in bulk pickers | GUI / Screen | Hidden — no star button visible |

**Report as:** `H₁ favorites: problem: ___`

---

### H₁ — Block Notes

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Block editor → click Block Notes → type a note in the anvil | GUI / Screen | Input accepted |
| 2 | Close and hover the block's icon | Tooltip | Note appears in the tooltip with a voice-mode-aware header |
| 3 | Type exactly 256 characters in the note | Tooltip | Accepted and saved |
| 4 | Type 257 characters | Chat / GUI | Either capped at 256 silently or rejected with a message |
| 5 | Relog — hover the block again | Tooltip | Note persisted across relog |

**Report as:** `H₁ notes: problem: ___`

---

### H₁ — Advanced Search Filters

| # | Search Query | Channel | Expected |
|---|-------------|---------|----------|
| 1 | `tag:foo` | GUI / Screen | Only blocks tagged `foo` appear |
| 2 | `base:oak_log` | GUI / Screen | Only blocks using `oak_log` as base material |
| 3 | `color:green` | GUI / Screen | Only green-dominant blocks |
| 4 | `hardness:>3` | GUI / Screen | Only blocks with hardness above 3 |
| 5 | `tag:nonexistent` | GUI / Screen | Empty result — no crash |
| 6 | `base:stone color:gray` | GUI / Screen | Results filtered by BOTH conditions simultaneously |

**Report as:** `H₁ search: problem: ___`

---

### H₁ — `/cb history` Dashboard

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb history` | GUI / Screen | GUI opens showing a list of recent block actions |
| 2 | Look at the rows | GUI / Screen | Each row shows: action type, block ID, timestamp, who performed it |
| 3 | Click a history row to undo | World | Reverts to that specific point in history |
| 4 | Check GUI layout | GUI / Screen | Header rows 0–8, content rows 9–35, footer 45–53; action buttons use legendary items |

**Report as:** `H₁ history: problem: ___`

---

### H₁ — Unlimited Undo

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Make 50 sequential edits to different blocks | World | All 50 are still individually undoable |
| 2 | Type `/cb config` → find `maxUndoDepth` | GUI / Screen | Default value is `10000` (was `20` in v1) |
| 3 | Type `/cb config undoDepth unlimited` | Chat | Sets depth to `100000` |
| 4 | Undo all 50 edits one by one | World | Each undo reverts the correct action in reverse order |

**Report as:** `H₁ undo: problem: ___`

---

### H₁ — Export PNG

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb exportpng <id>` | Chat | Confirmation that file was written |
| 2 | Navigate to `.minecraft/customblocks-exports/<id>.png` | File | File exists and opens correctly in any image viewer |
| 3 | Export an animated (GIF-based) block | File | Exports the first frame as a flat PNG |

**Report as:** `H₁ exportpng: problem: ___`

---

### H₁ — Not Yet Implemented (Skip All)

> Custom Drops, Custom Sounds, Live 3D Preview, Showcase Platform, Mass Rename, Large Font, Chat Thumbnails, Block Creator Credit, Gallery — **Status: NOT YET IMPLEMENTED. Mark `—` for all.**

---

## Phase H₂ — Tier-2 Block Features ✅/⏳

---

### H₂ — Atomic Confirm Threshold (≥5 Blocks)

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Select exactly 5 blocks → run bulk delete | GUI / Screen | Confirm GUI appears showing: count (5), scope, 5 sample block IDs |
| 2 | Click Confirm in the GUI | World | Operation proceeds |
| 3 | Select exactly 4 blocks → run bulk delete | World | No confirm GUI — proceeds directly without confirmation |
| 4 | When Confirm GUI appears → click Cancel | World | Nothing happens — blocks untouched |
| 5 | When Confirm GUI appears → press ESC | World | Same as Cancel — blocks untouched |
| 🔊 | Confirm GUI appears — listen | Sound | `BLOCK_NOTE_BLOCK_BASS` plays |
| ✨ | Confirm GUI appears — watch | Particles | `LARGE_SMOKE` 12-particle effect |

**Report as:** `H₂ confirm: problem: ___`

---

### H₂ — Safe-Delete Window (15s)

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb delete <id>` | Chat | `[CB] Deleted <id>. [Undo (15s)]` with a clickable undo link |
| 2 | Click the undo link within 15 seconds | World | Block fully restored to previous state |
| 3 | Verify the restored block | World | Same texture, name, and category as before deletion |
| 4 | Delete another block, wait more than 15 seconds | Chat | Undo link is gone; block stays deleted permanently |
| 5 | Delete a block, do other actions, click undo within 15s | World | Still works even with other commands in between |

**Report as:** `H₂ safedelete: problem: ___`

---

### H₂ — Auto-Snapshots

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Reduce `autoSnapshotMinutes` to 1 in config → wait 1 minute | File | New file appears in `config/customblocks/snapshots/<timestamp>.json.gz` |
| 2 | Generate 21 snapshots total | File | Oldest snapshot deleted automatically; only 20 most recent kept |
| 3 | Open any snapshot file manually | File | Valid JSON — readable, not corrupted |

**Report as:** `H₂ snapshots: problem: ___`

---

### H₂ — Pre-Op Snapshots

> ⚠️ Verify this BEFORE running any destructive operations.

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Check `snapshots/` folder, then run `/cb bulkdelete`, then check again | File | A snapshot tagged `pre_op_bulkdelete` appeared BEFORE any blocks were deleted |
| 2 | Check folder, run `/cb bulkrecolor`, check again | File | Snapshot tagged `pre_op_bulkrecolor` appeared first |
| 3 | Check folder, run `/cb panic confirm`, check again | File | Snapshot tagged `pre_op_panic` appeared first |

**Report as:** `H₂ pre-op: problem: ___`

---

### H₂ — `/cb recover`

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb recover` | GUI / Screen | GUI opens listing all available snapshots |
| 2 | Look at each snapshot row | GUI / Screen | Shows: date, time, snapshot file size, reason/tag |
| 3 | Click a snapshot row | GUI / Screen | Confirm dialog appears — cannot accidentally restore |
| 4 | Click Confirm | World | Blocks restored to the state at snapshot time |
| 5 | Check restored blocks in-world | World | Match the state from when that snapshot was taken |
| 6 | Open confirm dialog → close it without confirming | World | Nothing changes |

**Report as:** `H₂ recover: problem: ___`

---

### H₂ — `/cb panic`

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb panic` | Chat | `[CB] PANIC REQUESTED. Type /cb panic confirm within 30s...` |
| 2 | Look at the title screen immediately | Title | `§c§l⚠ PANIC ARMED` as title, `§f/cb panic confirm §7to execute` as subtitle |
| 3 | Type `/cb panic confirm` within 30 seconds | World | Rollback executes; blocks restored to last good state |
| 4 | Check snapshots folder | File | `pre_op_panic` snapshot exists and was created BEFORE the rollback |
| 5 | Type `/cb panic` then wait 30+ seconds without confirming | Chat | Panic auto-cancels — voice-aware cancellation message |
| 6 | As non-OP: type `/cb panic` | Chat | Blocked — requires `customblocks.panic` node |
| 🔊 | Panic arm — listen | Sound | `BLOCK_NOTE_BLOCK_BASS` at 1.0f / 0.5f |
| ✨ | Panic arm — watch | Particles | `LARGE_SMOKE` 12-particle effect |

**Report as:** `H₂ panic: problem: ___`

---

### H₂ — Hover Preview (Not Implemented)

> **Status: NOT YET IMPLEMENTED — skip. Mark `—`.**

---

## Phase I — Holograms System 🔶

> **Status:** Code is fully implemented. Disabled by default.
> **To enable for testing:** Edit `config/customblocks/config.json` → set `"hologramEnabled": true` → restart server.

---

### I1 — Hologram Appears on Block Placement

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Set `hologramEnabled` to `true` in config → restart | Console | Server starts without error; holograms now active |
| 2 | Place any custom block in the world | World | A floating text label appears ~1.5 blocks above the block showing its display name |
| 3 | Look at the label text | World | Yellow bold text: `§e§l<DisplayName>` |
| 4 | Break the block | World | Label disappears immediately |
| 5 | Place the same block again | World | Label reappears |

---

### I2 — Hologram Config Options

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Set `hologramHeight` to `2.5` → restart → place a block | World | Label floats 2.5 blocks above the block (noticeably higher) |
| 2 | Set `hologramColor` to `"§c§l"` → restart → place a block | World | Label is now red and bold |
| 3 | Rename a block with `/cb rename <id> <new name>` | World | Hologram label on already-placed blocks updates to the new name |
| 4 | Set `hologramEnabled` to `false` → restart | World | All holograms disappear; newly placed blocks have no label |

---

### I3 — Persistence Across Restart

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Place a block with holograms ON → restart server | World | Hologram is still visible after restart (ArmorStands persist in world save) |
| 2 | Disable holograms in config → restart | World | Existing hologram ArmorStands are still in the world — they are NOT cleaned up on disable |
| 3 | Re-enable holograms → restart | World | New placements get labels; old placements that lost their labels need to be broken and replaced |

> ⚠️ **Known limitation:** No `/cb hologram` toggle command exists. Config file edit + restart required every time.

**Report as:** `I1: problem: ___` through `I3: problem: ___`

---

## Phase J — AI Block Generator ⏳

> **Status: NOT YET IMPLEMENTED.** No `/cb ai` command exists. No source code for this phase. Skip all — mark `—`.

---

## Phase K — Stats Dashboard 🔶

> **Status:** Data fully tracked and saved. No in-game GUI or command to view it yet.

---

### K1 — Placement Tracking (Data Layer)

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Place any custom block → restart → open `config/customblocks/placement_stats.json.gz` | File | File exists and contains placement count data |
| 2 | Place the same block 5 more times | File | Count for that block increased by 5 after restart |
| 3 | Place blocks as two different player accounts | File | Per-player counts tracked separately under each UUID |

> ⚠️ **Missing:** No `/cb stats` command or GUI exists yet. The data is there — it just cannot be viewed in-game.

**Report as:** `K1: problem: ___`

---

## Phase L — Marketplace 🔶

---

### L1 — Marketplace GUI

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb market` | GUI / Screen | Marketplace GUI opens |
| 2 | Browse the block entries | GUI / Screen | Block listings visible if any have been uploaded to the cloud |
| 3 | Click the next page button | GUI / Screen | Next page of results loads (`?cursor=` pagination works) |
| 4 | Hover a block entry | Tooltip | Shows: block name, block ID, creator, upload date |
| 5 | Click a block to download it | World | Block downloaded and added to this server's block list |

---

### L2 — Upload to Marketplace

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Block editor → click Share/Upload button | GUI / Screen | Upload flow starts |
| 2 | Confirm the upload | Chat | Block sent to cloud vault; share code returned in chat |
| 3 | Look at the share code format | Chat | Starts with `CB~` followed by a 12-character code |
| 4 | On a different server: type `/cb importblock CB~<code>` | World | Block downloads and installs on the new server |

**Report as:** `L1: problem: ___` or `L2: problem: ___`

---

## Phase M — Discord Webhooks ✅

> **Setup:** Open `config/customblocks/config.json` → set `"discordWebhookUrl"` to a real Discord webhook URL before these tests. Leave it empty to disable.

---

### M1 — Block Created Notification

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb create my_block stone` | Discord | Message appears: `🟩 **Block Created** by \`<your name>\`` on one line, then `ID: \`my_block\` · Name: \`<name>\` · Slot #<n>` |
| 2 | Watch the timing | Discord | Message arrives within ~5 seconds |
| 3 | Remove the webhook URL from config → create another block | Discord | No message fires — completely silent |

---

### M2 — Block Deleted Notification

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb delete my_block` | Discord | Message: `🟥 **Block Deleted** by \`<your name>\`` then `ID: \`my_block\` · Slot #<n>` |
| 2 | Bulk delete 3 blocks | Discord | 3 separate Discord messages arrive — one per block deleted |

---

### M3 — Panic Armed Notification

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb panic` | Discord | Message: `⚠️ **PANIC ARMED** by \`<your name>\`` with the server MOTD and `— awaiting /cb panic confirm` |
| 2 | Let the panic expire (wait 30s without confirming) | Discord | The ARMED message was already sent — no cancellation message sent |
| 3 | Type `/cb panic confirm` | Discord | Rollback executes in-game; Discord only ever received the ARMED message |

> ⚠️ Webhook fires on a background thread. If the server shuts down immediately after an event, the message may not reach Discord in time.

**Report as:** `M1: problem: ___` through `M3: problem: ___`

---

## Phase N — Welcome Experience ✅

**Setup:** Use a fresh player account that has never typed `/cb` on this server.

---

### N — First-Use Welcome

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb` for the very first time on a fresh account | Inventory | Welcome book auto-appears |
| 2 | Type `/cb welcome` on an existing account | Inventory | Book given again |
| 3 | Open book to page 1 | Book | 6 voice mode samples visible; clicking one sets that voice mode |
| 4 | Open book to page 2 | Book | "First block in 60s" quickstart guide |
| 5 | Open book to page 3 | Book | Tools cheat sheet showing every tool wand with its icon |
| 6 | Open book to page 4 | Book | Categories & tags explanation |
| 7 | Open book to page 5 | Book | Color tools deep dive |
| 8 | Open book to page 6 | Book | Bulk operations guide |
| 9 | Open book to page 9 as OP | Book | Admin chapter visible |
| 10 | Open book to page 9 as non-OP | Book | Admin chapter hidden — not visible to regular players |
| 11 | Check chat on very first `/cb` | Chat | `[CB] Welcome to CustomBlocks. Try /cb menu to see everything.` with clickable `[Open Menu]` `[Read Book]` `[Pick Voice]` buttons |

**Report as:** `N welcome: problem: ___`

---

## Phase O — `/cb menu` Feature Gallery ✅

---

### O — Feature Menu

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb menu` | GUI / Screen | Opens tabbed chest GUI |
| 2 | Count the tabs | GUI / Screen | Exactly 4 tabs: Tools / Bulk Ops / Resource Pack / Config |
| 3 | Check each tab | GUI / Screen | Visible search bar slot on every tab |
| 4 | Hover any feature button | Tooltip | Description tooltip explains what the feature does |
| 5 | Click any feature button | GUI / Screen | That feature launches and opens correctly |
| 6 | Check all button icons | GUI / Screen | All interactive slots use legendary items — no plain paper, arrows, or regular dyes |
| 7 | Count clickable items on one tab | GUI / Screen | ≤18 clickable items per screen |
| 8 | Check layout compliance | GUI / Screen | Rows 0–8 = header/tabs; rows 45–53 = footer navigation |

**Report as:** `O menu: problem: ___`

---

## Phase P — Macros ✅

> **Storage location:** `config/customblocks/macros/<name>.json`

---

### P1 — Record and Run a Macro

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb macro record daily_setup` | Chat | Confirmation that recording has started |
| 2 | While recording: run several `/cb` commands (rename, setglow, sethardness) | World | Each command executes normally during recording |
| 3 | Type `/cb macro stop` | Chat | Recording stops; confirmation shows how many commands were saved |
| 4 | Open `config/customblocks/macros/daily_setup.json` | File | File exists and contains a `"commands"` array with all recorded commands |
| 5 | Type `/cb macro run daily_setup` | World | All recorded commands execute in order on your behalf |
| 6 | Compare the result | World | All changes from the original recording are reproduced exactly |

---

### P2 — List, Show, Delete

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb macro list` | Chat | All saved macro names listed |
| 2 | Type `/cb macro show daily_setup` | Chat | Exact list of commands stored in that macro |
| 3 | Type `/cb macro delete daily_setup` | Chat | Macro deleted — no longer appears in list |
| 4 | Type `/cb macro run daily_setup` after deleting it | Chat | Voice-aware "not found" error message — no crash |
| 5 | Type `/cb macro list` when no macros exist | Chat | Voice-aware empty list message — no crash |

---

### P3 — Add to Macro

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb macro add daily_setup retexture my_block <url>` | Chat | Confirmation that command was appended to the macro |
| 2 | Type `/cb macro show daily_setup` | Chat | The new command appears at the end of the list |
| 3 | Type `/cb macro run daily_setup` | World | All original commands + the newly added command execute in order |
| 4 | Type `/cb macro add brand_new_macro retexture my_block <url>` (macro doesn't exist yet) | Chat | New macro created with this as its only command |

---

### P4 — Macro Safety

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Start recording with `/cb macro record setup` → immediately try to start ANOTHER recording | Chat | Voice-aware error: already recording |
| 2 | Record a macro containing an OP-only command → run it as a non-OP player | Chat | That command is skipped or fails gracefully — no server crash |
| 3 | Type `/cb macro record "my macro!"` (name with spaces and special chars) | File | Macro saved as `my_macro_.json` (lowercase, special chars replaced with `_`) |

**Report as:** `P1: problem: ___` through `P4: problem: ___`

---

## Phase Q — Safety Nets ✅

> ⚠️ Run Phase H₂ alongside this — H₂ covers the same features in more detail.

---

### Q — Full Safety Net Sweep

| # | What To Test | Channel | Expected |
|---|-------------|---------|----------|
| 1 | Set `autoSnapshotMinutes` to 1 → wait 1 minute | File | `snapshots/<timestamp>.json.gz` appears |
| 2 | Run `/cb bulkdelete` on 3 blocks | File | `pre_op_bulkdelete` snapshot was created BEFORE anything was deleted |
| 3 | Generate 21 snapshots manually | File | Only 20 are kept; the oldest was deleted automatically |
| 4 | Type `/cb recover` | GUI / Screen | GUI lists all snapshots; clicking → confirming → restores correctly |
| 5 | Type `/cb panic` → `/cb panic confirm` within 30s | World | Rollback executes |
| 6 | Type `/cb panic` → wait 30s without confirming | Chat | Panic auto-cancels with a voice-aware message |
| 7 | Delete a block → click `[Undo (15s)]` within 15 seconds | World | Block fully restored |
| 8 | Delete 5 blocks at once | GUI / Screen | Forced confirm GUI appears before any deletion begins |
| 9 | Confirm GUI appears → press ESC | World | Nothing deleted — zero blocks removed |

**Report as:** `Q recover: problem: ___` or `Q panic: problem: ___` etc.

---

## Phase R — Achievements 🔶

> **Status:** Fully tracked and notified. Persisted to `config/customblocks/achievements.json.gz`.
> ⚠️ No `/cb achievements` view command exists yet — watch for them as they fire, you cannot browse them in-game.

---

### R1 — Creation Milestones

| # | Achievement | How To Trigger | Channel | Expected |
|---|------------|---------------|---------|----------|
| 1 | `§6§l✦ First Creation!` | Create your very first custom block | Title + Action Bar | Full-screen title fires + action bar fires simultaneously |
| 2 | `§a§l✦ Block Collector` | Create your 10th custom block total | Title + Action Bar | Same notification pattern |
| 3 | `§b§l✦ Master Builder` | Create your 50th custom block total | Title + Action Bar | Same |
| 4 | `§d§l✦ Legendary Architect` | Create your 100th custom block total | Title + Action Bar | Same |
| 🔊 | Any achievement fires — listen | Sound | `UI_TOAST_CHALLENGE_COMPLETE` plays |

---

### R2 — Action Milestones

| # | Achievement | How To Trigger | Channel | Expected |
|---|------------|---------------|---------|----------|
| 1 | `§e§l✦ First Placement!` | Place any custom block in the world for the first time | Title + Action Bar | Title fires |
| 2 | `§c§l✦ Favourited!` | Star any block as a favourite for the first time | Title + Action Bar | Title fires |
| 3 | `§7§l✦ Time Traveller` | Type `/cb undo` for the very first time | Title + Action Bar | Title fires |
| 4 | `§3§l✦ Organised!` | Create your first block category | Title + Action Bar | Title fires |

---

### R3 — Persistence

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Unlock an achievement → relog → trigger it again | Title | Achievement does NOT fire a second time |
| 2 | Unlock an achievement → restart server → trigger again | Title | Still does not fire again — saved to disk |
| 3 | New player joins and triggers the same action | Title | Their achievement fires (per-player tracking — each player starts at zero) |
| 4 | Watch any achievement notification | Title | Title line: `§6§l🏆 Achievement Unlocked!` / Subtitle: achievement title + description in `§7` |

**Report as:** `R1: problem: ___` through `R3: problem: ___`

---

## Phase S — Developer Console 🔶

---

### S — Dev Console

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Press `Ctrl+Shift+D` | Screen | Dev console overlay opens (client-side only) |
| 2 | Press `Ctrl+Shift+D` without `customblocks.devconsole` permission | Chat | Blocked with a permission message |
| 3 | Navigate to **Logs** tab | Screen | Recent CustomBlocks log entries visible |
| 4 | Navigate to **Performance** tab | Screen | Timing stats and memory usage visible |
| 5 | Navigate to **Eval** tab | Screen | Can evaluate a simple CB command or expression |
| 6 | Navigate to **Inspect** tab → click a block in-world | Screen | Full slot data for that block displayed |
| 7 | Navigate to **Simulate** tab → run a simulated action | Screen | Preview shows expected result — no real changes made to any block |

**Report as:** `S console: problem: ___`

---

## Phase T — Modrinth Release Polish 🔶/⏳

---

### T1 — Translation Skeleton 🔶

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Open the mod jar → check `assets/customblocks/lang/` | File | `en_us.json` is present |
| 2 | Check same folder for Arabic | File | `ar_sa.json` is present and populated |
| 3 | Use any CB command in-game | Chat | No hardcoded English strings — all use translation keys |

---

### T2 — Auto-Changelog ⏳

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Push a release tag `v*.*.*` to GitHub | CI | Minotaur Gradle plugin generates a changelog from git log |
| 2 | Check Modrinth release page | Modrinth | Changelog appears in the release notes |

---

### T3 — Modrinth Update Check ⏳

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Start the mod fresh | Chat / Console | Checks Modrinth API once for available updates |
| 2 | Start again in the same server session | Console | Does NOT check again — once per session only |
| 3 | Simulate an update being available | Chat | Branded notification sent to admin only |
| 4 | Already on latest version | Chat | Silent — no message sent |

---

### T4 — Mod Menu + Cloth Config Integration 🔶

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Start server with Mod Menu installed | Menu | CustomBlocks appears in the installed mods list |
| 2 | Click the config button on CustomBlocks in Mod Menu | GUI / Screen | Cloth Config GUI opens showing all mod settings |

---

### T5 — README + In-Game Help Combo ⏳

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb help` | Chat | GitHub wiki link visible in the output |
| 2 | Compare help output to actual registered command tree | Chat | No drift — help matches exactly what is registered |

---

### T6 — Demo Blocks via Book Button ⏳

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | `/cb menu` → Welcome book → last page → "Try the sample pack" button | Book | Button exists and is clickable |
| 2 | Click the button | World | 25 showcase blocks installed on the server |
| 3 | Hover any sample block's icon | Tooltip | `Origin: Sample` tag visible in lore |
| 4 | Type `/cb bulkdelete --origin sample` | World | ALL sample blocks removed cleanly |
| 5 | Click the install button again on an existing install | World | No duplicate blocks created (idempotent) |

---

### T7 — Screenshot Mode ⏳

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Type `/cb screenshot <id>` | Screen | HUD hides; block auto-rotates 360° |
| 2 | Check output folder | File | PNG saved to `.minecraft/screenshots/customblocks/` |
| 3 | Open the saved file | File | Clean, bright, well-lit screenshot |

---

### T8 — Modrinth Metadata 🔶

| # | What To Check | Channel | Expected |
|---|--------------|---------|----------|
| 1 | Mod icon file | File | 256×256 pixels, ≤256 KiB |
| 2 | Modrinth gallery screenshots | Modrinth page | 8–12 screenshots showing key features |
| 3 | Modrinth tags | Modrinth page | Includes: `Utility`, `Decoration`, `Management`, `Game Mechanics` |

---

## Phase X — Cross-Cutting Quality Gates

---

### X1 — Config Field Migration ✅

| Field | What To Check | Expected |
|-------|--------------|----------|
| `maxSlots` | Present in v2 config | Kept; valid range `[1, 8192]` |
| `defaultTextureSize` | Present | Kept; valid range `[16, 256]` |
| `bgRemovalTolerance` | Present | Kept; valid range `[0, 100]` |
| `downloadTimeoutSeconds` | Present | Kept; valid range `[1, 120]` |
| `maxUndoDepth` | Present | **New default: 10000** (was 20); range `[1, 100000]` |
| `permissionLevelAdmin` | Present | **New default: 4** (was 2); one-time warning on first start |
| `aiStyle` | Absent | **Renamed → `voiceMode`**; `"Echo"` → `"royal"` |
| `joinDebounceMs` | Present | Kept |
| `texturePayloadsPerTick` | Present | Kept; range `[1, 50]` |
| `cloudShareEnabled` | Present | Kept |
| `hologramEnabled` | Present | **New** — default `false` |
| `hologramHeight` | Present | **New** — default `1.5` |
| `hologramColor` | Present | **New** — default `"§e§l"` |
| `discordWebhookUrl` | Present | **New** — default `""` (disabled) |

**Migration test:**

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Upgrade from a v1 config | Console | All fields migrate correctly; no crash on start |
| 2 | Check for backup file | File | `config.json.bak` written before migration ran |

---

### X2 — Build Environment ✅

| Component | Expected |
|-----------|----------|
| Java toolchain | `21` |
| Minecraft version | `1.21.1` |
| Yarn mappings | `1.21.1+build.3:v2` |
| Fabric Loader | `0.16.9` |
| Fabric API | `0.104.0+1.21.1` |
| Resource pack `pack_format` | `34` |

---

### X3 — Royal Directive §2 Compliance ✅

| # | GUI To Check | Channel | Expected |
|---|-------------|---------|----------|
| 1 | `/cb menu` main hub | GUI / Screen | ALL interactive slots use legendary items (Echo Shard, Nether Star, etc.) |
| 2 | Bulk picker headers and footers | GUI / Screen | All nav, confirm, cancel buttons use legendary items |
| 3 | Block editor tabs | GUI / Screen | All tab icons use legendary items |
| 4 | `/cb recover` GUI | GUI / Screen | All action buttons use legendary items |
| 5 | Any remaining standard items used as buttons | GUI / Screen | None found — zero violations |

---

### X4 — Sacred Systems Index ✅

| # | System | What To Do | Channel | Expected |
|---|--------|-----------|---------|----------|
| 1 | Sound sweep | Use all GUI types: clicks, saves, errors, bulk completes | Sound | Every single action produces a sound — none are silent |
| 2 | ChatHelper routing | Trigger 10 different admin actions | Chat | Every message has exactly ONE `[CB]` prefix |
| 3 | VoiceCatalog check | Run `./gradlew verifyVoiceCatalog` | Console | Passes — all 6 voice files complete and valid |
| 4 | §0.6 GUI layout | Open 5 different GUIs | GUI / Screen | Header rows 0–8, content rows 9–35, footer rows 45–53 consistent across all |
| 5 | Item palette | Open 5 different GUIs | GUI / Screen | All buttons are legendary items in every GUI |

---

### X5 — Voice Catalog Gate ✅

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Run `./gradlew verifyVoiceCatalog` | Console | Passes with no errors |
| 2 | Open all 6 voice JSON files | File | Every file contains every required key — none missing |
| 3 | Scan all voice entries | File | No raw `[CB]` literal appears in any entry |

---

### X6 — Crash Capture + `/cb diagnostics` ✅

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Trigger a recoverable error inside the mod | File | Incident report written to `config/customblocks/incidents/<UTC-ISO>.json` |
| 2 | Open the incident file | File | Contains: timestamp, phase/manager, exception class + 5 stack frames, last action, mod/MC/Fabric versions |
| 3 | Check what's NOT in the file | File | Does NOT contain any API keys, webhook URLs, or player chat content |
| 4 | Generate 51 incidents | File | Only 50 most recent kept — oldest rotated out automatically |
| 5 | Type `/cb diagnostics` as admin | File | Creates `config/customblocks/diagnostics-<UTC>.zip` containing incidents + last 500 log lines |

---

### X7 — CI / Release Pipeline 🔶

| Pipeline Step | Expected |
|--------------|----------|
| `./gradlew build` (JDK 21) | Passes on every push |
| `./gradlew verifyVoiceCatalog` | Hard-fails on any missing voice key |
| Mojibake sweep: `rg "Ã[¢‚€]\|â€\|Â§"` | Hard-fails on any hit in `src/` |
| Sound `.value()` lint | Hard-fails on bare NOTE_BLOCK references without `.value()` |
| Modrinth publish | Only triggers on signed tag push matching `v*.*.*` |
| Auto-changelog | Appears in Modrinth release notes on publish |

---

### X8 — Marketplace Policy Docs ⏳

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Check `cloud-vault-worker/TOS.md` | File | Contains: upload license, content rules, suspension policy |
| 2 | Check `cloud-vault-worker/PRIVACY.md` | File | Contains: UUID-only PII, no IP logging, data retention policy, deletion endpoint |
| 3 | Check `cloud-vault-worker/CONTENT_RULES.md` | File | Contains: no NSFW/illegal content, report endpoint, manual review for first 100 uploads |
| 4 | Open marketplace for first time in-game | GUI / Screen | One-time ToS accept page appears; after accept `marketplaceTosAccepted` = `true` in config |

---

### X9 — Sample Blocks Pack ⏳

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | First server start | File | `config/customblocks/samples/pack.json` created from the jar |
| 2 | Open `pack.json` | File | Valid JSON with `version` and `blocks[]` — each block has `id`, `displayName`, `category`, `textureSource`, `model`, `soundType`, `hardness`, `lightLevel` |
| 3 | Install sample blocks | World | Each sample block has `Origin: Sample` in its lore |
| 4 | Type `/cb bulkdelete --origin sample` | World | ALL sample blocks removed cleanly — none remain |
| 5 | Install sample blocks, then install again | World | No duplicates — install is idempotent |

---

## Known Bugs — Watch For These During All Testing

| Bug | What To Watch For | Likely Phase |
|-----|-------------------|-------------|
| **Missing textures after relog** | Random blocks show purple/black checkerboard after joining or relogging | Any texture test |
| **Random disconnections** | "Internal Exception: ConcurrentModificationException" kicks you or a friend mid-session | Any multiplayer test |
| **Block shows fixed but isn't** | Admin uploads new texture, mod confirms, but block still looks wrong in world | H₁, texture tests |
| **Silent GUI sounds** | Some GUI actions produce no sound — root cause under investigation | F4, any GUI test |
| **Creative tab icon missing** | Creative tab shows default icon, not your custom block's texture | First server join |
| **Holograms need restart to toggle** | No in-game command — requires config edit + full server restart | Phase I |
| **No achievements view command** | Achievements fire and save but cannot be browsed or listed in-game | Phase R |
| **No stats dashboard** | Placement data is tracked on disk but `/cb stats` command does not exist yet | Phase K |

---

## §0.2 Sensory Quick-Reference

| Trigger | Particles | Sound |
|---------|-----------|-------|
| Button click (any GUI) | `ENCHANT` 6 | `BLOCK_AMETHYST_BLOCK_CHIME` 0.6f / 1.25f |
| Successful action | `COMPOSTER` 12 burst | `ENTITY_EXPERIENCE_ORB_PICKUP` 0.8f / 1.0f |
| Tool usage on block | `SOUL_FIRE_FLAME` 4 trail | `BLOCK_NOTE_BLOCK_CHIME.value()` 0.85f / 1.0f |
| Error / warning | `SMOKE` 4 puff | `BLOCK_NOTE_BLOCK_BASS.value()` 1.0f / 0.7f |
| Bulk apply complete | `FIREWORK` 24 + title | `UI_TOAST_CHALLENGE_COMPLETE` 1.0f / 1.0f |
| Hologram spawn | `END_ROD` 8 + `GLOW` 4 | `BLOCK_AMETHYST_BLOCK_RESONATE` 0.7f / 1.0f |
| Achievement earned | `TOTEM_OF_UNDYING` cinematic | `UI_TOAST_CHALLENGE_COMPLETE` 1.0f / 1.2f |
| Dangerous op (panic, bulk delete) | `LARGE_SMOKE` 12 | `BLOCK_NOTE_BLOCK_BASS.value()` 1.0f / 0.5f |
| Resume draft | `SOUL_FIRE_FLAME` 6 | `BLOCK_AMETHYST_CLUSTER_STEP` 0.5f / 1.1f |
| Snapshot / save | `ENCHANT` 4 | `ENTITY_PLAYER_LEVELUP` 0.6f / 1.4f |
| Color picked | `GLOW` 4 | `BLOCK_AMETHYST_BLOCK_CHIME` 0.5f / 1.4f |
| Webhook fired (admin HUD) | none in world | `BLOCK_NOTE_BLOCK_HAT` 0.3f / 2.0f |

---

## Definition of Done — For Every Phase

A phase passes only when all three checks pass:

| Check | What It Means |
|-------|---------------|
| **Friend Test** | A friend joins with zero guidance, uses the feature — it works, no disconnects, no visual glitches, no crashes |
| **Liquid UI Test** | Multi-step flows transition smoothly, selections persist across pages, no inventory flicker |
| **WOW Test** | Sounds play, particles fire, all buttons are legendary items, GUIs have correct header/content/footer zones |

---

## How to Report Findings

```
A1: problem: still shows red_concrete
A2: works fine
H₁ lock: crash: server crashed when locking a block
B2 voice: wrong: switching to Arabic changed nothing
Q recover: missing: GUI doesn't open at all
M1: problem: Discord message never arrived
P1: problem: macro run executed nothing
R1: problem: achievement never fired on first block creation
I1: problem: hologram appeared but label was blank
bug: missing texture after join: blocks obsidian_smooth and marble_dark
```

**Format:** `[phase code]: [label]: [one sentence description]`

**Labels:** `problem` · `works fine` · `crash` · `wrong` · `missing` · `weird` · `bug`

Add extra detail on the next line if needed — no special format required.

---

## How to Request Improvements

```
improve Q recover: also show who made the snapshot and when
change H₁ notes: raise the character limit from 256 to 500
upgrade A1: also show the block's category in the tooltip
remove H₁ gallery: don't want this section at all
add to B4: test suggestions while in Royal voice mode
rewrite Phase I holograms: want a /cb hologram toggle command instead of config edit
add to Phase R: want a /cb achievements command to browse all unlocked ones
add to Phase K: want a /cb stats GUI showing top 10 most placed blocks
```

**Format:** `[action word] [phase code]: [what you want]`

**Action words:** `improve` · `change` · `upgrade` · `remove` · `add to` · `rewrite`
