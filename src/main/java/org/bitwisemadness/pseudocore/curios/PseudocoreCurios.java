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
    public static final Logger LOGGER = LogUtils.getLogger();

    public PseudocoreCurios(IEventBus modBus) {
        LOGGER.info("Pseudocore Curios initialized");
    }
}
