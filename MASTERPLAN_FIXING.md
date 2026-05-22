# CustomBlocks — Fixing Masterplan (Forensic-Verified)

> Last verified: 2026-05-22  
> Every claim cross-referenced against actual source files. No assumptions. Code is the only truth.

---

## VERIFICATION REPORT

### ✅ VERIFIED ITEMS — Confirmed correct as written

| Item | Claim | Evidence |
|------|-------|----------|
| V4.1 | `finalizeStartupLoad()` at lines 1145–1168 | Confirmed: `private static void finalizeStartupLoad()` begins at line 1145, closes at line 1168 |
| V4.1 | `SlotData.FACE_KEYS` is `Set<String>` with 6 face names | Confirmed: `SlotData.java:84` — `public static final Set<String> FACE_KEYS = Set.of("top", "bottom", "north", "south", "east", "west")` |
| V4.1 | `writeFaceTextureFile(int slotIndex, String face, byte[] data)` exists | Confirmed: `SlotManager.java:1773` — private static method, atomic write |
| V4.1 | `withFaceTexture` returns `this` if face/tex null/empty | Confirmed: `SlotData.java:292–295` — guard returns `this` unchanged |
| V4.1 | `ensurePowerOf2` returns same reference when already correct | Confirmed: `ImageProcessor.java:1235` — identity check is valid |
| V4.1 | `saveAll()` called when `fixed > 0` covers both loops | Confirmed: existing pattern in `finalizeStartupLoad()` |
| V4.2 | `openBrokenBlocks(ServerPlayerEntity, int)` at line 572 | Confirmed: `GuiManager.java:572` |
| V4.2 | No startup guard currently in `openBrokenBlocks` | Confirmed: lines 572–578 go directly to business logic |
| V4.2 | `brokenBlocks()` flags `d.texture == null` as broken | Confirmed: `SlotManager.java:328` — `d.texture == null \|\| d.texture.length <= 4` |
| V4.2 | `isStartupLoadComplete()` is volatile-safe | Confirmed: `SlotManager.java:797–798` — reads a `volatile boolean` |
| V4.2 | `send(player, msg)` is valid in GuiManager | Confirmed: `GuiManager.java:6131` — `private static void send(ServerPlayerEntity p, String m)` |
| V4.2 | `/cb showbrokenblocks` calls `openBrokenBlocks()` | Confirmed: `CustomBlockCommand.java:639` — `GuiManager.openBrokenBlocks(p)` |
| V4.3 | `onServerTick(MinecraftServer server)` exists | Confirmed: `NetworkManager.java:212` |
| V4.3 | `LAST_FULL_SYNC` is `ConcurrentHashMap<UUID, Long>` | Confirmed: `NetworkManager.java:47` |
| V4.3 | `TextureQueue` deduplicates by `slotIndex:action:face` | Confirmed: `TextureQueue.java:132–134` — deduplication replaces stale payloads |
| V4.3 | `schedulePostStartupSync()` setting a `volatile boolean` is safe from IO thread | Confirmed: volatile visibility guarantee — no lock needed |
| V4.4 | `buf.writeString(facesJson)` at line 92 crashes when facesJson > 32,767 chars | Confirmed: `SlotUpdatePayload.java:92` |
| V4.4 | `buf.writeString(variantsJson)` at line 93 has same risk | Confirmed: `SlotUpdatePayload.java:93` |
| V4.4 | `buf.readString()` for facesJson/variantsJson at lines 107–108 | Confirmed: `SlotUpdatePayload.java:107–108` |
| V4.4 | `readByteArray(10_485_760)` cap matches existing texture field | Confirmed: `SlotUpdatePayload.java:100` |
| V4.4 | Record fields remain `String facesJson` and `String variantsJson` — no callers need changes | Confirmed: all callers in NetworkManager use String fields; wire format is the only change |

---

### ✏️ CORRECTED ITEMS — Original vs corrected, side by side

#### Correction C1 — V4.1: Problem severity massively overstated

