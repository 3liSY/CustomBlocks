package com.customblocks.core;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V4-30 — First-time onboarding. Tracks whether a player has seen the welcome
 * screen. Resets on server restart (intentional — welcome shown once per session).
 */
public final class OnboardingManager {

    private static final Set<UUID> SEEN = ConcurrentHashMap.newKeySet();

    public static boolean hasSeenWelcome(UUID uuid) {
        return SEEN.contains(uuid);
    }

    public static void markSeen(UUID uuid) {
        SEEN.add(uuid);
    }

    /** Sends the 5-step quick-start tutorial as chat messages. */
    public static void sendTutorial(ServerPlayerEntity player) {
        player.sendMessage(Text.literal("§b§l━━━ CustomBlocks Quick Start ━━━"), false);
        player.sendMessage(Text.literal("§7Step §e1 §7— Create a block: §f/cb create"), false);
        player.sendMessage(Text.literal("§7Step §e2 §7— Give it to yourself: §f/cb give <id>"), false);
        player.sendMessage(Text.literal("§7Step §e3 §7— Open the editor: §f/cb editor <id>"), false);
        player.sendMessage(Text.literal("§7Step §e4 §7— Change colors with the Square/Triangle tools: §f/cb magicitems"), false);
        player.sendMessage(Text.literal("§7Type §f/cb help §7anytime for the full reference guide."), false);
        player.sendMessage(Text.literal("§b§l━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"), false);
    }

    private OnboardingManager() {}
}
