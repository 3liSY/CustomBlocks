package com.customblocks.command;

import com.customblocks.CustomBlocksConfig;
import com.customblocks.gui.ChatHelper;
import com.customblocks.gui.GuiManager;

import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.customblocks.core.SlotManager;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase B4 — fuzzy fallback when the first argument is not a registered {@code /cb} literal.
 */
public final class DidYouMean {

    private static final Pattern CB_WORD = Pattern.compile("/cb\\s+(\\w+)");
    private static final String[] EXTRA_FIRST_TOKENS = {
        "blocks", "blockscategory", "blockscat",
        "dupe", "duplicate", "hexagon", "brush", "chisel",
        "showbrokenblocks", "givecategory", "givedisplayblock",
        "reid", "bulkcolor", "resume", "magicitems",
        "listgui", "helpgui",
    };

    private static final ConcurrentHashMap<UUID, String> LAST_SUGGESTED = new ConcurrentHashMap<>();

    private DidYouMean() {}

    /**
     * Appends greedy catch-all branch as the last sibling (must remain after all literals).
     */
    public static LiteralArgumentBuilder<ServerCommandSource> appendFallbackBranch(
            LiteralArgumentBuilder<ServerCommandSource> root
    ) {
        if ("off".equalsIgnoreCase(CustomBlocksConfig.normalizeDidYouMeanMode(CustomBlocksConfig.didYouMeanMode))) {
            return root;
        }
        return root.then(CommandManager.argument("__cb_unknown_tail", StringArgumentType.greedyString())
            .requires(PermissionHelper::canUse)
            .executes(ctx -> handleUnknown(ctx.getSource(),
                StringArgumentType.getString(ctx, "__cb_unknown_tail"))));
    }

    public static LinkedHashSet<String> subcommandCandidates() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (HelpRegistry.Entry e : HelpRegistry.all()) {
            Matcher m = CB_WORD.matcher(e.syntax());
            while (m.find()) {
                set.add(m.group(1).toLowerCase(Locale.ROOT));
            }
        }
        for (String t : EXTRA_FIRST_TOKENS) {
            set.add(t.toLowerCase(Locale.ROOT));
        }
        return set;
    }

    public static int handleUnknown(ServerCommandSource src, String tail) {
        String mode = CustomBlocksConfig.normalizeDidYouMeanMode(CustomBlocksConfig.didYouMeanMode);
        if ("off".equals(mode) || tail == null) return 0;
        tail = tail.trim();
        if (tail.isEmpty()) return 0;

        int sp = tail.indexOf(' ');
        String first = (sp < 0 ? tail : tail.substring(0, sp)).toLowerCase(Locale.ROOT).trim();
        String remainder = sp < 0 ? "" : tail.substring(sp).trim();

        LinkedHashSet<String> cmds = subcommandCandidates();
        ServerPlayerEntity player = src.getPlayer();
        String bestCmd = pickBest(mode, first, cmds, player);

        String full;
        String blockGift = resolveBlockGiftIfGenius(mode, first, bestCmd != null);

        if (bestCmd != null) {
            full = "/cb " + bestCmd + (remainder.isEmpty() ? "" : " " + remainder);
        } else if (blockGift != null) {
            full = "/cb give " + blockGift + (remainder.isEmpty() ? "" : " " + remainder);
        } else {
            return 0;
        }

        if (player != null) {
            if (bestCmd != null) {
                LAST_SUGGESTED.put(player.getUuid(), bestCmd);
            } else if (blockGift != null) {
                LAST_SUGGESTED.put(player.getUuid(), "give");
            }
            GuiManager.playClick(player);
            ChatHelper.didYouMeanSuggestion(player, first, full);
        } else {
            ChatHelper.didYouMeanConsole(src, first, full);
        }
        return 1;
    }

    /** Genius-only: fuzzy-block match when no decent subcommand typo was found. */
    private static String resolveBlockGiftIfGenius(String mode, String typed, boolean haveCmdHit) {
        if (!"genius".equals(mode) || typed.length() < 4 || haveCmdHit) return null;

        String tl = typed.toLowerCase(Locale.ROOT);
        String bestId = null;
        int bestEff = Integer.MAX_VALUE;

        for (var d : SlotManager.allSlots()) {
            String id = d.customId;
            String il = id.toLowerCase(Locale.ROOT);
            boolean prefix = tl.length() >= 2 && il.startsWith(tl);
            int dist = levenshtein(tl, il);
            int eff = prefix ? Math.min(dist, 1) : dist;
            if (eff <= 3 && (bestId == null || eff < bestEff || (eff == bestEff && id.compareTo(bestId) < 0))) {
                bestEff = eff;
                bestId = id;
            }
        }
        return bestId;
    }

    private static String pickBest(String mode, String typed, LinkedHashSet<String> cands,
                                   ServerPlayerEntity player) {
        int maxDist = "strict".equals(mode) ? 1 : ("smart".equals(mode) || "genius".equals(mode)) ? 3 : 3;

        UUID uuid = player != null ? player.getUuid() : null;
        String last = uuid != null ? LAST_SUGGESTED.get(uuid) : null;

        String best = null;
        int bestScore = Integer.MAX_VALUE;

        for (String c : cands) {
            if (typed.equalsIgnoreCase(c)) continue;

            int d = levenshtein(typed, c);

            boolean prefixBoost = ("genius".equals(mode) || "smart".equals(mode))
                    && typed.length() >= 2 && c.startsWith(typed);

            boolean lastBoost = "genius".equals(mode)
                    && last != null && last.equalsIgnoreCase(c) && typed.length() >= 3;

            if (d > maxDist && !prefixBoost) continue;

            int eff = prefixBoost ? Math.min(d, 1) : d;
            if (lastBoost) eff = Math.max(0, eff - 1);

            int score = eff * 1000 + c.length();
            if (best == null || score < bestScore || (score == bestScore && c.compareTo(best) < 0)) {
                best = c;
                bestScore = score;
            }
        }
        return best;
    }

    private static int levenshtein(String a, String b) {
        if (a.equals(b)) return 0;
        int n = a.length(), m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;
        int[] dp = new int[m + 1];
        for (int j = 0; j <= m; j++) dp[j] = j;
        for (int i = 1; i <= n; i++) {
            int prev = dp[0];
            dp[0] = i;
            for (int j = 1; j <= m; j++) {
                int tmp = dp[j];
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[j] = Math.min(Math.min(dp[j] + 1, dp[j - 1] + 1), prev + cost);
                prev = tmp;
            }
        }
        return dp[m];
    }
}
