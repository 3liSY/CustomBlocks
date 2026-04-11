import os

filepath = 'src/main/java/com/customblocks/gui/GuiManager.java'
with open(filepath, 'r', encoding='utf-8') as f:
    text = f.read()

# 1. Update GuiState
text = text.replace(
    'static GuiState picker(int page)                   { return new GuiState(GuiMode.MAIN,         page, "__picker__", false, 0, false); }',
    'static GuiState picker(int page)                   { return new GuiState(GuiMode.MAIN,         page, "__picker__", false, 0, false); }\n        static GuiState pickerTabIcon(int page)            { return new GuiState(GuiMode.MAIN,         page, "__picker_tabicon__", false, 0, false); }\n        static GuiState pickerBroken(int page)             { return new GuiState(GuiMode.MAIN,         page, "__picker_broken__", false, 0, false); }'
)

# 2. Add openTabIconMenu and openBrokenBlocks
open_methods = """
    public static void openTabIconMenu(ServerPlayerEntity player) {
        SimpleInventory inv = new SimpleInventory(27);
        for(int i=0;i<27;i++) inv.setStack(i, glass());
        inv.setStack(11, uiGlint(Items.PAINTING, "§eType in Chat", "§7Provide a URL or Custom Block ID"));
        inv.setStack(15, uiGlint(Items.CHEST, "§bPick from Menu", "§7Select an existing block visually"));
        STATES.put(player.getUuid(), GuiState.main(0));
        PENDING.put(player.getUuid(), new PendingInput(InputAction.SETTABICON_URL, "__tab_icon_menu__", null, null, null, 0));
        openScreen(player, new SimpleNamedScreenHandlerFactory((s, pi, p) -> new CbScreenHandler(s, pi, inv), Text.literal("§e§l🎨 Choose Tab Icon")));
    }

    public static void openTabIconPicker(ServerPlayerEntity player, int page) {
        int total = sortedBlocks().size();
        int max   = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        STATES.put(player.getUuid(), GuiState.pickerTabIcon(page));
        final int fp = page;
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildPicker(fp, false)),
            Text.literal("§b§l▶ §r§fChoose Tab Icon §7(ESC = back)")));
    }

    public static void openBrokenBlocks(ServerPlayerEntity player) {
        openBrokenBlocks(player, 0);
    }
    public static void openBrokenBlocks(ServerPlayerEntity player, int page) {
        int total = brokenBlocks().size();
        int max   = total == 0 ? 0 : Math.max(0, (total - 1) / BLOCKS_PER_PAGE);
        page = Math.max(0, Math.min(page, max));
        STATES.put(player.getUuid(), GuiState.pickerBroken(page));
        final int fp = page;
        openScreen(player, new SimpleNamedScreenHandlerFactory(
            (s, pi, p) -> new CbScreenHandler(s, pi, buildPicker(fp, true)),
            Text.literal("§c§l▶ §r§fBroken Blocks §7(ESC = back)")));
    }

    public static List<SlotManager.SlotData> brokenBlocks() {
        List<SlotManager.SlotData> list = new ArrayList<>();
        for (SlotManager.SlotData d : sortedBlocks()) {
            if (d.texture != null && ImageProcessor.isBrokenTexture(d.texture)) {
                list.add(d);
            }
        }
        return list;
    }
"""
text = text.replace('public static void openAdminGui(ServerPlayerEntity player)', open_methods + '\n    public static void openAdminGui(ServerPlayerEntity player)')

# 3. Update buildPicker to support broken blocks
text = text.replace('private static SimpleInventory buildPicker(int page) {', 'private static SimpleInventory buildPicker(int page, boolean brokenOnly) {')
text = text.replace('List<SlotManager.SlotData> blocks = sortedBlocks();', 'List<SlotManager.SlotData> blocks = brokenOnly ? brokenBlocks() : sortedBlocks();')
text = text.replace('private static SimpleInventory buildPicker(int page)', 'private static SimpleInventory buildPicker(int page, boolean brokenOnly)')

text = text.replace('buildPicker(fp)', 'buildPicker(fp, false)') # Note: openEditorPicker uses this
text = text.replace('buildPicker(0)', 'buildPicker(0, false)') # For safety

