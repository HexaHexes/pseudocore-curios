package org.bitwisemadness.pseudocore.curios.compat;

import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
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
import org.bitwisemadness.pseudocore.curios.ActiveCore;
import org.bitwisemadness.pseudocore.curios.PseudocoreCurios;
import org.bitwisemadness.pseudocore.curios.hotkey.ReleaseRecallAction;

/**
 * The ability-core seam (#69/#71): registers {@code pseudocore_curios:press}
 * - release/recall as a first-class ability - behind the optional
 * dependency, following the {@code IafHornAccess} rule: this class's own
 * references to core types resolve only inside methods the caller gates
 * on {@link #isLoaded()}, so nothing loads without the library. Callers
 * (the mod-bus subscriber) see only this class and vanilla/NeoForge
 * types in signatures.
 *
 * <p>The press handler is NATIVE-charged: the press's own pipeline owns
 * cooldown, cost and every gate exactly as the hotkey does (#11/#15),
 * and the refusals keep messaging the player themselves - the pipeline
 * result carries the outcome vocabulary only. The availability provider
 * projects the <b>same</b> shared throttle, so the curios bar and the
 * hotkey can never disagree.</p>
 *
 * <p>There is deliberately no selection ability here: #71's original
 * {@code select_core_N} family was dropped by ruling - pseudocore never
 * interacts with the ability bars. Release/recall reaches the bar world
 * only through the addon's own non-assignable curios bar, where each
 * seeded cell names its core through {@code ActivationRequest.slotContext},
 * and the active-core index here survives solely as the no-hotbar
 * hotkey's target (default plus read-time re-clamp).</p>
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
     * the mod-bus {@code RegisterEvent} for core's abilities key: the
     * press, whose bar home is the seeded cells of the addon's curios bar
     * (#69/#71, upstream hotbar#89/#92).
     */
    public static void registerDefinitions(RegisterEvent event) {
        registerOne(event, PRESS_ID, "pseudocore_curios.ability.press", Set.of("core", "press"));
    }

    /** One definition: INSTANT, the core category, the copper placeholder icon. */
    private static void registerOne(RegisterEvent event, ResourceLocation id,
                                    String displayKey, Set<String> tags) {
        event.register(org.bitwisemadness.abilitycore.registry.AbilityRegistries
                        .ABILITY_REGISTRY_KEY, id,
                () -> new AbilityDefinition(
                        displayKey,
                        ResourceLocation.fromNamespaceAndPath("pseudocore",
                                "textures/item/copper_pseudocore.png"),
                        ResourceLocation.fromNamespaceAndPath(PseudocoreCurios.MOD_ID, "core"),
                        ActivationType.INSTANT,
                        null,
                        tags,
                        Map.of()));
    }

    /**
     * Register the handler and the availability projection - called from
     * common setup, before any login can reach the pipeline. Idempotent
     * (both of core's registries fail loudly on duplicates).
     */
    public static void registerHandlers() {
        if (handlersRegistered) {
            return;
        }
        handlersRegistered = true;
        AbilityCore.HANDLERS.register(PRESS_ID, AbilityCoreBridge::press, ChargingModel.NATIVE);
        AvailabilityProviders.declare(PRESS_ID, AbilityCoreBridge::pressAvailability);
    }

    /**
     * The native pipeline: the curios slot stack through the hotkey's own
     * press (#11/#15 - the slot-based entry point), so the curios bar and
     * the hotkey are literally the same verb. The press has already
     * messaged the player for any refusal (#12's design), so only core's
     * outcome vocabulary travels back.
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
     * The press's overlay tuple (#69): cooldown progress from the shared
     * throttle, availability = the <b>active</b> core resolves to a core
     * (#71 - the hotkey's target), cost unknown for now - the contract
     * renders nothing rather than a lie, and the dynamic MP estimate
     * rides a pseudocore-side public API later.
     *
     * <p>The cooldown metric follows the same honesty rule: the throttle
     * lives in the server-only {@code LAST_USE} map, so only a
     * {@link ServerPlayer} can answer it - off-server the answer is
     * {@code unknown} (REQ-HOT-08 renders nothing for unknown) rather
     * than a projected zero that would draw "ready" mid-cooldown.
     * Core's cooldown contract (ability-core#16) is the eventual path
     * to a real client-side number.</p>
     */
    private static AbilityAvailability pressAvailability(Player player, ResourceLocation id) {
        Metric<Double> cooldown = player instanceof ServerPlayer serverPlayer
                ? Metric.known((double) ReleaseRecallAction.cooldownProgress(serverPlayer))
                : Metric.unknown();
        boolean hasCore = ReleaseRecallAction.isCore(ActiveCore.resolveStack(player).getItem());
        return new AbilityAvailability(
                cooldown,
                Metric.unknown(),
                hasCore ? AvailabilityState.available()
                        : AvailabilityState.unavailable("no active core"));
    }
}
