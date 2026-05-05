# GIF Stacked-Frames Bug — Deep-Dive Plan

**Date:** 2026-04-18
**Symptom:** User uploads a GIF, gets a block that shows all GIF frames stacked vertically (strip) instead of animating.
**Screenshot evidence:** "Beegif" block — yellow/black stripes filling the block face instead of a moving bee.

---

## 1. What the symptom actually means

This is **NOT** a GIF decoding bug. The screenshot proves:

| What we see | What it means |
|---|---|
| A block with vertical stripes covering its face | The vertical frame-strip PNG reached the client |
| Strip is stretched to fill the 16×16 face | Minecraft is rendering it as a static texture |
| No animation cycling between frames | **The `.mcmeta` animation metadata is not being applied** |

Translation: the decoder IS producing the multi-frame strip correctly. Something between "strip bytes leaves the server" and "Minecraft reads the pack" is losing the `.mcmeta` file or not applying it.

---

## 2. The full pipeline (what SHOULD happen)

Traced through the codebase:

```
[1] User pastes GIF URL
     │
     ▼
[2] GuiManager.CREATE_URL handler           (GuiManager.java:562-591)
     │   calls ImageProcessor.downloadAndProcess(url, size)
     ▼
[3] ImageProcessor.processAnimation         (ImageProcessor.java:455-644)
     │   produces ProcessResult(bytes=verticalStripPNG,
     │                          mcmeta="{\"animation\":{\"interpolate\":true,\"frames\":[...]}}",
     │                          frameCount=N)
     ▼
[4] SlotManager.assign(id, name, fb)        (stores PNG only)
    SlotManager.setAnimMeta(id, fa)          (stores mcmeta JSON string on SlotData)
    SlotManager.saveAll()                    (persists to disk)
     │
     ▼
[5] NetworkManager.broadcastUpdate(...)      (sends SlotUpdatePayload with animMeta field)
     │
     ▼  (server → client packet)
     │
[6] CustomBlocksClient "add" handler         (CustomBlocksClient.java:164-175)
     │   SlotManager.assignAtIndex(...)
     │   SlotManager.setAnimMeta(id, animMeta)   ← sets on CLIENT SlotManager
     │   scheduleGenerateAndReload(client, 2000ms)
     ▼
[7] After 2-second debounce:
     ResourcePackGenerator.generate(client)   (ResourcePackGenerator.java:38-287)
     │   Writes PNG to: resourcepacks/customblocks_generated/assets/customblocks/textures/block/slot_N.png
     │   IF data.isAnimated() && data.animMeta != null:
     │      Writes mcmeta to:  ...textures/block/slot_N.png.mcmeta
     ▼
[8] client.reloadResources()                 (Minecraft rebuilds texture atlas)
     │
     ▼
[9] Minecraft renders block with animation ✓
```

**Step 7** is the critical one. If `data.animMeta` is null or empty at that moment, the mcmeta file is **silently skipped** — and we get the stacked-frame bug we're seeing.

---

## 3. Hypotheses, ordered by likelihood

### H1 — **Most likely**: the block was created before the fix chain
**Evidence:**
- The user said "existing gifs on the server still have broken texture" (earlier in chat).
- The "Beegif" block may have been uploaded with the original broken `processAnimation` that returned null for all GIFs.
- When `processAnimation` returned null, the code silently fell through to the static pipeline (this silent fallback was only fixed in commit `8f8bcdd`).
- The SlotData that was saved has **texture=some PNG, animMeta=null**.
- On every subsequent regeneration, `data.isAnimated()` returns false, so no mcmeta is written.

**BUT** — the texture we see is clearly a multi-frame strip, not a single frame. So `processAnimation` DID run successfully at some point. Either:
- It ran on a newer build that fixed the first-frame extraction but didn't propagate animMeta
- The block was retextured (`retexture` path) with the strip but animMeta wasn't attached

**How to verify:** check if the SlotData JSON on disk for the Beegif block has a non-null `animMeta` field.

**Fix:** re-upload the GIF block from scratch with the current build. If that works → old blocks need migration.

### H2 — Race: reload scheduled before `setAnimMeta` packet arrives
**Evidence:**
- `scheduleGenerateAndReload` uses a 2-second debounce, so this is unlikely in isolation.
- BUT the "add" packet handler runs `SlotManager.setAnimMeta(...)` AFTER `assignAtIndex`. If the packet was malformed, missing, or dropped, the client would have texture but no animMeta.

**How to verify:** add logging in `ResourcePackGenerator.generate()` that prints, for every animated slot: "wrote mcmeta: true/false, animMeta null?: true/false".

