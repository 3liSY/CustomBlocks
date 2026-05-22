# CustomBlocks Mod — Complete Testing Guide

## How to Use

Open this guide alongside your Minecraft server. Run each command exactly as written, then check the Expected column — if what you see differs from what is listed, open a bug report using the shorthand at the bottom of each phase.

---

## Phase Overview

| Phase | Name | Status | Priority |
|-------|------|--------|----------|
| Ph01 | Block Creation & Core Commands | Implemented | Critical |
| Ph02 | Block Properties & Texture | Implemented | Critical |
| Ph03 | GUI — Main, Picker, Editor | Implemented | Critical |
| Ph04 | GUI — Face Editor & Face Change | Implemented | High |
| Ph05 | GUI — Shape Editor & Collision | Implemented | High |
| Ph06 | GUI — Animation (Anim GUI) | Implemented | High |
| Ph07 | GUI — Categories | Implemented | High |
| Ph08 | GUI — Bulk Operations | Implemented | High |
| Ph09 | GUI — Background Studio | Implemented | Medium |
| Ph10 | GUI — Resource Center (RP) | Implemented | Medium |
| Ph11 | Undo / Redo System | Implemented | Critical |
| Ph12 | Favorites & Locks | Implemented | Medium |
| Ph13 | Macro & Script Systems | Implemented | Medium |
| Ph14 | Snapshot / Backup / Panic / Recover | Implemented | High |
| Ph15 | Cloud Share & Import | Implemented | Medium |
| Ph16 | Achievement System | Implemented | Low |
| Ph17 | Config System | Implemented | Medium |
| Ph18 | Voice Mode & Diagnostics | Implemented | Low |
| Ph19 | GUI Stubs (not yet implemented) | Stub | Info only |
| Ph20 | Sensory Matrix (Sound & Particles) | Implemented | Critical |

---

## Start Here — 5 Quick Sanity Checks

Before running any phase, confirm the following are working. If any fail, stop and fix the server before continuing.

1. `/cb` — chat shows `§0§l[§b§lCB§0§l]§r` prefix on the response. If prefix is missing, ChatHelper is broken.
2. `/cb list` — returns `§0§l[§b§lCB§0§l]§r §6§lCustomBlocks §r§8│ §7<N> blocks  §8│  §7<N> free §e✦` without error.
3. `/cb menu` — opens a 54-slot chest GUI without a crash.
4. Sound check: click any button in any GUI — you hear `BLOCK_AMETHYST_BLOCK_CHIME` at vol 0.6 pitch 1.25 and see 6 ENCHANT particles.
5. `config/customblocks/config.json` exists on disk after first server start.

---

## Ph01 — Block Creation & Core Commands

**Status:** Implemented  **Priority:** Critical

### Setup

```
# Ensure you are OP (level 4) or have customblocks.admin permission
# Ensure at least 1 free slot (/cb list to check)
# Run all commands as a player in-game (not console) unless noted
```

### Main Tests

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | `/cb create test_block "My Test" https://i.imgur.com/xyz.png` | Chat | `§0§l[§b§lCB§0§l]§r §f'My Test' created! §7(slot 1) §a✔` |
| 2 | Run create again with same ID `test_block` | Chat | `§0§l[§b§lCB§0§l]§r §c'test_block' already exists. §4✖` |
| 3 | `/cb blocks` | Chat | Chat list shows `test_block` entry |
| 4 | `/cb list` | Chat | `§0§l[§b§lCB§0§l]§r §6§lCustomBlocks §r§8│ §71 blocks  §8│  §7<N> free §e✦` |
| 5 | `/cb give test_block` | Chat + Inventory | `§0§l[§b§lCB§0§l]§r §fGiven 1x '§fMy Test§a' to you. §a✔`, block item in hotbar |
| 6 | `/cb give test_block <OtherPlayer>` | Chat | `§0§l[§b§lCB§0§l]§r §fGave 1x to 1 player(s). §a✔` |
| 7 | `/cb give test_block @a` | Chat | `§0§l[§b§lCB§0§l]§r §fGave 1x to <N> player(s). §a✔` |
| 8 | `/cb rename test_block "New Name"` | Chat | `§0§l[§b§lCB§0§l]§r §fRenamed to '§fNew Name§a'. §a✔` |
| 9 | `/cb reid test_block test_block_2` | Chat | `§0§l[§b§lCB§0§l]§r §fRe-ID'd §f'test_block' §a→ §f'test_block_2'§a. §a✔` |
| 10 | `/cb delete test_block_2` | Chat | `§0§l[§b§lCB§0§l]§r §c'test_block_2' §7deleted. §a§n[Click here to undo (15s)]§r` (clickable) |
| 11 | Click the undo link within 15 seconds | Chat | Undo fires — see Ph11 for undo message |
| 12 | `/cb delete <nonexistent>` | Chat | `§0§l[§b§lCB§0§l]§r §cBlock ID not found. §4✖` |
| 13 | `/cb dupe test_block newid` | Chat | `§0§l[§b§lCB§0§l]§r §fDuplicated 'test_block§a' → 'newid§a' §7(slot <N>) §a✔` |
| 14 | `/cb help` | Chat | `§6§lCustomBlocks  §r§8│ §7page §e1§7/§e<N>  §8│ §7/cb or /customblock` shown |
| 15 | `/cb help 2` | Chat | Page 2 of help displayed |
| 16 | `/cb welcome` | GUI | WELCOME_MENU opens (54-slot chest) |
| 17 | `/cb menu` | GUI | MAIN or FEATURE_MENU opens |
| 18 | Discord webhook fires on create (if configured) | External | Discord posts `🟩 **Block Created** by \`<player>\`` with `ID: \`test_block\` · Name: \`My Test\` · Slot #1` |
| 19 | Discord webhook fires on delete (if configured) | External | Discord posts `🟥 **Block Deleted** by \`<player>\`` with `ID: \`test_block_2\` · Slot #<N>` |

### Edge Cases

| # | Scenario | Expected |
|---|---------|----------|
| E1 | Create with all 600 slots full | `§0§l[§b§lCB§0§l]§r §cAll 600 slots are full! §4✖` |
| E2 | Create with invalid URL (no http) | Download fails; error shown |
| E3 | Give a block that does not exist | `§0§l[§b§lCB§0§l]§r §cBlock ID not found. §4✖` |
| E4 | Reid to an ID already taken | `§0§l[§b§lCB§0§l]§r §cID 'newid' is already taken. §4✖` |
| E5 | Reid to empty string after sanitization | `§0§l[§b§lCB§0§l]§r §cNew ID is invalid (empty after sanitization). §4✖` |

### Not Yet Implemented

```
- /cb dress (DRESS_GUI is a stub)
- /cb gradient (GRADIENT_GUI is a stub)
```

**Report shorthand:** `Ph01-#<row> [PASS/FAIL] <actual>`

---

## Ph02 — Block Properties & Texture

**Status:** Implemented  **Priority:** Critical

### Setup

```
# Have block 'test_block' created: /cb create test_block "Test" <url>
# Ensure a valid image URL is accessible from the server
```

### Main Tests

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | `/cb retexture test_block https://i.imgur.com/abc.png` | Chat | `§0§l[§b§lCB§0§l]§r §fTexture updated for '§ftest_block§a'. §a✔` |
| 2 | `/cb resize test_block 64` | Chat | `§0§l[§b§lCB§0§l]§r §f'test_block' resized to 64px. §a✔` |
| 3 | `/cb resize test_block 999` | Chat | `§0§l[§b§lCB§0§l]§r §cTexture size must be between 16 and 256. You typed: 999 §4✖` |
| 4 | `/cb sethardness test_block 5.0` | Chat | `§0§l[§b§lCB§0§l]§r §f'test_block' hardness: 5.0. §a✔` |
| 5 | `/cb sethardness test_block -2` | Chat | `§0§l[§b§lCB§0§l]§r §cHardness must be between -1 (unbreakable) and 50. You typed: -2 §4✖` |
| 6 | `/cb sethardness test_block -1` | Chat | `§0§l[§b§lCB§0§l]§r §f'test_block' hardness: -1. §a✔` (unbreakable) |
| 7 | `/cb setsound test_block stone` | Chat | `§0§l[§b§lCB§0§l]§r §f'test_block' sound: stone. §a✔` |
| 8 | `/cb setsound test_block badtype` | Chat | `§0§l[§b§lCB§0§l]§r §cValid: stone wood grass metal glass sand wool gravel snow dirt coral bamboo nether_brick ice honey bone slime §4✖` |
| 9 | `/cb setglow test_block 10` | Chat | Glow confirmed; light level set |
| 10 | `/cb setglow test_block 16` | Chat | `§0§l[§b§lCB§0§l]§r §cLight level must be between 0 and 15. You typed: 16 §4✖` |
| 11 | `/cb setface test_block top https://i.imgur.com/face.png` | Chat | `§0§l[§b§lCB§0§l]§r §ftop face set on 'test_block'. §a✔` |
| 12 | `/cb setface test_block badface https://...` | Chat | `§0§l[§b§lCB§0§l]§r §cValid faces: top bottom north south east west §4✖` |
| 13 | `/cb clearface test_block top` | Chat | `§0§l[§b§lCB§0§l]§r §ftop face cleared on 'test_block'. §a✔` |
| 14 | `/cb clearallfaces test_block` | Chat | `§0§l[§b§lCB§0§l]§r §fAll face overrides cleared on 'test_block'. §a✔` |
| 15 | `/cb setcollision test_block false` | Chat | `§0§l[§b§lCB§0§l]§r §fCollision §ffalse§a for 'test_block'. §a✔` |
| 16 | `/cb setshape test_block cube` | Chat | `§0§l[§b§lCB§0§l]§r §fShape set to '§fcube§a' on '§ftest_block§a'. §a✔` |
| 17 | `/cb addshape test_block 0,0,0,16,8,16` | Chat | `§0§l[§b§lCB§0§l]§r §fAdded box #§f1§a to '§ftest_block§a'. Total: §f1 §a✔` |
| 18 | `/cb removeshape test_block 1` | Chat | `§0§l[§b§lCB§0§l]§r §fRemoved box #§f1§a from '§ftest_block§a'. §a✔` |
| 19 | `/cb clearshape test_block` | Chat | `§0§l[§b§lCB§0§l]§r §fShape reset to full cube on '§ftest_block§a'. §a✔` |
| 20 | `/cb settabicon test_block https://i.imgur.com/icon.png` | Chat | `§0§l[§b§lCB§0§l]§r §fTab icon updated! §7(Takes a few seconds to appear — resource pack is reloading) §a✔` |
| 21 | `/cb exportblock test_block` | Chat + Disk | Export message shown |
| 22 | `/cb importblock <code>` | Chat | `§0§l[§b§lCB§0§l]§r §fImported 'test_block' with texture! §a✔` or retexture hint |
| 23 | `/cb showbrokenblocks` | Chat | Lists broken blocks or `§0§l[§b§lCB§0§l]§r §7No broken blocks to delete. §e✦` |

