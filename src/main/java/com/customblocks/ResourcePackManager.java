package com.customblocks;

import com.customblocks.network.ResourcePackServer;
import net.minecraft.server.MinecraftServer;

/**
 * Thin façade over {@link ResourcePackServer} for triggering pack rebuilds and player pushes.
 *
 * <p>Previously referenced but never created; added here to resolve all compilation errors
 * that referenced {@code com.customblocks.ResourcePackManager.scheduleRebuild(server)}.
 */
public final class ResourcePackManager {

    private ResourcePackManager() {}

    /**
     * Rebuilds the resource pack ZIP from the current SlotManager state and then pushes
     * the updated pack to all online players via {@link ResourcePackServer#sendUpdateToAllPlayers()}.
     *
     * <p>The rebuild is already asynchronous inside {@link ResourcePackServer#updatePack()}.
     *
     * @param server the current Minecraft server instance (currently unused but kept for API symmetry)
     */
    public static void scheduleRebuild(MinecraftServer server) {
        ResourcePackServer.updatePack();
        // sendUpdateToAllPlayers runs on its own background thread inside ResourcePackServer
        ResourcePackServer.sendUpdateToAllPlayers();
    }
}