# 4. Update SETTABICON_URL in onChatInput
old_chat = """            case SETTABICON_URL -> {
                if (!isUrl(text)) { send(player, "§cNeeds a URL."); openMain(player, rp); return true; }
                send(player, "§e[GUI] Downloading tab icon…");
                MinecraftServer srv = player.getServer();
                thread(player, () -> { try {
                    byte[] bytes = ImageProcessor.downloadAndProcess(text);
                    srv.execute(() -> {
                        SlotManager.setTabIconTexture(bytes);
                        if (SlotManager.hasId("tab_icon")) {
                            SlotManager.SlotData ex = SlotManager.getById("tab_icon");
                            SlotManager.updateTexture("tab_icon", bytes); SlotManager.saveAll();
                            CustomBlocksMod.broadcastUpdate(srv, new SlotUpdatePayload("retexture", ex.index, "tab_icon", null, bytes, ex.lightLevel, ex.hardness, ex.soundType));
                        } else if (SlotManager.freeSlots() > 0) {
                            SlotManager.SlotData iconSlot = SlotManager.assign("tab_icon", "Tab Icon", bytes);
                            if (iconSlot != null) {
                                SlotManager.saveAll();
                                CustomBlocksMod.broadcastUpdate(srv, new SlotUpdatePayload("add", iconSlot.index, "tab_icon", "Tab Icon", bytes, iconSlot.lightLevel, iconSlot.hardness, iconSlot.soundType));
                            }
                        } else { send(player, "§e[GUI] §7Warning: all slots full — icon stored but not as a slot."); }
                        CustomBlocksMod.broadcastUpdate(srv, new SlotUpdatePayload("tabicon", -1, null, null, bytes, 0, 0, "stone"));
                        send(player, "§a[GUI] Tab icon updated! §7(Resource pack reloading…)");
                        openMain(player, rp);
                    });
                } catch (Exception e) { srv.execute(() -> { send(player, "§c[GUI] Failed: " + e.getMessage()); openMain(player, rp); }); } });
                return true;
            }"""

new_chat = """            case SETTABICON_URL -> {
                if ("cancel".equalsIgnoreCase(text)) { openMain(player, rp); return true; }
                String targetId = text.toLowerCase().trim();
                boolean isBlock = SlotManager.hasId(targetId);
                if (!isUrl(text) && !isBlock) { send(player, "§cNeeds a URL or Block ID."); openMain(player, rp); return true; }
                
                send(player, "§e[GUI] Processing tab icon…");
                MinecraftServer srv = player.getServer();
                thread(player, () -> { try {
                    byte[] finalBytes = null;
                    if (isBlock) {
                        SlotManager.SlotData dd = SlotManager.getById(targetId);
                        if (dd.texture != null) finalBytes = dd.texture.clone();
                        else throw new Exception("Block has no texture");
                    } else {
                        finalBytes = ImageProcessor.downloadAndProcess(text);
                    }
                    final byte[] bytes = finalBytes;
                    srv.execute(() -> {
                        SlotManager.setTabIconTexture(bytes);
                        if (SlotManager.hasId("tab_icon")) {
                            SlotManager.SlotData ex = SlotManager.getById("tab_icon");
                            SlotManager.updateTexture("tab_icon", bytes); SlotManager.saveAll();
                            CustomBlocksMod.broadcastUpdate(srv, new SlotUpdatePayload("retexture", ex.index, "tab_icon", null, bytes, ex.lightLevel, ex.hardness, ex.soundType));
                        } else if (SlotManager.freeSlots() > 0) {
                            SlotManager.SlotData iconSlot = SlotManager.assign("tab_icon", "Tab Icon", bytes);
                            if (iconSlot != null) {
                                SlotManager.saveAll();
                                CustomBlocksMod.broadcastUpdate(srv, new SlotUpdatePayload("add", iconSlot.index, "tab_icon", "Tab Icon", bytes, iconSlot.lightLevel, iconSlot.hardness, iconSlot.soundType));
                            }
                        } else { send(player, "§e[GUI] §7Warning: all slots full — icon stored but not as a slot."); }
                        CustomBlocksMod.broadcastUpdate(srv, new SlotUpdatePayload("tabicon", -1, null, null, bytes, 0, 0, "stone"));
                        send(player, "§a[GUI] Tab icon updated! §7(Resource pack reloading…)");
                        openMain(player, rp);
                    });
                } catch (Exception e) { srv.execute(() -> { send(player, "§c[GUI] Failed: " + e.getMessage()); openMain(player, rp); }); } });
                return true;
            }"""

