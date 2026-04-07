// 
// Decompiled by Procyon v0.6.0
// 

package com.customblocks.command;

import java.util.concurrent.CompletableFuture;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.customblocks.block.UndoHistory;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.FloatArgumentType;
import net.minecraft.class_2186;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.class_2170;
import net.minecraft.class_7157;
import com.mojang.brigadier.CommandDispatcher;
import java.util.List;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.net.URI;
import java.net.http.HttpRequest;
import net.minecraft.class_1802;
import net.minecraft.class_7923;
import net.minecraft.class_1792;
import net.minecraft.class_2960;
import com.google.gson.GsonBuilder;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.OpenOption;
import java.nio.file.Files;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Function;
import java.util.Comparator;
import java.io.File;
import java.util.Iterator;
import com.customblocks.block.SlotBlock;
import net.minecraft.class_1935;
import net.minecraft.class_1799;
import net.minecraft.class_3222;
import java.util.Collection;
import net.minecraft.server.MinecraftServer;
import com.customblocks.CustomBlocksMod;
import com.customblocks.network.SlotUpdatePayload;
import com.customblocks.SlotManager;
import net.minecraft.class_2561;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import java.net.http.HttpClient;
import net.minecraft.class_2168;
import com.mojang.brigadier.suggestion.SuggestionProvider;

public class CustomBlockCommand
{
    private static final SuggestionProvider<class_2168> BLOCK_SUGGESTIONS;
    private static final SuggestionProvider<class_2168> SOUND_SUGGESTIONS;
    private static final SuggestionProvider<class_2168> FACE_SUGGESTIONS;
    private static final HttpClient HTTP_CLIENT;
    
