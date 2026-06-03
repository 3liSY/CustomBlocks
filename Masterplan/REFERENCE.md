# CustomBlocks Reference & Gotchas

## Reference

### Build Command
```powershell
# Run at the start of every session before deploying
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
Set-Location "C:\Users\66664\OneDrive\Desktop\Coding\CustomBlockss"
.\gradlew.bat build
# Deploy: build/libs/customblocks-1.0.0.jar  (NOT -dev or -sources)
# The live server may be running an older version — always build fresh first.
# SpotBugs exits 1 as usual — not a failure, the build still succeeds.
```

### Critical Gotchas

**Read these before touching any code. Violating them causes silent bugs or build failures.**

**1. UTF-8 BOM (MOST IMPORTANT)** — The Edit tool sometimes writes files with a BOM (`﻿`), causing `illegal character` compile errors on every import line. Check after any edit:
```powershell
$bytes = [System.IO.File]::ReadAllBytes("path\to\File.java")
$bytes[0]  # Must be 112 ('p') — if it's 239, you have a BOM
```
Strip it if present:
```powershell
$path = "path\to\File.java"
$content = [System.IO.File]::ReadAllText($path).TrimStart([char]0xFEFF)
[System.IO.File]::WriteAllText($path, $content, [System.Text.UTF8Encoding]::new($false))
```

**1b. Curly-Quote Corruption (discovered Session 2)** — The Edit tool sometimes converts ASCII `"` into Unicode curly quotes `U+201C "` / `U+201D "` inside string literals. Java won't compile these — you get a cascade of `';' expected`, `illegal character: '“'`, `not a statement` errors that look unrelated. The `verifyMojibake` gradle task does NOT catch it. If a build dies with illegal-character errors right after a GUI/lore edit, run this on the file before debugging the logic:
```powershell
$path = "path\to\File.java"
$content = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
$fixed = $content.Replace([char]0x201C, [char]0x0022).Replace([char]0x201D, [char]0x0022)
[System.IO.File]::WriteAllText($path, $fixed, (New-Object System.Text.UTF8Encoding($false)))
```

**2. SoundEvents Type Split (Yarn 1.21.1+build.3)**
- `SoundEvents.BLOCK_NOTE_BLOCK_*` → type is `RegistryEntry<SoundEvent>` → **needs `.value()`**
- All other SoundEvents constants (ENTITY_EXPERIENCE_ORB_PICKUP, BLOCK_AMETHYST_BLOCK_CHIME, BLOCK_BEACON_ACTIVATE, etc.) → bare `SoundEvent` → **must NOT use `.value()`**

**3. Key Types — Do Not Guess These**
- `DraftManager.Draft` is a record — use `.payload()` **not** `.data()`
- `DraftManager.take(uuid)` returns `Optional<Draft>` — check `.isPresent()` first
- `SlotData` is **NOT a record** — use `d.customId` (public field), **not** `d.id()` or `d.customId()`
- `GuiState` is a record with exactly these fields in order: `(GuiMode mode, String editingId, int page, boolean confirmDelete, int shapeBoxPage, boolean fromCommand)`

### Key File Map
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
| `core/ImageProcessor.java` | Image download + background removal |
| `client/texture/TextureCache.java` | GPU texture cache with CRC32 skip + pre-decode pool |
| `command/CustomBlockCommand.java` | All `/cb` subcommands (~3000 lines) |
| `command/ChatHelper.java` | Branded message helpers |
| `command/PermissionHelper.java` | LuckPerms/OP permission checks |
| `network/ResourcePackServer.java` | Serves the pack ZIP over HTTP |
| `network/ResourcePackManager.java` | Schedules + builds the pack, notifies clients |
| `CustomBlocksConfig.java` | Config persistence (JSON); `maxUndoDepth=10000` |

### Code Patterns Reference
```java
// Send action bar overlay
FeedbackHelper.actionBar(player, "§a§l✔ §r§aCreated: §f" + name);

// Send full-screen title
FeedbackHelper.title(player, "§a§l✔ Created!", "§f" + name);

// Save draft on disconnect/ESC
DraftManager.save(uuid, DraftManager.Kind.SESSION_SHELL, Map.of(
    "guiMode", state.mode().name(),
    "editingId", state.editingId() != null ? state.editingId() : "",
    "page", state.page(),
    "shapeBoxPage", state.shapeBoxPage(),
    "fromCommand", state.fromCommand(),
    "confirmDelete", state.confirmDelete()));

// Restore draft
Optional<DraftManager.Draft> opt = DraftManager.take(uuid);
Map<String, Object> data = opt.get().payload(); // use .payload() NOT .data()

// Clickable link in chat
new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.OPEN_URL, url)
// (see ChatHelper.java; CustomBlockCommand uses this in several places)

// Lock check in editor handler
if (LockManager.isLocked(id) && slot != 44 && slot != 0 && slot != 2 && slot != 43 && slot != 45) {
    playError(player);
    FeedbackHelper.actionBar(player, "§c§l🔒 §r§cLocked — /cb unlock " + id);
    return;
}
```


