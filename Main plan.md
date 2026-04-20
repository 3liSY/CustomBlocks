# CustomBlocks — Stability & Performance Plan
> Forensically verified: April 20, 2026 — every line number and method signature confirmed against actual code.
>
> **Governing document:** `customblocks_master_directive.md` — Surgical edits only. Research-first. Zero regressions.

---

## Completed Fixes (verified in codebase)

| Fix | What was done | File | Evidence |
|-----|--------------|------|----------|
| **Fix 1** ✅ | Drip-feed throttling restored | `NetworkManager.java:68-71` | `broadcastUpdate` → `enqueueForPlayer` (queues, not direct send). `onServerTick` (line 165) drains at 256KB/tick. |
| **Fix 2** ✅ | ZIP builder capped to 1 thread | `ResourcePackServer.java:29-34` | `PACK_BUILDER = Executors.newSingleThreadExecutor()` + `pendingBuilds` AtomicInteger coalescing. |
| **Fix 5** ✅ | Texture delivery on join restored | `NetworkManager.java:112-142` | `sendFullSync` enqueues ALL textures via `TextureQueue` + `sync_done` sentinel. Client `sync_done` handler (line 166) sets `syncDoneReceived = true`. |

---

## Remaining Fixes (ordered by priority)

---

### Fix 7 + Fix 9 (CRITICAL): Bulletproof Instant Join

**Two bugs in the join flow. Must be fixed together — they share the same code path.**

#### Bug A — Join Failure (Fix 7)

**Symptom:** First 1-2 join attempts fail with `Pipeline has no outbound protocol configured`. Player must reconnect 2-3 times.

**Root cause:** `NetworkManager.onPlayerJoin()` (line 212) calls `sendFullSync(player)` immediately inside the `ServerPlayConnectionEvents.JOIN` callback. The Netty pipeline hasn't finished the CONFIGURATION → PLAY codec swap. `ServerPlayNetworking.send()` queues the packet, but the Netty encoder throws `EncoderException` because the outbound codec isn't installed yet.

**Evidence (server log):**
```
[08:47:16] Pipeline has no outbound protocol configured  ← attempt 1 FAIL
[08:47:47] Pipeline has no outbound protocol configured  ← attempt 2 FAIL
[08:47:53] 3liSY logged in                               ← attempt 3 works
```

**Exact code path:**
```
CustomBlocksMod.java:419  → ServerPlayConnectionEvents.JOIN.register(...)
  → NetworkManager.onPlayerJoin(handler.player)          [line 211]
    → sendFullSync(player)                                [line 212]
      → ServerPlayNetworking.send(player, syncPayload)    [line 110]  ← BOOM: encoder not ready
```

**The fix — client-initiated sync:**
Instead of the server pushing data on JOIN, the client sends a C2S "I'm ready" packet. If the C2S succeeds, the S2C pipeline is guaranteed ready too.

**Changes (4 files):**

1. **NEW FILE: `src/main/java/com/customblocks/network/SyncRequestPayload.java`**
   - Record implementing `CustomPayload`
   - Zero data fields (marker packet only)
   - Needs: `public static final Id<SyncRequestPayload> ID`, `CODEC`, `getId()`
   - Pattern: identical to existing `AnimSettingsPayload` but with no fields

2. **`CustomBlocksMod.java`** — Register the new payload (2 additions):
   - After line 273 (existing C2S registrations): `PayloadTypeRegistry.playC2S().register(SyncRequestPayload.ID, SyncRequestPayload.CODEC)`
   - After line 283 (existing C2S handlers): `ServerPlayNetworking.registerGlobalReceiver(SyncRequestPayload.ID, (payload, context) -> { context.server().execute(() -> NetworkManager.onPlayerJoin(context.player())); });`

3. **`NetworkManager.java` line 212:**
   - DELETE: `sendFullSync(player);`
   - KEEP: the entire RP enforcement block (lines 214-251) — it has its own 2-second delay

4. **`CustomBlocksClient.java`** — Send the request on join (add after line 116):
   - Register `ClientPlayConnectionEvents.JOIN` callback
   - Inside: `ClientPlayNetworking.send(new SyncRequestPayload())`

#### Bug B — Slow Join / Cache Wipe (Fix 9)

**Symptom:** Returning players wait 30-120 seconds on the loading screen every time they join, even though nothing changed.

**Root cause (TWO bugs working together):**

**Bug B1:** `CustomBlocksClient.java` line 126-127 wipes all cached textures on every join:
```java
SlotManager.clearAll();           // line 126 — destroys 439 cached textures
TextureCache.invalidateAll();     // line 127 — destroys render cache
```
After wiping, the drip-feed re-sends ALL 439 textures, then `ResourcePackGenerator.generate()` rewrites ALL PNGs, then `client.reloadResources()` triggers the full loading screen.

