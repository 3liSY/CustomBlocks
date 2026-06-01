# Active Batch: NF2, COL11, and PACK2

*This batch was moved from the main backlog. View the full backlog here: [MASTERPLAN.md](../../../../MASTERPLAN.md)*

---

## 1. PACK2: /cb rp pause breaks blocks
[x] **Code Written**
[x] **Tested In-Game** ✅ Confirmed working 2026-06-01

**State:** 🔴 BROKEN — root cause confirmed.
**Files:** `network/ResourcePackServer.java`
**Technical Details:** 
* `ServerPackGenerator.java` builds the ZIP with fallback vanilla items (e.g., dyes instead of custom tools) so vanilla players don't crash.
* When `/cb rp pause` runs, modifying a block still triggers `SlotManager.saveSlots()`, which rebuilds the pack without checking the pause state.
* `sendUpdateToAllPlayers()` broadcasts the update to everyone, forcing modded clients to receive the vanilla fallback pack.
**The Fix:** Modify the send logic so modded clients never receive the fallback pack.

---

## 2. COL11: Color Tool on Base Blocks
[x] **Code Written**
[x] **Tested In-Game** ✅ Confirmed working 2026-06-01

**State:** 🔨 BUILT — previous silent fix was wrong.
**Files:** `item/ColorSquareItem.java`, `item/ColorTriangleItem.java`
**Technical Details:** 
* Using a color tool on a base block (like 'BonBon' with no variant) falls back to itself.
* Currently, it hits `return ActionResult.SUCCESS;` silently, providing no feedback.
**The Fix:** Before returning success on the self-same check, add `player.sendMessage(...)` so it correctly prints `[CB] Already [color]` in the action bar.

---

## 3. NF2: Deleter Tool Polish
[x] **Code Written**
[x] **Tested In-Game** ✅ Confirmed working 2026-06-01

**State:** 🔴 BROKEN
**Files:** `item/DeleterItem.java`, `gui/GuiManager.java`, `texturegen/GenerateDeleterTexture.java`
**Technical Details:** 
* Chat prefix shows raw red `[CB]` instead of standard Aqua/White formatting in `DeleterItem.java`.
* The block does not visually delete on the client-side (requires a block update via `GuiManager.java`).
* `executeBulkOpFromGui` case `"delete"` skips `TrashManager.addToTrash`.
* Texture (generated via `GenerateDeleterTexture.java`) needs to be edited better.
