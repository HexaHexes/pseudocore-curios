package org.bitwisemadness.pseudocore.curios.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.bitwisemadness.pseudocore.curios.PseudocoreCurios;
import org.bitwisemadness.pseudocore.curios.hotkey.ReleaseRecallAction;

/**
 * Client to server: "the release/recall hotkey was pressed" (#11).
 *
 * <p>Carries no content on purpose - it is a press, not an instruction. Every
 * decision (does the sender have the slot, is a core in it, is the cooldown
 * fresh, is the core filled) is the server's to make in
 * {@link ReleaseRecallAction}; the client's own slot view only decides whether
 * the press is worth sending at all, and a stale client view can therefore
 * never make the server do the wrong thing.</p>
 */
public record ReleaseRecallC2S() implements CustomPacketPayload {

    public static final Type<ReleaseRecallC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PseudocoreCurios.MOD_ID, "release_recall"));

    /** Empty payload: a unit codec writes and reads nothing. */
    public static final StreamCodec<RegistryFriendlyByteBuf, ReleaseRecallC2S> STREAM_CODEC =
            StreamCodec.unit(new ReleaseRecallC2S());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Handles the press on the server thread.
     *
     * <p>{@code enqueueWork} is what makes the world access legal: the network
     * thread never touches a level, a player inventory, or the game time the
     * cooldown reads. Refusals are told back as an actionbar message in the
     * same style pseudocore's capture gates use.</p>
     */
    public static void handle(ReleaseRecallC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ReleaseRecallAction.Refusal refusal = ReleaseRecallAction.handle(player);
                if (refusal != null) {
                    player.displayClientMessage(refusal.component().copy()
                            .withStyle(net.minecraft.ChatFormatting.RED), true);
                }
            }
        });
    }
}