### Edge Cases

| # | Scenario | Expected |
|---|---------|----------|
| E1 | Retexture a nonexistent block | `§0§l[§b§lCB§0§l]§r §cBlock ID not found. §4✖` |
| E2 | Resize a block with no texture | `§0§l[§b§lCB§0§l]§r §c'test_block' has no texture to resize. §4✖` |
| E3 | addshape beyond 16 boxes | `§0§l[§b§lCB§0§l]§r §cMax 16 boxes per block. §4✖` |
| E4 | Import with code not starting with `CB~` | `§0§l[§b§lCB§0§l]§r §cInvalid code format. Must start with CB~, CB3!, CB2!, or CB! §4✖` |

### Not Yet Implemented

```
- /cb retexture via RETEXTURE_WIZARD GUI (RETEXTURE_WIZARD is a stub)
```

**Report shorthand:** `Ph02-#<row> [PASS/FAIL] <actual>`

---

## Ph03 — GUI: Main, Picker, Editor

**Status:** Implemented  **Priority:** Critical

### Setup

```
# Have at least 3 custom blocks created
# Stand in-game as a player
# Royal Directive: header = rows 0-8, content = rows 9-35, footer = rows 45-53
```

### Main Tests

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | `/cb gui` | GUI | MAIN (54-slot chest) opens; header rows 0-8 contain legendary items (Echo Shards, Nether Stars, Dragon Eggs, etc.) |
| 2 | Click any header button | Sound + Particles | 🔊 `SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME` vol 0.6 pitch 1.25 + ✨ ENCHANT x6 |
| 3 | Navigate to PICKER mode | GUI | PICKER opens listing all blocks; content rows 9-35 |
| 4 | Click a block in PICKER | GUI | EDITOR opens for that block |
| 5 | In EDITOR, click back arrow | GUI | Returns to PICKER (back-stack pop) |
| 6 | In EDITOR, click delete | Chat | `§0§l[§b§lCB§0§l]§r §a[GUI] Deleted '§f<id>§a'. Use /cb undo to restore. §a✔` |
| 7 | In EDITOR, rename block | Chat | Prompt appears; after rename: `§0§l[§b§lCB§0§l]§r §fRenamed to '§f<name>§a'. §a✔` |
| 8 | In EDITOR, give block to self | Chat + Inventory | `§0§l[§b§lCB§0§l]§r §a[GUI] Given 1x §f<name>` |
| 9 | In EDITOR, dupe block | Chat | Success message or `§0§l[§b§lCB§0§l]§r §c[GUI] Duplication failed. §4✖` on failure |
| 10 | `/cb editor` | GUI | EDITOR opens for most recent block |
| 11 | `/cb listgui` | Chat | `§0§l[§b§lCB§0§l]§r §7Click here to open the blocks GUI! §e✦` (clickable) |
| 12 | `/cb helpgui` | GUI | HELP_MENU opens |
| 13 | Open PICKER_BROKEN | GUI | Broken-blocks picker shows only broken blocks |
| 14 | Close any GUI (Escape) | (none) | GUI state cleared; no crash |

### Edge Cases

| # | Scenario | Expected |
|---|---------|----------|
| E1 | Open GUI from console | `§0§l[§b§lCB§0§l]§r §cOpen this from in-game as a player. §4✖` |
| E2 | PICKER with 0 blocks | Content area empty; no crash |
| E3 | Double-click GUI button | Only one action fires |
| E4 | Back-stack with no history | Back button returns to MAIN or does nothing |

### Not Yet Implemented

```
None — MAIN, PICKER, PICKER_BROKEN, EDITOR all in IMPLEMENTED_MODES.
```

**Report shorthand:** `Ph03-#<row> [PASS/FAIL] <actual>`

---

## Ph04 — GUI: Face Editor & Face Change

**Status:** Implemented  **Priority:** High

### Setup

```
# Have block 'test_block' with at least one face set
# /cb facechangegui test_block
```

### Main Tests

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | `/cb facechangegui test_block` | GUI | FACE_CHANGE_SELECT opens (6 face slots) |
| 2 | Click a face slot | GUI | FACE_CHANGE_PICKER opens for that face |
| 3 | In FACE_EDITOR, paste URL via chat prompt | Chat | `§0§l[§b§lCB§0§l]§r §6[GUI] §ePaste URL for §f<face> §eof '§f<id>§e' (or §ccancel§e):` then after paste: face set success message |
| 4 | Type `cancel` in face prompt | Chat | `§0§l[§b§lCB§0§l]§r §7[Properties] Cancelled. §e✦` |
| 5 | Clear face from FACE_EDITOR | Chat | `§0§l[§b§lCB§0§l]§r §a[GUI] All face overrides cleared. §a✔` |
| 6 | Undo face change from GUI | Chat | `§0§l[§b§lCB§0§l]§r §a[GUI] Undid '<delta>'. §a✔` |
| 7 | `/cb setface test_block north <url>` | Chat | `§0§l[§b§lCB§0§l]§r §fnorth face set on 'test_block'. §a✔` |
| 8 | `/cb importfolder` — drop import | Chat | `§0§l[§b§lCB§0§l]§r §fDrop your image into the §bimport folder§f. §7You have 5 minutes. §e✦` + `§0§l[§b§lCB§0§l]§r §7Target face: §b<face> §8• §7Folder: §b<path> §e✦` |

### Edge Cases

| # | Scenario | Expected |
|---|---------|----------|
| E1 | Paste non-URL in face prompt | `§0§l[§b§lCB§0§l]§r §cNeeds a URL starting with http:// or https:// §4✖` |
| E2 | Face import timeout (5 min) | `§0§l[§b§lCB§0§l]§r §eFace import timed out for §b<face>§e. §7Shift-click again when you're ready. §6⚠` |
| E3 | Copy face from block with no texture | `§0§l[§b§lCB§0§l]§r §cThat source block has no usable texture for §b<face>§c. §4✖` |

### Not Yet Implemented

```
None — FACE_EDITOR, FACE_CHANGE_SELECT, FACE_CHANGE_PICKER all in IMPLEMENTED_MODES.
```

**Report shorthand:** `Ph04-#<row> [PASS/FAIL] <actual>`

---

## Ph05 — GUI: Shape Editor & Collision

**Status:** Implemented  **Priority:** High

### Setup

```
# Have block 'test_block' created
# /cb shapeeditor test_block
```

