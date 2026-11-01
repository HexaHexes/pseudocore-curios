package org.bitwisemadness.pseudocore.curios;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.bitwisemadness.pseudocore.curios.hotkey.ReleaseRecallAction;
import org.bitwisemadness.pseudocore.curios.linkage.LinkageManager;
import org.bitwisemadness.pseudocore.event.PseudocoreReleaseEvent;

/**
 * Server-side wiring for linkage tracking and the press triggers (#8, #34).
 *
 * <p>Three listeners, all thin: the release listener stamps/tags/records
 * through {@link LinkageManager}, the entity-leave listener refreshes a
 * tagged mob's record - the chunk-unload hook that keeps the last-known
 * position exact - and the right-click listener runs the air press (#34).
 * All are game-bus listeners, routed automatically by
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

    /**
     * Right-click with a core in hand (#34): the air-click equivalent of the
     * hotkey, for a core that is <b>not</b> (or not only) in the Curios slot.
     * The same event-driven shape as the base's own capture listener - no
     * mixin, and no inheritance problem (the hotkey path could inject where
     * {@code Item.use} is <i>declared</i>; this cannot target the subclass
     * for a method it does not override).
     *
     * <p>Scope: both lines (#48). The gate is
     * {@link ReleaseRecallAction#isCore}, the namespace-based predicate the
     * hotkey path uses - this listener originally gated on
     * {@code instanceof PseudocoreItem}, which silently excluded the entire
     * dragon line ({@code DragonPseudocoreItem} extends IaF's horn item, not
     * {@code PseudocoreItem}), and #34's "dragon cores need a
     * pseudocore-side hook" note turned out to need nothing more than the
     * check the hotkey already had.</p>
     *
     * <p>Both sides cancel with SUCCESS (the click is consumed, the arm
     * swings); only the server runs the press, which carries the shared
     * cooldown with the hotkey - one player, one throttle, whichever
     * trigger they use. Either hand works (#51): the main-hand event, when
     * claimed, stops vanilla from attempting the offhand use at all, so one
     * click is at most one press. Right-clicking a block face that the
     * base's {@code useOn} consumes never reaches this event (the client
     * sends only the block interaction), so the two release paths cannot
     * both run on one click.</p>
     */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        // Both hands (#51): the event's stack IS the clicked hand's stack, so nothing else
        // distinguishes them. No double press with cores in both hands - the main-hand event
        // cancels with SUCCESS first and vanilla never attempts the offhand use.
        if (!ReleaseRecallAction.isCore(event.getItemStack().getItem())) {
            return;
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
        if (event.getEntity() instanceof ServerPlayer player) {
            if (player.isShiftKeyDown()) {
                // The clear gesture (#15) outranks the press while sneaking:
                // same stack, different verb.
                org.bitwisemadness.pseudocore.curios.hotkey.ClearCoreAction
                        .handle(player, event.getItemStack());
                return;
            }
            ReleaseRecallAction.press(player, event.getItemStack());
        }
    }
}
