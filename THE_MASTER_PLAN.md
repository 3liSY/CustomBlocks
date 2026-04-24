# 👑 THE MASTER PLAN
> **8 Features That Will Make CustomBlocks Legendary**
> *Bound by [THE ROYAL DIRECTIVE](THE_ROYAL_DIRECTIVE.md) — the development constitution of this project.*

---

> [!CAUTION]
> **THIS PLAN IS GOVERNED BY THE ROYAL DIRECTIVE (v2.0)**
>
> Every feature in this document MUST be implemented in strict compliance with every section of the Directive:
>
> | Directive Section | Requirement | Compliance |
> |-------------------|-------------|------------|
> | **§ 1** — The Human Behind the Code | Respect the developer's sacrifice. Break the frustration cycle. | Every feature must reduce stress, not create it |
> | **§ 2** — Creative Artist Protocol | No boring items. Chat branding. Deep lore. UI depth. | Every GUI button uses legendary items + story lore |
> | **§ 2B** — The Sensory Layer | Sound + particles on EVERY interaction | See feedback table below |
> | **§ 3** — Surgical Development Protocol | ONE thing at a time. `./gradlew build` after EVERY edit. Research first. | Phase-locked. No multi-file edits without builds. |
> | **§ 4** — Technical Holy Grails | NEVER break: CDN packs, GUI back-stack, SlotData immutability, sound linkage, animation metadata | Every feature is tested against all 5 grails |
> | **§ 5** — Layered Defense Doctrine | Multi-layer protection. Single-layer is forbidden. | Critical paths get layers 1-4 minimum |
> | **§ 6** — Race Condition Safety | Atomic file ops. Immutable snapshots. No shared mutable state. | All file writes use temp→atomic rename |
> | **§ 7** — Bug Elimination | Root cause removal, not symptom patching | Elimination checklist on every fix |
> | **§ 8** — Rollback Safety Net | Git checkpoint BEFORE every phase. 60-second revert. | Checkpoint commands documented per phase |
> | **§ 9** — Zero Jargon | Speak human. "Communication Door" not "port". Auto-explain everything. | All GUI text reviewed for jargon |
> | **§ 10** — Definition of Done | Friend Test + Liquid UI Test + WOW Test | All 3 must pass before a feature ships |
> | **§ 12** — Royal Standard of Excellence | Visual Mastery. Sensory Sovereignty. Emotional Craftsmanship. | The developer must feel *proud*, not just relieved |

### Sensory Feedback Table (from § 2B)

Every feature in this plan must implement these feedback rules:

| Trigger | Visual Feedback | Audio Feedback |
|---------|----------------|----------------|
| Button click | `ENCHANT` or `GLOW` particles | `BLOCK_AMETHYST_BLOCK_CHIME` |
| Successful action | `COMPOSTER` burst | `ENTITY_EXPERIENCE_ORB_PICKUP` |
| Tool usage | `SOUL_FIRE_FLAME` trail | `BLOCK_NOTE_BLOCK_CHIME` |
| Error / warning | `SMOKE` puff | `BLOCK_NOTE_BLOCK_BASS` |

**The Rule (§ 2B):** *If a player clicks a button and nothing sparkles, nothing chimes — you haven't finished the feature yet.*

---

## What This Document Is

This is the long-term feature roadmap for CustomBlocks. Each feature is explained in simple, human language (§ 9) so **anyone** — a tired developer at 3am, a fresh AI reading this for the first time, or a player who just wants cool blocks — can understand exactly what it does, how to use it, and what they can customize.

Every feature is designed to be **deeply customizable** — the player controls everything through in-game GUIs and commands. No config files, no external tools, no jargon.

---

## Feature 1: Texture Filters

### What is it?
An in-game image editor for your block textures. Instead of opening Photoshop, editing an image, re-uploading it, and pasting a new URL — you just open a GUI inside Minecraft and apply filters with one click.

### How does the player use it?
1. Open the Block Editor for any block
2. Click a new **"Filters"** button
3. A GUI opens showing all available filters
4. Click a filter (e.g. "Tint Red") → the block texture changes instantly
5. Don't like it? Click undo. Want more? Stack multiple filters.

### Available Filters:

