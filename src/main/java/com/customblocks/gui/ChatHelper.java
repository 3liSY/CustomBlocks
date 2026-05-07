package com.customblocks.gui;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public class ChatHelper {

    private static final String PREFIX = "§0§l[§b§lCB§0§l]§r ";

    public static void success(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(PREFIX + "§f" + message + " §a✔"), false);
    }
    public static void success(ServerCommandSource src, String message) {
        src.sendMessage(Text.literal(PREFIX + "§f" + message + " §a✔"));
    }

    public static void error(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(PREFIX + "§c" + message + " §4✖"), false);
        GuiManager.logError();
    }
    public static void error(ServerCommandSource src, String message) {
        src.sendError(Text.literal(PREFIX + "§c" + message + " §4✖"));
        GuiManager.logError();
    }

    public static void info(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(PREFIX + "§7" + message + " §e✦"), false);
    }
    public static void info(ServerCommandSource src, String message) {
        src.sendMessage(Text.literal(PREFIX + "§7" + message + " §e✦"));
    }

    public static void warn(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(PREFIX + "§e" + message + " §6⚠"), false);
    }
    public static void warn(ServerCommandSource src, String message) {
        src.sendMessage(Text.literal(PREFIX + "§e" + message + " §6⚠"));
    }
}