text = text.replace(old_chat, new_chat)

# 5. handleEscBack for __picker_tabicon__ and __picker_broken__
old_esc = """                if ("__picker__".equals(state.editingId())) {
                    openMain(player, 0);"""
new_esc = """                if ("__picker__".equals(state.editingId()) || "__picker_broken__".equals(state.editingId())) {
                    openMain(player, 0);
                } else if ("__picker_tabicon__".equals(state.editingId())) {
                    openTabIconMenu(player);"""
text = text.replace(old_esc, new_esc)

# 6. Update buildMain UI and handleMainClick
old_build_main = """        // ── Row 1: Core block actions ─────────────────────────────────────────
        inv.setStack(0, uiGlint(Items.LIME_CONCRETE,  "§a§l➕ Create New Block",
            "§7Click → type ID, name, URL in chat",
            "§8Creates a brand-new custom block"));
        inv.setStack(1, uiGlint(Items.CHEST,          "§b§l📂 Browse & Edit Blocks",
            "§7Browse all blocks and open the full editor",
            "§8Blocks: §f"+SlotManager.usedSlots()+" §8/ §f"+SlotManager.MAX_SLOTS));
        // Undo — GLOWING GOLD when available, stands out
        inv.setStack(2, undoSz > 0
            ? uiGlint(Items.GOLDEN_PICKAXE, "§6§l↩ UNDO  §e(" + undoSz + " left)",
                "§eUndo: §f\"" + SlotManager.peekUndoDescription() + "\"",
                "§8Click to undo this action",
                undoSz > 1 ? "§8After: §7\"" + peekSecondUndo() + "\"" : "§8(last undo)")
            : ui(Items.GRAY_STAINED_GLASS_PANE, "§8↩ Undo §7(nothing to undo)", "§7Make a change first"));
        // Redo — GLOWING BLUE when available
        inv.setStack(3, redoSz > 0
            ? uiGlint(Items.DIAMOND_PICKAXE, "§b§l↪ REDO  §3(" + redoSz + " left)",
                "§3Redo: §f\"" + SlotManager.peekRedoDescription() + "\"",
                "§8Click to re-apply this action")
            : ui(Items.GRAY_STAINED_GLASS_PANE, "§8↪ Redo §7(nothing to redo)", "§7Undo something first"));
        inv.setStack(4, uiGlint(Items.PAINTING,       "§e§l🎨 Set Tab Icon",
            "§7Click → paste image URL in chat",
            "§8Changes the icon shown on the CustomBlocks tab"));
        inv.setStack(5, uiGlint(Items.PAPER,          "§f§l📤 Export Blocks",
            "§7Export block list to JSON", "§8Runs: /cb export"));
        inv.setStack(6, uiGlint(Items.HOPPER,         "§f§l📥 Import Folder",
            "§7Bulk-import from config/customblocks/import/", "§8Runs: /cb importfolder"));
        inv.setStack(7, uiGlint(Items.NETHER_STAR,    "§f§l🔄 Reload All Data",
            "§7Reload & sync to all players", "§8Runs: /cb reload"));
        inv.setStack(8, ui(Items.TNT,                 "§c§l❌ Delete a Block",
            "§7Click → type block ID in chat", "§8Use Undo to restore"));

        // ── Separator ─────────────────────────────────────────────────────────
        inv.setStack(9, ui(Items.BLUE_STAINED_GLASS_PANE,  "§b§l── Tools & Quick Actions ──", "§7Block tools and shortcut commands below"));

        // ── Row 2: Tools ──────────────────────────────────────────────────────
        inv.setStack(10, uiGlint(Items.BLAZE_ROD,     "§6Rainbow Rectangle",
            "§7Face-paint wand — right-click face → URL", "§8Click to receive the tool"));
        inv.setStack(11, uiGlint(Items.WHITE_CONCRETE,"§fColor Square",
            "§7Flat-color region painter", "§8Click → choose: black | yellow | green"));
        inv.setStack(12, uiGlint(Items.WHITE_CARPET,  "§fColor Triangle",
            "§7Triangle region painter", "§8Click → choose: black | yellow | green"));
        inv.setStack(13, uiGlint(Items.COMMAND_BLOCK, "§4§l⚙ Admin Control Panel",
            "§7Full server-wide GUI control", "§8Opens /cb admingui"));
        inv.setStack(14, uiGlint(Items.BOOK,          "§b/cb list",
            "§7Shows all custom block IDs in chat"));
        inv.setStack(15, uiGlint(Items.WRITABLE_BOOK, "§b/cb help",
            "§7Shows all /cb commands and usage"));
        inv.setStack(16, ui(Items.CYAN_STAINED_GLASS_PANE, "§r"));
        // Stats on right
        inv.setStack(17, ui(Items.EMERALD, "§a§lStats",
            "§7Blocks used: §f"+SlotManager.usedSlots()+" §7/ §f"+SlotManager.MAX_SLOTS,
            "§7Free slots:  §f"+SlotManager.freeSlots(),
            "§7Undo depth:  §e"+undoSz+" §7/ Redo: §b"+redoSz,
            "§8Press §fESC §8to close this menu"));

        // ── Undo/Redo prominent banner ─────────────────────────────────────────
        // Make undo & redo stand out with a dedicated banner row
        for (int i = 18; i <= 26; i++) {"""