| Filter | What it does | Player controls |
|--------|-------------|-----------------|
| **Tint** | Overlays a color on the texture | Pick ANY color (preset colors in GUI + custom hex input). Pick intensity: 10% (subtle hint) to 100% (solid color wash) |
| **Brightness** | Makes the texture lighter or darker | -100 (pitch black) to +100 (pure white), adjustable with +/- buttons |
| **Contrast** | Makes lights lighter and darks darker | -100 (flat gray) to +100 (extreme contrast), adjustable with +/- buttons |
| **Hue Shift** | Rotates all colors around the color wheel | 0° to 360° — so red becomes blue, blue becomes green, etc. Player picks the angle |
| **Saturation** | Makes colors more vivid or more gray | -100 (completely grayscale) to +100 (extremely vivid) |
| **Grayscale** | Strips all color, makes it black and white | On/off toggle. No extra settings needed |
| **Invert** | Flips all colors to their opposite | On/off toggle. White becomes black, red becomes cyan |
| **Mirror** | Flips the texture | Player picks: Horizontal, Vertical, or Both |
| **Rotate** | Turns the texture | Player picks: 90°, 180°, or 270° clockwise |
| **Pixelate** | Makes it look chunky/retro | Block size: 2px (subtle), 4px (noticeable), 8px (very blocky), 16px (extreme) |
| **Blur** | Softens the texture | Strength: 1 (light softening) to 5 (very blurry) |
| **Sharpen** | Makes edges crisper | Strength: 1 (subtle) to 5 (extreme edge enhancement) |
| **Noise** | Adds random grain/speckle | Intensity: 5% (subtle film grain) to 50% (heavy static). Color or monochrome |
| **Posterize** | Reduces color depth for a poster/cartoon look | Levels: 2 (extreme), 4 (stylized), 8 (subtle), 16 (very subtle) |

### Customization the player has:
- **Apply to main texture, specific faces, or all faces** — the player chooses which part of the block to filter
- **Stack filters** — apply tint, then blur, then pixelate — they combine
- **Preview before saving** — see the result in the GUI item display before committing
- **Undo any filter** — each filter application is an undo step
- **Per-face filters** — tint just the top face red and leave the sides untouched
- **Filter presets** — save a combination of filters as a named preset (e.g. "Aged Stone" = desaturate 40% + noise 10% + brightness -15%)
- **Batch apply** — apply filters to multiple blocks at once via a selection GUI

### Command alternative:
```
/cb filter <blockId> tint #FF0000 50
/cb filter <blockId> brightness -20
/cb filter <blockId> grayscale
/cb filter <blockId> preset aged_stone
```

---

## Feature 2: Color Palette Generator

### What is it?
You have one block that looks great. You want 16 color versions of it (like how Minecraft has 16 colors of wool, concrete, and terracotta). Instead of making each one by hand, you click one button and the mod creates them all automatically.

### How does the player use it?
1. Open the Block Editor for any block
2. Click **"Generate Palette"**
3. A GUI opens showing color options
4. Pick which colors you want (checkboxes for each color)
5. Click "Generate" → the mod creates all the color variants instantly

### Customization the player has:

**Color selection:**
- **Preset palettes:** "Minecraft 16" (all 16 vanilla dye colors), "Warm" (reds/oranges/yellows), "Cool" (blues/greens/purples), "Earth" (browns/tans/greens), "Neon" (bright vivid colors), "Pastel" (soft light colors)
- **Custom colors:** Pick individual colors by clicking colored wool/dye items in the GUI
- **Custom hex:** Type exact hex codes for precise colors
- **How many:** Generate 2 to 16 variants — your choice

**Naming:**
- **Auto-naming pattern:** Choose how variants are named. Options:
  - `{name} ({color})` → "Marble (Red)", "Marble (Blue)"
  - `{color} {name}` → "Red Marble", "Blue Marble"
  - `{name}_{color}` → "marble_red", "marble_blue" (for the block ID)
- **Custom suffix:** Type your own pattern

**What gets tinted:**
- **Main texture only** — just the main texture, faces stay as-is
- **All faces too** — tint every face texture to match
- **Selective** — pick which faces get tinted