### Main Tests

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | `/cb shapeeditor test_block` | GUI | SHAPE_EDITOR opens |
| 2 | Select preset shape from SHAPE_EDITOR | Chat | `§0§l[§b§lCB§0§l]§r §a[Shape] Applied '§f<preset>§a' to current block. §a✔` |
| 3 | Add custom box via GUI | Chat | `§0§l[§b§lCB§0§l]§r §a[Shape] ✔ Created '§f<variant>§a' (ID: §f<id>§a) §a✔` |
| 4 | Attempt 25th variant (max 24) | Chat | `§0§l[§b§lCB§0§l]§r §c[Shape] Maximum variants reached (24). §4✖` |
| 5 | `/cb shapelist` | Chat | Lists registered shapes |
| 6 | `/cb shapepreview test_block` | Chat/GUI | Shape preview shown |
| 7 | `/cb setcollision test_block true` | Chat | `§0§l[§b§lCB§0§l]§r §fCollision §ftrue§a for 'test_block'. §a✔` |
| 8 | `/cb setcollision test_block false` | Chat | `§0§l[§b§lCB§0§l]§r §fCollision §ffalse§a for 'test_block'. §a✔` |
| 9 | `/cb square` | Chat + Inventory | `§0§l[§b§lCB§0§l]§r §fGiven <color> Square§a! §a✔` |
| 10 | `/cb triangle` | Chat + Inventory | `§0§l[§b§lCB§0§l]§r §fGiven <color> Triangle§a! §a✔` |
| 11 | `/cb chisel` | Chat + Inventory | `§0§l[§b§lCB§0§l]§r §dGiven §5Amethyst Chisel§d! §7Right-click any block to sculpt its shape. §a✔` |
| 12 | Right-click block with chisel | GUI | SHAPE_EDITOR opens |

### Edge Cases

| # | Scenario | Expected |
|---|---------|----------|
| E1 | Bad preset name | `§0§l[§b§lCB§0§l]§r §cUnknown preset or bad coords. Presets: <list> §4✖` |
| E2 | Bad coordinate format | `§0§l[§b§lCB§0§l]§r §cBad coords. Format: x1,y1,z1,x2,y2,z2 (0-16) §4✖` |
| E3 | OOM during shape creation | `§0§l[§b§lCB§0§l]§r §c[Shape] Not enough memory! §4✖` |

### Not Yet Implemented

```
None — SHAPE_EDITOR is in IMPLEMENTED_MODES.
```

**Report shorthand:** `Ph05-#<row> [PASS/FAIL] <actual>`

---

## Ph06 — GUI: Animation (Anim GUI)

**Status:** Implemented  **Priority:** High

### Setup

```
# Have block 'test_block' with a GIF texture
# /cb retexture test_block <gif_url>
# Default max GIF size: 2MB
```

### Main Tests

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Open ANIM_GUI from EDITOR for animated block | GUI | ANIM_GUI opens showing frame count and FPS |
| 2 | Set FPS to 20 via chat prompt | Chat | `§0§l[§b§lCB§0§l]§r §a[Anim] FPS set to §f20 §a✔` |
| 3 | Set FPS to invalid string | Chat | `§0§l[§b§lCB§0§l]§r §cInvalid number. Enter a value like §f20§c or §f0.5 §4✖` |
| 4 | Toggle blend interpolation | Chat | `§0§l[§b§lCB§0§l]§r §fSmooth blending <on/off>§a for '§f<id>§a'. §a✔` |
| 5 | Save with both FPS and blend changed | Chat | `§0§l[§b§lCB§0§l]§r §fAnimation updated for '§f<id>§a' (<fps> fps, blending <mode>§a). §a✔` |
| 6 | Save with only FPS changed | Chat | `§0§l[§b§lCB§0§l]§r §fAnimation speed updated for '§f<id>§a' (<fps> fps) §a✔` |
| 7 | Save with no changes | Chat | `§0§l[§b§lCB§0§l]§r §fAnimation settings saved for '§f<id>§a' (no changes) §a✔` |
| 8 | Click Abandon in ANIM_CONFIRM_ABANDON | GUI | Returns to EDITOR without saving |
| 9 | Retexture with GIF triggers metadata | Chat | `§0§l[§b§lCB§0§l]§r §fAnimation metadata generated! §7(Syncing...) §a✔` |

### Edge Cases

| # | Scenario | Expected |
|---|---------|----------|
| E1 | GIF exceeds max size (default 2MB) | Error logged; block created without animation |
| E2 | Open ANIM_GUI on static (non-GIF) block | GUI opens; animation fields show defaults |

### Not Yet Implemented

```
None — ANIM_GUI, ANIM_CONFIRM_ABANDON are in IMPLEMENTED_MODES.
```

**Report shorthand:** `Ph06-#<row> [PASS/FAIL] <actual>`

---

## Ph07 — GUI: Categories

**Status:** Implemented  **Priority:** High

### Setup

```
# Have several blocks created
# Commands tested: /cb blockscategory, /cb blockadd, /cb bulkblockadd, /cb givecategory,
#   /cb exportcategory, /cb exportall, /cb sharecategory, /cb importcategory, /cb givedisplayblock
```

### Main Tests

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | `/cb blockscategory create mycat "My Category"` | Chat | `§0§l[§b§lCB§0§l]§r §fCreated 'My Category'. §a✔` |
| 2 | Create duplicate category ID | Chat | Error — category already exists |
| 3 | `/cb blockadd test_block mycat` | Chat | `§0§l[§b§lCB§0§l]§r §fAdded 'test_block§a' to category §fmycat§a! §a✔` |
| 4 | `/cb blockadd test_block mycat` again | Chat | `§0§l[§b§lCB§0§l]§r §f'test_block' is already in 'mycat'. §a✔` |
| 5 | `/cb bulkblockadd mycat id1 id2 id3` | Chat | `§0§l[§b§lCB§0§l]§r §fBulk assign complete. Added: §f3§a, already in category: §f0§a, invalid IDs: §f0§a. §a✔` |
| 6 | `/cb bulkblockadd mycat` (no IDs) | Chat | `§0§l[§b§lCB§0§l]§r §7No block IDs were provided. Example: /cb bulkblockadd mycat id1 id2 id3 §e✦` |
| 7 | `/cb givecategory mycat` | Chat + Inventory | `§0§l[§b§lCB§0§l]§r §fGiven §f<N>§a blocks from §fmycat§a! §a✔` |
| 8 | `/cb givecategory emptycat` | Chat | `§0§l[§b§lCB§0§l]§r §fCategory 'emptycat' is empty. §a✔` |
| 9 | `/cb exportcategory mycat` | Chat + Disk | `§0§l[§b§lCB§0§l]§r §fExported category '§fmycat§a' with §f<N>§a blocks to config/customblocks/<file> §a✔` |
| 10 | `/cb exportall` | Chat + Disk | `§0§l[§b§lCB§0§l]§r §fExported all categories to config/customblocks/<file> §a✔` |
| 11 | `/cb sharecategory mycat` | Chat | Cloud share code displayed; format `CB~<12chars>` |
| 12 | `/cb importcategory <code>` | Chat | `§0§l[§b§lCB§0§l]§r §aImported category '§f<name>§a' with §f<N>§a block assignments. §a✔` |
| 13 | `/cb givedisplayblock mycat` | Inventory | `§0§l[§b§lCB§0§l]§r §aGave display block for §fmycat§a. §a✔` |
| 14 | Open CATEGORY_BROWSER via GUI | GUI | CATEGORY_BROWSER opens listing categories |
| 15 | Open CATEGORY_CONTROLLER | GUI | Manage actions visible (edit, delete, merge, sort) |
| 16 | Open CATEGORY_EDITOR | GUI | Allows renaming the category |
| 17 | Merge via MERGE_CATEGORY_PICKER_TARGET | Chat | `§0§l[§b§lCB§0§l]§r §aSuccessfully merged into §f<target> §a✔` |
| 18 | Sort via SORT_BLOCKS_MENU | Chat | `§0§l[§b§lCB§0§l]§r §aSort preference applied. §a✔` |
| 19 | View CATEGORY_STATS | GUI | Stats GUI shows block count per category |
| 20 | Set icon via CATEGORY_ICON_PICKER | Chat | `§0§l[§b§lCB§0§l]§r §aCategory icon updated! §a✔` |
| 21 | Open SUBCATEGORY_CONTROLLER | GUI | Sub-category management visible |
| 22 | Resolve conflict in IMPORT_CONFLICT | GUI | User selects skip or overwrite |
| 23 | Delete via DELETE_CATEGORY_MENU | GUI | Confirmation step before delete |
| 24 | Assign via BULK_ASSIGN_PICKER | Chat | `§0§l[§b§lCB§0§l]§r §aBulk assign complete. Added: §f<N>§a, already in category: §f<N> §a✔` |

### Edge Cases

| # | Scenario | Expected |
|---|---------|----------|
| E1 | importcategory with cloud disabled | `§0§l[§b§lCB§0§l]§r §cCloud sharing is disabled in config. §4✖` |
| E2 | blockscategory for nonexistent category | `§0§l[§b§lCB§0§l]§r §cCategory 'nonexistent' was not found. §4✖` |
| E3 | Display block right-clicked, category deleted | `§cThis category no longer exists.` in chat |

### Not Yet Implemented

```
None — all category GUI modes are in IMPLEMENTED_MODES.
```

**Report shorthand:** `Ph07-#<row> [PASS/FAIL] <actual>`

---

## Ph08 — GUI: Bulk Operations

**Status:** Implemented  **Priority:** High

### Setup

```
# Have 15+ blocks created
# /cb bulkgui  OR navigate from MAIN GUI
# bulkConfirmThreshold default = 10 (confirmation required above this count)
```

