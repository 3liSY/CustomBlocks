# CustomBlocks Mod — AI Handoff Document

## Project
Fabric Minecraft mod (MC 1.21.1, Fabric API 0.104.0, Yarn mappings 1.21.1+build.3, Java 21).
Source root: `CustomBlockss/src/main/java/com/customblocks/`
Build: `.\gradlew.bat classes` (must set `JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot`)

---

## What Was Completed This Session

| ID | Description | Status |
|----|-------------|--------|
| C2 | Wire all multi-step GUI flows to `DraftManager` (ESC/disconnect saves `SESSION_SHELL` draft; `/cb resume` restores) | ✅ Done |
| D3 | `TextureCache.invalidateIfChanged` — CRC32 hash-skip prevents redundant GPU re-uploads | ✅ Done |
| D5 | `TextureCache.schedulePreload` — off-thread NativeImage pre-decode via daemon pool | ✅ Done |
| D6 | `CbScreenHandler.refreshWith` — diff-only slot update using `ItemStack.areEqual` | ✅ Done |
| F1/F3 | Action bar + title feedback at block create, retexture, face-set, delete, undo, redo | ✅ Done |
| H1a | Lock padlock toggle in editor GUI (slot 44); favorites star toggle (slot 35); lock guard blocks mutations | ✅ Done |
| H1b | Undo picker pagination — `getUndoEntries(uuid, offset, max)` overload; prev/next buttons slots 46/52 | ✅ Done |

---

## Remaining Work

See **REMAINING_REPAIR_MASTERPLAN.md** for the full prioritized finding list (R.1–R.32). As of 2026-05-26 the following are the main open items:

### Medium priority (not yet implemented)
- **R.9** — `/cb config ai-key`, `ai-provider`, `ai-variations`, `ai-style` subcommands in CustomBlockCommand.java
- **R.11** — ColorTriangleItem recolor preview GUI (Phase 3.5)
- **R.12** — Script vs. Macro storage separation (currently share MacroManager + same directory)
- **R.13** — WELCOME_MENU content (currently minimal — one Nether Star and a back button)
- **R.14** — Verify DropConfigManager.load() is called at server startup
- **R.15** — Race condition in getPackUrl() volatile double-read
- **R.16** — HTTP connection leak in getExternalIp() (no try-with-resources, no timeout)
- **R.28** — generateSingleSlot() stale per-face/variant file cleanup

### Low priority
- **R.18** — DiagnosticsHelper GUI audit uses hardcoded stub list instead of registration-based check
- **R.19** — FACE_IMPORTS map entries not TTL-evicted for crashed clients
- **R.27** — validateUrlSecurity() passes URLs when DNS resolution fails (SSRF edge case)
- **R.29** — Client-side ResourcePackGenerator skips power-of-2 validation
- **R.31** — POST /pack Cloudflare Worker route has no rate limiting
- **R.32** — KV pack TTL is 24h (should be removed or extended to 30d)

### Previously documented H2/Q items (from original HANDOFF session)
- **H2 Atomic confirm threshold**: `bulkConfirmThreshold` config field + second-confirm click for bulk ops
- **H2 Safe delete 15-second undo link**: clickable chat undo after `/cb delete` via command path
- **Q `/cb recover` richness**: show deleted blocks from UndoManager with restore buttons
- **Q `/cb panic` two-step**: timestamp-based 5-second re-confirm window

---

## Critical Gotchas

### 1. UTF-8 BOM (MOST IMPORTANT)
The Edit tool sometimes writes files with a BOM (`\uFEFF`), causing `illegal character` compile errors on every import line. **Always check after edits:**
```powershell
$bytes = [System.IO.File]::ReadAllBytes("path\to\File.java"); $bytes[0]  # Must be 112 ('p')
```
If BOM present, strip it:
```powershell
$content = [System.IO.File]::ReadAllText($path).TrimStart([char]0xFEFF)
[System.IO.File]::WriteAllText($path, $content, [System.Text.UTF8Encoding]::new($false))
```

### 2. SoundEvents type split (Yarn 1.21.1+build.3)
- `SoundEvents.BLOCK_NOTE_BLOCK_*` → `RegistryEntry<SoundEvent>` → **needs `.value()`**
- All others (ENTITY_EXPERIENCE_ORB_PICKUP, BLOCK_AMETHYST_BLOCK_CHIME, BLOCK_BEACON_ACTIVATE, etc.) → bare `SoundEvent` → **must NOT use `.value()`**

### 3. Key types
- `DraftManager.Draft` record: `payload()` not `data()`; `DraftManager.take(uuid)` returns `Optional<Draft>`
- `SlotData` is NOT a record: use `d.customId` (public field), not `d.id()`
- `GuiState` record: `(GuiMode mode, String editingId, int page, boolean confirmDelete, int shapeBoxPage, boolean fromCommand)`

### 4. Build command
```powershell
Set-Location "c:\Users\66664\OneDrive\Desktop\Coding\CustomBlockss"
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot"
.\gradlew.bat classes
```
Last build: **BUILD SUCCESSFUL** (all H1 changes verified green).

---

## Key File Map

| File | Role |
|------|------|
| `gui/GuiManager.java` | All GUI open/build/click logic (~8000+ lines) |
| `gui/FeedbackHelper.java` | Sound/particle/actionBar/title/bossBar feedback |
| `gui/CbScreenHandler.java` | Screen handler; `refreshWith(SimpleInventory)` |
| `gui/GuiMode.java` | Enum of all GUI screen types |
| `gui/GuiState.java` | Immutable state record per player |
| `core/DraftManager.java` | Save/restore multi-step flow state across disconnect |
| `core/UndoManager.java` | Undo/redo stacks (global + per-player); 10k depth |
| `core/LockManager.java` | Per-block lock persistence (`locks.json`) |
| `core/FavoritesManager.java` | Per-player favorites (`favorites.json.gz`) |
| `core/SlotManager.java` | Block slot CRUD, persistence |
| `core/SlotData.java` | Block data object (NOT a record) |
| `client/texture/TextureCache.java` | GPU texture cache with CRC32 skip + pre-decode pool |
| `command/CustomBlockCommand.java` | All `/cb` subcommands (~3000 lines) |
| `command/ChatHelper.java` | Branded message helpers |
| `command/PermissionHelper.java` | LuckPerms/OP permission checks |
| `CustomBlocksConfig.java` | Config persistence (JSON); `maxUndoDepth=10000` |

---

## Patterns Used

```java
// Send action bar overlay
FeedbackHelper.actionBar(player, "§a§l✔ §r§aCreated: §f" + name);

// Send full-screen title
FeedbackHelper.title(player, "§a§l✔ Created!", "§f" + name);

// Save draft on disconnect/ESC
DraftManager.save(uuid, DraftManager.Kind.SESSION_SHELL, Map.of(
    "guiMode", state.mode().name(), "editingId", state.editingId() != null ? state.editingId() : "",
    "page", state.page(), "shapeBoxPage", state.shapeBoxPage(),
    "fromCommand", state.fromCommand(), "confirmDelete", state.confirmDelete()));

// Restore draft
Optional<DraftManager.Draft> opt = DraftManager.take(uuid);
Map<String,Object> data = opt.get().payload();

// Lock check in editor handler
if (LockManager.isLocked(id) && slot != 44 && slot != 0 && slot != 2 && slot != 43 && slot != 45) {
    playError(player);
    FeedbackHelper.actionBar(player, "§c§l🔒 §r§cLocked — /cb unlock " + id);
    return;
}
```
