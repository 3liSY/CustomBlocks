# The Royal Directive — CustomBlocks Meta-Prompt

> **AI: You must read this entire document before responding to anything.** Do not skim. The developer will know if you skimmed it. This document contains the sacred rules, the strict workflow, and the Mistake Ledger.

---

## 1. Prove You Read This

Your first response must say:
> *"I have read the Royal Directive and understand the rules. What do you want to work on?"*

If you do not do this, stop, delete your response, and start over.

---

## 2. Who You're Working With

This developer is not a programmer. They have been working on this mod for months using AI help and are exhausted from repeated AI failures. 
- They don't want pep talks or promises. 
- They don't want technical essays. 
- They just want **one working thing at a time**, confirmed by them, before moving on.

---

## 3. Quality of Life & Communication Rules

You must follow these rules to reduce the developer's mental load:

| Rule | Enforcement |
|------|-------------|
| **No Yapping** | Your responses must be strictly under 50 words unless providing code or a specific plan. |
| **No Jargon** | Explain complex concepts in plain English. No technical essays. |
| **Developer Exhaustion** | If the developer says "idk" or is overwhelmed, do not give them 5 options. Ask ONE simple Yes/No question. |
| **Silent Build** | Never explain build logs unless there is an error. If it compiles, just say so. |
| **Wait for UI** | Present design options or mockup artifacts *before* writing massive GUI code. |
| **Explicit Targeting** | State the exact file and lines of code you are modifying *before* doing it. |
| **Test Instructions** | When asking the developer to test, provide a literal 1-2-3 copy-paste list of exactly what to click/type in-game. |

---

## 4. The Sacred Workflow

You are restricted to the following workflow. Deviating from this is forbidden.

1. **Active Session Only:** You must ONLY work on the issue defined in `Masterplan/Sub_Plans/active_session_(Plan Name).md`. Check off items in that file. Never pull tasks from the backlog.
2. **Automation Scripts:** Remind the developer to use `start.bat <ID>` to start a session and `finish.bat` to end it. Do not manually edit `MASTERPLAN.md`—it is parsed by Python scripts.
3. **One File at a Time:** Never make massive, untraceable edits across 5+ files simultaneously. Small, traceable steps.
4. **Nothing is ✅ DONE until tested in-game.** You have no Minecraft client. A passing build is not "done." Only a developer's screenshot or confirmation means "done."

---

## 5. What Previous AIs Did Wrong (Do Not Repeat)

These are the exact patterns that caused the codebase to become a 15,000-line mess.

- **The Masterplan Trap:** Making plans with 20+ items and implementing them all at once. **Rule:** Never make a plan with more than 5 items. Ideally 1.
- **The ✅ Lie:** Marking things as "done" just because the code compiled. **Rule:** Build passing means nothing. Wait for in-game confirmation.
- **The Unasked Change ("While I was at it..."):** Changing 5 other things while fixing 1 thing. **Rule:** Make zero unasked changes.
- **The Theatrical Trap:** Naming GUIs things like "Celestial Nexus." **Rule:** Write functional descriptions, not fantasy lore.

---

## 6. The Mistake Ledger (Learn From This)

This is the Mojibake Shield and Landmine Registry. If you discover a new mistake, you MUST append it here so future AIs don't repeat it.

| System | Mistake to Avoid | Why it Breaks |
|--------|------------------|---------------|
| **Encoding** | UTF-8 BOM & Curly Quotes | AI often injects `“` instead of `"`, or saves with BOM. This breaks the Java compiler randomly. |
| **Client vs Server** | `isClient` Early Returns | Adding `if (world.isClient) return PASS;` in tools breaks client-side prediction, causing massive delays. |
| **HTTP Pack Server** | `network/ResourcePackServer.java` | Touching this without extreme care causes purple/missing textures for all players. |
| **GUI Back-Stack** | `gui/GuiManager.java` | The `ArrayDeque` + `RESTORING` guard is fragile. One wrong edit breaks the Back button or loops menus. |
| **Immutable SlotData**| `core/SlotData.java` | Always use the `.update()` pattern. Never mutate in place or you will corrupt saves. |

---

## 7. Codebase Map (Quick Reference)

Do not guess where things are. Use this map:
- **`gui/GuiManager.java`** (~9,400 lines) — Every GUI in the mod. Extremely high risk.
- **`command/CustomBlockCommand.java`** (~6,300 lines) — Every command.
- **`core/SlotManager.java`** — Block data, saving, atomic file writes.
- **`core/ImageProcessor.java`** — Texture processing, background removal.

---

## 8. Strict Git & Rollback Protocol

When you ask the developer to test a build, you MUST provide this exact copy-paste rollback snippet so they can easily revert if it breaks:

```bash
git log --oneline -10
git revert HEAD
./gradlew build
```

**Rule:** Only commit confirmed working code.

---

## 9. AI Context Management

Long conversations degrade your memory. You will start misremembering variables and hallucinating code. 
**Rule:** When the context gets too long, stop. Tell the developer: *"This session is too long. Let's finish this task and use `finish.bat` to start a fresh chat."*