| | Text |
|--|------|
| **ORIGINAL** | "Every server restart produces 150+ log lines... With ~150 blocks each having 2–6 face texture overrides, this produces 150+ warnings per build." |
| **CORRECTED** | This server has **5 face-textured slots** (confirmed from log: "595 main, 5 face"). Actual warnings per restart are **2** — both from **default textures** (not face textures), and already handled by the existing code after the first restart. The face texture fix in V4.1 is **preventive**: it ensures face textures are fixed on disk before ServerPackGenerator reads them, preventing future warnings if those 5 face textures are ever non-power-of-2. It is not currently eliminating 150 warnings. |

#### Correction C2 — V4.1: Inner loop iterates wrong collection

| | Code |
|--|------|
| **ORIGINAL** | `for (String face : SlotData.FACE_KEYS) { byte[] faceBytes = d.faceTextures.get(face); if (faceBytes == null \|\| faceBytes.length == 0) continue;` |
| **CORRECTED** | `for (Map.Entry<String, byte[]> faceEntry : d.faceTextures.entrySet()) { String face = faceEntry.getKey(); byte[] faceBytes = faceEntry.getValue();` |

**Why this matters:** `FACE_KEYS` has 6 entries (top/bottom/north/south/east/west). Iterating it checks all 6 possible faces even when a slot has only 1 face texture. Iterating `d.faceTextures.entrySet()` only visits faces that actually exist, is more semantically correct, and avoids the null-get on every missing face. The null guard is still removed because `entrySet()` never produces null values (the constructor does `v.clone()` on every entry). The iterator is bound to the original `d.faceTextures` object before `d` is reassigned — safe.

#### Correction C3 — V4.3: Experience description contains wrong claim

| | Text |
|--|------|
| **ORIGINAL** | "Their game sends a `SyncRequestPayload`, and the server sends the complete drip-feed." |
| **CORRECTED** | The server calls `sendFullSync(player)` **directly** — the client does NOT send a `SyncRequestPayload`. `sendFullSync` immediately sends a new `FullSyncPayload` (metadata) and queues all 602 texture payloads into the player's drip-feed queue. Because `TextureQueue` deduplicates by `slotIndex:action:face`, any partial join-sync payloads still in the queue are replaced by the fresh complete set. |

#### Correction C4 — V4.3: Missing edge case note about SyncCompletePayload

| | Text |
|--|------|
| **ORIGINAL** | No mention of the `SyncCompletePayload` sent by the original join sync. |
| **CORRECTED** | Add to Edge Cases: "**Stale SyncCompletePayload:** The original join-sync queued a `SyncCompletePayload` via `enqueueRaw()` (not deduplicated). If the join-sync drip-feed completes before startup finishes (typical: 100 textures × 8/tick = 12.5 ticks ≈ 0.6 seconds), the queue is empty by the time `schedulePostStartupSync` fires and this is a non-issue. If the drip-feed is still in progress (very slow connection, very low `texturePayloadsPerTick`), the client receives two `SyncCompletePayload`s. The client handler should be idempotent on receiving a second one — acceptable behavior." |

---

### ✅ Additional Verified — Security Note: Config GUI Slot 33

| Claim | Evidence |
|-------|----------|
| Slot 33 removed from `buildConfigGui()` | `GuiManager.java:2451` — `// Slot 33 was Cloud Vault URL — removed (URL is now a hardcoded constant, not user-configurable)`. The slot receives only the default `glass()` fill from line 2422 and is never overridden. |
| Slot 33 removed from `handleConfigGuiClick()` | `GuiManager.java:2602` — `// case 33 was Cloud Vault URL — removed (hardcoded constant)`. No case 33 handler exists in the switch. |

### 🚫 BLOCKED ITEMS — Could not verify (missing or unreadable source)

**None.** All items fully verified.

---

### 👑 ROYAL DIRECTIVE VIOLATIONS — Items violating THE_ROYAL_DIRECTIVE.md

