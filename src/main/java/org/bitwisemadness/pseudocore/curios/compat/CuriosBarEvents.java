package org.bitwisemadness.pseudocore.curios.compat;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.bitwisemadness.pseudocore.curios.PseudocoreCurios;
import top.theillusivec4.curios.api.event.CurioChangeEvent;

/**
 * The ability-hotbar seam's event half (#72): registration at common
 * setup, and re-seeding whenever the bar's content truth changes -
 * player login and every Curios content change for <b>our</b> slot.
 * Signatures are vanilla/NeoForge/curios types only (curios is a hard
 * dependency of this addon), and every body starts with the bridge's
 * {@code isLoaded()} gate - the {@code IafHornAccess} rule for
 * subscriber classes, so ability-hotbar stays optional.
 *
 * <p>Routing is not spelled out: {@code AutomaticEventSubscriber}
 * places each listener from its event type - {@code FMLCommonSetupEvent}
 * on the mod bus, the rest on the game bus.</p>
 */
@EventBusSubscriber(modid = PseudocoreCurios.MOD_ID)
public final class CuriosBarEvents {

    private CuriosBarEvents() {
    }

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        if (CuriosBarBridge.isLoaded()) {
            CuriosBarBridge.register();
        }
    }

    /** Login seeds the bar before the player can wheel to it (#72). */
    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (CuriosBarBridge.isLoaded() && event.getEntity() instanceof ServerPlayer player) {
            CuriosBarBridge.seed(player);
        }
    }

    /**
     * Curios is the source of truth (#72): equip/unequip re-derives the
     * whole bar (a cheap idempotent overwrite). Filtered to our slot's
     * identifier - every other curios slot in the game also fires this
     * event, and we must never seed from someone else's changes.
     */
    @SubscribeEvent
    public static void onCurioChange(CurioChangeEvent event) {
        if (!CuriosBarBridge.isLoaded()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (PseudocoreCurios.SLOT_ID.equals(event.getIdentifier())) {
            CuriosBarBridge.seed(player);
        }
    }
}
