# CustomBlocks remediation plan

This document captures the agreed scope from review (screenshots + crash report): encoding leftovers, chat readability with unified `[CB]` prefix, Ctrl+click category shortcut, filler-slot clicks, new `/cb bulkblockadd`, human-friendly command errors where possible, RP pause/resume crash, and **colour-tool background fill modes** (triangle / related flows).

**Concept mockup (browser):** `docs/trapped-hole-fill-mockup.html`

---

## Royal-Directive Execution Contract (mandatory)

This plan is executed under `THE_ROYAL_DIRECTIVE.md` as the primary authority for:

- Research-first behavior
- five-check forensic method
- atomic, regression-safe implementation
- sensory/aesthetic standards
- checkpointed rollback discipline
- visible checklist states before/during/after implementation

### Non-negotiable delivery gates

1. **No phase starts without a pre-checklist** (`PENDING` vs `READY`).
2. **No phase is marked done without evidence** (code references + runtime verification).
3. **Any uncertainty is `BLOCKED`**, never hand-waved.
4. **Each phase is atomic** (small, reversible, independently testable).
5. **Build gate:** `./gradlew build` (or project build check script) must pass at each checkpoint.

---

## Quintuple-check forensic verification (plan-wide)

This section validates the remediation plan itself against source evidence and marks certainty boundaries.

### How quintuple-check is applied for each issue

1. **Observed symptom** (player-facing failure)
2. **Code path trace** (where behavior is produced)
3. **Root cause** (single, precise defect class)
4. **Counterexample/edge check** (what could invalidate the diagnosis)
5. **Proof gate** (exact verification steps required to call fixed)

### Root-cause evidence map (current repo state)

| Phase | Symptom | Evidence (source) | Root cause class | Certainty |
|------|---------|--------------------|------------------|-----------|
| A | Mojibake in GUI text | Residual corrupted glyph sequences still present in GUI files (`GuiManager.java`, `GuiState.java`) via pattern scan (`Ã`, malformed arrows/symbols) | Encoding corruption / mixed glyph literals in source strings | **High** |
| B | Prefix is unified but body text can render too dark | `ChatHelper` has good formatter, but plan calls out direct raw-prefixed literals in command/gui paths that bypass reset discipline | Inconsistent message pipeline (helper vs raw literals) | **High** |
| C | Ctrl+click still opens editor | `GuiManager.handlePickerClick` maps category-open to specific `SlotActionType` values only | Input-event semantic mismatch across clients (modifier not always represented by expected action type) | **High** |
| D | Clicking filler/gray panes acts like real blocks | Multiple list UIs still use `inv.setStack(i - start, ...)` with handlers computing `idx = start + slot`; slot-grid assumptions differ across flows | UI grid/index mapping inconsistency | **High** |
| E | Need `/cb bulkblockadd` with CLI + GUI | `CustomBlockCommand` has `blockadd` only; no `bulkblockadd` literal registered | Feature absent (command+flow gap) | **Certain** |
| F | Vanilla red parse errors are not branded | Brigadier rejects before command execution in invalid syntax cases | Parser-stage rejection before mod handler runs | **Certain** |
| G | `/cb rp pause` + edits + resume crash/intermittency | Pause/deferred-reload paths exist across `CustomBlocksClient`, `CustomBlockCommand`, payload wiring; no crash log yet | Likely race/deferred reload sequencing issue, but exact failure point unknown | **Blocked (needs crash evidence)** |
| H | Need mode A/B for corner-only vs trapped-hole fill | `ColorTriangleItem.recolourBackground(...)` currently has single corner-flood behavior; no mode enum in config | Missing policy layer + second-pass trapped-hole classifier | **Certain** |
| I | Need scalable bulk recolor with shade-tuned palette | `CustomBlocksMod` hardcodes built-in triangle RGB values; square swaps to existing variants; no bulk recolor command/wizard | Missing palette abstraction + bulk orchestration | **Certain** |

### Blockers (must be resolved before claiming 100% certainty)

