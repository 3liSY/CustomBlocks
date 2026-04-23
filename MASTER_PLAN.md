# CustomBlocks Master Plan
> **Royal Architect Protocol — Surgical, One-at-a-Time, Build After Every Edit**
> **7 Concerns • 6 Phases • 13 Sub-Steps**
>
> *Verified against codebase commit as of 2026-04-22*

---

## Log Analysis Summary

### Boot 1 (03:06) — Crash Session
| Time | Event | Status |
|------|-------|--------|
| `03:06:35` | Server starts, loads 490 slots | ✔ |
| `03:06:48` | `Duplicate SlotData for slot_316 ('_atest' vs 'zomoruda')` | ⚠ Data corruption |
| `03:07:09` | Player joins, hash mismatch → full drip-feed (490 tex) | ✔ |
| `03:07:20` | Drip-feed complete | ✔ |
| `03:11:44` | Rectangle tool → face texture chunked (2 chunks, 655KB, slot=419, face=top) | ✔ |
| `03:14:20` | 🔴 **CRASH: `UTFDataFormatException` — 918KB CB2! chat message** | **Phase 2** |

### Boot 2 (04:08) — Recovery Session
| Time | Event | Status |
|------|-------|--------|
| `04:08:43` | Server restarts | ✔ |
| `04:11:43` | 🟡 Player kicked: "Pipeline has no outbound protocol" | Transient — identity2/Lithium mixin conflict |
| `04:14:56` | Player joins on 2nd attempt → full drip-feed OK | ✔ |
| `04:15:15` | Server stopped | ✔ |

### Client Log Highlights
| Time | Event | Status |
|------|-------|--------|
| `11:04:44` | Client loads, `maxSlots=2048`, 439 local slots | ✔ |
| `11:05:04` | **~1500 `Corrupt PNG` errors** for stale slots 500–2047 | **Phase 5** |
| `11:13:15` | `/cb` typed → "Unknown or incomplete command" | **Phase 1** |
| `11:14:18` | CB2! share code (918KB) sent in chat → server crashes | **Phase 2** |
| `12:15:19` | Texture cache HIT after successful reconnect | ✔ |

### Non-CustomBlocks Errors (Ignore)
- `mr_gamingbarns_guns` / `mr_luishs_guns` — recipe/function failures (wrong MC version)
- `luckyblocks` — unknown recipe serializer
- `identity2` — mixin overwrite conflict with Lithium (root cause of transient pipeline kicks)

---

# Phase 1 — Quick Wins
> *Low-risk fixes, instant value*

## 1A · `/cb` Opens GUI Directly

**Problem:** Typing `/cb` returns "Unknown or incomplete command." Only `/cb gui` works.

**Root Cause:** `CustomBlockCommand.java` — the root `customblock` builder (line 54) has no `.executes()` handler. The `/cb` alias at line 468–470 uses Brigadier `.redirect()`, which forwards subcommands to `customblock`'s children but does NOT inherit the target's executor for the bare command.

**Fix (2 changes required):**

**Part 1 — Add `.executes()` to root `customblock` builder (line 55):**
```java
LiteralArgumentBuilder<ServerCommandSource> tree = CommandManager.literal("customblock")
    .requires(src -> PermissionHelper.canUse(src))
    .executes(ctx -> cmdGui(ctx.getSource()))   // ← NEW: bare /customblock opens GUI
```

**Part 2 — Add `.executes()` to the `/cb` alias (line 468–470):**
Brigadier redirect nodes do not inherit the target's executor. `/cb` needs its own:
```java
dispatcher.register(CommandManager.literal("cb")
    .requires(src -> PermissionHelper.canUse(src))
    .executes(ctx -> cmdGui(ctx.getSource()))   // ← NEW: bare /cb opens GUI
    .redirect(dispatcher.getRoot().getChild("customblock")));
```

| | |
|---|---|
| **File** | `CustomBlockCommand.java` lines 54–55 and 468–470 |
| **Edit** | 2 lines added |
| **Risk** | Zero — `cmdGui` is proven, subcommands unaffected. Brigadier supports `.executes()` + `.redirect()` on the same node (execute on bare, redirect on subcommands). |

**Verify:** `/cb` → GUI opens ✔ · `/cb gui` → still works ✔ · `/cb create ...` → still works ✔ · `/customblock` → GUI opens ✔

