package com.customblocks.assistant;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySetHeadYawS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import java.util.UUID;

/**
 * CustomBlocks Assistant: A specialized FakePlayer that follows management directives.
 */
public class AssistantEntity extends ServerPlayerEntity {

    public static final UUID ASSISTANT_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    
    public AssistantEntity(MinecraftServer server, ServerWorld world, GameProfile profile) {
        super(server, world, profile, net.minecraft.network.packet.c2s.common.SyncedClientOptions.createDefault());
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        // Assistant is immortal
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        // Custom logic for following and scanning will go in AssistantManager
    }

    /** Broadcasts position updates to all players to ensure smooth movement. */
    public void syncPosition() {
        if (this.getWorld() instanceof ServerWorld sw) {
            sw.getServer().getPlayerManager().broadcastAll(new EntityPositionS2CPacket(this));
            sw.getServer().getPlayerManager().broadcastAll(new EntitySetHeadYawS2CPacket(this, (byte) (this.headYaw * 256 / 360)));
        }
    }
}
