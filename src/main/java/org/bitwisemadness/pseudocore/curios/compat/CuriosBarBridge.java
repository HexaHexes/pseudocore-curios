package org.bitwisemadness.pseudocore.curios.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.bitwisemadness.abilityhotbar.content.HotbarContentProviders;
import org.bitwisemadness.abilityhotbar.registry.AbilityBarScopes;
import org.bitwisemadness.abilityhotbar.state.SeededAssignments;
import org.bitwisemadness.pseudocore.curios.ActiveCore;
import org.bitwisemadness.pseudocore.curios.PseudocoreCurios;
import org.bitwisemadness.pseudocore.curios.client.CuriosBarContentProvider;
import org.bitwisemadness.pseudocore.curios.hotkey.ReleaseRecallAction;

/**
 * The ability-hotbar seam (#72): the addon's own non-assignable curios
 * bar - registered at setup, its cells seeded from the player's Curios
 * slots. Follows the {@code IafHornAccess}/{@code AbilityCoreBridge}
 * rule: hotbar types appear only inside methods whose callers gate on
 * {@link #isLoaded()}, so the optional dependency stays optional and a
 * pack without ability-hotbar loads this class without ever resolving
 * one of them.
 *
 * <p>The bar is <b>non-assignable</b> (2026-11-04 rulings): the menu
 * never lists it, players never assign to it, and pseudocore never
 * touches the built-in bars. {@code press} is scoped to this bar so the
 * verb cannot leak, and each filled Curios slot carries the seeded verb
 * at the matching cell - activating that cell releases/recalls that
 * cell's core through {@code ActivationRequest.slotContext}.</p>
 */
public final class CuriosBarBridge {

    /** The addon's bar id: registered non-assignable, cells = Curios slots. */
    public static final ResourceLocation CURIO_BAR = ResourceLocation.fromNamespaceAndPath(
            PseudocoreCurios.MOD_ID, "curios");

    /** Display name for the switch toast (and any future surface). */
    public static final String BAR_NAME_KEY = "pseudocore_curios.bar.curios";

    private static boolean registered;

    private CuriosBarBridge() {
    }

    /** True while ability-hotbar is installed. */
    public static boolean isLoaded() {
        ModList modList = ModList.get();
        return modList != null && modList.isLoaded("ability_hotbar");
    }

    /**
     * Register the bar, press's scope and the cell provider - common
     * setup, once, through Ability Hotbar's one-call content-bar helper
     * (hotbar#114). The helper registers the bar first (so the scope call
     * below validates it), constructs the provider <b>only on the
     * client</b> (the dist-cleaner trap it exists to remove), and logs the
     * registration natively - so this class no longer carries the local
     * debug line. The {@code registered} latch keeps the scope call, which
     * has no idempotence of its own, to a single pass.
     */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        // A lambda, not `CuriosBarContentProvider::new`: the method
        // reference resolves the client-only class at the lambda bootstrap
        // the server runs (dist-cleaner abort); the body resolves lazily.
        HotbarContentProviders.registerContentBar(CURIO_BAR, BAR_NAME_KEY,
                () -> new CuriosBarContentProvider());
        AbilityBarScopes.register(AbilityCoreBridge.PRESS_ID, CURIO_BAR);
    }

    /**
     * Seed the whole bar from the live Curios slots (#72) in one pass
     * (hotbar#114's {@code seedAll}: validated up front so a bad count
     * mutates nothing, one persist, one snapshot echo instead of one per
     * cell) - a core's slot gets {@code press}, an empty slot is cleared.
     * Curios is the source of truth, so this runs on login and on every
     * {@code CurioChangeEvent} for our slot; the overwrite is idempotent
     * and authoritative, and the seed is logged natively by the helper.
     */
    public static void seed(ServerPlayer player) {
        if (!isLoaded()) {
            return;
        }
        SeededAssignments.seedAll(player, CURIO_BAR, PseudocoreCurios.SLOT_COUNT, slot -> {
            ItemStack stack = ActiveCore.slotStack(player, slot);
            return ReleaseRecallAction.isCore(stack.getItem())
                    ? AbilityCoreBridge.PRESS_ID.toString()
                    : null;
        });
    }
}