**None.** All 4 items are backend-only bug fixes. No GUI elements are added, no buttons are placed, no sounds or particles are wired. The Directive's GUI rules (button items, header/footer rows, sensory feedback) do not apply to any change in this plan.

---

---

## IMPLEMENTATION PLAN (Corrected — Implementation-Ready)

> Implement in this exact order. V4.4 must ship first — it is blocking all joins.

---

## Item V4.4 — CRITICAL: All Players Crash on Join (String Too Big)

### The Problem

Every player who tries to join is immediately kicked with:
```
io.netty.handler.codec.EncoderException: String too big (was 55194 characters, max 32767)
    at com.customblocks.network.SlotUpdatePayload.lambda$static$0(SlotUpdatePayload.java:92)
```

**Root cause:** `SlotUpdatePayload.java` encodes `facesJson` and `variantsJson` using `buf.writeString()`. Minecraft's `PacketByteBuf.writeString()` has a hard limit of **32,767 characters** — this is a Minecraft engine constraint, not configurable. Base64 inflates binary data by ~33%. A 41 KB face texture becomes ~55 KB base64 = 55,194 characters. This slot exists in the delta-sync payload set and crashes every join attempt.

The server has 5 face-textured slots. One of them produces a 55,194-character `facesJson`. Every join that triggers a delta sync (which sends changed slots) crashes at line 92.

### The Fix

**File:** `CustomBlockss/src/main/java/com/customblocks/network/SlotUpdatePayload.java`

`buf.writeByteArray()` has no character limit — only a byte-length cap. Transmit `facesJson` and `variantsJson` as raw UTF-8 bytes.

**Encoder — replace lines 92–93:**

```java
// REMOVE these two lines:
buf.writeString(value.facesJson()     != null ? value.facesJson()     : "");
buf.writeString(value.variantsJson()  != null ? value.variantsJson()  : "");

// REPLACE WITH:
buf.writeByteArray((value.facesJson()    != null ? value.facesJson()    : "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
buf.writeByteArray((value.variantsJson() != null ? value.variantsJson() : "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
```

**Decoder — replace lines 107–108:**

```java
// REMOVE these two lines:
String facesJson    = buf.readableBytes() > 0 ? buf.readString() : "";
String variantsJson = buf.readableBytes() > 0 ? buf.readString() : "";

// REPLACE WITH:
String facesJson    = buf.readableBytes() > 0 ? new String(buf.readByteArray(10_485_760), java.nio.charset.StandardCharsets.UTF_8) : "";
String variantsJson = buf.readableBytes() > 0 ? new String(buf.readByteArray(10_485_760), java.nio.charset.StandardCharsets.UTF_8) : "";
```

The 10 MB cap matches the existing `texture` field cap at line 100. No other files change. The record fields remain `String facesJson` and `String variantsJson` — all callers are unaffected.

### Edge Cases

- **`variantsJson`** fixed as a precaution — same overflow risk exists if variants grow large.
- **Empty string:** `"".getBytes(UTF_8)` = zero-length byte array → `writeByteArray(new byte[0])` writes VarInt `0` → decoder reads empty array → `new String(new byte[0], UTF_8)` = `""`. Identical behavior.
- **Wire format not backwards-compatible.** Old clients cannot talk to new server. Given players cannot join at all right now, this is not a regression.
- **10 MB cap:** 5 face-textured slots × max 6 faces × 41 KB each = ~1.2 MB base64 — far under the cap.

### Files

| File | Change |
|------|--------|
| `network/SlotUpdatePayload.java` | Lines 92–93: 2× `writeString` → `writeByteArray` in encoder; Lines 107–108: 2× `readString` → `readByteArray` in decoder |

---

## Item V4.1 — Startup Resize Warnings (Face Textures Never Saved to Disk)

### The Problem

`ServerPackGenerator.java` (lines 138–140) calls `ensurePowerOf2()` on every face texture each time it builds the resource pack ZIP. It resizes them in memory but **never writes the result back to disk**. This means if any face texture file is non-power-of-2, the WARN fires every restart.

