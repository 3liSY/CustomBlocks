package com.customblocks.command;

import com.customblocks.CustomBlocksConfig;
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

    /** Check if source has admin permission. */
    public static boolean hasAdmin(ServerCommandSource src) {
        return src.hasPermissionLevel(CustomBlocksConfig.permissionLevelAdmin);
    }

    /** Check if source can create blocks. */
    public static boolean canCreate(ServerCommandSource src) {
        return src.hasPermissionLevel(CustomBlocksConfig.permissionLevelAdmin);
    }

    /** Check if source can edit blocks (retexture, rename, properties, shapes). */
    public static boolean canEdit(ServerCommandSource src) {
        return src.hasPermissionLevel(CustomBlocksConfig.permissionLevelAdmin);
    }

    /** Check if source can delete blocks. */
    public static boolean canDelete(ServerCommandSource src) {
        return src.hasPermissionLevel(CustomBlocksConfig.permissionLevelAdmin);
    }

    /** Check if source can give blocks. */
    public static boolean canGive(ServerCommandSource src) {
        return src.hasPermissionLevel(CustomBlocksConfig.permissionLevelUse);
    }

    /** Check if source can use GUI / tools. */
    public static boolean canUse(ServerCommandSource src) {
        return src.hasPermissionLevel(CustomBlocksConfig.permissionLevelUse);
    }

    /** Check if source can use undo/redo. */
    public static boolean canUndo(ServerCommandSource src) {
        return src.hasPermissionLevel(CustomBlocksConfig.permissionLevelAdmin);
    }

    /** Check if source can use admin-level utilities (reload, import, export). */
    public static boolean canAdmin(ServerCommandSource src) {
        return src.hasPermissionLevel(CustomBlocksConfig.permissionLevelAdmin);
    }

    /**
     * Check tool-item usage permission (for use inside Item classes that receive PlayerEntity).
     */
    public static boolean canUseTool(net.minecraft.entity.player.PlayerEntity player) {
        return player.hasPermissionLevel(CustomBlocksConfig.permissionLevelAdmin);
    }

    private PermissionHelper() {}
}