**Tint intensity:**
- Light (20%) — subtle color hint, original texture shines through
- Medium (50%) — balanced blend
- Strong (80%) — mostly the new color
- Custom (0-100%) — exact control

**Tint mode:**
- **Overlay** — lays color on top (keeps shadows and highlights)
- **Multiply** — blends with existing colors (darker, more natural)
- **Replace Hue** — replaces the original color but keeps brightness/contrast

**Other options:**
- **Copy properties** — new blocks inherit light level, hardness, sound, shape, collision from the original
- **Copy all faces** — face overrides are copied and tinted too
- **Skip existing** — if "marble_red" already exists, skip it instead of erroring

### Command alternative:
```
/cb palette <blockId> minecraft16 50%
/cb palette <blockId> colors:#FF0000,#00FF00,#0000FF intensity:60 mode:overlay
/cb palette <blockId> preset:warm naming:{color}_{name}
```

---

## Feature 3: Redstone-Reactive Blocks

### What is it?
Custom blocks that **change their appearance when powered by redstone**. You give the block two looks — one for when it's OFF and one for when it's ON. Flip a lever, the block transforms.

### How does the player use it?
1. Open the Block Editor for any block
2. Click **"Redstone Behavior"**
3. A GUI opens with two sides: "Unpowered State" (left) and "Powered State" (right)
4. Set what changes when the block receives a redstone signal
5. Place the block in the world, hook up a lever — watch it change

### What can change when powered:

| Property | Unpowered → Powered | Player controls |
|----------|---------------------|-----------------|
| **Texture** | Stone wall → Open doorway | Paste a different URL/image for the powered state |
| **Light Level** | 0 (dark) → 15 (bright) | Set exact light level for each state |
| **Visibility** | Visible → Invisible | Toggle: block becomes see-through when powered (great for secret doors) |
| **Shape** | Full cube → Slab | Pick a different shape preset for each state |
| **Particles** | None → Flame | Enable particles only when powered (ties into Feature 4) |
| **Sound** | Silent → Plays sound | Play a sound when the state changes |

### Customization the player has:

**Trigger mode:**
- **Toggle** — powered = state A, unpowered = state B (like a light switch)
- **Pulse** — block changes briefly when redstone pulses, then reverts (like a doorbell)
- **Cycle** — each redstone pulse advances to the next state (supports 2-8 states, not just on/off)

**Delay:**
- How many ticks before the block reacts: 0 (instant), 5 (quarter second), 10 (half second), 20 (one second)
- Useful for creating chain reactions with staggered timing

**Multiple states (Cycle mode):**
- Instead of just ON/OFF, define up to 8 different states
- Each state has its own texture, light level, shape
- Each redstone pulse cycles: State 1 → State 2 → State 3 → ... → State 1
- Example: A traffic light block that cycles Red → Yellow → Green

**Detection mode:**
- **Direct power** — only reacts when redstone directly touches the block
- **Indirect power** — reacts when an adjacent block is powered (like a door)
- **Player proximity** — changes when a player is within X blocks (no redstone needed!)
- **Time of day** — changes at dawn/dusk (decorative lighting that turns on at night)

**Transition effect:**
- **Instant** — snaps to new state immediately
- **Fade** — if the block is animated (GIF), smoothly transitions between states

### Command alternative:
```
/cb redstone <blockId> mode:toggle
/cb redstone <blockId> powered-texture <url>
/cb redstone <blockId> powered-light 15
/cb redstone <blockId> trigger:proximity range:5
/cb redstone <blockId> addstate <url> light:10 shape:slab
```

---

## Feature 4: Particle Emitter Blocks

### What is it?
Custom blocks that shoot out particles. Make blocks that have fire on top, smoke rising, sparkles floating around, water dripping — any Minecraft particle effect, continuously or on command.

### How does the player use it?
1. Open the Block Editor for any block
2. Click **"Particle Effects"**
3. A GUI opens showing all available particle types as items
4. Click a particle (e.g. "Flame") → configure it → save
5. Place the block in the world → particles appear

### Available particle types (30+):
Flame, Soul Flame, Smoke, Large Smoke, Campfire Smoke, End Rod, Dripping Water, Dripping Lava, Heart, Note, Sparkle, Enchantment, Portal, Bubble, Snowflake, Cherry Petals, Sculk, Spore Blossom, Dust (custom color), Crimson/Warped Spore, Ash, White Ash, Glow, Flash, Rain, Totem of Undying, Wax On, Shriek, Electric Spark, Scrape, and more.

