package com.customblocks.core;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Phase C — Universal Resume. Session-only drafts for ESC-dismissed GUIs ({@code /cb resume}).
 * <p>
 * Drop on disconnect (hook {@code ServerPlayConnectionEvents.DISCONNECT}).
 */
public final class DraftManager {

    public enum Kind {
        BULK_RECOLOR,
        BULK_ASSIGN,
        BLOCK_EDITOR,
        /** Per-face texture GUI — payload: {@code id}, {@code returnPage}. */
        FACE_EDITOR,
        SEARCH_CATEGORY,
        AI_PROMPT,
        HOLOGRAM_EDITOR,
        DROPS_BUILDER,
        MARKETPLACE_UPLOAD,
        ANVIL_RENAME,
        /** Colour fill mode GUI (from server config) — empty payload. */
        COLOR_PICKER,
        GRADIENT_WIZARD,
        /** Shape editor — payload: {@code id}, {@code returnPage}, {@code shapeBoxPage}. */
        SHAPE_EDITOR,
        PROPERTIES_MENU,
        SOUND_MENU,
        /** Animation settings GUI — payload: {@code id}, {@code returnPage}; uses live {@code ANIM_PARAMS}. */
        ANIM_GUI_SESSION,
        /**
         * Face copy flow — payload: {@code step} ({@code select}|{@code picker}), {@code id},
         * {@code returnPage}; picker adds {@code pickerPage}, {@code face}.
         */
        FACE_CHANGE,
        /** Background studio — empty payload. */
        BG_STUDIO,
        /** Category editor — payload: {@code categoryKey}, {@code tabIndex}. */
        CATEGORY_EDITOR,
        /** Bulk delete picker — payload: {@code page}, {@code selected}, optional {@code confirmUntil}. */
        BULK_DELETE,
        /** Snapshot recovery GUI — payload: {@code page}. */
        RECOVER_GUI,
        /** Undo/redo history GUI — payload: {@code page} (reserved for future paging). */
        UNDO_PICKER,
        /** Right-click assign flow — decision step — payload: {@code blockId}, {@code returnPage}. */
        ASSIGNMENT_DECISION,
        /** Pick target category for a block — payload: {@code blockId}, {@code page}. */
        CATEGORY_PICKER_ASSIGN,
        /** Import conflict wizard — payload: {@code rootJson}, {@code decisionsJson}, {@code page}. */
        IMPORT_CONFLICT,
        /** Merge category target picker — payload: {@code sourceKey}, {@code page}. */
        MERGE_CATEGORY,
        /** Delete category options menu — payload: {@code categoryKey}. */
        DELETE_CATEGORY_MENU,
        /**
         * Generic ESC capture for any remaining {@link com.customblocks.gui.GuiMode} — payload:
         * {@code guiMode}, optional {@code editingId}, {@code page}, {@code confirmDelete},
         * {@code shapeBoxPage}, {@code fromCommand} (mirrors {@link com.customblocks.gui.GuiState}).
         */
        SESSION_SHELL
    }

    @SuppressFBWarnings({"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})
    public record Draft(UUID playerId, Kind kind, Map<String, Object> payload, long createdAt) {}

    private static final ConcurrentHashMap<UUID, Draft> ACTIVE = new ConcurrentHashMap<>();

    private DraftManager() {}

    // ── Generic API ────────────────────────────────────────────────────────

    /** Save a draft for any kind. */
    public static void save(UUID uuid, Kind kind, Map<String, Object> payload) {
        ACTIVE.put(uuid, new Draft(uuid, kind, Map.copyOf(payload), System.currentTimeMillis()));
    }

    /** Phase C API alias: start a draft. */
    public static void start(UUID uuid, Kind kind, Map<String, Object> payload) {
        save(uuid, kind, payload);
    }

    /** Convenience for bulk recolor. */
    public static void putBulkRecolorDraft(UUID uuid, Map<String, Object> payload) {
        save(uuid, Kind.BULK_RECOLOR, payload);
    }

    /** Convenience for bulk assign. */
    public static void putBulkAssignDraft(UUID uuid, Map<String, Object> payload) {
        save(uuid, Kind.BULK_ASSIGN, payload);
    }

    /** Retrieve and remove the draft. */
    public static Optional<Draft> take(UUID uuid) {
        return Optional.ofNullable(ACTIVE.remove(uuid));
    }

    /** Phase C API: inspect current draft without consuming it. */
    public static Optional<Draft> active(UUID uuid) {
        return Optional.ofNullable(ACTIVE.get(uuid));
    }

    /** Phase C API: resume and dispatch to GUI flow. */
    public static int resume(ServerPlayerEntity player) {
        if (player == null) return 0;
        return com.customblocks.gui.GuiManager.resumePendingDraft(player);
    }

    /** Drop draft on disconnect / cancel. */
    public static void drop(UUID uuid) {
        ACTIVE.remove(uuid);
    }

    /** Drop all drafts — called on server shutdown. */
    public static void dropAll() {
        ACTIVE.clear();
    }
}