---

## 1B · `writePng` Fallback Writes Placeholder Instead of Corrupt Bytes

**Problem:** When `NativeImage.read()` fails on corrupt texture data, the fallback writes raw corrupt bytes to disk → Minecraft logs "Corrupt PNG" on every resource pack load.

**Root Cause:** `ResourcePackGenerator.java` line 422 — catch block writes `imageBytes` (the same corrupt data that just failed to decode) instead of `PLACEHOLDER_PNG` (a valid 1×1 pink PNG).

**Fix:** Change line 422:
```java
// BEFORE (line 422):
try { Files.write(dest.toPath(), imageBytes); }

// AFTER:
try { Files.write(dest.toPath(), PLACEHOLDER_PNG); }
```

| | |
|---|---|
| **File** | `ResourcePackGenerator.java` line 422 |
| **Edit** | 1 line changed |
| **Risk** | Zero — strictly safer, corrupt data never reaches disk. Worst case: block renders as pink placeholder instead of garbage. |

**Verify:** No more "Corrupt PNG" errors in client log for slots with bad data ✔

---

## 1C · Defensive Try-Catch on Server Player Join

**Problem:** If `NetworkManager.onPlayerJoin()` throws (from resource pack enforcement, disk I/O, etc.), the unhandled exception propagates up through Fabric's event handler and can disrupt the join sequence.

**Note:** The "Pipeline has no outbound protocol" error in the logs (Boot 2, 04:11:43) is caused by `identity2`'s mixin overwrite conflict with Lithium, NOT by CustomBlocks. The client already sends `SyncRequestPayload` via `ClientPlayConnectionEvents.JOIN` (line 137–141 of `CustomBlocksClient.java`), which fires AFTER the PLAY phase is established — the pipeline IS ready. No artificial delay is needed.

**Fix:** Wrap `onPlayerJoin` in try-catch (line 441–445 of `CustomBlocksMod.java`):
```java
ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
    try {
        NetworkManager.onPlayerJoin(handler.player);
    } catch (Exception e) {
        CustomBlocksMod.LOGGER.error("[CustomBlocks] Error during player join sync for {}",
                handler.player.getName().getString(), e);
    }
});
```

| | |
|---|---|
| **File** | `CustomBlocksMod.java` lines 441–445 |
| **Edit** | ~5 lines changed |
| **Risk** | Zero — purely defensive, no behavioral change on success path |

**Verify:** Server log shows no unhandled exceptions from player join ✔ · First join attempt succeeds ✔

---

# Phase 2 — Server Crash Fix
> *The #1 priority — server dies when Share button is clicked*

## 2A · Share Button Uses CB~ File Approach Instead of Chat

**Problem:** The Share Block button (slot 43 in Editor GUI) builds a `CB2!` code containing the **entire texture as gzipped Base64** and sends it as a chat message. For animated textures, this reaches **918KB** — exceeding Minecraft's packet limit and **crashing the server** with an unrecoverable `EncoderException`.

```
io.netty.handler.codec.EncoderException: java.io.UTFDataFormatException:
encoded string (CB2!H4sI...AOINAA==) too long: 918344 bytes
```

**Root Cause:** `GuiManager.java` lines 1502–1536 — the `case 43` handler in `handleEditorClick()` builds full JSON, gzips it, Base64-encodes it, and sends the result as a `player.sendMessage()` chat message. Minecraft's Netty encoder rejects UTF strings longer than ~65536 bytes.

**Fix:** Replace the slot 43 handler with the **same `CB~` hash-based file approach** already proven in `cmdExportBlock()` (lines 594–648 of `CustomBlockCommand.java`):

