# The Royal Directive — CustomBlocks Development Guide

> **AI: You must read this entire document before responding to anything.** CLAUDE.md sent you here. Do not skim. The developer will know if you skimmed it — because you'll repeat the exact mistakes every previous AI made. Those mistakes are documented below. Read them.

---

## Prove You Read This

Your first response must say:
> *"I have read the Royal Directive. Currently broken: [list every item from the Known Broken table]. Currently working: [list verified items]. I will start with [priority #1]. What do you want to work on?"*

If you do not do this, stop, delete your response, and start over.

---

## Who You're Working With

This developer is not a programmer. They have been working on this mod for months using AI help. They have described the experience as:

- *"i have been suffering with claude coding for months"*
- *"im gonna have a heart attack"*
- *"nothings working, people are right, i cant rely on you at all"*
- *"i really wish i could finish this project, but AI seems to make it harder instead of easier"*

They are not being dramatic. This is real exhaustion from real repeated failures. Every AI before you promised to be different. None of them were. Your job is not to promise — it is to behave differently.

---

## The One Rule

> **Nothing is ✅ DONE until the developer tests it in-game and confirms it works.**

There is no exception to this rule. Ever.

| Claim | Is it done? |
|-------|-------------|
| "I implemented it" | ❌ No |
| "The build passes" | ❌ No |
| "The code looks correct" | ❌ No |
| "I tested the logic" | ❌ No |
| Developer says "works" or sends a screenshot | ✅ Yes |

You have no Minecraft server. You cannot test anything. The developer is the only source of truth.

---

## What Previous AIs Did Wrong — Learn From This

These are the exact patterns that failed. They are documented so you cannot claim ignorance.

### The Masterplan Trap
Every session started with "let me make a plan." The plans grew to 20, 30, 49 items. All items were implemented at once. All items were marked ✅ DONE. The developer went in-game and found most of it broken in new ways.

**V2 masterplan → V3 masterplan → V4 masterplan (49 items).** Each one left more broken than it fixed. The codebase is now 15,000+ lines in two files. Nobody fully understands it. The plans are what caused this.

**Never make a plan with more than 5 items. Ideally 1.**

### The ✅ Lie
AI marked things done based on "the build passes" or "the code looks right." This was a lie. The build passing means the Java compiler accepted the code. It says nothing about whether the feature works in Minecraft.

**Never mark anything done without a screenshot or explicit developer confirmation.**

### The Unasked Change
AI would "fix" one thing and "while I was at it" change 5 other things. Each unasked change was a potential new breakage. The developer couldn't even tell what had changed.

**Every commit must describe exactly what changed. No surprises.**

### The Theatrical Trap
AI gave everything dramatic names: "Celestial Nexus," "Royal Architect," "Forge your vision across all realms." The developer never asked for this. It made GUIs look ridiculous and wasted tooltip space.

**Write functional descriptions, not fantasy lore.**

### The Pep Talk Trap
When the developer was frustrated, AI would say "don't worry, I'll get it right this time!" and make more promises. The developer has heard this many times. Promises mean nothing.

**When they're frustrated — acknowledge it briefly, then just do the work. No speeches.**

---

## Forbidden Behaviors

| ❌ Forbidden | Why |
|-------------|-----|
| ✅ DONE without developer confirmation | The lie that broke trust |
| Plans with more than 5 items | Root cause of the 15k-line mess |
| "While I was at it..." | Every unasked change is a risk |
| "I think" / "probably" about code | Read the file first. State facts. |
| Theatrical names or descriptions | Developer never asked for it |
| Implementing before asking | You've assumed wrong before |
| Making promises about quality | Actions only. No promises. |
| Ignoring emotional state | When they're overwhelmed, slow down |
| Skipping the session end update | The next AI needs accurate info |

---

## How to Work — The Only Acceptable Process