**Fix:** if animMeta IS null on client after packet arrival, investigate packet serialization.

### H3 — The mcmeta file is being written but Minecraft's texture atlas doesn't see it
**Evidence:** `client.reloadResources()` should pick up the mcmeta. Minecraft does this correctly in vanilla.
**Weakness of this hypothesis:** Minecraft has handled `.mcmeta` for a decade. Very unlikely to be broken.

**How to verify:** manually open `resourcepacks/customblocks_generated/assets/customblocks/textures/block/slot_N.png.mcmeta` on the user's machine and confirm it exists and contains valid JSON.

**Fix:** if mcmeta exists on disk but isn't applied, force a `client.reloadResources()` a second time or inject the pack higher in the pack priority list.

### H4 — Server-side ZIP pack overrides client-side generated pack
**Evidence:**
- The server sends the client a URL to its HTTP-served pack ZIP (`ResourcePackSendS2CPacket`).
- If that pack is accessible, Minecraft uses THAT pack, ignoring the client-generated one.
- On shared hosting, port 8080 is blocked → the server pack is unreachable → client pack is used.
- BUT on a self-hosted server with port-forward, the server pack IS used, and it's generated by `ServerPackGenerator.java`.
- `ServerPackGenerator` DOES write the mcmeta (line 90-92 of `ServerPackGenerator.java`), so that path should work.

**Weakness:** the user has been seeing this issue for weeks on the server. They're probably on shared hosting where port 8080 is blocked. So server pack isn't in use.

**How to verify:** ask the user what hosting they use. If shared hosting (Aternos, mcserverhost, etc.), port 8080 is blocked.

### H5 — NativeImage re-encoding corrupts the strip's dimensions
**Evidence:** `ResourcePackGenerator.writePng` uses `NativeImage.read(...)` → `img.writeTo(path)`. This decodes and re-encodes the PNG. If NativeImage normalizes dimensions in any way, a 128×512 strip might become something weird.

**Why this is unlikely:** NativeImage preserves dimensions. This has never been reported as a Minecraft bug.

**How to verify:** check the actual dimensions of the written `slot_N.png` file on disk (hex-dump the IHDR or open in an image viewer).

---

## 4. Priority-ordered fix plan

### Fix A — Loud diagnostic logging (ALWAYS do this first)
Add logging to `ResourcePackGenerator.generate()` so we know exactly what's happening:

```java
// In the loop, for each slot:
if (data != null && data.texture != null && data.texture.length > 0) {
    writePng(data.texture, texDest);
    boolean animated = data.isAnimated();
    boolean metaPresent = data.animMeta != null && !data.animMeta.isEmpty();
    CustomBlocksMod.LOGGER.info(
        "[CustomBlocks] Pack gen slot_{} ({}): animated={}, animMeta={} chars",
        i, data.customId, animated,
        data.animMeta != null ? data.animMeta.length() : 0);
    if (animated && metaPresent) {
        // ... write mcmeta
        CustomBlocksMod.LOGGER.info("[CustomBlocks] Wrote mcmeta: {}", mcmetaDest.getName());
    } else if (animated && !metaPresent) {
        CustomBlocksMod.LOGGER.warn(
            "[CustomBlocks] Slot {} is marked animated but animMeta is null/empty - animation WILL NOT WORK",
            i);
    }
}
```

After running this with logging + uploading a fresh GIF, the logs will tell us:
- If the slot is marked animated → packet reached client
- If animMeta is present → setAnimMeta succeeded
- If mcmeta was written → ResourcePackGenerator did its job

From the logs we can pinpoint the exact failure point.

### Fix B — Force regenerate on every animated-texture update
If animMeta sometimes arrives AFTER the reload was scheduled, we can fix by:

Adding a special case in the client packet handler: when `add`/`retexture` payload has `animMeta` set, ALWAYS reschedule the reload (even if one is in flight).

```java
// In CustomBlocksClient.java "add" and "retexture" cases:
if (payload.animMeta() != null && !payload.animMeta().isEmpty()) {
    SlotManager.setAnimMeta(payload.customId(), payload.animMeta());
    // Force a fresh reload since animMeta arrived
    scheduleGenerateAndReload(client, 500L);  // shorter debounce, urgent
}
```

### Fix C — Auto-heal orphan animated strips
If a texture LOOKS like a vertical frame strip (height > width, height divisible by width) but no animMeta is present, synthesize a default mcmeta in ResourcePackGenerator:

