package com.customblocks.command;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.customblocks.CustomBlocksMod;
import com.customblocks.CustomBlocksConfig;
import com.customblocks.core.ColorVariantService;
import com.customblocks.gui.FeedbackHelper;
import com.customblocks.gui.GuiManager;
import com.customblocks.gui.ColorLibrary;
import com.customblocks.ImageProcessor;
import com.customblocks.core.FavoritesManager;
import com.customblocks.core.IncidentRecorder;
import com.customblocks.core.PlayerPaletteManager;
import com.customblocks.core.SlotData;
import com.customblocks.core.SlotManager;
import com.customblocks.core.UndoManager;
import com.customblocks.core.FirstUseHints;
import com.customblocks.item.ColorTriangleItem;
import com.customblocks.network.NetworkManager;
import com.customblocks.command.PermissionHelper;
import com.customblocks.block.SlotBlock;
import com.customblocks.network.SlotUpdatePayload;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.BoolArgumentType;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CustomBlockCommand {
    // 7.9 — bounded queue (max 20) with CallerRunsPolicy prevents unbounded accumulation
    private static final ExecutorService EXECUTOR = new java.util.concurrent.ThreadPoolExecutor(
        2, 2, 60L, java.util.concurrent.TimeUnit.SECONDS,
        new java.util.concurrent.LinkedBlockingQueue<>(20),
        new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(5))
        .build();

    // 1.38 — alphanumeric only; !@#$%& removed: & breaks URL query params, % is a Windows batch var,
    // # truncates URLs as a fragment, and % & are illegal in Windows filenames.
    private static final String SHARE_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

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

    // V4-36 — block suggestions include display name as hover tooltip
    private static final SuggestionProvider<ServerCommandSource> BLOCK_SUGGESTIONS =
            (ctx, builder) -> {
                for (com.customblocks.core.SlotData d : SlotManager.allSlots()) {
                    builder.suggest(d.customId, net.minecraft.text.Text.literal(d.displayName));
                }
                return builder.buildFuture();
            };

    private static final String[] VALID_SOUNDS = {
        "stone","wood","grass","metal","glass","sand","wool",
        "gravel","snow","dirt","coral","bamboo","nether_brick","ice","honey","bone","slime"
    };
    // V4-36 — sound suggestions include category description as tooltip
    private static final java.util.Map<String, String> SOUND_DESCS = java.util.Map.ofEntries(
        java.util.Map.entry("stone",       "Hard stone / cobblestone"),
        java.util.Map.entry("wood",        "Wooden planks / logs"),
        java.util.Map.entry("grass",       "Grass / leaves"),
        java.util.Map.entry("metal",       "Iron / chain / anvil"),
        java.util.Map.entry("glass",       "Glass blocks / panes"),
        java.util.Map.entry("sand",        "Sand / gravel / soul sand"),
        java.util.Map.entry("wool",        "Wool / carpet"),
        java.util.Map.entry("gravel",      "Gravel"),
        java.util.Map.entry("snow",        "Snow block / powder"),
        java.util.Map.entry("dirt",        "Dirt / path"),
        java.util.Map.entry("coral",       "Coral / wet materials"),
        java.util.Map.entry("bamboo",      "Bamboo / scaffolding"),
        java.util.Map.entry("nether_brick","Nether brick"),
        java.util.Map.entry("ice",         "Ice / packed ice"),
        java.util.Map.entry("honey",       "Honey block"),
        java.util.Map.entry("bone",        "Bone block"),
        java.util.Map.entry("slime",       "Slime block")
    );
    private static final SuggestionProvider<ServerCommandSource> SOUND_SUGGESTIONS =
            (ctx, builder) -> {
                for (String s : VALID_SOUNDS) {
                    String desc = SOUND_DESCS.getOrDefault(s, s);
                    builder.suggest(s, net.minecraft.text.Text.literal(desc));
                }
                return builder.buildFuture();
            };

    private static final SuggestionProvider<ServerCommandSource> CATEGORY_SUGGESTIONS =
            (ctx, builder) -> { for (com.customblocks.core.Category cat : com.customblocks.core.CategoryManager.getAllCategories()) builder.suggest(cat.key()); return builder.buildFuture(); };

    private static final SuggestionProvider<ServerCommandSource> FACE_SUGGESTIONS =
            (ctx, builder) -> { for (String f : SlotData.FACE_KEYS) builder.suggest(f); return builder.buildFuture(); };

    private static final java.util.Map<String, String> SHAPE_DESCS = java.util.Map.ofEntries(
        java.util.Map.entry("full",        "Full 1x1x1 cube"),
        java.util.Map.entry("slab",        "Bottom half-slab"),
        java.util.Map.entry("thin",        "Thin slab (1/8 height)"),
        java.util.Map.entry("carpet",      "Carpet (1/16 height)"),
        java.util.Map.entry("wall",        "Centered pillar"),
        java.util.Map.entry("comparator",  "Small 3/4 cube"),
        java.util.Map.entry("small",       "Small 1/2 cube centered"),
        java.util.Map.entry("micro",       "Tiny centered cube"),
        java.util.Map.entry("pane",        "Thin vertical pane"),
        java.util.Map.entry("trapdoor",    "Trapdoor-style thin slab"),
        java.util.Map.entry("fence",       "Fence-style post"),
        java.util.Map.entry("stairs",      "Stair-shaped cutout"),
        java.util.Map.entry("cross",       "Cross / X shape")
    );
    private static final SuggestionProvider<ServerCommandSource> SHAPE_SUGGESTIONS =
            (ctx, builder) -> {
                for (String k : SlotManager.SHAPE_PRESETS.keySet()) {
                    String desc = SHAPE_DESCS.getOrDefault(k, k);
                    builder.suggest(k, net.minecraft.text.Text.literal(desc));
                }
                return builder.buildFuture();
            };

    private static final SuggestionProvider<ServerCommandSource> DRESS_OVERLAY_SUGGESTIONS =
            (ctx, builder) -> { for (String overlay : ColorVariantService.dressOverlays()) builder.suggest(overlay); return builder.buildFuture(); };

    private static final SuggestionProvider<ServerCommandSource> GRADIENT_MODE_SUGGESTIONS =
            (ctx, builder) -> { builder.suggest("--preview"); builder.suggest("--apply"); return builder.buildFuture(); };
    private static final SuggestionProvider<ServerCommandSource> VOICE_MODE_SUGGESTIONS =
            (ctx, builder) -> {
                builder.suggest("friendly",      net.minecraft.text.Text.literal("Warm, encouraging tone"));
                builder.suggest("professional",  net.minecraft.text.Text.literal("Formal, precise language"));
                builder.suggest("royal",         net.minecraft.text.Text.literal("Regal, poetic style"));
                builder.suggest("minimal",       net.minecraft.text.Text.literal("Short, no-frills responses"));
                builder.suggest("arabic",        net.minecraft.text.Text.literal("Arabic language responses"));
                builder.suggest("silly",         net.minecraft.text.Text.literal("Fun, playful humor"));
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
                    // V4-09: no-args → prompt for ID (starts guided creation flow)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) return usage(ctx.getSource(), "create");
                        GuiManager.openShortInputPrompt(p,
                            new GuiManager.PendingInput(GuiManager.InputAction.CREATE_ID, null, null, null, null, 0),
                            "§6New Block ID",
                            new net.minecraft.item.ItemStack(net.minecraft.item.Items.COMMAND_BLOCK),
                            "");
                        return 1;
                    })
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
                        .suggests((ctx, b) -> { b.suggest("green"); b.suggest("yellow"); b.suggest("black"); b.suggest("red"); return b.buildFuture(); })
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
                        ChatHelper.warn(ctx.getSource(), "/cb bulkcolor is deprecated. Use /cb bulkrecolor instead.");
                        GuiManager.openBulkRecolorWizard(p, 0);
                        return 1;
                    })
                    .then(CommandManager.argument("color", StringArgumentType.word())
                        .suggests((ctx, b) -> { b.suggest("green"); b.suggest("yellow"); b.suggest("black"); b.suggest("red"); return b.buildFuture(); })
                        .executes(ctx -> {
                            ChatHelper.warn(ctx.getSource(), "/cb bulkcolor is deprecated. Use /cb bulkrecolor instead.");
                            return cmdBulkRecolor(ctx.getSource(),
                                StringArgumentType.getString(ctx, "color"),
                                "all",
                                false);
                        })
                        .then(CommandManager.argument("scope", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                ChatHelper.warn(ctx.getSource(), "/cb bulkcolor is deprecated. Use /cb bulkrecolor instead.");
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
                    // V4-19: /cb delete # — delete block player is looking at
                    .then(CommandManager.literal("#")
                        .executes(ctx -> {
                            ServerPlayerEntity p = ctx.getSource().getPlayer();
                            if (p == null) { ChatHelper.error(ctx.getSource(), "Player only."); return 0; }
                            net.minecraft.util.hit.HitResult hit = p.raycast(10.0, 0.0f, false);
                            if (!(hit instanceof net.minecraft.util.hit.BlockHitResult bhr)) {
                                ChatHelper.error(ctx.getSource(), "Not looking at a block."); return 0;
                            }
                            net.minecraft.block.BlockState bs = p.getWorld().getBlockState(bhr.getBlockPos());
                            if (!(bs.getBlock() instanceof com.customblocks.block.SlotBlock sb)) {
                                ChatHelper.error(ctx.getSource(), "Not a custom block."); return 0;
                            }
                            SlotData d = SlotManager.getBySlot(sb.getSlotKey());
                            if (d == null) { ChatHelper.error(ctx.getSource(), "Not a custom block."); return 0; }
                            return cmdDelete(ctx.getSource(), d.customId);
                        }))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdDelete(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))

                // ── bulkdelete ──────────────────────────────────────────────
                .then(CommandManager.literal("bulkdelete")
                    .requires(PermissionHelper::canBulk)
                    .executes(ctx -> {
                        // V4-21: no-args opens bulk delete GUI
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) { GuiManager.openBulkDelete(p, 0); return 1; }
                        return usage(ctx.getSource(), "bulkdelete");
                    })
                    .then(CommandManager.argument("ids", StringArgumentType.greedyString())
                        .suggests(MULTI_BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdBulkDelete(ctx.getSource(),
                            StringArgumentType.getString(ctx, "ids")))))

                // ── Phase 2 bulk hub ─────────────────────────────────────────
                .then(CommandManager.literal("bulk")
                    .requires(PermissionHelper::canBulk)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) com.customblocks.gui.GuiManager.openBulkHub(p);
                        return 1;
                    }))
                .then(CommandManager.literal("bulkgui")
                    .requires(PermissionHelper::canBulk)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) com.customblocks.gui.GuiManager.openBulkHub(p);
                        return 1;
                    }))

                // ── bulkrename ───────────────────────────────────────────────
                .then(CommandManager.literal("bulkrename")
                    .requires(PermissionHelper::canBulk)
                    .executes(ctx -> usage(ctx.getSource(), "bulkrename"))
                    .then(CommandManager.argument("args", StringArgumentType.greedyString())
                        .executes(ctx -> cmdBulkRename(ctx.getSource(),
                            StringArgumentType.getString(ctx, "args")))))

                // ── bulkreid ─────────────────────────────────────────────────
                .then(CommandManager.literal("bulkreid")
                    .requires(PermissionHelper::canBulk)
                    .executes(ctx -> usage(ctx.getSource(), "bulkreid"))
                    .then(CommandManager.argument("args", StringArgumentType.greedyString())
                        .executes(ctx -> cmdBulkReId(ctx.getSource(),
                            StringArgumentType.getString(ctx, "args")))))

                // ── bulkproperty ─────────────────────────────────────────────
                .then(CommandManager.literal("bulkproperty")
                    .requires(PermissionHelper::canBulk)
                    .executes(ctx -> usage(ctx.getSource(), "bulkproperty"))
                    .then(CommandManager.argument("args", StringArgumentType.greedyString())
                        .executes(ctx -> cmdBulkProperty(ctx.getSource(),
                            StringArgumentType.getString(ctx, "args")))))

                // ── bulkexport ───────────────────────────────────────────────
                .then(CommandManager.literal("bulkexport")
                    .requires(PermissionHelper::canBulk)
                    .executes(ctx -> usage(ctx.getSource(), "bulkexport"))
                    .then(CommandManager.argument("scope", StringArgumentType.greedyString())
                        .executes(ctx -> cmdBulkExport(ctx.getSource(),
                            StringArgumentType.getString(ctx, "scope")))))

                // ── bulkmove ─────────────────────────────────────────────────
                .then(CommandManager.literal("bulkmove")
                    .requires(PermissionHelper::canBulk)
                    .executes(ctx -> usage(ctx.getSource(), "bulkmove"))
                    .then(CommandManager.argument("args", StringArgumentType.greedyString())
                        .executes(ctx -> cmdBulkMove(ctx.getSource(),
                            StringArgumentType.getString(ctx, "args")))))

                // ── bulkduplicate ────────────────────────────────────────────
                .then(CommandManager.literal("bulkduplicate")
                    .requires(PermissionHelper::canBulk)
                    .executes(ctx -> usage(ctx.getSource(), "bulkduplicate"))
                    .then(CommandManager.argument("args", StringArgumentType.greedyString())
                        .executes(ctx -> cmdBulkDuplicate(ctx.getSource(),
                            StringArgumentType.getString(ctx, "args")))))

                // ── bulklock / bulkunlock ─────────────────────────────────────
                .then(CommandManager.literal("bulklock")
                    .requires(PermissionHelper::canBulk)
                    .executes(ctx -> usage(ctx.getSource(), "bulklock"))
                    .then(CommandManager.argument("scope", StringArgumentType.greedyString())
                        .executes(ctx -> cmdBulkLock(ctx.getSource(),
                            StringArgumentType.getString(ctx, "scope"), true))))
                .then(CommandManager.literal("bulkunlock")
                    .requires(PermissionHelper::canBulk)
                    .executes(ctx -> usage(ctx.getSource(), "bulklock"))
                    .then(CommandManager.argument("scope", StringArgumentType.greedyString())
                        .executes(ctx -> cmdBulkLock(ctx.getSource(),
                            StringArgumentType.getString(ctx, "scope"), false))))

                // ── bulkfavorite / bulkunfavorite ────────────────────────────
                .then(CommandManager.literal("bulkfavorite")
                    .requires(PermissionHelper::canBulk)
                    .executes(ctx -> usage(ctx.getSource(), "bulkfavorite"))
                    .then(CommandManager.argument("scope", StringArgumentType.greedyString())
                        .executes(ctx -> cmdBulkFavorite(ctx.getSource(),
                            StringArgumentType.getString(ctx, "scope"), true))))
                .then(CommandManager.literal("bulkunfavorite")
                    .requires(PermissionHelper::canBulk)
                    .executes(ctx -> usage(ctx.getSource(), "bulkfavorite"))
                    .then(CommandManager.argument("scope", StringArgumentType.greedyString())
                        .executes(ctx -> cmdBulkFavorite(ctx.getSource(),
                            StringArgumentType.getString(ctx, "scope"), false))))

                // ── bulkshape ────────────────────────────────────────────────
                .then(CommandManager.literal("bulkshape")
                    .requires(PermissionHelper::canBulk)
                    .executes(ctx -> usage(ctx.getSource(), "bulkshape"))
                    .then(CommandManager.argument("args", StringArgumentType.greedyString())
                        .executes(ctx -> cmdBulkShape(ctx.getSource(),
                            StringArgumentType.getString(ctx, "args")))))

                // ── bulksound ────────────────────────────────────────────────
                .then(CommandManager.literal("bulksound")
                    .requires(PermissionHelper::canBulk)
                    .executes(ctx -> usage(ctx.getSource(), "bulksound"))
                    .then(CommandManager.argument("args", StringArgumentType.greedyString())
                        .executes(ctx -> cmdBulkSound(ctx.getSource(),
                            StringArgumentType.getString(ctx, "args")))))

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

                // ── swapid ──────────────────────────────────────────────────
                .then(CommandManager.literal("swapid")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "swapid"))
                    .then(CommandManager.argument("id1", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("id2", StringArgumentType.word())
                            .suggests(BLOCK_SUGGESTIONS)
                            .executes(ctx -> cmdSwapId(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id1"),
                                StringArgumentType.getString(ctx, "id2"))))))

                // ── swapname ────────────────────────────────────────────────
                .then(CommandManager.literal("swapname")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "swapname"))
                    .then(CommandManager.argument("id1", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("id2", StringArgumentType.word())
                            .suggests(BLOCK_SUGGESTIONS)
                            .executes(ctx -> cmdSwapName(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id1"),
                                StringArgumentType.getString(ctx, "id2"))))))

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
                        .then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
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

                // 1.30 — Dedicated unfavorite command (no accidental re-adding)
                .then(CommandManager.literal("unfavorite")
                    .requires(PermissionHelper::canFavorite)
                    .executes(ctx -> cmdUnfavorite(ctx.getSource(), null))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdUnfavorite(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))

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
                    .requires(PermissionHelper::canUse)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) GuiManager.openBrokenBlocks(p);
                        else ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only"));
                        return 1;
                    }))

                // V4-18: /cb deletedblocks — browse trash
                .then(CommandManager.literal("deletedblocks")
                    .requires(PermissionHelper::canUse)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) { GuiManager.openDeletedBlocksGui(p, 0); return 1; }
                        // Console: list trash
                        var trash = com.customblocks.core.TrashManager.list();
                        if (trash.isEmpty()) { ChatHelper.info(ctx.getSource(), "§7Trash is empty."); return 1; }
                        ChatHelper.info(ctx.getSource(), "§6Deleted blocks (" + trash.size() + "):");
                        for (var e : trash)
                            ChatHelper.info(ctx.getSource(), "  §f" + e.displayName() + " §8(§b" + e.originalId() + "§8) — deleted " + new java.util.Date(e.deletedAt()));
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
                        .executes(ctx -> cmdResizeInfo(ctx.getSource(), StringArgumentType.getString(ctx, "id")))
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
                    .then(CommandManager.literal("clear")
                        .requires(PermissionHelper::canUndo)
                        .executes(ctx -> cmdUndoClear(ctx.getSource())))
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

                // ── /cb snapshots (V4-43) ────────────────────────────────────
                .then(CommandManager.literal("snapshots")
                    .requires(PermissionHelper::canAdmin)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) { GuiManager.openSnapshotsGui(p, 0); return 1; }
                        // Console: list snapshots as text
                        var list = com.customblocks.core.SnapshotManager.list();
                        if (list.isEmpty()) { ChatHelper.info(ctx.getSource(), "§7No snapshots found."); return 1; }
                        ChatHelper.info(ctx.getSource(), "§6§lSnapshots (" + list.size() + "):");
                        for (var s : list) ChatHelper.info(ctx.getSource(), "  §f" + s.filename() + " §7— " + s.timestamp() + " §8(" + (s.sizeBytes()/1024) + " KB)");
                        return 1;
                    })
                    .then(CommandManager.literal("save")
                        .executes(ctx -> {
                            com.customblocks.core.SnapshotManager.takeSnapshot("manual");
                            ChatHelper.success(ctx.getSource(), "§aSnapshot saved (manual).");
                            return 1;
                        })
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .executes(ctx -> {
                                String name = StringArgumentType.getString(ctx, "name").replaceAll("[^a-zA-Z0-9_\\-]", "_");
                                com.customblocks.core.SnapshotManager.takeSnapshot("manual_" + name);
                                ChatHelper.success(ctx.getSource(), "§aSnapshot saved: §fmanual_" + name);
                                return 1;
                            }))))

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
                    .executes(ctx -> cmdList(ctx.getSource()))
                    .then(CommandManager.literal("export")
                        .executes(ctx -> usage(ctx.getSource(), "list export <txt|csv|json>"))
                        .then(CommandManager.argument("format", StringArgumentType.word())
                            .executes(ctx -> cmdListExport(ctx.getSource(),
                                StringArgumentType.getString(ctx, "format").toLowerCase(java.util.Locale.ROOT))))))

                // ── new graphical interfaces ─────────────────────────────────
                .then(CommandManager.literal("listgui")
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) GuiManager.openEditorPicker(p, 0);
                        else ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only"));
                        return 1;
                    }))

                // V4-49: helpgui renamed to /cb help (opens GUI); old chat-text help removed
                .then(CommandManager.literal("help")
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) GuiManager.openHelpGui(p);
                        else ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only"));
                        return 1;
                    }))
                // /cb edithud — opens HUD layout editor on the client
                .then(CommandManager.literal("edithud")
                    .requires(PermissionHelper::canUse)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) { ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only")); return 1; }
                        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new com.customblocks.network.OpenHudEditorPayload());
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
                        if (p != null) GuiManager.openWelcomeGui(p);
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

                // ── V4-24 editmagicitems ──────────────────────────────────────
                .then(CommandManager.literal("editmagicitems")
                    .requires(PermissionHelper::canUse)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) { ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only")); return 0; }
                        GuiManager.openMagicItemsGui(p);
                        ChatHelper.info(ctx.getSource(), "§7Magic Items Editor: §fClick any item in the GUI to receive it.");
                        ChatHelper.info(ctx.getSource(), "§7To rename your held magic item: §f/cb renameheld <new name>");
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



                // ── settings (primary) + config (alias) ──────────────────────
                .then(CommandManager.literal("settings")
                    .requires(PermissionHelper::canConfig)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) GuiManager.openConfigWarningGui(p);
                        else ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only"));
                        return 1;
                    })
                    // /cb settings hologram <true|false>
                    .then(CommandManager.literal("hologram")
                        .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                            .executes(ctx -> cmdConfigHologram(ctx.getSource(), BoolArgumentType.getBool(ctx, "enabled"))))))
                // CMD1 — /cb config is now the alias of /cb settings
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

                // ── sync — 1.22 player-triggered re-sync ─────────────────────
                .then(CommandManager.literal("sync")
                    .executes(ctx -> cmdSync(ctx.getSource())))

                // ── unsuppress — 1.23 restore broken-block warning ───────────
                .then(CommandManager.literal("unsuppress")
                    .requires(PermissionHelper::canEdit)
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdUnsuppress(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id")))))

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
                    // V4-15: no-args opens magic items GUI so player can pick a square
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) { GuiManager.openMagicItemsGui(p); return 1; }
                        return usage(ctx.getSource(), "square");
                    })
                    .then(CommandManager.argument("color", StringArgumentType.word())
                        .suggests((ctx, b) -> { b.suggest("black"); b.suggest("yellow"); b.suggest("green"); b.suggest("red"); return b.buildFuture(); })
                        .executes(ctx -> cmdGiveSquare(ctx.getSource(),
                            StringArgumentType.getString(ctx, "color")))))

                .then(CommandManager.literal("triangle")
                    .requires(PermissionHelper::canGive)
                    // V4-15: no-args opens magic items GUI so player can pick a triangle
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) { GuiManager.openMagicItemsGui(p); return 1; }
                        return usage(ctx.getSource(), "triangle");
                    })
                    .then(CommandManager.argument("color", StringArgumentType.word())
                        .suggests((ctx, b) -> { b.suggest("black"); b.suggest("yellow"); b.suggest("green"); b.suggest("red"); return b.buildFuture(); })
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
                // NF2 — /cb deleter
                .then(CommandManager.literal("deleter")
                    .requires(PermissionHelper::canGive)
                    .executes(ctx -> cmdGiveDeleterInternal(ctx.getSource())))

                // V4-42: /cb bgstudio — opens Background Studio (replaces removed diamondtriangle)
                .then(CommandManager.literal("bgstudio")
                    .requires(PermissionHelper::canUse)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) { ChatHelper.error(ctx.getSource(), "Player only."); return 0; }
                        GuiManager.openBgStudio(p);
                        return 1;
                    }))

                // V4-49: diamondtriangle removed — use /cb triangle instead
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
                        .executes(ctx -> {
                            // V4-12: no face given — detect from crosshair, prompt for URL
                            ServerPlayerEntity p = ctx.getSource().getPlayer();
                            if (p == null) return usage(ctx.getSource(), "setface");
                            String id = sanitize(StringArgumentType.getString(ctx, "id"));
                            if (!SlotManager.hasId(id)) { ctx.getSource().sendMessage(notFound(id)); return 0; }
                            String face = autoDetectFace(ctx.getSource());
                            if (face == null) { ChatHelper.error(ctx.getSource(), "Look at a block face first, then run /cb setface <id>."); return 0; }
                            GuiManager.openShortInputPrompt(p,
                                new GuiManager.PendingInput(GuiManager.InputAction.SETFACE_URL, id, face, null, null, 0),
                                "§6URL for " + face + " face of " + id,
                                new net.minecraft.item.ItemStack(net.minecraft.item.Items.PAINTING),
                                "");
                            return 1;
                        })
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
                        .executes(ctx -> {
                            // V4-12: no face given — detect from crosshair
                            String id = sanitize(StringArgumentType.getString(ctx, "id"));
                            String face = autoDetectFace(ctx.getSource());
                            if (face == null) { ChatHelper.error(ctx.getSource(), "Look at a block face first, then run /cb clearface <id>."); return 0; }
                            return cmdClearFace(ctx.getSource(), id, face);
                        })
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

                // ── history (Phase H₁ / V4-17) ──────────────────────────────
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

                // ── showcase (Phase H1 + 4C) ────────────────────────────────
                .then(CommandManager.literal("showcase")
                    .requires(PermissionHelper::canUse)
                    .executes(ctx -> usage(ctx.getSource(), "showcase"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdShowcase(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id"))))
                    // Phase 4C: /cb showcase config <x> <y> <z> source <source>
                    .then(CommandManager.literal("config")
                        .requires(PermissionHelper::canEdit)
                        .then(CommandManager.argument("x", IntegerArgumentType.integer())
                        .then(CommandManager.argument("y", IntegerArgumentType.integer())
                        .then(CommandManager.argument("z", IntegerArgumentType.integer())
                        .then(CommandManager.argument("source", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                int bx = IntegerArgumentType.getInteger(ctx, "x");
                                int by = IntegerArgumentType.getInteger(ctx, "y");
                                int bz = IntegerArgumentType.getInteger(ctx, "z");
                                String src = StringArgumentType.getString(ctx, "source");
                                ServerPlayerEntity player = ctx.getSource().getPlayer();
                                if (player == null) {
                                    ChatHelper.error(ctx.getSource(), "§cThis command requires a player.");
                                    return 0;
                                }
                                net.minecraft.util.math.BlockPos bpos =
                                    new net.minecraft.util.math.BlockPos(bx, by, bz);
                                com.customblocks.gui.ShowcaseManager.updateConfig(
                                    player.getServerWorld(), bpos, src, 5);
                                ctx.getSource().sendFeedback(
                                    () -> Text.literal("§aShowcase at " + bx + "," + by + "," + bz
                                        + " updated to source §f" + src + "§a."), false);
                                return 1;
                            })))))))

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

                // ── script (Phase 10.1 — renamed/parallel to macro) ───────────
                tree.then(CommandManager.literal("script")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "script"))
                    .then(CommandManager.literal("record")
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .executes(ctx -> {
                                var p = ctx.getSource().getPlayer();
                                if (p == null) return 0;
                                String n = StringArgumentType.getString(ctx, "name");
                                com.customblocks.core.MacroManager.startRecording(p.getUuid(), n);
                                ChatHelper.info(ctx.getSource(), "§b[Script] §aRecording started: §f" + n);
                                ChatHelper.info(ctx.getSource(), "§7Block edits are now captured. Run §f/cb script stop §7to finish.");
                                return 1;
                            })))
                    .then(CommandManager.literal("stop")
                        .executes(ctx -> {
                            var p = ctx.getSource().getPlayer();
                            if (p == null) return 0;
                            String n = com.customblocks.core.MacroManager.recordingName(p.getUuid());
                            if (n == null) { ChatHelper.warn(ctx.getSource(), "§7No script recording in progress."); return 0; }
                            int count = com.customblocks.core.MacroManager.stopRecording(p.getUuid());
                            if (count < 0) { ChatHelper.error(ctx.getSource(), "§cFailed to save script."); return 0; }
                            ChatHelper.success(ctx.getSource(), "§b[Script] §aSaved §f" + n + " §awith §f" + count + " §astep(s).");
                            return 1;
                        }))
                    .then(CommandManager.literal("run")
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .executes(ctx -> {
                                var p = ctx.getSource().getPlayer();
                                if (p == null) return 0;
                                String n = StringArgumentType.getString(ctx, "name");
                                if (com.customblocks.core.MacroManager.isRunning(p.getUuid())) {
                                    ChatHelper.error(ctx.getSource(), "§cA script is already running. Wait for it to finish.");
                                    return 0;
                                }
                                com.customblocks.core.MacroManager.ScriptRunResult result =
                                    com.customblocks.core.MacroManager.runScript(p, n);
                                if (result == null) {
                                    ChatHelper.error(ctx.getSource(), "§cScript '§f" + n + "§c' not found or corrupt.");
                                    return 0;
                                }
                                ChatHelper.success(ctx.getSource(), "§b[Script] §f" + n + " §acomplete — §f"
                                    + result.ran() + "§a/§f" + result.steps().size() + " §asteps passed.");
                                com.customblocks.gui.GuiManager.openScriptSummary(p, result);
                                return result.ran();
                            })))
                    .then(CommandManager.literal("list")
                        .executes(ctx -> {
                            var scripts = com.customblocks.core.MacroManager.listMacros();
                            if (scripts.isEmpty()) { ChatHelper.info(ctx.getSource(), "§7No scripts saved."); return 1; }
                            ChatHelper.info(ctx.getSource(), "§b[Script] §7Saved scripts §8(" + scripts.size() + ")§7:");
                            scripts.forEach(s -> ChatHelper.info(ctx.getSource(), "  §f" + s));
                            return 1;
                        }))
                    .then(CommandManager.literal("delete")
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .executes(ctx -> {
                                String n = StringArgumentType.getString(ctx, "name");
                                boolean ok = com.customblocks.core.MacroManager.deleteMacro(n);
                                if (!ok) { ChatHelper.warn(ctx.getSource(), "§7Script '§f" + n + "§7' not found."); return 0; }
                                ChatHelper.success(ctx.getSource(), "§b[Script] §cDeleted §f" + n);
                                return 1;
                            })))
                    .then(CommandManager.literal("show")
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .executes(ctx -> {
                                String n = StringArgumentType.getString(ctx, "name");
                                var cmds = com.customblocks.core.MacroManager.loadMacro(n);
                                if (cmds == null) { ChatHelper.error(ctx.getSource(), "§cScript '§f" + n + "§c' not found."); return 0; }
                                ChatHelper.info(ctx.getSource(), "§b[Script] §f" + n + " §8(" + cmds.size() + " steps)§7:");
                                for (int i = 0; i < cmds.size(); i++)
                                    ChatHelper.info(ctx.getSource(), "  §8" + (i + 1) + ". §f" + cmds.get(i));
                                return 1;
                            })))
                    .then(CommandManager.literal("add")
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .then(CommandManager.argument("command", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String n   = StringArgumentType.getString(ctx, "name");
                                    String cmd = StringArgumentType.getString(ctx, "command");
                                    boolean ok = com.customblocks.core.MacroManager.addCommand(n, cmd);
                                    if (!ok) { ChatHelper.error(ctx.getSource(), "§cFailed to save script."); return 0; }
                                    ChatHelper.success(ctx.getSource(), "§b[Script] §aStep added to §f" + n);
                                    return 1;
                                })))));

                // ── scriptgui (Phase 10.1) ─────────────────────────────────────
                tree.then(CommandManager.literal("scriptgui")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> {
                        var p = ctx.getSource().getPlayer();
                        if (p == null) return 0;
                        com.customblocks.gui.GuiManager.openScriptGui(p, 0);
                        return 1;
                    }));

                // ── market (Phase L3) ──────────────────────────────────────────
                tree.then(CommandManager.literal("market")
                    .executes(ctx -> {
                        var p = ctx.getSource().getPlayer();
                        if (p == null) return 0;
                        com.customblocks.gui.GuiManager.openMarketGui(p, 0, false);
                        return 1;
                    }));

                // ── 3.2 palette ────────────────────────────────────────────────
                tree.then(CommandManager.literal("palette")
                    .requires(PermissionHelper::canUse)
                    .executes(ctx -> cmdPaletteList(ctx.getSource()))
                    .then(CommandManager.literal("list")
                        .executes(ctx -> cmdPaletteList(ctx.getSource())))
                    .then(CommandManager.literal("add")
                        .then(CommandManager.argument("hex", StringArgumentType.word())
                            .executes(ctx -> cmdPaletteAdd(ctx.getSource(),
                                StringArgumentType.getString(ctx, "hex")))))
                    .then(CommandManager.literal("remove")
                        .then(CommandManager.argument("index", IntegerArgumentType.integer(1, com.customblocks.core.PlayerPaletteManager.MAX_PALETTE_SIZE))
                            .executes(ctx -> cmdPaletteRemove(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "index")))))
                    .then(CommandManager.literal("clear")
                        .executes(ctx -> cmdPaletteClear(ctx.getSource()))));

                // ── 3.6 tolerance ──────────────────────────────────────────────
                tree.then(CommandManager.literal("tolerance")
                    .requires(PermissionHelper::canUse)
                    .executes(ctx -> cmdToleranceShow(ctx.getSource()))
                    .then(CommandManager.literal("reset")
                        .executes(ctx -> cmdToleranceReset(ctx.getSource())))
                    .then(CommandManager.argument("value", IntegerArgumentType.integer(10, 80))
                        .executes(ctx -> cmdToleranceSet(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "value")))));

                // ── V4-29 trianglemode ─────────────────────────────────────────
                tree.then(CommandManager.literal("trianglemode")
                    .requires(PermissionHelper::canUse)
                    .executes(ctx -> cmdTriangleModeShow(ctx.getSource()))
                    .then(CommandManager.literal("edge")
                        .executes(ctx -> cmdTriangleModeSet(ctx.getSource(), "edge")))
                    .then(CommandManager.literal("full")
                        .executes(ctx -> cmdTriangleModeSet(ctx.getSource(), "full"))));

                // ── Phase 4.3 recent ───────────────────────────────────────────
                tree.then(CommandManager.literal("recent")
                    .requires(PermissionHelper::canUse)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) {
                            ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only"));
                            return 0;
                        }
                        GuiManager.openRecentGui(p);
                        return 1;
                    }));

                // ── Phase 4.4 find ─────────────────────────────────────────────
                tree.then(CommandManager.literal("find")
                    .requires(PermissionHelper::canUse)
                    .executes(ctx -> usage(ctx.getSource(), "find"))
                    .then(CommandManager.argument("blockId", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> {
                            ServerPlayerEntity p = ctx.getSource().getPlayer();
                            if (p == null) {
                                ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only"));
                                return 0;
                            }
                            String id = StringArgumentType.getString(ctx, "blockId");
                            com.customblocks.BlockFinder.findBlocks(p, id, 128, false);
                            return 1;
                        })
                        .then(CommandManager.literal("--count")
                            .executes(ctx -> {
                                ServerPlayerEntity p = ctx.getSource().getPlayer();
                                if (p == null) {
                                    ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only"));
                                    return 0;
                                }
                                String id = StringArgumentType.getString(ctx, "blockId");
                                com.customblocks.BlockFinder.findBlocks(p, id, 128, true);
                                return 1;
                            }))
                        .then(CommandManager.argument("radius", IntegerArgumentType.integer(16, 512))
                            .executes(ctx -> {
                                ServerPlayerEntity p = ctx.getSource().getPlayer();
                                if (p == null) {
                                    ChatHelper.error(ctx.getSource(), ChatHelper.formattedKey("cmd.console_player_only"));
                                    return 0;
                                }
                                String id = StringArgumentType.getString(ctx, "blockId");
                                int radius = IntegerArgumentType.getInteger(ctx, "radius");
                                com.customblocks.BlockFinder.findBlocks(p, id, radius, false);
                                return 1;
                            }))));

                // ── Phase 4B.2 shapelist / shapepreview ────────────────────────
                tree.then(CommandManager.literal("shapelist")
                    .requires(PermissionHelper::canUse)
                    .executes(ctx -> {
                        ServerCommandSource src = ctx.getSource();
                        ChatHelper.info(src, "§b§lAvailable Shape Presets:");
                        for (String key : SlotManager.SHAPE_PRESETS.keySet()) {
                            ChatHelper.info(src, "  §f" + key);
                        }
                        ChatHelper.info(src, "§7Use §f/cb setshape <id> <preset> §7to apply.");
                        return 1;
                    }));

                tree.then(CommandManager.literal("shapepreview")
                    .requires(PermissionHelper::canUse)
                    .executes(ctx -> usage(ctx.getSource(), "shapepreview"))
                    .then(CommandManager.argument("preset", StringArgumentType.word())
                        .suggests(SHAPE_SUGGESTIONS)
                        .executes(ctx -> {
                            String preset = StringArgumentType.getString(ctx, "preset");
                            if (!SlotManager.SHAPE_PRESETS.containsKey(preset)) {
                                ChatHelper.error(ctx.getSource(), "§cUnknown shape preset '§f" + preset + "§c'. Use §f/cb shapelist §cto see all presets.");
                                return 0;
                            }
                            ChatHelper.info(ctx.getSource(), "§7Shape preview: place a block with preset §b" + preset + "§7 to see its shape in-world.");
                            return 1;
                        })));

                // ── Phase 5.15 template ────────────────────────────────────────
                tree.then(CommandManager.literal("template")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> usage(ctx.getSource(), "template"))
                    // /cb template save <name> <blockId>
                    .then(CommandManager.literal("save")
                        .executes(ctx -> usage(ctx.getSource(), "template save <name> <blockId>"))
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .then(CommandManager.argument("blockId", StringArgumentType.word())
                                .suggests(BLOCK_SUGGESTIONS)
                                .executes(ctx -> cmdTemplateSave(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "name"),
                                    StringArgumentType.getString(ctx, "blockId"))))))
                    // /cb template apply <name> <blockId>
                    .then(CommandManager.literal("apply")
                        .executes(ctx -> usage(ctx.getSource(), "template apply <name> <blockId>"))
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .then(CommandManager.argument("blockId", StringArgumentType.word())
                                .suggests(BLOCK_SUGGESTIONS)
                                .executes(ctx -> cmdTemplateApply(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "name"),
                                    StringArgumentType.getString(ctx, "blockId"))))))
                    // /cb template list
                    .then(CommandManager.literal("list")
                        .executes(ctx -> cmdTemplateList(ctx.getSource())))
                    // /cb template delete <name>
                    .then(CommandManager.literal("delete")
                        .executes(ctx -> usage(ctx.getSource(), "template delete <name>"))
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .executes(ctx -> cmdTemplateDelete(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name"))))));

                // ── /cb backup ────────────────────────────────────────────────
                tree.then(CommandManager.literal("backup")
                    .requires(PermissionHelper::canAdmin)
                    .executes(ctx -> usage(ctx.getSource(), "backup <create|list|restore|delete>"))
                    .then(CommandManager.literal("create")
                        .executes(ctx -> cmdBackupCreate(ctx.getSource(), "manual"))
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .executes(ctx -> cmdBackupCreate(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name")))))
                    .then(CommandManager.literal("list")
                        .executes(ctx -> cmdBackupList(ctx.getSource())))
                    .then(CommandManager.literal("restore")
                        .executes(ctx -> usage(ctx.getSource(), "backup restore <name>"))
                        .then(CommandManager.argument("name", StringArgumentType.greedyString())
                            .executes(ctx -> cmdBackupRestore(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name")))))
                    .then(CommandManager.literal("delete")
                        .executes(ctx -> usage(ctx.getSource(), "backup delete <name>"))
                        .then(CommandManager.argument("name", StringArgumentType.greedyString())
                            .executes(ctx -> cmdBackupDelete(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name")))))
                    .then(CommandManager.literal("expiry")
                        .executes(ctx -> usage(ctx.getSource(), "backup expiry <name> <hours>"))
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .then(CommandManager.argument("hours", IntegerArgumentType.integer(1, 720))
                                .executes(ctx -> cmdBackupExpiry(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "name"),
                                    IntegerArgumentType.getInteger(ctx, "hours")))))));

                // ── /cb safety ────────────────────────────────────────────────
                tree.then(CommandManager.literal("safety")
                    .requires(PermissionHelper::canAdmin)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) { ChatHelper.error(ctx.getSource(), "Player only."); return 0; }
                        com.customblocks.gui.GuiManager.openSafetyCenter(p);
                        return 1;
                    }));

                // ── /cb historygui removed — use /cb history for text output ──
                tree.then(CommandManager.literal("undogui")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) { ChatHelper.error(ctx.getSource(), "Player only."); return 0; }
                        com.customblocks.gui.GuiManager.openUndoPicker(p, 0);
                        return 1;
                    }));
                tree.then(CommandManager.literal("redogui")
                    .requires(PermissionHelper::canEdit)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) { ChatHelper.error(ctx.getSource(), "Player only."); return 0; }
                        com.customblocks.gui.GuiManager.openUndoPicker(p, 0);
                        return 1;
                    }));

                // ── /cb ai (Phase 11.1) ────────────────────────────────────────
                tree.then(CommandManager.literal("ai")
                    .requires(PermissionHelper::canAi)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) { ChatHelper.error(ctx.getSource(), "Player only."); return 0; }
                        com.customblocks.gui.GuiManager.openAiGui(p);
                        return 1;
                    })
                    .then(CommandManager.argument("command", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String text = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "command");
                            ServerCommandSource src = ctx.getSource();
                            ServerPlayerEntity p = src.getPlayer();
                            com.customblocks.assistant.AiCommandParser.ParseResult r =
                                com.customblocks.assistant.AiCommandParser.parse(text);
                            if (r == null) {
                                if (p != null && !com.customblocks.CustomBlocksConfig.aiWorkerUrl.isEmpty()
                                        && !com.customblocks.CustomBlocksConfig.aiServerToken.isEmpty()) {
                                    com.customblocks.gui.GuiManager.postAiQuery(p, text);
                                    return 1;
                                }
                                ChatHelper.info(src, "§7AI: I didn't understand that. Try: §f'list all blocks'§7, §f'delete blocks starting with X'§7, §f'set glow 5 on block X'§7.");
                                return 0;
                            }
                            com.customblocks.assistant.AiCommandParser.execute(r, src, p != null ? p.getUuid() : null);
                            return 1;
                        })));

                // ── /cb colors ─────────────────────────────────────────────────
                tree.then(CommandManager.literal("colors")
                    .requires(PermissionHelper::canUse)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) { ChatHelper.error(ctx.getSource(), "Player only."); return 0; }
                        com.customblocks.gui.GuiManager.openColorsHub(p);
                        return 1;
                    }));

                // ── /cb customcolor (Phase 11.2) ───────────────────────────────
                tree.then(CommandManager.literal("customcolor")
                    .requires(PermissionHelper::canGive)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) { ChatHelper.error(ctx.getSource(), "Player only."); return 0; }
                        com.customblocks.gui.GuiManager.openCustomColorStudio(p, null);
                        return 1;
                    })
                    .then(CommandManager.literal("square")
                        .then(CommandManager.argument("hex", StringArgumentType.word())
                            .executes(ctx -> cmdGiveCustomColorToolsInternal(ctx.getSource(),
                                StringArgumentType.getString(ctx, "hex")))))
                    .then(CommandManager.literal("triangle")
                        .then(CommandManager.argument("hex", StringArgumentType.word())
                            .executes(ctx -> cmdGiveCustomColorToolsInternal(ctx.getSource(),
                                StringArgumentType.getString(ctx, "hex")))))
                    .then(CommandManager.argument("hex", StringArgumentType.word())
                        .executes(ctx -> cmdGiveCustomColorToolsInternal(ctx.getSource(),
                            StringArgumentType.getString(ctx, "hex")))));

                // ── /cb config (Phase 10.2) ────────────────────────────────────
                tree.then(CommandManager.literal("config")
                    .requires(PermissionHelper::canConfig)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) { GuiManager.openConfigWarningGui(p); return 1; }
                        return cmdConfigList(ctx.getSource());
                    })
                    .then(CommandManager.literal("max-slots")
                        .executes(ctx -> cmdConfigGet(ctx.getSource(), "max-slots", String.valueOf(com.customblocks.CustomBlocksConfig.maxSlots)))
                        .then(CommandManager.argument("value", IntegerArgumentType.integer(1, 100_000))
                            .executes(ctx -> cmdConfigSetInt(ctx.getSource(), "max-slots", IntegerArgumentType.getInteger(ctx, "value")))))
                    .then(CommandManager.literal("undo-depth")
                        .executes(ctx -> cmdConfigGet(ctx.getSource(), "undo-depth", String.valueOf(com.customblocks.CustomBlocksConfig.maxUndoDepth)))
                        .then(CommandManager.argument("value", IntegerArgumentType.integer(1, 100_000))
                            .executes(ctx -> cmdConfigSetInt(ctx.getSource(), "undo-depth", IntegerArgumentType.getInteger(ctx, "value")))))
                    .then(CommandManager.literal("gif-limit")
                        .executes(ctx -> cmdConfigGet(ctx.getSource(), "gif-limit", String.valueOf(com.customblocks.CustomBlocksConfig.maxGifSizeMb)))
                        .then(CommandManager.argument("value", IntegerArgumentType.integer(1, 64))
                            .executes(ctx -> cmdConfigSetInt(ctx.getSource(), "gif-limit", IntegerArgumentType.getInteger(ctx, "value")))))
                    .then(CommandManager.literal("texture-size")
                        .executes(ctx -> cmdConfigGet(ctx.getSource(), "texture-size", String.valueOf(com.customblocks.CustomBlocksConfig.defaultTextureSize)))
                        .then(CommandManager.argument("value", IntegerArgumentType.integer(16, 512))
                            .executes(ctx -> cmdConfigSetInt(ctx.getSource(), "texture-size", IntegerArgumentType.getInteger(ctx, "value")))))
                    .then(CommandManager.literal("instant-click")
                        .executes(ctx -> cmdConfigGet(ctx.getSource(), "instant-click", String.valueOf(com.customblocks.CustomBlocksConfig.instantClickAggressivenessMs)))
                        .then(CommandManager.argument("value", IntegerArgumentType.integer(0, 10_000))
                            .executes(ctx -> cmdConfigSetInt(ctx.getSource(), "instant-click", IntegerArgumentType.getInteger(ctx, "value")))))
                    .then(CommandManager.literal("hologram")
                        .executes(ctx -> cmdConfigGet(ctx.getSource(), "hologram", String.valueOf(com.customblocks.CustomBlocksConfig.hologramEnabled)))
                        .then(CommandManager.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                            .executes(ctx -> cmdConfigSetBool(ctx.getSource(), "hologram", com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "value")))))
                    .then(CommandManager.literal("hologram-height")
                        .executes(ctx -> cmdConfigGet(ctx.getSource(), "hologram-height", String.valueOf(com.customblocks.CustomBlocksConfig.hologramHeight)))
                        .then(CommandManager.argument("value", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0.1f, 5.0f))
                            .executes(ctx -> cmdConfigSetFloat(ctx.getSource(), "hologram-height", com.mojang.brigadier.arguments.FloatArgumentType.getFloat(ctx, "value")))))
                    .then(CommandManager.literal("sounds")
                        .executes(ctx -> cmdConfigGet(ctx.getSource(), "sounds", String.valueOf(com.customblocks.CustomBlocksConfig.soundsEnabled)))
                        .then(CommandManager.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                            .executes(ctx -> cmdConfigSetBool(ctx.getSource(), "sounds", com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "value")))))
                    .then(CommandManager.literal("particles")
                        .executes(ctx -> cmdConfigGet(ctx.getSource(), "particles", String.valueOf(com.customblocks.CustomBlocksConfig.particlesEnabled)))
                        .then(CommandManager.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                            .executes(ctx -> cmdConfigSetBool(ctx.getSource(), "particles", com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "value")))))
                    .then(CommandManager.literal("marketplace")
                        .executes(ctx -> cmdConfigGet(ctx.getSource(), "marketplace", String.valueOf(com.customblocks.CustomBlocksConfig.marketplaceEnabled)))
                        .then(CommandManager.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                            .executes(ctx -> cmdConfigSetBool(ctx.getSource(), "marketplace", com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "value")))))
                    .then(CommandManager.literal("voice")
                        .executes(ctx -> cmdConfigGet(ctx.getSource(), "voice", com.customblocks.CustomBlocksConfig.voiceMode))
                        .then(CommandManager.argument("value", StringArgumentType.word())
                            .executes(ctx -> cmdConfigSetVoice(ctx.getSource(), StringArgumentType.getString(ctx, "value")))))
                    .then(CommandManager.literal("backup-interval")
                        .executes(ctx -> cmdConfigGet(ctx.getSource(), "backup-interval", String.valueOf(com.customblocks.CustomBlocksConfig.autoSnapshotMinutes)))
                        .then(CommandManager.argument("value", IntegerArgumentType.integer(1, 1440))
                            .executes(ctx -> cmdConfigSetInt(ctx.getSource(), "backup-interval", IntegerArgumentType.getInteger(ctx, "value")))))
                    .then(CommandManager.literal("ai-provider")
                        .executes(ctx -> cmdConfigGet(ctx.getSource(), "ai-provider",
                            com.customblocks.CustomBlocksConfig.aiApiProvider.isEmpty() ? "off" : com.customblocks.CustomBlocksConfig.aiApiProvider))
                        .then(CommandManager.argument("value", StringArgumentType.word())
                            .executes(ctx -> cmdConfigSetAiProvider(ctx.getSource(), StringArgumentType.getString(ctx, "value")))))
                    .then(CommandManager.literal("ai-key")
                        .executes(ctx -> cmdConfigGet(ctx.getSource(), "ai-key",
                            com.customblocks.CustomBlocksConfig.aiApiKey.isEmpty() ? "[not set]" : "[hidden]"))
                        .then(CommandManager.argument("value", StringArgumentType.word())
                            .executes(ctx -> cmdConfigSetAiKey(ctx.getSource(), StringArgumentType.getString(ctx, "value")))))
                    .then(CommandManager.literal("ai-variations")
                        .executes(ctx -> cmdConfigGet(ctx.getSource(), "ai-variations", String.valueOf(com.customblocks.CustomBlocksConfig.aiMaxVariations)))
                        .then(CommandManager.argument("value", IntegerArgumentType.integer(1, 8))
                            .executes(ctx -> cmdConfigSetInt(ctx.getSource(), "ai-variations", IntegerArgumentType.getInteger(ctx, "value")))))
                    .then(CommandManager.literal("ai-style")
                        .executes(ctx -> cmdConfigGet(ctx.getSource(), "ai-style", com.customblocks.CustomBlocksConfig.aiTextureStyle))
                        .then(CommandManager.argument("value", StringArgumentType.word())
                            .executes(ctx -> cmdConfigSetAiStyle(ctx.getSource(), StringArgumentType.getString(ctx, "value"))))));

                // ── /cb cache (Phase 10.3) ─────────────────────────────────────
                tree.then(CommandManager.literal("cache")
                    .requires(PermissionHelper::canAdmin)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) { ChatHelper.error(ctx.getSource(), "Player only."); return 0; }
                        com.customblocks.gui.GuiManager.openCacheDashboard(p, 0);
                        return 1;
                    }));

                // ── /cb audit (Phase 10.4) ─────────────────────────────────────
                tree.then(CommandManager.literal("audit")
                    .requires(PermissionHelper::canAdmin)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) { ChatHelper.error(ctx.getSource(), "Player only."); return 0; }
                        com.customblocks.gui.GuiManager.openAuditGui(p, 0);
                        return 1;
                    }));

                // ── /cb screenshot (Phase 10.5) ────────────────────────────────
                tree.then(CommandManager.literal("screenshot")
                    .requires(PermissionHelper::canUse)
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .executes(ctx -> cmdScreenshot(ctx.getSource(), StringArgumentType.getString(ctx, "id")))));

                // ── /cb achievements (Phase 12.1) ─────────────────────────────
                tree.then(CommandManager.literal("achievements")
                    .requires(PermissionHelper::canUse)
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) { ChatHelper.error(ctx.getSource(), "Player only."); return 0; }
                        com.customblocks.gui.GuiManager.openAchievementsGui(p, 0);
                        return 1;
                    }));

                // ── /cb arabic ─────────────────────────────────────────────────
                tree.then(CommandManager.literal("arabic")
                    .requires(PermissionHelper::canUse)
                    // bare /cb arabic → opens browser
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) { ChatHelper.error(ctx.getSource(), "Player only."); return 0; }
                        com.customblocks.gui.GuiManager.openArabicBrowser(p, "black", 0);
                        return 1;
                    })
                    // /cb arabic gui [color]
                    .then(CommandManager.literal("gui")
                        .executes(ctx -> {
                            ServerPlayerEntity p = ctx.getSource().getPlayer();
                            if (p == null) { ChatHelper.error(ctx.getSource(), "Player only."); return 0; }
                            com.customblocks.gui.GuiManager.openArabicBrowser(p, "black", 0);
                            return 1;
                        })
                        .then(CommandManager.argument("color", StringArgumentType.word())
                            .suggests((ctx, b) -> {
                                b.suggest("black"); b.suggest("yellow"); b.suggest("green"); b.suggest("red");
                                return b.buildFuture();
                            })
                            .executes(ctx -> {
                                ServerPlayerEntity p = ctx.getSource().getPlayer();
                                if (p == null) { ChatHelper.error(ctx.getSource(), "Player only."); return 0; }
                                com.customblocks.gui.GuiManager.openArabicBrowser(p,
                                    StringArgumentType.getString(ctx, "color").toLowerCase(Locale.ROOT), 0);
                                return 1;
                            })))
                    // /cb arabic import <base_path>
                    .then(CommandManager.literal("import")
                        .requires(src -> src.hasPermissionLevel(CustomBlocksConfig.permissionLevelAdmin))
                        .then(CommandManager.argument("path", StringArgumentType.greedyString())
                            .executes(ctx -> cmdArabicImport(ctx.getSource(),
                                StringArgumentType.getString(ctx, "path").trim()))))
                    // /cb arabic give <letter> <color>
                    .then(CommandManager.literal("give")
                        .then(CommandManager.argument("letter", StringArgumentType.word())
                            .suggests((ctx, b) -> {
                                com.customblocks.arabic.ArabicLetterMap.LETTER_TO_CHAR.keySet()
                                    .forEach(b::suggest);
                                return b.buildFuture();
                            })
                            .then(CommandManager.argument("color", StringArgumentType.word())
                                .suggests((ctx, b) -> {
                                    b.suggest("black"); b.suggest("yellow");
                                    b.suggest("green"); b.suggest("red");
                                    return b.buildFuture();
                                })
                                .executes(ctx -> cmdArabicGive(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "letter"),
                                    StringArgumentType.getString(ctx, "color"))))))
                    // /cb arabic text <color> <arabic_text>
                    .then(CommandManager.literal("text")
                        .then(CommandManager.argument("color", StringArgumentType.word())
                            .suggests((ctx, b) -> {
                                b.suggest("black"); b.suggest("yellow");
                                b.suggest("green"); b.suggest("red");
                                return b.buildFuture();
                            })
                            .then(CommandManager.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> cmdArabicText(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "color"),
                                    StringArgumentType.getString(ctx, "text").trim()))))));

            dispatcher.register(DidYouMean.appendFallbackBranch(tree));
            dispatcher.register(CommandManager.literal("cb")
                .requires(src -> PermissionHelper.canUse(src))
                .executes(ctx -> {
                    var p = ctx.getSource().getPlayer();
                    if (p != null) com.customblocks.gui.GuiManager.openWelcomeGui(p);
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
                    // 1.12 — relay any trim/resize warning to the player
                    if (result.warning() != null) {
                        src.sendMessage(net.minecraft.text.Text.literal(result.warning()));
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
        for (var faceEntry : d.faceTextures.entrySet()) {
            NetworkManager.broadcastUpdate(src.getServer(), new SlotUpdatePayload(
                "setface", d.index, newId, null, faceEntry.getValue(),
                d.lightLevel, d.hardness, d.soundType, faceEntry.getKey(), null, d.animMeta));
        }
        if (d.isShaped()) broadcastShape(src.getServer(), d);
        if (d.noCollision) NetworkManager.broadcastUpdate(src.getServer(), new SlotUpdatePayload(
                "setcollision", d.index, newId, null, null, 0, 0, "stone", null, "false"));
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
        com.customblocks.core.TrashManager.addToTrash(d); // V4-18: move to trash before removing
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
            String _hDel = FirstUseHints.hint(p.getUuid(), "first_delete");
            if (_hDel != null) p.sendMessage(Text.literal(_hDel), false);
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
            com.customblocks.core.TrashManager.addToTrash(d); // V4-18
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
            // 1.15 — atomic write: temp file + move so a crash mid-write can't corrupt the cache
            java.nio.file.Path exportTarget = exportDir.resolve(hash + ".json");
            java.nio.file.Path exportTmp = exportDir.resolve(hash + ".json.tmp");
            java.nio.file.Files.writeString(exportTmp, jsonStr, java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.move(exportTmp, exportTarget, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
                // 7.36 — validate hash contains only safe characters before using as filename
                if (!hash.matches("[A-Za-z0-9!@#$%&_\\-\\.]{1,50}"))
                    throw new IllegalArgumentException("Invalid share code format.");
                // 7.36 — prevent path traversal: verify resolved path stays inside exports dir
                java.nio.file.Path exportsDir = java.nio.file.Path.of("config/customblocks/exports").toAbsolutePath().normalize();
                java.nio.file.Path exportFile = exportsDir.resolve(hash + ".json").normalize();
                if (!exportFile.startsWith(exportsDir))
                    throw new IllegalArgumentException("Invalid share code.");
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

    private static final int MAX_IMPORT_JSON_BYTES = 2 * 1024 * 1024; // 7.35 — 2 MB cap

    private static String decodeInlineImportCode(String code) throws Exception {
        if (code.startsWith("CB2!")) {
            byte[] compressed = java.util.Base64.getDecoder().decode(code.substring(4));
            // 7.35 — reject oversized compressed input before even starting decompression
            if (compressed.length > 1_048_576)
                throw new Exception("Import code is too large.");
            try (java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed));
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n, total = 0;
                while ((n = gz.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_IMPORT_JSON_BYTES)
                        throw new Exception("Import data too large (max 2 MB).");
                    out.write(buf, 0, n);
                }
                return out.toString(java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        // CB! path — cap the decoded output too (7.35)
        byte[] decoded = java.util.Base64.getDecoder().decode(code.substring(3));
        if (decoded.length > MAX_IMPORT_JSON_BYTES)
            throw new Exception("Import data too large (max 2 MB).");
        return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
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
            // V4-06: push undo so /cb undo can remove the imported block
            UndoManager.pushUndoCreate(id, getPlayerUuid(src));

            int light = Math.max(0, Math.min(15, obj.has("light") ? obj.get("light").getAsInt() : 0));
            float hard = Math.max(0f, Math.min(100f, obj.has("hard") ? obj.get("hard").getAsFloat() : 1.5f));
            String rawSound = obj.has("sound") ? obj.get("sound").getAsString() : "stone";
            String sound = java.util.Arrays.asList(VALID_SOUNDS).contains(rawSound) ? rawSound : "stone";
            SlotManager.setProperties(id, light, hard, sound);
            if (obj.has("anim")) SlotManager.setAnimMeta(id, obj.get("anim").getAsString());
            if (obj.has("ncol") && obj.get("ncol").getAsBoolean()) SlotManager.setCollision(id, false);

            if (obj.has("shape")) {
                java.util.List<SlotData.ShapeBox> shapeBoxes = new java.util.ArrayList<>();
                for (com.google.gson.JsonElement el : obj.getAsJsonArray("shape")) {
                    try { shapeBoxes.add(SlotData.ShapeBox.parse(el.getAsString())); } catch (IllegalArgumentException ignored) {}
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
        java.nio.file.Path exportDir = java.nio.file.Path.of("config/customblocks/exports").toAbsolutePath().normalize();
        java.nio.file.Files.createDirectories(exportDir);
        // 7.36 — prevent path traversal: verify resolved path stays inside exports dir
        java.nio.file.Path target = exportDir.resolve(hash + ".json").normalize();
        if (!target.startsWith(exportDir)) throw new java.io.IOException("Invalid share code.");
        java.nio.file.Path tmp = exportDir.resolve(hash + ".json.tmp");
        java.nio.file.Files.writeString(tmp, json, java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
        // 7.22 — strip raw § formatting codes so they don't bleed into tooltips/chat
        String sanitizedName = newName.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
        SlotManager.rename(id, sanitizedName);
        SlotManager.saveAll();
        NetworkManager.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("rename", d.index, id, sanitizedName, null, 0, 0, "stone"));
        ChatHelper.success(src, ChatHelper.formattedKey("cmd.rename_done", sanitizedName));
        // P1 — macro recording hook
        java.util.UUID _renUuid = getPlayerUuid(src);
        if (_renUuid != null) com.customblocks.core.MacroManager.record(_renUuid, "rename " + id + " " + sanitizedName);
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

    private static int cmdSwapId(ServerCommandSource src, String rawId1, String rawId2) {
        String id1 = sanitize(rawId1);
        String id2 = sanitize(rawId2);
        if (!SlotManager.hasId(id1)) { src.sendMessage(notFound(id1)); return 0; }
        if (!SlotManager.hasId(id2)) { src.sendMessage(notFound(id2)); return 0; }
        if (id1.equals(id2)) { ChatHelper.error(src, "The two IDs are the same."); return 0; }
        SlotData d1 = SlotManager.getById(id1);
        SlotData d2 = SlotManager.getById(id2);
        UndoManager.pushUndoMutation(id1, d1, "swapid", getPlayerUuid(src));
        UndoManager.pushUndoMutation(id2, d2, "swapid", getPlayerUuid(src));
        // Swap IDs: rename id1 to a temp, id2 to id1, temp to id2
        String tmp = "__swaptmp_" + System.nanoTime();
        SlotManager.reId(id1, tmp);
        SlotManager.reId(id2, id1);
        SlotManager.reId(tmp, id2);
        SlotManager.saveAll();
        MinecraftServer server = src.getServer();
        SlotData u1 = SlotManager.getById(id2);
        SlotData u2 = SlotManager.getById(id1);
        if (u1 != null) NetworkManager.broadcastUpdate(server,
            new SlotUpdatePayload("add", u1.index, id2, u1.displayName, u1.texture, u1.lightLevel, u1.hardness, u1.soundType, null, null, u1.animMeta));
        if (u2 != null) NetworkManager.broadcastUpdate(server,
            new SlotUpdatePayload("add", u2.index, id1, u2.displayName, u2.texture, u2.lightLevel, u2.hardness, u2.soundType, null, null, u2.animMeta));
        ChatHelper.success(src, "§aSwapped IDs: §f" + id1 + " §7↔ §f" + id2);
        return 1;
    }

    private static int cmdSwapName(ServerCommandSource src, String rawId1, String rawId2) {
        String id1 = sanitize(rawId1);
        String id2 = sanitize(rawId2);
        if (!SlotManager.hasId(id1)) { src.sendMessage(notFound(id1)); return 0; }
        if (!SlotManager.hasId(id2)) { src.sendMessage(notFound(id2)); return 0; }
        if (id1.equals(id2)) { ChatHelper.error(src, "The two IDs are the same."); return 0; }
        SlotData d1 = SlotManager.getById(id1);
        SlotData d2 = SlotManager.getById(id2);
        UndoManager.pushUndoMutation(id1, d1, "swapname", getPlayerUuid(src));
        UndoManager.pushUndoMutation(id2, d2, "swapname", getPlayerUuid(src));
        String name1 = d1.displayName;
        String name2 = d2.displayName;
        SlotManager.rename(id1, name2);
        SlotManager.rename(id2, name1);
        SlotManager.saveAll();
        MinecraftServer server = src.getServer();
        NetworkManager.broadcastUpdate(server, new SlotUpdatePayload("rename", d1.index, id1, name2, null, 0, 0, "stone"));
        NetworkManager.broadcastUpdate(server, new SlotUpdatePayload("rename", d2.index, id2, name1, null, 0, 0, "stone"));
        ChatHelper.success(src, "§aSwapped names: §f" + id1 + " §7↔ §f" + id2);
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
        SlotData pre = SlotManager.getById(id);
        int boxCount = (pre != null && pre.shapeBoxes != null) ? pre.shapeBoxes.size() : 0;
        if (pre == null || boxCount == 0 || index >= boxCount) {
            ChatHelper.error(src, "Box index " + index + " is out of range (block has " + boxCount + " box(es)).");
            return 0;
        }
        UndoManager.pushUndoMutation(id, pre, "removeshape", getPlayerUuid(src));
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
                String animWarning = null;
                if (anim != null && anim.isAnimated()) {
                    bytes = anim.bytes();
                    animMeta = anim.mcmeta();
                    animWarning = anim.warning(); // 1.12 — capture trim/resize warning
                } else {
                    bytes = ImageProcessor.toPng(raw);
                    bytes = ImageProcessor.padToSquare(bytes);
                    bytes = ImageProcessor.replaceBackground(bytes);
                    bytes = ImageProcessor.resizeTo(bytes, size);
                }
                final byte[] fb = bytes;
                final String fa = animMeta;
                final String fWarn = animWarning;
                server.execute(() -> {
                    // 1.12 — relay any trim/resize warning to the player
                    if (fWarn != null) src.sendMessage(net.minecraft.text.Text.literal(fWarn));
                    SlotData d = SlotManager.getById(id);
                    if (d == null) { src.sendMessage(notFound(id)); return; }
                    UndoManager.pushUndoMutation(id, d, "retexture", getPlayerUuid(src));
                    SlotManager.updateTexture(id, fb);
                    if (fa != null) SlotManager.setAnimMeta(id, fa);
                    SlotManager.saveAll();
                    NetworkManager.broadcastUpdate(server,
                        new SlotUpdatePayload("retexture", d.index, id, null, fb,
                                d.lightLevel, d.hardness, d.soundType, null, null, fa));
                    ChatHelper.success(src, ChatHelper.formattedKey("cmd.texture_updated", id));
                    com.customblocks.core.HistoryTracker.record(getPlayerUuid(src), getPlayerName(src), "retextured", id);
                    { ServerPlayerEntity _rp = src.getPlayer(); if (_rp != null) { String _h = FirstUseHints.hint(_rp.getUuid(), "first_retexture"); if (_h != null) _rp.sendMessage(Text.literal(_h), false); } }
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
        ItemStack stack = new ItemStack(item, Math.max(1, amount)); // V4-14: no upper cap — operators trusted
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
            if (amount < 1) {
                ChatHelper.error(src, "Give amount must be at least 1.");
                return 0;
            }
            return cmdGive(src, id, amount, null);
        } catch (NumberFormatException ex) {
            ChatHelper.error(src, "Invalid give amount '" + raw + "'. Provide a positive integer.");
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

    /** 1.30 — Dedicated unfavorite: never accidentally re-adds. */
    private static int cmdUnfavorite(ServerCommandSource src, String rawId) {
        ServerPlayerEntity p = src.getPlayer();
        if (p == null) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.console_player_only"));
            return 0;
        }
        if (rawId == null || rawId.isBlank()) {
            // No args → list current favorites (same as /cb favorite)
            java.util.ArrayList<String> favs = new java.util.ArrayList<>(FavoritesManager.validatedSet(p.getUuid()));
            if (favs.isEmpty()) {
                ChatHelper.info(src, ChatHelper.formattedKey("cmd.favorite_list_empty"));
                return 1;
            }
            int max = 48;
            java.util.List<String> head = favs.subList(0, Math.min(max, favs.size()));
            ChatHelper.info(src, ChatHelper.formattedKey("cmd.favorite_list_summary", favs.size(), String.join("§7, §f", head)));
            return 1;
        }
        String id = sanitize(rawId);
        if (!SlotManager.hasId(id)) {
            src.sendError(notFound(id));
            return 0;
        }
        if (!FavoritesManager.isFavorite(p.getUuid(), id)) {
            ChatHelper.info(src, "§7[CB] §f" + id + " §7is not in your favorites.");
            return 1;
        }
        FavoritesManager.toggle(p.getUuid(), id, src.getServer()); // removes it
        ChatHelper.success(src, "§7[CB] §f✗ §f" + id + " §7removed from favorites.");
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
        // V4-07: warn if no instance of this block is within 32 blocks of any online player
        boolean nearPlayer = isBlockNearAnyPlayer(src.getServer(), d.index, 32);
        ChatHelper.success(src, ChatHelper.formattedKey("cmd.light_set", id, level)
            + (nearPlayer ? "" : " §7Block is far from all players — lighting will update when a player gets close."));
        return 1;
    }

    /** V4-07 — returns true if any placed instance of slotIndex is within maxDist blocks of any online player. */
    private static boolean isBlockNearAnyPlayer(MinecraftServer server, int slotIndex, int maxDist) {
        String slotKey = "slot_" + slotIndex;
        int maxDistSq = maxDist * maxDist;
        int chunkRadius = (maxDist >> 4) + 1;
        for (net.minecraft.server.world.ServerWorld world : server.getWorlds()) {
            for (net.minecraft.server.network.ServerPlayerEntity p : world.getPlayers()) {
                int cx = p.getChunkPos().x;
                int cz = p.getChunkPos().z;
                for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                    for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                        net.minecraft.world.chunk.WorldChunk chunk = world.getChunkManager().getWorldChunk(cx + dx, cz + dz);
                        if (chunk == null) continue;
                        for (net.minecraft.util.math.BlockPos pos : chunk.getBlockEntityPositions()) {
                            net.minecraft.block.BlockState st = chunk.getBlockState(pos);
                            if (st.getBlock() instanceof com.customblocks.block.SlotBlock sb
                                    && sb.getSlotKey().equals(slotKey)
                                    && p.getBlockPos().getSquaredDistance(pos) <= maxDistSq) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
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
        // V4-08: fail fast if slot would be needed but none are free
        if (!SlotManager.hasId("tab_icon") && SlotManager.freeSlots() <= 0) {
            ChatHelper.error(src, "All block slots are full. Free a slot before setting a tab icon.");
            return 0;
        }
        ChatHelper.info(src, ChatHelper.formattedKey("cmd.downloading_tab_icon"));
        MinecraftServer server = src.getServer();
        thread(() -> {
            try {
                byte[] bytes = ImageProcessor.downloadAndProcess(url).bytes();
                server.execute(() -> {
                    // V4-06: push undo before modifying tab_icon
                    SlotData oldTabIcon = SlotManager.getById("tab_icon");
                    UUID actorUuid = getPlayerUuid(src);
                    if (oldTabIcon != null) UndoManager.pushUndoMutation("tab_icon", oldTabIcon, "settabicon", actorUuid);
                    SlotManager.setTabIconTexture(bytes);
                    if (!SlotManager.hasId("tab_icon")) {
                        SlotManager.assign("tab_icon", "Tab Icon", bytes);
                        UndoManager.pushUndoCreate("tab_icon", actorUuid);
                    } else {
                        SlotManager.updateTexture("tab_icon", bytes);
                    }
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
        if (d == null) { src.sendError(notFound(id)); return 0; }
        UndoManager.pushUndoMutation(id, d, "clearface " + face, getPlayerUuid(src));
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
        if (d == null) { src.sendError(notFound(id)); return 0; }
        UndoManager.pushUndoMutation(id, d, "clearallfaces", getPlayerUuid(src));
        SlotManager.clearAllFaces(id);
        SlotManager.saveAll();
        NetworkManager.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("clearfaces", d.index, id, null, null,
                    d.lightLevel, d.hardness, d.soundType));
        ChatHelper.success(src, ChatHelper.formattedKey("cmd.all_faces_cleared", id));
        return 1;
    }

    /** Phase 9.1 — clear the player's undo and redo stacks. */
    private static int cmdUndoClear(ServerCommandSource src) {
        UUID uuid = getPlayerUuid(src);
        int size = UndoManager.undoSize(uuid);
        UndoManager.clearPlayer(uuid);
        ChatHelper.success(src, "§aUndo stack cleared. §8(" + size + " entr" + (size == 1 ? "y" : "ies") + " removed)");
        return 1;
    }

    /** Undo the last block modification (retexture, setface, setglow, delete, create, …). */
    private static int cmdUndo(ServerCommandSource src) { return cmdUndo(src, false); }
    private static int cmdUndo(ServerCommandSource src, boolean silent) {
        UUID uuid = getPlayerUuid(src);
        if (UndoManager.undoSize(uuid) == 0) {
            if (!silent) ChatHelper.info(src, ChatHelper.formattedKey("cmd.undo_nothing"));
            return 1;
        }

        // UND1 — if the top of the undo stack is a batch operation, require a confirmation
        // before executing it (two /cb undo calls within 10 seconds = confirmed).
        if (UndoManager.peekIsUndoBatch(uuid)) {
            Long armedAt = BATCH_UNDO_ARMED.get(uuid);
            boolean confirmed = armedAt != null && (System.currentTimeMillis() - armedAt) < BATCH_UNDO_ARM_MS;
            if (!confirmed) {
                BATCH_UNDO_ARMED.put(uuid, System.currentTimeMillis());
                int batchSize = UndoManager.peekUndoBatchSize(uuid);
                String batchDesc = UndoManager.peekUndoBatchDescription(uuid);
                ChatHelper.warn(src, "§e[CB] Next undo is a bulk operation: §f" + batchDesc
                    + " §e(§f" + batchSize + " §eblocks). Run §f/cb undo §eagain within 10s to confirm.");
                return 1;
            }
            // Confirmed — execute the batch undo
            BATCH_UNDO_ARMED.remove(uuid);
            return cmdUndoBatch(src, uuid, silent);
        }

        BATCH_UNDO_ARMED.remove(uuid); // clear any stale arm if user switched to a normal undo
        UndoManager.UndoEntry entry = UndoManager.popUndo(uuid);
        if (entry == null) { if (!silent) ChatHelper.info(src, ChatHelper.formattedKey("cmd.undo_nothing")); return 1; }

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
            if (!silent) {
                ChatHelper.success(src, ChatHelper.formattedKey("cmd.undo_create_done", entry.customId(),
                    UndoManager.undoSize(uuid)));
                if (UndoManager.undoSize(uuid) > 0)
                    ChatHelper.info(src, ChatHelper.formattedKey("cmd.undo_next_hint", UndoManager.peekUndoDescription(uuid)));
                { ServerPlayerEntity _up = src.getPlayer(); if (_up != null) { String _h = FirstUseHints.hint(_up.getUuid(), "first_undo"); if (_h != null) _up.sendMessage(Text.literal(_h), false); } }
            }
            return 1;
        }

        // ── Undo a mutation or a deletion ────────────────────────────────────
        SlotData prev = entry.previousState();
        // For mutations: capture current state for redo BEFORE restore (block still exists)
        if (!entry.wasDeleted()) {
            SlotData curForRedo = SlotManager.getById(prev.customId);
            if (curForRedo != null) {
                UndoManager.UndoEntry redoEntry = new UndoManager.UndoEntry(entry.customId(), snapshotForCmd(curForRedo), entry.description(), false, uuid);
                UndoManager.pushRedo(redoEntry);
            }
        }
        boolean restored = SlotManager.restoreSnapshot(prev, entry.wasDeleted());
        if (!restored) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.undo_slot_busy", entry.customId()));
            return 0;
        }
        // For deletions: push redo AFTER restore — block now exists, redo = delete it again
        if (entry.wasDeleted()) {
            UndoManager.pushRedo(new UndoManager.UndoEntry(entry.customId(), null, "delete", false, uuid));
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
                if (d.isShaped()) broadcastShape(server, d);
                if (d.noCollision) NetworkManager.broadcastUpdate(server, new SlotUpdatePayload(
                        "setcollision", d.index, d.customId, null, null, 0, 0, "stone", null, "false"));
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
        if (!silent) {
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.undo_mutation_done", entry.description(), entry.customId(),
                UndoManager.undoSize(uuid), UndoManager.redoSize(uuid)));
            if (UndoManager.undoSize(uuid) > 0)
                ChatHelper.info(src, ChatHelper.formattedKey("cmd.undo_next_hint", UndoManager.peekUndoDescription(uuid)));
            { ServerPlayerEntity _up = src.getPlayer(); if (_up != null) { String _h = FirstUseHints.hint(_up.getUuid(), "first_undo"); if (_h != null) _up.sendMessage(Text.literal(_h), false); } }
        }
        return 1;
    }

    /**
     * UND1 — Execute a confirmed batch undo (restore all blocks in the batch at once).
     * Called from cmdUndo after the player has confirmed the batch (double /cb undo within 10s).
     */
    private static int cmdUndoBatch(ServerCommandSource src, UUID uuid, boolean silent) {
        java.util.List<UndoManager.UndoEntry> entries = UndoManager.popUndoBatch(uuid);
        if (entries.isEmpty()) {
            ChatHelper.info(src, ChatHelper.formattedKey("cmd.undo_nothing"));
            return 1;
        }
        MinecraftServer server = src.getServer();
        int restored = 0;
        int failed = 0;
        java.util.List<UndoManager.UndoEntry> redoEntries = new java.util.ArrayList<>();
        for (UndoManager.UndoEntry entry : entries) {
            if (entry.wasDeleted() && entry.previousState() != null) {
                SlotData prev = entry.previousState();
                boolean ok = SlotManager.restoreSnapshot(prev, true);
                if (ok) {
                    SlotData d = SlotManager.getById(prev.customId);
                    if (d != null) {
                        NetworkManager.broadcastUpdate(server,
                            new SlotUpdatePayload("add", d.index, d.customId, d.displayName, d.texture,
                                d.lightLevel, d.hardness, d.soundType, null, null, d.animMeta));
                        for (var fe : d.faceTextures.entrySet())
                            NetworkManager.broadcastUpdate(server,
                                new SlotUpdatePayload("setface", d.index, d.customId, null, fe.getValue(),
                                    d.lightLevel, d.hardness, d.soundType, fe.getKey()));
                        if (d.isShaped()) broadcastShape(server, d);
                    }
                    restored++;
                    // REDO2: each restored block can be re-deleted by a batch redo
                    redoEntries.add(new UndoManager.UndoEntry(entry.customId(), null, "delete", false));
                } else {
                    failed++;
                }
            }
        }
        if (restored > 0) {
            SlotManager.saveAll();
            // REDO2: push batch redo so /cb redo can re-delete all restored blocks
            if (!redoEntries.isEmpty()) {
                UndoManager.pushRedoBatch("bulk-redelete " + restored, redoEntries, uuid);
            }
        }
        if (!silent) {
            ChatHelper.success(src, "§a[CB] Bulk undo complete — restored §f" + restored + " §ablock(s)."
                + (failed > 0 ? " §c" + failed + " block(s) could not be restored (slot conflict)." : ""));
        }
        return 1;
    }

    /** Redo the last undone action. */
    private static int cmdRedo(ServerCommandSource src) {
        UUID uuid = getPlayerUuid(src);
        if (UndoManager.redoSize(uuid) == 0) {
            ChatHelper.info(src, ChatHelper.formattedKey("cmd.redo_nothing"));
            return 1;
        }

        // REDO2 — if top of redo stack is a batch, require confirmation (double /cb redo within 10s)
        if (UndoManager.peekIsRedoBatch(uuid)) {
            Long armedAt = BATCH_REDO_ARMED.get(uuid);
            boolean confirmed = armedAt != null && (System.currentTimeMillis() - armedAt) < BATCH_UNDO_ARM_MS;
            if (!confirmed) {
                BATCH_REDO_ARMED.put(uuid, System.currentTimeMillis());
                int batchSize = UndoManager.peekRedoBatchSize(uuid);
                String batchDesc = UndoManager.peekRedoBatchDescription(uuid);
                ChatHelper.warn(src, "§e[CB] Next redo is a bulk operation: §f" + batchDesc
                    + " §e(§f" + batchSize + " §eblocks). Run §f/cb redo §eagain within 10s to confirm.");
                return 1;
            }
            BATCH_REDO_ARMED.remove(uuid);
            return cmdRedoBatch(src, uuid);
        }

        BATCH_REDO_ARMED.remove(uuid);
        UndoManager.UndoEntry entry = UndoManager.popRedo(uuid);
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
                UndoManager.redoSize(uuid)));
            if (UndoManager.redoSize(uuid) > 0)
                ChatHelper.info(src, ChatHelper.formattedKey("cmd.redo_next_hint", UndoManager.peekRedoDescription(uuid)));
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
            UndoManager.redoSize(uuid), UndoManager.undoSize(uuid)));
        if (UndoManager.redoSize(uuid) > 0)
            ChatHelper.info(src, ChatHelper.formattedKey("cmd.redo_next_hint", UndoManager.peekRedoDescription(uuid)));
        return 1;
    }

    /** REDO2 — Execute a confirmed batch redo (re-delete all blocks in the redo batch). */
    private static int cmdRedoBatch(ServerCommandSource src, UUID uuid) {
        java.util.List<UndoManager.UndoEntry> entries = UndoManager.popRedoBatch(uuid);
        if (entries.isEmpty()) {
            ChatHelper.info(src, ChatHelper.formattedKey("cmd.redo_nothing"));
            return 1;
        }
        MinecraftServer server = src.getServer();
        int deleted = 0;
        int failed = 0;
        java.util.List<UndoManager.UndoEntry> undoEntries = new java.util.ArrayList<>();
        for (UndoManager.UndoEntry entry : entries) {
            SlotData d = SlotManager.getById(entry.customId());
            if (d != null) {
                undoEntries.add(new UndoManager.UndoEntry(entry.customId(), snapshotForCmd(d), "delete", true));
                int idx = d.index;
                SlotManager.remove(entry.customId());
                NetworkManager.broadcastUpdate(server,
                    new SlotUpdatePayload("remove", idx, entry.customId(), null, null, 0, 0, "stone"));
                deleted++;
            } else {
                failed++;
            }
        }
        if (deleted > 0) {
            SlotManager.saveAll();
            if (!undoEntries.isEmpty()) {
                UndoManager.pushUndoBatch("bulk-delete " + deleted + " (redo)", undoEntries, uuid);
            }
        }
        ChatHelper.success(src, "§a[CB] Bulk redo complete — deleted §f" + deleted + " §ablock(s)."
            + (failed > 0 ? " §c" + failed + " block(s) not found (already gone)." : ""));
        return 1;
    }

    /** Undo N times in a loop, sending one summary message at the end. */
    private static int cmdUndoN(ServerCommandSource src, int count) {
        int done = 0;
        int skipped = 0;
        for (int i = 0; i < count; i++) {
            if (UndoManager.undoSize(getPlayerUuid(src)) == 0) { skipped = count - i; break; }
            int r = cmdUndo(src, true);
            if (r == 0) break;
            done++;
        }
        if (done == 0) {
            ChatHelper.info(src, ChatHelper.formattedKey("cmd.undo_nothing"));
        } else if (skipped > 0) {
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.undo_batch_done", done) +
                " §7(" + skipped + " skipped — nothing more to undo)");
        } else {
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.undo_batch_done", done));
        }
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

    /** V4-11 — show current texture size and available options when no size is given. */
    private static int cmdResizeInfo(ServerCommandSource src, String id) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        SlotData d = SlotManager.getById(id);
        String sizeStr = "no texture";
        if (d.texture != null && d.texture.length >= 24) {
            int w = ((d.texture[16] & 0xFF) << 24) | ((d.texture[17] & 0xFF) << 16)
                  | ((d.texture[18] & 0xFF) << 8)  |  (d.texture[19] & 0xFF);
            sizeStr = w + "px";
        }
        src.sendMessage(net.minecraft.text.Text.literal(
            "§eResize §8— §f" + d.displayName + "\n"
          + "§7Current size: §f" + sizeStr + "\n"
          + "§7Available: §f16, 32, 64, 128, 256\n"
          + "§7Usage: §f/cb resize " + id + " <size>"));
        return 1;
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


    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
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
                    // Item 7.35 / DoS protection: cap individual image file size to 8MB
                    if (img.length() > 8 * 1024 * 1024) {
                        failed.add(id + "(file > 8MB)");
                        continue;
                    }
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

    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
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
        } catch (IOException | RuntimeException e) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.export_failed", e.getMessage())); }
        return 1;
    }

    private static int cmdList(ServerCommandSource src) {
        // V4-48: compact summary + export buttons
        int used = SlotManager.usedSlots(), free = SlotManager.freeSlots();
        int broken = SlotManager.brokenBlocks().size();
        String brokenStr = broken > 0 ? " §c· " + broken + " broken§r" : "";

        src.sendMessage(Text.literal("§b§lCustomBlocks: §f" + used + " §7blocks · §f" + free + " §7slots free" + brokenStr));

        // Export buttons — only shown to players; console gets text message instead
        ServerPlayerEntity p = src.getPlayer();
        if (p != null) {
            net.minecraft.text.MutableText exportLine = Text.literal("§7Export: ");
            exportLine.append(Text.literal("[TXT]").styled(s -> s
                .withColor(net.minecraft.util.Formatting.GREEN)
                .withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.RUN_COMMAND, "/cb list export txt"))
                .withHoverEvent(new net.minecraft.text.HoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT, Text.literal("Write block-list.txt")))));
            exportLine.append(Text.literal(" "));
            exportLine.append(Text.literal("[CSV]").styled(s -> s
                .withColor(net.minecraft.util.Formatting.YELLOW)
                .withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.RUN_COMMAND, "/cb list export csv"))
                .withHoverEvent(new net.minecraft.text.HoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT, Text.literal("Write block-list.csv")))));
            exportLine.append(Text.literal(" "));
            exportLine.append(Text.literal("[JSON]").styled(s -> s
                .withColor(net.minecraft.util.Formatting.AQUA)
                .withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.RUN_COMMAND, "/cb list export json"))
                .withHoverEvent(new net.minecraft.text.HoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT, Text.literal("Write block-list.json")))));
            p.sendMessage(exportLine, false);
        } else {
            src.sendMessage(Text.literal("§7Use /cb list export txt|csv|json to export the block list."));
        }
        return 1;
    }

    private static int cmdListExport(ServerCommandSource src, String format) {
        java.util.List<SlotData> sorted = new java.util.ArrayList<>(SlotManager.allSlots());
        sorted.removeIf(d -> "tab_icon".equals(d.customId));
        sorted.sort(java.util.Comparator.comparingInt(d -> d.index));
        java.nio.file.Path exportDir = java.nio.file.Path.of("config", "customblocks");
        try {
            java.nio.file.Files.createDirectories(exportDir);
        } catch (Exception e) {
            ChatHelper.error(src, "Could not create export directory: " + e.getMessage()); return 0;
        }
        try {
            switch (format) {
                case "txt" -> {
                    java.nio.file.Path out = exportDir.resolve("block-list.txt");
                    StringBuilder sb = new StringBuilder();
                    for (SlotData d : sorted) sb.append(d.customId).append("  ").append(d.displayName).append("\n");
                    java.nio.file.Files.writeString(out, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
                    ChatHelper.success(src, "§aExported §f" + sorted.size() + " §ablocks to §fconfig/customblocks/block-list.txt");
                }
                case "csv" -> {
                    java.nio.file.Path out = exportDir.resolve("block-list.csv");
                    StringBuilder sb = new StringBuilder("id,name,light,hardness,sound,collision\n");
                    for (SlotData d : sorted)
                        sb.append(csvEscape(d.customId)).append(",")
                          .append(csvEscape(d.displayName)).append(",")
                          .append(d.lightLevel).append(",")
                          .append(d.hardness).append(",")
                          .append(csvEscape(d.soundType)).append(",")
                          .append(d.noCollision ? "false" : "true").append("\n");
                    java.nio.file.Files.writeString(out, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
                    ChatHelper.success(src, "§aExported §f" + sorted.size() + " §ablocks to §fconfig/customblocks/block-list.csv");
                }
                case "json" -> {
                    java.nio.file.Path out = exportDir.resolve("block-list.json");
                    StringBuilder sb = new StringBuilder("[\n");
                    for (int i = 0; i < sorted.size(); i++) {
                        SlotData d = sorted.get(i);
                        sb.append("  {\"id\":\"").append(jsonEscape(d.customId)).append("\",")
                          .append("\"name\":\"").append(jsonEscape(d.displayName)).append("\",")
                          .append("\"light\":").append(d.lightLevel).append(",")
                          .append("\"hardness\":").append(d.hardness).append(",")
                          .append("\"sound\":\"").append(jsonEscape(d.soundType)).append("\",")
                          .append("\"collision\":").append(!d.noCollision).append("}");
                        if (i < sorted.size() - 1) sb.append(",");
                        sb.append("\n");
                    }
                    sb.append("]");
                    java.nio.file.Files.writeString(out, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
                    ChatHelper.success(src, "§aExported §f" + sorted.size() + " §ablocks to §fconfig/customblocks/block-list.json");
                }
                default -> { ChatHelper.error(src, "Unknown format: " + format + ". Use txt, csv, or json."); return 0; }
            }
        } catch (Exception e) {
            ChatHelper.error(src, "Export failed: " + e.getMessage()); return 0;
        }
        return 1;
    }

    private static String csvEscape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
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
        GuiManager.playClick(p);
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
            String sample = com.customblocks.core.VoiceCatalog.formatForMode(mode, "cmd.voice.set", mode);
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



    /** V4-28 — Add lore lines to an item stack before giving it. */
    private static ItemStack withToolLore(net.minecraft.item.Item item, String... loreLines) {
        ItemStack stack = new ItemStack(item, 1);
        java.util.List<net.minecraft.text.Text> ll = new java.util.ArrayList<>();
        for (String line : loreLines)
            ll.add(net.minecraft.text.Text.literal(line).styled(s -> s.withItalic(false)));
        stack.set(net.minecraft.component.DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(ll));
        return stack;
    }

    public static int cmdGiveSquareInternal(ServerCommandSource src, String color) {
        String c = color.toLowerCase().trim();
        if (!c.equals("black") && !c.equals("yellow") && !c.equals("green") && !c.equals("red")) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.give_color_bad_bwy", color)); return 0;
        }
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, c + "_square");
        net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
        if (item == null || item == net.minecraft.item.Items.AIR) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.give_square_not_found")); return 0; }
        try {
            src.getPlayerOrThrow().getInventory().insertStack(withToolLore(item,
                "§7Right-click a placed custom block to §eswap§7 it to the matching color variant.",
                "§8Use §f/cb square <color>§8 for black, yellow, green, or red."));
            String disp = Character.toUpperCase(c.charAt(0)) + c.substring(1);
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.give_square_done", disp));
            { ServerPlayerEntity _sp = src.getPlayer(); if (_sp != null) { String _h = FirstUseHints.hint(_sp.getUuid(), "hold_square"); if (_h != null) _sp.sendMessage(Text.literal(_h), false); } }
        } catch (Exception ex) { CustomBlocksMod.LOGGER.error("[CB] cmdGiveSquare failed", ex); ChatHelper.error(src, ChatHelper.formattedKey("cmd.gui_open_failed", ex.getMessage())); return 0; }
        return 1;
    }

    public static int cmdGiveTriangleInternal(ServerCommandSource src, String color) {
        String c = color.toLowerCase().trim();
        if (!c.equals("black") && !c.equals("yellow") && !c.equals("green") && !c.equals("red")) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.give_color_bad_bwy", color)); return 0;
        }
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, c + "_triangle");
        net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
        if (item == null || item == net.minecraft.item.Items.AIR) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.give_triangle_not_found")); return 0; }
        try {
            src.getPlayerOrThrow().getInventory().insertStack(withToolLore(item,
                "§7Right-click a placed custom block: §eremove its background color",
                "§7Mode: Edge fill (perimeter) or Full fill (everywhere)",
                "§7Switch mode: §f/cb trianglemode edge §7or §f/cb trianglemode full",
                "§7Adjust sensitivity: §f/cb tolerance <10-80>"));
            String disp = Character.toUpperCase(c.charAt(0)) + c.substring(1);
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.give_triangle_done", disp));
            { ServerPlayerEntity _tp = src.getPlayer(); if (_tp != null) { String _h = FirstUseHints.hint(_tp.getUuid(), "hold_triangle"); if (_h != null) _tp.sendMessage(Text.literal(_h), false); } }
        } catch (Exception ex) { CustomBlocksMod.LOGGER.error("[CB] cmdGiveTriangle failed", ex); ChatHelper.error(src, ChatHelper.formattedKey("cmd.gui_open_failed", ex.getMessage())); return 0; }
        return 1;
    }

    public static int cmdGiveRectangleInternal(ServerCommandSource src) {
        net.minecraft.util.Identifier rectId = net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, "rainbow_rectangle");
        net.minecraft.item.Item rectItem = net.minecraft.registry.Registries.ITEM.get(rectId);
        if (rectItem == null || rectItem == net.minecraft.item.Items.AIR) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.give_rainbow_rectangle_not_found")); return 0;
        }
        try {
            src.getPlayerOrThrow().getInventory().insertStack(withToolLore(rectItem,
                "§7Right-click two corners of a flat surface to §efill it§7 with custom blocks.",
                "§8Left-click to clear placed blocks. Works on any flat face."));
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.give_rainbow_rectangle_done"));
        } catch (Exception ex) { CustomBlocksMod.LOGGER.error("[CB] cmdGiveRectangle failed", ex); ChatHelper.error(src, ChatHelper.formattedKey("cmd.gui_open_failed", ex.getMessage())); return 0; }
        return 1;
    }

    public static int cmdGiveHexagonInternal(ServerCommandSource src) {
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, "golden_hexagon");
        net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
        if (item == null || item == net.minecraft.item.Items.AIR) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.give_golden_hexagon_not_found")); return 0; }
        try {
            src.getPlayerOrThrow().getInventory().insertStack(withToolLore(item,
                "§7Right-click a block face: §erotate 90° CW",
                "§7Sneak + right-click: §erotate 90° CCW",
                "§7Air-click: §etoggle Single-Face / All-Faces mode",
                "§8Auto-detects which face you're aiming at."));
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.give_golden_hexagon_done"));
        } catch (Exception ex) { CustomBlocksMod.LOGGER.error("[CB] cmdGiveHexagon failed", ex); ChatHelper.error(src, ChatHelper.formattedKey("cmd.gui_open_failed", ex.getMessage())); return 0; }
        return 1;
    }

    public static int cmdGiveBrushInternal(ServerCommandSource src) {
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, "lumina_brush");
        net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
        if (item == null || item == net.minecraft.item.Items.AIR) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.give_lumina_brush_not_found")); return 0; }
        try {
            src.getPlayerOrThrow().getInventory().insertStack(withToolLore(item,
                "§7Right-click block: §eopen Properties editor §7(light, hardness, sound)",
                "§7Sneak + right-click: §ecopy properties to clipboard",
                "§7Right-click with clipboard: §epaste all properties onto block",
                "§7Air-click: §eopen block picker"));
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.give_lumina_brush_done"));
        } catch (Exception ex) { CustomBlocksMod.LOGGER.error("[CB] cmdGiveBrush failed", ex); ChatHelper.error(src, ChatHelper.formattedKey("cmd.gui_open_failed", ex.getMessage())); return 0; }
        return 1;
    }

    public static int cmdGiveChiselInternal(ServerCommandSource src) {
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, "amethyst_chisel");
        net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
        if (item == null || item == net.minecraft.item.Items.AIR) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.give_amethyst_chisel_not_found")); return 0; }
        try {
            src.getPlayerOrThrow().getInventory().insertStack(withToolLore(item,
                "§7Right-click block: §eopen Shape Editor §7(hitbox, presets, multi-box)",
                "§7Sneak + right-click: §ecopy block's hitbox to clipboard",
                "§7Right-click with clipboard: §epaste hitbox onto block",
                "§7Air-click: §eopen block picker"));
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.give_amethyst_chisel_done"));
        } catch (Exception ex) { CustomBlocksMod.LOGGER.error("[CB] cmdGiveChisel failed", ex); ChatHelper.error(src, ChatHelper.formattedKey("cmd.gui_open_failed", ex.getMessage())); return 0; }
        return 1;
    }

    public static int cmdGiveDeleterInternal(ServerCommandSource src) { // NF2
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, "deleter");
        net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
        if (item == null || item == net.minecraft.item.Items.AIR) { ChatHelper.error(src, "Deleter item not found."); return 0; }
        try {
            src.getPlayerOrThrow().getInventory().insertStack(new net.minecraft.item.ItemStack(item, 1));
            ChatHelper.success(src, "§aGiven Deleter tool.");
        } catch (Exception ex) { ChatHelper.error(src, "Failed: " + ex.getMessage()); return 0; }
        return 1;
    }

    public static int cmdGiveDiamondInternal(ServerCommandSource src) {
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, "diamond_triangle");
        net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
        if (item == null || item == net.minecraft.item.Items.AIR) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.give_diamond_triangle_not_found")); return 0; }
        try {
            src.getPlayerOrThrow().getInventory().insertStack(new ItemStack(item, 1));
            ChatHelper.success(src, ChatHelper.formattedKey("cmd.give_diamond_triangle_done"));
        } catch (Exception ex) { CustomBlocksMod.LOGGER.error("[CB] cmdGiveDiamond failed", ex); ChatHelper.error(src, ChatHelper.formattedKey("cmd.gui_open_failed", ex.getMessage())); return 0; }
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
            CustomBlocksMod.LOGGER.error("[CB] cmdGiveCustomColorTools failed", ex);
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
        // ── 3.8 Full color resolution ─────────────────────────────────────────
        // Priority: built-in green/yellow/black → ColorLibrary name → hex string
        String colorInput = colorRaw == null ? "" : colorRaw.trim();
        String colorLower = colorInput.toLowerCase(java.util.Locale.ROOT);

        int[] rgb;
        String colorLabel;

        if (colorLower.equals("green")) {
            rgb = CustomBlocksConfig.builtInTriangleRgb("green", 30, 140, 30);
            colorLabel = "Green";
        } else if (colorLower.equals("yellow")) {
            rgb = CustomBlocksConfig.builtInTriangleRgb("yellow", 240, 200, 20);
            colorLabel = "Yellow";
        } else if (colorLower.equals("black")) {
            rgb = new int[]{20, 20, 20};
            colorLabel = "Black";
        } else {
            // Try ColorLibrary name lookup first, then hex parse
            String resolved = ColorLibrary.resolve(colorInput);
            if (resolved != null) {
                int rgbInt = Integer.parseInt(resolved.startsWith("#") ? resolved.substring(1) : resolved, 16);
                rgb = new int[]{(rgbInt >> 16) & 0xFF, (rgbInt >> 8) & 0xFF, rgbInt & 0xFF};
                // Use the library's canonical name if it was a named color
                ColorLibrary.LibColor libCol = null;
                for (ColorLibrary.LibColor c : ColorLibrary.ALL) {
                    if (c.hex().equalsIgnoreCase(resolved)) { libCol = c; break; }
                }
                colorLabel = (libCol != null) ? libCol.name()
                    : Character.toUpperCase(colorInput.charAt(0)) + colorInput.substring(1);
            } else {
                List<String> suggestions = ColorLibrary.suggest(colorInput);
                String hint = suggestions.isEmpty() ? "" : " §7Did you mean: §f" + String.join("§7, §f", suggestions) + "§7?";
                ChatHelper.error(src, "§cUnknown color '§f" + colorInput + "§c'. Use a color name (red, coral, navy...) or hex code (#FF5500)." + hint);
                return 0;
            }
        }

        if (!CustomBlocksConfig.isColorToolModeConfigured()) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.bulk_unconfigured_color_mode"));
            return 0;
        }

        // Use a URL-safe color key derived from the label for block IDs
        String color = colorLabel.toLowerCase(java.util.Locale.ROOT).replace(" ", "_");

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

    // 1.22 — Rate-limit /cb sync to once per 10 seconds per player
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long> SYNC_COOLDOWN =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SYNC_COOLDOWN_MS = 10_000L;

    // REL1 — prevent concurrent /cb reload calls
    private static final java.util.concurrent.atomic.AtomicBoolean RELOAD_IN_PROGRESS =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // UND1 — batch-undo confirmation arming (UUID -> arm timestamp)
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long> BATCH_UNDO_ARMED =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long BATCH_UNDO_ARM_MS = 10_000L;

    // REDO2 — batch-redo confirmation arming (UUID -> arm timestamp)
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long> BATCH_REDO_ARMED =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static int cmdSync(ServerCommandSource src) {
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) {
            ChatHelper.error(src, "This command can only be used by a player.");
            return 0;
        }
        long now = System.currentTimeMillis();
        Long last = SYNC_COOLDOWN.get(player.getUuid());
        if (last != null && (now - last) < SYNC_COOLDOWN_MS) {
            long waitSec = (SYNC_COOLDOWN_MS - (now - last)) / 1000 + 1;
            ChatHelper.warn(player, "§7Please wait §f" + waitSec + "s §7before re-syncing.");
            return 0;
        }
        SYNC_COOLDOWN.put(player.getUuid(), now);
        ChatHelper.info(player, "§7Re-syncing block textures…");
        com.customblocks.network.NetworkManager.sendFullSync(player);
        return 1;
    }

    /** 1.23 — Re-enable the broken-texture warning for a previously suppressed block. */
    private static int cmdUnsuppress(ServerCommandSource src, String id) {
        if (!SlotManager.hasId(id)) {
            ChatHelper.error(src, "Unknown block ID: " + id);
            return 0;
        }
        com.customblocks.core.SlotManager.setSuppressed(id, false);
        ChatHelper.success(src, "§aWarning restored for §f" + id + "§a. It will now appear in the broken-blocks view if it has no texture.");
        return 1;
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

    /** 1.29 — /cb config hologram <true|false>  — toggle hologram visibility from command line. */
    private static int cmdConfigHologram(ServerCommandSource src, boolean enabled) {
        com.customblocks.CustomBlocksConfig.hologramEnabled = enabled;
        com.customblocks.CustomBlocksConfig.save();
        if (enabled) {
            ChatHelper.success(src, "§a[CB] Hologram enabled. Changes apply immediately.");
            if (com.customblocks.CustomBlocksConfig.hologramHeight <= 0.0f) {
                ChatHelper.info(src, "§e[CB] Hologram height is 0 — set hologram-height above 0 in config for visible holograms.");
            }
        } else {
            ChatHelper.info(src, "§7[CB] Hologram disabled.");
        }
        return 1;
    }

    private static int cmdReload(ServerCommandSource src) {
        // REL1 — guard: refuse if block batch-loading is still in progress
        if (SlotManager.isStartupLoadInProgress()) {
            ChatHelper.warn(src, "§e[CB] Block loading is still in progress — please wait a moment before reloading.");
            return 0;
        }
        // REL1 — guard: refuse if another reload is already running
        if (!RELOAD_IN_PROGRESS.compareAndSet(false, true)) {
            ChatHelper.warn(src, "§e[CB] A reload is already in progress — please wait for it to finish.");
            return 0;
        }

        ChatHelper.success(src, "§7[CB] Reloading config, blocks, and resource pack...");
        // REL1 — use flushSaveForReload() instead of flushSave() so IO_EXECUTOR stays alive
        SlotManager.flushSaveForReload();
        net.minecraft.server.MinecraftServer server = src.getServer();
        int onlineCount = server.getPlayerManager().getPlayerList().size();
        new Thread(() -> {
            try {
                // Step 1 — Config
                try {
                    com.customblocks.CustomBlocksConfig.load();
                } catch (Exception e) {
                    IncidentRecorder.record("reload", "CustomBlocksConfig.load", e);
                    server.execute(() ->
                        ChatHelper.error(src, "§c[CB] Config reload failed: §f" + e.getMessage()
                            + "§c. Previous config remains active."));
                    return;
                }
                // Step 2 — Block data
                try {
                    SlotManager.loadAll();
                } catch (Exception e) {
                    IncidentRecorder.record("reload", "SlotManager.loadAll", e);
                    server.execute(() ->
                        ChatHelper.error(src, "§c[CB] Block reload failed: §f" + e.getMessage()));
                    return;
                }
                // Step 2b — REL1: wait for the tick-based batch loader to finish before rebuilding pack
                // (loadAll() queues slots for batch processing on server ticks; rebuilding before
                // they are all processed would generate a pack with incomplete block data)
                long deadline = System.currentTimeMillis() + 30_000L;
                while (SlotManager.isStartupLoadInProgress() && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(100); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); break;
                    }
                }
                if (SlotManager.isStartupLoadInProgress()) {
                    server.execute(() -> ChatHelper.warn(src,
                        "§e[CB] Block loading took over 30s — pack may be incomplete. Run /cb reload again once loading is done."));
                    return;
                }
                // Step 3 — Resource pack rebuild + push
                try {
                    com.customblocks.ResourcePackManager.scheduleRebuild(server);
                } catch (Exception e) {
                    IncidentRecorder.record("reload", "ResourcePackManager.scheduleRebuild", e);
                    server.execute(() ->
                        ChatHelper.error(src, "§c[CB] Reload failed during pack generation: §f" + e.getMessage()
                            + "§c. Blocks were reloaded but pack was NOT pushed."));
                    return;
                }
                // Step 4 — Broadcast data sync
                server.execute(() -> {
                    CustomBlocksMod.broadcastFullSync(server);
                    if (onlineCount == 0) {
                        ChatHelper.success(src, "§a[CB] Reload complete. No players online — pack will be sent on next join.");
                    } else {
                        ChatHelper.success(src, "§a[CB] Reload complete. Config §a✓ §fBlocks §a✓ §fResource pack pushed to §f"
                            + onlineCount + "§a player(s).");
                    }
                });
            } finally {
                RELOAD_IN_PROGRESS.set(false);
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
            CustomBlocksMod.LOGGER.error("[CB] cmdEditorPicker failed", ex);
            com.customblocks.gui.GuiManager.logError();
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.gui_open_failed", ex.getMessage()));
        }
        return 1;
    }

    private static int cmdGui(ServerCommandSource src) {
        try {
            ServerPlayerEntity player = src.getPlayerOrThrow();
            GuiManager.openWelcomeGui(player);
        } catch (Exception ex) {
            CustomBlocksMod.LOGGER.error("[CB] cmdGui failed", ex);
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
            CustomBlocksMod.LOGGER.error("[CB] cmdEditor failed", ex);
            com.customblocks.gui.GuiManager.logError();
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.gui_open_failed", ex.getMessage()));
        }
        return 1;
    }


    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
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

    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
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

    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
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
        } catch (IOException | RuntimeException e) {
            ChatHelper.error(src, ChatHelper.formattedKey("cmd.export_failed", e.getMessage()));
        }
        return 1;
    }

    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
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
        } catch (IOException | RuntimeException e) {
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
                        } catch (RuntimeException ex) {
                            ChatHelper.error(src, ChatHelper.formattedKey("cmd.cloud_parse_error", ex.getMessage()));
                        }
                    });
                } else {
                    src.getServer().execute(() -> {
                        ChatHelper.error(src, ChatHelper.formattedKey("cmd.cloud_download_failed", resp.statusCode()));
                    });
                }
            } catch (IOException | InterruptedException | RuntimeException e) {
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
            case "bulk", "bulkgui" -> "bulk  — open Bulk Operations hub GUI";
            case "bulkrename"    -> "bulkrename <scope> --prefix <text> | --suffix <text> | --replace <old> --with <new>";
            case "bulkreid"      -> "bulkreid <scope> --replace <old> --with <new>  — renames block IDs";
            case "bulkproperty"  -> "bulkproperty <scope> <sound|glow|hardness|collision> <value>";
            case "bulkexport"    -> "bulkexport <scope>  — export blocks + textures as ZIP";
            case "bulkmove"      -> "bulkmove <scope> <category>  — move blocks to a category";
            case "bulkduplicate" -> "bulkduplicate <scope> [--suffix <text>]  — clone blocks with new IDs";
            case "bulklock"      -> "bulklock <scope>  — lock blocks against editing";
            case "bulkfavorite"  -> "bulkfavorite <scope>  — star/unstar blocks";
            case "bulkshape"     -> "bulkshape <scope> <preset>  — presets: full slab thin carpet pillar small micro";
            case "bulksound"     -> "bulksound <scope> <sound>  — set sound type for multiple blocks";
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
            case "find"         -> "find <blockId> [radius]  — scan loaded chunks for placed instances (--count for count only)";
            case "shapepreview" -> "shapepreview <preset>  — show info about a shape preset";
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
    // 7.39 — Option B: targeted chunk relight instead of 274,625-position scan.
    // We tell the lighting engine to recheck each placed block's own chunk and
    // its immediate neighbours. Vanilla propagates glow changes automatically.
    private static void triggerGlowUpdate(MinecraftServer server, int slotIndex) {
        String slotKey = "slot_" + slotIndex;
        server.execute(() -> {
            for (net.minecraft.server.world.ServerWorld world : server.getWorlds()) {
                for (net.minecraft.server.network.ServerPlayerEntity p : world.getPlayers()) {
                    int cx = p.getChunkPos().x;
                    int cz = p.getChunkPos().z;
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            net.minecraft.world.chunk.WorldChunk chunk = world.getChunkManager()
                                    .getWorldChunk(cx + dx, cz + dz);
                            if (chunk == null) continue;
                            chunk.getBlockEntityPositions().stream()
                                .filter(pos -> {
                                    net.minecraft.block.BlockState st = chunk.getBlockState(pos);
                                    return st.getBlock() instanceof com.customblocks.block.SlotBlock sb
                                        && sb.getSlotKey().equals(slotKey);
                                })
                                .forEach(pos -> world.getLightingProvider().checkBlock(pos));
                        }
                    }
                }
            }
        });
    }

    private static java.util.UUID getPlayerUuid(net.minecraft.server.command.ServerCommandSource src) {
        var p = src.getPlayer();
        return p != null ? p.getUuid() : null;
    }

    /** V4-12: Raycast from player crosshair to auto-detect which face they're looking at. Returns null if not aimed at a block. */
    private static String autoDetectFace(ServerCommandSource src) {
        ServerPlayerEntity p = src.getPlayer();
        if (p == null) return null;
        net.minecraft.util.hit.HitResult hit = p.raycast(5.0, 0.0f, false);
        if (hit instanceof net.minecraft.util.hit.BlockHitResult bhr) {
            return switch (bhr.getSide()) {
                case UP    -> "top";
                case DOWN  -> "bottom";
                case NORTH -> "north";
                case SOUTH -> "south";
                case EAST  -> "east";
                case WEST  -> "west";
            };
        }
        return null;
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
                // 1.15 — atomic write
                java.nio.file.Path outTmp = exportDir.resolve(id + ".png.tmp");
                java.nio.file.Files.write(outTmp, d.texture);
                java.nio.file.Files.move(outTmp, outFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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

    // ════════════════════════════════════════════════════════════════════════
    // Phase 2.2 — Bulk operation commands
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Parse a greedy-string args line into a map of flags and a leading scope token.
     * Format: [scope] [--flag1 value1] [--flag2 value2] [--dryrun]
     * Returns map with "scope" key and flag keys (without "--").
     */
    private static java.util.Map<String, String> parseBulkArgs(String args) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        String[] tokens = args.trim().split("\\s+");
        StringBuilder scope = new StringBuilder();
        int i = 0;
        // Collect leading non-flag tokens as scope (until we hit a --)
        while (i < tokens.length && !tokens[i].startsWith("--")) {
            if (scope.length() > 0) scope.append(" ");
            scope.append(tokens[i]);
            i++;
        }
        out.put("scope", scope.toString().trim());
        // Parse --key value pairs
        while (i < tokens.length) {
            String tok = tokens[i];
            if (tok.startsWith("--")) {
                String key = tok.substring(2).toLowerCase(java.util.Locale.ROOT);
                if (i + 1 < tokens.length && !tokens[i + 1].startsWith("--")) {
                    i++;
                    out.put(key, tokens[i]);
                } else {
                    out.put(key, "true");
                }
            }
            i++;
        }
        return out;
    }

    /** Common scope-to-blocks resolver for bulk commands. Returns empty list + error if scope is blank/missing. */
    private static List<SlotData> resolveScope(ServerCommandSource src, String scope) {
        if (scope == null || scope.isBlank()) {
            ChatHelper.error(src, "§cProvide a scope: §fall §7| §fcategory:<key> §7| §fname:<text> §7| §f<id1,id2,...>");
            return null;
        }
        UUID playerUuid = getPlayerUuid(src);
        List<SlotData> blocks = com.customblocks.core.BulkScope.resolve(scope, playerUuid);
        if (blocks.isEmpty()) {
            ChatHelper.error(src, "§cNo blocks matched scope: §f" + scope);
            return null;
        }
        return blocks;
    }

    /** Dry-run or threshold guard — returns true if operation should proceed. */
    private static boolean bulkGuard(ServerCommandSource src, List<SlotData> blocks,
                                     java.util.Map<String, String> flags, String opName) {
        boolean dryRun = flags.containsKey("dryrun") || flags.containsKey("dry-run");
        int count = blocks.size();
        int threshold = com.customblocks.CustomBlocksConfig.bulkConfirmThreshold;
        if (dryRun) {
            ChatHelper.info(src, "§e[Dry run] §7" + opName + " would affect §f" + count + " §7block(s):");
            blocks.stream().limit(10).forEach(d -> ChatHelper.info(src, "  §b" + d.customId + " §7— " + d.displayName));
            if (count > 10) ChatHelper.info(src, "  §8... and " + (count - 10) + " more.");
            return false;
        }
        if (count > threshold && !flags.containsKey("apply") && !flags.containsKey("confirm")) {
            ChatHelper.warn(src, "§e" + opName + " will affect §f" + count + " §eblocks. "
                    + "Add §b--apply §eto confirm, or §b--dryrun §eto preview.");
            return false;
        }
        return true;
    }

    // ── bulkrename ──────────────────────────────────────────────────────────

    private static int cmdBulkRename(ServerCommandSource src, String args) {
        java.util.Map<String, String> flags = parseBulkArgs(args);
        List<SlotData> blocks = resolveScope(src, flags.get("scope"));
        if (blocks == null) return 0;
        if (!bulkGuard(src, blocks, flags, "Bulk Rename")) return 1;

        String prefix  = flags.getOrDefault("prefix",  "");
        String suffix  = flags.getOrDefault("suffix",  "");
        String fromStr = flags.getOrDefault("replace", null);
        String toStr   = flags.getOrDefault("with",    "");
        if (prefix.isEmpty() && suffix.isEmpty() && fromStr == null) {
            ChatHelper.error(src, "§cProvide at least one: §b--prefix <text> §7| §b--suffix <text> §7| §b--replace <old> --with <new>");
            return 0;
        }

        int count = 0;
        for (SlotData d : blocks) {
            String newName = d.displayName;
            if (fromStr != null) newName = newName.replace(fromStr, toStr);
            if (!prefix.isEmpty()) newName = prefix + newName;
            if (!suffix.isEmpty()) newName = newName + suffix;
            final String finalName = newName;
            SlotData prev = com.customblocks.core.SlotManager.getById(d.customId);
            if (prev != null) {
                com.customblocks.core.UndoManager.pushUndoMutation(d.customId, prev, "meta", getPlayerUuid(src));
                com.customblocks.core.SlotManager.rename(d.customId, finalName);
                count++;
            }
        }
        if (src.getServer() != null) com.customblocks.ResourcePackManager.scheduleRebuild(src.getServer());
        ChatHelper.success(src, "§aRenamed §f" + count + " §7block(s). §8/cb undo to revert.");
        return 1;
    }

    // ── bulkreid ────────────────────────────────────────────────────────────

    private static int cmdBulkReId(ServerCommandSource src, String args) {
        java.util.Map<String, String> flags = parseBulkArgs(args);
        List<SlotData> blocks = resolveScope(src, flags.get("scope"));
        if (blocks == null) return 0;
        if (!bulkGuard(src, blocks, flags, "Bulk Re-ID")) return 1;

        String fromStr = flags.get("replace");
        String toStr   = flags.getOrDefault("with", "");
        if (fromStr == null) {
            ChatHelper.error(src, "§cProvide: §b--replace <old_fragment> --with <new_fragment>");
            return 0;
        }
        // Pre-check for collisions
        List<String> collisions = new ArrayList<>();
        for (SlotData d : blocks) {
            String newId = d.customId.replace(fromStr, toStr);
            if (!newId.equals(d.customId) && com.customblocks.core.SlotManager.hasId(newId)) collisions.add(newId);
        }
        if (!collisions.isEmpty()) {
            ChatHelper.error(src, "§cID collision(s) detected — aborting. Conflicting IDs:");
            collisions.forEach(id -> ChatHelper.error(src, "  §f" + id));
            return 0;
        }

        int count = 0;
        for (SlotData d : blocks) {
            String newId = d.customId.replace(fromStr, toStr);
            if (!newId.equals(d.customId)) {
                SlotData prev = com.customblocks.core.SlotManager.getById(d.customId);
                if (prev != null) {
                    com.customblocks.core.UndoManager.pushUndoMutation(d.customId, prev, "meta", getPlayerUuid(src));
                    com.customblocks.core.SlotManager.update(d.customId, sd -> sd.withCustomId(newId));
                    // Update category assignments
                    new java.util.HashSet<>(com.customblocks.core.CategoryManager.getCategoriesForBlock(d.customId))
                            .forEach(cat -> {
                                com.customblocks.core.CategoryManager.unassignBlock(d.customId, cat);
                                com.customblocks.core.CategoryManager.assignBlock(newId, cat);
                            });
                    count++;
                }
            }
        }
        if (src.getServer() != null) com.customblocks.ResourcePackManager.scheduleRebuild(src.getServer());
        ChatHelper.success(src, "§aRe-ID'd §f" + count + " §7block(s): §f" + fromStr + " §7→ §f" + toStr + "  §8/cb undo to revert.");
        return 1;
    }

    // ── bulkproperty ────────────────────────────────────────────────────────

    private static int cmdBulkProperty(ServerCommandSource src, String args) {
        java.util.Map<String, String> flags = parseBulkArgs(args);
        String scopeAndProp = flags.get("scope"); // "scope prop value" all in one token
        // scope = everything before last 2 tokens; prop = second-to-last; value = last
        String[] parts = (scopeAndProp == null ? "" : scopeAndProp).trim().split("\\s+");
        if (parts.length < 3) {
            ChatHelper.error(src, "§cUsage: /cb bulkproperty <scope> <property> <value>");
            ChatHelper.error(src, "§7Properties: §bsound §7| §bglow §7| §bhardness §7| §bcollision");
            return 0;
        }
        String prop  = parts[parts.length - 2].toLowerCase(java.util.Locale.ROOT);
        String value = parts[parts.length - 1];
        String scopeStr = String.join(" ", java.util.Arrays.copyOf(parts, parts.length - 2));
        List<SlotData> blocks = resolveScope(src, scopeStr);
        if (blocks == null) return 0;
        flags.put("scope", scopeStr);
        if (!bulkGuard(src, blocks, flags, "Bulk Property")) return 1;

        int count = 0;
        for (SlotData d : blocks) {
            SlotData prev = com.customblocks.core.SlotManager.getById(d.customId);
            if (prev == null) continue;
            com.customblocks.core.UndoManager.pushUndoMutation(d.customId, prev, "meta", getPlayerUuid(src));
            try {
                switch (prop) {
                    case "sound" -> com.customblocks.core.SlotManager.update(d.customId, sd -> sd.withSoundType(value));
                    case "glow", "light" -> com.customblocks.core.SlotManager.setLightLevel(d.customId, Integer.parseInt(value));
                    case "hardness" -> com.customblocks.core.SlotManager.update(d.customId, sd -> sd.withHardness(Float.parseFloat(value)));
                    case "collision", "nocollision" -> com.customblocks.core.SlotManager.update(d.customId, sd -> sd.withNoCollision(Boolean.parseBoolean(value)));
                    default -> { ChatHelper.error(src, "§cUnknown property: §f" + prop); return 0; }
                }
                count++;
            } catch (NumberFormatException e) {
                ChatHelper.error(src, "§cInvalid value for §f" + prop + "§c: §f" + value);
                return 0;
            }
        }
        if (src.getServer() != null) com.customblocks.ResourcePackManager.scheduleRebuild(src.getServer());
        ChatHelper.success(src, "§aSet §f" + prop + "=" + value + " §7on §f" + count + " §7block(s). §8/cb undo to revert.");
        return 1;
    }

    // ── bulkexport ──────────────────────────────────────────────────────────

    private static int cmdBulkExport(ServerCommandSource src, String scopeRaw) {
        List<SlotData> blocks = resolveScope(src, scopeRaw);
        if (blocks == null) return 0;
        ChatHelper.info(src, "§7Exporting §f" + blocks.size() + " §7block(s) to ZIP...");
        thread(() -> {
            try {
                java.nio.file.Path exportDir = java.nio.file.Path.of("config", "customblocks", "exports");
                java.nio.file.Files.createDirectories(exportDir);
                String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
                java.nio.file.Path zipPath = exportDir.resolve("bulk_export_" + stamp + ".zip");
                try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                        java.nio.file.Files.newOutputStream(zipPath))) {
                    com.google.gson.JsonArray manifest = new com.google.gson.JsonArray();
                    for (SlotData d : blocks) {
                        com.google.gson.JsonObject meta = new com.google.gson.JsonObject();
                        meta.addProperty("customId",    d.customId);
                        meta.addProperty("displayName", d.displayName);
                        meta.addProperty("lightLevel",  d.lightLevel);
                        meta.addProperty("hardness",    d.hardness);
                        meta.addProperty("soundType",   d.soundType);
                        manifest.add(meta);
                        if (d.texture != null && d.texture.length > 0) {
                            zos.putNextEntry(new java.util.zip.ZipEntry("textures/" + d.customId + ".png"));
                            zos.write(d.texture);
                            zos.closeEntry();
                        }
                    }
                    zos.putNextEntry(new java.util.zip.ZipEntry("manifest.json"));
                    zos.write(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
                src.getServer().execute(() ->
                        ChatHelper.success(src, "§aExported §f" + blocks.size() + " §7block(s) to §f" + zipPath));
            } catch (IOException | RuntimeException e) {
                src.getServer().execute(() ->
                        ChatHelper.error(src, "§cExport failed: §f" + e.getMessage()));
            }
        });
        return 1;
    }

    // ── bulkmove ────────────────────────────────────────────────────────────

    private static int cmdBulkMove(ServerCommandSource src, String args) {
        java.util.Map<String, String> flags = parseBulkArgs(args);
        String scopeAndCat = flags.get("scope");
        if (scopeAndCat == null || scopeAndCat.isBlank()) {
            ChatHelper.error(src, "§cUsage: /cb bulkmove <scope> <category>");
            return 0;
        }
        String[] parts = scopeAndCat.trim().split("\\s+");
        if (parts.length < 2) {
            ChatHelper.error(src, "§cUsage: /cb bulkmove <scope> <target_category>");
            return 0;
        }
        String targetCat = parts[parts.length - 1];
        String scopeStr  = String.join(" ", java.util.Arrays.copyOf(parts, parts.length - 1));
        com.customblocks.core.Category cat = com.customblocks.core.CategoryManager.getCategory(targetCat);
        if (cat == null) {
            ChatHelper.error(src, "§cCategory not found: §f" + targetCat);
            return 0;
        }
        List<SlotData> blocks = resolveScope(src, scopeStr);
        if (blocks == null) return 0;
        flags.put("scope", scopeStr);
        if (!bulkGuard(src, blocks, flags, "Bulk Move")) return 1;

        com.customblocks.core.UndoManager.pushCategoryUndo(
                com.customblocks.core.UndoManager.captureCategorySnapshot("bulkmove→" + targetCat, getPlayerUuid(src)));
        int count = 0;
        for (SlotData d : blocks) {
            com.customblocks.core.CategoryManager.assignBlock(d.customId, cat.key());
            count++;
        }
        ChatHelper.success(src, "§aMoved §f" + count + " §7block(s) to §f" + cat.displayName() + ". §8/cb undo to revert.");
        return 1;
    }

    // ── bulkduplicate ───────────────────────────────────────────────────────

    private static int cmdBulkDuplicate(ServerCommandSource src, String args) {
        java.util.Map<String, String> flags = parseBulkArgs(args);
        List<SlotData> blocks = resolveScope(src, flags.get("scope"));
        if (blocks == null) return 0;
        if (!bulkGuard(src, blocks, flags, "Bulk Duplicate")) return 1;

        String suffix = flags.getOrDefault("suffix", "_copy");
        int free = com.customblocks.core.SlotManager.freeSlots();
        if (blocks.size() > free) {
            ChatHelper.error(src, "§cNot enough free slots (§f" + free + " §cavailable, §f" + blocks.size() + " §crequested).");
            return 0;
        }
        int count = 0;
        for (SlotData d : blocks) {
            String newId = d.customId + suffix;
            int attempts = 0;
            while (com.customblocks.core.SlotManager.hasId(newId) && attempts < 20) {
                newId = d.customId + suffix + (++attempts);
            }
            if (com.customblocks.core.SlotManager.hasId(newId)) continue;
            SlotData created = com.customblocks.core.SlotManager.assign(newId, d.displayName + suffix, d.texture);
            if (created != null) {
                final String fNewId = newId;
                com.customblocks.core.SlotManager.update(fNewId, sd -> sd
                        .withLightLevel(d.lightLevel)
                        .withHardness(d.hardness)
                        .withSoundType(d.soundType)
                        .withNoCollision(d.noCollision)
                        .withAnimMeta(d.animMeta)
                        .withShapeBoxes(d.shapeBoxes)
                        .withHologramText(d.hologramText));
                com.customblocks.core.UndoManager.pushUndoCreate(fNewId, getPlayerUuid(src));
                count++;
            }
        }
        if (src.getServer() != null) com.customblocks.ResourcePackManager.scheduleRebuild(src.getServer());
        ChatHelper.success(src, "§aDuplicated §f" + count + " §7block(s) with suffix §f\"" + suffix + "\". §8/cb undo to revert.");
        return 1;
    }

    // ── bulklock / bulkunlock ────────────────────────────────────────────────

    private static int cmdBulkLock(ServerCommandSource src, String scopeRaw, boolean lock) {
        List<SlotData> blocks = resolveScope(src, scopeRaw);
        if (blocks == null) return 0;
        int count = 0;
        for (SlotData d : blocks) {
            if (lock) { if (com.customblocks.core.LockManager.lock(d.customId))   count++; }
            else      { if (com.customblocks.core.LockManager.unlock(d.customId)) count++; }
        }
        ChatHelper.success(src, "§7" + (lock ? "Locked" : "Unlocked") + " §f" + count + " §7block(s).");
        return 1;
    }

    // ── bulkfavorite / bulkunfavorite ────────────────────────────────────────

    private static int cmdBulkFavorite(ServerCommandSource src, String scopeRaw, boolean favorite) {
        UUID playerUuid = getPlayerUuid(src);
        List<SlotData> blocks = resolveScope(src, scopeRaw);
        if (blocks == null) return 0;
        int count = 0;
        for (SlotData d : blocks) {
            boolean isFav = com.customblocks.core.FavoritesManager.isFavorite(playerUuid, d.customId);
            if (favorite != isFav) {
                com.customblocks.core.FavoritesManager.toggle(playerUuid, d.customId, src.getServer());
                count++;
            }
        }
        ChatHelper.success(src, "§d" + (favorite ? "Starred" : "Unstarred") + " §f" + count + " §7block(s).");
        return 1;
    }

    // ── bulkshape ────────────────────────────────────────────────────────────

    private static int cmdBulkShape(ServerCommandSource src, String args) {
        java.util.Map<String, String> flags = parseBulkArgs(args);
        String scopeAndPreset = flags.get("scope");
        if (scopeAndPreset == null || scopeAndPreset.isBlank()) {
            ChatHelper.error(src, "§cUsage: /cb bulkshape <scope> <preset>");
            ChatHelper.error(src, "§7Presets: §bfull slab thin carpet pillar small micro pane trapdoor fence stairs cross");
            return 0;
        }
        String[] parts = scopeAndPreset.trim().split("\\s+");
        if (parts.length < 2) {
            ChatHelper.error(src, "§cProvide both a scope and a shape preset name.");
            return 0;
        }
        String presetName = parts[parts.length - 1].toLowerCase(java.util.Locale.ROOT);
        String scopeStr   = String.join(" ", java.util.Arrays.copyOf(parts, parts.length - 1));
        List<SlotData.ShapeBox> boxes = ShapePresets.get(presetName);
        if (boxes == null) {
            ChatHelper.error(src, "§cUnknown shape preset: §f" + presetName
                    + " §7— try: full slab thin carpet pillar small micro pane trapdoor fence stairs cross");
            return 0;
        }
        List<SlotData> blocks = resolveScope(src, scopeStr);
        if (blocks == null) return 0;
        flags.put("scope", scopeStr);
        if (!bulkGuard(src, blocks, flags, "Bulk Shape")) return 1;

        int count = 0;
        for (SlotData d : blocks) {
            SlotData prev = com.customblocks.core.SlotManager.getById(d.customId);
            if (prev != null) {
                com.customblocks.core.UndoManager.pushUndoMutation(d.customId, prev, "shape", getPlayerUuid(src));
                com.customblocks.core.SlotManager.update(d.customId, sd -> sd.withShapeBoxes(boxes));
                count++;
            }
        }
        if (src.getServer() != null) com.customblocks.ResourcePackManager.scheduleRebuild(src.getServer());
        ChatHelper.success(src, "§aApplied shape §f" + presetName + " §7to §f" + count + " §7block(s). §8/cb undo to revert.");
        return 1;
    }

    // ── bulksound ────────────────────────────────────────────────────────────

    private static int cmdBulkSound(ServerCommandSource src, String args) {
        java.util.Map<String, String> flags = parseBulkArgs(args);
        String scopeAndSound = flags.get("scope");
        if (scopeAndSound == null || scopeAndSound.isBlank()) {
            ChatHelper.error(src, "§cUsage: /cb bulksound <scope> <sound>");
            return 0;
        }
        String[] parts = scopeAndSound.trim().split("\\s+");
        if (parts.length < 2) {
            ChatHelper.error(src, "§cProvide both a scope and a sound type.");
            return 0;
        }
        String sound    = parts[parts.length - 1].toLowerCase(java.util.Locale.ROOT);
        String scopeStr = String.join(" ", java.util.Arrays.copyOf(parts, parts.length - 1));
        List<SlotData> blocks = resolveScope(src, scopeStr);
        if (blocks == null) return 0;
        flags.put("scope", scopeStr);
        if (!bulkGuard(src, blocks, flags, "Bulk Sound")) return 1;

        int count = 0;
        for (SlotData d : blocks) {
            SlotData prev = com.customblocks.core.SlotManager.getById(d.customId);
            if (prev != null) {
                com.customblocks.core.UndoManager.pushUndoMutation(d.customId, prev, "meta", getPlayerUuid(src));
                com.customblocks.core.SlotManager.update(d.customId, sd -> sd.withSoundType(sound));
                count++;
            }
        }
        if (src.getServer() != null) com.customblocks.ResourcePackManager.scheduleRebuild(src.getServer());
        ChatHelper.success(src, "§aSet sound §f" + sound + " §7on §f" + count + " §7block(s). §8/cb undo to revert.");
        return 1;
    }

    /** Thin helper over ShapePresets lookup (avoids a GuiManager dependency). */
    private static final class ShapePresets {
        private static final java.util.Map<String, List<SlotData.ShapeBox>> MAP;
        static {
            MAP = new java.util.HashMap<>();
            MAP.put("full",      List.of(new SlotData.ShapeBox(0,0,0,16,16,16)));
            MAP.put("slab",      List.of(new SlotData.ShapeBox(0,0,0,16,8,16)));
            MAP.put("thin",      List.of(new SlotData.ShapeBox(0,0,0,16,4,16)));
            MAP.put("carpet",    List.of(new SlotData.ShapeBox(0,0,0,16,1,16)));
            MAP.put("pillar",    List.of(new SlotData.ShapeBox(4,0,4,12,16,12)));
            MAP.put("small",     List.of(new SlotData.ShapeBox(2,0,2,14,8,14)));
            MAP.put("micro",     List.of(new SlotData.ShapeBox(6,0,6,10,10,10)));
            MAP.put("pane",      List.of(new SlotData.ShapeBox(7,0,0,9,16,16)));
            MAP.put("trapdoor",  List.of(new SlotData.ShapeBox(0,0,0,16,3,16)));
            MAP.put("fence",     List.of(new SlotData.ShapeBox(6,0,6,10,16,10)));
            MAP.put("stairs",    List.of(new SlotData.ShapeBox(0,0,0,16,8,16), new SlotData.ShapeBox(0,8,0,16,16,8)));
            MAP.put("cross",     List.of(new SlotData.ShapeBox(6,0,0,10,16,16), new SlotData.ShapeBox(0,0,6,16,16,10)));
        }
        static List<SlotData.ShapeBox> get(String name) { return MAP.get(name); }
    }

    // ── 3.2 / 3.6 / 3.8 command implementations (Phase 3 non-GUI) ───────────

    private static int cmdPaletteList(ServerCommandSource src) {
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.console_player_only")); return 0; }
        List<String> palette = PlayerPaletteManager.getPalette(player.getUuid());
        if (palette.isEmpty()) {
            ChatHelper.info(src, "§7Your color palette is empty. Use §f/cb palette add <hex> §7to save colors.");
            return 1;
        }
        ChatHelper.info(src, "§6§lYour Color Palette §7(" + palette.size() + "/" + PlayerPaletteManager.MAX_PALETTE_SIZE + ")§7:");
        for (int i = 0; i < palette.size(); i++) {
            final String hex = palette.get(i);
            // Try to resolve a color name from the library
            ColorLibrary.LibColor named = null;
            for (ColorLibrary.LibColor c : ColorLibrary.ALL) {
                if (c.hex().equalsIgnoreCase(hex)) { named = c; break; }
            }
            final String label = (named != null) ? named.name() : hex;
            final int displayIndex = i + 1;
            src.sendFeedback(() -> Text.literal("  §8" + displayIndex + ". §f" + label + " §8(" + hex + ")"), false);
        }
        return 1;
    }

    private static int cmdPaletteAdd(ServerCommandSource src, String hexArg) {
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.console_player_only")); return 0; }
        String normalized = PlayerPaletteManager.normalize(hexArg);
        if (normalized == null) {
            ChatHelper.error(src, "§cInvalid hex color '§f" + hexArg + "§c'. Use 6-character hex like §fFF5500 §cor §f#FF5500§c.");
            return 0;
        }
        List<String> palette = PlayerPaletteManager.getPalette(player.getUuid());
        if (palette.size() >= PlayerPaletteManager.MAX_PALETTE_SIZE) {
            ChatHelper.error(src, "§cPalette full (§f" + PlayerPaletteManager.MAX_PALETTE_SIZE + " §ccolors max). Remove one first with §f/cb palette remove <index>§c.");
            return 0;
        }
        boolean added = PlayerPaletteManager.addColor(player.getUuid(), hexArg);
        if (!added) {
            ChatHelper.warn(src, "§7Color §f" + normalized + " §7is already in your palette.");
            return 0;
        }
        ChatHelper.success(src, "§aAdded §f" + normalized + " §ato your color palette.");
        return 1;
    }

    private static int cmdPaletteRemove(ServerCommandSource src, int index1Based) {
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.console_player_only")); return 0; }
        List<String> palette = PlayerPaletteManager.getPalette(player.getUuid());
        if (index1Based < 1 || index1Based > palette.size()) {
            ChatHelper.error(src, "§cIndex §f" + index1Based + " §cis out of range. Your palette has §f" + palette.size() + " §ccolor(s).");
            return 0;
        }
        String removed = palette.get(index1Based - 1);
        PlayerPaletteManager.removeColor(player.getUuid(), index1Based - 1);
        ChatHelper.success(src, "§aRemoved §f" + removed + " §afrom your color palette.");
        return 1;
    }

    private static int cmdPaletteClear(ServerCommandSource src) {
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.console_player_only")); return 0; }
        List<String> palette = PlayerPaletteManager.getPalette(player.getUuid());
        int count = palette.size();
        // Remove all entries by iterating in reverse (index-safe)
        for (int i = count - 1; i >= 0; i--) {
            PlayerPaletteManager.removeColor(player.getUuid(), i);
        }
        ChatHelper.success(src, "§aCleared §f" + count + " §acolor(s) from your palette.");
        return 1;
    }

    // ── 3.6 Tolerance command implementations ────────────────────────────────

    private static int cmdToleranceShow(ServerCommandSource src) {
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.console_player_only")); return 0; }
        int current = ColorTriangleItem.effectiveTolerance(player.getUuid());
        boolean overridden = ColorTriangleItem.PLAYER_TOLERANCE.containsKey(player.getUuid());
        String suffix = overridden ? " §8(custom)" : " §8(default)";
        ChatHelper.info(src, "§7Flood-fill tolerance: §f" + current + suffix + " §7— range 10–80. Set with §f/cb tolerance <value>§7.");
        return 1;
    }

    private static int cmdToleranceSet(ServerCommandSource src, int value) {
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.console_player_only")); return 0; }
        ColorTriangleItem.PLAYER_TOLERANCE.put(player.getUuid(), value);
        ChatHelper.success(src, "§aTolerance set to §f" + value + "§a. Range: 10–80.");
        return 1;
    }

    private static int cmdToleranceReset(ServerCommandSource src) {
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.console_player_only")); return 0; }
        ColorTriangleItem.PLAYER_TOLERANCE.remove(player.getUuid());
        int def = ColorTriangleItem.effectiveTolerance(player.getUuid());
        ChatHelper.success(src, "§aTolerance reset to default (§f" + def + "§a).");
        return 1;
    }

    // ── V4-29 Triangle mode command implementations ─────────────────────────

    private static int cmdTriangleModeShow(ServerCommandSource src) {
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.console_player_only")); return 0; }
        String mode = com.customblocks.item.ColorTriangleItem.effectiveMode(player.getUuid());
        boolean overridden = com.customblocks.item.ColorTriangleItem.PLAYER_MODE.containsKey(player.getUuid());
        String desc = "edge".equals(mode)
            ? "§7Seeds from entire image perimeter — safest for most textures."
            : "§7Replaces matching pixels everywhere, including interior regions.";
        ChatHelper.info(src, "§7Triangle fill mode: §f" + mode + (overridden ? " §8(custom)" : " §8(default)")
            + "\n" + desc + "\n§7Switch: §f/cb trianglemode edge §7or §f/cb trianglemode full");
        return 1;
    }

    private static int cmdTriangleModeSet(ServerCommandSource src, String mode) {
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.console_player_only")); return 0; }
        com.customblocks.item.ColorTriangleItem.PLAYER_MODE.put(player.getUuid(), mode);
        String desc = "edge".equals(mode) ? "Smart perimeter fill" : "Full image fill";
        ChatHelper.success(src, "§aTriangle fill mode set to §f" + mode + " §7(" + desc + ")§a.");
        return 1;
    }

    // ── Phase 5.15 Template implementations ─────────────────────────────────

    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    private static int cmdTemplateSave(ServerCommandSource src, String name, String blockId) {
        if (!SlotManager.hasId(blockId)) {
            ChatHelper.error(src, "§cBlock not found: §f" + blockId + "§c. §7Use /cb list to see block IDs.");
            return 0;
        }
        SlotData data = SlotManager.getById(blockId);
        if (data == null) { ChatHelper.error(src, ChatHelper.formattedKey("cmd.console_player_only")); return 0; }
        String author = src.getPlayer() != null ? src.getPlayer().getGameProfile().getName() : "server";
        boolean ok = com.customblocks.core.TemplateManager.save(name, data, author);
        if (!ok) {
            ChatHelper.error(src, "§cTemplate name cannot be blank.");
            return 0;
        }
        ChatHelper.success(src, "§aTemplate §f" + name + " §asaved from §f" + blockId
            + "§a. §7Properties: sound=" + data.soundType
            + ", glow=" + data.lightLevel
            + ", hardness=" + data.hardness + ".");
        return 1;
    }

    private static int cmdTemplateApply(ServerCommandSource src, String name, String blockId) {
        if (!com.customblocks.core.TemplateManager.exists(name)) {
            ChatHelper.error(src, "§cTemplate §f" + name + " §cnot found. §7Use /cb template list to see templates.");
            return 0;
        }
        if (!SlotManager.hasId(blockId)) {
            ChatHelper.error(src, "§cBlock not found: §f" + blockId + "§c. §7Use /cb list to see block IDs.");
            return 0;
        }
        boolean ok = com.customblocks.core.TemplateManager.apply(name, blockId);
        if (!ok) {
            ChatHelper.error(src, "§cFailed to apply template §f" + name + "§c to §f" + blockId + "§c.");
            return 0;
        }
        com.customblocks.network.NetworkManager.broadcastFullSync(src.getServer());
        ChatHelper.success(src, "§aTemplate §f" + name + " §aapplied to §f" + blockId + "§a.");
        return 1;
    }

    private static int cmdTemplateList(ServerCommandSource src) {
        var templates = com.customblocks.core.TemplateManager.getAll();
        if (templates.isEmpty()) {
            ChatHelper.info(src, "§7No templates saved. Create one with §f/cb template save <name> <blockId>§7.");
            return 1;
        }
        ChatHelper.info(src, "§b§lBlock Templates §7(" + templates.size() + ")§7:");
        for (com.customblocks.core.TemplateManager.Template t : templates) {
            String by = (t.createdBy() != null && !t.createdBy().isBlank()) ? " §8by §7" + t.createdBy() : "";
            ChatHelper.info(src, "  §f" + t.name()
                + " §8— §7sound=" + t.soundType()
                + " glow=" + t.lightLevel()
                + " hardness=" + t.hardness()
                + by);
        }
        ChatHelper.info(src, "§7Apply with §f/cb template apply <name> <blockId>§7.");
        return 1;
    }

    private static int cmdTemplateDelete(ServerCommandSource src, String name) {
        boolean ok = com.customblocks.core.TemplateManager.delete(name);
        if (!ok) {
            ChatHelper.error(src, "§cTemplate §f" + name + " §cnot found. §7Use /cb template list to see templates.");
            return 0;
        }
        ChatHelper.success(src, "§aTemplate §f" + name + " §adeleted.");
        return 1;
    }

    // ── Phase 5.14 Help scopes ────────────────────────────────────────────────

    private static int cmdHelpScopes(ServerCommandSource src) {
        ChatHelper.info(src, "§b§lScope Expressions §7— filter sets of blocks for bulk commands");
        ChatHelper.info(src, "");
        ChatHelper.info(src, "§e§lBasic scopes:");
        ChatHelper.info(src, "  §fall §7— every block on the server");
        ChatHelper.info(src, "  §f<id1,id2,...> §7— explicit comma-separated IDs (e.g. §fmarble,oak_log§7)");
        ChatHelper.info(src, "  §ffavorites §7— all blocks you have starred with §f/cb favorite");
        ChatHelper.info(src, "");
        ChatHelper.info(src, "§e§lCategory scopes:");
        ChatHelper.info(src, "  §fcategory:<key> §7— all blocks in a category (e.g. §fcategory:stone§7)");
        ChatHelper.info(src, "  §fname:<pattern> §7— blocks whose ID contains pattern (e.g. §fname:marble§7)");
        ChatHelper.info(src, "");
        ChatHelper.info(src, "§e§lFilter tokens (for /cb search):");
        ChatHelper.info(src, "  §fcategory:<key> §7— filter by category");
        ChatHelper.info(src, "  §fanimated:true|false §7— animated blocks only");
        ChatHelper.info(src, "  §fglow:>5 §7— blocks with glow level > 5 (supports <, >, =)");
        ChatHelper.info(src, "  §flocked:true|false §7— locked/unlocked blocks");
        ChatHelper.info(src, "  §ffavorite:true|false §7— favorited blocks");
        ChatHelper.info(src, "  §fsound:<type> §7— blocks with a specific sound type");
        ChatHelper.info(src, "  §fcreated:today|week|month §7— filter by creation time");
        ChatHelper.info(src, "  §fhas:variants|shape|faces|glow §7— feature presence check");
        ChatHelper.info(src, "  §fcolor:<name|#hex> §7— approximate color match");
        ChatHelper.info(src, "  §fhardness:>2.0 §7— hardness comparison");
        ChatHelper.info(src, "  §f<bare text> §7— searches ID and display name");
        ChatHelper.info(src, "");
        ChatHelper.info(src, "§e§lExamples:");
        ChatHelper.info(src, "  §f/cb bulkrecolor category:stone red §7— recolor all stone blocks");
        ChatHelper.info(src, "  §f/cb bulkdelete marble,oak_log §7— delete two specific blocks");
        ChatHelper.info(src, "  §f/cb search glow:>0 category:plants §7— glowing plant blocks");
        ChatHelper.info(src, "  §f/cb search animated:true has:variants §7— animated blocks with variants");
        return 1;
    }

    // ── 9.4 /cb backup ───────────────────────────────────────────────────────

    private static int cmdBackupCreate(ServerCommandSource src, String name) {
        // sanitize name
        String safe = name.replaceAll("[^A-Za-z0-9_\\-]", "_");
        EXECUTOR.submit(() -> {
            try {
                java.nio.file.Path path = com.customblocks.core.BackupManager.create(safe);
                long bytes = java.nio.file.Files.size(path);
                String size = String.format("%.1f MB", bytes / 1_048_576.0);
                src.getServer().execute(() ->
                    ChatHelper.success(src, "Backup created: §b" + path.getFileName() + " §7(" + size + ")"));
            } catch (Exception e) {
                src.getServer().execute(() ->
                    ChatHelper.error(src, "Backup failed: " + e.getMessage()));
            }
        });
        ChatHelper.info(src, "§7Creating backup '" + safe + "'...");
        return 1;
    }

    private static int cmdBackupList(ServerCommandSource src) {
        var list = com.customblocks.core.BackupManager.list();
        if (list.isEmpty()) { ChatHelper.info(src, "§7No backups found. Create one with /cb backup create."); return 1; }
        ChatHelper.info(src, "§e§lBackups (" + list.size() + "):");
        for (var b : list) {
            String exp = b.expiresAt() > 0
                ? " §8[expires in " + ((b.expiresAt() - System.currentTimeMillis()) / 3_600_000) + "h]"
                : "";
            ChatHelper.info(src, "  §f" + b.name() + " §7— §b" + b.sizeKb() + " KB §7— " + b.created() + exp);
        }
        return 1;
    }

    private static int cmdBackupRestore(ServerCommandSource src, String name) {
        boolean ok = com.customblocks.core.BackupManager.restore(name);
        if (!ok) { ChatHelper.error(src, "Backup '" + name + "' not found or is corrupt."); return 0; }
        NetworkManager.broadcastFullSync(src.getServer());
        ChatHelper.success(src, "Backup '" + name + "' restored. All blocks reloaded.");
        return 1;
    }

    private static int cmdBackupDelete(ServerCommandSource src, String name) {
        boolean ok = com.customblocks.core.BackupManager.delete(name);
        if (!ok) { ChatHelper.error(src, "Backup '" + name + "' not found."); return 0; }
        ChatHelper.success(src, "Backup '" + name + "' deleted.");
        return 1;
    }

    private static int cmdBackupExpiry(ServerCommandSource src, String name, int hours) {
        boolean ok = com.customblocks.core.BackupManager.setExpiry(name, hours);
        if (!ok) { ChatHelper.error(src, "Backup '" + name + "' not found."); return 0; }
        ChatHelper.success(src, "Backup '" + name + "' will auto-delete in §f" + hours + " §ahours if not restored.");
        return 1;
    }

    // ── Phase 10.2 — Config subcommand helpers ─────────────────────────────────

    private static int cmdConfigList(ServerCommandSource src) {
        ChatHelper.info(src, "§6§lCustomBlocks Config Settings:");
        ChatHelper.info(src, "  §emax-slots §7= §f" + com.customblocks.CustomBlocksConfig.maxSlots);
        ChatHelper.info(src, "  §eundo-depth §7= §f" + com.customblocks.CustomBlocksConfig.maxUndoDepth);
        ChatHelper.info(src, "  §egif-limit §7= §f" + com.customblocks.CustomBlocksConfig.maxGifSizeMb + " MB");
        ChatHelper.info(src, "  §etexture-size §7= §f" + com.customblocks.CustomBlocksConfig.defaultTextureSize);
        ChatHelper.info(src, "  §einstant-click §7= §f" + com.customblocks.CustomBlocksConfig.instantClickAggressivenessMs + " ms");
        ChatHelper.info(src, "  §ehologram §7= §f" + com.customblocks.CustomBlocksConfig.hologramEnabled);
        ChatHelper.info(src, "  §ehologram-height §7= §f" + com.customblocks.CustomBlocksConfig.hologramHeight);
        ChatHelper.info(src, "  §esounds §7= §f" + com.customblocks.CustomBlocksConfig.soundsEnabled);
        ChatHelper.info(src, "  §eparticles §7= §f" + com.customblocks.CustomBlocksConfig.particlesEnabled);
        ChatHelper.info(src, "  §emarketplace §7= §f" + com.customblocks.CustomBlocksConfig.marketplaceEnabled);
        ChatHelper.info(src, "  §evoice §7= §f" + com.customblocks.CustomBlocksConfig.voiceMode);
        ChatHelper.info(src, "  §ebackup-interval §7= §f" + com.customblocks.CustomBlocksConfig.autoSnapshotMinutes + " min");
        ChatHelper.info(src, "  §eai-provider §7= §f" + (com.customblocks.CustomBlocksConfig.aiApiProvider.isEmpty() ? "off" : com.customblocks.CustomBlocksConfig.aiApiProvider));
        ChatHelper.info(src, "  §eai-key §7= §f" + (com.customblocks.CustomBlocksConfig.aiApiKey.isEmpty() ? "[not set]" : "[hidden]"));
        ChatHelper.info(src, "  §eai-variations §7= §f" + com.customblocks.CustomBlocksConfig.aiMaxVariations);
        ChatHelper.info(src, "  §eai-style §7= §f" + com.customblocks.CustomBlocksConfig.aiTextureStyle);
        return 1;
    }

    private static int cmdConfigGet(ServerCommandSource src, String key, String value) {
        ChatHelper.info(src, "§e" + key + " §7= §f" + value);
        return 1;
    }

    private static int cmdConfigSetInt(ServerCommandSource src, String key, int value) {
        switch (key) {
            case "max-slots" -> {
                int highest = com.customblocks.core.SlotManager.highestUsedSlotIndex();
                int needed  = highest + 1;
                if (value < needed) {
                    ChatHelper.error(src,
                        "§cCannot reduce max-slots to §f" + value + "§c — you have blocks using slot indices up to §f"
                        + highest + "§c. §7Keep max-slots at §f" + needed + "§7 or higher to avoid blocks vanishing from the world.");
                    return 0;
                }
                com.customblocks.CustomBlocksConfig.maxSlots = value;
            }
            case "undo-depth"      -> com.customblocks.CustomBlocksConfig.maxUndoDepth = value;
            case "gif-limit"       -> com.customblocks.CustomBlocksConfig.maxGifSizeMb = value;
            case "texture-size"    -> com.customblocks.CustomBlocksConfig.defaultTextureSize = value;
            case "instant-click"   -> com.customblocks.CustomBlocksConfig.instantClickAggressivenessMs = value;
            case "backup-interval" -> com.customblocks.CustomBlocksConfig.autoSnapshotMinutes = value;
            case "ai-variations"   -> com.customblocks.CustomBlocksConfig.aiMaxVariations = Math.max(1, Math.min(8, value));
            default -> { ChatHelper.error(src, "Unknown integer config key: " + key); return 0; }
        }
        trySaveConfig(src, key, String.valueOf(value));
        return 1;
    }

    private static int cmdConfigSetBool(ServerCommandSource src, String key, boolean value) {
        switch (key) {
            case "hologram"     -> com.customblocks.CustomBlocksConfig.hologramEnabled = value;
            case "sounds"       -> com.customblocks.CustomBlocksConfig.soundsEnabled = value;
            case "particles"    -> com.customblocks.CustomBlocksConfig.particlesEnabled = value;
            case "marketplace"  -> com.customblocks.CustomBlocksConfig.marketplaceEnabled = value;
            default -> { ChatHelper.error(src, "Unknown boolean config key: " + key); return 0; }
        }
        trySaveConfig(src, key, String.valueOf(value));
        return 1;
    }

    private static int cmdConfigSetFloat(ServerCommandSource src, String key, float value) {
        switch (key) {
            case "hologram-height" -> com.customblocks.CustomBlocksConfig.hologramHeight = value;
            default -> { ChatHelper.error(src, "Unknown float config key: " + key); return 0; }
        }
        trySaveConfig(src, key, String.valueOf(value));
        return 1;
    }

    private static int cmdConfigSetVoice(ServerCommandSource src, String value) {
        if (!value.equals("friendly") && !value.equals("professional") && !value.equals("minimal")) {
            ChatHelper.error(src, "§fvoice §cmust be §ffriendly§c, §fprofessional§c, or §fminimal§c.");
            return 0;
        }
        com.customblocks.CustomBlocksConfig.voiceMode = value;
        trySaveConfig(src, "voice", value);
        return 1;
    }

    private static int cmdConfigSetAiProvider(ServerCommandSource src, String value) {
        String normalized = switch (value.toLowerCase(java.util.Locale.ROOT).trim()) {
            case "openai"       -> "openai";
            case "stability"    -> "stability";
            case "off", ""      -> "";
            default             -> null;
        };
        if (normalized == null) {
            ChatHelper.error(src, "§fai-provider §cmust be §fopenai§c, §fstability§c, or §foff§c.");
            return 0;
        }
        com.customblocks.CustomBlocksConfig.aiApiProvider = normalized;
        String display = normalized.isEmpty() ? "off" : normalized;
        trySaveConfig(src, "ai-provider", display);
        return 1;
    }

    private static int cmdConfigSetAiKey(ServerCommandSource src, String value) {
        if (value == null || value.isBlank()) {
            com.customblocks.CustomBlocksConfig.aiApiKey = "";
            try {
                com.customblocks.CustomBlocksConfig.save();
                ChatHelper.success(src, "§eai-key §acleared. AI generation will fall back to procedural.");
            } catch (Exception e) {
                ChatHelper.warn(src, "§eai-key §7cleared in memory but config save failed: §f" + e.getMessage());
            }
            return 1;
        }
        com.customblocks.CustomBlocksConfig.aiApiKey = value.trim();
        try {
            com.customblocks.CustomBlocksConfig.save();
            ChatHelper.success(src, "§eai-key §aset. §7Key stored in config.json — never shown in chat.");
        } catch (Exception e) {
            ChatHelper.warn(src, "§eai-key §7set in memory but config save failed: §f" + e.getMessage());
        }
        return 1;
    }

    private static int cmdConfigSetAiStyle(ServerCommandSource src, String value) {
        String normalized = switch (value.toLowerCase(java.util.Locale.ROOT).trim()) {
            case "pixel_art", "pixel" -> "pixel_art";
            case "natural"            -> "natural";
            case "flat"               -> "flat";
            default                   -> null;
        };
        if (normalized == null) {
            ChatHelper.error(src, "§fai-style §cmust be §fpixel_art§c, §fnatural§c, or §fflat§c.");
            return 0;
        }
        com.customblocks.CustomBlocksConfig.aiTextureStyle = normalized;
        trySaveConfig(src, "ai-style", normalized);
        return 1;
    }

    private static void trySaveConfig(ServerCommandSource src, String key, String value) {
        try {
            com.customblocks.CustomBlocksConfig.save();
            ChatHelper.success(src, "§e" + key + " §aset to §f" + value + "§a.");
        } catch (Exception e) {
            ChatHelper.warn(src, "§e" + key + " §7changed in memory but config save failed: §f" + e.getMessage() +
                "§7. Changes will not persist through restart.");
        }
    }

    // ── /cb arabic handlers ──────────────────────────────────────────────────

    private static int cmdArabicImport(ServerCommandSource src, String basePath) {
        String[] COLORS  = {"black", "yellow", "green", "red"};
        String[] FOLDERS = {"BLACK", "YELLOW", "GREEN", "RED"};
        ChatHelper.info(src, "§7Starting Arabic import from §f" + basePath + "§7...");

        EXECUTOR.submit(() -> {
            int imported = 0, skipped = 0, failed = 0;

            // Letter color folders: BLACK/, YELLOW/, GREEN/, RED/
            for (int ci = 0; ci < COLORS.length; ci++) {
                String color  = COLORS[ci];
                Path folder = Path.of(basePath, FOLDERS[ci]);
                if (!Files.isDirectory(folder)) {
                    final String msg = "Folder not found: " + folder + " — skipping " + color;
                    src.getServer().execute(() -> ChatHelper.warn(src, msg));
                    continue;
                }
                try (var stream = Files.list(folder)) {
                    for (Path file : stream.filter(f -> f.getFileName().toString().endsWith(".png")).toList()) {
                        String fname      = file.getFileName().toString();
                        // fname = "<letter_name>_<color>.png"
                        String letterName = fname.replace("_" + color + ".png", "");
                        String customId   = "arabic_" + letterName + "_" + color;
                        String dispName   = "Arabic " +
                            com.customblocks.arabic.ArabicLetterMap.displayName(letterName) + " " +
                            Character.toUpperCase(color.charAt(0)) + color.substring(1);

                        if (SlotManager.getById(customId) != null) { skipped++; continue; }
                        try {
                            byte[] bytes = Files.readAllBytes(file);
                            SlotData created = SlotManager.assign(customId, dispName, bytes);
                            if (created == null) { failed++; continue; }
                            boolean nonJoin = com.customblocks.arabic.ArabicLetterMap.isNonJoining(letterName);
                            SlotManager.update(customId, d -> d
                                .withIsLetter(true)
                                .withLetterConnectsLeft(!nonJoin)
                                .withLetterGroup("arabic_" + letterName)
                                .withLetterForm("isolated"));
                            com.customblocks.arabic.ArabicBlockRegistry.register(letterName, color, customId);
                            imported++;
                        } catch (Exception e) {
                            failed++;
                            CustomBlocksMod.LOGGER.error("[CB/Arabic] Failed to import {}: {}", file, e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    final String msg = "Error scanning " + folder + ": " + e.getMessage();
                    src.getServer().execute(() -> ChatHelper.warn(src, msg));
                }
            }

            // arabic_numbers_png/ folder: a0_black.png, a0_green.png, etc.
            Path numFolder = Path.of(basePath, "arabic_numbers_png");
            if (Files.isDirectory(numFolder)) {
                try (var stream = Files.list(numFolder)) {
                    for (Path file : stream.filter(f -> f.getFileName().toString().endsWith(".png")).toList()) {
                        String fname = file.getFileName().toString().replace(".png", "");
                        // fname = "a0_black", "a9_red", etc.
                        int ul = fname.lastIndexOf('_');
                        if (ul < 0) { failed++; continue; }
                        String letterName = fname.substring(0, ul);      // e.g. "a0"
                        String color      = fname.substring(ul + 1).toLowerCase(Locale.ROOT); // e.g. "black"
                        String customId   = "arabic_" + letterName + "_" + color;
                        String dispName   = "Arabic " + letterName.toUpperCase() + " " +
                            Character.toUpperCase(color.charAt(0)) + color.substring(1);

                        if (SlotManager.getById(customId) != null) { skipped++; continue; }
                        try {
                            byte[] bytes = Files.readAllBytes(file);
                            SlotData created = SlotManager.assign(customId, dispName, bytes);
                            if (created != null) {
                                SlotManager.update(customId, d -> d
                                    .withIsLetter(true)
                                    .withLetterGroup("arabic_" + letterName)
                                    .withLetterForm("isolated"));
                                com.customblocks.arabic.ArabicBlockRegistry.register(letterName, color, customId);
                                imported++;
                            } else { failed++; }
                        } catch (Exception e) { failed++; }
                    }
                } catch (Exception ignore) {}
            }

            // Trigger resource pack rebuild so clients see the new blocks
            NetworkManager.broadcastFullSync(src.getServer());

            final int fi = imported, fs = skipped, ff = failed;
            src.getServer().execute(() -> {
                ChatHelper.success(src, "Arabic import done: §f" + fi + " §aimported, §f" +
                    fs + " §askipped, §f" + ff + " §cfailed.");
                if (fi > 0)
                    ChatHelper.info(src, "§7Resource pack rebuilding — reconnect in ~10s to see new blocks.");
            });
        });
        return 1;
    }

    private static int cmdArabicGive(ServerCommandSource src, String letterName, String color) {
        ServerPlayerEntity p = src.getPlayer();
        if (p == null) { ChatHelper.error(src, "Player only."); return 0; }
        String col = color.toLowerCase(Locale.ROOT);
        String customId = com.customblocks.arabic.ArabicBlockRegistry.lookup(letterName, col);
        if (customId == null) {
            ChatHelper.error(src, "§fArabic " + letterName + " (" + col +
                ") §cnot imported. Run §f/cb arabic import <path> §cfirst.");
            return 0;
        }
        SlotData d = SlotManager.getById(customId);
        if (d == null) {
            ChatHelper.error(src, "Block §f" + customId + " §cis in the registry but not in SlotManager.");
            return 0;
        }
        com.customblocks.block.SlotBlock.SlotItem si = CustomBlocksMod.safeSlotItem(d.index);
        if (si == null) { ChatHelper.error(src, "Block §f" + customId + " §chas no registered item."); return 0; }
        ItemStack stack = new ItemStack(si);
        if (!p.getInventory().insertStack(stack)) {
            p.dropItem(stack, false);
            ChatHelper.info(src, "Inventory full — §f" + d.displayName + " §7dropped at your feet.");
        } else {
            ChatHelper.success(src, "Gave §f" + d.displayName + "§a.");
        }
        return 1;
    }

    private static int cmdArabicText(ServerCommandSource src, String color, String text) {
        ServerPlayerEntity p = src.getPlayer();
        if (p == null) { ChatHelper.error(src, "Player only."); return 0; }
        if (!com.customblocks.arabic.ArabicBlockRegistry.hasAny()) {
            ChatHelper.error(src, "No Arabic blocks imported yet. Run §f/cb arabic import <path> §cfirst.");
            return 0;
        }
        String col = color.toLowerCase(Locale.ROOT);
        List<String> blockIds = new ArrayList<>();
        List<Character> missing = new ArrayList<>();

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) continue;
            String blockId = com.customblocks.arabic.ArabicBlockRegistry.lookupByChar(ch, col);
            if (blockId == null) {
                // Try Western digit as a-style: '5' -> "a5"
                if (ch >= '0' && ch <= '9')
                    blockId = com.customblocks.arabic.ArabicBlockRegistry.lookup("a" + ch, col);
            }
            if (blockId == null) { missing.add(ch); continue; }
            blockIds.add(blockId);
        }

        if (blockIds.isEmpty()) {
            ChatHelper.error(src, "No imported letters found for \"" + text + "\" in " + col + ".");
            return 0;
        }

        // Reverse so the player places left-to-right for RTL reading order
        Collections.reverse(blockIds);
        int given = 0;
        for (String id : blockIds) {
            SlotData d = SlotManager.getById(id);
            if (d == null) continue;
            com.customblocks.block.SlotBlock.SlotItem si = CustomBlocksMod.safeSlotItem(d.index);
            if (si == null) continue;
            ItemStack stack = new ItemStack(si);
            if (!p.getInventory().insertStack(stack)) p.dropItem(stack, false);
            given++;
        }
        ChatHelper.success(src, "Gave §f" + given + " §aletter block" + (given == 1 ? "" : "s") +
            "§a. Place left→right for correct Arabic reading order.");
        if (!missing.isEmpty())
            ChatHelper.warn(src, "§7" + missing.size() + " character(s) not found in the " + col + " set.");
        return 1;
    }

    // ── Phase 10.5 — /cb screenshot ───────────────────────────────────────────

    private static int cmdScreenshot(ServerCommandSource src, String id) {
        com.customblocks.core.SlotData d = com.customblocks.core.SlotManager.getById(id);
        if (d == null) {
            ChatHelper.error(src, "Block §f" + id + " §cnot found.");
            return 0;
        }
        if (d.texture == null || d.texture.length == 0) {
            ChatHelper.error(src, "§f" + id + " §chas no texture. Upload one first with /cb retexture.");
            return 0;
        }
        final byte[] textureBytes = d.texture;
        EXECUTOR.submit(() -> {
            try {
                java.nio.file.Path exportDir = java.nio.file.Path.of("config/customblocks/exports");
                java.nio.file.Files.createDirectories(exportDir);
                String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
                String fileName = id + "_" + ts + ".png";
                java.nio.file.Path out = exportDir.resolve(fileName);
                java.nio.file.Files.write(out, textureBytes);

                String packUrl = com.customblocks.network.ResourcePackServer.getPackUrl(src.getServer());
                src.getServer().execute(() -> {
                    ChatHelper.success(src, "§fScreenshot saved: §b" + fileName);
                    if (packUrl != null && !packUrl.isBlank()) {
                        // Build download URL by replacing pack.zip path with exports/<filename>
                        String base = packUrl.replace("pack.zip", "exports/" + fileName);
                        net.minecraft.text.Text link = net.minecraft.text.Text.literal("§a§n[Click to Download]")
                            .styled(s -> s.withClickEvent(new net.minecraft.text.ClickEvent(
                                net.minecraft.text.ClickEvent.Action.OPEN_URL, base)));
                        ServerPlayerEntity p = src.getPlayer();
                        if (p != null) p.sendMessage(link, false);
                    } else {
                        ChatHelper.info(src, "§7Saved to §fconfig/customblocks/exports/" + fileName +
                            " §7(no link — HTTP server offline).");
                    }
                });
            } catch (Exception e) {
                src.getServer().execute(() -> ChatHelper.error(src, "Screenshot failed: " + e.getMessage()));
            }
        });
        return 1;
    }
}