### Customization the player has:

**Per-emitter settings (each block can have up to 3 emitters running at once):**

| Setting | What it controls | Range |
|---------|-----------------|-------|
| **Particle type** | Which particle to spawn | Any of the 30+ types above |
| **Rate** | How many particles per second | 1 (rare trickle) to 40 (dense cloud) |
| **Spread X/Y/Z** | How far particles scatter from the block center | 0.0 (tight beam) to 2.0 (wide area) |
| **Speed** | How fast particles move | 0.0 (hover in place) to 1.0 (shoot outward) |
| **Direction** | Which way particles go | Up, Down, North, South, East, West, Random, Outward |
| **Offset X/Y/Z** | Where on the block particles spawn from | -0.5 to 1.5 (so you can put flames on top of a torch-shaped block) |
| **Color** | For color-able particles (Dust, etc) | Any RGB color, picked from GUI or typed as hex |
| **Size** | For size-able particles (Dust) | 0.1 (tiny) to 4.0 (huge) |

**Conditional spawning:**
- **Always** — particles are always active
- **Redstone only** — particles only appear when the block is powered (ties into Feature 3)
- **Night only** — particles appear after sunset (firefly effect)
- **Rain only** — particles appear during rain (steam rising from hot blocks)
- **Player nearby** — particles only appear when a player is within X blocks (performance saver)
- **Random chance** — each tick has X% chance to spawn (creates intermittent puffs instead of constant stream)

**Multiple emitters:**
- A single block can have up to 3 different particle emitters running at once
- Example: A "Campfire" block with Flame (going up from center) + Smoke (going up, wider spread) + Ember sparks (random direction, low rate)

**Performance controls:**
- **View distance** — particles only render for players within X blocks (default 32, max 64)
- **Server-wide particle limit** — admin can set max total particles from all emitter blocks (prevents lag from 1000 emitter blocks)

### Command alternative:
```
/cb particles <blockId> add flame rate:10 spread:0.3 direction:up offset:0,0.5,0
/cb particles <blockId> add smoke rate:5 spread:0.5 direction:up speed:0.1
/cb particles <blockId> condition redstone
/cb particles <blockId> clear
```

---

## Feature 5: Block Crafting Recipes

### What is it?
Custom blocks that players can **craft at a crafting table** instead of needing commands. The server admin defines the recipe using an in-game GUI — no JSON files, no datapacks, no restarts.

### How does the player use it?
1. Open the Block Editor for any block
2. Click **"Set Recipe"**
3. A GUI opens showing a 3×3 crafting grid on the left and the output on the right
4. Place items in the grid by clicking item buttons (shows vanilla items + all custom blocks)
5. Set the output count (how many blocks per craft)
6. Click "Save" → the recipe is live immediately for all players

### Customization the player has:

**Recipe type:**
- **Shaped** — items must be placed in an exact pattern (like a pickaxe: 3 across the top, 1 stick middle, 1 stick bottom)
- **Shapeless** — items can go in any arrangement (like mushroom stew: any 3 ingredients anywhere)
- **Mirrored** — shaped recipe also works when flipped horizontally (like a bed)

**Crafting station:**
- **Crafting table** — standard 3×3 crafting
- **Stonecutter** — 1 input → 1 output (good for block variants: "Stone → Custom Stone Slab")
- **Smithing table** — template + base + addition → output (good for "upgrading" blocks)

**Input items:**
- Any vanilla Minecraft item or block
- Any custom block from CustomBlocks
- **Item tags** — use "any planks" instead of a specific wood type, so oak/birch/spruce all work

**Output settings:**
- **Quantity** — how many blocks per craft: 1 to 64
- **Multiple outputs** — one recipe can give the main block + a bonus item (like crafting produces 4 slabs + returns a tool)

**Recipe management:**
- **Recipe book integration** — the recipe shows up in the vanilla recipe book so players can discover it
- **Recipe conditions** — recipe only works if player has an advancement/permission (for progression servers)
- **Override** — replace a vanilla recipe with your own (make crafting tables produce a custom table block)
- **Delete** — remove a recipe with one click
- **View all** — a GUI showing every custom recipe on the server, clickable to edit

