package org.bitwisemadness.pseudocore.curios.compat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
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
import org.bitwisemadness.abilitycore.api.AmountResolvers;
import org.bitwisemadness.abilitycore.api.AvailabilityProviders;
import org.bitwisemadness.abilitycore.api.AvailabilityState;
import org.bitwisemadness.abilitycore.api.ChargingModel;
import org.bitwisemadness.abilitycore.api.ConsumptionStrategies;
import org.bitwisemadness.abilitycore.api.Metric;
import org.bitwisemadness.abilitycore.api.TargetContext;
import org.bitwisemadness.pseudocore.curios.ActiveCore;
import org.bitwisemadness.pseudocore.curios.PseudocoreCurios;
import org.bitwisemadness.pseudocore.curios.hotkey.ReleaseRecallAction;
import org.bitwisemadness.pseudocore.item.PseudocoreItem;

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

    /**
     * One definition: INSTANT, the core category, the copper placeholder icon.
     * No costs under core's default {@code all} strategy: the press charges
     * natively in its own pipeline (#11/#15), and core only refuses an empty
     * list for EXTERNAL abilities - which this one is not. The amount
     * resolver is core's built-in {@code static} identity - the ninth
     * constructor argument since core alpha.39 (ability-core#103/#104) -
     * so the nominal costs are priced per request unchanged.
     */
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
                        List.of(),
                        ConsumptionStrategies.ALL,
                        AmountResolvers.STATIC,
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
     * The native pipeline entry (#72): a curios-bar cell pressing its own
     * core - {@code slotContext} is the cell index, mapped to that Curios
     * slot through {@link ReleaseRecallAction#handleSlot}, so the bar
     * names its target explicitly while the addon's own hotkey (a
     * separate packet, active-core) keeps today's semantics untouched.
     * The press has already messaged the player for any refusal (#12's
     * design), so only core's outcome vocabulary travels back.
     *
     * <p>An aimed press - a crosshair {@link TargetContext.EntityHint} on
     * the request (hotbar#94's population) - is the capture half of the
     * same cell: #71's press-with-target ruling, {@link #captureAimed}.</p>
     */
    private static ActivationResult press(ActivationRequest request, ServerPlayer player) {
        int slot = request.slotContext();
        if (slot < 0) {
            // The pipeline's press exists only for the curios bar's
            // seeded cells (#72): a request without one refuses
            // fail-closed rather than guessing which core is meant.
            return ActivationResult.refused(ActivationOutcome.REFUSED_UNAVAILABLE);
        }
        if (request.target() instanceof TargetContext.EntityHint hint) {
            return captureAimed(player, slot, hint);
        }
        ReleaseRecallAction.Refusal refusal = ReleaseRecallAction.handleSlot(player, slot);
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
     * Aimed capture - #71's press-with-target ruling: the cell's core
     * captures the entity the crosshair named. The hint arrives already
     * resolved on the wire path (TargetValidationStep), but the handler
     * re-looks it up itself - the HANDLERS entry is reachable without
     * the pipeline. The cell is checked (a stale seeded cell refuses,
     * never a neighbour), the target is range-checked with vanilla's
     * own {@code canInteractWithEntity} convention (+1.0 - the buffer
     * its interact packets use), and the capture runs through the
     * ordinary {@link PseudocoreItem#tryCapture} gates, charges and
     * messages - the pipeline only carries the outcome vocabulary.
     */
    private static ActivationResult captureAimed(ServerPlayer player, int slot,
                                                 TargetContext.EntityHint hint) {
        var stacks = ActiveCore.stacks(player);
        if (stacks == null || slot < 0 || slot >= stacks.getSlots()
                || !ReleaseRecallAction.isCore(stacks.getStackInSlot(slot).getItem())) {
            // Stale cell (seeded, then emptied) or a slot that never held
            // a core: the bar shows unavailable; no message, no guessing.
            return ActivationResult.refused(ActivationOutcome.REFUSED_UNAVAILABLE);
        }
        var entity = player.serverLevel().getEntity(hint.entityId());
        if (!(entity instanceof LivingEntity target)
                || !player.canInteractWithEntity(target, 1.0)) {
            return ActivationResult.refused(ActivationOutcome.REFUSED_INVALID_TARGET);
        }
        if (PseudocoreItem.tryCapture(player, target, stacks.getStackInSlot(slot))
                == InteractionResult.SUCCESS) {
            return ActivationResult.ACTED;
        }
        // tryCapture has already messaged its specific reason; a single
        // InteractionResult cannot separate gates from an empty pool, so
        // the outcome is RESTRICTED (the actionbar tells the exact truth).
        return ActivationResult.refused(ActivationOutcome.REFUSED_RESTRICTED);
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
