package org.bitwisemadness.pseudocore.curios.client;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.bitwisemadness.pseudocore.curios.ActiveCore;
import org.bitwisemadness.pseudocore.curios.PseudocoreCurios;
import org.bitwisemadness.pseudocore.curios.linkage.ModAttachments;

/**
 * The selector strip (#71): the player's six cores laid out party-style
 * above the hotbar - display only: it frames the core the addon's own
 * hotkey acts on and takes no input of its own.
 *
 * <p>Every cell renders the live Curios stack: the carried core's own
 * {@code ItemStack}, so tier, filled state and enchantment glint come
 * from vanilla item rendering for free, and the Curios empty-slot icon
 * (the one this slot declares) where nothing is carried. The frame marks
 * {@link ActiveCore}'s <b>resolved</b> index - the same slot the press
 * acts on, clamped and fallen back by the same read - so the HUD can
 * never disagree with the verb.</p>
 *
 * <p>The strip renders only while at least one slot holds a core: until
 * the player owns a party there is no HUD furniture for it. Its y mirrors
 * vanilla's held-item-name band - {@code guiHeight - max(max(leftHeight,
 * rightHeight), 59)} as {@link net.minecraft.client.gui.Gui} computes it -
 * minus one cell and one gap, so the strip's bottom edge lands a clear
 * pixel above where the transient name draws. The name renders during
 * {@code Gui.render} and this listener runs after it, with
 * {@code leftHeight}/{@code rightHeight} already final for the frame; the
 * vanilla layers self-gate on the hidden-GUI flag, so this one checks it
 * itself.</p>
 */
public final class SelectorStrip {

    /**
     * The Curios empty-slot icon this slot declares
     * ({@code curios/slots/pseudocore.json} "icon"), 16x16.
     */
    private static final ResourceLocation EMPTY_SLOT =
            ResourceLocation.fromNamespaceAndPath("curios", "slot/empty_curio_slot");

    private static final int CELL = 16;
    private static final int GAP = 1;
    /** One cell + one gap: the strip's bottom edge lands a pixel above the name band. */
    private static final int CELL_PITCH = CELL + GAP;
    private static final int STRIP_WIDTH = PseudocoreCurios.SLOT_COUNT * CELL_PITCH - GAP;
    /** Vanilla's held-item-name baseline when no overlay pushes it up (#71's anchor). */
    private static final int NAME_BAND = 59;
    /** The active cell's frame, one pixel outside the cell so it never covers the icon. */
    private static final int ACTIVE_FRAME = 0xFFFFFFFF;

    private SelectorStrip() {
    }

    /** Called from the mod constructor behind {@code Dist.CLIENT}. */
    public static void register(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(SelectorStrip::onRenderGui);
    }

    private static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null) {
            return;
        }
        var stacks = ActiveCore.stacks(minecraft.player);
        if (stacks == null) {
            return;
        }
        // Visibility (#71): one resolution serves both the rule and the
        // frame - it is -1 only when every slot is empty.
        int active = ActiveCore.resolveIndex(stacks, ModAttachments.activeCoreIndex(minecraft.player));
        if (active < 0) {
            return;
        }

        var graphics = event.getGuiGraphics();
        int x0 = (graphics.guiWidth() - STRIP_WIDTH) / 2;
        int nameBand = graphics.guiHeight()
                - Math.max(Math.max(minecraft.gui.leftHeight, minecraft.gui.rightHeight), NAME_BAND);
        int y = nameBand - CELL_PITCH;

        int slots = Math.min(stacks.getSlots(), PseudocoreCurios.SLOT_COUNT);
        for (int slot = 0; slot < slots; slot++) {
            int x = x0 + slot * CELL_PITCH;
            ItemStack stack = stacks.getStackInSlot(slot);
            if (stack.isEmpty()) {
                graphics.blit(EMPTY_SLOT, x, y, 0.0F, 0.0F, CELL, CELL, 16, 16);
            } else {
                graphics.renderItem(stack, x, y);
                graphics.renderItemDecorations(minecraft.font, stack, x, y);
            }
            if (slot == active) {
                graphics.renderOutline(x - 1, y - 1, CELL + 2, CELL + 2, ACTIVE_FRAME);
            }
        }
    }
}