The existing `finalizeStartupLoad()` already fixes and saves **default** textures (lines 1149–1163). Face textures have no equivalent save step.

**Current state of this server:** 5 face-textured slots. The face textures on those 5 slots appear to already be power-of-2 (no face-texture warnings in current logs — only 2 default-texture warnings from two specific slots). This fix is **preventive**: it closes the gap so future face textures are fixed once on first startup rather than warned on every build.

### The Fix

**File:** `CustomBlockss/src/main/java/com/customblocks/core/SlotManager.java`  
**Method:** `finalizeStartupLoad()` (lines 1145–1168)

Inside the `synchronized (SlotManager.class)` block, after the existing default-texture loop, add a second loop for face textures. Share the existing `fixed` counter so the same `LOGGER.warn` and `saveAll()` call cover both.

**Exact code to add — insert after the closing brace of the existing `if (fixed > 0)` block, still inside the `synchronized` block:**

```java
// Fix face textures — ServerPackGenerator resizes on every pack build but never writes back.
for (SlotData d : new ArrayList<>(byId.values())) {
    boolean faceFixed = false;
    for (Map.Entry<String, byte[]> faceEntry : d.faceTextures.entrySet()) {
        String face = faceEntry.getKey();
        byte[] faceBytes = faceEntry.getValue();
        int faceFrames = com.customblocks.ImageProcessor.getVerticalFrames(faceBytes);
        if (faceFrames > 1) continue;
        byte[] fixedFace = com.customblocks.ImageProcessor.ensurePowerOf2(faceBytes);
        if (fixedFace != faceBytes) {
            d = d.withFaceTexture(face, fixedFace);
            writeFaceTextureFile(d.index, face, fixedFace);
            fixed++;
            faceFixed = true;
        }
    }
    if (faceFixed) put(d);
}
```

**Why `d.faceTextures.entrySet()` instead of `SlotData.FACE_KEYS`:** Only visits faces that actually exist on the slot. The for-each iterator is bound to the original `d.faceTextures` object at loop start — reassigning `d` inside the body does not affect the iterator. Safe.

**The existing default-texture loop stays exactly as-is.** Only the face-texture loop is new.

### Edge Cases

- **Animated face textures** (`faceFrames > 1`): skipped — animated strips do not need power-of-2 enforcement.
- **`withFaceTexture` returns `this`** if tex length == 0 (SlotData.java:293). Unreachable here since `entrySet()` never contains zero-length values (constructor clones them), but the guard is a no-cost safety net.
- **`fixedFace != faceBytes` identity check:** `ensurePowerOf2` returns the original reference unchanged when dimensions are already correct. The identity check is correct and intentional.
- **`saveAll()` already called when `fixed > 0`** — covers face fixes too. No extra call needed.
- **First restart:** warnings fire for any non-power-of-2 face textures found, files are corrected. Every subsequent restart: zero warnings for those files.

### Files

| File | Change |
|------|--------|
| `core/SlotManager.java` | Add face-texture fix loop inside `finalizeStartupLoad()`, after the existing default-texture loop, inside the `synchronized` block |

---

## Item V4.2 — Broken Blocks GUI Shows False Positives During Startup Load

### The Problem

`brokenBlocks()` in `SlotManager.java` (line 328) flags any slot where `d.texture == null || d.texture.length <= 4` as broken. During the async startup texture load, textures are null in RAM for every slot whose `.dat` file hasn't been read yet.

If an admin opens `/cb showbrokenblocks` during this window, they see hundreds of "broken" blocks that are actually fine — just not yet loaded from disk. The `brokenBlocksWithReasons()` method (lines 338–359) classifies these mid-load slots as `FILE_MISSING` ("Texture file was lost — please re-upload.") which is completely wrong.

### The Fix

**File:** `CustomBlockss/src/main/java/com/customblocks/gui/GuiManager.java`  
**Method:** `openBrokenBlocks(ServerPlayerEntity player, int page)` at line 572

Add a startup guard as the very first thing in the method body:

