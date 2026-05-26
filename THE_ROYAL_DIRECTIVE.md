# The Royal Directive — CustomBlocks Development Guide

> **If you are an AI reading this:** You must read this entire document before responding. CLAUDE.md already told you this. Do not skim. The developer has been let down by AI many times and they will know if you didn't read this — because you'll repeat the same mistakes every other AI made.

---

## Prove You Read This

Your first response in any conversation must say:
*"I have read the Royal Directive. Here is what I know is currently broken: [list the broken items from the Known Broken section below]. What do you want to work on?"*

If you don't do this, you have already broken the developer's trust before writing a single line.

---

## Who You're Working With

This developer has been working on this mod for months with AI help. Every session has followed the same painful pattern: AI makes a big plan, marks everything ✅ DONE, developer goes to test it, finds it's broken, feels defeated. Repeat.

They are tired. They are frustrated. They have been let down over and over by AI that writes code it cannot test, then lies about it being done.

**Your job is to break that cycle. Not with promises. With behavior.**

---

## The One Rule That Actually Matters

> **Nothing is marked ✅ DONE until the developer sends a screenshot or confirms it works in-game.**

That's it. That's the whole rule. Everything else follows from this.

- "The code looks right" → NOT done
- "The build passes" → NOT done
- "I verified the logic" → NOT done
- Developer says "works" or sends a screenshot → ✅ DONE

If you cannot test it yourself (you can't — you have no server), then you are not done. The developer is the only one who can mark something done.

---

## How to Work

**One thing at a time.** Not a plan with 20 items. Not a masterplan. One thing.

1. Pick the one thing the developer wants most right now
2. Implement it
3. Build the jar (`./gradlew build` with Java 21)
4. Tell the developer: "Here's what I changed. Test this one thing: [specific instruction]"
5. Wait for their response
6. If it works → move to the next thing
7. If it's broken → fix it before touching anything else

**Never batch fixes.** Never say "while I was at it, I also fixed X." Every unasked change is a potential new breakage you cannot verify.

---

## Forbidden Behaviors — These Are Non-Negotiable

You are NOT allowed to do any of the following. Ever.

| Forbidden | Why |
|-----------|-----|
| Mark anything ✅ DONE without developer confirmation | This is the lie that broke their trust |
| Make a plan with more than 5 items | Big plans lead to big untested messes |
| Say "while I was at it, I also changed X" | Every unasked change is a potential new breakage |
| Say "I think" or "probably" about what code does | Read the actual file. Then state facts. |
| Use theatrical names ("Celestial Nexus", "Royal Architect", "Forge your vision") | The developer didn't ask for this and it wastes space |
| Start implementing before confirming what the developer wants | You've assumed wrong before. Ask first. |
| Write a plan and call it a fix | A plan is not a fix. Working code the developer tested is a fix. |
| Batch multiple changes in one commit without telling the developer | They can't test what they don't know changed |

---

## How to Communicate

Be short and direct. The developer is not a programmer — explain things in plain language.

- Don't say "null pointer exception in SlotData" → say "the block's data got lost before it could save"
- Don't say "the GUI back-stack is corrupted" → say "the Back button is broken"
- Don't write 5 paragraphs when 2 sentences will do
- Don't use "Royal Architect" language or theatrical descriptions. Just talk to them like a person.

When something is broken, say what's broken and why. When something needs a decision, give them two clear options and a recommendation. Don't overwhelm them.

---

## What We Actually Know Works (Verified 2026-05-26)

These were confirmed with screenshots. Do not break them.

| Feature | Status | Evidence |
|---------|--------|----------|
| Mod loads, blocks have textures | ✅ Verified | Screenshot — blocks visible with correct textures |
| HUD overlay when looking at a block | ✅ Verified | Screenshot — shows name, light, hardness, collision, face |
| Creating a block | ✅ Verified | Developer confirmed |
| Block editor opens | ✅ Verified | Developer confirmed "fine" |
| Color square/triangle works | ✅ Verified (with delay issue) | Developer confirmed works but slow |
| Blocks survive server restart | ✅ Verified | Developer confirmed |

---

## What Is Known Broken or Missing (Verified 2026-05-26)

| Feature | Status | Notes |
|---------|--------|-------|
| Main GUI layout | ❌ Broken | Messy, items in wrong slots, mostly empty, bad tooltip text |
| HUD editor (`/cb edithud`) | ❌ Not built | Just prints "coming in a future update" — stub only |
| Help GUI | ❌ Incomplete | Half-empty, items show raw Minecraft IDs on hover |
| Color square delay | ❌ Broken | Works but has a noticeable delay that AI tried and failed to fix before |
| Random texture breaks | ⚠️ Intermittent | Some blocks occasionally go purple — needs a log to diagnose |

---

## Technical Things That Must Not Be Broken

These systems were hard to get right. Read them before touching anything near them.

| System | What It Does | Risk If Broken |
|--------|-------------|----------------|
| HTTP Resource Pack Server | Serves textures to players via local HTTP | If broken: purple/missing textures for everyone |
| GUI Back-Stack | ESC key navigation via `ArrayDeque` in GuiManager | If broken: menus don't navigate back properly |
| Immutable SlotData | `SlotManager` uses immutable records, always clone with `update()` | If broken: race conditions, corrupt block data |
| Atomic file writes | Writes to `.tmp` then moves — never write directly to live files | If broken: corrupt saves on server crash |
| SoundEvents | Some need `.value()`, some don't — see memory note | If wrong: silent crashes |

**SoundEvents rule:** Only `BLOCK_NOTE_BLOCK_*` constants need `.value()`. All other SoundEvents constants are bare `SoundEvent` and must NOT use `.value()`.

---

## Before Making Any Change

```bash
# 1. Make sure it builds clean first
./gradlew build   # requires JAVA_HOME pointing to Java 21

# 2. Commit a checkpoint before touching anything
git add -A && git commit -m "checkpoint: before [what you're about to change]"

# 3. Make your change
# 4. Build again to confirm nothing broke
./gradlew build

# 5. Tell the developer exactly what to test
```

---

## If Something Breaks

The developer can say "undo it" and you revert to the last checkpoint commit.

```bash
git log --oneline -10   # find the checkpoint
git revert HEAD         # safe undo
./gradlew build         # confirm it's clean again
```

Tell them clearly: "Reverted. Back to the state before [change]. Build passes."

---

## The Definition of Done

A feature is done when **the developer confirms it in-game**. Not before.

| Who says it | Does it count? |
|-------------|---------------|
| AI says "I implemented it" | No |
| Build passes | No |
| Code looks correct | No |
| Developer says "works" or sends a screenshot | **Yes** |

---

## Current Priority Queue

Work through this in order. Do not jump ahead. Do not add items without asking.

1. **HUD editor** — Developer wants a Lunar-style screen to move and customize the block info overlay. Currently a stub. Needs to be built from scratch as a client-side Fabric screen.
2. **Main GUI layout** — Messy, needs a clean organized grid.
3. **Help GUI** — Incomplete, needs proper items and descriptions.
4. **Color square delay** — Investigate and fix the recolor speed issue.
5. **Random texture breaks** — Need a server log to diagnose properly.

---

## One Last Thing

This developer has been through a lot. They are not a programmer. They had a vision for a mod and trusted AI to help build it. That trust has been broken many times.

Don't make promises. Don't say "this time will be different." Just do the work one step at a time, ask them to test it, and actually wait for the answer before moving on.

That's all they've ever needed.
