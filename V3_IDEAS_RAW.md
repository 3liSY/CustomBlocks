# CustomBlocks v3 — Raw Ideas (captured 2026-05-14)

## Pain Points (user-reported)

### 1. Bulk commands are too limited
- Wants bulk operations for "so many stuff" beyond just recolor/delete
- Needs expanding: bulk rename? bulk move category? bulk export? bulk property changes?
- (flesh out exactly which operations)

### 2. Black block detection is broken
- If a block's default background is black BUT its ID doesn't contain "black", the mod doesn't recognize it as a black variant
- Square tool can't swap a green/yellow block to black if the original block doesn't have "black" in the ID
- Root cause: color detection relies on ID string matching instead of actual texture/color analysis
- Fix: detect the block's color from its actual texture pixels, not from its ID name

### 3. Hex color studio (Square/Triangle) is poorly made
- Current hex creation flow needs rework
- "needs improvements and much more stuff that needs thinking"
- (needs deeper exploration of what specifically is bad about it)

## Open threads to explore
- What other bulk commands specifically?
- What's bad about the hex studio — the UI? the color picker? the output quality?
- Any other daily-use annoyances?
- Performance issues?
- Missing QoL features?

---
*This is a raw dump. Will be refined into a proper v3 masterplan.*
