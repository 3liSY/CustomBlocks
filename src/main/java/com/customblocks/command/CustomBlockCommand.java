package com.customblocks.command;

import com.customblocks.CustomBlocksMod;
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
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;

public class CustomBlockCommand {

    private static final SuggestionProvider<ServerCommandSource> BLOCK_SUGGESTIONS =
            (ctx, builder) -> {
                for (String id : SlotManager.allCustomIds()) builder.suggest(id);
                return builder.buildFuture();
            };

    private static final SuggestionProvider<ServerCommandSource> SOUND_SUGGESTIONS =
            (ctx, builder) -> {
                for (String s : new String[]{"stone","wood","grass","metal","glass","sand","wool"})
                    builder.suggest(s);
                return builder.buildFuture();
            };



    private static final SuggestionProvider<ServerCommandSource> FACE_SUGGESTIONS =
            (ctx, builder) -> {
                for (String f : SlotManager.FACE_KEYS) builder.suggest(f);
                return builder.buildFuture();
            };
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, reg, env) -> {
            // Build the command tree once, register under both /customblock and /cb
            var tree = CommandManager.literal("customblock")
                .requires(src -> src.hasPermissionLevel(2))

                // ── createurl <id> <name> <url> ──────────────────────────────
                .then(CommandManager.literal("createurl")
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(ctx -> cmdCreate(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "id"),
                                    StringArgumentType.getString(ctx, "name").replace("_", " "),
                                    StringArgumentType.getString(ctx, "url").trim()))
                            )
                        )
                    )
                )

                // ── delete <id> ──────────────────────────────────────────────
                .then(CommandManager.literal("delete")
                    .executes(ctx -> usage(ctx.getSource(), "delete"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdDelete(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id")))
                    )
                )

                // ── rename <id> <newname> ────────────────────────────────────
                .then(CommandManager.literal("rename")
                    .executes(ctx -> usage(ctx.getSource(), "rename"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("newname", StringArgumentType.greedyString())
                            .executes(ctx -> cmdRename(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "newname").replace("_", " ")))
                        )
                    )
                )

                // ── retexture <id> <url> ─────────────────────────────────────
                .then(CommandManager.literal("retexture")
                    .executes(ctx -> usage(ctx.getSource(), "retexture"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("url", StringArgumentType.greedyString())
                            .executes(ctx -> cmdRetexture(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "url").trim()))
                        )
                    )
                )

                // ── give <id> [amount] [player] ─────────────────────────────
                .then(CommandManager.literal("give")
                    .executes(ctx -> usage(ctx.getSource(), "give"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdGive(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id"), 1, null))
                        .then(CommandManager.argument("amount", IntegerArgumentType.integer(1, 64))
                            .executes(ctx -> cmdGive(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                IntegerArgumentType.getInteger(ctx, "amount"), null))
                            .then(CommandManager.argument("player", EntityArgumentType.players())
                                .executes(ctx -> cmdGive(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "id"),
                                    IntegerArgumentType.getInteger(ctx, "amount"),
                                    EntityArgumentType.getPlayers(ctx, "player"))))
                        )
                        .then(CommandManager.argument("player", EntityArgumentType.players())
                            .executes(ctx -> cmdGive(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"), 1,
                                EntityArgumentType.getPlayers(ctx, "player")))
                        )
                    )
                )

                // ── setglow <id> <0-15> ──────────────────────────────────────
                .then(CommandManager.literal("setglow")
                    .executes(ctx -> usage(ctx.getSource(), "setglow"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("level", IntegerArgumentType.integer(0, 15))
                            .executes(ctx -> cmdSetGlow(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                IntegerArgumentType.getInteger(ctx, "level")))
                        )
                    )
                )

                // ── sethardness <id> <value> ─────────────────────────────────
                .then(CommandManager.literal("sethardness")
                    .executes(ctx -> usage(ctx.getSource(), "sethardness"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("hardness", FloatArgumentType.floatArg(-1f, 50f))
                            .executes(ctx -> cmdSetHardness(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                FloatArgumentType.getFloat(ctx, "hardness")))
                        )
                    )
                )

                // ── setsound <id> <type> ─────────────────────────────────────
                .then(CommandManager.literal("setsound")
                    .executes(ctx -> usage(ctx.getSource(), "setsound"))
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("type", StringArgumentType.word())
                            .suggests(SOUND_SUGGESTIONS)
                            .executes(ctx -> cmdSetSound(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "type")))
                        )
                    )
                )

                // ── settabicon <url> ─────────────────────────────────────────
                .then(CommandManager.literal("settabicon")
                    .executes(ctx -> usage(ctx.getSource(), "settabicon"))
                    .then(CommandManager.argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> cmdSetTabIcon(ctx.getSource(),
                            StringArgumentType.getString(ctx, "url").trim()))
                    )
                )


                // ── per-face texture commands ─────────────────────────────────────────
                .then(CommandManager.literal("settopface")
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("url", StringArgumentType.greedyString())
                            .executes(ctx -> cmdSetFace(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"), "top",
                                StringArgumentType.getString(ctx, "url").trim())))))

                .then(CommandManager.literal("setbottomface")
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("url", StringArgumentType.greedyString())
                            .executes(ctx -> cmdSetFace(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"), "bottom",
                                StringArgumentType.getString(ctx, "url").trim())))))

                .then(CommandManager.literal("setnorthface")
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("url", StringArgumentType.greedyString())
                            .executes(ctx -> cmdSetFace(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"), "north",
                                StringArgumentType.getString(ctx, "url").trim())))))

                .then(CommandManager.literal("setsouthface")
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("url", StringArgumentType.greedyString())
                            .executes(ctx -> cmdSetFace(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"), "south",
                                StringArgumentType.getString(ctx, "url").trim())))))

                .then(CommandManager.literal("seteastface")
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("url", StringArgumentType.greedyString())
                            .executes(ctx -> cmdSetFace(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"), "east",
                                StringArgumentType.getString(ctx, "url").trim())))))

                .then(CommandManager.literal("setwestface")
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("url", StringArgumentType.greedyString())
                            .executes(ctx -> cmdSetFace(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"), "west",
                                StringArgumentType.getString(ctx, "url").trim())))))

                .then(CommandManager.literal("clearface")
                    .executes(ctx -> usage(ctx.getSource(), "clearface"))
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .then(CommandManager.argument("face", StringArgumentType.word())
                            .suggests(FACE_SUGGESTIONS)
                            .executes(ctx -> cmdClearFace(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "face"))))))

                // ── givesquare <black|yellow|green> ──────────────────────────────────────
                .then(CommandManager.literal("givesquare")
                    .executes(ctx -> usage(ctx.getSource(), "givesquare"))
                    .then(CommandManager.argument("color", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            builder.suggest("black"); builder.suggest("yellow"); builder.suggest("green");
                            return builder.buildFuture();
                        })
                        .executes(ctx -> cmdGiveSquare(ctx.getSource(),
                            StringArgumentType.getString(ctx, "color")))))

                .then(CommandManager.literal("clearallfaces")
                    .executes(ctx -> usage(ctx.getSource(), "clearallfaces"))
                    .then(CommandManager.argument("id", StringArgumentType.word()).suggests(BLOCK_SUGGESTIONS)
                        .executes(ctx -> cmdClearAllFaces(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id")))))

                // ── export ───────────────────────────────────────────────────
                .then(CommandManager.literal("export")
                    .executes(ctx -> cmdExport(ctx.getSource()))
                )

                // ── importfolder ─────────────────────────────────────────────
                // Reads all PNGs from config/customblocks/import/ on the server.
                // Filename becomes the block ID (e.g. green_a.png → id=green_a, name=Green A)
                .then(CommandManager.literal("importfolder")
                    .executes(ctx -> cmdImportFolder(ctx.getSource()))
                )

                // ── list ─────────────────────────────────────────────────────
                .then(CommandManager.literal("list")
                    .executes(ctx -> cmdList(ctx.getSource()))
                )

                // ── help ─────────────────────────────────────────────────────
                .then(CommandManager.literal("help")
                    .executes(ctx -> cmdHelp(ctx.getSource()))
                )

                // ── colorchanger — give all 3 squares at once or one color ──────────────
                .then(CommandManager.literal("colorchanger")
                    .executes(ctx -> cmdColorChangerAll(ctx.getSource()))
                    .then(CommandManager.argument("color", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            builder.suggest("black"); builder.suggest("yellow"); builder.suggest("green");
                            return builder.buildFuture();
                        })
                        .executes(ctx -> cmdGiveSquare(ctx.getSource(),
                            StringArgumentType.getString(ctx, "color")))))
            ;

            // Register under /customblock
            dispatcher.register(tree);

            // Register under /cb as a full alias (same tree, different name)
            dispatcher.register(CommandManager.literal("cb")
                .requires(src -> src.hasPermissionLevel(2))
                .redirect(dispatcher.getRoot().getChild("customblock")));
        });
    }

    // ── Implementations ───────────────────────────────────────────────────────

    private static int cmdCreate(ServerCommandSource src, String rawId, String name, String url) {
        String id = sanitize(rawId);
        if (id.isEmpty()) { src.sendError(Text.literal("§cInvalid ID.")); return 0; }
        if (SlotManager.hasId(id)) {
            src.sendError(Text.literal("§c'" + id + "' already exists.")); return 0;
        }
        if (SlotManager.freeSlots() == 0) {
            src.sendError(Text.literal("§cAll " + SlotManager.MAX_SLOTS + " slots are full!")); return 0;
        }
        src.sendMessage(Text.literal("§e[CustomBlocks] Downloading..."));
        MinecraftServer server = src.getServer();
        final String fId = id, fName = name;
        thread(() -> {
            try {
                byte[] bytes = download(url);
                server.execute(() -> {
                    SlotManager.SlotData d = SlotManager.assign(fId, fName, bytes);
                    if (d == null) { src.sendError(Text.literal("§cNo free slots!")); return; }
                    SlotManager.saveAll();
                    CustomBlocksMod.broadcastUpdate(server,
                        new SlotUpdatePayload("add", d.index, fId, fName, bytes,
                                d.lightLevel, d.hardness, d.soundType));
                    src.sendMessage(Text.literal("§a[CustomBlocks] '" + fName + "' created! §7(slot " + d.index + ")"));
                });
            } catch (Exception e) {
                server.execute(() -> src.sendError(Text.literal("§c[CustomBlocks] Download failed: " + e.getMessage())));
            }
        });
        return 1;
    }

    private static int cmdDelete(ServerCommandSource src, String id) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        SlotManager.SlotData d = SlotManager.getById(id);
        SlotManager.remove(id);
        SlotManager.saveAll();
        CustomBlocksMod.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("remove", d.index, id, null, null, 0, 0, "stone"));
        src.sendMessage(Text.literal("§a[CustomBlocks] '" + id + "' deleted."));
        return 1;
    }

    private static int cmdRename(ServerCommandSource src, String id, String newName) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        SlotManager.SlotData d = SlotManager.getById(id);
        SlotManager.rename(id, newName);
        SlotManager.saveAll();
        CustomBlocksMod.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("rename", d.index, id, newName, null, 0, 0, "stone"));
        src.sendMessage(Text.literal("§a[CustomBlocks] Renamed to '" + newName + "'."));
        return 1;
    }

    private static int cmdRetexture(ServerCommandSource src, String id, String url) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        src.sendMessage(Text.literal("§e[CustomBlocks] Downloading texture..."));
        MinecraftServer server = src.getServer();
        final String fId = id;
        thread(() -> {
            try {
                byte[] bytes = download(url);
                server.execute(() -> {
                    SlotManager.SlotData d = SlotManager.getById(fId);
                    if (d == null) { src.sendError(notFound(fId)); return; }
                    SlotManager.updateTexture(fId, bytes);
                    SlotManager.saveAll();
                    CustomBlocksMod.broadcastUpdate(server,
                        new SlotUpdatePayload("retexture", d.index, fId, null, bytes,
                                d.lightLevel, d.hardness, d.soundType));
                    src.sendMessage(Text.literal("§a[CustomBlocks] Texture updated for '" + fId + "'."));
                });
            } catch (Exception e) {
                server.execute(() -> src.sendError(Text.literal("§c[CustomBlocks] Download failed: " + e.getMessage())));
            }
        });
        return 1;
    }

    private static int cmdGive(ServerCommandSource src, String id, int amount,
                                Collection<ServerPlayerEntity> targets) {
        SlotManager.SlotData d = SlotManager.getById(id);
        if (d == null) { src.sendError(notFound(id)); return 0; }
        SlotBlock.SlotItem item = CustomBlocksMod.SLOT_ITEMS[d.index];
        ItemStack stack = new ItemStack(item, Math.max(1, Math.min(64, amount)));
        if (targets == null || targets.isEmpty()) {
            try {
                ServerPlayerEntity self = src.getPlayerOrThrow();
                self.getInventory().insertStack(stack.copy());
                src.sendMessage(Text.literal("§a[CustomBlocks] Given " + amount + "x '" + d.displayName + "' to you."));
            } catch (Exception ex) {
                src.sendError(Text.literal("§cRun as a player or specify a target."));
            }
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
        SlotManager.SlotData d = SlotManager.getById(id);
        SlotManager.setLightLevel(id, level);
        SlotManager.saveAll();
        CustomBlocksMod.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("setprop", d.index, id, null, null,
                    level, d.hardness, d.soundType));
        src.sendMessage(Text.literal("§a[CustomBlocks] '" + id + "' glow set to " + level + "."));
        return 1;
    }

    private static int cmdSetHardness(ServerCommandSource src, String id, float val) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        SlotManager.SlotData d = SlotManager.getById(id);
        SlotManager.setHardness(id, val);
        SlotManager.saveAll();
        String label = val < 0 ? "Unbreakable" : val == 0 ? "Instant break" : String.valueOf(val);
        CustomBlocksMod.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("setprop", d.index, id, null, null,
                    d.lightLevel, val, d.soundType));
        src.sendMessage(Text.literal("§a[CustomBlocks] '" + id + "' hardness: " + label + "."));
        return 1;
    }

    private static int cmdSetSound(ServerCommandSource src, String id, String type) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        String[] valid = {"stone","wood","grass","metal","glass","sand","wool"};
        boolean ok = false;
        for (String v : valid) if (v.equals(type)) { ok = true; break; }
        if (!ok) {
            src.sendError(Text.literal("§cValid sounds: stone, wood, grass, metal, glass, sand, wool"));
            return 0;
        }
        SlotManager.SlotData d = SlotManager.getById(id);
        SlotManager.setSoundType(id, type);
        SlotManager.saveAll();
        CustomBlocksMod.broadcastUpdate(src.getServer(),
            new SlotUpdatePayload("setprop", d.index, id, null, null,
                    d.lightLevel, d.hardness, type));
        src.sendMessage(Text.literal("§a[CustomBlocks] '" + id + "' sound: " + type + "."));
        return 1;
    }

    private static int cmdSetTabIcon(ServerCommandSource src, String url) {
        src.sendMessage(Text.literal("§e[CustomBlocks] Downloading tab icon..."));
        MinecraftServer server = src.getServer();
        thread(() -> {
            try {
                byte[] bytes = download(url);
                server.execute(() -> {
                    // Assign or update the reserved "tab_icon" slot
                    SlotManager.setTabIconTexture(bytes);
                    if (!SlotManager.hasId("tab_icon")) {
                        SlotManager.assign("tab_icon", "Tab Icon", bytes);
                    } else {
                        SlotManager.updateTexture("tab_icon", bytes);
                    }
                    SlotManager.saveAll();
                    // Broadcast as both tabicon (for the texture) and add (for the slot)
                    SlotManager.SlotData d = SlotManager.getById("tab_icon");
                    if (d != null) {
                        CustomBlocksMod.broadcastUpdate(server,
                            new SlotUpdatePayload("add", d.index, "tab_icon", "Tab Icon",
                                bytes, 0, 1.5f, "stone"));
                    }
                    CustomBlocksMod.broadcastUpdate(server,
                        new SlotUpdatePayload("tabicon", -1, null, null, bytes, 0, 0, "stone"));
                    src.sendMessage(Text.literal("§a[CustomBlocks] Tab icon updated!"));
                });
            } catch (Exception e) {
                server.execute(() -> src.sendError(Text.literal("§c[CustomBlocks] Download failed: " + e.getMessage())));
            }
        });
        return 1;
    }

    private static int cmdImportFolder(ServerCommandSource src) {
        File importDir = new File("config/customblocks/import");
        if (!importDir.exists()) {
            importDir.mkdirs();
            src.sendMessage(Text.literal("\u00a7e[CustomBlocks] Created import folder: \u00a7fconfig/customblocks/import/"));
            src.sendMessage(Text.literal("\u00a77Drop PNG files in there, then run /customblock importfolder again."));
            src.sendMessage(Text.literal("\u00a77Filename becomes block ID (e.g. green_a.png = id green_a, name Green A)"));
            return 1;
        }

        File[] allPngs = importDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".png"));
        if (allPngs == null || allPngs.length == 0) {
            src.sendMessage(Text.literal("\u00a7c[CustomBlocks] No PNG files found in config/customblocks/import/"));
            return 0;
        }

        java.util.Arrays.sort(allPngs, java.util.Comparator.comparing(File::getName));

        int free = SlotManager.freeSlots();
        if (free == 0) { src.sendError(Text.literal("\u00a7cAll " + SlotManager.MAX_SLOTS + " slots are full!")); return 0; }

        src.sendMessage(Text.literal("\u00a7e[CustomBlocks] Found " + allPngs.length + " PNG(s), " + free + " slots free. Importing..."));

        MinecraftServer server = src.getServer();

        thread(() -> {
            java.util.List<String[]> toAdd      = new java.util.ArrayList<>(); // [id, name, (bytes stored separately)]
            java.util.List<byte[]>   toAddBytes = new java.util.ArrayList<>();
            java.util.List<String>   skipped    = new java.util.ArrayList<>();
            java.util.List<String>   failed     = new java.util.ArrayList<>();

            for (File png : allPngs) {
                String rawName     = png.getName().replaceAll("(?i)\\.(png|jpg|jpeg)$", "");
                String id          = rawName.toLowerCase().replaceAll("[^a-z0-9_]", "_");
                String displayName = java.util.Arrays.stream(rawName.replace("_", " ").split(" "))
                    .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase())
                    .collect(java.util.stream.Collectors.joining(" "));

                if (SlotManager.hasId(id)) { skipped.add(id); continue; }
                if (toAdd.size() >= free)  { failed.add(id + "(no free slot)"); continue; }

                try {
                    byte[] bytes = java.nio.file.Files.readAllBytes(png.toPath());
                    toAdd.add(new String[]{id, displayName});
                    toAddBytes.add(bytes);
                } catch (Exception e) {
                    failed.add(id + "(read error)");
                }
            }

            server.execute(() -> {
                int created = 0;
                for (int i = 0; i < toAdd.size(); i++) {
                    String id   = toAdd.get(i)[0];
                    String name = toAdd.get(i)[1];
                    byte[] b    = toAddBytes.get(i);
                    SlotManager.SlotData d = SlotManager.assign(id, name, b);
                    if (d == null) { failed.add(id + "(slot full)"); continue; }
                    CustomBlocksMod.broadcastUpdate(server,
                        new SlotUpdatePayload("add", d.index, id, name, b,
                                d.lightLevel, d.hardness, d.soundType));
                    created++;
                }
                if (created > 0) SlotManager.saveAll();

                StringBuilder msg = new StringBuilder("\u00a7a[CustomBlocks] Done! \u00a7f" + created + " created");
                if (!skipped.isEmpty()) msg.append("\u00a77, ").append(skipped.size()).append(" skipped");
                if (!failed.isEmpty())  msg.append("\u00a7c, ").append(failed.size()).append(" failed");
                src.sendMessage(Text.literal(msg.toString()));
                src.sendMessage(Text.literal("\u00a77Slots: " + SlotManager.usedSlots() + " / " + SlotManager.MAX_SLOTS + " used"));
                if (!failed.isEmpty())
                    src.sendMessage(Text.literal("\u00a7cFailed: " + String.join(", ", failed)));
            });
        });
        return 1;
    }

    private static int cmdExport(ServerCommandSource src) {
        File dir = new File("config/customblocks");
        dir.mkdirs();
        File out = new File(dir, "export.json");
        try {
            com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (SlotManager.SlotData d : SlotManager.allSlots()) {
                com.google.gson.JsonObject e = new com.google.gson.JsonObject();
                e.addProperty("id",          d.customId);
                e.addProperty("displayName", d.displayName);
                e.addProperty("slot",        d.index);
                e.addProperty("lightLevel",  d.lightLevel);
                e.addProperty("hardness",    d.hardness);
                e.addProperty("soundType",   d.soundType);
                arr.add(e);
            }
            root.add("blocks", arr);
            root.addProperty("totalBlocks", SlotManager.usedSlots());
            root.addProperty("freeSlots",   SlotManager.freeSlots());
            try (java.io.FileWriter fw = new java.io.FileWriter(out, java.nio.charset.StandardCharsets.UTF_8)) {
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root, fw);
            }
            src.sendMessage(Text.literal("§a[CustomBlocks] Exported " + SlotManager.usedSlots() + " blocks to config/customblocks/export.json"));
        } catch (Exception e) {
            src.sendError(Text.literal("§cExport failed: " + e.getMessage()));
        }
        return 1;
    }

    private static int cmdList(ServerCommandSource src) {
        if (SlotManager.usedSlots() == 0) {
            src.sendMessage(Text.literal("§7[CustomBlocks] No blocks. " + SlotManager.freeSlots() + " slots free."));
            return 1;
        }
        src.sendMessage(Text.literal("§e[CustomBlocks] §f" + SlotManager.usedSlots() + " block(s) | §7" + SlotManager.freeSlots() + " free:"));
        for (SlotManager.SlotData d : SlotManager.allSlots()) {
            String glow  = d.lightLevel > 0 ? " §6*" + d.lightLevel : "";
            String hard  = d.hardness < 0 ? " §c∞" : "";
            src.sendMessage(Text.literal("  §f" + d.customId + " §7→ '" + d.displayName + "'" + glow + hard + " §8(slot " + d.index + ")"));
        }
        return 1;
    }

    private static int cmdHelp(ServerCommandSource src) {
        src.sendMessage(Text.literal("§e══ Custom Blocks Help ══"));
        src.sendMessage(Text.literal("§aPress §fB §ato open the visual GUI!"));
        src.sendMessage(Text.literal("§f/customblock createurl <id> <n> <url>  §7create from image"));
        src.sendMessage(Text.literal("§f/customblock delete <id>  §7delete a block"));
        src.sendMessage(Text.literal("§f/customblock rename <id> <newname>  §7rename"));
        src.sendMessage(Text.literal("§f/customblock retexture <id> <url>  §7change texture"));
        src.sendMessage(Text.literal("§f/customblock give <id> [amount] [player]  §7give block (amount 1-64)"));
        src.sendMessage(Text.literal("§f/customblock setglow <id> <0-15>  §7light emission"));
        src.sendMessage(Text.literal("§f/customblock sethardness <id> <val>  §7mining speed (−1=unbreakable)"));
        src.sendMessage(Text.literal("§f/customblock setsound <id> <stone|wood|metal|glass|grass|sand>"));
        src.sendMessage(Text.literal("§f/customblock settabicon <url>  §7set tab icon"));
        src.sendMessage(Text.literal("§f/customblock set[top|bottom|north|south|east|west]face <id> <url>  §7set a face"));
        src.sendMessage(Text.literal("§f/customblock givesquare <black|yellow|green>  §7get a color-swap square item"));
        src.sendMessage(Text.literal("§f/customblock colorchanger [color]  §7give all 3 squares (or one color)"));
        src.sendMessage(Text.literal("§7Tip: use §f/cb§7 as a short alias for §f/customblock§7!"));
        src.sendMessage(Text.literal("§f/customblock clearface <id> <face>  §7revert one face to default"));
        src.sendMessage(Text.literal("§f/customblock clearallfaces <id>  §7revert all faces to default"));
        src.sendMessage(Text.literal("§f/customblock importfolder  §7bulk-import PNGs from config/customblocks/import/"));
        src.sendMessage(Text.literal("§f/customblock export  §7export block list to config/customblocks/export.json"));
        src.sendMessage(Text.literal("§f/customblock list  §7list all blocks"));
        src.sendMessage(Text.literal("§7No restarts needed for any command!"));
        return 1;
    }


    private static int cmdSetFace(ServerCommandSource src, String id, String face, String url) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        src.sendMessage(Text.literal("§e[CustomBlocks] Downloading " + face + " face texture..."));
        MinecraftServer server = src.getServer();
        thread(() -> {
            try {
                byte[] bytes = download(url);
                server.execute(() -> {
                    SlotManager.SlotData d = SlotManager.getById(id);
                    if (d == null) { src.sendError(Text.literal("§c[CustomBlocks] '" + id + "' was deleted before texture arrived.")); return; }
                    SlotManager.setFaceTexture(id, face, bytes);
                    SlotManager.saveAll();
                    src.sendMessage(Text.literal("§a[CustomBlocks] " + face + " face set on '" + id + "'."));
                });
            } catch (Exception e) {
                server.execute(() -> src.sendError(Text.literal("§c[CustomBlocks] Download failed: " + e.getMessage())));
            }
        });
        return 1;
    }

    private static int cmdClearFace(ServerCommandSource src, String id, String face) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        if (!SlotManager.FACE_KEYS.contains(face)) {
            src.sendError(Text.literal("§cValid faces: top bottom north south east west")); return 0;
        }
        SlotManager.SlotData d = SlotManager.getById(id); // fetch BEFORE mutation
        if (d == null) { src.sendError(notFound(id)); return 0; }
        SlotManager.clearFaceTexture(id, face);
        SlotManager.saveAll();
        src.sendMessage(Text.literal("§a[CustomBlocks] " + face + " face cleared on '" + id + "' (reverted to default)."));
        return 1;
    }

    private static int cmdClearAllFaces(ServerCommandSource src, String id) {
        if (!SlotManager.hasId(id)) { src.sendError(notFound(id)); return 0; }
        SlotManager.SlotData d = SlotManager.getById(id); // fetch BEFORE mutation
        if (d == null) { src.sendError(notFound(id)); return 0; }
        SlotManager.clearAllFaces(id);
        SlotManager.saveAll();
        src.sendMessage(Text.literal("§a[CustomBlocks] All face overrides cleared on '" + id + "'."));
        return 1;
    }

    private static int cmdColorChangerAll(ServerCommandSource src) {
        // Give all 3 color squares silently, then send one combined message
        int given = 0;
        for (String col : new String[]{"black", "yellow", "green"}) {
            given += cmdGiveSquareSilent(src, col);
        }
        if (given > 0)
            src.sendMessage(Text.literal("§a[CustomBlocks] Given all 3 color squares! Right-click any Custom Block to swap its color."));
        else
            src.sendError(Text.literal("§cCould not give squares. Run as a player."));
        return given > 0 ? 1 : 0;
    }

    /** Like cmdGiveSquare but without the success message — used by colorchanger. */
    private static int cmdGiveSquareSilent(ServerCommandSource src, String color) {
        String normalized = color.toLowerCase().trim();
        net.minecraft.util.Identifier sqId =
            net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, normalized + "_square");
        net.minecraft.item.Item sqItem = net.minecraft.registry.Registries.ITEM.get(sqId);
        if (sqItem == null || sqItem == net.minecraft.item.Items.AIR) return 0;
        try {
            ServerPlayerEntity self = src.getPlayerOrThrow();
            self.getInventory().insertStack(new ItemStack(sqItem, 1));
            return 1;
        } catch (Exception ex) { return 0; }
    }

    private static int cmdGiveSquare(ServerCommandSource src, String color) {
        String normalized = color.toLowerCase().trim();
        if (!normalized.equals("black") && !normalized.equals("yellow") && !normalized.equals("green")) {
            src.sendError(Text.literal("§cValid colors: black, yellow, green")); return 0;
        }
        net.minecraft.util.Identifier sqId =
            net.minecraft.util.Identifier.of(CustomBlocksMod.MOD_ID, normalized + "_square");
        net.minecraft.item.Item sqItem = net.minecraft.registry.Registries.ITEM.get(sqId);
        if (sqItem == null || sqItem == net.minecraft.item.Items.AIR) {
            src.sendError(Text.literal("§cSquare item not found — is the mod loaded?")); return 0;
        }
        try {
            ServerPlayerEntity self = src.getPlayerOrThrow();
            self.getInventory().insertStack(new ItemStack(sqItem, 1));
            String label = Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
            src.sendMessage(Text.literal("§a[CustomBlocks] Given " + label + " Square. Right-click a Custom Block to swap color!"));
        } catch (Exception ex) {
            src.sendError(Text.literal("§cRun as a player.")); return 0;
        }
        return 1;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int usage(ServerCommandSource src, String cmd) {
        src.sendError(Text.literal(switch (cmd) {
            case "delete"      -> "§cUsage: /customblock delete <id>";
            case "rename"      -> "§cUsage: /customblock rename <id> <newname>";
            case "retexture"   -> "§cUsage: /customblock retexture <id> <url>";
            case "give"        -> "§cUsage: /customblock give <id> [amount 1-64] [player]";
            case "setglow"     -> "§cUsage: /customblock setglow <id> <0-15>";
            case "sethardness" -> "§cUsage: /customblock sethardness <id> <-1 to 50>  (-1=unbreakable)";
            case "setsound"    -> "§cUsage: /customblock setsound <id> <stone|wood|grass|metal|glass|sand|wool>";
            case "settabicon"  -> "§cUsage: /customblock settabicon <url>";
            case "clearface"   -> "§cUsage: /customblock clearface <id> <top|bottom|north|south|east|west>";
            case "givesquare"  -> "§cUsage: /customblock givesquare <black|yellow|green>";
            case "clearallfaces"-> "§cUsage: /customblock clearallfaces <id>";
            default -> "§cUsage: /customblock help";
        }));
        return 0;
    }

    private static Text notFound(String id) {
        return Text.literal("§c'" + id + "' not found. Use /customblock list");
    }

    private static String sanitize(String id) {
        return id.toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }

    private static final java.net.http.HttpClient HTTP_CLIENT =
            java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

    private static byte[] download(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "CustomBlocksMod/1.0")
                .timeout(Duration.ofSeconds(15)).build();
        HttpResponse<byte[]> res = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200)
            throw new IOException("HTTP " + res.statusCode());
        byte[] body = res.body();
        if (body.length > 10_485_760)
            throw new IOException("Image too large (max 10MB, got " + (body.length / 1024) + "KB)");
        return body;
    }

    private static void thread(Runnable r) {
        Thread t = new Thread(r, "CB-Download");
        t.setDaemon(true);
        t.start();
    }
}
