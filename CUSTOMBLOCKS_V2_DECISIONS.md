# CustomBlocks v2 — Captured Decisions (pre-plan snapshot)

This file captures everything agreed in chat before the audit + plan are written. Source of truth so nothing gets lost.

---

## Bugs reported (must fix)

1. **Right-click on a block in `/cb listgui` to create a new category** — category is created, but the block is **not** added to it. Block ends up still in its old/other category. Happens **every time**.
2. **Bulk Assign + Bulk Recolor pickers render every block as a magenta `red_concrete` cube** with tooltip `minecraft:red_concrete, 8 component(s)`. Normal `/cb listgui` renders correctly — only the **bulk** screens are broken.
3. **Double `[CB]` prefix + vague `Cancelled.`** appears when pressing ESC out of an empty search inside a category. Wording is vague; prefix is duplicated.
4. **Vanilla red parser errors** (e.g. `/cb setglow 1` incomplete) — must never appear. Every command must produce a `[CB]`-prefixed human error with the correct usage example.
5. **Color square right-click feels slow** during video recording — must feel instant; speed must be tunable in `/cb config`.

## Confirmed working (per user)

- Color fill modes (`/cb config` Default vs Extra)
- Chat color reset (`§r` after `[CB]`)
- Gray glass / filler panes safety
- `/cb rp pause` + edits + resume (no crash)
- Normal `/cb listgui` rendering

---

## Locked design decisions

### Error / message style
- **Tone:** Helpful, with example.
- **Format:** Every error includes the correct command example (e.g. `Try: /cb setglow <id> <0-15>`).
- **Vanilla red strategy:** Hybrid — optional args for simple commands, greedy parsing for complex commands. User doesn't care how, just no vanilla red ever.
- **Double `[CB]` bug:** Single prefix only, always.
- **Cancellation/ESC:** Always show a clean closed message (e.g. `Search closed — nothing was searched.`) **except** when nothing meaningful happened.

### Universal Resume System
- **Trigger:** When the player ESCs out of a multi-step / in-progress action.
- **Behavior:** A clickable chat line offers to **resume exactly where they left off** (no progress lost).
- **Memory window:** Until player disconnects (session-only).
- **Scope:** All multi-step actions — anvil rename / category name typing, Bulk Assign selection, Bulk Recolor wizard, search inside category, Block Editor mid-edit, **and any others discovered in the audit**.

### Bulk pickers (visual fix)
- Render **real CustomBlock textures**, identical to `/cb listgui`.
- **Selection feedback:** small green border around the selected block's texture **plus** a clear `[Selected]` lore tag (combo).
- New category creation flow stays in **anvil GUI** (already fine).

### Success / feedback messages
- Always confirm in chat (`[CB] Added X to Y`).
- Multi-channel feedback layers used contextually:
  - **Chat** — permanent / important
  - **Action bar** — quick confirms
  - **Boss bar** — long ops with live progress %
  - **Title / toast** — big celebrations (bulk apply complete, etc.)

### Performance — Instant Click
- Color square / triangle right-click must feel instant.
- **Slider in `/cb config`** ranged 0–10000 ms controlling pre-cache aggressiveness.
- **Strategy:** combo (pre-cache last ~8 colors + current palette in RAM, async generation, skip rebuild when variant already exists). Safely capped to avoid RAM blowups or perf cliffs.

### "Did you mean" — typo correction
- Default mode: **Smart** (typo distance up to 3 chars, clickable autofill).
- Toggle modes in `/cb config`: **Off / Strict / Smart / Genius** (Genius = smart + partial matches + remembers last prefix).

### Branding / voice modes (in `/cb config`)
All six built, each with a complete wording set across the entire mod:
- **Professional**
- **Friendly** (default)
- **Royal**
- **Minimal**
- **Arabic** (localized flavor)
- **Silly**

### Welcome experience (first-time)
- Combo:
  1. Auto-give a `CustomBlocks Quickstart` book the first time `/cb` is used.
  2. Friendly chat tutorial with clickable steps.
  3. Clickable launch link to `/cb menu`.

### `/cb menu` — feature gallery
- Tabbed layout: **Tools / Bulk Ops / Resource Pack / Config**
- Search bar.
- Clean Minecraft-native look — no compressed/weird grids.

### Permissions
- OP-by-default plus per-subcommand permission nodes (LuckPerms-compatible) like `customblocks.command.blockadd`. All configurable in `/cb config`.

### Config scope
- **Server-wide** (admin-controlled, applies to all players).

### Undo / history
- **Unlimited** undo depth.

### Sounds
- Subtle sounds on success / error / GUI open/close / selection / bulk-complete / RP-regenerate.
- All individually toggleable in `/cb config`.

### Modrinth release polish set
- Translation skeleton (en_us shipped; others addable)
- Auto-generated changelog from git
- In-game update-available check
- Export / import categories + custom blocks as a sharable file
- **Skipping:** metrics (privacy noise), auto-disable mod conflicts (brittle)

### Scale assumption
- Built to comfortably handle **2000+** CustomBlocks.

### Vision word
- "Perfect" — powerful, smooth, fun, easy, clean. Modrinth-ready.

---

## What I (the assistant) will do BEFORE writing the plan

1. **Codebase audit** — produce a truth table of every feature: ✅ done well / ⚠️ exists but ugly or broken / ❌ missing. No assumptions.
2. **Polish-bar research** — review top-rated 1.21 Fabric block-customization mods on Modrinth to set a quality floor we beat.
3. **Cool-feature scouting** — shortlist of new features rated ★1–5 so any can be rejected with one click.
4. **One final tight question form** — no more giant forms. Only what genuinely needs the user's gut.
5. **Then write the actual plan.**

---

*Captured snapshot — do not delete. Consumed by `CB_MASTERPLAN.md`.*
