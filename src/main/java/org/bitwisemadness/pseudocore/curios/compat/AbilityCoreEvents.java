package org.bitwisemadness.pseudocore.curios.compat;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.bitwisemadness.pseudocore.curios.PseudocoreCurios;

/**
 * The mod-bus half of the ability-core seam (#69): definition
 * registration on core's abilities registry, and handler + availability
 * registration at common setup. Every method's signature is
 * vanilla/NeoForge-only and every body starts with the bridge's
 * {@code isLoaded()} gate, so the optional dependency stays optional -
 * the {@code IafHornAccess} rule for subscriber classes.
 *
 * <p>The bus is not spelled out: {@code AutomaticEventSubscriber} routes
 * each listener from its event type, and both events here are mod-bus
 * events.</p>
 */
@EventBusSubscriber(modid = PseudocoreCurios.MOD_ID)
public final class AbilityCoreEvents {

    private AbilityCoreEvents() {
    }

    @SubscribeEvent
    public static void onRegisterAbilityRegistry(RegisterEvent event) {
        if (!AbilityCoreBridge.isLoaded()) {
            return;
        }
        // exactly one registry: press's definition and mastery declaration
        // ride core's abilities key - cross-key dispatch is a silent no-op
        // for registration, but a duplicate declaration for mastery
        if (!event.getRegistryKey().equals(
                org.bitwisemadness.abilitycore.registry.AbilityRegistries.ABILITY_REGISTRY_KEY)) {
            return;
        }
        AbilityCoreBridge.registerDefinitions(event);
    }

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        if (AbilityCoreBridge.isLoaded()) {
            AbilityCoreBridge.registerHandlers();
        }
    }
}
