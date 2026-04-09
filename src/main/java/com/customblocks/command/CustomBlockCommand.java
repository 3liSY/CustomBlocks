package com.customblocks.command;

import com.customblocks.CustomBlocksMod;
import com.customblocks.gui.GuiManager;
import com.customblocks.ImageProcessor;
import com.customblocks.SlotManager;
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
import java.util.Collection;

public class CustomBlockCommand {

    private static final SuggestionProvider<ServerCommandSource> BLOCK_SUGGESTIONS =
            (ctx, builder) -> { for (String id : SlotManager.allCustomIds()) builder.suggest(id); return builder.buildFuture(); };

    private static final SuggestionProvider<ServerCommandSource> SOUND_SUGGESTIONS =
            (ctx, builder) -> { for (String s : new String[]{"stone","wood","grass","metal","glass","sand","wool"}) builder.suggest(s); return builder.buildFuture(); };

    private static final SuggestionProvider<ServerCommandSource> FACE_SUGGESTIONS =
            (ctx, builder) -> { for (String f : SlotManager.FACE_KEYS) builder.suggest(f); return builder.buildFuture(); };

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, reg, env) -> {
            var tree = CommandManager.literal("customblock")
                .requires(src -> src.hasPermissionLevel(2))

                // ── create / createurl ──────────────────────────────────────
                .then(CommandManager.literal("create")
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            // /cb create <id> <name> <size> <url>  — size first so greedy URL still works
                            .then(CommandManager.argument("size", IntegerArgumentType.integer(16, 256))
                                .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                    .executes(ctx -> cmdCreate(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id"),
                                        StringArgumentType.getString(ctx, "name").replace("_", " "),
                                        StringArgumentType.getString(ctx, "url").trim(),
                                        IntegerArgumentType.getInteger(ctx, "size")))))
                            // /cb create <id> <name> <url>  — default 128
                            .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(ctx -> cmdCreate(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "id"),
                                    StringArgumentType.getString(ctx, "name").replace("_", " "),
                                    StringArgumentType.getString(ctx, "url").trim(),
                                    ImageProcessor.DEFAULT_SIZE))))))

                .then(CommandManager.literal("createurl")
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .then(CommandManager.argument("size", IntegerArgumentType.integer(16, 256))
                                .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                    .executes(ctx -> cmdCreate(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id"),
                                        StringArgumentType.getString(ctx, "name").replace("_", " "),
                                        StringArgumentType.getString(ctx, "url").trim(),
                                        IntegerArgumentType.getInteger(ctx, "size")))))
                            .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(ctx -> cmdCreate(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "id"),
                                    StringArgumentType.getString(ctx, "name").replace("_", " "),
                                    StringArgumentType.getString(ctx, "url").trim(),
                                    ImageProcessor.DEFAULT_SIZE))))))

                // ── delete ──────────────────────────────────────────────────
                .then(CommandManager.literal("delete")
                    .executes(ctx -> usage(ctx.getSource(), "delete"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdDelete(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))

                // ── rename ──────────────────────────────────────────────────
                .then(CommandManager.literal("rename")
                    .executes(ctx -> usage(ctx.getSource(), "rename"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("newname", StringArgumentType.greedyString())
                            .executes(ctx -> cmdRename(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "newname").replace("_", " "))))))

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

                // ── settabicon ──────────────────────────────────────────────
                .then(CommandManager.literal("settabicon")
                    .executes(ctx -> usage(ctx.getSource(), "settabicon"))
                    .then(CommandManager.argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> cmdSetTabIcon(ctx.getSource(),
                            StringArgumentType.getString(ctx, "url").trim()))))

                // ── per-face commands ───────────────────────────────────────
                .then(CommandManager.literal("settopface")
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("size", IntegerArgumentType.integer(16, 256))
                            .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(ctx -> cmdSetFace(ctx.getSource(), StringArgumentType.getString(ctx, "id"), "top", StringArgumentType.getString(ctx, "url").trim(), IntegerArgumentType.getInteger(ctx, "size")))))
                        .then(CommandManager.argument("url", StringArgumentType.greedyString())
                            .executes(ctx -> cmdSetFace(ctx.getSource(), StringArgumentType.getString(ctx, "id"), "top", StringArgumentType.getString(ctx, "url").trim(), ImageProcessor.DEFAULT_SIZE)))))

                .then(CommandManager.literal("setbottomface")
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("size", IntegerArgumentType.integer(16, 256))
                            .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(ctx -> cmdSetFace(ctx.getSource(), StringArgumentType.getString(ctx, "id"), "bottom", StringArgumentType.getString(ctx, "url").trim(), IntegerArgumentType.getInteger(ctx, "size")))))
                        .then(CommandManager.argument("url", StringArgumentType.greedyString())
                            .executes(ctx -> cmdSetFace(ctx.getSource(), StringArgumentType.getString(ctx, "id"), "bottom", StringArgumentType.getString(ctx, "url").trim(), ImageProcessor.DEFAULT_SIZE)))))

                .then(CommandManager.literal("setnorthface")
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("size", IntegerArgumentType.integer(16, 256))
                            .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(ctx -> cmdSetFace(ctx.getSource(), StringArgumentType.getString(ctx, "id"), "north", StringArgumentType.getString(ctx, "url").trim(), IntegerArgumentType.getInteger(ctx, "size")))))
                        .then(CommandManager.argument("url", StringArgumentType.greedyString())
                            .executes(ctx -> cmdSetFace(ctx.getSource(), StringArgumentType.getString(ctx, "id"), "north", StringArgumentType.getString(ctx, "url").trim(), ImageProcessor.DEFAULT_SIZE)))))

                .then(CommandManager.literal("setsouthface")
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("size", IntegerArgumentType.integer(16, 256))
                            .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(ctx -> cmdSetFace(ctx.getSource(), StringArgumentType.getString(ctx, "id"), "south", StringArgumentType.getString(ctx, "url").trim(), IntegerArgumentType.getInteger(ctx, "size")))))
                        .then(CommandManager.argument("url", StringArgumentType.greedyString())
                            .executes(ctx -> cmdSetFace(ctx.getSource(), StringArgumentType.getString(ctx, "id"), "south", StringArgumentType.getString(ctx, "url").trim(), ImageProcessor.DEFAULT_SIZE)))))

                .then(CommandManager.literal("seteastface")
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("size", IntegerArgumentType.integer(16, 256))
                            .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(ctx -> cmdSetFace(ctx.getSource(), StringArgumentType.getString(ctx, "id"), "east", StringArgumentType.getString(ctx, "url").trim(), IntegerArgumentType.getInteger(ctx, "size")))))
                        .then(CommandManager.argument("url", StringArgumentType.greedyString())
                            .executes(ctx -> cmdSetFace(ctx.getSource(), StringArgumentType.getString(ctx, "id"), "east", StringArgumentType.getString(ctx, "url").trim(), ImageProcessor.DEFAULT_SIZE)))))

                .then(CommandManager.literal("setwestface")
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("size", IntegerArgumentType.integer(16, 256))
                            .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(ctx -> cmdSetFace(ctx.getSource(), StringArgumentType.getString(ctx, "id"), "west", StringArgumentType.getString(ctx, "url").trim(), IntegerArgumentType.getInteger(ctx, "size")))))
                        .then(CommandManager.argument("url", StringArgumentType.greedyString())
                            .executes(ctx -> cmdSetFace(ctx.getSource(), StringArgumentType.getString(ctx, "id"), "west", StringArgumentType.getString(ctx, "url").trim(), ImageProcessor.DEFAULT_SIZE)))))

                // ── resize ──────────────────────────────────────────────────
                .then(CommandManager.literal("resize")
                    .executes(ctx -> usage(ctx.getSource(), "resize"))
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("size", IntegerArgumentType.integer(16, 256))
                            .executes(ctx -> cmdResize(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                IntegerArgumentType.getInteger(ctx, "size"))))))

                .then(CommandManager.literal("clearface")
                    .executes(ctx -> usage(ctx.getSource(), "clearface"))
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("face", StringArgumentType.word()).suggests(FACE_SUGGESTIONS)
                            .executes(ctx -> cmdClearFace(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "face"))))))

                .then(CommandManager.literal("clearallfaces")
                    .executes(ctx -> usage(ctx.getSource(), "clearallfaces"))
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdClearAllFaces(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))

                // ── undo ────────────────────────────────────────────────────
                .then(CommandManager.literal("undo")
                    .executes(ctx -> cmdUndo(ctx.getSource())))

                // ── tool items ──────────────────────────────────────────────
                .then(CommandManager.literal("givesquare")
                    .executes(ctx -> usage(ctx.getSource(), "givesquare"))
                    .then(CommandManager.argument("color", StringArgumentType.word())
                        .suggests((ctx, builder) -> { builder.suggest("black"); builder.suggest("yellow"); builder.suggest("green"); return builder.buildFuture(); })
                        .executes(ctx -> cmdGiveSquare(ctx.getSource(), StringArgumentType.getString(ctx, "color")))))

                .then(CommandManager.literal("givetriangle")
                    .executes(ctx -> usage(ctx.getSource(), "givetriangle"))
                    .then(CommandManager.argument("color", StringArgumentType.word())
                        .suggests((ctx, builder) -> { builder.suggest("black"); builder.suggest("yellow"); builder.suggest("green"); return builder.buildFuture(); })
                        .executes(ctx -> cmdGiveTriangle(ctx.getSource(), StringArgumentType.getString(ctx, "color")))))

                .then(CommandManager.literal("giverectangle")
                    .executes(ctx -> cmdGiveRectangle(ctx.getSource())))

                .then(CommandManager.literal("colorchanger")
                    .executes(ctx -> cmdColorChangerAll(ctx.getSource()))
                    .then(CommandManager.argument("color", StringArgumentType.word())
                        .suggests((ctx, builder) -> { builder.suggest("black"); builder.suggest("yellow"); builder.suggest("green"); return builder.buildFuture(); })
                        .executes(ctx -> cmdGiveSquare(ctx.getSource(), StringArgumentType.getString(ctx, "color")))))

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
                                    StringArgumentType.getString(ctx, "newname").replace("_", " ")))))))

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
                                    StringArgumentType.getString(ctx, "newname").replace("_", " ")))))))

                // ── data commands ───────────────────────────────────────────
                .then(CommandManager.literal("export")
                    .executes(ctx -> cmdExport(ctx.getSource())))

                .then(CommandManager.literal("importfolder")
                    .executes(ctx -> cmdImportFolder(ctx.getSource())))

                .then(CommandManager.literal("list")
                    .executes(ctx -> cmdList(ctx.getSource())))

                .then(CommandManager.literal("help")
                    .executes(ctx -> cmdHelp(ctx.getSource())))

                // ── gui ──────────────────────────────────────────────────────
                .then(CommandManager.literal("gui")
                    .executes(ctx -> cmdGui(ctx.getSource())));

            dispatcher.register(tree);
            dispatcher.register(CommandManager.literal("cb")
                .requires(src -> src.hasPermissionLevel(2))
                .redirect(dispatcher.getRoot().getChild("customblock")));
        });
    }

    // ── Implementations ───────────────────────────────────────────────────────

    private static int cmdCreate(ServerCommandSource src, String rawId, String name, String url, int size) {
        String id = sanitize(rawId);
        if (id.isEmpty()) { src.sendError(Text.literal("§cInvalid ID.")); return 0; }
        if (SlotManager.hasId(id)) { src.sendError(Text.literal("§c'" + id + "' already exists.")); return 0; }
        if (SlotManager.freeSlots() == 0) { src.sendError(Text.literal("§cAll " + SlotManager.MAX_SLOTS + " slots are full!")); return 0; }
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
                    SlotManager.SlotData d = SlotManager.assign(id, name, finalBytes);
                    if (d == null) { src.sendError(Text.literal("§cNo free slots!")); return; }
                    if (finalAnim != null) SlotManager.setAnimMeta(id, finalAnim);
                    SlotManager.pushUndoCreate(id);
                    SlotManager.saveAll();
                    CustomBlocksMod.broadcastUpdate(server,
                        new SlotUpdatePayload("add", d.index, id, name, finalBytes,
                                d.lightLevel, d.hardness, d.soundType));
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
        if (SlotManager.freeSlots() == 0) { src.sendError(Text.literal("§cAll " + SlotManager.MAX_SLOTS + " slots are full!")); return 0; }

        SlotManager.SlotData s = SlotManager.getById(sourceId);
        String finalName = (newName != null && !newName.isBlank()) ? newName : s.displayName + " (Copy)";

        byte[] texCopy = s.texture != null ? s.texture.clone() : null;
        SlotManager.SlotData d = SlotManager.assign(newId, finalName, texCopy);
        if (d == null) { src.sendError(Text.literal("§cNo free slots!")); return 0; }

        // Copy all properties and per-face textures
        SlotManager.setLightLevel(newId, s.lightLevel);
        SlotManager.setHardness(newId, s.hardness);
        SlotManager.setSoundType(newId, s.soundType);
        if (s.animMeta != null) SlotManager.setAnimMeta(newId, s.animMeta);
        for (var e : s.faceTextures.entrySet())
            SlotManager.setFaceTexture(newId, e.getKey(), e.getValue().clone());

        SlotManager.pushUndoCreate(newId);
        SlotManager.saveAll();
        d = SlotManager.getById(newId);
        CustomBlocksMod.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("add", d.index, newId, finalName, texCopy,
                    d.lightLevel, d.hardness, d.soundType));
        src.sendMessage(Text.literal("§a[CustomBlocks] Duplicated '§f" + sourceId + "§a' → '§f" + newId + "§a' §7(slot " + d.index + ")"));
        return 1;
    }

    private static int cmdDelete(ServerCommandSource src, String id) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        SlotManager.SlotData d = SlotManager.getById(id);
        SlotManager.pushUndoDelete(id);
        SlotManager.remove(id);
        SlotManager.saveAll();
        CustomBlocksMod.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("remove", d.index, id, null, null, 0, 0, "stone"));
        src.sendMessage(Text.literal("§a[CustomBlocks] '" + id + "' deleted."));
        return 1;
    }

    private static int cmdRename(ServerCommandSource src, String id, String newName) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        SlotManager.pushUndo(id, "rename");
        SlotManager.SlotData d = SlotManager.getById(id);
        SlotManager.rename(id, newName);
        SlotManager.saveAll();
        CustomBlocksMod.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("rename", d.index, id, newName, null, 0, 0, "stone"));
        src.sendMessage(Text.literal("§a[CustomBlocks] Renamed to '" + newName + "'."));
        return 1;
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
                    SlotManager.pushUndo(id, "retexture");
                    SlotManager.SlotData d = SlotManager.getById(id);
                    if (d == null) { src.sendError(notFound(id)); return; }
                    SlotManager.updateTexture(id, fb);
                    if (fa != null) SlotManager.setAnimMeta(id, fa);
                    SlotManager.saveAll();
                    CustomBlocksMod.broadcastUpdate(server,
                        new SlotUpdatePayload("retexture", d.index, id, null, fb,
                                d.lightLevel, d.hardness, d.soundType));
                    src.sendMessage(Text.literal("§a[CustomBlocks] Texture updated for '" + id + "'."));
                });
            } catch (Exception e) {
                server.execute(() -> src.sendError(Text.literal("§c[CustomBlocks] Failed: " + e.getMessage())));
            }
        });
        return 1;
    }

    private static int cmdGive(ServerCommandSource src, String id, int amount, Collection<ServerPlayerEntity> targets) {
        SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null) { src.sendError(notFound(id)); return 0; }
        SlotBlock.SlotItem item = CustomBlocksMod.SLOT_ITEMS[d.index];
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
        SlotManager.pushUndo(id, "setglow " + level);
        SlotManager.SlotData d = SlotManager.getById(id);
        SlotManager.setLightLevel(id, level);
        SlotManager.saveAll();
        CustomBlocksMod.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("setprop", d.index, id, null, null, level, d.hardness, d.soundType));
        src.sendMessage(Text.literal("§a[CustomBlocks] '" + id + "' glow set to " + level + "."));
        return 1;
    }

    private static int cmdSetHardness(ServerCommandSource src, String id, float val) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        SlotManager.pushUndo(id, "sethardness");
        SlotManager.SlotData d = SlotManager.getById(id);
        SlotManager.setHardness(id, val);
        SlotManager.saveAll();
        String label = val < 0 ? "Unbreakable" : val == 0 ? "Instant break" : String.valueOf(val);
        CustomBlocksMod.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("setprop", d.index, id, null, null, d.lightLevel, val, d.soundType));
        src.sendMessage(Text.literal("§a[CustomBlocks] '" + id + "' hardness: " + label + "."));
        return 1;
    }

    private static int cmdSetSound(ServerCommandSource src, String id, String type) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        String[] valid = {"stone","wood","grass","metal","glass","sand","wool"};
        boolean ok = false;
        for (String v : valid) if (v.equals(type)) { ok = true; break; }
        if (!ok) { src.sendError(Text.literal("§cValid sounds: stone, wood, grass, metal, glass, sand, wool")); return 0; }
        SlotManager.pushUndo(id, "setsound");
        SlotManager.SlotData d = SlotManager.getById(id);
        SlotManager.setSoundType(id, type);
        SlotManager.saveAll();
        CustomBlocksMod.broadcastUpdate(src.getServer(),
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
                    SlotManager.SlotData d = SlotManager.getById("tab_icon");
                    if (d != null)
                        CustomBlocksMod.broadcastUpdate(server,
                            new SlotUpdatePayload("add", d.index, "tab_icon", "Tab Icon", bytes, 0, 1.5f, "stone"));
                    CustomBlocksMod.broadcastUpdate(server,
                        new SlotUpdatePayload("tabicon", -1, null, null, bytes, 0, 0, "stone"));
                    src.sendMessage(Text.literal("§a[CustomBlocks] Tab icon updated!"));
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
                    SlotManager.pushUndo(id, "setface " + face);
                    SlotManager.SlotData d = SlotManager.getById(id);
                    if (d == null) { src.sendError(Text.literal("§c[CustomBlocks] '" + id + "' was deleted.")); return; }
                    SlotManager.setFaceTexture(id, face, fb);
                    SlotManager.saveAll();
                    // Broadcast setface — clients apply it to ONLY this face
                    CustomBlocksMod.broadcastUpdate(server,
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
        if (!SlotManager.FACE_KEYS.contains(face)) { src.sendError(Text.literal("§cValid faces: top bottom north south east west")); return 0; }
        SlotManager.pushUndo(id, "clearface " + face);
        SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null) { src.sendError(notFound(id)); return 0; }
        SlotManager.clearFaceTexture(id, face);
        SlotManager.saveAll();
        // Broadcast clearface so clients revert that face to default
        CustomBlocksMod.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("clearface", d.index, id, null, null,
                    d.lightLevel, d.hardness, d.soundType, face));
        src.sendMessage(Text.literal("§a[CustomBlocks] " + face + " face cleared on '" + id + "'."));
        return 1;
    }

    private static int cmdClearAllFaces(ServerCommandSource src, String id) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        SlotManager.pushUndo(id, "clearallfaces");
        SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null) { src.sendError(notFound(id)); return 0; }
        SlotManager.clearAllFaces(id);
        SlotManager.saveAll();
        CustomBlocksMod.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("clearfaces", d.index, id, null, null,
                    d.lightLevel, d.hardness, d.soundType));
        src.sendMessage(Text.literal("§a[CustomBlocks] All face overrides cleared on '" + id + "'."));
        return 1;
    }

    /** Undo the last block modification (retexture, setface, setglow, delete, create, …). */
    private static int cmdUndo(ServerCommandSource src) {
        if (SlotManager.undoStackSize() == 0) {
            src.sendMessage(Text.literal("§7[CustomBlocks] Nothing to undo."));
            return 1;
        }
        SlotManager.UndoEntry entry = SlotManager.popUndo();
        if (entry == null) { src.sendMessage(Text.literal("§7[CustomBlocks] Nothing to undo.")); return 1; }

        MinecraftServer server = src.getServer();

        // ── Undo a creation → delete the block ──────────────────────────────
        if (entry.previousState() == null) {
            SlotManager.SlotData d = SlotManager.getById(entry.customId());
            if (d == null) {
                src.sendError(Text.literal("§c[CustomBlocks] Cannot undo create — '" + entry.customId() + "' already gone."));
                return 0;
            }
            int idx = d.index;
            SlotManager.remove(entry.customId());
            SlotManager.saveAll();
            CustomBlocksMod.broadcastUpdate(server,
                new SlotUpdatePayload("remove", idx, entry.customId(), null, null, 0, 0, "stone"));
            src.sendMessage(Text.literal("§a[CustomBlocks] Undid create of §f" + entry.customId()
                + "§a. §7(" + SlotManager.undoStackSize() + " left)"));
            return 1;
        }

        // ── Undo a mutation or a deletion ────────────────────────────────────
        SlotManager.SlotData prev = entry.previousState();
        boolean restored = SlotManager.restoreSnapshot(prev, entry.wasDeleted());
        if (!restored) {
            src.sendError(Text.literal("§c[CustomBlocks] Cannot undo — slot for '" + entry.customId() + "' is now occupied by another block."));
            return 0;
        }
        SlotManager.saveAll();

        SlotManager.SlotData d = SlotManager.getById(prev.customId);
        if (d != null) {
            if (entry.wasDeleted()) {
                // Block was re-added: send "add" then restore every face
                CustomBlocksMod.broadcastUpdate(server,
                    new SlotUpdatePayload("add", d.index, d.customId, d.displayName, d.texture,
                            d.lightLevel, d.hardness, d.soundType));
                for (var fe : d.faceTextures.entrySet())
                    CustomBlocksMod.broadcastUpdate(server,
                        new SlotUpdatePayload("setface", d.index, d.customId, null, fe.getValue(),
                                d.lightLevel, d.hardness, d.soundType, fe.getKey()));
            } else {
                // Normal restore: texture + wipe stale faces + re-send snapshot faces + props + name
                if (d.texture != null)
                    CustomBlocksMod.broadcastUpdate(server,
                        new SlotUpdatePayload("retexture", d.index, d.customId, null, d.texture,
                                d.lightLevel, d.hardness, d.soundType));
                // Clear all faces on clients first, then re-apply only what the snapshot had
                CustomBlocksMod.broadcastUpdate(server,
                    new SlotUpdatePayload("clearfaces", d.index, d.customId, null, null,
                            d.lightLevel, d.hardness, d.soundType));
                for (var fe : d.faceTextures.entrySet())
                    CustomBlocksMod.broadcastUpdate(server,
                        new SlotUpdatePayload("setface", d.index, d.customId, null, fe.getValue(),
                                d.lightLevel, d.hardness, d.soundType, fe.getKey()));
                CustomBlocksMod.broadcastUpdate(server,
                    new SlotUpdatePayload("setprop", d.index, d.customId, null, null,
                            d.lightLevel, d.hardness, d.soundType));
                // Always re-sync display name in case a rename was undone
                CustomBlocksMod.broadcastUpdate(server,
                    new SlotUpdatePayload("rename", d.index, d.customId, d.displayName, null, 0, 0, "stone"));
            }
        }
        src.sendMessage(Text.literal("§a[CustomBlocks] Undid \"" + entry.description() + "\" on §f"
            + entry.customId() + "§a. §7(" + SlotManager.undoStackSize() + " left)"));
        return 1;
    }

    /** Resize the existing stored texture (and all face overrides) of a block. */
    private static int cmdResize(ServerCommandSource src, String id, int size) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null) { src.sendError(notFound(id)); return 0; }
        if (d.texture == null && d.faceTextures.isEmpty()) {
            src.sendError(Text.literal("§c'" + id + "' has no texture to resize.")); return 0;
        }
        SlotManager.pushUndo(id, "resize " + size);
        src.sendMessage(Text.literal("§e[CustomBlocks] Resizing '" + id + "' to " + size + "px..."));
        MinecraftServer server = src.getServer();
        thread(() -> {
            try {
                byte[] newTex = d.texture != null ? ImageProcessor.resizeTo(d.texture, size) : null;
                java.util.Map<String, byte[]> newFaces = new java.util.concurrent.ConcurrentHashMap<>();
                for (var e : d.faceTextures.entrySet())
                    newFaces.put(e.getKey(), ImageProcessor.resizeTo(e.getValue(), size));

                server.execute(() -> {
                    SlotManager.SlotData cur = SlotManager.getById(id);
                    if (cur == null) { src.sendError(notFound(id)); return; }
                    if (newTex != null) SlotManager.updateTexture(id, newTex);
                    for (var e : newFaces.entrySet()) SlotManager.setFaceTexture(id, e.getKey(), e.getValue());
                    SlotManager.saveAll();
                    SlotManager.SlotData updated = SlotManager.getById(id);
                    if (updated != null) {
                        if (newTex != null)
                            CustomBlocksMod.broadcastUpdate(server,
                                new SlotUpdatePayload("retexture", updated.index, id, null, newTex,
                                        updated.lightLevel, updated.hardness, updated.soundType));
                        for (var e : newFaces.entrySet())
                            CustomBlocksMod.broadcastUpdate(server,
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
                for (int i = 0; i < toAdd.size(); i++) {
                    String id = toAdd.get(i)[0], name = toAdd.get(i)[1];
                    byte[] b = toBytes.get(i);
                    String anim = toAnims.get(i);
                    SlotManager.SlotData d = SlotManager.assign(id, name, b);
                    if (d == null) { failed.add(id + "(slot full)"); continue; }
                    if (anim != null) SlotManager.setAnimMeta(id, anim);
                    SlotManager.pushUndoCreate(id);
                    CustomBlocksMod.broadcastUpdate(server,
                        new SlotUpdatePayload("add", d.index, id, name, b, d.lightLevel, d.hardness, d.soundType));
                    created++;
                }
                if (created > 0) SlotManager.saveAll();
                StringBuilder msg = new StringBuilder("§a[CustomBlocks] Done! §f" + created + " created");
                if (!skipped.isEmpty()) msg.append("§7, ").append(skipped.size()).append(" skipped (already exist)");
                if (!failed.isEmpty())  msg.append("§c, ").append(failed.size()).append(" failed");
                src.sendMessage(Text.literal(msg.toString()));
                src.sendMessage(Text.literal("§7Slots: " + SlotManager.usedSlots() + " / " + SlotManager.MAX_SLOTS));
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
            for (SlotManager.SlotData d : SlotManager.allSlots()) {
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
        if (SlotManager.usedSlots() == 0) {
            src.sendMessage(Text.literal("§7[CustomBlocks] No blocks yet. " + SlotManager.freeSlots() + " slots free.")); return 1;
        }
        src.sendMessage(Text.literal("§e[CustomBlocks] §f" + SlotManager.usedSlots() + " block(s) §7| " + SlotManager.freeSlots() + " free:"));
        for (SlotManager.SlotData d : SlotManager.allSlots()) {
            String glow  = d.lightLevel > 0 ? " §6✦" + d.lightLevel : "";
            String hard  = d.hardness < 0 ? " §c∞" : "";
            String anim  = d.isAnimated() ? " §b⟳" : "";
            String faces = d.hasFaces() ? " §d[faces]" : "";
            src.sendMessage(Text.literal("  §f" + d.customId + " §7→ '" + d.displayName + "'" + glow + hard + anim + faces + " §8(#" + d.index + ")"));
        }
        return 1;
    }

    /** Clean /cb help — grouped, easy to scan. */
    private static int cmdHelp(ServerCommandSource src) {
        final String D = "§8──────────────────────────────────────────";
        src.sendMessage(Text.literal(" "));
        src.sendMessage(Text.literal("  §6§l✦ §r§6CustomBlocks  §8│ §7/cb §8or §7/customblock  §8│ §7v2.0"));
        src.sendMessage(Text.literal(D));

        src.sendMessage(Text.literal("§e  Blocks"));
        src.sendMessage(Text.literal("§7  /cb §fcreate §b<id> <n> [size] <url>     §8— new block  §7size=16-256, default 128"));
        src.sendMessage(Text.literal("§7  /cb §fdupe §b<id> <newId> [name]          §8— copy a block and all its settings"));
        src.sendMessage(Text.literal("§7  /cb §fretexture §b<id> [size] <url>       §8— swap the main texture"));
        src.sendMessage(Text.literal("§7  /cb §fresize §b<id> §3<16-256>            §8— rescale stored texture in-place"));
        src.sendMessage(Text.literal("§7  /cb §frename §b<id> <n>               §8— rename a block"));
        src.sendMessage(Text.literal("§7  /cb §fdelete §b<id>                   §8— permanently remove"));
        src.sendMessage(Text.literal("§7  /cb §fgive §b<id> [amount] [player]    §8— add to inventory"));
        src.sendMessage(Text.literal("§7  /cb §flist                            §8— show all blocks and slots used"));
        src.sendMessage(Text.literal(D));

        src.sendMessage(Text.literal("§e  Per-Face Textures"));
        src.sendMessage(Text.literal("§7  /cb §fset§3(top§7|§3bottom§7|§3north§7|§3south§7|§3east§7|§3west§7)§fface §b<id> <url>"));
        src.sendMessage(Text.literal("§7  /cb §fclearface §b<id> §3<face>         §8— revert one face to default"));
        src.sendMessage(Text.literal("§7  /cb §fclearallfaces §b<id>             §8— revert every face"));
        src.sendMessage(Text.literal(D));

        src.sendMessage(Text.literal("§e  Properties"));
        src.sendMessage(Text.literal("§7  /cb §fsetglow §b<id> §3<0-15>          §8— light emission  §7(0 = off, 15 = max)"));
        src.sendMessage(Text.literal("§7  /cb §fsethardness §b<id> §3<-1 to 50>  §8— break speed  §7(-1 unbreakable, 0 instant)"));
        src.sendMessage(Text.literal("§7  /cb §fsetsound §b<id> §3<type>         §8— §7stone|wood|metal|glass|grass|sand|wool"));
        src.sendMessage(Text.literal(D));

        src.sendMessage(Text.literal("§e  Tools"));
        src.sendMessage(Text.literal("§7  /cb §fgiverectangle                   §8— §6Rainbow Rectangle §8face-paint wand"));
        src.sendMessage(Text.literal("§7  /cb §fgivesquare §b<black|yellow|green> §8— color-swap square"));
        src.sendMessage(Text.literal("§7  /cb §fgivetriangle §b<color>           §8— color-variant triangle"));
        src.sendMessage(Text.literal("§7  /cb §fcolorchanger                    §8— give all 3 color squares at once"));
        src.sendMessage(Text.literal(D));

        src.sendMessage(Text.literal("§e  Utilities"));
        src.sendMessage(Text.literal("§7  /cb §fundo                            §8— undo last change"));
        src.sendMessage(Text.literal("§7  /cb §fsettabicon §b<url>               §8— set the creative tab icon"));
        src.sendMessage(Text.literal("§7  /cb §fimportfolder                    §8— bulk-import from config/customblocks/import/"));
        src.sendMessage(Text.literal("§7  /cb §fexport                          §8— export block list to JSON"));
        src.sendMessage(Text.literal("§7  /cb §fgui                             §8— §6open the chest GUI"));
        src.sendMessage(Text.literal(D));

        src.sendMessage(Text.literal("  §8Press §7B §8for the HUD overlay  ·  No restart needed!"));
        src.sendMessage(Text.literal(" "));
        return 1;
    }

    // ── Color items ───────────────────────────────────────────────────────────

    private static int cmdColorChangerAll(ServerCommandSource src) {
        int given = 0;
        for (String col : new String[]{"black","yellow","green"}) given += cmdGiveSquareSilent(src, col);
        if (given > 0) src.sendMessage(Text.literal("§a[CustomBlocks] Given all 3 color squares! Right-click a block to swap its color."));
        else src.sendError(Text.literal("§cCould not give squares. Run as a player."));
        return given > 0 ? 1 : 0;
    }

    private static int cmdGiveSquareSilent(ServerCommandSource src, String color) {
        net.minecraft.util.Identifier sqId = net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, color + "_square");
        net.minecraft.item.Item sqItem = net.minecraft.registry.Registries.ITEM.get(sqId);
        if (sqItem == null || sqItem == net.minecraft.item.Items.AIR) return 0;
        try { src.getPlayerOrThrow().getInventory().insertStack(new ItemStack(sqItem, 1)); return 1; }
        catch (Exception ex) { return 0; }
    }

    private static int cmdGiveSquare(ServerCommandSource src, String color) {
        String c = color.toLowerCase().trim();
        if (!c.equals("black") && !c.equals("yellow") && !c.equals("green")) {
            src.sendError(Text.literal("§cValid colors: black, yellow, green")); return 0;
        }
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, c + "_square");
        net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
        if (item == null || item == net.minecraft.item.Items.AIR) { src.sendError(Text.literal("§cSquare item not found.")); return 0; }
        try {
            src.getPlayerOrThrow().getInventory().insertStack(new ItemStack(item, 1));
            src.sendMessage(Text.literal("§a[CustomBlocks] Given " + Character.toUpperCase(c.charAt(0)) + c.substring(1) + " Square!"));
        } catch (Exception ex) { src.sendError(Text.literal("§cRun as a player.")); return 0; }
        return 1;
    }

    private static int cmdGiveTriangle(ServerCommandSource src, String color) {
        String c = color.toLowerCase().trim();
        if (!c.equals("black") && !c.equals("yellow") && !c.equals("green")) {
            src.sendError(Text.literal("§cValid colors: black, yellow, green")); return 0;
        }
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, c + "_triangle");
        net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
        if (item == null || item == net.minecraft.item.Items.AIR) { src.sendError(Text.literal("§cTriangle not found.")); return 0; }
        try {
            src.getPlayerOrThrow().getInventory().insertStack(new ItemStack(item, 1));
            src.sendMessage(Text.literal("§a[CustomBlocks] Given " + Character.toUpperCase(c.charAt(0)) + c.substring(1) + " Triangle!"));
        } catch (Exception ex) { src.sendError(Text.literal("§cRun as a player.")); return 0; }
        return 1;
    }

    private static int cmdGiveRectangle(ServerCommandSource src) {
        net.minecraft.util.Identifier rectId = net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, "rainbow_rectangle");
        net.minecraft.item.Item rectItem = net.minecraft.registry.Registries.ITEM.get(rectId);
        if (rectItem == null || rectItem == net.minecraft.item.Items.AIR) {
            src.sendError(Text.literal("§cRainbow Rectangle not found.")); return 0;
        }
        try {
            src.getPlayerOrThrow().getInventory().insertStack(new ItemStack(rectItem, 1));
            src.sendMessage(Text.literal("§6[CustomBlocks] §eGiven §6Rainbow Rectangle§e! §7Right-click any block face and paste an image URL."));
        } catch (Exception ex) { src.sendError(Text.literal("§cRun as a player.")); return 0; }
        return 1;
    }

    private static int cmdGui(ServerCommandSource src) {
        try {
            ServerPlayerEntity player = src.getPlayerOrThrow();
            GuiManager.openMain(player, 0);
        } catch (Exception ex) {
            src.sendError(Text.literal("§cRun as a player."));
        }
        return 1;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int usage(ServerCommandSource src, String cmd) {
        src.sendError(Text.literal(switch (cmd) {
            case "delete"       -> "§cUsage: /cb delete <id>";
            case "rename"       -> "§cUsage: /cb rename <id> <newname>";
            case "retexture"    -> "§cUsage: /cb retexture <id> <url>";
            case "give"         -> "§cUsage: /cb give <id> [amount 1-64] [player]";
            case "setglow"      -> "§cUsage: /cb setglow <id> <0-15>";
            case "sethardness"  -> "§cUsage: /cb sethardness <id> <-1 to 50>  (-1=unbreakable)";
            case "setsound"     -> "§cUsage: /cb setsound <id> <stone|wood|grass|metal|glass|sand|wool>";
            case "settabicon"   -> "§cUsage: /cb settabicon <url>";
            case "clearface"    -> "§cUsage: /cb clearface <id> <top|bottom|north|south|east|west>";
            case "givesquare"   -> "§cUsage: /cb givesquare <black|yellow|green>";
            case "givetriangle" -> "§cUsage: /cb givetriangle <black|yellow|green>";
            case "clearallfaces"-> "§cUsage: /cb clearallfaces <id>";
            case "resize"       -> "§cUsage: /cb resize <id> <16-256>  — rescale stored texture";
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
        Thread t = new Thread(r, "CB-Download");
        t.setDaemon(true);
        t.start();
    }
}
