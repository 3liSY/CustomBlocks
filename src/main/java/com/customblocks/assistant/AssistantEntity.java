package com.customblocks.assistant;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import net.fabricmc.fabric.api.entity.FakePlayer;

import java.util.UUID;

/**
 * The AssistantEntity (The Helper).
 * <p>
 * A "Fake Player" entity used for mod maintenance assistance.
 * Uses Fabric's FakePlayer API for proper lifecycle management.
 * FakePlayer handles all network/connection plumbing internally —
 * do NOT call world.spawnEntity() on this. Use FakePlayer.get() instead.
 */
public class AssistantEntity extends FakePlayer {

    protected AssistantEntity(ServerWorld world, GameProfile profile) {
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
        // Prevent fall damage/gravity issues
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
