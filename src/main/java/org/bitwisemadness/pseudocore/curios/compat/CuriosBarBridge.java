package org.bitwisemadness.pseudocore.curios.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import org.bitwisemadness.abilityhotbar.content.HotbarContentProviders;
import org.bitwisemadness.abilityhotbar.registry.AbilityBarScopes;
import org.bitwisemadness.abilityhotbar.registry.HotbarRegistry;
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
     * setup, once. Order is load-bearing: the provider registration
     * validates the bar registration. All three APIs are documented as
     * setup-time and distribution-safe to call (the provider is only
     * ever <em>invoked</em> client-side, per hotbar#83's contract).
     */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        HotbarRegistry.register(CURIO_BAR, BAR_NAME_KEY, false);
        AbilityBarScopes.register(AbilityCoreBridge.PRESS_ID, CURIO_BAR);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            // Link-safety: the provider reads Minecraft's player, and the
            // JVM verifies whole classes at first use - constructing it
            // server-side trips the dist cleaner (FMLCommonSetup abort).
            HotbarContentProviders.register(CURIO_BAR, new CuriosBarContentProvider());
        }
        // Diagnostic (#79 follow-up): this line in debug.log is the proof the
        // bar entered HotbarRegistry.all() - the wheel cycle can only reach
        // what registration put there.
        PseudocoreCurios.LOGGER.debug("Curios bar registered (provider registered: {})",
                FMLEnvironment.dist == Dist.CLIENT);
    }

    /**
     * Seed the whole bar from the live Curios slots (#72): one seed per
     * slot - a core's slot gets {@code press}, an empty slot is cleared.
     * Curios is the source of truth, so this runs on login and on every
     * {@code CurioChangeEvent} for our slot; hotbar#92 makes a re-seed a
     * plain overwrite (idempotent and authoritative). Each
     * {@link ServerPlayer} seed echoes the snapshot itself - six small
     * echoes beat hand-driving the state loop and one send for the sake
     * of one packet.
     */
    public static void seed(ServerPlayer player) {
        if (!isLoaded()) {
            return;
        }
        for (int slot = 0; slot < PseudocoreCurios.SLOT_COUNT; slot++) {
            ItemStack stack = ActiveCore.slotStack(player, slot);
            String ability = ReleaseRecallAction.isCore(stack.getItem())
                    ? AbilityCoreBridge.PRESS_ID.toString()
                    : null;
            SeededAssignments.seed(player, CURIO_BAR, slot, ability);
            // Diagnostic (#79 follow-up): six of these per login prove the
            // seed ran; a missing press on a filled slot names the culprit.
            PseudocoreCurios.LOGGER.debug("Seeding curios bar slot {} with {}", slot, ability);
        }
    }
}
