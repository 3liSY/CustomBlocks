package com.customblocks.assistant;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;

import java.util.UUID;

/**
 * The AssistantEntity (The Helper).
 * <p>
 * A "Fake Player" entity that supports standard skins and animations.
 * Does not appear in the Tab list by default and is designed for mod maintenance assistance.
 */
public class AssistantEntity extends net.fabricmc.fabric.api.entity.FakePlayer {

    public AssistantEntity(MinecraftServer server, ServerWorld world, GameProfile profile) {
        super(world, profile);
    }

    @Override
    public boolean isInvulnerableTo(DamageSource damageSource) {
        return true;
    }

    @Override
    public boolean isPushedByFluids() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        // Prevent fall damage/gravity issues if disconnected
        if (this.getY() < -64) {
            this.setNoGravity(true);
        }
    }

    /**
     * Updates the NPC's name and appearance.
     */
    public void refreshIdentity(String name) {
        this.setCustomName(Text.literal(name));
        this.setCustomNameVisible(true);
    }
}
