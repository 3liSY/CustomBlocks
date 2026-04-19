package com.customblocks.mixin;

import net.minecraft.server.network.ServerCommonNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin for {@link ServerCommonNetworkHandler} keepalive fields.
 * Used to extend the keepalive grace period for players receiving a large
 * texture drip-feed, preventing false timeout disconnects during the
 * client-side resource pack reload.
 */
@Mixin(ServerCommonNetworkHandler.class)
public interface ServerCommonNetworkHandlerAccessor {

    @Accessor("lastKeepAliveTime")
    void setLastKeepAliveTime(long time);

    @Accessor("waitingForKeepAlive")
    void setWaitingForKeepAlive(boolean waiting);
}