**Exact old code (lines 572–578):**
```java
public static void openBrokenBlocks(ServerPlayerEntity player, int page) {
    int total = brokenBlocks().size();
    int max   = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
    page = Math.max(0, Math.min(page, max));
    pushBackStack(player.getUuid());
    openScreenFromGuiState(player, GuiState.pickerBroken(page), buildPicker(player.getUuid(), page, true), Text.translatable("customblocks.gui.picker_broken.title"));
}
```

**Exact new code:**
```java
public static void openBrokenBlocks(ServerPlayerEntity player, int page) {
    if (!com.customblocks.core.SlotManager.isStartupLoadComplete()) {
        send(player, "§eStill loading textures from disk — please wait a moment and try again.");
        return;
    }
    int total = brokenBlocks().size();
    int max   = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
    page = Math.max(0, Math.min(page, max));
    pushBackStack(player.getUuid());
    openScreenFromGuiState(player, GuiState.pickerBroken(page), buildPicker(player.getUuid(), page, true), Text.translatable("customblocks.gui.picker_broken.title"));
}
```

The 1-arg overload at line 571 (`openBrokenBlocks(player)`) delegates to the 2-arg version — the guard is inherited for both entry points.

### Edge Cases

- **`/cb showbrokenblocks` command** at `CustomBlockCommand.java:639` calls the 1-arg overload → delegates to 2-arg → guard applies. Both command and GUI button covered by one change.
- **Genuine broken blocks** (missing file, never uploaded): still detected correctly after load completes. The guard only blocks access during the load window.
- **Fresh installs** (no textures): `finalizeStartupLoad()` is called immediately with no async work → `startupLoadComplete = true` right away → guard opens immediately. No functional difference.
- **`isStartupLoadComplete()` thread safety:** reads a `volatile boolean` — safe from any thread including the server thread.

### Files

| File | Change |
|------|--------|
| `gui/GuiManager.java` | Add 4-line startup guard at the top of `openBrokenBlocks(ServerPlayerEntity, int)` at line 572 |

---

## Item V4.3 — Players Who Join During Startup Load Get Incomplete Texture Set

### The Problem

From the actual server logs:
```
01:20:13 — Player joins
01:20:13 — Async load progress: 100/602 (16%)
01:20:14 — Delta sync: "0 slots changed (of 101 total), 597 client-had"
01:20:31 — All 602 textures loaded — pack build triggered
```

**What goes wrong:**
1. Player joins at 01:20:13, `sendFullSync()` queues drip-feed for only the 100 in-memory slots.
2. Delta sync compares hashes against 101 in-memory slots and says "0 changed" — the other 501 are absent from the comparison entirely.
3. At 01:20:31, `finalizeStartupLoad()` triggers a pack rebuild via `flushPendingBuildIfNeeded()` but **does not re-sync online players**.
4. `FULL_SYNC_COOLDOWN_MS = 30_000` would block a re-sync anyway — the player joined 18 seconds earlier.

The player ends up with textures for ~100 of the 602 custom blocks. The rest render as purple/black missing texture.

### The Fix

Two coordinated changes:

#### Change A — NetworkManager.java

**Add field** (near other static fields, after `FULL_SYNC_COOLDOWN_MS`):
```java
/** Set true by finalizeStartupLoad() to re-sync players who joined before async load finished. */
private static volatile boolean pendingPostStartupSync = false;
```

**Add method** (anywhere in the class, before or after `broadcastFullSync`):
```java
/** Called from finalizeStartupLoad() (IO thread) — schedules a one-time re-sync on next tick. */
public static void schedulePostStartupSync() {
    pendingPostStartupSync = true;
}
```

