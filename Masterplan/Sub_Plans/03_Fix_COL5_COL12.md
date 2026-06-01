# Active Batch: COL5, COL12

*This batch was moved from the main backlog. View the full backlog here: [MASTERPLAN.md](../../../../MASTERPLAN.md)*

---

## 1. COL5: Tooltips / First Use Hints are Spammy
[ ] **Code Written**
[ ] **Tested In-Game**

**State:** 🔴 BROKEN — First Use Hints in chat are too wordy and annoying.
**Files:** `item/ColorSquareItem.java`, `item/ColorTriangleItem.java`
**Technical Details:** 
* `FirstUseHints` properly limits the main hint to one-time display, but `ColorSquareItem` and `ColorTriangleItem` forcibly append additional strings to the chat message when the hint fires.
* The appended strings like "Shift+right-click to preview first. Mode: Background + Enclosed Areas" spam the chat unnecessarily.
**The Fix:** Remove the appended chat messages from the inventory tick handlers in the tool items.

---

## 2. COL12: Random blocks say "No texture data to recolour"
[ ] **Code Written**
[ ] **Tested In-Game**

**State:** 🔴 BROKEN — NBT/SlotManager disconnect.
**Files:** TBD
**Technical Details:** 
* Needs investigation on why certain custom blocks lose their texture link or slot data, causing the recolor tool to fail.
**The Fix:** TBD
