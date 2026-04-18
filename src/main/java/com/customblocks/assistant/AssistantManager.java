package com.customblocks.assistant;

import com.customblocks.CustomBlocksConfig;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.passive.AllayEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Singleton manager for the in-world CustomBlocks assistant AI.
 * Uses a managed Allay so clients render it naturally without FakePlayer packet hacks.
 */
public final class AssistantManager {

    private static final String AI_TAG = "customblocks_ai_assistant";
    private static final double FOLLOW_START_DISTANCE_SQUARED = 9.0D;
    private static final double ARRIVAL_DISTANCE_SQUARED = 1.5D;
    private static final double TELEPORT_DISTANCE_SQUARED = 1024.0D;
    private static final double MOVE_SPEED = 1.5D;
    private static final List<String> STYLES = List.of("Echo", "Amethyst", "Compass", "Royal", "Builder", "Phantom", "Torch");

    private static AllayEntity aiEntity;
    private static boolean following = false;
    private static UUID followAnchor = null;
    private static BlockPos targetPos = null;

    private AssistantManager() {}

    public static void tick(MinecraftServer server) {
        if (aiEntity != null && !aiEntity.isAlive()) {
            resetTracking(false);
        }
        if (aiEntity != null && !CustomBlocksConfig.aiEnabled) {
            hide();
            return;
        }
        if (aiEntity == null || !CustomBlocksConfig.aiEnabled) {
            return;
        }

        refreshAppearance();

        Vec3d goal = resolveGoal(server);
        if (goal == null) {
            stopMovement();
            return;
        }

        aiEntity.setAiDisabled(false);
        aiEntity.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, goal);
        double dist = aiEntity.getPos().squaredDistanceTo(goal);
        if (dist > TELEPORT_DISTANCE_SQUARED) {
            aiEntity.getNavigation().stop();
            aiEntity.refreshPositionAndAngles(goal.x, goal.y, goal.z, aiEntity.getYaw(), aiEntity.getPitch());
            aiEntity.setVelocity(Vec3d.ZERO);
            return;
        }