### Main Tests

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | `/cb bulkgui` | GUI | BULK_HUB opens |
| 2 | Open BULK_OP_PICKER from BULK_HUB | GUI | Operation picker shows options |
| 3 | `/cb bulk rename test_* "Prefix_"` | Chat | Bulk rename summary shown |
| 4 | `/cb bulkrename <ids> "NewPrefix"` | Chat | Rename summary with count |
| 5 | `/cb bulkdelete id1 id2 id3` | Chat | `§0§l[§b§lCB§0§l]§r §fDeleted: id1, id2, id3 §a✔` |
| 6 | Bulk delete 11 IDs (above threshold 10) | Chat | `§0§l[§b§lCB§0§l]§r §7About to affect 11 blocks. Click confirm to apply. §e✦` |
| 7 | GUI bulk delete: arm then confirm within 15s | ActionBar + Chat + Title | ActionBar: `§6Bulk delete armed — tap Confirm again within §f15s§6.` Chat: `§0§l[§b§lCB§0§l]§r §eClick §cConfirm Delete§e again within §f15 seconds§e to run the delete. §6⚠` After confirm: Title `§e§lBulk delete` Subtitle `§a§l<N> §r§ablock(s) removed` |
| 8 | After GUI bulk delete | Chat | `§0§l[§b§lCB§0§l]§r §a[GUI] Bulk deleted §f<N>§a block(s). Use Undo to restore. §a✔` |
| 9 | `/cb bulkrecolor all green --apply` | Chat | `§0§l[§b§lCB§0§l]§r §fBulk recolor complete. Created: §f<N>§a, already existed: §f<N>§a, skipped: §f<N>§a, excluded: §f<N>§a, invalid: §f<N>§a. §a✔` |
| 10 | `/cb bulkrecolor all green` (preview only) | Chat | `§0§l[§b§lCB§0§l]§r §fPreview only. Matched: §f<N>§a, Excluded: §f<N>§a, Invalid tokens: §f<N>§a. §a✔` |
| 11 | `/cb bulkrecolor badscope badcolor` | Chat | `§0§l[§b§lCB§0§l]§r §cUnknown color 'badcolor'. Use: green, yellow, or black. §4✖` |
| 12 | `/cb bulkreid` | Chat | Bulk re-ID summary |
| 13 | `/cb bulkproperty` | Chat | Bulk property apply |
| 14 | `/cb bulkexport` | Chat + Disk | Bulk export summary |
| 15 | `/cb bulkmove` | Chat | Bulk move summary |
| 16 | `/cb bulkduplicate` | Chat | Bulk duplication summary |
| 17 | `/cb bulklock` | Chat | Bulk lock summary |
| 18 | `/cb bulkunlock` | Chat | Bulk unlock summary |
| 19 | `/cb bulkfavorite` | Chat | Bulk favorite summary |
| 20 | `/cb bulkunfavorite` | Chat | Bulk unfavorite summary |
| 21 | `/cb bulkshape` | Chat | Bulk shape summary |
| 22 | `/cb bulksound` | Chat | Bulk sound summary |
| 23 | Open BULK_RECOLOR_WIZARD | GUI | BULK_RECOLOR_WIZARD opens |
| 24 | Confirm in BULK_RECOLOR_CONFIRM | Sound + Particles | 🔊 `SoundEvents.BLOCK_BEACON_ACTIVATE` vol 0.8 pitch 1.0 + ✨ HAPPY_VILLAGER x10 on completion |

### Edge Cases

| # | Scenario | Expected |
|---|---------|----------|
| E1 | Bulk delete with no blocks selected | `§0§l[§b§lCB§0§l]§r §cNo blocks selected. §4✖` |
| E2 | Bulk recolor scope with no matches | `§0§l[§b§lCB§0§l]§r §cNo blocks matched scope '<scope>'. §4✖` |
| E3 | Open BULK_HUB without customblocks.bulk | `§0§l[§b§lCB§0§l]§r §cBulk GUI needs §fcustomblocks.bulk§c (or matching op level). §4✖` |
| E4 | Invalid token list in bulkrecolor | `§0§l[§b§lCB§0§l]§r §cInvalid IDs/tokens: <list> §4✖` |

### Not Yet Implemented

```
DROP_CONFIG is a stub — block drop configuration GUI not yet implemented.
All other bulk GUI modes are in IMPLEMENTED_MODES.
```

**Report shorthand:** `Ph08-#<row> [PASS/FAIL] <actual>`

---

## Ph09 — GUI: Background Studio (BG Studio)

**Status:** Implemented  **Priority:** Medium

### Setup

```
# /cb diamondtriangle  (gives Diamond Triangle tool)
# Right-click any block to open Background Studio
# OR navigate from EDITOR -> BG Studio button
```

### Main Tests

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | `/cb diamondtriangle` | Chat + Inventory | `§0§l[§b§lCB§0§l]§r §fGiven §bDiamond Triangle§f! §7Right-click anywhere to open the Background Studio. §a✔` |
| 2 | Right-click block with Diamond Triangle | GUI | BG_STUDIO opens |
| 3 | Enable background removal | Chat | `§0§l[§b§lCB§0§l]§r §a[BG Studio] Background removal §aENABLED§a (set to default 30). §a✔` |
| 4 | Disable background removal | Chat | `§0§l[§b§lCB§0§l]§r §a[BG Studio] Background removal §cDISABLED§a. §a✔` |
| 5 | `/cb tolerance set 50` | Chat | `§0§l[§b§lCB§0§l]§r §a[BG Studio] Tolerance set to §f50 §a✔` |
| 6 | `/cb tolerance set 100` | Chat | `§0§l[§b§lCB§0§l]§r §a[BG Studio] Tolerance set to §f100 §7(MAX) §a✔` |
| 7 | `/cb tolerance show` | Chat | `§0§l[§b§lCB§0§l]§r §a[BG Studio] Tolerance: §f<N> §a✔` |
| 8 | `/cb tolerance reset` | Chat | Tolerance reset to default 30 |
| 9 | Apply a BG Studio preset | Chat | `§0§l[§b§lCB§0§l]§r §a[BG Studio] Preset applied — tolerance §f<N> §a✔` |
| 10 | Set math mode | Chat | `§0§l[§b§lCB§0§l]§r §a[BG Studio] Background math: §f<mode> §a✔` |
| 11 | Type invalid hex color | Chat | `§0§l[§b§lCB§0§l]§r §c[BG Studio] Type a hex colour like §f#55CCFF §cor §f55ccff§c. §4✖` |
| 12 | Bulk re-apply started | Chat | `§0§l[§b§lCB§0§l]§r §5[BG Studio] §dBulk re-apply started for §f<N> §dblocks. Watch chat for progress… §e✦` |
| 13 | Bulk re-apply done | Chat | `§0§l[§b§lCB§0§l]§r §5[BG Studio] §dBulk re-apply done — §a<N> updated§d, §7<N> skipped§d, §c<N> failed§d. §e✦` |
| 14 | Cancel BG Studio triangle factory | Chat | `§0§l[§b§lCB§0§l]§r §7[BG Studio] Triangle Factory cancelled. §e✦` |
| 15 | `/cb customtriangle #55CCFF` | Chat + Inventory | `§0§l[§b§lCB§0§l]§r §b[BG Studio] Minted §f#<N> §bSquare + Triangle. §a✔` |

### Edge Cases

| # | Scenario | Expected |
|---|---------|----------|
| E1 | Custom triangle item not registered | `§0§l[§b§lCB§0§l]§r §c[BG Studio] Custom Triangle item is not registered. §4✖` |
| E2 | Hex color without # prefix | `§0§l[§b§lCB§0§l]§r §cUse a hex colour like #55CCFF or 55ccff. §4✖` |

### Not Yet Implemented

```
None — BG_STUDIO is in IMPLEMENTED_MODES.
```

**Report shorthand:** `Ph09-#<row> [PASS/FAIL] <actual>`

---

## Ph10 — GUI: Resource Center (RP)

**Status:** Implemented  **Priority:** Medium

### Setup

```
# /cb resourcepack  OR  /cb rp
# HTTP server port must be > 0 in config for download link to work
```

### Main Tests

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | `/cb rp` | GUI | RESOURCE_CENTER opens |
| 2 | Click "Get Download Link" | Chat | `§0§l[§b§lCB§0§l]§r §fDownload Link: §b§n<URL> §e✦` (clickable) |
| 3 | Click "Force Sync" | Chat | `§0§l[§b§lCB§0§l]§r §a[System] Force-syncing all clients... §a✔` |
| 4 | Click "Pause Reloads" | Chat | `§0§l[§b§lCB§0§l]§r §6[System] Resource pack reloads §ePAUSED§6 for all clients. §6⚠` |
| 5 | `/cb rp pause` | Chat | `§0§l[§b§lCB§0§l]§r §fResource pack reloads §ePAUSED§a for all clients. Use §f/cb rp resume§a when done. §a✔` |
| 6 | Click "Resume Reloads" | Chat | `§0§l[§b§lCB§0§l]§r §a[System] Resource pack reloads §aRESUMED§a — clients will reload now. §a✔` |
| 7 | `/cb rp resume` | Chat | `§0§l[§b§lCB§0§l]§r §fResource pack reloads §aRESUMED§a — clients will reload now. §a✔` |
| 8 | `/cb sync` | Chat | Resource pack sync broadcast to all players |
| 9 | Block texture updated — pack rebuilds | Sound + Particles | 🔊 `SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME` vol 0.35 pitch 1.15 + ✨ ENCHANT x4 |
| 10 | Get link with HTTP server stopped | Chat | `§0§l[§b§lCB§0§l]§r §cHTTP server is not running. Set a port > 0 first. §4✖` |
| 11 | IP detection starts | Chat | `§0§l[§b§lCB§0§l]§r §7Detecting your public IP address… §e✦` |
| 12 | Port not forwarded | Chat | `§0§l[§b§lCB§0§l]§r §eCould not detect public IP — ensure port §f<port> §eis forwarded! §6⚠` |