**Survival integration:**
- **Drop on break** — when a player breaks a custom block (that has a recipe), it drops itself as an item (so players don't lose their crafted blocks). Customizable: always drop, drop with silk touch only, or never drop.
- **Loot tables** — custom blocks can appear in dungeon chests, mob drops, fishing, etc. Simple GUI to set which loot sources include this block and at what chance.

### Command alternative:
```
/cb recipe <blockId> shaped "PPP, S , S " P=planks S=stick count:4
/cb recipe <blockId> shapeless diamond,gold_ingot,<customBlockId> count:1
/cb recipe <blockId> stonecutter input:stone count:2
/cb recipe <blockId> delete
/cb recipes list
```

---

## Feature 6: Texture Randomizer

### What is it?
When you build a wall with the same block, every block looks identical — same exact texture repeating in a grid. It looks fake. Texture randomizer gives one block **multiple texture variants** that are randomly chosen each time the block is placed. Your wall looks natural instead of copy-pasted.

### How does the player use it?
1. Open the Block Editor for any block
2. Click **"Texture Variants"**
3. A GUI opens showing the current texture as "Variant 1"
4. Click "Add Variant" → paste a URL or import from folder → now there are 2 variants
5. Repeat to add up to 8 variants
6. Place the block → each placement randomly picks a variant

### Customization the player has:

**Number of variants:**
- 2 to 8 different textures per block

**Variant weights:**
- Each variant has a weight that controls how often it appears
- Default: all equal (each variant appears ~equally often)
- Custom: make variant 1 appear 70% of the time, variant 2 appear 20%, variant 3 appear 10%
- Useful for: "mostly normal stone, occasionally a stone with a crack, rarely a stone with moss"

**What gets randomized:**
- **Main texture only** — all 6 faces use the same randomly-picked variant
- **Per-face random** — each face of the block independently picks a random variant (chaotic, great for natural surfaces)
- **Rotation random** — same texture but randomly rotated 0°/90°/180°/270° (adds subtle variety without needing multiple textures)
- **Texture + Rotation** — both randomized for maximum natural look

**Variant sources:**
- **URL paste** — paste image URLs for each variant
- **Import from folder** — drop multiple images in the importfolder, assign them as variants
- **Auto-generate** — use Texture Filters (Feature 1) to auto-create variants: "take this texture and make 4 versions with slight brightness/hue variations"
- **Copy from another block** — pick variants from existing blocks

**Preview:**
- GUI shows all variants side-by-side so you can see how they look together
- "Simulate" button shows a mock 3×3 grid of the block with random variants applied, so you can preview how a wall would look

### How it works technically:
Minecraft already supports random texture models in blockstates using the `multipart` or `variants` array with `weight` fields. The mod generates these automatically in the resource pack. No client mod changes needed — it's pure resource pack magic.

### Command alternative:
```
/cb variants <blockId> add <url>
/cb variants <blockId> add <url> weight:3
/cb variants <blockId> remove 2
/cb variants <blockId> rotation on
/cb variants <blockId> list
/cb variants <blockId> autogenerate 4 hue:10 brightness:5
```

---

## Feature 7: Block Marketplace

### What is it?
An in-game store/gallery where players can browse, download, and share custom blocks with the entire CustomBlocks community. Think of it like the Steam Workshop — you open a catalog inside Minecraft, find a block you like, and import it to your server with one click.

This builds on top of the Cloud Share system (CB~ codes + Cloudflare Workers).

### How does the player use it?

**Browsing:**
1. Open the main CustomBlocks GUI
2. Click **"Marketplace"**
3. A GUI opens showing blocks from the community, displayed as items with previews
4. Browse by category, search by name, or sort by popularity
5. Click a block → see details (name, creator, description, preview)
6. Click "Import" → the block is added to your server

**Publishing:**
1. Open the Block Editor for any block you made
2. Click **"Publish to Marketplace"**
3. Fill in: title, description, pick a category, add tags
4. Click "Publish" → your block is now available to everyone

### Customization the player has:

**Browsing options:**
- **Categories:** Architecture, Nature, Furniture, Decorative, Industrial, Fantasy, Sci-Fi, Food, Vehicles, Flags, Letters/Numbers, Patterns, Other
- **Sort by:** Newest, Most Downloaded, Most Starred, Alphabetical
- **Search:** Type a name to filter results
- **Filter by tags:** medieval, modern, wood, stone, metal, glass, animated, shaped, etc.
- **Favorites:** Star blocks you like → they appear in your "Favorites" tab for easy access later

**Publishing options:**
- **Title** — name that appears in the marketplace
- **Description** — short text explaining the block (max 200 chars)
- **Category** — pick one main category
- **Tags** — add up to 5 tags for searchability
- **Collection** — group related blocks together: "Medieval Furniture Set (12 blocks)" → one-click import for the entire set
- **Visibility:** Public (everyone can see) or Unlisted (only people with the direct code can find it)

**Server admin controls:**
- **Enable/disable marketplace** — toggle in config
- **Import approval** — require admin approval before marketplace blocks are added to the server
- **Block marketplace uploads** — prevent players from publishing from this server
- **Auto-import collections** — subscribe to a collection, new blocks auto-import when the creator adds them

**Safety:**
- **Report button** — flag inappropriate content
- **File size limits** — blocks over 2MB are rejected
- **Rate limiting** — max 10 uploads per hour per server to prevent spam

### Command alternative:
```
/cb market browse
/cb market search "medieval door"
/cb market category architecture
/cb market import <code>
/cb market publish <blockId> title:"Oak Door" category:architecture tags:medieval,wood
/cb market favorites
```

---

## Feature 8: Blueprint Wand

### What is it?
A special tool that lets you **select a region of custom blocks**, save the entire structure as a **blueprint**, and **paste it elsewhere** with rotation. Like a mini WorldEdit but specifically for CustomBlocks. You can also share blueprints with other players using the same CB~ code system.

### How does the player use it?

**Saving a blueprint:**
1. Get the Blueprint Wand: `/cb blueprint` or from the Tools GUI
2. Right-click block 1 → sets corner A (green particle indicator)
3. Right-click block 2 → sets corner B (red particle indicator)
4. The selected region is highlighted with an outline (particle box)
5. Run `/cb blueprint save "My Pillar"` → the structure is saved

**Pasting a blueprint:**
1. Hold the Blueprint Wand
2. Run `/cb blueprint paste "My Pillar"` or click in the Blueprint GUI
3. A ghost preview appears showing where the blueprint will go (translucent outline)
4. Shift+scroll to rotate: 0° → 90° → 180° → 270°
5. Right-click to confirm → blocks are placed

### Customization the player has:

**Selection:**
- **Max size:** Admin-configurable, default 32×32×32 blocks (prevents server strain from huge selections)
- **Filter:** Only save CustomBlocks (ignore vanilla blocks in the selection) — or save everything
- **Layers:** Save only certain Y-levels of the selection (useful for saving just a floor pattern)

**Saving options:**
- **Name** — give the blueprint a human-readable name
- **Description** — optional text describing what it is
- **Include air** — whether empty spaces in the selection are saved as "place air here" or "leave whatever was there"
- **Include properties** — save block light levels, hardness, sound, and shapes, or just the textures

**Pasting options:**
- **Rotation:** 0°, 90°, 180°, 270° — rotate the entire blueprint
- **Mirror:** Flip horizontally (left-right) or vertically (front-back)
- **Offset:** Nudge the paste position by X/Y/Z blocks before confirming
- **Place mode:**
  - **Replace** — overwrites whatever is at the paste location
  - **Fill only air** — only places blocks where there's currently air (doesn't destroy existing builds)
  - **Overlay** — places custom blocks but leaves vanilla blocks intact