        aiEntity.getNavigation().startMovingTo(goal.x, goal.y, goal.z, MOVE_SPEED);
    }

    public static void spawn(MinecraftServer server, ServerWorld world, double x, double y, double z) {
        hide();

        AllayEntity allay = new AllayEntity(EntityType.ALLAY, world);
        allay.refreshPositionAndAngles(x, y, z, 0.0F, 0.0F);
        allay.setPersistent();
        allay.setInvulnerable(true);
        allay.setCanPickUpLoot(false);
        allay.setNoGravity(true);
        allay.addCommandTag(AI_TAG);
        world.spawnEntity(allay);

        aiEntity = allay;
        CustomBlocksConfig.aiEnabled = true;
        CustomBlocksConfig.aiStyle = normalizeStyle(CustomBlocksConfig.aiStyle);
        refreshFromConfig();
        stopMovement();
        CustomBlocksConfig.save();
    }

    public static void orderMoveTo(BlockPos pos) {
        targetPos = pos;
        following = false;
        followAnchor = null;
    }

    public static void runSanityScan(ServerPlayerEntity player) {
        if (aiEntity == null) {
            return;
        }
        ServerWorld world = (ServerWorld) aiEntity.getWorld();
        BlockPos center = aiEntity.getBlockPos();
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
                            world.spawnParticles(ParticleTypes.SMOKE, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 3, 0.1, 0.1, 0.1, 0.02);
                        }
                    }
                }
            }
        }

        if (brokenCount > 0) {
            player.sendMessage(Text.literal(aiPrefix() + "§eScan complete. Found §c" + brokenCount + " §7broken blocks. Markers placed. §e⚠"), false);
        } else {
            player.sendMessage(Text.literal(aiPrefix() + "§aArea is clean. No broken blocks. §f✔"), false);
        }
    }

    public static void hide() {
        if (aiEntity != null) {
            aiEntity.discard();
        }
        resetTracking(true);
    }

    public static boolean handleChatCommand(ServerPlayerEntity player, String message) {
        if (!message.startsWith(".")) {
            return false;
        }

        String cmd = message.substring(1).toLowerCase(Locale.ROOT);
        switch (cmd) {
            case "come" -> {
                following = true;
                followAnchor = player.getUuid();
                targetPos = null;
                player.sendMessage(Text.literal(aiPrefix() + "§fFollowing you. §a✔"), false);
                return true;
            }
            case "stay" -> {
                following = false;
                targetPos = null;
                stopMovement();
                player.sendMessage(Text.literal(aiPrefix() + "§fStaying here. §a✔"), false);
                return true;
            }
            case "status" -> {
                player.sendMessage(Text.literal(aiPrefix() + "§fI'm working fine. §a✔"), false);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    public static boolean isFollowing() {
        return following;
    }

    public static void setFollowing(boolean enabled, UUID anchor) {
        following = enabled;
        followAnchor = enabled ? anchor : null;
        targetPos = null;
        if (!enabled) {
            stopMovement();
        }
    }

    public static boolean isSpawned() {
        return aiEntity != null && aiEntity.isAlive();
    }

    public static boolean isManagedAssistant(Entity entity) {
        if (!(entity instanceof AllayEntity allay)) {
            return false;
        }
        if (aiEntity != null && allay.getUuid().equals(aiEntity.getUuid())) {
            return true;
        }
        return allay.getCommandTags().contains(AI_TAG);
    }

    public static List<String> availableStyles() {
        return STYLES;
    }

    public static void refreshFromConfig() {
        if (aiEntity == null) {
            return;
        }
        applyStyle();
        refreshAppearance();
    }

    public static Item getStyleDisplayItem(String style) {
        return switch (normalizeStyle(style)) {
            case "Amethyst" -> Items.AMETHYST_SHARD;
            case "Compass" -> Items.RECOVERY_COMPASS;
            case "Royal" -> Items.NETHER_STAR;
            case "Builder" -> Items.BRUSH;
            case "Phantom" -> Items.PHANTOM_MEMBRANE;
            case "Torch" -> Items.TORCH;
            default -> Items.ECHO_SHARD;
        };
    }

    public static String getStatusSummary() {
        if (!isSpawned()) {
            return "§cNot spawned.";
        }
        BlockPos pos = aiEntity.getBlockPos();
        String state = following ? "§aFollowing" : (targetPos != null ? "§bMoving to target" : "§7Idle");
        return "§fStatus: " + state + "  §7Position: §f" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    public static void teleportToPlayer(ServerPlayerEntity player) {
        if (!isSpawned()) {
            return;
        }
        if (aiEntity.getWorld() != player.getWorld()) {
            spawn(player.getServer(), (ServerWorld) player.getWorld(), player.getX(), player.getY(), player.getZ());
            return;
        }
        aiEntity.getNavigation().stop();
        aiEntity.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(), aiEntity.getYaw(), aiEntity.getPitch());
        aiEntity.setVelocity(Vec3d.ZERO);
        refreshAppearance();
    }

    public static void teleportPlayerToHelper(ServerPlayerEntity player) {
        if (!isSpawned()) {
            return;
        }
        player.teleport((ServerWorld) aiEntity.getWorld(), aiEntity.getX(), aiEntity.getY(), aiEntity.getZ(), Set.of(), player.getYaw(), player.getPitch());
    }

    private static Vec3d resolveGoal(MinecraftServer server) {
        Vec3d goal = null;
        if (following && followAnchor != null) {
            ServerPlayerEntity target = server.getPlayerManager().getPlayer(followAnchor);
            if (target != null && target.getWorld() == aiEntity.getWorld()) {
                Vec3d targetPosVec = target.getPos().add(0.0, 0.5, 0.0);
                if (aiEntity.getPos().squaredDistanceTo(targetPosVec) > FOLLOW_START_DISTANCE_SQUARED) {
                    goal = targetPosVec;
                }
            }
        } else if (targetPos != null) {
            goal = Vec3d.ofCenter(targetPos).add(0.0, 0.2, 0.0);
            if (aiEntity.getPos().squaredDistanceTo(goal) < ARRIVAL_DISTANCE_SQUARED) {
                targetPos = null;
                goal = null;
            }
        }
        return goal;
    }

    private static void stopMovement() {
        if (aiEntity == null) {
            return;
        }
        aiEntity.getNavigation().stop();
        aiEntity.setVelocity(Vec3d.ZERO);
        aiEntity.setAiDisabled(true);
    }

    private static void refreshAppearance() {
        if (aiEntity == null) {
            return;
        }
        String name = CustomBlocksConfig.aiName;
        if (CustomBlocksConfig.aiHologram) {
            String status = following ? "§aFollowing" : (targetPos != null ? "§bMoving" : "§7Idle");
            aiEntity.setCustomName(Text.literal("§b§l" + name + "\n§f" + status));
            aiEntity.setCustomNameVisible(true);
        } else {
            aiEntity.setCustomName(Text.literal(name));
            aiEntity.setCustomNameVisible(false);
        }
    }

    private static void applyStyle() {
        if (aiEntity == null) {
            return;
        }
        CustomBlocksConfig.aiStyle = normalizeStyle(CustomBlocksConfig.aiStyle);
        aiEntity.equipStack(EquipmentSlot.MAINHAND, new ItemStack(getStyleDisplayItem(CustomBlocksConfig.aiStyle)));
    }

    public static String normalizeStyle(String style) {
        if (style == null || style.isBlank()) {
            return "Echo";
        }
        return switch (style.trim().toLowerCase(Locale.ROOT)) {
            case "amethyst" -> "Amethyst";
            case "compass" -> "Compass";
            case "royal" -> "Royal";
            case "builder" -> "Builder";
            case "phantom" -> "Phantom";
            case "torch" -> "Torch";
            default -> "Echo";
        };
    }

    private static void resetTracking(boolean persistDisabledState) {
        aiEntity = null;
        following = false;
        followAnchor = null;
        targetPos = null;
        CustomBlocksConfig.aiEnabled = false;
        if (persistDisabledState) {
            CustomBlocksConfig.save();
        }
    }

    private static String aiPrefix() {
        return "§0§l[§b§lAI§0§l] ";
    }
}