### Edge Cases

| # | Scenario | Expected |
|---|---------|----------|
| E1 | Resume when nothing was paused | Resume fires without error; clients reload |
| E2 | Pack rebuild triggered rapidly | Cooldown limits sound to 1 per cooldown window |

### Not Yet Implemented

```
FIND_PORT_GUI is a stub — in-GUI port-finding wizard not yet implemented.
```

**Report shorthand:** `Ph10-#<row> [PASS/FAIL] <actual>`

---

## Ph11 — Undo / Redo System

**Status:** Implemented  **Priority:** Critical

### Setup

```
# Undo snapshots at: config/customblocks/undo_snapshots/
# Max snapshot dir size: 200MB
# Delta types: MetaDelta, TextureDelta, FaceDelta, AnimDelta, ShapeDelta, SlotCreated, SlotDeleted
# Default undoMode: "both"
# Default maxUndoDepth: 10000
```

### Main Tests

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Create a block, then `/cb undo` | Chat | `§0§l[§b§lCB§0§l]§r §fUndid create of §f<id>§a. §7(<N> undo left) §a✔` |
| 2 | `/cb undo` with nothing to undo | Chat | `§0§l[§b§lCB§0§l]§r §7Nothing to undo. §e✦` |
| 3 | Rename a block, then `/cb undo` | Chat | `§0§l[§b§lCB§0§l]§r §fUndid "<delta>" on §f<id>§a. §7(<N> undo left, <N> redo) §a✔` |
| 4 | After undo, `/cb redo` | Chat | `§0§l[§b§lCB§0§l]§r §fRedid "<delta>" on §f<id>§a. §7(<N> redo left, <N> undo) §a✔` |
| 5 | `/cb redo` with nothing to redo | Chat | `§0§l[§b§lCB§0§l]§r §7Nothing to redo. §e✦` |
| 6 | Delete a block, then `/cb undo` | Chat | Block restored |
| 7 | After undo of delete, `/cb redo` | Chat | `§0§l[§b§lCB§0§l]§r §fRedid delete of §f<id>§a. §7(<N> redo left) §a✔` |
| 8 | `/cb undo 5` (batch) | Chat | `§0§l[§b§lCB§0§l]§r §fUndid §f5§a actions total. §a✔` |
| 9 | `/cb redo 5` (batch) | Chat | `§0§l[§b§lCB§0§l]§r §fRedid §f5§a actions total. §a✔` |
| 10 | Next undo hint | Chat | `§8  → Next undo: §7"<delta>"` |
| 11 | Next redo hint | Chat | `§8  → Next redo: §7"<delta>"` |
| 12 | Undo when target slot now occupied | Chat | `§0§l[§b§lCB§0§l]§r §cCannot undo — slot for '<id>' is now occupied by another block. §4✖` |
| 13 | Redo with slot conflict | Chat | `§0§l[§b§lCB§0§l]§r §cCannot redo — slot conflict for '<id>'. §4✖` |
| 14 | Undo create when block already deleted | Chat | `§0§l[§b§lCB§0§l]§r §cCannot undo create — '<id>' already gone. §4✖` |
| 15 | Open UNDO_PICKER GUI | GUI | UNDO_PICKER opens listing undo history |
| 16 | Undo from GUI | Chat | Same success message as row 3 |
| 17 | FIRST_UNDO achievement on first undo | Title + ActionBar | Title `§6§l🏆 Achievement Unlocked!` Subtitle `§7§l✦ Time Traveller` ActionBar `§6§l🏆 §r§7§l✦ Time Traveller §8— §7You used undo for the first time` |

### Edge Cases

| # | Scenario | Expected |
|---|---------|----------|
| E1 | `/cb undo` from console | Error — requires player context |
| E2 | Undo snapshot dir at 200MB | Oldest snapshots pruned automatically |
| E3 | undoMode=per_player — other player's actions absent | Only own undo actions visible |

### Not Yet Implemented

```
None — UNDO_PICKER is in IMPLEMENTED_MODES.
```

**Report shorthand:** `Ph11-#<row> [PASS/FAIL] <actual>`

---

## Ph12 — Favorites & Locks

**Status:** Implemented  **Priority:** Medium

### Setup

```
# Favorites: config/customblocks/favorites.json.gz
# Locks: config/customblocks/locks.json
# Permission for favorites from GUI: customblocks.favorites
```

### Main Tests

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | `/cb favorite test_block` | Chat | `§0§l[§b§lCB§0§l]§r §ftest_block§a added to favorites ✔ §a✔` |
| 2 | `/cb favorite test_block` again (toggle off) | Chat | `§0§l[§b§lCB§0§l]§r §ftest_block§a removed from favorites §a✔` |
| 3 | `/cb favorite` (list) | Chat | `§0§l[§b§lCB§0§l]§r §7<N> favorite(s): §f<ids> §e✦` |
| 4 | `/cb favorite` with empty list | Chat | `§0§l[§b§lCB§0§l]§r §7No favorites yet. Use §f/cb favorite <id>§7 to star a block. §e✦` |
| 5 | Open FAVORITES_GUI | GUI | FAVORITES_GUI opens listing favorited blocks |
| 6 | FIRST_FAVORITE achievement triggers on first | Title + ActionBar | Title `§6§l🏆 Achievement Unlocked!` Subtitle `§c§l✦ Favourited!` |
| 7 | `/cb lock test_block` | Chat | `§0§l[§b§lCB§0§l]§r §ftest_block §ais now locked. Edits and deletes are blocked until unlocked. §a🔒 §a✔` |
| 8 | Rename a locked block | Chat | Error — block is locked |
| 9 | Delete a locked block | Chat | Error — block is locked |
| 10 | `/cb unlock test_block` | Chat | Block unlocked; success message |
| 11 | `/cb bulklock id1 id2 id3` | Chat | All three locked |
| 12 | `/cb bulkunlock id1 id2 id3` | Chat | All three unlocked |
| 13 | `/cb bulkfavorite id1 id2` | Chat | Both favorited |
| 14 | `/cb bulkunfavorite id1 id2` | Chat | Both unfavorited |

### Edge Cases

| # | Scenario | Expected |
|---|---------|----------|
| E1 | Favorite a nonexistent block | `§0§l[§b§lCB§0§l]§r §cBlock ID not found. §4✖` |
| E2 | FAVORITES_GUI without permission | `§0§l[§b§lCB§0§l]§r §cYou need §fcustomblocks.favorites§c to star blocks from the editor. §4✖` |
| E3 | Lock then immediately delete | Error shown; block intact |

### Not Yet Implemented

```
PERMISSIONS_SUMMARY is a stub — in-GUI permissions overview not yet implemented.
```

**Report shorthand:** `Ph12-#<row> [PASS/FAIL] <actual>`

---

## Ph13 — Macro & Script Systems

**Status:** Implemented  **Priority:** Medium

### Setup

```
# Macros and scripts stored at: config/customblocks/macros/<name>.json
# /cb macro record <name>  to begin
# /cb script record <name>  to begin
```

### Main Tests

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | `/cb macro record mymacro` | Chat | `§0§l[§b§lCB§0§l]§r §b[Macro] §aRecording started: §fmymacro §a✔` then `§0§l[§b§lCB§0§l]§r §7Block edits (rename, delete, retexture, properties) are now captured.` then `§0§l[§b§lCB§0§l]§r §7Run §f/cb macro stop §7to finish.` |
| 2 | Perform several block edits while recording | (silent) | Edits captured |
| 3 | `/cb macro stop` | Chat | `§0§l[§b§lCB§0§l]§r §b[Macro] §aSaved §fmymacro §awith §f<N> §astep(s). §a✔` |
| 4 | `/cb macro run mymacro` | Chat | `§0§l[§b§lCB§0§l]§r §b[Macro] §aRan §fmymacro §a(<N> step(s)). §a✔` |
| 5 | `/cb macro list` | Chat | Lists all macros by name |
| 6 | `/cb macro show mymacro` | Chat | Shows steps in mymacro |
| 7 | `/cb macro delete mymacro` | Chat | Macro deleted |
| 8 | `/cb macro add mymacro` | Chat | Step added to macro |
| 9 | `/cb script record myscript` | Chat | Same messages as macro with `§b[Script]` prefix |
| 10 | `/cb script stop` | Chat | `§0§l[§b§lCB§0§l]§r §b[Script] §aSaved §fmyscript §awith §f<N> §astep(s). §a✔` |
| 11 | `/cb script run myscript` during execution | ActionBar | `§b▶ §fScript: §emyscript §8— §fstep §a<N>§8/§a<total>` |
| 12 | After script completes | Chat | `§0§l[§b§lCB§0§l]§r §b[Script] §fmyscript §acomplete — §f<ran>§a/§f<total> §asteps passed. §a✔` |
| 13 | `/cb scriptgui` | GUI | SCRIPT_GUI opens listing all scripts |
| 14 | Open SCRIPT_SUMMARY from SCRIPT_GUI | GUI | SCRIPT_SUMMARY shows step breakdown |

