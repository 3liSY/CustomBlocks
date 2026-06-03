import os

completed_path = r'c:\Users\66664\OneDrive\Desktop\Coding\CustomBlockss\Masterplan\Completed_Implementations.md'
rules_path = r'c:\Users\66664\OneDrive\Desktop\Coding\CustomBlockss\Masterplan\Rules_For_AI.md'

table_content = """
---

## Summarized Log of Completed Implementations

| ID | Issue Name | Why (Root Cause) | How (The Fix) | How to Test (Test Plan) |
|---|---|---|---|---|
| **PACK2** | `/cb rp pause` Broken; Magic Items Become Dyes | Vanilla fallback models were being sent to modded clients overriding local packs. | Added a check in `sendPackToPlayer()` to skip modded clients (via `canSend`). | Run `/cb rp pause`, modify a block, verify blocks don't go transparent and tools stay colored. |
| **COL1** | Color Square Client-Side Prediction | Server latency caused visual delay before `BlockUpdateS2CPacket` arrived. | Instantly swap the block on the client using `resolveTargetId` prediction. | Right-click custom block with color tool, verify zero visual delay. |
| **NF2** | Deleter Tool Item | Feature was missing; no dedicated tool to instantly delete or bulk trash. | Created `DeleterItem.java`, new textures/models, GUI confirm screen, and shift-to-delete. | Use `/cb deleter`. Right-click block for GUI, shift-click to instant delete. |
| **COL2** | Remove Runtime ImageIO Fallback | Lazy `ColorDetection.detect()` wasted 50-200ms on server thread during color tool use. | Removed fallback; strictly use authoritative `cachedColorFamily` from `SlotData`. | Right-click a block with a color tool; verify no delay. |
| **REDO1** | `/cb redo` Says "Nothing to Redo" | Redo entry pushed before snapshot restored, and lacked `playerUuid`. | Pushed redo AFTER restore, and passed `uuid` to constructor. | Delete a block -> `/cb undo` -> `/cb redo` -> verify block disappears again. |
| **COL11** | Color Tool on Base Block Error | Recolor algorithm hit a silent pass on base blocks, throwing a false error later. | Added an action-bar message "Already [color]" before returning `SUCCESS`. | Use color tool on a base block, verify it gracefully shows "Already [color]". |
| **IMG4-S3** | Transparent-Background Images Showed White | `replaceBackground()` composited transparent pixels against pure white at the end. | In Stage 3, check `alpha == 0` and force pixel to `BLACK` before white composite. | Import an image with a true transparent background (like Discord logo), verify it looks correct. |
| **PACK1** | Pack Download Fails (HTTP 404) | Server sent download URL to clients before the ZIP build / Cloud upload completed. | Moved `sendUpdateToAllPlayers()` to trigger only AFTER ZIP / upload is finished. | Trigger a pack rebuild (e.g., `/cb reload`), verify no "pack failed to download" error. |
| **C1** | Null Check Crashes (21 locations) | Clicking GUI buttons for blocks that were just deleted threw NullPointerExceptions. | Added `if (d == null) return` safely to all 21 GUI handler lines. | Delete a block, keep its GUI open, click buttons -> verify no crash. |
| **G2** | ESC from Bulk Delete Closes Everything | `openBulkOpPicker` didn't push back stack state, breaking ESC navigation. | Added `pushBackStack` to the start of `openBulkOpPicker`. | Open Bulk Delete, press ESC -> verify it goes to the previous screen. |
| **G4** | Bulk Delete Has Two Entry Points | Redundant GUI entry points for the same bulk delete action. | Removed direct `openBulkDelete` paths in favor of `openBulkHub`. | Access bulk delete via GUI or commands, verify it routes properly. |
| **G5** | Remove `/cb helpgui` | Command bloat; `/cb help` does the exact same thing. | Deleted the `.then(literal("helpgui"))` node from command tree. | Type `/cb helpgui`, verify it doesn't exist. |
| **G6** | Unify `/cb`, `/cb gui`, `/cb menu` | Multiple commands routed to different or legacy onboarding screens. | Simplified command tree to always call `openWelcomeGui(player)`. | Run `/cb`, `/cb gui`, `/cb menu` -> verify they all open the main welcome screen. |
| **UND1** | Bulk Undo as a Single Batch | Bulk delete pushed individual block undos, requiring many `/cb undo` calls. | Added `BatchDelta` type and `pushUndoBatch()`, plus a confirm GUI dialog. | Bulk delete multiple blocks -> `/cb undo` -> confirm dialog -> verify all return at once. |
| **REDO2** | Bulk Redo After Batch Undo | Batch undo didn't push anything to the redo stack after restoring blocks. | Pushed `BatchDelta` to the redo stack after a batch undo completes. | Bulk delete -> `/cb undo` -> `/cb redo` -> verify all blocks are deleted again. |
| **COL6** | Variant Naming | Color tools didn't assign readable color names (like "Dark Red"). | Overrode `NBT_CUSTOM_NAME` using a delta-E `labelForRgb()` dictionary match. | Create a `#8B0000` tool -> verify it's named "Dark Red". |
| **COL7** | Glint Always On | Color Square and Triangle lacked visual distinction in creative menu. | Configured items to always have enchantment glint active. | Look at Color Square/Triangle in inventory -> verify shiny glint. |
| **COL8b** | Red Hex Editor in `/cb config` | Green and yellow had a hex shade editor, but red was missing. | Added the Red Shade hex config button in the GUI builder. | Open `/cb config` -> verify Red hex editor is present. |
| **CMD1** | `/cb settings` Alias | No intuitive alias existed for the configuration menu. | Added `/cb settings` alias mapped to `/cb config`. | Type `/cb settings` -> verify it opens the config menu. |
| **IMG3** | `bgRemovalEnabled` Global Toggle | Developers needed a way to completely bypass all background removal logic globally. | Added config field and `if (!bgRemovalEnabled)` check to `ImageProcessor`. | Toggle off in config -> import image -> verify background is kept. |
"""

rule_content = """7. **"Maintain the Completed Implementations Summary Table."**
   When moving a confirmed or completed issue from `MASTERPLAN.md` into `Completed_Implementations.md`, you MUST append it to the file using the exact same format as existing entries. Most importantly, you MUST update the "Summarized Log of Completed Implementations" table at the bottom of the file. Add a new row containing the `ID`, `Issue Name`, `Why (Root Cause)`, `How (The Fix)`, and `How to Test (Test Plan)`. Keep it organized, surgical, and meticulously detailed.
"""

with open(completed_path, 'a', encoding='utf-8') as f:
    f.write(table_content)

with open(rules_path, 'a', encoding='utf-8') as f:
    f.write(rule_content)

print("Successfully updated both files!")
