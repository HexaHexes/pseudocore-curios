package org.bitwisemadness.pseudocore.curios.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.bitwisemadness.pseudocore.curios.ActiveCore;
import org.bitwisemadness.pseudocore.curios.net.ReleaseRecallC2S;

/**
 * The client half of the release/recall hotkey (#11): the keybind and the
 * press-to-server send.
 *
 * <p>Default keysym {@value #DEFAULT_KEYSYM} - GLFW_KEY_GRAVE_ACCENT, the
 * {@code `} key left of 1 - rebindable through vanilla's Controls screen like
 * any KeyMapping. Only ever loaded behind a client-distribution check in the
 * mod constructor, so a dedicated server never resolves a client class.</p>
 *
 * <p>The press is gated on a Curios slot holding a core (#71: any of the
 * six): cheap, client-local, and purely about not sending presses that
 * would certainly be refused. The server re-checks everything against its
 * own state anyway - a stale client view can at worst suppress a press
 * until the next slot sync, never make the server act wrongly.</p>
 */
public final class ClientHotkey {

    /**
     * GLFW_KEY_GRAVE_ACCENT - pinned as a plain int so the junit constant pin
     * needs no GLFW (or client) classes at all: compile-time constant, inlined
     * at the call site, class never loaded by the test.
     */
    public static final int DEFAULT_KEYSYM = 96;

    private static KeyMapping releaseRecall;

    private ClientHotkey() {
    }

    /** Called from the mod constructor behind {@code Dist.CLIENT}. */
    public static void register(IEventBus modBus) {
        modBus.addListener(ClientHotkey::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener(ClientHotkey::onClientTick);
    }

    private static void onRegisterKeyMappings(
            net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        if (net.neoforged.fml.ModList.get().isLoaded("ability_hotbar")) {
            // The bar owns activation when installed (#69): no keybind, no
            // Controls entry, no key event. The air click and clear gesture
            // are player actions, not the hotkey, and stay.
            return;
        }
        releaseRecall = new KeyMapping(
                "key.pseudocore_curios.release_recall",
                DEFAULT_KEYSYM,
                "key.categories.pseudocore_curios");
        event.register(releaseRecall);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        while (releaseRecall != null && releaseRecall.consumeClick()) {
            var player = Minecraft.getInstance().player;
            if (player != null && coreInSlot(player)) {
                PacketDistributor.sendToServer(new ReleaseRecallC2S());
            }
        }
    }

    /**
     * The client's own view of the Curios slot (#71): present, with at
     * least one core in its six slots. The server resolves <b>which</b>
     * slot the press acts on; this precheck only skips presses that would
     * certainly be refused, so "any slot filled" is the exact predicate.
     */
    private static boolean coreInSlot(net.minecraft.world.entity.player.Player player) {
        var stacks = ActiveCore.stacks(player);
        if (stacks == null) {
            return false;
        }
        for (int i = 0; i < stacks.getSlots(); i++) {
            if (!stacks.getStackInSlot(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
