# UI & Text (LANG1)

## 2. LANG1 — Command Hints Show `[<unknown_cb_tail>]`

[ ] **Code Written**
[ ] **Tested In-Game**

**State:** 🔴 BROKEN — confirmed in-game 2026-06-02. Typing `/cb resize` shows `[<unknown_cb_tail>]` in the action bar hint.

**Files:** `command/DidYouMean.java`

**Root cause:** `DidYouMean.java` line 52 registers a catch-all argument literally named `"unknown_cb_tail"`. Brigadier displays argument names directly in the action bar, so this raw internal name shows up as `[<unknown_cb_tail>]`.

**The Fix:** Rename the argument from `"unknown_cb_tail"` to something human-readable like `"subcommand"`. Also update the corresponding `getString(ctx, "unknown_cb_tail")` retrieval call on line 55.

**Test:** Type `/cb resize` — action bar hint should no longer show `[<unknown_cb_tail>]`.
