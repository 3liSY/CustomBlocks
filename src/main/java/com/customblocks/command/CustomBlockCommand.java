package com.customblocks.command;

import com.customblocks.CustomBlocksMod;
import com.customblocks.CustomBlocksConfig;
import com.customblocks.core.ColorVariantService;
import com.customblocks.gui.FeedbackHelper;
import com.customblocks.gui.GuiManager;
import com.customblocks.ImageProcessor;
import com.customblocks.core.FavoritesManager;
import com.customblocks.core.IncidentRecorder;
import com.customblocks.core.SlotData;
import com.customblocks.core.SlotManager;
import com.customblocks.core.UndoManager;
import com.customblocks.network.NetworkManager;
import com.customblocks.command.PermissionHelper;
import com.customblocks.block.SlotBlock;
import com.customblocks.network.SlotUpdatePayload;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import com.customblocks.gui.ChatHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CustomBlockCommand {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(5))
        .build();

    // Mixed alphabet for share codes — excludes filesystem-unsafe chars (/ \ : ? * " < > |)
    private static final String SHARE_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%&";

    /** Generates a 12-char code from SHA-256 hash of the input. */
    public static String generateShareCode(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 12; i++) sb.append(SHARE_ALPHABET.charAt((hashBytes[i] & 0xFF) % SHARE_ALPHABET.length()));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static final SuggestionProvider<ServerCommandSource> BLOCK_SUGGESTIONS =
            (ctx, builder) -> { for (String id : SlotManager.allSlots().stream().map(d -> d.customId).collect(java.util.stream.Collectors.toList())) builder.suggest(id); return builder.buildFuture(); };

    private static final String[] VALID_SOUNDS = {
        "stone","wood","grass","metal","glass","sand","wool",
        "gravel","snow","dirt","coral","bamboo","nether_brick","ice","honey","bone","slime"
    };
    private static final SuggestionProvider<ServerCommandSource> SOUND_SUGGESTIONS =
            (ctx, builder) -> { for (String s : VALID_SOUNDS) builder.suggest(s); return builder.buildFuture(); };

    private static final SuggestionProvider<ServerCommandSource> CATEGORY_SUGGESTIONS =
            (ctx, builder) -> { for (com.customblocks.core.Category cat : com.customblocks.core.CategoryManager.getAllCategories()) builder.suggest(cat.key()); return builder.buildFuture(); };

    private static final SuggestionProvider<ServerCommandSource> FACE_SUGGESTIONS =
            (ctx, builder) -> { for (String f : SlotData.FACE_KEYS) builder.suggest(f); return builder.buildFuture(); };

    private static final SuggestionProvider<ServerCommandSource> SHAPE_SUGGESTIONS =
            (ctx, builder) -> { for (String k : SlotManager.SHAPE_PRESETS.keySet()) builder.suggest(k); return builder.buildFuture(); };

    private static final SuggestionProvider<ServerCommandSource> DRESS_OVERLAY_SUGGESTIONS =
            (ctx, builder) -> { for (String overlay : ColorVariantService.dressOverlays()) builder.suggest(overlay); return builder.buildFuture(); };

    private static final SuggestionProvider<ServerCommandSource> GRADIENT_MODE_SUGGESTIONS =
            (ctx, builder) -> { builder.suggest("--preview"); builder.suggest("--apply"); return builder.buildFuture(); };
    private static final SuggestionProvider<ServerCommandSource> VOICE_MODE_SUGGESTIONS =
            (ctx, builder) -> {
                builder.suggest("friendly");
                builder.suggest("professional");
                builder.suggest("royal");
                builder.suggest("minimal");
                builder.suggest("arabic");
                builder.suggest("silly");
                return builder.buildFuture();
            };

    /**
     * Multi-block suggestion provider for bulkdelete.
     * Parses already-typed IDs from the input and suggests remaining valid block IDs.
     */
    private static final SuggestionProvider<ServerCommandSource> MULTI_BLOCK_SUGGESTIONS =
            (ctx, builder) -> {
                String input = ctx.getInput();
                // Extract the part after "bulkdelete "
                int cmdEnd = input.indexOf("bulkdelete");
                java.util.Set<String> alreadyTyped = new java.util.HashSet<>();
                if (cmdEnd >= 0) {
                    String afterCmd = input.substring(cmdEnd + "bulkdelete".length()).trim();
                    // Get the last partial token being typed
                    String[] tokens = afterCmd.split("\\s+");
                    for (int i = 0; i < tokens.length - 1; i++) {
                        alreadyTyped.add(tokens[i].toLowerCase());
                    }
                }
                for (SlotData d : SlotManager.allSlots()) {
                    if (!alreadyTyped.contains(d.customId.toLowerCase())) {
                        builder.suggest(d.customId);
                    }
                }
                return builder.buildFuture();
            };

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, reg, env) -> {
            LiteralArgumentBuilder<ServerCommandSource> tree = CommandManager.literal("customblock")
                .requires(src -> PermissionHelper.canUse(src))
                .executes(ctx -> cmdGui(ctx.getSource()))

                // ── create / createurl ──────────────────────────────────────
                .then(CommandManager.literal("create")
                    .requires(PermissionHelper::canCreate)
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .executes(ctx -> usage(ctx.getSource(), "create"))
                        .then(CommandManager.argument("name", StringArgumentType.string())
                            .executes(ctx -> usage(ctx.getSource(), "create"))
                            // /cb create <id> <name> <size> <url>  — size first so greedy URL still works
                            .then(CommandManager.argument("size", IntegerArgumentType.integer(16, 256))
                                .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                    .executes(ctx -> cmdAdd(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id"),
                                        StringArgumentType.getString(ctx, "name"),
                                        StringArgumentType.getString(ctx, "url").trim(),
                                        IntegerArgumentType.getInteger(ctx, "size")))))
                            .then(CommandManager.argument("size_text", StringArgumentType.word())
                                .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                    .executes(ctx -> cmdAddText(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id"),
                                        StringArgumentType.getString(ctx, "name"),
                                        StringArgumentType.getString(ctx, "url").trim(),
                                        StringArgumentType.getString(ctx, "size_text")))))
                            // /cb create <id> <name> <url>  — default 128
                            .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(ctx -> cmdAdd(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "id"),
                                    StringArgumentType.getString(ctx, "name"),
                                    StringArgumentType.getString(ctx, "url").trim(),
                                    128))))))

                // ── blocks & blockscat ──────────────────────────────────────
                .then(CommandManager.literal("blocks")
                    .requires(src -> PermissionHelper.canCategoryView(src))
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) {
                            GuiManager.playClick(p);
                            GuiManager.openBlocksGui(p, 0);
                        }
                        else ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only"));
                        return 1;
                    }))
                .then(CommandManager.literal("blockscategory")
                    .requires(src -> PermissionHelper.canCategoryView(src))
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) {
                            GuiManager.playClick(p);
                            GuiManager.openCategoryBrowser(p, 0);
                        }
                        else ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only"));
                        return 1;
                    }))
                .then(CommandManager.literal("blockscat")
                    .requires(src -> PermissionHelper.canCategoryView(src))
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) {
                            GuiManager.playClick(p);
                            GuiManager.openCategoryBrowser(p, 0);
                        }
                        else ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only"));
                        return 1;
                    }))

                // ── blockadd ────────────────────────────────────────────────
                .then(CommandManager.literal("blockadd")
                    .requires(src -> PermissionHelper.canCategoryAssign(src))
                    .executes(ctx -> usage(ctx.getSource(), "blockadd"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("cat", StringArgumentType.word())
                            .suggests(CATEGORY_SUGGESTIONS)
                            .executes(ctx -> cmdBlockAdd(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "cat"))))))
                .then(CommandManager.literal("bulkblockadd")
                    .requires(src -> PermissionHelper.canCategoryAssign(src))
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) {
                            ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.player_only_gui"));
                            return 0;
                        }
                        GuiManager.openBulkAssignPicker(p, 0);
                        return 1;
                    })
                    .then(CommandManager.argument("cat", StringArgumentType.word())
                        .suggests(CATEGORY_SUGGESTIONS)
                        .then(CommandManager.argument("ids", StringArgumentType.greedyString())
                            .suggests(MULTI_BLOCK_SUGGESTIONS)
                            .executes(ctx -> cmdBulkBlockAdd(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "cat"),
                                StringArgumentType.getString(ctx, "ids"))))))
                .then(CommandManager.literal("bulkrecolor")
                    .requires(src -> PermissionHelper.canCategoryAssign(src))
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) {
                            ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.player_only_gui"));
                            return 0;
                        }
                        GuiManager.openBulkRecolorWizard(p, 0);
                        return 1;
                    })
                    .then(CommandManager.argument("color", StringArgumentType.word())
                        .suggests((ctx, b) -> { b.suggest("green"); b.suggest("yellow"); b.suggest("black"); return b.buildFuture(); })
                        .executes(ctx -> cmdBulkRecolor(ctx.getSource(),
                            StringArgumentType.getString(ctx, "color"),
                            "all",
                            false))
                        .then(CommandManager.argument("scope", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String scope = StringArgumentType.getString(ctx, "scope");
                                boolean apply = scope.contains("--apply");
                                String exclude = "";
                                java.util.regex.Matcher m = java.util.regex.Pattern.compile("--exclude=([^\\s]+)").matcher(scope);
                                if (m.find()) exclude = m.group(1);
                                String clean = scope.replace("--apply", "").replaceAll("--exclude=[^\\s]+", "").trim();
                                return cmdBulkRecolor(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "color"),
                                    clean,
                                    exclude,
                                    apply);
                            }))))
                .then(CommandManager.literal("bulkcolor")
                    .requires(src -> PermissionHelper.canCategoryAssign(src))
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) {
                            ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.player_only_gui"));
                            return 0;
                        }
                        GuiManager.openBulkRecolorWizard(p, 0);
                        return 1;
                    })
                    .then(CommandManager.argument("color", StringArgumentType.word())
                        .suggests((ctx, b) -> { b.suggest("green"); b.suggest("yellow"); b.suggest("black"); return b.buildFuture(); })
                        .executes(ctx -> cmdBulkRecolor(ctx.getSource(),
                            StringArgumentType.getString(ctx, "color"),
                            "all",
                            false))
                        .then(CommandManager.argument("scope", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String scope = StringArgumentType.getString(ctx, "scope");
                                boolean apply = scope.contains("--apply");
                                String exclude = "";
                                java.util.regex.Matcher m = java.util.regex.Pattern.compile("--exclude=([^\\s]+)").matcher(scope);
                                if (m.find()) exclude = m.group(1);
                                String clean = scope.replace("--apply", "").replaceAll("--exclude=[^\\s]+", "").trim();
                                return cmdBulkRecolor(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "color"),
                                    clean,
                                    exclude,
                                    apply);
                            }))))

                // ── givecategory ────────────────────────────────────────────
                .then(CommandManager.literal("givecategory")
                    .requires(src -> PermissionHelper.canCategoryAssign(src))
                    .executes(ctx -> usage(ctx.getSource(), "givecategory"))
                    .then(CommandManager.argument("cat", StringArgumentType.word())
                        .suggests(CATEGORY_SUGGESTIONS)
                        .executes(ctx -> cmdGiveCategory(ctx.getSource(),
                            StringArgumentType.getString(ctx, "cat")))))

                // ── givedisplayblock ────────────────────────────────────────
                .then(CommandManager.literal("givedisplayblock")
                    .requires(src -> PermissionHelper.canCategoryAssign(src))
                    .executes(ctx -> usage(ctx.getSource(), "givedisplayblock"))
                    .then(CommandManager.argument("cat", StringArgumentType.word())
                        .suggests(CATEGORY_SUGGESTIONS)
                        .executes(ctx -> cmdGiveDisplayBlock(ctx.getSource(),
                            StringArgumentType.getString(ctx, "cat")))))

                // ── exportcategory ──────────────────────────────────────────
                .then(CommandManager.literal("exportcategory")
                    .requires(src -> PermissionHelper.canCategoryExport(src))
                    .executes(ctx -> usage(ctx.getSource(), "exportcategory"))
                    .then(CommandManager.argument("cat", StringArgumentType.word())
                        .suggests(CATEGORY_SUGGESTIONS)
                        .executes(ctx -> cmdExportCategory(ctx.getSource(),
                            StringArgumentType.getString(ctx, "cat")))))

                // ── exportall ───────────────────────────────────────────────
                .then(CommandManager.literal("exportall")
                    .requires(src -> PermissionHelper.canCategoryExport(src))
                    .executes(ctx -> cmdExportAll(ctx.getSource())))

                // ── sharecategory & importcategory ──────────────────────────
                .then(CommandManager.literal("sharecategory")
                    .requires(src -> PermissionHelper.canMarketplacePublish(src))
                    .executes(ctx -> usage(ctx.getSource(), "sharecategory"))
                    .then(CommandManager.argument("cat", StringArgumentType.word())
                        .suggests(CATEGORY_SUGGESTIONS)
                        .executes(ctx -> cmdShareCategory(ctx.getSource(),
                            StringArgumentType.getString(ctx, "cat")))))
                .then(CommandManager.literal("importcategory")
                    .requires(src -> PermissionHelper.canCategoryManage(src))
                    .executes(ctx -> usage(ctx.getSource(), "importcategory"))
                    .then(CommandManager.argument("code", StringArgumentType.word())
                        .executes(ctx -> cmdImportCategory(ctx.getSource(),
                            StringArgumentType.getString(ctx, "code")))))

                // ── delete ──────────────────────────────────────────────────
                .then(CommandManager.literal("delete")
                    .requires(PermissionHelper::canDelete)
                    .executes(ctx -> usage(ctx.getSource(), "delete"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdDelete(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))

                // ── bulkdelete ──────────────────────────────────────────────
                .then(CommandManager.literal("bulkdelete")
                    .requires(PermissionHelper::canBulk)
                    .executes(ctx -> usage(ctx.getSource(), "bulkdelete"))
                    .then(CommandManager.argument("ids", StringArgumentType.greedyString())
                        .suggests(MULTI_BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdBulkDelete(ctx.getSource(),
                            StringArgumentType.getString(ctx, "ids")))))

                // ── rename ──────────────────────────────────────────────────
                .then(CommandManager.literal("rename")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "rename"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("newname", StringArgumentType.greedyString())
                            .executes(ctx -> cmdRename(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "newname"))))))

                // ── reid ────────────────────────────────────────────────────
                .then(CommandManager.literal("reid")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "reid"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("newid", StringArgumentType.word())
                            .executes(ctx -> cmdReId(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "newid"))))))

                // ── exportblock ─────────────────────────────────────────────
                .then(CommandManager.literal("exportblock")
                    .requires(PermissionHelper::canAdmin)
                    .executes(ctx -> usage(ctx.getSource(), "exportblock"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdExportBlock(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id")))))

                // ── importblock ─────────────────────────────────────────────
                .then(CommandManager.literal("importblock")
                    .requires(PermissionHelper::canAdmin)
                    .executes(ctx -> usage(ctx.getSource(), "importblock"))
                    .then(CommandManager.argument("code", StringArgumentType.greedyString())
                        .executes(ctx -> cmdImportBlock(ctx.getSource(),
                                StringArgumentType.getString(ctx, "code")))))

                // ── retexture ───────────────────────────────────────────────
                .then(CommandManager.literal("retexture")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "retexture"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> usage(ctx.getSource(), "retexture"))
                        .then(CommandManager.argument("size", IntegerArgumentType.integer(16, 256))
                            .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(ctx -> cmdRetexture(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "id"),
                                    StringArgumentType.getString(ctx, "url").trim(),
                                    IntegerArgumentType.getInteger(ctx, "size")))))
                        .then(CommandManager.argument("size_text", StringArgumentType.word())
                            .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(ctx -> cmdRetextureText(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "id"),
                                    StringArgumentType.getString(ctx, "size_text"),
                                    StringArgumentType.getString(ctx, "url").trim()))))
                        .then(CommandManager.argument("url", StringArgumentType.greedyString())
                            .executes(ctx -> cmdRetexture(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "url").trim(),
                                ImageProcessor.DEFAULT_SIZE)))))

                // ── give ────────────────────────────────────────────────────
                .then(CommandManager.literal("give")
                    .requires(PermissionHelper::canGive)
                    .executes(ctx -> usage(ctx.getSource(), "give"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> usage(ctx.getSource(), "give"))
                        .executes(ctx -> cmdGive(ctx.getSource(), StringArgumentType.getString(ctx, "id"), 1, null))
                        .then(CommandManager.argument("amount", IntegerArgumentType.integer(1, 64))
                            .executes(ctx -> cmdGive(ctx.getSource(), StringArgumentType.getString(ctx, "id"),
                                IntegerArgumentType.getInteger(ctx, "amount"), null))
                            .then(CommandManager.argument("player", EntityArgumentType.players())
                                .executes(ctx -> cmdGive(ctx.getSource(), StringArgumentType.getString(ctx, "id"),
                                    IntegerArgumentType.getInteger(ctx, "amount"),
                                    EntityArgumentType.getPlayers(ctx, "player")))))
                        .then(CommandManager.argument("player", EntityArgumentType.players())
                            .executes(ctx -> cmdGive(ctx.getSource(), StringArgumentType.getString(ctx, "id"), 1,
                                EntityArgumentType.getPlayers(ctx, "player"))))
                        .then(CommandManager.argument("amount_text", StringArgumentType.greedyString())
                            .executes(ctx -> cmdGiveText(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "amount_text"))))))

                .then(CommandManager.literal("favorite")
                    .requires(PermissionHelper::canFavorite)
                    .executes(ctx -> cmdFavorite(ctx.getSource(), null))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdFavorite(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))

                // ── lock / unlock (Phase H1) ─────────────────────────────────
                .then(CommandManager.literal("lock")
                    .requires(PermissionHelper::canEdit)
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdLock(ctx.getSource(), StringArgumentType.getString(ctx, "id"), true))))

                .then(CommandManager.literal("unlock")
                    .requires(PermissionHelper::canEdit)
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdLock(ctx.getSource(), StringArgumentType.getString(ctx, "id"), false))))

                // ── setglow ─────────────────────────────────────────────────
                // Use unbounded integer + greedy fallback so out-of-range values produce
                // branded `[CB]` errors instead of vanilla red brigadier rejection.
                .then(CommandManager.literal("setglow")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "setglow"))
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> usage(ctx.getSource(), "setglow"))
                        .then(CommandManager.argument("level", IntegerArgumentType.integer())
                            .executes(ctx -> cmdSetGlowSafe(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                IntegerArgumentType.getInteger(ctx, "level"))))
                        .then(CommandManager.argument("level_text", StringArgumentType.greedyString())
                            .executes(ctx -> cmdSetGlowText(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "level_text"))))))

                // ── sethardness ─────────────────────────────────────────────
                .then(CommandManager.literal("sethardness")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "sethardness"))
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> usage(ctx.getSource(), "sethardness"))
                        .then(CommandManager.argument("hardness", FloatArgumentType.floatArg())
                            .executes(ctx -> cmdSetHardnessSafe(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                FloatArgumentType.getFloat(ctx, "hardness"))))
                        .then(CommandManager.argument("hardness_text", StringArgumentType.greedyString())
                            .executes(ctx -> cmdSetHardnessText(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "hardness_text"))))))

                // ── setsound ────────────────────────────────────────────────
                .then(CommandManager.literal("setsound")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "setsound"))
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> usage(ctx.getSource(), "setsound"))
                        .then(CommandManager.argument("type", StringArgumentType.word()).suggests(SOUND_SUGGESTIONS)
                            .executes(ctx -> cmdSetSound(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "type"))))))

                // ── showbrokenblocks ────────────────────────────────────────
                .then(CommandManager.literal("showbrokenblocks")
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) GuiManager.openBrokenBlocks(p);
                        else ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only"));
                        return 1;
                    }))

                .then(CommandManager.literal("settabicon")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) GuiManager.openTabIconPicker(p, 0);
                        else ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only"));
                        return 1;
                    })
                    .then(CommandManager.argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> cmdSetTabIcon(ctx.getSource(),
                            StringArgumentType.getString(ctx, "url").trim()))))
                // ── resize ──────────────────────────────────────────────────
                .then(CommandManager.literal("resize")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "resize"))
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> usage(ctx.getSource(), "resize"))
                        .then(CommandManager.argument("size", IntegerArgumentType.integer())
                            .executes(ctx -> cmdResizeSafe(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                IntegerArgumentType.getInteger(ctx, "size"))))
                        .then(CommandManager.argument("size_text", StringArgumentType.greedyString())
                            .executes(ctx -> cmdResizeText(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "size_text"))))))

                // ── undo ────────────────────────────────────────────────────
                .then(CommandManager.literal("undo")
                    .requires(PermissionHelper::canUndo)
                    .executes(ctx -> cmdUndo(ctx.getSource()))
                    .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 50))
                        .executes(ctx -> cmdUndoN(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count")))
                    .then(CommandManager.argument("count_text", StringArgumentType.greedyString())
                        .executes(ctx -> cmdUndoText(ctx.getSource(), StringArgumentType.getString(ctx, "count_text"))))))

                // ── redo ────────────────────────────────────────────────────
                .then(CommandManager.literal("redo")
                    .requires(PermissionHelper::canUndo)
                    .executes(ctx -> cmdRedo(ctx.getSource()))
                    .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 50))
                        .executes(ctx -> cmdRedoN(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count")))
                    .then(CommandManager.argument("count_text", StringArgumentType.greedyString())
                        .executes(ctx -> cmdRedoText(ctx.getSource(), StringArgumentType.getString(ctx, "count_text"))))))

                .then(CommandManager.literal("resume")
                    .requires(PermissionHelper::canResumeSession)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) {
                            ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only"));
                            return 0;
                        }
                        return com.customblocks.core.DraftManager.resume(p);
                    }))

                // ── /cb panic (Phase Q safety net) ───────────────────────────
                .then(CommandManager.literal("panic")
                    .requires(PermissionHelper::canPanic)
                    .executes(ctx -> cmdPanic(ctx.getSource()))
                    .then(CommandManager.literal("confirm")
                        .requires(PermissionHelper::canPanic)
                        .executes(ctx -> cmdPanicConfirm(ctx.getSource()))))

                // ── /cb recover ───────────────────────────────────────────────
                .then(CommandManager.literal("recover")
                    .requires(PermissionHelper::canPanic)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) { ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only")); return 0; }
                        com.customblocks.gui.GuiManager.openRecoverGui(p, 0);
                        return 1;
                    }))

                // (givesquare/givetriangle/giverectangle removed — use /cb square, /cb triangle, /cb rectangle)

                // ── dupe / duplicate (one-click clone) ───────────────────────
                .then(CommandManager.literal("dupe")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "dupe"))
                    .then(CommandManager.argument("sourceId", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdDupe(ctx.getSource(),
                            StringArgumentType.getString(ctx, "sourceId")))))

                .then(CommandManager.literal("duplicate")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "dupe"))
                    .then(CommandManager.argument("sourceId", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdDupe(ctx.getSource(),
                            StringArgumentType.getString(ctx, "sourceId")))))

                .then(CommandManager.literal("dress")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "dress"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("overlay", StringArgumentType.word())
                            .suggests(DRESS_OVERLAY_SUGGESTIONS)
                            .executes(ctx -> cmdDress(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "overlay"))))))

                .then(CommandManager.literal("gradient")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "gradient"))
                    .then(CommandManager.argument("fromId", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("toId", StringArgumentType.word())
                            .suggests(BLOCK_SUGGESTIONS)
                            .then(CommandManager.argument("steps", IntegerArgumentType.integer(1, 32))
                                .executes(ctx -> cmdGradient(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "fromId"),
                                    StringArgumentType.getString(ctx, "toId"),
                                    IntegerArgumentType.getInteger(ctx, "steps"),
                                    "--preview"))
                                .then(CommandManager.argument("mode", StringArgumentType.word())
                                    .suggests(GRADIENT_MODE_SUGGESTIONS)
                                    .executes(ctx -> cmdGradient(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "fromId"),
                                        StringArgumentType.getString(ctx, "toId"),
                                        IntegerArgumentType.getInteger(ctx, "steps"),
                                        StringArgumentType.getString(ctx, "mode")))))
                            .then(CommandManager.argument("steps_text", StringArgumentType.greedyString())
                                .executes(ctx -> cmdGradientText(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "fromId"),
                                    StringArgumentType.getString(ctx, "toId"),
                                    StringArgumentType.getString(ctx, "steps_text")))))))

                // ── data commands ───────────────────────────────────────────
                .then(CommandManager.literal("export")
                    .requires(PermissionHelper::canAdmin)
                    .executes(ctx -> cmdExport(ctx.getSource())))

                .then(CommandManager.literal("importfolder")
                    .requires(PermissionHelper::canAdmin)
                    .executes(ctx -> cmdImportFolder(ctx.getSource())))

                .then(CommandManager.literal("list")
                    .executes(ctx -> cmdList(ctx.getSource())))

                .then(CommandManager.literal("help")
                    .executes(ctx -> cmdHelp(ctx.getSource(), 1))
                    .then(CommandManager.argument("page", IntegerArgumentType.integer(1, 500))
                        .executes(ctx -> cmdHelp(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page")))))

                // ── new graphical interfaces ─────────────────────────────────
                .then(CommandManager.literal("listgui")
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) GuiManager.openEditorPicker(p, 0);
                        else ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only"));
                        return 1;
                    }))

                .then(CommandManager.literal("helpgui")
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) GuiManager.openHelpGui(p);
                        else ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only"));
                        return 1;
                    }))

                .then(CommandManager.literal("welcome")
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) {
                            GuiManager.openWelcomeGui(p);
                            sendVoicePickerLines(p);
                        }
                        else ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only"));
                        return 1;
                    }))

                .then(CommandManager.literal("voice")
                    .requires(PermissionHelper::canUse)
                    .executes(ctx -> cmdShowVoicePicker(ctx.getSource()))
                    .then(CommandManager.argument("mode", StringArgumentType.word())
                        .suggests(VOICE_MODE_SUGGESTIONS)
                        .executes(ctx -> cmdSetVoiceMode(
                            ctx.getSource(),
                            StringArgumentType.getString(ctx, "mode")))))

                .then(CommandManager.literal("menu")
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) GuiManager.openFeatureMenu(p, 0);
                        else ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only"));
                        return 1;
                    }))

                .then(CommandManager.literal("magicitems")
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) GuiManager.openMagicItemsGui(p);
                        else ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only"));
                        return 1;
                    }))

                // ── editor — works with or without ID ──────────────────────
                .then(CommandManager.literal("editor")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> cmdEditorPicker(ctx.getSource()))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdEditor(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id")))))

                // ── gui ──────────────────────────────────────────────────────
                .then(CommandManager.literal("gui")
                    .executes(ctx -> cmdGui(ctx.getSource())))

                // ── search ───────────────────────────────────────────────────
                .then(CommandManager.literal("search")
                    .executes(ctx -> usage(ctx.getSource(), "search"))
                    .then(CommandManager.argument("query", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayerEntity p = ctx.getSource().getPlayer();
                            if (p == null) { ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only")); return 0; }
                            GuiManager.openSearchPicker(p, StringArgumentType.getString(ctx, "query"), 0);
                            return 1;
                        })))



                // ── config ───────────────────────────────────────────────────
                .then(CommandManager.literal("config")
                    .requires(PermissionHelper::canConfig)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) GuiManager.openConfigWarningGui(p);
                        else ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only"));
                        return 1;
                    }))

                // ── reload ───────────────────────────────────────────────────
                .then(CommandManager.literal("reload")
                    .requires(PermissionHelper::canAdmin)
                    .executes(ctx -> cmdReload(ctx.getSource())))
                .then(CommandManager.literal("diagnostics")
                    .requires(PermissionHelper::canAdmin)
                    .executes(ctx -> cmdDiagnostics(ctx.getSource())))

                // ── resourcepack ─────────────────────────────────────────────
                .then(CommandManager.literal("resourcepack")
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) GuiManager.openResourceHub(p);
                        else ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only"));
                        return 1;
                    }))

                // ── rp (Alias + pause/resume) ─────────────────────────────────
                .then(CommandManager.literal("rp")
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) GuiManager.openResourceHub(p);
                        else ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only"));
                        return 1;
                    })
                    .then(CommandManager.literal("pause")
                        .requires(PermissionHelper::canAdmin)
                        .executes(ctx -> cmdRpPause(ctx.getSource(), true)))
                    .then(CommandManager.literal("resume")
                        .requires(PermissionHelper::canAdmin)
                        .executes(ctx -> cmdRpPause(ctx.getSource(), false))))

                // ── setshape ─────────────────────────────────────────────────
                .then(CommandManager.literal("setshape")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "setshape"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> usage(ctx.getSource(), "setshape"))
                        .then(CommandManager.argument("shape", StringArgumentType.greedyString())
                            .suggests(SHAPE_SUGGESTIONS)
                            .executes(ctx -> cmdSetShape(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "shape"))))))

                .then(CommandManager.literal("addshape")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "addshape"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> usage(ctx.getSource(), "addshape"))
                        .then(CommandManager.argument("coords", StringArgumentType.greedyString())
                            .executes(ctx -> cmdAddShape(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "coords"))))))

                .then(CommandManager.literal("removeshape")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "removeshape"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> usage(ctx.getSource(), "removeshape"))
                        .then(CommandManager.argument("boxindex", IntegerArgumentType.integer(0, 15))
                            .executes(ctx -> cmdRemoveShape(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                IntegerArgumentType.getInteger(ctx, "boxindex"))))
                        .then(CommandManager.argument("boxindex_text", StringArgumentType.greedyString())
                            .executes(ctx -> cmdRemoveShapeText(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "boxindex_text"))))))

                .then(CommandManager.literal("clearshape")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "clearshape"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdClearShape(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id")))))

                .then(CommandManager.literal("setcollision")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "setcollision"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> usage(ctx.getSource(), "setcollision"))
                        .then(CommandManager.literal("on")
                            .executes(ctx -> cmdSetCollision(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"), true)))
                        .then(CommandManager.literal("off")
                            .executes(ctx -> cmdSetCollision(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"), false)))))

                .then(CommandManager.literal("shapeeditor")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "shapeeditor"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdShapeEditor(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id")))))

                .then(CommandManager.literal("facechangegui")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "facechangegui"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdFaceChangeGui(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id")))))

                .then(CommandManager.literal("square")
                    .requires(PermissionHelper::canGive)
                    .executes(ctx -> usage(ctx.getSource(), "square"))
                    .then(CommandManager.argument("color", StringArgumentType.word())
                        .suggests((ctx, b) -> { b.suggest("black"); b.suggest("yellow"); b.suggest("green"); return b.buildFuture(); })
                        .executes(ctx -> cmdGiveSquare(ctx.getSource(),
                            StringArgumentType.getString(ctx, "color")))))

                .then(CommandManager.literal("triangle")
                    .requires(PermissionHelper::canGive)
                    .executes(ctx -> usage(ctx.getSource(), "triangle"))
                    .then(CommandManager.argument("color", StringArgumentType.word())
                        .suggests((ctx, b) -> { b.suggest("black"); b.suggest("yellow"); b.suggest("green"); return b.buildFuture(); })
                        .executes(ctx -> cmdGiveTriangle(ctx.getSource(),
                            StringArgumentType.getString(ctx, "color")))))

                .then(CommandManager.literal("rectangle")
                    .requires(PermissionHelper::canGive)
                    .executes(ctx -> cmdGiveRectangle(ctx.getSource())))

                .then(CommandManager.literal("hexagon")
                    .requires(PermissionHelper::canGive)
                    .executes(ctx -> cmdGiveHexagonInternal(ctx.getSource())))

                .then(CommandManager.literal("brush")
                    .requires(PermissionHelper::canGive)
                    .executes(ctx -> cmdGiveBrushInternal(ctx.getSource())))

                .then(CommandManager.literal("chisel")
                    .requires(PermissionHelper::canGive)
                    .executes(ctx -> cmdGiveChiselInternal(ctx.getSource())))

                .then(CommandManager.literal("diamondtriangle")
                    .requires(PermissionHelper::canGive)
                    .executes(ctx -> cmdGiveDiamondInternal(ctx.getSource())))

                .then(CommandManager.literal("customtriangle")
                    .requires(PermissionHelper::canGive)
                    .executes(ctx -> usage(ctx.getSource(), "customtriangle"))
                    .then(CommandManager.argument("hex", StringArgumentType.word())
                        .executes(ctx -> cmdGiveCustomColorToolsInternal(ctx.getSource(),
                            StringArgumentType.getString(ctx, "hex")))))

                // ── setface ──────────────────────────────────────────────────
                .then(CommandManager.literal("setface")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "setface"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> usage(ctx.getSource(), "setface"))
                        .then(CommandManager.argument("face", StringArgumentType.word()).suggests(FACE_SUGGESTIONS)
                            .executes(ctx -> usage(ctx.getSource(), "setface"))
                            .then(CommandManager.argument("size", IntegerArgumentType.integer(16, 256))
                                .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                    .executes(ctx -> cmdSetFace(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id"),
                                        StringArgumentType.getString(ctx, "face"),
                                        StringArgumentType.getString(ctx, "url").trim(),
                                        IntegerArgumentType.getInteger(ctx, "size")))))
                            .then(CommandManager.argument("size_text", StringArgumentType.word())
                                .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                    .executes(ctx -> cmdSetFaceText(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id"),
                                        StringArgumentType.getString(ctx, "face"),
                                        StringArgumentType.getString(ctx, "size_text"),
                                        StringArgumentType.getString(ctx, "url").trim()))))
                            .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(ctx -> cmdSetFace(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "id"),
                                    StringArgumentType.getString(ctx, "face"),
                                    StringArgumentType.getString(ctx, "url").trim(),
                                    ImageProcessor.DEFAULT_SIZE))))))

                // ── clearface ────────────────────────────────────────────────
                .then(CommandManager.literal("clearface")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "clearface"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> usage(ctx.getSource(), "clearface"))
                        .then(CommandManager.argument("face", StringArgumentType.word()).suggests(FACE_SUGGESTIONS)
                            .executes(ctx -> cmdClearFace(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "face"))))))

                // ── clearallfaces ────────────────────────────────────────────
                .then(CommandManager.literal("clearallfaces")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "clearallfaces"))
                        .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdClearAllFaces(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id")))))

                // ── history (Phase H₁) ──────────────────────────────────────
                .then(CommandManager.literal("history")
                    .requires(PermissionHelper::canAdmin)
                    .executes(ctx -> {
                        var entries = com.customblocks.core.HistoryTracker.latest(20);
                        if (entries.isEmpty()) {
                            ChatHelper.info(ctx.getSource(), "§7No mutations recorded this session.");
                        } else {
                            ChatHelper.info(ctx.getSource(), "§6§lRecent History §7(newest first)");
                            for (var e : entries) {
                                ctx.getSource().sendFeedback(() -> Text.literal(e.toDisplayString()), false);
                            }
                        }
                        return 1;
                    }))

                // ── note (Phase H₁) ─────────────────────────────────────────
                .then(CommandManager.literal("note")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "note"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "id");
                            String note = com.customblocks.core.BlockNotesManager.getNote(id);
                            if (note != null) ChatHelper.info(ctx.getSource(), "§e" + id + " §7note: §f" + note);
                            else ChatHelper.info(ctx.getSource(), "§e" + id + " §7has no note.");
                            return 1;
                        })
                        .then(CommandManager.argument("text", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String id = StringArgumentType.getString(ctx, "id");
                                String text = StringArgumentType.getString(ctx, "text");
                                if (!SlotManager.hasId(id)) {
                                    ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.block_not_found", id));
                                    return 0;
                                }
                                if (text.equalsIgnoreCase("clear") || text.equalsIgnoreCase("remove")) {
                                    com.customblocks.core.BlockNotesManager.removeNote(id);
                                    ChatHelper.success(ctx.getSource(), "§eNote cleared for §f" + id);
                                } else {
                                    com.customblocks.core.BlockNotesManager.setNote(id, text);
                                    ChatHelper.success(ctx.getSource(), "§eNote saved for §f" + id);
                                }
                                return 1;
                            }))))

                // ── exportpng (Phase H1) ─────────────────────────────────────
                .then(CommandManager.literal("exportpng")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "exportpng"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdExportPng(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id")))))

                // ── showcase (Phase H1) ──────────────────────────────────────
                .then(CommandManager.literal("showcase")
                    .requires(PermissionHelper::canUse)
                    .executes(ctx -> usage(ctx.getSource(), "showcase"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdShowcase(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id")))))

                // ── macro (Phase P1/P2) ────────────────────────────────────
                .then(CommandManager.literal("macro")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "macro"))
                    // /cb macro record <name>
                    .then(CommandManager.literal("record")
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .executes(ctx -> {
                                var p = ctx.getSource().getPlayer();
                                if (p == null) return 0;
                                String mName = StringArgumentType.getString(ctx, "name");
                                com.customblocks.core.MacroManager.startRecording(p.getUuid(), mName);
                                ChatHelper.info(ctx.getSource(), "§b[Macro] §aRecording started: §f" + mName);
                                ChatHelper.info(ctx.getSource(), "§7Block edits (rename, delete, retexture, properties) are now captured.");
                                ChatHelper.info(ctx.getSource(), "§7Run §f/cb macro stop §7to finish.");
                                return 1;
                            })))
                    // /cb macro stop
                    .then(CommandManager.literal("stop")
                        .executes(ctx -> {
                            var p = ctx.getSource().getPlayer();
                            if (p == null) return 0;
                            String mName = com.customblocks.core.MacroManager.recordingName(p.getUuid());
                            if (mName == null) { ChatHelper.warn(ctx.getSource(), "§7No recording in progress."); return 0; }
                            int count = com.customblocks.core.MacroManager.stopRecording(p.getUuid());
                            if (count < 0) { ChatHelper.error(ctx.getSource(), "§cFailed to save macro."); return 0; }
                            ChatHelper.info(ctx.getSource(), "§b[Macro] §aSaved §f" + mName + " §awith §f" + count + " §astep(s).");
                            return 1;
                        }))
                    // /cb macro run <name>
                    .then(CommandManager.literal("run")
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .executes(ctx -> {
                                var p = ctx.getSource().getPlayer();
                                if (p == null) return 0;
                                String mName = StringArgumentType.getString(ctx, "name");
                                int count = com.customblocks.core.MacroManager.runMacro(p, mName);
                                if (count < 0) { ChatHelper.error(ctx.getSource(), "§cMacro '§f" + mName + "§c' not found."); return 0; }
                                ChatHelper.info(ctx.getSource(), "§b[Macro] §aRan §f" + mName + " §a(" + count + " step(s)).");
                                return count;
                            })))
                    // /cb macro list
                    .then(CommandManager.literal("list")
                        .executes(ctx -> {
                            var macros = com.customblocks.core.MacroManager.listMacros();
                            if (macros.isEmpty()) { ChatHelper.info(ctx.getSource(), "§7No macros saved."); return 1; }
                            ChatHelper.info(ctx.getSource(), "§b[Macro] §7Saved macros §8(" + macros.size() + ")§7:");
                            macros.forEach(m -> ChatHelper.info(ctx.getSource(), "  §f" + m));
                            return 1;
                        }))
                    // /cb macro delete <name>
                    .then(CommandManager.literal("delete")
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .executes(ctx -> {
                                String mName = StringArgumentType.getString(ctx, "name");
                                boolean ok = com.customblocks.core.MacroManager.deleteMacro(mName);
                                if (!ok) { ChatHelper.warn(ctx.getSource(), "§7Macro '§f" + mName + "§7' not found."); return 0; }
                                ChatHelper.info(ctx.getSource(), "§b[Macro] §cDeleted §f" + mName);
                                return 1;
                            })))
                    // /cb macro show <name>
                    .then(CommandManager.literal("show")
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .executes(ctx -> {
                                String mName = StringArgumentType.getString(ctx, "name");
                                var cmds = com.customblocks.core.MacroManager.loadMacro(mName);
                                if (cmds == null) { ChatHelper.error(ctx.getSource(), "§cMacro '§f" + mName + "§c' not found."); return 0; }
                                ChatHelper.info(ctx.getSource(), "§b[Macro] §f" + mName + " §7(" + cmds.size() + " step(s))§8:");
                                for (int i = 0; i < cmds.size(); i++) {
                                    ChatHelper.info(ctx.getSource(), "  §8" + (i+1) + ". §f" + cmds.get(i));
                                }
                                return 1;
                            })))
                    // /cb macro add <name> <command...>
                    .then(CommandManager.literal("add")
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .then(CommandManager.argument("command", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String mName = StringArgumentType.getString(ctx, "name");
                                    String cmd   = StringArgumentType.getString(ctx, "command");
                                    boolean ok = com.customblocks.core.MacroManager.addCommand(mName, cmd);
                                    if (!ok) { ChatHelper.error(ctx.getSource(), "§cFailed to save macro."); return 0; }
                                    ChatHelper.info(ctx.getSource(), "§b[Macro] §aStep added to §f" + mName);
                                    return 1;
                                })))));

                // ── market (Phase L3) ──────────────────────────────────────────
                tree.then(CommandManager.literal("market")
                    .executes(ctx -> {
                        var p = ctx.getSource().getPlayer();
                        if (p == null) return 0;
                        com.customblocks.gui.GuiManager.openMarketGui(p, 0, false);
                        return 1;
                    }));

            dispatcher.register(DidYouMean.appendFallbackBranch(tree));
            dispatcher.register(CommandManager.literal("cb")
                .requires(src -> PermissionHelper.canUse(src))
                .executes(ctx -> {
                    var p = ctx.getSource().getPlayer();
                    if (p != null) com.customblocks.gui.GuiManager.openFeatureMenu(p, 0);
                    return 1;
                })
                .redirect(dispatcher.getRoot().getChild("customblock")));
        });
    }

    // ── Implementations ───────────────────────────────────────────────────────

    private static int cmdAdd(ServerCommandSource src, String rawId, String name, String url, int size) {
        final String id = sanitize(rawId);
        if (id.isEmpty()) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.invalid_id")); return 0; }
        if (SlotManager.hasId(id)) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.id_taken", id)); return 0; }
        if (SlotManager.freeSlots() == 0) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.all_slots_full", CustomBlocksConfig.maxSlots)); return 0; }
        ChatHelper.info(src, ChatHelper.formattedKey("cmd.downloading", size));
        MinecraftServer server = src.getServer();
        thread(() -> {
            try {
                final ImageProcessor.ProcessResult result = ImageProcessor.downloadAndProcess(url, size);
                server.execute(() -> {
                    SlotData d = SlotManager.assign(id, name, result.bytes());
                    if (d == null) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.no_free_slots_short")); return; }
                    
                    if (result.isAnimated()) {
                        SlotManager.setAnimMeta(id, result.mcmeta());
                        ChatHelper.info(src, ChatHelper.formattedKey("cmd.anim_meta_generated"));
                    }

                    UndoManager.pushUndoCreate(id, getPlayerUuid(src));
                    SlotManager.saveAll();
                    NetworkManager.broadcastUpdate(server,
                        new SlotUpdatePayload("add", d.index, id, name, result.bytes(),
                                d.lightLevel, d.hardness, d.soundType, null, null, result.mcmeta()));
                    com.customblocks.core.HistoryTracker.record(getPlayerUuid(src), getPlayerName(src), "created", id, name);
                    ChatHelper.success(src, ChatHelper.formattedKey("cmd.block_created", name, d.index));
                    com.customblocks.DiscordWebhook.post(
                        "\uD83D\uDFE9 **Block Created** by `" + getPlayerName(src) + "`\n" +
                        "ID: `" + id + "` · Name: `" + name + "` · Slot #" + d.index);
                    ServerPlayerEntity _achP = src.getPlayer();
                    if (_achP != null) com.customblocks.core.AchievementManager.onBlockCreated(_achP);
                });
            } catch (Exception e) {
                server.execute(() -> {
                    ChatHelper.error(src, ChatHelper.formattedKey("cmd.operation_failed", e.getMessage()));
                    GuiManager.logError();
                });
            }
        });
        return 1;
    }

    private static int cmdAddText(ServerCommandSource src, String rawId, String name, String url, String sizeText) {
        try {
            int size = Integer.parseInt(sizeText.trim());
            if (size < 16 || size > 256) {
                ChatHelper.error(src, "Texture size must be between 16 and 256.");
                return 0;
            }
            return cmdAdd(src, rawId, name, url, size);
        } catch (NumberFormatException ex) {
            ChatHelper.error(src, "Invalid texture size '" + sizeText + "'. Use 16-256.");
            return 0;
        }
    }

    /**
     * Auto-incremented dupe ID: tries {@code sourceId_dupe}, then {@code _dupe_2}, {@code _dupe_3}, … up to 99.
     */
    public static String generateDupeId(String sourceId) {
        String base = sourceId + "_dupe";
        if (!SlotManager.hasId(base)) return base;
        for (int i = 2; i <= 99; i++) {
            String candidate = base + "_" + i;
            if (!SlotManager.hasId(candidate)) return candidate;
        }
        return base + "_" + (System.currentTimeMillis() % 10000);
    }

    private static int cmdDupe(ServerCommandSource src, String rawSourceId) {
        String sourceId = sanitize(rawSourceId);
        if (!SlotManager.hasId(sourceId)) { src.sendMessage(notFound(sourceId)); return 0; }
        if (SlotManager.freeSlots() == 0) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.all_slots_full", CustomBlocksConfig.maxSlots)); return 0; }

        String newId = generateDupeId(sourceId);
        SlotData s = SlotManager.getById(sourceId);
        String finalName = s.displayName + " (Copy)";

        byte[] texCopy = s.texture != null ? s.texture.clone() : null;
        SlotData d = SlotManager.assign(newId, finalName, texCopy);
        if (d == null) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.no_free_slots_short")); return 0; }

        // Copy all properties and per-face textures
        SlotManager.setLightLevel(newId, s.lightLevel);
        SlotManager.setHardness(newId, s.hardness);
        SlotManager.setSoundType(newId, s.soundType);
        if (s.animMeta != null) SlotManager.setAnimMeta(newId, s.animMeta);
        for (var e : s.faceTextures.entrySet())
            SlotManager.setFaceTexture(newId, e.getKey(), e.getValue().clone());
        if (s.isShaped()) SlotManager.setShape(newId, new java.util.ArrayList<>(s.shapeBoxes));
        if (s.noCollision) SlotManager.setCollision(newId, false);

        UndoManager.pushUndoCreate(newId, getPlayerUuid(src));
        SlotManager.saveAll();
        // R12: duplicate inherits ALL the same categories from the source
        try {
            java.util.Set<String> srcCats = com.customblocks.core.CategoryManager.getCategoriesForBlock(sourceId);
            for (String cat : srcCats) {
                com.customblocks.core.CategoryManager.assignBlock(newId, cat);
            }
        } catch (Throwable ignored) {}
        d = SlotManager.getById(newId);
        NetworkManager.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("add", d.index, newId, finalName, texCopy,
                    d.lightLevel, d.hardness, d.soundType, null, null, d.animMeta));
        ChatHelper.success(src, ChatHelper.formattedKey("cmd.duplicate_done", sourceId, newId, d.index));
        return 1;
    }

    private static int cmdDress(ServerCommandSource src, String rawId, String rawOverlay) {
        String id = sanitize(rawId);
        if (!SlotManager.hasId(id)) { src.sendMessage(notFound(id)); return 0; }

        String overlay = ColorVariantService.normalizeDressOverlay(rawOverlay);
        if (!ColorVariantService.isValidDressOverlay(overlay)) {
            ChatHelper.error(src, "Unknown overlay. Use: cracked, mossy, weathered, glowing, frosted.");
            return 0;
        }

        SlotData source = SlotManager.getById(id);
        if (source == null) { src.sendMessage(notFound(id)); return 0; }

        String standardId = id + "_dressed_" + overlay;
        SlotData existing = SlotManager.getById(standardId);
        ServerPlayerEntity player = src.getPlayer();
        if (existing != null) {
            SlotBlock.SlotItem item = CustomBlocksMod.safeSlotItem(existing.index);
            if (player != null && item != null) player.getInventory().insertStack(new ItemStack(item));
            ChatHelper.info(src, "Dressed variant already exists as §f" + existing.customId + "§7. Gave you a copy.");
            return 1;
        }

        if (SlotManager.freeSlots() == 0) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.all_slots_full", CustomBlocksConfig.maxSlots));
            return 0;
        }

        String targetId = ColorVariantService.suggestDressedId(id, overlay);
        String targetName = ColorVariantService.dressedDisplayName(source.displayName, overlay);
        MinecraftServer server = src.getServer();
        java.util.UUID actorId = getPlayerUuid(src);
        String actorName = getPlayerName(src);
        ChatHelper.info(src, "Applying §f" + overlay + "§7 dressing to §f" + source.displayName + "§7...");
        thread(() -> {
            try {
                ColorVariantService.PreparedVariant prepared = ColorVariantService.prepareDressedVariant(source, overlay);
                server.execute(() -> {
                    ColorVariantService.CreationResult result = ColorVariantService.createPreparedVariant(
                        server,
                        source,
                        targetId,
                        targetName,
                        prepared,
                        actorId,
                        actorName,
                        player,
                        "dressed",
                        source.customId + " / " + overlay);
                    if (result == null || result.slotData() == null) {
                        ChatHelper.error(src, ChatHelper.formattedKey("cmd.no_free_slots_short"));
                        return;
                    }
                    if (result.created()) {
                        if (player != null) {
                            player.getServerWorld().spawnParticles(net.minecraft.particle.ParticleTypes.WAX_ON,
                                player.getX(), player.getY() + 1.0, player.getZ(),
                                14, 0.35, 0.35, 0.35, 0.02);
                            player.playSound(net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.05f);
                        }
                        ChatHelper.success(src, "Created dressed variant §f" + result.slotData().displayName + "§a as §f" + result.targetId() + "§a in slot §f#" + result.slotData().index);
                    } else {
                        ChatHelper.info(src, "Dressed variant already exists as §f" + result.targetId() + "§7. Gave you a copy.");
                    }
                });
            } catch (Exception e) {
                server.execute(() -> ChatHelper.error(src, "Dress failed: §f" + e.getMessage()));
            }
        });
        return 1;
    }

    private static int cmdGradient(ServerCommandSource src, String rawFromId, String rawToId, int steps, String rawMode) {
        String fromId = sanitize(rawFromId);
        String toId = sanitize(rawToId);
        if (!SlotManager.hasId(fromId)) { src.sendMessage(notFound(fromId)); return 0; }
        if (!SlotManager.hasId(toId)) { src.sendMessage(notFound(toId)); return 0; }
        if (fromId.equals(toId)) {
            ChatHelper.error(src, "Gradient endpoints must be different blocks.");
            return 0;
        }

        String mode = normalizeGradientMode(rawMode);
        if (!mode.equals("--preview") && !mode.equals("--apply")) {
            ChatHelper.error(src, "Unknown gradient mode. Use --preview or --apply.");
            return 0;
        }

        SlotData from = SlotManager.getById(fromId);
        SlotData to = SlotManager.getById(toId);
        if (from == null || to == null) {
            ChatHelper.error(src, "Could not resolve the selected gradient endpoints.");
            return 0;
        }

        try {
            String fromHex = ColorVariantService.extractRepresentativeColorHex(from);
            String toHex = ColorVariantService.extractRepresentativeColorHex(to);
            java.util.List<String> colors = ColorVariantService.gradientInteriorColors(fromHex, toHex, steps);
            if (colors.isEmpty()) {
                ChatHelper.warn(src, "No gradient steps were generated.");
                return 0;
            }

            if (mode.equals("--preview")) {
                int existing = 0;
                java.util.List<String> sample = new java.util.ArrayList<>();
                for (int i = 0; i < colors.size(); i++) {
                    String targetId = ColorVariantService.gradientVariantId(from.customId, i + 1, colors.get(i));
                    if (SlotManager.hasId(targetId)) existing++;
                    if (sample.size() < 6) sample.add(targetId + "=" + colors.get(i));
                }
                ChatHelper.info(src, "Gradient preview: §f" + colors.size() + "§7 intermediate variants from §f" + from.customId + "§7 to §f" + to.customId + "§7.");
                ChatHelper.info(src, "Endpoint colors: §f" + fromHex + " §7→ §f" + toHex);
                if (!sample.isEmpty()) {
                    ChatHelper.info(src, "Sample: §f" + String.join("§7, §f", sample));
                }
                if (existing > 0) {
                    ChatHelper.warn(src, "§f" + existing + "§e gradient variants already exist and will be reused/skipped on apply.");
                }
                ChatHelper.warn(src, "Run §f/cb gradient " + from.customId + " " + to.customId + " " + steps + " --apply §eto create them.");
                return 1;
            }

            java.util.List<String> createdOrExistingIds = new java.util.ArrayList<>();
            int neededSlots = 0;
            for (int i = 0; i < colors.size(); i++) {
                String targetId = ColorVariantService.gradientVariantId(from.customId, i + 1, colors.get(i));
                if (!SlotManager.hasId(targetId)) neededSlots++;
            }
            if (neededSlots > SlotManager.freeSlots()) {
                ChatHelper.error(src, "Gradient needs §f" + neededSlots + "§c free slots, but only §f" + SlotManager.freeSlots() + "§c remain.");
                return 0;
            }

            MinecraftServer server = src.getServer();
            java.util.UUID actorId = getPlayerUuid(src);
            String actorName = getPlayerName(src);
            String categoryKey = ensureGradientCategory(from, to, colors);
            int created = 0;
            int existed = 0;
            java.util.List<String> failed = new java.util.ArrayList<>();
            for (int i = 0; i < colors.size(); i++) {
                String hex = colors.get(i);
                String targetId = ColorVariantService.gradientVariantId(from.customId, i + 1, hex);
                if (SlotManager.hasId(targetId)) {
                    existed++;
                    createdOrExistingIds.add(targetId);
                    continue;
                }
                try {
                    ColorVariantService.PreparedVariant prepared = ColorVariantService.prepareRecoloredVariant(from, hex);
                    String targetName = ColorVariantService.gradientDisplayName(from.displayName, i + 1, colors.size(), hex);
                    ColorVariantService.CreationResult result = ColorVariantService.createPreparedVariant(
                        server,
                        from,
                        targetId,
                        targetName,
                        prepared,
                        actorId,
                        actorName,
                        null,
                        "gradient",
                        from.customId + " -> " + to.customId + " / " + hex);
                    if (result != null && result.slotData() != null) {
                        if (result.created()) created++;
                        else existed++;
                        createdOrExistingIds.add(result.targetId());
                    } else {
                        failed.add(targetId + " (no free slot)");
                    }
                } catch (Exception ex) {
                    failed.add(targetId + " (" + ex.getMessage() + ")");
                }
            }
            if (!createdOrExistingIds.isEmpty()) {
                com.customblocks.core.CategoryManager.assignBlocksBulk(createdOrExistingIds, categoryKey);
            }
            ServerPlayerEntity player = src.getPlayer();
            if (player != null && created > 0) {
                player.getServerWorld().spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD,
                    player.getX(), player.getY() + 1.0, player.getZ(),
                    18, 0.4, 0.35, 0.4, 0.03);
                player.playSound(net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.05f);
            }
            ChatHelper.success(src, "Gradient apply complete. Created §f" + created + "§a, reused §f" + existed + "§a, failed §f" + failed.size() + "§a. Category: §f" + categoryKey);
            if (!failed.isEmpty()) {
                int preview = Math.min(4, failed.size());
                ChatHelper.warn(src, "Failures: §f" + String.join("§7, §f", failed.subList(0, preview))
                    + (failed.size() > preview ? " §7... +" + (failed.size() - preview) + " more" : ""));
            }
            return created > 0 || existed > 0 ? 1 : 0;
        } catch (Exception e) {
            ChatHelper.error(src, "Gradient failed: §f" + e.getMessage());
            return 0;
        }
    }

    private static int cmdGradientText(ServerCommandSource src, String fromId, String toId, String stepsAndModeText) {
        if (stepsAndModeText == null || stepsAndModeText.trim().isEmpty()) {
            return usage(src, "gradient");
        }
        String[] parts = stepsAndModeText.trim().split("\\s+", 2);
        int steps;
        try {
            steps = Integer.parseInt(parts[0]);
        } catch (NumberFormatException ex) {
            ChatHelper.error(src, "Invalid gradient steps '" + parts[0] + "'. Use 1-32.");
            return 0;
        }
        String mode = parts.length > 1 ? parts[1].trim() : "--preview";
        return cmdGradient(src, fromId, toId, steps, mode);
    }

    private static String normalizeGradientMode(String rawMode) {
        if (rawMode == null || rawMode.isBlank()) return "--preview";
        String mode = rawMode.trim().toLowerCase(java.util.Locale.ROOT);
        if (mode.equals("preview")) return "--preview";
        if (mode.equals("apply")) return "--apply";
        return mode;
    }

    private static String ensureGradientCategory(SlotData from, SlotData to, java.util.List<String> colors) {
        String displayName = from.displayName + " Gradient to " + to.displayName;
        com.customblocks.core.Category candidate = com.customblocks.core.Category.create(displayName);
        if (colors != null && !colors.isEmpty()) {
            candidate = candidate.withColor(colors.get(colors.size() / 2));
        }
        com.customblocks.core.Category existing = com.customblocks.core.CategoryManager.getCategory(candidate.key());
        if (existing == null) {
            com.customblocks.core.CategoryManager.addCategory(candidate);
            return candidate.key();
        }
        return existing.key();
    }

    private static int cmdDelete(ServerCommandSource src, String rawId) {
        String id = sanitize(rawId);
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        if (com.customblocks.core.LockManager.isLocked(id)) {
            ChatHelper.error(src, "§f" + id + " §cis locked. Run §f/cb unlock " + id + " §cfirst.");
            return 0;
        }
        SlotData d = SlotManager.getById(id);
        UndoManager.pushUndoDeletion(id, d.deepCopy(), getPlayerUuid(src));
        SlotManager.remove(id);
        SlotManager.saveAll();
        NetworkManager.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("remove", d.index, id, null, null, 0, 0, "stone"));
        com.customblocks.core.HistoryTracker.record(getPlayerUuid(src), getPlayerName(src), "deleted", id);
        com.customblocks.DiscordWebhook.post(
            "\uD83D\uDFE5 **Block Deleted** by `" + getPlayerName(src) + "`\n" +
            "ID: `" + id + "` · Slot #" + d.index);
        ServerPlayerEntity p = src.getPlayer();
        if (p != null) {
            com.customblocks.gui.ChatHelper.clickableUndo(p, id);
        } else {
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.block_deleted", id));
        }
        // P1 — macro recording hook
        java.util.UUID _delUuid = getPlayerUuid(src);
        if (_delUuid != null) com.customblocks.core.MacroManager.record(_delUuid, "delete " + id);
        return 1;
    }

    private static int cmdBulkDelete(ServerCommandSource src, String idsRaw) {
        com.customblocks.core.SnapshotManager.takeSnapshot("pre_op_bulk_delete");
        String[] rawIds = idsRaw.trim().split("\\s+");
        List<String> deleted = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        for (String rawId : rawIds) {
            String id = sanitize(rawId);
            if (!SlotManager.hasId(id)) { notFound.add(id); continue; }
            SlotData d = SlotManager.getById(id);
            UndoManager.pushUndoDeletion(id, d.deepCopy(), getPlayerUuid(src));
            SlotManager.remove(id);
            NetworkManager.broadcastUpdate(src.getServer(),
                new SlotUpdatePayload("remove", d.index, id, null, null, 0, 0, "stone"));
            deleted.add(id);
        }
        if (!deleted.isEmpty()) {
            SlotManager.saveAll();
            ChatHelper.info(src, ChatHelper.formattedKey("cmd.bulk_delete_deleted", String.join(", ", deleted)));
        }
        if (!notFound.isEmpty()) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.bulk_delete_not_found", String.join(", ", notFound)));
        }
        return deleted.isEmpty() ? 0 : 1;
    }

    private static int cmdExportBlock(ServerCommandSource src, String id) {
        SlotData d = SlotManager.getById(id.toLowerCase());
        if (d == null) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.block_not_found"));
            return 0;
        }
        try {
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            obj.addProperty("customId", d.customId);
            obj.addProperty("displayName", d.displayName);
            obj.addProperty("light", d.lightLevel);
            obj.addProperty("hard", d.hardness);
            obj.addProperty("sound", d.soundType);
            if (d.animMeta != null) obj.addProperty("anim", d.animMeta);
            if (d.noCollision) obj.addProperty("ncol", true);
            if (d.isShaped()) {
                com.google.gson.JsonArray boxes = new com.google.gson.JsonArray();
                for (SlotData.ShapeBox box : d.shapeBoxes) boxes.add(box.toSerialString());
                obj.add("shape", boxes);
            }
            if (d.texture != null) obj.addProperty("tex", java.util.Base64.getEncoder().encodeToString(d.texture));
            if (d.hasFaces()) {
                com.google.gson.JsonObject faces = new com.google.gson.JsonObject();
                for (var fe : d.faceTextures.entrySet())
                    faces.addProperty(fe.getKey(), java.util.Base64.getEncoder().encodeToString(fe.getValue()));
                obj.add("faces", faces);
            }
            String jsonStr = obj.toString();

            // Generate share code using mixed alphabet
            String hash = generateShareCode(jsonStr);

            java.nio.file.Path exportDir = java.nio.file.Path.of("config/customblocks/exports");
            java.nio.file.Files.createDirectories(exportDir);
            java.nio.file.Files.writeString(exportDir.resolve(hash + ".json"), jsonStr, java.nio.charset.StandardCharsets.UTF_8);
            GuiManager.uploadShareToCloud(hash, jsonStr);

            String code = "CB~" + hash;
            // Single branded, clickable message — no duplicate print.
            net.minecraft.text.MutableText clickable = Text.literal(ChatHelper.formattedKey("cmd.ui_cyan_underline", code))
                .styled(s -> s
                    .withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                    .withHoverEvent(new net.minecraft.text.HoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT, Text.literal(ChatHelper.formattedKey("cmd.click_copy_hover")))));
            net.minecraft.text.MutableText line = ChatHelper.rawPrefixed(ChatHelper.formattedKey("cmd.share_line_prefix", d.customId))
                .append(clickable);
            src.sendMessage(line);
            return 1;
        } catch (Exception e) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.export_failed", e.getMessage()));
            return 0;
        }
    }

    private static int cmdImportBlock(ServerCommandSource src, String code) {
        if (!code.startsWith("CB!") && !code.startsWith("CB2!")
                && !code.startsWith("CB3!") && !code.startsWith("CB~")) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.invalid_share_code_format"));
            return 0;
        }
        try {
            if (code.startsWith("CB~") || code.startsWith("CB3!")) {
                String hash = code.startsWith("CB~") ? code.substring(3).trim() : code.substring(4).trim();
                java.nio.file.Path exportFile = java.nio.file.Path.of("config/customblocks/exports", hash + ".json");
                if (java.nio.file.Files.exists(exportFile)) {
                    return importDecodedBlock(src,
                        java.nio.file.Files.readString(exportFile, java.nio.charset.StandardCharsets.UTF_8),
                        false);
                }
                if (CustomBlocksConfig.isCloudShareEnabled()) {
                    MinecraftServer server = src.getServer();
                    ServerPlayerEntity player = src.getPlayer();
                    if (player != null) {
                        player.sendMessage(ChatHelper.rawPrefixed(ChatHelper.formattedKey("cmd.cloud_checking")), false);
                    }
                    EXECUTOR.submit(() -> {
                        try {
                            String json = fetchCloudShareJson(hash);
                            if (json == null || json.isBlank()) {
                                server.execute(() -> {
                                    if (player != null) playCloudImportFailure(player);
                                    src.sendError(ChatHelper.rawPrefixed(ChatHelper.formattedKey("cmd.cloud_not_found")));
                                });
                                return;
                            }
                            cacheCloudShare(hash, json);
                            server.execute(() -> importDecodedBlock(src, json, true));
                        } catch (Exception e) {
                            server.execute(() -> {
                                if (player != null) playCloudImportFailure(player);
                                src.sendError(ChatHelper.rawPrefixed(ChatHelper.formattedKey("cmd.cloud_import_failed", e.getMessage())));
                            });
                        }
                    });
                    return 1;
                }
                ChatHelper.error(src, ChatHelper.formattedKey("cmd.export_not_found", code));
                return 0;
            }

            return importDecodedBlock(src, decodeInlineImportCode(code), false);
        } catch (Exception e) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.decode_error", e.getMessage()));
            return 0;
        }
    }

    private static String decodeInlineImportCode(String code) throws Exception {
        if (code.startsWith("CB2!")) {
            byte[] compressed = java.util.Base64.getDecoder().decode(code.substring(4));
            try (java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed));
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = gz.read(buf)) != -1) out.write(buf, 0, n);
                return out.toString(java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return new String(java.util.Base64.getDecoder().decode(code.substring(3)), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int importDecodedBlock(ServerCommandSource src, String json, boolean fromCloud) {
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();

            String id = obj.get("customId").getAsString();
            String name = obj.has("displayName") ? obj.get("displayName").getAsString() : id;
            if (SlotManager.hasId(id)) id = id + "_imp";

            byte[] texture = null;
            if (obj.has("tex")) texture = java.util.Base64.getDecoder().decode(obj.get("tex").getAsString());

            SlotData d = SlotManager.assign(id, name, texture);
            if (d == null) {
                ChatHelper.error(src, ChatHelper.formattedKey("cmd.import_no_slots"));
                return 0;
            }

            int light = obj.has("light") ? obj.get("light").getAsInt() : 0;
            float hard = obj.has("hard") ? obj.get("hard").getAsFloat() : 1.5f;
            String sound = obj.has("sound") ? obj.get("sound").getAsString() : "stone";
            SlotManager.setProperties(id, light, hard, sound);
            if (obj.has("anim")) SlotManager.setAnimMeta(id, obj.get("anim").getAsString());
            if (obj.has("ncol") && obj.get("ncol").getAsBoolean()) SlotManager.setCollision(id, false);

            if (obj.has("shape")) {
                java.util.List<SlotData.ShapeBox> shapeBoxes = new java.util.ArrayList<>();
                for (com.google.gson.JsonElement el : obj.getAsJsonArray("shape")) {
                    try { shapeBoxes.add(SlotData.ShapeBox.parse(el.getAsString())); } catch (Exception ignored) {}
                }
                if (!shapeBoxes.isEmpty()) SlotManager.setShape(id, shapeBoxes);
            }

            if (obj.has("faces")) {
                com.google.gson.JsonObject faces = obj.getAsJsonObject("faces");
                for (var entry : faces.entrySet())
                    SlotManager.setFaceTexture(id, entry.getKey(), java.util.Base64.getDecoder().decode(entry.getValue().getAsString()));
            }

            SlotData finalData = SlotManager.getById(id);
            SlotManager.saveAll();
            String animMeta = obj.has("anim") ? obj.get("anim").getAsString() : null;
            NetworkManager.broadcastUpdate(src.getServer(), new SlotUpdatePayload(
                    "add", finalData.index, finalData.customId, finalData.displayName,
                    texture, finalData.lightLevel, finalData.hardness, finalData.soundType, null, null, animMeta));
            for (var faceEntry : finalData.faceTextures.entrySet()) {
                NetworkManager.broadcastUpdate(src.getServer(), new SlotUpdatePayload(
                    "setface", finalData.index, finalData.customId, null, faceEntry.getValue(),
                    finalData.lightLevel, finalData.hardness, finalData.soundType, faceEntry.getKey(), null, animMeta));
            }
            if (finalData.isShaped()) {
                broadcastShape(src.getServer(), finalData);
            }
            if (finalData.noCollision) {
                NetworkManager.broadcastUpdate(src.getServer(), new SlotUpdatePayload(
                    "setcollision", finalData.index, finalData.customId, null, null,
                    0, 0, "stone", null, "false"));
            }

            ServerPlayerEntity player = src.getPlayer();
            if (fromCloud && player != null) {
                playCloudImportSuccess(player);
                player.sendMessage(ChatHelper.rawPrefixed(ChatHelper.formattedKey("cmd.import_cloud_success")), false);
            }

            if (texture != null) ChatHelper.success(src, ChatHelper.formattedKey("cmd.imported_with_texture", id));
            else ChatHelper.success(src, ChatHelper.formattedKey("cmd.imported_need_retexture", id));
            return 1;
        } catch (Exception e) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.decode_error", e.getMessage()));
            return 0;
        }
    }

    private static String fetchCloudShareJson(String hash) throws Exception {
        String baseUrl = CustomBlocksConfig.normalizedCloudShareUrl();
        if (baseUrl.isBlank()) return null;

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(baseUrl + "/share/" + java.net.URLEncoder.encode(hash, java.nio.charset.StandardCharsets.UTF_8)))
            .timeout(java.time.Duration.ofSeconds(5))
            .GET()
            .build();
        java.net.http.HttpResponse<String> response = HTTP.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return null;
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new java.io.IOException("Cloud Vault returned status " + response.statusCode());
        }
        return normalizeCloudResponse(response.body());
    }

    private static String normalizeCloudResponse(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            com.google.gson.JsonElement element = com.google.gson.JsonParser.parseString(body);
            if (element.isJsonObject()) {
                com.google.gson.JsonObject obj = element.getAsJsonObject();
                if (obj.has("customId")) return obj.toString();
                if (obj.has("data") && obj.get("data").isJsonObject()) return obj.get("data").getAsJsonObject().toString();
                if (obj.has("block") && obj.get("block").isJsonObject()) return obj.get("block").getAsJsonObject().toString();
                if (obj.has("json") && obj.get("json").isJsonPrimitive()) return obj.get("json").getAsString();
            } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                return element.getAsString();
            }
        } catch (Exception ignored) {}
        return body;
    }

    private static void cacheCloudShare(String hash, String json) throws java.io.IOException {
        java.nio.file.Path exportDir = java.nio.file.Path.of("config/customblocks/exports");
        java.nio.file.Files.createDirectories(exportDir);
        java.nio.file.Files.writeString(exportDir.resolve(hash + ".json"), json, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void playCloudImportSuccess(ServerPlayerEntity player) {
        player.getServerWorld().spawnParticles(net.minecraft.particle.ParticleTypes.COMPOSTER,
            player.getX(), player.getY() + 1.0, player.getZ(),
            18, 0.35, 0.35, 0.35, 0.02);
        player.playSound(net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.0f);
    }

    private static void playCloudImportFailure(ServerPlayerEntity player) {
        player.getServerWorld().spawnParticles(net.minecraft.particle.ParticleTypes.SMOKE,
            player.getX(), player.getY() + 1.0, player.getZ(),
            12, 0.25, 0.35, 0.25, 0.01);
        player.playSound(net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 1.0f, 0.8f);
    }

    private static int cmdRename(ServerCommandSource src, String rawId, String newName) {
        String id = sanitize(rawId);
        if (!SlotManager.hasId(id)) { src.sendMessage(notFound(id)); return 0; }
        SlotData d = SlotManager.getById(id);
        UndoManager.pushUndoMutation(id, d, "rename", getPlayerUuid(src));
        SlotManager.rename(id, newName);
        SlotManager.saveAll();
        NetworkManager.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("rename", d.index, id, newName, null, 0, 0, "stone"));
        ChatHelper.success(src, ChatHelper.formattedKey("cmd.rename_done", newName));
        // P1 — macro recording hook
        java.util.UUID _renUuid = getPlayerUuid(src);
        if (_renUuid != null) com.customblocks.core.MacroManager.record(_renUuid, "rename " + id + " " + newName);
        return 1;
    }

    private static int cmdReId(ServerCommandSource src, String rawOldId, String rawNewId) {
        String oldId = sanitize(rawOldId);
        String newId = sanitize(rawNewId);
        if (!SlotManager.hasId(oldId)) { src.sendMessage(notFound(oldId)); return 0; }
        if (newId.isEmpty()) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.new_id_empty")); return 0; }
        if (SlotManager.hasId(newId)) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.new_id_taken", newId));
            return 0;
        }
        SlotData d = SlotManager.getById(oldId);
        UndoManager.pushUndoMutation(oldId, d, "reid", getPlayerUuid(src));
        SlotManager.reId(oldId, newId);
        SlotManager.saveAll();
        // Broadcast a full re-add with the new ID so clients update their mapping
        SlotData updated = SlotManager.getById(newId);
        NetworkManager.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("remove", d.index, oldId, null, null, 0, 0, "stone"));
        NetworkManager.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("add", updated.index, newId, updated.displayName, updated.texture,
                updated.lightLevel, updated.hardness, updated.soundType, null, null, updated.animMeta));
        ChatHelper.success(src, ChatHelper.formattedKey("cmd.reid_done", oldId, newId));
        return 1;
    }

    // ── Shape commands ────────────────────────────────────────────────────────

    private static String serializeShape(List<SlotData.ShapeBox> boxes) {
        if (boxes == null || boxes.isEmpty()) return "full";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < boxes.size(); i++) {
            if (i > 0) sb.append(";");
            sb.append(boxes.get(i).toSerialString());
        }
        return sb.toString();
    }

    private static void broadcastShape(MinecraftServer server, SlotData d) {
        List<SlotData.ShapeBox> boxes = d.shapeBoxes;
        NetworkManager.broadcastUpdate(server, new SlotUpdatePayload(
                "setshape", d.index, d.customId, null, null, 0, 0, "stone",
                null, serializeShape(boxes)));
    }

    private static int cmdSetShape(ServerCommandSource src, String id, String shapeArg) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        List<SlotData.ShapeBox> boxes;
        String shapeArgTrimmed = shapeArg.trim();
        if (SlotManager.SHAPE_PRESETS.containsKey(shapeArgTrimmed)) {
            boxes = new ArrayList<>(SlotManager.SHAPE_PRESETS.get(shapeArgTrimmed));
        } else {
            try {
                SlotData.ShapeBox box = SlotData.ShapeBox.parse(shapeArgTrimmed);
                
                boxes = List.of(box);
            } catch (Exception e) {
                ChatHelper.error(src, ChatHelper.formattedKey("cmd.shape_bad_preset", String.join(", ", SlotManager.SHAPE_PRESETS.keySet())));
                return 0;
            }
        }
        UndoManager.pushUndoMutation(id, SlotManager.getById(id), "setshape", getPlayerUuid(src));
        SlotManager.setShape(id, boxes);
        SlotManager.saveAll();
        SlotData d = SlotManager.getById(id);
        broadcastShape(src.getServer(), d);
        ChatHelper.success(src, ChatHelper.formattedKey("cmd.shape_set", shapeArgTrimmed, id));
        return 1;
    }

    private static int cmdAddShape(ServerCommandSource src, String id, String coords) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        SlotData d = SlotManager.getById(id);
        int current = d.shapeBoxes != null ? d.shapeBoxes.size() : 0;
        if (current >= 16) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.shape_max_boxes")); return 0; }
        SlotData.ShapeBox box;
        try {
            box = SlotData.ShapeBox.parse(coords.trim());
            
        } catch (Exception e) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.shape_bad_coords")); return 0;
        }
        UndoManager.pushUndoMutation(id, SlotManager.getById(id), "addshape", getPlayerUuid(src));
        SlotManager.addBox(id, box);
        SlotManager.saveAll();
        d = SlotManager.getById(id);
        broadcastShape(src.getServer(), d);
        ChatHelper.success(src, ChatHelper.formattedKey("cmd.shape_box_added", current + 1, id, d.shapeBoxes.size()));
        return 1;
    }

    private static int cmdRemoveShape(ServerCommandSource src, String id, int index) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        UndoManager.pushUndoMutation(id, SlotManager.getById(id), "removeshape", getPlayerUuid(src));
        SlotManager.removeBox(id, index);
        SlotManager.saveAll();
        SlotData d = SlotManager.getById(id);
        broadcastShape(src.getServer(), d);
        ChatHelper.success(src, ChatHelper.formattedKey("cmd.shape_box_removed", index, id));
        return 1;
    }

    private static int cmdRemoveShapeText(ServerCommandSource src, String id, String boxIndexText) {
        try {
            int index = Integer.parseInt(boxIndexText.trim());
            if (index < 0 || index > 15) {
                ChatHelper.error(src, "Shape box index must be between 0 and 15.");
                return 0;
            }
            return cmdRemoveShape(src, id, index);
        } catch (NumberFormatException ex) {
            ChatHelper.error(src, "Invalid shape box index '" + boxIndexText + "'. Use 0-15.");
            return 0;
        }
    }

    private static int cmdClearShape(ServerCommandSource src, String id) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        UndoManager.pushUndoMutation(id, SlotManager.getById(id), "clearshape", getPlayerUuid(src));
        SlotManager.setShape(id, null);
        SlotManager.saveAll();
        SlotData d = SlotManager.getById(id);
        broadcastShape(src.getServer(), d);
        ChatHelper.success(src, ChatHelper.formattedKey("cmd.shape_reset_full", id));
        return 1;
    }

    private static int cmdSetCollision(ServerCommandSource src, String id, boolean on) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        UndoManager.pushUndoMutation(id, SlotManager.getById(id), "setcollision", getPlayerUuid(src));
        SlotManager.setCollision(id, on);
        SlotManager.saveAll();
        SlotData d = SlotManager.getById(id);
        NetworkManager.broadcastUpdate(src.getServer(), new SlotUpdatePayload(
                "setcollision", d.index, id, null, null, 0, 0, "stone", null, on ? "true" : "false"));
        ChatHelper.success(src, ChatHelper.formattedKey("cmd.collision_toggle", on ? "ON" : "OFF", id));
        return 1;
    }

    private static int cmdShapeEditor(ServerCommandSource src, String id) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        net.minecraft.server.network.ServerPlayerEntity player = src.getPlayer();
        if (player == null) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.console_player_only")); return 0; }
        com.customblocks.gui.GuiManager.openShapeEditor(player, id, 0);
        return 1;
    }

    private static int cmdFaceChangeGui(ServerCommandSource src, String id) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.console_player_only")); return 0; }
        GuiManager.openFaceChangeSelect(player, id, 0);
        return 1;
    }

    private static int cmdGiveSquare(ServerCommandSource src, String color) {
        return cmdGiveSquareInternal(src, color);
    }

    private static int cmdGiveTriangle(ServerCommandSource src, String color) {
        return cmdGiveTriangleInternal(src, color);
    }

    private static int cmdGiveRectangle(ServerCommandSource src) {
        return cmdGiveRectangleInternal(src);
    }

    private static int cmdRetexture(ServerCommandSource src, String id, String url, int size) {
        if (!SlotManager.hasId(id)) { src.sendMessage(notFound(id)); return 0; }
        ChatHelper.info(src, ChatHelper.formattedKey("cmd.downloading_texture", size));
        MinecraftServer server = src.getServer();
        thread(() -> {
            try {
                byte[] raw = ImageProcessor.download(url);
                ImageProcessor.ProcessResult anim = ImageProcessor.isAnimatedImage(raw) ? ImageProcessor.processAnimation(raw, size) : null;
                byte[] bytes;
                String animMeta = null;
                if (anim != null && anim.isAnimated()) {
                    bytes = anim.bytes();
                    animMeta = anim.mcmeta();
                } else {
                    bytes = ImageProcessor.toPng(raw);
                    bytes = ImageProcessor.padToSquare(bytes);
                    bytes = ImageProcessor.replaceBackground(bytes);
                    bytes = ImageProcessor.resizeTo(bytes, size);
                }
                final byte[] fb = bytes;
                final String fa = animMeta;
                server.execute(() -> {
                    SlotData d = SlotManager.getById(id);
        UndoManager.pushUndoMutation(id, d, "retexture", getPlayerUuid(src));
                    if (d == null) { src.sendMessage(notFound(id)); return; }
                    SlotManager.updateTexture(id, fb);
                    if (fa != null) SlotManager.setAnimMeta(id, fa);
                    SlotManager.saveAll();
                    NetworkManager.broadcastUpdate(server,
                        new SlotUpdatePayload("retexture", d.index, id, null, fb,
                                d.lightLevel, d.hardness, d.soundType, null, null, fa));
                    ChatHelper.success(src, ChatHelper.formattedKey("cmd.texture_updated", id));
                    com.customblocks.core.HistoryTracker.record(getPlayerUuid(src), getPlayerName(src), "retextured", id);
                });
            } catch (Exception e) {
                server.execute(() -> {
                    ChatHelper.error(src, ChatHelper.formattedKey("cmd.operation_failed", e.getMessage()));
                    GuiManager.logError();
                });
            }
        });
        return 1;
    }

    private static int cmdRetextureText(ServerCommandSource src, String id, String sizeText, String url) {
        try {
            int size = Integer.parseInt(sizeText.trim());
            if (size < 16 || size > 256) {
                ChatHelper.error(src, "Texture size must be between 16 and 256.");
                return 0;
            }
            return cmdRetexture(src, id, url, size);
        } catch (NumberFormatException ex) {
            ChatHelper.error(src, "Invalid texture size '" + sizeText + "'. Use 16-256.");
            return 0;
        }
    }

    private static int cmdGive(ServerCommandSource src, String id, int amount, Collection<ServerPlayerEntity> targets) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { src.sendMessage(notFound(id)); return 0; }
        SlotBlock.SlotItem item = CustomBlocksMod.safeSlotItem(d.index); 
        if (item == null) { 
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.slot_item_missing", d.index)); 
            GuiManager.logError();
            return 0; 
        }
        ItemStack stack = new ItemStack(item, Math.max(1, Math.min(64, amount)));
        if (targets == null || targets.isEmpty()) {
            try {
                ServerPlayerEntity self = src.getPlayerOrThrow();
                self.getInventory().insertStack(stack.copy());
                ChatHelper.success(src, ChatHelper.formattedKey("cmd.given_you", amount, d.displayName));
            } catch (Exception ex) { 
                ChatHelper.error(src, ChatHelper.formattedKey("cmd.give_need_player_or_target")); 
                GuiManager.logError();
            }
        } else {
            for (ServerPlayerEntity p : targets) {
                p.getInventory().insertStack(stack.copy());
                ChatHelper.success(p, ChatHelper.formattedKey("cmd.received_blocks", amount, d.displayName));
            }
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.gave_players", amount, targets.size()));
        }
        return 1;
    }

    private static int cmdGiveText(ServerCommandSource src, String id, String amountText) {
        String raw = amountText == null ? "" : amountText.trim();
        if (raw.isEmpty()) return usage(src, "give");
        try {
            int amount = Integer.parseInt(raw);
            if (amount < 1 || amount > 64) {
                ChatHelper.error(src, "Give amount must be between 1 and 64.");
                return 0;
            }
            return cmdGive(src, id, amount, null);
        } catch (NumberFormatException ex) {
            ChatHelper.error(src, "Invalid give amount '" + raw + "'. Use 1-64 or provide a valid target player.");
            return 0;
        }
    }

    /** Toggle favorite star for a block, or list favorites when {@code rawId} is null/blank. */
    private static int cmdFavorite(ServerCommandSource src, String rawId) {
        ServerPlayerEntity p = src.getPlayer();
        if (p == null) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.console_player_only"));
            return 0;
        }
        if (rawId == null || rawId.isBlank()) {
            java.util.ArrayList<String> favs = new java.util.ArrayList<>(FavoritesManager.validatedSet(p.getUuid()));
            if (favs.isEmpty()) {
                ChatHelper.info(src, ChatHelper.formattedKey("cmd.favorite_list_empty"));
                return 1;
            }
            int max = 48;
            java.util.List<String> head = favs.subList(0, Math.min(max, favs.size()));
            String joined = String.join("§7, §f", head);
            ChatHelper.info(src, ChatHelper.formattedKey("cmd.favorite_list_summary", favs.size(), joined));
            if (favs.size() > max) {
                ChatHelper.info(src, ChatHelper.formattedKey("cmd.favorite_list_more", favs.size() - max));
            }
            return 1;
        }
        String id = sanitize(rawId);
        if (!SlotManager.hasId(id)) {
            src.sendError(notFound(id));
            return 0;
        }
        boolean was = FavoritesManager.isFavorite(p.getUuid(), id);
        FavoritesManager.toggle(p.getUuid(), id, src.getServer());
        if (was) {
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.favorite_removed", id));
        } else {
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.favorite_added", id));
        }
        return 1;
    }

    private static int cmdLock(ServerCommandSource src, String rawId, boolean lock) {
        String id = sanitize(rawId);
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        ServerPlayerEntity p = src.getPlayer();
        if (lock) {
            boolean was = com.customblocks.core.LockManager.lock(id);
            if (was) {
                ChatHelper.success(src, "§f" + id + " §ais now locked. Edits and deletes are blocked until unlocked. §a🔒");
                if (p != null) com.customblocks.gui.GuiManager.playSuccess(p);
            } else {
                ChatHelper.info(src, "§f" + id + " §7is already locked.");
            }
        } else {
            boolean was = com.customblocks.core.LockManager.unlock(id);
            if (was) {
                ChatHelper.success(src, "§f" + id + " §ais now unlocked. §a🔓");
                if (p != null) com.customblocks.gui.GuiManager.playClick(p);
            } else {
                ChatHelper.info(src, "§f" + id + " §7is not locked.");
            }
        }
        return 1;
    }

    private static int cmdSetGlow(ServerCommandSource src, String id, int level) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        SlotData d = SlotManager.getById(id);
        UndoManager.pushUndoMutation(id, d, "setglow " + level, getPlayerUuid(src));
        SlotManager.setLightLevel(id, level);
        SlotManager.saveAll();
        NetworkManager.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("setprop", d.index, id, null, null, level, d.hardness, d.soundType));
        triggerGlowUpdate(src.getServer(), d.index);
        ChatHelper.success(src, ChatHelper.formattedKey("cmd.light_set", id, level));
        return 1;
    }

    /** Wrapper that validates the level range and gives a friendly message. */
    private static int cmdSetGlowSafe(ServerCommandSource src, String id, int level) {
        if (level < 0 || level > 15) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.light_bad_range", level));
            return 0;
        }
        return cmdSetGlow(src, id, level);
    }

    /** Fallback when the user types a non-integer like 'bright'. */
    private static int cmdSetGlowText(ServerCommandSource src, String id, String text) {
        try {
            int level = Integer.parseInt(text.trim());
            return cmdSetGlowSafe(src, id, level);
        } catch (NumberFormatException ex) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.light_bad_text", text));
            return 0;
        }
    }

    private static int cmdSetHardness(ServerCommandSource src, String id, float val) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        SlotData d = SlotManager.getById(id);
        UndoManager.pushUndoMutation(id, d, "sethardness", getPlayerUuid(src));
        SlotManager.setHardness(id, val);
        SlotManager.saveAll();
        String label = val < 0 ? "Unbreakable" : val == 0 ? "Instant break" : String.valueOf(val);
        NetworkManager.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("setprop", d.index, id, null, null, d.lightLevel, val, d.soundType));
        ChatHelper.success(src, ChatHelper.formattedKey("cmd.hardness_set", id, label));
        return 1;
    }

    /** Wrapper validating hardness range with branded message. */
    private static int cmdSetHardnessSafe(ServerCommandSource src, String id, float val) {
        if (val < -1f || val > 50f) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.hardness_bad_range", val));
            return 0;
        }
        return cmdSetHardness(src, id, val);
    }

    private static int cmdSetHardnessText(ServerCommandSource src, String id, String text) {
        try {
            float val = Float.parseFloat(text.trim());
            return cmdSetHardnessSafe(src, id, val);
        } catch (NumberFormatException ex) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.hardness_bad_text", text));
            return 0;
        }
    }

    private static int cmdSetSound(ServerCommandSource src, String id, String type) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        boolean ok = false;
        for (String v : VALID_SOUNDS) if (v.equals(type)) { ok = true; break; }
        if (!ok) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.sound_valid_list")); return 0; }
        SlotData d = SlotManager.getById(id);
        UndoManager.pushUndoMutation(id, d, "setsound", getPlayerUuid(src));
        SlotManager.setSoundType(id, type);
        SlotManager.saveAll();
        NetworkManager.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("setprop", d.index, id, null, null, d.lightLevel, d.hardness, type));
        ChatHelper.success(src, ChatHelper.formattedKey("cmd.sound_set", id, type));
        return 1;
    }

    private static int cmdSetTabIcon(ServerCommandSource src, String url) {
        ChatHelper.info(src, ChatHelper.formattedKey("cmd.downloading_tab_icon"));
        MinecraftServer server = src.getServer();
        thread(() -> {
            try {
                byte[] bytes = ImageProcessor.downloadAndProcess(url).bytes();
                server.execute(() -> {
                    SlotManager.setTabIconTexture(bytes);
                    if (!SlotManager.hasId("tab_icon")) SlotManager.assign("tab_icon", "Tab Icon", bytes);
                    else SlotManager.updateTexture("tab_icon", bytes);
                    SlotManager.saveAll();
                    SlotData d = SlotManager.getById("tab_icon");
                    if (d != null)
                        NetworkManager.broadcastUpdate(server,
                            new SlotUpdatePayload("add", d.index, "tab_icon", "Tab Icon", bytes, 0, 1.5f, "stone"));
                    // Send tabicon payload — clients receive texture and schedule reload
                    NetworkManager.broadcastUpdate(server,
                        new SlotUpdatePayload("tabicon", -1, null, null, bytes, 0, 0, "stone"));
                    ChatHelper.success(src, ChatHelper.formattedKey("cmd.tab_icon_updated"));
                });
            } catch (Exception e) {
                server.execute(() -> ChatHelper.error(src, ChatHelper.formattedKey("cmd.operation_failed", e.getMessage())));
            }
        });
        return 1;
    }

    /**
     * Set a single face texture.
     * ONLY that face changes — all other faces are untouched.
     */
    private static int cmdSetFace(ServerCommandSource src, String id, String face, String url, int size) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        ChatHelper.info(src, ChatHelper.formattedKey("cmd.face_downloading", face, size));
        MinecraftServer server = src.getServer();
        thread(() -> {
            try {
                // Route through the unified animation pipeline so GIF face textures
                // receive proper disposal handling + animMeta. Fixes server crash
                // when setting a GIF as a face texture.
                final ImageProcessor.ProcessResult result = ImageProcessor.downloadAndProcess(url, size);
                if (result == null || result.bytes() == null || result.bytes().length == 0) {
                    server.execute(() -> ChatHelper.error(src, ChatHelper.formattedKey("cmd.image_empty")));
                    return;
                }
                server.execute(() -> {
                    SlotData d = SlotManager.getById(id);
                    if (d == null) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.block_deleted_mid_op", id)); return; }
                    UndoManager.pushUndoMutation(id, d, "setface " + face, getPlayerUuid(src));
                    SlotManager.setFaceTexture(id, face, result.bytes());
                    // Propagate animation metadata if the face is an animated image.
                    // Without this the GIF frames render stacked (no .mcmeta on client).
                    if (result.isAnimated() && result.mcmeta() != null) {
                        SlotManager.setAnimMeta(id, result.mcmeta());
                    }
                    SlotManager.saveAll();
                    // Broadcast setface — clients apply it to ONLY this face
                    NetworkManager.broadcastUpdate(server,
                        new SlotUpdatePayload("setface", d.index, id, null, result.bytes(),
                                d.lightLevel, d.hardness, d.soundType, face,
                                null, result.isAnimated() ? result.mcmeta() : null));
                    String suffix = result.isAnimated() ? " §8(animated, " + result.frameCount() + " frames)" : "";
                    ChatHelper.success(src, ChatHelper.formattedKey("cmd.face_set_line", face.toUpperCase(), id, suffix));
                });
            } catch (Exception e) {
                server.execute(() -> ChatHelper.error(src, ChatHelper.formattedKey("cmd.operation_failed", e.getMessage())));
            }
        });
        return 1;
    }

    private static int cmdSetFaceText(ServerCommandSource src, String id, String face, String sizeText, String url) {
        try {
            int size = Integer.parseInt(sizeText.trim());
            if (size < 16 || size > 256) {
                ChatHelper.error(src, "Face texture size must be between 16 and 256.");
                return 0;
            }
            return cmdSetFace(src, id, face, url, size);
        } catch (NumberFormatException ex) {
            ChatHelper.error(src, "Invalid face texture size '" + sizeText + "'. Use 16-256.");
            return 0;
        }
    }

    private static int cmdClearFace(ServerCommandSource src, String id, String face) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        if (!SlotData.FACE_KEYS.contains(face)) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.valid_faces")); return 0; }
        SlotData d = SlotManager.getById(id);
        UndoManager.pushUndoMutation(id, d, "clearface " + face, getPlayerUuid(src));
        if (d == null) { src.sendError(notFound(id)); return 0; }
        SlotManager.clearFaceTexture(id, face);
        SlotManager.saveAll();
        // Broadcast clearface so clients revert that face to default
        NetworkManager.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("clearface", d.index, id, null, null,
                    d.lightLevel, d.hardness, d.soundType, face));
        ChatHelper.success(src, ChatHelper.formattedKey("cmd.face_cleared", face, id));
        return 1;
    }

    private static int cmdClearAllFaces(ServerCommandSource src, String id) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        SlotData d = SlotManager.getById(id);
        UndoManager.pushUndoMutation(id, d, "clearallfaces", getPlayerUuid(src));
        if (d == null) { src.sendError(notFound(id)); return 0; }
        SlotManager.clearAllFaces(id);
        SlotManager.saveAll();
        NetworkManager.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("clearfaces", d.index, id, null, null,
                    d.lightLevel, d.hardness, d.soundType));
        ChatHelper.success(src, ChatHelper.formattedKey("cmd.all_faces_cleared", id));
        return 1;
    }

    /** Undo the last block modification (retexture, setface, setglow, delete, create, …). */
    private static int cmdUndo(ServerCommandSource src) {
        if (UndoManager.undoSize(getPlayerUuid(src)) == 0) {
            ChatHelper.info(src, ChatHelper.formattedKey("cmd.undo_nothing"));
            return 1;
        }
        UndoManager.UndoEntry entry = UndoManager.popUndo(getPlayerUuid(src));
        if (entry == null) { ChatHelper.info(src, ChatHelper.formattedKey("cmd.undo_nothing")); return 1; }

        MinecraftServer server = src.getServer();

        // ── Undo a creation → delete the block ──────────────────────────────
        if (entry.previousState() == null) {
            SlotData d = SlotManager.getById(entry.customId());
            if (d == null) {
                ChatHelper.error(src, ChatHelper.formattedKey("cmd.undo_create_gone", entry.customId()));
                return 0;
            }
            int idx = d.index;
            // Push to redo before removing
            UndoManager.UndoEntry redoEntry = new UndoManager.UndoEntry(entry.customId(), snapshotForCmd(d), "create", true);
            UndoManager.pushRedo(redoEntry);
            SlotManager.remove(entry.customId());
            SlotManager.saveAll();
            NetworkManager.broadcastUpdate(server,
                new SlotUpdatePayload("remove", idx, entry.customId(), null, null, 0, 0, "stone"));
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.undo_create_done", entry.customId(),
                UndoManager.undoSize(getPlayerUuid(src))));
            if (UndoManager.undoSize(getPlayerUuid(src)) > 0)
                ChatHelper.info(src, ChatHelper.formattedKey("cmd.undo_next_hint", UndoManager.peekUndoDescription(getPlayerUuid(src))));
            return 1;
        }

        // ── Undo a mutation or a deletion ────────────────────────────────────
        SlotData prev = entry.previousState();
        // Save current state for redo
        SlotData curForRedo = SlotManager.getById(prev.customId);
        if (curForRedo != null) {
            UndoManager.UndoEntry redoEntry = new UndoManager.UndoEntry(entry.customId(), snapshotForCmd(curForRedo), entry.description(), entry.wasDeleted());
            UndoManager.pushRedo(redoEntry);
        }
        boolean restored = SlotManager.restoreSnapshot(prev, entry.wasDeleted());
        if (!restored) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.undo_slot_busy", entry.customId()));
            return 0;
        }
        SlotManager.saveAll();

        SlotData d = SlotManager.getById(prev.customId);
        if (d != null) {
            if (entry.wasDeleted()) {
                NetworkManager.broadcastUpdate(server,
                    new SlotUpdatePayload("add", d.index, d.customId, d.displayName, d.texture,
                            d.lightLevel, d.hardness, d.soundType, null, null, d.animMeta));
                for (var fe : d.faceTextures.entrySet())
                    NetworkManager.broadcastUpdate(server,
                        new SlotUpdatePayload("setface", d.index, d.customId, null, fe.getValue(),
                                d.lightLevel, d.hardness, d.soundType, fe.getKey()));
            } else {
                if (d.texture != null)
                    NetworkManager.broadcastUpdate(server,
                        new SlotUpdatePayload("retexture", d.index, d.customId, null, d.texture,
                                d.lightLevel, d.hardness, d.soundType));
                NetworkManager.broadcastUpdate(server,
                    new SlotUpdatePayload("clearfaces", d.index, d.customId, null, null,
                            d.lightLevel, d.hardness, d.soundType));
                for (var fe : d.faceTextures.entrySet())
                    NetworkManager.broadcastUpdate(server,
                        new SlotUpdatePayload("setface", d.index, d.customId, null, fe.getValue(),
                                d.lightLevel, d.hardness, d.soundType, fe.getKey()));
                NetworkManager.broadcastUpdate(server,
                    new SlotUpdatePayload("setprop", d.index, d.customId, null, null,
                            d.lightLevel, d.hardness, d.soundType));
                NetworkManager.broadcastUpdate(server,
                    new SlotUpdatePayload("rename", d.index, d.customId, d.displayName, null, 0, 0, "stone"));
            }
        }
        ChatHelper.success(src, ChatHelper.formattedKey("cmd.undo_mutation_done", entry.description(), entry.customId(),
            UndoManager.undoSize(getPlayerUuid(src)), UndoManager.redoSize(getPlayerUuid(src))));
        if (UndoManager.undoSize(getPlayerUuid(src)) > 0)
            ChatHelper.info(src, ChatHelper.formattedKey("cmd.undo_next_hint", UndoManager.peekUndoDescription(getPlayerUuid(src))));
        return 1;
    }

    /** Redo the last undone action. */
    private static int cmdRedo(ServerCommandSource src) {
        if (UndoManager.redoSize(getPlayerUuid(src)) == 0) {
            ChatHelper.info(src, ChatHelper.formattedKey("cmd.redo_nothing"));
            return 1;
        }
        UndoManager.UndoEntry entry = UndoManager.popRedo(getPlayerUuid(src));
        if (entry == null) { ChatHelper.info(src, ChatHelper.formattedKey("cmd.redo_nothing")); return 1; }

        MinecraftServer server = src.getServer();

        if (entry.previousState() == null) {
            // Redo deletion
            SlotData d = SlotManager.getById(entry.customId());
            if (d != null) {
                UndoManager.UndoEntry undoEntry = new UndoManager.UndoEntry(entry.customId(), snapshotForCmd(d), "delete", true);
                UndoManager.pushUndoForRedo(undoEntry);
                SlotManager.remove(entry.customId());
                SlotManager.saveAll();
                NetworkManager.broadcastUpdate(server,
                    new SlotUpdatePayload("remove", d.index, entry.customId(), null, null, 0, 0, "stone"));
            }
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.redo_delete_done", entry.customId(),
                UndoManager.redoSize(getPlayerUuid(src))));
            if (UndoManager.redoSize(getPlayerUuid(src)) > 0)
                ChatHelper.info(src, ChatHelper.formattedKey("cmd.redo_next_hint", UndoManager.peekRedoDescription(getPlayerUuid(src))));
            return 1;
        }

        SlotData prev = entry.previousState();
        SlotData curForUndo = SlotManager.getById(prev.customId);
        if (curForUndo != null) {
            UndoManager.UndoEntry undoEntry = new UndoManager.UndoEntry(entry.customId(), snapshotForCmd(curForUndo), entry.description(), entry.wasDeleted());
            UndoManager.pushUndoForRedo(undoEntry);
        }

        boolean restored = SlotManager.restoreSnapshot(prev, entry.wasDeleted());
        if (!restored) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.redo_slot_conflict", entry.customId()));
            return 0;
        }
        SlotManager.saveAll();

        SlotData d = SlotManager.getById(prev.customId);
        if (d != null) {
            if (entry.wasDeleted()) {
                NetworkManager.broadcastUpdate(server,
                    new SlotUpdatePayload("add", d.index, d.customId, d.displayName, d.texture,
                            d.lightLevel, d.hardness, d.soundType, null, null, d.animMeta));
            } else {
                if (d.texture != null)
                    NetworkManager.broadcastUpdate(server,
                        new SlotUpdatePayload("retexture", d.index, d.customId, null, d.texture,
                                d.lightLevel, d.hardness, d.soundType));
                NetworkManager.broadcastUpdate(server,
                    new SlotUpdatePayload("clearfaces", d.index, d.customId, null, null,
                            d.lightLevel, d.hardness, d.soundType));
                for (var fe : d.faceTextures.entrySet())
                    NetworkManager.broadcastUpdate(server,
                        new SlotUpdatePayload("setface", d.index, d.customId, null, fe.getValue(),
                                d.lightLevel, d.hardness, d.soundType, fe.getKey()));
                NetworkManager.broadcastUpdate(server,
                    new SlotUpdatePayload("setprop", d.index, d.customId, null, null,
                            d.lightLevel, d.hardness, d.soundType));
                NetworkManager.broadcastUpdate(server,
                    new SlotUpdatePayload("rename", d.index, d.customId, d.displayName, null, 0, 0, "stone"));
            }
        }
        ChatHelper.success(src, ChatHelper.formattedKey("cmd.redo_mutation_done", entry.description(), entry.customId(),
            UndoManager.redoSize(getPlayerUuid(src)), UndoManager.undoSize(getPlayerUuid(src))));
        if (UndoManager.redoSize(getPlayerUuid(src)) > 0)
            ChatHelper.info(src, ChatHelper.formattedKey("cmd.redo_next_hint", UndoManager.peekRedoDescription(getPlayerUuid(src))));
        return 1;
    }

    /** Undo N times in a loop. */
    private static int cmdUndoN(ServerCommandSource src, int count) {
        int done = 0;
        for (int i = 0; i < count; i++) {
            if (UndoManager.undoSize(getPlayerUuid(src)) == 0) break;
            int r = cmdUndo(src);
            if (r == 0) break;
            done++;
        }
        if (done > 1) ChatHelper.success(src, ChatHelper.formattedKey("cmd.undo_batch_done", done));
        return done > 0 ? 1 : 0;
    }

    private static int cmdUndoText(ServerCommandSource src, String countText) {
        try {
            int count = Integer.parseInt(countText.trim());
            if (count < 1 || count > 50) {
                ChatHelper.error(src, "Undo count must be between 1 and 50.");
                return 0;
            }
            return cmdUndoN(src, count);
        } catch (NumberFormatException ex) {
            ChatHelper.error(src, "Invalid undo count '" + countText + "'. Use 1-50.");
            return 0;
        }
    }

    /** Redo N times in a loop. */
    private static int cmdRedoN(ServerCommandSource src, int count) {
        int done = 0;
        for (int i = 0; i < count; i++) {
            if (UndoManager.redoSize(getPlayerUuid(src)) == 0) break;
            int r = cmdRedo(src);
            if (r == 0) break;
            done++;
        }
        if (done > 1) ChatHelper.success(src, ChatHelper.formattedKey("cmd.redo_batch_done", done));
        return done > 0 ? 1 : 0;
    }

    private static int cmdRedoText(ServerCommandSource src, String countText) {
        try {
            int count = Integer.parseInt(countText.trim());
            if (count < 1 || count > 50) {
                ChatHelper.error(src, "Redo count must be between 1 and 50.");
                return 0;
            }
            return cmdRedoN(src, count);
        } catch (NumberFormatException ex) {
            ChatHelper.error(src, "Invalid redo count '" + countText + "'. Use 1-50.");
            return 0;
        }
    }



    // ── Phase Q: /cb panic ────────────────────────────────────────────────────

    private static int cmdPanic(ServerCommandSource src) {
        ServerPlayerEntity p = src.getPlayer();
        if (p == null) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.console_player_only")); return 0; }
        if (com.customblocks.core.SnapshotManager.list().isEmpty()) {
            ChatHelper.error(src, "§cNo snapshots found. Nothing to roll back to.");
            com.customblocks.gui.GuiManager.playError(p);
            return 0;
        }
        com.customblocks.core.SnapshotManager.armPanic(p.getUuid());
        com.customblocks.gui.GuiManager.playError(p);
        com.customblocks.gui.FeedbackHelper.title(p, "§c§l⚠ PANIC ARMED", "§f/cb panic confirm §7to execute");
        ChatHelper.warn(src, "§c§lPANIC REQUESTED. §r§7Type §f/cb panic confirm§7 within 30 seconds to roll back to the last snapshot.");
        com.customblocks.DiscordWebhook.post(
            "\u26A0\uFE0F **PANIC ARMED** by `" + p.getName().getString() + "`\n" +
            "Server: " + (src.getServer() != null ? src.getServer().getServerMotd() : "unknown") +
            " — awaiting `/cb panic confirm`");
        ChatHelper.warn(src, "§7To cancel, do nothing — the panic will expire.");
        return 1;
    }

    private static int cmdPanicConfirm(ServerCommandSource src) {
        ServerPlayerEntity p = src.getPlayer();
        if (p == null) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.console_player_only")); return 0; }
        if (!com.customblocks.core.SnapshotManager.isPanicArmed(p.getUuid())) {
            ChatHelper.error(src, "§cNo active panic. Run §f/cb panic§c first, then confirm within 30 seconds.");
            return 0;
        }
        // Pre-op snapshot so restore itself can be undone
        com.customblocks.core.SnapshotManager.takeSnapshot("pre_op_panic");
        boolean ok = com.customblocks.core.SnapshotManager.confirmPanic(p.getUuid());
        if (ok) {
            com.customblocks.gui.GuiManager.playSuccess(p);
            ChatHelper.success(p, "PANIC COMPLETE — rolled back to last snapshot.");
            p.getServerWorld().spawnParticles(net.minecraft.particle.ParticleTypes.TOTEM_OF_UNDYING,
                p.getX(), p.getY() + 1.5, p.getZ(), 40, 0.5, 0.5, 0.5, 0.1);
            p.getServerWorld().playSound(null, p.getBlockPos(),
                net.minecraft.sound.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
                net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 1.0f);
        } else {
            com.customblocks.gui.GuiManager.playError(p);
            ChatHelper.error(src, "§cPanic restore failed — check server logs for details.");
        }
        return ok ? 1 : 0;
    }

    private static SlotData snapshotForCmd(SlotData d) {
        java.util.Map<String, byte[]> facesCopy = new java.util.concurrent.ConcurrentHashMap<>();
        d.faceTextures.forEach((k, v) -> facesCopy.put(k, v.clone()));
        return new SlotData(d.index, d.customId, d.displayName,
                d.texture != null ? d.texture.clone() : null,
                d.lightLevel, d.hardness, d.soundType, facesCopy, d.animMeta,
                d.shapeBoxes != null ? new java.util.ArrayList<>(d.shapeBoxes) : null, d.noCollision);
    }

    /** Wrapper validating size range with branded message. */
    private static int cmdResizeSafe(ServerCommandSource src, String id, int size) {
        if (size < 16 || size > 256) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.texture_size_bad_range", size));
            return 0;
        }
        return cmdResize(src, id, size);
    }

    private static int cmdResizeText(ServerCommandSource src, String id, String text) {
        try {
            int size = Integer.parseInt(text.trim());
            return cmdResizeSafe(src, id, size);
        } catch (NumberFormatException ex) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.texture_size_bad_text", text));
            return 0;
        }
    }

    /** Resize the existing stored texture (and all face overrides) of a block. */
    private static int cmdResize(ServerCommandSource src, String id, int size) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        SlotData d = SlotManager.getById(id);
        if (d == null) { src.sendError(notFound(id)); return 0; }
        if (d.texture == null && d.faceTextures.isEmpty()) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.resize_no_texture", id)); return 0;
        }
        UndoManager.pushUndoMutation(id, SlotManager.getById(id), "resize " + size, getPlayerUuid(src));
        ChatHelper.info(src, ChatHelper.formattedKey("cmd.resize_progress", id, size));
        MinecraftServer server = src.getServer();
        thread(() -> {
            try {
                byte[] newTex = d.texture != null ? ImageProcessor.resizeTo(d.texture, size) : null;
                java.util.Map<String, byte[]> newFaces = new java.util.concurrent.ConcurrentHashMap<>();
                for (var e : d.faceTextures.entrySet())
                    newFaces.put(e.getKey(), ImageProcessor.resizeTo(e.getValue(), size));

                server.execute(() -> {
                    SlotData cur = SlotManager.getById(id);
                    if (cur == null) { src.sendError(notFound(id)); return; }
                    if (newTex != null) SlotManager.updateTexture(id, newTex);
                    for (var e : newFaces.entrySet()) SlotManager.setFaceTexture(id, e.getKey(), e.getValue());
                    SlotManager.saveAll();
                    SlotData updated = SlotManager.getById(id);
                    if (updated != null) {
                        if (newTex != null)
                            NetworkManager.broadcastUpdate(server,
                                new SlotUpdatePayload("retexture", updated.index, id, null, newTex,
                                        updated.lightLevel, updated.hardness, updated.soundType));
                        for (var e : newFaces.entrySet())
                            NetworkManager.broadcastUpdate(server,
                                new SlotUpdatePayload("setface", updated.index, id, null, e.getValue(),
                                        updated.lightLevel, updated.hardness, updated.soundType, e.getKey()));
                    }
                    ChatHelper.success(src, ChatHelper.formattedKey("cmd.resize_done", id, size));
                });
            } catch (Exception e) {
                server.execute(() -> ChatHelper.error(src, ChatHelper.formattedKey("cmd.resize_failed", e.getMessage())));
            }
        });
        return 1;
    }


    private static int cmdImportFolder(ServerCommandSource src) {
        File importDir = new File("config/customblocks/import");
        if (!importDir.exists()) {
            importDir.mkdirs();
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.import_folder_created"));
            ChatHelper.info(src, ChatHelper.formattedKey("cmd.import_folder_drop_hint"));
            return 1;
        }

        File[] images = importDir.listFiles((dir, name) -> {
            String l = name.toLowerCase();
            return l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg")
                || l.endsWith(".gif") || l.endsWith(".bmp") || l.endsWith(".webp")
                || l.endsWith(".tiff") || l.endsWith(".tif");
        });
        if (images == null || images.length == 0) {
            ChatHelper.warn(src, ChatHelper.formattedKey("cmd.import_no_images"));
            ChatHelper.info(src, ChatHelper.formattedKey("cmd.import_formats_line"));
            return 0;
        }
        java.util.Arrays.sort(images, java.util.Comparator.comparing(File::getName));
        int free = SlotManager.freeSlots();
        if (free == 0) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.all_slots_full", CustomBlocksConfig.maxSlots)); return 0; }
        ChatHelper.info(src, ChatHelper.formattedKey("cmd.import_found_counts", images.length, free));
        MinecraftServer server = src.getServer();
        thread(() -> {
            java.util.List<String[]> toAdd = new java.util.ArrayList<>();
            java.util.List<byte[]> toBytes = new java.util.ArrayList<>();
            java.util.List<String> toAnims = new java.util.ArrayList<>();
            java.util.List<String> skipped = new java.util.ArrayList<>();
            java.util.List<String> failed  = new java.util.ArrayList<>();

            for (File img : images) {
                String rawName = img.getName().replaceAll("(?i)\\.(png|jpg|jpeg|gif|bmp|webp|tiff|tif)$", "");
                String id = rawName.toLowerCase().replaceAll("[^a-z0-9_]", "_");
                String displayName = java.util.Arrays.stream(rawName.replace("_"," ").split(" "))
                    .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase())
                    .collect(java.util.stream.Collectors.joining(" "));

                if (SlotManager.hasId(id)) { skipped.add(id); continue; }
                if (toAdd.size() >= free) { failed.add(id + "(no slot)"); continue; }
                try {
                    byte[] raw = java.nio.file.Files.readAllBytes(img.toPath());
                    String animMeta = null;
                    byte[] bytes;
                     ImageProcessor.ProcessResult result = ImageProcessor.isAnimatedImage(raw) ? ImageProcessor.processAnimation(raw, 128) : null;
                     if (result != null && result.isAnimated()) {
                         bytes = result.bytes();
                         animMeta = result.mcmeta();
                     } else {
                         bytes = ImageProcessor.toPng(raw);
                         bytes = ImageProcessor.padToSquare(bytes);
                         bytes = ImageProcessor.replaceBackground(bytes);
                     }
                    toAdd.add(new String[]{id, displayName});
                    toBytes.add(bytes);
                    toAnims.add(animMeta);
                } catch (Exception e) { failed.add(id + "(error: " + e.getMessage() + ")"); }
            }

            server.execute(() -> {
                int created = 0;
                java.util.List<String> createdIds = new java.util.ArrayList<>();
                for (int i = 0; i < toAdd.size(); i++) {
                    String id = toAdd.get(i)[0], name = toAdd.get(i)[1];
                    byte[] b = toBytes.get(i);
                    String anim = toAnims.get(i);
                    SlotData d = SlotManager.assign(id, name, b);
                    if (d == null) { failed.add(id + "(slot full)"); continue; }
                    if (anim != null) SlotManager.setAnimMeta(id, anim);
                    UndoManager.pushUndoCreate(id, getPlayerUuid(src));
                    NetworkManager.broadcastUpdate(server,
                        new SlotUpdatePayload("add", d.index, id, name, b, d.lightLevel, d.hardness, d.soundType, null, null, d.animMeta));
                    created++;
                    createdIds.add("§b" + id + "§7(§f" + name + "§7)" + (anim != null ? " §d[GIF]" : ""));
                }
                if (created > 0) SlotManager.saveAll();
                StringBuilder msg = new StringBuilder(ChatHelper.formattedKey("cmd.import_summary_head", created));
                if (!skipped.isEmpty()) msg.append(ChatHelper.formattedKey("cmd.import_summary_skipped", skipped.size()));
                if (!failed.isEmpty()) msg.append(ChatHelper.formattedKey("cmd.import_summary_failed", failed.size()));
                ChatHelper.success(src, msg.toString());
                ChatHelper.info(src, ChatHelper.formattedKey("cmd.import_slots_line", SlotManager.usedSlots(), CustomBlocksConfig.maxSlots));
                if (!createdIds.isEmpty())
                    ChatHelper.info(src, ChatHelper.formattedKey("cmd.import_blocks_created_line", String.join("§7, ", createdIds)));
                if (!skipped.isEmpty())
                    ChatHelper.info(src, ChatHelper.formattedKey("cmd.import_skipped_line", String.join("§7, §e", skipped)));
                if (!failed.isEmpty())
                    ChatHelper.error(src, ChatHelper.formattedKey("cmd.import_failed_line", String.join(", ", failed)));
            });
        });
        return 1;
    }

    private static int cmdExport(ServerCommandSource src) {
        File dir = new File("config/customblocks"); dir.mkdirs();
        File out = new File(dir, "export.json");
        try {
            com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (SlotData d : SlotManager.allSlots()) {
                com.google.gson.JsonObject e = new com.google.gson.JsonObject();
                e.addProperty("id", d.customId);
                e.addProperty("displayName", d.displayName);
                e.addProperty("slot", d.index);
                e.addProperty("lightLevel", d.lightLevel);
                e.addProperty("hardness", d.hardness);
                e.addProperty("soundType", d.soundType);
                e.addProperty("animated", d.isAnimated());
                if (!d.faceTextures.isEmpty()) {
                    com.google.gson.JsonArray faces = new com.google.gson.JsonArray();
                    d.faceTextures.keySet().forEach(faces::add);
                    e.add("faceOverrides", faces);
                }
                arr.add(e);
            }
            root.add("blocks", arr);
            root.addProperty("totalBlocks", SlotManager.usedSlots());
            root.addProperty("freeSlots", SlotManager.freeSlots());
            try (java.io.FileWriter fw = new java.io.FileWriter(out, java.nio.charset.StandardCharsets.UTF_8)) {
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root, fw);
            }
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.export_json_done", SlotManager.usedSlots()));
        } catch (Exception e) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.export_failed", e.getMessage())); }
        return 1;
    }

    private static int cmdList(ServerCommandSource src) {
        final String D = "§8§m                                              §r";
        int used = SlotManager.usedSlots(), free = SlotManager.freeSlots();
        src.sendMessage(Text.literal(" "));
        ChatHelper.info(src, ChatHelper.formattedKey("cmd.list_stats", used, free));
        src.sendMessage(Text.literal(D));
        if (used == 0) {
            src.sendMessage(Text.literal(ChatHelper.formattedKey("cmd.list_empty_hint")));
            src.sendMessage(Text.literal(D));
            src.sendMessage(Text.literal(" "));
            return 1;
        }

        try {
            ServerPlayerEntity p = src.getPlayerOrThrow();
            p.sendMessage(ChatHelper.rawPrefixed("").append(Text.literal("  " + ChatHelper.formattedKey("cmd.listgui_hint")).styled(s -> s.withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.RUN_COMMAND, "/cb listgui")).withFormatting(net.minecraft.util.Formatting.UNDERLINE))), false);
            p.sendMessage(Text.literal(ChatHelper.formattedKey("cmd.list_breakdown_hint")));
        } catch(Exception ignored) {}

        java.util.List<SlotData> sorted = new java.util.ArrayList<>(SlotManager.allSlots());
        sorted.removeIf(d -> "tab_icon".equals(d.customId));
        sorted.sort(java.util.Comparator.comparingInt(d -> d.index));
        for (SlotData d : sorted) {
            StringBuilder line = new StringBuilder();
            line.append("  §f").append(String.format("%-20s", d.customId))
                .append(" §7→ §e").append(d.displayName)
                .append(" §8(#").append(d.index).append(")");
            if (d.lightLevel > 0) line.append("  §6✦").append(d.lightLevel);
            if (d.hardness < 0)   line.append("  §c∞");
            if (d.isAnimated())   line.append("  §b⟳GIF");
            if (d.hasFaces())     line.append("  §d⬡faces");
            if (!d.soundType.equals("stone")) line.append("  §7[").append(d.soundType).append("]");
            src.sendMessage(Text.literal(line.toString()));
        }
        src.sendMessage(Text.literal(D));
        src.sendMessage(Text.literal(" "));
        return 1;
    }

    private static final int HELP_PAGE_SIZE = 10;

    private static int cmdHelp(ServerCommandSource src, int page) {
        final String D = "§8§m                                              §r";
        final String G = "§7";

        List<HelpRegistry.Entry> entries = HelpRegistry.all();
        int totalPages = Math.max(1, (entries.size() + HELP_PAGE_SIZE - 1) / HELP_PAGE_SIZE);
        if (page < 1 || page > totalPages) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.help_bad_page", page, totalPages));
            return 0;
        }
        int start = (page - 1) * HELP_PAGE_SIZE;
        int end = Math.min(start + HELP_PAGE_SIZE, entries.size());

        src.sendMessage(Text.literal(" "));
        ChatHelper.info(src, ChatHelper.formattedKey("cmd.help_header", page, totalPages));
        src.sendMessage(Text.literal(D));

        for (int i = start; i < end; i++) {
            HelpRegistry.Entry e = entries.get(i);
            String suggest = suggestFromSyntax(e.syntax());
            net.minecraft.text.MutableText syntax = Text.literal("  " + e.syntax()).styled(s -> {
                net.minecraft.text.Style st = s;
                if (suggest != null && !suggest.isEmpty()) {
                    st = st.withClickEvent(new net.minecraft.text.ClickEvent(
                        net.minecraft.text.ClickEvent.Action.SUGGEST_COMMAND, suggest));
                }
                return st.withUnderline(suggest != null && !suggest.isEmpty());
            });
            net.minecraft.text.MutableText line = Text.literal("§8[" + e.category() + "]§r ").append(G).append(syntax)
                .append(Text.literal("  " + G + "- " + e.description() + " §8[" + e.permissionNode() + "]"));
            src.sendMessage(line);
        }
        src.sendMessage(Text.literal(D));
        if (totalPages > 1) {
            int next = page >= totalPages ? 1 : page + 1;
            src.sendMessage(Text.literal(ChatHelper.formattedKey("cmd.help_page_nav", next, totalPages)));
        }
        ChatHelper.info(src, ChatHelper.formattedKey("cmd.help_overlay_hint"));
        src.sendMessage(Text.literal(" "));
        return 1;
    }

    private static int cmdShowVoicePicker(ServerCommandSource src) {
        ServerPlayerEntity p = src.getPlayer();
        if (p == null) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.console_player_only"));
            return 0;
        }
        sendVoicePickerLines(p);
        return 1;
    }

    private static int cmdSetVoiceMode(ServerCommandSource src, String rawMode) {
        String normalized = CustomBlocksConfig.normalizeVoiceMode(rawMode);
        if (!normalized.equalsIgnoreCase(rawMode.trim())) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.voice.invalid", rawMode));
            return 0;
        }
        CustomBlocksConfig.voiceMode = normalized;
        CustomBlocksConfig.save();
        ChatHelper.success(src, ChatHelper.formattedKey("cmd.voice.set", normalized));
        ServerPlayerEntity p = src.getPlayer();
        if (p != null) {
            GuiManager.openWelcomeGui(p);
        }
        return 1;
    }

    private static void sendVoicePickerLines(ServerPlayerEntity p) {
        p.sendMessage(ChatHelper.rawPrefixed("§d§lPick Voice §7(click a mode):"), false);
        String[] modes = {"friendly", "professional", "royal", "minimal", "arabic", "silly"};
        for (String mode : modes) {
            String sample = com.customblocks.core.VoiceCatalog.formatForMode(mode, "cancel.search.empty");
            net.minecraft.text.MutableText line = net.minecraft.text.Text.literal("  §f" + mode + " §8— §7" + sample)
                .styled(s -> s.withClickEvent(new net.minecraft.text.ClickEvent(
                    net.minecraft.text.ClickEvent.Action.RUN_COMMAND, "/cb voice " + mode
                )).withUnderline(true));
            p.sendMessage(line, false);
        }
    }

    /** Best-effort clickable suggestion seed from HelpRegistry syntax. */
    private static String suggestFromSyntax(String syntax) {
        if (syntax == null) return null;
        String s = syntax.trim();
        if (!s.startsWith("/cb")) return null;
        int altIdx = s.indexOf('|');
        if (altIdx > 0) {
            int cbIdx = s.indexOf("/cb ");
            if (cbIdx >= 0 && altIdx > cbIdx + 4) {
                s = s.substring(0, altIdx) + s.substring(s.indexOf(' ', altIdx) >= 0 ? s.indexOf(' ', altIdx) : s.length());
            }
        }
        int argIdx = s.indexOf('<');
        if (argIdx > 0) s = s.substring(0, argIdx).trim();
        int optIdx = s.indexOf('[');
        if (optIdx > 0) s = s.substring(0, optIdx).trim();
        return s;
    }

    // ── Color items ───────────────────────────────────────────────────────────



    public static int cmdGiveSquareInternal(ServerCommandSource src, String color) {
        String c = color.toLowerCase().trim();
        if (!c.equals("black") && !c.equals("yellow") && !c.equals("green")) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.give_color_bad_bwy", color)); return 0;
        }
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, c + "_square");
        net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
        if (item == null || item == net.minecraft.item.Items.AIR) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.give_square_not_found")); return 0; }
        try {
            src.getPlayerOrThrow().getInventory().insertStack(new ItemStack(item, 1));
            String disp = Character.toUpperCase(c.charAt(0)) + c.substring(1);
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.give_square_done", disp));
        } catch (Exception ex) { ex.printStackTrace(); ChatHelper.error(src, ChatHelper.formattedKey("cmd.gui_open_failed", ex.getMessage())); return 0; }
        return 1;
    }

    public static int cmdGiveTriangleInternal(ServerCommandSource src, String color) {
        String c = color.toLowerCase().trim();
        if (!c.equals("black") && !c.equals("yellow") && !c.equals("green")) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.give_color_bad_bwy", color)); return 0;
        }
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, c + "_triangle");
        net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
        if (item == null || item == net.minecraft.item.Items.AIR) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.give_triangle_not_found")); return 0; }
        try {
            src.getPlayerOrThrow().getInventory().insertStack(new ItemStack(item, 1));
            String disp = Character.toUpperCase(c.charAt(0)) + c.substring(1);
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.give_triangle_done", disp));
        } catch (Exception ex) { ex.printStackTrace(); ChatHelper.error(src, ChatHelper.formattedKey("cmd.gui_open_failed", ex.getMessage())); return 0; }
        return 1;
    }

    public static int cmdGiveRectangleInternal(ServerCommandSource src) {
        net.minecraft.util.Identifier rectId = net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, "rainbow_rectangle");
        net.minecraft.item.Item rectItem = net.minecraft.registry.Registries.ITEM.get(rectId);
        if (rectItem == null || rectItem == net.minecraft.item.Items.AIR) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.give_rainbow_rectangle_not_found")); return 0;
        }
        try {
            src.getPlayerOrThrow().getInventory().insertStack(new ItemStack(rectItem, 1));
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.give_rainbow_rectangle_done"));
        } catch (Exception ex) { ex.printStackTrace(); ChatHelper.error(src, ChatHelper.formattedKey("cmd.gui_open_failed", ex.getMessage())); return 0; }
        return 1;
    }

    public static int cmdGiveHexagonInternal(ServerCommandSource src) {
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, "golden_hexagon");
        net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
        if (item == null || item == net.minecraft.item.Items.AIR) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.give_golden_hexagon_not_found")); return 0; }
        try {
            src.getPlayerOrThrow().getInventory().insertStack(new ItemStack(item, 1));
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.give_golden_hexagon_done"));
        } catch (Exception ex) { ex.printStackTrace(); ChatHelper.error(src, ChatHelper.formattedKey("cmd.gui_open_failed", ex.getMessage())); return 0; }
        return 1;
    }

    public static int cmdGiveBrushInternal(ServerCommandSource src) {
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, "lumina_brush");
        net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
        if (item == null || item == net.minecraft.item.Items.AIR) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.give_lumina_brush_not_found")); return 0; }
        try {
            src.getPlayerOrThrow().getInventory().insertStack(new ItemStack(item, 1));
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.give_lumina_brush_done"));
        } catch (Exception ex) { ex.printStackTrace(); ChatHelper.error(src, ChatHelper.formattedKey("cmd.gui_open_failed", ex.getMessage())); return 0; }
        return 1;
    }

    public static int cmdGiveChiselInternal(ServerCommandSource src) {
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, "amethyst_chisel");
        net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
        if (item == null || item == net.minecraft.item.Items.AIR) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.give_amethyst_chisel_not_found")); return 0; }
        try {
            src.getPlayerOrThrow().getInventory().insertStack(new ItemStack(item, 1));
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.give_amethyst_chisel_done"));
        } catch (Exception ex) { ex.printStackTrace(); ChatHelper.error(src, ChatHelper.formattedKey("cmd.gui_open_failed", ex.getMessage())); return 0; }
        return 1;
    }

    public static int cmdGiveDiamondInternal(ServerCommandSource src) {
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, "diamond_triangle");
        net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
        if (item == null || item == net.minecraft.item.Items.AIR) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.give_diamond_triangle_not_found")); return 0; }
        try {
            src.getPlayerOrThrow().getInventory().insertStack(new ItemStack(item, 1));
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.give_diamond_triangle_done"));
        } catch (Exception ex) { ex.printStackTrace(); ChatHelper.error(src, ChatHelper.formattedKey("cmd.gui_open_failed", ex.getMessage())); return 0; }
        return 1;
    }

    public static int cmdGiveCustomColorToolsInternal(ServerCommandSource src, String hexInput) {
        Integer rgb = parseHexColor(hexInput);
        if (rgb == null) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.give_hex_bad_format"));
            return 0;
        }
        net.minecraft.util.Identifier squareId = net.minecraft.util.Identifier.of(
            CustomBlocksMod.MOD_ID, com.customblocks.item.ColorSquareItem.CUSTOM_SQUARE_REGISTRY_ID);
        net.minecraft.util.Identifier triangleId = net.minecraft.util.Identifier.of(
            CustomBlocksMod.MOD_ID, com.customblocks.item.ColorTriangleItem.CUSTOM_TRIANGLE_REGISTRY_ID);
        net.minecraft.item.Item squareItem = net.minecraft.registry.Registries.ITEM.get(squareId);
        net.minecraft.item.Item triangleItem = net.minecraft.registry.Registries.ITEM.get(triangleId);
        if (squareItem == null || squareItem == net.minecraft.item.Items.AIR
                || triangleItem == null || triangleItem == net.minecraft.item.Items.AIR) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.give_custom_tools_not_found"));
            return 0;
        }
        try {
            ServerPlayerEntity player = src.getPlayerOrThrow();
            player.getInventory().insertStack(com.customblocks.item.ColorSquareItem.createCustomStack(squareItem, rgb));
            player.getInventory().insertStack(com.customblocks.item.ColorTriangleItem.createCustomStack(triangleItem, rgb));
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.give_custom_tools_done",
                String.format(java.util.Locale.ROOT, "%06X", rgb & 0xFFFFFF)));
        } catch (Exception ex) {
            ex.printStackTrace();
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.gui_open_failed", ex.getMessage()));
            return 0;
        }
        return 1;
    }

    private static Integer parseHexColor(String text) {
        if (text == null) return null;
        String hex = text.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.regionMatches(true, 0, "0x", 0, 2)) hex = hex.substring(2);
        if (hex.length() == 3 && hex.matches("[0-9a-fA-F]{3}")) {
            hex = "" + hex.charAt(0) + hex.charAt(0)
                     + hex.charAt(1) + hex.charAt(1)
                     + hex.charAt(2) + hex.charAt(2);
        }
        if (!hex.matches("[0-9a-fA-F]{6}")) return null;
        return Integer.parseInt(hex, 16);
    }

    public static int cmdBulkRecolorFromGui(ServerPlayerEntity player, String color, String scopeExpr, String excludeExpr, boolean apply) {
        if (player == null) return 0;
        return cmdBulkRecolor(player.getCommandSource(), color, scopeExpr, excludeExpr, apply);
    }

    private static int cmdBulkRecolor(ServerCommandSource src, String colorRaw, String scopeExprRaw, boolean apply) {
        return cmdBulkRecolor(src, colorRaw, scopeExprRaw, "", apply);
    }

    private static int cmdBulkRecolor(ServerCommandSource src, String colorRaw, String scopeExprRaw, String excludeExprRaw, boolean apply) {
        if (apply) com.customblocks.core.SnapshotManager.takeSnapshot("pre_op_bulk_recolor");
        String color = colorRaw == null ? "" : colorRaw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!color.equals("green") && !color.equals("yellow") && !color.equals("black")) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.bulk_recolor_unknown_color", colorRaw));
            return 0;
        }
        if (!CustomBlocksConfig.isColorToolModeConfigured()) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.bulk_unconfigured_color_mode"));
            return 0;
        }

        String scopeExpr = (scopeExprRaw == null || scopeExprRaw.isBlank()) ? "all" : scopeExprRaw.trim();
        ScopeResolution scope = resolveBulkRecolorScope(src, scopeExpr);
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>(scope.matched());
        if (ids.isEmpty()) {
            ChatHelper.warn(src, ChatHelper.formattedKey("cmd.bulk_recolor_no_match", scopeExpr));
            if (!scope.invalidTokens().isEmpty()) {
                ChatHelper.warn(src, ChatHelper.formattedKey("cmd.bulk_recolor_invalid_tokens", String.join(", ", scope.invalidTokens())));
            }
            return 0;
        }
        ScopeResolution exclude = resolveBulkRecolorScope(src, excludeExprRaw == null ? "" : excludeExprRaw.trim());
        if (!exclude.matched().isEmpty()) {
            ids.removeAll(exclude.matched());
        }

        int[] rgb = switch (color) {
            case "green" -> CustomBlocksConfig.builtInTriangleRgb("green", 30, 140, 30);
            case "yellow" -> CustomBlocksConfig.builtInTriangleRgb("yellow", 240, 200, 20);
            default -> new int[]{20, 20, 20};
        };
        String colorLabel = Character.toUpperCase(color.charAt(0)) + color.substring(1);

        if (!apply) {
            java.util.List<String> sample = ids.stream().limit(8).toList();
            ChatHelper.warn(src, ChatHelper.formattedKey("cmd.bulk_recolor_preview", ids.size(), exclude.matched().size(),
                scope.invalidTokens().size() + exclude.invalidTokens().size()));
            if (!sample.isEmpty()) {
                ChatHelper.warn(src, ChatHelper.formattedKey("cmd.bulk_recolor_sample", String.join(", ", sample)));
            }
            ChatHelper.warn(src, ChatHelper.formattedKey("cmd.bulk_recolor_apply_example", color, scopeExpr));
            return 1;
        }

        int created = 0;
        int existed = 0;
        int skipped = 0;
        java.util.List<String> failedIds = new java.util.ArrayList<>();

        ServerPlayerEntity viewer = src.getPlayer();
        int totalIds = ids.size();
        boolean bossBar = viewer != null && totalIds >= 2;
        if (bossBar) {
            FeedbackHelper.startBossBar(viewer, "Bulk recolor...");
        }
        try {
            int pass = 0;
            for (String id : ids) {
            pass++;
            if (bossBar) {
                FeedbackHelper.updateBossBar(viewer,
                    "Recoloring " + pass + " / " + totalIds,
                    totalIds <= 0 ? 1f : pass / (float) totalIds);
            }
            com.customblocks.core.SlotData source = SlotManager.getById(id);
            if (source == null) { skipped++; continue; }

            String newId = com.customblocks.item.ColorTriangleItem.variantIdFor(source.customId, color);
            if (newId.equals(source.customId)) { skipped++; continue; }
            if (SlotManager.hasId(newId)) { existed++; continue; }

            byte[] workTexture = source.texture;
            if ((workTexture == null || workTexture.length == 0) && source.faceTextures != null && !source.faceTextures.isEmpty()) {
                workTexture = source.faceTextures.get("north");
                if (workTexture == null) workTexture = source.faceTextures.values().iterator().next();
            }
            if (workTexture == null || workTexture.length == 0) {
                skipped++;
                failedIds.add(id + " (no texture)");
                continue;
            }
            if (SlotManager.freeSlots() == 0) {
                failedIds.add(id + " (no free slots)");
                break;
            }

            try {
                byte[] tex = com.customblocks.item.ColorTriangleItem.recolourTexture(
                    workTexture, rgb[0], rgb[1], rgb[2], CustomBlocksConfig.useTrappedHoleFill());
                String newName = com.customblocks.item.ColorTriangleItem.variantDisplayNameFor(source.displayName, colorLabel);
                SlotData createdData = SlotManager.assign(newId, newName, tex);
                if (createdData == null) {
                    failedIds.add(id + " (assign failed)");
                    continue;
                }
                SlotManager.setLightLevel(newId, source.lightLevel);
                SlotManager.setHardness(newId, source.hardness);
                SlotManager.setSoundType(newId, source.soundType);
                UndoManager.pushUndoCreate(newId, getPlayerUuid(src));
                created++;
            } catch (Exception ex) {
                failedIds.add(id + " (" + ex.getMessage() + ")");
            }
            }
        } finally {
            if (bossBar) {
                FeedbackHelper.clearBossBar(viewer);
            }
        }

        SlotManager.saveAll();
        CustomBlocksMod.broadcastFullSync(src.getServer());
        ChatHelper.success(src, ChatHelper.formattedKey("cmd.bulk_recolor_complete", created, existed, skipped,
            exclude.matched().size(), scope.invalidTokens().size() + exclude.invalidTokens().size()));
        ChatHelper.warn(src, ChatHelper.formattedKey("cmd.bulk_recolor_undo_hint"));
        if (!failedIds.isEmpty()) {
            int preview = Math.min(5, failedIds.size());
            String failDetail = String.join(", ", failedIds.subList(0, preview))
                + (failedIds.size() > preview ? " ... +" + (failedIds.size() - preview) + " more" : "");
            ChatHelper.warn(src, ChatHelper.formattedKey("cmd.bulk_recolor_fail_line", failDetail));
        }
        return created > 0 ? 1 : 0;
    }

    private record ScopeResolution(java.util.LinkedHashSet<String> matched, java.util.LinkedHashSet<String> invalidTokens) {}

    private static ScopeResolution resolveBulkRecolorScope(ServerCommandSource src, String scopeExpr) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> invalid = new java.util.LinkedHashSet<>();
        String s = scopeExpr == null ? "all" : scopeExpr.trim();
        if (s.isBlank()) return new ScopeResolution(out, invalid);
        String low = s.toLowerCase(java.util.Locale.ROOT);

        if (low.equals("all") || low.equals("everything")) {
            for (SlotData d : SlotManager.allSlots()) out.add(d.customId);
            return new ScopeResolution(out, invalid);
        }
        if (low.equals("uncategorized") || low.equals("unsorted")) {
            for (SlotData d : SlotManager.allSlots()) {
                if (com.customblocks.core.CategoryManager.getCategoriesForBlock(d.customId).isEmpty()) out.add(d.customId);
            }
            return new ScopeResolution(out, invalid);
        }
        if (low.equals("selected") || low.equals("currently_selected")) {
            java.util.UUID uuid = getPlayerUuid(src);
            if (uuid == null) return new ScopeResolution(out, invalid);
            out.addAll(GuiManager.getBulkRecolorSelected(uuid));
            return new ScopeResolution(out, invalid);
        }
        if (low.equals("favorites")) {
            if (!PermissionHelper.canFavorite(src)) return new ScopeResolution(out, invalid);
            ServerPlayerEntity p = src.getPlayer();
            if (p == null) return new ScopeResolution(out, invalid);
            out.addAll(com.customblocks.core.FavoritesManager.validatedSet(p.getUuid()));
            return new ScopeResolution(out, invalid);
        }
        if (low.startsWith("category:")) {
            String key = s.substring("category:".length()).trim();
            com.customblocks.core.Category cat = com.customblocks.core.CategoryManager.getCategory(key);
            if (cat == null) {
                invalid.add("category:" + key);
                return new ScopeResolution(out, invalid);
            }
            for (SlotData d : com.customblocks.core.CategoryManager.getBlocksInCategory(cat.key())) out.add(d.customId);
            return new ScopeResolution(out, invalid);
        }
        if (low.startsWith("ids:")) {
            String csv = s.substring("ids:".length());
            for (String token : csv.split("[,\\s]+")) {
                String id = token.trim();
                if (id.isEmpty()) continue;
                if (SlotManager.hasId(id)) out.add(id);
                else invalid.add(id);
            }
            return new ScopeResolution(out, invalid);
        }
        if (low.startsWith("query:")) {
            String q = s.substring("query:".length()).trim().toLowerCase(java.util.Locale.ROOT);
            for (SlotData d : SlotManager.allSlots()) {
                if (d.customId.toLowerCase(java.util.Locale.ROOT).contains(q)
                    || d.displayNameLower.contains(q)) {
                    out.add(d.customId);
                }
            }
            return new ScopeResolution(out, invalid);
        }
        if (low.startsWith("range:")) {
            String payload = s.substring("range:".length()).trim();
            String[] parts = payload.split("-", 2);
            if (parts.length == 2) {
                try {
                    int a = Integer.parseInt(parts[0].trim());
                    int b = Integer.parseInt(parts[1].trim());
                    int lo = Math.min(a, b);
                    int hi = Math.max(a, b);
                    for (SlotData d : SlotManager.allSlots()) {
                        if (d.index >= lo && d.index <= hi) out.add(d.customId);
                    }
                } catch (NumberFormatException ignored) {
                    invalid.add("range:" + payload);
                }
            }
            return new ScopeResolution(out, invalid);
        }
        if (low.startsWith("recent:")) {
            int n = 10;
            try { n = Math.max(1, Integer.parseInt(s.substring("recent:".length()).trim())); }
            catch (Exception ignored) { invalid.add(s); }
            java.util.List<SlotData> all = new java.util.ArrayList<>(SlotManager.allSlots());
            all.sort(java.util.Comparator.comparingInt(d -> -d.index));
            for (int i = 0; i < Math.min(n, all.size()); i++) out.add(all.get(i).customId);
            return new ScopeResolution(out, invalid);
        }

        for (String token : s.split("[,\\s]+")) {
            if (token.isBlank()) continue;
            if (SlotManager.hasId(token)) out.add(token);
            else invalid.add(token);
        }
        return new ScopeResolution(out, invalid);
    }

    private static int cmdRpPause(ServerCommandSource src, boolean pause) {
        var server = src.getServer();
        var packet = new com.customblocks.network.RpPausePayload(pause);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, packet);
        }
        if (pause) {
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.resourcepack_paused"));
        } else {
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.resourcepack_resumed"));
        }
        return 1;
    }

    private static int cmdReload(ServerCommandSource src) {
        ChatHelper.success(src, ChatHelper.formattedKey("cmd.reload_background"));
        // Fix 4: Run off the server thread to prevent blocking ticks for 10+ seconds
        SlotManager.flushSave();
        new Thread(() -> {
            try {
                SlotManager.loadAll();
                src.getServer().execute(() -> {
                    CustomBlocksMod.broadcastFullSync(src.getServer());
                    ChatHelper.success(src, ChatHelper.formattedKey("cmd.reload_done"));
                });
            } catch (Exception e) {
                IncidentRecorder.record("reload", "SlotManager.loadAll", e);
                src.getServer().execute(() ->
                    ChatHelper.error(src, ChatHelper.formattedKey("cmd.reload_failed", e.getMessage())));
            }
        }, "CustomBlocks-Reload").start();
        return 1;
    }

    private static int cmdDiagnostics(ServerCommandSource src) {
        try {
            java.nio.file.Path zipPath = com.customblocks.core.DiagnosticsHelper.createDiagnosticsZip();
            if (zipPath == null) {
                ChatHelper.error(src, "Diagnostics export failed — check server log for details.");
                return 0;
            }
            ChatHelper.success(src, "Diagnostics bundle created: " + zipPath);
            return 1;
        } catch (Exception e) {
            ChatHelper.error(src, "Diagnostics export failed: " + e.getMessage());
            return 0;
        }
    }

    private static int cmdEditorPicker(ServerCommandSource src) {
        try {
            ServerPlayerEntity player = src.getPlayerOrThrow();
            GuiManager.openEditorPicker(player);
        } catch (Exception ex) { 
            ex.printStackTrace(); 
            com.customblocks.gui.GuiManager.logError();
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.gui_open_failed", ex.getMessage())); 
        }
        return 1;
    }

    private static int cmdGui(ServerCommandSource src) {
        try {
            ServerPlayerEntity player = src.getPlayerOrThrow();
            GuiManager.openFeatureMenu(player, 0);
        } catch (Exception ex) { 
            ex.printStackTrace(); 
            com.customblocks.gui.GuiManager.logError();
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.gui_open_failed", ex.getMessage())); 
        }
        return 1;
    }

    private static int cmdEditor(ServerCommandSource src, String id) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        try {
            ServerPlayerEntity player = src.getPlayerOrThrow();
            GuiManager.openEditor(player, id, 0, true);  // fromCommand = true → 1-press ESC exits
        } catch (Exception ex) { 
            ex.printStackTrace(); 
            com.customblocks.gui.GuiManager.logError();
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.gui_open_failed", ex.getMessage())); 
        }
        return 1;
    }


    private static int cmdBlockAdd(ServerCommandSource src, String rawId, String rawCat) {
        String catKey = sanitize(rawCat);
        com.customblocks.core.Category cat = com.customblocks.core.CategoryManager.getCategory(catKey);
        if (cat == null) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.category_not_found", catKey));
            return 0;
        }

        if (rawId.equals("*")) {
            java.util.UUID uuid = src.getPlayer() != null ? src.getPlayer().getUuid() : null;
            // Snapshot BEFORE the bulk change for atomic undo (R20)
            com.customblocks.core.UndoManager.CategoryUndoEntry snap =
                    com.customblocks.core.UndoManager.captureCategorySnapshot(
                            "bulk-assign all → " + cat.displayName(), uuid);
            java.util.Collection<SlotData> blocks = SlotManager.allSlots();
            int count = 0;
            for (SlotData d : blocks) {
                if (!com.customblocks.core.CategoryManager.getCategoriesForBlock(d.customId).contains(catKey)) {
                    com.customblocks.core.CategoryManager.assignBlock(d.customId, catKey);
                    count++;
                }
            }
            if (count > 0) com.customblocks.core.UndoManager.pushCategoryUndo(snap);
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.block_add_bulk_done", count, cat.displayName()));
            return 1;
        } else {
            String id = sanitize(rawId);
            if (!SlotManager.hasId(id)) {
                src.sendError(notFound(id));
                return 0;
            }
            if (com.customblocks.core.CategoryManager.getCategoriesForBlock(id).contains(catKey)) {
                ChatHelper.info(src, ChatHelper.formattedKey("cmd.block_add_already", id, cat.displayName()));
                return 1;
            }
            com.customblocks.core.CategoryManager.assignBlock(id, catKey);
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.block_add_single", id, cat.displayName()));
            return 1;
        }
    }

    private static int cmdBulkBlockAdd(ServerCommandSource src, String rawCat, String rawIds) {
        String catKey = sanitize(rawCat);
        com.customblocks.core.Category cat = com.customblocks.core.CategoryManager.getCategory(catKey);
        if (cat == null) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.category_not_found", catKey));
            return 0;
        }

        java.util.LinkedHashSet<String> tokens = new java.util.LinkedHashSet<>();
        for (String token : (rawIds == null ? "" : rawIds).split("[,\\s]+")) {
            if (!token.isBlank()) tokens.add(sanitize(token));
        }
        if (tokens.isEmpty()) {
            ChatHelper.warn(src, ChatHelper.formattedKey("cmd.bulk_assign_example", catKey));
            return 0;
        }

        java.util.UUID uuid = src.getPlayer() != null ? src.getPlayer().getUuid() : null;
        com.customblocks.core.UndoManager.CategoryUndoEntry snap =
            com.customblocks.core.UndoManager.captureCategorySnapshot(
                "bulkblockadd " + tokens.size() + " -> " + cat.displayName(), uuid);

        java.util.List<String> invalid = new java.util.ArrayList<>();
        java.util.List<String> already = new java.util.ArrayList<>();
        int added = 0;
        for (String id : tokens) {
            if (!SlotManager.hasId(id)) {
                invalid.add(id);
                continue;
            }
            if (com.customblocks.core.CategoryManager.getCategoriesForBlock(id).contains(cat.key())) {
                already.add(id);
                continue;
            }
            com.customblocks.core.CategoryManager.assignBlock(id, cat.key());
            added++;
        }
        if (added > 0) com.customblocks.core.UndoManager.pushCategoryUndo(snap);

        ChatHelper.success(src, ChatHelper.formattedKey("cmd.bulk_assign_complete", added, already.size(), invalid.size()));
        if (!already.isEmpty()) {
            int n = Math.min(6, already.size());
            String alreadyDetail = String.join(", ", already.subList(0, n))
                + (already.size() > n ? " ... +" + (already.size() - n) + " more" : "");
            ChatHelper.warn(src, ChatHelper.formattedKey("cmd.bulk_assign_already", cat.displayName(), alreadyDetail));
        }
        if (!invalid.isEmpty()) {
            int n = Math.min(6, invalid.size());
            String invalidDetail = String.join(", ", invalid.subList(0, n))
                + (invalid.size() > n ? " ... +" + (invalid.size() - n) + " more" : "");
            ChatHelper.warn(src, ChatHelper.formattedKey("cmd.bulk_assign_skipped", invalidDetail));
        }
        return added > 0 ? 1 : 0;
    }

    private static int cmdGiveDisplayBlock(ServerCommandSource src, String rawCat) {
        String catKey = sanitize(rawCat);
        com.customblocks.core.Category cat = com.customblocks.core.CategoryManager.getCategory(catKey);
        if (cat == null) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.category_not_found", catKey));
            return 0;
        }
        ServerPlayerEntity p = src.getPlayer();
        if (p == null) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.player_only_gui")); return 0; }
        if (!cat.displayBlockEnabled()) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.category_display_disabled"));
            return 0;
        }
        ItemStack stack = com.customblocks.core.CategoryDisplayBlockManager.createDisplayBlockStack(cat);
        if (!p.getInventory().insertStack(stack)) p.dropItem(stack, false);
        src.sendMessage(ChatHelper.rawPrefixed(ChatHelper.formattedKey("cmd.category_display_given", cat.displayName())));
        return 1;
    }

    private static int cmdGiveCategory(ServerCommandSource src, String rawCat) {
        String catKey = sanitize(rawCat);
        com.customblocks.core.Category cat = com.customblocks.core.CategoryManager.getCategory(catKey);
        if (cat == null) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.category_not_found", catKey));
            return 0;
        }
        ServerPlayerEntity p = src.getPlayer();
        if (p == null) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.player_only_gui"));
            return 0;
        }
        java.util.List<SlotData> blocks = com.customblocks.core.CategoryManager.getBlocksInCategory(catKey);
        if (blocks.isEmpty()) {
            ChatHelper.info(src, ChatHelper.formattedKey("cmd.give_category_empty", cat.displayName()));
            return 1;
        }
        int given = 0;
        for (SlotData d : blocks) {
            SlotBlock.SlotItem item = CustomBlocksMod.safeSlotItem(d.index);
            if (item != null) {
                p.getInventory().insertStack(new ItemStack(item, 1));
                given++;
            }
        }
        ChatHelper.success(src, ChatHelper.formattedKey("cmd.give_category_done", given, cat.displayName()));
        return 1;
    }

    private static int cmdExportCategory(ServerCommandSource src, String rawCat) {
        String catKey = sanitize(rawCat);
        com.customblocks.core.Category cat = com.customblocks.core.CategoryManager.getCategory(catKey);
        if (cat == null) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.category_not_found", catKey));
            return 0;
        }
        File dir = new File("config/customblocks"); dir.mkdirs();
        File out = new File(dir, "export_cat_" + catKey + ".json");
        try {
            com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            com.google.gson.JsonObject catObj = new com.google.gson.JsonObject();
            catObj.addProperty("key", cat.key());
            catObj.addProperty("displayName", cat.displayName());
            catObj.addProperty("iconItem", cat.iconItem());
            if (cat.iconCustomBlockId() != null) catObj.addProperty("iconCustomBlockId", cat.iconCustomBlockId());
            if (cat.color() != null) catObj.addProperty("color", cat.color());
            if (cat.badge() != null) catObj.addProperty("badge", cat.badge());
            if (cat.badgeColor() != null) catObj.addProperty("badgeColor", cat.badgeColor());
            if (cat.parentKey() != null) catObj.addProperty("parentKey", cat.parentKey());
            catObj.addProperty("hidden", cat.hidden());
            catObj.addProperty("locked", cat.locked());
            catObj.addProperty("isDefault", cat.isDefault());
            if (cat.lorePrefix() != null) catObj.addProperty("lorePrefix", cat.lorePrefix());
            if (cat.lorePrefixPosition() != null) catObj.addProperty("lorePrefixPosition", cat.lorePrefixPosition());
            if (cat.subcategoryIndicator() != null) catObj.addProperty("subcategoryIndicator", cat.subcategoryIndicator());
            catObj.addProperty("displayBlockEnabled", cat.displayBlockEnabled());
            if (cat.displayBlockType() != null) catObj.addProperty("displayBlockType", cat.displayBlockType());
            if (cat.badgeOverflowMode() != null) catObj.addProperty("badgeOverflowMode", cat.badgeOverflowMode());
            if (cat.colorPlacement() != null) catObj.addProperty("colorPlacement", cat.colorPlacement());
            catObj.addProperty("sortOrder", cat.sortOrder());
            if (cat.description() != null) catObj.addProperty("description", cat.description());
            root.add("category", catObj);
            
            java.util.List<SlotData> blocks = com.customblocks.core.CategoryManager.getBlocksInCategory(catKey);
            com.google.gson.JsonArray blocksArr = new com.google.gson.JsonArray();
            for (SlotData d : blocks) {
                com.google.gson.JsonObject e = new com.google.gson.JsonObject();
                e.addProperty("id", d.customId);
                e.addProperty("displayName", d.displayName);
                e.addProperty("lightLevel", d.lightLevel);
                e.addProperty("hardness", d.hardness);
                e.addProperty("soundType", d.soundType);
                e.addProperty("animated", d.isAnimated());
                blocksArr.add(e);
            }
            root.add("blocks", blocksArr);
            root.addProperty("totalBlocks", blocks.size());
            root.addProperty("exportedAt", System.currentTimeMillis());
            try (java.io.FileWriter fw = new java.io.FileWriter(out, java.nio.charset.StandardCharsets.UTF_8)) {
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root, fw);
            }
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.export_category_done",
                cat.displayName(), blocks.size(), out.getName()));
        } catch (Exception e) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.export_failed", e.getMessage()));
        }
        return 1;
    }

    private static int cmdExportAll(ServerCommandSource src) {
        File dir = new File("config/customblocks"); dir.mkdirs();
        File out = new File(dir, "export_all_categories.json");
        try {
            com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            com.google.gson.JsonArray catsArr = new com.google.gson.JsonArray();
            for (com.customblocks.core.Category cat : com.customblocks.core.CategoryManager.getAllCategories()) {
                com.google.gson.JsonObject catObj = new com.google.gson.JsonObject();
                catObj.addProperty("key", cat.key());
                catObj.addProperty("displayName", cat.displayName());
                catObj.addProperty("iconItem", cat.iconItem());
                if (cat.iconCustomBlockId() != null) catObj.addProperty("iconCustomBlockId", cat.iconCustomBlockId());
                if (cat.color() != null) catObj.addProperty("color", cat.color());
                if (cat.badge() != null) catObj.addProperty("badge", cat.badge());
                if (cat.badgeColor() != null) catObj.addProperty("badgeColor", cat.badgeColor());
                if (cat.parentKey() != null) catObj.addProperty("parentKey", cat.parentKey());
                catObj.addProperty("hidden", cat.hidden());
                catObj.addProperty("locked", cat.locked());
                catObj.addProperty("isDefault", cat.isDefault());
                if (cat.lorePrefix() != null) catObj.addProperty("lorePrefix", cat.lorePrefix());
                if (cat.lorePrefixPosition() != null) catObj.addProperty("lorePrefixPosition", cat.lorePrefixPosition());
                if (cat.subcategoryIndicator() != null) catObj.addProperty("subcategoryIndicator", cat.subcategoryIndicator());
                catObj.addProperty("displayBlockEnabled", cat.displayBlockEnabled());
                if (cat.displayBlockType() != null) catObj.addProperty("displayBlockType", cat.displayBlockType());
                if (cat.badgeOverflowMode() != null) catObj.addProperty("badgeOverflowMode", cat.badgeOverflowMode());
                if (cat.colorPlacement() != null) catObj.addProperty("colorPlacement", cat.colorPlacement());
                catObj.addProperty("sortOrder", cat.sortOrder());
                if (cat.description() != null) catObj.addProperty("description", cat.description());
                catsArr.add(catObj);
            }
            root.add("categories", catsArr);
            root.addProperty("exportedAt", System.currentTimeMillis());
            try (java.io.FileWriter fw = new java.io.FileWriter(out, java.nio.charset.StandardCharsets.UTF_8)) {
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root, fw);
            }
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.export_all_categories_done", out.getName()));
        } catch (Exception e) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.export_failed", e.getMessage()));
        }
        return 1;
    }

    private static int cmdShareCategory(ServerCommandSource src, String rawCat) {
        String catKey = sanitize(rawCat);
        com.customblocks.core.Category cat = com.customblocks.core.CategoryManager.getCategory(catKey);
        if (cat == null) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.category_not_found", catKey));
            return 0;
        }
        
        if (!com.customblocks.CustomBlocksConfig.isCloudShareEnabled()) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.cloud_share_disabled"));
            return 0;
        }
        
        ChatHelper.info(src, ChatHelper.formattedKey("cmd.cloud_preparing_upload"));
        
        try {
            com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            com.google.gson.JsonObject catObj = new com.google.gson.JsonObject();
            catObj.addProperty("key", cat.key());
            catObj.addProperty("displayName", cat.displayName());
            catObj.addProperty("iconItem", cat.iconItem());
            if (cat.iconCustomBlockId() != null) catObj.addProperty("iconCustomBlockId", cat.iconCustomBlockId());
            if (cat.color() != null) catObj.addProperty("color", cat.color());
            if (cat.badge() != null) catObj.addProperty("badge", cat.badge());
            if (cat.badgeColor() != null) catObj.addProperty("badgeColor", cat.badgeColor());
            if (cat.parentKey() != null) catObj.addProperty("parentKey", cat.parentKey());
            catObj.addProperty("hidden", cat.hidden());
            catObj.addProperty("locked", cat.locked());
            catObj.addProperty("isDefault", cat.isDefault());
            if (cat.lorePrefix() != null) catObj.addProperty("lorePrefix", cat.lorePrefix());
            if (cat.lorePrefixPosition() != null) catObj.addProperty("lorePrefixPosition", cat.lorePrefixPosition());
            if (cat.subcategoryIndicator() != null) catObj.addProperty("subcategoryIndicator", cat.subcategoryIndicator());
            catObj.addProperty("displayBlockEnabled", cat.displayBlockEnabled());
            if (cat.displayBlockType() != null) catObj.addProperty("displayBlockType", cat.displayBlockType());
            if (cat.badgeOverflowMode() != null) catObj.addProperty("badgeOverflowMode", cat.badgeOverflowMode());
            if (cat.colorPlacement() != null) catObj.addProperty("colorPlacement", cat.colorPlacement());
            catObj.addProperty("sortOrder", cat.sortOrder());
            if (cat.description() != null) catObj.addProperty("description", cat.description());
            root.add("category", catObj);
            
            java.util.List<SlotData> blocks = com.customblocks.core.CategoryManager.getBlocksInCategory(catKey);
            com.google.gson.JsonArray blocksArr = new com.google.gson.JsonArray();
            for (SlotData d : blocks) {
                com.google.gson.JsonObject e = new com.google.gson.JsonObject();
                e.addProperty("id", d.customId);
                e.addProperty("displayName", d.displayName);
                e.addProperty("lightLevel", d.lightLevel);
                e.addProperty("hardness", d.hardness);
                e.addProperty("soundType", d.soundType);
                e.addProperty("animated", d.isAnimated());
                // In a real scenario we'd attach base64 textures here too,
                // but let's keep it compact for the example backend.
                blocksArr.add(e);
            }
            root.add("blocks", blocksArr);
            root.addProperty("totalBlocks", blocks.size());
            
            String jsonPayload = new com.google.gson.Gson().toJson(root);

            thread(() -> {
                try {
                    java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(com.customblocks.CustomBlocksConfig.normalizedCloudShareUrl() + "/share"))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonPayload))
                        .build();
                    java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                    
                    if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                        com.google.gson.JsonObject res = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
                        String code = res.has("code") ? res.get("code").getAsString() : "BC#???";
                        src.getServer().execute(() -> {
                            net.minecraft.text.MutableText line = ChatHelper.rawPrefixed(ChatHelper.formattedKey("cmd.cloud_share_success", code))
                                .styled(s -> s.withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                                    .withHoverEvent(new net.minecraft.text.HoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT,
                                        Text.literal(ChatHelper.formattedKey("cmd.click_copy_hover")))));
                            src.sendMessage(line);
                        });
                    } else {
                        src.getServer().execute(() -> {
                            ChatHelper.error(src, ChatHelper.formattedKey("cmd.cloud_upload_failed", resp.statusCode()));
                        });
                    }
                } catch (Exception e) {
                    src.getServer().execute(() -> {
                        ChatHelper.error(src, ChatHelper.formattedKey("cmd.cloud_error_generic", e.getMessage()));
                    });
                }
            });
        } catch (Exception e) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.export_failed", e.getMessage()));
        }
        return 1;
    }

    private static int cmdImportCategory(ServerCommandSource src, String code) {
        if (!com.customblocks.CustomBlocksConfig.isCloudShareEnabled()) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.cloud_share_disabled"));
            return 0;
        }
        
        ChatHelper.info(src, ChatHelper.formattedKey("cmd.cloud_downloading_category", code));
        
        thread(() -> {
            try {
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(com.customblocks.CustomBlocksConfig.normalizedCloudShareUrl() + "/import/" + code))
                    .GET()
                    .build();
                java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
                    src.getServer().execute(() -> {
                        try {
                            if (!root.has("category")) {
                                ChatHelper.error(src, ChatHelper.formattedKey("cmd.cloud_invalid_payload"));
                                return;
                            }
                            com.google.gson.JsonObject catObj = root.getAsJsonObject("category");
                            String catKey = catObj.get("key").getAsString();
                            String displayName = catObj.has("displayName") ? catObj.get("displayName").getAsString() : catKey;
                            
                            // Check if category already exists
                            com.customblocks.core.Category existingCat = com.customblocks.core.CategoryManager.getCategory(catKey);
                            if (existingCat != null) {
                                ChatHelper.info(src, ChatHelper.formattedKey("cmd.cloud_category_conflict", catKey));
                                ServerPlayerEntity player = src.getPlayer();
                                if (player != null) {
                                    com.customblocks.gui.GuiManager.PENDING_IMPORTS.put(player.getUuid(), root);
                                    com.customblocks.gui.GuiManager.openImportConflictGui(player, 0);
                                    return;
                                }
                            }
                            
                            com.customblocks.core.Category cat = com.customblocks.core.Category.create(displayName);
                            if (catObj.has("iconItem")) cat = cat.withIconItem(catObj.get("iconItem").getAsString());
                            if (catObj.has("color")) cat = cat.withColor(catObj.get("color").getAsString());
                            if (catObj.has("badge")) cat = cat.withBadge(catObj.get("badge").getAsString());
                            
                            com.customblocks.core.CategoryManager.addCategory(cat);
                            
                            int imported = 0;
                            if (root.has("blocks")) {
                                com.google.gson.JsonArray blocksArr = root.getAsJsonArray("blocks");
                                for (com.google.gson.JsonElement el : blocksArr) {
                                    com.google.gson.JsonObject bObj = el.getAsJsonObject();
                                    String bId = bObj.get("id").getAsString();
                                    
                                    if (!SlotManager.hasId(bId)) {
                                        // We don't have textures via this simple cloud payload, but we assign ID
                                        // A real import would download textures too.
                                        ChatHelper.info(src, ChatHelper.formattedKey("cmd.cloud_skip_block_simple", bId));
                                    } else {
                                        com.customblocks.core.CategoryManager.assignBlock(bId, cat.key());
                                        imported++;
                                    }
                                }
                            }
                            ChatHelper.success(src, ChatHelper.formattedKey("cmd.cloud_import_assignments_done", displayName, imported));
                        } catch (Exception ex) {
                            ChatHelper.error(src, ChatHelper.formattedKey("cmd.cloud_parse_error", ex.getMessage()));
                        }
                    });
                } else {
                    src.getServer().execute(() -> {
                        ChatHelper.error(src, ChatHelper.formattedKey("cmd.cloud_download_failed", resp.statusCode()));
                    });
                }
            } catch (Exception e) {
                src.getServer().execute(() -> {
                    ChatHelper.error(src, ChatHelper.formattedKey("cmd.cloud_unavailable"));
                    ServerPlayerEntity p = src.getPlayer();
                    if (p != null) {
                        p.playSound(net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 1.0f, 1.0f);
                        p.getServerWorld().spawnParticles(net.minecraft.particle.ParticleTypes.SMOKE, p.getX(), p.getY() + 1, p.getZ(), 10, 0.5, 0.5, 0.5, 0.05);
                    }
                });
            }
        });
        return 1;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int usage(ServerCommandSource src, String cmd) {
        String msg = switch (cmd) {
            case "create"       -> "create <id> <name> [size] <url>";
            case "bulkdelete"    -> "bulkdelete <id1> <id2> ...  — delete multiple blocks";
            case "delete"       -> "delete <id>";
            case "rename"       -> "rename <id> <newname>";
            case "reid"         -> "reid <id> <newid>  — change block ID";
            case "retexture"    -> "retexture <id> [size] <url>";
            case "give"         -> "give <id> [amount] [player]";
            case "dupe"         -> "dupe|duplicate <sourceId>  — clone with auto new id";
            case "blockadd"     -> "blockadd <id> <category>";
            case "givecategory" -> "givecategory <category>";
            case "givedisplayblock" -> "givedisplayblock <category>";
            case "exportcategory" -> "exportcategory <category>";
            case "sharecategory" -> "sharecategory <category>";
            case "importcategory" -> "importcategory <code>";
            case "exportblock"  -> "exportblock <id>";
            case "importblock"  -> "importblock <code>";
            case "favorite"     -> "favorite [id]  — star/unstar block; omit id to list favorites";
            case "menu"         -> "menu  — open the main dashboard";
            case "welcome"      -> "welcome  — open the welcome and voice setup screen";
            case "voice"        -> "voice [friendly|professional|royal|minimal|arabic|silly]";
            case "diagnostics"  -> "diagnostics  — export a support ZIP";
            case "setglow"      -> "setglow <id> <0-15>";
            case "sethardness"  -> "sethardness <id> <val>";
            case "setsound"     -> "setsound <id> <type>";
            case "settabicon"   -> "settabicon [url]  — GUI if url omitted";
            case "setface"      -> "setface <id> <face> [size] <url>";
            case "clearface"    -> "clearface <id> <face>";
            case "givesquare"   -> "square <black|yellow|green>";
            case "givetriangle" -> "triangle <black|yellow|green>";
            case "clearallfaces"-> "clearallfaces <id>";
            case "resize"       -> "resize <id> <16-256>";
            case "editor" -> "editor [id]";
            case "setshape"     -> "setshape <id> <preset|coords>";
            case "addshape"     -> "addshape <id> <coords>";
            case "removeshape"  -> "removeshape <id> <boxIndex>";
            case "clearshape"   -> "clearshape <id>";
            case "setcollision" -> "setcollision <id> <on|off>";
            case "shapeeditor"  -> "shapeeditor <id>";
            case "facechangegui"-> "facechangegui <id>";
            case "square"       -> "square <black|yellow|green>";
            case "triangle"     -> "triangle <black|yellow|green>";
            case "customtriangle" -> "customtriangle <#RRGGBB>  — gives matching custom square + triangle";
            case "bulkrecolor" -> "bulkrecolor <green|yellow|black> [scope] [--exclude=...] [--apply]  — no args opens wizard";
            case "bulkblockadd" -> "bulkblockadd <category> <id1> <id2> ...  — no args opens picker";
            case "dress"        -> "dress <id> <cracked|mossy|weathered|glowing|frosted>";
            case "gradient"     -> "gradient <fromId> <toId> <steps> [--preview|--apply]";
            case "search"       -> "search <query>";
            case "note"         -> "note <id> [text|clear]";
            case "exportpng"    -> "exportpng <id>";
            case "showcase"     -> "showcase <id>";
            case "macro"        -> "macro record <name> | stop | run <name> | list | show <name> | add <name> <cmd> | delete <name>";
            case "market"       -> "market  — browse the Cloud Vault marketplace and import shared blocks";
            case "resume"       -> "resume  — reopen last ESC-saved GUI (bulk ops, search, editors, category flows, import conflict, recover, undo history, …)";
            default -> "help";
        };
        ChatHelper.warn(src, ChatHelper.formattedKey("cmd.usage", msg));
        return 0;
    }

    private static Text notFound(String id) {
        return Text.literal(ChatHelper.formattedKey("cmd.id_not_found_inline", id));
    }

    private static String sanitize(String id) {
        return id.toLowerCase().replaceAll("[^a-z0-9_\\-]", "_");
    }

    private static void thread(Runnable r) {
        EXECUTOR.submit(r);
    }

    /**
     * Trigger light updates for all blocks of a given slot in a 32-block radius
     * around every online player. Runs off the main thread.
     */
    private static void triggerGlowUpdate(MinecraftServer server, int slotIndex) {
        String slotKey = "slot_" + slotIndex;
        thread(() -> {
            server.execute(() -> {
                for (net.minecraft.server.network.ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    net.minecraft.server.world.ServerWorld world = p.getServerWorld();
                    int cx = p.getBlockX(), cy = p.getBlockY(), cz = p.getBlockZ();
                    net.minecraft.util.math.BlockPos.Mutable mpos = new net.minecraft.util.math.BlockPos.Mutable();
                    int radius = 32;
                    for (int x = cx - radius; x <= cx + radius; x++) {
                        for (int y = Math.max(world.getBottomY(), cy - radius);
                             y <= Math.min(world.getTopY() - 1, cy + radius); y++) {
                            for (int z = cz - radius; z <= cz + radius; z++) {
                                mpos.set(x, y, z);
                                net.minecraft.block.BlockState st = world.getBlockState(mpos);
                                if (st.getBlock() instanceof com.customblocks.block.SlotBlock sb
                                        && sb.getSlotKey().equals(slotKey)) {
                                    world.getLightingProvider().checkBlock(mpos.toImmutable());
                                }
                            }
                        }
                    }
                }
            });
        });
    }

    private static java.util.UUID getPlayerUuid(net.minecraft.server.command.ServerCommandSource src) {
        var p = src.getPlayer();
        return p != null ? p.getUuid() : null;
    }

    private static String getPlayerName(net.minecraft.server.command.ServerCommandSource src) {
        var p = src.getPlayer();
        return p != null ? p.getName().getString() : "Console";
    }

    // ── Phase H₁: Export PNG ─────────────────────────────────────────────────
    private static int cmdExportPng(ServerCommandSource src, String rawId) {
        final String id = sanitize(rawId);
        SlotData d = SlotManager.getById(id);
        if (d == null) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.block_not_found", id)); return 0; }
        if (d.texture == null || d.texture.length == 0) {
            ChatHelper.error(src, "§cBlock §f" + id + " §chas no texture data to export.");
            return 0;
        }
        thread(() -> {
            try {
                java.nio.file.Path exportDir = java.nio.file.Path.of("config", "customblocks", "exports");
                java.nio.file.Files.createDirectories(exportDir);
                java.nio.file.Path outFile = exportDir.resolve(id + ".png");
                java.nio.file.Files.write(outFile, d.texture);
                src.getServer().execute(() -> {
                    ChatHelper.success(src, "§aExported §f" + id + " §ato §f" + outFile);
                    com.customblocks.core.HistoryTracker.record(getPlayerUuid(src), getPlayerName(src), "exported", id);
                });
            } catch (Exception e) {
                src.getServer().execute(() ->
                    ChatHelper.error(src, "§cExport failed: §f" + e.getMessage()));
            }
        });
        return 1;
    }

    // ── Phase H₁: Showcase (30s floating display) ─────────────────────────────
    private static int cmdShowcase(ServerCommandSource src, String rawId) {
        final String id = sanitize(rawId);
        SlotData d = SlotManager.getById(id);
        if (d == null) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.block_not_found", id)); return 0; }
        var player = src.getPlayer();
        if (player == null) { ChatHelper.error(src, "§cThis command requires a player."); return 0; }

        net.minecraft.server.world.ServerWorld world = player.getServerWorld();
        net.minecraft.item.Item blockItem = com.customblocks.CustomBlocksMod.safeSlotItem(d.index);
        if (blockItem == null) {
            ChatHelper.error(src, "§cCannot showcase — block item not registered.");
            return 0;
        }

        // Use an invisible ArmorStand with the block on its head for reliable display
        net.minecraft.entity.decoration.ArmorStandEntity stand =
            new net.minecraft.entity.decoration.ArmorStandEntity(world,
                player.getX(), player.getY() + 1.5, player.getZ());
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setInvulnerable(true);
        stand.setCustomName(Text.literal("§e§l" + d.displayName + " §7§o(showcase)"));
        stand.setCustomNameVisible(true);
        stand.equipStack(net.minecraft.entity.EquipmentSlot.HEAD,
            new net.minecraft.item.ItemStack(blockItem));

        world.spawnEntity(stand);

        // Remove after 30 seconds
        final var entityRef = stand;
        java.util.concurrent.CompletableFuture.delayedExecutor(30, java.util.concurrent.TimeUnit.SECONDS)
            .execute(() -> src.getServer().execute(() -> {
                if (entityRef.isAlive()) {
                    entityRef.discard();
                }
            }));

        // Sensory feedback
        double x = player.getX(), y = player.getY() + 2.5, z = player.getZ();
        world.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD,
            x, y + 0.5, z, 16, 0.3, 0.3, 0.3, 0.05);
        player.playSound(net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7f, 1.0f);

        ChatHelper.success(src, "§aShowcasing §f" + d.displayName + " §afor 30 seconds.");
        return 1;
    }
}