    public static void register() {
        CommandRegistrationCallback.EVENT.register((Object)((dispatcher, reg, env) -> {
            final LiteralArgumentBuilder<class_2168> tree = (LiteralArgumentBuilder<class_2168>)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)class_2170.method_9247("customblock").requires(src -> src.method_9259(2))).then(class_2170.method_9247("createurl").then(class_2170.method_9244("id", (ArgumentType)StringArgumentType.word()).then(class_2170.method_9244("name", (ArgumentType)StringArgumentType.word()).then(class_2170.method_9244("url", (ArgumentType)StringArgumentType.greedyString()).executes(ctx -> cmdCreate((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "id"), StringArgumentType.getString(ctx, "name").replace("_", " "), StringArgumentType.getString(ctx, "url").trim()))))))).then(((LiteralArgumentBuilder)class_2170.method_9247("delete").executes(ctx -> usage((class_2168)ctx.getSource(), "delete"))).then(class_2170.method_9244("id", (ArgumentType)StringArgumentType.word()).suggests((SuggestionProvider)CustomBlockCommand.BLOCK_SUGGESTIONS).executes(ctx -> cmdDelete((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "id")))))).then(((LiteralArgumentBuilder)class_2170.method_9247("rename").executes(ctx -> usage((class_2168)ctx.getSource(), "rename"))).then(class_2170.method_9244("id", (ArgumentType)StringArgumentType.word()).suggests((SuggestionProvider)CustomBlockCommand.BLOCK_SUGGESTIONS).then(class_2170.method_9244("newname", (ArgumentType)StringArgumentType.greedyString()).executes(ctx -> cmdRename((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "id"), StringArgumentType.getString(ctx, "newname").replace("_", " "))))))).then(((LiteralArgumentBuilder)class_2170.method_9247("retexture").executes(ctx -> usage((class_2168)ctx.getSource(), "retexture"))).then(class_2170.method_9244("id", (ArgumentType)StringArgumentType.word()).suggests((SuggestionProvider)CustomBlockCommand.BLOCK_SUGGESTIONS).then(class_2170.method_9244("url", (ArgumentType)StringArgumentType.greedyString()).executes(ctx -> cmdRetexture((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "id"), StringArgumentType.getString(ctx, "url").trim())))))).then(((LiteralArgumentBuilder)class_2170.method_9247("give").executes(ctx -> usage((class_2168)ctx.getSource(), "give"))).then(((RequiredArgumentBuilder)((RequiredArgumentBuilder)class_2170.method_9244("id", (ArgumentType)StringArgumentType.word()).suggests((SuggestionProvider)CustomBlockCommand.BLOCK_SUGGESTIONS).executes(ctx -> cmdGive((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "id"), 1, null))).then(((RequiredArgumentBuilder)class_2170.method_9244("amount", (ArgumentType)IntegerArgumentType.integer(1, 64)).executes(ctx -> cmdGive((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "id"), IntegerArgumentType.getInteger(ctx, "amount"), null))).then(class_2170.method_9244("player", (ArgumentType)class_2186.method_9308()).executes(ctx -> cmdGive((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "id"), IntegerArgumentType.getInteger(ctx, "amount"), class_2186.method_9312(ctx, "player")))))).then(class_2170.method_9244("player", (ArgumentType)class_2186.method_9308()).executes(ctx -> cmdGive((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "id"), 1, class_2186.method_9312(ctx, "player"))))))).then(((LiteralArgumentBuilder)class_2170.method_9247("setglow").executes(ctx -> usage((class_2168)ctx.getSource(), "setglow"))).then(class_2170.method_9244("id", (ArgumentType)StringArgumentType.word()).suggests((SuggestionProvider)CustomBlockCommand.BLOCK_SUGGESTIONS).then(class_2170.method_9244("level", (ArgumentType)IntegerArgumentType.integer(0, 15)).executes(ctx -> cmdSetGlow((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "id"), IntegerArgumentType.getInteger(ctx, "level"))))))).then(((LiteralArgumentBuilder)class_2170.method_9247("sethardness").executes(ctx -> usage((class_2168)ctx.getSource(), "sethardness"))).then(class_2170.method_9244("id", (ArgumentType)StringArgumentType.word()).suggests((SuggestionProvider)CustomBlockCommand.BLOCK_SUGGESTIONS).then(class_2170.method_9244("hardness", (ArgumentType)FloatArgumentType.floatArg(-1.0f, 50.0f)).executes(ctx -> cmdSetHardness((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "id"), FloatArgumentType.getFloat(ctx, "hardness"))))))).then(((LiteralArgumentBuilder)class_2170.method_9247("setsound").executes(ctx -> usage((class_2168)ctx.getSource(), "setsound"))).then(class_2170.method_9244("id", (ArgumentType)StringArgumentType.word()).suggests((SuggestionProvider)CustomBlockCommand.BLOCK_SUGGESTIONS).then(class_2170.method_9244("type", (ArgumentType)StringArgumentType.word()).suggests((SuggestionProvider)CustomBlockCommand.SOUND_SUGGESTIONS).executes(ctx -> cmdSetSound((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "id"), StringArgumentType.getString(ctx, "type"))))))).then(((LiteralArgumentBuilder)class_2170.method_9247("settabicon").executes(ctx -> usage((class_2168)ctx.getSource(), "settabicon"))).then(class_2170.method_9244("url", (ArgumentType)StringArgumentType.greedyString()).executes(ctx -> cmdSetTabIcon((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "url").trim()))))).then(class_2170.method_9247("settopface").then(class_2170.method_9244("id", (ArgumentType)StringArgumentType.word()).suggests((SuggestionProvider)CustomBlockCommand.BLOCK_SUGGESTIONS).then(class_2170.method_9244("url", (ArgumentType)StringArgumentType.greedyString()).executes(ctx -> cmdSetFace((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "id"), "top", StringArgumentType.getString(ctx, "url").trim())))))).then(class_2170.method_9247("setbottomface").then(class_2170.method_9244("id", (ArgumentType)StringArgumentType.word()).suggests((SuggestionProvider)CustomBlockCommand.BLOCK_SUGGESTIONS).then(class_2170.method_9244("url", (ArgumentType)StringArgumentType.greedyString()).executes(ctx -> cmdSetFace((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "id"), "bottom", StringArgumentType.getString(ctx, "url").trim())))))).then(class_2170.method_9247("setnorthface").then(class_2170.method_9244("id", (ArgumentType)StringArgumentType.word()).suggests((SuggestionProvider)CustomBlockCommand.BLOCK_SUGGESTIONS).then(class_2170.method_9244("url", (ArgumentType)StringArgumentType.greedyString()).executes(ctx -> cmdSetFace((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "id"), "north", StringArgumentType.getString(ctx, "url").trim())))))).then(class_2170.method_9247("setsouthface").then(class_2170.method_9244("id", (ArgumentType)StringArgumentType.word()).suggests((SuggestionProvider)CustomBlockCommand.BLOCK_SUGGESTIONS).then(class_2170.method_9244("url", (ArgumentType)StringArgumentType.greedyString()).executes(ctx -> cmdSetFace((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "id"), "south", StringArgumentType.getString(ctx, "url").trim())))))).then(class_2170.method_9247("seteastface").then(class_2170.method_9244("id", (ArgumentType)StringArgumentType.word()).suggests((SuggestionProvider)CustomBlockCommand.BLOCK_SUGGESTIONS).then(class_2170.method_9244("url", (ArgumentType)StringArgumentType.greedyString()).executes(ctx -> cmdSetFace((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "id"), "east", StringArgumentType.getString(ctx, "url").trim())))))).then(class_2170.method_9247("setwestface").then(class_2170.method_9244("id", (ArgumentType)StringArgumentType.word()).suggests((SuggestionProvider)CustomBlockCommand.BLOCK_SUGGESTIONS).then(class_2170.method_9244("url", (ArgumentType)StringArgumentType.greedyString()).executes(ctx -> cmdSetFace((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "id"), "west", StringArgumentType.getString(ctx, "url").trim())))))).then(((LiteralArgumentBuilder)class_2170.method_9247("clearface").executes(ctx -> usage((class_2168)ctx.getSource(), "clearface"))).then(class_2170.method_9244("id", (ArgumentType)StringArgumentType.word()).suggests((SuggestionProvider)CustomBlockCommand.BLOCK_SUGGESTIONS).then(class_2170.method_9244("face", (ArgumentType)StringArgumentType.word()).suggests((SuggestionProvider)CustomBlockCommand.FACE_SUGGESTIONS).executes(ctx -> cmdClearFace((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "id"), StringArgumentType.getString(ctx, "face"))))))).then(((LiteralArgumentBuilder)class_2170.method_9247("givesquare").executes(ctx -> usage((class_2168)ctx.getSource(), "givesquare"))).then(class_2170.method_9244("color", (ArgumentType)StringArgumentType.word()).suggests((ctx, builder) -> {
                builder.suggest("black");
                builder.suggest("yellow");
                builder.suggest("green");
                return builder.buildFuture();
            }).executes(ctx -> cmdGiveSquare((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "color")))))).then(((LiteralArgumentBuilder)class_2170.method_9247("clearallfaces").executes(ctx -> usage((class_2168)ctx.getSource(), "clearallfaces"))).then(class_2170.method_9244("id", (ArgumentType)StringArgumentType.word()).suggests((SuggestionProvider)CustomBlockCommand.BLOCK_SUGGESTIONS).executes(ctx -> cmdClearAllFaces((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "id")))))).then(class_2170.method_9247("export").executes(ctx -> cmdExport((class_2168)ctx.getSource())))).then(class_2170.method_9247("importfolder").executes(ctx -> cmdImportFolder((class_2168)ctx.getSource())))).then(class_2170.method_9247("stop").executes(ctx -> {
                final class_2168 src = (class_2168)ctx.getSource();
                for (final class_3222 p : src.method_9211().method_3760().method_14571()) {
                    p.method_43496((class_2561)class_2561.method_43470("§c[Server] Server is stopping..."));
                }
                src.method_9211().method_3747(false);
                return 1;
            }))).then(class_2170.method_9247("restart").executes(ctx -> {
                final class_2168 src = (class_2168)ctx.getSource();
                for (final class_3222 p : src.method_9211().method_3760().method_14571()) {
                    p.method_43496((class_2561)class_2561.method_43470("§c[Server] Restarting in 3 seconds..."));
                }
                final Thread t = new Thread(() -> {
                    try {
                        Thread.sleep(3000L);
                    }
                    catch (final InterruptedException ex) {}
                    src.method_9211().method_3747(false);
                    return;
                }, "CB-Restart");
                t.setDaemon(true);
                t.start();
                return 1;
            }))).then(class_2170.method_9247("reload").executes(ctx -> {
                final class_2168 src = (class_2168)ctx.getSource();
                final File configDir = new File("config/customblocks");
                if (!configDir.exists()) {
                    src.method_9213((class_2561)class_2561.method_43470("§c[CustomBlocks] config/customblocks/ not found."));
                    return 0;
                }
                final File[] folders = configDir.listFiles(File::isDirectory);
                if (folders == null || folders.length == 0) {
                    src.method_45068((class_2561)class_2561.method_43470("§7[CustomBlocks] No block folders found."));
                    return 0;
                }
                int loaded = 0;
                int skipped = 0;
                final MinecraftServer server = src.method_9211();
                for (final File folder : folders) {
                    final String id = folder.getName().toLowerCase().replaceAll("[^a-z0-9_]", "_");
                    if (!id.isEmpty()) {
                        if (SlotManager.hasId(id)) {
                            ++skipped;
                        }
                        else {
                            final File tex = new File(folder, "texture.png");
                            if (!tex.exists()) {
                                ++skipped;
                            }
                            else {
                                if (SlotManager.freeSlots() == 0) {
                                    src.method_9213((class_2561)class_2561.method_43470("§c[CustomBlocks] No free slots left!"));
                                    break;
                                }
                                try {
                                    final byte[] bytes = Files.readAllBytes(tex.toPath());
                                    String name = id;
                                    final File nameTxt = new File(folder, "name.txt");
                                    if (nameTxt.exists()) {
                                        final String n = Files.readString(nameTxt.toPath()).trim();
                                        if (!n.isEmpty()) {
                                            name = n;
                                        }
                                    }
                                    final SlotManager.SlotData d = SlotManager.assign(id, name, bytes);
                                    if (d != null) {
                                        CustomBlocksMod.broadcastUpdate(server, new SlotUpdatePayload("add", d.index, id, name, bytes, d.lightLevel, d.hardness, d.soundType));
                                        ++loaded;
                                    }
                                }
                                catch (final Exception e) {
                                    ++skipped;
                                }
                            }
                        }
                    }
                }
                if (loaded > 0) {
                    SlotManager.saveAll();
                }
                src.method_45068((class_2561)class_2561.method_43470("§a[CustomBlocks] Reload done: §f" + loaded + " §aloaded, §7" + skipped + " skipped."));
                src.method_45068((class_2561)class_2561.method_43470("§7Slots: " + SlotManager.usedSlots() + " used, " + SlotManager.freeSlots() + " free."));
                return loaded;
            }))).then(class_2170.method_9247("list").executes(ctx -> cmdList((class_2168)ctx.getSource())))).then(class_2170.method_9247("help").executes(ctx -> cmdHelp((class_2168)ctx.getSource())))).then(((LiteralArgumentBuilder)class_2170.method_9247("colorchanger").executes(ctx -> cmdColorChangerAll((class_2168)ctx.getSource()))).then(class_2170.method_9244("color", (ArgumentType)StringArgumentType.word()).suggests((ctx, builder) -> {
                builder.suggest("black");
                builder.suggest("yellow");
                builder.suggest("green");
                return builder.buildFuture();
            }).executes(ctx -> cmdGiveSquare((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "color")))))).then(((LiteralArgumentBuilder)class_2170.method_9247("givetriangle").executes(ctx -> usage((class_2168)ctx.getSource(), "givetriangle"))).then(class_2170.method_9244("color", (ArgumentType)StringArgumentType.word()).suggests((ctx, builder) -> {
                builder.suggest("black");
                builder.suggest("yellow");
                builder.suggest("green");
                return builder.buildFuture();
            }).executes(ctx -> cmdGiveTriangle((class_2168)ctx.getSource(), StringArgumentType.getString(ctx, "color")))))).then(class_2170.method_9247("undo").executes(ctx -> {
                final class_2168 src = (class_2168)ctx.getSource();
                try {
                    final class_3222 player = src.method_9207();
                    final boolean ok = UndoHistory.undo(player);
                    src.method_45068((class_2561)class_2561.method_43470(ok ? "§a[CustomBlocks] Undone!" : "§7[CustomBlocks] Nothing to undo."));
                    return ok ? 1 : 0;
                }
                catch (final Exception ex) {
                    src.method_9213((class_2561)class_2561.method_43470("§c[CustomBlocks] Only players can undo."));
                    return 0;
                }
            }))).then(class_2170.method_9247("redo").executes(ctx -> {
                final class_2168 src = (class_2168)ctx.getSource();
                try {
                    final class_3222 player = src.method_9207();
                    final boolean ok = UndoHistory.redo(player);
                    src.method_45068((class_2561)class_2561.method_43470(ok ? "§a[CustomBlocks] Redone!" : "§7[CustomBlocks] Nothing to redo."));
                    return ok ? 1 : 0;
                }
                catch (final Exception ex) {
                    src.method_9213((class_2561)class_2561.method_43470("§c[CustomBlocks] Only players can redo."));
                    return 0;
                }
            }))).then(class_2170.method_9247("bulkdelete").executes(ctx -> {
                final class_2168 src = (class_2168)ctx.getSource();
                final File file = new File("config/customblocks/delete_list.txt");
                if (!file.exists()) {
                    src.method_9213((class_2561)class_2561.method_43470("§c[CustomBlocks] File not found: config/customblocks/delete_list.txt"));
                    src.method_45068((class_2561)class_2561.method_43470("§7Create that file with one block ID per line, then run /cb bulkdelete."));
                    return 0;
                }
                try {
                    final List<String> lines = Files.readAllLines(file.toPath());
                    int deleted = 0;
                    int skipped = 0;
                    for (final String line : lines) {
                        final String id = line.trim().toLowerCase();
                        if (!id.isEmpty()) {
                            if (id.startsWith("#")) {
                                continue;
                            }
                            if (SlotManager.hasId(id)) {
                                final SlotManager.SlotData d = SlotManager.getById(id);
                                CustomBlocksMod.broadcastUpdate(src.method_9211(), new SlotUpdatePayload("remove", d.index, id, null, null, 0, 0.0f, "stone"));
                                SlotManager.remove(id);
                                ++deleted;
                            }
                            else {
                                ++skipped;
                            }
                        }
                    }
                    if (deleted > 0) {
                        SlotManager.saveAll();
                    }
                    src.method_45068((class_2561)class_2561.method_43470("§a[CustomBlocks] Bulk delete done: §f" + deleted + " §adeleted, §7" + skipped + " not found."));
                    src.method_45068((class_2561)class_2561.method_43470("§7Slots now: " + SlotManager.usedSlots() + " used, " + SlotManager.freeSlots() + " free."));
                    return deleted;
                }
                catch (final IOException e) {
                    src.method_9213((class_2561)class_2561.method_43470("§c[CustomBlocks] Error reading file: " + e.getMessage()));
                    return 0;
                }
            }));
            dispatcher.register((LiteralArgumentBuilder)tree);
            dispatcher.register((LiteralArgumentBuilder)((LiteralArgumentBuilder)class_2170.method_9247("cb").requires(src -> src.method_9259(2))).redirect(dispatcher.getRoot().getChild("customblock")));
        }));
    }
    
    private static int cmdCreate(final class_2168 src, final String rawId, final String name, final String url) {
        final String id = sanitize(rawId);
        if (id.isEmpty()) {
            src.method_9213((class_2561)class_2561.method_43470("§cInvalid ID."));
            return 0;
        }
        if (SlotManager.hasId(id)) {
            src.method_9213((class_2561)class_2561.method_43470("§c'" + id + "' already exists."));
            return 0;
        }
        if (SlotManager.freeSlots() == 0) {
            src.method_9213((class_2561)class_2561.method_43470("§cAll 512 slots are full!"));
            return 0;
        }
        src.method_45068((class_2561)class_2561.method_43470("§e[CustomBlocks] Downloading..."));
        final MinecraftServer server = src.method_9211();
        final String fId = id;
        final String fName = name;
        thread(() -> {
            try {
                final byte[] bytes = download(url);
                server.execute(() -> {
                    final SlotManager.SlotData d = SlotManager.assign(fId, fName, bytes);
                    if (d == null) {
                        src.method_9213((class_2561)class_2561.method_43470("§cNo free slots!"));
                    }
                    else {
                        SlotManager.saveAll();
                        CustomBlocksMod.broadcastUpdate(server, new SlotUpdatePayload("add", d.index, fId, fName, bytes, d.lightLevel, d.hardness, d.soundType));
                        src.method_45068((class_2561)class_2561.method_43470("§a[CustomBlocks] '" + fName + "' created! §7(slot " + d.index));
                    }
                });
            }
            catch (final Exception e) {
                server.execute(() -> src.method_9213((class_2561)class_2561.method_43470("§c[CustomBlocks] Download failed: " + e.getMessage())));
            }
            return;
        });
        return 1;
    }
    
    private static int cmdDelete(final class_2168 src, final String id) {
        if (!SlotManager.hasId(id)) {
            src.method_9213(notFound(id));
            return 0;
        }
        final SlotManager.SlotData d = SlotManager.getById(id);
        SlotManager.remove(id);
        SlotManager.saveAll();
        CustomBlocksMod.broadcastUpdate(src.method_9211(), new SlotUpdatePayload("remove", d.index, id, null, null, 0, 0.0f, "stone"));
        src.method_45068((class_2561)class_2561.method_43470("§a[CustomBlocks] '" + id + "' deleted."));
        return 1;
    }
    
    private static int cmdRename(final class_2168 src, final String id, final String newName) {
        if (!SlotManager.hasId(id)) {
            src.method_9213(notFound(id));
            return 0;
        }
        final SlotManager.SlotData d = SlotManager.getById(id);
        SlotManager.rename(id, newName);
        SlotManager.saveAll();
        CustomBlocksMod.broadcastUpdate(src.method_9211(), new SlotUpdatePayload("rename", d.index, id, newName, null, 0, 0.0f, "stone"));
        src.method_45068((class_2561)class_2561.method_43470("§a[CustomBlocks] Renamed to '" + newName + "'."));
        return 1;
    }
    
    private static int cmdRetexture(final class_2168 src, final String id, final String url) {
        if (!SlotManager.hasId(id)) {
            src.method_9213(notFound(id));
            return 0;
        }
        src.method_45068((class_2561)class_2561.method_43470("§e[CustomBlocks] Downloading texture..."));
        final MinecraftServer server = src.method_9211();
        final String fId = id;
        thread(() -> {
            try {
                final byte[] bytes = download(url);
                server.execute(() -> {
                    final SlotManager.SlotData d = SlotManager.getById(fId);
                    if (d == null) {
                        src.method_9213(notFound(fId));
                    }
                    else {
                        SlotManager.updateTexture(fId, bytes);
                        SlotManager.saveAll();
                        CustomBlocksMod.broadcastUpdate(server, new SlotUpdatePayload("retexture", d.index, fId, null, bytes, d.lightLevel, d.hardness, d.soundType));
                        src.method_45068((class_2561)class_2561.method_43470("§a[CustomBlocks] Texture updated for '" + fId + "'."));
                    }
                });
            }
            catch (final Exception e) {
                server.execute(() -> src.method_9213((class_2561)class_2561.method_43470("§c[CustomBlocks] Download failed: " + e.getMessage())));
            }
            return;
        });
        return 1;
    }
    
    private static int cmdGive(final class_2168 src, final String id, final int amount, final Collection<class_3222> targets) {
        final SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null) {
            src.method_9213(notFound(id));
            return 0;
        }
        final SlotBlock.SlotItem item = CustomBlocksMod.SLOT_ITEMS[d.index];
        final class_1799 stack = new class_1799((class_1935)item, Math.max(1, Math.min(64, amount)));
        if (targets != null) {
            if (!targets.isEmpty()) {
                for (class_3222 p : targets) {
                    p.method_31548().method_7394(stack.method_7972());
                    p.method_43496((class_2561)class_2561.method_43470("§a[CustomBlocks] You received " + amount + "x '" + d.displayName + "'."));
                }
                src.method_45068((class_2561)class_2561.method_43470("§a[CustomBlocks] Gave " + amount + "x to " + targets.size() + " player(s)."));
                return 1;
            }
        }
        try {
            final class_3222 self = src.method_9207();
            self.method_31548().method_7394(stack.method_7972());
            src.method_45068((class_2561)class_2561.method_43470("§a[CustomBlocks] Given " + amount + "x '" + d.displayName + "' to you."));
        }
        catch (final Exception ex) {
            src.method_9213((class_2561)class_2561.method_43470("§cRun as a player or specify a target."));
        }
        return 1;
    }
    
    private static int cmdSetGlow(final class_2168 src, final String id, final int level) {
        if (!SlotManager.hasId(id)) {
            src.method_9213(notFound(id));
            return 0;
        }
        final SlotManager.SlotData d = SlotManager.getById(id);
        SlotManager.setLightLevel(id, level);
        SlotManager.saveAll();
        CustomBlocksMod.broadcastUpdate(src.method_9211(), new SlotUpdatePayload("setprop", d.index, id, null, null, level, d.hardness, d.soundType));
        src.method_45068((class_2561)class_2561.method_43470("§a[CustomBlocks] '" + id + "' glow set to " + level));
        return 1;
    }
    
    private static int cmdSetHardness(final class_2168 src, final String id, final float val) {
        if (!SlotManager.hasId(id)) {
            src.method_9213(notFound(id));
            return 0;
        }
        final SlotManager.SlotData d = SlotManager.getById(id);
        SlotManager.setHardness(id, val);
        SlotManager.saveAll();
        final String label = (val < 0.0f) ? "Unbreakable" : ((val == 0.0f) ? "Instant break" : String.valueOf(val));
        CustomBlocksMod.broadcastUpdate(src.method_9211(), new SlotUpdatePayload("setprop", d.index, id, null, null, d.lightLevel, val, d.soundType));
        src.method_45068((class_2561)class_2561.method_43470("§a[CustomBlocks] '" + id + "' hardness: " + label));
        return 1;
    }
    
    private static int cmdSetSound(final class_2168 src, final String id, final String type) {
        if (!SlotManager.hasId(id)) {
            src.method_9213(notFound(id));
            return 0;
        }
        final String[] valid = { "stone", "wood", "grass", "metal", "glass", "sand", "wool" };
        boolean ok = false;
        for (final String v : valid) {
            if (v.equals(type)) {
                ok = true;
                break;
            }
        }
        if (!ok) {
            src.method_9213((class_2561)class_2561.method_43470("§cValid sounds: stone, wood, grass, metal, glass, sand, wool"));
            return 0;
        }
        final SlotManager.SlotData d = SlotManager.getById(id);
        SlotManager.setSoundType(id, type);
        SlotManager.saveAll();
        CustomBlocksMod.broadcastUpdate(src.method_9211(), new SlotUpdatePayload("setprop", d.index, id, null, null, d.lightLevel, d.hardness, type));
        src.method_45068((class_2561)class_2561.method_43470("§a[CustomBlocks] '" + id + "' sound: " + type));
        return 1;
    }
    
    private static int cmdSetTabIcon(final class_2168 src, final String url) {
        src.method_45068((class_2561)class_2561.method_43470("§e[CustomBlocks] Downloading tab icon..."));
        final MinecraftServer server = src.method_9211();
        thread(() -> {
            try {
                final byte[] bytes = download(url);
                server.execute(() -> {
                    SlotManager.setTabIconTexture(bytes);
                    if (!SlotManager.hasId("tab_icon")) {
                        SlotManager.assign("tab_icon", "Tab Icon", bytes);
                    }
                    else {
                        SlotManager.updateTexture("tab_icon", bytes);
                    }
                    SlotManager.saveAll();
                    final SlotManager.SlotData d = SlotManager.getById("tab_icon");
                    if (d != null) {
                        CustomBlocksMod.broadcastUpdate(server, new SlotUpdatePayload("add", d.index, "tab_icon", "Tab Icon", bytes, 0, 1.5f, "stone"));
                    }
                    CustomBlocksMod.broadcastUpdate(server, new SlotUpdatePayload("tabicon", -1, null, null, bytes, 0, 0.0f, "stone"));
                    src.method_45068((class_2561)class_2561.method_43470("§a[CustomBlocks] Tab icon updated!"));
                });
            }
            catch (final Exception e) {
                server.execute(() -> src.method_9213((class_2561)class_2561.method_43470("§c[CustomBlocks] Download failed: " + e.getMessage())));
            }
            return;
        });
        return 1;
    }
    
    private static int cmdImportFolder(final class_2168 src) {
        final File importDir = new File("config/customblocks/import");
        if (!importDir.exists()) {
            importDir.mkdirs();
            src.method_45068((class_2561)class_2561.method_43470("§e[CustomBlocks] Created import folder: §fconfig/customblocks/import/"));
            src.method_45068((class_2561)class_2561.method_43470("§7Drop PNG files in there, then run /customblock importfolder again."));
            src.method_45068((class_2561)class_2561.method_43470("§7Filename becomes block ID (e.g. green_a.png = id green_a, name Green A)"));
            return 1;
        }
        final File[] allPngs = importDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".png"));
        if (allPngs == null || allPngs.length == 0) {
            src.method_45068((class_2561)class_2561.method_43470("§c[CustomBlocks] No PNG files found in config/customblocks/import/"));
            return 0;
        }
        Arrays.sort(allPngs, Comparator.comparing((Function<? super File, ? extends Comparable>)File::getName));
        final int free = SlotManager.freeSlots();
        if (free == 0) {
            src.method_9213((class_2561)class_2561.method_43470("§cAll 512 slots are full!"));
            return 0;
        }
        src.method_45068((class_2561)class_2561.method_43470("§e[CustomBlocks] Found " + allPngs.length + " PNG(s), " + free + " slots free. Importing..."));
        final MinecraftServer server = src.method_9211();
        thread(() -> {
            final ArrayList toAdd = new ArrayList<String[]>();
            final ArrayList<byte[]> toAddBytes = new ArrayList<byte[]>();
            final ArrayList<String> skipped = new ArrayList<String>();
            final ArrayList<String> failed = new ArrayList<String>();
            int i = 0;
            for (int length = allPngs.length; i < length; ++i) {
                final File png = allPngs[i];
                final String rawName = png.getName().replaceAll("(?i)\\.(png|jpg|jpeg)$", "");
                final String id = rawName.toLowerCase().replaceAll("[^a-z0-9_]", "_");
                final String displayName = Arrays.stream(rawName.replace("_", " ").split(" ")).map(w -> w.isEmpty() ? w : (Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase())).collect((Collector<? super Object, ?, String>)Collectors.joining(" "));
                if (SlotManager.hasId(id)) {
                    skipped.add(id);
                }
                else if (toAdd.size() >= free) {
                    failed.add(id + "(no free slot)");
                }
                else {
                    try {
                        final byte[] bytes = Files.readAllBytes(png.toPath());
                        toAdd.add(new String[] { id, displayName });
                        toAddBytes.add(bytes);
                    }
                    catch (final Exception e) {
                        failed.add(id + "(read error)");
                    }
                }
            }
            server.execute(() -> {
                int created = 0;
                for (int j = 0; j < toAdd.size(); ++j) {
                    final String id2 = ((String[])toAdd.get(j))[0];
                    final String name2 = ((String[])toAdd.get(j))[1];
                    final byte[] b = toAddBytes.get(j);
                    final SlotManager.SlotData d = SlotManager.assign(id2, name2, b);
                    if (d == null) {
                        failed.add(id2 + "(slot full)");
                    }
                    else {
                        try {
                            final File blockFolder = new File("config/customblocks/" + id2);
                            blockFolder.mkdirs();
                            Files.write(new File(blockFolder, "texture.png").toPath(), b, new OpenOption[0]);
                            Files.writeString(new File(blockFolder, "name.txt").toPath(), name2, new OpenOption[0]);
                        }
                        catch (final Exception ex) {}
                        CustomBlocksMod.broadcastUpdate(server, new SlotUpdatePayload("add", d.index, id2, name2, b, d.lightLevel, d.hardness, d.soundType));
                        ++created;
                    }
                }
                if (created > 0) {
                    SlotManager.saveAll();
                }
                final StringBuilder msg = new StringBuilder("§a[CustomBlocks] Done! §f" + created + " created");
                if (!skipped.isEmpty()) {
                    msg.append("§7, ").append(skipped.size()).append(" skipped");
                }
                if (!failed.isEmpty()) {
                    msg.append("§c, ").append(failed.size()).append(" failed");
                }
                src.method_45068((class_2561)class_2561.method_43470(msg.toString()));
                src.method_45068((class_2561)class_2561.method_43470("§7Slots: " + SlotManager.usedSlots() + " / 512 used"));
                if (!failed.isEmpty()) {
                    src.method_45068((class_2561)class_2561.method_43470("§cFailed: " + String.join(", ", failed)));
                }
            });
            return;
        });
        return 1;
    }
    
    private static int cmdExport(final class_2168 src) {
        final File dir = new File("config/customblocks");
        dir.mkdirs();
        final File out = new File(dir, "export.json");
        try {
            final JsonObject root = new JsonObject();
            final JsonArray arr = new JsonArray();
            for (final SlotManager.SlotData d : SlotManager.allSlots()) {
                final JsonObject e = new JsonObject();
                e.addProperty("id", d.customId);
                e.addProperty("displayName", d.displayName);
                e.addProperty("slot", (Number)d.index);
                e.addProperty("lightLevel", (Number)d.lightLevel);
                e.addProperty("hardness", (Number)d.hardness);
                e.addProperty("soundType", d.soundType);
                arr.add((JsonElement)e);
            }
            root.add("blocks", (JsonElement)arr);
            root.addProperty("totalBlocks", (Number)SlotManager.usedSlots());
            root.addProperty("freeSlots", (Number)SlotManager.freeSlots());
            try (final FileWriter fw = new FileWriter(out, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson((JsonElement)root, (Appendable)fw);
            }
            src.method_45068((class_2561)class_2561.method_43470("§a[CustomBlocks] Exported " + SlotManager.usedSlots() + " blocks to config/customblocks/export.json"));
        }
        catch (final Exception e2) {
            src.method_9213((class_2561)class_2561.method_43470("§cExport failed: " + e2.getMessage()));
        }
        return 1;
    }
    
    private static int cmdList(final class_2168 src) {
        if (SlotManager.usedSlots() == 0) {
            src.method_45068((class_2561)class_2561.method_43470("§7[CustomBlocks] No blocks. " + SlotManager.freeSlots() + " slots free."));
            return 1;
        }
        src.method_45068((class_2561)class_2561.method_43470("§e[CustomBlocks] §f" + SlotManager.usedSlots() + " block(s) | §7" + SlotManager.freeSlots() + " free:"));
        for (SlotManager.SlotData d : SlotManager.allSlots()) {
            final String glow = (d.lightLevel > 0) ? (" §6*" + d.lightLevel) : "";
            final String hard = (d.hardness < 0.0f) ? " §c\u221e" : "";
            src.method_45068((class_2561)class_2561.method_43470("  §f" + d.customId + " §7\u2192 '" + d.displayName + "'" + glow + hard + " §8(slot " + d.index));
        }
        return 1;
    }
    
    private static int cmdHelp(final class_2168 src) {
        src.method_45068((class_2561)class_2561.method_43470("§e\u2550\u2550 Custom Blocks Help \u2550\u2550"));
        src.method_45068((class_2561)class_2561.method_43470("§aPress §fB §ato open the visual GUI!"));
        src.method_45068((class_2561)class_2561.method_43470("§f/customblock createurl <id> <n> <url>  §7create from image"));
        src.method_45068((class_2561)class_2561.method_43470("§f/customblock delete <id>  §7delete a block"));
        src.method_45068((class_2561)class_2561.method_43470("§f/customblock rename <id> <newname>  §7rename"));
        src.method_45068((class_2561)class_2561.method_43470("§f/customblock retexture <id> <url>  §7change texture"));
        src.method_45068((class_2561)class_2561.method_43470("§f/customblock give <id> [amount] [player]  §7give block (amount 1-64)"));
        src.method_45068((class_2561)class_2561.method_43470("§f/customblock setglow <id> <0-15>  §7light emission"));
        src.method_45068((class_2561)class_2561.method_43470("§f/customblock sethardness <id> <val>  §7mining speed (\u22121=unbreakable)"));
        src.method_45068((class_2561)class_2561.method_43470("§f/customblock setsound <id> <stone|wood|metal|glass|grass|sand>"));
        src.method_45068((class_2561)class_2561.method_43470("§f/customblock settabicon <url>  §7set tab icon"));
        src.method_45068((class_2561)class_2561.method_43470("§f/customblock set[top|bottom|north|south|east|west]face <id> <url>  §7set a face"));
        src.method_45068((class_2561)class_2561.method_43470("§f/customblock givesquare <black|yellow|green>  §7get a color-swap square item"));
        src.method_45068((class_2561)class_2561.method_43470("§f/customblock givetriangle <black|yellow|green>  §7get a color-change triangle item"));
        src.method_45068((class_2561)class_2561.method_43470("§f/customblock colorchanger [color]  §7give all 3 squares (or one color)"));
        src.method_45068((class_2561)class_2561.method_43470("§f/customblock reload  §7load new blocks from config/customblocks/ without restart"));
        src.method_45068((class_2561)class_2561.method_43470("§f/customblock undo  §7undo last color-square swap"));
        src.method_45068((class_2561)class_2561.method_43470("§f/customblock redo  §7redo last undone swap"));
        src.method_45068((class_2561)class_2561.method_43470("§f/customblock bulkdelete  §7delete all IDs listed in config/customblocks/delete_list.txt"));
        src.method_45068((class_2561)class_2561.method_43470("§7Tip: use §f/cb§7 as a short alias for §f/customblock§7!"));
        src.method_45068((class_2561)class_2561.method_43470("§f/customblock clearface <id> <face>  §7revert one face to default"));
        src.method_45068((class_2561)class_2561.method_43470("§f/customblock clearallfaces <id>  §7revert all faces to default"));
        src.method_45068((class_2561)class_2561.method_43470("§f/customblock importfolder  §7bulk-import PNGs from config/customblocks/import/"));
        src.method_45068((class_2561)class_2561.method_43470("§f/customblock export  §7export block list to config/customblocks/export.json"));
        src.method_45068((class_2561)class_2561.method_43470("§f/customblock list  §7list all blocks"));
        src.method_45068((class_2561)class_2561.method_43470("§7No restarts needed for any command!"));
        return 1;
    }
    
    private static int cmdSetFace(final class_2168 src, final String id, final String face, final String url) {
        if (!SlotManager.hasId(id)) {
            src.method_9213(notFound(id));
            return 0;
        }
        src.method_45068((class_2561)class_2561.method_43470("§e[CustomBlocks] Downloading " + face + " face texture..."));
        final MinecraftServer server = src.method_9211();
        thread(() -> {
            try {
                final byte[] bytes = download(url);
                server.execute(() -> {
                    final SlotManager.SlotData d = SlotManager.getById(id);
                    if (d == null) {
                        src.method_9213((class_2561)class_2561.method_43470("§c[CustomBlocks] '" + id + "' was deleted before texture arrived."));
                    }
                    else {
                        SlotManager.setFaceTexture(id, face, bytes);
                        SlotManager.saveAll();
                        src.method_45068((class_2561)class_2561.method_43470("§a[CustomBlocks] " + face + " face set on '" + id + "'."));
                    }
                });
            }
            catch (final Exception e) {
                server.execute(() -> src.method_9213((class_2561)class_2561.method_43470("§c[CustomBlocks] Download failed: " + e.getMessage())));
            }
            return;
        });
        return 1;
    }
    
    private static int cmdClearFace(final class_2168 src, final String id, final String face) {
        if (!SlotManager.hasId(id)) {
            src.method_9213(notFound(id));
            return 0;
        }
        if (!SlotManager.FACE_KEYS.contains(face)) {
            src.method_9213((class_2561)class_2561.method_43470("§cValid faces: top bottom north south east west"));
            return 0;
        }
        final SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null) {
            src.method_9213(notFound(id));
            return 0;
        }
        SlotManager.clearFaceTexture(id, face);
        SlotManager.saveAll();
        src.method_45068((class_2561)class_2561.method_43470("§a[CustomBlocks] " + face + " face cleared on '" + id + "' (reverted to default)."));
        return 1;
    }
    
    private static int cmdClearAllFaces(final class_2168 src, final String id) {
        if (!SlotManager.hasId(id)) {
            src.method_9213(notFound(id));
            return 0;
        }
        final SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null) {
            src.method_9213(notFound(id));
            return 0;
        }
        SlotManager.clearAllFaces(id);
        SlotManager.saveAll();
        src.method_45068((class_2561)class_2561.method_43470("§a[CustomBlocks] All face overrides cleared on '" + id + "'."));
        return 1;
    }
    
    private static int cmdColorChangerAll(final class_2168 src) {
        int given = 0;
        for (final String col : new String[] { "black", "yellow", "green" }) {
            given += cmdGiveSquareSilent(src, col);
        }
        if (given > 0) {
            src.method_45068((class_2561)class_2561.method_43470("§a[CustomBlocks] Given all 3 color squares! Right-click any Custom Block to swap its color."));
        }
        else {
            src.method_9213((class_2561)class_2561.method_43470("§cCould not give squares. Run as a player."));
        }
        return (given > 0) ? 1 : 0;
    }
    
    private static int cmdGiveSquareSilent(final class_2168 src, final String color) {
        final String normalized = color.toLowerCase().trim();
        final class_2960 sqId = class_2960.method_60655("customblocks", normalized + "_square");
        final class_1792 sqItem = (class_1792)class_7923.field_41178.method_10223(sqId);
        if (sqItem == null || sqItem == class_1802.field_8162) {
            return 0;
        }
        try {
            final class_3222 self = src.method_9207();
            self.method_31548().method_7394(new class_1799((class_1935)sqItem, 1));
            return 1;
        }
        catch (final Exception ex) {
            return 0;
        }
    }
    
    private static int cmdGiveSquare(final class_2168 src, final String color) {
        final String normalized = color.toLowerCase().trim();
        if (!normalized.equals("black") && !normalized.equals("yellow") && !normalized.equals("green")) {
            src.method_9213((class_2561)class_2561.method_43470("§cValid colors: black, yellow, green"));
            return 0;
        }
        final class_2960 sqId = class_2960.method_60655("customblocks", normalized + "_square");
        final class_1792 sqItem = (class_1792)class_7923.field_41178.method_10223(sqId);
        if (sqItem == null || sqItem == class_1802.field_8162) {
            src.method_9213((class_2561)class_2561.method_43470("§cSquare item not found \u2014 is the mod loaded?"));
            return 0;
        }
        try {
            final class_3222 self = src.method_9207();
            self.method_31548().method_7394(new class_1799((class_1935)sqItem, 1));
            final String label = Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
            src.method_45068((class_2561)class_2561.method_43470("§a[CustomBlocks] Given " + label + " Square. Right-click a Custom Block to swap color!"));
        }
        catch (final Exception ex) {
            src.method_9213((class_2561)class_2561.method_43470("§cRun as a player."));
            return 0;
        }
        return 1;
    }
    
    private static int cmdGiveTriangle(final class_2168 src, final String color) {
        final String normalized = color.toLowerCase().trim();
        if (!normalized.equals("black") && !normalized.equals("yellow") && !normalized.equals("green")) {
            src.method_9213((class_2561)class_2561.method_43470("§cValid colors: black, yellow, green"));
            return 0;
        }
        final class_2960 triId = class_2960.method_60655("customblocks", normalized + "_triangle");
        final class_1792 triItem = (class_1792)class_7923.field_41178.method_10223(triId);
        if (triItem == null || triItem == class_1802.field_8162) {
            src.method_9213((class_2561)class_2561.method_43470("§cTriangle item not found — is the mod loaded?"));
            return 0;
        }
        try {
            final class_3222 self = src.method_9207();
            self.method_31548().method_7394(new class_1799((class_1935)triItem, 1));
            final String label = Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
            src.method_45068((class_2561)class_2561.method_43470("§a[CustomBlocks] Given " + label + " Triangle. Right-click a Custom Block to change its background color!"));
        }
        catch (final Exception ex) {
            src.method_9213((class_2561)class_2561.method_43470("§cRun as a player."));
            return 0;
        }
        return 1;
    }
    
    private static int usage(final class_2168 src, final String cmd) {
        src.method_9213((class_2561)class_2561.method_43470(switch (cmd) {
            case "delete" -> "§cUsage: /customblock delete <id>";
            case "rename" -> "§cUsage: /customblock rename <id> <newname>";
            case "retexture" -> "§cUsage: /customblock retexture <id> <url>";
            case "give" -> "§cUsage: /customblock give <id> [amount 1-64] [player]";
            case "setglow" -> "§cUsage: /customblock setglow <id> <0-15>";
            case "sethardness" -> "§cUsage: /customblock sethardness <id> <-1 to 50>  (-1=unbreakable)";
            case "setsound" -> "§cUsage: /customblock setsound <id> <stone|wood|grass|metal|glass|sand|wool>";
            case "settabicon" -> "§cUsage: /customblock settabicon <url>";
            case "clearface" -> "§cUsage: /customblock clearface <id> <top|bottom|north|south|east|west>";
            case "givesquare" -> "§cUsage: /customblock givesquare <black|yellow|green>";
            case "givetriangle" -> "§cUsage: /customblock givetriangle <black|yellow|green>";
            case "clearallfaces" -> "§cUsage: /customblock clearallfaces <id>";
            default -> "§cUsage: /customblock help";
        }));
        return 0;
    }
    
    private static class_2561 notFound(final String id) {
        return (class_2561)class_2561.method_43470("§c'" + id + "' not found. Use /customblock list");
    }
    
    private static String sanitize(final String id) {
        return id.toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }
    
    private static byte[] download(final String url) throws IOException, InterruptedException {
        final HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "CustomBlocksMod/1.0").timeout(Duration.ofSeconds(15L)).build();
        final HttpResponse<byte[]> res = CustomBlockCommand.HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) {
            throw new IOException("HTTP " + res.statusCode());
        }
        final byte[] body = res.body();
        if (body.length > 10485760) {
            throw new IOException("Image too large (max 10MB, got " + body.length / 1024 + "KB)");
        }
        return body;
    }
    
    private static void thread(final Runnable r) {
        final Thread t = new Thread(r, "CB-Download");
        t.setDaemon(true);
        t.start();
    }
    
    static {
        BLOCK_SUGGESTIONS = ((ctx, builder) -> {
            for (final String id : SlotManager.allCustomIds()) {
                builder.suggest(id);
            }
            return builder.buildFuture();
        });
        SOUND_SUGGESTIONS = ((ctx, builder) -> {
            for (final String s : new String[] { "stone", "wood", "grass", "metal", "glass", "sand", "wool" }) {
                builder.suggest(s);
            }
            return builder.buildFuture();
        });
        FACE_SUGGESTIONS = ((ctx, builder) -> {
            for (final String f : SlotManager.FACE_KEYS) {
                builder.suggest(f);
            }
            return builder.buildFuture();
        });
        HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10L)).build();
    }
}
