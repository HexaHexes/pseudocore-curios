package org.bitwisemadness.pseudocore.curios;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.bitwisemadness.pseudocore.curios.linkage.ModAttachments;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

/**
 * The active core (#71): which of the six Curios slots the press acts
 * on and the curios bar frames. The selection is server-authoritative
 * (a synced player attachment, see {@link ModAttachments}), but resolution
 * is a pure read - raw index clamped to the live slot count, falling back
 * to the first non-empty slot, so removing the active core can never
 * strand the selection on an empty one.
 *
 * <p>Resolution lives here rather than in the press so every consumer -
 * the hotkey, the ability bridge, the death search, the client strip -
 * derives the <b>same</b> slot from the same state, and the pure
 * {@link #resolveIndex(IDynamicStackHandler, int)} core needs no player,
 * no curios lookup and no side.</p>
 */
public final class ActiveCore {

    private ActiveCore() {
    }

    /**
     * The live Curios stacks for this addon's slot, or null when the
     * player has no such slot. Works on both sides: Curios syncs the
     * slot contents to the owning client.
     */
    public static IDynamicStackHandler stacks(Player player) {
        var handler = CuriosApi.getCuriosInventory(player)
                .map(inventory -> inventory.getCurios().get(PseudocoreCurios.SLOT_ID))
                .orElse(null);
        return handler == null ? null : handler.getStacks();
    }

    /**
     * The slot a press acts on (#71): the stored selection clamped to the
     * live slot count, falling back to the first non-empty slot when that
     * selection points at an emptied slot, and -1 only when every slot is
     * empty. Read-time resolution - the stored selection is never rewritten
     * by looking at it.
     *
     * @param stacks   the live slot stacks, or any empty handler
     * @param rawIndex the stored selection, before clamping
     */
    public static int resolveIndex(IDynamicStackHandler stacks, int rawIndex) {
        int slots = stacks.getSlots();
        if (slots <= 0) {
            return -1;
        }
        int active = Math.min(Math.max(rawIndex, 0), slots - 1);
        if (!stacks.getStackInSlot(active).isEmpty()) {
            return active;
        }
        for (int i = 0; i < slots; i++) {
            if (!stacks.getStackInSlot(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** {@link #resolveIndex(IDynamicStackHandler, int)} for a player: live stacks + stored selection. */
    public static int resolveIndex(Player player) {
        IDynamicStackHandler stacks = stacks(player);
        return stacks == null ? -1 : resolveIndex(stacks, ModAttachments.activeCoreIndex(player));
    }

    /** The stack the press acts on, or EMPTY when no slot holds anything (#71). */
    public static ItemStack resolveStack(Player player) {
        IDynamicStackHandler stacks = stacks(player);
        if (stacks == null) {
            return ItemStack.EMPTY;
        }
        int index = resolveIndex(stacks, ModAttachments.activeCoreIndex(player));
        return index < 0 ? ItemStack.EMPTY : stacks.getStackInSlot(index);
    }

    /** One slot's stack, EMPTY when out of range or the slot is missing (#71). */
    public static ItemStack slotStack(Player player, int slot) {
        IDynamicStackHandler stacks = stacks(player);
        if (stacks == null || slot < 0 || slot >= stacks.getSlots()) {
            return ItemStack.EMPTY;
        }
        return stacks.getStackInSlot(slot);
    }
}
