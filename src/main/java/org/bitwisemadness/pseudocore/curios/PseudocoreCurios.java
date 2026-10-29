package org.bitwisemadness.pseudocore.curios;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Pseudocore Curios entry point.
 *
 * <p>The companion mod to Pseudocore for the Tensura pack: a Curios slot that
 * holds a Pseudocore core, a rebindable hotkey that releases the stored mob at
 * the player's position and recalls it again through the normal capture gates,
 * and the Pseudocore ecosystem's Tensura data (Gear Evolution chains for every
 * core tier) - Tensura-side integration lives here rather than in base
 * Pseudocore, mirroring how the wallet chains ship in lightmans-tensura.</p>
 */
@Mod(PseudocoreCurios.MOD_ID)
public final class PseudocoreCurios {

    public static final String MOD_ID = "pseudocore_curios";
    /**
     * The Curios slot id this addon defines ({@code curios/slots/pseudocore.json}).
     * A plain String constant on purpose: the client's hotkey precheck and the
     * server's action handler both read it, and a constant inlines at both call
     * sites without loading either side's classes into the other's runtime.
     */
    public static final String SLOT_ID = "pseudocore";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PseudocoreCurios(IEventBus modBus, net.neoforged.fml.ModContainer container) {
        org.bitwisemadness.pseudocore.curios.linkage.ModAttachments.ATTACHMENT_TYPES.register(modBus);
        org.bitwisemadness.pseudocore.curios.linkage.ModLinkageComponents.DATA_COMPONENTS.register(modBus);
        org.bitwisemadness.pseudocore.curios.PseudocoreCuriosConfig.register(container);
        modBus.addListener(org.bitwisemadness.pseudocore.curios.net.ModNetwork::register);
        if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) {
            // The reference below only resolves when this branch runs, so a
            // dedicated server never loads the client hotkey class.
            org.bitwisemadness.pseudocore.curios.client.ClientHotkey.register(modBus);
            // Death-preserve's client half (#14): tint + tooltip - the same
            // dist discipline for the same reason.
            org.bitwisemadness.pseudocore.curios.client.DeathCoreClient.register(modBus);
        }
        LOGGER.info("Pseudocore Curios initialized");
    }
}