```java
case 43 -> {
    // Share button — hash-based file export (safe for any texture size)
    try {
        com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
        obj.addProperty("customId", d.customId);
        obj.addProperty("displayName", d.displayName);
        obj.addProperty("light", d.lightLevel);
        obj.addProperty("hard", d.hardness);
        obj.addProperty("sound", d.soundType);
        if (d.animMeta != null) obj.addProperty("anim", d.animMeta);
        if (d.noCollision) obj.addProperty("ncol", true);
        if (d.isShaped() && d.shapeBoxes != null) {
            com.google.gson.JsonArray boxes = new com.google.gson.JsonArray();
            for (SlotData.ShapeBox box : d.shapeBoxes) boxes.add(box.toSerialString());
            obj.add("shape", boxes);
        }
        if (d.texture != null)
            obj.addProperty("tex", java.util.Base64.getEncoder().encodeToString(d.texture));
        if (d.hasFaces()) {
            com.google.gson.JsonObject faces = new com.google.gson.JsonObject();
            for (var fe : d.faceTextures.entrySet())
                faces.addProperty(fe.getKey(),
                    java.util.Base64.getEncoder().encodeToString(fe.getValue()));
            obj.add("faces", faces);
        }
        String jsonStr = obj.toString();

        // SHA-256 hash → 12-char code
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = md.digest(jsonStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hexSb = new StringBuilder();
        for (int i = 0; i < 6; i++) hexSb.append(String.format("%02x", hashBytes[i]));
        String hash = hexSb.toString();

        // Write to server file
        java.nio.file.Path exportDir = java.nio.file.Path.of("config/customblocks/exports");
        java.nio.file.Files.createDirectories(exportDir);
        java.nio.file.Files.writeString(exportDir.resolve(hash + ".json"),
            jsonStr, java.nio.charset.StandardCharsets.UTF_8);

        // Send short, clickable code (12 chars, not 918KB)
        String code = "CB~" + hash;
        net.minecraft.text.MutableText clickable = Text.literal("§b§n" + code)
            .styled(s -> s
                .withClickEvent(new net.minecraft.text.ClickEvent(
                    net.minecraft.text.ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                .withHoverEvent(new net.minecraft.text.HoverEvent(
                    net.minecraft.text.HoverEvent.Action.SHOW_TEXT,
                    Text.literal("§eClick to copy"))));
        net.minecraft.text.MutableText line = Text.literal(
            "§0§l[§b§lCB§0§l] §a[Share] §f'§b" + d.customId + "§f' ready! ").append(clickable);
        player.sendMessage(line, false);
        playSuccess(player);
    } catch (Exception ex) {
        send(player, "§c[CB] Share failed: " + ex.getMessage());
    }
}
```

| | |
|---|---|
| **File** | `GuiManager.java` lines 1502–1536 |
| **Edit** | ~35 lines replaced |
| **Risk** | Zero — uses proven `cmdExportBlock` pattern, eliminates 918KB chat message entirely |

**Verify:**
- Click Share → see `CB~xxxxxxxxxxxx` in chat (not 918KB blob) ✔
- Click code → copies to clipboard ✔
- `/cb importblock CB~xxxxxxxxxxxx` → imports correctly ✔
- Server does NOT crash ✔

---

# Phase 3 — Export Code Format
> *Cosmetic improvement — makes codes shorter and more visually interesting*

## 3A · Export Codes Use Mixed Alphanumeric + Symbols

**Problem:** Current format: `CB~` + 12 hex chars (`0-9, a-f` only). User wants letters, numbers, AND symbols.

**Root Cause:** `CustomBlockCommand.java` line 627 — uses `String.format("%02x", ...)` for hex encoding. Same pattern used in the new Phase 2A share handler.

**Fix:** Create a shared helper method and replace hex encoding with a custom alphabet:
```
ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%&*
```
Take 12 bytes from SHA-256, map each `(b & 0xFF) % alphabet.length()` → codes like `CB~Kf3$mR7x&Qp2`.

Filesystem-unsafe chars (`/ \ : ? * " < > |`) are excluded from the alphabet.

Both `cmdExportBlock()` and the new share handler (Phase 2A) should call the same helper.

| | |
|---|---|
| **Files** | `CustomBlockCommand.java` (shared helper + cmdExportBlock), `GuiManager.java` (Phase 2A share handler) |
| **Edit** | ~15 lines: new helper method + 2 call sites |
| **Risk** | Zero — old hex codes still import fine (file lookup is hash-based, `cmdImportBlock` reads from `config/customblocks/exports/<code>.json` regardless of encoding) |

**Verify:** `/cb exportblock <id>` → `CB~Kf3$mR7x&Qp2` format ✔ · GUI Share button → same format ✔ · Old hex codes still import ✔

---

# Phase 4 — Rectangle Tool Face Rendering
> *Two stacked client-side bugs prevent face textures from appearing on single-slot updates*

