package org.bitwisemadness.pseudocore.curios.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.bitwisemadness.pseudocore.curios.PseudocoreCurios;
import org.bitwisemadness.pseudocore.curios.client.ClientWaypoint;

/**
 * Server to client: "the recall found nothing - here is where the mob was
 * last seen" (#13).
 *
 * <p>Sent alongside the unreachable actionbar line, carrying exactly the
 * numbers that line prints (same rounding, same record), so the waypoint
 * and the message can never disagree. The client drops it into Xaero's
 * waypoint list when Xaero is there; without Xaero the payload is simply
 * dropped ({@link ClientWaypoint} gates on the mod).</p>
 *
 * @param name      the mob's display name - the waypoint's name
 * @param x         last-known position, rounded
 * @param y         last-known position, rounded
 * @param z         last-known position, rounded
 * @param dimension the dimension id the record lives in
 */
public record UnreachableRecallS2C(String name, int x, int y, int z, String dimension)
        implements CustomPacketPayload {

    public static final Type<UnreachableRecallS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PseudocoreCurios.MOD_ID, "unreachable_recall"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UnreachableRecallS2C> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, UnreachableRecallS2C::name,
                    ByteBufCodecs.VAR_INT, UnreachableRecallS2C::x,
                    ByteBufCodecs.VAR_INT, UnreachableRecallS2C::y,
                    ByteBufCodecs.VAR_INT, UnreachableRecallS2C::z,
                    ByteBufCodecs.STRING_UTF8, UnreachableRecallS2C::dimension,
                    UnreachableRecallS2C::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Client-side hop onto the main thread: Xaero's waypoint storage is
     * client world state and never touched from the network thread.
     */
    public static void handle(UnreachableRecallS2C payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientWaypoint.mark(payload));
    }
}