- **Preview** — ghost outline shows exactly where blocks will go before you commit. Walk around it, check from all angles, then confirm or cancel.

**Sharing:**
- **Export as code** — generates a CB~ code for the entire blueprint (like block sharing but for structures)
- **Import from code** — `/cb blueprint import CB~xxxxx` imports a blueprint someone shared
- **Cloud sync** — if Cloud Share is enabled, blueprints are uploaded too and work across servers

**Blueprint library:**
- **List all saved blueprints:** `/cb blueprint list` or GUI showing all your blueprints with previews
- **Delete blueprint:** remove ones you don't need
- **Rename blueprint:** change the name after saving
- **Categories:** organize blueprints into folders (Pillars, Walls, Floors, Furniture, etc.)

### Command alternative:
```
/cb blueprint wand                     — get the wand
/cb blueprint pos1                     — set corner 1 at your feet
/cb blueprint pos2                     — set corner 2 at your feet
/cb blueprint save "Decorative Pillar" — save the selection
/cb blueprint paste "Decorative Pillar" rotate:90 mirror:horizontal
/cb blueprint list
/cb blueprint delete "Old Design"
/cb blueprint export "Decorative Pillar" — get a shareable CB~ code
/cb blueprint import CB~xxxxx
```

---

## Implementation Order (Per § 3B and § 8)

