package org.bitwisemadness.pseudocore.curios.net;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers this addon's payloads on the mod event bus (#11).
 *
 * <p>One registrar, version {@value #PROTOCOL_VERSION} - bump it alongside any
 * payload shape change so mismatched clients fail loudly instead of decoding
 * garbage. More payloads join here as the queue lands: the recall flow shares
 * {@link ReleaseRecallC2S} (the same press), and the waypoint issue adds the
 * serverbound/clientbound pair of its own.</p>
 */
public final class ModNetwork {

    /** Protocol version; bump alongside any payload shape change. */
    private static final String PROTOCOL_VERSION = "1";

    private ModNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(
                ReleaseRecallC2S.TYPE,
                ReleaseRecallC2S.STREAM_CODEC,
                ReleaseRecallC2S::handle);
        registrar.playToClient(
                UnreachableRecallS2C.TYPE,
                UnreachableRecallS2C.STREAM_CODEC,
                UnreachableRecallS2C::handle);
    }
}