### Edge Cases

| # | Scenario | Expected |
|---|---------|----------|
| E1 | Run nonexistent macro | Error — macro not found |
| E2 | Record zero steps then stop | `§0§l[§b§lCB§0§l]§r §b[Macro] §aSaved §fmymacro §awith §f0 §astep(s). §a✔` |

### Not Yet Implemented

```
None — SCRIPT_GUI, SCRIPT_SUMMARY are in IMPLEMENTED_MODES.
```

**Report shorthand:** `Ph13-#<row> [PASS/FAIL] <actual>`

---

## Ph14 — Snapshot / Backup / Panic / Recover

**Status:** Implemented  **Priority:** High

### Setup

```
# Snapshots at: config/customblocks/snapshots/
# Max 20 snapshots (pruned automatically)
# Panic window: 30 seconds
# Auto-snapshot interval: 30 minutes (default)
```

### Main Tests

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | `/cb backup create` | Chat + Sound | Success message + 🔊 `SoundEvents.ENTITY_PLAYER_LEVELUP` vol 0.6 pitch 1.4 + ✨ ENCHANT x4 |
| 2 | `/cb backup list` | Chat | Lists up to 20 snapshots by timestamp |
| 3 | `/cb backup restore <N>` | Chat | Snapshot restored; block count shown |
| 4 | `/cb backup delete <N>` | Chat | Snapshot deleted |
| 5 | `/cb backup expiry <days>` | Chat | Expiry set |
| 6 | Create 21 snapshots | Disk | Oldest auto-pruned; only 20 remain |
| 7 | `/cb panic` | Chat | `§0§l[§b§lCB§0§l]§r §7PANIC requested. Type §f/cb panic confirm§r within 30s to roll back. §e✦` |
| 8 | `/cb panic confirm` within 30s | Chat | `§0§l[§b§lCB§0§l]§r §fRolled back to snapshot <N>. <N> blocks restored. §a✔` |
| 9 | `/cb panic confirm` after 30s | Chat | Panic window expired; no rollback |
| 10 | `/cb recover` (open RECOVER_GUI) | GUI | RECOVER_GUI opens |
| 11 | Open RECOVER_GUI without customblocks.panic | Chat | `§0§l[§b§lCB§0§l]§r §c[GUI] Snapshot recovery needs §fcustomblocks.panic§c (admin). §4✖` |
| 12 | `/cb safety` | GUI | SAFETY_CENTER opens |
| 13 | `/cb resume` with saved draft | GUI | Resumes last in-progress GUI state |
| 14 | `/cb resume` with nothing saved | Chat | `§0§l[§b§lCB§0§l]§r §7Nothing to resume. §e✦` |
| 15 | Auto-snapshot fires after 30 minutes | Disk | New snapshot appears in `config/customblocks/snapshots/` |

### Edge Cases

| # | Scenario | Expected |
|---|---------|----------|
| E1 | Panic with no snapshots | Error — no snapshot to roll back to |
| E2 | Restore with invalid index | Error — snapshot not found |

### Not Yet Implemented

```
None — RECOVER_GUI, SAFETY_CENTER are in IMPLEMENTED_MODES.
```

**Report shorthand:** `Ph14-#<row> [PASS/FAIL] <actual>`

---

## Ph15 — Cloud Share & Import

**Status:** Implemented  **Priority:** Medium

### Setup

```
# Cloud Vault URL: https://cb-cloud-vault.cbbblocksvault.workers.dev
# Share codes format: CB~<12charSHA256hash> (alphanumeric only)
# cloudShareEnabled must be true (default)
```

### Main Tests

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | `/cb exportblock test_block` | Chat | Share code displayed: `§0§l[§b§lCB§0§l]§r §a[Share] §f'§btest_block§f' ready! ` + code |
| 2 | Share via GUI editor Share button | Chat + Title | `§0§l[§b§lCB§0§l]§r §fBlock shared! §7Code below §a✔ ` + code; Title `§a§lShared!` Subtitle `§7<code>`; ActionBar `§a✔ Click the code in chat to copy!` |
| 3 | `/cb importblock CB~<code>` | Chat | `§0§l[§b§lCB§0§l]§r §7Checking the §bCloud Vault§7… §e✦` then `§0§l[§b§lCB§0§l]§r §fImported from §bCloud Vault§f! §a✔` |
| 4 | Import with nonexistent code | Chat | `§0§l[§b§lCB§0§l]§r §cBlock not found locally or in the Cloud Vault §7✘ §4✖` |
| 5 | Import with bad format | Chat | `§0§l[§b§lCB§0§l]§r §cInvalid code format. Must start with CB~, CB3!, CB2!, or CB! §4✖` |
| 6 | `/cb sharecategory mycat` | Chat | `§0§l[§b§lCB§0§l]§r §fPreparing category data for upload... §a✔` then share code |
| 7 | `/cb importcategory <code>` | Chat | Download message then `§0§l[§b§lCB§0§l]§r §aImported category '§f<name>§a' with §f<N>§a block assignments. §a✔` |
| 8 | Cloud service unavailable | Chat | `§0§l[§b§lCB§0§l]§r §cCloud service unavailable. Try again later. §4✖` |
| 9 | Cloud share disabled in config | Chat | `§0§l[§b§lCB§0§l]§r §cCloud sharing is disabled in config. §4✖` |
| 10 | Import with conflict | Chat | `§0§l[§b§lCB§0§l]§r §7Category 'mycat' already exists. Resolving conflicts... §e✦` |
| 11 | `/cb market` | GUI | MARKET_GUI opens |
| 12 | Import with no free slots | Chat | `§0§l[§b§lCB§0§l]§r §cImport failed: No free slots. §4✖` |

### Edge Cases

| # | Scenario | Expected |
|---|---------|----------|
| E1 | Network timeout | `§0§l[§b§lCB§0§l]§r §cCloud import failed. §7<reason> §4✖` |
| E2 | Cloud returns invalid JSON | `§0§l[§b§lCB§0§l]§r §cParse error: <msg> §4✖` |
| E3 | Import for locally-exported block | Resolves locally first; never hits cloud |

### Not Yet Implemented

```
IMPORT_WIZARD is a stub — in-GUI guided import wizard not yet implemented.
```

**Report shorthand:** `Ph15-#<row> [PASS/FAIL] <actual>`

---

## Ph16 — Achievement System

**Status:** Implemented  **Priority:** Low

### Setup

```
# Achievements at: config/customblocks/achievements.json.gz
# Each achievement fires once per player per UUID
# /cb achievements to open GUI
```

### Main Tests

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Create your first block ever | Title + ActionBar | Title: `§6§l🏆 Achievement Unlocked!` Subtitle: `§6§l✦ First Creation!` SubText: `You created your first Custom Block` ActionBar: `§6§l🏆 §r§6§l✦ First Creation! §8— §7You created your first Custom Block` |
| 2 | Create 10 blocks total | Title + ActionBar | Subtitle: `§a§l✦ Block Collector` SubText: `You've created 10 custom blocks` |
| 3 | Create 50 blocks total | Title + ActionBar | Subtitle: `§b§l✦ Master Builder` SubText: `You've created 50 custom blocks` |
| 4 | Create 100 blocks total | Title + ActionBar | Subtitle: `§d§l✦ Legendary Architect` SubText: `You've created 100 custom blocks` |
| 5 | Place custom block in world | Title + ActionBar | Subtitle: `§e§l✦ First Placement!` SubText: `You placed your first custom block in the world` |
| 6 | Add block to favorites | Title + ActionBar | Subtitle: `§c§l✦ Favourited!` SubText: `You starred your first block as a favourite` |
| 7 | Use undo first time | Title + ActionBar | Subtitle: `§7§l✦ Time Traveller` SubText: `You used undo for the first time` |
| 8 | Create a category | Title + ActionBar | Subtitle: `§3§l✦ Organised!` SubText: `You created your first block category` |
| 9 | Achievement sound | Sound | 🔊 `SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP` vol 0.8 pitch 1.0 (GuiManager.playSuccess) |
| 10 | Same achievement does NOT re-trigger | (none) | No title or sound on repeated qualifying action |
| 11 | Earn achievement while offline, then join | Title + Chat | Offline-queued achievement delivered on join |
| 12 | `/cb achievements` | GUI | ACHIEVEMENTS_GUI opens listing unlocked |
| 13 | Server-wide broadcast (if onBroadcast set) | Chat (all players) | All players see the broadcast |
| 14 | achievements.json.gz exists after first unlock | Disk | File present and valid GZ |