**Bug B2:** The `packExists` check at line 356-357 has a WRONG PATH:
```java
boolean packExists = new File(client.runDirectory,
        "customblocks_generated/assets").isDirectory();
//       ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
//       WRONG: pack is at "resourcepacks/customblocks_generated/assets"
//       This ALWAYS returns false → cache HIT can NEVER fire
```
Even without `clearAll()`, the cache HIT at line 359 would still fail because `packExists` is always `false`.

**Same bug exists in `scheduleAnimMetaReload` at line 424:**
```java
File packRoot = new File(client.runDirectory, "customblocks_generated");
//                                             ^^^^^^^^^^^^^^^^^^^^^^^
//                                             WRONG: should be "resourcepacks/customblocks_generated"
//                                             Anim .mcmeta writes go to wrong directory → never take effect
```

**The fix (replace lines 122-145 in FullSyncPayload handler):**
```java
client.execute(() -> {
    syncDoneReceived = false;
    joinBurst        = true;

    // ── Smart merge instead of clearAll() ────────────────────────
    // Step 1: Build set of server-side IDs
    Set<String> serverIds = new HashSet<>();
    for (FullSyncPayload.SlotEntry e : payload.entries()) {
        serverIds.add(e.customId());
    }

    // Step 2: Remove local blocks the server no longer has
    List<String> toRemove = new ArrayList<>();
    for (SlotData local : SlotManager.allSlots()) {
        if (!serverIds.contains(local.customId)) {
            toRemove.add(local.customId);
        }
    }
    for (String id : toRemove) {
        SlotManager.remove(id);
    }
    TextureCache.invalidateAll();  // render cache still needs refresh

    // Step 3: Merge — update metadata, KEEP existing textures
    for (FullSyncPayload.SlotEntry e : payload.entries()) {
        SlotData existing = SlotManager.getById(e.customId());
        if (existing != null) {
            if (existing.index != e.index()) {
                SlotManager.remove(e.customId());
                SlotManager.assignAtIndex(e.index(), e.customId(), e.displayName(), existing.texture);
            }
            SlotManager.setProperties(e.customId(), e.lightLevel(), e.hardness(), e.soundType());
            if (e.animMeta() != null && !e.animMeta().isEmpty())
                SlotManager.setAnimMeta(e.customId(), e.animMeta());
        } else {
            SlotManager.assignAtIndex(e.index(), e.customId(), e.displayName(), null);
            SlotManager.setProperties(e.customId(), e.lightLevel(), e.hardness(), e.soundType());
            if (e.animMeta() != null && !e.animMeta().isEmpty())
                SlotManager.setAnimMeta(e.customId(), e.animMeta());
        }
    }

    if (payload.tabIconTexture() != null)
        SlotManager.setTabIconTexture(payload.tabIconTexture());

    long fallbackDebounce = com.customblocks.CustomBlocksConfig.joinDebounceMs > 0
            ? com.customblocks.CustomBlocksConfig.joinDebounceMs : 4000L;
    scheduleGenerateAndReload(client, fallbackDebounce);
});
```

**Also fix the two wrong paths:**
- Line 357: `"customblocks_generated/assets"` → `"resourcepacks/customblocks_generated/assets"`
- Line 424: `"customblocks_generated"` → `"resourcepacks/customblocks_generated"`

**Also add missing imports** at top of `CustomBlocksClient.java`:
```java
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
```
(`SlotData` is already imported at line 7.)

**Verified method signatures (all exist in `SlotManager.java`):**
- `clearAll()` — line 331 (will be REMOVED from call site)
- `getById(String customId)` — line 139, returns `SlotData`
- `allSlots()` — line 171, returns `Collection<SlotData>`
- `remove(String customId)` — line 317, returns `SlotData`
- `assignAtIndex(int index, String customId, String displayName, byte[] texture)` — line 261
- `setProperties(String id, int light, float hard, String sound)` — line 406
- `setAnimMeta(String id, String meta)` — line 377

**Verified `FullSyncPayload.SlotEntry` fields (line 22-31 of `FullSyncPayload.java`):**
- `index()` — int
- `customId()` — String
- `displayName()` — String
- `texture()` — byte[] (always null in full sync)
- `lightLevel()` — int
- `hardness()` — float
- `soundType()` — String
- `animMeta()` — String