## 4A · `generateSingleSlot` Uses Correct Model Type

**Problem:** `generateSingleSlot()` always creates a `cube_all` model — even for blocks with per-face textures that need a `cube` model with explicit face references.

**Root Cause:** `ResourcePackGenerator.java` lines 396–401 — inside the `if (!bsFile.exists())` guard, the block model is hardcoded to `minecraft:block/cube_all`:
```java
bm.addProperty("parent", "minecraft:block/cube_all");
JsonObject tex = new JsonObject();
tex.addProperty("all", MOD_ID + ":block/" + slotKey);
```

Meanwhile, the full `generate()` method (lines 164–230) correctly branches between `isShaped()`, `hasFaces()`, and `cube_all`. This logic is missing from `generateSingleSlot()`.

**Fix:** Port the same model-type branching from `generate()` into `generateSingleSlot()`:
```java
JsonObject bm = new JsonObject();
if (data != null && data.isShaped()) {
    // Same shaped-block logic as generate() lines 166-211
    // ...elements array with per-face UV...
} else if (data != null && data.hasFaces()) {
    bm.addProperty("parent", "minecraft:block/cube");
    JsonObject tex = new JsonObject();
    tex.addProperty("particle", MOD_ID + ":block/" + slotKey);
    for (String face : SlotData.FACE_KEYS) {
        String mcFace = FACE_TO_MC.get(face);
        tex.addProperty(mcFace, MOD_ID + ":block/" + slotKey + "_" + face);
    }
    bm.add("textures", tex);
} else {
    bm.addProperty("parent", "minecraft:block/cube_all");
    JsonObject tex = new JsonObject();
    tex.addProperty("all", MOD_ID + ":block/" + slotKey);
    bm.add("textures", tex);
}
```

| | |
|---|---|
| **File** | `ResourcePackGenerator.java` lines 396–401 |
| **Edit** | ~20 lines replaced |
| **Risk** | Low — only affects single-slot resource pack generation path |

---

## 4B · Always Regenerate Block Model (Not Guarded by `bsFile.exists()`)

**Problem:** `generateSingleSlot()` has `if (!bsFile.exists())` at line 384 — once a model is created (as `cube_all` during the initial full `generate()`), the entire blockstate+model block is never re-entered, even when face textures arrive later via the Rectangle tool.

**Root Cause:** The guard was added as an optimization ("blockstate + model files already exist from the initial full generate"). But this prevents the model from being regenerated when the block's face state changes.

**Fix:** Split the guard — blockstate and item model are static (never change), but the **block model** must always regenerate because it depends on `hasFaces()` / `isShaped()`:

```java
// Blockstate + item model — write only if missing (they're static)
File bsFile = new File(assets, "blockstates/" + slotKey + ".json");
if (!bsFile.exists()) {
    new File(assets, "blockstates").mkdirs();
    new File(assets, "models/item").mkdirs();
    String modelRef = MOD_ID + ":block/" + slotKey;
    // Blockstate JSON...
    writeJson(bs, bsFile);
    // Item model JSON...
    writeJson(im, new File(assets, "models/item/" + slotKey + ".json"));
}

// Block model — ALWAYS regenerate (face state can change at any time)
new File(assets, "models/block").mkdirs();
JsonObject bm = new JsonObject();
// ... Phase 4A branching logic ...
writeJson(bm, new File(assets, "models/block/" + slotKey + ".json"));
```

| | |
|---|---|
| **File** | `ResourcePackGenerator.java` lines 382–408 |
| **Edit** | ~30 lines restructured |
| **Risk** | Low — regenerating a single JSON model file is cheap (~1KB) and idempotent. Static files (blockstate, item model) still only written once. |

---

## 4C · Pending Full Reload Flag

**Problem:** If `scheduleGenerateAndReload` is called while `generateRunning` is true (e.g., during a `scheduleSingleSlotReload`), the full reload is silently dropped because `generateRunning.compareAndSet(false, true)` returns false. The face texture arrives and is stored in SlotManager, but the resource pack model is never regenerated with the correct `cube` parent.