### Edge Cases

| # | Scenario | Expected |
|---|---------|----------|
| E1 | Disconnect mid-unlock | Achievement queued in PENDING_OFFLINE; delivered on rejoin |
| E2 | achievements.json.gz corrupted | Warn logged; fresh empty data used |

### Not Yet Implemented

```
None — ACHIEVEMENTS_GUI is in IMPLEMENTED_MODES.
```

**Report shorthand:** `Ph16-#<row> [PASS/FAIL] <actual>`

---

## Ph17 — Config System

**Status:** Implemented  **Priority:** Medium

### Setup

```
# Config at: config/customblocks/config.json
# /cb config <key> <value>  to change any setting
# /cb reload  to reload from disk
```

### Main Tests

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | `/cb config max-slots 100` | Chat | `§0§l[§b§lCB§0§l]§r §a[Config] §fmaxSlots §a= §e100 §a✔` |
| 2 | `/cb config undo-depth 500` | Chat | `§0§l[§b§lCB§0§l]§r §a[Config] §fmaxUndoDepth §a= §e500 §a✔` |
| 3 | `/cb config gif-limit 5` | Chat | `§0§l[§b§lCB§0§l]§r §a[Config] §fmaxGifSizeMb §a= §e5 §a✔` |
| 4 | `/cb config texture-size 256` | Chat | `§0§l[§b§lCB§0§l]§r §a[Config] §fdefaultTextureSize §a= §e256 §a✔` |
| 5 | `/cb config hologram true` | Chat | `§0§l[§b§lCB§0§l]§r §a[Config] aiHologram = true §a✔` |
| 6 | `/cb config hologram-height 2.5` | Chat | Config updated |
| 7 | `/cb config sounds false` | Chat | Sounds disabled |
| 8 | `/cb config particles false` | Chat | Particles disabled |
| 9 | `/cb config marketplace false` | Chat | Marketplace disabled |
| 10 | `/cb config voice royal` | Chat | `§0§l[§b§lCB§0§l]§r §fVoice mode is now §froyal§a. §a✔` |
| 11 | `/cb config backup-interval 60` | Chat | Auto-snapshot interval updated |
| 12 | `/cb config ai-provider openai` | Chat | AI provider set |
| 13 | `/cb config ai-key <key>` | Chat | AI key set |
| 14 | `/cb config ai-variations 3` | Chat | Variations count set |
| 15 | `/cb config ai-style cartoon` | Chat | AI style set |
| 16 | `/cb config` (no args) | GUI | CONFIG_GUI or CONFIG_WARNING opens |
| 17 | `/cb reload` | Chat | `§0§l[§b§lCB§0§l]§r §7Reloading blocks… (running in background) §e✦` then `§0§l[§b§lCB§0§l]§r §fReload complete — synced to all players. §a✔` |
| 18 | `/cb diagnostics` | Disk | ZIP at `config/customblocks/diagnostics/cb-diagnostics-<yyyyMMdd-HHmmss>.zip` |
| 19 | ZIP contents check | Disk | Contains: config.json, incidents/ (up to 10), locks.json, categories.json, latest_log_tail.txt (200 lines), summary.txt |
| 20 | `/cb config max-slots abc` (invalid type) | Chat | `§0§l[§b§lCB§0§l]§r §c[Config] Invalid number. §4✖` |
| 21 | `/cb config <unknown_key>` | Chat | `§0§l[§b§lCB§0§l]§r §c[Config] Unknown config key. §4✖` |
| 22 | `/cb cache` | GUI | CACHE_DASHBOARD opens |
| 23 | `/cb audit` | GUI | AUDIT_GUI opens showing recent actions |

### Edge Cases

| # | Scenario | Expected |
|---|---------|----------|
| E1 | Reload while bulk op in progress | Reload deferred or completes safely |
| E2 | config.json corrupted on disk | Warn logged; defaults used |

### Not Yet Implemented

```
None — CONFIG_GUI, CONFIG_WARNING, CACHE_DASHBOARD, AUDIT_GUI all in IMPLEMENTED_MODES.
```

**Report shorthand:** `Ph17-#<row> [PASS/FAIL] <actual>`

---

## Ph18 — Voice Mode & Diagnostics

**Status:** Implemented  **Priority:** Low

### Setup

```
# Voice modes: friendly, professional, royal, minimal, arabic, silly
# Lang files at: assets/customblocks/lang/voice_*.json
```

### Main Tests

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | `/cb voice friendly` | Chat | `§0§l[§b§lCB§0§l]§r §fVoice mode is now §ffriendly§a. §a✔` |
| 2 | `/cb voice professional` | Chat | `§0§l[§b§lCB§0§l]§r §fVoice mode is now §fprofessional§a. §a✔` |
| 3 | `/cb voice royal` | Chat | `§0§l[§b§lCB§0§l]§r §fVoice mode is now §froyal§a. §a✔` |
| 4 | `/cb voice minimal` | Chat | `§0§l[§b§lCB§0§l]§r §fVoice mode is now §fminimal§a. §a✔` |
| 5 | `/cb voice arabic` | Chat | `§0§l[§b§lCB§0§l]§r §fVoice mode is now §farabic§a. §a✔` |
| 6 | `/cb voice silly` | Chat | `§0§l[§b§lCB§0§l]§r §fVoice mode is now §fsilly§a. §a✔` |
| 7 | `/cb voice badmode` | Chat | `§0§l[§b§lCB§0§l]§r §cUnknown voice mode §f'badmode'§c. Use: friendly, professional, royal, minimal, arabic, silly. §4✖` |
| 8 | Open VOICE_PICKER GUI | GUI | VOICE_PICKER opens showing mode options |
| 9 | After changing voice, create a block | Chat | Creation message uses new voice mode wording |
| 10 | `/cb diagnostics` | Disk | ZIP at `config/customblocks/diagnostics/cb-diagnostics-<timestamp>.zip` |
| 11 | `/cb unsuppress` | Chat | Error suppression cleared |
| 12 | `/cb palette list` | Chat | Lists saved palettes |
| 13 | `/cb palette add <color>` | Chat | Color added |
| 14 | `/cb palette remove <color>` | Chat | Color removed |
| 15 | `/cb palette clear` | Chat | Palette cleared |
| 16 | `/cb recent` | GUI | RECENT_GUI opens |
| 17 | `/cb find <query>` | Chat | Search results shown |
| 18 | `/cb search <query>` | GUI | SEARCH_PICKER opens |
| 19 | `/cb history` | GUI | HISTORY_GUI opens |
| 20 | `/cb note test_block "My note"` | Chat | Note attached to block |
| 21 | `/cb ai` | GUI | AI_GEN opens |
| 22 | `/cb customcolor` | GUI | CUSTOM_COLOR_STUDIO opens |
| 23 | `/cb screenshot` | Chat | Screenshot/export PNG initiated |
| 24 | `/cb exportpng test_block` | Disk + Chat | PNG exported |
| 25 | `/cb template save mytemplate` | Chat | Template saved |
| 26 | `/cb template apply mytemplate test_block` | Chat | Template applied |
| 27 | `/cb template list` | Chat | Templates listed |
| 28 | `/cb template delete mytemplate` | Chat | Template deleted |

### Edge Cases

| # | Scenario | Expected |
|---|---------|----------|
| E1 | Voice lang file missing for a mode | Falls back to friendly mode strings |
| E2 | diagnostics — incidents/ empty | ZIP created; incidents/ folder is empty inside |

### Not Yet Implemented

```
ASSISTANT_CONTROL is a stub — AI assistant mode control GUI not yet implemented.
AI_PICKER is a stub — AI texture picker GUI not yet implemented.
```

**Report shorthand:** `Ph18-#<row> [PASS/FAIL] <actual>`

---

## Ph19 — GUI Stubs (Not Yet Implemented)

**Status:** Stub  **Priority:** Info only

These modes appear in `GuiManager.STUB_MODES`. Opening them via commands produces no GUI or a placeholder — this is expected behavior, not a bug, unless the server crashes.

### Stub Mode Table

| Stub Mode | Trigger | Expected Behavior |
|-----------|---------|------------------|
| FIND_PORT_GUI | Button in RESOURCE_CENTER | No GUI or placeholder |
| ASSISTANT_CONTROL | `/cb ai` alternate path | No GUI or placeholder |
| DRESS_GUI | `/cb dress` | No GUI or placeholder |
| GRADIENT_GUI | `/cb gradient` | No GUI or placeholder |
| IMPORT_WIZARD | GUI import button | No GUI or placeholder |
| RETEXTURE_WIZARD | GUI retexture path | No GUI or placeholder |
| AI_PICKER | AI texture path | No GUI or placeholder |
| DROP_CONFIG | GUI drop config button | No GUI or placeholder |
| PERMISSIONS_SUMMARY | GUI permissions path | No GUI or placeholder |

