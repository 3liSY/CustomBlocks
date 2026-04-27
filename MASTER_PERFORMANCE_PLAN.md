# 👑 CustomBlocks — Master Performance & UX Plan

> **The Royal Architect's Performance Blueprint**
>
> This plan exists to make CustomBlocks **untouchably fast** — the kind of mod where 500 blocks load in seconds, GUIs respond instantly, and batch edits never freeze. Every optimization here was discovered by reading the actual source code, verified against real line numbers, and designed to cause **zero regressions**.
>
> **Governing law:** [`THE_ROYAL_DIRECTIVE.md`](THE_ROYAL_DIRECTIVE.md) — Surgical edits only. Research-first. Build after EVERY edit. Zero regressions. Premium UX at every layer.
>
> **Sacred systems (§ 4 — DO NOT BREAK):** GUI Back-Stack (`RESTORING` guard), Immutable SlotData (clone-on-write), `SoundEvents.value()` linkage, Animation `.mcmeta` 
`synchronized` for multi-step mutations.
>
> **Rollback safety (§ 8):** Each phase is independent. `./gradlew build` after every edit. Git checkpoint before and after each phase. Any phase can be reverted without breaking others.
>
> Last updated: April 27, 2026

---

## Table of Contents
1. [Current State — Numbers at Scale](#1-current-state--numbers-at-scale)
2. [Every Bottleneck — Ranked](#2-every-bottleneck--ranked)
3. [All Improvements — Unified Priority Table](#3-all-improvements--unified-priority-table)
4. [Feature: `/cb rp pause` / `/cb rp resume`](#4-feature-cb-rp-pause--cb-rp-resume)
5. [Implementation Phases — Step by Step](#5-implementation-phases--step-by-step)
6. [Files to Edit — Complete List](#6-files-to-edit--complete-list)
7. [Before vs After — Every Scenario](#7-before-vs-after--every-scenario)
8. [Edge Cases](#8-edge-cases)
9. [Testing Checklist](#9-testing-checklist)
10. [Layered Defense Audit (§ 5)](#10-layered-defense-audit--5)
11. [Definition of Done (§ 10)](#11-definition-of-done--10)
12. [Cleanup: Remove rpEnforceOnJoin](#12-cleanup-remove-rpenforceonjoin)
13. [Feature Roadmap — 8 New Features](#13-feature-roadmap--8-new-features)

---

## 1. Current State — Numbers at Scale

Your server: **534 blocks**, `maxSlots=600`, default client `maxSlots=2048`

| Metric | Current (534) | At 1000 | At 2000 |
|--------|:------------:|:-------:|:-------:|
| Server RAM for SlotData | ~2.7 MB | ~5 MB | ~10 MB |
| Drip-feed time (first join) | **~13 sec** | ~25 sec | ~50 sec |
| Client pack generation | **~15-30 sec** | ~30-60 sec | ~60-120 sec |
| Client `slots.json` (Base64) | ~5-10 MB | ~15-20 MB | ~30-40 MB |
| Server `slots.json` (metadata only) | ~500 KB | ~1 MB | ~2 MB |
| Server ZIP pack build | ~2-5 sec | ~5-10 sec | ~10-20 sec |
| Block+Item registry objects | 600 | 1000 | 2048 |
| Files written to resource pack | ~2100 | ~4000 | ~8000 |
| `client.reloadResources()` time | **5-15 sec** | 5-15 sec | 5-15 sec |
| Loading screens per triangle batch (6 blocks) | **6** | 6 | 6 |

---

## 2. Every Bottleneck — Ranked

### 🔴 CRITICAL — Will crash, freeze, or make the mod unusable at scale

| ID | Bottleneck | File : Lines | What happens at 500+ blocks | Fix |
|----|-----------|-------------|----------------------------|-----|
| **S1** | **VoxelShape rebuilt from scratch on EVERY call** | `SlotBlock.java:122-131` | `buildVoxelShape()` creates new `VoxelShapes.union()` objects every time Minecraft queries collision/outline/culling. Called **thousands of times/sec** per loaded chunk. 50 shaped blocks in render distance = TPS drops, lag spikes, GC storms. | Cache in `ConcurrentHashMap`, invalidate on shape change |
| **C1** | **`writePng()` decode→re-encode EVERY texture** | `ResourcePackGenerator.java:~1009-1027` | `NativeImage.read(bytes)` → `img.writeTo(dest)` for 500+ textures. Full PNG decode + re-encode cycle — completely wasted because bytes from server are **already valid PNG**. This is the **#1 reason** pack gen takes 15-30 sec. | Write raw bytes: `Files.write(dest.toPath(), imageBytes)` with PNG header check |
| **C5** | **`client.reloadResources()` fires on EVERY block edit** | `CustomBlocksClient.java:1241` | Reloads ALL resource packs (vanilla + all mods), not just CustomBlocks. Takes 5-15 sec each. **No Minecraft API exists for partial reload.** Creating 6 triangle variants = **6 loading screens in a row.** | **`/cb rp pause` command** — suppress reloads during batch edits, one catch-up reload on resume |
| **S2** | **`isBrokenTexture()` full PNG decode on every SlotData construction** | `SlotData.java:68` | Public constructor calls `ImageProcessor.isBrokenTexture(texture)` → `ImageIO.read()`. Every `loadAll()`, `assignAtIndex()`, `withTexture()` triggers it. 500 PNG decodes at startup + 500 more during drip-feed. | Use internal constructor with `precomputedBroken=false` for batch loads |
| **N1** | **Drip-feed locked at 2 payloads/tick** | `CustomBlocksConfig.java:56` | 500 textures ÷ 2/tick ÷ 20 TPS = **12.5 sec wait**. Most textures are ~500 bytes. The 512KB/tick byte budget is barely used — the packet count cap is the bottleneck. At 2000 blocks: **50 seconds.** | Change default `texturePayloadsPerTick` from 2 → 8 |
| **R1** | **Default `maxSlots=2048` registers 2048 Block+Item** | `CustomBlocksMod.java:149-187` | Registers 2048 blocks + 2048 items at startup even when server only uses 534. Adds ~2-3 sec to startup. Client having 2048 while server has 600 causes YoCube-type "Corrupt PNG" errors. | Change default from 2048 → 600 |

### 🟡 HIGH/MEDIUM — Degrades performance noticeably

| ID | Bottleneck | File : Lines | What happens at 500+ blocks | Fix |
|----|-----------|-------------|----------------------------|-----|
| **C2** | **Pack generates files for ALL slots (even empty)** | `ResourcePackGenerator.java:119-121` | Loops `0 → 600`, writes placeholder PNG + 3 JSONs for empty slots. ~264 wasted file writes at 600 slots. At 2048: **~6000 wasted files.** | `if (data == null) continue;` in loop |
| **C3** | **`saveToClientDir()` writes ALL textures as Base64 JSON** | `SlotManager.java:852-893` | 500 textures Base64-encoded into one `slots.json`. File = 5-10 MB. Called on every join + every edit. 33% overhead from Base64. | Use `.dat` files like server does |
| **C4** | **`loadFromClientDir()` loads entire JSON into memory** | `SlotManager.java:899-955` | `Files.readString()` loads 5-10 MB. `JsonParser.parseString()` builds DOM tree 2-3× that. At 1000+ blocks: **60MB+ heap spike.** | Streaming `JsonReader` |
| **C6** | **Client `computeTextureHash()` has no caching** | `CustomBlocksClient.java:1401-1447` | Hashes 500 textures (~2.5 MB) every time it's called. Called in `scheduleGenerateAndReload`, `scheduleSingleSlotReload`, `scheduleAnimMetaReload`. | Cache like server does, invalidate on change |
| **S3** | ~~`saveAllAsync` safety check~~ **ALREADY FIXED** | `SlotManager.java:762-794` | Now uses `textured_count.txt` sidecar — zero JSON parsing, zero heap allocation. ✅ No action needed. | Already uses sidecar file |
| **S4** | **`luminance` lambda does ConcurrentHashMap lookup every call** | `CustomBlocksMod.java:161-166` | `SlotManager.getByIndex(idx)` runs on every light-engine query per custom block. Adds up in dense builds. | `int[] lightCache` array |
| **S7** | **5 players joining = 2500 queued payloads** | `NetworkManager.java:128-147` | 5 × 500 textures queued, 10 send calls/tick for 250 ticks. Network I/O on Netty thread. | Increase drip-feed rate + global bandwidth cap |
| **N2** | **Hash mismatch = resend ALL textures (no delta)** | `NetworkManager.java:253-280` | Even 1 changed block → full 500-texture re-drip via `sendFullSync()`. No per-slot comparison. | Per-slot hashing + diff protocol |

### 🟢 LOW — Minor or already well-handled

| ID | Bottleneck | File : Lines | Notes |
|----|-----------|-------------|-------|
| **S5** | `getSoundGroup()` map lookup + switch per call | `SlotBlock.java:86-108` | Cache `BlockSoundGroup` per slot |
| **S6** | `getName()` map lookup per call | `SlotBlock.java:38-42` | Cache name per slot |
| **C7** | `cleanupStaleSlotFiles()` lists all files in 4 dirs | `ResourcePackGenerator.java:941-1003` | Only run inside `generate()`. Stays `private`. |
| **S8** | ServerPackGenerator ZIP build time | `ServerPackGenerator.java:48-211` | Already well-designed (streams, single thread) |
| **S9** | `computeTextureHash()` server-side | `SlotManager.java:1130-1158` | Already cached + invalidated |
| **N3** | FullSyncPayload grows linearly | `NetworkManager.java:112-122` | ~55 KB at 500, OK until 5000+ |
| **N4** | No compression on packets | `NetworkManager.java:337-347` | PNGs already compressed |
| **R2** | Luminance lambda closure overhead | `CustomBlocksMod.java:160-167` | Minor, fix with S4 |
| **R3** | `loadAll()` synchronous at startup | `CustomBlocksMod.java:548` | Acceptable — once at boot |

### 🟡 NEW — Discovered in deep audit (GUI, memory, network)

| ID | Bottleneck | File : Lines | What happens at 500+ blocks | Severity | Fix |
|----|-----------|-------------|----------------------------|----------|-----|
| **G1** | **`sortedBlocks()` called 15+ times per GUI interaction — re-sorts EVERY time** | `GuiManager.java:3116-3118` → `SlotManager.java:181-189` | Every page click, search, bulk delete, face picker, tab icon picker calls `sortedBlocks()` which runs `.stream().filter().sorted().collect()` on all 500+ SlotData objects. That's 500 × log(500) comparisons **per click**. Opening a 28-page picker = 15+ sorts. | 🟡 **HIGH** | Cache sorted list, invalidate on `put()`/`remove()` |
| **G2** | **`searchBlocks()` sorts then filters on every click** | `GuiManager.java:2651-2654` | `handleSearchPickerClick` calls `searchBlocks(query)` which calls `sortedBlocks().stream().filter()` on EVERY page-nav click and block click. Double work: sort + filter + collect repeated. | 🟡 **MEDIUM** | Use cached sorted list + filter |
| **G3** | **Per-player GUI state maps never cleaned up** | `GuiManager.java:102-111` | `STATES`, `BACK_STACK`, `PENDING`, `HANDLERS`, `SEARCH_QUERIES`, `BULK_DELETE_SELECTIONS`, `FACE_CHANGE_*`, `RECENT_BLOCKS` — 8 `ConcurrentHashMap<UUID, ...>` that grow forever. Players who disconnect leave stale entries. On a popular server with 1000 unique joins/day, this leaks indefinitely. | 🟡 **MEDIUM** | Clean all maps in a player-disconnect handler |
| **G4** | **`TextureCache.getOrLoad()` double map lookup** | `TextureCache.java:24` | `CACHE.containsKey(customId)` then `CACHE.get(customId)` — two ConcurrentHashMap operations instead of one. Called on render thread. | 🟢 **LOW** | Use `CACHE.get()` with null check, or `computeIfAbsent` |
| **M1** | **`SlotData.texture.clone()` in every constructor** | `SlotData.java:79` | Immutable design requires defensive copying. Every `withTexture()`, `withFaceTexture()`, `assignAtIndex()`, `loadAll()` clones the full texture bytes. For animated textures (50KB+), 500 clones = 25MB of garbage. During "Create 6 triangles": each variant clones texture 3-4 times across assign→put→IO→broadcast = 18-24 clones. | 🟡 **MEDIUM** | Add `SlotData.withTextureNoCopy()` for trusted internal callers (already-validated bytes). Keep clone for user-facing API. |
| **M2** | **`saveAll()` called after EVERY single GUI mutation** | `GuiManager.java` (20+ calls) | Rename, retexture, setface, create, delete, reid, set property, set sound, toggle collision, duplicate, set shape — each calls `SlotManager.saveAll()`. During "Create 6 triangle variants": 6 `saveAll()` calls in a loop. Each triggers debounced async write + safety check. | 🟡 **MEDIUM** | Batch mutations: `saveAll()` once after a batch, not per-item. Or increase debounce coalescing. |
| **N5** | **`sendFullSync` sends face textures as SEPARATE payloads** | `NetworkManager.java:138-144` | For 500 blocks with avg 2 faces each = **1000 extra payloads** on top of 500 main textures. Total: 1500 payloads at 2/tick = **37.5 seconds**! Even at 8/tick = 9.4 seconds. Face textures are small (~500 bytes) but each is a separate queue entry + packet. | 🟡 **HIGH** | Bundle main texture + face textures into ONE payload per block. Or add face bytes as fields on the existing "add" payload. |
| **N6** | **`SlotUpdatePayload` allocates full-size objects for tiny metadata changes** | Across all broadcast calls | Rename, setprop, set sound — all create a full `SlotUpdatePayload` with null texture but still carry all fields through serialization. Minor but adds up with 500+ blocks and frequent edits. | 🟢 **LOW** | Separate lightweight `MetadataPayload` for non-texture changes |
| **F1** | **`findFreeSlot()` is O(n) linear scan** | `SlotManager.java:1246-1258` (called at :243) | Scans all slot indices 0→maxSlots, creating `"slot_" + i` String on every iteration and doing a HashMap lookup. With 500+ blocks at maxSlots=600: 600 String allocations + 600 map lookups per `assign()`. During batch creation (6 triangles): 6 × 600 = 3600 checks. | 🟢 **LOW** | Maintain a `TreeSet<Integer>` of free slots, O(log n) first-available |
| **F2** | **`broadcastUpdate` per face = N×players×faces packets** | `GuiManager.java:689-691, 723-725` | Creating a variant with 6 face textures: 1 "add" + 6 "setface" = 7 `broadcastUpdate` calls, each iterating all players and enqueuing. With 20 players: 140 queue operations for one block. | 🟢 **LOW** | Combine into single payload or batch-enqueue |
| **F3** | **`isBrokenTexture()` re-check on `assign()` / `assignAtIndex()` / `withTexture()`** | `SlotData.java:62-69, 103-106, 133-136` | **Verified:** 13 of 15 `with*()` methods already pass `this.isBroken` to the internal constructor — only `withTexture()` (line 133) intentionally re-checks because the texture bytes changed. **The real S2 issue** is the 4-arg constructor (line 103) used by `assign()` (line 247) and `assignAtIndex()` (line 270) — these call the public 11-arg constructor which triggers `isBrokenTexture()` (~5-10ms PNG decode) on every block creation and drip-feed receive. At 500 blocks during join: **2.5-5 seconds** of pure PNG decoding. | 🟡 **HIGH** | Use internal constructor with `precomputedBroken=false` for `assignAtIndex` (drip-feed path). Check only on user-upload path in `ImageProcessor.process()`. |

---

## 3. All Improvements — Unified Priority Table

| # | ID | What to do | Lines | Impact | Risk |
|---|-----|-----------|:-----:|--------|:----:|
| **1** | **C1** | `writePng()` → raw `Files.write()` with PNG header check fallback | ~15 | Pack gen: **30s → 3s** | Low |
| **2** | **S1** | VoxelShape cache: `ConcurrentHashMap<Integer, VoxelShape>` in SlotBlock | ~20 | **Shaped block lag → zero** | Med |
| **3** | **RP-PAUSE** | `/cb rp pause` + `/cb rp resume` + GUI buttons — suppress loading screens during batch edits | ~200 | **6 loading screens → 0** during batch | Med |
| **4** | **N1** | `texturePayloadsPerTick` default: 2 → 8 | **1** | Join: **13s → 3s** | None |
| **5** | **R1** | `maxSlots` default: 2048 → 600 | **1** | Startup -2s, no Corrupt PNG | None |
| **6** | **C2** | Skip empty slots in `generate()` loop | **1** | -264 to -6000 file writes | None |
| **7** | **S2** | Skip `isBrokenTexture` in batch loads — use internal constructor | ~10 | **-500 PNG decodes at startup** | Low |
| **8** | **C3** | Client binary `.dat` texture storage (mirror server design) | ~80 | Save: **10 MB → 500 KB** JSON | Med |
| **9** | **C4** | Streaming `JsonReader` for `loadFromClientDir` | ~40 | Load: **no 60 MB heap spike** | Med |
| **10** | **S4** | `int[] lightCache` for luminance lookups | ~15 | Fewer map lookups/tick | Low |
| **11** | **C6** | Client-side texture hash caching | ~10 | -50ms per hash call | Low |
| **12** | **—** | Parallel file writes in `ResourcePackGenerator.generate()` | ~20 | Pack gen: **3s → 1s** | Med |
| **13** | **—** | Incremental pack gen via `pack_manifest.json` per-slot hashes | ~60 | Rejoin (1 changed): **30s → 0.1s** | Med |
| **14** | **S5+S6** | Sound/name caches in int-indexed arrays | ~20 | Minor CPU savings | Low |
| **15** | **N2** | Delta sync — per-slot hashing, server sends only changed | ~100 | Rejoin: **all 500 → just changed** | High |
| **16** | **S7** | Server-wide bandwidth cap across all players | ~15 | Prevents 5×500 flood | Low |
| **17** | **G1** | Cache `sortedBlocks()` — return cached list, invalidate on `put()`/`remove()` | ~15 | **-15 sorts per GUI click** | Low |
| **18** | **N5** | Bundle face textures into main "add" payload during `sendFullSync` | ~30 | Join: **1500 payloads → 500** = 3× faster sync | Med |
| **19** | **G3** | Clean per-player GUI state on disconnect (`ServerPlayConnectionEvents.DISCONNECT`) | ~10 | **No memory leak** on popular servers | Low |
| **20** | **M1** | `SlotData.withTextureNoCopy()` for trusted internal callers (skip clone) | ~15 | **-25 MB garbage** during batch loads | Low |
| **21** | **M2** | Batch `saveAll()` — coalesce multiple GUI mutations into one save | ~10 | **6 saves → 1** during triangle batch | Low |
| **22** | **F3** | ~~Ensure ALL with*() pass this.isBroken~~ **13/15 already do** — main win is `createTrusted()` factory in S2 above | ~5 | Verification only (Phase 4 covers this) | Low |
| **23** | **F1** | `TreeSet<Integer>` for free slot tracking — O(log n) instead of O(n) | ~20 | Faster `assign()` at 500+ blocks | Low |
| **24** | **G4** | `TextureCache.getOrLoad()` single lookup instead of containsKey+get | ~3 | Fewer map ops on render thread | Low |
| **25** | **NEW** | `SlotBlock.slotKey` final field — cache `"slot_" + slotIndex` once | **1** | Eliminates String concat in 7+ methods called per-tick (shapes, sounds, names) | None |

---

## 4. Feature: `/cb rp pause` / `/cb rp resume`

> **Why this exists:** Bottleneck **C5** — `client.reloadResources()` takes 5-15 seconds and reloads ALL packs. Minecraft has no partial reload API. This is a **UX workaround** that lets players suppress loading screens during batch editing.

### Commands
```
/cb rp              — opens Resource Pack Hub GUI (existing, unchanged)
/cb rp pause        — shows confirmation prompt → suppresses loading screens
/cb rp resume       — shows confirmation prompt → fires one catch-up reload
/cb rp pause confirm  — (hidden) executes pause after clickable confirm
/cb rp resume confirm — (hidden) executes resume after clickable confirm
```
Server-side Brigadier commands, operator-only. State is **per-player** (`Set<UUID>`, not persisted).

### Confirmation Prompts

**Pause:**
```
§8§m─────────────────────────────────────
§b§l  ⏸ PAUSE Resource Pack Reloading?
§8§m─────────────────────────────────────
§7  All resource pack loading screens will be
§7  suppressed until you resume. Textures are
§7  still saved to disk — just not applied yet.
§7
§a  [✔ CONFIRM PAUSE]     §c  [✖ CANCEL]
§8§m─────────────────────────────────────
```
- `[✔ CONFIRM PAUSE]` → clickable `Text` → runs `/cb rp pause confirm`
- `[✖ CANCEL]` → clickable → sends "§7Cancelled." and does nothing

**Resume:**
```
§8§m─────────────────────────────────────
§b§l  ▶ RESUME Resource Pack Reloading?
§8§m─────────────────────────────────────
§7  This will apply all pending texture changes
§7  in a single resource pack reload.
§7
§a  [✔ CONFIRM RESUME]    §c  [✖ CANCEL]
§8§m─────────────────────────────────────
```

**After pause confirmed:**
```
§8§m─────────────────────────────────────
§b§l  ⏸ Resource Pack Reloading PAUSED
§8§m─────────────────────────────────────
§7  Edit blocks freely — no loading screens.
§7  Run §f/cb rp resume §7or click in §f/cb rp
§7  to apply all changes at once.
§8§m─────────────────────────────────────
```

**After resume confirmed:**
```
§8§m─────────────────────────────────────
§b§l  ▶ Resource Pack Reloading RESUMED
§8§m─────────────────────────────────────
§7  Applying all pending texture changes...
§8§m─────────────────────────────────────
```

### GUI Buttons in Resource Pack Hub

Added to the existing `/cb rp` chest GUI alongside slots 20, 22, 24, 45:

| Slot | Item | Label | Lore (§ 2A: deep tooltips) | Click action |
|:----:|------|-------|---------------------------|-------------|
| **29** | `ECHO_SHARD` (enchant glint, active) / `AMETHYST_SHARD` (enchant glint, paused) | `§e§l⏸ Pause Reloads` / `§a§lReloading Paused ✔` | `§7Freezes loading screens while` / `§7you create, delete, and edit.` / `§8Textures save to disk silently.` | Toggle pause — direct, no second prompt (GUI click = confirmation) |
| **33** | `NETHER_STAR` (enchant glint, active) / `GUNPOWDER` (no glint, paused) | `§a§l▶ Resume Reloads` / `§7§l▶ Resume (not paused)` | `§7Applies ALL pending changes in` / `§7one smooth reload.` / `§8One loading screen instead of many.` | Resume if paused, no-op if already active |

Buttons **swap appearance** based on pause state. Header compass (slot 4) shows: `§7RP Reload: §ePAUSED` or `§7RP Reload: §aACTIVE`.

### Sensory Feedback (§ 2B — Silence is a bug)

| Action | Sound | Particles | Chat |
|--------|-------|-----------|------|
| **Pause confirmed** (GUI or command) | `BLOCK_AMETHYST_BLOCK_CHIME` | `ENCHANT` burst at player | Branded `§b§l⏸ PAUSED` message |
| **Resume confirmed** | `ENTITY_EXPERIENCE_ORB_PICKUP` | `COMPOSTER` burst at player | Branded `§a§l▶ RESUMED` message |
| **Resume complete** (after reload) | `BLOCK_NOTE_BLOCK_CHIME` (pitch 1.5) | `GLOW` particles at player | Resume summary with changelog |
| **Resume with no changes** | `BLOCK_NOTE_BLOCK_BASS` (soft) | `SMOKE` puff | `§7Nothing changed while paused.` |
| **Cancel** | — (no sound on no-op) | — | `§7Cancelled.` |

### How It Works — Technical

#### Server Side

**`CustomBlockCommand.java`:**
1. Static `Set<UUID> rpPausedPlayers = ConcurrentHashMap.newKeySet()`
2. Nest `pause` and `resume` as sub-literals under existing `rp` literal
3. `/cb rp pause` → send chat confirmation (clickable text)
4. `/cb rp pause confirm` → add UUID to set → send `RpPausePayload(true)` → chat feedback
5. `/cb rp resume` → send chat confirmation
6. `/cb rp resume confirm` → remove UUID from set → send `RpPausePayload(false)` → chat feedback

**`GuiManager.java`:**
1. `buildResourceHub()` → add pause/resume buttons at slots 29 and 33
2. `handleResourceHubClick()` → slot 29 toggles pause (direct), slot 33 resumes
3. Both send `RpPausePayload` and reopen GUI to reflect new state

#### New Packet

**`RpPausePayload.java`** — S2C only:
```java
public record RpPausePayload(boolean paused) implements CustomPayload {
    public static final Id<RpPausePayload> ID = ...;
    public static final PacketCodec<RegistryByteBuf, RpPausePayload> CODEC = ...;
}
```

#### Client Side

**`CustomBlocksClient.java`:**
1. New fields:
   ```java
   private static volatile boolean rpPaused = false;
   // § 6: AtomicInteger, NOT volatile int — rpPausedChangeCount++ is a non-atomic RMW
   private static final AtomicInteger rpPausedChangeCount = new AtomicInteger(0);
   private static final Set<Integer> rpPausedDirtySlots = ConcurrentHashMap.newKeySet(); // which slots changed
   private static volatile boolean rpPausedHadDeletes = false; // single-writer (client thread only), volatile OK
   private static volatile boolean rpPausedNeedsFullGenerate = false; // set when remove/setface/clearface/clearfaces/tabicon happens during pause

   // Change log — records what happened during pause for the resume summary
   private static final List<String> rpPauseLog = Collections.synchronizedList(new ArrayList<>());
   // Entry format: "created:Diamond Ore", "deleted:Old Block", "retextured:Ruby", "edited:Emerald Slab"
   ```
2. Register `RpPausePayload` receiver:
   - `paused=true` → set `rpPaused = true`, reset counter/set/flag
   - `paused=false` → set `rpPaused = false` → trigger **optimized resume reload** (see below)
3. **Guard the two reload entry points:**
   - `scheduleSingleSlotReload()` (~line 995): if `rpPaused` → still run `generateSingleSlot` (writes PNG+JSON to resource pack dir), **skip** `saveToClientDir`, `computeTextureHash`, `saveCachedHash`, `clearServerHash`, and `client.reloadResources()` (all deferred to resume). Increment `rpPausedChangeCount`, add slot index to `rpPausedDirtySlots`. **Add entry to `rpPauseLog`** (e.g. `"retextured:Ruby"`).
   - `scheduleGenerateAndReload()` (~line 1119): if `rpPaused` → **skip entirely** (no `generate()`, no `saveToClientDir`, no reload). If triggered by a delete, set `rpPausedHadDeletes = true`. **Set `rpPausedNeedsFullGenerate = true`** because remove/setface/clearface/clearfaces (line 967) and tabicon (line 941) go through this path and their files DON'T get written by `generateSingleSlot`. On resume, if this flag is set, run a full `generate()` before `reloadResources()`. **Add entry to `rpPauseLog`** (e.g. `"deleted:Old Block"`, `"edited:Ruby"`).
   - On `processSlotUpdatePayload()` with `action="add"` while paused: **Add entry** `"created:BlockName"`.
   - On `processSlotUpdatePayload()` with `action="retexture"/"setface"` while paused: **Add entry** `"edited:BlockName"`.

**During pause — what runs vs what's skipped:**
| Action | During pause | On resume |
|--------|:----------:|:--------:|
| `generateSingleSlot()` (per add/retexture) | ✅ runs per edit | — (already done) |
| `ResourcePackGenerator.generate()` (full regen) | ❌ skipped | ✅ runs ONCE **only if** remove/setface/clearface/clearfaces/tabicon happened |
| `saveToClientDir()` (write full client cache) | ❌ skipped | ✅ runs ONCE |
| `computeTextureHash()` + `saveCachedHash()` | ❌ skipped | ✅ runs ONCE |
| `clearServerHash()` (invalidate stale server hash) | ❌ skipped | ✅ runs ONCE |
| `client.reloadResources()` (loading screen) | ❌ skipped | ✅ runs ONCE |

This means during pause: **zero heavy I/O per edit** (only the tiny per-slot file writes). All expensive operations are batched into a single resume call.

### Resume Reload — Optimized for Heavy Batch Edits

> **Problem to solve:** After pausing and creating 50 blocks, deleting 20, editing 30 textures, the resume must NOT lag, freeze, or crash. It must apply everything in one clean reload.

**Key insight:** During pause, `generateSingleSlot()` writes each `add`/`retexture` block's PNG + JSON files to disk as they happen. So for the **common case** (batch creating blocks), files are **already there** on resume — no full `generate()` needed. **However**, `remove`/`setface`/`clearface`/`clearfaces` (line 967) and `tabicon` (line 941) go through `scheduleGenerateAndReload` (not `generateSingleSlot`), so their files are NOT written during pause. If any of those happened, resume runs one full `generate()` first.

**Resume flow (in the `RpPausePayload` receiver, `paused=false`):**

> **THREADING:** `generate()`, `saveToClientDir()`, and `computeTextureHash()` are heavy I/O.
> They MUST run on a background thread — NOT on `client.execute()` (main thread).
> This matches the existing pattern in `scheduleGenerateAndReload` (lines 1125-1217).

```java
client.execute(() -> {
    // ── Step 0: Reset state on main thread (instant) ──────────────────
    rpPaused = false;
    int changes = rpPausedChangeCount.getAndSet(0);    // § 6: atomic read-and-reset
    boolean hadDeletes = rpPausedHadDeletes;
    boolean needsFullGenerate = rpPausedNeedsFullGenerate;
    Set<Integer> dirtySlots = new HashSet<>(rpPausedDirtySlots);
    List<String> log = new ArrayList<>(rpPauseLog);

    // Reset tracking state
    rpPausedDirtySlots.clear();
    rpPausedHadDeletes = false;
    rpPausedNeedsFullGenerate = false;
    rpPauseLog.clear();

    if (changes == 0) {
        // Nothing changed while paused — no reload needed
        // § 2B: soft feedback even on no-op
        LOGGER.info("[CustomBlocks] RP resumed — nothing changed, skipping reload.");
        return;
    }

    LOGGER.info("[CustomBlocks] RP resumed — applying {} pending changes ({} slots touched, deletes={}).",
            changes, dirtySlots.size(), hadDeletes);

    // ── Acquire generateRunning lock ──────────────────────────────
    // Prevents race with incoming packets that could trigger
    // scheduleSingleSlotReload/scheduleGenerateAndReload concurrently.
    // Same pattern as scheduleGenerateAndReload (line 1123).
    if (!generateRunning.compareAndSet(false, true)) {
        // Another generate cycle is already running — queue as pending
        pendingFullReload.set(true);
        return;
    }

    // ── Step 1-3: Heavy I/O on background thread ─────────────────────
    new Thread(() -> {
        // Step 1: If remove/setface/clearface/clearfaces happened during pause,
        // run ONE full generate() to write their files + cleanup stale slots.
        if (needsFullGenerate || hadDeletes) {
            ResourcePackGenerator.generate(client);
        }
        // NOTE: If only add/retexture happened, files are already on disk
        // from generateSingleSlot() — no full generate needed.

        // Step 2: Save consolidated client cache (one write, not N)
        SlotManager.saveToClientDir(client.runDirectory);

        // Step 3: Update texture hash (one computation, not N)
        String currentHash = computeTextureHash();
        saveCachedHash(client.runDirectory, currentHash);
        clearServerHash(client.runDirectory); // server hash is stale after local edits

        // ── Step 4-6: Back to main thread for reload ─────────────────
        client.execute(() -> {
            generateRunning.set(false);  // release lock before reload

            if (reloadInFlight.compareAndSet(false, true)) {
                client.reloadResources().thenRun(() -> client.execute(() -> {
                    reloadInFlight.set(false);
                    LOGGER.info("[CustomBlocks] Resume reload complete — {} changes applied.", changes);
                    sendResumeSummary(log, changes);
                    // Handle any packets that arrived during resume
                    if (pendingFullReload.compareAndSet(true, false)) {
                        scheduleGenerateAndReload(client, 500L);
                    }
                })).exceptionally(ex -> {
                    client.execute(() -> {
                        reloadInFlight.set(false);
                        // § 5: Circuit breaker — if reload fails, still unlock
                        LOGGER.error("[CustomBlocks] Resume reload FAILED.", ex);
                    });
                    return null;
                });
            }
        });
    }, "CustomBlocks-Resume").start();
});
```

**Why this won't lag or crash:**
- **No main-thread freeze:** `generate()`, `saveToClientDir()`, and `computeTextureHash()` all run on a background thread. Only state reset and `reloadResources()` run on the main thread.
- **No race conditions:** `generateRunning` lock prevents concurrent generate/reload cycles. `reloadInFlight` prevents double reloads. `pendingFullReload` queues any packets that arrive during resume.
- **Common case (add/retexture only):** Pack files were written incrementally by `generateSingleSlot()`. Resume just tells Minecraft to reload what's already on disk. No `generate()` needed.
- **remove/setface/clearface/clearfaces/tabicon case:** ONE `generate()` call writes all files. Still only ONE, not N.
- **One `saveToClientDir()` call:** Instead of N calls (one per edit), the client cache is written once on resume.
- **One `computeTextureHash()` call:** Instead of N calls, computed once.
- **One `reloadResources()` call:** Instead of N loading screens, exactly one.
- **Cleanup handled:** `cleanupStaleSlotFiles()` runs inside `generate()` when deletes happened — orphaned files removed before reload.

### Resume Summary — Chat Changelog

After the reload finishes, the player sees a branded summary listing every block that was created, deleted, or edited:

```
§8§m─────────────────────────────────────
§b§l  ▶ Resume Complete — 12 changes applied
§8§m─────────────────────────────────────
§a  + Created:  §fDiamond Ore, Ruby Block, Emerald Slab
§c  - Deleted:  §fOld Block, Test Block
§e  ✎ Edited:   §fSapphire, Gold Brick, Marble Pillar
§e  ✎ Edited:   §fQuartz Tile, Obsidian Slab
§8§m─────────────────────────────────────
```

**Implementation — `sendResumeSummary(List<String> log, int totalChanges)`:**
(Note: This runs **client-side** in `CustomBlocksClient.java`, so use `client.player`)
```java
private static void sendResumeSummary(List<String> log, int totalChanges) {
    MinecraftClient mc = MinecraftClient.getInstance();
    if (mc.player == null) return;  // safety check

    // Group by action type
    List<String> created = new ArrayList<>(), deleted = new ArrayList<>(), edited = new ArrayList<>();
    for (String entry : log) {
        String[] parts = entry.split(":", 2);
        String action = parts[0], name = parts.length > 1 ? parts[1] : "?";
        switch (action) {
            case "created"    -> created.add(name);
            case "deleted"    -> deleted.add(name);
            default           -> edited.add(name);  // retextured, edited, setface, etc.
        }
    }
    // Deduplicate edited (same block may have been edited multiple times)
    edited = new ArrayList<>(new LinkedHashSet<>(edited));

    // Build chat message — client-side overlay messages
    mc.player.sendMessage(Text.literal("§8§m─────────────────────────────────────"));
    mc.player.sendMessage(Text.literal("§b§l  ▶ Resume Complete — " + totalChanges + " changes applied"));
    mc.player.sendMessage(Text.literal("§8§m─────────────────────────────────────"));
    if (!created.isEmpty())
        mc.player.sendMessage(Text.literal("§a  + Created:  §f" + String.join(", ", created)));
    if (!deleted.isEmpty())
        mc.player.sendMessage(Text.literal("§c  - Deleted:  §f" + String.join(", ", deleted)));
    if (!edited.isEmpty()) {
        // Split into lines of ~5 names each to avoid chat overflow
        for (int i = 0; i < edited.size(); i += 5) {
            List<String> chunk = edited.subList(i, Math.min(i + 5, edited.size()));
            mc.player.sendMessage(Text.literal("§e  ✎ Edited:   §f" + String.join(", ", chunk)));
        }
    }
    mc.player.sendMessage(Text.literal("§8§m─────────────────────────────────────"));
}
```

**Details:**
- Created/deleted/edited names are **deduped** — editing the same block 5 times shows it once under "Edited"
- Long lists split into rows of 5 names so chat doesn't get a single 200-character line
- If nothing in a category (e.g. no deletes), that line is hidden
- Summary only shows **after** the reload finishes (in the `thenRun` callback), so the player sees it right when textures are visible

**Worst case: 100 creates + 50 deletes + 50 retextures + 20 face edits while paused:**
- During pause: 100 `generateSingleSlot()` calls write add/retexture files incrementally (no loading screen). Face edits are in-memory only.
- On resume: 1 `generate()` (~2-3 sec, needed because face edits + deletes happened) + 1 `saveToClientDir()` (~200ms) + 1 `reloadResources()` (~3-5 sec)
- Total resume time: **~5-8 seconds, one loading screen, no lag, no crash**

**Best case (only add/retexture, no face edits or deletes):**
- On resume: NO `generate()` needed — files already on disk. Just 1 `saveToClientDir()` + 1 `reloadResources()`
- Total resume time: **~3-5 seconds**

---

## 5. Implementation Phases — Step by Step

> **Rule:** One phase at a time. Build after EVERY edit. No commits without explicit approval.

```
PHASE 1 — Config Defaults (2 lines, 0 risk, 2 minutes)
════════════════════════════════════════════════════════
  File: CustomBlocksConfig.java
  ├── Line 28: maxSlots = 2048 → 600                           [R1]
  └── Line 56: texturePayloadsPerTick = 2 → 8                  [N1]
  
  Build & verify: server starts, config.json updated on first run.

PHASE 2 — Raw PNG Write (15 lines, low risk, 10 minutes)
════════════════════════════════════════════════════════
  File: ResourcePackGenerator.java
  └── Replace writePng() body:
      - Check first 4 bytes for PNG signature (0x89 0x50 0x4E 0x47)
      - If PNG → Files.write(dest.toPath(), imageBytes)
      - Else → fallback to NativeImage.read() + writeTo()  
                                                                [C1]
  Build & verify: join server, textures render correctly,
  pack gen time in log drops from ~15-30s to ~2-3s.

PHASE 3 — VoxelShape Cache (20 lines, medium risk, 15 minutes)
════════════════════════════════════════════════════════
  File: SlotBlock.java
  ├── Add: static ConcurrentHashMap<Integer, VoxelShape> SHAPE_CACHE
  ├── getOutlineShape/getCollisionShape/getCullingShape: 
  │   return SHAPE_CACHE.computeIfAbsent(slotIndex, ...)
  └── Add: static void invalidateShape(int slotIndex)          [S1]
  
  File: SlotManager.java
  └── In setShape(): call SlotBlock.invalidateShape(slotIndex)

  Build & verify: place shaped blocks (stairs, slabs), F3 TPS
  stays at 20. Break/rebuild shaped blocks — correct shapes.

PHASE 4 — Skip Wasted Work (15 lines, low risk, 15 minutes)
════════════════════════════════════════════════════════
  File: ResourcePackGenerator.java
  └── In generate() loop: if (data == null) continue;          [C2]
      (Note: cleanupStaleSlotFiles() at line 115 already
       handles stale file removal — skipping null slots
       avoids writing blockstate/model JSONs for empties.)
  
  File: SlotData.java
  ├── Add package-private static factory:                      [S2+F3]
  │   SlotData createTrusted(index, id, name, texture)
  │   → calls internal constructor with precomputedBroken=false
  │   → skips isBrokenTexture() PNG decode (~5-10ms each)
  │
  │   VERIFIED: 13 of 15 with*() methods already pass
  │   this.isBroken correctly. Only withTexture() re-checks
  │   (intentional — texture bytes changed). The real win is
  │   the 4-arg constructor path used by:
  │     • assign()         (line 247) — server-side creation
  │     • assignAtIndex()  (line 270) — client drip-feed receive
  └── Keep isBrokenTexture() ONLY in ImageProcessor.process()
      (line 80, 91, 109) where user uploads are validated.

  Build & verify: pack generates faster (fewer files),
  startup log shows no isBrokenTexture decode messages,
  join with 500 blocks skips 500 PNG decodes = -2.5-5 sec.

PHASE 5 — RP Pause/Resume Command (200 lines, medium risk, 1 hour)
════════════════════════════════════════════════════════
  Step 5a: Create RpPausePayload.java                         [RP-PAUSE]
  ├── Record with boolean paused
  ├── Id, CODEC, getId()
  └── Pattern: copy AnimSettingsPayload structure
  
  Step 5b: Register in CustomBlocksMod.java
  └── PayloadTypeRegistry.playS2C().register(RpPausePayload.ID, ...)
  
  Step 5c: Commands in CustomBlockCommand.java
  ├── static Set<UUID> rpPausedPlayers = ConcurrentHashMap.newKeySet()
  ├── /cb rp pause → chat confirmation
  ├── /cb rp pause confirm → add to set, send packet, feedback
  ├── /cb rp resume → chat confirmation  
  └── /cb rp resume confirm → remove from set, send packet, feedback
  
  Step 5d: GUI in GuiManager.java
  ├── buildResourceHub(): slots 29 + 33 with state-aware items
  └── handleResourceHubClick(): toggle/resume logic
  
  Step 5e: ResourcePackGenerator.java
  └── No visibility changes needed. Resume calls generate() which
      calls cleanupStaleSlotFiles() internally. cleanupStaleSlotFiles
      stays private (line 941). Only change: ensure generate() is
      accessible from CustomBlocksClient (already public static).

  Step 5f: Client in CustomBlocksClient.java
  ├── rpPaused (volatile boolean)
  ├── rpPausedChangeCount (AtomicInteger — § 6: ++ is non-atomic RMW!)
  ├── rpPausedDirtySlots (ConcurrentHashMap.newKeySet())
  ├── rpPausedHadDeletes (volatile boolean — single-writer OK)
  ├── rpPausedNeedsFullGenerate (volatile boolean — set by remove/setface/clearface/clearfaces/tabicon)
  ├── rpPauseLog (Collections.synchronizedList)
  ├── RpPausePayload receiver with optimized resume logic:
  │   - Skip reload if nothing changed (§ 2B: soft feedback)
  │   - If rpPausedNeedsFullGenerate OR hadDeletes → run ONE
  │     ResourcePackGenerator.generate(client) (writes all files,
  │     cleanupStaleSlotFiles runs inside generate for deletes)
  │   - ONE saveToClientDir() (not N)
  │   - ONE computeTextureHash() + saveCachedHash() + clearServerHash()
  │   - ONE reloadResources() (not N loading screens)
  │   - reloadResources().exceptionally() fallback — if reload fails,
  │     still unlock rpPaused and log error (§ 5: circuit breaker)
  ├── Guard scheduleSingleSlotReload() — still run generateSingleSlot,
  │   skip saveToClientDir/hash/reload, track dirty,
  │   use rpPausedChangeCount.incrementAndGet() (not ++)
  └── Guard scheduleGenerateAndReload() — triggered by remove/setface/
      clearface/clearfaces (CustomBlocksClient:967) + tabicon
      (CustomBlocksClient:941). Skip ENTIRELY
      if paused, set rpPausedNeedsFullGenerate=true, track dirty.
      NOT triggered by setprop/setshape/setcollision (metadata-only).
  
  Step 5g: Sensory feedback (§ 2B)
  ├── CustomBlockCommand.java: play sounds + particles on pause/resume confirm
  ├── GuiManager.java: play sounds on GUI button clicks (slots 29, 33)
  └── CustomBlocksClient.java: play chime + particles after resume reload

  Build & test:
  1. /cb rp pause → confirm → create 6 triangles → NO loading screens
     → /cb rp resume → confirm → ONE reload → all textures visible
  2. Pause → create 20 + delete 5 + retexture 10 → resume → ONE reload,
     no lag, no crash, all changes applied
  3. Test GUI buttons (ECHO_SHARD / AMETHYST_SHARD / NETHER_STAR items)
  4. Verify sounds play on every action (§ 2B)
  5. Verify resume summary chat appears AFTER loading screen

PHASE 6 — Client Data Optimization (120 lines, medium risk, 1 hour)
════════════════════════════════════════════════════════
  File: SlotManager.java
  ├── saveToClientDir(): write metadata-only JSON +             [C3]
  │   separate .dat texture files (mirror server design)
  ├── loadFromClientDir(): streaming JsonReader + read           [C4]
  │   textures from .dat files
  └── Client slots.json goes from 10 MB → 500 KB

  File: CustomBlocksClient.java
  └── Add cachedClientHash field, invalidate on                 [C6]
      texture change (same pattern as server)

  Build & verify: join → check customblocks_data/ has .dat files,
  no heap spike in profiler, hash computation skipped when cached.

PHASE 7 — Advanced (100 lines, medium-high risk, 2 hours)
════════════════════════════════════════════════════════
  File: ResourcePackGenerator.java
  ├── Parallel file writes with Executors.newFixedThreadPool(4) [parallel]
  └── pack_manifest.json with per-slot hashes —                 [incremental]
      only rewrite changed slots on rejoin

  File: CustomBlocksMod.java
  └── int[] lightCache for luminance lookups                    [S4]

  File: SlotBlock.java
  └── BlockSoundGroup[] and String[] name caches                [S5+S6]

  Build & verify: rejoin with 1 block changed → only 1 file
  rewritten. Pack gen < 1 sec. TPS rock-solid.

PHASE 8 — Server Intelligence (100 lines, low-medium risk, 1.5 hours)
════════════════════════════════════════════════════════
  File: SlotManager.java
  ├── Cache sortedSlots() — store sorted list, invalidate      [G1]
  │   on put()/remove(). Return cached copy. Eliminates
  │   15+ stream().filter().sorted().collect() per GUI click.
  ├── findFreeSlot() → TreeSet<Integer> freeSlots             [F1]
  │   Maintain set of free indices. O(log n) first() instead
  │   of O(n) linear scan.
  └── withTextureNoCopy() internal method on SlotData          [M1]
      Skip texture.clone() for trusted internal callers
      (loadAll, drip-feed receive). Keep clone for user API.

  File: SlotData.java
  └── VERIFIED: 13 of 15 with*() already pass this.isBroken.  [F3]
      Only withTexture() re-checks (correct — texture changed).
      Main F3 win is in Phase 4 (createTrusted factory).
      Phase 8 action: just verify no regressions after P4.

  File: SlotBlock.java
  └── Cache getSlotKey() result as a final field:              [NEW]
      private final String slotKey = "slot_" + slotIndex;
      Eliminates String concatenation on EVERY call to
      getOutlineShape, getCollisionShape, getCullingShape,
      getSoundGroup, getName, calcBlockBreakingDelta, onUse.
      Each of these creates "slot_" + int every invocation.

  File: GuiManager.java
  ├── Register ServerPlayConnectionEvents.DISCONNECT handler   [G3]
  │   to clean STATES, BACK_STACK, PENDING, HANDLERS,
  │   SEARCH_QUERIES, BULK_DELETE_SELECTIONS,
  │   FACE_CHANGE_*, RECENT_BLOCKS for disconnected UUID.
  └── Use cached sortedBlocks() everywhere (already fast       [G2]
      after G1 cache).

  File: NetworkManager.java
  └── sendFullSync(): bundle face textures into the main       [N5]
      "add" payload instead of separate "setface" packets.
      1500 payloads → 500 payloads = 3× faster join sync.

  File: TextureCache.java
  └── getOrLoad(): single CACHE.get() with null check          [G4]
      instead of containsKey() + get() double lookup.

  Build & verify: open GUI picker with 500 blocks → instant
  page navigation. Disconnect + rejoin → no stale state.
  Join sync log shows ~500 payloads (not ~1500).
```

---

## 6. Files to Edit — Complete List

| # | File | Phase | What changes |
|---|------|:-----:|-------------|
| 1 | `CustomBlocksConfig.java` | 1 | `maxSlots` 2048→600, `texturePayloadsPerTick` 2→8 |
| 2 | `ResourcePackGenerator.java` | 2, 4, 7 | Raw PNG write in `writePng()`. Skip empty slots in `generate()`. Parallel writes + incremental manifest. (`cleanupStaleSlotFiles` stays private — resume calls `generate()` which handles it.) |
| 3 | `SlotBlock.java` | 3, 7, 8 | VoxelShape cache + invalidation. Sound/name caches. **Cache `slotKey` as final field** (eliminates String concat in every shape/sound/name lookup). |
| 4 | `SlotManager.java` | 3, 6, 8 | Call `SlotBlock.invalidateShape()` on `setShape()`. Client binary `.dat` storage. Streaming reader. Cache `sortedSlots()`. `TreeSet<Integer>` for free slots. |
| 5 | `SlotData.java` | 4, 8 | `createTrusted()` factory (skip `isBrokenTexture` for drip-feed/load). `withTextureNoCopy()` for trusted internal callers. |
| 6 | **`RpPausePayload.java`** | 5 | **NEW** — S2C payload record with `boolean paused` |
| 7 | `CustomBlocksMod.java` | 5, 7 | Register `RpPausePayload` S2C. Luminance `int[]` cache. |
| 8 | `CustomBlockCommand.java` | 5 | `rpPausedPlayers` set. `pause`/`resume`/`confirm` sub-commands. Clickable chat. **Sounds + particles** on confirm (§ 2B). |
| 9 | `GuiManager.java` | 5, 8 | Pause/Resume buttons (ECHO_SHARD/NETHER_STAR items, slots 29, 33) + click handling + sounds. Disconnect cleanup for per-player maps (§ 5). |
| 10 | `CustomBlocksClient.java` | 5, 6 | `RpPausePayload` receiver (`AtomicInteger` counter). `rpPaused` flags. Guard reload calls. Client hash caching. **Resume sound/particles** (§ 2B). |
| 11 | `TextureCache.java` | 8 | Single-lookup `getOrLoad()` instead of double containsKey+get |
| 12 | `NetworkManager.java` | 8 | Bundle face textures into main "add" payload during `sendFullSync` |

---

## 7. Before vs After — Every Scenario

| Scenario | Before | After Phase 1-2 | After Phase 1-5 | After All |
|----------|:------:|:----------------:|:----------------:|:---------:|
| **First join (new player)** | ~30 sec loading | ~8 sec | ~8 sec | ~3 sec |
| **Rejoin (nothing changed)** | ~5 sec (cache hit) | ~3 sec | ~3 sec | ~1 sec |
| **Rejoin (3 blocks changed)** | ~30 sec (full regen) | ~8 sec | ~8 sec | ~0.5 sec |
| **Create 6 triangle variants** | **6 loading screens** (30+ sec total) | 6 × 2-3 sec = 15 sec | **0 screens** (paused) + 1 resume = **3 sec** | **0 + 1 = <1 sec** |
| **Create 20 blocks in a row** | **20 loading screens** | 20 × 2-3 sec | **0 screens + 1 resume = 3-5 sec** | **0 + 1 = <1 sec** |
| **50 creates + 20 deletes + 30 edits (paused)** | N/A (impossible without pause) | N/A | **0 screens + 1 resume = 5-8 sec** (generate runs for deletes) | **0 + 1 = <2 sec** |
| **Server startup (500 blocks)** | ~5 sec | ~4 sec | ~3 sec | ~2 sec |
| **5 players join at once** | 12.5 sec drip each | 3 sec each | 3 sec each | **1.5 sec** (face bundling) |
| **GUI picker page navigation (500 blocks)** | ~50ms (re-sort) | ~50ms | ~50ms | **<1ms** (cached) |
| **Server memory after 1000 unique players** | Leaks ~50MB | Leaks ~50MB | Leaks ~50MB | **Cleaned on disconnect** |
| **Shaped blocks TPS** | Lag spikes | Lag spikes | **Zero** (cached) | **Zero** |
| **Server RAM during save** | +60-100 MB spike | +60-100 MB | +60-100 MB | ~0 extra |
| **Client RAM during load** | +30-120 MB spike | +30-120 MB | +30-120 MB | ~2 MB |
| **Pack generation (client)** | 15-30 sec | **2-3 sec** | **2-3 sec** | **<1 sec** |
| **Pack generation while paused** | N/A | N/A | Files written, **no loading screen** | Same |

---

## 8. Edge Cases

### RP Pause Edge Cases
| Scenario | What happens |
|----------|-------------|
| **Disconnect while paused** | Client: `rpPaused` resets to `false` on next join (volatile default). Server: UUID removed from `rpPausedPlayers` set in disconnect handler. |
| **Multiple edits while paused** | `add`/`retexture` → `generateSingleSlot()` (files written). `remove`/`setface`/`clearface`/`clearfaces`/`tabicon` → `scheduleGenerateAndReload` skipped entirely (sets `rpPausedNeedsFullGenerate`; `remove` also sets `rpPausedHadDeletes`). `setprop`/`setshape`/`setcollision`/`rename` → metadata-only, no reload. On resume: if needsFullGenerate → one `generate()` + `reloadResources()`. Otherwise just `reloadResources()`. |
| **50 creates + 20 deletes + 30 retextures while paused** | 50 `generateSingleSlot()` calls for creates + 30 for retextures run during pause (fast). Deletes set `rpPausedHadDeletes=true`. On resume: one `generate()` (because deletes) + one save + one reload = ~5-8 sec total. No lag, no crash. |
| **Resume with 200+ pending changes** | Resume is always O(1) reloads regardless of count. If only add/retexture: no `generate()`. If any face/delete: one `generate()` (~2-3 sec for 500 blocks). Total: 3-8 sec. |
| **Join sync while paused** | `joinBurst` already suppresses reloads during join. No conflict with `rpPaused`. |
| **`/cb reload` while paused** | `broadcastFullSync` uses the `joinBurst` path. The resulting `sync_done` reload should also be suppressed. Both `scheduleGenerateAndReload` and `scheduleSingleSlotReload` check `rpPaused`. |
| **GUI click = direct confirm** | No second chat prompt when using GUI buttons. Clicking in the GUI IS the confirmation. |
| **Resume when nothing changed** | `rpPausedChangeCount.get() == 0` → no reload fires, just unpauses cleanly. § 2B: soft feedback ("Nothing changed while paused."). |

### Performance Edge Cases
| Scenario | What happens |
|----------|-------------|
| **1000+ blocks, first join** | Drip-feed at 8/tick: 1000 ÷ 8 ÷ 20 = 6.25 sec. Pack gen with raw PNG write: ~5 sec. Total: ~12 sec (vs ~55 sec before). |
| **2000+ blocks, first join** | Drip-feed: 12.5 sec. Pack gen: ~10 sec. Total: ~23 sec (vs ~170 sec before). |
| **50 shaped blocks in one chunk** | Before: TPS drops to ~5-10. After S1 cache: zero impact — shapes cached once, VoxelShapes.union() never re-runs. |
| **20 rapid block creates** | Before: 20 × `isBrokenTexture` PNG decodes. After S2: zero decodes. |
| **VoxelShape change during gameplay** | Cache invalidated for that slot only. Next query rebuilds and re-caches. Other slots unaffected. |
| **maxSlots lowered (e.g. 2048→500)** | Stale files for slots 500-2048 remain on disk. User should delete `resourcepacks/CustomBlocks/` folder. `cleanupStaleSlotFiles()` handles this on next full generation. |
| **Client with 2048 maxSlots, server with 600** | After R1 fix: new clients default to 600. Existing clients keep 2048 until they update their config. No crash — just empty slots. |

---

## 9. Testing Checklist

### Phase 1 — Config defaults
- [ ] Server starts with new defaults (`maxSlots=600`, `texturePayloadsPerTick=8`)
- [ ] Existing servers with custom config keep their values
- [ ] New server generates `config.json` with updated defaults

### Phase 2 — Raw PNG write
- [ ] Join server → all 534 textures render correctly
- [ ] Animated textures still work (mcmeta files intact)
- [ ] Face textures render on all 6 sides
- [ ] Server log: pack generation time < 5 sec (was 15-30)

### Phase 3 — VoxelShape cache
- [ ] Shaped blocks (stairs, slabs, pillar, etc.) show correct outline
- [ ] Breaking shaped blocks → correct collision
- [ ] `/cb shape set <id> slab` → shape updates immediately
- [ ] F3 TPS stays at 20 with 50+ shaped blocks loaded
- [ ] No memory leak (cache doesn't grow beyond number of shaped slots)

### Phase 4 — Skip wasted work
- [ ] Pack has NO placeholder PNGs for empty slots
- [ ] No `isBrokenTexture` warnings in startup log
- [ ] Block upload still detects broken textures (user upload path)

### Phase 5 — RP Pause/Resume
- [ ] `/cb rp pause` → branded confirmation in chat
- [ ] Click `[✔ CONFIRM PAUSE]` → "PAUSED" feedback
- [ ] Create 6 triangle variants → **ZERO loading screens**
- [ ] Textures ARE written to disk (check `resourcepacks/CustomBlocks/`)
- [ ] `/cb rp resume` → branded confirmation → click confirm
- [ ] **ONE loading screen** → all 6 textures visible
- [ ] GUI buttons in `/cb rp`: slot 29 shows pause state, slot 33 resume
- [ ] GUI click = direct toggle (no chat prompt)
- [ ] Disconnect while paused → rejoin → paused state reset
- [ ] Resume when nothing changed → no reload, no loading screen
- [ ] **Heavy batch test:** pause → create 20 blocks + delete 5 + retexture 10 → resume → ONE loading screen, all changes applied, no crash, no lag
- [ ] **Stress test:** pause → create 50 triangle variants (rapid-fire) → resume → ONE loading screen, all 50 visible
- [ ] Resume log shows: `RP resumed — applying N pending changes (M slots touched, deletes=true/false)`
- [ ] **Add/retexture only:** No full `generate()` on resume — only `reloadResources()` picks up files from `generateSingleSlot()`
- [ ] **setface/delete test:** pause → setface on a block + delete another → resume → `generate()` runs ONCE, then `reloadResources()` — face visible, deleted block gone
- [ ] `rpPausedNeedsFullGenerate` flag set correctly (true when remove/setface/clearface/clearfaces/tabicon happens during pause — NOT setprop/setshape/setcollision, which are metadata-only)
- [ ] Client `slots.json` written once on resume (not 50 times during pause)
- [ ] **Resume summary:** after reload, branded chat shows created/deleted/edited blocks by name
- [ ] Summary dedupes edits (edit same block 5 times → shows once under "Edited")
- [ ] Summary splits long lists into rows of 5 names
- [ ] Summary hides empty categories (no "Deleted:" line if nothing was deleted)
- [ ] Summary appears AFTER loading screen finishes (not before)
- [ ] **Sensory feedback (§ 2B):** sound plays on pause confirm, resume confirm, resume complete, and cancel
- [ ] **GUI items (§ 2A):** ECHO_SHARD for pause, NETHER_STAR for resume (not boring concrete)
- [ ] **Circuit breaker:** if `reloadResources()` fails, state unlocks and error is logged (no permanent lock)
- [ ] **Concurrency (§ 6):** `rpPausedChangeCount` is `AtomicInteger`, uses `.incrementAndGet()` and `.getAndSet(0)`
- [ ] **cleanupStaleSlotFiles:** stays private, called internally by `generate()` — verify stale files removed after resume with deletes

### Phase 6 — Client data optimization
- [ ] `customblocks_data/` has `.dat` files (not all in JSON)
- [ ] `slots.json` is metadata-only (< 500 KB, not 10 MB)
- [ ] Rejoin → no 60 MB+ heap spike (check with `-verbose:gc` or profiler)
- [ ] Hash caching: second `computeTextureHash()` call is instant

### Phase 7 — Advanced
- [ ] Pack gen with parallel writes: < 1 sec (check log)
- [ ] Rejoin with 1 changed block: only 1 file rewritten (check `pack_manifest.json`)
- [ ] Luminance correct on all custom blocks after cache change
- [ ] Sound types correct on break/step/place

### Phase 8 — Server Intelligence
- [ ] GUI picker opens instantly at 500+ blocks (no re-sort lag)
- [ ] Page navigation in picker: <1ms response (cached sorted list)
- [ ] Search results use cached sorted list (not re-sort per query)
- [ ] Player disconnect → all 8 per-player maps cleaned (verify with `/debug` or log)
- [ ] Server memory stable after 100 connect/disconnect cycles
- [ ] Join sync log shows ~500 drip-feed payloads (not ~1500 with separate face packets)
- [ ] `findFreeSlot()` returns instantly even at 599/600 slots used
- [ ] `TextureCache.getOrLoad()` does 1 map operation per call (not 2)
- [ ] `SlotBlock.getSlotKey()` returns cached field (no String concat — verify with debugger)
- [ ] All 7 `SlotBlock` methods that call `getSlotKey()` use the cached field
- [ ] `withTexture()` still calls `isBrokenTexture()` (intentional — texture bytes changed)
- [ ] All other `with*()` methods still pass `this.isBroken` (no regression from Phase 4)

---

## 10. Layered Defense Audit (§ 5)

> **Every critical system in this plan must have multiple layers of protection.**

### RP Pause/Resume — Defense Layers

| Layer | Protection | Implementation |
|-------|-----------|---------------|
| **1. Atomic ops** | `rpPausedChangeCount` is `AtomicInteger` — safe for concurrent increment | `getAndSet(0)` on resume for atomic read-and-reset |
| **2. Volatile flags** | `rpPaused` and `rpPausedHadDeletes` are `volatile` — writes visible across threads | Single-writer assumption: only client thread writes, Netty reads |
| **3. Thread-safe collections** | `rpPausedDirtySlots` = `ConcurrentHashMap.newKeySet()`, `rpPauseLog` = `synchronizedList` | No external iteration without copy |
| **4. client.execute() + Thread** | State reset on main thread, heavy I/O on background `"CustomBlocks-Resume"` thread, reload back on main thread | Prevents UI freeze (generate=2-3s) while keeping Minecraft API calls on correct thread |
| **5. Null/empty guards** | `if (changes == 0) return;` — no reload when nothing changed | Prevents wasted loading screen |
| **6. Stale file cleanup** | `cleanupStaleSlotFiles()` runs inside `generate()` when deletes/face-edits happened | Prevents Minecraft from loading orphaned textures |
| **7. Circuit breaker** | `reloadResources().exceptionally()` catches reload failure, unlocks state, logs error | Prevents permanent lock if reload crashes |
| **8. Disconnect reset** | Server removes UUID from `rpPausedPlayers` on disconnect; client volatile resets on next join | No stale pause state survives session |

### VoxelShape Cache — Defense Layers

| Layer | Protection |
|-------|-----------|
| **1. ConcurrentHashMap** | Thread-safe read/write from any thread |
| **2. computeIfAbsent** | Atomic insertion — no double-computation |
| **3. invalidateShape()** | Called from `setShape()` — cache never serves stale shapes |
| **4. Null fallback** | `buildVoxelShape()` returns `null` for non-shaped → `VoxelShapes.fullCube()` default |

### Sorted List Cache — Defense Layers

| Layer | Protection |
|-------|-----------|
| **1. volatile reference** | Cached list is stored in a `volatile` field — writes from `put()`/`remove()` visible to GUI thread |
| **2. Invalidate-on-write** | `put()` and `remove()` set cache to `null` — next `sortedSlots()` call rebuilds |
| **3. Immutable list** | Cache returns `List.copyOf()` — callers cannot corrupt the cache |
| **4. Lazy rebuild** | Only rebuilt when actually needed (next GUI open), not on every mutation |

---

## 11. Definition of Done (§ 10)

> **Every phase must pass these three tests before moving to the next.**

### 🧑‍🤝‍🧑 Friend Test
> *"If I handed my Minecraft to a friend who has never seen this mod, could they figure out pause/resume without me explaining it?"*

- [ ] GUI buttons have clear labels and deep tooltips
- [ ] Chat confirmations use plain English (no jargon like "drip-feed" or "debounce")
- [ ] Resume summary makes sense to someone who doesn't know what a "slot" is
- [ ] Error states give human-readable guidance ("Try /cb rp resume" not "rpPaused flag mismatch")

### 💧 Liquid UI Test
> *"Does every interaction feel smooth, responsive, and intentional?"*

- [ ] Every click has a sound (§ 2B: silence is a bug)
- [ ] Pause/resume confirmations appear instantly (no visible delay)
- [ ] Resume loading screen = one smooth transition (not a flicker-then-load)
- [ ] GUI items use legendary-tier items with enchant glint (§ 2A)
- [ ] No orphaned UI state (e.g. paused indicator stuck after disconnect)

### 🤯 WOW Test
> *"Would this make someone say 'this mod is insane'?"*

- [ ] 500 blocks load in <3 seconds (people are used to 30+)
- [ ] Creating 6 triangle variants takes <5 seconds total (not 30+ seconds of loading screens)
- [ ] Resume summary feels like an achievement log — branded, categorized, satisfying
- [ ] The mod NEVER crashes, freezes, or corrupts data — even under extreme stress

### 📋 Technical Verification
- [ ] `./gradlew build` passes after every single file change
- [ ] Git checkpoint (commit) before and after each phase
- [ ] No new warnings in server or client logs
- [ ] F3 TPS stays at 20.0 under all test scenarios
- [ ] Memory profiler shows no leaks after 100 connect/disconnect cycles

---

## 12. Cleanup: Remove rpEnforceOnJoin

> **Why:** Feature is already force-disabled and blocked in GUI. Causes disconnects on shared hosting (port 8080 firewalled). Dead code — keeping it confuses users and adds risk.

### Files to Edit

#### 1. CustomBlocksConfig.java (8 references)
- [ ] Delete field `rpEnforceOnJoin`
- [ ] Delete field `rpPromptMessage`
- [ ] Delete field `rpKickMessage`
- [ ] Remove from `load()` — the `getBool("rpEnforceOnJoin", ...)` line
- [ ] Remove from `load()` — the `getString("rpPromptMessage", ...)` line
- [ ] Remove from `load()` — the `getString("rpKickMessage", ...)` line
- [ ] Remove from `load()` — the force-false warning block (lines 141-147)
- [ ] Remove from `save()` — the three `addProperty` calls
- [ ] Remove from `needsRewrite()` — the `rpEnforceOnJoin` check

#### 2. NetworkManager.java (1 reference)
- [ ] Delete the entire "Mandatory Resource Pack Enforcement" block (~15 lines) that sends the RP URL on player join

#### 3. GuiManager.java (9 references)
- [ ] Resource Pack Hub (slot 4 compass): remove the "RP Enforce: ON/OFF" line from the lore
- [ ] Resource Pack Hub (slot 24): remove the toggle button
- [ ] Resource Pack Hub click handler (slot 24): remove the toggle logic
- [ ] Config GUI (slot 10): remove the "Auto-Send Texture Pack" toggle
- [ ] Config GUI click handler (case 10): remove the toggle logic

### Testing
- [ ] Build after every edit (`gradlew build`)
- [ ] Verify config.json no longer contains rpEnforceOnJoin, rpPromptMessage, rpKickMessage
- [ ] Verify Resource Pack Hub GUI loads without the toggle
- [ ] Verify Config GUI loads without the toggle
- [ ] Verify joining a server works without any RP enforcement

### Notes
- Surgical edits only — don't touch anything else
- Keep the HTTP Resource Pack Server itself (it still serves the ZIP for the download link feature)
- If a toggle slot is removed, replace it with glass pane to keep layout clean

---

## 13. Feature Roadmap — 8 New Features

> *Each feature has a clear MVP and stretch goals. Governed by [THE ROYAL DIRECTIVE](THE_ROYAL_DIRECTIVE.md).*

### At a Glance

| # | Feature | MVP Effort | What the MVP delivers |
|---|---------|-----------|----------------------|
| 1 | **Texture Filters** | ~200 lines | Tint, brightness, grayscale, invert, mirror, rotate — applied via GUI |
| 2 | **Color Palette Generator** | ~80 lines | One-click 16-color palette from any block using tint |
| 3 | **Texture Randomizer** | ~60 lines | 2–8 variant textures per block via blockstate `variants` array |
| 4 | **Particle Emitter Blocks** | ~150 lines | Pick a particle type + rate + direction, stored in SlotData, spawned via tick event |
| 5 | **Redstone-Reactive Blocks** | ~300 lines | ON/OFF texture swap via BlockEntity + blockstate property |
| 6 | **Block Crafting Recipes** | ~200 lines | Shaped/shapeless recipe defined via 3×3 GUI, injected at runtime |
| 7 | **Block Marketplace** | ~150 lines | Browse + import from Cloud Vault (already built). Publish = upload to cloud |
| 8 | **Blueprint Wand** | ~400 lines | Select region → save → paste with rotation. CB~ sharing |

**Total MVP estimate: ~1,600 lines across all 8 features.**

### Feature 1: Texture Filters

**What:** An in-game image editor. Open Block Editor → click "Filters" → apply effects with one click.

**MVP (build this first):**
- **6 filters:** Tint (color + intensity), Brightness (+/-), Grayscale (toggle), Invert (toggle), Mirror (H/V), Rotate (90/180/270)
- Applied to the **main texture only**
- Each filter creates an undo entry
- GUI: 54-slot chest. Top row = filter buttons (legendary items). Center = preview. Bottom = Save / Cancel
- All image processing in `ImageProcessor.java` (pure `BufferedImage` pixel math — no new libraries)

**Stretch goals:** Per-face filters, Blur, Sharpen, Noise, Posterize, Contrast, Hue Shift, Saturation, stacking, named presets, batch apply, `/cb filter` command.

**Files:** `ImageProcessor.java`, `GuiManager.java`, `GuiMode.java` + `GuiState.java`

### Feature 2: Color Palette Generator

**What:** One block → 16 color variants automatically. Like Minecraft's wool/concrete/terracotta sets.

**MVP:**
- Editor button: "Generate Palette" → GUI showing 16 dye colors as wool items
- Click a color → creates a tinted copy (reuses Feature 1's tint logic)
- Auto-naming: `{originalId}_{color}` — copies all properties, skips if variant exists

**Stretch goals:** Custom hex colors, preset palettes, intensity slider, tint modes, naming patterns, `/cb palette` command.

**Files:** `GuiManager.java`, `ImageProcessor.java` | **Dependency:** Feature 1 (tint filter)

### Feature 3: Texture Randomizer

**What:** One block, multiple texture variants. Each placed block randomly picks one → walls look natural.

**MVP:**
- Editor button: "Texture Variants" → GUI to add 2–8 variant textures (paste URL)
- Variants stored in SlotData (`List<byte[]> variants`)
- `ResourcePackGenerator` outputs blockstate `variants` array with `weight` fields
- Minecraft handles randomization natively — zero runtime code

**Stretch goals:** Per-variant weights, rotation randomization, per-face random, auto-generate via filters, simulate preview, `/cb variants` command.

**Files:** `SlotData.java`, `ResourcePackGenerator.java`, `GuiManager.java`

### Feature 4: Particle Emitter Blocks

**What:** Custom blocks that emit particles. Fire, smoke, sparkles, water drips.

**MVP:**
- Editor button: "Particle Effects" → GUI showing ~15 particle types as items
- Click to select type → set rate and direction (Up/Random)
- Stored in SlotData as `particleConfig` JSON string
- Client tick event spawns particles for nearby emitter blocks. One emitter per block.

**Stretch goals:** 3 emitters per block, full customization (spread, speed, offset, color, size), conditional spawning, server-wide limit, `/cb particles` command.

**Files:** `SlotData.java`, `CustomBlocksClient.java`, `GuiManager.java` | **Note:** Client-side rendering only for MVP — no BlockEntity.

### Feature 5: Redstone-Reactive Blocks

**What:** Blocks that change texture/light when powered by redstone.

**MVP:**
- Editor button: "Redstone Behavior" → GUI with Unpowered/Powered texture slots
- Paste a URL for powered state, optionally set powered light level (0–15)
- `SlotBlock` uses **BlockEntity** + `powered=true/false` blockstate property
- `neighborUpdate()` checks redstone signal → updates blockstate
- Resource pack generates two model variants

**Stretch goals:** Shape change, visibility toggle (secret doors), pulse/cycle modes, delay, proximity/time-of-day detection, `/cb redstone` command.

**Files:** `SlotBlock.java`, `SlotData.java`, `ResourcePackGenerator.java`, `GuiManager.java` | **Risk:** Highest — BlockEntity is a major architecture change.

### Feature 6: Block Crafting Recipes

**What:** Define crafting recipes via in-game GUI. No JSON files, no datapacks.

**MVP:**
- Editor button: "Set Recipe" → GUI with 3×3 grid + output slot
- Click ingredient slots → item picker (vanilla + custom blocks)
- Shaped and shapeless modes (toggle). Recipes injected at runtime via Fabric.
- Saved in `config/customblocks/recipes.json`, survives restart.

**Stretch goals:** Stonecutter/smithing table, item tags, output quantity, recipe book, drop on break, loot tables, `/cb recipe` command.

**Files:** `GuiManager.java`, new `RecipeManager.java`, `CustomBlocksMod.java` | **Note:** Research `ServerRecipeManager` + Fabric dynamic recipes first.

### Feature 7: Block Marketplace

**What:** Browse and download community-shared blocks in-game. Built on existing Cloud Share.

**MVP:**
- Main GUI button: "Marketplace" → paginated list from Cloud Vault (GET requests)
- Click a block → imports it. "Publish" button in Editor → uploads to cloud.
- Blocks tagged with title + creator server name.

**Stretch goals:** Categories, tags, search, sort by popularity, favorites, collections, admin controls, reporting, rate limiting, `/cb market` command.

**Files:** `GuiManager.java`, `CustomBlockCommand.java`, Cloud Vault worker | **Dependency:** Cloud Share (already working — `uploadShareToCloud`/`fetchCloudShareJson` exist).

### Feature 8: Blueprint Wand

**What:** Select a region → save as blueprint → paste elsewhere with rotation. Share via CB~ codes.

**MVP:**
- `/cb blueprint wand` → custom item (Blaze Rod with enchant glint)
- Right-click two blocks to set corners (particle indicators)
- `/cb blueprint save/paste/list` commands
- Serialized as JSON in `config/customblocks/blueprints/`

**Stretch goals:** Rotation/mirror, ghost preview, GUI browser, size limits, air/vanilla filter, CB~ export/import, cloud sync, offset nudging.

**Files:** New `BlueprintManager.java`, new `BlueprintWandItem.java`, `CustomBlockCommand.java`, `CustomBlocksMod.java` | **Risk:** Medium — serialization must handle missing blocks.

### Feature Implementation Order

```
Feature 1: Texture Filters        ← foundation, everything depends on this
    ↓
Feature 2: Color Palette           ← uses Feature 1's tint
    ↓
Feature 3: Texture Randomizer      ← pure resource pack, low risk
    ↓
Feature 4: Particle Emitters       ← client-side only, additive
    ↓
Feature 6: Crafting Recipes        ← additive, no architecture changes
    ↓
Feature 5: Redstone-Reactive       ← biggest risk (BlockEntity), do it when stable
    ↓
Feature 7: Marketplace             ← needs Cloud Share working (it is)
    ↓
Feature 8: Blueprint Wand          ← most complex, do last
```

Each feature is **independent and revertable**. Git checkpoint before each one.
