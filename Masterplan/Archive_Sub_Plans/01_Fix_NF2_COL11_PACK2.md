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

---

## 4. COL11 Regression: "Already Red" Bug & Hex Fallbacks
[ ] **Code Written**
[ ] **Tested In-Game**

**State:** ?? BROKEN � Bug introduced by the previous COL11 fix.
**Files:** item/ColorSquareItem.java
**Technical Details:** 
* The previous COL11 fix checked if the fallback base block equalled the current block, and incorrectly assumed that meant it was *already* the target color. This hid the "No variant exists" error entirely.
* Additionally, when a user creates a named variant (e.g., discord_red), using a Hex Square (e.g., #EE3333) fails because it specifically looks for discord_hex_ee3333.
**The Fix:** 
1. Re-implement the base block check to only report "Already [Color]" if current.cachedColorFamily *actually matches* the target color.
2. Implement **Hex Fallbacks**: If a Hex Square cannot find its exact hex variant, it will fall back to searching for a standard named variant (like _red) that matches the hex's closest label.