### Not Yet Implemented

```
All 9 modes above are stubs.
File bugs only if a stub causes a SERVER CRASH — not for missing GUI.
```

**Report shorthand:** `Ph19-STUB-<mode> [CRASH/NO_CRASH] <actual>`

---

## Ph20 — Sensory Matrix (Sound & Particles)

**Status:** Implemented  **Priority:** Critical

### Setup

```
# Ensure soundsEnabled=true and particlesEnabled=true in config
# All sounds via FeedbackHelper; respects per-category config maps
```

### Main Tests

| # | What To Do | Channel | Expected |
|---|-----------|---------|----------|
| 1 | Click any GUI button | In-game | 🔊 `SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME` vol 0.6 pitch 1.25 + ✨ ENCHANT x6 around player |
| 2 | Successfully create a block | In-game | 🔊 `SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP` vol 0.8 pitch 1.0 + ✨ COMPOSTER x12 |
| 3 | Use a tool (square, triangle, chisel) on a block | In-game | 🔊 `SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value()` vol 0.85 pitch 1.0 + ✨ SOUL_FIRE_FLAME x4 |
| 4 | Trigger an error (bad command arg) | In-game | 🔊 `SoundEvents.BLOCK_NOTE_BLOCK_BASS.value()` vol 1.0 pitch 0.7 + ✨ SMOKE x4 |
| 5 | Trigger dangerous action (bulk delete confirm) | In-game | 🔊 `SoundEvents.BLOCK_NOTE_BLOCK_BASS.value()` vol 1.0 pitch 0.5 + ✨ LARGE_SMOKE x12 |
| 6 | `/cb backup create` | In-game | 🔊 `SoundEvents.ENTITY_PLAYER_LEVELUP` vol 0.6 pitch 1.4 + ✨ ENCHANT x4 |
| 7 | Complete a bulk operation | In-game | 🔊 `SoundEvents.BLOCK_BEACON_ACTIVATE` vol 0.8 pitch 1.0 + ✨ HAPPY_VILLAGER x10 |
| 8 | Resource pack rebuilds after texture change | In-game | 🔊 `SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME` vol 0.35 pitch 1.15 + ✨ ENCHANT x4 |
| 9 | Set `sounds=false` in config, repeat row 1 | In-game | No sound plays; particles still appear |
| 10 | Set `particles=false` in config, repeat row 1 | In-game | No particles; sound still plays |
| 11 | Set sound category `gui=false`, repeat row 1 | In-game | No sound for GUI clicks specifically |
| 12 | Royal Directive check: button click with no sparkle | In-game | FAIL — if ENCHANT particles are absent on any button click, feature is incomplete |

### Edge Cases

| # | Scenario | Expected |
|---|---------|----------|
| E1 | Achievement fires — what sound plays | 🔊 `SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP` via GuiManager.playSuccess |
| E2 | Multiple rapid GUI clicks | Sound fires each time (no deduplication) |
| E3 | particlesEnabled=false | Zero particles; FeedbackHelper checks `CustomBlocksConfig.particlesEnabled` |

### Not Yet Implemented

```
None — all FeedbackHelper methods are fully implemented.
```

**Report shorthand:** `Ph20-#<row> [PASS/FAIL] <actual>`

---

## Global Known Bugs

| # | Bug | Phase | Impact | Status |
|---|-----|-------|--------|--------|
| B1 | Friend cannot connect to server (join issues) | Any | High | Confirmed by user |
| B2 | Placed custom blocks vanish from world | Ph02 | High | Confirmed by user |
| B3 | GIF texture crashes server | Ph06 | Critical | Confirmed by user |
| B4 | Frequent connection resets | Any | High | Confirmed by user |
| B5 | IP detection fails for RP download link | Ph10 | Medium | Expected on Docker/MCServerHost |

---

## Sensory Quick-Reference

| Event | Sound Constant | Vol | Pitch | Particle | Count |
|-------|---------------|-----|-------|----------|-------|
| GUI Click | `SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME` | 0.6 | 1.25 | ENCHANT | 6 |
| Success | `SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP` | 0.8 | 1.0 | COMPOSTER | 12 |
| Tool | `SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value()` | 0.85 | 1.0 | SOUL_FIRE_FLAME | 4 |
| Error | `SoundEvents.BLOCK_NOTE_BLOCK_BASS.value()` | 1.0 | 0.7 | SMOKE | 4 |
| Danger | `SoundEvents.BLOCK_NOTE_BLOCK_BASS.value()` | 1.0 | 0.5 | LARGE_SMOKE | 12 |
| Save | `SoundEvents.ENTITY_PLAYER_LEVELUP` | 0.6 | 1.4 | ENCHANT | 4 |
| Bulk Done | `SoundEvents.BLOCK_BEACON_ACTIVATE` | 0.8 | 1.0 | HAPPY_VILLAGER | 10 |
| RP Rebuild | `SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME` | 0.35 | 1.15 | ENCHANT | 4 |

---

## Definition of Done

| Criterion | Requirement |
|-----------|------------|
| Chat prefix | Every chat message starts with `§0§l[§b§lCB§0§l]§r ` |
| Success suffix | Every success message ends with `§a✔` |
| Error suffix | Every error message ends with `§4✖` |
| Info suffix | Every info message ends with `§e✦` |
| Warn suffix | Every warn message ends with `§6⚠` |
| GUI Royal Directive | Header rows 0-8, content rows 9-35, footer rows 45-53 |
| Legendary items | At least one Echo Shard, Nether Star, or Dragon Egg in GUI header or footer |
| Sound on click | Every GUI button click fires BLOCK_AMETHYST_BLOCK_CHIME |
| Particles on click | Every GUI button click spawns ENCHANT x6 |
| Undo available | Every destructive action pushes to undo stack |
| Config persists | After `/cb config <key> <value>`, value survives `/cb reload` |
| Achievements fire once | No achievement fires twice for the same player |
| Stub = no crash | Stub modes produce no server crash (warn or no-op only) |

---

## How to Report

Use this format for every bug:

```
Ph<NN>-#<row> [FAIL]
Expected: <copy exact Expected cell text>
Actual:   <copy exact text from chat/game>
Steps:    <numbered list of exactly what you did>
Server:   <Minecraft version, mod version>
```

Example reports:

```
Ph01-#1 [FAIL]
Expected: §0§l[§b§lCB§0§l]§r §f'My Test' created! §7(slot 1) §a✔
Actual:   [CB] 'My Test' created (slot 1)
Steps: 1. /cb create test_block "My Test" https://i.imgur.com/xyz.png
Server: MC 1.21, CustomBlocks 1.0
```

```
Ph20-#1 [FAIL]
Expected: BLOCK_AMETHYST_BLOCK_CHIME sound + ENCHANT x6 particles
Actual:   No particles visible, sound plays normally
Steps: 1. Open /cb gui  2. Click any header button
Server: MC 1.21, CustomBlocks 1.0
```

```
Ph11-#2 [FAIL]
Expected: §0§l[§b§lCB§0§l]§r §7Nothing to undo. §e✦
Actual:   No message sent to chat
Steps: 1. Fresh player with no actions  2. /cb undo
Server: MC 1.21, CustomBlocks 1.0
```

```
Ph16-#1 [FAIL]
Expected: Title §6§l🏆 Achievement Unlocked! + Subtitle §6§l✦ First Creation!
Actual:   No title shown on first block create
Steps: 1. New player, never created a block  2. /cb create x "X" <url>
Server: MC 1.21, CustomBlocks 1.0
```

```
Ph19-STUB-DRESS_GUI [CRASH]
Expected: No crash; no-op or placeholder only
Actual:   Server crash: NullPointerException in GuiManager
Steps: 1. /cb dress
Server: MC 1.21, CustomBlocks 1.0
```

---

## How to Request Improvements

Use this format:

```
IMPROVE Ph<NN>-#<row>
Request: <what should change>
Reason:  <why it matters>
```

Example requests:

```
IMPROVE Ph01-#1
Request: Add slot number to creation action bar (show "Slot #1")
Reason:  Hard to tell which slot a new block was assigned without opening /cb list
```

```
IMPROVE Ph20-#1
Request: Make GUI click particle count configurable per-category like sounds
Reason:  Some servers want minimal particles but still want click sounds
```

```
IMPROVE Ph13-#11
Request: Show per-step result in chat after script run (passed/failed per step)
Reason:  Completion message shows totals only; debugging which step failed requires guessing
```

```
IMPROVE Ph15-#3
Request: Show block name in import success message, not just "Imported from Cloud Vault"
Reason:  Confirms you imported the correct block when codes are shared between players
```

```
IMPROVE Ph19-STUB-DRESS_GUI
Request: Implement DRESS_GUI placeholder showing "Coming soon" with a back button
Reason:  /cb dress currently produces no user feedback at all
```

---

*Guide generated from source code only: CustomBlockCommand.java, FeedbackHelper.java, ChatHelper.java, GuiManager.java, AchievementManager.java, CustomBlocksConfig.java, UndoManager.java, voice_friendly.json — no plan documents used.*
