# Core Networking & Reloading (PACK, REL, RT)

# Active Batch: PACK2

*This batch was moved from the main backlog. View the full backlog here: [MASTERPLAN.md](../MASTERPLAN.md)*

---

## 1. PACK2: Modded Clients Receive Vanilla Fallback Pack

[x] **Code Written** — already implemented in a prior session (ResourcePackServer.java lines 67–72)
[x] **Tested In-Game** — confirmed 2026-06-02, survives /cb reload

**State:** 🔴 BROKEN — modded clients receive the server's vanilla fallback resource pack, overriding their local mod pack. Causes: all magic items show as dye items, placed custom blocks go transparent.

**Root Cause (confirmed by audit):**
The dye mappings in `ServerPackGenerator.java` are intentional fallbacks for vanilla (non-modded) players. The bug is that `sendPackToPlayer()` in `ResourcePackServer.java` sends this vanilla fallback pack to ALL clients — including modded ones who have the CustomBlocks mod installed and generate their own resource pack locally. The vanilla pack loads with higher priority and overrides the mod pack, so tools become dyes and blocks go transparent.

**Files:** `network/ResourcePackServer.java`

**Technical Details:**
* `sendPackToPlayer(ServerPlayerEntity player)` has no check for whether the player has the mod installed.
* Modded clients can be detected via `ServerPlayNetworking.canSend(player, SlotUpdatePayload.ID)` — returns `true` only if the client can receive the mod's custom packet (vanilla clients cannot).
* If `canSend` returns `true` → player has the mod → skip sending the vanilla fallback pack.
* If `canSend` returns `false` → vanilla player → send the pack as normal.
* This also fixes PACK2 during `/cb rp pause` — the pause guard was broken because the pack was being sent anyway after modifications.

**The Fix:**
In `ResourcePackServer.java`, inside `sendPackToPlayer()`, add a guard immediately after the hash null-check:
```java
if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
        .canSend(player, com.customblocks.network.SlotUpdatePayload.ID)) {
    return false;
}
```

**Expected Side Effects (good):**
* NF4 — tool colors turning into dyes should resolve automatically.
* COL8 — all magic items turning into dyes should resolve automatically.

**Test Plan:**
1. Join with mod installed. Confirm tools show as colored triangles/squares (not dyes).
2. Place 3 custom blocks. Confirm they're visible.
3. `/cb rp pause` → delete one block → remaining 2 blocks stay visible, tools stay colored.
4. `/cb rp resume` → everything still correct.
5. `/cb reload` → blocks survive, no rejoin needed.
6. Check server console for: `[CustomBlocks] PACK2: skipping vanilla fallback for modded client [yourname]`



# Active Batch: RT1

*This batch was moved from the main backlog. View the full backlog here: [MASTERPLAN.md](../MASTERPLAN.md)*

---

## 1. RT1: Rectangle Tool Block Stays Purple 30+ Seconds

[ ] **Code Written**
[ ] **Tested In-Game**

**State:** 🔴 BROKEN — confirmed in-game 2026-06-02. Block placed by rectangle tool stays purple/black for 30+ seconds or permanently until rejoin. HIGH PRIORITY.

**Files:** `item/RectangleToolItem.java`, `network/ResourcePackServer.java`, `network/ServerPackGenerator.java`

**What is known:**
* The fix to broadcast the slot BEFORE placing the block was already applied (clients know the block exists).
* Despite this, the block still shows as purple for 30+ seconds.
* This is NOT normal pack rebuild delay — a pack rebuild should complete in seconds, not 30+.
* The block eventually resolves (without rejoin) in some cases, which means the pack IS being rebuilt and delivered eventually — just extremely slowly.

**Investigation required:**
1. Read `RectangleToolItem.java` — confirm the broadcast-before-place order is actually in place
2. Read `ResourcePackServer.java` — find where `sendUpdateToAllPlayers()` / pack rebuild is triggered after a rectangle tool block is placed. Is it being triggered at all?
3. Check if there's a debounce/delay on the pack rebuild that's too long (e.g. 30-second debounce timer)
4. Check if the Cloud Vault upload is blocking the pack delivery (upload must complete before the URL is sent to players)
5. Check server logs during rectangle tool use for `[CustomBlocks]` / `[PackBuilder]` lines

**Hypothesis:** The 30-second delay matches a debounce timer. There may be a `NOTIFY_PENDING` guard or a delay in `ResourcePackServer.java` that holds off sending the pack update until N seconds of inactivity. Rectangle tool blocks may be triggering this debounce instead of an immediate rebuild.



### REL1 — `/cb reload` Data-Loss / Blocks Break Visually
**State:** ✅ CONFIRMED IN-GAME (2026-06-02) — /cb reload works, no data loss, no rejoin needed.
**Files:** `core/SlotManager.java`, `command/CustomBlockCommand.java`
**Priority:** 🔴

**Root cause:** `flushSave()` called `IO_EXECUTOR.shutdown()`, permanently killing the IO thread mid-session. Plus a tick-based batch-loader race (the pack was rebuilt before all blocks finished loading).

**Fix built:** New `flushSaveForReload()` that saves without shutting down IO + a wait loop for `startupLoadInProgress = false` before the pack rebuild + a `RELOAD_IN_PROGRESS` lock to prevent concurrent reloads. Data now saves correctly (rejoin proves it). The remaining visual breakage is PACK1 — the pack never reaches the client after reload.

**Test (after PACK1):** `/cb reload` → all blocks survive and stay visible, no rejoin needed.

### PACK1
Extracted from history. Blocks REL1 and RT1.