#### Edge Cases
| Scenario | What happens |
|---|---|
| **Returning player, nothing changed** | Merge keeps all 439 textures. `computeTextureHash()` matches cached hash. `packExists` now correctly checks `resourcepacks/` path → true. Cache HIT → **instant join, no loading screen.** |
| **Returning player, 3 blocks retextured** | Merge keeps 436, drip-feed delivers 3 new. Cache MISS → regenerate once. |
| **Returning player, 5 blocks deleted on server** | Step 2 removes 5 stale, keeps 434. Cache MISS → regenerate once. |
| **New player, no local cache** | All 439 arrive via drip-feed (same as current). |
| **Player disconnects mid-drip-feed, reconnects** | Partial cache preserved, drip-feed fills gaps. |
| **Block re-IDed on server** | Old ID removed in step 2, new ID created in step 3. |
| **Slot index reassigned** | Step 3 detects `existing.index != e.index()`, removes and re-assigns with cached texture. |
| **maxSlots lowered (e.g. 2048→500)** | Old stale files for slots 500-2048 remain on disk. `ResourcePackGenerator.generate()` only writes 0-499 on next run. Stale PNGs cause "Corrupt PNG" errors on reload. **Mitigation:** user should delete `resourcepacks/customblocks_generated` folder after changing maxSlots. |

#### Verification
**Server log:**
```
[CustomBlocks] Received sync request from 3liSY          ← Fix 7: client-initiated
[CustomBlocks] Drip-feed queued for 3liSY: 439 textures  ← drip-feed still runs
```
**Client log (returning player):**
```
[CustomBlocks] Texture cache HIT (hash=a1b2c3d4e5). Skipping generation + reload.
```
**Client log (new player):**
```
[CustomBlocks] Texture cache MISS (cur=a1b2, cached=null, packExists=false). Regenerating.
```

---

### Fix 3 (HIGH): Optimize the safety check in `saveAllAsync`

**File:** `SlotManager.java` lines 645-719

**Problem:** Every save reads the ENTIRE existing `slots.json` (30-50MB) via `Files.readString()` + `JsonParser.parseString()` to count textured slots. Creates ~60-100MB of temporary heap allocations per save. On shared hosting with 512MB-1GB RAM, doing this rapidly (e.g. creating 20 blocks in a row) triggers an OOM timeout and kicks players.

**Fix:** Do NOT remove the safety check (violates Layered Defense). Instead, make it cost zero memory:
- When saving `slots.json`, also write a tiny `textured_count.txt` containing just an integer (e.g., `428`).
- On the *next* save, read `textured_count.txt` (a 3-byte file) instead of parsing the 50MB JSON.
- If `textured_count.txt` is missing, fallback to parsing the JSON once to recreate it.

**Impact:** Keeps the catastrophic data loss protection, but reduces memory usage during saves from 60-100MB down to literally 0MB. Allows the server to survive extreme pressure (20+ rapid saves).

---

### Fix 4 (HIGH): Fix `/cb reload` — don't block server thread

**File:** `CustomBlockCommand.java`

**Problem:** `cmdReload` calls `loadAll()` synchronously on the server thread. `loadAll()` reads and parses a 30-50MB JSON file, blocking ticks for 10+ seconds. Also race condition: `flushSave()` is async but `loadAll()` reads immediately after.

**Fix:**
- Submit the entire reload sequence to `IO_EXECUTOR`:
  1. Wait for pending save to complete
  2. Read and parse `slots.json` off the server thread
  3. Then `server.execute(() -> { /* swap maps + broadcastFullSync */ })`
- Send "Reloading..." chat message immediately, "Done" on completion

**Impact:** Prevents `/cb reload` from crashing/timing out players.

---

### Fix 6 + Fix 8 (MEDIUM): Data corruption cleanup

**Fix 6 — Duplicate slot_316:**
`SlotManager.java` — Two customIds (`_atest` and `zomoruda`) share index 316. In `assignAtIndex()` (line 261), existing duplicates are already handled. One-time manual cleanup: remove `_atest` from `slots.json`.

**Fix 8 — Sanitize `&` in input:**
`GuiManager.java` — Rename converts `&` → `§`. Re-ID converts `&atest` → `_atest`. Strip `§` and `&` from display names. Validate resulting ID isn't empty or a single `_`.

---

### Fix 11 (MEDIUM): Universal block recoloring — remove "black" name requirement

**Files:** `ColorSquareItem.java`, `ColorTriangleItem.java`

**Problem:** The Color Square and Color Triangle tools currently only recognize a block as recolorable if its name or ID contains "black" (the assumed base color). This means players cannot right-click and recolor blocks that were created without "black" in their identifier.

**Expected behavior:** ANY custom block should be treated as recolorable — the tools should apply the selected color regardless of the block's name/ID. If a block has no color keyword in its name, treat it as if it were "black" (the default/base color).

**Fix:**
- Remove or bypass the "black" name/ID check in both `ColorSquareItem` and `ColorTriangleItem`
- When the target block has no recognized color in its name, default to treating it as `black` (base color)
- This allows right-clicking any custom block to apply a color overlay or change its background