**Root Cause:** `CustomBlocksClient.java` lines 524–601 — `scheduleGenerateAndReload()` only starts a new thread if `generateRunning` is false. And line 599–600 says:
```java
// If generateRunning is already true, the running thread will see the updated
// lastPacketTime on its next 200ms poll and extend or break its wait as needed.
```
But the running thread only extends the *wait* — it doesn't know it needs to do a **full** generate instead of a single-slot generate.

**Fix:** Add an `AtomicBoolean pendingFullReload` flag. When `scheduleGenerateAndReload` fails to acquire `generateRunning`, set the flag. After `generateRunning` is released (in both `scheduleSingleSlotReload` and `scheduleGenerateAndReload`), check the flag:

```java
private static final AtomicBoolean pendingFullReload = new AtomicBoolean(false);

// In scheduleGenerateAndReload():
if (generateRunning.compareAndSet(false, true)) {
    // ... existing thread logic ...
} else {
    pendingFullReload.set(true);  // ← NEW: mark for retry
}

// At the end of scheduleSingleSlotReload's client.execute() callback (line 484):
client.execute(() -> {
    generateRunning.set(false);
    if (pendingFullReload.compareAndSet(true, false)) {
        scheduleGenerateAndReload(client, 500L);  // ← Retry the full reload
        return;
    }
    // ... existing reload logic ...
});

// Same check at the end of scheduleGenerateAndReload's client.execute() (line 574):
generateRunning.set(false);
if (pendingFullReload.compareAndSet(true, false)) {
    scheduleGenerateAndReload(client, 500L);
    return;
}
```

| | |
|---|---|
| **File** | `CustomBlocksClient.java` — `scheduleGenerateAndReload` + `scheduleSingleSlotReload` |
| **Edit** | ~12 lines |
| **Risk** | Low — only adds a retry mechanism. Uses `compareAndSet` to prevent infinite loops. |

**Verify (all of Phase 4):**
- Rectangle tool → paste GIF URL → variant block created with animated face ✔
- World block shows the face texture on the correct side ✔
- Client log: `Chunk reassembly complete` + correct model type generated ✔

---

# Phase 5 — Client Cleanup & Sync
> *Eliminate ~1500 "Corrupt PNG" errors from stale slot files*

## 5A · Sync `maxSlots` from Server on Join

**Problem:** Client config has `maxSlots=2048`, server has `500`. The `generate()` loop (line 58) iterates `CustomBlocksConfig.maxSlots` times, creating texture files for 2048 slots. Slots 500–2047 have no data → placeholder PNGs with no matching SlotData → "Corrupt PNG" errors.

