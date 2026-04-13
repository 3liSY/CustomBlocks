package com.customblocks.command;

import com.customblocks.CustomBlocksMod;
import com.customblocks.CustomBlocksConfig;
import com.customblocks.gui.GuiManager;
import com.customblocks.ImageProcessor;
import com.customblocks.core.SlotData;
import com.customblocks.core.SlotManager;
import com.customblocks.core.UndoManager;
import com.customblocks.network.NetworkManager;
import com.customblocks.command.PermissionHelper;
import com.customblocks.block.SlotBlock;
import com.customblocks.network.SlotUpdatePayload;
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

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CustomBlockCommand {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);

    private static final SuggestionProvider<ServerCommandSource> BLOCK_SUGGESTIONS =
            (ctx, builder) -> { for (String id : SlotManager.allSlots().stream().map(d -> d.customId).collect(java.util.stream.Collectors.toList())) builder.suggest(id); return builder.buildFuture(); };

    private static final String[] VALID_SOUNDS = {
        "stone","wood","grass","metal","glass","sand","wool",
        "gravel","snow","dirt","coral","bamboo","nether_brick","ice","honey","bone","slime"
    };
    private static final SuggestionProvider<ServerCommandSource> SOUND_SUGGESTIONS =
            (ctx, builder) -> { for (String s : VALID_SOUNDS) builder.suggest(s); return builder.buildFuture(); };

    private static final SuggestionProvider<ServerCommandSource> FACE_SUGGESTIONS =
            (ctx, builder) -> { for (String f : SlotData.FACE_KEYS) builder.suggest(f); return builder.buildFuture(); };

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, reg, env) -> {
            var tree = CommandManager.literal("customblock")
                .requires(src -> PermissionHelper.canUse(src))

                // ── create / createurl ──────────────────────────────────────
                .then(CommandManager.literal("create")
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .then(CommandManager.argument("name", StringArgumentType.string())
                            // /cb create <id> <name> <size> <url>  — size first so greedy URL still works
                            .then(CommandManager.argument("size", IntegerArgumentType.integer(16, 256))
                                .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                    .executes(ctx -> cmdCreate(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id"),
                                        StringArgumentType.getString(ctx, "name"),
                                        StringArgumentType.getString(ctx, "url").trim(),
                                        IntegerArgumentType.getInteger(ctx, "size")))))
                            // /cb create <id> <name> <url>  — default 128
                            .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(ctx -> cmdCreate(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "id"),
                                    StringArgumentType.getString(ctx, "name"),
                                    StringArgumentType.getString(ctx, "url").trim(),
                                    ImageProcessor.DEFAULT_SIZE))))))

                // ── delete ──────────────────────────────────────────────────
                .then(CommandManager.literal("delete")
                    .executes(ctx -> usage(ctx.getSource(), "delete"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdDelete(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))

                // ── bulkdelete ──────────────────────────────────────────────
                .then(CommandManager.literal("bulkdelete")
                    .executes(ctx -> usage(ctx.getSource(), "bulkdelete"))
                    .then(CommandManager.argument("ids", StringArgumentType.greedyString())
                        .executes(ctx -> cmdBulkDelete(ctx.getSource(),
                            StringArgumentType.getString(ctx, "ids")))))

                // ── rename ──────────────────────────────────────────────────
                .then(CommandManager.literal("rename")
                    .executes(ctx -> usage(ctx.getSource(), "rename"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("newname", StringArgumentType.greedyString())
                            .executes(ctx -> cmdRename(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "newname"))))))

                // ── reid ────────────────────────────────────────────────────
                .then(CommandManager.literal("reid")
                    .executes(ctx -> usage(ctx.getSource(), "reid"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("newid", StringArgumentType.word())
                            .executes(ctx -> cmdReId(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "newid"))))))

                // ── retexture ───────────────────────────────────────────────
                .then(CommandManager.literal("retexture")
                    .executes(ctx -> usage(ctx.getSource(), "retexture"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("size", IntegerArgumentType.integer(16, 256))
                            .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(ctx -> cmdRetexture(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "id"),
                                    StringArgumentType.getString(ctx, "url").trim(),
                                    IntegerArgumentType.getInteger(ctx, "size")))))
                        .then(CommandManager.argument("url", StringArgumentType.greedyString())
                            .executes(ctx -> cmdRetexture(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "url").trim(),
                                ImageProcessor.DEFAULT_SIZE)))))

                // ── give ────────────────────────────────────────────────────
                .then(CommandManager.literal("give")
                    .executes(ctx -> usage(ctx.getSource(), "give"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
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
                                EntityArgumentType.getPlayers(ctx, "player"))))))

                // ── setglow ─────────────────────────────────────────────────
                .then(CommandManager.literal("setglow")
                    .executes(ctx -> usage(ctx.getSource(), "setglow"))
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("level", IntegerArgumentType.integer(0, 15))
                            .executes(ctx -> cmdSetGlow(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                IntegerArgumentType.getInteger(ctx, "level"))))))

                // ── sethardness ─────────────────────────────────────────────
                .then(CommandManager.literal("sethardness")
                    .executes(ctx -> usage(ctx.getSource(), "sethardness"))
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("hardness", FloatArgumentType.floatArg(-1f, 50f))
                            .executes(ctx -> cmdSetHardness(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                FloatArgumentType.getFloat(ctx, "hardness"))))))

                // ── setsound ────────────────────────────────────────────────
                .then(CommandManager.literal("setsound")
                    .executes(ctx -> usage(ctx.getSource(), "setsound"))
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("type", StringArgumentType.word()).suggests(SOUND_SUGGESTIONS)
                            .executes(ctx -> cmdSetSound(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "type"))))))

                // ── showbrokenblocks ────────────────────────────────────────
                .then(CommandManager.literal("showbrokenblocks")
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) GuiManager.openBrokenBlocks(p);
                        else ctx.getSource().sendError(Text.literal("Player only."));
                        return 1;
                    }))

                .then(CommandManager.literal("settabicon")
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) GuiManager.openTabIconPicker(p, 0);
                        else ctx.getSource().sendError(Text.literal("Player only."));
                        return 1;
                    })
                    .then(CommandManager.argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> cmdSetTabIcon(ctx.getSource(),
                            StringArgumentType.getString(ctx, "url").trim()))))
                // ── resize ──────────────────────────────────────────────────
                .then(CommandManager.literal("resize")
                    .executes(ctx -> usage(ctx.getSource(), "resize"))
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("size", IntegerArgumentType.integer(16, 256))
                            .executes(ctx -> cmdResize(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                IntegerArgumentType.getInteger(ctx, "size"))))))

                // ── undo ────────────────────────────────────────────────────
                .then(CommandManager.literal("undo")
                    .executes(ctx -> cmdUndo(ctx.getSource())))

                // ── redo ────────────────────────────────────────────────────
                .then(CommandManager.literal("redo")
                    .executes(ctx -> cmdRedo(ctx.getSource())))

                // (givesquare/givetriangle/giverectangle removed — use /cb square, /cb triangle, /cb rectangle)

                // ── dupe / duplicate ────────────────────────────────────────
                .then(CommandManager.literal("dupe")
                    .executes(ctx -> usage(ctx.getSource(), "dupe"))
                    .then(CommandManager.argument("sourceId", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("newId", StringArgumentType.word())
                            .executes(ctx -> cmdDupe(ctx.getSource(),
                                StringArgumentType.getString(ctx, "sourceId"),
                                StringArgumentType.getString(ctx, "newId"), null))
                            .then(CommandManager.argument("newname", StringArgumentType.greedyString())
                                .executes(ctx -> cmdDupe(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "sourceId"),
                                    StringArgumentType.getString(ctx, "newId"),
                                    StringArgumentType.getString(ctx, "newname")))))))

                .then(CommandManager.literal("duplicate")
                    .executes(ctx -> usage(ctx.getSource(), "dupe"))
                    .then(CommandManager.argument("sourceId", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("newId", StringArgumentType.word())
                            .executes(ctx -> cmdDupe(ctx.getSource(),
                                StringArgumentType.getString(ctx, "sourceId"),
                                StringArgumentType.getString(ctx, "newId"), null))
                            .then(CommandManager.argument("newname", StringArgumentType.greedyString())
                                .executes(ctx -> cmdDupe(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "sourceId"),
                                    StringArgumentType.getString(ctx, "newId"),
                                    StringArgumentType.getString(ctx, "newname")))))))

                // ── data commands ───────────────────────────────────────────
                .then(CommandManager.literal("export")
                    .executes(ctx -> cmdExport(ctx.getSource())))

                .then(CommandManager.literal("importfolder")
                    .executes(ctx -> cmdImportFolder(ctx.getSource())))

                .then(CommandManager.literal("list")
                    .executes(ctx -> cmdList(ctx.getSource())))

                .then(CommandManager.literal("help")
                    .executes(ctx -> cmdHelp(ctx.getSource())))

                // ── new graphical interfaces ─────────────────────────────────
                .then(CommandManager.literal("listgui")
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) GuiManager.openEditorPicker(p, 0);
                        else ctx.getSource().sendError(Text.literal("Player only."));
                        return 1;
                    }))

                .then(CommandManager.literal("helpgui")
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) GuiManager.openHelpGui(p);
                        else ctx.getSource().sendError(Text.literal("Player only."));
                        return 1;
                    }))

                .then(CommandManager.literal("magicitems")
                    .executes(ctx -> {
                        cmdGiveTriangleInternal(ctx.getSource(), "green");
                        cmdGiveTriangleInternal(ctx.getSource(), "yellow");
                        cmdGiveTriangleInternal(ctx.getSource(), "black");
                        cmdGiveSquareInternal(ctx.getSource(), "green");
                        cmdGiveSquareInternal(ctx.getSource(), "yellow");
                        cmdGiveSquareInternal(ctx.getSource(), "black");
                        cmdGiveRectangleInternal(ctx.getSource());
                        ctx.getSource().sendMessage(Text.literal("§a[CustomBlocks] Granted all magic items!"));
                        return 1;
                    }))

                // ── editor — works with or without ID ──────────────────────
                .then(CommandManager.literal("editor")
                    .executes(ctx -> cmdEditorPicker(ctx.getSource()))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdEditor(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id")))))

                // ── gui ──────────────────────────────────────────────────────
                .then(CommandManager.literal("gui")
                    .executes(ctx -> cmdGui(ctx.getSource())))

                // ── reload ───────────────────────────────────────────────────
                .then(CommandManager.literal("reload")
                    .executes(ctx -> cmdReload(ctx.getSource())))

                // ── resourcepack ─────────────────────────────────────────────
                .then(CommandManager.literal("resourcepack")
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p != null) GuiManager.openPortConfigMenu(p);
                        else ctx.getSource().sendError(Text.literal("Player only."));
                        return 1;
                    })
                    .then(CommandManager.literal("setport")
                        .executes(ctx -> {
                            ServerPlayerEntity p = ctx.getSource().getPlayer();
                            if (p != null) GuiManager.openPortConfigMenu(p);
                            else ctx.getSource().sendError(Text.literal("Player only."));
                            return 1;
                        })
                        .then(CommandManager.argument("port", IntegerArgumentType.integer(0, 65535))
                            .executes(ctx -> {
                                int port = IntegerArgumentType.getInteger(ctx, "port");
                                CustomBlocksConfig.resourcePackPort = port;
                                CustomBlocksConfig.save();
                                com.customblocks.network.ResourcePackServer.start();
                                ctx.getSource().sendMessage(Text.literal("§a[CustomBlocks] Server port set to " + port));
                                return 1;
                            })))
                    .then(CommandManager.literal("push")
                        .executes(ctx -> {
                            String hash = com.customblocks.network.ResourcePackServer.getHash();
                            if (hash == null) {
                                ctx.getSource().sendError(Text.literal("§cPack not built yet."));
                                return 0;
                            }
                            int port = CustomBlocksConfig.resourcePackPort;
                            String serverIp = ctx.getSource().getServer().getServerIp();
                            if (serverIp == null || serverIp.isEmpty()) serverIp = "127.0.0.1";
                            String url = "http://" + serverIp + ":" + port + "/pack.zip";
                            
                            for (ServerPlayerEntity p : ctx.getSource().getServer().getPlayerManager().getPlayerList()) {
                                p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket(
                                    java.util.UUID.randomUUID(), url, hash, true, java.util.Optional.of(Text.literal("Custom Blocks Texture Sync"))
                                ));
                            }
                            ctx.getSource().sendMessage(Text.literal("§a[CustomBlocks] Pushed resource pack to all players!"));
                            return 1;
                        })))

                // ── setshape ─────────────────────────────────────────────────
                .then(CommandManager.literal("setshape")
                    .executes(ctx -> usage(ctx.getSource(), "setshape"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("shape", StringArgumentType.greedyString())
                            .executes(ctx -> cmdSetShape(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "shape"))))))

                .then(CommandManager.literal("addshape")
                    .executes(ctx -> usage(ctx.getSource(), "addshape"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("coords", StringArgumentType.greedyString())
                            .executes(ctx -> cmdAddShape(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "coords"))))))

                .then(CommandManager.literal("removeshape")
                    .executes(ctx -> usage(ctx.getSource(), "removeshape"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("boxindex", IntegerArgumentType.integer(0, 15))
                            .executes(ctx -> cmdRemoveShape(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                IntegerArgumentType.getInteger(ctx, "boxindex"))))))

                .then(CommandManager.literal("clearshape")
                    .executes(ctx -> usage(ctx.getSource(), "clearshape"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdClearShape(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id")))))

                .then(CommandManager.literal("setcollision")
                    .executes(ctx -> usage(ctx.getSource(), "setcollision"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.literal("on")
                            .executes(ctx -> cmdSetCollision(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"), true)))
                        .then(CommandManager.literal("off")
                            .executes(ctx -> cmdSetCollision(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"), false)))))

                .then(CommandManager.literal("saveshape")
                    .executes(ctx -> usage(ctx.getSource(), "saveshape"))
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .then(CommandManager.argument("id", StringArgumentType.word())
                            .suggests(BLOCK_SUGGESTIONS)
                            .executes(ctx -> cmdSaveShape(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name"),
                                StringArgumentType.getString(ctx, "id"))))))

                .then(CommandManager.literal("loadshape")
                    .executes(ctx -> usage(ctx.getSource(), "loadshape"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .executes(ctx -> cmdLoadShape(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "name"))))))

                .then(CommandManager.literal("shapeeditor")
                    .executes(ctx -> usage(ctx.getSource(), "shapeeditor"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdShapeEditor(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id")))))

                .then(CommandManager.literal("square")
                    .executes(ctx -> usage(ctx.getSource(), "square"))
                    .then(CommandManager.argument("color", StringArgumentType.word())
                        .suggests((ctx, b) -> { b.suggest("black"); b.suggest("yellow"); b.suggest("green"); return b.buildFuture(); })
                        .executes(ctx -> cmdGiveSquare(ctx.getSource(),
                            StringArgumentType.getString(ctx, "color")))))

                .then(CommandManager.literal("triangle")
                    .executes(ctx -> usage(ctx.getSource(), "triangle"))
                    .then(CommandManager.argument("color", StringArgumentType.word())
                        .suggests((ctx, b) -> { b.suggest("black"); b.suggest("yellow"); b.suggest("green"); return b.buildFuture(); })
                        .executes(ctx -> cmdGiveTriangle(ctx.getSource(),
                            StringArgumentType.getString(ctx, "color")))))

                .then(CommandManager.literal("rectangle")
                    .executes(ctx -> cmdGiveRectangle(ctx.getSource())))

                // ── setface ──────────────────────────────────────────────────
                .then(CommandManager.literal("setface")
                    .executes(ctx -> usage(ctx.getSource(), "setface"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("face", StringArgumentType.word()).suggests(FACE_SUGGESTIONS)
                            .then(CommandManager.argument("size", IntegerArgumentType.integer(16, 256))
                                .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                    .executes(ctx -> cmdSetFace(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id"),
                                        StringArgumentType.getString(ctx, "face"),
                                        StringArgumentType.getString(ctx, "url").trim(),
                                        IntegerArgumentType.getInteger(ctx, "size")))))
                            .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(ctx -> cmdSetFace(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "id"),
                                    StringArgumentType.getString(ctx, "face"),
                                    StringArgumentType.getString(ctx, "url").trim(),
                                    ImageProcessor.DEFAULT_SIZE))))))

                // ── clearface ────────────────────────────────────────────────
                .then(CommandManager.literal("clearface")
                    .executes(ctx -> usage(ctx.getSource(), "clearface"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("face", StringArgumentType.word()).suggests(FACE_SUGGESTIONS)
                            .executes(ctx -> cmdClearFace(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "face"))))))

                // ── clearallfaces ────────────────────────────────────────────
                .then(CommandManager.literal("clearallfaces")
                    .executes(ctx -> usage(ctx.getSource(), "clearallfaces"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdClearAllFaces(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id")))));

            dispatcher.register(tree);
            dispatcher.register(CommandManager.literal("cb")
                .requires(src -> PermissionHelper.canUse(src))
                .redirect(dispatcher.getRoot().getChild("customblock")));
        });
    }

    // ── Implementations ───────────────────────────────────────────────────────

    private static int cmdCreate(ServerCommandSource src, String rawId, String name, String url, int size) {
        String id = sanitize(rawId);
        if (id.isEmpty()) { src.sendError(Text.literal("§cInvalid ID.")); return 0; }
        if (SlotManager.hasId(id)) { src.sendError(Text.literal("§c'" + id + "' already exists.")); return 0; }
        if (SlotManager.freeSlots() == 0) { src.sendError(Text.literal("§cAll " + CustomBlocksConfig.maxSlots + " slots are full!")); return 0; }
        src.sendMessage(Text.literal("§e[CustomBlocks] Downloading... §7(" + size + "px)"));
        MinecraftServer server = src.getServer();
        thread(() -> {
            try {
                byte[] raw = ImageProcessor.download(url);

                // Detect animated GIF
                final ImageProcessor.GifResult gifResult =
                        ImageProcessor.isAnimatedGif(raw) ? ImageProcessor.processGif(raw, size) : null;
                if (gifResult != null)
                    server.execute(() -> src.sendMessage(Text.literal(
                        "§b[CustomBlocks] Animated GIF detected — " + gifResult.frameCount() + " frames!")));

                byte[] bytes;
                String animMeta = null;
                if (gifResult != null) {
                    bytes = gifResult.stripPng();
                    animMeta = gifResult.mcmeta();
                } else {
                    bytes = ImageProcessor.toPng(raw);
                    bytes = ImageProcessor.padToSquare(bytes);
                    bytes = ImageProcessor.replaceBackground(bytes);
                    bytes = ImageProcessor.resizeTo(bytes, size);
                }

                final byte[] finalBytes = bytes;
                final String finalAnim  = animMeta;
                server.execute(() -> {
                    SlotData d = SlotManager.assign(id, name, finalBytes);
                    if (d == null) { src.sendError(Text.literal("§cNo free slots!")); return; }
                    if (finalAnim != null) SlotManager.setAnimMeta(id, finalAnim);
                    UndoManager.pushUndoCreate(id, getPlayerUuid(src));
                    SlotManager.saveAll();
                    NetworkManager.broadcastUpdate(server,
                        new SlotUpdatePayload("add", d.index, id, name, finalBytes,
                                d.lightLevel, d.hardness, d.soundType, null, null, finalAnim));
                    src.sendMessage(Text.literal("§a[CustomBlocks] '" + name + "' created! §7(slot " + d.index + ")"));
                });
            } catch (Exception e) {
                server.execute(() -> src.sendError(Text.literal("§c[CustomBlocks] Failed: " + e.getMessage())));
            }
        });
        return 1;
    }

    private static int cmdDupe(ServerCommandSource src, String rawSourceId, String rawNewId, String newName) {
        String sourceId = sanitize(rawSourceId);
        String newId    = sanitize(rawNewId);
        if (!SlotManager.hasId(sourceId)) { src.sendError(notFound(sourceId)); return 0; }
        if (newId.isEmpty())              { src.sendError(Text.literal("§cInvalid new ID.")); return 0; }
        if (SlotManager.hasId(newId))     { src.sendError(Text.literal("§c'" + newId + "' already exists.")); return 0; }
        if (SlotManager.freeSlots() == 0) { src.sendError(Text.literal("§cAll " + CustomBlocksConfig.maxSlots + " slots are full!")); return 0; }

        SlotData s = SlotManager.getById(sourceId);
        String finalName = (newName != null && !newName.isBlank()) ? newName : s.displayName + " (Copy)";

        byte[] texCopy = s.texture != null ? s.texture.clone() : null;
        SlotData d = SlotManager.assign(newId, finalName, texCopy);
        if (d == null) { src.sendError(Text.literal("§cNo free slots!")); return 0; }

        // Copy all properties and per-face textures
        SlotManager.setLightLevel(newId, s.lightLevel);
        SlotManager.setHardness(newId, s.hardness);
        SlotManager.setSoundType(newId, s.soundType);
        if (s.animMeta != null) SlotManager.setAnimMeta(newId, s.animMeta);
        for (var e : s.faceTextures.entrySet())
            SlotManager.setFaceTexture(newId, e.getKey(), e.getValue().clone());

        UndoManager.pushUndoCreate(newId, getPlayerUuid(src));
        SlotManager.saveAll();
        d = SlotManager.getById(newId);
        NetworkManager.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("add", d.index, newId, finalName, texCopy,
                    d.lightLevel, d.hardness, d.soundType, null, null, d.animMeta));
        src.sendMessage(Text.literal("§a[CustomBlocks] Duplicated '§f" + sourceId + "§a' → '§f" + newId + "§a' §7(slot " + d.index + ")"));
        return 1;
    }

    private static int cmdDelete(ServerCommandSource src, String id) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        SlotData d = SlotManager.getById(id);
        UndoManager.pushUndoDeletion(id, d.deepCopy(), getPlayerUuid(src));
        SlotManager.remove(id);
        SlotManager.saveAll();
        NetworkManager.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("remove", d.index, id, null, null, 0, 0, "stone"));
        src.sendMessage(Text.literal("§a[CustomBlocks] '" + id + "' deleted."));
        return 1;
    }

    private static int cmdBulkDelete(ServerCommandSource src, String idsRaw) {
        String[] ids = idsRaw.trim().split("\\s+");
        List<String> deleted = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        for (String id : ids) {
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
            src.sendMessage(Text.literal("§a[CustomBlocks] Deleted: " + String.join(", ", deleted)));
        }
        if (!notFound.isEmpty()) {
            src.sendError(Text.literal("§c[CustomBlocks] Not found: " + String.join(", ", notFound)));
        }
        return deleted.isEmpty() ? 0 : 1;
    }

    private static int cmdRename(ServerCommandSource src, String id, String newName) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        SlotData d = SlotManager.getById(id);
        UndoManager.pushUndoMutation(id, d, "rename", getPlayerUuid(src));
        SlotManager.rename(id, newName);
        SlotManager.saveAll();
        NetworkManager.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("rename", d.index, id, newName, null, 0, 0, "stone"));
        src.sendMessage(Text.literal("§a[CustomBlocks] Renamed to '" + newName + "'."));
        return 1;
    }

    private static int cmdReId(ServerCommandSource src, String oldId, String newId) {
        if (!SlotManager.hasId(oldId)) { src.sendError(notFound(oldId)); return 0; }
        if (SlotManager.hasId(newId)) {
            src.sendError(Text.literal("§c[CustomBlocks] ID '" + newId + "' is already taken."));
            return 0;
        }
        if (!newId.matches("[a-z0-9_\\-]+")) {
            src.sendError(Text.literal("§c[CustomBlocks] ID must be lowercase letters, numbers, _ or - only."));
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
        src.sendMessage(Text.literal("§a[CustomBlocks] Re-ID'd §f'" + oldId + "' §a→ §f'" + newId + "'."));
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
                src.sendError(Text.literal("§cUnknown preset or bad coords. Presets: " + String.join(", ", SlotManager.SHAPE_PRESETS.keySet())));
                return 0;
            }
        }
        UndoManager.pushUndoMutation(id, SlotManager.getById(id), "setshape", getPlayerUuid(src));
        SlotManager.setShape(id, boxes);
        SlotManager.saveAll();
        SlotData d = SlotManager.getById(id);
        broadcastShape(src.getServer(), d);
        src.sendMessage(Text.literal("§a[CustomBlocks] Shape set to '§f" + shapeArgTrimmed + "§a' on '§f" + id + "§a'."));
        return 1;
    }

    private static int cmdAddShape(ServerCommandSource src, String id, String coords) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        SlotData d = SlotManager.getById(id);
        int current = d.shapeBoxes != null ? d.shapeBoxes.size() : 0;
        if (current >= 16) { src.sendError(Text.literal("§cMax 16 boxes per block.")); return 0; }
        SlotData.ShapeBox box;
        try {
            box = SlotData.ShapeBox.parse(coords.trim());
            
        } catch (Exception e) {
            src.sendError(Text.literal("§cBad coords. Format: x1,y1,z1,x2,y2,z2 (0–16)")); return 0;
        }
        UndoManager.pushUndoMutation(id, SlotManager.getById(id), "addshape", getPlayerUuid(src));
        SlotManager.addBox(id, box);
        SlotManager.saveAll();
        d = SlotManager.getById(id);
        broadcastShape(src.getServer(), d);
        src.sendMessage(Text.literal("§a[CustomBlocks] Added box #§f" + (current + 1) + "§a to '§f" + id + "§a'. Total: §f" + d.shapeBoxes.size()));
        return 1;
    }

    private static int cmdRemoveShape(ServerCommandSource src, String id, int index) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        UndoManager.pushUndoMutation(id, SlotManager.getById(id), "removeshape", getPlayerUuid(src));
        SlotManager.removeBox(id, index);
        SlotManager.saveAll();
        SlotData d = SlotManager.getById(id);
        broadcastShape(src.getServer(), d);
        src.sendMessage(Text.literal("§a[CustomBlocks] Removed box #§f" + index + "§a from '§f" + id + "§a'."));
        return 1;
    }

    private static int cmdClearShape(ServerCommandSource src, String id) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        UndoManager.pushUndoMutation(id, SlotManager.getById(id), "clearshape", getPlayerUuid(src));
        SlotManager.setShape(id, null);
        SlotManager.saveAll();
        SlotData d = SlotManager.getById(id);
        broadcastShape(src.getServer(), d);
        src.sendMessage(Text.literal("§a[CustomBlocks] Shape reset to full cube on '§f" + id + "§a'."));
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
        src.sendMessage(Text.literal("§a[CustomBlocks] Collision §f" + (on ? "ON" : "OFF") + "§a for '§f" + id + "§a'."));
        return 1;
    }

    private static int cmdSaveShape(ServerCommandSource src, String name, String id) {
        src.sendError(net.minecraft.text.Text.literal("§cTemplates disabled."));
        return 0;
    }

    private static int cmdLoadShape(ServerCommandSource src, String id, String name) {
        src.sendError(net.minecraft.text.Text.literal("§cTemplates disabled."));
        return 0;
    }

    private static int cmdShapeEditor(ServerCommandSource src, String id) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        net.minecraft.server.network.ServerPlayerEntity player = src.getPlayer();
        if (player == null) { src.sendError(Text.literal("§cPlayer only.")); return 0; }
        com.customblocks.gui.GuiManager.openShapeEditor(player, id, 0);
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
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        src.sendMessage(Text.literal("§e[CustomBlocks] Downloading texture... §7(" + size + "px)"));
        MinecraftServer server = src.getServer();
        thread(() -> {
            try {
                byte[] raw = ImageProcessor.download(url);
                ImageProcessor.GifResult gifResult = ImageProcessor.isAnimatedGif(raw) ? ImageProcessor.processGif(raw, size) : null;
                byte[] bytes;
                String animMeta = null;
                if (gifResult != null) { bytes = gifResult.stripPng(); animMeta = gifResult.mcmeta(); }
                else {
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
                    if (d == null) { src.sendError(notFound(id)); return; }
                    SlotManager.updateTexture(id, fb);
                    if (fa != null) SlotManager.setAnimMeta(id, fa);
                    SlotManager.saveAll();
                    NetworkManager.broadcastUpdate(server,
                        new SlotUpdatePayload("retexture", d.index, id, null, fb,
                                d.lightLevel, d.hardness, d.soundType, null, null, fa));
                    src.sendMessage(Text.literal("§a[CustomBlocks] Texture updated for '" + id + "'."));
                });
            } catch (Exception e) {
                server.execute(() -> src.sendError(Text.literal("§c[CustomBlocks] Failed: " + e.getMessage())));
            }
        });
        return 1;
    }

    private static int cmdGive(ServerCommandSource src, String id, int amount, Collection<ServerPlayerEntity> targets) {
        SlotData d = SlotManager.getById(id);
        if (d == null) { src.sendError(notFound(id)); return 0; }
        SlotBlock.SlotItem item = CustomBlocksMod.safeSlotItem(d.index); if (item == null) { src.sendError(Text.literal("§cSlot item missing for index " + d.index)); return 0; }
        ItemStack stack = new ItemStack(item, Math.max(1, Math.min(64, amount)));
        if (targets == null || targets.isEmpty()) {
            try {
                ServerPlayerEntity self = src.getPlayerOrThrow();
                self.getInventory().insertStack(stack.copy());
                src.sendMessage(Text.literal("§a[CustomBlocks] Given " + amount + "x '" + d.displayName + "' to you."));
            } catch (Exception ex) { src.sendError(Text.literal("§cRun as a player or specify a target.")); }
        } else {
            for (ServerPlayerEntity p : targets) {
                p.getInventory().insertStack(stack.copy());
                p.sendMessage(Text.literal("§a[CustomBlocks] You received " + amount + "x '" + d.displayName + "'."));
            }
            src.sendMessage(Text.literal("§a[CustomBlocks] Gave " + amount + "x to " + targets.size() + " player(s)."));
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
        src.sendMessage(Text.literal("§a[CustomBlocks] '" + id + "' light level set to " + level + ". §7(0=off, 7=torch, 14=sea lantern, 15=glowstone)"));
        return 1;
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
        src.sendMessage(Text.literal("§a[CustomBlocks] '" + id + "' hardness: " + label + "."));
        return 1;
    }

    private static int cmdSetSound(ServerCommandSource src, String id, String type) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        boolean ok = false;
        for (String v : VALID_SOUNDS) if (v.equals(type)) { ok = true; break; }
        if (!ok) { src.sendError(Text.literal("§cValid: stone wood grass metal glass sand wool gravel snow dirt coral bamboo nether_brick ice honey bone slime")); return 0; }
        SlotData d = SlotManager.getById(id);
        UndoManager.pushUndoMutation(id, d, "setsound", getPlayerUuid(src));
        SlotManager.setSoundType(id, type);
        SlotManager.saveAll();
        NetworkManager.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("setprop", d.index, id, null, null, d.lightLevel, d.hardness, type));
        src.sendMessage(Text.literal("§a[CustomBlocks] '" + id + "' sound: " + type + "."));
        return 1;
    }

    private static int cmdSetTabIcon(ServerCommandSource src, String url) {
        src.sendMessage(Text.literal("§e[CustomBlocks] Downloading tab icon..."));
        MinecraftServer server = src.getServer();
        thread(() -> {
            try {
                byte[] bytes = ImageProcessor.downloadAndProcess(url);
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
                    src.sendMessage(Text.literal("§a[CustomBlocks] Tab icon updated! §7(Takes a few seconds to appear — resource pack is reloading)"));
                });
            } catch (Exception e) {
                server.execute(() -> src.sendError(Text.literal("§c[CustomBlocks] Failed: " + e.getMessage())));
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
        src.sendMessage(Text.literal("§e[CustomBlocks] Downloading " + face + " face... §7(" + size + "px)"));
        MinecraftServer server = src.getServer();
        thread(() -> {
            try {
                byte[] raw = ImageProcessor.download(url);
                byte[] bytes = ImageProcessor.toPng(raw);
                bytes = ImageProcessor.padToSquare(bytes);
                bytes = ImageProcessor.replaceBackground(bytes);
                bytes = ImageProcessor.resizeTo(bytes, size);
                final byte[] fb = bytes;
                server.execute(() -> {
                    SlotData d = SlotManager.getById(id);
        UndoManager.pushUndoMutation(id, d, "setface " + face, getPlayerUuid(src));
                    if (d == null) { src.sendError(Text.literal("§c[CustomBlocks] '" + id + "' was deleted.")); return; }
                    SlotManager.setFaceTexture(id, face, fb);
                    SlotManager.saveAll();
                    // Broadcast setface — clients apply it to ONLY this face
                    NetworkManager.broadcastUpdate(server,
                        new SlotUpdatePayload("setface", d.index, id, null, fb,
                                d.lightLevel, d.hardness, d.soundType, face));
                    src.sendMessage(Text.literal("§a[CustomBlocks] " + face.toUpperCase() + " face set on '" + id + "'."));
                });
            } catch (Exception e) {
                server.execute(() -> src.sendError(Text.literal("§c[CustomBlocks] Failed: " + e.getMessage())));
            }
        });
        return 1;
    }

    private static int cmdClearFace(ServerCommandSource src, String id, String face) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        if (!SlotData.FACE_KEYS.contains(face)) { src.sendError(Text.literal("§cValid faces: top bottom north south east west")); return 0; }
        SlotData d = SlotManager.getById(id);
        UndoManager.pushUndoMutation(id, d, "clearface " + face, getPlayerUuid(src));
        if (d == null) { src.sendError(notFound(id)); return 0; }
        SlotManager.clearFaceTexture(id, face);
        SlotManager.saveAll();
        // Broadcast clearface so clients revert that face to default
        NetworkManager.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("clearface", d.index, id, null, null,
                    d.lightLevel, d.hardness, d.soundType, face));
        src.sendMessage(Text.literal("§a[CustomBlocks] " + face + " face cleared on '" + id + "'."));
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
        src.sendMessage(Text.literal("§a[CustomBlocks] All face overrides cleared on '" + id + "'."));
        return 1;
    }

    /** Undo the last block modification (retexture, setface, setglow, delete, create, …). */
    private static int cmdUndo(ServerCommandSource src) {
        if (UndoManager.undoSize(getPlayerUuid(src)) == 0) {
            src.sendMessage(Text.literal("§7[CustomBlocks] Nothing to undo."));
            return 1;
        }
        UndoManager.UndoEntry entry = UndoManager.popUndo(getPlayerUuid(src));
        if (entry == null) { src.sendMessage(Text.literal("§7[CustomBlocks] Nothing to undo.")); return 1; }

        MinecraftServer server = src.getServer();

        // ── Undo a creation → delete the block ──────────────────────────────
        if (entry.previousState() == null) {
            SlotData d = SlotManager.getById(entry.customId());
            if (d == null) {
                src.sendError(Text.literal("§c[CustomBlocks] Cannot undo create — '" + entry.customId() + "' already gone."));
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
            src.sendMessage(Text.literal("§a[CustomBlocks] Undid create of §f" + entry.customId()
                + "§a. §7(" + UndoManager.undoSize(getPlayerUuid(src)) + " undo left)"));
            if (UndoManager.undoSize(getPlayerUuid(src)) > 0)
                src.sendMessage(Text.literal("§8  → Next undo: §7\"" + UndoManager.peekUndoDescription(getPlayerUuid(src)) + "\""));
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
            src.sendError(Text.literal("§c[CustomBlocks] Cannot undo — slot for '" + entry.customId() + "' is now occupied by another block."));
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
        src.sendMessage(Text.literal("§a[CustomBlocks] Undid \"" + entry.description() + "\" on §f"
            + entry.customId() + "§a. §7(" + UndoManager.undoSize(getPlayerUuid(src)) + " undo left, " + UndoManager.redoSize(getPlayerUuid(src)) + " redo)"));
        if (UndoManager.undoSize(getPlayerUuid(src)) > 0)
            src.sendMessage(Text.literal("§8  → Next undo: §7\"" + UndoManager.peekUndoDescription(getPlayerUuid(src)) + "\""));
        return 1;
    }

    /** Redo the last undone action. */
    private static int cmdRedo(ServerCommandSource src) {
        if (UndoManager.redoSize(getPlayerUuid(src)) == 0) {
            src.sendMessage(Text.literal("§7[CustomBlocks] Nothing to redo."));
            return 1;
        }
        UndoManager.UndoEntry entry = UndoManager.popRedo(getPlayerUuid(src));
        if (entry == null) { src.sendMessage(Text.literal("§7[CustomBlocks] Nothing to redo.")); return 1; }

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
            src.sendMessage(Text.literal("§a[CustomBlocks] Redid delete of §f" + entry.customId()
                + "§a. §7(" + UndoManager.redoSize(getPlayerUuid(src)) + " redo left)"));
            if (UndoManager.redoSize(getPlayerUuid(src)) > 0)
                src.sendMessage(Text.literal("§8  → Next redo: §7\"" + UndoManager.peekRedoDescription(getPlayerUuid(src)) + "\""));
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
            src.sendError(Text.literal("§c[CustomBlocks] Cannot redo — slot conflict for '" + entry.customId() + "'."));
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
        src.sendMessage(Text.literal("§a[CustomBlocks] Redid \"" + entry.description() + "\" on §f"
            + entry.customId() + "§a. §7(" + UndoManager.redoSize(getPlayerUuid(src)) + " redo left, " + UndoManager.undoSize(getPlayerUuid(src)) + " undo)"));
        if (UndoManager.redoSize(getPlayerUuid(src)) > 0)
            src.sendMessage(Text.literal("§8  → Next redo: §7\"" + UndoManager.peekRedoDescription(getPlayerUuid(src)) + "\""));
        return 1;
    }

    private static SlotData snapshotForCmd(SlotData d) {
        java.util.Map<String, byte[]> facesCopy = new java.util.concurrent.ConcurrentHashMap<>();
        d.faceTextures.forEach((k, v) -> facesCopy.put(k, v.clone()));
        return new SlotData(d.index, d.customId, d.displayName,
                d.texture != null ? d.texture.clone() : null,
                d.lightLevel, d.hardness, d.soundType, facesCopy, d.animMeta,
                d.shapeBoxes != null ? new java.util.ArrayList<>(d.shapeBoxes) : null, d.noCollision);
    }


    /** Resize the existing stored texture (and all face overrides) of a block. */
    private static int cmdResize(ServerCommandSource src, String id, int size) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        SlotData d = SlotManager.getById(id);
        if (d == null) { src.sendError(notFound(id)); return 0; }
        if (d.texture == null && d.faceTextures.isEmpty()) {
            src.sendError(Text.literal("§c'" + id + "' has no texture to resize.")); return 0;
        }
        UndoManager.pushUndoMutation(id, SlotManager.getById(id), "resize " + size, getPlayerUuid(src));
        src.sendMessage(Text.literal("§e[CustomBlocks] Resizing '" + id + "' to " + size + "px..."));
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
                    src.sendMessage(Text.literal("§a[CustomBlocks] '" + id + "' resized to " + size + "px."));
                });
            } catch (Exception e) {
                server.execute(() -> src.sendError(Text.literal("§c[CustomBlocks] Resize failed: " + e.getMessage())));
            }
        });
        return 1;
    }


    private static int cmdImportFolder(ServerCommandSource src) {
        File importDir = new File("config/customblocks/import");
        if (!importDir.exists()) {
            importDir.mkdirs();
            src.sendMessage(Text.literal("§e[CustomBlocks] Created: §fconfig/customblocks/import/"));
            src.sendMessage(Text.literal("§7Drop images there (PNG, JPG, GIF, BMP, WEBP) then run again."));
            return 1;
        }

        File[] images = importDir.listFiles((dir, name) -> {
            String l = name.toLowerCase();
            return l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg")
                || l.endsWith(".gif") || l.endsWith(".bmp") || l.endsWith(".webp")
                || l.endsWith(".tiff") || l.endsWith(".tif");
        });
        if (images == null || images.length == 0) {
            src.sendMessage(Text.literal("§c[CustomBlocks] No supported images found in import folder."));
            src.sendMessage(Text.literal("§7Supported: PNG, JPG, GIF, BMP, WEBP, TIFF"));
            return 0;
        }
        java.util.Arrays.sort(images, java.util.Comparator.comparing(File::getName));
        int free = SlotManager.freeSlots();
        if (free == 0) { src.sendError(Text.literal("§cAll slots full!")); return 0; }
        src.sendMessage(Text.literal("§e[CustomBlocks] Found " + images.length + " image(s), " + free + " slots free. Importing..."));
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
                    if (ImageProcessor.isAnimatedGif(raw)) {
                        ImageProcessor.GifResult gif = ImageProcessor.processGif(raw);
                        if (gif != null) { bytes = gif.stripPng(); animMeta = gif.mcmeta(); }
                        else { bytes = ImageProcessor.toPng(raw); bytes = ImageProcessor.padToSquare(bytes); bytes = ImageProcessor.replaceBackground(bytes); }
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
                StringBuilder msg = new StringBuilder("§a[CustomBlocks] Done! §f" + created + " created");
                if (!skipped.isEmpty()) msg.append("§7, ").append(skipped.size()).append(" skipped (already exist)");
                if (!failed.isEmpty())  msg.append("§c, ").append(failed.size()).append(" failed");
                src.sendMessage(Text.literal(msg.toString()));
                src.sendMessage(Text.literal("§7Slots: " + SlotManager.usedSlots() + " / " + CustomBlocksConfig.maxSlots));
                if (!createdIds.isEmpty())
                    src.sendMessage(Text.literal("§7Created blocks: " + String.join("§7, ", createdIds)));
                if (!skipped.isEmpty())
                    src.sendMessage(Text.literal("§7Skipped (already exist): §e" + String.join("§7, §e", skipped)));
                if (!failed.isEmpty())
                    src.sendMessage(Text.literal("§cFailed: " + String.join(", ", failed)));
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
            src.sendMessage(Text.literal("§a[CustomBlocks] Exported " + SlotManager.usedSlots() + " blocks → config/customblocks/export.json"));
        } catch (Exception e) { src.sendError(Text.literal("§cExport failed: " + e.getMessage())); }
        return 1;
    }

    private static int cmdList(ServerCommandSource src) {
        final String D = "§8§m                                              §r";
        int used = SlotManager.usedSlots(), free = SlotManager.freeSlots();
        src.sendMessage(Text.literal(" "));
        src.sendMessage(Text.literal("  §6§l✦ CustomBlocks §r§8│ §7" + used + " blocks  §8│  §7" + free + " free  §8│  §7" + CustomBlocksConfig.maxSlots + " total"));
        src.sendMessage(Text.literal(D));
        if (used == 0) {
            src.sendMessage(Text.literal("  §7No blocks yet. Use §b/cb create§7 to add one."));
            src.sendMessage(Text.literal(D));
            src.sendMessage(Text.literal(" "));
            return 1;
        }

        try {
            ServerPlayerEntity p = src.getPlayerOrThrow();
            p.sendMessage(Text.literal("  §a[CustomBlocks] Click here to open the blocks GUI!").styled(s -> s.withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.RUN_COMMAND, "/cb listgui")).withFormatting(net.minecraft.util.Formatting.UNDERLINE)));
            p.sendMessage(Text.literal("  §7...or see the chat breakdown below:"));
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

    private static int cmdHelp(ServerCommandSource src) {
        final String D = "§8§m                                              §r";
        final String H = "§e§l";   // heading colour
        final String C = "§b";     // command colour
        final String G = "§7";     // grey description
        final String A = "§f";     // arg colour

        src.sendMessage(Text.literal(" "));
        src.sendMessage(Text.literal("  §6§l✦ CustomBlocks  §r§8│ §7/cb or /customblock  §8│ §71.0"));
        src.sendMessage(Text.literal(D));

        src.sendMessage(Text.literal(H + "  Blocks"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "create " + A + "<id> <name> [size] <url>  " + G + "— new block (size 16-256, default 128)"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "retexture " + A + "<id> [size] <url>       " + G + "— swap texture"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "resize " + A + "<id> <16-256>             " + G + "— rescale stored texture"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "rename " + A + "<id> <name>               " + G + "— rename a block"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "dupe " + A + "<id> <newId> [name]         " + G + "— copy a block + all settings"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "delete " + A + "<id>                      " + G + "— permanently remove"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "give " + A + "<id> [amount] [player]      " + G + "— add to inventory"));
        src.sendMessage(Text.literal(D));

        src.sendMessage(Text.literal(H + "  Per-Face Textures"));
        src.sendMessage(Text.literal(G + "  §6Rainbow Rectangle §7— right-click any face of a block to paint it"));
        src.sendMessage(Text.literal(G + "  §8Shift+click §7= 256px quality  |  Normal click §7= 128px"));
        src.sendMessage(Text.literal(G + "  §8Creates a NEW variant block — original stays unchanged"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "editor " + A + "<id>                     " + G + "— open face editor GUI for a block"));
        src.sendMessage(Text.literal(D));

        src.sendMessage(Text.literal(H + "  Properties"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "setglow " + A + "<id> §3<0-15>            " + G + "— light emission  §8(0=off, 15=max like beacon)"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "sethardness " + A + "<id> §3<-1 to 50>    " + G + "— break speed  §8(-1=unbreakable, 0=instant)"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "setsound " + A + "<id> §3<sound>          " + G + "— §8stone wood metal glass grass sand wool"));
        src.sendMessage(Text.literal(G + "  " + G + "§8                              gravel snow dirt coral bamboo nether_brick ice honey bone slime"));
        src.sendMessage(Text.literal(D));

        src.sendMessage(Text.literal(H + "  Tools"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "rectangle                     " + G + "— §6Rainbow Rectangle §8face-paint wand"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "square " + A + "§3<black|yellow|green>    " + G + "— color-swap tool"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "triangle " + A + "§3<black|yellow|green>  " + G + "— color triangle tool"));
        src.sendMessage(Text.literal(D));

        src.sendMessage(Text.literal(H + "  Shapes"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "setshape " + A + "<id> <preset|coords>    " + G + "— set shape  §8(full slab thin carpet pillar small micro pane trapdoor fence stairs cross)"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "addshape " + A + "<id> <x1,y1,z1,x2,y2,z2>" + G + "— add a box to the shape"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "removeshape " + A + "<id> <index>          " + G + "— remove a box by index (0-based)"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "clearshape " + A + "<id>                  " + G + "— reset to full cube"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "setcollision " + A + "<id> <on|off>        " + G + "— toggle walkthrough"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "saveshape " + A + "<name> <id>            " + G + "— save shape as template"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "loadshape " + A + "<id> <name>            " + G + "— apply saved template"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "shapeeditor " + A + "<id>                 " + G + "— open shape editor GUI"));
        src.sendMessage(Text.literal(D));

        src.sendMessage(Text.literal(H + "  Utilities"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "undo                          " + G + "— undo last change  §8(up to 20 steps, shows next)"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "redo                          " + G + "— redo the last undone change"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "settabicon " + A + "<url>               " + G + "— set the creative tab icon"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "importfolder                  " + G + "— bulk-import from config/customblocks/import/"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "export                        " + G + "— export block list to JSON"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "list                          " + G + "— show all blocks"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "gui                           " + G + "— open chest GUI"));
        src.sendMessage(Text.literal(G + "  /cb " + C + "reload                        " + G + "— reload all blocks & sync to all players"));
        src.sendMessage(Text.literal(D));
        src.sendMessage(Text.literal("  §8Press §7B §8to open the overlay HUD · No restart needed!"));
        src.sendMessage(Text.literal(" "));
        return 1;
    }

    // ── Color items ───────────────────────────────────────────────────────────



    private static int cmdGiveSquareInternal(ServerCommandSource src, String color) {
        String c = color.toLowerCase().trim();
        if (!c.equals("black") && !c.equals("yellow") && !c.equals("green")) {
            src.sendError(Text.literal("§c'" + color + "' is not a valid color. Choose: §fblack §7| §fyellow §7| §fgreen")); return 0;
        }
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, c + "_square");
        net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
        if (item == null || item == net.minecraft.item.Items.AIR) { src.sendError(Text.literal("§cSquare item not found.")); return 0; }
        try {
            src.getPlayerOrThrow().getInventory().insertStack(new ItemStack(item, 1));
            src.sendMessage(Text.literal("§a[CustomBlocks] Given " + Character.toUpperCase(c.charAt(0)) + c.substring(1) + " Square!"));
        } catch (Exception ex) { ex.printStackTrace(); src.sendError(Text.literal("§cError: " + ex.getMessage())); return 0; }
        return 1;
    }

    private static int cmdGiveTriangleInternal(ServerCommandSource src, String color) {
        String c = color.toLowerCase().trim();
        if (!c.equals("black") && !c.equals("yellow") && !c.equals("green")) {
            src.sendError(Text.literal("§c'" + color + "' is not a valid color. Choose: §fblack §7| §fyellow §7| §fgreen")); return 0;
        }
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, c + "_triangle");
        net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
        if (item == null || item == net.minecraft.item.Items.AIR) { src.sendError(Text.literal("§cTriangle not found.")); return 0; }
        try {
            src.getPlayerOrThrow().getInventory().insertStack(new ItemStack(item, 1));
            src.sendMessage(Text.literal("§a[CustomBlocks] Given " + Character.toUpperCase(c.charAt(0)) + c.substring(1) + " Triangle!"));
        } catch (Exception ex) { ex.printStackTrace(); src.sendError(Text.literal("§cError: " + ex.getMessage())); return 0; }
        return 1;
    }

    private static int cmdGiveRectangleInternal(ServerCommandSource src) {
        net.minecraft.util.Identifier rectId = net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, "rainbow_rectangle");
        net.minecraft.item.Item rectItem = net.minecraft.registry.Registries.ITEM.get(rectId);
        if (rectItem == null || rectItem == net.minecraft.item.Items.AIR) {
            src.sendError(Text.literal("§cRainbow Rectangle not found.")); return 0;
        }
        try {
            src.getPlayerOrThrow().getInventory().insertStack(new ItemStack(rectItem, 1));
            src.sendMessage(Text.literal("§6[CustomBlocks] §eGiven §6Rainbow Rectangle§e! §7Right-click any block face and paste an image URL."));
        } catch (Exception ex) { ex.printStackTrace(); src.sendError(Text.literal("§cError: " + ex.getMessage())); return 0; }
        return 1;
    }

    private static int cmdReload(ServerCommandSource src) {
        SlotManager.loadAll();
        CustomBlocksMod.broadcastFullSync(src.getServer());
        src.sendMessage(Text.literal("§a[CustomBlocks] Reloaded all blocks and synced to all players."));
        return 1;
    }

    private static int cmdEditorPicker(ServerCommandSource src) {
        try {
            ServerPlayerEntity player = src.getPlayerOrThrow();
            GuiManager.openEditorPicker(player);
        } catch (Exception ex) { ex.printStackTrace(); src.sendError(Text.literal("§cError: " + ex.getMessage())); }
        return 1;
    }

    private static int cmdGui(ServerCommandSource src) {
        try {
            ServerPlayerEntity player = src.getPlayerOrThrow();
            GuiManager.openMain(player, 0);
        } catch (Exception ex) { ex.printStackTrace(); src.sendError(Text.literal("§cError: " + ex.getMessage())); }
        return 1;
    }

    private static int cmdEditor(ServerCommandSource src, String id) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        try {
            ServerPlayerEntity player = src.getPlayerOrThrow();
            GuiManager.openEditor(player, id, 0, true);  // fromCommand = true → 1-press ESC exits
        } catch (Exception ex) { ex.printStackTrace(); src.sendError(Text.literal("§cError: " + ex.getMessage())); }
        return 1;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int usage(ServerCommandSource src, String cmd) {
        src.sendError(Text.literal(switch (cmd) {
            case "bulkdelete"    -> "§cUsage: /cb bulkdelete <id1> <id2> ...  — delete multiple blocks at once";
            case "delete"       -> "§cUsage: /cb delete <id>";
            case "rename"       -> "§cUsage: /cb rename <id> <newname>";
            case "reid"         -> "§cUsage: /cb reid <id> <newid>  — changes the block's ID (lowercase, a-z 0-9 _ -)";
            case "retexture"    -> "§cUsage: /cb retexture <id> <url>";
            case "give"         -> "§cUsage: /cb give <id> [amount 1-64] [player]";
            case "setglow"      -> "§cUsage: /cb setglow <id> <0-15>";
            case "sethardness"  -> "§cUsage: /cb sethardness <id> <-1 to 50>  (-1=unbreakable)";
            case "setsound"     -> "§cUsage: /cb setsound <id> <sound>  — stone|wood|grass|metal|glass|sand|wool|gravel|snow|dirt|coral|bamboo|nether_brick|ice|honey|bone|slime";
            case "settabicon"   -> "§cUsage: /cb settabicon <url>";
            case "clearface"    -> "§cUsage: /cb clearface <id> <top|bottom|north|south|east|west>";
            case "givesquare"   -> "§cUsage: /cb givesquare <black|yellow|green>";
            case "givetriangle" -> "§cUsage: /cb givetriangle <black|yellow|green>";
            case "clearallfaces"-> "§cUsage: /cb clearallfaces <id>";
            case "resize"       -> "§cUsage: /cb resize <id> <16-256>  — rescale stored texture";
            case "editor" -> "§cUsage: /cb editor [id]  — omit ID to pick from a list";
            case "setshape"     -> "§cUsage: /cb setshape <id> <preset|x1,y1,z1,x2,y2,z2>  — presets: full slab thin carpet pillar small micro pane trapdoor fence stairs cross";
            case "addshape"     -> "§cUsage: /cb addshape <id> <x1,y1,z1,x2,y2,z2>";
            case "removeshape"  -> "§cUsage: /cb removeshape <id> <boxIndex 0-15>";
            case "clearshape"   -> "§cUsage: /cb clearshape <id>";
            case "setcollision" -> "§cUsage: /cb setcollision <id> <on|off>";
            case "saveshape"    -> "§cUsage: /cb saveshape <templateName> <id>";
            case "loadshape"    -> "§cUsage: /cb loadshape <id> <templateName>";
            case "shapeeditor"  -> "§cUsage: /cb shapeeditor <id>";
            case "square"       -> "§cUsage: /cb square <black|yellow|green>";
            case "triangle"     -> "§cUsage: /cb triangle <black|yellow|green>";
            default -> "§cUsage: /cb help";
        }));
        return 0;
    }

    private static Text notFound(String id) {
        return Text.literal("§c'" + id + "' not found. Use /cb list to see all blocks.");
    }

    private static String sanitize(String id) {
        return id.toLowerCase().replaceAll("[^a-z0-9_]", "_");
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
}