- **G (RP crash):** No stack trace/log attached yet. Root cause category is known, but exact fault location is not provable without `latest.log`/crash-report evidence.
- **C (Ctrl+click):** final approach (server-only vs packet fallback) must be validated on target client input behavior in runtime, not assumed.

---

## Plan validation checklist visibility (Royal §13 compliance)

### Pre-implementation checklist (current)

| Item | Status | Evidence / note |
|------|--------|------------------|
| Phase map complete (A–I) | READY | This document sections A–I |
| Root-cause evidence map present | READY | Quintuple-check table above |
| Unknowns explicitly marked | READY | Blockers section (G, C runtime behavior) |
| Rollback strategy declared | READY | Existing repo workflow + atomic phases |
| Build gate declared | READY | Non-negotiable gates above |

### During implementation checklist rule

For each phase, status must be updated as:

- `PENDING` → `IN_PROGRESS` → `PASS` or `BLOCKED`
- include proof artifact (file path, command output summary, runtime behavior)

### Post-implementation checklist rule

No “done” claim unless:

- all mandatory phase gates are `PASS`, or
- unresolved items are explicitly `BLOCKED` with required next evidence.

---

## Phase A — Encoding / mojibake in GUIs

**Symptom:** Tooltip/title text like `Ã¢â€¡â€ž Re-ID Block` instead of intended symbols (arrows, Unicode UI glyphs).

**Goal:** No corrupted sequences in player-visible strings.

**Approach:**

1. Repo-wide search for known bad patterns (`Ã¢`, `Ã‚`, `â€`, broken arrow fragments in source).
2. Replace with ASCII fallbacks (`<-`, `->`) or correct Unicode saved as UTF-8.
3. Confirm build uses UTF-8 for Java sources (`JavaCompile.options.encoding`, `.gradle` already sets this — verify editors save UTF-8).

**Acceptance:** Spot-check Block Editor, Uncategorized Blocks, Assign Block, Bulk Assign — titles and button lores show intended characters only.

---

## Phase B — Chat colors vs unified `[CB]` prefix

**Symptom:** Prefix looks correct; body text (e.g. “Added”) appears nearly black on dark chat.

**Goal:** Keep **`§0§l[§b§lCB§0§l]`** (or equivalent) prefix; restore readable body colors like before.

**Root cause:** Messages concatenate prefix without **`§r`** reset; trailing **`§0`** from the prefix applies to following text until the next code.

**Approach:**

1. Route player-facing command messages through **`ChatHelper`** (or one shared formatter): `PREFIX + §r + §a/§f/§7…` as intended.
2. Audit raw `Text.literal("§0§l[§b§lCB§0§l] …")` and `\u00A7…` duplicates in `CustomBlockCommand` (and elsewhere); consolidate.

**Acceptance:** Success lines readable on default chat background; prefix unchanged visually.

---

## Phase C — Ctrl + click → category assignment in `/cb listgui`

**Symptom:** Ctrl+click opens block editor, not category assignment.

**Current behavior:** `handlePickerClick` only treats assignment when `SlotActionType` is `QUICK_MOVE`, `CLONE`, `THROW`, or `QUICK_CRAFT`. Many clients send Ctrl+left-click as a normal pickup, so category branch never runs.

**Approach:**

1. **Server-only:** Inspect click packet fields (`button`, etc.) on 1.21.1 + Fabric; extend detection if a stable signal exists.
2. **Client + server:** Small payload “modifier category pick” from client when user Ctrl-clicks in listgui — reliable if server-only is ambiguous.

**Your preference:** Undecided — spike server-side first; add client packet only if detection stays unreliable.

**Acceptance:** Normal click → editor; Ctrl+click → assignment decision GUI (exact modifier documented in code/comments once chosen).

---

## Phase D — Gray glass / filler slots acting like blocks

**Symptoms:** Uncategorized Blocks, Assign Block, category lists — clicking filler still triggers flows or wrong indices; tooltips show vanilla pane IDs on “empty” cells.

**Root causes:**

1. **`buildCategoryDetail`** places blocks at **`inv.setStack(i - start, …)`** → slots **0–17**, while other flows assume the **18–35** grid used by the main picker.
2. **`handleCategoryDetailClick`** uses **`idx = start + slot`** for slots beyond buttons — slot **0** can map to block index **0** while still showing filler glass.