**Impact:** All custom blocks become recolorable on right-click, not just those named with "black." Significantly improves creative workflow.

---

### Fix 10 (LOW): Rename generated resource pack to "CustomBlocks"

**4 locations (2 files):**
| File | Line | Current | New |
|------|------|---------|-----|
| `ResourcePackGenerator.java` | 41 | `"resourcepacks/customblocks_generated"` | `"resourcepacks/CustomBlocks"` |
| `CustomBlocksClient.java` | 44 | `"file/customblocks_generated"` | `"file/CustomBlocks"` |
| `CustomBlocksClient.java` | 357 | `"resourcepacks/customblocks_generated/assets"` | `"resourcepacks/CustomBlocks/assets"` |
| `CustomBlocksClient.java` | 424 | `"resourcepacks/customblocks_generated"` | `"resourcepacks/CustomBlocks"` |

**Note:** Lines 357 and 424 will already be fixed to include the `resourcepacks/` prefix in Fix 9. Fix 10 just changes the folder name itself.

**User action required:** Delete old `resourcepacks/customblocks_generated` folder after updating.

---

## Implementation Order

```
Fix 2 ✅ DONE
Fix 1 ✅ DONE
Fix 5 ✅ DONE
    ↓
Fix 7 + Fix 9  →  build + test
    ↓
    First-attempt join + instant returning joins + path bug fixes
    ↓
Fix 3  →  build + test
    ↓
    Saves 60-100MB peak memory per save
    ↓
Fix 4  →  build + test
    ↓
    /cb reload no longer freezes server
    ↓
Fix 6 + Fix 8  →  build + test
    ↓
    Data corruption cleanup
    ↓
Fix 10  →  build + test
    ↓
    Rename to "CustomBlocks"  →  DONE
```

Each fix is a single surgical edit. Build after EVERY edit. No commits or pushes without explicit approval.

---

## Files to Modify

| File | Fixes | Changes |
|---|---|---|
| `SyncRequestPayload.java` | 7 | **NEW** — C2S marker payload for client-initiated sync |
| `CustomBlocksMod.java` | 7 | Register `SyncRequestPayload` C2S type + handler |
| `NetworkManager.java` | 7 | Remove `sendFullSync(player)` from `onPlayerJoin` (line 212) |
| `CustomBlocksClient.java` | 7, 9 | Send `SyncRequestPayload` on join; replace `clearAll()` with smart merge; fix 2 wrong paths (lines 357, 424) |
| `SlotManager.java` | 3, 6 | Remove safety check (lines 645-719); manual cleanup of `_atest` |
| `CustomBlockCommand.java` | 4 | Move `/cb reload` off server thread |
| `GuiManager.java` | 8 | Sanitize `&`/`§` in rename/re-id |
| `ResourcePackGenerator.java` | 10 | Rename folder path (line 41) |

---

## Testing Checklist

### Join Flow (Fix 7 + Fix 9)
- [ ] First join attempt works (no Pipeline error)
- [ ] Returning player: cache HIT → no loading screen
- [ ] New player: all textures arrive via drip-feed → pack generates → blocks display correctly
- [ ] Player disconnects mid-drip-feed, reconnects: partial cache preserved, gaps filled
- [ ] Server log shows `Received sync request from PlayerName`
- [ ] Client log shows `Texture cache HIT` for returning players
- [ ] Animation mcmeta changes apply immediately (path fix verified)

### Triangle Spam (Fix 1 ✅ + Fix 2 ✅)
- [ ] Create 6 blocks with triangles → server stays alive
- [ ] Only 1 ZIP build runs at a time
- [ ] Memory stays under 1GB

### /cb reload (Fix 4)
- [ ] Run `/cb reload` with 2 players online → no crash
- [ ] Players don't get timed out during reload
- [ ] Blocks correctly re-synced after

### Stability & Pressure Handling
- [ ] Server runs for 30+ minutes with active block editing → no crash
- [ ] No "Duplicate SlotData" warnings after Fix 6
- [ ] **Extreme Stress Test:** Create 20+ blocks rapidly using triangles. Server MUST queue them cleanly, memory MUST stay stable, and players MUST NOT be kicked/timed out.

---

## Success Criteria
1. **Join works on first attempt** — no Pipeline errors, no reconnecting
2. **Returning players join instantly** — cache HIT, no loading screen
3. **Server survives EXTREME pressure** (20+ simultaneous blocks without crashing or timing out)
4. **`/cb reload` works** without freezing or timing out players
5. **New players see textures** on first join (not purple/black)
6. **Memory stays under 1GB** during saves and ZIP generation
7. **No data corruption** warnings in logs