**Add handler** at the very start of `onServerTick(MinecraftServer server)` — insert before line 214 (`int perTick = ...`):
```java
if (pendingPostStartupSync) {
    pendingPostStartupSync = false;
    List<ServerPlayerEntity> online = server.getPlayerManager().getPlayerList();
    if (!online.isEmpty()) {
        LOGGER.info("[CustomBlocks] Post-startup re-sync: {} online player(s) who joined during load.", online.size());
        for (ServerPlayerEntity p : online) {
            LAST_FULL_SYNC.remove(p.getUuid()); // bypass 30s cooldown — their join sync was partial
            sendFullSync(p);
        }
    }
}
```

**Why `onServerTick` and not directly from `finalizeStartupLoad()`:** `finalizeStartupLoad()` runs on `IO_EXECUTOR` (async thread). Sending packets and iterating the player list must happen on the server thread. The tick event is server-thread-guaranteed.

**Why `TextureQueue` deduplication makes this safe:** `sendFullSync` queues 602 "add" payloads using `enqueue()`, which deduplicates by `slotIndex:action:face`. Any partial join-sync payloads (slots 0–100) still in the queue are replaced by the new complete payloads. The player ends up with exactly 602 fresh payloads queued.

#### Change B — SlotManager.java

Add one line at the very end of `finalizeStartupLoad()`, after `flushPendingBuildIfNeeded()`:
```java
com.customblocks.network.NetworkManager.schedulePostStartupSync();
```

This is safe to call from `IO_EXECUTOR` — it only sets a `volatile boolean`.

### Edge Cases

- **No players online when load completes:** `online.isEmpty()` check skips the loop. `pendingPostStartupSync` cleared either way.
- **Player disconnects between join and load completion:** `server.getPlayerManager().getPlayerList()` returns only currently connected players. `onPlayerDisconnect()` already cleaned up their queue. Safe.
- **Multiple restarts:** `pendingPostStartupSync` resets to `false` immediately on first tick after startup. Each restart triggers exactly one post-startup sync.
- **Stale SyncCompletePayload:** The original join-sync queued a `SyncCompletePayload` via `enqueueRaw()` (not deduplicated). At 8 payloads/tick, 100 join-sync payloads drain in ~0.6 seconds. By the time startup load finishes (~18 seconds later), the queue is empty and this `SyncCompletePayload` is already sent. Edge case only on extremely slow connections or very low `texturePayloadsPerTick`.
- **Fresh installs** (no textures, `finalizeStartupLoad` called immediately): no players are online yet at server start — the `if (!online.isEmpty())` check no-ops. Harmless.

### Files

| File | Change |
|------|--------|
| `network/NetworkManager.java` | Add `pendingPostStartupSync` field; add `schedulePostStartupSync()` method; add 10-line block at top of `onServerTick()` |
| `core/SlotManager.java` | Add 1 line at end of `finalizeStartupLoad()` |

---

## Summary Table

| Priority | Item | Root Cause | Files | Lines |
|----------|------|-----------|-------|-------|
| **1 — CRITICAL** | **V4.4: All joins crash** | `buf.writeString(facesJson)` exceeds 32,767-char Minecraft limit | `SlotUpdatePayload.java` | 4 |
| 2 | V4.2: Broken blocks false positives | No startup-load guard in `openBrokenBlocks()` | `GuiManager.java` | 4 |
| 3 | V4.3: Incomplete textures for early-joining players | No re-sync after `finalizeStartupLoad()`; 30s cooldown blocks it | `NetworkManager.java`, `SlotManager.java` | ~15 |
| 4 | V4.1: Face texture resize not persisted | Face textures never written back after `ensurePowerOf2` in pack generator | `SlotManager.java` | ~15 |

**Total: ~38 lines across 4 files.**

---

## Security Notes (Permanent — Do Not Violate)

- **`cloudShareUrl`** — hardcoded `public static final String` constant in `CustomBlocksConfig.java`. Never move to `config.json` or the Config GUI. Any OP could redirect it to their own server and intercept all resource pack data.
- **`cloudPackSecret`** — `config.json` only. Never render in the in-game Config GUI. Any OP with GUI access could read the upload secret and forge pack uploads.
- **Config GUI slot 33** — must remain removed from `buildConfigGui()` and `handleConfigGuiClick()`.
