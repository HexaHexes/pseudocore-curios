package org.bitwisemadness.pseudocore.curios.compat;

import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.bitwisemadness.abilitycore.AbilityCore;
import org.bitwisemadness.abilitycore.api.AbilityAvailability;
import org.bitwisemadness.abilitycore.api.AbilityDefinition;
import org.bitwisemadness.abilitycore.api.ActivationOutcome;
import org.bitwisemadness.abilitycore.api.ActivationRequest;
import org.bitwisemadness.abilitycore.api.ActivationResult;
import org.bitwisemadness.abilitycore.api.ActivationType;
import org.bitwisemadness.abilitycore.api.AvailabilityProviders;
import org.bitwisemadness.abilitycore.api.AvailabilityState;
import org.bitwisemadness.abilitycore.api.ChargingModel;
import org.bitwisemadness.abilitycore.api.Metric;
import org.bitwisemadness.pseudocore.curios.PseudocoreCurios;
import org.bitwisemadness.pseudocore.curios.hotkey.ReleaseRecallAction;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * The ability-core seam (#69): registers {@code pseudocore_curios:press}
 * - release/recall as a first-class bar ability - behind the optional
 * dependency, following the {@code IafHornAccess} rule: this class's own
 * references to core types resolve only inside methods the caller gates
 * on {@link #isLoaded()}, so nothing loads without the library. Callers
 * (the mod-bus subscriber) see only this class and vanilla/NeoForge
 * types in signatures.
 *
 * <p>The handler is NATIVE-charged: the press's own pipeline owns
 * cooldown, cost and every gate exactly as the hotkey does (#11/#15),
 * and the refusals keep messaging the player themselves - the pipeline
 * result carries the outcome vocabulary only. The availability provider
 * projects the <b>same</b> shared throttle, so the bar's overlay and the
 * hotkey can never disagree.</p>
 */
public final class AbilityCoreBridge {

    /** The press ability's id (registered on core's abilities registry). */
    public static final ResourceLocation PRESS_ID = ResourceLocation.fromNamespaceAndPath(
            PseudocoreCurios.MOD_ID, "press");

    private static boolean handlersRegistered;

    private AbilityCoreBridge() {
    }

    /** True while Tensura Ability Core is installed. */
    public static boolean isLoaded() {
        ModList modList = ModList.get();
        return modList != null && modList.isLoaded("ability_core");
    }

    /**
     * Register the ability definition on core's registry - called from
     * the mod-bus {@code RegisterEvent} for core's abilities key (#69).
     */
    public static void registerDefinition(RegisterEvent event) {
        event.register(org.bitwisemadness.abilitycore.registry.AbilityRegistries
                        .ABILITY_REGISTRY_KEY, PRESS_ID,
                () -> new AbilityDefinition(
                        "pseudocore_curios.ability.press",
                        ResourceLocation.fromNamespaceAndPath("pseudocore",
                                "textures/item/copper_pseudocore.png"),
                        ResourceLocation.fromNamespaceAndPath(PseudocoreCurios.MOD_ID, "core"),
                        ActivationType.INSTANT,
                        null,
                        Set.of("core", "press"),
                        Map.of()));
    }

    /**
     * Register the handler and the availability projection - called from
     * common setup, before any login can reach the pipeline. Idempotent
     * (both of core's registries fail loudly on duplicates).
     */
    public static void registerPress() {
        if (handlersRegistered) {
            return;
        }
        handlersRegistered = true;
        AbilityCore.HANDLERS.register(PRESS_ID, AbilityCoreBridge::press, ChargingModel.NATIVE);
        AvailabilityProviders.declare(PRESS_ID, AbilityCoreBridge::availability);
    }

    /**
     * The native pipeline: the curios slot stack through the hotkey's own
     * press (#11/#15 - the slot-based entry point), so the bar and the hotkey
     * are literally the same verb. The press has already messaged the player
     * for any refusal (#12's design), so only core's outcome vocabulary
     * travels back.
     */
    private static ActivationResult press(ActivationRequest request, ServerPlayer player) {
        ReleaseRecallAction.Refusal refusal = ReleaseRecallAction.handle(player);
        if (refusal == null) {
            return ActivationResult.ACTED;
        }
        return ActivationResult.refused(switch (refusal) {
            case COOLDOWN -> ActivationOutcome.REFUSED_COOLDOWN;
            case UNREACHABLE -> ActivationOutcome.REFUSED_INVALID_TARGET;
            case GATED -> ActivationOutcome.REFUSED_RESTRICTED;
            default -> ActivationOutcome.REFUSED_UNAVAILABLE;
        });
    }

    /**
     * The overlay tuple (#69): cooldown progress from the shared throttle,
     * availability = the slot holds a core, cost unknown for now - the
     * contract renders nothing rather than a lie, and the dynamic MP
     * estimate rides a pseudocore-side public API later.
     */
    private static AbilityAvailability availability(Player player, ResourceLocation id) {
        double progress = 0.0D;
        ItemStack slot = ItemStack.EMPTY;
        if (player instanceof ServerPlayer serverPlayer) {
            progress = ReleaseRecallAction.cooldownProgress(serverPlayer);
            slot = CuriosApi.getCuriosInventory(serverPlayer)
                    .map(inv -> inv.getCurios().get(PseudocoreCurios.SLOT_ID))
                    .map(stacks -> stacks.getStacks().getStackInSlot(0))
                    .orElse(ItemStack.EMPTY);
        }
        boolean hasCore = !slot.isEmpty() && ReleaseRecallAction.isCore(slot.getItem());
        return new AbilityAvailability(
                Metric.known(progress),
                Metric.unknown(),
                hasCore ? AvailabilityState.available()
                        : AvailabilityState.unavailable("no core in the slot"));
    }
}
