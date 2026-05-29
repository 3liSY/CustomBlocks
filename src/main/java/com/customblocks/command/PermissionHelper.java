package com.customblocks.command;

import com.customblocks.CustomBlocksConfig;
import com.customblocks.core.VoiceCatalog;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * Permission gates: when Fabric Permissions API is installed, each {@code check} uses the
 * {@code customblocks.*} node with the matching {@link CustomBlocksConfig#permissionFallback*}
 * field as the numeric fallback level (legacy {@link CustomBlocksConfig#permissionLevelUse} /
 * {@link CustomBlocksConfig#permissionLevelAdmin} seed migration defaults when those fallbacks
 * are absent from disk).
 * Without the API, only vanilla {@link ServerCommandSource#hasPermissionLevel} is used
 * against that same fallback.
 */
public final class PermissionHelper {

    private static boolean permissionsApiLoaded() {
        return FabricLoader.getInstance().isModLoaded("fabric-permissions-api-v0");
    }

    private static boolean check(ServerCommandSource src, String node, int fallbackLevel) {
        if (permissionsApiLoaded()) {
            return me.lucko.fabric.api.permissions.v0.Permissions.check(src, node, fallbackLevel);
        }
        return src.hasPermissionLevel(fallbackLevel);
    }

    public static boolean canUse(ServerCommandSource src) {
        return check(src, "customblocks.use", CustomBlocksConfig.permissionFallbackUse);
    }

    public static boolean canCreate(ServerCommandSource src) {
        return check(src, "customblocks.create", CustomBlocksConfig.permissionFallbackCreate);
    }

    public static boolean canEdit(ServerCommandSource src) {
        return check(src, "customblocks.edit", CustomBlocksConfig.permissionFallbackEdit);
    }

    public static boolean canDelete(ServerCommandSource src) {
        return check(src, "customblocks.delete", CustomBlocksConfig.permissionFallbackDelete);
    }

    public static boolean canBulk(ServerCommandSource src) {
        return check(src, "customblocks.bulk", CustomBlocksConfig.permissionFallbackBulk);
    }

    /**
     * {@code /cb resume} — command may run if any resume target is plausible; each draft kind is
     * re-checked in {@link com.customblocks.gui.GuiManager#resumePendingDraft} before {@code take()}.
     */
    public static boolean canResumeSession(ServerCommandSource src) {
        return canBulk(src) || canEdit(src) || canUse(src) || canCategoryManage(src) || canConfig(src)
            || canPanic(src) || canUndo(src) || canAdmin(src);
    }

    public static boolean canGive(ServerCommandSource src) {
        return check(src, "customblocks.use", CustomBlocksConfig.permissionFallbackUse);
    }

    public static boolean canUndo(ServerCommandSource src) {
        return check(src, "customblocks.edit", CustomBlocksConfig.permissionFallbackUndo);
    }

    public static boolean canAdmin(ServerCommandSource src) {
        return check(src, "customblocks.admin", CustomBlocksConfig.permissionFallbackAdmin);
    }

    public static boolean canConfig(ServerCommandSource src) {
        return check(src, "customblocks.config", CustomBlocksConfig.permissionFallbackConfig);
    }

    public static boolean canFavorite(ServerCommandSource src) {
        return check(src, "customblocks.favorites", CustomBlocksConfig.permissionFallbackFavorites);
    }

    public static boolean canPanic(ServerCommandSource src) {
        return check(src, "customblocks.panic", CustomBlocksConfig.permissionFallbackPanic);
    }

    public static boolean canMarketplacePublish(ServerCommandSource src) {
        return check(src, "customblocks.marketplace.publish", CustomBlocksConfig.permissionFallbackMarketplacePublish);
    }

    /** Phase J / config gate — {@code customblocks.ai}. */
    public static boolean canAi(ServerCommandSource src) {
        return check(src, "customblocks.ai", CustomBlocksConfig.permissionFallbackAi);
    }

    /** Phase S dev console — {@code customblocks.devconsole}. */
    public static boolean canDevConsole(ServerCommandSource src) {
        return check(src, "customblocks.devconsole", CustomBlocksConfig.permissionFallbackDevConsole);
    }

    public static boolean canCategoryView(ServerCommandSource src) {
        return canUse(src);
    }

    public static boolean canCategoryAssign(ServerCommandSource src) {
        return canBulk(src);
    }

    public static boolean canCategoryExport(ServerCommandSource src) {
        return canAdmin(src);
    }

    public static boolean canCategoryManage(ServerCommandSource src) {
        return canAdmin(src);
    }

    public static boolean canViewSpecificCategory(ServerPlayerEntity player, String categoryKey) {
        if (canAdmin(player.getCommandSource())) return true;
        com.customblocks.core.Category cat = com.customblocks.core.CategoryManager.getCategory(categoryKey);
        return cat == null || !cat.hidden();
    }

    public static boolean canAssignToSpecificCategory(ServerPlayerEntity player, String categoryKey) {
        if (canAdmin(player.getCommandSource())) return true;
        com.customblocks.core.Category cat = com.customblocks.core.CategoryManager.getCategory(categoryKey);
        return cat == null || !cat.locked();
    }

    /**
     * Magic wand / chisel tools — same node and vanilla fallback as {@link #canEdit(ServerCommandSource)}.
     */
    public static boolean canUseTool(PlayerEntity player) {
        if (player.getWorld().isClient) return true; // client-side prediction bypass; server still validates
        if (!(player instanceof ServerPlayerEntity sp)) return false;
        return canEdit(sp.getCommandSource());
    }

    /** Action-bar denial when {@link #canUseTool} fails (LuckPerms: {@code customblocks.edit}). */
    public static Text toolPermissionDeniedMessage() {
        return Text.literal(VoiceCatalog.format("cmd.tool_permission_denied"));
    }

    private PermissionHelper() {}
}
