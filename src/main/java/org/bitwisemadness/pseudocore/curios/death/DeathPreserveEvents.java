package org.bitwisemadness.pseudocore.curios.death;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import org.bitwisemadness.pseudocore.curios.PseudocoreCurios;
import org.bitwisemadness.pseudocore.curios.PseudocoreCuriosConfig;
import org.bitwisemadness.pseudocore.curios.linkage.LinkageManager;
import org.bitwisemadness.pseudocore.curios.linkage.LinkageRecord;
import org.bitwisemadness.pseudocore.curios.linkage.ModLinkageComponents;
import org.bitwisemadness.pseudocore.item.PseudocoreItem;
import org.bitwisemadness.pseudocore.item.component.ContainedMobComponent;
import org.bitwisemadness.pseudocore.registry.ModDataComponents;
import org.bitwisemadness.pseudocore.util.IafHornAccess;

import java.util.UUID;

/**
 * The death-preserve hook (#14): a tracked mob about to die from a
 * configured source is saved into the core that released it, instead of
 * dying.
 *
 * <p>The issue's flow, in order, each gate a plain early return that
 * leaves the death completely untouched:</p>
 * <ol>
 *   <li>feature enabled (config);</li>
 *   <li>this hit is lethal ({@code health - newDamage <= 0});</li>
 *   <li>the mob carries a linkage tag (it was released by this addon's
 *       flow - everything else is nobody's business);</li>
 *   <li>the damage type is whitelisted (void and friends stay lethal by
 *       omission);</li>
 *   <li>the origin player is online (their attachment holds the record -
 *       the search is linkage-scoped through them, always);</li>
 *   <li>the core resolves: <b>slot, then inventory, then corpse</b>, per
 *       config - each leg requiring the linkage stamp match and no live
 *       mob inside (a stale {@code dead_mob} is fine: this write replaces
 *       it);</li>
 *   <li><b>capture gates first</b>: the real {@code tryCapture} /
 *       {@code tryCaptureDragon}, so anti-theft and every tier gate
 *       re-apply - a gate refusal means normal death, unchanged;</li>
 *   <li>migration: the live storage capture just wrote becomes
 *       {@code dead_mob} (plus the death facts), the live component is
 *       stripped - base pseudocore then sees an <b>empty</b> core.</li>
 * </ol>
 *
 * <p>Zeroing the damage after a successful capture matters: capture
 * discards the mob mid-event, and the following health application must
 * not walk a discarded entity into the death path.</p>
 *
 * <p>Corpse leg notes: the search uses the death <b>history</b> only to
 * locate - the mutation always lands on the corpse entity's live
 * inventory, never on a historical copy (looted items must not reappear).
 * A corpse in an unloaded chunk is skipped: no force-loads on a damage
 * event, the same locked rule as recall.</p>
 */
@EventBusSubscriber(modid = PseudocoreCurios.MOD_ID)
public final class DeathPreserveEvents {

