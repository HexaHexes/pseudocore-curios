package org.bitwisemadness.pseudocore.curios;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import org.bitwisemadness.pseudocore.curios.linkage.LinkageManager;
import org.bitwisemadness.pseudocore.event.PseudocoreReleaseEvent;

/**
 * Server-side wiring for linkage tracking (#8).
 *
 * <p>Two listeners, both thin: the release listener stamps/tag/records
 * through {@link LinkageManager}, and the entity-leave listener refreshes a
 * tagged mob's record - the chunk-unload hook that keeps the last-known
 * position exact. Both are game-bus listeners, routed automatically by
 * {@code AutomaticEventSubscriber} as in the base repo.</p>
 */
@EventBusSubscriber(modid = PseudocoreCurios.MOD_ID)
public final class PseudocoreCuriosEvents {

    private PseudocoreCuriosEvents() {
    }

    /** One integration point (pseudocore #106): stamp, tag, record. */
    @SubscribeEvent
    public static void onRelease(PseudocoreReleaseEvent event) {
        if (event.releaser() == null) {
            // Dispenser-style releases have no attachment home; not tracked.
            return;
        }
        LinkageManager.remember(event.releaser(), event.stack(), event.released());
    }

    /**
     * A tagged mob leaving the level: at chunk unload this is the last moment
     * it is reachable, so its final position is recorded - which is what makes
     * the loaded-chunks-only recall's unreachable message accurate (#8, #12).
     * Plain removals (deaths) re-record the same position harmlessly; the
     * death-side features handle those separately.
     */
    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        Entity mob = event.getEntity();
        java.util.UUID linkage = LinkageManager.taggedLinkage(mob);
        if (linkage == null || !(event.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        java.util.UUID owner = LinkageManager.taggedOwner(mob);
        if (owner == null) {
            return;
        }
        ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(owner);
        if (player != null) {
            LinkageManager.record(player, mob, linkage);
        }
    }
}
