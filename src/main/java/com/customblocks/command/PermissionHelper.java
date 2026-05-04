package com.customblocks.command;

import com.customblocks.CustomBlocksConfig;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.command.ServerCommandSource;

/**
 * Granular permission checks.
 * <p>
 * Permission nodes:
 * <ul>
 *     <li>{@code customblocks.admin}  — create, delete, edit, reload, import/export</li>
 *     <li>{@code customblocks.create} — create blocks</li>
 *     <li>{@code customblocks.edit}   — retexture, rename, set properties, shapes, faces</li>
 *     <li>{@code customblocks.give}   — give blocks to self or others</li>
 *     <li>{@code customblocks.use}    — open GUI, use tools</li>
 * </ul>
 * Falls back to OP level from config if no permission mod is present.
 * LuckPerms integration: if Fabric Permissions API is available, checks there first.
 */
public final class PermissionHelper {

    private static final String COMMAND_PREFIX = "customblocks.command.";
    private static final String GUI_PREFIX = "customblocks.gui.";
    private static final String TOOL_PREFIX = "customblocks.tool.";

    public static boolean dynamicCheck(ServerCommandSource src, String node, int fallbackLevel) {
        return Permissions.check(src, node, fallbackLevel);
    }

    public static boolean dynamicCheck(PlayerEntity player, String node, int fallbackLevel) {
        return Permissions.check(player, node, fallbackLevel);
    }

    public static boolean canUseCommand(ServerCommandSource src, String commandName) {
        return dynamicCheck(src, COMMAND_PREFIX + commandName, CustomBlocksConfig.permissionLevelUse);
    }

    public static boolean canAdminCommand(ServerCommandSource src, String commandName) {
        return dynamicCheck(src, COMMAND_PREFIX + commandName, CustomBlocksConfig.permissionLevelAdmin);
    }

    public static boolean canOpenGui(PlayerEntity player, String guiName) {
        return dynamicCheck(player, GUI_PREFIX + guiName, CustomBlocksConfig.permissionLevelUse);
    }

    public static boolean canUseTool(PlayerEntity player, String toolName) {
        return dynamicCheck(player, TOOL_PREFIX + toolName, CustomBlocksConfig.permissionLevelAdmin);
    }

    /** Check if source has admin permission. */
    public static boolean hasAdmin(ServerCommandSource src) {
        return dynamicCheck(src, "customblocks.admin", CustomBlocksConfig.permissionLevelAdmin);
    }

    /** Check if source can create blocks. */
    public static boolean canCreate(ServerCommandSource src) {
        return dynamicCheck(src, "customblocks.create", CustomBlocksConfig.permissionLevelAdmin);
    }

    /** Check if source can edit blocks (retexture, rename, properties, shapes). */
    public static boolean canEdit(ServerCommandSource src) {
        return dynamicCheck(src, "customblocks.edit", CustomBlocksConfig.permissionLevelAdmin);
    }

    /** Check if source can delete blocks. */
    public static boolean canDelete(ServerCommandSource src) {
        return dynamicCheck(src, "customblocks.delete", CustomBlocksConfig.permissionLevelAdmin);
    }

    /** Check if source can give blocks. */
    public static boolean canGive(ServerCommandSource src) {
        return dynamicCheck(src, "customblocks.give", CustomBlocksConfig.permissionLevelUse);
    }

    /** Check if source can use GUI / tools. */
    public static boolean canUse(ServerCommandSource src) {
        return dynamicCheck(src, "customblocks.use", CustomBlocksConfig.permissionLevelUse);
    }

    /** Check if source can use undo/redo. */
    public static boolean canUndo(ServerCommandSource src) {
        return dynamicCheck(src, "customblocks.undo", CustomBlocksConfig.permissionLevelAdmin);
    }

    /** Check if source can use admin-level utilities (reload, import, export). */
    public static boolean canAdmin(ServerCommandSource src) {
        return dynamicCheck(src, "customblocks.admin", CustomBlocksConfig.permissionLevelAdmin);
    }

    /**
     * Check tool-item usage permission (for use inside Item classes that receive PlayerEntity).
     */
    public static boolean canUseTool(PlayerEntity player) {
        return dynamicCheck(player, "customblocks.tool.use", CustomBlocksConfig.permissionLevelAdmin);
    }

    private PermissionHelper() {}
}