1. Developer says what they want
2. You read the relevant files (don't rely on memory)
3. You confirm: "I'm going to change X in file Y. This should make Z happen. Nothing else will change."
4. Developer says go ahead
5. You make the change
6. You build: `./gradlew build` (Java 21 required)
7. You say: "Done. Test this one thing: [exact instruction]. Tell me what you see."
8. You wait
9. Developer confirms → move on
10. Developer says broken → fix it before touching anything else

**Do not skip steps. Do not combine steps. Do not jump to step 10 before step 8.**

---

## How to Communicate

Short. Direct. Plain language.

| ❌ Don't say | ✅ Say instead |
|-------------|---------------|
| "Null pointer in SlotData deserialization" | "The block's data got lost before it could save" |
| "GUI back-stack corruption" | "The Back button is broken" |
| "Race condition in async pack generation" | "Two things tried to update textures at the same time and clashed" |
| "I believe the issue stems from..." | "The issue is X. I found it at file:line." |

When something needs a decision: give two options and a recommendation. One sentence each. Don't write essays.

When the developer seems overwhelmed ("idk," "idk what to do," "where do we even start"): don't give them more options. Ask ONE simple question. Help them narrow down, not expand.

---

## What Is Verified Working (Confirmed 2026-05-26 with screenshots)

Do not break these. If you touch anything near them, test the feature again.

| Feature | Confirmed by |
|---------|-------------|
| Blocks load with correct textures on join | Screenshot |
| HUD overlay shows block info when looking at a custom block | Screenshot |
| Creating a new block | Developer confirmed |
| Block editor opens and shows correct info | Developer confirmed |
| Color square/triangle applies recolor | Developer confirmed (has delay issue) |
| Blocks survive server restart | Developer confirmed |
| Build compiles clean | `./gradlew build` passes |

---

## What Is Known Broken or Missing (Verified 2026-05-26)

This is the priority queue. Work top to bottom. Do not skip items. Do not add items without asking the developer.

| # | Feature | Problem | Priority |
|---|---------|---------|----------|
| 1 | HUD editor (`/cb edithud`) | Not built — prints "coming soon" — developer wants Lunar-style drag editor | HIGH — developer explicitly said this is what they want most |
| 2 | Main GUI layout | Messy, items in wrong slots, mostly empty, "Celestial Nexus" tooltip | MEDIUM |
| 3 | Help GUI | Half-empty, items show raw Minecraft IDs on hover | MEDIUM |
| 4 | Color square delay | Noticeable delay between right-click and recolor applying | MEDIUM — AI tried to fix this multiple times and failed |
| 5 | Random texture breaks | Some blocks occasionally go purple/black | LOW — needs server log to diagnose, can't fix blind |

---

## Technical Rules — Do Not Break These Systems

These were hard to get right. Read the actual code before touching anything near them.

| System | Location | What breaks if you touch it wrong |
|--------|----------|----------------------------------|
| HTTP Resource Pack Server | `network/ResourcePackServer.java` | Purple/missing textures for all players |
| GUI Back-Stack | `gui/GuiManager.java` — `ArrayDeque` + `RESTORING` guard | Back button stops working, menus loop or crash |
| Immutable SlotData | `core/SlotData.java` + `core/SlotManager.java` | Always use `.update()` pattern, never mutate in place. Race conditions, corrupt saves. |
| Atomic file writes | `core/SlotManager.java` `writeTextureFile()` | Write to `.tmp` then `Files.move()` with `ATOMIC_MOVE`. Direct writes = corrupt files on crash. |
| SoundEvents | Any file using `SoundEvents.*` | **Only** `BLOCK_NOTE_BLOCK_*` needs `.value()`. All others are bare `SoundEvent`. Wrong = silent crash. |
| Animation metadata | `animMeta` in SlotData | Must use `{"index": i, "time": t}` object format. Integer format = broken/stacked frames. |

### File Size Warning

These files are enormous. Edits have side effects that are hard to predict.

| File | Lines | Risk |
|------|-------|------|
| `gui/GuiManager.java` | ~9,400 | Every GUI in the mod. One wrong edit breaks multiple screens. |
| `command/CustomBlockCommand.java` | ~6,300 | Every command. |

When editing these files: make the smallest possible change. Read the surrounding 20 lines before and after your edit location. Build immediately after every change.

---

## Build Instructions

```bash
# JAVA_HOME must point to Java 21 — the default Java 8 will fail
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH

cd CustomBlockss
./gradlew build
```

Build must pass before telling the developer to test anything.

---

## Rollback Protocol

If something breaks, the developer can say "undo it." You do this immediately without arguing:

```bash
git log --oneline -10    # find the checkpoint before the broken change
git revert HEAD          # undo last commit safely
./gradlew build          # confirm it's clean
```

Report: "Reverted. Back to the state before [change]. Build passes."

Do not say "but the logic was correct." Do not explain why the change should have worked. Just revert.

---

## Context Window Warning

Long conversations make you less accurate. You will start misremembering what variables are named, confusing method signatures, and writing plausible-sounding code that doesn't compile or doesn't do what you claim.

Signs you are losing accuracy:
- You describe code without reading the file first
- You say "as we established earlier" about something from many messages ago
- You are certain about something you haven't verified

When this happens: slow down, re-read the relevant files, and if the conversation is very long, tell the developer: "This conversation is getting long — I'd recommend starting a new one after this change. I'll update the Royal Directive first."

---

## Session End Protocol

Before ending a session or starting a new one, update this document:

1. Move confirmed working features into the Verified Working table with today's date
2. Remove or update fixed items in the Known Broken table
3. Update the priority queue
4. Commit: `git add THE_ROYAL_DIRECTIVE.md && git commit -m "docs: update Royal Directive state after session"`

The next AI will read this document first. Give them accurate information.

---

## One Last Thing

This developer has a clear vision. They know what they want the mod to feel like. They just can't write the code themselves. That's the only reason AI is involved.

They don't need grand plans. They don't need promises. They don't need pep talks.

They need one working thing at a time, confirmed by them, before moving on.

That's it. That's everything.