new_build_main = """        // ── Row 1 & 2: Core block actions spaced out ──────────────────────────
        inv.setStack(0, uiGlint(Items.LIME_CONCRETE,  "§a§l➕ Create New Block",
            "§7Click → type ID, name, URL in chat",
            "§8Creates a brand-new custom block"));
        inv.setStack(1, glass());
        inv.setStack(2, uiGlint(Items.CHEST,          "§b§l📂 Browse & Edit Blocks",
            "§7Browse all blocks and open the full editor"));
        inv.setStack(3, glass());
        inv.setStack(4, undoSz > 0
            ? uiGlint(Items.GOLDEN_PICKAXE, "§6§l↩ UNDO  §e(" + undoSz + " left)",
                "§eUndo: §f\\"" + SlotManager.peekUndoDescription() + "\\"",
                "§8Click to undo this action")
            : ui(Items.GRAY_STAINED_GLASS_PANE, "§8↩ Undo §7(nothing to undo)", "§7Make a change first"));
        inv.setStack(5, glass());
        inv.setStack(6, redoSz > 0
            ? uiGlint(Items.DIAMOND_PICKAXE, "§b§l↪ REDO  §3(" + redoSz + " left)",
                "§3Redo: §f\\"" + SlotManager.peekRedoDescription() + "\\"",
                "§8Click to re-apply this action")
            : ui(Items.GRAY_STAINED_GLASS_PANE, "§8↪ Redo §7(nothing to redo)", "§7Undo something first"));
        inv.setStack(7, glass());
        inv.setStack(8, ui(Items.TNT,                 "§c§l❌ Delete a Block",
            "§7Click → type block ID in chat", "§8Use Undo to restore"));

        inv.setStack(9, uiGlint(Items.PAINTING,       "§e§l🎨 Set Tab Icon",
            "§7Click → open Tab Icon Menu"));
        inv.setStack(10, glass());
        inv.setStack(11, uiGlint(Items.PAPER,          "§f§l📤 Export Blocks",
            "§7Export block list to JSON", "§8Runs: /cb export"));
        inv.setStack(12, glass());
        inv.setStack(13, uiGlint(Items.HOPPER,         "§f§l📥 Import Folder",
            "§7Bulk-import from config/customblocks/import/", "§8Runs: /cb importfolder"));
        inv.setStack(14, glass());
        inv.setStack(15, uiGlint(Items.NETHER_STAR,    "§f§l🔄 Reload All Data",
            "§7Reload & sync to all players", "§8Runs: /cb reload"));
        inv.setStack(16, glass());
        inv.setStack(17, uiGlint(Items.ANVIL,          "§6§l🔍 View Broken Blocks", "§7Search for broken/missing textures"));

        // ── Separator ─────────────────────────────────────────────────────────
        inv.setStack(18, ui(Items.BLUE_STAINED_GLASS_PANE,  "§b§l── Tools & Quick Actions ──", "§7Block tools and shortcut commands below"));

        // ── Row 4: Tools ──────────────────────────────────────────────────────
        inv.setStack(19, uiGlint(Items.BLAZE_ROD,     "§6Rainbow Rectangle",
            "§7Face-paint wand — right-click face → URL"));
        inv.setStack(20, uiGlint(Items.WHITE_CONCRETE,"§fColor Square",
            "§7Flat-color region painter"));
        inv.setStack(21, uiGlint(Items.WHITE_CARPET,  "§fColor Triangle",
            "§7Triangle region painter"));
        inv.setStack(22, uiGlint(Items.COMMAND_BLOCK, "§4§l⚙ Admin Control Panel",
            "§7Full server-wide GUI control"));
        inv.setStack(23, uiGlint(Items.BOOK,          "§b/cb list",
            "§7Shows all custom block IDs in chat"));
        inv.setStack(24, uiGlint(Items.WRITABLE_BOOK, "§b/cb help",
            "§7Shows all /cb commands and usage"));
        inv.setStack(25, ui(Items.CYAN_STAINED_GLASS_PANE, "§r"));
        // Stats on right
        inv.setStack(26, ui(Items.EMERALD, "§a§lStats",
            "§7Blocks used: §f"+SlotManager.usedSlots()+" §7/ §f"+SlotManager.MAX_SLOTS,
            "§7Free slots:  §f"+SlotManager.freeSlots(),
            "§7Undo depth:  §e"+undoSz+" §7/ Redo: §b"+redoSz,
            "§8Press §fESC §8to close this menu"));

        // ── Undo/Redo prominent banner ─────────────────────────────────────────
        for (int i = 27; i <= 35; i++) {"""
