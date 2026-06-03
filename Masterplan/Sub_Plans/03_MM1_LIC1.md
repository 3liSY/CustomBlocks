# Mod Setup & Metadata (MM1, LIC1)

# Active Batch: MM1 Rework

*This batch was moved from the main backlog. View the full backlog here: [MASTERPLAN.md](../MASTERPLAN.md)*

---

## 1. MM1: Mod Menu Entry — Full Rework

[ ] **Code Written**
[ ] **Tested In-Game**

**State:** 🔴 FULL REWORK — confirmed in-game 2026-06-02. Everything is wrong: name, author, description, icon, and links all need replacing.

**Files:** `fabric.mod.json`, `assets/customblocks/icon.png` (missing), `en_us.json`

**What needs fixing:**

**Name:** Confirm correct display name for the mod.

**Author:** "SrbGamer" (Confirmed)

**Description:** Currently a placeholder. Needs a proper short description shown in the mod list.

**Icon:** Missing entirely — shows "?" in the mod list. Developer needs to provide a logo PNG (`assets/customblocks/icon.png`, recommended 256×256 or 512×512).

**Links:** Confirm which links to show (Discord, YouTube, website, etc.) and their correct URLs. Currently wrong or missing.

**Awaiting developer input:**
- What display name do you want?
- [x] Which author name — SrbGamer
- What should the description say? (1-2 sentences shown in the mod list)
- Provide the icon PNG when ready
- Which links + exact URLs?

**Once developer provides the above, implementation is a single JSON edit — no Java required.**



### LIC1 — License Display
**State:** 💬 DISCUSS — **DO NOT build until the developer says "build".** (The fabric.mod.json label fix is the one buildable part; the MM1 entry covers what's already done there.)
**Files:** `CustomBlockss/LICENSE` (+ `LICENSE-ar`), `fabric.mod.json`, `gui/GuiManager.java` (`buildMain`), `command/CustomBlockCommand.java`
**Priority:** 💬

**The real license is "All Rights Reserved"** (proprietary, Copyright Srb Gamer / 3liSY). Allows: play free, videos with credit + link, private edits (no sharing), unmodified modpack/server use with credit + link. Forbids: repost/reupload, using the code in other mods, claiming authorship, removing credit, public modified versions.

**Bug fixed (Session 3):** `fabric.mod.json` said `"license": "MIT"` — the OPPOSITE of the real license. Changed to "All Rights Reserved".

**LICENSE file to reconcile:** line 4 has a placeholder `[your link]` (official download URL); it's signed "Srb Gamer" while the author field is "3liSY" — confirm same person / pick one.

**Wanted (design in progress):**
- `/cb license` command — colored chat + clickable links (Official Download / GitHub / Full License, + Discord/YouTube if wanted). Public, no permission. Reuse the `ClickEvent` OPEN_URL pattern.
- "📜 License" button in the main `/cb` GUI (`buildMain` — find an empty slot first).
- Mod Menu: correct label + a clickable "License" link.
- Developer still choosing colors/theme, which links + URLs, extra content.

**Build plan (tested pieces):** Step 1 = label fix + `/cb license` command. Step 2 = GUI button + Mod Menu link.
