package com.customblocks.gui;

import com.customblocks.CustomBlocksConfig;
import com.customblocks.TextSanitizer;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase F — Multi-channel feedback layers.
 * <p>
 * Every interaction triggers visual + audio feedback per the Sensory Matrix (§0.2).
 * Respects {@link CustomBlocksConfig#soundsEnabled} / {@link CustomBlocksConfig#particlesEnabled}
 * and per-category maps from config.
 */
public final class FeedbackHelper {
    public static final String CAT_SUCCESS = "success";
    public static final String CAT_ERROR = "error";
    public static final String CAT_GUI = "gui";
    public static final String CAT_SELECTION = "selection";
    public static final String CAT_BULK_COMPLETE = "bulk_complete";
    public static final String CAT_RP_REGENERATE = "rp_regenerate";
    public static final String CAT_ACHIEVEMENT = "achievement";

    private static final Map<UUID, ServerBossBar> ACTIVE_BARS = new ConcurrentHashMap<>();
    private static final Object RP_FEEDBACK_LOCK = new Object();
    private static long lastRpPackFeedbackMs;

    private FeedbackHelper() {}

    // ── F4: Sounds (per §0.2 Sensory Matrix) ─────────────────────────────

    /** Button click — chime, 6 enchant particles. */
    public static void clickFeedback(ServerPlayerEntity player) {
        if (CustomBlocksConfig.isSoundCategoryEnabled(CAT_GUI)) {
            player.playSound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.25f);
        }
        if (CustomBlocksConfig.isParticleCategoryEnabled(CAT_GUI)) {
            ServerWorld sw = player.getServerWorld();
            sw.spawnParticles(ParticleTypes.ENCHANT,
                    player.getX(), player.getY() + 1.5, player.getZ(),
                    6, 0.3, 0.3, 0.3, 0.05);
        }
    }

    /** Successful action — orb pickup + composter burst. */
    public static void successFeedback(ServerPlayerEntity player) {
        if (CustomBlocksConfig.isSoundCategoryEnabled(CAT_SUCCESS)) {
            player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.0f);
        }
        if (CustomBlocksConfig.isParticleCategoryEnabled(CAT_SUCCESS)) {
            ServerWorld sw = player.getServerWorld();
            sw.spawnParticles(ParticleTypes.COMPOSTER,
                    player.getX(), player.getY() + 1.5, player.getZ(),
                    12, 0.4, 0.4, 0.4, 0.02);
        }
    }

    /** Tool usage — note chime + soul fire flame trail. */
    public static void toolFeedback(ServerPlayerEntity player) {
        if (CustomBlocksConfig.isSoundCategoryEnabled(CAT_SELECTION)) {
            player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 0.85f, 1.0f);
        }
        if (CustomBlocksConfig.isParticleCategoryEnabled(CAT_SELECTION)) {
            ServerWorld sw = player.getServerWorld();
            sw.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME,
                    player.getX(), player.getY() + 1.0, player.getZ(),
                    4, 0.2, 0.2, 0.2, 0.01);
        }
    }

    /** Error / warning — bass note + smoke puff. */
    public static void errorFeedback(ServerPlayerEntity player) {
        if (CustomBlocksConfig.isSoundCategoryEnabled(CAT_ERROR)) {
            player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 1.0f, 0.7f);
        }
        if (CustomBlocksConfig.isParticleCategoryEnabled(CAT_ERROR)) {
            ServerWorld sw = player.getServerWorld();
            sw.spawnParticles(ParticleTypes.SMOKE,
                    player.getX(), player.getY() + 1.5, player.getZ(),
                    4, 0.3, 0.3, 0.3, 0.02);
        }
    }

    /** Dangerous action — deep bass + large smoke. */
    public static void dangerFeedback(ServerPlayerEntity player) {
        if (CustomBlocksConfig.isSoundCategoryEnabled(CAT_ERROR)) {
            player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 1.0f, 0.5f);
        }
        if (CustomBlocksConfig.isParticleCategoryEnabled(CAT_ERROR)) {
            ServerWorld sw = player.getServerWorld();
            sw.spawnParticles(ParticleTypes.LARGE_SMOKE,
                    player.getX(), player.getY() + 1.5, player.getZ(),
                    12, 0.4, 0.4, 0.4, 0.02);
        }
    }

    /** Snapshot / save — level up + enchant sparkle. */
    public static void saveFeedback(ServerPlayerEntity player) {
        if (CustomBlocksConfig.isSoundCategoryEnabled(CAT_SUCCESS)) {
            player.playSound(SoundEvents.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
        }
        if (CustomBlocksConfig.isParticleCategoryEnabled(CAT_SUCCESS)) {
            ServerWorld sw = player.getServerWorld();
            sw.spawnParticles(ParticleTypes.ENCHANT,
                    player.getX(), player.getY() + 1.5, player.getZ(),
                    4, 0.2, 0.2, 0.2, 0.05);
        }
    }

    public static void bulkCompleteFeedback(ServerPlayerEntity player) {
        if (CustomBlocksConfig.isSoundCategoryEnabled(CAT_BULK_COMPLETE)) {
            player.playSound(SoundEvents.BLOCK_BEACON_ACTIVATE, 0.8f, 1.0f);
        }
        if (CustomBlocksConfig.isParticleCategoryEnabled(CAT_BULK_COMPLETE)) {
            ServerWorld sw = player.getServerWorld();
            sw.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                player.getX(), player.getY() + 1.2, player.getZ(),
                10, 0.35, 0.35, 0.35, 0.02);
        }
    }

    /** Subtle cue when the downloadable resource pack ZIP finishes rebuilding (throttled server-wide). */
    public static void rpRegenerateFeedback(ServerPlayerEntity player) {
        if (CustomBlocksConfig.isSoundCategoryEnabled(CAT_RP_REGENERATE)) {
            player.playSound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.35f, 1.15f);
        }
        if (CustomBlocksConfig.isParticleCategoryEnabled(CAT_RP_REGENERATE)) {
            ServerWorld sw = player.getServerWorld();
            sw.spawnParticles(ParticleTypes.ENCHANT,
                player.getX(), player.getY() + 1.2, player.getZ(),
                4, 0.25, 0.25, 0.25, 0.03);
        }
    }

    /**
     * Notifies online players that the pack was rebuilt, at most once per {@code cooldownMs}
     * per successful build completion (avoids spam during rapid saves).
     */
    public static void broadcastPackRegeneratedIfDue(MinecraftServer server, long cooldownMs) {
        if (server == null) return;
        long now = System.currentTimeMillis();
        synchronized (RP_FEEDBACK_LOCK) {
            if (lastRpPackFeedbackMs != 0L && now - lastRpPackFeedbackMs < cooldownMs) return;
            lastRpPackFeedbackMs = now;
        }
        server.execute(() -> {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                rpRegenerateFeedback(p);
            }
        });
    }

    // F1
    public static void actionBar(ServerPlayerEntity player, String message) {
        if (message == null || message.isBlank()) return;
        player.sendMessage(Text.literal(TextSanitizer.fix(message)), true);
    }

    // F3 — real screen title (matches share celebration in GuiManager)
    public static void title(ServerPlayerEntity player, String title, String subtitle) {
        if (title == null || title.isBlank()) return;
        player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(TextSanitizer.fix(title))));
        if (subtitle != null && !subtitle.isBlank()) {
            player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(TextSanitizer.fix(subtitle))));
        }
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 40, 20));
    }

    // F2
    public static void startBossBar(ServerPlayerEntity player, String label) {
        UUID uuid = player.getUuid();
        clearBossBar(player);
        ServerBossBar bar = new ServerBossBar(
            Text.literal(TextSanitizer.fix(label == null ? "Working..." : label)),
            BossBar.Color.BLUE,
            BossBar.Style.PROGRESS
        );
        bar.setPercent(0f);
        bar.addPlayer(player);
        ACTIVE_BARS.put(uuid, bar);
    }

    public static void updateBossBar(ServerPlayerEntity player, String label, float progress) {
        ServerBossBar bar = ACTIVE_BARS.get(player.getUuid());
        if (bar == null) return;
        if (label != null && !label.isBlank()) bar.setName(Text.literal(TextSanitizer.fix(label)));
        bar.setPercent(Math.max(0f, Math.min(1f, progress)));
    }

    public static void clearBossBar(ServerPlayerEntity player) {
        ServerBossBar old = ACTIVE_BARS.remove(player.getUuid());
        if (old != null) old.removePlayer(player);
    }

    public static void playSound(ServerPlayerEntity player, SoundEvent sound, float volume, float pitch) {
        if (player == null || sound == null || !CustomBlocksConfig.soundsEnabled) return;
        player.getServerWorld().playSound(null, player.getBlockPos(), sound, SoundCategory.PLAYERS, volume, pitch);
    }

    public static void spawnParticle(ServerPlayerEntity player, ParticleEffect particle, int count) {
        if (player == null || particle == null || count <= 0 || !CustomBlocksConfig.particlesEnabled) return;
        ServerWorld sw = player.getServerWorld();
        sw.spawnParticles(particle, player.getX(), player.getY() + 1.2, player.getZ(),
            count, 0.25, 0.25, 0.25, 0.03);
    }

}