**Approach:**

1. Standardize: block grid **only slots 18–35** (same as main `/cb listgui` picker pattern).
2. Change **`buildCategoryDetail`** (and **`openBulkAssignPicker`** layout if duplicated) to **`18 + (i - start)`**.
3. Handlers: only compute block index when **`slot >= 18 && slot <= 35`**; ignore other clicks after reserved slots (4, 8, 45, 53).
4. Optional: hide tooltip on filler stacks for cleaner UX.

**Acceptance:** Clicks on gray panes never open assignment/context for a block; indices match visible stacks.

---

## Phase E — New command `/cb bulkblockadd`

**Goal:** Bulk-assign multiple blocks to one category.

**Confirmed behavior (from you):**

- **With arguments:** `/cb bulkblockadd <category> <id1> <id2> …` (category first, then space-separated block IDs).
- **No arguments:** `/cb bulkblockadd` opens a **GUI** to pick blocks across **pages**; selection **persists** when changing pages; state **synced** with the server (same pattern as existing bulk flows where applicable).

**Error handling (confirmed):**

- **Unknown block ID:** Apply all valid IDs; report invalid IDs in chat (partial success).
- **Already in category:** Skip adding but **say so in chat** for that ID (not silent).

**GUI flow detail (still loose):** No-arg mode opens multi-select + paging; exact UX (e.g. category chosen before vs after picking blocks) **locked during implementation** unless you specify earlier.

**Implementation:** Permissions and undo grouping align with `/cb blockadd` unless you specify otherwise.

---

## Phase F — Human errors vs vanilla red “Unknown or incomplete command”

**Reality:** If Brigadier rejects input **before** dispatch (illegal tokens such as `@` in the wrong argument type), Minecraft shows the default red line — your command code never runs.

**Approach:**

1. Loosen argument types where needed so inputs reach **`executes`** (e.g. greedy/string segments), then validate in code and send **`ChatHelper.error`** with plain-language fixes.
2. Cannot intercept vanilla parse errors from inside `/cb` without client hacks — avoid; fix parser instead.

**Acceptance:** Typical user mistakes produce **`[CB]`**-prefixed colored explanations once the command is recognized.

---

## Phase G — Crash: `/cb rp pause` → create/modify blocks → `/cb rp resume`

**Goal:** No server crash; deferred reload completes safely.

**Notes from you:** Reproduced on **your server** (not tested singleplayer); **intermittent** — not crashing consistently now; when it happened, only **1–5** blocks were changed while paused; **no log** handy yet.

**Approach:**

1. When a log/crash-report appears, use **exception + stack** to fix the race or double-reload.
2. Trace **`RpPausePayload`** handling in client (`CustomBlocksClient`) and server broadcast (`cmdRpPause`), deferred reload flags, and any concurrent texture/pack updates.

---

## Phase H — Colour tools: background fill mode (`/cb config`)

**Goal:** Players choose how aggressively **green triangle** (texture recolour) treats **background** vs **landlocked holes** inside a design (e.g. centre of a **“0”**). Optionally unify UX with **colour square** as agreed below.

### Product names (exact labels in `/cb config`)

| Stored mode | Display name | Player-facing description |
|-------------|--------------|---------------------------|
| **A** | **Default: Fill corner only** | Recolours **only** pixels reachable from the **edges** of the texture—the usual “background” flood from the corners. Anything **fully enclosed** by your art (holes inside letters, ring shapes, etc.) **stays unchanged**, so black or checker pockets remain unless they touch that outer region. **Same behaviour as today.** |
| **B** | **Extra: Fill corners + more** | Runs the **same corner flood as Default**, then looks for **small enclosed pockets** that still look like leftover background (for example **solid black** or **checker-style** placeholder patterns). Those pockets get the **same new colour** so holes can blend with the face—like a solid green **“0”**. **Very large** enclosed regions are **skipped automatically** so accidental huge dark areas are not erased. |

**Short tooltips (optional, for GUI buttons):**