```java
// In ResourcePackGenerator.generate(), after writing texture:
int frames = ImageProcessor.getVerticalFrames(data.texture);
if (frames > 1) {
    String effectiveMeta = data.animMeta;
    if (effectiveMeta == null || effectiveMeta.isEmpty()) {
        // Heal: synthesize uniform mcmeta from frame count
        effectiveMeta = synthesizeDefaultMcmeta(frames);
        CustomBlocksMod.LOGGER.warn(
            "[CustomBlocks] Slot {} has strip texture but no animMeta - synthesizing default",
            i);
    }
    try (FileWriter fw = new FileWriter(mcmetaDest, StandardCharsets.UTF_8)) {
        fw.write(effectiveMeta);
    }
}
```

This is a **self-healing safety net** — even if animMeta never arrives, any block whose texture IS a strip will still animate. This fixes old pre-existing blocks that were uploaded before the animMeta propagation was fixed.

### Fix D — Mirror-write animMeta to SlotData on texture update
In `SlotManager.updateTexture(id, tex)`, auto-detect if the new texture is a vertical strip and synthesize default animMeta if the current animMeta is null:

```java
public static void updateTexture(String id, byte[] tex) {
    update(id, d -> {
        SlotData updated = d.withTexture(tex);
        if (d.animMeta == null || d.animMeta.isEmpty()) {
            int frames = ImageProcessor.getVerticalFrames(tex);
            if (frames > 1) {
                updated = updated.withAnimMeta(synthesizeDefaultMcmeta(frames));
            }
        }
        return updated;
    });
}
```

This makes animMeta self-consistent with the texture dimensions, eliminating the whole class of "strip texture but no animMeta" bugs.

---

## 5. Recommended order of operation

1. **Step 1 (safe):** Add Fix A (diagnostic logging). No behavior change.
2. **Step 2:** User runs the new build, creates a FRESH GIF block, sends the log snippet.
3. **Step 3:** Based on what the log says:
   - If "animMeta null at generate time" → apply Fix B (force-reload on animMeta) + Fix D (mirror-write)
   - If "mcmeta written but animation still missing" → apply Fix C (auto-heal) and investigate pack priority
   - If "packet never carries animMeta" → investigate NetworkManager serialization
4. **Step 4:** Apply Fix C (auto-heal) regardless — it fixes existing blocks that were uploaded during broken builds.
5. **Step 5:** Apply Fix D (mirror-write) — makes the whole system consistent even against future regressions.

**Fix C + Fix D together would eliminate the bug class entirely** because:
- Any texture that IS a strip will automatically get an mcmeta written (Fix C)
- Any texture saved to a SlotData will auto-gain animMeta if it's a strip (Fix D)
- Even if a packet drops animMeta, the strip alone is enough to re-animate

---

## 6. Test protocol

After applying fixes, verify:

| Test | Setup | Expected |
|---|---|---|
| T1 | Fresh GIF block, new name | Animates correctly |
| T2 | Existing "Beegif" block (old, stacked) | Animates after `/cb reload` or block re-upload |
| T3 | GIF as face texture (top) | Face animates |
| T4 | Upload a GIF, disconnect, reconnect | Still animates after rejoin |
| T5 | Delete the block, re-upload same URL | Animates |
| T6 | Upload GIF that `processAnimation` can't decode | Chat error (not silent static) |
| T7 | Check log for the new diagnostic lines | Shows `animated=true, animMeta=N chars, wrote mcmeta` |

---

## 7. What I'm NOT going to change

- **The decoder itself** (already hardened in commit `d31ead1`). The user's screenshot proves it's working.
- **Pack format number** (34). The texture IS loaded; pack_format is correct for MC 1.21.x.
- **Block model JSON**. `cube_all` with a single texture works for animation — Minecraft auto-detects based on the mcmeta.
- **HTTP server pack generation**. The user is on shared hosting, so it's likely not in use.

---

## 8. Open questions that require user input

1. Is the "Beegif" block a fresh upload (after today's commits `d31ead1` / `8f8bcdd`) or an existing one from last week?
2. What hosting are you using? (Aternos / shared / self-hosted with port forward)
3. After next build, would you be willing to send me the log file showing a fresh GIF upload attempt?

---

## 9. Bottom line

**The decoder works.** The bug is on the path from "processAnimation output" → "written mcmeta file". My top guess: the `animMeta` on the SlotData is null when `ResourcePackGenerator.generate()` runs, either because:
- The block was made before animMeta propagation was fixed, OR
- A timing/race issue loses the animMeta between packet arrival and pack regeneration.

Fix plan is:
1. Add diagnostic logging
2. Apply Fix C (auto-heal on vertical strip)
3. Apply Fix D (mirror-write animMeta when texture is a strip)
4. Test

**Do not push yet.** Review this plan and tell me which fixes to apply. I will not modify code until you approve.
