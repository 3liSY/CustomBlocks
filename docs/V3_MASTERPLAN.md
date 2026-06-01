# CustomBlocks — Master Fix, Improvement & Feature Plan

---

## How to Read This Plan

Each item follows this structure:

- **The Problem** — what's broken, missing, or painful (from the player's perspective)
- **The Solution** — exactly what to build, with enough detail to implement without guessing
- **The Experience** — what the player sees, feels, and hears when interacting with it
- **Edge Cases** — what could go wrong, and how to handle it gracefully
- **Files** — which source files are involved

---

## 📋 Feature Status (Updated 2026-05-26)

> This table reflects actual code state as of the srb-1.0-improvements branch. See REMAINING_REPAIR_MASTERPLAN.md for the full finding list.

### Complete (fully implemented, build-verified)
- Async startup texture loading (SlotManager.tickStartupLoad / loadTexturesAsync)
- Cloud Vault pack delivery (cloudPackSecret, ResourcePackServer upload, Worker POST /pack)
- AI query route (Worker POST /ai, CB_SERVER_TOKEN auth, Groq API, rate limiting)
- AI config fields wired (aiApiProvider, aiApiKey, aiMaxVariations, aiTextureStyle in load/save)
- SpotBugs 0 warnings (reduced from 261)
- Script GUI (paginated list, run, step-view, delete with chat confirm)
- GUI handleClick() default safety case
- Dead GuiMode values removed (FIND_PORT_GUI, ASSISTANT_CONTROL, PERMISSIONS_SUMMARY, DRESS_GUI, GRADIENT_GUI, IMPORT_WIZARD, RETEXTURE_WIZARD, AI_PICKER, DROP_CONFIG)
- TrashManager (deleted-block trash bin with GZip persistence, restore, auto-purge)
- All core managers (MacroManager, SnapshotManager, DraftManager, FavoritesManager, LockManager, CategoryManager, etc.)

### Partial (implementation exists, known gaps documented in REMAINING_REPAIR_MASTERPLAN.md)
- COLOR_PICKER — routes to Color Studio as fallback (R.7)
- WELCOME_MENU — functional but minimal content (R.13)
- Stability AI provider — OpenAI works; Stability silently falls back (R.8)
- ResourcePackGenerator — stale per-face files not cleaned on single-slot update (R.28)
- Cloud Vault KV TTL — set to 24h; should be removed or extended (R.32)

### Stub only (opens screen, no real workflow)
- None remaining after srb-1.0 repair pass.

### Not yet started (documented in REMAINING_REPAIR_MASTERPLAN.md)
- /cb config ai-* subcommands (R.9)
- ColorTriangleItem recolor preview GUI (R.11)
- Script/Macro storage separation (R.12)
- DropConfigManager startup wiring verification (R.14)
- POST /pack rate limiting in Worker (R.31)
- Client-side power-of-2 texture validation (R.29)

---

## ⚠️ CRITICAL CORRECTIONS FROM CODE AUDIT (2026-05-17)

These facts were confirmed by forensic analysis of every source file. Any plan item or developer instruction that contradicts these facts must defer to this table.

### Correct field names in CustomBlocksConfig.java
| Plan Used | Correct Name | Note |
|---|---|---|
| `cloudVaultUrl` | `cloudShareUrl` | |
| `cloudVaultEnabled` | `cloudShareEnabled` | |
| `cloudPackSecret` | **EXISTS** | Added as new field; wired in load/save/missingManagedKeys; never rendered in GUI |
| `colorSquareFallbackMode` | **DOES NOT EXIST** | Must be added as a new field |
| `SlotData.textureStatus` | `SlotData.isBroken` (transient boolean) | |
| `SlotData.importBgMode` | **DOES NOT EXIST** | Must be added as a new field |
| `maxUndoDepth` default | Current code: **20**. Plan target after item 1.28 redesign: **50** | |
| `maxUndoDepth` max clamp | **100** (unchanged — item 1.28 keeps this) | |
| `joinDebounceMs` | **REMOVED** — replaced by count-verified signal-driven sync (item 1.20). Delete from config entirely. | |

### Classes that MUST BE CREATED FROM SCRATCH (none of these exist in the codebase)
~~MacroManager · SnapshotManager · PlacementStats · DraftManager · BlockNotesManager · AutoCategorizeManager · WelcomeManager · FavoritesManager · CategoryManager · LockManager~~

> **UPDATE (2026-05-26):** All ten classes now exist in `src/main/java/com/customblocks/core/`. This warning is obsolete. Do not create them from scratch — they have real implementations.

### Command behavior corrections
| Plan Assumed | Reality |
|---|---|
| `/cb` (bare) opens main GUI | Both `/cb` and `/customblock` (bare) call the identical `cmdGui()` function and open the **same** main GUI. There is no Feature Menu. |
| `/cb unfavorite` needs to be added | Already registered in CustomBlockCommand.java |
| Texture writes are atomic | Face texture writes (`slot_N.dat`) are **NOT atomic** — direct Files.write() |
| AnimSettingsPayload is rate-limited | **No rate limiting** — confirmed DoS vector |
| Sort Blocks Menu exists | **No sort menu exists at all.** `SlotManager.sortedSlots()` always sorts alphabetically by display name. There is no user-configurable sort order, no sort GUI mode in `GuiMode.java`, and the string "Sort preference applied" does not appear anywhere in the codebase. |

### Highest-priority unimplemented items (production impact today)
1. **Cloud Vault pack upload** — Friends have NEVER seen block textures (ports blocked on MCServerHost)
2. **Async startup loading** — 15.9s freeze still kicks players every restart
3. **SSRF protection** — Any OP can make the server fetch private/internal network URLs

### Security — locked fields (do not expose in GUI or config)

| Field | Rule | Reason |
|---|---|---|
| `cloudShareUrl` | **Hardcoded constant** — remove from `config.json` and Config GUI entirely | Any OP could redirect uploads to a server they control and intercept all resource pack data |
| `cloudPackSecret` | **`config.json` only** — never render in the in-game Config GUI | Any OP with GUI access could read the upload secret and forge pack uploads |
| Config GUI slot 33 | **Remove this slot** from `buildConfigGui()` and `handleConfigGuiClick()` in `GuiManager.java` | This slot currently exposes the Cloud Vault URL as an editable field |

Three confirmed active security vulnerabilities requiring new plan items:

1. **SSRF (Server-Side Request Forgery)** — `ImageProcessor.java` fetches any URL an OP supplies, including `http://localhost/`, `http://169.254.169.254/` (AWS metadata/credentials), and private LAN addresses. Fix: validate and blocklist private IP ranges before every HTTP request. See item 1.25.
2. **AnimSettingsPayload DoS** — no rate limiting on this client-to-server packet. Any connected client (not only OPs) can spam it, triggering heavy server-side operations on every packet. Fix: 100ms per-player cooldown. See item 1.26.
3. **Cloud Vault URL in GUI** — already addressed by locking `cloudShareUrl` above.

---

## Phase 1 — Fix What's Broken

*These are bugs and oversights that make the mod look unfinished. Every one
of these was discovered during real testing. Fix them all before touching
anything else. Items 1.8–1.10 were diagnosed from server/client logs (join
failures, visual degradation). Items 1.11–1.18 were found via deep code
audit and confirmed by the server owner's live experience.*

### 1.1 Color detection fails on blocks without color in their name

**The problem:** The color square/triangle tools determine a block's color by
scanning its ID string for words like "black", "yellow", or "green". If a
block is visually black but named `obsidian_polished` instead of
`obsidian_black`, the tool silently does nothing — no error, no suggestion,
just... nothing happens. The player thinks the tool is broken.

Only 3 colors are recognized: black, yellow, green. That's it.

**The solution:** Two-layer detection — fast path (name matching) with an
intelligent fallback (pixel analysis):

```
Layer 1 — Name scan (instant, current behavior but expanded):
  Check block ID segments against 16 color families + 40 aliases.
  "obsidian_black" → black. "marble_crimson" → red. "stone_grey" → gray.

Layer 2 — Pixel analysis (fallback when name scan finds nothing):
  1. Load the block's texture bytes from SlotData
  2. Decode to BufferedImage
  3. Sample every 4th pixel, skip fully transparent ones
  4. Convert each sample to HSB color space
  5. Cluster samples by hue bucket (12 buckets of 30° each)
  6. The largest cluster = dominant color family
  7. Special handling for achromatic pixels (S < 0.15):
     - B < 0.2 → black family
     - B > 0.8 → white family
     - else → gray family
  8. Map the winning bucket to the nearest color family name
```

**Why HSB instead of RGB distance:** RGB Euclidean distance treats
`(200, 0, 0)` as "closer to black" than `(255, 50, 50)`, which is wrong
perceptually. HSB separates hue from brightness, so dark red is still
detected as red, not black. This fixes the #1 false classification.

**The experience:**
- Player uses color square on `obsidian_polished` → tool detects "black"
  via pixel analysis → creates the variant. It just works.
- If pixel analysis is ambiguous (two colors nearly tied), show:
  `"§eDetected: red (67%) or brown (33%). §7Using red. Use /cb recolor brown to override."`

**Edge cases:**
- Multi-colored blocks (no dominant color > 40%) → "§cCan't detect a
  single dominant color. §7Specify the color manually: /cb recolor <color> <id>"
- Blocks with no texture data → "§cThis block has no texture. §7Add one
  with /cb retexture <id> <url>"
- Transparent/empty textures (all pixels transparent) → treat as "no texture"

**Files:** `ColorSquareItem.java` (resolveTargetId), `ColorTriangleItem.java`,
new `ColorDetection.java` (shared pixel analysis logic), new `ColorNames.java`
(expanded color families + alias map)

---

### 1.3 Only 3 colors exist in the entire color system

**The problem:** `KNOWN_COLORS = {"black", "yellow", "green"}`. Three colors.
The mod has a Color Studio with 7 tints, a Palette Generator with 16 hues,
AI Smart Suggest with 18 presets — but the core color tools that players
actually use daily only understand three words.

**The solution:** Expand to 16 base color families with 40+ aliases:

**Base families (16):**
```
black, white, red, orange, yellow, green, lime, blue,
cyan, purple, magenta, pink, brown, gray, dark, light
```

**Alias map (40+):**
```
charcoal/coal/ebony/onyx/obsidian    → black
snow/ivory/pearl/cream               → white
crimson/scarlet/ruby/blood/cherry    → red
amber/gold/honey/saffron             → orange (not yellow — gold is warm)
lemon/butter/canary                  → yellow
forest/emerald/jade/olive/moss       → green
chartreuse/neon                      → lime
navy/sapphire/azure/sky/cobalt       → blue
teal/aqua/turquoise                  → cyan
violet/indigo/plum/grape             → purple
fuchsia/hot_pink                     → magenta
rose/salmon/blush/coral              → pink
beige/tan/chocolate/coffee/mocha     → brown
grey/silver/ash/slate/stone          → gray
```

**How it integrates:** A shared `ColorNames.java` utility used by:
- Color square/triangle ID resolution (primary use case)
- Command parsing (`/cb recolor red marble` — "red" resolves via this map)
- Color Library display names (Phase 3.1)
- Search filters (`/cb search color:red` — Phase 4.1)

**Alias resolution order:** exact family match → alias match → pixel
analysis fallback (1.1). This means `/cb recolor crimson marble` works
even without pixel analysis.

**Files:** New `ColorNames.java`, `ColorSquareItem.java`, `ColorTriangleItem.java`

---

### 1.8 Registered blocks without resource pack files cause "missing model" errors

**The problem:** At startup, the mod registers `maxSlots` blocks (e.g.
600) in the Minecraft block registry — ALL of them, whether they have
data or not. This is necessary because Minecraft requires blocks to be
registered at startup, before data is loaded.

The resource pack generators (both server-side and client-side) correctly
skip empty slots — they only write blockstate/model/texture files for
slots that actually have data (~590 of 600). But Minecraft still expects
EVERY registered block to have a model. The ~10 empty slots produce
"missing model" errors in the client log:

```
Exception loading blockstate definition: 'customblocks:blockstates/slot_592.json'
  missing model for variant: 'customblocks:slot_592#'
  ... (for every registered-but-empty slot)
```

Note: earlier client logs showed errors up to `slot_2047`, suggesting
maxSlots was previously set to 2048. With maxSlots=600, only ~10 errors
appear — but any error count > 0 is a problem that obscures real errors.

**The solution:** Generate minimal placeholder files for empty registered
slots so Minecraft doesn't complain:

```
For each registered slot that has NO data:
  - Blockstate: points to a shared "empty" model
  - Model: transparent 1×1 pixel cube (invisible in-game)
  - Texture: 1×1 transparent PNG (shared, ~67 bytes)

This means:
  Slot 0-589 (has data)  → real blockstate + model + texture
  Slot 590-599 (empty)   → placeholder blockstate → empty model → transparent tex
  Result: zero "missing model" errors
```

The placeholder model should be a single shared file
(`customblocks:block/empty_slot`) referenced by all empty slots. One
model file + one tiny texture covers any number of empty slots.

**The experience:** Player joins → zero errors in client log. Empty slots
are invisible in-game (transparent 1×1 model). Clean logs make real
errors easy to spot.

**Edge cases:**
- Slot filled after being empty → replace placeholder with real files
  on next resource pack build
- Slot emptied after having data → old files must be replaced with
  placeholder (the `cleanupStaleSlotFiles()` method already handles
  this — verify it writes the placeholder, not just deleting)
- `maxSlots` increased → new slots get placeholders automatically

**Why this matters:** Even a small number of "missing model" errors
clutters the log and makes debugging harder. More importantly, some
mod loaders and performance mods treat these errors as problems and
may behave unexpectedly. Zero errors = clean operation.

**Files:** `ServerPackGenerator.java` (add placeholder generation for
empty slots), `client/ResourcePackGenerator.java` (same on client side),
new shared `empty_slot` model + transparent 1×1 PNG

---

### 1.9 Non-power-of-2 textures disable mipmapping for ALL blocks

**The problem:** A player imported a 150×150 image into slot_8. Minecraft
requires textures in the `blocks` atlas to be power-of-2 dimensions
(16, 32, 64, 128, 256...). One non-conforming texture forces the ENTIRE
blocks atlas to drop mipmapping from level 4 to level 0:

```
Texture customblocks:block/slot_8 with size 150x150 limits mip level from 1 to 0
minecraft:textures/atlas/blocks.png: dropping miplevel from 4 to 0,
  because of minimum power of two: 1
```

**What mipmapping does:** It makes distant blocks look smooth instead of
shimmery/noisy. Losing it affects EVERY block in the game — vanilla
blocks, other mods' blocks, everything. One bad custom texture degrades
the visual quality of the entire world.

**The solution:** Enforce power-of-2 dimensions during image processing.
Every texture must be resized to the nearest power-of-2 BEFORE being
written to the resource pack.

```
Input: 150×150
  → nearest power-of-2 that fits: 128×128
  → resize using the quality algorithm from 4A.9

Resize logic:
  int target = nearestPowerOf2(Math.max(width, height));
  // nearestPowerOf2: round DOWN to avoid upscaling small images
  // 150 → 128, 200 → 128, 260 → 256, 500 → 512 (round UP if closer)
  // Minimum: 16. Maximum: configurable (default 256).

Power-of-2 table:
  16, 32, 64, 128, 256, 512
  Input → nearest in table (prefer rounding to configured texture size)
```

**Validation gate:** Add a check in `ServerPackGenerator` that rejects
or auto-fixes non-power-of-2 textures during resource pack assembly.
This catches any texture that slipped through — belt AND suspenders.

```java
// In ServerPackGenerator, before writing texture to ZIP:
if (!isPowerOfTwo(width) || !isPowerOfTwo(height)) {
    LOGGER.warn("Slot {} has non-power-of-2 texture ({}x{}), resizing to {}x{}",
        slot, width, height, target, target);
    image = resize(image, target, target);
}
```

**The experience:** Player imports a 150×150 image → mod silently resizes
to 128×128 → texture looks correct → mipmapping preserved for ALL blocks.
No visual degradation, no log warnings, no action needed from the player.

**Edge cases:**
- Existing blocks with bad dimensions → fix on next resource pack rebuild.
  Add a startup scan: log which slots have non-power-of-2 textures and
  auto-correct them. Message to console: "§e[CustomBlocks] Auto-fixed 3
  textures with non-power-of-2 dimensions."
- Player deliberately wants 150px → too bad, Minecraft requires power-of-2.
  The nearest size preserves visual quality. Document this in the import
  wizard (4A.6) tooltip: "§7Textures are resized to power-of-2 for Minecraft
  compatibility."
- Very small source (8×8) → minimum 16×16 (Minecraft's minimum useful size)
- Very large source (4096×4096) → cap at configured max (default 256)

**Why this matters:** One bad texture silently ruins the visual quality of
every block in the game for every player on the server. This is invisible
damage — players notice "the game looks worse" but can't pinpoint why.
The fix is simple, automatic, and has zero downside.

**Files:** `ImageProcessor.java` (resize step — enforce power-of-2),
`ServerPackGenerator.java` (validation gate during pack assembly),
`SlotManager.java` (startup scan for existing bad textures)

---

### 1.10 Server freezes 15+ seconds on startup, disconnecting joining players

> ✅ **IMPLEMENTED (V4.3)** — Async texture loading is fully implemented. `SlotManager.tickStartupLoad()` (lines 951–1038) runs texture loading off the main thread in batches; `SlotManager.loadTexturesAsync()` (lines 1047–1129) handles async texture fetching with post-startup re-sync for players who joined during loading. The 15.9-second freeze no longer occurs.

**The problem:** When the server starts, CustomBlocks loads ALL textures
synchronously on the main server thread. This blocks the tick loop for
nearly 16 seconds. **Confirmed from live server log (2026-05-15):**

```
[09:04:50] Done (4.375s)!   ← server claims it's ready
[09:04:59] User Authenticator: UUID of player 3liSY ...  ← player joins 9s later
[09:05:08] Loaded 584 textures and 5 face textures from files.
[09:05:08] Can't keep up! Running 15881ms or 317 ticks behind  ← 15.9s freeze
[09:05:08] 3liSY lost connection: Disconnected  ← player kicked by the freeze
```

During this freeze, any player attempting to join gets disconnected. The
server is literally frozen — it can't respond to keepalive packets, process
login sequences, or send resource pack data. Players see two error types:

```
Type 1: "Disconnected" (generic) — server never processed the handshake
Type 2: "Connection reset" (java.net.SocketException) — server accepted
        the connection but couldn't keep it alive during the freeze
```

The server owner confirmed: "Connection reset" kicks happen "mainly when
joining after a reset" and are "too frequent" during normal play. The
15.5-second freeze combined with 3 GB RAM and 137 mods creates massive
garbage collection pressure. JVM stop-the-world GC pauses during or after
the freeze can cause Netty to drop connections even after the initial
texture load completes.

This is the direct cause of both the "kicked on join" and "Connection
reset" bugs reported during testing.

**The solution:** Move texture loading off the main server thread using a
two-phase async approach:

```
Phase 1 — Metadata only (main thread, fast):
  Load slot index, block IDs, names, properties from JSON config.
  This is tiny — just text data, < 50ms for 600 blocks.
  Server is now tick-ready. Players can connect.

Phase 2 — Textures (background thread, async):
  Load texture bytes from disk on a separate thread.
  Feed them into the resource pack generator as they load.
  No tick loop blocking. No keepalive timeout.
```

**Progress tracking during async load:**
- Console: `[CustomBlocks] Loading textures... 150/584 (26%)`
- Updates every 100 textures (not every single one — avoid log spam)
- On completion: `[CustomBlocks] All 584 textures loaded in 4.2s (async)`

**Player joins during texture loading:**
- If a player joins BEFORE textures finish loading, they need to wait for
  the resource pack. Two approaches (choose one):
  - **Option A (recommended):** Send a "loading" resource pack with
    placeholder textures, then send the real pack when ready. Player sees
    temporary pink/black checkerboard blocks, then they pop in correctly.
  - **Option B:** Hold the resource pack send until textures are loaded.
    Player sees vanilla blocks briefly, then receives the full pack.
    Simpler but potentially confusing ("where are my blocks?").
- In either case: the player CONNECTS successfully. No disconnect.

**The experience:** Server starts → players can join immediately → blocks
load in the background → resource pack sent when ready. No freeze, no
disconnect, no "Can't keep up!" warning.

**Edge cases:**
- Error loading a texture file (corrupted/missing) → log warning, skip
  that slot, continue loading others. Don't let one bad file block the
  entire async load.
- Server shutdown during async load → cancel the background task cleanly.
  Don't leave orphan threads.
- `/cb reload` during async load → queue the reload to run after the
  initial load completes. Don't start two concurrent load operations.
- Very large texture count (1000+) → the async approach handles this
  naturally. Progress logging keeps the admin informed.

**Relationship to Phase 7:** This item fixes the CRITICAL bug (server
freeze → player disconnect). Phase 7.2 (Lazy texture loading) goes
further by keeping textures out of RAM entirely with an LRU cache. They
complement each other: 1.10 moves loading async so the server doesn't
freeze; 7.2 reduces what's loaded at all. Implement 1.10 first as an
urgent fix, then 7.2 as a performance optimization.

**Why this matters:** Players literally cannot join the server after a
restart until the freeze ends. On a server with 500+ blocks, that's 15+
seconds of complete unresponsiveness. Players see "Disconnected" and think
the server is broken. This is the #1 most impactful bug for multiplayer
servers.

**Files:** `SlotManager.java` (split load into metadata + async texture
phases), `ServerPackGenerator.java` (handle partial-load state during pack
assembly), `ResourcePackServer.java` (queue pack send until textures ready)

---

### 1.11 Placing an animated GIF block kicks the player (timeout)

> ⚠️ **NOT YET IMPLEMENTED** — Forensic analysis confirmed zero implementation exists in the current codebase.

**The problem:** A player places a custom block that has a GIF animation
(even a SHORT one — tested with a 3-frame "Talking Ben saying yes" GIF).
A few moments later: "Timed out." Kicked from the server. The server log
shows zero errors — just silence, then "lost connection: Timed out."

This happens because placing an animated block triggers a cascade of
heavy operations that choke the server:

```
Player places animated block
  → SlotManager.saveAll() marks data dirty
  → Debounced save fires → saveAllAsync()
  → ResourcePackServer.updatePackWithSnapshot() queued
  → ServerPackGenerator rebuilds ENTIRE ZIP
    → For EACH animated slot: encode PNG strip + mcmeta JSON
    → A 30-frame GIF at 128×128 = 128×3840 pixel strip (~1-2 MB PNG)
    → Compress all 590+ slots into ZIP
    → Write to disk
  → Meanwhile: network sync tries to push the large texture payload
    → Animated texture: 1-3 MB (vs ~20 KB for a normal block)
    → Exceeds 900 KB packet limit → chunking required
    → Drip-feed throttled at 512 KB/tick
    → Large payload triggers cooldown ticks
  → Server can't process keepalive packets during this storm
  → Player times out
```

No crash, no error, no log entry. Just silence and a kick.

**The solution:** Three-part fix:

**Part A — Don't rebuild the full ZIP on block placement:**
Block placement should NOT trigger a resource pack rebuild. The animated
block's texture is already IN the pack (it was added when the block was
created). Placement only changes the world state, not the resource pack.
Audit the save path: ensure `saveAll()` after placement doesn't trigger
`updatePackWithSnapshot()` unless texture data actually changed.

**Part B — Throttle animated texture sync:**
When syncing an animated block's texture to clients, don't send the full
frame strip if the client already has it. The delta sync system (N3)
already checks hashes — verify it works for animated blocks. If the
client's hash matches, skip the texture payload entirely.

**Part C — Background ZIP generation must not block tick loop:**
The ZIP builder already runs on a dedicated thread (`CustomBlocks-
PackBuilder`). Verify it doesn't hold any lock that blocks the server
thread. Add a guard: if ZIP generation takes > 5 seconds, log a warning:
`"[CustomBlocks] WARNING: Resource pack rebuild took Xs — consider
reducing block count or enabling incremental builds."`

**The experience:** Player places a GIF block → block appears → animation
plays → no kick, no lag, no drama. Just works.

**Edge cases:**
- Player places 10 animated blocks rapidly → don't trigger 10 separate
  ZIP rebuilds. The debounce system should coalesce these into one rebuild.
  Verify the debounce timer is sufficient (current: configurable, likely
  ~2000ms). If 10 placements happen within 2s, only 1 rebuild fires.
- Player with slow connection receives animated texture → drip-feed should
  pace delivery. If it takes > 30s to deliver, log warning but don't kick.
  Increase keepalive tolerance during texture sync if possible.
- Server has 50+ animated blocks → ZIP rebuild is inherently slow. This is
  where 7.1 (incremental RP) becomes critical. Cross-reference: 7.1 should
  prioritize animated blocks as the heaviest entries.

**Why this matters:** Animated blocks are one of the mod's coolest
features. If placing one kicks you, nobody will use them. This was
reported by the server owner placing a simple 3-frame GIF — it shouldn't
happen even with a 100-frame GIF.

**Files:** `SlotManager.java` (saveAll trigger path), `ResourcePackServer.java`
(updatePackWithSnapshot), `ServerPackGenerator.java` (ZIP generation),
`NetworkManager.java` (animated texture sync throttling)

---

### 1.12 GIF larger than ~1 MB crashes both server and client

> 🔶 **PARTIALLY IMPLEMENTED** — The 20MB download cap exists but is checked AFTER full download (OOM window remains). Frame cap (MAX_FRAMES=100) exists. However the 4-layer size protection described here is not fully implemented — the Layer 1 strip-size pre-check and the configurable MAX_STRIP_BYTES guard are absent.

**The problem:** When a player imports a GIF that's larger than
approximately 1 MB, both the server AND the client crash. Not a timeout
— a full crash requiring restart. This was reported directly by the
server owner and is reproducible.

The crash chain:

```
Player runs: /cb create talkingben "Talking Ben" <large-gif-url>
  → ImageProcessor downloads GIF (~1-3 MB file)
  → Decodes frames: up to 100 frames × 128×128 = massive BufferedImage[]
  → Composites frames into vertical strip: 128×12800 pixels
  → Encodes strip as PNG: 2-5 MB
  → Stores in SlotData.texture (byte array held in RAM)
  → Server tries to sync this to all online players
  → NetworkManager chunks it into 900 KB packets
  → Multiple simultaneous chunk transmissions flood the pipeline
  → Server: OutOfMemoryError OR network buffer overflow
  → Client: packet too large OR OOM during texture reassembly
  → Both crash
```

**Why the size matters:** A normal block texture is ~10-50 KB. An animated
GIF decoded into a vertical frame strip can be 100-500x larger. The
entire network pipeline was designed for small textures.

**The solution:** Multi-layer size protection:

**Layer 1 — Cap decoded animation size BEFORE processing:**
```java
// In ImageProcessor, after decoding GIF frames:
long estimatedStripBytes = frameWidth * frameHeight * frameCount * 4; // ARGB
long MAX_STRIP_BYTES = 4_000_000; // 4 MB uncompressed (configurable)

if (estimatedStripBytes > MAX_STRIP_BYTES) {
    // Option A: Reduce frame count
    int maxFrames = (int)(MAX_STRIP_BYTES / (frameWidth * frameHeight * 4));
    frames = frames.subList(0, maxFrames);
    warn("§eGIF too large — trimmed to " + maxFrames + " frames.");

    // Option B: Reduce frame resolution
    // Downscale each frame to 64×64 instead of 128×128
}
```

**Layer 2 — Cap final PNG size after encoding:**
```java
byte[] pngBytes = encodePng(strip);
long MAX_PNG_BYTES = 2_000_000; // 2 MB compressed

if (pngBytes.length > MAX_PNG_BYTES) {
    // Re-encode at lower quality or smaller dimensions
    // Or reduce frame count until it fits
    // Tell the player: "§eGIF compressed to fit. Reduced to X frames at Ypx."
}
```

**Layer 3 — Network safety valve:**
In `NetworkManager`, before queueing a texture payload:
```java
if (payload.length > MAX_SINGLE_TEXTURE_BYTES) {
    LOGGER.warn("Texture for slot {} is {} MB — skipping network sync, "
        + "clients will get it via resource pack download", slot, mb);
    // Don't send via drip-feed. Let the RP HTTP download handle it.
    return;
}
```

**Layer 4 — Graceful OOM handling:**
Wrap the entire GIF processing pipeline in a try-catch for
`OutOfMemoryError`. If OOM is caught:
- Free the frame buffer immediately
- Send error to player: "§cGIF too large for server memory. §7Try a
  shorter GIF (under 30 frames) or a smaller resolution."
- Don't crash. Don't create a half-finished block.

**The experience:** Player imports a huge GIF → mod trims it to a safe
size → shows message: "§eGIF had 80 frames — trimmed to 25 to fit
safely. §7Use /cb anim to adjust." → Block works, server doesn't crash.

**Edge cases:**
- GIF with enormous resolution (1920×1080 frames) → resize each frame
  to configured max (default 128×128) BEFORE strip assembly
- GIF with 1 frame → not animated, treat as static image (skip strip)
- Server with 2 GB RAM vs 8 GB → the MAX_STRIP_BYTES should be
  configurable. Default conservative (4 MB) for low-RAM servers.
- Multiple players importing GIFs simultaneously → each GIF decode
  runs on the 2-thread executor. If both are large, OOM risk doubles.
  Add a "one GIF import at a time" queue with: "§eAnother GIF is being
  processed. §7Your import will start in a moment."

**Why this matters:** This is a server-crashing bug. One player importing
one GIF can take down the entire server for everyone. On a 3 GB RAM
server with 137 mods, there's almost no headroom for large image
processing. The mod MUST protect itself and the server from this.

**Files:** `ImageProcessor.java` (GIF decode pipeline, frame strip
assembly), `NetworkManager.java` (payload size guard), `SlotManager.java`
(texture storage), `CustomBlockCommand.java` (create/retexture commands)

---

### 1.13 + 1.14 Resource pack completely broken on MCServerHost — friends NEVER see textures

> ✅ **IMPLEMENTED** — `cloudPackSecret` field exists in `CustomBlocksConfig.java`, fully wired in load/save/missingManagedKeys. `ResourcePackServer.java` includes pack upload logic with timing-safe secret validation (`pendingBuilds` coalescing, `PENDING_PACK_PUSH` deferred delivery). The Cloud Vault Worker has a POST /pack endpoint with `crypto.subtle.timingSafeEqual` secret checking. External players receive textures via Cloudflare CDN.

> **These two items are merged because the confirmed root cause invalidates
> the previous diagnosis and solution for both.**

**The problem (CONFIRMED 2026-05-15):**

Tested by opening `http://yoyo.mcsh.io:24454/pack.zip` while the server
was running with players connected. Result: `ERR_CONNECTION_REFUSED`.

Port 24454 is **fully blocked by MCServerHost's firewall** from outside.
All external HTTP ports (8080, 8081, 24454, 8082, 3000) are blocked.
The resource pack HTTP server runs fine internally — but **no player
outside the server has ever been able to download the pack from it.**

```
What actually happens every time a friend joins:
  1. Server sends: "Download your resource pack from http://IP:24454/pack.zip"
  2. Friend's Minecraft tries to connect to port 24454
  3. MCServerHost firewall: CONNECTION REFUSED
  4. Friend's Minecraft gives up on the pack
  5. All custom blocks appear as purple/black checkerboard (missing texture)
  6. Friend sees invisible/broken blocks every single time

This is not intermittent. This is 100% of the time, for all friends.
The IP detection bug (Docker UUID hostname) was a secondary issue.
The real problem is: the entire HTTP server approach is wrong for
MCServerHost — and any other shared hosting provider that locks ports.
```

**The solution — use Cloud Vault instead of a local HTTP server:**

The mod already has a Cloudflare Worker at
`https://cb-cloud-vault.cbbblocksvault.workers.dev`. Cloudflare Workers
are globally accessible over HTTPS — no ports, no firewall, no hosting
restrictions. This is already deployed and working.

The fix: after every resource pack rebuild, upload the ZIP to Cloud Vault
and use the Cloud Vault URL as the Minecraft resource pack URL instead of
the local HTTP server.

```
Current flow (BROKEN on shared hosting):
  Pack rebuilds → stored at customblocks_data/customblocks_pack.zip
  Player joins  → server sends "download from http://IP:24454/pack.zip"
  Result        → CONNECTION REFUSED → broken blocks

New flow (works everywhere):
  Pack rebuilds → stored locally AND uploaded to Cloud Vault via HTTPS
  Player joins  → server sends "download from https://cloud-vault-url/pack.zip"
  Result        → HTTPS download succeeds → blocks visible ✓
```

**Implementation — two parts: worker update + mod update:**

**Part A — Add `/pack` endpoint to the Cloud Vault worker:**

The current worker (index.js) only has `/share` and `/market` endpoints
for sharing individual block JSON. It needs a new `/pack` endpoint for
binary ZIP data. Cloudflare KV supports values up to 25 MB; current
packs are 2–5 MB so this fits within the free tier.

```javascript
// New endpoint in index.js:

// POST /pack — upload resource pack ZIP (binary, base64-encoded)
if (request.method === "POST" && url.pathname === "/pack") {
    // Only accept from authenticated server (shared secret header)
    const auth = request.headers.get("x-pack-secret");
    if (!auth || auth !== env.PACK_SECRET) {
        return json({ error: "Unauthorized" }, 401);
    }
    const body = await request.arrayBuffer();
    if (body.byteLength > 20 * 1024 * 1024) {  // 20 MB limit
        return json({ error: "Pack too large" }, 413);
    }
    const hash = request.headers.get("x-pack-hash") || "latest";
    // Store raw bytes with 24-hour TTL
    await env.BLOCKS.put("pack:latest", body, {
        expirationTtl: 86400,
        metadata: { hash, updatedAt: new Date().toISOString() }
    });
    return json({ ok: true, url: `https://${url.hostname}/pack.zip` }, 201);
}

// GET /pack.zip — serve resource pack to Minecraft clients
if (request.method === "GET" && url.pathname === "/pack.zip") {
    const data = await env.BLOCKS.getWithMetadata("pack:latest", { type: "arrayBuffer" });
    if (!data || !data.value) return new Response("No pack uploaded", { status: 404 });
    return new Response(data.value, {
        status: 200,
        headers: {
            "content-type": "application/zip",
            "content-disposition": "attachment; filename=\"pack.zip\"",
            "x-pack-hash": data.metadata?.hash || "",
            ...CORS_HEADERS,
        },
    });
}
```

**Why a shared secret?** Without auth, anyone on the internet could
upload a malicious ZIP as your resource pack. The secret is stored in
Cloudflare Worker environment variables (never in the mod's config).

**Part B — Upload ZIP from the mod after every pack rebuild:**

```java
// In ResourcePackServer.java, after pack ZIP is written to disk:
if (CustomBlocksConfig.cloudShareEnabled && packFile.exists()) {
    String secret = CustomBlocksConfig.cloudPackSecret; // new config field
    if (secret != null && !secret.isBlank()) {
        PACK_UPLOADER.submit(() -> uploadPackToCloudVault(packFile, currentHash, secret));
    }
}

private static void uploadPackToCloudVault(File packFile, String hash, String secret) {
    try {
        byte[] packBytes = Files.readAllBytes(packFile.toPath());
        String url = CustomBlocksConfig.normalizedCloudShareUrl() + "/pack";
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(url))
            .header("Content-Type", "application/zip")
            .header("x-pack-secret", secret)
            .header("x-pack-hash", hash)
            .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(packBytes))
            .timeout(java.time.Duration.ofSeconds(30))
            .build();
        var response = HTTP_CLIENT.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 201) {
            cloudPackUrl = CustomBlocksConfig.normalizedCloudShareUrl() + "/pack.zip";
            LOGGER.info("[CustomBlocks] Resource pack uploaded to Cloud Vault ✓");
        } else {
            LOGGER.warn("[CustomBlocks] Pack upload failed: HTTP {}", response.statusCode());
        }
    } catch (Exception e) {
        LOGGER.warn("[CustomBlocks] Pack upload failed: {}", e.getMessage());
    }
}
```

**Part C — Use Cloud Vault URL for resource pack sends:**
```java
public static String getPackUrl() {
    // Priority 1: Cloud Vault (works on all hosting, including MCServerHost)
    if (cloudPackUrl != null && CustomBlocksConfig.cloudShareEnabled) {
        return cloudPackUrl + "?v=" + currentHash.substring(0, 8); // cache bust
    }
    // Priority 2: Local HTTP server (LAN/home servers with open ports)
    if (activePort > 0 && cachedExternalIp != null) {
        return "http://" + cachedExternalIp + ":" + activePort + "/pack.zip";
    }
    return null; // no delivery method available
}
```

**Config changes:**
- **Keep `resourcePackPort`** — do NOT remove it. Home servers with open ports rely on this for local HTTP fallback delivery. It continues to control the local pack server.
- Add `cloudPackSecret` — this field does not yet exist and must be added to `CustomBlocksConfig.java`. It is the shared secret the mod sends in the `x-pack-secret` header when uploading the resource pack ZIP to Cloudflare. It must match the `PACK_SECRET` environment variable set in the Cloudflare dashboard. **This field must never appear in the in-game Config GUI** — admins set it by editing `config.json` directly. Without this secret, anyone on the internet could POST a malicious ZIP to the worker and replace everyone's textures.
- **Lock `cloudShareUrl` as a hardcoded constant** — remove it from `config.json` and from the Config GUI (slot 33 in `buildConfigGui()` / case 33 in `handleConfigGuiClick()`). Replace all references with a single Java constant: `private static final String CLOUD_VAULT_URL = "https://cb-cloud-vault.cbbblocksvault.workers.dev";`. Admins cannot change it — this prevents any OP from redirecting uploads to a server they control.
- Keep `cloudShareEnabled` — controls both block sharing AND pack upload

**Admin setup (one-time):**
1. Set `PACK_SECRET` in Cloudflare Worker environment variables
2. Set `cloudPackSecret` in mod config to the same value
3. Done — packs upload automatically after every texture change

**Edge cases:**
- Cloud Vault upload fails (network down, quota exceeded) → fall back to
  local HTTP silently. Log the failure. Retry on next pack rebuild.
- Cloud Vault returns stale pack (CDN cache) → add cache-busting hash to
  the URL: `https://.../pack.zip?v=abc123`
- Home server with open ports → local HTTP works fine. Cloud Vault still
  uploads for redundancy. Admin can disable Cloud Vault if desired.
- Pack is 50MB+ → Cloudflare Workers have a 100MB upload limit per request.
  Compress the pack aggressively. Current packs are ~2-5 MB based on test
  server data.
- First join before pack is uploaded → show loading message, retry pack
  send once upload completes.

**Why this matters:** Custom blocks have been invisible for ALL friends
on this server since the beginning. This is the #1 most impactful bug
for anyone playing with friends. Fixing it makes the mod work as
intended for the first time. Friends will finally see the blocks.

**Files:**
- `cloud-vault-worker/src/index.js` — add `POST /pack` (upload) and
  `GET /pack.zip` (serve) endpoints with shared-secret auth
- `ResourcePackServer.java` — upload to Cloud Vault after every rebuild,
  use Cloud Vault URL as primary, local HTTP as fallback
- `CustomBlocksConfig.java` — keep `resourcePackPort`; add `cloudPackSecret` (config file only, never in GUI); remove `cloudShareUrl` field (replaced with hardcoded constant)
- `GuiManager.java` — remove slot 33 (Cloud Vault URL prompt) from `buildConfigGui()` and case 33 from `handleConfigGuiClick()`
- Worker environment: set `PACK_SECRET` variable in Cloudflare dashboard

---

### 1.15 Non-atomic file writes risk data corruption on crash

> 🔶 **PARTIALLY IMPLEMENTED** — Main slot data save (slots.json.gz) and config save use atomic writes correctly. However face texture writes (slot_N.dat, slot_N_FACE.dat, slot_N_varN.dat) are all confirmed NON-ATOMIC direct Files.write() calls. Cloud share cache writes are also non-atomic. The atomic pattern has NOT been applied to all write operations as required by this item.

**The problem:** Several file write operations in the mod are not
atomic. An atomic write means "either the entire file is written
correctly, or the old file is preserved." A non-atomic write means a
crash mid-save produces a corrupted or empty file.

This was found during a code audit — it hasn't been confirmed as the
cause of any specific user-reported bug, but it's a ticking time bomb
on a 3 GB server that auto-sleeps and can crash under memory pressure.

```
Vulnerable write paths found in the code:

1. Face texture writes (SlotManager.java:1100):
   Files.write(dir.resolve("slot_" + slotIndex + ".dat"), data);
   // Direct write — no temp file, no atomic move

2. Cloud share cache (CustomBlockCommand.java:1092-1093):
   Files.writeString(exportDir.resolve(hash + ".json"), json);
   // Direct write — no temp file, no atomic move

4. Config save paths that use Files.write() without temp-file pattern
```

The main slot data save (SlotManager.java:780) DOES use atomic move
correctly — but only for the primary save. If the backup copy fails
(line ~495, exception swallowed silently), and THEN the primary save
gets corrupted, there's nothing to fall back to.

**The solution:**

**Rule 1 — Every file write uses the atomic pattern:**
```java
// The CORRECT pattern (already used in some places):
Path tempFile = file.resolveSibling(file.getFileName() + ".tmp");
Files.write(tempFile, data);
Files.move(tempFile, file,
    StandardCopyOption.ATOMIC_MOVE,
    StandardCopyOption.REPLACE_EXISTING);

// Apply this to ALL write operations. No exceptions.
```

**Rule 2 — Backup file must succeed before overwriting primary:**
```java
// BEFORE saving primary:
Path backup = file.resolveSibling(file.getFileName() + ".bak");
Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
// If backup fails → REFUSE to save primary. Log error + tell admin.
// Better to have stale data than NO data.
```

**Rule 3 — Startup recovery from backups:**
On startup, if the primary file is missing, corrupted, or empty:
```
1. Check for .bak file → restore from backup
2. Check for .bak1.json (already exists in mod — `SlotManager.java` line 483) → restore from that
3. Check snapshots → restore from most recent
4. If nothing found → log CRITICAL error, tell admin
```

The mod already creates `.bak1.json` files (**NOT** `.bak1.json.gz` — no compression; confirmed in `SlotManager.java` line 493: `"[CustomBlocks] Backup saved to slots.bak1.json"`). The recovery logic should automatically detect when the primary is damaged and fall back.

**Rule 4 — Improve backup error visibility:**
The current code at `SlotManager.java:~495` does log a warning when backup
fails (`LOGGER.warn("[CustomBlocks] Could not create backup: {}")`), but the
message is easy to miss and not flagged to admins in-game. Upgrade it to also
send an in-game warning: `"[CustomBlocks] ⚠ Backup copy FAILED — data safety reduced!"`

**The experience:** Server crashes → restarts → all blocks are exactly
as they were at last save. Admin sees "§a[CustomBlocks] Loaded 584
blocks from primary save." or "§e[CustomBlocks] Primary save damaged —
restored 584 blocks from backup (last saved 2 min ago)."

**Edge cases:**
- Crash during the atomic move itself → on most filesystems,
  `ATOMIC_MOVE` is truly atomic. On NFS or some Docker overlay
  filesystems, it may not be. Mitigation: keep TWO backup generations.
- Snapshot restore overwrites current blocks → snapshot system should
  ALSO be protected by the same atomic write pattern.
- Disk full → atomic move fails, temp file left behind. Startup should
  clean up stale `.tmp` files and warn if disk is nearly full.

**Why this matters:** On a 3 GB auto-sleep server with 137 mods, crashes
happen. Every crash is a coin flip on whether an in-progress file write
gets corrupted. Making all writes atomic eliminates this risk entirely.

**Files:** `SlotManager.java` (all write paths — primary save, face
textures, backup copies), `CustomBlockCommand.java` (cloud cache writes),
`CustomBlocksConfig.java` (config save)

---

### 1.16 Placed blocks vanish from the world when maxSlots is lowered

> ⚠️ **NOT YET IMPLEMENTED** — Forensic analysis confirmed zero implementation exists in the current codebase.

**The problem:** The server owner confirmed: after a server restart,
blocks that were PLACED in the world disappeared. The block definitions
(textures, names) were still in the mod — nothing was deleted from the
config. But the physical blocks in the Minecraft world were gone. They
had to walk around and place everything again.

**Root cause (CONFIRMED from code analysis + user answers):**

The server owner changed maxSlots. At one point it was set to 2048, then
changed back to 600. Here's what happens inside Minecraft:

```
Step 1 — Server starts with maxSlots=2048:
  CustomBlocksMod.java:85 registers slot_0 through slot_2047.
  That's 2048 Block objects + 2048 Item objects in Minecraft's registry.
  Player places blocks using slot_650, slot_900, slot_1200, etc.
  World saves these as "customblocks:slot_650" in the chunk data.

Step 2 — Admin changes maxSlots to 600 (via GUI or config):
  GuiManager.java:1330 updates the config value immediately.
  SlotManager.rebuildFreeSlotSet() now only tracks indices 0-599.
  Blocks at indices 600+ still exist in memory but are ORPHANED.
  Config file saves maxSlots=600.

Step 3 — Server restarts:
  CustomBlocksMod.java:85 registers slot_0 through slot_599 ONLY.
  Slots 600-2047 are NEVER registered.
  Minecraft loads chunk data containing "customblocks:slot_650".
  Registry lookup fails → Minecraft replaces it with AIR.
  Block is gone. SILENTLY. No log. No warning. No recovery.
```

**This is almost certainly what happened.** The user confirmed they
changed maxSlots and had "so many issues and problems" with it. The
config currently reads 600 but was previously 2048.

**Additional damage from high maxSlots:**
- 2048 Block + Item registrations on a 3 GB server = enormous memory
  pressure, contributing to Connection reset kicks (see 1.10)
- 2048 blockstates need model data → thousands of "missing model"
  warnings in client logs for empty slots
- `rebuildFreeSlotSet()` iterates up to maxSlots() — at 2048 that's
  2048 iterations instead of 600

**The solution:**

**Guard 1 — Track previous maxSlots and REFUSE to decrease below the
highest used slot index:**
```java
// On config load:
int highestUsedSlot = bySlot.keySet().stream()
    .map(s -> Integer.parseInt(s.replace("slot_", "")))
    .max(Integer::compare).orElse(0);

if (newMaxSlots < highestUsedSlot + 1) {
    LOGGER.warn("[CustomBlocks] ⚠ Cannot reduce maxSlots to {} — "
        + "you have blocks using slot indices up to {}. "
        + "Keeping maxSlots at {}.",
        newMaxSlots, highestUsedSlot, highestUsedSlot + 1);
    maxSlots = highestUsedSlot + 1;
}
```

**Guard 2 — When maxSlots IS lowered, warn about orphaned blocks:**
```
If the admin insists on lowering (e.g., via force flag):
  1. Count blocks with index >= new maxSlots
  2. Log: "[CustomBlocks] ⚠ 47 blocks have indices above the new
     maxSlots (600). These blocks will become INVISIBLE in the world
     after restart. Raise maxSlots back to at least 1801 to keep them."
  3. Show the same warning in chat to the admin
```

> Note: The warning previously referenced `/cb compact` — that command has been removed from
> the plan (see below). The corrected warning tells the admin to raise maxSlots instead.

**Guard 3 — `/cb compact` is NOT implemented (removed from plan):**

`/cb compact` would move all block definitions to lower slot indices and rewrite
every chunk file in the world that contains a placed custom block. On a large world
this means thousands of chunk files. If the server crashes mid-operation, the world
is left in a half-migrated state with orphaned and duplicate block references —
a worse outcome than the problem it was trying to solve.

Guards 1 and 2 already PREVENT the blocks-vanishing problem from happening again.
Guard 4 (startup check) catches any leftover orphaned blocks and tells the admin
exactly how to recover (raise maxSlots). The problem is fully solved without compact.

If slot index reorganization is ever needed in the future, it must be implemented
as a standalone offline migration tool that: (a) requires a full world backup first,
(b) runs while the server is completely stopped, (c) verifies integrity after each
chunk file is processed, and (d) has a rollback mechanism. That is out of scope for
this plan.

**Guard 4 — Startup registry integrity check:**
```
After block registration, log:
  "[CustomBlocks] Registered 600 block slots (slot_0 to slot_599)"
  
Then check loaded block data:
  If any block data has index >= maxSlots:
  "[CustomBlocks] ⚠ WARNING: 12 block definitions have indices above
   maxSlots (600). These blocks exist in config but cannot be placed.
   Raise maxSlots to at least 1801 to restore them, then restart."
```

**The experience:** The admin NEVER silently loses blocks. If they try
to lower maxSlots, Guard 1 refuses and explains why. If they force it,
Guard 2 warns them with a recovery path. Guard 4 catches any previously
orphaned blocks on startup and tells the admin exactly what to do.

**Edge cases:**
- maxSlots increased → always safe, no blocks affected, no warning needed.
- Mod removed entirely → ALL customblocks:slot_* blocks become unknown.
  This is standard Minecraft behavior, not a CustomBlocks bug.
- Admin with blocks at slot indices 600+ who lowered maxSlots already
  (your situation) → raise maxSlots back to at least the highest index +1
  and restart. Guard 4 will confirm all blocks are recoverable.

**Why this matters:** The server owner lost hours of work because they
changed a config number with no warning about the consequences. A
creative tool that silently destroys player work when you change a
setting is fundamentally broken. These three guards make maxSlots changes SAFE.

**Files:** `CustomBlocksMod.java` (block registration, maxSlots guard),
`SlotManager.java` (highest-used-slot check, startup audit),
`CustomBlocksConfig.java` (maxSlots change detection + warning),
`GuiManager.java` (maxSlots GUI with warning text)

---

### 1.17 Resource pack built with ZERO textures during startup — clients get empty pack

> ⚠️ **NOT YET IMPLEMENTED** — Forensic analysis confirmed zero implementation exists in the current codebase. The `startupLoadComplete` flag does not exist in SlotManager.java.

**The problem:** The resource pack ZIP is generated **twice** during
server startup, and the **first build happens before a single texture
is loaded**. The original live server log (2026-05-15) showed two premature builds from an older codebase version; current code produces one premature build, but the core problem is unchanged:

```
[09:04:34] [CustomBlocks-PackBuilder] Cached internal resource pack ZIP ← 0 textures
[09:04:35] Initialized. 0 slot(s) loaded, maxSlots=600           ← still 0 loaded!
... (textures load for 33 seconds) ...
[09:05:08] Loaded 584 textures and 5 face textures.
[09:05:10] [CustomBlocks-PackBuilder] Cached internal resource pack ZIP ← correct
```

Any client who joins between server start and ~09:05:10 (a ~36-second
window) downloads a pack.zip that contains NO custom block textures.
Every custom block they see renders as purple/black checkerboard. The
pack hash sent to that client is "empty pack hash", not "full pack hash",
so Minecraft doesn't know to re-send the pack until the client reconnects.

This directly explains the purple/black checkerboard bug confirmed by the
server owner. The second join (after the correct pack is built at 09:05:10)
works fine because the hash has changed.

Additionally, the wasted early pack build adds unnecessary load during the already
critical startup window.

**The solution:** Suppress pack building until startup texture loading is
complete. A simple flag (`startupLoadComplete`) set by `SlotManager` when
the async startup load finishes. The pack builder checks this flag:

```
// In ServerPackGenerator:
if (!SlotManager.isStartupLoadComplete()) {
    // Don't build yet — startup load still running
    pendingBuildRequest = true;
    return;
}
```

When the flag flips, if `pendingBuildRequest == true`, trigger one pack
build. This guarantees:
1. Exactly one pack build during startup (after textures are loaded)
2. No wasted pack builds with 0 textures
3. The correct hash is broadcast to clients on join

**The experience:** Admin joins right after "Done" is logged → still gets
kicked by the 15.9s freeze (bug 1.10) → after 1.10 is fixed, joins
immediately → receives the real full-texture pack on first connection.
The checkerboard phase during startup is eliminated entirely.

**Edge cases:**
- Server starts with 0 custom blocks → no pack to build, flag logic still
  applies, one empty pack built after load (correct behavior, fast)
- Pack rebuild triggered by `/cb rebuild` before startup load completes →
  queue the rebuild, execute it after the flag flips
- Server crashes during startup before flag flips → on next start, the
  previous correct pack ZIP is still on disk from the last run. Serve that
  until the new build is ready.

**Files:** `SlotManager.java` (expose `isStartupLoadComplete()` flag),
`ServerPackGenerator.java` (check flag before building, hold
`pendingBuildRequest`), `ResourcePackServer.java` (no changes needed —
it serves whatever is on disk)

---

### 1.18 Unknown `/cb` subcommand shows cryptic raw argument hint instead of help

> ⚠️ **NOT YET IMPLEMENTED** — Forensic analysis confirmed zero implementation exists in the current codebase. The raw `[<__cb_unknown_tail>]` label is still displayed.

**The problem:** When a player types an invalid `/cb` subcommand — like
`/cb admin diag` (which doesn't exist) — Minecraft displays:

```
[<__cb_unknown_tail>]
```

This is the raw internal argument parser debug label. It tells the player
nothing. They don't know what commands exist, what they typed wrong, or
what to try instead. The mod has a `/cb diagnostics` command (not
`/cb admin diag`), but there is no fallback that explains this.

This was confirmed directly when the server owner typed `/cb admin diag`
and received `[<__cb_unknown_tail>]` with zero explanation.

**The solution:** Add a catch-all argument handler on the root `/cb`
command that intercepts unknown tails and prints a friendly error:

```java
// In CustomBlockCommand registerCommands():
.then(CommandManager.argument("unknown", StringArgumentType.greedyString())
    .executes(ctx -> {
        String typed = StringArgumentType.getString(ctx, "unknown");
        ChatHelper.error(ctx.getSource(),
            "§cUnknown command: §f/cb " + typed + "\n" +
            "§7Type §f/cb help§7 to see all available commands.");
        return 0;
    }))
```

Also rename the internal argument label from `__cb_unknown_tail` to
something that won't leak raw identifiers to players even in vanilla
tab-complete.

**The experience:**
- Player types `/cb admin diag` → sees:
  `✗ Unknown command: /cb admin diag. Type /cb help to see all available commands.`
- Player types `/cb blah blah blah` → same clear message
- Player uses tab-complete → sees only real subcommands (no change to autocomplete)

**Edge cases:**
- Console uses unknown command → same message works in console context
- Player doesn't have permission for the command they're trying → existing
  `requires()` guards still fire first, unknown-tail handler never reached
- `/cb help` itself → this item doesn't define help, but the error message
  should direct to wherever help is implemented

**Files:** `CustomBlockCommand.java` (add catch-all argument at end of
root command builder, rename internal argument label)

---

### 1.19 Creative tab icon has never shown — 3-layer architectural oversight

> ⚠️ **NOT YET IMPLEMENTED** — Forensic analysis confirmed zero implementation exists in the current codebase. TAB_ICON_ITEM is not registered, no model JSON is generated, and the icon lambda does not use the tab icon texture.

**The problem:** The custom creative tab icon has been broken since the mod was
first created (February 2025 — 3+ months). The tab always shows a fallback icon
(the first slot block) instead of the server owner's chosen image. Three separate
missing pieces compound into a complete failure.

**Five-check forensic trace:**

1. **Symptom:** Creative tab shows the first registered block's icon, never the
   custom tab icon texture the owner uploaded.

2. **Code path — Layer 1 (item never registered):**
   `CustomBlocksMod.java:322–336` — the icon lambda calls
   `SlotManager.getById("tab_icon")`. But `tab_icon` is stored as raw bytes
   in a private field (`SlotManager.tabIconTexture`, set via
   `SlotManager.setTabIconTexture()`) — it is NOT stored as a `SlotData` entry
   in the main slot map. So `getById("tab_icon")` always returns `null`. The
   lambda falls through to the fallback and returns the first slot block's item.
   The actual tab icon texture bytes are never used by the lambda at all.

3. **Code path — Layer 2 (model JSON never generated):**
   `ServerPackGenerator.java:233–245` writes `tab_icon.png` to the ZIP, then
   immediately jumps to `addGeneratedItemModel(zos, "black_square", ...)` at
   line 247 — skipping `tab_icon` entirely. Same gap in
   `ResourcePackGenerator.java:415–432`. Every other item (`black_square`,
   `yellow_square`, `diamond_triangle`, `lumina_brush`, etc.) has a
   `models/item/<name>.json` generated. `tab_icon` has none. Minecraft cannot
   render an item without a model.

4. **Code path — Layer 3 (no registered Item):**
   `CustomBlocksMod.java:115–198` registers every tool item — but there is no
   `Registry.register(Registries.ITEM, Identifier.of("customblocks", "tab_icon"), ...)`
   call anywhere. Without a registered `Item`, no `ItemStack` can be constructed
   to pass to the tab icon lambda, even if layers 1 and 2 were fixed.

5. **Root cause:** The tab icon system was designed as: upload texture →
   store bytes → expose via a dedicated registered `Item` → lambda returns
   `ItemStack` of that item. Steps 1 and 3 of that chain were never
   implemented. The texture infrastructure (network delivery, disk write,
   `SlotManager` storage) works perfectly — the consumer side was simply
   never built.

**What works correctly (do not touch):**
- `FullSyncPayload` sends `tabIconTexture` bytes to the client ✓
- `CustomBlocksClient.java:383–384` receives and stores the bytes ✓
- `SlotManager.getTabIconTexture()` / `setTabIconTexture()` work correctly ✓
- `bustItemGroupIconCache()` reflection logic is correct (ineffective only
  because the item doesn't exist yet, not because of any bug in it) ✓
- `tab_icon.png` texture is written to the resource pack correctly ✓

**The solution — three precise changes:**

**Change 1 — Register a dedicated `TabIconItem` in `CustomBlocksMod.java`**
(add near line 198, after the other tool registrations):
```java
// Tab icon — a flat item whose texture is the server-chosen tab image.
// Registered once at startup; texture is swapped via resource pack reload.
public static final Item TAB_ICON_ITEM =
    Registry.register(
        Registries.ITEM,
        Identifier.of(MOD_ID, "tab_icon"),
        new net.minecraft.item.Item(new Item.Settings()));
```

**Change 2 — Fix the icon lambda in `CustomBlocksMod.java:322–336`**
to return a stack of `TAB_ICON_ITEM` when a tab icon texture exists:
```java
.icon(
    () -> {
        // If a tab icon texture has been uploaded, use the dedicated item.
        // Its texture is backed by the resource pack — no slot lookup needed.
        if (SlotManager.getTabIconTexture() != null
                && SlotManager.getTabIconTexture().length > 0) {
            return new ItemStack(TAB_ICON_ITEM);
        }
        // Fallback: first available slot block
        for (SlotData d : SlotManager.allSlots()) {
            Item si = safeSlotItem(d.index);
            if (si != null) return new ItemStack(si);
        }
        return new ItemStack(Items.BOOKSHELF);
    })
```

**Change 3 — Generate the model JSON in both pack generators**

In `ServerPackGenerator.java`, add immediately after line 245 (after the
`else` block that writes `PLACEHOLDER_PNG`):
```java
addGeneratedItemModel(zos, "tab_icon", "customblocks:item/tab_icon", writtenPaths);
```

In `ResourcePackGenerator.java`, add immediately after line 432 (after the
`else` block that writes `PLACEHOLDER_PNG`):
```java
JsonObject tabTex = new JsonObject();
tabTex.addProperty("layer0", MOD_ID + ":item/tab_icon");
JsonObject tabModel = new JsonObject();
tabModel.addProperty("parent", "minecraft:item/generated");
tabModel.add("textures", tabTex);
writeJson(tabModel, new File(assets, "models/item/tab_icon.json"));
```

**Why this design is correct:**
- `TAB_ICON_ITEM` is a plain vanilla `Item` with no special behaviour.
  Its visual appearance is entirely controlled by the resource pack texture.
  When the owner uploads a new tab icon, the resource pack regenerates and
  the texture changes — the item itself never needs to change.
- The lambda no longer needs to look up `SlotData` at all for the tab icon
  case; it simply checks whether a texture has been stored.
- `bustItemGroupIconCache()` will continue to work correctly after this fix —
  it clears the cached `ItemStack` so the lambda re-runs and picks up the
  latest texture after a resource pack reload.

**The experience:**
- Server owner uploads a tab icon image → resource pack regenerates →
  creative tab immediately shows the correct icon for all players.
- Players who open the creative tab before the resource pack loads see
  the bookshelf fallback briefly, then the correct icon appears after
  pack application (same behaviour as block textures).
- No player action required. No server restart required.

**Edge cases:**
- Tab icon texture not yet uploaded (fresh install) → `getTabIconTexture()`
  returns `null` → lambda falls through to first slot block as before.
  Behaviour is unchanged from current (broken) state until an icon is set.
- Tab icon texture deleted → same null-check fallback kicks in.
- Resource pack reload mid-session → `bustItemGroupIconCache()` already
  handles this; no additional work needed.
- `TAB_ICON_ITEM` appears in the creative tab item list → add
  `.excludeFromStandardCreativeTab()` to `Item.Settings()` or add a
  `hideFromItemGroups` data component to prevent it showing as a pickable
  item.

**Files:**
- `CustomBlocksMod.java` — register `TAB_ICON_ITEM`; rewrite icon lambda
- `network/ServerPackGenerator.java` — add `addGeneratedItemModel` call
  for `tab_icon` after line 245
- `client/ResourcePackGenerator.java` — write `models/item/tab_icon.json`
  after line 432

---

### 1.20 Player sees invisible/missing texture blocks after joining — drip-feed race condition

> ⚠️ **NOT YET IMPLEMENTED** — Both bugs confirmed by code audit. The current folder-exists hash check and timer-based debounce are still in place.

**The problem:** When a player joins a server with many custom blocks (500+), some
random blocks appear with missing/purple textures. The blocks look fine in the world
but have no texture. Rejoining usually fixes them, but not always. The problem is
random — different blocks are missing each time.

**Root cause — two compounding bugs:**

*Bug 1 — Timer-based sync is a race condition:* The client waits up to `joinDebounceMs`
(default 4000 ms) for all textures to arrive, then builds the resource pack regardless
of whether all textures arrived. This is fundamentally broken: ANY hardcoded timer is
a guess. Any server hiccup (GC pause, chunk load, other player actions) can push delivery
past the timer. The client builds a partial pack with no way to know it's partial.

*Bug 2 — Hash check only verifies the folder exists, not individual PNG files:*
After building a partial pack, the client computes a hash and stores it. On the next
join, the hash check in `CustomBlocksClient.java:1008` only checks whether the
`assets/` folder exists — not whether every PNG file is present and uncorrupted. A
partial pack with missing or corrupted PNGs passes the hash check and is re-used
as-is. The missing textures persist across rejoins indefinitely.

**The solution — two architectural upgrades:**

**Fix 1 — Count-verified signal-driven sync (replaces the timer entirely):**

The server already sends `SyncCompletePayload` when all textures have been transmitted.
Minecraft uses TCP — packets are NEVER dropped or reordered, they are retransmitted by
the OS. A timer is only needed when you don't know how many packets to expect.

The fix: server counts exactly how many texture payloads it sends during this sync
session, and includes that count in `SyncCompletePayload`:

```java
// Server side — in NetworkManager.java:
int payloadsSent = 0;
// ... drip-feed loop sends payloads, incrementing payloadsSent each time ...
// When done:
player.networkHandler.sendPacket(new SyncCompletePayload(payloadsSent, manifest));
```

Client build condition — BOTH must be true before building:
```java
// Client side — CustomBlocksClient.java:
if (receivedPayloadCount == expectedPayloadCount && syncCompleteReceived) {
    buildResourcePack(); // guaranteed to have everything
}
```

`joinDebounceMs` is **removed entirely** from `CustomBlocksConfig.java`. No timer,
no race condition. The pack builds the exact moment the last texture arrives.
If the server crashes mid-sync: TCP signals disconnection, the client knows immediately
(count didn't match), and the SHA-256 manifest from the previous join (Fix 2 below)
identifies exactly which textures are missing on the next join.

**Fix 2 — Per-texture SHA-256 manifest (replaces the folder-exists hash check):**

The server includes a cryptographic manifest in `SyncCompletePayload`:
```java
Map<String, String> manifest; // slotId → SHA-256 hex of texture bytes
// ~50 bytes per slot × 600 slots = ~30KB total — small
```

Client stores this manifest after every successful pack build:
`config/customblocks/pack_manifest.json`

On next join, BEFORE requesting any sync, the client verifies every texture
individually:
```java
for (Map.Entry<String, String> entry : storedManifest.entrySet()) {
    String slotId = entry.getKey();
    String expectedHash = entry.getValue();
    File png = new File(assetsDir, slotId + ".png");
    if (!png.exists() || !sha256(png).equals(expectedHash)) {
        missingOrChanged.add(slotId); // needs re-fetch
    }
}
```

If `missingOrChanged` is empty → skip sync entirely (zero network traffic on rejoin).
If not empty → send `PartialSyncRequestPayload(missingOrChanged)` to server, which
sends ONLY those specific textures. No full re-sync of 584 blocks for a 3-texture update.

This is SHA-256 — cryptographically certain. A matching hash means byte-for-byte
identical texture. Zero false positives. Zero chance of a corrupt or missing texture
going undetected regardless of how many times the server crashes.

**The experience:** Player joins → all custom blocks show correct textures on first
join, every time. Rejoining after a texture update is nearly instant (only changed
textures transfer). No more "rejoin lottery." No more "I have to rejoin 3 times."

**Edge cases:**
- Server has 0 custom blocks → `SyncCompletePayload(payloadsSent: 0, manifest: {})` → client
  builds immediately, verifies 0 entries → done in milliseconds.
- Server crashes after sending 400 of 584 textures → TCP drops → client count doesn't
  match → uses stored manifest to identify and re-request the 184 missing textures on
  next join. Player sees 400 correct textures and 184 purple blocks, then the 184 fill
  in as they re-sync. No full restart of the whole sync process.
- Chunk textures (animated GIFs split into multiple `ChunkedTexturePayload`): count them
  as ONE payload when the final chunk is assembled (not per-chunk). The server's
  `payloadsSent` count = number of completed slot deliveries, not number of packets.
- Player's local pack directory is manually deleted → manifest file is also gone →
  client treats this as a fresh install → full sync. Works correctly.
- Texture updated while player is online → server sends targeted slot update packet
  → client updates that slot's file → updates the manifest entry → hash stays current.

**Files:**
- `CustomBlocksClient.java` — replace timer loop with count-verified signal handler;
  replace folder-exists hash check with SHA-256 manifest verification; add
  `PartialSyncRequestPayload` send for missing/changed textures
- `NetworkManager.java` — count payloads during drip-feed; include count + manifest
  in `SyncCompletePayload`; add handler for `PartialSyncRequestPayload`
- `SyncCompletePayload.java` — add `payloadCount: int` and `manifest: Map<String,String>` fields
- `CustomBlocksConfig.java` — remove `joinDebounceMs` field entirely

---

### 1.21 Admin uploads replacement texture — block marked fixed but still shows broken in-world

**The problem:** When a block's texture file is missing or corrupted, it appears in
the `/cb listgui` broken-blocks view. An admin uploads a replacement texture. The
block immediately disappears from the broken list. But the block in the world still
shows the purple/black checkerboard — it's still broken. The admin has no feedback
that the fix didn't take, and the block stays visually broken indefinitely.

**Root cause:** The `isBroken` flag on `SlotData` is computed from whether the
PNG bytes decode successfully. When a new texture is uploaded, `withTexture()`
creates a new `SlotData` with `isBroken = false` (the new bytes decode fine). The
admin list shows the block as fixed. But the texture file write to disk may still be
pending in the IO_EXECUTOR queue, and the resource pack may not have been regenerated
yet. The in-world block never updates.

**The solution:** After a retexture operation completes (texture written to disk,
resource pack regenerated, sync sent to clients), send the admin a confirmation:

```
[CB] slot_42 (obsidian_smooth) retextured — pack updated. Blocks refreshed for 3 online players.
```

If the resource pack regeneration fails or no players received the updated pack,
change the message:
```
[CB] Texture uploaded but resource pack failed to regenerate. Type /cb rp to retry.
```

Additionally, keep the block in the "needs attention" admin list until the resource
pack has actually been rebuilt and confirmed — not just until `isBroken` flips.

**The experience:** Admin uploads texture → gets a confirmed success or a clear
error with a fix action. No more silently broken blocks after "fixed" uploads.

**Edge cases:**
- If no players are online when the texture is updated, the message says
  `Pack updated — will sync when players join.`
- If the retexture is for a block that has never been placed in the world, the
  confirmation still fires (the pack still needs to be current for future placements).
- If `isBrokenTexture()` returns false for a 0×0 pixel PNG (decodes but renders
  as nothing), the block still looks broken. Add a dimension check: any texture
  with width or height < 16 is flagged as broken.

**Files:**
- `SlotManager.java` (`update()` — add post-confirmation callback)
- `GuiManager.java` (retexture handler — send confirmation message after pack regen)
- `SlotData.java` (`isBrokenTexture()` — add dimension check)

---

### 1.22 Join sync failure is logged silently — player sees invisible blocks with no explanation

> ⚠️ **NOT YET IMPLEMENTED** — Forensic analysis confirmed zero implementation exists in the current codebase. onPlayerJoin() in NetworkManager.java is empty. No retry logic or player-facing error messages exist.

**The problem:** When something goes wrong while sending block data to a joining
player (network hiccup, serialization error, slow server), the exception is caught
in `CustomBlocksMod.java:402-412` and logged to the **server console only** with
`LOGGER.error`. The **player** sees invisible blocks — or no blocks at all — with
zero explanation. No in-game error message, no retry option, no way to recover
except repeatedly rejoining and hoping it works.

**The solution:** When a join sync fails (exception caught, or `sync_done` never
received within the timeout), send the player a clear chat message:

```
[CB] Block sync incomplete — some blocks may be invisible. Type /cb sync to retry.
```

And implement `/cb sync` (client-side command) that sends a fresh `SyncRequestPayload`
to restart the drip-feed from the beginning. This already exists on the server side
(the server responds to `SyncRequestPayload`), it just has no accessible trigger for
players.

**Architecture note:** `onPlayerJoin()` in `NetworkManager.java` is intentionally
empty — sync is client-initiated. The client sends `SyncRequestPayload` on join, and
the server responds. No server-push on join. The retry and error message logic must
live on the CLIENT SIDE, not in `onPlayerJoin()`.

When the count-verified sync from item 1.20 detects a mismatch (received count ≠
expected count at TCP disconnect), the client sends the player:

```
[CB] Block sync incomplete — some blocks may be invisible. Type /cb sync to retry.
```

`/cb sync` is a client-side command that sends a fresh `SyncRequestPayload` (or
`PartialSyncRequestPayload` once item 1.20's manifest system is in place — which
re-requests only the missing textures specifically).

Server-side: if `CustomBlocksMod.java:402-412` catches an exception during the
initial data push, log it AND send the player a message. Do NOT touch `onPlayerJoin()`
in NetworkManager.java — it handles the SyncRequestPayload response correctly as-is.

**The experience:** Player joins with broken blocks → immediately knows why → one
command fixes it → blocks appear. No helpless rejoining loop.

**Edge cases:**
- If the retry also fails (persistent network issue), the player gets:
  `[CB] Sync failed after retries. This is usually a network issue. Contact the server admin if it persists.`
- `/cb sync` should be rate-limited to once per 10 seconds per player to prevent spam.
- Once item 1.20 is implemented: `/cb sync` triggers a `PartialSyncRequestPayload`
  with just the missing slot IDs from the manifest diff — much faster than a full re-sync.

**Files:**
- `CustomBlocksMod.java` (~line 402 — add player notification on exception catch)
- `CustomBlocksClient.java` — detect count mismatch on TCP disconnect; send player
  error message; handle `/cb sync` client-side command
- `CustomBlockCommand.java` (add `/cb sync` command — triggers client-side sync request)
- `NetworkManager.java` — no changes needed to `onPlayerJoin()`; already handles `SyncRequestPayload` correctly

---

### 1.23 Null vs. missing texture indistinguishable — admin can't tell if upload ever happened

**The problem:** In the broken-blocks admin view, blocks show as "BROKEN" with no
reason. A block where the texture was never uploaded looks identical to a block
where the texture file was accidentally deleted. The admin re-uploads textures for
blocks that were never broken — they just have no texture by design (e.g. blocks
under construction). Admin wastes time and confusion spirals.

**The solution:** Distinguish three states in the broken-blocks view:

| Status | What it means | Admin tooltip |
|--------|--------------|---------------|
| `NEVER UPLOADED` | `texture == null` and no `.dat` file on disk | "No texture has been uploaded for this block yet." |
| `FILE MISSING` | `texture == null` but slot was previously saved with a texture (detect via save metadata timestamp) | "Texture file was lost — please re-upload." |
| `CORRUPTED` | `isBroken == true` (file exists but PNG decode failed) | "Texture file is corrupted — please re-upload." |

Add a `reason` field to the broken-block record that the GUI reads for the tooltip.

**The experience:** Admin opens broken-blocks list → immediately sees WHY each
block is broken → knows exactly what action to take. No guessing.

**Edge cases:**
- If the metadata timestamp approach isn't feasible, fall back to a simpler check:
  if the `.dat` file exists but is unreadable, it's CORRUPTED; if no `.dat` file
  exists and no texture in memory, it's NEVER UPLOADED or FILE MISSING (combine
  as "MISSING" with tooltip "No texture on disk — upload one to fix this block").
- Blocks that have NEVER been given a texture intentionally (admin workflow) should
  not appear as broken. Add a `textureIntentionallyEmpty` flag that admins can set
  to suppress the warning for a block.

**Files:**
- `SlotManager.java` (`brokenBlocks()` — add reason enum to return type)
- `SlotData.java` (add a new texture status enum to carry reason — note: `SlotData.textureStatus` does NOT exist yet; the current broken-texture concept is `transient boolean isBroken`. Add a new `textureReason` enum: `NEVER_UPLOADED`, `FILE_MISSING`, `CORRUPTED`)
- `GuiManager.java` (broken-blocks view — show reason in tooltip, add "suppress warning"
  button for intentionally empty blocks)

---

### 1.24 CbScreenHandler click exceptions crash the GUI with no error or recovery

**The problem:** Every GUI interaction — clicking a slot in any CustomBlocks chest
GUI — goes through `CbScreenHandler.onSlotClick()`. This method calls
`GuiManager.handleClick()` with no try-catch. If `handleClick()` throws for any
reason (texture write fails, null block data, concurrent modification), the screen
handler crashes. The player's GUI freezes — server thinks the screen is closed,
client still shows it as open. The action is lost silently. The player has to close
the GUI and reopen it with no idea what happened.

**The solution:** Wrap the `GuiManager.handleClick()` call in a try-catch:

```java
try {
    GuiManager.handleClick(sp, slotIndex, button, actionType);
} catch (Exception e) {
    CustomBlocksMod.LOGGER.error("[CustomBlocks] GUI click error in slot {}", slotIndex, e);
    sp.sendMessage(Text.literal("§c[CB] Something went wrong. The action was not applied."), true);
    // Force-close the screen to put client and server in agreement
    sp.closeHandledScreen();
}
```

The action bar message (`true` as second arg) is non-intrusive. Force-closing the
screen via `closeHandledScreen()` ensures the client knows the GUI closed, preventing
the frozen-screen state.

**The experience:** Admin clicks a slot → if something goes wrong, a brief red
message appears above the hotbar, the GUI closes cleanly, and they can reopen it.
No freeze, no confusion.

**Edge cases:**
- If the error is transient (network blip), reopening the GUI works fine. If it's
  persistent (corrupted slot data), the error fires every time → admin knows there's
  a deeper problem and can report it.
- Don't swallow the exception silently — always log it so the server log shows what
  went wrong.
- `syncState()` (called after the click) should still run even after a caught
  exception, to keep the GUI in a consistent visual state if the screen wasn't closed.

**Files:**
- `CbScreenHandler.java` (`onSlotClick()` ~line 54 — add try-catch around
  `GuiManager.handleClick()`)

---

### 1.25 SSRF — server fetches internal/private network URLs on OP command

> ❌ **SECURITY VULNERABILITY — NOT PATCHED** — Confirmed by code audit. `ImageProcessor.java` accepts and fetches arbitrary URLs with no address validation. Zero protection against private network access.

**The problem:** Every command that accepts a URL (`/cb create`, `/cb retexture`, `/cb setface`, tab icon upload) passes that URL directly to `ImageProcessor.downloadImage()`. The server fetches it with no check on where it points. An OP can type:

```
/cb retexture myblock http://169.254.169.254/latest/meta-data/iam/security-credentials/
  → Server fetches AWS instance credentials from the hosting provider's metadata endpoint

/cb retexture myblock http://localhost:8080/admin
  → Server fetches its own admin panel and processes the HTML as an image

/cb retexture myblock http://10.0.0.1/api/internal
  → Server fetches internal services on the hosting provider's container network
```

This is called SSRF (Server-Side Request Forgery). On MCServerHost's Docker infrastructure, the server shares a network with other tenants' containers and internal services. Private address access can expose credentials, admin interfaces, or other customers' data.

**The fix — validate every URL before connecting:**

```java
// Add to ImageProcessor.java:
private static void validateUrlSecurity(String urlString) throws IOException {
    java.net.URI uri;
    try {
        uri = java.net.URI.create(urlString);
    } catch (IllegalArgumentException e) {
        throw new IOException("Invalid URL format.");
    }

    String scheme = uri.getScheme();
    if (!"http".equals(scheme) && !"https".equals(scheme)) {
        throw new IOException("Only http:// and https:// URLs are allowed.");
    }

    String host = uri.getHost();
    if (host == null || host.isBlank()) {
        throw new IOException("URL has no valid host.");
    }

    java.net.InetAddress addr;
    try {
        addr = java.net.InetAddress.getByName(host);
    } catch (java.net.UnknownHostException e) {
        throw new IOException("Could not resolve hostname: " + host);
    }

    if (addr.isLoopbackAddress()) {
        throw new IOException("URL points to localhost — not allowed.");
    }
    if (addr.isSiteLocalAddress()) {
        throw new IOException("URL points to a private network — not allowed.");
    }
    if (addr.isLinkLocalAddress()) {
        throw new IOException("URL points to a link-local address — not allowed.");
    }
    if (addr.isAnyLocalAddress()) {
        throw new IOException("URL resolves to an unroutable address — not allowed.");
    }
}
```

Call `validateUrlSecurity()` at the top of every method in `ImageProcessor.java` that accepts a URL, before any connection is opened. Also call it in `GuiManager.java` for any URL input fields in the face import flow.

**Player-facing error:** `§c[CB] That URL is not allowed. Only public internet addresses are accepted.`

**Edge cases:**
- IPv6 private ranges (::1, fc00::/7, fe80::/10) — `isLoopbackAddress()` and `isSiteLocalAddress()` cover the common ones; add explicit checks for the IPv6 private unicast prefix fc00::/7 if needed.
- URL redirects to a private address — follow each redirect and re-run `validateUrlSecurity()` on the redirect target before connecting.
- Cloudflare Worker URLs (already in the code for cloud sharing) — these resolve to public Cloudflare IPs and pass the check with no special handling needed.

**Why this matters:** This is not a theoretical risk. On shared Docker hosting, every container on the same physical host may be reachable via link-local or internal addresses. A single OP with malicious intent could use this to probe the hosting provider's internal network. The fix is a small validation method called before every HTTP fetch.

**Files:** `ImageProcessor.java` — add `validateUrlSecurity()`, call it in every URL-accepting method before connection; `GuiManager.java` — call the same validation for any URL typed into GUI input fields (face import URL, retexture URL prompts)

---

### 1.26 AnimSettingsPayload DoS — any connected client can spam the server tick loop

> ❌ **SECURITY VULNERABILITY — NOT PATCHED** — Confirmed by code audit. `NetworkManager.java` applies no rate limiting to `AnimSettingsPayload`. Any connected client (not just OPs) can send this packet at unlimited speed.

**The problem:** `AnimSettingsPayload` is a client-to-server packet that updates animation settings (FPS, interpolation, frame count) for a custom block. The handler in `NetworkManager.java` processes every packet immediately with no throttle. On each packet, the server runs:

```
AnimSettingsPayload received
  → SlotManager.setAnimMeta() — modifies slot data
  → SlotManager.saveAll()    — marks dirty, queues disk write
  → ResourcePackServer.updatePackWithSnapshot() — may trigger ZIP rebuild
```

A client sending 1,000 packets per second triggers this entire chain 1,000 times per second. The ZIP rebuild alone can take 2+ seconds. The server falls behind on ticks, and players start getting "Can't keep up!" warnings and disconnects. This does not require OP permission — any connected player whose client is modified can send this packet.

**The fix — 100ms cooldown per player UUID:**

```java
// In NetworkManager.java, add at class level:
private static final Map<UUID, Long> ANIM_SETTINGS_COOLDOWN = new ConcurrentHashMap<>();
private static final long ANIM_SETTINGS_COOLDOWN_MS = 100L;

// At the top of the AnimSettingsPayload handler:
long now = System.currentTimeMillis();
Long last = ANIM_SETTINGS_COOLDOWN.put(playerUuid, now);
if (last != null && (now - last) < ANIM_SETTINGS_COOLDOWN_MS) {
    return; // silently discard — client is sending faster than allowed
}
```

Also add cleanup in the player disconnect handler to prevent memory leaks:
```java
// In onPlayerDisconnect():
ANIM_SETTINGS_COOLDOWN.remove(uuid);
```

**Edge cases:**
- Player legitimately adjusting FPS with a slider — 100ms cooldown allows 10 updates per second, more than enough for any slider interaction. The final value always goes through when the slider stops moving.
- Multiple players editing different blocks simultaneously — tracked per UUID, no cross-player interference.
- 10ms cooldown vs 100ms — 100ms is the correct value. 10ms is too permissive (100 packets/second still strains the server). 1000ms is too strict (noticeable lag on slider drag).

**Why this matters:** This vulnerability requires no OP permission and no special knowledge — any player with a modified client can trigger it. On a 3 GB RAM server with 137 mods already loaded, a sustained packet storm will cause real disconnects for legitimate players within seconds.

**Files:** `NetworkManager.java` — add `ANIM_SETTINGS_COOLDOWN` map, add cooldown check at top of AnimSettingsPayload handler, add cleanup in `onPlayerDisconnect()`

---

### 1.27 Sort menu does not exist — blocks always sorted alphabetically with no user control

> ⚠️ **NOT YET IMPLEMENTED** — `SlotManager.sortedSlots()` always sorts alphabetically by `displayNameLower`. No sort menu exists in `GuiMode.java`. No user-configurable sort order of any kind.

**The problem:** Every block list in every screen — the main picker, the editor picker,
bulk delete, search results — uses `sortedBlocks()` which calls `SlotManager.sortedSlots()`.
That method always sorts by display name alphabetically, hardcoded. There is no way for
an admin to find their most recently edited block, see blocks grouped by category, find
the heaviest animated GIFs, or prioritize broken blocks. With 500+ blocks, navigating
without sorting control is painful.

**The solution:** A sort menu accessible from the main picker and the block list screens,
with persistent sort preference saved per-player:

```
Sort Options:
  [A→Z  Name]        — alphabetical ascending (current default)
  [Z→A  Name]        — alphabetical descending
  [0→9  Slot Index]  — creation order, oldest first
  [9→0  Slot Index]  — most recently added first
  [★ Recently Edited]— most recently changed block at top
  [▶ Animated First] — animated GIF blocks before static
  [⚠ Broken First]  — blocks with broken/missing textures at top
  [📁 By Category]   — group blocks by category, alphabetical within
  [📦 By Size]       — largest texture (most RAM/disk) first
  [🔒 Locked First]  — locked/protected blocks at top
  [💡 By Glow]       — highest glow level first
  [🔊 By Sound]      — grouped by sound type (wood, stone, metal...)
```

Sort preference is stored per-player UUID in a `Map<UUID, SortMode>` in GuiManager.
It persists for the session. A small sort indicator in the picker title shows the
active sort: `"§b§l▶ §r§fPick a Block §8— sorted by: Recently Edited"`.

Clicking the sort button opens a 54-slot sort menu. Each option is a distinct item
(Echo Shard for name sorts, Clock for time-based, Wrench for property-based).
Clicking an option sets the preference and reopens the picker with the new sort applied.

**A sort button** is added to the picker screen (e.g., slot 51 in the footer row),
opening the sort menu via `pushBackStack` → `openSortMenu` → user picks → `openEditorPicker`.

**The experience:** Admin opens the block picker → clicks Sort → picks "Recently Edited" →
the block they were just working on is at the top. Or picks "Broken First" → immediately
sees all the blocks that need texture uploads. No more scrolling through pages looking
for one block.

**Edge cases:**
- "By Category" sort when CategoryManager isn't built yet (Tier 1, see item 1.29) →
  show option as greyed-out with lore "§7Requires categories to be set up first."
- "Broken First" requires `isBroken` evaluation per slot on sort — this is already
  computed in SlotData and cached in SlotManager. No extra cost.
- "Recently Edited" requires a `lastEditedAt` timestamp in SlotData — add this field.
  On upgrade, existing blocks get `lastEditedAt = 0` and sort to the bottom (treated
  as "never edited"). New edits update the timestamp.
- "By Size" for animated blocks — use the texture byte array length. Requires reading
  texture data for every slot during sort — do this once and cache in sorted order.
- Sort preference resets on disconnect — acceptable. Session-level persistence only.

**Files:**
- `SlotManager.java` — expose `sortedSlots(SortMode mode)` overload; add `lastEditedAt`
  field write-through when any slot is modified
- `SlotData.java` — add `lastEditedAt: long` field
- `GuiManager.java` — add `openSortMenu()`, `buildSortMenu()`, `handleSortMenuClick()`;
  add `PLAYER_SORT_PREFS: Map<UUID, SortMode>`; update `buildPicker()` to pass
  active sort to `sortedBlocks()`; add sort button to picker footer
- New `SortMode.java` enum — NAME_ASC, NAME_DESC, INDEX_ASC, INDEX_DESC, RECENTLY_EDITED,
  ANIMATED_FIRST, BROKEN_FIRST, BY_CATEGORY, BY_SIZE, LOCKED_FIRST, BY_GLOW, BY_SOUND

---

### 1.28 UndoManager stores full texture copies in RAM — redesign to delta-differential with disk-backed texture snapshots

> ⚠️ **NOT YET IMPLEMENTED** — Current UndoManager stores full `SlotData` deep copies per entry including all texture bytes. Forensic analysis: `SlotData` constructor does eager `texture.clone()` + clones every face byte array. The existing `maxUndoDepth = 20` with "both" mode (global stack + per-player stack) = up to 6 stacks simultaneously. Worst case: 5 players × 2 stacks × 20 entries × 1.4 MB = **168 MB** consumed by undo alone on a 3 GB server with 137 mods.

**The problem:** Each undo entry stores a complete deep copy of `SlotData` including all texture byte arrays (50–200 KB each). Most undo operations do NOT change the texture — a rename, a hardness change, a sound type change — yet each stores hundreds of KB of untouched texture data. This is pure waste.

**The solution — Delta-Differential Undo with Disk-backed Texture Snapshots:**

**Principle:** Store ONLY what changed. For metadata ops (rename, sound, hardness, shape, animMeta): store in RAM — these are tiny (~50–300 bytes each). For texture ops (retexture, face change, delete): snapshot old texture to disk, store only the file path in RAM (~60 bytes). This gives a hard RAM guarantee regardless of texture sizes.

**Sealed delta interface:**
```java
sealed interface UndoDelta permits
    MetaDelta, TextureDelta, FaceDelta,
    AnimDelta, ShapeDelta, SlotCreated, SlotDeleted {

    String customId();
    String description();
    UUID playerUuid();

    // Metadata ops — tiny, RAM-only (~50-300 bytes each)
    record MetaDelta(String customId, UUID playerUuid,
        String prevName, Float prevHardness, Integer prevLight,
        String prevSound, Boolean prevNoCollision) implements UndoDelta {
        public String description() { return "meta"; }
    }
    record AnimDelta(String customId, UUID playerUuid,
        String prevMcmeta) implements UndoDelta {
        public String description() { return "anim"; }
    }
    record ShapeDelta(String customId, UUID playerUuid,
        List<SlotData.ShapeBox> prevBoxes) implements UndoDelta {
        public String description() { return "shape"; }
    }

    // Texture ops — snapshot stored on disk; RAM holds only the path (~60 bytes)
    record TextureDelta(String customId, UUID playerUuid,
        String snapshotPath) implements UndoDelta {
        public String description() { return "retexture"; }
    }
    record FaceDelta(String customId, UUID playerUuid,
        String face, String snapshotPath) implements UndoDelta {
        public String description() { return "face-" + face; }
    }

    // Create/delete — create undo = delete; delete undo = recreate from full snapshot
    record SlotCreated(String customId, UUID playerUuid) implements UndoDelta {
        public String description() { return "create"; }
    }
    record SlotDeleted(String customId, UUID playerUuid,
        String snapshotPath) implements UndoDelta { // full SlotData on disk
        public String description() { return "delete"; }
    }
}
```

**RAM profile per delta type:**
| Operation | Current | After redesign |
|---|---|---|
| Rename | 1.4 MB | ~50 bytes |
| Hardness / glow / sound change | 1.4 MB | ~30 bytes |
| Shape change | 1.4 MB | ~200 bytes |
| Anim meta change | 1.4 MB | ~200 bytes |
| Texture retexture (200 KB) | 1.4 MB | ~60 bytes (path only) |
| Face texture change | 1.4 MB | ~70 bytes (path only) |
| Block deletion | 1.4 MB | ~80 bytes (path only) |
| **20 renames, 5 players, "both" mode** | **168 MB** | **~36 KB** |

**Apply-undo logic (pattern-matched):**
```java
static void applyUndo(UndoDelta delta, SlotManager slots) {
    switch (delta) {
        case MetaDelta m -> {
            SlotData cur = slots.get(m.customId()), next = cur;
            if (m.prevName()        != null) next = next.withDisplayName(m.prevName());
            if (m.prevHardness()    != null) next = next.withHardness(m.prevHardness());
            if (m.prevLight()       != null) next = next.withLightLevel(m.prevLight());
            if (m.prevSound()       != null) next = next.withSoundType(m.prevSound());
            if (m.prevNoCollision() != null) next = next.withNoCollision(m.prevNoCollision());
            slots.put(m.customId(), next);
        }
        case AnimDelta a   -> slots.put(a.customId(),
                                slots.get(a.customId()).withAnimMeta(a.prevMcmeta()));
        case ShapeDelta s  -> slots.put(s.customId(),
                                slots.get(s.customId()).withShapeBoxes(s.prevBoxes()));
        case TextureDelta t -> {
            byte[] bytes = readSnapshot(t.snapshotPath());
            if (bytes == null) { warn("Texture snapshot missing for " + t.customId()); return; }
            slots.put(t.customId(), slots.get(t.customId()).withTexture(bytes));
        }
        case FaceDelta f   -> {
            byte[] bytes = readSnapshot(f.snapshotPath());
            slots.put(f.customId(), bytes == null
                ? slots.get(f.customId()).withoutFaceTexture(f.face())
                : slots.get(f.customId()).withFaceTexture(f.face(), bytes));
        }
        case SlotDeleted d -> {
            SlotData snap = deserializeSlotData(readSnapshot(d.snapshotPath()));
            if (snap != null) slots.restore(snap);
        }
        case SlotCreated c -> slots.delete(c.customId());
    }
}
```

**Texture snapshot mechanism:**
When a texture mutation occurs, BEFORE overwriting:
1. Copy old texture file → `config/customblocks/undo_snapshots/<slotId>_<timestamp>.dat`
2. Use `.tmp` → `ATOMIC_MOVE` to make snapshot write crash-safe
3. Store only the snapshot path in the delta record (~60 bytes RAM)
4. On undo: read snapshot from disk, broadcast update
5. When entry is evicted from the stack: delete its snapshot file

**Snapshot directory management:**
- Max snapshot directory size: 200 MB (enforced on eviction — when evicting an entry, delete its snapshot file first)
- Startup orphan cleanup: scan `undo_snapshots/` on startup, delete any `.dat.tmp` files and any `.dat` files with no corresponding undo entry reference
- No time-based expiry — snapshots are kept until the undo entry is evicted from the stack or the 200 MB cap triggers eviction

**Undo depth settings (new values):**
- Default: `maxUndoDepth = 50` (raised from current 20)
- Maximum clamp: `100` (unchanged from current code)
- Config field name: `maxUndoDepth` (unchanged)

**The experience:** Admin does 50 operations (renames, texture changes, shape edits) →
uses `/cb undo` repeatedly → each operation reverts correctly → RAM from undo
history is negligible regardless of texture sizes. Server RAM is unaffected.

**Edge cases:**
- Snapshot file deleted externally → undo logs "Cannot restore texture for `<id>` — snapshot missing. Metadata (name, settings) will still be restored."
- Server crash mid-snapshot write → startup finds `.dat.tmp` → deletes it → undo entry for it is evicted at next access (null snapshotPath check in applyUndo)
- Undo during async startup load → block until `startupLoadComplete = true`. Show: "§e[CB] Server is still loading — undo available in a moment."
- `undoMode = "global"` → single stack, same mechanism; cap = `maxUndoDepth`
- Delete undo stores full `SlotData` serialized to disk (unavoidable — you must be able to recreate the block). This is the one case where the snapshot is large; deletions are rare.

**Files:**
- `UndoManager.java` — complete redesign: replace `UndoEntry` with `UndoDelta` sealed interface; add snapshot write/read/cleanup; add startup orphan cleanup; wire `applyUndo()` pattern match
- `SlotData.java` — add `lastEditedAt: long` timestamp (updated on every mutation)
- `CustomBlocksConfig.java` — update `maxUndoDepth` default from 20 to 50
- New `config/customblocks/undo_snapshots/` directory (created automatically on first write)

---

### 1.29 Manager class implementation plan — tier structure

Ten manager classes are referenced throughout this plan. None of them exist in the
current codebase (confirmed by full source audit). They must all be built from scratch.
They are organized into tiers by priority and complexity:

**Tier 1 — Required NOW (Phase 2 depends on them):**

| Class | Location | Purpose | Complexity |
|---|---|---|---|
| `CategoryManager` | `com.customblocks.core` | Organize blocks into named groups/folders. Each block can belong to one category. Categories appear in the picker as filter tabs. | Medium |
| `FavoritesManager` | `com.customblocks.core` | Per-player Set<String> of favorited block IDs. Stars appear on favorited blocks in the picker. | Small |
| `LockManager` | `com.customblocks.core` | Per-block boolean "locked" flag. Locked blocks refuse edit/delete commands with a clear error. | Small |

Full specifications for these three are embedded in the Phase 2 items that use them.

**Tier 2 — Phase 3–4 (medium complexity, placeholder spec):**

| Class | Purpose |
|---|---|
| `BlockNotesManager` | Attach free-text notes/annotations to individual blocks. Notes visible in editor GUI and in lore of the block's item. |
| `WelcomeManager` | First-run experience. Detects fresh install, shows guided setup flow (set Cloud Vault URL, upload a test texture, create first block). |
| `AutoCategorizeManager` | Suggest category assignments based on block name patterns. E.g. all blocks containing "marble" suggested for "Marble" category. Uses `CategoryManager`. |

**Tier 3 — Future (large/complex, deferred to a Phase 5+ spec document):**

| Class | Purpose | Why deferred |
|---|---|---|
| `MacroManager` | Record sequences of admin actions and replay them. E.g. record "create block → upload texture → set properties" and replay for 20 new blocks. | Very complex — requires action recording, serialization, replay safety |
| `SnapshotManager` | Save and restore complete server-side block state (all 600 slots + textures). Separate from UndoManager. | Large disk/RAM cost; needs its own storage format |
| `PlacementStats` | Track how many times each custom block has been placed in the world. Enables "Most Used" sort. | Requires world event hooks; data grows indefinitely |
| `DraftManager` | Allow blocks to be saved in "draft" state — visible to admins but invisible to regular players until published. | Significant architecture change to slot visibility system |

All Tier 3 classes are acknowledged, wanted, and deferred. A separate Phase 5 spec document should be created when Tiers 1 and 2 are complete.

---

## Phase 2 — The Bulk Operations Hub

*Right now, bulk operations are scattered across 6+ separate commands that
players have to memorize. This phase puts every bulk action in one place,
adds missing operations, and makes the selection system actually usable.*

### 2.1 Central bulk GUI — one place for everything

**The problem:** To bulk-delete you type `/cb bulkdelete`. To bulk-recolor,
`/cb bulkrecolor`. To bulk-add to a category, `/cb bulkblockadd`. Each has
different syntax. There's no discoverability — you only know these exist if
you read the help text for every command.

**The solution:** `/cb bulkgui` (alias: `/cb bulk`) opens a hub:

```
Row 1:  [Delete]     [Recolor]    [Rename]       [Re-ID]
Row 2:  [Properties] [Move Cat.]  [Export]        [Duplicate]
Row 3:  [Lock/Unlock][Favorite]   [Shape]         [Sound]
Row 4:  [Select All] [Deselect]   [Filter: ___]  [§e0 selected]
```

**Every button follows the same flow:**
1. Opens a block selector (search + category filter + select all)
2. Player selects blocks across any number of pages (selection persists)
3. Player configures the operation (e.g., picks target color, types new prefix)
4. Preview: "§eThis will rename 23 blocks. §7Example: marble → Custom marble"
5. Confirm / Cancel
6. Boss bar progress during execution: "Renaming 7/23..."
7. Summary: "§aRenamed 23 blocks. §7/cb undo to revert."

**Why a hub matters:** Players think in tasks — "I want to change a bunch of
blocks." They don't think in command names. A visual hub lets them DISCOVER
operations they didn't know existed. Someone who came to delete blocks sees
"Bulk Recolor" and thinks "oh, I can do that too?"

**Files:** `GuiManager.java`, `CustomBlockCommand.java`

---

### 2.2 New bulk operations

**Operations that don't exist yet:**

| Operation | Command | What it does |
|-----------|---------|-------------|
| Bulk Rename | `/cb bulkrename <scope> --prefix "Custom "` | Change display names. Supports `--prefix`, `--suffix`, `--replace "old" "new"` |
| Bulk Re-ID | `/cb bulkreid <scope> --replace "mob_" "creature_"` | Change block IDs. Updates ALL references: categories, variants, placed blocks |
| Bulk Properties | `/cb bulkproperty <scope> sound wood` | Set any property: sound, glow, hardness, collision |
| Bulk Export | `/cb bulkexport <scope>` | Export as a shareable ZIP (blocks + textures) |
| Bulk Move Category | `/cb bulkmove <from> <to>` | Move blocks between categories (removes from source) |
| Bulk Duplicate | `/cb bulkduplicate <scope> --suffix "_copy"` | Clone blocks with new IDs |
| Bulk Lock/Unlock | `/cb bulklock <scope>` | Protect/unprotect blocks from editing |
| Bulk Favorite | `/cb bulkfavorite <scope>` | Star/unstar blocks |
| Bulk Shape | `/cb bulkshape <scope> slab` | Apply a shape preset to multiple blocks |
| Bulk Sound | `/cb bulksound <scope> wood` | Set sound type for multiple blocks |

**Every bulk command follows these rules:**
- Accepts scope expressions: `category:stone`, `name:marble*`, `all`,
  `favorite:yes`, `animated:yes`, `locked:no`
- `--dry-run` flag: preview without applying ("Would affect 23 blocks")
- Full undo support via UndoManager (single undo reverts entire batch)
- Boss bar progress for 5+ blocks
- Summary message with undo hint on completion
- Refuses to execute without confirmation for 10+ blocks (configurable threshold)

**Bulk Re-ID safety:** The most dangerous operation. Must:
- Check for ID collisions BEFORE executing (refuse if any target ID exists)
- Update category assignments, variant parent links, SearchIndex entries
- Show full old→new mapping in preview for the first 10 blocks
- Skip placed-in-world updates if the player doesn't have admin permission

**Files:** `CustomBlockCommand.java`, `GuiManager.java`, `SlotManager.java`,
`CategoryManager.java` (does not yet exist — must be created from scratch at `com.customblocks.core`), `UndoManager.java`

---

### 2.3 Selection system that works across pages

**The problem:** The current bulk delete GUI tracks selection per-page.
Select 5 blocks on page 1, go to page 2, go back to page 1 — your
selections are gone. This makes bulk operations unusable for more than 9
blocks (one page).

**The solution:** Global `Set<String> selectedIds` that persists across
page navigation. Every bulk GUI inherits this exact selection component:

```
┌──────────────────────────────────────────────┐
│ [Search: ________]  [Category: All ▼]        │
│ [Select All]  [Deselect All]  [By Pattern]   │
│──────────────────────────────────────────────│
│ [block1 ✓] [block2  ] [block3 ✓] [block4  ] │ ← glint = selected
│ [block5  ] [block6 ✓] [block7  ] [block8  ] │
│ [block9  ] ........                          │
│──────────────────────────────────────────────│
│ [◀ Prev]   §e3 / 47 selected   [Next ▶]     │
└──────────────────────────────────────────────┘
```

**Key behaviors:**
- "Select All" selects ALL matching blocks (not just current page)
- Search/filter narrows visible blocks but doesn't clear selections
- Enchantment glint on selected items — visible at a glance
- Selection counter updates in real-time: "§e12 / 47 selected"
- "By Pattern" opens an anvil input: type `marble*` → selects all matching

**Files:** `GuiManager.java`, `GuiState.java`

---

## Phase 3 — Color System Overhaul

*The mod has Color Studio (7 tints), Palette Generator (16 hues), AI Smart
Suggest (18 presets), Dress Overlays (5 effects), and Gradients. Impressive
on paper — but scattered across commands and GUIs with no unified entry
point. A player who wants to change a block's color shouldn't need to know
which of 5 tools to use. This phase unifies everything into one coherent
color experience.*

### 3.1 Color Library — click a color, not type a hex code

**The problem:** Every color operation requires a hex code. To get a red
block, you type `#FF0000`. Most players don't know hex codes. They just
want "red."

**The solution:** A visual Color Library GUI with 30+ named, clickable colors:

```
┌─ My Palette (saved colors) ──────────────────┐
│ [My Red]  [Dark Wood]  [Sky Accent]  [+Save] │
├─ Recently Used ──────────────────────────────┤
│ [#FF5500]  [#2244AA]  [#88CC33]              │
├─ Basic Colors ───────────────────────────────┤
│ [Red]  [Orange]  [Yellow]  [Lime]  [Green]   │
│ [Cyan] [Blue]    [Purple]  [Magenta] [Pink]  │
├─ Neutrals ───────────────────────────────────┤
│ [White] [Light Gray] [Gray] [Dark Gray] [Black] [Brown] │
├─ Rich ───────────────────────────────────────┤
│ [Crimson] [Gold] [Forest] [Navy] [Indigo] [Coral] │
├─ Pastels ────────────────────────────────────┤
│ [Baby Blue] [Lavender] [Mint] [Peach] [Rose] [Butter] │
├─ Custom ─────────────────────────────────────┤
│ [Enter Hex Code...]                          │
└──────────────────────────────────────────────┘
```

Each color is a **dyed leather helmet** (the ONLY Minecraft item that supports
arbitrary RGB display — required for a functional color swatch). Display name =
color name. Lore = hex code + RGB values.

> **Royal Directive §2 note:** Leather helmets are standard armor, but they are
> the only item type in Minecraft that can visually display an arbitrary color.
> This is a forced exception. To comply with the spirit of §2, every color swatch
> item MUST have enchantment glint applied. All non-swatch items in this GUI
> (navigation buttons, frame slots, action buttons) MUST use legendary items:
> Echo Shards for borders, Nether Star for the "Save to Palette" action button,
> Enchanted Books for the "Import" button.

**Click behavior depends on context:**
- Opened from Color Studio → creates a color triangle/square tool with that color
- Opened from Bulk Recolor → sets the target recolor color
- Opened from any hex input prompt → fills in the hex value
- The library is a reusable component, not a standalone feature

**"Enter Hex Code" at the bottom:** Opens anvil input for power users who
DO know their hex codes. Both paths lead to the same result.

**Commands also accept color names:**
- `/cb triangle red` — creates a red triangle tool
- `/cb square "baby blue"` — quotes for multi-word names
- `/cb recolor coral marble` — recolor marble to coral
- Resolution: exact name → alias (from 1.3) → hex parse → error with suggestions

**Files:** New `ColorLibrary.java`, `GuiManager.java`, new `ColorPickerHelper.java` (**DOES NOT EXIST** — must be built from scratch),
`CustomBlockCommand.java`

---

### 3.2 Personal color palette (save your favorites)

**The problem:** You found the perfect shade of blue after experimenting.
Tomorrow you need it again and can't remember the hex code. Lost forever.

**The solution:** Per-player saved palette:
- **Shift-click** any color in the Color Library → save to personal palette
- Personal palette = top row of the Color Library (always visible, always first)
- `/cb palette add "My Blue" #2266BB` — save with a custom name
- `/cb palette remove "My Blue"` — delete a saved color
- `/cb palette list` — view all saved colors in chat with hex codes
- Max 18 saved colors (one full GUI row). If full: "§cPalette full (18/18).
  §7Remove a color with shift-click or /cb palette remove."
- Stored in `config/customblocks/palettes/<uuid>.json`
- Palette colors appear in command tab-completion for any color argument

**Recently used colors (automatic, no action needed):**
- Every color tool use automatically records the hex to a "recent" list
- Last 9 unique colors shown as the second row in the Color Library
- Fades old entries as new ones are added — no management needed

**Files:** New `PlayerPaletteManager.java`, `GuiManager.java`,
`CustomBlockCommand.java`

---

### 3.3 Dress Overlays in GUI

**The problem:** The 5 dress overlays (cracked, mossy, weathered, glowing,
frosted) are genuinely cool effects — but only accessible via `/cb dress
<id> <type>`. Most players never discover they exist. The effects are
invisible unless you memorize command syntax.

**The solution:** "Dress & Effects" button in the Block Editor:

```
┌─ Dress & Effects ────────────────────────────┐
│ [Original] ─→ [Preview]                      │
│                                               │
│ Overlays:                                     │
│ [Cracked] [Mossy] [Weathered] [Glowing] [Frosted] │
│                                               │
│ Strength:  [◀  Medium  ▶]                     │
│ (Low = subtle / Medium = balanced / High = heavy) │
│                                               │
│ [§a Apply & Create Variant]    [§c Cancel]     │
└──────────────────────────────────────────────┘
```

**Flow:**
1. Click an overlay → "Generating preview..." appears in the preview slot
2. Background thread generates the dressed texture (< 500ms)
3. Preview item shows the result — player sees BEFORE and AFTER side by side
4. Adjust strength with arrows (0.3 / 0.5 / 0.8 — scales overlay opacity)
5. "Apply" → creates `marble_dressed_cracked` as a new variant block
6. Original is untouched. Player receives the new variant item.

**Strength parameter:** `ColorVariantService.java` **DOES NOT EXIST** — confirmed by full-codebase grep; zero matches found. It must be created from scratch. When built, include a `float strength` parameter (0.0–1.0) from the start that scales overlay alpha proportionally. Low = barely visible crack lines. High = heavy weathering.

**Files:** `GuiManager.java`, new `ColorVariantService.java` (MUST BE CREATED FROM SCRATCH — contains the 5 dress overlay rendering methods + the `float strength` parameter)

---

### 3.4 Gradient Generator in GUI

**The problem:** `/cb gradient <from> <to> <steps>` creates beautiful color
gradients between two blocks — but it's command-only, you can't preview the
result, and you have to guess how many steps you want.

**The solution:** Visual gradient builder:

```
┌─ Gradient Generator ─────────────────────────┐
│ [Block A: marble_white]  ─→  [Block B: ???]  │
│                [Pick Block B]                 │
│                                               │
│ Steps: [◀  5  ▶]  (creates 5 intermediate)   │
│                                               │
│ Preview:                                      │
│ [A] [■] [■] [■] [■] [■] [B]                 │
│  ↑ live color preview as dyed leather items   │
│                                               │
│ [§a Create Gradient]  [§c Cancel]             │
│ §7Creates 5 new blocks in category            │
│ §7"Gradient: marble_white → marble_dark"      │
└──────────────────────────────────────────────┘
```

**The experience:**
1. Opens showing current block as "Block A"
2. Click "Pick Block B" → block picker opens → select destination color
3. Adjust steps with arrows (2–16). Preview row updates LIVE.
4. "Create" → generates intermediate blocks → auto-assigned to a category
5. All blocks created as one batch (single undo to revert all)

**Edge cases:**
- No texture on either block → "§cBoth blocks need textures for a gradient."
- Steps would exceed free slots → "§c5 free slots remaining. §7Reduce to 5
  steps or delete unused blocks."

**Files:** `GuiManager.java`, new `ColorVariantService.java` (**DOES NOT EXIST** — confirmed by full-codebase grep; must be built from scratch), `CustomBlockCommand.java`

---

### 3.5 Recolor preview (see before you commit)

**The problem:** Right-clicking with a color triangle immediately creates a
new variant. No preview. If the result looks bad, you've wasted a slot and
have to undo.

**The solution:** **Shift + right-click** = preview mode:

```
┌─ Recolor Preview ────────────────────────────┐
│                                               │
│    [Original]    →    [Preview]               │
│    marble_white       marble_red (preview)    │
│                                               │
│    [§a✔ Apply]       [§c✖ Cancel]            │
└──────────────────────────────────────────────┘
```

1. Shift+right-click runs the recolor in a background thread
2. Opens preview GUI showing original vs result side-by-side
3. "Apply" creates the variant (same as normal right-click)
4. "Cancel" discards — no slot wasted, no undo needed
5. Normal right-click (no shift) still applies immediately for speed

**Files:** `ColorTriangleItem.java`, `GuiManager.java`

---

### 3.6 Adjustable flood-fill tolerance

**The problem:** Background detection tolerance is hardcoded at 35. Some
textures need higher (gradient backgrounds), some need lower (foreground
similar to background). The player has no control.

**The solution:**
- Per-player tolerance setting: adjustable 10–80, default 35
- Set via Background Studio GUI slider (already has tolerance UI — wire it)
- Also via command: `/cb tolerance 50`
- Triangle reads player's setting instead of the constant
- Current tolerance shown in triangle item lore: "§7Tolerance: 35"
- Reset: `/cb tolerance reset` returns to 35

**Files:** `ColorTriangleItem.java`, `CustomBlocksConfig.java`, `GuiManager.java`

---

### 3.7 Smarter background detection

**The problem:** Triangle samples only the top-left pixel as "this is the
background color." Fails when the design touches the corner (logos, full
textures). The assumption "top-left = background" is wrong for 30%+ of
real textures.

**The solution:** Multi-point border sampling with consensus voting:

```
Sample points:
  [1]──[2]──[3]
   │          │
  [4]        [5]
   │          │
  [6]──[7]──[8]
(4 corners + 4 edge midpoints = 8 samples)
```

1. Sample 8 border points
2. Group by color similarity (within ±tolerance in RGB space)
3. Largest group > 4 of 8 = confident background color
4. Largest group 3–4 of 8 = uncertain, use but warn:
   "§eBackground detection uncertain. §7Result may need cleanup."
5. No group > 2 = can't detect, tell the player:
   "§cCan't auto-detect background. §7Use /cb bgpick to select manually."

**Manual override — `/cb bgpick`:**
- Activates "eyedropper" mode for the triangle tool
- Next right-click on any block samples THAT pixel as the background color
- Stored per-player, used for all future triangle ops until changed
- `/cb bgpick reset` returns to auto-detection

**Files:** `ColorTriangleItem.java`, `CustomBlockCommand.java`

---

### 3.8 Bulk recolor with full color support

**The problem:** `/cb bulkrecolor` only accepts the 3 built-in color names.
Can't bulk-apply hex colors or use the expanded color library.

**The solution:** Accept any color specification:
- `/cb bulkrecolor #FF5500 category:walls` — hex code
- `/cb bulkrecolor red category:walls` — color name
- `/cb bulkrecolor coral all` — alias name
- Resolution: ColorLibrary name → ColorNames alias → hex parse → error

In the bulk GUI, the "Bulk Recolor" button opens the Color Library as
the color picker (not just the 3 built-in options).

**Files:** `CustomBlockCommand.java`, `GuiManager.java`

---

## Phase 4 — Search & Discovery

*Players with 100+ blocks can't find anything. The search is basic text
matching with no filters. This phase turns search into a power tool that
makes large block collections manageable.*

### 4.1 Search overhaul — filters that actually filter

**The problem:** `/cb search` only matches name/ID text. You can't search by
property, category, or any other attribute. With 200+ blocks, finding "all
glowing animated blocks in the stone category" is impossible.

**The solution:** Property-aware search with composable filters:

| Filter | Example | What it matches |
|--------|---------|----------------|
| (text) | `marble` | Name or ID contains "marble" |
| `category:` | `category:stone` | Blocks in a specific category |
| `animated:` | `animated:yes` | Animated blocks only |
| `glow:` | `glow:>0` | Blocks with any light level |
| `hardness:` | `hardness:>1.5` | Blocks harder than 1.5 |
| `sound:` | `sound:wood` | Blocks with wood sound |
| `locked:` | `locked:yes` | Locked blocks |
| `favorite:` | `favorite:yes` | Favorited blocks |
| `created:` | `created:today` | Created in the last 24h |
| `has:variants` | `has:variants` | Blocks with color variants |
| `has:shape` | `has:shape` | Blocks with custom shapes |
| `has:faces` | `has:faces` | Blocks with per-face textures |
| `color:` | `color:red` | Blocks detected as red (via 1.1) |

**Combine freely:** `/cb search category:stone glow:>0 animated:yes`
matches animated glowing blocks in the stone category.

**Implementation:** `SearchFilter.java` parses `key:value` tokens, matches
against `SlotData` fields. Numeric comparisons (`>`, `<`, `=`) for glow and
hardness. Boolean for animated, locked, favorite. Date comparison for created.

**Files:** `CustomBlockCommand.java`, New `SearchIndex.java`, New `SearchFilter.java`

---

### 4.2 Search GUI with clickable filters

**The problem:** Even with powerful search syntax, not everyone wants to type
`category:stone glow:>0`. The GUI should let players click their way to results.

**The solution:**

```
┌─ Block Search ───────────────────────────────┐
│ [🔍 Search: ________]           [Clear All]  │
│ [Category ▼] [Animated ○] [Locked ○]         │
│ [Favorites ○] [Has Glow ○] [Has Shape ○]     │
│ [Sort: Name ▼]  [A→Z / Z→A]                  │
│──────────────────────────────────────────────│
│ [result1] [result2] [result3] [result4]      │
│ [result5] [result6] [result7] [result8]      │ ← 18 per page
│ [result9] ...                                │
│──────────────────────────────────────────────│
│ [◀ Prev]  Page 1/3 (23 results)  [Next ▶]   │
└──────────────────────────────────────────────┘
```

**Behaviors:**
- Clicking search bar opens anvil input; results filter as you type
- Toggle filters: click = enable (enchant glint), click again = disable
- Category dropdown: click cycles, shift-click opens category picker
- Sort options: Name, ID, Category, Created Date, Glow, Hardness
- Results update instantly on any filter/sort change
- Each result item shows: block icon + name + category badge in lore
- Left-click result = open block editor
- Right-click result = quick action popup (Give / Edit / Favorite / Delete)
- Shift-click result = give yourself 1 of that block

**Files:** `GuiManager.java`, New `SearchIndex.java`

---

### 4.3 Recent blocks & quick access

**The problem:** `MAX_RECENT = 3`. Three. After editing 4 blocks, the first
one is gone from recent history. There's also no quick way to jump back to
blocks you were just working on.

**The solution:**
- Increase `MAX_RECENT` to 10
- `/cb recent` → opens GUI showing last 10 edited blocks with timestamps
- Recent blocks row shown at the top of the main dashboard GUI
- Per-player tracking: `config/customblocks/recent/<uuid>.json`
- "Edited" = any mutation (create, retexture, rename, property change, etc.)
- Clicking a recent block opens its editor directly — one click, no navigation

**Files:** `GuiManager.java`, `CustomBlockCommand.java`, `SlotManager.java`

---

### 4.4 Find placed blocks in the world

**The problem:** "I placed marble_block somewhere in my build 3 days ago.
Where?" There's no way to find placed instances of a custom block.

**The solution:** `/cb find <id>` — world scanner:
- Scans loaded chunks for placed instances of the specified block
- Spawns glowing particle markers at each found location (30s duration)
- Shows count in chat: "Found 7 instances within 128 blocks"
- Each position is clickable text → teleports you there
- `--count` flag: just show count, no particles
- `--radius 50`: limit scan range (default: render distance)

**Performance:** Background thread, loaded chunks only, no disk I/O.
Progress bar for large scans (100+ chunks).

**Files:** `CustomBlockCommand.java`, new `BlockFinder.java`

---

## Phase 4A — Image Processing Overhaul

*This is why Mario looks terrible as a block texture. The current pipeline
forces every image through the same destructive process: kill all
transparency, fill with black, darken edges, flatten everything. No options,
no intelligence. This phase makes imported images actually look good.*

**Current pipeline (destructive, one-size-fits-all):**
```
download → PNG convert → pad to square (BLACK fill)
→ flood-fill background (WHITE only) → kill ALL transparency
→ flatten semi-transparent pixels (against BLACK) → resize
```

**Result:** Mario with transparent background → Mario on an ugly black
square with darkened edges, muddy colors, and eaten white details.

### 4A.1 Transparency is a choice, not a forced removal

**The problem:** Every texture is forced to 100% opaque with black background.
But Minecraft block textures CAN have transparency (glass, leaves, flowers).
Players who import a PNG with intentional transparency lose it entirely.

**The solution:** Background handling becomes a per-block setting:

| Mode | Behavior | Best for |
|------|----------|----------|
| **Keep transparent** | Preserve original alpha as-is | Glass, leaves, logos |
| **Remove auto** | Smart border detection + flood-fill | Photos, sprites with background |
| **Fill with color** | Replace transparent → chosen color | Custom background |
| **Fill black** | Current behavior (legacy) | Backwards compatibility |

- Default for new blocks: **Keep transparent**
- Stored in `SlotData.importBgMode` ⚠ This field does not exist in SlotData.java and must be added so retexture remembers the choice
- Configurable in import wizard (4A.6) and via command flag:
  `/cb create id name url --transparent` or `--bg #FF0000` or `--nobg`

**Files:** `ImageProcessor.java`, `SlotData.java`, `CustomBlocksConfig.java`

---

### 4A.2 Smart background detection

**The problem:** `isBackground()` only detects white and transparent pixels.
A blue-sky background behind Mario? Not detected. A gray studio background?
Not detected. Only white and transparent.

**The solution:** Border-pixel consensus:
1. Sample ALL pixels on the 4 borders (top row, bottom row, left col, right col)
2. Cluster by color similarity (within tolerance in RGB space)
3. Largest cluster > 60% of border → that's the background
4. Use that color as the flood-fill seed (not hardcoded white)
5. No dominant color → skip background removal, warn user

**Override flags:**
- `--bg #0000FF` → use this specific color as background
- `--nobg` → skip background removal entirely

**Files:** `ImageProcessor.java`, `CustomBlockCommand.java`

---

### 4A.3 Fix edge darkening

**The problem:** Semi-transparent pixels (anti-aliased edges) get composited
against BLACK: `out = src * (alpha/255)`. A 50% alpha red pixel becomes dark
brown. Every edge in every imported image is unnaturally dark.

**The solution:** Composite against the CHOSEN background color, not black:

```java
// OLD (against black):
r = (int)(srcR * a / 255.0);
// NEW (against chosen background):
r = (int)(srcR * a / 255.0 + bgR * (255 - a) / 255.0);
```

When preserving transparency (4A.1), don't flatten alpha at all — keep
semi-transparent pixels exactly as they are.

**Files:** `ImageProcessor.java` (replaceBackground method, starts at line 387)

---

### 4A.4 Anti-fringe is too aggressive

**The problem:** The fringe removal pass (stage 2) eats any pixel with
RGB >= 175 that's near the background boundary. On light images, this eats
actual content: Mario's white gloves, white text, light-colored details.

**The solution:** Reduce default and make configurable:

| Preset | FRINGE_TOLERANCE | Expand | Use case |
|--------|-----------------|--------|----------|
| Off | - | - | Pixel art, already clean edges |
| Light | 30 | none | Most images |
| Normal | 40 | 1px | **New default** |
| Aggressive | 80 | 1px | Photos with heavy fringing |

Exposed in import wizard (4A.6) and via `--fringe off|light|normal|aggressive`.

**Files:** `ImageProcessor.java` (FRINGE_TOLERANCE, isFringe)

---

### 4A.5 Padding uses transparency, not black bars

**The problem:** `padToSquare()` fills letterbox areas with opaque black.
Import a tall image → black bars on the sides visible in-game.

**The solution:** Fill with fully transparent pixels (`0x00000000`) instead
of opaque black. If the user chose a fill color (4A.1), use that for padding.

```java
// OLD:
g.setColor(Color.BLACK);
g.fillRect(0, 0, size, size);
// NEW:
g.setComposite(AlphaComposite.Clear);
g.fillRect(0, 0, size, size);
g.setComposite(AlphaComposite.SrcOver);
```

**Files:** `ImageProcessor.java` (padToSquare)

---

### 4A.6 Import wizard with live preview

**The problem:** `/cb create <id> <name> <url>` runs the full pipeline with
zero options and zero preview. You see the result only after it's done. If
it looks bad, you undo and try again with no way to change settings.

**The solution:** A visual import wizard that opens automatically:

```
┌─ Import Wizard ──────────────────────────────┐
│ [Original Image]          [Live Preview]      │
│                                               │
│ Background:                                   │
│ [Keep Trans.] [Remove Auto] [Fill Black]      │
│ [Fill White]  [Custom Color ▼]                │
│                                               │
│ Fringe:  [Off] [Light] [Normal] [Aggressive]  │
│ Size:    [64]  [128]  [256]                   │
│                                               │
│ [§a✔ Create Block]        [§c✖ Cancel]        │
└──────────────────────────────────────────────┘
```

**Flow:**
1. URL is downloaded → original image shown in left slot
2. Default settings applied → processed preview shown in right slot
3. Player clicks any option → "Processing..." in preview slot → new preview
   generated in background thread (< 1 second)
4. Player sees EXACTLY what the block will look like BEFORE committing
5. "Create" saves with chosen settings. "Cancel" discards everything.

**Quick mode for power users:**
- `/cb create id name url --quick` → use global defaults, skip wizard
- `/cb create id name url --transparent --fringe off --size 128` → CLI flags

**Files:** `ImageProcessor.java`, `GuiManager.java`, `CustomBlockCommand.java`

---

### 4A.7 Retexture remembers your settings

**The problem:** `/cb retexture <id> <url>` runs the same blind pipeline as
create. If you created a block with "keep transparent" and retexture it, the
new texture uses the default (which kills transparency). Your artistic
intent is lost.

**The solution:** Store import settings per-block in SlotData (these are NEW fields to ADD — none of these exist yet in SlotData.java):
```java
String importBgMode;   // ⚠ DOES NOT EXIST YET — must be added. "transparent", "remove_auto", "fill_black", etc.
String importFringe;   // ⚠ DOES NOT EXIST YET — must be added. "off", "light", "normal", "aggressive"
int importSize;        // 64, 128, 256
```

When retexturing, the wizard pre-populates with saved settings. The player
can adjust if needed, or just confirm to use the same config.

**Files:** `CustomBlockCommand.java`, `SlotData.java`, `GuiManager.java`

---

### 4A.8 Visual test suite (prove it before shipping)

**The problem:** Image processing changes are terrifying because you can't
see what broke. Fix edge darkening, break fringe removal. Fix fringe, break
padding. Invisible chain of breakage.

**The solution:** Before touching ImageProcessor, build a test harness:
- 15+ test images: transparent PNG, opaque PNG, photo, pixel art, colored
  background, gradient background, white background, dark image, thin
  details, tall/narrow, wide/short, anti-aliased edges, full-square,
  very small source (16px), very large source (4K)
- Run each through CURRENT pipeline → save output PNGs
- Run each through NEW pipeline → save output PNGs
- Side-by-side visual comparison — every single output reviewed
- **NO code ships until every test image looks correct**
- Test suite is permanent — runs on every ImageProcessor change going forward

**Files:** New `ImageProcessorTest.java`, test resources directory

---

### 4A.9 High-quality resize

**The problem:** Default Java image scaling. Fine for pixel art, lossy for
detailed images (photos, artwork with gradients).

**The solution:** Adaptive resize based on source size:
- Source < 64px → **nearest-neighbor** (preserves pixel art sharpness)
- Source >= 64px → **multi-step bicubic** (halve repeatedly, then final
  resize with `VALUE_INTERPOLATION_BICUBIC`). This is the standard
  technique for high-quality image downscaling.

**Files:** `ImageProcessor.java` (resizeTo method)

---

## Phase 4B — Animation, Shape & Face Tools

*These three features already exist and work — but they're rough, hard to
use, and missing visual feedback. This phase makes them feel like real
creative tools instead of config forms.*

### 4B.1 Animation editor overhaul

**The problem:** The animation GUI (`AnimBlockScreen`) lets you type FPS and
frame numbers. That's it. No preview, no scrubbing, no visual feedback. You
configure blindly, place the block, and hope it looks right.

**The solution:** A visual animation editor:

```
┌─ Animation Editor ───────────────────────────┐
│ [Preview: animated block cycling frames]      │
│                                               │
│ Frames:                                       │
│ [1✓] [2✓] [3✓] [4✓] [5✓] [6 ] [7 ] [8 ]   │
│  ↑ click to jump, current = enchant glint     │
│                                               │
│ [▶ Play] [⏸ Pause] [⏹ Stop]                  │
│                                               │
│ Speed: [Slow 2fps] [Normal 4fps] [Fast 8fps]  │
│        [Cinema 12fps] [Custom: __ fps]        │
│                                               │
│ Loop:  [Loop ↻]  [Ping-Pong ↔]               │
│ Range: [First: 1]  [Last: 5]                  │
│                                               │
│ [§b Test Block] [§a Save] [§c Cancel]         │
└──────────────────────────────────────────────┘
```

**Key features:**
- **Frame scrubber:** numbered items showing each frame's texture. Click to
  jump. Current frame has enchant glint.
- **In-GUI playback:** Preview item cycles through frames on a client-side
  timer. See the animation WITHOUT placing the block.
- **Speed presets:** one click instead of manual FPS entry
- **"Test Block" button:** spawns a temporary 10-second preview block at
  the player's feet. Walk around it, see it animate in-world, it auto-removes.

**Files:** `AnimBlockScreen.java`, `GuiManager.java`, `CustomBlockCommand.java`

---

### 4B.2 Shape editor with visual feedback

**The problem:** Configuring shapes via `/cb addshape id 0 0 0 8 16 8` is
blind coordinate entry. You can't see what you're building. You have to
place the block, walk around it, break it, adjust numbers, repeat.

**The solution:** Visual shape editor:

```
┌─ Shape Editor ───────────────────────────────┐
│ Top-down view (16×16 grid):                   │
│ ┌─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┐         │
│ │ │ │ │ │ │ │ │ │ │ │ │ │ │ │ │ │         │
│ │ │ │ │■│■│■│■│■│■│■│■│ │ │ │ │ │         │
│ │ │ │ │■│■│■│■│■│■│■│■│ │ │ │ │ │  ← box 1│
│ │ │ │ │ │ │ │ │ │ │ │ │ │ │ │ │ │         │
│ └─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┘         │
│                                               │
│ Boxes:                                        │
│ [Box 1: 3,0,3 → 12,16,12] [Delete]          │
│ [+ Add Box]                                   │
│                                               │
│ Presets: [Slab] [Stairs] [Fence] [Wall] [Pole]│
│                                               │
│ [§b Test Block] [§a Save] [§c Cancel]         │
└──────────────────────────────────────────────┘
```

**Key features:**
- **2D grid visualization** using a map item or colored stained glass panes
  representing the 16×16 top-down footprint
- **Preset picker:** click a preset (slab, stairs, etc.) to apply instantly.
  Each preset shows a mini description in lore.
- **Box list:** each box shows coordinates, click to highlight on grid,
  delete button per box
- **Test block:** temporary 10s preview block to check collision in-world
- `/cb shapelist` — lists all presets with descriptions in chat
- `/cb shapepreview <id>` — spawns temporary preview

**Files:** `GuiManager.java`, `SlotManager.java`, `CustomBlockCommand.java`

---

### 4B.3 Face editor with visual layout

**The problem:** Per-face textures (top, bottom, north, south, east, west)
are powerful but the GUI is minimal. Key workflows are missing entirely: you
can't copy a face, can't import a face from URL easily, can't see all 6
faces at once.

**The solution:** Visual face editor with unfolded cube layout:

```
┌─ Face Editor ────────────────────────────────┐
│              [Top]                             │
│     [West]  [North]  [East]  [South]          │
│             [Bottom]                          │
│                                               │
│ Selected: North                               │
│ [Import URL]  [Copy From...]  [Rotate 90°]   │
│ [Clear Face]  [Same All Sides]                │
│                                               │
│ [Clone All From Block...]                     │
│ [§a Save] [§c Cancel]                         │
└──────────────────────────────────────────────┘
```

**Key features:**
- **Unfolded cube:** 6 slots arranged like a cube net. Each shows its face
  texture. Empty faces = gray "No texture" placeholder.
- **Click a face** to select it → action buttons apply to selected face
- **Copy From:** click source face, then target face — copies texture
- **Import URL:** anvil input → downloads → applies to just that face
- **Rotate 90°:** rotate face texture without re-importing
- **Same All Sides:** copies main texture to all 6 faces at once
- **Clone All From Block:** opens block picker → copies all 6 faces from
  another block. Useful for making a variant with the same face layout.

**Files:** `GuiManager.java`, `SlotData.java`, `CustomBlockCommand.java`

---

## Phase 4C — Showcase Display Block

*A placeable block that cycles through your custom blocks like a museum
display. Place it, configure it, and it automatically shows off your
collection. Full creative control over every aspect.*

### 4C.1 The block

- Registered as a special block type in the game
- Renders a floating, slowly spinning custom block above it (invisible
  ArmorStand with block on head — same technique as holograms)
- Auto-switches to next block on a configurable timer
- Name label hologram shows current block's display name
- Subtle particle ring around the base marks it as a showcase
- Breaking the showcase cleanly removes ArmorStand + hologram

### 4C.2 Source selection (what to display)

Configurable per showcase block:

| Source | Description |
|--------|------------|
| All blocks | Every custom block you have |
| Category | Only blocks from a specific category |
| Manual pick | Hand-pick exactly which blocks (block picker GUI) |
| Favorites | Only starred blocks |
| Search filter | Scope expression (e.g., `animated:yes glow:>0`) |

### 4C.3 Appearance (how it looks)

| Setting | Options |
|---------|---------|
| Speed | Slow (5s), Normal (2s), Fast (0.5s), Custom interval |
| Order | Sequential, Random, Shuffle (random no repeats) |
| Spinning | On/Off + spin speed (slow/normal/fast) |
| Name label | Show/Hide + label color (any formatting code) |
| Particles | On/Off + type (end rod, enchant, flame, heart) |
| Hover height | 0.5 – 3.0 blocks above base |

### 4C.4 Interaction

- **Right-click** → open settings GUI
- **Shift+right-click** → pause/resume cycling
- **While paused, right-click** → give yourself 1 of the displayed block
- **Redstone signal** → pause (wire to lever for external control)

### 4C.5 Commands

```
/cb showcase place                          — get a showcase block item
/cb showcase config <x> <y> <z>             — open settings for a placed one
/cb showcase set <x> <y> <z> speed 3.0      — set speed remotely
/cb showcase set <x> <y> <z> source category:stone
/cb showcase set <x> <y> <z> order random
/cb showcase list                           — list all active showcases
```

### 4C.6 Settings GUI

```
┌─ Showcase Settings ──────────────────────────┐
│ Source: [All Blocks ▼]  [Pick Category]       │
│         [Manual Select]                       │
│                                               │
│ Speed:  [◀  2.0s  ▶]    Order: [Sequential ▼]│
│ Spin:   [ON]             Label: [ON]          │
│ Particles: [ON]          Height: [◀ 1.5 ▶]   │
│ Label Color: [White ▼]                        │
│                                               │
│ [Pause/Resume]  [Preview]  [Reset Defaults]   │
└──────────────────────────────────────────────┘
```

### 4C.7 Hologram integration

- Showcase uses the hologram system for the name label
- Text updates automatically as blocks cycle
- Can override hologram color/height per-showcase
- Works even if global holograms are disabled

### 4C.8 Fix the hologram system

**The problem:** Holograms are disabled by default (`hologramEnabled = false`).
Even when enabled, they may not work correctly — needs investigation.

**Fix checklist:**
- [ ] Test: enable in config → place block → verify ArmorStand spawns
- [ ] Verify `SlotBlock.postPlacement` calls `HologramManager.onBlockPlaced`
- [ ] Verify block break cleans up ArmorStands
- [ ] Add `/cb hologram on|off` toggle (no config file editing)
- [ ] Add hologram toggle in config GUI (clickable button, instant apply)

**Files:** New `ShowcaseBlock.java`, New `ShowcaseBlockEntity.java`,
`GuiManager.java`, `CustomBlockCommand.java`, New `HologramManager.java`

---

## Phase 5 — User Experience

*This is the heart of v3. Not new features — refinement. The difference
between a mod that works and a mod that feels good. Every item here is
about removing friction, adding feedback, and making the player feel like
the mod anticipates their needs.*

### 5.1 Tooltips on every GUI button

**The problem:** Many GUI buttons are just a colored item with a short name.
Hover → still no idea what it does or what clicking will do.

**The rule:** Every button in every GUI must have:
- **Line 1:** What it does (plain language, one sentence)
- **Line 2:** Click behavior ("Left-click: apply / Right-click: preview")
- **Line 3 (if applicable):** Current value ("Current: stone")

**Scope:** Every `inv.setStack()` call in `GuiManager.java`. Estimated: 200+ buttons.

**Files:** `GuiManager.java`

---

### 5.2 Sound preview before applying

**The problem:** You set a block's sound type, place the block, break it,
and only THEN find out if the sound is right. Pick wrong → undo → try again.

**The solution:**
- **Right-click** a sound option = play a preview at the player's position
  (`world.playSound` with the "break" variant — most distinctive)
- **Left-click** = apply it
- Currently active sound has enchantment glint

**The experience:** Player browses the sound GUI. Right-clicks "glass" → hears
glass breaking. Right-clicks "wood" → hears wood breaking. Left-clicks
"wood" → applied. Done. No blind guessing.

**Files:** `GuiManager.java` (sound picker)

---

### 5.3 Quick actions everywhere

**The problem:** To give yourself 1 block: open dashboard → find block →
click it → editor opens → find "Give" button → click it. That's 4-5 clicks
for the most common action.

**The solution:** In ANY block list/grid anywhere in the mod:
- **Shift-click** = give yourself 1 instantly (no GUI opens)
- **Right-click** = quick action popup:

```
[Give 1x]  [Edit]  [★ Favorite]  [Rename]  [Delete]
```

5 slots, one click, done. Works in dashboard, search results, category
views, bulk selection — everywhere you see a block item.

**Files:** `GuiManager.java`

---

### 5.4 Error messages that tell you what to DO

**The problem:** "variant not found." What now? The player is stuck. Every
error should answer the question "okay, what do I do about it?"

**The pattern:** `"§c[Problem.] §7[Solution.]"`

| Current | Improved |
|---------|----------|
| "variant not found" | "§cVariant not found. §7Create it with a color triangle, or /cb dress." |
| "block is locked" | "§cBlock is locked. §7/cb unlock \<id\> to edit it." |
| "no texture" | "§cNo texture on this block. §7/cb retexture \<id\> \<url\>" |
| "not configured" | "§cColor tools not set up. §7Run /cb config to configure." |
| "no free slots" | "§cNo free slots (600/600). §7Delete unused blocks or increase maxSlots." |
| "rate limited" | "§cShare rate limited. §7Try again in 60 seconds." |

**Scope:** Every `ChatHelper.error()` call in the entire codebase. Grep and audit.

**Files:** All files that produce error messages

---

### 5.5 Remember last-used settings per-player

**The problem:** Open Background Studio → set tolerance to 50 → close → open
again → tolerance is back to 35. Every GUI resets every time. Players waste
time re-configuring.

**What to remember:**
- Last tolerance in Background Studio
- Last tint in Color Studio
- Last sort order in search
- Last category filter
- Last import settings (bg mode, fringe, size)
- Last bulk operation scope expression

**Storage:** `DraftManager.java` **DOES NOT EXIST** — it must be created from scratch (confirmed by full-codebase grep; zero matches found). When built, it will handle session-only ESC drafts for GUI resume. This plan item should create DraftManager for session drafts OR a separate `PrefsManager.java` specifically for persistent user preferences.

**Files:** New `DraftManager.java` (MUST BE CREATED FROM SCRATCH) or new `PrefsManager.java` for the persistent preferences use case, `GuiManager.java`

---

### 5.6 Progress feedback for async operations

**The problem:** Click "Generate Palette" → GUI goes blank → 2 seconds of
nothing → results appear. Was it working? Did it crash? No feedback.

**The rule:** Anything that takes > 500ms shows progress:

| Operation | Feedback |
|-----------|----------|
| Palette generation | "Generating palette..." item in GUI (spinning compass) |
| Texture download | "§7Downloading..." in chat immediately |
| Bulk operations (5+) | Boss bar: "Processing 3/12..." |
| Import wizard preview | "Processing..." item while recomputing |
| Block creation | "§7Creating block..." in chat immediately |
| Gradient generation | Boss bar for multi-step |

**Completion sounds:**
- Creation → level-up sound
- Deletion → anvil break sound
- Modification → note block pling
- Bulk complete → experience orb sound

**Files:** `GuiManager.java`, new `FeedbackHelper.java` (MUST BE CREATED FROM SCRATCH — confirmed absent by full-codebase grep), `CustomBlockCommand.java`

---

### 5.7 Satisfying feedback on every action

**The problem:** Create a block → text appears in chat. Delete a block → text
appears in chat. Everything feels the same. Nothing feels special.

**Feedback rules (universal, applied everywhere):**

| Action | Particles | Sound | Display |
|--------|-----------|-------|---------|
| Create/duplicate | Green sparkles | Level-up | Actionbar: "§aCreated: name" |
| Delete | Red dust | Anvil break | Actionbar: "§cDeleted: name" |
| Retexture/recolor | Purple | Note pling | Actionbar: "§dUpdated: name" |
| Bulk complete | Firework | Experience orb | Title flash + chat summary |
| Favorite | Yellow stars | Click | Actionbar: "§e★ Favorited: name" |
| Share | Gold sparkles | Level-up | Title: "Block Shared!" |
| Achievement | Mixed | Totem | Title + subtitle |
| Error | None | None | Red chat with solution |

**Implementation rule:** Grep for all state-modifying code paths. Every single
one gets appropriate feedback. No silent mutations.

**Files:** new `FeedbackHelper.java` (MUST BE CREATED FROM SCRATCH — confirmed absent by full-codebase grep), `GuiManager.java`, `CustomBlockCommand.java`

---

### 5.8 Contextual tips in GUIs

**The problem:** Players don't know about powerful features because nothing
tells them. Shift-click for quick give? Nobody knows. Scope expressions?
Nobody knows.

**The solution:** Subtle, rotating tips in GUI footers:

```
§8Tip: Shift-click any block to give yourself one instantly
§8Tip: Use /cb bulkgui for batch operations on multiple blocks
§8Tip: Right-click a sound option to preview it before applying
§8Tip: Type color names instead of hex codes — "red", "coral", "navy"
§8Tip: /cb recent shows your last 10 edited blocks
§8Tip: /cb find <id> highlights placed blocks in the world with particles
```

- Pool of 20+ tips in `TipPool.java`
- Each GUI open picks one random tip (different from last shown)
- Shown as gray lore on a Paper item in the bottom-right corner
  (Royal Directive §2: never use Glass for GUI items — Paper fits the "tip/note" metaphor)
- Non-intrusive — easily ignored, occasionally life-changing

**Files:** `GuiManager.java`, new `TipPool.java`

---

### 5.9 Related blocks in the editor

**The problem:** Editing `marble_white` — you also have `marble_black`,
`marble_red`, and `marble_gray`. But there's no way to navigate between
variants without going back to the dashboard and finding each one.

**The solution:** Bottom row of the Block Editor shows up to 7 related blocks:

**Relation priority (checked in order):**
1. **Variants** — blocks sharing the same base ID prefix (`marble_*`)
2. **Same category** — other blocks in the same category
3. **Similar name** — Levenshtein distance ≤ 3 on the ID

Click a related block → opens its editor. One click to navigate between
variants. Makes color families feel connected.

**Files:** `GuiManager.java` (editor builder)

---

### 5.10 Category improvements

**What's missing:**

| Feature | What it adds |
|---------|-------------|
| Block count badge | "(42 blocks)" in category lore |
| Quick stats | "§712 animated §8\| §75 glowing §8\| §73 locked" in lore |
| Reorder | [↑] and [↓] arrows to swap category positions |
| Category icon | First block's texture as the category display icon |

**Files:** `GuiManager.java`, new `CategoryManager.java` (MUST BE CREATED FROM SCRATCH — confirmed absent from codebase by full-codebase grep)

---

### 5.11 Undo shows what happened

**The problem:** Undo history entries are anonymous — you don't know what
you're undoing until you click it.

**The solution:** Every undo entry includes a description and timestamp:

```
§fCreated marble_block §8— §72 min ago
§fDeleted test_stone §8— §715 min ago
§fBulk renamed 12 blocks §8— §71 hour ago
§fRetextured brick_wall §8— §7yesterday
```

**Implementation:** `UndoManager` already has a `description` field. Ensure
every push includes a meaningful description: `create <id>`, `delete <id>`,
`retexture <id>`, `bulk_delete 5 blocks`, `rename old → new`.

**Files:** `GuiManager.java` (undo picker), `UndoManager.java`

---

### 5.12 Better delete confirmation

**The problem:** Delete requires clicking twice within 5 seconds with no
visual warning. Easy to misclick. Easy to miss the timeout.

**The solution:** After first click, the button transforms:
- Item → RED_STAINED_GLASS_PANE
- Name → "§c§l⚠ CLICK AGAIN TO DELETE"
- Lore → "§7This will permanently delete this block. Expires in 10s."
- Sound → warning bass note
- Timeout → 10 seconds (was 5)
- Second click → delete with red particles + confirmation message

**Files:** `GuiManager.java`

---

### 5.13 GUI access for every command-only feature

**The problem:** These features exist ONLY as commands with no visual path:

| Feature | Command | Needs GUI in |
|---------|---------|-------------|
| Dress overlays | `/cb dress` | Block Editor (covered in 3.3) |
| Gradients | `/cb gradient` | Color Studio (covered in 3.4) |
| Custom triangle | `/cb customtriangle #hex` | Color Library (3.1) |
| Export block | `/cb exportblock` | Block Editor |
| Import block | `/cb importblock` | Main dashboard |
| Find in world | `/cb find` | Search GUI |
| Templates | `/cb template` | Block Editor + create flow |
| Compare | `/cb compare` | Block picker |
| Hologram toggle | `/cb hologram` | Config GUI (4C.8) |
| Tolerance | `/cb tolerance` | Background Studio (3.6) |
| Palette | `/cb palette` | Color Library (3.2) |

**Rule:** Every feature must be reachable by clicking through GUIs alone.
Commands are power-user shortcuts, not the only path.

**Files:** `GuiManager.java`

---

### 5.14 Scope expressions documented and discoverable

**The problem:** `category:plants` filter syntax is powerful but invisible.
Nobody knows it exists unless they read source code.

**The solution:**
- `/cb help scopes` — dedicated help page with examples and syntax
- Every bulk command's usage error includes one scope example
- Bulk GUI shows `§8(syntax: category:name, name:marble*, all)` as hint text
- Scope examples in the Help GUI / tutorial section

**Files:** `CustomBlockCommand.java`, New `HelpRegistry.java`

---

### 5.15 Block templates / presets

**The problem:** Making a set of 20 stone blocks — each needs the same
sound (stone), hardness (2.0), shape (full), and collision (on). Set those
4 properties 20 times = 80 clicks.

**The solution:** Save property presets as templates:
- `/cb template save "stone_style" marble_block` — saves all properties
- `/cb template apply "stone_style" new_block url` — creates with saved props
- `/cb template list` — shows all templates
- `/cb template delete "stone_style"`
- Template picker in create flow: click a template → auto-applies properties
- Templates are server-wide, stored in `config/customblocks/templates.json`

**What's saved:** sound, glow, hardness, shape, collision, import settings

**Files:** New `TemplateManager.java`, `CustomBlockCommand.java`, `GuiManager.java`

---

### 5.16 Macro system GUI

> **⚠️ CORRECTION:** `MacroManager.java` **DOES NOT EXIST** — confirmed by full-codebase grep; zero matches found. The "Already implemented" claim was false. This plan item must build MacroManager from scratch, including all recording logic, persistence, and the GUI described below.

**The problem:** The macro system has never been built. This plan item creates `MacroManager.java` from scratch and builds its GUI.

**The solution:** Macro Manager GUI from the Feature Menu:

```
┌─ Macro Manager ──────────────────────────────┐
│ [§a Record New]  [Import Macro]               │
│                                               │
│ my_stone_setup (7 commands) — used 2h ago     │
│   [▶ Run]  [✎ Edit]  [✖ Delete]  [↗ Export]  │
│                                               │
│ quick_slab (3 commands) — used yesterday      │
│   [▶ Run]  [✎ Edit]  [✖ Delete]  [↗ Export]  │
│                                               │
│ §8Tip: Assign a macro to a paper item for     │
│ §8one-click execution from your hotbar        │
└──────────────────────────────────────────────┘
```

**Key features:**
- **Visual recorder:** click "Record" → boss bar appears "Recording:
  \<name\>". Run commands. `/cb macro stop` to finish.
- **Edit mode:** reorder steps with arrows, delete steps, add new ones
- **Hotbar macro item:** `/cb macro bind <name>` gives you a named paper
  item. Right-click = execute the macro. Perfect for repetitive workflows.
- **Export/Import:** share macros via share codes

**Files:** New `MacroManager.java` (MUST BE CREATED FROM SCRATCH), `GuiManager.java`, `CustomBlockCommand.java`

---

### 5.17 Placement stats dashboard

> **⚠️ CORRECTION:** `PlacementStats.java` **DOES NOT EXIST** — confirmed by full-codebase grep; zero matches found. The "Already implemented" claim was false. This plan item must build PlacementStats from scratch, including all tracking logic, persistence, and the GUI described below.

**The problem:** Placement stats have never been tracked. This plan item creates `PlacementStats.java` from scratch and builds its GUI.

**The solution:** `/cb stats` command and GUI:

```
┌─ Block Stats ────────────────────────────────┐
│ [Total: 247 blocks]  [Placed: 1,842 times]   │
│ [Active Players: 5]  [Categories: 12]         │
│                                              │
│ §eMost Popular Blocks:                        │
│ 1. marble_white    — 342 placements           │
│ 2. oak_custom      — 218 placements           │
│ 3. stone_brick_v2  — 156 placements           │
│ 4. ...                                        │
└──────────────────────────────────────────────┘
```

**Also in Block Editor lore:**
- "Placed 42 times by 3 players"
- "Last placed: 2 hours ago"
- "Most placed by: PlayerName (28 times)"

Makes blocks feel alive. Players care about their most popular creations.

**Files:** New `PlacementStats.java` (MUST BE CREATED FROM SCRATCH), `GuiManager.java`, `CustomBlockCommand.java`

---

### 5.18 Block notes in the editor

> **⚠️ CORRECTION:** `BlockNotesManager.java` **DOES NOT EXIST** — confirmed by full-codebase grep; zero matches found. The "Already implemented" claim was false. The `/cb note` commands are NOT registered (class doesn't exist). This plan item must build BlockNotesManager from scratch, register the commands, and build the GUI integration described below.

**The problem:** Block notes have never been implemented. This plan item creates `BlockNotesManager.java` from scratch, registers all commands, and builds the in-editor GUI experience.

**The solution:**
- Block Editor shows a book-and-quill item in the info section
- If note exists: lore shows the note text, click to edit (anvil input)
- If no note: "§7Add a note..." placeholder, click to create
- Notes visible in block list tooltips (first 40 chars + "...")
- Notes searchable: `/cb search note:marble`
- `/cb note <id>` (no text) = view current note
- `/cb note <id> clear` = remove note

**Files:** New `BlockNotesManager.java`, `GuiManager.java`, `CustomBlockCommand.java`

---

### 5.19 Auto-categorization rules GUI

> **⚠️ CORRECTION:** `AutoCategorizeManager.java` **DOES NOT EXIST** — confirmed by full-codebase grep; zero matches found. It is also NOT called from `SlotManager.assign()` at line 321 (that line was verified and contains no such call). The "Already implemented" claim was entirely false. This plan item must build AutoCategorizeManager from scratch.

**The problem:** Auto-categorization has never been implemented. This plan item creates `AutoCategorizeManager.java` from scratch, wires it into `SlotManager.assign()`, and builds the GUI described below.

**The solution:** Visual rules editor from category management:

```
┌─ Auto-Categorize Rules ──────────────────────┐
│ [§a + Add Rule]                               │
│                                               │
│ Rule 1: "stone" → Stone category              │
│   §7Priority: 1 | Enabled: Yes | Matched: 23  │
│   [Edit] [Toggle] [Delete]                    │
│                                               │
│ Rule 2: "wood" → Wood category                │
│   §7Priority: 2 | Enabled: Yes | Matched: 15  │
│   [Edit] [Toggle] [Delete]                    │
│                                               │
│ [§e Test Rules] §7— dry run against all blocks │
└──────────────────────────────────────────────┘
```

- Rules fire on create AND import
- "Test Rules" button: shows what WOULD be categorized without doing it
- Message on match: "§7Auto-categorized §fmarble_new §7→ §fStone"

**Files:** New `AutoCategorizeManager.java` (MUST BE CREATED FROM SCRATCH), `GuiManager.java`, `CustomBlockCommand.java`

---

### 5.20 Config GUI that actually works

**The problem:** 40+ settings in `customblocks.json`. The GUI shows a few
of them. Most require editing a JSON file and restarting the server. No
player will ever do this.

**The solution:** Full config GUI with every setting:

```
Page 1 — General:
  [Max Slots: ◀ 600 ▶]  [Texture Size: ◀ 128 ▶]
  [Sounds: ON]  [Particles: ON]  [Holograms: OFF]
  [Did-You-Mean: Smart ▼]  [Voice: Friendly ▼]

Page 2 — Network:
  [RP Port: ◀ 8080 ▶]  [Reload Debounce: ◀ 2000ms ▶]
  [Payloads/Tick: ◀ 4 ▶]
  §8Note: Join sync is now count-verified signal-driven (item 1.20) — no timer field.

Page 3 — Safety:
  [Auto Snapshot: ◀ 30 min ▶]  [Bulk Confirm: ◀ 10 ▶]

Page 4 — Permissions:
  [Admin OP: ◀ 4 ▶]  [Use OP: ◀ 0 ▶]  ...per-node levels
```

**Every setting:** clear label, current value shown, click to change, instant
apply (no restart), auto-saves to config file. Settings that DO need restart
show "§c(restart required)" in lore. "Reset to Default" per page.

**Files:** `GuiManager.java`, `CustomBlocksConfig.java`

---

### 5.21 Share & import experience

**The problem:** Sharing works, but it feels like nothing happened. Importing
with ID conflicts is confusing — what got overwritten?

**Sharing improvements:**
- Gold particles + level-up sound + title "Block Shared!"
- Share code appears as a clickable chat message (click = copy to clipboard)
- `/cb shares` — your share history with codes for re-sharing

**Import improvements:**
- Preview before import: see the block + properties + name
- Conflict resolution GUI (when ID already exists):
  ```
  [Keep Existing]  [Overwrite]  [Import as Copy (new ID)]
  ```
  Side-by-side comparison: your block vs incoming block
- Bulk import: paste multiple codes or import from ZIP

**Files:** `GuiManager.java`, `CustomBlockCommand.java`, new `DraftManager.java` (MUST BE CREATED FROM SCRATCH — confirmed absent from codebase by full-codebase grep)

---

### 5.22 Snapshot recovery with context

> **⚠️ CORRECTION:** `SnapshotManager.java` **DOES NOT EXIST** — confirmed by full-codebase grep; zero matches found. The commands `/cb snapshot`, `/cb snapshot list`, `/cb snapshot restore` are NOT registered. The "Already implemented" claim was entirely false. This plan item must build SnapshotManager from scratch, register all commands, and build the full GUI described below.

**The problem:** The snapshot system has never been built. This plan item creates `SnapshotManager.java` from scratch, registers all commands, and builds the diff GUI experience described below.

**The solution:** Informative recovery GUI:

```
┌─ Restore from Snapshot ──────────────────────┐
│                                               │
│ [Clock] "2 hours ago (Auto)"                  │
│   §7Contains 247 blocks, 12 categories        │
│   §eDiff vs current: +3 new, -1 removed,      │
│   §e~5 modified                                │
│   §7Left-click: view diff  §7Right-click: restore │
│                                               │
│ [Clock] "5 min ago (Pre-bulk-delete)"          │
│   §7Contains 250 blocks, 12 categories        │
│   §eDiff vs current: +4 new, ~0 modified       │
│   ...                                         │
└──────────────────────────────────────────────┘
```

**Diff calculation:** Compare snapshot slots vs current slots → show
added/removed/modified counts. Click "view diff" to see actual block names.

**Safety:** Restore requires a second confirmation click: "§c§lThis will
replace ALL current blocks with this snapshot. Click again to confirm."

**Also:**
- `/cb snapshot create "before big refactor"` — manual labeled snapshot
- Panic mode: boss bar countdown + title display (more visible than chat)

**Files:** New `SnapshotManager.java` (MUST BE CREATED FROM SCRATCH), `GuiManager.java`, `CustomBlockCommand.java`

---

### 5.23 Unified Color Hub

**The problem:** Color Studio, Palette Generator, AI Smart Suggest, Dress
Overlays, Gradients, Background Studio — 6 color tools scattered across
commands and GUIs. A player who wants to "change a block's color" has to
know which of 6 tools is the right one.

**The solution:** One "Color Hub" that links to everything:

```
┌─ Color Hub ──────────────────────────────────┐
│ §fChoose a color tool:                        │
│                                               │
│ [Color Studio]     §77 tint variations        │
│ [Palette Generator] §716 hue variants         │
│ [AI Smart Suggest]  §718 intelligent presets   │
│ [Gradient Creator]  §7Blend between 2 blocks   │
│ [Dress Effects]     §7Cracked, mossy, etc.     │
│ [Background Studio] §7Background removal tools │
│ [Color Library]     §730+ named colors         │
│ [My Palette]        §7Your saved colors        │
│ [Custom Color Tool] §7Enter a hex code         │
└──────────────────────────────────────────────┘
```

Accessible from: Block Editor, Magic Items, Feature Menu, `/cb colorhub`

**AI Smart Suggest upgrade:** Show BEFORE (slot 11) / AFTER (slot 15) with
arrow between them. Let the player see the result BEFORE applying.

**Palette Generator upgrade:** Let player select which of the 16 generated
colors to keep (toggle each one) instead of auto-picking 7.

**Recently used colors:** automatically tracked, shown as first row in any
color picker (max 9 recent, auto-managed).

**Files:** `GuiManager.java`, new `ColorPickerHelper.java` (**DOES NOT EXIST** — must be built from scratch), new `ColorVariantService.java` (**DOES NOT EXIST** — confirmed by full-codebase grep)

---

### 5.X Rich Command Output — Clickable Links, Copy Buttons, and Cloud Upload

**The problem:** Every command that creates a file or outputs a URL sends
plain unformatted text. The player sees something like:

```
[CB] Diagnostics bundle created: config/customblocks/diagnostics/cb-..zip ✔
```

That path is unclickable, uncopiable text. On MCServerHost, getting to
that file requires opening the file manager, navigating three folders, and
manually downloading. Share links and pack URLs suffer the same problem —
some are clickable, some aren't, and none have a consistent "copy" option.

Current state of output formatting:
- `/cb export` (`cmdExport` at line 2573) → plain text with file path
- `/cb share` (block) → already has COPY_TO_CLIPBOARD ✓
- `/cb share` (category cloud) → already has COPY_TO_CLIPBOARD ✓
- Resource pack URL in GUI → already has OPEN_URL ✓
- Everything else → plain text

Note: `/cb diagnostics`, `/cb export png`, `/cb export category`, and
`/cb export all` are NEW commands that will be created as part of this
feature — they do not exist yet.

**The solution:** A complete rich output system with three components:

---

**Component 1 — File output format**

Every command that writes a file sends a two-part message:

```
[CB] Diagnostics bundle created: ✔
     config/customblocks/diagnostics/cb-diagnostics-20260515-134942.zip
     [📋 Copy path]  [☁ Upload ↑]
```

- **Path line** — full path, plain text (still readable without interaction)
- **`[📋 Copy path]`** — `COPY_TO_CLIPBOARD` click event, copies the full path
  silently. Hover text: "Click to copy the file path"
- **`[☁ Upload ↑]`** — Runs `/cb upload <filepath>` which uploads the file
  to Cloud Vault and replies with a download link (see Component 3)

After clicking `[☁ Upload ↑]`:
```
[CB] Uploading cb-diagnostics-20260515-134942.zip...
[CB] Download ready: ✔
     [⬇ Click to download]  [📋 Copy link]
```

Applied to these commands (new commands to be created in `CustomBlockCommand.java`):
- `cmdDiagnostics` — new `/cb diagnostics` command: ZIP path + copy + upload
- `cmdExportPng` — new `/cb export png <id>` command: PNG path + copy + upload
- `cmdExportCategory` — new `/cb export category <name>` command: JSON path + copy + upload (full path, not just filename)
- `cmdExportAll` — new `/cb export all` command: JSON path + copy + upload
- `cmdExport` (line 2573) — existing `/cb export` command: upgrade to use `fileOutput()` instead of plain text

---

**Component 2 — URL output format**

Every URL sent by any command uses this consistent layout:

```
[CB] Share link ready:
     https://cb-cloud-vault.cbbblocksvault.workers.dev/share/AbC123
     [🔗 Open in browser]  [📋 Copy link]
```

- **URL text** — shown in full (readable even without clicking)
- **`[🔗 Open in browser]`** — `OPEN_URL` click event
- **`[📋 Copy link]`** — `COPY_TO_CLIPBOARD` click event, copies the raw URL

Applied to:
- Cloud share success messages (already have copy but no open-in-browser)
- Resource pack URL display (already has open-in-browser but no copy)
- Any future Cloud Vault download links

---

**Component 3 — Cloud Vault file upload endpoint**

New endpoints on the Cloud Vault Cloudflare Worker:

```
POST /files
  Header: Authorization: Bearer <UPLOAD_SECRET>
  Body: binary file data
  Metadata: filename, upload timestamp
  Returns: { "id": "abc123", "url": "https://...workers.dev/files/abc123" }

GET /files/:id
  Returns: file bytes with Content-Disposition: attachment; filename="..."
  (Forces browser download instead of rendering in browser tab)
```

Storage: Cloudflare KV (`env.BLOCKS` namespace) with key `file:{id}`.
No auto-expiry — files persist until manually deleted (by design, matches
user preference).

Upload secret: `UPLOAD_SECRET` environment variable in the Cloudflare
Worker, same pattern as existing pack upload secret. On the mod side:
`config.cloudPackSecret` ⚠ This field does not exist yet and must be added to CustomBlocksConfig.java (already used for pack upload).

File size limit: Cloudflare KV supports 25 MB values. Diagnostics ZIPs
are typically 5–50 KB. PNG exports are typically 2–50 KB. All well under
the limit.

---

**Component 4 — New ChatHelper methods**

New static methods added to `ChatHelper.java`:

```java
// File output: path text + [Copy path] + [Upload ↑] buttons
public static void fileOutput(ServerCommandSource src, String label,
                               java.nio.file.Path filePath) { ... }

// URL output: URL text + [Open in browser] + [Copy link] buttons  
public static void urlOutput(ServerCommandSource src, String label,
                              String url) { ... }
```

These replace the current `ChatHelper.success(src, "... " + path)` calls
at the affected command sites.

---

**The experience (end to end):**

1. Player runs `/cb diagnostics`
2. Sees path + `[📋 Copy path]` + `[☁ Upload ↑]`
3. Clicks `[☁ Upload ↑]` → "Uploading..."
4. Sees `[⬇ Click to download]` + `[📋 Copy link]`
5. Clicks `[⬇ Click to download]` → browser opens, ZIP downloads immediately

Or, if on a home server without Cloud Vault:
1. Runs `/cb diagnostics`
2. Sees path + `[📋 Copy path]`
3. Clicks `[📋 Copy path]` → path in clipboard
4. Pastes into file manager search

**Edge cases:**
- Upload fails (Cloud Vault down / rate limited) → show error: "Upload
  failed — try again or use [📋 Copy path] to find it manually"
- Upload secret not configured → hide `[☁ Upload ↑]` button silently
  (only show if Cloud Vault is enabled in config)
- File was deleted before upload → "File not found. Try running the
  command again."
- Player offline when upload completes (async) → message sent when they
  reconnect (queue via `ServerPlayerEntity.sendMessage`)

**Files:**
- `ChatHelper.java` — add `fileOutput()` and `urlOutput()` static methods
- `CustomBlockCommand.java` — create new `cmdDiagnostics`, `cmdExportPng`,
  `cmdExportCategory`, `cmdExportAll`; update existing `cmdExport` (line 2573)
  to use `fileOutput()`
- `srb-made-customblocks/cloud-vault-worker/src/index.js` — add
  `POST /files` and `GET /files/:id` endpoints
- `CustomBlocksConfig.java` — no new fields needed (reuses existing
  `cloudShareEnabled` + `cloudShareUrl` + `cloudPackSecret` ⚠ This field does not exist yet and must be added to CustomBlocksConfig.java)

---

## Phase 6 — Consistency & Polish

*The mod has 30+ GUIs built over months. They all look slightly different.
Back buttons in different slots, borders inconsistent, message formats
varying. This phase makes everything look like it was designed together.*

### 6.1 Standard GUI layout

**The standard (apply to ALL GUIs):**

| Slot | Purpose |
|------|---------|
| 0 | Back button (always) |
| 4 | Title item (GUI name, display icon) |
| 1-3, 5-8 | Gray glass border |
| 9-44 | Content area (rows 2-5) |
| 45 | Previous page (paginated only) |
| 49 | Page indicator "Page 2/5" |
| 53 | Next page (paginated only) |

Create `GuiLayout.java` with `addBorder()`, `addBackButton()`,
`addPagination()` helpers. Audit every `openScreenFromGuiState` call.

**Files:** `GuiManager.java`, new `GuiLayout.java`

---

### 6.2 Standard message format

**The rules (apply everywhere):**

| Context | Channel | Format |
|---------|---------|--------|
| Quick confirmations | Actionbar | "§aCreated: name" |
| Detailed info / lists | Chat | With §8[CB] prefix |
| Big events | Title + chat | Title flash + details in chat |
| Errors | Chat only | "§c[Problem.] §7[Solution.]" |

Remove all inconsistent prefixes (`§a[GUI]`, `§c[BG Studio]`, etc.).
Use `ChatHelper.rawPrefixed()` for everything.

**Files:** `GuiManager.java`, `CustomBlockCommand.java`, new `FeedbackHelper.java` (MUST BE CREATED FROM SCRATCH),
`ChatHelper.java`

---

### 6.3 Editor GUI section grouping

**The problem:** 17+ buttons in the Block Editor crammed together with no
visual separation. Finding what you need requires scanning every button.

**The solution:** Colored glass pane section headers:

```
§e Textures     → Retexture, AI Suggest, Color Studio, Face Editor
§b Properties   → Shape, Sound, Light, Hardness, Collision
§d Color & FX   → Color Hub, Dress, Gradient
§a Manage       → Rename, Re-ID, Duplicate, Export, Share
§c Danger       → Delete (isolated, separated from other buttons)
```

**Files:** `GuiManager.java` (editor builder)

---

### 6.4 Clean up command aliases

- Keep `/cb` as the only documented prefix
- Remove `bulkcolor` (keep `bulkrecolor` as canonical)
- Keep `/customblock` as hidden alias, don't show in help
- Log deprecation warning when removed aliases are used

**Files:** `CustomBlockCommand.java`

---

## Phase 7 — Performance & Persistence

*Invisible to players but critical at scale. The mod gets slow with 200+
blocks. Data gets lost on restart. This phase fixes both.*

### 7.1 Incremental resource pack updates

**The problem:** Every edit rebuilds the ENTIRE resource pack ZIP (all 600+
blocks). #1 performance bottleneck.

**The solution:** Dirty-set tracking:
1. Track which slot indices changed since last build
2. If few changed + existing ZIP valid → copy ZIP, replace only changed entries
3. If ZIP missing or > 50% changed → full rebuild (current behavior)
4. Manifest file (slot index → CRC32) for change detection

**Expected gain:** Single block edit: ~2-5s → <200ms.

**Files:** `ServerPackGenerator.java`, `ResourcePackServer.java`

---

### 7.2 Lazy texture loading

**The problem:** All textures in RAM on startup. 600 blocks = ~300MB+ RAM.

**The solution:**
- Startup: load only metadata (ID, name, properties, CRC hash)
- Texture bytes: loaded on-demand (for network send, ZIP build, color ops)
- LRU cache: configurable max (default 50 textures in memory)
- Evicted after use, reloaded from disk when needed again

**Files:** `SlotManager.java`, `SlotData.java`

---

### 7.3 Incremental search index

**The problem:** Full index rebuild on every slot change.

**The solution:** `addToIndex()`, `removeFromIndex()`, `updateInIndex()`.
Only touch changed entries. Full rebuild only at startup.

**Files:** New `SearchIndex.java`

---

### 7.5 Persist undo across restarts

**The problem:** Undo stacks lost on restart.

**The solution:**
- Shutdown: serialize to `config/customblocks/undo.json.gz`
- Startup: deserialize and restore
- Cap at `maxUndoDepth` from config
- Each entry: description, timestamp, player UUID, and a disk-backed differential mutation record (see item 1.28 — NOT a full SlotData clone in RAM; texture ops store only the snapshot file path)

**Files:** `UndoManager.java`

---

### 7.7 Reduce default undo depth — each entry clones ALL texture bytes into RAM

> **⚠️ SUPERSEDED BY ITEM 1.28.** The disk-backed differential undo redesign (item 1.28) replaces the entire approach described here. All numbers in the original text were wrong. Corrections are recorded below for auditing purposes only. Implement item 1.28 instead.

**Confirmed real numbers (forensic audit of `CustomBlocksConfig.java`):**

| Value | Plan Claimed | Reality |
|---|---|---|
| `maxUndoDepth` default | 10,000 | **20** (`public static volatile int maxUndoDepth = 20;` at line 56) |
| `maxUndoDepth` max clamp | 100,000 | **100** (`Math.max(1, Math.min(100, maxUndoDepth))` at line 156) |
| After item 1.28 redesign | — | default **50**, max **100** (disk-backed, ~10 KB RAM per stack) |

**Why this item is superseded:**

The original problem (each undo entry deep-copies all texture bytes into RAM, ~200 KB–1.5 MB per entry) is real and confirmed from `SlotData.deepCopy()`. However, item 1.28 solves it with a fundamentally different architecture: a sealed `Mutation` interface with disk-backed differential records. Texture mutations store only a path to a snapshot file on disk (~60 bytes RAM). Non-texture ops store tiny JSON records (~100 bytes RAM). Total RAM per depth-100 stack: ~10 KB.

**Do not implement the "memory-aware trimming" code block originally in this item.** It was designed around the in-RAM approach, which is being replaced entirely.

**The one valid addition from this item** — showing undo memory usage in `/cb stats` — remains useful:
- `/cb stats` should show: "Undo: 47 entries (disk-backed, ~10 KB RAM)"
- Config GUI shows current depth alongside the setting

**Files:** See item 1.28 for the full implementation spec. `UndoManager.java`, `CustomBlocksConfig.java`

---

### 7.8 Replace ~28 silent failure points with real error handling

**The problem:** The codebase has ~28 locations where exceptions are
caught and thrown away — `catch (Exception ignored) {}`. When something
goes wrong in these places, the player sees nothing: no error, no
warning, no feedback. The operation silently fails.

**Affected areas (by category):**

| Category | Count | Impact |
|----------|-------|--------|
| Texture loading/parsing | 8 | Block appears broken, no explanation |
| GUI operations | 7 | Button does nothing when clicked |
| Shape/color parsing | 6 | Shape or color silently wrong |
| File I/O (backup, cache) | 5 | Data safety silently reduced |
| GIF/animation processing | 5 | Animation silently broken |
| Network/cloud operations | 3 | Share/import fails silently |
| Config operations | 3 | Setting silently not applied |

**The worst offenders:**
- `SlotManager.java:~495` — backup copy failure is logged as a warning but not
  surfaced to admins in-game. Upgrade to an in-game alert so admins know.
- `GuiManager.java` — multiple swallowed exceptions across GUI operations.
  Only one `catch (OutOfMemoryError oom)` exists at line 3655 with proper
  handling; all other silent catches need to be upgraded.
- `CustomBlockCommand.java` — several background operations (cloud fetch,
  texture download) catch broad exceptions and return generic errors without
  logging enough detail for diagnosis.
- `ResourcePackGenerator.java` — pack build exceptions that get logged but
  not surfaced to the player running the triggering command.
- `ImageProcessor.java` — processing failures in background threads that
  are logged but don't feed back to the initiating player or admin.

**The solution:** Systematic audit — replace every `catch (Exception
ignored) {}` with appropriate handling:

**For player-facing operations (GUI clicks, commands):**
```java
catch (Exception e) {
    LOGGER.warn("[CustomBlocks] Failed to load texture for slot {}: {}", slot, e.getMessage());
    ChatHelper.error(player, "§cFailed to load block texture. §7Try /cb retexture " + id);
}
```

**For background operations (backups, cleanup):**
```java
catch (Exception e) {
    LOGGER.error("[CustomBlocks] Backup failed for {}: {}", file, e.getMessage());
    // Don't spam the player, but track failure count
    // Alert admin on join if failures accumulated
}
```

**For truly ignorable cases (cleanup of temp resources):**
```java
catch (Exception e) {
    // OK to swallow — but add a comment explaining WHY:
    // Cleanup of temporary GPU texture. Failure is harmless — GC will collect.
}
```

**Priority: fix the data-safety ones first** (backup, slot loading,
config save). These can cause actual data loss. GUI cosmetic failures
are lower priority but still need fixing.

**Files:** All files listed in the audit. Primary targets:
`SlotManager.java`, `GuiManager.java`, `ImageProcessor.java`,
`CustomBlockCommand.java`, `TextureCache.java`

---

### 7.9 Fix thread safety for multiplayer servers

**The problem:** Several shared data structures are accessed by multiple
threads without proper synchronization. On a singleplayer server this
rarely causes issues, but on a multiplayer server with 2+ players doing
things simultaneously, it causes:
- Intermittent crashes (ConcurrentModificationException)
- Data corruption (two players editing different blocks, one edit lost)
- Inconsistent state (player A sees one thing, player B sees another)

**Specific issues found:**

| Location | Problem |
|----------|---------|
| `GuiManager.java:215` | `RESTORING` is a plain `HashSet` — must be `ConcurrentHashMap.newKeySet()` |
| `GuiManager.java:94` | `faceImportTickCounter` incremented without synchronization |
| `GuiManager.java:169` | `errorCount` is a plain `int` — needs `AtomicInteger` |
| `ResourcePackServer.java:20` | `activePort` written without synchronization |
| `CustomBlockCommand.java:35` | 2-thread executor with unbounded queue — can accumulate forever |
| `GuiManager.java:3624-3630` | `SHAPE_CREATE_COOLDOWN` get-then-put race — two players can bypass cooldown simultaneously |
| `GuiManager.java:1316-1317` | `ANIM_PARAMS` ConcurrentHashMap get-then-put race — animation parameter updates lost |
| `NetworkManager.java:228-230` | Cooldown decrement get+put on ConcurrentHashMap is not atomic |

**The solution:**

**Replace non-concurrent collections:**
```java
// OLD:
private static final Set<UUID> RESTORING = new HashSet<>();
// NEW:
private static final Set<UUID> RESTORING = ConcurrentHashMap.newKeySet();
```

**Replace plain int counters with AtomicInteger:**
```java
// OLD:
private static int errorCount = 0;
// NEW:
private static final AtomicInteger errorCount = new AtomicInteger(0);
```

**Bound the command executor queue:**
```java
// OLD:
private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
// NEW:
private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
    2, 2, 60L, TimeUnit.SECONDS,
    new LinkedBlockingQueue<>(20),  // max 20 queued tasks
    new ThreadPoolExecutor.CallerRunsPolicy()  // if full, run on caller thread
);
```
The `CallerRunsPolicy` means if 20 tasks are already queued, the next
one runs directly on the server thread. This provides natural
backpressure — the server slows down instead of accumulating an infinite
queue that eventually causes OOM.

**The experience:** Multiplayer servers stop experiencing random glitches,
lost edits, and intermittent crashes. No visible change for players —
things just stop breaking randomly.

**Files:** `GuiManager.java`, `ResourcePackServer.java`,
`CustomBlockCommand.java`, `NetworkManager.java`

---

### 7.11 Shutdown save is async — data loss on container kill

**The problem:** When the server shuts down cleanly (e.g., `/stop`),
it fires `SERVER_STOPPING`. The mod calls `SlotManager.flushSave()`
which calls `saveAllAsync()`. But `saveAllAsync()` submits the save
task to `IO_EXECUTOR` and **returns immediately** without waiting for
the write to complete.

```java
// CustomBlocksMod.java:436-439
SlotManager.flushSave();
// Control returns here. Server continues shutting down.
// IO thread: still writing to disk...
// Server process: exits.
// IO thread: killed mid-write.
// Result: slots.json.gz is incomplete or corrupted.
```

On MCServerHost (Docker container), the hosting provider may kill the
container immediately after a crash or auto-sleep trigger. The JVM
gets no chance to finish the write.

**Worst case scenario:**
```
1. You add 10 new blocks
2. MCServerHost kills the container (auto-sleep, crash, or restart)
3. IO_EXECUTOR was in the middle of writing slots.json.gz.tmp
4. The temp file exists but is incomplete
5. ATOMIC_MOVE never ran → old slots.json.gz is intact (good!)
6. BUT: any saves queued AFTER the last successful write are lost
7. Your 10 new blocks are gone
```

The ATOMIC_MOVE protects against corrupted files, but doesn't protect
against losing the LATEST unsaved data.

**The solution:** In `flushSave()`, after calling `saveAllAsync()`,
wait for the IO thread to finish:

```java
public static void flushSave() {
    if (dirty) {
        dirty = false;
        saveAllAsync();
    }
    // Wait for any pending IO to complete (max 5 seconds):
    IO_EXECUTOR.shutdown();
    try {
        if (!IO_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
            LOGGER.warn("[CustomBlocks] Save did not complete in 5s on shutdown");
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOGGER.warn("[CustomBlocks] Save interrupted during shutdown");
    }
}
```

5 seconds is generous — on the test server, a full 590-slot save takes
about 2 seconds. This ensures the write completes before the JVM exits.

**The experience:** Server stops → `[CustomBlocks] All data saved.` →
process exits. Every block edit made before `/stop` is guaranteed to
be on disk.

**Edge cases:**
- Container hard-kill (SIGKILL) → nothing can save against this. It's
  OS-level force termination. Mitigation: the auto-snapshot every 30
  minutes means max loss is 30 minutes of work, not everything.
- Multiple shutdown hooks run in parallel → FlushSave should be the
  FIRST hook to run, before other managers, to maximize save time.
- IO thread stuck (disk full, NFS hang) → the 5s timeout prevents the
  JVM from hanging forever. Warns admin.

**Why this matters:** This is a silent data loss bug on every ungraceful
shutdown. On a 3 GB Docker server that can be killed by the host at
any time (OOM, container restart, host maintenance), "ungraceful
shutdown" is not rare — it's a regular occurrence.

**Files:** `SlotManager.java` (flushSave — await IO_EXECUTOR completion),
`CustomBlocksMod.java` (server stopping event — ensure flushSave is
first in the shutdown sequence)

---

### 7.13 Chunked texture packet can cause client crash (OOM)

**The problem:** When the server sends a large texture in chunks,
the client creates a `ChunkBuffer` to hold them. The buffer allocates
`new byte[totalChunks][]` — an array sized by `totalChunks`, which
comes directly from the packet.

There is NO bounds check on `totalChunks` before the allocation:

```java
ChunkBuffer(int totalChunks) {
    this.totalChunks = totalChunks;
    this.chunks = new byte[totalChunks][];  // no limit check!
}
```

If a corrupted packet (or a bug causing a bad value) sends
`totalChunks = 1,000,000`, the client immediately tries to allocate
a 4 GB array → `OutOfMemoryError` → Minecraft crashes.

In practice, the server controls this value so accidental corruption
is the main risk, not an attack. But on a 3 GB server under memory
pressure, a corrupted packet could crash every connected client.

**The fix:**
```java
private static final int MAX_CHUNKS = 200; // 200 × 512KB = 100MB max

ChunkBuffer(int totalChunks) {
    if (totalChunks <= 0 || totalChunks > MAX_CHUNKS) {
        throw new IllegalArgumentException(
            "Invalid totalChunks: " + totalChunks);
    }
    this.totalChunks = totalChunks;
    this.chunks = new byte[totalChunks][];
}
```

And in the packet handler:
```java
if (payload.totalChunks() <= 0 || payload.totalChunks() > MAX_CHUNKS) {
    LOGGER.warn("[CustomBlocks] Rejected chunk packet — invalid totalChunks: {}",
        payload.totalChunks());
    return;
}
```

**Files:** `CustomBlocksClient.java` (ChunkBuffer constructor + handler)

---

### 7.14 Image download SSRF — server can be made to probe internal network

**The problem:** When you use `/cb create` or `/cb edit` with an image
URL, the MOD SERVER (not the player's computer) makes the HTTP request.
The URL is accepted without any validation of the destination address.

```
What a player could do:
  /cb create myblock "http://192.168.1.1:8080/admin" png

What happens:
  Your server reaches out to 192.168.1.1:8080 — which might be
  your router admin panel, MCServerHost's internal network, or any
  internal service on the same network as the server.
  The response body is processed as an "image" and fails gracefully.
  But the request WAS made — and the attacker learns:
  - Whether that internal address exists (connection vs timeout)
  - The response time (network topology)

Cloud metadata APIs can also be probed this way:
  http://169.254.169.254/latest/meta-data/ (AWS metadata API)
  → Returns instance credentials, SSH keys, and more
```

On MCServerHost's Docker setup, the internal network risk is lower
since containers are isolated. But the metadata API risk is real on
any cloud-hosted server.

Additionally: **the size check happens AFTER the full download.**
A malicious URL serving a 1 GB file will cause the server to download
the full gigabyte into RAM before rejecting it. On a 3 GB server this
alone can trigger OOM.

**The fix:**

**Block 1 — Reject internal IP addresses before connecting:**
```java
private static boolean isBlockedAddress(String url) {
    try {
        String host = URI.create(url).getHost();
        if (host == null) return true;
        // Block localhost, link-local, private ranges
        if (host.equals("localhost")) return true;
        InetAddress addr = InetAddress.getByName(host);
        return addr.isLoopbackAddress()
            || addr.isSiteLocalAddress()   // 10.x, 172.16-31.x, 192.168.x
            || addr.isLinkLocalAddress()   // 169.254.x (metadata APIs)
            || addr.isAnyLocalAddress();
    } catch (Exception e) {
        return true; // if we can't resolve it, block it
    }
}
```

**Block 2 — Check Content-Length BEFORE downloading:**
```java
// Send HEAD request first:
HttpRequest headReq = HttpRequest.newBuilder()
    .uri(URI.create(url))
    .method("HEAD", HttpRequest.BodyPublishers.noBody())
    .timeout(Duration.ofSeconds(5))
    .build();
HttpResponse<Void> headRes = HTTP.send(headReq, HttpResponse.BodyHandlers.discarding());
String contentLength = headRes.headers().firstValue("content-length").orElse("0");
long size = Long.parseLong(contentLength);
if (size > 20_971_520) throw new IOException("File too large: " + size + " bytes");
```

**The experience:** Players trying to probe internal addresses get:
`§c[CustomBlocks] That URL is not allowed (internal network).`
The error is the same for all blocked addresses — no information leak
about whether the address exists.

**Files:** `ImageProcessor.java` (download method — add SSRF check
and HEAD pre-check before full download)

---

### 7.21 Animation settings GUI has a TOCTOU race — stale undo entry pushed for deleted block

**The problem:** `applyAnimSettings()` in `GuiManager.java` checks block
existence at line 3894 (`if (!SlotManager.hasId(id)) return;`), then
proceeds to call `UndoManager.pushUndoMutation()` at line 3908, then
`SlotManager.setAnimMeta(id, ...)` at line 3909.

Between the existence check and the mutation, another admin session can
run `/cb delete id`. The result:

1. `UndoManager.pushUndoMutation()` pushes an undo entry for a block that
   no longer exists in `SlotManager`
2. `SlotManager.setAnimMeta()` acts on a deleted block — undefined behavior
3. If the player then undoes this operation, the undo system tries to
   restore a block that doesn't exist, creating a ghost undo entry that
   can never be popped cleanly

No crash occurs (null check at line 3912 prevents NPE), but the undo
stack is now corrupted with a dangling entry.

**The solution:** Re-check block existence after acquiring the undo entry, and guard the entire operation:

```java
SlotData before = SlotManager.getById(id);
if (before == null) { playError(player); return; }
UndoManager.pushUndoMutation(id, before, "animsettings", player.getUuid());
SlotManager.setAnimMeta(id, newMeta);
// Re-validate after mutation
if (SlotManager.getById(id) == null) {
    playError(player);
    return;
}
```

Note: SlotManager does not expose a public lock object. The proper fix is to move the pre-mutation existence check as close as possible to the mutation, and use the null-check on the post-mutation `getById` (already at line 3911) as the safety net.

**Files:** `GuiManager.java` (`applyAnimSettings` — wrap hasId check +
pushUndoMutation + setAnimMeta in a single synchronized block)

---

### 7.22 Player-injected § formatting codes stored verbatim in block names

**The problem:** `cmdRename()` in `CustomBlockCommand.java` (line 1408)
passes the player-supplied name directly to `SlotManager.rename()` with
NO stripping of `§` formatting codes:

```java
SlotManager.rename(id, newName);  // newName comes straight from command arg
```

A player running `/cb rename myblock "Test §c§lRED§r Normal"` stores the
raw `§c§l§r` formatting codes verbatim in the block's display name. When
this name is rendered in chat, tooltips, or GUI headers, those codes bleed
color into adjacent text in unpredictable ways. Block names with `§k`
(obfuscated) render as random flickering characters in every tooltip.

There is no validation path in the rename command that strips raw `§`
characters. `GuiManager.stripFormattingCodes()` exists at line 5925 but
is only used internally for GUI display — it is never applied to
player-supplied input before storage.

**The solution:** In the rename command handler, strip raw `§` codes from
player-supplied names before storing:

```java
String sanitized = name.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
```

Optionally: whitelist a safe subset (§a–§f for colors, §r for reset) if
color names are a desired feature.

**Files:** `CustomBlockCommand.java` (rename handler at line 1408 — strip `§` codes before passing to SlotManager.rename)

---

### 7.27 AnimSettingsPayload has no rate limit, no permission check, and no size cap

**The problem:** The `AnimSettingsPayload` C2S handler in `CustomBlocksMod.java:246-295`
accepts animation metadata changes from ANY connected client with zero guards:

1. **No rate limiting** — a malicious or buggy client can send thousands of packets
   per second, each triggering `SlotManager.saveAll()` (disk I/O), `UndoManager.pushUndoMutation()`
   (heap allocation), and `NetworkManager.broadcastUpdate()` (sends to ALL players).
   Result: disk I/O saturation + memory exhaustion + network flooding for every online player.

2. **No permission check** — `canEdit` is checked at the command level but NOT in the
   network handler. Any player, regardless of OP level, can send this packet and change
   animation settings on any block. There is no `PermissionHelper.canEdit()` call in
   the handler.

3. **No `animMeta` size cap** — Minecraft allows string fields up to 65,535 characters.
   A 65KB `animMeta` stored in `SlotData` is then broadcast to EVERY player on every
   join sync. With 600 blocks all having 65KB animMeta: `600 × 65KB = 39MB` added to
   every full sync payload. On a 3GB server with 8 players, this alone can cause OOM.

Confirmed: the handler at line 268 calls `SlotManager.saveAll()` unconditionally with
no cooldown. The only check is `if (cid == null || meta == null || meta.isEmpty()) return;`
— which stops nulls but not spam or oversized strings.

**The solution:**

**Rate limit:** One AnimSettings update per block per player per second:
```java
private static final ConcurrentHashMap<UUID, Long> LAST_ANIM_TIME = new ConcurrentHashMap<>();
long now = System.currentTimeMillis();
Long last = LAST_ANIM_TIME.get(playerId);
if (last != null && (now - last) < 1000) return; // 1s cooldown
LAST_ANIM_TIME.put(playerId, now);
```

**Permission check:**
```java
if (!PermissionHelper.canEdit(context.player().getCommandSource())) return;
```

**Size cap:**
```java
private static final int MAX_ANIM_META = 8192; // 8KB is more than enough for mcmeta JSON
if (meta.length() > MAX_ANIM_META) { LOGGER.warn(...); return; }
```

Clean up `LAST_ANIM_TIME` entry in `NetworkManager.onPlayerDisconnect()`.

**The experience:** No visible change for legitimate use. Spamming the packet does nothing
after the first update. Unpermissioned players can't modify blocks they shouldn't be able to.

**Edge cases:**
- Spam from lag/buggy client → rate limit absorbs it silently
- Admin with high-frequency legitimate use → 1s cooldown is unnoticeable for manual GUI
- Extremely long mcmeta (many frames with exact per-frame timing) → 8KB handles 1000+
  frame entries. Any real mcmeta is well under 2KB.

**Files:** `CustomBlocksMod.java` (AnimSettingsPayload handler — add rate limit, permission
check, size cap), `NetworkManager.java` (onPlayerDisconnect — clean up LAST_ANIM_TIME)

---

### 7.29 ResourcePackServer HTTP handler has a TOCTOU race — clients receive wrong Content-Length

**The problem:** The HTTP handler for `/pack.zip` in `ResourcePackServer.java:73-88`
has a time-of-check / time-of-use (TOCTOU) race:

```java
if (currentPackFile == null || !currentPackFile.exists()) {  // check at T1
    // 404...
}
exchange.sendResponseHeaders(200, currentPackFile.length());  // use at T2 — different instant!
Files.copy(currentPackFile.toPath(), os);                     // use at T3 — yet another instant
```

Between T1, T2, and T3, the `PackBuilder` thread can atomically replace `currentPackFile`
(via `ResourcePackServer.updatePackWithSnapshot`). The HTTP handler holds the OLD reference
for `.length()` but `currentPackFile` now points to a NEW file. The result:

- Incorrect `Content-Length` header sent to Minecraft
- Minecraft's resource pack downloader compares received bytes against `Content-Length`
- Mismatch → download aborted → client forced to retry → "Failed to download" resource pack
- This is invisible: no log entry, no error on the server side, just clients stuck without textures

Additionally, `currentPackFile` and `currentHash` are plain (non-volatile) `static` fields.
The HTTP handler runs on the `sun.net.httpserver` thread; the pack builder runs on
`CustomBlocks-PackBuilder` thread. Java's memory model does NOT guarantee the HTTP thread
sees the latest write to non-volatile fields.

**The solution:** Capture the file reference atomically at the start of the handler:

```java
server.createContext("/pack.zip", exchange -> {
    java.io.File file = currentPackFile; // capture once — atomic read
    if (file == null || !file.exists()) { /* 404 */ return; }
    exchange.sendResponseHeaders(200, file.length()); // same reference throughout
    Files.copy(file.toPath(), exchange.getResponseBody());
});
```

Also mark both `currentPackFile` and `currentHash` as `volatile`:
```java
private static volatile java.io.File currentPackFile;
private static volatile String currentHash;
```

**The experience:** Players reliably receive the correct pack ZIP with a matching
`Content-Length` every time — no silent download failures, no "failed to apply resource pack"
errors on the client side.

**Files:** `ResourcePackServer.java` (HTTP handler — capture `currentPackFile` into local
variable before any use; mark `currentPackFile` and `currentHash` as `volatile`)

---

### 7.30 getExternalIp() makes a blocking HTTPS call with no caching — 3-second stall per call

**The problem:** `ResourcePackServer.getExternalIp()` (lines 166–180) makes a synchronous
HTTPS connection to `checkip.amazonaws.com` every single time the pack URL is needed:

```java
public static String getExternalIp() {
    java.net.URL url = new java.net.URL("https://checkip.amazonaws.com");
    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
    conn.setConnectTimeout(3000); // ← 3s blocking wait
    conn.setReadTimeout(3000);    // ← 3s blocking wait
    ...
}

public static String getPackUrl(...) {
    String ip = getExternalIp(); // ← called every time — NO CACHE
```

On MCServerHost, ALL external HTTP ports are blocked. This function ALWAYS:
1. Times out after 3 seconds
2. Returns `"127.0.0.1"` (localhost — wrong)
3. Leaves players with a pack URL that nobody can connect to

This blocking call happens whenever any player or command requests the pack URL —
including commands like `/cb config`, the GUI "Copy Link" button, and FeedbackHelper's
`broadcastPackRegenerated` flow. Each call stalls the calling thread for 3 seconds.

On the server thread, a 3-second stall means 3 seconds of the tick loop blocked, which
triggers "Can't keep up!" warnings and potentially kicks players.

**The solution:** Cache the result with a 5-minute TTL, and never block on retry:

```java
private static volatile String cachedExternalIp = null;
private static volatile long ipCacheTime = 0;
private static final long IP_CACHE_TTL_MS = 300_000; // 5 minutes

public static String getExternalIp() {
    long now = System.currentTimeMillis();
    if (cachedExternalIp != null && (now - ipCacheTime) < IP_CACHE_TTL_MS) {
        return cachedExternalIp; // instant — no network call
    }
    // Fetch on a background thread; return last known value (or 127.0.0.1) immediately
    String result = fetchExternalIpAsync();
    return result != null ? result : (cachedExternalIp != null ? cachedExternalIp : "127.0.0.1");
}
```

The fetch should always happen on a daemon thread, never on the server thread or HTTP handler.
The fallback returns the last successfully fetched IP rather than `"127.0.0.1"` — so if the
initial fetch ever succeeded, future timeouts don't break the URL.

**The experience:** Pack URL generation is instant. On MCServerHost where the lookup always
fails, it fails fast (immediately returns `127.0.0.1`) without stalling anything. On servers
where the lookup succeeds, the real IP is cached and used for 5 minutes.

**Files:** `ResourcePackServer.java` (`getExternalIp` — add in-memory cache with TTL,
async refresh on background thread, fallback to last-known IP)

---

### 7.31 Shape cache accumulates dead entries for deleted blocks — minor unbounded growth

**The problem:** `SlotBlock.java` maintains a static `SHAPE_CACHE` (line 30):
```java
private static final ConcurrentHashMap<Integer, VoxelShape> SHAPE_CACHE = new ConcurrentHashMap<>();
```

`invalidateShape(int slotIndex)` at line 45 removes an entry from the cache when a block's
shape is CHANGED. But when a block slot is DELETED via `SlotManager.remove()`, there is no
corresponding call to `invalidateShape()`. The dead `VoxelShape` entry for that slot index
stays in the cache permanently.

Each `VoxelShape` is small (~1–4 KB depending on complexity). With 600 blocks over a long
session with frequent create-delete cycles, the cache accumulates hundreds of dead entries.
This is a low-severity, slow-growing memory leak.

Additionally, if a deleted slot index is reused (a new block created in the same slot),
the old cached shape is served immediately before the new block's shape is processed —
briefly giving the new block the wrong collision shape.

**The solution:** One line in `SlotManager.remove()`:
```java
com.customblocks.block.SlotBlock.invalidateShape(data.index);
```

This is the same pattern used when shapes are updated — just apply it on deletion too.

**Files:** `SlotManager.java` (`remove()` — add `SlotBlock.invalidateShape(data.index)` call)

---

### 7.33 ImageProcessor.download() makes server-side requests without an IP blocklist — SSRF vulnerability

**The problem:** `ImageProcessor.download()` accepts any URL from a player command
(`/cb create`, `/cb retexture`, `/cb setface`) and opens an HTTP connection to it via
Java's `HttpClient`. There is no check whether the URL resolves to a private, loopback,
or link-local address. Any player with create/retexture permission can use the mod as an
HTTP proxy to probe internal services:

```java
// No IP validation anywhere in download():
HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
```

Exploitable via:
- `http://127.0.0.1:8080/internal-admin` — probe localhost services
- `http://192.168.1.1/config` — probe local router/NAS admin panels
- `http://169.254.169.254/latest/meta-data/` — AWS/cloud metadata endpoint
- `http://10.0.0.1/api/` — probe private container network

The `HttpClient.Redirect.NORMAL` setting (line 54) makes it worse: a public URL that
redirects to an internal IP will still be followed.

Additionally, the current CDN URL rewriting (lines 161-167) could theoretically be used
to construct internal URLs that partially match the Discord/Imgur pattern checks.

**The solution:** Before sending the request, resolve the URI's hostname and reject if
it maps to a private range:

```java
private static void rejectPrivateIp(URI uri) throws IOException {
    try {
        InetAddress addr = InetAddress.getByName(uri.getHost());
        if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
            throw new IOException(
                "§eThat link points to an internal network address. " +
                "§7Only public internet URLs are allowed.");
        }
    } catch (java.net.UnknownHostException e) {
        throw new IOException(
            "§eCouldn't look up §f" + uri.getHost() +
            "§e. §7Check for typos in the URL.");
    }
}
```

Call `rejectPrivateIp(URI.create(fetchUrl))` immediately after the URL is built, before
`HTTP.send()`. Also call it on the post-redirect final URL if the response has a
`Location` header pointing to a private IP.

IPv6 private ranges to block: `::1` (loopback), `fc00::/7` (unique local),
`fe80::/10` (link-local).

**The experience:** A player who accidentally (or deliberately) pastes an internal URL
gets a clear error: `"§eThat link points to an internal network address. §7Only public
internet URLs are allowed."` Normal public URLs are unaffected.

**Edge cases:**
- DNS rebinding: hostname resolves to public IP first, then attacker changes DNS to
  private IP between check and request. Mitigation: re-validate the IP after the
  response is received using the connection's remote address (or disable redirects to
  private IPs). Full DNS rebinding protection requires a more invasive socket factory
  approach — document this limitation.
- IPv6 mapped IPv4 (`::ffff:192.168.1.1`): The `InetAddress` check covers this because
  Java normalizes mapped addresses before `isSiteLocalAddress()`.
- wsrv.nl proxy path: when `isWebP()` triggers, the re-request goes to wsrv.nl (a
  legitimate public proxy). This is fine — `rejectPrivateIp` would still allow
  wsrv.nl's public IP.

**Files:** `ImageProcessor.java` (`download()` method — add `rejectPrivateIp()` helper,
call it before `HTTP.send()` and again after any 3xx redirect is followed)

---

### 7.34 Full HTTP response body allocated in memory before size check — memory exhaustion DoS

**The problem:** `ImageProcessor.download()` uses `HttpResponse.BodyHandlers.ofByteArray()`
which buffers the entire response body in a single `byte[]` before the size check runs:

```java
HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
// ...
byte[] body = res.body();      // full body already in memory
if (body.length > 20_971_520)  // check AFTER allocation
    throw new IOException("Too big!");
```

This means:
1. A 20 MB image allocates 20 MB on the heap before being rejected
2. Two concurrent `/cb create` commands × 20 MB each = 40 MB allocated simultaneously
3. With 8 players all running retexture at once on a 3 GB heap server, this easily
   consumes 160 MB in transient download buffers
4. Crafted slow responses that trickle bytes without sending Content-Length can keep
   large buffers alive longer than necessary

The `EXECUTOR` in `CustomBlockCommand.java` is a fixed pool of 2 threads, which limits
download parallelism there — but `GuiManager` has its own separate `EXECUTOR` pool of 2
threads, and `RectangleToolItem` spawns its own untracked `Thread`. Downloads can pile up
from multiple sources simultaneously.

**The solution:** Switch to streaming download with a hard cap on bytes read:

```java
// Pre-check Content-Length header if present:
long contentLength = res.headers().firstValueAsLong("Content-Length").orElse(-1);
if (contentLength > 20_971_520)
    throw new IOException("§eToo big! Content-Length reports " +
        (contentLength / 1_048_576) + " MB — max is 20 MB.");

// Stream with byte counter instead of ofByteArray():
InputStream in = res.body();  // using BodyHandlers.ofInputStream()
ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min((int)contentLength, 1_048_576));
byte[] buf = new byte[65536];
int n, total = 0;
while ((n = in.read(buf)) != -1) {
    total += n;
    if (total > 20_971_520)
        throw new IOException("§eToo big! Max is §f20 MB§7. Shrink it first.");
    out.write(buf, 0, n);
}
```

This caps heap allocation to 20 MB regardless of the claimed content length, and rejects
oversized responses after at most one extra buffer read.

**The experience:** Oversized files are rejected faster (from headers when possible, from
the stream immediately when the limit is hit) without first consuming full memory.

**Edge cases:**
- Servers that don't send `Content-Length`: stream cap still enforces the 20 MB limit
- `ByteArrayOutputStream` still allocates up to 20 MB in the worst case — but this is
  the minimum possible for a streaming download that must return a `byte[]`
- The wsrv.nl proxy path re-uses `download()` for WebP conversion — the same fix
  applies automatically

**Files:** `ImageProcessor.java` (`download()` — switch to `BodyHandlers.ofInputStream()`,
add `Content-Length` pre-check, stream with a byte counter)

---

### 7.35 CB2! import code triggers zip bomb — unbounded GZIP decompression crashes server

**The problem:** `CustomBlockCommand.decodeInlineImportCode()` decompresses `CB2!` share
codes without any output size cap:

```java
if (code.startsWith("CB2!")) {
    byte[] compressed = Base64.getDecoder().decode(code.substring(4));
    try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(compressed));
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buf = new byte[4096];
        int n;
        while ((n = gz.read(buf)) != -1) out.write(buf, 0, n); // ← no cap
        return out.toString(UTF_8);
    }
}
```

A classic zip bomb: a few hundred bytes of valid GZIP that decompresses to gigabytes of
repeated data. Java's `GZIPInputStream` will happily read and expand the entire thing.
With no size limit, `ByteArrayOutputStream` grows unboundedly until the JVM throws
`OutOfMemoryError` and crashes the server.

This is reachable by any player who can run `/cb importblock` (which only requires the
`canUse` permission — the same permission as running `/cb gui`). No elevated permission
is needed.

**The solution:** Add a hard cap on the total bytes read from the decompressed stream:

```java
private static final int MAX_IMPORT_JSON_BYTES = 2 * 1024 * 1024; // 2 MB

if (code.startsWith("CB2!")) {
    byte[] compressed = Base64.getDecoder().decode(code.substring(4));
    if (compressed.length > 1_048_576) // reject oversized compressed input too
        throw new Exception("Import code is too large.");
    try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(compressed));
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buf = new byte[4096];
        int n, total = 0;
        while ((n = gz.read(buf)) != -1) {
            total += n;
            if (total > MAX_IMPORT_JSON_BYTES)
                throw new Exception("Import data too large (max 2 MB).");
            out.write(buf, 0, n);
        }
        return out.toString(UTF_8);
    }
}
```

A legitimate block JSON (all fields + base64 texture) is well under 1 MB even for 256px
textures with face overrides. 2 MB gives more than 10× headroom while stopping bombs.

**The experience:** A malicious or corrupted import code fails with a clear error:
`"§c[CustomBlocks] Error decoding block: Import data too large (max 2 MB)."` No server
restart needed, no data lost.

**Edge cases:**
- Legacy `CB!` codes use raw Base64 (not gzip) at line 1201-1203 — that path does not
  call `GZIPInputStream`, so it is not affected by this specific bomb vector. However,
  its `Base64.getDecoder().decode()` also has no size cap. Add the same 2 MB cap to
  the decoded string length.
- The `compressed.length > 1_048_576` pre-check on the compressed input adds a second
  layer: GZIP typically achieves 10:1 compression at best for text, so 1 MB compressed
  → 10 MB decompressed — with the 2 MB decompressed cap this is never reached in
  practice. The pre-check eliminates the overhead of even starting to decompress obvious
  bombs.

**Files:** `CustomBlockCommand.java` (`decodeInlineImportCode()` — add
`MAX_IMPORT_JSON_BYTES` constant, add per-read counter, add pre-check on compressed
input length; also cap the CB! Base64 decoded output)

---

### 7.36 Block import hash used directly as filename — path traversal writes outside exports directory

**The problem:** When a player runs `/cb importblock CB~<hash>`, the hash is extracted
directly from the command and used to construct a file path without any sanitization:

```java
// cmdImportBlock:
String hash = code.substring(3).trim();   // user-controlled!
Path exportFile = Path.of("config/customblocks/exports", hash + ".json");
// ...
// cacheCloudShare:
Files.writeString(exportDir.resolve(hash + ".json"), json, UTF_8);
```

On most operating systems, `Path.of("config/customblocks/exports", "../../../evil")`
resolves to `config/evil` (or higher). A player can write arbitrary JSON files anywhere
the server process has write access:

```
/cb importblock CB~../../server.properties
```
This would overwrite `config/server.properties` with arbitrary JSON content on next
cloud import cache write.

The same vulnerability exists in `cmdImportBlock`'s local file read:
```java
Path exportFile = Path.of("config/customblocks/exports", hash + ".json");
if (Files.exists(exportFile)) {
    return importDecodedBlock(src,
        Files.readString(exportFile, UTF_8), false); // arbitrary file read
}
```

A path like `../../../config.json` reads the mod's own config file, which is then
parsed as block JSON — benign but potentially confusing. A path like
`../../../slots.json` reads the entire slot database.

**The solution:** After constructing the path, validate it stays within the exports
directory:

```java
Path exportsDir = Path.of("config/customblocks/exports").toAbsolutePath().normalize();
Path exportFile = exportsDir.resolve(hash + ".json").normalize();
if (!exportFile.startsWith(exportsDir)) {
    throw new IllegalArgumentException("Invalid share code.");
}
```

Additionally, validate the hash contains only safe characters before the path check:

```java
if (!hash.matches("[A-Za-z0-9!@#$%&_\\-\\.]{1,50}")) {
    throw new IllegalArgumentException("Invalid share code format.");
}
```

**The experience:** Malformed or malicious codes fail with `"§c[CustomBlocks] Error
decoding block: Invalid share code."` No files are read or written outside the exports
directory.

**Edge cases:**
- The SHARE_ALPHABET used by `generateShareCode()` already excludes `/` and `\`, so
  legitimate locally-generated codes are always safe. This bug only matters when a user
  manually constructs a `CB~` code with crafted content.
- Windows path separators: `hash + ".json"` could contain backslash on Windows. The
  `normalize()` call handles both separators.
- The `cacheCloudShare()` method is called from `EXECUTOR.submit()` on an async thread
  — the validation must happen BEFORE the async submit, using the hash from the same
  scope.

**Files:** `CustomBlockCommand.java` (`cmdImportBlock()` — validate hash before
`Path.of()` lookup and before `EXECUTOR.submit()`; `cacheCloudShare()` — add
`startsWith(exportsDir)` guard after normalize)

---

### 7.37 Texture .dat files written non-atomically — crash during write corrupts the slot permanently

**The problem:** `SlotManager.writeTextureFile()` and `writeFaceTextureFile()` write
binary texture data directly to the destination path:

```java
// No temp file, no atomic rename:
Files.write(dir.resolve("slot_" + slotIndex + ".dat"), data);
```

If the server crashes, is killed, or runs out of disk space while this write is in
progress, the `.dat` file is left in a partial state. On the next startup, `loadAll()`
calls `readTextureFile()` which reads the partial bytes and stores them in
`SlotData.texture`. The slot then appears visually broken (purple/black checkerboard)
even though the texture was valid before the crash.

This is different from the `slots.json` save (which correctly uses temp+ATOMIC_MOVE) —
the texture files are the SECOND storage location for the same data. Both need the same
protection.

The risk is highest for large textures (256px animated strips can be 300+ KB) where the
write spans multiple filesystem blocks and a crash mid-write is more likely.

**The solution:** Write to a `.tmp` file first, then atomically rename:

```java
private static void writeTextureFile(int slotIndex, byte[] data) {
    try {
        Path dir = Path.of(TEXTURES_DIR);
        Files.createDirectories(dir);
        Path dest = dir.resolve("slot_" + slotIndex + ".dat");
        if (data != null && data.length > 0) {
            Path tmp = dir.resolve("slot_" + slotIndex + ".dat.tmp");
            Files.write(tmp, data);
            Files.move(tmp, dest,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(dest);
        }
    } catch (Exception e) {
        LOGGER.error("[CustomBlocks] Failed to write texture for slot_{}", slotIndex, e);
    }
}
```

Apply the same pattern to `writeFaceTextureFile()`.

**The experience:** A server crash during texture write leaves the `.tmp` file behind.
On next startup, `.tmp` files are ignored by `readTextureFile()` (which only looks for
`.dat`), so the slot uses its last known-good texture (from the previous successful save)
rather than a corrupt partial file.

**Edge cases:**
- `ATOMIC_MOVE` may not be atomic on all platforms (Windows, NFS). The `.tmp` extension
  means even a non-atomic rename leaves at worst the `.tmp` artifact, never a corrupt
  `.dat`.
- Orphaned `.tmp` files from a crash: add a startup cleanup pass in `loadAll()` that
  deletes any `*.dat.tmp` files in the textures directory.
- `writeTextureFile` is called from `IO_EXECUTOR` (single thread) so there are no
  concurrent write conflicts between two calls for the same slot index.

**Files:** `SlotManager.java` (`writeTextureFile()` and `writeFaceTextureFile()` —
add tmp+ATOMIC_MOVE pattern; `loadAll()` — add startup `.dat.tmp` cleanup)

---

### 7.38 GuiManager leaks RECENT_BLOCKS and SEARCH_QUERIES maps on player disconnect

**The problem:** `GuiManager.onPlayerDisconnect(UUID)` cleans up 13 per-player maps,
but silently misses two:

```java
// Never cleaned up in onPlayerDisconnect:
private static final Map<UUID, Deque<String>> RECENT_BLOCKS = new ConcurrentHashMap<>();
private static final Map<UUID, String> SEARCH_QUERIES = new ConcurrentHashMap<>();
```

`RECENT_BLOCKS` stores the last 3 blocks each player visited in the editor GUI.
`SEARCH_QUERIES` stores the last search string they typed in the search picker.

Both maps use `ConcurrentHashMap<UUID, ...>`, meaning a disconnected player's
entry survives indefinitely until the server restarts. On a long-running server
with players joining and leaving over weeks, this is a steady unbounded memory
growth: one `Deque<String>` + one `String` per unique player UUID ever seen.

The same maps are also absent from `GuiManager.clearState()`:
```java
public static void clearState(ServerPlayerEntity player) {
    STATES.remove(player.getUuid());
    PENDING.remove(player.getUuid());
    FACE_IMPORTS.remove(player.getUuid());
    FACE_CHANGE_SELECTIONS.remove(player.getUuid());
    FACE_CHANGE_RETURN_PAGES.remove(player.getUuid());
    BACK_STACK.remove(player.getUuid());
    // ← RECENT_BLOCKS and SEARCH_QUERIES not removed
}
```

**The solution:** Add both maps to `onPlayerDisconnect()` and `clearState()`:

```java
public static void onPlayerDisconnect(UUID uuid) {
    // ... existing 13 removals ...
    RECENT_BLOCKS.remove(uuid);      // add this
    SEARCH_QUERIES.remove(uuid);     // add this
}
```

**The experience:** Memory stays flat regardless of how many players have ever
connected. Search state and recent-block history are intentionally ephemeral —
both should be cleared when a player leaves anyway.

**Edge cases:**
- `RECENT_BLOCKS` entries are limited to 3 items by `MAX_RECENT` — so the per-player
  leak is small (3 strings). The issue is purely the number of unique UUID entries
  accumulating indefinitely, not the per-entry size.
- `clearState()` is called when a player types certain commands (like `/cb`) to reset
  their GUI state. If it doesn't clear these maps, stale search queries could
  unexpectedly persist and re-populate the GUI after a re-open.

**Files:** `GuiManager.java` (`onPlayerDisconnect()` — add `RECENT_BLOCKS.remove()` and
`SEARCH_QUERIES.remove()`; `clearState()` — same two additions)

---

### 7.39 triggerGlowUpdate() scans 274,625 positions on the main server thread every glow change

**The problem:** Every time a player changes a block's glow level via the `/cb setglow` command,
`triggerGlowUpdate()` is called. It spawns a background thread only to immediately
schedule a triple-nested loop on the **main server thread** (via `server.execute()`):

```java
// CustomBlockCommand.java ~line 3370
thread(() -> {
    server.execute(() -> {               // ← main thread from here
        for (int x = cx - 32; x <= cx + 32; x++)
            for (int y = cy - 32; y <= cy + 32; y++)
                for (int z = cz - 32; z <= cz + 32; z++) {
                    world.getBlockState(mpos);   // 274,625 chunk reads
                    // + checkBlock() on matches
                }
    });
});
```

The `thread()` wrapper provides no real relief — its only job is to call
`server.execute()`, which immediately queues all 274,625 iterations on the
main thread anyway.

Radius = 32 on all three axes → **(32×2+1)³ = 65³ = 274,625 position checks**,
every single time any player sets a glow value. On a live server with 3 players
each adjusting glow on different blocks simultaneously, the main thread processes
**~823,875 position checks in a single tick**, stalling every other game system
(movement, mob AI, block ticks) until the scan finishes. This manifests as a
noticeable TPS drop (multiple hundreds of ms) whenever anyone touches the glow
slider — even players who are nowhere near the edited block.

**The solution:** Move glow propagation off the main thread entirely. Two options,
either works:

*Option A — async world scan:*
Submit the triple loop to the mod's existing `EXECUTOR` thread pool. Guard each
position check with `server.execute()` for the actual block-state mutation only,
so chunk lock is respected without holding the scan on the main thread.

*Option B — targeted propagation:*
Instead of scanning a full 65³ cube, only re-light the block's own chunk and its
directly adjacent chunks (max 9 chunks). The vanilla lighting engine propagates
glow level changes to neighbors automatically — the triple-loop reinvents work
the engine already does.

Option B is preferred: it eliminates the scan entirely and delegates to MC's
built-in lighting.

**The experience:** Admin changes a block's glow level → the block lights up
instantly. No TPS spike, no lag for other players, no perceptible pause.

**Edge cases:**
- Adjacent-chunk approach misses edge cases where the block is very close to
  chunk borders and the glow radius extends past the 3×3 chunk window. This
  only matters for glow levels above ~8 blocks. For the mod's use case (glow
  per-slot, not per-world-position), the chunk-trigger approach is sufficient.
- If two players update glow on adjacent blocks in the same tick and Option B
  triggers two chunk relight tasks, vanilla handles them in sequence correctly —
  no race condition.

**Files:** `CustomBlockCommand.java` (`triggerGlowUpdate()` ~line 3370)

---

### 7.40 GoldenHexagonItem.ROTATION_STATE is allocated but never used

**The problem:** `GoldenHexagonItem.java` declares a static `ConcurrentHashMap`
at the top of the class:

```java
// GoldenHexagonItem.java ~line 45
private static final Map<String, Integer> ROTATION_STATE = new ConcurrentHashMap<>();
```

This map is **never written to and never read from** anywhere in the file or in
any other class. It is dead code. A `ConcurrentHashMap` is allocated on every
class load for zero purpose — a small, harmless memory waste, but dead code in
a concurrent context also suggests incomplete feature work or a forgotten
refactor.

**The solution:** Delete the field declaration entirely. If rotation state was
intended as a future feature, the map should be tracked in the mod's issue log —
not silently sitting in production code as an uninitialized stub.

**The experience:** No visible player change. Code becomes cleaner.

**Edge cases:**
- Verify `ROTATION_STATE` is not referenced by reflection or annotation
  processing before removing. A Grep confirms it is not.

**Files:** `GoldenHexagonItem.java` (~line 45, remove the field declaration)

---

### 7.41 scheduleSingleSlotReload() silently drops second texture write during concurrent updates

**The problem:** When two texture updates arrive within ~500 ms of each other
(e.g., a player rapidly runs `/cb retexture` twice on different slots),
`scheduleSingleSlotReload()` is called twice. The second call hits the
`generateRunning == true` branch and **returns without queuing the second slot's
texture write**:

```java
// CustomBlocksClient.java ~line 854
if (generateRunning.compareAndSet(false, true)) {
    // thread starts — writes slot A's texture PNG to pack dir, then reloads
} else {
    // generateRunning was already true
    // NOTHING happens here — slot B's PNG is never written
    // pendingFullReload is NOT set
}
```

Compare to `scheduleGenerateAndReload()`, which correctly handles the same
situation:
```java
} else {
    pendingFullReload.set(true);  // ← ensures a full rebuild happens after current run
}
```

The result: slot B's texture file in the resource pack directory is **never
updated** during this session. The player sees slot B with its old texture
(or a purple/black checkerboard for a new slot) until they disconnect and
reconnect, which triggers a full pack regeneration.

**The solution:** Mirror the `scheduleGenerateAndReload()` else-branch pattern
in `scheduleSingleSlotReload()`:

```java
} else {
    pendingFullReload.set(true);  // add this line
}
```

This ensures that when a single-slot update arrives while a generation is
already in progress, a full pack rebuild is queued to run immediately after
the current generation completes — catching both slots.

**The experience:** Player retextures two blocks quickly → both textures appear
correctly without needing to reconnect. Resource pack reloads once, not twice.

**Edge cases:**
- `pendingFullReload` is already checked at the end of the generator thread:
  after the current `generateRunning` task finishes, if `pendingFullReload` is
  true, it triggers `scheduleGenerateAndReload()`. The chain already works
  correctly — this fix just adds the missing `set(true)` to make it kick in.
- Setting `pendingFullReload = true` means a full pack rebuild runs instead of
  a single-slot update. This is slightly heavier but correct — single-slot
  updates are an optimization, not a correctness guarantee.

**Files:** `CustomBlocksClient.java` (`scheduleSingleSlotReload()` else branch, ~line 860 — add `pendingFullReload.set(true)`)

---

### 7.42 Reference equality in SlotManager.update() triggers unnecessary disk writes every edit

**The problem:** Every time a block's texture is changed, `SlotManager.update()` compares
the old and new textures using `!=` (reference equality — `old.texture != updated.texture`).
Because `SlotData` is immutable and `withTexture()` always creates a new `byte[]` array,
these are always different object references — the comparison always returns `true`.
**Every update, on every call, triggers a disk write for all 6 face texture files, even if
nothing actually changed.** During normal live editing (color picker, animation), this
produces 6+ disk writes per click. With rapid editing at 5-10 clicks/second, the IO_EXECUTOR
single-thread queue accumulates hundreds of pending writes. The queue has no upper bound —
it can grow without limit. On a live server, this manifests as periodic server stutters
every 2-3 seconds and a slow accumulation of lag under the disk I/O backlog.

**Race condition:** The IO_EXECUTOR queue has no synchronization with `saveAllAsync()`.
If `saveAll` writes the JSON metadata file while the texture writes are still queued, a
crash during that window leaves the JSON pointing to texture files that don't exist yet.

**The solution:** Replace reference equality with content comparison using
`Arrays.equals()`:

```java
// SlotManager.java ~line 242
if (!Arrays.equals(old.texture, updated.texture)) {
    // ... schedule write
}
// ~line 250
if (!Arrays.equals(oldFace, newFace)) {
    // ... schedule write
}
```

This ensures disk writes only happen when texture content genuinely changed.

Additionally, add a soft bound to the IO_EXECUTOR queue: if more than 50 texture writes
are queued, log a warning and drop the oldest pending write for the same slot (the latest
wins). This prevents unbounded queue growth under load.

**The experience:** Live editing becomes smooth — no periodic server stutters from disk
I/O buildup. Block edit operations feel snappier on servers with many concurrent admins.

**Edge cases:**
- If both `old.texture` and `updated.texture` are `null`, `Arrays.equals(null, null)`
  returns `true` → no write triggered. Correct.
- If `old.texture` is `null` and `updated.texture` is a new PNG, `Arrays.equals` returns
  `false` → write triggers. Correct.
- The queue bound (50 entries) should be per-slot, not global: having 50 pending writes for
  50 different slots is fine. 50 writes for the same slot means only the last matters.

**Files:** `SlotManager.java` (~lines 242, 250 — replace `!=` with `!Arrays.equals()`;
add queue bound check)

---

### 7.43 Share code collision silently overwrites blocks in the cloud

**The problem:** The share code system (CB~ codes used by `/cb share`) generates codes
by taking the first 12 bytes of a SHA-256 hash and mapping them to a 68-character alphabet
(`A-Z a-z 0-9 !@#$%&`). The cloud worker's POST /share endpoint does **no existence check**
before calling `env.BLOCKS.put(hash, …)` — any upload silently overwrites whatever is
stored under that hash. For identical block data this is harmless (same content → same
hash, overwrite is idempotent). For a true hash collision (two different blocks producing
the same 12-byte SHA-256 prefix) the first block is permanently lost.

The collision math: 68^12 ≈ 6.9 × 10^21 possible codes. The birthday attack threshold
is ~√(6.9 × 10^21) ≈ 83 million uploads before the first collision would be expected.
At the mod's scale this risk is essentially zero. The real issue is the unconditional PUT
— if an existence check were added it would at minimum make the overwrite explicit and
auditable, and would protect against any future weakening of the hash truncation.

**The solution:** Add collision detection in the cloud worker before overwriting:

```javascript
// cloud-vault-worker/src/index.js — POST /share
const existing = await env.BLOCKS.get(hash);
if (existing) {
    // Collision detected — generate a suffix
    const newHash = hash + "_" + Date.now().toString(36);
    // Store under new hash, return new code to client
    return json({ ok: true, hash: newHash, code: `CB~${newHash}` }, 201);
}
```

On the client side, always save the code returned by the server (not the locally computed
hash) as the canonical code for the block.

**The experience:** Sharing blocks is reliable. No silent data loss from hash collisions.
The user always gets back a code that retrieves exactly what they uploaded.

**Edge cases:**
- If the suffix approach is used, share codes can be longer than 12 characters. The import
  command must handle variable-length codes (already does, since it strips the `CB~` prefix
  and uses the remainder as a lookup key).
- The collision check adds one KV read per upload. At 10 uploads/minute per user (rate
  limit), this is negligible.
- A truly paranoid implementation would use a UUID or a longer hash. For the mod's scale,
  collision detection + suffix is sufficient.

**Files:**
- `cloud-vault-worker/src/index.js` (POST /share handler — add existence check before PUT)
- `CustomBlockCommand.java` (`cmdShareBlock()` — use server-returned code, not locally
  computed hash, as the canonical share code)

---

### 7.44 Cloud worker crashes permanently if one KV entry has corrupt JSON

**The problem:** The cloud marketplace's rate-limit check reads a JSON record from
Cloudflare KV and parses it with `JSON.parse(raw)` at line 39 — with no try-catch.
If that KV entry ever contains invalid JSON (from a prior crash mid-write, a race
condition, or a malicious upload), every subsequent call to `checkRateLimit()` throws
an uncaught exception. The entire POST /share endpoint returns HTTP 500 for all users
until the corrupt entry expires (up to 60 seconds) or is manually deleted.

A targeted attacker who can cause a corrupt write to a specific rate-limit KV key can
effectively take down the cloud upload endpoint for all users from that IP range for
a rolling 60-second window.

**The solution:** Wrap the `JSON.parse` call in a try-catch and treat corrupt entries
as a fresh rate-limit window:

```javascript
// cloud-vault-worker/src/index.js ~line 39
if (raw) {
    try {
        const rl = JSON.parse(raw);
        if (now < rl.reset) {
            if (rl.count >= RATE_LIMIT_MAX) return true;
            await env.BLOCKS.put(key, JSON.stringify({ count: rl.count + 1, reset: rl.reset }), ...);
            return false;
        }
    } catch {
        // Corrupt entry — treat as fresh window (best-effort protection)
        await env.BLOCKS.delete(key);
    }
}
```

Apply the same fix to any other `JSON.parse` call in the worker that lacks a try-catch.

**The experience:** Corrupt KV entries no longer take down the cloud endpoint. The worst
case is one user's rate-limit window resets unexpectedly — a minor, acceptable trade-off
vs. a global service outage.

**Edge cases:**
- `env.BLOCKS.delete()` is async. If the delete fails (KV service error), the next
  request hits the corrupt entry again. Add a fallback: if delete fails, proceed as
  if the window is fresh (return false, don't try to parse again).
- Log corrupt entries to the worker's error log so the server owner can investigate
  if it happens repeatedly.

**Files:** `cloud-vault-worker/src/index.js` (~line 39 — add try-catch around
`JSON.parse(raw)` in `checkRateLimit()`)

---

### 7.45 Silent GUI sounds — root cause under investigation ⚠️ DIAGNOSIS CORRECTED

**The problem:** Several GUI actions produce no audio feedback. The original diagnosis
(this item was written with "missing `.value()`") was **incorrect after code verification**.

Code review shows:
- `BLOCK_ANVIL_USE`, `ENTITY_EXPERIENCE_ORB_PICKUP`, `BLOCK_AMETHYST_BLOCK_CHIME` and
  similar constants at the listed lines are **bare `SoundEvent`** — they compile correctly
  as-is and do NOT need `.value()`.
- The only constants in MC 1.21.1 that require `.value()` are `BLOCK_NOTE_BLOCK_*` types
  (which are `RegistryEntry<SoundEvent>`). All NOTE_BLOCK calls in the codebase already
  use `.value()` correctly (lines 5777, 6304, 6334, 6452 in GuiManager.java; line 1405
  in CustomBlockCommand.java).

**Silent sounds are real (user-reported) but the cause is unknown.**

Candidates to investigate:
- `player.playSound()` vs `world.playSound()` — some server-side calls may not propagate
  correctly depending on the MC version's internal routing
- Sound category MASTER vs PLAYERS — may be muted by client volume settings
- Permission or world state checks that return early before the sound line
- The sounds are playing but at wrong coordinates (behind a wall, different dimension)

**Action required:** Player needs to confirm which specific actions are silent so the
exact call site can be debugged. Then check: does adding logging just before the
`playSound()` confirm the code path is reached? If yes, the sound plays server-side but
client isn't hearing it.

**Files:**
- `CustomBlockCommand.java` (~line 1389)
- `GuiManager.java` (~lines 1091, 1519, 2064, 2114, 6289, 6319, 6422, 6437)
- `ColorSquareItem.java` (~line 214)

---

### 7.46 Face texture updates silently dropped during rapid multi-face editing

**The problem:** When an admin rapidly changes multiple faces on a block (top, then
north, then east within a few hundred milliseconds), some face updates are silently
lost. The `TextureQueue` deduplication logic uses a key of `slotIndex:action:face`.
Under concurrent access — the queue's `drain()` running every server tick while
`enqueue()` is called from packet handlers — a race exists where `latest.remove()`
in `drain()` removes a newly-enqueued payload that was just added. That payload is
never sent to the client.

The result: after a burst of face edits, the block shows mixed textures — some faces
have the new color, some have the old one. No error. The admin has to manually redo
each affected face.

**The solution:** Use an atomic compare-and-remove pattern to eliminate the race. After
dequeuing a payload in `drain()`, only remove it from the `latest` map if the map
still contains the exact same object (not a newer replacement):

```java
// TextureQueue.java — drain() method
if (p instanceof SlotUpdatePayload sup) {
    latest.remove(dedupeKey(sup), sup);  // Only removes if value is still 'sup'
    // Note: ConcurrentHashMap.remove(key, value) is already atomic
}
```

The current code uses `latest.remove(dedupeKey(sup))` (key-only remove), which also
removes any newer payload that replaced `sup` in the map. Changing to the
`remove(key, value)` form (which `ConcurrentHashMap` supports atomically) eliminates
the race entirely.

**The experience:** Multi-face edits always arrive complete. Rapid color changes across
different faces all stick. No more "redo the north face" after a fast edit session.

**Edge cases:**
- If `drain()` and `enqueue()` still interleave in an edge case, the new payload (`p4`)
  remains in both the queue and the `latest` map and will be sent on the next tick.
  Maximum delay: 50ms (one tick). This is acceptable.
- This fix has no impact on throughput — `ConcurrentHashMap.remove(key, value)` is O(1).

**Files:** `TextureQueue.java` (`drain()` method — change `latest.remove(key)` to
`latest.remove(key, payload)`)

---

### 7.47 File deletion results ignored — stale texture and mcmeta files accumulate permanently

**The problem:** `ResourcePackGenerator.java` calls `.delete()` on files in dozens of
places (lines 168, 175, 177, 236, 241, 596, 603, 643, 648, and throughout
`cleanupStaleSlotFiles()`). The `File.delete()` method returns a boolean indicating
whether the deletion succeeded — but every call in this file ignores the return value.

If a file can't be deleted (disk full, permissions issue, file locked by antivirus on
Windows), the code continues silently as if it was deleted. The old file remains on disk.
On the next resource pack load, Minecraft reads the stale file: old animation timing files
cause blocks to animate at wrong speeds, stale blockstate files reference deleted models,
and orphaned texture files waste disk space and confuse the pack structure.

**The solution:** Replace all ignored `file.delete()` calls with a helper that logs
failures:

```java
private static void safeDelete(File f, String context) {
    if (f.exists() && !f.delete()) {
        CustomBlocksMod.LOGGER.warn(
            "[CustomBlocks] Could not delete stale file: {} ({})", f.getAbsolutePath(), context);
    }
}
```

Replace every `if (f.exists()) f.delete()` call site with `safeDelete(f, "description")`.
In `cleanupStaleSlotFiles()`, log each failed deletion and continue — don't silently skip.

**The experience:** Stale files are always cleaned up or the admin sees a log warning
explaining why they weren't. Animation timing issues from stale mcmeta files are
eliminated.

**Edge cases:**
- On Windows, files that are currently open (e.g., Minecraft has the texture loaded)
  cannot be deleted. The warning log tells the admin. The fix is for them to restart
  the client to release the file lock.
- If `cleanupStaleSlotFiles()` fails to delete a large number of files (corrupted
  resource pack folder), a single consolidated warning is better than hundreds of
  individual warnings. Add a counter: log `[CB] Could not delete N stale files — check
  resource pack folder permissions` if more than 5 deletions fail.

**Files:** `ResourcePackGenerator.java` (all `.delete()` call sites — replace with
`safeDelete()` helper; add helper method at bottom of class)

---

### 7.48 Packet protocol has no version field — mod updates disconnect all older clients

**The problem:** Every network packet the mod uses (`SlotUpdatePayload`,
`FullSyncPayload`, `SyncRequestPayload`, etc.) has no version number. The packet codec
reads fields in a fixed binary sequence. If a future mod update adds, removes, or
reorders a field, old clients reading new packets (or new clients reading old packets)
will:
1. Read bytes from the wrong field offset
2. Fail to parse the packet
3. Disconnect with `Internal Exception: io.netty.handler.codec.DecoderException`

There's no graceful "please update your mod" — just a hard crash-disconnect.

**The solution:** Add a version byte at the start of every packet codec, read on the
receiving side before any other fields:

```java
// SlotUpdatePayload encoder
buf.writeByte(1);  // protocol version
buf.writeString(value.action());
// ... rest of fields

// SlotUpdatePayload decoder
int version = buf.readByte();
if (version > SUPPORTED_VERSION) {
    // Newer server — read gracefully with defaults for unknown fields
    CustomBlocksMod.LOGGER.warn("[CB] Server packet version {} > client version {}", version, SUPPORTED_VERSION);
}
```

For the current mod (v1), all packets get version = 1. When fields are added in v2,
the decoder reads the version byte first and falls back gracefully if it sees a version
it doesn't support (skips remaining bytes, uses defaults).

**The experience:** Players with an older mod version on a newer server get a clear
branded message: `[CB] Server is running a newer version of CustomBlocks. Some blocks
may not display correctly. Please update your mod.` instead of a hard disconnect.

**Edge cases:**
- If the version is much higher than expected (version 99 on a v1 client), graceful
  degradation isn't always possible. The client logs a warning and disconnects cleanly
  with the "please update" message rather than crashing.
- The version byte adds 1 byte overhead to every packet. At 8 packets/tick and 584
  blocks, that's 584 extra bytes per full sync — negligible.
- `SyncRequestPayload` is empty (no fields). Adding a version byte there still
  future-proofs it.

**Files:**
- `SlotUpdatePayload.java` (add version byte to encoder/decoder)
- `FullSyncPayload.java` (add version byte to encoder/decoder)
- `SyncRequestPayload.java` (add version byte for future-proofing)
- All other payload classes (AnimSettingsPayload, ChunkedTexturePayload,
  OpenAnimGuiPayload, RpPausePayload, SyncCompletePayload)

---

### 7.49 Cloud vault has no read rate limiting — full enumeration and data theft possible

**The problem:** The cloud marketplace POST /share endpoint is rate-limited to 10
uploads per 60 seconds. But GET /share/:hash and GET /market have **no rate limiting
at all**. Anyone can send unlimited GET requests:

- `GET /market?limit=50&cursor=...` — paginate through the entire database with no
  throttle, extracting every block hash ever uploaded
- `GET /share/{hash}` — for each extracted hash, download the full block JSON
  (including texture bytes)

Within minutes, an attacker can enumerate and download the entire cloud vault:
every block, every texture, every creator's work — with no authentication required
and no log of who did it.

**The solution:**

1. Rate-limit GET endpoints to 100 requests per 60 seconds per IP (10x the POST limit
   to allow normal browsing):

```javascript
// Apply to GET /market and GET /share/:hash
if (request.method === "GET") {
    const limited = await checkRateLimit(env, clientIp, 100, 60);
    if (limited) return json({ error: "Rate limit exceeded" }, 429);
}
```

2. Add a `cursor` blacklist: after a client has paginated through more than 500 market
   entries in one session, require a 60-second cooldown before more pagination is
   allowed.

3. Log all GET /share/:hash requests with IP and timestamp to a separate KV namespace
   (`AUDIT_LOG`) for investigation if bulk downloads are detected.

**The experience:** Normal players browsing the marketplace are unaffected (100
requests/minute is far more than normal browsing). Bulk scrapers are throttled and
logged.

**Edge cases:**
- Cloudflare Workers automatically rate-limit to ~1000 requests/second per worker
  instance. This is a soft ceiling, not a substitute for application-level rate limits.
- IP-based rate limiting can be bypassed with IP rotation. For stronger protection,
  a token-based system (require a mod API key) would be needed. That's a larger
  infrastructure change — the rate limit is a practical first step.
- The audit log KV namespace incurs a small cost per write. Limit audit log entries
  to one per IP per minute to avoid runaway writes.

**Files:** `cloud-vault-worker/src/index.js` (add rate limit check to GET handlers;
add audit logging for GET /share/:hash)

---

### 7.50 Market listing crashes on any KV service error — no fallback or partial results

**The problem:** The GET /market endpoint fetches all block metadata entries in parallel
using `Promise.all()`. Each entry's `JSON.parse()` call **is** already wrapped in a
per-entry try-catch that returns `null` on failure. However, the `await env.BLOCKS.get()`
call above it is **not** — if a KV fetch throws (Cloudflare KV service temporarily
unavailable, timeout, permission error), the async function throws before reaching the
try-catch, the entire `Promise.all()` rejects, and the endpoint returns HTTP 500.
Players trying to browse the marketplace see a generic error with no explanation and no
way to recover.

**The solution:** Wrap each individual KV fetch inside the `Promise.all()` in a
try-catch, returning `null` for failed entries:

```javascript
const entries = await Promise.all(
    listed.keys.map(async (k) => {
        try {
            const raw = await env.BLOCKS.get(k.name);
            if (!raw) return null;
            return JSON.parse(raw);
        } catch {
            return null;  // Skip this entry; partial results are better than a 500
        }
    })
);
```

If more than 50% of entries fail (widespread KV outage), return a friendly error:
```json
{ "error": "Marketplace is temporarily unavailable. Please try again shortly.", "partial": true }
```
with HTTP 503 (Service Unavailable) instead of 500.

**The experience:** Brief Cloudflare KV hiccups result in a partial marketplace listing
(some blocks missing) rather than a complete failure. Full outages show a clear "try
again later" message instead of a raw 500.

**Edge cases:**
- `Promise.allSettled()` is an alternative to `Promise.all()` that never rejects.
  Consider using it for more expressive intent, though the try-catch approach is
  equivalent.
- If the `listed.keys` KV list call itself fails, the outer handler's try-catch
  returns 500. That's acceptable — if list() fails, there's nothing to return.

**Files:** `cloud-vault-worker/src/index.js` (GET /market `Promise.all()` block —
add per-entry try-catch and 503 fallback)

---

### 7.51 Keep-alive mixin uses non-thread-safe ArrayList across server and network threads

**The problem:** `ServerKeepAliveGraceMixin.java` stores pending keep-alive IDs in
a plain `ArrayList`. This list is accessed from two different threads:
- The **server tick thread** calls `baseTick()` → calls `.add()` and `.size()`
- The **Netty network thread** calls `onKeepAlive()` → calls `.contains()` and `.clear()`

`ArrayList` is not thread-safe. When `.add()` is called on the server thread while
`.contains()` is iterating on the network thread, Java's `ArrayList` detects the
concurrent modification and throws `ConcurrentModificationException`. This immediately
disconnects the player with `Internal Exception: java.util.ConcurrentModificationException`.

The disconnect is random — it occurs more frequently with high-latency players and
under server load (more keep-alive packets queue up, larger list, more iteration time,
higher chance of collision with add). With multiple players online, 2-5 random
disconnects per minute are possible.

**The solution:** Replace `ArrayList` with `CopyOnWriteArrayList`, which is designed
for exactly this access pattern (rare writes, frequent reads, no need for locks):

```java
// ServerKeepAliveGraceMixin.java ~line 51
@Unique private final List<Long> customblocks$pendingKeepAlives = new java.util.concurrent.CopyOnWriteArrayList<>();
```

One line change. `CopyOnWriteArrayList.contains()` is safe to call while `add()` is
running on another thread. The list size is small (<30 entries at max) so the
copy-on-write overhead is negligible.

**The experience:** The random "Internal Exception: ConcurrentModificationException"
disconnects stop entirely. Players — especially friends with higher latency — stay
connected.

**Edge cases:**
- `CopyOnWriteArrayList.clear()` is an O(1) write. `contains()` during a concurrent
  `clear()` will either see the old list (safe) or the new empty list (also safe —
  returns false, keep-alive not acknowledged, will be retried). No data corruption.
- If the pending list ever grows to 30+ entries (30+ seconds of no keep-alive
  responses), the disconnect logic (`disconnect(timeout)`) fires. That logic is correct
  and unaffected by this change.

**Files:** `ServerKeepAliveGraceMixin.java` (~line 51 — change `ArrayList` to
`CopyOnWriteArrayList`)

---

### 7.52 Cloud upload timeout hardcoded at 5 seconds — config setting has no effect

**The problem:** `CustomBlocksConfig.java` has a `downloadTimeoutSeconds` setting
(default 15). The config description implies it controls all network timeouts in the
mod. But cloud vault operations (block import via `/cb importblock`, cloud upload in
`GuiManager`) use hardcoded 5-second timeouts in their `HttpClient` builders, ignoring
the config value entirely.

On a slow or high-latency network, 5 seconds is not enough for a cloud round-trip. The
import fails with a timeout error. The admin increases `downloadTimeoutSeconds` to 30.
Nothing changes — cloud imports still fail at 5 seconds. The admin has no way to fix
this without modifying source code.

**The solution:** Replace every hardcoded `Duration.ofSeconds(5)` in HTTP client
builders with `Duration.ofSeconds(CustomBlocksConfig.downloadTimeoutSeconds)`:

```java
// CustomBlockCommand.java ~line 37
java.net.http.HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(CustomBlocksConfig.downloadTimeoutSeconds))
    .build();

// GuiManager.java ~line 47
java.net.http.HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(CustomBlocksConfig.downloadTimeoutSeconds))
    .build();
```

Affected locations (all hardcoded to 5s):
- `CustomBlockCommand.java:37` (HttpClient creation)
- `CustomBlockCommand.java:1336` (fetchCloudShareJson request timeout)
- `GuiManager.java:47` (HttpClient creation)
- `GuiManager.java:6471` (request timeout)

`ImageProcessor.java:184` and `:280` already use `CustomBlocksConfig.downloadTimeoutSeconds`
correctly — keep those as-is.

**The experience:** Admins on slow networks configure `downloadTimeoutSeconds = 30` and
cloud imports work. The config setting does what it says it does.

**Edge cases:**
- `downloadTimeoutSeconds = 0` would mean infinite timeout. Add a minimum clamp:
  `Math.max(5, CustomBlocksConfig.downloadTimeoutSeconds)` to prevent hanging forever.
- The `HttpClient` instances in `CustomBlockCommand` and `GuiManager` are created as
  static fields (created once at class load). They won't pick up a runtime config
  change without a server restart. This is acceptable — document it in the config
  tooltip: `Changes take effect on restart.`

**Files:**
- `CustomBlockCommand.java` (~lines 37, 1336)
- `GuiManager.java` (~lines 47, 6471)

---

## Phase 8 — Cleanup

### 8.2 Permission model has only two levels — insufficient for real server needs

**The problem:** `CustomBlocksConfig.java` has exactly two permission fields:

```java
public static volatile int permissionLevelAdmin = 2;  // create/delete/edit
public static volatile int permissionLevelUse   = 0;  // give/open GUI
```

This means ALL administrative actions (create, delete, rename, retexture,
setglow, setshape, etc.) share a single `permissionLevelAdmin=2` threshold.
There is no way to allow a player to retexture blocks (low-risk) without
also giving them the ability to delete blocks (high-risk). Any player at
OP level 2 can delete blocks — there is no granular control.

The practical result on a server with trusted-but-not-admin players: you
either give them level 2 (and risk deletions) or give them nothing. There's
no middle ground.

**The solution:** Split the monolithic `permissionLevelAdmin` into separate
levels per action category:

```java
public static volatile int permissionLevelCreate  = 2;  // /cb create, /cb clone
public static volatile int permissionLevelDelete  = 2;  // /cb delete, /cb bulkdelete
public static volatile int permissionLevelEdit    = 2;  // rename, retexture, setglow, etc.
public static volatile int permissionLevelUse     = 0;  // give, open GUI (unchanged)
```

This allows a server owner to say "players at level 1 can edit but not delete" by setting `permissionLevelEdit=1` and `permissionLevelDelete=2`.

**The experience:** Server owner has actual control over who can do what, without
giving untrusted players delete access just to allow them to retexture.

**Files:** `CustomBlocksConfig.java` (split permissionLevelAdmin → 3 fields),
`PermissionHelper.java`, `CustomBlockCommand.java` (use correct field per action),
`GuiManager.java` (permission GUI — show all three fields with descriptions)

---

### 8.3 SpotBugs static analysis permanently disabled — entire class of bugs escapes CI

**The problem:** `build.gradle` disables SpotBugs on Java versions above 17 due to
an ASM compatibility issue with Java 21 (`SpotBugsTask` can't instrument classes
compiled with `--release 21`). The result: no static analysis tool scans for null
pointer dereferences, resource leaks, ignored return values, or unsafe synchronization.
The entire quality gate that would automatically catch many of the bugs in this plan
is silently skipped on every build and CI run.

SpotBugs would have caught:
- Reference equality on byte arrays (item 7.42)
- Ignored `File.delete()` return values (item 7.47)
- Null dereferences in texture loading (items 1.23, 7.37)
- Unchecked exception handling (item 1.24)

**The solution:** Replace the disabled SpotBugs tasks with a compatible alternative
for Java 21. Two options:

*Option A — Upgrade SpotBugs:* SpotBugs 4.8+ added Java 21 bytecode support. Update
`build.gradle` to use `id 'com.github.spotbugs' version '6.0.x'` (which bundles
SpotBugs 4.8+). Remove the disable block.

*Option B — Add PMD rules for the same detections:* PMD already runs and supports
Java 21. Add rules to `config/pmd/ruleset.xml` that cover the categories SpotBugs
handled:
- `DontUseFloatTypeForLoopIndices` → already in PMD
- `ReturnEmptyCollectionRatherThanNull` → catches many null-return bugs
- `UseEqualsToCompareStrings` → catches the reference equality class of bugs
- `CloseResource` → catches resource leak bugs

Option A is preferred — SpotBugs's interprocedural analysis catches bugs PMD misses.
Option B is the fallback if SpotBugs 6 introduces other incompatibilities.

**The experience:** No direct player-visible change. CI catches more bugs before they
reach the server. The quality improvement is invisible but cumulative — fewer bugs
ship, fewer players get disconnected or lose data.

**Edge cases:**
- SpotBugs 6 may flag existing code issues that were silently ignored. These should
  be addressed (suppress with `@SuppressFBWarnings` only where genuinely intentional)
  rather than blanket-suppressing the entire scan.
- The `@SuppressFBWarnings` annotation requires the `spotbugs-annotations` dependency
  in `build.gradle`. Add it as `compileOnly`.
- CI must be updated to treat SpotBugs warnings as errors (`effort = 'max'`,
  `reportLevel = 'low'`) once the initial cleanup pass is done.

**Files:** `build.gradle` (remove Java 21 SpotBugs disable block; update to SpotBugs
plugin 6.x; add `spotbugs-annotations` compileOnly dependency),
`config/pmd/ruleset.xml` (add supplemental rules if using Option B)

---

## Summary

| Phase | Items | Player feels... |
|-------|-------|----------------|
| 1 — Fix Broken | 24 | "Everything works out of the box" |
| 2 — Bulk Hub | 3 | "I can change 100 blocks in 3 clicks" |
| 3 — Color Overhaul | 8 | "Colors are click-to-use, not type-to-use" |
| 4 — Search | 4 | "I can find any block instantly" |
| 4A — Image Processing | 9 | "My images actually look good as blocks" |
| 4B — Anim/Shape/Face | 3 | "Creative tools that feel like creative tools" |
| 4C — Showcase | 8 | "My blocks have a museum display" |
| 5 — User Experience | 24 | "This mod anticipates what I need" |
| 6 — Consistency | 4 | "Everything looks like it belongs together" |
| 7 — Performance | 36 | "Fast, persistent, rewarding" |
| 8 — Cleanup | 2 | "No dead weight" |
| **Total** | **124** | |

---

## What's NOT in This Plan

These were considered and deliberately excluded:

- **New block types** (furniture, slabs) — out of scope, stabilize first
- **Plugin API** — premature, the mod needs to be finished before being extensible
- **Per-face animation** — niche, not a pain point anyone has reported
- **True 3D custom models** — massive scope, v4+
- **YCbCr / advanced color science** — v4+ after the basics work perfectly
- **UV manipulation tools** — v4+ after face editing is polished
- **Cloud Vault marketplace overhaul** — needs its own plan and infrastructure
- **Cross-server cloud sharing** — infrastructure project, not a mod UX issue
- **Language translations** — voice modes already provide personality variety

---

## Implementation Order Recommendation

*Based on deep code audit + live server testing (2026-05-15). Root
causes confirmed. Order is by real-world impact — what hurts the most,
right now, on your actual server.*

---

### TIER 0 — Fix Before Anything Else

These bugs actively harm the server every session. Root causes confirmed.

| # | Bug | Root Cause | Confirmed? |
|---|-----|-----------|-----------|
| 1.10 | 15.9s startup freeze → Connection reset kicks | `postProcessLoadedSlots()` blocks server thread | YES — logs show **15881ms** freeze, player kicked |
| 1.17 | Pack built with 0 textures during startup → purple/black checkerboard | Pack builder runs before slot loading completes | YES — 3 pack builds observed, first 2 with 0 textures |
| 1.16 | maxSlots reduction silently destroys placed blocks | Block registry mismatch on restart | YES — happened Apr 27 |
| 1.13+1.14 | Friends NEVER see textures — pack port fully blocked | ALL external HTTP ports blocked by MCServerHost firewall | YES — `ERR_CONNECTION_REFUSED` on port 24454 while server running |
| 7.51 | Random player disconnects — ConcurrentModificationException in keep-alive mixin | ArrayList accessed from two threads simultaneously | YES — matches reported frequent connection resets |
| 1.20 | Random missing textures after join — drip-feed race + hash check only checks folder | Debounce timer fires before sync_done; partial pack passes hash check | YES — reported by user after joining own server |

**Fix order for Tier 0:**
```
1. Fix 1.10 first → moves texture loading async → startup freeze gone →
   connection resets stop → server becomes joinable immediately after restart

2. Fix 1.16 → add maxSlots guard + startup warning → blocks can never
   silently vanish again from a config change

3. Fix 1.13+1.14 → upload pack to Cloud Vault after every rebuild,
   use HTTPS Cloud Vault URL instead of blocked local HTTP server →
   friends see blocks for the FIRST TIME
```

---

### TIER 1 — Fix Soon (Data Safety)

These bugs can silently corrupt or lose data.

| # | Bug | Impact |
|---|-----|--------|
| 1.15 | Non-atomic texture/face/cache file writes | Crash during save = corrupted file |
| 7.11 | Shutdown save is async — doesn't wait for write to complete | Last-minute block edits lost on container kill |
| 7.8 | ~28 silent exception catches — critical failures hidden from admin |
| 7.9 | Thread safety (RESTORING HashSet, ANIM_PARAMS race, etc.) | Multiplayer data corruption |
| 7.27 | AnimSettingsPayload: no rate limit + no permission check + 65KB string → DoS + privilege escalation | Any client can spam disk I/O, network, heap; non-OP can modify all blocks |
| 7.29 | ResourcePackServer HTTP TOCTOU race + non-volatile fields → wrong Content-Length served | Clients get corrupted pack downloads silently |
| 7.21 | Animation settings TOCTOU race — stale undo entry pushed for deleted block | Undo stack corrupted after concurrent delete |
| 7.33 | ImageProcessor.download() no private-IP blocklist — SSRF: any player can probe internal services | Confirmed — no IP validation before HttpClient.send() |
| 7.34 | Full 20 MB body allocated before size check — multi-player concurrent downloads exhaust 3 GB heap | Confirmed — ofByteArray() buffers everything first |
| 7.35 | CB2! import code zip bomb — unbounded GZIP decompression → OOM crash via /cb importblock | Confirmed — no decompressed-bytes cap in decodeInlineImportCode() |
| 7.36 | Import hash used as filename without sanitization — path traversal reads/writes outside exports dir | Confirmed — Path.of("exports", userHash) with no normalize+startsWith guard |
| 7.37 | Texture .dat files written non-atomically — crash mid-write corrupts slot permanently | Confirmed — Files.write() directly, unlike slots.json which uses tmp+ATOMIC_MOVE |
| 7.42 | Reference equality in SlotManager.update() — 6+ unnecessary disk writes per edit, unbounded IO queue | `!=` on byte arrays always true for immutable SlotData copies |
| 7.43 | Share code: unconditional PUT overwrites existing cloud entries with no check | 68-char alphabet, 12-byte SHA-256 prefix; collision risk negligible but PUT is blind |
| 7.44 | Cloud worker crashes on corrupt KV entry — POST /share endpoint DoS | No try-catch around JSON.parse in checkRateLimit() |
| 7.46 | Face texture updates silently dropped during rapid multi-face editing | ConcurrentHashMap.remove(key) race in TextureQueue.drain() |
| 7.47 | Stale texture/mcmeta files accumulate — old animation timing causes wrong frame playback | File.delete() return value ignored everywhere |
| 7.48 | Packet protocol has no version field — mod update disconnects all older clients | Fixed binary codec with no version byte |

---

### TIER 2 — Fix Before Public Release

These bugs make the mod look unfinished or broken.

| # | Bug | Impact |
|---|-----|--------|
| 1.11 | Placing GIF block kicks the player | Most visible crash-adjacent bug |
| 1.12 | GIF >1MB crashes server (or causes kick) | Can't use large GIFs at all |
| 1.18 | Unknown `/cb` subcommand shows cryptic `[<__cb_unknown_tail>]` | Confirmed — user tried `/cb admin diag`, got raw parser hint |
| 7.7 | ~~maxUndoDepth memory safety — each entry clones all textures into RAM~~ **SUPERSEDED by item 1.28** | Actual defaults: default=**20**, max clamp=**100** (not 10,000/100,000 as originally claimed). Item 1.28 replaces in-RAM storage with disk-backed differential mutations (~10 KB RAM per stack). See item 1.28 for the full implementation spec. |
| 1.8  | Empty slot "missing model" log spam | Hundreds of errors per startup |
| 1.9  | Mipmap degradation warning (non-power-of-2 textures) | Visual quality issue |
| 7.30 | getExternalIp() 3s blocking HTTPS call per pack URL, no caching | Server thread stall on every pack URL display |
| 7.31 | Shape cache not cleared on block deletion | Minor unbounded VoxelShape leak |
| 7.37 | Texture .dat files written non-atomically | Crash during write corrupts slot permanently |
| 7.39 | triggerGlowUpdate() scans 274,625 positions on main thread per glow change | TPS spike on every glow edit; worsens with player count |
| 7.41 | scheduleSingleSlotReload() drops second texture write during rapid updates | Second slot shows stale/purple texture until reconnect |
| 1.22 | Join sync failure visible only in server console — player never notified in-game | Exception logged server-side only; player sees invisible blocks with no message |
| 1.24 | CbScreenHandler click exceptions freeze the GUI with no error or recovery | No try-catch around GuiManager.handleClick() |
| 7.45 | Silent GUI sounds — root cause under investigation (not a .value() issue) | Sounds compile correctly; actual cause of silence unknown, needs player testing |
| 7.52 | Cloud import timeout hardcoded at 5s — config downloadTimeoutSeconds has no effect | HttpClient created with Duration.ofSeconds(5) hardcoded |

---

### TIER 3 — UX Bugs (Fix After Stability)

| # | Bug | Impact |
|---|-----|--------|
| 1.1, 1.3 | Color detection and limited color set | Confusing but not breaking |
| 7.40 | GoldenHexagonItem.ROTATION_STATE allocated but never used | Dead code / harmless allocation waste |
| 1.21 | Admin uploads replacement texture — block marked fixed but still broken in-world | isBroken flips immediately without confirming pack regen completed |
| 1.23 | Null vs. missing texture indistinguishable in admin broken-blocks view | All broken states show same "BROKEN" label with no reason |
| 7.49 | Cloud vault has no read rate limiting — full enumeration possible | GET /market and GET /share/:hash have zero throttle |
| 7.50 | Market listing crashes if any KV fetch throws — JSON.parse guarded but KV.get is not | Promise.all() rejects on KV service error; outer try-catch missing on env.BLOCKS.get() |
| 8.3 | SpotBugs disabled on Java 21 — entire class of bugs escapes CI | ASM incompatibility with Java 21; SpotBugs 6+ fixes this |
| 4A.8 | Add image processing test suite | Required before changing image code |
| 4A | Image pipeline improvements | Better texture quality |
| 2 | Bulk operations hub | Major QoL |
| 3 | Color overhaul | Major QoL |

---

### TIER 4 — Features & Polish

```
Phase 5.1-5.7 (core UX)   ← polish what exists
Phase 4 (search)           ← essential at 100+ blocks
Phase 4B (anim/shape/face) ← improve existing tools
Phase 6 (consistency)      ← visual unification pass
Phase 4C (showcase)        ← new feature, build on solid foundation
Phase 5.8-5.23 (deep UX)  ← advanced quality of life
Phase 7.1-7.6 (perf)      ← optimization after features settle
Phase 8 (cleanup)          ← last
```

---

**Why this order:** Tier 0 bugs hit every player every session. Tier 1
bugs are silent — they don't crash anything but they can destroy data
and nobody notices until it's too late. Tier 2 bugs are the ones that
make a first-time player think the mod is broken. Features (Tier 3-4)
on top of a broken foundation are wasted work.

**Bottom line:** Fix stability → fix data safety → fix usability →
add polish → add features. In that order. No exceptions.

---

*v3 is about making everything that already exists feel FINISHED. The mod
has a massive feature set — 106 items to make every one of them a joy to use.*

---

## Appendix: Adding New Items to This Plan

When you discover a new bug, want a new feature, or have an idea during
testing — add it here using this template. Every new item should match the
same quality standard as everything above. Copy/paste this and fill it in.

### Template

```markdown
### X.X [Short, clear title — what it IS, not what it fixes]

**The problem:** Describe what's broken, missing, or frustrating from the
PLAYER's perspective. Not "the code does X" — instead "when I try to do Y,
Z happens and it's confusing/slow/broken." Include what the player expected
vs what actually happened. If you found this during testing, describe the
exact steps that led to the problem.

**The solution:** Exactly what to build. Specific enough that someone can
implement it without asking questions. Include:
- What the player sees (GUI layout, chat message, particle effect)
- What the player does (click, type, shift-click)
- What happens behind the scenes (algorithm, data flow, storage)

If it's a GUI, draw it:
```
┌─ Title ──────────────────────────────────────┐
│ [Button 1]  [Button 2]  [Button 3]           │
│                                               │
│ [Content area with description of each slot]  │
│                                               │
│ [§a Confirm]              [§c Cancel]         │
└──────────────────────────────────────────────┘
```

If it's a command, show the syntax with examples:
- `/cb example <required> [optional]` — what it does
- `/cb example marble` — concrete example with expected output

**The experience:** What does the player see, hear, and feel? What makes
this satisfying? What feedback confirms it worked? Think about:
- Visual: particles, GUI changes, item updates
- Audio: what sound plays on success/failure
- Information: what message confirms the action, what changes on screen

**Edge cases:** What could go wrong? What weird inputs might players try?
- What if the block doesn't exist?
- What if permissions are insufficient?
- What if there's a network timeout / async failure?
- What if the player does this while another operation is running?
For each edge case: what error message appears, and what does it tell
the player to DO about it?

**Why this matters:** One sentence on why this improves the mod experience.
Not "because the code needs it" — because the PLAYER needs it. What pain
does it remove? What delight does it add? If you can't answer this, the
item might not belong in the plan.

**Files:** Which source files need to change. Be specific:
- `FileName.java` (method or line range if known)
- New `FileName.java` if a new file is needed
```

### Quality checklist for new items

Before adding an item, check these boxes:

- [ ] **Player-first language** — described from the player's view, not
      the developer's. "The player sees..." not "The code does..."
- [ ] **Specific enough to implement** — someone could build this without
      asking you a single question about what you meant
- [ ] **GUI drawn out** — if it involves a GUI, there's an ASCII layout
      showing exactly what goes where
- [ ] **Commands shown with examples** — if it involves commands, syntax
      AND concrete examples with expected output
- [ ] **Edge cases covered** — at least 3 "what if" scenarios with
      graceful handling (error messages with solutions, not just failures)
- [ ] **Feedback defined** — what the player sees/hears on success AND
      failure. No silent operations.
- [ ] **Files listed** — specific source files that need changes
- [ ] **Fits a phase** — placed in the right phase, or creates a new
      phase with a clear theme. Not just dumped at the bottom.
- [ ] **Not a duplicate** — checked that no existing item already covers
      this (search the plan for keywords first)
- [ ] **"Why" is clear** — the reason this matters is obvious from reading
      the problem description. If it's not obvious, add a "Why this matters"
      line.

### Where to put new items

| If the item is... | Put it in... |
|-------------------|-------------|
| A bug found during testing | Phase 1 (Fix Broken) |
| A missing bulk operation | Phase 2 (Bulk Hub) |
| About colors, recoloring, palettes | Phase 3 (Color) |
| About finding/browsing blocks | Phase 4 (Search) |
| About image import quality | Phase 4A (Image Processing) |
| About animation, shapes, or faces | Phase 4B (Anim/Shape/Face) |
| About the showcase display block | Phase 4C (Showcase) |
| About making something feel better | Phase 5 (User Experience) |
| About visual consistency across GUIs | Phase 6 (Consistency) |
| About speed, memory, or persistence | Phase 7 (Performance) |
| About removing dead/unused code | Phase 8 (Cleanup) |
| Doesn't fit any phase | Create Phase 9+ with a clear theme name |

### Numbering

New items get the next available number in their phase:
- Phase 1 currently ends at **1.29** (items 1.25–1.29 added by forensic audit: SSRF, AnimSettings DoS, Sort Menu, Disk-backed Undo, Manager Class Tier Structure) → next item is **1.30** ⚠️ Note: appendix items below mistakenly used 1.25–1.29 — those numbers are now taken. Appendix items should be renumbered to 1.42+ when promoted.
- Phase 7 currently ends at 7.53 → next item is 7.54
- Phase 5 currently ends at 5.27 → next item is 5.28
- Phase 8 currently ends at 8.5 → next item is 8.6
- Phase 9 currently ends at 9.4 → next item is 9.5
- Phase 10 currently ends at 10.5 → next item is 10.6
- If you create a new phase, start at X.1

### Ideas parking lot

*Not ready for the plan yet, but don't want to forget them. Move items
from here into the main plan when they're fleshed out:*

<!-- Add raw ideas here as simple bullet points. When you're ready to
     promote one to a full plan item, write it up using the template
     above and move it to the correct phase. Delete it from here. -->

- (empty — add ideas as you find them)

---

> **⚠️ NUMBERING CONFLICT NOTE:** The items below numbered 1.25–1.29 were written BEFORE items 1.25–1.29 were inserted into Phase 1 (SSRF, AnimSettings DoS, Sort Menu, Disk-backed Undo, Manager Class Tier Structure). These appendix items use conflicting numbers. When promoting any appendix item to Phase 1, renumber it starting from **1.42** (since appendix items 1.30–1.41 are already in use). Do NOT reuse 1.25–1.29 — those numbers are taken by the forensic audit additions.

### 1.25 GIF upload crash (>1 MB kicks player and crashes server)

**The problem:** Uploading a GIF file larger than 1 MB causes the server to become unresponsive and kicks the uploading player. The player loses their work and has to rejoin.

**The solution:** Add `maxGifSizeMb` config field in `CustomBlocksConfig.java` (type: int, default: 2, range: 1–10, comment: "Maximum GIF file size in MB before upload is rejected"). In `ImageProcessor.java`, add a size check immediately BEFORE the call to `processAnimation()` (around line 89, after `isAnimatedImage()` returns true): `if (raw.length > CustomBlocksConfig.maxGifSizeMb * 1024 * 1024) { return ProcessResult.error("gif_too_large"); }`. The error propagates to the caller which shows the player an error message. No `processAnimation()` is ever called for oversized GIFs — the OOM is impossible.

Root cause evidence: `ImageProcessor.java:674` — `new ByteArrayInputStream(raw)` loads the ENTIRE GIF byte array into heap memory with no size check before processing. A 1 MB GIF can contain many frames; when decoded to raw pixels, each frame = width × height × 4 bytes, inflating to 50–100 MB of heap usage. `ImageProcessor.java:724` opens a second `ByteArrayInputStream(raw)` for a second scan pass — two full copies in memory simultaneously. `ImageProcessor.java:846` catches the `OutOfMemoryError` and returns null, but by then the heap is starved. `GuiManager.java:862` — the null-recovery handler tries to schedule work on the server thread, which is now under extreme GC pressure and cannot respond to the client keepalive. Client times out and is kicked.

**The experience:** Player uploads a 3 MB GIF → immediately sees in chat: `§c[CB] §fGIF too large (§c3.1§f MB). Max allowed: §f2§f MB. Compress it first or use a shorter animation.` No server lag, no kick, no crash. If under the limit, processing proceeds normally.

**Edge cases:**
- GIF exactly at the limit (2.0 MB exactly) → accepted and processed normally.
- Player tries to bypass by renaming a large GIF as .png → `isAnimatedImage()` detects the GIF header regardless of file extension, size check still applies.
- Admin sets `maxGifSizeMb = 0` → treat as "1" (minimum enforced by config validation).
- Server has very low RAM (under 512 MB heap) → consider lowering default to 1 MB; document this in config comment.

**Files:** `ImageProcessor.java` (lines 88–90, add pre-check before processAnimation call), `CustomBlocksConfig.java` (add maxGifSizeMb field), `CustomBlockCommand.java` (handle ProcessResult.error case in cmdAdd)

---

### 1.26 Unknown commands show no response at all (B3-4)

**The problem:** When a player types a completely unknown command like `/cb totally_fake_command`, absolutely nothing happens. No error, no suggestion, no message. The player stares at the chat bar and thinks the mod is frozen or broken.

**The solution:** The fallback handler in `CustomBlockCommand.java` must always produce output for any unrecognized command. The catch-all branch (currently silent) must send: `ChatHelper.error(source, "§7Unknown command '§f" + input + "§7'. Type §f/cb help §7to see all commands.")` — voice-aware, formatted correctly, no vanilla red error. The Did-You-Mean system only fires when the unknown command is lexically close to a known one (e.g., "setgow" → "setglow"). Completely novel strings fall through the fallback branch with no output — this must be fixed.

**The experience:** Player types `/cb blorp` → chat shows `§7Unknown command '§fblorp§7'. Type §f/cb help §7to see all commands.` (or equivalent in current voice mode). Response is immediate, friendly, and instructive.

**Edge cases:**
- Player types `/cb` with trailing spaces → normalized before matching, treated as root command (opens Feature Menu).
- Player types `/cb ?` → returns the same unknown command message, not a crash.
- Did-You-Mean fires AND the fallback fires → only Did-You-Mean message is shown (suppress the fallback when DYM has a suggestion).

**Files:** `CustomBlockCommand.java` (fallback/catch-all branch in command registration)

---

### 1.27 `/cb reload` only reloads blocks, not config, and breaks textures

**The problem:** Running `/cb reload` only reloads block data — it does NOT reload the config file, does NOT regenerate the resource pack, and does NOT push the updated pack to connected players. After reload, all custom block textures appear as purple/black checkerboard (missing texture) because the old resource pack on each client no longer matches the reloaded server data. The server owner had to: delete the RP folder from their PC, restart the launcher AND server, rejoin, and re-accept the resource pack — just to recover from a `/cb reload`.

**The solution:** Rewrite `cmdReload()` in `CustomBlockCommand.java` to perform a full reload sequence on a background thread: (1) `CustomBlocksConfig.reload()` — parse and apply config.json changes; (2) `SlotManager.loadAll()` — reload all block data from disk; (3) `ServerPackGenerator.generate()` — regenerate the resource pack ZIP with current block textures; (4) push the updated pack to all connected players using the existing pack-push mechanism. Each step logs progress. If any step fails, halt the sequence and report the exact failure. The full sequence must not block the server tick thread.

**The experience:** Admin types `/cb reload` → chat shows `§7[CB] Reloading config, blocks, and resource pack...` → 3–8 seconds later: `§a[CB] Reload complete. Config ✓ Blocks ✓ Resource pack pushed to §f<N>§a player(s).` All players seamlessly receive the updated resource pack without manual intervention. No purple/black textures.

**Edge cases:**
- A player is mid-GUI during reload → close their GUI gracefully before pack push, reopen to current page after push completes.
- Pack generation fails (disk full, corrupt slot data) → `§c[CB] Reload failed during pack generation: §f<error>. Blocks were reloaded but pack was NOT pushed. Players may see missing textures until the issue is fixed.`
- No players online → skip pack push, show `§a[CB] Reload complete. No players online — pack will be sent on next join.`
- Config file has JSON syntax errors → `§c[CB] Config reload failed: §fJSON syntax error at line <N>. Previous config remains active.`

**Files:** `CustomBlockCommand.java` (cmdReload method, ~line 3294), `CustomBlocksConfig.java` (add reload() method), `ServerPackGenerator.java` (ensure generate() is callable on demand), `NetworkManager.java` (pack-push to all players)

---

### 1.28 Color square/triangle variant fallback shows error instead of using base block

**The problem:** When a player right-clicks a block with a color triangle (e.g., the black triangle on "Fortnite Yellow" which has ID `fortnite_yellow`) and the target variant `fortnite_black` does not exist yet, the mod shows: `§cfortnite_black doesn't exist yet. Create it first with a matching triangle.` — and stops. The player has to stop, create the variant manually, then come back and try again. This is the opposite of helpful.

**The solution:** Implement the `colorSquareFallbackMode` config toggle (three values: `use_base`, `auto_create`, `error`). Default: `use_base`. When mode is `use_base`: if the target variant (e.g., `fortnite_black`) does not exist, the tool uses the base block (`fortnite`) as the starting texture and applies the color transformation to it, creating the variant automatically without interrupting the player. No message is shown — it simply works. Mode `auto_create` does the same but shows a brief action bar note. Mode `error` restores the old behavior (for admins who want strict variant control).

**The experience:** Player right-clicks `fortnite_yellow` with black triangle. `fortnite_black` does not exist. Mode is `use_base` (default). The tool silently uses `fortnite` as the base, creates `fortnite_black`, and the recolor proceeds as normal. The player sees the result instantly — no interruption, no error.

**Edge cases:**
- Base block also does not exist (no `fortnite` at all) → `§c[CB] §fBase block '§cfortnite§f' not found. Cannot create variant.`
- Mode is `auto_create` → action bar briefly shows `§7[CB] Created §ffortnite_black §7from base block.`
- Mode is `error` → original behavior: `§cfortnite_black doesn't exist yet. Create it first.`
- Player uses color square (not triangle) — same fallback logic applies.

**Files:** `ColorSquareItem.java` (resolveTargetId method), `ColorTriangleItem.java` (same), `CustomBlocksConfig.java` (add colorSquareFallbackMode field, default "use_base")

---

### 1.29 Holograms cannot be enabled from in-game — config file edit required

**The problem:** The hologram feature is disabled by default. The only way to enable it is to manually open `config/customblocks/config.json`, find `"hologramEnabled": false`, change it to `true`, save the file, and restart the server. There is no in-game toggle. The hologram item in the GUI even says: "Enable in /cb config to use holograms. Set hologramEnabled: true in config." — this instruction is useless since `/cb config` does not have a hologram toggle.

**The solution:** Two access points: (1) Config GUI → Appearance tab → Hologram item: click to toggle ON/OFF, saves immediately, applies without restart. (2) `/cb config hologram true` or `/cb config hologram false` command — sets the value, saves config, shows confirmation.

**The experience:** Admin opens Config GUI → Appearance tab → clicks Hologram item → item name changes from `§cHologram (Disabled)` to `§aHologram (Enabled)` → action bar shows `§a[CB] Holograms enabled.` → sound plays. OR: admin types `/cb config hologram true` → chat shows `§a[CB] Hologram enabled. Changes apply immediately.`

**Edge cases:**
- Hologram enabled but `hologramHeight` is 0 → holograms appear inside the block. Show warning: `§e[CB] Hologram height is 0. Set hologram-height above 0 in config for visible holograms.`
- Player tries `/cb config hologram true` without `canConfig` permission → `§c[CB] You don't have permission to change config settings.`
- Config file is read-only on disk → `§c[CB] Config save failed: file is read-only. Change the setting manually.`

**Files:** `CustomBlocksConfig.java` (hologramEnabled field), `CustomBlockCommand.java` (add hologram subcommand to config branch), `GuiManager.java` (Config GUI Appearance tab hologram toggle)

---

### 1.30 `/cb unfavorite` command behavior

> **⚠️ CORRECTION:** `/cb unfavorite` is **NOT registered** in `CustomBlockCommand.java` — confirmed by full-codebase grep; zero matches found. The prior "ALREADY IMPLEMENTED" claim was false. Since `FavoritesManager.java` also does not exist (see Files below), the entire favorites system — including `/cb unfavorite` — must be built from scratch.

**The problem:** There is no `/cb unfavorite` command and no favorites system. The lore text that says `§6★ Favorite §8— Press §fF §8to unfavorite` references a feature that does not exist. Players have no way to unfavorite blocks because the favorite system itself has not been implemented.

**The solution:** Verify that `/cb unfavorite <id>` provides clear feedback — if the block is already not in favorites, it should show a helpful message instead of silently toggling. Improve lore text to reference the correct command explicitly.

**The experience:** Player types `/cb unfavorite fortnite_yellow` → if favorited: chat shows `§7[CB] §f✗ §ffortnite_yellow §7removed from favorites.` → sound plays. If not favorited: chat shows `§7[CB] §ffortnite_yellow §7is not in your favorites.` No accidental favoriting.

**Edge cases:**
- Block ID does not exist → `§c[CB] §fBlock '§c<id>§f' not found.`
- Player has no favorites at all → `§7[CB] You have no favorites yet.`
- `/cb unfavorite` with no arguments → lists current favorites (same as `/cb favorite` with no arguments).

**Files:** `CustomBlockCommand.java` (register `literal("unfavorite")` in command tree, add `cmdUnfavorite()` method), new `FavoritesManager.java` (**DOES NOT EXIST** — confirmed by full-codebase grep; must be built from scratch with `addFavorite()`, `removeFavorite()`, `getFavorites()`, and persistence logic)

---

### 1.31 Color triangle/square custom color produces wrong color (always diamond-blue)

**The problem:** When a player runs `/cb customtriangle #111111` (or any hex code), the tool that is given always produces a diamond-colored (light blue/cyan) result when used on a block, regardless of the hex code entered. The player's intended dark color (`#111111` = near-black) is completely ignored. The feature is functionally broken.

**The solution:** Debug and fix the hex-to-pixel color mapping pipeline in the custom color tool creation code. The hex value `#111111` must produce a tool that, when used, recolors the background pixels to RGB `(17, 17, 17)` — near-black. The bug is likely in how the hex string is parsed or how the `targetColor` is stored in the item's NBT/components. Verify the full chain: hex input → RGB parse → stored in item → read during right-click → applied to pixels. Every step must be verified with a unit test.

**The experience:** Player runs `/cb customcolor square #111111` → receives a Color Square with target color `#111111`. Right-clicks a block → background pixels turn near-black (`RGB 17,17,17`). The result in the world matches the hex the player typed.

**Edge cases:**
- Player enters invalid hex `#ZZZZZZ` → `§c[CB] Invalid hex color '§f#ZZZZZZ§c'. Use format #RRGGBB.`
- Player enters 3-digit hex `#111` → expand to `#111111` automatically.
- Player enters hex with no `#` → add `#` automatically and proceed.
- Fully transparent result (hex `#00000000`) → `§c[CB] Fully transparent colors cannot be used. Use a hex with non-zero RGB.`

**Files:** `ColorSquareItem.java` (targetColor storage and read), `ColorTriangleItem.java` (same), `CustomBlockCommand.java` (hex parse in customcolor command)

---

### 1.32 Resource pack reload causes full-screen red overlay after every block change

**The problem:** After any block creation, edit, or retexture, the mod pushes an updated resource pack to the player. This triggers Minecraft's built-in resource pack reload animation — a full-screen red/dark overlay that lasts several seconds. During this time the player cannot see any action bar feedback, cannot interact with the world, and the GUI closes. For a mod that is supposed to feel instant and seamless, this is a severe UX problem.

**The solution:** Optimize the resource pack generation and push pipeline to minimize reload duration: (1) Use delta/incremental pack updates — only regenerate the textures that actually changed, not the full pack. (2) Compress the pack aggressively (PNG optimization, remove unused files). (3) Pre-generate the pack in the background immediately when a block is changed so it's ready before the push is triggered. (4) Where the Minecraft protocol allows, delay the push until the player closes their current GUI so the overlay does not interrupt active editing. Target: under 1 second total reload time.

**The experience:** Player creates a block → GUI closes → a brief `§7[CB] Syncing pack...` action bar message appears → under 1 second later → `§a[CB] Pack synced.` → the red screen flash, if it occurs at all, is too short to be disruptive. Normal gameplay resumes immediately.

**Edge cases:**
- Player is mid-anvil input when pack push triggers → delay push until anvil closes to avoid breaking input state.
- Pack generation fails (disk full) → `§c[CB] Pack sync failed: disk full. Block was saved but may not be visible until the issue is resolved.`
- Player has a slow connection → pack push may still take longer; show progress: `§7[CB] Syncing pack (large pack)...`

**Files:** `ServerPackGenerator.java` (delta generation), `NetworkManager.java` (push timing), `GuiManager.java` (delay push until GUI closes)

---

### 1.33 Cloud vault URL should not be user-configurable

> **⚠️ DUPLICATE — ALREADY ADDRESSED.** This is covered by the Critical Corrections table (Security — locked fields) and items 1.13/1.14. Retained for traceability only — do not implement separately.

**The problem:** The config file exposes `cloudShareUrl` as an editable field. This allows anyone with config access to redirect the mod to a different vault server — potentially an attacker's server that could receive block data, textures, or player information. The vault endpoint should be fixed in the mod source, not settable by server admins.

**The solution:** Remove `cloudShareUrl` from `CustomBlocksConfig.java`. Hardcode the vault URL in the source file that makes vault requests (`cloud-vault-worker/` or wherever the HTTP calls originate). Any existing config files that have `cloudShareUrl` will silently ignore the key after the next reload (Gson/Jackson ignores unknown fields by default).

**The experience:** No player-visible change. Admins who used to set this field will find it gone from the config on next reload. No error message is needed — the field is simply absent.

**Edge cases:**
- Admin had a custom vault URL → their custom routing stops working. Document this breaking change in the release notes.
- Future need for a configurable endpoint → add it back behind a build flag, not a runtime config.
- Config file has the old field → gracefully ignored on next load (no error).

**Files:** `CustomBlocksConfig.java` (remove `cloudShareUrl` field), source file that makes vault HTTP calls (remove reference to config field, use hardcoded constant instead)

---

### 1.34 SSRF vulnerability in ImageProcessor.download()

> **⚠️ DUPLICATE — ALREADY ADDRESSED.** This is fully covered by item 1.25 (SSRF — server fetches internal/private network URLs on OP command) and item 7.33. Retained for traceability only — do not implement separately.

**The problem:** `ImageProcessor.download()` accepts an arbitrary URL from player commands (`/cb create`, `/cb texture`, `/cb retexture`) and makes HTTP requests to that URL from the Minecraft server process. There is NO protection against private IP ranges. Any OP-level player can make the server HTTP-fetch:
- `http://127.0.0.1:PORT` (loopback — any port on the same machine)
- `http://10.0.0.0/8` or `http://172.17.0.1` (Docker bridge network — on MCServerHost this can reach the control panel)
- `http://169.254.169.254/latest/meta-data/` (AWS instance metadata service — leaks IAM credentials on cloud hosts)
- `http://192.168.x.x` (local network)
The server is confirmed to run on MCServerHost Docker, making the Docker bridge attack vector real and exploitable.

**The solution:** Before calling the HTTP client, resolve the hostname and check against a blocklist:
```java
InetAddress resolved = InetAddress.getByName(uri.getHost());
byte[] addr = resolved.getAddress();
// Block IPv4 private ranges
if (addr.length == 4) {
    int b0 = addr[0] & 0xFF, b1 = addr[1] & 0xFF;
    if (b0 == 127) throw new SecurityException("Loopback addresses are not allowed");
    if (b0 == 10) throw new SecurityException("Private network addresses are not allowed");
    if (b0 == 172 && b1 >= 16 && b1 <= 31) throw new SecurityException("Private network addresses are not allowed");
    if (b0 == 192 && b1 == 168) throw new SecurityException("Private network addresses are not allowed");
    if (b0 == 169 && b1 == 254) throw new SecurityException("Link-local addresses are not allowed");
    if (b0 == 0) throw new SecurityException("Unspecified address not allowed");
}
// Block IPv6 loopback
if (addr.length == 16 && resolved.isLoopbackAddress()) throw new SecurityException("Loopback addresses are not allowed");
// Only allow http:// and https://
if (!scheme.equals("http") && !scheme.equals("https")) throw new SecurityException("Only http/https URLs are allowed");
```
Player message on block: `§c[CB] That URL points to a private or restricted address. Only public URLs are allowed.`

**Files:** `ImageProcessor.java` (downloadImage method, before HttpClient.send())

---

### 1.35 RESTORING set is not thread-safe

> **⚠️ DUPLICATE — ALREADY ADDRESSED.** This is covered by item 7.9 (thread safety, specifically `GuiManager.java:215`). Retained for traceability only — do not implement separately.

**The problem:** `GuiManager.java` line 215: `private static final Set<UUID> RESTORING = new java.util.HashSet<>()`. This set is used as a guard to prevent double-open during ESC navigation. Both `handleEscBack()` and `openScreenFromGuiState()` read and write this set. These methods can be called from different threads (server tick thread vs. netty network thread). A plain `HashSet` is not thread-safe. Concurrent modification can corrupt the internal array, causing `NullPointerException`, infinite loops, or silent state corruption.

**The solution:** Replace with `ConcurrentHashMap.newKeySet()`:
```java
// Line 215 — replace:
private static final Set<UUID> RESTORING = new java.util.HashSet<>();
// With:
private static final Set<UUID> RESTORING = java.util.concurrent.ConcurrentHashMap.newKeySet();
```

**Files:** `GuiManager.java` (line 215)

---

### 1.36 Sort Blocks Menu is a silent fake feature

> **⚠️ FORENSIC CORRECTION + DUPLICATE.** This item contains inaccurate claims. Forensic audit found:
> - The string `"Sort preference applied"` does NOT appear anywhere in `GuiManager.java`
> - `GuiMode.java` has NO `SORT_BLOCKS_MENU` enum value
> - There is NO sort menu handler at lines 6514–6532 or anywhere in the codebase
>
> The correct finding is that NO sort menu exists at all — not even a fake/silent one. This is already addressed in the Critical Corrections table ("Sort Blocks Menu exists → No sort menu exists at all") and item 1.27 (which adds a proper sort system as a new feature). Retained for traceability only — do not implement separately. See item 1.27 for the correct sort implementation plan.

~~**The problem:** `GuiManager.java` SORT_BLOCKS_MENU handler (lines 6514–6532): the GUI displays three sort options (Alphabetical, by Clock/date, Oldest First) and when clicked, plays a success sound and sends `§aSort preference applied.`~~ **FALSE — no such code exists. See correction above.**

---

### 1.37 7 player state maps not cleaned on disconnect

**⚠️ CORRECTION:** `BULK_RECOLOR_COLOR`, `BULK_RECOLOR_SCOPE`, `BULK_RECOLOR_SCOPE_VALUE`, `BULK_RECOLOR_EXCLUDE`, `BULK_RECOLOR_SELECTED`, `PENDING_CATEGORIES`, and `BULK_ASSIGN_SELECTED` **DO NOT EXIST** in `GuiManager.java` — confirmed by full-codebase grep; zero matches found. These maps belong to features not yet implemented (Bulk Recolor Wizard, Category Management). The memory leak described below is a design requirement for when these features are built, not a fix to existing code: every new per-player map added to `GuiManager.java` MUST be cleaned up in `onPlayerDisconnect()` (line 267) to prevent permanent memory leaks.

**The problem:** When `GuiManager.onPlayerDisconnect()` (line 267) is extended to support new per-player state maps for the Bulk Recolor Wizard and Category Management features, it MUST clean them all up. These maps, if not cleaned:
- `BULK_RECOLOR_COLOR` — String per player
- `BULK_RECOLOR_SCOPE` — String per player
- `BULK_RECOLOR_SCOPE_VALUE` — String per player
- `BULK_RECOLOR_EXCLUDE` — String per player
- `BULK_RECOLOR_SELECTED` — Set\<String\> per player (the largest potential leak)
- `PENDING_CATEGORIES` — Map per player
- `BULK_ASSIGN_SELECTED` — Set\<String\> per player

On a server where players cycle regularly (e.g., 20 unique players per week), uncleaned maps grow without bound.

**The solution:** Add cleanup for all 7 maps in `onPlayerDisconnect()`:
```java
BULK_RECOLOR_COLOR.remove(uuid);
BULK_RECOLOR_SCOPE.remove(uuid);
BULK_RECOLOR_SCOPE_VALUE.remove(uuid);
BULK_RECOLOR_EXCLUDE.remove(uuid);
BULK_RECOLOR_SELECTED.remove(uuid);
PENDING_CATEGORIES.remove(uuid);
BULK_ASSIGN_SELECTED.remove(uuid);
```

**Files:** `GuiManager.java` (onPlayerDisconnect method)

---

### 1.38 SHARE_ALPHABET contains filesystem-unsafe characters

**The problem:** `CustomBlockCommand.java` lines 40–41: `SHARE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%&"`. The characters `!@#$%&` are problematic:
- `&` in a URL corrupts query parameter parsing (e.g., `CB~abc&def` → the `&def` is parsed as a second query parameter)
- `%` in a Windows path is interpreted as a batch variable prefix
- `#` in a URL is parsed as a fragment identifier, truncating the share code
- Share codes are used as export filenames: `hash.json` — Windows rejects filenames containing `%` and `&`

**The solution:** Replace SHARE_ALPHABET with alphanumeric only:
```java
private static final String SHARE_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
```
62 characters is sufficient for a 12-character hash to produce 6.2×10²¹ unique codes. Existing share codes using the old alphabet will continue to work if the decode path handles unknown characters gracefully (add a validation gate that rejects codes containing non-alphanumeric chars with a clear error message).

**Files:** `CustomBlockCommand.java` (lines 40–41, SHARE_ALPHABET constant)

---

### 1.39 CATEGORY_STATS crash for empty categories

**⚠️ CORRECTION:** The `CATEGORY_STATS` case **DOES NOT EXIST** in `GuiManager.java` — confirmed by full-codebase grep; zero matches found. There is no category stats view, no `blocks.get(totalBlocks - 1)` call, and no `IndexOutOfBoundsException` to fix. The feature must be built from scratch. The bug described below is a design requirement for the new category stats view, not a fix to existing code.

**The problem:** When the category stats GUI is built, it must guard against empty categories. A direct `blocks.get(totalBlocks - 1)` call with no bounds check will crash with `IndexOutOfBoundsException` when a category has zero blocks.

**The solution:** Add a null/empty check before the list access:
```java
if (blocks.isEmpty()) {
    send(player, "§e[CB] This category has no blocks yet.");
    handleEscBack(player);
    return;
}
```

**Files:** `GuiManager.java` (build new CATEGORY_STATS click handler; does not exist yet — add empty-list guard from the start)

---

### 1.40 hologramHeight config max exceeds AABB search ceiling

> **⚠️ CORRECTION:** `HologramManager.java` **DOES NOT EXIST** — confirmed by full-codebase grep; zero matches found. The hologram system has not been implemented. This bug applies to the hologram feature when it is built (Phase 9, item 2.9). The AABB ceiling constraint below is a design requirement for the new HologramManager, not a fix to existing code.

**The problem:** When `HologramManager.java` is implemented, it must search for hologram entities in an AABB when a block is removed. The AABB ceiling must be dynamic — if `hologramHeight` (a config float with no validated maximum) exceeds the search ceiling, the hologram ArmorStand will spawn above the ceiling and will NEVER be removed when the block is deleted. Orphaned hologram entities would accumulate on the server permanently.

**The solution:** When implementing `HologramManager.java`, make the AABB search ceiling dynamic: `blockPos.getY() + Math.ceil(hologramHeight) + 2`. Also clamp `hologramHeight` to a sensible maximum in config validation (e.g., 4.0f) to prevent extreme values.

**Files:** new `HologramManager.java` (implement onBlockRemoved with dynamic AABB ceiling), `CustomBlocksConfig.java` (add hologramHeight max clamp = 4.0f)

---

### 1.41 TextSanitizer regex corrupts legitimate question marks

> **⚠️ CORRECTION:** `TextSanitizer.java` **DOES NOT EXIST** — confirmed by full-codebase grep; zero matches found. This bug applies to the TextSanitizer when it is built. The regex design requirement below is a specification for the new class, not a fix to existing code.

**The problem:** When `TextSanitizer.java` is implemented, its mojibake-fix regex must NOT convert `?X` → `§X` for Minecraft formatting code characters. Any user-visible string containing a legitimate `?` followed by certain letters would be silently corrupted. Example: `"Is it ready? Absolutely!"` → `"Is it ready§aAbsolutely!"` because `?A` matches (`A` is in range A-F). This would affect hologram text, block names set via anvil, voice catalog strings, and any admin-configured text.

**The solution:** When implementing `TextSanitizer.java`, do NOT use `?X` as a mojibake pattern. Instead, detect the specific Windows-1252/UTF-8 mismatch sequences that actually occur in practice: `Â§` or `â€` prefix patterns. These are unambiguous indicators of encoding corruption and will never appear in legitimate user text.

**Files:** new `TextSanitizer.java` (does not exist — when building, use `Â§`/`â€` detection, not `?X` regex)

---

### Known Royal Directive Violations (Confirmed by Code Audit)

> **⚠️ NOTE:** The `SORT_BLOCKS_MENU` entries below reference a GuiMode that does NOT exist in `GuiMode.java` (confirmed by forensic audit). The sort menu does not exist in the codebase. These violations apply to the NEW sort menu to be built by item 1.27 — they are aspirational requirements, not existing code violations.

The following GUI elements were confirmed to violate The Royal Directive §2A by using non-legendary items as interactive buttons. All must be replaced with legendary items (Echo Shard, Nether Star, Totem of Undying, Dragon Egg, Elytra, Heart of the Sea, Netherite Ingot) before any phase is considered complete.

| GUI Mode | Slot | Current Item | Violation | Replacement |
|----------|------|--------------|-----------|-------------|
| WELCOME_MENU | 49 | BARRIER | Not legendary | ECHO_SHARD |
| FEATURE_MENU | 22 | COMPASS | Not legendary | NETHER_STAR |
| FEATURE_MENU | 49 | BARRIER | Not legendary | ECHO_SHARD |
| RECOVER_GUI | 49 | RED_CONCRETE | Not legendary | ECHO_SHARD |
| BG_STUDIO | 0, 45 | RED_CONCRETE | Not legendary | ECHO_SHARD |
| CONFIG_WARNING | 11 | RED_CONCRETE | Not legendary | TOTEM_OF_UNDYING |
| CONFIG_WARNING | 15 | LIME_CONCRETE | Not legendary | NETHER_STAR |
| MAGIC_ITEMS | 45 | RED_CONCRETE | Not legendary | ECHO_SHARD |
| CONFIG_GUI | 45 | RED_CONCRETE | Not legendary | ECHO_SHARD |
| UNDO_PICKER | 45 | RED_CONCRETE | Not legendary | ECHO_SHARD |
| DELETE_CATEGORY_MENU | 22 | RED_CONCRETE | Not legendary | ECHO_SHARD |
| CATEGORY_STATS | 22 | RED_CONCRETE | Not legendary | ECHO_SHARD |
| SORT_BLOCKS_MENU | 11 | PAPER | Not legendary | ECHO_SHARD |
| SORT_BLOCKS_MENU | 15 | COMPASS | Not legendary | NETHER_STAR |
| SORT_BLOCKS_MENU | 22 | RED_CONCRETE | Not legendary | ECHO_SHARD |
| SUBCATEGORY_CONTROLLER | 45 | RED_CONCRETE | Not legendary | ECHO_SHARD |
| MERGE_CATEGORY_PICKER_TARGET | 45 | RED_CONCRETE | Not legendary | ECHO_SHARD |
| BULK_ASSIGN_PICKER | 45 | RED_CONCRETE | Not legendary | ECHO_SHARD |
| BULK_RECOLOR_WIZARD | 11 | LIME_DYE | Not legendary | AMETHYST_CLUSTER |
| BULK_RECOLOR_WIZARD | 13 | COMPASS | Not legendary | NETHER_STAR |
| BULK_RECOLOR_WIZARD | 45 | RED_CONCRETE | Not legendary | ECHO_SHARD |
| BULK_RECOLOR_WIZARD | 16 | BARRIER | Not legendary | ECHO_SHARD |
| BULK_RECOLOR_CONFIRM | 18 | RED_CONCRETE | Not legendary | ECHO_SHARD |
| BULK_RECOLOR_CONFIRM | 26 | BARRIER | Not legendary | ECHO_SHARD |

**Total: 24 violations across 14 GUI modes.** All category management GUIs added in later development phases share the same pattern: RED_CONCRETE for back buttons, BARRIER for cancel buttons. Earlier GUIs (EDITOR, PICKER, MAIN) are compliant.

---

### 5.24 Feature Menu (`/cb` and `/cb menu`) is an empty stub

**The problem:** The single most important entry point to the mod — typing `/cb` with no arguments or `/cb menu` — opens a 54-slot inventory containing only a compass that says "Feature configuration coming soon." and a barrier close button. There are no navigation buttons, no tabs, no links to any feature. A new player types `/cb`, sees an empty screen, and has no idea the mod even has features.

**The solution:** Build an 8-tab Feature Menu hub that serves as the complete navigation dashboard for the mod. Tab selectors use legendary items in the header row (slots 0–8). Each tab's content follows the Royal Directive layout (header rows 0–8, content rows 9–35, footer rows 45–53). Tabs: (1) Blocks — List, Search, Favorites, Recent, Categories; (2) Edit — Editor Picker, Retexture, Set Shape, Lock/Unlock, Block Notes, Magic Items, Showcase; (3) Bulk — Bulk Recolor, Bulk Delete, Bulk Block Add, Export All, Export Category, Import; (4) Scripts & History — Script GUI, History GUI; (5) Settings — Config GUI, Voice Mode, Hologram Toggle; (6) Safety — Safety Center, Undo, Panic Mode, Recovery; (7) Server — Stats, Cache Dashboard, Resource Pack Hub, Marketplace, Diagnostics, Help; (8) Admin — Permission Management, Session Monitor, Whitelist/Ban, Force-Save, Backup, Reload Pack, Log Tail, OP List.

**The experience:** Player types `/cb` → a professional 54-slot inventory opens showing 8 tab buttons across the top. Each tab's label is clear and uses a legendary item icon. Clicking a tab shows that section's buttons in the content area. Every button plays a click sound. Clicking a feature button launches it. The menu feels like a complete mod dashboard, not a placeholder.

**Edge cases:**
- Non-admin player opens Admin tab → tab button is shown but grayed out with lore `§7Requires admin permission.`; clicking it plays an error sound and shows `§c[CB] Admin tab requires admin permission.`
- A feature is disabled in config (e.g., marketplace off) → that button is shown with a `§7(Disabled)` lore line and a barrier icon; clicking shows `§c[CB] This feature is disabled. Enable it in /cb config.`
- GUI opened from `/cb menu` or `/cb` — both lead to the same GUI.

**Files:** `GuiManager.java` (buildFeatureMenu, openFeatureMenu — replace stub with full implementation), `CustomBlockCommand.java` (menu and root command handlers)

---

### 5.25 Voice Mode Picker has no GUI — shows only chat text

**The problem:** `/cb voice` with no arguments sends a wall of text to chat listing available modes. After reading it, the player must type a second command (`/cb voice <mode>`) to actually set anything. After setting a mode via `/cb voice <mode>`, the code opens the Welcome GUI — which is also an empty stub (just a Nether Star saying "Welcome to CustomBlocks!" and a close button). There is no visual, clickable experience for picking a voice mode.

**The solution:** Build a Voice Mode Picker GUI — a 54-slot inventory with one legendary item per voice mode, arranged in the content area. Each item shows the mode name, a 1-line description of what that mode sounds like, and an indicator if it's currently active (enchantment glint). Clicking a mode: sets `CustomBlocksConfig.voiceMode`, saves config, plays `SoundEvents.ENTITY_PLAYER_LEVELUP`, shows action bar `§a✔ §fVoice mode set to: §b<mode>`, closes the GUI. Modes: friendly ("Warm, encouraging messages"), professional ("Clean, no-fluff output"), royal ("Formal and dramatic language"), minimal ("Shortest possible messages"), arabic ("Messages in Arabic"), silly ("Playful and fun responses"). Voice Picker is accessible from: `/cb voice`, Feature Menu Tab 5 (Settings), and the Welcome screen.

**The experience:** Player types `/cb voice` → a beautiful inventory opens showing 6 mode options as legendary items. Currently active mode glows. Player clicks "royal" → action bar says `§a✔ §fVoice mode set to: §broyal` → sound plays → GUI closes. The player immediately notices their next command response uses the royal voice style.

**Edge cases:**
- Player clicks the already-active mode → action bar says `§7[CB] Voice mode is already §b<mode>§7.` — no change.
- `/cb voice <mode>` typed directly (no GUI) → sets mode silently with chat confirmation, opens Voice Picker GUI so player can see it was applied.
- Invalid mode typed (`/cb voice blorp`) → `§c[CB] Unknown voice mode '§fblorp§c'. Valid modes: §ffriendly, professional, royal, minimal, arabic, silly`.

**Files:** `GuiManager.java` (new `buildVoicePickerGui()`, `openVoicePickerGui()`), `CustomBlockCommand.java` (cmdShowVoicePicker — replace chat-only with GUI open), `CustomBlocksConfig.java` (add `voiceMode` field — **DOES NOT EXIST** yet; confirmed by full-codebase grep)

---

### 5.26 Did-You-Mean suggestions are not clickable to fill the chat bar

**The problem:** When the mod suggests a corrected command (e.g., player typed `/cb setgow block 4` and the mod suggests `/cb setglow block 4`), the suggestion appears in chat as plain text with no way to click it. The player must retype the corrected command manually — often including block IDs or other arguments they just typed. The screenshot showed the suggestion displayed but not interactive.

**The solution:** Send the Did-You-Mean suggestion as a `Text.literal()` with a `ClickEvent` of type `SUGGEST_COMMAND` (not `RUN_COMMAND`). `SUGGEST_COMMAND` fills the player's chat bar with the corrected command text but does NOT execute it. This lets the player review and edit the arguments before pressing Enter. The clickable text should be visually distinct: `§a§n[Click to use: /cb setglow block 4]` — underlined green.

**The experience:** Player types `/cb setgow block 4` → chat shows: `§c[CB] §7'setgow' not found. Did you mean: §a§n[Click to use: /cb setglow block 4]` — player clicks the underlined text → their chat bar fills with `/cb setglow block 4` — they can change "block" to the real block ID before pressing Enter.

**Edge cases:**
- Suggestion contains a greedy string argument → the full suggestion is pre-filled; player edits in place.
- Player is on a platform that doesn't support clickable chat (unlikely) → suggestion is shown as plain text as fallback.
- Multiple close matches exist → show top 2 as separate clickable links.

**Files:** `CustomBlockCommand.java` (DidYouMean suggestion formatting), `ChatHelper.java` (if suggestion formatting is centralized there)

---

### 5.27 `/cb exportpng` needs rework and a download link

**The problem:** `/cb exportpng <id>` saves the block's texture to disk but only shows the file path in chat as plain text. The server owner cannot click the path — they must SSH into the server or use an SFTP client to retrieve the file. For a server owner who manages things in-game, this is an unnecessary extra step.

**The solution:** After saving the PNG to `config/customblocks/exports/<id>.png`, send a chat message with a clickable `[Click to Download]` link that uses `ClickEvent.OPEN_URL` to open the texture via the mod's built-in HTTP server (the same server used to serve the resource pack). If the HTTP server is not running, fall back to showing the path only with a note.

**The experience:** Player types `/cb exportpng stone_tile` → PNG saved → chat shows: `§a[CB] §fExported: §bconfig/customblocks/exports/stone_tile.png` on one line, then `§a[Click to Download]` as a clickable link that opens the image in their browser.

**Edge cases:**
- HTTP server not running → `§a[CB] Exported to §fconfig/customblocks/exports/stone_tile.png §7(no download link — HTTP server not running).`
- Block has no texture (null texture bytes) → `§c[CB] §fstone_tile §chas no texture to export.`
- Disk full → `§c[CB] Export failed: §fdisk full.`
- File already exists from a previous export → overwrite silently (most recent texture wins).

**Files:** `CustomBlockCommand.java` (cmdExportPng method), `NetworkManager.java` or `ResourcePackServer.java` (HTTP server URL construction)

---

### 7.53 Texture pre-cache and instant click are not wired

**⚠️ CORRECTION:** `instantClickAggressivenessMs` **DOES NOT EXIST** in `CustomBlocksConfig.java` — confirmed by full-codebase grep; zero matches found. There is no such field and no `@Deprecated` annotation for it. Line 138 of `CustomBlocksConfig.java` contains a different field (`sessionTimeoutSeconds`). The field and wiring described below must be added as new code.

**The problem:** Right-clicking a color square or any tool wand feels sluggish. There is a noticeable delay between click and result. There is no config field to tune this delay, and no texture pre-loading. The delay persists for all players.

Additionally: textures are loaded on demand (first access). The first time a player interacts with a block after server start, there is extra latency while the texture is read from disk. There is no pre-loading.

**The solution:** (1) Remove the `@Deprecated` tag from `instantClickAggressivenessMs`. (2) Find the right-click handler in `ColorSquareItem.java` and `ColorTriangleItem.java`. Remove any artificial delay or cooldown. Connect `instantClickAggressivenessMs` so that a value of `0` means zero added delay (maximum response speed). (3) On server startup, after `SlotManager.loadAll()` completes, spin up a background thread that iterates all slots and reads each texture into an in-memory cache. (4) Whenever a block is created or edited, immediately cache its new texture in the background. The cache is always warm.

**The experience:** From the moment the server starts, clicking any color tool is instantaneous. Zero delay. The admin sets `instant_click = 0` in config and never thinks about it again.

**Edge cases:**
- Server has 500+ blocks → startup pre-cache may take a few seconds; run it on a background thread so server startup is not delayed.
- Block is edited while it is being pre-cached → update the cache entry immediately after the edit completes.
- Cache uses too much RAM → add a `maxCacheSizeMb` config field (default 256 MB); if exceeded, LRU eviction applies.

**Files:** `ColorSquareItem.java` (right-click handler, remove delay), `ColorTriangleItem.java` (same), `CustomBlocksConfig.java` (add new `instantClickAggressivenessMs` field — does not exist yet), `CustomBlocksMod.java` or `SlotManager.java` (startup pre-cache thread), new `TextureCacheManager.java` (shared cache with LRU eviction)

---

### 8.4 `marketplaceEnabled` config field is wired to nothing

**⚠️ CORRECTION:** `marketplaceEnabled` **DOES NOT EXIST** in `CustomBlocksConfig.java` — confirmed by full-codebase grep; zero matches found. There is no such field and no `@Deprecated` annotation for it. Line 158 of `CustomBlocksConfig.java` contains a different field. The field and wiring described below must be added as new code.

**The problem:** There is no marketplace enable/disable config toggle. `openMarketGui()` has no gate — the marketplace always opens regardless of admin intent. Admins have no way to disable the marketplace without modifying source code.

**The solution:** Wire the field. Add a check at the top of `openMarketGui()` in `GuiManager.java`: if `!CustomBlocksConfig.marketplaceEnabled`, close the screen and show action bar `§c[Market] §7Marketplace is disabled by the server admin.` Also add the same check in the `/cb market` command handler. Remove the `@Deprecated` tag. Add the marketplace toggle to the Config GUI Integrations tab.

**The experience:** Admin sets `marketplace_enabled: false` in config or via GUI toggle → any player who tries to open the market sees `§c[Market] §7Marketplace is disabled by the server admin.` The market GUI never opens.

**Edge cases:**
- Admin disables market while a player has the market GUI open → GUI closes on next interaction with a `§c[Market] §7Marketplace has been disabled.` message.
- Re-enabling via config GUI takes effect immediately without restart.
- `/cb market` command when disabled → same error, no GUI opens.

**Files:** `CustomBlocksConfig.java` (add new `marketplaceEnabled` field — does not exist yet), `GuiManager.java` (add check at start of openMarketGui), `CustomBlockCommand.java` (add check in market command handler)

---

### 8.5 Cloud vault URL should not be user-configurable

> **⚠️ DUPLICATE — ALREADY ADDRESSED.** This item is fully covered by the Critical Corrections table (Security — locked fields section) and items 1.13/1.14. `cloudShareUrl` has been designated a hardcoded constant throughout the plan. Do not reimplement this — follow the Critical Corrections table for the exact approach. This item is retained for traceability only.

---

## Phase 9 — Safety & Recovery

*These features give the server owner full control over data safety. Every mutation is recorded, every change is reversible, and emergency tools are one command away.*

### 9.1 Undo Stack: Persistent Per-Player History

**Current state:** `UndoManager.java` exists and is fully implemented with global and per-player stacks. Confirmed actual values: `maxUndoDepth` default = **20** (not 10,000 as previously claimed), max clamp = **100**. After item 1.28 redesign this becomes default 50, max 100 with disk-backed differential storage. It is PURELY IN-MEMORY — no disk persistence exists. The plan item (combined with item 1.28) is to add disk persistence and raise the default depth.

**The problem:** The current undo system has configurable stack size (default 10,000) but no persistence behavior and no in-game way to see what can be undone. Players have no confidence that their last action can be reversed. There is no warning when the undo stack is nearly full, and there is no way to clear it intentionally.

**The solution:** Per-player undo stacks, configurable size (default 50, range 10–500, config key `undo_stack_size`). History persists to disk on server shutdown and is reloaded on server start — history survives restarts and relogs. Stack is cleared ONLY when: (a) panic mode triggers, or (b) player runs `/cb undo clear`. When any mutation occurs, a 4-second action bar flash shows: `§b[↩] §7/cb undo to reverse: §f<action description>`. When stack exceeds 80% capacity, each new mutation shows: `§e[Undo] §7Stack nearly full (§f42/50§7). Oldest entries will drop.` Config option `undo_confirm` (default false): if true, `/cb undo` shows a confirmation GUI before executing.

**The experience:** Player creates a block by mistake → action bar immediately shows `§b[↩] §7/cb undo to reverse: created fortnite_blue` → they type `/cb undo` → block is removed → `§a[CB] Undone: §ffortnite_blue §acreation reversed.`

**Edge cases:**
- Player tries to undo after stack is empty → `§7[CB] Nothing to undo.`
- Undo conflicts with a later action (e.g., a block that was created then renamed) → undo reverts the creation, warns: `§e[CB] Note: §f2 §elater actions also affected §ffortnite_blue §eand were also reversed.`
- Server crash before stack is persisted → partial stack may be lost; log a warning on startup if persisted stack is missing.

**Files:** `UndoManager.java` (stack persistence, size config, 80% warning), `CustomBlocksConfig.java` (undo_stack_size, undo_confirm), `CustomBlockCommand.java` (undo command handler)

---

### 9.2 Safety Center (`/cb safety`)

**The problem:** Safety features — panic mode, undo, snapshots, recovery — are spread across separate commands and GUIs with no central place. The server owner has no dashboard showing the current safety state: is panic on? How many undo entries are available? When was the last backup? It feels disconnected and hard to use in an emergency.

**The solution:** Build a unified `/cb safety` GUI — admin-only — that shows live safety status and provides all safety actions from one place. Layout (Royal Directive compliant): Header = decorative glass with title. Content area shows: last auto-snapshot time, total undo entries across all online players, panic mode status (ON/OFF with colored indicator item), last backup file name and size, broken block count. Footer legendary-item buttons: slot 45 = Force Snapshot Now (Echo Shard), slot 47 = View Recovery (Elytra), slot 49 = Close (Barrier), slot 51 = View History (Dragon Egg), slot 53 = Toggle Panic Mode (Totem of Undying). Panic mode when ON: ALL block mutations blocked. Creates, edits, deletes, retextures, recolors all return: `§c§l⚠ §r§cPanic mode is active. No mutations allowed.` Panic persists through server restarts. Toggle is silent — no broadcast to non-admin players.

**The experience:** Server owner types `/cb safety` → professional dashboard opens. They can see at a glance: panic is OFF, 23 undo entries across 2 players, last backup was 2 hours ago, 0 broken blocks. If something goes wrong during a build session, they click Panic to lock everything instantly, then use Force Snapshot to save the current state before investigating.

**Edge cases:**
- Non-admin tries `/cb safety` → `§c[CB] Safety Center requires admin permission.`
- Toggle Panic while a player is mid-bulk-operation → current operation completes, all subsequent mutations blocked.
- Force Snapshot fails (disk full) → `§c[CB] Snapshot failed: disk full. Free up space and try again.`

**Files:** `GuiManager.java` (new openSafetyCenter, buildSafetyCenter methods), `CustomBlockCommand.java` (register /cb safety), new `PanicManager.java` (panic state persistence), `UndoManager.java` (expose per-player counts)

---

### 9.3 History GUI (`/cb historygui`)

**The problem:** `/cb history` shows the last 20 mutations as plain chat text — no filtering, no interactive undo, no date range, no way to find a specific change. For a server with active editing, 20 entries scroll off the screen in minutes.

**The solution:** Build `/cb historygui` — a paginated 54-slot GUI showing mutations newest-first. Each entry shown as a named item: `§f<PlayerName> §7<action> §b<blockId> §8<timestamp>`. Left sidebar (slots 0, 9, 18, 27, 36, 45) = filter buttons: player name filter (anvil prompt), block ID filter (anvil prompt), action type filter (cycle: All/Created/Deleted/Renamed/Retextured/Recolored/Locked), date filter (cycle: All Time/Today/This Week/Custom). Each entry's lore shows two undo options: `§a▼ Undo to here §7— reverses all actions after this point` and `§e◆ Undo only this §7— reverses only this specific action (conflict warning if needed)`. Export button in footer (Dragon Egg, slot 51): exports current filtered view to `config/customblocks/history_export_<timestamp>.txt`. Keep `/cb history` chat command alongside the GUI — it is NOT removed.

**The experience:** Admin types `/cb historygui` → opens a paginated history with all mutations. They filter by player name "Steve" → only Steve's mutations remain visible. They spot a bad retexture from 2 hours ago → hover over it → see both undo options → click "Undo to here" → confirmation shows "This will reverse 7 actions. Confirm?" → click confirm → 7 mutations reversed.

**Edge cases:**
- No history recorded this session → GUI shows a single item: `§7No history recorded yet.`
- Cherry-pick undo conflicts with later actions → show: `§e[Warning] §7Later actions also affected §f<blockId>§7. Undoing anyway.`
- Export file already exists → append timestamp suffix to avoid overwriting.
- Filter returns zero results → show `§7No mutations match your current filters.`

**Files:** `GuiManager.java` (new openHistoryGui, buildHistoryGui methods), `CustomBlockCommand.java` (register /cb historygui), new `HistoryTracker.java` (does not exist — must be built from scratch; add filter/export methods), new `HistoryFilter.java` (filter state per player)

---

### 9.4 `/cb backup` System

**⚠️ CORRECTION:** `SnapshotManager.java` **DOES NOT EXIST** — confirmed by full-codebase grep; zero matches found. The commands `/cb snapshot`, `/cb snapshot list`, `/cb snapshot restore` are NOT registered. The "Already implemented" claim was entirely false (see also item 5.22 correction). The `/cb backup` command and all SnapshotManager functionality must be built from scratch.

**The problem:** While the snapshot system exists, there is no manual backup command with named backups, expiry timers, or size information in listings.

**The solution:** Full `/cb backup` command group — admin-only. Subcommands: `create [name]` → creates a ZIP of all block data to `config/customblocks/backups/<timestamp>_<name>.zip`, shows exact path and file size in chat; `list` → lists all backups with name, date, size, and expiry status; `restore <name>` → opens a confirmation GUI showing block count, creation date, and what changes before executing restore; `delete <name>` → deletes the backup file; `expiry <name> <hours>` → sets an auto-delete timer — if the backup is not restored within N hours, it auto-deletes and notifies all online admins: `§e[Backup] §fExpired backup deleted: §b<name>§f (was set to expire after §f<N>§f hours)`; `auto` → shows current auto-backup schedule from Config GUI Safety tab.

**The experience:** Admin types `/cb backup create pre_update` → `§a[CB] §fBackup created: §bconfig/customblocks/backups/20260517_1423_pre_update.zip §7(2.3 MB)` — they set an expiry: `/cb backup expiry pre_update 48` → `§7[CB] Backup 'pre_update' will auto-delete in 48 hours if not restored.` After the update goes well, they let it expire. If the update breaks things, they run `/cb backup restore pre_update` → confirmation GUI → confirm → all blocks restored.

**Edge cases:**
- No backups exist for `list` or `restore` → `§7[CB] No backups found. Create one with /cb backup create.`
- Backup file is corrupt → `§c[CB] Backup 'pre_update' is corrupt and cannot be restored. Delete it with /cb backup delete pre_update.`
- Restore while panic mode is ON → still allowed (restore is a safety operation).
- Auto-backup fails → notify online admins: `§c[Backup] Auto-backup failed: <reason>.`

**Files:** `CustomBlockCommand.java` (register /cb backup subcommands), new `BackupManager.java` (create, list, restore, delete, expiry logic), `CustomBlocksConfig.java` (auto-backup interval field)

---

## Phase 10 — Power User Tools

*Features for admins and advanced players who want deep control over the mod.*

### 10.1 Scripts System (renamed from Macros) with Tutorial Overlay

**⚠️ CORRECTION:** `MacroManager.java` **DOES NOT EXIST** — confirmed by full-codebase grep; zero matches found. The commands `/cb macro record/stop/run/list/delete/show/add` are NOT registered. The "Already implemented" claim was entirely false (see also item 5.16 correction). The Scripts system must be built from scratch as `ScriptManager.java`. The plan item is to: (1) build the recording and execution engine from scratch, (2) name it "scripts" from the start (no rename needed), (3) build the tutorial overlay, (4) build the post-run GUI countdown.

**The problem:** The macro system (`/cb macro`) is described by the server owner as "way too complicated and weird and unexplained." The commands are technical and unfamiliar (`record`, `stop`, `show`, `add`), there is no in-game guide, and there is no GUI to manage saved macros. Players who would benefit most from automation are the ones least likely to discover and use it.

**The solution:** Rename the entire macro system to "scripts" throughout — `/cb script record`, `/cb script stop`, `/cb script run`, `/cb script list`, `/cb script show`, `/cb script delete`, `/cb script add`. Add `/cb scriptgui` as the GUI access point. The Script GUI shows all saved scripts as Clock items (lore: `§7Steps: §f<N> §7— Last run: §f<time>`; left-click = Run; right-click = Options sub-menu with: Show Steps, Delete, Rename, Add Step). First time `/cb scriptgui` is opened by any player, a tutorial overlay plays: 3 steps shown as GUI items in sequence — Step 1: `Record` (Writable Book), Step 2: `Run /cb commands`, Step 3: `Stop` (Nether Star). "Got it" button closes the overlay and marks it seen for that player. Run behavior: GUI closes → action bar shows step-by-step progress: `§b▶ §fScript: daily_setup §8— §fstep §a3§8/§a7` → on completion: summary GUI opens for 5 seconds with a countdown and [Close Now] button.

**The experience:** New player opens `/cb scriptgui` for the first time → tutorial overlay walks them through the 3-step concept. They record their first script, come back to the GUI, see their Clock item, click Run → watch the action bar count up through steps → summary shows all steps that ran. Feels like automation made simple.

**Edge cases:**
- Player runs `/cb script run daily_setup` while another script is running → `§c[CB] A script is already running. Wait for it to finish.`
- A step in the script fails (e.g., block ID no longer exists) → log the failed step in the summary, continue remaining steps.
- Script file is corrupt → `§c[CB] Script '§f<name>§c' is corrupt and cannot be run. Delete and re-record it.`

**Files:** new `ScriptManager.java` (build from scratch — MacroManager.java does not exist; no rename needed), `GuiManager.java` (new openScriptGui, buildScriptGui, tutorial overlay logic), `CustomBlockCommand.java` (register new `literal("script")` subtree and scriptgui — no `/cb macro` to rename)

---

### 10.2 Config Subcommands for All Settings with Human-Readable Names

**The problem:** Most config settings can only be changed by manually editing `config/customblocks/config.json`. There is no in-game command for `instantClickAggressivenessMs`, `undoDepth`, `hologramHeight`, `maxSlots`, `aiApiKey`, or dozens of other fields. The few config subcommands that exist use camelCase technical names that feel like source code, not player commands.

**The solution:** Register ALL config fields as subcommands of `/cb config` with human-readable names: `voice`, `hologram`, `instant-click`, `max-slots`, `webhook`, `ai-key`, `undo-depth`, `gif-limit`, `texture-size`, `marketplace`, `backup-interval`, `hologram-height`, `undo-confirm`, `cache-size` — and every other field in `CustomBlocksConfig.java`. Each subcommand: (1) with no value argument → shows current value; (2) with a value argument → sets the value, saves config, shows confirmation. Full tab-complete: typing `/cb config instant-click ` shows `§8Hint: integer 0–10000, current: 300`. `/cb config` with no args shows a paginated list of all settings with current values and brief descriptions.

**The experience:** Admin types `/cb config instant-click 0` → `§a[CB] instant-click set to §f0§a. (Textures: maximum speed, no delay.)` Admin types `/cb config undo-depth unlimited` → `§a[CB] undo-depth set to §funlimited§a.` (unlimited = Integer.MAX_VALUE internally).

**Edge cases:**
- Value out of valid range → `§c[CB] §finstant-click §cmust be between §f0 §cand §f10000§c. You entered: §f-5§c.`
- Boolean field gets non-boolean value → `§c[CB] §fhologram §caccepts only §ftrue §cor §ffalse§c.`
- Player without `canConfig` permission → `§c[CB] You don't have permission to change config settings.`
- Config save fails (disk error) → `§c[CB] Setting changed in memory but config save failed: §f<error>§c. Changes will not persist through restart.`

**Files:** `CustomBlockCommand.java` (config subcommand registration — one per field, or a dynamic approach using reflection), `CustomBlocksConfig.java` (add validation ranges per field as constants)

---

### 10.3 Cache & Server Health Dashboard (`/cb cache`)

**The problem:** There is no in-game way to check the state of the texture cache, resource pack, server memory, or sync status. Diagnosing performance problems requires server console access or log reading — neither of which is accessible while playing.

**The solution:** Build `/cb cache` — admin-only — as a 5-tab professional GUI (Royal Directive compliant). Tab 1 (Texture Cache): shows cached texture count, total cache size on disk, last cache refresh time; buttons: [Clear Cache] (echo shard), [Warm Cache] (nether star), [Force Re-Sync All Players] (heart of sea). Tab 2 (Server Memory): shows JVM heap used/max formatted as `§f2.1 GB §7/ §f4.0 GB`, non-heap memory, GC runs today; button: [Suggest GC] (totem). Tab 3 (Resource Pack): pack file size, last generated timestamp, count of players who have received it; buttons: [Regenerate Pack], [Force-Send to All]. Tab 4 (Sync Status): last broadcast time, pending-sync block count, whether sync is paused; button: [Force Full Sync]. Tab 5 (Diagnostics): broken block count, last 10 error/warning lines, buttons: [View Broken Blocks], [Create Diagnostics ZIP], [Export Error Log]. All destructive actions show a confirmation click before executing.

**The experience:** Admin notices lag after creating many blocks. Opens `/cb cache` → Tab 2 shows heap at 95% → clicks [Suggest GC] → `§7[CB] GC requested. Heap may recover over the next few seconds.` They check Tab 1 → texture cache is 800 MB → clicks [Clear Cache] (confirms) → `§a[CB] Texture cache cleared. Blocks will reload textures on next access.`

**Edge cases:**
- GC suggestion is ignored by JVM → note in lore: `§8This is advisory only. The JVM decides whether to honor it.`
- Force-Send All while a player has a GUI open → delay their send until they close the GUI.
- Diagnostics ZIP creation fails → `§c[CB] Diagnostics export failed — check server log.`

**Files:** `GuiManager.java` (new openCacheDashboard, buildCacheDashboard with 5 tabs), `CustomBlockCommand.java` (register /cb cache), new `DiagnosticsHelper.java` (**DOES NOT EXIST** — confirmed by full-codebase grep; must be built from scratch), `GuiManager.java` (openBrokenBlocks — already exists)

---

### 10.4 `/cb audit` — Royal Directive Compliance Scanner

**The problem:** It is difficult to verify that every GUI in the mod follows the Royal Directive (legendary-item buttons, correct row placement, sounds on every action, particles on completions). Violations creep in during development and are only caught during manual testing, if at all.

**The solution:** Build `/cb audit` — admin-only — that scans every registered GUI mode in the mod and reports compliance violations in a scrollable GUI. Checks per GUI: (a) Are all interactive slots using legendary items (Echo Shard, Nether Star, Totem, Dragon Egg, Elytra, Heart of Sea, Netherite Ingot, or approved equivalent)? (b) Are header rows 0–8 used only for navigation/cosmetic items? (c) Are footer rows 45–53 used only for action buttons? (d) Does every button click play a sound? (e) Does every completion action fire particles? Each violation shown as a named Barrier item: `§c✗ §f<GuiMode>: <violation description>`. Passing checks shown as Lime Concrete: `§a✓ §f<GuiMode>: all checks pass`. Summary item in header: `§f<N> §apasses, §f<M> §cfailures`.

**The experience:** Admin runs `/cb audit` → GUI shows all GUI modes listed. 3 red barriers indicate violations: "BULK_RECOLOR_WIZARD: slot 16 uses Barrier (not legendary)" and "FEATURE_MENU: no sound on tab click" and "COLOR_STUDIO: footer row missing". Admin notes these violations and they are fixed in the next development cycle.

**Edge cases:**
- All checks pass → header shows `§a✓ All §f<N> §aGUI checks pass. Royal Directive compliant.`
- Audit takes longer than 500ms (many GUIs) → run on background thread, open GUI when complete with `§7Auditing... please wait.` placeholder.
- A GUI mode has no items at all (stub) → report as violation: `§c✗ §f<GuiMode>: stub — no content built.`

**Files:** `GuiManager.java` (new runAudit() method that inspects all GuiMode cases), `CustomBlockCommand.java` (register /cb audit), `GuiMode.java` (audit metadata per mode)

---

### 10.5 `/cb screenshot` — Timestamped Texture Archive with Download Link

**The problem:** `/cb exportpng` (5.27) always overwrites the same file — you lose the previous export. There is no way to keep a visual history of a block's texture at different points in time, and no command named `/cb screenshot` for players who think of it that way.

**The distinction from 5.27:** `/cb exportpng <id>` is the "get the current texture right now" command — it overwrites `<id>.png` every time (always the freshest copy). `/cb screenshot <id>` is the "archive this moment" command — it saves a timestamped copy to `config/customblocks/exports/<id>_<timestamp>.png` and never overwrites a previous screenshot, building a version history you can browse.

**The solution:** `/cb screenshot <id>` saves the block's texture to `config/customblocks/exports/<id>_<timestamp>.png` (e.g. `stone_tile_20260517_143022.png`), then sends a clickable `[Click to Download]` link via `OPEN_URL` ClickEvent through the built-in HTTP server. If the HTTP server is not running, show the file path only.

**The experience:** Player types `/cb screenshot stone_tile` → chat shows: `§a[CB] §fScreenshot saved: §bstone_tile_20260517_143022.png` then on the next line `§a§n[Click to Download]` — clicking opens the image in their browser at `http://<server-ip>:<port>/exports/stone_tile_20260517_143022.png`.

**Edge cases:**
- Block has no texture → `§c[CB] §fstone_tile §chas no texture. Upload one first with /cb retexture.`
- HTTP server offline → show file path only: `§a[CB] Saved to §fconfig/customblocks/exports/stone_tile.png §7(no link — HTTP server offline).`
- `/cb screenshot` with no arguments → usage message listing correct syntax.

**Files:** `CustomBlockCommand.java` (register /cb screenshot, cmdScreenshot method), `ResourcePackServer.java` or `NetworkManager.java` (expose exports/ folder via HTTP)

---

## Phase 11 — AI & Generation

*Intelligent features that generate content for the player rather than requiring manual input.*

### 11.1 AI Block Generator (`/cb ai`)

**The problem:** Creating custom block textures requires external tools, image editors, or finding URLs online. Players with no design experience have no way to create unique textures for their blocks. The mod could generate textures for them.

**The solution:** Two modes depending on whether an API key is configured. Mode A (API key set): `/cb ai` opens a GUI with a text input (anvil prompt). Player types a description (e.g., "dark stone wall with glowing cracks"). The mod sends the prompt to Stability AI or OpenAI image API. A loading animation shows in the GUI. 3 texture variations are returned and displayed as preview items. Player clicks one → the mod downloads and processes that texture → block is created. Config fields: `ai_api_provider` (stability/openai), `ai_api_key` (stored securely), `ai_max_variations` (default 3), `ai_texture_style` (pixel_art/natural). Mode B (no API key): free procedural generation based on keyword detection ("stone" → grey noise texture, "lava" → orange/red gradient, "grass" → green noise, "wood" → brown striped pattern, etc.). Clearly marked as procedural: `§7[AI] Using procedural mode. Set ai-key in Config → Integrations for real AI generation.`

**The experience:** Player types `/cb ai` → GUI opens → types "ancient temple block with moss" → loading animation → 3 variations appear → clicks the best one → block is created with that texture → `§a[CB] AI Block 'ancient_temple_1' created from your description!`

**Edge cases:**
- API request fails (network error) → `§c[CB] AI generation failed: §f<error>. Try again or use /cb create with a URL.`
- API returns no results matching style requirements → retry once automatically, then show error.
- API key is invalid → `§c[CB] AI API key rejected. Check your key in Config → Integrations.`
- No free slots for the new block → `§c[CB] All §f<N> §cslots are full. Delete a block first.`
- Procedural mode cannot match keyword → use a generic noise texture and note: `§7[AI] No match for your description — using generic texture.`

**Files:** `CustomBlockCommand.java` (register /cb ai), new `AiTextureGenerator.java` (API calls + procedural fallback), `GuiManager.java` (new AI generation GUI with loading animation and variation picker), `CustomBlocksConfig.java` (ai_api_provider, ai_api_key, ai_max_variations, ai_texture_style fields)

---

### 11.2 `/cb customcolor` GUI — Color Studio Redesign

> ℹ️ **COMMAND NOTE** — The command `/cb customtriangle` already exists and is registered in CustomBlockCommand.java. This plan item adds `/cb customcolor` as a new unified command while keeping `/cb customtriangle` as a deprecated alias.

**The problem:** `/cb customtriangle` and `/cb customsquare` with a hex code always produce a diamond-colored (light blue) result regardless of the hex entered (see also 1.31). The command names are confusing (why is it called "triangle"?). There is no GUI for configuring custom colors — players must know the exact hex syntax to use these tools.

**The solution:** Unify and rename: both commands become `/cb customcolor` (NEW command to add). Keep `/cb customtriangle` as a deprecated alias that still works. Add a new Color Studio GUI that opens with `/cb customcolor` (no arguments). The GUI has: a hex input button (click → anvil prompt to type hex code), HSB sliders represented as cycling items (H: Hue 0–360, S: Saturation 0–100, B: Brightness 0–100), a preview swatch item showing the current color as best as inventory rendering allows, and three legendary-item buttons in the footer: [Give Square] (Echo Shard), [Give Triangle] (Nether Star), [Give Both] (Dragon Egg). Players can still use CLI: `/cb customcolor square #111111` or `/cb customcolor triangle #FF0000`. Fix the core color accuracy bug: the hex → pixel color mapping must produce exactly the RGB values specified.

**The experience:** Player types `/cb customcolor` → Color Studio opens → clicks hex input → types `#FF4400` (orange) → preview swatch updates to show orange → clicks [Give Both] → receives a Square and Triangle both targeting `RGB 255, 68, 0` → uses triangle on a block → background pixels turn exactly that orange.

**Edge cases:**
- Player enters invalid hex `#ZZZZZZ` → `§c[CB] Invalid hex color '§f#ZZZZZZ§c'. Use format #RRGGBB.`
- Player enters 3-digit hex `#111` → expand to `#111111` automatically.
- Player enters hex with no `#` → add `#` automatically and proceed.
- Fully transparent result (hex `#00000000`) → `§c[CB] Fully transparent colors cannot be used. Use a hex with non-zero RGB.`

**Files:** `ColorSquareItem.java` (rename, fix color mapping), `ColorTriangleItem.java` (rename, fix color mapping), `GuiManager.java` (new openColorStudio GUI for custom color — separate from existing Color Studio for palette), `CustomBlockCommand.java` (register /cb customcolor, deprecate /cb customtriangle and /cb customsquare)

---

## Phase 12 — Achievements & Custom Drops

*Reward systems that make playing with the mod feel meaningful and fun.*

### 12.1 Achievements: Full Display Implementation

**⚠️ CORRECTION:** `AchievementManager.java` **DOES NOT EXIST** — confirmed by full-codebase grep; zero matches found. The methods `AchievementManager.onBlockCreated`, `onBlockDeleted`, etc. are NOT called anywhere. No achievement data is saved. The original problem description was entirely false. The entire achievement system must be built from scratch.

**The problem:** There is no achievement system in the mod at all. Players receive no recognition for milestones (creating N blocks, using all tools, running macros/scripts, etc.). There are no achievements defined, no unlock logic, and no display layer.

**The solution:** When any achievement unlock event fires, execute the full display sequence: (1) Send a screen title to the unlocking player: `§6§l✨ Achievement Unlocked!` with subtitle `§e<AchievementName>` and body text `§7<AchievementDescription>`. (2) Play `SoundEvents.UI_TOAST_CHALLENGE_COMPLETE` at full volume (the advancement fanfare sound). (3) Broadcast to all online players in chat: `§6[★] §f<PlayerName> §aunlocked: §e<AchievementName>!`. Add `/cb achievements` command that opens a GUI showing all achievements (locked and unlocked), with progress indicators for multi-step achievements.

**The experience:** Player creates their 50th custom block → screen flashes with `§6§l✨ Achievement Unlocked!` → `§eMaster Builder` → advancement fanfare plays → all players in chat see `§6[★] §fPlayerName §aunlocked: §eMaster Builder!` → Player types `/cb achievements` → browses the full achievement list, sees which ones they have and which ones are still locked.

**Edge cases:**
- Player is offline when achievement would trigger → queue the display for next login; show on join: `§6[★] §7You unlocked §e<N> §7achievement(s) while offline. Type §f/cb achievements §7to see them.`
- All players see the broadcast, including non-OP players (achievements are player-facing celebrations, not admin-only).
- `/cb achievements` shows locked achievements as gray items with `§8???` name until unlocked, or shows what milestone is needed.

**Files:** new `AchievementManager.java` (does not exist — build from scratch: define achievements, unlock logic, disk persistence, and display calls), `GuiManager.java` (new openAchievementsGui, buildAchievementsGui), `CustomBlockCommand.java` (register /cb achievements)

---

### 12.2 Custom Drops: Per-Block Drop Configuration

**The problem:** When a player breaks a custom block in the world, Minecraft applies the vanilla drop logic — which gives the wrong item or nothing meaningful. There is no way to configure what a custom block drops.

**The solution:** Add a "Drop Settings" section to the block editor. Each block stores a drop config in `SlotData`: drop mode (`nothing`, `self`, `custom`) and up to 5 custom drop entries (each: vanilla item ID, quantity min/max, drop chance 0–100%). Drop config is accessible via: Block Editor GUI → Drop Settings button (Heart of the Sea, slot 35 in editor). The button opens a Drop Config GUI where each of the 5 drop slots is configurable via anvil prompt. Drop logic is applied server-side when the block is broken.

**The experience:** Admin opens a block's editor → clicks [Drop Settings] → sees current mode is "self" (block drops itself) → changes to "custom" → configures: 2 diamonds (100% chance) + 1 emerald (50% chance) → saves → a player breaks the block in the world → 2 diamonds always drop, emerald drops half the time. Feels like a real custom block with real rewards.

**Edge cases:**
- Drop mode is `nothing` → block broken silently, no drops, no XP.
- Drop mode is `self` but block has no corresponding placeable item → `§e[CB] Drop mode 'self' — note: this block may not be placeable after pickup.`
- Custom drop item ID is invalid → `§c[CB] Invalid item ID '§f<id>§c'. Use a valid Minecraft item ID.`
- Quantity min > max → swap them automatically.
- All 5 drop slots empty while mode is `custom` → treat as `nothing`.

**Files:** `SlotData.java` (add dropMode and dropEntries fields), `GuiManager.java` (Drop Settings button in editor, openDropConfigGui), `SlotBlock.java` or block break handler (apply drop logic on block break event), `CustomBlockCommand.java` (no new commands — all GUI-driven)

---

## Phase 13 — Discord Integration Upgrade

*Making the Discord webhook feel like a professional integration, not an afterthought.*

### 13.1 Discord Webhooks: Full Rework

**The problem:** Discord webhook messages are plain text with no formatting. Bulk operations spam Discord with dozens of individual messages. There is no way to choose which events post to Discord. There is no in-game status indicator showing whether the webhook is working. There is no way to undo an action that was announced in Discord.

**The solution:** Four major upgrades: (1) Rich embeds — all messages use Discord embed format with color coding (green sidebar for create, red for delete, orange for rename, yellow for retexture/recolor, dark red for panic), player name in embed footer, timestamp, block thumbnail URL if available. (2) Rate limiting and batching — if N events fire within 5 seconds (e.g., bulk delete of 20 blocks), aggregate into one message: `🟥 **Bulk Delete** by \`PlayerName\` — 20 blocks deleted. [View list]`. (3) Configurable events — Config GUI → Integrations tab → Discord section → toggle checkboxes per event type: Create, Delete, Rename, Retexture, Recolor, Panic. Toggled via click, saved immediately. (4) Undo via reaction — when the webhook posts a message, it also sends the event ID. A companion Discord bot (bot token configurable in Integrations) watches for ↩️ reactions on CB messages. When an admin reacts with ↩️, the bot calls back to the server's HTTP endpoint, which reverses the action via `UndoManager`. Requires a Discord bot token in addition to the webhook URL. Clearly documented that bot token is optional — only needed for reaction-undo. (5) Integrations tab UI upgrades: status indicator (🟢 connected / 🟡 not tested / 🔴 failed), Test Connection button (Echo Shard), last post timestamp in lore, enable/disable toggle (keeps URL but pauses posting), post count today.

**The experience:** Admin bulk-deletes 15 blocks → one Discord message appears: `🟥 **Bulk Delete** by \`AdminName\` — 15 blocks deleted.` An admin in Discord sees it, thinks it was a mistake, reacts with ↩️ → the Discord bot calls back → all 15 blocks are restored → `§a[CB] Discord reaction undo: §f15 §ablocks restored.` in-game.

**Edge cases:**
- Webhook URL is empty → Discord posting is silently disabled; no errors in logs.
- Discord returns non-200 response → log the error, set status indicator to 🔴, notify online admins: `§c[Discord] Webhook post failed (HTTP <code>). Check your webhook URL.`
- Bot token is not set → ↩️ reaction undo is unavailable; Integrations tab shows bot token field as `§8Not configured (optional)`.
- Undo triggered via Discord but action is no longer undoable (stack cleared) → bot responds in Discord: "This action is no longer undoable (undo stack was cleared)."
- Batch window expires before all events are collected → send what was collected so far.

**Files:** new `DiscordWebhook.java` (**DOES NOT EXIST** — confirmed by full-codebase grep; must be built from scratch with embed format, batching, and per-event toggles), `CustomBlocksConfig.java` (discord event toggles, bot token field), `GuiManager.java` (Integrations tab Discord section), new `DiscordBotListener.java` (HTTP endpoint for bot callbacks), `UndoManager.java` (expose undo-by-event-ID)

---