**Fix:**
1. Server includes `maxSlots` in the `FullSyncPayload` (already sent on join)
2. Client stores `serverMaxSlots` and uses it as the loop bound in `generate()`
3. Blocks stay registered up to local maxSlots (can't un-register at runtime), but resource pack only generates files for the server's range

| | |
|---|---|
| **Files** | `FullSyncPayload.java` (add field), `CustomBlocksClient.java` (store + use) |
| **Edit** | ~10 lines |
| **Risk** | Low — additive field in existing payload, backward-compatible (default to local config if absent) |

---

## 5B · Cleanup Pass in `generate()`

**Problem:** When maxSlots decreases (e.g. 2048→500), old slot files for indices 500–2047 remain on disk with placeholder data, causing thousands of errors and a bloated texture atlas.

**Fix:** At the start of `generate()`, before the main loop:
1. List all `slot_*.png` files in the textures directory
2. Delete any file for a slot index ≥ `serverMaxSlots` (or `maxSlots` if sync unavailable)
3. Also delete their `.mcmeta`, blockstate JSON, model JSON, and item model counterparts
4. Delete face variant files (`slot_*_top.png`, `slot_*_north.png`, etc.) for those indices

```java
// At the top of generate(), after mkdirs:
File texDir = new File(assets, "textures/block");
int effectiveMax = serverMaxSlots > 0 ? serverMaxSlots : CustomBlocksConfig.maxSlots;
if (texDir.exists()) {
    for (File f : texDir.listFiles()) {
        String name = f.getName();
        if (!name.startsWith("slot_")) continue;
        try {
            // Extract slot index from filename like "slot_123.png" or "slot_123_north.png"
            String numPart = name.substring(5).split("[_.]")[0];
            int idx = Integer.parseInt(numPart);
            if (idx >= effectiveMax || SlotManager.getBySlot("slot_" + idx) == null) {
                f.delete();
            }
        } catch (NumberFormatException ignored) {}
    }
}
// Same cleanup for blockstates/ and models/block/ and models/item/
```

| | |
|---|---|
| **File** | `ResourcePackGenerator.java` — `generate()` method, before main loop |
| **Edit** | ~20 lines |
| **Risk** | Low — only deletes files in the CustomBlocks resource pack directory under `resourcepacks/CustomBlocks/` |

**Verify (all of Phase 5):**
- Join server → client uses server's maxSlots for generation ✔
- No corrupt PNG errors in client log ✔
- Texture atlas size is reasonable (~4096×4096 instead of 16384×16384) ✔
- Change maxSlots on server → client picks it up on next join ✔

---

# Phase 6 — Data Integrity
> *Optional — fixes pre-existing data corruption*

## 6A · Duplicate SlotData Auto-Repair on Load

**Problem:** `slots.json` has two blocks (`_atest` and `zomoruda`) both assigned to slot index 316. The `put()` method in `SlotManager` (which populates both `byId` and `bySlot` maps) silently overwrites the previous occupant of `slot_316` in `bySlot`. The first block's ID remains in `byId`, but its slot mapping is lost — it becomes a ghost entry that can't be edited or rendered.

**Root Cause:** Pre-existing data corruption — possibly from a race condition, manual edit, or a bug in a previous version.

**Fix:** Add a dedup pass in `SlotManager.loadAll()` after the main deserialization loop:
```java
// After the main for-loop in loadAll() (line 606):
// Detect and repair duplicate slot indices
Map<Integer, String> indexToId = new HashMap<>();
List<String> toReassign = new ArrayList<>();
for (SlotData d : new ArrayList<>(byId.values())) {
    String existing = indexToId.put(d.index, d.customId);
    if (existing != null) {
        LOGGER.warn("[CustomBlocks] Duplicate slot index {} claimed by '{}' and '{}'. Reassigning '{}'.",
                d.index, existing, d.customId, d.customId);
        toReassign.add(d.customId);
    }
}
for (String id : toReassign) {
    SlotData d = byId.get(id);
    if (d == null) continue;
    int newIdx = findFreeSlot();
    if (newIdx >= 0) {
        byId.remove(id);
        bySlot.remove("slot_" + d.index);
        SlotData fixed = new SlotData(newIdx, d.customId, d.displayName, d.texture);
        // Copy all properties from the original
        // ... (light, hardness, sound, animMeta, faces, shape, collision)
        put(fixed);
        LOGGER.info("[CustomBlocks] Reassigned '{}' from slot {} → slot {}", id, d.index, newIdx);
    }
}
if (!toReassign.isEmpty()) {
    // Trigger save to persist the fix
    LOGGER.info("[CustomBlocks] {} duplicate(s) repaired. Saving corrected data.", toReassign.size());
}
```

**Alternative (manual):** Edit `config/customblocks/slots.json` and change one duplicate's `"index"` field.

| | |
|---|---|
| **File** | `SlotManager.java` — `loadAll()` method, after line 606 |
| **Edit** | ~25 lines |
| **Risk** | Low — only affects data loading, `assign()` is already synchronized |

**Verify:** Server starts without `Duplicate SlotData` warnings ✔

---

# Execution Roadmap

```
 Phase 1 ─── Quick Wins & Defensive Fixes
   ├─ 1A: /cb opens GUI (2 lines — both nodes)              ──→ build ✔
   ├─ 1B: writePng fallback fix (1 line)                     ──→ build ✔
   └─ 1C: Try-catch on onPlayerJoin (5 lines)                ──→ build ✔

 Phase 2 ─── Server Crash Fix  ★ CRITICAL ★
   └─ 2A: Share button CB2! → CB~ file approach              ──→ build ✔

 Phase 3 ─── Export Code Format
   └─ 3A: Mixed alphanumeric + symbols (shared helper)       ──→ build ✔

 Phase 4 ─── Rectangle Tool Face Rendering
   ├─ 4A: Fix generateSingleSlot model type                  ──→ build ✔
   ├─ 4B: Always regenerate block model JSON                 ──→ build ✔
   └─ 4C: Add pendingFullReload flag                         ──→ build ✔

 Phase 5 ─── Client Cleanup & Sync
   ├─ 5A: Sync maxSlots from server on join                  ──→ build ✔
   └─ 5B: Cleanup stale slot files in generate()             ──→ build ✔

 Phase 6 ─── Data Integrity
   └─ 6A: Duplicate SlotData auto-repair on load             ──→ build ✔

```

**Total: 10 sub-steps across 6 phases, each followed by `gradlew build`**

---

## Key Corrections from Original Plan

| # | Original Claim | Correction |
|---|----------------|------------|
| 1 | Phase 1A: "Add `.executes()` to root builder only" | Must add to BOTH `customblock` root AND `cb` alias — Brigadier `.redirect()` does not inherit the target's executor |
| 2 | Phase 1C: "Delay SyncRequestPayload by ~500ms" | **Removed.** Client sends SyncRequestPayload after PLAY phase is established (pipeline is ready). The "Pipeline has no outbound protocol" error is from identity2/Lithium, not CustomBlocks. |
| 3 | Phase 1C: "Try-catch on server onSyncRequest" | **Removed.** The SyncRequest handler already runs inside `context.server().execute()`, which has its own exception handling. |
| 4 | Phase 3A: "line ~624" | Correct line is 627 |
| 5 | Phase 4B: "Remove the `if (!bsFile.exists())` guard entirely" | **Refined.** Split the guard: keep it for blockstate + item model (static), remove it for block model (face-dependent) |
| 6 | Original total: "14 sub-steps" | Corrected to 10 sub-steps (removed unnecessary delay + extra try-catch, merged 4A+4B edit scope) |

---

## WebP Test Link

For testing WebP proxy via wsrv.nl, use this direct WebP URL in-game:
```
https://www.gstatic.com/webp/gallery/1.webp
```

---

# Phase 7 — Display Order & Slot Swap *(Post-Stabilization Feature)*
> *User's idea from 2026-04-22 @ 10:26 PM — saved so he doesn't forget after school 😂*

## Problem
Blocks are displayed in the GUI sorted by slot index. Since blocks are created over time, the order is random and messy (e.g., Arabic letter Alef at slot 171, Ba at slot 451, Ta at slot 454). The user wants to reorganize blocks into logical groups.

## Key Insight: Display Order ≠ Slot Index
**Slot indices must NEVER change** — the world stores placed blocks as `customblocks:slot_316`. Changing the index breaks every placed block.

Instead, add a **`displayOrder`** field to `SlotData`. The GUI sorts by this field. The slot index stays fixed forever. World blocks are untouched.

## Implementation

### 7A · Add `displayOrder` Field to SlotData
- New `int displayOrder` field (default = `index` for backward compat)
- Serialized/deserialized in `slots.json`
- Synced to client via `FullSyncPayload`

### 7B · GUI Sorts by `displayOrder`
- `SlotManager.sortedSlots()` sorts by `displayOrder` instead of `index`
- All GUI pages, picker menus, and tab lists use the new order

### 7C · `/cb swaporder <blockA> <blockB>` Command
- Swaps the `displayOrder` values of two blocks
- Does NOT touch slot indices — world is completely safe
- Broadcasts update to all clients

### 7D · `/cb sortblocks <mode>` Command (Optional)
- `alphabetical` — sorts all blocks A-Z by displayName
- `category` — groups by suffix (_black, _green, _yellow, etc.)
- `manual` — opens a drag-and-drop GUI (stretch goal)

## Example
```
Before:
  slot_104 (Ferrari)     → displayOrder: 1    (shows 1st in GUI)
  slot_95  (BMW)         → displayOrder: 2    (shows 2nd)
  slot_96  (Toyota)      → displayOrder: 3    (shows 3rd)

After /cb swaporder ferrari toyota:
  slot_104 (Ferrari)     → displayOrder: 3    (shows 3rd)
  slot_95  (BMW)         → displayOrder: 2    (stays 2nd)
  slot_96  (Toyota)      → displayOrder: 1    (shows 1st)

World blocks: COMPLETELY UNCHANGED ✅
```

**Estimated effort:** ~50 lines of code across SlotData, SlotManager, GuiManager, CustomBlockCommand

---

> *"Speed is irrelevant. Quality is everything." — Master Directive §7*