    private DeathPreserveEvents() {
    }

    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Pre event) {
        if (!PseudocoreCuriosConfig.deathSaveEnabled()) {
            return;
        }
        LivingEntity mob = event.getEntity();
        if (mob.getHealth() - event.getNewDamage() > 0.0F) {
            return; // not lethal - ordinary damage, untouched
        }
        UUID linkage = LinkageManager.taggedLinkage(mob);
        if (linkage == null) {
            return; // not one of ours
        }
        UUID owner = LinkageManager.taggedOwner(mob);
        if (owner == null) {
            return;
        }
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }
        // Damage types live in a dynamic registry, not the built-in one.
        ResourceLocation damageType = level.registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
                .getKey(event.getSource().type());
        if (damageType == null || !PseudocoreCuriosConfig.damageSourceAllowed(
                damageType.toString())) {
            return; // unwhitelisted source: it just kills
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
        if (player == null) {
            return; // records live on the owner's attachment - offline, no save
        }
        LinkageRecord record = LinkageManager.find(player, linkage);
        if (record == null) {
            return;
        }

        ItemStack core = findPreservableCore(player, level, owner, linkage);
        if (core == null) {
            // Not found -> normal death + the issue's actionbar line.
            announceFell(player, record, damageType);
            return;
        }

        InteractionResult captured = core.getItem() instanceof PseudocoreItem
                ? PseudocoreItem.tryCapture(player, mob, core)
                : IafHornAccess.tryCaptureDragon(player, mob, core);
        if (captured != InteractionResult.SUCCESS) {
            // A gate refused (anti-theft, tier limits, ...): the gate has
            // already said its piece and the death proceeds normally - no
            // second message from here.
            return;
        }

        migrateToDeathRecord(core, event, damageType);
        // Capture discarded the mob; keep the death machinery off it.
        event.setNewDamage(0.0F);
        player.displayClientMessage(net.minecraft.network.chat.Component
                .translatable("pseudocore_curios.message.preserved", record.name()), true);
    }

    /**
     * Slot -> inventory -> corpse, each leg narrowed to the linkage match
     * and an empty-of-live-mobs core; the slot always wins over the
     * inventory (the issue's "slot preferred").
     */
    private static ItemStack findPreservableCore(ServerPlayer player, ServerLevel level,
                                                  UUID owner, UUID linkage) {
        // 1. The Curios slot - the hotkey's own home.
        var slot = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player)
                .map(inv -> inv.getCurios().get(PseudocoreCurios.SLOT_ID))
                .map(stacks -> stacks.getStacks().getStackInSlot(0))
                .orElse(null);
        if (isPreservable(slot, linkage)) {
            return slot;
        }

        // 2. The inventory - main storage only: hotbar + main rows, never
        // offhand or armor (the config's declared widening).
        if (PseudocoreCuriosConfig.allowNonSlotCores()) {
            for (ItemStack stack : player.getInventory().items) {
                if (isPreservable(stack, linkage)) {
                    return stack;
                }
            }
        }

        // 3. The corpse - its own class, behind the gate (see CorpseSearch).
        if (PseudocoreCuriosConfig.searchCorpses() && ModList.get().isLoaded("corpse")) {
            ItemStack onCorpse = CorpseSearch.find(level, owner, linkage);
            if (onCorpse != null) {
                return onCorpse;
            }
        }
        return null;
    }

    /**
     * The linkage stamp must be ours and the core must hold no live mob -
     * package-visible so {@link CorpseSearch} narrows identically.
     */
    static boolean isPreservable(ItemStack stack, UUID linkage) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        UUID stamp = stack.get(ModLinkageComponents.LINKAGE.get());
        return linkage.equals(stamp)
                && !stack.has(ModDataComponents.CONTAINED_MOB.get())
                && (!ModList.get().isLoaded("iceandfire")
                || IafHornAccess.storedDragonType(stack) == null);
    }

    /**
     * Capture wrote the live component; move it - with the death facts -
     * into the addon's record and strip the live half, so base pseudocore
     * sees an empty core (release inert, empty art, death tooltip).
     */
    private static void migrateToDeathRecord(ItemStack core, LivingDamageEvent.Pre event,
                                             ResourceLocation damageType) {
        String msgId = event.getSource().getMsgId();
        long now = System.currentTimeMillis();
        ContainedMobComponent live = core.get(ModDataComponents.CONTAINED_MOB.get());
        if (live != null) {
            core.set(ModLinkageComponents.DEAD_MOB.get(), new DeadMobComponent(
                    live.entityType(), live.entityUuid(), live.entityData(),
                    damageType, msgId, now));
            core.remove(ModDataComponents.CONTAINED_MOB.get());
            return;
        }
        // Dragon line: the live half is IaF's horn component - looked up by
        // its stable registry id, never by class (the addon cannot name IaF
        // types), and a no-op where IaF is absent (no dragon core exists).
        ResourceLocation hornId = ResourceLocation.fromNamespaceAndPath("iceandfire", "dragon_horn");
        var hornType = BuiltInRegistries.DATA_COMPONENT_TYPE.getOptional(hornId).orElse(null);
        if (hornType != null && core.has(hornType)) {
            @SuppressWarnings("unchecked")
            var typed = (net.minecraft.core.component.DataComponentType<CompoundTag>) hornType;
            CompoundTag horn = core.get(typed);
            ResourceLocation dragonType = IafHornAccess.storedDragonType(core);
            if (dragonType != null && horn != null) {
                core.set(ModLinkageComponents.DEAD_MOB.get(), new DeadMobComponent(
                        dragonType,
                        IafHornAccess.storedDragonUuid(core) == null
                                ? new UUID(0L, 0L) : IafHornAccess.storedDragonUuid(core),
                        IafHornAccess.storedDragonData(core) == null
                                ? new CompoundTag() : IafHornAccess.storedDragonData(core),
                        damageType, msgId, now));
            }
            core.remove(typed);
        }
    }

    private static void announceFell(ServerPlayer player, LinkageRecord record,
                                     ResourceLocation damageType) {
        // The issue's wording: "Your Blue Fang Direwolf fell to a player
        // attack." - the type id's path reads as that phrase, where the
        // vanilla msgId ("player") would drop the word "attack".
        player.displayClientMessage(net.minecraft.network.chat.Component
                .translatable("pseudocore_curios.message.fell",
                        record.name(), "a " + damageType.getPath().replace('_', ' ')), true);
    }
}