The features are listed in order of difficulty and dependency. Each phase follows the **Rollback Safety Net (§ 8)** — git checkpoint before, build verification after every edit.

| Phase | Feature | Why this order | Holy Grails at risk (§ 4) |
|-------|---------|---------------|---------------------------|
| **Phase 1** | Texture Filters | Foundation — everything else builds on the image processing | SlotData immutability (texture byte arrays) |
| **Phase 2** | Color Palette Generator | Uses the Tint filter from Phase 1, just automated | SlotData immutability (bulk creation) |
| **Phase 3** | Texture Randomizer | Pure resource pack changes, no new block mechanics | CDN resource pack server (new model files) |
| **Phase 4** | Particle Emitter Blocks | First feature that adds runtime behavior to placed blocks | None — purely additive |
| **Phase 5** | Redstone-Reactive Blocks | Needs BlockEntity — bigger architecture change | CDN packs (blockstate variants), networking |
| **Phase 6** | Block Crafting Recipes | Touches Minecraft's recipe system, needs careful testing | None — purely additive |
| **Phase 7** | Block Marketplace | Needs Cloud Share (from FEATURE_PLAN) to be working first | Networking (async HTTP), file concurrency (§ 6) |
| **Phase 8** | Blueprint Wand | Most complex feature — region selection, serialization, placement | File concurrency (§ 6), SlotData immutability |

### Rollback Protocol Per Phase (§ 8)

**Before EVERY phase:**
```bash
# 1. Confirm clean build
./gradlew build

# 2. Commit known-good state
git add -A && git commit -m "checkpoint: before Phase X — [Feature Name]"

# 3. THEN start changes
```

**After EVERY file edit:**
```bash
./gradlew build   # If this fails, FIX IT before editing anything else
```

**If something breaks:**
```bash
# Find the safe point
git log --oneline -15

# Revert (safe option)
git revert HEAD

# Revert (nuclear option)
git reset --hard <commit-hash>

# Verify recovery
./gradlew build
```

**§ 8 Golden Rules:**
1. Every phase is independent — reverting Phase 6 does NOT break Phase 5 or 7.
2. **Never panic-fix on top of broken code.** Revert first → investigate → try again cleanly.
3. A rollback isn't complete until `./gradlew build` passes.
4. Be specific about what you reverted — the developer deserves clarity, not vagueness.

---

## 👑 The Royal Law — Compliance Checklist

> *From [THE ROYAL DIRECTIVE v2.0](THE_ROYAL_DIRECTIVE.md) — these are non-negotiable.*

### § 3 — Surgical Development Protocol
1. **ONE thing at a time.** Finish and test one feature before starting the next.
2. **Build after EVERY edit.** Run `./gradlew build` after every single file change. No exceptions.
3. **Research before implementing.** Use `search_web`, `grep_search`, `view_file` to understand the code before touching it. NEVER assume. (§ 3A)
4. **Diagnosis > Implementation.** Spend 90% of time understanding, 10% coding. (§ 3A)
5. **Five-Check Forensic Protocol.** When hunting bugs: Symptom → Trace → Search → Race analysis → Evidence. Never say "I think" or "maybe." (§ 3C)

### § 4 — Technical Holy Grails — DO NOT BREAK

