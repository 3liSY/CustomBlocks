package com.customblocks.mixin;

import com.customblocks.network.NetworkManager;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects at the HEAD of {@link ServerCommonNetworkHandler#baseTick()} to
 * clear {@code waitingForKeepAlive} BEFORE the keepalive timeout check runs.
 * <p>
 * Without this, there is a race:
 * <ol>
 *   <li>{@code baseTick()} sees {@code waitingForKeepAlive == true}</li>
 *   <li>{@code baseTick()} calls {@code disconnect(TIMEOUT_TEXT)}</li>
 *   <li>Our {@code END_SERVER_TICK} handler would clear the flag — too late.</li>
 * </ol>
 * <p>
 * By injecting at HEAD of {@code baseTick()}, we clear the flag at exactly
 * the right moment — before the timeout check, inside the same method.
 */
@Mixin(ServerCommonNetworkHandler.class)
public abstract class ServerKeepAliveGraceMixin {

    @Shadow private boolean waitingForKeepAlive;

    @Shadow protected abstract GameProfile getProfile();

    @Inject(method = "baseTick", at = @At("HEAD"))
    private void customblocks$clearKeepAliveBeforeCheck(CallbackInfo ci) {
        try {
            GameProfile profile = this.getProfile();
            if (profile != null && NetworkManager.isInGracePeriod(profile.getId())) {
                this.waitingForKeepAlive = false;
            }
        } catch (Exception ignored) {
            // Failsafe: never crash the tick loop
        }
    }
}