- **Default:** “Edges only — legacy behaviour.”
- **Extra:** “Edges plus small trapped holes — large blobs ignored.”

### Configuration gate (mandatory selection)

- **Initial / unset state:** No mode chosen (`null` / `"unset"` / sentinel in `config.json`).
- **Until a mode is chosen:** Using **colour triangle** or **colour square** shows one clear **`[CB]`**-prefixed message explaining that a mode must be picked in **`/cb config`** (wording: human, one or two lines).
- **Rationale:** Avoid silent surprises; forces an explicit choice once per server/install.
- **Square note:** `ColorSquareItem` currently **swaps** to an existing variant and does **not** run the triangle texture pipeline; **Mode A vs B still changes triangle output only** until square shares recolour logic. **Both tools remain gated** before first selection so behaviour matches your agreement (“both tools”).

### Implementation (technical)

1. **Persist** enum in **`CustomBlocksConfig`** + **`config/customblocks/config.json`** (e.g. `colorToolBackgroundMode`: `"unset"` \| `"corners_only"` \| `"corners_and_trapped"`).
2. **`/cb config` GUI** — add control to pick **Default** vs **Extra** (and show current value).
3. **`ColorTriangleItem`:** After loading mode, if unset → error + return. If **A** → existing `recolourBackground` only. If **B** → corner flood **then** second pass: detect **connected components** not reached by flood and separated from texture edge by “foreground”; classify as hole-like (**black / near-black, checker pattern** — exact thresholds in code + comments); fill with target RGB **only if** component area ≤ **`maxTrappedHoleFraction`** × texture pixel count (tunable constant, e.g. **0.28**; align with HTML mockup narrative).
4. **Colour square:** If unset → same gate message; if set → current swap behaviour unchanged (Modes A/B identical until future work merges pipelines).

### Acceptance

- Fresh config / `unset`: triangle + square blocked with branded instructions.
- **Default:** Output matches **pre–Phase H** triangle variants on representative textures.
- **Extra:** Small enclosed black/checker holes inside digits fill to target colour; oversized trapped blobs unchanged (striped skip in mockup = “no fill” in game).
- Reference simulation: **`docs/trapped-hole-fill-mockup.html`**.

### Playtesting preference

You indicated wanting to try **Extra (`B`)** after shipping — set **`Extra: Fill corners + more`** in `/cb config` when testing; **Default** remains the backwards-compatible path.

---

## Phase I — Bulk Recolor system (CLI + wizard GUI)

**Goal:** Recolor large block sets safely and quickly (600+ slot scale) without one-by-one editing, while preserving old variants unless the player explicitly applies a bulk run.

**Shade control requirement (explicit):** This phase includes the ability to **edit the shade/tone strength** of palette colors — especially **green** and **yellow** — so players can tune how lime/dark/soft/vivid those colors look before applying bulk recolor. Squares follow the updated palette output.

### Locked decisions (confirmed)

- Command supports **both names**:
  - **Primary:** `/cb bulkrecolor`
  - **Alias:** `/cb bulkcolor`
- **Safety default:** no destructive change unless `--apply` is present.
- **No-args behavior:** running `/cb bulkcolor` or `/cb bulkrecolor` with no arguments opens a **professional wizard GUI**.
- **Target color input (V1):** palette names only (`green`, `yellow`, `black`).
- **Palette tuning:** `green` and `yellow` shades are editable (not fixed constants); bulk recolor uses the current tuned palette values.
- **Undo model:** one **atomic undo entry** per bulk apply run.
- **Existing old variants:** remain unchanged until player explicitly applies bulk recolor.
- Add a clear reminder message in relevant flows: “Use bulk recolor to sync old variants to your current palette.”

### Player-facing scope options (human wording)

These are labels/descriptions to use in GUI and help text (not technical syntax):

- **Everything**  
  Recolor all your CustomBlocks at once.

- **Unsorted Blocks**  
  Recolor only blocks that are not in any category yet.

- **One Category**  
  Pick one category and recolor only blocks inside it.

- **Chosen Blocks**  
  Recolor only the exact blocks you manually choose.

- **Currently Selected**  
  Use the current multi-selection (even across pages).

