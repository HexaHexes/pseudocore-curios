package org.bitwisemadness.pseudocore.curios.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.bitwisemadness.abilityhotbar.content.HotbarContentProvider;
import org.bitwisemadness.pseudocore.curios.ActiveCore;
import org.bitwisemadness.pseudocore.curios.PseudocoreCurios;
import org.bitwisemadness.pseudocore.curios.compat.CuriosBarBridge;

/**
 * What the addon's curios bar shows (#72): the six Curios slots,
 * positionally - a filled slot renders the core's own {@code ItemStack}
 * (tint, foil and the mob shimmer all ride vanilla item rendering), an
 * empty slot renders an <b>empty</b> cell (the provider's default
 * chrome - deliberately <b>not</b> the Curios empty-slot icon, per the
 * 2026-11-04 ruling), and the frame marks the resolved active core so
 * the bar and the strip can never disagree about which core the addon's
 * own hotkey targets.
 *
 * <p>Reads client-side every frame through {@link ActiveCore#stacks},
 * which works on the owning client because Curios syncs slot contents -
 * no new sync, no server round trip, exactly the
 * {@link HotbarContentProvider} contract. {@code hasContent()} is the
 * derived default (any core), which is also the skip-empty answer.</p>
 */
public final class CuriosBarContentProvider implements HotbarContentProvider {

    @Override
    public int slotCount() {
        // Six positional cells always: without a player (or without the
        // slot) every cell is simply empty, and ContentPlan requires [1, 18].
        return PseudocoreCurios.SLOT_COUNT;
    }

    @Override
    public ItemStack slotStack(int index) {
        if (index < 0 || index >= PseudocoreCurios.SLOT_COUNT) {
            return ItemStack.EMPTY;
        }
        var player = Minecraft.getInstance().player;
        return player == null ? ItemStack.EMPTY : ActiveCore.slotStack(player, index);
    }

    @Override
    public int activeSlot() {
        // Ruling (2026-11-05, #82): with Ability Hotbar loaded the addon's
        // keybind is unregistered, so the bar's wheel selection IS the
        // active slot - the frame follows it; the attachment is fallback.
        var state = org.bitwisemadness.abilityhotbar.client.ClientBarState.get();
        if (state != null) {
            for (var bar : state.bars()) {
                if (CuriosBarBridge.CURIO_BAR.equals(bar.id())) {
                    return Math.min(bar.selectedSlot(), PseudocoreCurios.SLOT_COUNT - 1);
                }
            }
        }
        var player = Minecraft.getInstance().player;
        return player == null ? -1 : ActiveCore.resolveIndex(player);
    }
}
