package org.bitwisemadness.pseudocore.curios.hotkey;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.bitwisemadness.pseudocore.curios.PseudocoreCurios;
import org.bitwisemadness.pseudocore.item.PseudocoreItem;
import org.bitwisemadness.pseudocore.registry.ModDataComponents;
import org.bitwisemadness.pseudocore.util.IafHornAccess;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The server side of the release/recall hotkey (#11): what one validated press
 * does.
 *
 * <p>The payload hands over nothing but the fact of the press, so everything
 * is checked here against live server state - the sender really has the
 * {@code pseudocore} slot, a core really sits in it, the cooldown is really
 * fresh. Only then does the press act: a filled core releases through the same
 * Phase-1 APIs the block/horn paths use ({@link PseudocoreItem#releaseContained}
 * for the beast line, {@link IafHornAccess#releaseDragonAt} for the dragon
 * line), which apply every gate they always did and post the
 * {@code PseudocoreReleaseEvent} our linkage listener already stamps (#8).</p>
 *
 * <p>Two guards, both load-bearing for an environment without Ice and Fire
 * (the CI gametest classpath is exactly that):</p>
 * <ul>
 *   <li>the dragon-filled read sits behind {@code isLoaded("iceandfire")} -
 *       {@link IafHornAccess#storedDragonType} matches on IaF's own component
 *       type, so calling it without IaF throws {@link NoClassDefFoundError};
 *       without IaF no dragon core can exist, so the guard never skips a true
 *       answer;</li>
 *   <li>item identity is decided by registry namespace rather than
 *       {@code instanceof DragonPseudocoreItem}: that class extends IaF's horn
 *       item, and IaF is not on this addon's <b>compile</b> classpath at all -
 *       the instanceof would not build, never mind run. Every item pseudocore
 *       registers is a core ({@code ModItems} holds nothing else), so
 *       "pseudocore namespace" and "one of the two core classes" are the same
 *       set by construction.</li>
 * </ul>
 */
public final class ReleaseRecallAction {

    /** How long one press locks the key: 20 ticks, a short anti-spam bar. */
    public static final long COOLDOWN_TICKS = 20L;

    /**
     * Last press time per player, read against the level's game time.
     *
     * <p>In-memory on purpose: a cooldown is session state, it must not follow
     * the player through death or relog, and entries are bounded by the
     * server's unique players per session - deliberately not worth an
     * attachment.</p>
     */
    private static final Map<UUID, Long> LAST_USE = new HashMap<>();

    private ReleaseRecallAction() {
    }

    /** Why a press did nothing; null means the press acted. */
    public enum Refusal {
        NO_SLOT("pseudocore_curios.message.no_slot"),
        NO_CORE("pseudocore_curios.message.no_core"),
        NOT_CORE("pseudocore_curios.message.not_core"),
        COOLDOWN("pseudocore_curios.message.cooldown"),
        NOTHING_TO_RELEASE("pseudocore_curios.message.nothing_to_release");

        private final String key;

        Refusal(String key) {
            this.key = key;
        }

        /** The actionbar text for this refusal, in pseudocore's message style. */
        public Component component() {
            return Component.translatable(this.key);
        }
    }

    /**
     * Run one press for this player, on the server thread.
     *
     * @return the refusal to tell the player, or null when the press acted
     */
    public static Refusal handle(ServerPlayer player) {
        var inventory = CuriosApi.getCuriosInventory(player).orElse(null);
        var stacks = inventory == null ? null
                : inventory.getCurios().get(PseudocoreCurios.SLOT_ID);
        if (stacks == null) {
            return Refusal.NO_SLOT;
        }

        ItemStack stack = stacks.getStacks().getStackInSlot(0);
        if (stack.isEmpty()) {
            return Refusal.NO_CORE;
        }
        if (!isCore(stack.getItem())) {
            return Refusal.NOT_CORE;
        }

        long now = player.level().getGameTime();
        Long last = LAST_USE.get(player.getUUID());
        if (last != null && now - last < COOLDOWN_TICKS) {
            return Refusal.COOLDOWN;
        }
        // Past every validation the press is remembered - including when the
        // branch below refuses - so the key cannot be used to probe release
        // gates faster than the cooldown allows.
        LAST_USE.put(player.getUUID(), now);

        boolean beast = stack.get(ModDataComponents.CONTAINED_MOB.get()) != null;
        boolean dragon = ModList.get().isLoaded("iceandfire")
                && IafHornAccess.storedDragonType(stack) != null;
        if (!beast && !dragon) {
            // Interim seam: #12 turns an empty press into the recall flow.
            return Refusal.NOTHING_TO_RELEASE;
        }

        ServerLevel level = (ServerLevel) player.level();
        // Release position per spec: the player's feet, their yaw.
        var pos = player.position();
        if (beast) {
            PseudocoreItem.releaseContained(stack, level, pos, player.getYRot(), player);
        } else {
            IafHornAccess.releaseDragonAt(stack, level, pos, player.getYRot(), player);
        }
        return null;
    }

    /**
     * Is this item a core of either line?
     *
     * <p>Spec-literal in effect ({@code PseudocoreItem} or
     * {@code DragonPseudocoreItem}) but expressed through the beast check plus
     * the registry namespace - see the class javadoc: the dragon class cannot
     * be named on this addon's classpath, and {@code ModItems} registers
     * nothing but cores, so the namespace answers exactly what the instanceof
     * would.</p>
     */
    private static boolean isCore(Item item) {
        if (item instanceof PseudocoreItem) {
            return true;
        }
        return BuiltInRegistries.ITEM.getKey(item)
                .getNamespace().equals(org.bitwisemadness.pseudocore.Pseudocore.MOD_ID);
    }
}