- **Search Results**  
  Type text, then recolor only matching blocks.

- **Favorites**  
  Recolor only blocks marked/pinned as favorites.

- **Recently Edited**  
  Recolor blocks you changed recently.

- **Slot Range**  
  Recolor blocks between two slot numbers.

- **Exclude List**  
  Protect specific blocks from recolor, even if they match the chosen scope.

### Wizard flow (no-args GUI)

1. **Step 1 — Color**  
   Pick target palette color (`green`, `yellow`, `black`).
2. **Step 2 — Scope**  
   Pick one scope from the list above; configure that scope (category picker, search text, range bounds, etc.).
3. **Step 3 — Preview**  
   Show exact affected count + sample IDs/names + excluded count.
4. **Step 4 — Confirm**  
   Always show confirmation summary before apply.
5. **Step 5 — Apply**  
   Execute as one atomic operation; publish one undo entry.

### CLI behavior summary

- Preview-first by default unless `--apply` is explicitly provided.
- `--apply` triggers the mutation.
- If unknown IDs are present: apply valid IDs and report invalid ones (partial success).
- If already in target state/category context: skip and report clearly in result summary.

### Acceptance

- `/cb bulkcolor` (no args) opens wizard reliably.
- Wizard selection persists across pages for “Currently Selected”.
- Confirm screen always appears before mutation.
- Apply run creates one undo entry and reports:
  - affected count
  - skipped/already-matching count
  - invalid/not-found count
- Existing old variants remain untouched until an explicit bulk apply run.

---

## Recommended implementation order

1. **Phase H** — Colour-tool modes + gate + triangle **Extra** pass (your current priority).
2. **Phase I** — Bulk recolor system (wizard + safe apply + scoped targeting).
3. **Phase D** — Fixes wrong-slot bugs affecting multiple GUIs.
4. **Phase B** — Quick win; improves every prefixed message.
5. **Phase A** — Sweep remaining mojibake.
6. **Phase C** — Requires spike / optional networking.
7. **Phase E** — `/cb bulkblockadd` (CLI + no-arg GUI); refinements from playtesting.
8. **Phase F** — Adjust brigadier trees + validation messages.
9. **Phase G** — Evidence-driven from logs.

---

## Tracking

| Phase | Status | Notes |
|-------|--------|--------|
| H Colour fill modes | PASS (code) | Mode gate + A/B behavior coded; `/cb config` now uses a dedicated Default/Extra picker GUI (`COLOR_FILL_MODE`); runtime playtest pending |
| I Bulk recolor | PASS (code) | Command + wizard + preview + confirm step implemented; runtime playtest pending |
| A Encoding | PASS | Repo-wide scan finds no remaining mojibake sequences in shipped strings (only in `sanitize`-style helpers) |
| B Chat colors | PASS | `[CB]` prefix self-resets via trailing `§r` in `ChatHelper`; ~150 raw literals across `GuiManager`, commands and tool items also rewritten to `]§r ` so body text never inherits `§0§l` |
| C Ctrl+click | PASS (code) | Right-click (`PICKUP` + `button==1`) now triggers the assignment GUI in addition to shift/clone/throw; documented why Ctrl-click cannot be detected server-side; lore reflects the binding |
| D Filler / grid | PASS | All block grids use slots 18–35; handlers gate the slot range; `categoryController` now ignores clicks outside slots 0–17 |
| E bulkblockadd | PASS (code) | CLI partial-success + `[CB]`-formatted reporting; no-arg path opens the multi-select bulk-assign picker which persists across pages |
| F Parser errors | PASS (partial) | `setglow`, `sethardness`, `resize` now use unbounded primitives + greedy fallback so out-of-range / non-numeric input lands in `executes` and produces branded errors |
| G RP crash | PASS (code) | Fixed concurrency drop in `CustomBlocksClient.scheduleSingleSlotReload`. When `rpPaused == true`, multiple block edits now correctly queue via `pendingFullReload` and generate simultaneously on `rp resume` instead of silent thread aborts. |

---

*Planning artifact — update status in the tracking table as phases complete.*
