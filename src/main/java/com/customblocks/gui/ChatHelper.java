package com.customblocks.gui;

import com.customblocks.TextSanitizer;
import com.customblocks.core.VoiceCatalog;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.regex.Pattern;

public class ChatHelper {

    private static final String PREFIX = "§0§l[§b§lCB§0§l]§r ";

    /** Strips duplicate literal CB prefixes accidentally baked into message bodies. */
    private static final Pattern EMBEDDED_PREFIX = Pattern.compile("^(?:§0§l\\[§b§lCB§0§l\\]§r\\s*)+");

    private static final Pattern EMBEDDED_PREFIX_UNICODE =
        Pattern.compile("^(?:\\u00A70\\u00A7l\\[\\u00A7b\\u00A7lCB\\u00A70\\u00A7l\\]\\u00A7r\\s*)+");
    private static final Pattern EMBEDDED_PREFIX_PLAIN =
        Pattern.compile("^(?:(?:\\u00A7[0-9A-FK-ORa-fk-or]){0,4}\\[CB\\](?:\\u00A7r)?\\s*)+");

    /** Voice-catalog line for the active {@code voiceMode} (no CB prefix — helpers add it). */
    public static String formattedKey(String key, Object... args) {
        return stripEmbedded(TextSanitizer.fix(VoiceCatalog.format(key, args)));
    }

    static String stripEmbedded(String s) {
        if (s == null) return "";
        String t = EMBEDDED_PREFIX.matcher(TextSanitizer.fix(s)).replaceAll("");
        t = EMBEDDED_PREFIX_UNICODE.matcher(t).replaceAll("");
        return EMBEDDED_PREFIX_PLAIN.matcher(t).replaceAll("");
    }

    /** One §0§l[§b§lCB§0§l]§r prefix + your already-formatted body (colors, etc.). */
    public static MutableText rawPrefixed(String formattedBody) {
        return Text.literal(PREFIX + stripEmbedded(formattedBody));
    }

    public static void success(ServerPlayerEntity player, String message) {
        String body = stripEmbedded(message);
        player.sendMessage(Text.literal(PREFIX + "§f" + body + " §a✔"), false);
    }

    public static void success(ServerCommandSource src, String message) {
        String body = stripEmbedded(message);
        src.sendMessage(Text.literal(PREFIX + "§f" + body + " §a✔"));
    }

    public static void error(ServerPlayerEntity player, String message) {
        String body = stripEmbedded(message);
        player.sendMessage(Text.literal(PREFIX + "§c" + body + " §4✖"), false);
        GuiManager.logError();
    }

    public static void error(ServerCommandSource src, String message) {
        String body = stripEmbedded(message);
        src.sendError(Text.literal(PREFIX + "§c" + body + " §4✖"));
        GuiManager.logError();
    }

    public static void info(ServerPlayerEntity player, String message) {
        String body = stripEmbedded(message);
        player.sendMessage(Text.literal(PREFIX + "§7" + body + " §e✦"), false);
    }

    public static void info(ServerCommandSource src, String message) {
        String body = stripEmbedded(message);
        src.sendMessage(Text.literal(PREFIX + "§7" + body + " §e✦"));
    }

    public static void warn(ServerPlayerEntity player, String message) {
        String body = stripEmbedded(message);
        player.sendMessage(Text.literal(PREFIX + "§e" + body + " §6⚠"), false);
    }

    public static void warn(ServerCommandSource src, String message) {
        String body = stripEmbedded(message);
        src.sendMessage(Text.literal(PREFIX + "§e" + body + " §6⚠"));
    }

    /**
     * One {@code [CB]} prefix + voice line for saved draft; entire line runs {@code /cb resume} on click.
     */
    public static void notifyDraftSavedWithResume(ServerPlayerEntity player) {
        String body = stripEmbedded(VoiceCatalog.format("cancel.draft.saved"));
        MutableText link = Text.literal(body).styled(s -> s
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cb resume"))
            .withUnderline(true));
        player.sendMessage(Text.literal(PREFIX).append(link), false);
    }

    /**
     * Phase B4: unknown first argument — voice intro + clickable command that suggests into chat.
     */
    public static void didYouMeanSuggestion(ServerPlayerEntity player, String typedFirst, String fullSuggestedCommand) {
        String intro = stripEmbedded(VoiceCatalog.format("cmd.did_you_mean_intro", typedFirst));
        String suffix = stripEmbedded(VoiceCatalog.format("cmd.did_you_mean_suffix"));
        MutableText link = Text.literal(fullSuggestedCommand).styled(s -> s
            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, fullSuggestedCommand + " "))
            .withUnderline(true));
        player.sendMessage(Text.literal(PREFIX).append(Text.literal(intro)).append(link).append(Text.literal(suffix)), false);
    }

    /**
     * H2: After a single-block deletion, send a clickable undo link.
     * The link runs {@code /cb undo 1} and is styled to look time-sensitive.
     */
    public static void clickableUndo(ServerPlayerEntity player, String deletedId) {
        MutableText label = Text.literal("§a§n[Click here to undo (15s)]§r").styled(s -> s
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cb undo 1")));
        player.sendMessage(
            Text.literal(PREFIX + "§c'" + deletedId + "' §7deleted. ").append(label),
            false);
    }

    /**
     * Non-player source (console): same wording; suggest click is meaningless so keep plain literals.
     */
    public static void didYouMeanConsole(ServerCommandSource src, String typedFirst, String fullSuggestedCommand) {
        String intro = stripEmbedded(VoiceCatalog.format("cmd.did_you_mean_intro", typedFirst));
        String suffix = stripEmbedded(VoiceCatalog.format("cmd.did_you_mean_suffix"));
        src.sendMessage(Text.literal(PREFIX + "§7" + intro + "§r§f" + fullSuggestedCommand + "§r" + suffix));
    }
}
