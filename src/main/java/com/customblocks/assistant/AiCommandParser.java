package com.customblocks.assistant;

import com.customblocks.core.SlotData;
import com.customblocks.core.SlotManager;
import com.customblocks.core.UndoManager;
import com.customblocks.gui.ChatHelper;
import com.customblocks.network.NetworkManager;
import com.customblocks.network.SlotUpdatePayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V4-41 — Rule-based natural language command parser for the AI assistant.
 * Converts plain-English commands into mod operations.
 */
public final class AiCommandParser {

    private AiCommandParser() {}

    // ── Patterns ─────────────────────────────────────────────────────────────

    private static final Pattern DELETE_PREFIX  = Pattern.compile(
        "(?:delete|remove)\\s+all\\s+blocks?\\s+(?:starting|that start)\\s+with\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_CONTAIN = Pattern.compile(
        "(?:delete|remove)\\s+all\\s+blocks?\\s+(?:with|containing|that contain)\\s+(\\S+)\\s+in\\s+(?:name|id)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_NAME    = Pattern.compile(
        "(?:delete|remove)\\s+all\\s+blocks?\\s+(?:named|called|with name)\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_BROKEN  = Pattern.compile(
        "(?:delete|remove)\\s+all\\s+broken\\s+blocks?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_BLOCK   = Pattern.compile(
        "(?:delete|remove)\\s+block\\s+(\\S+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern FIND_CONTAIN   = Pattern.compile(
        "(?:find|search|list|show)\\s+blocks?\\s+(?:with|containing|that contain|that include)\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIND_PREFIX    = Pattern.compile(
        "(?:find|search|list|show)\\s+blocks?\\s+(?:starting|that start)\\s+with\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIST_ALL       = Pattern.compile(
        "(?:list|show)\\s+(?:all\\s+)?blocks?", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIST_BROKEN    = Pattern.compile(
        "(?:list|show|find)\\s+(?:all\\s+)?broken\\s+blocks?", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT_BLOCKS   = Pattern.compile(
        "how\\s+many\\s+blocks?|count\\s+(?:all\\s+)?blocks?", Pattern.CASE_INSENSITIVE);

    private static final Pattern SET_GLOW_BLOCK = Pattern.compile(
        "set\\s+glow(?:\\s+level)?\\s+(\\d+)\\s+on\\s+(?:block\\s+)?(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SET_GLOW_ALL   = Pattern.compile(
        "set\\s+glow(?:\\s+level)?\\s+(\\d+)\\s+on\\s+all\\s+blocks?\\s+(?:with|containing)\\s+(\\S+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern RENAME_BLOCK   = Pattern.compile(
        "rename\\s+(?:block\\s+)?(\\S+)\\s+to\\s+(\\S+)", Pattern.CASE_INSENSITIVE);

    // ── Result types ─────────────────────────────────────────────────────────

    public enum ActionType {
        DELETE_MATCHING, DELETE_BROKEN, LIST_MATCHING, LIST_ALL_BLOCKS,
        COUNT_ALL, SET_GLOW, RENAME
    }

    public record ParseResult(ActionType type, String param1, String param2, String rawText) {
        static ParseResult of(ActionType t, String p1, String p2, String raw) {
            return new ParseResult(t, p1, p2, raw);
        }
    }

    // ── Parse ─────────────────────────────────────────────────────────────────

    public static ParseResult parse(String text) {
        String t = text.trim();

        Matcher m;

        // Count
        m = COUNT_BLOCKS.matcher(t);
        if (m.find()) return ParseResult.of(ActionType.COUNT_ALL, null, null, t);

        // List broken
        m = LIST_BROKEN.matcher(t);
        if (m.find()) return ParseResult.of(ActionType.LIST_MATCHING, "__broken__", null, t);

        // Delete broken
        m = DELETE_BROKEN.matcher(t);
        if (m.find()) return ParseResult.of(ActionType.DELETE_BROKEN, null, null, t);

        // Delete prefix
        m = DELETE_PREFIX.matcher(t);
        if (m.find()) return ParseResult.of(ActionType.DELETE_MATCHING, "prefix:" + m.group(1).toLowerCase(Locale.ROOT), null, t);

        // Delete containing
        m = DELETE_CONTAIN.matcher(t);
        if (m.find()) return ParseResult.of(ActionType.DELETE_MATCHING, "contains:" + m.group(1).toLowerCase(Locale.ROOT), null, t);

        // Delete named
        m = DELETE_NAME.matcher(t);
        if (m.find()) return ParseResult.of(ActionType.DELETE_MATCHING, "id:" + m.group(1).toLowerCase(Locale.ROOT), null, t);

        // Delete single block
        m = DELETE_BLOCK.matcher(t);
        if (m.find()) return ParseResult.of(ActionType.DELETE_MATCHING, "id:" + m.group(1).toLowerCase(Locale.ROOT), null, t);

        // Find prefix
        m = FIND_PREFIX.matcher(t);
        if (m.find()) return ParseResult.of(ActionType.LIST_MATCHING, "prefix:" + m.group(1).toLowerCase(Locale.ROOT), null, t);

        // Find containing
        m = FIND_CONTAIN.matcher(t);
        if (m.find()) return ParseResult.of(ActionType.LIST_MATCHING, "contains:" + m.group(1).toLowerCase(Locale.ROOT), null, t);

        // List all
        m = LIST_ALL.matcher(t);
        if (m.find()) return ParseResult.of(ActionType.LIST_ALL_BLOCKS, null, null, t);

        // Set glow on all matching
        m = SET_GLOW_ALL.matcher(t);
        if (m.find()) {
            try { Integer.parseInt(m.group(1)); }
            catch (NumberFormatException e) { return null; }
            return ParseResult.of(ActionType.SET_GLOW, "contains:" + m.group(2).toLowerCase(Locale.ROOT), m.group(1), t);
        }

        // Set glow on specific block
        m = SET_GLOW_BLOCK.matcher(t);
        if (m.find()) {
            try { Integer.parseInt(m.group(1)); }
            catch (NumberFormatException e) { return null; }
            return ParseResult.of(ActionType.SET_GLOW, "id:" + m.group(2).toLowerCase(Locale.ROOT), m.group(1), t);
        }

        // Rename
        m = RENAME_BLOCK.matcher(t);
        if (m.find()) return ParseResult.of(ActionType.RENAME, m.group(1).toLowerCase(Locale.ROOT), m.group(2), t);

        return null;
    }

    // ── Execute ───────────────────────────────────────────────────────────────

    public static void execute(ParseResult result, ServerCommandSource src, UUID playerUuid) {
        MinecraftServer server = src.getServer();

        switch (result.type()) {

            case COUNT_ALL -> {
                int count = SlotManager.allSlots().size();
                ChatHelper.info(src, "§7AI: You have §f" + count + " §7custom block(s) defined.");
            }

            case LIST_ALL_BLOCKS -> {
                List<SlotData> all = SlotManager.allSlots().stream()
                    .sorted((a, b) -> a.customId.compareToIgnoreCase(b.customId))
                    .toList();
                if (all.isEmpty()) { ChatHelper.info(src, "§7AI: No blocks defined yet."); return; }
                ChatHelper.info(src, "§7AI: §f" + all.size() + " §7block(s): " + summarize(all));
            }

            case LIST_MATCHING -> {
                List<SlotData> matches = matchingBlocks(result.param1());
                if (matches.isEmpty()) {
                    ChatHelper.info(src, "§7AI: No blocks match '" + result.param1() + "'.");
                } else {
                    ChatHelper.info(src, "§7AI: §f" + matches.size() + " §7match(es): " + summarize(matches));
                }
            }

            case DELETE_BROKEN -> {
                List<SlotData> broken = new ArrayList<>(SlotManager.brokenBlocksWithReasons().keySet());
                if (broken.isEmpty()) { ChatHelper.info(src, "§7AI: No broken blocks found."); return; }
                for (SlotData d : broken) {
                    UndoManager.pushUndoDeletion(d.customId, d.deepCopy(), playerUuid);
                    SlotManager.remove(d.customId);
                    NetworkManager.broadcastUpdate(server, new SlotUpdatePayload("remove", d.index, d.customId, null, null, 0, 0, "stone", null, null, null));
                }
                SlotManager.saveAll();
                ChatHelper.success(src, "§7AI: Deleted §f" + broken.size() + " §abroken block(s). Use §f/cb undo §ato restore.");
            }

            case DELETE_MATCHING -> {
                List<SlotData> matches = matchingBlocks(result.param1());
                if (matches.isEmpty()) {
                    ChatHelper.info(src, "§7AI: No blocks match '" + result.param1() + "'. Nothing deleted.");
                    return;
                }
                for (SlotData d : matches) {
                    UndoManager.pushUndoDeletion(d.customId, d.deepCopy(), playerUuid);
                    SlotManager.remove(d.customId);
                    NetworkManager.broadcastUpdate(server, new SlotUpdatePayload("remove", d.index, d.customId, null, null, 0, 0, "stone", null, null, null));
                }
                SlotManager.saveAll();
                ChatHelper.success(src, "§7AI: Deleted §f" + matches.size() + " §ablock(s) matching '" + result.param1() + "'. Use §f/cb undo §ato restore.");
            }

            case SET_GLOW -> {
                int level = Math.max(0, Math.min(15, Integer.parseInt(result.param2())));
                List<SlotData> matches = matchingBlocks(result.param1());
                if (matches.isEmpty()) {
                    ChatHelper.info(src, "§7AI: No blocks match '" + result.param1() + "'.");
                    return;
                }
                int count = 0;
                for (SlotData d : matches) {
                    UndoManager.pushUndoMutation(d.customId, d, "setglow", playerUuid);
                    SlotManager.setLightLevel(d.customId, level);
                    count++;
                }
                SlotManager.saveAll();
                ChatHelper.success(src, "§7AI: Set glow §f" + level + " §aon §f" + count + " §ablock(s).");
            }

            case RENAME -> {
                String id = result.param1();
                String newName = result.param2().replace("_", " ");
                if (!SlotManager.hasId(id)) {
                    ChatHelper.error(src, "§7AI: Block §f'" + id + "'§c not found.");
                    return;
                }
                SlotData d = SlotManager.getById(id);
                UndoManager.pushUndoMutation(id, d, "rename", playerUuid);
                SlotManager.rename(id, newName);
                SlotManager.saveAll();
                ChatHelper.success(src, "§7AI: Renamed §f'" + id + "'§a to §f'" + newName + "'§a.");
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static List<SlotData> matchingBlocks(String param) {
        if ("__broken__".equals(param)) {
            return new ArrayList<>(SlotManager.brokenBlocksWithReasons().keySet());
        }
        if (param.startsWith("prefix:")) {
            String prefix = param.substring(7);
            return SlotManager.allSlots().stream()
                .filter(d -> d.customId.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
        }
        if (param.startsWith("contains:")) {
            String needle = param.substring(9);
            return SlotManager.allSlots().stream()
                .filter(d -> d.customId.toLowerCase(Locale.ROOT).contains(needle)
                          || d.displayName.toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        }
        if (param.startsWith("id:")) {
            String id = param.substring(3);
            SlotData d = SlotManager.getById(id);
            return d != null ? List.of(d) : List.of();
        }
        return List.of();
    }

    private static String summarize(List<SlotData> blocks) {
        if (blocks.size() <= 5) {
            return String.join(", ", blocks.stream().map(d -> "§f" + d.customId + "§7").toList());
        }
        List<String> first5 = blocks.subList(0, 5).stream().map(d -> "§f" + d.customId + "§7").toList();
        return String.join(", ", first5) + "§8... and §f" + (blocks.size() - 5) + "§8 more";
    }
}