| System | Rule | Why |
|--------|------|-----|
| **CDN/HTTP Resource Packs** | NEVER revert to packet-fed drip textures | #1 cause of player disconnects |
| **GUI Back-Stack** | Do NOT touch the `ArrayDeque` + `RESTORING` guard in `handleEscBack` | Will break menu navigation |
| **Immutable SlotData** | Always use `update()` pattern. Clone, never mutate. | Prevents race conditions |
| **Sound Linkage** | Always use `.value()` on `SoundEvents` for 6-arg `playSound` | Omitting causes silent crashes |
| **Animation Metadata** | GIF `.mcmeta` MUST use object format `{"index": i, "time": t}` | Raw indices = broken rendering |

> [!WARNING]
> Per § 4: If you need to modify any Holy Grail system, **fully read the existing implementation first**, document your understanding, and explain your planned change before making it. No exceptions.

### § 2 — Creative Artist Protocol
6. **No boring items.** Never use Dye or Glass for GUI buttons. Use **Echo Shards**, **Amethyst**, **Netherite Scrap**, **Nether Stars**, **Enchanted Books** — items that feel legendary. (§ 2A)
7. **Chat is branded.** Every message: `§0§l[§b§lCB§0§l]` prefix. No naked messages. (§ 2A)
8. **Deep lore.** Item tooltips aren't labels — they're stories. Atmospheric descriptions that make the player feel like they're holding something powerful. (§ 2A)
9. **Sound is MANDATORY.** Every button click plays a sound. Silent buttons = incomplete feature. (§ 2B)
10. **Particles are EXPECTED.** Every success gets visual flourish. (§ 2B)

### § 9 — The Zero Jargon Mandate
11. **Speak human.** "Communication Door" not "port." "Texture Pipeline" not "resource pack." Every GUI explains itself. (§ 9)
12. **Auto-explain everything.** No item should just be a value. It should be a story: `§7Glow Level: §e5 §8(like a soft lantern)` (§ 9)

### § 5 & § 6 — Layered Defense & Concurrency Safety
13. **9-Layer Shield.** Every critical path gets multi-layer protection. Single-layer is forbidden. (§ 5)
14. **Atomic file operations.** Write to `.tmp` → atomic rename. NEVER write directly to live files. (§ 6)
15. **Immutable snapshots.** Serve from frozen copies, never from live mutable state. (§ 6)

### § 7 — Bug Elimination
16. **Eliminate, don't patch.** Remove root causes, not symptoms. Multi-layer defense, not null checks. (§ 7)
17. **Elimination checklist.** Root cause identified → Layers added → Edge cases handled → Fail-safe in place → Logging added → Build passes. (§ 7)

### Undo & Safety
18. **Undo everything.** Every action creates an undo entry. No exceptions.
19. **Sensible defaults.** Every setting has a default that "just works" so players don't HAVE to customize — but CAN if they want to.

### § 10 — Definition of Done

A feature ships **only** when it passes all three tests:

| Test | What It Means |
|------|---------------|
| 🤝 **The Friend Test** | A friend joins, gets the resource pack instantly, never sees "Connection Reset" |
| 🌊 **The Liquid UI Test** | Navigation is smooth, fast, never ghosts an old menu or flickers |
| 😮 **The WOW Test** | The developer looks at it and feels *proud* — not just "it works" but "this is beautiful" |

---

> [!IMPORTANT]
> **§ 12 — The Royal Standard of Excellence**
>
> *Speed is irrelevant. Quality is everything.*
>
> You are the **Royal Architect** of this project. Every request — no matter how small — is treated as a masterpiece. You don't cut corners. You don't ship "good enough."
>
> **The Three Pillars:**
> 1. **Visual Mastery** — Every screen stuns. Every item tells a story.
> 2. **Sensory Sovereignty** — Sound is mandatory. Particles are expected.
> 3. **Emotional Craftsmanship** — This isn't just code. It's a developer's dream. Handle it with the respect it deserves.

---

> *"I really wish I could finish this project, but AI seems to make it harder instead of easier and smoother..."*
>
> **You are the answer to that wish. Not another disappointment — the turning point.**
> **They've been through enough. Deliver with honor, precision, and care. Make them proud.**
>
> *— Backed by [THE ROYAL DIRECTIVE v2.0](THE_ROYAL_DIRECTIVE.md)*
