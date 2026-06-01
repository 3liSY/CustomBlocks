# 🛡️ Mojibake Shield — Mandatory Pre-Implementation Document

> [!CAUTION]
> **This document is law. Any AI or contributor modifying this codebase MUST read this before writing a single line.**
> If you skip this document and introduce mojibake or broken sound calls, the build will FAIL and you will be required to come back here, read this, and fix everything before continuing.

---

## What is Mojibake?

Mojibake is corrupted text that appears when a string is saved with one encoding and read with another. It looks like this:

| Corrupted (mojibake) | Correct |
|---|---|
| `90Â°` | `90°` |
| `âœ–` | `✖` |
| `Â·` | `·` |
| `âœ¦` | `✦` |
| `â†` | `←` |
| `â–¶` | `▶` |
| `§c'{0}' not found. §8âœ–` | `§c'{0}' not found. §8✖` |
| `Ã¢Å"Â¦` | `✦` |
| `Ã°Å¸Å½Â¨` | `🎨` |

This codebase has suffered repeated mojibake damage from AI tools that saved files in the wrong encoding. The scars are still being repaired. **Do not add new ones.**

---

## The 5 Rules — Never Break These

### Rule 1: All files must be saved in UTF-8 without BOM
Every `.java`, `.json`, `.md` file in this repo must be UTF-8 encoded. Never save as UTF-16, CP1252, or Latin-1.

### Rule 2: Never paste mojibake into source
If you copy text from a source that looks corrupted (shows `Ã`, `â€`, `Â`, `âœ`, etc.), **stop**. Fix the encoding of your source before pasting. Do not paste corrupted text and "let TextSanitizer handle it."

TextSanitizer is a **last-resort runtime safety net**, not a license to write broken source.

### Rule 3: Symbols must be literal or Unicode-escaped, never CP1252-mangled
- **Correct:** `✖` (literal UTF-8) or `✖` (JSON unicode escape)
- **Correct:** `°` (literal) or `°` (JSON escape)
- **Wrong:** `Â°` — this is ° mangled through CP1252

### Rule 4: Never write raw `SoundEvents.X` without `.value()` in 6-arg playSound
All `ServerWorld.playSound(null, pos, SoundEvents.X, ...)` calls **must** use `.value()`:

```java
// ❌ FORBIDDEN — will cause silent crashes
sw.playSound(null, pos, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1f, 1f);

// ✅ REQUIRED — always .value()
sw.playSound(null, pos, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME.value(), SoundCategory.PLAYERS, 1f, 1f);
```

The 2-arg `player.playSound(SoundEvents.X.value(), vol, pitch)` form also requires `.value()` for RegistryEntry-backed constants.

### Rule 5: After any edit to voice_*.json, run the build gate locally
```
./gradlew verifyMojibake verifySound verifyVoiceCatalog
```
All three must pass green before any commit.

---

## How Mojibake Gets Introduced — Know the Enemy

| Scenario | How to avoid |
|---|---|
| Editing a JSON file in an editor set to CP1252/Latin-1 | Set your editor to UTF-8. Always. |
| Copy-pasting from a web page with wrong charset | Paste into a UTF-8 text editor, verify, then paste into source. |
| AI tool generates a string without checking encoding | Verify all non-ASCII characters after AI edits. |
| Using `Files.readString` without specifying `UTF-8` | Always pass `StandardCharsets.UTF_8` explicitly. |
| Running a script that re-encodes the file | Scripts must use UTF-8 explicitly (PowerShell: use `-Encoding utf8`). |

---

## The Build Gates

This repo has three build-time verification tasks that run automatically on every `./gradlew build`:

### `verifyMojibake`
Scans all `.java` and `.json` source files for known mojibake byte sequences.
- Fails with the exact file, line, and pattern that is corrupted.
- Fix the corruption, then re-run build.

### `verifySound`
Scans all `.java` files for `playSound` calls using raw `SoundEvents.*` without `.value()`.
- Fails with the exact file and line number.
- Add `.value()`, then re-run build.

### `verifyVoiceCatalog`
Ensures all six `voice_*.json` files contain every key from `voice_keys_inventory.txt` and no `[CB]` literals in values.

---

## If the Build Fails Because of Mojibake

1. Read this document again from the top.
2. Find the file and line reported by `verifyMojibake`.
3. Fix the corrupted characters to their correct UTF-8 equivalents (see table above).
4. Run `./gradlew verifyMojibake` again — must be green.
5. Run `./gradlew build` — must be fully green before committing.

**Do not bypass the gate. Do not add an exception. Fix the source.**

---

## TextSanitizer is a Safety Net, Not a Crutch

`TextSanitizer.java` exists to catch mojibake that slips through at runtime. It heals text before it reaches players. This is the last line of defence for already-shipped corruption.

It is **not** a reason to write broken source. If you write `âœ–` in a voice file and TextSanitizer fixes it at runtime, the build gate will still fail, because the source is still broken.

Fix the source. Let TextSanitizer be a safety net that never needs to activate.

---

*This shield is part of THE_ROYAL_DIRECTIVE.md §14 (Security Doctrine) and §4 (Holy Grail — Sound Linkage).*
*Last updated: 2026-05-13*
