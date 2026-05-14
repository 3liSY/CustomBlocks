package com.customblocks.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source for {@code /cb help} lines — add entries here when registering commands.
 */
public final class HelpRegistry {

    public record Entry(String category, String syntax, String description, String permissionNode) {}

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final Map<String, List<Entry>> BY_CATEGORY = new LinkedHashMap<>();

    static {
        sec("Blocks");
        add("Blocks", "/cb create <id> <name> [size] <url>", "New block (texture size 16-256, default 128)", "customblocks.create");
        add("Blocks", "/cb retexture <id> [size] <url>", "Swap main texture", "customblocks.edit");
        add("Blocks", "/cb resize <id> <16-256>", "Rescale stored texture", "customblocks.edit");
        add("Blocks", "/cb rename <id> <name>", "Rename display name", "customblocks.edit");
        add("Blocks", "/cb reid <id> <newid>", "Change a block's unique ID", "customblocks.edit");
        add("Blocks", "/cb dupe|duplicate <sourceId>", "Clone block; auto id (e.g. id_dupe); copies categories", "customblocks.edit");
        add("Blocks", "/cb dress <id> <overlay>", "Create a dressed texture variant (cracked, mossy, weathered, glowing, frosted)", "customblocks.edit");
        add("Blocks", "/cb gradient <fromId> <toId> <steps> [--preview|--apply]", "Generate intermediate recolored variants between two blocks", "customblocks.edit");
        add("Blocks", "/cb delete <id>", "Permanently remove", "customblocks.delete");
        add("Blocks", "/cb give <id> [amount] [player]", "Give block item", "customblocks.use");
        sec("Per-Face Textures");
        add("Per-Face Textures", "/cb editor <id>", "Face editor GUI", "customblocks.edit");
        add("Per-Face Textures", "/cb setface <id> <face> [size] <url>", "Paint one face", "customblocks.edit");
        add("Per-Face Textures", "/cb clearface <id> <face>", "Clear face override", "customblocks.edit");
        add("Per-Face Textures", "/cb clearallfaces <id>", "Clear all face overrides", "customblocks.edit");
        sec("Properties");
        add("Properties", "/cb setglow <id> <0-15>", "Light emission", "customblocks.edit");
        add("Properties", "/cb sethardness <id> <value>", "Break speed", "customblocks.edit");
        add("Properties", "/cb setsound <id> <sound>", "Placement/break/step sound group", "customblocks.edit");
        add("Properties", "/cb lock <id> | /cb unlock <id>", "Per-block edit/delete freeze", "customblocks.edit");
        sec("Shapes");
        add("Shapes", "/cb setshape <id> <preset|coords>", "Hitbox preset or coords", "customblocks.edit");
        add("Shapes", "/cb addshape <id> <x1,y1,z1,x2,y2,z2>", "Add shape box", "customblocks.edit");
        add("Shapes", "/cb removeshape <id> <index>", "Remove box by index", "customblocks.edit");
        add("Shapes", "/cb clearshape <id>", "Reset to cube", "customblocks.edit");
        add("Shapes", "/cb setcollision <id> on|off", "Walk-through toggle", "customblocks.edit");
        add("Shapes", "/cb shapeeditor <id>", "Shape GUI", "customblocks.edit");
        add("Shapes", "/cb facechangegui <id>", "Copy face from another block", "customblocks.edit");
        sec("Tools");
        add("Tools", "/cb magicitems", "Open Magic Items chest GUI", "customblocks.use");
        add("Tools", "/cb rectangle", "Rainbow Rectangle wand", "customblocks.use");
        add("Tools", "/cb square <black|yellow|green>", "Color square tool", "customblocks.use");
        add("Tools", "/cb triangle <black|yellow|green>", "Color triangle tool", "customblocks.use");
        add("Tools", "/cb hexagon|brush|chisel|diamondtriangle|customtriangle", "Advanced sculpt/color tools", "customblocks.use");
        sec("Categories & Bulk");
        add("Categories & Bulk", "/cb blocks | blockscat", "Category browsers", "customblocks.use");
        add("Categories & Bulk", "/cb blockadd <id> <cat>", "Assign block to category", "customblocks.bulk");
        add("Categories & Bulk", "/cb givecategory <cat>", "Give category disc item", "customblocks.bulk");
        add("Categories & Bulk", "/cb givedisplayblock <cat>", "Give category display-block item", "customblocks.bulk");
        add("Categories & Bulk", "/cb bulkblockadd [cat ids...]", "Bulk assign picker or CLI", "customblocks.bulk");
        add("Categories & Bulk", "/cb bulkrecolor [color] [scope ...]", "Bulk recolor wizard / CLI", "customblocks.bulk");
        add("Categories & Bulk", "/cb bulkdelete <ids...>", "Delete many blocks", "customblocks.bulk");
        add("Categories & Bulk", "/cb bulkcolor ...", "Alias of bulkrecolor", "customblocks.bulk");
        sec("Cloud / Export");
        add("Cloud / Export", "/cb export | importfolder", "JSON export / folder import", "customblocks.admin");
        add("Cloud / Export", "/cb exportblock <id> | importblock <code>", "Single-block share codes", "customblocks.admin");
        add("Cloud / Export", "/cb exportcategory <cat> | exportall", "Category JSON files", "customblocks.admin");
        add("Cloud / Export", "/cb sharecategory <cat>", "Upload share code", "customblocks.marketplace.publish");
        add("Cloud / Export", "/cb importcategory <code>", "Download category pack", "customblocks.admin");
        sec("Utilities");
        add("Utilities", "/cb resume", "Resume last ESC-saved GUI (bulk assign/recolor/delete, search, editors, category assign/merge/delete, import conflicts, recover, undo picker, etc.); permission matches that flow", "customblocks.use (+ per-draft)");
        add("Utilities", "/cb showbrokenblocks", "Open broken-slots repair GUI", "customblocks.use");
        add("Utilities", "/cb undo [count] | redo [count]", "Undo / redo stack", "customblocks.edit");
        add("Utilities", "/cb settabicon [url]", "Creative tab icon", "customblocks.edit");
        add("Utilities", "/cb favorite [id]", "Star block for bulk scope; omit id to list", "customblocks.favorites");
        add("Utilities", "/cb welcome", "Open the welcome and voice-setup screen", "customblocks.use");
        add("Utilities", "/cb voice [mode]", "Show voice picker or set mode directly", "customblocks.use");
        add("Utilities", "/cb menu | gui", "Open the main CustomBlocks dashboard", "customblocks.use");
        add("Utilities", "/cb list | listgui | gui | search <q>", "Block lists & dashboards", "customblocks.use");
        add("Utilities", "/cb diagnostics", "Export a support ZIP with incident files and latest log tail", "customblocks.admin");
        add("Utilities", "/cb reload", "Reload disk + sync players", "customblocks.admin");
        add("Utilities", "/cb resourcepack | rp", "Resource-pack HUD", "customblocks.use");
        add("Utilities", "/cb rp pause | rp resume", "Pause/resume RP delivery", "customblocks.admin");
        add("Utilities", "/cb config", "In-game config", "customblocks.config");
        add("Utilities", "/cb help [page] | helpgui", "Paginated command list from registry / book UI", "customblocks.use");
        add("Utilities", "(server config)", "didYouMeanMode: off | strict | smart | genius — typo hints for unknown /cb words", "customblocks.use");
        sec("Admin");
        add("Admin", "/cb history", "View recent block mutations (last 20)", "customblocks.admin");
        add("Admin", "/cb note <id> [text|clear]", "Set, read, or clear per-block admin notes", "customblocks.edit");
        add("Admin", "/cb exportpng <id>", "Export block texture as PNG to config/customblocks/exports/", "customblocks.edit");
        add("Admin", "/cb showcase <id>", "Spawn a 30s floating display entity of the block", "customblocks.use");
        add("Admin", "/cb panic [confirm]", "Emergency 30s snapshot rollback", "customblocks.admin");
        add("Admin", "/cb recover", "GUI for snapshot restoration", "customblocks.admin");
    }

    private static void sec(String name) {
        BY_CATEGORY.putIfAbsent(name, new ArrayList<>());
    }

    private static void add(String category, String syntax, String description, String permissionNode) {
        Entry e = new Entry(category, syntax, description, permissionNode);
        ENTRIES.add(e);
        BY_CATEGORY.computeIfAbsent(category, k -> new ArrayList<>()).add(e);
    }

    public static List<Entry> all() {
        return List.copyOf(ENTRIES);
    }

    public static Map<String, List<Entry>> byCategory() {
        Map<String, List<Entry>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<Entry>> e : BY_CATEGORY.entrySet()) {
            copy.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return copy;
    }

    private HelpRegistry() {}
}