text = text.replace(old_build_main, new_build_main)


old_handle_main = """    private static void handleMainClick(ServerPlayerEntity player, GuiState state, int slot) {
        int page = state.page();
        boolean isPicker = "__picker__".equals(state.editingId());

        if (isPicker) {
            if (slot == 0)  { openMain(player, 0); return; }
            if (slot == 45) { openEditorPicker(player, Math.max(0, page-1)); return; }
            if (slot == 53) { openEditorPicker(player, page+1); return; }
            if (slot >= 18 && slot <= 35) {
                List<SlotManager.SlotData> blocks = sortedBlocks();
                int idx = page * BLOCKS_PER_PAGE + (slot - 18);
                if (idx < blocks.size()) openEditor(player, blocks.get(idx).customId, page);
            }
            return;
        }

        switch (slot) {
            // Row 1 — primary actions
            case 0 -> { PENDING.put(player.getUuid(), new PendingInput(InputAction.CREATE_ID, null, null, null, null, page)); player.closeHandledScreen(); send(player, "§6[GUI] §eType a block §fID §e(a-z 0-9 _ only) or §ccancel§e:"); }
            case 1 -> openEditorPicker(player, 0);
            // Undo — slot 2 (glowing)
            case 2 -> {
                if (SlotManager.undoStackSize() == 0) { send(player, "§7Nothing to undo."); refreshScreen(player, buildMain()); return; }
                SlotManager.UndoEntry entry = SlotManager.popUndo();
                if (entry == null) { refreshScreen(player, buildMain()); return; }
                applyUndoEntry(player, entry);
                // Show what the next undo would be
                if (SlotManager.undoStackSize() > 0) {
                    send(player, "§8  → Next undo: §7\\"" + SlotManager.peekUndoDescription() + "\\" §8(" + SlotManager.undoStackSize() + " left)");
                }
                refreshScreen(player, buildMain());
            }
            // Redo — slot 3 (new!)
            case 3 -> {
                if (SlotManager.redoStackSize() == 0) { send(player, "§7Nothing to redo."); refreshScreen(player, buildMain()); return; }
                SlotManager.UndoEntry entry = SlotManager.popRedo();
                if (entry == null) { refreshScreen(player, buildMain()); return; }
                applyRedoEntry(player, entry);
                if (SlotManager.redoStackSize() > 0) {
                    send(player, "§8  → Next redo: §7\\"" + SlotManager.peekRedoDescription() + "\\" §8(" + SlotManager.redoStackSize() + " left)");
                }
                refreshScreen(player, buildMain());
            }
            case 4 -> { PENDING.put(player.getUuid(), new PendingInput(InputAction.SETTABICON_URL, null, null, null, null, page)); player.closeHandledScreen(); send(player, "§6[GUI] §ePaste image URL for the §fcreative tab icon §e(or §ccancel§e):"); }
            case 5 -> { player.closeHandledScreen(); player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb export"); }
            case 6 -> { player.closeHandledScreen(); player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb importfolder"); }
            case 7 -> { player.closeHandledScreen(); player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb reload"); }
            case 8 -> { PENDING.put(player.getUuid(), new PendingInput(InputAction.CREATE_ID, "__delete__", null, null, null, page)); player.closeHandledScreen(); send(player, "§6[GUI] §eType the §fID §eof the block to delete (or §ccancel§e):"); }
            // Row 2 — tools
            case 10 -> { player.getInventory().insertStack(new ItemStack(net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, "rainbow_rectangle")), 1)); send(player, "§6[GUI] Given §6Rainbow Rectangle§e!"); refreshScreen(player, buildMain()); }
            case 11 -> { PENDING.put(player.getUuid(), new PendingInput(InputAction.REID_TEXT, "__givesquare__", null, null, null, page)); player.closeHandledScreen(); send(player, "§6[GUI] §eType color: §fblack §7| §fyellow §7| §fgreen§e:"); }
            case 12 -> { PENDING.put(player.getUuid(), new PendingInput(InputAction.REID_TEXT, "__givetriangle__", null, null, null, page)); player.closeHandledScreen(); send(player, "§6[GUI] §eType color: §fblack §7| §fyellow §7| §fgreen§e:"); }
            case 13 -> openAdminGui(player);
            case 14 -> { player.closeHandledScreen(); player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb list"); }
            case 15 -> { player.closeHandledScreen(); player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb help"); }
            case 16 -> { player.closeHandledScreen(); player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb admingui"); }
        }"""

