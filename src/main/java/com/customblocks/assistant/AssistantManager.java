package com.customblocks.assistant;

import com.customblocks.CustomBlocksConfig;
import com.customblocks.CustomBlocksMod;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * Singleton manager for the Assistant (The Helper).
 */
public class AssistantManager {

    private static AssistantEntity helperEntity;
    private static boolean following = false;
    private static UUID followAnchor = null;
    private static BlockPos targetPos = null;

    public static void tick(MinecraftServer server) {
        if (helperEntity == null || !CustomBlocksConfig.helperEnabled) return;

        // --- Hologram Update (Visual feedback) ---
        if (CustomBlocksConfig.helperHologram) {
             String status = following ? "§aFollowing Architect" : (targetPos != null ? "§bHeading to Sector" : "§7Standing Guard");
             helperEntity.setCustomName(Text.literal("§b§l" + CustomBlocksConfig.helperName + "\n§f" + status));
             helperEntity.setCustomNameVisible(true);
        }

        // --- Movement Logic ---
        Vec3d goal = null;
        if (following && followAnchor != null) {
            ServerPlayerEntity target = server.getPlayerManager().getPlayer(followAnchor);
            if (target != null && target.getWorld() == helperEntity.getWorld()) {
                if (helperEntity.getPos().squaredDistanceTo(target.getPos()) > 9.0) {
                    goal = target.getPos();
                }
            }
        } else if (targetPos != null) {
            goal = Vec3d.ofCenter(targetPos);
            if (helperEntity.getPos().squaredDistanceTo(goal) < 1.5) targetPos = null;
        }

        if (goal != null) {
            helperEntity.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, goal);
            double dist = helperEntity.getPos().squaredDistanceTo(goal);
            if (dist > 1024.0) { // Teleport if too far
                 helperEntity.teleport(goal.x, goal.y, goal.z, true);
            } else {
                 // Immersive movement towards goal
                 Vec3d dir = goal.subtract(helperEntity.getPos()).normalize().multiply(0.25);
                 helperEntity.setVelocity(dir);
                 helperEntity.velocityModified = true;
            }
        }
    }

    public static void spawn(MinecraftServer server, ServerWorld world, double x, double y, double z) {
        if (helperEntity != null) helperEntity.discard();

        GameProfile profile = new GameProfile(UUID.randomUUID(), CustomBlocksConfig.helperName);
        helperEntity = new AssistantEntity(server, world, profile);
        helperEntity.refreshIdentity(CustomBlocksConfig.helperName);
        helperEntity.refreshPositionAndAngles(x, y, z, 0, 0);
        
        world.spawnEntity(helperEntity);
        CustomBlocksConfig.helperEnabled = true;
        CustomBlocksConfig.save();
    }

    public static void orderMoveTo(BlockPos pos) {
        targetPos = pos;
        following = false;
        followAnchor = null;
    }

    public static void runSanityScan(ServerPlayerEntity player) {
        if (helperEntity == null) return;
        ServerWorld world = (ServerWorld) helperEntity.getWorld();
        BlockPos center = helperEntity.getBlockPos();
        int radius = 16;
        int brokenCount = 0;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos p = center.add(x, y, z);
                    net.minecraft.block.BlockState state = world.getBlockState(p);
                    if (state.getBlock() instanceof com.customblocks.block.SlotBlock sb) {
                        com.customblocks.core.SlotData d = com.customblocks.core.SlotManager.getByIndex(sb.getSlotIndex());
                        String id = d != null ? d.customId : null;
                        if (id == null || !com.customblocks.core.SlotManager.hasId(id)) {
                            brokenCount++;
                            world.spawnParticles(net.minecraft.particle.ParticleTypes.SMOKE, p.getX()+0.5, p.getY()+0.5, p.getZ()+0.5, 3, 0.1, 0.1, 0.1, 0.02);
                        }
                    }
                }
            }
        }

        if (brokenCount > 0) {
            player.sendMessage(Text.literal("§0§l[§b§lHelper§0§l] §eScan complete. §c" + brokenCount + " §7anomalies detected. Visual markers placed. §e⚠"), false);
        } else {
            player.sendMessage(Text.literal("§0§l[§b§lHelper§0§l] §aArea is clean. All designs are stable. §f✔"), false);
        }
    }

    public static void hide() {
        if (helperEntity != null) {
            helperEntity.discard();
            helperEntity = null;
        }
        CustomBlocksConfig.helperEnabled = false;
        CustomBlocksConfig.save();
    }

    public static boolean handleChatCommand(ServerPlayerEntity player, String message) {
        if (!message.startsWith(".")) return false;
        
        String cmd = message.substring(1).toLowerCase();
        switch (cmd) {
            case "come" -> {
                following = true;
                followAnchor = player.getUuid();
                targetPos = null;
                player.sendMessage(Text.literal("§0§l[§b§lHelper§0§l] §fFollowing your lead, Architect. §a✔"), false);
                return true;
            }
            case "stay" -> {
                following = false;
                targetPos = null;
                player.sendMessage(Text.literal("§0§l[§b§lHelper§0§l] §fStanding guard here. §a✔"), false);
                return true;
            }
            case "status" -> {
                player.sendMessage(Text.literal("§0§l[§b§lHelper§0§l] §fAll systems nominal. §7(Sanity: Optimal)"), false);
                return true;
            }
        }
        return false;
    }

    public static boolean isFollowing() { return following; }
    public static void setFollowing(boolean f, UUID anchor) { following = f; followAnchor = anchor; targetPos = null; }
    public static boolean isSpawned() { return helperEntity != null; }
}
