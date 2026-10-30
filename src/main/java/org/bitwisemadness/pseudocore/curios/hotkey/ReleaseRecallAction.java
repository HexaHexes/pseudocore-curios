package org.bitwisemadness.pseudocore.curios.hotkey;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.bitwisemadness.pseudocore.curios.PseudocoreCurios;
import org.bitwisemadness.pseudocore.curios.linkage.LinkageManager;
import org.bitwisemadness.pseudocore.curios.linkage.LinkageRecord;
import org.bitwisemadness.pseudocore.curios.linkage.ModLinkageComponents;
import org.bitwisemadness.pseudocore.curios.net.UnreachableRecallS2C;
import org.bitwisemadness.pseudocore.item.PseudocoreItem;
import org.bitwisemadness.pseudocore.registry.ModDataComponents;
import org.bitwisemadness.pseudocore.util.IafHornAccess;
import top.theillusivec4.curios.api.CuriosApi;

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
 * <p>An empty press runs the recall flow (#12): linkage-scoped (the core's own
 * stamp resolves the record), loaded chunks only - a chunk-unloaded or gone
 * mob answers with the mob's last-known position and dimension, never a
 * force-load - and capture through the real gate paths ({@link
 * PseudocoreItem#tryCapture} / {@link IafHornAccess#tryCaptureDragon}), so
 * anti-theft and every other gate re-apply unchanged. A successful recall
 * forgets its record ({@link LinkageManager#forget}, #18's consumer
 * contract); a gate-refused one reports {@link Refusal#GATED}, because the
 * gates decide and this mod does not paraphrase them.</p>
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
        NOT_LINKED("pseudocore_curios.message.not_linked"),
        UNREACHABLE("pseudocore_curios.message.unreachable"),
        /** The core holds preserved death data (#14) - nothing to release. */
        HAS_DEAD("pseudocore_curios.message.has_dead"),
        /**
         * A gate already spoke for itself - a capture/release gate's own
         * actionbar line, or IaF's click-path silence on an ownership
         * decline. Never messaged here by design: the gates decide, and
         * this mod does not paraphrase them (#11, #12).
         */
        GATED(null);

        private final String key;

        Refusal(String key) {
            this.key = key;
        }

        /**
         * The actionbar text for this refusal, in pseudocore's message style.
         * Takes the same arguments the translation key declares (the
         * unreachable message names the mob, the coordinates and the
         * dimension).
         */
        public Component component(Object... args) {
            return Component.translatable(this.key, args);
        }
    }

    /**
     * Run one press for this player, on the server thread - resolving the
     * core from the Curios slot (#11).
     *
     * @return the refusal to tell the player, or null when the press acted
     */
    public static Refusal handle(ServerPlayer player) {
        var inventory = CuriosApi.getCuriosInventory(player).orElse(null);
        var stacks = inventory == null ? null
                : inventory.getCurios().get(PseudocoreCurios.SLOT_ID);
        if (stacks == null) {
            return refuse(player, Refusal.NO_SLOT);
        }

        ItemStack stack = stacks.getStacks().getStackInSlot(0);
        if (stack.isEmpty()) {
            return refuse(player, Refusal.NO_CORE);
        }
        if (!isCore(stack.getItem())) {
            return refuse(player, Refusal.NOT_CORE);
        }
        return press(player, stack);
    }

    /**
     * One press against a <b>given</b> core stack: the shared body behind
     * both entry points - the slot hotkey ({@link #handle}) and the air
     * right-click (#34) - so cooldown, release and recall can never drift
     * apart between them. Slot-shape refusals ({@code NO_SLOT} and friends)
     * belong to the caller that resolved the stack; this method only
     * validates what a stack can be wrong about.
     *
     * @return the refusal to tell the player, or null when the press acted
     */
    public static Refusal press(ServerPlayer player, ItemStack stack) {
        Refusal cooldown = checkCooldown(player);
        if (cooldown != null) {
            return refuse(player, cooldown);
        }

        boolean beast = stack.get(ModDataComponents.CONTAINED_MOB.get()) != null;
        boolean dragon = ModList.get().isLoaded("iceandfire")
                && IafHornAccess.storedDragonType(stack) != null;
        if (beast || dragon) {
            // A refused release has already said its piece through the base
            // API's own gates; the press consumed the cooldown above either way.
            return releaseNow(player, stack) ? null : Refusal.GATED;
        }
        // Death-preserve state (#14): a dead-record core reports itself and
        // never releases (nothing live to release) nor recalls (the mob is
        // gone - the record could only answer UNREACHABLE). Live storage
        // plus stale death data never reaches this branch: the beast/dragon
        // check above wins - the issue's "counts as filled" rule.
        if (stack.has(org.bitwisemadness.pseudocore.curios.linkage.ModLinkageComponents.DEAD_MOB.get())) {
            return refuse(player, Refusal.HAS_DEAD, stack.getHoverName());
        }
        // Empty core: the recall flow (#12).
        return recall(player, stack);
    }

    /**
     * The one throttle shared by every trigger - hotkey, air click, clear
     * gesture (#11/#34/#15): returns the cooldown refusal while locked, and
     * <b>remembers the press the moment it passes</b> - so no trigger can
     * probe release gates faster than the window allows. Callers message
     * the refusal; this method stays silent by design.
     */
    static Refusal checkCooldown(ServerPlayer player) {
        long now = player.level().getGameTime();
        Long last = LAST_USE.get(player.getUUID());
        if (last != null && now - last < COOLDOWN_TICKS) {
            return Refusal.COOLDOWN;
        }
        LAST_USE.put(player.getUUID(), now);
        return null;
    }

    /**
     * Spawn the stored mob at the player's feet - one implementation for
     * the hotkey press, the air click and the clear gesture's `release`
     * mode (#15's shared-helper requirement), applying the base APIs'
     * gates as always.
     *
     * @return true when something was released, false when the stack held
     *         nothing live (callers decide what that refusal means)
     */
    static boolean releaseNow(ServerPlayer player, ItemStack stack) {
        boolean beast = stack.get(ModDataComponents.CONTAINED_MOB.get()) != null;
        boolean dragon = ModList.get().isLoaded("iceandfire")
                && IafHornAccess.storedDragonType(stack) != null;
        if (!beast && !dragon) {
            return false;
        }
        ServerLevel level = (ServerLevel) player.level();
        var pos = player.position();
        return beast
                ? PseudocoreItem.releaseContained(stack, level, pos, player.getYRot(), player)
                : IafHornAccess.releaseDragonAt(stack, level, pos, player.getYRot(), player);
    }

    /**
     * The linkage-scoped recall (#12): resolve the core's stamp to its
     * record, then capture the tracked mob through the real gate paths -
     * loaded chunks only, never a force-load.
     *
     * <p>Cross-dimension only when the stored dimension exists as a level at
     * all; otherwise the unreachable message names the dimension. The
     * message and the level lookup parse the record's dimension id
     * independently, so a malformed record degrades to the unreachable path
     * rather than crashing the press (#13 will hang its waypoint off that
     * same branch).</p>
     */
    private static Refusal recall(ServerPlayer player, ItemStack stack) {
        UUID linkage = stack.get(ModLinkageComponents.LINKAGE.get());
        if (linkage == null) {
            return refuse(player, Refusal.NOT_LINKED);
        }
        LinkageRecord record = LinkageManager.find(player, linkage);
        if (record == null) {
            // Never recorded here, or evicted past the #18 cap: the same
            // player-facing answer - this core recalls nothing.
            return refuse(player, Refusal.NOT_LINKED);
        }

        ServerLevel level;
        try {
            level = player.getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(record.dimension())));
        } catch (RuntimeException badDimension) {
            level = null;
        }
        if (level == null) {
            return unreachable(player, record);
        }

        Entity entity = level.getEntity(UUID.fromString(record.mobUuid()));
        if (!(entity instanceof LivingEntity living)) {
            // Unloaded chunk or a mob that is simply gone: the last-known
            // position message, never a chunk load (#12, locked).
            return unreachable(player, record);
        }

        InteractionResult result = stack.getItem() instanceof PseudocoreItem
                ? PseudocoreItem.tryCapture(player, living, stack)
                : IafHornAccess.tryCaptureDragon(player, living, stack);
        if (result == InteractionResult.SUCCESS) {
            LinkageManager.forget(player, linkage);
            return null;
        }
        return Refusal.GATED;
    }

    /** The last-known-position actionbar line, naming the mob and dimension (#12). */
    private static Refusal unreachable(ServerPlayer player, LinkageRecord record) {
        // The waypoint half (#13): same record, same numbers as the message
        // below - payloadFor is the one builder, so they cannot disagree.
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, payloadFor(record));
        Component dimension;
        try {
            ResourceLocation id = ResourceLocation.parse(record.dimension());
            dimension = Component.translatable("dimension." + id.getNamespace() + "." + id.getPath());
        } catch (RuntimeException badDimension) {
            // A record that never should exist still gets its message out.
            dimension = Component.literal(record.dimension());
        }
        return refuse(player, Refusal.UNREACHABLE, record.name(),
                Math.round(record.x()), Math.round(record.y()), Math.round(record.z()), dimension);
    }

    /**
     * The waypoint payload the unreachable branch sends (#13): the record's
     * name, position rounded exactly as the message rounds it, and the
     * dimension id. Public so the gametest asserts the very builder the
     * branch hands to {@code PacketDistributor} - one line away.
     */
    public static UnreachableRecallS2C payloadFor(LinkageRecord record) {
        return new UnreachableRecallS2C(record.name(),
                (int) Math.round(record.x()), (int) Math.round(record.y()), (int) Math.round(record.z()),
                record.dimension());
    }

    /** Tell the player, red on the actionbar, exactly as the base gates do. */
    private static Refusal refuse(ServerPlayer player, Refusal refusal, Object... args) {
        player.displayClientMessage(
                refusal.component(args).copy().withStyle(ChatFormatting.RED), true);
        return refusal;
    }

    /**
     * Is this item a core of either line?
     *
     * <p>Spec-literal in effect ({@code PseudocoreItem} or
     * {@code DragonPseudocoreItem}) but expressed through the beast check plus
     * the registry namespace - see the class javadoc: the dragon class cannot
     * be named on this addon's classpath, and {@code ModItems} registers
     * nothing but cores, so the namespace answers exactly what the instanceof
     * would. Public because the air right-click listener (#34) gates on this
     * too: an {@code instanceof PseudocoreItem} there locked the entire
     * dragon line out of the press family - {@code DragonPseudocoreItem}
     * extends IaF's horn item, not {@code PseudocoreItem} (#48).</p>
     */
    public static boolean isCore(Item item) {
        if (item instanceof PseudocoreItem) {
            return true;
        }
        return BuiltInRegistries.ITEM.getKey(item)
                .getNamespace().equals(org.bitwisemadness.pseudocore.Pseudocore.MOD_ID);
    }
}