new_handle_main = """    private static void handleMainClick(ServerPlayerEntity player, GuiState state, int slot) {
        int page = state.page();
        boolean isPicker = state.editingId() != null && state.editingId().startsWith("__picker");
        
        if ("__tab_icon_menu__".equals(state.editingId())) {
            PENDING.remove(player.getUuid());
            if (slot == 11) { PENDING.put(player.getUuid(), new PendingInput(InputAction.SETTABICON_URL, null, null, null, null, page)); player.closeHandledScreen(); send(player, "§6[GUI] §ePaste URL or Block ID for the tab icon (or §ccancel§e):"); }
            if (slot == 15) { openTabIconPicker(player, 0); }
            return;
        }

        if (isPicker) {
            String mode = state.editingId();
            if (slot == 0) { 
                if (mode.equals("__picker_tabicon__")) openTabIconMenu(player);
                else openMain(player, 0); 
                return; 
            }
            if (slot == 45) { 
                if (mode.equals("__picker_tabicon__")) openTabIconPicker(player, Math.max(0, page-1));
                else if (mode.equals("__picker_broken__")) openBrokenBlocks(player, Math.max(0, page-1));
                else openEditorPicker(player, Math.max(0, page-1)); 
                return; 
            }
            if (slot == 53) { 
                if (mode.equals("__picker_tabicon__")) openTabIconPicker(player, page+1);
                else if (mode.equals("__picker_broken__")) openBrokenBlocks(player, page+1);
                else openEditorPicker(player, page+1); 
                return; 
            }
            if (slot >= 18 && slot <= 35) {
                List<SlotManager.SlotData> blocks = mode.equals("__picker_broken__") ? brokenBlocks() : sortedBlocks();
                int idx = page * BLOCKS_PER_PAGE + (slot - 18);
                if (idx < blocks.size()) {
                    if (mode.equals("__picker_tabicon__")) {
                        SlotManager.SlotData dd = blocks.get(idx);
                        if (dd.texture != null) {
                            SlotManager.setTabIconTexture(dd.texture.clone());
                            SlotManager.saveAll();
                            CustomBlocksMod.broadcastUpdate(player.getServer(), new SlotUpdatePayload("tabicon", -1, null, null, dd.texture.clone(), 0, 0, "stone"));
                            send(player, "§a[GUI] Tab icon updated to " + dd.displayName);
                            player.closeHandledScreen();
                        } else {
                            send(player, "§cBlock has no texture.");
                        }
                    } else {
                        openEditor(player, blocks.get(idx).customId, page);
                    }
                }
            }
            return;
        }

        switch (slot) {
            // Row 1 — primary actions
            case 0 -> { PENDING.put(player.getUuid(), new PendingInput(InputAction.CREATE_ID, null, null, null, null, page)); player.closeHandledScreen(); send(player, "§6[GUI] §eType a block §fID §e(a-z 0-9 _ only) or §ccancel§e:"); }
            case 2 -> openEditorPicker(player, 0);
            case 4 -> {
                if (SlotManager.undoStackSize() == 0) { send(player, "§7Nothing to undo."); refreshScreen(player, buildMain()); return; }
                SlotManager.UndoEntry entry = SlotManager.popUndo();
                if (entry == null) { refreshScreen(player, buildMain()); return; }
                applyUndoEntry(player, entry);
                if (SlotManager.undoStackSize() > 0) {
                    send(player, "§8  → Next undo: §7\\"" + SlotManager.peekUndoDescription() + "\\" §8(" + SlotManager.undoStackSize() + " left)");
                }
                refreshScreen(player, buildMain());
            }
            case 6 -> {
                if (SlotManager.redoStackSize() == 0) { send(player, "§7Nothing to redo."); refreshScreen(player, buildMain()); return; }
                SlotManager.UndoEntry entry = SlotManager.popRedo();
                if (entry == null) { refreshScreen(player, buildMain()); return; }
                applyRedoEntry(player, entry);
                if (SlotManager.redoStackSize() > 0) {
                    send(player, "§8  → Next redo: §7\\"" + SlotManager.peekRedoDescription() + "\\" §8(" + SlotManager.redoStackSize() + " left)");
                }
                refreshScreen(player, buildMain());
            }
            case 8 -> { PENDING.put(player.getUuid(), new PendingInput(InputAction.CREATE_ID, "__delete__", null, null, null, page)); player.closeHandledScreen(); send(player, "§6[GUI] §eType the §fID §eof the block to delete (or §ccancel§e):"); }
            
            // Row 2 actions
            case 9 -> openTabIconMenu(player);
            case 11 -> { player.closeHandledScreen(); player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb export"); }
            case 13 -> { player.closeHandledScreen(); player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb importfolder"); }
            case 15 -> { player.closeHandledScreen(); player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb reload"); }
            case 17 -> openBrokenBlocks(player);

            // Row 4 — tools (shifted by +9)
            case 19 -> { player.getInventory().insertStack(new ItemStack(net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, "rainbow_rectangle")), 1)); send(player, "§6[GUI] Given §6Rainbow Rectangle§e!"); refreshScreen(player, buildMain()); }
            case 20 -> { PENDING.put(player.getUuid(), new PendingInput(InputAction.REID_TEXT, "__givesquare__", null, null, null, page)); player.closeHandledScreen(); send(player, "§6[GUI] §eType color: §fblack §7| §fyellow §7| §fgreen§e:"); }
            case 21 -> { PENDING.put(player.getUuid(), new PendingInput(InputAction.REID_TEXT, "__givetriangle__", null, null, null, page)); player.closeHandledScreen(); send(player, "§6[GUI] §eType color: §fblack §7| §fyellow §7| §fgreen§e:"); }
            case 22 -> openAdminGui(player);
            case 23 -> { player.closeHandledScreen(); player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb list"); }
            case 24 -> { player.closeHandledScreen(); player.getServer().getCommandManager().executeWithPrefix(player.getCommandSource(), "cb help"); }
        }"""

text = text.replace(old_handle_main, new_handle_main)
with open(filepath, 'w', encoding='utf-8') as f:
    f.write(text)
