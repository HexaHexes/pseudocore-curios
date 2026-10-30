package org.bitwisemadness.pseudocore.curios.hotkey;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.bitwisemadness.pseudocore.curios.PseudocoreCuriosConfig;
import org.bitwisemadness.pseudocore.curios.linkage.ModLinkageComponents;
import org.bitwisemadness.pseudocore.registry.ModDataComponents;
import org.bitwisemadness.pseudocore.util.IafHornAccess;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sneak + right-click on air clears the core (#15).
 *
 * <p>The issue's state table, in decision order:</p>
 * <ul>
 *   <li><b>death record only</b> -> wiped instantly, config-independent -
 *       "the record's explicit exit" (the preserve flow's counterpart);
 *       silent, no cooldown - nothing living is at stake;</li>
 *   <li><b>empty</b> -> silent no-op: no message, and no cooldown
 *       consumed - a no-op must not throttle the real triggers;</li>
 *   <li><b>filled</b> (with or without stale death data - coexistence
 *       counts as filled, #14's rule) -> the shared cooldown first, then
 *       per {@code filledCoreClearAction}: {@link ClearActionMode#RELEASE}
 *       runs {@link ReleaseRecallAction#releaseNow} - the same spawn-at-
 *       feet code the hotkey and air click use, gates included - and
 *       {@link ClearActionMode#DESTROY} arms a two-step confirm: the
 *       first press warns, a second within {@link #CONFIRM_WINDOW_TICKS}
 *       executes the deletion (live storage plus any death record; for a
 *       dragon core the horn storage goes by registry id, the same trick
 *       {@code DeathPreserveEvents.migrateToDeathRecord} uses). An expired
 *       window re-arms rather than executes - only a genuine double-press
 *       destroys.</li>
 * </ul>
 *
 * <p>Cooldown sharing is deliberate: one player, one throttle across
 * hotkey, air click and clear - a destroy cannot dodge it either.</p>
 */
public final class ClearCoreAction {

    /** A second confirm press must land within 5 seconds of the first. */
    public static final long CONFIRM_WINDOW_TICKS = 100L;

    /** The armed-destroy timestamps per player; absence = not armed. */
    private static final Map<UUID, Long> ARMED = new HashMap<>();

    private ClearCoreAction() {
    }

    /** One sneak press against a given core stack; the silent rows stay silent. */
    public static void handle(ServerPlayer player, ItemStack stack) {
        handle(player, stack, PseudocoreCuriosConfig.clearAction());
    }

    /**
     * The same press with the mode injected - the entry the tests drive, so
     * no test ever mutates the live config across a delayed hop (a flip
     * window racing a sibling test's read failed exactly that way once; the
     * config value itself is pinned by the junit parse tests and the
     * default assertion, not by writing it here).
     */
    public static void handle(ServerPlayer player, ItemStack stack, ClearActionMode mode) {
        boolean live = stack.get(ModDataComponents.CONTAINED_MOB.get()) != null
                || (ModList.get().isLoaded("iceandfire")
                && IafHornAccess.storedDragonType(stack) != null);
        boolean dead = stack.has(ModLinkageComponents.DEAD_MOB.get());

        if (!live && !dead) {
            return; // empty: silent no-op, no cooldown (#15)
        }
        if (dead && !live) {
            // The record's explicit exit - instant, whatever the config says.
            stack.remove(ModLinkageComponents.DEAD_MOB.get());
            return;
        }

        // Filled (possibly with stale death data): the shared throttle.
        if (ReleaseRecallAction.checkCooldown(player) != null) {
            return; // locked out; the next allowed press warns/executes
        }

        if (mode == ClearActionMode.RELEASE) {
            ReleaseRecallAction.releaseNow(player, stack);
            if (stack.has(ModLinkageComponents.DEAD_MOB.get())) {
                // Coexistence rule (#14): the configured action empties the
                // live mob, the record goes with it.
                stack.remove(ModLinkageComponents.DEAD_MOB.get());
            }
            return;
        }

        // DESTROY: two-step confirm.
        long now = player.level().getGameTime();
        Long armedAt = ARMED.get(player.getUUID());
        if (armedAt == null || now - armedAt > CONFIRM_WINDOW_TICKS) {
            ARMED.put(player.getUUID(), now);
            player.displayClientMessage(Component.translatable(
                    "pseudocore_curios.message.clear_confirm", stack.getHoverName())
                    .withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        ARMED.remove(player.getUUID());
        stack.remove(ModDataComponents.CONTAINED_MOB.get());
        stack.remove(ModLinkageComponents.DEAD_MOB.get());
        // Dragon storage: looked up by stable registry id, never by class
        // (the addon cannot name IaF types; absent IaF means no horn anyway).
        var hornType = net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE
                .getOptional(ResourceLocation.fromNamespaceAndPath("iceandfire", "dragon_horn"))
                .orElse(null);
        if (hornType != null) {
            stack.remove(hornType);
        }
    }
}
